# R3: Audit of AkashicWeb's client interaction and GUI code (wave 3 input)

Date: 2026-10-13 (UTC). Read-only audit of `/home/user/akashicweb`, branch `claude/clever-goodall-1t9p9u`.

**The branch head moved during the audit.**
- It started at `e05fee0`. At 19:53 UTC the M4 wave-2 fix was merged as `90875d3` ("D-016 server side, controller lock, …").
- That merge also changed the M5 input call sites (`789d9dc`): the input packets are gone, and input is now applied locally.
- **Every citation below is at `90875d3` unless marked `@e05fee0`.**
- The M5 wave-2 fix (ClientScreens, GUIs, wiring) is still being built in another worktree, so client line numbers will move again.

## Grades

| Tag | Meaning | Repository grade |
|---|---|---|
| **[V]** | VERIFIED: I read the code or doc at the cited `file:line` | STATIC |
| **[I]** | INFERRED: reasoning, or behaviour I did not observe | — |

## Path prefixes

| Prefix | Location |
|---|---|
| `AW:` | `src/main/java/com/akashiic/web/` |
| `AWT:` | `src/test/java/com/akashiic/web/` |
| `HOST:` | `host/src/` |
| `MC:` | Forge 1.7.10 MCP sources from `build/rfg/mcp_patched_minecraft-sources.jar` (extracted to `scratchpad/wave3/r3mc/`) |
| `LWJGL:` | tag `lwjgl2.9.3`, at `scratchpad/wave3/src/lwjgl2` |
| `EL:` | Electron **v44.7.0** docs, at `scratchpad/edocs/` |
| `ANG:` | Angelica tag `2.2.20` (`1babfd9`), at `scratchpad/wave3/src/angelica` |
| `R1` / `R2` | sibling wave-3 reports `r1-mui2.md` (ModularUI2) and `r2-wd-dynamics.md` (Web Displays dynamics). Their grades are theirs; I re-checked only what I cite as [V]. |

## What was not run

I ran nothing:
- no Gradle and no JUnit (Gradle would write into the read-only repository);
- no Minecraft client and no host;
- nothing on Windows, no ABNT2 keyboard, no Angelica and no shaders.

The latency figures in section 1.4 come from the lead's wave-2 integration notes, not from me.

---

## 0. Findings in short

1. **Input at `90875d3` never leaves the client (D-016 implemented minimally).**
   - Path: crosshair `MouseEvent` or keyboard GUI → `ClientScreens.click/wheel/type` → `applyLocally` → `InputTranslator.toHost` → `BrowserHost.input` → writer thread → host → `webContents.sendInputEvent`.
   - Input applies only while `ScreenState.controller` equals the hello's `playerUuid`, or on a PRIVATE screen. [V] `AW:client/screen/ClientScreens.java:409-449`
   - C2SPointer, C2SKey and S2CInput are gone. Only `C2SControl take` travels to the server, as a keepalive at most every 5 s. [V] `ClientScreens.java:92,447,469-477`; `AW:common/net/ServerBound.java:11-13`
2. **In-world mouse is "click only", as in Web Displays.**
   - A use or attack press on the picture becomes mouseDown and mouseUp at the same pixel, in the same tick, always with the LEFT button. [V] `AW:client/screen/ScreenInteraction.java:52-72`; `ClientScreens.java:413-414`
   - Nothing produces `MOVE`: there is no hover, no drag, no right or middle button, and no modifiers. [V] grep: `Kind.MOVE` is used only in `InputTranslator.java:72`.
   - Hit-testing uses `mc.objectMouseOver`, so reach is **4.5 blocks in survival and 5 in creative**. [V] `ScreenInteraction.java:77`; `MC:client/multiplayer/PlayerControllerMP.java:306-309`; `MC:client/renderer/EntityRenderer.java:352-353`
3. **The first click on a free screen is dropped.**
   - `takeIfFree` sends C2SControl, but `controller` stays null until the description packet returns, so `applyLocally` returns false. `click()` still returns true, so the MouseEvent is cancelled and nothing happens. [V] `ClientScreens.java:409-416,438-441,475-477` (the javadoc admits it: "not replayed … M5 follow-up")
   - The same silent swallow hits anyone with CLICK_TYPE while someone else holds the lock. There is no feedback. [V]
4. **Every in-world and GUI input is quantised to the 20 Hz game tick.**
   - `MouseEvent` and `GuiScreen.handleInput` both run inside `Minecraft.runTick`, which runs once per elapsed tick. Mouse look is applied per render frame. [V] `MC:client/Minecraft.java:1036-1039,1731,1776-1778`; `MC:client/renderer/EntityRenderer.java:1039-1041`
   - So presses wait up to 50 ms, while aim and hover can be sampled per frame. [I]
5. **The keyboard GUI cannot click, and the in-world pointer cannot type.**
   - `KeyboardGui` ungrabs the mouse, freezes the camera and forwards no mouse buttons. It scrolls only at the page centre (0.5, 0.5). [V] `AW:client/gui/KeyboardGui.java:79-85`; `MC:client/Minecraft.java:861,1396-1403`
   - Typing into a field is therefore: Esc, aim, click, reopen the keyboard, type. This is the biggest gap against "WD dynamics". [I]
6. **There is no hookable way to capture keys in-world in 1.7.10.**
   - Vanilla updates `KeyBinding` state before anything else, and FML's `fireKeyInput` comes after and is not cancelable. [V] `MC:client/Minecraft.java:1839,1964`
   - Opening any `GuiScreen` unpresses all keys, drops focus and ungrabs the mouse. [V] `MC:client/Minecraft.java:861,1396-1403`
   - So a WD-style "navigation mode" with free look needs one of three things [I]:
     - a GuiScreen (MUI2) that drives the camera itself;
     - a free cursor un-projected onto the screen (the 1.20 fork's approach, R2 §0.7);
     - a mixin into the `runTick` keyboard loop (UniMixins is in the pack; ADR-009 currently says no mixins).
7. **ABNT2 text that works** [V] `AW:client/gui/KeyboardRouter.java`, `KeyboardRouterTest`:
   - composed accents arrive as one char;
   - AltGr is never treated as a shortcut, so "/" and "?" on ABNT2 type normally;
   - ç is typed as text;
   - Enter sends keyDown plus the newline; Space sends keyDown plus " ";
   - Ctrl+A/Z/Y are relayed; Ctrl+V pastes from Minecraft's clipboard.
8. **ABNT2 text that is lost:**
   - dead key + Space (the accent becomes a space);
   - non-composing dead-key pairs (´ then k);
   - Alt+numpad codes;
   - emoji low surrogates.

   All of these arrive from LWJGL 2 on Windows as **key-0 / released** events, and `KeyboardRouter.java:78` drops every non-named release. [V] code, with the LWJGL event shape per R1 §4.4 and wave-2 review M5 #7.
9. **Page keyboard shortcuts cannot fire.**
   - Letters are sent only as CHAR, with no keyDown/keyUp. [V] `KeyMapping.java:8-16`, `KeyboardRouter.java:78-80`
   - So YouTube's k/j/l/f/m shortcuts and keydown-driven games and web apps see no `keydown`. Only Space (a named key) works. [I: Chromium does not fire keydown for `char` events]
   - The host already accepts a single printable ASCII `keyCode`. [V] `HOST:input.js:72-81`
10. **The paste cap chain is inconsistent.**

    | Stage | Cap | Citation |
    |---|---|---|
    | KeyboardRouter | 512 UTF-16 units | `KeyboardRouter.java:25` [V] |
    | InputTranslator | 512; longer text is **dropped**, not cut | `InputTranslator.java:24,60` [V] |
    | M2 `HostMessages` | **256, cut with `substring`** (can split a surrogate pair) | `AW:client/host/HostMessages.java:26,152` [V] |
    | Host | 4096 code points | `HOST:input.js:34` [V] |

    The effective paste is 256 units. (`@e05fee0` it was 64, through `NetCodec.MAX_TEXT`; that codec is gone.)
11. **M2 drops modifiers on mouse and wheel events.** `HostMessages.input` serialises modifiers only for keys, so Shift+click (extend a selection) and Ctrl+wheel are impossible even after the GUI sends them. [V] `AW:client/host/HostMessages.java:119-149`
12. **The host can already do far more than the client uses:**
    - `mouseMove` with held-button modifiers (drag, selection, sliders);
    - left, middle and right buttons;
    - clickCount up to 3;
    - wheel in pixels;
    - keyDown/keyUp with modifiers;
    - multi-character `char` up to 4096 code points.

    [V] `HOST:input.js:122-187`
    - Missing on the host: `mouseEnter`, `mouseLeave` and `contextMenu`, which Electron's MouseInputEvent supports; today they throw `bad-type`. [V] `HOST:input.js:183-184`; `EL:api_structures_mouse-input-event.md`
    - The host's held-button set resets only on `did-navigate`, so a lost mouseUp leaves a phantom drag. [V] `HOST:screens.js:220-222`
13. **STATE carries `cursor`, but Java ignores it.**
    - The host sends it from `cursor-changed`, using Electron's names, including `pointer`, `hand`, `text` and `default`. [V] `HOST:screens.js:40,238-241,426`; `EL:api_web-contents.md:780-789`
    - `client/screen/HostMessages.state` and `PageState` have no cursor field. [V] `AW:client/screen/HostMessages.java:42-51`; `PageState.java`
    - Electron OSR frames carry no cursor, so a WD-style cursor must be drawn by us. [I]
14. **Geometry is solid and shared.**
    - The client `ScreenGeometry` (drawing and click mapping) and the server `HitMath` (validation, and since D-016 `aimPoint` for the lock) use the same `BlockSide` bases, the 2/16 frame and clockwise rotation.
    - A test asserts agreement on 6 faces × 4 rotations. [V] `AW:client/screen/ScreenGeometry.java`, `AW:common/HitMath.java`, `AWT:client/screen/ScreenGeometryTest.java:39,99`
    - Long-range aim can reuse `HitMath.rayHit`. [V] `HitMath.java:69-82`
15. **The TESR is draw-only and conservative:**
    - full-bright lightmap, lighting off, culling off, blend off;
    - a quad lifted 0.005 block off the face;
    - no polygon offset, no mipmaps;
    - the shadow pass is skipped.

    [V] `AW:client/render/ScreenRenderer.java:62-143`
    - Risks [I]: z-fighting at telão distances; shimmering text on large far screens (LINEAR filtering without mips); the card's `glDisable(GL_TEXTURE_2D)` under shader packs (Angelica 2.2.20 never calls `setInputs`, so texture-less program selection does not follow that state [V grep, `ANG:net/coderbot/iris/pipeline/DeferredWorldRenderingPipeline.java:223,2232`]).
16. **The vanilla GUIs work but are minimal.**
    - The address bar has: URL, Go, back/forward/reload/home, Keyboard, take/release control, close.
    - It has no preview, no loading bar, no rights or configuration UI, and it shows a UUID prefix as the controller because `ClientScreen.copyOf` drops `controllerName`. [V] `AW:client/gui/AddressBarGui.java:194-202`; `AW:client/screen/ClientScreen.java:132-149`
    - Both vanilla text paths lose LWJGL char-only events, because `GuiScreen.handleKeyboardInput` calls `keyTyped` only on key-down. [V] `MC:client/gui/GuiScreen.java:366-374`
17. **AkashicFM's "house style" is not MUI2.** It uses vanilla `GuiScreen` with custom flat widgets (`FlatButton`, `FlatSlider`, `FrequencyDial`) and a GTNHLib `@Config` + `SimpleGuiFactory` config screen. [V] `/home/user/AkashicFM/src/main/java/com/akashiic/fm/client/gui/*`, GTNHLib imports. MUI2 is therefore new to the owner's stack; R1 covers it.

---

## 1. How in-world clicks, wheel and keys reach the browser today (`90875d3`)

### 1.1 Paths and threads

| Step | Class / method | Thread | Notes |
|---|---|---|---|
| LWJGL buffers a button, wheel or key event | — | OS → LWJGL queue | Windows wheel is raw `WHEEL_DELTA` (120 per notch, smaller on hi-res wheels and touchpads), no horizontal wheel. [V] `LWJGL:opengl/WindowsDisplay.java:1019-1021` (no `WM_MOUSEHWHEEL`). X11: ±120 per click. [V] `LWJGL:opengl/LinuxMouse.java:48,260,265` |
| In-world mouse: `Minecraft.runTick` loop `while (Mouse.next())` → `ForgeHooksClient.postMouseEvent()`; if cancelled, `continue` (no KeyBinding update, no hotbar scroll) | `ScreenInteraction.onMouse` | client thread, **once per tick** | [V] `MC:client/Minecraft.java:1776-1784`; `MC:net/minecraftforge/client/ForgeHooksClient.java:365-368`. Fires only when `currentScreen == null` [V] `ScreenInteraction.java:36` |
| Picture under the crosshair | `ScreenInteraction.crosshairHit` → `ClientScreens.hit` → `ScreenGeometry.pageUv` | client | Uses `mc.objectMouseOver` (block ray, reach 4.5/5) [V] `ScreenInteraction.java:76-86`, `ClientScreens.java:373-382` |
| Gesture → page action | `ClientScreens.click/wheel` | client | `click`: CLICK_TYPE check, `takeIfFree`, `ClickCounter`, DOWN then UP at once [V] `ClientScreens.java:409-416` |
| Lock gate and translation | `applyLocally` → `InputTranslator.toHost(in, requestedW, requestedH)` | client | Face (u, v) → page pixel; wheel notches × 100 px; keys → Electron names; CHAR ≤ 512 [V] `ClientScreens.java:438-449`, `InputTranslator.java:20-106` |
| Keyboard GUI | `KeyboardGui.handleKeyboardInput` → `KeyboardRouter.route` → `ClientScreens.type` | client, **once per tick** (`GuiScreen.handleInput` in `runTick`) | [V] `KeyboardGui.java:41-76`; `MC:client/Minecraft.java:1731`, `MC:client/gui/GuiScreen.java:307-324` |
| M2 queue | `BrowserHostImpl.input` (`synchronized(lock)`, clamps to page) → `HostConnection.sendInput` → bounded queue | client → `AkashicWeb-Host-Writer` | Queue 4096; **a full queue closes the whole host connection** (`WRITE_STALLED`) [V] `AW:client/host/HostConnection.java:47,312-316`; `BrowserHostImpl.java:151-158,281-290` |
| Wire | INPUT (type 36) JSON | loopback TCP | `{type:"mouseDown",x,y,button,clickCount}` with no `modifiers`; keys carry `modifiers` [V] `AW:client/host/HostMessages.java:117-154` |
| Host | `router` → `ScreenManager.input` → `InputMapper.map` → `wc.focus()` → `wc.sendInputEvent` | Electron main process | [V] `HOST:router.js:93`, `HOST:screens.js:333-341`, `HOST:input.js:144-186` |
| Frame back | `paint` → FRAME → `AkashicWeb-Host-Reader` → FrameRing3 → `FrameUploader` at RenderTickEvent START → TESR | reader → client | [V] `AW:client/screen/ClientEvents.java:80-84`, `FrameUploader.java:58-67` |
| Lock keepalive | `control(s, true)` → `ServerBound.control` → C2SControl | client → Netty | At most every 5 s while input is applied [V] `ClientScreens.java:447,469-473` |

### 1.2 Server side of the lock (only C2SControl now)

- **Take or keepalive is accepted only when all of these hold** [V] `AW:server/ScreenActions.java:216-232`; `AW:server/ActionRules.java:47-52`; `AW:server/ViewCheck.java` `canSee`:
  - rights and lock (`RightsPolicy.canTakeControl`);
  - eye in front of the face;
  - eye within `interactRange` (24);
  - the look within `controlMaxAngleDeg` (45) of `HitMath.aimPoint`;
  - unobstructed line of sight.
- A refused keepalive is quiet.
- Pre-emption: the owner or an admin may take a busy lock; others get `CONTROLLER_BUSY`. [V] `AW:common/RightsPolicy.java:65-72`
- **Release** happens on any of these [V] `AW:server/ControlLock.java` (`REACH_MARGIN = 4`, idle = `max(lastKeepalive, lastPlaying)`):
  - offline;
  - another dimension;
  - distance > `interactRange` + 4;
  - rights lost;
  - idle for `controllerIdleSeconds` (60) without a keepalive or a playing/ad media sample.
- **Consequence for the design** [I]: any client aim or interaction range larger than `interactRange` (24 by default, configurable up to 256) produces clicks the lock rules then drop. The client does not know `interactRange` or `controlMaxAngleDeg`, because S2CHello does not carry them [V] `AW:common/net/S2CHello.java:21-36`.

### 1.3 Rate limits and caps on the input path

| Where | Limit | Grade |
|---|---|---|
| Client, pointer, wheel, keys | **None.** One press gives 2 INPUTs; each LWJGL key event (including OS auto-repeat; `enableRepeatEvents(true)`) gives 1–2 | [V] `ClientScreens.java:409-431`; `KeyboardGui.java:37` |
| Client, C2SControl | keepalive ≤ 1 per 5 s per screen; but `takeIfFree` sends one on **every** click or key while the screen is free | [V] `ClientScreens.java:447,475-477` |
| Server, C2SControl | 1/s burst 2 per player (`CONTROL`) | [V] `AW:server/ActionLimits.java:22,37`; rate per the M4 integration notes |
| M2 writer queue | 4096 messages; overflow = `HOST_STALLED` for every screen | [V] see 1.1 |
| Host INPUT | no limiter (the EventLimiter covers only EVENT kinds); coordinates clamped to the page; wheel ±10000 px; ≤ 4096 code points per char event | [V] `HOST:limiter.js:1-16`, `HOST:input.js:30-34,111-120` |
| Text | 512 → 512 (dropped beyond) → 256 (cut) → 4096 | [V] finding 10 |
| Wheel | in-world: `signum × max(1, abs/120)` notches; keyboard GUI: `wheel / 120f` (proportional) | [V] `ScreenInteraction.java:47`, `KeyboardGui.java:84`. A 40-unit hi-res wheel step gives a full notch in-world, 3× too fast [I arithmetic] |
| Clicks | double and triple click: same screen, ≤ 500 ms, drift ≤ 0.01 of the face (not pixels) | [V] `ClickCounter.java:10-12` |

### 1.4 Latency (reported, not re-measured)

- **Reported by the lead's wave-2 notes, AUTOMATED (Linux, Electron 44.7.0), not re-measured by me:**
  - M2 end-to-end "click → frame 20–22 ms";
  - M1 "input RTT 16 ms".
- Add up to 50 ms of tick quantisation for presses and keys (finding 4), plus up to one render frame for the upload. [I]
- `@e05fee0` the path also crossed the server: Netty, then queued until the next server tick START, then echoed back as S2CInput, then the client task queue. [V] `@e05fee0 AW:server/ScreenActions.java:187-241`; `AW:server/ServerRuntime.java:75-79`

### 1.5 D-016 changes since `e05fee0`, and what is still open

| State | Item |
|---|---|
| Done [V] | `ClientHooks.onInput`, `ServerBound.pointer/key`, `C2SPointer`/`C2SKey`/`S2CInput` removed (ids are tombstones in `RetiredMessage`) |
| Done [V] | `S2CHello.playerUuid` used by `ClientScreens.player()` (`:388-392`) |
| Done [V] | Keepalive (`:447`) |
| Done [V] | Config: `pointerPerSecond`, `keysPerSecond`, `pointerReach`, `pointerMaxAngleDeg` removed; `controlMaxAngleDeg` 45 added; `controllerIdleSeconds` now 60 (`AW:common/WebSettings.java:37-40,76,156-158`) |
| Open (wave-2 M5 review, in flight) [V] | First-input replay (#4c) |
| Open [V] | `ClientScreen.copyOf` drops `controllerName`/`friendNames` (#4e) |
| Open [V] | `onMediaClock` ignores `live`/`liveLagMs` (`AW:client/net/ClientNetHooks.java:58-63`) |
| Open [V] | Paste caps (#4g) |
| Open [V] | Hover/drag/reach (#6) |
| Open [V] | ABNT2 char-only events (#7) |
| Open [V] | Back/forward navigating twice (#8; `ClientScreens.java:461-467` still applies them locally) |
| Open [V] | "Format error" on 48 reason texts (#5; `ScreenRenderer.java:210` still formats with no args; `pt_BR.lang` has 48 `%s` reason lines) |
| Open [V] | Click while holding an item or regaining focus (#16) |
| Stale javadoc [V] | `InputTranslator` ("Server-relayed input (S2CInput)…", `:8-11`) |
| Stale javadoc [V] | `KeyboardGui` ("to C2SKey", `:17`) |
| Stale javadoc [V] | `KeyboardRouter` ("the server applies its own length cap", `:24`) |
| Stale javadoc [V] | `ScreenInteraction` ("The server re-checks rights, reach and the ray-plane hit", `:22-23`) |

**Overlap warning for the lead:** wave-2 M5 findings #6, #7 and #16 are the core of wave 3's interaction layer. Decide whether wave 2 ships minimal fixes (recommended: #7 router fix, #16 empty-hand rule, first-click queue) and wave 3 replaces `ScreenInteraction` wholesale, so the same code is not written twice. [I]

---

## 2. In-world mouse today (`ScreenInteraction`)

| Gesture (no GUI open, aiming at the picture within 4.5/5 blocks) | What happens | Grade |
|---|---|---|
| Use (right) or attack (left) press, not sneaking | Page **left** click (down+up at once, clickCount from ClickCounter, modifiers 0); event cancelled if the player has CLICK_TYPE | [V] `ScreenInteraction.java:52-72` |
| Release | Not intercepted ("reaches vanilla untouched") | [V] `:52` |
| Sneak + use | Opens `AddressBarGui` (any player, no rights check) | [V] `:65-69` |
| Sneak + attack | Vanilla: mines the screen block | [V] `:65-70` |
| Use while the engine needs consent | Runs `ClientServices.consentOpener()`; while unwired (null) the event falls to vanilla | [V] `:57-63`; `AW:client/screen/ClientServices.java:37-46` |
| Wheel, not sneaking | Page wheel at the crosshair (u, v); hotbar scroll cancelled | [V] `:41-51` |
| Wheel, sneaking | Hotbar | [V] `:42` |
| Press on the 2/16 frame | `hit == null` → vanilla: **attack mines the screen**; use → `BlockScreen.onBlockActivated` → `onScreenActivated` (M5 does not implement it: default no-op) | [V] `ClientScreens.java:373-382`, `AW:common/net/ClientHooks.java:54-59`, `AW:common/block/BlockScreen.java:74-94` |
| Middle button, other buttons | Ignored (only the use/attack bindings are matched; `keyCode + 100` = mouse index) | [V] `:53-55` |
| Holding an item | Still a page click (contradicts BlockScreen's "let the item do its thing") | [V] `:53-72`; `BlockScreen.java:77` |
| Window regains focus with a click | The click still reaches the page (MouseEvent fires before `setIngameFocus`) | [V] `MC:client/Minecraft.java:1778,1816-1818` |
| Non-controller with CLICK_TYPE, screen busy | Event cancelled, nothing happens, no message | [V] `ClientScreens.java:409-416,440` |
| Hover, drag, right-click in the page, Shift-click, long range, cursor | Not implemented | [V] |

Two notes on Minecraft itself:
- Cancelling a press keeps vanilla's `KeyBinding` unpressed, so the held button cannot start mining or repeat use. That makes press–move–release drags safe to implement by also cancelling the release. [V] `MC:client/Minecraft.java:1778-1784,2058`
- `mc.objectMouseOver` is recomputed each frame. A per-frame aim sampler (`RenderTickEvent`, interpolated look) gives smooth hover, while button presses still arrive per tick. [V] `MC:client/renderer/EntityRenderer.java:352-353`; [I] for smoothness.

---

## 3. What the vanilla GUIs and helpers do

### 3.1 `AddressBarGui`

`AW:client/gui/AddressBarGui.java` [V]:
- **Layout.** Up to 380 px wide, centred, with three rows:
  - URL `GuiTextField` (max 2048) plus Go;
  - Back, Forward, Reload, Home;
  - Keyboard, Take/Release control, Close.

  Title line: the page title (sanitised) or "Endereço da tela". Controller line: "Controlado por: X". Error line in red. `drawDefaultBackground()` dims the world; does not pause. (`:52-79,181-192,211`)
- **Enablement** is a UX guess from `LocalRights`:
  - Go, Reload and Home need CHANGE_URL;
  - Back and Forward also need `PageState.canGoBack/Forward`;
  - Keyboard needs CLICK_TYPE;
  - Control needs a non-PRIVATE screen and either mine or CLICK_TYPE.

  Refreshed every tick. (`:86-100,103-111`) The client cannot know admin status, so admin pre-emption is invisible. [V] `RightsPolicy.java:70-71` vs `LocalRights.java`
- **Actions:**
  - Enter or Go → `UrlInput.normalize` (http/https, dotted host, adds `https://`) → C2SSetUrl, then close (`:114-124,164-178`);
  - nav buttons → C2SNav, with back, forward and stop also applied locally on the controller (finding #8, double navigation) (`ClientScreens.java:461-467`);
  - Keyboard → `KeyboardGui` (`:153`);
  - Control → C2SControl (`:156`).
- **Closes** when the screen disappears. (`:103-108`)
- **Gaps:**
  - controller name is a UUID prefix (`:194-202`, root cause `ClientScreen.copyOf`);
  - no page preview, loading state, URL history or suggestions;
  - no friends, rights, resolution, rotation or mode UI (server side exists in `C2SRights`/`RightsEditor`; there is no client GUI);
  - text-field ABNT2 char-only loss (finding 16).

### 3.2 `KeyboardGui`

`AW:client/gui/KeyboardGui.java` [V]:
- Transparent, with a 28 px bottom bar: the hint "Digitando na tela — Esc para sair", plus the URL or "Outra pessoa controla esta tela". (`:94-115`)
- Every LWJGL keyboard event goes through `KeyboardRouter`; Esc closes; Ctrl+V reads `GuiScreen.getClipboardString()`. (`:41-76`)
- Calls `mc.func_152348_aa()` on every event. That method handles the Twitch keys, **F11 fullscreen and F2 screenshot**. [V] `MC:client/Minecraft.java:2892-2970`
  - KeyMapping also maps F11 and F12 (and F1–F10) to the page, so F11 and F2 do both: Minecraft's action plus a page key. [V] `KeyMapping.java:72-74`
- Wheel scrolls at the page centre; mouse clicks do nothing. (`:79-85`)
- Closes beyond 24 blocks or when the screen disappears. (`:24,88-91`)
- Input applies only for the controller. The GUI shows the "someone else controls" line only when *another* player controls; a free screen silently drops the first keys (finding 3). [V] `:99-107`; `ClientScreens.java:426-449`

### 3.3 Other helpers

| Helper | Behaviour | Grade |
|---|---|---|
| `DoctorCommand` (`/awdoctor`) | Client command, permission level 0. Prints `ClientProxy.diagnostics()`: session, engine, host, clock, browsers per screen, upload stats, Angelica registration, task queue. English on purpose. No input counters; no M2 `host.diagnostics()` (review M5 #14) | [V] `AW:client/gui/DoctorCommand.java:16-50`; `AW:client/ClientProxy.java:124-136` |
| `ClientNotices` | Chat lines "[AkashicWeb] …", arguments sanitised (no §, no controls, 128 chars) | [V] `AW:client/gui/ClientNotices.java:14-45` |
| `LangKeys` | All M5 keys | [V] `AW:client/gui/LangKeys.java` |
| Lang files | `en_US.lang` and `pt_BR.lang` have 163 lines each (UTF-8, with "…" and "—"), including `gui.address.*`, `gui.keyboard.*`, `key.*`, `chat.*`, `placeholder.*` | [V] `assets/akashicweb/lang/pt_BR.lang:113-152` |
| Key bindings | "Teclado da tela" and "Barra de endereço da tela", both **unbound** (`KEY_NONE`); handled in ClientTick END only with no GUI open; they open for the crosshair screen or print "Mire em uma tela primeiro" | [V] `ClientProxy.java:89-92`; `ClientEvents.java:73-76,91-99` |
| Placeholders | `PlaceholderModel.resolve` precedence: server (protocol, kill switch, hello) → engine (absent, consent, installing %, failed) → host (unavailable, down) → screen failure (banner over the last frame, or a card) → budget "aproxime-se" → no URL / starting / loading → live | [V] `AW:client/screen/PlaceholderModel.java:40-92` |
| Placeholder drawing | `ScreenRenderer` draws a dark card 0x12161C with the title in 0x8FD3FF and wrapped lines (line height ≈ face height / 7), or a black band at the bottom over a stale frame | [V] `ScreenRenderer.java:47-52,145-196` |

---

## 4. Key mapping and ABNT2 (`KeyMapping`, `KeyboardRouter`)

**Design** [V] `KeyMapping.java:7-17`:
- Only *named* keys travel as key events (LWJGL code → DOM name → Electron accelerator).
- Everything else travels as text, already composed by the OS, so no layout table is needed.
- Modifier keys travel only as flags, never as key events. This is deliberate: a standalone Alt from AltGr would fire page shortcuts on every "/" typed on ABNT2.

| Case | Behaviour | Grade |
|---|---|---|
| Named keys: Enter (both), Backspace, Tab, Escape (closes the GUI instead), Space, Delete, Insert, Home, End, PgUp, PgDn, arrows, F1–F12 | keyDown/keyUp with modifier flags; Enter and Space also send their text ("\r" is dropped by the host, which synthesises its own) | [V] `KeyMapping.java:56-76`; `KeyboardRouter.java:65-71`; `HOST:input.js:170-180` |
| Composed accents (´+a → á), ç, AltGr+Q/W (/ ?), Shift letters, numpad digits with NumLock | CHAR text on key-down | [V] `KeyboardRouter.java:78-95` (behaviour on real Windows: [I], R1 §4.4) |
| `shortcut = (ctrl && !alt) || meta` | AltGr (Ctrl+Alt on Windows) never counts as a shortcut | [V] `:63` |
| Ctrl+A / Z / Y | Relayed as keyDown/keyUp `a`/`z`/`y` with modifiers | [V] `KeyMapping.java:77-80`; `KeyboardRouter.java:73-76` |
| Ctrl+V | Paste: CRLF → LF, controls dropped, ≤ 512 units, surrogate-safe, one CHAR | [V] `KeyboardRouter.java:74,101-121` |
| Ctrl+C / Ctrl+X | Never relayed. The reason ("a shared screen must not copy into anyone's clipboard") predates D-016: the copy would now hit only the controller's own OS clipboard | [V] `:15-16`; [I] on revisiting it |
| Other Ctrl/Meta letters (Ctrl+F, Ctrl+L, Ctrl+Shift+T…) | Dropped | [V] `:75-76` |
| **Char-only events (key 0, state released): dead-key fallback 2nd char, Alt+numpad, IME commit, emoji low surrogate** | **Dropped** (`if (!down) return NONE`); `pendingHigh` stays set | [V] `:78`; event shape per R1 §4.4 [V there, from LWJGL source] |
| **Dead key + Space** (to type a lone ~ ^ \` ´ ¨) | Arrives as KEY_SPACE carrying '~'; the named path sends " " | [V] `:65-71`; review M5 #7 |
| Letters/digits as key events | Never (text only), so page `keydown` shortcuts never fire | [V]; effect [I] |
| Modifier-only presses (Shift, Ctrl alone) | Never sent | [V] (by design) |
| Esc to the page (close a modal, leave fullscreen video) | Impossible: Esc always closes the GUI | [V] `:59` |
| Modifier state | Read with `Keyboard.isKeyDown` at **processing** time (per tick), not at event time | [V] `KeyboardGui.java:47-50`; [I] effect: rare stale modifiers in a burst |
| CapsLock/NumLock as modifiers | Not sent (the host would accept `capslock`/`numlock`) | [V] `HOST:input.js:24-27` |
| Typable filter | Rejects NUL, ISO controls, lone surrogates, U+FFFE/U+FFFF | [V] `KeyMapping.java:115-119` |

**Tests** [V] `AWT:client/gui/KeyMappingTest.java` (5 tests) and `KeyboardRouterTest.java` (8 tests):
- covered: named keys, letters, Enter/Space text, modifiers, AltGr, clipboard shortcuts, Esc, surrogate pairs, paste caps;
- not covered: char-only / UP events, and dead key + Space.

---

## 5. Geometry for hit-testing and drawing

- **Conventions** [V] `AW:common/BlockSide.java:5-15,203-208`:
  - `BlockSide` gives `up`, `right` and outward `forward` per Forge side;
  - `right = (−forward) × up` on every face, so the BOTTOM face is not mirrored (unlike WD);
  - blocks sit at `origin + i·right + j·up`.
- **Client `ScreenGeometry`** [V] `AW:client/screen/ScreenGeometry.java` (immutable, pure):
  - picture inset by `FRAME_INSET` 2/16;
  - lifted `SURFACE_OFFSET` 0.005 along the normal;
  - `PLANE_TOLERANCE` 0.05 for a hit on the face plane;
  - `corner(i)`, `texCoord(i)` (page rotated clockwise);
  - `faceUv` / `pageUv` (`:112-125`), `faceToPage` (`:128-139`);
  - `distanceTo`, `center`, `boundingRadius`;
  - `basisMatrix()`: column-major, x = right, y = down, z = into the face, origin = lifted top-left (`:169-175`);
  - rotation snaps to the nearest quarter turn (`:89-92`).
- **Server and shared `HitMath`** [V] `AW:common/HitMath.java`:
  - `BORDER` 2/16 (`:22`);
  - `uvOnFace` and its inverse `worldPoint` (`:42-62`);
  - `rayHit(eye, dir, maxDist)` → `{u, v, dist}`, front face only, drawable area only (`:69-82`);
  - `aimPoint` (D-016 lock test, frame included, clamped) (`:91-103`);
  - `heightAbove`, `distanceToScreen`, `lookVector`, `angleDeg`, `faceToPage`/`pageToFace`;
  - `quarterTurns` treats non-multiples as 0 (`:172`).

  The two rotation rules differ only for invalid input, which the wire and NBT reject. [V] `AW:common/net/NetCodec.java:240`, `AW:common/ScreenNbt.java:100`
- **Agreement:** `ScreenGeometryTest.agreesWithTheServerHitMathOnEveryFaceAndRotation` and `textureCornersAndHitMappingAgreeForEveryRotation`. [V] `AWT:client/screen/ScreenGeometryTest.java:39,99`
- **Missing for a WD-style layer** [I]:
  - a client ray pick beyond vanilla reach (reuse `HitMath.rayHit` against the tracked `ScreenState`s), with an occlusion check against a block ray trace;
  - a **picture vs frame** classification, so frame clicks can be swallowed instead of mining;
  - an inverse `pageToLocal(u, v)`, to draw a cursor sprite, a caret or a highlight in the TESR's face basis;
  - page-pixel (not face-fraction) click slop.
- **Render box and distance** (M4): true multiblock box plus `renderDistance = base + diagonal + 1`. [V] `AW:common/ScreenGeometry.java:19-55`

---

## 6. How the TESR draws (`ScreenRenderer`)

**Bound** to `ModContent.screenTileClass`. [V] `ClientProxy.java:86-87`

**Per tile, in order** [V] `AW:client/render/ScreenRenderer.java`:
1. Return during Angelica's shadow pass: `ShadowRenderingState.areShadowsCurrentlyBeingRendered` by MethodHandle. (`:64`; `AngelicaCompat.java:113-139`)
2. `RenderHelper.disableStandardItemLighting()`. (`:68`)
3. Lightmap 240/240 on the lightmap unit (`:69`), and `Tessellator.setBrightness(0xF000F0)` on every quad (`:46,122,135`): full-bright.
   - Vanilla had set the per-tile lightmap and colour just before. [V] `MC:client/renderer/tileentity/TileEntityRendererDispatcher.java:121-125`
4. `glDisable(GL_CULL_FACE)`; blend disabled if it was enabled. (`:70-72`)
5. Per face:
   - `glPushMatrix`, `glTranslated` to the origin block, `glMultMatrix(basisMatrix)` (`:97-103`);
   - live page: `texture.bind()` plus a Tessellator quad with rotated UVs, white (`:104-129`);
   - otherwise: a solid card with `GL_TEXTURE_2D` disabled/enabled around it (`:131-143`);
   - placeholder text with `FontRenderer` at z −0.002 (towards the viewer), scaled to ~1/7 of the face height; the banner band at z −0.001 (`:52,145-196`).
6. `finally`: `glColor4f(1,1,1,1)`, blend restored, `glEnable(GL_CULL_FACE)`, `enableStandardItemLighting()`. (`:86-91`)
   - This matches the state `RenderGlobal` sets before the TESR loop. [V] `MC:client/renderer/RenderGlobal.java:532-543`

**What is never touched:** depth function and mask, alpha test, polygon offset, texture environment, active texture unit. [V] (absent from the file)

**Depth offset:** geometric only, 0.005 block (the same as WD's 0.505 from the block centre). [V] `ScreenGeometry.java:26`

**Texture** (`ScreenTexture`) [V] `AW:client/render/ScreenTexture.java:59-123`:
- RGBA8, filled from BGRA `8_8_8_8_REV`;
- LINEAR min/mag, one mip level, CLAMP_TO_EDGE;
- uploads only at RenderTickEvent START, under a budget;
- unpack state reset after each upload.

**Costs** [V] wave-2 review M5 #17 (AUTOMATED there):
- about 192 B of garbage per screen per frame;
- card text re-wrapped every frame (`:165-171,174-178`).

**Risks and improvements** [I]:
- **z-fighting** of the picture against the block face at telão distances (no polygon offset). Options: `glPolygonOffset(-1,-1)` around the quad, or render the block's front face transparent where the picture is.
- **Moiré/shimmer** on large far screens with LINEAR filtering and no mipmaps. Optional `glGenerateMipmap` at LOD-dependent cadence.
- **Shader packs:**
  - `glDisable(GL_TEXTURE_2D)` probably does not switch Iris to an untextured program in Angelica 2.2.20 (no caller of `setInputs`, [V] grep), so the card samples whatever texture is bound × the dark colour (low visual impact). A 1×1 white texture is safer.
  - BSL/Complementary may shade the full-bright quad; an emissive entry in the pack's shader `block.properties` may be needed (owner/pack action). Check at G3.
- **Nothing drawn for interaction:** no cursor, no hover or focus highlight, no "Controlado por" strip, no loading bar. All are additive in the same face basis.

---

## 7. What the host supports for input

`HOST:input.js` (`InputMapper`, pure) [V]:

| INPUT | Host behaviour | Java sends today | Grade |
|---|---|---|---|
| `mouseMove {x,y}` | Clamped and rounded to the page; adds `leftbuttondown`/`middlebuttondown`/`rightbuttondown` for buttons it saw pressed → **drag, selection, sliders** | `HostMessages` can serialise it (`:119-120`), but nothing creates a MOVE | `input.js:139-152` |
| `mouseDown`/`mouseUp {x,y,button,clickCount}` | Buttons left/middle/right (bad → left); clickCount 1..3; updates the pressed set | Always left; clickCount from ClickCounter; **no modifiers** | `input.js:153-162`; `HostMessages.java:121-133` |
| `wheel {x,y,deltaX,deltaY}` | → `mouseWheel`, rounded, ±10000 px; deltaY > 0 = up | Notches × 100; deltaX always 0; **no modifiers** | `input.js:163-169`; `HostMessages.java:134-145` |
| `keyDown`/`keyUp {keyCode, modifiers}` | Accelerator names or single printable ASCII; DOM names mapped; non-ASCII dropped; keyDown Enter adds `char "\r"` unless Ctrl/Alt/Meta | Named keys and a/z/y with modifiers | `input.js:72-81,170-180` |
| `char {text}` | One Chromium char per code point, ≤ 4096; "\n" → Shift+"\r"; "\t" kept; other controls dropped | Up to 256 units (M2 cut) | `input.js:91-109` |
| `mouseEnter`, `mouseLeave`, `contextMenu`, `rawKeyDown`, touch, gesture | **Rejected** (`bad-type`), although Electron's InputEvent lists them | — | `input.js:183-184`; `EL:api_structures_input-event.md`, `EL:api_structures_mouse-input-event.md` |
| Wheel extras (`wheelTicksX/Y`, `hasPreciseScrollingDeltas`, `canScroll`) | Not passed | — | `EL:api_structures_mouse-wheel-input-event.md` |
| Pointer lock (`movementX/Y`) | Not passed | — | `EL:api_structures_mouse-input-event.md` |

**Side behaviours** in `HOST:screens.js` [V]:
- `wc.focus()` before every input (`:337-339`);
- `mouseUp` and `keyDown` stamp user activation, so `window.open` is allowed for 1 s after (`:335`);
- the pressed set resets on `did-navigate` only (`:220-222`);
- `RESIZE` updates the mapper size (`:370`).

**STATE.cursor:**
- Set from `wc.on('cursor-changed', (e, type))` (`:238-241`) and sent in STATE (`:426`).
- Electron's type names [V] `EL:api_web-contents.md:780-789`: `pointer, crosshair, hand, text, wait, help, *-resize, *-panning, move, vertical-text, cell, context-menu, alias, progress, nodrop, copy, none, not-allowed, zoom-in, zoom-out, grab, grabbing, custom, null, drag-drop-*, *-no-resize, default`.
- Naming trap [I]: Electron follows Chromium's `CursorType`, where `pointer` is the **arrow** and `hand` is the link hand (the opposite of CSS). Check by hovering a link in the dev host before mapping sprites.
- Java ignores it: no field in `PageState`, not parsed in `client/screen/HostMessages.state`. [V]

---

## 8. File by file: keep, replace, add

These are proposals for ADR-017/018 [I], assuming the wave-2 M5 fix lands first. The guiding split:
- MUI2 owns only *GUI screens* (address bar, configuration, consent, navigation-mode overlay);
- the in-world layer stays plain Forge events plus our own pure logic;
- every decision is a Minecraft-free class with a JUnit test.

### 8.1 `client/gui`

| File | Verdict | What |
|---|---|---|
| `AddressBarGui` | **REPLACE** (MUI2 `AddressBarScreen`) | Browser-like bar: URL field (with the ABNT2 char-only fix, see R1 A.4); back/forward/reload/home; a live page preview widget that draws the screen's GL texture (R1 §2.7) and accepts clicks through `GuiPageMapper`; loading bar from `PageState.loading`; "Controlado por <controllerName>" strip with take/release; "Navegar" button (enters navigation mode); gear → configuration panel when the player has MANAGE_* rights. Keep its logic as `AddressBarModel` (below). |
| `KeyboardGui` | **REPLACE** (navigation mode) | WD's "Keyboard hooked" crossed with the fork's mouse mode (R2 §5.3): all keys and mouse buttons go to the page, Esc leaves, Shift+Esc sends Escape to the page, a MUI2 overlay hint with no dimming. **Technical choice needed** (finding 6): (a) MUI2 screen + own camera control, (b) MUI2 screen + free cursor un-projected onto the screen, (c) a mixin into `runTick`'s keyboard loop. Keep `MAX_DISTANCE`-style exit rules in `NavigationModeRules`. |
| `KeyboardRouter` | **KEEP + FIX** (pure) | Route char-only/UP events with a typable `ch` as CHAR. Dead key + Space: send the carried char. Join a low surrogate arriving on UP. Optional keyDown/keyUp for printable ASCII keys, so page shortcuts work. Shift+Esc → page Escape. Configurable F11/F2 policy. Chunk pastes to the smallest downstream cap. |
| `KeyMapping` | **KEEP + EXTEND** (pure) | `accelerator(lwjglKey, ch)` for printable ASCII; numpad keys (with Electron's `iskeypad` modifier); an Escape-to-page entry; keep modifiers-as-flags. |
| `UrlInput` | **KEEP** (pure) | Optionally a "search terms" branch through a configured search URL. Owner decision: today a bare word is refused. |
| `LangKeys`, lang files | **KEEP + ADD** | Keys for the new GUIs, toasts, navigation mode, cursor/aim hints, config panel. pt_BR first; respect the "one %s" reason convention. |
| `ClientNotices` | **KEEP** | `sanitize` is pure. Add a short HUD toast for "Controlado por X" / "Sem permissão" instead of chat spam. |
| `DoctorCommand` | **KEEP + EXTEND** | Add input counters: applied, dropped as non-controller, dropped as no-host, queued first input, keepalives sent/refused, last cursor kind. Add `host.diagnostics()`. |

### 8.2 `client/screen`

| File | Verdict | What |
|---|---|---|
| `ScreenInteraction` | **REPLACE** with `WorldPointer` | A thin Forge adapter. `MouseEvent` handles press/release/wheel and cancels both press **and** release while a page gesture is active. `RenderTickEvent` START samples the interpolated eye/look and feeds `AimPicker` → `PointerSession`. All decisions go to `InteractionPolicy`. Empty-hand rule (or a pointer item); ignore focus-regain clicks; frame clicks swallowed unless sneaking. |
| `ClickCounter` | **KEEP + EXTEND** | Per button; slop in page pixels (desktop-like ±4 px) instead of 1% of the face; injectable interval. |
| `InputTranslator` | **KEEP** (pure) | Fix the stale javadoc. Add MOVE/LEAVE, modifiers for pointer and wheel, capture-style clamping during drags, caps aligned with M2. |
| `ClientScreens` | **KEEP** lifecycle; **MOVE OUT** input | Extract `click/wheel/type/applyLocally/takeIfFree`/keepalive into a Minecraft-free `ScreenInputController`: first-input queue, keepalive schedule, release-all on lock loss, teardown or GUI close, per-screen pointer sessions. `hit()` becomes ray-based (`AimPicker`). |
| `ClientScreen` | **KEEP** | Replace `copyOf` with `ScreenState.copy()` (keeps `controllerName`/`friendNames`). Hold per-screen pointer state, or let the controller own it. |
| `ScreenGeometry` | **KEEP + ADD** | `pageToLocal(u, v)` (inverse of `pageUv`, for the cursor sprite or caret); `classify(point)` → PICTURE/FRAME/OFF; optionally a local `rayHit`. |
| `LocalRights` | **KEEP + ADD** | `inputState(state, me)` → `DRIVE`, `MUST_TAKE`, `BUSY(controllerName)`, `NO_RIGHTS`, `PRIVATE_DRIVE`. Optionally reuse `RightsPolicy.canInput` with `admin = false`. |
| `PlayerView` | **KEEP + ADD** `AimView` | A per-frame snapshot with partial ticks (eye, look, held-item-empty, sneaking, focus). |
| `PageState`, `HostMessages` (screen) | **KEEP + ADD** | Parse `cursor` (and `textInput`/caret if the host adds them). |
| `ClientEvents` | **KEEP + CHANGE** | Default key bindings (navigation mode, address bar) after a clash check against the pack (R2 Q2); hook the aim sampler; drain stays. |
| `PlaceholderModel`, `Placeholder` | **KEEP** | The wave-2 "Format error" fix is in flight. |
| `HostBridge`, `HostAccess`, `ClientServices`, `ServerSession`, `UrlConvergence`, `BrowserBudget`, `ScreenLod`, `ScreenAudio`, `GainModel`, `ClientReason`, `WorldEvents`, `SystemMemory` | **KEEP** | Outside this audit's scope. `ServerSession` may take `interactRange`/`controlMaxAngleDeg` from the hello (section 9). |

### 8.3 `client/render`

| File | Verdict | What |
|---|---|---|
| `ScreenRenderer` | **KEEP + ADD** | Cursor sprite at the page pixel (via `pageToLocal`, drawn at the text lift); hover/focus ring in navigation mode; "Controlado por X" strip; loading bar; turn-on effect (R2 §4.5). Fix per-frame wrapping and allocations; use a white 1×1 texture for cards; consider a polygon offset. |
| `FrameUploader`, `ScreenTexture`, `UploadPlan`, `UploadBudget`, `UploadStats`, `Rect`, `TextureTarget`, `AngelicaCompat` | **KEEP** | Optional mipmaps later. |
| *new* `CursorSprites` + `CursorKind` | **ADD** | A small atlas (arrow, hand, I-beam, wait, resize, not-allowed, grab…). `CursorKind.fromElectron` is pure. Reused by the HUD crosshair in navigation mode. |
| *new* `PointerHud` | **ADD** | `RenderGameOverlayEvent.Pre(CROSSHAIRS)`: swap the crosshair for the page cursor when aiming at a drivable picture; a dim "×" or "Controlado por X" hint otherwise. |

### 8.4 `client/net`, `ClientProxy`, `common`, host and M2

| Area | Verdict | What |
|---|---|---|
| `ClientNetHooks`, `ClientTaskQueue` | **KEEP** | Pass `live`/`liveLagMs` through (wave 2). |
| `ClientProxy` | **KEEP + CHANGE** | Register `WorldPointer`/`PointerHud`; open MUI2 screens; MUI2 dependency string (R1 §1.4). Wiring of M2/M3 is wave 2. |
| `common/HitMath`, `BlockSide`, `common/ScreenGeometry` | **KEEP** | `rayHit` reused on the client. Optionally a `rayHitFace` that also reports frame hits. |
| `common/net/ScreenInput` | **MOVE or EXTEND** | It is client-only since D-016 (M2 uses only its `MOD_*` bits). Either move it to `client.input.PageInput` (owner M5) or extend it in place (`LEAVE`, buttons mask). CONTRACTS.md lists it as an M4 contract type, so it changes. |
| `common/net/ClientHooks.onScreenActivated` | **IMPLEMENT or REMOVE** | Unused by M5. A frame right-click could open the address bar or consent here. |
| `HOST:input.js` | **ADD** | `mouseEnter`/`mouseLeave` (Electron supports them); optionally `contextMenu` (no OSR menu is painted [I]); an explicit "release all" (or reset on `mouseLeave`); accept modifiers on mouse/wheel (already does when present). |
| `HOST:screens.js` + preload | **ADD (optional)** | STATE `textInput: bool` (focused element is editable), so the client can suggest or enter navigation mode, like the fork's camera-to-field. |
| M2 `HostMessages.input`, `InputEvent` | **CHANGE** | Serialise `modifiers` for mouse/wheel; types `MOUSE_LEAVE`/`MOUSE_ENTER`; `MAX_CHAR_TEXT` aligned and surrogate-safe. |

---

## 9. Contract types that would change

| Contract (owner) | Change | Why |
|---|---|---|
| `common.net.ScreenInput` (M4 → M5) | Move to client or extend: `Kind.LEAVE` (and `ENTER`), pointer modifiers honoured, optional `buttons` mask | Hover, drag, leave; no longer wire (D-016) [V] `ScreenInput.java:3-7` |
| `client.host.InputEvent` (M2) | `MOUSE_ENTER`/`MOUSE_LEAVE` types; modifiers serialised for pointer and wheel | finding 11; host change below |
| `client.host.HostMessages` caps (M2) | `MAX_CHAR_TEXT` = min(downstream) and surrogate-safe; or chunk in the router | finding 10 |
| PROTOCOL.md §1 INPUT (M1/M2) | Add `mouseEnter`, `mouseLeave` (optional `contextMenu`); document modifiers on mouse/wheel; document "pressed set resets on leave / release-all" | [V] host gaps, section 7 |
| PROTOCOL.md §1 STATE (M1) | `cursor` is already there; Java must parse it. Optional `textInput` (and caret rect) | [V] section 7 |
| `S2CHello` (M4) | Append `interactRange`, `controlMaxAngleDeg` (and optionally `admin` for UX) | Client aim limits must match the lock rules (section 1.2); appended fields are forward-compatible [V] `S2CHello.java:51-65` |
| `ClientHooks` (M4) | `onHello(..., limits)` if S2CHello grows; `onScreenActivated` used or dropped | — |
| `ServerBound` (M4) | Unchanged (`control(take)` = keepalive) | [V] `ServerBound.java:23-27` |
| `ScreenState` (M4) | Unchanged; client copies must use `copy()` | [V] `ScreenState.java:41-70` |
| CONTRACTS.md "Cross-cutting" | MUI2 (and possibly UniMixins/GTNHLib) become client hard dependencies: ADR-009 amendment | D-017; R1 §1.4 |
| PROTOCOL.md §2 (optional, D-016 amendment) | A low-rate "controller pointer" broadcast for spectators of the telão (R2 Q1) | Owner/lead decision; it relays pointer *position* only |

---

## 10. Client-side testability hooks (pure functions)

All of these are Java 8, Minecraft-free and LWJGL-free (LWJGL constants copied as KeyMapping already does). Each gets a JUnit test. The existing doubles `FakeHost` and `RecordingServer` cover the controller and host seams. [V] `AWT:client/screen/FakeHost.java`, `RecordingServer.java`

1. **`AimPicker.pick(Collection<ScreenState> faces, double[] eye, double[] dir, double maxRange, double blockHitDist)`** → `Pick{key, u, v, dist, kind = PICTURE|FRAME}` or null.
   - Uses `HitMath.rayHit` for the picture and a frame-inclusive variant.
   - Occluded when `blockHitDist < dist − ε`.
   - Tests: six faces × four rotations against `pageUv`; nearest of two screens; behind-the-face rejection; occlusion; range limit.
2. **`InteractionPolicy.decide(Ctx)`** → `PAGE_PRESS | PAGE_RELEASE | PAGE_WHEEL | OPEN_ADDRESS_BAR | OPEN_CONSENT | VANILLA | SWALLOW(hintKey)`.
   - `Ctx`: button, pressed, sneaking, held-item-empty, `inGameHasFocus`, hit kind, engine state, `inputState` (from `LocalRights`), navigation mode.
   - A decision table, tested exhaustively; it replaces the `if` chain in `ScreenInteraction.onMouse`.
3. **`PointerSession`**, one per screen, a state machine:
   - inputs: `onAim(Pick|null, nowMs)`, `onButton(btn, down, nowMs)`, `onWheel(dwheel, shift)`, `onCancel(reason)`;
   - output: an ordered list of page events.
   - **Invariants under test:**
     - every down gets exactly one up, including on cancel (GUI opened, lock lost, screen gone, world unload, focus loss);
     - while a button is held, moves keep going with coordinates clamped to the page (capture);
     - without a button, aim leaving the picture gives one LEAVE;
     - at most one MOVE per frame, only when the page pixel changes;
     - clickCount comes from the extended `ClickCounter`.
4. **`WheelAccumulator.add(int dwheel, boolean shift)`** → `(dxPx, dyPx)`.
   - Proportional (120 units = 100 px), accumulates sub-pixel remainders, Shift → horizontal, clamps.
   - Tests: 120/240 on X11 and Windows; 8- and 40-unit hi-res steps; sign; the flood cap.
5. **`ClickCounter`** (extended): per button, page-pixel slop, interval. Tests for double and triple click, a move beyond the slop, another screen, another button.
6. **`KeyboardRouter.route`** (fixed) plus a **`LwjglKeyScript`** fixture of recorded event shapes:
   - Windows: dead key + vowel, dead key + Space, dead key + consonant (char-only UP), AltGr+Q, Alt+numpad 0231, emoji (high on down, low on UP), key repeat;
   - X11 equivalents.
   - Asserts the exact output events.
7. **`KeyMapping.accelerator(int lwjglKey, char ch)`** and **`ModifierSnapshot.of(lshift, rshift, lctrl, rctrl, lalt, ralt, lmeta, rmeta)`** → mods plus an `altGr` flag. Tested as tables.
8. **`PasteChunker.chunks(String, int maxUnits)`**: surrogate-safe splits; property test that concatenation equals the input and no chunk ends in a high surrogate.
9. **`CursorKind.fromElectron(String)`** → sprite id. Total over the documented names (unknown → ARROW); pins the `pointer`/`hand` semantics once observed.
10. **`ScreenGeometry.pageToLocal(u, v)`** and **`cursorQuad(u, v, sizePx, pageW, pageH)`**: round-trip with `pageUv` for every face and rotation; the cursor stays upright and inside the picture.
11. **`GuiPageMapper.toPage(mouseX, mouseY, widgetX, widgetY, widgetW, widgetH, pageW, pageH)`** → page pixel or null (letterboxed fit). Tests: aspect ratios, edges, outside the widget, scaled GUI.
12. **`ControlAim.ok(state, eye, look, interactRange, maxAngleDeg)`**: the server's `ViewCheck.canSee` minus line of sight, built from `HitMath.aimPoint`/`angleDeg`. The client pre-checks before sending a keepalive or take, so refusals stop being silent. Tested against `ViewCheck` on the same inputs (they must agree).
13. **`KeepaliveSchedule.due(nowMs, lastTakeMs, lastInteractionMs, aimOk)`** and **`FirstInputQueue`**:
    - buffer gestures while `MUST_TAKE`;
    - flush when `controller == me`;
    - drop on `CONTROLLER_BUSY` or after 1 s;
    - always release any held button on drop.

    Tests with an injected clock.
14. **`NavigationModeRules.shouldExit(ctx)`**: Esc (but not Shift+Esc), distance > range, lock lost, screen gone, dimension change, damage taken, another GUI opened. Exit always emits release-all.
15. **`AddressBarModel.of(ScreenState, PageState, me)`** → enabled flags, title, controller line (`controllerName`, never a UUID prefix), error text keys. This makes the MUI2 screen a thin binding.
16. **Existing pure classes to keep as they are:** `InputTranslator`, `UrlInput`, `LocalRights`, `PlaceholderModel`, `ClientNotices.sanitize`, `HostMessages` (screen), `ScreenGeometry`, `HitMath`.
17. **QA hooks:**
    - `/awdoctor input` counters (section 8.1);
    - a debug toggle that draws the aim point and page pixel on the screen (gives G3 screenshots evidence);
    - a headless "input replay" test that drives `ScreenInputController` + `FakeHost` from a script and asserts host INPUT JSON. The real-host variant can reuse M2's `RealHostEndToEndTest` harness.

---

## 11. Risks and open points for ADR-017/018

1. **The navigation-mode mechanism** (finding 6) decides whether ADR-009's "no mixins" stays. To check in a dev client [I]:
   - whether a GuiScreen can keep the mouse grabbed and drive `player.rotationYaw/Pitch` from `Mouse.getDX/DY` without vanilla fighting it;
   - whether MUI2's cancelable `KeyboardInputEvent.Pre`/`MouseInputEvent.Pre` (R1 §0.4) is enough.
2. **Aim range vs lock range.**
   - The client must not offer interaction beyond `interactRange` or outside the 45° look cone, or keepalives are refused silently and the lock idles out after 60 s.
   - Either send the limits in S2CHello or use the same constants.
   - For the telão, the owner can raise `interactRange`. [V] section 1.2; [I] for UX.
3. **Hover only for the controller.** A non-controller's local hover would diverge its own page: YouTube hover-to-play previews and their audio. Recommend no page events at all for non-controllers. [I]
4. **Phantom held buttons.** Any path that sends DOWN without UP (GUI opens mid-drag, lock lost, host restart) leaves the host's pressed set dirty until the next navigation.
   - Fix it in `PointerSession` (always balance) **and** on the host (`mouseLeave`/release-all).
   - [V] `HOST:screens.js:220-222`; [I] effect.
5. **Writer-queue stall.** Hover at 60 Hz × 4 browsers is fine, but an unthrottled move flood (a bug) would close the whole host connection. Throttle in `PointerSession`, and have M2 coalesce MOVE in its queue (latest wins). [V] `HostConnection.java:312-316`; [I] proposal.
6. **F11/F2 double action** in typing modes: decide whether Minecraft's own hotkeys win. [V] section 3.2
7. **Ctrl+C/X under D-016:** copying from the page into the controller's own clipboard is now harmless. Revisit the block (`KeyboardRouter.java:15-16`). [I]
8. **Shaders and visuals:** z-fighting at distance, moiré, full-bright under BSL/Complementary, and the card texture state. All to check at G3. [I]
9. **Overlap with wave 2** (section 1.5): avoid re-implementing #6, #7 and #16 twice.

---

## 12. Sources and what I did

- **Read**, at `90875d3` (and `e05fee0` for the pre-merge path):
  - all of `AW:client/{gui,screen,render,net}` that touches input, GUIs or drawing, plus `ClientProxy`;
  - `AW:common/{HitMath,BlockSide,ScreenGeometry,RightsPolicy,ScreenState,WebSettings}`;
  - `AW:common/net/{ScreenInput,ServerBound,ClientHooks,S2CHello,C2SControl,NetCodec}`;
  - `AW:common/block/BlockScreen`;
  - `AW:server/{ScreenActions,ActionRules,ViewCheck,ControlLock,ActionLimits,ServerRuntime}`;
  - `AW:client/host/{InputEvent,HostMessages,BrowserHostImpl,HostConnection}`;
  - `HOST:{input,screens,router,limiter}.js`;
  - lang files; the related tests (names and coverage);
  - docs: PROJECT_STATE, DECISIONS, ADR-012 with the D-016 amendment, PROTOCOL, CONTRACTS, and the Crucible/dev-client evidence;
  - wave-1 M5 report, wave-2 M5 review, wave-2 integration checklist;
  - R1 and R2 (headline sections only).
- **External sources read:**
  - MC 1.7.10 sources (`Minecraft`, `GuiScreen`, `EntityRenderer`, `PlayerControllerMP`, `TileEntityRendererDispatcher`, `RenderGlobal`, `ForgeHooksClient`);
  - LWJGL 2.9.3 (`WindowsDisplay`, `WindowsMouse`, `LinuxMouse`, `Mouse`);
  - Electron v44.7.0 docs (`web-contents.md`, the InputEvent structures);
  - Angelica 2.2.20 (`IrisSamplers`, `DeferredWorldRenderingPipeline`);
  - AkashicFM's client GUI package.
- **Not run:** any build, test, game, host or Windows check (see the top of this file). No file in `/home/user/akashicweb` was modified. Scratch output: this file and `scratchpad/wave3/r3mc/` (extracted MC sources).
