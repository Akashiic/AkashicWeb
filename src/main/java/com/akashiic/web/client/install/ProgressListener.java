package com.akashiic.web.client.install;

/** Progress of a long installer step, called on the installer thread. Implementations must be cheap. */
@FunctionalInterface
public interface ProgressListener {

    ProgressListener NONE = (done, total) -> {};

    void onProgress(long done, long total);

    /** Maps this step's 0..total onto [from, to] of an overall 0..1 progress reported through {@code sink}. */
    static ProgressListener scaled(FractionSink sink, float from, float to) {
        return (done, total) -> {
            float f = total <= 0 ? 0f : Math.min(1f, Math.max(0f, (float) done / total));
            sink.onFraction(from + (to - from) * f);
        };
    }

    @FunctionalInterface
    interface FractionSink {

        void onFraction(float fraction);
    }
}
