package io.mindspice.lyra.runtime;

/** Terminal-aware state machine for a runtime-owned module instance. */
public enum LifecycleState {
    INITIALIZING,
    OPEN,
    CLOSED,
    FAILED
}
