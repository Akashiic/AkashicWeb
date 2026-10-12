# AkashicWeb protocol v1

Two protocols, both versioned by `Protocol.MAJOR` (Java `com.akashiic.web.protocol.Protocol`, JS `host/src/protocol.js`). Grade: AUTHORED (2026-10-12); it implements ADR-003, ADR-010 and ADR-012 with the reviewer amendments.

## 1. Game ↔ AkashicWebHost (local IPC, client side only)

### Transport and handshake

1. The client's `HostSupervisor` opens a `ServerSocket` on `127.0.0.1`, port 0 (ephemeral), backlog 1.
2. It spawns the host: `AkashicWebHost(.exe) --akw-port=<port> --akw-parent-pid=<pid> --akw-user-data=<dir> --akw-log=<file>` with an **explicitly built environment**: copy of the parent's environment minus every `NODE_*` and `ELECTRON_*` variable; no `--inspect`. The host app lives in `resources/app/`; `default_app.asar` is deleted at install (ADR-001 amendment).
3. The 128-bit random token (32 hex chars) is written to the host's **stdin**, followed by `\n`, then stdin is closed. Fallback when stdin cannot be read: environment variable `AKW_TOKEN` set only in the child's environment.
4. The host connects to `127.0.0.1:<port>` and sends `HELLO`. Java accepts exactly one connection, validates the token in constant time, checks `protoMajor == Protocol.MAJOR`, then sends `WELCOME`. Anything else closes the socket and records reason `HOST_HANDSHAKE`.
5. Lifetime: the host quits on socket EOF and also polls `--akw-parent-pid` every 2 s and quits if the parent is gone. Java treats EOF as a host exit: placeholder, relaunch with exponential backoff (1 s, 2 s, 4 s … max 60 s), crash-loop guard after 5 exits in 10 minutes.

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
| 1 | HELLO | JSON `{token, protoMajor, protoMinor, hostVersion, engine:"electron", engineVersion, chromeVersion, platform}` |
| 2 | FRAME | binary: `u16 width, u16 height, u16 x, u16 y, u16 w, u16 h` then `w*h*4` bytes BGRA (premultiplied, row-major, stride `w*4`) for the dirty rect `(x,y,w,h)` of a `width×height` page. A full frame has `x=y=0,w=width,h=height`. |
| 3 | PCM | binary: `i64 ptsMicros` (host monotonic), `u32 sampleRate` (48000), `u16 channels` (2), `u16 frames`, then `frames*channels` signed 16-bit little-endian samples interleaved |
| 4 | STATE | JSON `{url, title, loading, canGoBack, canGoForward, audible, cursor}` (sent on change) |
| 5 | MEDIA | JSON `{t, mediaTime, paused, rate, duration, live, behindLiveEdge, adShowing, fullscreen}` sampled at 4 Hz on the controller's screen; `t` = host monotonic ms |
| 6 | EVENT | JSON `{kind, detail}`; kinds: `load-error`, `blocked-url`, `renderer-gone`, `unresponsive`, `capture-error`, `download-cancelled`, `permission-denied` |
| 7 | PONG | JSON `{nonce, hostTimeMs}` |
| 8 | BYE | JSON `{reason}` before a clean exit |

### Game → host

| Type | Name | Payload |
|---|---|---|
| 32 | WELCOME | JSON `{protoMajor, policy, locale:"pt-BR", userAgent}` |
| 33 | CREATE | JSON `{width, height, fps, url, session:"shared"\|"private", audio:"os"\|"spatial", volume}` — screenId in the header is chosen by Java |
| 34 | DESTROY | empty |
| 35 | NAVIGATE | JSON `{url}` or `{action:"back"\|"forward"\|"reload"\|"stop"}` |
| 36 | INPUT | JSON, one of: `{type:"mouseMove",x,y}`, `{type:"mouseDown"\|"mouseUp",x,y,button:"left"\|"middle"\|"right",clickCount}`, `{type:"wheel",x,y,deltaX,deltaY}`, `{type:"keyDown"\|"keyUp",keyCode,modifiers:[...]}`, `{type:"char",text}`; coordinates in page pixels |
| 37 | POLICY | JSON `{mode:"allowlist"\|"blocklist", allow:[...], block:[...]}` (host-wide) |
| 38 | AUDIO | JSON `{muted, volume}` (0..1, distance x MC master x records; applied to every frame) |
| 39 | RENDER | JSON `{fps, painting}` (LOD: 30 near, 10-15 mid, 1-5 off-frustum; `painting:false` beyond range) |
| 40 | RESIZE | JSON `{width, height}` |
| 41 | SYNC | JSON `{targetMediaTime, rate, paused, seek}` follower steering computed by Java from the server clock |
| 42 | PING | JSON `{nonce}` |
| 43 | QUIT | empty |

### Backpressure and threads

- Host: at most one pending FRAME per screen; a newer paint replaces an unsent one (latest wins). PCM is never dropped by the host; Java's ring drops oldest.
- Java: one daemon reader thread (`AkashicWeb-Host-Reader`) parses messages, applies FRAME crops to a per-screen canvas and publishes into a 3-slot ring with accumulated damage (ADR-004); it never touches Minecraft or World objects. Writes go through one writer lock.

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
