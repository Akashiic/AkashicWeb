# 2026-10-13: first browser on an in-world screen, two players in sync

Grade: **LIVE-OBSERVED (Linux dev clients, not Windows, not the Forbidden pack)**.

## Setup

| Item | Value |
|---|---|
| Mod | `akashicweb-f8e1487.jar` (end of wave 2), SHA-256 `c84b9d7520d72ed01b5b91a88d4df84ed1d10eff5a3ce2141989dfeaf444f647`, on the server and in two RFG dev clients |
| Server | The owner's Crucible build on Java 21 with UniMixins and lwjgl3ify, no owner plugins (see [the boot evidence](2026-10-13-crucible-boot-and-dev-client.md)) |
| Site policy | Switched to BLOCKLIST for the test (`/webadmin policy reload`) |
| Clients | Java 8, LWJGL 2.9.4, Forge 10.13.4.1614 |
| Displays | Xvfb + Mesa llvmpipe, one display per client; `xrandr` and `xdotool` installed only for the harness |
| Players | Two Linux users (Chromium refuses to run as root, and a packaged host refuses `--no-sandbox`); the proxy CA was added to each user's NSS store |
| Network | Through the container's egress proxy |
| Engine | Official Electron 44.7.0 linux-x64, installed by the mod from its import folder |

## Observed

1. **Install.** The installer took the zip from the import folder, checked it and installed it in about 6 s (43 files kept, 287,441,210 B). The state went DOWNLOADING → READY.
2. **Host.** The host started when the screen got its first URL: `host 0.1.0 (electron 44.7.0, Chrome 152.0.7977.130, linux-x64, protocol 1.0)`. `/awdoctor` then showed READY, a 0 ms ping and 376 frames uploaded with 0 failures, at p50 0.37 ms / p95 1.33 ms per upload (software GL).
3. **Fixed bugs.**
   - The placeholder text no longer shows "Format error".
   - The owner gets the address-bar buttons through the server-side UUID (D-016).
   - Crucible registers `akashicweb.web` / `akashicweb.webadmin`.
4. **Pages on the in-world screen.**
   - `https://example.com/` rendered upright and framed, with a scrollbar.
   - The mouse wheel on the screen scrolled it.
   - The screen keyboard (world still visible, "Typing on the screen — Esc to leave") followed a link with Tab + Enter. The controller's client navigated and the server recorded epoch 2.
5. **YouTube.**
   - The home page rendered in Portuguese ("Que tal uma pesquisa para começar?"), so the pt-BR locale and the new CSP sandbox header did not break it.
   - A watch page showed "Vídeo indisponível". Plain headless Chromium from the same container gets Google's "unusual traffic" page, so this is the container's IP being bot-walled (AW-22), not the mod.
6. **Video.** A public H.264 MP4 played on the screen, with successive frames differing through to the 0:10 end.
7. **Two players.**
   - Player 2 saw the same page on their own browser and saw player 1.
   - As a visitor, player 2 was refused interaction with a notice.
   - When player 1 took control (address bar → Take control → "Controlled by: you") and changed the URL, player 2's screen followed.
   - With a 10-minute VP9 video, player 2's follower reached `IN_SYNC`. The sync error was p50 11 ms / p95 32 ms over 240 samples, steady state, with both clients on one machine (a best case with no WAN latency). Including the start-up convergence it was p50 7 ms / p95 179 ms over 178 samples.
8. **Load per client** (software rendering): Minecraft about 150% CPU; Electron renderer about 7% CPU / 140 MB RSS; host main about 2-4% CPU / 300-350 MB RSS.

## Findings for wave 3 (UX)

- **Media sync waits for a controller.** Setting the URL from the address bar does not take control, so followers play on their own until someone presses Take control. A SHARED screen with no controller should give control to whoever sets its URL.
- **Refusal notice.** The notice for a refused visitor ends with the owner's name as its detail ("... on this screen. Developer"). It should read as the owner ("dono: Developer") or drop the name.
- **Direct media URLs.** A direct media URL plays at its native size in Chromium's media viewer, small on a big screen. Fitting it to the page would look better.
- **Window focus.** In-world interaction requires the game window to have focus (`inGameHasFocus`). That is correct, but the harness needs `xdotool windowfocus`.

## Not run

- Windows, the Forbidden pack, Angelica or shaders.
- Real YouTube or Twitch playback (bot wall).
- WAN latency between players.
- Sound: there is no audio device in the container.
- More than two players.

## Screenshots

Kept outside the repository; SHA-256:

| File | SHA-256 |
|---|---|
| v2-join.png | `2eb524c56697a2fd4a3c94922a34910552f99783de66034a6af5af9254f03f43` |
| v2-addr2.png | `6a930dabb2395dfc2727720b720f5b7b46bb87b52a91655f210026169d4ff44c` |
| v2-page2-keep.png | `8f49ff0aa68cb5ef394c5edee2bc617c811d4e630c6e692662f02e29c535e950` |
| v2-scroll.png | `57bc83e7cc613377166f513fb6661d261eaef69f581f59f9fdfe2bc813226b27` |
| v2-kbd.png | `b0dfbb879f460c8a2160783a1dec090d2961096d51ac7988e4cba7363a6ad3ca` |
| v2-link.png | `e07fadf4fdb6d4e8d54fad5f17a15ebb56422536cb3d50496dbc3aa99291e810` |
| v2-yt2.png | `2e255cfc9431def3dfadbff62747355af2b7d695fce7a77727632e321dfb3328` |
| v2-video2.png | `608474b06f7c0b9025ae6c81537c6efda72e7e55976312d4d997e9c11fbca8cd` |
| v2-mp4a.png | `9bac2dc9447a18daa5c98ff32304d67b68d29c0b7d9e4b4d76edebd32b79fbe9` |
| v2-mp4b.png | `5c632660f408508b6e2534e9afc392c27b19a99f59b0d9d93e67c53e19ef01e4` |
| p2-view1.png | `54e89497cdb591643495be287db2f467196efe3e2ef2a37ab6b6bcf3235874ed` |
| p1-addr2.png | `681e8b4d4adbb395858ea9065e6e2e1a0779f429eca0c778ce754d5e9428b193` |
| p2-bbb.png | `3f4e49b9b890ec0e56d682907b6fd66a2a860a24ef440ce84a1b188bf05e6dfa` |
| p2-wm1.png | `b69148c4b7de073d8ec0a4f707015de8dcc284a1ec378a20c669af6fd3462f57` |
| p2-wm2.png | `7b86e6614deb9dccf45a4f0bb9d5413e71ba76dd2aff6a37b21ffe5262a79ee1` |
| p1-wm2.png | `dd5b7b0d1fa2ab5f9488e08a0d6e711e01d084145932ef244faa0c1abd373ede` |
