package com.akashiic.web.client.screen;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;

/**
 * Total physical memory for the browser budget. Read through the public {@code com.sun.management} interface by
 * reflection: present on every HotSpot/OpenJ9 the pack runs (8u51 included) and accessible under Java 9+ modules,
 * unlike the implementation class. Unknown (-1) when the JVM does not expose it.
 */
public final class SystemMemory {

    private SystemMemory() {}

    public static long totalPhysicalBytes() {
        try {
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            Class<?> ext = Class.forName("com.sun.management.OperatingSystemMXBean");
            if (!ext.isInstance(os)) return -1;
            Method m = ext.getMethod("getTotalPhysicalMemorySize");
            Object v = m.invoke(os);
            return v instanceof Long ? (Long) v : -1;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return -1; // the default budget applies
        }
    }
}
