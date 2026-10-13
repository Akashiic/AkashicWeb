package com.akashiic.web.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The Bukkit permission bridge (review finding, low): only explicit grants count, and an exception inside Bukkit or
 * LuckPerms loses one answer instead of switching the bridge off until restart. Bukkit is absent from the test
 * classpath, so a stand-in with Bukkit's Permissible semantics is called through the same reflective path.
 */
class BukkitBridgeTest {

    /** Bukkit's PermissibleBase for unregistered nodes: set values win, otherwise ops have every node. */
    public static final class Permissible {

        final Map<String, Boolean> set = new HashMap<>();
        boolean op;
        boolean throwing;

        public boolean isPermissionSet(String node) {
            if (throwing) throw new IllegalStateException("permission plugin reloading");
            return set.containsKey(node);
        }

        public boolean hasPermission(String node) {
            if (throwing) throw new IllegalStateException("permission plugin reloading");
            Boolean v = set.get(node);
            return v != null ? v : op;
        }
    }

    private static Boolean ask(Permissible p, String node) throws ReflectiveOperationException {
        Method isSet = Permissible.class.getMethod("isPermissionSet", String.class);
        Method has = Permissible.class.getMethod("hasPermission", String.class);
        return BukkitBridge.explicitGrant(p, isSet, has, node);
    }

    @AfterEach
    void tearDown() {
        BukkitBridge.reset();
    }

    @Test
    void onlyAnExplicitGrantCounts() throws Exception {
        Permissible op = new Permissible();
        op.op = true;
        assertEquals(Boolean.FALSE, ask(op, Admins.NODE_ADMIN), "Bukkit's op default is not a grant");
        Permissible granted = new Permissible();
        granted.set.put(Admins.NODE_ADMIN, true);
        assertEquals(Boolean.TRUE, ask(granted, Admins.NODE_ADMIN));
        Permissible negated = new Permissible();
        negated.op = true;
        negated.set.put(Admins.NODE_ADMIN, false);
        assertEquals(Boolean.FALSE, ask(negated, Admins.NODE_ADMIN), "an explicit false wins over op");
    }

    @Test
    void anExceptionInsideBukkitLosesOneAnswerOnly() throws Exception {
        Permissible reloading = new Permissible();
        reloading.throwing = true;
        InvocationTargetException inside = null;
        try {
            ask(reloading, Admins.NODE_ADMIN);
        } catch (InvocationTargetException e) {
            inside = e;
        }
        assertNull(BukkitBridge.failed(inside), "this answer is unknown");
        assertEquals(BukkitBridge.State.ABSENT, BukkitBridge.state(), "not switched off (no Bukkit in tests)");
    }

    @Test
    void reflectionThatDoesNotFitSwitchesTheBridgeOff() {
        assertNull(BukkitBridge.failed(new NoSuchMethodException("isPermissionSet")));
        assertEquals(BukkitBridge.State.FAILED, BukkitBridge.state());
        BukkitBridge.reset();
        assertNull(BukkitBridge.failed(new IllegalArgumentException("argument type mismatch")));
        assertEquals(BukkitBridge.State.FAILED, BukkitBridge.state());
    }
}
