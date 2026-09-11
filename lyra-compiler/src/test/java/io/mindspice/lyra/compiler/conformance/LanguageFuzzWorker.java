package io.mindspice.lyra.compiler.conformance;

import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.SourceResolver;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.lex.TokenKind;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.ResolvedSource;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeMap;

import static io.mindspice.lyra.compiler.conformance.LanguageTestSupport.*;

/** Test-only process entry point. Fuzzed execution never runs on the Maven/JUnit owner thread. */
public final class LanguageFuzzWorker {
    static final List<String> MODES = List.of("numeric", "state", "mutation", "grammar", "modules", "bytes", "runtime", "artifact", "io", "match", "loops");
    static final int MINIMUM_CASES = MODES.size() * NumericModel.values().length;
    private static final String[] TOKENS = {"let", "@pub", "@mut", "@nil", "a", "b", "I32", "Array", "Tuple",
            "Fn", "#T", "#F", "#NIL", "0", "255U8", "18446744073709551615U64", "1.0e-99", "'x'",
            "\"😀\"", "=", ":=", "=>", "::", ":.", "->", ":", ",", ";", "|", "(", ")", "[", "]", "{", "}",
            "+", "-", "*", "/", "%", "^", "<", ">", "==", "eq?", "and", "xor", "match", "when", "??", "_", "/*", "*/", "//\n"};

    public static void main(String[] args) throws Throwable {
        try {
            execute(args);
        } catch (Throwable failure) {
            Throwable root = failure;
            for (int i = 0; i < 20 && root.getCause() != null; i++) root = root.getCause();
            String detail = String.valueOf(root.getMessage()).lines().findFirst().orElse("");
            // Keep stable diagnostic codes even while normalizing changing offsets and sizes.
            var codes = java.util.regex.Pattern.compile("LY[CR]-[A-Z]+(?:-[0-9]{3})?").matcher(detail)
                    .results().map(java.util.regex.MatchResult::group).distinct().sorted().toList();
            System.err.println("FUZZ_FAILURE=" + root.getClass().getName() + ":" + codes + ":"
                    + detail.replaceAll("[0-9]+", "#"));
            throw failure;
        }
    }

    private static void execute(String[] args) throws Throwable {
        if (args.length == 3 && args[0].equals("--probe") && args[1].equals("hang")) {
            new FuzzCase("mutation", "let x = 1").save(Path.of(args[2]));
            for (;;) Thread.sleep(1000);
        }
        if (args.length == 2 && args[0].equals("--replay")) {
            run(FuzzCase.read(Path.of(args[1])));
            System.out.println("REPLAY PASS");
            return;
        }
        if (args.length != 4 || !args[0].equals("--campaign")) throw new IllegalArgumentException("invalid worker arguments");
        long seed = Long.parseLong(args[1]);
        int count = Integer.parseInt(args[2]);
        if (count < MINIMUM_CASES) throw new IllegalArgumentException(
                "campaign count must be at least " + MINIMUM_CASES + " to cover every mode and numeric kind");
        Path directory = Path.of(args[3]);
        List<LanguageCorpus.Case> corpus = LanguageCorpus.read();
        TreeMap<String, Integer> coverage = new TreeMap<>();
        TreeMap<NumericModel, Integer> numericCoverage = new TreeMap<>();
        for (int index = 0; index < count; index++) {
            // Index-local seeds preserve the first N cases when the budget grows and enable sharding.
            long caseSeed = mix(seed + index);
            SplittableRandom random = new SplittableRandom(caseSeed);
            String mode = modeAt(index);
            FuzzCase test = switch (mode) {
                case "numeric" -> numeric(random, numericTypeAt(index));
                case "match" -> match(random);
                case "loops" -> loops(random);
                case "state" -> state(random);
                case "mutation" -> new FuzzCase(mode, mutate(random, corpus.get(random.nextInt(corpus.size())).source()));
                case "grammar" -> new FuzzCase(mode, grammar(random, 4));
                case "modules" -> modules(random);
                case "bytes" -> bytes(random, index);
                case "runtime" -> runtime(random);
                case "io" -> io(random);
                case "artifact" -> {
                    FuzzCase artifact = new FuzzCase(mode, "let @pub run :Fn<;I32> = (=> | | 42)");
                    artifact.put("corruption", random.nextInt(5));
                    artifact.put("offset", random.nextInt(1_000_000));
                    yield artifact;
                }
                default -> throw new AssertionError(mode);
            };
            test.put("seed", seed); test.put("index", index); test.put("caseSeed", caseSeed);
            test.put("java", System.getProperty("java.runtime.version"));
            test.save(directory);
            run(test);
            coverage.merge(mode, 1, Integer::sum);
            if (mode.equals("numeric")) numericCoverage.merge(numericTypeAt(index), 1, Integer::sum);
        }
        require(coverage.keySet().containsAll(MODES), "Campaign omitted a generator family");
        require(numericCoverage.keySet().containsAll(List.of(NumericModel.values())),
                "Campaign omitted a numeric kind");
        Files.writeString(directory.resolve("summary.txt"), "seed=" + seed + " cases=" + count + "\n"
                + coverage + "\nnumeric=" + numericCoverage + "\n");
        System.out.println("FUZZ PASS seed=" + seed + " cases=" + count + " " + coverage
                + " numeric=" + numericCoverage);
    }

    static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    static String modeAt(int index) {
        return MODES.get(Math.floorMod(index, MODES.size()));
    }

    static NumericModel numericTypeAt(int index) {
        if (!modeAt(index).equals("numeric")) throw new IllegalArgumentException("Not a numeric case index: " + index);
        return NumericModel.values()[Math.floorMod(index / MODES.size(), NumericModel.values().length)];
    }

    static FuzzCase numeric(SplittableRandom random, NumericModel type) {
        var tree = TypedProgramGenerator.generateMatch(random, type, random.nextInt(1, 5));
        NumericModel destination = NumericModel.values()[random.nextInt(NumericModel.values().length)];
        FuzzCase test = new FuzzCase("numeric", TypedProgramGenerator.program(tree, type)
                + "let @pub convert :Fn<" + type + ";" + destination + "> = (=> |value| " + destination + "[value])\n");
        test.put("type", type);
        test.put("convertTo", destination);
        // Several independent runtime arguments per compiled shape, including signed/unsigned limits.
        for (int i = 0; i < 6; i++) {
            Object a = type.random(random), b = type.random(random);
            test.put("a." + i, type.encode(a)); test.put("b." + i, type.encode(b));
            try { test.put("expected." + i, type.encode(tree.evaluate(type, a, b))); }
            catch (NumericModel.Trap trap) { test.put("failure." + i, trap.code); }
            try { test.put("converted." + i, destination.encode(destination.convert(type, a))); }
            catch (NumericModel.Trap trap) { test.put("conversionFailure." + i, trap.code); }
        }
        return test;
    }

    static FuzzCase loops(SplittableRandom random) {
        int start = random.nextInt(-20, 21);
        int end = random.nextInt(-20, 21);
        int step = random.nextInt(1, 6) * (random.nextBoolean() ? 1 : -1);
        boolean inclusive = random.nextBoolean();
        boolean binding = random.nextBoolean();
        boolean bracket = random.nextBoolean();
        int actions = 0;
        int sum = 0;
        for (int x = start; step > 0 ? inclusive ? x <= end : x < end : inclusive ? x >= end : x > end; x += step) {
            actions++;
            sum += binding ? x : 1;
        }
        String range = "(" + signedLoopLiteral(start) + (inclusive ? "..." : "..")
                + signedLoopLiteral(end) + ":" + signedLoopLiteral(step) + ")";
        String callback = binding ? "|x| { sum := (+ sum x) }" : "|| { sum := (+ sum 1) }";
        String iter = bracket ? "::iter[range " + callback + "]" : "(iter range " + callback + ")";
        int count = random.nextInt(0, 21);
        String source = "let @pub run :Fn<;I64> = (=> || { let range = " + range
                + " let @mut sum :I64 = 0 " + iter
                + " let @mut n :I64 = 0 let @mut tests :I64 = 0 "
                + "::while[|| { tests := (+ tests 1) (< n " + count + ") } || { n := (+ n 1) }] "
                + "(+ (* sum 10000) (* tests 100) n) })";
        FuzzCase result = new FuzzCase("loops", source);
        result.put("expected", sum * 10000L + (count + 1) * 100L + count);
        result.put("actions", actions);
        return result;
    }

    private static String signedLoopLiteral(int value) {
        return value < 0 ? "(- " + (-value) + ")" : Integer.toString(value);
    }

    static FuzzCase match(SplittableRandom random) {
        TypedProgramGenerator.MatchProgram program = TypedProgramGenerator.match(random);
        FuzzCase test = new FuzzCase("match", program.source());
        test.put("conditional", program.conditional());
        test.put("patternOffset", program.patternOffset());
        test.put("conditionOffset", program.conditionOffset());
        test.put("firstDelta", program.firstDelta());
        test.put("secondDelta", program.secondDelta());
        test.put("comparisonCase", program.comparisonCase());
        for (int i = 0; i < 6; i++) {
            int[] boundaries = {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE};
            int a = random.nextInt(3) == 0 ? boundaries[random.nextInt(boundaries.length)] : random.nextInt(-20, 21);
            int b = random.nextInt(3) == 0 ? boundaries[random.nextInt(boundaries.length)] : random.nextInt(-20, 21);
            test.put("a." + i, a);
            test.put("b." + i, b);
            TypedProgramGenerator.MatchEvaluation expected = program.evaluate(a, b);
            if (expected.failure() == null) test.put("expected." + i, expected.value());
            else test.put("failure." + i, expected.failure());
        }
        return test;
    }

    static FuzzCase state(SplittableRandom random) {
        int n = random.nextInt(20);
        int[] array = {random.nextInt(10), random.nextInt(10), random.nextInt(10)};
        StringBuilder body = new StringBuilder("let @pub run :Fn<;I32> = (=> | | {\n")
                .append("let @mut n :I32 = ").append(n).append("\nlet original :Array<I32> = Array[")
                .append(array[0]).append(' ').append(array[1]).append(' ').append(array[2]).append("]\n")
                .append("let @mut alias = original\nlet bump :Fn<;I32> = (=> | | { n := (++ n) n })\n")
                .append("let apply :Fn<Fn<I32;I32>,I32;I32> = (=> |f x| (f x))\n");
        for (int i = 0; i < random.nextInt(8, 25); i++) {
            int value = random.nextInt(10), index = random.nextInt(array.length);
            switch (random.nextInt(9)) {
                case 0 -> { body.append("alias[").append(index).append("] := ").append(value).append('\n'); array[index] = value; }
                case 1 -> { body.append("n := (+ n ").append(value).append(")\n"); n += value; }
                case 2 -> { body.append("(bump)\n"); n++; }
                case 3 -> body.append("(and #F (bump))\n(or #T (bump))\n");
                case 4 -> { body.append("(xor (bump) (bump))\n"); n += 2; }
                case 5 -> {
                    boolean nil = random.nextBoolean();
                    body.append("{ let @nil maybe :I32 = ").append(nil ? "#NIL" : value)
                            .append(" n := (+ n (maybe : 3)) }\n"); n += nil ? 3 : value;
                }
                case 6 -> { body.append("n := ::apply[|x| (+ x 2), n]\n"); n += 2; }
                case 7 -> body.append("{ let saved :Fn<;I32> = (=> | | n) let n :String = \"shadow\" (saved) }\n");
                case 8 -> {
                    boolean nil = random.nextBoolean();
                    int truthValue = random.nextBoolean() ? 0 : random.nextInt(1, 10);
                    body.append("{ let @nil maybe :I32 = ")
                            .append(nil ? "#NIL" : truthValue)
                            .append(" (maybe present -> { n := (+ n present) } : { n := (+ n 3) }) ")
                            .append("((== #F maybe) -> { n := (+ n 5) } : { n := (+ n 7) }) }\n");
                    n += nil || truthValue == 0 ? 3 : truthValue;
                    n += nil || truthValue == 0 ? 5 : 7;
                }
                default -> throw new AssertionError();
            }
        }
        body.append("let tuple = Tuple[n original[0] original[1] original[2]]\n")
                .append("(+ tuple:.0 tuple:.1 tuple:.2 tuple:.3) })\n");
        FuzzCase test = new FuzzCase("state", body.toString());
        test.put("expected", n + array[0] + array[1] + array[2]);
        return test;
    }

    static FuzzCase modules(SplittableRandom random) {
        int count = random.nextInt(2, 6), expected = 0;
        FuzzCase test = new FuzzCase("modules", "");
        test.put("moduleCount", count);
        String prefix = random.nextBoolean() ? "group->" : "group->nested->";
        for (int i = count - 1; i >= 0; i--) {
            int value = random.nextInt(100);
            expected += value;
            int form = random.nextInt(3);
            String next = prefix + "mod" + (i + 1);
            String header = switch (form) {
                case 0 -> "import " + next + " as next\n";
                case 1 -> "import " + next + "->{run as delegated}\n";
                case 2 -> "import @pub " + next + "->{run as delegated" + i + "}\n";
                default -> throw new AssertionError();
            };
            String call = form == 0 ? "next->:.run" : form == 1 ? "delegated" : "delegated" + i;
            String source = i == count - 1 ? "" : header + "import " + next + "\n";
            String result = "(+ value (" + call + "))";
            String terminal = random.nextBoolean() ? "" : "->";
            String matchBody = next + terminal + ":.value ?? " + next + terminal
                    + ":.value when " + next + terminal + "::ready[] -> " + result + " ?? _ -> (- 1)";
            String match = random.nextBoolean() ? "(match " + matchBody + ")" : "::match[" + matchBody + "]";
            source += "let @pub value :I32 = " + value + "\nlet @pub ready :Fn<;Bool> = (=> || #T)"
                    + "\nlet @pub run :Fn<;I32> = (=> | | "
                    + (i == count - 1 ? "value" : match) + ")\n";
            test.put("moduleName." + i, prefix + "mod" + i);
            test.put("module." + i, source);
        }
        test.put("source", test.get("module.0")); test.put("expected", expected);
        return test;
    }

    static String mutate(SplittableRandom random, String source) {
        // Work on code points so the public String API's well-formed UTF-16 precondition is respected.
        List<String> points = new ArrayList<>(source.codePoints().mapToObj(Character::toString).toList());
        for (int i = 0, edits = random.nextInt(1, 6); i < edits; i++) {
            int at = random.nextInt(points.size() + 1);
            String token = TOKENS[random.nextInt(TOKENS.length)];
            switch (random.nextInt(5)) {
                case 0 -> { if (at < points.size()) points.remove(at); }
                case 1 -> points.add(at, token);
                case 2 -> { if (at < points.size()) points.set(at, token); }
                case 3 -> { if (at < points.size()) points.add(at, points.get(at)); }
                case 4 -> points.subList(at, points.size()).clear();
                default -> throw new AssertionError();
            }
        }
        return String.join("", points);
    }

    static String grammar(SplittableRandom random, int depth) {
        if (depth == 0) return TOKENS[random.nextInt(TOKENS.length)];
        String a = grammar(random, depth - 1), b = grammar(random, depth - 1);
        return switch (random.nextInt(11)) {
            case 0 -> "let a :I32 = " + a + "\n" + b;
            case 1 -> "(" + a + " " + b + ")";
            case 2 -> "Array<@nil I32>[" + a + ", " + b + "]";
            case 3 -> "(=> :I32 |a :I32| " + a + ")";
            case 4 -> "(" + a + " binding -> " + b + " : 0)";
            case 5 -> "{ " + a + " " + b + " }";
            case 6 -> "/* outer /* inner */ */\n" + a;
            case 7 -> "::a[" + a + " " + b + "]";
            case 8 -> a + TOKENS[random.nextInt(TOKENS.length)] + b;
            case 9 -> "(match " + a + " ?? " + b + " -> " + a + " ?? _ -> " + b + ")";
            case 10 -> "::match[_ ?? " + a + " -> " + b + " ?? _]";
            default -> throw new AssertionError();
        };
    }

    static FuzzCase bytes(SplittableRandom random, int index) {
        byte[] bytes;
        if ((index / MODES.size()) % 2 == 0) {
            bytes = new byte[random.nextInt(0, 257)];
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) random.nextInt(256);
        } else {
            String source = "\ufeff/* 😀 */\r\nlet x = \"\\uD800\\0\"\n" + grammar(random, 2);
            bytes = source.getBytes(StandardCharsets.UTF_8);
        }
        FuzzCase test = new FuzzCase("bytes", "// Exact source bytes are saved as base64 in current.properties\n");
        test.put("bytes", Base64.getEncoder().encodeToString(bytes));
        return test;
    }

    static FuzzCase runtime(SplittableRandom random) {
        FuzzCase test = new FuzzCase("runtime", """
                let @mut count :I32 = 0
                let @pub read :Fn<;I32> = (=> | | count)
                let @pub add :Fn<I32;I32> = (=> |n| { count := (+ count n) count })
                let @pub fail :Fn<I32;I32> = (=> |n| { count := (+ count n) let zero :I32 = 0 (% 1 zero) })
                let @pub maker :Fn<;Fn<;I32>> = (=> | | (=> | | count))
                let @pub apply :Fn<Fn<;I32>;I32> = (=> |f| (f))
                """);
        for (int i = 0; i < 12; i++) {
            test.put("action." + i, random.nextInt(5));
            test.put("delta." + i, random.nextInt(1, 10));
        }
        return test;
    }

    static FuzzCase io(SplittableRandom random) {
        String[] alphabet = {"a", "Z", "0", "😀", "é", "\t", "\u0000", "\\", "\""};
        StringBuilder text = new StringBuilder();
        for (int i = 0, length = random.nextInt(0, 24); i < length; i++) text.append(alphabet[random.nextInt(alphabet.length)]);
        String payload = text.toString();
        String escaped = payload.replace("\\", "\\\\").replace("\"", "\\\"").replace("\t", "\\t").replace("\u0000", "\\0");
        String source = """
                import std->io
                let @pub run :Fn<;I32> = (=> | | {
                    let text :String = "%s"
                    io->::print[text] io->::println[text]
                    io->::eprint[text] io->::eprintln[text]
                    let first = io->::readLine[]
                    let second = io->::readLine[]
                    let third = io->::readLine[]
                    let eof = io->::readLine[]
                    io->::print[(+ (first : "EOF") "|" (second : "EOF") "|" (third : "EOF") "|" (eof : "EOF"))]
                    text:.length
                })
                """.formatted(escaped);
        FuzzCase test = new FuzzCase("io", source);
        test.put("input", payload + "\r\n\nlast");
        test.put("stdout", payload + payload + "\n" + payload + "||last|EOF");
        test.put("stderr", payload + payload + "\n");
        test.put("expected", payload.length());
        return test;
    }

    static void run(FuzzCase test) throws Throwable {
        switch (test.get("mode")) {
            case "mutation", "grammar" -> checkUntrustedSyntax(test.get("source"));
            case "numeric" -> {
                NumericModel type = NumericModel.valueOf(test.get("type"));
                var artifact = compile(test.get("source"));
                sameArtifact(artifact, compile(test.get("source")));
                try (var fixture = new Fixture(artifact)) {
                    if (test.get("convertTo") != null) {
                        NumericModel destination = NumericModel.valueOf(test.get("convertTo"));
                        for (int i = 0; i < 6; i++) {
                            Object argument = type.decode(test.get("a." + i));
                            String signature = "Fn<" + type + ";" + destination + ">";
                            if (test.get("conversionFailure." + i) != null) {
                                expectCode(test.get("conversionFailure." + i), () -> fixture.call("convert", signature, argument));
                            } else {
                                equal(destination.decode(test.get("converted." + i)), fixture.call("convert", signature, argument),
                                        "Explicit conversion oracle mismatch: " + type + " -> " + destination + ", input " + i);
                            }
                        }
                    }
                    for (int i = 0; i < 6; i++) for (String name : List.of("run", "alternate")) {
                        Object a = type.decode(test.get("a." + i)), b = type.decode(test.get("b." + i));
                        String signature = "Fn<" + type + "," + type + ";" + type + ">";
                        String code = test.get("failure." + i);
                        Object actual;
                        try { actual = fixture.call(name, signature, a, b); }
                        catch (Throwable failure) {
                            if (code == null) throw failure;
                            runtimeFailure(failure, code);
                            continue;
                        }
                        require(code == null, "Expected " + code + " but returned " + actual);
                        equal(type.decode(test.get("expected." + i)), actual, "Numeric oracle mismatch, input " + i + ", " + name);
                    }
                }
            }
            case "match" -> {
                var artifact = compile(test.get("source"));
                sameArtifact(artifact, compile(test.get("source")));
                try (var fixture = new Fixture(artifact)) {
                    String signature = "Fn<I32,I32;I32>";
                    for (int i = 0; i < 6; i++) {
                        int a = Integer.parseInt(test.get("a." + i));
                        int b = Integer.parseInt(test.get("b." + i));
                        String code = test.get("failure." + i);
                        Object actual;
                        try { actual = fixture.call("run", signature, a, b); }
                        catch (Throwable failure) {
                            if (code == null) throw failure;
                            runtimeFailure(failure, code);
                            try {
                                fixture.call("alternate", signature, a, b);
                                throw new AssertionError("Expected alternate to fail with " + code);
                            } catch (Throwable alternateFailure) {
                                runtimeFailure(alternateFailure, code);
                            }
                            continue;
                        }
                        require(code == null, "Expected " + code + " but returned " + actual);
                        equal(Integer.parseInt(test.get("expected." + i)), actual, "Match oracle mismatch, input " + i);
                        equal(actual, fixture.call("alternate", signature, a, b), "Equivalent match spelling mismatch, input " + i);
                    }
                }
            }
            case "loops" -> {
                var artifact = compile(test.get("source"));
                try (var fixture = new Fixture(artifact)) {
                    equal(Long.parseLong(test.get("expected")), fixture.call("run", "Fn<;I64>"), test.get("source"));
                }
            }
            case "state" -> {
                var artifact = compile(test.get("source"));
                try (var first = new Fixture(artifact); var second = new Fixture(artifact)) {
                    int expected = Integer.parseInt(test.get("expected"));
                    equal(expected, first.call("run", "Fn<;I32>"), "State/alias/capture model mismatch");
                    equal(expected, second.call("run", "Fn<;I32>"), "Module instance isolation mismatch");
                    equal(expected, first.call("run", "Fn<;I32>"), "Local state was retained between calls");
                }
            }
            case "modules" -> {
                List<ResolvedSource> sources = new ArrayList<>();
                int count = Integer.parseInt(test.get("moduleCount"));
                for (int i = 0; i < count; i++) sources.add(ResolvedSource.memory(LogicalModuleId.parse(
                        test.data.getProperty("moduleName." + i, "mod" + i)),
                        "memory:fuzz/mod" + i, test.get("module." + i)));
                String root = test.data.getProperty("moduleName.0", "mod0");
                var request = CompileRequest.builder().rootModule(root).resolver(SourceResolver.inMemory(sources)).build();
                var artifact = compile(request);
                java.util.Collections.reverse(sources);
                sameArtifact(artifact, compile(CompileRequest.builder().rootModule(root)
                        .resolver(SourceResolver.inMemory(sources)).build()));
                try (var fixture = new Fixture(artifact)) {
                    equal(Integer.parseInt(test.get("expected")), fixture.call("run", "Fn<;I32>"), "Module graph oracle mismatch");
                }
            }
            case "bytes" -> checkBytes(Base64.getDecoder().decode(test.get("bytes")));
            case "runtime" -> checkRuntime(test);
            case "artifact" -> checkArtifact(test);
            case "io" -> {
                var output = new java.io.ByteArrayOutputStream();
                var error = new java.io.ByteArrayOutputStream();
                var environment = new io.mindspice.lyra.runtime.RuntimeIoEnvironment(
                        new java.io.ByteArrayInputStream(test.get("input").getBytes(StandardCharsets.UTF_8)),
                        output, error, StandardCharsets.UTF_8);
                try (var fixture = new Fixture(compile(test.get("source")), new io.mindspice.lyra.runtime.LoadOptions(environment))) {
                    equal(Integer.parseInt(test.get("expected")), fixture.call("run", "Fn<;I32>"), "UTF-16 length oracle");
                }
                equal(test.get("stdout"), output.toString(StandardCharsets.UTF_8), "I/O ordering, Unicode, or EOF mismatch");
                equal(test.get("stderr"), error.toString(StandardCharsets.UTF_8), "Error-stream ordering mismatch");
            }
            default -> throw new IllegalArgumentException("Unknown fuzz mode: " + test.get("mode"));
        }
    }

    private static void checkRuntime(FuzzCase test) throws Throwable {
        var artifact = compile(test.get("source"));
        try (var fixture = new Fixture(artifact)) {
            int expected = 0;
            var add = fixture.module.export("add", "Fn<I32;I32>").methodHandle();
            var read = fixture.module.export("read", "Fn<;I32>").methodHandle();
            Object closure = fixture.call("maker", "Fn<;Fn<;I32>>");
            var apply = fixture.module.export("apply", "Fn<Fn<;I32>;I32>").methodHandle();
            Class<?> functionType = apply.type().parameterType(0);
            java.util.concurrent.atomic.AtomicBoolean forgedCalled = new java.util.concurrent.atomic.AtomicBoolean();
            Object forged = java.lang.reflect.Proxy.newProxyInstance(functionType.getClassLoader(), new Class<?>[]{functionType},
                    (proxy, method, args) -> { forgedCalled.set(true); return 123; });
            for (int i = 0; i < 12; i++) {
                int delta = Integer.parseInt(test.get("delta." + i));
                switch (Integer.parseInt(test.get("action." + i))) {
                    case 0 -> { expected += delta; equal(expected, add.invokeWithArguments(delta), "Live mutation"); }
                    case 1 -> {
                        expected += delta;
                        expectCode("LYR-ARITH", () -> fixture.call("fail", "Fn<I32;I32>", delta));
                    }
                    case 2 -> {
                        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
                        Thread wrongOwner = new Thread(() -> {
                            try { add.invokeWithArguments(delta); } catch (Throwable caught) { failure.set(caught); }
                        });
                        wrongOwner.start(); wrongOwner.join(2000);
                        require(!wrongOwner.isAlive(), "Owner guard hung");
                        require(failure.get() instanceof io.mindspice.lyra.runtime.LyraThreadException, "Wrong-owner call was accepted");
                    }
                    case 3 -> {
                        expectCode("LYR-LINK", () -> apply.invokeWithArguments(forged));
                        require(!forgedCalled.get(), "Unauthenticated Java callable executed");
                    }
                    case 4 -> equal(expected, apply.invokeWithArguments(closure), "Authenticated closure lost its captured cell");
                    default -> throw new AssertionError();
                }
                equal(expected, read.invokeWithArguments(), "Guard/failure mutated unexpected state");
            }
            fixture.module.close(); fixture.module.close();
            expectCode("LYR-CLOSED", () -> add.invokeWithArguments(1));
            expectCode("LYR-CLOSED", () -> read.invokeWithArguments());
            expectCode("LYR-CLOSED", () -> apply.invokeWithArguments(closure));
            // Closing one instance must not invalidate another instance's own authority.
            try (var independent = new Fixture(artifact)) {
                equal(0, independent.call("read", "Fn<;I32>"), "Closed-instance state leaked into a new instance");
            }
        }
    }

    private static void checkArtifact(FuzzCase test) {
        var artifact = compile(test.get("source"));
        var entries = new java.util.LinkedHashMap<>(artifact.entries());
        int corruption = Integer.parseInt(test.get("corruption"));
        int offset = Integer.parseInt(test.get("offset"));
        String classEntry = entries.keySet().stream().filter(name -> name.endsWith(".class")).sorted().findFirst().orElseThrow();
        if (corruption < 2) {
            String name = corruption == 0 ? "META-INF/lyra/artifact.json" : "META-INF/lyra/debug-map.json";
            byte[] original = entries.get(name);
            entries.put(name, java.util.Arrays.copyOf(original, offset % (original.length - 2)));
        } else if (corruption == 2) {
            byte[] original = entries.get(classEntry).clone();
            original[offset % 4] ^= 1;
            entries.put(classEntry, original);
        } else if (corruption == 3) {
            entries.remove(classEntry);
        } else {
            byte[] original = entries.get(classEntry);
            entries.put(classEntry, java.util.Arrays.copyOf(original, offset % 8));
        }
        try (var ignored = io.mindspice.lyra.runtime.LyraRuntime.load(
                io.mindspice.lyra.runtime.ArtifactSource.fromEntries(artifact.metadata(), entries))) {
            throw new AssertionError("Structurally corrupt artifact was accepted: " + corruption);
        } catch (io.mindspice.lyra.runtime.LyraRuntimeException failure) {
            require(List.of("LYR-COMPAT", "LYR-LINK", "LYR-VERIFY").contains(failure.code()),
                    "Corrupt artifact escaped as " + failure);
        }
        // A failed load must not poison the runtime's next clean loading context.
        try (var ignored = new Fixture(artifact)) { }
    }

    @FunctionalInterface private interface Action { Object run() throws Throwable; }

    private static void expectCode(String code, Action action) throws Throwable {
        try { action.run(); }
        catch (io.mindspice.lyra.runtime.LyraRuntimeException failure) {
            equal(code, failure.code(), "Runtime boundary failure category");
            return;
        }
        throw new AssertionError("Runtime boundary accepted an operation requiring " + code);
    }

    static void checkBytes(byte[] bytes) {
        boolean valid;
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            valid = true;
        } catch (CharacterCodingException expected) { valid = false; }
        var result = SourceSnapshot.capture(SourceId.path("case.lyra"), PhysicalSourceKey.uri(java.net.URI.create("memory:fuzz")), bytes);
        equal(valid, result.isSuccess(), "UTF-8 decoder differential mismatch");
        if (result instanceof PhaseResult.Failure<?> failure) {
            diagnostics(failure.diagnostics(), bytes.length);
            equal("LYC-SOURCE-001", failure.diagnostics().getFirst().code().value(), "Malformed UTF-8 category");
            return;
        }
        SourceSnapshot source = ((PhaseResult.Success<SourceSnapshot>) result).value();
        var lexed = Lexer.lex(source);
        if (lexed.isSuccess()) {
            var tokens = lexed.optionalValue().orElseThrow().tokens();
            equal(TokenKind.EOF, tokens.getLast().kind(), "Lexer EOF missing");
            int end = 0;
            for (var token : tokens) {
                require(token.span().startOffset() >= end, "Overlapping/nonmonotone tokens");
                equal(token.lexeme(), source.text().substring(token.span().startOffset(), token.span().endOffset()), "Token span mismatch");
                end = token.span().endOffset();
            }
            equal(source.utf16Length(), end, "EOF does not cover complete input");
        } else diagnostics(lexed.diagnostics(), source.utf16Length());
        checkUntrustedSyntax(source.text());
    }
}
