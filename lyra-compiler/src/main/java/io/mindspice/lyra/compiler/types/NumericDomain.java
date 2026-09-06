package io.mindspice.lyra.compiler.types;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/** The mathematical domain represented by one primitive numeric type. */
public record NumericDomain(NumericKind kind, int bitWidth) {
    private static final BigInteger TWO = BigInteger.valueOf(2);

    public NumericDomain {
        Objects.requireNonNull(kind, "kind");
        if (kind == NumericKind.FLOAT) {
            if (bitWidth != 32 && bitWidth != 64) {
                throw new IllegalArgumentException("floating numeric domains must be F32 or F64");
            }
        } else if (bitWidth != 8 && bitWidth != 16 && bitWidth != 32 && bitWidth != 64) {
            throw new IllegalArgumentException("integer numeric domains must use an 8/16/32/64-bit width");
        }
    }

    public boolean isInteger() {
        return kind != NumericKind.FLOAT;
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

    public Optional<BigInteger> minimum() {
        return isInteger() ? Optional.of(minimumInteger()) : Optional.empty();
    }

    public Optional<BigInteger> maximum() {
        return isInteger() ? Optional.of(maximumInteger()) : Optional.empty();
    }

    public BigInteger minimumInteger() {
        requireInteger();
        if (isUnsignedInteger()) {
            return BigInteger.ZERO;
        }
        return TWO.pow(bitWidth - 1).negate();
    }

    public BigInteger maximumInteger() {
        requireInteger();
        if (isUnsignedInteger()) {
            return TWO.pow(bitWidth).subtract(BigInteger.ONE);
        }
        return TWO.pow(bitWidth - 1).subtract(BigInteger.ONE);
    }

    public boolean contains(BigInteger value) {
        Objects.requireNonNull(value, "value");
        return isInteger()
                && value.compareTo(minimumInteger()) >= 0
                && value.compareTo(maximumInteger()) <= 0;
    }

    /** Whether every value in this domain is losslessly contained by {@code target}. */
    public boolean canImplicitlyWidenTo(NumericDomain target) {
        Objects.requireNonNull(target, "target");
        if (this.equals(target)) {
            return true;
        }
        if (isInteger() && target.isInteger()) {
            if (kind == target.kind) {
                return target.bitWidth >= bitWidth;
            }
            return isUnsignedInteger()
                    && target.isSignedInteger()
                    && target.bitWidth > bitWidth;
        }
        if (isInteger() && target.isFloating()) {
            return target.bitWidth == 32 ? bitWidth <= 16 : bitWidth <= 32;
        }
        return kind == NumericKind.FLOAT && bitWidth == 32
                && target.kind == NumericKind.FLOAT && target.bitWidth == 64;
    }

    public boolean isLosslessWideningTo(NumericDomain target) {
        Objects.requireNonNull(target, "target");
        return canImplicitlyWidenTo(target) && !equals(target);
    }

    private void requireInteger() {
        if (!isInteger()) {
            throw new IllegalStateException("floating numeric domains have no integer bounds");
        }
    }
}
