package io.mindspice.lyra.repl.remote;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Cross-thread cancellation token for one admitted remote evaluation. */
public final class RemoteCancellation {
    private final AtomicBoolean requested = new AtomicBoolean();
    private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);
    private final Object admission = new Object();
    private boolean admitted;

    public boolean request() {
        synchronized (admission) {
            if (state.get() != State.ACTIVE) {
                return false;
            }
            requested.set(true);
            return true;
        }
    }

    /** Atomically admits the owner operation unless cancellation won first. */
    boolean admit() {
        synchronized (admission) {
            if (state.get() != State.ACTIVE || requested.get() || admitted) {
                return false;
            }
            admitted = true;
            return true;
        }
    }

    public boolean isRequested() {
        return requested.get();
    }

    public State state() {
        return state.get();
    }

    /** Owner-side observation of the request. */
    public boolean observe() {
        if (!requested.get()) {
            return false;
        }
        state.compareAndSet(State.ACTIVE, State.OBSERVED);
        return true;
    }

    /** Marks the token terminal after the owner operation has ended. */
    public void complete() {
        synchronized (admission) {
            state.set(requested.get() ? State.OBSERVED : State.COMPLETED);
        }
    }

    public enum State {
        ACTIVE,
        OBSERVED,
        COMPLETED
    }
}
