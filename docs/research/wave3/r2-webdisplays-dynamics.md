# R2 - Web Displays interaction and visual dynamics, and what AkashicWeb should do

Research stream R2 for wave 3 (owner directive D-017: ModularUI2 GUIs, reproduce Web Displays' keyboard-and-mouse-in-the-world dynamics). Written 2026-10-13. Read-only on `/home/user/akashicweb`.

**Grades.** VERIFIED = I read the code or page (cited `file:line` or URL). INFERRED = reasoning, vanilla behaviour I did not read here, or a design proposal. Every "improvement" and "AkashicWeb should" item is a proposal (INFERRED) until an ADR accepts it.

**Path shorthands.**
- `WD:` = `/home/user/montoyo/webdisplays/src/main/java/net/montoyo/wd/` (Web Displays 1.12.2-1.1, montoyo, public domain).
- `WDR:` = `/home/user/montoyo/webdisplays/src/main/resources/assets/webdisplays/`.
- `FK:` = `/home/user/mysticpasta1/webdisplays/src/main/java/net/montoyo/wd/` (CinemaMod/Mysticpasta1 fork, branch `1.20`, HEAD `594c4de`, MIT).
- `AW:` = `/home/user/akashicweb/src/main/java/com/akashiic/web/` (wave-1 code as merged; wave 2 is changing it in other worktrees).
- `HOST:` = `/home/user/akashicweb/host/src/`.

**Not run.** No Minecraft client or server, no Web Displays runtime, no AkashicWeb client. Evidence is code reading, the research docs, and GitHub issue pages fetched with WebFetch on 2026-10-13. The GitHub API and `curl` to github.com were refused in this session (HTTP 403), so issue text comes only from WebFetch summaries. Vanilla 1.12.2 and 1.7.10 behaviour (reach distance, camera free-look in a GUI) is INFERRED because I did not read the vanilla sources here.

---

## 0. Findings in short

1. **Web Displays has one in-world mouse gesture: right-click with an empty hand.** It becomes a single page left-click at the hit pixel: move, press and release at once, with click count 1 and no modifiers. The server computes the pixel, then broadcasts the click to every client within 64 blocks. Hover, drag, wheel, double-click, right/middle buttons and shift-click do not exist on a placed screen. VERIFIED: `WD:block/BlockScreen.java:170-180`, `WD:entity/TileEntityScreen.java:457-468,477-502`. No `injectMouseWheel` call exists anywhere in WD (grep VERIFIED).
2. **Left-click is not handled, so it mines the block.** Players break their screen while trying to click (montoyo#189). A commenter answered "Simple. You don't." when the player asked how to get any other click. VERIFIED: there is no `onBlockClicked` in `WD:block/BlockScreen.java`; https://github.com/montoyo/webdisplays/issues/189.
3. **The only press, drag and release path is the Laser Pointer.** It needs four things:
   - the **Laser Sensor** upgrade installed on the screen;
   - the pointer in the main hand;
   - the use button held;
   - the crosshair **not** on any block within reach.

   So a laser pointer does nothing at close range: a dead zone (VERIFIED: `WD:client/ClientProxy.java:578-580` together with `WD:block/BlockScreen.java:127-131`). Moves go through the server at most every 100 ms (`WD:client/ClientProxy.java:662-676`). Only the user sees the red beam, in first person (`WD:client/renderers/LaserPointerRenderer.java:82-115`).
4. **Typing needs a Keyboard block,** placed two blocks wide and linked to the screen with a separate **Linking Tool**. The keyboard GUI is transparent and shows one yellow line: "Keyboard hooked. Press escape to leave." It grabs the mouse. The first time, it shows three red lines saying typed text goes to the server in **plain text** and must never contain a password.

   Each key-down becomes a press-plus-release pair with modifiers 0. Real key-ups are ignored. The GUI sends batches every 5 ticks (250 ms), and the server replays them on every nearby client. VERIFIED: `WD:client/gui/GuiKeyboard.java:63-146`; `WDR:lang/en_us.lang:94-97`.
5. **Every input goes through the server and is replayed by every nearby player's browser.** Yet a link navigation stays local; this is the known FIXME. Late joiners get only the URL. VERIFIED: `WD:entity/TileEntityScreen.java:262-264,679-693`. AkashicWeb's D-016 inverts this: input stays on the controller's own client.
6. **Defaults let anyone click, type and change the URL** (`WD:core/ScreenRights.java:16-18`, `WD:entity/TileEntityScreen.java:277-278`). AkashicWeb already defaults visitors to watch-only (`AW:common/Rights.java:14-15`).
7. **The fork (1.20) points to where the dynamics should go,** but it is unfinished:
   - The keyboard GUI aims the camera at the screen or at the focused element, and lets the free mouse cursor click the page by un-projecting it into the world (`FK:client/gui/GuiKeyboard.java:160,286-357`; `FK:client/gui/camera/KeyboardCamera.java:84-195`).
   - The laser forwards all three buttons through a mixin, and an R key toggles a "mouse mode" with continuous hover (`FK:mixins/MouseHandlerMixin.java`; `FK:client/ClientProxy.java:649-659,857`).
   - The crosshair becomes a cursor sprite matching the page's cursor type (`FK:client/ClientProxy.java:110-185`).
   - It still has a TODO "draw a mouse cursor on the screen" (`FK:client/renderers/ScreenRenderer.java:138`).
   - It ships `switch_buttons=true` by default, which inverts the laser buttons. That is the cause of CinemaMod#59 and #47 (`FK:config/ClientConfig.java:71-76`, `FK:item/ItemLaserPointer.java:108-110`).
8. **AkashicWeb wave 1 already fixes several Web Displays pains:**
   - left-click on the picture = page click, and sneak + left-click breaks the screen;
   - the wheel scrolls the page;
   - double-click counting;
   - real key down/up with modifiers;
   - ABNT2 composed characters and Ctrl+V paste;
   - Esc is consumed by the GUI.

   Sources: `AW:client/screen/ScreenInteraction.java:14-73`, `AW:client/gui/KeyboardRouter.java:9-120`.

   It still lacks hover, drag, a cursor, long-range aiming, a keyboard block, a config/rights GUI, connected bezel textures, a turn-on effect and a loading bar.

---

## 1. Inventory: the Web Displays player-facing surface (VERIFIED)

| Kind | Name (en_us) | Where |
|---|---|---|
| Block | Web Screen (multiblock, 2x2 to 16x16, up to one screen per face of the multiblock) | `WD:block/BlockScreen.java`, `WD:entity/TileEntityScreen.java:44-63,212` |
| Block (peripheral) | Keyboard (2 blocks wide, 1/16 high) | `WD:block/BlockPeripheral.java:177-212`, `WD:block/BlockKeyboardRight.java:41`, `WD:entity/TileEntityKeyboard.java` |
| Block (peripheral) | Remote Controller | `WD:entity/TileEntityRCtrl.java:18-41` |
| Block (peripheral) | Redstone Controller | `WD:entity/TileEntityRedCtrl.java:47-102` |
| Block (peripheral) | Server (miniserv console) | `WD:entity/TileEntityServer.java:40-48` |
| Block (peripheral) | ComputerCraft / OpenComputers Interface | `WD:entity/TileEntityInterfaceBase.java` |
| Item | Screen Configurator | `WD:item/ItemScreenConfigurator.java:40-67` |
| Item | Linking Tool | `WD:item/ItemLinker.java:47-126` |
| Item | Laser Pointer | `WD:item/ItemLaserPointer.java` (a plain item; logic in `WD:client/ClientProxy.java:575-616`) |
| Item | minePad / minePad 2 (handheld browser) | `WD:item/ItemMinePad2.java`, `WD:client/gui/GuiMinePad.java` |
| Item | Screen Upgrade: Laser Sensor, Redstone Input Port, Redstone Output Port, GPS Module | `WD:core/DefaultUpgrade.java` |
| Item | Ownership Thief [ADMIN] | `WD:item/ItemOwnershipThief.java:41-108` |
| Items | Craft components (stone key, blank upgrade, peripheral base, battery cell/pack, 650nm laser diode, backlight, extension card, bad extension card) | `WD:core/CraftComponent.java`, `WDR:recipes/*.json` |

There is **no handheld remote item**: the "Remote Controller" is a block (VERIFIED: the item model list in `WD:client/ClientProxy.java:478-499` has none).

---

## 2. Behaviour in detail

### 2.1 Building and turning on a screen

- **Form.** Right-click a face of a flat rectangle of screen blocks with an empty hand (VERIFIED: `WD:block/BlockScreen.java:125-216`):
  - The code walks to the bottom-left origin and measures the rectangle.
  - It rejects shapes below 2x2 ("Too small! Minimum size is 2x2.") or above the configured maximum of 16x16 ("Too big!").
  - It rejects holes and screen blocks in front of or behind the face ("Structure is invalid; look at x,y,z").
  - It then creates the tile entity on the origin.
  - The new face gets the config homepage `mod://webdisplays/main.html`, a rotating 3D cube plus the WD logo (`WD:entity/TileEntityScreen.java:275`, `WDR:html/main.html`).
  - Its resolution is (blocks x 16 - 4) x 8 px, clamped to 1920x1080 (`WD:entity/TileEntityScreen.java:185-197,298-308`).
  - A screen on the TOP or BOTTOM face takes its rotation from the player's yaw (`:284-295`).
- **Sneak + right-click before forming:** "You need to turn the screen on first!" (`WD:block/BlockScreen.java:182-185`, `WDR:lang/en_us.lang:39`).
- **Placing any screen block next to a formed screen tears that screen down.** Its tile entity is removed and its upgrades drop (VERIFIED: `WD:block/BlockScreen.java:336-357`). Pistons cannot move screen blocks (`:359-363`).

### 2.2 Clicking on a screen (mouse, WD 1.12)

`onBlockActivated` (VERIFIED: `WD:block/BlockScreen.java:125-181`) handles these cases:

| Gesture | Result |
|---|---|
| Right-click, empty hand, CLICK right | Server-side `hit2pixels` → `te.click()` → `CMessageScreenUpdate.MOUSE_CLICK` to all within 64 blocks. Each client calls `injectMouseMove`, then press and release of button 1 (left), click count 1, modifiers 0 (`WD:entity/TileEntityScreen.java:457-468,484-488`). Ignored while a laser user holds the screen (`:466`). |
| Right-click without the CLICK right | Red chat: "[WebDisplays] You are not allowed to do this :(" (`WD:block/BlockScreen.java:171-174`; `WD:utilities/Util.java:131-141`). |
| Sneak + right-click, empty hand | Set URL GUI if the player has CHANGE_URL, otherwise the same red chat line (`:146-152`). |
| Right-click holding an upgrade item | Installs the upgrade if the player has MANAGE_UPGRADES. Shows "Upgrade installed!" in aqua and plays `upgradeAdd` (`:153-169`; `WD:entity/TileEntityScreen.java:837-870`, max 16 upgrades). |
| Right-click holding any other item | `onBlockActivated` returns false, so the item acts: a block gets placed against the screen and nothing clicks (`:127-131`). |
| Left-click | No handler: vanilla mining. Breaking any block dissolves the screen (`:304-334`). |
| Wheel | Not forwarded. Vanilla hotbar scroll. |

**Hit-to-pixel mapping** (VERIFIED: `WD:block/BlockScreen.java:235-291`):
1. Take the hit relative to the origin and subtract the 2/16-block bezel.
2. Divide by (size - 4/16).
3. Flip Y, except on BOTTOM.
4. Apply the rotation (90: flip Y; 180: flip both; 270: flip X).
5. Multiply by the resolution, swapping axes when the rotation is vertical.

A hit on the bezel returns false: the click is swallowed and nothing happens.

**Server checks** (VERIFIED: `WD:net/server/SMessageScreenCtrl.java:282-359`):
- same dimension;
- within 128 blocks, or within player reach of a Remote Controller block for remote URL changes;
- per-action rights. CHANGE_URL also allows running JS; CLICK also allows typing (`WD:core/ScreenRights.java:9-18`).

### 2.3 Laser Pointer and Laser Sensor (WD 1.12)

VERIFIED: `WD:client/ClientProxy.java:575-616,662-692`; `WD:entity/TileEntityScreen.java:436-455,942-987`.

- **Active only when:**
  - the pointer is in the main hand;
  - the use key (right mouse) is **held**;
  - `objectMouseOver` is not a block, so the crosshair is beyond reach (about 4.5 blocks in survival, INFERRED vanilla).
- **Aim.** Each tick, a client raycast of 64 blocks from the eyes along the look vector, honouring liquids (`:686-692`, with a "TODO: Make that distance configurable"). The target must be a screen face with the Laser Sensor upgrade and a live browser. The hit is mapped with the same `hit2pixels`.
- **Events.**
  - First contact sends `CTRL_LASER_DOWN`. The server then:
    - requires CLICK and the upgrade;
    - takes a per-screen **laser lock**;
    - broadcasts MOUSE_DOWN.
  - Further ticks on the same face send `CTRL_LASER_MOVE`, throttled to 100 ms. The server relays MOUSE_MOVE only from the lock holder.
  - Releasing the button or leaving the face sends LASER_UP, a release at the last position.
  - So "hold use and sweep" = drag (text selection, sliders, scrollbars), and a short press = click. There is **no hover without the button held**, and only the left page button exists.
- **Lock.** It drops when the holder dies, stops holding the pointer (`:436-443`), or loses CLICK (`:445-448`). While it is held, other players' right-click clicks are ignored (`:466`).
- **Range.** Client raycast 64 blocks; server accepts up to 128.
- **Visual.** A red line, alpha 0.5 and 3 px wide, from the pointer tip toward the crosshair. It is drawn only in the user's first-person hand renderer (`WD:client/renderers/LaserPointerRenderer.java:82-115`; `WD:client/ClientProxy.java:629-647`). Other players see no beam and no dot. The page shows no cursor; it only reacts with CSS `:hover` at the last injected position.

### 2.4 Keyboard block and GuiKeyboard (WD 1.12)

- **Placement** (VERIFIED: `WD:block/BlockPeripheral.java:177-212`; `WD:block/BlockKeyboardRight.java:41,140-156`):
  - It needs a non-air block below and a free spot to the right or left; otherwise the item drops back.
  - It becomes two blocks, each a 1/16-high slab, facing the player's yaw.
  - Removing the support drops it and closes its GUI (`WD:block/BlockPeripheral.java:231-246`).
- **Linking** (VERIFIED: `WD:item/ItemLinker.java:47-126`):
  1. Right-click a screen face with the Linking Tool (needs MANAGE_UPGRADES): "Screen set! Now right click on the peripheral...". The tooltip shows the saved position and side.
  2. Right-click the keyboard: "Linked!".
  - Sneak + right-click on a non-peripheral: "Linker reset".
- **Opening** (VERIFIED: `WD:entity/TileEntityKeyboard.java:23-46`): right-click the keyboard. It fails with a chat line if:
  - the screen's chunk is not loaded ("The chunk the screen is placed in is not loaded!");
  - it is not linked ("This peripheral has not been linked yet.");
  - the player lacks CLICK.
- **What the GUI shows** (VERIFIED: `WD:client/gui/GuiKeyboard.java:59-102`, `WDR:gui/keyboard.json`, `WDR:lang/en_us.lang:94-98`):
  - The first time on a server or a LAN-opened world, a darkened screen with three red lines: "WARNING! Typed data are sent in PLAIN TEXT to the server." / "This means anyone can know what you're up to." / "NEVER write one of your passwords using this keyboard." It has a "Got ya" button.
  - The acknowledgement goes to `<mcDataDir>/wd_keyboard_warning.txt` (`:169-197`).
  - In non-public singleplayer the warning is skipped (`:63-66`).
  - After that there is no background, just one yellow label at the top left: "Keyboard hooked. Press escape to leave."
  - The mouse is grabbed (`mc.inGameHasFocus = true; grabMouseCursor()`, `:95-98`). So the camera keeps free-looking with the mouse while typing (INFERRED from vanilla: the camera rotates when `inGameHasFocus`). Mouse buttons do not reach the page in this GUI (INFERRED: `handleInput` drains only `Keyboard`, `:105-138`). To click, the player must leave the GUI, right-click, and open it again.
- **Key handling** (VERIFIED: `:116-137`):
  - Every key-down event becomes `PRESS` + `RELEASE` with the LWJGL2 key code, back to back. Real key-up events are ignored.
  - Every non-zero char becomes `TYPE`.
  - Repeat events are on (`WD:client/gui/WDScreen.java:154-158`), so holding a key repeats.
  - Esc closes the GUI (`:118-119`).
  - The GUI sends batches as JSON every 5 ticks (`:101,141-146`).
  - The server checks CLICK, broadcasts `UPDATE_TYPE` to all within 64 blocks, and plays one random `keyboardType` click (8 variants, volume 0.25, block category) at the keyboard for each batch. Everyone nearby hears it (`WD:entity/TileEntityScreen.java:752-810`).
  - Clients inject `injectKeyPressedByKeyCode`, `injectKeyReleasedByKeyCode` and `injectKeyTyped` with **modifiers 0** (`:771-790`). So Ctrl/Shift shortcuts and shift-selection never reach the page.
  - MCEF remaps only about 13 named keys. Every other key sends its char code as a Windows VK, so '$' becomes Home, '%' Left and '&' Up (research 02, keyboard finding, VERIFIED there).
- **"Cat on keyboard".** Any entity standing on the keyboard (feet 1/16 to 2/16 above it) has a 25% chance each collision to type one random character from an AZERTY string into the linked screen. That needs CLICK for players, or the screen's "others" CLICK right for mobs. There is an advancement "DAMN CATS" (VERIFIED: `WD:block/BlockPeripheral.java:263-275`; `WD:entity/TileEntityKeyboard.java:20,48-71`).

### 2.5 Remote Controller, Redstone Controller, Server, CC/OC (WD 1.12)

- **Remote Controller (block).**
  - Right-click: the Set URL GUI for the linked screen, if the player has CHANGE_URL (VERIFIED: `WD:entity/TileEntityRCtrl.java:18-41`).
  - The server accepts the URL only if the player is within reach of the remote block (`WD:net/server/SMessageScreenCtrl.java:289-298`).
  - Feel: a "TV remote" that sits next to a far or high screen.
- **Redstone Controller (block).**
  - Right-click (needs CHANGE_URL) opens a GUI with two URL fields: rising edge and falling edge. Each has a YT button, plus OK and Cancel (`WDR:gui/redstonectrl.json`).
  - A power change sets the linked screen's URL. Power is read like a dispenser, from the block or the block above (VERIFIED: `WD:entity/TileEntityRedCtrl.java:47-102`).
- **Server block.** A miniserv file host with an owner-only terminal GUI. It opens TCP 25566 (research 01/02). Not relevant; do not port.
- **CC/OC Interface** (VERIFIED: `WD:entity/TileEntityInterfaceBase.java:52-391`). Computer functions:
  - `isLinked`, `getScreenPos`, `getScreenSide`, `getOwner`, `can(right)`, `hasUpgrade`;
  - `getSize`, `getResolution`, `getRotation`, `getURL`;
  - `click(x,y,[click|down|up|move])`, `type(text ≤ 64)`, `typeAdvanced(≤ 16 events)`;
  - `setURL`, `setResolution`, `setRotation`, `runJS`, `unlink`.

  Rights are evaluated for the interface's owner.
  **Computer clicks never arrive.** `clickUnsafe` sends only `if(world.isRemote)`, and it runs on the server (VERIFIED bug: `WD:entity/TileEntityScreen.java:470-475`). This matches montoyo#86 and #157: "returns true but doesn't do anything" (VERIFIED: https://github.com/montoyo/webdisplays/issues/86, https://github.com/montoyo/webdisplays/issues/157).

### 2.6 minePad (WD 1.12)

- **Turning it on.** Sneak + right-click in the air opens the URL GUI with "Shut down", "Cancel" and "OK". This assigns a pad ID (world file `wd_next.txt`) and `PadURL` in NBT. The tooltip says "Sneak and right-click to turn on" (VERIFIED: `WD:item/ItemMinePad2.java:50-68`; `WD:net/server/SMessagePadCtrl.java:47-80`).
- **Using it.** Right-click opens `GuiMinePad` (VERIFIED: `WD:client/gui/GuiMinePad.java:54-136`):
  - a darkened background, a light-grey 16 px bezel, and the page with a 59:30 aspect ratio and a height of 480 px by default;
  - the keyboard and mouse go **directly** to the local browser, with no server hop: key down and up by key code, chars, mouse move (hover works), and all buttons;
  - no wheel;
  - a bug swaps the width and height used in the mouse mapping (`:104-107`);
  - Esc closes.
- **In hand.** In first person, the page is drawn live on the pad model, with the player's arm (`WD:client/renderers/MinePadRenderer.java:50-110`). Other players see only the item. A browser exists only while the pad is in the hotbar; this is polled every 10 ticks (`WD:client/ClientProxy.java:548-573`).
- **Navigation reporting.** A navigation inside the pad is reported to the server at most once per second (`WD:client/ClientProxy.java:386-401`).
- **Breaking it.** Dropping a minePad from 20 or more blocks breaks it into an Extension Card, with a glass-break sound and the "Reverse Engineering" advancement. With `hardRecipes=true`, crafting an Extension Card without that advancement yields a "Bad Extension Card" (VERIFIED: `WD:item/ItemMinePad2.java:82-114`; `WD:WebDisplays.java:370-379`).

### 2.7 Screen Configurator GUI (WD 1.12)

Opened by right-clicking a screen face with the Screen Configurator (not while sneaking). It opens even without rights, with controls disabled (VERIFIED: `WD:item/ItemScreenConfigurator.java:40-67`; `WD:client/gui/GuiScreenConfig.java:126-173,431-466`; layout `WDR:gui/screencfg.json`):

| Area | Content | Who may edit |
|---|---|---|
| Owner | Label "Screen owner: name" | - |
| Friends | List (click an entry to remove it, red selection), text field, and a "+" button. Tab autocompletes names from the server and cycles through results; Enter adds. An unknown name flashes red for 10 ticks (`:230-246,333-376`). | MANAGE_FRIEND_LIST |
| Permissions / Friends | Checkboxes: Change URL, Click & type, Manage friends, Manage others, Upgrade & link, Change resolution | Owner only (`:445`) |
| Permissions / Others | Checkboxes: Change URL, Click & type, Upgrade & link, Change resolution | MANAGE_OTHER_RIGHTS |
| Upgrades | Row of installed upgrade icons with tooltips. Click to remove: the item returns to the inventory or drops 1.5 blocks behind the screen, and `upgradeDel` plays (`WD:entity/TileEntityScreen.java:889-940`). | MANAGE_UPGRADES |
| Resolution | X and Y fields (digits only, 4 chars), "Lock ratio" (on by default), "Set Resolution" (enabled after an edit). A change **closes and recreates the browser**, so the page reloads (`WD:entity/TileEntityScreen.java:423-429`). | CHANGE_RESOLUTION |
| Rotation | Button cycling 0°, 90°, 180°, 270°. Going between landscape and portrait recreates the browser (`:1034-1037`). | CHANGE_RESOLUTION |
| Auto Volume | Checkbox with the tooltip "Careful! Auto volume only works on YouTube videos, and if enabled in client config!" (`WDR:lang/en_us.lang:80`) | MANAGE_UPGRADES |

- **Debounce.** Rights edits go to the server 40 ticks (2 s) after the last change (`WD:client/gui/WDScreen.java:51,319-340`).
- **Live updates.** If another player changes the same screen, the open GUI updates live and drops the local pending edit (`WD:client/gui/GuiScreenConfig.java:401-423`; `WD:client/ClientProxy.java:209-237`).
- **Sound.** Opening plays `screencfg_open` locally (`:172`).
- **Help.** F1 over a WD GUI or item opens montoyo's wiki in MCEF's example browser window (`WD:client/ClientProxy.java:505-523`).

### 2.8 Set URL GUI (WD 1.12)

VERIFIED: `WD:client/gui/GuiSetURL2.java:63-114`; `WDR:gui/seturl.json`; `WD:client/gui/controls/YTButton.java:24-76`; `WD:utilities/VideoType.java:71-80`; `WD:utilities/Util.java:149-151`.

- **Layout.** A darkened background, the label "URL:", one text field (max 65,535 chars) pre-filled with the current URL, a small **"YT" button**, then Cancel and OK (the pad adds "Shut down").
- **The YT button** is enabled only for youtube.com/watch or youtu.be URLs. It rewrites the field to `youtube.com/embed/<id>`, and Shift-click adds `?autoplay=1`.
- **Submitting.** Enter = OK. The GUI adds `http://` when there is no `://`, converts the host with punycode, sends the URL and closes. An empty field just closes.
- **What it lacks.** No history, no autocomplete, no back/forward/reload/home and no page title.
- **Blocked domains.** A blacklisted domain shows the built-in "Blacklisted" page (`WDR:html/blacklisted.html`).

### 2.9 Upgrades and the in-page JS API (WD 1.12)

VERIFIED: `WD:core/DefaultUpgrade.java`; `WD:client/JSQueryDispatcher.java:220-367`; `WD:block/BlockScreen.java:218-233`; `WD:entity/TileEntityScreen.java:504-598`.

- **Laser Sensor.** Enables the laser pointer on that face (section 2.3).
- **Redstone Input Port.**
  - When a neighbour's power changes, the page gets `webdisplaysRedstoneCallback(x, y, level)`, where x, y is the screen block inside the multiblock.
  - Pages can query `GetRedstoneAt` and `GetRedstoneArray`.
- **Redstone Output Port.**
  - Owner only: `SetRedstoneAt`, `ClearRedstone`, `IsEmitting`, `GetEmissionArray`.
  - These make individual screen blocks emit power 15.
- **GPS Module.** `GetLocation` returns {x, y, z, side}.
- **Always available:** `GetSize`, `GetUpgrades`, `IsOwner`, `GetRotation`, `GetSide`, and the `webdisplaysUpgradesChanged()` callback.

### 2.10 What the player sees on the screen (WD 1.12)

VERIFIED: `WD:client/renderers/ScreenRenderer.java:28-142,199-204`; `WD:client/ClientProxy.java:531-546`; `WD:WebDisplays.java:151-152,185-186`.

- **The page.** One full-bright, unlit quad, with blending off, 0.005 blocks in front of the face, inset by the 2/16 bezel.
- **The frame.** The block model draws one continuous dark frame around the whole multiblock through connected textures (16 variants `screen0..15`, `WDR:textures/blocks/`). There are no seams between blocks.
- **Turn-on animation.** When a browser is (re)created, the quad scales from the centre, 0 to 100%, in 100 ms: a CRT power-on. This happens on screen creation, on entering load range, and after a resolution or rotation change (`:42-52,94-104`).
- **Loading and blank states.** There is no loading indicator. A new browser shows whatever MCEF's texture holds until the first paint (INFERRED: blank). The home page is the spinning cube.
- **Out of range.** Beyond `unloadDistance` (32 blocks by default) the browser is closed and nothing is drawn over the block texture: a "switched off" screen. The client re-checks one screen per tick, round-robin. Re-entering within `loadDistance` (30) re-creates the browser at the **server URL**, so the page restarts.
- **Cursor and hover.** None is drawn. Hover exists only at the last injected mouse position (a click or a laser). MCEF 1.x re-sends the last mouse event every frame, which keeps YouTube's hover controls visible (research 02, OSR finding).
- **Sounds.** None for clicks, page loads or URL changes. The only sounds are:
  - keyboard typing: 8 variants, volume 0.25, heard by everyone near the keyboard;
  - upgrade add and remove (block category, volume 1.0);
  - the configurator opening (local);
  - the server block;
  - a meme sound, `ironic.ogg`.

  Sources: `WDR:sounds.json`, `WD:entity/TileEntityScreen.java:799-810,867,918`.
- **Chat.** Every message is a red (failure) or aqua (success) chat line prefixed "[WebDisplays] " (`WD:utilities/Util.java:131-141`). On first login there are three purple welcome lines (`WD:WebDisplays.java:393-403`).

### 2.11 Crafting (WD 1.12)

VERIFIED: `WDR:recipes/*.json`.

| Item | Recipe |
|---|---|
| Screen x8 | 3 glass panes / red, green, blue dye / 3 backlights |
| Backlight | 3 glass panes / 3 glowstone dust |
| Peripheral base | 8 wooden slabs around a redstone torch |
| Stone key x9 | 1 stone pressure plate |
| Keyboard | 8 stone keys around a peripheral base |
| Remote Controller | peripheral base over 3 wooden buttons |
| Redstone Controller | 7 redstone around a peripheral base, 3 wooden buttons below |
| Screen Configurator | 2 redstone torches / glass, diamond, glass / 3 glass |
| Linking Tool | redstone / stick / ender pearl and stone button |
| 650nm Laser Diode x2 | iron, glowstone, iron / iron, redstone, iron / gold |
| Laser Pointer | laser diode / battery cell / wooden button |
| Blank Upgrade | 4 quartz around an extension card (hard mode: needs the pad-break advancement) |
| Laser Sensor | blank upgrade and laser diode |
| minePad | 3 glass panes / RGB dye / diamond, battery pack, diamond |

### 2.12 Multiplayer: who sees and hears what (WD 1.12)

VERIFIED unless marked: `WD:entity/TileEntityScreen.java:262-264,351-354,600-606`; `WD:net/client/CMessageScreenUpdate.java:233-274`; `WD:client/ClientProxy.java:594-595`.

- **State.** The server holds URL, size, resolution, rotation, owner, friends, rights, upgrades and auto-volume. A client asks for the screen record when its tile entity loads. Friends and rights are **not** synced to clients ("let the server check them").
- **Relayed to every client within 64 blocks:**
  - URL;
  - resolution and rotation (each recreates the browser);
  - owner and upgrades;
  - every click, laser down, move or up, and keystroke batch;
  - JS runs and redstone-to-JS events;
  - screen deletion.

  Each nearby client's own browser executes the clicks and keys.
- **Not relayed:**
  - link navigation, which updates only the local copy (FIXME, `:679-693`);
  - scrolling inside the page (no wheel at all);
  - minePad input.
- **Late joiners and anyone more than 64 blocks away** see only the URL as the server holds it. Clicks and typing are not replayed. Every player's page can therefore show something different (montoyo#38 "everyone sees the video different?", research 02).
- **Visible only to the actor:** the laser beam, the minePad's page in hand, and the keyboard GUI label.
- **Visible to everyone near:** the page change itself in each viewer's own browser, the typing sound, and the upgrade sounds.

### 2.13 What the 1.20 fork changed in the dynamics (FK, VERIFIED)

| Change | Where | Status in the fork |
|---|---|---|
| Keyboard GUI keeps a visible OS cursor. The camera is steered, lerped each tick, toward the screen centre or the **focused element** (config `keyboard_camera=true`). Mouse moves, clicks and releases are un-projected into the world, ray-traced onto the screen and sent to the page as MOVE/DOWN/UP. They are applied locally at once and sent to the server as laser packets. | `FK:client/gui/GuiKeyboard.java:160-168,286-370`; `FK:client/gui/camera/KeyboardCamera.java:84-125,175-195`; `FK:config/ClientConfig.java:62-69` | Works per the code; the TODO list still lists "Center camera to screen when using keyboard / Allow actual cursor to be used via aiming it at the screen" (`/home/user/mysticpasta1/webdisplays/TODO.md`). |
| Keys: every press, release and char is sent individually and applied locally at once (no 250 ms wait), with GLFW key codes and **modifiers**. The page's cursor type is shown as the OS cursor while the keyboard is open. | `FK:client/gui/GuiKeyboard.java:160-168,192-220` | Present. |
| Laser: a mixin takes left, right and middle mouse while the pointer is in hand. Moves are applied locally each tick (hover) and sent to the server every 100 ms. An **R key toggles "mouse mode"** so the laser stays on (continuous hover) without holding a button. | `FK:mixins/MouseHandlerMixin.java`; `FK:item/ItemLaserPointer.java:51-143`; `FK:client/ClientProxy.java:649-659,857`; `FK:client/renderers/LaserPointerRenderer.java:33-43` | `switch_buttons=true` by default swaps left and right → CinemaMod#59, #47. |
| Crosshair replaced by a 15 px cursor sprite (`cursors.png`) chosen from the page's CEF cursor type (arrow, hand, I-beam...) while lasering. | `FK:client/ClientProxy.java:110-185` | Present. Still no cursor drawn on the screen itself (`FK:client/renderers/ScreenRenderer.java:138` TODO). |
| Block `use` ignores the off hand (fixes double clicks) and refuses while holding the laser pointer. | `FK:block/ScreenBlock.java:78-90` | Present. |
| minePad: wheel and Ctrl+wheel (zoom) plumbing; **pointer lock** for browser games (cursor recentred, deltas sent by JS); Shift+Esc closes, so Esc reaches the page. | `FK:client/gui/GuiMinePad.java:154-158,207-249` | Wheel marked "TODO: this doesn't work". |
| Regressions: no 128-block range check, the JS and redstone relays commented out, distance volume off. | research 01 | - |

---

## 3. UX problems players reported that touch interaction, and how AkashicWeb avoids them

Issue texts are from WebFetch on 2026-10-13 (VERIFIED as summaries), or from research 02 where noted.

| # | Report | Root cause | AkashicWeb avoidance (status) |
|---|---|---|---|
| U1 | montoyo#189 "Accidentally breaking screen on click": left-click breaks the screen; told to use right-click, and that no other click exists ("Simple. You don't.") | No left-click handler (`WD:block/BlockScreen.java`) | Left-click on the picture = page click, cancelled before vanilla mining; sneak + left-click breaks (`AW:client/screen/ScreenInteraction.java:17-18,52-72`). DONE in wave 1 (STATIC). |
| U2 | montoyo#248 "can only click, can not long press drag" (open, 2024) | Right-click = instant press+release (`WD:entity/TileEntityScreen.java:484-488`); drag only through the laser | AW wave 1 also sends DOWN+UP at once (`AW:client/screen/ClientScreens.java:404-412`). **Needed:** press on button-down, MOVE while held (crosshair moves), UP on release. The host already tracks pressed buttons for drag (`HOST:input.js:2-3,148-161`). |
| U3 | montoyo#55 '$' typed as Home, #58 "Crazy Keyboard" (any non-French layout) | MCEF `remapKeycode` sends chars as VK codes (research 02) | VK names only for named keys; text as composed chars; ABNT2 dead keys work (`AW:client/gui/KeyMapping.java:56-79`; `AW:client/gui/KeyboardRouter.java:9-120`). DONE (STATIC); needs a live ABNT2 test. |
| U4 | montoyo#143 and #178: Backspace dead, arrows dead, Enter acting as Shift+Enter; CinemaMod#36: arrows and shortcuts don't work | Key-up ignored, modifiers 0, PRESS+RELEASE sent together (`WD:client/gui/GuiKeyboard.java:123-131`); MCEF mapping | Real down/up with modifiers; named Backspace, Enter, arrows, Home/End, PageUp/PageDown, F-keys; Ctrl+A/Z/Y relayed; Ctrl+V pasted as text. DONE (STATIC). Test each key at G3. |
| U5 | montoyo#138: keys pressed while the screen loads **stay stuck**, so the player keeps walking | Lost key-up events | **Needed:** on GUI close, focus loss or screen loss, send keyUp for every held key and mouseUp for every held button, and call `KeyBinding.unPressAllKeys()` so movement does not stick (INFERRED fix). |
| U6 | CinemaMod#38: Esc in the keyboard also opens the game menu | Two Esc handlers | AW `KeyboardGui` owns all keyboard input; Esc → close only (`AW:client/gui/KeyboardRouter.java:59`). DONE. Add Shift+Esc → Escape to the page (fork minePad idea, inverted). |
| U7 | CinemaMod#59 and #47: laser left and right inverted | `switch_buttons=true` default (`FK:config/ClientConfig.java:71-76`) | Fixed table LWJGL 0→left, 1→right, 2→middle with a unit test; no swap option on by default; sanitise any other button (CinemaMod#73 crash on extra buttons). |
| U8 | CinemaMod#73: game crashes when an extra mouse button is pressed | Unchecked button index | Accept only buttons 0-2; ignore the rest (matrix row 33). |
| U9 | Scroll missing (WD never forwards the wheel); broken in MCEF versions (mcef#20, CinemaMod/mcef#102, research 02) | No `injectMouseWheel` call | Wheel while aiming scrolls the page instead of the hotbar (`AW:client/screen/ScreenInteraction.java:41-50`). DONE. Add Shift+wheel = horizontal. |
| U10 | montoyo#86 and #157: OpenComputers `click()` returns true but does nothing | `clickUnsafe` sends only on the client (`WD:entity/TileEntityScreen.java:470-475`) | No computer API for 10-25. If OC (in the pack: OpenComputers 1.12.61-GTNH, research 07) is added later: under D-016 there is no server→browser input path, so a computer click would need a design (e.g. a server→controller-client "automation input" with its own right). Not a port of WD. |
| U11 | CinemaMod#75: "cant put in url", shift-click does nothing | Sneak+use with an item bypasses the block in modern MC; the URL GUI depends on one gesture | Sneak + use on the picture opens the address bar whatever is in hand, because the Forge `MouseEvent` fires before vanilla (`AW:client/screen/ScreenInteraction.java:33-71`). Address keybind too (`AW:client/screen/ClientEvents.java:74-75`). DONE. The keybinds default to **unbound** (`AW:client/ClientProxy.java:89-91`), so discovery depends on the hint text. |
| U12 | CinemaMod#12: "screen is on, but it says it's not" on east, up and down faces | Used the player's facing instead of the clicked side | AW uses the hit's `sideHit` (`AW:client/screen/ScreenInteraction.java:76-86`; `AW:common/block/BlockScreen.java:70-91`). DONE. |
| U13 | CinemaMod#108: game freezes when clicking the keyboard after the display was turned off | Peripheral linked to a dead screen | AW `KeyboardGui` closes when the screen disappears or the player moves more than 24 blocks away (`AW:client/gui/KeyboardGui.java:23-24,87-91`). A future keyboard block must re-validate its link on every open. |
| U14 | Keyboard plain-text warning (WD's own lang); research 02/ADR-012: keystrokes replayed on every client; a password typed leaks | Server relay of keystrokes | D-016: keys go only to the controller's own browser; nothing typed reaches the server. Replace WD's scary warning with a short notice that shared screens hold no login (in-memory sessions, ADR-012). |
| U15 | Click lag: a WD click is a full server round trip; typing waits 250 ms batches (`WD:client/gui/GuiKeyboard.java:101`) | Server-side hit computation and relay | D-016: local apply, zero round trip; the server keeps only the controller lock (keepalive). |
| U16 | Griefing: anyone can click, type and change the URL on a fresh screen | `ScreenRights.DEFAULTS` | `DEFAULT_OTHERS = 0` (`AW:common/Rights.java:14-15`); the controller lock tells others "Someone else controls this screen" (`AW:client/gui/KeyboardGui.java:99-105`). |
| U17 | montoyo#71/#95, CinemaMod#6 (research 02): screens blank or restart at about 30-40 blocks; the interaction context (scroll, form text) is lost | Destroy beyond `unloadDistance`, recreate at the server URL | Matrix row 6 / ADR-012: hide instead of destroy inside the plaza; URL/epoch and media-clock convergence on return. |
| U18 | montoyo#85: wants a page overlay on the HUD with a cursor key "without losing camera control" | Not supported | Out of scope for 10-25; the same "cursor without losing camera" need is met by the in-world mouse mode (section 5). PRIVATE screens or a minePad later. |
| U19 | Laser dead zone (no issue found; code): the pointer does nothing at close range, and right-click with it in hand does nothing either | `objectMouseOver != BLOCK` condition plus non-upgrade item → block returns false | Own ray-plane aim that works from 0 to N blocks in one mode (section 5); no upgrade gating. |
| U20 | Hover menus and tooltips never open; YouTube controls stuck on screen (research 02: per-frame mouse re-send) | No hover; MCEF mouse-spam hack | Continuous local hover from the aim point; no synthetic mouse spam (matrix row 33). |

---

## 4. The table: WD behaviour → what the player sees/feels → AkashicWeb equivalent → improvement

"AkashicWeb equivalent" gives the wave-1 code (STATIC) and, where it changes, the D-016 target. "Improvement" is a proposal (INFERRED) for ADR-017/018.

### 4.1 Mouse

| WD behaviour (file:line) | What the player sees/feels | AkashicWeb equivalent | Improvement |
|---|---|---|---|
| Right-click, empty hand → server `hit2pixels` → MOUSE_CLICK (move + press + release, left, clickCount 1, mods 0) to all within 64 blocks (`WD:block/BlockScreen.java:170-180`; `WD:entity/TileEntityScreen.java:457-468,484-488`) | "Point and right-click" = a left click on the page, after a server round trip. No double-click. Every nearby page reacts. | Use **or** attack on the picture = page left click (`AW:client/screen/ScreenInteraction.java:52-72`). Local rights check, takes control if free, DOWN+UP sent to the server today (`AW:client/screen/ClientScreens.java:404-412,454-457`). D-016: applied straight to the controller's own host. | Keep "right-click = click" muscle memory and add left-click. Zero round trip. Click count from `ClickCounter` (double and triple click). **Real press and release** on button down and up. |
| Holding any non-upgrade item: no click; the item acts (block placed against the screen) (`WD:block/BlockScreen.java:127-131`) | Annoying: must empty the hand to click. Laser pointer at close range: nothing happens. | `ScreenInteraction` intercepts aimed use/attack whatever is held (`:36-72`). `BlockScreen.onBlockActivated` returns false with an item (`AW:common/block/BlockScreen.java:72-73`). | Decide explicitly: click with any item (recommended: the picture is "glass"), sneak + use with a block in hand = place, sneak + attack = break. Document the rule in the formed-screen chat hint. |
| Left-click = mining, no page click (no `onBlockClicked`) | Screens broken by accident (montoyo#189) | Left-click = page click; sneak + left-click breaks (`AW:client/screen/ScreenInteraction.java:17-18,65-72`) | Keep. Optional: require holding attack 0.5 s while sneaking before breaking a formed screen (protect the telão). |
| Sneak + right-click → Set URL GUI if CHANGE_URL, else red chat (`WD:block/BlockScreen.java:146-152`) | The only way to open the URL bar | Sneak + use → `AddressBarGui` (`AW:client/screen/ScreenInteraction.java:65-70`); unbound keybinds for address bar and keyboard (`AW:client/ClientProxy.java:89-91`; `AW:client/screen/ClientEvents.java:74-75,92-100`) | Keep the WD gesture. Ship the address-bar and keyboard keybinds **bound by default** (owner to pick keys; a clash check against the Forbidden pack is needed). Make the address bar a ModularUI2 panel (section 4.4). |
| `hit2pixels`: 2/16 bezel excluded, Y flipped except BOTTOM, rotation, vertical swap, × resolution, integer px (`WD:block/BlockScreen.java:235-291`) | Clicks land where aimed. A click on the frame silently does nothing. | `HitMath.uvOnFace`, `BORDER = 2/16` (`AW:common/HitMath.java:22`), u,v in 0..1; the server re-checks the ray-plane hit (ADR-010). | Same mapping (credit montoyo). Show the cursor only over the picture, so the bezel "dead" area is visible. |
| Range: right-click = vanilla reach (INFERRED ~4.5 survival); server allows 128 (`WD:net/server/SMessageScreenCtrl.java:299-300`); laser 64 (`WD:client/ClientProxy.java:582`) | Big or high screens are clickable only with the laser and upgrade; dead zone in between (U19) | Crosshair via `mc.objectMouseOver` = reach only (`AW:client/screen/ScreenInteraction.java:76-86`) | **Own ray-plane aim** from the eye against known screen faces (the client already has the geometry, `AW:client/screen/ScreenGeometry.java`). Reach in normal play; up to a configured range (e.g. 32-64 blocks, server-capped) in "mouse mode" or with a laser item. One continuous mode, no dead zone. |
| Laser: hold use beyond reach → LASER_DOWN, MOVE every ≥100 ms via server, UP on release; lock per screen; needs Laser Sensor + CLICK (`WD:client/ClientProxy.java:575-616,662-684`; `WD:entity/TileEntityScreen.java:942-987`) | "Press and sweep" drags, but stutters at 10 Hz and lags. Others' clicks are ignored while the lock is held. | Controller lock (`C2SControl`, ADR-012/D-016) replaces the laser lock; no drag yet | Drag with the normal buttons at render rate (local); the lock is the D-016 controller lock with keepalive. No upgrade gating. |
| Hover: none; mouse moves only inside clicks or laser (`WD:entity/TileEntityScreen.java:477-502`) | Hover menus never open; tooltips absent; YouTube controls frozen (U20) | Not sent (no client mouseMove today). The protocol has INPUT `mouseMove` (PROTOCOL §1, type 36); the host tracks the cursor type (`HOST:screens.js:238-239,426`) | **Hover from aim:** when the aim point changes by ≥1 page px, send `mouseMove` to the own host (≤30 Hz), and `mouseLeave`/move-out when the aim leaves the picture (INFERRED: Electron supports `mouseLeave`; to verify). Only for the controller (D-016). |
| Drag: laser only (montoyo#248) | Cannot select text, drag sliders, pan maps or use scrollbars | None (`ClientScreens.click` sends DOWN+UP together) | Press on button-down, MOVE while held, UP on release. Cancel the vanilla event while a drag is active; release on GUI open, death or leaving the screen (U5). |
| Wheel never forwarded (grep: no `injectMouseWheel`; only `WD:client/gui/WDScreen.java:180` for GUI lists) | The wheel changes the hotbar slot | Wheel while aiming (not sneaking) scrolls the page; the hotbar is untouched (`AW:client/screen/ScreenInteraction.java:41-50`) | Keep. Shift+wheel = horizontal. Sneak + wheel = hotbar (an escape hatch). |
| Buttons: only left ever reaches the page (`button 1` hard-coded) | No right-click menus (not rendered in OSR anyway), no middle-click | Left only today | Middle → page middle (autoscroll). Right → page `contextMenu` only if the host draws one (Electron OSR draws none: INFERRED). Otherwise keep right = left as in WD. Sanitise other buttons (U8). |
| Off-hand: n/a in 1.12 WD; fork ignores the off hand to avoid double clicks (`FK:block/ScreenBlock.java:89`) | Double clicks in the fork | 1.7.10 has no off hand | - |
| Fork keyboard mode: visible cursor un-projected onto the screen; camera steered to the focused element (`FK:client/gui/GuiKeyboard.java:286-357`; `FK:client/gui/camera/KeyboardCamera.java`) | Typing and clicking without leaving the GUI; the camera glides to the input field | KeyboardGui frees the cursor but uses it only for the wheel, at the page centre (`AW:client/gui/KeyboardGui.java:78-85`) | **Combined keyboard+mouse "navigation mode"** (section 5): grabbed mouse, crosshair = pointer, buttons and wheel go to the page, keys go to the page, Esc leaves. Optional camera glide to the focused element (fork idea) as a setting. |

### 4.2 Keyboard

| WD behaviour (file:line) | What the player sees/feels | AkashicWeb equivalent | Improvement |
|---|---|---|---|
| Needs a Keyboard block, linked by a Linking Tool, plus CLICK; right-click it opens `GuiKeyboard` (`WD:entity/TileEntityKeyboard.java:23-46`; `WD:item/ItemLinker.java:47-126`) | A physical keyboard in the world; three items to craft and link before typing | Keybind (unbound) or the "Keyboard" button in the address bar opens `KeyboardGui` for the aimed screen (`AW:client/gui/AddressBarGui.java:75,153`; `AW:client/screen/ClientEvents.java:92-100`) | Keep the keybind/button path **and** add a Keyboard block for the WD feel (spawn desks, the telão booth). Right-click opens the same GUI. Link automatically to the screen it faces within N blocks, or by sneak-clicking keyboard then screen (no separate linker item). |
| First use on a server: red PLAIN TEXT / never type passwords warning + "Got ya"; acknowledgement file in mcDataDir (`WD:client/gui/GuiKeyboard.java:63-66,169-197`; `WDR:lang/en_us.lang:95-97`) | Scary, but honest for WD | None needed for the relay (D-016: keys never leave the client) | One-time pt-BR notice in a ModularUI2 toast: "O que você digita vai só para o seu navegador. Telas compartilhadas não guardam login." (wording to the owner). |
| GUI visuals: no background, yellow "Keyboard hooked. Press escape to leave." top left (`WDR:gui/keyboard.json`) | The world stays fully visible; minimal chrome | No background; a bottom bar (alpha ~0.63 black) with "Typing on the screen — Esc to leave", the URL, or "Someone else controls this screen" (`AW:client/gui/KeyboardGui.java:93-116`; lang `:142-143`) | Keep the minimal style. Add a small key-cap/caret icon and the page title. Show modifier state (Shift/Ctrl/AltGr) and a "paste" flash. Use ModularUI2 for the overlay, with no pause and no dimming. |
| Mouse grabbed while typing; the camera free-looks; clicks do not reach the page (INFERRED from `WD:client/gui/GuiKeyboard.java:95-98,105-138`) | Type ↔ click requires leaving and reopening | Cursor free (vanilla GuiScreen), so no camera look; wheel scrolls the page centre | Navigation mode (section 5): grabbed mouse + crosshair pointer + buttons to the page while typing. That is the owner's "keyboard and mouse in the world". |
| Each key-down → PRESS+RELEASE pair; key-up ignored; chars → TYPE; repeat on; modifiers 0 (`WD:client/gui/GuiKeyboard.java:116-133`; `WD:entity/TileEntityScreen.java:771-790`) | Shortcuts, shift-select and held arrows inside games broken; '$' bug (U3, U4) | Real keyDown/keyUp with Shift/Ctrl/Alt/Meta; chars from OS composition; surrogate pairs; AltGr never a shortcut; Ctrl+V paste (≤512 chars); Ctrl+A/Z/Y relayed; Ctrl+C/X not relayed (`AW:client/gui/KeyboardRouter.java:9-120`); repeat on (`AW:client/gui/KeyboardGui.java:35-38`) | With D-016, allow Ctrl+C/X **locally** (the controller's own browser writes the controller's own clipboard), which the "never relay" rule no longer forbids. Keep the paste cap. Add a release-all on close (U5). |
| Batches every 5 ticks (250 ms) → server → replay on all clients within 64 + typing sound at the keyboard (`WD:client/gui/GuiKeyboard.java:101,141-146`; `WD:entity/TileEntityScreen.java:796-801`) | Typing echo is laggy; everyone nearby hears the clicks; everyone's page receives the text | Wave 1: per event via C2SKey (being removed by D-016) | Local, immediate. For the **ambient feel** (others hear typing): a content-free "typing" activity ping at ≤2 Hz from the controller → server plays the keyboard sound at the keyboard block. This needs a D-016-compatible packet carrying no key data (owner or lead decision; it leaks typing rhythm only). |
| Esc closes (`WD:client/gui/GuiKeyboard.java:118-119`) | No way to send Esc to the page (close a dialog, leave video fullscreen) | Esc closes (`AW:client/gui/KeyboardRouter.java:59`) | Esc closes; **Shift+Esc sends Escape to the page** (the inverse of the fork minePad, `FK:client/gui/GuiMinePad.java:154-158`). Show it in the hint. |
| "Cat on keyboard": entities on the block type random AZERTY chars (`WD:block/BlockPeripheral.java:263-275`; `WD:entity/TileEntityKeyboard.java:48-71`) | A fun easter egg, and a griefing vector (walk on a keyboard to type into someone's page) | None | Do not port as typing. A purely cosmetic sound or particle is fine. |
| minePad GUI: keys straight to the local browser (`WD:client/gui/GuiMinePad.java:81-102`) | Responsive (no server) | Same principle now applies to all screens (D-016) | - |

### 4.3 Peripherals and items

| WD behaviour (file:line) | What the player sees/feels | AkashicWeb equivalent | Improvement |
|---|---|---|---|
| Laser Pointer item: held + use held + beyond reach; red first-person beam; Laser Sensor upgrade required (`WD:client/ClientProxy.java:575-616`; `WD:client/renderers/LaserPointerRenderer.java:82-115`) | Long-range "remote mouse" for big screens; a hidden dead zone; others see nothing | None | Optional "Ponteiro laser" item = long-range aim (≤64 blocks, server-capped) + all buttons + hover + a beam. Others see the beam only if D-016 is amended to relay a coarse pointer (owner question Q1). Otherwise the "navigation mode" keybind gives the same function with no item. |
| Linking Tool: screen first, then peripheral; NBT tooltip; sneak resets (`WD:item/ItemLinker.java:47-126`) | Two-step ritual; easy to forget which screen is linked | None | Auto-link on placement (nearest screen face in front within 8 blocks) plus sneak-click re-link; show the link in WAILA or a tooltip. No separate item. |
| Remote Controller block: right-click → Set URL for the linked screen; must be within reach of the remote (`WD:entity/TileEntityRCtrl.java:18-41`; `WD:net/server/SMessageScreenCtrl.java:289-298`) | "TV remote" next to an unreachable screen | Address bar via keybind on the aimed screen; nothing for out-of-sight screens | For the telão: a "Controle remoto" block (or the address-bar keybind with long-range aim) that opens the ModularUI2 address bar for its linked screen. Server checks reach to the remote plus rights. Low effort once the address bar exists. |
| Redstone Controller: rising/falling-edge URLs (`WD:entity/TileEntityRedCtrl.java:47-102`) | Automation (door opens → screen shows X) | None | After the event: easy, because the server owns the URL (it just calls the SetURL path with site policy and audit). |
| Upgrades (Laser Sensor, Redstone In/Out, GPS) + JS API (`WD:client/JSQueryDispatcher.java:220-367`) | Progression and gating; scriptable pages | None (security baseline: no page→game API for 10-25, ADR-015) | Do not port the upgrades as gates for basic input. A JS bridge, if ever, only on PRIVATE/owner pages with an allowlist. |
| CC/OC interface; `click()` broken (`WD:entity/TileEntityScreen.java:470-475`) | Automation that silently fails (montoyo#86, #157) | None; FakePlayers rejected (`AW:common/block/BlockScreen.java:88`) | Later, OpenComputers only (in the pack); needs a D-016-compatible design (U10). |
| minePad: handheld browser; page drawn on the in-hand model (first person only); browser lives while in the hotbar; breaks when dropped from ≥20 blocks (`WD:item/ItemMinePad2.java:50-114`; `WD:client/renderers/MinePadRenderer.java:50-110`; `WD:client/ClientProxy.java:548-573`) | A personal tablet; others see a plain item | None (PRIVATE mode is post-event, ADR-012 item 8) | Post-event: "Tablet" = a PRIVATE screen in a ModularUI2 panel with the page as a widget (hover, wheel, pointer lock); the in-hand render reuses ScreenTexture. |
| Ownership Thief [ADMIN] item (`WD:item/ItemOwnershipThief.java:41-108`) | An admin hands over a screen by item | `/webadmin takeover`, `clear`, `lock` (`AW` lang `cmd.admin.usage`) | Keep commands; add owner transfer to the config panel for admins. |
| Screen Configurator item: right-click face → config GUI (`WD:item/ItemScreenConfigurator.java:40-67`) | A dedicated tool | No config GUI yet; C2SRights exists in the protocol (PROTOCOL §2, id 22) | ModularUI2 "Configurar tela" panel opened from the address bar (gear button) and by sneak+use with an optional configurator item. No extra item required. |

### 4.4 GUIs

| WD behaviour (file:line) | What the player sees/feels | AkashicWeb equivalent | Improvement |
|---|---|---|---|
| Set URL GUI: URL field (pre-filled), YT→embed button, Cancel/OK; `http://` added; punycode; no history/back/home (`WD:client/gui/GuiSetURL2.java:63-114`; `WDR:gui/seturl.json`) | Bare; YouTube watch pages needed the YT trick | `AddressBarGui`: URL field + Go, Back, Forward, Reload, Home, Keyboard, Take/Release control, Close; buttons enabled by rights and `canGoBack/Forward` (`AW:client/gui/AddressBarGui.java:18-19,64-99`) | ModularUI2 address bar, browser-like: title + favicon-less site name, loading progress, stop/reload toggle, local history dropdown and suggestions (per player, client side), "who controls" line, site-policy hint when blocked. No YT trick needed (Electron plays watch pages). |
| Screen config GUI: owner, friends (Tab autocomplete, click to remove), rights matrix (friends 6, others 4), upgrades row, resolution X/Y + lock ratio, rotation cycle, auto volume (`WD:client/gui/GuiScreenConfig.java`; `WDR:gui/screencfg.json`) | Dense but complete; live updates when others edit; a 2 s debounce | None on the client | ModularUI2 tabs: **Acesso** (owner, friends with online-player autocomplete, rights matrix with AW bits 1/2/4/8/16), **Tela** (resolution presets 480p/720p/1080p within the server cap, rotation, mode SHARED/PRIVATE later), **Som** (distance volume, mute), **Admin** (lock, clear, transfer). Live update on S2CScreenState like WD. |
| Keyboard GUI (above) | - | `KeyboardGui` | Overlay (section 4.2). |
| Redstone Controller GUI (`WDR:gui/redstonectrl.json`) | Two URL fields with YT buttons | None | Post-event. |
| minePad GUI: grey 16 px bezel, page, direct input, no wheel (`WD:client/gui/GuiMinePad.java:54-130`) | Tablet feel | None | Post-event Tablet (above). |
| JSON-driven custom GUI framework (~5k lines, research 01) | Consistent WD look | Vanilla GuiScreen (`AddressBarGui`, `KeyboardGui`) | ModularUI2 2.3.85 (D-013/D-017); match the AkashicFM house style. |
| F1 over a WD GUI or item → wiki in MCEF's example browser (`WD:client/ClientProxy.java:505-523`) | In-game help | `/web doctor`, placeholders with reason codes | A "?" button in each panel that opens a pt-BR help page **on the aimed screen or a private panel**, never an external OS browser. |
| Chat feedback, red/aqua "[WebDisplays] ..." (`WD:utilities/Util.java:131-141`) | Chat spam during building and clicking | Reason codes as chat notices (`AW:client/gui/ClientNotices.java`) | Interaction errors (no permission, controlled by X, too far) as short HUD toasts near the crosshair, not chat. Formation and admin messages stay in chat. |

### 4.5 Visual feedback

| WD behaviour (file:line) | What the player sees/feels | AkashicWeb equivalent | Improvement |
|---|---|---|---|
| Full-bright page quad, 2/16 inset, z +0.005 (`WD:client/renderers/ScreenRenderer.java:33-38,109-124`) | A glowing monitor, visible at night | Same idea: full-bright Tessellator quad, 2/16 frame, Angelica-aware, skipped in the shadow pass (`AW:client/render/ScreenRenderer.java:32-42`) | Keep; test BSL/Complementary (ADR-005). |
| Connected bezel textures `screen0..15`: one frame around the multiblock (`WD:block/BlockScreen.java:88-105`; `WDR:textures/blocks/`) | Looks like one big monitor, no seams | One `screen.png` for every block (`AW:common/block/BlockScreen.java:39`) | Port the 16-variant connected texture via `getIcon(IBlockAccess,x,y,z,side)` (research 01 portability). A cheap, big gain to the "real monitor" look. |
| Turn-on: quad scales 0→100% in 100 ms when the browser is (re)created (`WD:client/renderers/ScreenRenderer.java:94-104`) | A satisfying "power on" | None (placeholder card, then the page) | Add a turn-on effect (≈150 ms scale or a brightness ramp) on the first frame after a placeholder; a reverse "power off" on unload. |
| No loading indicator; blank until the first paint (INFERRED) | "Is it broken?" (white-screen reports, research 02) | Placeholder cards with reason codes: waiting for server, installing %, starting browser, failures (`AW:client/screen/PlaceholderModel.java:8-14`; lang placeholder keys) | Add a thin **loading bar** along the top edge of the picture driven by STATE.loading, plus the title for 2 s after navigation. |
| No cursor on the page; hover only at the last click; fork TODO (`FK:client/renderers/ScreenRenderer.java:138`) | Players cannot see where they point on a big screen | None | **Draw the cursor on the page** at the aim point: shape from STATE.cursor (arrow/hand/text/wait; `HOST:screens.js:238-239`), scaled to the page, only for the local aiming player (D-016). Replace the vanilla crosshair with the same sprite while aiming at a controllable picture (fork idea, `FK:client/ClientProxy.java:110-185`). |
| Laser beam: first person only (`WD:client/renderers/LaserPointerRenderer.java:82-115`) | Others cannot tell who is pointing | None | Show a "Controlado por <nome>" strip under the picture for everyone (the server already has `controllerName`, ADR-012 lead amendment). A beam or dot for others needs Q1. |
| Out of range: browser closed, block texture only; one screen re-checked per tick (`WD:client/ClientProxy.java:531-546`) | Screens "switch off" as you walk away and restart on return | LOD/budget: fps tiers, frozen frame + "aproxime-se" beyond the budget (ADR-012 item 7; `AW:client/screen/ScreenLod.java`, `BrowserBudget.java`) | Keep the frozen last frame plus a dim overlay rather than black; resume at the converged URL/media time. |
| Sounds: typing, upgrade add/remove, configurator open; none for clicks or navigation (`WDR:sounds.json`) | Quiet interaction; typing heard by all | None | Subtle local click tick on page press (option); keyboard typing sound at the keyboard block (activity ping, see 4.2); a short power-on blip with the turn-on effect. Respect the MC master/blocks volume. |
| Chat welcome on first login (`WD:WebDisplays.java:393-403`) | Onboarding pointer to F1 help | `akashicweb.msg.formed` = "Screen formed: %sx%s blocks. Right-click it to open the browser." (lang line 8) | Extend the formed message with the three gestures (right-click = click, sneak+right-click = address bar, keybind = keyboard). Show a first-time ModularUI2 tip card. |

---

## 5. Proposed AkashicWeb interaction model for ADR-017/018

All INFERRED: a design that honours D-016 and D-017, for the lead to accept, amend or reject.

1. **Aim.** A client-side ray from the eye along the look vector against known screen faces (picture only, the bezel excluded). It works from 0 blocks up to `reach` in normal play, and up to a server-advertised `aimRange` (default 32, telão 64) in navigation mode or with a laser item. The server validates reach and line of sight only when the controller lock is taken or kept alive (D-016), not per event.
2. **Normal play (no GUI open)**, WD gestures kept, the rest added:
   - **Right- or left-click on the picture** = page left button. Press on down, move while held, release on up. Double-click by timing.
   - **Middle** = middle.
   - **Wheel** = scroll at the aim point. Shift+wheel = horizontal. Sneak+wheel = hotbar.
   - **Sneak + right-click** = address bar. **Sneak + left-click** = break.
   - **Hover** = `mouseMove` from aim changes (≤30 Hz).
   - **Cursor sprite** on the page and as the crosshair.
   - Everything above only when the player can control the screen (rights plus lock: free or own). Otherwise the click becomes a toast ("Controlado por X").
3. **Navigation mode** (keybind toggle, and opened by the Keyboard block or the address-bar Keyboard button). WD's "Keyboard hooked" crossed with the fork's mouse mode:
   - the mouse stays grabbed (camera free-look) and the crosshair is the pointer;
   - mouse buttons and the wheel go to the page;
   - **all keys go to the page**, so WASD types and does not walk;
   - Esc leaves; Shift+Esc sends Escape to the page;
   - a minimal overlay (ModularUI2, no dim): the screen title, "Esc para sair", modifier state;
   - optional setting "câmera segue o campo de texto" (fork `keyboard_camera`).
   - The mode closes on its own when the screen unloads, the lock is lost, the player is hurt, or the player is more than `aimRange` away. It always releases held keys and buttons and unpresses vanilla key bindings.
4. **D-016 guarantees.** Pointer and key events go only to the local host. The server sees only C2SControl take/keepalive/release. The followers' view converges on URL, epoch and media clock (ADR-012).
5. **Physical props (WD feel, optional for 10-25):**
   - a Keyboard block that opens navigation mode for its linked screen (auto-link, no linker item);
   - a Remote block that opens the address bar for its linked screen;
   - a Laser Pointer item for long range.

   None of these is required to browse.
6. **GUIs in ModularUI2:** address bar (browser-like), screen configuration (Acesso/Tela/Som/Admin), consent/installer, first-use tip. Live updates from S2CScreenState, as WD's config GUI did.
7. **Visual polish:** connected bezel textures, turn-on effect, loading bar, page cursor, a "Controlado por" strip, frozen-frame LOD.

### Open questions for the owner or the lead

- **Q1.** On shared screens (the telão), should other players see the controller's pointer (a dot, laser or cursor) at a low rate? That relays pointer *position* (not keystrokes) through the server, so it needs an amendment to D-016's "no pointer position travels to the server".
- **Q2.** Default keys for "navigation mode" and "address bar" in the Forbidden pack (both are unbound today). A clash check against the pack's key bindings is needed.
- **Q3.** Should right-click with an item in hand click the page (recommended) or perform the item's use?
- **Q4.** Should other players hear typing at a Keyboard block? That needs a content-free activity ping.
- **Q5.** Do we ship the WD props (Keyboard block, Remote, Laser Pointer) for 10-25 with recipes in the pack's style, or only the keybind path?

---

## 6. Limits of this research

- Not run: Minecraft, Web Displays, MCEF, the AkashicWeb client or host. No input was tested on LWJGL2 or lwjgl3ify, no ABNT2 test.
- INFERRED points to confirm in a dev client:
  - vanilla camera free-look while a GUI grabs the mouse;
  - mouse buttons being dropped in WD's keyboard GUI;
  - WD's blank first frame;
  - Electron `mouseLeave` support and the absence of OSR context-menu rendering;
  - the survival reach value.
- Issue evidence is WebFetch summaries of single GitHub pages (2026-10-13). GitHub search pages were fetched for "keyboard", "click" and a mouse/scroll/laser/minepad/drag query on montoyo/webdisplays, and one combined query on CinemaMod/webdisplays. Other categories rely on research 02.
- AkashicWeb citations are the wave-1 code at HEAD `e05fee0` in `/home/user/akashicweb`. Wave 2 is changing `ClientScreens`, the protocol (C2SPointer/C2SKey/S2CInput removed by D-016) and the GUIs in other worktrees, so line numbers will move.
