package probe;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.ProbeOsr;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

/** Usage: Probe116 <libDir> <url> <seconds> <jsFile> */
public class Probe116 {
    public static void main(String[] a) throws Exception {
        System.setProperty("jcef.path", a[0]);
        final String url = a[1];
        final int seconds = Integer.parseInt(a[2]);
        final String js = new String(Files.readAllBytes(Paths.get(a[3])), "UTF-8");
        String[] sw = {"--autoplay-policy=no-user-gesture-required", "--no-sandbox", "--disable-gpu"};
        if (!CefApp.startup(sw)) throw new IllegalStateException("startup failed");
        CefSettings s = new CefSettings();
        s.windowless_rendering_enabled = true;
        File cache = Files.createTempDirectory("cef116").toFile();
        s.cache_path = new File(cache, "c").getAbsolutePath();
        s.log_file = new File(cache, "cef.log").getAbsolutePath();
        final CefApp app = CefApp.getInstance(sw, s);
        CefClient client = app.createClient();
        final AtomicBoolean done = new AtomicBoolean();
        client.addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override public boolean onConsoleMessage(CefBrowser b, CefSettings.LogSeverity l, String m, String src, int line) {
                System.out.println("CONSOLE: " + m);
                if (m.startsWith("PROBE_DONE")) done.set(true);
                return true;
            }
        });
        client.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override public void onLoadEnd(CefBrowser b, CefFrame f, int status) {
                if (f.isMain()) { System.out.println("LOAD_END " + status + " " + b.getURL()); b.executeJavaScript(js, b.getURL(), 0); }
            }
        });
        ProbeOsr br = new ProbeOsr(client, url, 640, 360);
        br.createImmediately();
        long end = System.currentTimeMillis() + seconds * 1000L;
        boolean sized = false;
        while (System.currentTimeMillis() < end && !done.get()) {
            app.N_DoMessageLoopWork();
            if (!sized) { try { br.size(640, 360); sized = true; } catch (Throwable t) { } }
            Thread.sleep(5);
        }
        for (int i = 0; i < 50; i++) { app.N_DoMessageLoopWork(); Thread.sleep(5); }
        System.out.println("CEF version: " + app.getVersion() + " paints=" + br.paints.get() + " done=" + done.get());
        System.exit(0);
    }
}
