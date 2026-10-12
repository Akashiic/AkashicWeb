package com.akashiic.web.common;

/** How viewers share a screen (ADR-012). Contract type. */
public enum ScreenMode {
    /** Everyone sees the same URL and media clock; one controller drives input. */
    SHARED,
    /** Each viewer browses on their own; only the URL is shared. */
    PRIVATE,
    /** The controller's view is followed by URL and media clock only (no input relay). */
    CAST
}
