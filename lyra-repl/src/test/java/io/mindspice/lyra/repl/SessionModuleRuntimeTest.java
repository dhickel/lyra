package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.api.SourceResolver;
import io.mindspice.lyra.compiler.source.ResolvedSource;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SessionModuleRuntimeTest {
    @Test
    void diamondDependenciesInitializeOnceAndReuseOneLiveProducer() {
        var base = source("base", "import std->io io->::println[\"base-init\"]\n"
                + "let @pub @mut value :I32 = 20");
        var left = source("left", "import base\nlet @pub left :I32 = base->:.value");
        var right = source("right", "import base\nlet @pub right :I32 = base->:.value");
        var calls = new AtomicInteger();
        var resolver = resolver(calls, base, left, right);
        var output = new java.io.ByteArrayOutputStream();
        try (var session = LyraSession.open(SessionOptions.builder().resolver(resolver)
                .ioEnvironment(io(output)).build())) {
            var first = assertInstanceOf(EvaluationResult.Success.class,
                    session.submit("diamond.lyra", "import left import right (+ left->:.left right->:.right)"));
            assertEquals("40", scalar(first));
            assertEquals("base-init" + System.lineSeparator(), output.toString(java.nio.charset.StandardCharsets.UTF_8));
            var second = assertInstanceOf(EvaluationResult.Success.class,
                    session.submit("later.lyra", "import right right->:.right"));
            assertEquals("20", scalar(second));
            assertEquals("base-init" + System.lineSeparator(), output.toString(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(3, calls.get(), "one resolver query per newly discovered logical module");
        }
    }

    @Test
    void intrinsicIoExecutesThroughTheSessionAndPreservesFollowingSource() {
        var output = new java.io.ByteArrayOutputStream();
        var input = new java.io.ByteArrayInputStream("héllo\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var options = SessionOptions.builder().ioEnvironment(new io.mindspice.lyra.runtime.RuntimeIoEnvironment(
                input, output, output, java.nio.charset.StandardCharsets.UTF_8)).build();
        try (var session = LyraSession.open(options)) {
            var read = assertInstanceOf(EvaluationResult.Success.class,
                    session.submit("read.lyra", "import std->io io->::readLine[]"));
            assertEquals("héllo", scalar(read));
            success(session, "import std->io io->::println[\"after-read\"]");
            var following = assertInstanceOf(EvaluationResult.Success.class,
                    session.submit("following.lyra", "1"));
            assertEquals("1", scalar(following));
            assertEquals("after-read" + System.lineSeparator(),
                    output.toString(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    void importedAggregateAndCallableValuesKeepTheirOriginalIdentityAndMutationRules() {
        var module = source("values", "let @pub @mut items :Array<I32> = Array<I32>[1 2]\n"
                + "let @pub set :Fn<I32;Unit> = (=> |n| (items[0] := n))");
        try (var session = LyraSession.open(SessionOptions.builder().resolver(SourceResolver.single(module)).build())) {
            success(session, "import values");
            assertEquals("1", scalar(session, "values->:.items[0]"));
            assertInstanceOf(EvaluationResult.CompilationFailure.class,
                    session.submit("forbidden.lyra", "values->:.items[0] := 9"));
            success(session, "import values values->::set[9]");
            assertEquals("9", scalar(session, "values->:.items[0]"));
        }
    }

    @Test
    void failedModuleIsNotReusedOnIdenticalRetry() {
        var source = source("sameRetry", "import std->io io->::println[\"attempt\"]\n"
                + "let zero :I32 = 0\nlet @pub value :I32 = (% 1 zero)");
        var output = new java.io.ByteArrayOutputStream();
        try (var session = LyraSession.open(SessionOptions.builder()
                .resolver(SourceResolver.single(source)).ioEnvironment(io(output)).build())) {
            assertInstanceOf(EvaluationResult.RuntimeFailure.class,
                    session.submit("first.lyra", "import sameRetry"));
            assertInstanceOf(EvaluationResult.RuntimeFailure.class,
                    session.submit("second.lyra", "import sameRetry"));
            assertEquals("attempt" + System.lineSeparator() + "attempt" + System.lineSeparator(),
                    output.toString(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    void graphSourceCapacityRejectsBeforeInitializerEffects() {
        var output = new java.io.ByteArrayOutputStream();
        var module = source("tooLarge", "import std->io io->::println[\"must-not-run\"]\n"
                + "let @pub value :I32 = 1");
        var options = SessionOptions.builder().resolver(SourceResolver.single(module))
                .maxSourceRecords(1).ioEnvironment(io(output)).build();
        try (var session = LyraSession.open(options)) {
            var result = assertInstanceOf(EvaluationResult.CompilationFailure.class,
                    session.submit("capacity.lyra", "import tooLarge"));
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.summary().contains("source registry")));
            assertEquals("", output.toString(java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(session.workspaceState().bindings().isEmpty());
        }
    }

    @Test
    void failedModuleInitializerKeepsEffectsHiddenAndRetryReexecutesIt() {
        var sourceText = new AtomicReference<>("import std->io io->::println[\"attempt\"]\n"
                + "let zero :I32 = 0\nlet @pub value :I32 = (% 1 zero)");
        var logical = io.mindspice.lyra.compiler.source.LogicalModuleId.parse("retry");
        var resolverCalls = new AtomicInteger();
        var resolver = (io.mindspice.lyra.compiler.api.SourceResolver) requested -> {
            if (!requested.equals(logical)) return java.util.Optional.empty();
            resolverCalls.incrementAndGet();
            return java.util.Optional.of(ResolvedSource.memory(logical, URI.create("memory://retry.lyra"),
                    sourceText.get()));
        };
        var output = new java.io.ByteArrayOutputStream();
        try (var session = LyraSession.open(SessionOptions.builder().resolver(resolver)
                .ioEnvironment(io(output)).build())) {
            assertInstanceOf(EvaluationResult.RuntimeFailure.class,
                    session.submit("first.lyra", "import retry"));
            assertTrue(session.workspaceState().moduleRevisions().isEmpty());
            assertEquals("attempt" + System.lineSeparator(), output.toString(java.nio.charset.StandardCharsets.UTF_8));
            sourceText.set("import std->io io->::println[\"attempt\"]\nlet @pub value :I32 = 7");
            var success = assertInstanceOf(EvaluationResult.Success.class,
                    session.submit("retry.lyra", "import retry retry->:.value"));
            assertEquals("7", scalar(success));
            assertEquals("attempt" + System.lineSeparator() + "attempt" + System.lineSeparator(),
                    output.toString(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(2, resolverCalls.get());
        }
    }

    private static ResolvedSource source(String logical, String text) {
        return ResolvedSource.memory(logical, URI.create("memory://" + logical + ".lyra"), text);
    }

    private static io.mindspice.lyra.compiler.api.SourceResolver resolver(AtomicInteger calls,
            ResolvedSource... sources) {
        Map<io.mindspice.lyra.compiler.source.LogicalModuleId, ResolvedSource> map = new java.util.HashMap<>();
        for (var source : sources) map.put(source.logicalModule(), source);
        return requested -> {
            if (map.containsKey(requested)) calls.incrementAndGet();
            return java.util.Optional.ofNullable(map.get(requested));
        };
    }

    private static io.mindspice.lyra.runtime.RuntimeIoEnvironment io(java.io.ByteArrayOutputStream output) {
        return new io.mindspice.lyra.runtime.RuntimeIoEnvironment(
                java.io.InputStream.nullInputStream(), output, output, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void success(LyraSession session, String source) {
        var result = session.submit("test.lyra", source);
        assertInstanceOf(EvaluationResult.Success.class, result, result.toString());
    }

    private static String scalar(LyraSession session, String source) {
        return scalar(assertInstanceOf(EvaluationResult.Success.class,
                session.submit(source, source)));
    }

    private static String scalar(EvaluationResult.Success result) {
        return assertInstanceOf(ValueSnapshot.Scalar.class,
                result.value().orElseThrow().data()).value();
    }
}
