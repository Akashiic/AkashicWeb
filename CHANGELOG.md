# Changelog

## Unreleased

### Added (2026-10-10)
- Project documentation from the research chat: README, CLAUDE.md, AGENTS.md, `docs/PROJECT_STATE.md`, `docs/PLAN.md`, `docs/DECISIONS.md`, `docs/PROBLEM_FIX_MATRIX.md` (88 problems with fixes), `docs/ISSUES_REGISTER.md`, `docs/HANDOFF.md`, `docs/PT-BR_RESUMO.md`.
- Research write-ups with sources in `docs/research/` (Web Displays code, issue catalog, CEF engines, GTNH integration, codecs/latency/rights, plan reviews, TSE data) and the raw agent outputs in `docs/research/raw/`.
- Research probe sources in `spikes/` (Linux-container evidence only).

### Changed (2026-10-11)
- Self-review of the docs against the raw research (the automated review agents hit the usage limit): removed kiosk-scope leftovers from `docs/PROBLEM_FIX_MATRIX.md` (codec build is now the primary video path; `/web` command), added a data model / packet / config / command / test sketch to `docs/PLAN.md` (section 4.11), marked README comparisons as planned and the H.264 patent date as INFERRED.

### Removed (2026-10-11)
- TSE results data notes and the live-relay material (relay latency harness, encode benchmark, YouTube format parser), per owner decision D-008: the project is only a Web Displays-like browser mod.

### Decided (2026-10-11)
- Owner answers recorded: Forbidden Series on Crucible (D-009), AkashicFM in the pack (D-010), codec build accepted (D-011), team = owner + AI (D-012). New risks AW-25..AW-27; new pending item P-11 (pack mod list).

### Added (2026-10-12)
- `docs/research/07-forbidden-pack-inventory.md`: client pack TEOF-3.6 and server composition facts (D-013); risks AW-28, AW-29.

### Decided (2026-10-12)
- Technology review: ADR-001..ADR-016 in `docs/adr/README.md` (with reviewer amendments) and the challenges in `docs/research/08-tech-challenge.md`. D-014 (owner delegates, produce without date pressure) and D-015 (browser in a separate AkashicWebHost process on official Electron 44.x; JCEF/OBS fallback). Electron host and natives probes added to `spikes/`.

### Added (2026-10-12, code)
- Mod skeleton on the GTNH buildscript (Forge 1.7.10, Jabel `--release 8`, JUnit 5): `AkashicWeb` `@Mod`, `CommonProxy`/`ClientProxy`, `protocol.Protocol` (major 1), `client.host.HostProtocol` (AKW1 framing, FRAME header validation) with tests.
- `docs/PROTOCOL.md` (game↔host IPC v1 and server↔client packets) and `docs/CONTRACTS.md` (module ownership and interfaces).
- `host/`: AkashicWebHost package with the JS protocol codec and `node --test` tests.
- Contract types in Java for parallel module work (`client.host`, `client.install`, `common`, `common.net`), documented in `docs/CONTRACTS.md`.
- Build evidence (Linux container): `./gradlew build` green, 4 JUnit + 3 node tests pass, class major 52. Not run: Windows, Minecraft client, Crucible server.

### Added (2026-10-13, wave 1 of the implementation)
- Five modules built in parallel against `docs/CONTRACTS.md` and merged: AkashicWebHost app (`host/`, M1), host connection (`client.host`, M2), engine installer (`client.install`, M3), screen block, rights, network and commands (`common`, `server`, M4), client screens, rendering, input and sync (`client.*`, M5).
- Integration fixes: one `ReasonCode` list (18 codes proposed by M1/M2/M3/M5 merged), every lang key in `en_US.lang` and `pt_BR.lang` (140 each), the client geometry now uses the server's `BlockSide` (the ceiling layout was mirrored), and the client destroys its browser ids when the host goes down so the host connection does not replay stale screens.
- Evidence (Linux container only): `./gradlew build` green with 440 JUnit tests (2 env-gated skips); `node --test` 123 unit + 29 Electron 44.7.0 integration tests pass under Xvfb; all 321 classes in the jar are major 52; no natives in the jar. Not run: Windows, a Minecraft client, the Crucible server. Reviews of M1 and M2 found defects that wave 2 fixes; the M3, M4 and M5 reviews did not run (usage limit) and are part of wave 2.

### Tested (2026-10-13)
- Crucible boot with the mod and a first Linux dev-client run against it: the screen forms and draws, the address bar opens, `/web info` is right; three bugs found (placeholder text format, owner rights compared by client UUID, Crucible permission node naming). See `docs/evidence/2026-10-13-crucible-boot-and-dev-client.md`.

### Changed (2026-10-13, wave 2)
- All five modules fixed after adversarial reviews and merged (`f8e1487`): the host's internal-address check on every request and a document-start preload (M1); thread-death handling, stall detection and a client-side URL check (M2); the TLS trust order with pinned roots, a consent record and per-file pins (M3); D-016 on the server, the controller lock, friend names without Mojang lookups, the Crucible permission nodes, a fail-closed config and screen protection (M4); the client wiring of the installer and host, D-016 on the client, the live rule and the consent flow (M5).
- Evidence: 644 JUnit tests (Linux). **First browser on an in-world screen**: real pages, scrolling, keyboard navigation, H.264 video, and two players in sync (p50 11 ms / p95 32 ms on one machine). See `docs/evidence/2026-10-13-first-browser-in-game.md`. Not run: Windows, the Forbidden pack, YouTube playback (the container's IP is bot-walled).

### Changed (2026-10-13, W3-0 integration follow-ups)
- The host supervisor reads the host's exit code: 65 (forbidden switch) disables with `HOST_FORBIDDEN_SWITCH`, 64 (bad argument) disables with `HOST_LAUNCH_FAILED`, 67 is `HOST_CONNECT_FAILED`, 66 `HOST_HANDSHAKE`, 68/69 `HOST_PROTOCOL_ERROR` (68 before WELCOME: `HOST_HANDSHAKE`); it waits at most 500 ms on its own thread for the code of a host whose connection dropped. Evidence: BUILD / AUTOMATED (Linux), `HostSupervisorTest` with fake hosts.

No release yet.
