package com.akashiic.web.client.screen;

/** Last STATE message of a screen's browser (docs/PROTOCOL.md). Immutable. */
public final class PageState {

    public static final PageState EMPTY = new PageState("", "", false, false, false, false);

    public final String url;
    public final String title;
    public final boolean loading;
    public final boolean canGoBack;
    public final boolean canGoForward;
    public final boolean audible;

    public PageState(String url, String title, boolean loading, boolean canGoBack, boolean canGoForward,
        boolean audible) {
        this.url = url == null ? "" : url;
        this.title = title == null ? "" : title;
        this.loading = loading;
        this.canGoBack = canGoBack;
        this.canGoForward = canGoForward;
        this.audible = audible;
    }
}
