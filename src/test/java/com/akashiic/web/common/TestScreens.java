package com.akashiic.web.common;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Builders shared by the tests. */
public final class TestScreens {

    public static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    public static final UUID FRIEND = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    public static final UUID VISITOR = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private TestScreens() {}

    public static ScreenState state(BlockSide side, int x, int y, int z, int w, int h, int rotation) {
        ScreenState s = new ScreenState();
        s.key = new ScreenKey(0, x, y, z, side.side());
        s.widthBlocks = w;
        s.heightBlocks = h;
        s.rotation = rotation;
        s.resolutionW = 1280;
        s.resolutionH = 720;
        s.owner = OWNER;
        s.ownerName = "Owner";
        s.friends.add(FRIEND);
        s.friendNames.put(FRIEND, "Friend");
        s.url = "https://www.youtube.com/";
        return s;
    }

    /** A sparse world: listed positions are screen blocks, others air, and "unloaded" positions are unknown. */
    public static final class Grid implements BlockQuery {

        private final Set<String> screens = new HashSet<>();
        private final Set<String> unloaded = new HashSet<>();

        public Grid add(int x, int y, int z) {
            screens.add(x + "," + y + "," + z);
            return this;
        }

        public Grid remove(int x, int y, int z) {
            screens.remove(x + "," + y + "," + z);
            return this;
        }

        public Grid unload(int x, int y, int z) {
            unloaded.add(x + "," + y + "," + z);
            return this;
        }

        /** Fills a box (inclusive corners). */
        public Grid box(int x0, int y0, int z0, int x1, int y1, int z1) {
            for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
                for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                    for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) add(x, y, z);
            return this;
        }

        @Override
        public Cell at(int x, int y, int z) {
            String k = x + "," + y + "," + z;
            if (unloaded.contains(k)) return Cell.UNLOADED;
            return screens.contains(k) ? Cell.SCREEN : Cell.OTHER;
        }
    }
}
