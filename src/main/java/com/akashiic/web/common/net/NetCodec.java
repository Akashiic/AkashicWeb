package com.akashiic.web.common.net;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.akashiic.web.common.ReasonCode;
import com.akashiic.web.common.Rights;
import com.akashiic.web.common.ScreenGeometry;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.ScreenMode;
import com.akashiic.web.common.ScreenNbt;
import com.akashiic.web.common.ScreenState;
import com.akashiic.web.common.SitePolicy;
import com.akashiic.web.common.TextSanitizer;
import com.akashiic.web.common.UrlPolicy;

import io.netty.buffer.ByteBuf;

/**
 * Field codecs for the {@code akashicweb} channel (docs/PROTOCOL.md section 2). Readers enforce every length cap
 * before allocating and sanitise text, so a hostile packet costs at most its own size; writers apply the same caps so
 * an honest sender never produces a packet the other side refuses.
 */
public final class NetCodec {

    /** Wire caps (characters unless stated). */
    public static final int MAX_URL = UrlPolicy.MAX_URL_LENGTH;
    public static final int MAX_NAME = 32;
    public static final int MAX_ACTION = 16;
    public static final int MAX_NOTICE_ARGS = 4;
    public static final int MAX_NOTICE_ARG = 256;
    /**
     * Friend names per list (add, remove) in one C2SRights. Each name costs a lookup on the server's main thread, so
     * a packet carries few (review finding: 16 + 16 names per packet each cost a blocking Mojang lookup).
     */
    public static final int MAX_FRIEND_CHANGES = 4;
    /** Longest Minecraft player name. */
    public static final int MAX_PLAYER_NAME = 16;
    public static final int MAX_PREFLIGHT_ENTRIES = 16;
    public static final int MAX_PREFLIGHT_KEY = 32;
    public static final int MAX_PREFLIGHT_VALUE = 128;

    private NetCodec() {}

    // ---- primitives ----

    public static void writeVarInt(ByteBuf b, int v) {
        while ((v & ~0x7F) != 0) {
            b.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        b.writeByte(v);
    }

    public static int readVarInt(ByteBuf b) {
        int v = 0;
        for (int i = 0; i < 5; i++) {
            int x = b.readUnsignedByte();
            v |= (x & 0x7F) << (7 * i);
            if ((x & 0x80) == 0) return v;
        }
        throw new CodecException("varint too long");
    }

    /** UTF-8 with a varint byte length. Longer text is cut at {@code maxChars} without splitting a surrogate pair. */
    public static void writeString(ByteBuf b, String s, int maxChars) {
        String v = s == null ? "" : s;
        if (v.length() > maxChars) {
            int end = maxChars;
            if (end > 0 && Character.isHighSurrogate(v.charAt(end - 1))) end--;
            v = v.substring(0, end);
        }
        byte[] bytes = v.getBytes(StandardCharsets.UTF_8);
        writeVarInt(b, bytes.length);
        b.writeBytes(bytes);
    }

    /** Raw string, length-checked (a UTF-16 char is at most 3 UTF-8 bytes). Not sanitised. */
    public static String readString(ByteBuf b, int maxChars) {
        int len = readVarInt(b);
        if (len < 0 || len > maxChars * 3) throw new CodecException("string of " + len + " bytes");
        if (len > b.readableBytes()) throw new CodecException("truncated string");
        byte[] bytes = new byte[len];
        b.readBytes(bytes);
        String s = new String(bytes, StandardCharsets.UTF_8);
        if (s.length() > maxChars) throw new CodecException("string of " + s.length() + " chars");
        return s;
    }

    /** Player-visible text (names, notice args): no control or formatting characters. */
    public static String readText(ByteBuf b, int maxChars) {
        return TextSanitizer.clean(readString(b, maxChars), maxChars);
    }

    public static String readUrl(ByteBuf b) {
        return TextSanitizer.cleanUrl(readString(b, MAX_URL), MAX_URL);
    }

    public static void writeUuid(ByteBuf b, UUID id) {
        b.writeBoolean(id != null);
        if (id != null) {
            b.writeLong(id.getMostSignificantBits());
            b.writeLong(id.getLeastSignificantBits());
        }
    }

    public static UUID readUuid(ByteBuf b) {
        return b.readBoolean() ? new UUID(b.readLong(), b.readLong()) : null;
    }

    public static double readFinite(ByteBuf b) {
        double d = b.readDouble();
        if (Double.isNaN(d) || Double.isInfinite(d)) throw new CodecException("non-finite double");
        return d;
    }

    public static float readFiniteFloat(ByteBuf b) {
        float f = b.readFloat();
        if (Float.isNaN(f) || Float.isInfinite(f)) throw new CodecException("non-finite float");
        return f;
    }

    // ---- composite values ----

    public static void writeKey(ByteBuf b, ScreenKey k) {
        b.writeInt(k.dim);
        b.writeInt(k.x);
        b.writeInt(k.y);
        b.writeInt(k.z);
        b.writeByte(k.side);
    }

    public static ScreenKey readKey(ByteBuf b) {
        int dim = b.readInt(), x = b.readInt(), y = b.readInt(), z = b.readInt();
        int side = b.readUnsignedByte();
        if (side > 5) throw new CodecException("side " + side);
        return new ScreenKey(dim, x, y, z, side);
    }

    public static void writeSample(ByteBuf b, MediaSample s) {
        b.writeInt(s.epoch);
        b.writeDouble(s.mediaTime);
        b.writeFloat(s.rate);
        b.writeBoolean(s.paused);
        b.writeBoolean(s.adShowing);
        b.writeBoolean(s.live);
        b.writeDouble(s.behindLiveEdge);
        b.writeLong(s.clientServerTimeMs);
    }

    public static MediaSample readSample(ByteBuf b) {
        int epoch = b.readInt();
        double mediaTime = readFinite(b);
        float rate = readFiniteFloat(b);
        boolean paused = b.readBoolean(), ad = b.readBoolean(), live = b.readBoolean();
        double behind = readFinite(b);
        long stamp = b.readLong();
        return new MediaSample(epoch, mediaTime, rate, paused, ad, live, behind, stamp);
    }

    public static void writePolicy(ByteBuf b, SitePolicy p) {
        b.writeByte(p.mode.ordinal());
        writeList(b, p.allow);
        writeList(b, p.block);
    }

    public static SitePolicy readPolicy(ByteBuf b) {
        int m = b.readUnsignedByte();
        SitePolicy.Mode[] modes = SitePolicy.Mode.values();
        if (m >= modes.length) throw new CodecException("policy mode " + m);
        List<String> allow = readList(b), block = readList(b);
        return SitePolicy.of(modes[m], allow, block);
    }

    private static void writeList(ByteBuf b, List<String> list) {
        int n = Math.min(list.size(), SitePolicy.MAX_ENTRIES);
        writeVarInt(b, n);
        for (int i = 0; i < n; i++) writeString(b, list.get(i), SitePolicy.MAX_ENTRY_LENGTH);
    }

    private static List<String> readList(ByteBuf b) {
        int n = readVarInt(b);
        if (n < 0 || n > SitePolicy.MAX_ENTRIES) throw new CodecException("list of " + n);
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(readString(b, SitePolicy.MAX_ENTRY_LENGTH));
        return out;
    }

    /**
     * Full screen record (S2CScreenState). The controller and its name are included: they are part of the live network
     * form. Must be the last thing in its packet, because fields added later are appended here and read only when
     * present.
     */
    public static void writeState(ByteBuf b, ScreenState s) {
        writeKey(b, s.key);
        b.writeByte(s.widthBlocks);
        b.writeByte(s.heightBlocks);
        b.writeShort(s.resolutionW);
        b.writeShort(s.resolutionH);
        b.writeShort(s.rotation);
        writeString(b, s.url, MAX_URL);
        b.writeInt(s.epoch);
        writeUuid(b, s.owner);
        writeString(b, s.ownerName, MAX_NAME);
        int n = Math.min(s.friends.size(), ScreenNbt.MAX_FRIENDS);
        b.writeByte(n);
        int i = 0;
        for (UUID f : s.friends) {
            if (i++ >= n) break;
            writeUuid(b, f);
            writeString(b, s.friendNames.get(f), MAX_NAME);
        }
        b.writeByte(s.friendRights & Rights.ALL);
        b.writeByte(s.otherRights & Rights.ALL);
        b.writeBoolean(s.locked);
        writeUuid(b, s.controller);
        b.writeByte(s.mode.ordinal());
        // Appended by D-016; must stay the last field (S2CScreenState carries nothing after the state).
        writeString(b, s.controller == null ? "" : s.controllerName, MAX_NAME);
    }

    public static ScreenState readState(ByteBuf b) {
        ScreenState s = new ScreenState();
        s.key = readKey(b);
        s.widthBlocks = b.readUnsignedByte();
        s.heightBlocks = b.readUnsignedByte();
        s.resolutionW = b.readUnsignedShort();
        s.resolutionH = b.readUnsignedShort();
        s.rotation = b.readUnsignedShort();
        if (s.widthBlocks < 2 || s.heightBlocks < 2 || s.widthBlocks > 64 || s.heightBlocks > 64) {
            throw new CodecException("size " + s.widthBlocks + "x" + s.heightBlocks);
        }
        if (s.resolutionW < ScreenGeometry.MIN_RESOLUTION || s.resolutionH < ScreenGeometry.MIN_RESOLUTION
            || s.resolutionW > 4096
            || s.resolutionH > 4096) throw new CodecException("resolution");
        if (s.rotation % 90 != 0 || s.rotation >= 360) throw new CodecException("rotation " + s.rotation);
        s.url = readUrl(b);
        s.epoch = b.readInt();
        s.owner = readUuid(b);
        s.ownerName = readText(b, MAX_NAME);
        int n = b.readUnsignedByte();
        if (n > ScreenNbt.MAX_FRIENDS) throw new CodecException("friends " + n);
        for (int i = 0; i < n; i++) {
            UUID f = readUuid(b);
            String name = readText(b, MAX_NAME);
            if (f == null) throw new CodecException("null friend");
            s.friends.add(f);
            s.friendNames.put(f, name);
        }
        s.friendRights = b.readUnsignedByte() & Rights.ALL;
        s.otherRights = b.readUnsignedByte() & Rights.ALL;
        s.locked = b.readBoolean();
        s.controller = readUuid(b);
        int mode = b.readUnsignedByte();
        ScreenMode[] modes = ScreenMode.values();
        if (mode >= modes.length) throw new CodecException("mode " + mode);
        s.mode = modes[mode];
        // Appended by D-016 as the last field: a server of the same major built before it does not send it.
        s.controllerName = b.isReadable() ? readText(b, MAX_NAME) : "";
        if (s.controller == null) s.controllerName = "";
        return s;
    }

    public static void writeRights(ByteBuf b, RightsChange c) {
        writeNames(b, c.addFriends);
        writeNames(b, c.removeFriends);
        b.writeInt(c.friendRights);
        b.writeInt(c.otherRights);
        b.writeInt(c.resolutionW);
        b.writeInt(c.resolutionH);
        b.writeInt(c.rotation);
        b.writeInt(c.locked);
    }

    public static RightsChange readRights(ByteBuf b) {
        RightsChange c = new RightsChange();
        c.addFriends.addAll(readNames(b));
        c.removeFriends.addAll(readNames(b));
        c.friendRights = readOptional(b, 0, Rights.ALL);
        c.otherRights = readOptional(b, 0, Rights.ALL);
        c.resolutionW = readOptional(b, 1, 4096);
        c.resolutionH = readOptional(b, 1, 4096);
        c.rotation = readOptional(b, 0, 270);
        c.locked = readOptional(b, 0, 1);
        if (c.rotation != RightsChange.UNCHANGED && c.rotation % 90 != 0) throw new CodecException("rotation");
        return c;
    }

    /** An int that is either {@link RightsChange#UNCHANGED} or inside [lo, hi]. */
    private static int readOptional(ByteBuf b, int lo, int hi) {
        int v = b.readInt();
        if (v == RightsChange.UNCHANGED) return v;
        if (v < lo || v > hi) throw new CodecException("value " + v + " outside " + lo + ".." + hi);
        return v;
    }

    private static void writeNames(ByteBuf b, List<String> names) {
        int n = Math.min(names.size(), MAX_FRIEND_CHANGES);
        b.writeByte(n);
        for (int i = 0; i < n; i++) writeString(b, names.get(i), MAX_PLAYER_NAME);
    }

    /** Player names only ({@link #isPlayerName}); anything else makes the packet malformed. */
    private static List<String> readNames(ByteBuf b) {
        int n = b.readUnsignedByte();
        if (n > MAX_FRIEND_CHANGES) throw new CodecException("names " + n);
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String name = readString(b, MAX_PLAYER_NAME);
            if (!isPlayerName(name)) throw new CodecException("player name");
            out.add(name);
        }
        return out;
    }

    /** A Minecraft player name: 1 to 16 of A-Z, a-z, 0-9 and underscore (what Mojang and the proxy accept). */
    public static boolean isPlayerName(String s) {
        if (s == null || s.isEmpty() || s.length() > MAX_PLAYER_NAME) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    public static void writeNotice(ByteBuf b, ReasonCode code, String[] args) {
        writeString(b, code.name(), 48);
        int n = args == null ? 0 : Math.min(args.length, MAX_NOTICE_ARGS);
        b.writeByte(n);
        for (int i = 0; i < n; i++) writeString(b, args[i], MAX_NOTICE_ARG);
    }

    public static void writeMap(ByteBuf b, Map<String, String> map) {
        int n = Math.min(map.size(), MAX_PREFLIGHT_ENTRIES);
        b.writeByte(n);
        int i = 0;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (i++ >= n) break;
            writeString(b, e.getKey(), MAX_PREFLIGHT_KEY);
            writeString(b, e.getValue(), MAX_PREFLIGHT_VALUE);
        }
    }

    public static Map<String, String> readMap(ByteBuf b) {
        int n = b.readUnsignedByte();
        if (n > MAX_PREFLIGHT_ENTRIES) throw new CodecException("entries " + n);
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            String k = readText(b, MAX_PREFLIGHT_KEY);
            String v = readText(b, MAX_PREFLIGHT_VALUE);
            if (!k.isEmpty()) out.put(k, v);
        }
        return out;
    }
}
