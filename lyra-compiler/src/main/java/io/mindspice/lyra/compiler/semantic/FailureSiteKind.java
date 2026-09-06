package io.mindspice.lyra.compiler.semantic;

/** Runtime-failure category recorded by semantic analysis for later lowering. */
public enum FailureSiteKind {
    ARITHMETIC,
    DIVISION,
    CONVERSION,
    BOUNDS
}
