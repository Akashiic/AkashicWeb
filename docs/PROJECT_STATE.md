# Project state

Last update: 2026-10-10 (UTC). Phase: **research complete, no code.** Nothing is built, released, tested in game, or live-approved.

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
| Mod source code | None | — |
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

Answered on 2026-10-11: Forbidden Series (Technic) on Crucible, not GTNH (D-009); AkashicFM is in the pack (D-010); codec-enabled Chromium accepted (D-011); team = owner + AI assistant (D-012). Pending: the pack's mod list (P-11), site policy and admins (P-05), channels (P-07), licence (P-08), telemetry text (P-09). See [`DECISIONS.md`](DECISIONS.md).

## In progress

A skeptical technology review (seven specialists: engine and process model, frame transport, codecs, audio, mod stack for Forbidden/Crucible, natives distribution, multiplayer model) followed by architecture decision records. Its results will update `PLAN.md`.
