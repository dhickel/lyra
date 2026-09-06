package io.mindspice.lyra.compiler.types;

import io.mindspice.lyra.compiler.lex.LiteralValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/** Exact/default/contextual typing hooks for numeric literals. */
public final class LiteralTyping {
    private LiteralTyping() {
    }

    /** Infers a forced type or the language default (I64/F64) without an expected context. */
    public static Optional<PrimitiveType> infer(ExactNumericLiteral literal) {
        Objects.requireNonNull(literal, "literal");
        PrimitiveType type = literal.forcedType().orElse(
                literal.isInteger() ? PrimitiveType.I64 : PrimitiveType.F64);
        return representableAs(literal, type) ? Optional.of(type) : Optional.empty();
    }

    public static Optional<PrimitiveType> infer(LiteralValue literal) {
        return infer(ExactNumericLiteral.from(literal));
    }

    /**
     * Performs contextual typing.  An unsuffixed literal adopts an expected
     * numeric primitive when its exact value is representable.  A suffix fixes
     * the literal's primitive type and is never silently replaced by context.
     */
    public static Optional<PrimitiveType> infer(
            ExactNumericLiteral literal, LyraType expectedType) {
        Objects.requireNonNull(literal, "literal");
        Objects.requireNonNull(expectedType, "expectedType");
        LyraType expectedBase = expectedType.withoutQualifiers();
        if (!(expectedBase instanceof PrimitiveType expectedPrimitive)
                || !expectedPrimitive.isNumeric()) {
            return Optional.empty();
        }

        if (literal.forcedType().isPresent()) {
            PrimitiveType forced = literal.forcedType().orElseThrow();
            return representableAs(literal, forced)
                    && TypeRules.canImplicitlyConvert(forced, expectedBase)
                    ? Optional.of(forced)
                    : Optional.empty();
        }
        return representableAs(literal, expectedPrimitive)
                ? Optional.of(expectedPrimitive)
                : Optional.empty();
    }

    public static Optional<PrimitiveType> infer(
            LiteralValue literal, LyraType expectedType) {
        return infer(ExactNumericLiteral.from(literal), expectedType);
    }

    public static Optional<PrimitiveType> contextualType(
            ExactNumericLiteral literal, LyraType expectedType) {
        return infer(literal, expectedType);
    }

    public static boolean representableAs(
            ExactNumericLiteral literal, PrimitiveType target) {
        Objects.requireNonNull(literal, "literal");
        Objects.requireNonNull(target, "target");
        if (!target.isNumeric()) {
            return false;
        }
        if (literal.isInteger()) {
            BigInteger value = literal.integerValue();
            if (target.isInteger()) {
                return target.numericDomain().orElseThrow().contains(value);
            }
            return integerExactlyRepresentableAsFloat(value, target);
        }

        if (target.isInteger()) {
            return false;
        }
        return decimalRepresentableAsFloat(literal.decimalValue(), target);
    }

    public static boolean representableAs(LiteralValue literal, PrimitiveType target) {
        return representableAs(ExactNumericLiteral.from(literal), target);
    }

    /** Alias with a name useful to semantic callers performing range checks. */
    public static boolean fits(ExactNumericLiteral literal, PrimitiveType target) {
        return representableAs(literal, target);
    }

    private static boolean integerExactlyRepresentableAsFloat(
            BigInteger value, PrimitiveType target) {
        if (target == PrimitiveType.F32) {
            float rounded = value.floatValue();
            if (!Float.isFinite(rounded)) {
                return false;
            }
            return new BigDecimal(rounded).toBigIntegerExact().equals(value);
        }
        double rounded = value.doubleValue();
        if (!Double.isFinite(rounded)) {
            return false;
        }
        return new BigDecimal(rounded).toBigIntegerExact().equals(value);
    }

    private static boolean decimalRepresentableAsFloat(
            BigDecimal value, PrimitiveType target) {
        if (value.signum() == 0) {
            return true;
        }
        if (target == PrimitiveType.F32) {
            float rounded = value.floatValue();
            return Float.isFinite(rounded) && rounded != 0.0f;
        }
        double rounded = value.doubleValue();
        return Double.isFinite(rounded) && rounded != 0.0d;
    }
}
