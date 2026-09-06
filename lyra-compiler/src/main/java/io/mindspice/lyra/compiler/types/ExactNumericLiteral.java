package io.mindspice.lyra.compiler.types;

import io.mindspice.lyra.compiler.lex.LiteralValue;
import io.mindspice.lyra.compiler.lex.NumericSuffix;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/**
 * Parser-independent exact numeric literal data retained for contextual
 * typing.  A sign is represented in the value when supplied by a semantic
 * caller; lexer values can be imported directly and are non-negative integer
 * magnitudes because Lyra signs are operators.
 */
public final class ExactNumericLiteral {
    public enum Kind {
        INTEGER,
        DECIMAL
    }

    private final Kind kind;
    private final BigInteger integerValue;
    private final BigDecimal decimalValue;
    private final Optional<PrimitiveType> forcedType;
    private final boolean negativeZero;
    private final String canonical;

    private ExactNumericLiteral(
            Kind kind,
            BigInteger integerValue,
            BigDecimal decimalValue,
            Optional<PrimitiveType> forcedType,
            boolean negativeZero) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.integerValue = integerValue;
        this.decimalValue = decimalValue;
        this.forcedType = Objects.requireNonNull(forcedType, "forcedType");
        this.negativeZero = kind == Kind.DECIMAL
                && decimalValue != null
                && decimalValue.signum() == 0
                && negativeZero;
        if (kind == Kind.INTEGER) {
            if (integerValue == null || decimalValue != null) {
                throw new IllegalArgumentException("integer literal data is inconsistent");
            }
            if (forcedType.isPresent() && !forcedType.orElseThrow().isInteger()) {
                throw new IllegalArgumentException("integer literals require an integer forced type");
            }
            this.canonical = integerValue + forcedSuffix(forcedType);
        } else {
            if (decimalValue == null || integerValue != null) {
                throw new IllegalArgumentException("decimal literal data is inconsistent");
            }
            if (forcedType.isPresent() && !forcedType.orElseThrow().isFloating()) {
                throw new IllegalArgumentException("decimal literals require a floating forced type");
            }
            this.canonical = (this.negativeZero ? "-" : "")
                    + decimalValue.toString() + forcedSuffix(forcedType);
        }
    }

    public static ExactNumericLiteral integer(BigInteger value) {
        return integer(value, Optional.empty());
    }

    public static ExactNumericLiteral integer(BigInteger value, PrimitiveType forcedType) {
        return integer(value, Optional.of(Objects.requireNonNull(forcedType, "forcedType")));
    }

    public static ExactNumericLiteral integer(BigInteger value, NumericSuffix suffix) {
        return integer(value, forcedPrimitive(suffix));
    }

    public static ExactNumericLiteral decimal(BigDecimal value) {
        return decimal(value, Optional.empty());
    }

    public static ExactNumericLiteral decimal(BigDecimal value, PrimitiveType forcedType) {
        return decimal(value, Optional.of(Objects.requireNonNull(forcedType, "forcedType")));
    }

    public static ExactNumericLiteral decimal(BigDecimal value, NumericSuffix suffix) {
        return decimal(value, forcedPrimitive(suffix));
    }

    public static ExactNumericLiteral from(LiteralValue value) {
        Objects.requireNonNull(value, "value");
        if (value instanceof LiteralValue.IntegerLiteral integer) {
            return integer(integer.exactValue(), integer.suffix());
        }
        if (value instanceof LiteralValue.FloatLiteral decimal) {
            return decimal(decimal.exactValue(), decimal.suffix());
        }
        throw new IllegalArgumentException("literal is not numeric: " + value);
    }

    public Kind kind() {
        return kind;
    }

    public boolean isInteger() {
        return kind == Kind.INTEGER;
    }

    public boolean isDecimal() {
        return kind == Kind.DECIMAL;
    }

    public boolean isFloating() {
        return isDecimal();
    }

    public BigInteger integerValue() {
        if (!isInteger()) {
            throw new IllegalStateException("decimal literal has no integer value");
        }
        return integerValue;
    }

    public Optional<BigInteger> optionalIntegerValue() {
        return Optional.ofNullable(integerValue);
    }

    public BigDecimal decimalValue() {
        if (!isDecimal()) {
            throw new IllegalStateException("integer literal has no decimal value");
        }
        return decimalValue;
    }

    public Optional<BigDecimal> optionalDecimalValue() {
        return Optional.ofNullable(decimalValue);
    }

    public Optional<PrimitiveType> forcedType() {
        return forcedType;
    }

    public boolean isContextual() {
        return forcedType.isEmpty();
    }

    /** True only for a decimal zero produced with a semantic negative sign. */
    public boolean isNegativeZero() {
        return negativeZero;
    }

    public String canonicalSpelling() {
        return canonical;
    }

    public String canonical() {
        return canonical;
    }

    /** Applies the semantic unary minus without losing exactness. */
    public ExactNumericLiteral negated() {
        return isInteger()
                ? integer(integerValue.negate(), forcedType)
                : decimal(decimalValue.negate(), forcedType,
                        decimalValue.signum() == 0 && !negativeZero);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ExactNumericLiteral literal)
                || kind != literal.kind
                || !forcedType.equals(literal.forcedType)
                || negativeZero != literal.negativeZero) {
            return false;
        }
        return isInteger()
                ? integerValue.equals(literal.integerValue)
                : decimalValue.compareTo(literal.decimalValue) == 0;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(kind, forcedType, negativeZero);
        return 31 * result + (isInteger()
                ? integerValue.hashCode()
                : decimalValue.stripTrailingZeros().hashCode());
    }

    @Override
    public String toString() {
        return canonical;
    }

    private static ExactNumericLiteral integer(
            BigInteger value, Optional<PrimitiveType> forcedType) {
        return new ExactNumericLiteral(
                Kind.INTEGER,
                Objects.requireNonNull(value, "value"),
                null,
                forcedType,
                false);
    }

    private static ExactNumericLiteral decimal(
            BigDecimal value, Optional<PrimitiveType> forcedType) {
        return decimal(value, forcedType, false);
    }

    private static ExactNumericLiteral decimal(
            BigDecimal value, Optional<PrimitiveType> forcedType, boolean negativeZero) {
        return new ExactNumericLiteral(
                Kind.DECIMAL,
                null,
                Objects.requireNonNull(value, "value"),
                forcedType,
                negativeZero);
    }

    private static Optional<PrimitiveType> forcedPrimitive(NumericSuffix suffix) {
        Objects.requireNonNull(suffix, "suffix");
        return suffix == NumericSuffix.NONE
                ? Optional.empty()
                : Optional.of(PrimitiveType.fromSpelling(suffix.spelling()).orElseThrow(
                        () -> new IllegalArgumentException("unknown numeric suffix: " + suffix)));
    }

    private static String forcedSuffix(Optional<PrimitiveType> forcedType) {
        return forcedType.map(type -> type.canonicalSpelling()).orElse("");
    }
}
