package com.akashiic.web.client.net;

import java.util.concurrent.ConcurrentLinkedQueue;

import com.akashiic.web.AkashicWeb;

/**
 * Work posted from the Netty thread (packet handlers) or the host reader thread, run on the client thread.
 * <p>
 * Copied from AkashicFM {@code com.akashiic.fm.client.ClientTaskQueue} (same owner), comments translated. Drained at
 * ClientTickEvent START and RenderTickEvent START so a relayed click waits at most one frame, not one tick.
 */
public final class ClientTaskQueue {

    /** Bounded per drain so a flood of packets cannot stall a frame; the rest waits for the next drain. */
    private static final int MAX_PER_DRAIN = 256;
    private static final ConcurrentLinkedQueue<Runnable> QUEUE = new ConcurrentLinkedQueue<>();

    private ClientTaskQueue() {}

    /** Any thread. */
    public static void add(Runnable task) {
        if (task != null) QUEUE.add(task);
    }

    /** Client thread only. */
    public static void drain() {
        for (int i = 0; i < MAX_PER_DRAIN; i++) {
            Runnable r = QUEUE.poll();
            if (r == null) return;
            try {
                r.run();
            } catch (RuntimeException e) {
                // Documented boundary: one bad packet or host message must not kill the client tick.
                AkashicWeb.LOG.error("AkashicWeb: client task failed", e);
            }
        }
    }

    /** Pending tasks, for /web doctor. */
    public static int size() {
        return QUEUE.size();
    }
}
