package io.mindspice.lyra.compiler.types;

import java.util.Objects;

/** A declaration contract; binding mutability is intentionally not a type qualifier. */
public record BindingContract(LyraType valueType, BindingMutability mutability) {
    public BindingContract {
        Objects.requireNonNull(valueType, "valueType");
        Objects.requireNonNull(mutability, "mutability");
        if (valueType.hasQualifier(TypeQualifier.MUT)) {
            throw new IllegalArgumentException(
                    "binding mutability is stored separately from the value type");
        }
        if (valueType instanceof QualifiedType qualified) {
            qualified.validateFor(TypePosition.BINDING);
        }
    }

    public static BindingContract immutable(LyraType valueType) {
        return new BindingContract(valueType, BindingMutability.IMMUTABLE);
    }

    public static BindingContract mutable(LyraType valueType) {
        return new BindingContract(valueType, BindingMutability.MUTABLE);
    }

    public LyraType type() {
        return valueType;
    }

    public boolean isMutable() {
        return mutability.isMutable();
    }

    /** Canonical declaration-contract spelling, including binding-local {@code @mut}. */
    public String canonicalSpelling() {
        return (isMutable() ? TypeQualifier.MUT.spelling() : "")
                + valueType.canonicalSpelling();
    }

    public String canonical() {
        return canonicalSpelling();
    }

    @Override
    public String toString() {
        return canonicalSpelling();
    }
}
