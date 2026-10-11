# Handoff to the build chat

Written 2026-10-10 at the end of the research chat. The build continues in a new chat. Read this file after [`PROJECT_STATE.md`](PROJECT_STATE.md).

## Where things stand

- Research done; no code. The plan is [`PLAN.md`](PLAN.md); decisions are in [`DECISIONS.md`](DECISIONS.md); every known problem with its fix is in [`PROBLEM_FIX_MATRIX.md`](PROBLEM_FIX_MATRIX.md); risks in [`ISSUES_REGISTER.md`](ISSUES_REGISTER.md).
- The owner's scope (binding): a real, **interactive** Web Displays-style browser (D-001, D-002), from scratch (D-003), premium GTNH-stack quality (D-004), sound through AkashicFM (D-005).
- Event: 2026-10-25. Schedule and gates in PLAN sections 6-7.

## First actions in the new chat

1. Ask the owner the pending decisions P-01..P-04 (server/pack + version, AkashicFM in the pack, codec build, staffing). Ask in Brazilian Portuguese. Record answers in `DECISIONS.md`.
2. Start the native build first, because it is the longest pole and only Windows matters for G1:
   - fork or vendor [CCBlueX/java-cef](https://github.com/CCBlueX/java-cef) at fe8cc9e (or a newer pin) under the Akashiic organisation, or add it as a build input of this repo;
   - CI job that downloads OBS CEF `cef_binary_7871_windows_x64.zip` from `https://cdn-fastly.obsproject.com/downloads/`, verifies the SHA-256 pinned in obs-studio `CMakePresets.json` (starts `54cd191a`), extracts it to `third_party/cef/cef_binary_<ver>_windows64` (the zip has no top-level folder) and builds with `-DCEF_VERSION=<exact version from include/cef_version.h>`;
   - apply the jni_util guard ([`spikes/ccbluex-obs-cef/jni_util-cef150.patch`](../spikes/ccbluex-obs-cef/jni_util-cef150.patch), as an `#if`), then the native patches in PLAN 4.1;
   - strip LiquidBounce upload steps from CCBlueX's `build-jcef.yml`;
   - in parallel, the same build against Spotify CEF 152 (codec-free fallback);
   - Linux job in an older container (glibc floor; PLAN 4.1).
3. Put a standalone Java 8 probe (adapt [`spikes/ccbluex-obs-cef/Probe152.java`](../spikes/ccbluex-obs-cef/Probe152.java)) in the owner's hands on Windows for G1.
4. In parallel, create the mod skeleton: copy the GTNH buildscript setup from `Akashiic/AkashicFM` (`build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `dependencies.gradle`, `repositories.gradle`, `addon.gradle`, `gtnhShared/`), set `modId`, package `com.akashiic.web` (proposal), Jabel, UniMixins, GTNHLib, `usesShadowedDependencies` for natives-free jars only.
5. Create GitHub issues from [`ISSUES_REGISTER.md`](ISSUES_REGISTER.md) once the owner agrees.

## Sources to clone in the new chat (all public)

| Repo | Why |
|---|---|
| [montoyo/webdisplays](https://github.com/montoyo/webdisplays) (master, 1.12.2, public domain) | Multiblock, hit2pixels, rotation, rights model to reuse with credit |
| [Mysticpasta1/webdisplays](https://github.com/Mysticpasta1/webdisplays) (redirects to CinemaMod/webdisplays; MIT) | `ScreenControl` registry idea (1.20 branch); `audio-handler` and `expirement/ssr` branches as references |
| [CCBlueX/java-cef](https://github.com/CCBlueX/java-cef) (BSD) | Engine base |
| [chromiumembedded/java-cef](https://github.com/chromiumembedded/java-cef) | Upstream reference |
| [CinemaMod/mcef](https://github.com/CinemaMod/mcef) (LGPL-2.1) | Ideas only: render-thread pumping, dirty-rect upload, `add-audio-handler` branch; emergency 116 natives |
| [obsproject/obs-studio](https://github.com/obsproject/obs-studio) | `CMakePresets.json` pins the CEF 7871 URLs and hashes |
| [GTNewHorizons/Angelica](https://github.com/GTNewHorizons/Angelica), [GTNewHorizons/lwjgl3ify](https://github.com/GTNewHorizons/lwjgl3ify) | GL redirection, render-bounds registry (2.x only), input APIs |
| `Akashiic/AkashicFM` | Buildscript, ToolManager, UrlPolicy, AuditLog, ServerClock/ClockSync, audio engine, E2E and prod harnesses |

## Facts that are easy to get wrong

- TSE results data and the live relay were **abandoned** (D-008). Do not bring them back; the product is the browser.

- Old research said CinemaMod CEF 116 has no codecs: **wrong**, it does (stream 05 tested it).
- Angelica's `TileEntityRenderBoundsRegistry` exists only in Angelica 2.x (GTNH 2.9), not in GTNH 2.8.4's beta66b.
- CCBlueX `CefApp.initialize()` overwrites `browser_subprocess_path`; renaming the helper needs a Java patch too.
- `DirectByteBuffer(long,int)` does not exist on Java 21.
- OBS's Windows CEF zip unpacks to 1.2 GB, mostly build leftovers; extract only `Release/` and `Resources/`.
- YouTube live audio is AAC-only and live HLS video is H.264; stock CEF cannot play lives.
- The AkashicFM harnesses ran only on Linux; Chromium is native, so Windows must be tested on real PCs.
- AkashicFM's `RadioAudioController` releases foreign playback keys every tick; 3D audio needs an AkashicFM API change first.
- The Akashic-Voice docs describe the production field client as the Technic "Forbidden Series" pack (Crucible + Velocity), not GTNH. Confirm P-01 before assuming GTNH.

## Things the research container had that are gone

The downloaded OBS CEF archives, the built `libjcef.so`, the test clip and all probe logs. Only the sources in [`/spikes`](../spikes) and the raw research JSON in [`research/raw`](research/raw) were kept. Rebuild as needed.

## Conversation notes (owner)

- Gabriel (GitHub `Akashiic`, Rede Akashic) speaks Brazilian Portuguese; repository text is English; keep [`PT-BR_RESUMO.md`](PT-BR_RESUMO.md) in step with `PROJECT_STATE.md`.
- He reacted strongly against a "reduced" solution (JSON board) and against a command-only kiosk. Present alternatives only as internal techniques or clearly labelled safety nets.
- He cares about low-delay lives and AkashicFM-quality 3D sound.
