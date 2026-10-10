package probe;

import org.cef.CefApp;
import org.cef.CefBrowserSettings;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.SystemBootstrap;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.HeadlessOsrBrowser;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Usage: Probe <libDir> <url> <seconds> <fps> <width> <height> <evalJsFile|-> [extra chromium switches...]
 * Prints console messages, paint statistics and per-process RSS / CPU of the browser process tree.
 */
public class Probe {
    public static void main(String[] a) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> { System.out.println("UNCAUGHT " + t.getName() + " " + e); StackTraceElement[] st = e.getStackTrace(); for (int i = 0; i < Math.min(12, st.length); i++) System.out.println("  at " + st[i]); });
        final File lib = new File(a[0]);
        String url = a[1];
        int seconds = Integer.parseInt(a[2]);
        int fps = Integer.parseInt(a[3]);
        int w = Integer.parseInt(a[4]);
        int h = Integer.parseInt(a[5]);
        final String js = "-".equals(a[6]) ? null : new String(Files.readAllBytes(Paths.get(a[6])), "UTF-8");
        List<String> args = new ArrayList<>();
        for (int i = 7; i < a.length; i++) args.add(a[i]);

        SystemBootstrap.setLoader(libname -> { });
        System.loadLibrary("jawt");
        System.load(new File(lib, "libjcef.so").getAbsolutePath());
        if (!CefApp.startup(args.toArray(new String[0]))) throw new IllegalStateException("startup failed");
        System.load(new File(lib, "libcef.so").getAbsolutePath());

        CefSettings s = new CefSettings();
        s.windowless_rendering_enabled = true;
        s.browser_subprocess_path = new File(lib, "jcef_helper").getAbsolutePath();
        s.resources_dir_path = lib.getAbsolutePath();
        s.locales_dir_path = new File(lib, "locales").getAbsolutePath();
        File cache = Files.createTempDirectory("cefprobe").toFile();
        s.root_cache_path = cache.getAbsolutePath();
        s.cache_path = new File(cache, "c").getAbsolutePath();
        s.log_severity = CefSettings.LogSeverity.valueOf(System.getProperty("cef.log", "LOGSEVERITY_WARNING"));
        s.log_file = new File(cache, "cef.log").getAbsolutePath();

        CefApp app = CefApp.getInstance(args.toArray(new String[0]), s);
        System.out.println("CEF version: " + app.getVersion());
        CefClient client = app.createClient();
        final CountDownLatch done = new CountDownLatch(1);
        client.addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override
            public boolean onConsoleMessage(CefBrowser b, CefSettings.LogSeverity level, String message, String source, int line) {
                System.out.println("CONSOLE: " + message);
                if (message.startsWith("PROBE_DONE")) done.countDown();
                return true;
            }
        });
        client.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser b, CefFrame frame, int status) {
                if (frame.isMain()) {
                    System.out.println("LOAD_END status=" + status + " url=" + b.getURL());
                    if (js != null) b.executeJavaScript(js, b.getURL(), 0);
                }
            }
            @Override
            public void onLoadError(CefBrowser b, CefFrame frame, ErrorCode code, String text, String failed) {
                System.out.println("LOAD_ERROR " + code + " " + text + " " + failed);
            }
        });
        CefBrowserSettings bs = new CefBrowserSettings();
        bs.windowless_frame_rate = fps;
        HeadlessOsrBrowser br = new HeadlessOsrBrowser(client, url, w, h, bs);
        br.createImmediately();

        long pid = getPid();
        long t0 = System.nanoTime();
        long cpu0 = treeCpuTicks(pid);
        long p0 = br.paints.get();
        boolean finished = done.await(seconds, TimeUnit.SECONDS);
        if (finished && js != null && seconds > 0) Thread.sleep(500);
        double el = (System.nanoTime() - t0) / 1e9;
        long cpu1 = treeCpuTicks(pid);
        long paints = br.paints.get() - p0;
        System.out.printf("ELAPSED %.1fs paints=%d (%.1f/s) avgDirtyPx/paint=%d avgCopyMs=%.3f paintThread=%s%n",
                el, paints, paints / el, paints == 0 ? 0 : br.dirtyPixels.get() / Math.max(1, br.paints.get()),
                br.paints.get() == 0 ? 0 : br.copyNanos.get() / 1e6 / br.paints.get(), br.paintThread);
        System.out.printf("CPU tree: %.1f%% of one core over window (ticks=%d)%n", 100.0 * (cpu1 - cpu0) / 100.0 / el, cpu1 - cpu0);
        printTree(pid);
        br.close(true);
        Thread.sleep(500);
        System.exit(0);
    }

    static long getPid() throws Exception {
        return Long.parseLong(new File("/proc/self").getCanonicalFile().getName());
    }

    static List<Long> tree(long root) {
        List<Long> out = new ArrayList<>();
        out.add(root);
        for (int i = 0; i < out.size(); i++) {
            long p = out.get(i);
            File[] procs = new File("/proc").listFiles();
            if (procs == null) continue;
            for (File f : procs) {
                if (!f.getName().matches("\\d+")) continue;
                try {
                    String st = new String(Files.readAllBytes(Paths.get(f.getPath(), "stat")), "UTF-8");
                    String[] parts = st.substring(st.lastIndexOf(')') + 2).split(" ");
                    if (Long.parseLong(parts[1]) == p) out.add(Long.parseLong(f.getName()));
                } catch (Exception ignored) { }
            }
        }
        return out;
    }

    static long treeCpuTicks(long root) {
        long sum = 0;
        for (long p : tree(root)) {
            try {
                String st = new String(Files.readAllBytes(Paths.get("/proc", "" + p, "stat")), "UTF-8");
                String[] parts = st.substring(st.lastIndexOf(')') + 2).split(" ");
                sum += Long.parseLong(parts[11]) + Long.parseLong(parts[12]);
            } catch (Exception ignored) { }
        }
        return sum;
    }

    static void printTree(long root) {
        long total = 0; long totalPss = 0;
        for (long p : tree(root)) {
            try {
                String cmd = new String(Files.readAllBytes(Paths.get("/proc", "" + p, "cmdline")), "UTF-8").replace('\0', ' ');
                String type = "java";
                int i = cmd.indexOf("--type=");
                if (i >= 0) type = cmd.substring(i, Math.min(cmd.length(), cmd.indexOf(' ', i) < 0 ? cmd.length() : cmd.indexOf(' ', i)));
                long rss = 0;
                for (String line : Files.readAllLines(Paths.get("/proc", "" + p, "status"))) {
                    if (line.startsWith("VmRSS:")) rss = Long.parseLong(line.replaceAll("\\D+", ""));
                }
                long pss = 0;
                try { for (String line : Files.readAllLines(Paths.get("/proc", "" + p, "smaps_rollup"))) if (line.startsWith("Pss:")) pss = Long.parseLong(line.replaceAll("\\D+", "")); } catch (Exception ignored) { }
                total += rss; totalPss += pss;
                System.out.printf("PROC %d %s RSS=%d MiB PSS=%d MiB%n", p, type, rss / 1024, pss / 1024);
            } catch (Exception ignored) { }
        }
        System.out.printf("RSS total (sum, shared pages double-counted) = %d MiB; PSS total = %d MiB%n", total / 1024, totalPss / 1024);
    }
}
