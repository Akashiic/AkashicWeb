package com.akashiic.web.client.host;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs a short system tool (tasklist, taskkill, ps, kill) with a hard timeout. Used only by the stale-host sweep, so a
 * missing or hung tool costs at most {@link #TIMEOUT_MILLIS} and never stops the host from starting.
 */
final class ToolRunner {

    static final long TIMEOUT_MILLIS = 5000;
    private static final int MAX_OUTPUT = 64 * 1024;

    /** Exit code and combined stdout/stderr of one run. */
    static final class Result {

        final int exitCode;
        final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private ToolRunner() {}

    /** Returns null when the tool could not be started or did not finish in time. */
    static Result run(List<String> command) {
        Process p;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true)
                .start();
        } catch (IOException e) {
            return null;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> copy(p.getInputStream(), bytes), "AkashicWeb-Host-Tool");
        drain.setDaemon(true);
        drain.start();
        try {
            p.getOutputStream()
                .close();
        } catch (IOException e) {
            // stdin of a tool that already exited: harmless.
        }
        try {
            if (!p.waitFor(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            drain.join(1000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread()
                .interrupt();
            return null;
        }
        String out;
        synchronized (bytes) {
            out = new String(bytes.toByteArray(), Charset.defaultCharset());
        }
        return new Result(p.exitValue(), out);
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        byte[] buf = new byte[4096];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                synchronized (out) {
                    if (out.size() < MAX_OUTPUT) out.write(buf, 0, Math.min(n, MAX_OUTPUT - out.size()));
                }
            }
        } catch (IOException e) {
            // The tool closed its pipe; whatever was read is the output.
        }
    }
}
