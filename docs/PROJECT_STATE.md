# Project state

Last update: 2026-10-13 (UTC). Phase: **implementation, wave 2 (review fixes).** All five modules exist and build; nothing is released, tested in game or on Windows, or live-approved.

## Evidence grades used in this repository

| Grade | Meaning |
|---|---|
| AUTHORED | Written, not checked |
| STATIC | Checked by reading code or docs |
| BUILD | Compiled or packaged |
| AUTOMATED | Ran in a harness or test (state the OS) |
| LIVE-OBSERVED | Seen in a real Minecraft client/server |
| LIVE-APPROVED | Accepted by the owner after a live run |

The research files use VERIFIED (≈ STATIC or AUTOMATED, with a source) and INFERRED (reasoning or estimate).

## What exists

| Item | State | Grade |
|---|---|---|
| Research on Web Displays, MCEF, issues, CEF engines, GTNH integration, codecs, latency, rights | Done ([`research/`](research/)) | STATIC / AUTOMATED (Linux container) |
| Plan, decisions, problem→fix matrix, risk register | Written | AUTHORED |
| Probe and harness sources from the research | Kept in [`/spikes`](../spikes) | AUTOMATED (Linux container only) |
| Mod code, wave 1 (merged 2026-10-13, commit `d9a69c4`): host app `host/` (M1), host connection `client.host` (M2), engine installer `client.install` (M3), screen block, rights, network and commands in `common`/`server` (M4), client screens, rendering, input and sync in `client.*` (M5) | `./gradlew build` green: 440 JUnit tests, 2 env-gated skips; all 321 classes in the jar are major 52; no natives in the jar; no client/LWJGL/process/socket references from `common`/`server` (Linux container, Java 21 toolchain) | BUILD / AUTOMATED |
| AkashicWebHost app on the real Electron 44.7.0 | 123 unit tests + 29 integration tests pass under Xvfb (Linux container, Node 22); not run on Windows | AUTOMATED (Linux only) |
| Crucible boot with the mod (owner's `server.jar`, Java 21, UniMixins, lwjgl3ify, GTNHLib; no owner plugins) | Mod loads on the dedicated server; commands register through Crucible's command group (node naming bug found) ([evidence](evidence/2026-10-13-crucible-boot-and-dev-client.md)) | AUTOMATED (Linux) |
| First in-game run: Linux dev client (Java 8, LWJGL 2.9.4, Mesa) joined that server, formed a 4x3 screen, placeholder drawn, address bar opened, `/web info` correct | Two client bugs found (placeholder text format, owner rights by UUID); no browser yet (not wired at `b9e844c`) | LIVE-OBSERVED (Linux dev client, not Windows/Forbidden) |
| Wave-1 reviews | M1 and M2 reviewed (M1: 2 high, 8 medium; M2: 1 high, 5 medium), fixes in progress; M3, M4, M5 reviews running in wave 2 (the wave-1 runs hit the usage limit) | — |
| IPC and module contracts ([`PROTOCOL.md`](PROTOCOL.md), [`CONTRACTS.md`](CONTRACTS.md)) | Written; contract types compile; protocol changes from D-016 and the reviews are being folded in | AUTHORED / BUILD |
| Native builds (libjcef/jcef.dll) | None kept; the Linux build in the research container was a throwaway | — |
| Releases | None | — |

## What the research ran (Linux cloud container, not Windows, not Minecraft)

- Upstream jcef-api 152 (jcefmaven natives) in a Java 8 OSR probe without JOGL: 1080p VP9/Opus at 29.7 fps (30 fps setting) and 59.3 fps (60 fps setting); paints on the AWT EDT; `--no-first-run` required (exit code 28 otherwise). AUTOMATED.
- Codec probe on stock CEF 152: VP9, AV1, Opus, MP3, Vorbis yes; H.264, AAC, HEVC, MPEG-TS no. AUTOMATED.
- CinemaMod CEF 116 natives: H.264/AAC MP4 played. AUTOMATED.
- CCBlueX java-cef (fe8cc9e) compiled against OBS CEF 150.0.19 (Linux) with a 6-line patch: H.264/AAC, MSE TS, native HLS, WebRTC VP8/VP9/H.264/AV1 + Opus. AUTOMATED (Linux only).
- CCBlueX Java sources compile with `javac --release 8` (143 files). BUILD.

## What was NOT run

Anything on Windows or macOS; anything inside Minecraft (any GTNH version, Angelica, lwjgl3ify, shaders); any WAN test; anything against YouTube (the container's IP is bot-walled); any load test; CEF audio capture end to end; the Windows OBS-CEF build; the no-codec CEF 152 with WebRTC at runtime.

## Gates

| Gate | Date | State |
|---|---|---|
| G1 engine (Windows + Linux probe) | 2026-10-13 | Not started |
| G3 in game | 2026-10-16 | Not started |
| G4 public rehearsal | 2026-10-18 | Not started |
| G5 rehearsal #2 | 2026-10-22 | Not started |
| G6 dress rehearsal + go/no-go | 2026-10-24 | Not started |

## Owner decisions

Answered: Forbidden Series (Technic) on Crucible, not GTNH (D-009); AkashicFM is in the pack (D-010); codec-enabled Chromium accepted (D-011); team = owner + AI assistant (D-012); pack mod list (D-013, answers P-11); engine/codec/test choices delegated, produce without date pressure (D-014). Engineering decisions under D-014: Electron host (D-015), page input stays on the client (D-016). Pending: site policy and admins (P-05), channels (P-07), licence (P-08), telemetry text (P-09), consent wording and installer behaviours (P-12), LuckPerms grant for `/web` (P-13). See [`DECISIONS.md`](DECISIONS.md).

## Technology review (done 2026-10-12)

Seven specialists, an architect and an adversarial reviewer produced ADR-001..ADR-016 ([`adr/README.md`](adr/README.md); challenges in [`research/08-tech-challenge.md`](research/08-tech-challenge.md)). Main outcome: the browser moves out of the game JVM into AkashicWebHost on the official Electron 44.x build (D-015). Electron OSR, H.264/AAC playback, PCM capture, ABNT2 input and process cleanup were VERIFIED only in a Linux container; Windows is untested.

## Now

Wave 2: reviews of M3, M4 and M5 and fixes for all five modules: the host's internal-address check on every request, document-start volume and guards, fail-closed policy, thread-death handling in the host connection, OS trust store for old JREs, D-016 in the protocol, client wiring of the installer and host plus the consent screen. Then: integration, an end-to-end test with the real Electron host from Java, the Windows test kit for the owner (G1-E), the Crucible boot test, and the first in-game run (G3). Dates are not the driver (D-014).
