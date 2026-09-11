package io.mindspice.lyra.compiler.conformance;

import java.util.List;
import java.util.SplittableRandom;

/** Bounded, well-typed trees with independent oracles and equivalent renderings. */
final class TypedProgramGenerator {
    enum Shape { A, B, BINARY, BLOCK, ARRAY, TUPLE, CALL, CONDITIONAL, COALESCE, MATCH }
    private static final List<Shape> RECURSIVE_SHAPES = List.of(
            Shape.BINARY, Shape.BLOCK, Shape.ARRAY, Shape.TUPLE, Shape.CALL,
            Shape.CONDITIONAL, Shape.COALESCE, Shape.MATCH);

    record Expr(Shape shape, String operator, Expr left, Expr right) {
        String source(NumericModel type, boolean brackets) {
            String l = left == null ? "" : left.source(type, brackets);
            String r = right == null ? "" : right.source(type, brackets);
            return switch (shape) {
                case A -> "a";
                case B -> "b";
                case BINARY -> brackets ? operator + "[" + l + ", " + r + "]"
                        : "(" + operator + " " + l + " " + r + ")";
                case BLOCK -> "{ let temporary :" + type + " = " + l + " temporary }";
                case ARRAY -> "Array<" + type + ">[" + l + ", a][0]";
                case TUPLE -> "Tuple<" + type + ",Bool>[" + l + ", #T]:.0";
                case CALL -> "((=> :" + type + " |value :" + type + "| value) " + l + ")";
                case CONDITIONAL -> "{ let less :Bool = (< a b) (less -> " + l + " : " + r + ") }";
                case COALESCE -> "{ let @nil maybe :" + type + " = " + l + " (maybe : " + r + ") }";
                case MATCH -> brackets ? "::match[a ?? ((< a b) -> a : b) -> " + l + " ?? _ -> " + r + "]"
                        : "(match a ?? ((< a b) -> a : b) -> " + l + " ?? _ -> " + r + ")";
            };
        }

        Object evaluate(NumericModel type, Object a, Object b) {
            return switch (shape) {
                case A -> a;
                case B -> b;
                case BINARY -> type.operation(operator, left.evaluate(type, a, b), right.evaluate(type, a, b));
                case BLOCK, ARRAY, TUPLE, CALL, COALESCE -> left.evaluate(type, a, b);
                case CONDITIONAL -> {
                    boolean less = type.floating() ? ((Number) a).doubleValue() < ((Number) b).doubleValue()
                            : type.integer(a).compareTo(type.integer(b)) < 0;
                    yield (less ? left : right).evaluate(type, a, b);
                }
                case MATCH -> {
                    boolean less = type.floating() ? ((Number) a).doubleValue() < ((Number) b).doubleValue()
                            : type.integer(a).compareTo(type.integer(b)) < 0;
                    boolean equal = type.floating() ? ((Number) a).doubleValue() == ((Number) b).doubleValue()
                            : type.integer(a).equals(type.integer(b));
                    yield (less || equal ? left : right).evaluate(type, a, b);
                }
            };
        }
    }

    /** A generated match program carries only source-independent choices and its test oracle. */
    record MatchProgram(boolean conditional, int patternOffset, int conditionOffset,
                        int firstDelta, int secondDelta, int comparisonCase) {
        MatchProgram(boolean conditional, int patternOffset, int conditionOffset,
                     int firstDelta, int secondDelta) {
            this(conditional, patternOffset, conditionOffset, firstDelta, secondDelta, 0);
        }

        MatchProgram {
            if (comparisonCase < 0 || comparisonCase > 5) {
                throw new IllegalArgumentException("comparisonCase must be between 0 and 5");
            }
        }

        String source() {
            return function("run", false) + function("alternate", true);
        }

        private String function(String name, boolean brackets) {
            String expression;
            if (conditional) {
                String maybeValue = addExpression("a", conditionOffset);
                String match = brackets ? "::match[_ ?? (conditionOne) -> "
                        + addExpression("a", firstDelta) + " ?? (conditionTwo) -> "
                        + addExpression("b", secondDelta) + " ?? _ -> (% 1 b)]"
                        : "(match _ ?? (conditionOne) -> " + addExpression("a", firstDelta)
                        + " ?? (conditionTwo) -> " + addExpression("b", secondDelta)
                        + " ?? _ -> (% 1 b))";
                expression = "let @nil maybe :I32 = ((== a 0) -> #NIL : " + maybeValue + ")\n"
                        + "let conditionOne :Fn<;@nil I32> = (=> | | { probes := (+ probes 10) maybe })\n"
                        + "let conditionTwo :Fn<;I32> = (=> | | { probes := (+ probes 20) b })\n"
                        + "let selected :I32 = " + match;
            } else {
                String match = brackets ? "::match[(subject) ?? (patternOne) when (guard) -> "
                        + addExpression("a", firstDelta) + " ?? (patternTwo) -> "
                        + addExpression("b", secondDelta) + " ?? _ -> (% 1 b)]"
                        : "(match (subject) ?? (patternOne) when (guard) -> "
                        + addExpression("a", firstDelta) + " ?? (patternTwo) -> "
                        + addExpression("b", secondDelta) + " ?? _ -> (% 1 b))";
                expression = "let subject :Fn<;I32> = (=> | | { probes := (++ probes) a })\n"
                        + "let patternOne :Fn<;I32> = (=> | | { probes := (+ probes 10) "
                        + addExpression("a", patternOffset) + " })\n"
                        + "let guard :Fn<;Bool> = (=> | | { probes := (+ probes 100) (!= b 0) })\n"
                        + "let patternTwo :Fn<;I32> = (=> | | { probes := (+ probes 20) "
                        + addExpression("b", conditionOffset) + " })\n"
                        + "let selected :I32 = " + match;
            }
            return "let @pub " + name + " :Fn<I32,I32;I32> = (=> |a b| {\n"
                    + "let @mut probes :I32 = 0\n" + expression
                    + "\nlet comparisonProbe :I32 = " + mixedWidthComparison(brackets)
                    + "\n(+ selected probes comparisonProbe) })\n";
        }

        private String mixedWidthComparison(boolean brackets) {
            String subject;
            String pattern;
            switch (comparisonCase) {
                case 0 -> { subject = "255U8"; pattern = "255I16"; }
                case 1 -> { subject = "65535U16"; pattern = "65535I32"; }
                case 2 -> { subject = "4294967295U32"; pattern = "4294967295I64"; }
                case 3 -> { subject = "4294967295U32"; pattern = "4294967295.0F64"; }
                case 4 -> { subject = "16777217I32"; pattern = "16777217.0F64"; }
                case 5 -> { subject = "1.5F32"; pattern = "1.5F64"; }
                default -> throw new AssertionError("validated comparisonCase");
            }
            return brackets
                    ? "::match[" + subject + " ?? " + pattern + " -> 0 ?? _ -> 1000000]"
                    : "(match " + subject + " ?? " + pattern + " -> 0 ?? _ -> 1000000)";
        }

        private static String addExpression(String value, int delta) {
            if (delta == 0) return value;
            return delta > 0 ? "(+ " + value + " " + delta + ")" : "(- " + value + " " + (-delta) + ")";
        }

        MatchEvaluation evaluate(int a, int b) {
            try {
                int probes = 0;
                int result;
                if (conditional) {
                    Integer maybe;
                    if (a == 0) {
                        maybe = null;
                    } else {
                        maybe = Math.addExact(a, conditionOffset);
                    }
                    probes += 10;
                    if (maybe != null && maybe != 0) {
                        result = Math.addExact(a, firstDelta);
                    } else {
                        probes += 20;
                        if (b != 0) {
                            result = Math.addExact(b, secondDelta);
                        } else {
                            result = remainder(b);
                        }
                    }
                } else {
                    probes = 1;
                    int subject = a;
                    probes += 10;
                    int patternOne = Math.addExact(a, patternOffset);
                    if (subject == patternOne) {
                        probes += 100;
                        if (b != 0) {
                            result = Math.addExact(a, firstDelta);
                            return new MatchEvaluation(Math.addExact(result, probes), null);
                        }
                    }
                    probes += 20;
                    int patternTwo = Math.addExact(b, conditionOffset);
                    result = subject == patternTwo ? Math.addExact(b, secondDelta) : remainder(b);
                }
                return new MatchEvaluation(Math.addExact(result, probes), null);
            } catch (ArithmeticException failure) {
                return new MatchEvaluation(null, "LYR-ARITH");
            }
        }

        private static int remainder(int b) {
            if (b == 0) throw new ArithmeticException("division by zero");
            return 1 % b;
        }
    }

    record MatchEvaluation(Integer value, String failure) { }

    static Expr generate(SplittableRandom random, NumericModel type, int depth) {
        if (depth == 0) return new Expr(random.nextBoolean() ? Shape.A : Shape.B, "", null, null);
        Shape shape = RECURSIVE_SHAPES.get(random.nextInt(RECURSIVE_SHAPES.size()));
        List<String> operators = type.floating() ? List.of("+", "-", "*", "/", "^") : List.of("+", "-", "*", "%", "^");
        return new Expr(shape, operators.get(random.nextInt(operators.size())),
                generate(random, type, depth - 1), generate(random, type, depth - 1));
    }

    static Expr generateMatch(SplittableRandom random, NumericModel type, int depth) {
        if (depth < 1) throw new IllegalArgumentException("Match depth must be positive");
        return new Expr(Shape.MATCH, "", generate(random, type, depth - 1), generate(random, type, depth - 1));
    }

    static List<Shape> recursiveShapes() {
        return RECURSIVE_SHAPES;
    }

    static MatchProgram match(SplittableRandom random) {
        return new MatchProgram(random.nextBoolean(), random.nextInt(-1, 2), random.nextInt(-1, 2),
                random.nextInt(-2, 3), random.nextInt(-2, 3), random.nextInt(6));
    }

    static String program(Expr expression, NumericModel type) {
        String signature = " :Fn<" + type + "," + type + ";" + type + "> = (=> |a b| ";
        return "let @pub run" + signature + expression.source(type, false) + ")\n"
                + "/* Equivalent operator spelling, optional commas, and nested /* trivia */. */\n"
                + "let @pub alternate" + signature + expression.source(type, true) + ")\n";
    }
}
