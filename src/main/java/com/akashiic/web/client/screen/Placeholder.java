package com.akashiic.web.client.screen;

import java.util.Arrays;

import com.akashiic.web.client.gui.LangKeys;
import com.akashiic.web.common.ReasonCode;

/**
 * What a screen shows instead of (or over) its page: a lang key with arguments and an optional detail key with its own
 * arguments. When {@link #overFrame} is true the last frame stays visible with a banner (budget, page error),
 * otherwise a dark card is drawn. Immutable value; equality lets the renderer cache translated text.
 * <p>
 * Reason texts ({@code akashicweb.reason.*}) take exactly one argument, the detail ({@code %s}, "" when there is none):
 * the lang files follow M4's {@code S2CNotice} convention. Formatting one without its argument makes Minecraft print
 * "Format error: ..." (seen in the 2026-10-13 dev-client run), so every reason placeholder is built here with one.
 */
public final class Placeholder {

    private static final Object[] NO_DETAIL = { "" };

    public final String key;
    public final Object[] args;
    /** Second line (usually a reason), or null. */
    public final String detailKey;
    /** Arguments for {@link #detailKey}; exactly one for a reason key. */
    public final Object[] detailArgs;

    public final boolean overFrame;

    public Placeholder(String key, Object[] args, String detailKey, Object[] detailArgs, boolean overFrame) {
        this.key = key;
        this.args = args == null ? new Object[0] : args.clone();
        this.detailKey = detailKey;
        this.detailArgs = detailArgs == null ? new Object[0] : detailArgs.clone();
        this.overFrame = overFrame;
    }

    /** A card with one text line (no reason). */
    static Placeholder card(String key, Object... args) {
        return new Placeholder(key, args, null, null, false);
    }

    /** A card with a text line and a second line that takes no argument (a hint). */
    static Placeholder hint(String key, String hintKey) {
        return new Placeholder(key, null, hintKey, null, false);
    }

    /** A card with a text line and a reason below it. */
    static Placeholder reason(String key, ReasonCode reason, String detail) {
        return new Placeholder(key, null, reason.langKey(), detail(detail), false);
    }

    /** The AkashicWeb title card with a reason. */
    static Placeholder reasonCard(ReasonCode reason, String detail) {
        return reason(LangKeys.TITLE, reason, detail);
    }

    /** A reason banner over the last frame. */
    static Placeholder reasonBanner(ReasonCode reason, String detail) {
        return new Placeholder(reason.langKey(), detail(detail), null, null, true);
    }

    private static Object[] detail(String detail) {
        return detail == null || detail.isEmpty() ? NO_DETAIL : new Object[] { detail };
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Placeholder)) return false;
        Placeholder p = (Placeholder) o;
        return key.equals(p.key) && Arrays.equals(args, p.args)
            && (detailKey == null ? p.detailKey == null : detailKey.equals(p.detailKey))
            && Arrays.equals(detailArgs, p.detailArgs)
            && overFrame == p.overFrame;
    }

    @Override
    public int hashCode() {
        return (key.hashCode() * 31 + Arrays.hashCode(args)) * 31 + Arrays.hashCode(detailArgs);
    }

    @Override
    public String toString() {
        return key + (args.length == 0 ? "" : Arrays.toString(args))
            + (detailKey == null ? "" : " / " + detailKey + Arrays.toString(detailArgs))
            + (overFrame ? " (banner)" : "");
    }
}
