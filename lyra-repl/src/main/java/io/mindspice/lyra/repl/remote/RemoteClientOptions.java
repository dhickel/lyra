package io.mindspice.lyra.repl.remote;

import java.time.Duration;
import java.util.Objects;

/** Immutable bounded client transport configuration. */
public final class RemoteClientOptions {
    public static final RemoteClientOptions DEFAULT = builder().build();

    private final Duration connectTimeout;
    private final Duration handshakeTimeout;
    private final int maxFrameBytes;
    private final int maxOutstandingOperations;

    private RemoteClientOptions(Builder builder) {
        connectTimeout = timeout(builder.connectTimeout, "connectTimeout");
        handshakeTimeout = timeout(builder.handshakeTimeout, "handshakeTimeout");
        maxFrameBytes = bound(builder.maxFrameBytes, RemoteProtocol.MIN_FRAME_BYTES,
                RemoteProtocol.MAX_FRAME_BYTES, "maxFrameBytes");
        maxOutstandingOperations = bound(builder.maxOutstandingOperations, 1, 256,
                "maxOutstandingOperations");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static RemoteClientOptions defaults() {
        return DEFAULT;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration handshakeTimeout() {
        return handshakeTimeout;
    }

    public int maxFrameBytes() {
        return maxFrameBytes;
    }

    public int maxOutstandingOperations() {
        return maxOutstandingOperations;
    }

    public static final class Builder {
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration handshakeTimeout = Duration.ofSeconds(5);
        private int maxFrameBytes = RemoteProtocol.MAX_FRAME_BYTES;
        private int maxOutstandingOperations = 64;

        public Builder connectTimeout(Duration value) {
            connectTimeout = Objects.requireNonNull(value, "connectTimeout");
            return this;
        }

        public Builder handshakeTimeout(Duration value) {
            handshakeTimeout = Objects.requireNonNull(value, "handshakeTimeout");
            return this;
        }

        public Builder maxFrameBytes(int value) {
            maxFrameBytes = value;
            return this;
        }

        public Builder maxOutstandingOperations(int value) {
            maxOutstandingOperations = value;
            return this;
        }

        public RemoteClientOptions build() {
            return new RemoteClientOptions(this);
        }
    }

    private static Duration timeout(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative() || value.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException(field + " must be in (0, 1 minute]");
        }
        // Socket APIs interpret a zero timeout as unlimited. Preserve the
        // bounded timeout contract when callers provide sub-millisecond values.
        long millis = value.toMillis();
        if (value.getNano() % 1_000_000 != 0) {
            millis++;
        }
        return Duration.ofMillis(Math.max(1L, millis));
    }

    private static int bound(int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " must be in " + minimum + ".." + maximum);
        }
        return value;
    }
}
