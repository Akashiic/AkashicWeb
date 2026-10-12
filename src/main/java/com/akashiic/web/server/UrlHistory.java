package com.akashiic.web.server;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-owned navigation history of one screen, so back/forward are URL changes the server decides and broadcasts
 * (each viewer's browser has its own, divergent history). In memory only; a restart starts a fresh history.
 * Peek and move are separate so a target refused by the (possibly reloaded) site policy leaves the cursor alone.
 */
public final class UrlHistory {

    public static final int MAX = 16;

    private final List<String> urls = new ArrayList<>();
    private int cursor = -1;

    /** A new page: drops any "forward" entries, like a browser. Re-visiting the current page changes nothing. */
    public void push(String url) {
        if (cursor >= 0 && urls.get(cursor)
            .equals(url)) return;
        while (urls.size() > cursor + 1) urls.remove(urls.size() - 1);
        urls.add(url);
        if (urls.size() > MAX) urls.remove(0);
        cursor = urls.size() - 1;
    }

    /** URL one step back, or null at the start. */
    public String peekBack() {
        return cursor > 0 ? urls.get(cursor - 1) : null;
    }

    public String peekForward() {
        return cursor >= 0 && cursor < urls.size() - 1 ? urls.get(cursor + 1) : null;
    }

    public void moveBack() {
        if (cursor > 0) cursor--;
    }

    public void moveForward() {
        if (cursor >= 0 && cursor < urls.size() - 1) cursor++;
    }

    public void clear() {
        urls.clear();
        cursor = -1;
    }

    public int size() {
        return urls.size();
    }
}
