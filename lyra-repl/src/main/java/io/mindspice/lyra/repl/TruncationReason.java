package io.mindspice.lyra.repl;

/** Why a snapshot stopped expanding a value. */
public enum TruncationReason {
    DEPTH,
    AGGREGATE_ELEMENTS,
    RENDERED_OUTPUT
}
