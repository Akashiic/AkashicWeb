package com.akashiic.web.client.render;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.akashiic.web.AkashicWeb;

/**
 * Angelica 2.2.20 integration by reflection only (ADR-009: compatibility target, never a dependency).
 * <ul>
 * <li>Bounds (ADR-005 with reviewer amendment): register the screen tile class as DYNAMIC in
 * {@code TileEntityRenderBoundsRegistry} during client preInit, before any chunk meshes, because {@code classify()}
 * caches a class forever on first sight. Then verify by reading the registry's name set, without instantiating our
 * tile (classify() on a dummy would also work but runs the tile's constructor).</li>
 * <li>Shadow pass: Angelica's shader pipeline renders tile entities a second time for shadows
 * ({@code ShadowRenderer}); the screen casts no shadow, so the TESR skips that pass.</li>
 * </ul>
 * Every failure is logged with its outcome and the game continues; the pack config's {@code dynamicBoundsTileEntities}
 * entry stays the primary belt (owner action).
 */
public final class AngelicaCompat {

    private static final Logger LOG = LogManager.getLogger(AkashicWeb.NAME);

    static final String REGISTRY = "com.gtnewhorizons.angelica.rendering.TileEntityRenderBoundsRegistry";
    static final String SHADOW_STATE = "net.coderbot.iris.shadows.ShadowRenderingState";

    /** Outcome of the bounds registration, for the log and /web doctor. */
    public enum Registration {
        /** Registered and seen in the registry's name set. */
        VERIFIED,
        /** registerDynamicClass returned but the name set could not be read (Angelica internals changed). */
        UNVERIFIED,
        /** Angelica is not installed (vanilla frustum-tests the live box anyway). */
        ANGELICA_ABSENT,
        /** Angelica without registerDynamicClass (pre-2.x): the screen may be culled; M4's INFINITE box fallback. */
        API_MISSING,
        /** M4 did not assign ModContent.screenTileClass before our preInit. */
        NO_TILE_CLASS,
        FAILED
    }

    private static volatile Registration registration;
    private static MethodHandle shadowCheck;
    private static boolean shadowResolved;

    private AngelicaCompat() {}

    public static Registration registration() {
        return registration;
    }

    public static Registration registerDynamicBounds(Class<?> tileClass) {
        Registration r = doRegister(tileClass);
        registration = r;
        switch (r) {
            case VERIFIED:
                LOG.info("AkashicWeb: Angelica dynamic bounds registered and verified for {}", tileClass.getName());
                break;
            case ANGELICA_ABSENT:
                LOG.info("AkashicWeb: Angelica not present; vanilla culling uses the live render box");
                break;
            case NO_TILE_CLASS:
                LOG.warn("AkashicWeb: screen tile class not assigned yet; Angelica bounds not registered");
                break;
            default:
                LOG.warn(
                    "AkashicWeb: Angelica dynamic bounds {} for {}; add it to dynamicBoundsTileEntities in Angelica's"
                        + " config",
                    r,
                    tileClass == null ? "?" : tileClass.getName());
        }
        return r;
    }

    private static Registration doRegister(Class<?> tileClass) {
        if (tileClass == null) return Registration.NO_TILE_CLASS;
        Class<?> registry;
        try {
            registry = Class.forName(REGISTRY);
        } catch (ClassNotFoundException e) {
            return Registration.ANGELICA_ABSENT;
        }
        String name = tileClass.getName();
        try {
            Method register = registry.getMethod("registerDynamicClass", String.class);
            register.invoke(null, name);
        } catch (NoSuchMethodException e) {
            return Registration.API_MISSING;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOG.error("AkashicWeb: Angelica registerDynamicClass failed", e);
            return Registration.FAILED;
        }
        try {
            Field f = registry.getDeclaredField("dynamicClassNames");
            f.setAccessible(true);
            Object set = f.get(null);
            if (set instanceof Set) return ((Set<?>) set).contains(name) ? Registration.VERIFIED : Registration.FAILED;
            return Registration.UNVERIFIED;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Registration.UNVERIFIED;
        }
    }

    /** True while Angelica renders the shadow map. Cheap: one resolved static call per TESR invocation. */
    public static boolean isShadowPass() {
        if (!shadowResolved) resolveShadowCheck();
        MethodHandle mh = shadowCheck;
        if (mh == null) return false;
        try {
            return (boolean) mh.invokeExact();
        } catch (Throwable t) {
            // Documented boundary: never let a compat probe break rendering; stop asking after the first failure.
            shadowCheck = null;
            LOG.warn("AkashicWeb: Angelica shadow-pass check failed; disabled", t);
            return false;
        }
    }

    private static void resolveShadowCheck() {
        shadowResolved = true;
        try {
            Class<?> c = Class.forName(SHADOW_STATE);
            shadowCheck = MethodHandles.publicLookup()
                .findStatic(c, "areShadowsCurrentlyBeingRendered", MethodType.methodType(boolean.class));
        } catch (ClassNotFoundException e) {
            shadowCheck = null; // no Angelica shaders: there is no shadow pass to skip
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            shadowCheck = null;
            LOG.warn("AkashicWeb: Angelica shadow state not found; screens will draw in the shadow pass too", e);
        }
    }
}
