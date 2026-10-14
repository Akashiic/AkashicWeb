# AkashicWeb wave 3: final specification

In-world keyboard and mouse in the Web Displays style, the ModularUI2 GUI set, and the stack decisions behind them (ADR-017, ADR-018).

| Item | Value |
|---|---|
| Date | 2026-10-13 (UTC) |
| Version | **Final (v2).** v1 revised against two reviews: **RA** (input-layer audit: 1 high, 11 medium, 10 low) and **RB** (owner-goal audit: 9 high, 7 medium, 7 low). Every finding is accepted, adapted or rejected with a reason in §0.4. |
| Document grade | AUTHORED. Each claim carries VERIFIED (read in code or docs, or run; cited) or INFERRED (reasoning, design, unobserved behaviour). "VERIFIED (RA)" means the reviewer read it and cited file:line; the rows I re-read myself carry the citation without that tag. |
| Code base | Branch `claude/clever-goodall-1t9p9u` at **`b1019e0`**. W3-0 is merged (`e273183`); `git diff --stat 8730941..b1019e0` shows 28 files, +903/−44, of which `b1019e0` itself is the host test harness and a changelog line (2 files, +6/−2). `git worktree list` shows one worktree. VERIFIED: `git log`, `git diff --stat 8730941..b1019e0`, `git worktree list`. Last recorded build at this head: Java 656 tests, 2 skipped, 0 failed; host 216/216 with Electron 44.7.0 (VERIFIED as recorded in `CHANGELOG.md:50`; AUTOMATED there, Linux; not re-run here). |
| Inputs | Research R1 (ModularUI2), R2 (Web Displays dynamics), R3 (client audit), R4 (GTNH stack), R5 (free cursor); designs F, M, H; judges J1, J2; spec v1; reviews RA, RB; evidence `docs/evidence/2026-10-13-first-browser-in-game.md` |
| Run for this spec | Read-only checks only (§10). Nothing was built, launched or tested, and nothing in `/home/user/akashicweb` was edited. |

**Citation prefixes.**

| Prefix | Location |
|---|---|
| `AW:` | `/home/user/akashicweb/src/main/java/com/akashiic/web/` at `b1019e0` |
| `AWR:` | `/home/user/akashicweb/src/main/resources/assets/akashicweb/` |
| `HOST:` | `/home/user/akashicweb/host/src/` |
| `DOC:` | `/home/user/akashicweb/docs/` |
| `MC:` | `/home/user/akashicweb/build/rfg/minecraft-src/java/net/minecraft/` (Forge 10.13.4.1614 sources) |
| `MCA:` | Vanilla 1.7.10 `pt_BR.lang` asset, `/root/.gradle/caches/retro_futura_gradle/assets/objects/4e/4eedcb87b68eb6c17cc6ad8bb32b381341da08ee` |
| `MUI:` | ModularUI2 tag `2.3.85-1.7.10`, `scratchpad/wave3/src/mui2-2.3.85/src/main/java/com/cleanroommc/modularui/` |
| `GL:` | GTNHLib tag `0.11.51`, `scratchpad/wave3/src/gtnhlib-0.11.51/src/main/java/com/gtnewhorizon/gtnhlib/` |
| `LW:` | LWJGL 2.9.x sources, `scratchpad/wave3/src/lwjgl2/src/` |
| `HP:` | Hodgepodge sources, `scratchpad/wave3/src/hodgepodge/src/main/java/com/mitchej123/hodgepodge/` |
| `ANG:` | Angelica 2.2.20, `scratchpad/wave3/src/angelica/` |
| `EC:` | EntityCulling 1.6.4, `scratchpad/wave3/src/entityculling-1.6.4-1.7.10/` |
| `AV:` | Akashic Voice (same owner, in the pack), `/home/user/Akashic-Voice/src/main/java/dev/akashic/voice/voice/client/` |
| `AFM:` | `/home/user/AkashicFM/src/main/java/com/akashiic/fm/` |
| `WD:` / `WDR:` | Web Displays 1.12.2 at `/home/user/montoyo/webdisplays/` (`src/main/java/net/montoyo/wd/` / `src/main/resources/assets/webdisplays/`) |
| `R1`..`R5`, `F`, `M`, `H`, `J1`, `J2`, `RA`, `RB` | Research, designs, judges and reviews; their own grades apply to what they cite |

`scratchpad` = `/tmp/claude-0/-home-user/c5c14dc0-9bf3-521b-b7c6-50e7f96d7b47/scratchpad`.

---

## 0. Basis

### 0.1 Ground truth at `b1019e0` (what wave 3 keeps, replaces or adds)

All rows VERIFIED by reading the cited code unless marked.

| Area | State today | Wave 3 |
|---|---|---|
| In-world reach | Crosshair ray to `ClientScreens.POINTER_REACH = 24` (`AW:client/screen/ClientScreens.java:116`), block-only occlusion trace each frame (`AW:client/screen/ScreenInteraction.java:166-189`); entities are ignored | Reach from `S2CHello.interactRange` (default 24); **entities occlude like blocks** (§3.6, C-40) |
| Press, drag, hover | `pointerDown`/`pointerUp` with `PointerPress`, hover per frame (`ScreenInteraction.java:47-130`; `ClientScreens.java:596-635`). A release whose press vanilla saw is never cancelled (`ScreenInteraction.java:50-54,73`) | Same semantics in `client.input`, balanced release on every path; the vanilla-release rule becomes invariant I9 |
| Held item | Click and wheel need an empty hand (`ScreenInteraction.java:59-63,93`); **sneak + use opens the address bar with any item** (`:86-91`); `BlockScreen.onBlockActivated` gives up when an item is held (`AW:common/block/BlockScreen.java:77`) | Only an empty hand or the Laser Pointer points or opens the address bar; every other item acts (C-04, C-13) |
| First take | Optimistic for `TAKE_PENDING_MS = 3000`, retry ≤ 1/s, keepalive ≤ every 5 s, `leaveSoon` grace 5 s (`ClientScreens.java:107-121,659-690,710-716`); refusal ends optimism and resyncs (`endOptimism`, `:757-770`) | Kept; a refusal also releases page input and **closes an open session** (C-05) |
| Wheel | Any wheel on a free screen claims it (`ClientScreens.java:637-643`); `signum × max(1, abs/120)` notches (`ScreenInteraction.java:61-71`) | **Only a click takes a screen** (C-41); fractional notches |
| Aim plane | `HitMath.rayHit` on the unlifted plane, allocating (`ClientScreens.java:555`) | Lifted plane, zero allocation (R5 §2.4, SF-2) |
| Pointer modifiers | M2 serialises modifiers for keys only (`AW:client/host/HostMessages.java:117-155`); hover sends `BUTTON_LEFT`, modifiers 0 (`ClientScreens.java:623-635`) | Serialise for mouse and wheel; mouse modifiers OR the polled keyboard state (§3.5.5) |
| Page cursor | Host STATE `cursor` (`HOST:screens.js:238-241`); `PageState` ignores it | Parse it; draw it in the world |
| Fullscreen | Host samples HTML fullscreen (`HOST:media.js:121,172`; `HOST:screens.js:242-243`); Java parses it (`AW:client/sync/LocalMedia.java:22,35`) and nothing reads it; `S2CMediaClock` has no such field (`DOC:PROTOCOL.md:114`) | **Video-fit convergence** for followers (§3.3.9, C-39) |
| C2S decoding | C2S packets decode exactly; S2C tolerate trailing bytes (`AW:common/net/AkwMessage.java:25-27`; `DOC:PROTOCOL.md:128`) | One optional trailing field per C2S packet, sent only to a server whose hello says `protoMinor ≥ 1` (§6.1.4, C-46) |
| Keyboard router | Key-0 events typed in any state (`AW:client/gui/KeyboardRouter.java:84-88`); dead key + Space types the accent (`:72-75`); letters only as CHAR; Ctrl+C/X dropped (`:79-83`); AltGr is text (`:65`); F1-F12 to the page, F2/F11 included (`AW:client/gui/KeyMapping.java:72-74`) | Router v2 (§3.7) |
| Typing GUI | Vanilla `KeyboardGui`: polls `Keyboard.isKeyDown` for modifiers (`AW:client/gui/KeyboardGui.java:49-52`); no clicks; wheel at the page centre | MUI2 `SessionScreen` |
| Address bar, consent | Vanilla `AddressBarGui`, `ConsentGui` | MUI2 screens |
| Key bindings | "Teclado da tela", "Barra de endereço da tela", both `KEY_NONE` (`AW:client/ClientProxy.java:91-94`) | Ids kept (`MC:client/settings/GameSettings.java:1054,1167` stores `key_<description>`); one-time free-key assignment (C-10) |
| Renderer | No cursor, highlight or polygon offset; shadow pass skipped (`AW:client/render/ScreenRenderer.java:64`) | §3.9 |
| EntityCulling | No `isForcedVisible()` on `TileEntityScreen` (`AW:common/block/TileEntityScreen.java`) | Add, stateless (EntityCulling calls it from its cull thread, `EC:.../CullTask.java:55-75`) |
| `S2CHello` | Ends with `playerUuid` (`AW:common/net/S2CHello.java:51-76`); re-sent on policy reload and kill switch (`AW:server/ScreenSync.java:107-123`) | Append the limits block (§6.1.1) |
| Lock rules | Take and keepalive share the full view check (`AW:server/ActionRules.java:47-52`); sweep releases beyond `interactRange + 4` (`AW:server/ControlLock.java:39-48`); a refused take is recorded quietly (`AW:server/ScreenActions.java:226-228`) | Control grants (§3.3) |
| Reason codes | W3-0 appended `URL_DNS_VIA_PROXY`, `POLICY_INVALID` (`AW:common/ReasonCode.java:107,109`); no `SCREEN_PROTECTED` | Append `SCREEN_PROTECTED` (no collision) |
| Build | `usesMixins = false`, `forceEnableMixins = false` (`gradle.properties:101,129`); no runtime dependencies (`dependencies.gradle`) | MUI2 + GTNHLib (ADR-017) |
| Dev harness | Dev client had no GTNHLib; dev Crucible had GTNHLib but no MUI2 (`DOC:evidence/2026-10-13-crucible-boot-and-dev-client.md:17,51`); **no Hodgepodge** on either, although the pack ships 2.7.189 on both sides (`DOC:research/07-forbidden-pack-inventory.md`) | Both sides get UniMixins, GTNHLib, MUI2 **and Hodgepodge** (§2 Consequences) |

**Wave-2 UX findings folded in** (VERIFIED, `DOC:evidence/2026-10-13-first-browser-in-game.md` "Findings for wave 3"):
1. Setting the URL of a free SHARED screen does not take control, so followers drift. Fixed in §3.3.6 item 6 (take before SetUrl, no server change).
2. The visitor refusal reads "...nesta tela. Developer": a visitor's attack is not intercepted (`ScreenInteraction.java:93-95`), vanilla tries to break the block, and the server answers `NO_PERMISSION` with the owner's name as `%s` (`AW:server/ServerEvents.java:86`; `AWR:lang/pt_BR.lang:26`). Fixed twice: the client swallows the attack with a toast (C-12), and a real break attempt gets `SCREEN_PROTECTED` (§6.1.3).
3. A direct media URL plays at native size. Fixed by the host's media-document fit (§6.2).
4. The harness needs `xdotool windowfocus` (§8.3).

### 0.2 How the designs were merged

| Judge | F | H | M | Winner |
|---|---|---|---|---|
| J1 | 47 | 44 | 40 | F |
| J2 | 44 | 46 | 42 | H |
| Sum | **91** | 90 | 82 | — |

- **Backbone from F:** the Web Displays gestures; a typing mode that keeps the mouse grabbed and the camera live, driven by our own `LookDriver` (`inGameHasFocus` stays false); server-side peripheral sessions with no new packet; the Keyboard block and Laser Pointer; the Doctor key tester; the AkashicFM palette; no ISBRH.
- **Baseline and access model from H:** wave-2 parity; access classes with PREEMPT and access-coloured frames; the controller badge on AIMED; the Angelica cutout-plus-lift resolution; the echo page and `/awdoctor keys`; auto session on a text field; an explicit cut order.
- **Grafts from M:** URL-history and context-menu privacy; `ModifierTracker`; idle warning; "Conversar"; GTNHLib toasts; settings draft with Save and a "changed elsewhere" banner; Info tab; BrowserBudget boost; exit on damage; bot scenarios B7 and B23.
- **Removed by the reviews:** the free-cursor CURSOR style and its toggle key (post-event; the preview drawer is the one precision path, RB-16); M's `UseSwallow` (its premise is false, RA-12).
- **Rejected earlier, still rejected:** M's exit on focus loss (C-15); M's 3840×2160 caps; M's middle-click entry; H's `inGameHasFocus = true`; H's `C2SControlRequest` and its Shift+click; H's ISBRH keyboard; F's vanilla-reach crosshair; F's one-second input buffer; F's two-block keyboard; F's kill switch in the GUI.

### 0.3 Conflict resolution log

Binding resolutions. "(rev)" marks a row changed by the reviews; C-38..C-49 are new.

| # | Conflict | Resolution | Why |
|---|---|---|---|
| C-01 (rev) | Pointer style while typing | **LOOK** only (crosshair is the pointer, camera live). The free-cursor CURSOR style is post-event. Precision at range comes from the address bar's live **preview drawer** (Phase C) | D-017 asks for WD dynamics (VERIFIED `WD:client/gui/GuiKeyboard.java:95-98`). One precision path instead of two (RB-16): CURSOR needed matrix reflection, native-cursor hiding against MUI2's cursor reset, a hover widget and a toggle key |
| C-02 (rev) | Camera look inside a GUI | Our `LookDriver`; `inGameHasFocus` stays false; **`GrabArbiter` owns every grab change** (C-47) | Vanilla look is gated by `inGameHasFocus && Display.isActive()` (VERIFIED `MC:client/renderer/EntityRenderer.java:1023,1038-1044`). With the flag false, vanilla never ungrabs for us (C-47) |
| C-03 | Crosshair reach in normal play | `S2CHello.interactRange` (default 24); the laser reaches further | Wave-2 parity |
| C-04 | Item in hand | The item acts. Only an empty hand or the Laser Pointer points | `AW:common/block/BlockScreen.java:77` |
| C-05 (rev) | First input on a free screen | Optimistic apply (kept). On refusal: release-all, the wave-2 resync, and **close any open session** with the toast "Não foi possível assumir o controle" | RA-7: otherwise a session types into nothing |
| C-06 | The telão at distance | Peripheral sessions (Keyboard block) and laser reach that grows with the screen; raising `interactRange` is a config fallback only | The server reads the held item and the booth position |
| C-07 (rev) | Keepalive when a typist looks away | **Control grants** (§3.3). A keepalive passes while the grant that won the lock still holds geometrically; LASER also needs the laser held (5 s grace). The first take keeps the full view check | For DIRECT this is exactly the distance `ControlLock` already tolerates (VERIFIED `AW:server/ControlLock.java:39-48`). LASER and PERIPHERAL extend it only while the player holds the laser or stays at the booth (RA-15 corrected the v1 claim that grants "widen nothing") |
| C-08 | "Pedir a vez" | Not in wave 3 | No new C2S packet |
| C-09 (rev) | Middle button on the picture | Page middle on DRIVE or TAKE_PENDING; vanilla pick block otherwise | C-41 |
| C-10 (rev) | Default key for typing | Code default `KEY_NONE`. On the first world join, `KeyAutoAssign` binds the first key among **K, U, J, H, Y** that no registered binding uses, once, with a toast. **N is excluded** (Akashic Voice mute, VERIFIED `AV:keybinds/AkashicVoiceKeybinds.java:14`). Minimal `/awdoctor keys` is Phase A | Hodgepodge fires every binding on a shared key (VERIFIED `HP:.../config/FixesConfig.java:307-309`, RA); Phase A must have one-gesture typing (RB-6) |
| C-11 (rev) | Session opens on a page text field | **On by default, Phase A.** Trigger: the host's `textInputSeq` increases within 500 ms after our own page `mouseUp`; the host bumps it only on a trusted pointer-up that lands on or focuses an editable element, so an already focused field also works and script focus never does | RB-5, RB-6 |
| C-12 | Attack on the 2/16 bezel | Swallow with an empty hand or the laser, toast "%s para quebrar a tela" (live sneak key); vanilla with another item | Stops accidental mining |
| C-13 (rev) | Sneak + use | **Empty hand or the laser: address bar. Any other item, `ItemBlock` included: vanilla.** The Keyboard item's own sneak-use links it (§5.1) | RB-12: eating, throwing or drawing a bow while sneaking must work near a screen |
| C-14 | Lock taken by someone else during a session | Close with "%s assumiu o controle" | No dead modal state |
| C-15 (rev) | Window loses focus | release-all and `PAUSED(UNFOCUSED)`; on refocus, key and button downs within 200 ms are ignored | `displayInGameMenu` acts only with no GUI (VERIFIED `MC:client/Minecraft.java:1409-1413`). LWJGL synthesizes key-ups on focus loss (VERIFIED `LW:java/org/lwjgl/opengl/WindowsDisplay.java:367-391`, `LinuxKeyboard.java:310-318`); Windows turns the first repeat after refocus into a fresh press (VERIFIED RA: `WindowsKeyboard.java:126-135,148`) |
| C-16 | Session bar position | Top | Toasts, hotbar and chat stay readable |
| C-17 | Keyboard block shape | One 1/16 slab, 4 facings, top icon by metadata, no ISBRH | Carpet pattern (VERIFIED `MC:block/BlockCarpet.java:24,93-124`) |
| C-18 | Art and sounds | WD textures with credit (public domain, VERIFIED `/home/user/montoyo/webdisplays/LICENSE:1`); no WD sounds; generator fallback | — |
| C-19 | Saving settings | Draft + "Salvar"; resolution and rotation through "Aplicar" with a confirmation | — |
| C-20 | Kill switch in the GUI | No; `/webadmin kill` | — |
| C-21 | Polygon offset | Page `(−1,−2)`, decorations `(−1,−4)`, cursor `(−1,−8)` | R5 SF-1 |
| C-22 (rev) | Overlay material on Angelica's TESR API | `cutout(0.5f)` + distance-scaled lift, Phase D, opt-in | The builder takes a threshold (VERIFIED `ANG:.../api/tesr/TesrMaterial.java:113`) |
| C-23 | Resolution caps | Server caps from `S2CHello` | `AW:common/WebSettings.java:148-150` |
| C-24 | Ctrl+C / Ctrl+X | INPUT `edit` → `webContents.copy()/cut()/...` | Writes only the controller's clipboard (D-016) |
| C-25 | Transport of `edit` | INPUT subtype | Same D-016 gate |
| C-26 (rev) | Page context menu | Dialog at the pointer, Phase D, opened by the **Menu key or Shift+F10** (and the right button when `rightButtonInSession = RIGHT`). In LOOK the ungrab happens through `GrabArbiter`, then the cursor is put at the window centre explicitly | `ungrabMouseCursor` centres the cursor, but `Mouse.setGrabbed(false)` may move it back to the grab point (VERIFIED `MC:util/MouseHelper.java:31-35`; `LW:java/org/lwjgl/input/Mouse.java:657-661`) |
| C-27 | Where `ScreenInput` lives | `common.net.ScreenInput`, new kinds | Contract file |
| C-28 | Others hear typing | No | No D-016 change |
| C-29 (rev) | Hover as keepalive | In normal play hover never takes and never keeps the lock. **Inside a session**, pointer motion over the picture is activity and may send a keepalive (≤ 1 per 5 s), never a first take | RB-15: in LOOK the only way to point is to move the mouse; the idle warning must be true |
| C-30 | Leave the session when hurt | Yes, pref, default on | WASD types in a session |
| C-31 | PeekScreen | No; the preview drawer covers it | — |
| C-32 (rev) | LOOK ↔ CURSOR toggle key | **Removed** with CURSOR (post-event) | C-01 |
| C-33 | Page click sound | On, low (0.12); lead default | D-014 delegates it |
| C-34 | Props in wave 3 | Keyboard block and Laser Pointer (Phase C); Remote, Configurator, Tablet post-event | — |
| C-35 (rev) | Linking a Keyboard block | **Sneak + use with the Keyboard item aimed at a screen** (near or far, up to `peripheralRange`) stores the link in the item; placement uses it, else the front-facing formed face within `peripheralRange` closest to the placer's look; the dropped item keeps the link | RB-3: placement aims at the desk, so a far telão could never be linked by placement |
| C-36 | Keyboard LEDs | Cut | — |
| C-37 (rev) | Jar guards | `ArchitectureGuardTest`; `common/block/**` and `common/item/**` may reference `IIconRegister` only inside `@SideOnly(CLIENT)` members | RA-9: block icons need `registerBlockIcons(IIconRegister)` |
| **C-38** | Right button | **Page left everywhere** by default (`input.rightButtonInSession = LEFT`); RIGHT is a pref; the page context menu comes from the Menu key or Shift+F10 | RB-4: one meaning per button; Electron offscreen rendering draws no native menu (R2 §4.1, INFERRED) |
| **C-39** | Telão fullscreen | The controller's HTML-fullscreen bit travels in `C2SMediaSample` (minor-gated); the server keeps it with the media clock as `S2CMediaClock.fit`; each follower's host fullscreens the page's main video. Sticky until the next URL epoch or a controller sample that says otherwise | RB-2. It is presentation state owned by the server like the clock, not input, so D-016 holds |
| **C-40** | Entities in front of a picture | Entities occlude like blocks: no page hit, the event stays vanilla's | RA-6 / RB-1 (P7) |
| **C-41** | What takes a free screen | **Only a click** (use or attack) or opening a session. Wheel and middle act on the page only on DRIVE or TAKE_PENDING; on TAKE they stay vanilla | RB-11: a stray wheel must not lock a telão |
| **C-42** | Mouse buttons 4/5 and bound buttons | Normal play: vanilla by default (`input.sideButtons = SESSION`). In a session: back/forward. Never consume a mouse button that a registered `KeyBinding` uses, except use, attack and pick-block on a drivable picture | RB-8: a back on a telão moves every viewer; voice PTT on a side button (VERIFIED `AV:keybinds/PttInputBridge.java:21-26`) |
| **C-43** | Minecraft keys inside a session | Never call `func_152348_aa`. Replicate only screenshot and fullscreen, by their bound codes, when those are non-text keys. Stream keys, F5 and F6 are dropped | RA-2 / RB-13: F6 is `key.streamStartStop` and opens `GuiStreamUnavailable` over the session (VERIFIED `MC:client/settings/GameSettings.java:223-224`, `MC:client/Minecraft.java:2892-2934`); a bare F5 would reload the telão for everyone |
| **C-44** | Akashic Voice push-to-talk | A PTT bound to a text key (default V) still types into the page; a non-text or mouse PTT is never consumed. The Doctor flags a text-key PTT; Akashic Voice should gate keyboard PTT while a text GUI is open (cross-repo proposal) | RB-9. Withholding V would make "v" untypeable; Akashic Voice already opens the mic when V is typed in vanilla chat (VERIFIED `AV:ClientVoiceBootstrap.java:228-229` polls with no screen guard; `AV:keybinds/PttInputBridge.java:30-41`) |
| **C-45** | Test echo origin | Unpackaged test mode only: the host rewrites the one alias origin `https://akw-echo.test/` to `file://<host/test/pages>/` before its navigation check and maps it back in STATE. No protocol handler | RA-10: a per-partition `https` handler would put every test request on a JS path, and partitions are pooled (VERIFIED `HOST:sessions.js:1-14,49-70`); `file:` is already allowed in that mode (VERIFIED `HOST:policy.js:179,209`, `HOST:main.js:93`) |
| **C-46** | Evolving a C2S packet | One optional trailing field per packet, sent only when the server's `S2CHello.protoMinor ≥ 1`; the server decodes both shapes exactly | Needed by C-39 without a new packet; keeps "decode exactly" (VERIFIED `AW:common/net/AkwMessage.java:25-27`) |
| **C-47** | Who grabs the mouse | `GrabArbiter` alone changes the grab, and only at `RenderTickEvent` START (HIGHEST), never inside an input handler. A session that hands over to any GUI ungrabs within one frame | RA-1: `setIngameNotInFocus` is a no-op while `inGameHasFocus` is false (VERIFIED `MC:client/Minecraft.java:1396-1404,859-861`). RA-5: `Mouse.setGrabbed` always discards queued mouse events (VERIFIED `LW:java/org/lwjgl/input/Mouse.java:250-253,647-672`) |
| **C-48** | Keys already held when a session opens | Ignored until their first release | RA-4: MUI2 turns on key repeat at every MUI screen open (VERIFIED `MUI:screen/ClientScreenHandler.java:235`) |
| **C-49** | `UseSwallow` after a session | Dropped | RA-12: Hodgepodge's re-sync skips mouse codes (VERIFIED `HP:mixins/early/minecraft/MixinKeyBinding.java:86-93`) and the session open already unpressed `use` |

### 0.4 Review resolution log

Every finding of RA and RB, in review order. "Accepted" = the reviewer's fix as given; "Adapted" = the problem is fixed another way; "Rejected" = with the reason.

| ID | Sev. | Finding | Decision | Where |
|---|---|---|---|---|
| RA-1 | High | A LOOK session that hands over to any GUI leaves the mouse grabbed | Accepted, with RA-5's constraint: `GrabArbiter` ungrabs at the next render tick start when the current screen is a non-session GUI; I10; S21 | §3.8.2, §3.4.4, §8.3 |
| RA-2 | Med | F6 (stream key) in a session opens `GuiStreamUnavailable`; F7 also goes through `func_152348_aa` | Accepted: never call `func_152348_aa`; replicate screenshot and fullscreen by bound non-text code; drop stream keys, F5, F6; S8 | §3.7.1-3.7.2 |
| RA-3 | Med | Mouse events drain before key events, so same-tick modifiers are missed | Accepted: mouse and wheel modifiers = tracker OR polled `Keyboard.isKeyDown` | §3.5.5 |
| RA-4 | Med | Keys held at session open leak auto-repeat; I4 as written would drop key-0 text | Accepted: pre-held set; I4 scoped to key-up emission; I11 | §3.7.3, §3.4.4 |
| RA-5 | Med | `Mouse.setGrabbed` discards queued events; grabbing inside input handlers loses clicks | Accepted: grab changes only at render tick start, then reconcile held buttons with `Mouse.isButtonDown`. The LOOKAROUND case is gone with CURSOR | §3.8.2 |
| RA-6 | Med | Entities in front of the picture are ignored | Accepted (also RB-1) | §3.6 |
| RA-7 | Med | A refused take leaves the session open | Accepted: new exit reason, toast, S30 | §3.3.6, §3.4.2 |
| RA-8 | Med | Coalescing acceptance contradicts itself | Accepted: coalesce only a MOVE at the tail of a screen's queue | §3.10, §7.2 |
| RA-9 | Med | The guard forbids `IIconRegister`, which block icons need | Accepted: `@SideOnly(CLIENT)` members only, plus a guard self-test | §2 item 7 |
| RA-10 | Med | The echo origin's `https` handler recurses, leaks out of the partition or skews results | Adapted: host-side alias rewrite to `file:` in unpackaged test mode; no handler; real-site scenarios run without the flag | §6.2, §8.3 |
| RA-11 | Med | ABNT2 bot scenarios would not exercise dead keys | Accepted: UTF-8 locale for the JVM, `xdotool key` on physical keysyms, assert raw event shapes | §8.3 |
| RA-12 | Med | No Hodgepodge in the dev runtime; `UseSwallow` premise false; S16 behaves differently in the pack | Accepted: Hodgepodge in both dev runtimes; `UseSwallow` dropped (C-49); S16 restated | §2, §3.4.3, §8.3 |
| RA-13 | Low | "Key-ups will not come" is false | Accepted: wording fixed; refocus window covers key downs | §3.4.2 |
| RA-14 | Low | `drawDarkBackground(true)` does not dim a client-only MUI2 screen | Accepted: explicit translucent rectangle | §4.5 |
| RA-15 | Low | LASER keepalive and URL reach never re-check the held laser | Accepted: re-check with a 5 s grace; C-07 and R-04 corrected | §3.3.3, §3.3.5 |
| RA-16 | Low | Handler order at `RenderTickEvent` START unspecified | Accepted: GrabArbiter/LookDriver HIGHEST, task drain HIGH, aim sampler LOW; ordering test | §3.6 |
| RA-17 | Low | TAKE frame alpha 0.45 conflicts with the alpha-test recipe | Accepted: opaque darker accent `0xFF3A6A99` | §3.9.1 |
| RA-18 | Low | Back faces cannot be told apart from `IBlockAccess` | Accepted: connect all faces as WD does | §5.3 |
| RA-19 | Low | `isForcedVisible` thread, GTNHLib category classes, `cutout(float)` | Accepted | §2 items 2, 5, 6; §4.10 |
| RA-20 | Low | `WebGuiFactory` missing until WP-4; §5 lang keys not in WP-0; no owner for WP-4 wiring; repeated Keyboard activation | Accepted: WP-0 stubs and `UiBootstrap` seam; all lang keys in WP-0; `UiOpen` de-duplicates | §7.0, §7.1, §3.4.4 I5 |
| RA-21 | Low | Baseline moved | Accepted: re-cited at `b1019e0` | Header, §0.1 |
| RA-22 | Low | Missing tests | Accepted: I9; S21, S22 variants, S23 entity, S28, S29, S30; Tab-completion filter test | §3.4.4, §8 |
| RB-1 | High | Aim ignores entities | Accepted (= RA-6) | §3.6 |
| RB-2 | High | The telão crowd never sees the controller's fullscreen | Accepted, option (a): fullscreen bit with the media clock | §3.3.9, §6 |
| RB-3 | High | The Keyboard block cannot auto-link to a far telão | Accepted: link by sneak-use with the item (server ray trace; no new packet) | §5.1 |
| RB-4 | High | The right button changes meaning in a session | Accepted: page left by default | §3.5.2, C-38 |
| RB-5 | High | Auto session misses already focused fields | Accepted: `textInputSeq` on trusted pointer-up | §3.8.3, §6.2 |
| RB-6 | High | Phase A has no one-gesture typing | Accepted: auto session and minimal `/awdoctor keys` in Phase A; one-time free-key assignment | §7, C-10 |
| RB-7 | High | `SessionKeys` drops AltGr text | Accepted: every Ctrl reservation is `(ctrl && !alt) \|\| meta`; AltGr and US-International rows | §3.7.2, §8.1 |
| RB-8 | High | Side buttons navigate every viewer; bound buttons stop working | Accepted | §3.5.1, C-42 |
| RB-9 | High | N clashes with Akashic Voice; V typing opens the mic | Accepted for N. Adapted for V: text-key PTT keeps typing (withholding it would make "v" untypeable); non-text PTT passes through; Doctor warning; cross-repo proposal | C-10, C-44, §3.7.6 |
| RB-10 | Med | Spectators no longer see typing, menus or scrolling, and the owner was not told | Accepted: §1 line and owner question P-15 (scroll anchor, cursor) | §1, §9.2 |
| RB-11 | Med | A stray wheel takes a free screen | Accepted (C-41) | §3.4.2 |
| RB-12 | Med | C-13 contradicts C-04 | Accepted | C-13 |
| RB-13 | Med | F5 in a session reloads the telão for all | Accepted (C-43) | §3.7.2 |
| RB-14 | Med | The typing notice is false in effect | Accepted: reworded (URLs and searches are seen by everyone) | §3.8.4 |
| RB-15 | Med | The idle and keepalive messages contradict the rules | Adapted: in-session pointer motion counts as activity (C-29); "Olhe para a tela" only for a predicted refusal of a first take | §3.3.6 |
| RB-16 | Med | Two precision paths | Accepted: CURSOR post-event | C-01 |
| RB-17 | Low | pt-BR strings clash with vanilla terms | Accepted: live vanilla names (`soundCategory.record` = "Blocos Musicais", `key.sneak` = "Esgueirar", VERIFIED `MCA:` lines 856, 1206); "Monitor" button gone; access-aware first tip | §4 |
| RB-18 | Low | Badge visibility inconsistent | Accepted: AIMED for everyone | §3.3.7, §3.9.1 |
| RB-19 | Low | G3 checklist mixes phases | Accepted: G3-A plus later addenda | §8.4 |
| RB-20 | Low | Page cursor on TAKE uses a stale host cursor | Accepted: plain arrow on TAKE | §3.9.1 |
| RB-21 | Low | ABNT2 coverage misses common compositions; Caps chip has no source | Accepted: rows added; Caps chip dropped | §3.7.4, §4.2 |
| RB-22 | Low | Baseline stale | Accepted (= RA-21) | Header |
| RB-23 | Low | Too many owner questions; internal prefs exposed | Accepted: five questions; the rest are lead defaults (D-014); internal prefs hidden | §9.2, §4.10 |

No finding is rejected outright. Three are adapted rather than taken literally: RA-10 (a different, smaller mechanism for the test origin), RB-9 (V keeps typing) and RB-15 (in-session motion counts as activity instead of rewording the idle text).

---

## 1. Resumo para o dono (pt-BR)

**O que muda na onda 3, em poucas linhas:**

- **Igual ao Web Displays, só que melhor.** Com a mão vazia, mire na tela e clique (botão direito ou esquerdo) para clicar na página. Segure e mexa a mira para arrastar. Depois do primeiro clique, a rodinha rola a página. Agache + botão direito (mão vazia) abre a barra de endereço; agache + botão esquerdo quebra a tela. O cursor da página aparece desenhado na própria tela, e a borda acende quando você pode usar. Com um item na mão, o item funciona normalmente.
- **Digitar com o mundo visível.** Ao clicar numa caixa de texto da página, a digitação começa sozinha (já na primeira entrega). Também dá para começar pela tecla "Digitar na tela" (o mod escolhe uma tecla livre na primeira vez, entre K, U, J, H e Y), pelo botão "Digitar" da barra de endereço ou por um **Teclado** (bloco novo). Na digitação:
  - todas as teclas vão para a página (WASD digita);
  - o mouse continua girando a câmera e a mira é o ponteiro;
  - Esc sai;
  - Ctrl+L abre o endereço;
  - Ctrl+T abre o chat e volta depois.
- **Telas novas em ModularUI2**, no visual do AkashicFM:
  - barra de endereço com histórico e, depois, uma prévia ao vivo onde dá para clicar com precisão;
  - configuração da tela com abas Acesso, Tela, Som, Admin e Info;
  - instalação do navegador;
  - diagnóstico, com teste de teclado ABNT2.
- **Telão.** Você controla de uma cabine com um Teclado ligado ao telão (até 32 blocos, configurável até 64) ou com o **Ponteiro laser** (até 48 blocos num telão de 32×18). Quando quem controla põe o vídeo em tela cheia, **o telão de todo mundo também mostra só o vídeo**. Visitantes só assistem e veem um aviso curto. O nome de quem controla aparece quando alguém mira na tela.
- **O que quem assiste vê.** Todos veem o mesmo endereço e o vídeo no mesmo ponto, e agora a tela cheia também. Quem assiste **não** vê a digitação, os menus abertos nem a rolagem dentro da página; isso é o preço da regra D-016 (nada do que você digita passa pelo servidor). A pergunta P-15 oferece um "ponto de rolagem" compartilhado depois do evento.
- **Segurança mantida (D-016).** Nada do que você digita ou aponta passa pelo servidor. O servidor só guarda quem controla, o endereço, o relógio do vídeo e se o vídeo está em tela cheia.
- **Corrige o que vimos no teste de hoje:**
  - quem muda o endereço de uma tela livre passa a controlá-la, então os outros sincronizam;
  - o aviso de recusa fica claro;
  - vídeo aberto direto preenche a tela.
- **Convivência com o Akashic Voice.** A tecla N (mudo do Akashic Voice) nunca é usada pelo AkashicWeb. Se a tecla de falar for V, ela continua digitando "v" na página (como já acontece no chat); sugerimos trocar a tecla de falar por um botão do mouse ou mudar o Akashic Voice para ignorar a tecla quando uma tela de texto estiver aberta (P-14).
- **Fica para depois do evento:** cursor livre, Controle remoto, Configurador, Tablet (minePad), ver o cursor de quem controla, rolagem compartilhada e ouvir a digitação dos outros.
- **Nada foi rodado ainda.** Tudo precisa do seu teste no Windows com o pack Forbidden. O roteiro G3-A cobre só a primeira entrega (seção 8.4). As perguntas para você estão na seção 9.2; a mais urgente é a P-18 (o ForbiddenMobile tem ModularUI2 e GTNHLib?).

---

## 2. ADR-017: client UI stack

**ModularUI2 screens, GTNHLib client preferences, no mixins of our own, and Angelica's TESR API as an opt-in path.** Status: `proposed`. It becomes `accepted` when the lead commits it with ADR-018; confirm by G3-A.

### Context

- **Owner directive D-017** (VERIFIED `DOC:DECISIONS.md:24`): modern technology, GUIs in ModularUI2, Web Displays' keyboard-and-mouse dynamics in the world.
- **ADR-009 today** (VERIFIED `DOC:adr/README.md:726-761`): Forge-only hard dependencies; vanilla `GuiScreen` GUIs; ModularUI2 rejected because "it hides the low-level keyboard events we need".
- **Why that rejection no longer holds.** ModularUI2 2.3.85 redirects `GuiScreen.handleInput` to post cancelable `KeyboardInputEvent.Pre` and `MouseInputEvent.Pre` on the Forge bus for every LWJGL event, before any handling (VERIFIED `MUI:api/event/KeyboardInputEvent.java:17,30`, `MUI:api/event/MouseInputEvent.java:17,30`; VERIFIED RA: `GuiScreenMixin.java:28-50`). A cancelled event skips `handleKeyboardInput` and with it `func_152348_aa` (VERIFIED `MC:client/gui/GuiScreen.java:366-373`).
- **Pack facts** (D-013, `DOC:research/07-forbidden-pack-inventory.md`): ModularUI2 2.3.85, GTNHLib 0.11.51, UniMixins 0.3.1 and Hodgepodge 2.7.189 on client and server; Angelica 2.2.20 and NEI 2.8.125-GTNH on the client.
- **House style** (VERIFIED `AFM:client/gui/FlatButton.java:10-16`): GTNHLib `@Config` with `SimpleGuiFactory` and a flat dark palette.
- **AGENTS.md** already lists GTNHLib and UniMixins and "mixins only where Forge has no hook" (VERIFIED `/home/user/akashicweb/AGENTS.md:9-10`).

### Decision

1. **ModularUI2 2.3.85, hard dependency, used client-only.**
   - Coordinate `com.github.GTNewHorizons:ModularUI2:2.3.85-1.7.10:dev`, `implementation`, transitive so `runClient` gets UniMixins, NEI and Baubles-Expanded. Dev jar SHA-256 `b73067d456614b1f3163d614f2f4f144ab698f2a2311c2b1389bed00ec3a6112`; the pack's jar matches the Maven reobf jar in size and CRC32; modid `modularui2` (VERIFIED R1 §1.1, §1.3; `MUI:ModularUI.java:18`).
   - **How screens are built.** Every screen is a client-only `ModularScreen` or `CustomModularScreen`, opened with `ClientGUI.open`, always deferred one tick through `UiOpen.later` on `ClientTaskQueue`, because `ClientGUI.open` opens at once (VERIFIED `MUI:factory/GuiManager.java:136-151`). A new screen and new panels on every open. `openParentOnClose` stays false (the default, VERIFIED `MUI:screen/ModularScreen.java:88`): returning to a session always opens a fresh session, never MUI2's parent stack.
   - **Never used:** synced panels, `IGuiHolder`, `GuiFactories` (D-016); `IMuiScreen`, `getScreenWrapper()`, NEA or lwjgl3ify types. Use `ModularScreen.getCurrent()` (VERIFIED R1 §2.14).
   - **Package rule.** Only `com.akashiic.web.client.ui` and its sub-packages, except `client.ui.model`, reference `com/cleanroommc/modularui/`.
2. **GTNHLib 0.11.51, hard dependency.**
   - Coordinate `com.github.GTNewHorizons:GTNHLib:0.11.51:dev`, `implementation` (VERIFIED R4 §1.5: HTTP 200, 2026-10-13). ModularUI2 already requires it (`gtnhlib@[0.2.0,)`, R1 §1.3).
   - **Client preferences** (`client.prefs`): **one `@Config` class per category**, all with `modid = "akashicweb"`, `filename = "akashicweb-client"`, registered in `ClientProxy.preInit` only. Categories share one file; culling only removes categories no registered class observed (VERIFIED `GL:config/ConfigurationManager.java:48,299,580-611`). Hidden state lives in its own class annotated `@Config.ExcludeFromAutoGui`, which applies to types only (VERIFIED `GL:config/Config.java:236-242`) and only to the auto list (VERIFIED `GL:config/ConfigurationManager.java:66-74`). `WebGuiFactory implements SimpleGuiFactory` (VERIFIED `GL:config/SimpleGuiFactory.java:12`) returns a `SimpleGuiConfig` built with the explicit list of visible classes (VERIFIED constructor `GL:config/SimpleGuiConfig.java:17-20`).
   - **Toasts** with `AboveHotbarHUD.renderTextAboveHotbar(String, int, boolean, boolean)` (VERIFIED `GL:util/AboveHotbarHUD.java:34`).
   - **`DefaultVertexFormat`** for the Angelica API (item 5).
   - **Not used** (R4 §1.3): `SyncedKeybind` (sends key state to the server, against D-016); `IPacket`/`NetworkChannel`; `ServerThreadUtil`; Brigadier; `@EventBusSubscriber`.
   - **Server policy stays in `WebConfig`**: GTNHLib resets a bad file to defaults (R4 §1.2), which is fail-open for the kill switch and the site lists.
3. **`@Mod` declaration** on `AW:AkashicWeb.java:21-25`:

   ```java
   dependencies = "required-after:gtnhlib@[0.11.51,);required-after:modularui2@[2.3.85,);after:angelica",
   guiFactory = "com.akashiic.web.client.prefs.WebGuiFactory"
   ```

   - The ranges evaluate as intended with FML 1.7.10's `VersionParser` (R4 §1.5, R1 §1.3). `mcmod.info` keeps no `useDependencyInformation`. Copy AkashicFM's `ModDependencyTest`.
   - FML 1.7.10 has no client-only dependency syntax, so the server needs both jars (INFERRED; the Forbidden server has them).
   - A `guiFactory` is instantiated on the client only. **WP-0 commits a working stub `WebGuiFactory`**, so FML never logs a factory error between WP-0 and WP-4 (RA-20; FML instantiates it at load, VERIFIED RA: `FMLClientHandler.java:332-347`).
4. **Mixins: none of our own.**
   - Keep `usesMixins = false`, no coremod, no access transformer. Set `forceEnableMixins = true`, which puts UniMixins on the dev runtime only (R4 §2.1).
   - **Hooks used instead:** Forge `MouseEvent` (cancelable before key bindings, VERIFIED `MC:client/Minecraft.java:1776-1784`); ModularUI2 `KeyboardInputEvent.Pre` / `MouseInputEvent.Pre` at `EventPriority.HIGHEST`; `TickEvent.RenderTickEvent` START (handler order fixed in §3.6); `RenderGameOverlayEvent.Pre(CROSSHAIRS)`, `RenderHandEvent`, `DrawBlockHighlightEvent`, `GuiOpenEvent`.
   - **Rejected mixin candidates:** keyboard capture in `runTick`, a Netty clock stamp, an Angelica pipeline hook (R4 §2.3).
5. **Angelica 2.2.20 TESR material API** (`angelica|tesr`, apiVersion 2), **opt-in, Phase D.**
   - `compileOnly("com.github.GTNewHorizons:Angelica:2.2.20:api") { transitive = false }` (R4 §1.5: 43 classes, major 52).
   - All calls in `client.render.AngelicaTesrPath`, loaded only when `ModAPIManager.INSTANCE.hasAPI("angelica|tesr")` and a reflective probe of `TesrMaterial.SpecialRender.BEACON_BEAM` succeed.
   - **Page material:** `lightmap(240,240).unlit().noCull()`, plus `.special(BEACON_BEAM)` for the emissive variant (R4 §3.3).
   - **Overlays:** `cutout(0.5f)` (VERIFIED signature `ANG:.../api/tesr/TesrMaterial.java:113`) plus a lift of `max(0.002, 3.6e-6 · d²)` blocks along the normal (R5 §5.3; INFERRED margin).
   - **Pref** `render.screenPath = TESSELLATOR | ANGELICA_LIT | ANGELICA_EMISSIVE`, in the hidden Advanced category; default TESSELLATOR until G3 compares the paths under BSL v8.2.09 and Complementary r5.9.3.
   - Keep the reflective `TileEntityRenderBoundsRegistry.registerDynamicClass` (R4 §3.8) and the shadow-pass skip.
6. **Other stack rules:**
   - `TileEntityScreen.isForcedVisible()` is `{ return true; }` with no state: EntityCulling 1.6.4 calls it from its own cull thread and tests only the origin block (VERIFIED `EC:.../CullTask.java:55-75`; R4 §4.4).
   - `GainModel` squares Master and Records when Hodgepodge's `logarithmicVolumeControl` is on (VERIFIED `HP:config/FixesConfig.java:118`; Hodgepodge squares only the SoundSystem arguments, VERIFIED RA: `MixinSoundManager.java`).
   - lwjgl3ify `InputEvents.beginTextInput()` / `endTextInput()` by reflection on session open and close (R4 §4.6).
   - Clipboard only through `GuiScreen.getClipboardString()` / `setClipboardString()`.
7. **Jar guards.** `ArchitectureGuardTest` (JUnit, part of `check`) scans the compiled classes (`CONSTANT_Class` and member references, not string constants). No committed guard exists today (VERIFIED: grep of `*.gradle*` and `src/test`). It checks:
   - class major 52 everywhere;
   - `com/cleanroommc/modularui/` only under `client/ui/` (not `client/ui/model/`);
   - no `com/gtnewhorizons/modularui/` (MUI1), `com/cleanroommc/neverenoughanimations/`, `me/eigenraven/lwjgl3ify/` or `IMuiScreen`;
   - `com/gtnewhorizons/angelica/` only in `client/render/AngelicaTesrPath*`;
   - `common/**` and `server/**` never reference `com/cleanroommc/`, `com/gtnewhorizon/gtnhlib/` or `org/lwjgl/`;
   - `common/**` and `server/**` never reference `net/minecraft/client/`, **with one exception**: `net/minecraft/client/renderer/texture/IIconRegister`, from `common/block/**` and `common/item/**` only, and only inside a method whose `RuntimeVisibleAnnotations` carry `cpw/mods/fml/relauncher/SideOnly` with `Side.CLIENT` (RA-9). `ArchitectureGuardSelfTest` feeds the scanner a compiled sample block class with an annotated and an unannotated `registerBlockIcons`, and expects pass and fail.

   The manifest rule (no `TweakClass`, no `FMLCorePlugin`) stays a release check (R4 §2.1).

### Alternatives considered

- **Vanilla `GuiScreen` (ADR-009):** does not meet D-017.
- **MUI1:** not in the pack.
- **Synced ModularUI2 panels:** a container on Crucible and a second channel, against D-016 (R1 §2.13).
- **HoloUI:** experimental, render-only (R1 §2.11).
- **GTNHLib `@Config` for server policy:** fail-open on a bad file.
- **Our own mixins:** every need has a hook.
- **Angelica TESR path on by default:** shader behaviour unobserved (R4 §3.9).

### Consequences

- ADR-009 is amended in the same commit (dependencies, GUIs, the false note at `DOC:adr/README.md:761`). CONTRACTS changes (§6.4).
- A client or server without ModularUI2 or GTNHLib stops at FML's missing-mods screen. Acceptable for the Forbidden pack; ForbiddenMobile must be confirmed (R-06, P-18).
- **Dev runtime parity.** The dev client and the dev Crucible both carry UniMixins 0.3.1, GTNHLib 0.11.51, ModularUI2 2.3.85 **and Hodgepodge 2.7.189** (RA-12: Hodgepodge changes focus and key-binding behaviour, VERIFIED `HP:mixins/early/minecraft/MixinMinecraft_UpdateKeys.java`, `MixinKeyBinding.java:86-93`). Dev-only configuration name `runtimeOnlyNonPublishable` and the Hodgepodge Maven coordinate are INFERRED, checked in WP-0 against the GTNH Maven. `config/modularui2.cfg` with `guiDebugMode=false`, `enableTestGuis=false` on both sides (the 2.3.85 dev defaults differ, R1 §4.7).
- **Global GL effect.** ModularUI2 already enables and clears the stencil every frame in the pack (R1 §4.1). Nothing new.
- **Vanilla GUI classes are retired only after their replacements pass.** `AddressBarGui`, `KeyboardGui` and `ConsentGui` stay reachable through `VanillaUiPort` until S6, S8, S11, S13 and S14 pass.

### Confirm by

| Grade | Check |
|---|---|
| BUILD | `javac --release 8` against `ModularUI2:2.3.85-1.7.10:dev`; `ArchitectureGuardTest` + self-test; `ModDependencyTest` |
| AUTOMATED (Linux) | Dev client and dev Crucible with the four jars; §8.3 scenarios |
| LIVE-OBSERVED (G3-A, Windows, Forbidden pack, Angelica + BSL/Complementary) | GUI scale 1-4; no dimming except Consent; stencil clipping of lists; raw ABNT2 keys in a session |

### Fallback

- If ModularUI2 misbehaves at G3, `SessionScreen` drops back to a vanilla `GuiScreen` view of the same `SessionController` (all logic is in `client.input`).
- The vanilla address bar, keyboard and consent GUIs come back through `VanillaUiPort`.
- If GTNHLib `@Config` fails, `ClientPrefs` falls back to a Forge `Configuration` file with the same keys.

---

## 3. ADR-018: in-world interaction model

**Web Displays gestures on a lock-aware, balanced pointer; a typing session that keeps the world and the camera; control grants on the server; the controller's fullscreen shared like the media clock.** Status: `proposed`, accepted with ADR-017. It extends ADR-012 and its lead amendment D-016; it does not replace them.

### 3.0 Context

- **D-016** (VERIFIED `DOC:DECISIONS.md:23`): page input stays on the client. The server owns the controller lock, the URL and the media clock. No pointer position or keystroke goes to the server.
- **D-017** asks for Web Displays' visual dynamics; D-002 asks for players who build, click, type and browse.
- **Wave 2** (§0.1): a 24-block crosshair pointer with press, drag and hover, empty hand only; an optimistic first take; a vanilla typing GUI that cannot click.
- **Web Displays** (R2 §2): right-click is a page left click; sneak + right-click opens the URL GUI; the Keyboard block opens a typing GUI that keeps the mouse grabbed (`WD:client/gui/GuiKeyboard.java:95-98`); the Laser Pointer drags at range. Known defects: no drag, hover or wheel; left-click mines; keys stuck; plain-text relay (R2 §3).
- **Vanilla and LWJGL facts the design rests on** (all VERIFIED):
  - Mouse look runs only when `inGameHasFocus && Display.isActive()` (`MC:client/renderer/EntityRenderer.java:1023,1038-1044`).
  - `displayGuiScreen(gui)` posts `GuiOpenEvent`, closes the old screen and calls `setIngameNotInFocus()` (`MC:client/Minecraft.java:829-867`). `setIngameNotInFocus()` unpresses keys and ungrabs **only if `inGameHasFocus`** (`:1396-1404`). `displayGuiScreen(null)` with health ≤ 0 shows `GuiGameOver` (`:835-838`).
  - `setIngameFocus()` acts only when the window is active and `!inGameHasFocus`; it grabs, closes the GUI and sets `leftClickCounter = 10000` (`:1379-1391`). Hodgepodge then re-syncs keyboard bindings from `Keyboard.isKeyDown`, skipping mouse codes (`HP:mixins/early/minecraft/MixinMinecraft_UpdateKeys.java`, `MixinKeyBinding.java:86-93`).
  - `displayInGameMenu` acts only when no GUI is open (`:1409-1413`).
  - A cancelled Forge `MouseEvent` never reaches the key bindings (`:1776-1784`).
  - A GUI drains all mouse events before any keyboard event in a tick (`MC:client/gui/GuiScreen.java:307-323`); so does `runTick` (`MC:client/Minecraft.java:1776`, `:1837`, VERIFIED RA).
  - `Mouse.setGrabbed` always polls and then discards the queued mouse events (`LW:java/org/lwjgl/input/Mouse.java:250-253,647-672`).
  - `func_152348_aa` handles stream keys (F6 opens `GuiStreamUnavailable` with the stock null stream), fullscreen and screenshot (`MC:client/Minecraft.java:2892-2975`; defaults `MC:client/settings/GameSettings.java:219-224`: screenshot 60 = F2, perspective 63 = F5, stream 64 = F6 and 65 = F7, fullscreen 87 = F11).
  - Survival entity picking reaches 3 blocks (`MC:client/renderer/EntityRenderer.java:357-366`).

### 3.1 Principles (binding)

| # | Principle |
|---|---|
| P1 | **D-016.** Page input goes only to this client's own host, and only while `ClientScreens.drivesInput(s)` holds: controller, optimistic take window, or PRIVATE (VERIFIED `AW:client/screen/ClientScreens.java:659-667`). Nothing new about input goes to the server. The fullscreen bit (§3.3.9) is presentation state sampled with the media clock, not input. |
| P2 | **One focus screen.** Hover, keys and drags reach at most one page at a time. |
| P3 | **Balanced input.** Every page `mouseDown`/`keyDown` gets exactly one `mouseUp`/`keyUp`, on every exit path. The host also clears its pressed set on `mouseLeave` and `releaseAll`. |
| P4 | **Keys are captured only in a visible session** whose top bar names the way out. In normal play the keyboard stays vanilla (1.7.10 updates key bindings before any cancelable hook, R3 finding 6). |
| P5 | **No affordance without a right.** A watch-only player gets no page cursor and no frame; a click gets one throttled toast and is swallowed so it never mines. |
| P6 | **Pure core, thin adapters.** Every decision is a Minecraft-free class with JUnit tables (`client.input`); Forge and ModularUI2 adapters only translate events. |
| P7 | **Nothing by accident.** No mining, item use, entity hit or block placement happens because the player aimed at a page; no page click happens because the player gave the window focus back; nothing that moves every viewer (back, forward, reload, take) happens on a stray wheel or a habitual key. |
| P8 | **WD first.** Both mouse buttons are a page left click, in the world and in a session (C-38). |
| P9 | **One owner of the mouse grab.** Only `GrabArbiter` changes the grab, only at `RenderTickEvent` START, never inside an input handler (C-47). |

### 3.2 Access classes (pure `client.input.LocalAccess.of(state, me, hello, engine, now)`)

UX only; the server decides (ADR-010).

| Access | Condition | Cursor and frame (§3.9) | Click result |
|---|---|---|---|
| `NEEDS_ENGINE` | `ConsentController.opensOnUse(...)` (VERIFIED, used at `AW:client/screen/ScreenInteraction.java:78`) | Vanilla crosshair | Use opens ConsentScreen; attack swallowed |
| `OFFLINE` | Host not READY, kill switch, or another placeholder | Vanilla crosshair; the card explains | Swallowed; toast with the reason |
| `LOCKED` | `state.locked && !hello.admin` | Vanilla crosshair | Swallowed; toast "Tela trancada" |
| `WATCH_ONLY` | No `CLICK_TYPE` (visitors by default: `AW:common/Rights.java:14`) | Vanilla crosshair, no frame | Swallowed; toast "Só para assistir" (once per 10 s) |
| `BUSY(name)` | SHARED, `controller != null && != me`, not owner or admin | NOT_ALLOWED sprite, amber frame | Swallowed; toast "Controlado por %s" |
| `PREEMPT(name)` | As BUSY, but owner or admin (`AW:common/RightsPolicy.java:65-72`) | Arrow with a crown badge, amber frame | Confirm dialog "Assumir o controle de %s?" |
| `TAKE` | SHARED, free, `CLICK_TYPE` | **Plain arrow** (the host cursor is stale until we drive), dark cyan frame | A click takes optimistically and is applied; wheel and middle stay vanilla (C-41) |
| `TAKE_PENDING` | Take sent; optimistic window open (`TAKE_PENDING_MS = 3000`, `ClientScreens.java:111`) | Page cursor, cyan frame | Applied |
| `DRIVE` | `controller == me`, or PRIVATE with `CLICK_TYPE` | Page cursor, solid cyan frame | Applied |

`hello.admin` comes from the limits block (§6.1.1); without it PREEMPT shows only for the owner. `LocalAccess` follows the **server's** rule on locked screens: everyone but admins has no rights, the owner included (VERIFIED `AW:common/RightsPolicy.java:36-48`). Today's `LocalRights.rightsFor` keeps the owner's rights on a locked screen (VERIFIED `AW:client/screen/LocalRights.java:20-26`); wave 3 fixes it.

### 3.3 Multiplayer: the lock, control grants, peripheral sessions and video fit

#### 3.3.1 What travels

The only input-related traffic stays `C2SControl {key, take}` (id 21); take doubles as the keepalive (VERIFIED `DOC:PROTOCOL.md` §2). **No new C2S packet.** The Keyboard block's session starts from the vanilla block-activation packet; its link from the vanilla use-item packet (§5.1). The only C2S change is one optional trailing byte on `C2SMediaSample` (§6.1.4).

#### 3.3.2 Control grants (server, pure `server.ControlGrant`)

A take is accepted when rights and lock pass (`RightsPolicy.canTakeControl`, unchanged) **and** one grant holds. Grants are tried in order; the first that holds is recorded in `ScreenSession.grant` (server memory only).

| Grant | First-take condition | Reach |
|---|---|---|
| `DIRECT` | `ViewCheck.canSee(s, eye, look, interactRange, controlMaxAngleDeg, sight)`: today's rule (VERIFIED `AW:server/ActionRules.java:47-52`, `AW:server/ViewCheck.java:41-55`) | `interactRange` (24) |
| `LASER` | The player's **server-side held item** is `ModContent.laserPointer`, and `ViewCheck.canSee` passes with `range = ControlReach.laser(s, cfg)` | `ControlReach.laser = min(laserMaxReach, max(interactRange, laserReachPerBlock × max(widthBlocks, heightBlocks)))`; defaults 1.5 and 64: a 32×18 telão gets 48, an 8×5 screen keeps 24 |
| `PERIPHERAL` | A valid peripheral session (§3.3.4) for this player and screen, eye within `PERIPHERAL_RADIUS = 6` of its Keyboard block | The Keyboard block's distance to the face, at most `peripheralRange` (32, clamp 4-64) |

`ControlReach` is pure, in `common`, so client and server compute the same number.

#### 3.3.3 Keepalive and release (ADR-012 amendment)

- **Keepalive.** A take from the **current** controller passes when rights pass and the recorded grant still holds, with the release sweep's margin. No look-angle or line-of-sight check:

  | Grant | Keepalive condition |
  |---|---|
  | `DIRECT` | Same dimension, eye-to-screen ≤ `interactRange + 4` |
  | `LASER` | Same dimension, distance ≤ `ControlReach.laser + 4`, **and the laser held now or within the last 5 s** (`ScreenSession.laserSeenMs`, refreshed whenever the server sees it held: take, keepalive, URL action, sweep) |
  | `PERIPHERAL` | Session valid, eye within `6 + 2` of the Keyboard block |

  If the recorded grant fails, any other grant may take over, checked as a first take. The first take always keeps the full view check.
- **What this changes** (INFERRED, argued; corrected after RA-15):
  - For DIRECT it changes nothing about distance: `ControlLock` already keeps a lock within `interactRange + 4` until the idle timer runs out (VERIFIED `AW:server/ControlLock.java:39-48`). It only stops the silent loss of a typist's lock when they glance away (VERIFIED quiet refusal `AW:server/ScreenActions.java:226-228`).
  - LASER and PERIPHERAL do extend the distance a holder keeps, but only while the laser is in hand (5 s grace) or the player stays at the booth. Rights, lock, idle and dimension rules are unchanged.
  - Lever: `WebSettings.controlGrants = false` collapses everything to DIRECT, today's behaviour.
- **Release sweep.** `ControlLock.check` releases when **no** grant's keepalive condition holds (a booth typist who walks up to the screen is still DIRECT). Offline, dimension, rights and idle 60 s are unchanged.

#### 3.3.4 Peripheral sessions (server only; no new packet)

1. **Opening.** `BlockKeyboard.onBlockActivated` runs on both sides. On the server, after Crucible has fired `PlayerInteractEvent` so region plugins can veto (ADR-010), it calls `PeripheralSessions.open(player, keyboardPos, linkedKey, now)` when: the player is an `EntityPlayerMP` and not a FakePlayer; the linked screen is loaded in the same dimension; the player has `CLICK_TYPE` or `CHANGE_URL` on it; the Keyboard block's centre is within `peripheralRange` of the face.
2. **Lifetime.** 300 s after the last renewal; renewed by every accepted take, keepalive, `C2SSetUrl`, `C2SNav` or `C2SRights` that relied on it. Cleared when the Keyboard block is broken or relinked, the screen dissolves, the player logs out or changes dimension.
3. **Ordering.** The vanilla activation packet and our `C2SControl` share the per-player FIFO; our packet is queued to the next tick start (VERIFIED `DOC:PROTOCOL.md` §2 "Threads"; VERIFIED RA). The client also opens the session a tick later and sends take from `onOpen`. So the session exists before the take is processed (S17).
4. **A vetoed or refused activation** leaves no session; the client's optimistic session then closes when its take is not granted (§3.3.6 item 1; S30).
5. **Bounds:** rights and lock unchanged; radius 6; `peripheralRange` ≤ 64; region plugins can veto; no pointer or key data (D-016).

#### 3.3.5 URL, navigation and rights reach

`ActionRules.urlChange` checks `distanceToScreen ≤ interactRange` today (VERIFIED `AW:server/ActionRules.java:37-44`). It becomes OK when any of these holds: within `interactRange`; the player is the controller with a `LASER` grant, within `ControlReach.laser`, with the laser held or seen within 5 s; the player has a valid peripheral session for this screen with the eye inside its radius. Rights and epoch checks are unchanged. The same reach applies to `C2SNav` and `C2SRights` (`AW:server/ScreenActions.java:298`), so Settings works from the booth.

#### 3.3.6 Client lock flows

1. **First input on a free screen** (a click, or opening a session): `claim` sends take; input is applied optimistically for 3 s (wave 2, VERIFIED `ClientScreens.java:673-690`).
   - **If the take is not granted** (someone else gets the lock, or the window ends without `controller == me`): `PointerSession.releaseAll` and `HeldKeys.releaseAll` first, then the wave-2 resync (`endOptimism`, `ClientScreens.java:757-770`), then **an open session closes** with the toast "Não foi possível assumir o controle desta tela" (RA-7).
2. **Keepalive** at most every 5 s while the player interacts: press, wheel, key, or **pointer motion over the picture inside a session** (C-29). Never on normal-play hover. Never while `ControlAim` predicts a refusal.
3. **Messages.** A first take that `ControlAim` predicts the server would refuse shows "Olhe para a tela" (look cone) or "Chegue mais perto" (distance). A keepalive that would fail the grant geometry shows "Chegue mais perto" only (§3.3.3 has no look check).
4. **Idle warning.** In a session with no activity for `controllerIdleSeconds − 15` s (45 s by default), the bar shows "Ainda está aí? Mexa a mira na tela, clique ou digite para manter o controle". Not shown while a video plays (the server keeps the lock then).
5. **Pre-emption is never implicit.** A click on BUSY gives a toast. Owner or admin on PREEMPT: confirm dialog → take; the server pre-empts and audits it (VERIFIED `AW:server/ScreenActions.java:239-242`). The pre-empted player's session closes with "%s assumiu o controle" (C-14).
6. **Take before setting the URL** (wave-2 finding 1). When the address bar submits a URL or a nav action on a **free** SHARED screen and the player has `CLICK_TYPE`, the client sends `C2SControl take` first, then `C2SSetUrl`/`C2SNav`; both go through the same per-player FIFO (INFERRED from PROTOCOL §2 "Threads"; S11). A player with `CHANGE_URL` but no `CLICK_TYPE` changes the URL without becoming controller (today's behaviour; R-08).
7. **Release:** "Liberar controle" in the address bar; the binding `akashicweb.key.release`; `/web release`; Esc from a session (`leaveSoon`, 5 s grace, `ClientScreens.java:710-716`); walking past the release distance.
8. **Navigation that moves every viewer** (back, forward, reload from the bar, side buttons in a session, Alt+arrows, Ctrl+R) shows the controller a one-time-per-session toast "A página muda para todos que assistem esta tela" (RB-8).

#### 3.3.7 What each player sees and hears

| Viewer | Page | Frame | Cursor | Badge (`AIMED` default) | Sound |
|---|---|---|---|---|---|
| Controller | Own browser, own hover, typing | Solid cyan; keyboard glyph in a session | Page cursor sprite | "Você controla" chip in their GUIs | Own click and typing sounds |
| Friend, lock free | Converged page | Dark cyan when aimed | Plain arrow | Shown while aiming | — |
| Friend, lock busy | Converged page, no hover | Amber when aimed | NOT_ALLOWED sprite | Shown while aiming | Denied tick on click |
| Owner or admin, busy | Same | Amber | Crown badge | Shown while aiming | — |
| Visitor (watch only) | Converged page | None | Vanilla crosshair | **Shown while aiming** (RB-18) | — |

- "Converged" means URL, epoch, media clock and, new, video fit (§3.3.9). Nobody sees another player's cursor, hover, typing, menus, in-page scrolling or laser beam (D-016; owner question P-15).
- The badge rule is one rule for everyone: `feedback.controllerBadge = AIMED` shows it while aiming and for 5 s after a controller change; `ALWAYS` and `NEVER` are prefs.

#### 3.3.8 The telão playbook (INFERRED; lead defaults under D-014)

1. **Configuration:** SHARED, `otherRights = 0`, staff as friends with `CLICK_TYPE | CHANGE_URL`; optionally `locked`. `interactRange` 24; `peripheralRange` 32 (max 64); laser reach 48 on a 32×18 face.
2. **Booth:** a Keyboard block on a desk, linked by sneak-use with the item aimed at the telão, then placed. Right-click opens a session; Ctrl+L opens the address bar; typing works while looking anywhere within 6 blocks of the desk.
3. **Plaza:** the Laser Pointer clicks, drags and scrolls up to 48 blocks.
4. **Precision at range:** the address bar's live preview drawer (Phase C), about 2 page px per window px (INFERRED, R5 §2.6 model), with your back to the telão if needed.
5. **Fullscreen:** the controller presses the player's fullscreen button or `f`; every viewer's telão shows only the video (§3.3.9). Shift+Esc (Esc to the page) leaves it for everyone.
6. **Visitors:** no cursor or frame; one toast on click; badge while aiming.
7. **Pack:** `TileEntityScreen.isForcedVisible()`; polygon offsets against z-fighting beyond about 60 blocks (R5 SF-1); the FpsReducer idle setting (R4 §4.3, owner pack task).

#### 3.3.9 Video-fit convergence (C-39; Phase A)

What the controller sees in fullscreen, every viewer sees, without relaying any input.

| Step | Where | Rule |
|---|---|---|
| 1. Sample | Controller's host | Already reports `fullscreen` in MEDIA (VERIFIED `HOST:media.js:121,172`; `HOST:screens.js:242-243`) |
| 2. Send | Controller's client (`client.sync.MediaSampler`) | `C2SMediaSample` carries the bit as one trailing byte, only when the server's hello says `protoMinor ≥ 1` (§6.1.4). Same cadence as today (≤ 4 Hz) |
| 3. Own | Server, `ScreenSession.fit` | Set from accepted controller samples. **Sticky**: kept when the controller releases or leaves; reset to false on a new URL epoch. A new controller's samples overwrite it |
| 4. Broadcast | Server | `S2CMediaClock` gains an appended `fit` byte (S2C append is allowed) |
| 5. Apply | Each follower's client (`client.sync.SyncController`) | Sends host `SYNC` with `fullscreen: fit` on a change of `fit`, and again at most once per second while its own MEDIA sample disagrees (page re-render, SPA navigation). Only when the host's HELLO `protoMinor ≥ 1` |
| 6. Fullscreen | Follower's host | `fullscreen:true` and no fullscreen element: pick the **main video** (largest rendered area, `readyState ≥ 1`, visible) in the main frame, else in sub-frames through `webFrameMain`; call `requestFullscreen()` via `executeJavaScript(code, userGesture = true)` (the API lifts the user-activation requirement, INFERRED from Electron docs; WP-1 fact (e)). If it fails, inject the media-fit CSS on that element (`position:fixed; inset:0; width:100vw; height:100vh; object-fit:contain; z-index:2147483647; background:#000`). `fullscreen:false`: exit only a fullscreen the host itself entered |

- A follower who later becomes controller already shows the fullscreen page; its own samples then report `fullscreen = true`, so `fit` stays consistent (INFERRED).
- The window does not resize: the host already sets `disableHtmlFullscreenWindowResize: true` (VERIFIED `HOST:screens.js:95`).
- D-016: one presentation bit per media sample, owned by the server like the clock; no pointer, key or page content crosses the network.

### 3.4 Interaction state machine (pure `client.input.InteractionMachine`)

One machine per local player. Input: events from the adapters. Output: an ordered list of `PageCommand` (to `ScreenInputController`) and `UiCommand` (open, close, toast, sound, grab intent). The *focus screen* is the one the pointer is on, or the one the session belongs to.

#### 3.4.1 States

| State | Holds when | What the player sees |
|---|---|---|
| `IDLE` | No eligible picture under the aim | Vanilla |
| `AIM` | Crosshair on a picture within reach, nothing nearer (blocks or entities), empty hand or the Laser Pointer, no GUI, window focused | Per access (§3.2) |
| `AIM_FRAME` | Crosshair on the 2/16 bezel of a formed screen | Vanilla crosshair; frame dimmed |
| `PRESSED(btn)` | A page button held from the crosshair | Pressed cursor; the page follows the drag (capture clamp) |
| `SESSION(sub)` | `SessionScreen` open (LOOK). Sub-states `READY`, `PRESSED(btn)`, `PAUSED(UNFOCUSED)` | Top bar; world and screen fully visible; keys go to the page |
| `PANEL(kind)` | Address bar, Settings, Consent, Doctor, Help or Confirm open | That panel; the world stays visible except for Consent (dimmed on purpose) |

#### 3.4.2 Transitions

Every row that leaves `PRESSED` or `SESSION` runs **release-all** (§3.4.3). "Hand ok" = empty hand or the Laser Pointer.

| From | Event | To | Effects |
|---|---|---|---|
| IDLE / AIM | Aim enters or leaves a picture (sampled per frame) | AIM / IDLE | Leaving with no button held sends `mouseLeave` once, if we drove |
| AIM (DRIVE, TAKE_PENDING) | Aim moves ≥ 1 page px | AIM | `mouseMove`, ≤ 1 per frame, only while driving |
| AIM (DRIVE, TAKE, TAKE_PENDING) | Use or attack down, not sneaking, hand ok | PRESSED(left) | Entity check on the press path (§3.6): an entity nearer than the picture → no transition, event untouched. Else `claim` (TAKE only), `mouseMove` if needed, `mouseDown(left, clickCount)`; Forge event cancelled |
| PRESSED | Same button up | AIM | `mouseUp` at the last captured point; cancelled |
| PRESSED | Another button down | PRESSED | Cancelled with its later release; not sent (one world button at a time) |
| PRESSED | Any GUI opens (`GuiOpenEvent`), window inactive, screen gone, lock lost, death, world unload, dimension change, or `Mouse.isButtonDown` false at a frame | AIM / IDLE | release-all |
| AIM (DRIVE, TAKE_PENDING) | Middle down, hand ok | PRESSED(middle) | Page middle (autoscroll); pick block cancelled |
| AIM (TAKE) | Middle or wheel | — | Vanilla (pick block, hotbar). Hint "Clique para usar a página" (C-41) |
| AIM (DRIVE, TAKE_PENDING) | Wheel, not sneaking, hand ok | AIM | Page wheel at the aim point, fractional (§3.5.5) |
| AIM / AIM_FRAME | Sneak + wheel | — | Vanilla hotbar |
| AIM (DRIVE) | Button 4 / 5 (LWJGL 3 / 4), pref `input.sideButtons = ALWAYS`, button not bound to any `KeyBinding` | AIM | `C2SNav back/forward`; cancelled; "muda para todos" toast (§3.3.6 item 8). Default `SESSION`: vanilla here (C-42) |
| AIM (PREEMPT) | Use or attack down, hand ok | PANEL (confirm) | Cancelled; deferred open |
| AIM (BUSY, WATCH_ONLY, LOCKED, OFFLINE) | Use or attack down, hand ok | AIM | Cancelled; throttled toast; denied tick |
| AIM (NEEDS_ENGINE) | Use / attack, hand ok | PANEL (consent) / AIM | Consent opens / attack cancelled |
| AIM / AIM_FRAME | Sneak + use, **hand ok** | PANEL (address) | Deferred open (C-13) |
| AIM / AIM_FRAME | Sneak + use, any other item | — | Vanilla: the item acts or places; the Keyboard item links (§5.1) |
| AIM / AIM_FRAME | Sneak + attack | — | Vanilla break; server protection applies (`SCREEN_PROTECTED`) |
| AIM_FRAME | Attack, not sneaking, hand ok | AIM_FRAME | Cancelled; toast "%s para quebrar a tela" (C-12) |
| AIM_FRAME | Use, not sneaking, hand ok | — | Cancelled; nothing |
| any | Any event while another item than the laser is held | — | Vanilla (C-04) |
| IDLE / AIM | Binding "Digitar na tela" | SESSION | Aimed screen, else the last driven screen within reach, else toast "Mire numa tela primeiro". BUSY: toast; PREEMPT: confirm first |
| any (no GUI) | Right-click a linked Keyboard block | SESSION | Server opens a peripheral session (§3.3.4); camera eases toward the screen (≤ 150 ms) if the look is outside the cone (pref `input.turnToScreen`) |
| AIM (DRIVE) | Host `textInputSeq` increases within 500 ms after our own page `mouseUp`; pref `input.autoTypeOnTextField` | SESSION | Opens after the mouseUp (C-11) |
| PANEL (address) | "Digitar" | SESSION | The bar closes; the session opens |
| SESSION | Esc | AIM / IDLE | `leaveSoon` (5 s) |
| SESSION | Ctrl+L | PANEL (address, `returnTo = SESSION`) | The session closes; closing the bar opens a fresh session |
| SESSION | Ctrl+T ("Conversar") | `GuiChat` | Lock kept; when chat closes within 60 s and the screen is still valid, a fresh session opens |
| SESSION | `Display.isActive()` false | `PAUSED(UNFOCUSED)` | release-all now. LWJGL also synthesizes key-ups on focus loss (VERIFIED `LW:java/org/lwjgl/opengl/WindowsDisplay.java:367-391`, `LinuxKeyboard.java:310-318`); they are dropped by I4. The GUI stays open (C-15) |
| `PAUSED(UNFOCUSED)` | Window active again | previous | **Key downs and button downs** within 200 ms are ignored (focus-regain click; Windows turns the first repeat into a fresh press) |
| SESSION | Lock taken by someone else; **take not granted** (§3.3.6 item 1); distance > reach + 2; screen gone; dimension change; death; world unload; host DOWN; kill switch; damage taken (pref `input.exitSessionOnDamage`) | IDLE | Close; toast with the reason |
| SESSION | Any other GUI is displayed (server GUI, `GuiGameOver`, another mod) | that GUI | Session closes (MUI2 calls `onClose` on every GUI change, VERIFIED `MUI:screen/ClientScreenHandler.java:207-254`); release-all; grab intent released (C-47) |
| PANEL | Esc or the close button | previous | One Esc closes (§4.0) |

#### 3.4.3 release-all (pure `ReleasePlan`, executed in a `finally`)

1. `mouseUp` for every page button we pressed, at the last page point.
2. `keyUp` for every key in `HeldKeys`.
3. `mouseLeave` at the last page point.
4. Host INPUT `releaseAll`, a second line of defence (§6.2).
5. `KeyBinding.unPressAllKeys()`.
6. `GrabArbiter.release(SESSION)` (applied at the next render tick start) and HUD restore.

`UseSwallow` is gone (C-49): the session open already unpressed `use` (VERIFIED `MC:client/Minecraft.java:1396-1404` while `inGameHasFocus` was true), and Hodgepodge's refocus re-sync skips mouse codes (VERIFIED `HP:mixins/early/minecraft/MixinKeyBinding.java:86-93`).

#### 3.4.4 Invariants (property-tested over random event scripts with an injected clock)

| # | Invariant |
|---|---|
| I1 | Every page DOWN is followed by exactly one UP for the same screen and button, on every path |
| I2 | At most one `mouseMove` per rendered frame per screen, only on a page-pixel change, ≤ 60 per second |
| I3 | No page event unless `drivesInput` |
| I4 | **No key-up is emitted** for a key never sent down in this session. This covers the release of the key or click that opened the session (R1 §2.8) and LWJGL's synthetic key-ups. It never suppresses key-0 text events, which are text in any state (RA-4) |
| I5 | Never two sessions. Opening one for another screen closes the first; `UiOpen` drops a pending open for the same screen and kind (repeated Keyboard activation, RA-20) |
| I6 | Normal-play hover never takes the lock and is never a keepalive; in-session pointer motion over the picture may be a keepalive, never a first take |
| I7 | release-all is emitted exactly once per exit; the machine is IDLE or AIM afterwards |
| I8 | Nothing mines, uses, places or hits an entity because of a page gesture: every press we act on is cancelled together with its release |
| I9 | **A release whose press vanilla saw is never cancelled** (wave-2 rule, VERIFIED `AW:client/screen/ScreenInteraction.java:50-54,73`); a press we cancelled always has its release cancelled |
| I10 | `Mouse.setGrabbed` is called only from `GrabArbiter` at `RenderTickEvent` START. One frame after a session hands over to any non-null, non-session GUI, the mouse is not grabbed |
| I11 | A key physically down when a session opens reaches the page neither as down, repeat nor char until it has been released once |

### 3.5 Mouse mapping

Button indices: use = `keyBindUseItem + 100`, attack = `keyBindAttack + 100` (VERIFIED `AW:client/screen/ScreenInteraction.java:74-75`), pick block = `keyBindPickBlock + 100` likewise. One fixed table maps LWJGL 0 to left, 1 to right, 2 to middle, 3 and 4 to the side buttons 4/5; there is **no swap option** (CinemaMod#59, R2 U7); buttons ≥ 5 are ignored (R2 U8).

**Bound-button rule (C-42).** A mouse button whose code (`button − 100`) is used by any registered `KeyBinding` other than attack, use and pick block is never consumed, in the world or in a session. Its owner (for example a voice push-to-talk on a side button, VERIFIED `AV:keybinds/PttInputBridge.java:21-26`) keeps working.

#### 3.5.1 Normal play (no GUI)

"Drivable" = DRIVE or TAKE_PENDING (TAKE also for use and attack).

| Input on the picture | Empty hand | Laser Pointer held | Any other item (`ItemBlock` included) |
|---|---|---|---|
| Use or attack | Page **left**: press, drag, release (WD); a click on TAKE takes the screen | Same, at laser reach | Vanilla (C-04) |
| Middle | Page middle when drivable; vanilla pick block on TAKE | Same | Vanilla |
| Buttons 4 / 5 | Vanilla (default `input.sideButtons = SESSION`); back/forward with `ALWAYS` | Same | Vanilla |
| Wheel | Page wheel when drivable; hotbar on TAKE (C-41) | Same | Hotbar |
| Sneak + wheel | Hotbar | Hotbar | Hotbar |
| Sneak + use | Address bar | Address bar at range | Vanilla; the Keyboard item links (§5.1) |
| Sneak + attack | Break (server protection) | Break | Break |
| Attack on the bezel | Swallowed + toast | Swallowed + toast | Vanilla |
| **An entity nearer than the picture** | Vanilla (C-40) | Vanilla | Vanilla |

Shift is sneak, so there is no horizontal wheel in normal play.

#### 3.5.2 Session (LOOK: mouse grabbed, the crosshair is the pointer)

| Input | On the picture | Off the picture |
|---|---|---|
| Left | Page left: down, drag, up | Nothing |
| Right | Page **left** (default, C-38); page right with `input.rightButtonInSession = RIGHT` | Nothing |
| Middle | Page middle | Nothing |
| 4 / 5 | Back / forward (`C2SNav`, "muda para todos" toast) when `input.sideButtons` is SESSION (default) or ALWAYS, unless the button is bound to a `KeyBinding` | Same |
| Wheel | Page wheel; Shift = horizontal; Ctrl stripped (no page zoom) | Nothing |
| Motion | Camera turns (LookDriver); the page pointer follows; counts as activity (C-29) | Camera turns |
| Menu key, Shift+F10 (Phase D) | Context-menu dialog at the pointer (§4.9) | — |

Buttons and wheel come from ModularUI2's `MouseInputEvent.Pre`, which `client.ui.input.RawInputHooks` cancels at `EventPriority.HIGHEST`, so ModularUI2 widgets never see them (VERIFIED cancelable, `MUI:api/event/MouseInputEvent.java:17-18`). They are mapped at the crosshair using the last displayed frame's aim (R5 §3.3).

#### 3.5.3 Preview drawer (Phase C; the one precision path)

`PagePreviewWidget` (R1 A.5, V-BUILD) in the address bar draws the screen's GL texture at the page's aspect ratio (300×169 at 16:9). It maps press, drag, wheel and hover to page pixels from raw LWJGL coordinates (pure `GuiPageMapper`), under the same D-016 gate and the same grants (DIRECT, LASER or PERIPHERAL). About 2 page px per window px at GUI scale 2 (INFERRED from R5 §2.6), against 3.2-6.3 for the crosshair at 30-60 blocks. It is the minePad equivalent: the owner can drive the telão with their back to it. The free-cursor CURSOR style is post-event (C-01).

#### 3.5.4 Shared rules

- **Click count.** `ClickCounter` v2 per button, within 500 ms, slop ≤ 6 page px (crosshair) or ≤ 4 window px × k (drawer). Today's slop is 0.01 of the face, about 13 px on a 1280-px page (VERIFIED `AW:client/screen/ClickCounter.java:10-12`).
- **Modifiers (RA-3).** `ModifierTracker` follows the keyboard event stream (L/R Shift, Ctrl, Alt, Meta; AltGr = LCtrl + RAlt on Windows) and re-syncs from `Keyboard.isKeyDown` when a session opens; it drives key events and AltGr detection. **Mouse and wheel events carry tracker state OR the polled `Keyboard.isKeyDown` state**, because a GUI drains every mouse event of a tick before any key event (VERIFIED `MC:client/gui/GuiScreen.java:307-323`) while LWJGL's polled state already reflects the whole batch read at `Display.update`. M2 must serialise them (VERIFIED missing, `AW:client/host/HostMessages.java:117-155`). In normal play no modifiers are sent (Shift is sneak).
- **Wheel.** `WheelAccumulator`: 120 raw units = 1 notch = `input.wheelPixelsPerNotch` (100, hidden pref) host px; keeps sub-notch remainders; caps a flood at ±10 notches per event. Replaces `signum × max(1, |d|/120)` (VERIFIED `ScreenInteraction.java:61-71`, R5 SF-3).
- **Leave.** `mouseLeave` when the pointer leaves the picture with no button held, on session close and on focus change. A held button keeps capture until release.
- **No Ctrl + wheel.** It would zoom the controller's layout away from the followers'.

### 3.6 Aim, reach and occlusion (pure `client.input.AimPicker`, `FacePlane`, `AimHit`)

- **Ray.** The interpolated eye plus `getLook(pt)` (R5 Appendix A: the camera eye lies on the same line, so no matrices are needed).
- **Sampling.** Once per frame at `RenderTickEvent` START with `renderTickTime`; presses arrive at 20 Hz in `runTick` and use the last frame's hit (R3 finding 4).
- **Plane.** The lifted plane (+0.005, `ScreenGeometry.SURFACE_OFFSET`); removes the 3-10 page px parallax at 60-80° of today's `HitMath.rayHit` on the block surface (VERIFIED `ClientScreens.java:555`; R5 §2.4, SF-2).
- **Classification.** `PICTURE`, `FRAME` (2/16 border) or none; the nearest screen wins; back faces drop out.
- **Reach by source:**

  | Source | Reach |
  |---|---|
  | Crosshair, empty hand | `hello.interactRange` (default 24; `POINTER_REACH` parity, VERIFIED `ClientScreens.java:116`) |
  | Laser Pointer held | `ControlReach.laser(s, hello)` (§3.3.2) |
  | Session from a Keyboard block | `max(interactRange, distance(keyboard, face) + 8)` |

- **Block occlusion.** A block ray trace from the eye to the hit, stopping 0.05 short (wave-2 rule, VERIFIED `ScreenInteraction.java:166-189`), only when the aimed page pixel or the eye block changed, at most once per frame.
- **Entity occlusion (C-40, RA-6/RB-1).**
  - *Per frame, zero allocation:* if `mc.objectMouseOver` is an `ENTITY` hit nearer than the picture hit, there is no picture hit. Vanilla picks entities within 3 blocks in survival (VERIFIED `MC:client/renderer/EntityRenderer.java:357-366`).
  - *On every press and wheel event (≤ 20 Hz; allocation allowed):* `world.getEntitiesWithinAABBExcludingEntity(player, box)` over the ray's box up to the picture distance; each entity with `canBeCollidedWith()` is tested by `boundingBox.expand(border).calculateIntercept(eye, hit)` (border = `getCollisionBorderSize()`). Any hit nearer than the picture → the event is left to vanilla untouched (no cancel, no page event, no take). Item frames, players, mobs and NPCs occlude; dropped items and arrows do not (`canBeCollidedWith` false).
  - Cosmetic limit: beyond 3 blocks the per-frame check does not see entities, so the page cursor can be drawn behind a distant mob while the press itself stays correct (INFERRED acceptable).
- **Handler order at `RenderTickEvent` START** (RA-16), on the FML bus:

  | Priority | Handler |
  |---|---|
  | HIGHEST | `GrabArbiter` + `LookDriver` (§3.8.2) |
  | HIGH | `ClientTaskQueue.drain()` (opens deferred GUIs; VERIFIED drained at this event, `AW:client/net/ClientTaskQueue.java:10-11`) |
  | LOW | `AimSampler` (`WorldPointer` aim, hover, cursor) |

  So the sampled aim uses this frame's camera angles, and a GUI opened by the drain has its grab handled one frame later. `RenderTickOrderTest` reads the `@SubscribeEvent` priorities by reflection.
- **`ControlAim`** (pure) mirrors the server's grant rules minus line of sight, using the limits block; a take or keepalive the server would refuse is not sent (§3.3.6 item 3).
- **Zero allocation per frame** on the aim path: mutable `AimHit`, cached `FacePlane` per `ClientScreen`, preallocated vectors; enforced by an allocation-guard test (§8.1). The press path may allocate.

### 3.7 Keyboard

#### 3.7.1 Capture

`client.ui.input.RawInputHooks` subscribes to ModularUI2's `KeyboardInputEvent.Pre` at `EventPriority.HIGHEST` (VERIFIED cancelable, `MUI:api/event/KeyboardInputEvent.java:17-18`). It acts only when `ModularScreen.getCurrent()` is a capturing `RawKeySink` (`SessionScreen`, or the Doctor key tester in observe mode). For each LWJGL event it:

1. reads `getEventKey`, `getEventCharacter`, `getEventKeyState`, `isRepeatEvent`, `getEventNanoseconds`;
2. applies `PreHeldFilter` (I11);
3. feeds `ModifierTracker`;
4. handles **Minecraft keys**: a non-repeat press of the code bound to `keyBindScreenshot` or `field_152395_am` (fullscreen), when that code is a non-text key (`KeyClass.isNonText`: F1-F19, Print, Pause, Scroll, Insert, navigation keys), runs the same action vanilla runs (screenshot to chat; `toggleFullscreen()`), VERIFIED `MC:client/Minecraft.java:2961-2968`;
5. feeds `SessionKeys`, then `KeyboardRouter` v2;
6. cancels the event.

It **never calls `func_152348_aa`** (C-43): with the stock null stream, the F6 stream key opens `GuiStreamUnavailable` over the session (VERIFIED `MC:client/Minecraft.java:2902-2933`; F6 default `MC:client/settings/GameSettings.java:223`). ModularUI2's LOW-phase Esc and E never run. Our code never references `IMuiScreen`.

#### 3.7.2 Reserved keys inside a session (pure `SessionKeys`)

**"Ctrl" in this table always means `(ctrl && !alt) || meta`**, exactly the router's shortcut rule (VERIFIED `AW:client/gui/KeyboardRouter.java:65`). AltGr (LCtrl + RAlt on Windows) is therefore never a reservation (RB-7).

| Keys | Action | Path |
|---|---|---|
| Esc | Leave the session | Client |
| Shift+Esc | Esc to the page (close a modal, leave fullscreen video) | Host keyDown/keyUp `Escape` |
| Ctrl+L | Address bar with the URL selected; returns to the session | Client |
| Ctrl+T | "Conversar": chat, then back | Client |
| Ctrl+R | Reload; moves every viewer (toast, §3.3.6 item 8) | `C2SNav reload` |
| **Left** Alt + ← / → (no Ctrl) | Back / forward | `C2SNav` |
| Screenshot and fullscreen keys (F2 and F11 by default) | Minecraft only (§3.7.1 step 4) | Local |
| F5, F6, F7, and any code bound to a stream binding | **Dropped** (F5 is the perspective habit and would reload the telão; F6/F7 are stream keys) | — |
| Ctrl+C, Ctrl+X, Ctrl+A, Ctrl+Z, Ctrl+Y, Ctrl+Shift+Z | copy, cut, selectAll, undo, redo | Host INPUT `edit` (C-24) |
| Ctrl+V, Shift+Insert | Paste `GuiScreen.getClipboardString()` as CHAR chunks (existing `KeyboardRouter.paste`, surrogate-safe, ≤ 512 units) | CHAR |
| Ctrl+P / S / O / N / W / U / J / H / D, Ctrl+Tab, Ctrl + `+` / `-` / `0` | Dropped (no dialogs, tabs or zoom) | — |
| Any other Ctrl/Meta + key | keyDown/keyUp with modifiers (Ctrl+F, Ctrl+Enter work) | Host |
| AltGr + key | Text only | CHAR |
| Menu key, Shift+F10 (Phase D) | Context-menu dialog at the pointer: right down/up sent at the pointer, then EVENT `context-menu` | Client + host |
| F1, F3, F4, F8, F9, F10, F12 | To the page | Host |
| A text key bound to a pass-through binding (Akashic Voice PTT) | Still typed; see §3.7.6 | CHAR |

Everything else goes to the page: WASD, T, E, Q, digits, `/`, the session key. The top bar says so (§4.2).

#### 3.7.3 `KeyboardRouter` v2 and the key filters (pure; existing tests kept)

1. **Kept:** char-only key-0 events are text in any state (VERIFIED `KeyboardRouter.java:84-88`); dead key + Space types the accent (`:72-75`); surrogates joined across events; repeats repeat.
2. **New: `PreHeldFilter` (RA-4, I11).** At session open, every LWJGL code < 256 with `Keyboard.isKeyDown` true goes into a pre-held set. Downs, repeats and chars of those codes are dropped until their first release, which is also dropped. MUI2 turns key repeat on at every MUI screen open (VERIFIED `MUI:screen/ClientScreenHandler.java:235`), so without it a player walking with W who clicks a search box would type "wwww".
3. **New: `HeldKeys`.** The set of keys sent down. A key-up for a key not in it emits nothing (I4); `releaseAll()` sends keyUp for each.
4. **New: page shortcuts on letters and digits.** When the LWJGL key is A-Z or 0-9 and the event char is that plain ASCII letter or digit (case-insensitive), send `keyDown(letter)` + CHAR, then `keyUp(letter)` on release (tracked in `HeldKeys`), so YouTube's k/j/l/f/m and keydown-driven games work (R3 finding 9). Any other char (ç, á, ´, symbols, AltGr output) stays CHAR only. **Gated** by router flag `letterKeyEvents`, on only after WP-1 fact (a) proves that a letter `keyDown` types nothing by itself.
5. **Changed:** Ctrl+C / Ctrl+X become `edit` (VERIFIED dropped today, `KeyboardRouter.java:79-83`); the screenshot, fullscreen, F5, F6 and F7 codes leave the page's named-key table (VERIFIED mapped today, `AW:client/gui/KeyMapping.java:72-74`).
6. **Unchanged:** modifiers alone are never sent as key events (R3 §4).

#### 3.7.4 Typing matrix: ABNT2 and US-International (expected; Windows shapes need G3-A)

| Typing | LWJGL 2 events (Windows shape, R1 §4.4; INFERRED where marked) | Page receives | Status |
|---|---|---|---|
| ´ then a | (dead, down, 0), (A, down, 'á') | CHAR "á" | Done (STATIC) |
| ` (Shift+´) then a | (dead, down, 0), (A, down, 'à') (INFERRED) | CHAR "à" | New test row |
| ^ then e; ~ then o | as above with 'ê' / 'õ' (INFERRED) | CHAR "ê" / "õ" | New test rows |
| ´ then Shift+E; Shift+ç | (E, down, 'É'); (SEMICOLON, down, 'Ç') (INFERRED) | CHAR "É" / "Ç", no keyDown (not ASCII) | New test rows |
| ~ then Space | (SPACE, down, '~') | keyDown Space + CHAR "~" | Done in wave 2 (STATIC) |
| ´ then x | (X, down, '´'), (0, up, 'x') | CHAR "´", CHAR "x" | Done in wave 2 (STATIC) |
| ç | (SEMICOLON, down, 'ç') | CHAR "ç" | Done |
| AltGr+Q → `/`, AltGr+W → `?` | (Q, down, '/') with Ctrl+Alt; (W, down, '?') with Ctrl+Alt | CHAR "/" / "?" (never Ctrl+W, RB-7) | Done in the router; new `SessionKeys` rows |
| ABNT_C1 "/?" key | (0, down, '/') | CHAR "/" | Done |
| Alt+0233 | (0, up, 'é') | CHAR "é" | Done |
| Emoji | high surrogate on down, low on (0, up) | One CHAR with the pair | Done |
| US-Intl ' then c | (APOSTROPHE, down, 0), (C, down, 'ç') (INFERRED) | CHAR "ç" | New test row |
| US-Intl " then Space | (SPACE, down, '"') after a dead key (INFERRED) | keyDown Space + CHAR '"' | New test row |
| US-Intl AltGr+A → á | (A, down, 'á') with Ctrl+Alt (INFERRED) | CHAR "á" (never selectAll) | New `SessionKeys` row |
| k on YouTube | (K, down, 'k') | keyDown k + CHAR k + keyUp k | New (flag) |
| Ctrl+C | (C, down, 0x03) with Ctrl | INPUT `edit copy` | New |
| AltGr+= → § | (EQUALS, down, '§') | CHAR "§" (our URL field refuses §) | Done |

#### 3.7.5 Key bindings (vanilla Controls, category `akashicweb.key.category`)

| Id (lang key) | pt_BR name | Default | Note |
|---|---|---|---|
| `akashicweb.key.keyboard` (id kept; name changes) | Digitar na tela | Code default `KEY_NONE`; **`KeyAutoAssign`** (below) | Opens a session on the aimed screen. Saved bindings persist because the id is kept (VERIFIED `MC:client/settings/GameSettings.java:1054,1167`) |
| `akashicweb.key.address` | Barra de endereço da tela | `KEY_NONE` | Sneak + use and Ctrl+L already cover it |
| `akashicweb.key.release` (new) | Liberar controle da tela | `KEY_NONE` | `leave()` on the screen we control |

**`KeyAutoAssign` (C-10, Phase A).** On the first client tick with a world, once per profile (`ClientPrefsInternal.keyAutoAssigned`), when `akashicweb.key.keyboard` is still `KEY_NONE`: collect the codes of every registered `KeyBinding` (`mc.gameSettings.keyBindings`, which by then includes every mod's bindings, INFERRED), pick the first of **K (37), U (22), J (36), H (35), Y (21)** not in use (pure `KeyAutoAssign.pick(used, candidates)`), set it, call `KeyBinding.resetKeyBindingArrayAndHash()` and `gameSettings.saveOptions()`, and toast "Tecla para digitar na tela: %s (mude em Controles)". If none is free the binding stays unset and the toast points to Controls. N, V and O are never candidates (Akashic Voice mute, push-to-talk and settings, VERIFIED `AV:keybinds/AkashicVoiceKeybinds.java:13-17`). The owner can still pick a fixed default after `/awdoctor keys` (P-14).

Hints never print a literal key name: they resolve the bound key live with `GameSettings.getKeyDisplayString(code)` (VERIFIED `MC:client/settings/GameSettings.java:311-314`; a mouse code reads "Botão %1$s", `MCA:` line 850), and vanilla action names with `I18n.format("key.sneak")` etc. (`MCA:` lines 824, 856, 863). Missing binding → the hint segment is left out.

#### 3.7.6 Akashic Voice and other polled keys (C-44)

- Akashic Voice reads push-to-talk by polling `Keyboard.isKeyDown` / `Mouse.isButtonDown` every client tick with no screen guard (VERIFIED `AV:keybinds/PttInputBridge.java:30-41`, `AV:ClientVoiceBootstrap.java:220-229`). Cancelling MUI2 events does not change what it polls.
- Inside a session, the codes of the bindings listed in the hidden pref `advanced.passThroughBindings` (default `key.akashicvoice.ptt`, matched by description at session open) are handled so:
  - **non-text key or mouse button:** never routed to the page and never used as a session key, so the player can talk while typing;
  - **text key** (the default V): still typed into the page, because withholding it would make "v" untypeable. The bar shows a one-time hint "A tecla de falar (%s) também digita aqui" and the Doctor lists it. This is the same behaviour Akashic Voice already has in vanilla chat (INFERRED from the polling above).
- Cross-repo proposal for the owner (P-14): Akashic Voice ignores keyboard push-to-talk while a text-input screen is open, or defaults it to a non-text key. Out of AkashicWeb's scope.

#### 3.7.7 Chat, inventory and stuck keys

- In a session, T and E type into the page; Ctrl+T ("Conversar") chats without losing the session.
- Opening a session from in-game unpresses every binding (VERIFIED `MC:client/Minecraft.java:1396-1404`); leaving releases every page key (release-all; R2 U5, WD#138 fixed).
- With Hodgepodge, a movement key still physically held when a session closes is pressed again by the refocus re-sync, so the player walks while it is held and stops on release (VERIFIED `HP:mixins/early/minecraft/MixinMinecraft_UpdateKeys.java`; the same as later Minecraft versions). S16 asserts exactly that.
- A focused NEI search field could take keys first, so `Platform.unFocusRecipeViewer()` is called in `onOpen` (R1 §4.5).

### 3.8 Session mechanics

#### 3.8.1 `client.ui.SessionScreen`

- Extends `ModularScreen`, implements `RawKeySink`; `pausesGame(false)`, `drawDarkBackground(false)`.
- One panel, `ModularPanel("akw_session").fullScreenInvisible()` (VERIFIED `MUI:screen/ModularPanel.java:931`), holding the top bar only. No interactive widget, so nothing reacts to the clamped virtual mouse while grabbed.
- All logic in `client.input.SessionController`; the screen is a thin view of `SessionView`, replaceable by a vanilla `GuiScreen` view (ADR-017 fallback).
- Opened through `UiOpen.later(...)` on `ClientTaskQueue` (next tick; R1 §3.1), de-duplicated (I5).
- **`onOpen()`:** `Platform.unFocusRecipeViewer()`; lwjgl3ify `InputEvents.beginTextInput()` by reflection if present (R4 §4.6); `ModifierTracker.resync()`; `PreHeldFilter.snapshot()`; `controller.claim()` (take, or keepalive if ours); `GrabArbiter.acquire(SESSION)`; `BrowserBudget` rank boost and RENDER fps 60 for this screen.
- **`onUpdate()`:** `super` first, then `SessionRules.check(ctx)` (exit and pause reasons, §3.4.2).
- **`onClose()`** (MUI2 calls it on every GUI change, VERIFIED `MUI:screen/ClientScreenHandler.java:207-254`): release-all; `endTextInput`; `GrabArbiter.release(SESSION)`; `leaveSoon` unless closing for Ctrl+L or Ctrl+T; budget boost off.

#### 3.8.2 `client.input.mc.GrabArbiter` and `LookDriver` (C-02, C-47)

```java
// One handler on TickEvent.RenderTickEvent START, EventPriority.HIGHEST (before EntityRenderer.updateCameraAndRender,
// MC:client/Minecraft.java:1064-1067). The only place where AkashicWeb changes the mouse grab (I10).
void onRenderTickStart() {
    boolean want = sessions.lookActive()                    // a SessionScreen is mc.currentScreen
        && Display.isActive() && !Boolean.getBoolean("fml.noGrab");
    if (want && !Mouse.isGrabbed()) {                       // session opened, or self-heal after resize/chat
        mc.mouseHelper.grabMouseCursor();                   // drops queued mouse events (LW Mouse.java:647-672)
        sessions.reconcileButtons(Mouse::isButtonDown);     // a held page button whose release was dropped -> mouseUp
    } else if (!want && ownsGrab && mc.currentScreen != null && Mouse.isGrabbed()) {
        mc.mouseHelper.ungrabMouseCursor();                 // a GUI took over from a session, or the window lost focus (RA-1)
        Mouse.setCursorPosition(Display.getWidth() / 2, Display.getHeight() / 2);
        sessions.reconcileButtons(Mouse::isButtonDown);
    }
    ownsGrab = want;                                        // in-game (currentScreen == null): vanilla owns the grab
    if (!want) return;
    mc.mouseHelper.mouseXYChange();                         // vanilla does not read DX/DY while inGameHasFocus is false
    float f1 = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F, f2 = f1 * f1 * f1 * 8.0F;   // EntityRenderer.java:1041-1042
    float k = prefs.input.sessionLookSensitivity;           // default 0.6
    int inv = mc.gameSettings.invertMouse ? -1 : 1;
    mc.thePlayer.setAngles(mc.mouseHelper.deltaX * f2 * k, mc.mouseHelper.deltaY * f2 * k * inv);
}
```

- `inGameHasFocus` stays **false**: vanilla look never runs twice, and other mods see a GUI. Because of that, `setIngameNotInFocus()` never ungrabs when another GUI replaces the session (VERIFIED `MC:client/Minecraft.java:1396-1404`), which is why the arbiter does it (RA-1): Ctrl+L, Ctrl+T, the confirm dialog, `GuiGameOver`, a server-opened inventory.
- When the session closes to no GUI, `setIngameFocus()` grabs as usual and sets `leftClickCounter = 10000`, so a held left button cannot mine (VERIFIED `MC:client/Minecraft.java:1379-1391`).
- Grab changes discard queued mouse events, so they happen only here, never inside an input handler (RA-5). A click buffered in the same frame as a GUI transition can be lost; the reconcile step keeps the page balanced (INFERRED acceptable).
- `smoothCamera` is ignored inside a session (INFERRED: precise pointing matters more).
- ModularUI2 with a grabbed mouse is unverified (MUI2-UNVERIFIED-1): the panel has no interactive widget, and S6-S8 and S21 check it.

#### 3.8.3 Auto session on a page text field (C-11, Phase A)

- **Host (WP-1).** The preload, in every frame, listens for trusted `pointerup` events (capture phase, `event.isTrusted`; `sendInputEvent` input is trusted, INFERRED, WP-1 fact (f)). After the default action has run, if the event target or `document.activeElement` is editable (`input` of a text-like type, `textarea`, `[contenteditable]`), it reports it; the host increments the screen's `textInputSeq` (u32) and sends STATE, coalesced at 100 ms. STATE `textInput` stays true while an editable element has focus (`focusin`/`focusout`). A script that focuses a field bumps nothing.
- **Client.** Opens a session when: the local player drives; `input.autoTypeOnTextField` is on (default on, lead default); `textInputSeq` increased within 500 ms after **our own** page `mouseUp` sent with an empty hand or the laser. An already focused field works (RB-5). A one-time tip explains Esc.

#### 3.8.4 First-use typing notice

Shown once, in the bar for 8 s (reworded, RB-14): "As teclas vão só para o seu navegador, não para o servidor do jogo. Mas endereços e buscas que você abrir aparecem para todos que assistem esta tela. Telas compartilhadas não guardam login." The seen flag is `ClientPrefsInternal.typingNoticeSeen`. Wording: owner question P-16.

### 3.9 Visual and sound feedback, and rendering

#### 3.9.1 Elements

| Element | Who sees it | Spec | Phase |
|---|---|---|---|
| **Page cursor sprite** | Local driver only (D-016) | Drawn by the TESR in page space at the aim point, after the page quad, inside the face basis; turns with the page rotation (R5 §5.2).<br>- Shape: on DRIVE and TAKE_PENDING from STATE `cursor` through pure `CursorKind.fromElectron` (R5 §5.4; Electron `pointer` is the arrow, `hand` the link hand); **on TAKE always the plain arrow** (RB-20).<br>- Size `clamp(pageH/34, 12k, 64k)` page px × `cursor.scale`.<br>- Procedural atlas `CursorAtlas`: pure rasteriser, own art, 512×256, white fill with a 2-px dark outline (R5 §5.5).<br>- Pressed variant: 0.9 scale, darker.<br>- Badges: NOT_ALLOWED (BUSY), crown (PREEMPT). | A |
| **Crosshair swap** | Local | Cancel `RenderGameOverlayEvent.Pre(CROSSHAIRS)` while a page cursor or arrow is drawn (AIM on DRIVE, TAKE, TAKE_PENDING, BUSY, PREEMPT) and in sessions. Per frame even under Angelica's HUD cache (VERIFIED RA: `MixinGuiIngameForge_HUDCaching:28-31`, `HUDCaching.java:149-154`) | A |
| **Block highlight** | Local | Cancel `DrawBlockHighlightEvent` on screen blocks while a picture is aimed | A |
| **Frame highlight** | Local | Four thin full-bright quads in the 2/16 bezel around the **whole** face, all opaque (alpha-test recipe, RA-17):<br>- DRIVE and TAKE_PENDING: accent `0xFF6FB0FF`;<br>- TAKE: dark accent `0xFF3A6A99`;<br>- BUSY / PREEMPT: amber `0xFFFFD070`;<br>- WATCH_ONLY: none;<br>- session: accent plus a keyboard glyph at the top-right corner.<br>Colour is always paired with a shape cue | A |
| **Controller badge** | Everyone, visitors included | A plate on the bottom bezel with the controller icon and the name from `ScreenState.controllerName`. `feedback.controllerBadge = AIMED` (default: while aiming and for 5 s after a change), `ALWAYS`, `NEVER`. Text width cached per name | B |
| **Loading bar** | Everyone (own browser) | Indeterminate band, 1/120 of the face height, along the top edge while STATE `loading`; 1.2 s sweep; static under `render.reduceMotion` | B |
| **Power-on** | Everyone | WD's CRT effect (VERIFIED `WD:client/renderers/ScreenRenderer.java:94-104`): scale 0 → 1 from the centre over 150 ms on the first live frame after a placeholder; 100 ms reverse on unload; off under `reduceMotion` | B |
| **Placeholders** | Everyone | Existing `PlaceholderModel` cards, restyled (card `0xFF2A2F36`, title `0xFF6FB0FF`, body `0xFFE0E0E0`); the card's `glDisable(GL_TEXTURE_2D)` becomes a 1×1 white texture (shader-safe, R3 #15) | A |
| **Connected bezel** | Everyone | 16 frame textures picked by in-plane neighbours on **every** face (§5.3) | C |
| **Laser beam** | Holder only, first person | `RenderWorldLastEvent`: 2-px line, opaque, depth-tested, from the item tip to the hit; dim red when out of reach; red dot under the sprite | C |
| **Session bar** | Local | §4.2 | A |
| **Toasts** | Local | GTNHLib `AboveHotbarHUD.renderTextAboveHotbar(msg, 60, true, true)` (VERIFIED `GL:util/AboveHotbarHUD.java:34`), throttled by the existing `NoticeThrottle` (one per reason per 10 s); HUD caching steps them at 20 FPS (R4 §4.2). `feedback.toastsInChat` mirrors them to chat. Formation and admin results stay in chat | A |

#### 3.9.2 GL recipe on the Tessellator path (default)

| Draw | State |
|---|---|
| Page quad | `glEnable(GL_POLYGON_OFFSET_FILL); glPolygonOffset(-1, -2)` (C-21; R5 SF-1) |
| Placeholder text, badge, loading bar, frame | Offset `(-1, -4)`; opaque quads; text with the vanilla font path |
| Cursor | Offset `(-1, -8)`, depth test on, `glDepthMask(false)`, alpha test `GL_GREATER 0.5`, blend off (keeps Iris away from the translucent program; R5 §5.3) |
| `finally` | Restore polygon-offset enable, alpha func and ref, depth mask and colour explicitly; no `glPushAttrib` |

The shadow-pass early return stays (VERIFIED `AW:client/render/ScreenRenderer.java:64`). Card text is wrapped only on change (R3 §6). The Angelica TESR path is Phase D, opt-in (ADR-017 item 5).

#### 3.9.3 Sounds (vanilla names only; local; master × category volume; lead defaults)

| Event | Sound | Volume / pitch | Pref | Default |
|---|---|---|---|---|
| Page press | `random.click` (VERIFIED `MC:block/BlockButton.java:250`) | 0.12 / 1.6 | `audio.clickSound` | On |
| Typing in a session | `random.click` | 0.06 / random 1.7-2.0, ≤ 15 per second | `audio.typingSound` | On |
| Denied (busy, watch only, locked) | `random.click` | 0.2 / 0.5 | `audio.deniedSound` | On |
| Session open / close | `random.click` | 0.2 / 1.2 and 0.9 | — | On |

WD's `.ogg` files are not shipped (C-18). Others never hear typing (C-28).

### 3.10 Performance budgets (targets, INFERRED; measured in `/awdoctor input` and the tests)

| Item | Budget |
|---|---|
| Aim + access + pointer session per frame, ≤ 16 tracked screens | ≤ 0.05 ms on the client thread; **0 bytes allocated** (guard test) |
| Block occlusion trace | ≤ 1 per frame, only on a pixel or eye-block change |
| Entity occlusion scan | Press and wheel events only (≤ 20 Hz); ≤ 0.1 ms each |
| Session extra per frame (GrabArbiter + LookDriver + MUI2 bar) | ≤ 0.2 ms; bar ≤ 12 widgets; no IPC or allocation in `IKey.dynamic` suppliers (R1 §4.6) |
| Host INPUT | `mouseMove` ≤ 1 per frame and ≤ 60/s per screen. **Coalescing (RA-8):** in M2's writer queue a new MOVE replaces the previous MOVE for that screen only when that MOVE is the last queued INPUT for the screen; a MOVE before a DOWN, between DOWN and UP, or before any other input is never dropped or reordered. ≤ 2 messages per key event. Writer-queue high-water ≤ 256 in S24 (the queue holds 4096 and closes the connection when full, VERIFIED R3 §1.1) |
| `C2SControl` | Take ≤ 1 per second (server limit 1/s, burst 2); keepalive ≤ 1 per 5 s |
| Video fit | Host `SYNC` with `fullscreen` only on a change, re-sent ≤ 1/s while the follower's own sample disagrees |
| TESR extras per aimed or controlled screen | ≤ 7 quads + 1 cached string; ≤ 0.05 ms |
| VRAM | Cursor atlas 512 KiB; GUI icons 64 KiB; once |
| GUI open | ≤ 1 tick after the gesture (deferred queue) |
| Latency | Press or key → host ≤ 50 ms of tick quantisation + about 20 ms host path (M2 Linux AUTOMATED figure, not re-measured). Sprite ≤ 2 frames behind the camera |

### 3.11 Accessibility (pt-BR first)

- **Language.** Every string is a lang key, pt_BR and en_US with parity (`LangFilesTest`, existing). `%s` only where an argument is always passed. No emoji or non-BMP glyphs in text; icons come from the atlas.
- **Vanilla vocabulary.** Action and key names in hints are resolved live from vanilla (`key.sneak` = "Esgueirar", `soundCategory.record` = "Blocos Musicais"; VERIFIED `MCA:` lines 856, 1206) and from the player's bindings, so they match the Controls screen and survive rebinding (RB-17).
- **Never colour alone.** Access is shown by cursor shape, glyph and text; cyan and amber, never a red/green pair.
- **Readable GUIs.** Text at scale 1.0; every panel fits 320×240 GUI units (R1 §4.2); every icon button has a tooltip with its shortcut; below 360 units of width, icon buttons gain labels.
- **Keyboard-only GUIs.** Enter submits; Esc closes the top panel in one press; ↑/↓ and Tab work in the URL history; our own focus order list backs `focusNext` (behaviour unverified, R1 §4.4).
- **Motion.** `render.reduceMotion` turns off the power-on effect, the camera ease, the loading sweep and the highlight fade.
- **Cursor.** `cursor.scale` 0.5-2.0; `cursor.highContrast` (yellow with a black outline).
- **Typing trap.** The bar always says that keys go to the page and how to leave; the first-use notice explains it once.
- **ABNT2 is first-class.** The Doctor's key tester shows what the game received (§4.6).

### 3.12 Alternatives, consequences, confirm by, fallback

**Alternatives considered:**
- **M's monitor-first model** and the CURSOR style in wave 3: not WD dynamics (D-017); a second precision path beside the drawer (RB-16). CURSOR stays a post-event candidate; its research is in R5.
- **H's `inGameHasFocus = true`:** leaks a global flag and needs manual focus handling (C-02).
- **F's crosshair at vanilla reach:** a regression from wave 2 (C-03).
- **A global `ReachRule` for every big screen with an empty hand** (H): a modified client could claim it; LASER grants check the held item on the server (C-06).
- **`C2SControlRequest`:** new C2S surface; deferred (C-08).
- **A mixin into `runTick`'s keyboard loop** for typing without a GUI: ADR-017 rejects our own mixins.
- **Controller fullscreen relayed as input** (WD replays clicks): against D-016. A server-owned presentation bit does the job (C-39).

**Consequences:**
- ADR-012 is amended: control grants, keepalive geometry (LASER re-check), URL reach, peripheral sessions, `ControlLock` FAR by grant, and video fit as part of the media state.
- PROTOCOL §1 and §2 change additively (§6), including the minor-gated C2S rule.
- `ScreenInteraction`, `KeyboardGui`, `AddressBarGui` and `ConsentGui` are retired after their replacements pass (ADR-017).

**Confirm by:**

| Grade | Check |
|---|---|
| AUTOMATED (Linux) | §8.1 unit tables; §8.2 host facts; §8.3 bot scenarios |
| LIVE-OBSERVED (G3-A, owner, Windows) | §8.4 G3-A list: ABNT2 rows, LOOK feel at 30/60/144 fps, video fit on the telão, Alt+Tab, shaders |
| LIVE-APPROVED | The owner accepts the gestures and the default keys |

**Fallback:**
- LOOK under ModularUI2 misbehaves → `SessionController` drives a vanilla `GuiScreen` view.
- Control grants misbehave → `controlGrants = false` (DIRECT only, today's behaviour).
- Follower fullscreen fails on a site → the CSS fit path; if both fail, the follower keeps the page layout (today's behaviour) and the Doctor records it.
- Auto session misfires → `input.autoTypeOnTextField = false`.

---

## 4. GUI catalogue (ModularUI2 2.3.85)

### 4.0 Common rules (package `com.akashiic.web.client.ui`)

| Concern | Rule | Grade |
|---|---|---|
| Screen type | Client-only `ModularScreen` / `CustomModularScreen`, new screen and panels on every open; `ClientGUI.open` only through `UiOpen.later(Supplier<ModularScreen>)` on `ClientTaskQueue`, de-duplicated per screen and kind; `openParentOnClose(false)` (default) | VERIFIED R1 §2.1, §3.1; `MUI:screen/ModularScreen.java:88` |
| Never | Synced panels, `IGuiHolder`, `IMuiScreen`, NEA, lwjgl3ify types (jar guard) | VERIFIED R1 §2.13-2.14 |
| Pause and dim | `pausesGame(false)`, `drawDarkBackground(false)` everywhere. ModularUI2 2.3.85 never draws the vanilla gradient for a client-only screen (only `drawContainer` calls `drawDefaultBackground`, VERIFIED `MUI:screen/ClientScreenHandler.java:471`), so ConsentScreen dims with its own full-screen rectangle (§4.5) | VERIFIED (RA-14) |
| Theme `akashicweb:flat_dark` | `assets/akashicweb/themes.json` + `themes/flat_dark.json` (MUI2-UNVERIFIED-6); fallback `IThemeApi.get().registerTheme`, then per-widget `Rectangle` backgrounds. AkashicFM palette (VERIFIED `AFM:client/gui/FlatButton.java:10-16`, `GuiIPod.java:901,920,937`): panel `0xF0181C24`, button `0xFF2A2F36`, hover `0xFF3A4452`, disabled `0xFF1C1F24`, border `0xFF4A5260`, text `0xFFE0E0E0`, text hover `0xFFFFFFA0`, text disabled `0xFF707070`, accent `0xFF6FB0FF`, dark accent `0xFF3A6A99`, selection `0x603A7BD5`, amber `0xFFFFD070`, error `0xFFFF8070`, ok `0xFF70FF80` | INFERRED design |
| Icons | `textures/gui/icons.png`, 16×16 cells, own art generated by `tools/gen-gui-icons.py`, `UITexture.builder()…nonOpaque()`: back, forward, reload, stop, home, keyboard, preview, gear, help, close, lock, https, eye, crown, chat, trash, plus, minus, copy, refresh, warning, mic | INFERRED |
| Icon button | `IconButton` = `ButtonWidget` + icon + lang tooltip with the shortcut; disabled look = `MC_BUTTON_DISABLED`-style background and presses ignored (MUI2 has no greyed state) | VERIFIED R1 §2.3 |
| Size | Every panel fits **320×240 GUI units** (1280×720 at Auto = 426×240) | VERIFIED R1 §4.2 |
| Text fields | `UrlField` / `NameField` (R1 A.4, V-BUILD): Enter submits; key-0 chars inserted on release; pattern `[^\p{Cntrl}\s§]*`; multi-line paste joined; bound setters never throw (GTNH #27170) | VERIFIED R1 §4.4 |
| Esc | Closes the top panel in **one** press (focused field included): our hook cancels Esc and calls `closeIfOpen` on the top panel, because MUI2's own Esc closes everything | VERIFIED R1 §4.5 |
| Logic | Each screen binds to a pure model in `client.ui.model` (`AddressBarModel`, `SettingsModel`, `SessionHud`, `DoctorModel`, `ConsentController.View`), unit-tested without MUI2 | INFERRED |
| Text | Lang keys only; `%s` only with an argument; no emoji / non-BMP; vanilla action and key names resolved live (`KeyHint`, §3.7.5) | §3.11 |

`KeyHint` (pure, `client.input`): `KeyHint.of(binding)` returns, for a default mouse binding, our words (`akashicweb.hud.button.left/right/middle`), else `GameSettings.getKeyDisplayString(code)`; `KeyHint.combo(action1, action2)` formats `akashicweb.hud.combo` ("%s + %s") with the vanilla action name for sneak (`I18n.format("key.sneak")`) and `KeyHint.of` for buttons.

The sketches use `[icon]` for atlas icons, GUI units and pt-BR labels.

### 4.1 HUD (not MUI2): toasts and hints

| Key | en_US | pt_BR |
|---|---|---|
| `akashicweb.hud.watch_only` | You can only watch this screen | Só para assistir: você não controla esta tela |
| `akashicweb.hud.locked` | This screen is locked | Tela trancada |
| `akashicweb.hud.controlled_by` | Controlled by %s | Controlado por %s |
| `akashicweb.hud.you_control` | You control this screen | Você controla esta tela |
| `akashicweb.hud.take_hint` | Click to use the page | Clique para usar a página |
| `akashicweb.hud.sneak_to_break` | %s to break the screen | %s para quebrar a tela |
| `akashicweb.hud.look_at_screen` | Look at the screen to take control | Olhe para a tela para assumir o controle |
| `akashicweb.hud.too_far` | Move closer to the screen | Chegue mais perto da tela |
| `akashicweb.hud.control_taken_by` | %s took control | %s assumiu o controle |
| `akashicweb.hud.control_lost` | You no longer control this screen | Você não controla mais esta tela |
| `akashicweb.hud.take_refused` | Could not take control of this screen | Não foi possível assumir o controle desta tela |
| `akashicweb.hud.aim_first` | Aim at a screen first | Mire numa tela primeiro |
| `akashicweb.hud.offline` | Browser unavailable: see the screen | Navegador indisponível: veja a tela |
| `akashicweb.hud.first_aim` | Click: use the page · %s: address bar | Clique: usar a página · %s: barra de endereço |
| `akashicweb.hud.first_aim_type` | %s: type | %s: digitar |
| `akashicweb.hud.nav_for_all` | The page changes for everyone watching this screen | A página muda para todos que assistem esta tela |
| `akashicweb.hud.key_assigned` | Key to type on screens: %s (change it in Controls) | Tecla para digitar na tela: %s (mude em Controles) |
| `akashicweb.hud.key_unassigned` | Pick a key for "Type on the screen" in Controls | Escolha uma tecla para "Digitar na tela" em Controles |
| `akashicweb.hud.ptt_types` | The talk key (%s) also types here | A tecla de falar (%s) também digita aqui |
| `akashicweb.hud.keyboard_linked` | Keyboard linked to the screen at %s | Teclado ligado à tela em %s |
| `akashicweb.hud.keyboard_not_linked` | This keyboard is not linked: %s while aiming at a screen | Este teclado não está ligado: %s mirando numa tela |
| `akashicweb.hud.keyboard_screen_gone` | This keyboard's screen is gone | A tela deste teclado não existe mais |
| `akashicweb.hud.laser_out_of_reach` | Out of laser reach | Fora do alcance do laser |
| `akashicweb.hud.combo` | %s + %s | %s + %s |
| `akashicweb.hud.button.left` / `.right` / `.middle` | left button / right button / middle button | botão esquerdo / botão direito / botão do meio |
| `akashicweb.hud.no_key` | (no key) | (sem tecla) |
| `akashicweb.msg.formed` (changed text, still two arguments) | Screen formed: %sx%s blocks. Click to use it; help is the ? button in the address bar. | Tela montada: %sx%s blocos. Clique para usar; a ajuda está no botão ? da barra de endereço. |

- `first_aim` is shown once, only on TAKE or DRIVE, with `combo(sneak, use)`; `first_aim_type` is appended (" · ") only when "Digitar na tela" is bound. A watch-only player gets `watch_only` instead (RB-17).
- The server sends `akashicweb.msg.formed` as a `ChatComponentTranslation` with exactly two arguments (VERIFIED `AW:server/ScreenLifecycle.java:206`, `AW:server/ScreenSync.java:100-103`); it names no key because the server cannot know the player's bindings.

### 4.2 G1 `SessionScreen`: the typing overlay

Purpose: capture every key for the page while the world, the screen and the camera stay live (§3.8). Opened by: auto session on a text field, binding "Digitar na tela", address bar "Digitar", Keyboard block, return from Ctrl+L / Ctrl+T.

```
y=0 ┌──────────────────────────────────────────────────────────────────────────────────────┐ full width, h=14
    │[kbd] Digitando em: Lofi Girl – rádio ao vivo…              [Shift][Ctrl][Alt][AltGr]  │ bg argb(0,0,0,160)
    │      Esc sai · Shift+Esc: Esc na página · Ctrl+L: endereço · Ctrl+T: chat           │ (2nd line at width < 360)
    └──────────────────────────────────────────────────────────────────────────────────────┘
                  (world and in-world page fully visible; page cursor drawn in the world)
```

| Widget | MUI2 | Binding |
|---|---|---|
| Bar | `Flow.row().widthRel(1f).height(14).top(0).background(new Rectangle().color(Color.argb(0,0,0,160)))` (VERIFIED `Color.argb(r,g,b,a)`, R1 §2.6) | — |
| Title | `ScrollingTextWidget(IKey.dynamic(hud::title))` | `SessionHud.title` |
| Hint / status line | `TextWidget(IKey.dynamic(hud::hint))`, amber when a status replaces it | `SessionHud.hint`: pause reasons, "Chegue mais perto", idle warning, "Assumindo o controle…", "Colado: N caracteres" (1 s), PTT hint |
| Modifier chips | 4 × `TextWidget` with dynamic background (Shift, Ctrl, Alt, AltGr; no Caps: LWJGL 2 gives key state, not lock state, RB-21) | `ModifierTracker` |

| Key | en_US | pt_BR |
|---|---|---|
| `akashicweb.gui.session.typing` | Typing on: %s | Digitando em: %s |
| `akashicweb.gui.session.hint` | Esc exits · Shift+Esc: Esc to the page · Ctrl+L: address · Ctrl+T: chat | Esc sai · Shift+Esc: Esc na página · Ctrl+L: endereço · Ctrl+T: chat |
| `akashicweb.gui.session.taking` | Taking control… | Assumindo o controle… |
| `akashicweb.gui.session.unfocused` | Window not focused: click to continue | Janela sem foco: clique para continuar |
| `akashicweb.gui.session.idle` | Still there? Move the crosshair on the screen, click or type to keep control | Ainda está aí? Mexa a mira na tela, clique ou digite para manter o controle |
| `akashicweb.gui.session.pasted` | Pasted: %s characters | Colado: %s caracteres |
| `akashicweb.gui.session.notice` | Keys go only to your own browser, not to the game server. But addresses and searches you open are seen by everyone watching this screen. Shared screens keep no login. | As teclas vão só para o seu navegador, não para o servidor do jogo. Mas endereços e buscas que você abrir aparecem para todos que assistem esta tela. Telas compartilhadas não guardam login. |
| `akashicweb.gui.session.closed_reason` | Typing ended: %s | Digitação encerrada: %s |
| `akashicweb.gui.session.mod.shift` / `.ctrl` / `.alt` / `.altgr` | Shift / Ctrl / Alt / AltGr | Shift / Ctrl / Alt / AltGr |

`akashicweb.gui.keyboard.hint` and `.not_controller` are retired with `KeyboardGui`.

### 4.3 G2 `AddressBarScreen`: browser chrome along the top

Purpose: URL, navigation, control, entry to typing and settings. Opened by: sneak + use (empty hand or laser), binding "Barra de endereço", Ctrl+L in a session. Replaces `AddressBarGui` (wave-2 logic moves to `AddressBarModel`).

```
┌─────────────────────── width = min(screenW − 8, 316), top = 4, not dimmed ───────────────────────┐
│[<][>][R|X][H] [ https://www.youtube.com/watch?v=…                            ][v][ Ir ]           │ h=16
│▔▔▔▔▔▔▔▔▔▔ loading sweep h=2 (indeterminate while PageState.loading) ▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔│
│[https] Lofi Girl – rádio ao vivo…                                   youtube.com                   │ h=12
│ Controlado por: você                                   [ Liberar controle ] / [ Assumir controle ]│ h=14
│[kbd Digitar] [preview Prévia]* [gear Configurar] [? Ajuda]                         [ Fechar ]    │ h=16
│ [warning] Endereço inválido (só http/https)                                                       │ h=10, only when set
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
   history (v, ↓ or typing): ≤ 8 rows × 12, below the field:      * Phase C
   │ youtube.com/@LofiGirl                     Lofi Girl - YouTube │
   │ [trash] Limpar histórico                                      │
   preview drawer (Phase C, "Prévia"): the middle rows fold into one status line and a 300×169 live
   PagePreviewWidget appears (total ≤ 236 units high)
```

| Widget | MUI2 | Behaviour |
|---|---|---|
| Nav buttons | `IconButton` | Enabled from `AddressBarModel`: CHANGE_URL, or controller with CLICK_TYPE (server rule, VERIFIED `AW:server/ActionRules.java:27-34`); back/forward also need `PageState.canGoBack/Forward`; R ↔ X by `loading` ("stop" stays local, VERIFIED `AW:client/screen/ClientScreens.java:730-738`). Back, forward, home and reload show the "muda para todos" toast once per session |
| URL field | `UrlField` (max 2048) | Focused with the URL selected on open; Enter / "Ir" → `UrlInput.normalize` → (take first on a free screen, §3.3.6 item 6) → `C2SSetUrl` → close. Read-only (greyed, tooltip) without the right |
| History | `DropdownWidget<String>` (MUI2-UNVERIFIED-5; fallback `ListWidget` in a secondary panel) | ↑/↓ browse, Tab accepts the first match; prefix/substring over `UrlHistory` (§4.3.1) |
| Loading sweep | Custom 2-unit widget | `PageState.loading` |
| Title / host | `ScrollingTextWidget` + `TextWidget`; [https] icon | `PageState.title` (sanitised), host from URL |
| Controller line | `TextWidget(IKey.dynamic)` + `ButtonWidget` | `LocalRights.controllerDisplayName` (never a UUID prefix when a name exists, VERIFIED `AW:client/screen/LocalRights.java:39-50`); [Assumir] on PREEMPT opens G7; [Liberar] → `leave()` |
| Action row | `IconButton`s | "Digitar" → SessionScreen; "Prévia" toggles the drawer (C); "Configurar" → G3 (any MANAGE_* right, CHANGE_RESOLUTION, owner or admin; otherwise read-only Info tab); "Ajuda" → G6 |
| Error line | `TextWidget`, error colour | `invalid_url`, `blocked`, server notices for this screen within 5 s |
| Preview (C) | `PagePreviewWidget` (R1 A.5, V-BUILD; MUI2-UNVERIFIED-11) | §3.5.3 |

Narrow mode (< 360 units): labels collapse to icons. One Esc closes; opened from a session, closing opens a fresh session.

#### 4.3.1 URL history (pure `client.ui.model.UrlHistory`)

Local only, `<mcDataDir>/akashicweb/url-history.json`, 50 entries, newest first, de-duplicated, never sent anywhere. Before storing: drop credentials and the fragment; replace the value of any query key matching `(?i)token|key|auth|sess|code|sig|pass` with `…`; skip URLs longer than 2048. Pref `privacy.keepUrlHistory` (default on, lead default); off = nothing written and the file deleted. "Limpar histórico" in the dropdown and in Settings → Som e privacidade.

| Key | en_US | pt_BR |
|---|---|---|
| `akashicweb.gui.address.stop` | Stop | Parar |
| `akashicweb.gui.address.hint` | Type an address (https://…) | Digite um endereço (https://…) |
| `akashicweb.gui.address.history` | History | Histórico |
| `akashicweb.gui.address.history_clear` | Clear history | Limpar histórico |
| `akashicweb.gui.address.type` | Type | Digitar |
| `akashicweb.gui.address.preview` | Preview | Prévia |
| `akashicweb.gui.address.settings` | Configure | Configurar |
| `akashicweb.gui.address.help` | Help | Ajuda |
| `akashicweb.gui.address.blocked` | Site blocked on this server: %s | Site bloqueado neste servidor: %s |
| `akashicweb.gui.address.watch_only` | You can only watch this screen | Você só pode assistir esta tela |
| `akashicweb.gui.address.secure` | Secure connection (https) | Conexão segura (https) |
| `akashicweb.gui.address.tip.back` / `.forward` / `.reload` / `.home` | Back (Alt+←) / Forward (Alt+→) / Reload (Ctrl+R) / Home | Voltar (Alt+←) / Avançar (Alt+→) / Recarregar (Ctrl+R) / Início |
| existing, kept | `gui.address.title, go, back, forward, reload, home, take_control, release_control, close, invalid_url, read_only, controller, nobody, you` | (unchanged) |

### 4.4 G3 `ScreenSettingsScreen`: "Configurar tela"

Purpose: friends, rights, resolution, rotation, local sound, admin actions, info. Opened by the address bar gear. 316×220, centred, not dimmed. Tabs: `PagedWidget` + `PageButton` (BogoSorter pattern, R1 §3.1). Edits go to a **draft**; "Salvar" sends (C-19). Phase B.

```
┌ [gear] Configurar tela · 4×3 em -43, 64, 236 (Norte) · dono: Akashiic ──────────────[X]┐
│ [ Acesso ] [ Tela ] [ Som ] [ Admin ]* [ Info ]                    * admins only         │
├──────────────────────────────────────────────────────────────────────────────────────────┤
│ Amigos (3/32)                     │ Direitos                    Amigos    Visitantes    │
│ ┌───────────────────────────────┐ │ Mudar endereço                [x]        [ ]        │
│ │ Fulano                    [-] │ │ Clicar e digitar              [x]        [ ]        │
│ │ Ciclana                   [-] │ │ Gerenciar amigos              [ ]         —         │
│ └───────────────────────────────┘ │ Gerenciar direitos            [ ]         —         │
│ [ nome do jogador        ][ + ]   │ Resolução e rotação           [ ]        [ ]        │
│  Tab completa com quem está online│                                                     │
├──────────────────────────────────────────────────────────────────────────────────────────┤
│ * Alterações não salvas                                     [ Desfazer ]  [ Salvar ]     │
│ ([warning] Alterado por outra pessoa — [ Recarregar ], when the server changed a dirty draft)│
└──────────────────────────────────────────────────────────────────────────────────────────┘
Tela:  Tamanho 4×3 blocos · Página atual 1280×720
       Resolução: (o) Padrão (1280×720) ( ) 480p ( ) 720p ( ) 1080p ( ) Outra [1280]×[ 720] [x] manter proporção
       Rotação: [ 0° > ]        Modo: Compartilhada   (Particular: depois do evento)
       [warning] A página recarrega para todos ao aplicar.                          [ Aplicar ]
Som e privacidade:
       Volume desta tela (só para você)  [-] [=========|-----] [+] 70%   [ ] Silenciar esta tela
       Todas as telas: 100%   [ Preferências… ]       Segue o volume "Blocos Musicais" do Minecraft.
       [x] Guardar histórico de endereços neste PC          [ Limpar histórico ]
Admin (hello.admin; the server re-checks):
       [x] Trancar tela (só admins controlam)        [ Assumir controle à força ]  [ Limpar endereço ]
       Os resultados aparecem no chat.   (kill switch: /webadmin kill — never a button, C-20)
Info:  chave, dono, época, controlador, modo, endereço, amigos           [ Copiar ]
```

| Part | Widgets | Rules |
|---|---|---|
| Friends | `ListWidget` rebuilt on model change (MUI2-UNVERIFIED-4), `NameField` (`[A-Za-z0-9_]{0,16}`) + "+" | Needs MANAGE_FRIENDS. Tab completes from `NetHandlerPlayClient.playerInfoList` names (VERIFIED H: `MC:client/network/NetHandlerPlayClient.java:227`) after stripping `§` codes and **keeping only names matching `[A-Za-z0-9_]{1,16}`** (proxy tab plugins put decorated or fake entries there, RA-22) |
| Rights matrix | `ToggleButton` + `GuiTextures.CHECK_BOX` (MUI2-UNVERIFIED-9) | Owner sets friend rights (MANAGE_RIGHTS for others); visitors' MANAGE_* show "—" (`RightsPolicy.grantable`, VERIFIED `AW:common/RightsPolicy.java:90-94`) |
| Save | `ButtonWidget` | Draft → `RightsChange` with UNCHANGED sentinels; friend names in chunks of ≤ 4 per `C2SRights` (PROTOCOL §2) |
| Resolution | Radio `ToggleButton` group + 2 number fields + keep-ratio | Presets filtered by `hello.maxResolutionW/H/Pixels` (C-23); "Padrão" = `hello.defaultResolutionW/H`; "Aplicar" → G7 → `C2SRights resolutionW/H`; `BAD_RESOLUTION` in the error line |
| Rotation | `CycleButtonWidget` 0/90/180/270 (MUI2-UNVERIFIED-7) | Applied with "Aplicar" |
| Sound | `SliderWidget` (no wheel in 2.3.85, R1 §2.3) with −/+ buttons; mute `ToggleButton` | Local only: per-`ScreenKey` gain in `GainModel` for the session; global `audio.screenVolume`. The note names the vanilla category live (`I18n.format("soundCategory.record")`, "Blocos Musicais", VERIFIED `MCA:` line 1206) |
| Admin | `ToggleButton` lock → `C2SRights locked`; buttons send `/webadmin takeover|clear x y z side dim` as chat commands (VERIFIED `AW:server/command/WebAdminCommand.java:37`), each behind G7 | Page button hidden unless `hello.admin` (MUI2-UNVERIFIED-8) |
| Info | `ListWidget` of read-only lines + "Copiar" | No UUIDs beyond the key; URL without query |

| Key | en_US | pt_BR |
|---|---|---|
| `akashicweb.gui.settings.title` | Configure screen | Configurar tela |
| `akashicweb.gui.settings.where` | %s at %s (%s) | %s em %s (%s) |
| `akashicweb.gui.settings.owner` | owner: %s | dono: %s |
| `akashicweb.gui.settings.tab.access` / `.screen` / `.sound` / `.admin` / `.info` | Access / Screen / Sound and privacy / Admin / Info | Acesso / Tela / Som e privacidade / Admin / Info |
| `akashicweb.gui.settings.friends` | Friends (%s) | Amigos (%s) |
| `akashicweb.gui.settings.friend_hint` | player name | nome do jogador |
| `akashicweb.gui.settings.friend_tab_hint` | Tab completes with online players | Tab completa com quem está online |
| `akashicweb.gui.settings.friend_remove` | Remove %s | Remover %s |
| `akashicweb.gui.settings.rights` | Rights | Direitos |
| `akashicweb.gui.settings.col.friends` / `.col.visitors` | Friends / Visitors | Amigos / Visitantes |
| `akashicweb.gui.settings.right.change_url` | Change address | Mudar endereço |
| `akashicweb.gui.settings.right.click_type` | Click and type | Clicar e digitar |
| `akashicweb.gui.settings.right.manage_friends` | Manage friends | Gerenciar amigos |
| `akashicweb.gui.settings.right.manage_rights` | Manage rights | Gerenciar direitos |
| `akashicweb.gui.settings.right.change_resolution` | Resolution and rotation | Resolução e rotação |
| `akashicweb.gui.settings.dirty` | Unsaved changes | Alterações não salvas |
| `akashicweb.gui.settings.save` / `.undo` | Save / Undo | Salvar / Desfazer |
| `akashicweb.gui.settings.saved` | Saved | Salvo |
| `akashicweb.gui.settings.changed_elsewhere` | Changed by someone else | Alterado por outra pessoa |
| `akashicweb.gui.settings.reload` | Reload | Recarregar |
| `akashicweb.gui.settings.read_only` | Read only: you cannot change this | Somente leitura: você não pode mudar isto |
| `akashicweb.gui.settings.size` | Size %s×%s blocks · Current page %s×%s | Tamanho %s×%s blocos · Página atual %s×%s |
| `akashicweb.gui.settings.resolution` | Resolution | Resolução |
| `akashicweb.gui.settings.res_default` | Default (%s) | Padrão (%s) |
| `akashicweb.gui.settings.res_custom` | Other | Outra |
| `akashicweb.gui.settings.keep_ratio` | keep aspect ratio | manter proporção |
| `akashicweb.gui.settings.rotation` | Rotation | Rotação |
| `akashicweb.gui.settings.rotation_value` | %s° | %s° |
| `akashicweb.gui.settings.mode` | Mode | Modo |
| `akashicweb.gui.settings.mode.shared` | Shared | Compartilhada |
| `akashicweb.gui.settings.mode.private_soon` | Private: after the event | Particular: depois do evento |
| `akashicweb.gui.settings.apply` | Apply | Aplicar |
| `akashicweb.gui.settings.reload_warning` | Applying reloads the page for everyone. | A página recarrega para todos ao aplicar. |
| `akashicweb.gui.settings.volume_screen` | This screen's volume (only for you) | Volume desta tela (só para você) |
| `akashicweb.gui.settings.mute_screen` | Mute this screen | Silenciar esta tela |
| `akashicweb.gui.settings.volume_all` | All screens: %s | Todas as telas: %s |
| `akashicweb.gui.settings.prefs` | Preferences… | Preferências… |
| `akashicweb.gui.settings.records_note` | Follows Minecraft's "%s" volume. | Segue o volume "%s" do Minecraft. |
| `akashicweb.gui.settings.keep_history` | Keep address history on this PC | Guardar histórico de endereços neste PC |
| `akashicweb.gui.admin.lock` | Lock screen (only admins control) | Trancar tela (só admins controlam) |
| `akashicweb.gui.admin.takeover` | Take control by force | Assumir controle à força |
| `akashicweb.gui.admin.clear` | Clear address | Limpar endereço |
| `akashicweb.gui.admin.chat_note` | Results appear in chat. | Os resultados aparecem no chat. |
| `akashicweb.gui.info.copy` | Copy | Copiar |
| `akashicweb.gui.info.copied` | Copied | Copiado |
| `akashicweb.gui.info.line` | %s: %s | %s: %s |

### 4.5 G4 `ConsentScreen`: install, progress, failure

Purpose: engine consent, progress and failure (port of wave-2 `ConsentGui`; logic stays in `ConsentController`, VERIFIED `AW:client/gui/ConsentController.java`). 300×184, centred, **dimmed on purpose**: below the consent panel sits a full-screen panel whose only content is a `new Rectangle().color(0xC8101010)` background (about the middle of the vanilla gradient; RA-14). Opened by use on a NEEDS_ENGINE screen and `/awinstall` (existing `ClientServices.consentOpener`).

```
┌ Instalar o navegador das telas? ────────────────────────────────────────┐
│ (title and lines from ConsentController.View, wrapped at 284)            │
│ As telas do AkashicWeb usam um navegador separado: %s, de %s.            │
│ Download de %s MB de %s; ocupa %s MB em disco.                           │
│ Ele roda fora do Minecraft, só neste PC. Para remover, apague a pasta %s.│
│ Sem internet? Coloque o arquivo %s na pasta %s.                          │
│                 [ Instalar ]      [ Agora não ]      [ Detalhes… ]       │
├ progress: Instalando navegador… 42%  [==========--------------] 66/158 MB │
│                                                         [ Cancelar ]     │
├ failure:  [warning] <reason text>                [ Tentar de novo ] [ Reparar ]│
└──────────────────────────────────────────────────────────────────────────┘
```

| Widget | MUI2 | Rule |
|---|---|---|
| Body | `ListWidget` of `TextWidget`s from `View.lines` | Existing `akashicweb.install.consent.*` keys; wording is owner decision P-12 |
| Buttons | `ButtonWidget`s from `View.buttons`; `press()` returns close or rebuild (existing contract) | Esc closes without deciding |
| Progress | `ProgressWidget.progress(DoubleSupplier)` (VERIFIED `MUI:widgets/ProgressWidget.java:170`) bound to new `View.progress` (−1 or 0..1) | Polled in `onUpdate` |

| Key | en_US | pt_BR |
|---|---|---|
| `akashicweb.install.details` | Details… | Detalhes… |
| `akashicweb.install.progress_mb` | %s / %s MB | %s / %s MB |
| `akashicweb.install.retry` | Try again | Tentar de novo |
| `akashicweb.install.done_toast` | Browser installed: click a screen | Navegador instalado: clique numa tela |
| existing, kept | `install.consent.*`, `install.progress`, `install.repair`, `install.cancel`, `install.ready`, `install.import_hint`, `install.download_hint` | (unchanged) |

### 4.6 G5 `DoctorScreen` and `/awdoctor keys`

**Phase A (WP-3): `/awdoctor keys` in chat.** Lists every registered `KeyBinding` (description, category, code, display string) that shares a code with one of ours or with a candidate of `KeyAutoAssign` (K, U, J, H, Y), marks free candidates, and flags a pass-through binding (Akashic Voice PTT) bound to a text key. Hodgepodge fires all bindings on a shared key (R4 §4.1), so a clash is a double action. Lines in English, like `/awdoctor`.

**Phase B (WP-4): `DoctorScreen`**, 316×220, opened by `/awdoctor gui` and Help → "Diagnóstico". Tabs Geral, Telas, Entrada, Teclas.

```
┌ Diagnóstico do AkashicWeb ──────────────────────────────────────────────────────[X]┐
│ [ Geral ] [ Telas ] [ Entrada ] [ Teclas ]                                          │
├─────────────────────────────────────────────────────────────────────────────────────┤
│ Teclas:  Digite aqui: cada tecla mostra o que o jogo recebeu e o que a página recebe.│
│  raw LWJGL                         →  router                                         │
│  key=40 (´) ch=0x00B4 down rep=0   →  (nada: tecla morta)                            │
│  key=0      ch=0x0078 up           →  CHAR "x"                                       │
│  key=16 (Q) ch=0x002F down ctrl alt→  CHAR "/"  (AltGr)                              │
│  … last 12 events                                                                    │
│  Atalhos em conflito: K → "Mapa" (outro mod) [warning]                                │
│  [mic] Akashic Voice: a tecla de falar (V) também digita na página                   │
├─────────────────────────────────────────────────────────────────────────────────────┤
│ [ Copiar relatório ]  [ Atualizar ]                     Relatório sem dados pessoais │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

| Tab | Content (English data lines on purpose; pt-BR chrome) |
|---|---|
| Geral | Session (proto, hello, limits, server `protoMinor`), engine state, host status and versions (`BrowserHost.diagnostics()`), render path, Angelica registration, upload stats, hidden Advanced prefs (read-only) |
| Telas | One row per tracked screen: key, access, fps, controller, page size, grant (if ours), video fit, last failure |
| Entrada | Counters: applied; dropped (not controller / no host / entity in front); optimistic takes, refused; keepalives sent / predicted-refused; MOVE rate; writer-queue high-water; last cursor kind; `textInputSeq`; grab owner; interaction CPU EMA |
| Teclas | `KeyTesterWidget`: `RawInputHooks` in **observe** mode routes raw events through `PreHeldFilter`, `SessionKeys` and `KeyboardRouter` into the tester (the page is never touched) and shows raw event → output for the last 12 events; then the clash list and the PTT check |

`Copiar relatório`: redacted text (no UUIDs, no URL queries, no host or port) to the clipboard.

| Key | en_US | pt_BR |
|---|---|---|
| `akashicweb.gui.doctor.title` | AkashicWeb diagnostics | Diagnóstico do AkashicWeb |
| `akashicweb.gui.doctor.tab.general` / `.screens` / `.input` / `.keys` | General / Screens / Input / Keys | Geral / Telas / Entrada / Teclas |
| `akashicweb.gui.doctor.keytest_hint` | Type here: each key shows what the game received and what the page gets. | Digite aqui: cada tecla mostra o que o jogo recebeu e o que a página recebe. |
| `akashicweb.gui.doctor.clashes` | Conflicting keys: | Atalhos em conflito: |
| `akashicweb.gui.doctor.no_clash` | No conflicts with AkashicWeb keys. | Nenhum conflito com as teclas do AkashicWeb. |
| `akashicweb.gui.doctor.ptt_text_key` | Akashic Voice: the talk key (%s) also types on the page. Use a mouse button or a key that types nothing. | Akashic Voice: a tecla de falar (%s) também digita na página. Use um botão do mouse ou uma tecla que não digita. |
| `akashicweb.gui.doctor.copy` | Copy report | Copiar relatório |
| `akashicweb.gui.doctor.copied` | Report copied (no personal data). | Relatório copiado (sem dados pessoais). |
| `akashicweb.gui.doctor.refresh` | Refresh | Atualizar |
| `akashicweb.gui.doctor.redacted_note` | Report without personal data | Relatório sem dados pessoais |

### 4.7 G6 `HelpDialog` and first-use tips

A 300×200 MUI2 `Dialog` (Phase B), opened by "?" in the address bar; `/web` usage is unchanged. Key and action names are arguments resolved live (`KeyHint`). First-use tips are HUD toasts (`hud.first_aim`, the session notice), seen-flags in `InternalPrefs`.

```
┌ Como usar as telas ─────────────────────────────────────────────────[X]┐
│ [mouse] Clique (botão direito ou botão esquerdo): clicar na página      │
│ [mouse] Segure e mova a mira: arrastar · Rodinha: rolar (após clicar)   │
│ [sneak] Esgueirar + botão direito, mão vazia: barra de endereço         │
│ [sneak] Esgueirar + botão esquerdo: quebrar a tela                      │
│ [kbd]   Clique numa caixa de texto, K ou o bloco Teclado: digitar       │
│ [laser] Ponteiro laser: usar telas grandes de longe                     │
│ Telas compartilhadas: só quem controla clica e digita. Os outros veem o │
│ mesmo endereço, o vídeo e a tela cheia, mas não a digitação nem a       │
│ rolagem. Endereços e buscas aparecem para todos. Esc libera a tela.     │
│ [ Diagnóstico ]                                          [ Entendi ]    │
└─────────────────────────────────────────────────────────────────────────┘
```

| Key | en_US | pt_BR |
|---|---|---|
| `akashicweb.gui.help.title` | How to use the screens | Como usar as telas |
| `akashicweb.gui.help.click` | Click (%s or %s): click the page | Clique (%s ou %s): clicar na página |
| `akashicweb.gui.help.drag` | Hold and move the crosshair: drag · Wheel: scroll (after a click) | Segure e mova a mira: arrastar · Rodinha: rolar (após clicar) |
| `akashicweb.gui.help.address` | %s, empty hand: address bar | %s, mão vazia: barra de endereço |
| `akashicweb.gui.help.break` | %s: break the screen | %s: quebrar a tela |
| `akashicweb.gui.help.type` | Click a text box, %s or the Keyboard block: type (Esc exits, Ctrl+T: chat) | Clique numa caixa de texto, %s ou o bloco Teclado: digitar (Esc sai, Ctrl+T: chat) |
| `akashicweb.gui.help.type_nokey` | Click a text box or use the Keyboard block: type (Esc exits, Ctrl+T: chat) | Clique numa caixa de texto ou use o bloco Teclado: digitar (Esc sai, Ctrl+T: chat) |
| `akashicweb.gui.help.laser` | Laser pointer: use big screens from afar | Ponteiro laser: usar telas grandes de longe |
| `akashicweb.gui.help.etiquette` | Shared screens: only the controller clicks and types. The others see the same address, the video and fullscreen, but not typing or scrolling. Addresses and searches are seen by everyone. Esc frees the screen. Shared screens keep no login. | Telas compartilhadas: só quem controla clica e digita. Os outros veem o mesmo endereço, o vídeo e a tela cheia, mas não a digitação nem a rolagem. Endereços e buscas aparecem para todos. Esc libera a tela. Telas compartilhadas não guardam login. |
| `akashicweb.gui.help.doctor` | Diagnostics | Diagnóstico |
| `akashicweb.gui.help.ok` | Got it | Entendi |

### 4.8 G7 `ConfirmDialog`

One generic yes/no for take-over, apply resolution/rotation, admin take-over and clear, clear history. Inside an open screen: `Dialog<Boolean>` via `IPanelHandler.simple(parent, …, true)` (VERIFIED `MUI:api/IPanelHandler.java:34`) with `setDisablePanelsBelow(false)` (MUI2-UNVERIFIED-16). From the world (PREEMPT click): a standalone 220×80 `ModularScreen`. Phase A (PREEMPT needs it).

```
┌ Assumir o controle? ──────────────────────────────┐
│ Fulano está usando esta tela. Assumir mesmo assim? │
│                         [ Cancelar ]  [ Assumir ]  │
└────────────────────────────────────────────────────┘
```

| Key | en_US | pt_BR |
|---|---|---|
| `akashicweb.gui.confirm.takeover.title` | Take control? | Assumir o controle? |
| `akashicweb.gui.confirm.takeover.body` | %s is using this screen. Take over anyway? | %s está usando esta tela. Assumir mesmo assim? |
| `akashicweb.gui.confirm.takeover.yes` | Take over | Assumir |
| `akashicweb.gui.confirm.apply.title` | Apply resolution and rotation? | Aplicar resolução e rotação? |
| `akashicweb.gui.confirm.apply.body` | The page reloads for everyone watching. | A página recarrega para todos que estão assistindo. |
| `akashicweb.gui.confirm.clear.title` | Clear this screen's address? | Limpar o endereço desta tela? |
| `akashicweb.gui.confirm.history.title` | Clear the address history on this PC? | Limpar o histórico de endereços deste PC? |
| `akashicweb.gui.common.cancel` / `.confirm` / `.close` | Cancel / Confirm / Close | Cancelar / Confirmar / Fechar |

### 4.9 G8 `ContextMenuDialog` (Phase D)

The page's context menu. Opened in a session by the Menu key or Shift+F10 (and by the right button when `input.rightButtonInSession = RIGHT`): the client sends a right `mouseDown`/`mouseUp` at the pointer and the host answers with EVENT `context-menu` (§6.2); generic entries when none arrives within 150 ms. A `Dialog<Void>` child of the session's panel at the window centre (where the crosshair is), clamped to the window (MUI2-UNVERIFIED-12). While it is open, `SessionView.lookActive()` is false, so `GrabArbiter` ungrabs at the next frame and puts the cursor at the centre (C-26); closing re-grabs.

```
┌─────────────────────────────┐ w=128, rows of 11
│ Voltar               Alt+←  │
│ Avançar              Alt+→  │
│ Recarregar           Ctrl+R │
├ (only when editable / selection) ┤
│ Recortar · Copiar · Colar · Selecionar tudo │
├ (only for an http/https link) ┤
│ Abrir link aqui · Copiar endereço do link    │
├─────────────────────────────┤
│ Copiar endereço da página   │
└─────────────────────────────┘
```

| Key | en_US | pt_BR |
|---|---|---|
| `akashicweb.gui.ctx.back` / `.forward` / `.reload` | Back / Forward / Reload | Voltar / Avançar / Recarregar |
| `akashicweb.gui.ctx.cut` / `.copy` / `.paste` / `.select_all` | Cut / Copy / Paste / Select all | Recortar / Copiar / Colar / Selecionar tudo |
| `akashicweb.gui.ctx.open_link` / `.copy_link` / `.copy_page` | Open link here / Copy link address / Copy page address | Abrir link aqui / Copiar endereço do link / Copiar endereço da página |

"Abrir link aqui" goes through `C2SSetUrl` (server policy applies); copy actions use `GuiScreen.setClipboardString`; edit actions use INPUT `edit`.

### 4.10 G9 Client preferences (GTNHLib `SimpleGuiConfig`, not MUI2)

One `@Config(modid = "akashicweb", filename = "akashicweb-client", category = …)` class per category in `client.prefs`, all registered in `ClientProxy.preInit`; a facade `ClientPrefs` hands out immutable snapshots. `WebGuiFactory implements SimpleGuiFactory` (mod list "Config" button and Settings → "Preferências…") builds `SimpleGuiConfig(parent, "akashicweb", "AkashicWeb", InputPrefs.class, CursorPrefs.class, FeedbackPrefs.class, RenderPrefs.class, AudioPrefs.class, PrivacyPrefs.class)`, so `AdvancedPrefs` and `InternalPrefs` (both `@Config.ExcludeFromAutoGui`) never show (RA-19, RB-23). Lang keys follow GTNHLib's `%mod.%cat.%field` pattern, dumped with `-Dgtnhlib.dumpkeys=true` (R4 §1.2) and added to both files.

| Class (category) | Field (default, range) | pt_BR label |
|---|---|---|
| `InputPrefs` (`input`) | `sessionLookSensitivity` (0.6, 0.1-2.0) | Sensibilidade da mira ao digitar |
| | `rightButtonInSession` (`LEFT` \| `RIGHT`, LEFT) | Botão direito ao digitar |
| | `autoTypeOnTextField` (true) | Digitar ao clicar numa caixa de texto |
| | `exitSessionOnDamage` (true) | Sair da digitação ao levar dano |
| | `turnToScreen` (true) | Virar para a tela ao começar |
| | `sideButtons` (`OFF` \| `SESSION` \| `ALWAYS`, SESSION) | Botões laterais do mouse: voltar e avançar |
| `CursorPrefs` (`cursor`) | `scale` (1.0, 0.5-2.0) | Tamanho do cursor |
| | `highContrast` (false) | Cursor de alto contraste |
| `FeedbackPrefs` (`feedback`) | `frameHighlight` (true) | Destacar a borda da tela |
| | `controllerBadge` (`ALWAYS` \| `AIMED` \| `NEVER`, AIMED) | Mostrar quem controla |
| | `toastsInChat` (false) | Avisos também no chat |
| | `firstUseTips` (true) | Dicas de primeiro uso |
| `RenderPrefs` (`render`) | `reduceMotion` (false) | Reduzir animações |
| `AudioPrefs` (`audio`) | `screenVolume` (100, 0-100) | Volume das telas |
| | `muteAllScreens` (false) | Silenciar todas as telas |
| | `clickSound`, `typingSound`, `deniedSound` (true) | Som de clique / de digitação / de recusa |
| `PrivacyPrefs` (`privacy`) | `keepUrlHistory` (true), `historySize` (50, 0-50) | Guardar histórico / Tamanho do histórico |
| `AdvancedPrefs` (`advanced`, hidden; read-only in the Doctor) | `screenPath` (`TESSELLATOR` \| `ANGELICA_LIT` \| `ANGELICA_EMISSIVE`, TESSELLATOR); `wheelPixelsPerNotch` (100, 20-400); `passThroughBindings` (`key.akashicvoice.ptt`); `letterKeyEvents` (true once WP-1 fact (a) passes, else false) | — |
| `InternalPrefs` (`internal`, hidden) | `tipsShown` (bitmask), `typingNoticeSeen`, `keyAutoAssigned` | — |

Read on the client thread; snapshots handed to other threads; edits via `ConfigChangedEvent.OnConfigChangedEvent` for modid `akashicweb`.

### 4.11 Retired and changed lang keys

- Retired with the vanilla GUIs: `akashicweb.gui.keyboard.hint`, `akashicweb.gui.keyboard.not_controller`, `akashicweb.gui.address.keyboard`.
- Changed values: `akashicweb.key.keyboard` = "Digitar na tela" / "Type on the screen"; `akashicweb.msg.formed` (§4.1, same two arguments).
- New key binding: `akashicweb.key.release` = "Liberar controle da tela" / "Release screen control".
- Placeholder: `akashicweb.placeholder.consent_hint` becomes "Clique na tela para instalar" / "Click the screen to install".
- Not created (v1 keys dropped with CURSOR): `gui.session.hint_cursor`, `.lookaround`, `.aim_back`, `.mod.caps`, `gui.address.monitor`, `key.pointer`.

### 4.12 ModularUI2 assumptions still unverified

Each is checked in the dev client in its phase and has a fallback.

| Id | Assumption | Check | Fallback |
|---|---|---|---|
| MUI2-UNVERIFIED-1 | A MUI2 screen behaves with the mouse grabbed by us: no cursor flicker from `resetCursorIcon` (VERIFIED it runs when nothing is hovered, `MUI:screen/viewport/ModularGuiContext.java:405`), no ungrab on resize | S6-S8, S21 | `GrabArbiter` self-heal; vanilla `GuiScreen` view of `SessionController` |
| MUI2-UNVERIFIED-2 | Cancelling every `MouseInputEvent.Pre` leaves no stuck pressed widget | S6, S16 | Cancel only button and wheel events |
| MUI2-UNVERIFIED-4 | `ListWidget` children rebuild live on a model change | S12 | Rebuild the tab page |
| MUI2-UNVERIFIED-5 | `DropdownWidget` suits the URL history | S11 | `ListWidget` in a secondary panel |
| MUI2-UNVERIFIED-6 | `themes.json` from domain `akashicweb` loads with no code | S14 | `IThemeApi.registerTheme`, then per-widget backgrounds |
| MUI2-UNVERIFIED-7 | `CycleButtonWidget` cycles the rotation | S12 | A button cycling a field |
| MUI2-UNVERIFIED-8 | Hiding the Admin `PageButton` keeps `PagedWidget` indices right | S12 | Build the tab list per open |
| MUI2-UNVERIFIED-9 | `ToggleButton` + `CHECK_BOX` reads well at GUI scale 3 | S14 | Text toggles ("sim"/"não") |
| MUI2-UNVERIFIED-11 | `PagePreviewWidget`'s raw GL texture draw works under Angelica's core profile with MUI2 stencil clipping (V-BUILD only, R1 A.5) | G3-C | Drawer disabled under Angelica (Advanced pref) |
| MUI2-UNVERIFIED-12 | `Dialog` at an arbitrary position | Phase D | Children of the session's full-screen panel |
| MUI2-UNVERIFIED-15 | Tooltip draw order (bug fixed in 2.3.89, R1 §4.8) | S14 | Tooltips never overlap neighbours |
| MUI2-UNVERIFIED-16 | A `Dialog` with `setDisablePanelsBelow(false)` draws no dark overlay and leaves panels below usable (R1 §2.10) | S12, S15 | A plain secondary panel styled as a dialog |

---

## 5. Blocks and items

All are M4 content (WP-5), registered in `CommonProxy.preInit`; client hooks through `ClientHooks` defaults (existing pattern, VERIFIED `AW:common/net/ClientHooks.java:59`). FakePlayers are rejected; every server action re-checks rights. Failures reach the player through the existing `ScreenSync.tell(player, ReasonCode, detail)` chat path (VERIFIED `AW:server/ScreenSync.java:91-97`) with existing codes.

### 5.1 Teclado da tela `akashicweb:keyboard` (`BlockKeyboard`, `ItemBlockKeyboard`, `TileEntityKeyboard`), Phase C

| Aspect | Spec |
|---|---|
| Shape | One block, a 1/16 slab like the carpet (`setBlockBounds(0,0,0,1,0.0625F,1)`; `canBlockStay` needs a non-air block below; drops itself when support goes: VERIFIED pattern `MC:block/BlockCarpet.java:24,93-124`). Not opaque, not a normal block |
| Facing | Metadata 0-3 from the placer's yaw; the keys face the placer |
| Render | Plain block renderer; `getIcon(side, meta)`: top = one of 4 pre-rotated icons (`keyboard_top_n/e/s/w`), sides = `keyboard_side`, bottom = stone slab. `registerBlockIcons` is `@SideOnly(CLIENT)` (guard rule, §2 item 7). No ISBRH, no TESR (C-17) |
| **Link by aiming (C-35, RB-3)** | `ItemBlockKeyboard` (extends `ItemBlock`, like the existing `ItemBlockScreen`, VERIFIED `AW:common/block/ItemBlockScreen.java`):<br>- **Use on a screen block** within vanilla reach (sneaking or not): `onItemUse` on a `BlockScreen` links to that screen and returns true, so nothing is placed on the screen.<br>- **Sneak + use aimed at a far screen**: vanilla sends the use-item packet because no block is within reach, so the server's `onItemRightClick` runs; pure `KeyboardLink.pickByRay(screens, eye, look, peripheralRange)` returns the nearest formed face whose picture the look ray hits within `peripheralRange`, with a block line-of-sight check.<br>- Both need `CLICK_TYPE` or `CHANGE_URL` on the screen. The `ScreenKey` goes into the stack NBT (`akwLink`). Failures: `TOO_FAR`, `OUT_OF_VIEW`, `NO_PERMISSION`, `UNKNOWN_SCREEN` via `ScreenSync.tell`.<br>- Success is seen by the client when the held stack's NBT changes through vanilla's slot sync; it toasts `hud.keyboard_linked`. No new packet |
| Placement | `onBlockPlacedBy` (server) uses the stack's link when the screen still exists, is in the same dimension, the keyboard's centre is within `peripheralRange` of the face, and the placer has the rights. Otherwise `KeyboardLink.pickNearest(...)`: among formed faces within `peripheralRange` whose front faces the keyboard, the one with the smallest angle between the placer's look and the direction to the face centre. Otherwise unlinked (toast `hud.keyboard_not_linked` with `combo(sneak, use)`). The link is stored in `TileEntityKeyboard` (NBT + description packet), re-validated on every use (CinemaMod#108 lesson, R2 U13), and cleared when the screen dissolves |
| Drops | The dropped item carries the TE's link in its NBT, so a moved keyboard stays linked (INFERRED pattern: copy in `removedByPlayer` before the TE is removed) |
| Right-click | Server: peripheral session (§3.3.4). Client: `clientHooks().onPeripheralActivated(KEYBOARD, x, y, z, linkedKey, sneaking)` → if not sneaking, linked and the screen known, a session for it (deferred, de-duplicated, I5). Holding the button re-activates every 4 ticks; the de-duplication keeps one open |
| Sneak + right-click (empty hand) | Toast with the link status (`hud.keyboard_linked` / `_not_linked` / `_screen_gone`) |
| Not ported | WD's "cat on keyboard" typing (griefing, R2 §2.4); LEDs (C-36) |
| Recipe (shaped, vanilla; owner question P-17) | `BBB` / `SRS`: B = `Blocks.stone_button`, S = `Blocks.stone_slab` (meta 0), R = `Items.redstone` → 1 |
| Lang | `tile.akashicweb.keyboard.name` = Teclado da tela / Screen Keyboard. Tooltip lines (live key names via `KeyHint`): `tile.akashicweb.keyboard.tooltip.link` = "%s mirando numa tela: ligar" / "%s while aiming at a screen: link"; `tile.akashicweb.keyboard.tooltip.use` = "Coloque e clique com o botão direito para digitar" / "Place it and right-click to type"; `tile.akashicweb.keyboard.tooltip.linked` = "Ligado à tela em %s" / "Linked to the screen at %s" |

### 5.2 Ponteiro laser `akashicweb:laser_pointer` (`ItemLaserPointer`), Phase C

| Aspect | Spec |
|---|---|
| Item | Stack size 1, no durability, no energy. `onItemRightClick` returns the stack untouched |
| Client | While held: aim reach = `ControlReach.laser` (§3.3.2); use and attack = page left; middle = middle when drivable; sneak + use = address bar at range. First-person beam and red dot (§3.9.1). Others see nothing (D-016) |
| Server | `LASER` grant checks the held item at take time and, with a 5 s grace, at keepalive, URL reach and the sweep (§3.3.3, RA-15) |
| Recipe (shaped) | `..G` / `.R.` / `I..`: G = `Items.glowstone_dust`, R = `Items.redstone`, I = `Items.iron_ingot` → 1 |
| Lang | `item.akashicweb.laser_pointer.name` = Ponteiro laser / Laser Pointer; `item.akashicweb.laser_pointer.tooltip` = Clique, arraste e role telas grandes de longe. / Click, drag and scroll big screens from afar. |

### 5.3 Screen bezel: connected frame (existing block, Phase C)

`BlockScreen.getIcon(IBlockAccess, x, y, z, side)` picks one of 16 frame textures on **every** face, as Web Displays does (RA-18). Pure `ConnectedFrame.index(side, neighbours)`: for the face `side`, using `BlockSide` up/right axes, set bit 1 bottom, 2 right, 4 top, 8 left when the in-plane neighbour is **not** a screen block (WD's flag order, VERIFIED `WD:block/BlockScreen.java:58-61,94-99`). It reads only `IBlockAccess` (world snapshot; safe under Angelica's chunk builder, INFERRED); screen blocks keep only the origin bit in metadata and the facing lives in the origin TE (VERIFIED `AW:common/block/BlockScreen.java:35-52`), which must not be read from chunk-build threads, so back faces are not told apart. The item icon keeps `screen.png`.

### 5.4 Textures, credit and provenance

| File (`assets/akashicweb/textures/`) | Source | How |
|---|---|---|
| `blocks/screen_frame_0..15.png` | WD `screen0..15.png` (16×16, committed by montoyo in 2018) | Copied; renamed |
| `blocks/keyboard_top_{n,e,s,w}.png`, `keyboard_side.png` | WD `kb_base.png` + `kb_key.png` (16×16) | Composed and rotated by `tools/gen-block-textures.py` (committed script and outputs) |
| `items/laser_pointer.png` | WD `items/laserpointer.png` (16×16) | Copied |
| `gui/icons.png` | Own | `tools/gen-gui-icons.py` |
| Cursor atlas | Own, procedural at runtime | No file (R5 §5.5) |

- WD is public domain with a credit request (VERIFIED `/home/user/montoyo/webdisplays/LICENSE:1`). Credit line in a new `NOTICE` and in `mcmod.info` credits: "Screen frame, keyboard and laser pointer textures derived from Web Displays by montoyo (public domain)". `NOTICE` lists each source path and its SHA-256.
- WD `.ogg` sounds are not shipped (origin unknown); vanilla sounds instead.
- Fallback (P-08, P-17): `tools/gen-block-textures.py --own-art` draws flat replacements in the AkashicFM palette.

### 5.5 Post-event (not in wave 3)

Free-cursor CURSOR style (R5 design kept for later), focus zoom, Controle remoto, Configurador, Tablet / minePad, Redstone Controller, OpenComputers interface (needs a D-016-compatible design, R2 U10), keyboard LEDs, two-block WD keyboard, shared scroll anchor and controller cursor for spectators (P-15), "Pedir a vez".

### 5.6 Owner pack checklist (Forbidden)

1. FinalForgeRestrictor and BlockLimiter entries for `akashicweb:keyboard` and `akashicweb:laser_pointer`, including item use (the keyboard links through the use-item path) (research/07).
2. `config/entityculling.json`: add `"tile.akashicweb.screen"` to `blockEntityWhitelist` (belt; the mod also forces visibility).
3. FpsReducer idle setting raised or off for the event (R4 §4.3); Angelica's reducer state in `angelica-options.json`.
4. WorldGuard / GriefPreventionPlus: interaction flags at spawn so visitors cannot open peripheral sessions on staff keyboards (the server rights check holds anyway).
5. `config/modularui2.cfg` unchanged on players' clients (debug off is the 2.3.85 non-dev default, R1 §4.7).
6. Akashic Voice push-to-talk: prefer a mouse button or a non-text key for the event (C-44, P-14).
7. Akashic-World-Firewall: declare AkashicWeb's server classes, including the new item and block (research/07 "Consequences" 5).

---

## 6. Protocol and contract changes (consistent with D-016)

**Nothing new carries page input to the server.** No pointer position, key, "typing" bit or selection text crosses the network. The one new upstream datum is the controller's fullscreen bit, a presentation fact sampled with the media clock (C-39). Both protocols stay `MAJOR = 1`; `MINOR` goes from 0 to 1 (today `Protocol.MINOR = 0`, `PROTO_MINOR = 0`, VERIFIED `AW:protocol/Protocol.java:10-11`, `HOST:protocol.js:8-9`).

### 6.1 Server ↔ client (PROTOCOL §2)

#### 6.1.1 `S2CHello` gains a limits block, appended after `playerUuid`

S2C fields may only be appended; clients skip unknown trailing bytes (VERIFIED `DOC:PROTOCOL.md:128`; reading pattern `AW:common/net/S2CHello.java:64-65`). The hello is already re-sent on policy reload and kill switch (VERIFIED `AW:server/ScreenSync.java:111-123`). Its existing `protoMinor` (always first) tells the client which C2S shapes the server accepts (§6.1.4).

| Field | Wire | Default when absent | Source (server) |
|---|---|---|---|
| `limitsVersion` | u8, = 1 | — | Constant; a client reads the fields it knows |
| `interactRange` | f32 | 24 | `WebSettings.interactRange` (VERIFIED `AW:common/WebSettings.java:155`) |
| `controlMaxAngleDeg` | f32 | 45 | `WebSettings.controlMaxAngleDeg` |
| `controllerIdleSeconds` | u16 | 60 | `WebSettings.controllerIdleSeconds` |
| `peripheralRange` | f32 | 32 | New `WebSettings.peripheralRange` (clamp 4-64) |
| `laserReachPerBlock` | f32 | 1.5 | New (clamp 1-4) |
| `laserMaxReach` | f32 | 64 | New (clamp `interactRange`-128) |
| `maxResolutionW`, `maxResolutionH` | u16, u16 | 1920, 1920 | `WebSettings` (VERIFIED `:149`) |
| `maxResolutionPixels` | u32 | 2,073,600 | `WebSettings` (VERIFIED `:150`) |
| `defaultResolutionW`, `defaultResolutionH` | u16, u16 | 1280, 720 | `WebSettings` (VERIFIED `:148`) |
| `admin` | bool | false | `Admins.isAdmin(player, cfg)`; UX only, the server re-checks every action |

The handler calls the new `ClientHooks.onHelloLimits(HelloLimits)`, with `HelloLimits.DEFAULTS` when the block is absent; it skips the call on a foreign major. With a minor-0 server the client also predicts DIRECT only (`ControlAim`).

#### 6.1.2 Server rules (ADR-012 amendment)

| Packet | Change |
|---|---|
| `C2SControl` take (id 21) | First take: rights and lock, then grants DIRECT → LASER → PERIPHERAL (§3.3.2). Keepalive: the recorded grant's geometry, no look or line-of-sight check; LASER also needs the laser held now or within 5 s (§3.3.3). `ControlLock` FAR uses the grants. The grant lives in `ScreenSession` (server memory, never sent) |
| `C2SSetUrl` (16), `C2SNav` (17), `C2SRights` (22) | Reach = `interactRange`, **or** a LASER grant (controller, laser held or seen within 5 s) within laser reach, **or** a valid peripheral session (§3.3.5). Rights and epoch unchanged |
| `C2SMediaSample` (20) | Optional trailing `fullscreen` byte (§6.1.4) → `ScreenSession.fit` (sticky; reset on a new epoch) |
| `S2CMediaClock` (4) | Appended `fit` byte (u8 0/1) |
| Vanilla block activation on `akashicweb:keyboard` | Opens or renews a peripheral session (§3.3.4) |
| Vanilla use item / use on block with `ItemBlockKeyboard` | Links the item (§5.1) |
| `BreakEvent` refusal | `ReasonCode.SCREEN_PROTECTED` with the owner name, replacing `NO_PERMISSION` (VERIFIED today `AW:server/ServerEvents.java:86`) |

New `WebSettings` / `WebConfig` keys, category `interaction`, fail-closed parsing kept: `peripheralRange` (32, clamp 4-64); `laserReachPerBlock` (1.5, clamp 1-4); `laserMaxReach` (64); `controlGrants` (true; false = DIRECT only, today's behaviour).

#### 6.1.3 Reason codes

Appended after W3-0's `URL_DNS_VIA_PROXY` and `POLICY_INVALID` (VERIFIED `AW:common/ReasonCode.java:107,109`); no name collides.

| Code | en_US | pt_BR |
|---|---|---|
| `SCREEN_PROTECTED` | This screen is protected: only its owner (%s) or an admin can break it. | Esta tela é protegida: só o dono (%s) ou um admin pode quebrá-la. |

Client-only messages for watch-only, busy, locked and take-refused screens are HUD toasts (§4.1), not reason codes.

#### 6.1.4 Evolving a C2S packet within a major (C-46)

PROTOCOL §2 "Forward compatibility" gains one rule: *a C2S packet may gain **one** optional trailing field in a new minor. The client sends it only when the server's `S2CHello.protoMinor` is at least that minor; the server decodes both shapes exactly (no other trailing length is accepted).* `AkwMessage`'s exact decoding stays (VERIFIED `AW:common/net/AkwMessage.java:25-27`); `C2SMediaSample.read` reads one more byte only when exactly one byte remains and it is 0 or 1. Minor 1 uses it once:

| Packet | Minor | Field | Meaning |
|---|---|---|---|
| `C2SMediaSample` (20) | 1 | `fullscreen` (u8 0/1) | The controller's page is in HTML fullscreen |

Mixed versions: a minor-0 client never sends it and ignores `S2CMediaClock.fit` and the limits block; a minor-1 client facing a minor-0 server sends the old shape and gets defaults. S29 checks both ways.

### 6.2 Game ↔ host (PROTOCOL §1)

An unknown INPUT `type` is rejected with `bad-type` today (VERIFIED `HOST:input.js:183-184`). The host ships inside the mod jar (ADR-011), so Java and host match; as a belt, Java sends the new types and fields only when the host's HELLO says `protoMinor ≥ 1`.

| Change | Payload | Host behaviour | Phase |
|---|---|---|---|
| INPUT `mouseEnter` / `mouseLeave` | `{type, x, y}` | `sendInputEvent({type:'mouseEnter'\|'mouseLeave', x, y})` (Electron 44 `MouseInputEvent.type`, F6). `mouseLeave` also clears `InputMapper.pressed` (VERIFIED `HOST:input.js:126,135-137`). Whether `:hover` clears: WP-1 fact (b) | A |
| INPUT `releaseAll` | `{type:"releaseAll"}` | `mouseUp` at the last position for each pressed button, then reset (today the pressed set resets only on `did-navigate`, VERIFIED `HOST:screens.js:220-222`) | A |
| INPUT `edit` | `{type:"edit", op:"copy"\|"cut"\|"selectAll"\|"undo"\|"redo"}` | `wc.focus()` then the `WebContents` method (F6: `electron.d.ts`). Paste stays CHAR | A |
| INPUT modifiers on `mouseDown` / `mouseUp` / `mouseMove` / `wheel` | `modifiers:[...]` | Already accepted when present (VERIFIED `HOST:input.js:144-169`); Java starts sending them | A |
| STATE `cursor` | Already sent (VERIFIED `HOST:screens.js:238-241`) | Java parses it into `PageState.cursor`, sanitised to `[a-z-]{1,32}` | A |
| STATE `textInput` (bool), `textInputSeq` (u32) | New fields | §3.8.3: `textInput` follows `focusin`/`focusout` of editable elements in every frame (the preload runs in iframes, VERIFIED `HOST:screens.js:84`); `textInputSeq` increments on a trusted `pointerup` that lands on or focuses an editable element; coalesced at 100 ms | A |
| SYNC `fullscreen` (bool) | Added to the existing SYNC JSON (type 41) | §3.3.9 step 6: fullscreen the main video with `executeJavaScript(code, true)` in the frame that holds it (`webFrameMain` for sub-frames), else the CSS fit; exit only a fullscreen the host entered | A |
| EVENT `context-menu` | `{x, y, link, mediaType, editable, hasSelection, canCut, canCopy, canPaste, canSelectAll}` | From `wc.on('context-menu', params)`; `link` http/https only, ≤ 2048 chars, else `""`; **no `selectionText`**; at most one per right `mouseUp` | D |
| Media-document fit (no wire change) | — | When the main document is Chromium's media document (`document.contentType` starts with `video/` or `audio/`), the preload injects `video{width:100vw;height:100vh;object-fit:contain;background:#000}` (wave-2 finding 3) | A |
| **Test-only echo alias (no wire change; C-45)** | — | Only when unpackaged with `--akw-test-allow-file` (VERIFIED gate `HOST:main.js:93`): before its navigation check, the host rewrites a URL whose origin is exactly `https://akw-echo.test` to `file://<host>/test/pages/<path>` (path normalised, no `..`), and maps that `file:` URL back to the alias in STATE `url`, so Java and the server only ever see the https alias. `file:` is already allowed in this mode (VERIFIED `HOST:policy.js:179,209`); nothing else changes, no protocol handler is registered, and real sites keep the production network path. Java and the server accept the alias under a BLOCKLIST policy because `.test` is not an internal name on either side (VERIFIED `AW:common/UrlPolicy.java:107-113`, `HOST:policy.js:126,138`). WP-1 asserts a packaged host never rewrites | A |

### 6.3 Java contracts (CONTRACTS.md)

| Contract | Change | Owner |
|---|---|---|
| `common.net.ScreenInput` | `Kind` gains `ENTER`, `LEAVE`, `RELEASE_ALL`, `EDIT`; `ScreenInput.edit(op)` carries the op in `text` (stays in `common.net`, C-27) | M4 (WP-0) |
| `common.net.HelloLimits` (new) | Immutable value class; `DEFAULTS`; read and write helpers used by `S2CHello` | M4 (WP-0) |
| `common.net.MediaSample`, `C2SMediaSample`, `S2CMediaClock` | `fullscreen` in the sample (optional trailing byte, §6.1.4); `fit` appended to the clock | M4 (WP-0 codec), WP-5 server rule |
| `common.net.ClientHooks` | New defaults: `onHelloLimits(HelloLimits)`; `onPeripheralActivated(PeripheralKind kind, int x, int y, int z, ScreenKey linked, boolean sneaking)`; the media-clock hook receives `fit` | M4 (WP-0) |
| `common.ControlReach` (new, pure) | `static double laser(ScreenState s, double interactRange, double perBlock, double max)` | M4 (WP-0) |
| `common.ModContent` | `keyboardBlock`, `keyboardItem`, `keyboardTileClass`, `laserPointer` | M4 (WP-0) |
| `common.ReasonCode` | `SCREEN_PROTECTED` | M4 (WP-0) |
| `client.host.InputEvent` | `Type` gains `MOUSE_ENTER`, `MOUSE_LEAVE`, `RELEASE_ALL`, `EDIT`; modifiers honoured for pointer and wheel; SYNC gains `fullscreen` | M2 (WP-0 types, WP-2 serialisation) |
| `client.input.PointerView` (new) | What the TESR and HUD draw this frame: focus key, page point, cursor kind, pressed, access, session flag, badge visibility | M5 (WP-0 interface) |
| `client.input.SessionView` (new) | What `SessionScreen` shows (title, hint, chips) and the actions it may call; `lookActive()` | M5 (WP-0 interface) |
| `client.input.UiPort` (new) | `openAddressBar`, `openSession`, `openSettings`, `openConsent`, `openDoctor`, `openHelp`, `confirmTakeover`, `openContextMenu`. `client.ui.MuiUiPort` implements it; `client.input.VanillaUiPort` keeps the wave-2 GUIs until WP-4 lands | M5 (WP-0 interface) |
| `client.ui.UiBootstrap` (new, stub in WP-0) | `static UiPort register(ClientServices)`: called once from `ClientProxy` by WP-0; returns `VanillaUiPort` until WP-4 fills it with `MuiUiPort` and `RawInputHooks` registration (RA-20) | WP-0 stub, then WP-4 |
| `client.prefs.*Prefs`, `ClientPrefs` facade, `WebGuiFactory` (stub) | §4.10 | M5 (WP-0), then WP-4 for the GUI factory |
| `client.screen.PageState` | `cursor`, `textInput`, `textInputSeq` | M5 |

### 6.4 CONTRACTS.md ownership and cross-cutting rules

**Ownership rows:**
- **M5** gains `client/input/**`, `client/ui/**`, `client/prefs/**`; `client/sync/**` changes for video fit.
- **M4** gains `common/block/BlockKeyboard*`, `common/block/ItemBlockKeyboard`, `common/block/TileEntityKeyboard`, `common/item/**`, `tools/gen-block-textures.py`, `NOTICE`, block and item textures.
- **M5's UI work** also owns `assets/akashicweb/themes*` and `textures/gui/**`.

**Cross-cutting rules** (enforced by `ArchitectureGuardTest`, §2 item 7):
- ModularUI2 2.3.85 and GTNHLib 0.11.51 are hard dependencies.
- `com/cleanroommc/modularui/` only from `client/ui/**`, not `client/ui/model/**`.
- `com/gtnewhorizons/angelica/` only from `client/render/AngelicaTesrPath*`.
- `common/**` and `server/**` never reference `com/cleanroommc/`, `com/gtnewhorizon/gtnhlib/` or `org/lwjgl/`; `net/minecraft/client/` only as `IIconRegister` inside `@SideOnly(CLIENT)` members of `common/block/**` and `common/item/**`.
- C2S packets evolve only by §6.1.4.

### 6.5 Documents updated in the same commits (CLAUDE.md rule)

- `docs/adr/README.md`: ADR-017 and ADR-018 (§2, §3); amendments to ADR-009 (dependencies, GUIs, the false note at `:761`), ADR-012 (grants, keepalive with the LASER re-check, URL reach, peripheral sessions, video fit) and ADR-005 (cursor in the world, polygon offset, Angelica path opt-in, `isForcedVisible`).
- `docs/PROTOCOL.md` (§6.1, §6.2, the C2S evolution rule) and `docs/CONTRACTS.md` (§6.3, §6.4).
- `docs/DECISIONS.md`: D-017 consequences; the lead defaults of §9.3 as a D-018 entry under D-014; owner questions P-14..P-18 (§9.2).
- `docs/PROJECT_STATE.md`, `docs/PLAN.md` (phases and cut order), `docs/ISSUES_REGISTER.md`, `docs/PT-BR_RESUMO.md`, `CHANGELOG.md` (Unreleased).

---

## 7. Implementation plan: work packages

**Preconditions:**
- W3-0 is merged (`e273183`, VERIFIED `git log`); its reason codes and address-rule alignment do not collide with this spec (§6.1.3, §6.2).
- The Xvfb dev-client harness is restored (task "restore the harness"), with the §8.3 additions.
- Dates do not drive the plan (D-014). Order and cut list do.

**Phases** (every WP delivers its Phase A items first):

| Phase | Content |
|---|---|
| **A, core** (before G3-A) | Dependencies, guards, prefs, stubs; dev runtimes with Hodgepodge. `client.input` core: access, machine, picker on the lifted plane, entity occlusion, pointer session, wheel, click counter v2, `ModifierTracker` (polled OR), `PreHeldFilter`, router v2, `SessionKeys`, `KeyHint`, `KeyAutoAssign`. `WorldPointer` replacing `ScreenInteraction`. `SessionScreen` + `GrabArbiter`/`LookDriver` + `RawInputHooks`. **Auto session on a text field.** MUI2 `AddressBarScreen` (no preview), `ConsentScreen`, `ConfirmDialog`. HUD toasts; Ctrl+T "Conversar"; **minimal `/awdoctor keys`**. Host: enter/leave/releaseAll/edit, modifiers, media fit, echo alias, `textInput`/`textInputSeq`, SYNC `fullscreen`. M2: modifiers, tail-only MOVE coalescing, `protoMinor` gate, dev host seam. Render: cursor sprite, crosshair swap, frame highlight, polygon offsets, `isForcedVisible`. `GainModel` squared. Server: limits block, control grants (DIRECT keepalive), `SCREEN_PROTECTED`, **video fit**. Client: take before SetUrl |
| **B** | Settings, Doctor GUI and key tester, Help, controller badge, loading bar, power-on, URL history, idle warning, BrowserBudget boost, lwjgl3ify text input |
| **C** | Keyboard block with link by aiming and peripheral sessions; Laser Pointer with LASER grant and beam; connected bezel; preview drawer |
| **D** | Context-menu dialog; Angelica TESR path (opt-in) |

**Cut order if time is short** (first cut first): Angelica path → context menu → connected bezel → laser beam visuals → power-on and loading bar → preview drawer → Laser Pointer → Keyboard block and peripheral sessions. Phase A is never cut. Compared with v1, Phase A gained auto session, video fit, `KeyAutoAssign`, `/awdoctor keys` and entity occlusion, and wave 3 lost the CURSOR style (RB-6, RB-2, RB-16; R-11).

### 7.0 WP-0: contract commit (lead; sequential, before the fan-out)

| Item | Files (exact) |
|---|---|
| Build | `dependencies.gradle` (MUI2 `:dev`, GTNHLib `0.11.51:dev`, `compileOnly` Angelica `2.2.20:api`, dev-only Hodgepodge 2.7.189); `gradle.properties` (`forceEnableMixins = true`); `src/main/java/com/akashiic/web/AkashicWeb.java` (`dependencies`, `guiFactory`); `src/main/resources/mcmod.info` (credits) |
| Contracts | `protocol/Protocol.java` (`MINOR = 1`); `common/net/ScreenInput.java`, `common/net/HelloLimits.java`, `common/net/S2CHello.java` (limits codec), `common/net/MediaSample.java`, `common/net/C2SMediaSample.java` (optional trailing byte), `common/net/S2CMediaClock.java` (`fit`), `common/net/ClientHooks.java`, `common/ControlReach.java`, `common/ModContent.java`, `common/ReasonCode.java`; `client/host/InputEvent.java` (types only); `client/input/PointerView.java`, `client/input/SessionView.java`, `client/input/UiPort.java`, `client/input/VanillaUiPort.java`; `client/prefs/*Prefs.java`, `client/prefs/ClientPrefs.java`; **stubs** `client/prefs/WebGuiFactory.java` (working, lists the visible classes) and `client/ui/UiBootstrap.java` (returns `VanillaUiPort`); `client/ClientProxy.java` (registers the prefs and calls `UiBootstrap.register`) |
| Lang | `assets/akashicweb/lang/en_US.lang`, `pt_BR.lang`: **every** key of §4, §5 and §6.1.3 and the GTNHLib pref keys, values final, so later WPs never edit lang files (RA-20) |
| Guards | `src/test/java/com/akashiic/web/ArchitectureGuardTest.java` and `ArchitectureGuardSelfTest.java` (scan `build/classes/java/main`, §2 item 7); copy of AkashicFM's `ModDependencyTest` |
| Docs | ADR-017/018 and amendments, PROTOCOL, CONTRACTS, DECISIONS (D-018 lead defaults, P-14..P-18), PROJECT_STATE, PLAN, ISSUES_REGISTER, PT-BR_RESUMO, CHANGELOG |
| Dev runtime | Dev client and dev Crucible carry UniMixins 0.3.1, GTNHLib 0.11.51, ModularUI2 2.3.85 and Hodgepodge 2.7.189; `config/modularui2.cfg` with `guiDebugMode=false`, `enableTestGuis=false` on both |

**Acceptance (WP-0):**
- `./gradlew build` green with every existing test (656 run, 2 skipped at `b1019e0`, as recorded in `CHANGELOG.md:50`; re-counted at WP-0).
- `ArchitectureGuardTest` + self-test, `ModDependencyTest`, `LangFilesTest`, `HelloLimitsCodecTest`, `MediaSampleCodecTest` pass.
- `runClient` reaches the main menu with the four mods; the mod list "Config" button opens the stub prefs GUI.
- The dev Crucible boots with them (log lines in the evidence file).

### 7.1 Parallel work packages

| WP | Scope | Owns (exact paths; nobody else edits them) | Depends on |
|---|---|---|---|
| **WP-1 Host** (M1) | §6.2: INPUT enter/leave/releaseAll/edit, modifiers, STATE `textInput`/`textInputSeq`, SYNC `fullscreen`, media fit, echo alias + `host/test/pages/input-echo.html` and `video.html`, EVENT `context-menu` (D) | `host/**` (`host/src/protocol.js`: `PROTO_MINOR = 1`) | WP-0 |
| **WP-2 Host client and render** (M2 + M5 render) | M2 serialisation of the new types, pointer modifiers and SYNC `fullscreen`; tail-only MOVE coalescing; HELLO `protoMinor` gate; dev host launch seam (make `HostExecutable.fixed(command, userDataDir)` reachable from `client`, today package-private, VERIFIED `AW:client/host/HostExecutable.java:52-53`; honoured only when `fml.deobfuscatedEnvironment` is true). TESR: polygon offsets, `CursorAtlas`, `CursorKind`, `CursorOverlay`, `FrameHighlight`, white card texture, cached text (A); `ControllerBadge`, `LoadingBar`, `PowerOnEffect` (B); `LaserBeamRenderer` (C); `AngelicaTesrPath` (D) | `client/host/**` (except the WP-0 `InputEvent` types), `client/render/**`, matching tests | WP-0 |
| **WP-3 Interaction core** (M5) | Pure core in `client/input/` (`LocalAccess`, `InteractionMachine`, `InteractionPolicy`, `AimPicker`, `FacePlane`, `AimHit`, `PointerSession`, `ClickCounter` v2, `WheelAccumulator`, `ModifierTracker`, `PreHeldFilter`, `SessionKeys`, `SessionRules`, `ReleasePlan`, `ControlAim`, `KeepaliveSchedule`, `SessionController`, `ScreenInputController`, `KeyHint`, `KeyAutoAssign`) and adapters in `client/input/mc/` (`WorldPointer`, `AimSampler`, `EntityOcclusion`, `GrabArbiter` + `LookDriver`, `PointerHud`, `HighlightEvents`, `KeyAutoAssignHook`); router v2; `ClientScreens` input extraction; `PageState` fields; video fit in `client/sync/` (`MediaSampler` sends the bit, `SyncController` adds `fullscreen` to SYNC); `BrowserBudget` boost; `GainModel` squared; `onHelloLimits` / `onPeripheralActivated`; `/awdoctor keys` and the Doctor `input` lines; dev host seam wiring | `client/input/**`, `client/gui/KeyboardRouter.java`, `KeyMapping.java`, `UrlInput.java`, `ClientNotices.java`, `LangKeys.java`, `DoctorCommand.java`, `client/screen/**` (`ScreenInteraction.java` deleted at the end of Phase A), `client/net/**`, `client/sync/**`, `client/ClientProxy.java` (after WP-0), `client/ClientWiring.java`, `client/ServiceWatcher.java` | WP-0; integrates after WP-2 |
| **WP-4 GUIs** (M5 UI) | Every ModularUI2 screen of §4, `RawInputHooks`, `KeyTesterWidget`, `UrlField`, `NameField`, `PagePreviewWidget` (C), pure models, theme, icons, `MuiUiPort`, the real `UiBootstrap` (registers `RawInputHooks`, returns `MuiUiPort`); retire the vanilla GUIs after S6, S8, S11, S13 and S14 pass | `client/ui/**` (incl. `client/ui/input/**`, `client/ui/model/**`, `client/ui/UiBootstrap.java`), `client/prefs/WebGuiFactory.java`, `client/gui/AddressBarGui.java`, `KeyboardGui.java`, `ConsentGui.java`, `ConsentController.java`, `assets/akashicweb/themes.json`, `assets/akashicweb/themes/**`, `assets/akashicweb/textures/gui/**`, `tools/gen-gui-icons.py` | WP-0; behaviour tests after WP-3 |
| **WP-5 Server and content** (M4) | Limits block values; `ControlGrant`, keepalive with the LASER re-check, `ControlLock` by grant, URL reach (§3.3); video-fit rule in `ScreenSession`; `PeripheralSessions`; `SCREEN_PROTECTED`; `WebSettings`/`WebConfig` keys; `BlockKeyboard`, `ItemBlockKeyboard`, `TileEntityKeyboard`, `KeyboardLink`, `ItemLaserPointer`, recipes; `ConnectedFrame` + `BlockScreen.getIcon`; `TileEntityScreen.isForcedVisible`; textures and `NOTICE` | `common/**` except the WP-0 contract files, `server/**`, `assets/akashicweb/textures/blocks/**`, `assets/akashicweb/textures/items/**`, `tools/gen-block-textures.py`, `NOTICE` | WP-0 |
| **WP-6 QA and evidence** (lead) | Harness (§8.3), scenarios, `docs/evidence/<date>-wave3-interaction.md`, G3-A kit (pt-BR) and later addenda | `docs/evidence/**` (new files only), scratchpad harness (outside the repo) | Merges of WP-1..5 per phase |

**Merge order per phase:** WP-1 → WP-5 → WP-2 → WP-3 → WP-4, then WP-6 runs the scenarios. Each merge runs `./gradlew build` and the host tests. After WP-4's Phase A merge, the lead checks that `UiBootstrap` returns `MuiUiPort` in the dev client (S11).

### 7.2 Acceptance criteria per work package

| WP | Phase A acceptance (must pass to merge) | Later phases |
|---|---|---|
| WP-1 | `node --test` green (216 existing + new). Integration under Xvfb with Electron 44.7.0 records, as AUTOMATED: (a) a lone `keyDown` for `a` types nothing; (b) `mouseLeave` clears `:hover`; (c) `releaseAll` ends a drag; (d) `edit copy` puts the selection on the clipboard; (e) SYNC `fullscreen:true` fullscreens the main video of a page that never had user activation, in the main frame and in a cross-origin iframe, and which path worked (API or CSS); (f) `textInputSeq` increases on a trusted pointer-up in an already focused input and in an iframe input, and not on a script `focus()`; (g) a right `mouseDown`/`mouseUp` raises `context-menu` (recorded either way). Media fit fills a 1280×720 page with a 640×360 MP4. Echo alias: works only unpackaged with the flag; STATE `url` shows the alias; a packaged-mode test asserts no rewrite; a navigation from the echo page to a blocked https URL is still blocked | D: `context-menu` never contains selection text (test asserts the key set) |
| WP-2 | `HostMessagesTest`: modifiers on mouse and wheel; new types; float wheel; SYNC `fullscreen`; nothing new sent to a `protoMinor` 0 host. `WriterCoalescingTest` (RA-8): 10,000 MOVEs with nothing else queued leave one per screen; for random per-screen queues of MOVE, DOWN, UP, WHEEL and KEY, after coalescing there are no two adjacent MOVEs for a screen, the relative order of every non-MOVE input is unchanged, the last MOVE before each DOWN and each UP is kept, and screens never merge. `CursorKindTest` total over Electron 44's cursor list; `CursorAtlasTest` (deterministic hash, hotspots inside cells); `ScreenRendererStateTest`; no per-frame allocation in `CursorOverlay` | B: badge and loading bar draw only when `PointerView` says so. D: `AngelicaTesrPath` never class-loaded without `angelica\|tesr` |
| WP-3 | All §8.1 WP-3 tables green. `WorldPointer` parity with wave 2 (ported `PointerTest`). Zero-allocation guard on the aim path. `GrabArbiterTest`, `LookDriverMathTest`, `RenderTickOrderTest`, `KeyAutoAssignTest`. Router v2 keeps every existing `KeyboardRouterTest` case. In-game on the dev client: S1-S11 (S9 only if WP-1 fact (a) passed), S16, S18, S20-S23, S25, S27, S28 | B: idle warning, Doctor input lines. C: peripheral take (S17), laser aim and re-check (S19), drawer mapping |
| WP-4 | Models unit-tested (§8.1). Every Phase A screen opens and closes at GUI scales 1-4 within 320×240 (S14); one Esc closes; Consent is dimmed (pixel probe, S13). Address bar and Consent replace the vanilla GUIs (S11, S13) behind `UiPort` (fallback still selectable) | B: Settings (S12), Doctor and key tester (S26), Help. C: preview drawer clicks land within ±2 page px |
| WP-5 | `ControlGrantTest` (DIRECT, the `controlGrants` lever), `KeepaliveGeometryTest`, `ControlLockTest` (grant reach), `ActionRulesTest` (URL reach), `HelloLimitsCodecTest`, `MediaSampleCodecTest` (both shapes accepted; any other trailing length malformed), `VideoFitRuleTest` (set by the controller's samples; kept on release; reset on a new epoch; overwritten by a new controller), `SCREEN_PROTECTED` in `ServerEventsTest`, `isForcedVisible` constant. `controlGrants=false` reproduces today's behaviour (property test against the wave-2 rules) | C: `ControlGrantTest` LASER (server-side held item, 5 s grace, reach per size, cap) and PERIPHERAL; `PeripheralSessionsTest` (expiry, renewal, radius, range, cleared on dissolve, logout, dimension); `KeyboardLinkTest` (`pickByRay`, `pickNearest`, rights, range, line of sight); `ConnectedFrameTest` (16 cases + WD bit order); recipes registered; S17, S19, S30 |
| WP-6 | Evidence file with SHA-256 of every screenshot; the Phase A scenarios of §8.3 pass on the Linux dev clients; G3-A kit delivered in pt-BR | B-D scenarios as their phases merge; G3 addenda per phase |

---

## 8. Test plan

Nothing in this section has been run. Linux bot results are AUTOMATED or LIVE-OBSERVED on Linux dev clients only; they never stand for Windows, ABNT2 hardware, the Forbidden pack, Angelica or shaders.

### 8.1 Unit tests per package (JUnit 5, Java 8, no Minecraft or LWJGL in the pure core)

Existing doubles: `FakeHost`, `RecordingServer` (`src/test/java/com/akashiic/web/client/screen/`).

| WP | Test class | Asserts |
|---|---|---|
| WP-3 | `InteractionMachineTest` | Every row of §3.4.2 for every access class; invariants I1-I11 as property tests over random event scripts with an injected clock |
| WP-3 | `InteractionPolicyTest` | Exhaustive table: button × press/release × sneaking × held (empty, laser, item, `ItemBlock`, Keyboard item) × hit (picture, frame) × access × session × focus-regain window × entity in front × button bound to a `KeyBinding` → decision. Includes: wheel and middle on TAKE stay vanilla; sneak + use with an item never opens the address bar; a release whose press vanilla saw is never cancelled (I9) |
| WP-3 | `LocalAccessTest` | Owner, friend, visitor, locked, admin, PRIVATE, busy, pending, timeout; agrees with `RightsPolicy.canInput` / `canTakeControl` given `admin` |
| WP-3 | `AimPickerTest`, `FacePlaneTest`, `EntityOcclusionTest` | 6 faces × 4 rotations against `ScreenGeometry.pageUv` on the lifted plane; picture vs frame; nearest of two; back faces rejected; occlusion distance; reach by source; entity boxes nearer / farther than the hit, `canBeCollidedWith` false ignored; 0 bytes allocated on the aim path over 10,000 iterations (`ThreadMXBean.getThreadAllocatedBytes`) |
| WP-3 | `PointerSessionTest` | Balanced DOWN/UP on every cancel reason; capture clamp; one LEAVE; ≤ 1 MOVE per frame; no MOVE when not driving; `reconcileButtons` sends the missing UP; ported wave-2 `PointerTest` |
| WP-3 | `WheelAccumulatorTest` | 120 / 240 units, 8- and 40-unit hi-res steps, sign, Shift → X, flood cap, remainders |
| WP-3 | `ClickCounterV2Test` | Per button; page-px and window-px slop; 500 ms; triple click; another screen resets |
| WP-3 | `ModifierTrackerTest` | AltGr = LCtrl + RAlt; resync; release; **a same-tick Ctrl-down after a click still marks the click (polled OR)** |
| WP-3 | `KeyboardRouterV2Test` + `LwjglKeyScript` fixtures | Every row of §3.7.4 in Windows and X11 shapes, ABNT2 and US-International; `HeldKeys`; key-ups never emitted for keys not sent down, key-0 text kept (I4); `PreHeldFilter` (held W: no down, repeat or char until release; I11); synthetic focus-loss key-ups emit nothing twice; letters → keyDown + CHAR + keyUp (flag on) and CHAR only (flag off); ç / á / É / Ç CHAR only; Ctrl+C/X → `edit`; screenshot, fullscreen, F5, F6, F7 never to the page; every existing case still passes |
| WP-3 | `SessionKeysTest` | §3.7.2 table; every Ctrl reservation is `(ctrl && !alt) \|\| meta`: AltGr+W types "?", AltGr+Q types "/", AltGr+A types "á", AltGr+L never opens the address bar, AltGr+T never opens chat, AltGr+C never copies; Right Alt + ← never navigates; Shift+Esc never exits; F6/F7 never reach `func_152348_aa`; screenshot replicated only for a non-text bound code; text-key PTT typed, non-text PTT passed through |
| WP-3 | `SessionRulesTest` | Every exit and pause reason, including "take not granted"; each emits release-all once; refocus window ignores key and button downs |
| WP-3 | `ControlAimTest` | Agrees with the server's grant rules minus line of sight on shared inputs (same `ControlReach`); DIRECT only against a minor-0 hello |
| WP-3 | `KeepaliveScheduleTest` | ≤ 1 per 5 s; none on normal-play hover; in-session motion over the picture counts; none when `ControlAim` fails; idle warning at `idle − 15 s` |
| WP-3 | `ScreenInputControllerTest` | Optimistic take kept; refusal → release-all, resync, session close; take before SetUrl on a free screen only; "muda para todos" toast once per session |
| WP-3 | `GrabArbiterTest`, `LookDriverMathTest`, `RenderTickOrderTest` | Fake `Mouse`/`Display`/`MouseHelper`: the grab changes only in `onRenderTickStart`; a non-session GUI after a session → ungrab and centred cursor next frame; closing to no GUI leaves the grab to vanilla; window inactive → ungrab, active → grab; reconcile after every change; vanilla sensitivity × `k`, invert; handler priorities HIGHEST / HIGH / LOW |
| WP-3 | `KeyAutoAssignTest`, `KeyHintTest` | First free of K, U, J, H, Y; N, V, O never; once only; none free → unset; default mouse bindings named in words, rebound ones by display string; missing binding → segment dropped |
| WP-3 | `VideoFitClientTest` | Sample carries `fullscreen` only to a minor-1 server; follower SYNC on a change and ≤ 1/s while its own sample disagrees; nothing to a minor-0 host |
| WP-2 | `HostMessagesTest` (extended), `WriterCoalescingTest` | §7.2 |
| WP-2 | `CursorKindTest`, `CursorAtlasTest`, `CursorQuadTest` | Electron 44 type list total, unknown → ARROW; raster hash; quad upright per rotation, inside the picture; TAKE always ARROW |
| WP-2 | `ScreenRendererStateTest` | Polygon-offset enable, alpha func, depth mask restored on every path (exceptions included) |
| WP-4 | `AddressBarModelTest` | Enabled flags against the server rules; controller line never a UUID prefix when a name exists; read-only mode |
| WP-4 | `UrlHistoryTest` | Dedupe, cap, order, token and credential stripping, fragment dropped, `keepUrlHistory=false` writes nothing and deletes the file, corrupt file tolerated |
| WP-4 | `SettingsModelTest`, `TabCompletionTest` | Draft → `RightsChange` with UNCHANGED sentinels; names in chunks of ≤ 4; `grantable` respected; dirty draft + server change raises the banner; presets filtered by the limits block. Tab candidates: `§` stripped, only `[A-Za-z0-9_]{1,16}` kept (decorated and fake proxy entries dropped) |
| WP-4 | `GuiPageMapperTest`, `SessionHudTest`, `ConsentViewTest` | Aspect ratios, edges, outside, GUI scales 1-4; hint and status precedence; `progress` mapping |
| WP-5 | `ControlGrantTest`, `KeepaliveGeometryTest`, `ControlLockTest`, `ActionRulesTest` | §7.2; keepalive accepted while looking away within the geometry; LASER fails 5 s after the laser leaves the hand; FAR uses the union of grants; idle unchanged |
| WP-5 | `PeripheralSessionsTest`, `KeyboardLinkTest`, `ConnectedFrameTest`, `VideoFitRuleTest` | §7.2 |
| WP-0 | `ArchitectureGuardTest`, `ArchitectureGuardSelfTest`, `ModDependencyTest`, `LangFilesTest`, `HelloLimitsCodecTest`, `MediaSampleCodecTest` | §6.4 rules and the `@SideOnly` exception; FML range parsing; pt_BR/en_US parity and argument counts; codecs both ways (an old reader stops after `playerUuid`; a new reader defaults when absent; `limitsVersion` extra bytes ignored) |
| WP-1 | `node --test`: `input.test.js`, `screens.test.js`, `preload.test.js`, `policy.test.js` | §7.2; the echo alias rewrite and its packaged-mode refusal |

### 8.2 Host integration facts (WP-1; Xvfb, Electron 44.7.0, Linux)

| Fact | Test | Gates |
|---|---|---|
| (a) | Letter `keyDown` alone types nothing; `keyDown` + `char` + `keyUp` types once | Router flag `letterKeyEvents`, S9 |
| (b) | `mouseLeave` clears `:hover` (hover-styled box; computed style read through the test channel) | Reliance on leave for hover menus |
| (c) | `releaseAll` mid-drag: no `mousemove` with a button flag afterwards | P3 |
| (d) | `edit copy` / `cut` / `selectAll` / `undo` / `redo` on a textarea | Ctrl+C/X |
| (e) | SYNC `fullscreen:true` in a page without user activation: main-frame video and cross-origin iframe video go fullscreen (API or CSS path recorded); `false` exits only a host-entered fullscreen | Video fit, S27 |
| (f) | `textInputSeq` on trusted pointer-up into a focused input, a fresh input and an iframe input; not on script `focus()` | Auto session, S22 |
| (g) | A right `mouseDown`/`mouseUp` raises `context-menu`, or not (recorded) | Phase D |
| — | Media fit: a direct MP4 fills the page (pixel probe) | S25 |

### 8.3 Dev-client bot scenarios (WP-6)

**Harness setup:**
- Xvfb displays `:77` and `:78`, Mesa llvmpipe, two Linux users (Chromium refuses root), `xdotool windowfocus` before input (wave-2 finding 4).
- **Keyboard (RA-11):** `locale-gen pt_BR.UTF-8` in the container; dev clients start with `LC_ALL=pt_BR.UTF-8`, because LWJGL 2 composes dead keys only through a local XIM opened with `XSetLocaleModifiers("@im=none")` (VERIFIED `LW:native/linux/org_lwjgl_opengl_LinuxKeyboard.c:85-90`; no XIC when `XOpenIM` fails, VERIFIED RA: `LinuxKeyboard.java:127-131`). `setxkbmap br abnt2`; keys are driven as physical keysyms with `xdotool key` (`dead_acute`, `dead_tilde`, `dead_circumflex`, `ccedilla`, `ISO_Level3_Shift+q`), never `xdotool type` for composed text (it maps missing keysyms onto a scratch keycode, INFERRED from its documented behaviour). Raw LWJGL events are asserted from a dev-only debug log (`-Dakashicweb.debugKeys=true`: `RawInputHooks` logs the last 32 raw events) in Phase A, and from the Doctor's Teclas tab in Phase B.
- A local Crucible (the owner's `server.jar`) and two RFG dev clients, all with UniMixins 0.3.1, GTNHLib 0.11.51, ModularUI2 2.3.85 **and Hodgepodge 2.7.189**, with `modularui2.cfg` debug off. Site policy BLOCKLIST.
- Dev clients launch the unpackaged host (`-Dakashicweb.dev.electron=…`, `-Dakashicweb.dev.hostApp=…`; honoured only when `fml.deobfuscatedEnvironment` is true). **Two host profiles:** with `--akw-test-allow-file` for echo-page scenarios, labelled "test origin" in the evidence; without it for real-site runs (S11b, S27b) when the container's network allows.
- Echo pages served through the alias `https://akw-echo.test/` (§6.2):
  - `input-echo.html`: writes the last event as compact JSON into `document.title` (type, x, y, button, clickCount, key, char, modifiers); dots each mousedown on a canvas; `cursor: pointer`, `text` and `wait` zones; a hover menu; a 5,000-px list; an autofocused `<input>`, a second `<input>`, a `<textarea>` and a button that focuses the textarea by script after 2 s; an iframe with its own input;
  - `video.html`: a looping MP4 with its own fullscreen button and an `f` key handler;
  - `clip.mp4` for the media-document fit.
- **Assertions** read `/awdoctor input` lines (which include the page title) from `latest.log`; pixel probes with Python `-I` + PIL. Screenshots (F2 or `import -window root`) stay outside the repo; only their SHA-256 goes into the evidence file.
- **Bot additions:** `mdown <b>`, `mup <b>`, `rel <dx> <dy>` (relative motion for grabbed-mouse look), `xkb <layout>`, `key <keysym…>`.

| # | Phase | Scenario | Pass criteria and expected screenshot |
|---|---|---|---|
| S1 | A | Form 4×3; aim at a known pixel with an empty hand | Title `mousemove` within ±2 page px. `s01-aim.png`: page cursor at the crosshair, vanilla crosshair absent, cyan frame |
| S2 | A | Right-click, left-click, double, triple | `mousedown`/`mouseup` left with clickCount 1/2/3; the block is still there (`/web info`) |
| S3 | A | Hold attack, `rel` moves, release; then in a session hold Shift and click elsewhere in the same tick | A move stream with the left flag, then `mouseup`; `s03-select.png` shows selected text; the Shift+click extends the selection (modifiers include shift) |
| S4 | A | On a free screen: wheel 3 notches; click; wheel 3 notches; sneak + wheel | First wheel: hotbar slot changes, `/web info` shows no controller; after the click the list scrolls 300 px; sneak + wheel moves the hotbar only |
| S5 | A | Aim at the link, text and wait zones while driving; then on a second, free screen | `s05-hand.png`, `s05-ibeam.png`, `s05-wait.png`; on the free screen a plain arrow |
| S6 | A | Fresh dev profile (our binding absent from `options.txt`): join; press the auto-assigned key; type `abc 123`; `rel 200 0` | Toast names K (or the first free candidate); input shows `abc 123`; player yaw changed; no key-up of the session key reaches the page. `s06-session.png`: top bar, world visible, no dimming |
| S7 | A | `xkb br abnt2`, `LC_ALL=pt_BR.UTF-8`: `key dead_acute a`, `dead_tilde a`, `dead_circumflex e`, `dead_acute x`, `ccedilla`, `Shift+dead_acute a`, `ISO_Level3_Shift+q`, `ISO_Level3_Shift+w` | Input shows `á ã ê ´x ç à / ?`; the debug log shows the dead-key event shapes (X11); Windows shapes stay on the G3-A list |
| S8 | A | In a session: Esc / Shift+Esc / Ctrl+L then Esc / F2 / Ctrl+T then Esc / F5 / F6 / F7 / Ctrl+R | Exits / title `keydown Escape` / address bar, then the session back / screenshot file created and no F2 in the title / chat, then the session back / F5, F6, F7: nothing in the title, no epoch change, `currentScreen` never `GuiStreamUnavailable` (logged class names) / Ctrl+R: new epoch and the "muda para todos" toast |
| S9 | A | Session: `k` (only if fact (a) passed) | Title `keydown k`, `char k`, `keyup k` |
| S10 | A | Select text, Ctrl+C, `xclip -o`; `xclip -i "olá"`, Ctrl+V | Clipboard equals the selection; the input shows `olá` |
| S11 | A | Sneak + use (empty hand) → MUI2 address bar; type URL; Enter; reopen; ↓ history (B); one Esc. Repeat holding a stick: sneak + use | URL changed; closed in one Esc. Client B follows the media clock (`IN_SYNC` in B's `/awdoctor`) because A took control before SetUrl. With the stick: no address bar. `s11-address.png` |
| S12 | B | Settings: add Bot2 with CLICK_TYPE, Save; B has Settings open while A saves; Apply a resolution | `/web info` lists Bot2; B shows "Alterado por outra pessoa"; the page size changes after the confirm |
| S13 | A | Engine absent (fresh profile, zip in the import folder) | Consent → progress → done toast; Cancel works; `s13-consent.png` dimmed (corner pixel darker than the same pixel without the screen) |
| S14 | A/B | Every MUI2 screen at GUI scale 1-4 × 854×480, 1280×720, 1920×1080 | Panel bounds inside the window; no dimming except Consent; no clipped text (review) |
| S15 | A | Two clients: A clicks first; B aims and clicks; owner C pre-empts | B: amber frame, NOT_ALLOWED sprite, toast, no page event in B's host log. C: confirm dialog, then controller. A: session closed with the toast |
| S16 | A | Drag, then E; hold `a` in a session, then Esc; hold `w` while pressing Esc, release `w` 1 s later | `mouseup` after the GUI opened; `keyup a` on exit; with Hodgepodge the player walks while `w` is held and stops within 2 ticks of its release (position log) |
| S17 | C | Keyboard item at a desk 30 blocks from the screen (`interactRange` 24): sneak + use aimed at the screen; place on the desk; right-click; type; Ctrl+L; set a URL | Link toast; session opens; take accepted (`/web info` controller = A); URL changes. A take from 30 blocks without the keyboard is refused. No OUT_OF_VIEW/TOO_FAR between activation and take in the server diagnostics |
| S18 | A | Session; turn 180° away; type one key every 10 s for 90 s; repeat with `controlGrants=false` | Still controller at 90 s; lost after about 60 s with grants off |
| S19 | C | 32×18 screen at 40 blocks: empty hand, then the Laser Pointer; then switch to another item for 10 s while keeping a session open | No hover with an empty hand; hover, click and drag with the laser; LASER grant in diagnostics; `s19-laser.png` shows the beam; after the switch the lock is released within about 6 s |
| S20 | A | Session; `xdotool windowfocus` on another window; refocus with a click | `PAUSED`; release-all in the title; no second `keyup` from LWJGL's synthetic key-ups; the GUI still open; the refocus click is not a page click |
| S21 | A | Grab transitions in a session: Ctrl+L then click "Ir" with the mouse; Ctrl+T then click in the chat input; `/kill` then click "Respawn" | Each time the OS cursor is visible (`Mouse.isGrabbed()` false in `/awdoctor input`) and the click acts (URL set, chat focused, player respawned) |
| S22 | A | Click into the autofocused `<input>`; Esc; click the same input again; press the button that focuses the textarea by script; click into the iframe input; hold `w` and click the second input | A session opens after each click into an input, including the already focused one and the iframe; none after the script focus; with `w` held, the page receives no `w` until it is released and pressed again |
| S23 | A | Visitor clicks the picture; visitor sneak + attacks; a villager stands between the owner (driving) and the picture: use, then attack | Toast "Só para assistir", block intact, no `NO_PERMISSION` chat; then `SCREEN_PROTECTED` naming the owner; use opens the villager's trade GUI and attack hits it, with no page event |
| S24 | A | 60 s hover sweep | FPS delta vs idle, GC log, writer-queue high-water ≤ 256, MOVE rate ≤ 60/s |
| S25 | A | Direct MP4 URL (`clip.mp4`) | The video fills the picture (pixel probe ≥ 90 % of width or height) |
| S26 | B | `/awdoctor gui` → Teclas; `key dead_acute x`; `/awdoctor keys`; "Copiar relatório" | Raw events listed and routed as CHAR "´", CHAR "x"; clash list and PTT line printed; the clipboard holds no UUID-like string or URL query |
| S27 | A | A drives `video.html` in a session and presses `f`; then A releases control and walks away; then A (or C) sets a new URL | B's picture shows only the video (pixel probe ≥ 90 %), `fit` in B's `/awdoctor`; B stays fullscreen after A leaves; B exits on the new URL. S27b repeats on a real video site without the test flag when the network allows |
| S28 | A | `options.txt` binds vanilla `key.playerlist` to mouse button 4 (LWJGL 3); aim at a driven screen and press it; then open a session and press mouse button 5 (unbound) | Normal play: the player list shows, no navigation; session: back with the "muda para todos" toast |
| S29 | A | Mixed versions: a `b1019e0` client (minor 0) against a wave-3 server, and a wave-3 client against a `b1019e0` server | Join, form, click, set URL, follower sync all work; no `BAD_PACKET` in the server diagnostics; the wave-3 client predicts DIRECT only and sends the old `C2SMediaSample` shape |
| S30 | A / C | A opens a session on a free screen while the owner removes A's CLICK_TYPE with `/web` in the same second; (C) a linked keyboard whose user lost the rights | The session closes within 3 s with "Não foi possível assumir o controle"; no page input after the close; (C) same through the peripheral path |

### 8.4 Owner G3 kits (Windows, Forbidden pack, Angelica + BSL v8.2.09 / Complementary r5.9.3, ABNT2)

**G3-A (after Phase A; the only kit before the first owner test):**
1. Typing rows of §3.7.4 on real hardware: ´ then x, ~ + Space, crase, ê, õ, É, Ç, AltGr+Q/W, AltGr+= (§), Alt+0233, emoji with Win+., `k` on a YouTube page (if the flag is on).
2. `/awdoctor keys` in the pack: which key "Digitar na tela" received; clashes; the Akashic Voice line (P-14).
3. Click into YouTube's search box: the session opens; type a search; Enter. Click it again after Esc.
4. LOOK session feel at 30, 60 and 144 fps.
5. Video fit with two players: the controller presses fullscreen on a live; the other client shows only the video, and keeps it after the controller walks away.
6. Cursor sprite and frame under no shaders, BSL and Complementary, day / night / indoors; z-fighting on a big screen at 32, 64 and 96 blocks.
7. Alt+Tab mid-drag and mid-typing: no stuck button or key.
8. Ctrl+L, Ctrl+T and death inside a session: the mouse cursor is visible and usable in the next screen.
9. GUI scale Auto at the owner's resolution: address bar, consent and confirm readable.
10. A villager or Citizens NPC in front of a screen gets the click, not the page.
11. Hodgepodge volume curve with the click sounds; FpsReducer idle at the screen.

**G3-B addendum:** Settings, Doctor key tester, badge, power-on, loading bar, Help.
**G3-C addendum:** the telão from a booth Keyboard block (link from the desk), the Laser Pointer at 40+ blocks, the preview drawer under Angelica (MUI2-UNVERIFIED-11), region plugins at spawn.
**G3-D addendum:** Angelica path comparison screenshots; context menu.

---

## 9. Risks, questions and lead defaults

### 9.1 Risks (INFERRED unless cited)

| # | Risk | Likelihood / impact | Mitigation |
|---|---|---|---|
| R-01 | ModularUI2 with a grabbed mouse flickers the cursor or keeps a stale hover (MUI2-UNVERIFIED-1) | Medium / medium | `GrabArbiter` (one owner, render-tick only); no interactive widget in the session panel; S6-S8, S21; vanilla `GuiScreen` view as the fallback |
| R-02 | Electron offscreen facts: a letter `keyDown` types by itself; `mouseLeave` keeps `:hover`; `context-menu` does not fire; fullscreen without activation fails; `pointerup` from `sendInputEvent` is not trusted | Medium / medium | WP-1 facts (a)-(g) gate the router flag, the leave reliance, Phase D, the video-fit path (CSS fallback) and the auto-session trigger |
| R-03 | Crosshair pointing fatigue on long reading or selection (R5 §4.3) | Medium / medium | Session look sensitivity 0.6; preview drawer (Phase C) |
| R-04 | The keepalive relaxation is seen as loosening the server | Low / low | DIRECT changes only the look check; LASER needs the laser in hand (5 s grace), PERIPHERAL the booth (§3.3.3); first take unchanged; `controlGrants=false` lever |
| R-05 | Peripheral activation processed after the take | Low / medium | Take sent from `onOpen` a tick later; S17; a refused first take retries at 1/s, then the session closes (S30) |
| R-06 | Clients without ModularUI2 or GTNHLib (another launcher, **ForbiddenMobile**) stop at FML's missing-mods screen | Unknown / **high** for them | P-18 before WP-0. Fallback: a build where `client.ui` is optional (`after:` instead of `required-after:`, with `VanillaUiPort`), at the cost of two code paths |
| R-07 | Key binding clashes; Hodgepodge fires every binding on a key | Medium / medium | `KeyAutoAssign` picks only unused codes; `/awdoctor keys` in Phase A; a binding a mod registers after the first world join can still clash (INFERRED rare) |
| R-08 | A player with CHANGE_URL but no CLICK_TYPE changes the URL with no controller; followers play on their own clock | Low / low | Owner's rights choice; documented in Help |
| R-09 | WD texture provenance or the repository licence (P-08) | Low / low | Public domain with credit; `--own-art` fallback; no WD sounds |
| R-10 | Shader packs shade the overlays oddly; polygon offset differs under Angelica's core profile | Medium / medium | Opaque cutout overlays, no blend; offsets set explicitly; Angelica path opt-in; G3-A item 6 |
| R-11 | Scope against the event window | High / high | Phase A first; explicit cut order (§7); CURSOR moved out of wave 3 |
| R-12 | MUI2 2.3.85 tooltip draw order (fixed in 2.3.89, R1 §4.8) | High / low | Tooltips never overlap neighbours |
| R-13 | A MOVE flood fills the 4096-slot writer queue and closes the host connection (VERIFIED R3 §1.1) | Low / high | ≤ 1 MOVE per frame; tail-only coalescing; S24 |
| R-14 | lwjgl3ify (Tier 2) delivers chars only while SDL text input is on | Medium / low | Reflective `begin/endTextInput` (R4 §4.6) |
| R-15 | Akashic Voice push-to-talk on V opens the mic for each typed "v" (as it already does in chat) | High (default binding) / low-medium | C-44: page wins for text keys; non-text PTT passes through; Doctor warning; P-14 cross-repo fix |
| R-16 | Video fit fails or looks wrong on a site (custom player, fullscreen blocked, transformed ancestors break the CSS path) | Medium / medium | Two paths (API, CSS); fact (e); Doctor records the path; the follower keeps the layout as today if both fail; S27b on a real site |
| R-17 | Hodgepodge re-presses a movement key still held when a session closes | Certain / low | Accepted (later Minecraft versions behave so); S16 asserts the player stops on release |
| R-18 | A grab change drops a click buffered in the same frame (`Mouse.setGrabbed`) | Low / low | Grab changes only on GUI transitions; `reconcileButtons` keeps the page balanced |
| R-19 | Entities beyond 3 blocks occlude only on press, so the page cursor can be drawn over a distant mob | Medium / low | Cosmetic; the press itself is correct (§3.6) |

### 9.2 Perguntas para o dono (pt-BR; viram P-14..P-18 em `DECISIONS.md`)

Só o que depende do dono (D-014 delegou o resto; ver 9.3).

| # | Pergunta | Recomendação |
|---|---|---|
| P-14 | **Teclas e Akashic Voice.** "Digitar na tela" pega sozinha a primeira tecla livre entre K, U, J, H e Y no primeiro uso (a N fica de fora: é o mudo do Akashic Voice). Prefere uma tecla fixa? E a tecla de falar do Akashic Voice em V também digita "v" na página (como já acontece no chat): mudamos o Akashic Voice para ignorar a tecla de falar quando uma tela de texto está aberta, ou trocamos o padrão para um botão do mouse? | Deixar a escolha automática e conferir com `/awdoctor keys` no G3-A. No Akashic Voice: ignorar a tecla de falar com tela de texto aberta. |
| P-15 | **O que quem assiste vê.** Hoje quem assiste vê o endereço, o vídeo no mesmo ponto e a tela cheia, mas não a digitação, os menus nem a rolagem. Quer, depois do evento, um "ponto de rolagem" compartilhado ou o cursor de quem controla? Isso amplia a D-016 (a posição da rolagem ou do cursor passaria pelo servidor; teclas nunca). | Depois do evento; no máximo o ponto de rolagem. |
| P-16 | **Textos.** Aviso de digitação ("As teclas vão só para o seu navegador… endereços e buscas aparecem para todos…") e a etiqueta da Ajuda (seção 4). | Aprovar ou ajustar. |
| P-17 | **Receitas e arte.** Receitas com itens vanilla (seção 5) e texturas do Web Displays com crédito ao montoyo (liga com a P-08)? | Vanilla agora; texturas do WD com crédito. |
| P-18 | **ForbiddenMobile e outros launchers.** Eles têm ModularUI2 e GTNHLib? Com a onda 3 os dois passam a ser obrigatórios; sem eles o jogador não entra no servidor. | Confirmar a lista de mods do pack mobile antes do WP-0. |

Already open elsewhere: whether a loose word in the address bar becomes a search, and with which engine, folds into P-05; the telão channels are P-07.

### 9.3 Lead defaults under D-014 (recorded as D-018)

| Topic | Default |
|---|---|
| Auto session on a page text field | On (pref to turn off) |
| Props for 10-25 | Keyboard block and Laser Pointer; Remote, Configurator, Tablet after the event |
| Telão reach | `interactRange` 24, `peripheralRange` 32 (max 64), laser 1.5 × the screen's larger side (max 64) |
| Typing style | LOOK only; free cursor after the event; precision through the preview drawer |
| Right button | Page left everywhere (`rightButtonInSession = LEFT`) |
| Side buttons | Back/forward only inside a session |
| Page click sound | On, low |
| URL history | Local, on, with "Limpar histórico" |
| Typing key | Automatic free key (P-14 can override) |
| "Pedir a vez" | After the event |

---

## 10. What this spec did and did not run

- **Did (STATIC, read-only):**
  - Read the repository at `b1019e0` (`git log`, `git diff --stat 8730941..b1019e0`, `git worktree list`) and re-read every `AW:`/`DOC:`/`HOST:` citation that is not tagged "(RA)" or attributed to R1-R5, including the W3-0 changes to `ClientScreens`, `ReasonCode`, `UrlPolicy` and the host policy, and `AkwMessage`, `C2SMediaSample`, `ScreenSync.tell`, `LocalMedia`, `ClientTaskQueue`, `HostExecutable`, `HostLaunchConfig`, `host/src/sessions.js`, `main.js`, `policy.js`.
  - Re-read the vanilla sources behind the review findings: `Minecraft.displayGuiScreen`, `setIngameFocus`, `setIngameNotInFocus`, `func_152348_aa`, `GameSettings` key defaults and `getKeyDisplayString`, `GuiScreen.handleInput` and `handleKeyboardInput`, `MouseHelper`, `EntityRenderer` entity reach; and the vanilla `pt_BR.lang` asset.
  - Re-read LWJGL 2 (`Mouse.setGrabbed`/`resetMouse`, `WindowsDisplay.appActivate`, `LinuxKeyboard.releaseAll`, the native XIM setup), Hodgepodge (`MixinMinecraft_UpdateKeys`, `MixinKeyBinding`), ModularUI2 (`ClientScreenHandler.onGuiChanged`, repeat events, `drawContainer`, `openParentOnClose`), GTNHLib (`SimpleGuiConfig`, `ExcludeFromAutoGui`, category culling), Angelica (`TesrMaterial.cutout(float)`), EntityCulling (`CullTask`), and Akashic Voice (`AkashicVoiceKeybinds`, `PttInputBridge`, `ClientVoiceBootstrap`).
- **Did not run:** any build, test, Gradle task, Minecraft client or server, host or Electron; anything on Windows; an ABNT2 keyboard, Angelica, shaders, ModularUI2 at runtime; any bot scenario. The test counts quoted are the ones recorded in `CHANGELOG.md`, not re-run.
- **Grades.** Every design element is AUTHORED/INFERRED until ADR-017/018 are accepted and the gates confirm them. Behaviour cited from R1-R5, RA and RB keeps those reports' own grades.
- **Repository.** Nothing in `/home/user/akashicweb` was edited or committed. Scratch files are under `scratchpad/wave3/`.
