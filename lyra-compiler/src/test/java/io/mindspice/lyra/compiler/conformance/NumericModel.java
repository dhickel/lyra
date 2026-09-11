package io.mindspice.lyra.compiler.conformance;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/** Independent mathematical test oracle. Do not call compiler folding or runtime arithmetic here. */
public enum NumericModel {
    I8(8, true, byte.class), I16(16, true, short.class), I32(32, true, int.class), I64(64, true, long.class),
    U8(8, false, byte.class), U16(16, false, short.class), U32(32, false, int.class), U64(64, false, long.class),
    F32(32, true, float.class), F64(64, true, double.class);

    public final int bits;
    public final boolean signed;
    public final Class<?> carrier;

    NumericModel(int bits, boolean signed, Class<?> carrier) {
        this.bits = bits;
        this.signed = signed;
        this.carrier = carrier;
    }

    public boolean floating() { return this == F32 || this == F64; }
    public BigInteger min() { return signed ? BigInteger.ONE.shiftLeft(bits - 1).negate() : BigInteger.ZERO; }
    public BigInteger max() { return BigInteger.ONE.shiftLeft(signed ? bits - 1 : bits).subtract(BigInteger.ONE); }

    public BigInteger integer(Object value) {
        BigInteger result = BigInteger.valueOf(((Number) value).longValue());
        return !signed && result.signum() < 0 ? result.add(BigInteger.ONE.shiftLeft(bits)) : result;
    }

    public Object box(BigInteger value) {
        return switch (bits) {
            case 8 -> value.byteValue();
            case 16 -> value.shortValue();
            case 32 -> value.intValue();
            case 64 -> value.longValue();
            default -> throw new AssertionError(bits);
        };
    }

    public Object checked(BigInteger value, String failureCode) {
        if (value.compareTo(min()) < 0 || value.compareTo(max()) > 0) throw new Trap(failureCode);
        return box(value);
    }

    public Object finite(double value, String failureCode) {
        if (this == F32) {
            float narrowed = (float) value;
            if (!Float.isFinite(narrowed)) throw new Trap(failureCode);
            return narrowed;
        }
        if (!Double.isFinite(value)) throw new Trap(failureCode);
        return value;
    }

    public Object operation(String operator, Object left, Object right) {
        if (floating()) {
            double a = ((Number) left).doubleValue();
            double b = ((Number) right).doubleValue();
            // IEEE operations round at each F32 node, not just at the final expression.
            double result = switch (operator) {
                case "+" -> this == F32 ? (float) a + (float) b : a + b;
                case "-" -> this == F32 ? (float) a - (float) b : a - b;
                case "*" -> this == F32 ? (float) a * (float) b : a * b;
                case "/" -> this == F32 ? (float) a / (float) b : a / b;
                case "^" -> Math.pow(a, b);
                default -> throw new AssertionError(operator);
            };
            return finite(result, "LYR-ARITH");
        }
        BigInteger a = integer(left), b = integer(right);
        BigInteger result = switch (operator) {
            case "+" -> a.add(b);
            case "-" -> a.subtract(b);
            case "*" -> a.multiply(b);
            case "%" -> {
                if (b.signum() == 0) throw new Trap("LYR-ARITH");
                yield a.remainder(b);
            }
            case "^" -> {
                if (b.signum() < 0) throw new Trap("LYR-ARITH");
                if (b.signum() == 0) yield BigInteger.ONE;
                if (a.signum() == 0 || a.equals(BigInteger.ONE)) yield a;
                if (a.equals(BigInteger.ONE.negate())) yield b.testBit(0) ? a : BigInteger.ONE;
                if (b.compareTo(BigInteger.valueOf(64)) > 0) throw new Trap("LYR-ARITH");
                yield a.pow(b.intValueExact());
            }
            default -> throw new AssertionError(operator);
        };
        return checked(result, "LYR-ARITH");
    }

    public Object convert(NumericModel from, Object value) {
        if (floating()) {
            if (this == F32 && !from.floating()) {
                return finite(from.integer(value).floatValue(), "LYR-CONVERT");
            }
            double number = from.floating() ? ((Number) value).doubleValue() : from.integer(value).doubleValue();
            return finite(number, "LYR-CONVERT");
        }
        BigInteger integer;
        try {
            // BigDecimal(double) retains the exact binary value, including boundary rounding.
            integer = from.floating() ? new BigDecimal(((Number) value).doubleValue()).toBigIntegerExact()
                    : from.integer(value);
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new Trap("LYR-CONVERT");
        }
        return checked(integer, "LYR-CONVERT");
    }

    public List<Object> boundaries() {
        if (floating()) {
            double largest = this == F32 ? Float.MAX_VALUE : Double.MAX_VALUE;
            double smallest = this == F32 ? Float.MIN_VALUE : Double.MIN_VALUE;
            List<Object> values = new ArrayList<>();
            for (double d : new double[]{-largest, -128.0, -1.5, -1.0, -smallest, -0.0, 0.0,
                    smallest, 0.5, 1.0, 2.0, 127.0, 128.0, 255.0, 256.0, largest}) {
                values.add(finite(d, "LYR-CONVERT"));
            }
            return List.copyOf(values);
        }
        return java.util.stream.Stream.of(min(), min().add(BigInteger.ONE),
                        signed ? BigInteger.ONE.negate() : BigInteger.TWO,
                        BigInteger.ZERO, BigInteger.ONE, BigInteger.TWO, max().subtract(BigInteger.ONE), max())
                .distinct().map(this::box).toList();
    }

    public Object random(SplittableRandom random) {
        if (random.nextBoolean()) {
            List<Object> boundaries = boundaries();
            return boundaries.get(random.nextInt(boundaries.size()));
        }
        if (floating()) return finite(Math.scalb(random.nextDouble(-1, 1), random.nextInt(-20, 21)), "LYR-CONVERT");
        return box(BigInteger.valueOf(random.nextLong()));
    }

    public String encode(Object value) {
        return floating() ? value.toString() : integer(value).toString();
    }

    public Object decode(String value) {
        if (this == F32) return Float.valueOf(value);
        if (this == F64) return Double.valueOf(value);
        return box(new BigInteger(value));
    }

    public static final class Trap extends RuntimeException {
        public final String code;
        public Trap(String code) { super(code, null, false, false); this.code = code; }
    }
}
