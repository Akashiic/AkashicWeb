package com.akashiic.web.server;

/**
 * Remembers the server's main thread (marked on FMLServerStartingEvent and on every server tick) so code reached
 * from other threads (Netty handlers, RCON, a chat bridge) can tell that it must queue instead of acting.
 */
public final class ServerThread {

    private static volatile Thread main;

    private ServerThread() {}

    public static void mark() {
        main = Thread.currentThread();
    }

    /** True only on the marked main thread; false before the server started. */
    public static boolean isMain() {
        Thread m = main;
        return m != null && m == Thread.currentThread();
    }

    /** True when no main thread is known yet (single-threaded tests, early startup). */
    public static boolean unknown() {
        return main == null;
    }

    public static void clear() {
        main = null;
    }
}
