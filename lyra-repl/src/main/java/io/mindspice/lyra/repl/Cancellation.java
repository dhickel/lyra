package io.mindspice.lyra.repl;

import java.util.Objects;

/** A cancellation notice tied to exactly one evaluation identity. */
public record Cancellation(EvaluationId evaluationId, CancellationState state) {
    public Cancellation {
        evaluationId = Objects.requireNonNull(evaluationId, "evaluationId");
        state = Objects.requireNonNull(state, "state");
    }

    public static Cancellation requested(EvaluationId evaluationId) {
        return new Cancellation(evaluationId, CancellationState.REQUESTED);
    }

    public static Cancellation observed(EvaluationId evaluationId) {
        return new Cancellation(evaluationId, CancellationState.OBSERVED);
    }

    /** Returns the terminal observation for a requested cancellation. */
    public Cancellation observe() {
        if (state != CancellationState.REQUESTED) {
            throw new IllegalStateException("cancellation is already observed");
        }
        return new Cancellation(evaluationId, CancellationState.OBSERVED);
    }
}
