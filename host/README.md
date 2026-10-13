# AkashicWebHost (host app)

The out-of-process browser of AkashicWeb (ADR-001/002). It is a small JavaScript app that runs inside
the **official, unmodified Electron 44.x build** (Chromium 152). The game spawns it, it renders every
in-world screen offscreen, and it talks to exactly one Minecraft client over one loopback TCP
connection using the binary protocol in [`../docs/PROTOCOL.md`](../docs/PROTOCOL.md) section 1.

No npm dependencies. Plain CommonJS that runs on Electron 44's Node 24 and on Node 22 for the tests.

Evidence grade of everything below: AUTOMATED on **Linux only** (Electron 44.7.0 linux-x64 under Xvfb,
Node 22.22, 4-vCPU container, 2026-10-12/13). Nothing here was run on Windows, inside Minecraft or
against real web sites (the container has no web access; test sites are local).

## Launch contract (what HostSupervisor must do)

```
AkashicWebHost(.exe) --akw-port=<port> --akw-parent-pid=<pid|0> --akw-user-data=<abs dir> [--akw-log=<abs file>] [--akw-gpu]
```

- The app lives in `resources/app/` next to the renamed Electron executable; `default_app.asar` is deleted
  at install (ADR-001 amendment). `package.json` `main` is `src/main.js`.
- **Packaged host = allowlist.** When the executable is the renamed host (`app.isPackaged`), every argument
  that is not `--akw-*` is refused with exit 65 (Chromium switches such as `--host-resolver-rules`,
  `--proxy-server`, `--no-sandbox`, `--enable-logging`, Node options, positional arguments), and so are the
  environment variables `ELECTRON_ENABLE_LOGGING`, `ELECTRON_LOG_FILE`, `ELECTRON_ENABLE_STACK_DUMPING`,
  `ELECTRON_RUN_AS_NODE`, `NODE_OPTIONS`, `NODE_DEBUG`. Unpackaged runs (`electron <dir>`: development and
  the integration test) use a denylist (debugging, code-launching and web-security switches) so tests can
  pass `--no-sandbox` (root), `--host-resolver-rules`, `--proxy-server`, `--force-device-scale-factor` and
  `--unsafely-treat-insecure-origin-as-secure`.
- Token: 32 hex chars + `\n` on **stdin**, then stdin closed. Fallback: `AKW_TOKEN` in the child
  environment only. `main.js` copies and deletes `AKW_TOKEN` as its very first statement, so no child
  process started afterwards inherits it (processes Chromium starts before the app's JavaScript runs
  could; on Linux none was found to carry it).
- `--akw-parent-pid=0` means "unknown": the parent-PID poll is skipped and only socket EOF ends the host.
- Environment: built explicitly, without any `NODE_*` / `ELECTRON_*` variable; no `--inspect`.
- `--akw-gpu` selects GPU OSR; the default is software OSR (`app.disableHardwareAcceleration()`), the
  configuration the Linux probe verified. Which one ships is decided by the G1-E measurements.
- The host connects to `127.0.0.1:<port>` after Electron is ready, sends `HELLO`, and expects `WELCOME`
  within 10 s. `HELLO` carries `pid` (the host's own process id, for the stale-host sweep).
- Output: the host owns `--akw-log` (2 MiB, then one rotation to `.1`). JavaScript writes to
  stdout/stderr go into that file (echoed only in unpackaged runs), and Chromium's logging is turned
  down to FATAL at startup. What remains on stderr: the few lines Chromium logs before the app's
  JavaScript runs, and third-party libraries that write there themselves (Linux: ALSA without a sound
  card, D-Bus at startup).

Exit codes (stable, `src/args.js` `EXIT`):

| Code | Meaning | Suggested game reason |
|---|---|---|
| 0 | Normal exit (QUIT, socket closed, parent gone, signal) | `HOST_EXITED` if unexpected |
| 64 | Missing/invalid `--akw-*` argument or profile dir not creatable | `HOST_HANDSHAKE` |
| 65 | A forbidden switch (unpackaged) or any non-`--akw-*` argument or forbidden variable (packaged) | new `HOST_FORBIDDEN_SWITCH` |
| 66 | No valid token on stdin nor in `AKW_TOKEN` | `HOST_HANDSHAKE` |
| 67 | Could not connect to `127.0.0.1:<port>` within 5 s | new `HOST_CONNECT_FAILED` (firewall/AV) |
| 68 | No `WELCOME` in 10 s, `protoMajor` mismatch, or a malformed `WELCOME` | `HOST_HANDSHAKE` / `PROTOCOL_MISMATCH` |
| 69 | The game sent bytes that are not protocol v1 | `HOST_HANDSHAKE` |
| 70 | Uncaught main-process exception, or a child-process crash loop | `HOST_EXITED` |

`BYE {reason}` precedes every exit after the connection exists. Reasons: `quit`, `parent-gone`,
`protocol-error`, `protocol-mismatch`, `bad-welcome`, `welcome-timeout`, `internal-error`,
`child-crash-loop-<type>`, `signal-<SIG>`. Socket loss exits without `BYE`.

## Files

| File | What it does |
|---|---|
| `src/main.js` | Entry point: token taken first, arguments, allowlist/denylist, Electron switches, output capture, connection, HELLO/WELCOME, parent-PID poll (2 s), shutdown |
| `src/protocol.js` | Wire format (shared with `HostProtocol.java`); helpers `encodeParts`, `pcmHeader`, `decodeJson`, `typeName` were added, the format is unchanged |
| `src/link.js` | The socket: framing, per-screen `seq`, backpressure flag, 64 MiB write-queue cap (drops a game that stopped reading) |
| `src/router.js` | Dispatch of game messages; nothing but WELCOME/PING/QUIT before WELCOME; malformed messages are logged and dropped, except a malformed WELCOME (handshake failure) and POLICY (deny-all) |
| `src/messages.js` | Validation and clamping of every game JSON payload; page size cap; deny-all policy; stock Chrome UA; Accept-Language |
| `src/screens.js` | One offscreen `BrowserWindow` per screen: CREATE, DESTROY, NAVIGATE, INPUT, AUDIO, RENDER, RESIZE, SYNC, STATE, EVENT; navigation generations; popup gating |
| `src/page/screen-preload.js` | The screen preload (sandboxed, `contextIsolation`, exposes nothing): gain from document start, MEDIA sampling, SYNC, main-world guard, in every frame |
| `src/sessions.js` | Pool of in-memory partitions (`akw-<generation>`) for SHARED screens, cleared completely before reuse; `persist:akw-private` for PRIVATE |
| `src/frames.js` | Dirty-rect crops, row bands under the 16 MiB cap, latest-wins `FrameScheduler` with damage merging; a failed send makes the next frame full |
| `src/input.js` | INPUT JSON -> `sendInputEvent` objects (mouse with held-button modifiers, wheel, keys, chars, pastes) |
| `src/policy.js` | URL checks, internal-address detection (IPv4/IPv6), site policy, per-request name resolution with a verdict cache, proxy awareness, document sandbox CSP, service-worker block, permission decisions |
| `src/audio.js` | Engine-mute decision (game mute/gain, SPATIAL hold, document without the gain yet) and the gain sent to every frame |
| `src/media.js` | MEDIA from the preloads (best frame, sanitised samples), SYNC planning and dispatch (ADR-012) |
| `src/capture.js`, `src/capture/*`, `src/pcm.js` | SPATIAL audio capture (optional, ADR-008) |
| `src/log.js`, `src/clock.js`, `src/limiter.js`, `src/args.js`, `src/token.js` | Capped log with URL redaction and stdio capture, host monotonic clock, per-kind EVENT limiter, command line, token |

## Behaviour the game can rely on

- **Page size**: width and height at most 4096 and width x height at most 4,194,301 pixels (a full frame
  then fits one 16 MiB FRAME). A bigger CREATE or RESIZE is refused with EVENT `screen-limit`
  `{reason:'size', width, height, maxWidth, maxHeight, maxPixels}`; a refused RESIZE keeps the old size.
  Sizes below 16 are raised to 16.
- **FRAME**: BGRA, premultiplied, rows of the dirty rect only, device scale factor 1 (`width`/`height`
  equal the CREATE/RESIZE size whatever the display scaling). The first frame of a screen, the first after
  a size change, after `RENDER painting:true`, after `RESIZE` and after a frame that failed to send is
  always the full page. While the socket is blocked at most one frame per screen waits; newer paints
  replace it and the dirty rects are merged, so applying every FRAME in order to a canvas always
  reproduces the page (checked pixel-exact in the integration test). Always trust the `width`/`height` in
  each FRAME.
- **STATE** `{url, title, loading, canGoBack, canGoForward, audible, cursor}` is sent on change only.
- **MEDIA** `{t, mediaTime, paused, rate, duration, live, behindLiveEdge, adShowing, fullscreen}` at
  about 4 Hz on **every** painted or audible screen with a media element (followers need it to steer), also
  while the page is still loading. Sampled by the preload of each frame from a cached element set
  (rescanned on DOM mutations and every 3 s); the frame with the best element (playing > paused,
  video > audio, larger) is the screen's clock. `t` is the host monotonic arrival time; `duration` is `-1`
  when unknown or infinite; only these 9 fields, finite numbers. `t`, `PONG.hostTimeMs` and
  `PCM.ptsMicros` share one host monotonic clock.
- **SYNC** is applied by the preload of that frame to that element: `playbackRate` clamped to 0.95-1.05
  (reset to 1 while paused), optional seek (VOD clamped to the duration; live never past
  `seekable.end - 0.5 s`), play/pause. While this client's own ad shows (`.ad-showing` on YouTube, Twitch ad
  labels; selectors unverified, looked up inside the player only), steering is suspended and the rate is 1.
- **INPUT** (each rule has a test):
  - coordinates are page pixels, clamped to the page;
  - wheel: `deltaY > 0` scrolls up, `< 0` down, in pixels; the game sends notches x 100;
  - `keyDown`/`keyUp` take a DOM `key` name (`ArrowLeft`, `Enter`, `a`, `" "`) or an Electron accelerator name
    (`Space`, `PageUp`); they never insert text: `keyDown Space` types nothing, the game's `char " "` types
    the space;
  - `keyDown Enter` (without Ctrl/Alt/Meta) types the newline itself; a `char "\r"` is dropped;
  - `char` may carry a whole paste (up to 4096 code points): every character is typed in order; a `"\n"`
    is typed as Shift+Enter (a line break; in a single-line field the page sees an Enter), a tab as a tab,
    other control characters are dropped;
  - `modifiers` may be an array of names or the Java `ScreenInput.MOD_*` bitmask.
- **AUDIO** `{muted, volume}`: the gain reaches every frame's preload at once (also during page load) and
  is applied to every `<video>`/`<audio>` from document start; a new document is silent until it has the
  gain. The engine mute (`setAudioMuted`, the only hard guarantee) is on when the screen is muted, the gain
  is 0, a SPATIAL screen's capture is not running, or a new document has not taken the gain yet while
  the gain is below 1. For SPATIAL screens the game should send volume 1 and apply its gain in AkashicFM.
- **POLICY / WELCOME.policy**: no policy (before WELCOME, `null`, missing) is deny-all (an empty allow
  list). A malformed WELCOME ends the host (exit 68, `BYE bad-welcome`); a malformed POLICY switches to
  deny-all, re-checks every screen and sends EVENT `policy-error` `{error, applied:'deny-all'}` on screen 0.
- **EVENT** `{kind, detail}`:
  - `blocked-url` `{url, host, reason, via}`; reasons `malformed`, `too-long`, `scheme`, `credentials`,
    `internal`, `not-allowed`, `blocked`, `internal-dns`, `dns-failed`, `dns-via-proxy`, `popup`,
    `internal-ip` (with `ip`); `via` is `navigate`, `will-navigate`, `will-frame-navigate`, `will-redirect`,
    `window-open`, `policy-change`, `request-<resourceType>` or `response`;
  - `load-error` `{code, description, url}` for main-frame failures (aborts and our own blocks excluded);
  - `screen-limit` `{reason:'size', ...}` or `{reason:'count', limit:16}`;
  - `renderer-gone` `{reason, exitCode}`; `unresponsive` `{}` and `responsive` `{}`;
  - `capture-error` `{detail}`, `download-cancelled` `{filename}`, `permission-denied` `{permission}`;
  - `policy-error` `{error, applied}` (screen 0).
  Only page-triggerable kinds (`blocked-url`, `permission-denied`, `download-cancelled`) are rate limited,
  per screen and kind (burst 20, then 5/s); what is dropped is reported every 2 s as one EVENT of the same
  kind with `{suppressed: n}`. All other kinds are never dropped.
- **window.open** is honoured (loaded in the same screen, if the policy allows it) only within 1 s of an
  INPUT `mouseUp`/`keyDown` on that screen, once per input, and not across a navigation; otherwise EVENT
  `blocked-url` `{reason:'popup', via:'window-open'}`.
- `CREATE` on an existing screenId recreates the screen with a fresh (cleared) session. At most 16 screens.

## Security posture

- Every screen window: `sandbox`, `contextIsolation`, no Node in page, worker or subframes (the preload runs
  in subframes, sandboxed), no `<webview>`, `devTools:false`, `webSecurity` on, dialogs disabled, no
  spellchecker download, no drag-and-drop navigation. `app.enableSandbox()` unless `--no-sandbox` is
  present (unpackaged Linux test runs only).
- No remote debugging, no application menu (no DevTools/reload accelerators); `devtools-opened` closes
  DevTools. Uncaught main-process exceptions are logged and end the host (no Electron error dialog).
- Downloads cancelled (`will-download`). HTTP auth prompts and client-certificate selection answered
  with nothing. Device pickers (HID, serial, USB, Bluetooth) answered with "no device".
- Permissions: request **and** check handlers deny everything except `fullscreen` (YouTube/Twitch
  fullscreen buttons; the offscreen view never resizes) and `media`/`display-capture` for our own capture
  page. `local-network`, `loopback-network` and `local-network-access` are denied too, **but this protects
  nothing in Electron 44**: Electron disables Chromium's `LocalNetworkAccessChecks` in every renderer and
  `--enable-features` cannot turn it back on (wave-1 review, measured).
- Internal-address block (ADR-013 amendment, adapted to LNA being off):
  1. Literal checks in `will-navigate`, `will-frame-navigate`, `will-redirect`, the window-open handler and
     `webRequest.onBeforeRequest` for every request.
  2. **Every** http/https/ws/wss request with a name (documents, frames, fetch/XHR, images, beacons,
     WebSockets, scripts, media...) is resolved with the session's own resolver before it may start and
     cancelled if any answer is private, loopback, link-local, CGNAT, multicast, reserved, IPv6
     ULA/link-local, or IPv4 embedded in IPv6 (mapped, compat, NAT64, 6to4). Failure or a 5 s timeout also
     cancels (fail closed). Verdicts are cached per session and host for 30 s (failures 5 s, at most 1024
     hosts, concurrent lookups shared) and cleared with the session. Navigations started by the game are
     resolved before `loadURL`, so the screen keeps its page.
  3. Detector: `details.ip` on `onBeforeRedirect`, `onResponseStarted` and `onCompleted`; an internal
     address stops the screen, loads `about:blank` and emits `blocked-url` with `reason:'internal-ip'`.
     Skipped when the request goes through a proxy (`ses.resolveProxy` not `DIRECT`): `details.ip` is then
     the proxy's address (local filtering proxies, antivirus web shields and "game boosters" listen on
     loopback). Behind a proxy, a name the host cannot resolve is refused as `dns-via-proxy`.
  4. The server-side `UrlPolicy` (M4) stays authoritative.
  **Residual risk: DNS rebinding.** The host's lookup and Chromium's own lookup for the connection are
  separate: a name with TTL 0 that answers a public address to the host and an internal one to the
  connection (within the 30 s verdict window, or by racing the two lookups) still reaches the internal
  address; only the detector sees it, after the request was sent. Not covered either: WebRTC (no
  webRequest), and `<link rel=preconnect>`/DNS prefetch, which open sockets without a request.
- Site policy: `blocklist` entries (host and subdomains) apply to every frame; `allowlist` applies to the
  top-level document only, so allowed sites keep their third-party frames (consent, players). A POLICY
  change also re-checks what each screen currently shows.
- Shared screens use in-memory sessions from a pool; a partition is reused only after its cookies and
  storage, HTTP cache, auth cache, host resolver cache, connections and verdict cache are cleared.
- WebRTC: `default_public_interface_only` (no LAN addresses to IP loggers). Media keys and the OS media
  overlay are disabled (`HardwareMediaKeyHandling`, `MediaSessionService`).
- Native dialogs (print, file choosers) - Electron opens them even for offscreen windows and has no API to
  veto them:
  - every document response (main frame and iframes) gets a CSP `sandbox` **without `allow-modals`**, so
    Blink ignores `print()` (and `alert`/`confirm`/`prompt`) in the document and in every frame it creates,
    fresh `about:blank` iframes included. Kept: scripts, same origin (cookies, storage), forms, popups,
    pointer lock, presentation, orientation lock, top navigation after user activation, storage access,
    downloads (so they still reach `will-download`, which cancels them and sends `download-cancelled`;
    without it Blink drops them silently). Lost: setting `document.domain` throws, plugins. Measured on
    local pages only: YouTube/Twitch compatibility is unverified and must be checked live;
  - service worker scripts are refused (a worker could serve documents that never get the header);
  - the preload installs a guard in the page's main world before any page script, in every frame where it
    runs: `print()`, `showOpenFilePicker`/`showSaveFilePicker`/`showDirectoryPicker`, file-input
    `showPicker()` and file-input clicks neutralised, `navigator.serviceWorker.register` refused. In
    initial `about:blank` iframes (no preload) the parent's preload cancels file-input clicks; a
    file chooser also needs user activation, which only the controller's own client can give.
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
a host-monotonic pts (arrival time minus packet duration; jitter is left to the Java tap). The screen is
**engine-muted from CREATE until the capture runs**, and again when it stops or fails (then until the game
re-creates the screen, in OS mode per ADR-008). Loading the capture page and starting the capture each
time out after 10 s. Failures emit `capture-error`. Linux result: 100 packets/s, RMS 0.21 of a
0.3-amplitude sine. **Untested on Windows**, including the silent-source requirement.

## Tests

```
cd host
node --test test/*.test.js                       # unit tests; the integration tests skip themselves
AKW_ELECTRON=/path/to/electron node --test test/integration.test.js
```

The integration test is a small Node "game" (`test/harness.js`): it listens on `127.0.0.1:0`, spawns the
Electron binary the way HostSupervisor will (stdin token, clean environment), under `xvfb-run` when there
is no display, adds `--no-sandbox` only when running as root on Linux, and loads `file:` fixtures from a
temporary directory (a sine WAV is generated, no media is committed). An http "public site" is served
through a loopback proxy with names mapped to a public address (`--host-resolver-rules`), so the host's
internal-address check passes and pages are real http documents. `AKW_HOST_DIR` runs the same tests
against another copy of the host app (A/B runs). It checks HELLO (token, pid), PING/PONG, STATE, frame
size/pixels/rate, a canvas rebuilt from dirty rects against a full repaint, a 4 s stalled reader, the input
contract (click, accents, Backspace, Enter, Space, a multi-line paste, wheel), page and iframe volume, the
guard, MEDIA/SYNC, every internal-address layer (literal, policy, DNS answer, fetch/img/WebSocket/beacon by
name with zero hits on the listener), a NAVIGATE race, popup gating, window.open, fullscreen, the page
size cap, RENDER, RESIZE, two screens, DESTROY, deny-all after a malformed POLICY, the public site through
a loopback proxy (no false internal-ip, `dns-via-proxy`, CREATE volume 0.1 on a still-loading page at 0.1
from the first report, MEDIA during load, print in a fresh iframe returning at once, service workers
refused, default UA), device scale factor under a simulated 2x display, no Chromium log lines on stderr,
partition churn (flat memory, nothing carried over), exit on socket close, exit on parent death, parent
PID 0, the env-token fallback, exit codes 65/66/68 (including a malformed WELCOME), and SPATIAL PCM.

## Not run / open issues

- Nothing was run on Windows: stdin token delivery to a GUI-subsystem exe, Defender/SmartScreen on the
  unsigned exe, firewall prompts, Windows OSR at 125%/150% display scaling, audio, `wc.focus()`
  behaviour, the packaged allowlist with the renamed exe (it cannot run as root on Linux, which needs
  `--no-sandbox`).
- No real website was loaded: YouTube/Twitch under the CSP sandbox header, volume/MEDIA/ad selectors on
  real players, live-edge numbers.
- DNS rebinding, WebRTC and preconnect (see the security posture) stay open.
- Electron skips the preload in an iframe whose initial `about:blank` document was touched (by anyone,
  including the page itself) while its first same-origin navigation was pending; such a frame gets no
  gain, sampling or guard (the engine mute and the CSP sandbox still hold). The host itself never
  touches frames that have a `src`/`srcdoc`.
- Linux tier 2 as root cannot use a packaged host (it would need `--no-sandbox`, which the allowlist
  refuses).
