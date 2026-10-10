# AkashicWeb: engineering rules

Scope: this repository. Changes needed in AkashicFM (for example the external-audio API) are made in that repository, with its own gates.

## Runtime and product contracts

- Minecraft 1.7.10, Forge 10.13.4.1614, Java 8 source compatibility via Jabel, **class major 52** for every shipped class, including `org.cef`.
- Clients: Java 8 with LWJGL2, and Java 17-25 with lwjgl3ify (2.x GLFW in GTNH 2.8.x, 3.x SDL3 in GTNH 2.9). Support both.
- GTNHLib and UniMixins are dependencies. Hodgepodge, Angelica, lwjgl3ify and AkashicFM are compatibility targets reached by reflection with availability checks, never hard dependencies.
- Mixins only where Forge has no hook. Each mixin narrow, namespaced and documented with the reason.
- Browser engine: CCBlueX java-cef built by our own CI against a pinned CEF distribution (OBS 7871 with codecs; Spotify 152 codec-free fallback). libjcef must match the exact CEF build. `org.cef` is never relocated.
- Package rule: only `client.web` touches `org.cef`; `common` holds no CEF types; the dedicated server never loads natives or download code.
- Rendering: the TESR only draws; texture upload happens once per frame at `RenderTickEvent` START from our own GL11/GL12 classes; reset unpack state after uploads; never assume Angelica tracks it.
- Networking: the server is authoritative; every client packet is validated (rights, dimension, range, rate limit, length caps); sends only from the main thread through a queue.
- Security: never `--disable-web-security`; site policy (allowlist/blocklist) and `UrlPolicy.isInternal` on server and client; no devtools, downloads, remote debugging; password fields are not relayed; every URL change goes to the audit log.
- Natives: pinned manifest, SHA-256 before extraction, canonical-path checks, atomic install outside `mods/` and outside the working directory (`%LOCALAPPDATA%` on Windows), consent before download.
- Failure is never silent: every failure becomes a reason code on the screen and in `/web doctor`; a client without the browser still joins and plays.

## Evidence and release discipline

- Distinguish AUTHORED, STATIC, BUILD, AUTOMATED, LIVE-OBSERVED, LIVE-APPROVED. State the OS for AUTOMATED results.
- Windows behaviour is proven only on real Windows PCs. A Linux/Xvfb harness never approves a native feature for Windows.
- Add regression tests for repairs; a historical pass does not approve a changed tree.
- Record SHA-256 for every native bundle and release jar; do not commit binaries, logs or captures.
- No release tag, "stable" wording or production deployment without the gate evidence in `docs/PLAN.md`.
- Report uncertainty and blocked validation; never claim zero defects or guaranteed performance.
- Review passes by one assistant are not independent external reviewers.

## Licences

- Web Displays (montoyo) is public domain: reused code keeps montoyo's credit and file headers.
- The CinemaMod/Mysticpasta1 Web Displays fork is MIT: keep the notice for anything borrowed.
- CinemaMod MCEF is LGPL-2.1: ideas only unless shipped as a separate, unmodified-or-published library.
- `net.montoyo.mcef.*` has no licence grant: never copy it.
- CEF, java-cef and CCBlueX java-cef are BSD: keep their LICENSE files with any distribution.
- Codec-enabled CEF (H.264/AAC) carries patent uncertainty: follow the owner's decision P-03 on how it is distributed.

## Working practice

- Preserve local modifications you did not make; never reset, clean or stash another worker's changes.
- Keep reads bounded and writes small; after a transport failure, revalidate state before retrying.
- Do not bypass security or tool denials.
