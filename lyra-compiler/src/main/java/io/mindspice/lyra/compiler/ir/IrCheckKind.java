package io.mindspice.lyra.compiler.ir;

/** Runtime checks/failure sites retained in the initial typed IR. */
public enum IrCheckKind {
    /** Checked integer arithmetic and finite floating-point arithmetic. */
    ARITHMETIC,
    /** Checked division/remainder zero and the integer-division result rule. */
    DIVISION,
    EXPLICIT_CONVERSION,
    BOUNDS
}
