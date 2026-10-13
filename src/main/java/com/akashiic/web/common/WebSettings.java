package com.akashiic.web.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable snapshot of the server settings ({@code config/akashicweb.cfg}, read by {@link WebConfig}). Readers take
 * one snapshot per decision, so a reload in the middle of an action never mixes old and new limits. Plain data, so
 * tests build it with {@link Builder} and no Forge.
 */
public final class WebSettings {

    // site policy
    public final SitePolicy sitePolicy;
    public final boolean allowHighPorts;
    public final int urlMaxLength;
    /** Target of the "home" navigation action. */
    public final String homeUrl;

    // screens
    public final int maxWidth, maxHeight;
    public final int adminMaxWidth, adminMaxHeight;
    /** 0 = unlimited. Admins are never limited. */
    public final int maxPerPlayer;
    public final boolean protectScreens;
    public final int defaultResolutionW, defaultResolutionH;
    public final int maxResolutionW, maxResolutionH;
    public final long maxResolutionPixels;
    public final int maxFriends;

    // limits
    public final int actionsPerSecond, mediaSamplesPerSecond, clockPingsPerSecond;
    public final double interactRange;
    /** Largest angle between the player's look and the screen when taking or keeping control (D-016). */
    public final double controlMaxAngleDeg;
    public final int mediaClockHz;
    public final int controllerIdleSeconds;

    // admin
    public final Set<UUID> adminUuids;
    public final boolean opsAreAdmins;

    // server
    public final boolean killSwitch;

    private final UrlGate urlGate;

    private WebSettings(Builder b) {
        sitePolicy = SitePolicy.of(b.siteMode, b.allow, b.block);
        allowHighPorts = b.allowHighPorts;
        urlMaxLength = clamp(b.urlMaxLength, 64, UrlPolicy.MAX_URL_LENGTH);
        homeUrl = b.homeUrl == null ? "" : b.homeUrl;
        maxWidth = clamp(b.maxWidth, ScreenFormation.MIN_SIZE, 64);
        maxHeight = clamp(b.maxHeight, ScreenFormation.MIN_SIZE, 64);
        adminMaxWidth = clamp(b.adminMaxWidth, maxWidth, 64);
        adminMaxHeight = clamp(b.adminMaxHeight, maxHeight, 64);
        maxPerPlayer = Math.max(0, b.maxPerPlayer);
        protectScreens = b.protectScreens;
        maxResolutionW = clamp(b.maxResolutionW, ScreenGeometry.MIN_RESOLUTION, 4096);
        maxResolutionH = clamp(b.maxResolutionH, ScreenGeometry.MIN_RESOLUTION, 4096);
        maxResolutionPixels = Math.max(
            (long) ScreenGeometry.MIN_RESOLUTION * ScreenGeometry.MIN_RESOLUTION,
            Math.min(b.maxResolutionPixels, (long) maxResolutionW * maxResolutionH));
        defaultResolutionW = clamp(b.defaultResolutionW, ScreenGeometry.MIN_RESOLUTION, maxResolutionW);
        defaultResolutionH = clamp(b.defaultResolutionH, ScreenGeometry.MIN_RESOLUTION, maxResolutionH);
        maxFriends = clamp(b.maxFriends, 0, 64);
        actionsPerSecond = clamp(b.actionsPerSecond, 1, 100);
        mediaSamplesPerSecond = clamp(b.mediaSamplesPerSecond, 1, 40);
        clockPingsPerSecond = clamp(b.clockPingsPerSecond, 1, 40);
        interactRange = Math.max(2, Math.min(256, b.interactRange));
        controlMaxAngleDeg = Math.max(5, Math.min(90, b.controlMaxAngleDeg));
        mediaClockHz = clamp(b.mediaClockHz, 1, 10);
        controllerIdleSeconds = clamp(b.controllerIdleSeconds, 5, 3600);
        adminUuids = Collections.unmodifiableSet(new LinkedHashSet<>(b.adminUuids));
        opsAreAdmins = b.opsAreAdmins;
        killSwitch = b.killSwitch;
        urlGate = new UrlGate(new UrlPolicy(allowHighPorts, urlMaxLength), sitePolicy);
    }

    public UrlGate urlGate() {
        return urlGate;
    }

    /** Server ticks between two media clock broadcasts (20 ticks per second). */
    public int mediaClockIntervalTicks() {
        return Math.max(1, 20 / mediaClockHz);
    }

    public static WebSettings defaults() {
        return new Builder().build();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    /** Mutable builder with the documented defaults. */
    public static final class Builder {

        /**
         * Allowlist for public screens (ADR-013, P-05 pending with the owner): YouTube, Twitch and the hosts their
         * players load. Subdomains are included.
         */
        public static final List<String> DEFAULT_ALLOW = Collections.unmodifiableList(
            Arrays.asList(
                "youtube.com",
                "youtube-nocookie.com",
                "youtu.be",
                "googlevideo.com",
                "ytimg.com",
                "ggpht.com",
                "gstatic.com",
                "twitch.tv",
                "ttvnw.net",
                "jtvnw.net",
                "twitchcdn.net",
                "twitchsvc.net",
                "live-video.net"));
        /** Known IP-logger domains (AW-09); the block list applies in both modes. */
        public static final List<String> DEFAULT_BLOCK = Collections.unmodifiableList(
            Arrays.asList(
                "grabify.link",
                "iplogger.org",
                "iplogger.com",
                "iplogger.ru",
                "2no.co",
                "yip.su",
                "ipgrabber.ru",
                "iplis.ru",
                "ezstat.ru",
                "blasze.tk"));

        public SitePolicy.Mode siteMode = SitePolicy.Mode.ALLOWLIST;
        public List<String> allow = new ArrayList<>(DEFAULT_ALLOW);
        public List<String> block = new ArrayList<>(DEFAULT_BLOCK);
        public boolean allowHighPorts = false;
        public int urlMaxLength = 1024;
        public String homeUrl = "https://www.youtube.com/";
        public int maxWidth = 16, maxHeight = 16;
        public int adminMaxWidth = 32, adminMaxHeight = 32;
        public int maxPerPlayer = 4;
        public boolean protectScreens = true;
        public int defaultResolutionW = 1280, defaultResolutionH = 720;
        public int maxResolutionW = 1920, maxResolutionH = 1920;
        public long maxResolutionPixels = 1920L * 1080L;
        public int maxFriends = 32;
        public int actionsPerSecond = 4;
        public int mediaSamplesPerSecond = 8;
        public int clockPingsPerSecond = 8;
        public double interactRange = 24;
        public double controlMaxAngleDeg = 45;
        public int mediaClockHz = 4;
        public int controllerIdleSeconds = 60;
        public List<UUID> adminUuids = new ArrayList<>();
        public boolean opsAreAdmins = true;
        public boolean killSwitch = false;

        public WebSettings build() {
            return new WebSettings(this);
        }
    }
}
