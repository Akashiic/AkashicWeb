package com.akashiic.web.client.host;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Operating-system process operations the supervisor needs beyond {@link Process}: the PID of a child (for the PID
 * file), whether a recorded PID is still a host, and a forced kill for the stale-host sweep. The game runs on Java 8
 * (no ProcessHandle) and on Java 17-25, so the implementation is chosen at runtime.
 */
public interface ProcessControl {

    /**
     * File name of the executable of live process {@code pid} (e.g. {@code AkashicWebHost.exe}), {@code ""} when it
     * is alive but its image cannot be read, or null when no such process runs.
     */
    String imageName(long pid);

    /** Forcibly ends {@code pid} and, where the platform allows, its children. True if the kill was issued. */
    boolean kill(long pid);

    /** PID of a process this JVM started, or -1 when this JVM cannot tell (Java 8 on Windows). */
    long pidOf(Process process);

    /** The best implementation for this JVM and OS. */
    static ProcessControl forThisPlatform() {
        boolean windows = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .startsWith("windows");
        ProcessControl os = windows ? new WindowsProcessControl() : new UnixProcessControl();
        ProcessControl jdk = JdkProcessControl.tryCreate(os);
        return jdk != null ? jdk : os;
    }

    /**
     * PID through reflection: {@code Process.pid()} on Java 9+, else the private {@code pid} field of Java 8's
     * UNIXProcess. Java 8 on Windows only has a handle, so this returns -1 there and the host's HELLO pid is used.
     */
    static long reflectivePid(Process process) {
        if (process == null) return -1;
        try {
            Method m = Process.class.getMethod("pid");
            return ((Number) m.invoke(process)).longValue();
        } catch (ReflectiveOperationException | RuntimeException notJava9) {
            // Java 8: fall through to the field.
        }
        try {
            Field f = process.getClass()
                .getDeclaredField("pid");
            f.setAccessible(true);
            return f.getLong(process);
        } catch (ReflectiveOperationException | RuntimeException noField) {
            return -1;
        }
    }

    /** Last path element, for comparing executables recorded with a path against image names without one. */
    static String fileName(String path) {
        if (path == null) return null;
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return cut < 0 ? path : path.substring(cut + 1);
    }
}
