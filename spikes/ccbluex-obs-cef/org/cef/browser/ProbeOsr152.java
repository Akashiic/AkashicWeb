package org.cef.browser;

import org.cef.CefBrowserSettings;
import org.cef.CefClient;
import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

public class ProbeOsr152 extends CefBrowserOsr {
    public final AtomicLong paints = new AtomicLong();
    public ProbeOsr152(CefClient c, String url, int w, int h, CefBrowserSettings s) {
        super(c, url, false, null, s);
        browser_rect_ = new Rectangle(0, 0, w, h);
    }
    @Override public void onPaint(CefBrowser b, boolean popup, Rectangle[] d, ByteBuffer buf, int w, int h) { paints.incrementAndGet(); }
}
