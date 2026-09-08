package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.api.SourceResolver;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ResolvedSource;
import io.mindspice.lyra.runtime.RuntimeIoEnvironment;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One common source corpus exercised by every Phase-13 surface: the local
 * Java API, the plain console, the rich JLine console, the remote standalone
 * v2 adapter, and a live attached application.  The corpus covers persistent
 * counters and private replacement, source-local and imported higher-order
 * functions, recursive modules (including cross-module import cycles),
 * arrays/tuples/callable aggregates, real std->io, duplicate imports,
 * ownership diagnostics, old/new reload references and delayed source errors
 * with exact UTF-16 span fidelity.
 */
public final class CrossSurfaceCorpus {
    private CrossSurfaceCorpus() {
    }

    /* ---- module sources (revision 1 unless marked otherwise) ---- */

    public static final String COUNTER_V1 = "import std->io io->::println[\"counter-init\"]\n"
            + "let @mut hidden :I32 = 0\n"
            + "let @pub @mut visible :I32 = 10\n"
            + "let @pub bump :Fn<;I32> = (=> || { hidden := (+ hidden 1) hidden })\n"
            + "let @pub read :Fn<;I32> = (=> || hidden)";

    public static final String COUNTER_V2 = "import std->io io->::println[\"counter-init-2\"]\n"
            + "let @mut hidden :I32 = 100\n"
            + "let @pub @mut visible :I32 = 20\n"
            + "let @pub bump :Fn<;I32> = (=> || { hidden := (+ hidden 10) hidden })\n"
            + "let @pub read :Fn<;I32> = (=> || hidden)";

    public static final String HIGHER = "let @pub apply :Fn<Fn<I32;I32>,I32;I32> = (=> |f x| (f x))\n"
            + "let @pub compose :Fn<Fn<I32;I32>,Fn<I32;I32>;Fn<I32;I32>> = "
            + "(=> |f g| (=> :I32 |x| (f (g x))))\n"
            + "let @pub makeAdder :Fn<I32;Fn<I32;I32>> = (=> |n| (=> :I32 |x| (+ x n)))";

    public static final String REC = "let @pub fact :Fn<I32;I32> = "
            + "(=> |n| ((== n 0) -> 1 : { let rest :I32 = ::fact[(- n 1)] (* n rest) }))\n"
            + "let @pub ping :Fn<I32;I32> = "
            + "(=> |n| ((== n 0) -> 0 : { let rest :I32 = ::pong[(- n 1)] rest }))\n"
            + "let @pub pong :Fn<I32;I32> = "
            + "(=> |n| ((== n 0) -> 0 : { let rest :I32 = ::ping[(- n 1)] rest }))";

    public static final String CYCLEA = "import cycleb\n"
            + "let @pub ping :Fn<I32;I32> = "
            + "(=> |n| ((== n 0) -> 0 : { let rest :I32 = cycleb->::pong[(- n 1)] rest }))";

    public static final String CYCLEB = "import cyclea\n"
            + "let @pub pong :Fn<I32;I32> = "
            + "(=> |n| ((== n 0) -> 0 : { let rest :I32 = cyclea->::ping[(- n 1)] rest }))";

    public static final String VALUES = "let @pub pair :Tuple<I32,String> = Tuple[1 \"one\"]\n"
            + "let @pub items :Array<I32> = Array<I32>[1 2 3]\n"
            + "let @pub callables :Array<Fn<I32;I32>> = "
            + "Array<Fn<I32;I32>>[(=> :I32 |x| (+ x 1))]\n"
            + "let @pub @mut counter :I32 = 5\n"
            + "let @pub bump :Fn<;I32> = (=> || { counter := (+ counter 1) counter })";

    public static final String ERRORS_V1 = "let @pub fail :Fn<I32;I32> = (=> |x| (% 11 x))\n"
            + "let @pub late :Fn<;I32> = (=> || ::fail[0])";

    public static final String ERRORS_V2 = "/* 😀 revision two */ let @pub fail :Fn<I32;I32> = (=> |x| (% 22 x))\n"
            + "let @pub late :Fn<;I32> = (=> || ::fail[0])";

    public static final String BASE = "let @pub value :I32 = 1";

    public static final String TOP_V1 = "import base\n"
            + "let @pub value :I32 = base->:.value\n"
            + "let @pub read :Fn<;I32> = (=> || base->:.value)";

    public static final String TOP_V2 = "import added\n"
            + "let @pub value :I32 = added->:.value\n"
            + "let @pub read :Fn<;I32> = (=> || added->:.value)";

    public static final String ADDED = "let @pub value :I32 = 9";

    /** 200 elements; default snapshot budgets truncate the display at 100. */
    public static final String LARGE_ARRAY_SOURCE = largeArraySource();

    private static String largeArraySource() {
        StringBuilder builder = new StringBuilder("Array<I32>[");
        for (int index = 0; index < 200; index++) {
            if (index > 0) {
                builder.append(' ');
            }
            builder.append(index);
        }
        return builder.append(']').toString();
    }

    public static final String PROGRAM_INPUT = "prog-input\n";

    /* ---- the corpus program ---- */

    public enum Expectation {
        SUCCESS_NO_VALUE,
        SUCCESS_SCALAR,
        SUCCESS_FUNCTION,
        SUCCESS_AGGREGATE_TRUNCATED,
        OWNERSHIP_DIAGNOSTIC,
        RUNTIME_DIVISION
    }

    public record Step(String label, String source, Expectation expectation, String expected) {
        public Step {
            label = Objects.requireNonNull(label, "label");
            source = Objects.requireNonNull(source, "source");
            expectation = Objects.requireNonNull(expectation, "expectation");
            expected = Objects.requireNonNull(expected, "expected");
        }
    }

    /** Step source beginning with this marker mutates a revisioned module. */
    public static final String MUTATE_PREFIX = "__mutate__:";

    /** Step source beginning with this marker reloads the named module or alias. */
    public static final String RELOAD_PREFIX = "__reload__:";

    public static boolean isMutation(Step step) {
        return step.source().startsWith(MUTATE_PREFIX);
    }

    public static boolean isReload(Step step) {
        return step.source().startsWith(RELOAD_PREFIX);
    }

    public static String mutation(Step step) {
        return step.source().substring(MUTATE_PREFIX.length());
    }

    public static String reloadTarget(Step step) {
        return step.source().substring(RELOAD_PREFIX.length());
    }

    public static List<Step> program() {
        return List.of(
                new Step("import-counter", "import counter", Expectation.SUCCESS_NO_VALUE, ""),
                new Step("bump-1", "counter->::bump[]", Expectation.SUCCESS_SCALAR, "1"),
                new Step("bump-2", "counter->::bump[]", Expectation.SUCCESS_SCALAR, "2"),
                new Step("duplicate-imports", "import counter import counter->{bump as bumpAgain}",
                        Expectation.SUCCESS_NO_VALUE, ""),
                new Step("bump-again", "(bumpAgain)", Expectation.SUCCESS_SCALAR, "3"),
                new Step("capture-old", "import counter->{read as readOld bump as oldBump}",
                        Expectation.SUCCESS_NO_VALUE, ""),
                new Step("counter-cell", "let @mut count :I32 = 1 count := 41",
                        Expectation.SUCCESS_NO_VALUE, ""),
                new Step("reader", "let reader :Fn<;I32> = (=> || count)",
                        Expectation.SUCCESS_NO_VALUE, ""),
                new Step("reader-41", "(reader)", Expectation.SUCCESS_SCALAR, "41"),
                new Step("private-replacement", "let count :String = \"replacement\"",
                        Expectation.SUCCESS_NO_VALUE, ""),
                new Step("old-identity", "(reader)", Expectation.SUCCESS_SCALAR, "41"),
                new Step("new-identity", "count", Expectation.SUCCESS_SCALAR, "replacement"),
                new Step("imported-hof", "import higher (higher->:.apply (=> |x| (* x 3)) 7)",
                        Expectation.SUCCESS_SCALAR, "21"),
                new Step("hof-compose",
                        "let triple :Fn<I32;I32> = (=> :I32 |x| (* x 3)) "
                                + "((higher->:.compose triple triple) 2)",
                        Expectation.SUCCESS_SCALAR, "18"),
                new Step("hof-return", "{ let adder :Fn<I32;I32> = higher->::makeAdder[10] (adder 5) }",
                        Expectation.SUCCESS_SCALAR, "15"),
                new Step("recursive-module", "import rec rec->::fact[6]",
                        Expectation.SUCCESS_SCALAR, "720"),
                new Step("mutual-recursion", "rec->::ping[3]", Expectation.SUCCESS_SCALAR, "0"),
                new Step("recursive-module-cycle", "import cyclea cyclea->::ping[3]",
                        Expectation.SUCCESS_SCALAR, "0"),
                new Step("post-cycle-pin", "import values values->:.items[0]",
                        Expectation.SUCCESS_SCALAR, "1"),
                new Step("tuple-field", "values->:.pair:.1", Expectation.SUCCESS_SCALAR, "one"),
                new Step("callable-in-array", "values->:.callables[0]",
                        Expectation.SUCCESS_FUNCTION, ""),
                new Step("callable-in-array-call", "(values->:.callables[0] 5)",
                        Expectation.SUCCESS_SCALAR, "6"),
                new Step("module-state-1", "values->::bump[]", Expectation.SUCCESS_SCALAR, "6"),
                new Step("module-state-2", "values->::bump[]", Expectation.SUCCESS_SCALAR, "7"),
                new Step("ownership-direct", "values->:.items[0] := 9",
                        Expectation.OWNERSHIP_DIAGNOSTIC, "LYC-RESOLVE-022"),
                new Step("ownership-alias", "let @mut alias :Array<I32> = values->:.items alias[0] := 9",
                        Expectation.OWNERSHIP_DIAGNOSTIC, "LYC-RESOLVE-022"),
                new Step("std-io-print", "import std->io io->::println[\"héllo 😀\"]",
                        Expectation.SUCCESS_NO_VALUE, ""),
                new Step("std-io-read", "import std->io io->::readLine[]",
                        Expectation.SUCCESS_SCALAR, "prog-input"),
                new Step("import-errors", "import errors", Expectation.SUCCESS_NO_VALUE, ""),
                new Step("capture-old-errors", "import errors->{fail as oldFail}",
                        Expectation.SUCCESS_NO_VALUE, ""),
                new Step("delayed-failure", "errors->::late[]",
                        Expectation.RUNTIME_DIVISION, "(% 11 x)"),
                new Step("mutate-counter", MUTATE_PREFIX + "counter->v2",
                        Expectation.SUCCESS_NO_VALUE, ""),
                new Step("reload-counter", RELOAD_PREFIX + "counter",
                        Expectation.SUCCESS_NO_VALUE, ""),
                new Step("reload-visible", "counter->:.visible", Expectation.SUCCESS_SCALAR, "20"),
                new Step("reload-old-reader", "(readOld)", Expectation.SUCCESS_SCALAR, "3"),
                new Step("reload-new-reader", "counter->::read[]", Expectation.SUCCESS_SCALAR, "100"),
                new Step("reload-old-bump", "(oldBump)", Expectation.SUCCESS_SCALAR, "4"),
                new Step("reload-new-bump", "counter->::bump[]", Expectation.SUCCESS_SCALAR, "110"),
                new Step("distinct-producers", "(eq? oldBump counter->:.bump)",
                        Expectation.SUCCESS_SCALAR, "false"),
                new Step("mutate-errors", MUTATE_PREFIX + "errors->v2",
                        Expectation.SUCCESS_NO_VALUE, ""),
                new Step("reload-errors", RELOAD_PREFIX + "errors",
                        Expectation.SUCCESS_NO_VALUE, ""),
                new Step("old-delayed-failure", "(oldFail 0)",
                        Expectation.RUNTIME_DIVISION, "(% 11 x)"),
                new Step("new-delayed-failure", "errors->::late[]",
                        Expectation.RUNTIME_DIVISION, "(% 22 x)"),
                new Step("import-top",
                        "import top import top->{read as oldTopRead} top->::read[]",
                        Expectation.SUCCESS_SCALAR, "1"),
                new Step("mutate-top", MUTATE_PREFIX + "top->v2",
                        Expectation.SUCCESS_NO_VALUE, ""),
                new Step("reload-top", RELOAD_PREFIX + "top",
                        Expectation.SUCCESS_NO_VALUE, ""),
                new Step("topology-new", "top->::read[]", Expectation.SUCCESS_SCALAR, "9"),
                new Step("topology-old-edge", "(oldTopRead)", Expectation.SUCCESS_SCALAR, "1"),
                new Step("bounded-aggregate", LARGE_ARRAY_SOURCE,
                        Expectation.SUCCESS_AGGREGATE_TRUNCATED, ""));
    }

    /**
     * Mutable memory resolver for the corpus.  {@link #mutate(String)}
     * switches one revisioned module source before a reload phase.
     */
    public static final class Sources implements SourceResolver {
        private final Map<LogicalModuleId, ResolvedSource> modules = new LinkedHashMap<>();
        private final AtomicReference<String> counter = new AtomicReference<>(COUNTER_V1);
        private final AtomicReference<String> errors = new AtomicReference<>(ERRORS_V1);
        private final AtomicReference<String> top = new AtomicReference<>(TOP_V1);
        private boolean counterV2AfterFirstResolution;
        /** One resolver query per newly discovered logical module. */
        final List<String> queries = new ArrayList<>();

        public Sources() {
            modules.put(id("base"), source(id("base"), BASE));
            modules.put(id("added"), source(id("added"), ADDED));
            modules.put(id("higher"), source(id("higher"), HIGHER));
            modules.put(id("rec"), source(id("rec"), REC));
            modules.put(id("values"), source(id("values"), VALUES));
        }

        @Override
        public Optional<ResolvedSource> resolve(LogicalModuleId logical) {
            queries.add(logical.value());
            switch (logical.value()) {
                case "counter":
                    if (counterV2AfterFirstResolution
                            && queries.stream().filter("counter"::equals).count() > 1) {
                        counter.set(COUNTER_V2);
                    }
                    return Optional.of(source(logical, counter.get()));
                case "errors":
                    return Optional.of(source(logical, errors.get()));
                case "cyclea":
                    return Optional.of(source(logical, CYCLEA));
                case "cycleb":
                    return Optional.of(source(logical, CYCLEB));
                case "top":
                    return Optional.of(source(logical, top.get()));
                default:
                    return Optional.ofNullable(modules.get(logical));
            }
        }

        public void useCounterV2OnReload() {
            counterV2AfterFirstResolution = true;
        }

        public void mutate(String mutation) {
            switch (mutation) {
                case "counter->v2" -> counter.set(COUNTER_V2);
                case "errors->v2" -> errors.set(ERRORS_V2);
                case "top->v2" -> top.set(TOP_V2);
                default -> throw new IllegalArgumentException("unknown corpus mutation: " + mutation);
            }
        }

        private static LogicalModuleId id(String name) {
            return LogicalModuleId.parse(name);
        }

        private static ResolvedSource source(LogicalModuleId logical, String text) {
            return ResolvedSource.memory(logical,
                    URI.create("memory://corpus/" + logical.value() + ".lyra"), text);
        }
    }

    public static SessionOptions.Builder options(Sources sources) {
        return options(sources, new ByteArrayOutputStream());
    }

    public static SessionOptions.Builder options(Sources sources, ByteArrayOutputStream output) {
        return SessionOptions.builder()
                .resolver(sources)
                .ioEnvironment(io(output));
    }

    public static RuntimeIoEnvironment io(ByteArrayOutputStream output) {
        return new RuntimeIoEnvironment(
                new ByteArrayInputStream(PROGRAM_INPUT.getBytes(StandardCharsets.UTF_8)),
                output, output, StandardCharsets.UTF_8);
    }

    public static ByteArrayOutputStream output() {
        return new ByteArrayOutputStream();
    }

    public static String text(ByteArrayOutputStream output) {
        return output.toString(StandardCharsets.UTF_8);
    }

    /* ---- shared surface-independent execution and assertion helpers ---- */

    /**
     * Applies one corpus step against a local session.  Mutation markers
     * change resolver text and return null; reload markers reload the named
     * module or alias; everything else is an ordinary submission.
     */
    public static EvaluationResult execute(LyraSession session, Sources sources, Step step) {
        if (isMutation(step)) {
            sources.mutate(mutation(step));
            return null;
        }
        if (isReload(step)) {
            return session.reload(reloadTarget(step));
        }
        return session.submit(step.label() + ".lyra", step.source());
    }

    /** Asserts one corpus expectation against a local-session result. */
    public static void assertExpectation(Step step, EvaluationResult result) {
        switch (step.expectation()) {
            case SUCCESS_NO_VALUE -> org.junit.jupiter.api.Assertions.assertInstanceOf(
                    EvaluationResult.Success.class, result, step.label() + " => " + result);
            case SUCCESS_SCALAR -> {
                var success = org.junit.jupiter.api.Assertions.assertInstanceOf(
                        EvaluationResult.Success.class, result, step.label() + " => " + result);
                var scalar = org.junit.jupiter.api.Assertions.assertInstanceOf(
                        ValueSnapshot.Scalar.class, success.value().orElseThrow().data(),
                        step.label() + " => " + result);
                org.junit.jupiter.api.Assertions.assertEquals(step.expected(), scalar.value(),
                        step.label());
            }
            case SUCCESS_FUNCTION -> {
                var success = org.junit.jupiter.api.Assertions.assertInstanceOf(
                        EvaluationResult.Success.class, result, step.label() + " => " + result);
                ValueSnapshot.Function function = org.junit.jupiter.api.Assertions.assertInstanceOf(
                        ValueSnapshot.Function.class, success.value().orElseThrow().data(),
                        step.label() + " => " + result);
                org.junit.jupiter.api.Assertions.assertFalse(function.identity().isBlank(), step.label());
            }
            case SUCCESS_AGGREGATE_TRUNCATED -> {
                var success = org.junit.jupiter.api.Assertions.assertInstanceOf(
                        EvaluationResult.Success.class, result, step.label() + " => " + result);
                ValueSnapshot.Aggregate aggregate = org.junit.jupiter.api.Assertions.assertInstanceOf(
                        ValueSnapshot.Aggregate.class, success.value().orElseThrow().data(),
                        step.label() + " => " + result);
                org.junit.jupiter.api.Assertions.assertTrue(aggregate.truncation().isPresent(),
                        step.label() + " => " + result);
                org.junit.jupiter.api.Assertions.assertEquals(100, aggregate.elements().size(), step.label());
            }
            case OWNERSHIP_DIAGNOSTIC -> {
                var failure = org.junit.jupiter.api.Assertions.assertInstanceOf(
                        EvaluationResult.CompilationFailure.class, result, step.label() + " => " + result);
                org.junit.jupiter.api.Assertions.assertEquals(step.expected(),
                        failure.diagnostics().getFirst().code().toString(), step.label() + " => " + result);
            }
            case RUNTIME_DIVISION -> {
                var failure = org.junit.jupiter.api.Assertions.assertInstanceOf(
                        EvaluationResult.RuntimeFailure.class, result, step.label() + " => " + result);
                org.junit.jupiter.api.Assertions.assertTrue(
                        failure.failureSummary().orElse("").contains("division by zero"),
                        step.label() + " => " + result);
                org.junit.jupiter.api.Assertions.assertTrue(
                        failure.frames().stream().anyMatch(frame ->
                                frame.excerpt().orElse("").contains(step.expected())),
                        step.label() + " => " + result);
            }
        }
    }
}
