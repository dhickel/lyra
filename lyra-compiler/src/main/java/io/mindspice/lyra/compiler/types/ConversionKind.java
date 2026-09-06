package io.mindspice.lyra.compiler.types;

/** High-level classification of a source-to-target type decision. */
public enum ConversionKind {
    IDENTITY,
    IMPLICIT,
    EXPLICIT,
    INCOMPATIBLE;

    public boolean isAllowed() {
        return this != INCOMPATIBLE;
    }

    public boolean isImplicit() {
        return this == IDENTITY || this == IMPLICIT;
    }

    public boolean isExplicit() {
        return this == EXPLICIT;
    }
}
