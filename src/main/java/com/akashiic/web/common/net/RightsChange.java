package com.akashiic.web.common.net;

import java.util.ArrayList;
import java.util.List;

/** C2SRights payload. Fields left at {@link #UNCHANGED} (or empty lists) are not touched. Contract type. */
public final class RightsChange {

    public static final int UNCHANGED = -1;

    /** Player names, resolved on the server. */
    public final List<String> addFriends = new ArrayList<>();

    public final List<String> removeFriends = new ArrayList<>();
    public int friendRights = UNCHANGED;
    public int otherRights = UNCHANGED;
    public int resolutionW = UNCHANGED;
    public int resolutionH = UNCHANGED;
    public int rotation = UNCHANGED;
    /** 0 unlock, 1 lock, UNCHANGED otherwise (admins only). */
    public int locked = UNCHANGED;
}
