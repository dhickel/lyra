package io.mindspice.lyra.compiler.lex;

import java.util.Arrays;
import java.util.Optional;

/** Uppercase primitive suffixes allowed on exact numeric literals. */
public enum NumericSuffix {
    NONE("", NumericKind.NONE, 0),
    I8("I8", NumericKind.SIGNED_INTEGER, 8),
    I16("I16", NumericKind.SIGNED_INTEGER, 16),
    I32("I32", NumericKind.SIGNED_INTEGER, 32),
    I64("I64", NumericKind.SIGNED_INTEGER, 64),
    U8("U8", NumericKind.UNSIGNED_INTEGER, 8),
    U16("U16", NumericKind.UNSIGNED_INTEGER, 16),
    U32("U32", NumericKind.UNSIGNED_INTEGER, 32),
    U64("U64", NumericKind.UNSIGNED_INTEGER, 64),
    F32("F32", NumericKind.FLOAT, 32),
    F64("F64", NumericKind.FLOAT, 64);

    private final String spelling;
    private final NumericKind kind;
    private final int bitWidth;

    NumericSuffix(String spelling, NumericKind kind, int bitWidth) {
        this.spelling = spelling;
        this.kind = kind;
        this.bitWidth = bitWidth;
    }

    public String spelling() {
        return spelling;
    }

    public boolean isInteger() {
        return kind == NumericKind.SIGNED_INTEGER || kind == NumericKind.UNSIGNED_INTEGER;
    }

    public boolean isSignedInteger() {
        return kind == NumericKind.SIGNED_INTEGER;
    }

    public boolean isUnsignedInteger() {
        return kind == NumericKind.UNSIGNED_INTEGER;
    }

    public boolean isFloating() {
        return kind == NumericKind.FLOAT;
    }

    public int bitWidth() {
        return bitWidth;
    }

    public static Optional<NumericSuffix> fromSpelling(String spelling) {
        if (spelling == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(suffix -> !suffix.spelling.isEmpty() && suffix.spelling.equals(spelling))
                .findFirst();
    }

    private enum NumericKind {
        NONE,
        SIGNED_INTEGER,
        UNSIGNED_INTEGER,
        FLOAT
    }
}
