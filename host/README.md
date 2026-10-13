# AkashicWebHost (host app)

The out-of-process browser of AkashicWeb (ADR-001/002). It is a small JavaScript app that runs inside
the **official, unmodified Electron 44.x build** (Chromium 152). The game spawns it, it renders every
in-world screen offscreen, and it talks to exactly one Minecraft client over one loopback TCP
connection using the binary protocol in [`../docs/PROTOCOL.md`](../docs/PROTOCOL.md) section 1.

No npm dependencies. Plain CommonJS that runs on Electron 44's Node 24 and on Node 22 for the tests.

Evidence grade of everything below: AUTOMATED on **Linux only** (Electron 44.7.0 linux-x64 under Xvfb,
Node 22.22, 4-vCPU container, 2026-10-12). Nothing here was run on Windows or inside Minecraft.

## Launch contract (what HostSupervisor must do)

```
AkashicWebHost(.exe) --akw-port=<port> --akw-parent-pid=<pid> --akw-user-data=<abs dir> [--akw-log=<abs file>] [--akw-gpu]
```

- The app lives in `resources/app/` next to the renamed Electron executable; `default_app.asar` is deleted
  at install (ADR-001 amendment). `package.json` `main` is `src/main.js`.
- Token: 32 hex chars + `\n` on **stdin**, then stdin closed. Fallback: `AKW_TOKEN` in the child
  environment only. The host removes `AKW_TOKEN` from its own environment as soon as it is read, so
  renderer and utility processes never inherit it.
- Environment: built explicitly, without any `NODE_*` / `ELECTRON_*` variable; no `--inspect`.
- `--akw-gpu` selects GPU OSR; the default is software OSR (`app.disableHardwareAcceleration()`), the
  configuration the Linux probe verified. Which one ships is decided by the G1-E measurements.
- The host connects to `127.0.0.1:<port>` after Electron is ready, sends `HELLO`, and expects `WELCOME`
  within 10 s.

Exit codes (stable, `src/args.js` `EXIT`):

| Code | Meaning | Suggested game reason |
|---|---|---|
| 0 | Normal exit (QUIT, socket closed, parent gone, signal) | `HOST_EXITED` if unexpected |
| 64 | Missing/invalid `--akw-*` argument or profile dir not creatable | `HOST_HANDSHAKE` |
| 65 | A forbidden switch was on the command line | new `HOST_FORBIDDEN_SWITCH` |
| 66 | No valid token on stdin nor in `AKW_TOKEN` | `HOST_HANDSHAKE` |
| 67 | Could not connect to `127.0.0.1:<port>` within 5 s | new `HOST_CONNECT_FAILED` (firewall/AV) |
| 68 | No `WELCOME` in 10 s, or `protoMajor` mismatch | `HOST_HANDSHAKE` / `PROTOCOL_MISMATCH` |
| 69 | The game sent bytes that are not protocol v1 | `HOST_HANDSHAKE` |
| 70 | Uncaught main-process exception, or a child-process crash loop | `HOST_EXITED` |

`BYE {reason}` precedes every exit after the connection exists. Reasons: `quit`, `parent-gone`,
`protocol-error`, `protocol-mismatch`, `welcome-timeout`, `internal-error`, `child-crash-loop-<type>`,
`signal-<SIG>`. Socket loss exits without `BYE`.

## Files

| File | What it does |
|---|---|
| `src/main.js` | Entry point: arguments, forbidden-switch check, Electron switches, token, connection, HELLO/WELCOME, parent-PID poll (2 s), shutdown |
| `src/protocol.js` | Wire format (shared with `HostProtocol.java`); helpers `encodeParts`, `pcmHeader`, `decodeJson`, `typeName` were added, the format is unchanged |
| `src/link.js` | The socket: framing, per-screen `seq`, backpressure flag, 64 MiB write-queue cap (drops a game that stopped reading) |
| `src/router.js` | Dispatch of game messages; nothing but WELCOME/PING/QUIT before WELCOME; malformed messages are logged and dropped |
| `src/messages.js` | Validation and clamping of every game JSON payload; stock Chrome UA; Accept-Language |
| `src/screens.js` | One offscreen `BrowserWindow` per screen: CREATE, DESTROY, NAVIGATE, INPUT, AUDIO, RENDER, RESIZE, SYNC, STATE, EVENT |
| `src/sessions.js` | One in-memory partition per SHARED screen (`akw-<screenId>-<generation>`), cleared on DESTROY; `persist:akw-private` for PRIVATE |
| `src/frames.js` | Dirty-rect crops, row bands under the 16 MiB cap, latest-wins `FrameScheduler` with damage merging |
| `src/input.js` | INPUT JSON -> `sendInputEvent` objects (mouse with held-button modifiers, wheel, keys, chars) |
| `src/policy.js` | URL checks, internal-address detection (IPv4/IPv6), site policy, permission decisions, and their Electron wiring |
| `src/inject.js` | Runs host scripts in every frame (isolated world in the main frame, main world in iframes) |
| `src/audio.js` | Engine mute + page volume script (OS audio mode, ADR-007) |
| `src/media.js` | MEDIA sampler (4 Hz) and SYNC steering on `HTMLMediaElement` (ADR-012) |
| `src/guard.js` | Best-effort page guard against native print and file dialogs |
| `src/capture.js`, `src/capture/*`, `src/pcm.js` | SPATIAL audio capture (optional, ADR-008) |
| `src/log.js`, `src/clock.js`, `src/limiter.js`, `src/args.js`, `src/token.js` | Logging with URL redaction, host monotonic clock, EVENT rate limit, command line, token |

## Behaviour the game can rely on

- **FRAME**: BGRA, premultiplied, rows of the dirty rect only. The first frame of a screen, the first
  after a size change, after `RENDER painting:true` and after `RESIZE` is always the full page. While the
  socket is blocked at most one frame per screen waits; newer paints replace it and the dirty rects are
  merged, so applying every FRAME in order to a canvas always reproduces the page (checked pixel-exact in
  the integration test). A page larger than about 4.1 megapixels is sent as several row bands, each a
  valid FRAME. The page is at most 4096x4096; Chromium may clamp the offscreen view to the display size
  (ADR-002 amendment), so always trust the `width`/`height` in each FRAME.
- **STATE** `{url, title, loading, canGoBack, canGoForward, audible, cursor}` is sent on change only.
- **MEDIA** `{t, mediaTime, paused, rate, duration, live, behindLiveEdge, adShowing, fullscreen}` at
  about 4 Hz while a screen is painted or audible and has a media element. `duration` is `-1` when
  unknown or infinite. `t`, `PONG.hostTimeMs` and `PCM.ptsMicros` share one host monotonic clock.
  The main media element is the playing video with the largest area (open shadow roots included); if the
  top document has none, iframes are scanned every 2 s.
- **SYNC** is applied to that element: `playbackRate` clamped to 0.95-1.05 (reset to 1 while paused),
  optional seek (VOD clamped to the duration; live never past `seekable.end - 0.5 s`), play/pause.
  While this client's own ad shows (`.ad-showing` on YouTube, Twitch ad labels; selectors unverified),
  steering is suspended and the rate is 1.
- **INPUT**: coordinates are page pixels, clamped to the page. Wheel uses the Chromium sign: `deltaY < 0`
  scrolls down (the same sign as LWJGL `Mouse.getDWheel()`), about 120 per notch. `keyDown`/`keyUp` take a
  DOM `key` name (`ArrowLeft`, `Enter`, `a`) or an Electron accelerator name; they never insert text.
  Text comes only from `char` (accents composed by the game); control characters in `char` are dropped,
  and `keyDown Enter` (without Ctrl/Alt/Meta) inserts the newline itself. `modifiers` may be an array of
  names or the Java `ScreenInput.MOD_*` bitmask.
- **AUDIO** `{muted, volume}`: `muted` or `volume <= 0` is an engine mute (`setAudioMuted`), the only hard
  guarantee. The volume is also written into every `<video>`/`<audio>` of every frame and re-applied every
  100 ms. For SPATIAL screens the game should send volume 1 and apply its gain in AkashicFM, because the
  capture taps the page after the element volume.
- **EVENT** `{kind, detail}`:
  - `blocked-url` `{url, host, reason, via}`; reasons `malformed`, `too-long`, `scheme`, `credentials`,
    `internal`, `not-allowed`, `blocked`, `internal-dns`, `dns-failed`, `internal-ip` (with `ip`); `via` is
    `navigate`, `will-navigate`, `will-frame-navigate`, `will-redirect`, `window-open`, `policy-change`,
    `request-<resourceType>` or `response`;
  - `load-error` `{code, description, url}` for main-frame failures (aborts and our own blocks excluded),
    or `{reason:'screen-limit', limit:16}`;
  - `renderer-gone` `{reason, exitCode}`; `unresponsive` `{}` and **`responsive` `{}`** (added kind);
  - `capture-error` `{detail}`, `download-cancelled` `{filename}`, `permission-denied` `{permission}`.
  EVENTs are rate limited per screen (burst 20, then 5/s).
- `CREATE` on an existing screenId recreates the screen with a fresh session. At most 16 screens.

## Security posture

- Every screen window: `sandbox`, `contextIsolation`, no Node in page, worker or subframes, no
  `<webview>`, `devTools:false`, `webSecurity` on, dialogs disabled, no spellchecker download, no
  drag-and-drop navigation. `app.enableSandbox()` unless `--no-sandbox` is present.
- Refuses to start (exit 65) with `--remote-debugging-*`, `--inspect*`, `--debug*`,
  `--disable-web-security`, `--allow-file-access-from-files`, `--user-data-dir`, `--load-extension`, and on
  Windows also `--no-sandbox`, `--disable-gpu-sandbox`, `--no-zygote`. No remote debugging is ever opened.
- No application menu (no DevTools/reload accelerators); `devtools-opened` closes DevTools.
- Uncaught main-process exceptions are logged and end the host (no Electron error dialog over the game).
- Child windows: `setWindowOpenHandler` always denies; an allowed URL is loaded in the same screen.
- Downloads cancelled (`will-download`). HTTP auth prompts and client-certificate selection answered
  with nothing. Device pickers (HID, serial, USB, Bluetooth) answered with "no device".
- Permissions: request **and** check handlers deny everything, `local-network`, `loopback-network` and
  `local-network-access` explicitly (so Chromium's Local Network Access blocks public pages from reaching
  LAN/loopback after DNS). Exceptions: `fullscreen` (needed by player fullscreen buttons; the offscreen
  view never resizes thanks to `disableHtmlFullscreenWindowResize`; denying it was observed to break
  `requestFullscreen()`), and `media`/`display-capture` for our own capture page only.
- Internal-address block (ADR-013 amendment):
  1. LNA permissions denied (above).
  2. Literal checks in `will-navigate`, `will-frame-navigate`, `will-redirect`, the window-open handler and
     `webRequest.onBeforeRequest` for every request (http, https, ws, wss); main-frame and sub-frame
     targets with a name are resolved with `session.resolveHost` and cancelled if any answer is private,
     loopback, link-local, CGNAT, multicast, reserved, IPv6 ULA/link-local, or IPv4 embedded in
     IPv6 (mapped, compat, NAT64, 6to4). Resolution failure or a 5 s timeout also cancels (fail closed).
     Navigations started by the game are resolved before `loadURL`, so the screen keeps its page.
  3. Detector: `details.ip` on `onBeforeRedirect`, `onResponseStarted` and `onCompleted`; an internal
     address stops the screen, loads `about:blank` and emits `blocked-url` with `reason:'internal-ip'`.
  4. The server-side `UrlPolicy` (M4) stays authoritative.
- Site policy: `blocklist` entries (host and subdomains) apply to every frame; `allowlist` applies to the
  top-level document only, so allowed sites keep their third-party frames (consent, players). A POLICY
  change also re-checks what each screen currently shows.
- Shared screens use in-memory sessions, a new one per CREATE, cleared on DESTROY.
- WebRTC: `default_public_interface_only` (no LAN addresses to IP loggers). Media keys and the OS media
  overlay are disabled (`HardwareMediaKeyHandling`, `MediaSessionService`).
- Page guard (best effort, main world of every frame, after each load): `print()`, `showOpenFilePicker`,
  `showSaveFilePicker`, `showDirectoryPicker` neutralised, file-input `showPicker()` and clicks cancelled.
  Electron opens the native print dialog and the native file chooser even for offscreen windows and has
  no API to veto them; a page that calls `print()` before the guard is installed still gets a dialog.
- Logs redact URLs to scheme, host and path. The token is never logged.

Test-only switch: `--akw-test-allow-file` lets `file:` pages load so the integration test needs no
network. It is honoured only when `app.isPackaged` is false, i.e. when the executable is still named
`electron`; the renamed production `AkashicWebHost.exe` ignores it, and Java never passes it.

## SPATIAL audio (optional, behind `CREATE audio:'spatial'`)

Per the ADR-008 amendment, a hidden capture window is created **inside the screen's own partition**
(sandboxed, `contextIsolation`, a `contextBridge` preload exposing two one-way calls). The session's
display-media handler grants the screen's tab audio only to that capture page (`enableLocalEcho` false,
so local playback is muted while captured). The page captures with AEC/AGC/NS off, `channelCount: 2`,
48 kHz, through an AudioWorklet that emits 480-frame (10 ms) s16 packets; the host sends them as PCM with
a host-monotonic pts (arrival time minus packet duration; jitter is left to the Java tap). Failures emit
`capture-error`; the game falls back to OS mode (ADR-008). Linux result: 100 packets/s, RMS 0.21 of a
0.3-amplitude sine. **Untested on Windows**, including the silent-source requirement; pre-mute is not
implemented (only needed if G1-E shows a leak before capture starts).

## Tests

```
cd host
node --test test/*.test.js                       # unit tests; the integration test skips itself
AKW_ELECTRON=/path/to/electron node --test test/integration.test.js
```

The integration test is a small Node "game" (`test/harness.js`): it listens on `127.0.0.1:0`, spawns the
Electron binary the way HostSupervisor will (stdin token, clean environment), under `xvfb-run` when there
is no display, adds `--no-sandbox` only when running as root on Linux, and loads `file:` fixtures from a
temporary directory (a sine WAV is generated, no media is committed). It checks HELLO, PING/PONG, STATE,
frame size/pixels/rate, a canvas rebuilt from dirty rects against a full repaint, a 2 s stalled reader,
mouse/char/Backspace/Enter/wheel input, page and iframe volume, the page guard, MEDIA/SYNC, every
internal-address layer, window.open, fullscreen, RENDER, RESIZE, two screens, DESTROY, exit on socket
close, exit on parent death, the env-token fallback, exit codes 65/66/68, and SPATIAL PCM.

## Not run / open issues

- Nothing was run on Windows: stdin token delivery to a GUI-subsystem exe, Defender/SmartScreen on the
  unsigned exe, firewall prompts, Windows OSR clamp to the display, audio, `wc.focus()` behaviour.
- No real website was loaded (no network in the test container): YouTube/Twitch volume, ad selectors,
  live-edge numbers, and Chromium's LNA blocking from a public origin are unverified.
- Native print/file dialogs: only mitigated (see guard). Candidate fix for a later wave: a CSP
  `sandbox` header without `allow-modals` on documents, which needs a YouTube/Twitch compatibility check.
- A local proxy or VPN that answers on loopback would make the response detector blank every page; the
  event carries the IP so `/web doctor` can show it.
- The response detector only fires when the internal host answers HTTP; a request that gets no response
  is not detected (it is a detector, not a preventer; layers 1-2 are the preventers).
- The isolated world is used only in the main frame; iframes run host scripts in the page's main world,
  so a hostile iframe can undo the volume script (the engine mute still holds).
