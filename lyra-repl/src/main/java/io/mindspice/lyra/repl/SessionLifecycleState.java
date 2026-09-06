package io.mindspice.lyra.repl;

/** Lifecycle states of a reusable REPL session. */
public enum SessionLifecycleState {
    INITIALIZING,
    OPEN,
    CLOSED,
    FAILED;

    public boolean isTerminal() {
        return this == CLOSED || this == FAILED;
    }

    public boolean acceptsEvaluation() {
        return this == OPEN;
    }
}
