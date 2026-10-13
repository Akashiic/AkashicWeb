package com.akashiic.web.server;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

import com.akashiic.web.common.BlockSide;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.TextSanitizer;

/**
 * Saves the {@link ScreenIndex} in {@code data/akashicweb_screens.dat} of the overworld (the map storage every
 * dimension shares in 1.7.10). The index is a cache of what the tile entities say, so a lost or damaged file only
 * costs accuracy of {@code /webadmin list} and the per-player limit until chunks load again.
 */
public final class ScreenIndexData extends WorldSavedData {

    public static final String NAME = "akashicweb_screens";

    public final ScreenIndex index = new ScreenIndex();

    /** Called by MapStorage through reflection. */
    public ScreenIndexData(String name) {
        super(name);
    }

    /** The shared instance, created on first use. */
    public static ScreenIndexData get(World overworld) {
        ScreenIndexData data = (ScreenIndexData) overworld.mapStorage.loadData(ScreenIndexData.class, NAME);
        if (data == null) {
            data = new ScreenIndexData(NAME);
            overworld.mapStorage.setData(NAME, data);
        }
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        index.clear();
        NBTTagList list = tag.getTagList("screens", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            ScreenIndex.Entry e = read(list.getCompoundTagAt(i));
            if (e != null) index.put(e);
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        NBTTagList list = new NBTTagList();
        for (ScreenIndex.Entry e : index.all()) list.appendTag(write(e));
        tag.setTag("screens", list);
    }

    static NBTTagCompound write(ScreenIndex.Entry e) {
        NBTTagCompound t = new NBTTagCompound();
        t.setInteger("dim", e.key.dim);
        t.setInteger("x", e.key.x);
        t.setInteger("y", e.key.y);
        t.setInteger("z", e.key.z);
        t.setByte("side", (byte) e.key.side);
        if (e.owner != null) t.setString("owner", e.owner.toString());
        t.setString("ownerName", e.ownerName);
        t.setShort("w", (short) e.width);
        t.setShort("h", (short) e.height);
        t.setString("url", e.url);
        return t;
    }

    static ScreenIndex.Entry read(NBTTagCompound t) {
        BlockSide side = BlockSide.fromSide(t.getByte("side"));
        if (side == null) return null;
        UUID owner = null;
        try {
            if (t.hasKey("owner")) owner = UUID.fromString(t.getString("owner"));
        } catch (IllegalArgumentException e) {
            owner = null; // a damaged owner only loses the limit count for that screen
        }
        ScreenKey key = new ScreenKey(
            t.getInteger("dim"),
            t.getInteger("x"),
            t.getInteger("y"),
            t.getInteger("z"),
            side.side());
        return new ScreenIndex.Entry(
            key,
            owner,
            TextSanitizer.clean(t.getString("ownerName"), 32),
            t.getShort("w"),
            t.getShort("h"),
            TextSanitizer.cleanUrl(t.getString("url"), ScreenIndex.URL_CHARS));
    }
}
