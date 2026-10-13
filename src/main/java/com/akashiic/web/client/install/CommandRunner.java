package com.akashiic.web.client.install;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs a short system command (Windows {@code reg.exe}, {@code cmd.exe}) and returns its output. An interface so the
 * Smart App Control and short-path logic can be tested on Linux with canned outputs.
 */
public interface CommandRunner {

    final class Result {

        public final int exitCode;
        public final String output;

        public Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }

    /**
     * @param workDir working directory, or null to inherit
     * @throws IOException if the command could not start, or did not finish within {@code timeoutMs}
     */
    Result run(List<String> command, File workDir, long timeoutMs) throws IOException;

    /** The real runner: stdout and stderr merged, output capped at 64 KiB, killed on timeout. */
    CommandRunner SYSTEM = new CommandRunner() {

        @Override
        public Result run(List<String> command, File workDir, long timeoutMs) throws IOException {
            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
            if (workDir != null) pb.directory(workDir);
            Process p = pb.start();
            p.getOutputStream()
                .close();
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final InputStream in = p.getInputStream();
            // Read on a helper thread so a command that never closes its stdout cannot block past the timeout.
            Thread reader = new Thread(() -> {
                byte[] buf = new byte[4096];
                int n;
                try {
                    while ((n = in.read(buf)) != -1) {
                        synchronized (out) {
                            if (out.size() < 64 * 1024) out.write(buf, 0, n);
                        }
                    }
                } catch (IOException ignored) {
                    // Boundary: the process was killed or closed its pipe; whatever was read is the output.
                }
            }, "AkashicWeb-Installer-Cmd");
            reader.setDaemon(true);
            reader.start();
            try {
                if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    p.destroy();
                    throw new IOException("timed out after " + timeoutMs + " ms: " + command.get(0));
                }
                reader.join(1000);
            } catch (InterruptedException e) {
                p.destroy();
                Thread.currentThread()
                    .interrupt();
                throw new IOException("interrupted: " + command.get(0), e);
            }
            synchronized (out) {
                // Windows console tools print in the OEM code page; the values we parse are ASCII either way.
                return new Result(p.exitValue(), new String(out.toByteArray(), StandardCharsets.ISO_8859_1));
            }
        }
    };
}
