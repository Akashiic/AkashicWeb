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
    long lastSyncEvalMs;
    SyncDecision lastDecision = SyncDecision.WAITING;
    boolean wasController;

    // Page
    PageState page = PageState.EMPTY;
    ClientReason failure;
    boolean reportedUrl;
    long lastUrlReportMs;

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
        if (failure == ClientReason.UPLOAD_FAILED || failure == ClientReason.PAGE_CRASHED
            || failure == ClientReason.PAGE_UNRESPONSIVE) failure = null;
    }

    /** Called by FrameUploader when GL refused the frame or the page is too large for this GPU. */
    public void onUploadFailure(ClientReason reason) {
        failure = reason;
    }

    static ScreenState copyOf(ScreenState s) {
        ScreenState c = new ScreenState();
        c.key = s.key;
        c.widthBlocks = s.widthBlocks;
        c.heightBlocks = s.heightBlocks;
        c.resolutionW = s.resolutionW;
        c.resolutionH = s.resolutionH;
        c.rotation = s.rotation;
        c.url = s.url == null ? "" : s.url;
        c.epoch = s.epoch;
        c.owner = s.owner;
        c.ownerName = s.ownerName == null ? "" : s.ownerName;
        if (s.friends != null) c.friends.addAll(s.friends);
        c.friendRights = s.friendRights;
        c.otherRights = s.otherRights;
        c.locked = s.locked;
        c.controller = s.controller;
        c.mode = s.mode;
        return c;
    }
}
