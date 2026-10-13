package com.akashiic.web.client.install;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;

/**
 * A second process that holds the shared {@link AppUseLock} on a file, the way another game instance does. Runs with
 * only the test classes on its classpath and JDK classes in its body.
 */
public final class AppLockHolder {

    private AppLockHolder() {}

    public static void main(String[] args) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(args[0], "rw");
            FileLock lock = raf.getChannel()
                .lock(0, Long.MAX_VALUE, true)) {
            System.out.println("locked " + lock.isShared());
            System.out.flush();
            while (System.in.read() != -1) {
                // Hold the lock until the parent kills this process or closes its stdin.
            }
        }
    }

    /** Starts the holder on {@code lockFile} and returns once it holds the lock. */
    static Process start(File lockFile) throws Exception {
        String java = new File(System.getProperty("java.home"), "bin/java").getAbsolutePath();
        String cp = new File(
            AppLockHolder.class.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI()).getPath();
        Process p = new ProcessBuilder(java, "-Xmx32m", "-cp", cp, AppLockHolder.class.getName(), lockFile.getPath())
            .redirectErrorStream(true)
            .start();
        BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = out.readLine()) != null) {
            if (line.equals("locked true")) return p;
        }
        p.destroy();
        throw new IllegalStateException("lock holder exited with " + p.waitFor());
    }
}
