package com.akashiic.web.server;

import java.lang.reflect.Method;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import com.akashiic.web.AkashicWeb;

/**
 * Bukkit permission checks on a Crucible server, by reflection only (no compile dependency on Bukkit or Crucible,
 * ADR-009/ADR-010), so LuckPerms decides who is an admin. On plain Forge (dev, E2E) Bukkit is absent and every call
 * answers "unknown". Must be called on the main thread (Bukkit's API is not thread-safe in general).
 * <p>
 * Lookup order: the CraftBukkit entity behind the Forge player ({@code Entity.getBukkitEntity()}, added by
 * Cauldron-family servers such as Crucible), then {@code Bukkit.getPlayer(UUID)}. If reflection fails once, the bridge
 * logs it and switches itself off for the rest of the session instead of failing on every check.
 */
public final class BukkitBridge {

    public enum State {
        /** Not probed yet. */
        UNPROBED,
        /** No Bukkit on this server (plain Forge). */
        ABSENT,
        ACTIVE,
        /** Bukkit is present but reflection failed; see the log. */
        FAILED
    }

    private static State state = State.UNPROBED;
    private static Method getBukkitEntity;
    private static Method bukkitGetPlayer;
    private static Method hasPermission;

    private BukkitBridge() {}

    public static synchronized State state() {
        probe();
        return state;
    }

    /**
     * {@code Boolean.TRUE}/{@code FALSE} from Bukkit, or null when it cannot answer (absent, failed, not on the main
     * thread, player not known to Bukkit). Note Bukkit's default for an unregistered node is "ops only".
     */
    public static synchronized Boolean hasPermission(EntityPlayerMP player, String node) {
        probe();
        if (state != State.ACTIVE || player == null) return null;
        if (!ServerThread.isMain()) return null;
        try {
            Object bukkit = bukkitPlayer(player);
            if (bukkit == null) return null;
            return (Boolean) hasPermission.invoke(bukkit, node);
        } catch (ReflectiveOperationException | ClassCastException | IllegalArgumentException e) {
            fail(e);
            return null;
        }
    }

    private static Object bukkitPlayer(EntityPlayerMP player) throws ReflectiveOperationException {
        if (getBukkitEntity != null) {
            Object o = getBukkitEntity.invoke(player);
            if (o != null && hasPermission.getDeclaringClass()
                .isInstance(o)) return o;
        }
        if (bukkitGetPlayer != null) {
            UUID id = player.getUniqueID();
            return bukkitGetPlayer.invoke(null, id);
        }
        return null;
    }

    private static void probe() {
        if (state != State.UNPROBED) return;
        ClassLoader cl = BukkitBridge.class.getClassLoader();
        Class<?> bukkit;
        try {
            bukkit = Class.forName("org.bukkit.Bukkit", false, cl);
        } catch (ClassNotFoundException e) {
            state = State.ABSENT;
            return;
        }
        try {
            Class<?> permissible = Class.forName("org.bukkit.permissions.Permissible", false, cl);
            hasPermission = permissible.getMethod("hasPermission", String.class);
            getBukkitEntity = findNoArg(EntityPlayerMP.class, "getBukkitEntity");
            try {
                bukkitGetPlayer = bukkit.getMethod("getPlayer", UUID.class);
            } catch (NoSuchMethodException e) {
                bukkitGetPlayer = null; // older Bukkit API: getBukkitEntity alone must work
            }
            if (getBukkitEntity == null && bukkitGetPlayer == null) throw new NoSuchMethodException("no player lookup");
            state = State.ACTIVE;
            AkashicWeb.LOG.info(
                "AkashicWeb: Bukkit permissions active (getBukkitEntity={}, Bukkit.getPlayer(UUID)={})",
                getBukkitEntity != null,
                bukkitGetPlayer != null);
        } catch (ReflectiveOperationException | LinkageError e) {
            fail(e);
        }
    }

    private static Method findNoArg(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                // keep walking up: Cauldron-family servers declare it on net.minecraft.entity.Entity
            }
        }
        return null;
    }

    private static void fail(Throwable e) {
        state = State.FAILED;
        AkashicWeb.LOG.warn(
            "AkashicWeb: Bukkit permission bridge disabled ({}); admins come from op and the config list only",
            e.toString());
    }

    /** Server stopped: probe again next start (a different server jar may be used). */
    public static synchronized void reset() {
        state = State.UNPROBED;
        getBukkitEntity = null;
        bukkitGetPlayer = null;
        hasPermission = null;
    }
}
