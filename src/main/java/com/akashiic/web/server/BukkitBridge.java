package com.akashiic.web.server;

import java.lang.reflect.InvocationTargetException;
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
 * Cauldron-family servers such as Crucible), then {@code Bukkit.getPlayer(UUID)}. If reflection itself fails (a
 * method is missing, inaccessible or has another type), the bridge logs it and switches itself off for the rest of
 * the session instead of failing on every check. An exception thrown by Bukkit or the permission plugin inside the
 * call (LuckPerms during {@code /lp reload}, for example) only makes that one answer unknown, logged at most once a
 * minute; the bridge stays on (review finding: one such exception used to remove every LuckPerms-only admin until
 * restart).
 * <p>
 * Only an explicit grant counts ({@code isPermissionSet(node) && hasPermission(node)}). Our nodes are not registered
 * with Bukkit, and Bukkit's default for an unregistered node is "ops have it", which LuckPerms falls back to for
 * nodes it has no value for; asking hasPermission alone made every op a screen admin even with
 * {@code opsAreAdmins = false} (review finding).
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
    private static Method isPermissionSet;

    /** Answers lost to exceptions inside Bukkit since the last log line, and when that line was written. */
    private static long transientFailures;
    private static long lastTransientLogNs;
    static final long TRANSIENT_LOG_INTERVAL_NS = 60_000_000_000L;

    private BukkitBridge() {}

    public static synchronized State state() {
        probe();
        return state;
    }

    /**
     * {@code Boolean.TRUE} when Bukkit says the player was explicitly granted the node, {@code FALSE} when not, or
     * null when it cannot answer (absent, failed, not on the main thread, player not known to Bukkit, or the call threw
     * inside Bukkit).
     */
    public static synchronized Boolean hasPermission(EntityPlayerMP player, String node) {
        probe();
        if (state != State.ACTIVE || player == null) return null;
        if (!ServerThread.isMain()) return null;
        try {
            Object bukkit = bukkitPlayer(player);
            if (bukkit == null) return null;
            return explicitGrant(bukkit, isPermissionSet, hasPermission, node);
        } catch (ReflectiveOperationException | ClassCastException | IllegalArgumentException e) {
            return failed(e);
        }
    }

    /** Set and true: a node the player holds by a grant, never by Bukkit's op default for unregistered nodes. */
    static Boolean explicitGrant(Object permissible, Method isSet, Method has, String node)
        throws IllegalAccessException, InvocationTargetException {
        if (!Boolean.TRUE.equals(isSet.invoke(permissible, node))) return Boolean.FALSE;
        return Boolean.TRUE.equals(has.invoke(permissible, node));
    }

    /**
     * A failed call: an exception thrown inside Bukkit or its permission plugin ({@link InvocationTargetException})
     * loses this answer only; anything else means our reflection does not fit this server, and the bridge goes off.
     * Returns null, the "cannot answer" value.
     */
    static Boolean failed(Exception e) {
        if (e instanceof InvocationTargetException) {
            transientFailure(e.getCause() == null ? e : e.getCause());
        } else {
            fail(e);
        }
        return null;
    }

    private static void transientFailure(Throwable cause) {
        transientFailures++;
        long now = System.nanoTime();
        if (lastTransientLogNs != 0 && now - lastTransientLogNs < TRANSIENT_LOG_INTERVAL_NS) return;
        lastTransientLogNs = now;
        AkashicWeb.LOG.warn(
            "AkashicWeb: Bukkit permission check threw ({}); {} answer(s) unknown since the last report, the bridge"
                + " stays on",
            cause.toString(),
            transientFailures);
        transientFailures = 0;
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
            isPermissionSet = permissible.getMethod("isPermissionSet", String.class);
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
        isPermissionSet = null;
        transientFailures = 0;
        lastTransientLogNs = 0;
    }
}
