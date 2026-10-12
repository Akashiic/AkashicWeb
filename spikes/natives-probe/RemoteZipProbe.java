import java.io.*;
import java.net.*;
import java.nio.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/**
 * Probe: fetch only selected entries of a remote ZIP using HTTP Range requests,
 * inflate them with java.util.zip.Inflater (raw deflate) and print SHA-256.
 * Java 8 source level. Usage: RemoteZipProbe <url> <outDir> <prefix1,prefix2,...>
 */
public class RemoteZipProbe {
    static long totalBytesFetched = 0;
    static int requests = 0;

    static byte[] range(String url, long start, long endInclusive) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "AkashicWeb-probe/0");
        c.setRequestProperty("Range", start < 0 ? "bytes=" + start : "bytes=" + start + "-" + endInclusive);
        int code = c.getResponseCode(); requests++;
        if (code != 206) throw new IOException("expected 206, got " + code);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[1 << 16]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            totalBytesFetched += out.size();
            return out.toByteArray();
        } finally { c.disconnect(); }
    }

    static void rangeToFile(String url, long start, long len, File f) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setRequestProperty("Range", "bytes=" + start + "-" + (start + len - 1));
        int code = c.getResponseCode(); requests++;
        if (code != 206) throw new IOException("expected 206, got " + code);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(f)) {
            byte[] b = new byte[1 << 16]; int n; long got = 0;
            while ((n = in.read(b)) > 0) { out.write(b, 0, n); got += n; }
            totalBytesFetched += got;
            if (got != len) throw new IOException("short read " + got + " != " + len);
        } finally { c.disconnect(); }
    }

    public static void main(String[] a) throws Exception {
        String url = a[0]; File outDir = new File(a[1]); String[] prefixes = a[2].split(",");
        long t0 = System.nanoTime();
        byte[] tail = range(url, -65557, 0);
        ByteBuffer bb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = -1;
        for (int i = tail.length - 22; i >= 0; i--) if (bb.getInt(i) == 0x06054b50) { eocd = i; break; }
        if (eocd < 0) throw new IOException("no EOCD");
        int entries = bb.getShort(eocd + 10) & 0xffff;
        long cdSize = bb.getInt(eocd + 12) & 0xffffffffL, cdOff = bb.getInt(eocd + 16) & 0xffffffffL;
        byte[] cd = range(url, cdOff, cdOff + cdSize - 1);
        ByteBuffer c = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN);
        int p = 0; long wantedCompressed = 0; int wanted = 0;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (int e = 0; e < entries; e++) {
            if (c.getInt(p) != 0x02014b50) throw new IOException("bad CD at " + p);
            int method = c.getShort(p + 10) & 0xffff;
            long csize = c.getInt(p + 20) & 0xffffffffL, usize = c.getInt(p + 24) & 0xffffffffL;
            int nlen = c.getShort(p + 28) & 0xffff, xlen = c.getShort(p + 30) & 0xffff, clen = c.getShort(p + 32) & 0xffff;
            long lho = c.getInt(p + 42) & 0xffffffffL;
            String name = new String(cd, p + 46, nlen, "UTF-8");
            p += 46 + nlen + xlen + clen;
            boolean take = false;
            for (String pre : prefixes) if (name.startsWith(pre) && !name.endsWith("/")) take = true;
            if (!take) continue;
            wanted++; wantedCompressed += csize;
            byte[] lh = range(url, lho, lho + 29);
            ByteBuffer l = ByteBuffer.wrap(lh).order(ByteOrder.LITTLE_ENDIAN);
            if (l.getInt(0) != 0x04034b50) throw new IOException("bad LFH for " + name);
            long dataStart = lho + 30 + (l.getShort(26) & 0xffff) + (l.getShort(28) & 0xffff);
            File out = new File(outDir, name);
            if (!out.getCanonicalPath().startsWith(outDir.getCanonicalPath() + File.separator)) throw new IOException("zip-slip " + name);
            out.getParentFile().mkdirs();
            File part = new File(out.getPath() + ".deflate.part");
            if (csize > 0) rangeToFile(url, dataStart, csize, part); else new FileOutputStream(part).close();
            md.reset();
            try (InputStream in = method == 8 ? new InflaterInputStream(new FileInputStream(part), new Inflater(true), 1 << 16) : new FileInputStream(part);
                 OutputStream os = new FileOutputStream(out)) {
                byte[] b = new byte[1 << 16]; int n; long w = 0;
                while ((n = in.read(b)) > 0) { os.write(b, 0, n); md.update(b, 0, n); w += n; if (w > usize) throw new IOException("size overflow " + name); }
                if (w != usize) throw new IOException("size mismatch " + name + " " + w + " != " + usize);
            }
            part.delete();
            StringBuilder hex = new StringBuilder(); for (byte x : md.digest()) hex.append(String.format("%02x", x));
            if (name.endsWith("libcef.dll") || name.endsWith("icudtl.dat")) System.out.println(hex + "  " + name + " (" + usize + " bytes, " + csize + " compressed)");
        }
        System.out.printf("entries=%d wanted=%d wantedCompressed=%d fetched=%d requests=%d seconds=%.1f%n",
            entries, wanted, wantedCompressed, totalBytesFetched, requests, (System.nanoTime() - t0) / 1e9);
    }
}
