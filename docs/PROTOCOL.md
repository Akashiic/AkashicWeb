# AkashicWeb protocol v1

Two protocols, both versioned by `Protocol.MAJOR` (Java `com.akashiic.web.protocol.Protocol`, JS `host/src/protocol.js`). Grade: AUTHORED (2026-10-12); it implements ADR-003, ADR-010 and ADR-012 with the reviewer amendments.

## 1. Game ↔ AkashicWebHost (local IPC, client side only)

Grade: BUILD / AUTOMATED (Linux container, Electron 44.7.0): the Java side (`client.host`) and the host app (`host/`) implement this section; `RealHostEndToEndTest` runs them together. Nothing here has run on Windows yet.

### Transport and handshake

1. The client's `HostSupervisor` opens a `ServerSocket` on `127.0.0.1`, port 0 (ephemeral), backlog 1.
2. It spawns the host: `AkashicWebHost(.exe) --akw-port=<port> --akw-parent-pid=<pid> --akw-user-data=<dir> --akw-log=<file>` with an **explicitly built environment**: copy of the parent's environment minus every `NODE_*` and `ELECTRON_*` variable; no `--inspect`. The host app lives in `resources/app/`; `default_app.asar` is deleted at install (ADR-001 amendment).
   - `--akw-parent-pid` is 0 when Java cannot read its own PID; the host then skips the parent poll.
   - A **packaged** host (the renamed executable of an installed engine) refuses every argument that is not `--akw-*` and the variables `ELECTRON_ENABLE_LOGGING`, `ELECTRON_LOG_FILE`, `ELECTRON_ENABLE_STACK_DUMPING`, `ELECTRON_RUN_AS_NODE`, `NODE_OPTIONS`, `NODE_DEBUG` (exit 65). Test-only switches (`--akw-test-allow-file`) are honoured only when the host is not packaged. An unpackaged host (`electron <dir>`, development and tests) uses a denylist instead.
   - The host owns `--akw-log` (2 MiB plus one `.1` rotation); Chromium logging is cut to FATAL. Java writes the host's stdout/stderr to `host-stdio.log` next to it, rotated before each launch.
3. The 128-bit random token (32 hex chars) is written to the host's **stdin**, followed by `\n`, then stdin is closed. Fallback when stdin cannot be read: environment variable `AKW_TOKEN` set only in the child's environment; the host copies and deletes it in its first statement.
4. The host connects to `127.0.0.1:<port>` and sends `HELLO`. Java accepts exactly one connection, validates the token in constant time, checks `protoMajor == Protocol.MAJOR`, then sends `WELCOME`. A wrong token, a malformed or missing HELLO, or no connection within the connect timeout (15 s) closes the socket and records `HOST_HANDSHAKE` (relaunch with backoff). A different `protoMajor` records `PROTOCOL_MISMATCH` and disables the host until it is re-enabled.
5. Lifetime: the host quits on socket EOF and also polls `--akw-parent-pid` every 2 s and quits if the parent is gone. Java treats EOF as a host exit: placeholder, relaunch with exponential backoff (1 s, 2 s, 4 s … max 60 s), crash-loop guard after 5 exits in 10 minutes.
   - Java sends PING every 10 s, one outstanding. A PING unanswered for 4 intervals (counted, not wall time) closes the connection as `HOST_STALLED`.
   - DOWN reasons: EOF or reset `HOST_EXITED`; a framing or message violation `HOST_PROTOCOL_ERROR`; a full send queue or an unanswered PING `HOST_STALLED`; a process that cannot start `HOST_LAUNCH_FAILED`; an Error in Java's own threads `HOST_EXITED`.
   - When every exit in the crash-loop window was a launch failure, the host is disabled with `HOST_LAUNCH_FAILED` instead of `HOST_CRASH_LOOP`. A kill ends the host's whole process tree.
6. Replay: after each connect Java sends WELCOME, then CREATE for each screen with its current size, fps and latest URL (re-checked against the policy), then AUDIO and RENDER if they were ever set. Input, actions and SYNC are dropped while the host is not READY.

Host exit codes: 0 normal; 64 bad `--akw-*` argument or profile directory; 65 forbidden switch, or (packaged) any non-`--akw-*` argument or forbidden variable; 66 no token; 67 could not connect within 5 s; 68 no WELCOME within 10 s, protocol major mismatch or malformed WELCOME; 69 bytes that are not protocol v1; 70 uncaught exception or child crash loop. BYE reasons: `quit`, `parent-gone`, `protocol-error`, `protocol-mismatch`, `bad-welcome`, `welcome-timeout`, `internal-error`, `child-crash-loop-<type>`, `signal-<SIG>`.

Loopback TCP is the only frame transport (amendment to ADR-003: stdio and Java `RandomAccessFile` named pipes are rejected for frames).

### Framing

Every message has a 20-byte big-endian header followed by `length` payload bytes:

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | magic `0x414B5731` ("AKW1") |
| 4 | 2 | type (table below) |
| 6 | 2 | flags (0 for now) |
| 8 | 4 | screenId (0 = host-level message) |
| 12 | 4 | seq (per direction, per screen, wraps) |
| 16 | 4 | length (payload bytes, ≤ 16 MiB; larger closes the connection) |

JSON payloads are UTF-8 without BOM. Unknown types are ignored (forward compatibility within a major).

### Host → game

| Type | Name | Payload |
|---|---|---|
| 1 | HELLO | JSON `{token, pid, protoMajor, protoMinor, hostVersion, engine:"electron", engineVersion, chromeVersion, platform}`; `pid` is the host's process ID (Java 8 on Windows needs it for the PID file and the tree kill) |
| 2 | FRAME | binary: `u16 width, u16 height, u16 x, u16 y, u16 w, u16 h` then `w*h*4` bytes BGRA (premultiplied, row-major, stride `w*4`) for the dirty rect `(x,y,w,h)` of a `width×height` page. A full frame has `x=y=0,w=width,h=height`. `width`/`height` always equal the last accepted CREATE/RESIZE size (device scale factor 1). A frame that fails to send is followed by a full frame. Java holds at most 4096 per side and 4,194,301 pixels: frames of a larger page are skipped and fail only that screen; an inconsistent rect or length closes the connection (`HOST_PROTOCOL_ERROR`). |
| 3 | PCM | binary: `i64 ptsMicros` (host monotonic), `u32 sampleRate` (48000), `u16 channels` (2), `u16 frames`, then `frames*channels` signed 16-bit samples interleaved. Header fields are big-endian, samples little-endian; at most 1 MiB (longer closes the connection). |
| 4 | STATE | JSON `{url, title, loading, canGoBack, canGoForward, audible, cursor}` (sent on change) |
| 5 | MEDIA | JSON `{t, mediaTime, paused, rate, duration, live, behindLiveEdge, adShowing, fullscreen}` at about 4 Hz on **every** painted or audible screen with media (controller and followers), also while the page loads; `t` = host monotonic arrival time in ms; only these fields, finite numbers |
| 6 | EVENT | JSON `{kind, detail}`; kinds: `load-error`, `blocked-url` `{url, host, reason, via[, ip]}`, `renderer-gone`, `unresponsive`, `responsive`, `capture-error`, `download-cancelled`, `permission-denied`, `screen-limit` (`{reason:"size", width, height, maxWidth:4096, maxHeight:4096, maxPixels:4194301}` or `{reason:"count", limit:16}`), `policy-error` (`{error, applied:"deny-all"}`, on screen 0). `blocked-url` reasons: `malformed`, `too-long`, `scheme`, `credentials`, `internal`, `not-allowed`, `blocked`, `internal-dns`, `dns-failed`, `dns-via-proxy`, `popup`, `internal-ip`. Only `blocked-url`, `permission-denied` and `download-cancelled` are rate-limited (burst 20, then 5/s, with `{suppressed:n}` summaries every 2 s). |
| 7 | PONG | JSON `{nonce, hostTimeMs}`; `hostTimeMs` is optional and Java ignores it |
| 8 | BYE | JSON `{reason}` before a clean exit |

Java also raises two EVENTs locally, never on the wire: `blocked-url` `{reason, code, host, via:"client"}` when its own URL check refuses a URL, and `load-error` `{reason:"frame-too-large", code:"TEXTURE_TOO_LARGE", width, height, maxSide, maxPixels}`.

### Game → host

| Type | Name | Payload |
|---|---|---|
| 32 | WELCOME | JSON `{protoMajor, policy, locale:"pt-BR", userAgent}`; `userAgent` is optional (the host keeps its stock Chrome UA); `policy` is the deny-all allowlist until the server's policy is known, and a null or missing policy means deny-all |
| 33 | CREATE | JSON `{width, height, fps, url, session:"shared"\|"private", audio:"os"\|"spatial", volume}` — screenId in the header is chosen by Java. Width and height 16–4096 and `width × height` ≤ 4,194,301, otherwise EVENT `screen-limit`. A spatial screen is engine-muted until its capture runs and after it fails; the game falls back by re-sending CREATE with `audio:"os"`. |
| 34 | DESTROY | empty |
| 35 | NAVIGATE | JSON `{url}` or `{action:"back"\|"forward"\|"reload"\|"stop"}` |
| 36 | INPUT | JSON, one of: `{type:"mouseMove",x,y}`, `{type:"mouseDown"\|"mouseUp",x,y,button:"left"\|"middle"\|"right",clickCount}`, `{type:"wheel",x,y,deltaX,deltaY}`, `{type:"keyDown"\|"keyUp",keyCode,modifiers:[...]}`, `{type:"char",text}`; coordinates in page pixels. Rules: keyDown `Space` types nothing (the game's char `" "` does); keyDown `Enter` types the newline and char `"\r"` is dropped; a char event may carry up to 4,096 code points (paste), `"\n"` is typed as Shift+Enter and `"\t"` as a tab; wheel `deltaY > 0` scrolls up (notches × 100). `mouseUp`/`keyDown` count as user activation: `window.open` is honoured only within 1 s of one, otherwise EVENT `blocked-url` reason `popup`. |
| 37 | POLICY | JSON `{mode:"allowlist"\|"blocklist", allow:[...], block:[...]}` (host-wide). A malformed POLICY switches the host to deny-all and sends EVENT `policy-error`. |
| 38 | AUDIO | JSON `{muted, volume}` (0..1, distance x MC master x records; applied to every frame from document start). The host engine-mutes a screen when the game mutes it or sends gain 0, while a spatial capture is not running, and on a new page until its preload has the gain (when the gain is below 1). |
| 39 | RENDER | JSON `{fps, painting}` (LOD: 30 near, 10-15 mid, 1-5 off-frustum; `painting:false` beyond range) |
| 40 | RESIZE | JSON `{width, height}`; same limits as CREATE (a refused RESIZE keeps the old size) |
| 41 | SYNC | JSON `{targetMediaTime, rate, paused, seek}` follower steering computed by Java from the server clock |
| 42 | PING | JSON `{nonce}` |
| 43 | QUIT | empty |

Java checks every URL with the server's `UrlGate` (`UrlPolicy(true, 2048)` plus the site policy) before CREATE or NAVIGATE: a refused CREATE goes out as `about:blank`, a refused NAVIGATE is not sent. The host checks again (site policy, then the internal-address block on every request, see ADR-013).

### Backpressure and threads

- Host: at most one pending FRAME per screen; a newer paint replaces an unsent one (latest wins). PCM is never dropped by the host; Java's ring drops oldest.
- Java: one daemon reader thread (`AkashicWeb-Host-Reader`) parses messages, applies FRAME crops to a per-screen canvas and publishes into a 3-slot ring with accumulated damage (ADR-004); it never touches Minecraft or World objects. Sends never block the game: messages go to a bounded queue of 4096 drained by the daemon thread `AkashicWeb-Host-Writer`; a full queue means `HOST_STALLED`. Whatever ends the reader or the writer closes the connection.

## 2. Server ↔ client (Forge SimpleNetworkWrapper channel `akashicweb`)

All client→server packets are validated on the server main thread: rights, dimension, reach and ray-plane hit, rate limit, length caps, controller lock, screen epoch; FakePlayers ignored; rejected packets are audited. Sends only from the main thread.

| Id | Direction | Name | Content |
|---|---|---|---|
| 0 | S→C | S2CHello | protoMajor, protoMinor, killSwitch, sitePolicy (mode, allow, block), serverTimeMs |
| 1 | S→C | S2CScreenState | full screen record (also via the TE description packet) |
| 2 | S→C | S2CUrl | screen key, url, epoch |
| 3 | S→C | S2CInput | screen key, epoch, input (controller's input relayed only to the controller's own client on SHARED screens, see ADR-012 amendment) |
| 4 | S→C | S2CMediaClock | screen key, epoch, mediaTime, rate, paused, serverTimeMs |
| 5 | S→C | S2CClockPong | nonce, clientSendMs, serverTimeMs |
| 6 | S→C | S2CNotice | reason code + args |
| 16 | C→S | C2SSetUrl | screen key, epoch, url |
| 17 | C→S | C2SNav | screen key, epoch, action |
| 18 | C→S | C2SPointer | screen key, side, u, v (0..1), button, action |
| 19 | C→S | C2SKey | screen key, type, keyCode, text, modifiers |
| 20 | C→S | C2SMediaSample | screen key, epoch, mediaTime, rate, paused, adShowing, live, behindLiveEdge, clientServerTimeMs |
| 21 | C→S | C2SControl | screen key, take/release controller |
| 22 | C→S | C2SRights | screen key, friends add/remove, rights bits, resolution, rotation |
| 23 | C→S | C2SClockPing | nonce, clientSendMs |
| 24 | C→S | C2SPreflight | opt-in: os, arch, java, launcher, gpu, ram, engine state |

Screen key = `(dimension, x, y, z, side)` of the multiblock origin. `epoch` increments on every URL change; packets with an old epoch are dropped.
