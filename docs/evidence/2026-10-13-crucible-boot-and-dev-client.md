# 2026-10-13: Crucible boot and first dev-client run

Grades:
- **AUTOMATED (Linux container):** the Crucible boot.
- **LIVE-OBSERVED (Linux dev client):** the in-game steps.

Neither is a Windows result, and the client was not the Forbidden pack.

## Crucible boot (06:38 UTC)

### Setup

| Item | Value |
|---|---|
| Server | The owner's Crucible `server.jar` (staging-67b53034), SHA-256 `cb79a231de9208f38ff0902bf1149a71a60363b143a16eff06af5ce73564fdee` |
| Java | OpenJDK 21.0.11, the owner's `java9args.txt`, `-Xmx3G` |
| Mods | UniMixins 0.3.1, lwjgl3ify 3.0.31, GTNHLib 0.11.51, AkashicWeb `b9e844c` (SHA-256 `e957e1d1c0c77fea9de10ff8e74a29fa0476f6f8b130ae5c88ecdffd7bdf7e36`) |
| Plugins and configs | None of the owner's plugins or configs (they hold credentials) |
| World and network | Fresh FLAT world, offline mode, bound to 127.0.0.1 |

### Libraries

Crucible downloads 73 libraries on first start. Three of them come from `github.com/juanmuscaria/maven/raw/...`, which this session's egress policy refuses (HTTP 403):
- `launchwrapper-1.13`
- `KImagine-0.2.0`
- `server-1.7.10`

They were taken from a sparse git checkout of that public repository instead:
- The SHA-1 matched the repository for `launchwrapper-1.13` and `server-1.7.10`.
- `KImagine` has no SHA-1 file there, but its MD5 matched the repository's `.md5`.

### Result

- `AkashicWeb b9e844c (protocol 1) starting`, then `Done (0.916s)`.
- The mod logged no warnings or errors.
- The other exceptions in the log are environment noise:
  - FML scanning `asm-*.jar`;
  - a GTNHLib client mixin target on the server;
  - the fresh server's missing `ops.json` and similar files.
- Both commands registered through Crucible's command group.
- **Finding:** Crucible builds the permission node as *group + command name*, so the nodes are `akashicweb.web.web` and `akashicweb.webadmin.webadmin`, while our log claims `akashicweb.web`. The fix is to register with the group `akashicweb`. This matters for the LuckPerms grant (P-13).

## Dev client against that server (07:17-07:22 UTC)

### Setup

| Item | Value |
|---|---|
| Client | RFG `runClient`: Java 8 (Temurin), LWJGL 2.9.4, Forge 10.13.4.1614 |
| Display | Xvfb with Mesa llvmpipe (OpenGL 4.5 compatibility profile) |
| Server | The Crucible boot above, without GTNHLib (the dev client does not have it) |

Harness needs:
- `xrandr`: LWJGL 2 reads display modes through it.
- `pauseOnLostFocus:false`: there is no window manager.
- A small XTest bot for clicks and keys.

### Observed

1. The client joined, and `S2CHello` arrived (`server hello: protocol 1.0 policy ALLOWLIST`).
2. A 4x3 wall of `akashicweb:screen` blocks placed with `setblock` formed on an empty-hand right-click:
   - server audit line `screen formed: screen[0:-43,4,236/2] 4x3`;
   - chat message `Screen formed: 4x3 blocks...`;
   - the tile renderer drew the placeholder card over the whole face, upright and framed.
3. Sneak + right-click opened the address bar GUI.
4. `/web info` printed the right record:
   - 4x3 blocks, page 982x720, owner `Developer`;
   - mode SHARED, no controller;
   - rights 31.

### Bugs found (fixed in wave 2 or at its integration)

- **Placeholder text "Format error: ... %s".** Reason texts carry one `%s`, and the client formats them without an argument.
- **Owner shown with no rights on their own client.** Every address-bar button was disabled because the client compared the owner with its own session UUID; on an offline-mode server (and on some proxies) the server-side UUID differs. The fix is the server-side UUID in `S2CHello` (D-016, AW-35), used for every owner, friend and controller check on the client.
- **Crucible permission node.** See above.

### Not run

- A browser on the screen: the dev build at `b9e844c` had no engine or host wired yet.
- Windows, the Forbidden pack, Angelica, shaders.
- More than one player.
- The owner's plugins.

### Screenshots

Kept outside the repository; SHA-256:

| File | SHA-256 |
|---|---|
| menu.png | `c516eed74eb95c028a208f47418354efe8b4c27c60657ecb3e58afe6a6c1de47` |
| ingame3.png | `af484cca2dc1b36a170eccc5abe1b0913f014e2ded875ad9d2f9329935b9a183` |
| formed1.png | `61ca70b44afc11462935fba6a004f280d3ad7e80fa1f475dd71099625a6aed26` |
| addr1.png | `972c97c7b5abb3ac448ca11b0099e31a283caed6c5fe45a5bc1ff7ed2f0b2fbe` |
| addr2.png | `78ce60023553c9d3020f93a42c7c4d823ddc3dbf997d17608125c6c480db08de` |
| webinfo2.png | `19c07a07d1b305a38fd9d5c7a92540ca09cfe5ec199f8caa25844c4aaba76407` |
