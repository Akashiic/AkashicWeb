package com.akashiic.web.common;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import net.minecraftforge.common.config.Configuration;

import com.akashiic.web.AkashicWeb;

/**
 * Typed wrapper over Forge's {@link Configuration} for {@code config/akashicweb.cfg} (ADR-009: Forge-only, no GTNHLib
 * config). Produces immutable {@link WebSettings} snapshots; {@code /webadmin policy reload} re-reads the file and
 * {@code /webadmin kill} writes the kill switch back so it survives a restart. Main thread only.
 */
public final class WebConfig {

    static final String SITE = "sitePolicy";
    static final String SCREENS = "screens";
    static final String LIMITS = "limits";
    static final String ADMIN = "admin";
    static final String SERVER = "server";

    private static File file;
    private static Configuration config;
    private static volatile WebSettings current = WebSettings.defaults();

    private WebConfig() {}

    /** preInit: remember the file and read it (creating it with defaults and comments). */
    public static void init(File configFile) {
        file = configFile;
        reload();
    }

    /** Current snapshot; defaults until {@link #init} ran (tests, early calls). */
    public static WebSettings get() {
        return current;
    }

    /**
     * Re-reads the file. Returns false (and keeps the previous snapshot) if it cannot be parsed; Forge's loader
     * renames a corrupt file and starts from defaults, which is logged.
     */
    public static synchronized boolean reload() {
        if (file == null) return false;
        try {
            Configuration c = new Configuration(file);
            WebSettings s = read(c);
            if (c.hasChanged()) c.save();
            config = c;
            current = s;
            return true;
        } catch (RuntimeException e) {
            // Boundary: a broken config must not take the server down; the previous settings stay in force.
            AkashicWeb.LOG.error("AkashicWeb: could not read {}: {}", file, e.toString());
            return false;
        }
    }

    /** Persists the kill switch and publishes a new snapshot. */
    public static synchronized void setKillSwitch(boolean on) {
        if (config != null) {
            config.get(SERVER, "killSwitch", false)
                .set(on);
            config.save();
            current = read(config);
        } else {
            WebSettings.Builder b = toBuilder(current);
            b.killSwitch = on;
            current = b.build();
        }
    }

    static WebSettings read(Configuration c) {
        WebSettings.Builder d = new WebSettings.Builder();
        WebSettings.Builder b = new WebSettings.Builder();
        c.addCustomCategoryComment(SITE, "Which pages screens may show. Internal addresses are always refused.");
        String mode = c.getString(
            "mode",
            SITE,
            d.siteMode.name(),
            "ALLOWLIST: only hosts in 'allow' (and their subdomains). BLOCKLIST: every public host except 'block'.");
        SitePolicy.Mode parsed = SitePolicy.parseMode(mode);
        if (parsed == null) AkashicWeb.LOG.warn("AkashicWeb: unknown sitePolicy.mode '{}', using ALLOWLIST", mode);
        b.siteMode = parsed == null ? SitePolicy.Mode.ALLOWLIST : parsed;
        b.allow = list(
            c.getStringList(
                "allow",
                SITE,
                d.allow.toArray(new String[0]),
                "Allowed hosts in ALLOWLIST mode; each entry also allows its subdomains."));
        b.block = list(
            c.getStringList(
                "block",
                SITE,
                d.block.toArray(new String[0]),
                "Hosts always refused (both modes), with their subdomains."));
        b.allowHighPorts = c
            .getBoolean("allowHighPorts", SITE, d.allowHighPorts, "Accept ports 1024-65535 besides 80/443.");
        b.urlMaxLength = c.getInt("urlMaxLength", SITE, d.urlMaxLength, 64, UrlPolicy.MAX_URL_LENGTH, "Longest URL.");
        b.homeUrl = c.getString("homeUrl", SITE, d.homeUrl, "Page opened by the 'home' button.");

        c.addCustomCategoryComment(SCREENS, "Screen multiblock limits.");
        b.maxWidth = c.getInt("maxWidth", SCREENS, d.maxWidth, 2, 64, "Widest screen a player may form, in blocks.");
        b.maxHeight = c.getInt("maxHeight", SCREENS, d.maxHeight, 2, 64, "Tallest screen a player may form.");
        b.adminMaxWidth = c
            .getInt("adminMaxWidth", SCREENS, d.adminMaxWidth, 2, 64, "Widest screen an admin may form.");
        b.adminMaxHeight = c
            .getInt("adminMaxHeight", SCREENS, d.adminMaxHeight, 2, 64, "Tallest screen an admin may form.");
        b.maxPerPlayer = c
            .getInt("maxPerPlayer", SCREENS, d.maxPerPlayer, 0, 1000, "Screens one player may own (0 = unlimited).");
        b.protectScreens = c.getBoolean(
            "protectScreens",
            SCREENS,
            d.protectScreens,
            "Only the owner and admins may break the blocks of a formed screen.");
        b.defaultResolutionW = c.getInt("defaultResolutionW", SCREENS, d.defaultResolutionW, 64, 4096, "");
        b.defaultResolutionH = c.getInt(
            "defaultResolutionH",
            SCREENS,
            d.defaultResolutionH,
            64,
            4096,
            "New screens fit their page inside defaultResolutionW x defaultResolutionH (720p by default).");
        b.maxResolutionW = c.getInt("maxResolutionW", SCREENS, d.maxResolutionW, 64, 4096, "");
        b.maxResolutionH = c.getInt("maxResolutionH", SCREENS, d.maxResolutionH, 64, 4096, "");
        b.maxResolutionPixels = c.getInt(
            "maxResolutionPixels",
            SCREENS,
            (int) d.maxResolutionPixels,
            4096,
            4096 * 4096,
            "Largest page area a player may set (width x height).");
        b.maxFriends = c.getInt("maxFriends", SCREENS, d.maxFriends, 0, 64, "Friends per screen.");

        c.addCustomCategoryComment(LIMITS, "Per-player rate limits (per second, burst 2x) and validation distances.");
        b.actionsPerSecond = c
            .getInt("actionsPerSecond", LIMITS, d.actionsPerSecond, 1, 100, "URL, nav, rights, control.");
        b.mediaSamplesPerSecond = c.getInt("mediaSamplesPerSecond", LIMITS, d.mediaSamplesPerSecond, 1, 40, "");
        b.clockPingsPerSecond = c.getInt("clockPingsPerSecond", LIMITS, d.clockPingsPerSecond, 1, 40, "");
        b.interactRange = c.getInt(
            "interactRange",
            LIMITS,
            (int) d.interactRange,
            2,
            256,
            "Blocks from the screen within which a player may change its URL, rights or control.");
        b.controlMaxAngleDeg = c.getInt(
            "controlMaxAngleDeg",
            LIMITS,
            (int) d.controlMaxAngleDeg,
            5,
            90,
            "Largest angle between the player's look and the screen when taking or keeping its controller lock.");
        b.mediaClockHz = c.getInt("mediaClockHz", LIMITS, d.mediaClockHz, 1, 10, "Media clock broadcasts per second.");
        b.controllerIdleSeconds = c.getInt(
            "controllerIdleSeconds",
            LIMITS,
            d.controllerIdleSeconds,
            5,
            3600,
            "The controller lock is released after this long without a keepalive from its holder (sent while it"
                + " interacts) and without playing media.");

        c.addCustomCategoryComment(
            ADMIN,
            "Admins: ops (if opsAreAdmins), Bukkit permission akashicweb.admin (Crucible + LuckPerms), or this list.");
        b.adminUuids = uuids(c.getStringList("adminUuids", ADMIN, new String[0], "Admin player UUIDs."));
        b.opsAreAdmins = c.getBoolean("opsAreAdmins", ADMIN, d.opsAreAdmins, "Server operators are screen admins.");

        b.killSwitch = c.getBoolean(
            "killSwitch",
            SERVER,
            d.killSwitch,
            "Turns every browser off: clients show a placeholder. Toggle live with /webadmin kill on|off.");
        return b.build();
    }

    private static List<String> list(String[] values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    static List<UUID> uuids(String[] values) {
        List<UUID> out = new ArrayList<>();
        for (String v : values) {
            try {
                out.add(UUID.fromString(v.trim()));
            } catch (IllegalArgumentException e) {
                AkashicWeb.LOG.warn("AkashicWeb: ignoring invalid admin UUID '{}'", v);
            }
        }
        return out;
    }

    private static WebSettings.Builder toBuilder(WebSettings s) {
        WebSettings.Builder b = new WebSettings.Builder();
        b.siteMode = s.sitePolicy.mode;
        b.allow = new ArrayList<>(s.sitePolicy.allow);
        b.block = new ArrayList<>(s.sitePolicy.block);
        b.allowHighPorts = s.allowHighPorts;
        b.urlMaxLength = s.urlMaxLength;
        b.homeUrl = s.homeUrl;
        b.maxWidth = s.maxWidth;
        b.maxHeight = s.maxHeight;
        b.adminMaxWidth = s.adminMaxWidth;
        b.adminMaxHeight = s.adminMaxHeight;
        b.maxPerPlayer = s.maxPerPlayer;
        b.protectScreens = s.protectScreens;
        b.defaultResolutionW = s.defaultResolutionW;
        b.defaultResolutionH = s.defaultResolutionH;
        b.maxResolutionW = s.maxResolutionW;
        b.maxResolutionH = s.maxResolutionH;
        b.maxResolutionPixels = s.maxResolutionPixels;
        b.maxFriends = s.maxFriends;
        b.actionsPerSecond = s.actionsPerSecond;
        b.mediaSamplesPerSecond = s.mediaSamplesPerSecond;
        b.clockPingsPerSecond = s.clockPingsPerSecond;
        b.interactRange = s.interactRange;
        b.controlMaxAngleDeg = s.controlMaxAngleDeg;
        b.mediaClockHz = s.mediaClockHz;
        b.controllerIdleSeconds = s.controllerIdleSeconds;
        b.adminUuids = new ArrayList<>(s.adminUuids);
        b.opsAreAdmins = s.opsAreAdmins;
        b.killSwitch = s.killSwitch;
        return b;
    }
}
