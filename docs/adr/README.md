# Architecture decision records

> Produced 2026-10-12 by a skeptical technology review: seven specialists (engine, frame transport, codecs, audio, mod stack, distribution, multiplayer), one architect and one adversarial reviewer. Raw: [`../research/raw/09-tech-challenge.json`](../research/raw/09-tech-challenge.json). **These ADRs supersede the technical sections of [`../PLAN.md`](../PLAN.md) (4.1-4.10, 6-8) and decisions T-01..T-10.** Each ADR lists the reviewer's amendments, which are binding. Owner context: D-014 in [`../DECISIONS.md`](../DECISIONS.md). Evidence grades: everything here is STATIC or AUTOMATED (Linux container) until confirmed on Windows and in game.

## Summary

Lead-architect verdict (2026-10-12). Nothing here was built or run on Windows or in Minecraft. I read code and docs only, plus /home/user/akashicweb/docs/research/07-forbidden-pack-inventory.md and two read-only checks: the Angelica 2.2.20 tag and Authenticode on the extracted electron.exe.

1) Biggest change: move the browser out of the game JVM now, not after the event.
- Run it as a separate host process behind an engine-agnostic socket protocol. Status: ACCEPTED.
- Use the official Electron 44.x build (Chromium 152.0.7977.130, H.264/AAC on) as the engine for 10-25. Status: PROPOSED-NEEDS-SPIKE. It is confirmed or rejected by G1-E on the owner's Windows PC (about half a day, no CI build).
- Why: it removes the riskiest part of the schedule, the untested Windows libjcef build against OBS CEF 150 plus 4+ JNI patches.
  - It isolates native crashes from the fragile 8u51 client (VERIFIED: FIELD_EVIDENCE OOMs).
  - It turns the renderer sandbox on. JCEF hard-codes no_sandbox (VERIFIED: context.cpp:211).
  - The mod becomes pure Java 8, with no org.cef or JNI.
  - Every core function was VERIFIED in the review, but only in a Linux container.
- Fallback: CCBlueX JCEF on OBS CEF 7871 in a child JVM, behind the same protocol. Its Windows CI build runs in parallel on 10-12 as insurance. In-process JCEF is the last resort.

2) Kept, with the corrections the challenges found (each ACCEPTED):
- CPU frames plus glTexSubImage2D at RenderTickEvent START. Fixes: damage accumulation, BGRA/RGBA8 allocation, CLAMP_TO_EDGE, saving the PBO binding, a per-frame upload budget, instrumentation, 720p default.
- Per-client browsers with server URL authority. Shared screens get in-memory sessions, which closes a confused-deputy bug. Followers steer HTMLMediaElement.playbackRate (not the IFrame API), with ad- and live-edge awareness and skew telemetry.
- Two audio modes. OS mode with a hard mute is the guaranteed baseline. SPATIAL capture into AkashicFM stays a Stretch goal (go/no-go 10-21) and needs an AkashicFM API release in the pack by 10-18.
- Forge-only runtime with no mixins for 10-25. GTNHLib, UniMixins and ModularUI2 are present (D-013) but not needed on the critical path.
- Two-layer runtime install: the engine comes from the vendor's server with a SHA-256 pin; the host app and glue ship in the mod jar. No Akashic mirror.

3) Correction from the newer pack facts (D-013 / research 07): the pack runs Angelica 2.2.20, not 2.1.33.
- Its TileEntityRenderBoundsRegistry.registerDynamicClass exists (VERIFIED in the 2.2.20 tag), and so does section-escaping 'global' TE classification. So the screen keeps its true multiblock box, registered by reflection, instead of the INFINITE box the mod-stack challenge proposed.

4) Rejected for 10-25: WebView2, CDP screencast, JxBrowser, Ultralight, Servo/Ladybird, WPE, Gecko, server-side rendering or cast, DOM mirroring, WASAPI/Pulse loopback, PBO and accelerated paint (deferred), CinemaMod 116.

5) New risks found:
- electron.exe in the official v44.7.0 zip is unsigned (VERIFIED: pesig.py), the same AV/SAC posture as OBS's unsigned libcef.dll.
- The 1080p relay plus capture costs about 120% of one core (VERIFIED, Linux), hence the 720p default.
- Patent facts in the docs are too optimistic: US 9,356,620 runs to at least November 2030.

Confidence: medium. The main unknowns are Windows behaviour of the official Electron build (codecs, OSR rate, AV, firewall), YouTube behaviour in Electron from Brazilian residential IPs, and whether the AkashicFM release lands by 10-18.

## Reviewer verdict

Not refuted, but conditionally accepted with corrections. Moving the browser out of the game JVM (ADR-001) and spiking the official Electron 44.x build (ADR-002) remain the best options I could find for 10-25. I found no better engine. WebView2, CDP, JxBrowser, Ultralight, Servo, WPE and Gecko all still fail on at least one hard requirement.

Several load-bearing details are wrong or untested, though:
- ADR-013's "post-connect IP check in onResponseStarted, which aborts the load" cannot be built. That event cannot cancel a request and carries no IP field (VERIFIED, Electron v44.7.0 docs).
- ADR-003's fallback #1 (stdio pipes) would stall Electron's main thread on Windows, where Node pipe writes are synchronous (VERIFIED, Node docs). Fallback #2 (a named pipe through RandomAccessFile) has a likely read/write serialization deadlock (INFERRED).
- ADR-008 depends on capturing audio from webContents that live in per-screen session partitions (ADR-012). The probe only ever used defaultSession for both the capture page and the target (VERIFIED, probe main.js:26,49). Its token also went through argv, not stdin as ADR-003 specifies.
- The schedule has already slipped. As of the latest commit (ef50648, 2026-10-12 00:41 UTC), none of the 10-11 deliverables exist: no skeleton, no G1-E kit, no protocol spec, no insurance CI. Yet G1-E is scheduled for today.
- A few smaller facts need correcting:
  - enableLargerThanScreen is macOS-only.
  - In Angelica 2.2.20, DYNAMIC classes are always global, so the section-rebuild step is not needed.
  - The in-memory partition behaviour is documented, so it can be graded VERIFIED.
  - Raw input relay to followers stays risky even with in-memory sessions.

Nothing was run on Windows or in Minecraft for this review. Checks were docs, git tags, releases.json and the scratchpad probe sources.

| ADR | Title | Status |
|---|---|---|
| [ADR-001](#adr-001) | Process model: the browser runs in a separate host process (one per Minecraft client) from the start, not in-process | accepted |
| [ADR-002](#adr-002) | Engine for 10-25: official Electron 44.x (Chromium 152) offscreen host; CCBlueX JCEF on OBS CEF 7871 in a child JVM as fallback | proposed-needs-spike |
| [ADR-003](#adr-003) | Host-game IPC: loopback TCP with token, versioned binary framing, latest-frame-wins backpressure | accepted |
| [ADR-004](#adr-004) | Frame transport into Minecraft: CPU frames and direct glTexSubImage2D at RenderTickEvent START, with damage accumulation, Angelica-safe allocation and an upload budget | accepted |
| [ADR-005](#adr-005) | Screen tile entity rendering and culling under Angelica 2.2.20 with shader packs | accepted |
| [ADR-006](#adr-006) | Codecs and legal path: H.264/AAC only from a third party's codec build, downloaded on the player's PC from that publisher; no Akashic mirror | accepted |
| [ADR-007](#adr-007) | Audio baseline for every screen: Chromium plays to the OS, distance volume pushed into the page, engine-level hard mute | accepted |
| [ADR-008](#adr-008) | Spatial audio (Stretch): per-screen PCM capture -> host protocol -> AkashicFM ExternalAudioSources, with video delay for lip-sync | proposed-needs-spike |
| [ADR-009](#adr-009) | Mod build and dependency stack for the Forbidden pack: GTNHGradle + Jabel, Forge-only hard dependencies, no mixins for 10-25, soft integrations by reflection or IMC | accepted |
| [ADR-010](#adr-010) | Server authority, permissions and protection on Crucible behind a proxy | accepted |
| [ADR-011](#adr-011) | Runtime distribution: two layers (vendor engine downloaded once; host app and glue inside the mod jar), pinned hashes, consent, resume, no Akashic hosting for 10-25 | accepted |
| [ADR-012](#adr-012) | Multiplayer model: each client runs its own browser; server owns URL and video clock; shared screens use in-memory sessions; state convergence instead of trusting raw input | accepted |
| [ADR-013](#adr-013) | Security baseline for public screens | accepted |
| [ADR-014](#adr-014) | Platform and test matrix for 10-25 (replaces the GTNH-based matrix) | accepted |
| [ADR-015](#adr-015) | Schedule, gates and freeze points after the engine change | accepted |
| [ADR-016](#adr-016) | Engines and sharing models rejected for 10-25 | rejected-alternative |

## ADR-001

**Process model: the browser runs in a separate host process (one per Minecraft client) from the start, not in-process** — status: `accepted`

### Context

PLAN 4.1 / T-03 put CEF in-process for 10-25 and a host process after. This is the weakest point for a public spawn event.
- The field client is JVM 1.8.0_51 + LWJGL 2.9.1, with whole-client OOMs in 5 sessions and UnsatisfiedLinkError storms (VERIFIED, /home/user/Akashic-Voice/docs/FIELD_EVIDENCE_2026-10.md:13,30-35).
- In-process, a libcef/JNI fault kills every attendee's game.
- JCEF hard-codes settings.no_sandbox = true (VERIFIED, /home/user/ccbluex/java-cef/native/context.cpp:211).
- org.cef cannot be relocated and lives under LaunchClassLoader with Swing/EDT dependencies (VERIFIED, CefApp.java:16,237).
- The plan itself calls the host process the right architecture (VERIFIED, DECISIONS.md T-03), so shipping in-process now means a rewrite after the event.
- An Electron host probe showed crash isolation in both directions on Linux (VERIFIED, scratchpad/challenge/scripts/life.sh, life2.sh): SIGKILL of the Java parent left 0 host processes after 1.8 s, and SIGKILL of the host gave Java EOF.

### Decision

The engine runs in its own OS process, 'AkashicWebHost': one per client, multiplexing every screen that client renders.

MINECRAFT SIDE
- The mod is pure Java 8 bytecode: no org.cef, no JNI, no AWT, no native loading in the game JVM.
- A client-only HostSupervisor spawns the host with ProcessBuilder and owns its lifetime.

LIFETIME RULES
- The host exits on socket EOF.
- The host also polls the parent PID, which Java passes from RuntimeMXBean.getName().
- On host exit, Java shows the placeholder and relaunches with exponential backoff.
- The crash-loop guard counts host exits per session, not game crashes.
- At startup, sweep stale hosts via `tasklist /FI "IMAGENAME eq AkashicWebHost.exe"` plus a PID file, killing only PIDs we recorded.
- Kill switch, reason codes and the server-forced placeholder are unchanged.
- No shutdown mixin: closing the socket on JVM exit is enough, with a shutdown hook as belt-and-braces.

### Alternatives considered

- In-process JCEF (current T-03): rejected as primary. Native faults stay in javaw, the sandbox is off, and org.cef classloader conflicts remain.
- JCEF in a child JVM: kept as engine fallback (ADR-002).
- Own C++ CEF host: post-event efficiency path behind the same protocol.
- JxBrowser (out-of-process by design): rejected (ADR-016).

### Consequences

Positive:
- One process crash no longer kills Minecraft.
- The game JVM is identical on 8u51, Java 17-25 and lwjgl3ify.
- The sandbox becomes possible.
- The shutdown mixin and the org.cef packaging, CI checks and conflict checks disappear.
- The macOS main-thread clash with lwjgl3ify is avoided (INFERRED).

Negative:
- One extra frame copy across processes. Electron toBitmap takes 2.2-3.2 ms at 720p; the Java read is 1.16 ms per frame, about 2% of one core (VERIFIED, Linux).
- A protocol to version and test.
- About 385 MB PSS per host (VERIFIED, Linux).
- If the engine is Electron, a second language (JS) enters the repository.

### Confirm by

G1-E on the owner's Windows 10/11 PC, 10-12, using the Technic jre-legacy javaw 1.8.0_51. Pass:
- the host connects in under 2 s, 5/5 launches;
- killing javaw leaves zero AkashicWebHost processes after 5 s (Task Manager);
- killing the host gives Java EOF in under 1 s;
- the loopback listen raises no Windows Firewall prompt;
- no Defender quarantine of the renamed host in %LOCALAPPDATA%.
Decision recorded by 10-13 20:00 BRT.

### Fallback

If the process model itself fails on Windows (for example AV blocks spawned children, or there is no usable IPC), go back to in-process JCEF (original T-03), using the JCEF insurance CI artifact from ADR-002. Decide by 10-14. Unlikely, because JCEF also spawns helper exes (INFERRED).

### Reviewer amendments (binding)

- **[medium] Stock Electron fuses leave the host steerable by its environment. RunAsNode, the NODE_OPTIONS environment variable and the --inspect CLI flags are all enabled by default. A player with NODE_OPTIONS or ELECTRON_RUN_AS_NODE in their user environment (developers, other launchers) gets a broken or debuggable host. The plan to flip fuses after the event changes the binary hash. Launching through default_app.asar (`AkashicWebHost.exe &lt;appdir>`) also keeps default_app's CLI handling.**  
  Evidence: VERIFIED: Electron v44.7.0 docs/tutorial/fuses.md. runAsNode is 'Default: Enabled' (:26-32), nodeOptions 'Default: Enabled' (:56-62), nodeCliInspect 'Default: Enabled' (:68-74), and default_app.asar is in the app search order (:98-102).  
  Correction: In HostSupervisor, build the child environment explicitly: remove NODE_OPTIONS, ELECTRON_RUN_AS_NODE, ELECTRON_ENABLE_LOGGING, ELECTRON_* and NODE_* variables. Pass no --inspect. Install the host app into resources/app/ and delete default_app.asar at install time, then record both in the local manifest. This changes no vendor binary. Keep the post-event fuse flip as planned.

## ADR-002

**Engine for 10-25: official Electron 44.x (Chromium 152) offscreen host; CCBlueX JCEF on OBS CEF 7871 in a child JVM as fallback** — status: `proposed-needs-spike`

### Context

The current T-01 path (CCBlueX java-cef built by our CI against OBS cef_binary_7871) has never been built or run on Windows (VERIFIED, PROJECT_STATE 'What was NOT run'). It also needs native patches before it works on Java 8/LWJGL2:
- the JNI looks up org/lwjgl/sdl/SDLKeycode and SDLMouse (VERIFIED, CefBrowser_N.cpp:1807,2100);
- DataPointer needs LWJGL3 MemoryUtil;
- the audio JNI has defects (VERIFIED, audio_handler.cpp:15,43,51,56,76).

OBS CEF is Chromium 150.0.7871.252; Chrome 155 went stable on 2026-10-06 (VERIFIED, chromiumdash).

The official Electron 44.7.0 (2026-10-07) is Chromium 152.0.7977.130, and its build args include proprietary_codecs=true and ffmpeg_branding="Chrome" (VERIFIED, releases.json and electron build/args/all.gn).

The Linux container probe played the following in OSR (VERIFIED, scratchpad/challenge/probe-electron, Linux only):
- H.264/AAC MP4, native HLS and MSE avc1+mp4a;
- 30 paints/s at 720p and 28.8/s at 1080p;
- a Java FrameSink compiled --release 8 received 29.3-29.9 fps;
- PCM capture at 48 kHz stereo;
- 'ação ç $%&' typed back exactly through sendInputEvent.

Windows facts:
- The win32-x64 zip is 158,293,649 B, sha256 eee30dc8fa1f5ea95490e59f44e46ea68dd24c6e93d22facf70fe5c2d4c2665c (VERIFIED against SHASUMS256.txt).
- electron.exe and ffmpeg.dll in it carry no Authenticode signature (VERIFIED, scratchpad/challenge/scripts/pesig.py on x-win/).
- Windows codec presence is INFERRED from the 3,113,472 B vs 1,676,800 B (codec-free) ffmpeg.dll sizes.

### Decision

Pin Electron 44.7.0 win32-x64 (and linux-x64, sha256 3ae7d5bdad61c664486c6ab61361dd084d064c8c08af8d13a687096e18ce555a). Re-pin to the newest 44.x patch at the 10-16 engine freeze.

HOST APP
A small JS app (main.js, a capture page with preload, package.json) ships inside the mod jar with SHA-256 pins.

PER SCREEN
- One offscreen BrowserWindow: sandbox true, contextIsolation true, nodeIntegration false, backgroundThrottling false.
- setFrameRate(30) near and lower LOD far (ADR-004). stopPainting() beyond range.
- A per-screen session partition (ADR-012).
- A Chrome user agent via app.userAgentFallback; --autoplay-policy=no-user-gesture-required.
- enableLargerThanScreen or an equivalent workaround, because the OSR window is clamped to the display (VERIFIED: 1279x719 on a 1280x720 Xvfb).

RESOLUTION AND GPU
- Default screen resolution for 10-25 is 1280x720. 1080p is opt-in per screen by admins, because the 1080p relay plus capture costs about 120% of one core (VERIFIED, Linux, software).
- app.disableHardwareAcceleration() vs GPU OSR is decided from G1-E CPU numbers.

RANKED FALLBACK LADDER
- (a) Electron, software OSR.
- (b) Electron, GPU OSR.
- (c) CCBlueX java-cef fe8cc9e on OBS 7871 in a child JVM, speaking the same protocol. Its Windows CI workflow is written and run on 10-12 as insurance and dropped if G1-E passes.
- (d) In-process JCEF (ADR-001 fallback).
- No-codec degradation in any case: Electron's official codec-free ffmpeg.dll, with the reason code 'lives indisponíveis (sem codecs)'.

CinemaMod 116 is dropped as a fallback: Chromium 116, --disable-web-security by default, stale fork (VERIFIED, CefUtil.java:85-93; last commits 2024-10-22 / 2025-06-17).

### Alternatives considered

- JCEF in-process on OBS CEF 150 (current T-01): kept only as fallback (d).
- JCEF in a child JVM: fallback (c).
- Own C++ CEF host on OBS 7871: the most efficient option (one memcpy, native planar PCM), but it still needs the Windows CI build and has 6-9 days of variance. Post-event candidate behind the same protocol.
- jcefmaven/Spotify CEF 152: no H.264/AAC (VERIFIED, research 03). No-codec fallback only.
- WebView2, CDP screencast, JxBrowser, Ultralight, Servo/Ladybird, WPE, Gecko: rejected (ADR-016).

### Consequences

Positive:
- The Windows libjcef build and 4+ JNI patches leave the critical path.
- The owner can run G1 with the official zip and four JS files (no CI).
- Newer Chromium on Electron's 8-week cadence, with the latest three majors supported (VERIFIED, electron-timelines).
- Download is 158 MB instead of 345 MB.
- Linux Tier 2 needs no Akashic redistribution, and the Linux floor is GLIBC 2.25 (VERIFIED, objdump).

Negative:
- About 1k lines of JS to own, with lint and tests.
- The Node main thread carries the frame relay: +14 points of one core at 720p30 (VERIFIED, Linux).
- The audio path goes through WebAudio (ADR-008).
- The binaries are unsigned (the same AV/SAC posture as OBS).
- Electron OSR had 2026 CVEs, all patched. Avoid child windows and shared-texture mode for now.

### Confirm by

G1-E on 10-12 on the owner's PC, plus an Intel-iGPU laptop if available. The kit: the official zip with sha256 checked, the probe app, FrameSink.class (major 52) run on Technic's javaw 1.8.0_51, and a local media page. Pass, all of:
- (a) connect under 2 s, 5/5;
- (b) at 720p, at least 28 paints/s reach Java, with the Java reader at most 5% of one core;
- (c) the local H.264/AAC MP4, HLS, a YouTube watch page, a YouTube LIVE and a Twitch live each play with audible audio;
- (d) PCM RMS above 0 at 48 kHz stereo, with speakers silent while capturing;
- (e) 'ação ç $%&' comes back exactly; wheel and click register;
- (f)/(g) per ADR-001;
- (h) the Electron tree uses at most 50% of one core at 720p30 software and at most 600 MB private memory, with GPU-OSR numbers recorded;
- a &lt;select> dropdown renders in the OSR frame.
Go/no-go recorded 10-13.

### Fallback

If any of (b)-(e) fails on Windows, switch to ladder step (c): JCEF on OBS 7871 in a child JVM, using the insurance CI artifact. Same protocol, so HostSupervisor and the mod side are unchanged. It takes the Linux-VERIFIED build recipe plus the SDL-keycode/DataPointer patches. Decided 10-13, at most 2 days of slip, absorbed by the 10-19 buffer.

If codecs fail on every engine, ship codec-free with the reason code, and VOD/VP9 sites still work.

### Reviewer amendments (binding)

- **[medium] Making software OSR (app.disableHardwareAcceleration) the default is costly for video. The call is process-wide and turns off the GPU process, so every video is decoded in software. YouTube on Chromium 152 will usually pick VP9 or AV1 for VOD (dav1d AV1 is the most CPU-hungry) and H.264 only for some lives. Criterion (h), at most 50% of one core at 720p30, was set from a 4-vCPU Linux container and is likely to fail on the low-end iGPU or 8 GB machines the plan targets. Also, the clamp workaround named, 'enableLargerThanScreen', is macOS-only.**  
  Evidence: VERIFIED: docs/tutorial/offscreen-rendering.md: 'To enable this mode, GPU acceleration has to be disabled by calling app.disableHardwareAcceleration()'. VERIFIED: docs/api/structures/base-window-options.md:65-67: enableLargerThanScreen '_macOS_ … other OSes allow larger-than-screen windows by default'. VERIFIED: web-preferences.md:103: OSR deviceScaleFactor defaults to 1, so there is no Windows DPI-scaling trap. INFERRED: YouTube codec choice and Windows CPU cost.  
  Correction: In G1-E, measure 4 cells: {software, GPU OSR} × {YouTube as served, YouTube with an h264ify-style override that makes MediaSource.isTypeSupported return false for av01/vp09 on SHARED screens}. Pick the default from the iGPU numbers. Drop enableLargerThanScreen from the plan. On Windows, check instead that a 1280x720 OSR window gets full-size paints on a 1366x768 laptop. Split criterion (a) into a cold first launch (Defender scan of a 246 MB unsigned exe) and a warm launch.

- **[low] The download-size advantage is overstated. '158 MB instead of 345 MB' compares Electron with the full OBS zip, but ADR-011's own OBS fallback range-fetches 158.5 MB. Download size is therefore not a differentiator between the engines. The real differentiators are Chromium currency, the sandbox and no native build.**  
  Evidence: VERIFIED (distribution challenge): RemoteZipProbe fetched 158,526,133 B from the OBS zip. VERIFIED: the Electron win32-x64 zip is 158,293,649 B.  
  Correction: Reword the consequences: the downloads are equal (about 158 MB each); disk is about 320 MB after locale pruning for Electron versus 358.5 MB for OBS.

## ADR-003

**Host-game IPC: loopback TCP with token, versioned binary framing, latest-frame-wins backpressure** — status: `accepted`

### Context

A host process needs a transport that a Java 8 game can use without JNI.
- The Linux probe moved 720p30 frames at 108-110 MB/s, 1.16 ms per read, about 2% of one core; 1080p at 233 MB/s, 4.2% (VERIFIED, probe-electron/java/FrameSink.java, JDK 21 running major-52 classes).
- Shared memory on Windows from Java 8 needs JNI: FileChannel.map is file-backed only (INFERRED).
- Whether a loopback listen from javaw triggers a Windows Firewall prompt, or AV interference, is unknown.

### Decision

HANDSHAKE
- Java listens on 127.0.0.1:0.
- The host gets the port as an argument and a 128-bit random token on its stdin, so the token never appears in the process list.
- The host connects, sends the token, its host-app version and the protocol major. A mismatch closes the connection.

FRAMING
Each message: [magic, protocolVersion, type, screenId, seq, length].

Host to game:
- FRAME: w, h, dirty rect and a BGRA crop of only the dirty rect.
- PCM: screenId, host-monotonic pts in µs, 48 kHz s16 stereo.
- STATE: URL, title, media state at 4 Hz for sync.
- EVENT: load error, crash, audible.

Game to host:
- CREATE/DESTROY screen, NAVIGATE.
- INPUT: mouse, wheel, key down/char/up.
- POLICY: allowlist and blocklist.
- MUTE/VOLUME, FRAMERATE/PAINTING, QUIT.

BACKPRESSURE
The host keeps at most one pending frame per screen and drops the older one when the socket is busy. Damage correctness is restored on the Java side (ADR-004).

THREADS
One Java reader thread, a daemon named AkashicWeb-Host-Reader. It never touches Minecraft or World objects.

### Alternatives considered

- stdio pipes of the child process: no socket, so no firewall question. Electron stdio on Windows GUI-subsystem builds is INFERRED to work but not tested. This is fallback #1.
- Windows named pipe: Java 8 opens \\.\pipe\name with RandomAccessFile; Node creates it with net.createServer(path). Fallback #2.
- Shared memory: needs a native N-API addon and JNI or a pagefile mapping. Post-event, to remove the relay copy.
- JCEF in-process: no IPC at all (ADR-001, rejected).

### Consequences

- Pure Java 8 java.net/java.nio, so the same code runs on 8u51, 17-25 and lwjgl3ify.
- Protocol versioning lets the host app and the mod evolve together.
- Cost: one socket copy per frame, up to 110 MB/s at 720p30 per screen.
- Multiple 1080p screens could saturate the Node main thread. This is mitigated by the 720p default, dirty crops, LOD and stopPainting.

### Confirm by

G1-E (b) and (g) on 10-12:
- at least 28 frames/s at 720p reach Java with the reader thread at most 5% of one core;
- no Windows Firewall prompt;
- no AV complaint about 127.0.0.1 traffic, checked with Defender and, if the owner has one, Avast or Kaspersky.

### Fallback

Switch the transport behind the same message framing:
- a firewall or AV problem on loopback goes to stdio pipes, then to a named pipe;
- relay CPU too high goes to a lower default resolution (960x540) now, and shared-memory frames after the event.

### Reviewer amendments (binding)

- **[medium] Both transport fallbacks are flawed, and the primary handshake was not exercised as specified.
(a) Fallback #1, stdio pipes: Node's process.stdout and stderr writes to pipes are synchronous on Windows. Frame relay of 110-233 MB/s over stdout would block Electron's main thread whenever Java's reader lags, freezing input, navigation and every screen.
(b) Fallback #2, a named pipe opened by Java 8 RandomAccessFile: the handle is opened without FILE_FLAG_OVERLAPPED. Windows serializes I/O on such a handle, so a blocking read on the reader thread can stall writes from the input thread.
(c) The token-on-stdin handshake was never run. The probe passes the token on argv. stdin in a GUI-subsystem electron.exe main process on Windows is untested.**  
  Evidence: VERIFIED: Node v24 docs/api/process.md:4236-4239: 'Pipes (and sockets): synchronous on Windows, asynchronous on POSIX' (Electron 44.7.0 ships Node 24.21.0 per releases.json). VERIFIED: probe main.js:8 reads TOKEN from the --token= argv. (b) INFERRED from Win32 synchronous-handle semantics (CreateFile without FILE_FLAG_OVERLAPPED); not tested.  
  Correction: Keep loopback TCP as the only frame transport. If loopback is blocked, use stdio for control messages only, and move frames to a named pipe that the HOST creates (net.createServer on \\.\pipe\…, async in Node). Java opens TWO one-directional pipe instances, one for reads and one for writes, never one RandomAccessFile used by two threads. Add to G1-E: token delivered on stdin to the renamed exe, with an environment-variable fallback (inherited only by the child); kill-javaw cleanup under each transport.

## ADR-004

**Frame transport into Minecraft: CPU frames and direct glTexSubImage2D at RenderTickEvent START, with damage accumulation, Angelica-safe allocation and an upload budget** — status: `accepted`

### Context

The CPU upload path works on every stack the pack can have: LWJGL2 compat GL, Angelica's GL backend, Angelica SDL_GPU (TextureOps.java:45-66) and lwjgl3ify (VERIFIED, code). Production precedents:
- WATERMeDIA 2.1.x RenderAPI.java:152-159;
- CinemaMod MCEF MCEFBrowser.java:205-224;
- CCBlueX CefTextureRenderer.java:322-361.

The plan's 'rotating slot receives only dirty rects; upload newest' loses damage (INFERRED, logic), and it lacks several details:
- the allocation format: BGRA storage under SDL_GPU only via glTexImage2D(...GL_RGBA8...GL_BGRA...) (VERIFIED, SDLGPURenderBackend.java:1511-1523);
- GL_CLAMP is removed in core profile (VERIFIED, GLStateManager.java:7605-7616);
- a stray PIXEL_UNPACK_BUFFER binding;
- an upload budget.

The pack's Angelica 2.2.20 runs use_no_error_g_l_context=true, so GL errors are undefined behaviour (VERIFIED, research/07). Upload cost on any Windows GPU is unmeasured.

### Decision

READER THREAD
- It applies each FRAME crop to a private full-size canvas.
- It copies the canvas into a free slot of a 3-slot ring of direct, native-order ByteBuffers (0.29 ms at 720p, VERIFIED container).
- It ORs the dirty rect into a pendingDamage bounding box, then publishes the slot atomically (drop oldest).

RENDER THREAD (RenderTickEvent START)
It takes the newest slot plus the accumulated damage and uploads that bounding box, or the full frame when the box is at least 50% of it (CCBlueX threshold).

TEXTURE
- Allocate with glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, null).
- GL_LINEAR, GL_CLAMP_TO_EDGE, MAX_LEVEL 0.
- Reallocate only on resize; drop frames whose size does not match.
- Validate every size and offset before calling GL (no-error context).

UPLOAD HYGIENE (from our own GL11/GL12 calls so Angelica redirects them)
- If GL 2.1: save PIXEL_UNPACK_BUFFER_BINDING and bind 0.
- UNPACK_ALIGNMENT 4, ROW_LENGTH = w, SKIP_PIXELS/ROWS = bbox origin.
- glTexSubImage2D, then reset ROW_LENGTH/SKIP and restore the binding.
- On any exception, draw the placeholder.

BUDGET
At most about 8.3 MB uploaded per render frame (one 1080p frame), nearest/largest screen first. Skipped screens keep accumulating damage.

SOURCE-SIDE LOD (host)
- setFrameRate: 30 near, 10-15 mid, 1-5 off-frustum.
- stopPainting() beyond render range.
- This replaces the planned N_WasHidden JNI patch (not bound in CCBlueX, VERIFIED CefBrowser_N.cpp:1633-1644). Screens within audio range keep running (ADR-008).

INSTRUMENTATION FROM DAY 1, SHOWN IN /web doctor
- per-upload render-thread time p50/p95/p99;
- bytes/s;
- paints received, uploaded and dropped.

### Alternatives considered

- Persistent-mapped PBO ring (GL44.glBufferStorage exists in LWJGL 2.9.1, VERIFIED javap): post-event Phase A, only if p95 exceeds 1 ms. It is CPU-emulated under SDL_GPU.
- Accelerated paint: Electron OSR useSharedTexture, or CEF OnAcceleratedPaint plus a native D3D11 copy plus WGL_NV_DX_interop2. Post-event Phase B, Windows only, opt-in with a GPU allowlist.
- EXT_memory_object_win32: rejected as primary. Intel has not implemented D3D11 import (VERIFIED, IGCIT #1143), and AMD before 26.1.1 leaks.
- YUV/NV12: rejected. OSR is BGRA only, and CPU conversion costs 3.5-5.7 ms (VERIFIED container).
- Render-thread pumping: n/a with a host process.
- Linux dma-buf: rejected (needs an EGL context).

### Consequences

- Correct pixels even when the render thread skips frames.
- No per-pixel swizzle under any Angelica backend.
- A predictable worst-case render-thread cost.
- The render-thread driver copy stays: INFERRED 0.3-0.6 ms at 720p.
- The PBO and zero-copy upgrades stay open behind the same consumer interface.

### Confirm by

T-UPLOAD measurements on the owner's PC (plus an iGPU laptop if available):
- first at the in-game first frame (target 10-13/10-14);
- formally at G3 on 10-16.

Pass:
- 720p30 upload p95 at most 1.0 ms and p99 at most 2.0 ms (2.0/4.0 on the iGPU);
- frame-time p99 regression at most 2 ms;
- 0 pixel mismatches over 3,600 frames with the render loop throttled to 20 fps (glGetTexImage vs the latest full slot every 30 frames; red stays red);
- median FPS drop at most 25% at a fixed position over 60 s.

### Fallback

If p95 fails:
- enable the PBO ring behind a flag on the Angelica GL backend;
- and/or drop the default to 960x540;
- and cap uploads to the nearest screen.

## ADR-005

**Screen tile entity rendering and culling under Angelica 2.2.20 with shader packs** — status: `accepted`

### Context

Pack facts (D-013 / research 07, VERIFIED from the owner's files):
- Angelica 2.2.20 with the BSL v8.2.09 and Complementary Unbound r5.9.3 shaderpacks;
- section_gated_tesr_culling, use_occlusion_culling, async_occlusion_mode EVERYTHING, use_entity_culling;
- entityculling 1.6.4 and FpsReducer 1.10.3 in mods/.

The mod-stack challenge assumed Angelica 2.1.33 from an older repo snapshot and proposed INFINITE_EXTENT_AABB. That premise is superseded. In 2.2.20 (VERIFIED, git show 2.2.20 in /home/user/gtnewhorizons/Angelica):
- TileEntityRenderBoundsRegistry.registerDynamicClass(String) exists;
- DYNAMIC classes are tested with the live getRenderBoundingBox() (CeleritasWorldRenderer.java 2.2.20:553-560);
- a TE whose box extends beyond its 16^3 section is put in globalBlockEntities, so it is not section-culled (AngelicaChunkBuilderMeshingTask.java 2.2.20:175-191). That classification happens at meshing time;
- the config key dynamicBoundsTileEntities exists (AngelicaConfig.java 2.2.20:326).

Angelica's shadow pass renders TEs again (VERIFIED, ShadowRenderer.java:810).

### Decision

BOUNDS
- getRenderBoundingBox() returns the true multiblock box.
- At client init, if Angelica is loaded, call registerDynamicClass(&lt;our TE class name>) by reflection. No compile dependency; failure is logged and ignored.
- When the screen size changes (description packet or formation), call markBlockRangeForRenderUpdate over the origin's section so Angelica reclassifies the TE as global.
- The owner also adds our class to Angelica's dynamicBoundsTileEntities in the pack config, as belt-and-braces.

TESR
- Draw-only: never uploads.
- Full-bright quad, lightmap 240/240.
- Respects the shadow pass: skip or draw cheaply.

PACK SETTINGS FOR THE EVENT (owner)
- Raise or disable FpsReducer's idle reduction (idleFPS 10 after 300 s).
- Add our TE to entityculling's blockEntityWhitelist if G3 shows culling.

Test both shaderpacks at G3.

### Alternatives considered

- INFINITE_EXTENT_AABB plus our own frustum/distance test in the TESR: kept as the automatic fallback if registerDynamicClass is missing or misbehaves. Angelica then never culls it, so we must.
- A finite cached box without registration: rejected. It goes stale when the multiblock box arrives after meshing.
- Angelica TESR material API ('angelica|tesr', apiVersion 2): post-event, for a shader-correct emissive screen.
- Drawing after the composite pass: post-event investigation.

### Consequences

- Angelica culls correctly for free, and the screen stays visible with the origin block off-screen.
- Works on vanilla too, which frustum-tests the live box.
- Some blocklight tint under shaders is accepted for 10-25.

### Confirm by

G3, 10-16, Technic Windows client on Java 8u51 with Angelica 2.2.20, with BSL off, then BSL on, then Complementary on. Pass:
- a 16x9 screen renders with the origin block off-screen and from behind a wall corner;
- no flicker after resizing the multiblock;
- no GL error or crash;
- the texture is uploaded once per frame, not in the shadow pass.

### Fallback

Switch the class to INFINITE_EXTENT_AABB with our own frustum and distance culling in the TESR. Ask the owner to whitelist the TE in entityculling.

### Reviewer amendments (binding)

- **[low] There are two inaccuracies about Angelica 2.2.20 behaviour.
(a) Calling markBlockRangeForRenderUpdate on resize 'so Angelica reclassifies the TE as global' is unnecessary for a registered class. DYNAMIC classes are always put in globalBlockEntities, and the section-escape test applies only to STATIC classes.
(b) The real ordering hazard was missed. classify() caches the result per class forever, and DYNAMIC applies only if the name is registered before the first classify() of that class. A registration that runs after the first screen chunk meshes (late init, or reflection failing silently) leaves the class STATIC or INFINITE for the session.**  
  Evidence: VERIFIED: git show 2.2.20 AngelicaChunkBuilderMeshingTask.java:173-175 (`if (boundsClass == INFINITE || boundsClass == DYNAMIC) isGlobal = true`). TileEntityRenderBoundsRegistry.java (2.2.20) classify() caches into classRegistry, and registerDynamicClass only adds to a name set. CeleritasWorldRenderer.java:556-557 frustum-tests DYNAMIC classes with the live getRenderBoundingBox(). Angelica's own ClientProxy.preInit:133 registers config names in preInit. VERIFIED: research/07 takes Angelica 2.2.20 from the TEOF-3.6 zip, superseding the 2.1.33 public snapshot.  
  Correction: Call registerDynamicClass reflectively in our client preInit, and verify by reflection afterwards (call classify on a dummy instance, or log the set membership). Keep the pack-config dynamicBoundsTileEntities entry as the primary belt. Drop the section-rebuild-on-resize step, or keep it only for the INFINITE fallback. Angelica 2.2.20 targets a GL 3.3+ core profile (README.MD:17-18), so the 'If GL 2.1' branch in ADR-004 is moot for this pack.

## ADR-006

**Codecs and legal path: H.264/AAC only from a third party's codec build, downloaded on the player's PC from that publisher; no Akashic mirror** — status: `accepted`

### Context

YouTube live (AAC only, H.264 HLS) and Twitch need H.264/AAC. Chrome branding adds exactly the h264 and aac decoders and the aac demuxer (VERIFIED, chromium third_party/ffmpeg config diff).

Candidate sources:
- Electron's official build (proprietary_codecs=true, VERIFIED all.gn), hosted on github.com/electron/electron/releases;
- OBS cef_binary_7871 (sha256 54cd191a...f341, pinned by obs-studio CMakePresets.json:54-66, VERIFIED), hosted on cdn-fastly.obsproject.com.

Neither OBS nor OpenJS is a Via LA licensee by name (INFERRED, name search). The Eclipse IP team flagged Electron's bundled codecs (VERIFIED, iplab #7912).

The plan's patent facts are too optimistic:
- Via LA's AVC Attachment 1 (2026-09-28) lists US patents granted in 2026 and Brazilian patents;
- US 9,356,620 runs to at least November 2030 (VERIFIED).

Brazil: Lei 9.279 art. 43 I covers private, non-commercial acts, and art. 42 §1 covers contributing to infringement (text VERIFIED, application INFERRED). The owner accepted 'codec-enabled Chromium' (D-011) with OBS as the context.

### Decision

POSTURE
- Akashic never hosts or redistributes Chrome-branded FFmpeg for 10-25.
- The player's client fetches the engine from the publisher's own server, checking a SHA-256 pinned in the mod jar.
- Primary source: the Electron 44.x official win32-x64 zip (ADR-002). Fallback: OBS 7871 (ADR-011 range-fetch).
- Codec-free degradation: Electron's official codec-free ffmpeg.dll (or Spotify CEF 152 under the JCEF fallback), with a pt-BR reason code.

CONSENT SCREEN (pt-BR, owner approves the text)
Names the component, its publisher, the download size and the disk size. It makes no legal claims.

LICENCE NOTICES
Keep LICENSE, LICENSES.chromium.html and CREDITS in the install folder.

DOCS CORRECTIONS
- H.264 patents run to at least November 2030, and Via LA lists Brazilian patents.
- AAC risk is lower: Fedora/Red Hat ship AAC-LC.
- One question for a Brazilian IP lawyer: does server monetisation change the art. 43 I / 42 §1 analysis?

IF THE OBS FALLBACK IS USED
Send OBS (WizardCM/RytoEX) a courtesy note, and run a daily CI HEAD+SHA check of the CDN file until 10-25.

AFTER THE EVENT
Spike a Windows OS-decoder-only build: CEF #3559 patch, media_use_openh264=false, and a MediaFoundationAudioDecoder AAC-LC/HE-AAC patch (today only xHE-AAC: media_foundation_audio_decoder.cc:101-107, VERIFIED). This is the cleanest legal posture.

### Alternatives considered

- Self-built CEF with codecs on a paid runner: Akashic becomes the distributor (worst posture) and it does not fit before 10-25. Rejected.
- OS-decoder-only CEF: post-event spike, 7-12 days.
- CinemaMod 116 natives: Chromium 116, stale, legally no better. Dropped.
- WebView2/Edge or Chrome for Testing (vendor-licensed codecs): no offscreen rendering, no audio capture, Windows only. Rejected for 10-25 (ADR-016).
- JxBrowser: codec licensing pushed to the customer. Rejected.
- OpenH264, OS decoders without an AAC patch, VLC/WaterMedia: technically insufficient (Constrained Baseline only; no AAC) or not a browser. Rejected.

### Consequences

- Akashic is a possible 'contributor', never the distributor (INFERRED).
- The posture is identical for Electron and OBS.
- Security currency improves with Electron (152 vs 150).
- A dependency on GitHub (Electron) or Fastly (OBS) availability, with manual import as the escape (ADR-011).

### Confirm by

Two checks, both by 10-13:
- G1-E codec arm, 10-12/13: the official build plays YouTube live, Twitch live and an H.264/AAC MP4 with audio for 60 s each. The control arm (codec-free ffmpeg.dll swapped in, 5 minutes) fails YouTube live and Twitch, which proves the codec dependency still exists in October 2026. If the control plays YouTube live, re-open this decision.
- The owner confirms in writing that D-011 covers Electron's bundled ffmpeg.

### Fallback

If the owner does not accept Electron's codecs: use the OBS 7871 source with the JCEF engine (ADR-002 step c), or ship codec-free with the 'lives indisponíveis' reason code.

### Reviewer amendments (binding)

- **[medium] The codec control arm is ambiguous as designed. With GPU acceleration on, Chromium on Windows decodes H.264 through D3D11 hardware decoders, which do not need ffmpeg's h264 decoder, and MSE support answers come from the build flag rather than from ffmpeg.dll. With the codec-free ffmpeg.dll swapped in, YouTube live may therefore still show video with silent audio and isTypeSupported('mp4a.40.2') may still return true. 'Control plays YouTube live, re-open the decision' would then be a false trigger, or the arm reads as a pass for the wrong reason.**  
  Evidence: INFERRED from Chromium architecture: the GPU-process hardware decoders on Windows and the proprietary_codecs compile flag (media_options.gni, cited in the codec challenge). The codec challenge VERIFIED that Chrome branding adds only the h264 and aac software decoders and the aac demuxer. Not tested with Electron.  
  Correction: Define the control-arm outcome on BOTH audio RMS > 0 and video currentTime progress, and run it twice: with app.disableHardwareAcceleration() and without. Record isTypeSupported results separately; they are not evidence of decode. As a post-event legal option, note that 'Electron official + codec-free ffmpeg + GPU hardware H.264' covers video. AAC is still the blocker, which matches the MediaFoundation AAC patch analysis.

## ADR-007

**Audio baseline for every screen: Chromium plays to the OS, distance volume pushed into the page, engine-level hard mute** — status: `accepted`

### Context

3D audio through AkashicFM (D-005) is a Stretch for 10-25 and depends on a cross-mod release. A guaranteed baseline is required that can never leak full-volume, non-positional sound from far or unloaded screens. Web Displays' complaint was audio audible across the map, not tied to MC volume (VERIFIED, research 02:23). Page-JS-only muting is unreliable because sites rewrite element volume.

### Decision

OS MODE (default and fallback)
- The host lets Chromium output to the default device.
- A per-screen script in every frame (a preload with webFrame / executeJavaScript in all frames) sets each media element's volume to distance gain x MC master x records. The gain follows the AkashicFM GainModel.sourceGain curve, copied with attribution, not linked.
- Re-applied at about 10 Hz.

HARD MUTE
Through the engine API (Electron webContents.setAudioMuted(true); CEF SetAudioMuted under the JCEF fallback). Applied out of audio range, in menus, and on world unload, disconnect, dimension change, kill switch and host restart.

RULE
Muting never depends on page JS.

### Alternatives considered

- Spatial capture into AkashicFM: ADR-008 (Stretch).
- In-page WebAudio spatialisation (PannerNode driven by AkashicFM data): a post-event middle tier. CORS/CSP/iframe fragility.
- Own OpenAL engine in AkashicWeb: rejected. It contradicts D-005, and the LWJGL2 AL.destroy stub-reset hazard applies (VERIFIED, Akashic-Voice issue #3).

### Consequences

- Zero added latency and perfect lip-sync.
- Not positional, no occlusion or reverb.
- WebAudio-generated sound is only covered by the hard mute.

### Confirm by

Checked twice:
- G1-E: setAudioMuted silences a playing YouTube live within 100 ms and unmuting restores it.
- G3: walking out of range fades and then hard-mutes; 10 join/leave/break/dimension-hop cycles leave no lingering audio.

### Fallback

If the setAudioMuted binding misbehaves: destroy the screen's browser out of range (stronger but slower to resume).

### Reviewer amendments (binding)

- **[low] 'A preload with webFrame … in every frame' does not work as stated. Electron preloads run only in the main frame unless nodeIntegrationInSubFrames is enabled, an experimental option that loads every preload into every iframe. YouTube embeds and Twitch players inside third-party pages are cross-origin iframes, so their media elements would get no distance volume.**  
  Evidence: VERIFIED: docs/api/structures/web-preferences.md:11-14: nodeIntegrationInSubFrames is an 'Experimental option … All your preloads will load for every iframe'. Without it, preloads load only in the main frame.  
  Correction: Apply the volume script from the main process to every frame: webContents.mainFrame.framesInSubtree plus 'frame-created' / 'did-frame-finish-load', calling webFrameMain.executeJavaScript in an isolated world. Keep the engine-level setAudioMuted as the only hard guarantee, as already stated.

## ADR-008

**Spatial audio (Stretch): per-screen PCM capture -> host protocol -> AkashicFM ExternalAudioSources, with video delay for lip-sync** — status: `proposed-needs-spike`

### Context

Electron path:
- A capture page using setDisplayMediaRequestHandler({audio: WebFrameMain}) captured 48 kHz stereo with AEC/AGC/NS off (RMS 0.062).
- Local echo is off by default, so the source should be silent (VERIFIED on Linux; speaker silence INFERRED).

CEF path (fallback engine):
- CefAudioHandler uses loopback with mute_source=true on 7871/7977 (VERIFIED, audio_loopback_stream_creator.cc:70-72).
- It leaks audio at every capture start and after 2 s silences, and un-mutes on capture errors (VERIFIED, alloy_browser_host_impl.cc:1172-1197; audio_capturer.cc).
- Chromium snoopers still receive data while the WebContents is muted (VERIFIED, output_controller.cc), so pre-mute fixes the leak (INFERRED).

AkashicFM HEAD fa54626 blockers:
- RadioAudioController stopAll/retainOnly (VERIFIED, :156,275-283);
- blocking ring writes;
- a radio-tuned latency of about 220-250 ms plus OpenAL Soft 1.15.1 WASAPI at about 93 ms (VERIFIED, Playback.java:29-38; ALc.c:2708-2710).
ITU-R BT.1359 acceptability is −185 ms of audio lag.

### Decision

Two runtime modes per screen with no engine restart: SPATIAL or OS (ADR-007). Mode selection: client option, server flag and AkashicFM API presence.

SPATIAL PIPELINE
1. The host captures each audible screen's webContents audio via the capture page (AudioWorklet, channelCount 2, AEC/AGC/NS false, video track stopped, local echo off). The capture page runs with contextIsolation true and contextBridge; the probe used false and that must change.
2. The screen is pre-muted with setAudioMuted(true), only if G1-E shows capture still flows while muted.
3. PCM frames go out stamped with a host-monotonic pts.
4. A Java tap (no allocation per packet) maps pts to the local clock with a min-filtered offset and writes into a non-blocking, drop-oldest timed ring.
5. The ring feeds AkashicFM through a compileOnly API plus a bridge class, loaded only after Loader.isModLoaded("akashicfm") and an API_VERSION check.
6. LEFT/RIGHT emitters at the screen edges (clamped ±4 blocks), occlusion and room reverb, a 'Telas' volume, nearest-first 2 audible screens (configurable 1-4).

AKASHICFM RELEASE IN THE PACK BY 10-18 MUST ADD
- ExternalAudioSources.register;
- external sources not stopped by radioVolume=0;
- a non-blocking TimedPcmRing.offer;
- AudioFeed local-clock hooks;
- a per-feed low-latency Playback profile (chunk 960-1024 frames, prebuffer 3);
- API_VERSION.

LIP-SYNC
- A video delay ring Dv = feed latency + device estimate − 1 frame, applied only while that screen's audio is PLAYING.
- A client A/V offset slider of ±250 ms.

AUTOMATIC FALLBACK
SPATIAL drops to OS mode on capture error, or when a screen is audible but no packets arrive for 2 s.

Off-frustum screens within audio range keep running; never stopPainting them, only skip uploads.

JCEF fallback engine: CefAudioHandler with the SetAudioMuted pre-mute binding, parameter read-back to 48000 Hz stereo, cached-loader FindClass, delivery without LWJGL3, and the Win11 timer-throttling opt-out (obs-browser PR #484).

Go/no-go 10-21 as planned. Never ship SPATIAL without silent-source proof.

### Alternatives considered

- WASAPI process loopback: one mixed stream for all screens, cannot be silent and captured at once, needs build 20348+ (VERIFIED, MS docs). Rejected.
- PulseAudio/PipeWire null sink: Linux only, mixed. Rejected.
- Media side-channel decoding (yt-dlp-style): conflicts with D-001/D-008, drifts. Rejected.
- In-page WebAudio spatialisation: a post-event middle tier.
- Own OpenAL engine: emergency only, if the AkashicFM release slips past 10-19.

### Consequences

- Real 3D, occluded, reverberant screen audio with the AkashicFM quality bar.
- Costs: an AkashicFM release dependency, a video delay of about 150-250 ms while spatial audio plays, about 192 KB/s PCM per screen on the socket, and WebAudio hop latency (ScriptProcessor was about 85 ms in the probe; AudioWorklet expected lower, INFERRED).

### Confirm by

G1-E (d) on 10-12, extended:
- with pre-mute on, OS output stays silent during the first 500 ms after every play or resume and after 3 s silences;
- PCM is 48 kHz stereo at about 100 packets/s;
- capture latency is measured on a flash/beep clip;
- pts-vs-nanoTime offset jitter stays within ±5 ms over 10 min.

G3 on 10-16/17, in game on Java 8/LWJGL2:
- A/V offset within −125..+45 ms on the flash/beep clip, still within it after 10 forced hitches;
- two audible screens independently positioned, with zero OS output.

Go/no-go 10-21.

### Fallback

Ship OS mode (ADR-007) for 10-25 if capture cannot be silent and captured at once, if A/V fails, or if the AkashicFM release is not in the pack by 10-18.

### Reviewer amendments (binding)

- **[medium] The SPATIAL capture design combines two things that were never tested together. setDisplayMediaRequestHandler is a per-session API, and ADR-012 gives every shared screen its own in-memory partition. The probe registered the handler on session.defaultSession, with both the capture page and the target window in defaultSession. It is unverified whether a capture page in one session can capture audio from a WebFrameMain in another partition, and whether N concurrent captures work. The probe's capture window also ran with contextIsolation false and sandbox false, and capture was started through executeJavaScript with userGesture=true.**  
  Evidence: VERIFIED: scratchpad/challenge/probe-electron/app/main.js:26 (defaultSession permission handler), :49-52 (defaultSession.setDisplayMediaRequestHandler returning win.webContents.mainFrame), :54 (capture window contextIsolation:false, sandbox:false), :58 (executeJavaScript('startCapture()', true)). VERIFIED: docs/api/session.md:1117-1146 defines the handler as `ses.setDisplayMediaRequestHandler`, with audio as a WebFrameMain that 'will capture audio from the webContents that contains that frame', and enableLocalEcho defaulting to false. Cross-session capture: not tested (INFERRED risk).  
  Correction: Add a G1-E case: 2 screens in 2 non-persist partitions, captured concurrently. Run it with the capture page in its own session and again with one hidden capture window per screen partition, and record which works. If cross-session capture fails, create the capture window inside each screen's partition. That costs one extra renderer per audible screen, so re-measure RAM against the 600 MB budget. Also note that capture with enableLocalEcho=false already mutes local playback. Pre-mute only closes the window before getDisplayMedia resolves and the period after a capture stops. Test both.

## ADR-009

**Mod build and dependency stack for the Forbidden pack: GTNHGradle + Jabel, Forge-only hard dependencies, no mixins for 10-25, soft integrations by reflection or IMC** — status: `accepted`

### Context

D-004 asks for the GTNH buildscript plus GTNHLib and UniMixins. D-013 / research 07 (VERIFIED, the owner's files) shows:
- UniMixins 0.3.1, GTNHLib 0.11.51 and ModularUI2 2.3.85 on client and server;
- Angelica 2.2.20 and Hodgepodge 2.7.189;
- no lwjgl3ify jar on the client; lwjgl3ify 3.0.31 on the Crucible server.

usesMixins=true writes TweakClass MixinTweaker into the manifest (VERIFIED, GTNHGradle ToolchainModule.java:431-439), which makes UniMixins a launch requirement and puts us in the mixin-conflict surface of Hodgepodge, Angelica, ArchaicFix and CoreTweaks. The only planned mixin, the shutdown hook, is unnecessary with a host process (ADR-001).

GTNHLib has broken API before: AkashicFM needed a second reload path below 0.9.62 (VERIFIED, FASE7-RESULTADOS.md:295-298).

The in-house precedents:
- Akashic Voice builds with the GTNH convention, usesMixins=false, Forge-only (VERIFIED, its gradle.properties).
- AkashicFM 1.2.0 uses Gradle 9.7.1 with gtnhsettingsconvention 2.0.33 (VERIFIED).

### Decision

BUILD
- com.gtnewhorizons.gtnhsettingsconvention 2.0.33, Gradle 9.7.1, autoUpdateBuildScript=false.
- enableModernJavaSyntax=jabel (--release 8, class major 52). Not jvmDowngrader, whose default stubs provider silently adds a GTNHLib runtime dependency (VERIFIED, JVMDowngraderModule.java).
- usesMixins=false, no coreModClass, no access transformer. JUnit 5.

HARD RUNTIME DEPENDENCIES FOR 10-25
Minecraft 1.7.10 and Forge 10.13.4.1614 only. GTNHLib, UniMixins and ModularUI2 are allowed later (they are present on both sides) but are not used on the 10-25 critical path.

FOR 10-25
- Config: a typed wrapper over Forge Configuration plus GuiConfig/IModGuiFactory.
- GUIs: vanilla GuiScreen, needed anyway for raw ABNT2 key capture.
- Networking: SimpleNetworkWrapper plus our S2CHello. @Mod(acceptableRemoteVersions="[1.0,2.0)") tied to the protocol major, so server-only or client-only hotfixes do not force everyone to update.
- Multiblock: the WD-derived flood-fill, credited to montoyo (D-003).
- Pieces copied with attribution from AkashicFM (same owner): RateLimiter, TextSanitizer, Permissions, AuditLog, UrlPolicy, ServerActionQueue, ClientTaskQueue, ClockMath. Copied, not depended on.

SOFT INTEGRATIONS (presence-checked)
- Angelica registerDynamicClass (ADR-005).
- Bukkit permissions (ADR-010).
- AkashicFM bridge (ADR-008).
- WAILA IMC provider, only if time allows.

CI CHECKS ON THE JAR
- every class is major 52;
- no TweakClass or FMLCorePlugin;
- common and server classes do not reference net/minecraft/client, org/lwjgl or java/lang/ProcessBuilder;
- nothing references org/cef, org/lwjgl/system, sdl or glfw;
- no native binaries;
- host-app files match their pinned hashes.

### Alternatives considered

- Plan as written (hard GTNHLib + UniMixins + shutdown mixin): it would load, since both are present, but it adds launch-time and conflict risk for no 10-25 value.
- FPGradle 4.1.0, raw RetroFuturaGradle, the anatawa12 ForgeGradle fork: they split the toolchain from the in-house mods, with no gain.
- ModularUI2 for GUIs: post-event, for the configurator, rights and friends lists. It hides the low-level keyboard events we need.
- StructureLib: not in the pack, and it does not fit a variable flood-fill rectangle.
- org.cef in a child classloader: moot with a host process.

### Consequences

- Nothing in the manifest can stop the game from launching.
- It behaves the same when the pack updates GTNHLib, UniMixins or Angelica.
- It loads cleanly on ForbiddenMobile (placeholder).
- Cost: no @Config auto-GUI or config sync. A small hand-written config and the S2CHello policy push.
- D-004 must be reworded as a quality bar, not a dependency list (owner).

### Confirm by

Two checks:
- Skeleton CI green on 10-11/12: major-52 check, manifest check and the reference scans clean.
- Skeleton boots on 10-14/15: in a Technic Windows client (8u51, the live mod list) and on the Crucible staging-67b53034 / RFB 1.1.0 / Java 21 server with the owner's mods, with zero AkashicWeb errors.

### Fallback

If a specific need appears (for example netty-side packet stamping), add UniMixins and GTNHLib as declared hard dependencies (required-after) with a minimal mixin set. They are present on both sides (D-013), so the risk is acceptable after 10-25.

## ADR-010

**Server authority, permissions and protection on Crucible behind a proxy** — status: `accepted`

### Context

Server facts (VERIFIED, research 07):
- Crucible 1.7.10-staging-67b53034 via RetroFuturaBootstrap 1.1.0 on Java 21, with java9args.txt;
- online-mode=false behind a proxy with BungeeGuard; whitelist on;
- LuckPerms 5.5.77 + Vault, WorldGuard 6.2, GriefPreventionPlus 13.9, PlotMe, FinalForgeRestrictor 2.0.4, BlockLimiter 2.1.1, ProtocolLib, ClearLagg, Multiverse;
- Akashic-World-Firewall audits the pack's mods.

Crucible patches (VERIFIED in the challenge, Crucible d2feb1f):
- mod commands bypass canCommandSenderUseCommand and are gated by a Bukkit node named after the command class (CommandHandler.java.patch:21,32-54);
- a Bukkit PlayerInteractEvent fires before onBlockActivated (ItemInWorldManager.java.patch:198-234);
- placement can be rolled back after onBlockPlacedBy;
- TileEntity.hashCode is overridden.

FML channels work on production Crucible (LIVE-OBSERVED via Akashic Voice, 138 authenticated sessions).

### Decision

COMMANDS
- Register /web and /webadmin through Crucible's registerCommand(String group, ICommand) when it exists (stable nodes akashicweb.web and akashicweb.webadmin); otherwise use registerServerCommand.
- Always re-check rights inside processCommand: op (func_152596_g), or Bukkit Player.hasPermission("akashicweb.admin") by reflection on the main thread (LuckPerms), or a config UUID list.

INPUT
- Our own C2S pointer and key packets carry (screen, side, u, v, button, action, epoch).
- The server checks reach and the ray-plane hit with its own positions, plus rights, rate limits, length caps and the controller lock.
- FakePlayers are ignored.
- onBlockActivated only opens the GUI.

PLACEMENT
- Screen creation goes through normal block placement, so WorldGuard, GPP, FinalForgeRestrictor and BlockLimiter apply.
- Formation, registry entries and audit lines are deferred one tick via ServerActionQueue and then re-validated.
- Self-heal on chunk load and neighbour change.

TILE ENTITY AND KEYS
- The TE is non-ticking.
- Maps are keyed by (dimension, x, y, z) values.
- Players to notify are found with PlayerManager.isPlayerWatchingChunk.
- Server-reachable classes use no javax.xml.bind, Nashorn, sun.*, ProcessBuilder or networking.

VERSION AND PROXY
- S2CHello{protocolVersion, sitePolicy, killSwitch} is sent on PlayerLoggedInEvent. A mismatch gives a placeholder, not a disconnect.
- The client tears everything down on WorldEvent.Unload as well as on disconnect, for proxy transfers.

COORDINATION WITH THE OWNER
- Declare AkashicWeb to Akashic-World-Firewall before 10-16.
- Check that the custom payload channel passes ProtocolLib and ProtocolObelisk.
- Make sure ClearLagg never touches our TEs.

### Alternatives considered

- Web Displays-style input via onBlockActivated: region plugins cancel PlayerInteractEvent at spawn, and it cannot carry hover, drag or wheel. Rejected.
- Rely on Forge op only: it ignores LuckPerms, and Crucible bypasses canCommandSenderUseCommand. Rejected.
- Firing Bukkit events for every screen action: too costly for 10-25. Post-event, for region-aware URL changes if needed.

### Consequences

- One authority (our rights model) for screen input, the same on dev (plain Forge) and production.
- Admins managed in LuckPerms.
- Server hotfixes do not force client updates within the protocol major.
- Reflection into an exact Crucible build must be boot-tested.

### Confirm by

Crucible boot test by 10-15: the staging-67b53034 jar, RFB 1.1.0, Java 21, java9args.txt, the owner's server mods (GTNHLib 0.11.51, UniMixins 0.3.1, Hodgepodge 2.7.189, AkashicFM, Akashic Voice, lwjgl3ify 3.0.31), LuckPerms and WorldGuard. Pass:
- zero AkashicWeb errors;
- /webadmin denied to a non-op, allowed to an op, and allowed to a non-op holding akashicweb.webadmin;
- a 3x2 screen placed by a non-member in protected spawn is reverted with no registry entry or audit line;
- out-of-reach, no-rights or unauthenticated pointer packets are rejected and audited;
- a member's packet reaches every tracker.

### Fallback

If the group registration or Bukkit reflection fails on this build: use registerServerCommand with the class-name node documented for LuckPerms, plus op and the UUID-list check inside processCommand.

## ADR-011

**Runtime distribution: two layers (vendor engine downloaded once; host app and glue inside the mod jar), pinned hashes, consent, resume, no Akashic hosting for 10-25** — status: `accepted`

### Context

Technic deletes mods/ and orphaned files on pack update (VERIFIED, ImmutableInstallerPlanner.java:493-520,663-700). The 855 MB TEOF pack is non-Solder and re-downloaded whole (VERIFIED, api.technicpack.net), so the engine must not go into the pack.

AkashicFM ToolManager is server-side with a 250 MiB cap and no resume (VERIFIED, ToolManager.java:30,44-49,166-173). NativesInstaller is new code.

Mojang's jre-legacy 8u51 runtime ships a refreshed cacerts (143 roots, 2025-10-06) (VERIFIED, piston-meta).

OBS CDN supports Range (206) with a stable ETag. A Java major-52 probe range-fetched only the 23 needed entries: 158.5 MB instead of 344.8 MB, byte-identical (VERIFIED, scratchpad/challenge/probe/src/RemoteZipProbe.java, Linux).

Azure Artifact Signing excludes Brazil (VERIFIED, MS quickstart 2026-10-08). The official electron.exe is unsigned (VERIFIED, pesig.py).

### Decision

LAYER 1, THE ENGINE
The vendor's bytes, downloaded on the player's PC, shared across instances and mod versions:
- Windows path: %LOCALAPPDATA%\AkashicWeb\engine\&lt;engineId>-&lt;hash8>\.
- Electron: the official zip (158,293,649 B), full-file SHA-256 pinned in the jar.
  - Prune locales except en-US and pt-BR.
  - Rename electron.exe to AkashicWebHost.exe.
  - Re-hash the kept files into a local manifest.
- OBS fallback: Range-fetch of the 23 needed entries, with per-file and per-8-MiB-chunk hashes generated by CI from the OBS-pinned zip. If the CDN answers 200, stream the full zip once.

LAYER 2, OUR FILES
- The host app JS (Electron) or jcef.dll plus the helper (JCEF fallback, about 1.5 MB), carried in the jar, hash-checked and written next to Layer 1 under versioned names.
- Fixes to our code are ordinary mod updates and never re-download the engine.

INSTALL
- Download to .part with Range resume and If-Range/ETag, a state file across restarts, connect 15 s / read 30 s timeouts and exponential backoff.
- Require at least 1 GB free.
- Stage, then atomic rename plus a .complete marker. Keep the previous engine for rollback.
- At startup check sizes and the marker only. Run a full re-hash after install and after any load failure.

USER EXPERIENCE
- pt-BR consent at the main menu with exact numbers, then a background download with a progress overlay. The mod in the 10-16 pack update doubles as the pre-installer.
- Players who decline still join and see the placeholder.
- Manual import: the player saves the zip into %LOCALAPPDATA%\AkashicWeb\import\, for TLS, firewall or DNS failures.

TLS
The JRE trust store by default. An installer-only SSLContext with bundled roots as fallback, never setDefault.

PLATFORM GATING BEFORE ANY BYTE
- Windows x64 on a 64-bit JVM downloads.
- Linux x64 downloads Electron's official linux zip (Tier 2, no redistribution needed).
- macOS, Android/ForbiddenMobile, arm64 and 32-bit get the placeholder and download nothing.

NEW REASON CODES
DOWNLOAD_FAILED, HASH_MISMATCH, DISK_FULL, TLS_FAILED, AV_BLOCKED, SAC_BLOCKED, HOST_EXITED.

User-Agent: 'AkashicWeb/&lt;ver> (+repo URL)'.

HOSTING COST FOR 10-25
$0, no new accounts.

### Alternatives considered

- Full OBS zip (current plan): 2.2x the bytes needed. Kept only as the OBS fallback's fallback.
- Akashic repack (per-file xz, 116.5 MB) on GitHub Releases: redistribution. Dormant, needs an explicit owner decision.
- Cloudflare R2 / Backblaze B2 / Maven Central mirrors: post-event or not needed.
- Engine inside the Technic pack: taxes every update and Android players. Rejected.
- Standalone pre-installer exe: Mark-of-the-Web, SmartScreen/SAC, unsigned. Rejected.
- Binary deltas: -33% at best, need native zstd (VERIFIED, delta.log). Rejected.
- Code signing now: not obtainable in time (Azure excludes Brazil; SignPath/Certum need an OSI licence, P-08). Post-event.

### Consequences

- About 158 MB per player once.
- No codec redistribution by Akashic.
- Our own fixes ship as small jar updates.
- Unsigned vendor binaries may meet AV or Smart App Control friction; we detect it and explain it in pt-BR.

### Confirm by

NativesProbe on the owner's PC on 10-12, run with the Technic jre-legacy javaw (expected 1.8.0_51). Pass:
- the Electron zip downloads and its hash matches;
- a mid-download abort resumes, re-fetching less than one chunk;
- HTTPS to github.com, release-assets.githubusercontent.com and cdn-fastly.obsproject.com all succeed on 8u51;
- the full install takes under 3 min;
- no AV quarantine;
- the Smart App Control state and Defender history are recorded.

### Fallback

If TLS fails on 8u51, use bundled roots plus manual import. If AV or SAC blocks, publish a pt-BR guide with a reason code and pull signing work forward after the event. If GitHub is blocked, manual import, then (owner decision) an Akashic mirror.

### Reviewer amendments (binding)

- **[medium] The Smart App Control and AV risk is understated and detected too late. The official electron.exe is unsigned. Almost every shipping Electron app rewrites (rcedit) and signs its exe, which changes the hash, so the stock v44.7.0 electron.exe hash probably has little prevalence or cloud reputation. Under SAC enforcement, an unsigned, low-reputation exe plus an unsigned ffmpeg.dll is the case SAC blocks. A renamed unsigned Electron binary in %LOCALAPPDATA% also matches common malware heuristics. The current design only learns this after a 158 MB download, through SAC_BLOCKED at launch.**  
  Evidence: VERIFIED (architect): electron.exe and ffmpeg.dll in the v44.7.0 zip carry no Authenticode signature (pesig.py). VERIFIED (distribution challenge): SAC evaluates all code loaded and allows unsigned files only with cloud reputation (textslashplain.com 2026-04-28). INFERRED: the low prevalence of the stock hash.  
  Correction: Before the consent screen, read the SAC state (`reg query HKLM\SYSTEM\CurrentControlSet\Control\CI\Policy /v VerifiedAndReputablePolicyState`: 0 off, 1 enforce, 2 evaluation). If it is 1, show SAC_BLOCKED in pt-BR before downloading. Add a G1-E cell on a clean Windows 11 install or VM with SAC in evaluation/enforce mode and Defender cloud protection on, and record whether AkashicWebHost.exe and ffmpeg.dll load. Keep the original file hash; renaming is fine because it does not change the hash.

- **[low] Resume across restarts is under-specified for GitHub-hosted assets. github.com/electron/electron/releases/download/... redirects to release-assets.githubusercontent.com with short-lived signed query parameters. A stored redirected URL expires, so If-Range/ETag resume must re-resolve the redirect on every attempt and compare the ETag from the asset host.**  
  Evidence: INFERRED from GitHub release-asset redirect behaviour; not probed in this review. The distribution challenge VERIFIED Range support on the OBS CDN only.  
  Correction: The installer stores only the canonical github.com URL plus the ETag and size. Each attempt follows the redirect afresh and resumes with If-Range. If the ETag changes, restart from 0, and rely on the SHA-256 pin. Add a 'resume after 30 minutes' case to NativesProbe.

## ADR-012

**Multiplayer model: each client runs its own browser; server owns URL and video clock; shared screens use in-memory sessions; state convergence instead of trusting raw input** — status: `accepted`

### Context

A confused deputy in the current plan: one persistent profile per instance (PLAN 4.1) plus relaying the controller's clicks and keys to every client (PLAN 4.5) executes clicks inside each follower's own logged-in session. Web Displays broadcast clicks, typing and even arbitrary JS within 64 blocks (VERIFIED, TileEntityScreen.java:262-263,457-473,752-797,1056).

Divergence:
- per-profile ads;
- consent and LGPD banners;
- SPA routes that bypass navigation hooks (INFERRED).

Video clock blind spots:
- the IFrame API setPlaybackRate rounds toward 1 (VERIFIED, iframe_api_reference);
- a live player cannot run faster than the live edge;
- per-client ads;
- tick-thread stamping jitter.

AkashicFM's ClockMath achieved 1.6-6.8 ms between players for relayed audio (VERIFIED, FASE6-RESULTADOS.md:107,118, Linux harness). Server-side rendering or cast would re-stream third-party video (D-008).

### Decision

KEEP CLIENT-SIDE BROWSERS WITH SERVER URL AUTHORITY, PLUS:

1. Sessions. Every SHARED screen uses its own in-memory session: an Electron partition without the 'persist:' prefix (INFERRED from Electron session docs), or JCEF createContext with an empty cache_path (VERIFIED, CefRequestContext_N.cpp:34-37). The session is disposed when the screen unloads. A persistent profile is used only for PRIVATE screens (post-event).

2. Input. One controller lock at a time; only the controller's input is relayed; visitors on the telão watch only; arbitrary JS is never relayed.

3. Media sample. The controller samples {mediaTime, paused, rate, adShowing, secondsBehindLiveEdge, fullscreen, url} at 4 Hz and stamps each sample in server time on the client, using a ClockMath copy.

4. Server. It validates and relays without re-stamping on the tick thread. It keeps an extrapolated clock and takes over when the controller leaves.

5. Followers.
   - Steer HTMLMediaElement.playbackRate within 0.95-1.05, with a ±40 ms deadband.
   - Seek only above 1.5 s (VOD).
   - Lives: target lag = the maximum in-range client lag + 0.5 s, never forward past the edge.
   - Suspend while their own ad shows.
   - Fullscreen-video convergence; same-document URL convergence with normalisation and the epoch.

6. Telemetry. 1 Hz per client: serverTime, mediaTime, adShowing. The server logs p50/p95 per screen.

7. Browser budget per client. 2 browsers on 8 GB RAM or less, 4 by default, ranked by size x proximity. The rest show a frozen frame and 'aproxime-se'.

8. Screen 'mode' {SHARED, PRIVATE, CAST} in NBT and the protocol from day 1. Only SHARED is used on 10-25.

9. G4 retuned: live p95 ≤ 1 s, VOD p95 ≤ 0.3 s.

MUST items (1-4, 6), about 1.5 days, before G3. SHOULD items (fullscreen, SPA routes, budget, scroll) are cut from the bottom.

### Alternatives considered

- Plan A as written: a security bug plus blind spots.
- DOM mirroring (rrweb): cannot mirror MSE video or cross-origin iframes. Rejected.
- Server-side rendering in the Crucible JVM: rights (D-008), crash kills the server, datacenter bot-wall, egress about 2-3 Mbit/s per viewer. Rejected.
- Cast from the owner's client: rights and a new pipeline. Post-event spike, owner decision.
- neko/Hyperbeam: rights and cost. Rejected.
- CinemaMod curated player page: violates D-002 as a default. Rejected.
- PRIVATE screens: post-event (0.5-1 day).

### Consequences

- The confused deputy is closed.
- Sync becomes measurable.
- Zero server bandwidth.
- Android players still get only a placeholder.
- Shared screens never hold logins, by design.
- Ad-detection selectors and YouTube honouring a direct playbackRate are unverified until the spike.

### Confirm by

Sync spike on 10-13/14, piggybacking on the host probe: 3 webContents in separate in-memory partitions, with a simulated +120 ms offset and staggered starts. Ten minutes each of an MP4, a monetised YouTube VOD and a YouTube live. Pass:
- MP4 p95 within 150 ms;
- VOD p95 within 250 ms, excluding ads;
- live p95 within 750 ms, with at most 1 seek per follower per 10 min;
- playbackRate 1.03 holds in at least 95% of reads;
- ad state detected in every ad window;
- followers stay logged out after a relayed click.
Then G4 on 10-18 with real telemetry.

### Fallback

If YouTube resets playbackRate, use micro-seeks above 400 ms and accept VOD p95 ≤ 0.5 s. If live currentTime is not comparable across clients, steer on secondsBehindLiveEdge with a ≤ 1.5 s target.

### Reviewer amendments (binding)

- **[medium] In-memory sessions close the logged-in confused deputy, but raw controller input is still relayed to followers' independent browsers. Followers load each page with their own ad decisions and layout, so a relayed click can land on an ad or promoted item in N follower sessions at once. That causes N invalid ad clicks from N residential IPs, plus a navigation divergence that URL convergence then has to fight. Separately, the media-clock precision cited (ClockMath, 1.6-6.8 ms) came from Netty-arrival stamping through a mixin, and ADR-009 forbids mixins for 10-25.**  
  Evidence: VERIFIED (challenge, re-read): the AkashicFM relay sync figures depend on mixins/common/MixinNetworkManager.java:29 (Netty-arrival stamping). VERIFIED: ADR-009 sets usesMixins=false. VERIFIED: ADR-012 item 2 still says 'only the controller's input is relayed', meaning to followers. Ad divergence: https://support.google.com/youtube/answer/132596 (cited by the sync challenge). The multiplied-ad-click consequence is INFERRED.  
  Correction: On SHARED screens, deliver raw pointer and key input ONLY to the controller's own browser. Followers converge on state alone: normalised URL with epoch, media clock, fullscreen, and (SHOULD) scroll anchor. Write this down as a D-002-compatible decision. Budget the clock error for handler-thread stamping: about ±25 ms bias, plus tick quantisation on the client (INFERRED). Keep the ±40 ms deadband and the VOD p95 ≤ 0.3 s target, but stop citing 1.6-6.8 ms as support. Measure it in the 10-13/14 sync spike.

- **[low] In-memory sessions are graded INFERRED but are documented behaviour.**  
  Evidence: VERIFIED: Electron v44.7.0 docs/api/session.md:37-39: 'If there is no persist: prefix, the page will use an in-memory session.'  
  Correction: Upgrade the grade to VERIFIED (docs). Still confirm in the sync spike that followers stay logged out.

## ADR-013

**Security baseline for public screens** — status: `accepted`

### Context

This is a public server where players choose URLs, and every nearby client loads a screen's URL (IP loggers, exploit pages).
- The JCEF path has the sandbox off (VERIFIED, context.cpp:211).
- Electron's renderer sandbox is on by default since 20 (VERIFIED, electron docs).
- Electron OSR had 2026 CVEs in child windows and shared texture, all patched (VERIFIED existence).
- The Electron probe's capture window used contextIsolation false and sandbox false (VERIFIED, probe-electron/app/main.js:54).
- Chromium 150 (OBS) is about 6 milestones behind stable by 10-25 (VERIFIED, chromiumdash).

### Decision

HOST
- sandbox true, contextIsolation true, nodeIntegration false for every browsing window.
- The capture page loads only a local file, also with contextIsolation true and a contextBridge preload.
- No remote debugging port.
- Deny all permission requests (camera, mic, geolocation, notifications, HID, and so on).
- Cancel will-download.
- setWindowOpenHandler denies, then navigates in the same window. No child windows.
- http and https only.

INTERNAL-ADDRESS BLOCK, ON BOTH SIDES
- Host literal-host check in will-navigate, will-redirect and session.webRequest.onBeforeRequest.
- Post-connect IP check in onResponseStarted, which aborts the load.
- Server UrlPolicy.isInternal (copied from AkashicFM).

SITE POLICY
- Allowlist on SHARED public and spawn screens: youtube.com, youtube-nocookie.com, twitch.tv and the CDNs they need (P-05).
- Blocklist only on PRIVATE screens (post-event).

NEVER
--disable-web-security or --no-sandbox on Windows.

LINUX TIER 2
Use SUID or userns if available; otherwise --no-sandbox, documented.

CURRENCY
- Re-pin to the newest 44.x patch at the 10-16 freeze.
- After the event, track a supported Electron major (latest three).
- After the event, flip Electron fuses (RunAsNode off) at install time and pin the post-flip hash, so we never redistribute a modified binary.

Rights are checked server-side on every packet: rate limits, length caps, AuditLog, kill switch.

### Alternatives considered

- Keep the sandbox off and rely on the allowlist (current PLAN 4.7, forced by JCEF): weaker. Remains the case only under the JCEF fallback, where the allowlist becomes mandatory for every SHARED screen.

### Consequences

- A renderer exploit is contained by Chromium's sandbox and by process isolation from the game.
- The allowlist limits what spawn screens can show.
- Owner must answer P-05.

### Confirm by

Security tests at G3 (10-16):
- private, loopback and link-local URLs are blocked by both the server and the host;
- a DNS name resolving to 127.0.0.1 is aborted;
- window.open stays in the same window;
- a download is cancelled;
- permission prompts are auto-denied;
- chrome://, devtools:// and file:// are refused;
- Task Manager shows renderer processes with the sandbox on (Electron).

### Fallback

Under the JCEF engine fallback: allowlist-only on every SHARED screen, and keep the post-event plan to move to a sandboxed host.

### Reviewer amendments (binding)

- **[high] The internal-address block's second layer, 'Post-connect IP check in onResponseStarted, which aborts the load', cannot be built in Electron. onResponseStarted is a notification-only listener: no callback, so it cannot cancel. Its details have no ip field; ip appears only on onBeforeRedirect and onCompleted. By then the request has already reached the internal host, so a CSRF against a router or LAN device has already happened. The literal-host check in onBeforeRequest misses DNS names that resolve to private IPs, including DNS rebinding. The architect also missed Chromium's own mechanism, which Electron now exposes.**  
  Evidence: VERIFIED: Electron v44.7.0 docs/api/web-request.md:207-240. onResponseStarted is called as listener(details) with no callback, and its details fields include no ip. The only ip field is at line 268, under onBeforeRedirect/onCompleted. VERIFIED: docs/api/session.md:981-983 and 1054-1056 list the permission types 'local-network', 'loopback-network' and 'local-network-access' (Local Network Access). VERIFIED (third-party sources): Chromium has enforced LNA since 142 (2025-10-28), so 152 has it: https://blog.openreplay.com/chrome-local-network-access-lna-permission/ and https://www.dynamsoft.com/web-twain/docs/faq/chromium-142-local-network-access-issue.md. VERIFIED: ses.resolveHost exists (session.md:761). INFERRED: LNA checks the resolved IP at connect time, so it covers rebinding for subresources but not top-level navigations.  
  Correction: Replace the onResponseStarted layer with four measures:
(1) Deny 'local-network', 'loopback-network' and 'local-network-access' in BOTH setPermissionRequestHandler and setPermissionCheckHandler on every screen session. This makes Chromium's own LNA block subresource requests from public pages to private or loopback IPs, after DNS resolution.
(2) For mainFrame and subFrame navigations, in onBeforeRequest, call ses.resolveHost(host) and cancel if any returned address is private, loopback, link-local, CGNAT, or IPv4-mapped ::ffff:. Literal-IP checks must also cover IPv6 (::1, fc00::/7, fe80::/10).
(3) In onCompleted / onBeforeRedirect, when the ip is internal, call webContents.stop() and show the placeholder. This is a detector, not a preventer.
(4) Keep the server-side UrlPolicy.
Add to the G3 tests: a page on a public host that fetch()es http://192.168.0.1/ and http://127.0.0.1:&lt;port>/ must fail without any request reaching a local listener.

## ADR-014

**Platform and test matrix for 10-25 (replaces the GTNH-based matrix)** — status: `accepted`

### Context

The PLAN section 8 matrix is written for GTNH versions with lwjgl3ify and Java 21 clients. Actual pack (VERIFIED, research 07):
- Technic client: LWJGL2, Java 8 (8u51 seen in field logs), Angelica 2.2.20 with BSL and Complementary, AkashicFM and Akashic Voice present, no lwjgl3ify jar (a leftover lwjgl3ify.cfg exists).
- Server: Crucible/RFB/Java 21 with lwjgl3ify 3.0.31.
- Android players use ForbiddenMobile (LWJGL3, arm64).
With an Electron host, Linux x64 needs no redistribution (ADR-011).

### Decision

TIER 1 (P0)
Windows 10/11 x64 + Technic Java 8 + LWJGL 2.9.1 + Angelica 2.2.20:
- shaders off, BSL, Complementary;
- AkashicFM and Akashic Voice co-installed;
- on the owner's PC and one low-end Intel-iGPU / 8 GB machine.

SERVER CELL
Crucible staging-67b53034 / RFB 1.1.0 / Java 21 / lwjgl3ify 3.0.31, with the owner's plugins. The dedicated server never spawns a host.

P1
- Path with accent and space (C:\Users\João Conceição); OneDrive Documents; Controlled Folder Access; non-admin account.
- Defender plus Avast or Kaspersky.
- Intel/AMD/NVIDIA.
- ABNT2 'ação ç $%&'.
- Two instances at once.
- A Linux x64 Tier 2 smoke test.

DROPPED FROM 10-25 TESTING
Java 17-25 / lwjgl3ify client cells and the GTNH version axes. Guards remain, and the host model makes the mod side Java-version-independent anyway.

PLACEHOLDER ONLY, NO DOWNLOAD
Android/ForbiddenMobile, macOS, arm64, 32-bit Java.

Preflight telemetry (opt-in, P-09) reports java.version, launcher, OS, GPU and RAM, to validate these assumptions at G4.

### Alternatives considered

Keep the GTNH matrix: it tests the wrong stack. Rejected.

### Consequences

Test effort goes where the players are. Linux becomes cheap to support, but stays first in the cut order.

### Confirm by

G4 preflight telemetry on 10-18: at least 90% of desktop players report Windows x64 plus Java 8. If more than 10% are on Java 17+ or other launchers, add a P1 cell.

### Fallback

Re-add a Java 21/lwjgl3ify client cell before RC1 (10-20) if telemetry shows meaningful use.

## ADR-015

**Schedule, gates and freeze points after the engine change** — status: `accepted`

### Context

PLAN section 6 front-loads Windows CI and native patches (10-11/10-12) and freezes a 'native buildId' on 10-16 (T-10). With an Electron host there is no native build of ours on the critical path. The engine binary is a vendor pin, and our host app is JS inside the jar.

### Decision

G1 BECOMES G1-E (owner-run, no CI)
- 10-12 on the owner's PC: engine, codec arm with a codec-free control, audio silence, lifecycle, firewall/AV, NativesProbe TLS and resume, and the sync mini-check.
- Engine go/no-go recorded 10-13.
- The JCEF insurance CI build runs 10-12 in parallel and is abandoned on a G1-E pass.

T-10 REWORDED
'Engine pin frozen 10-16' (Electron 44.x patch, or OBS 7871 under the fallback). After 10-16 only Java and host-app changes ship, as mod updates. The engine is never re-downloaded.

MOVED EARLIER
In-game first frame from 10-14 toward 10-13/14, because no native build is needed.

OTHER DATES
- 10-15: Crucible boot test (ADR-010).
- 10-18: AkashicFM API release deadline (ADR-008).

CUT ORDER (first cut first)
Linux tier → SPATIAL audio (keep OS mode) → SHOULD sync items → keyboard IME polish → address-bar extras → admin GUI.

NEVER CUT
Crash guard, kill switch, site policy, internal-address block, installer hash checks, in-memory sessions on shared screens.

### Alternatives considered

Keep the original schedule with JCEF native work on 10-11/12: higher variance on the longest pole. Rejected unless G1-E fails.

### Consequences

- About 2-3 days of native-build variance leave the critical path.
- If G1-E fails, the insurance build limits the slip to about 2 days, absorbed by the 10-19 buffer.

### Confirm by

Schedule checkpoint on 10-13 evening: G1-E recorded, skeleton CI green, HostSupervisor talking to a host on the owner's PC.

### Fallback

If G1-E fails: the original 10-12..10-14 JCEF track in a child JVM, starting 10-13. Cut Linux and SPATIAL audio immediately.

### Reviewer amendments (binding)

- **[high] The schedule is already a day behind on paper. 'first_three_days' assigns six deliverables to 10-11: the docs commit, the G1-E kit, the mod skeleton plus CI, the protocol v1 spec, the JCEF insurance workflow and the AkashicFM API draft. The verdict is dated 10-12, and none of these exist in the repository. G1-E is planned for 10-12, today, and needs a kit that has not been built plus half a day of the owner's time. The insurance CI workflow does not exist either, so the claim that 'the insurance build limits the slip to about 2 days' is unsupported. The AkashicFM release by 10-18 is a third-party-style dependency on the same single developer.**  
  Evidence: VERIFIED: `git log` in /home/user/akashicweb shows HEAD ef50648 '2026-10-12 00:41 Add the Forbidden Series pack and server inventory'. spikes/ contains only README.md, ccbluex-obs-cef, cinemamod-116 and jcefmaven-osr-probe: no electron-host, cdp or natives directories, and no mod skeleton. The probe sources exist only under the scratchpad (/tmp/claude-0/.../scratchpad/challenge/probe-electron). VERIFIED: the decision text plans G1-E for 10-12 with go/no-go on 10-13.  
  Correction: Re-baseline now:
- G1-E kit by 10-12 evening.
- G1-E run 10-13.
- Engine decision 10-14 (not 10-13).
- Start the JCEF insurance CI today. A Windows libjcef build against a 344.8 MB CEF zip is the long pole of the fallback. Without it, a G1-E failure on 10-14 cannot be absorbed by the 10-19 buffer.
- Move the in-game first frame to 10-15 and keep G3 on 10-16/17.
- Declare SPATIAL audio cut unless the AkashicFM API PR is merged by 10-15, not 10-18. One developer cannot ship both mods in parallel during the event week.
- Record the slip in PROJECT_STATE.

## ADR-016

**Engines and sharing models rejected for 10-25** — status: `rejected-alternative`

### Context

The engine, codec and sync challenges surfaced several alternative technologies. Each was checked against: real-browser (D-001/D-002), YouTube live/Twitch codecs, Java 8/LWJGL2 fit, PCM for 3D audio, rights (D-008) and 14-day feasibility.

### Decision

Not used for 10-25. Each item gives the reason and when, if ever, to revisit.
- WebView2: no offscreen rendering API; issue #547 open since 2020, no Microsoft update since 2022-04 (VERIFIED). No audio capture, Windows only. Revisit only if Microsoft ships offscreen rendering.
- CDP Page.startScreencast with the player's Edge/Chrome: JPEG q80 (43 KB/frame) plus 6.3 ms Java decode on JDK 21, no audio (VERIFIED, probe-cdp). Possible post-event 'lite mode', clearly labelled.
- JxBrowser 9.5.3: Java 17/21/25 only (VERIFIED), from $1,979, no PCM API, codec licences pushed onto us.
- Ultralight: the free tier has no media playback (VERIFIED, pricing).
- Servo 0.5 / Ladybird: no YouTube parity, no Windows embedding (VERIFIED, secondary sources).
- WPE/WebKitGTK: no Windows port.
- Gecko: no desktop embedding (INFERRED).
- Server-side rendering in Crucible, cast mode, neko/Hyperbeam, DOM mirroring: rights (D-008), server crash coupling, datacenter bot-wall and egress; or no video. Cast with rights-cleared content is a post-event owner decision.

### Alternatives considered

See ADR-002 and ADR-012.

### Consequences

Focus stays on one Chromium-based host engine behind an engine-agnostic protocol. The protocol keeps the door open for Android WebView in ForbiddenMobile and for a C++ CEF host later.

### Confirm by

No spike needed. Re-evaluate in the post-event review (November 2026).

### Fallback

Not applicable.

## Changes to the plan

- PLAN 4.1 Engine: replace 'in-process JCEF; host process after the event' with the out-of-process AkashicWebHost (ADR-001). Engine = official Electron 44.x, pinned sha256 eee30dc8…2665c (win32-x64), proposed-needs-spike at G1-E (ADR-002). Fallback ladder: GPU OSR, then CCBlueX JCEF on OBS 7871 in a child JVM, then in-process JCEF. Delete the CinemaMod 116 fallback.
- PLAN 4.1 Threading: drop the CEF pump thread and onPaint triple buffer. Add a host reader thread that composites dirty crops into a full canvas and publishes into a 3-slot ring with accumulated damage (ADR-004).
- PLAN 4.1 Profile: shared screens use per-screen in-memory sessions; the persistent profile is only for future PRIVATE screens (ADR-012).
- PLAN 4.2 Mod structure: remove org.cef packaging, JNI, the shutdown mixin and the hard GTNHLib/UniMixins dependencies for 10-25. Build = GTNHGradle 2.0.33 + Jabel + usesMixins=false. Add the HostSupervisor and host-app resources (with hashes) to client.web. Add the new CI jar checks (ADR-009).
- PLAN 4.3 Rendering: GL_RGBA8 allocation with GL_BGRA/8_8_8_8_REV source; GL_CLAMP_TO_EDGE (not GL_CLAMP); save, zero and restore PIXEL_UNPACK_BUFFER; damage-correct uploads; 8.3 MB per frame upload budget; 720p default resolution; instrumentation in /web doctor. Replace 'register Angelica bounds' with the reflective registerDynamicClass + true box + section rebuild on resize, with INFINITE as fallback (ADR-004, ADR-005). Replace wasHidden with host setFrameRate LOD and stopPainting beyond audio range.
- PLAN 4.4 Input: our own C2S pointer and key packets with server-side reach and ray validation; controller lock; FakePlayers ignored; onBlockActivated only opens the GUI. Input reaches the host via sendInputEvent (ADR-010, ADR-012).
- PLAN 4.5 Sync: rewrite to ADR-012. Controller-side stamping at 4 Hz; HTMLMediaElement.playbackRate steering within 0.95-1.05 and a ±40 ms deadband; seek above 1.5 s; live-edge rule; ad suspension; no JS relay; 1 Hz skew telemetry; screen mode {SHARED, PRIVATE, CAST} in NBT and protocol; per-client browser budget. Target skew changes from '0.5-2 s' to live p95 ≤ 1 s and VOD p95 ≤ 0.3 s.
- PLAN 4.6 Audio: MVP becomes OS mode with an engine-level hard mute (setAudioMuted). Stretch becomes host PCM capture (Electron per-frame loopback, or CefAudioHandler with pre-mute under fallback) into the AkashicFM ExternalAudioSources API, with a low-latency feed profile, a video delay ring of about 150-250 ms (not 4-5 frames) and an A/V offset slider. List the exact AkashicFM API needed by 10-18 (ADR-007, ADR-008).
- PLAN 4.7 Security: the sandbox stays ON (Electron) and the old 'sandbox stays off' sentence is removed; add host lockdown (permissions, downloads, window.open, http(s) only, internal-address checks before request and after connect). Allowlist mandatory on SHARED public screens (ADR-013).
- PLAN 4.8 Natives distribution: two-layer runtime (ADR-011). The Electron zip is downloaded from GitHub releases on the player's PC; locales pruned, exe renamed, host app copied from the jar. The OBS fallback uses Range-fetch of 23 entries (158.3 MB instead of 344.8 MB). Startup does a size and marker check only, with full re-hash after install or failure. Add manual import, a bundled-roots TLS fallback and the new reason codes. Correct the 'built from AkashicFM ToolManager' and 'CinemaMod delta updates' claims.
- PLAN 4.9 Diagnostics: the crash-loop guard counts host exits. Add HOST_EXITED, TLS_FAILED, AV_BLOCKED, SAC_BLOCKED and DISK_FULL. Replace 'deterministic shutdown: mixin + Job Object' with host self-exit on socket EOF and parent-PID poll, plus a stale-host sweep. Make --disable-gpu-compositing / disableHardwareAcceleration a G1-E measured default, not an assumption.
- PLAN 4.10 Platforms and section 8 Test matrix: replace the GTNH axes with ADR-014. Tier 1 = Windows + Technic Java 8 + LWJGL2 + Angelica 2.2.20 with BSL and Complementary. The server cell is Crucible staging-67b53034/RFB/Java 21. Drop Java 21 client cells from P0. Android, macOS, arm64 and 32-bit get a placeholder with no download. Linux Tier 2 now needs no redistribution.
- PLAN 4.11 Data model: add screen mode, protocol major, S2CHello{protocolVersion, sitePolicy, killSwitch}, acceptableRemoteVersions="[1.0,2.0)", and Crucible command group registration with nodes akashicweb.web / akashicweb.webadmin (ADR-010).
- PLAN section 3 / research 05 legal facts: correct 'last major US H.264 patent ~Nov 2027' to 'US 9,356,620 to at least Nov 2030; Via LA list of 2026-09-28 includes Brazilian patents'. Add the art. 43 I / 42 §1 note and the lawyer question (ADR-006).
- PLAN section 6 Schedule: 10-11/10-12 Windows CI and native patches are replaced by G1-E (owner-run, 10-12) plus the JCEF insurance CI in parallel. T-10 becomes 'engine pin frozen 10-16'. Add the Crucible boot test on 10-15 and the AkashicFM API release by 10-18. Update the cut order (ADR-015).
- PLAN section 7 Gates: rewrite G1 as G1-E with the ADR-002 pass criteria (a)-(h), the codec control arm and the audio-silence checks. In G3, replace 'Java 21 (lwjgl3ify)' with 'BSL and Complementary on/off'. Retune G4 skew to live p95 ≤ 1 s and VOD p95 ≤ 0.3 s, measured from telemetry.
- DECISIONS.md: T-01 to T-07 and T-10 are superseded by ADR-001..ADR-015. D-004 needs owner rewording, and D-011 needs owner confirmation for Electron. Add pending P-items for the questions below. Per CLAUDE.md, update PROJECT_STATE.md, ISSUES_REGISTER.md, CHANGELOG (Unreleased) and PT-BR_RESUMO.md in the same commit, citing this review. Copy the probe sources from the scratchpad (probe-electron, probe-cdp, probe/RemoteZipProbe.java, scripts) into /spikes, because they exist only in /tmp.

## Reviewer: claims confirmed

- Electron 44.7.0 was released 2026-10-07 with Chromium 152.0.7977.130 and Node 24.21.0. 45.0.0-beta.1 (Chromium 156.0.8078.12) followed on 2026-10-09, and 44.x patches are frequent (44.5.1 on 09-29, 44.6.0 on 10-05, 44.7.0 on 10-07). VERIFIED: https://releases.electronjs.org/releases.json, fetched 2026-10-12.
- Angelica 2.2.20 has TileEntityRenderBoundsRegistry.registerDynamicClass(String) and the config key dynamicBoundsTileEntities (AngelicaConfig.java:326). DYNAMIC classes are frustum-tested with the live getRenderBoundingBox (CeleritasWorldRenderer.java:556-557) and placed in globalBlockEntities (AngelicaChunkBuilderMeshingTask.java:173-191). VERIFIED: git show 2.2.20 in /home/user/gtnewhorizons/Angelica.
- The pack's Angelica version comes from the TEOF-3.6 release zip (855,203,279 B) per research/07-forbidden-pack-inventory.md:3,17. This supersedes the 2.1.33 public-repo snapshot used by the mod-stack challenge. VERIFIED (document read).
- Electron OSR facts. Only the dirty area is passed to 'paint'. The non-shared-texture frame rate tops out at 240. Software output device mode requires app.disableHardwareAcceleration(). The OSR deviceScaleFactor defaults to 1, so Windows display scaling does not change the bitmap size. VERIFIED: v44.7.0 docs/tutorial/offscreen-rendering.md and docs/api/structures/web-preferences.md:103.
- setDisplayMediaRequestHandler accepts audio as a WebFrameMain and captures that frame's webContents. enableLocalEcho defaults to false, so local playback is muted while capturing. VERIFIED: docs/api/session.md:1117-1146.
- Partitions without the 'persist:' prefix are in-memory sessions. VERIFIED: docs/api/session.md:37-39.
- Electron exposes the Local Network Access permission types local-network, loopback-network and local-network-access, so a deny-all permission handler can enforce Chromium's LNA. Chromium has shipped LNA enforcement since 142. VERIFIED: session.md:981-983,1054-1056, plus third-party release notes.
- The default Electron fuses enable RunAsNode, NODE_OPTIONS and the --inspect CLI flags. VERIFIED: docs/tutorial/fuses.md:26-74.
- The probe's capture window ran with contextIsolation:false and sandbox:false, as ADR-013 says (main.js:54). The probe used defaultSession for capture and passed the token on argv. VERIFIED: scratchpad/challenge/probe-electron/app/main.js.
- ADR-001's direction holds. No org.cef, JNI or AWT in the game JVM, plus crash isolation, makes the mod side independent of 8u51, Java 17+ and lwjgl3ify. No better out-of-process engine was found for 10-25. The rejected alternatives in ADR-016 still fail on OSR, codecs, Java 8 or rights, as documented in the challenges (reasoning; not re-tested here).
- ADR-009's reasons against jvmDowngrader (its default stubs provider is GTNHLib) and against usesMixins (MixinTweaker in the manifest) match the challenge's verified GTNHGradle citations. I did not re-read GTNHGradle source myself.

## Open questions from the architect

- Do you accept moving the browser into a separate process (AkashicWebHost) now, with the official Electron 44.x as the engine if G1-E passes on your PC on 10-12? This adds a small JS/Node host app (about 1k lines, with lint and tests) to the repository next to the Java mod. The fallback is JCEF/OBS CEF in a child process.
- Does D-011 ('codec-enabled Chromium accepted') also cover Electron's official build, which bundles H.264/AAC in ffmpeg.dll? The legal grey area is the same as with OBS's CEF. Akashic still redistributes nothing; each player downloads it from Electron's GitHub releases.
- Can you run G1-E on 10-12 for about half a day? It needs your Windows PC (plus an Intel-iGPU laptop if you have one), the Technic Forbidden instance's own Java (jre-legacy 8u51), and a YouTube LIVE, a Twitch live and a monetised YouTube VOD to test against.
- Can AkashicFM get a release in the Forbidden pack by 10-18 with the audio API AkashicWeb needs (ExternalAudioSources, non-blocking ring, local clock, low-latency profile, API_VERSION)? Who publishes the pack update, and when? Without it, 10-25 ships OS-mode audio (distance volume plus hard mute), not 3D.
- Do you accept rewording D-004 as a quality bar (GTNH buildscript, tests, E2E, evidence) rather than a mandate to hard-depend on GTNHLib/UniMixins/ModularUI2? They are in the pack, but for 10-25 we would use no mixins and only Forge APIs, adding them later where they add value (for example a ModularUI2 configurator).
- Authentication: the server runs online-mode=false behind a proxy with BungeeGuard. Where are players authenticated (premium check at the proxy, or a login plugin such as AuthMe/nLogin on a limbo or backend)? Can a not-yet-logged-in player send mod packets to the Crucible backend that hosts the telão? Which proxy is it (Velocity or Bungee), and will every backend reachable with the telão run the same AkashicWeb version?
- P-05: for spawn and public screens, do you confirm allowlist mode (YouTube, youtube-nocookie, Twitch and their CDNs)? Who gets the LuckPerms nodes akashicweb.admin / akashicweb.webadmin?
- P-07: which channel(s) will the telão show? Is the channel monetised (each player gets their own ads, which breaks sync during ads), what is its latency setting, and does it allow embedding? A second backup channel is still needed by 10-18.
- Akashic-World-Firewall: what does it flag (ProcessBuilder, sockets, reflection into Bukkit, jar names)? AkashicWeb's client classes spawn a process and open a loopback socket; its server classes do neither. Can AkashicWeb be declared or allowlisted before 10-16, and can its audit run in our CI?
- Can you change pack configs for the event? Specifically: raise or disable FpsReducer's idle FPS (10 FPS after 300 s); add our tile entity to entityculling's whitelist and Angelica's dynamicBoundsTileEntities; check BlockLimiter and FinalForgeRestrictor entries for the screen block; make sure ClearLagg ignores our tile entities.
- Default screen resolution for 10-25: is 1280x720 acceptable for the telão on players' PCs? In the Linux container, 1080p cost about 1.2 CPU cores per player with audio capture.
- Lip-sync: if 3D audio is on, the picture must be delayed about 150-250 ms while that screen plays audio. Is that acceptable, or do you prefer OS audio (perfect sync, not positional) on the telão?
- Should Linux x64 (Tier 2) be offered on 10-25? With Electron it needs no redistribution, but it is still first in the cut order. Also, is any desktop player known to use a launcher other than Technic, or Java 17+?
- P-08 licence for AkashicWeb: an OSI licence is a prerequisite for free or cheap code signing (SignPath Foundation, Certum Open Source) after the event. The Electron and OBS binaries are unsigned, so some antivirus or Smart App Control setups may block them.
- Will Akashic Voice proximity chat be active at spawn during the event? If so, inter-player video skew above about 0.5 s becomes audible through reactions, and the G4 target should be tightened. How many players do you expect at spawn on 10-25, and roughly what hardware?
