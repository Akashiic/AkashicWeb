# Forbidden Series: client pack and server inventory

> Taken 2026-10-12 from the owner's files (VERIFIED by reading archive listings and a few small files). Client: Technic pack release `TEOF-3.6` of `Akashiic/Technic-Forbidden` (`TEOF-3.6.zip`, 855,203,279 bytes, sha256 `c8db4a52…aa44`), read through its zip index without extracting. Server: the owner's server composition archive of 2026-10-11 (882,137,142 bytes, sha256 `cfafd12a…d988`), kept only in the research container. **Nothing from the server archive is committed**: no configs, no credentials, no host or port. Only names and versions that matter for AkashicWeb are listed.

## Client (Technic launcher)

- Minecraft 1.7.10, Forge 10.13.4.1614 (`bin/version.json`, main class `net.minecraft.launchwrapper.Launch`, FMLTweaker).
- **LWJGL2** natives in `bin/natives` (`lwjgl64.dll`, `OpenAL64.dll`; old Twitch SDK DLLs too). No lwjgl3ify jar in `mods/`, although a `config/lwjgl3ify.cfg` exists (leftover or other launchers; to confirm). Java version: Technic's runtime, Java 8 expected (to confirm with players).
- JVM args added by the pack: only `-XX:CompileCommand=exclude,...` entries for Forestry/ThePatcher.
- 208 mod files. The ones that matter for AkashicWeb:

| Library | Version in the pack | Use for AkashicWeb |
|---|---|---|
| UniMixins (all) | 0.3.1 (`+unimixins-all-1.7.10-0.3.1.jar`) | Mixins available (hard dependency acceptable) |
| GTNHLib | 0.11.51 | Config with GUI, utilities (AkashicFM already needs it) |
| ModularUI2 | 2.3.85 | Modern GUI framework for the address bar, rights editor, config screens |
| Angelica | **2.2.20** (2.x line) | Has `TileEntityRenderBoundsRegistry.registerDynamicClass` (VERIFIED in the 2.2.20 tag) and a TESR mesh/material API (`com.gtnewhorizons.angelica.api.tesr`, `@API provides "angelica|tesr"`, apiVersion 2) |
| Hodgepodge | 2.7.189 | Compatibility target |
| ArchaicFix | 0.8.0 | Compatibility target (client only) |
| FalsePatternLib / ChunkAPI / EndlessIDs | 1.12.2 / 0.8.4 / 1.7.4 | Present; block/item IDs extended |
| CoreTweaks, FoamFix, entityculling, FpsReducer | 0.3.4.7-GTNH, 1.0.4, 1.6.4, 1.10.3 | entityculling and Angelica culling can hide a large screen |
| Waila, NEI, OpenComputers, Baubles Expanded | 1.19.31, 2.8.125-GTNH, 1.12.61-GTNH, 2.2.22 | Optional integrations |
| AkashicFM | build `fa54626` (dirty) | 3D audio engine (D-010) |
| Akashic Voice | present | Echo risk at spawn (AW-20) |
| Akashic mods | ThePatcher, Player Backport, CinematicMenu, many fixes | Must coexist |

- **Shader packs shipped:** BSL v8.2.09 and Complementary Unbound r5.9.3 (Angelica shaders). The screen must be tested with both.
- **Angelica options shipped** (`config/angelica-options.json`): `section_gated_tesr_culling: true`, `use_occlusion_culling: true`, `async_occlusion_mode: EVERYTHING`, `use_entity_culling: true`, `use_no_error_g_l_context: true` (GL errors are undefined behaviour: uploads must be correct), `streaming_upload_strategy: MAP_BUFFER_RANGE`.
- No StructureLib, no OptiFine jar.
- Android players use the separate ForbiddenMobile launcher (LWJGL3 runtime): placeholder only.

## Server

- **Crucible** `1.7.10-staging-67b53034` (Thermos-legacy manifest), launched through **RetroFuturaBootstrap 1.1.0** on **Java 21** with `java9args.txt` (`--add-opens` for java.base/java.desktop, `-Dcrucible.weAreJava9=true`, `-Djava.security.manager=allow`), G1GC, 14 GiB heap, two Akashic Java agents (RGB transport, nChat).
- `online-mode=false` (behind a proxy with BungeeGuard), `max-players=100`, `view-distance=10`, `spawn-protection=16`, whitelist on.
- Server mods (198 files): same core libraries as the client (UniMixins 0.3.1, GTNHLib 0.11.51, ModularUI2 2.3.85, Hodgepodge 2.7.189, AkashicFM, Akashic Voice) plus **lwjgl3ify 3.0.31**, spark, EventHelper, BukkitBridge, and server-only Akashic guards including **Akashic-World-Firewall** (audits the pack's mods; AkashicWeb's server classes must be declared to it) and a Crucible ChunkAPI bridge.
- Bukkit plugins that matter:

| Plugin | Why it matters |
|---|---|
| LuckPerms (Bukkit Legacy 5.5.77) + Vault | Admin and player permissions for screens: check `akashicweb.*` nodes through Bukkit `Player.hasPermission` by reflection |
| WorldGuard 6.2, GriefPreventionPlus 13.9, PlotMe | Who may place or use screens in protected areas; our packets bypass their events, so our server must ask them (or fire Bukkit events) |
| FinalForgeRestrictor 2.0.4 | Restricts Forge blocks/items by region; the screen block and keyboard may need entries |
| BlockLimiter 2.1.1 | May cap screen blocks per chunk or player |
| ProtocolLib, ProtocolObelisk (Crucible) | Packet tooling; check our custom payload channel passes untouched |
| Multiverse-Core, PerWorldInventory | Several worlds: screen state per dimension/world |
| ClearLagg (Crucible) | Make sure it never touches our tile entities |
| Citizens, HolographicDisplays, TAB, DiscordSRV, Skript | Coexistence only |

## Consequences for the plan

1. Hard dependencies are acceptable for **UniMixins 0.3.1, GTNHLib 0.11.51 and ModularUI2 2.3.85** (present on both sides). Compile against those exact versions or older APIs.
2. Rendering must be Angelica **2.x** aware: register the screen TE as dynamic bounds, survive section-gated TESR culling and occlusion culling, and consider Angelica's TESR material API for a full-bright, shader-friendly screen.
3. The client is LWJGL2 on Java 8 by default; lwjgl3ify paths matter for the server and possibly other launchers only.
4. Server-side permissions come from LuckPerms via Bukkit; protection plugins must be consulted before accepting screen actions.
5. The Akashic-World-Firewall must know about AkashicWeb before deployment.
