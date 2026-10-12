package com.akashiic.web.client.host;

/**
 * Time source for the supervisor's backoff and crash-loop window, injectable so tests run the 1, 2, 4 ... 60 s schedule
 * without waiting. Socket and process timeouts deliberately use real time: they guard real I/O.
 */
public interface HostClock {

    long nowMillis();

    void sleep(long millis) throws InterruptedException;

    HostClock SYSTEM = new HostClock() {

        @Override
        public long nowMillis() {
            return System.nanoTime() / 1_000_000L;
        }

        @Override
        public void sleep(long millis) throws InterruptedException {
            Thread.sleep(millis);
        }
    };
}
