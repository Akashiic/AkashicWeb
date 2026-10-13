package com.akashiic.web.client.install;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The pinned engine (ADR-002, ADR-006, ADR-011): the official Electron 44.7.0 release zips, fetched from the
 * publisher's own server and accepted only if the whole file matches the SHA-256 pinned here.
 * <p>
 * Provenance (AUTOMATED, Linux container, 2026-10-12): both hashes were compared with the official
 * {@code https://github.com/electron/electron/releases/download/v44.7.0/SHASUMS256.txt}; both sizes are the
 * {@code Content-Length} served after the github.com 302 redirect. Kept bytes were summed from the real zips' central
 * directories after pruning. Re-pin at the engine freeze by updating all of these values together.
 * <p>
 * Per-file pins (AUTOMATED, 2026-10-13): every kept file of both official zips (the SHA-256s above), hashed after
 * mapping each entry with {@link EngineExtractor#installedPath}; 19 files / 336,044,091 B (win32) and 18 files /
 * 287,441,210 B (linux), equal to the kept-bytes values measured in wave 1. At a re-pin, regenerate them the same way
 * and move the outgoing targets to {@link #PREVIOUS}.
 */
public final class EngineManifest {

    public static final String ELECTRON_VERSION = "44.7.0";
    /** Chromium version inside Electron 44.7.0 (releases.json), shown on the consent screen. */
    public static final String CHROMIUM_VERSION = "152.0.7977.130";
    static final String RELEASE_BASE = "https://github.com/electron/electron/releases/download/v" + ELECTRON_VERSION
        + "/";

    public static final EngineTarget WIN32_X64 = new EngineTarget(
        "win32-x64",
        ELECTRON_VERSION,
        "electron-v44.7.0-win32-x64.zip",
        RELEASE_BASE + "electron-v44.7.0-win32-x64.zip",
        158_293_649L,
        "eee30dc8fa1f5ea95490e59f44e46ea68dd24c6e93d22facf70fe5c2d4c2665c",
        "electron.exe",
        "AkashicWebHost.exe",
        true,
        Collections.<String>emptyList(),
        pins(
            "2529b494f4123f152bce346051f7bd855ef95006424a561953cf17c49a3d1ba8 246302208 AkashicWebHost.exe",
            "5154e165bd6c2cc0cfbcd8916498c7abab0497923bafcd5cb07673fe8480087d 1096 LICENSE",
            "e6e6b1f9cb0f32235412be113de7716333e88121f9159a0ea0ff3be652021af7 20477229 LICENSES.chromium.html",
            "ab7c4b7b6cb8832a31d44b95ec50ce52294f49422a3bdde4e21968eca2930cbe 719654 chrome_100_percent.pak",
            "30fa4a6e27c9ff2fb0f939f01158477e621a67d86e9ecb84cf11b3947db8eb9d 1269017 chrome_200_percent.pak",
            "a05f99734f7c4822fefc12b367af21fd0976ed6608752fb1e1e80b6ece7ecbbb 4741488 d3dcompiler_47.dll",
            "22d415f86ce445f4beb57afbb6856b3b83f19918ad270b6ae6047168ec73956d 25744384 dxcompiler.dll",
            "77e039c905030a641e53658a008b74e90635a5ea9b6b79eabd0f2003bdfca59a 1509760 dxil.dll",
            "d38957b4351486a16141bdd3162643b811564ba6bfa1d12f374c13b70207dc11 3113472 ffmpeg.dll",
            "da53f0b69843aaa43315f0f210a67e4a874b0be338f3dae08ff57b8afe76ab0d 10875024 icudtl.dat",
            "23b271e8009bfa14783700cd0712b4ecaa5b8f2ea28a4d81ae1d7e05bc0c96b1 586582 locales/en-US.pak",
            "5546559a56f9ac3191062c3fd42defd190a68e78ac988eb57fa5a6fd2dc5f874 697019 locales/pt-BR.pak",
            "335d91338d271cc341cbea39179941cf043965f47aa5d1cd8ef0c98401ddcf9f 12436174 resources.pak",
            "ec901619d6c0d2589d30e94cb60c313b49dd9c2e4334b6deeb454ac97a67ce21 368768 snapshot_blob.bin",
            "883f08309bf436fb442d1903452a69c03747ed77c5f502e88667d9aa1b706556 742200 v8_context_snapshot.bin",
            "3e1e0322d949de3be7e5b9883a0c8014660aafa67e417a7d86b281e9733829a1 6 version",
            "e66cfe13aafbd7d80226e3ce085de5786479de35f7d6a75eded9ecb4ebf9784b 5520384 vk_swiftshader.dll",
            "32d83ff113fef532a9f97e0d2831f8656628ab1c99e9060f0332b1532839afd9 106 vk_swiftshader_icd.json",
            "85d52af200d0020855e91477f6334b59e12167f051991b2a0bb35b9697c0c778 939520 vulkan-1.dll"));

    public static final EngineTarget LINUX_X64 = new EngineTarget(
        "linux-x64",
        ELECTRON_VERSION,
        "electron-v44.7.0-linux-x64.zip",
        RELEASE_BASE + "electron-v44.7.0-linux-x64.zip",
        122_998_223L,
        "3ae7d5bdad61c664486c6ab61361dd084d064c8c08af8d13a687096e18ce555a",
        "electron",
        "AkashicWebHost",
        false,
        Arrays.asList("AkashicWebHost", "chrome_crashpad_handler", "chrome-sandbox"),
        pins(
            "10a14d05c6ff4f94075cfb3eeb6ed6571be33ebcc08cbd675b5ce9ff84706564 228605256 AkashicWebHost",
            "5154e165bd6c2cc0cfbcd8916498c7abab0497923bafcd5cb07673fe8480087d 1096 LICENSE",
            "3375e2a9bd7bc631738fde10be6548a76efe8cf5e4b8826b8140ba8b0c4d6353 20115518 LICENSES.chromium.html",
            "a4f6dfd7325ddd55f94ddc0c487d22726d40ef099ded6475d5dffe236e277896 15232 chrome-sandbox",
            "891176da5eee33b54352fdb1407a8e09a2bd48c7eb2077311e75787de33a095e 719546 chrome_100_percent.pak",
            "b3ce9953a50959e9c1a018d6512742f48cee0dbf53644ea952909418bd7c2b3e 1268994 chrome_200_percent.pak",
            "3e9742b8edaef6779c48e0966b9f433db39e59ee148854312b5a5c8612100a04 1918544 chrome_crashpad_handler",
            "da53f0b69843aaa43315f0f210a67e4a874b0be338f3dae08ff57b8afe76ab0d 10875024 icudtl.dat",
            "c4c805e46f957356f6b80be31999459e5f07e8b0c351221544ad43a16c980638 2801752 libffmpeg.so",
            "f473264f0013f07345b5bb258dd12f10b461a291c578f32b7466c9000b662707 4657864 libvk_swiftshader.so",
            "951c832a600fd72fc0eff1a42b8e251e81ea200a62499e74c86d4aeb3646aa34 2483336 libvulkan.so.1",
            "e37db76f0a15a4b2a3cbd365bd4f06b0d8dc91fb9a83c03b7cd38503c23c044b 106969 locales/en-US.pak",
            "907226d904de6a571a80289ef6a8072bad867b1f63ec37fcd735c30763af970d 126281 locales/pt-BR.pak",
            "631f8de6e1d5b46de9a5930ac3da5c1f77ebd2eef355be75c8c2095d0b29dbab 12634717 resources.pak",
            "318358a565b6799c7cd42f4647a68d07c326aea80171d5b451af8f14d1bc345f 368768 snapshot_blob.bin",
            "f7ce43b3433a5c36621b09b24fb1b49823dcad925c089ebe60f09aa780941aa1 742200 v8_context_snapshot.bin",
            "3e1e0322d949de3be7e5b9883a0c8014660aafa67e417a7d86b281e9733829a1 6 version",
            "c9eef28b6b984fec220ef0abfecc40b502d46946706e47bfe97707027cb818bd 107 vk_swiftshader_icd.json"));

    /**
     * Targets of earlier pins, newest first. A rollback after a load failure only goes to an engine whose files can be
     * checked against pins, so at a re-pin the outgoing targets move here. Empty: 44.7.0 is the first pin.
     */
    public static final List<EngineTarget> PREVIOUS = Collections.emptyList();

    /** Bytes on disk after pruning (win32: 19 files; linux: 18 files), plus our host app (a few KB). */
    public static final long WIN32_X64_KEPT_BYTES = 336_044_091L;
    public static final long LINUX_X64_KEPT_BYTES = 287_441_210L;

    /** Free space required before a download or an import starts (ADR-011): zip + unpacked copy + margin. */
    public static final long MIN_FREE_BYTES = 1024L * 1024 * 1024;

    /** Only these UI locales are kept; the other 53 .pak files are never written (about 40 MB on Windows). */
    public static final List<String> KEPT_LOCALES = Collections
        .unmodifiableList(Arrays.asList("locales/en-US.pak", "locales/pt-BR.pak"));
    static final String LOCALES_PREFIX = "locales/";

    /**
     * Vendor entries that are never installed. Without default_app.asar the exe cannot be pointed at another app
     * folder from its command line, and Electron loads only our {@code resources/app} (ADR-001 amendment).
     */
    public static final List<String> REMOVED_ENTRIES = Collections
        .unmodifiableList(Collections.singletonList("resources/default_app.asar"));

    /** Where our host app goes inside the engine folder (Electron's app search path). */
    public static final String APP_DIR = "resources/app";

    /** Zip-bomb guards: the real win32 zip has 73 entries that unpack to 385,514,847 B. */
    static final int MAX_ENTRIES = 512;
    static final long MAX_WRITTEN_BYTES = 640L * 1024 * 1024;

    private EngineManifest() {}

    /** Parses {@code "<sha256> <size> <path>"} lines. */
    static List<LocalManifest.Entry> pins(String... lines) {
        List<LocalManifest.Entry> out = new ArrayList<>();
        for (String line : lines) {
            String[] p = line.split(" ", 3);
            out.add(new LocalManifest.Entry(p[2], Long.parseLong(p[1]), p[0]));
        }
        return out;
    }

    /** Bytes the install occupies once done, for the consent screen. */
    public static long keptBytes(EngineTarget t) {
        if (t == WIN32_X64) return WIN32_X64_KEPT_BYTES;
        if (t == LINUX_X64) return LINUX_X64_KEPT_BYTES;
        return t.size * 2; // test targets: a safe over-estimate
    }
}
