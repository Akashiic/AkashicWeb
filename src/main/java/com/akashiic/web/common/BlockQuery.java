package com.akashiic.web.common;

/**
 * What the multiblock code needs to know about one block position. Implemented over a real World on the server (with
 * a chunk-loaded check, so measuring a screen never loads or generates chunks) and over plain grids in tests.
 */
public interface BlockQuery {

    enum Cell {
        /** A screen block. */
        SCREEN,
        /** Anything else, including air. */
        OTHER,
        /** The chunk is not loaded: the answer is unknown and the caller must not guess. */
        UNLOADED
    }

    Cell at(int x, int y, int z);
}
