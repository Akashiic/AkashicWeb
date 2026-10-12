package com.akashiic.web.client.host;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Java 9+ {@code ProcessHandle}, reached by reflection because the mod compiles against the Java 8 API (Jabel). No
 * external tool is spawned. When the JDK cannot read a process's command line (another user's or an elevated process
 * on Windows) the OS fallback is asked instead.
 */
final class JdkProcessControl implements ProcessControl {

    private final ProcessControl fallback;
    private final Method of;
    private final Method isAlive;
    private final Method info;
    private final Method command;
    private final Method destroyForcibly;
    private final Method descendants;
    private final Method pid;

    private JdkProcessControl(ProcessControl fallback) throws ReflectiveOperationException {
        this.fallback = fallback;
        Class<?> handle = Class.forName("java.lang.ProcessHandle");
        Class<?> handleInfo = Class.forName("java.lang.ProcessHandle$Info");
        this.of = handle.getMethod("of", long.class);
        this.isAlive = handle.getMethod("isAlive");
        this.info = handle.getMethod("info");
        this.command = handleInfo.getMethod("command");
        this.destroyForcibly = handle.getMethod("destroyForcibly");
        this.descendants = handle.getMethod("descendants");
        this.pid = Process.class.getMethod("pid");
    }

    /** Null on Java 8. */
    static JdkProcessControl tryCreate(ProcessControl fallback) {
        try {
            return new JdkProcessControl(fallback);
        } catch (ReflectiveOperationException | RuntimeException java8) {
            return null;
        }
    }

    @Override
    public String imageName(long pid) {
        if (pid <= 0) return null;
        try {
            Object h = handle(pid);
            if (h == null || !(Boolean) isAlive.invoke(h)) return null;
            Optional<?> cmd = (Optional<?>) command.invoke(info.invoke(h));
            if (cmd.isPresent()) return ProcessControl.fileName(String.valueOf(cmd.get()));
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Fall back to the OS tool below.
        }
        String name = fallback.imageName(pid);
        return name == null ? "" : name; // ProcessHandle saw it alive; keep it "alive, image unknown"
    }

    @Override
    public boolean kill(long pid) {
        if (pid <= 0) return false;
        try {
            Object h = handle(pid);
            if (h == null) return false;
            List<?> children = ((Stream<?>) descendants.invoke(h)).collect(Collectors.toList());
            for (Object child : children) destroyForcibly.invoke(child);
            return (Boolean) destroyForcibly.invoke(h);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return fallback.kill(pid);
        }
    }

    @Override
    public long pidOf(Process process) {
        try {
            return ((Number) pid.invoke(process)).longValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1; // UnsupportedOperationException from exotic Process implementations
        }
    }

    private Object handle(long p) throws ReflectiveOperationException {
        Optional<?> o = (Optional<?>) of.invoke(null, p);
        return o.isPresent() ? o.get() : null;
    }
}
