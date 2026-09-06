package io.mindspice.lyra.runtime;

/**
 * Immutable observation state for a cooperative cancellation token.
 *
 * <p>{@link LyraCancellation#isClosed()} is reported separately because
 * closing a controller retires its token without erasing the terminal
 * cancellation observation needed by its owner.</p>
 */
public enum LyraCancellationState {
    /** No cancellation request has been published. */
    ACTIVE,
    /** A cancellation request has been published but not observed at a safe point. */
    REQUESTED,
    /** A safe point has observed the request. */
    OBSERVED;

    public boolean isCancellationRequested() {
        return this != ACTIVE;
    }

    public boolean isObserved() {
        return this == OBSERVED;
    }
}
