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

No release yet.
