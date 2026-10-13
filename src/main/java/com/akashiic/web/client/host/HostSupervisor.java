package com.akashiic.web.client.host;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.protocol.Protocol;

/**
 * Owns the AkashicWebHost process for the whole game session (ADR-001, ADR-003, docs/PROTOCOL.md section 1).
 *
 * <p>
 * One daemon thread, {@value #THREAD}, runs the loop: sweep hosts left by an earlier session (once), listen on
 * 127.0.0.1:0 with backlog 1, spawn the host with a sanitised environment, hand it a fresh token on stdin, accept
 * exactly one connection, check token (constant time) and protocol major, then hand the connection to
 * {@link Callbacks#onConnected} and report READY. When the connection ends the host is relaunched after 1, 2, 4 ... 60
 * s; five exits within ten minutes (HOST_CRASH_LOOP, or HOST_LAUNCH_FAILED when not one of them got as far as
 * starting), a protocol-major mismatch or a missing engine disable it until {@link #reenable()}. A JVM shutdown hook
 * closes the socket and kills the child as a last resort.
 *
 * <p>
 * Status changes are reported on the supervisor thread through {@link Callbacks#onStatus}.
 */
public final class HostSupervisor {

    public static final String THREAD = "AkashicWeb-Host-Supervisor";

    /** Upper bound for a HELLO payload: it is a handful of short strings. */
    static final int MAX_HELLO_BYTES = 16 * 1024;
    /** How long a host that lost its connection may take to exit on its own before it is killed. */
    static final long EXIT_GRACE_MILLIS = 2000;
    /** QUIT flush budget at shutdown. */
    static final long QUIT_FLUSH_MILLIS = 300;
    /** Liveness ping while connected; feeds /web doctor (round trip, unanswered pings). */
    static final long PING_INTERVAL_MILLIS = 10_000;

    private static final long ACCEPT_POLL_MILLIS = 100;
    private static final Logger LOG = LogManager.getLogger("AkashicWeb/Host");

    /** Hooks into the browser-host layer. All are called on the supervisor thread. */
    public interface Callbacks {

        /** Right after a good handshake, before READY: queue WELCOME first, then replay screens. Must not block. */
        void onConnected(HostConnection connection, HelloInfo hello);

        /** After the connection closed, before the next status. */
        void onDisconnected(HostConnection connection);

        void onStatus(HostStatus status);
    }

    /** How one run ended. */
    private static final class RunResult {

        final HostFailure failure;
        String detail;
        final boolean healthy;

        RunResult(HostFailure failure, String detail, boolean healthy) {
            this.failure = failure;
            this.detail = detail == null ? "" : detail;
            this.healthy = healthy;
        }
    }

    /** A run that ended before a connection existed. */
    private static final class RunFailure extends Exception {

        private static final long serialVersionUID = 1L;
        final HostFailure failure;

        RunFailure(HostFailure failure, String detail) {
            super(detail, null, false, false);
            this.failure = failure;
        }
    }

    private final HostLaunchConfig config;
    private final HostClock clock;
    private final ProcessControl processes;
    private final HostConnection.Inbound inbound;
    private final Callbacks callbacks;
    private final Backoff backoff = new Backoff();
    private final CrashLoopGuard crashGuard;
    private final HostPidFile pidFile;
    private final long ourPid;

    private final Object monitor = new Object();
    // Guarded by monitor.
    private Thread thread;
    private Thread shutdownHook;
    private boolean stopping;
    private boolean disabled;
    private boolean sleeping;
    private Process process;
    private HostConnection connection;

    private volatile HostStatus status = HostStatus.STARTING;
    private volatile HostFailure lastFailure;
    private volatile String lastFailureDetail = "";
    private volatile HelloInfo lastHello;
    private volatile long hostPid = -1;
    private volatile int launches;
    private volatile long lastRttMillis = -1;

    public HostSupervisor(HostLaunchConfig config, HostClock clock, ProcessControl processes,
        HostConnection.Inbound inbound, Callbacks callbacks) {
        this(config, clock, processes, inbound, callbacks, new CrashLoopGuard());
    }

    HostSupervisor(HostLaunchConfig config, HostClock clock, ProcessControl processes, HostConnection.Inbound inbound,
        Callbacks callbacks, CrashLoopGuard crashGuard) {
        this.config = config;
        this.clock = clock;
        this.processes = processes;
        this.inbound = inbound;
        this.callbacks = callbacks;
        this.crashGuard = crashGuard;
        this.pidFile = new HostPidFile(config.pidFile());
        this.ourPid = HostCommandLine.currentPid();
    }

    // --- public control ----------------------------------------------------------------------------------------

    /** Starts the supervisor thread (once). Non-blocking. */
    public void start() {
        synchronized (monitor) {
            if (stopping || thread != null) return;
            thread = new Thread(this::runLoop, THREAD);
            thread.setDaemon(true);
            shutdownHook = new Thread(this::emergencyStop, "AkashicWeb-Host-ShutdownHook");
            try {
                Runtime.getRuntime()
                    .addShutdownHook(shutdownHook);
            } catch (IllegalStateException | SecurityException e) {
                shutdownHook = null; // JVM already exiting, or a security manager: the socket EOF still ends the host
            }
            thread.start();
        }
    }

    /**
     * Leaves DISABLED (crash loop, protocol mismatch, missing engine), resets the backoff and launches now. Also cuts a
     * running backoff short. Starts the supervisor if it was never started.
     */
    public void reenable() {
        synchronized (monitor) {
            if (stopping) return;
            crashGuard.clear();
            backoff.reset();
            disabled = false;
            monitor.notifyAll();
            if (sleeping && thread != null) thread.interrupt();
        }
        start();
    }

    /**
     * Stops for good: sends QUIT, closes the connection, waits briefly for the host to exit and kills it otherwise.
     * Blocks the caller for at most about 3.5 s (game exit only).
     */
    public void shutdown() {
        Thread t;
        HostConnection c;
        synchronized (monitor) {
            if (stopping) return;
            stopping = true;
            t = thread;
            c = connection;
            monitor.notifyAll();
        }
        if (c != null) {
            c.sendQuit();
            c.flushAndClose(QUIT_FLUSH_MILLIS);
        }
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try {
                t.join(EXIT_GRACE_MILLIS + 1000);
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
            }
        }
        Process p;
        synchronized (monitor) {
            p = process;
        }
        if (p != null) killNow(p); // the supervisor thread did not finish in time
        removeShutdownHook();
        publish(HostStatus.down(ReasonCode.OK));
    }

    public HostStatus status() {
        return status;
    }

    /** Lines for /web doctor. Never contains the token. */
    public List<String> diagnostics() {
        List<String> out = new ArrayList<>();
        out.add("host status: " + status);
        HelloInfo hello = lastHello;
        out.add("host: " + (hello == null ? "never connected" : hello.toString()));
        out.add("host pid: " + (hostPid > 0 ? Long.toString(hostPid) : "unknown"));
        out.add(
            "launches: " + launches
                + ", exits in the last 10 min: "
                + crashGuard.recentExits(clock.nowMillis())
                + "/"
                + CrashLoopGuard.DEFAULT_LIMIT);
        HostFailure f = lastFailure;
        out.add("last failure: " + (f == null ? "none" : f + " -> " + f.reason() + " (" + lastFailureDetail + ")"));
        out.add("ping round trip: " + (lastRttMillis < 0 ? "n/a" : lastRttMillis + " ms"));
        out.add(
            "logs: " + config.hostLogFile()
                .getAbsolutePath()
                + ", "
                + config.stdioLogFile()
                    .getAbsolutePath());
        return out;
    }

    public HostFailure lastFailure() {
        return lastFailure;
    }

    public String lastFailureDetail() {
        return lastFailureDetail;
    }

    public int launches() {
        return launches;
    }

    /** PID of the current or last host, or -1 when unknown. */
    public long hostPid() {
        return hostPid;
    }

    // --- supervisor thread -------------------------------------------------------------------------------------

    private void runLoop() {
        sweepStaleHosts();
        while (true) {
            synchronized (monitor) {
                while (disabled && !stopping) {
                    try {
                        monitor.wait();
                    } catch (InterruptedException e) {
                        // shutdown() or reenable(): re-check the flags.
                    }
                }
                if (stopping) break;
            }
            Thread.interrupted(); // drop an interrupt meant for a sleep that already ended

            List<String> command;
            ReasonCode unavailable;
            boolean noUserData;
            try {
                command = config.executable.command();
                unavailable = command == null || command.isEmpty() ? config.executable.unavailableReason() : null;
                noUserData = config.executable.userDataDir() == null;
            } catch (Throwable t) {
                // Boundary: the engine locator is another module's code; whatever it throws, keep supervising.
                LOG.error("AkashicWeb: the engine locator failed", t);
                disable(HostFailure.ENGINE_UNAVAILABLE, ReasonCode.ENGINE_ABSENT, "engine locator failed: " + t);
                continue;
            }
            if (command == null || command.isEmpty()) {
                disable(HostFailure.ENGINE_UNAVAILABLE, unavailable, "engine not ready");
                continue;
            }
            if (noUserData) {
                disable(HostFailure.ENGINE_UNAVAILABLE, ReasonCode.ENGINE_ABSENT, "no user data directory");
                continue;
            }

            RunResult r;
            try {
                r = runOnce(command);
            } catch (Throwable t) {
                // Boundary: a bug or an Error in one run (a callback's LinkageError, an OutOfMemoryError) must not kill
                // the supervisor and leave the game without a host, or with a stale READY, for the rest of the session.
                // runOnce's finally has already closed the connection and stopped the process.
                LOG.error("AkashicWeb: host run failed unexpectedly", t);
                r = new RunResult(HostFailure.INTERNAL_ERROR, "internal: " + t, false);
            }
            if (isStopping()) break;

            lastFailure = r.failure;
            lastFailureDetail = r.detail;
            LOG.warn("AkashicWeb: browser host ended: {} ({})", r.failure, r.detail);
            if (r.failure.disables()) {
                disable(r.failure, r.failure.reason(), r.detail);
                continue;
            }
            long now = clock.nowMillis();
            if (crashGuard.recordExit(now, r.failure)) {
                if (crashGuard.allRecentWere(HostFailure.LAUNCH_FAILED, now)) {
                    // The host never even started (antivirus block, missing file): say so instead of "crashing".
                    disable(HostFailure.LAUNCH_FAILED, ReasonCode.HOST_LAUNCH_FAILED, r.failure + ": " + r.detail);
                } else {
                    disable(HostFailure.CRASH_LOOP, ReasonCode.HOST_CRASH_LOOP, r.failure + ": " + r.detail);
                }
                continue;
            }
            if (r.healthy) backoff.reset();
            long delay;
            synchronized (monitor) {
                delay = backoff.next();
            }
            publish(HostStatus.down(r.failure.reason()));
            sleepBackoff(delay);
        }
    }

    private RunResult runOnce(List<String> command) {
        publish(HostStatus.STARTING);
        String token = HostToken.generate();
        Process p = null;
        long pid = -1;
        HostConnection conn = null;
        boolean connected = false;
        RunResult result = null;
        try {
            Socket socket;
            try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }))) {
                List<String> args = HostCommandLine.build(
                    command,
                    server.getLocalPort(),
                    ourPid,
                    config.executable.userDataDir(),
                    config.hostLogFile());
                p = launch(args, token);
                pid = processes.pidOf(p);
                recordPid(pid, command.get(0));
                writeToken(p, token);
                socket = acceptOne(server, p);
            } // The listener closes here: exactly one connection is ever accepted per launch.

            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
            HelloInfo hello = handshake(socket, in, token);
            if (pid <= 0 && hello.pid > 0) {
                pid = hello.pid; // Java 8 on Windows: trust the authenticated host for its own pid
                recordPid(pid, command.get(0));
            }
            try {
                conn = new HostConnection(socket, in, inbound);
            } catch (IOException e) {
                closeQuietly(socket);
                throw new RunFailure(HostFailure.CONNECTION_LOST, "socket unusable after handshake: " + e);
            }
            synchronized (monitor) {
                if (stopping) throw new RunFailure(HostFailure.SHUTDOWN, "stopping");
                connection = conn;
            }
            lastHello = hello;
            hostPid = pid;
            LOG.info("AkashicWeb: browser host connected: {}", hello);
            callbacks.onConnected(conn, hello);
            conn.start();
            connected = true;
            long connectedAt = clock.nowMillis();
            publish(HostStatus.READY);
            waitWhileConnected(conn);
            callbacks.onDisconnected(conn);
            boolean healthy = clock.nowMillis() - connectedAt >= config.stableRunMillis;
            HostFailure why = conn.closeReason() == null ? HostFailure.CONNECTION_LOST : conn.closeReason();
            // Report DOWN now, not after the exit grace below, so placeholders appear as soon as frames stop.
            if (!isStopping() && !why.disables()) {
                lastFailure = why;
                lastFailureDetail = conn.closeDetail();
                publish(HostStatus.down(why.reason()));
            }
            result = new RunResult(why, conn.closeDetail(), healthy);
        } catch (RunFailure f) {
            result = new RunResult(f.failure, f.getMessage(), false);
        } catch (IOException e) {
            result = new RunResult(HostFailure.LAUNCH_FAILED, "loopback listen/accept: " + e, false);
        } finally {
            if (conn != null) {
                conn.close(HostFailure.SHUTDOWN, "run ended");
                synchronized (monitor) {
                    if (connection == conn) connection = null;
                }
            }
            String exit = stopProcess(p, pid, connected ? EXIT_GRACE_MILLIS : 0);
            if (result != null && exit != null) result.detail = result.detail + "; " + exit;
        }
        return result;
    }

    private Process launch(List<String> args, String token) throws RunFailure {
        File stdio = config.stdioLogFile();
        try {
            mkdirs(config.logDir);
            File userData = config.executable.userDataDir();
            if (userData != null) mkdirs(userData);
            rotate(stdio);
            ProcessBuilder pb = new ProcessBuilder(args);
            Map<String, String> env = pb.environment();
            env.clear();
            env.putAll(ChildEnvironment.build(config.parentEnvironment, token));
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(stdio));
            pb.redirectInput(ProcessBuilder.Redirect.PIPE);
            Process p = pb.start();
            launches++;
            boolean stop;
            synchronized (monitor) {
                stop = stopping;
                if (!stop) process = p;
            }
            if (stop) {
                killNow(p); // shutdown raced with the launch: never leave this child behind
                throw new RunFailure(HostFailure.SHUTDOWN, "stopping");
            }
            return p;
        } catch (IOException | RuntimeException e) {
            // RuntimeException: ProcessBuilder throws IllegalArgumentException/SecurityException for unusable commands.
            throw new RunFailure(HostFailure.LAUNCH_FAILED, e.toString());
        }
    }

    /** The token goes through stdin so it never shows in the process list; AKW_TOKEN is the fallback. */
    private static void writeToken(Process p, String token) {
        try (OutputStream stdin = p.getOutputStream()) {
            stdin.write((token + "\n").getBytes(StandardCharsets.US_ASCII));
            stdin.flush();
        } catch (IOException e) {
            // The host exited or closed stdin early: acceptOne notices an exit, and AKW_TOKEN covers a closed stdin.
            LOG.debug("AkashicWeb: could not write the token to the host's stdin: {}", e.toString());
        }
    }

    private Socket acceptOne(ServerSocket server, Process p) throws IOException, RunFailure {
        server.setSoTimeout((int) ACCEPT_POLL_MILLIS);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.connectTimeoutMillis);
        while (true) {
            if (isStopping()) throw new RunFailure(HostFailure.SHUTDOWN, "stopping");
            try {
                return server.accept();
            } catch (SocketTimeoutException e) {
                if (!p.isAlive()) throw new RunFailure(HostFailure.EXITED_BEFORE_CONNECT, "exited before connecting");
                if (System.nanoTime() - deadline > 0) throw new RunFailure(
                    HostFailure.CONNECT_TIMEOUT,
                    "no connection within " + config.connectTimeoutMillis + " ms");
            }
        }
    }

    /** Reads HELLO from {@code in}; on any failure the socket is closed and a {@link RunFailure} thrown. */
    private HelloInfo handshake(Socket socket, DataInputStream in, String token) throws RunFailure {
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, config.connectTimeoutMillis));
            HostProtocol.Header h = HostProtocol.readHeader(in);
            if (h == null) throw new RunFailure(HostFailure.BAD_HELLO, "closed before HELLO");
            if (h.type != HostProtocol.HELLO)
                throw new RunFailure(HostFailure.BAD_HELLO, "first message type " + h.type);
            if (h.length > MAX_HELLO_BYTES)
                throw new RunFailure(HostFailure.BAD_HELLO, "HELLO of " + h.length + " bytes");
            byte[] payload = new byte[h.length];
            in.readFully(payload);
            String[] presented = new String[1];
            HelloInfo hello = HelloInfo.parse(new String(payload, StandardCharsets.UTF_8), presented);
            // Token first: an unauthenticated peer learns nothing else, not even whether its version would fit.
            if (!HostToken.matches(token, presented[0])) throw new RunFailure(HostFailure.BAD_TOKEN, "token mismatch");
            if (!Protocol.compatible(hello.protoMajor)) throw new RunFailure(
                HostFailure.PROTOCOL_MAJOR,
                "host speaks protocol " + hello.protoMajor + ", the mod speaks " + Protocol.MAJOR);
            socket.setSoTimeout(0);
            return hello;
        } catch (RunFailure f) {
            closeQuietly(socket);
            throw f;
        } catch (SocketTimeoutException e) {
            closeQuietly(socket);
            throw new RunFailure(HostFailure.BAD_HELLO, "no HELLO within " + config.connectTimeoutMillis + " ms");
        } catch (IOException e) {
            closeQuietly(socket);
            throw new RunFailure(HostFailure.BAD_HELLO, e.getMessage());
        }
    }

    private void waitWhileConnected(HostConnection conn) {
        boolean warnedUnresponsive = false;
        while (true) {
            try {
                if (conn.awaitClosed(PING_INTERVAL_MILLIS)) return;
            } catch (InterruptedException e) {
                if (isStopping()) return; // shutdown() closes the connection itself
                continue;
            }
            lastRttMillis = conn.lastRttMillis();
            if (conn.pongOverdue(3 * PING_INTERVAL_MILLIS) && !warnedUnresponsive) {
                // Diagnostics only: a GC pause or a busy page can delay a PONG, so we never kill on this alone.
                LOG.warn("AkashicWeb: the browser host has not answered a ping for 30 s");
                warnedUnresponsive = true;
            }
            if (!conn.pongOverdue(PING_INTERVAL_MILLIS)) conn.sendPing();
        }
    }

    /** Waits up to {@code graceMillis} for the host to exit, then kills it; returns how it ended. */
    private String stopProcess(Process p, long pid, long graceMillis) {
        if (p == null) return null;
        if (graceMillis > 0) waitQuietly(p, graceMillis);
        if (p.isAlive()) killNow(p);
        String how = p.isAlive() ? "still running after kill" : "exit code " + p.exitValue();
        if (pid > 0 && !p.isAlive()) pidFile.remove(pid);
        synchronized (monitor) {
            if (process == p) process = null;
        }
        return how;
    }

    private static void killNow(Process p) {
        p.destroy();
        if (!waitQuietly(p, 500)) {
            p.destroyForcibly();
            waitQuietly(p, 1000);
        }
    }

    /** Process.waitFor that finishes its bounded wait even if interrupted, then restores the interrupt. */
    private static boolean waitQuietly(Process p, long millis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        boolean interrupted = false;
        try {
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0) return !p.isAlive();
                try {
                    return p.waitFor(left, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread()
                .interrupt();
        }
    }

    private void sleepBackoff(long millis) {
        synchronized (monitor) {
            if (stopping) return;
            sleeping = true;
        }
        try {
            clock.sleep(millis);
        } catch (InterruptedException e) {
            // reenable() (launch now) or shutdown() (loop exits).
        } finally {
            synchronized (monitor) {
                sleeping = false;
            }
            Thread.interrupted();
        }
    }

    private void disable(HostFailure failure, ReasonCode reason, String detail) {
        lastFailure = failure;
        lastFailureDetail = detail;
        synchronized (monitor) {
            disabled = true;
        }
        LOG.warn("AkashicWeb: browser host disabled: {} ({})", reason, detail);
        publish(HostStatus.disabled(reason));
    }

    private void publish(HostStatus s) {
        HostStatus old = status;
        status = s;
        if (old != null && old.kind == s.kind && old.reason == s.reason) return;
        try {
            callbacks.onStatus(s);
        } catch (Throwable t) {
            // Boundary: whatever a status listener throws, even an Error, the supervisor keeps supervising.
            LOG.error("AkashicWeb: host status listener failed", t);
        }
    }

    private void sweepStaleHosts() {
        try {
            int killed = new StaleHostSweeper(pidFile, processes, ourPid).sweep();
            if (killed > 0) LOG.info("AkashicWeb: ended {} browser host(s) left by an earlier session", killed);
        } catch (Throwable t) {
            // Boundary: the sweep is best effort and must never keep the host from starting.
            LOG.warn("AkashicWeb: stale host sweep failed: {}", t.toString());
        }
    }

    private void recordPid(long pid, String executable) {
        if (pid <= 0) return;
        hostPid = pid;
        pidFile
            .add(new HostPidFile.Entry(pid, ourPid, System.currentTimeMillis(), ProcessControl.fileName(executable)));
    }

    /** JVM shutdown hook: no waiting on the host's goodbye, just close and kill. */
    private void emergencyStop() {
        HostConnection c;
        Process p;
        synchronized (monitor) {
            stopping = true;
            c = connection;
            p = process;
        }
        if (c != null) c.close(HostFailure.SHUTDOWN, "JVM exit");
        if (p != null) killNow(p);
    }

    private void removeShutdownHook() {
        Thread hook;
        synchronized (monitor) {
            hook = shutdownHook;
            shutdownHook = null;
        }
        if (hook == null) return;
        try {
            Runtime.getRuntime()
                .removeShutdownHook(hook);
        } catch (IllegalStateException | SecurityException e) {
            // Already shutting down: the hook runs and finds nothing left to do.
        }
    }

    private boolean isStopping() {
        synchronized (monitor) {
            return stopping;
        }
    }

    private static void rotate(File log) {
        if (log.isFile() && log.length() > HostLaunchConfig.STDIO_LOG_ROTATE_BYTES) {
            File old = new File(log.getPath() + ".1");
            if (old.exists() && !old.delete()) return;
            if (!log.renameTo(old)) LOG.debug("AkashicWeb: could not rotate {}", log);
        }
    }

    private static void mkdirs(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("cannot create " + dir);
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException e) {
            // The handshake already failed; the close result changes nothing.
        }
    }
}
