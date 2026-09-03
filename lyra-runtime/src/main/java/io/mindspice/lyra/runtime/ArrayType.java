package io.mindspice.lyra.runtime;

import java.util.Objects;

/** An invariant homogeneous Lyra array type. */
public final class ArrayType implements LyraType {
    private final LyraType elementType;
    private final String canonical;

    public ArrayType(LyraType elementType) {
        this.elementType = Objects.requireNonNull(elementType, "elementType");
        rejectMutableNestedContract(elementType);
        this.canonical = "Array<" + elementType.canonicalSpelling() + ">";
    }

    public static ArrayType of(LyraType elementType) {
        return new ArrayType(elementType);
    }

    public LyraType elementType() {
        return elementType;
    }

    public LyraType memberType() {
        return elementType;
    }

    @Override
    public String canonicalSpelling() {
        return canonical;
    }

    @Override
    public boolean isPrimitive() {
        return false;
    }

    @Override
    public boolean isComposite() {
        return true;
    }

    @Override
    public boolean isNumeric() {
        return false;
    }

    @Override
    public boolean isInteger() {
        return false;
    }

    @Override
    public boolean isFloating() {
        return false;
    }

    @Override
    public boolean isNilable() {
        return false;
    }

    @Override
    public boolean isMutable() {
        return false;
    }

    @Override
    public boolean hasQualifier(TypeQualifier qualifier) {
        Objects.requireNonNull(qualifier, "qualifier");
        return false;
    }

    @Override
    public LyraType withoutQualifiers() {
        return this;
    }

    @Override
    public LyraType baseType() {
        return this;
    }

    @Override
    public LyraType withQualifier(TypeQualifier qualifier) {
        return QualifiedType.of(this, qualifier);
    }

    @Override
    public LyraType withQualifiers(java.util.Set<TypeQualifier> qualifiers) {
        Objects.requireNonNull(qualifiers, "qualifiers");
        return qualifiers.isEmpty() ? this : QualifiedType.of(this, qualifiers);
    }

    @Override
    public LyraType nilable() {
        return QualifiedType.nilable(this);
    }

    @Override
    public LyraType mutable() {
        return QualifiedType.mutable(this);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ArrayType array && elementType.equals(array.elementType);
    }

    @Override
    public int hashCode() {
        return 31 * ArrayType.class.hashCode() + elementType.hashCode();
    }

    @Override
    public String toString() {
        return canonical;
    }

    static void rejectMutableNestedContract(LyraType type) {
        if (type instanceof QualifiedType qualified && qualified.hasQualifier(TypeQualifier.MUT)) {
            throw new IllegalArgumentException("@mut is not legal in a nested value contract");
        }
    }
}
