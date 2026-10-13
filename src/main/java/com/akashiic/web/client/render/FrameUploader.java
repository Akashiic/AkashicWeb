package com.akashiic.web.client.render;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.akashiic.web.AkashicWeb;
import com.akashiic.web.client.host.BrowserHost;
import com.akashiic.web.client.host.FrameRing;
import com.akashiic.web.client.host.FrameSlot;
import com.akashiic.web.client.screen.ClientScreen;
import com.akashiic.web.client.screen.ClientScreens;
import com.akashiic.web.common.ReasonCode;

/**
 * Texture uploads, once per render frame at RenderTickEvent START (ADR-004): never in the TESR, never in the shadow
 * pass. For each screen with a browser, nearest/largest first, it takes the newest slot from M2's ring, adds any damage
 * carried over from frames it had to skip, plans the upload ({@link UploadPlan}) and spends the per-frame budget
 * ({@link UploadBudget}). A screen that does not fit keeps its slot and its damage for the next frame, so pixels stay
 * correct when the render thread falls behind. Client (GL) thread only.
 */
public final class FrameUploader {

    private static final Logger LOG = LogManager.getLogger(AkashicWeb.NAME);
    /** Log the first GL failure and then one in this many, so a broken driver does not flood the log. */
    private static final int LOG_EVERY = 200;

    private final ClientScreens screens;
    private final LongSupplier timeMs;
    private final Supplier<TextureTarget> textures;
    private final IntSupplier maxTextureSize;
    private final UploadBudget budget = new UploadBudget(UploadBudget.DEFAULT_BYTES_PER_FRAME);
    private final UploadStats stats = new UploadStats();

    /** The game's uploader: GL textures, GL_MAX_TEXTURE_SIZE from the driver. */
    public FrameUploader(ClientScreens screens, LongSupplier timeMs) {
        this(screens, timeMs, ScreenTexture::new, ScreenTexture::maxTextureSize);
    }

    FrameUploader(ClientScreens screens, LongSupplier timeMs, Supplier<TextureTarget> textures,
        IntSupplier maxTextureSize) {
        this.screens = screens;
        this.timeMs = timeMs;
        this.textures = textures;
        this.maxTextureSize = maxTextureSize;
    }

    public UploadStats stats() {
        return stats;
    }

    /** RenderTickEvent START. */
    public void uploadFrame() {
        BrowserHost host = screens.host();
        if (host == null) return;
        budget.beginFrame();
        List<UploadBudget.Entry<ClientScreen>> entries = new ArrayList<>();
        for (ClientScreen s : screens.all()) {
            if (s.hostScreenId() != 0) entries.add(new UploadBudget.Entry<>(s, s.uploadPriority(), s.skippedFrames));
        }
        for (ClientScreen s : UploadBudget.order(entries)) uploadOne(s, host);
    }

    private void uploadOne(ClientScreen s, BrowserHost host) {
        FrameRing ring = host.frames(s.hostScreenId());
        if (ring == null) return;
        FrameSlot slot = ring.takeLatest();
        if (slot != null) {
            stats.recordTaken();
            if (s.heldSlot != null) {
                // A newer full canvas supersedes the held one; its damage must still be uploaded from the newer one.
                s.carriedDamage = s.carriedDamage.union(damageOf(s.heldSlot));
                ring.release(s.heldSlot);
                s.heldSlot = null;
            }
        } else if (s.heldSlot != null) {
            slot = s.heldSlot;
            s.heldSlot = null;
        } else {
            return;
        }

        Rect damage = s.carriedDamage.union(damageOf(slot));
        int maxTexture = maxTextureSize.getAsInt();
        int texW = s.texture == null ? 0 : s.texture.width();
        int texH = s.texture == null ? 0 : s.texture.height();
        UploadPlan plan = UploadPlan.plan(
            slot.width,
            slot.height,
            slot.bgra.capacity(),
            slot.bgra.isDirect(),
            damage,
            s.pageWidth(),
            s.pageHeight(),
            texW,
            texH,
            maxTexture);
        switch (plan.kind) {
            case NOTHING:
                s.carriedDamage = Rect.EMPTY;
                ring.release(slot);
                return;
            case DROP_SIZE:
                stats.recordDroppedSize();
                s.carriedDamage = Rect.EMPTY; // the next frame at the new size reallocates and uploads in full
                ring.release(slot);
                return;
            case DROP_INVALID:
                stats.recordDroppedInvalid();
                if (slot.width > maxTexture || slot.height > maxTexture)
                    s.onUploadFailure(ReasonCode.TEXTURE_TOO_LARGE, slot.width + "x" + slot.height);
                ring.release(slot);
                return;
            default:
                break;
        }
        if (!budget.tryConsume(plan.bytes())) {
            s.heldSlot = slot;
            s.carriedDamage = damage;
            s.skippedFrames++;
            stats.recordSkippedBudget();
            return;
        }
        long t0 = System.nanoTime();
        try {
            if (plan.kind == UploadPlan.Kind.ALLOCATE) {
                if (s.texture == null) s.texture = textures.get();
                s.texture.allocate(slot.width, slot.height);
            }
            s.texture.upload(slot.bgra, slot.width, slot.height, plan.rect);
            stats.recordUpload(System.nanoTime() - t0, plan.bytes(), timeMs.getAsLong());
            s.carriedDamage = Rect.EMPTY;
            s.skippedFrames = 0;
            s.onFrameUploaded();
        } catch (RuntimeException e) {
            // Documented boundary (ADR-004 "On any exception, draw the placeholder"): the screen shows UPLOAD_FAILED
            // and
            // the next frame retries with a full upload.
            stats.recordFailure();
            s.onUploadFailure(ReasonCode.UPLOAD_FAILED);
            s.carriedDamage = new Rect(0, 0, slot.width, slot.height);
            if ((stats.failures() - 1) % LOG_EVERY == 0)
                LOG.error("AkashicWeb: texture upload failed for {} ({})", s.key, plan, e);
        } finally {
            ring.release(slot);
        }
    }

    static Rect damageOf(FrameSlot slot) {
        return slot.hasDamage() ? new Rect(slot.damageX, slot.damageY, slot.damageW, slot.damageH) : Rect.EMPTY;
    }
}
