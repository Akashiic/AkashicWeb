package com.akashiic.web.client.host;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Everything the supervisor needs to launch the host. Immutable; build with {@link #builder}. */
public final class HostLaunchConfig {

    public static final long DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000;
    /** A run at least this long counts as healthy and resets the backoff to 1 s. */
    public static final long DEFAULT_STABLE_RUN_MILLIS = 60_000;
    /** The host's stdout/stderr file is rotated once it passes this size, before a launch. */
    public static final long STDIO_LOG_ROTATE_BYTES = 4L * 1024 * 1024;
    /** Liveness PING period while connected; also feeds the round trip shown in /web doctor. */
    public static final long DEFAULT_PING_INTERVAL_MILLIS = 10_000;
    /**
     * Ping intervals one PING may stay unanswered before the host counts as stalled (HOST_STALLED) and is replaced: 4
     * x 10 s rides out GC pauses and heavy page loads, yet a hung host no longer freezes screens without a reason.
     */
    public static final int DEFAULT_STALL_INTERVALS = 4;

    public final HostExecutable executable;
    /** Directory for {@code host.log} (the host's own log, {@code --akw-log}) and {@code host-stdio.log}. */
    public final File logDir;
    /** Directory for the PID file of hosts we started. */
    public final File stateDir;

    public final long connectTimeoutMillis;
    public final long stableRunMillis;
    public final long pingIntervalMillis;
    /** Unanswered ping intervals after which the connection is closed as {@link HostFailure#STALLED}. */
    public final int stallIntervals;
    /** Source of the child's environment before sanitising (System.getenv() in the game, a map in tests). */
    public final Map<String, String> parentEnvironment;

    public final String locale;
    /** User agent override for WELCOME, or null to let the host keep its own default. */
    public final String userAgent;

    private HostLaunchConfig(Builder b) {
        this.executable = b.executable;
        this.logDir = b.logDir;
        this.stateDir = b.stateDir;
        this.connectTimeoutMillis = b.connectTimeoutMillis;
        this.stableRunMillis = b.stableRunMillis;
        this.pingIntervalMillis = b.pingIntervalMillis;
        this.stallIntervals = b.stallIntervals;
        this.parentEnvironment = Collections.unmodifiableMap(new LinkedHashMap<>(b.parentEnvironment));
        this.locale = b.locale;
        this.userAgent = b.userAgent;
    }

    public File hostLogFile() {
        return new File(logDir, "host.log");
    }

    /**
     * The child's stdout/stderr. Kept apart from {@code host.log}, which the host writes and may rotate itself, so the
     * two writers never fight over one file.
     */
    public File stdioLogFile() {
        return new File(logDir, "host-stdio.log");
    }

    public File pidFile() {
        return new File(stateDir, "host-pids.txt");
    }

    public static Builder builder(HostExecutable executable, File logDir, File stateDir) {
        return new Builder(executable, logDir, stateDir);
    }

    public static final class Builder {

        private final HostExecutable executable;
        private final File logDir;
        private final File stateDir;
        private long connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MILLIS;
        private long stableRunMillis = DEFAULT_STABLE_RUN_MILLIS;
        private long pingIntervalMillis = DEFAULT_PING_INTERVAL_MILLIS;
        private int stallIntervals = DEFAULT_STALL_INTERVALS;
        private Map<String, String> parentEnvironment = System.getenv();
        private String locale = HostMessages.DEFAULT_LOCALE;
        private String userAgent;

        private Builder(HostExecutable executable, File logDir, File stateDir) {
            if (executable == null || logDir == null || stateDir == null) throw new NullPointerException();
            this.executable = executable;
            this.logDir = logDir;
            this.stateDir = stateDir;
        }

        public Builder connectTimeoutMillis(long v) {
            this.connectTimeoutMillis = Math.max(100, v);
            return this;
        }

        public Builder stableRunMillis(long v) {
            this.stableRunMillis = Math.max(0, v);
            return this;
        }

        /** PING period while connected (at least 50 ms; tests shorten it). */
        public Builder pingIntervalMillis(long v) {
            this.pingIntervalMillis = Math.max(50, v);
            return this;
        }

        /** Unanswered ping intervals before the host counts as stalled (at least 2). */
        public Builder stallIntervals(int v) {
            this.stallIntervals = Math.max(2, v);
            return this;
        }

        public Builder parentEnvironment(Map<String, String> v) {
            this.parentEnvironment = v;
            return this;
        }

        public Builder locale(String v) {
            this.locale = v;
            return this;
        }

        public Builder userAgent(String v) {
            this.userAgent = v;
            return this;
        }

        public HostLaunchConfig build() {
            return new HostLaunchConfig(this);
        }
    }
}
