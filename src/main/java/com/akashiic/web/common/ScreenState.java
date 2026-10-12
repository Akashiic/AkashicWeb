package com.akashiic.web.common;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Full record of one screen face: NBT form on the server, network form in S2CScreenState and the TE description
 * packet. Mutable by design (owned by the tile entity on the server, replaced wholesale on the client). Contract type.
 */
public final class ScreenState {

    public ScreenKey key;
    public int widthBlocks;
    public int heightBlocks;
    /** Page size in CSS pixels. */
    public int resolutionW;

    public int resolutionH;
    /** 0, 90, 180 or 270 degrees, clockwise when looking at the face. */
    public int rotation;

    public String url = "";
    /** Increments on every URL change; packets carrying an older epoch are dropped. */
    public int epoch;

    public UUID owner;
    public String ownerName = "";
    public final Set<UUID> friends = new LinkedHashSet<>();
    public int friendRights = Rights.DEFAULT_FRIENDS;
    public int otherRights = Rights.DEFAULT_OTHERS;
    public boolean locked;
    /** Player currently driving input on SHARED screens, or null. */
    public UUID controller;

    public ScreenMode mode = ScreenMode.SHARED;

    // ---- added by M4 (additive; existing members unchanged) ----

    /** Friend names as last seen by the server, for the rights GUI (UUID -> name). Same keys as {@link #friends}. */
    public final java.util.Map<UUID, String> friendNames = new java.util.LinkedHashMap<>();

    /** Deep copy, so a receiver can keep a snapshot while the owner keeps mutating its record. */
    public ScreenState copy() {
        ScreenState c = new ScreenState();
        c.key = key;
        c.widthBlocks = widthBlocks;
        c.heightBlocks = heightBlocks;
        c.resolutionW = resolutionW;
        c.resolutionH = resolutionH;
        c.rotation = rotation;
        c.url = url;
        c.epoch = epoch;
        c.owner = owner;
        c.ownerName = ownerName;
        c.friends.addAll(friends);
        c.friendNames.putAll(friendNames);
        c.friendRights = friendRights;
        c.otherRights = otherRights;
        c.locked = locked;
        c.controller = controller;
        c.mode = mode;
        return c;
    }

    @Override
    public String toString() {
        return key + " " + widthBlocks + "x" + heightBlocks + " epoch=" + epoch + " mode=" + mode;
    }
}
