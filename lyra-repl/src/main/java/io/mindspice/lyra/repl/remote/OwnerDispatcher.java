package io.mindspice.lyra.repl.remote;

import io.mindspice.lyra.runtime.LyraOwnerController;

import java.util.Objects;

/**
 * Bounded owner-thread seam used by the transport. Implementations must run
 * submitted work only from {@link #poll()} on the owner thread.
 */
public interface OwnerDispatcher {
    boolean isOwnerThread();

    Dispatch dispatch(Runnable operation);

    boolean poll();

    /** Adapts the dependency-free runtime controller without moving work. */
    static OwnerDispatcher runtime(LyraOwnerController controller) {
        Objects.requireNonNull(controller, "controller");
        return new OwnerDispatcher() {
            @Override
            public boolean isOwnerThread() {
                return controller.isOwnerThread();
            }

            @Override
            public Dispatch dispatch(Runnable operation) {
                LyraOwnerController.Dispatch dispatch = controller.dispatch(operation);
                return new Dispatch() {
                    @Override
                    public DispatchState state() {
                        return switch (dispatch.status()) {
                            case PENDING -> DispatchState.PENDING;
                            case RUNNING -> DispatchState.RUNNING;
                            case COMPLETED -> DispatchState.COMPLETED;
                            case FAILED -> DispatchState.FAILED;
                            case CANCELLED -> DispatchState.CANCELLED;
                            case CLOSED -> DispatchState.CLOSED;
                        };
                    }

                    @Override
                    public boolean cancel() {
                        return dispatch.cancel();
                    }
                };
            }

            @Override
            public boolean poll() {
                return controller.poll();
            }
        };
    }

    interface Dispatch {
        DispatchState state();

        boolean cancel();
    }

    enum DispatchState {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED,
        CLOSED
    }
}
