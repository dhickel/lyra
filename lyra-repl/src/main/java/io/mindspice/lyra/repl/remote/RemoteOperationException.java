package io.mindspice.lyra.repl.remote;

import java.io.IOException;
import java.util.Objects;

/** A server-declared operation rejection without exposing server internals. */
public final class RemoteOperationException extends IOException {
    private final ProtocolMessage.ErrorCode code;

    public RemoteOperationException(ProtocolMessage.ErrorCode code, String detail) {
        super(Objects.requireNonNull(detail, "detail"));
        this.code = Objects.requireNonNull(code, "code");
    }

    public ProtocolMessage.ErrorCode code() {
        return code;
    }
}
