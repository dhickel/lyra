package io.mindspice.lyra.runtime;

/** Signature positions with distinct qualifier legality. */
public enum TypePosition {
    BINDING,
    PARAMETER,
    RETURN,
    NESTED_VALUE;

    public boolean permitsMutableQualifier() {
        return this == BINDING || this == PARAMETER;
    }
}
