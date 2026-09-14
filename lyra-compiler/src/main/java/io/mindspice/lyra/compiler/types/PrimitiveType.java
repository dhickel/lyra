package io.mindspice.lyra.compiler.types;

import java.util.Arrays;
import java.util.Optional;

/** The complete set of primitive Lyra types. */
public enum PrimitiveType implements LyraType {
    I8("I8", new NumericDomain(NumericKind.SIGNED_INTEGER, 8)),
    I16("I16", new NumericDomain(NumericKind.SIGNED_INTEGER, 16)),
    I32("I32", new NumericDomain(NumericKind.SIGNED_INTEGER, 32)),
    I64("I64", new NumericDomain(NumericKind.SIGNED_INTEGER, 64)),
    U8("U8", new NumericDomain(NumericKind.UNSIGNED_INTEGER, 8)),
    U16("U16", new NumericDomain(NumericKind.UNSIGNED_INTEGER, 16)),
    U32("U32", new NumericDomain(NumericKind.UNSIGNED_INTEGER, 32)),
    U64("U64", new NumericDomain(NumericKind.UNSIGNED_INTEGER, 64)),
    F32("F32", new NumericDomain(NumericKind.FLOAT, 32)),
    F64("F64", new NumericDomain(NumericKind.FLOAT, 64)),
    BOOL("Bool", null),
    CHAR("Char", null),
    STRING("String", null),
    UNIT("Unit", null);

    private final String spelling;
    private final NumericDomain numericDomain;

    PrimitiveType(String spelling, NumericDomain numericDomain) {
        this.spelling = spelling;
        this.numericDomain = numericDomain;
    }

    @Override
    public String canonicalSpelling() {
        return spelling;
    }

    @Override
    public String canonical() {
        return spelling;
    }

    @Override
    public String spelling() {
        return spelling;
    }

    @Override
    public boolean isPrimitive() {
        return true;
    }

    @Override
    public boolean isComposite() {
        return false;
    }

    @Override
    public boolean isNumeric() {
        return numericDomain != null;
    }

    @Override
    public boolean isInteger() {
        return numericDomain != null && numericDomain.isInteger();
    }

    @Override
    public boolean isFloating() {
        return numericDomain != null && numericDomain.isFloating();
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
        java.util.Objects.requireNonNull(qualifier, "qualifier");
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
        java.util.Objects.requireNonNull(qualifiers, "qualifiers");
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

    public Optional<NumericDomain> numericDomain() {
        return Optional.ofNullable(numericDomain);
    }

    public boolean isSignedInteger() {
        return numericDomain != null && numericDomain.isSignedInteger();
    }

    public boolean isUnsignedInteger() {
        return numericDomain != null && numericDomain.isUnsignedInteger();
    }

    public static Optional<PrimitiveType> fromSpelling(String spelling) {
        if (spelling == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(type -> type.spelling.equals(spelling))
                .findFirst();
    }

    @Override
    public String toString() {
        return spelling;
    }
}
