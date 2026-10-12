package com.akashiic.web.common;

import java.util.Collection;

/**
 * What the client renderer needs from the screen tile entity, so M5 does not depend on M4's concrete class. M4's
 * tile entity implements it. Contract type.
 */
public interface ScreenTile {

    /** Screen shown on the given face, or null. */
    ScreenState screen(int side);

    Collection<ScreenState> screens();
}
