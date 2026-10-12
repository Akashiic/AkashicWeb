import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;

/** Java 8 parent: listens on loopback, spawns the Electron host, receives BGRA frames into one direct buffer. */
public class FrameSink {
    public static void main(String[] a) throws Exception {
        String electron = a[0], app = a[1], url = a[2]; int seconds = Integer.parseInt(a[3]);
        ServerSocketChannel ss = ServerSocketChannel.open();
        ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        int port = ((InetSocketAddress) ss.getLocalAddress()).getPort();
        String token = Long.toHexString(new Random().nextLong());
        List<String> cmd = new ArrayList<String>(Arrays.asList(electron, "--no-sandbox", app, "--url=" + url, "--fps=30",
            "--sink=127.0.0.1:" + port, "--token=" + token, "--seconds=" + seconds, "--w=" + (a.length > 4 ? a[4] : "1280"), "--h=" + (a.length > 5 ? a[5] : "720"), (a.length > 6 ? a[6] : "--x")));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT);
        long tStart = System.nanoTime();
        Process p = pb.start();
        SocketChannel ch = ss.accept();
        // token line
        ByteBuffer one = ByteBuffer.allocate(1); StringBuilder sb = new StringBuilder();
        while (true) { one.clear(); ch.read(one); char c = (char) one.get(0); if (c == '\n') break; sb.append(c); }
        if (!sb.toString().equals(token)) { System.out.println("BAD TOKEN"); p.destroy(); return; }
        System.out.println("[java] host connected after " + (System.nanoTime() - tStart) / 1000000 + " ms");
        ByteBuffer hdr = ByteBuffer.allocateDirect(16);
        ByteBuffer frame = ByteBuffer.allocateDirect(1920 * 1080 * 4);
        long frames = 0, bytes = 0, t0 = System.nanoTime(), first = -1; long readNs = 0; int w = 0, h = 0; long chk = 0;
        long cpu0 = cpuNs();
        try {
            while (true) {
                hdr.clear(); if (!readFully(ch, hdr)) break; hdr.flip();
                if (hdr.getInt() != 0x414B5746) throw new IOException("bad magic");
                w = hdr.getInt(); h = hdr.getInt(); int len = hdr.getInt();
                frame.clear(); frame.limit(len);
                long r0 = System.nanoTime();
                if (!readFully(ch, frame)) break;
                readNs += System.nanoTime() - r0;
                if (first < 0) first = (System.nanoTime() - tStart) / 1000000;
                chk += frame.get(len / 2 + 1) & 0xff; // touch the frame (here glTexSubImage2D would read it)
                frames++; bytes += len;
            }
        } catch (IOException e) { System.out.println("[java] read ended: " + e.getMessage()); }
        double s = (System.nanoTime() - t0) / 1e9;
        long cpu = cpuNs() - cpu0;
        System.out.printf("[java] frames=%d size=%dx%d fps=%.1f MB/s=%.1f firstFrameMs=%d avgReadMsPerFrame=%.2f javaCpuPct=%.1f chk=%d%n",
            frames, w, h, frames / s, bytes / s / 1e6, first, readNs / 1e6 / Math.max(1, frames), 100.0 * cpu / (s * 1e9), chk);
        int code = p.waitFor();
        System.out.println("[java] host exit code " + code);
    }
    static boolean readFully(SocketChannel ch, ByteBuffer b) throws IOException {
        while (b.hasRemaining()) { if (ch.read(b) < 0) return false; }
        return true;
    }
    static long cpuNs() {
        java.lang.management.ThreadMXBean t = java.lang.management.ManagementFactory.getThreadMXBean();
        return t.getCurrentThreadCpuTime();
    }
}
