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
| D-008 | 10-11 | **Abandoned:** TSE results data and the "Akashic live relay" (OBS/MediaMTX/WebRTC re-streaming). The focus is only a Web Displays-like mod that runs a browser inside Minecraft; lives are watched by opening the site in the browser. | Owner, 2026-10-11. Raw research JSON still mentions both as history. |
| D-009 | 10-11 | Target pack: the Rede Akashic **Forbidden Series** Technic modpack (Forge 10.13.4.1614), server **Crucible 1.7.10** (Java 21 in production), not GTNH. (Answers P-01.) | Desktop players use the Technic launcher (Java 8 typical); Android players use ForbiddenMobile and get a placeholder. The pack's exact mod list is still needed. |
| D-010 | 10-11 | AkashicFM is already in the Forbidden pack; 3D audio uses its engine. (Answers P-02.) | AkashicFM hard-depends on GTNHLib, so GTNHLib is probably in the pack (INFERRED until the mod list is checked). |
| D-011 | 10-11 | The codec-enabled Chromium (H.264/AAC) is accepted. (Answers P-03.) | Windows: download from OBS's CDN on the player's PC. Linux: decide later. |
| D-012 | 10-11 | Team until 10-25: the owner (tests on his Windows PC, decides) + the AI assistant (writes code). (Answers P-04.) | Cut order in PLAN section 6 applies. |
| D-013 | 10-12 | Pack facts from the owner's files (answers P-11): UniMixins 0.3.1, GTNHLib 0.11.51 and ModularUI2 2.3.85 on client and server; Angelica 2.2.20 + BSL/Complementary shaders on the client; LWJGL2 Technic client; Crucible staging-67b53034 on RFB + Java 21 with lwjgl3ify 3.0.31; LuckPerms/WorldGuard/GriefPreventionPlus/FinalForgeRestrictor/BlockLimiter plugins. | [`research/07-forbidden-pack-inventory.md`](research/07-forbidden-pack-inventory.md) |
| D-007 | 10-10 | Repository `Akashiic/AkashicWeb` created by the owner; the build happens in a new chat from these docs. | — |

## Owner decisions (pending)

P-11 (pack mod list) answered on 10-12 (D-013).


| ID | Question | Why it matters | Recommendation |
|---|---|---|---|
| P-05 | Site policy for public screens: allowlist or blocklist? Who are admins (OP level, list, UUIDs)? | IP-logger and abuse risk (PLAN 4.7). | Allowlist at spawn and public areas; blocklist elsewhere. |
| P-06 | Platform stance: Windows Tier 1, Linux Tier 2, macOS placeholder for 10-25? | Sets what is tested and announced. | Yes. |
| P-07 | Which live channel(s) for the spawn telão, plus a second backup channel? Does it allow embedding? | Rehearsals and the fallback ladder use them. | Pick two by 10-18. |
| P-08 | Repository licence for AkashicWeb (AkashicFM uses its own LICENSE). | Needed before publishing jars. Inputs: WD public domain, CCBlueX java-cef BSD, CinemaMod MCEF LGPL-2.1 (not used as a base), Web Displays fork MIT. | — |
| P-09 | Telemetry opt-in text (LGPD) and retention. | Preflight/diagnostics report OS, GPU, Java. | Opt-in, no identifiers in committed evidence. |

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
| T-09 | No `--disable-web-security`; allowlist/blocklist site policy; internal-address block on both sides. | Security review | Security tests |
| T-10 | Native buildId frozen on 10-16; afterwards only Java-side fixes so players never re-download. | Feasibility review | — |
