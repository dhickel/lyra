package io.mindspice.lyra.compiler.types;

/** Individual semantic operations represented by a conversion decision. */
public enum ConversionStep {
    NUMERIC_WIDENING,
    NIL_LIFT,
    MUTABILITY_DROP,
    NUMERIC_EXPLICIT,
    TEXT_EXPLICIT
}
