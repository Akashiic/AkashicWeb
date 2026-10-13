package com.akashiic.web.client.install;

import static com.akashiic.web.client.install.TestSupport.env;
import static com.akashiic.web.client.install.TestSupport.facts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.akashiic.web.common.ReasonCode;

/** The ADR-014 support matrix: what downloads, and what gets the placeholder with which reason. */
class PlatformGateTest {

    private static final Map<String, String> DESKTOP = env("DISPLAY", ":0");

    private static PlatformGate.Decision decide(String os, String arch, String model, Map<String, String> env) {
        return PlatformGate.decide(facts(os, arch, model, env));
    }

    @Test
    void windowsX64On64BitJvmIsSupported() {
        for (String os : new String[] { "Windows 10", "Windows 11", "Windows Server 2022" }) {
            PlatformGate.Decision d = decide(os, "amd64", "64", env());
            assertTrue(d.supported(), os);
            assertEquals(PlatformGate.Kind.WINDOWS_X64, d.kind);
            assertSame(EngineManifest.WIN32_X64, PlatformGate.target(d.kind));
        }
    }

    @Test
    void thirtyTwoBitJavaOnWindowsIsUnsupportedArchWithHint() {
        PlatformGate.Decision d = decide("Windows 10", "x86", "32", env("PROCESSOR_ARCHITEW6432", "AMD64"));
        assertEquals(InstallFailure.UNSUPPORTED_ARCH, d.failure);
        assertTrue(d.detail.contains("64-bit Java"), d.detail);
        assertEquals(InstallFailure.UNSUPPORTED_ARCH, decide("Windows 7", "x86", "32", env()).failure);
    }

    @Test
    void windowsOnArmAndOddDataModelAreUnsupportedArch() {
        assertEquals(InstallFailure.UNSUPPORTED_ARCH, decide("Windows 11", "aarch64", "64", env()).failure);
        assertEquals(InstallFailure.UNSUPPORTED_ARCH, decide("Windows 10", "amd64", "32", env()).failure);
    }

    @Test
    void linuxX64WithDesktopIsSupported() {
        assertEquals(PlatformGate.Kind.LINUX_X64, decide("Linux", "amd64", "64", DESKTOP).kind);
        Map<String, String> wayland = env("WAYLAND_DISPLAY", "wayland-0");
        assertEquals(PlatformGate.Kind.LINUX_X64, decide("Linux", "x86_64", "", wayland).kind);
        assertSame(EngineManifest.LINUX_X64, PlatformGate.target(PlatformGate.Kind.LINUX_X64));
    }

    @Test
    void linuxWithoutDesktopIsUnsupportedOs() {
        PlatformGate.Decision d = decide("Linux", "amd64", "64", env("DISPLAY", " "));
        assertEquals(InstallFailure.UNSUPPORTED_OS, d.failure);
    }

    @Test
    void linuxArmAnd32BitAreUnsupportedArch() {
        assertEquals(InstallFailure.UNSUPPORTED_ARCH, decide("Linux", "aarch64", "64", DESKTOP).failure);
        assertEquals(InstallFailure.UNSUPPORTED_ARCH, decide("Linux", "arm", "32", DESKTOP).failure);
        assertEquals(InstallFailure.UNSUPPORTED_ARCH, decide("Linux", "i386", "32", DESKTOP).failure);
    }

    @Test
    void macOsAndOtherSystemsAreUnsupportedOs() {
        assertEquals(InstallFailure.UNSUPPORTED_OS, decide("Mac OS X", "x86_64", "64", env()).failure);
        assertEquals(InstallFailure.UNSUPPORTED_OS, decide("Mac OS X", "aarch64", "64", env()).failure);
        assertEquals(InstallFailure.UNSUPPORTED_OS, decide("FreeBSD", "amd64", "64", DESKTOP).failure);
        assertEquals(InstallFailure.UNSUPPORTED_OS, decide("SunOS", "amd64", "64", env()).failure);
    }

    @Test
    void pojavStyleLaunchersAreDetectedEvenOnX64() {
        // An x86_64 Android emulator with a desktop-looking environment: only Android signals give it away.
        assertEquals(
            InstallFailure.UNSUPPORTED_OS,
            decide("Linux", "amd64", "64", env("DISPLAY", ":0", "ANDROID_ROOT", "/system")).failure);
        assertEquals(
            InstallFailure.UNSUPPORTED_OS,
            decide("Linux", "aarch64", "64", env("POJAV_RENDERER", "opengles3")).failure);
        PlatformFacts withProps = new PlatformFacts(
            "Linux",
            "amd64",
            "64",
            "Oracle",
            "OpenJDK",
            "OpenJDK",
            DESKTOP,
            new HashSet<>(Arrays.asList("pojav.path.minecraft")),
            false);
        assertEquals(InstallFailure.UNSUPPORTED_OS, PlatformGate.decide(withProps).failure);
        PlatformFacts withFiles = new PlatformFacts(
            "Linux",
            "amd64",
            "64",
            "Oracle",
            "OpenJDK",
            "OpenJDK",
            DESKTOP,
            Collections.<String>emptySet(),
            true);
        assertEquals(InstallFailure.UNSUPPORTED_OS, PlatformGate.decide(withFiles).failure);
        PlatformFacts dalvik = new PlatformFacts(
            "Linux",
            "aarch64",
            "",
            "The Android Project",
            "Dalvik",
            "Android Runtime",
            env(),
            Collections.<String>emptySet(),
            false);
        assertTrue(PlatformGate.decide(dalvik).detail.contains("Android"));
    }

    @Test
    void androidSdkVariablesOnADesktopAreNotAndroid() {
        Map<String, String> sdk = env("DISPLAY", ":0", "ANDROID_HOME", "/opt/sdk", "ANDROID_SDK_ROOT", "/x");
        assertTrue(decide("Linux", "amd64", "64", sdk).supported());
        assertTrue(decide("Windows 10", "amd64", "64", env("ANDROID_HOME", "C:\\sdk")).supported());
    }

    @Test
    void environmentLookupIsCaseInsensitiveLikeWindows() {
        PlatformFacts f = facts("Windows 10", "x86", "32", env("processor_architew6432", "AMD64"));
        assertEquals("AMD64", f.env("PROCESSOR_ARCHITEW6432"));
        assertTrue(PlatformGate.decide(f).detail.contains("64-bit Java"));
    }

    @Test
    void everyFailureMapsToItsReasonCode() {
        for (InstallFailure f : InstallFailure.values()) {
            assertEquals(f.name(), f.reason.name());
            assertFalse(f.reason == ReasonCode.OK);
        }
    }
}
