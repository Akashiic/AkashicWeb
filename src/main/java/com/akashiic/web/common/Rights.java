package com.akashiic.web.common;

/** Screen permission bits (ported idea from Web Displays ScreenRights, montoyo, public domain). Contract type. */
public final class Rights {

    public static final int CHANGE_URL = 1;
    public static final int CLICK_TYPE = 2;
    public static final int MANAGE_FRIENDS = 4;
    public static final int MANAGE_RIGHTS = 8;
    public static final int CHANGE_RESOLUTION = 16;
    public static final int ALL = CHANGE_URL | CLICK_TYPE | MANAGE_FRIENDS | MANAGE_RIGHTS | CHANGE_RESOLUTION;

    /** Visitors only watch (ADR-013). */
    public static final int DEFAULT_OTHERS = 0;
    public static final int DEFAULT_FRIENDS = CHANGE_URL | CLICK_TYPE;

    private Rights() {}

    public static boolean has(int bits, int right) {
        return (bits & right) == right;
    }
}
