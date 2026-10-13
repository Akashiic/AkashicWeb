package com.akashiic.web.client.screen;

/**
 * One host EVENT ({@code {kind, detail}}, docs/PROTOCOL.md section 1), reduced to the fields the game uses. Every
 * string comes from web content or the host, so the parser ({@link HostMessages#event}) caps and cleans them before
 * they can reach a placeholder or the chat. Immutable.
 */
public final class HostEvent {

    /** e.g. "blocked-url", "load-error", "screen-limit", "policy-error". */
    public final String kind;
    /** detail.reason: "popup", "dns-failed", "frame-too-large", "count", "crashed"... ("" when absent). */
    public final String reason;
    /** detail.code when it is a string: the ReasonCode name of the two local EVENTs M2 raises ("" otherwise). */
    public final String code;
    /** detail.host, or the host of detail.url ("" when absent). */
    public final String host;
    /** detail.via: "client", "navigate", "will-navigate", "request-mainFrame"... ("" when absent). */
    public final String via;
    /**
     * A short human detail for the reason text: the Chromium error name of a load-error, "WxH" of a page that is too
     * large, the permission asked for, the renderer exit reason, the policy error ("" when none).
     */
    public final String text;
    /** A rate-limit summary ({@code {suppressed: n}}): nothing happened beyond what was already reported. */
    public final boolean suppressedSummary;

    public HostEvent(String kind, String reason, String code, String host, String via, String text,
        boolean suppressedSummary) {
        this.kind = kind;
        this.reason = nz(reason);
        this.code = nz(code);
        this.host = nz(host);
        this.via = nz(via);
        this.text = nz(text);
        this.suppressedSummary = suppressedSummary;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    @Override
    public String toString() {
        return kind + (reason.isEmpty() ? "" : " reason=" + reason)
            + (code.isEmpty() ? "" : " code=" + code)
            + (host.isEmpty() ? "" : " host=" + host)
            + (via.isEmpty() ? "" : " via=" + via)
            + (text.isEmpty() ? "" : " (" + text + ")")
            + (suppressedSummary ? " (suppressed summary)" : "");
    }
}
