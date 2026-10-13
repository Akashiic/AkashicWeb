package com.akashiic.web.client.host;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stand-in for AkashicWebHost in the supervisor tests: a separate JVM that speaks protocol v1 like the real host
 * (token from stdin, HELLO, then reacts to commands) and writes what it saw to a record file. Behaviour is chosen by
 * a mode file read at start, so a test can change it between launches.
 *
 * <p>
 * Args: {@code <modeFile> <recordFile> --akw-port=N --akw-parent-pid=P --akw-user-data=D --akw-log=L}. Modes:
 * {@code ok}, {@code exit-after-welcome}, {@code bad-token}, {@code bad-major}, {@code silent} (never connects),
 * {@code die} (exits at once), {@code env-token} (uses AKW_TOKEN, ignores stdin), {@code garbage} (bad magic after
 * HELLO), {@code hang-once} (the first launch stops reading after WELCOME, like a hung main thread that keeps its
 * socket open; later launches behave like {@code ok}), {@code slow-pong:<ms>} (answers every PING after a delay),
 * {@code oversize} (CREATE first gets a FRAME of a 4097x16 page, then the normal answer), {@code stubborn-hang-once}
 * (like {@code hang-once}, but the first launch also ignores SIGTERM and starts a child process, "child PID"). In
 * every connected mode: CREATE answers with a full FRAME, a STATE and a PCM chunk; DESTROY with a late FRAME and a
 * STATE {@code {"late":true}}; PING with PONG; QUIT with BYE and exit; NAVIGATE {@code {"action":"stop"}} makes it exit
 * (a simulated crash).
 */
public final class FakeHostMain {

    private static final Pattern NUMBER = Pattern.compile("\"(width|height)\":(\\d+)");
    private static final Pattern NONCE = Pattern.compile("\"nonce\":(\\d+)");

    private static File record;
    private static long pid;

    private FakeHostMain() {}

    public static void main(String[] args) throws Exception {
        String mode = new String(Files.readAllBytes(new File(args[0]).toPath()), StandardCharsets.UTF_8).trim();
        record = new File(args[1]);
        pid = HostCommandLine.currentPid();
        int port = 0;
        List<String> akw = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            akw.add(args[i]);
            if (args[i].startsWith("--akw-port=")) port = Integer.parseInt(args[i].substring("--akw-port=".length()));
        }
        record("start mode=" + mode + " args=" + String.join(" ", akw));
        record("env " + envReport());
        if (mode.equals("die")) System.exit(3);
        if (mode.equals("stubborn-hang-once") && !new File(args[0] + ".hung").exists()) {
            // Like a host whose main thread hangs: SIGTERM is not acted on, and it has a child of its own.
            ignoreSigterm();
            Process child = new ProcessBuilder("sleep", "300").start();
            record("child " + ProcessControl.reflectivePid(child));
            mode = "hang-once";
        }

        String stdinToken = readLine(System.in);
        record("stdin-token " + (stdinToken == null ? "none" : "len" + stdinToken.length()));
        if (mode.equals("silent")) {
            Thread.sleep(120_000);
            return;
        }
        String token = mode.equals("env-token") ? System.getenv("AKW_TOKEN") : stdinToken;
        record("env-token-equal " + (stdinToken != null && stdinToken.equals(System.getenv("AKW_TOKEN"))));

        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setTcpNoDelay(true);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            String helloToken = mode.equals("bad-token") ? "00000000000000000000000000000000" : token;
            int major = mode.equals("bad-major") ? 99 : 1;
            HostProtocol.writeJson(
                out,
                HostProtocol.HELLO,
                0,
                0,
                "{\"token\":\"" + helloToken
                    + "\",\"protoMajor\":"
                    + major
                    + ",\"protoMinor\":0,\"hostVersion\":\"fake-1\",\"engine\":\"fake\","
                    + "\"engineVersion\":\"0\",\"chromeVersion\":\"0\",\"platform\":\"test\",\"pid\":"
                    + pid
                    + "}");
            if (mode.equals("garbage")) out.writeInt(0xDEADBEEF);
            out.flush();
            int seq = 0;
            while (true) {
                HostProtocol.Message m = HostProtocol.read(in);
                if (m == null) {
                    record("eof");
                    return;
                }
                record("recv " + m.type + " " + m.screenId + " " + m.seq + " " + m.text());
                switch (m.type) {
                    case HostProtocol.WELCOME: {
                        if (mode.equals("exit-after-welcome")) {
                            record("exiting");
                            System.exit(0);
                        }
                        File hung = new File(args[0] + ".hung");
                        if (mode.equals("hang-once") && hung.createNewFile()) {
                            record("hanging");
                            Thread.sleep(120_000); // never reads again; the socket stays open
                            return;
                        }
                        break;
                    }
                    case HostProtocol.CREATE: {
                        int w = field(m.text(), "width");
                        int h = field(m.text(), "height");
                        if (mode.equals("oversize")) {
                            // A page wider than 4096 (a 1x1 dirty rect, so the payload itself is tiny).
                            byte[] big = new byte[HostProtocol.FrameHeader.BYTES + 4];
                            int[] head = { 4097, 16, 0, 0, 1, 1 };
                            for (int i = 0; i < head.length; i++) {
                                big[i * 2] = (byte) (head[i] >> 8);
                                big[i * 2 + 1] = (byte) head[i];
                            }
                            HostProtocol.write(out, HostProtocol.FRAME, m.screenId, seq++, big);
                        }
                        HostProtocol.write(out, HostProtocol.FRAME, m.screenId, seq++, frame(m.screenId, w, h));
                        HostProtocol.writeJson(
                            out,
                            HostProtocol.STATE,
                            m.screenId,
                            seq++,
                            "{\"url\":\"fake\",\"title\":\"Fake ação\",\"loading\":false}");
                        HostProtocol.write(out, HostProtocol.PCM, m.screenId, seq++, pcm());
                        out.flush();
                        break;
                    }
                    case HostProtocol.DESTROY:
                        // A paint that was already on its way: the game must discard it. The STATE marks its passage.
                        HostProtocol.write(out, HostProtocol.FRAME, m.screenId, seq++, frame(m.screenId, 8, 8));
                        HostProtocol.writeJson(out, HostProtocol.STATE, m.screenId, seq++, "{\"late\":true}");
                        out.flush();
                        break;
                    case HostProtocol.NAVIGATE:
                        if (m.text()
                            .contains("\"action\":\"stop\"")) {
                            record("crashing");
                            System.exit(0);
                        }
                        break;
                    case HostProtocol.PING: {
                        Matcher n = NONCE.matcher(m.text());
                        String nonce = n.find() ? n.group(1) : "0";
                        if (mode.startsWith("slow-pong:")) {
                            Thread.sleep(Long.parseLong(mode.substring("slow-pong:".length())));
                        }
                        HostProtocol
                            .writeJson(out, HostProtocol.PONG, 0, seq++, "{\"nonce\":" + nonce + ",\"hostTimeMs\":1}");
                        out.flush();
                        break;
                    }
                    case HostProtocol.QUIT:
                        HostProtocol.writeJson(out, HostProtocol.BYE, 0, seq++, "{\"reason\":\"quit\"}");
                        out.flush();
                        record("bye");
                        System.exit(0);
                        break;
                    default:
                        break;
                }
            }
        }
    }

    /** Installs a SIGTERM handler that does nothing (sun.misc.Signal, by reflection: Java 8 and 9+ alike). */
    private static void ignoreSigterm() throws ReflectiveOperationException {
        Class<?> signal = Class.forName("sun.misc.Signal");
        Class<?> handler = Class.forName("sun.misc.SignalHandler");
        Object term = signal.getConstructor(String.class)
            .newInstance("TERM");
        Object ignore = java.lang.reflect.Proxy
            .newProxyInstance(handler.getClassLoader(), new Class<?>[] { handler }, (proxy, method, a) -> null);
        signal.getMethod("handle", signal, handler)
            .invoke(null, term, ignore);
    }

    /** Full frame where pixel (x, y) of screen s holds bytes {s, x, y, 0xFF}. */
    static byte[] frame(int screenId, int w, int h) {
        byte[] p = new byte[HostProtocol.FrameHeader.BYTES + w * h * 4];
        int[] head = { w, h, 0, 0, w, h };
        for (int i = 0; i < head.length; i++) {
            p[i * 2] = (byte) (head[i] >> 8);
            p[i * 2 + 1] = (byte) head[i];
        }
        int o = HostProtocol.FrameHeader.BYTES;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                p[o++] = (byte) screenId;
                p[o++] = (byte) x;
                p[o++] = (byte) y;
                p[o++] = (byte) 0xFF;
            }
        }
        return p;
    }

    /** PCM: pts 123456 us, 48 kHz, 2 channels, 2 frames of samples {1, -1, 300, -300}. */
    static byte[] pcm() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        try {
            d.writeLong(123456L);
            d.writeInt(48000);
            d.writeShort(2);
            d.writeShort(2);
            for (int s : new int[] { 1, -1, 300, -300 }) {
                d.writeByte(s & 0xFF);
                d.writeByte((s >> 8) & 0xFF);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return b.toByteArray();
    }

    private static int field(String json, String name) {
        Matcher m = NUMBER.matcher(json);
        while (m.find()) if (m.group(1)
            .equals(name)) return Integer.parseInt(m.group(2));
        return 1;
    }

    /** Names of NODE_* / ELECTRON_* variables this process can see, and whether AKW_TOKEN is set. */
    private static String envReport() {
        TreeSet<String> bad = new TreeSet<>();
        for (Map.Entry<String, String> e : System.getenv()
            .entrySet()) {
            String u = e.getKey()
                .toUpperCase(Locale.ROOT);
            if (u.startsWith("NODE_") || u.startsWith("ELECTRON_")) bad.add(e.getKey());
        }
        return "bad=" + bad + " akw=" + (System.getenv("AKW_TOKEN") != null);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') b.write(c);
        if (c < 0 && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static synchronized void record(String line) {
        try (FileOutputStream f = new FileOutputStream(record, true)) {
            f.write(("pid=" + pid + " " + line + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("fake host cannot record: " + e);
        }
    }
}
