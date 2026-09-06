package io.mindspice.lyra.repl;

/** Terminal outcomes of one evaluation admission. */
public enum EvaluationStatus {
    SUCCESS,
    COMPILATION_FAILURE,
    RUNTIME_FAILURE,
    CANCELLED,
    BUSY,
    CLOSED;

    public boolean isFailure() {
        return this == COMPILATION_FAILURE || this == RUNTIME_FAILURE || this == CANCELLED;
    }

    /** Every published evaluation result is terminal; pending work is not a result. */
    public boolean isTerminal() {
        return true;
    }
}
