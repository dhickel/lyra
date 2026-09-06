package io.mindspice.lyra.compiler.lex;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/**
 * Exact decoded values produced by the lexer.  Integer values are never
 * routed through floating point, and decimal values retain their exact
 * {@link BigDecimal} representation in addition to the token's raw lexeme.
 */
public sealed interface LiteralValue extends TokenValue
        permits LiteralValue.BooleanLiteral,
                LiteralValue.NilLiteral,
                LiteralValue.StringLiteral,
                LiteralValue.CharLiteral,
                LiteralValue.IntegerLiteral,
                LiteralValue.FloatLiteral {
    record BooleanLiteral(boolean value) implements LiteralValue {
    }

    record NilLiteral() implements LiteralValue {
        public static final NilLiteral INSTANCE = new NilLiteral();
    }

    record StringLiteral(String value) implements LiteralValue {
        public StringLiteral {
            Objects.requireNonNull(value, "value");
        }
    }

    record CharLiteral(char value) implements LiteralValue {
    }

    record IntegerLiteral(BigInteger value, NumericSuffix suffix) implements LiteralValue {
        public IntegerLiteral {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(suffix, "suffix");
            if (value.signum() < 0) {
                throw new IllegalArgumentException("lexer integer values do not include a leading sign");
            }
            if (!suffix.isInteger() && suffix != NumericSuffix.NONE) {
                throw new IllegalArgumentException("integer literal has a floating suffix: " + suffix);
            }
        }

        public BigInteger exactValue() {
            return value;
        }
    }

    record FloatLiteral(BigDecimal value, NumericSuffix suffix) implements LiteralValue {
        public FloatLiteral {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(suffix, "suffix");
            if (!suffix.isFloating() && suffix != NumericSuffix.NONE) {
                throw new IllegalArgumentException("floating literal has an integer suffix: " + suffix);
            }
        }

        public BigDecimal exactValue() {
            return value;
        }
    }
}
