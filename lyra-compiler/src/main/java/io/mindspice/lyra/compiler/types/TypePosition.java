package io.mindspice.lyra.compiler.types;

/** Syntactic contract positions whose qualifier legality differs. */
public enum TypePosition {
    BINDING,
    PARAMETER,
    RETURN,
    NESTED_VALUE;

    public boolean permitsMutableQualifier() {
        return this == BINDING || this == PARAMETER;
    }
}
