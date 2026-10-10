package org.cef.browser;

import org.cef.CefClient;
import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

public class ProbeOsr extends CefBrowserOsr {
    public final AtomicLong paints = new AtomicLong();
    public ProbeOsr(CefClient c, String url, int w, int h) {
        super(c, url, false, null);
        browser_rect_ = new Rectangle(0, 0, w, h);
    }
    public void size(int w, int h) { browser_rect_ = new Rectangle(0, 0, w, h); wasResized(w, h); }
    @Override public void onPaint(org.cef.browser.CefBrowser b, boolean popup, Rectangle[] d, ByteBuffer buf, int w, int h) { paints.incrementAndGet(); }
}
