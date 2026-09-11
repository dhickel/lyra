package io.mindspice.lyra.compiler.conformance;

import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.CompileResult;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.lang.invoke.MethodType;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.stream.Stream;

import static io.mindspice.lyra.compiler.conformance.LanguageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Cross-products of primitive boundaries exercise emitted operations with runtime inputs. */
class LanguageNumericTest {
    @TestFactory
    Stream<DynamicTest> checkedArithmeticAndComparisonsForEveryNumericCarrier() {
        return Arrays.stream(NumericModel.values()).map(type -> DynamicTest.dynamicTest(type + "/arithmetic", () -> {
            List<String> operators = type.floating() ? List.of("+", "-", "*", "/", "^") : List.of("+", "-", "*", "%", "^");
            StringBuilder source = new StringBuilder();
            for (int i = 0; i < operators.size(); i++) source.append("let @pub op").append(i)
                    .append(" :Fn<").append(type).append(',').append(type).append(';').append(type)
                    .append("> = (=> |a b| (").append(operators.get(i)).append(" a b))\n");
            for (String op : List.of("<", "<=", ">", ">=", "==", "!=")) source.append("let @pub cmp")
                    .append(List.of("<", "<=", ">", ">=", "==", "!=").indexOf(op))
                    .append(" :Fn<").append(type).append(',').append(type).append(";Bool> = (=> |a b| (")
                    .append(op).append(" a b))\n");
            source.append("let @pub inc :Fn<").append(type).append(';').append(type).append("> = (=> |a| (++ a))\n")
                    .append("let @pub dec :Fn<").append(type).append(';').append(type).append("> = (=> |a| (-- a))\n")
                    .append("let @pub neg :Fn<").append(type).append(';').append(type).append("> = (=> |a| (- a))\n")
                    .append("let @pub reciprocal :Fn<").append(type).append(";F64> = (=> |a| (/ a))\n")
                    .append("let @pub div :Fn<").append(type).append(',').append(type).append(";F64> = (=> |a b| (/ a b))\n");
            try (var fixture = new Fixture(compile(source.toString()))) {
                String signature = "Fn<" + type + "," + type + ";" + type + ">";
                assertEquals(MethodType.methodType(type.carrier, type.carrier, type.carrier),
                        fixture.module.export("op0", signature).methodType());
                for (Object left : type.boundaries()) {
                    // The complete F64 return context widens F32 operands before this operation.
                    check(() -> NumericModel.F64.finite(1.0 /
                                    (type.floating() ? ((Number) left).doubleValue() : type.integer(left).doubleValue()), "LYR-ARITH"),
                            () -> fixture.call("reciprocal", "Fn<" + type + ";F64>", left), "reciprocal " + type);
                    for (int i = 0; i < 3; i++) {
                        String name = List.of("inc", "dec", "neg").get(i);
                        String op = i == 0 ? "+" : "-";
                        Object a = i == 2 ? type.decode("0") : left;
                        Object b = i == 2 ? left : type.decode("1");
                        check(() -> name.equals("neg") && type.floating()
                                ? type.finite(-((Number) left).doubleValue(), "LYR-ARITH")
                                : type.operation(op, a, b), () -> fixture.call(name,
                                "Fn<" + type + ";" + type + ">", left), type + " " + name + " " + left);
                    }
                    for (Object right : type.boundaries()) {
                        for (int i = 0; i < operators.size(); i++) {
                            String op = operators.get(i), name = "op" + i;
                            check(() -> type.operation(op, left, right),
                                    () -> fixture.call(name, signature, left, right),
                                    type + " " + op + " " + type.encode(left) + " " + type.encode(right));
                        }
                        double a = type.floating() ? ((Number) left).doubleValue() : 0;
                        double b = type.floating() ? ((Number) right).doubleValue() : 0;
                        int cmp = type.floating() ? (a == b ? 0 : a < b ? -1 : 1)
                                : type.integer(left).compareTo(type.integer(right));
                        List<Boolean> expected = List.of(cmp < 0, cmp <= 0, cmp > 0, cmp >= 0, cmp == 0, cmp != 0);
                        for (int i = 0; i < expected.size(); i++) assertEquals(expected.get(i), fixture.call("cmp" + i,
                                "Fn<" + type + "," + type + ";Bool>", left, right), type + " comparison " + i);
                        if (!type.floating()) check(() -> {
                            if (type.integer(right).signum() == 0) throw new NumericModel.Trap("LYR-ARITH");
                            return type.integer(left).doubleValue() / type.integer(right).doubleValue();
                        }, () -> fixture.call("div", "Fn<" + type + "," + type + ";F64>", left, right), "integer division " + type);
                    }
                }
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> allOneHundredExplicitNumericConversionPairs() {
        return Arrays.stream(NumericModel.values()).map(from -> DynamicTest.dynamicTest(from + "/conversions", () -> {
            StringBuilder source = new StringBuilder();
            for (var to : NumericModel.values()) source.append("let @pub to").append(to).append(" :Fn<")
                    .append(from).append(';').append(to).append("> = (=> |value| ").append(to).append("[value])\n");
            try (var fixture = new Fixture(compile(source.toString()))) {
                for (var to : NumericModel.values()) for (Object value : from.boundaries()) check(
                        () -> to.convert(from, value),
                        () -> fixture.call("to" + to, "Fn<" + from + ";" + to + ">", value),
                        from + " -> " + to + ": " + from.encode(value));
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> unsignedLongFloatingConversionRoundsOnceAtTiesAndRandomValues() {
        return Stream.of(NumericModel.F32, NumericModel.F64).map(target -> DynamicTest.dynamicTest(
                "U64 -> " + target + "/rounding", () -> {
                    var inputs = new TreeSet<BigInteger>();
                    inputs.add(BigInteger.ZERO);
                    inputs.add(NumericModel.U64.max());
                    inputs.add(new BigInteger("16092878570068704171")); // Retained seed-42 failure.
                    int precision = target == NumericModel.F32 ? 24 : 53;
                    for (int exponent = precision; exponent <= 63; exponent++) {
                        BigInteger base = BigInteger.ONE.shiftLeft(exponent);
                        BigInteger spacing = BigInteger.ONE.shiftLeft(exponent - precision + 1);
                        // Both even and odd significand ties, adjacent values, and power boundaries.
                        for (BigInteger pivot : List.of(base, base.add(spacing.shiftRight(1)),
                                base.add(spacing).add(spacing.shiftRight(1)),
                                base.shiftLeft(1).subtract(spacing.shiftRight(1)))) {
                            for (int delta = -1; delta <= 1; delta++) {
                                BigInteger value = pivot.add(BigInteger.valueOf(delta));
                                if (value.signum() >= 0 && value.compareTo(NumericModel.U64.max()) <= 0) {
                                    inputs.add(value);
                                }
                            }
                        }
                    }
                    Random random = new Random(0x55464cL);
                    for (int i = 0; i < 2048; i++) inputs.add(new BigInteger(64, random));
                    String source = "let @pub convert :Fn<U64;" + target
                            + "> = (=> |value| " + target + "[value])\n"
                            + "let @pub divide :Fn<U64;F64> = (=> |value| (/ value 1U64))\n";
                    try (var fixture = new Fixture(compile(source))) {
                        for (BigInteger value : inputs) {
                            Object expected;
                            if (target == NumericModel.F32) expected = value.floatValue();
                            else expected = value.doubleValue();
                            assertEquals(expected, fixture.call("convert", "Fn<U64;" + target + ">", value.longValue()),
                                    "U64 -> " + target + ": " + value);
                            if (target == NumericModel.F64) assertEquals(value.doubleValue(),
                                    fixture.call("divide", "Fn<U64;F64>", value.longValue()),
                                    "U64 floating division: " + value);
                        }
                    }
                }));
    }

    @TestFactory
    Stream<DynamicTest> exactIntegerLiteralRangesAndConstantFailures() {
        return Arrays.stream(NumericModel.values()).filter(t -> !t.floating()).map(type -> DynamicTest.dynamicTest(
                type + "/literal boundaries", () -> {
                    for (BigInteger n : List.of(type.min(), type.max())) {
                        String literal = n.signum() < 0 ? "(- " + n.negate() + type + ")" : n + type.toString();
                        try (var fixture = new Fixture(compile("let @pub run :Fn<;String> = (=> | | String[" + literal + "] )"))) {
                            assertEquals(n.toString(), fixture.call("run", "Fn<;String>"));
                        }
                    }
                    for (String expression : List.of(type.max().add(BigInteger.ONE) + type.toString(),
                            "(++ " + type.max() + type + ")", type.signed
                                    ? "(-- (- " + type.min().negate() + type + "))" : "(-- 0" + type + ")")) {
                        String source = "let x = " + expression;
                        var failure = assertInstanceOf(CompileResult.Failure.class,
                                LyraCompiler.compile(CompileRequest.source("case.lyra", source)), source);
                        diagnostics(failure.diagnostics(), source.length());
                        assertTrue(List.of("LYC-LEX-011", "LYC-TYPE-008").contains(
                                failure.diagnostics().getFirst().code().value()), failure.toString());
                    }
                }));
    }

    @FunctionalInterface public interface Evaluation { Object run() throws Throwable; }

    public static void check(Evaluation expected, Evaluation actual, String label) throws Throwable {
        Object value;
        try { value = expected.run(); }
        catch (NumericModel.Trap trap) {
            Throwable failure = assertThrows(Throwable.class, actual::run, label);
            try { runtimeFailure(failure, trap.code); }
            catch (AssertionError assertion) { throw new AssertionError(label, assertion); }
            return;
        }
        assertEquals(value, actual.run(), label);
    }
}
