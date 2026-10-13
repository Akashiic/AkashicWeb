package com.akashiic.web.server;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

import com.akashiic.web.common.BlockSide;
import com.akashiic.web.common.ScreenKey;
import com.akashiic.web.common.TextSanitizer;
import com.akashiic.web.common.net.NetCodec;

/**
 * Saves the {@link ScreenIndex} and the {@link KnownPlayers} in {@code data/akashicweb_screens.dat} of the overworld
 * (the map storage every dimension shares in 1.7.10). The index is a cache of what the tile entities say, so a lost
 * or damaged file only costs accuracy of {@code /webadmin list} and the per-player limit until chunks load again; the
 * known players come back as they log in.
 */
public final class ScreenIndexData extends WorldSavedData {

    public static final String NAME = "akashicweb_screens";

    public final ScreenIndex index = new ScreenIndex();
    public final KnownPlayers players = new KnownPlayers();

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
        readPlayers(tag, players);
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        NBTTagList list = new NBTTagList();
        for (ScreenIndex.Entry e : index.all()) list.appendTag(write(e));
        tag.setTag("screens", list);
        writePlayers(tag, players);
    }

    static void writePlayers(NBTTagCompound tag, KnownPlayers players) {
        NBTTagList list = new NBTTagList();
        for (KnownPlayers.Entry e : players.all()) {
            NBTTagCompound t = new NBTTagCompound();
            t.setString("id", e.id.toString());
            t.setString("name", e.name);
            t.setLong("seen", e.seenMs);
            list.appendTag(t);
        }
        tag.setTag("players", list);
    }

    /** Damaged entries are skipped; names are re-validated like names on the wire. */
    static void readPlayers(NBTTagCompound tag, KnownPlayers players) {
        players.clear();
        NBTTagList list = tag.getTagList("players", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound t = list.getCompoundTagAt(i);
            String name = t.getString("name");
            if (!NetCodec.isPlayerName(name)) continue;
            try {
                players.seen(UUID.fromString(t.getString("id")), name, t.getLong("seen"));
            } catch (IllegalArgumentException e) {
                // a damaged id: that player is simply unknown until they log in again
            }
        }
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
