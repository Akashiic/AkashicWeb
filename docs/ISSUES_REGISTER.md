# Issues and risk register

No GitHub issues exist yet. Create them from this table when the owner agrees (one issue per row; label `risk` or `task`). Keep this file, [`PROJECT_STATE.md`](PROJECT_STATE.md) and [`PLAN.md`](PLAN.md) in step.

| ID | Type | Title | Likelihood / impact | Mitigation | Owner of next step | State |
|---|---|---|---|---|---|---|
| AW-01 | Risk | OBS CEF 150 + CCBlueX libjcef fails to build or run on Windows (only Linux tested) | Medium / High | Spotify-152 build in parallel (no lives); CinemaMod 116 emergency; decided at G1 (10-13) | Build chat | Open |
| AW-02 | Decision | Server/pack and version not confirmed (GTNH vs Forbidden Series) | — / High | Answered: Forbidden Series on Crucible (D-009) | Owner | Closed |
| AW-25 | Risk | The Forbidden pack's mod list is unknown (GTNHLib, UniMixins, Angelica, Hodgepodge, OptiFine presence) | — / High | P-11: owner sends the `mods/` listing; until then only soft dependencies | Owner | Open |
| AW-26 | Risk | Crucible (Bukkit hybrid) server: permissions, plugins, akashic-world-firewall guard, Velocity transfers | Medium / Medium | Test the server side on Crucible Java 21; Bukkit permissions by reflection | Build | Open |
| AW-27 | Risk | Android (ForbiddenMobile) players cannot run CEF | Certain / Low | Placeholder on Android; never crash | Build | Accepted |
| AW-03 | Decision | Codec build acceptance and distribution model | — / High | Accepted (D-011); Windows downloads from OBS's CDN | Owner | Closed |
| AW-04 | Risk | Scope (interactive browser) exceeds one developer in 15 days | High / High | Cut order in PLAN 6; buffer days 10-19 and 10-23 | Owner + build chat | Open |
| AW-05 | Risk | In-process libcef crash takes down clients at spawn; crash loop on join | Medium / High | Crash guard, delayed creation, server kill switch, Tier-1 only; host process after the event | Build chat | Open |
| AW-06 | Risk | Angelica (beta66b or 2.x core profile) culls the screen or corrupts GL state | Medium / High | Draw-only TESR, unpack reset, reflective bounds registry, G3 with shaders | Build chat | Open |
| AW-07 | Risk | Players do not install the ~415 MB natives before 10-25 | Medium / High | Pre-install release on 10-16, announcements 10-15/10-20/10-24, placeholder for others | Owner | Open |
| AW-08 | Risk | Mod required in the pack: players who did not update cannot join | Medium / High | Early announcement, install deadline 10-23, support channel | Owner | Open |
| AW-09 | Risk | IP-logger links or LAN addresses on public screens | Medium / High | Site policy (allowlist/blocklist), UrlPolicy internal block both sides, AuditLog | Build chat | Open |
| AW-10 | Risk | Passwords typed on a shared screen are relayed to all nearby clients | Medium / Medium | No relay while a password field is focused; warning | Build chat | Open |
| AW-11 | Risk | Video desync between players larger than expected (each client buffers its own stream) | Medium / Medium | Shared video clock; measure at G4; optional relay later | Build chat | Open |
| AW-12 | Risk | 3D audio not stable by 10-21 | Medium / Low | Ship distance volume; 3D audio as a mod update (native hooks already in the first build) | Build chat | Open |
| AW-13 | Risk | AkashicFM needs an API change (ExternalAudioSources, non-blocking ring, timed feed) | Certain / Medium | Separate AkashicFM release; soft dependency | Build chat | Open |
| AW-14 | Risk | Windows paths: OneDrive Documents, Controlled Folder Access, accented usernames | Medium / Medium | LOCALAPPDATA install, ASCII-safe fallback, P1 cells | Build chat | Open |
| AW-15 | Risk | Launcher JREs (CurseForge 8u51) fail HTTPS; 32-bit Java | Medium / Medium | Extra ISRG roots, manual zip, UNSUPPORTED_ARCH | Build chat | Open |
| AW-16 | Risk | Antivirus flags the unsigned helper or slows first load | Medium / Medium | pt-BR whitelist guide, hashes, AV-specific reason code; signing later | Owner + build chat | Open |
| AW-17 | Risk | Linux natives need a too-new glibc/libstdc++ | Medium / Low | Old build container, objdump CI check | Build chat | Open |
| AW-18 | Risk | macOS cannot run CEF under lwjgl3ify | High / Low | Placeholder for 10-25 | — | Accepted |
| AW-19 | Risk | Rights: Content ID on the owner's restream of broadcaster coverage | High / Medium | Mute/blur/switch in OBS; allowlisting by the rights holder | Owner | Open |
| AW-20 | Risk | Telão audio feeds Akashic Voice mics (echo at spawn) | Medium / Low | Lower default volume, "use fones" notice, optional duck | Build chat | Open |
| AW-21 | Risk | Codec patent exposure (H.264/AAC FFmpeg in CEF) | Low-Medium / Medium | No Akashic mirror on Windows; legal opinion; codec-free fallback documented | Owner | Open |
| AW-22 | Risk | YouTube changes (bot checks, embed restrictions, old-browser warnings) | Medium / Medium | Current Chromium, stock UA, two allowlisted channels, TSE page as safety net | Build chat | Open |
| AW-23 | Ops | Election-night single points of failure (one operator, server restarts, TPS at spawn) | Medium / High | Roles with deputies, no restarts 14:00-01:00, spawn chunk preload, printed runbook | Owner | Open |
