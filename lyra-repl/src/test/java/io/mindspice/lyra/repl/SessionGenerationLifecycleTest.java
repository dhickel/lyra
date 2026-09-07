package io.mindspice.lyra.repl;

import io.mindspice.lyra.runtime.LyraLifecycleException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionGenerationLifecycleTest {
    @Test
    void resetRetiresTheOldGenerationAndPreservesRevisionMonotonicity() {
        try (var session = LyraSession.open()) {
            success(session.submit("first.lyra", "let @pub value :I32 = 10"));
            var firstIdentity = session.workspaceState().bindings().get("value").identity();
            var firstRevision = session.workspaceState().revision();

            session.reset();

            assertEquals(firstRevision, session.workspaceState().revision());
            assertTrue(session.workspaceState().bindings().isEmpty());
            assertTrue(session.workspaceState().moduleRevisions().isEmpty());
            assertTrue(session.sourceRecords().isEmpty());
            assertInstanceOf(EvaluationResult.CompilationFailure.class,
                    session.submit("gone.lyra", "value"));

            success(session.submit("second.lyra", "let @pub value :I32 = 20"));
            assertNotEquals(firstIdentity, session.workspaceState().bindings().get("value").identity());
            assertEquals(new SessionRevision(firstRevision.value() + 1),
                    session.workspaceState().revision());
        }
    }

    @Test
    void closeIsIdempotentAndClosedSubmissionDoesNotReopenTheSession() {
        var session = LyraSession.open();
        success(session.submit("value.lyra", "let @pub value :I32 = 1"));
        session.close();
        session.close();

        assertEquals(SessionLifecycleState.CLOSED, session.lifecycleState());
        var closed = assertInstanceOf(EvaluationResult.Closed.class,
                session.submit("after-close.lyra", "1"));
        assertEquals(SessionLifecycleState.CLOSED, closed.lifecycleState());
        assertThrows(LyraLifecycleException.class, session::reset);
    }

    @Test
    void failedReloadDoesNotAdvanceRevisionOrRetargetExistingNamespace() {
        var sourceText = new java.util.concurrent.atomic.AtomicReference<>(
                "let @pub value :I32 = 7");
        var logical = io.mindspice.lyra.compiler.source.LogicalModuleId.of("lifecycle");
        var resolver = (io.mindspice.lyra.compiler.api.SourceResolver) requested ->
                requested.equals(logical)
                        ? java.util.Optional.of(io.mindspice.lyra.compiler.source.ResolvedSource.memory(
                                logical, java.net.URI.create("memory://lifecycle.lyra"), sourceText.get()))
                        : java.util.Optional.empty();

        try (var session = LyraSession.open(SessionOptions.builder().resolver(resolver).build())) {
            success(session.submit("import.lyra", "import lifecycle"));
            var before = session.workspaceState();
            sourceText.set("let @pub value :I32 = (% 1 0)");
            var result = assertInstanceOf(EvaluationResult.CompilationFailure.class,
                    session.reload(logical));
            assertEquals(before.revision(), result.revision());
            assertEquals(before, session.workspaceState());
            assertEquals("7", scalar(session.submit("read.lyra", "lifecycle->:.value")));
        }
    }

    @Test
    void scratchProgressIncludesExactlyCompletedTopLevelInitializers() {
        try (var session = LyraSession.open()) {
            var failed = assertInstanceOf(EvaluationResult.RuntimeFailure.class,
                    session.submit("progress", "let first :I32 = 1 let zero :I32 = 0 let last :I32 = (% first zero)"));
            assertEquals(3, failed.initializerProgress().scheduled().size());
            assertEquals(failed.initializerProgress().scheduled(), failed.initializerProgress().attempted());
            assertEquals(failed.initializerProgress().scheduled().subList(0, 2), failed.initializerProgress().completed());
            assertTrue(session.workspaceState().bindings().isEmpty());
            session.reset();
            var retried = assertInstanceOf(EvaluationResult.Success.class, session.submit("retry", "let first :I32 = 2"));
            assertEquals(1, retried.initializerProgress().completed().size());
            assertNotEquals(failed.initializerProgress().scheduled().getFirst().declarationId(),
                    retried.initializerProgress().completed().getFirst().declarationId());
        }
    }

    @Test
    void repeatedReloadResetAndCloseRetireOwnedGenerations() {
        var text = new java.util.concurrent.atomic.AtomicReference<>("let @pub value :I32 = 1");
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var resolver = (io.mindspice.lyra.compiler.api.SourceResolver) logical -> {
            reads.incrementAndGet();
            return java.util.Optional.of(io.mindspice.lyra.compiler.source.ResolvedSource.memory(
                    logical, java.net.URI.create("memory://reset-reload"), text.get()));
        };
        var session = LyraSession.open(SessionOptions.builder().resolver(resolver).build());
        try {
            for (int cycle = 0; cycle < 3; cycle++) {
                success(session.submit("import", "import resettable"));
                success(session.reload("resettable"));
                var before = session.workspaceState().revision();
                session.reset();
                assertEquals(before, session.workspaceState().revision());
                assertTrue(session.workspaceState().bindings().isEmpty());
                assertTrue(session.sourceRecords().isEmpty());
            }
            assertEquals(6, reads.get());
        } finally {
            session.close();
        }
        assertInstanceOf(EvaluationResult.Closed.class, session.reload("resettable"));
        assertInstanceOf(EvaluationResult.Closed.class, session.reload("invalid target"));
        session.close();
    }

    @Test
    void bomSubmissionConsumesItsReservedSourceSlotExactlyOnce() {
        try (var session = LyraSession.open(SessionOptions.builder().maxSourceRecords(1).build())) {
            var result = assertInstanceOf(EvaluationResult.Success.class,
                    session.submit("bom", "\ufefflet value :I32 = 1"));
            assertEquals(1, result.initializerProgress().completed().size());
            assertEquals(1, session.sourceRecords().size());
            assertTrue(session.workspaceState().bindings().containsKey("value"));
        }
    }

    private static void success(EvaluationResult result) {
        assertInstanceOf(EvaluationResult.Success.class, result,
                () -> result.status() + " " + result.diagnostics() + " " + result.failureSummary());
    }

    private static String scalar(EvaluationResult result) {
        var success = assertInstanceOf(EvaluationResult.Success.class, result);
        return assertInstanceOf(ValueSnapshot.Scalar.class,
                success.value().orElseThrow().data()).value();
    }
}
