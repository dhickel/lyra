package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.types.ExactNumericLiteral;

import java.util.Objects;

/** Exact JVM-independent literal data retained by the typed semantic graph. */
public sealed interface TypedLiteralValue
        permits TypedLiteralValue.BooleanValue,
                TypedLiteralValue.NilValue,
                TypedLiteralValue.IntegerValue,
                TypedLiteralValue.DecimalValue,
                TypedLiteralValue.StringValue,
                TypedLiteralValue.CharacterValue,
                TypedLiteralValue.UnitValue {
    record BooleanValue(boolean value) implements TypedLiteralValue {
    }

    record NilValue() implements TypedLiteralValue {
        public static final NilValue INSTANCE = new NilValue();
    }

    record IntegerValue(ExactNumericLiteral value) implements TypedLiteralValue {
        public IntegerValue {
            Objects.requireNonNull(value, "value");
            if (!value.isInteger()) {
                throw new IllegalArgumentException("integer literal value must be integral");
            }
        }

        public ExactNumericLiteral exactValue() {
            return value;
        }
    }

    record DecimalValue(ExactNumericLiteral value) implements TypedLiteralValue {
        public DecimalValue {
            Objects.requireNonNull(value, "value");
            if (!value.isDecimal()) {
                throw new IllegalArgumentException("decimal literal value must be decimal");
            }
        }

        public ExactNumericLiteral exactValue() {
            return value;
        }
    }

    record StringValue(String value) implements TypedLiteralValue {
        public StringValue {
            Objects.requireNonNull(value, "value");
        }
    }

    record CharacterValue(char value) implements TypedLiteralValue {
    }

    record UnitValue(String spelling) implements TypedLiteralValue {
        public UnitValue {
            Objects.requireNonNull(spelling, "spelling");
            if (spelling.isEmpty()) {
                throw new IllegalArgumentException("unit spelling must not be empty");
            }
        }
    }
}
