# Module contracts (v1)

Grade: AUTHORED 2026-10-12; the interfaces below exist as compiling Java in the repository (BUILD). These are the seams between modules so they can be built in parallel. Packages are under `com.akashiic.web`. Each module owns its paths; only the owner edits them. Shared wiring (proxies, `AkashicWeb.java`) is owned by the module named below.

## Ownership

| Module | Owns | Must not touch |
|---|---|---|
| M1 host app (JS) | `host/**` (except `host/src/protocol.js`, shared; extend only by adding) | Java sources |
| M2 host client (Java) | `client/host/**` (existing `HostProtocol` may gain helpers, never wire changes) | Minecraft classes (pure Java + LWJGL-free) |
| M3 engine installer (Java) | `client/install/**`, `src/main/resources/assets/akashicweb/host-app/` packaging task in `addon.gradle` | Minecraft classes except a tiny GUI hook class `client/install/gui/**` |
| M4 game common/server (Java) | `common/**`, `server/**`, `AkashicWeb.java`, `common/CommonProxy.java`, `assets/akashicweb/lang/*.lang`, block/textures assets | client packages |
| M5 game client (Java) | `client/ClientProxy.java`, `client/render/**`, `client/gui/**`, `client/screen/**`, `client/sync/**`, `client/net/**` | server packages |

Lang keys from M3/M5 are listed in their reports and merged into M4's files at integration.

## Contract files

These files are the contract. Their owner may add members; nobody changes or removes existing ones without updating this document and every user in the same commit.

| Package | Files | Owner after integration |
|---|---|---|
| `client.host` | `BrowserHost`, `HostListener`, `HostStatus`, `CreateSpec`, `InputEvent`, `SyncCommand`, `FrameRing`, `FrameSlot`, `HostProtocol` | M2 |
| `client.install` | `EngineLocator`, `EngineState` | M3 |
| `common` | `ScreenKey`, `ScreenMode`, `ScreenState`, `ScreenTile`, `Rights`, `ReasonCode`, `SitePolicy`, `ModContent` | M4 |
| `common.net` | `ScreenInput`, `MediaSample`, `RightsChange`, `ServerBound`, `ClientHooks` | M4 |

## M4 ↔ M5: network seam (`common.net`)

- **Client → server.** M5 calls `ModContent.toServer` (a `ServerBound`). M4 implements `ServerBound` over the `akashicweb` SimpleNetworkWrapper channel and assigns it in `init`.
- **Server → client.** M4's client-side packet handlers call `AkashicWeb.proxy.clientHooks()` (a `ClientHooks`) on the client thread (1.7.10 queues FML packets to `processReceivedPackets`); M5's implementation hops through its own queue anyway. The handler classes must not reference client classes, because FML instantiates them on the dedicated server too. `CommonProxy` returns `ClientHooks.NOOP`; M5's `ClientProxy` returns its implementation. M4's tile entity also calls `onScreenState` from `onDataPacket` and `onScreenRemoved` from `invalidate`/`onChunkUnload` on the client world.
- **Rendering.** M4 assigns `ModContent.screenBlock` and `ModContent.screenTileClass` in `preInit`; its tile entity implements `ScreenTile`. M5 binds its TESR to `ModContent.screenTileClass` and casts to `ScreenTile`, never to the concrete class.

## M2 → M5: browser host API (`client.host`)

```java
public interface BrowserHost {
    HostStatus status();                         // STARTING, READY, DOWN(reason), DISABLED(reason)
    int createScreen(CreateSpec spec);           // returns screenId (> 0); queued until READY
    void destroyScreen(int screenId);
    void navigate(int screenId, String url);
    void action(int screenId, String action);    // back|forward|reload|stop
    void input(int screenId, InputEvent event);  // see PROTOCOL INPUT
    void audio(int screenId, boolean muted, float volume);
    void render(int screenId, int fps, boolean painting);
    void resize(int screenId, int width, int height);
    void sync(int screenId, SyncCommand cmd);
    void policy(SitePolicy policy);
    FrameRing frames(int screenId);              // null if unknown
    void addListener(HostListener l);
    void shutdown();
}

public interface HostListener {
    default void onStatus(HostStatus s) {}
    default void onState(int screenId, String json) {}
    default void onMedia(int screenId, String json) {}
    default void onEvent(int screenId, String json) {}
    default void onPcm(int screenId, long ptsMicros, int sampleRate, int channels, short[] interleaved, int frames) {}
}

/** 3-slot ring, written by the reader thread, read by the render thread (ADR-004). */
public interface FrameRing {
    /** Newest complete frame plus the damage accumulated since the last take, or null if nothing new. */
    FrameSlot takeLatest();
    void release(FrameSlot slot);
}

public final class FrameSlot {
    public final java.nio.ByteBuffer bgra; // direct, native order, width*height*4, full page canvas
    public final int width, height;
    public final int damageX, damageY, damageW, damageH; // region to upload (whole frame if >= 50%)
}
```

Callback threads (wave 2): host messages arrive on the reader thread, `onStatus` on the supervisor thread, and the local `blocked-url` EVENT on the caller's thread (or the supervisor thread during a replay). M5 must hop to the client thread (`ClientTaskQueue`) before touching Minecraft. A listener that throws, even an Error, is logged and skipped.

Wave-2 additions to the M2 contract:
- `BrowserHost.shutdown()` blocks for up to about 3.5 s; call it at game exit only. The other methods never block.
- New default methods: `BrowserHost.reenable()`, `BrowserHost.diagnostics()` and `FrameRing.requeue(FrameSlot)`, which returns a slot that was taken but not uploaded so its damage is kept.
- W3-0: default method `BrowserHost.lastFailureDetail()` ("" when none): the launch error text, exit code or protocol problem of the last host failure. `ClientWiring` passes it to `EngineLocator.verifyAfterLoadFailure(String)` (the diagnostics only when it is empty).
- W3-0: listeners hear only of screens the game created and has not destroyed: STATE, MEDIA, EVENT and PCM of any other screen id are dropped and counted in `diagnostics()`. Host-level EVENTs on screen 0 (e.g. `policy-error`) always reach the listeners.
- `FrameSlot.MAX_DIMENSION = 4096`; the constructor rejects sizes below 0 or above it.
- `HostProtocol.read()` throws `EOFException` when a stream is cut inside the magic bytes.
- URLs are checked locally before they leave the JVM (see PROTOCOL.md section 1). The policy is deny-all until `policy()` is called. An empty URL gives a blank page.

## M3 → M2: engine location (`client.install`)

```java
public interface EngineLocator {
    EngineState state();              // ABSENT, CONSENT_NEEDED, DOWNLOADING(progress), READY, FAILED(reasonCode), UNSUPPORTED(reasonCode)
    java.io.File hostExecutable();    // AkashicWebHost(.exe) when READY
    java.io.File userDataDir();       // per-instance profile root
    String engineId();                // e.g. "electron-44.7.0-win32-x64"
    void requestInstall();            // after consent
    // wave 2 (default methods): repair(), cancelInstall(), verifyAfterLoadFailure(String launchError),
    // detail(), consentInfo() -> ConsentInfo {component, publisher, host, download/disk/free sizes,
    // installDir (whole AkashicWeb folder), installDirDisplay, zipName, downloadUrl, importDir, importDirDisplay}
}
```

## M4 ↔ M5: game state (`common`)

```java
public final class ScreenKey { int dim, x, y, z; int side; }            // origin of the multiblock face
public enum ScreenMode { SHARED, PRIVATE, CAST }
public final class ScreenState {                                          // NBT + network form
    ScreenKey key; int widthBlocks, heightBlocks; int resolutionW, resolutionH; int rotation;
    String url; int epoch; java.util.UUID owner; String ownerName; java.util.Set<java.util.UUID> friends;
    int friendRights, otherRights; boolean locked; java.util.UUID controller; ScreenMode mode;
}
public final class Rights { CHANGE_URL=1, CLICK_TYPE=2, MANAGE_FRIENDS=4, MANAGE_RIGHTS=8, CHANGE_RESOLUTION=16; DEFAULT_OTHERS=0; DEFAULT_FRIENDS=CHANGE_URL|CLICK_TYPE }
public final class HitMath { static double[] uvOnFace(ScreenState s, Vec3 hit); static int[] pixel(ScreenState s, double u, double v); }
```

Packets are the ones in [PROTOCOL.md](PROTOCOL.md) section 2. Codecs and the S2C handlers live in `common.net` next to their messages; the S2C handlers call only `ClientHooks`. Server handlers are in `server.net`. Everything is registered in `WebNetwork.init`; the dedicated server never loads client classes.

Wave-2 changes (D-016 and the M4 review):
- `ServerBound`: `pointer`/`key` removed; `control(key, take)` doubles as the controller keepalive.
- `ClientHooks`: `onInput` removed.
  - `onHello(int protoMajor, int protoMinor, boolean killSwitch, SitePolicy policy, long serverTimeMs, UUID playerUuid)`: `policy` and `playerUuid` are null for a foreign major, and `playerUuid` is also null from an older server.
  - `onMediaClock(ScreenKey, int epoch, double mediaTime, float rate, boolean paused, long serverTimeMs, boolean live, int liveLagMs)`.
- `ScreenState.controllerName`: in the network form and the description NBT; "" when there is no controller.
- `ScreenInput` is client-local since D-016.
- `SitePolicy`'s constructor rejects a null mode or list; untrusted input goes through `SitePolicy.of`.

## Cross-cutting rules

- Java 8 bytecode; `common` and `server` never reference `net.minecraft.client`, `org.lwjgl`, `java.lang.ProcessBuilder`, sockets or files outside the world/config dirs.
- No `org.cef`, no JNI, no natives in the jar.
- Every failure becomes a reason code (`ReasonCode` enum in `common`, owned by M4; M2/M3/M5 add codes by listing them in their reports).
- Tests: JUnit 5 for every pure-logic class; host app tests with `node --test`.
