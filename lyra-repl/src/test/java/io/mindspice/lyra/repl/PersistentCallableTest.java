package io.mindspice.lyra.repl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PersistentCallableTest {
    @Test
    @Timeout(30)
    void bothCallbackLoopsHonorSessionCancellation() throws Exception {
        for (String loop : List.of("while[|| #T || {}]", "iter[(0..9223372036854775807:1) || {}]")) {
            try (var session = LyraSession.open()) {
                success(session, "let @mut count :I32 = 0");
                var before = session.workspaceState();
                var request = new EvaluationRequest(EvaluationId.create(), before.revision(),
                        EvaluationSource.of("loop-cancel.lyra", "count := 1 " + loop + " count := 99"));
                Thread owner = Thread.currentThread();
                AtomicReference<Throwable> controlFailure = new AtomicReference<>();
                Thread control = new Thread(() -> {
                    try {
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                        while (System.nanoTime() < deadline) {
                            if (java.util.Arrays.stream(owner.getStackTrace()).anyMatch(frame ->
                                    frame.getClassName().startsWith("lyra.generated.session.")
                                            && frame.getMethodName().equals("invoke"))) {
                                assertTrue(session.cancel(request.evaluationId()));
                                return;
                            }
                            Thread.sleep(1);
                        }
                        throw new AssertionError("loop never reached generated callback execution");
                    } catch (Throwable failure) { controlFailure.set(failure); }
                    finally { session.cancel(request.evaluationId()); }
                });
                control.start();
                try { assertInstanceOf(EvaluationResult.Cancelled.class, session.submit(request)); }
                finally { control.join(11000); }
                assertFalse(control.isAlive());
                assertNull(controlFailure.get());
                assertEquals(before, session.workspaceState());
                assertEquals("1", scalar(session, "count"));
            }
        }
    }

    @Test
    void callbackFailurePreservesCompletedWritesAndStopsIteration() {
        try (var session = LyraSession.open()) {
            success(session, "let @mut n :I32 = 0");
            for (String source : List.of(
                    "iter[(0..5:1) || { n := (+ n 1) let z :I32 = 0 (% 1 z) {} }]",
                    "while[|| { n := (+ n 1) let z :I32 = 0 (% 1 z) #T } || { n := 99 }]")) {
                var failure = assertInstanceOf(EvaluationResult.RuntimeFailure.class, session.submit("failure.lyra", source));
                assertEquals("LYR-ARITH", failure.code());
                assertFalse(failure.frames().isEmpty());
            }
            assertEquals("2", scalar(session, "n"));
            var zero = assertInstanceOf(EvaluationResult.RuntimeFailure.class, session.submit("range-zero.lyra",
                    "let step :I64 = 0 iter[(0..5:step) || { n := 99 }]"));
            assertEquals("LYR-ARITH", zero.code());
            assertFalse(zero.frames().isEmpty());
            assertEquals("2", scalar(session, "n"));
        }
    }
    @Test
    void callbackLoopsAndRangesPersistAcrossSubmissions() {
        try (var session = LyraSession.open()) {
            success(session, "let @mut count :I64 = 0 let range = (0..5:1)");
            assertEquals("(0..5:1)", scalar(session, "range"));
            success(session, "iter[range |x| { count := (+ count x) }]");
            assertEquals("10", scalar(session, "count"));
            success(session, "let run :Fn<;Unit> = (=> || while[|| (< count 15) || { count := (+ count 1) }])");
            success(session, "(run)");
            assertEquals("15", scalar(session, "count"));
            success(session, "iter[range || { count := (+ count 1) }]");
            assertEquals("20", scalar(session, "count"));
        }
    }
    @Test
    void closuresRetainOriginalBindingsAndShareMutableCellsAcrossSubmissions() {
        try (var session = LyraSession.open()) {
            success(session, "let @mut count :I32 = 1 let add :Fn<I32;I32> = (=> |n| { count := (+ count n) count })");
            assertEquals("3", scalar(session, "(add 2)"));
            success(session, "count := 40");
            assertEquals("42", scalar(session, "(add 2)"));
            success(session, "let count :String = \"new lexical binding\"");
            assertEquals("43", scalar(session, "(add 1)"));
            assertEquals("new lexical binding", scalar(session, "count"));
            success(session, "let next :Fn<;I32> = { let @mut n :I32 = 0 (=> || { n := (+ n 1) n }) }");
            assertEquals("1", scalar(session, "(next)"));
            assertEquals("2", scalar(session, "(next)"));
        }
    }

    @Test
    void stdIoGenerationCallableUsesItsCertifiedAccessorAcrossLaterNamedCalls() {
        try (var session = LyraSession.open()) {
            success(session, "import std->io as io\n"
                    + "let @mut count :I32 = 0 "
                    + "let next :Fn<;I32> = (=> || { count := (+ count 1) count })");
            assertEquals("1", scalar(session, "::next[]"));
            assertEquals("2", scalar(session, "(next)"));
            assertEquals("2", scalar(session, "count"));
        }
    }

    @Test
    void returnedCallablesRetainProducerCodeAndCapturedCellsAcrossSubmissions() {
        try (var session = LyraSession.open()) {
            success(session, "let @mut base :I32 = 1 let make :Fn<I32;Fn<;I32>> = (=> |n| (=> || (+ base n)))");
            success(session, "let saved :Fn<;I32> = (make 5)");
            assertEquals("6", scalar(session, "(saved)"));
            success(session, "base := 10");
            assertEquals("15", scalar(session, "(saved)"));
        }
    }

    @Test
    void recursiveFunctionsKeepCertifiedSharedCellEffectsAcrossGenerations() {
        try (var session = LyraSession.open()) {
            success(session, "let @mut count :I32 = 0 let addMany :Fn<I32;I32> = (=> |n| ((> n 0) -> { count := (+ count 1) (addMany (- n 1)) } : count))");
            assertEquals("40", scalar(session, "(addMany 40)"));
            assertEquals("42", scalar(session, "(addMany 2)"));
            assertEquals("42", scalar(session, "count"));
        }
    }

    @Test
    void exactHigherOrderCallsAndCallableArraysTuplesRetainIdentityAndRebinding() {
        try (var session = LyraSession.open()) {
            success(session, "let one :Fn<I32;I32> = (=> |n| (+ n 1)) let apply :Fn<Fn<I32;I32>,I32;I32> = (=> |f n| (f n))");
            success(session, "let wrap :Fn<Fn<I32;I32>;Fn<I32;I32>> = (=> |f| (=> |n| (f n))) let wrapped = (wrap one)");
            assertEquals("42", scalar(session, "(apply wrapped 41)"));
            success(session, "let @mut functions :Array<Fn<I32;I32>> = Array[one wrapped] let pair = Tuple[wrapped functions]");
            success(session, "let @mut alias = functions");
            assertEquals("true", scalar(session, "(eq? functions pair:.1)"));
            assertEquals("true", scalar(session, "(eq? one alias[0])"));
            success(session, "functions[0] := (=> :I32 |n :I32| (+ n 2))");
            assertEquals("42", scalar(session, "(pair:.1[0] 40)"));
            assertEquals("42", scalar(session, "(pair:.0 41)"));
            success(session, "functions := Array<Fn<I32;I32>>[wrapped]");
            assertEquals("false", scalar(session, "(eq? functions alias)"));
            assertEquals("42", scalar(session, "(apply alias[0] 40)"));
        }
    }

    @Test
    void functionsEscapingFailedSubmissionsKeepInitializedCapturesWithoutPublishingNames() {
        try (var session = LyraSession.open()) {
            success(session, "let @mut selected :Fn<;I32> = (=> || 1)");
            var committed = session.workspaceState();
            var failure = assertInstanceOf(EvaluationResult.RuntimeFailure.class, session.submit("escaped.lyra", """
                    let @mut hidden :I32 = 40
                    selected := (=> :I32 || { hidden := (+ hidden 1) hidden })
                    let zero :I32 = 0
                    (% 1 zero)
                    let neverInitialized :I32 = 99
                    """));
            assertEquals("LYR-ARITH", failure.code());
            assertEquals(committed, session.workspaceState());
            assertEquals("41", scalar(session, "(selected)"));
            assertEquals("42", scalar(session, "(selected)"));
            success(session, "let hidden :String = \"not the failed capture\"");
            assertEquals("43", scalar(session, "(selected)"));
            for (String name : List.of("zero", "neverInitialized")) {
                assertInstanceOf(EvaluationResult.CompilationFailure.class, session.submit("invisible.lyra", name));
            }
        }
    }

    @Test
    void failedNestedCallsRetainIntermediateWritesAndOldGetterIdentities() {
        try (var session = LyraSession.open()) {
            success(session, "let @mut f :Fn<;I32> = (=> || 1) let get :Fn<;Fn<;I32>> = (=> || f)");
            var before = session.workspaceState();
            assertInstanceOf(EvaluationResult.RuntimeFailure.class, session.submit("intermediate.lyra", """
                    let @mut hidden :I32 = 40
                    let intermediate :Fn<;I32> = (=> || { hidden := (+ hidden 1) hidden })
                    let set :Fn<;Bool> = (=> || {
                        f := intermediate
                        let zero :I32 = 0
                        (% 1 zero)
                        f := (=> :I32 || 99)
                        #T
                    })
                    { (set) }
                    """));
            assertEquals(before, session.workspaceState());
            assertEquals("41", scalar(session, "((get))"));
            success(session, "let f :String = \"replacement\"");
            assertEquals("42", scalar(session, "((get))"));
            assertEquals("replacement", scalar(session, "f"));
            assertFalse(session.workspaceState().bindings().containsKey("intermediate"));
        }
    }

    @Test
    @Timeout(20)
    void callableAggregateEscapeSurvivesCooperativeCancellation() throws Exception {
        try (var session = LyraSession.open()) {
            success(session, "let @mut functions :Array<Fn<;I32>> = Array<Fn<;I32>>[(=> || 1)]");
            var before = session.workspaceState();
            var request = new EvaluationRequest(EvaluationId.create(), before.revision(), EvaluationSource.of("cancelled.lyra", """
                    let hidden :I32 = 42
                    functions[0] := (=> :I32 || hidden)
                    let spin :Fn<;I32> = (=> || (spin))
                    (spin)
                    functions[0] := (=> :I32 || 99)
                    """));
            Thread owner = Thread.currentThread();
            AtomicReference<Throwable> controlFailure = new AtomicReference<>();
            Thread control = new Thread(() -> {
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (System.nanoTime() < deadline) {
                        if (java.util.Arrays.stream(owner.getStackTrace()).anyMatch(frame ->
                                frame.getClassName().startsWith("lyra.generated.session.")
                                        && frame.getMethodName().equals("invoke"))) {
                            assertTrue(session.cancel(request.evaluationId()));
                            return;
                        }
                        Thread.sleep(1);
                    }
                    throw new AssertionError("generated execution never reached the cancellation boundary");
                } catch (Throwable failure) { controlFailure.set(failure); }
                finally { session.cancel(request.evaluationId()); }
            });
            control.start();
            try { assertInstanceOf(EvaluationResult.Cancelled.class, session.submit(request)); }
            finally { control.join(11000); }
            assertFalse(control.isAlive());
            assertNull(controlFailure.get());
            assertEquals(before, session.workspaceState());
            assertEquals("42", scalar(session, "(functions[0])"));
            assertFalse(session.workspaceState().bindings().containsKey("hidden"));
        }
    }

    @Test
    void delayedClosureFailuresRetainProducerOriginVersionAndExactUtf16Span() {
        String text = "let label :String = \"😀\" let fail :Fn<I32;I32> = (=> |n| (+ n 2147483647))";
        var origin = new SourceOrigin("producer-selection", Optional.of(URI.create("memory:/producer.lyra")),
                Optional.of(7L), 50, 50 + text.length());
        try (var session = LyraSession.open()) {
            assertInstanceOf(EvaluationResult.Success.class, session.submit(new EvaluationSource(origin, text)));
            success(session, "let unrelated = 7");
            var failure = assertInstanceOf(EvaluationResult.RuntimeFailure.class, session.submit("later.lyra", "(fail 1)"));
            assertEquals("LYR-ARITH", failure.code());
            var frame = failure.frames().stream().filter(value -> value.origin().equals(origin)
                    && value.span().startOffset() == 50 + text.indexOf("(+ n")).findFirst().orElseThrow();
            assertEquals(50 + text.indexOf("(+ n") + "(+ n 2147483647)".length(), frame.span().endOffset());
            assertEquals("(+ n 2147483647)", frame.excerpt().orElseThrow());
            assertEquals(URI.create("memory:/producer.lyra"), frame.span().sourceId().asUri());
        }
    }

    @Test
    void nonExecutingQueriesTypeErrorsPublicProtectionAndResetKeepTheirContracts() {
        ValueSnapshot snapshot;
        try (var session = LyraSession.open()) {
            success(session, "let @mut count :I32 = 0 let @pub next :Fn<;I32> = (=> || { count := (+ count 1) count })");
            assertEquals("I32", session.type(EvaluationSource.of("type.lyra", "(next)")).canonicalType().orElseThrow());
            assertEquals("0", scalar(session, "count"));
            assertInstanceOf(EvaluationResult.CompilationFailure.class, session.submit("bad.lyra", "count := 7 let invalid :Bool = (next)"));
            assertEquals("0", scalar(session, "count"));
            for (String text : List.of("let next = 1", "next := (=> || 1)", "(next 1)",
                    "let f :Fn<String;I32> = next")) {
                assertInstanceOf(EvaluationResult.CompilationFailure.class, session.submit("invalid.lyra", text));
            }
            snapshot = success(session, "next").value().orElseThrow();
            session.reset();
            assertInstanceOf(EvaluationResult.CompilationFailure.class, session.submit("reset.lyra", "next"));
            success(session, "let next :Fn<;I32> = (=> || 42)");
            assertEquals("42", scalar(session, "(next)"));
        }
        assertInstanceOf(ValueSnapshot.Function.class, snapshot.data());
    }

    private static EvaluationResult.Success success(LyraSession session, String source) {
        var result = session.submit("callable.lyra", source);
        return assertInstanceOf(EvaluationResult.Success.class, result, source + " => " + result);
    }

    private static String scalar(LyraSession session, String source) {
        return assertInstanceOf(ValueSnapshot.Scalar.class, success(session, source).value().orElseThrow().data()).value();
    }
}
