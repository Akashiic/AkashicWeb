package org.cef.browser;

import org.cef.CefBrowserSettings;
import org.cef.CefClient;
import org.cef.callback.CefDragData;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefScreenInfo;

import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Probe: an OSR browser built on the upstream jcef-api jar without JOGL/AWT canvas.
 * Lives in org.cef.browser because CefBrowser_N is package-private (split package on the classpath).
 */
public class HeadlessOsrBrowser extends CefBrowser_N implements CefRenderHandler {
    public final AtomicLong paints = new AtomicLong();
    public final AtomicLong dirtyPixels = new AtomicLong();
    public final AtomicLong copyNanos = new AtomicLong();
    public volatile String paintThread = "";
    private final Rectangle rect;
    private ByteBuffer staging;

    public HeadlessOsrBrowser(CefClient client, String url, int w, int h, CefBrowserSettings settings) {
        super(client, url, null, null, null, settings);
        rect = new Rectangle(0, 0, w, h);
    }

    @Override
    public void createImmediately() {
        createBrowser(getClient(), 0, getUrl(), true, false, null, getRequestContext());
    }

    @Override public Component getUIComponent() { return null; }
    @Override public CefRenderHandler getRenderHandler() { return this; }
    @Override protected CefBrowser_N createDevToolsBrowser(CefClient c, String u, CefRequestContext x, CefBrowser_N p, Point i) { return null; }
    @Override public CompletableFuture<BufferedImage> createScreenshot(boolean nativeResolution) { return null; }

    @Override public Rectangle getViewRect(CefBrowser browser) { return rect; }
    @Override public boolean getScreenInfo(CefBrowser browser, CefScreenInfo info) {
        info.Set(1.0, 32, 8, false, rect.getBounds(), rect.getBounds());
        return true;
    }
    @Override public Point getScreenPoint(CefBrowser browser, Point viewPoint) { return new Point(viewPoint); }
    @Override public void onPopupShow(CefBrowser browser, boolean show) {}
    @Override public void onPopupSize(CefBrowser browser, Rectangle size) {}

    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
        paintThread = Thread.currentThread().getName();
        paints.incrementAndGet();
        long px = 0;
        for (Rectangle r : dirtyRects) px += (long) r.width * r.height;
        dirtyPixels.addAndGet(px);
        // Simulate what a Minecraft port must do: copy the frame out of the callback (the buffer is only
        // valid during the call) into a staging buffer that the render thread uploads later.
        long t0 = System.nanoTime();
        int bytes = width * height * 4;
        if (staging == null || staging.capacity() < bytes) staging = ByteBuffer.allocateDirect(bytes);
        staging.clear();
        ByteBuffer src = buffer.duplicate();
        src.clear();
        src.limit(Math.min(bytes, src.capacity()));
        staging.put(src);
        copyNanos.addAndGet(System.nanoTime() - t0);
    }

    @Override public void addOnPaintListener(Consumer<CefPaintEvent> l) {}
    @Override public void setOnPaintListener(Consumer<CefPaintEvent> l) {}
    @Override public void removeOnPaintListener(Consumer<CefPaintEvent> l) {}
    @Override public boolean onCursorChange(CefBrowser browser, int cursorType) { return true; }
    @Override public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) { return false; }
    @Override public void updateDragCursor(CefBrowser browser, int operation) {}
}
