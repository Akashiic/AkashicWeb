# R1: ModularUI2 2.3.85 (Minecraft 1.7.10) for AkashicWeb

Date: 2026-10-13 (UTC). Scope: the ModularUI2 (MUI2) artifact the Forbidden pack ships, an API cheat sheet that compiles against it, and 1.7.10 gotchas. The goal is building AkashicWeb's GUIs with MUI2 (D-017) while keeping Web Displays-style keyboard and mouse control of the in-world browser.

**Grades used**

| Tag | Meaning | Repo evidence grade |
|---|---|---|
| **V-STATIC** | VERIFIED by reading code or docs (cited `file:line`) | STATIC |
| **V-BUILD** | VERIFIED by compiling here | BUILD |
| **V-AUTO** | VERIFIED by running something here (Linux container) | AUTOMATED |
| **INF** | INFERRED | (none) |

Nothing here ran inside Minecraft, on Windows, with Angelica and shaders, or with an ABNT2 keyboard (see §6). Nothing here is LIVE-OBSERVED.

This file was written in a first pass and then re-verified in a second pass on the same day: the main citations were re-read against the tag, the sample was recompiled, and the second pass added findings (Option C screen-level keys, `func_152348_aa` on every event, Angelica's FPS reducer hooks, Hodgepodge check, compile without NEA/lwjgl3ify).

**Sources** (read-only clones under `scratchpad/wave3/src/`, downloads under `scratchpad/wave3/maven/`)

- **MUI2:** `GTNewHorizons/ModularUI2` at tag `2.3.85-1.7.10` (commit `f9cfb98389ff2a39ae0f060bad38aef6168ec6f7`, 2026-08-02). Unprefixed paths below are relative to `src/main/java/com/cleanroommc/modularui/`.
- **Minecraft/Forge 1.7.10 (10.13.4.1614) sources:** `/home/user/akashicweb/build/rfg/mcp_patched_minecraft-sources.jar`. Cited as `MC:<path>`.
- **LWJGL 2:** `LWJGL/lwjgl` at tag `lwjgl2.9.3`. `handleChar` is identical in `lwjgl2.9.1`, checked with a diff. Cited as `LWJGL:<path>`.
- **Angelica:** at tag `2.2.20` (commit `1babfd9`, the pack's version). Cited as `ANG:<path>`.
- **Others:**
  - UniMixins `0.3.1`
  - GTNHLib `0.11.51`
  - NotEnoughItems `2.8.125-GTNH`
  - InventoryBogoSorter `1.3.46-GTNH`
  - GT5-Unofficial master `3a90a75` (2026-10-13)

---

## 0. Key findings

1. **The pack's jar is the GTNH Maven artifact.**
   - The pack has `mods/modularui2-2.3.85-1.7.10.jar`: 1,624,381 bytes, CRC32 `63af7f03`, read from the TEOF-3.6 zip central directory.
   - This is byte-for-byte the same size and CRC32 as `com.github.GTNewHorizons:ModularUI2:2.3.85-1.7.10` (reobf jar, SHA-256 `ccd7a2a1…6712`).
   - The `-sources` jar matches the git tag exactly (0 differing files). [V-AUTO]
2. **Identity and dependencies.** modid is `modularui2`. It declares `required-after:gtnhmixins@[2.0.1,); required-after:gtnhlib@[0.2.0,)` (`ModularUI.java:21-27`). The pack satisfies both:
   - UniMixins 0.3.1 provides `gtnhmixins` 2.2.0.
   - GTNHLib is 0.11.51.

   FML's own `VersionRange` accepts `2.3.85-1.7.10` for `[2.3.85,)` and rejects `2.3.84-1.7.10`. [V-AUTO]
3. **Client-only screen.** `ClientGUI.open(new ModularScreen("akashicweb", panel))` wraps it in `GuiScreenWrapper` and opens it immediately, although the javadoc says "next tick". [V-STATIC]
4. **MUI2 does not hide raw input.**
   - Its mixin posts cancelable `KeyboardInputEvent.Pre` / `MouseInputEvent.Pre` on the Forge bus for every LWJGL event on every GuiScreen, before any handling.
   - Cancelling at `EventPriority.HIGHEST` gives full control, because MUI2's own handlers do not receive cancelled events.
   - A `ModularScreen` subclass can also override `onKeyPressed` / `onKeyRelease` to take every key at screen level (Option C, §2.8).
   - This contradicts the reason ADR-009 gave for rejecting MUI2 ("It hides the low-level keyboard events we need", `docs/adr/README.md:761`). [V-STATIC]
5. **Widget-level keyboard has two traps.**
   - A focused widget gets keys first, but key releases carry the *last pressed* char.
   - LWJGL 2's "char-only" events (`key 0`, state released) reach widgets only as releases, so stock text fields drop them. On Windows these come from:
     - the second char of a dead-key fallback (´ then x);
     - IME commits and Alt+numpad codes;
     - the low surrogate of an emoji.
   - The same loss exists in vanilla 1.7.10 and in AkashicWeb's `KeyboardRouter` (§4.4). [V-STATIC; behaviour on real Windows: INF]
6. **Non-pausing, non-dimming screen.** `pausesGame` defaults to `false`. In 2.3.85 a client-only `GuiScreenWrapper` never draws the dark gradient; only container GUIs do. `panel.fullScreenInvisible()` plus translucent `Rectangle` drawables leaves the world, and the in-world page, visible. [V-STATIC]
7. **Custom GL drawing.**
   - Use an `IDrawable` lambda or override `Widget.draw`. GL is already translated to the widget origin.
   - Clipping uses the stencil buffer, not scissor.
   - `Platform.setupDrawTex(int glTextureId)` plus `GuiDraw.drawTexture(...)` draws the live page texture.

   [V-STATIC; drawing code V-BUILD]
8. **Mouse precision.** Widget mouse coordinates are integer GUI units, refreshed once per frame. For page pixels use `Mouse.getEventX/Y` with `ScaledResolution`. [V-STATIC]
9. **Global GL side effects.**
   - MUI2 enables `GL_STENCIL_TEST` and clears the stencil buffer on every `RenderTickEvent`, even with no GUI open.
   - Angelica 2.2.20 always creates an 8-bit-stencil, core-profile context.
   - Angelica redirects all GL11 calls from MUI2 and from us into its GLSM.

   [V-STATIC]
10. **Known bugs and traps in 2.3.85.**
    - Tooltip draw order and overlay text input: fixed in 2.3.89.
    - Debug outlines are on by default in dev environments: fixed in 2.3.90.
    - A `TextFieldWidget` whose bound value throws on focus loss crashes the client (GTNH modpack #27170).
    - Esc in-world closes the whole screen, dialogs included.

    [V-STATIC / web]
11. **Real examples in the same stack.**
    - InventoryBogoSorter 1.3.46-GTNH: in the pack and compiled against MUI2 2.3.85, so its config GUI is the closest model.
    - GT5-Unofficial: toolbox radial menu, a full-screen invisible panel with custom GL.
    - MUI2's own test GUIs.

    [V-STATIC]
12. **A seven-file sample compiles.** Address bar, URL field, live page preview, keyboard mode, raw input hooks, screen-level key capture and key sink compile against 2.3.85 with `javac --release 8`: 10 classes, all class major 52. They compile with only the MUI2 `dev` jar plus Minecraft/Forge and LWJGL, without NEA, lwjgl3ify or GTNHLib, as long as our code avoids `IMuiScreen` (§2.14). [V-BUILD]
13. **Angelica 2.2.20 hooks the same input loop.** Its FPS reducer counts every `Mouse.next()` / `Keyboard.next()` in `GuiScreen.handleInput` as activity, even for events we cancel. The reducer is off by default. Hodgepodge 2.7.189 has no mixin on `GuiScreen.handleInput` / `handleKeyboardInput`. [V-STATIC]

---

## 1. Artifact, sources, dependencies, modid

### 1.1 Maven coordinates

Repository: `https://nexus.gtnewhorizons.com/repository/public/`. `maven-metadata.xml` lists `2.3.80…2.3.89-1.7.10`; latest is `2.3.92-1.7.10` (lastUpdated 20261005063833). [V-AUTO]

| Classifier | File | Size | SHA-256 |
|---|---|---|---|
| (reobf, what players run) | `ModularUI2-2.3.85-1.7.10.jar` | 1,624,381 | `ccd7a2a1244ad1e1e03f07a63bce884d2c69c0755e94a00edcfa090125d06712` |
| `dev` (compile against this) | `ModularUI2-2.3.85-1.7.10-dev.jar` | 1,631,160 | `b73067d456614b1f3163d614f2f4f144ab698f2a2311c2b1389bed00ec3a6112` |
| `sources` | `ModularUI2-2.3.85-1.7.10-sources.jar` | 923,831 | `8bc0b491d549dedc4936c75eba18640f5e7a1a994b8676d1069612289980ff76` |
| `api`, `javadoc` | not published (HTTP 404) | — | — |

[V-AUTO]

- **Git tag:** `2.3.85-1.7.10`. Older `v2.x` tags in that repo are the 1.12 CleanroomMC line and are not ours. [V-STATIC]
- **License:** the repo ships `LICENSE`. It was not checked for redistribution; we only depend on MUI2 and do not ship it.

### 1.2 Jar facts

- **Manifest:**
  ```
  FMLCorePlugin: com.cleanroommc.modularui.core.ModularUICore
  FMLCorePluginContainsFMLMod: true
  TweakClass: org.spongepowered.asm.launch.MixinTweaker
  MixinConfigs: mixins.modularui2.json
  ForceLoadAsMod: true
  ```
  MUI2 is a coremod with mixins. [V-AUTO]
- **Class versions:** all 857 classes are major 52, including vendored, unrelocated `com.ezylang.evalex` (math in number fields). [V-AUTO]
- **Early client mixins:** `GuiScreenMixin`, `MinecraftMixin`, `GuiButtonMixin`, `GuiContainerMixin`, accessors, and `ForgeHooksClientMixin` (`core/mixinplugin/Mixins.java:9-27`). [V-STATIC]
- **Common mixins:** `EntityPlayerMPMixin`, `SimpleNetworkWrapperMixin` and accessors. [V-STATIC]
- **Late mixins:** NEI `RecipeInfoMixin`; a Thaumcraft one that does not apply to us. [V-STATIC]
- **Network channel:** `modularui2`, a `SimpleNetworkWrapper` (`network/NetworkHandler.java:23`). [V-STATIC]
- **Config:** `config/modularui2.cfg`, through GTNHLib `@Config` (`ModularUIConfig.java:7-45`). The pack ships this file (1,685 bytes); its content was not read. [V-STATIC / V-AUTO listing]

### 1.3 Its dependencies (POM / Gradle module metadata of 2.3.85)

| Variant | Dependency | Scope / notes | In the Forbidden pack? |
|---|---|---|---|
| api | `com.github.GTNewHorizons:GTNHLib:0.11.19:dev` | compile + runtime | GTNHLib **0.11.51** (client + server) |
| api | `com.github.GTNewHorizons:lwjgl3ify:3.0.25:api` (excludes `*:*`) | compile only | **server only** (3.0.31); no lwjgl3ify jar in client `mods/` |
| api | `com.github.GTNewHorizons:ModularUI:1.3.4:dev` (MUI1, excludes `*:*`) | compile only | **not in pack** |
| api | `curse.maven:neverenoughanimation-1062347:7664003-sources-7663978` (NEA) | compile only, from CurseMaven | **not in pack** |
| runtime | `io.github.legacymoddingmc:unimixins:0.3.1:dev` | runtime | UniMixins 0.3.1 |
| runtime | `com.github.GTNewHorizons:NotEnoughItems:2.8.105-GTNH:dev` | runtime | NEI **2.8.125-GTNH** |
| runtime | `com.github.GTNewHorizons:Baubles-Expanded:2.2.21-GTNH:dev` | runtime | BaublesExpanded 2.2.22-GTNH |

[V-AUTO, POM and `.module`; pack listing V-AUTO]

**`@Mod` dependency string** (`ModularUI.java:18-27`):

```
modid = "modularui2"
"required-after:gtnhmixins@[2.0.1,); required-after:gtnhlib@[0.2.0,);"
"after:NotEnoughItems@[2.3.27-GTNH,);after:hodgepodge@[2.0.0,);before:gregtech;"
```

[V-STATIC]

- **`gtnhmixins` is provided by UniMixins 0.3.1** at version 2.2.0. Sources: `module-gtnhmixins/gradle.properties:1` (`extraVersion=2.2.0`) and `GTNHMixins.java:12` (`MODID = "gtnhmixins"`). [V-STATIC]
- **GTNHLib API used by the 2.3.85 tag commit** (`NumberFormatConfig.useForgeFluidMillibuckets`) exists in GTNHLib 0.11.51 (`NumberFormatConfig.java:22`). [V-STATIC]
- **FML version checks**, run with `cpw.mods.fml.common.versioning.VersionRange` from the RFG 1.7.10 jar:

  | Range | Version | Result |
  |---|---|---|
  | `[2.3.85,)` | `2.3.85-1.7.10` | true |
  | `[2.3.85,)` | `2.3.84-1.7.10` | false |
  | `[2.3.85,)` | `2.3.92-1.7.10` | true |
  | `[2.0.1,)` | `2.2.0+uni.0.3.1` | true |

  [V-AUTO]

### 1.4 What AkashicWeb would declare

These are proposals; the AkashicWeb repo was not edited.

```groovy
// dependencies.gradle
// MUI2 exactly as shipped by the Forbidden pack. Keep it transitive: runClient needs its runtime deps (UniMixins, NEI,
// Baubles-Expanded), and javac needs NEA's IAnimatedScreen and lwjgl3ify's InputEvents if our code ever references
// IMuiScreen (§2.14). Our own code should use ModularScreen.getCurrent() instead and then needs neither.
implementation("com.github.GTNewHorizons:ModularUI2:2.3.85-1.7.10:dev")
// Pin GTNHLib to the pack's version (MUI2's POM asks for 0.11.19; Gradle picks the highest).
implementation("com.github.GTNewHorizons:GTNHLib:0.11.51:dev")
```

- **Repositories.** CurseMaven is needed for NEA. The GTNH convention plugin 2.0.33 adds it (`https://cursemaven.com`, group `curse.maven`) when `includeWellKnownRepositories = true`, which AkashicWeb already sets (`gradle.properties:143`). The NEA artifact URL answered HTTP 200 from this container. [V-STATIC, V-AUTO]
- **`gradle.properties`.** Set `forceEnableMixins = true`. The buildscript's own comment reads: "Enables Mixins even if this mod doesn't use them, useful if one of the dependencies uses mixins" (`gradle.properties:128-129`). Whether `runClient` actually needs it was not tried. [INF]
- **`@Mod(dependencies=…)`.** Add `required-after:modularui2@[2.3.85,)`, plus `required-after:gtnhlib@[0.11.51,)` only if we call GTNHLib ourselves.
  - This makes MUI2 a hard dependency on **both** sides.
  - That is fine for Forbidden: MUI2 2.3.85 is on client and server (D-013, `research/07`).
  - It supersedes ADR-009's "Forge-only runtime" for GUIs, so ADR-017/018 must record it.
  - **Dev test server:** the dev Crucible server (evidence 2026-10-13) must get the MUI2 jar too, or the handshake and loading fail.

  [INF]
- **CI guards to add.**
  - Our jar must not reference classes absent on Forbidden clients: `com/gtnewhorizons/modularui/` (MUI1), `com/cleanroommc/neverenoughanimations/` and `me/eigenraven/lwjgl3ify/`. All three are on the compile classpath transitively, so IDE auto-import can pull them in.
  - `common` and `server` must not reference `com/cleanroommc/modularui/`.

  [INF]

---

## 2. API cheat sheet (compiles against 2.3.85)

### 2.1 Opening and closing a client-only screen

| API | Where | Notes |
|---|---|---|
| `new ModularScreen(String owner, ModularPanel main)` | `screen/ModularScreen.java:114-116` | `owner` = our modid; screen name = main panel name (`:133`), used for theme lookup |
| `new ModularScreen(String owner, Function<ModularGuiContext, ModularPanel>)` | `:124-126` | build lazily |
| `abstract class CustomModularScreen` + `buildUI(ModularGuiContext)` | `screen/CustomModularScreen.java:16-47` | style used by BogoSorter (§3) |
| `ClientGUI.open(ModularScreen)` / `open(screen, UISettings)` | `factory/ClientGUI.java:34-36, 83-85` | Goes to `GuiManager.openScreen` → `new GuiScreenWrapper(screen)` → `mc.displayGuiScreen` **immediately** (`factory/GuiManager.java:136-151`, `api/MCHelper.java:77-84`). The javadoc at `ClientGUI.java:30-33` says "next client tick". |
| `ClientGUI.close()` | `ClientGUI.java:99-101` | `displayGuiScreen(null)` |
| `screen.close()` | `ModularScreen.java:219-240` | Closes the main panel → `MCHelper.popScreen(openParentOnClose, parent)` (`api/MCHelper.java:59-74`) |
| `.openParentOnClose(true)` | `ModularScreen.java:795-798` | Closing returns to the screen that was open before (e.g. keyboard mode → address bar) |
| `ModularScreen.getCurrent()` / `isActive()` | `:72-78`, `:571-573` | `getCurrent()` avoids touching `IMuiScreen` (see §2.14) |

[all V-STATIC]

- **Build a new `ModularScreen` and panels on every open.**
  - `construct()` throws "ModularScreen is already constructed!" on reuse (`ModularScreen.java:157-158`).
  - Panels are disposed after close (`ModularPanel.State.DISPOSED`, `screen/ModularPanel.java:971-974`).
  - Opening the *same* instance while it is already shown is a no-op (`GuiManager.java:137-140`).

  [V-STATIC]
- **Custom wrapper `GuiScreen` (implements `IMuiScreen`).** Possible, but `GuiManager.openScreen` ignores `UISettings.customGui` for client-only screens (`GuiManager.java:142-149`). You would call `mc.displayGuiScreen(new MyWrapper(screen))` yourself. You must also call `screen.getContext().setSettings(new UISettings())` (marked `@ApiStatus.Internal`, `screen/viewport/ModularGuiContext.java:478-487`); otherwise `getUISettings()` throws (`:464-469`). Not needed for anything in this document. [V-STATIC]

### 2.2 Layout

- **`new ModularPanel(String name)`** is centred by default (`screen/ModularPanel.java:102-105`).
  - `ModularPanel.defaultPanel(name[, w, h])` gives 176×166 (`:65-71`).
  - Panel names must be unique among the open panels of a screen (`screen/PanelManager.java:93-95`).
- **Flow layout** (`widgets/layout/Flow.java`):
  - `Flow.row()` / `Flow.column()` / `Flow.col()` (`:22-32`).
  - **Defaults to `sizeRel(1f, 1f)`**, i.e. fills its parent (`:65-69`). Use `coverChildren()` / `coverChildrenHeight()` or an explicit `height()`.
  - `mainAxisAlignment(Alignment.MainAxis.START|CENTER|END|SPACE_BETWEEN|SPACE_AROUND)` (`:330`), `crossAxisAlignment(...)` (`:342`), `childPadding(int)` (`:353`), `collapseDisabledChild()` (`:361`), `wrap()` (`:402-408`), `reverseLayout()` (`:385-390`).
  - `Row` and `Column` are `@Deprecated`, scheduled for removal in 3.3.0 (`widgets/layout/Row.java:7-8`, `Column.java:7-8`).
  - `Grid` exists (`widgets/layout/Grid.java`).
- **Sizing and positioning** (all on `IPositioned`, `api/widget/IPositioned.java`):
  - Size: `size(w,h)` / `size(n)` / `width` / `height` / `widthRel` / `heightRel` / `sizeRel` / `full()` / `fullWidth()` / `fullHeight()` (`:329-432`).
  - `expanded()` takes the remaining space inside a Flow (`:93-96`).
  - Position: `pos(x,y)` / `left` / `right` / `top` / `bottom` and the `*Rel` variants (`:128-323`); `align(Alignment)` / `center()` / `horizontalCenter()` / `verticalCenter()` (`:492-505`).
  - Spacing: `padding(...)` / `margin(...)` (`:514-592`); `coverChildren*` (`:36-74`).
- **Coordinates are GUI units** (ScaledResolution).
  - `Area` extends `java.awt.Rectangle`.
  - `x`/`y` are absolute: `x = parent.x + rx` (`widget/sizer/Area.java:214`).
  - `rx`/`ry` are relative to the parent.

[all V-STATIC]

### 2.3 Widgets we need

| Need | API (2.3.85) | Where |
|---|---|---|
| Text field + validation | `new TextFieldWidget()` with `.value(IStringValue)`, `.setValidator(Function<String,String>)` (applied on focus loss), `.setPattern(Pattern)`, `.setMaxLength(int)`, `.autoUpdateOnChange(true)` (value written on every edit), `getText()`, `setText(s)` | `widgets/textfield/TextFieldWidget.java:426, 237, 232, 227, 222, 125, 135`; commit on focus loss `:166-178` |
| Text field focus and hint | `.setFocusOnGuiOpen(true)` (focus + select all in `afterInit`), `.hintText(String)`, `.setTextColor(int)`, `.setTextAlignment(Alignment)` | `widgets/textfield/BaseTextFieldWidget.java:389, 91-97, 401, 379, 352` |
| Focus API | `getContext().focus(IFocusedWidget)` / `removeFocus()` / `isFocused(w)` / `focusNext(parent)` | `ModularGuiContext.java:152-232` |
| Button | `new ButtonWidget<>()` with `.onMousePressed(mb -> {...; return true;})`, `.onMouseTapped(...)`, `.onKeyPressed(...)`, `.playClickSound(bool)`, `.overlay(IKey/IDrawable)`; `ButtonWidget.panelCloseButton()` | `widgets/ButtonWidget.java:138-171, 178, 18-30` |
| Toggle / cycle | `new ToggleButton()` with `.value(IBoolValue)` (e.g. `new BoolValue.Dynamic(getter, setter)`), `.overlay(boolean selected, IDrawable...)`, `.stateBackground(UITexture)` (e.g. `GuiTextures.CHECK_BOX`); `CycleButtonWidget` for N states | `widgets/ToggleButton.java:44-46, 77-80`; `AbstractCycleButtonWidget.java:294`; `CycleButtonWidget.java:17` |
| Scrollable list | `new ListWidget<>()` with `.child(...)` / `.children(Iterable, fn)` / `.scrollDirection(GuiAxis)` / `.childSeparator(IIcon)` / `.collapseDisabledChild()`; built-in scrolling, `defaultScrollSpeed=30` px | `widgets/ListWidget.java:34, 258-298`; `ModularUIConfig.java:10-12` |
| Dialog | `new Dialog<T>(name, Consumer<T> result)` with `.closeWith(T)`, `.setDraggable`, `.setDisablePanelsBelow` (default **true**), `.setCloseOnOutOfBoundsClick` (default false). Opened through `IPanelHandler.simple(parentPanel, (parent, player) -> dialog, subPanel)`, then `.openPanel()` / `.closePanel()` / `.togglePanel()` | `widgets/Dialog.java:7-58`; `api/IPanelHandler.java:34-77`; builder `screen/SecondaryPanel.java:103-106` |
| Tooltips | `.tooltip(t -> t.addLine(IKey.lang(...)))`, `.addTooltipLine(String/IDrawable)`, `.tooltipShowUpTimer(ticks)`, `.tooltipPos(RichTooltip.Pos)`, `.tooltipDynamic(...)` | `api/widget/ITooltip.java:64, 238-249, 177, 109, 98`; `api/drawable/IRichTextBuilder.java:49-67`; drawn in `Widget.drawForeground` (`widget/Widget.java:202-208`) |
| Slider | `new SliderWidget()` with `.bounds(min,max)`, `.value(IDoubleValue)`, `.stopper(...)`, `.setAxis(GuiAxis)`. Wheel scrolling only arrives in 2.3.92. | `widgets/SliderWidget.java:201-236` |
| Tabs | `PagedWidget.Controller c`, `new PageButton(i, c)` (`.tab(TabTexture, loc)`), `new PagedWidget<>().controller(c).addPage(w)`, `.onPageChange(IntConsumer)` | `widgets/PagedWidget.java:14-139`; `widgets/PageButton.java:22, 72` |
| Labels | `IKey.lang("key").asWidget()` → `TextWidget` with `.color(int)`, `.scale(f)`, `.shadow(b)`, `.alignment(...)`; `ScrollingTextWidget` scrolls long text on hover (page titles) | `api/drawable/IKey.java:248`; `widgets/TextWidget.java:167-190`; `widgets/ScrollingTextWidget.java:14-60` |
| Menus | `widgets/menu/DropdownWidget`, `ContextMenuButton`, `MenuPanel` (URL history, for example) | files exist; not studied in depth |
| Hide / show | `setEnabled(false)` / `disabled()` / `setEnabledIf(pred)`: a disabled widget is **not drawn and not interactive**. There is no built-in greyed-out state; use `GuiTextures.MC_BUTTON_DISABLED` as background and ignore presses. | `widget/AbstractWidget.java:230-242`; `Widget.java:648-650, 773-776`; `drawable/GuiTextures.java:141` |

[all V-STATIC]

### 2.4 Values (client-only bindings)

All are `IValue` implementations with getter/setter lambdas:

- `new StringValue(s)` and `new StringValue.Dynamic(Supplier<String>, Consumer<String>)` (`value/StringValue.java:32`)
- `BoolValue.Dynamic(BooleanSupplier, BooleanConsumer)` (`value/BoolValue.java:67`; `BooleanConsumer` is MUI2's own, `utils/BooleanConsumer.java`)
- `DoubleValue.Dynamic(DoubleSupplier, DoubleConsumer)` (`value/DoubleValue.java:78`)
- `IntValue.Dynamic(IntSupplier, IntConsumer)` (`value/IntValue.java:77`)
- `EnumValue.Dynamic<>(Class, getter, setter)`

When the field is not focused, `TextFieldWidget` re-reads its value every tick (`TextFieldWidget.java:107-115`). [V-STATIC]

### 2.5 Text, IKey and lang keys

- **Factories:**
  - `IKey.lang(key)` / `IKey.lang(key, args...)` / `IKey.lang(Supplier<String>)` (`api/drawable/IKey.java:74-117`)
  - `IKey.str(s)` (`:127`)
  - `IKey.dynamic(Supplier<String>)` (`:167`)
  - `IKey.comp(...)` (`:157`)
  - Styling: `.color(int)` / `.scale(f)` / `.shadow(b)` / `.alignment(...)` (`:287-305`)
- **`LangKey.get()`** calls `I18n.format(key, args)` once per client tick (cached by tick) and turns a literal `\n` into a newline (`drawable/text/LangKey.java:50-55`).
  - Keys live in our `assets/akashicweb/lang/{en_US,pt_BR}.lang`.
  - A `%s` with no args prints `null`; it does not raise the "Format error" seen in the evidence file. Pass args or avoid `%s`. [INF from `String.format` semantics]
- **pt-BR glyphs.** Text renders through the vanilla `FontRenderer` (`MCHelper.getFontRenderer`), so ç/ã/é render as in vanilla. [INF]

[V-STATIC unless marked]

### 2.6 Themes and textures

- **Theme selection:**
  - `screen.useTheme("vanilla" | "vanilla_dark")` (`ModularScreen.java:772-776`)
  - `panel.themeOverride(id)` (`ModularPanel.java:945-949`)
  - Global default: `ModularUIConfig.useDarkThemeByDefault` (`ModularUIConfig.java:30-31`)
- **Theme definitions:**
  - From resources: every `assets/<domain>/themes.json` (id → `"<domain>:<name>"`, plus a `"screens": {"akashicweb:address_bar": "<id>"}` map) and `assets/<domain>/themes/<name>.json` (`theme/ThemeManager.java:62-64, 185-190`; example `assets/modularui/themes.json`).
  - From Java: `IThemeApi.get().registerTheme(id, JsonBuilder)` (`api/IThemeApi.java:138`).
  - Widget theme keys: `IThemeApi.PANEL`, `BUTTON`, `TEXT_FIELD`, `TOGGLE_BUTTON`, … (`:34-74`).
- **Textures** (`drawable/UITexture.java`):
  - `UITexture.fullImage("akashicweb", "gui/x")` auto-prefixes `textures/` and suffixes `.png` (`:98-99, 126-128`).
  - Builder: `UITexture.builder().location(mod, path)` with `.fullImage()`, `.xy(...)`, `.adaptable(border)` (9-slice), `.nonOpaque()`, `.build()` (`:114, 337-598`).
  - **Without `nonOpaque()` the texture is drawn with GL_BLEND disabled** (`:177-184`, `drawable/GuiDraw.java:233-240`, `utils/Platform.java:102-110`). Soft-alpha sprites (cursor!) need `nonOpaque()`.
- **Stock textures** (`drawable/GuiTextures.java`): `MC_BACKGROUND` (`:91`), `MC_BUTTON` (`:107`), `MC_BUTTON_HOVERED` (`:125`), `MC_BUTTON_DISABLED` (`:141`), `CHECK_BOX` (`:218`), `CROSS_TINY` (`:220`).
- **Rectangle:** `new Rectangle().color(argb)` / `.cornerRadius(n)` / gradients (`drawable/Rectangle.java:45-124`).
- **Colour helper trap:** `Color.argb(int r, int g, int b, int a)` takes **r, g, b, a** despite its name (`utils/Color.java:36`).

[V-STATIC]

### 2.7 Custom drawing: live page preview and cursor sprite

**Option 1: `IDrawable` lambda.** `IDrawable` has a single abstract method `draw(GuiContext, int x, int y, int w, int h, WidgetTheme)` (`api/drawable/IDrawable.java:46-47`). Use it as `.background(...)` / `.overlay(...)`, or wrap it with `.asWidget()` / `.asIcon()` (`:147-156, 179-192`). Example: `test/GLTestGui.java:133-138`.

**Option 2: subclass `Widget<W>`** and override `draw(ModularGuiContext, WidgetThemeEntry<?>)` (`widget/Widget.java:176-177`). Before `draw`, `InternalWidgetTree.drawTree` does three things (`widget/InternalWidgetTree.java:62-77`):

- pushes the widget transform;
- applies it to GL (`context.applyToOpenGl()`);
- sets `color(1,1,1,panelAlpha)`.

So **(0,0) is the widget's top-left corner, in GUI units**.

**Helpers:**

- `Platform.setupDrawTex(int glTextureId)`: enable TEXTURE_2D and ALPHA, disable BLEND, bind (`utils/Platform.java:93-96, 102-110`).
- `GuiDraw.drawTexture(x0,y0,x1,y1,u0,v0,u1,v1)`: Tessellator quad (`drawable/GuiDraw.java:242-257`).
- `GuiDraw.drawRect(x,y,w,h,argb)`: restores state afterwards via `Platform.endDrawColor` (`:46-52`, `Platform.java:78-82`).
- `Platform.startDrawing(DrawMode, VertexFormat, Consumer<BufferBuilder>)`: Tessellator wrapper (`Platform.java:59-65, 184-212`).

**Live page preview, as in the compile-checked `PagePreviewWidget`:**

```java
public void draw(ModularGuiContext ctx, WidgetThemeEntry<?> theme) {
    Area a = getArea();
    Platform.setupDrawTex(textureId.getAsInt());   // our FrameUploader's GL texture
    GL11.glColor4f(1f, 1f, 1f, 1f);                 // MUI2's GlStateManager says "do not use outside MUI"
    GuiDraw.drawTexture(0, 0, a.w(), a.h(), 0f, 0f, u1, v1);
    if (isHovering()) CURSOR.draw(ctx, ctx.getMouseX(), ctx.getMouseY(), 8, 8, theme.getTheme());
}
```

**Cursor options:**

- **(a) Draw a sprite** in the widget, as above (`UITexture` built with `.nonOpaque()`).
- **(b) Set the OS cursor** with `Mouse.setNativeCursor(org.lwjgl.input.Cursor)`, exactly as MUI2 does for its resize cursors (`ClientProxy.java:85-103, 138-169`).
  - LWJGL 2 only: MUI2 disables this on LWJGL 3 (`:90-93`).
  - Caveat: whenever **no** widget is under the mouse, `ModularGuiContext.onFrameUpdate` calls `ClientProxy.resetCursorIcon()`, which calls `Mouse.setNativeCursor(null)` (`ModularGuiContext.java:399-405`, `ClientProxy.java:157-169`). Leaving a resizable panel's drag area resets it the same way (`ModularGuiContext.java:433`, `ClientProxy.java:138-142`). A full-screen widget avoids the first case; non-resizable panels avoid the second.

  [V-STATIC; visual result INF]

### 2.8 Raw keyboard

**Event flow in 2.3.85, per LWJGL event while any `GuiScreen` is open.** [V-STATIC]

1. Vanilla `GuiScreen.handleInput()` loops `Keyboard.next()` (`MC:net/minecraft/client/gui/GuiScreen.java:307-327`).
2. MUI2's `@Redirect` posts `KeyboardInputEvent.Pre(gui)` on `MinecraftForge.EVENT_BUS` (`core/mixins/early/minecraft/GuiScreenMixin.java:40-50`; event class `api/event/KeyboardInputEvent.java:7-36`, `@Cancelable`).
   - If it is cancelled, vanilla `handleKeyboardInput()` is skipped. That also skips F2/F11, which live in `Minecraft.func_152348_aa()` (`MC:GuiScreen.java:366-374`, `MC:Minecraft.java:2892-2970`).
3. MUI2's own handlers run (`screen/ClientScreenHandler.java`):
   - **HIGH (EARLY):** `ClientScreenHandler.onGuiInputHigh` (`:109-113`). It updates the context event state (`:122`; `GuiContext.updateEventState` stores `getEventKey` / `getEventCharacter`, `screen/viewport/GuiContext.java:74-79`).
     - It gives a **focused NEI search field** first refusal (`:123-133`; NEI only acts if focused, `NEI:TextField.java:76-77`).
     - Then, **key down:** `ModularScreen.onKeyPressed(char, key)` (`:320-323`). Overlays are tried before the screen (`doAction` → `OverlayStack.interact`, `:265-267`; `overlay/OverlayStack.java:41-53`).
     - **Key up:** `onKeyRelease(lastChar, key)`, i.e. the char of the **previous press** (`:324-331`).
     - **Release gate:** `lastChar` is reset to `null` whenever the GUI changes (`:257`). Until the first key-down in the new screen, every release, char-only ones included, is dropped by MUI2 (`:328`).
   - Inside `ModularScreen.onKeyPressed` (`screen/ModularScreen.java:441-454`):
     - All `IGuiAction.KeyPressed` listeners run first, and **cannot consume**.
     - Then each panel, top first. `ModularPanel.onKeyPressed` tries the **focused widget first**, then hovered widgets (`ModularPanel.java:518-553`).
     - A `Result` with `stops` ends it and the event is cancelled (`api/widget/Interactable.java:172-198`).
   - **LOW (LATE),** only if not cancelled (`:116-119, 340-367`):
     - Esc in-world calls `closePanelsAndScreen()`, which closes everything, dialogs included.
     - The inventory key (E) closes the top panel.
     - Ctrl+Shift+Alt+C toggles debug.
     - A release event with `key==0 && char>=' '` is sent **only** to this screen-level `keyTyped` (`:333-335`), never to widgets.
   - FML delivers cancelled events only to `receiveCanceled` handlers (`MC:cpw/mods/fml/common/eventhandler/ASMEventHandler.java:52`), and MUI2's handlers do not set it.

**Option B (recommended for keyboard mode, which must see every key): a Forge handler at HIGHEST that cancels.**

- `@SubscribeEvent(priority = EventPriority.HIGHEST) onKey(KeyboardInputEvent.Pre e)`, active when `ModularScreen.getCurrent()` is our keyboard-mode screen.
- Read the event directly: `Keyboard.getEventKey()`, `getEventCharacter()`, `getEventKeyState()`, `isRepeatEvent()`, `getEventNanoseconds()`.
- Forward to the existing `KeyboardRouter`, call `mc.func_152348_aa()`, then `e.setCanceled(true)`.
  - Call `func_152348_aa()` for **every** event, as vanilla `handleKeyboardInput` does (`MC:GuiScreen.java:366-374`). It filters key 0 and repeats itself, and it needs key-ups too: the stream push-to-talk key acts on release (`MC:Minecraft.java:2892-2970`). The first-pass sample called it on key-down only; the sample is now fixed.
- This sees exactly one call per LWJGL event, including the char-only key-0 release events and releases that MUI2's `lastChar` gate drops. See `RawInputHooks` in §2.14 and Appendix A. [V-STATIC + V-BUILD]
- **Stray key-up (INF).** If the screen is opened by a key press in-world, that key's release arrives in the new screen. The router should ignore key-ups for keys it never saw go down.

**Option C (screen-level, no Forge handler): override the `ModularScreen` key hooks.**

- `ModularScreen.onKeyPressed(char, int)` and `onKeyRelease(char, int)` are public and not final (`screen/ModularScreen.java:441, 465`). Returning `true` cancels the Forge event (`ClientScreenHandler.java:134-136`).
- Read `Keyboard.getEventCharacter()` in `onKeyRelease`; the `typedChar` parameter is the last pressed char.
- Pass Esc to `super` (or close yourself).
- **Limits:**
  - Overlays and a focused NEI search field see the key first.
  - MUI2's `lastChar` gate applies.
  - The panels' focused widgets no longer get keys unless you call `super`.
- Sample: `ScreenKeyCaptureScreen` (§2.14, Appendix A). [V-STATIC + V-BUILD]

**Option A (fine for ordinary GUI widgets): a focused widget.**

- `class KeySink extends Widget<KeySink> implements Interactable, IFocusedWidget` (`api/widget/IFocusedWidget.java:9-29`).
- Call `getContext().focus(this)` in `afterInit()`.
- Return `Result.SUCCESS` from `onKeyPressed(char, int)` and `true` from `onKeyRelease(char, int)`.
- Read the true char of the current event with `getContext().getTypedChar()` (`GuiContext.java:160-162`).
- **Limits:**
  - IGuiAction listeners, overlays and a focused NEI field still see the key first.
  - MUI2's `lastChar` gate applies, as in Option C.
  - A mouse click on a non-focusable widget removes focus (`ModularPanel.java:400-404`).
  - Returning `IGNORE` for Esc lets MUI2 close the screen.
- Modifiers: `Interactable.hasControlDown()` / `hasShiftDown()` / `hasAltDown()` (`Interactable.java:116-135`) or `Keyboard.isKeyDown`.

**Key repeat.** MUI2 calls `Keyboard.enableRepeatEvents(true)` when a MUI screen opens and `false` when the GUI closes to `null` (`ClientScreenHandler.java:235, 242`). [V-STATIC]

**lwjgl3ify.** Under lwjgl3ify, which Forbidden clients do not have, MUI2 routes text through `ModernInteractable.onTextInput(InputEvents.TextEvent)`. `BaseTextFieldWidget.onKeyPressed` then returns SUCCESS without inserting the char (`api/widget/ModernInteractable.java:12-18`; `BaseTextFieldWidget.java:300-303, 320-332`). [V-STATIC]

### 2.9 Raw mouse

| Need | API | Notes |
|---|---|---|
| Press / release / tap | `Interactable.onMousePressed(int)` (0 left, 1 right, 2 middle), `onMouseRelease(int)`, `onMouseTapped(int)` (press + release within 200 ms) | `Interactable.java:28-53`; `ModularPanel.java:73, 337-516, 1036-1038`. **On release the focused widget is tried first** (`:462`), so make a drag target `IFocusedWidget` to get releases outside it. A press that returns `SUCCESS` on an `IFocusedWidget` focuses it; any other press removes focus (`:398-402`). |
| Position in widget coordinates | `getContext().getMouseX()/getMouseY()` (un-transformed, local, int GUI units); `getAbsMouseX/Y()` (screen GUI units) | `GuiContext.java:116-146`. The panel applies the widget matrix before every callback (`ModularPanel.java:353, 368-369, 499`) and before `draw` (`InternalWidgetTree.java:62-63`). |
| Freshness | mouse x/y are refreshed **only** at `DrawScreenEvent.Pre` (`ClientScreenHandler.java:158-168`, `GuiContext.java:66-71`) | During input callbacks the position is up to one frame old. For exact page pixels use `Mouse.getEventX()/getEventY()` (the input runs inside `GuiScreen.handleInput`'s `Mouse.next()` loop) and `ScaledResolution(mc, displayWidth, displayHeight).getScaleFactor()`, with `y = displayHeight - rawY - 1`. |
| Wheel | `onMouseScroll(UpOrDown dir, int amount)` (amount = abs(dwheel)); signed raw delta via `getContext().getMouseWheel()` | `ClientScreenHandler.java:149-155`; `GuiContext.java:152-154`; `api/UpOrDown.java:3-8` |
| Drag | `onMouseDrag(int button, long msSincePress)`: only for the widget that got the press, on button-held move events | `ModularPanel.java:634-660`; `ClientScreenHandler.java:301-304` |
| Hover / move with no button | no event; poll in `draw()` (per frame) or `onUpdate()` (20 Hz); or `onMouseStartHover/EndHover/EnterArea/LeaveArea` (`api/widget/IWidget.java:107-122`); or the raw `MouseInputEvent.Pre` hook (every LWJGL mouse event, `GuiScreenMixin.java:28-38`) | For CSS `:hover` and cursor feedback, forward moves from `draw()` when the position changed |
| Not to MUI2, raw | `@SubscribeEvent(priority=HIGHEST) MouseInputEvent.Pre`, then `Mouse.getEventButton/ButtonState/DWheel/EventX/EventY`; cancel to hide from MUI2 | `api/event/MouseInputEvent.java:7-36` |

[all V-STATIC]

### 2.10 Non-pausing, non-dimming, partly transparent screen

| Requirement | How | Where |
|---|---|---|
| No pause | `ModularScreen.pausesGame` defaults to `false`; `GuiScreenWrapper.doesGuiPauseGame()` returns it. It only matters in singleplayer anyway. | `ModularScreen.java:87, 785-788`; `screen/GuiScreenWrapper.java:31-34` |
| No dimming | Call `drawDarkBackground(false)` for intent and future-proofing (`ModularScreen.java:790-793`). In 2.3.85 the gradient is only drawn via `drawDefaultBackground()` → `drawWorldBackground` → `IMuiScreen.handleDrawBackground` (`api/IMuiScreen.java:68-78`, `ClientScreenHandler.java:416-427`). For client-only `GuiScreenWrapper`, MUI2 cancels `DrawScreenEvent.Pre` (`ClientScreenHandler.java:159-168`; `MC:EntityRenderer.java:1136-1138`) and draws without ever calling `drawDefaultBackground()`; only container GUIs do (`ClientScreenHandler.java:471`). Vanilla `GuiScreen.drawScreen` itself draws no background (`MC:GuiScreen.java:57-70`). | V-STATIC; **confirm in the dev client** |
| No panel texture | `ModularPanel.fullScreenInvisible()` = `invisible().full()`. `invisible()` disables the theme and hover backgrounds, and an invisible panel cannot be hovered, so clicks on empty areas fall through. | `ModularPanel.java:925-933, 243-246`; `Widget.java:556-559` |
| Translucent bar | `Flow.row().widthRel(1f).height(14).bottom(0).background(new Rectangle().color(Color.argb(0,0,0,160)))` | V-BUILD (`KeyboardModeScreen`) |
| Avoid | `Dialog` with `disablePanelsBelow()==true` draws a 125-alpha dark rect over everything below it (`ModularScreen.java:308-310`) | V-STATIC |
| World keeps rendering | 1.7.10 renders the world, then the GUI; the TESR page stays live. In-game input (movement, Forge `MouseEvent`) is only processed when `currentScreen == null` or `allowUserInput` is set (`MC:Minecraft.java:1771-1778`), so no walking while typing. | V-STATIC |
| Close when walking away | `UISettings.canInteractWith` only applies to container GUIs (`ModularContainer.java:239-240`). Check distance in an overridden `ModularScreen.onUpdate()` (call `super`) and `close()`. | V-STATIC / V-BUILD |

### 2.11 Overlays and HoloUI

- **Overlays.**
  - The new API is `@SubscribeEvent OpenScreenEvent e` → `e.addOverlay(new ModularScreen(...))` (`screen/OpenScreenEvent.java:13-40`). Real example: `test/TestEventHandler.java:125-136`.
  - Overlays are drawn on `DrawScreenEvent.Post` above **any open GuiScreen** (`ClientScreenHandler.java:172-176`, `overlay/OverlayStack.java:60-74, 123-147`).
  - They get input before the screen (`ClientScreenHandler.java:265-267`).
  - **Not on the in-game HUD**, because no GuiScreen is open; use `RenderGameOverlayEvent` for that.
  - `OverlayManager` / `OverlayHandler` are `@Deprecated`, scheduled for removal in 3.2.0 (`overlay/OverlayManager.java:8-9`; `overlay/OverlayHandler.java:13`).
  - In 2.3.85, overlay text input under lwjgl3ify is broken (fixed in 2.3.89, commit `64ce6c9d`).

  [V-STATIC]
- **HoloUI.** `holoui/` renders a ModularScreen on a client-spawned entity in the world (`holoui/HoloUI.java:17-93`, `HoloScreenEntity.java:19-141`, `ScreenEntityRender.java:31-49`). It is marked "Highly experimental". It is render-only: no ray-cast-to-mouse input, and `drawScreen(0,0,pt)`.
  - **Not recommended** for in-world UI.
  - It confirms that MUI2's stencil clipping was designed to work with 3D transforms (`drawable/Stencil.java:15-18`).

  [V-STATIC; recommendation INF]

### 2.12 Lifecycle hooks

| Object | Hooks |
|---|---|
| `ModularScreen` | `onOpen()`: after open, before first resize; called from `onResize` when `PanelManager.tryInit()` is true (`ModularScreen.java:186-201, 206-207`) |
| | `onClose()`: last panel closed (`:212-213`, called from `PanelManager.java:229, 264`) |
| | `onUpdate()`: client tick END, **call super** (`:265-270`, driven from `ClientScreenHandler.java:178-188`) |
| | `onResize(w,h)` (`:186-201`); `drawScreen()` / `drawForeground()` overrides (`:297-352`) |
| | `registerFrameUpdateListener(widget, Runnable)`: ~60 Hz, auto-removed when the widget becomes invalid (`:700-732`) |
| | `registerGuiActionListener(IGuiAction)`: observe only (`:685-689`) |
| `ModularPanel` | `onOpen(ModularScreen)` / `onClose()` / `dispose()`, each `@MustBeInvokedByOverriders` (`ModularPanel.java:248-297`) |
| | `onCloseAction(Runnable)` (`:940-943`); BogoSorter saves config in an overridden `onClose` (§3) |
| Widget | `onInit()` / `afterInit()` (`widget/AbstractWidget.java:102, 108`); `onUpdate()` at 20 Hz, runs even when disabled (`Widget.java:572`) |
| | `onUpdateListener(Consumer)` (`:615-639`); `listenGuiAction(IGuiAction)` (`:598-607`); `dispose()` (`:121-135`) |
| | `onResized()` / `postResize()` (`IWidget.java:342-347`); hover callbacks (`IWidget.java:107-122`) |
| Screen tracking | MUI2 tracks screen changes on `GuiOpenEvent` at LOWEST (`ClientScreenHandler.java:94-97, 207-246`) |

[V-STATIC]

### 2.13 Server-synced panels (summary; probably not needed)

- **How they work:**
  - Implement `IGuiHolder<T extends GuiData>` with `buildUI(T, PanelSyncManager, UISettings)` and optionally `createScreen` (`api/IGuiHolder.java:17-41`).
  - Use a factory from `GuiFactories.tileEntity()/sidedTileEntity()/item()/entity()/playerInventory()/createSimple(...)` (`factory/GuiFactories.java:11-41`).
  - Open on the server with `GuiManager.open(factory, data, EntityPlayerMP)`, or from the client with `GuiManager.openFromClient(factory, data)` (`factory/GuiManager.java:71-133`).
  - The client gets a `GuiContainerWrapper` around a `ModularContainer`: a real container with a window id, closed by Esc/E.
  - Values sync through `PanelSyncManager` and `*SyncValue` / `InteractionSyncHandler`. Client→server writes are **refused unless the handler calls `allowC2S()`** (since #121; `value/sync/SyncHandler.java:211-235`).

  [V-STATIC]
- **Why AkashicWeb should not use them:**
  - D-016 keeps page input client-side, and URL, controller and rights already travel over our validated `SimpleNetworkWrapper` packets.
  - A synced GUI adds a server container on Crucible, with Bukkit inventory events and plugin side effects, plus a second channel.
  - Use client-only screens and our own packets.

  [INF]

### 2.14 Compile-checked sample

**Location:** `scratchpad/wave3/compile-check/src/akw/cheatsheet/`. Seven files, package `akw.cheatsheet`:

| File | What it shows | SHA-256 prefix |
|---|---|---|
| `AddressBarScreen` | Panel, Flow rows, nav buttons with disabled look, `UrlField`, Go button, Dialog via `IPanelHandler.simple`, tabs, preview page, settings page with `ToggleButton` + `SliderWidget`, `ClientGUI.open(screen, new UISettings())` | `622a9eae` |
| `UrlField` | `TextFieldWidget` subclass: Enter submits, char-only key-0 events inserted, pattern `[^\p{Cntrl}\s§]*` | `62d30ff5` |
| `PagePreviewWidget` | `Widget` + `Interactable` + `IFocusedWidget`; draws a GL texture id; event-accurate page pixels; cursor sprite (`UITexture...nonOpaque()`) | `b2c286ac` |
| `KeyboardModeScreen` | `ModularScreen` subclass, `fullScreenInvisible` + translucent hint bar, `pausesGame(false)`, `drawDarkBackground(false)`, distance auto-close in `onUpdate` | `9124fc5e` |
| `RawInputHooks` | Option B: `KeyboardInputEvent.Pre` / `MouseInputEvent.Pre` at HIGHEST; cancel; `func_152348_aa` on every event; `ModularScreen.getCurrent()` instead of `IMuiScreen` | `6b157172` |
| `ScreenKeyCaptureScreen` | Option C: `ModularScreen.onKeyPressed` / `onKeyRelease` overrides | `37da3052` |
| `KeySinkWidget` | Option A: focused key sink | `3e3531da` |

**Command:** `javac --release 8 -proc:none` (JDK 21.0.11). **Result:** exit 0, 10 classes, all major 52. [V-BUILD]

**Compile classpath (full):**

- `ModularUI2-2.3.85-1.7.10-dev.jar`
- `/home/user/akashicweb/build/rfg/recompiled_minecraft-1.7.10.jar`
- LWJGL `2.9.4-nightly-20150209`
- log4j-api `2.0-beta9-fixed`, guava 17.0, gson 2.2.4, jetbrains-annotations 13.0, netty-all 4.0.10, launchwrapper 1.12, commons-lang3 3.3.2
- fastutil 8.5.12
- GTNHLib `0.11.51:dev`
- lwjgl3ify `3.0.25:api`
- NEA `7664003`

**Reduced classpaths.** [V-BUILD]

- **First pass, without NEA and lwjgl3ify:** the only failure was the `instanceof IMuiScreen` / `((IMuiScreen) gui).getScreen()` reference in `RawInputHooks`: `cannot access IAnimatedScreen`, `cannot access InputEvents`.
- **Second pass:** `RawInputHooks` now uses `ModularScreen.getCurrent()`. All seven files compile (exit 0, 10 classes, major 52) both without NEA and lwjgl3ify, and also without GTNHLib and fastutil.
- **Rule:** do not reference `IMuiScreen` (or `getScreenWrapper()`) in our code, and keep MUI2's transitive dependencies in Gradle for `runClient` anyway.

---

## 3. How GTNH mods structure MUI2 client GUIs (real code)

### 3.1 InventoryBogoSorter `1.3.46-GTNH` (`GTNewHorizons/InventoryBogoSorter`, commit `14b265d`)

This is the closest model: it is **in the Forbidden pack** (`mods/bogosorter-1.3.46-GTNH.jar`) and builds against **exactly** `api("com.github.GTNewHorizons:ModularUI2:2.3.85-1.7.10:dev")` (`dependencies.gradle:41`). [V-STATIC]

- **`@Mod`:** `dependencies = "required-after:unimixins@[0.1.19,);required-after:gtnhlib@[0.6.1,);required-after:modularui2@[2.2.2,);"` (`BogoSorter.java:51-52`).
- **`ConfigGui extends CustomModularScreen`** (`common/config/ConfigGui.java:63`), with `buildUI(context)` (`:95`).
  - Main panel is an anonymous `ModularPanel("bogo_config")` that saves config in `onClose()` (`:98-112`).
  - `PagedWidget.Controller` + two `PageButton`s as tabs (`:114-147`).
  - `ListWidget` page (`:161`).
  - `CycleButtonWidget` bound to `BoolValue.Dynamic` (`:176-185`).
  - `TextFieldWidget` bound to `IntValue.Dynamic` with `.setNumbers(0, Short.MAX_VALUE)` and `Rectangle` background (`:270-280`).
  - `Dialog` opened via `IPanelHandler.simple(...)` (`:812-822`).
- **Opening:**
  - `ClientGUI.open(new ConfigGui(old))` (`BogoSortAPI.java:234`).
  - Also deferred: `openNextTick(GuiScreen)` stores the screen (`ClientEventHandler.java:93-95`), and the next `ClientTickEvent` (either phase) runs `ClientGUI.open(nextGui)` (`:117-120`). Use the same deferral when opening from inside an input handler; AkashicWeb's `client/net/ClientTaskQueue` fits.
  - Its main panel is `size(300, 250).center()` (`ConfigGui.java:111-112`), taller than the 240-unit minimum GUI height (§4.2). Even the in-pack reference can overflow at 1280×720 with GUI scale Auto. [V-STATIC; overflow INF]

### 3.2 GT5-Unofficial master `3a90a75` (uses MUI2 `2.3.92`, `dependencies.gradle:44`)

- **`GTModularScreen extends ModularScreen`:** a constructor that calls `useTheme(theme.getId())` (`gregtech/api/modularui2/GTModularScreen.java:18-25`). Themes are kept in `GTGuiThemes` / `GTGuiTheme`.
- **Toolbox radial menu:**
  - `ToolboxSelectGuiFactory` builds `ModularPanel.defaultPanel("ToolboxSelectUI")` + `panel.fullScreenInvisible()` + a custom widget (`gregtech/api/modularui2/ToolboxSelectGuiFactory.java:79-97`). This one is a synced open via `GuiManager.openFromClient`.
  - `RadialMenu extends Widget<RadialMenu> implements Interactable` (`gregtech/common/gui/modularui/widget/radialmenu/RadialMenu.java:32`).
  - Its `draw()` issues raw GL11 (`glBegin(GL_TRIANGLE_STRIP)`, blend, `glDisable(GL_TEXTURE_2D)` … `glEnable(GL_TEXTURE_2D)`) (`:48-176`).
  - Mouse in widget coordinates via `getContext().getMouseX()` (`:224-241`); click hit-test in `onMousePressed` (`:179-198`).
  - This is the in-pack precedent for a full-screen invisible MUI2 panel with custom GL and mouse mapping. GT5U is not in Forbidden; only the pattern is reused.

[V-STATIC]

### 3.3 MUI2's own client test GUIs (same tag 2.3.85)

- `TestGuis extends CustomModularScreen` opens each demo with `ClientGUI.open(new ModularScreen(ModularUI.ID, panel).openParentOnClose(true))` (`test/TestGuis.java:116-177`).
- Search field + filtered `ListWidget` (`:449-480`); text fields (`:740-762`); Dialog via `IPanelHandler.simple` (`:524-551`).
- `GLTestGui`: custom `IDrawable` with raw GL state toggles (`test/GLTestGui.java:133-192`).
- In a dev environment these GUIs are enabled by default: right-clicking a diamond opens them (`test/TestEventHandler.java:87-92`; `ModularUIConfig.java:39-41`).

[V-STATIC]

---

## 4. Gotchas on 1.7.10

### 4.1 GL state

- **Global, every frame, GUI or not.**
  - `ClientScreenHandler.preDraw(RenderTickEvent)` calls `glEnable(GL_STENCIL_TEST)` at START and `Stencil.reset()` at START and END (`ClientScreenHandler.java:190-196`).
  - `Stencil.reset()` does `glClear(GL_STENCIL_BUFFER_BIT)` with func ALWAYS and mask `0x00` (`drawable/Stencil.java:31-41`).
  - So the world, including our TESR, renders with stencil test on but inert. This is already true in the pack today.

  [V-STATIC]
- **Stencil buffer.**
  - MUI2 sets Forge's `stencilBits=8` in preInit (`ClientProxy.java:79-83`), after the main framebuffer was created without stencil (`MC:Minecraft.java:474, 509, 522`; `MC:Framebuffer.java:120-127`).
  - Angelica 2.2.20 overwrites `createDisplay` to always request 8 stencil bits on a core-profile context (`ANG:src/mixin/.../MixinForgeHooksClient_CoreProfile.java:44-48`, registered at `ANG:src/main/java/.../mixins/Mixins.java:45`).
  - So clipping should work on Forbidden clients. Without Angelica, clipping may silently not clip until a framebuffer re-creation. [V-STATIC; runtime INF]
- **State when `Widget.draw` is called:**
  - depth test OFF, lighting OFF, alpha test OFF at screen start (`ModularScreen.java:297-302`);
  - stencil test ON (clip);
  - matrix translated to the widget;
  - colour `(1,1,1,alpha)`;
  - TEXTURE_2D, BLEND and ALPHA left as the previous drawable set them. MUI2 helpers end with TEXTURE_2D on, ALPHA on, BLEND off (`Platform.java:67-110`, fix #147 in 2.3.80).
  - Depth is cleared between panels (`ModularScreen.java:312-313`).

  [V-STATIC]
- **Contract for our draw code:**
  - Set what you need explicitly (bind, blend).
  - Restore TEXTURE_2D on, BLEND off and colour white.
  - Leave depth off.
  - Never touch `glStencilFunc/Op/Mask`, because MUI2's clipping depends on them.
  - Push and pop any matrix change.
  - Keep the active texture unit at `GL_TEXTURE0`. MUI2 sets lightmap coords before the foreground pass (`ClientScreenHandler.java:454`).

  [INF from the above]

### 4.2 Scaling and GUI scale

- **Units.** MUI2 works in scaled GUI units: `onGuiInit` → `ModularScreen.onResize(gui.width, gui.height)` (`ClientScreenHandler.java:99-106`).
- **Minimum area.** `ScaledResolution` keeps the GUI area ≥ 320×240 units when the window allows (`MC:ScaledResolution.java:31-38`).
  - Example: 1280×720 at "Auto" gives scale 3, i.e. 426×240 units, so a 250-unit-tall panel overflows.
  - Design panels to fit 320×240, or use `widthRel` / `heightRel`.

  [V-STATIC]
- **Precision.** Mouse coordinates are integers in GUI units, so one unit is 2-4 screen pixels. Map page pixels from raw LWJGL coordinates (§2.9). [V-STATIC]

### 4.3 Angelica 2.2.20

- **GL call redirection.** Every `GL11` call in non-excluded classes, MUI2 and AkashicWeb included, is redirected to Angelica's GLSM. The exclusions are only `org.lwjgl`, `com.gtnewhorizon.gtnhlib.asm`, `com.gtnewhorizons.angelica.glsm.` and `me.eigenraven.lwjgl3ify` (`ANG:glsm/.../redirect/GLSMRedirector.java:180-185`). Cached state stays consistent as long as everything goes through `GL11`/`OpenGlHelper`. [V-STATIC]
- **Immediate mode** (`glBegin`/`glEnd`/`glVertex*`) and `glStencil*` / `glClear` are redirected too (`:203, 208, 236, 359-371`). Prefer the Tessellator (`GuiDraw` / `Platform.startDrawing`) for the page quad. [V-STATIC; preference INF]
- **Shaders (BSL/Complementary).** GUI drawing happens after the world pipeline, so a GUI page preview is a plain textured quad. [INF; test with both shader packs]
- **Core profile.** 2.2.20 requests a core-profile context (`MixinForgeHooksClient_CoreProfile`) and emulates fixed-function and the Tessellator on top of it (`ANG:src/main/java/.../mixins/Mixins.java:45, 64`). Another reason to draw through the Tessellator rather than `glBegin`. [V-STATIC; runtime INF]
- **FPS reducer and input.**
  - Angelica's mixins wrap `Mouse.next()` / `Keyboard.next()` in `GuiScreen.handleInput` and `Minecraft.runTick` and count every event as activity (`ANG:src/mixin/.../fpsreducer/MixinGuiScreen_FpsReducerInput.java:12-19`, `MixinMinecraft_FpsReducerInput.java:12-19`).
  - Because they wrap the loop condition, events that MUI2 or we cancel still count.
  - The reducer is **off by default**: `enabled = false`, `idleTimeoutMinutes = 5`, `idleFpsLimit = 30`, `unfocusedFpsLimit = 10` (`ANG:src/main/java/me/jellysquid/.../SodiumGameOptions.java:253-262`).
  - Its menu cap only applies with no world loaded (`ANG:src/mixin/.../MixinMinecraft.java:87`), so our in-world screens are not capped.
  - If a player enables it, a passive viewer of the telão drops to the idle cap after 5 minutes without input. This is outside the GUI scope, noted for the rendering research.

  [V-STATIC]

### 4.4 Text fields, ABNT2 and dead keys (LWJGL 2 on Windows)

**How LWJGL 2 delivers Windows key messages.** It handles `WM_CHAR`/`WM_SYSCHAR` but not `WM_DEADCHAR` (`LWJGL:src/java/org/lwjgl/opengl/WindowsDisplay.java:874-879, 1055-1057`). [V-STATIC]

- A `WM_CHAR` is attached to the pending key-down. If there is none, or the pending event already has a char, LWJGL emits **(key 0, state released, char)** (`LWJGL:.../WindowsKeyboard.java:158-165`).
- Unknown VKs map to `KEY_NONE` (0) (`LWJGL:.../WindowsKeycodes.java:574-575`). This covers ABNT2 `VK_ABNT_C1` ("/?" key), `VK_ABNT_C2` and `VK_OEM_102` ("\\|"), which arrive as `(0, down, char)` and type correctly.

**Resulting cases:**

| Typing | LWJGL 2 events | Stock MUI2 text field | AkashicWeb `KeyboardRouter` |
|---|---|---|---|
| ´ then a → á | (dead VK, down, 0), (KEY_A, down, 'á') | dead key → `STOP` (nothing inserted); `á` inserted (`BaseTextFieldWidget.java:277-308`). OK. | OK |
| ~ or ´ then Space → literal ~ / ´ | (KEY_SPACE, down, '~') | inserted (Space is not special-cased). OK. | **Sends a space**: the named key Space always types `" "` (`KeyboardRouter.java:65-72`, `KeyMapping.java:61, 76`): **wrong char** |
| ´ then x → "´x" | (KEY_X, down, '´'), then **(0, released, 'x')** | 'x' **lost** (release → `onKeyRelease(lastChar,0)`; `ClientScreenHandler.java:324-335`) | 'x' lost (`if (!down) return Result.NONE;` before `typed`, `KeyboardRouter.java:78`) |
| Alt+0233, IME commit, emoji (surrogate pair) | char-only (0, released, c) events | lost | lost |
| AltGr+Q → '/' (Ctrl+Alt on Windows) | (KEY_Q, down, '/') with Ctrl and Alt down | inserted: Ctrl+C/V/X/A require `!hasAltDown()` (`Interactable.java:137-151`). OK. | OK (AltGr is never a shortcut) |
| AltGr+= → '§' | (KEY_EQUALS, down, '§') | **rejected**: `BASE_PATTERN = [^§]`; paste also strips `§` (`BaseTextFieldWidget.java:47, 288, 300`) | passes as text |

Vanilla 1.7.10 `GuiScreen.handleKeyboardInput` also ignores every non-down event (`MC:GuiScreen.java:366-374`). [V-STATIC; real Windows behaviour INF]

AkashicWeb paths are `src/main/java/com/akashiic/web/client/gui/` in `/home/user/akashicweb`, read at commit `e05fee0`. Wave 2 may change these files before wave 3 starts; re-check them then.

**Fixes:**

- **MUI2 text fields:** in an `onKeyRelease` override, if `keyCode==0 && getContext().getTypedChar() >= ' '`, insert it (`UrlField`, V-BUILD).
- **Keyboard mode:** with the raw hook, treat `key==0 && ch!=0` as typed text regardless of state.
- **Named keys:** for Space and similar keys, prefer the event char when it is printable and differs from the key's default text.

[INF]

**Other text field traps** [V-STATIC]:

- **Enter does not submit.** In a single-line field it only removes focus, which commits the value (`BaseTextFieldWidget.java:230-237`). Override `onKeyPressed` for Enter.
- **Esc takes two presses.** The first Esc in a focused field removes focus and commits (or restores, if `escRestoreLastText`, default false; `ModularUIConfig.java:21-22`). The second Esc closes the screen (`BaseTextFieldWidget.java:238-244`).
- **Right-click clears the field** (`:183-184`).
- **Tab inserts `\t`.** There is no Tab focus traversal and `[^§]` accepts it. Use a pattern that excludes control characters.
- **`setPattern` is tested twice:** against the single typed char (`:300`) and against the whole resulting line (`TextFieldHandler.java:296-297, 334-342`). Use "class-star" patterns such as `[^\p{Cntrl}\s§]*`; `https?://.*` would reject every keystroke.
- **`setMaxLength` is enforced on the whole line during insert** (`TextFieldHandler.java:334-336`).
- **Multi-line paste into a single-line field is rejected entirely** (`TextFieldHandler.java:300-313`). Sanitize newlines in a Ctrl+V override for URLs.
- **Button handlers see uncommitted text.** A button's `onMousePressed` runs **before** the text field loses focus and commits its value (`ModularPanel.java:368-404`). In "Go" handlers read `textField.getText()`, not the bound value.
- **Bound values must never throw.** `TextFieldWidget.onRemoveFocus` calls `stringValue.setStringValue(...)` (`TextFieldWidget.java:166-178`). A setter that throws crashes the client. Real case: GTNH modpack issue #27170, `NumberFormatException: For input string: "3k"` from `IntSyncValue.setStringValue` via `TextFieldWidget.onRemoveFocus`; fixed consumer-side in GT5U PR #8352. [web, read 2026-10-13]
- **Default tooltip.** `TextFieldWidget.onInit` adds a tooltip showing the full text, shown only when the text overflows (`:76-87, 117-122`).

### 4.5 Input routing traps

- **Other mixins on the same path (pack versions).**
  - Hodgepodge 2.7.189 has no mixin on `GuiScreen.handleInput` / `handleKeyboardInput` / `handleMouseInput`. Its only `GuiScreen` mixin no-ops `setIngameFocus` in `keyTyped` (`MixinGuiScreen_PreventDoubleMouseGrabbing.java:18-23`, grep of tag `2.7.189`, `b9a655d`).
  - Angelica's input mixins observe only (§4.3).
  - So MUI2's `@Redirect` is the only thing that changes control flow in `handleInput` among the pack's core mods checked here. Other pack mods were not checked.

  [V-STATIC]
- **Esc** in-world closes **all** panels and the screen, a Dialog included (`ClientScreenHandler.java:348-356`).
- **The inventory key (E)** closes the top panel when no widget consumed it (`:361-365`).
- **NEI search field.** A focused NEI search field eats keys before MUI2 widgets (`ClientScreenHandler.java:123-133`). Call `Platform.unFocusRecipeViewer()` in our screen's `onOpen()` (`utils/Platform.java:51-57`). [V-STATIC; INF that it is ever focused]
- **`IGuiAction` listeners cannot consume input** (`ModularScreen.java:441-444, 685-689`).
- **F2/F11 inside MUI2 screens** only work for keys MUI2 did not consume. A raw handler that cancels must call `mc.func_152348_aa()` itself, for every event (key-ups included), as vanilla does (`MC:GuiScreen.java:366-374`, `MC:Minecraft.java:2892-2970`).
- **`GuiContext` mouse position lags input** by up to a frame (§2.9). Hover lists are rebuilt at ~60 Hz from that position, so a very fast click can hit the widget under the previous frame's position (`ModularPanel.java:117-120, 224-241`).

[V-STATIC]

### 4.6 Performance per frame

- **Per-frame work.**
  - A full widget-tree draw every frame (`InternalWidgetTree.drawTree`).
  - Each viewport (scroll lists, text fields) adds two stencil quads (`Stencil.apply/remove`).
  - Hover detection and frame listeners at a fixed ~60 Hz, with up to 20 catch-up iterations per frame at low FPS (`core/mixins/early/minecraft/MinecraftMixin.java:16-28`; `ModularPanel.java:117-120`).
  - `LangKey` is cached per tick; `IKey.dynamic` suppliers run every draw, so keep them allocation-free and never call IPC from them.

  [V-STATIC]
- **Cost.** For our GUIs (tens of widgets plus one textured quad for the page) the cost is negligible next to the frame upload (FrameUploader, separate). [INF]
- **Global stencil clears.** The two per-frame stencil clears from §4.1 are already paid by the pack. [V-STATIC]

### 4.7 Dev environment

- **Defaults differ in a dev environment.** With `isDevEnv` true, 2.3.85 defaults `guiDebugMode = true` (widget outlines and info drawn) and `enableTestGuis = true` (test block and item registered; a diamond right-click opens test GUIs) (`ModularUIConfig.java:27-28, 39-41`; `CommonProxy.java:45-48`). In the dev run's `config/modularui2.cfg` set both to `false`, so that screenshots match production and the dev client's block/item registry matches the Crucible server. [V-STATIC; registry-mismatch effect INF]
- **Runtime classpath.** MUI2 brings NEI 2.8.105, Baubles-Expanded, UniMixins and GTNHLib into `runClient` (runtime variant). [V-AUTO from the module metadata]
- **Test server mods.** Our dev Crucible test server must carry MUI2 2.3.85 once AkashicWeb `required-after`s it. [INF]

### 4.8 Known bugs and changelog around 2.3.85

**Fixed after 2.3.85**, from the git log `2.3.85-1.7.10..master` with `git tag --contains`. [V-STATIC]

| Version | Commit | Fix |
|---|---|---|
| 2.3.86 | `e8b48541` | Localize the config |
| 2.3.87 | `a1cdcd1e` | Swapping to and from the same slot |
| 2.3.88 | `6b28ad40` | Padded fluid sprites |
| 2.3.89 | `64ce6c9d` | **Overlay text input (lwjgl3ify) and tooltip draw order.** In 2.3.85, tooltips draw during tree traversal and can be overdrawn by later widgets' foreground; 2.3.89 adds `ModularGuiContext.queueForegroundRender`. |
| 2.3.90 | `c1dbdf31` | **`guiDebugMode` default → `false`** |
| 2.3.92 | `104df5f7` | Sliders scroll with the wheel |

**In 2.3.85 already** (2.3.x history): Lwjgl3ify compat (#103, 2.3.53), screen close logic and text field focus (#118, 2.3.60), dark-background option (#139, 2.3.78), text field fixes (#145, 2.3.79), and GL state restored after untextured draws (#147, 2.3.80). [V-STATIC]

**Issue tracking.** `GTNewHorizons/ModularUI2` shows 0 issues; GTNH tracks them in `GT-New-Horizons-Modpack` under label `Mod: ModularUI` (92 issues on 2026-10-13). They are mostly container/slot/sync problems in GT machine GUIs, plus #27170 above. Nothing found about client-only screens, dead keys or IME. [web, read via WebFetch]

**Static findings, not reported upstream as far as seen:**

- char-only key events lost in widgets (§4.4);
- `ClientGUI` javadoc vs behaviour (§2.1);
- `UISettings.canInteractWith` ignored for client-only screens (§2.10).

---

## 5. Recommendations for wave 3 (INF unless noted)

1. **Use MUI2 for all GUIs** as client-only screens (`ModularScreen` + `ClientGUI.open`), with our own packets for every server action. Never use synced panels (§2.13).
2. **Keyboard mode (Web Displays keyboard).**
   - Use `KeyboardModeScreen`: `fullScreenInvisible` panel, translucent hint bar, non-pausing, no dimming.
   - Capture all keys with `RawInputHooks` (KeyboardInputEvent.Pre, HIGHEST, cancel, `func_152348_aa` on every event) into the existing `KeyboardRouter`. Option C (`ScreenKeyCaptureScreen`) is the fallback if a Forge handler is unwanted. It misses releases before the first key-down (§2.8).
   - Fix the router's two LWJGL 2 cases from §4.4: char-only key-0 events, and Space carrying ´/~.
   - Ignore key-ups for keys the router never saw go down (the key that opened the screen).
   - Auto-close on distance from `onUpdate`.
3. **Address bar:**
   - `UrlField` (Enter submits, key-0 chars, newline-stripping paste, control-free pattern);
   - nav buttons with the `MC_BUTTON_DISABLED` look;
   - controller toggle;
   - confirm dialogs;
   - a **live interactive preview tab** (`PagePreviewWidget`), the "minePad" equivalent: real click, drag, wheel and hover on the page from the GUI;
   - a settings tab (volume, mode);
   - rights and friends tabs (`ListWidget` + field + buttons).

   Size to fit 320×240 GUI units.
4. **Free-cursor in-world mode (optional, beyond Web Displays):**
   - a MUI2 `fullScreenInvisible` panel with one full-screen `Interactable + IFocusedWidget`;
   - un-project the GUI cursor into a world ray (math outside R1) to drive hover, click, drag and wheel on the in-world screen;
   - draw the cursor sprite, or set a native cursor (§2.7).

   The MUI2 pieces are V-STATIC/V-BUILD; the ray math is for the interaction-model research.
5. **Correct ADR-009's rejected-alternative note** ("It hides the low-level keyboard events we need", `docs/adr/README.md:761`). It is false for 2.3.85 (§0.4, §2.8). ADR-009's "Forge-only runtime" line (`docs/adr/README.md:734`) also changes. Record the MUI2 hard dependency in ADR-017/018 and update `docs/DECISIONS.md` (D-017), `PROJECT_STATE.md` and `ISSUES_REGISTER.md` together.
6. **Build.** Apply §1.4: dependency lines, `forceEnableMixins=true` (to confirm), `@Mod required-after:modularui2@[2.3.85,)`, CI guards against MUI1/NEA/lwjgl3ify references, and MUI2 references only from client packages.
7. **Test plan seeds** (none run here):
   - ABNT2 on Windows: á, ç, ~+Space, ´+x, AltGr+Q, Alt+0233.
   - Address bar at GUI scales 2/3/4 and 1280×720.
   - Preview click accuracy against crosshair clicks.
   - Scroll-list clipping with Angelica + BSL and Complementary.
   - NEI focused-search edge case.
   - Esc/E behaviour in dialogs.
   - Keyboard mode with F2/F11.
   - Opening keyboard mode with a key bind: no stray key-up reaches the page.

---

## 6. What was run and what was not

**Run (Linux container):**

- Maven metadata, POM and `.module` fetch; downloads of the three jars; SHA-256s. [V-AUTO]
- CRC32 and size comparison of the pack's jar, using the TEOF-3.6 central directory already in the scratchpad. [V-AUTO]
- Sources jar vs git tag diff: identical. [V-AUTO]
- Class-major scan of the MUI2 jar: 857 classes, major 52. [V-AUTO]
- FML `VersionRange` checks. [V-AUTO]
- `javac --release 8` of the sample: six files in the first pass; seven files in the second pass, compiled with the full classpath, without NEA/lwjgl3ify, and without NEA/lwjgl3ify/GTNHLib/fastutil. All exit 0, 10 classes, major 52. [V-BUILD]
- Second-pass re-reads of the load-bearing citations at the `2.3.85-1.7.10` tag: `ClientScreenHandler`, `ModularScreen`, `ModularPanel` (key-release path and `Input.doRelease`), `GuiScreenWrapper`, `IMuiScreen`, `BaseTextFieldWidget`, `TextFieldWidget`, `GuiContext`, `Platform`, `GuiDraw`, `ClientProxy`, `GuiScreenMixin`, `MinecraftMixin`, the `KeyboardInputEvent` and `MouseInputEvent` classes, plus `MC:EntityRenderer`, `MC:GuiScreen`, `MC:Minecraft.func_152348_aa` and `LWJGL:WindowsKeyboard`. Line numbers were corrected where they had drifted. [V-STATIC]
- Re-hash of the three MUI2 jars (same SHA-256s) and re-fetch of `maven-metadata.xml` (latest still `2.3.92-1.7.10`, lastUpdated `20261005063833`). [V-AUTO]
- Shallow clone of Hodgepodge `2.7.189` and grep for input mixins; grep of Angelica `2.2.20` for input and FPS reducer mixins. [V-STATIC]
- CurseMaven reachability (HTTP 200). [V-AUTO]
- Web reads of GTNH modpack issues via WebFetch.

**NOT run:**

- Any Minecraft client or server with MUI2.
- Any Windows run.
- Angelica or shaders.
- An ABNT2 keyboard: the LWJGL 2 Windows event behaviour is from source reading only.
- The dark-background claim (§2.10), stencil clipping under Angelica, and cursor visuals: all from code reading only.
- A Gradle build of AkashicWeb with MUI2 added: the repo was not touched. `forceEnableMixins` necessity is untested.
- The `config/modularui2.cfg` contents in the pack were not read.

---

## 7. Sources and hashes

| Item | Value |
|---|---|
| MUI2 git | `https://github.com/GTNewHorizons/ModularUI2`, tag `2.3.85-1.7.10` (`f9cfb983…`), compared with master `104df5f7` (2.3.92) |
| MUI2 maven | `https://nexus.gtnewhorizons.com/repository/public/com/github/GTNewHorizons/ModularUI2/2.3.85-1.7.10/` |
| Pack listing | `scratchpad/teof/listing.tsv` + `tail.bin` (TEOF-3.6 central directory) |
| Pack jar | `mods/modularui2-2.3.85-1.7.10.jar`, size 1,624,381, CRC32 `63af7f03` = maven reobf jar |
| UniMixins | `https://github.com/LegacyModdingMC/UniMixins`, tag `0.3.1` (`c47090b`) |
| GTNHLib | `https://github.com/GTNewHorizons/GTNHLib`, tag `0.11.51` (`8c3c2df`) |
| NEI | `https://github.com/GTNewHorizons/NotEnoughItems`, tag `2.8.125-GTNH` (`f8d00a5`) |
| InventoryBogoSorter | `https://github.com/GTNewHorizons/InventoryBogoSorter`, tag `1.3.46-GTNH` (`14b265d`) |
| GT5-Unofficial | `https://github.com/GTNewHorizons/GT5-Unofficial`, master `3a90a75` (sparse) |
| Angelica | local clone of `/home/user/gtnewhorizons/Angelica`, tag `2.2.20` (`1babfd9`); the on-disk checkout itself is at 2.2.29 |
| LWJGL 2 | `https://github.com/LWJGL/lwjgl`, tag `lwjgl2.9.3` (`70a8746`) + `lwjgl2.9.1` for the diff |
| Forge/MC 1.7.10 | `/home/user/akashicweb/build/rfg/mcp_patched_minecraft-sources.jar`, `recompiled_minecraft-1.7.10.jar` |
| GTNH issues | `https://github.com/GTNewHorizons/GT-New-Horizons-Modpack/issues/27170`; label search `Mod: ModularUI` |
| Hodgepodge | `https://github.com/GTNewHorizons/Hodgepodge`, tag `2.7.189` (`b9a655d`) |
| AkashicWeb (read-only) | `/home/user/akashicweb` at `e05fee0`: `docs/adr/README.md:707, 734, 761`; `docs/DECISIONS.md:24` (D-017); `client/gui/KeyboardRouter.java`, `KeyMapping.java` |
| Downloaded compile deps (SHA-256) | GTNHLib-0.11.51-dev `38ce6b26…ed`; lwjgl3ify-3.0.25-api `6303b6ab…3c`; NEA 7664003 `2416310e…de`; fastutil-8.5.12 `b5543ee0…d4` |

---

## Appendix A. Compile-checked code (V-BUILD against 2.3.85)

These are verbatim copies of `scratchpad/wave3/compile-check/src/akw/cheatsheet/`, with `package` and `import` lines removed for brevity. The MUI2 imports come from `com.cleanroommc.modularui.{api, api.event, api.widget, api.drawable, drawable, factory, screen, screen.viewport, theme, utils, value, widget, widget.sizer, widgets, widgets.layout, widgets.textfield}`; the rest are Minecraft/Forge 1.7.10 (MCP names), LWJGL 2 and `org.jetbrains.annotations.NotNull`. `AddressBarScreen` (full GUI composition) and `KeySinkWidget` (Option A) are in that directory only.

### A.1 `RawInputHooks.java`

```java
/**
 * Raw LWJGL 2 input while one of our MUI2 screens is in "page capture" mode. MUI2's GuiScreenMixin posts these events
 * once per LWJGL event, before GuiScreen.handleKeyboardInput/handleMouseInput; MUI2's own handlers run at HIGH/LOW and do
 * not receive cancelled events, so cancelling here at HIGHEST hides the event from MUI2 and from vanilla.
 * Uses ModularScreen.getCurrent() instead of IMuiScreen, so it compiles without NEA/lwjgl3ify on the classpath.
 */
public final class RawInputHooks {

    /** Implemented by the keyboard-mode screen. */
    public interface RawKeySink {

        boolean capturing();

        /** One LWJGL event: down/up/repeat; ch may be 0 (dead key, named key) and key may be 0 (char-only event). */
        void key(int key, char ch, boolean down, boolean repeat, long nanos);
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(new RawInputHooks());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onKey(KeyboardInputEvent.Pre e) {
        RawKeySink sink = sink(e.gui);
        if (sink == null || !sink.capturing()) return;
        sink.key(
            Keyboard.getEventKey(),
            Keyboard.getEventCharacter(),
            Keyboard.getEventKeyState(),
            Keyboard.isRepeatEvent(),
            Keyboard.getEventNanoseconds());
        // Vanilla GuiScreen.handleKeyboardInput calls this for every event (it filters key 0 and repeats itself):
        // F2 screenshot, F11 fullscreen, stream keys. Cancelling skips vanilla, so call it here.
        Minecraft.getMinecraft().func_152348_aa();
        e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onMouse(MouseInputEvent.Pre e) {
        RawKeySink sink = sink(e.gui);
        if (sink == null || !sink.capturing()) return;
        int button = Mouse.getEventButton(); // -1 = move/wheel only
        int wheel = Mouse.getEventDWheel();
        int px = Mouse.getEventX(), py = Mouse.getEventY(); // window pixels, origin bottom-left
        // ... map (px, py) to page pixels and forward; leave e uncancelled to let MUI2 widgets see it too.
    }

    private static RawKeySink sink(GuiScreen gui) {
        if (gui != Minecraft.getMinecraft().currentScreen) return null; // event for a screen being replaced
        ModularScreen screen = ModularScreen.getCurrent(); // the wrapper's ModularScreen, or null
        return screen instanceof RawKeySink ? (RawKeySink) screen : null;
    }
}
```

### A.2 `ScreenKeyCaptureScreen.java`

```java
/**
 * Option C: screen-level capture by overriding ModularScreen's key hooks. ClientScreenHandler (EARLY phase) calls
 * onKeyPressed for key-down and onKeyRelease(lastPressedChar, key) for key-up; returning true cancels the Forge event.
 * Overlays and a focused NEI search field still get the key first. Read the true event data from Keyboard directly.
 */
public class ScreenKeyCaptureScreen extends ModularScreen {

    private final RawInputHooks.RawKeySink sink;

    public ScreenKeyCaptureScreen(ModularPanel panel, RawInputHooks.RawKeySink sink) {
        super("akashicweb", panel);
        this.sink = sink;
    }

    @Override
    public boolean onKeyPressed(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) return super.onKeyPressed(typedChar, keyCode); // LATE phase closes the screen
        sink.key(keyCode, typedChar, true, Keyboard.isRepeatEvent(), Keyboard.getEventNanoseconds());
        return true;
    }

    @Override
    public boolean onKeyRelease(char lastPressedChar, int keyCode) {
        // lastPressedChar is the char of the previous press; key-0 char-only events (dead-key fallback, IME, Alt+numpad)
        // carry their real char only in Keyboard.getEventCharacter().
        sink.key(keyCode, Keyboard.getEventCharacter(), false, false, Keyboard.getEventNanoseconds());
        return true;
    }
}
```

### A.3 `KeyboardModeScreen.java`

```java
/**
 * Web Displays-style typing: the world (and the in-world page) stays fully visible, the game does not pause, and every
 * LWJGL key event goes to the page through {@link RawInputHooks}. Only a thin translucent hint bar is drawn.
 */
public class KeyboardModeScreen extends ModularScreen implements RawInputHooks.RawKeySink {

    private final DoubleSupplier distanceToScreen;
    private final RawInputHooks.RawKeySink forward;

    public KeyboardModeScreen(DoubleSupplier distanceToScreen, RawInputHooks.RawKeySink forward) {
        super("akashicweb", buildPanel());
        this.distanceToScreen = distanceToScreen;
        this.forward = forward;
        pausesGame(false); // already the default; never pauses on a dedicated server anyway
        drawDarkBackground(false); // no gradient (client-only GuiScreenWrapper draws none in 2.3.85 anyway)
    }

    private static ModularPanel buildPanel() {
        ModularPanel panel = new ModularPanel("keyboard_mode").fullScreenInvisible(); // no panel texture, full screen
        panel.child(
            Flow.row()
                .widthRel(1f)
                .height(14)
                .bottom(0)
                .background(new Rectangle().color(Color.argb(0, 0, 0, 160))) // argb(r, g, b, a) parameter order
                .mainAxisAlignment(Alignment.MainAxis.CENTER)
                .child(IKey.lang("akashicweb.gui.keyboard.hint").asWidget().color(0xFFFFFFFF).shadow(true)));
        return panel;
    }

    @Override
    public void onUpdate() {
        super.onUpdate(); // @MustBeInvokedByOverriders
        if (distanceToScreen.getAsDouble() > 24) close(); // UISettings.canInteractWith only applies to containers
    }

    @Override
    public boolean capturing() {
        return true;
    }

    @Override
    public void key(int key, char ch, boolean down, boolean repeat, long nanos) {
        if (key == org.lwjgl.input.Keyboard.KEY_ESCAPE && down) {
            close();
            return;
        }
        forward.key(key, ch, down, repeat, nanos);
    }

    public static void open(DoubleSupplier distance, RawInputHooks.RawKeySink forward) {
        ClientGUI.open(new KeyboardModeScreen(distance, forward));
    }
}
```

### A.4 `UrlField.java`

```java
/**
 * Single-line URL field. Enter submits (stock TextFieldWidget only drops focus on Enter), and characters that LWJGL 2 on
 * Windows delivers as "key 0, released" events (second char of a dead-key fallback such as ´ then x, IME commits,
 * Alt+numpad, the low half of a surrogate pair) are inserted instead of lost.
 */
public class UrlField extends TextFieldWidget {

    /** Matches one char and the whole line: TextFieldHandler tests both (single char first, then the full line). */
    public static final Pattern URL_CHARS = Pattern.compile("[^\\p{Cntrl}\\s§]*");

    private final Consumer<String> onSubmit;

    public UrlField(Consumer<String> onSubmit) {
        this.onSubmit = onSubmit;
        setMaxLength(2048);
        setPattern(URL_CHARS);
        acceptsExpressions(false);
    }

    @Override
    public @NotNull Result onKeyPressed(char character, int keyCode) {
        if (isFocused() && (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER)) {
            onSubmit.accept(getText()); // read the live text: the bound value is only written on focus loss
            return Result.SUCCESS;
        }
        return super.onKeyPressed(character, keyCode);
    }

    @Override
    public boolean onKeyRelease(char typedChar, int keyCode) {
        // MUI2 passes the char of the last *press* here; the real char of this LWJGL event is in the context.
        char real = getContext().getTypedChar();
        if (isFocused() && keyCode == 0 && real >= ' ') {
            super.onKeyPressed(real, 0); // reuse the stock insert path (pattern, max length, marked text)
            return true;
        }
        return super.onKeyRelease(typedChar, keyCode);
    }
}
```

### A.5 `PagePreviewWidget.java`

```java
/**
 * Live page preview drawn from our own GL texture id, with mouse mapped to page pixels. Focusable so it always gets the
 * release of a drag that ends outside it (ModularPanel tries the focused widget first on release).
 */
public class PagePreviewWidget extends Widget<PagePreviewWidget> implements Interactable, IFocusedWidget {

    /** Sink for page input; the real implementation forwards to the host (D-016: client-only). */
    public interface PageInput {

        void move(double px, double py);

        void button(double px, double py, int button, boolean down);

        void wheel(double px, double py, int delta);
    }

    /** assets/akashicweb/textures/gui/cursor_arrow.png; nonOpaque() keeps GL_BLEND on for soft alpha edges. */
    public static final UITexture CURSOR = UITexture.builder()
        .location("akashicweb", "gui/cursor_arrow")
        .fullImage()
        .nonOpaque()
        .build();

    private final IntSupplier textureId;
    private final int pageW, pageH;
    private final float u1, v1; // sub-rectangle of a power-of-two texture actually holding the page
    private final PageInput input;
    private int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE;

    public PagePreviewWidget(IntSupplier textureId, int pageW, int pageH, float u1, float v1, PageInput input) {
        this.textureId = textureId;
        this.pageW = pageW;
        this.pageH = pageH;
        this.u1 = u1;
        this.v1 = v1;
        this.input = input;
    }

    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        int tex = textureId.getAsInt();
        Area a = getArea();
        if (tex > 0) {
            Platform.setupDrawTex(tex); // enableTexture2D, enableAlpha, disableBlend, bindTexture(tex)
            GL11.glColor4f(1f, 1f, 1f, 1f); // MUI2's GlStateManager is internal ("do not use outside MUI")
            GuiDraw.drawTexture(0, 0, a.w(), a.h(), 0f, 0f, u1, v1); // GL is already translated to this widget
        } else {
            GuiDraw.drawRect(0, 0, a.w(), a.h(), 0xFF101010);
        }
        if (isHovering()) {
            int mx = context.getMouseX(), my = context.getMouseY(); // local coords while drawing this widget
            CURSOR.draw(context, mx, my, 8, 8, widgetTheme.getTheme());
            if (mx != lastX || my != lastY) {
                lastX = mx;
                lastY = my;
                double[] p = pagePixel(false);
                input.move(p[0], p[1]);
            }
        }
    }

    /**
     * Exact page pixel under the mouse from raw LWJGL pixels (a GUI unit is 1-4+ screen pixels). Input callbacks run
     * inside GuiScreen.handleInput's Mouse.next() loop, so use the event position there; GuiContext's mouse position is
     * only refreshed once per frame (DrawScreenEvent.Pre) and lags input by up to a frame.
     */
    public double[] pagePixel(boolean fromEvent) {
        Minecraft mc = Minecraft.getMinecraft();
        int scale = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
        Area a = getArea(); // absolute GUI position (Area.x = parent.x + rx; no scrolling parent here)
        int rawX = fromEvent ? Mouse.getEventX() : Mouse.getX();
        int rawY = fromEvent ? Mouse.getEventY() : Mouse.getY();
        double x = rawX - a.x * (double) scale;
        double y = (mc.displayHeight - rawY - 1) - a.y * (double) scale; // LWJGL origin is bottom-left
        return new double[] { x / (a.width * scale) * pageW, y / (a.height * scale) * pageH };
    }

    @Override
    public @NotNull Result onMousePressed(int mouseButton) {
        double[] p = pagePixel(true);
        input.button(p[0], p[1], mouseButton, true);
        return Result.SUCCESS; // consume + become the pressed (and focused) widget
    }

    @Override
    public boolean onMouseRelease(int mouseButton) {
        double[] p = pagePixel(true);
        input.button(p[0], p[1], mouseButton, false);
        return true;
    }

    @Override
    public void onMouseDrag(int mouseButton, long timeSinceClick) {
        double[] p = pagePixel(true);
        input.move(p[0], p[1]);
    }

    @Override
    public boolean onMouseScroll(UpOrDown scrollDirection, int amount) {
        double[] p = pagePixel(true);
        int raw = getContext().getMouseWheel(); // signed LWJGL delta (120 per notch on Windows)
        input.wheel(p[0], p[1], raw != 0 ? raw : scrollDirection.modifier * amount);
        return true;
    }

    // IFocusedWidget
    @Override
    public boolean isFocused() {
        return getContext().isFocused(this);
    }

    @Override
    public void onFocus(ModularGuiContext context) {}

    @Override
    public void onRemoveFocus(ModularGuiContext context) {}
}
```

