package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.CompileResult;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.compiler.api.SourceResolver;
import io.mindspice.lyra.compiler.source.ResolvedSource;
import io.mindspice.lyra.runtime.LyraRuntime;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 13 cross-surface conformance, surface 1: the synchronous local
 * Java API.  Runs the complete common source corpus through LyraSession
 * with exact value, identity, provenance, progress, fidelity, reload and
 * bound assertions.
 */
class CrossSurfaceLocalApiTest {
    @Test
    void commonCorpusExecutesThroughTheLocalSessionApi() {
        var sources = new CrossSurfaceCorpus.Sources();
        var output = CrossSurfaceCorpus.output();
        try (var session = LyraSession.open(
                CrossSurfaceCorpus.options(sources, output).build())) {
            var revision = session.currentRevision();
            for (CrossSurfaceCorpus.Step step : CrossSurfaceCorpus.program()) {
                EvaluationResult result = CrossSurfaceCorpus.execute(session, sources, step);
                if (result == null) {
                    continue;
                }
                CrossSurfaceCorpus.assertExpectation(step, result);
                if (result instanceof EvaluationResult.Success success) {
                    assertTrue(success.revision().value() >= revision.value(), step.label());
                    revision = success.revision();
                }
            }
            // Initializer markers ran exactly once per generation.
            assertEquals(1, occurrences(CrossSurfaceCorpus.text(output), "counter-init\n"),
                    CrossSurfaceCorpus.text(output));
            assertEquals(1, occurrences(CrossSurfaceCorpus.text(output), "counter-init-2\n"),
                    CrossSurfaceCorpus.text(output));
            assertTrue(CrossSurfaceCorpus.text(output).contains("héllo 😀\n"));
            // Persistent module state across the whole corpus.
            assertEquals("110", scalar(session, "counter->::read[]"));
            assertEquals("3", scalar(session, "values->:.items[2]"));
            assertEquals(1, sources.queries.stream().filter("base"::equals).count(),
                    "the removed old dependency stays pinned and is not reread during reload");
        }
    }

    @Test
    void delayedFailuresKeepExactSameFileRevisionsAndUtf16Spans() {
        var sources = new CrossSurfaceCorpus.Sources();
        try (var session = LyraSession.open(
                CrossSurfaceCorpus.options(sources).build())) {
            success(session, "import errors import errors->{fail as oldFail}");
            var first = assertInstanceOf(EvaluationResult.RuntimeFailure.class,
                    session.submit("late.lyra", "errors->::late[]"));
            assertFrame(first, "(% 11 x)", CrossSurfaceCorpus.ERRORS_V1);
            sources.mutate("errors->v2");
            success(session, session.reload("errors"));
            var old = assertInstanceOf(EvaluationResult.RuntimeFailure.class,
                    session.submit("old.lyra", "(oldFail 0)"));
            var fresh = assertInstanceOf(EvaluationResult.RuntimeFailure.class,
                    session.submit("new.lyra", "errors->::late[]"));
            assertFrame(old, "(% 11 x)", CrossSurfaceCorpus.ERRORS_V1);
            assertFrame(fresh, "(% 22 x)", CrossSurfaceCorpus.ERRORS_V2);
            // The astral 😀 occupies two UTF-16 code units in revision 2; the
            // authoritative zero-based end-exclusive span accounts for it.
            assertEquals(CrossSurfaceCorpus.ERRORS_V2.indexOf("(%"), freshSpan(fresh).startOffset());
            assertEquals(CrossSurfaceCorpus.ERRORS_V1.indexOf("(%"), oldSpan(old).startOffset());
            assertEquals(freshSpan(fresh).sourceId(), oldSpan(old).sourceId(),
                    "public spans share the caller-visible source URI");
        }
    }

    @Test
    void importedCallableProvenanceAndIdentitySurviveReload() {
        var sources = new CrossSurfaceCorpus.Sources();
        try (var session = LyraSession.open(
                CrossSurfaceCorpus.options(sources).build())) {
            success(session, "import counter import counter->{bump as oldBump}");
            assertEquals("1", scalar(session, "counter->::bump[]"));
            var before = session.submit("f.lyra", "counter->:.bump");
            var first = assertInstanceOf(ValueSnapshot.Function.class,
                    assertInstanceOf(EvaluationResult.Success.class, before)
                            .value().orElseThrow().data());
            sources.mutate("counter->v2");
            success(session, session.reload("counter"));
            assertEquals("false", scalar(session, "(eq? oldBump counter->:.bump)"));
            var after = assertInstanceOf(ValueSnapshot.Function.class,
                    assertInstanceOf(EvaluationResult.Success.class,
                            session.submit("f.lyra", "counter->:.bump"))
                            .value().orElseThrow().data());
            assertFalse(first.identity().isBlank());
            assertFalse(after.identity().isBlank());
            assertEquals("110", scalar(session, "counter->::bump[]"));
            assertEquals("2", scalar(session, "(oldBump)"));
        }
    }

    @Test
    void importCyclePinsSurviveLaterSubmissionsWithSoundSummaries() {
        var sources = new CrossSurfaceCorpus.Sources();
        try (var session = LyraSession.open(
                CrossSurfaceCorpus.options(sources).build())) {
            // The import cycle compiles and executes.
            assertEquals("0", scalar(session, "import cyclea cyclea->::ping[3]"));
            // A later submission that reuses the pinned cycle modules while
            // compiling new work keeps the callable summaries consistent
            // (regression for the certificate-only SCC coverage defect).
            assertEquals("1", scalar(session, "import values values->:.items[0]"));
            assertEquals("0", scalar(session, "(cyclea->:.ping 2)"));
            assertEquals("6", scalar(session, "values->::bump[]"));
            assertEquals("7", scalar(session, "(values->:.callables[0] 6)"));
        }
    }

    @Test
    void sourceRecordsKeepExactSubmissionFidelityAndRevisionMonotonicity() {
        var sources = new CrossSurfaceCorpus.Sources();
        try (var session = LyraSession.open(
                CrossSurfaceCorpus.options(sources).build())) {
            var revision = session.currentRevision();
            for (CrossSurfaceCorpus.Step step : CrossSurfaceCorpus.program()) {
                if (CrossSurfaceCorpus.isMutation(step)) {
                    sources.mutate(CrossSurfaceCorpus.mutation(step));
                    continue;
                }
                EvaluationResult result = CrossSurfaceCorpus.isReload(step)
                        ? session.reload(CrossSurfaceCorpus.reloadTarget(step))
                        : session.submit(step.label() + ".lyra", step.source());
                if (result instanceof EvaluationResult.Success success) {
                    assertTrue(success.revision().value() >= revision.value(), step.label());
                    revision = success.revision();
                }
            }
            // Every retained submission record preserves its exact original
            // text, including Unicode, with no program-input contamination.
            for (var record : session.sourceRecords()) {
                if (record.source().origin().label().equals("std-io-read.lyra")) {
                    assertEquals("import std->io io->::readLine[]", record.source().text());
                }
            }
            assertFalse(session.sourceRecords().toString()
                    .contains(CrossSurfaceCorpus.PROGRAM_INPUT.trim()),
                    "program input never enters retained source records");
            assertEquals("import counter import counter->{bump as bumpAgain}",
                    session.sourceRecords().stream()
                            .filter(record -> record.source().origin().label()
                                    .equals("duplicate-imports.lyra"))
                            .findFirst().orElseThrow().source().text());
        }
    }

    @Test
    void wholeGraphSourceCapacityRejectsBeforeInitializerEffects() {
        var sources = new CrossSurfaceCorpus.Sources();
        var output = CrossSurfaceCorpus.output();
        try (var session = LyraSession.open(CrossSurfaceCorpus.options(sources, output)
                .maxSourceRecords(1).build())) {
            var failure = assertInstanceOf(EvaluationResult.CompilationFailure.class,
                    session.submit("capacity.lyra", "import counter"));
            assertTrue(failure.diagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.summary().contains("source registry")),
                    failure.diagnostics().toString());
            assertEquals("", output.toString(StandardCharsets.UTF_8));
            assertTrue(session.workspaceState().bindings().isEmpty());
        }
    }

    @Test
    void oversizedStringConstantsAreRejectedBeforeSessionEffects() {
        var sources = new CrossSurfaceCorpus.Sources();
        try (var session = LyraSession.open(CrossSurfaceCorpus.options(sources).build())) {
            success(session, "let @mut count :I32 = 1");
            String oversized = "😀".repeat(10_923);
            EvaluationResult.CompilationFailure failure = assertInstanceOf(
                    EvaluationResult.CompilationFailure.class,
                    session.submit("oversized.lyra",
                            "{ count := 2 \"" + oversized + "\" }"));
            assertEquals("LYC-EMIT-001",
                    failure.diagnostics().getFirst().code().toString());
            assertTrue(failure.diagnostics().getFirst().summary().contains("CONSTANT_Utf8"));
            assertEquals("1", scalar(session, "count"));
        }
    }

    @Test
    void largeDynamicResultsTruncateExplicitlyAfterEffects() {
        var sources = new CrossSurfaceCorpus.Sources();
        var output = CrossSurfaceCorpus.output();
        try (var session = LyraSession.open(
                CrossSurfaceCorpus.options(sources, output).build())) {
            success(session, "import std->io io->::println[\"before-large\"]");
            var result = assertInstanceOf(EvaluationResult.Success.class,
                    session.submit("large.lyra", CrossSurfaceCorpus.LARGE_ARRAY_SOURCE));
            var aggregate = assertInstanceOf(ValueSnapshot.Aggregate.class,
                    result.value().orElseThrow().data());
            assertTrue(aggregate.truncation().isPresent());
            assertEquals(100, aggregate.elements().size());
            assertEquals("Array<I32>", result.value().orElseThrow().canonicalType());
            assertEquals("before-large\n", output.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void theSameCorpusSourcesCompileAndRunInOrdinaryAotIsolation() {
        var sources = new CrossSurfaceCorpus.Sources();
        CompileResult compiled = LyraCompiler.compile(CompileRequest.builder()
                .source("main.lyra", "import higher import rec import values\n"
                        + "let @pub main :Fn<Array<String>;I32> = (=> |args|\n"
                        + "{ let applied :I32 = (higher->:.apply (=> |x| (* x 3)) 7)\n"
                        + "  let rest :I32 = rec->::fact[6]\n"
                        + "  let base :I32 = values->:.items[0]\n"
                        + "  (+ applied (+ rest base)) })\n")
                .resolver(sources).build());
        var success = assertInstanceOf(CompileResult.Success.class, compiled, compiled.toString());
        // Ordinary artifacts carry no REPL/session instrumentation.
        assertFalse(success.artifact().classes().keySet().stream()
                        .anyMatch(name -> name.contains("repl") || name.contains("Repl")),
                success.artifact().classes().keySet().toString());
        try (var loaded = LyraRuntime.load(success.artifact());
             var root = loaded.instantiate()) {
            assertEquals(21 + 720 + 1,
                    (int) root.export("main", "Fn<Array<String>;I32>")
                            .methodHandle().invokeExact(new String[0]));
        } catch (Throwable failure) {
            throw new AssertionError(failure);
        }
    }

    private static int occurrences(String text, String value) {
        return (text.length() - text.replace(value, "").length()) / value.length();
    }

    private static void assertFrame(EvaluationResult.RuntimeFailure failure, String excerpt,
                                    String sourceText) {
        var frame = failure.frames().stream()
                .filter(value -> value.excerpt().orElse("").contains(excerpt))
                .findFirst().orElseThrow(() -> new AssertionError(failure.toString()));
        int expected = sourceText.indexOf("(%");
        assertEquals(expected, frame.span().startOffset(), frame.toString());
    }

    private static io.mindspice.lyra.compiler.source.SourceSpan freshSpan(
            EvaluationResult.RuntimeFailure failure) {
        return failure.frames().stream()
                .filter(frame -> frame.excerpt().orElse("").contains("(% 22"))
                .findFirst().orElseThrow().span();
    }

    private static io.mindspice.lyra.compiler.source.SourceSpan oldSpan(
            EvaluationResult.RuntimeFailure failure) {
        return failure.frames().stream()
                .filter(frame -> frame.excerpt().orElse("").contains("(% 11"))
                .findFirst().orElseThrow().span();
    }

    private static void success(LyraSession session, EvaluationResult result) {
        assertInstanceOf(EvaluationResult.Success.class, result,
                () -> result.status() + " " + result.diagnostics() + " " + result.failureSummary());
    }

    private static void success(LyraSession session, String source) {
        success(session, session.submit("local.lyra", source));
    }

    private static String scalar(LyraSession session, String source) {
        return scalar(assertInstanceOf(EvaluationResult.Success.class,
                session.submit("local.lyra", source), source));
    }

    private static String scalar(EvaluationResult.Success result) {
        return assertInstanceOf(ValueSnapshot.Scalar.class,
                result.value().orElseThrow().data()).value();
    }
}
