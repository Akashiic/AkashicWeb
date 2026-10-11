# Decisions

Owner decisions are binding. Technical decisions are proposals from the research until a gate confirms them. Dates are 2026.

## Owner decisions (made)

| ID | Date | Decision | Context |
|---|---|---|---|
| D-001 | 10-05 | The product is a **real web browser** in game (Web Displays style). A data/results board or a radio-only solution is rejected. | The first answer proposed a TSE-JSON results board plus AkashicFM radio; the owner rejected it explicitly. Do not propose it again as the product. |
| D-002 | 10-10 | The browser is **interactive for players**: they build screens, click, type and navigate. It is **not** an admin command/preset kiosk. The spawn telão is one big screen owned by the owner, who opens the live through the address bar. | The first plan (`research/raw/06-plan-kiosk-scope.json`) assumed a locked kiosk; it was re-scoped in [`PLAN.md`](PLAN.md) and [`PROBLEM_FIX_MATRIX.md`](PROBLEM_FIX_MATRIX.md). |
| D-003 | 10-10 | AkashicWeb is a **from-scratch mod** with the Web Displays idea, the way AkashicFM relates to OpenFM. Web Displays is public domain, so its engine-independent pieces (multiblock, hit-to-pixel, rotation, rights bits) may be reused **with credit** to montoyo. | `net.montoyo.mcef.*` has no licence grant and must not be copied. |
| D-004 | 10-10 | Code quality bar: "premium", like AkashicFM: GTNH buildscript, GTNHLib, UniMixins (minimal mixins), Hodgepodge/Angelica/lwjgl3ify compatibility, Java 8 bytecode, tests, E2E, evidence discipline. | See [`../AGENTS.md`](../AGENTS.md). |
| D-005 | 10-10 | Sound should use **AkashicFM's audio technology** (3D, occlusion, reverb, organised sources). | Planned as Stretch for 10-25 with a distance-volume fallback (PLAN 4.6). |
| D-006 | 10-10 | Low-delay lives are wanted. | Expectations set in PLAN section 5: browser adds tens of ms; the source sets the rest. |
| D-007 | 10-10 | Repository `Akashiic/AkashicWeb` created by the owner; the build happens in a new chat from these docs. | — |

## Owner decisions (pending; ask at the start of the next chat)

| ID | Question | Why it matters | Recommendation |
|---|---|---|---|
| P-01 | Which server and modpack get the mod: GT New Horizons (which exact version, Java 8 / 17-25?) or the Technic "Forbidden Series" (Crucible + Velocity, per Akashic-Voice docs)? | Changes the stack (Angelica/lwjgl3ify versions), the test matrix and the server software (Crucible changes commands/permissions). | Freeze the CURRENT version for the event; never migrate the world for this. |
| P-02 | Is AkashicFM in (or going into) that server's pack? | 3D audio uses its engine; without it the screen uses distance volume only. | — |
| P-03 | Accept the codec-enabled Chromium (OBS CEF 150, H.264/AAC via FFmpeg)? Patent status is a grey area (CEF maintainer warns about codec licensing; last major US H.264 patent reportedly ~2027-11, INFERRED from secondary sources). Windows: download from OBS's CDN on the player's PC (no Akashic redistribution) or ship a repackaged bundle? Linux: repackage (redistribution) or codec-free? | Without it, YouTube live and Twitch do not play. | Accept on Windows with download-from-OBS + selective extraction; decide Linux separately. Get a quick legal opinion if possible. |
| P-04 | Staffing: only the owner + an AI assistant? | The schedule assumes one developer; cut order in PLAN 6. | — |
| P-05 | Site policy for public screens: allowlist or blocklist? Who are admins (OP level, list, UUIDs)? | IP-logger and abuse risk (PLAN 4.7). | Allowlist at spawn and public areas; blocklist elsewhere. |
| P-06 | Platform stance: Windows Tier 1, Linux Tier 2, macOS placeholder for 10-25? | Sets what is tested and announced. | Yes. |
| P-07 | Which live channel(s) for the spawn telão, plus a second backup channel? Does it allow embedding? | Rehearsals and the fallback ladder use them. | Pick two by 10-18. |
| P-08 | Repository licence for AkashicWeb (AkashicFM uses its own LICENSE). | Needed before publishing jars. Inputs: WD public domain, CCBlueX java-cef BSD, CinemaMod MCEF LGPL-2.1 (not used as a base), Web Displays fork MIT. | — |
| P-09 | Telemetry opt-in text (LGPD) and retention. | Preflight/diagnostics report OS, GPU, Java. | Opt-in, no identifiers in committed evidence. |
| P-10 | Optional Akashic relay (frame-level sync, licensed non-YouTube source): wanted for 10-25 or later? | Needs a VPS with ≥300 Mbit/s egress and rights. | Later, unless a licensed source needs it. |

## Technical decisions (proposed by the research; confirm at gates)

| ID | Decision | Evidence | Gate |
|---|---|---|---|
| T-01 | Engine: CCBlueX java-cef (BSD), pinned, built by our own CI against OBS CEF 7871 (codecs). Fallbacks: Spotify CEF 152 (no codecs), CinemaMod 116 (emergency). | Linux build + H.264/AAC playback VERIFIED; Windows untested | G1 |
| T-02 | Old MCEF 1.7.10 and montoyo's MCEF code are not used. | VERIFIED unviable | — |
| T-03 | In-process CEF for 10-25; separate host process after. | Time | — |
| T-04 | Daemon pump thread + triple-buffered onPaint copy + upload at RenderTickEvent START from our own GL11 classes. | Copy cost measured; Angelica rules VERIFIED in code | G3 |
| T-05 | Server-authoritative URL; controller navigation reported as SetURL; shared video clock (ServerClock pattern). | Design (INFERRED) | G4 |
| T-06 | Natives installer from AkashicFM ToolManager, pinned manifest, SHA-256 before extraction, LOCALAPPDATA on Windows. | Pattern VERIFIED in AkashicFM | G3 |
| T-07 | Minimal mixins (shutdown hook); Angelica/lwjgl3ify/AkashicFM via reflection. | Design | — |
| T-08 | Akashic relay (MediaMTX) is optional, not the primary path. | Superseded by the interactive scope + codec build | — |
| T-09 | No `--disable-web-security`; allowlist/blocklist site policy; internal-address block on both sides. | Security review | Security tests |
| T-10 | Native buildId frozen on 10-16; afterwards only Java-side fixes so players never re-download. | Feasibility review | — |
