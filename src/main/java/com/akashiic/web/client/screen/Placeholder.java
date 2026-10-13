package com.akashiic.web.client.screen;

import java.util.Arrays;

/**
 * What a screen shows instead of (or over) its page: a lang key with arguments and an optional detail key. When
 * {@link #overFrame} is true the last frame stays visible with a banner (budget, page error), otherwise a dark card is
 * drawn. Immutable value; equality lets the renderer cache translated text.
 */
public final class Placeholder {

    public final String key;
    public final Object[] args;
    /** Second line (usually a reason), or null. */
    public final String detailKey;

    public final boolean overFrame;

    public Placeholder(String key, Object[] args, String detailKey, boolean overFrame) {
        this.key = key;
        this.args = args == null ? new Object[0] : args.clone();
        this.detailKey = detailKey;
        this.overFrame = overFrame;
    }

    static Placeholder card(String key, Object... args) {
        return new Placeholder(key, args, null, false);
    }

    static Placeholder card(String key, String detailKey) {
        return new Placeholder(key, null, detailKey, false);
    }

    static Placeholder banner(String key) {
        return new Placeholder(key, null, null, true);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Placeholder)) return false;
        Placeholder p = (Placeholder) o;
        return key.equals(p.key) && Arrays.equals(args, p.args)
            && (detailKey == null ? p.detailKey == null : detailKey.equals(p.detailKey))
            && overFrame == p.overFrame;
    }

    @Override
    public int hashCode() {
        return key.hashCode() * 31 + Arrays.hashCode(args);
    }

    @Override
    public String toString() {
        return key + (args.length == 0 ? "" : Arrays.toString(args))
            + (detailKey == null ? "" : " / " + detailKey)
            + (overFrame ? " (banner)" : "");
    }
}
