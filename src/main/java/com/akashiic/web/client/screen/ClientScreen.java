package com.akashiic.web.client.screen;

import com.akashiic.web.client.host.FrameSlot;
import com.akashiic.web.client.host.SyncCommand;
import com.akashiic.web.client.render.Rect;
import com.akashiic.web.client.render.TextureTarget;
import com.akashiic.web.client.sync.LocalMedia;
import com.akashiic.web.client.sync.MediaClock;
import com.akashiic.web.client.sync.MediaSampler;
import com.akashiic.web.client.sync.SyncController;
import com.akashiic.web.client.sync.SyncDecision;
import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenState;

/**
 * Everything this client knows and holds for one screen face: our own copy of the server record, the browser in the
 * host (if granted), its GL texture, and the audio/sync/LOD controllers. Plain mutable record, client thread only;
 * {@link ClientScreens} owns the lifecycle and {@code FrameUploader} the texture fields.
 */
public final class ClientScreen {

    public final ScreenKey key;
    private ScreenState state;
    private ScreenGeometry geometry;

    // Browser in the host (0 = none)
    int hostScreenId;
    int requestedW;
    int requestedH;
    int navigatedEpoch;
    long lastNavigateMs;
    ScreenLod lastLodSent;
    final ScreenAudio audio = new ScreenAudio();

    // Sync
    final SyncController sync = new SyncController();
    final MediaSampler sampler = new MediaSampler();
    MediaClock mediaClock;
    LocalMedia localMedia;
    SyncCommand lastSyncSent;
    long lastSyncEvalMs = NEVER;
    SyncDecision lastDecision = SyncDecision.WAITING;
    boolean wasController;
    /**
     * "Long ago" for the time stamps below: the monotonic clock's origin is arbitrary (System.nanoTime may even be
     * negative), so 0 must not mean "never". Far enough from Long.MIN_VALUE that {@code now - NEVER} cannot overflow.
     */
    static final long NEVER = Long.MIN_VALUE / 4;

    /** Last C2SControl take sent for this screen (D-016 keepalive while the controller interacts). */
    long lastTakeMs = NEVER;
    long lastReleaseMs = NEVER;
    /** The keyboard was closed: release the lock at this time unless the screen is used again (0 = not due). */
    long leaveAtMs;
    /** Until then our take of a free screen is pending and our input is applied optimistically (D-016). */
    long takePendingUntilMs;
    /** Input was applied while the server had not confirmed us as the controller. */
    boolean optimistic;
    /** Click count of the last press, reused by its release. */
    int pressCount;
    /** Page pixel of the last pointer move sent, so the crosshair only sends a move when it changes. */
    int lastPointerX = -1;
    int lastPointerY = -1;

    // Page
    PageState page = PageState.EMPTY;
    ReasonCode failure;
    /** Detail argument of {@link #failure} for its reason text ("" when none). */
    String failureDetail = "";
    /** Renderer crashes of the current page (reset by a new epoch) and when the browser is recreated (0 = not due). */
    int crashes;
    long recreateAtMs;
    boolean reportedUrl;
    long lastUrlReportMs;
    /** The page URL this controller reported last (C2SSetUrl), until the server's next URL arrives; null if none. */
    String lastReportedUrl;

    /** prune() did not find the tile at its last pass, since {@link #missingSinceMs}. */
    boolean missing;
    long missingSinceMs;

    // View, recomputed every tick
    double distance = Double.POSITIVE_INFINITY;
    boolean inView;
    double priority;
    boolean granted;

    // Texture (FrameUploader, render thread = client thread in 1.7.10)
    public TextureTarget texture;
    public boolean hasFrame;
    public FrameSlot heldSlot;
    public Rect carriedDamage = Rect.EMPTY;
    public int skippedFrames;

    ClientScreen(ScreenState state) {
        this.key = state.key;
        setState(state);
    }

    /** Our own copy: later changes to the tile's object (or our URL updates) never alias. */
    void setState(ScreenState s) {
        this.state = copyOf(s);
        this.geometry = ScreenGeometry.of(this.state);
    }

    public ScreenState state() {
        return state;
    }

    /** Null when the record is not drawable (bad size). */
    public ScreenGeometry geometry() {
        return geometry;
    }

    public int hostScreenId() {
        return hostScreenId;
    }

    public int pageWidth() {
        return requestedW;
    }

    public int pageHeight() {
        return requestedH;
    }

    public PageState page() {
        return page;
    }

    public double distance() {
        return distance;
    }

    /** Upload priority: size x proximity, lowered when out of view. */
    public double uploadPriority() {
        return inView ? priority : priority * 0.25;
    }

    public int maxDimBlocks() {
        return Math.max(state.widthBlocks, state.heightBlocks);
    }

    /** Called by FrameUploader after a successful upload: a fresh frame clears a page-level failure banner. */
    public void onFrameUploaded() {
        hasFrame = true;
        if (failure == ReasonCode.UPLOAD_FAILED || failure == ReasonCode.PAGE_CRASHED
            || failure == ReasonCode.PAGE_UNRESPONSIVE
            || failure == ReasonCode.BAD_RESOLUTION) clearFailure();
    }

    /** Called by FrameUploader when GL refused the frame or the page is too large for this GPU. */
    public void onUploadFailure(ReasonCode reason) {
        onUploadFailure(reason, "");
    }

    /** As {@link #onUploadFailure(ReasonCode)} with the detail shown in the reason text (e.g. the page size). */
    public void onUploadFailure(ReasonCode reason, String detail) {
        fail(reason, detail);
    }

    void fail(ReasonCode reason, String detail) {
        failure = reason;
        failureDetail = detail == null ? "" : detail;
    }

    void clearFailure() {
        failure = null;
        failureDetail = "";
    }

    public ReasonCode failure() {
        return failure;
    }

    /**
     * M4's deep copy (every field, so members added later, like friendNames and controllerName, are never dropped
     * here), with null strings made empty: the GUIs draw them.
     */
    static ScreenState copyOf(ScreenState s) {
        ScreenState c = s.copy();
        if (c.url == null) c.url = "";
        if (c.ownerName == null) c.ownerName = "";
        if (c.controllerName == null) c.controllerName = "";
        return c;
    }
}
