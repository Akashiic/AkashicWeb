package com.akashiic.web.client.install;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A snapshot of what the JVM tells us about the machine, taken once so {@link PlatformGate} is a pure function and
 * the support matrix can be tested on any OS. Environment lookups are case-insensitive, as on Windows.
 */
public final class PlatformFacts {

    /** Files that exist on every Android system image, also inside Pojav-style launchers' JVMs. */
    static final String[] ANDROID_FILES = { "/system/build.prop", "/system/bin/app_process" };

    public final String osName;
    public final String osArch;
    /** {@code sun.arch.data.model} ("64"/"32"), or empty when the JVM does not report it. */
    public final String dataModel;
    public final String javaVendor;
    public final String javaVmName;
    public final String javaRuntimeName;
    public final Map<String, String> env;
    public final Set<String> propertyNames;
    public final boolean androidSystemFiles;

    public PlatformFacts(String osName, String osArch, String dataModel, String javaVendor, String javaVmName,
        String javaRuntimeName, Map<String, String> env, Set<String> propertyNames, boolean androidSystemFiles) {
        this.osName = nz(osName);
        this.osArch = nz(osArch);
        this.dataModel = nz(dataModel);
        this.javaVendor = nz(javaVendor);
        this.javaVmName = nz(javaVmName);
        this.javaRuntimeName = nz(javaRuntimeName);
        TreeMap<String, String> e = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (env != null) e.putAll(env);
        this.env = Collections.unmodifiableMap(e);
        this.propertyNames = Collections.unmodifiableSet(
            propertyNames == null ? new TreeSet<String>() : new TreeSet<>(propertyNames));
        this.androidSystemFiles = androidSystemFiles;
    }

    public static PlatformFacts fromSystem() {
        Properties p = System.getProperties();
        boolean android = false;
        for (String f : ANDROID_FILES) {
            android |= new File(f).exists();
        }
        return new PlatformFacts(
            p.getProperty("os.name"),
            p.getProperty("os.arch"),
            p.getProperty("sun.arch.data.model"),
            p.getProperty("java.vendor"),
            p.getProperty("java.vm.name"),
            p.getProperty("java.runtime.name"),
            System.getenv(),
            p.stringPropertyNames(),
            android);
    }

    /** Environment variable or null; empty values count as absent. */
    public String env(String name) {
        String v = env.get(name);
        return v == null || v.trim().isEmpty() ? null : v;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
