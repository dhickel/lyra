package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.types.ExactNumericLiteral;

import java.util.Objects;

/** Exact, JVM-independent constant data used by the closed typed IR. */
public sealed interface IrConstantValue
        permits IrConstantValue.BooleanValue,
                IrConstantValue.NilValue,
                IrConstantValue.IntegerValue,
                IrConstantValue.DecimalValue,
                IrConstantValue.StringValue,
                IrConstantValue.CharacterValue,
                IrConstantValue.UnitValue {
    record BooleanValue(boolean value) implements IrConstantValue {
    }

    record NilValue() implements IrConstantValue {
        public static final NilValue INSTANCE = new NilValue();
    }

    record IntegerValue(ExactNumericLiteral value) implements IrConstantValue {
        public IntegerValue {
            Objects.requireNonNull(value, "value");
            if (!value.isInteger()) {
                throw new IllegalArgumentException("integer IR constant must be integral");
            }
        }

        public ExactNumericLiteral exactValue() {
            return value;
        }
    }

    record DecimalValue(ExactNumericLiteral value) implements IrConstantValue {
        public DecimalValue {
            Objects.requireNonNull(value, "value");
            if (!value.isDecimal()) {
                throw new IllegalArgumentException("decimal IR constant must be decimal");
            }
        }

        public ExactNumericLiteral exactValue() {
            return value;
        }
    }

    record StringValue(String value) implements IrConstantValue {
        public StringValue {
            Objects.requireNonNull(value, "value");
        }
    }

    record CharacterValue(char value) implements IrConstantValue {
    }

    record UnitValue(String spelling) implements IrConstantValue {
        public UnitValue {
            Objects.requireNonNull(spelling, "spelling");
            if (spelling.isEmpty()) {
                throw new IllegalArgumentException("unit IR constant spelling must not be empty");
            }
        }
    }
}
