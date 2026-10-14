# R4: the rest of the modern GTNH stack, judged for AkashicWeb

Date: 2026-10-13 (UTC). Research stream R4 for wave 3 (owner directive D-017). I worked read-only on `/home/user/akashicweb`, whose HEAD is `83585d3` ("Document the wave-2 server protocol (D-016)"). All clones and downloads are under `scratchpad/wave3/`.

**Grades.**
- **VERIFIED:** I read the code or doc (cited `file:line`) or ran the thing myself. The run kind is marked: AUTOMATED (Linux container) or HTTP check.
- **INFERRED:** reasoning, an estimate, or a design proposal. Every "recommend" or "should" is INFERRED until an ADR accepts it.

Nothing here ran inside Minecraft, on Windows, with Angelica and shaders, or on Crucible with new code. Nothing here is LIVE-OBSERVED.

**Path shorthands**

| Tag | Source | Revision |
|---|---|---|
| `GL:` | GTNHLib `scratchpad/wave3/src/gtnhlib-0.11.51/src/main/java/com/gtnewhorizon/gtnhlib/` (resources: `.../src/main/resources/`) | tag `0.11.51`, `8c3c2df9fe`, 2026-09-20 |
| `ANG:` | Angelica `scratchpad/wave3/src/angelica/src/main/java/`; mixins are `ANGM:` = `.../src/mixin/java/com/gtnewhorizons/angelica/mixins/early/` | tag `2.2.20`, `1babfd968c` (the pack's version) |
| `UM:` | UniMixins `scratchpad/wave3/src/unimixins/module-gtnhmixins/src/main/java/com/gtnewhorizon/gtnhmixins/` | tag `0.3.1`, `c47090bcae` |
| `GG:` | GTNHGradle `scratchpad/wave3/src/gtnhgradle-2.0.33/src/main/java/com/gtnewhorizons/gtnhgradle/` | tag `2.0.33`, `15c83faa7b` (the version AkashicWeb's settings plugin uses) |
| `HP:` | Hodgepodge `scratchpad/wave3/src/hodgepodge/src/main/java/com/mitchej123/hodgepodge/` | tag `2.7.189`, `b9a655dae5` |
| `EC:` | EntityCulling `scratchpad/wave3/src/entityculling-1.6.4-1.7.10/src/main/java/dev/tr7zw/entityculling/` | tag `1.6.4-1.7.10`, `d226a35031` (matches the pack jar name `entityculling-1.6.4-mc1.7.10`; not byte-compared, INFERRED) |
| `CT:` | CoreTweaks `scratchpad/wave3/src/coretweaks-0.3.4.7/src/main/java/makamys/coretweaks/` | tag `0.3.4.7-GTNH`, `71f2329a6f` |
| `CR:` | Crucible `scratchpad/wave3/src/crucible/patches/` | tag `staging-67b5303` (`67b530340b`), the owner's build, read with `git show` |
| `MUI:` | ModularUI2 `scratchpad/wave3/src/mui2-2.3.85/src/main/java/com/cleanroommc/modularui/` | tag `2.3.85-1.7.10`, `f9cfb98389` |
| `MC:` | Forge 10.13.4.1614 decompiled sources, `/home/user/akashicweb/build/rfg/minecraft-src/java/` | RFG output |
| `AFM:` | AkashicFM `/home/user/AkashicFM/` | `fa54626496` |
| `AW:` | AkashicWeb `/home/user/akashicweb/src/main/java/com/akashiic/web/` | `83585d3` |
| `WD:` | Web Displays `/home/user/montoyo/webdisplays/src/main/java/net/montoyo/wd/` | — |
| `L3:` | lwjgl3ify `/home/user/gtnewhorizons/lwjgl3ify/` | `3.0.37` (`c87172bedb`). The server runs 3.0.31, which I did not read. |
| `BOOT:` | the 2026-10-13 Crucible boot logs, `scratchpad/crucible-boot/` | — |

**What I ran**
- **HTTP HEAD checks on the GTNH Maven:** every artifact named in §1.5 returns 200. HTTP check, 2026-10-13.
- **Angelica API jar.** I downloaded `Angelica-2.2.20-api.jar` (SHA-256 `749b97eee158cb5c72e621ec0e4183e23fd746c2facbf2df431c685cdc8c3f16`), listed it and read the class versions. AUTOMATED.
- **FML version ranges.** A 10-line Java program ran FML's `VersionParser` from `build/rfg/recompiled_minecraft-1.7.10.jar` against the dependency ranges proposed in §1.5. AUTOMATED (Linux, JDK 21).

R1 (`r1-mui2.md`) already covered ModularUI2 itself. Where this report touches MUI2, it agrees with R1: MUI2 hard-requires GTNHLib and gtnhmixins, and R1 recommends `forceEnableMixins = true`.

---

## 0. Verdict at a glance

| # | Question | Recommendation (INFERRED) | Key evidence |
|---|---|---|---|
| 1 | GTNHLib as a dependency | **Adopt as a declared hard dependency** (`required-after:gtnhlib@[0.11.51,)`). It is already transitive: MUI2 2.3.85 requires `gtnhlib@[0.2.0,)` and `gtnhmixins@[2.0.1,)`. | VERIFIED `MUI:ModularUI.java:21-22` |
| 2 | Move `common/WebConfig` to GTNHLib `@Config` | **No.** Keep the fail-closed Forge wrapper for server policy, kill switch and admins. | VERIFIED: GTNHLib's `registerConfig` uses Forge's `Configuration` constructor, which renames a bad file to `.errored` and loads defaults (`GL:config/ConfigurationManager.java:83-84`, `MC:net/minecraftforge/common/config/Configuration.java:107-121`). That is the exact regression the wave-2 review fixed (`AW:common/WebConfig.java` class comment). |
| 3 | GTNHLib `@Config` for **client preferences** | **Yes.** New file `config/akashicweb-client.cfg`, registered only in `ClientProxy.preInit`, with the in-game config GUI (`SimpleGuiConfig` + `SimpleGuiFactory`), the AkashicFM house style. **Use a separate file**, or GTNHLib's category culling deletes WebConfig's categories. | VERIFIED `GL:config/Config.java:37-49`, `ConfigurationManager.java` `cullDeadCategories`; `AFM:client/gui/FmConfigGui.java`, `FmGuiFactory.java` |
| 4 | GTNHLib keybinds, networking, scheduler, Brigadier, EventBusSubscriber | **No.** | `SyncedKeybind` sends key state to the server (against D-016). `IPacket` throws on decode and relies on a mixin-driven queue. On Crucible, Brigadier commands all share one Bukkit permission node. Details in §1.3. |
| 5 | Our own mixins | **None** (`usesMixins=false`, keep ADR-009's jar checks). Set `forceEnableMixins=true` for the dev run only (R1). | §2 |
| 6 | Netty-arrival clock stamps | **Yes, but without a mixin:** a Netty `ChannelHandler` inserted before `packet_handler` through `FMLNetworkEvent`, plus a `serverRecvMs` field appended to `S2CClockPong`. AkashicFM's `MixinNetworkManager` is the fallback. **This also fixes a wrong thread assumption in the current client code (§5.1).** | VERIFIED `MC:net/minecraft/network/NetworkManager.java` `channelRead0` (queues unless `hasPriority`), `MC:cpw/.../NetworkDispatcher.java:124,181,189,288-293`, `NetworkManager.java:382` |
| 7 | Angelica TESR material API (`angelica|tesr` v2) | **Adopt, behind a presence check.** Draw the page quad and the in-world cursor with `TesrMeshCache.renderCached`, using a material copied from Angelica's own beacon beam: `lightmap(240,240).unlit().noCull().special(BEACON_BEAM)`. Under shaders this routes the draw to `gbuffers_beaconbeam`. Keep the current Tessellator path as the fallback, with a client option to flip between them at G3. | VERIFIED `ANG:com/gtnewhorizons/angelica/rendering/tesr/BeaconBeamMeshes.java:18-24`, `ANG:net/coderbot/iris/pipeline/DeferredWorldRenderingPipeline.java:869-870,488` |
| 8 | `TileEntityRenderBoundsRegistry` | **Keep as is** (reflection in client preInit plus the pack-config belt). It is not in the API jar. | VERIFIED §3.8 |
| 9 | EntityCulling 1.6.4 | **It will cull a large screen whenever the origin block is occluded:** it tests a 1×1×1 box at the TE position. Fix on the mod side by overriding `isForcedVisible()` in `TileEntityScreen`. Owner belt: `"tile.akashicweb.screen"` in `entityculling.json`. | VERIFIED `EC:CullTask.java:68-81`, `EC:mixin/CullableMixin.java`, `EC:mixin/BlockEntityRenderDispatcherMixin.java` |
| 10 | FPS reducers | **Pack config, not code.** FpsReducer 1.10.3: owner raises or disables the idle reduction. Angelica's built-in reducer is off by default. Our GUIs must keep input flowing through `GuiScreen.handleInput`. | §4 |
| 11 | Angelica HUD caching | **Draw nothing per-frame on the HUD.** The cursor goes in world space (TESR). | VERIFIED `ANG:com/gtnewhorizons/angelica/config/AngelicaConfig.java:101-117`, `ANG:.../hudcaching/HUDCaching.java:98-150` |
| 12 | Hodgepodge volume curve | **Mirror it.** Hodgepodge squares the master and category volumes by default; `GainModel` is linear. | VERIFIED `HP:config/FixesConfig.java:116-118`, `HP:mixins/early/minecraft/MixinSoundManager.java`; `AW:client/screen/GainModel.java` `screenGain` |

---

## 1. GTNHLib 0.11.51

### 1.1 Facts that matter

- **Mod identity:** modid `gtnhlib`, `acceptableRemoteVersions = "[0.0.13,)"` (VERIFIED `GL:GTNHLib.java:15-20`). GTNHLib is a coremod (`coreModClass = core.GTNHLibCore`) and uses mixins (`usesMixins = true`) (VERIFIED `gtnhlib-0.11.51/gradle.properties`).
- **Libraries fetched at first launch.** FalsePatternLib's dependency loader downloads `fastutil 8.5.18`, `joml 1.10.8` and `brigadier 1.0.18` from Maven Central or libraries.minecraft.net (VERIFIED `GL resources: META-INF/gtnhlib_deps.json`). Below Java 17 it also downloads `jvmdowngrader-java-api` (`gtnhlib_deps8.json`). The Crucible boot shows it doing this (VERIFIED `BOOT:boot3.log:17,21`). This is not our concern (the pack already has GTNHLib), but our compile-time use of fastutil or Brigadier would depend on that download.
- **Launch requirement:** GTNHLib needs UniMixins 0.1.23 or newer (MixinExtras Expressions), checked at coremod construction (VERIFIED `GL:core/GTNHLibCore.java` `verifyDependencies`). The pack has 0.3.1.
- **Hybrid servers:** GTNHLib detects Bukkit/Thermos and adjusts LaunchClassLoader exceptions (VERIFIED `GL:core/GTNHLibCore.java:80-84`).
- **On Crucible** (owner's server.jar, Java 21, RFB) GTNHLib 0.11.51 booted with one harmless mixin warning for a client target (`RenderBlocks` on the server) (VERIFIED `BOOT:boot3.log:70-71`).

### 1.2 Config API, in detail

**Annotations** (VERIFIED `GL:config/Config.java`):

| Annotation | Line | Purpose |
|---|---|---|
| `@Config(modid, category="general", configSubDirectory, filename, categoryCulling=true)` | 14-49 | Marks a config class |
| `@Comment`, `@Default{Boolean,Int,Float,Double,String,Enum,StringList,IntList,DoubleList}`, `@Range{Int,Float,Double}`, `@Pattern`, `@Name` | — | Field values, limits and naming |
| `@LangKey`, `@LangKeyPattern` (default `%mod.%cat.%field`) | 53-84 | Lang keys; `-Dgtnhlib.printkeys=true` / `-Dgtnhlib.dumpkeys=true` print them |
| `@RequiresMcRestart`, `@RequiresWorldRestart` | 203, 207 | Restart flags |
| `@ExcludeFromAutoGui` | 242 | Hides a class from the auto GUI |
| `@Sync` | 244-259 | Server → client sync |
| `@Entry` | 267 | Custom GUI entry |
| `@Order` | 274 | Ordering |
| `@RequiresMod` | 290 | Hides a field when a mod is absent |
| `@Reloadable(id)` | 304-329 | Fields re-read by `reloadConfig` |

**Registration.** `ConfigurationManager.registerConfig(Class)` must run "before or during preInit". It builds a Forge `Configuration` per (modid, subdir, filename), loads it, processes the fields and saves (VERIFIED `GL:config/ConfigurationManager.java:66-99`). Because it uses Forge's constructor, **a file that does not parse is renamed `*.errored` and replaced by defaults** (VERIFIED `MC:.../Configuration.java:107-121`).

**Reload.** `reloadConfig(Class, id)` re-reads the file and re-applies only the fields marked `@Reloadable(id)`. Without the mark it silently does nothing (VERIFIED `ConfigurationManager.java:121-135`). AkashicFM hit this and added a test that every server field carries the mark (VERIFIED `AFM:docs/FASE7-RESULTADOS.md:31`).

**Sync.** `@Sync` fields are sent once, on `PlayerLoggedInEvent`, keyed by `Field.toString()`, and restored on disconnect (VERIFIED `GL:config/ConfigSyncHandler.java:26-44`, `PacketSyncConfig.java`). Nothing re-sends after a server-side reload.

**GUI.**
- `SimpleGuiConfig(parent, modid, modName)` builds a Forge `GuiConfig` from every class registered for the modid (VERIFIED `GL:config/SimpleGuiConfig.java`). GUI edits write straight back to the static fields (VERIFIED `GL:config/IConfigElementProxy.java:172-180`).
- With no `guiFactory`, GTNHLib's `MixinGuiModList` creates the GUI anyway (mixin `AUTO_CONFIG_GUI`) (VERIFIED `GL:mixins/early/fml/MixinGuiModList.java`, `GL:mixins/Mixins.java:16`).

**Category culling.** On by default: categories that GTNHLib did not "observe" are deleted from the file (VERIFIED `Config.java:37-49`; `ConfigurationManager.java` `cullDeadCategories`). **Consequence:** GTNHLib config classes must never share `akashicweb.cfg` with WebConfig's hand-made categories.

### 1.3 The other GTNHLib modules, judged

| Module | What it is (VERIFIED) | Verdict for AkashicWeb (INFERRED) |
|---|---|---|
| `keybind/SyncedKeybind` | "Server-backed keybindings": the client sends key state to the server in `PacketKeyDown` (`GL:keybind/SyncedKeybind.java:30,131,214`). The package has nothing else. | **No.** Page keys must never reach the server (D-016). Our 2-3 binds stay plain `ClientRegistry.registerKeyBinding` with `KEY_NONE` defaults, as in AkashicFM `MuteKeys`. |
| `network/base/NetworkChannel` + `IPacket` | Thin wrapper over `SimpleNetworkWrapper`. A decode failure throws `RuntimeException("Failed to decode packet …")` (`GL:network/base/IPacket.java:54-58`). Server handlers hop through `ServerThreadUtil.addScheduledTask` (`:45`). | **No.** Our `AkwMessage` never throws into FML, marks malformed packets and counts them (VERIFIED `AW:common/net/AkwMessage.java`). We also have rate limits and a per-player queue (PROTOCOL.md:86). |
| `util/ServerThreadUtil` | Its queue is drained by GTNHLib's own `MixinMinecraftServer` (mixin `BACKPORT_SERVER_TICKING`) (`GL:util/ServerThreadUtil.java:39,113`; `GL:mixins/early/MixinMinecraftServer.java`; `Mixins.java:20`). | **No.** AkashicFM rejected it for that reason: if the mixin does not apply, the queue never runs (`AFM:docs/FASE7-RESULTADOS.md:28`). Keep `ServerActionQueue` and `ClientTaskQueue`. |
| `brigadier/*` | A dispatcher plus one `BrigadierCommandWrapper` ICommand per root literal (`GL:CommonProxy.java:136-137`). | **No.** Crucible gives an ICommand a Bukkit permission node equal to its class name (VERIFIED `CR:net/minecraft/command/CommandHandler.java.patch:32-54`). Every Brigadier command therefore shares the node `com.gtnewhorizon.gtnhlib.brigadier.BrigadierCommandWrapper` (VERIFIED `BOOT:boot3.log:332-334`). LuckPerms could not grant `/web` without also granting `/gtnhteam`, `/gtnhteam_admin` and the rest (P-13). Keep `CommandBase` with the Crucible group fix. |
| `eventbus/@EventBusSubscriber` | Static subscribers auto-registered through GTNHLib's ASM transformer and mixins (`GL:eventbus/EventBusSubscriber.java`). | **No (style only).** Explicit registration in `ClientProxy` is clearer and is what the tests already wire. |
| `client/renderer/vertex/DefaultVertexFormat` | Vertex formats, `POSITION_TEXTURE` at `:65` (`GL:client/renderer/vertex/DefaultVertexFormat.java`). | **Yes, compile-time only.** Angelica's `TesrMeshSink.angelica$bucket(VertexFormat, …)` takes it (§3). |
| `client/opengl/GLCaps` | `bufferStorageSupported()`, `texStorageSupported()`, `fboSupported()` (`:19-27`). | **Optional.** Use it if ADR-004's persistent-PBO upgrade is ever built. |
| `util/AboveHotbarHUD` | `renderTextAboveHotbar(message, duration, shadow, fadeIn)` (`:34`). | **Optional** for "You control this screen: Esc to release" hints. It is HUD text, so Angelica caches it at 20 FPS (§4.2), which is fine for text. |
| Font replacement (`FONT_RENDERER` mixin, on by default) | `GL:GTNHLibConfig.java:12-14`; `Mixins.java` `FONT_RENDERER` | **Note.** Our TESR placeholder text goes through whatever `FontRenderer` the pack installed. Nothing to do. |
| `integration/mui2/ClientTextField` | A one-line, client-only MUI2 text field used by GTNHLib's teams GUI. | Not public API. Use MUI2's own `TextFieldWidget` (R1). |

### 1.4 AkashicFM house style (VERIFIED, `AFM:`)

- **Dependency:** `api("com.github.GTNewHorizons:GTNHLib:0.11.52:dev")` (`dependencies.gradle`). The `@Mod` declares `dependencies = "required-after:gtnhlib@[" + MIN_GTNHLIB + ",)"` with `MIN_GTNHLIB = "0.5.23"` and `guiFactory = "…FmGuiFactory"` (`AkashicFM.java:15-35`). `mcmod.info` sets `useDependencyInformation: false`, so FML honours the annotation's range. `ModDependencyTest` parses the range the way FML does (`src/test/.../ModDependencyTest.java`; finding in `docs/FASE7-RESULTADOS.md`).
- **Config classes:** `FmConfig` has one nested `@Config(modid, category)` class per category, with `@Comment`, `@Default*`, `@Range*`, and `@Reloadable("server")` on server fields (`common/FmConfig.java`).
- **Registration:** in `CommonProxy.preInit`, wrapped in `try/catch (ConfigException)` that rethrows (`CommonProxy.java:47-61`).
- **GUI:** `FmConfigGui extends SimpleGuiConfig`, and `FmGuiFactory implements SimpleGuiFactory` (`client/gui/`).
- **Writes from code:** a client value toggled by a key is saved with `ConfigurationManager.save(FmConfig.Client.class)` (`client/MuteKeys.java:61`).
- **`/fm reload`:** goes through `reloadConfig`, with a reflective fallback below 0.9.62 (`server/ConfigReload.java`).
- **GTNHLib modules not used:** networking, keybinds, eventbus, Brigadier and `ServerThreadUtil`.
- **Mixins:** `usesMixins=true`, `mixinsPackage=mixins` and a hand-written `mixins.akashicfm.json` (`"required": false`) with two `remap=false` mixins (Netty clock stamps, OpenAL lifecycle) (`src/main/resources/mixins.akashicfm.json`; `mixins/`).

### 1.5 Coordinates, `@Mod` string, build properties

**GTNH Maven** (`https://nexus.gtnewhorizons.com/repository/public/`, HTTP HEAD 200 on 2026-10-13 for each):

| Artifact | Use |
|---|---|
| `com.github.GTNewHorizons:GTNHLib:0.11.51` (pom, `:dev`, `:sources`) | compile + runtime |
| `com.github.GTNewHorizons:Angelica:2.2.20:api` | compile only. 43 classes (`api`, `api.clouds`, `api.tesr`); base classes major **52**; Java 17 multi-release copies under `META-INF/versions/17` (VERIFIED, AUTOMATED: jar listing + class header) |
| `com.github.GTNewHorizons:Angelica:2.2.20:dev` | dev runtime only, optional |
| `com.github.GTNewHorizons:ModularUI2:2.3.85-1.7.10:dev` | R1 |
| `com.github.GTNewHorizons:Hodgepodge:2.7.189:dev` | optional dev runtime |
| `io.github.legacymoddingmc:unimixins:0.3.1` | — |

**Proposed `dependencies.gradle` additions** (INFERRED; R1 adds MUI2):
```groovy
// GTNHLib: client preferences (@Config + config GUI) and the vertex formats Angelica's TESR API takes.
// The pack ships 0.11.51 on client and server (research/07).
implementation("com.github.GTNewHorizons:GTNHLib:0.11.51:dev")
// Angelica TESR material API ("angelica|tesr", apiVersion 2): compile only, reached only when the API is present.
compileOnly("com.github.GTNewHorizons:Angelica:2.2.20:api") { transitive = false }
```

**`gradle.properties`:** keep `usesMixins = false`. Set `forceEnableMixins = true`, which adds UniMixins to the dev runtime only and writes nothing into our jar's manifest (VERIFIED `GG:modules/MixinModule.java:127-128` vs `GG:modules/ToolchainModule.java:422-439`).

**`@Mod` dependency string** (INFERRED):
```java
dependencies = "required-after:gtnhlib@[0.11.51,);required-after:modularui2@[2.3.85,);after:angelica"
```

The ranges evaluate as intended (VERIFIED, AUTOMATED with FML 1.7.10 `VersionParser`):

| Range | Version | Accepted |
|---|---|---|
| `[2.3.85,)` | `2.3.85-1.7.10` | yes |
| `[2.3.85,)` | `2.3.84-1.7.10` | no |
| `[0.11.51,)` | `0.11.51` | yes |
| `[0.11.51,)` | `0.11.52` | yes |
| `[0.11.51,)` | `0.10.7` | no |

Notes:
- Our `mcmod.info` has no `useDependencyInformation`, so the annotation range applies (VERIFIED `src/main/resources/mcmod.info`). Copy AkashicFM's `ModDependencyTest` so it stays that way.
- `after:angelica` is ordering only. Angelica's own registry calls happen in its `preInit` (`ANG:com/gtnewhorizons/angelica/proxy/ClientProxy.java:133`), and `registerDynamicClass` is just a set add (`ANG:.../rendering/TileEntityRenderBoundsRegistry.java:29-31`).

### 1.6 Recommendation: what goes into GTNHLib config and what stays in WebConfig (INFERRED)

**Stays in `common/WebConfig`** (Forge `Configuration` behind `CheckedConfiguration`): site policy, screen limits, rate limits, admins, kill switch. Reasons:

- **Fail-closed on a bad file.** GTNHLib's path renames and resets (§1.2), which turns the kill switch off and loses the site lists during an incident.
- **Immutable snapshots read by several threads.** GTNHLib uses plain mutable static fields.
- **The kill-switch write-back** (`setKillSwitch`) re-reads the file first.
- **The policy reaches clients with protocol versioning** in `S2CHello` and changes live. GTNHLib `@Sync` is login-only.

**New `client/ClientPrefs`:** GTNHLib `@Config(modid = "akashicweb", filename = "akashicweb-client", category = …)`, registered in `ClientProxy.preInit` only. It shows up in the mod list's "Config" button through a `WebGuiFactory implements SimpleGuiFactory` (house style; explicit rather than relying on GTNHLib's auto-GUI mixin). Candidate fields (all AUTHORED, to be decided with R2/R3):

| Category | Field | Type / default | Why |
|---|---|---|---|
| `browsers` | `maxBrowsersOverride` | int, 0 = auto from RAM (`BrowserBudget`) | Low-RAM players |
| `browsers` | `nearFps` / `midFps` / `farFps` | int 30/15/5 | ADR-004 source-side LOD |
| `render` | `uploadBudgetMBPerFrame` | int 8 | ADR-004 budget |
| `render` | `screenShading` | enum `AUTO`/`EMISSIVE`/`LIT`, default `AUTO` | §3.3: flip between the BEACON_BEAM material and the vanilla full-bright quad per shader pack at G3, with no rebuild |
| `render` | `showCursor` | bool true | In-world pointer (§3.5) |
| `audio` | `screenVolume` | int 0-100 | Per-client loudness on top of Master × Records |
| `audio` | `muteAllScreens` | bool | Like AkashicFM's `enableAudio` |
| `input` | `focusModeMouseLook` | bool true | R2's WD-style typing with mouse-look |
| `privacy` | `telemetryOptIn` | bool false | P-09 |

Rules:
- Read the fields on the client thread and hand **snapshots** to host and reader threads.
- Add the generated lang keys (`%mod.%cat.%field`, dumped with `-Dgtnhlib.dumpkeys=true`) to both `en_US.lang` and `pt_BR.lang`. `LangFilesTest` already enforces parity.
- React to GUI edits on `ConfigChangedEvent.OnConfigChangedEvent` for `modID == "akashicweb"`.

---

## 2. Mixins (UniMixins 0.3.1, GTNH toolchain)

### 2.1 How the toolchain wires mixins (VERIFIED)

- **`usesMixins = true`** writes into our jar's manifest:
  - `TweakClass: org.spongepowered.asm.launch.MixinTweaker`
  - `MixinConfigs: mixins.<modid>.json`
  - `ForceLoadAsMod: true`
  - `FMLCorePluginContainsFMLMod: true`

  (`GG:modules/ToolchainModule.java:422-439`). It also adds the mixin annotation processor and refmap (`GG:modules/MixinModule.java:105-125`).
- **`mixinsPackage`** is required with `usesMixins` (`GG:modules/StructureCheckModule.java:61-63`). If no `mixins.<modid>.json` exists, one is generated with `"required": true`, `minVersion 0.8.5-GTNH` and empty lists (`GG:tasks/GenerateMixinAssetsTask.java:75-117`).
- **`mixinPlugin`** names an `IMixinConfigPlugin` written into that JSON (`:95-99`).
- **`forceEnableMixins`** only puts UniMixins on the dev runtime (`MixinModule.java:127-128`).
- **Early vs late (UniMixins `gtnhmixins`):**
  - *Early* mixins target vanilla or Forge classes. They need a coremod (`IFMLLoadingPlugin` implementing `IEarlyMixinLoader`) (`UM:IEarlyMixinLoader.java`), or AkashicFM's route of a plain JSON listed in the manifest.
  - *Late* mixins target mod classes. They are found by scanning `ASMDataTable` for `@LateMixin` classes implementing `ILateMixinLoader` (`UM:mixins/LateMixinOrchestrationMixin.java:45-66`).
  - The modern style is an `IMixins` enum of `MixinBuilder`s with `setPhase`/`setApplyIf`/`addRequiredMod`, as GTNHLib, Angelica and Hodgepodge use (`GL:mixins/Mixins.java`).

### 2.2 What the stack tells us about mixin risk

- **UniMixins on the owner's Crucible** (RFB, Java 21, lwjgl3ify 3.0.31) loads and applies GTNHLib's and lwjgl3ify's mixins. It logs `MixinBootstrap.doInit() called during a tweak constructor!` as ERROR even with no mixins of ours (VERIFIED `BOOT:boot6.log:21-57`, `BOOT:boot3.log:37-71`).
- **Crucible's `NetworkManager` patch** at `staging-67b5303` does not touch `channelRead0`, and the pipeline name `packet_handler` is unchanged (VERIFIED `CR:net/minecraft/network/NetworkManager.java.patch` has no `channelRead0` hunk; `CR:net/minecraft/network/NetworkSystem.java.patch:31,41`). An AkashicFM-style `@Inject(HEAD)` there would apply on Crucible (INFERRED: code reading, not run).
- **AkashicFM's two mixins** are already in the Forbidden pack on both sides (research/07). They passed AkashicFM's production matrix on GTNH 2.7.4 / 2.8.4 / 2.9 (VERIFIED `AFM:docs/FASE7-RESULTADOS.md:283-302`). There is no Crucible run recorded in AkashicFM's docs (VERIFIED, grep).
- **The client render and input path is crowded:**
  - Angelica `@Redirect`s the TESR call in `TileEntityRendererDispatcher.renderTileEntityAt` (`ANGM:angelica/tesr/MixinTileEntityRendererDispatcher.java:18-23`).
  - EntityCulling `@Inject`s HEAD on the same method (`EC:mixin/BlockEntityRenderDispatcherMixin.java`).
  - Angelica `@ModifyExpressionValue`s every `Mouse.next()` and `Keyboard.next()` in `Minecraft.runTick` and in `GuiScreen.handleInput` (`ANGM:angelica/fpsreducer/MixinMinecraft_FpsReducerInput.java`, `MixinGuiScreen_FpsReducerInput.java`).
  - MUI2 posts its own input events from `GuiScreen` (R1).
  - Any mixin of ours there joins a three-way contest.

### 2.3 Candidates

| Candidate | Real benefit? | Mixin needed? | Recommendation (INFERRED) |
|---|---|---|---|
| **Netty-arrival timestamps** for the clock exchange (AkashicFM `MixinNetworkManager.channelRead0`) | **Yes.** In 1.7.10 `FMLProxyPacket` has no `hasPriority`, so `NetworkManager.channelRead0` queues it and it runs on the next tick, 0-50 ms later (VERIFIED `MC:net/minecraft/network/NetworkManager.java` `channelRead0`; `MC:cpw/mods/fml/common/network/internal/FMLProxyPacket.java` `processPacket`; client drain at `MC:net/minecraft/client/Minecraft.java:2152`). AkashicFM measured min RTT 53 ms on localhost and up to ±25 ms clock bias before its fix (VERIFIED `AFM:docs/FASE3-RESULTADOS.md:32-35`), and 1.6-6.8 ms between players after (ADR-012, citing FASE6). | **No.** `NetworkManager.channel()` is public (`MC:.../NetworkManager.java:382`) and FML hands us the `NetworkManager` in `FMLNetworkEvent.ClientConnectedToServerEvent` / `ServerConnectionFromClientEvent` (`MC:cpw/.../NetworkDispatcher.java:181,189`). A `ChannelInboundHandler` added before `packet_handler` sits after `fml:packet_handler` (`:124`) and sees the same `FMLProxyPacket` objects (`:288-293`) on the Netty thread. | **Do it, mixin-free (§2.4).** Fallback: copy AkashicFM's `@Inject(HEAD, remap=false)` mixin if G3 shows the handler missing on Crucible or behind the proxy. |
| Input capture (clicks, wheel, keys) | — | **No.** Forge `MouseEvent` is cancelable and fires before the button reaches the keybindings (VERIFIED `MC:.../Minecraft.java:1776-1781`). WD types with mouse-look by opening a `GuiScreen` that sets `mc.inGameHasFocus = true; mc.mouseHelper.grabMouseCursor()` (VERIFIED `WD:client/gui/GuiKeyboard.java:95-98`). | No mixin. In our GUIs, override `handleKeyboardInput`/`handleMouseInput`, **not** `handleInput`. WD overrides `handleInput` and calls `Keyboard.next()` itself (`WD:client/gui/GuiKeyboard.java:104-138`); that bypasses Angelica's reducer hook and MUI2's input events. |
| Shadow-pass hook | — | **No.** `ShadowRenderingState.areShadowsCurrentlyBeingRendered()` is public (VERIFIED `ANG:net/coderbot/iris/shadows/ShadowRenderingState.java:7`) and already reached by reflection (`AW:client/render/AngelicaCompat.java`). | Keep. |
| Emissive / "after composite" draw | Wanted for shader-correct brightness | **No.** Angelica's public TESR material can request the beacon-beam program (§3.3). | Use the API. Never mixin into Angelica's pipeline. |
| Shutdown hook | Not with an out-of-process host (ADR-001) | — | No. |
| OpenAL lifecycle | Only for SPATIAL audio, which AkashicFM owns (ADR-008) | — | No; use AkashicFM's API. |
| EntityCulling | Needed (§4.4) | **No.** A method override in our own TE does it. | No mixin. |
| FPS reducer "media wake" | Arguable | Not possible for the closed-source FpsReducer; Angelica's `FpsReducer.onInput()` is internal, not `@API` | No. Use pack config (§4.3). |
| Crosshair replaced by a cursor sprite (CinemaMod fork does it on the HUD, per R2) | Yes | **No.** `RenderGameOverlayEvent` exists, but see Angelica HUD caching (§4.2). | Draw the cursor in world space (§3.5). |

### 2.4 Mixin-free clock stamping (design sketch, INFERRED)

`AW:client/net/ClientNetHooks.onClockPong` stamps `arrival` assuming it runs on the Netty thread (`:15-18,66-70`). `AW:client/sync/ServerClock` says the same (`:12-14,66`). It actually runs on the client thread after the tick queue (§5.1). The server answers the ping at handler time with `t1 = t2 = ServerClock.nowMs()` (VERIFIED `AW:server/net/ServerPackets.java:139`), so its leg carries the same 0-50 ms dwell.

```java
/** Netty thread. Records arrival of our clock packets before the vanilla queue. Read-only; never throws. */
@ChannelHandler.Sharable
public final class ClockArrival extends ChannelInboundHandlerAdapter {
    public static final String NAME = "akashicweb:clock_arrival";
    public static final ClockArrival INSTANCE = new ClockArrival();

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            if (msg instanceof FMLProxyPacket && WebNetwork.CHANNEL_NAME.equals(((FMLProxyPacket) msg).channel())) {
                ByteBuf b = ((FMLProxyPacket) msg).payload();
                int i = b.readerIndex();
                // FMLIndexedMessageToMessageCodec writes the discriminator byte first (MC:cpw/.../FMLIndexedMessageToMessageCodec.java:50);
                // the nonce is the first long of both C2SClockPing and S2CClockPong (AW:common/net/C2SClockPing.java:26, S2CClockPong.java:35).
                if (b.readableBytes() >= 9) {
                    int d = b.getByte(i);
                    if (d == WebNetwork.C2S_CLOCK_PING || d == WebNetwork.S2C_CLOCK_PONG)
                        ClockArrivals.put(ctx.channel(), b.getLong(i + 1), monotonicMs());
                }
            }
        } catch (RuntimeException ignored) {
            // documented boundary: clock diagnostics must never disturb the connection
        }
        ctx.fireChannelRead(msg);
    }

    /** From FMLNetworkEvent.{ClientConnectedToServerEvent, ServerConnectionFromClientEvent}: event.manager. */
    public static void install(NetworkManager manager) {
        ChannelPipeline p = manager.channel().pipeline();
        if (p.get(NAME) == null && p.get("packet_handler") != null) p.addBefore("packet_handler", NAME, INSTANCE);
    }
}
```

- **Server.** The handler looks up the ping's arrival (`t1`) and replies with `t2 = now`. Append **`serverRecvMs`** to `S2CClockPong`. S2C messages skip trailing bytes (VERIFIED `AW:common/net/AkwMessage.java` `toleratesTrailingBytes`), so a v1.0 client stays compatible. This is a protocol minor bump.
- **Client.** `onClockPong` takes the arrival from `ClockArrivals` (fallback: handler time), and `ServerClock.onPong` uses four timestamps as AkashicFM does (`AFM:network/S2CClockPong.java:10-20`).
- **Keys and cleanup.** Key by `(channel identity, nonce)` with a size cap, the same pattern as `AFM:network/ClockStamps.java`.
- **Tests.** Unit-test the handler with Netty's `EmbeddedChannel`. Integration: integrated server (local channel, `packet_handler` at `MC:net/minecraft/network/NetworkSystem.java:125`, `NetworkManager.java:329`) and the Crucible boot.
- **Optional, behind a config flag:** set `TCP_NODELAY` on the channel in the same hook. CoreTweaks does it by default on the client (§4.5); I do not know whether the server has CoreTweaks.

### 2.5 Verdict

AkashicWeb ships **zero mixins**: `usesMixins=false`, and the ADR-009 jar checks (no `TweakClass`, no `FMLCorePlugin`) stay. UniMixins is already a launch requirement through MUI2 and GTNHLib. The remaining costs of an own mixin would be the conflict surface and the coremod manifest flags, and none of the candidates needs one. Revisit only if the pipeline handler fails at G3.

---

## 3. Angelica 2.2.20 TESR material/mesh API

### 3.1 What it is (VERIFIED, `ANG:com/gtnewhorizons/angelica/api/tesr/`)

- **Package:** `@API(owner="angelica", provides="angelica|tesr", apiVersion="2")`, "TESR mesh cache/batch API (Render thread only)" (`package-info.java:1-3`). It is unchanged between 2.2.20 and 2.2.29 (VERIFIED: `git diff --stat 2.2.20 HEAD -- …/api/tesr/` in `/home/user/gtnewhorizons/Angelica` is empty).
- **`TesrMeshCache.renderCached(Object key, TesrMeshBuilder)` and `invalidate(key)`** (`TesrMeshCache.java:10-17`). The cache:
  - is keyed by **identity** (`Reference2ObjectOpenHashMap`);
  - evicts after 60 s unused;
  - clears on a texture re-stitch

  (`ANG:.../rendering/tesr/AngelicaTesrMeshCache.java:34,45,101-118,190-198`).
- **`TesrMeshProvider`** is an alternative to implement on the TESR, with `angelica$meshKey`, `angelica$meshDirty`, `angelica$transform` and `angelica$build` (`TesrMeshProvider.java:12-28`). Angelica's dispatcher redirect calls it instead of `renderTileEntityAt` when `enableTESRProviderDispatch` (default `true`) is set (`ANGM:angelica/tesr/MixinTileEntityRendererDispatcher.java:18-23`; `ANG:.../rendering/tesr/TesrProviderDispatch.java:27-39`; `ANG:.../config/AngelicaConfig.java:169-172`).
- **`TesrMeshSink.angelica$bucket(VertexFormat, ResourceLocation texture, TesrMaterial)`** returns a capturing `Tessellator` (`TesrMeshSink.java:8-18`).
- **`TesrMaterial.builder()`** (interned) (`TesrMaterial.java:51-169`) offers:
  - `color`, `lightmap`, `noCull`, `unlit`, `noDepthWrite`, `depthOnly`, `stream`, `cutout`, `depthEqual`;
  - `translucent` / `additive` / `additiveAlpha` / `glint`;
  - `special(NONE|BEACON_BEAM|GLINT)`;
  - `shader(TesrShader)`.
- **How queued geometry is drawn:**
  - Queued meshes take the **current model-view matrix at queue time** (`TesrBatchRenderer.java:161-162`). Our `glTranslated`/`glMultMatrix` before the call places the quad.
  - Inside a world pass they are retained and drawn at the pass flush (`CeleritasWorldRenderer.java:406,478`; `ShadowRenderer.java:816,848`). Outside one, they are drawn immediately (`TesrBatchRenderer.java:178-180`).
  - At draw time the layer binds the bucket texture with `TextureManager.bindTexture(ResourceLocation)` (`ANG:.../compat/mojang/RenderLayer.java:193-200`).

### 3.2 Which entry point (INFERRED)

Use **`TesrMeshCache.renderCached` from inside our own `renderTileEntityAt`**, not `TesrMeshProvider`:
- our TESR keeps control of the shadow-pass skip, the placeholder card and text, and the cursor;
- nothing depends on Angelica's dispatcher config flag;
- the mesh is static (one quad per screen face), so the cache always hits; only the texture content changes, through our `glTexSubImage2D` at `RenderTickEvent` START (ADR-004).

**Mesh keys.** Each `ClientScreen` holds one key object per face. When the geometry changes, call `TesrMeshCache.invalidate(oldKey)` and allocate a new key.

### 3.3 A shader-correct, full-bright page quad

Angelica draws the vanilla beacon beam through this API with exactly the attributes a screen wants (VERIFIED `ANG:.../rendering/tesr/BeaconBeamMeshes.java:18-24,33-34`):

```java
TesrMaterial.builder().color(1f, 1f, 1f, 1f).lightmap(240f, 240f).unlit().noCull()
    .special(TesrMaterial.SpecialRender.BEACON_BEAM).build();
```

- `special(BEACON_BEAM)` makes the layer call `GbufferPrograms.setupSpecialRenderCondition(SpecialCondition.BEACON_BEAM)` around the draw (VERIFIED `RenderLayer.java:46-48,140-156`).
- With a shader pack loaded, Iris maps that to `RenderCondition.BEACON_BEAM` → program `gbuffers_beaconbeam`, falling back to `gbuffers_textured` (VERIFIED `ANG:net/coderbot/iris/pipeline/DeferredWorldRenderingPipeline.java:869-870,488`; `ANG:net/coderbot/iris/shaderpack/loading/ProgramId.java:38`).
- Without the special condition a TESR quad goes to `gbuffers_block` (`BLOCK_ENTITIES`, `DeferredWorldRenderingPipeline.java` `getCondition`). That program lights it like a block, which is the "blocklight tint" ADR-005 accepted for 10-25.
- The other emissive route, `ENTITY_EYES` → `gbuffers_spidereyes`, forces additive blending (`ProgramId.java:48-50`). It is wrong for an opaque page, and the API does not expose it anyway.

**INFERRED, test at G3.** BSL v8.2.09 and Complementary r5.9.3 shade `gbuffers_beaconbeam` as unlit/emissive, and they may add bloom or glow. That is why §1.6 proposes the `render.screenShading = AUTO/EMISSIVE/LIT` client option: `EMISSIVE` = BEACON_BEAM material; `LIT` = today's look (`special NONE`, lightmap 240). The owner picks per pack at G3 without a rebuild.

### 3.4 The page texture as a `ResourceLocation` (INFERRED design, VERIFIED mechanics)

The bucket wants a `ResourceLocation`. `TextureManager.bindTexture` looks the location up in its map; a missing one becomes a `SimpleTexture` loaded from resources (VERIFIED `MC:net/minecraft/client/renderer/texture/TextureManager.java:38-49`).

- **Register:** `ScreenTexture` (or a thin `AbstractTexture` adapter over its GL id) goes in with `TextureManager.loadTexture(rl, obj)` (`:82`), where `rl = new ResourceLocation("akashicweb", "dynamic/screen_<slot>")`.
- **Delete** with `deleteTexture(rl)` (`:153`). The adapter's `loadTexture(IResourceManager)` is a no-op, because F3+T reloads call it (`:163`).
- **Use a fixed pool of locations** (one per browser-budget slot) and re-register the same location when a texture is reallocated. Angelica interns one `RenderLayer` per distinct texture location and caches it until a full clear (`TesrBatchRenderer.java:266-322`; `RenderLayer.java:115-117`), so fresh locations per resize would grow those maps.

### 3.5 In-world cursor overlay (INFERRED)

- **Material:** a second bucket with a small cursor sprite (`assets/akashicweb/textures/gui/cursor.png`, frames chosen from the host's cursor type as the CinemaMod fork does, per R2), using the beacon's OUTER recipe: `color(...).lightmap(240,240).unlit().noCull().translucent().noDepthWrite().special(BEACON_BEAM)` (VERIFIED as Angelica's own combination, `BeaconBeamMeshes.java:22-24`).
- **Mesh:** a static unit quad with its own key. Before `renderCached`, `glTranslated` to the hit point (page px → face coordinates through `ScreenGeometry`) and scale. The mesh stays cached; only the matrix changes each frame (`TesrBatchRenderer.java:162`).
- **Depth:** lift it slightly off the page (as `TEXT_LIFT` does today) so it never z-fights.
- **Why not the HUD:** Angelica's HUD caching would redraw a HUD cursor at 20 FPS (§4.2). A world-space cursor is also correct for every viewer angle and under shaders.

### 3.6 Shadow pass, text, fallbacks

- **Shadow pass.** Our TESR keeps returning early during the shadow pass, as it does now (`AW:client/render/ScreenRenderer.java` `renderTileEntityAt`). Angelica renders TEs again there and would otherwise queue our mesh into `PASS_SHADOW` (`ShadowRenderer.java:816,829,841,848`). The face sits on solid blocks that already cast shadows.
- **Text.** The placeholder card, banner and text stay on the immediate Tessellator/`FontRenderer` path (Angelica batches fonts itself). Depth keeps the order right, because the banner and text are lifted in front.
- **Fallback.** With no Angelica, or no `angelica|tesr` API, keep today's Tessellator code. Both paths must take their corners and UVs from the same `ScreenGeometry`. Add a unit test that compares them.

### 3.7 Build wiring (INFERRED, VERIFIED mechanics)

- Put the Angelica calls in a separate client class, e.g. `client/render/AngelicaTesrPath`. Load it only when `ModAPIManager.INSTANCE.hasAPI("angelica|tesr")` (or `Loader.isModLoaded("angelica")`) **and** a reflective probe for `TesrMaterial$SpecialRender.BEACON_BEAM` succeeds.
- FML's `@Optional` stripping also understands API ids (VERIFIED `MC:cpw/mods/fml/common/asm/transformers/ModAPITransformer.java:52`). A separate class selected at runtime is simpler to test than `@Optional` on the TESR.
- Compile only against `Angelica:2.2.20:api` and GTNHLib (`DefaultVertexFormat.POSITION_TEXTURE`, `GL:client/renderer/vertex/DefaultVertexFormat.java:65`).
- Extend the jar check: only `client.render.*` may reference `com/gtnewhorizons/angelica`.

### 3.8 `TileEntityRenderBoundsRegistry`: keep what exists

- `registerDynamicClass(String)` adds to a concurrent name set (VERIFIED `ANG:.../rendering/TileEntityRenderBoundsRegistry.java:21,29-31`).
- `classify()` caches per class on first sight (`:37-58`). DYNAMIC classes are frustum-tested with the live `getRenderBoundingBox()` (`CeleritasWorldRenderer.java:552-560`).
- The pack-config name list is applied in Angelica's `preInit` (`ANG:.../proxy/ClientProxy.java:133`; `AngelicaConfig.java:326`).
- The class is **not** in the API jar (VERIFIED jar listing: only `api`, `api.clouds`, `api.tesr`). AkashicWeb's reflective registration in client preInit with read-back verification (`AW:client/render/AngelicaCompat.java`) is already the right shape. No change.

### 3.9 Risks and G3 checks (INFERRED)

- **Risk:** BEACON_BEAM may bloom, fog or tint under a pack. Mitigation: the `screenShading` option, tested BSL off / BSL / Complementary, with a 16x9 screen in day, night and an indoor dark room.
- **Risk:** the retained mesh holds a stale texture binding after a resize. Mitigation: invalidate on reallocation; test resizing a formed screen while a video plays.
- **Check:** upload stays once per frame and never happens in the shadow pass (ADR-005).
- **Check:** `/awdoctor` shows which path is active (`ANGELICA_TESR` / `TESSELLATOR`) and the material mode.

---

## 4. The rest of the pack's stack

### 4.1 Hodgepodge 2.7.189 (client and server)

- **`logarithmicVolumeControl`, default `true`:** squares the value passed to `setMasterVolume` and to each category's `setVolume` (VERIFIED `HP:config/FixesConfig.java:116-118`; `HP:mixins/early/minecraft/MixinSoundManager.java`; applied through `HP:mixins/Mixins.java:144-147`). `AW:client/screen/GainModel.screenGain` multiplies **linear** Master × Records, so at 50 % sliders the page plays at 0.25 while every game sound plays at 0.0625. **Fix (INFERRED):** when Hodgepodge is loaded, read `com.mitchej123.hodgepodge.config.FixesConfig.logarithmicVolumeControl` once by reflection and square both levels. AkashicFM's plan noted the same curve (`AFM:docs/PLANO.md:35`).
- **`triggerAllConflictingKeybindings`, default `true`:** every keybinding on a shared key fires (VERIFIED `HP:config/FixesConfig.java:307-309`; `HP:mixins/Mixins.java:610-615`). Our binds default to `KEY_NONE`; never rely on exclusive delivery.
- **`enableTextFieldCtrlShortcuts`, default `true`:** changes Ctrl/Cmd shortcuts in **vanilla** `GuiTextField` only (VERIFIED `HP:config/TweaksConfig.java:105-107`; `Mixins.java:626-628`). It does not affect MUI2 fields or our own key router.
- **`increasePacketSizeLimit`, default `true`:** VERIFIED `FixesConfig.java:256-258`. Our packets are small; do not depend on it.
- Nothing else touches TESR dispatch (only a profiler mixin), the Netty pipeline position, or mouse input (VERIFIED grep of `HP:mixins/Mixins.java`).

### 4.2 Angelica 2.2.20 built-ins that the interaction layer must respect

- **HUD caching**, `enableHudCaching` default `true`, `hudCachingFPS` default 20 (VERIFIED `AngelicaConfig.java:101-117`):
  - The whole `renderGameOverlay`, Forge overlay events included, is rendered into a framebuffer at most 20 times a second and reused in between (VERIFIED `ANG:.../hudcaching/HUDCaching.java:98-115`).
  - Only vignette, helmet, portal and crosshairs are re-drawn every frame (`:117-150`).
  - **So no smooth HUD animation:** a HUD cursor or progress bar would step at 20 FPS. Put the pointer in the world (§3.5).
- **Built-in FPS reducer:**
  - Defaults (VERIFIED `ANG:me/jellysquid/mods/sodium/client/gui/SodiumGameOptions.java:253-262`): `enabled=false`, `idleTimeoutMinutes=5`, `idleFpsLimit=30`, `unfocusedFpsLimit=10`, volumes 100 %.
  - It counts input through mixins on `Minecraft.runTick` and `GuiScreen.handleInput` (`ANG:.../mixins/Mixins.java:566-573`; `ANGM:angelica/fpsreducer/*`).
  - When enabled with volumes below 100 %, it scales only paulscode's MASTER level (`ANGM:angelica/fpsreducer/MixinGameSettings_FpsReducerVolume.java`). Chromium's audio ignores it (INFERRED). That is acceptable while the defaults stay at 100 %.
  - I did not read the pack's `angelica-options.json` reducer block (research/07 lists other keys only). **Open:** does the pack enable it?
- **`use_entity_culling`** applies to entities only. Tile entities are frustum-tested by bounds class (VERIFIED `CeleritasWorldRenderer.java:552-560` for TEs, `:613-631` for entities).

### 4.3 FpsReducer 1.10.3 (bre2el)

- **Source:** no public repository at `github.com/bre2el/FpsReducer` (VERIFIED: `git ls-remote` asks for credentials, i.e. not public). Behaviour is INFERRED.
- **Effect:** earlier research of the pack config recorded "idle FPS 10 after 300 s without input" (`docs/research/raw/09-tech-challenge.json`; ADR-005 "Pack settings for the event"). A player watching the telão without touching keys or mouse drops to 10 FPS. Every screen texture is uploaded and drawn per rendered frame, so the live looks like 10 FPS.
- **Recommendation (INFERRED):**
  - The owner raises or disables the idle reduction in the pack config before any public test.
  - The client `/awdoctor` prints a line when a reducer mod is loaded.
  - The host may lower its own frame rate when the game's FPS is capped, to save CPU.
  - No code integration: the mod is closed source with no known API.

### 4.4 EntityCulling 1.6.4 (tr7zw, 1.7.10 build)

What the code does (VERIFIED):
- A background thread tests every loaded TE **within 64 blocks** with a ray-occlusion check of the **1×1×1 box at the TE's own coordinates**, not its render box (`EC:CullTask.java:59-83`, the fixed box at `:77-81`).
- TEs whose block's unlocalized name is in `blockEntityWhitelist` are skipped (`:68`). The default whitelist is `{"tile.beacon"}`, read from `config/entityculling.json` (`EC:Config.java:11`, `EC:EntityCullingModBase.java:32-63`).
- The renderer then cancels the TESR of culled TEs (`EC:mixin/BlockEntityRenderDispatcherMixin.java`).

Consequence (INFERRED from the code): our screen keeps its single TE at the multiblock origin (a corner). Whenever that one corner block is hidden from the camera — behind a pillar, a wall edge or other players' builds — **the whole screen disappears** even though most of it is in plain view. At the telão this will happen.

Fixes:
1. **Mod side (recommended).** `TileEntityScreen` declares `public boolean isForcedVisible() { return true; }`.
   - EntityCulling's mixin adds that method to `TileEntity` (VERIFIED `EC:mixin/CullableMixin.java`).
   - Both the cull thread (`CullTask.java:72`) and the dispatcher check (`BlockEntityRenderDispatcherMixin`) call it through the `Cullable` interface, so our subclass's method wins by normal virtual dispatch (INFERRED, standard JVM semantics). Without EntityCulling it is an unused public method.
   - No other mod in the stack defines that name on `TileEntity` (VERIFIED grep of Angelica, Hodgepodge, GTNHLib, CoreTweaks).
2. **Pack side (belt).** Add `"tile.akashicweb.screen"` to `blockEntityWhitelist`. Our block is named `akashicweb.screen` (VERIFIED `AW:common/block/BlockScreen.java:42`).
3. **Not recommended:** adding to the whitelist set by reflection at runtime.
   - `EntityCullingModBase` constructs an `EntityCullingMod` in a static initializer (`EC:EntityCullingModBase.java:22`), and FML constructs another; each constructor starts its own cull thread with its own config object (`EC:EntityCullingMod.java:22-30`, `EC:EntityCullingModBase.java:42-70`).
   - So two cull threads probably exist (INFERRED), and reflection only reaches the last instance's set.

### 4.5 CoreTweaks 0.3.4.7-GTNH (client; server presence unknown)

- **`tcpNoDelay`, default `TRUE`,** "works on server as well as client" (VERIFIED `CT:Config.java:201-205`; mixin `optimization.tcpnodelay.MixinChannelInitializers` in `CT:MixinConfigPlugin.java:169-170`). Small packets (clock pings, control) leave without Nagle delay. It does not conflict with a pipeline handler.
- **`transformerCache`** default on, mode `LITE` ("Safe") (`CT:Config.java:244-252`). It caches select transformers' output. Nothing in AkashicWeb is transformed, because we ship no mixins and no ASM.
- Nothing touches TE rendering or input.

### 4.6 lwjgl3ify (server 3.0.31; not in the Forbidden client)

- **Server:** nothing in the interaction layer runs there. Our server classes boot under it (evidence file, 2026-10-13).
- **Players on other launchers** with Java 17+ and lwjgl3ify (Tier-2 only):
  - Clipboard must go through `GuiScreen.getClipboardString()`, which lwjgl3ify `@Overwrite`s to SDL (VERIFIED `L3:src/main/java/me/eigenraven/lwjgl3ify/mixins/early/game/MixinGuiScreen.java`). We already do (VERIFIED `AW:client/gui/KeyboardGui.java:64`).
  - Characters arrive only while SDL text input is on. lwjgl3ify switches it on for vanilla text fields (`L3:.../mixins/early/game/MixinGuiTextField.java:32`). A custom keyboard GUI should call `me.eigenraven.lwjgl3ify.api.InputEvents.beginTextInput()` / `endTextInput()` by reflection on open and close (API VERIFIED in 3.0.37, `L3:src/main/java/me/eigenraven/lwjgl3ify/api/InputEvents.java:173,180`; not checked in older versions).

### 4.7 FoamFix 1.0.4 and ArchaicFix 0.8.0

Not inspected: I did not locate FoamFix 1.7.10's source, and ArchaicFix was out of scope. No interaction is known. Watch the G3 client log for their names.

---

## 5. Findings against the current code (at `83585d3`)

1. **The clock pong is not stamped on the Netty thread.**
   - `ClientNetHooks` and `ServerClock` claim Netty-thread arrival (`AW:client/net/ClientNetHooks.java:15-18,66-70`; `AW:client/sync/ServerClock.java:12-14,66`).
   - But `S2CClockPong.Handler` runs where every handler of our channel runs: the client thread, after the vanilla queue. `WebNetwork`'s own comment and PROTOCOL.md say so (`AW:common/net/WebNetwork.java:15-18`; `docs/PROTOCOL.md:86`), and the code confirms it (§2.3).
   - The server likewise stamps `t1 = t2` at handler time (`AW:server/net/ServerPackets.java:139`).
   - Impact (INFERRED from AkashicFM's measurement): an offset error of up to about ±25 ms per side. The min-RTT filter only partly hides it. It counts against ADR-012's ±40 ms deadband.
   - Fix: §2.4 (VERIFIED mechanism, INFERRED magnitude).
2. **`GainModel` ignores Hodgepodge's squared volume curve** (§4.1). VERIFIED.
3. **EntityCulling will hide whole screens.** It culls on the origin block alone (§4.4). The code is VERIFIED; the in-game effect is INFERRED.
4. **GUI input path.** Any GUI that overrides `handleInput` (the WD pattern) hides input from Angelica's reducer and from MUI2's input events. Override `handleKeyboardInput`/`handleMouseInput` instead (§2.3). VERIFIED hooks; effect INFERRED.
5. **HUD overlays step at 20 FPS** under Angelica (§4.2). VERIFIED.

---

## 6. Proposed doc changes (for the lead; not made, this stream is read-only)

- **ADR-009 amendment** (D-017 supersedes "Forge-only hard dependencies"):
  - Declared hard dependencies become GTNHLib ≥ 0.11.51 and ModularUI2 ≥ 2.3.85, which bring gtnhmixins/UniMixins with them.
  - AkashicWeb still ships no mixins, no coremod and no access transformer.
  - `forceEnableMixins=true` applies to the dev run only.
  - Server policy stays on the fail-closed Forge wrapper; client preferences move to GTNHLib `@Config` in a separate file.
  - GTNHLib's Brigadier, `SyncedKeybind`, `IPacket` and `ServerThreadUtil` are not used, with the reasons in §1.3.
- **ADR-005 amendment:**
  - The page and cursor use Angelica's TESR API with the BEACON_BEAM material when `angelica|tesr` v2 is present, with a client shading toggle. The Tessellator path is the fallback.
  - EntityCulling is handled by the `isForcedVisible()` override plus the pack whitelist, no longer "if G3 shows culling".
  - No per-frame HUD drawing.
- **ADR-012 / PROTOCOL:**
  - Netty-arrival stamping through a pipeline handler.
  - `S2CClockPong` gains `serverRecvMs` (appended; protocol minor bump).
  - Correct the comments in `ClientNetHooks` and `ServerClock`.
- **Owner pack checklist (P-list):**
  - FpsReducer idle setting.
  - `entityculling.json` whitelist entry `tile.akashicweb.screen`.
  - Angelica reducer state in `angelica-options.json`.
  - Whether the server runs CoreTweaks (TCP_NODELAY).

## 7. Not run, and open questions

- **Not run:**
  - anything in Minecraft (client or server) with these changes;
  - any shader pack;
  - Windows;
  - the pipeline handler on Crucible or behind BungeeCord;
  - the BEACON_BEAM look under BSL or Complementary;
  - EntityCulling's double cull thread (inferred from code only);
  - FpsReducer's actual idle rule (source unavailable).
- **Open:**
  - the pack's `angelica-options.json` reducer block and `entityculling.json` content;
  - whether CoreTweaks is on the server;
  - ProtocolLib's pipeline insertions on Crucible, which I did not read and which do not move `packet_handler` (INFERRED);
  - FoamFix and ArchaicFix sources.
