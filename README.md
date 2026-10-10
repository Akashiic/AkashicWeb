# AkashicWeb

A real web browser on in-world screens for **Minecraft 1.7.10** (Forge), built on the GT New Horizons stack (GTNHLib, UniMixins; compatible with Hodgepodge, Angelica and lwjgl3ify). It is a from-scratch mod with the idea of [Web Displays](https://github.com/montoyo/webdisplays) by montoyo, the same way [AkashicFM](https://github.com/Akashiic/AkashicFM) is a rewrite inspired by OpenFM. Web Displays is public domain; pieces reused from it keep montoyo's credit.

Players build screens of any size, click, scroll, type and browse: YouTube (including lives), Twitch, MP4 and ordinary sites, with everyone near a screen seeing the same page. Sound comes from the screen, and the goal is full 3D audio through the AkashicFM engine.

> Resumo em português: [`docs/PT-BR_RESUMO.md`](docs/PT-BR_RESUMO.md).

## Status

**Research complete, no code yet** (2026-10-10). Nothing has been built for Minecraft, tested on Windows, or observed in game. First target: a working spawn telão for the Brazilian presidential runoff coverage on 2026-10-25.

## What changes compared with Web Displays

| Problem in Web Displays | AkashicWeb |
|---|---|
| 1.7.10 version: Chromium from 2015, Windows-only, no public source; 1.12.2+ forks on Chromium 116 | Chromium 150 with H.264/AAC (OBS's codec-enabled CEF) on CCBlueX java-cef, Java 8 bytecode |
| Silent white/black screens (19% of 590 issues) | Init state machine with reason codes shown on the screen in Portuguese and English, `/web doctor` |
| Fragile native downloads, CWD-relative paths, pinned expired certificates | Installer with a pinned manifest, SHA-256 before extraction, resume, mirrors, `%LOCALAPPDATA%` on Windows |
| Each player on a different page; no video sync ("cannot be fixed") | Server-authoritative URL and a shared video clock |
| Audio straight to the OS device, not positional | Distance volume tied to Minecraft sliders; 3D audio with occlusion and reverb through AkashicFM |
| Anyone can change any screen's URL | Owner / friends / visitors rights with safe defaults; admin lock, clear, kill switch; audit log |
| Screens can leak players' IPs or reach their LAN | Site allowlist/blocklist and internal-address blocking on server and client |
| Orphan `jcef_helper` processes | Job Object, renamed helper, deterministic shutdown |
| Broken Portuguese typing (`$ % &` become arrows) | ABNT2 with dead keys and paste |

## Documentation

- [`docs/PROJECT_STATE.md`](docs/PROJECT_STATE.md): what exists, what was run, what was not
- [`docs/PLAN.md`](docs/PLAN.md): architecture, scope, schedule, gates, test matrix
- [`docs/DECISIONS.md`](docs/DECISIONS.md): owner and technical decisions, pending questions
- [`docs/PROBLEM_FIX_MATRIX.md`](docs/PROBLEM_FIX_MATRIX.md): every known problem and its fix
- [`docs/ISSUES_REGISTER.md`](docs/ISSUES_REGISTER.md): risks and open items
- [`docs/HANDOFF.md`](docs/HANDOFF.md): where the next session starts
- [`docs/research/`](docs/research/): full research with sources
- [`spikes/`](spikes/): probes used in the research

## Credits

Web Displays by montoyo (BARBOTIN Nicolas), public domain. Ideas from the CinemaMod/Mysticpasta1 Web Displays fork (MIT) and CinemaMod MCEF (LGPL-2.1). Chromium Embedded Framework and java-cef (BSD); CCBlueX's java-cef fork (BSD). OBS Studio hosts the codec-enabled CEF builds referenced here.

Licence of this repository: to be decided (see `docs/DECISIONS.md`, P-08).
