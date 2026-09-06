package io.mindspice.lyra.repl.remote;

/** Structured adapter signal used when an attached live session is unavailable. */
public final class RemoteSessionUnavailableException extends RuntimeException {
    public RemoteSessionUnavailableException(String message) {
        super(message);
    }
}
