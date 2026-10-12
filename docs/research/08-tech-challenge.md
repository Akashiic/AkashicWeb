# Technology challenges (2026-10-12)

> Seven skeptical specialists, each trying to prove a better technology exists for one area. Raw: [`raw/09-tech-challenge.json`](raw/09-tech-challenge.json). Decisions: [`../adr/README.md`](../adr/README.md).

## Frame transport from CEF to the Minecraft texture, and rendering under Angelica, OptiFine and shader packs (MC 1.7.10, Forbidden Series pack)

**Recommendation:** `keep-with-changes` (confidence medium)

### Current choice assessment

VERDICT: the CPU path is the right transport for 10-25, but the plan as written has four defects and two gaps. Fix them before the 10-14 upload work.

STRENGTHS (evidence):
1. It is the only transport that works the same on every client stack the pack might have. Raw GL interop needs a real WGL context, and Angelica 2.2.29 adds an opt-in SDL_GPU backend (Vulkan/D3D12) that has none. That backend still emulates glTexSubImage2D, including the unpack state (TextureOps.java:45-66), so the CPU path also survives there. The list: LWJGL2 compat GL (Java 8), lwjgl3ify, OptiFine (no redirection), Angelica's GL backend (old betas and HEAD), Angelica SDL_GPU, Pojav (placeholder). VERIFIED (code).
2. Other projects ship it today: WATERMeDIA 2.1.x (whole frame, BGRA/8_8_8_8_REV, RenderAPI.java:152-159), CinemaMod MCEF (dirty rects with SKIP_*, MCEFBrowser.java:205-224), the CCBlueX CPU path (dirty-bbox packing, CefTextureRenderer.java:322-361), and OBS's CPU fallback (gs_texture_set_image). WATERMeDIA's PBO player sits in an `old` package. VERIFIED.
3. Bandwidth is small. 720p frame = 3,686,400 B (110.6 MB/s at 30 fps); 1080p = 8,294,400 B (248.8 MB/s at 30 fps). Producer copy measured here (Java 21, container, hot cache): 0.29 ms at 720p, 0.63 ms at 1080p. The earlier real-CEF probe measured 0.85-1.14 ms per 1080p frame. At 30 fps that is about 1-3.4% of one core. VERIFIED (container).
4. Uploading outside the TESR is correct: Angelica's shadow pass renders block entities again (ShadowRenderer.java:810). VERIFIED.

DEFECTS AND GAPS:
(a) Damage is lost. "onPaint copies dirty rects into a triple buffer; the render thread uploads only the newest frame" leaves stale regions twice over. A rotating slot that receives only frame n's dirty rects still holds frames n-1/n-2 in other regions. And the dirty rects of frames the render thread skips are never uploaded. INFERRED (logic). CEF says the buffer always holds the whole image (cef_render_handler.h), so a fix is cheap.
(b) The allocation format is not specified. Under Angelica SDL_GPU, a texture gets BGRA storage only when allocated with glTexImage2D(..., GL_RGBA8, ..., GL_BGRA, ...) (SDLGPURenderBackend.java:1511-1523). Otherwise every upload runs a per-pixel Java red/blue swap copy (PixelOps.java:269-283). VERIFIED.
(c) The plan says GL_CLAMP. Core profile removed it; Angelica HEAD remaps it (GLStateManager.java:7605-7616), but use GL_CLAMP_TO_EDGE. VERIFIED.
(d) A stray GL_PIXEL_UNPACK_BUFFER binding left by another mod would turn our client-memory upload into a PBO offset read. Angelica HEAD suspends that binding (GLStateManager.java:6177-6190); beta66b, vanilla and OptiFine do not. VERIFIED / INFERRED.
(e) `wasHidden` is not bound in CCBlueX java-cef fe8cc9e. Only wasResized/invalidate exist, and setWindowVisibility is a macOS-only no-op for OSR (CefBrowser_N.cpp:1633-1644). setWindowlessFrameRate does exist (CefBrowser.java:406). VERIFIED.
(f) PET_POPUP paints (`&lt;select>` dropdowns) are not handled. CinemaMod and CCBlueX both composite them. VERIFIED (their code).

OTHER RISKS:
- No upload time has been measured on any Windows GPU, and there is no per-frame budget when several screens update at once.
- The plan's default --disable-gpu-compositing may force software H.264 decode. A Xojo CEF report showed "Video Decode: Software only" with that flag (INFERRED generality). The same default also closes the shared-texture upgrade path.
- Research row 47 ("EXT_memory_object not fit for 1.7.10") is too pessimistic. WGL_NV_DX_interop2 driven from our own small native DLL does not depend on LWJGL. CCBlueX java-cef already exposes OnAcceleratedPaint with the D3D11 handle (render_handler.cpp:275-368), so the upgrade does not need a libjcef rebuild. VERIFIED.

### Recommendation detail

FOR 10-25: keep CPU OnPaint plus a direct glTexSubImage2D upload at RenderTickEvent START. No PBO and no accelerated paint for the event. Change the following.

1. Ring semantics (fixes the lost-damage defect). Use 3 slots (front = being uploaded, ready = newest, back = being written), each a direct native-order ByteBuffer allocated once. In onPaint (pump thread) the producer copies the FULL CEF frame into the back slot: 0.29 ms at 720p measured here, at most 1.14 ms at 1080p with real CEF. It then composites the last PET_POPUP image over its rect, ORs this frame's dirtyRects into a shared pendingDamage bbox, and publishes the slot (an atomic swap with ready; the oldest frame is dropped). The render thread takes ready plus the accumulated damage and uploads that bbox, or the whole frame when bbox area is at least 50% (CCBlueX's threshold). Skipped frames never lose damage.

2. Texture allocation and state:
   - Allocate with glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, (ByteBuffer) null). Never glTexStorage2D and never source format GL_RGBA; this keeps Angelica SDL_GPU on B8G8R8A8 storage with no per-pixel swap.
   - Filtering and wrap: GL_LINEAR, GL_CLAMP_TO_EDGE (not GL_CLAMP), GL_TEXTURE_MAX_LEVEL 0.
   - Reallocate only on resize; drop frames whose size does not match.

3. Upload hygiene, all from our own classes so Angelica redirects them:
   - GL11.glBindTexture.
   - If GL 2.1: save GL_PIXEL_UNPACK_BUFFER_BINDING and bind 0.
   - UNPACK_ALIGNMENT 4, ROW_LENGTH = frame width, SKIP_PIXELS/ROWS = bbox origin.
   - glTexSubImage2D(BGRA, 8_8_8_8_REV).
   - Reset ROW_LENGTH/SKIP to 0 and restore the PBO binding.
   - Wrap everything in try/catch so a failure draws the placeholder.

4. Budget and LOD:
   - At most about 8.3 MB uploaded per render frame across all screens by default (one 1080p frame), largest or closest screen first. A skipped screen keeps accumulating damage.
   - setWindowlessFrameRate: 30 near, 10-15 mid, 1-5 off-frustum. This already exists, so no native patch.
   - Also add a ~15-line N_WasHidden JNI to the 10-16 frozen native build, so hiding beyond the load range is possible later without a re-download. Test whether unhide glitches live HLS before using it.

5. Instrumentation from day 1 (needed for G3 evidence, shown in /web doctor):
   - per-upload render-thread time (p50/p95/p99) and bytes/s;
   - paints received vs uploaded vs dropped;
   - optional GL_TIME_ELAPSED query.

6. Shaders:
   - Keep the full-bright quad (lightmap 240/240). Under Angelica the TESR is drawn with gbuffers_block and again in the shadow pass; OptiFine 1.7.10 E7 and SwanSong are the other shader stacks the pack might have.
   - Accept some blocklight tint for 10-25, and test one pack (Complementary+Euphoria, Angelica's main target) at G3.
   - Investigate drawing the screen after the composite pass only after the event.

7. Decouple the GPU-compositing default from transport. Before keeping --disable-gpu-compositing as the default, measure on Windows whether it forces software H.264 decode (CPU% and chrome://gpu). GPU compositing is also a prerequisite for the accelerated upgrade.

AFTER 10-25 (upgrade path):
- Phase A (Nov, only if G3/G4 data shows upload p95 above about 1 ms on real PCs): a 3-slot persistent-mapped PBO ring (GL44.glBufferStorage, which LWJGL 2.9.1 has; ARB_buffer_storage is in 93-99% of 2022+ Windows reports). The producer writes directly into the mapped slot; the render thread polls fences. It falls back to step 3 when the extension is missing, under Angelica SDL_GPU (detected reflectively; PBOs are CPU-emulated there), or on any GL error.
- Phase B (Dec-Jan, Windows only): accelerated paint through a small native DLL, as in A4 (D3D11 copy in the callback, WGL_NV_DX_interop2 ring, adapter matching via --use-adapter-luid). Opt-in first, with a per-GPU allowlist and automatic fallback to CPU OnPaint. Do not adopt EXT_memory_object_win32 as primary: Intel has not implemented the D3D11 import, and AMD before 26.1.1 leaks.
- Phase C (2027): out-of-process host. Frames go in pagefile-backed shared memory (via the same DLL) or /dev/shm, with slot ownership signalled over the control pipe (not a Java 8 seqlock). The consumer code is unchanged.

REJECTED: YUV/NV12 for CEF (BGRA only; CPU conversion 3.5-5.7 ms), Linux dma-buf (needs an EGL context), a shared GL context on the pump thread (GLSM assumes one context and would mis-cache redirected calls), and render-thread pumping.

### Spike

SPIKE "T-UPLOAD" (about 0.5 day; run on the owner's Windows PC right after the G1 probe works, plus one Intel-iGPU laptop if available, before the 10-14 in-game work).

Setup:
- A standalone Java 8 app on LWJGL 2.9.1, the exact MC 1.7.10 library, with a Display window, a compatibility context, vsync off and a synthetic 60 fps scene of about 5 ms GPU load.
- CEF (the G1 build) loads two pages:
  - (a) a local 30 fps H.264 MP4 filling the viewport;
  - (b) a test page drawing a frame counter and a moving 1-px grid at random positions every frame.
- The A1 path (ring + damage + direct upload), and optionally A2 (persistent PBO) behind a flag.
- Runs of 120 s each at 1280x720@30 and 1920x1080@30.

Measure:
- render-thread time per upload (p50/p95/p99);
- frame-time p99 against a no-browser baseline;
- paints received vs uploaded;
- GPU time with GL_TIME_ELAPSED;
- correctness: every 30 frames, glGetTexImage the texture and compare it with the producer's latest full slot. Zero mismatched pixels and red stays red (BGRA order).

PASS:
- 720p30 direct upload p95 ≤ 1.0 ms and p99 ≤ 2.0 ms on the owner's PC (≤ 2.0/4.0 ms on the iGPU);
- frame-time p99 regression ≤ 2 ms;
- 0 pixel mismatches across 3,600 frames, including forced skipped frames (render loop artificially throttled to 20 fps).

Outcomes:
- If direct fails p95 and PBO passes, enable A2 for the event on the GL backend only.
- Also record CPU% of the browser process tree with and without --disable-gpu-compositing, plus chrome://gpu 'Video Decode', to settle the compositing default.
- In-game confirmation at G3: Angelica present (and SDL_GPU opt-in once), OptiFine if the pack uses it, one shader pack. The screen must update every frame, be uploaded once per frame (not in the shadow pass), and not be culled.

### Alternatives

| Option | Fits 1.7.10/Java 8 | H.264/AAC | Licence/cost | Maturity | Effort | Pros | Cons |
|---|---|---|---|---|---|---|---|
| A1. Direct client-memory glTexSubImage2D (current plan) with the fixes | yes | not-applicable | Our own code; GL is free. | Highest. In production in WATERMeDIA 2.1.x (Dec 2025), CinemaMod MCEF (Jun 2025) and CCBlueX mcef (Oct 2026). | 1.5-2 days, inside the 10-14 slot (ring + damage + popups + budget + instrumentation) | Works on every stack, including Angelica's SDL_GPU backend and OptiFine. Used by WATERMeDIA, CinemaMod and CCBlueX's CPU path. No new native code, small code, easy to reason about. | The driver copies on the render thread: INFERRED about 0.3-0.6 ms per 720p upload and 0.7-1.3 ms per 1080p upload, not measured on any GPU. A GPU readback still happens inside Chromium. Damage logic and popups must be done right. A full-frame producer copy costs 0.29-1.14 ms per frame off the render thread. |
| A2. PBO ring with persistent mapping (ARB_buffer_storage), glMapBufferRange as fallback | yes | not-applicable | Free | A standard technique (NVIDIA GTC 2012 Texture Transfers). Not proven in MC 1.7.10 mods. WATERMeDIA's single-PBO player sits in an `old` package and maps without orphaning. | 2-3 days plus 1-2 days of cross-GPU testing | Removes the driver memcpy from the render thread: about 0.05 ms of CPU per 1080p upload, with a 1.1-1.5 ms GPU-side copy that runs asynchronously (RTX 3070 report). Also removes our own extra copy, because the producer writes into the PBO. LWJGL 2.9.1, as shipped with MC 1.7.10, has GL44.glBufferStorage, GL_MAP_PERSISTENT_BIT and GL30.glMapBufferRange. lwjgl3ify provides GL44/ARBBufferStorage. | Fences must be created and polled on the GL thread, and slot ownership spans two threads, so it is easy to get wrong. Angelica beta-era builds do not track buffer bindings, so the binding must be restored by hand. Under Angelica SDL_GPU a PBO is a CPU staging copy, which makes it slower than A1. ARB_buffer_storage is missing on old Intel (GL 4.0-era) drivers. The gain is only worth it if A1's measured cost is a problem. |
| A3. Dirty-rect partial uploads with damage accumulation (buffer-age style) | yes | not-applicable | Free | Used by CinemaMod MCEF and CCBlueX. The buffer-age concept is standard (EGL_EXT_buffer_age). | 0.5 day (part of A1) | Large savings for normal browsing (cursor blink, hover, scrolling small areas): often under 5% of the frame. Required for correctness anyway once uploads are skipped. CEF already supplies dirtyRects. | No gain for the telão use case: a full-screen video is about 100% damage every frame. Rect-list uploads cost one GL call per rect, so use a bbox above a few rects. |
| A4. Windows accelerated paint: OnAcceleratedPaint -> native D3D11 copy -> WGL_NV_DX_interop2 GL texture | partly | not-applicable | Free (Windows SDK, our own DLL) | The technique is mature: Spout2 and mpv's dxinterop use NV_DX_interop, and OBS uses D3D11 shared handles natively. No Minecraft mod does this path; CCBlueX uses EXT_memory_object instead. | 6-10 days plus 1-2 weeks of field testing (post-event) | No CPU copies, no readback in Chromium, no PCIe round trip. Saves about 2 x 249 MB/s of bus traffic at 1080p30 (INFERRED) and the render-thread upload entirely. Works on LWJGL2 and lwjgl3ify alike because the WGL calls are native. Covers Intel, where EXT_memory_object D3D11 import is not implemented. The CCBlueX java-cef native side already delivers the HANDLE, so libjcef needs no rebuild and the 400 MB payload stays the same; only a small DLL is added. | Windows only. Not under Angelica SDL_GPU, Pojav or Linux. Needs GPU compositing (conflicts with the plan's --disable-gpu-compositing default). The CEF handle may only be used inside the callback, so the copy must happen there on the pump thread. Known NV_DX_interop problems: leaks on Intel with repeated wglDXOpenDeviceNV/registration (Spout reports), Optimus adapter mismatch, and NVIDIA FBO-attach failures (sampling-only use is safer). Shared texture is a per-browser setting, so fallback means recreating the browser. No time to field-test before 10-25. |
| A5. Windows accelerated paint via GL_EXT_memory_object_win32 (CCBlueX mcef approach) | partly | not-applicable | Free; CCBlueX mcef is LGPL-2.1, so reference only | Maintained by CCBlueX (mcef 70f3cc9, 2026-10-02) for MC 1.21/26.x on LWJGL3 with a per-vendor policy. | 4-6 days (native) post-event; not recommended as primary | Pure GL, no D3D11 device of our own. Production-proven by CCBlueX/LiquidBounce on NVIDIA and fixed AMD drivers. | Intel's Windows driver does not implement the D3D11 image import (Intel confirmed it is not implemented), although 80% of Intel reports list the extension. AMD before Adrenalin 26.1.1 leaks shared textures, and Polaris/Vega stay on 31.0.219xx. Needs GL 4.2+ and EXT_memory_object, with no LWJGL2 binding, so native code is needed anyway. CCBlueX gets around the callback-only rule by pumping CEF on the render thread, which our design avoids. |
| A6. Linux accelerated paint (dma-buf via EGL, or GL_EXT_memory_object_fd) | no | not-applicable | Free | Works in OBS (EGL renderer) and in CCBlueX on LWJGL3/EGL. | >10 days, lwjgl3ify-only; not recommended | Zero-copy on Mesa drivers. | Needs the game's context to be EGL. MC 1.7.10 LWJGL2 on X11 uses GLX, and CCBlueX reports UNSUPPORTED when the game has no EGL context. GL_EXT_memory_object_fd imports opaque fds, not dma-bufs. NVIDIA is opt-in only in both CCBlueX and OBS (OBS disables it on NVIDIA). Fails on hybrid GPUs (CCBlueX issue 8080). Linux is only Tier 2. |
| A7. Out-of-process browser host + shared-memory frames (memory-mapped slots + seqlock or pipe-signalled ownership) | partly | not-applicable | Free | The pattern Chromium itself uses (shared memory + IPC). No MC 1.7.10 precedent found. | 10-15 days post-event (whole host, not only transport) | Crash isolation (a libcef crash no longer kills Minecraft), the same upload code as A1/A2, no extra copy versus in-process (the host's copy replaces the triple-buffer copy: 0.275 ms at 720p, 0.626 ms at 1080p into /dev/shm here). The host can use a modern Java or C++ and LWJGL3. Accelerated handles can be duplicated across processes later. | On Windows, Java 8 FileChannel.map only maps files; dirty pages of a file-backed mapping are written back to disk (INFERRED) at up to 249 MB/s for 1080p30. A pagefile-backed named mapping (CreateFileMapping(INVALID_HANDLE_VALUE)) needs JNI. A cross-process seqlock in Java 8 needs Unsafe volatile access (no VarHandles; Unsafe memory access is being deprecated in newer JDKs), so pipe-signalled ownership is simpler. Input, audio and a supervisor must also be built. Too large for 10-25. |
| A8. YUV (NV12/I420) upload + shader conversion | partly | not-applicable | Free (VLC LGPL, FFmpeg LGPL if a decoder path were ever added) | Standard in players (mpv/VLC). Irrelevant for the CEF path. | Not recommended (would be 10+ days as a separate decoder path) | Cuts bandwidth by 62.5%: 720p30 drops to 41.5 MB/s, 1080p30 to 93.3 MB/s. | Does not apply to CEF: OSR delivers composited BGRA only. Converting BGRA to NV12 on the CPU costs 3.5 ms (720p) and 5.7 ms (1080p) here, about 10x the copy it would save. It only makes sense with a separate native video decoder (VLC/FFmpeg), which bypasses the browser and conflicts with D-001/D-002 (a real browser; YouTube/Twitch players). A custom shader on the TESR would also bypass Angelica/OptiFine gbuffers programs under shader packs. |
| A9. Frame-production control: per-screen windowless frame-rate LOD, WasHidden, external begin frames | yes | not-applicable | Free | CEF APIs; supported in CCBlueX java-cef settings (external begin frame flag present). | 0.5-1 day (frame-rate LOD); +0.5 day native WasHidden JNI; 2-3 days for external begin frames post-event | The cheapest saving overall: fewer paints, readbacks, copies and uploads for distant screens. setWindowlessFrameRate already exists in java-cef (1-60, dynamic), so no native patch. | WasHidden is not bound in CCBlueX java-cef, so a JNI patch is needed before the 10-16 buildId freeze. Hidden pages may get Chromium's background-video optimisations, which could glitch on unhide for live HLS (INFERRED). External begin frames change video pacing and need testing. |
| A10. Pump CEF on the render thread (CinemaMod/CCBlueX style) instead of a dedicated pump thread | yes | not-applicable | Free | Used by CinemaMod MCEF and CCBlueX mcef. | Similar to A1; not recommended | One fewer copy; no ring buffer. Accelerated import would be possible on the GL thread without D3D11 copy code (that is how CCBlueX does it). | CEF UI-thread work (callbacks, IPC, JS dialogs) runs inside Minecraft frames. Paint delivery is tied to MC FPS, so a world-load hitch or GC pause stalls the browser and any CefAudioHandler PCM path. The plan chose the pump thread for these reasons. |

### Open questions

- The exact Forbidden Series mod list. Is it Angelica (which version; OpenGL or the opt-in SDL_GPU backend?), OptiFine 1.7.10 HD_U_E7, SwanSong, or none? Which lwjgl3ify version for Java 17+ players? Angelica lists OptiFine as permanently incompatible, so the pack has at most one of them.
- The owner's GPU and the players' GPU mix (share of Intel iGPUs and old AMD Polaris/Vega). It decides whether A1's render-thread cost matters and whether Phase B helps many players.
- Does --disable-gpu-compositing force software H.264/AAC decode in the OBS CEF 150 build on Windows? A third-party Xojo report suggests yes. This drives client CPU cost and blocks shared textures.
- Does Chromium's background-video optimisation after WasHidden cause a black frame or jump when a live HLS screen is unhidden? This decides whether WasHidden or only setWindowlessFrameRate is used for off-frustum screens.
- Does Angelica 2.2.x TESR batching (RetainedTesrGroups/ModelPartBatcher) leave a Tessellator-drawn TESR with a per-frame-updated texture alone? This is expected (it targets ModelRenderer parts) but not verified.
- Which gbuffers program do OptiFine 1.7.10 E7 shaders use for TESRs, and how strong is the blocklight tint at lightmap 240 in common packs? Is a post-composite draw feasible under Angelica/OptiFine?
- For Phase B: does CEF 150's pooled D3D11 texture need a keyed mutex or fence? obs-browser has acquire/release commented out. Does WGL_NV_DX_interop2 sampling work on current Intel UHD/Xe drivers and on Optimus laptops with --use-adapter-luid?
- Is GL_PIXEL_UNPACK_BUFFER ever left bound by mods in this pack (no Angelica)? The defensive save/zero/restore covers it, but it should be observed once.

## Browser engine and embedding model (in-process JCEF vs separate browser-host process; which Chromium/engine)

**Recommendation:** `replace` (confidence medium)

### Current choice assessment

Current choice: in-process JCEF, using the CCBlueX java-cef fork built by our CI against OBS's codec-enabled CEF 150 (build 7871). CPU OnPaint goes into a triple buffer and is uploaded with glTexSubImage2D. A separate host process is planned only after the event (PLAN 4.1; T-01, T-03).

STRENGTHS
- The codec path is proven on Linux. CCBlueX fe8cc9e built against OBS CEF 150.0.19 with a 6-line patch played H.264/AAC MP4, MSE avc1/mp4a/mp2t, native HLS and WebRTC. (VERIFIED, research 05 line 75, Linux container only.)
- A standalone Java 8 JCEF OSR probe rendered 1080p at 29.7 and 59.3 fps. (VERIFIED, research 03, Linux.)
- Single process: one copy per frame (~1.1 ms at 1080p, VERIFIED research 03) and native CefAudioHandler PCM.
- BSD licence, and a detailed plan already exists.

WEAKNESSES
1. The longest pole is untested. Nothing has been built or run on Windows: no libjcef against the 344.8 MB OBS zip, none of the 4 native patches. (VERIFIED, PROJECT_STATE "What was NOT run".) The fork's JNI looks up classes that do not exist on Java 8 or LWJGL2: org/lwjgl/sdl/SDLKeycode and SDLMouse (VERIFIED, /home/user/ccbluex/java-cef/native/CefBrowser_N.cpp:1807,2100).
2. Every native fault runs inside javaw.exe of a client that is already fragile:
   - The field client is JVM 1.8.0_51 with LWJGL 2.9.1 (VERIFIED, /home/user/Akashic-Voice/docs/FIELD_EVIDENCE_2026-10.md:13).
   - Its logs show whole-client OOM crashes in 5 sessions and UnsatisfiedLinkError storms (VERIFIED, same file :30-35).
   - At a spawn telão, a libcef or JNI crash kills every attendee's game. The plan only mitigates this with guards (AW-05).
3. The Chromium sandbox is off: settings.no_sandbox = true (VERIFIED, /home/user/ccbluex/java-cef/native/context.cpp:211). This is on a public server where players choose URLs.
4. Security patching depends on OBS's CEF drops (Chromium 150.0.7871.252, VERIFIED research 05). Electron already ships 152.0.7977.130 (VERIFIED, releases.electronjs.org, 2026-10-07).
5. org.cef classes live in the game JVM:
   - They depend on Swing/EDT (VERIFIED, CefApp.java:16,237).
   - The JNI cached class loader runs under LaunchClassLoader.
   - org.cef names cannot be relocated, so the mod conflicts with any pack mod that bundles MCEF/JCEF. The pack's mod list is unknown (P-11).
   - DataPointer needs LWJGL3 MemoryUtil.
6. The Linux glibc floor comes from our own build host. CCBlueX's ubuntu-24.04 build needs GLIBCXX_3.4.31 / GLIBC_2.34 (VERIFIED, research 06 line 65).
7. Download size is 344.8 MB on Windows (VERIFIED, research 05).
8. The plan already calls the host process the right architecture (T-03). Shipping in-process now means a rewrite after the event.

CORRECTION TO THE BRIEF
obs-browser is not an out-of-process example. It calls CefInitialize inside the OBS process; obs-browser-page is only CEF's helper (VERIFIED, https://github.com/obsproject/obs-browser/blob/master/obs-browser-plugin.cpp). Real out-of-process examples:
- JxBrowser: "The browser engine runs in a separate process" (VERIFIED, https://teamdev.com/jxbrowser/).
- Steam's steamwebhelper (INFERRED, forum sources only).

OVERALL
The engine choice is technically sound on Linux. Running it in-process is the weakest part of the design for a public spawn event.

### Recommendation detail

DECISION: build the separate browser-host process now, not after the event, with the official Electron build as the 10-25 engine. Keep the protocol engine-agnostic, so a C++ CEF host (B) or JCEF in a child JVM (C) can be swapped in later.

Why now:
- It removes the schedule's longest pole: the Windows libjcef build against OBS CEF plus 4 JNI patches.
- It moves every native fault out of the fragile 8u51 game JVM (crash isolation at spawn).
- It turns the renderer sandbox on.
- It brings a newer, faster-patched Chromium: 152.0.7977.130 vs OBS's 150.
- It makes the mod pure Java 8: no org.cef, no JNI, no AWT. That makes the Java 8u51 / Java 17-25 / lwjgl3ify / Angelica and unknown-mod-list risks largely moot.
- Every core function was VERIFIED in this review on Linux: H.264/AAC, HLS, MSE, 30 fps OSR, Java 8-bytecode reader, PCM capture, ABNT2 input, process cleanup in both directions.
- Postponing means building in-process now and rewriting it after the event.

1) ENGINE PIN AND INSTALL
- Pin Electron 44.7.0 (Chromium 152.0.7977.130).
  - win32-x64 zip: 158,293,649 B, sha256 eee30dc8fa1f5ea95490e59f44e46ea68dd24c6e93d22facf70fe5c2d4c2665c.
  - linux-x64 zip: sha256 3ae7d5bdad61c664486c6ab61361dd084d064c8c08af8d13a687096e18ce555a.
- Re-pin to the newest 44.x patch on the 10-16 native freeze (T-10 still applies).
- The installer downloads from github.com/electron/electron/releases on the player's PC, so Akashic does not redistribute codec binaries. It keeps the existing PLAN 4.8 rules: SHA-256 before extraction, zip-slip guard, atomic install, LOCALAPPDATA.
- After extraction:
  - delete locales except pt-BR and en-US (saves about 47 MB);
  - rename electron.exe to AkashicWebHost.exe;
  - copy our hash-checked JS host app (main.js, a capture page, package.json) from the mod jar.

2) MINECRAFT SIDE (Java 8 bytecode, client only)
- HostSupervisor spawns the host with ProcessBuilder, passing a per-instance userData dir.
- It listens on 127.0.0.1:0 and checks a random token.
- One host process per client, many screens multiplexed. Frame header: screenId, w, h, dirty rect, seq.
- A reader thread fills a triple buffer of direct ByteBuffers. The render thread uploads with glTexSubImage2D at RenderTickEvent START (PLAN 4.3 unchanged).
- A PCM channel feeds AkashicFM (D-010, Stretch); the distance-volume fallback is unchanged.
- Input, navigation, policy and quit go out as commands.
- On host exit: placeholder, then relaunch with backoff. The crash-loop guard now counts host exits rather than game crashes. Kill switch and reason codes unchanged.

3) HOST (Electron main)
- One offscreen BrowserWindow per active screen:
  - setFrameRate(30), backgroundThrottling false, sandbox true, contextIsolation true, nodeIntegration false;
  - enableLargerThanScreen or a size workaround (the window is clamped to the display).
- app.disableHardwareAcceleration() by default (software output device, as in PLAN 4.9), with a GPU toggle.
- paint: crop to the dirty rect, then write to the socket with drop-oldest backpressure; stop painting hidden or far screens.
- Audio: one hidden capture window, setDisplayMediaRequestHandler({video: frame, audio: frame}). Use AEC/AGC/NS false and channelCount 2, stop the video track immediately, and use an AudioWorklet (not ScriptProcessor). Local echo stays disabled, so speakers should be silent.
- Input: sendInputEvent for keyDown/char/keyUp, mouse and wheel; ABNT2 text as char events.
- Policy and lockdown:
  - will-navigate / will-redirect plus session.webRequest: http(s) only; block internal addresses, including a post-DNS IP check where the webRequest details provide it.
  - setWindowOpenHandler: deny, then navigate in the same window (avoids the child-window CVE class).
  - Cancel will-download; deny every permission; no remote debugging.
  - Chrome UA via app.userAgentFallback; --autoplay-policy=no-user-gesture-required; never --disable-web-security.
  - crashReporter local only; app.quit() on socket close.

4) REMOVE FROM THE PLAN
- JCEF fork, CI and native patches 1-4.
- org.cef packaging and the major-52 check for org.cef; the CefApp conflict check.
- The Spotify-152 fallback, replaced by swapping in Electron's official codec-free ffmpeg-v44.7.0 (780 KB) if codecs must ever be dropped.

KEEP everything above the engine boundary: server authority, rights, sync, TESR and Angelica rules, site policy, installer rules, gates.

5) FALLBACK LADDER (decide at G1 on 10-12/13)
- (a) Electron, software OSR.
- (b) Electron, GPU OSR.
- (c) Same protocol with CCBlueX JCEF on OBS CEF 150 in a child JVM. This uses the Linux-VERIFIED recipe and needs the Windows CI build.
- (d) In-process JCEF only if the host model itself fails on Windows. Unlikely: JCEF also spawns helper exes.

6) AFTER 10-25
- Shared-memory frames through a tiny N-API addon, or OSR shared texture, to remove the ~14-point relay cost.
- Electron fuses (RunAsNode off).
- Evaluate the C++ CEF host (B) if host RAM/CPU on weak PCs is the bottleneck.
- macOS through the host process (no main-thread clash with lwjgl3ify).
- The ForbiddenMobile launcher could implement the same protocol with Android WebView.

7) SCHEDULE EFFECT
- The 10-11/10-12 Windows-CI work is replaced by an owner-run probe that needs no build (official zip, 4 JS files, FrameSink.jar).
- The engine G1 can run on 10-12. The protocol, HostSupervisor and installer fit in 10-12..10-15 (INFERRED 4-6 days total).

### Spike

G1-E (half a day, owner's Windows 10/11 x64 PC plus one Intel-iGPU laptop, no CI)

INPUTS
- The official electron-v44.7.0-win32-x64.zip; check its SHA-256 = eee30dc8...2665c.
- The probe app files from scratchpad/challenge/probe-electron/app (main.js, cap-preload.js, capture.html, package.json).
- FrameSink.class (major 52) from probe-electron/java.
- A local test page with the H.264/AAC MP4 and an HLS copy (media/page.html).
- Run FrameSink with the Technic Forbidden client's own javaw 1.8.0_51 (not a newer JDK). Also run once with the exe renamed to AkashicWebHost.exe.

PASS CRITERIA (every one must hold)
- (a) The host connects in under 2 s, 5/5 launches.
- (b) At 1280x720: at least 28 paints/s delivered to Java; Java reader thread at most 5% of one core.
- (c) Each plays with audible audio: the local H.264/AAC MP4, the HLS stream, a YouTube watch page, a YouTube LIVE, and a Twitch live, all with the Chrome UA set. Codecs on Windows are the main thing this checks.
- (d) PCM: RMS above 0, 48 kHz stereo. Speakers silent while capturing (local echo off).
- (e) The typed text 'ação ç $%&' comes back exactly; wheel and click register.
- (f) Process cleanup, measured in Task Manager:
  - kill javaw: zero AkashicWebHost/electron processes after 5 s;
  - kill the host: Java sees EOF in under 1 s.
- (g) No Windows Firewall prompt from the loopback listen. Defender does not quarantine.
- (h) Electron tree CPU at 720p30 at most 50% of one core with software OSR, and PSS/private memory at most 600 MB. Record the GPU-OSR numbers too.

NEXT STEP
Same probe inside the Forbidden client (Forge 10.13.4.1614, Java 8u51), uploading frames from the socket buffer with glTexSubImage2D at RenderTickEvent.
- Pass: median FPS drop at most 25% at a fixed position over 60 s, and no GL-state leaks with Angelica/OptiFine if present.

IF G1-E FAILS
On codecs or OSR: switch to fallback (c), JCEF in a child JVM behind the same protocol, by 10-13.

### Alternatives

| Option | Fits 1.7.10/Java 8 | H.264/AAC | Licence/cost | Maturity | Effort | Pros | Cons |
|---|---|---|---|---|---|---|---|
| A. Electron (official release) as a separate browser-host process, offscreen rendering, Java 8 IPC over a loopback socket | yes | yes | Electron is MIT and free. Chromium is BSD; ffmpeg is LGPL-2.1 (dynamic ffmpeg.dll). H.264/AAC patent exposure is the same grey area as OBS CEF, and the owner accepted codecs (D-011). Downloading from Electron's own GitHub release on the player's PC avoids Akashic redistribution. | Very high. Electron 44.7.0, 43.7.9 and 42.11.12 were all released on 2026-10-07. The OSR path gets active security fixes. Widely deployed. | Windows G1 probe: under 1 day (exists in scratchpad). MVP host plus Java HostSupervisor plus installer: 4-6 days (INFERRED). This replaces about 5-7 days of JCEF native CI, patches and adapter work, which also carries higher variance. | - No native build or CI at all. The official binaries already include H.264/AAC (proprietary_codecs=true).<br>- Chromium 152.0.7977.130 is newer than OBS CEF 150.<br>- Electron supports the latest three major versions, on an 8-week cadence, with frequent patch releases.<br>- The renderer sandbox is on by default. JCEF hard-codes it off.<br>- Crash isolation in both directions, VERIFIED on Linux.<br>- The Minecraft side is identical on Java 8u51, Java 17-25 and lwjgl3ify: java.nio plus glTexSubImage2D from a direct ByteBuffer.<br>- No org.cef in the game JVM, so no LaunchClassLoader, AWT or classpath conflicts.<br>- Linux floor is GLIBC_2.25 with no GLIBCXX dependency.<br>- Download: 158 MB on Windows vs OBS CEF's 345 MB.<br>- The engine sits behind a protocol, so it can be swapped later (C++ CEF, WebView2, Android WebView in ForbiddenMobile).<br>- Avoids the macOS main-thread conflict with lwjgl3ify (INFERRED).<br>- If codecs ever have to go, the fallback is swapping in Electron's official codec-free ffmpeg (780 KB).<br>- The owner can run the Windows G1 today with the official zip. | - Not yet run on Windows; Windows codecs are INFERRED from build args and DLL size.<br>- Frame relay costs an extra copy:<br>  - toBitmap takes 2.2-3.2 ms at 720p and 6.4 ms at 1080p on the Node main thread, plus 110-233 MB/s over the socket.<br>  - At 720p30 the measured cost is about +14 percentage points of one core. Several 1080p screens could saturate the main thread.<br>  - Mitigations: dirty-rect crop, pausing hidden screens, a 480p preset. After the event, shared memory through an N-API addon.<br>- Audio goes through a WebAudio hop (more latency than CefAudioHandler). It needs AEC/AGC/NS turned off (verified necessary) and the video capture track stopped.<br>- Node.js is a new language and codebase in a Java project.<br>- Memory: about 385 MB PSS per host, comparable to CEF.<br>- The offscreen window size is clamped to the display, so it needs a workaround.<br>- Electron UA token: set a Chrome UA.<br>- Linux sandbox needs SUID or userns, or --no-sandbox (same as JCEF).<br>- RunAsNode fuse hardening changes the binary hash.<br>- OSR had 2026 CVEs (all patched). Avoid child windows and shared-texture mode for now. |
| B. Own C++ CEF browser-host process (OBS CEF 150 codec build) with the same IPC protocol | yes | yes | CEF BSD; our code; OBS CEF binaries free; codec patents grey (D-011) | CEF is mature. The OBS codec drops are infrequent: 5060 (2022), 6533 (2024/25), 7871 (2026). | 6-9 days to MVP parity, higher variance (INFERRED). Best kept as the post-event efficiency path behind the same protocol. | - Most efficient host: one memcpy per dirty rect, no Node main thread, native planar-float PCM.<br>- Full control of CEF settings, multi_threaded_message_loop on Windows, and the exe manifest.<br>- Job Object, helper naming and parent-death watch are trivial in C++.<br>- No JNI.<br>- Same crash isolation and Java-version independence as A. | - Still needs a Windows CI build against the 344.8 MB OBS zip (not yet done), plus C++ debugging on Windows with only the owner as tester.<br>- Patch cadence is tied to OBS's CEF drops (Chromium 150).<br>- Sandbox would have to be wired up by us.<br>- obs-browser is GPL-2, so it is a reference only, no code copying.<br>- More code than A for the same features. |
| C. CCBlueX JCEF in a child JVM (current engine, moved out of process) | partly | yes | BSD (java-cef); OBS CEF free; codec grey | CCBlueX fork active (fe8cc9e, 2026-10-02) but LiquidBounce-oriented; natives hosted on LiquidBounce servers; must be rebuilt by us | 5-8 days (native CI plus patches plus IPC) (INFERRED) | - Smallest conceptual change from the current plan; reuses the Linux-VERIFIED build recipe and Probe152.java.<br>- Crash isolation.<br>- No org.cef in the game JVM.<br>- With a bundled JRE 21, the LWJGL3 MemoryUtil / SDL assumptions of the fork could be satisfied instead of patched (INFERRED). | - Keeps the riskiest item: the untested Windows libjcef build against OBS CEF.<br>- Adds a JVM per client: more RAM and a slower start.<br>- On Minecraft's Java 8u51 it still needs the DataPointer and SDL-keycode patches. Bundling a JRE adds about 40-50 MB (INFERRED).<br>- Still no_sandbox.<br>- Still on OBS's Chromium 150. |
| D. Microsoft Edge WebView2 host process (Windows only) | partly | yes | Free runtime (Microsoft terms); OS codecs licensed by Microsoft (INFERRED) | Runtime mature and auto-updated; the OSR feature request has had no Microsoft update since 2022 | 8-15 days, Windows only, plus a second engine for Linux; not feasible for 10-25 (INFERRED) | - Runtime is preinstalled: included in Windows 11 and on the vast majority of Windows 10 (a 0 MB download for most players).<br>- Microsoft keeps it patched (evergreen) and it runs sandboxed.<br>- H.264/AAC come through OS codecs licensed by Microsoft (INFERRED), which removes the codec-patent question on Windows. | - No supported offscreen rendering: issue #547 open since 2020; the last Microsoft statement was 2022-04 ('starting on the design phase').<br>- The capture workaround is poorly documented; frame rate tied to vsync; focus stealing reported with SendMouseInput.<br>- No audio capture API. WASAPI process loopback needs build 20348+, so it likely excludes consumer Windows 10 (INFERRED).<br>- No Linux, no Java binding.<br>- Debloated Windows images may lack the runtime (INFERRED). Fixed Version is over 250 MB.<br>- Evergreen updates make behaviour drift. |
| E. Headless Chromium or the player's installed Edge/Chrome driven over the DevTools Protocol (Page.startScreencast) | yes | yes | Free; uses the user's browser | CDP stable; screencast semi-official; Edge/Chrome behaviour drifts with auto-updates | 3-5 days for a labelled 'lite mode' safety net after the event; not primary (INFERRED) | - Zero download for most Windows players.<br>- Licensed codecs (Edge/Chrome).<br>- Sandboxed and auto-patched.<br>- The CDP Input domain is focus-independent.<br>- Measured 30 fps at 720p on a 30 fps H.264 page. | - JPEG quality loss (q80, ~43 KB/frame), plus decode CPU in Java: 6.3 ms/frame with ImageIO on JDK 21, more on 8u51 (INFERRED).<br>- Frames are event-driven.<br>- No audio through CDP: needs JS WebAudio capture, so no clean 3D audio.<br>- Depends on the user's browser version and policies; Edge may be removed on debloated systems.<br>- The remote-debugging surface must stay on a pipe. |
| F. JxBrowser (TeamDev) | no | yes | Indie $1,979; Project from $8,199; Enterprise from $29,599 (prices exclusive of tax); no free open-source licence advertised; plus Via LA H.264 licence per TeamDev docs | Active commercial product (9.5.3) | 6-10 days plus licence purchase; blocked by Java 17+ and no PCM (INFERRED) | - Out-of-process architecture.<br>- Commercial support.<br>- Proprietary codecs can be enabled. | - Current 9.5.3 supports only Java 17/21/25, so it cannot load in Java 8 Minecraft; it could only run in a child JVM.<br>- Indie licence $1,979, Project from $8,199.<br>- Codecs require the licensee to hold Via LA licences; TeamDev disclaims responsibility.<br>- Audio API is only mute and playing events (no PCM capture for 3D).<br>- No documented raw-frame callback for custom GL rendering found. |
| G. Ultralight | unknown | unknown | Free (&lt;$200K turnover; no JIT, no media); Indie $395 (&lt;$1M); Pro $4,500 (&lt;$10M); Enterprise custom | Active small vendor; web compatibility far below Chromium | 10+ days with a high chance of failing YouTube/Twitch; not recommended (INFERRED) | - Small, game-oriented, offscreen-first.<br>- Cheap Indie tier. | - The free tier has no media playback at all.<br>- Third-party integrators report 'limited web page support' and no audio capture.<br>- No evidence that YouTube, YouTube live or Twitch players work (MSE/H.264/AAC unknown).<br>- Not a 'real browser' in the sense of D-001/D-002.<br>- Java bindings' maintenance unknown. |
| H. Servo / Ladybird | no | unknown | Free (MPL-2.0 / BSD-2) | Early stage | Not feasible for 10-25 (INFERRED) | Modern, independent, permissive licences; Servo has an embedding API | - Servo 0.5 (2026-08-31) is celebrated for rendering the DuckDuckGo landing page. Full-web parity is not a goal; YouTube is cited as a gap.<br>- Ladybird: alpha for Linux/macOS only, beta 2027, stable 2028, no embedding API, no Windows.<br>- Neither can carry YouTube live or Twitch on 10-25. |
| I. WPE WebKit / WebKitGTK | partly | unknown | LGPL/BSD, free; codecs from system GStreamer | Active (Igalia), embedded-focused | 6-10 days, Linux only; not useful for Tier 1 (INFERRED) | - Offscreen-first design (WPE).<br>- GStreamer can decode H.264/AAC where the distro has the plugins. | - No Windows (Tier 1) port.<br>- Codec availability depends on each Linux distro's GStreamer plugins.<br>- YouTube/Twitch behaviour on WebKit differs from Chromium.<br>- Needs a native host anyway. |
| J. Gecko / Firefox embedding | no | unknown | MPL-2.0 | Engine mature; desktop embedding abandoned | Not feasible (INFERRED) | Independent engine; Firefox has licensed H.264 through OpenH264 / OS decoders | No maintained desktop embedding or offscreen API. Firefox headless gives screenshots or WebDriver BiDi only, with no screencast or audio path. |
| K1. Upstream jcefmaven (stock CEF 152, codec-free) | partly | no | BSD / Apache | Active (152.0.6, 2026-09-30) | 1-3 days to embed, but fails the codec requirement | - Java 8 classes (major 52).<br>- Current CEF 152.0.6.<br>- Prebuilt for every desktop platform. | - No H.264/AAC, so YouTube live and Twitch fail (blocker).<br>- In-process.<br>- AWT/EDT pump.<br>- No CefAudioHandler.<br>- Installer does no checksum or path-traversal check. |
| K2. CinemaMod MCEF (CEF 116) | no | yes | LGPL-2.1 (mcef), BSD (java-cef) | Stale | Emergency only; 3-5 days with heavy patching (INFERRED) | - Hosted natives decode H.264/AAC.<br>- Ideas for render-thread pumping and dirty-rect upload. | - Chromium 116 (2023).<br>- Java 21/GLFW input API.<br>- --disable-web-security by default.<br>- The 'checksum' never hashes the archive.<br>- In-process.<br>- Stale: java-cef eaeb3d4 2024-10-22; mcef last commit 2025-06-17. |

### Open questions

- Windows runtime behaviour of Electron 44 is untested. H.264/AAC in the official win32 build is INFERRED from all.gn and the 3.1 MB vs 1.7 MB ffmpeg.dll size; OSR paint rate, sandbox and AV reaction also need checking on real PCs.
- Does Technic's bundled Java 8u51 trust the TLS chain of github.com release downloads (release-assets.githubusercontent.com)? The same question applies to OBS's CDN. It may need a bundled truststore or a host-side download. Not tested; this container only has JDK 21.
- Does a loopback-only ServerSocket from javaw trigger a Windows Defender Firewall prompt, and do Avast/Kaspersky interfere with 127.0.0.1 traffic or with an unsigned renamed electron.exe in LOCALAPPDATA? If so, switch transport to a Windows named pipe, which Java 8 can open with RandomAccessFile.
- Is the owner comfortable with a small Node.js host app (about 1k lines of JS) in the repository, with its own lint/tests, next to the Java mod?
- YouTube and Twitch with an Electron runtime and a Chrome UA from residential Brazilian IPs: any embedded-browser or bot checks? Not testable from this datacenter container. TLS to external sites was not attempted, because verification must not be disabled.
- Is the owner's codec acceptance (D-011, 'codec-enabled Chromium') meant to cover Electron's bundled ffmpeg the same way as OBS CEF? The legal grey area is identical.
- A/V sync: WebAudio capture adds latency (ScriptProcessor 4096 frames ~85 ms in the probe; AudioWorklet should cut it). How large a video delay ring is needed for lip-sync with AkashicFM output?
- Multi-screen cost: how many concurrent 720p30 screens can one Electron main thread relay on a low-end player PC before shared-memory frames (an N-API addon) become necessary for 10-25?
- Pack mod list (P-11): even with no org.cef in the game JVM, check for OptiFine/Angelica GL-state interactions with the TESR texture. This applies whatever engine is chosen.
- Process note: during the lifecycle tests in this shared container, I ran pkill -x java, pkill -x Xvfb and pkill -x electron (around 23:44-23:46 UTC). If a parallel agent's Java or Xvfb process died then, this is the cause. Probe sources live only in /tmp/claude-0/-home-user/c5c14dc0-9bf3-521b-b7c6-50e7f96d7b47/scratchpad/challenge/{probe-electron,probe-cdp,media,scripts/cpu.sh,scripts/life*.sh}. Copy them into akashicweb/spikes/ if kept; no repository was modified.

## Video codecs (H.264/AAC) and their legality: where the codec-enabled Chromium for YouTube live, Twitch and MP4 comes from

**Recommendation:** `keep-with-changes` (confidence medium)

### Current choice assessment

CURRENT CHOICE: CCBlueX java-cef built against OBS's codec-enabled CEF 150 (build 7871). Players' clients download the binaries from cdn-fastly.obsproject.com, with the SHA-256 pinned and no Akashic mirror.

STRENGTHS
1. It plays the target codecs. The earlier Linux spike played H.264/AAC, MSE MPEG-TS and native HLS (VERIFIED, research/05). This research confirms that Chrome branding of Chromium's FFmpeg adds exactly three components: the h264 decoder, the aac decoder and the aac demuxer. These are precisely what YouTube live and Twitch need (VERIFIED: chromium/third_party/ffmpeg chromium/config/{Chrome,Chromium}/win/x64 codec_list.c and demuxer_list.c, diffed today).
2. Provenance is anchored twice:
   - OBS master pins version "7871" with windows-x64 sha256 54cd191a…f341 (VERIFIED: raw obs-studio CMakePresets.json:54-66, fetched 2026-10-12).
   - The CDN serves that file today: 344,759,147 bytes, Last-Modified 2026-09-11, Fastly cache-control max-age 7257600 (VERIFIED: curl -I).
3. It is becoming OBS release-grade. PR #13900 ("Update CEF from 127/6533 to 150/7871") was merged 2026-09-18, and OBS 33.0.0-rc1 (2026-10-09) ships it (VERIFIED: github.com/obsproject/obs-studio/pull/13900 and the releases page).
4. Akashic does not itself distribute codec binaries.

WEAKNESSES
1. The build is opaque. We cannot rebuild or patch it:
   - obs-deps master f80e1d3 (2026-08-25) has no CEF scripts (VERIFIED: grep in /home/user/obsproject/obs-deps).
   - The obsproject/cef fork has no 7871 branch; its newest branches are 6533-* (VERIFIED: git ls-remote).
   - OBS describes using "an unofficial patch to CEF" and hosting on Fastly (VERIFIED: obs-studio discussion #3853).
2. Security debt:
   - Chrome 150 went stable on 2026-06-30. Chrome 155 went stable on 2026-10-06 and 156.0.8078.12 was already in the Stable channel on 2026-10-07 (VERIFIED: chromiumdash API). So on 10-25 we run a Chromium about 6 milestones old, with the sandbox off, on a public server.
   - OBS plans to update CEF roughly once a year (VERIFIED: WizardCM, 2026-05-24, discussion #3853).
3. Legal: it is not a "clean" path, only a "not our distribution" path.
   - OBS is not in Via LA's AVC licensee list; Google, Microsoft, Cisco, Spotify, Valve and Vuplex are (VERIFIED by name search of via-la.com/licensing-programs/avc-h-264; absence under other names is possible, so INFERRED).
   - The mod triggers the download. Brazil's Lei 9.279 art. 42 §1 lets a patentee stop third parties who "contribuam" to infringement (VERIFIED text; application INFERRED).
   - The plan's patent facts are too optimistic. Via LA's AVC Attachment 1 dated 2026-09-28 lists about 344 US patent numbers (some granted in 2026, US 12,6xx,xxx) and dozens of Brazilian patents and applications. Wikipedia cites US 9,356,620 as lasting "at least until November 2030" (VERIFIED). "Last major US H.264 patent ~Nov 2027" understates this.
4. Operational risks:
   - The file name cef_binary_7871_windows_x64.zip has no patch version, so OBS could replace it in place and break our pin (INFERRED).
   - We would hot-link a donation-funded CDN, about 345 MB per player, without asking (INFERRED etiquette risk). No OBS statement permitting or forbidding third-party use was found.
5. Platform gaps:
   - The Windows libjcef build and runtime are still untested (G1).
   - Linux needs either a 2.03 GB unstripped libcef.so on disk or an Akashic-redistributed stripped copy.

VERDICT: This is still the only option that can realistically play YouTube live and Twitch on Windows by 2026-10-25. It needs hardening, and a planned exit after the event.

### Recommendation detail

FOR 2026-10-25 (Windows Tier 1)
Keep OBS CEF 7871 + CCBlueX java-cef. Of the paths that are ready, this is the safest legal one that plays YouTube live and Twitch, because Akashic never distributes the encumbered decoders. Change the following:

1. Download and provenance.
   - The player's client downloads cef_binary_7871_windows_x64.zip straight from cdn-fastly.obsproject.com.
   - Check sha256 54cd191a3f56bb1f05be22b95e1fa8041061b38bd5fe94e0776fb34dd599f341 against both our manifest and OBS's CMakePresets.json:63.
   - Stream-extract only Release/ and Resources/.
   - No Akashic mirror by default.
   - Keep CEF's LICENSE.txt and CREDITS.html in the install folder (CEF is BSD and Chromium's FFmpeg is LGPL, so notices must ship; INFERRED).

2. Courtesy contact with OBS. Before the 10-16 native freeze, message the OBS maintainers who merged #13900 (WizardCM / RytoEX). Say that a few hundred players will fetch this zip once and ask whether they object.
   - If they object: the owner decides between an Akashic mirror (Akashic becomes the distributor; record the decision as D-0xx) and shipping codec-free.

3. CDN monitoring. A daily CI job runs HEAD + SHA-256 on the OBS URL until 10-25, because the file name has no patch version. If the hash changes, alert and keep the last known-good copy in private storage for a manual decision. Never auto-mirror.

4. Consent screen (pt-BR). State that the runtime is OBS's Chromium build including H.264/AAC decoders, its size (345 MB) and its source. No legal claims. The owner approves the text.

5. Fallbacks.
   - Automatic: stock Spotify CEF 152 (no codecs), with an on-screen reason code "lives indisponíveis (sem codecs)".
   - Drop CinemaMod 116 as a fallback for public browsing: Chromium 116 from 2023, a stale fork, and no better legally.

6. Security. Chromium 150 is about 6 milestones old by 10-25 and the sandbox is off. Keep allowlist mode for spawn and public screens (youtube.com, youtube-nocookie.com, twitch.tv and the CDNs they need) and the internal-address block. Use blocklist mode only for private screens.

7. Linux (Tier 2). Do not redistribute OBS's stripped libcef.so. Either download OBS's 291.6 MB tar.xz on the player's PC and keep the 2.03 GB unstripped library, or go codec-free on Linux. Owner decision.

8. Correct the docs.
   - H.264 is not "~Nov 2027": Via LA's list (2026-09-28) carries US patents granted in 2026 and many Brazilian patents, and US 9,356,620 runs to at least Nov 2030.
   - AAC risk is lower. Fedora/Red Hat ship AAC-LC but not H.264. Via LA's AAC list is dominated by HE/xHE-era families (INFERRED).
   - Brazil: art. 43 I (private, non-commercial acts) probably covers players; art. 42 §1 (contributing to infringement) is Akashic's theoretical exposure.
   - Ask a Brazilian IP lawyer one question: does the server's monetisation (if any) change this?

AFTER 10-25 (exit from the opaque, stale binary)
Run two time-boxed spikes and pick one by the end of November:

(a) A Windows CEF that uses only OS decoders, built by our CI on a GitHub larger runner (Windows 32-core, $0.162/min):
   - CEF #3559 branding patch;
   - media_use_openh264=false;
   - a Chromium patch so MediaFoundationAudioDecoder takes AAC-LC and HE-AAC (today only xHE-AAC: mf_audio_decoder.cc:101-107, gpu_mojo_media_client_win.cc:104-107).
   This gives the cleanest legal posture plus CEF-stable security. Keep the OBS codec build as an opt-in fallback for PCs without hardware H.264 decode.

(b) An Electron host running out of process, for a current Chromium, crash isolation and no JNI. Needs proof that per-screen audio can be tapped for AkashicFM.

If Akashic ever redistributes Chrome-branded FFmpeg itself (A1 or a mirror), first take the Via LA AVC licence ($0 under 100k units/year) and ask Via LA whether AAC fees apply to free distribution (small-entity initial fee $1,000).

### Spike

G1 codec arm, on the owner's Windows 10/11 x64 PC, by 10-13. About 2 hours once the Windows libjcef artifact exists.

Setup: a standalone Java 8 probe with two engine builds side by side:
- (A) CCBlueX java-cef built against OBS cef_binary_7871_windows_x64 (150.0.20-7871-devtools_patch);
- (B) the control: the same java-cef on stock Spotify CEF 152 (no codecs).

Steps:
1. Verify that the downloaded zip's SHA-256 equals 54cd191a...f341 and record the HTTP Last-Modified header.
2. In each build, log `MediaSource.isTypeSupported('video/mp4; codecs="avc1.64002a"')`, `'audio/mp4; codecs="mp4a.40.2"'`, `'audio/mp4; codecs="mp4a.40.5"'`, `'video/mp2t; codecs="avc1.64002a,mp4a.40.2"'` and `canPlayType('application/vnd.apple.mpegurl')`.
3. Play each for 60 s at 1280x720 OSR:
   - (i) a current YouTube live watch page;
   - (ii) a Twitch live channel;
   - (iii) an H.264 High + AAC-LC MP4.
   Log video.currentTime progress, paints per second and the RMS level of the CefAudioHandler PCM.
4. Repeat (A) with `--disable-gpu --disable-gpu-compositing` to prove playback does not depend on a hardware decoder.

PASS: (A) passes all three with audio RMS above zero for at least 55 of 60 s, at least 25 paints/s, 5/5 cold starts, and works with GPU off. The control (B) fails YouTube live and Twitch, which confirms the codec dependency still exists in Oct 2026.

If (B) plays YouTube live, re-open the whole codec decision, because a codec-free engine would then cover the main use case.

Parallel legal sub-spike: the OBS courtesy message is sent by 10-13. Outcome (no objection, objection, or no reply by 10-16) is recorded in DECISIONS.md.

### Alternatives

| Option | Fits 1.7.10/Java 8 | H.264/AAC | Licence/cost | Maturity | Effort | Pros | Cons |
|---|---|---|---|---|---|---|---|
| A0. Current plan, hardened: OBS CEF 7871 downloaded on the player's PC (no Akashic mirror) | yes | yes | $0 cash. Patent posture: OBS distributes the decoders; the player uses them privately (Brazilian art. 43 I may apply, INFERRED); Akashic could be called a contributor (art. 42 §1, INFERRED). | OBS ships it in 33.0.0-rc1 (2026-10-09) to millions of users. CEF updates are about yearly. Linux build plus a 6-line jni_util patch already compiled (prior research). | 0.5-1 day on top of the existing plan (OBS contact, CDN monitor, consent text, fallback wiring) | Playback path already verified on Linux. No new engineering. Akashic is not the distributor. OBS's own repo pins the hash. OBS 33.0 RC already ships the build. | Opaque build that we cannot rebuild. Stuck on Chromium 150 until OBS's next yearly bump. Codec patents on paper still live (US to 2030+, Brazilian patents listed in 2026). Depends on a third-party CDN that might replace or rate-limit the file. Windows still untested. |
| A1. Self-built CEF with proprietary_codecs=true ffmpeg_branding=Chrome on a paid large runner | yes | yes | Compute: Windows 32-core larger runner (128 GB RAM, 1200 GB SSD) at $0.162/min = $9.72/h, so at most $58 per 6-hour attempt; Linux 32-core $0.082/min (VERIFIED prices; attempt count INFERRED, about 2-4). Patents: Via LA AVC is $0 for the first 100,000 units/year but you must be a licensee (VERIFIED terms). AAC: $1,000 small-entity initial fee and $0.98/unit for the first 500k, with 'fees due on the sale' (VERIFIED; whether free distribution owes fees is unclear). | CEF's automate-git build is the standard route. We would own all maintenance, one build per CEF update per platform. | 3-5 days to a first green Windows build (pipeline plus 2-4 attempts), then about 0.5 day per update. Not before 10-25. | We control the Chromium version, so security updates follow CEF stable instead of OBS's yearly cadence. The build is reproducible and patchable (native patches, helper rename). Removes the dependency on OBS's CDN. | Akashic becomes the distributor of the H.264/AAC decoders, the worst legal posture of all options unless Akashic takes Via LA licences. The first Chromium build is risky inside the 6-hour hosted-job limit. Needs a GitHub Team org for larger runners. Costs schedule time the 10-25 plan does not have. |
| A2. Self-built Windows CEF that uses only OS decoders (CEF issue #3559 approach plus an AAC patch) | partly | yes | Same compute as A1 (about $10/h on a Windows 32-core runner). Patent cost to Akashic: none expected, because decoding is done by licensed OS components (INFERRED; get a lawyer's confirmation). | Experimental: nobody ships a CEF like this publicly. Vivaldi and Opera do similar things in their own forks (platform_media). | 7-12 days (Chromium AAC patch, rebased #3559 patch, 2-4 builds, GPU matrix tests). Post-event only. | Legally the cleanest option that still uses Chromium/CEF. Microsoft's built-in decoders are covered under Via LA's PC-operating-system tier, which includes end users' personal-use rights, and Microsoft is a Via LA licensee. Akashic ships no encumbered decoder. Tracks CEF stable. MF AAC covers exactly YouTube's mp4a.40.2 and 40.5. | Not ready: CEF issue #3559 has been open since 2023 (last maintainer comment Aug 2023; 'It's 2026, and I am also struggling with this', Jun 2026). H.264 then needs working GPU hardware decode, so VMs, blocklisted GPUs or a dead GPU process lose video. The decoders run in the GPU process on Windows. Needs our own Chromium patch plus full builds. Windows only; Linux has no equivalent here. |
| A3. CinemaMod MCEF CEF 116 codec natives | partly | yes | $0 cash; same patent posture as A0 with a less reputable distributor | Stale, single maintainer, no newer natives (6478/6533 branches returned HTTP 403 in earlier research). | 1-2 days to wire as an emergency engine. Recommend dropping it as a public-browsing fallback. | Prebuilt for Windows, Linux and macOS; codecs verified at runtime. | Chromium 116 dates from Aug 2023: unacceptable for open browsing on a public server. The java-cef fork is stale: last commit 2024-10-22 (MCEF last commit 2025-06-17). Its Java API differs. Legally no better: CinemaMod hosts and distributes its own codec CEF build (DownloadCEF pulls from mcef-download.cinemamod.com/cef-builds). Its maintainer said in 2022 that distributing codec CEF requires licences. |
| A4. Electron as an out-of-process off-screen browser host | yes | yes | $0 cash. Patents as in A0 (distributor: Electron/OpenJS; OpenJS is not in Via LA's licensee list by name, INFERRED). | Very mature and actively maintained (v44.7.0 released Oct 2026; v45 beta). OSR is a documented feature. | 5-8 days for a host app plus Java bridge plus input. Audio tap unknown. Post-event candidate; needs a spike. | Current Chromium on an 8-week cadence (v45 beta is on Chromium 156). Smaller download (electron-v44.7.0-win32-x64.zip is 151 MB, against 345 MB for OBS). No JNI and no libjcef-to-CEF version lock. Crash isolation for free (out-of-process was already the post-event goal). Electron ships a separate codec-free ffmpeg (761 KB), which shows the codec is a deliberate, swappable choice. Mainstream redistribution model (MIT, OpenJS). | Major architecture change two weeks before the event. No native per-browser PCM audio capture comparable to CefAudioHandler, so 3D audio would need an injected WebAudio/AudioWorklet tap (unverified). Same patent posture as A0: a third party (OpenJS/Electron) distributes the Chrome-branded FFmpeg, and the Eclipse Foundation IP team flagged exactly this. Windows 10+ only. |
| A5. A browser whose codecs the vendor already licenses: WebView2/Edge (preinstalled) through a capture bridge, or Google Chrome for Testing over CDP | partly | yes | $0. Codec licences are held by Microsoft or Google (VERIFIED that both are AVC licensees; scope of coverage for this use INFERRED). | The runtimes are very mature. The capture bridge would be ours, built from community samples. Not proven anywhere in a game at 30 fps with audio. | WebView2 bridge 8-12 days; CDP screencast prototype 3-5 days. Both post-event spikes. | Safest legal posture: Akashic downloads or ships no codec. Microsoft and Google are Via LA licensees, and Google-branded Chrome for Testing has the codecs on. WebView2 needs no runtime download on Windows (it already exists) and updates itself with Edge security fixes, removing the security debt. | WebView2 has no off-screen rendering API: issue #547 has been open since 2020 with no staff progress since 2022, and the capture workarounds are fragile. It needs a native C++/WinRT bridge we must build and sign. Audio capture is not exposed. Windows only. CDP screencast sends event-driven JPEGs whose 30 fps video quality is unproven, and Chrome for Testing is meant for testing, not end-user browsing (terms not checked). |
| A6. Commercial Chromium-for-Java SDK (TeamDev JxBrowser) | unknown | yes | Commercial SDK licence (official 2026 price not found) plus our own Via LA AVC/AAC licences | Mature commercial product (version 9.x SBOMs published). | 3-6 days to integrate plus procurement. Not for 10-25. | Supported product, out-of-process Chromium, Java API, codec switch built in. | TeamDev puts codec licensing on the customer ('companies must acquire the license from Via LA'; 'TeamDev shall not be responsible'), so it is legally no better than A1. Commercial price; third-party figures range from about $1,979 to $8,199 (unverified). Java 8 support for current versions unverified. Requires a licence key. Closed source. |
| A7. Codec-only sources rejected on technical grounds: Cisco OpenH264, OS-decoder-only CEF without an AAC patch, VLC/WaterMedia | no | no | n/a | n/a | n/a (rejected) | OpenH264: Cisco pays royalties for its own binaries. Windows OS decoders: licensed. | OpenH264's decoder supports only Constrained Baseline, but YouTube live HLS is High profile (avc1.64002a), and it has no AAC. OS-decoder-only CEF without a patch plays H.264 but not AAC (CEF #3559 test), so YouTube live audio fails. WaterMedia/VLC is not a browser, needs stream-URL extraction (YouTube ToS forbids downloading or separating streams), targets MC 1.16.5+ and is licensed Polyform Strict + commercial VLCJ. The owner already rejected relays and transcoding (D-008). |

### Open questions

- Does OBS (WizardCM/RytoEX) object to third parties pointing hundreds of players at cdn-fastly.obsproject.com for the 345 MB CEF zip? Will cef_binary_7871_windows_x64.zip be replaced in place before OBS 33.0 final (the file name has no patch version)?
- Is YouTube live still AAC-only with H.264 HLS in Oct 2026? Only 2024 format tables were verified; the G1 control arm (stock CEF 152) answers this directly.
- Does Rede Akashic have commercial activity (VIP/ranks)? That could defeat the 'em caráter privado e sem finalidade comercial' exemption of Lei 9.279 art. 43 I and raise art. 42 §1 contributory exposure. Needs a Brazilian IP lawyer's opinion.
- Is the STF ADI 5529 modulation (already-granted non-health patents keep their extended terms) correctly summarised? Only a secondary law-firm summary was accessible; the STF site returned 403. It matters for which Brazilian AVC patents from the 2002-2004 filings are still in force.
- Under Via LA's AAC programme, do any per-unit fees apply to decoders distributed free of charge ('License fees are due on the sale')? Only relevant if Akashic ever mirrors or self-builds codec binaries.
- Linux Tier 2: accept a 2.03 GB unstripped libcef.so extracted locally from OBS's tar.xz, or go codec-free on Linux?
- For the post-event OS-decoder build: what share of players' GPUs can hardware-decode H.264 through D3D11 inside CEF off-screen rendering, and how large is the Chromium MF AAC patch really (estimated, not built)?
- For the Electron alternative: can a preload WebAudio/AudioWorklet tap deliver per-screen PCM reliably on YouTube and Twitch pages, so AkashicFM 3D audio (D-005) survives without CefAudioHandler?

## Browser audio into Minecraft 3D audio: CEF capture (CCBlueX CefAudioHandler) into the AkashicFM OpenAL engine, mute semantics, lip-sync, A/V drift, sample rates, voice budget

**Recommendation:** `keep-with-changes` (confidence medium)

### Current choice assessment

Nothing was built or run for this review. No Windows run, no in-game test. Everything comes from reading code and docs on 2026-10-11/12.

STRENGTHS
(1) The capture primitive is the right one. CEF captures the whole WebContents: all frames, every media element, and sound the page makes through WebAudio. It uses the audio-service loopback with mute_source=true, which is identical on CEF 150 (branch 7871) and CEF 152 (branch 7977). VERIFIED in chromiumembedded/cef 7871 and 7977, libcef/browser/audio_loopback_stream_creator.cc:70-72.
(2) Muting keeps the picture and the capture alive. When muted, Chromium swaps the device stream for a fake stream that keeps pumping, because "video playback is synchronized to the audio stream". Loopback snoopers receive data in OnMoreData whether or not the stream is muted. VERIFIED in chromium services/audio/output_controller.cc (RecreateStream fake-stream branch; OnMoreData BroadcastDataToSnoopers).
(3) The pts is usable as a lip-sync anchor. It is the playout reference time on the TimeTicks clock: reference_time = delay_timestamp + delay, passed through audio_capturer.cc Capture(). The loopback adds an initial 20 ms capture delay and does sinc resampling and channel mixing to the requested parameters. VERIFIED in loopback_signal_provider.cc:22 and snooper_node.cc:39-82.
(4) There is a production precedent. OBS obs-browser ("Control audio via OBS") uses CefAudioHandler on OBS's own CEF builds. It requests the OBS sample rate and channel count with 1024 frames, and feeds pts*1e6 straight into its monotonic timestamps. VERIFIED in obs-browser browser-client.cpp:577-637.
(5) AkashicFM already has the engine. It has mono OpenAL voices with EFX low-pass occlusion and reverb, smoothed gains, aligned starts, and a PTS-timed mode. The timed mode does sample-accurate skips, ±0.2% pitch drift correction and a resync at 250 ms. VERIFIED in Playback.java:29-49,250-300. OpenAL Soft 1.15.1 on LWJGL2 left 224 free sources with EFX and 4 sends. VERIFIED in AkashicFM docs/FASE0-RESULTADOS.md:74-82 (null backend).

WEAKNESSES (found by trying to break the plan)
(a) Audio leaks at the start of every capture. CEF creates the capturer only when the WebContents becomes audible. Audibility is polled at 15 Hz (output_stream.cc kPollingPeriod). CEF tears the capturer down 2 s after silence (alloy_browser_host_impl.cc:1172-1197, the same on 7871 and 7977). Until the loopback mutes the group, audio plays unmuted and unattenuated on the OS device. That happens again after every pause of 2 s or more. Code VERIFIED. The blip length (tens of ms up to about 100 ms) is INFERRED. OBS users report start-of-sound glitches after pauses with this option (forum thread 116648, anecdotal).
(b) A capture error un-mutes the page. On OnCaptureError (anything except kSocketError) CEF stops the stream, so the page plays full-volume and non-positional on the OS device. VERIFIED in audio_capturer.cc. Neither (a) nor (b) is in the plan.
(c) CCBlueX native defects (already known), confirmed at fe8cc9e:
- raw FindClass on the audio thread (audio_handler.cpp:15,43,56);
- the parameters are never read back, so CEF's defaults apply: 44100 Hz and 1024 frames (CEF alloy_browser_host_impl.cc:1256-1257);
- null parameters are passed to onAudioStreamStarted (:76);
- DataPointer needs LWJGL3 MemoryUtil (DataPointer.java:20,73);
- if the JNI env is missing, GetAudioParameters returns true, so capture runs with no consumer and the screen is silent (:51);
- the Java doc says the UI thread, but packets arrive on the audio stream thread (CefAudioHandler.java:13 vs cef_audio_handler.h).
(d) The latency budget breaks lip-sync. AkashicFM's Playback is tuned for radio: 2048-frame chunks (42.7 ms), a 4-chunk prebuffer (171 ms) and a 1 s queue (Playback.java:29-38). Its timed start waits for the server ClockSync (Playback.java:253). A browser feed would therefore need a latency of at least about 220-250 ms. On top of that, OpenAL Soft 1.15.1 opens WASAPI with 1024 frames × 4 periods at 44.1 kHz, about 93 ms (VERIFIED in ALc.c:2708-2710 and mmdevapi.c:354). LWJGL2 AL.create() is 44100 Hz at 60 Hz refresh (VERIFIED in AL.java:205-206). The total is about 300 ms or more of audio behind the picture (INFERRED). ITU-R BT.1359 puts detectability at −125 ms and acceptability at −185 ms of audio lag, and those limits were derived from newsreader clips, which is exactly election coverage. So the plan's 4-5 frame video ring is too small unless AkashicFM gets a low-latency per-feed profile.
(e) AkashicFM integration blockers are still in HEAD fa54626:
- RadioAudioController.stopAll, retainOnly and the DRY room (RadioAudioController.java:156,275,279-283);
- blocking ring writes (TimedPcmRing.java:66, PcmRing.java:51);
- Resampler48k is linear (Resampler48k.java:5);
- AudioFeed is fixed at 48 kHz (AudioFeed.java:9), while OpenAL Soft 1.15.1 resamples 48 kHz down to 44.1 kHz with a linear resampler (alSource.c:37). This is a minor quality loss.
(f) OpenAL is fed only at RenderTickEvent START (ClientEvents.java:43). Any hitch longer than the AL queue causes an underrun. Timed mode recovers without permanent drift, but each GC or chunk hitch is a gap. During a stop-the-world GC the CEF audio thread also blocks inside the Java callback (INFERRED).
(g) Windows 11 timer-resolution throttling distorts CEF-captured audio when the window is minimized. OBS fixed it by opting its CEF helper out with SetProcessInformation(PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION). VERIFIED in obs-browser #406, PR #484 (merged 2025-05-06) and obs-browser-page-main.cpp:77-82. Not in our plan.
(h) The plan's wasHidden when off-frustum hurts 3D audio. Chromium disables the video track of hidden audio+video media after 10 s (non-streaming media with keyframe distance under 5.5 s) and switches tracks back on show, noting that this "may also cause AV sync issues". VERIFIED in web_media_player_impl.cc:3764-3826, Chromium main. With 3D audio you hear screens behind you, so turning around causes a hiccup.
(i) The CinemaMod 116 fallback ships hosted natives that we cannot patch, so 3D audio cannot work there (INFERRED).
(j) A purely reflective soft dependency cannot implement AkashicFM's AudioFeed interface. It needs a compileOnly API plus a guarded bridge class (INFERRED).

### Recommendation detail

FOR 10-25. Keep CefAudioHandler into AkashicFM as the 3D path. It stays a go/no-go Stretch, decided on 10-21 as planned. Ship the cheap safety work regardless of that decision.

1) Native build, frozen 10-16. Add these to the planned audio patches:
(a) Bind CefBrowserHost::SetAudioMuted and IsAudioMuted in JNI.
(b) Opt the renamed helper (jcef_helper main) and the browser process (libjcef init) out of Windows 11 timer-resolution throttling: SetProcessInformation(ProcessPowerThrottling, PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION), as obs-browser does.
(c) In audio_handler.cpp:
- use the cached-loader FindClass;
- read back the requested parameters (48000 Hz, stereo, 480 or 960 frames) so Chromium's sinc resampler does the conversion, not Java;
- pass the real parameters to onAudioStreamStarted;
- deliver PCM without LWJGL3: copy the planar floats into a Java-registered direct buffer, or into a float[] per channel;
- return false (normal playback) when no JNIEnv is available.

2) Two runtime modes per browser, with no CEF restart:
- SPATIAL: SetAudioMuted(true) right at browser creation, plus getAudioParameters returning true. Pre-muting closes the start-of-capture leak and the error-path un-mute. Capture still receives PCM, because snoopers sit before muting.
- OS (fallback): getAudioParameters returns false, JS distance volume runs per tick, and SetAudioMuted(true) is the hard mute when out of range, unloaded, or with no world.
Fall back automatically from SPATIAL to OS on onAudioStreamError, or when a screen is audible but no packets arrive for 2 s. Mode selection: client option, server flag, and AkashicFM API presence.

3) The tap runs on CEF's audio thread. It never touches MC or World objects and allocates nothing per packet. It downmixes to s16 interleaved stereo and maps pts to the local clock with a min-filtered offset (arrival − pts). On Windows the epochs probably match, since OBS uses pts directly, but this is only INFERRED. It writes into a non-blocking drop-oldest TimedPcmRing and starts a new segment on stream start or on a pts gap. The map is keyed by browser id.

4) AkashicFM release, in the pack by 10-18:
- ExternalAudioSources.register(provider), merged into keep, the single occlusion budget and the room;
- external sources are not stopped by radioVolume=0; they get their own "Telas" volume × MC master × records;
- TimedPcmRing.offer (non-blocking);
- AudioFeed clock hooks (clockReady/clockNowMs, defaulting to ClockSync) so local feeds never wait for server pings;
- a per-feed Playback profile (chunk 960-1024 frames, prebuffer 3 chunks, target about 8 chunks), with radio defaults unchanged;
- an API_VERSION constant.
AkashicWeb compiles against the API with compileOnly. A bridge class is loaded only after Loader.isModLoaded("akashicfm") and an API_VERSION check, not by reflection.

5) Emitters are LEFT/RIGHT at the screen edges, clamped to about ±4 blocks from the centre on huge screens, or MIX on small screens. Gains come from GainModel.sourceGain. Arbitration is nearest-first: 2 audible screens by default (configurable 1-4), sharing AkashicFM's 96-voice and occlusion budgets. Screens over budget drop their PCM and stay muted. OpenAL sources are not the constraint (224 free on LWJGL2); browser CPU is.

6) Lip-sync:
- latencyMs = 120 ms on the local clock;
- video delay ring Dv = latencyMs + device estimate (OpenAL Soft 1.15.1/LWJGL2: about 80 ms; lwjgl3ify OpenAL Soft 1.2x: about 60 ms) − 1 render frame, which is about 6 frames at 30 fps, roughly 22 MB per audible 720p screen;
- the delay applies only while that screen's Playback is PLAYING, so UI clicks are not delayed otherwise;
- add a client A/V offset slider of ±250 ms;
- target −125..+45 ms on a flash/beep test video.
The timed mode never drifts permanently after hitches: pitch corrects, a resync triggers past 250 ms, and underruns restart aligned.

7) Do not call wasHidden on screens within audio range. Stop the texture uploads instead, and hide only beyond the audio range plus hysteresis.

8) Gates:
- extend G1(f) with the leak and restart tests (see spike);
- G3 adds the A/V offset and hitch test;
- if there is no-go on 10-21, ship OS mode, which keeps the SetAudioMuted kill.
Never ship SPATIAL without pre-mute.

AFTER 10-25:
- Keep CefAudioHandler inside the browser-host process and send pts-stamped PCM over a shared-memory SPSC ring.
- Give AkashicFM a dedicated AL feeder thread under its existing lock, so queues can shrink to about 60 ms and survive render hitches.
- Feed at the device mixing rate (ALC_FREQUENCY, 44.1 kHz on LWJGL2) to avoid OpenAL 1.15.1's linear resampler.
- On lwjgl3ify, read the real device latency through AL_SOFT_source_latency.
- Optionally add A5 (in-page spatialization) as a middle tier for engines without capture (CinemaMod 116).
- Document the OpenAL HRTF option.
- Reject A3 (WASAPI process loopback), A6 (PulseAudio/PipeWire) and A7 (media side-channel). They cannot separate screens and/or cannot be silent and captured at once.

### Spike

Smallest experiment: extend the G1 standalone Java 8 probe (no Minecraft) on the owner's Windows 10/11 PC, using the first CI artifact (OBS CEF 150/7871 + patched CCBlueX libjcef with the SetAudioMuted binding and the parameter read-back).

Steps:
(1) Create one OSR browser and call SetAudioMuted(true) before loading anything. Register the audio handler requesting 48000 Hz, stereo, 480 frames.
(2) Load a local H.264/AAC A/V-sync clip (a white flash plus a 1 kHz beep every second, with silences of 3 s or more between bursts), then a YouTube live.
(3) Log OnAudioStreamStarted parameters and packets per second. Log the pts vs System.nanoTime offset (min, median, max over 10 min) and every Started, Stopped and Error event.
(4) Have the owner listen with the probe's own playback disabled, while watching the Windows volume mixer meters for the javaw and helper sessions.
(5) Repeat with pre-mute OFF to measure the leak.
(6) Minimize the probe window for 60 s on Windows 11.

PASS:
(a) With pre-mute, OS output stays silent, including the first 500 ms after every play or resume. Without pre-mute an audible blip is observed, which confirms the fix is needed.
(b) OnAudioStreamStarted reports 48000 Hz and 2 channels; about 100 packets/s; no ClassNotFound or JNI exceptions.
(c) pts − nanoTime offset jitter stays within ±5 ms; record whether the base is shared.
(d) After each 3 s silence, Stopped then Started occurs with no OS blip and no repeated-start artifact.
(e) No pts gaps or skip-aheads while minimized.
(f) The tap uses less than 1% of one core per browser.

Then at G3, in game on Java 8/LWJGL2 at 60 fps, filmed with a phone in slow motion:
(g) A/V offset on the flash/beep clip is within −125..+45 ms, and still within it after 10 forced hitches (System.gc key or teleport chunk loads), with no growing lag.
(h) Two audible screens play independently positioned audio with zero OS output.

FAIL: if (a) fails, meaning capture does not start while the browser is pre-muted, ship OS mode for 10-25. If (g) fails, raise Dv or the offset slider before go/no-go.

### Alternatives

| Option | Fits 1.7.10/Java 8 | H.264/AAC | Licence/cost | Maturity | Effort | Pros | Cons |
|---|---|---|---|---|---|---|---|
| A1. Hardened CefAudioHandler pipeline (current choice, with fixes) | yes | not-applicable | BSD (CEF, java-cef/CCBlueX); AkashicFM in-house; no cost | The CEF audio capturer has existed since 2019 and is unchanged in 7871/7977/master. obs-browser uses it on OBS CEF builds. CCBlueX fork active (fe8cc9e, 2026-10). | 4-5 days total: native additions 0.5 (inside the 10-12 native day), AkashicFM API 1.5, tap and bridge 1, video delay and A/V offset 0.5-1, Windows tuning 0.5-1 | Per-screen PCM that covers every frame, element and WebAudio sound. Chromium keeps its own A/V clock while muted. The same CEF code exists on 150 and 152. Used in production by OBS. Reuses AkashicFM's occlusion, EFX reverb, gain and timed-sync code. Works in-process now and in a host process later (PCM is about 192 KB/s per screen). A runtime per-browser fallback to OS output exists: getAudioParameters returns false. | Needs native patches in the frozen 10-16 build: FindClass, parameter read-back, PCM delivery, a SetAudioMuted binding, and the timer-throttling opt-out in the helper. Needs an AkashicFM release in the pack. Lip-sync needs a video delay ring of about 150-200 ms on LWJGL2 (OpenAL Soft 1.15.1 buffers about 93 ms). Gaps on game hitches longer than the queue. Not available on the CinemaMod 116 fallback. |
| A2. Chromium plays to the OS + JS distance volume + native SetAudioMuted hard mute (the plan's MVP, strengthened) | yes | not-applicable | BSD; no cost | The WD approach, with known weaknesses. SetAudioMuted is a stable CEF API. | 0.5-1 day (already in the MVP; +0.25 for the SetAudioMuted JNI) | Zero added latency and perfect lip-sync, because Chromium keeps its own A/V clock. No AkashicFM release needed. Works on every engine, including CinemaMod 116 and stock jcefmaven. Very low effort and risk. SetAudioMuted gives a kill switch that does not depend on page JS. | Not positional (no left/right), no occlusion or reverb, does not follow MC's output device. Site players such as YouTube rewrite element volume. WebAudio-generated sound is not covered unless AudioContext is patched. Every frame and iframe needs the injection. These are WD's long-standing complaints. |
| A3. Windows WASAPI process loopback (AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK) of the CEF/host process tree | partly | not-applicable | Windows SDK API; no cost | Microsoft API, used by OBS Application Audio Capture since OBS 28 | 3-5 days, and still cannot separate screens; rejected | Needs no CEF or JCEF audio patch. Captures whatever the process renders, including plugins. Endpoint-independent. | Structural deal-breakers. (1) One audio service renders every browser, so all screens arrive as one mixed stream and per-screen positioning is impossible. In-process, the javaw tree also contains Minecraft's own OpenAL output. (2) It does not mute the source, so you hear double audio. If you mute the browser instead, Chromium swaps to a fake stream and nothing reaches WASAPI, so it cannot be silent and captured at the same time. (3) Microsoft documents Windows 10 build 20348+; OBS says Windows 10 2004+ in practice, and a plugin author says it is officially Windows 11 only. (4) Windows-only COM/JNI code. |
| A4. In-page WebAudio capture (MediaElementAudioSourceNode -> AudioWorklet -> MessagePort -> CefMessageRouter/cefQuery -> Java) | partly | not-applicable | Web standard; no cost | Web Audio is stable, but this injection technique is bespoke and fragile against site changes | 3-4 days plus a long tail of per-site fixes; not for 10-25 | No native audio patch. Works with any JCEF, including upstream, jcefmaven and the CinemaMod 116 hosted natives. Rerouting implicitly silences the element's OS output. AudioContext timestamps give a clock. | (1) CORS-cross-origin media outputs silence by spec, and the element is then silent everywhere. (2) AudioWorklet needs a secure context, and addModule of a blob: or data: URL falls under page CSP/Trusted Types; YouTube enforces Trusted Types (INFERRED). (3) JS reaches Java only as strings: about 256 KB/s base64 per screen at 48 kHz s16 stereo, about 50 messages/s, handled on the CEF UI thread, which is our pump thread. (4) Main-thread jank on heavy pages adds jitter. (5) Must patch every frame plus AudioContext.destination. createMediaElementSource works once per element. (6) Added latency of about 20-60 ms plus jitter (INFERRED). |
| A5. In-page WebAudio spatialization driven by AkashicFM data (PannerNode/StereoPannerNode + BiquadFilter low-pass + ConvolverNode reverb), Chromium plays to the OS | partly | not-applicable | Web standard; no cost | Bespoke | 2-3 days; candidate after-event middle tier for engines without CefAudioHandler | Perfect lip-sync with zero added latency. Stereo panning plus occlusion feel. No OpenAL voices, no AkashicFM release needed for 10-25. Works on any engine. | Same CORS, CSP and iframe injection issues as A4 (minus the transport). Listener updates at 20 Hz need smoothing (setTargetAtTime). Not EFX-identical to the radios. Ignores MC's device selection. Pages can interfere. Does not literally meet D-005 (AkashicFM's engine). |
| A6. Linux PulseAudio/PipeWire per-app null sink (PULSE_SINK) + monitor capture | partly | not-applicable | LGPL system libraries; no cost | System-dependent | 2-3 days, Linux only; rejected | No CEF audio patch. | Linux only (Tier 2). Changes the user's sound server (module-null-sink), which leaks if we crash. All browsers mix into one sink. PULSE_SINK must be set before CEF spawns helpers, and Java cannot setenv, so it needs JNI. Needs libpulse or a parec subprocess. Behaviour differs between PipeWire-pulse and PulseAudio. No per-screen separation. |
| A7. Media side-channel: extract the stream's audio URL (YouTube HLS AAC / MP4) and decode outside the browser (AkashicFM or FFmpeg/WaterMedia-style), browser muted | partly | unknown | FFmpeg LGPL/GPL builds; WaterMedia licence not checked | Fragile by design (extractor churn) | 5+ days; rejected | Full control of audio buffering, and native positional audio. | Needs per-site extractors with yt-dlp-class churn and ToS risk. Separate decoding drifts against the browser's video. AkashicFM has no HLS support. Works only for a few sites. It conflicts with the real-browser goal (D-001) and the abandoned relay direction (D-008). |
| A8. Own mini OpenAL engine inside AkashicWeb (no AkashicFM change) | yes | not-applicable | In-house; no cost | Duplicate maintenance | 3-4 days; emergency-only if the AkashicFM release slips past 10-19 | No cross-mod release dependency before 10-25. | A second engine fights AkashicFM for EFX aux slots and reverb ownership. Context lifecycle code is duplicated. In LWJGL2, AL.destroy() resets the AL/ALC/EFX native stubs, so calls between destroy and the next create raise UnsatisfiedLinkError (Akashic-Voice #3, live-observed there). Contradicts D-005. |

### Open questions

- P-11 mod list: is Hodgepodge in the Forbidden pack? AkashicFM's probe saw Hodgepodge install its own OpenAL sound library. Does it change the MC context (frequency, refresh, sources) or the device latency? Is OptiFine present?
- Does a WebContents muted with SetAudioMuted(true) still trigger OnAudioStateChanged(audible) and the CEF capturer on 7871? The Chromium code chain says yes (INFERRED); the spike step (a) decides it.
- Is CEF pts on the same epoch as Java System.nanoTime on the players' Windows machines (both QPC-based)? OBS relies on that for its own clock. The design uses min-offset mapping anyway.
- Will an AkashicFM release with ExternalAudioSources, a local clock and a low-latency profile be in the Forbidden pack by 10-18? Who updates the pack, and when?
- Owner preference for the spawn telão: stereo L/R at the screen edges or a single centred mono source? Should 3D audio be on or opt-in by default on 10-25?
- Is a 150-200 ms video delay (only while audio plays) acceptable to the owner for interactive use, or does he prefer a looser lip-sync with no video delay?
- Do players with Java 17-25 + lwjgl3ify (OpenAL Soft 1.2x) need a different device-latency estimate? Measure at G3 on Java 21.
- Combined OpenAL source and EFX budget with Akashic-Voice and AkashicFM radios on real devices (224/184 free sources were measured only on the null backend).
- Do the CinemaMod 116 hosted natives (emergency fallback) include a working audio handler at all? Assume no; OS mode only.

## The mod's own stack for the Rede Akashic Forbidden Series pack (Technic client on Forge 10.13.4.1614, Crucible 1.7.10 server on Java 21, optional Velocity, Android via ForbiddenMobile): build tool, runtime dependencies, networking, multiblock, GUI, optional integrations, and Crucible/Bukkit/Velocity/firewall rules on the server side.

**Recommendation:** `keep-with-changes` (confidence medium)

### Current choice assessment

WHAT THE PACK CAN BE ASSUMED TO CONTAIN. Evidence: the public repo Akashiic/Technic-Forbidden. I listed its trees (main 4df9059, 2026-06-08; branch mobile-launcher-dev f3150bb, 2026-09-18; both list the same 170 entries under mods/). VERIFIED in that snapshot: gtnhlib-0.10.7, +unimixins-all-1.7.10-0.3.1, angelica-2.1.33, hodgepodge-2.7.160, archaicfix-0.8.0, coretweaks-0.3.4.6-GTNH, modularui2-2.3.67-1.7.10, necrotempus-1.3.6, Waila-1.19.29, NotEnoughItems-2.8.101-GTNH, entityculling-1.6.4-mc1.7.10, FpsReducer-mc1.7.10-1.10.3, BlockLimiter-0.7.0, PacketUnlimiter, NettyPatcher, and a BSL_v8.2.09 shaderpack. VERIFIED absent: OptiFine (Angelica 2.1.33 lists it as permanently incompatible), lwjgl3ify, StructureLib, ModularUI 1, AkashicFM, Akashic Voice and Distant Horizons. The field logs nevertheless show Akashic Voice RC7 and Distant Horizons on the live client, so this snapshot is the pack's base and not the live production pack (VERIFIED mismatch). bin/modpack.jar (SHA-256 00d1ca02…11529) is stock Forge 10.13.4.1614 with mainClass net.minecraft.launchwrapper.Launch and launchwrapper 1.12. The field logs show JVM 1.8.0_51 and LWJGL 2.9.1. In practice the Technic desktop client is Java 8 + LWJGL2 only: stock LaunchWrapper cannot start on Java 9+, and the pack has no lwjgl3ify (INFERRED, near-certain; confirm with preflight telemetry). AkashicFM declares required-after:gtnhlib@[0.5.23,) and builds with usesMixins=true. GTNHGradle then writes TweakClass=org.spongepowered.asm.launch.MixinTweaker into its manifest, so the live pack must also contain GTNHLib and a mixin provider (INFERRED from VERIFIED code; the snapshot agrees).

STRENGTHS OF THE CURRENT CHOICE.
(1) The GTNH buildscript is proven in-house. AkashicFM 1.2.0 builds with Gradle 9.7.1 and gtnhsettingsconvention 2.0.33; Akashic Voice RC7 builds with 2.0.20, and does so with enableModernJavaSyntax=false and usesMixins=false. So the buildscript does not force GTNHLib or UniMixins on us.
(2) Jabel mode compiles with --release 8, so the code is limited to the Java 8 API and emits class major 52.
(3) SimpleNetworkWrapper is used by both in-house mods. FML custom channels work on the production Crucible (LIVE-OBSERVED through Akashic Voice: 138 authenticated sessions over its control channel).
(4) A hand-written, WD-derived flood-fill multiblock fits rectangular screens of variable size.
(5) A vanilla GuiScreen is needed anyway for raw keyboard and dead-key handling.

WEAKNESSES.
(a) The plan and test matrix were written for GTNH. The cells for lwjgl3ify, Java 17-25 clients and Angelica beta66b/2.2.x do not match this pack. The pack has Angelica 2.1.33, a core-profile build with Iris shaders enabled, its own entity culling, async occlusion EVERYTHING and a no-error GL context.
(b) Hard GTNHLib and UniMixins dependencies give nothing on the 10-25 critical path. With usesMixins=true, the TweakClass becomes a launch-time requirement. Every mixin puts us in the conflict surface of Hodgepodge, Angelica, ArchaicFix and CoreTweaks. GTNHLib has broken API before: AkashicFM's /fm reload needed a second code path for GTNHLib versions before 0.9.62.
(c) The planned Angelica call TileEntityRenderBoundsRegistry.registerDynamicClass does not exist in 2.1.33. That version also caches each TE's render box by position, metadata and block type, so a multiblock box that grows after the description packet stays stale.
(d) The plan does not cover these Crucible behaviours (all VERIFIED in its patches):
- mod commands skip canCommandSenderUseCommand and are gated by a Bukkit permission named after the command's class;
- a Bukkit PlayerInteractEvent fires before onBlockActivated, so region plugins can block clicks on screens;
- the Forge/Bukkit placement capture can roll a block back after onBlockPlacedBy has already run.
(e) FML's default network check requires the exact same mod version on client and server, so any server hotfix forces every player to update.
(f) Two pack settings hurt a long telão watch: FpsReducer drops to 10 FPS after 300 s without input, and EntityCulling/Angelica cull tile entities by their bounding box.
(g) Offline-mode or proxy authentication is unknown. Our mod's packets reach the server before a login plugin authenticates the player, so ownership by UUID could be spoofed.

### Recommendation detail

FOR 10-25 (keep the GTNHGradle + Jabel + SimpleNetworkWrapper + WD-derived multiblock + vanilla GuiScreen core; change the dependency stance, the server rules and the platform matrix).

1. Build (BUILD and STATIC level, from AkashicFM):
- settings plugin com.gtnewhorizons.gtnhsettingsconvention 2.0.33, Gradle 9.7.1, blowdryerTag 0.2.2, autoUpdateBuildScript=false; do not move to 2.0.34 before 10-25;
- enableModernJavaSyntax=jabel (gives --release 8 and class major 52); not jvmDowngrader, whose default stubs provider would add a hidden GTNHLib runtime dependency;
- usesMixins=false, empty coreModClass, no access transformer;
- JUnit 5 as in AkashicFM;
- org.cef classes go in through a dedicated configuration with a pinned SHA-256, added to shadowJar after relocation and never relocated (Akashic Voice nativeOpusBundle pattern).

2. Runtime dependencies:
- HARD: Minecraft 1.7.10 and Forge 10.13.4.1614 only.
- SOFT, by reflection or IMC with presence checks: Bukkit/Crucible (permissions, command registration), AkashicFM (audio, Stretch), WAILA (IMC provider, only if time allows), Angelica (none needed, see 6).
- NOT USED for 10-25: GTNHLib (0.10.7 in the snapshot), UniMixins (0.3.1), ModularUI2 (2.3.67), StructureLib (absent), NEI.
- Reword D-004 with the owner as a quality bar, not a dependency list.

3. Version handshake: set @Mod(acceptableRemoteVersions="[1.0,2.0)"), a range tied to a protocol major. Send S2CHello{protocolVersion, sitePolicy, killSwitch} on PlayerLoggedInEvent. A protocol mismatch gives a placeholder and a message, not a disconnect. This lets server-only and client-only Java fixes ship during the event week without forcing everyone to update.

4. Server rules for Crucible on Java 21:
- Commands: register /web and /webadmin through Crucible's registerCommand(String group, ICommand) when it exists, which gives stable nodes akashicweb.web and akashicweb.webadmin; otherwise use registerServerCommand. Always re-check rights inside processCommand: op via func_152596_g, or Bukkit hasPermission("akashicweb.admin") by reflection on the main thread, or a config UUID list.
- Pointer and keyboard input: our own C2S packets with server-side reach and ray validation, rights, rate limits, length caps and epoch. Ignore FakePlayers. onBlockActivated only opens the GUI.
- Placement: Forge/Crucible may roll a placement back after onBlockPlacedBy when BlockPlaceEvent is cancelled. Defer multiblock formation, registry entries and audit lines one tick through ServerActionQueue, then re-validate. Self-heal on chunk load and neighbour change (WorldEdit and Schematica also exist in the pack).
- TE: non-ticking (canUpdate false). Push state through the description packet and our own packets. Find players to notify with the public PlayerManager.isPlayerWatchingChunk (Crucible and Hodgepodge both modify PlayerManager internals). Key maps by (dimension, x, y, z) values, not TE objects (Crucible overrides TileEntity.hashCode).
- Server-reachable classes must not use javax.xml.bind, Nashorn, sun.* or reflection into the JDK.
- Authentication gate: drop C2S screen packets from players a login plugin has not authenticated. Which plugin to probe is decided after the owner answers; if login happens at the proxy, no gate is needed.

5. Velocity and transfers: FML 1.7.10 clients accept HandshakeReset without disconnecting. The client must therefore tear everything down on the client-side WorldEvent.Unload as well as on ClientDisconnectionFromServerEvent: close browsers, clear input queues, reset the clock sync, deny new browsers until the new S2CHello arrives. The server side relies on PlayerLoggedInEvent per backend. Every backend a player can reach with the telão should run the same AkashicWeb version. Velocity's docs say Forge-Bukkit hybrids are not supported, so this must be tested if Velocity is used.

6. Client and platform:
- Tier 1 for Technic desktop is Windows + Java 8 (8u51 seen) + LWJGL 2.9.1 + Angelica 2.1.33 with BSL on and off.
- Drop the lwjgl3ify and Java 17-25 client cells for 10-25 (keep the reflective guards).
- The screen TE returns INFINITE_EXTENT_AABB, and the TESR does its own frustum and distance test against the true box.
- Android (ForbiddenMobile) and any non-x64 platform get a static placeholder and never load client.web; avoid AWT in every client path.
- Ship assets/akashicweb/glyphs/modern_fonts.json = {"providers": []} so NecroTempus logs an INFO line instead of an ERROR with a stack trace on every launch.

7. Pack settings the owner controls:
- raise or disable FpsReducer's idle reduction for the event (idleFPS=10 after 300 s would make a still viewer's telão run at 10 FPS);
- add our TE to entityculling.json's blockEntityWhitelist if G3 shows culling;
- check BlockLimiter on the server for screen-block caps.

8. CI checks on the built jar (adapt Akashic Voice's inspect_jar idea):
- every class is major 52;
- no TweakClass or FMLCorePlugin in the manifest;
- no class outside client.web (and org.cef itself) references org/cef;
- common and server classes do not reference net/minecraft/client or org/lwjgl;
- nothing references org/lwjgl/system, sdl or glfw;
- no natives inside the jar.

9. E2E: add a 'forbidden' pack definition to the copied tools/prod (from the owner's real list), plus a Crucible variant (the production staging build, pinned by SHA-256) on Java 21 with java9args.txt, a permissions plugin and a protection plugin.

AFTER THE EVENT:
- Re-evaluate UniMixins only for a proven need (deterministic shutdown, netty-side clock stamping), with required-after:gtnhmixins.
- ModularUI2 for the screen configurator (rights and friends lists).
- WAILA provider.
- org.cef shipped inside the native bundle with a child classloader (A14) together with the separate browser-host process.
- Revisit Angelica's registerDynamicClass if the pack moves to Angelica 2.2.x or later.

NOT RUN: no build, no game client, no Crucible boot. Everything here comes from reading code and docs, blobless clones, the pack snapshot and web documentation.

### Spike

One-day skeleton spike (the owner runs the Windows part).

(1) Build a skeleton with the A2 build: GTNHGradle 2.0.33, Jabel, usesMixins=false, Forge-only at runtime. It contains one screen block and a non-ticking TE (INFINITE render box, TESR draws a static test texture with its own frustum check), one C2S pointer packet relayed S2C, an S2CHello with protocol version and acceptableRemoteVersions="[1.0,2.0)", /web and /webadmin with internal checks plus the reflective Bukkit permission bridge and Crucible group registration, and the empty NecroTempus fonts file. CI must show: all classes major 52, no TweakClass or FMLCorePlugin in the manifest, the org.cef, client and lwjgl reference scan clean.

(2) Server: boot the production Crucible staging build on Java 21 with java9args.txt and the owner's server mods folder (at minimum the snapshot's GTNHLib 0.10.7, UniMixins 0.3.1, Hodgepodge 2.7.160, plus AkashicFM), the permissions plugin and the region plugin. Pass if:
- the server starts with zero AkashicWeb errors;
- /webadmin is denied to a non-op, allowed to an op, and allowed to a non-op who has akashicweb.webadmin;
- a 3x2 screen placed by a non-member inside a protected spawn region is reverted and leaves no server registry entry or audit line;
- a pointer packet from a player outside reach, without rights, or not yet authenticated is rejected and audited;
- a member's pointer packet reaches every tracker.

(3) Client: Technic Windows client (Java 8u51, the live mod list including Angelica 2.1.33), with BSL off and then on. Pass if:
- the screen renders with the origin block off-screen and from behind a wall corner;
- there is no GL error or crash;
- NecroTempus logs INFO, not ERROR, for the akashicweb domain;
- an Android ForbiddenMobile client (if available) joins and shows the placeholder with no exception.

(4) If Velocity is in use: two backend transfers with no stale screen, browser or clock state, and no AkashicWeb exception.

(5) The owner runs akashic-world-firewall's audit on the new jar: no new blocking finding.

Any failure in (2) or (3) points to a specific item in this recommendation that must change before the G1/G3 dates.

### Alternatives

| Option | Fits 1.7.10/Java 8 | H.264/AAC | Licence/cost | Maturity | Effort | Pros | Cons |
|---|---|---|---|---|---|---|---|
| A1. Plan as written: GTNHGradle 2.0.33 + Jabel + hard GTNHLib + hard UniMixins (shutdown mixin) + SimpleNetworkWrapper + WD-derived multiblock + vanilla GuiScreen | yes | not-applicable | Build tools are not redistributed. The runtime dependencies are already in the pack and we would not redistribute them. Their licences were not checked here. | GTNHGradle: latest tag 2.0.34, HEAD 04d5d0f on 2026-09-29. GTNHLib: latest tag 0.11.52. UniMixins: latest tag 0.3.2 (the pack has 0.3.1). | 0.5-1 for the skeleton, plus 0.5 to test against the pack's GTNHLib | Same as AkashicFM, so one mental model. GTNHLib 0.10.7 and UniMixins 0.3.1 are in the pack snapshot and AkashicFM requires both, so the hard dependencies would very likely be met. @Config gives an auto-generated GUI and server-to-client config sync. | usesMixins=true writes TweakClass MixinTweaker, MixinConfigs and FMLCorePluginContainsFMLMod into the manifest, which makes UniMixins a launch-time requirement. It puts AkashicWeb in the mixin-conflict surface of Hodgepodge, Angelica, ArchaicFix and CoreTweaks. GTNHLib API drift is real: AkashicFM needed a second reload path below 0.9.62. The pack's GTNHLib 0.10.7 is not a version AkashicFM tested (0.5.23, 0.7.10, 0.11.52). The only planned mixin, the shutdown hook, can be done without a mixin. It conflicts with the Akashic Voice contract for the same pack. It adds nothing to the 10-25 critical path. |
| A2. RECOMMENDED: GTNHGradle 2.0.33 + Jabel, Forge-only at runtime (no hard mod dependencies, no mixins, no coremod), SimpleNetworkWrapper + own protocol handshake, WD-derived multiblock, vanilla GuiScreen + Forge GuiConfig, soft integrations via reflection or IMC | yes | not-applicable | No new runtime licences. Code copied from AkashicFM has the same owner. | The in-house pipeline is proven by AkashicFM 1.2.0 (2026-10-05) and Akashic Voice RC7. Pin GTNHGradle 2.0.33 and Gradle 9.7.1 (as AkashicFM) through 10-25; autoUpdateBuildScript=false. | 0.5-1 for the skeleton plus 0.5 for CI architecture checks | Nothing in the manifest can stop the game from launching, and there is no mixin-conflict surface. The mod behaves the same if the pack updates GTNHLib, UniMixins or Angelica. Fewer findings are likely from akashic-world-firewall. It loads cleanly on ForbiddenMobile, where the client shows a placeholder. Forge 10.13.4.1614 already ships GuiConfig, IModGuiFactory, Configuration, SimpleNetworkWrapper, MouseEvent and NetworkCheckHandler. AkashicFM's ServerActionQueue, ClientTaskQueue, RateLimiter, TextSanitizer, Permissions (FakePlayer-safe op check), AuditLog, UrlPolicy, FmCommand RCON pattern, ToolManager and tools/e2e+prod can be copied with attribution (same owner) rather than depended on. | No @Config auto-GUI or config sync: we write a small typed wrapper around Forge Configuration, and policy reaches clients through our own S2CHello packet (needed anyway for the kill switch). Deterministic CEF shutdown must use a JVM shutdown hook, polling of Display.isCloseRequested() and Minecraft.running, and the Windows Job Object, instead of a mixin. Packet arrival times are stamped in the handler, not on the netty thread (AkashicFM measured ±25 ms bias, acceptable for a 0.5-2 s sync target). |
| A3. FPGradle (FalsePattern, com.falsepattern.fpgradle-mc) | yes | not-applicable | Build-time only; licence not checked. | Latest 4.1.0 (2026-08-09) on the Gradle plugin portal. That it is built on RetroFuturaGradle is INFERRED and not checked. | 1-2 | Actively released (4.1.0 on 2026-08-09). Modern Gradle. Used by FalsePattern mods. | No in-house experience. It would split the toolchain from AkashicFM and Akashic Voice, which both use GTNHGradle. No gain for this mod. Learning cost during a 14-day window. |
| A4. Raw RetroFuturaGradle (no GTNH conventions) | yes | not-applicable | Build-time only; licence not checked. | Recent RFG tags include 2.0.4-2.0.6 (git ls-remote). | 1-2 | Fewer layers and full control. | Re-implements what GTNHGradle already gives: manifest attributes, Jabel toolchain, run tasks, reobf chain, dependency substitutions, Spotless. It loses parity with AkashicFM's harness. No gain. |
| A5. anatawa12 ForgeGradle-1.2 fork | yes | not-applicable | Build-time only; licence not checked. | Maintenance not confirmed. | 1-2 | Closest to the original MDK. Simple. | Old Gradle (the docs say 4.4.1+, a forum report says 5.0+). No Jabel or mixin integration. No in-house use. Weaker test and run tooling. Its maintenance status was not confirmed. |
| A6. GTNHGradle jvmDowngrader mode instead of Jabel | partly | not-applicable | jvmdowngrader-java-api is LGPL-2.1 per GTNHLib's dependencies.gradle comment. | Present in GTNHGradle HEAD (2026-09-29). | 0.5-1 | Java 9+ APIs could be used directly. This mode is what GTNHLib itself uses. | The default stubs provider is 'gtnhlib', which silently adds a runtime dependency on GTNHLib. The jar becomes multi-release (21/25). More moving parts in the reobf chain. ProcessHandle is only needed in one place, and MethodHandles behind a java.specification.version check covers it. |
| A7. GTNHLib as an optional or soft dependency (config, @EventBusSubscriber, NetworkChannel, config sync) | yes | not-applicable | Already in the pack; we do not redistribute it. Licence not checked. | The pack snapshot has 0.10.7 (2026-05-25); the latest tag is 0.11.52. | 0.5 either way (GTNHLib @Config or Forge GuiConfig) | Nicer annotation-driven config and GUI. Server-to-client config sync. Shares conventions with AkashicFM. | Config is needed on the server and on every client, so a 'soft' config library ends up as two code paths. GTNHLib is itself a coremod plus mixins (it requires UniMixins). Our needs are small (about 20 keys). ServerThreadUtil duplicates AkashicFM's ServerActionQueue. |
| A8. ModularUI2 2.3.67 (present in the pack) for the address bar, keyboard and configurator GUIs | yes | not-applicable | Already in the pack; licence not checked. | Tag 2.3.67-1.7.10 commit 0e8cf99 (2026-05-25). | 2-4 (vanilla GuiScreen: 1-1.5) | It is in the pack snapshot. Lists of friends and rights and sync between the GUI and the server would come for free. It would look more modern. | Hard runtime requirements: gtnhmixins@[2.0.1,) and gtnhlib@[0.2.0,), with NotEnoughItems as an implementation dependency. It is a coremod plus mixins. Its API moves fast (2.3.6x releases). Raw keyboard capture with ABNT2 dead keys and CEF key events needs low-level Keyboard event handling that its abstractions would hide. It is not on the 10-25 path. |
| A9. StructureLib for the multiblock | partly | not-applicable | It would have to be added to the pack; licence not checked. | Not checked. | 2+ (WD port: 0.5-1) | Mature in GT-style packs. Hologram previews. | Not in the pack (another mod to add on the client and the server). Designed for controller-based fixed shapes, not a flood-fill rectangle of any size from 2x2 to 16x16. WD's public-domain Multiblock, BlockSide and hit2pixels already solve our case. |
| A10. WAILA and NEI integration via IMC | yes | not-applicable | compileOnly; not redistributed. | WAILA 1.19.29 in the pack; AkashicFM compiles against waila:1.19.34:dev. That the IMC API is the same across those versions is INFERRED. | 0.5 | Waila 1.19.29 is in the pack. AkashicFM already uses this exact pattern (compileOnly WAILA API, IMC 'register'), with no runtime dependency. Helps moderation at spawn. | Nice-to-have only. NEI integration has almost no value for 2-3 blocks. |
| A11. Crucible/Bukkit permission bridge by reflection + group command registration | yes | not-applicable | None (reflection only, no compile dependency on GPL-3.0 Crucible or Bukkit code). | Crucible HEAD d2feb1f (2026-10-09); automatic staging builds (dev-271, 10 Oct). | 0.5-1 including one Crucible boot test | Admins are managed with the server's permissions plugin (LuckPerms or PEX, still to confirm). Permission nodes stay stable when classes are renamed. It closes a real hole: on Crucible, canCommandSenderUseCommand is bypassed. Falls back to op (func_152596_g) plus a config UUID list on plain Forge (dev and E2E). | Reflection into a server whose exact build is unknown. Must run on the main thread. Bukkit's default for an unregistered node is OP, which needs documenting. akashic-world-firewall might flag reflection into Bukkit (rules unknown). |
| A12. Screen input through Block.onBlockActivated (Web Displays style) versus our own C2S input packets with server-side ray validation | yes | not-applicable | None. | n/a | 1 (similar to the onBlockActivated path, plus 0.5 for validation) | Own packets are not cancelled by WorldGuard/GriefPrevention PlayerInteractEvent handling at spawn. They support hover, drag and wheel, which onBlockActivated cannot. FakePlayers (MFR, OpenComputers robots, golems) cannot click. Behaviour is the same on plain Forge (dev) and Crucible. | They bypass the server's region rules for screen input, so our rights model becomes the only authority (acceptable: building a screen still goes through protected placement). More validation code to write. |
| A13. Angelica integration: TileEntityRenderBoundsRegistry or a finite box, versus INFINITE_EXTENT_AABB plus our own culling | yes | not-applicable | No dependency (no API call is needed). | Angelica 2.1.33 (99ba7fa, 2026-06-07) in the pack; registerDynamicClass appears only in later Angelica (local HEAD after 2.2.29). | 0.25 plus a G3 cell | With INFINITE_EXTENT_AABB, Angelica classifies the class as infinite and renders it as a global TE (never section- or frustum-culled by Angelica), and the stale per-TE box cache never matters. The same code works on vanilla. A finite, registry-based approach would let Angelica do the culling for us. | With an infinite box the TESR runs for every loaded screen, including Iris shadow passes, so the TESR must do its own frustum and distance test against the real multiblock box. A finite box is unreliable on 2.1.33: there is no registerDynamicClass, and the cached box is not refreshed when the TE's size arrives later. |
| A14. org.cef Java classes shipped inside the downloaded native bundle (child classloader) instead of inside the mod jar | partly | not-applicable | None beyond the BSD java-cef. | Not attempted by anyone here. | 2-3 (defer until after the event) | The dedicated server jar contains no org.cef or native-loading code at all, so there is less for akashic-world-firewall to flag. The JNI library and its Java classes always stay in lockstep (libjcef must match exactly). Isolates our org.cef from another mod's copy. | Classloader complexity (JNI FindClass on native threads, transformers not applied in the child loader). GL upload code must stay on the mod side so Angelica redirects it. More failure modes during the event week. A fix to the Java side of org.cef would then need a bundle re-download. |

### Open questions

- Send the LIVE mods/ listing (file names + SHA-256) of the Technic client as served by Solder and of the Crucible server, plus the server's plugins/ listing. The public Technic-Forbidden repo (main 4df9059, mobile-launcher-dev f3150bb) lacks AkashicFM, Akashic Voice and Distant Horizons, which the field logs show, so it is not the production pack.
- Which exact Crucible build runs in production (staging-xxxx / dev-NNN), with which Java 21 vendor/version and JVM flags (java9args.txt or custom)?
- Is the network online-mode or offline-mode? Where are players authenticated: a proxy limbo, or a backend plugin such as AuthMe or nLogin? Are backend ports firewalled, or protected by something like BungeeGuard, so forwarded UUIDs cannot be spoofed?
- Is Velocity in front of the Crucible servers in production (its docs say Forge-Bukkit hybrids are unsupported)? Which backends exist, which one hosts the spawn telão, and will every reachable backend run the same AkashicWeb version?
- Which permissions plugin (LuckPerms, PermissionsEx, GroupManager) and which region-protection plugins (WorldGuard, GriefPrevention, Factions, others) run on the server? Who should get akashicweb.admin?
- What does akashic-world-firewall flag (bytecode patterns such as System.load, ProcessBuilder, URLConnection, reflection into Bukkit; jar names; modids)? Can AkashicWeb be allowlisted, and can its audit run in our CI?
- Does any desktop player use a launcher other than Technic, for example Prism with lwjgl3ify on Java 17+? Stock Technic launch (LaunchWrapper 1.12) cannot run on Java 9+. Can a preflight packet report java.version, the launcher and the OS to settle this?
- Is CubicChunks (repos Akashiic/CubicChunks1710 and Akashic-CubicChunks-CrucibleCompat) used in production worlds, especially the spawn world? It replaces chunk tracking and the world height limit.
- Can the owner change pack configs for the event: FpsReducer waitingTime/idleFPS, the entityculling.json whitelist, and the server-side BlockLimiter limits for screen blocks?
- Does the owner accept rewording D-004 as a quality bar (GTNH buildscript, tests, E2E, evidence) rather than a mandate to hard-depend on GTNHLib and UniMixins?

## Multiplayer model of a shared interactive browser: client browsers with server-authoritative URL, input relay and a shared video clock, compared with leader/follower state sync, DOM mirroring, server-side rendering or cast, hybrid, and per-player private screens

**Recommendation:** `keep-with-changes` (confidence medium)

### Current choice assessment

VERDICT: the base model is right for 10-25, but the plan as written has one security bug and four sync blind spots. All grades are VERIFIED (seen) unless marked INFERRED.

STRENGTHS
- Zero server bandwidth and CPU. Each player watches through YouTube's own page or embed, which YouTube allows ("You may also show YouTube videos through the embeddable YouTube player"; research 05 line 129, VERIFIED). Server-side rendering would make us a retransmitter, the D-008/Lei 9.610 art. 95 problem.
- Server URL authority removes Web Displays' "CANNOT be fixed" FIXME (research 01 line 50, VERIFIED).
- The clock code already exists in-house. AkashicFM ClockMath takes the median offset of the lowest-RTT samples and stamps packets on Netty arrival (AkashicFM audio/relay/ClockMath.java; mixins/common/MixinNetworkManager.java:29, VERIFIED). AkashicFM measured 1.6-6.8 ms inter-player sync for relayed audio (docs/FASE6-RESULTADOS.md:107,118; ADMIN.md:45; Linux harness, VERIFIED).
- No new media pipeline to build.

WEAKNESSES
1) SECURITY BUG, a confused deputy. PLAN 4.1 gives each instance one persistent profile. PLAN 4.5 relays the controller's clicks and keys to every client. So a relayed click runs inside each follower's own logged-in session: subscribe, like, post, buy. Web Displays did the same: it broadcast clicks, typing and even arbitrary JS to everyone within 64 blocks (montoyo/webdisplays TileEntityScreen.java:262-263, 457-473, 752-797, 1056, VERIFIED).
2) DIVERGENCE. Raw pixel input lands on pages that differ per client: per-profile ads (embeds follow the same ad settings, support.google.com/youtube/answer/132596, VERIFIED), consent and LGPD banners, A/B buckets, personalised feeds, lazy-load layout shift, fonts.
 - Misdirected clicks can toggle play/pause out of phase.
 - Typed text can hit YouTube's hotkeys on a follower whose focus differs (k, j, l, m, digits; INFERRED).
 - The plan cancels followers' main-frame navigations, but YouTube's SPA route changes never go through onBeforeBrowse (INFERRED).
3) VIDEO CLOCK BLIND SPOTS:
 - (a) The YouTube IFrame API's setPlaybackRate is "not guaranteed" and rounds unsupported values toward 1, so ±5% steering through it does nothing. The rate must be set on HTMLMediaElement directly (developers.google.com/youtube/iframe_api_reference, VERIFIED).
 - (b) A live player cannot run faster than the live edge (INFERRED).
 - (c) Each client gets its own pre-roll and mid-roll ads. Teleparty still ships "ad synchronization" fixes (Edge add-on notes, VERIFIED weak).
 - (d) PLAN 4.5 has the server stamp the state, and pins every send to the main thread. If the stamp also happens on the tick thread it picks up 50-100+ ms of jitter when TPS sags on event night (INFERRED).
4) COST SCALES WITH SCREENS. Each browser costs about 0.43-0.54 GiB and about 40% of a core at 1080p30 (PLAN section 3, Linux, VERIFIED). Six screens in range is about 3 GiB per player. The plan has no per-client browser budget.
5) BLAST RADIUS. A malicious allowed page reaches every nearby client, and the JCEF sandbox is off (PLAN 4.7, VERIFIED).
6) MEASUREMENT. G4's "skew ≤2 s" cannot be measured without telemetry (research 06 line 29, VERIFIED). The 0.5-2 s target is also lax: clock steering reaches ms-level for audio (AkashicFM) and under 10 ms for browser media (W3C timing CG, INFERRED for our case). Proximity voice at spawn makes skews above about 0.5 s noticeable (INFERRED).
7) PLATFORMS. Every client needs the codec CEF (G1, Windows untested). Android/ForbiddenMobile players get nothing.

### Recommendation detail

FOR 10-25: keep each-client-runs-its-own-browser (alternative B). Do NOT build server-side rendering or cast for the event. The reasons are effort (6-15 days), rights and D-008 (the server would retransmit third-party video), the YouTube bot-wall on datacenter IPs, server egress (≈1.8-3 Mbit/s per viewer), and head-of-line blocking on the game TCP connection.

MUST, about 1.5 days, before G3:
1) Shared screens get their own in-memory CefRequestContext (CefRequestContext.createContext; empty cache_path = in-memory), disposed when the screen unloads. The persistent profile is reserved for private screens. This closes the confused deputy: a relayed click can no longer act with a follower's own login. It also removes per-profile divergence (consent, history-based feeds).
2) Media sync as client-side stamping:
 - The controller samples {mediaTime, paused, rate, adShowing, secondsBehindLiveEdge, fullscreen} at 4 Hz and stamps each sample in server time with AkashicFM ClockMath.
 - The server validates and relays, with no tick-thread re-stamp (or stamps at Netty arrival, like MixinNetworkManager). It keeps an extrapolated clock (mediaTime + (now - t)*rate), so followers need not see the controller, and the server takes over if the controller leaves.
 - Followers use a ±40 ms deadband and proportional steering of HTMLMediaElement.playbackRate in 0.95-1.05, never the IFrame API, which rounds to 1.
 - Seek only above 1.5 s (VOD).
 - Lives: target lag = max of the in-range clients' lags + 0.5 s. Never seek forward past the edge.
 - A follower whose own ad is showing is excluded and resynced after the ad.
3) 1 Hz skew telemetry per client (serverTime, mediaTime, adShowing). The server logs p50/p95 per screen. Retune G4 to: live p95 ≤1 s, VOD p95 ≤0.3 s, ≥90% of samples not in ads.
4) No relay of arbitrary JS (Web Displays let CHANGE_URL also run JS). One controller lock at a time. Visitors on the telão watch only.

SHOULD, 1.5-2 days, cut from the bottom first:
5) Fullscreen sync: when the controller fullscreens the video, followers fullscreen their main video. On the telão this hides almost all page divergence.
6) Same-document URL convergence via onAddressChange, with URL normalisation and the epoch, to avoid reload loops.
7) Per-client browser budget: 2 browsers on ≤8 GB RAM, 4 by default. Rank screens by size × proximity; the rest show a frozen frame plus 'aproxime-se'.
8) Scroll convergence by anchor (element at viewport centre plus offset, 2 Hz while scrolling).
9) Uniform client environment: pt-BR locale and Accept-Language, DPR 1, fixed resolution per screen.

DATA MODEL NOW: add screen 'mode' {SHARED, PRIVATE, (future) CAST} to the NBT and protocol from day 1, so post-event features need no migration.

AFTER 10-25:
(a) Ship PRIVATE screens (H, 0.5-1 day). Site policy: allowlist on SHARED public screens; blocklist plus the internal-address block on PRIVATE screens, since only the user's own PC is exposed.
(b) Spike CAST mode (E): the owner's client encodes VP9/Opus. Transport stays off the game TCP connection (UDP like Akashic-Voice, or WebRTC). Followers decode in their own CEF (WebCodecs/MSE through a custom scheme) or with JavaCPP FFmpeg (Java 7 bytecode; Android natives exist). Use it only for content with rights (own streams, TV Câmara CC BY, TV Senado), for ForbiddenMobile viewers, and as an isolation sandbox for untrusted URLs. This requires an explicit owner decision because it borders D-008.
(c) Rejected:
 - Chromium inside the Crucible JVM (D): one crash kills the server; bot-walled IP.
 - DOM mirroring (C): no video.
 - Hyperbeam: third-party, recurring cost.
 - neko (F) is acceptable only as an experiment for non-video co-browsing.

### Spike

WHEN: 10-13, on the owner's Windows PC, piggybacking on the G1 Java 8 probe; about 0.5-1 day.

SETUP: one probe process creates 3 OSR browsers (A = leader, B and C = followers), each with its own in-memory CefRequestContext (separate cookies, so separate ad decisions).
- Inject the sync loop described in the recommendation.
- Give B a simulated +120 ms clock offset and start B and C 2 s and 6 s late.
- Run three sources for 10 minutes each: an H.264/AAC MP4, a monetised YouTube VOD watch page, and a YouTube live (the planned channel, low-latency setting).
- Every 1 s log each follower's error vs A's extrapolated clock, its playbackRate, seeks, ad state and secondsBehindLiveEdge.
- Also check: (i) set video.playbackRate = 1.03 on YouTube and read it back after 5 s; (ii) read the ad indicator (e.g. the .ad-showing class on the player container); (iii) whether currentTime of the same live is comparable across A/B/C; (iv) a second probe loads a page, A logs in to a test site and clicks a relayed button; B and C must stay logged out (confused-deputy check).

PASS (all of these):
- MP4 p95 |error| ≤150 ms after 15 s.
- YouTube VOD p95 ≤250 ms, excluding ad windows.
- Live p95 ≤750 ms, with ≤1 seek per follower per 10 min.
- playbackRate 1.03 holds in ≥95% of reads.
- Ad state detected in 100% of ad windows; no steering during ads.
- Live currentTime offsets between instances stable within 100 ms, so media time is a valid shared axis.
- B and C stay logged out.

ON FAILURE:
- If live currentTime is not comparable, fall back to steering on secondsBehindLiveEdge and widen the live target to ≤1.5 s.
- If YouTube resets playbackRate, steer with micro-seeks only above 400 ms and accept p95 ≤0.5 s VOD.

Network diversity (different ISPs) is tested only at G4 with the telemetry from MUST item 3. The spike does not substitute for it.

### Alternatives

| Option | Fits 1.7.10/Java 8 | H.264/AAC | Licence/cost | Maturity | Effort | Pros | Cons |
|---|---|---|---|---|---|---|---|
| A. Current plan as written (Web Displays semantics plus server URL authority plus video clock) | yes | yes | No new licences. Server egress about 0. Each player downloads the stream from YouTube. | The Web Displays model is old (1.12.2, 2017-era) with a known, unfixed nav FIXME. Our sync layer is new code. | 2.5-4 (already in plan) | No server bandwidth or CPU. Rights-clean: each player is an official YouTube or embed viewer. Already scheduled (10-15 to 10-17). Reuses AkashicFM ClockSync. | Relayed input runs with each follower's persistent-profile credentials (security bug). Raw input lands on divergent pages. Clock blind to IFrame API rate rounding, the live edge, per-client ads, SPA routes and tick-thread stamping. No browser budget. Skew not measurable. Needs codec CEF on every client. Android excluded. |
| B. Hardened client model: state-convergent followers (RECOMMENDED for 10-25) | yes | yes | None. Syncplay algorithm is a reference only (Apache-2.0); no code copied. | Building blocks are proven: Syncplay last commit 2026-08-28, AkashicFM ClockSync measured, java-cef createContext in CCBlueX fe8cc9e. | A + 2.5-3.5 (must-have subset ~1.5) | Removes the confused deputy. Tolerates ads, SPA routes and TPS drops. Expected p95 skew (INFERRED, to confirm by spike): VOD ≤150-250 ms, live ≤0.5-1 s. Divergence on the telão goes to about zero once the video is fullscreen. Makes G4 measurable. Server cost stays about 0. No new natives. | Still needs codec CEF on every client and leaves Android out. Feeds and logins still diverge on shared screens, by design (no logins there). The ad-detection selector and YouTube honouring a direct playbackRate are unverified (spike). Adds 2.5-3.5 days to an overloaded schedule; about 1.5 days are must-have. |
| C. Leader/follower DOM mirroring (rrweb-style co-browsing) | partly | not-applicable | rrweb MIT, free | rrweb 20.3k stars, MIT, active. Live co-browse mode is less proven. | 4-6, plus ongoing breakage | Identical HTML layout for followers: no ad, A/B or feed divergence for text pages. Scroll and typing mirror exactly. | MSE/blob video, cross-origin iframes (the YouTube embed) and canvas/WebGL do not mirror, so the main use case (video) still needs per-client players. Followers still fetch images and CSS from the origin (IP exposure). Many edge cases. JS bridge throughput. |
| D. Server-side rendering in the server JVM (WebDisplaysTogether style) | partly | yes | FFmpeg LGPL-2.1 + JavaCPP Apache-2.0. Server egress scales with viewers. Rights: retransmission needs a licence. | WDT: one author, created 2026-07-26, last commit 2026-09-04, git history only for 1.0-1.01 (CHANGELOG reconstructed from jars). Mysticpasta SSR branch abandoned at prototype (2023-10-19). | 10-15 for 1.7.10 | Every player sees the identical frame; skew is about 50-350 ms with a fixed jitter buffer, ≤50 ms with PTS scheduling on the server clock (INFERRED). No page divergence, shared logins. Player IPs hidden from sites. Only the server needs H.264/AAC. Clients do not need CEF at all, so a door opens for Android: the bytedeco android-arm64 natives exist. | Server retransmits third-party video: the D-008/Lei 9.610 art. 95/YouTube ToS problem. A Chromium crash or leak takes the whole game server down. Datacenter IP likely bot-walled by YouTube. Egress ≈1.8-3 Mbit/s per viewer (100 viewers ≈180-300 Mbit/s; ≈3.6 GB per viewer per 4 h at 2 Mbit/s). One encode per screen means the slowest viewer lowers quality for all. Video on the game TCP connection causes head-of-line blocking of movement and chunk packets. Needs Xvfb and Chromium libs on Linux, and passes through Velocity. A second media pipeline to build. |
| E. Cast from the owner's client (client-hosted SSR) | partly | yes | FFmpeg LGPL if JavaCPP is used; none if the client's CEF decodes. Server egress as D. Rights as D. | Nothing exists for 1.7.10; new design. | 6-10 | Residential IP, so no datacenter bot-wall. Codec CEF needed only on the owner's PC: lives would work even if players fall back to codec-free Spotify CEF 152 (G1 fallback). Identical frames, sub-second skew. Chromium never in the server JVM. Owner's uplink carries one stream (~2-3 Mbit/s). | Same rights problem as D (retransmission). Server egress N×2-3 Mbit/s. Must not ride the game TCP connection (head-of-line blocking), so a separate transport. The owner's PC is a single point of failure. A new pipeline, not feasible in 14 days next to G1/G3. |
| F. External virtual-browser service (neko self-hosted, or Hyperbeam) | partly | yes | neko Apache-2.0 (self-host VPS + egress). Hyperbeam: 10,000 free participant-minutes/month, then $0.007/min. Its page footer says 2022, so freshness is unverified. A 100-viewer, 4 h event is 24,000 participant-minutes. | neko 22.5k stars, v3.1.6 dated 2026-09-30 (active). Hyperbeam status in 2026 unverified. | 1-2 (ops + screen mode), excluding rights work | Lowest mod effort for SSR-like identical frames. neko is mature: WebRTC, VP8/VP9/AV1/H.264/H.265 + Opus, NVENC/VAAPI, multi-participant control. Clients need only stock CEF for VP8/VP9/Opus. | Same rights and bot-wall problems as D. WebRTC needs UDP ports or TURN on a host (extra attack surface; player IPs reach that host). neko fans out N separate streams, so host egress is N×bitrate. Linux Docker only. Hyperbeam is a US third party with per-minute fees. |
| G. Hybrid: B everywhere plus an opt-in 'cast' mode for selected screens (after 10-25) | partly | yes | As B for normal screens; as E for cast screens. | New. The WDT design is the only reference and is young. | B + 6-10 (post-event) | Keeps the rights-clean per-client default. Gives tight sync, an Android path and a security sandbox where they matter. The data model can carry the mode bit from day 1. | Two pipelines to maintain. Every cons of E applies to cast screens. Needs an owner decision on whether cast is 're-streaming' under D-008. |
| H. Per-player private screens (visibility = PRIVATE) | yes | not-applicable | None | Trivial extension of the rights and NBT model. | 0.5-1 | No divergence (no followers). Logins and cookies safe. Looser site policy is acceptable because only the user's own PC loads the page. Saves CPU/RAM on everyone else. Minepad-style use. | Not a shared experience. Needs clear UI so players understand why they see a placeholder. Should not be used for the telão. |
| I. CinemaMod-style curated player page | yes | yes | Hosting a small HTTPS page | CinemaMod last commit 2025-01-13 | 1-2 | Small, predictable page: no feed, comments or ads around the player. Easy start and seek control. | Not the real browser (D-002 risk if it becomes the default). IFrame API cannot do fine playbackRate steering. CinemaMod itself only does a one-shot seek in whole seconds from server vs client wall clocks, nothing for lives, and its seekVideo is a TODO. Embeds can be disabled by the broadcaster and need a Referer. |

### Open questions

- How many players do we expect at spawn on 10-25, and how many on ForbiddenMobile (Android)? In the client model Android players see only a placeholder; only a future CAST mode could serve them.
- Which channel will the telão show (P-07)? Is it monetised (pre-roll and mid-roll ads per client delay first frame and break sync), what is its latency setting, and does it allow embedding?
- For shared screens, does the owner want followers to see the controller's scroll and typing (full Web Displays semantics), or is 'same URL + same video moment + same fullscreen' enough? This decides how much of the SHOULD list is needed.
- Will Akashic-Voice proximity chat be active at spawn during the event? If so, inter-player skew above ~0.5 s becomes audible through reactions, and the G4 target should be tightened.
- Does the owner consider a CAST/SSR mode of third-party video to be the 're-streaming' abandoned in D-008, or is it acceptable for content with explicit rights (TV Câmara CC BY, TV Senado, own streams)?
- What is the production host's egress cap and price, and is Velocity in front of Crucible in production? This only matters if CAST is ever built.
- Unverified YouTube behaviours (spike): does YouTube honour a direct HTMLMediaElement.playbackRate of 1.03 on watch pages and lives? Is live currentTime the same axis across viewers? What reliably signals 'ad showing'? Does element fullscreen in CEF OSR fill the view as expected?
- Do 1.7.10 SimpleNetworkWrapper handlers run on the Netty thread (as assumed, INFERRED)? This affects where the server must stamp video state if client-side stamping is not used.

## Distribution and updates of the native browser runtime (CEF ~360 MB per platform): NativesInstaller design, hosting, compression, deltas, code signing, launcher interaction, integrity, Windows path/AV issues, resume

**Recommendation:** `keep-with-changes` (confidence medium)

### Current choice assessment

The plan (PLAN 4.8, T-06, PROBLEM_FIX_MATRIX rows 25-27 and N5-N9) is a custom NativesInstaller: a manifest pinned in the jar, SHA-256 checked before extraction, resume, mirrors, atomic install under %LOCALAPPDATA%. On Windows it downloads OBS's full CEF zip from cdn-fastly.obsproject.com and extracts Release/ and Resources/ (~415 MB). The direction is right. Some of the evidence behind it is weaker than the docs say, and one big saving was missed.

STRENGTHS (each verified):
- Pinning hashes in the jar is the right trust root. Integrity comes from the hash, not the transport.
- Installing outside the instance is essential on Technic. Technic launcher 4.0-1170 deletes mods/ and removes orphaned files on every pack update (ImmutableInstallerPlanner.java:493-520, 663-700).
- %LOCALAPPDATA% is outside Controlled Folder Access (CFA) default folders and outside OneDrive Known Folder Move (KFM).
- OBS's CDN supports Range requests (HTTP 206), sends a stable ETag, and keeps old builds for years.
- The OBS zip matches obs-studio's pin: sha256 54cd191a...f341, 344,759,147 bytes.

WEAKNESSES:
1. "Built from AkashicFM ToolManager" overstates the reuse (VERIFIED). ToolManager is server-side. It trusts a SHA-256 fetched from the same "latest" GitHub release. It has a 250 MiB cap, no Range resume, no mirrors and no consent prompt (ToolManager.java:30, 44-49, 166-173, 347-369). NativesInstaller is essentially new code.
2. The premise "CinemaMod did delta updates" is false (VERIFIED). MCEFDownloader downloads a plain tar.gz from an S3 bucket. It only compares the new .sha256 file with the old one and never hashes the archive. It has no resume and no tar-slip guard (MCEFDownloader.java:41-42, 118-192; java-cef build-jcef.yml:33-40).
3. Downloading the whole OBS zip wastes bandwidth (VERIFIED). The 23 files the runtime needs are 358.5 MB on disk (locales cut to en-US/pt-BR). Inside the zip they are only 158.3 MB compressed. A Java-8-bytecode probe fetched exactly those entries with 48 Range requests: 158.5 MB in 10.9 s. The output was byte-identical to a full extraction. That cuts the download by 54% (344.8 -> 158.5 MB) while still pulling only OBS's own bytes, so D-011 holds.
4. Treating Akashic's own natives as part of a "frozen buildId" couples two very different layers. jcef.dll (~0.9 MB) plus jcef_helper.exe (~0.6 MB) are tiny (jcefmaven CEF132 natives, VERIFIED). They can ride inside the mod jar. Then our native patches become a mod update and never force a CEF re-download.
5. Re-verifying all hashes at every startup means reading 359 MB on every launch, plus antivirus scan time (INFERRED cost).
6. Old-JRE TLS is mostly solved upstream (VERIFIED):
   - Players run 1.8.0_51 (Akashic-Voice FIELD_EVIDENCE:13).
   - Technic 4.x installs Mojang runtimes and checks each file by SHA-1.
   - Mojang's jre-legacy "8u51-cacert462b08" (2025-10-06) ships a refreshed cacerts with 143 roots, including ISRG X1/X2, GTS R1-R4 and DigiCert G2.
   Bundled roots become a fallback, not a requirement.
7. Code signing "later" is not a cheap fix (VERIFIED):
   - Azure Artifact Signing (Public Trust) is closed to Brazilian individuals and organisations.
   - OBS's libcef.dll and most CEF DLLs are unsigned. Smart App Control (SAC) checks every DLL, so signing our 1.5 MB alone cannot make the runtime SAC-proof.
8. The ~855 MB Technic pack is non-Solder and is re-downloaded whole on every version (VERIFIED). Putting CEF in the pack would tax every future update and Android players too.

NOT RUN: nothing on Windows; no real Java 8 JVM (JDK 21 ran major-52 classes); no TLS chain check (the proxy re-terminates TLS); no AV or SAC test.

### Recommendation detail

Keep a custom installer with hashes pinned in the jar, but redesign it as a two-layer runtime. The CEF bytes come from OBS by HTTP Range; our JNI glue rides in the jar.

FOR 2026-10-25

1. Layers.
   - Layer 1, CEF runtime: OBS 7871 win64, 23 files, 358.5 MB on disk, locales cut to en-US/pt-BR (and their _FEMININE/_MASCULINE/_NEUTER variants). Path: %LOCALAPPDATA%\AkashicWeb\cef\obs7871-win64-&lt;setHash8>\. Shared by all instances and mod versions; never under the Technic instance.
   - Layer 2, JCEF glue: jcef.dll plus the renamed helper (~1.5 MB), embedded in the mod jar. Extracted into the Layer 1 folder under versioned names (the helper must sit beside libcef.dll, INFERRED). Checked against SHA-256 values in the jar.
   - Change T-10 from "freeze native buildId 10-16" to "freeze the CEF layer 10-16". Native JNI fixes after that are ordinary ~1.5 MB jar updates.

2. Windows acquisition order.
   a) Range-fetch the 23 entries from https://cdn-fastly.obsproject.com/downloads/cef_binary_7871_windows_x64.zip: 158.3 MB.
      - CI generates the manifest: entry offsets, sizes, per-file SHA-256 and per-8-MiB-chunk SHA-256, after checking the zip against obs-studio's pin 54cd191a…f341.
      - The client sends If-Range with the ETag, uses 2-3 parallel connections, and retries a single chunk when it fails.
   b) If the CDN answers 200 instead of 206: stream the full zip once, keep only the needed entries, and check both the zip hash and the per-file hashes.
   c) Manual import. The pt-BR screen shows the OBS link; the player saves the zip into %LOCALAPPDATA%\AkashicWeb\import\. Covers TLS, firewall and DNS-filter failures with no redistribution.
   d) Optional Akashic mirror: per-file xz with x86 BCJ, 116.5 MB, on a public GitHub repo with immutable releases. Build the CI step, but publish only if the owner explicitly accepts redistributing codec binaries (a new decision; it touches D-011's context).

3. Timing and user experience.
   - The mod ships in the pack update by 10-16 and doubles as the pre-installer.
   - At the main menu, a pt-BR consent dialog shows exact numbers: "158 MB de download, 359 MB em disco".
   - Download in the background with a progress overlay; resumable across restarts via a state file.
   - No standalone exe.
   - Players who decline still join and see the placeholder.

4. Robustness.
   - Timeouts: connect 15 s, read 30 s. Exponential backoff.
   - Check for at least 1 GB free before starting.
   - Install into a staging folder, then atomic rename plus a .complete marker. Keep the previous layer for rollback; delete older ones.
   - At startup check sizes and the marker only. Run a full SHA-256 re-verify after install and after any load failure.
   - New reason codes: DOWNLOAD_FAILED, HASH_MISMATCH, DISK_FULL, TLS_FAILED (leads to manual import), AV_BLOCKED (file vanished or helper exits immediately), SAC_BLOCKED (load error mentions Application Control).
   - Set the User-Agent to "AkashicWeb/&lt;ver> (+repo URL)" and send OBS a courtesy note.

5. Platform gating before any byte is downloaded.
   - Only Windows x64 on a 64-bit JVM downloads.
   - Android/ForbiddenMobile, arm64, 32-bit and macOS get the placeholder and download nothing.
   - Linux Tier 2 needs a stripped Akashic repack (OBS Linux is a non-seekable tar.xz with a 2.03 GB libcef.so), which means redistribution. Keep Linux first in the cut order unless the owner approves the repack.

6. TLS.
   - Use the JRE's trust store by default. Technic installs Mojang's jre-legacy, whose refreshed cacerts includes ISRG/GTS/DigiCert G2.
   - Keep a small installer-only SSLContext with bundled roots as a fallback. Never call setDefault. Integrity comes from the pins.

7. Hosting cost for 10-25: $0 and no new accounts.
   - OBS/Fastly serves Layer 1.
   - The existing Technic-Forbidden GitHub release carries the jar, which already contains Layer 2.

AFTER THE EVENT

- Signing. Sign Layer 2 with Certum Open Source Code Signing in the Cloud (from €49), or SignPath Foundation (free), once the licence P-08 is OSI and the project is released. Azure Artifact Signing is not available to a Brazilian individual or organisation. Say plainly that SAC may still block OBS's unsigned libcef.dll; detect it and explain.
- Akashic-hosted artifacts. GitHub Releases with immutable releases and release attestations as primary ($0, no bandwidth cap). Cloudflare R2 behind the owner's own domain as a mirror (about $0 at ~1 GB stored and ~300 GB per rollout). Avoid r2.dev, and avoid B2 behind the Cloudflare free CDN.
- Updates. No binary deltas: the measured gain is -33% at best, they need native zstd, and Layer 2 already removes most re-downloads. Per-file content addressing skips unchanged files.
- CEF security patches. If they must reach players without a mod update, add a remote manifest signed with ECDSA P-256 or RSA (verifiable by Java 8's built-in providers), with the key held offline by the owner. Sigstore stays for audit, not in-game.

COST TABLE (assumption: 2,000 installs per rollout, INFERRED from 16,837 lifetime installs)
- Recommended design: Akashic egress ≈ 0. OBS/Fastly serves ≈ 317 GB.
- If the Akashic mirror is used: 233 GB per rollout.
  - GitHub: $0.
  - R2: $0.
  - B2 direct: ≈ $2.30.
- Signing: Certum €49 per listed price; renewal terms not verified.

### Spike

Smallest experiment (owner's Windows PC, ~1 hour): run a Java 8 NativesProbe jar with the Technic Forbidden instance's own runtime, found under %APPDATA%\.technic\runtimes\jre-legacy (expected 1.8.0_51). Adapt it from /tmp/claude-0/-home-user/c5c14dc0-9bf3-521b-b7c6-50e7f96d7b47/scratchpad/challenge/probe/src/RemoteZipProbe.java.

Steps:
1. Range-fetch the 23 entries from the OBS CDN into %LOCALAPPDATA%\AkashicWeb-spike\ and compare against obs7871-win-minimal.sha256 (in the same scratchpad).
2. Abort at about 50% (pull the network cable or kill the process), restart, and resume.
3. Make one HTTPS request to cdn-fastly.obsproject.com and one to release-assets.githubusercontent.com with that JVM.
4. Extract a CCBlueX-built jcef.dll and helper from the jar next to libcef.dll. Run `dumpbin /dependents` on them (no VC++ redist DLLs expected). System.load libcef.dll then jcef.dll. Start the helper once with Defender on, and time the first load.
5. Record the Smart App Control state and Defender protection history.

PASS when:
- fetched bytes are ≤ 159 MB;
- all 23 hashes match;
- after resume, re-fetched bytes are less than one 8 MiB chunk;
- both TLS handshakes succeed on 8u51;
- the full install finishes in under 3 min on the owner's link;
- no antivirus quarantine or block;
- the first libcef load takes under 15 s;
- jcef.dll and the helper need only system DLLs.

FAIL handling:
- any TLS failure: ship the bundled-roots fallback plus manual import;
- antivirus or SAC block: pt-BR guide, reason code, and move signing earlier;
- CDN answers 200 instead of 206: use the full-zip fallback.

### Alternatives

| Option | Fits 1.7.10/Java 8 | H.264/AAC | Licence/cost | Maturity | Effort | Pros | Cons |
|---|---|---|---|---|---|---|---|
| A. Current plan: full OBS zip download, then selective extraction | yes | yes | $0 to Akashic; egress is borne by OBS/Fastly | OBS pins 7871 on master; older OBS CEF builds (5060, 6533) still return HTTP 200 years later | 1.5-2 | Simple. One whole-file hash, OBS's own pin. No redistribution by Akashic (D-011). Range resume works. | Downloads 2.2x more than needed (344.8 MB versus 158.3 MB of needed entries). Needs ~345 MB temp plus ~360-415 MB final disk. Depends on a third-party CDN with no stated terms or SLA. Gives no path for CEF security patches, since OBS pins one build. |
| B. HTTP Range fetch of only the needed entries from OBS's zip (RECOMMENDED primary for Windows) | yes | yes | $0 to Akashic | Plain HTTP/1.1 Range plus the ZIP format; no dependency | 2-3 (CI manifest generator 0.5, installer with chunks/resume/consent/progress 1.5-2, tests 0.5) | 158.3 MB instead of 344.8 MB (-54%). Still OBS's own bytes from OBS's server, so D-011 is unchanged. Per-file and per-chunk hashes are stronger than one whole-zip hash, and a corrupt chunk is re-fetched alone. No extra library: the JRE's native zlib inflates at ~148 MB/s. No temp copy of the whole zip. | Relies on OBS's CDN keeping Range support and identical bytes; a byte change fails closed with HASH_MISMATCH, then fallback. 48 requests per install instead of 1. Third party with no permission or SLA, so a courtesy note to OBS and a descriptive User-Agent are advised. Linux OBS builds are tar.xz, which has no random access, so this works on Windows only. |
| C. Akashic repack: per-file xz (x86 BCJ for DLLs) hosted on GitHub Releases | yes | yes | $0 hosting; legal exposure is the owner's call | xz-java 1.12 (last metadata update 2026-03-01); GitHub Releases are mature | +1 on top of B (CI packing + xz decode + publish) | Smallest download: 116.5 MB (-26% against B, -66% against A). Akashic controls availability. Per-file layout allows file-level dedupe across CEF updates. GitHub has no bandwidth limit and supports Range. Works for Linux too (stripped libcef.so). | Akashic redistributes codec binaries, which changes D-011's stated context and needs an explicit owner decision. Adds 170 KB of xz-java to the jar. Decoding takes ~11 s for 358.5 MB in pure Java (slower on low-end PCs). Repo must be public. |
| D. Embed Akashic-built JCEF glue (jcef.dll + renamed helper exe) inside the mod jar (RECOMMENDED) | yes | not-applicable | $0 | Common pattern (LWJGL, WaterMedia) | 0.5 | No Akashic host needed for 10-25. Java org.cef classes and jcef.dll are version-locked by construction, which closes WD/MCEF coupling problem #58. Fixing our native patches becomes a ~1.5 MB mod update, so T-10 can freeze only the CEF layer. Prior art: WaterMedia ships a 36 MB VLC zip inside its jar. | Jar grows ~1.5-3 MB, and Android/ForbiddenMobile downloads that too. Files must be extracted to %LOCALAPPDATA% before loading; never run from a temp folder. Antivirus scanners might inspect the jar (INFERRED, low). |
| E. Cloudflare R2 with a custom domain (mirror after the event) | yes | not-applicable | Storage $0.015/GB-month (10 GB free); Class B $0.36/million (10M free); egress free. With ~1 GB stored and ~300 GB per rollout: $0 | Mature | 0.5 plus domain setup | Effectively $0 at this scale. Free egress. Full control. Edge caching. A good secondary mirror for GitHub Releases. | Production use needs a domain added as a Cloudflare zone in the same account; the r2.dev URL is rate-limited and for development only. No owner domain is known (redeakashic.com.br does not resolve). Third-party guides say a payment method is needed. Not needed for 10-25 if B+D are used. |
| F. Backblaze B2 (direct, or behind the Cloudflare free CDN) | yes | not-applicable | $6.95/TB-month (first 10 GB free); egress free up to 3x average storage, then $0.01/GB. A 233 GB rollout costs about $2.30 direct | Mature | 0.5 | Cheap. No domain needed for direct downloads. | With ~1 GB stored only ~3 GB/month of egress is free; a rollout costs a few dollars. Putting 100+ MB binaries behind the Cloudflare free CDN conflicts with Cloudflare's terms for non-Enterprise plans (large files need paid services), and .xz is not cached by default. No advantage over GitHub or R2. |
| G. Maven Central as a mirror (jcefmaven precedent) | yes | yes | $0 | Mature; jcefmaven's Windows natives on Central are CEF 132 (Feb 2025) | 1-2 plus namespace setup | Free, immutable, global CDN. Precedent: a 137.6 MB natives jar is hosted there. | Needs namespace verification and the publishing process. Publishing Chrome-branded codec binaries there is legally murkier than A/B. Brings Java packaging (jar-in-jar) into the native path. Not doable by 10-25. |
| H. Put the natives inside the Technic pack zip | yes | yes | $0 (GitHub) | n/a | 0.5 | One download through the launcher players already use. Available at first join without consent or delay. | The pack is non-Solder: every version re-downloads the whole zip, already 855 MB. CEF would add 158-360 MB to every future update, for every player including Android/ForbiddenMobile (INFERRED same pack). Technic keeps a cache copy, doubling disk use. Files would sit under the instance, which Technic cleans. The Technic downloader has no resume. It also mixes codec redistribution into the pack. |
| I. Standalone pre-installer (exe or jar) | partly | not-applicable | $0 unsigned; signing see K | n/a | 2-4 plus signing | Download is decoupled from the game. | An unsigned exe downloaded with a browser carries Mark-of-the-Web, so SmartScreen and SAC prompt or block, and signing is not available in time. A jar needs Java on PATH, but Technic's runtime lives under .technic/runtimes. Players must update the pack anyway (N12), so the mod at the main menu is the natural pre-installer. |
| J. Binary delta updates (zstd --patch-from / bsdiff) | partly | not-applicable | $0 | zstd mature; no Java 8 pure decoder for long windows | 3-5 | Some saving on consecutive CEF patch releases. | Measured gain is modest: 56.6 MB patch against 83.9 MB full xz (-33%) for 150.0.21 -> 150.0.22, and ~0% across majors (150.0.22 -> 152.0.13 = 80.2 MB). The pure-Java zstd decoder (aircompressor 2.0.3) rejects long windows, so this needs native zstd-jni. bspatch needs old+new in memory (~560 MB). OBS changes its CEF pin rarely. Our own patches already avoid CEF re-downloads via D. |
| K. Code signing of our binaries (Certum Open Source cloud / SignPath Foundation / Azure Artifact Signing) | yes | not-applicable | Certum Open Source Code Signing in the Cloud from €49 (card set €69); SignPath free; Azure $9.99/month but not eligible | Mature services | 0.5 integration plus validation time (Certum unknown, INFERRED ~1-2 weeks) | Helps antivirus reputation. Required for SAC, which can be re-enabled since April 2026 on 25H2. SmartScreen is mostly irrelevant here: files written by Java carry no Mark-of-the-Web. | Azure Artifact Signing (Public Trust): individuals only in US/Canada; organisations only in a listed set that excludes Brazil; validation takes 1-20 business days. SignPath Foundation: every component must be OSI-licensed (P-08 is open), the project must already be released and maintained, each release needs manual approval, and only own builds may be signed. Certum Open Source cloud starts at €49 but also presumes an open-source project, with ID validation (INFERRED days). Signing our files does not fix OBS's unsigned libcef.dll and chrome_elf.dll under SAC. |
| L. Runtime signatures (minisign / sigstore) versus SHA-256 pinned in the jar | partly | not-applicable | $0 | Mature primitives | 1-2 (post-event, only with a remote manifest) | Lets a remote manifest change (CEF security patches, revocations) without a mod update. | While the manifest is inside the jar, a signature adds nothing: the jar comes through the same GitHub channel as any public key would. Java 8 has no EdDSA, so minisign needs a vendored verifier. Sigstore keyless verification needs an online transparency log and a heavy client; unsuitable in-game. ECDSA P-256 or RSA via the JDK is the Java 8-native choice if a remote manifest is ever added. |
| M. (Cross-area, not validated) WebView2 instead of shipping CEF on Windows | unknown | unknown | $0 | WebView2 mature; off-screen capture is a workaround | unknown (engine spike) | If viable, the Windows download disappears: Microsoft-signed (antivirus/SAC-friendly), auto-updated security patches, no codec redistribution by Akashic. | No official off-screen rendering API (WebView2Feedback #547). Capture-based, needs a native bridge DLL from us, Windows-only. Audio-capture and input paths unknown. Belongs to the engine review; not tested here. |

### Open questions

- Owner decision: may Akashic redistribute the codec CEF binaries? It is needed only for the Linux Tier 2 repack and for the dormant Windows mirror (116.5 MB per-file xz); it changes the context recorded in D-011.
- Does OBS object to third parties Range-fetching its CEF zips from cdn-fastly.obsproject.com? No terms or position were found; send a courtesy note and keep the fallbacks ready.
- Does the owner have a domain on Cloudflare for an R2 mirror? redeakashic.com.br does not resolve.
- Licence P-08: signing through SignPath Foundation or Certum Open Source requires an OSI licence; until then nothing can be signed cheaply.
- Is jcef.dll built by our CI statically linked against the C runtime (/MT), or does it need the VC++ redistributable? Check with dumpbin at G1.
- Does CEF 150 run with only the en-US/pt-BR locale paks (plus gendered variants) when Windows uses another UI language? INFERRED fallback to en-US; test at G1.
- How many players have Smart App Control enforcing, and does OBS's unsigned libcef.dll (sha256 2515467d...) have Microsoft cloud reputation?
- Does every Technic player run the Mojang 8u51 runtime with the refreshed 2025-10 cacerts, or do some point Technic at a custom Java? Field logs show only the version string 1.8.0_51.
- Active player count, to size the rollout; only lifetime installs (16,837) and runs (56,202) are known.
- Does ForbiddenMobile download the same TEOF zip? This decides whether ~1.5-3 MB of Windows/Linux glue in the jar matters for mobile data.
- Cross-area: if the engine review finds a workable WebView2 off-screen capture path, Windows distribution would mostly disappear (preinstalled, Microsoft-signed, auto-updated). Not tested here.
- Housekeeping: /home/user/akashiic/Technic-Forbidden is a blob:none partial clone that already showed a staged deletion of the whole tree before I touched it. My `git ls-tree -l` fetched missing blobs lazily into its object store and triggered an automatic background `git gc` repack. The index and working tree were untouched; the earlier staged state is not mine. Remaining scratch: an OBS zip (~340 MB), logs and probes under /tmp/claude-0/-home-user/c5c14dc0-9bf3-521b-b7c6-50e7f96d7b47/scratchpad/challenge/{dist,probe,scripts}.

