package io.mindspice.lyra.repl.remote;

import java.io.IOException;
import java.util.Objects;

/** A bounded, non-sensitive protocol or framing failure. */
public final class ProtocolException extends IOException {
    private final Reason reason;

    public ProtocolException(Reason reason, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public ProtocolException(Reason reason, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        END_OF_FRAME,
        FRAME_TOO_LARGE,
        INVALID_FRAME_LENGTH,
        INVALID_UTF8,
        MALFORMED_JSON,
        INVALID_SCHEMA,
        UNSUPPORTED_VERSION,
        HANDSHAKE_REQUIRED,
        AUTHENTICATION_FAILED,
        CONTROLLER_BUSY,
        SESSION_MISMATCH,
        SEQUENCE_REJECTED,
        REQUEST_EXPIRED,
        CONNECTION_LIMIT,
        HANDSHAKE_TIMEOUT
    }
}
