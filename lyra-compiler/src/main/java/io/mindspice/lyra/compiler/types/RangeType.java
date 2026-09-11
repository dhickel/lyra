package io.mindspice.lyra.compiler.types;

import java.util.Objects;

/** An invariant immutable signed-integer range type. */
public final class RangeType implements LyraType {
    private final LyraType elementType;
    private final String canonical;

    public RangeType(LyraType elementType) {
        this.elementType = Objects.requireNonNull(elementType, "elementType");
        if (!(elementType instanceof PrimitiveType primitive) || !primitive.isInteger()
                || primitive.name().startsWith("U")) {
            throw new IllegalArgumentException("Range requires an unqualified signed integer element type");
        }
        this.canonical = "Range<" + elementType.canonicalSpelling() + ">";
    }

    public static RangeType of(LyraType elementType) {
        return new RangeType(elementType);
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
    public String canonical() {
        return canonical;
    }

    @Override
    public String spelling() {
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
    public boolean isNullable() {
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
        return this == other
                || other instanceof RangeType array && elementType.equals(array.elementType);
    }

    @Override
    public int hashCode() {
        return 31 * RangeType.class.hashCode() + elementType.hashCode();
    }

    @Override
    public String toString() {
        return canonical;
    }

}
