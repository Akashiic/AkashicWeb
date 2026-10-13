package com.akashiic.web.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.jupiter.api.Test;

class ScreenNbtTest {

    @Test
    void roundTripKeepsEverythingButTheController() {
        ScreenState s = TestScreens.state(BlockSide.TOP, 5, 70, -3, 6, 4, 270);
        s.epoch = 42;
        s.locked = true;
        s.mode = ScreenMode.CAST;
        s.friendRights = Rights.CLICK_TYPE;
        s.otherRights = Rights.CHANGE_URL;
        s.controller = TestScreens.FRIEND;
        NBTTagCompound tag = new NBTTagCompound();
        ScreenNbt.write(tag, Arrays.asList(s, null), false);
        List<ScreenState> back = ScreenNbt.read(tag, 7, 5, 70, -3);
        assertEquals(1, back.size());
        ScreenState r = back.get(0);
        assertEquals(new ScreenKey(7, 5, 70, -3, BlockSide.TOP.side()), r.key);
        assertEquals(6, r.widthBlocks);
        assertEquals(4, r.heightBlocks);
        assertEquals(270, r.rotation);
        assertEquals(s.url, r.url);
        assertEquals(42, r.epoch);
        assertEquals(TestScreens.OWNER, r.owner);
        assertEquals("Owner", r.ownerName);
        assertEquals(s.friends, r.friends);
        assertEquals("Friend", r.friendNames.get(TestScreens.FRIEND));
        assertEquals(Rights.CLICK_TYPE, r.friendRights);
        assertEquals(Rights.CHANGE_URL, r.otherRights);
        assertTrue(r.locked);
        assertEquals(ScreenMode.CAST, r.mode);
        assertNull(r.controller, "the save file never holds a controller lock");
        assertEquals("", r.controllerName);
        s.controllerName = "Friend";
        NBTTagCompound desc = new NBTTagCompound();
        ScreenNbt.write(desc, Arrays.asList(s), true);
        ScreenState d = ScreenNbt.read(desc, 0, 5, 70, -3)
            .get(0);
        assertEquals(TestScreens.FRIEND, d.controller);
        assertEquals("Friend", d.controllerName, "D-016: the description packet names the controller");
        NBTTagCompound save = new NBTTagCompound();
        ScreenNbt.write(save, Arrays.asList(s), false);
        assertEquals(
            "",
            ScreenNbt.read(save, 0, 5, 70, -3)
                .get(0).controllerName,
            "nor its name");
    }

    @Test
    void hostileNbtIsClampedOrSkipped() {
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagList faces = new NBTTagList();
        faces.appendTag(face(9, 4, 4)); // bad side
        faces.appendTag(face(2, 1, 4)); // too small
        faces.appendTag(face(3, 99, 4)); // too big
        NBTTagCompound good = face(2, 3, 3);
        good.setShort("rot", (short) 45);
        good.setShort("resW", (short) 10);
        good.setByte("friendRights", (byte) 0xFF);
        good.setString("mode", "SOMETHING_NEW");
        good.setString("owner", "not-a-uuid");
        good.setString("ownerName", "§kEvil\nName");
        faces.appendTag(good);
        faces.appendTag(face(2, 4, 4)); // second face on the same side: ignored
        tag.setTag("faces", faces);
        List<ScreenState> read = ScreenNbt.read(tag, 0, 0, 0, 0);
        assertEquals(1, read.size());
        ScreenState s = read.get(0);
        assertEquals(3, s.widthBlocks);
        assertEquals(0, s.rotation);
        assertEquals(ScreenGeometry.MIN_RESOLUTION, s.resolutionW);
        assertEquals(Rights.ALL, s.friendRights);
        assertEquals(ScreenMode.SHARED, s.mode);
        assertNull(s.owner);
        assertEquals("EvilName", s.ownerName);
    }

    private static NBTTagCompound face(int side, int w, int h) {
        NBTTagCompound t = new NBTTagCompound();
        t.setByte("side", (byte) side);
        t.setShort("w", (short) w);
        t.setShort("h", (short) h);
        t.setShort("resW", (short) 1280);
        t.setShort("resH", (short) 720);
        return t;
    }
}
