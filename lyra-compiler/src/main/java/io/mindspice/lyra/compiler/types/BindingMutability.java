package io.mindspice.lyra.compiler.types;

/** Binding-local mutation permission, kept separate from a value type. */
public enum BindingMutability {
    IMMUTABLE,
    MUTABLE;

    public boolean isMutable() {
        return this == MUTABLE;
    }
}
