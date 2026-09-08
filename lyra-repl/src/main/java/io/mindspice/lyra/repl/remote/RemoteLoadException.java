package io.mindspice.lyra.repl.remote;

import java.util.Objects;

/**
 * A pre-effect LOAD failure: the server-side file could not be captured as
 * a bounded UTF-8 source. The request is rejected before any Lyra effect
 * runs and the detail is returned to the controller explicitly.
 */
public final class RemoteLoadException extends RuntimeException {
    public RemoteLoadException(String detail) {
        super(Objects.requireNonNull(detail, "detail"));
    }
}
