# R5: Free mouse cursor over an in-world screen (Minecraft 1.7.10, LWJGL 2)

Wave 3 research, 2026-10-13. Read-only on the repository; nothing was built or run in Minecraft.

**Scope.** The player opens a non-pausing, transparent GUI. The OS cursor is released, the view stays still, and moving the mouse over the screen's quad in the 3D view moves the page's mouse: hover, click, drag and wheel, like a real monitor in the world. This note covers four things:
- the math (camera matrices, unprojection, ray against the face, precision);
- the engineering (frame timing, input pipeline, drawing the page cursor, edge cases, performance);
- a comparison with crosshair-as-cursor and camera-locked alternatives;
- pseudo-code for the implementation.

**Grades.** VERIFIED means I read the code or doc (cited) or ran it. INFERRED means reasoning or design.

**Path abbreviations.**

| Prefix | Location |
|---|---|
| `MC:` | Forge 10.13.4.1614 patched sources, `/home/user/akashicweb/build/rfg/minecraft-src/java/net/minecraft/...` (and `.../net/minecraftforge/...`) |
| `AW:` | `/home/user/akashicweb/src/main/java/com/akashiic/web/...` |
| `ANG:` | Angelica tag `2.2.20` in `/home/user/gtnewhorizons/Angelica`, read with `git show 2.2.20:<path>` |
| `LW2:` | LWJGL 2 tag `lwjgl2.9.1` in `scratchpad/wave3/src/lwjgl2` |
| `L3:` | lwjgl3ify HEAD 3.0.37, `/home/user/gtnewhorizons/lwjgl3ify` |
| `WD:` | montoyo Web Displays, `/home/user/montoyo/webdisplays/src/main/java/net/montoyo/wd` |
| `FK:` | 1.20 fork, `/home/user/mysticpasta1/webdisplays/src/main/java/net/montoyo/wd` |
| `EL:` | `electron@44.7.0` npm package, `electron.d.ts` (fetched to `scratchpad/wave3/src/electron-44/package/electron.d.ts`) |
| `MUI2:` | ModularUI2 2.3.85 source, `scratchpad/wave3/src/mui2-2.3.85/src/main/java/com/cleanroommc/modularui` |
| `HP:` | Hodgepodge 2.7.189 source, `scratchpad/wave3/src/hodgepodge` |

The numeric model is `scratchpad/wave3/r5check/cursor_math_check.py` (pure Python, run with `python3 -I`). Its output is in Appendix A.

---

## 0. Answers in short

1. **Exact matrices.** Read `ActiveRenderInfo`'s private static `modelview` and `projection` FloatBuffers (SRG `field_74594_j` / `field_74595_k`). The game fills them once per world pass, right after `setupCameraTransform`. So they hold everything the player sees:
   - FOV with sprint, potion, water, death and Forge `FOVUpdateEvent` changes;
   - view bobbing, hurt cam, nausea and third person.
   
   Angelica 2.2.20 copies the same buffers into its `RenderingState`. Its GL state manager answers `glGetFloat` from its own Java-side matrices. The **camera position** is the interpolated render-view entity position, the same value as `TileEntityRendererDispatcher.staticPlayerX/Y/Z`, which both TESR offsets and Angelica's TESR path use. (VERIFIED, section 1)
2. **Unprojection.**
   - Start from raw `Mouse.getX()/getY()`: physical window pixels, origin at the bottom left, divided by `displayWidth/displayHeight` (the world viewport).
   - Take the ray through `inverse(P·V)` in doubles, relative to the camera, then add the camera position.
   - Intersect it with the **drawn** plane, lifted 0.005 off the block. Use the `HitMath` / `BlockSide` conventions.
   
   The numeric model reproduces the vanilla camera exactly (VERIFIED by running it; Appendix A):
   - the centre ray equals `Entity.getLook` to within 1.5e-6°;
   - unproject∘project comes back within 1e-14 blocks;
   - (u, v) are recovered exactly on 6 faces × 4 rotations.
   
   Using the block surface instead of the lifted quad costs up to about 10 page px at 80°.
3. **Frame timing.**
   - Compute hover lazily, once per rendered frame: on the first screen TESR call or in the GUI's `drawScreen`, whichever comes first. Use that frame's matrices and that frame's polled mouse position.
   - Button and wheel events arrive in `runTick` at 20 Hz. Map them with **their own event coordinates** and the snapshot of the **last displayed frame**.
   
   The cursor sprite is drawn by the game, not baked into the page frames. So it never waits for the browser. (Mixed: VERIFIED order, INFERRED design)
4. **Mode.** For the navigation GUI, use the free cursor with the camera locked. Keep crosshair aim for play without a GUI.
   - At default sensitivity, the free cursor is about 2× more precise than look-to-point (1.6–1.9 against 3.2–3.8 page px per input step). It also causes no vection.
   - Add a "hold to look around" gesture that re-projects the cursor afterwards.
   - Add an optional focus zoom through Forge's `FOVUpdateEvent`.
   
   (INFERRED, numbers VERIFIED by the model)
5. **Page cursor.** The TESR draws it in page space after the page quad.
   - Sprites come from our own procedural atlas, so licensing is safe.
   - Each sprite has a hotspot. The cursor is about `pageH/34` page px tall, clamped to 12–64 window px.
   - GL state: polygon offset, depth test on, depth write off, alpha cutout.
   - Hide the OS cursor only while it is over the page. LWJGL 2.9.1 on Windows supports only 1-bit cursors (VERIFIED), so mirroring CSS cursors as OS cursors would look jagged.
6. **Contract gaps found.**
   - `PageState` does not parse the `cursor` field that the host already sends (VERIFIED).
   - INPUT has no `mouseLeave`. Electron 44 supports it (VERIFIED, `EL:9938`). Without it, hover menus stay open after the cursor leaves the screen.
   - The wheel should carry fractional notches.

---

## 1. The world camera in 1.7.10

### 1.1 Frame order (VERIFIED)

1. `Minecraft.runGameLoop` first runs `runTick()` once per elapsed tick (`MC:client/Minecraft.java:1037-1040`). Then it posts the FML render tick START and calls `entityRenderer.updateCameraAndRender(renderPartialTicks)` (`:1064-1067`). Then `func_147120_f` calls `Display.update()` (`:1106`, `:1142-1144`).
2. **GUI input runs at tick rate.**
   - `runTick` calls `currentScreen.handleInput()` (`MC:client/Minecraft.java:1731`).
   - That drains `Mouse.next()` and `Keyboard.next()` (`MC:client/gui/GuiScreen.java:307-327`).
   - So GUI mouse events reach the GUI in bursts, at most 20 times a second.
   - ModularUI2 wraps each `handleMouseInput` call that `handleInput` makes in its `MouseInputEvent` Pre/Post (`MUI2:core/mixins/early/minecraft/GuiScreenMixin.java:28-38`).
3. **`updateCameraAndRender`:**
   - Mouse look runs only when `inGameHasFocus && Display.isActive()` (`MC:client/renderer/EntityRenderer.java:1039-1065`). With a GUI open the view does not turn.
   - An inactive window with `pauseOnLostFocus` opens the in-game menu after 500 ms (`:1024-1030`). That replaces our GUI.
4. **`renderWorld`:**
   1. `getMouseOver(pt)` (`:1203`).
   2. Per anaglyph eye `j`: `glViewport(0, 0, displayWidth, displayHeight)` (`:1229`), `setupCameraTransform` (`:1234`), `ActiveRenderInfo.updateRenderInfo` (`:1235`).
   3. `renderEntities`, pass 0, which includes the TESRs (`:1300`); `renderEntities`, pass 1 (`:1413`).
   4. `RenderWorldLastEvent` (`:1430`), then the hand (`:1434-1437`).
5. **Then the GUI.** `glClear(DEPTH)` and `currentScreen.drawScreen(k, l, pt)` (`:1132-1137`). `k` and `l` are GUI-scaled integers (`:1076-1077`).
6. **LWJGL mouse polling.** `Display.update` swaps, then `processMessages` runs `pollDevices`, which runs `Mouse.poll()` (`LW2:src/java/org/lwjgl/opengl/Display.java:638-668, 595-603, 670-675`). So `Mouse.getX/getY` stay constant for a whole frame and change after each swap.

### 1.2 What P and V contain (VERIFIED, `MC:client/renderer/EntityRenderer.java`)

**Projection.** `setupCameraTransform` (`:655-679`) builds:

```
P = Aₚ · Z · Persp(fovy, displayWidth/displayHeight, 0.05, 2·farPlane) · S_demo
  Aₚ     = Translate(−(2j−1)·0.07, 0, 0)            only with anaglyph (:662-665)
  Z      = Translate(cameraYaw, −cameraPitch, 0) · Scale(cameraZoom, cameraZoom, 1)   only if cameraZoom ≠ 1 (:667-671)
  farPlane = renderDistanceChunks · 16 (:657)
  S_demo = Scale(1, 2/3, 1) if playerController.enableEverythingIsScrewedUpMode() (:675-679)
```

**Field of view.** `getFOVModifier(pt, true)` (`:473-504`) computes:
```
fovy = fovSetting                                   (degrees, :486)
     × lerp(fovModifierHandPrev, fovModifierHand, pt)  (sprint, speed/slowness, bow; FOVUpdateEvent; clamp 0.1..1.5 at :459-466)
     ÷ ((1 − 500/(deathTime+pt+500))·2 + 1)            when dead
     × 60/70                                           under water (:500)
     + debug-cam FOV
```

**Modelview.**
```
V = Aᵥ · Hurt · Bob · Nausea · Orient
  Aᵥ     = Translate((2j−1)·0.1, 0, 0)               anaglyph (:685-688)
  Hurt   = hurtCameraEffect (:507-531)               death roll, hurt shake
  Bob    = setupViewBobbing (:533-550)               when viewBobbing
  Nausea = rotate·scale·unrotate (:697-713)          portal / nausea, non-rigid
  Orient = orientCamera (:552-651):
           Rz(camRoll) · [sleeping or 3rd-person terms]
           · Translate(0, 0, −0.1)  (first person, :636)
           · Rx(pitch) (:641) · Ry(yaw + 180) (:642)
           · Translate(0, yOffset − 1.62, 0) (:645)   = 0 for the player (EntityPlayer.yOffset = 1.62, MC:entity/player/EntityPlayer.java:170)
```

**V has no world translation.**
- World geometry is drawn relative to `c = lerp(renderViewEntity.lastTickPos, pos, pt)`. `RenderGlobal.renderEntities` stores `c` in `TileEntityRendererDispatcher.staticPlayerX/Y/Z` (`MC:client/renderer/RenderGlobal.java:459-464`).
- Every TESR is called at `te.xCoord − staticPlayerX` (`MC:client/renderer/tileentity/TileEntityRendererDispatcher.java:126`).
- Hence a world point `p` appears at `clip = P·V·(p − c, 1)`.
- Consequence: the first-person camera eye sits 0.1 block **behind** the entity eye along the look. Checked by the model: `eye = −0.1·look` (Appendix A).

### 1.3 How to read them

| Option | How | Verdict |
|---|---|---|
| **A. `ActiveRenderInfo` buffers** | `updateRenderInfo` fills them with `glGetFloat(GL_MODELVIEW_MATRIX, modelview)`, `glGetFloat(GL_PROJECTION_MATRIX, projection)` and `glGetInteger(GL_VIEWPORT, viewport)` (`MC:client/renderer/ActiveRenderInfo.java:50-57`). They are `private static`, allocated once (`:28-32`). SRG names: `modelview` = `field_74594_j`, `projection` = `field_74595_k`, `viewport` = `field_74597_i` (`/root/.gradle/caches/minecraft/.../srgs/mcp-srg.srg` lines 2619, 8303, 3818). Resolve them once with `cpw.mods.fml.relauncher.ReflectionHelper.findField(ActiveRenderInfo.class, "modelview", "field_74594_j")`, or make them public with an access transformer. | **Recommended.** It is the frame's own matrix, written before any TESR, and the source Angelica copies. (VERIFIED facts; INFERRED choice) |
| B. Our own `glGetFloat` in `RenderWorldLastEvent` | The GL modelview at that point is the camera V. The stack should be balanced after `renderEntities` push/pop (INFERRED). | Fallback only. It fires **after** the TESRs (`:1430` against `:1300`), so the sprite would use last frame's matrices. |
| C. Rebuild from yaw/pitch/FOV | Recompute the formulas in 1.2. | **Rejected.** It misses bobbing, hurt cam, nausea, FOV lerps, Angelica settings and any other mod. The fork did this: `FK:client/gui/GuiKeyboard.java:286-311` rebuilds the camera from `KeyboardCamera` angles and `getProjectionMatrix(fov)`. Its line 311, `coord.add(proj.invert().transform(coord))`, adds the unprojected vector to the NDC vector and inverts `proj` in place (INFERRED bug). |

**Rules for option A.**
- Read only with **absolute** `get(i)`. Never call `get()`, `flip()` or `rewind()`. The next `glGetFloat` writes from the buffer position, and Angelica's mixin reads from it (JOML `set(FloatBuffer)`), so moving the position would corrupt both.
- **Anaglyph:** the buffers hold the second eye (j = 1). To map with the "cyclopean" camera, multiply V by `Translate(−0.1, 0, 0)` and P by `Translate(+0.07, 0, 0)` on the left. That only matters in anaglyph mode. (INFERRED, low priority)

### 1.4 Angelica 2.2.20 and shader packs

- **Same source of truth** (VERIFIED). `ANG:src/mixin/java/com/gtnewhorizons/angelica/mixins/early/angelica/MixinActiveRenderInfo.java:13-21` copies `projection` and `modelview` into `RenderingState` at the TAIL of `updateRenderInfo` (`ANG:src/main/java/com/gtnewhorizons/angelica/rendering/RenderingState.java`).
- **No GL round trip** (VERIFIED). `GLStateManager.glGetFloat` returns Angelica's tracked Java matrices for `GL_MODELVIEW_MATRIX` and `GL_PROJECTION_MATRIX` (`ANG:glsm/src/main/java/com/gtnewhorizons/angelica/glsm/GLStateManager.java:1185-1194`). So the buffers hold exactly what the emulated pipeline renders with.
- **Camera tweaks go through the same matrices** (VERIFIED, `ANG:.../mixins/early/angelica/MixinEntityRenderer.java`):
  - hurt shake is scaled by `HURT_SHAKE` (ModifyConstant on `14.0F`);
  - bobbing on the camera or the hand follows `BOBVIEW_MODE` (WrapWithCondition);
  - the FOV is captured.
  
  Per-frame matrices absorb all of these.
- **Zoom is on by default and resets on any GUI open** (VERIFIED).
  - `AngelicaConfig.enableZoom` defaults to true (`ANG:src/main/java/com/gtnewhorizons/angelica/config/AngelicaConfig.java:253-256`).
  - Zoom divides the FOV (`ANG:.../mixins/early/angelica/zoom/MixinEntityRenderer_Zoom.java`, ModifyReturnValue on `getFOVModifier`).
  - It is **reset whenever any GUI opens** (`ANG:src/main/java/com/gtnewhorizons/angelica/proxy/ClientProxy.java:365-376`).
  - The return to normal is smoothed (`ANG:.../zoom/Zoom.java`, `updateZoomLerp`).
  
  Consequences:
  - For a few frames after our GUI opens, the FOV animates. Per-frame matrices keep the mapping exact. (INFERRED)
  - A player who zoomed in to read a distant telão loses the zoom when the GUI opens. Our own focus zoom (section 4.4) replaces it. (INFERRED)
- **TESR camera** (VERIFIED). Angelica's Celeritas path renders tile entities with `TileEntityRendererDispatcher.staticPlayerX/Y/Z` and `GLStateManager.getModelViewMatrix()` (`ANG:src/main/java/com/gtnewhorizons/angelica/rendering/celeritas/CeleritasWorldRenderer.java:402-421`).
- **Shaders (Iris port)** (VERIFIED):
  - TESRs draw in phase `BLOCK_ENTITIES`.
  - Iris picks `BLOCK_ENTITIES_TRANSLUCENT` when the current blend state matches a translucent blend (`ANG:src/main/java/net/coderbot/iris/pipeline/DeferredWorldRenderingPipeline.java:891-892, 912-914`). Hence the cursor uses alpha cutout and no blending (section 5.3).
  - The shadow pass re-runs TESRs with its own camera (`ShadowRenderer.java:697-716, 818-850`). Our TESR already returns early in the shadow pass (`AW:client/render/ScreenRenderer.java:64`). **Never** take the snapshot from live GL state inside a TESR.
- **Shader vertex effects** (INFERRED). A pack can move geometry in its vertex shaders without changing the matrices:
  - TAA jitter is sub-pixel and harmless;
  - "world curvature" style options would misplace the cursor at distance.
  
  Test BSL v8.2.09 and Complementary r5.9.3 (shipped, `docs/research/07-forbidden-pack-inventory.md:27`) at their defaults.
- **FpsReducer 1.10.3 is in the pack** (inventory `:21`). INFERRED: idle frame-rate drops lengthen sprite latency until the mouse moves.

### 1.5 lwjgl3ify (not used by the pack's clients) and HiDPI

- **lwjgl3ify** (VERIFIED, HEAD 3.0.37, not the pack's server copy 3.0.31). Mouse positions come from SDL and are converted to framebuffer pixels with `Display.getPixelScaleFactor()` (`L3:src/main/java/org/lwjglx/input/Mouse.java:84-107`). `setCursorPosition` converts back (`:362-365`). The same formulas hold, with `displayWidth` equal to the framebuffer width.
- **LWJGL 2.9.1 on Windows** (VERIFIED). `getPixelScaleFactor()` returns 1 (`LW2:src/java/org/lwjgl/opengl/WindowsDisplay.java:1195-1197`). Mouse, Display size and viewport share one pixel space.
  - INFERRED: DPI virtualisation, when the process is not DPI-aware, scales all three alike, so ratios are unaffected.
- **macOS** (VERIFIED, LWJGL 2.9.3). HiDPI applies only with the `org.lwjgl.opengl.Display.enableHighDPI` property or in fullscreen (`MacOSXDisplay.java:141-143`). That is a vanilla concern, not ours.
- **Android ForbiddenMobile** (LWJGL3, touch) is out of scope.

---

## 2. From the mouse to a page pixel

### 2.1 Window to NDC

```
mx = Mouse.getX()   my = Mouse.getY()        // or getEventX/getEventY for events; ints, origin bottom-left
W  = mc.displayWidth  H = mc.displayHeight    // = world viewport (EntityRenderer.java:1229)
xn = 2·(mx + 0.5)/W − 1
yn = 2·(my + 0.5)/H − 1
```

- **Origin** (VERIFIED). LWJGL's origin is at the bottom left. `GuiScreen.handleMouseInput` flips Y for GUI coordinates (`MC:client/gui/GuiScreen.java:331-332`).
- **Clamping** (VERIFIED). Positions are clamped into `[0, W−1] × [0, H−1]` unless the system property `org.lwjgl.input.Mouse.allowNegativeMouseCoords` is set (`LW2:src/java/org/lwjgl/input/Mouse.java:152, 374, 466`). Use `Mouse.isInsideWindow()` to detect "outside".
- **Do not use the GUI-scaled `mouseX/mouseY`.** At GUI scale g they are quantised to g physical pixels (`GuiScreen.java:331`; `EntityRenderer.java:1076-1077`). GUI scale does not affect the ray at all. It only matters for ModularUI2 widget hit-testing.

### 2.2 NDC to world ray

```
M = P · V            (double, column-major as glGetFloat returns them: m[col*4+row])
Q = M⁻¹
n = Q · (xn, yn, −1, 1);   n /= n.w        // a point on the near plane, 0.05 ahead of the camera eye
f = Q · (xn, yn, +1, 1);   f /= f.w        // a point on the far plane
origin    o = c + n.xyz                    // c = interpolated camera position (double)
direction d = normalize(f.xyz − n.xyz)
```

- **Work relative to the camera.** V has no world translation, so the float inputs stay well-conditioned. Add `c` in double at the end, which avoids float loss at |x| up to 30M. (VERIFIED by 1.2; INFERRED numerics)
- **Near and far do not matter.** Any two depths on the same pixel give the same line. So a far plane changed by Angelica or another mod does not matter. (INFERRED, exact algebra)
- **Prefer the full inverse to a closed form.** It covers anaglyph, `cameraZoom`, nausea and any mod's extra transform. Cost: one 4×4 inverse per frame, about 200 flops, done only when the matrices changed.

### 2.3 World to window (forward projection)

```
clip = M · (p − c, 1)                 // clip.w ≤ 0 → behind the camera
xw = (clip.x/clip.w + 1)/2 · W − 0.5
yw = (clip.y/clip.w + 1)/2 · H − 0.5  // LWJGL window pixels, bottom-left origin
```

Uses:
- Place the OS cursor on a page point with `Mouse.setCursorPosition(x, y)` (`LW2:src/java/org/lwjgl/input/Mouse.java:218-232`). Needed when the GUI opens for a screen that is not under the crosshair (Keyboard block), and after a look-around.
- Compute the screen's on-window rectangle, for "screen partly off-view" hints.

### 2.4 Ray against a screen face (HitMath conventions)

Use `BlockSide` (`AW:common/BlockSide.java`): `right`, `up` and `fwd`, where `fwd` is the outward normal, all unit integer vectors. `c₀ = HitMath.corner(s, side)` is the bottom-left corner of the whole face rectangle, frame included, on the block surface (`AW:common/HitMath.java:182-197`). Define:

- `B = HitMath.BORDER = 2/16`;
- `Wb, Hb` = `widthBlocks, heightBlocks`;
- `λ = ScreenGeometry.SURFACE_OFFSET = 0.005`, the drawn quad's lift (`AW:client/screen/ScreenGeometry.java:26, 95-100`).

```
h   = fwd·(o − c₀) − λ                  // height of the ray origin above the DRAWN plane
tow = fwd·d
valid only if h > 0 (in front) and tow < 0 (pointing into the face)
t   = −h / tow                          // distance along unit d
p   = o + t·d − c₀
a   = right·p      b = up·p
fx  = (a − B) / (Wb − 2B)               // 0..1 across the picture, left → right
fy  = 1 − (b − B) / (Hb − 2B)           // 0..1 down the picture, top → bottom
inside = 0 ≤ fx ≤ 1 and 0 ≤ fy ≤ 1
(u, v) = HitMath.faceToPage(rotation, fx, fy)
         q=0: (fx, fy)   q=1: (fy, 1−fx)   q=2: (1−fx, 1−fy)   q=3: (1−fy, fx)
page px: x = clamp(⌊u·pageW⌋, 0, pageW−1),  y = clamp(⌊v·pageH⌋, 0, pageH−1)
```

- **Same math as the server.** These are `HitMath.rayHit` (`AW:common/HitMath.java:63-83`) and `uvOnFace` (`:42-51`), plus the lift. The page-pixel rule is the same as `InputTranslator.pixel` (`AW:client/screen/InputTranslator.java:79-84`), and the host clamps again (`host/src/input.js:111-114`).
- **Why the lift matters.** `rayHit` intersects the unlifted plane. Ignoring λ shifts the hit by `λ·tan θ`, where θ is the angle from the normal. On a 4-block-wide 1280-px page that is 1.0 px at 30°, 3.0 px at 60° and 9.7 px at 80° (VERIFIED by the model). It also means the crosshair path, which maps the block-surface `objectMouseOver.hitVec` (`AW:client/screen/ScreenInteraction.java:76-86`), shows the same parallax against the picture.
- **Drag capture.** While a button is held, keep mapping outside the picture: use `inside = false` but clamp `fx, fy` to [0, 1]. If `h ≤ 0` or `tow ≥ 0`, keep the last position. A desktop browser does the same: it keeps sending `mousemove` to the page that got the `mousedown`. So text selection and scrollbar or seek-bar drags keep working past the edge. (INFERRED)

### 2.5 Which screen, and occlusion

- **Nearest hit wins.** Test the ray against every `ClientScreen` of the dimension with geometry and take the nearest `t` with `inside`. Cap `t` at a configurable range, for example the TESR render distance.
- **Input goes only to the focus screen**, the one this client controls (D-016). If the nearest hit is another screen, the focus screen is occluded: show the OS cursor and send no hover. A later version could switch focus on click.
- **Optional block occlusion.**
  - Call `world.func_147447_a(o, hitPoint, false, true, false)`, the same call `EntityLivingBase.rayTrace` uses (`MC:entity/EntityLivingBase.java:2213-2218`).
  - If it returns a block that is not one of the screen's blocks, the hover is occluded.
  - Run it only when the hovered page pixel changes, at most once per frame. It allocates a few `Vec3` (INFERRED).
- **Back faces drop out.** A face whose back faces the camera fails `h > 0`, so front and back faces of the same tile never compete.

### 2.6 Precision: page pixels per input step

Let `k` = page px per window px at the hover point.

- **Frontal, near the centre of view:**
  `k ≈ pageW · 2z / (quadW · H · P₅)`. Here `z = clip.w` (eye depth), `P₅ = 1/tan(fovy/2)` (projection element [5]) and `quadW = Wb − 2B`.
- **Oblique:** `k ≈ k_frontal / cos θ` along the tilt axis, with `cos θ = −fwd·d`.
- **General, and cheap:** cast two extra rays at `(mx+1, my)` and `(mx, my+1)`. Then `k = max(|Δu|·pageW, |Δv|·pageH)`, a Jacobian estimate.
- **Look-to-point (crosshair) per mouse count:**
  `k_look = (0.15·f₂·π/180) · z · pageW / quadW`, with `f₂ = (0.6·s + 0.2)³ · 8` and `s = mouseSensitivity` (`MC:client/renderer/EntityRenderer.java:1042-1043`; `MC:entity/Entity.java:364`). At the default `s = 0.5`, `f₂ = 1`, which is 0.15° per count.

VERIFIED by the model (1920×1080, fovy 70°, 1280-px page, sensitivity 0.5):

| Case | Free cursor (page px per window px) | Crosshair (page px per mouse count) |
|---|---|---|
| 2×2 at 2 blocks | 1.90 | 3.83 |
| 4×3 at 4 blocks | 1.77 | 3.57 |
| 8×5 at 8 blocks | 1.71 | 3.46 |
| telão 32×18 at 30 blocks | 1.57 | 3.17 |
| telão 32×18 at 60 blocks | 3.14 | 6.33 |

`k` drives four things:
- the minimum cursor sprite size (5.2);
- the double-click drift (3.3);
- a "chegue mais perto / fique de frente" hint when `k > 4`;
- the focus-zoom factor (4.4).

### 2.7 Numeric check (ran)

`scratchpad/wave3/r5check/cursor_math_check.py` rebuilds the camera exactly as `setupCameraTransform` and `orientCamera` do in first person (`T(0,0,−0.1)·Rx(pitch)·Ry(yaw+180)`, `gluPerspective`). It checks:
1. the window-centre ray against `HitMath.lookVector` / `EntityLivingBase.getLook`, over 2,000 random yaw/pitch pairs;
2. unproject(project(p)) passing through p;
3. ray to (u, v) on all 6 `BlockSide` faces × 4 rotations, using `HitMath.corner`, `faceToPage` and `pageToFace` re-implemented as in the Java;
4. the parallax of ignoring the lift;
5. the camera-eye offset;
6. the precision table above;
7. the 24-bit depth step.

All passed; the output is in Appendix A. **Limit:** this checks the algebra against my reading of the vanilla code, not the running game.

### 2.8 Pseudo-code: camera snapshot, ray and face test (no per-frame allocation)

```java
// client/cursor/CameraSnapshot.java — client thread only.
final class CameraSnapshot {
    private static FloatBuffer mv, proj;                       // ActiveRenderInfo's buffers, resolved once

    static boolean resolve() {                                 // client init; on failure use the RenderWorldLast fallback
        try {
            mv = (FloatBuffer) ReflectionHelper.findField(ActiveRenderInfo.class, "modelview", "field_74594_j").get(null);
            proj = (FloatBuffer) ReflectionHelper.findField(ActiveRenderInfo.class, "projection", "field_74595_k").get(null);
            return mv != null && proj != null;
        } catch (Exception e) { LOG.warn(...); return false; }
    }

    private final float[] raw = new float[32];                 // P then V, last seen
    private final double[] pv = new double[16], inv = new double[16];
    double cx, cy, cz; int w, h; long frame = Long.MIN_VALUE; boolean valid;

    /** Once per rendered frame, after updateRenderInfo: first screen TESR call or the GUI's drawScreen. */
    void capture(long frameId, float pt) {
        if (frame == frameId) return;
        frame = frameId;
        Minecraft mc = Minecraft.getMinecraft();
        EntityLivingBase e = mc.renderViewEntity;
        if (e == null || mv == null) { valid = false; return; }
        cx = e.lastTickPosX + (e.posX - e.lastTickPosX) * pt;  // == TileEntityRendererDispatcher.staticPlayerX
        cy = e.lastTickPosY + (e.posY - e.lastTickPosY) * pt;
        cz = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * pt;
        w = mc.displayWidth; h = mc.displayHeight;
        boolean changed = false;
        for (int i = 0; i < 16; i++) {                         // ABSOLUTE get only
            float a = proj.get(i), b = mv.get(i);
            if (a != raw[i] || b != raw[16 + i]) { raw[i] = a; raw[16 + i] = b; changed = true; }
        }
        if (changed) { Mat4.mul(raw, 0, raw, 16, pv); valid = Mat4.invert(pv, inv); } // MESA-style 4x4 inverse in double
    }

    /** Window pixel (LWJGL, bottom-left origin) to a world ray; o, d are caller-owned double[3]. */
    boolean ray(double mx, double my, double[] o, double[] d) {
        if (!valid) return false;
        double xn = 2 * (mx + 0.5) / w - 1, yn = 2 * (my + 0.5) / h - 1;
        double ax = inv[0] * xn + inv[4] * yn + inv[12], ay = inv[1] * xn + inv[5] * yn + inv[13];
        double az = inv[2] * xn + inv[6] * yn + inv[14], aw = inv[3] * xn + inv[7] * yn + inv[15];
        double nw = aw - inv[11], fw = aw + inv[11];           // z = -1 (near) and z = +1 (far)
        double x0 = (ax - inv[8]) / nw, y0 = (ay - inv[9]) / nw, z0 = (az - inv[10]) / nw;
        double x1 = (ax + inv[8]) / fw, y1 = (ay + inv[9]) / fw, z1 = (az + inv[10]) / fw;
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(len > 0)) return false;
        o[0] = cx + x0; o[1] = cy + y0; o[2] = cz + z0;
        d[0] = dx / len; d[1] = dy / len; d[2] = dz / len;
        return true;
    }
}

// Cached in ClientScreen whenever its ScreenState changes (side, size, rotation, page size).
final class FacePlane {
    int rx, ry, rz, ux, uy, uz, nx, ny, nz, quarterTurns, pageW, pageH;
    double cx, cy, cz, wb, hb;                                 // c0 = HitMath.corner; wb, hb = width/heightBlocks

    /** Writes into out; false when the ray misses (or, unless drag, lands outside the picture). */
    boolean intersect(double[] o, double[] d, boolean drag, PointerHit out) {
        double ox = o[0] - cx, oy = o[1] - cy, oz = o[2] - cz;
        double hgt = nx * ox + ny * oy + nz * oz - ScreenGeometry.SURFACE_OFFSET;
        double tow = nx * d[0] + ny * d[1] + nz * d[2];
        if (hgt <= 0 || tow >= 0) return false;
        double t = -hgt / tow;
        double px = ox + d[0] * t, py = oy + d[1] * t, pz = oz + d[2] * t;
        double a = rx * px + ry * py + rz * pz, b = ux * px + uy * py + uz * pz;
        double B = HitMath.BORDER;
        double fx = (a - B) / (wb - 2 * B), fy = 1 - (b - B) / (hb - 2 * B);
        boolean inside = fx >= 0 && fx <= 1 && fy >= 0 && fy <= 1;
        if (!inside && !drag) return false;
        fx = fx < 0 ? 0 : fx > 1 ? 1 : fx; fy = fy < 0 ? 0 : fy > 1 ? 1 : fy;
        switch (quarterTurns) {                                // HitMath.faceToPage, no allocation
            case 1:  out.u = fy;     out.v = 1 - fx; break;
            case 2:  out.u = 1 - fx; out.v = 1 - fy; break;
            case 3:  out.u = 1 - fy; out.v = fx;     break;
            default: out.u = fx;     out.v = fy;
        }
        out.t = t; out.inside = inside;
        out.px = Math.min(pageW - 1, Math.max(0, (int) Math.floor(out.u * pageW)));
        out.py = Math.min(pageH - 1, Math.max(0, (int) Math.floor(out.v * pageH)));
        return true;
    }
}
```

---

## 3. Cursor mode: the GUI and the input pipeline

### 3.1 GUI properties

1. **Non-pausing.** `doesGuiPauseGame()` returns false; the default is true (`MC:client/gui/GuiScreen.java:429-431`).
2. **Transparent.** Do not call `drawDefaultBackground()`, so the world renders under the GUI.
3. **Hide HUD pieces that get in the way.** The HUD still draws with a GUI open (`EntityRenderer.java:1111-1115`). While cursor mode is active:
   - cancel `RenderGameOverlayEvent.Pre(CROSSHAIRS)` (`MC:../net/minecraftforge/client/GuiIngameForge.java:133, 218-220`);
   - cancel `RenderHandEvent` (`ForgeHooksClient.java:264-266`; `EntityRenderer.java:1434`), so the held item does not cover the lower right of the screen;
   - cancel `DrawBlockHighlightEvent` on the screen block.
   
   (VERIFIED hooks; INFERRED choice)
4. **Leave `allowUserInput` false** (`GuiScreen.java:44`; `Minecraft.java:1770`). True would also feed mouse buttons to the attack/use key bindings.
5. **Opening.**
   - `displayGuiScreen` calls `setIngameNotInFocus` first: `KeyBinding.unPressAllKeys`, then `ungrabMouseCursor`, which **centres the OS cursor**. Only after that does it call `setWorldAndResolution`/`initGui` (`MC:client/Minecraft.java:857-866, 1396-1403`; `MC:util/MouseHelper.java`). (VERIFIED)
   - So the free cursor appears exactly where the crosshair was.
   - When the GUI opens for a screen that is not under the crosshair, move the cursor in `initGui` with 2.3 (INFERRED).
6. **Lock check.**
   - The server's controller-lock check wants the look within `controlMaxAngleDeg` (default 45°) of the screen and the player within `interactRange` (default 24) (`AW:common/WebSettings.java:155-156`; `AW:server/ViewCheck.java:41-55`). (VERIFIED)
   - In cursor mode the camera never moves, so the look direction stays valid only if it was valid at open.
   - If it was not (opened from a Keyboard block): turn the camera to the screen first, with a short yaw/pitch lerp (≤ 200 ms) before releasing the cursor. The fork does this every tick (`FK:client/gui/camera/KeyboardCamera.java:143-195`). (INFERRED)
7. **Optional: force first person while open**, saving and restoring `thirdPersonView`. Third person works mathematically, because the matrices include it, but the player's own body can hide the screen. (INFERRED)

### 3.2 Per frame (hover and drag)

A frame id is incremented in `TickEvent.RenderTickEvent` START; `pt = event.renderTickTime`. The first caller in the frame runs `capture()`.

```java
void onFrame(long frameId, float pt) {                          // from the first TESR call or GUI drawScreen
    snap.capture(frameId, pt);
    boolean over = false;
    if (snap.valid && Mouse.isInsideWindow() && !overModularUiWidget()
        && snap.ray(Mouse.getX(), Mouse.getY(), o, d)) {
        ClientScreen nearest = pickNearest(o, d, hit);          // section 2.5
        boolean drag = pressedButtons != 0;
        if (nearest == focus || (drag && focus.plane().intersect(o, d, true, hit))) {
            over = hit.inside || drag;
            hit.k = magnification(snap, focus.plane(), Mouse.getX(), Mouse.getY()); // 2 extra rays
            if (!entered) { entered = true; /* optional INPUT mouseEnter */ }
            if (hit.px != lastX || hit.py != lastY) {            // at most one mouseMove per frame
                sendPointer(MOUSE_MOVE, hit.px, hit.py, 0, 0);
                lastX = hit.px; lastY = hit.py;
            }
        }
    }
    if (!over && entered && pressedButtons == 0) {
        sendPointer(MOUSE_LEAVE, lastX, lastY, 0, 0);            // new INPUT type, section 5.4
        entered = false;
    }
    osCursor.setHidden(over && style == IN_WORLD);              // calls Mouse.setNativeCursor only on change
    view.publish(over ? focus : null, hit);                     // what the TESR draws this frame
}
```

**Ordering subtlety.** The TESR runs before `drawScreen` in the same frame (section 1.1). So the TESR is usually the first caller, which is what lets the sprite use the current frame's matrices and mouse. When the screen is culled, `drawScreen` calls `onFrame` and nothing is drawn.

### 3.3 Events (click, release, wheel)

- **Source.** Events reach `handleMouseInput` in `runTick`. Each one carries exact `Mouse.getEventX/getEventY`. Map them with the snapshot of the **last displayed frame**, which is the one the player saw. Do not use `Mouse.getX()`, which may already be newer.
- **Button down** on the focus page:
  1. If the event point differs from the last move, send `mouseMove(x, y)` first, so Chromium hit-tests where the press happens.
  2. Send `mouseDown(x, y, button, clickCount)` and set the bit in `pressedButtons`.
  
  LWJGL buttons: 0 left, 1 right, 2 middle. Ignore any other button (R2 U7).
- **Button up.** If we pressed that button, send `mouseUp` at the last drag position, even off the screen. The host keeps the pressed set for drags (`host/src/input.js:2-4, 148-161`).
- **Wheel.** Accumulate `Mouse.getEventDWheel()`. Send **fractional** notches `dwheel/120`; `ScreenInput.wheel` takes a float and `InputTranslator` scales by 100 px. Shift + wheel becomes `deltaX`.
  - INFERRED: Windows' own shift-to-horizontal conversion is not applied to `sendInputEvent`.
  - Today's in-world path rounds every delta up to a whole notch (`AW:client/screen/ScreenInteraction.java:47`), which overscrolls with precision touchpads.
- **Double click.** `ClickCounter` uses 500 ms and a drift of 0.01 in uv (`AW:client/screen/ClickCounter.java:10-12`), about 13 px on a 1280 page. For the free cursor, use ≤ 4 window px, which is `≤ 4·k` page px, and 500 ms (Windows defaults, INFERRED).
- **Modifiers.** Read `Keyboard.isKeyDown` for LSHIFT/RSHIFT, LCONTROL/RCONTROL and LMENU/RMENU when each event arrives.
- **Click latency** (VERIFIED order, INFERRED option).
  - GUI clicks wait up to 50 ms for the next tick (`Minecraft.java:1731`, `:1037-1040`).
  - Optional "frame-rate input": drain `Mouse.next()` ourselves in `drawScreen` each frame, still on the client thread, and dispatch.
  - This must stay compatible with ModularUI2's `MouseInputEvent` wrapping (`MUI2:core/mixins/early/minecraft/GuiScreenMixin.java:28-38`). That decision belongs to R1.
- **Resize or fullscreen toggle** between the snapshot and an event: `W`/`H` no longer match. Drop pointer events until the next frame's snapshot. This is rare. (INFERRED)

### 3.4 Release and safety

**When cursor mode ends.** Triggers:
- Esc, or Shift+Esc to send Escape to the page (R2);
- death;
- window focus loss, which opens the in-game menu (`EntityRenderer.java:1024-1030`);
- lock lost or controller changed;
- screen unloaded or removed;
- world unload or dimension change.

**Then:**
1. Send `mouseUp` for each pressed button at the last position.
2. Send `mouseLeave`.
3. Restore the OS cursor with `Mouse.setNativeCursor(null)`.
4. Show the hand and crosshair again.

Do all of this in `onGuiClosed` plus a world-unload hook, inside `finally`.

**Closing while the left button is held does not mine** (VERIFIED). `setIngameFocus` sets `leftClickCounter = 10000`, and the counter only drops back to 0 once the left button is up (`MC:client/Minecraft.java:1388, 1422-1429`).

**Right button held while closing** (INFERRED risk). Hodgepodge re-syncs key-binding states on `setIngameFocus` (`HP:src/main/java/com/mitchej123/hodgepodge/mixins/early/minecraft/MixinMinecraft_UpdateKeys.java`). So a right button still held after closing can act as "use" on the screen block. Mitigation: swallow `use` until every mouse button has been released.

### 3.5 Latency budget (INFERRED)

- **Sprite.** The mouse is polled at the end of frame N−1, the sprite is drawn in frame N and appears at swap N, plus vsync or compositor delay. That is about 1–2 frames behind the hardware cursor; at 60 fps, 17–33 ms. Normal for a software cursor.
- **Page hover effects.** The move goes to the host, Chromium paints, then the frame is uploaded: about 2–4 frames. Because the game draws the sprite, the pointer itself never waits for page frames. A cursor baked into the page texture would add that browser latency, at 30 to 60 fps page rates.
- **Clicks.** 0–50 ms of tick wait, unless frame-rate input is adopted, plus the host hop.

### 3.6 D-016

- Hover, clicks and wheel go only to this client's own host, and only while the server lists this player as controller or the screen is PRIVATE (`AW:client/screen/ClientScreens.java:438-450`).
- The 5 s control keepalive is time-gated, so 60 Hz moves do not multiply it (`ClientScreens.java:92, 447`).
- **No pointer position goes to the server.** Showing the controller's cursor to spectators is R2's Q1 and needs a D-016 amendment.

---

## 4. Alternatives

### 4.1 How Web Displays' laser pointer did it (VERIFIED)

- **When it runs.** Client tick END (20 Hz), only while the player holds the Laser Pointer with the use key down and the crosshair is **not** on a block within reach (`WD:client/ClientProxy.java:575-616`). That leaves a close-range dead zone (R2).
- **Ray.** 64 blocks from `getPositionEyes(1.0f)` / `getLook(1.0f)` (`:686-692`). With no partial ticks, the ray can lag the rendered view by up to a tick while the player turns (INFERRED).
- **Messages.**
  - The first hit sends `LASER_DOWN`.
  - Moves are throttled to 100 ms and go through the server to every client (`:662-676`).
  - Leaving sends `LASER_UP`.
- **No cursor is drawn.** Hover exists only at the last injected point (R2 §2.3, §2.10).
- **The fork** (VERIFIED):
  - The Keyboard GUI keeps a free OS cursor and unprojects it with rebuilt matrices (`FK:client/gui/GuiKeyboard.java:286-311`).
  - It polls hover in `tick()` at 20 Hz (`:360-367`).
  - It steers the camera to the screen or the focused element (`FK:client/gui/camera/KeyboardCamera.java:143-195`).
  - It swaps the HUD crosshair for a 15-px `cursors.png` sprite chosen by the CEF cursor type (`FK:client/ClientProxy.java:109-185`).
  - It still draws no cursor on the screen itself (`FK:client/renderers/ScreenRenderer.java:138` TODO).

### 4.2 Comparison

| | **A. Crosshair as cursor** (aim mode; WD laser feel, R2 "navigation mode") | **B. Free OS cursor, camera locked** (this note) | **C. Grabbed mouse, virtual page cursor** (pointer-lock style) |
|---|---|---|---|
| What moves | The camera | The cursor | A virtual cursor, clamped to the page |
| Precision (default sensitivity, 1280-px page) | 3.2–3.8 page px per mouse count at 2–30 blocks; 6.3 at 60 (VERIFIED model) | 1.6–1.9 page px per window px; 3.1 at 60 (VERIFIED model) | Any gain; sub-window-pixel steps possible |
| Reach | Anything you can turn to, including off-view parts of a big telão | Only what is on the window; needs look-around or stepping back | The whole page; the camera may need to follow |
| Hover stability | Hand tremor and view bobbing move the hover point | Stable: the world stands still | Stable |
| Comfort and motion sickness | Every pointer move rotates the whole view (vection). Long read-and-point sessions and text drag selection are tiring | Desktop-like, no vection | Desktop-like |
| Pointer latency | Camera turns at frame rate; hover per frame | Sprite 1–2 frames behind the hardware cursor (OS cursor hidden over the page) | Like B, with no OS cursor at all |
| ModularUI2 widgets (address bar, buttons) | Need a key to free the cursor | Shared cursor, natural | Need a toggle |
| Several screens | Natural | Natural (point at another; switch focus on click) | Bound to one screen |
| Grazing angles | Worst: `k_look / cos θ` | `k / cos θ` | Gain can compensate per axis |
| Server lock check (look ≤ 45°) | Always satisfied while aiming | Fixed at open; turn the camera to the screen when opening | Like B |
| WD parity | WD right-click and laser | Fork's Keyboard GUI, improved | Fork's minePad pointer lock (for page games) |
| Risk | Low: today's code plus a per-frame ray and the sprite | Medium: matrices, OS cursor hide, sprite | Medium: gain, edge handling, no OS cursor for widgets |

All grades in the table are INFERRED except the precision figures.

### 4.3 Motion-sickness notes (INFERRED)

- In A, the inputs that cause vection are many small, frequent full-view rotations tied to pointing. They are worse with large FOV, view bobbing and smooth or cinematic camera; Angelica forces smoothing while zoomed (`MixinEntityRenderer_Zoom`, redirect of `smoothCamera`).
- B and C keep the world still. Only the cursor moves.
- Any automatic camera motion should be short (≤ 200 ms), eased, and never continuous. That covers the turn-to-screen at open, the focus zoom and the fork-style "camera follows text field". The fork's per-tick camera steering toward the focused element is the opposite of this advice.

### 4.4 Recommendation (INFERRED)

- **Navigation GUI: B.** It best matches the owner directive "teclado e mouse ... como um monitor no mundo" ("keyboard and mouse ... like a monitor in the world"). It gives the best precision for the effort, and the toolbar shares the cursor. Add three things:
  1. **Look around.** Hold a key (default to agree with the owner; Left Alt is a candidate, but on Windows a lone Alt press can start menu-bar mode, INFERRED) or hold the right button off the page. While it is held:
     - grab the mouse (`Mouse.setGrabbed(true)`);
     - apply `setAngles(dx·f₂, dy·f₂·invert)` ourselves, because vanilla only does it when `inGameHasFocus` (`EntityRenderer.java:1039-1065`).
     
     On release, ungrab and `Mouse.setCursorPosition(project(lastHoverWorldPoint))`.
  2. **Focus zoom.** Optional, off by default. Use `FOVUpdateEvent.newfov` (`MC:../net/minecraftforge/client/ForgeHooksClient.java:372`) with factor `clamp(k_target/k, 0.1, 1)`, where `k_target` is about 1.5. The game lerps it at 0.5 per tick and clamps it at ≥ 0.1 (`EntityRenderer.java:456-466`). This restores the precision lost when Angelica's zoom resets.
  3. **Turn to the screen at open**, only when the lock check would otherwise fail (3.1).
- **Play without a GUI: A** for quick clicks and scroll. Use the same per-frame ray, with the window centre as the pointer (`mx = W/2 − 0.5`), and the same sprite on the page.
- **C is only an internal technique**, later, for pages that request pointer lock.

---

## 5. Drawing the page cursor on the in-world screen

### 5.1 Where and when

- **Where.** In `ScreenRenderer.draw` (`AW:client/render/ScreenRenderer.java:94-114`), after the page quad and the placeholder text, inside the same `glMultMatrix(basisMatrix)`. Local x runs along the viewer's right, local y along the viewer's down, local z into the face; units are blocks; the origin is the lifted top-left corner (`AW:client/screen/ScreenGeometry.java:165-175`).
- **Which screen.** Only the screen the local pointer hovers this frame, in cursor mode or aim mode.
- **Which pass.** Only the main pass. The shadow-pass early return already covers this (`ScreenRenderer.java:64`).
- **Other players** never see it (D-016; R2 Q1).

### 5.2 Geometry: page point, axes, hotspot, size

The hover point `(u, v)` maps to the face with `HitMath.pageToFace(rotation, u, v)` (`AW:common/HitMath.java:157-169`), then to local coordinates: `(bx, by) = (fu·qw, fv·qh)`. Here `qw, qh = g.quadWidth, g.quadHeight`.

One page pixel along page x (`ex`) and page y (`ey`), in local units (derived from `pageToFace`):

| quarterTurns | ex | ey |
|---|---|---|
| 0 | (qw/pw, 0) | (0, qh/ph) |
| 1 (90°) | (0, qh/pw) | (−qw/ph, 0) |
| 2 (180°) | (−qw/pw, 0) | (0, −qh/ph) |
| 3 (270°) | (0, −qh/pw) | (qw/ph, 0) |

`pw, ph` = `ClientScreen.pageWidth()/pageHeight()`. With the default resolution, page pixels are square (`AW:common/ScreenGeometry.java:61-74`).

The sprite is drawn in **page space**, so it turns with the page, the way a rotated monitor shows its pointer. For each sprite-cell corner `(sx, sy) ∈ {(0,0), (C,0), (C,C), (0,C)}`, with cell size `C` in sprite px:

```
s     = sizePage / C                         // page px per sprite px
X     = bx + ((sx − hotX)·exX + (sy − hotY)·eyX)·s
Y     = by + ((sx − hotX)·exY + (sy − hotY)·eyY)·s
sizePage = clamp(ph / 34, 12·k, 64·k)        // about a desktop cursor (a 32-px cursor on 1080p ≈ 1/34 of the height),
                                             // never under 12 nor over 64 window px (k from 2.6)
```

- **Hotspots** are defined on a 32-unit design grid (INFERRED design): arrow tip (2, 2); hand fingertip (10, 1); I-beam centre (16, 16); spinners, crosshair, move, resize, not-allowed and zoom at the centre or lens centre.
- **Spinners** (`wait`, `progress`) rotate their quad around the hotspot by `(nanoTime / 125 ms) mod 8 · 45°`.

### 5.3 Depth and GL state

| State | Setting | Why |
|---|---|---|
| Depth test | On (vanilla `GL_LEQUAL`) | Blocks, entities and players in front of the screen hide the cursor, as they hide the page |
| Depth write | Off (`glDepthMask(false)`) | The overlay does not disturb later translucent draws |
| Lift | z = −0.001 (towards the viewer; local z points into the face, like `TEXT_LIFT = −0.002`, `ScreenRenderer.java:51-52`) | Small constant lift |
| **Polygon offset** | `glEnable(GL_POLYGON_OFFSET_FILL); glPolygonOffset(−1, −8)` | **A fixed lift is not enough at telão distances.** The 24-bit depth step with near = 0.05 is 0.0003 blocks at 16, 0.0012 at 32, 0.0049 at 64 and 0.011 at 96 (VERIFIED, model). A polygon offset scales with depth resolution and with slope. Angelica's GLSM implements it (`ANG:glsm/.../GLStateManager.java:758, 840, 5881`). |
| Alpha | Alpha test `GL_GREATER 0.5`, blend off | Same as the page (`ScreenRenderer.java:71-72`). Avoids Iris switching to the translucent block-entity program when it detects a translucent blend (1.4). Sprites carry a 2-px dark outline, so cutout edges still read well. |
| Light | Full bright, as the page | Already set by the TESR |
| Restore | Explicit save and restore of alpha func/ref, depth mask and polygon-offset enable | Do not rely on `glPushAttrib`; Angelica's emulation of it is not verified here |

Cost per frame: one extra texture bind and one Tessellator quad.

### 5.4 Sprite set and the mapping from Electron cursor types

**Where the type comes from** (VERIFIED):
- The host records `cursor-changed` and puts `cursor` into STATE (`host/src/screens.js:238-241, 426`; `docs/PROTOCOL.md:50`).
- `PageState` has no `cursor` field and `HostMessages.state()` does not read it (`AW:client/screen/PageState.java`). It has to be added: sanitise to ≤ 32 chars of `[a-z-]`, and map it to an enum once per STATE, not per frame.
- Electron 44's type list is at `EL:electron.d.ts:16561-16592`. Note that Electron's `pointer` is the **arrow**, and CSS `cursor: pointer` arrives as `hand`. (VERIFIED list; INFERRED Chromium naming: `CursorType::kPointer` is the arrow)

| Sprite | Electron `type` values |
|---|---|
| ARROW | `pointer`, `default`, `null`, unknown values, `custom` (v1) |
| HAND | `hand` |
| IBEAM | `text` |
| VIBEAM | `vertical-text` |
| WAIT (spinner) | `wait` |
| PROGRESS (arrow + small spinner) | `progress` |
| CROSSHAIR | `crosshair` |
| CELL (thick plus) | `cell` |
| MOVE | `move`, `m-panning`, `m-panning-vertical`, `m-panning-horizontal`, `e-/n-/ne-/nw-/s-/se-/sw-/w-panning` |
| EW | `e-resize`, `w-resize`, `ew-resize`, `col-resize`, `ew-no-resize` |
| NS | `n-resize`, `s-resize`, `ns-resize`, `row-resize`, `ns-no-resize` |
| NESW | `ne-resize`, `sw-resize`, `nesw-resize`, `nesw-no-resize` |
| NWSE | `nw-resize`, `se-resize`, `nwse-resize`, `nwse-no-resize` |
| NOT_ALLOWED | `not-allowed`, `nodrop`, `drag-drop-none` |
| HELP (arrow + ?) | `help` |
| CONTEXT_MENU, COPY, ALIAS (arrow + badge) | `context-menu`, `copy` / `drag-drop-copy`, `alias` / `drag-drop-link` |
| GRAB / GRABBING | `grab` / `grabbing`, `drag-drop-move` |
| ZOOM_IN / ZOOM_OUT | `zoom-in` / `zoom-out` |
| NONE (draw nothing) | `none` |

**Custom cursors.** `custom` (CSS `url(...)`) carries a NativeImage, scale, size and hotspot (`EL:16574-16592`). v1 draws the arrow. A later version could send a bounded PNG (≤ 128×128) with its hotspot in a new host message and upload it to a small texture (INFERRED).

**Host addition.** Add INPUT `{type:"mouseLeave", x, y}`, and optionally `mouseEnter`. In `host/src/input.js`, map it to `sendInputEvent({type:'mouseLeave', x, y})`. Electron 44 `MouseInputEvent.type` includes `mouseEnter` and `mouseLeave` (`EL:9921-9941`). The host clamps coordinates to ≥ 0 (`input.js:111-114`), so "move to −1" cannot stand in for a leave. Still to observe: whether Chromium's offscreen widget clears `:hover` on `mouseLeave` (INFERRED, test it).

### 5.5 Textures: license-safe, drawn by us

- **Generate the atlas at runtime** from our own vector shapes, in pure Java with no AWT:
  - polygon scanline fill with 4×4 supersampling;
  - white fill and a 2-px dark outline;
  - 64-px cells, about 22 cells, so 512×256 RGBA;
  - uploaded once to a GL texture with LINEAR filtering, no mipmaps, CLAMP.
- **Why generate:** no binary asset, deterministic, unit-testable (the rasteriser is a pure function to `int[]`), and no third-party art.
- **What not to use** (INFERRED licensing summary; check before any reuse):
  - Windows and macOS cursor art is proprietary.
  - Linux themes such as Adwaita, Breeze and Bibata carry CC-BY-SA, LGPL or GPL terms.
  - The fork's `cursors.png` has unclear provenance.
- **Fallback:** commit a PNG drawn by the team under the mod's licence. A PNG texture in resources is not a forbidden artefact.

### 5.6 The OS cursor

- **Hide it only while the pointer is over the focus page.**
  - Create a fully transparent cursor once: `new Cursor(n, n, 0, 0, 1, zeroBuffer, null)` with `n = Cursor.getMinCursorSize()` (`LW2:src/java/org/lwjgl/input/Cursor.java:85, 118`).
  - Toggle with `Mouse.setNativeCursor(empty | null)`, which needs `CURSOR_ONE_BIT_TRANSPARENCY` (`LW2:src/java/org/lwjgl/input/Mouse.java:183-197`).
  - Call it only on change. Always restore `null` on close. Destroy the cursor at shutdown.
  - Over the world, the bezel or ModularUI2 widgets, the normal arrow shows.
- **Why not mirror the page cursor as the OS cursor** (the fork's approach)?
  - LWJGL 2.9.1, 2.9.3 and HEAD on Windows report only `CURSOR_ONE_BIT_TRANSPARENCY`, at the fixed size `SM_CXCURSOR` (`LW2:src/java/org/lwjgl/opengl/WindowsDisplay.java:616-618, 646-652`). Mirrored cursors would have jagged 1-bit edges.
  - A flat OS cursor also ignores perspective and the screen's scale.
- **Low-FPS fallback.** Below about 30 fps the software sprite feels heavy. Offer "Cursor: no mundo / do sistema" ("in the world / system"). In system mode, skip the sprite and keep the hardware arrow. An "auto" setting can follow `Minecraft.debugFPS` with hysteresis. (INFERRED)

---

## 6. Edge cases

| Case | What happens | Handling | Grade |
|---|---|---|---|
| Screen at a grazing angle (any face) | `k` grows as 1/cos θ along the tilt; one window px skips several page px | Hint at `k > 4` ("fique de frente", stand in front); focus zoom; never auto-move the player. Turning the camera cannot fix obliqueness, which depends only on eye position against the plane | INFERRED |
| Very close (eye about 0.3 from the face; near plane 0.05) | Works; `k < 1`, so sub-pixel | Sprite capped at 64 window px; page px are ints anyway (host rounds) | INFERRED |
| Rotation 90/270 | `faceToPage` swaps axes; the sprite turns with the page (5.2) | Table in 5.2; checked by the model for all 4 rotations | VERIFIED (model) |
| Floor (TOP) and ceiling (BOTTOM) | `BlockSide` keeps `right = (−fwd) × up` on every face (`AW:common/BlockSide.java:8-13`), so no mirroring special case; a ceiling needs the eye below (`h > 0`) | Same code path; floor screens are often oblique (eye 1.62 above, 4 blocks away gives θ ≈ 68°, `k ×2.7` on one axis): hint | VERIFIED (model) / INFERRED |
| Several screens in view, or several faces of one tile | Nearest `t` wins; back faces fail `h > 0` | Input only to the focus screen; OS cursor shows over others | INFERRED |
| Screen partly off-view | Hidden parts unreachable in B | Look-around (4.4); forward projection to restore the cursor | INFERRED |
| GUI scale 1–4 | Does not affect the ray (raw mouse px) | Use only for ModularUI2 layout | VERIFIED (2.1) |
| Window resize or F11 | `displayWidth/Height` and aspect change; `setWorldAndResolution` re-inits | Snapshot holds `W`, `H`; drop events across a mismatch; re-apply the hidden native cursor after a fullscreen switch | INFERRED |
| HiDPI | Windows LWJGL2: scale factor 1, one pixel space; macOS: HiDPI only by property or fullscreen; lwjgl3ify: framebuffer px | Formulas unchanged | VERIFIED (1.5) |
| Mouse outside the window | Coordinates clamp to the edge | `Mouse.isInsideWindow()` false sends `mouseLeave` and shows the cursor | VERIFIED (2.1) |
| Window loses focus | In-game menu replaces our GUI after 500 ms | Release path (3.4) | VERIFIED |
| FOV animates (sprint stop, potion, Angelica zoom reset, our focus zoom) | Projection changes over several frames | Per-frame matrices keep the hover exact; clicks map to the shown frame | VERIFIED order / INFERRED |
| Player pushed, riding, hurt, bobbing | Camera moves under a still mouse | Hover follows, as a real monitor moving under a cursor would | INFERRED |
| Third person (F5) | Matrices include the 3rd-person camera | Works; optionally force first person while open | VERIFIED (1.2) / INFERRED |
| Anaglyph | Buffers hold eye 1 | Cyclopean correction (1.3), low priority | INFERRED |
| Shader-pack geometry effects (curvature) | Picture drawn elsewhere than the matrices say | Test both packs at defaults; document unsupported options | INFERRED |
| Telão farther than `interactRange` (24) | Server refuses the lock | Server config for the event (owner question) | VERIFIED default |
| Lock lost or controller changed | `applyLocally` stops applying | Release path; close or toast | VERIFIED (`ClientScreens.java:438-450`) |
| Page resized (`requestedW/H` changes) or admin rotates the screen | Pixel mapping changes | Recompute `FacePlane` on state change; per frame, read the current page size | INFERRED |
| Death, respawn, dimension change, world unload | GUI replaced or world gone | Release path; clear `FacePlane` cache with the screens | INFERRED |
| Right button still held when the GUI closes | May act as "use" (Hodgepodge re-sync) | Swallow `use` until all buttons are up | INFERRED |

---

## 7. Performance (no allocation per frame)

**Per frame:**
- 32 float reads and compares;
- when changed: one 4×4 multiply and inverse in double, about 250 flops;
- one ray (two mat-vecs);
- one plane test per screen, about 25 flops, plus two extra rays for `k` on the focus screen;
- at most one `InputEvent` per frame, only when the page pixel changes;
- `setNativeCursor` only on change;
- one extra bind and quad in the TESR.

**Do not call allocating helpers per frame.** `HitMath` allocates corner and result arrays (`AW:common/HitMath.java:42-83, 186-189`). Client `ScreenGeometry` allocates in `corner`, `faceUv`, `pageUv` (`ScreenGeometry.java:95-125`). Use `FacePlane` with a mutable `PointerHit`, and preallocated `double[3]` for `o` and `d`. Iterate a cached `ArrayList` of screens rather than `LinkedHashMap.values()`. INFERRED: this avoids the iterator, which escape analysis may or may not remove.

**Automated guard (INFERRED).** A unit test runs `capture` + `ray` + `intersect` 10,000 times and asserts 0 bytes allocated, using `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`, which HotSpot 8 provides.

---

## 8. Implementation plan and tests (INFERRED)

**New classes (client only, Java 8):**
- `client.cursor.CameraSnapshot`: section 2.8; reflection resolve and fallback.
- `client.cursor.Mat4`: double multiply and inverse (MESA `gluInvertMatrix` algorithm).
- `FacePlane`: cached in `ClientScreen`, rebuilt on state change.
- `PointerHit`: mutable.
- `client.cursor.PointerController`:
  - per-frame hover and drag, event mapping, button set, release path;
  - aim mode (window centre) and cursor mode (OS mouse);
  - OS cursor toggle.
- `client.cursor.CursorSprites`: Electron type to sprite enum; procedural atlas rasteriser (pure); hotspots.
- `ScreenRenderer.drawCursor(...)`: section 5.

**Contract changes:**
- `PageState.cursor` plus parsing in `HostMessages.state()`.
- INPUT `mouseLeave` (and `mouseEnter`) in `docs/PROTOCOL.md`, `host/src/input.js`, `InputEvent.Type`.
- `ScreenInput` wheel with fractional notches in cursor mode.

**Unit tests (Minecraft-free, pure):**
1. Port `cursor_math_check.py`:
   - centre ray == `HitMath.lookVector` for random yaw/pitch;
   - unproject∘project round trip;
   - `FacePlane.intersect` == `HitMath.uvOnFace` on the lifted plane for 6 faces × 4 rotations;
   - drag clamp;
   - behind and away rays rejected.
2. `pageToFace` local axes (table 5.2): the sprite's +x maps to page +x for every rotation.
3. Every Electron 44 cursor type maps to a sprite; unknown values map to ARROW; sanitising rejects junk.
4. Zero-allocation guard (section 7).
5. Release path: after any close trigger, the host received `mouseUp` for every pressed button and `mouseLeave`.

**In-game acceptance (Windows, the pack, later):**
- a local test page that draws the coordinates it receives;
- frontal error ≤ 1 page px, and ≤ 3 px at 60°;
- 6 faces × 4 rotations;
- Angelica with no shaders, BSL and Complementary;
- GUI scale 1–4, resize, F11;
- 30, 60 and 144 fps;
- text drag-select, YouTube seek-bar drag, hover menus closing on leave;
- telão at 30 and 60 blocks.

---

## 9. Open questions for the owner or lead

- **Q-R5-1.** Default pointer look in cursor mode: the in-world sprite (recommended, the "monitor in the world" feel) or the system arrow?
- **Q-R5-2.** The "look around" gesture: which key or button? Left Alt is a candidate, with the Windows menu caveat.
- **Q-R5-3.** Focus zoom for distant screens (telão): offer it, and on or off by default?
- **Q-R5-4.** Should spectators see the controller's cursor? This is R2 Q1 and needs a D-016 amendment.
- **Q-R5-5.** Telão control distance: `interactRange` defaults to 24 blocks, so an admin 30–60 blocks away cannot take the lock. Should the server config for the event raise it?

---

## 10. Side findings (outside R5; for the lead)

- **SF-1. Possible z-fighting of the page at telão distance** (INFERRED: computed, not observed).
  - The page quad sits 0.005 above the block face (`ScreenGeometry.SURFACE_OFFSET`). The 24-bit depth step is about 0.0049 blocks at 64 blocks, so the page and the bezel/face may z-fight from roughly 60 blocks.
  - Placeholder text and banner (`TEXT_LIFT −0.002`, `−0.001`) from roughly 35–40 blocks.
  - Fix: polygon offset for the page quad and the text, as in 5.3.
- **SF-2. Parallax in today's crosshair path** (VERIFIED code, numbers from the model). `ScreenInteraction` maps the block-surface `hitVec`, but the picture is drawn 0.005 in front. That is 3 page px off at 60° and about 10 at 80°. Use the lifted-plane ray (2.4) for aim mode as well.
- **SF-3. Wheel rounding** (VERIFIED code). `ScreenInteraction.java:47` rounds every delta up to at least one notch, which overscrolls on precision touchpads.
- **SF-4. `PageState` ignores the host's `cursor`** (VERIFIED), so no cursor shape can be shown today.
- **SF-5. Double-click drift in uv units** (VERIFIED code). `ClickCounter.MAX_DRIFT = 0.01` is about 13 page px on a 1280 page, too loose for a free cursor. Use window-pixel drift (3.3).

---

## 11. What I did not run, and limits

- **Not run:**
  - no Minecraft client, dev or pack;
  - no Windows;
  - no GL context, Angelica runtime or shader packs;
  - no Electron (so `mouseLeave` behaviour in offscreen rendering is not observed);
  - no build or tests of the akashicweb repository.
- **What I did run:** one pure-Python numeric model of the vanilla camera and of the HitMath conventions (Appendix A). It checks the algebra, not the game.
- **Version caveats:**
  - LWJGL facts come from the 2.9.1 tag (2.9.3 and HEAD where noted). The exact LWJGL 2 build in the Technic pack was not confirmed.
  - lwjgl3ify facts are from 3.0.37 HEAD.
  - Angelica facts are from the 2.2.20 tag, the version the pack ships.
- **Licensing statements in 5.5 are INFERRED.** Check them before any third-party art is considered.

---

## Appendix A: output of `r5check/cursor_math_check.py` (ran 2026-10-13)

```
max angle centre ray vs getLook (deg): 1.48e-06
max unproject(project(p)) miss (blocks): 1.33e-14
max |uv error| ray->screen, 6 sides x 4 rotations: 1.22e-15
parallax of ignoring the 0.005 lift at  0 deg: 0.00 page px
parallax of ignoring the 0.005 lift at 30 deg: 0.99 page px
parallax of ignoring the 0.005 lift at 45 deg: 1.71 page px
parallax of ignoring the 0.005 lift at 60 deg: 2.96 page px
parallax of ignoring the 0.005 lift at 75 deg: 6.37 page px
parallax of ignoring the 0.005 lift at 80 deg: 9.68 page px
camera eye relative to entity eye: (0.0470, 0.0342, -0.0814), = -0.1 * look: (0.0470, 0.0342, -0.0814)
2x2 at 2 blocks            free cursor 1.90 page px / window px; crosshair 3.83 page px / mouse count (0.150 deg/count)
4x3 at 4 blocks            free cursor 1.77 page px / window px; crosshair 3.57 page px / mouse count (0.150 deg/count)
8x5 at 8 blocks            free cursor 1.71 page px / window px; crosshair 3.46 page px / mouse count (0.150 deg/count)
telao 32x18 at 30 blocks   free cursor 1.57 page px / window px; crosshair 3.17 page px / mouse count (0.150 deg/count)
telao 32x18 at 60 blocks   free cursor 3.14 page px / window px; crosshair 6.33 page px / mouse count (0.150 deg/count)
24-bit depth step at   4 blocks: 0.00002 blocks
24-bit depth step at  16 blocks: 0.00031 blocks
24-bit depth step at  32 blocks: 0.00122 blocks
24-bit depth step at  64 blocks: 0.00488 blocks
24-bit depth step at  96 blocks: 0.01099 blocks
```
