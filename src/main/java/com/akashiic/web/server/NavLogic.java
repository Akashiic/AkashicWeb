package com.akashiic.web.server;

import com.akashiic.web.common.ScreenState;

/**
 * URL authority rules (ADR-012): every URL change bumps the screen's epoch, so packets that still refer to the old
 * page (media samples, stale navigations) are dropped. Navigation buttons become URL changes decided here. Pure; the
 * caller has already checked rights and the URL gate.
 */
public final class NavLogic {

    private NavLogic() {}

    /** Applies a new URL. Returns false (and keeps the epoch) when the screen already shows exactly this URL. */
    public static boolean setUrl(ScreenState s, UrlHistory history, String url) {
        if (url.equals(s.url)) return false;
        s.url = url;
        s.epoch++;
        history.push(url);
        return true;
    }

    /**
     * URL a navigation action leads to, or null when there is nothing to do server-side ("stop", or no history in
     * that direction). "reload" targets the current URL: it is applied with a new epoch so every viewer reloads.
     */
    public static String target(String action, ScreenState s, UrlHistory history, String homeUrl) {
        switch (action) {
            case "back":
                return history.peekBack();
            case "forward":
                return history.peekForward();
            case "home":
                return homeUrl == null || homeUrl.isEmpty() ? null : homeUrl;
            case "reload":
                return s.url == null || s.url.isEmpty() ? null : s.url;
            default:
                return null;
        }
    }

    /** Applies an accepted navigation target; always a new epoch (a reload must reach every viewer). */
    public static void commit(String action, ScreenState s, UrlHistory history, String url) {
        switch (action) {
            case "back":
                history.moveBack();
                break;
            case "forward":
                history.moveForward();
                break;
            case "home":
                history.push(url);
                break;
            default:
                break;
        }
        s.url = url;
        s.epoch++;
    }

    /** Admin "clear": blank page, new epoch, history forgotten. */
    public static void clear(ScreenState s, UrlHistory history) {
        s.url = "";
        s.epoch++;
        history.clear();
    }
}
