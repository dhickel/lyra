package io.mindspice.lyra.compiler.conformance;

import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.compiler.api.SessionCompileRequest;
import io.mindspice.lyra.compiler.api.SessionCompileResult;
import io.mindspice.lyra.compiler.api.SessionFlowCertificate;
import io.mindspice.lyra.compiler.api.SourceResolver;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.ir.IrNode;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.lex.TokenKind;
import io.mindspice.lyra.compiler.semantic.DeclarationKind;
import io.mindspice.lyra.compiler.semantic.DeclarationVisibility;
import io.mindspice.lyra.compiler.semantic.flow.NominalObjectFact;
import io.mindspice.lyra.compiler.semantic.flow.NominalObjectIdentity;
import io.mindspice.lyra.compiler.semantic.flow.OwnershipWitness;
import io.mindspice.lyra.compiler.semantic.flow.ProjectionPath;
import io.mindspice.lyra.compiler.session.SessionSnapshot;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.ResolvedSource;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.runtime.LoadOptions;
import io.mindspice.lyra.runtime.LyraLinkException;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.LyraRuntimeException;
import io.mindspice.lyra.runtime.RuntimeIoEnvironment;
import io.mindspice.lyra.runtime.SessionStorageDomain;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.TreeMap;

import static io.mindspice.lyra.compiler.conformance.LanguageTestSupport.*;

/** Test-only process entry point. Fuzzed execution never runs on the Maven/JUnit owner thread. */
public final class LanguageFuzzWorker {
    static final List<String> MODES = List.of("numeric", "state", "mutation", "grammar", "modules", "bytes", "runtime", "artifact", "io", "match", "loops", "retained");
    static final int MINIMUM_CASES = MODES.size() * NumericModel.values().length;
    private static final String[] TOKENS = {"let", "@pub", "@mut", "@nil", "a", "b", "I32", "Array", "Tuple",
            "Fn", "#T", "#F", "#NIL", "0", "255U8", "18446744073709551615U64", "1.0e-99", "'x'",
            "\"😀\"", "=", ":=", "=>", "::", ":.", "->", ":", ",", ";", "|", "(", ")", "[", "]", "{", "}",
            "+", "-", "*", "/", "%", "^", "<", ">", "==", "eq?", "and", "xor", "match", "cond", "when", "_", "/*", "*/", "//\n"};

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
        TreeMap<String, Integer> retainedOps = new TreeMap<>();
        TreeMap<String, Integer> retainedProfiles = new TreeMap<>();
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
                case "retained" -> {
                    int retainedOrdinal = index / MODES.size();
                    for (String op : RetainedNominalModel.opsAt(retainedOrdinal)) {
                        retainedOps.merge(op, 1, Integer::sum);
                    }
                    retainedProfiles.merge(RetainedNominalModel.profileAt(retainedOrdinal), 1, Integer::sum);
                    yield retained(random, retainedOrdinal);
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
        require(retainedOps.keySet().containsAll(RetainedNominalModel.ALL_OPS),
                "Campaign omitted a retained nominal operation");
        require(retainedProfiles.keySet().containsAll(RetainedNominalModel.PROFILES),
                "Campaign omitted a retained nominal profile");
        int retainedTotal = retainedOps.values().stream().mapToInt(Integer::intValue).sum();
        StringBuilder summary = new StringBuilder("seed=" + seed + " cases=" + count + "\n"
                + coverage + "\nnumeric=" + numericCoverage + "\nretained=" + retainedOps
                + "\n" + retainedProfiles + "\nretained.ops=" + retainedTotal + "\n");
        retainedOps.forEach((op, total) -> summary.append("retained.op.").append(op).append('=').append(total).append('\n'));
        retainedProfiles.forEach((profile, total) -> summary.append("retained.profile.").append(profile)
                .append('=').append(total).append('\n'));
        Files.writeString(directory.resolve("summary.txt"), summary);
        System.out.println("FUZZ PASS seed=" + seed + " cases=" + count + " " + coverage
                + " numeric=" + numericCoverage + " retained=" + retainedOps + " retainedOps=" + retainedTotal);
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
        String iter = bracket ? "iter[range " + callback + "]" : "(iter range " + callback + ")";
        int count = random.nextInt(0, 21);
        String source = "let @pub run :Fn<;I64> = (=> || { let range = " + range
                + " let @mut sum :I64 = 0 " + iter
                + " let @mut n :I64 = 0 let @mut tests :I64 = 0 "
                + "while[|| { tests := (+ tests 1) (< n " + count + ") } || { n := (+ n 1) }] "
                + "(+ (* sum 10000) (* tests 100) n) })";
        FuzzCase result = new FuzzCase("loops", source);
        result.put("expected", sum * 10000L + (count + 1) * 100L + count);
        result.put("actions", actions);
        return result;
    }

    /**
     * Renders loop bounds through the adjacent bare negative literal spelling so the
     * bounded campaign exercises it, including the signed minimum magnitude.  The
     * arithmetic model is unchanged because the language normalizes the adjacent
     * minus to the same unary-minus operation.
     */
    private static String signedLoopLiteral(int value) {
        return value < 0 ? "-" + (-value) : Integer.toString(value);
    }

    /** Number of completed mode rotations before this index; retained cases rotate profiles per ordinal. */
    static int retainedOrdinalAt(int index) {
        if (!modeAt(index).equals("retained")) throw new IllegalArgumentException("Not a retained case index: " + index);
        return index / MODES.size();
    }

    static FuzzCase retained(SplittableRandom random, int retainedOrdinal) {
        RetainedNominalModel.Plan plan = RetainedNominalModel.generate(random, retainedOrdinal);
        FuzzCase test = new FuzzCase("retained", plan.producer());
        test.put("retained.profile", plan.profile());
        test.put("retained.ordinal", retainedOrdinal);
        test.put("retained.memberCount", plan.members().size());
        for (int i = 0; i < plan.members().size(); i++) {
            RetainedNominalModel.MemberExpectation member = plan.members().get(i);
            test.put("retained.member." + i, member.nominal() + "|" + member.member()
                    + "|" + member.variant() + "|" + member.structure());
        }
        test.put("retained.stepCount", plan.steps().size());
        for (int i = 0; i < plan.steps().size(); i++) {
            RetainedNominalModel.Step step = plan.steps().get(i);
            test.put("retained.step." + i + ".source", step.source());
            test.put("retained.step." + i + ".outcome", step.outcome());
            test.put("retained.step." + i + ".type", step.type());
            test.put("retained.step." + i + ".expected", step.expected());
            test.put("retained.step." + i + ".opCount", step.ops().size());
            for (int j = 0; j < step.ops().size(); j++) {
                test.put("retained.step." + i + ".op." + j, step.ops().get(j));
            }
        }
        test.put("retained.output", plan.output());
        List<String> ops = plan.ops();
        test.put("retained.opCount", ops.size());
        for (int i = 0; i < ops.size(); i++) test.put("retained.op." + i, ops.get(i));
        return test;
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
            String matchBody = next + terminal + ":.value " + next + terminal
                    + ":.value when " + next + terminal + "::ready[] -> " + result + " _ -> (- 1)";
            String match = random.nextBoolean() ? "(match " + matchBody + ")" : "match[" + matchBody + "]";
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
            case 9 -> "(match " + a + " " + b + " -> " + a + " _ -> " + b + ")";
            case 10 -> "match[_ " + a + " -> " + b + " _]";
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
            case "retained" -> runRetained(test);
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

    /**
     * Drives one retained-nominal case through the real session storage domain:
     * producer compile/execute/publication, certificate checks against the
     * independent model, then each modeled construction/observation generation.
     * Expected values and failure codes come only from the saved replay data.
     */
    private static void runRetained(FuzzCase test) throws Throwable {
        List<RetainedNominalModel.MemberExpectation> members = new ArrayList<>();
        int memberCount = Integer.parseInt(test.get("retained.memberCount"));
        for (int i = 0; i < memberCount; i++) {
            String[] parts = test.get("retained.member." + i).split("\\|", -1);
            members.add(new RetainedNominalModel.MemberExpectation(
                    parts[0], parts[1], parts[2], Integer.parseInt(parts[3])));
        }
        List<RetainedNominalModel.Step> steps = new ArrayList<>();
        int stepCount = Integer.parseInt(test.get("retained.stepCount"));
        for (int i = 0; i < stepCount; i++) {
            List<String> stepOps = new ArrayList<>();
            int stepOpCount = Integer.parseInt(test.get("retained.step." + i + ".opCount"));
            for (int j = 0; j < stepOpCount; j++) {
                stepOps.add(test.get("retained.step." + i + ".op." + j));
            }
            steps.add(new RetainedNominalModel.Step(
                    test.get("retained.step." + i + ".source"),
                    test.get("retained.step." + i + ".outcome"),
                    test.get("retained.step." + i + ".type"),
                    test.get("retained.step." + i + ".expected"), stepOps));
        }
        List<String> expectedOps = new ArrayList<>();
        int opCount = Integer.parseInt(test.get("retained.opCount"));
        for (int i = 0; i < opCount; i++) expectedOps.add(test.get("retained.op." + i));
        require(!expectedOps.isEmpty(), "Retained case carried no operation inventory");
        require(test.get("retained.profile") != null, "Retained case lost its profile identity");
        List<String> canonicalOps = RetainedNominalModel.opsAt(Integer.parseInt(test.get("retained.ordinal")));
        require(canonicalOps.equals(expectedOps),
                "Retained operation inventory drifted from the deterministic model rotation");
        boolean forgeChecks = test.get("retained.profile").equals("values");
        int executedOps = 0;

        var output = new java.io.ByteArrayOutputStream();
        var environment = new RuntimeIoEnvironment(
                java.io.InputStream.nullInputStream(), output, output, StandardCharsets.UTF_8);
        try (var domain = new SessionStorageDomain()) {
            var workspace = new RetainedWorkspace();
            long revision = 0;
            var producer = compileSessionExpectSuccess(
                    "retained-producer.lyra", test.get("source"), SessionSnapshot.empty());
            executedOps += checkCertificate(producer, members, forgeChecks);
            var producerLoaded = loadRetained(domain, workspace, revision, producer, environment);
            var producerPrepared = io.mindspice.lyra.runtime.LyraRuntime.prepareSubmission(producerLoaded);
            io.mindspice.lyra.runtime.LyraRuntime.executeSubmission(producerPrepared);
            stageRetained(domain, workspace, revision, producer, producerPrepared);
            revision++;
            var snapshot = producer.stagedSnapshot();
            for (int i = 0; i < steps.size(); i++) {
                RetainedNominalModel.Step step = steps.get(i);
                var compiled = compileSessionExpectSuccess(
                        "retained-step-" + i + ".lyra", step.source(), snapshot);
                if (step.outcome().equals("failure")) {
                    var loaded = loadRetained(domain, workspace, revision, compiled, environment);
                    var module = io.mindspice.lyra.runtime.LyraRuntime.prepareSubmission(loaded);
                    try {
                        io.mindspice.lyra.runtime.LyraRuntime.executeSubmission(module);
                        throw new AssertionError("Expected " + step.expected()
                                + " but the retained submission executed: " + step.source());
                    } catch (LyraRuntimeException failure) {
                        equal(step.expected(), failure.code(),
                                "Retained runtime failure category, step " + i + ": " + step.source());
                    }
                    // A failed submission publishes nothing and never advances the revision.
                } else {
                    if (forgeChecks && i == 0) executedOps += forgedLinkCheck(domain, workspace, revision, compiled);
                    var loaded = loadRetained(domain, workspace, revision, compiled, environment);
                    var module = io.mindspice.lyra.runtime.LyraRuntime.prepareSubmission(loaded);
                    io.mindspice.lyra.runtime.LyraRuntime.executeSubmission(module);
                    stageRetained(domain, workspace, revision, compiled, module);
                    revision++;
                    snapshot = compiled.stagedSnapshot();
                    if (step.outcome().equals("value")) {
                        Object actual = io.mindspice.lyra.runtime.LyraRuntime.readSubmissionResult(
                                module, io.mindspice.lyra.runtime.LyraType.parse(step.type()));
                        equal(parseExpected(step.type(), step.expected()), actual,
                                "Retained observation oracle mismatch, step " + i + "\n" + step.source());
                    }
                }
                executedOps += step.ops().size();
            }
            if (!test.get("retained.output").isEmpty()) {
                equal(test.get("retained.output"), output.toString(StandardCharsets.UTF_8),
                        "Retained construction intrinsic output mismatch");
            }
            require(executedOps == expectedOps.size(), "Retained case executed " + executedOps
                    + " operations but the model inventory lists " + expectedOps.size());
        }
    }

    private record RetainedWorkspace(
            Map<Long, SessionStorageDomain.Binding> bindings,
            Map<Long, SessionStorageDomain.NominalFactory> factories) {
        RetainedWorkspace() { this(new LinkedHashMap<>(), new LinkedHashMap<>()); }
    }

    private static SessionCompileResult.Success compileSessionExpectSuccess(
            String label, String source, SessionSnapshot snapshot) {
        var result = LyraCompiler.compileSession(new SessionCompileRequest(label, source, snapshot));
        if (!(result instanceof SessionCompileResult.Success success)) {
            throw new AssertionError("Expected successful session compilation of " + label
                    + ": " + result.diagnostics());
        }
        require(success.diagnostics().stream().noneMatch(d -> d.severity().isError()),
                "Successful session compilation carried errors: " + success.diagnostics());
        return success;
    }

    /** Mirrors the REPL's exact storage and retained-factory requirement derivation. */
    private static List<SessionStorageDomain.Requirement> dataRequirements(SessionCompileResult.Success compiled) {
        LinkedHashMap<Long, SessionStorageDomain.Requirement> index = new LinkedHashMap<>();
        compiled.typedIr().declarations().stream()
                .flatMap(declaration -> declaration.externalBinding().stream())
                .map(binding -> new SessionStorageDomain.Requirement(
                        binding.declarationId().ordinal(), binding.storageIdentity()
                        .map(value -> value.ordinal()).orElse(-1L),
                        binding.name(), binding.type().canonicalSpelling(),
                        binding.allowsRebinding()))
                .forEach(required -> index.put(required.id(), required));
        compiled.typedIr().sessionExecution().orElseThrow().externalAccesses().stream()
                .map(access -> {
                    var declaration = access.target().declaration();
                    boolean mutable = declaration.contract().orElseThrow().isMutable();
                    return new SessionStorageDomain.Requirement(
                            declaration.id().ordinal(), mutable ? declaration.id().ordinal() : -1L,
                            declaration.name(), declaration.contract().orElseThrow()
                            .valueType().canonicalSpelling(), access.writableFacade());
                })
                .forEach(required -> index.putIfAbsent(required.id(), required));
        return List.copyOf(index.values());
    }

    private static List<SessionStorageDomain.NominalFactoryRequirement> factoryRequirements(
            SessionCompileResult.Success compiled) {
        var currentNominals = compiled.typedIr().modules().stream()
                .flatMap(irModule -> irModule.body().forms().stream())
                .filter(IrNode.NominalDeclaration.class::isInstance)
                .map(IrNode.NominalDeclaration.class::cast)
                .collect(java.util.stream.Collectors.toMap(
                        value -> value.declarationId().ordinal(), value -> value));
        return compiled.resolvedGraph().nominals().stream()
                .filter(nominal -> !currentNominals.containsKey(nominal.declaration().ordinal()))
                .map(nominal -> new SessionStorageDomain.NominalFactoryRequirement(
                        nominal.declaration().ordinal(),
                        nominal.schema().type().canonicalSpelling()))
                .toList();
    }

    private static io.mindspice.lyra.runtime.LoadedArtifact loadRetained(
            SessionStorageDomain domain, RetainedWorkspace workspace, long revision,
            SessionCompileResult.Success compiled, RuntimeIoEnvironment environment) {
        var options = LoadOptions.defaults().withIoEnvironment(environment);
        var requirements = dataRequirements(compiled);
        var capabilities = requirements.stream()
                .map(required -> workspace.bindings().get(required.id())).toList();
        var factories = factoryRequirements(compiled);
        var factoryCapabilities = factories.stream()
                .map(required -> workspace.factories().get(required.declarationId())).toList();
        var linkage = domain.link(compiled.artifact(), revision, requirements, capabilities,
                factories, factoryCapabilities);
        return LyraRuntime.loadSubmission(compiled.artifact(), options, linkage);
    }

    /** Mirrors the REPL's staged publication of root bindings and retained factories. */
    private static void stageRetained(SessionStorageDomain domain, RetainedWorkspace workspace,
                                      long revision, SessionCompileResult.Success compiled,
                                      io.mindspice.lyra.runtime.ModuleHandle module) {
        Map<Long, SessionStorageDomain.Binding> stagedStorage = new LinkedHashMap<>();
        for (var declaration : compiled.typedIr().declarations()) {
            if (declaration.kind() != DeclarationKind.LET || declaration.contract().isEmpty()
                    || !declaration.scopeId().equals(compiled.resolvedGraph()
                    .module(declaration.moduleId()).orElseThrow().rootScope())
                    || declaration.imported()) {
                continue;
            }
            boolean stagedRoot = compiled.stagedDeclarations().contains(declaration.id());
            boolean newProducer = compiled.executionPlan().module(declaration.moduleId())
                    .filter(work -> work.isNew() && !work.scratch()).isPresent()
                    && declaration.visibility() == DeclarationVisibility.PUBLIC;
            if (!stagedRoot && !newProducer) continue;
            var required = new SessionStorageDomain.Requirement(
                    declaration.id().ordinal(), declaration.isMutable()
                    ? declaration.id().ordinal() : -1L,
                    declaration.name(), declaration.contract().orElseThrow()
                    .valueType().canonicalSpelling(), declaration.isMutable());
            stagedStorage.put(required.id(), domain.register(module,
                    toRuntimeModuleId(declaration.moduleId()), required));
        }
        var currentNominals = compiled.typedIr().modules().stream()
                .flatMap(irModule -> irModule.body().forms().stream())
                .filter(IrNode.NominalDeclaration.class::isInstance)
                .map(IrNode.NominalDeclaration.class::cast)
                .collect(java.util.stream.Collectors.toMap(
                        value -> value.declarationId().ordinal(), value -> value));
        Map<Long, SessionStorageDomain.NominalFactory> stagedFactories = new LinkedHashMap<>();
        for (var nominal : currentNominals.values()) {
            var required = new SessionStorageDomain.NominalFactoryRequirement(
                    nominal.declarationId().ordinal(),
                    nominal.schema().type().canonicalSpelling());
            stagedFactories.put(required.declarationId(), domain.registerNominalFactory(module,
                    toRuntimeModuleId(nominal.schema().type().id().module().moduleId()), required));
        }
        domain.commit(revision, List.copyOf(stagedStorage.values()),
                List.copyOf(stagedFactories.values()));
        workspace.bindings().putAll(stagedStorage);
        workspace.factories().putAll(stagedFactories);
    }

    private static io.mindspice.lyra.runtime.ModuleId toRuntimeModuleId(
            io.mindspice.lyra.compiler.source.ModuleId moduleId) {
        return moduleId.isUri()
                ? io.mindspice.lyra.runtime.ModuleId.uri(moduleId.asUri())
                : io.mindspice.lyra.runtime.ModuleId.path(moduleId.value());
    }

    /** Independent certificate verification: guard silence, exact inventories, forged routes and inventories. */
    private static int checkCertificate(SessionCompileResult.Success producer,
                                        List<RetainedNominalModel.MemberExpectation> members,
                                        boolean forgeChecks) {
        var certificate = producer.flowCertificate();
        require(SessionFlowCertificate.retainedInitializerDiagnostic(producer.typedGraph()).isEmpty(),
                "Fail-closed retained preflight guard rejected a legal generated producer");
        var retained = certificate.retainedNominals();
        require(!members.isEmpty(), "Retained case carried no member expectations");
        for (var member : members) {
            var nominal = retained.values().stream()
                    .filter(value -> value.name().equals(member.nominal())).findFirst().orElse(null);
            require(nominal != null, "Certificate omitted nominal " + member.nominal());
            for (int index = 0; index < nominal.memberInitializers().size(); index++) {
                equal(nominal.nominal().schema().members().get(index).hasInitializer(),
                        nominal.memberInitializer(index).isPresent(),
                        "Retained initializer inventory drifted for " + member.nominal());
            }
            var transfer = memberTransfer(nominal, member.member()).orElseThrow(
                    () -> new AssertionError("Certificate omitted transfer for "
                            + member.nominal() + "." + member.member()));
            equal(member.variant(), transfer.getClass().getSimpleName(),
                    "Retained transfer variant mismatch for " + member.nominal() + "." + member.member());
            equal(member.structure(), structureOf(transfer),
                    "Retained transfer structure mismatch for " + member.nominal() + "." + member.member());
        }
        // Forged or mismatched certification evidence must never certify.
        int routeForgeChecks = 0;
        if (forgeChecks) {
            var construction = certificate.callableSummaries().orderedSummaries().stream()
                    .flatMap(summary -> summary.callReferences().stream())
                    .map(certificate::retainedConstruction).flatMap(Optional::stream)
                    .findFirst().orElse(null);
            if (construction == null) {
                throw new AssertionError("Retained forge profile carried no retained construction evidence");
            }
            var root = new NominalObjectFact(
                    new NominalObjectIdentity(construction.moduleId(), construction.site(),
                            construction.nominalType()),
                    ProjectionPath.root(), OwnershipWitness.local(construction.moduleId(),
                            construction.allocation(), construction.scopeId(),
                            construction.call().span()).withOriginSite(construction.site()));
            require(certificate.certifiesObject(root), "Exact retained object route was not certified");
            require(certificate.certifiesObject(root.prefixedBy(ProjectionPath.tupleMember(0))),
                    "Exact retained tuple-member route was not certified");
            require(!certificate.certifiesObject(root.prefixedBy(ProjectionPath.tupleMember(99))),
                    "Forged retained tuple-member route was certified");
            require(!certificate.certifiesObject(root.prefixedBy(ProjectionPath.arrayElement(0))),
                    "Forged retained array route was certified");
            require(!certificate.certifiesObject(root.prefixedBy(ProjectionPath.unknownArrayElement())),
                    "Forged retained unknown-element route was certified");
            routeForgeChecks = 1;
        }
        var box = retained.values().stream()
                .filter(value -> value.memberInitializers().stream().anyMatch(Optional::isPresent))
                .findFirst().orElse(null);
        require(box != null, "Retained forge profile carried no initializer-bearing nominal");
        int inventoryForgeChecks = 0;
        if (forgeChecks) {
            requireIllegalInventory(() -> new SessionFlowCertificate.RetainedNominal(
                    box.name(), box.nominal(), box.visibility(), box.constructorLambda(), List.of()));
            List<Optional<SessionFlowCertificate.RetainedInitializerTransfer>> flipped =
                    new ArrayList<>(box.memberInitializers());
            int present = 0;
            while (flipped.get(present).isEmpty()) present++;
            flipped.set(present, Optional.empty());
            requireIllegalInventory(() -> new SessionFlowCertificate.RetainedNominal(
                    box.name(), box.nominal(), box.visibility(), box.constructorLambda(), flipped));
            var foreign = producer.typedGraph().declarations().stream()
                    .filter(declaration -> declaration.name().equals("shared"))
                    .findFirst()
                    .flatMap(declaration -> certificate.value(declaration.id())
                            .map(value -> new SessionFlowCertificate.RetainedInitializerTransfer.Value(
                                    declaration.contract().orElseThrow().valueType(), value)))
                    .orElse(null);
            if (foreign != null) {
                int presentIndex = present;
                var forgedValue = foreign;
                var boxValue = box;
                requireIllegalInventory(() -> new SessionFlowCertificate.RetainedNominal(
                        boxValue.name(), boxValue.nominal(), boxValue.visibility(),
                        boxValue.constructorLambda(),
                        wrongTypedInventory(boxValue, presentIndex, forgedValue)));
            }
            inventoryForgeChecks = 1;
        }
        // guard-silent + inventory-exact, one per member verification, plus the executed forge checks
        return 2 + members.size() + routeForgeChecks + inventoryForgeChecks;
    }

    private static List<Optional<SessionFlowCertificate.RetainedInitializerTransfer>> wrongTypedInventory(
            SessionFlowCertificate.RetainedNominal nominal, int index,
            SessionFlowCertificate.RetainedInitializerTransfer forged) {
        List<Optional<SessionFlowCertificate.RetainedInitializerTransfer>> wrongTyped =
                new ArrayList<>(nominal.memberInitializers());
        wrongTyped.set(index, Optional.of(forged));
        return wrongTyped;
    }

    private static void requireIllegalInventory(Runnable forged) {
        try {
            forged.run();
            throw new AssertionError("Forged retained nominal inventory was accepted");
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage() != null && !expected.getMessage().isBlank(),
                    "Forged inventory rejection lost its reason");
        }
    }

    private static int structureOf(SessionFlowCertificate.RetainedInitializerTransfer transfer) {
        return switch (transfer) {
            case SessionFlowCertificate.RetainedInitializerTransfer.Call call -> call.arguments().size();
            case SessionFlowCertificate.RetainedInitializerTransfer.CallableCall call -> call.arguments().size();
            case SessionFlowCertificate.RetainedInitializerTransfer.Composite composite -> composite.elements().size();
            case SessionFlowCertificate.RetainedInitializerTransfer.Apply apply -> apply.operands().size();
            case SessionFlowCertificate.RetainedInitializerTransfer.Alternative alternative -> alternative.branches().size();
            case SessionFlowCertificate.RetainedInitializerTransfer.Sequence sequence -> sequence.steps().size();
            case SessionFlowCertificate.RetainedInitializerTransfer.Project project -> project.index().isPresent() ? 1 : 0;
            case SessionFlowCertificate.RetainedInitializerTransfer.Construct construct -> construct.arguments().size();
            default -> 0;
        };
    }

    /** Rejects one mismatched data or factory capability before the real construction link. */
    private static int forgedLinkCheck(SessionStorageDomain domain, RetainedWorkspace workspace,
                                       long revision, SessionCompileResult.Success compiled) {
        var requirements = dataRequirements(compiled);
        var capabilities = requirements.stream()
                .map(required -> workspace.bindings().get(required.id())).toList();
        var factories = factoryRequirements(compiled);
        var factoryCapabilities = factories.stream()
                .map(required -> workspace.factories().get(required.declarationId())).toList();
        if (!requirements.isEmpty()) {
            var first = requirements.getFirst();
            var forged = new SessionStorageDomain.Requirement(first.id(), first.storageIdentity() + 1,
                    first.name(), first.type(), first.writable());
            List<SessionStorageDomain.Requirement> forgedList = new ArrayList<>(requirements);
            forgedList.set(0, forged);
            try {
                domain.link(compiled.artifact(), revision, forgedList, capabilities,
                        factories, factoryCapabilities);
                throw new AssertionError("Mismatched storage identity was accepted");
            } catch (LyraLinkException expected) {
                equal("LYR-LINK", expected.code(), "Mismatched storage link failure category");
            }
        } else if (!factories.isEmpty()) {
            var first = factories.getFirst();
            var forged = new SessionStorageDomain.NominalFactoryRequirement(
                    first.declarationId() + 1, first.type());
            List<SessionStorageDomain.NominalFactoryRequirement> forgedFactories =
                    new ArrayList<>(factories);
            forgedFactories.set(0, forged);
            try {
                domain.link(compiled.artifact(), revision, requirements, capabilities,
                        forgedFactories, factoryCapabilities);
                throw new AssertionError("Mismatched nominal factory identity was accepted");
            } catch (LyraLinkException expected) {
                equal("LYR-LINK", expected.code(), "Mismatched factory link failure category");
            }
        } else {
            require(false, "Retained construction exposed no data or factory requirement to forge");
        }
        return 1;
    }

    private static Optional<SessionFlowCertificate.RetainedInitializerTransfer> memberTransfer(
            SessionFlowCertificate.RetainedNominal nominal, String memberName) {
        for (int index = 0; index < nominal.nominal().members().size(); index++) {
            if (memberName.equals(nominal.nominal().schema().members().get(index).name())) {
                return nominal.memberInitializer(index);
            }
        }
        throw new IllegalArgumentException("Unknown member: " + memberName);
    }

    private static Object parseExpected(String type, String expected) {
        return switch (type) {
            case "I32" -> Integer.parseInt(expected);
            case "I64" -> Long.parseLong(expected);
            case "Bool" -> Boolean.parseBoolean(expected);
            case "Char" -> expected.charAt(0);
            case "String" -> expected;
            case "Unit" -> io.mindspice.lyra.runtime.LyraUnit.INSTANCE;
            default -> throw new IllegalArgumentException("Unsupported retained observation type: " + type);
        };
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
