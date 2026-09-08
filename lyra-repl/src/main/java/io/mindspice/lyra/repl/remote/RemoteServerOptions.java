package io.mindspice.lyra.repl.remote;

import java.time.Duration;
import java.util.Objects;

/** Immutable bounded configuration for a credential-free loopback REPL server. */
public final class RemoteServerOptions {
    public static final RemoteServerOptions DEFAULT = builder().build();

    private final int port;
    private final Duration handshakeTimeout;
    private final int maxFrameBytes;
    private final int maxConnections;
    private final int maxRetainedResults;
    private final int maxOutboundMessages;
    private final int maxOutboundBytes;
    private final int maxDiagnostics;

    private RemoteServerOptions(Builder builder) {
        port = port(builder.port);
        handshakeTimeout = timeout(builder.handshakeTimeout, "handshakeTimeout");
        maxFrameBytes = bound(builder.maxFrameBytes, RemoteProtocol.MIN_FRAME_BYTES,
                RemoteProtocol.MAX_FRAME_BYTES, "maxFrameBytes");
        maxConnections = bound(builder.maxConnections, 1, 128, "maxConnections");
        maxRetainedResults = bound(builder.maxRetainedResults, 1,
                RemoteProtocol.MAX_RETAINED_RESULTS, "maxRetainedResults");
        maxOutboundMessages = bound(builder.maxOutboundMessages, 1, 256, "maxOutboundMessages");
        maxOutboundBytes = bound(builder.maxOutboundBytes, 1024,
                RemoteProtocol.MAX_FRAME_BYTES * 2, "maxOutboundBytes");
        maxDiagnostics = bound(builder.maxDiagnostics, 1, RemoteProtocol.MAX_DIAGNOSTICS,
                "maxDiagnostics");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static RemoteServerOptions defaults() {
        return DEFAULT;
    }

    public int port() {
        return port;
    }

    public Duration handshakeTimeout() {
        return handshakeTimeout;
    }

    public int maxFrameBytes() {
        return maxFrameBytes;
    }

    public int maxConnections() {
        return maxConnections;
    }

    public int maxRetainedResults() {
        return maxRetainedResults;
    }

    public int maxOutboundMessages() {
        return maxOutboundMessages;
    }

    public int maxOutboundBytes() {
        return maxOutboundBytes;
    }

    public int maxDiagnostics() {
        return maxDiagnostics;
    }

    public static final class Builder {
        private int port;
        private Duration handshakeTimeout = Duration.ofSeconds(5);
        private int maxFrameBytes = RemoteProtocol.MAX_FRAME_BYTES;
        private int maxConnections = 16;
        private int maxRetainedResults = 64;
        private int maxOutboundMessages = 64;
        private int maxOutboundBytes = RemoteProtocol.MAX_FRAME_BYTES;
        private int maxDiagnostics = RemoteProtocol.MAX_DIAGNOSTICS;

        public Builder port(int value) {
            port = value;
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

        public Builder maxConnections(int value) {
            maxConnections = value;
            return this;
        }

        public Builder maxRetainedResults(int value) {
            maxRetainedResults = value;
            return this;
        }

        public Builder maxOutboundMessages(int value) {
            maxOutboundMessages = value;
            return this;
        }

        public Builder maxOutboundBytes(int value) {
            maxOutboundBytes = value;
            return this;
        }

        public Builder maxDiagnostics(int value) {
            maxDiagnostics = value;
            return this;
        }

        public RemoteServerOptions build() {
            return new RemoteServerOptions(this);
        }
    }

    private static int port(int value) {
        if (value < 0 || value > 65535) {
            throw new IllegalArgumentException("port must be in 0..65535");
        }
        return value;
    }

    private static Duration timeout(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative() || value.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException(field + " must be in (0, 1 minute]");
        }
        return value;
    }

    private static int bound(int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " must be in " + minimum + ".." + maximum);
        }
        return value;
    }
}
