package com.akashiic.web.client.install;

import java.util.Locale;

/**
 * Decides, before a single byte is downloaded, whether this machine can run the engine (ADR-011 "Platform gating
 * before any byte", ADR-014). Supported: Windows x64 on a 64-bit JVM, and Linux x64 with a desktop session. Everything
 * else (macOS, Android/ForbiddenMobile/Pojav, arm64, 32-bit) gets the placeholder and downloads nothing. Pure.
 */
public final class PlatformGate {

    /** Outcome: either a supported {@link Kind} or a failure with a detail for {@code /web doctor}. */
    public static final class Decision {

        public final Kind kind;
        public final InstallFailure failure;
        public final String detail;

        private Decision(Kind kind, InstallFailure failure, String detail) {
            this.kind = kind;
            this.failure = failure;
            this.detail = detail;
        }

        public boolean supported() {
            return failure == null;
        }

        @Override
        public String toString() {
            return supported() ? kind.toString() : failure + " (" + detail + ")";
        }
    }

    public enum Kind {
        WINDOWS_X64,
        LINUX_X64
    }

    private PlatformGate() {}

    public static Decision decide(PlatformFacts f) {
        String android = androidSignal(f);
        if (android != null) {
            return unsupported(InstallFailure.UNSUPPORTED_OS, "Android/mobile launcher (" + android + ")");
        }
        String os = f.osName.toLowerCase(Locale.ROOT);
        String arch = f.osArch.toLowerCase(Locale.ROOT);
        boolean windows = os.startsWith("windows");
        boolean linux = os.startsWith("linux");
        if (!windows && !linux) {
            // macOS is placeholder-only for 10-25 (ADR-014: no pin, no test cell); other OSes have no Electron build.
            return unsupported(InstallFailure.UNSUPPORTED_OS, "os.name=" + f.osName);
        }
        if (!isX64(arch)) {
            String hint = windows && "AMD64".equalsIgnoreCase(f.env("PROCESSOR_ARCHITEW6432"))
                ? " (32-bit Java on 64-bit Windows: a 64-bit Java is needed)"
                : "";
            return unsupported(InstallFailure.UNSUPPORTED_ARCH, "os.arch=" + f.osArch + hint);
        }
        if (!f.dataModel.isEmpty() && !"64".equals(f.dataModel)) {
            return unsupported(InstallFailure.UNSUPPORTED_ARCH, "sun.arch.data.model=" + f.dataModel);
        }
        if (windows) return new Decision(Kind.WINDOWS_X64, null, "Windows x64");
        // A Minecraft client on Linux always has X11 or Wayland; without either this is a headless or mobile JVM,
        // and Chromium could not create its offscreen windows anyway.
        if (f.env("DISPLAY") == null && f.env("WAYLAND_DISPLAY") == null) {
            return unsupported(InstallFailure.UNSUPPORTED_OS, "Linux without DISPLAY or WAYLAND_DISPLAY");
        }
        return new Decision(Kind.LINUX_X64, null, "Linux x64");
    }

    /** The pinned asset for a supported decision. */
    public static EngineTarget target(Kind kind) {
        return kind == Kind.WINDOWS_X64 ? EngineManifest.WIN32_X64 : EngineManifest.LINUX_X64;
    }

    static boolean isX64(String arch) {
        return "amd64".equals(arch) || "x86_64".equals(arch) || "x64".equals(arch);
    }

    /**
     * Pojav-family launchers (ForbiddenMobile, PojavLauncher, FCL, Zalith) run a regular OpenJDK inside an Android app
     * process, so os.name is just "Linux". They still inherit Android's process environment and file system, and
     * they set their own system properties. Any one signal is enough. ANDROID_HOME/ANDROID_SDK_ROOT are deliberately
     * ignored: desktop developers set them.
     */
    static String androidSignal(PlatformFacts f) {
        for (String s : new String[] { f.javaVendor, f.javaVmName, f.javaRuntimeName }) {
            String l = s.toLowerCase(Locale.ROOT);
            if (l.contains("android") || l.contains("dalvik")) return "java runtime '" + s + "'";
        }
        for (String var : new String[] { "ANDROID_ROOT", "ANDROID_DATA", "ANDROID_ART_ROOT", "POJAV_NATIVEDIR",
            "POJAV_RENDERER" }) {
            if (f.env(var) != null) return "env " + var;
        }
        for (String name : f.propertyNames) {
            String l = name.toLowerCase(Locale.ROOT);
            if (l.startsWith("pojav.") || l.startsWith("fcl.") || l.startsWith("zalith.")) {
                return "property " + name;
            }
        }
        if (f.androidSystemFiles) return "/system files";
        return null;
    }

    private static Decision unsupported(InstallFailure failure, String detail) {
        return new Decision(null, failure, detail);
    }
}
