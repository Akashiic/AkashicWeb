package com.akashiic.web.client.host;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import com.akashiic.web.common.ReasonCode;

/** Shared pieces for the end-to-end tests that run {@link FakeHostMain} as a real child process. */
final class FakeHostFixture {

    final File dir;
    final File modeFile;
    final File recordFile;
    final FakeClock clock = new FakeClock();
    final StatusLog statuses = new StatusLog();

    FakeHostFixture(File dir, String mode) throws IOException {
        this.dir = dir;
        this.modeFile = new File(dir, "mode.txt");
        this.recordFile = new File(dir, "record.txt");
        mode(mode);
    }

    void mode(String mode) throws IOException {
        Files.write(modeFile.toPath(), mode.getBytes(StandardCharsets.UTF_8));
    }

    /** Java command that runs FakeHostMain with only the main and test class directories on its classpath. */
    List<String> command() {
        String java = new File(System.getProperty("java.home"), "bin/java").getAbsolutePath();
        String cp = codeSource(FakeHostMain.class) + File.pathSeparator + codeSource(HostProtocol.class);
        return Arrays.asList(
            java,
            "-Xmx48m",
            "-XX:+UseSerialGC",
            "-XX:TieredStopAtLevel=1",
            "-cp",
            cp,
            FakeHostMain.class.getName(),
            modeFile.getAbsolutePath(),
            recordFile.getAbsolutePath());
    }

    /** Parent environment with variables that must never reach the host, mixed case included. */
    static Map<String, String> pollutedEnvironment() {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("NODE_OPTIONS", "--inspect=9229");
        env.put("ELECTRON_RUN_AS_NODE", "1");
        env.put("Node_Debug_Native", "x");
        env.put("electron_enable_logging", "1");
        env.put("AKW_TOKEN", "stale-token-from-parent");
        return env;
    }

    HostLaunchConfig.Builder config() {
        return HostLaunchConfig
            .builder(
                HostExecutable.fixed(command(), new File(dir, "userdata")),
                new File(dir, "logs"),
                new File(dir, "state"))
            .connectTimeoutMillis(30_000)
            .parentEnvironment(pollutedEnvironment());
    }

    /** Record lines grouped by fake-host pid, in launch order. */
    Map<String, List<String>> records() throws IOException {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (!recordFile.isFile()) return out;
        for (String line : Files.readAllLines(recordFile.toPath(), StandardCharsets.UTF_8)) {
            int sp = line.indexOf(' ');
            out.computeIfAbsent(line.substring(0, sp), k -> new ArrayList<>())
                .add(line.substring(sp + 1));
        }
        return out;
    }

    List<String> allRecords() throws IOException {
        List<String> all = new ArrayList<>();
        for (List<String> l : records().values()) all.addAll(l);
        return all;
    }

    static void await(String what, long millis, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for " + what);
            Thread.sleep(20);
        }
    }

    /**
     * True while process {@code pid} runs. With /proc a zombie counts as ended: it has exited, and a container whose
     * PID 1 does not reap orphans would otherwise keep it "alive" forever. Without /proc, the platform's view.
     */
    static boolean running(long pid) {
        File stat = new File("/proc/" + pid + "/stat");
        if (new File("/proc/self/stat").isFile()) {
            try {
                String s = new String(Files.readAllBytes(stat.toPath()), StandardCharsets.UTF_8);
                String state = s.substring(s.lastIndexOf(')') + 1)
                    .trim()
                    .split(" ")[0];
                return !state.equals("Z") && !state.equals("X");
            } catch (IOException | RuntimeException e) {
                return false; // gone
            }
        }
        return ProcessControl.forThisPlatform()
            .imageName(pid) != null;
    }

    static boolean anyLineStartsWith(List<String> lines, String prefix) {
        for (String l : lines) if (l.startsWith(prefix)) return true;
        return false;
    }

    private static String codeSource(Class<?> c) {
        try {
            return new File(
                c.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()).getAbsolutePath();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Backoff clock that never waits: each sleep is recorded and advances time. */
    static final class FakeClock implements HostClock {

        final AtomicLong now = new AtomicLong(1_000_000L);
        final List<Long> sleeps = Collections.synchronizedList(new ArrayList<>());

        @Override
        public long nowMillis() {
            return now.get();
        }

        @Override
        public void sleep(long millis) throws InterruptedException {
            sleeps.add(millis);
            now.addAndGet(millis);
            if (Thread.interrupted()) throw new InterruptedException();
        }

        List<Long> sleeps() {
            synchronized (sleeps) {
                return new ArrayList<>(sleeps);
            }
        }
    }

    /** Every status the host reported, in order. */
    static final class StatusLog implements HostListener {

        final List<HostStatus> seen = new CopyOnWriteArrayList<>();

        @Override
        public void onStatus(HostStatus status) {
            seen.add(status);
        }

        boolean saw(HostStatus.Kind kind, ReasonCode reason) {
            for (HostStatus s : seen) if (s.kind == kind && s.reason == reason) return true;
            return false;
        }

        int count(HostStatus.Kind kind) {
            int n = 0;
            for (HostStatus s : seen) if (s.kind == kind) n++;
            return n;
        }
    }
}
