package com.akashiic.web.common;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * NBT form of the screen faces stored on the origin tile entity (save file and description packet). Everything read
 * back is clamped and sanitised: NBT can come from old saves, world editors or a hostile server.
 * <p>
 * Layout (version 1): {@code faces} = list of compounds {side, w, h, resW, resH, rot, url, epoch, owner (UUID string),
 * ownerName, friends = list of {id, name}, friendRights, otherRights, locked, mode, controller (description packet
 * only)}. Coordinates and dimension are not stored: they come from the tile entity when it joins a world.
 */
public final class ScreenNbt {

    public static final int VERSION = 1;
    /** Friends kept per face whatever the config says (bounds NBT and packet size). */
    public static final int MAX_FRIENDS = 64;
    public static final int MAX_NAME = 32;
    static final String FACES = "faces";

    private ScreenNbt() {}

    /**
     * Writes all non-null faces. {@code withController} adds the transient controller (description packet); the save
     * file never stores it, so nobody holds a lock after a restart.
     */
    public static void write(NBTTagCompound tag, Iterable<ScreenState> faces, boolean withController) {
        NBTTagList list = new NBTTagList();
        for (ScreenState s : faces) if (s != null) list.appendTag(writeFace(s, withController));
        tag.setTag(FACES, list);
        tag.setInteger("v", VERSION);
    }

    static NBTTagCompound writeFace(ScreenState s, boolean withController) {
        NBTTagCompound t = new NBTTagCompound();
        t.setByte("side", (byte) s.key.side);
        t.setShort("w", (short) s.widthBlocks);
        t.setShort("h", (short) s.heightBlocks);
        t.setShort("resW", (short) s.resolutionW);
        t.setShort("resH", (short) s.resolutionH);
        t.setShort("rot", (short) s.rotation);
        t.setString("url", s.url == null ? "" : s.url);
        t.setInteger("epoch", s.epoch);
        if (s.owner != null) t.setString("owner", s.owner.toString());
        t.setString("ownerName", s.ownerName == null ? "" : s.ownerName);
        NBTTagList friends = new NBTTagList();
        for (UUID f : s.friends) {
            NBTTagCompound fc = new NBTTagCompound();
            fc.setString("id", f.toString());
            String name = s.friendNames.get(f);
            fc.setString("name", name == null ? "" : name);
            friends.appendTag(fc);
        }
        t.setTag("friends", friends);
        t.setByte("friendRights", (byte) s.friendRights);
        t.setByte("otherRights", (byte) s.otherRights);
        t.setBoolean("locked", s.locked);
        t.setString("mode", s.mode.name());
        if (withController && s.controller != null) t.setString("controller", s.controller.toString());
        return t;
    }

    /**
     * Reads the faces; each gets a key built from the given position (dimension may be a placeholder until the tile
     * entity knows its world). Invalid faces are skipped; at most one face per side.
     */
    public static List<ScreenState> read(NBTTagCompound tag, int dim, int x, int y, int z) {
        List<ScreenState> out = new ArrayList<>();
        boolean[] seen = new boolean[6];
        NBTTagList list = tag.getTagList(FACES, 10);
        for (int i = 0; i < list.tagCount() && i < 6; i++) {
            ScreenState s = readFace(list.getCompoundTagAt(i), dim, x, y, z);
            if (s == null || seen[s.key.side]) continue;
            seen[s.key.side] = true;
            out.add(s);
        }
        return out;
    }

    static ScreenState readFace(NBTTagCompound t, int dim, int x, int y, int z) {
        BlockSide side = BlockSide.fromSide(t.getByte("side"));
        if (side == null) return null;
        int w = t.getShort("w"), h = t.getShort("h");
        if (w < ScreenFormation.MIN_SIZE || h < ScreenFormation.MIN_SIZE || w > 64 || h > 64) return null;
        ScreenState s = new ScreenState();
        s.key = new ScreenKey(dim, x, y, z, side.side());
        s.widthBlocks = w;
        s.heightBlocks = h;
        s.resolutionW = clamp(t.getShort("resW"), ScreenGeometry.MIN_RESOLUTION, 4096);
        s.resolutionH = clamp(t.getShort("resH"), ScreenGeometry.MIN_RESOLUTION, 4096);
        s.rotation = HitMath.quarterTurns(t.getShort("rot")) * 90;
        s.url = TextSanitizer.cleanUrl(t.getString("url"), UrlPolicy.MAX_URL_LENGTH);
        s.epoch = t.getInteger("epoch");
        s.owner = uuid(t.getString("owner"));
        s.ownerName = TextSanitizer.clean(t.getString("ownerName"), MAX_NAME);
        NBTTagList friends = t.getTagList("friends", 10);
        for (int i = 0; i < friends.tagCount() && s.friends.size() < MAX_FRIENDS; i++) {
            NBTTagCompound fc = friends.getCompoundTagAt(i);
            UUID id = uuid(fc.getString("id"));
            if (id == null) continue;
            s.friends.add(id);
            s.friendNames.put(id, TextSanitizer.clean(fc.getString("name"), MAX_NAME));
        }
        s.friendRights = t.getByte("friendRights") & Rights.ALL;
        s.otherRights = t.getByte("otherRights") & Rights.ALL;
        s.locked = t.getBoolean("locked");
        s.mode = mode(t.getString("mode"));
        s.controller = uuid(t.getString("controller"));
        return s;
    }

    static ScreenMode mode(String name) {
        try {
            return ScreenMode.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return ScreenMode.SHARED; // unknown or missing: the only mode used on 10-25
        }
    }

    static UUID uuid(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
