# Research spikes

Throwaway probes and harnesses from the research chat (2026-10-05..10). They ran **only in a Linux cloud container** (Ubuntu 24.04, JDK 21 with `--release 8`, Xvfb). They are evidence and starting points, **not production code**, and they are not part of the mod build. Results are summarised in [`../docs/PROJECT_STATE.md`](../docs/PROJECT_STATE.md) and [`../docs/research/`](../docs/research/).

| Folder | What it is | Result in the container (AUTOMATED, Linux) |
|---|---|---|
| [`jcefmaven-osr-probe/`](jcefmaven-osr-probe/) | Java 8 OSR browser on the unmodified upstream `jcef-api` 152 (jcefmaven natives) without JOGL: `HeadlessOsrBrowser` in package `org.cef.browser` (CefBrowser_N is package-private), paint stats, per-process RSS/PSS/CPU. `codecs.js` lists MSE/canPlayType/EME support | 1080p VP9/Opus at 29.7 fps (30 fps setting) and 59.3 fps (60); paints on AWT-EventQueue-0; copy 0.85-1.14 ms per 1080p frame; H.264/AAC unsupported; needs `--no-first-run` |
| [`ccbluex-obs-cef/`](ccbluex-obs-cef/) | CCBlueX java-cef (fe8cc9e) built against OBS CEF 150.0.19 Linux (`cef_binary_7871_linux_x86_64`), external pump via `N_DoMessageLoopWork`. `jni_util-cef150.patch` removes 3 net error codes CEF 150 lacks (turn it into an `#if CEF_VERSION_MAJOR >= 152` guard). `rtc.js` lists WebRTC receive codecs | H.264/AAC MP4 played; MSE avc1/mp4a/mp2t true; native HLS "maybe"; WebRTC VP8/VP9/H264/AV1 + Opus |
| [`cinemamod-116/`](cinemamod-116/) | Same probe on CinemaMod's hosted MCEF natives (java-cef eaeb3d4, Chromium 116). Set the MP4 path in `probe.js` | H.264/AAC MP4 played (codecs present) |

| [`electron-host/`](electron-host/) | Electron 44 offscreen-rendering host probe (`app/main.js`, capture page) and a Java 8 `FrameSink` that spawns it and reads BGRA frames over loopback TCP; `page.html` is the codec/input test page | Linux: H.264/AAC, HLS, MSE played; ~30 paints/s at 720p; PCM captured; ABNT2 text and wheel round-trip; host exits when the parent dies (see ADR-002) |
| [`natives-probe/`](natives-probe/) | `RemoteZipProbe`: HTTP Range fetch of single entries from a remote zip (OBS CEF fallback) | Range fetch of the 23 needed OBS entries = 158.3 MB (ADR-011) |

Not kept: CEF/OBS archives, built natives, media clips, the abandoned relay/encoding harnesses (D-008), logs (they contained container proxy settings). Rebuild from the sources and the research notes.
