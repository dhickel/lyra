package io.mindspice.lyra.runtime;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** The complete primitive Lyra type universe. */
public enum PrimitiveType implements LyraType {
    I8("I8", true, true, 8),
    I16("I16", true, true, 16),
    I32("I32", true, true, 32),
    I64("I64", true, true, 64),
    U8("U8", true, false, 8),
    U16("U16", true, false, 16),
    U32("U32", true, false, 32),
    U64("U64", true, false, 64),
    F32("F32", false, false, 32),
    F64("F64", false, false, 64),
    BOOL("Bool", false, false, 0),
    CHAR("Char", false, false, 0),
    STRING("String", false, false, 0),
    UNIT("Unit", false, false, 0);

    public static final PrimitiveType Bool = BOOL;
    public static final PrimitiveType Char = CHAR;
    public static final PrimitiveType String = STRING;
    public static final PrimitiveType Unit = UNIT;

    private final String spelling;
    private final boolean integer;
    private final boolean signed;
    private final int bitWidth;

    PrimitiveType(String spelling, boolean integer, boolean signed, int bitWidth) {
        this.spelling = spelling;
        this.integer = integer;
        this.signed = signed;
        this.bitWidth = bitWidth;
    }

    @Override
    public String canonicalSpelling() {
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
        return integer || isFloating();
    }

    @Override
    public boolean isInteger() {
        return integer;
    }

    @Override
    public boolean isFloating() {
        return this == F32 || this == F64;
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

    public boolean isSignedInteger() {
        return integer && signed;
    }

    public boolean isUnsignedInteger() {
        return integer && !signed;
    }

    public int bitWidth() {
        return bitWidth;
    }

    public Optional<BigInteger> minimumIntegerValue() {
        if (!integer) {
            return Optional.empty();
        }
        if (signed) {
            return Optional.of(BigInteger.ONE.shiftLeft(bitWidth - 1).negate());
        }
        return Optional.of(BigInteger.ZERO);
    }

    public Optional<BigInteger> maximumIntegerValue() {
        if (!integer) {
            return Optional.empty();
        }
        if (signed) {
            return Optional.of(BigInteger.ONE.shiftLeft(bitWidth - 1).subtract(BigInteger.ONE));
        }
        return Optional.of(BigInteger.ONE.shiftLeft(bitWidth).subtract(BigInteger.ONE));
    }

    public static Optional<PrimitiveType> fromSpelling(String spelling) {
        if (spelling == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(value -> value.spelling.equals(spelling)).findFirst();
    }

    @Override
    public String toString() {
        return spelling;
    }
}
