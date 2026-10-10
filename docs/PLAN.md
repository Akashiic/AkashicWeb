# AkashicWeb: plan

Status on 2026-10-10: **research complete, no code yet.** Everything here is AUTHORED or INFERRED unless a line cites a VERIFIED spike or source (see [`research/`](research/)). Nothing has been built for Minecraft, run on Windows, or observed in game.

Target event: Sunday **2026-10-25**, second round of the Brazilian presidential election. Official results start at 17:00 BRT. The owner wants a big screen (telão) at spawn showing a live coverage stream inside a real in-game web browser.

## 1. What AkashicWeb is

A from-scratch Minecraft 1.7.10 mod, built on the GTNH stack, that gives the server a **real web browser on in-world screens**, following the idea of [Web Displays](https://github.com/montoyo/webdisplays) (montoyo). It relates to Web Displays the way AkashicFM relates to OpenFM: same idea, new engineering. Web Displays is public domain, so its engine-independent pieces may be reused with credit (DECISIONS D-003).

It is **not** a command-driven kiosk, a results board or a radio. Players build screens, click, scroll, type and browse. The spawn telão is one large screen owned by the owner, who opens the live through the screen's address bar like any other player (DECISIONS D-001, D-002).

## 2. Player-facing scope

### MVP (must work on 2026-10-25)

1. **Screens.** Screen blocks form multiblock screens from 2x2 up to a configured maximum (default 16x16; admin-placed screens may be larger). Any player can build them (recipe), anywhere.
2. **Browse directly on the screen.** Right-click = click at the hit pixel; scroll; drag. Address bar with back, forward, reload and home (GUI opened by sneak + right-click or a configurator item).
3. **Keyboard block.** Linked to a screen; types with ABNT2 accents (ç, ã, é) and Ctrl+V paste. Password fields are not relayed (see Security).
4. **Ownership and rights.** Owner, friends and visitors. Default: visitors only watch. Admins can lock, clear or take over any screen.
5. **Everyone sees the same page.** The server owns the URL; when the controller navigates, everyone follows. Videos follow a shared clock (target 0.5-2 s between players, INFERRED).
6. **Live video.** YouTube (including lives), Twitch and MP4 through a codec-enabled Chromium 150 (H.264/AAC).
7. **Sound from the screen.** Volume falls with distance and follows Minecraft's sliders (MVP). Target: full 3D audio through the AkashicFM engine (Stretch, section 4.6).
8. **Safe installation.** The Chromium runtime installs itself on first use with SHA-256 verification; errors are shown in Portuguese on the screen; no helper process survives the game.
9. **Admin tools.** `/web` command: list screens (owner, URL), clear, lock, unlock, kill switch, doctor, status. Audit log of every URL change.

### After the event

minePad (handheld browser), laser pointer, remote controller, redstone controller, upgrades and the JavaScript API, OpenComputers interface, separate browser-host process (crash isolation), PBO / GPU-accelerated paint, macOS support, IME, full screen-configurator GUI polish, GT-style recipes.

## 3. Ground truth from the research (short)

| Topic | Fact | Grade | Where |
|---|---|---|---|
| Old WD 1.7.10 | No public source exists for WD 0.8-0.11; old MCEF 1.7.10 pulls a 2015 CEF, Windows-only; a GTNH mod (TeXTech) reports it often fails to start on GTNH/Prism | VERIFIED | [01](research/01-webdisplays-code.md) |
| WD 1.12.2 | 142 Java files / 17.8k lines; ~70% optional (JSON GUI framework, miniserv, minePad, laser, upgrades, JS API); public domain | VERIFIED | [01](research/01-webdisplays-code.md) |
| WD design flaws | Each client runs its own browser, no playback sync, client-side navigation desync (FIXME), audio straight to the OS device, default rights let anyone change the URL | VERIFIED | [01](research/01-webdisplays-code.md) |
| Issue history | 590 issue titles: 19% white/black screen; then native crashes, platform, codecs, input, rendering, natives download | VERIFIED | [02](research/02-issue-catalog.md) |
| Java 8 | Upstream jcef-api 152 classes are major 52; a Java 8 OSR probe rendered 1080p VP9/Opus at ~30/60 fps without JOGL (Linux container) | VERIFIED | [03](research/03-cef-engine.md) |
| Codecs | Stock CEF (Spotify/jcefmaven/CCBlueX 152) has no H.264/AAC; YouTube live audio is AAC-only and live HLS is H.264, so lives fail on stock CEF | VERIFIED | [05](research/05-codecs-live-relay-rights.md) |
| Codec build | OBS hosts codec-enabled CEF 150 (7871) for Windows/Linux/macOS; CCBlueX java-cef compiled against the Linux one with a 6-line patch and played H.264/AAC, HLS and WebRTC | VERIFIED (Linux only) | [05](research/05-codecs-live-relay-rights.md), [`spikes/ccbluex-obs-cef`](../spikes/ccbluex-obs-cef) |
| CinemaMod 116 | Its hosted natives do decode H.264/AAC; Chromium 116 (2023), Java 21/GLFW API | VERIFIED | [05](research/05-codecs-live-relay-rights.md) |
| Audio capture | CCBlueX java-cef has CefAudioHandler; CEF mutes the source while capturing (CEF master source) | VERIFIED (code) | [03](research/03-cef-engine.md), [06](research/06-plan-reviews.md) |
| GTNH stacks | 2.8.4: lwjgl3ify 2.1.16 (GLFW) + Angelica 1.0.0-beta66b; 2.9.0 RC-2: lwjgl3ify 3.0.37 (SDL3) + Angelica 2.2.28 (core profile); Java 8 packs have no lwjgl3ify | VERIFIED | [04](research/04-gtnh-integration.md) |
| macOS | lwjgl3ify forces headless AWT and runs the game off the main thread; CEF on macOS needs the main thread | INFERRED incompatible | [04](research/04-gtnh-integration.md) |
| Cost | One browser ~0.43-0.54 GiB PSS, ~40% of a core at 1080p30 software decode (container) | VERIFIED (Linux) | [03](research/03-cef-engine.md) |
| Latency | YouTube: under 10 s (low latency), under 5 s (ultra-low); the embedded browser adds tens of ms | VERIFIED (docs) / INFERRED | [05](research/05-codecs-live-relay-rights.md) |

## 4. Architecture

### 4.1 Engine

- **Base:** [CCBlueX/java-cef](https://github.com/CCBlueX/java-cef) (BSD), pinned commit (fe8cc9e was the one tested). It is current, has no jawt/JOGL native dependency, exposes `N_DoMessageLoopWork` for an external pump, and has `CefAudioHandler`. It still imports AWT data classes and uses Swing's EDT for state callbacks (harmless on Windows/Linux per review).
- **Native distribution:** CCBlueX java-cef built against **OBS's codec-enabled CEF 150 (build 7871)**: Windows `150.0.20-7871-devtools_patch`, Linux `150.0.19`. libjcef must be built against the exact CEF it ships with (java-cef uses CEF's unversioned experimental API). The 6-line `jni_util.cpp` guard for three net error codes is in [`spikes/ccbluex-obs-cef/jni_util-cef150.patch`](../spikes/ccbluex-obs-cef/jni_util-cef150.patch); make it a `#if CEF_VERSION_MAJOR >= 152` guard instead of a deletion.
- **Fallback engines:** (a) the same java-cef on Spotify CEF 152, codec-free: everything works except H.264/AAC (no YouTube live, no Twitch); (b) emergency: CinemaMod's hosted CEF 116 natives (codecs, old Chromium, different java-cef fork API).
- **Native patches** (one build, before the first release, so later features do not force a re-download):
  1. plain-int JNI entry points for key, mouse and wheel events (the fork looks up `org/lwjgl/sdl/*` classes that do not exist on Java 8 or lwjgl3ify 2.x);
  2. Windows Job Object with `KILL_ON_JOB_CLOSE`, plus a renamed helper (`akashicweb_helper`): patch the CMake target, `CefApp.initialize()` (it overwrites `browser_subprocess_path`) and the manifest;
  3. audio: `audio_handler.cpp` must use JCEF's cached-loader `FindClass`, read back the audio params (request 48 kHz stereo), and hand Java a `NewDirectByteBuffer` per channel (the fork's `DataPointer` needs LWJGL3 `MemoryUtil`);
  4. Linux: build in an older container or link libstdc++ statically; CI fails if `objdump -T` shows GLIBC/GLIBCXX above the declared floor.
- **Java side:** org.cef classes compiled with `--release 8` (class major 52), **not relocated** (JNI symbols are bound to `org.cef` names). Use CCBlueX's own `CefBrowserOsr` (or a thin subclass) with a paint listener; a custom OSR class is unnecessary (technical review). Refuse to start, with a message, if another mod already loaded `org.cef.CefApp`.
- **Threading:** a daemon thread `AkashicWeb-Pump` runs `CefApp` init, the external message pump and shutdown. `onPaint` copies dirty rects into a triple buffer (0.85-1.14 ms per 1080p frame measured); the render thread uploads only the newest frame.
- **Switches:** `--no-first-run` (CEF 152 exits with code 28 without it in the container), `--no-default-browser-check`, `--autoplay-policy=no-user-gesture-required`. **Never** `--disable-web-security`. No devtools, remote debugging, downloads or context menu; pop-ups are converted into same-screen navigations or blocked.
- **Profile:** per instance, under `%LOCALAPPDATA%\AkashicWeb\profiles\<hash(instanceDir)>` on Windows (never OneDrive/Documents), `<gameDir>/akashic-web/profile` elsewhere; `PROFILE_LOCKED` placeholder on conflict.
- **Process model:** in-process for 10-25; a separate browser-host process (shared-memory frames, PCM pipe, supervisor) after the event.

### 4.2 Mod structure

A new GTNH-buildscript mod (`modid` to be chosen, e.g. `akashicweb`), Jabel to class major 52, CI check that every class (including the org.cef jar) is major 52.

| Package | Role | Rule |
|---|---|---|
| `common` | Screen block, multiblock, `BlockSide`, hit-to-pixel, rotation, `TileEntityScreen` (NBT state), rights, packets | No org.cef types anywhere, so the dedicated server never loads natives |
| `server` | Authoritative state, validated packets, rate limits, site policy, UrlPolicy, AuditLog, `/web` admin command, kill switch | Main-thread send queues only (AkashicFM `ServerActionQueue` pattern) |
| `client` | TESR (draw only), texture upload at `RenderTickEvent` START, keyboard GUI and VK mapping, address bar GUI, placeholders | Every exception caught; a placeholder is drawn instead |
| `client.web` | The ONLY package that touches org.cef: `IWebView` adapter, pump thread, triple buffer, init state machine with reason codes, `BrowserRegistry` | Loaded only on the client |
| `client.natives` | `NativesInstaller` (manifest pinned in the jar, SHA-256 before extraction, zip-slip guard, resume, timeouts, mirrors, atomic install, consent prompt, manual-zip import) | Built from AkashicFM `ToolManager` (raise caps: 512 MiB/file, 1 GiB total) |
| `sync` | Clock ping/pong, video-state relay | Copy of the AkashicFM pattern or soft dependency |
| `audio` | Bridge to AkashicFM (Stretch) | Soft dependency, reflective |

Mixins (UniMixins) only where Forge has no hook: a `Minecraft.shutdown` hook for deterministic CEF disposal. Angelica, lwjgl3ify and AkashicFM are reached by reflection with availability checks.

Reuse from Web Displays (public domain, keep montoyo's credit and file headers): `Multiblock`, `BlockSide`, `Vector3i/2i`, `Rotation`, `hit2pixels`, the rights bit model and the screen NBT layout. Do **not** copy `net.montoyo.mcef.*` (no licence grant). Keep the MIT notice for anything borrowed from the CinemaMod/Mysticpasta1 fork (e.g. the `ScreenControl` registry idea). Drop `ironic.ogg`, miniserv and the `wd://` scheme.

### 4.3 Rendering (Angelica-safe)

- The TESR only draws: one `Tessellator` quad per face, full-bright (lightmap 240/240), lighting off.
- Upload once per frame at `RenderTickEvent` START, outside the TESR (shader packs render TESRs again in the shadow pass). GL11/GL12 calls from our own classes (Angelica redirects them); texture `GL_RGBA8`, LINEAR, CLAMP, no mipmaps; per dirty rect set `UNPACK_ROW_LENGTH/SKIP_PIXELS/SKIP_ROWS`, `glTexSubImage2D(..., GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, ...)`, then reset the unpack state to 0 (Angelica beta66b does not track it). No PBOs in the MVP.
- `getRenderBoundingBox` returns the whole multiblock from the description packet before the first render; raise `getMaxRenderDistanceSquared`; on Angelica 2.x also call `TileEntityRenderBoundsRegistry.registerDynamicClass` reflectively (the class does not exist on beta66b).
- Browser hidden (`wasHidden`, uploads stop) when off-frustum or beyond the load range; closed beyond the unload range, with hysteresis. Fixed browser resolution per screen (default 1280x720, presets 480p/720p/1080p), `windowless_frame_rate` 30. Client option: Off / 480p / 720p.

### 4.4 Input

- Right-click on a screen → server-side hit-to-pixel → click broadcast. CEF button numbering differs from LWJGL2 (1 and 2 swapped). Wheel normalised to notches x 120.
- Keyboard block GUI: `RAWKEYDOWN`/`KEYUP` with real modifiers through an LWJGL2 → Windows VK table for named keys only; text as `KEYEVENT_CHAR` from composed characters (ABNT2 dead keys); `InputEvents.beginTextInput/endTextInput` reflectively on lwjgl3ify 3.x; paste via `GuiScreen.getClipboardString`; copy/cut never replicated. The old remap that turned `$ % &` into Home/Left/Up must not be copied.
- Password fields: the controller's client checks the focused element through injected JS and does not relay keystrokes while it is a password field (INFERRED approach).

### 4.5 Navigation and sync model

- The server owns each screen's state: URL, resolution, rotation, owner, rights, lock, controller, video state.
- Input events from a player with rights are relayed to every client tracking the screen (Web Displays semantics), so pages stay aligned.
- Main-frame navigations on the controller's client are reported as a rate-limited `SetURL`; the server validates (rights, UrlPolicy, site policy, length) and broadcasts; non-controlling clients cancel their own main-frame navigations and load the server URL. This removes WD's "cannot be fixed" FIXME.
- Video: the controller reports the main media element state; the server stamps it with `ServerClock` time; followers steer with `playbackRate` (±5%) or seek when more than ~1 s off. Lives are held at a common offset behind the live edge when DVR exists. Target skew 0.5-2 s (INFERRED; measured at G4).
- Updates go to every player tracking the chunk (plus a margin), not WD's fixed 64 blocks.
- Optional, not in the MVP: the **Akashic relay** (OBS/ffmpeg → MediaMTX → WebRTC/LL-HLS with VP9/AV1 + Opus). Measured locally inside JCEF: WebRTC 126 ms added delay, 22 ms between two clients; LL-HLS ~1.04 s. Useful only for frame-level sync or a licensed non-YouTube source; needs ~2 Mbit/s egress per viewer and per-player tokens. See [05](research/05-codecs-live-relay-rights.md).

### 4.6 Audio

- **MVP:** Chromium's own output; an injected script sets every `<video>`/`<audio>` volume = distance gain (AkashicFM `GainModel.sourceGain` curve) x MC master x records, pushed every tick; muted and closed on unload, world unload, disconnect, dimension change.
- **Stretch (target 10-25):** `CefAudioHandler` → non-blocking drop-oldest ring → AkashicFM `AudioFeed` → `AudioEngine.touch` with LEFT/RIGHT `EmitterSpec`s at the screen edges, `OcclusionField` and room reverb (EFX), Minecraft volumes and mute keys, voice budget across many screens. Needs, in AkashicFM: an `ExternalAudioSources` registration API (its `RadioAudioController` currently releases foreign playback keys every tick), a non-blocking `PcmRing` variant, and a timed feed so lip-sync survives game hitches (video delayed by a matching 4-5 frame ring). CEF mutes its own output while capturing (verify on 150 at G1). Requires AkashicFM in the server pack.

### 4.7 Security and moderation (public server)

- Server site policy in config: **allowlist** mode (recommended for public screens) or **blocklist** mode (known IP-logger and URL-shortener domains). Reason: every nearby client loads a screen's URL, so a malicious link collects viewers' IPs.
- `UrlPolicy.isInternal` (from AkashicFM) on the server and in the client's `onBeforeBrowse`/`onBeforeResourceLoad`: no private, loopback, link-local addresses, no `file://`, `chrome://`, `devtools://`.
- Rights checked server-side on every packet; per-player rate limits and length caps; per-player screen limits; AuditLog; admin clear/lock/ban; kill switch.
- Web security stays on. The Chromium sandbox stays off (JCEF hard-codes `no_sandbox`); the allowlist and the internal-address block are the mitigation. Keep CEF patch-current after the event.

### 4.8 Natives distribution

- Manifest pinned in the mod jar: `{platform, url, size, sha256, buildId}`. Download to `.part` with Range resume, timeouts, retries and a mirror list; SHA-256 **before** extraction; per-entry allowlist and canonical-path check; atomic rename and `.complete` marker; hashes re-verified at startup.
- Install to `%LOCALAPPDATA%\AkashicWeb\<buildId>` on Windows and `<mcDataDir>/akashic-web/<platform>/<buildId>` elsewhere; ASCII-safe fallback for non-ASCII paths; never `mods/`, never relative to the working directory.
- Windows codec payload: either download OBS's official zip on the player's machine (OBS hash pinned, no Akashic mirror) and stream-extract only `Release/` + `Resources/` (~415 MB), or ship a repackaged bundle (Akashic then redistributes codec binaries). Linux codec build implies a stripped repackage (libcef.so is 2.03 GB unstripped). Owner decision (DECISIONS P-03).
- Consent prompt with exact sizes; manual-zip import; extra ISRG roots for old JREs; refuse 32-bit Java with a placeholder.
- A small "pre-install" release (installer + preflight only) goes out once the native buildId is frozen, so players download the big payload during the week, not on election night.

### 4.9 Diagnostics and resilience

- Init state machine with reason codes (`DOWNLOAD_FAILED`, `HASH_MISMATCH`, `NATIVE_LOAD`, `GPU_CRASH`, `UNSUPPORTED_OS`, `UNSUPPORTED_ARCH`, `PROFILE_LOCKED`, `DISABLED_BY_SERVER`, `CRASH_GUARD`) drawn on the screen in pt-BR and English.
- `/web doctor` and an `about:blank` paint self-test; preflight result reported to the server (opt-in telemetry: OS, GPU, Java, lwjgl3ify version; LGPD text; no identifiers in committed evidence).
- Crash-loop guard: marker file before CEF init and browser creation; if present at the next start, stay disabled until re-enabled in a GUI; delay browser creation 10-20 s after join; server flag in the login handshake forces every client to the placeholder; `akashic-web/DISABLED` file as self-rescue.
- Deterministic shutdown: mixin + shutdown hook → dispose on the pump thread with a 3 s timeout → halt watchdog. Job Object on Windows. Startup sweep of stale `akashicweb_helper` processes (only ours).
- Watchdog: no paint or video progress for N seconds → placeholder + automatic reload.
- GPU: default `--disable-gpu-compositing` for OSR with a GPU toggle; GPU-process crash → software fallback.

### 4.10 Platforms

- Tier 1: Windows 10/11 x64, Java 8 (LWJGL2) and Java 17-25 (lwjgl3ify), Angelica on, on the server's current GTNH version.
- Tier 2: Linux x64 (glibc floor measured from our build).
- Placeholder only (keeps playing, sees a message): macOS, arm64, Windows 7/8.1, 32-bit Java, Pojav/Android, OpenJ9.

## 5. Latency: what to promise

| Part | Delay | Controlled by |
|---|---|---|
| Live vs. the real event | YouTube: under 5 s (ultra-low latency), under 10 s (low), more on normal; the broadcaster chooses | The broadcaster |
| Added by the in-game browser | Tens of ms (render at 30 fps) | Us |
| Between players | 1-10 s without sync; target 0.5-2 s with the shared video clock (INFERRED) | Us |
| With the optional relay | +0.3-0.6 s (WebRTC, INFERRED over WAN; 126 ms measured locally) and tens of ms between players | Us |
| The owner's own restream | His platform's latency on top; his viewers see only his client | His channel settings |

"Almost zero ms" behind the real event is impossible for any internet live. What we can deliver: the same latency as watching in Chrome, and players close to each other.

## 6. Schedule (from 2026-10-11; one developer plus an AI assistant assumed)

| Date | Work | Exit criteria |
|---|---|---|
| Sun 10-11 | Owner decisions P-01..P-08. Fork CCBlueX java-cef; CI job #1 = Windows x64 against OBS CEF 7871 (and Spotify 152 in parallel); Linux in an old container. Mod skeleton (GTNH buildscript, proxies, GTNHLib config, major-52 check) | Decisions in writing; Windows artifact building |
| Mon 10-12 | Native patches (input JNI, Job Object + helper rename, audio FindClass/params/NewDirectByteBuffer). Standalone Java 8 probe on the owner's Windows PC | First Windows artifact + SHA-256 on the owner's PC |
| Tue 10-13 | **G1 engine gate** on Windows and Linux. Screen block, multiblock, hit-to-pixel, TileEntity NBT, description packet | G1 pass or fallback engine chosen |
| Wed 10-14 | NativesInstaller; client.web adapter; upload at RenderTickEvent; draw-only TESR; Angelica bounds | First in-game frame on Windows Java 8 |
| Thu 10-15 | Rights, packets, server URL authority, site policy, UrlPolicy, AuditLog, `/web`. Address-bar GUI | A player browses YouTube on a screen in a dev client |
| Fri 10-16 | **Native buildId frozen** (after this only Java-side fixes). Pre-install release to players. Keyboard block + ABNT2. Crash guard, kill switch, reason codes. **G3 in-game gate** | G3 pass on Java 8 and Java 21 |
| Sat 10-17 | Closed alpha with 5-10 real players on their own Windows PCs. Video sync. Distance volume. 3D audio bridge starts (Stretch) | Every alpha client shows video or a correct reason code; zero hard crashes |
| Sun 10-18 | **G4 public rehearsal**, 20:00 BRT, 10+ players, on a live news stream | G4 criteria |
| Mon 10-19 | Buffer day: G4 fixes | G4 blockers closed |
| Tue 10-20 | **Feature freeze, RC1** (not "stable"). P0/P1 matrix. Second announcement | RC1 hashes recorded; P0 green |
| Wed 10-21 | Bug fixes; 3D audio go/no-go (else ship distance volume) | Decision recorded |
| Thu 10-22 | **G5 rehearsal #2** on RC1, including the kill switch and the fallback ladder | G5 criteria |
| Fri 10-23 | Buffer day. Build the spawn telão on production, set ownership and site policy; install deadline for players | Telão shows the test stream to staff |
| Sat 10-24 | **G6 dress rehearsal** 17:00-20:00 BRT incl. the owner's unlisted restream; go/no-go 22:00 BRT; code freeze | GO/NO-GO recorded |
| Sun 10-25 | 13:00 checks; 15:30 telão live; 17:00 results. No server restarts or backups that pause the server 14:00-01:00 | Telão runs to the end of coverage; incidents logged |

Cut order if behind (first cut first): Linux tier → 3D audio (keep distance volume) → keyboard IME polish → address-bar extras (keep URL entry, back, reload) → admin GUI (keep commands). Never cut: crash guard, kill switch, site policy, internal-address block, installer hash checks.

## 7. Gates

- **G1 (engine, 10-13)**, standalone Java 8 probe on a real Windows 10/11 x64 PC and Linux x64: (a) CEF starts 5/5 times; (b) ≥25 paints/s at 1280x720 on a 30 fps page; (c) an H.264/AAC MP4, a YouTube watch page and a YouTube live play with audio (unmuted, autoplay switch on); (d) the process tree is gone 5 s after exit; (e) the helper runs under the renamed image; (f) audio capture delivers PCM and the OS output goes silent. Fail on OBS-150 Windows → Spotify-152 (no lives) or CinemaMod 116 decided by 10-14.
- **G3 (in game, 10-16)**, frozen GTNH version, Windows, Java 8 and Java 21 (lwjgl3ify), Angelica default: video + audio on a 16x9 screen; no culling with the origin block off-screen; median FPS drop ≤25% on the owner's PC and one iGPU laptop at a fixed position and view (60 s average); 10 join/leave/break/dimension-hop cycles with zero crashes and no lingering audio; dedicated server boots without natives; a client without natives joins and sees the placeholder; a forced native kill + rejoin triggers the crash guard.
- **G4 (public rehearsal, 10-18)**, 10+ players: ≥90% of preflight-OK players see video within 15 s of entering range (first-frame telemetry); skew between players ≤2 s measured from page telemetry; zero mod-attributable crashes; no rights bypass; no internal-address or off-policy navigation.
- **G5 (rehearsal #2, 10-22)**: G4 criteria on RC1; kill switch and URL fallback work in under 10 s for all clients; preflight OK for ≥60% of players active in the last 14 days (named action if below).
- **G6 (dress, 10-24)**: G5 still met; recovery from a browser crash and from the source stream dropping; the owner's restream mitigation (mute/blur/switch) works in under 5 s. NO-GO → the telão shows the TSE official results page (still a real web page in the browser), labelled as the safety net.

## 8. Test matrix

- **Axes:** OS {Windows 10/11 x64, Linux x64, macOS placeholder-only} x Java {8, 21} x GTNH {the server's current version first; 2.8.4 = lwjgl3ify 2.1.16 GLFW + Angelica beta66b; 2.9.0-RC = lwjgl3ify 3.0.37 SDL3 + Angelica 2.2.28; 2.7.4} x Angelica {default, shaders on (2 packs), off if possible}.
- **P0 (by 10-20):** Win11 Java 8; Win11 Java 21; Win10 Java 8 on a low-end iGPU with 8 GB RAM; dedicated server Java 8 and 21 (no natives). Each cell: preflight, YouTube watch page, YouTube live, MP4, address bar, keyboard, rights, kill switch, join/leave/break/dimension hop, exit leaves no helper, missing natives → placeholder, crash guard.
- **P1 (by 10-21):** Win11 Java 21 + shaders; Linux x64 Java 8/21; `C:\Users\João Conceição` (accent + space); Prism instance name with spaces and parentheses; instance under OneDrive Documents; Controlled Folder Access on; non-admin account; launchers Prism (GTNH zip), CurseForge (bundled 8u51 JRE), MultiMC, ATLauncher, vanilla; Defender + Avast or Kaspersky; Intel iGPU, AMD, NVIDIA; ABNT2 typing `ação ç $%&` on Java 8 and 21; two instances at once; Akashic Voice and AkashicFM co-installed.
- **P2 (smoke):** other GTNH versions the players actually use.
- **Placeholder-only:** macOS Intel/ARM, Windows arm64, Win7/8.1, 32-bit Java.
- **Security:** non-rights SetURL/Click/Type rejected and audited; internal-IP and `file://` URLs blocked on both sides; off-policy navigation cancelled; rate limit triggers; password field keystrokes not relayed.

Evidence rule: every cell is recorded with its grade (BUILD / AUTOMATED / LIVE-OBSERVED). A Linux harness never substitutes for a Windows LIVE-OBSERVED cell.

## 9. Election-night operations (outline)

Roles with deputies: director (telão URL), relay/ingest operator (only if the relay is used), server operator (TPS, restarts disabled), support (#telao-suporte from 14:00). Fallback ladder for the telão: primary YouTube live → second allowlisted channel → TSE official results page → poster. Kill switch documented in a printed pt-BR runbook. No server restarts, backups or world saves that pause the server between 14:00 and 01:00. Raise max players, preload spawn chunks, watch TPS.

## 10. Honest odds

The adversarial review of the first (smaller, kiosk) plan estimated, INFERRED: about 35-50% that most attending Windows players see live video on 10-25, and 80-90% that at least a real web page runs in the in-game browser for players with natives. The interactive scope is larger; re-estimate after G1 and after G3. The biggest drivers: the Windows native build (G1), the owner's decisions this week, one developer for the whole scope, players installing the natives in time, and field defects that only real Windows PCs reveal.
