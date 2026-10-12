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

No code, build or release yet.
