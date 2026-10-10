# Research spikes

Throwaway probes and harnesses from the research chat (2026-10-05..10). They ran **only in a Linux cloud container** (Ubuntu 24.04, JDK 21 with `--release 8`, Xvfb). They are evidence and starting points, **not production code**, and they are not part of the mod build. Results are summarised in [`../docs/PROJECT_STATE.md`](../docs/PROJECT_STATE.md) and [`../docs/research/`](../docs/research/).

| Folder | What it is | Result in the container (AUTOMATED, Linux) |
|---|---|---|
| [`jcefmaven-osr-probe/`](jcefmaven-osr-probe/) | Java 8 OSR browser on the unmodified upstream `jcef-api` 152 (jcefmaven natives) without JOGL: `HeadlessOsrBrowser` in package `org.cef.browser` (CefBrowser_N is package-private), paint stats, per-process RSS/PSS/CPU. `codecs.js` lists MSE/canPlayType/EME support | 1080p VP9/Opus at 29.7 fps (30 fps setting) and 59.3 fps (60); paints on AWT-EventQueue-0; copy 0.85-1.14 ms per 1080p frame; H.264/AAC unsupported; needs `--no-first-run` |
| [`ccbluex-obs-cef/`](ccbluex-obs-cef/) | CCBlueX java-cef (fe8cc9e) built against OBS CEF 150.0.19 Linux (`cef_binary_7871_linux_x86_64`), external pump via `N_DoMessageLoopWork`. `jni_util-cef150.patch` removes 3 net error codes CEF 150 lacks (turn it into an `#if CEF_VERSION_MAJOR >= 152` guard). `rtc.js` lists WebRTC receive codecs | H.264/AAC MP4 played; MSE avc1/mp4a/mp2t true; native HLS "maybe"; WebRTC VP8/VP9/H264/AV1 + Opus |
| [`cinemamod-116/`](cinemamod-116/) | Same probe on CinemaMod's hosted MCEF natives (java-cef eaeb3d4, Chromium 116). Set the MP4 path in `probe.js` | H.264/AAC MP4 played (codecs present) |
| [`relay-latency/`](relay-latency/) | Latency harness: ffmpeg (burned-in frame counter, VP9/VP8 + Opus) → MediaMTX v1.21.1 → page in the JCEF browser via WebRTC (WHEP) or LL-HLS (hls.js 1.7.3, not included). Set `SPIKE_DIR` | Localhost: WebRTC median 126 ms, LL-HLS median ~1.04 s; two clients 22 ms / 116 ms apart (muted autoplay; result lines not archived) |
| [`encode-bench/`](encode-bench/) | ffmpeg `-benchmark` realtime encode cost. Set `IN` to a 720p clip | 4 vCPU Xeon: VP9 720p30 0.40 core, 480p 0.26; VP8 720p30 0.57; SVT-AV1 p12 ~0.56; x264 veryfast 0.41; Opus 0.014 |
| [`youtube-formats/`](youtube-formats/) | Parses `ytInitialPlayerResponse` from a saved watch page to list formats | The container's IP was bot-walled by YouTube; run it from a residential IP |

Not kept: CEF/OBS archives, built natives, MediaMTX binary/config, media clips, logs (they contained container proxy settings). Rebuild from the sources and the research notes.
