package io.mindspice.lyra.repl;

/** Immutable cooperative-cancellation observation state. */
public enum CancellationState {
    REQUESTED,
    OBSERVED;

    public boolean isObserved() {
        return this == OBSERVED;
    }
}
