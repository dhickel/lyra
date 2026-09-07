package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.api.SourceResolver;
import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.runtime.LyraLifecycleException;
import io.mindspice.lyra.runtime.LyraThreadException;
import io.mindspice.lyra.runtime.PrimitiveType;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyraSessionTest {
    @Test
    void configurationAndSubmissionInputsAreCopiedAndNullMisuseIsRejected() {
        ArrayList<Path> roots = new ArrayList<>(List.of(Path.of("sources")));
        HashMap<String, String> semanticOptions = new HashMap<>();
        semanticOptions.put("feature", "enabled");
        SessionOptions options = SessionOptions.builder()
                .sourceRoots(roots)
                .semanticOptions(semanticOptions)
                .build();
        roots.clear();
        semanticOptions.clear();

        assertEquals(List.of(Path.of("sources")), options.sourceRoots());
        assertEquals(Map.of("feature", "enabled"), options.semanticOptions());
        assertThrows(UnsupportedOperationException.class, () -> options.sourceRoots().clear());
        assertThrows(UnsupportedOperationException.class, () -> options.semanticOptions().clear());
        assertThrows(NullPointerException.class, () -> LyraSession.open(null));
        try (LyraSession session = LyraSession.open()) {
            assertThrows(NullPointerException.class,
                    () -> session.submit((EvaluationSource) null));
            assertThrows(NullPointerException.class,
                    () -> session.submit((EvaluationRequest) null));
        }
    }

    @Test
    void transportCancellationIsCheckedAfterSessionAdmission() {
        try (LyraSession session = LyraSession.open()) {
            EvaluationRequest request = new EvaluationRequest(
                    EvaluationId.create(), SessionRevision.initial(),
                    EvaluationSource.of("cancel-admission.lyra", "42"));
            EvaluationResult result = session.submit(request, () -> {
                assertTrue(session.cancel(request.evaluationId()));
                return false;
            });
            assertInstanceOf(EvaluationResult.Cancelled.class, result);
            assertFalse(session.isBusy());
        }
    }

    @Test
    void compilerConfigurationIsForwardedToSessionCompilation() {
        for (SessionOptions options : List.of(
                SessionOptions.builder().javaTarget(24).build(),
                SessionOptions.builder().javaBasePackage("java.invalid").build())) {
            try (LyraSession session = LyraSession.open(options)) {
                EvaluationResult.CompilationFailure failure = assertInstanceOf(
                        EvaluationResult.CompilationFailure.class,
                        session.submit(EvaluationSource.of("configuration.lyra", "")));
                assertEquals(CompilerDiagnosticCodes.MODULE_INVALID_CONFIGURATION,
                        failure.diagnostics().getFirst().code());
                assertEquals(SessionRevision.initial(), session.workspaceState().revision());
            }
        }
    }

    @Test
    void successfulBytecodeSubmissionPublishesImmutableMetadataAndAdvancesRevision() {
        try (LyraSession session = LyraSession.open()) {
            EvaluationResult.Success first = assertInstanceOf(
                    EvaluationResult.Success.class,
                    session.submit(new EvaluationRequest(
                            EvaluationId.create(),
                            SessionRevision.initial(),
                            EvaluationSource.of(
                                    "counter.lyra",
                                    "let @pub @mut counter :I32 = 1"))));

            assertEquals(new SessionRevision(1), first.revision());
            BindingMetadata counter = session.workspaceState().bindings()
                    .get("counter");
            assertNotNull(counter);
            assertEquals(PrimitiveType.I32, counter.type());
            assertTrue(counter.isMutable());
            assertTrue(counter.storageIdentity().isPresent());
            assertEquals(1, session.workspaceState().moduleRevisions().size());

            EvaluationResult.Success second = assertInstanceOf(
                    EvaluationResult.Success.class,
                    session.submit(EvaluationSource.of("empty.lyra", "")));
            assertEquals(new SessionRevision(2), second.revision());
            assertEquals(new SessionRevision(2), session.workspaceState().revision());
            assertEquals(2, session.sourceRecords().size());
            assertEquals("counter.lyra",
                    session.sourceRecords().getFirst().source().origin().label());
        }
    }

    @Test
    void typeQueryUsesFinalTopLevelFormWithoutExecutingOrPublishing() {
        try (LyraSession session = LyraSession.open()) {
            WorkspaceState.Committed before = session.workspaceState();

            LyraSession.TypeQuery expression = session.type(EvaluationSource.of(
                    "type-expression.lyra",
                    "let @pub queryOnly :I32 = 1\n42"));
            assertFalse(expression.busy());
            assertEquals(Optional.of("I64"), expression.canonicalType());
            assertTrue(expression.diagnostics().isEmpty());

            LyraSession.TypeQuery declaration = session.type(EvaluationSource.of(
                    "type-declaration.lyra",
                    "let @pub declarationOnly :I32 = 1"));
            assertEquals(Optional.of("Unit"), declaration.canonicalType());

            LyraSession.TypeQuery noOp = session.type(EvaluationSource.of(
                    "type-no-op.lyra", "/* query only */"));
            assertEquals(Optional.of("Unit"), noOp.canonicalType());

            assertEquals(before, session.workspaceState());
            assertTrue(session.sourceRecords().isEmpty());
            assertEquals(SessionRevision.initial(), session.workspaceState().revision());
        }
    }

    @Test
    void typeQueryRetainsMappedImmutableDiagnosticsWithoutRecordingSource() {
        URI uri = URI.create("file:///workspace/type.lyra");
        String text = "missing";
        SourceOrigin origin = new SourceOrigin(
                "type-selection", Optional.of(uri), Optional.of(8L), 30, 30 + text.length());
        try (LyraSession session = LyraSession.open()) {
            LyraSession.TypeQuery failure = session.type(new EvaluationSource(origin, text));

            assertFalse(failure.busy());
            assertTrue(failure.canonicalType().isEmpty());
            Diagnostic diagnostic = failure.diagnostics().getFirst();
            assertEquals(CompilerDiagnosticCodes.RESOLVE_UNRESOLVED_NAME, diagnostic.code());
            assertEquals(SourceSpan.of(SourceId.uri(uri), 30, 37), diagnostic.primarySpan());
            assertThrows(UnsupportedOperationException.class,
                    () -> failure.diagnostics().clear());
            assertTrue(session.sourceRecords().isEmpty());
            assertEquals(SessionRevision.initial(), session.workspaceState().revision());
        }
    }

    @Test
    void typeQueryExcludesReentrantEvaluationAndLifecycleMutation() {
        AtomicReference<LyraSession> sessionReference = new AtomicReference<>();
        AtomicReference<LocalConsoleSession> adapterReference = new AtomicReference<>();
        AtomicReference<LyraSession.TypeQuery> nestedType = new AtomicReference<>();
        AtomicReference<ConsoleSession.Query> nestedConsoleQuery = new AtomicReference<>();
        AtomicReference<EvaluationResult> nestedEvaluation = new AtomicReference<>();
        AtomicReference<Throwable> resetFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        SourceResolver resolver = logicalModule -> {
            LyraSession session = sessionReference.get();
            nestedType.set(session.type(EvaluationSource.of("nested-type.lyra", "42")));
            nestedConsoleQuery.set(adapterReference.get().query(
                    ConsoleSession.QueryRequest.type(
                            EvaluationSource.of("nested-console-type.lyra", "42"))));
            nestedEvaluation.set(session.submit(EvaluationSource.of("nested-evaluation.lyra", "")));
            try {
                session.reset();
            } catch (Throwable failure) {
                resetFailure.set(failure);
            }
            try {
                session.close();
            } catch (Throwable failure) {
                closeFailure.set(failure);
            }
            return Optional.empty();
        };
        try (LyraSession session = LyraSession.open(
                SessionOptions.builder().resolver(resolver).build())) {
            sessionReference.set(session);
            adapterReference.set(new LocalConsoleSession(session));

            LyraSession.TypeQuery outer = session.type(EvaluationSource.of(
                    "outer-type.lyra", "import missing"));

            assertTrue(outer.canonicalType().isEmpty());
            assertTrue(nestedType.get().busy());
            assertEquals(ConsoleSession.QueryStatus.BUSY, nestedConsoleQuery.get().status());
            assertEquals(EvaluationStatus.BUSY, nestedEvaluation.get().status());
            assertInstanceOf(LyraLifecycleException.class, resetFailure.get());
            assertInstanceOf(LyraLifecycleException.class, closeFailure.get());
            assertEquals(SessionLifecycleState.OPEN, session.lifecycleState());
            assertEquals(SessionRevision.initial(), session.workspaceState().revision());
            assertTrue(session.sourceRecords().isEmpty());
            assertEquals(Optional.of("I64"),
                    session.type(EvaluationSource.of("after-type.lyra", "42")).canonicalType());
        }
    }

    @Test
    void publicMutableStatePersistsWithoutInventingImplicitResultBindings() {
        try (LyraSession session = LyraSession.open()) {
            EvaluationResult.Success first = assertInstanceOf(EvaluationResult.Success.class,
                    session.submit("count.lyra", "let @pub @mut count :I32 = 40"));
            assertTrue(first.value().isEmpty());
            var identity = session.workspaceState().bindings().get("count").identity();
            assertInstanceOf(EvaluationResult.Success.class, session.submit("use-count.lyra", "count := (+ count 2)"));
            var read = assertInstanceOf(EvaluationResult.Success.class, session.submit("use-count.lyra", "count"));
            assertEquals("42", assertInstanceOf(ValueSnapshot.Scalar.class, read.value().orElseThrow().data()).value());
            assertEquals(identity, session.workspaceState().bindings().get("count").identity());
            assertEquals(1, session.workspaceState().bindings().size());
            EvaluationResult.Success local = assertInstanceOf(EvaluationResult.Success.class,
                    session.submit("local.lyra", "42"));
            assertEquals(new SessionRevision(4), local.revision());
            assertEquals("42", assertInstanceOf(ValueSnapshot.Scalar.class,
                    local.value().orElseThrow().data()).value());
        }
    }

    @Test
    void exactPrivateCounterSequenceReturnsExecutedValuesFromStableStorage() {
        try (LyraSession session = LyraSession.open()) {
            EvaluationResult.Success declaration = assertInstanceOf(EvaluationResult.Success.class,
                    session.submit("count.lyra", "let @mut count :I32 = 40"));
            assertTrue(declaration.value().isEmpty());
            WorkspaceState.Committed before = session.workspaceState();
            assertEquals(BindingVisibility.PRIVATE, before.bindings().get("count").visibility());
            assertEquals(new SessionRevision(1), before.revision());
            assertInstanceOf(EvaluationResult.Success.class, session.submit("count.lyra", "count := (+ count 2)"));
            var read = assertInstanceOf(EvaluationResult.Success.class, session.submit("count.lyra", "count"));
            assertEquals("42", assertInstanceOf(ValueSnapshot.Scalar.class, read.value().orElseThrow().data()).value());
            assertEquals(before.bindings().get("count"), session.workspaceState().bindings().get("count"));
            assertEquals(new SessionRevision(3), read.revision());
            assertTrue(session.sourceRecords().stream().allMatch(record -> record.status() == SourceRecordStatus.COMMITTED));
            assertEquals(3, session.sourceRecords().size());
        }
    }

    @Test
    void compileAndCallableSignatureFailuresPrecedeEverySourceInitializer() {
        String prefix = "let zero :I32 = 0\nlet @pub staged :I32 = (% 1 zero)\n";
        try (LyraSession session = LyraSession.open()) {
            assertInstanceOf(EvaluationResult.Success.class,
                    session.submit("prior.lyra", "let @pub @mut count :I32 = 40"));
            WorkspaceState.Committed before = session.workspaceState();
            assertInstanceOf(EvaluationResult.Success.class, session.submit("callable-array.lyra", "let items :Array<Fn<;I32>> = Array<Fn<;I32>>[(=> || 1)]"));
            before = session.workspaceState();
            for (String suffix : List.of("let badFn :Fn<;Bool> = items[0]", "let bad :I32 = \"wrong\"")) {
                EvaluationResult.CompilationFailure failure = assertInstanceOf(
                        EvaluationResult.CompilationFailure.class,
                        session.submit("rejected.lyra", prefix + suffix));
                assertEquals(CompilerDiagnosticCodes.TYPE_MISMATCH, failure.diagnostics().getFirst().code());
                assertEquals(before, session.workspaceState());
                assertEquals(before.revision(), failure.revision());
                assertTrue(failure.value().isEmpty());
                assertEquals(SourceRecordStatus.COMPILATION_FAILURE,
                        session.sourceRecords().getLast().status());
            }
            // Without the later static error, the same prefix really fails in generated execution.
            assertInstanceOf(EvaluationResult.RuntimeFailure.class,
                    session.submit("runtime.lyra", prefix));
            assertEquals(before, session.workspaceState());
            assertEquals(SourceRecordStatus.RUNTIME_FAILURE, session.sourceRecords().getLast().status());
        }
    }

    @Test
    void metadataViewsAndCleanupCheckOwnerBeforeAccessingCommittedWorkspace() throws Exception {
        try (LyraSession session = LyraSession.open()) {
            assertInstanceOf(EvaluationResult.Success.class,
                    session.submit("prior.lyra", "let @pub @mut count :I32 = 40"));
            WorkspaceState.Committed before = session.workspaceState();
            List<Runnable> operations = List.of(session::workspaceState, session::sourceRecords,
                    session::lifecycleState, session::reset, session::close);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread other = new Thread(() -> {
                try {
                    for (Runnable operation : operations) {
                        assertEquals("LYR-THREAD",
                                assertThrows(LyraThreadException.class, operation::run).code());
                    }
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            });
            other.start();
            other.join(5_000);
            assertFalse(other.isAlive());
            assertNull(failure.get());
            assertEquals(before, session.workspaceState());
            assertEquals(1, session.sourceRecords().size());
            assertEquals(SessionLifecycleState.OPEN, session.lifecycleState());
            session.reset();
            assertTrue(session.workspaceState().bindings().isEmpty());
            assertEquals(before.revision(), session.workspaceState().revision());
            assertTrue(before.bindings().containsKey("count"), "earlier immutable metadata remains unchanged");
        }
    }

    @Test
    void repeatedLabelsUseReservedCompilerIdentitiesAndCommitDistinctModules() {
        try (LyraSession session = LyraSession.open()) {
            EvaluationResult.Success first = assertInstanceOf(
                    EvaluationResult.Success.class,
                    session.submit("repeated.lyra", "let @pub first :I32 = 1"));
            WorkspaceState.Committed before = session.workspaceState();
            EvaluationResult.Success second = assertInstanceOf(
                    EvaluationResult.Success.class,
                    session.submit("repeated.lyra", "let @pub second :I32 = 2"));

            assertEquals(new SessionRevision(1), first.revision());
            assertEquals(new SessionRevision(2), second.revision());
            assertEquals(new SessionRevision(1), before.revision());
            assertEquals(1, before.bindings().size());
            assertEquals(2, session.workspaceState().bindings().size());
            assertEquals(2, session.workspaceState().moduleRevisions().size());
            SourceRecord firstRecord = session.sourceRecords().getFirst();
            SourceRecord secondRecord = session.sourceRecords().getLast();
            assertTrue(firstRecord.compilerSourceId().value().startsWith("repl/submission-"));
            assertNotEquals(firstRecord.compilerSourceId(), secondRecord.compilerSourceId());
            for (SourceRecord record : session.sourceRecords()) {
                assertEquals("repeated.lyra", record.source().origin().label());
                assertEquals(SourceRecordStatus.COMMITTED, record.status());
                assertTrue(session.workspaceState().moduleRevisions()
                        .containsKey("path:" + record.compilerSourceId().value()));
            }
        }
    }

    @Test
    void repeatedUrisCommitDistinctModulesAndMapLaterDiagnosticsExactlyOnce() {
        URI uri = URI.create("file:///workspace/repeated.lyra");
        SourceOrigin origin = new SourceOrigin(
                "selection", Optional.of(uri), Optional.of(4L), 20, 21);
        try (LyraSession session = LyraSession.open()) {
            assertInstanceOf(EvaluationResult.Success.class,
                    session.submit(new EvaluationSource(origin, "1")));
            EvaluationResult.Success second = assertInstanceOf(EvaluationResult.Success.class,
                    session.submit(new EvaluationSource(origin, "2")));
            assertEquals(new SessionRevision(2), second.revision());
            WorkspaceState.Committed before = session.workspaceState();
            assertEquals(2, before.moduleRevisions().size());
            SourceRecord firstRecord = session.sourceRecords().getFirst();
            SourceRecord secondRecord = session.sourceRecords().getLast();
            assertTrue(firstRecord.compilerSourceId().value().startsWith("repl/submission-"));
            assertNotEquals(firstRecord.compilerSourceId(), secondRecord.compilerSourceId());
            assertTrue(before.moduleRevisions()
                    .containsKey("path:" + firstRecord.compilerSourceId().value()));
            assertTrue(before.moduleRevisions()
                    .containsKey("path:" + secondRecord.compilerSourceId().value()));
            assertEquals(origin, firstRecord.source().origin());
            assertEquals(origin, secondRecord.source().origin());

            String text = "\"\uD83D\uDE00\"\nmissing";
            SourceOrigin failureOrigin = new SourceOrigin(
                    origin.label(), origin.uri(), origin.documentVersion(),
                    20, 20 + text.length());
            EvaluationResult.CompilationFailure failure = assertInstanceOf(
                    EvaluationResult.CompilationFailure.class,
                    session.submit(new EvaluationSource(failureOrigin, text)));
            Diagnostic diagnostic = failure.diagnostics().getFirst();
            assertEquals(CompilerDiagnosticCodes.RESOLVE_UNRESOLVED_NAME, diagnostic.code());
            assertEquals(SourceSpan.of(SourceId.uri(uri),
                            20 + text.indexOf("missing"), 20 + text.length()),
                    diagnostic.primarySpan());
            assertEquals(second.revision(), failure.revision());
            assertEquals(before, session.workspaceState());
            SourceRecord failedRecord = session.sourceRecords().getLast();
            assertNotEquals(firstRecord.compilerSourceId(), failedRecord.compilerSourceId());
            assertNotEquals(secondRecord.compilerSourceId(), failedRecord.compilerSourceId());
            assertEquals(failureOrigin, failedRecord.source().origin());
            assertEquals(failure.diagnostics(), failedRecord.diagnostics());
            assertEquals(SourceRecordStatus.COMPILATION_FAILURE, failedRecord.status());
            assertTrue(failedRecord.publishedRevision().isEmpty());
        }
    }

    @Test
    void compilationFailurePublishesNeitherPendingMetadataNorARevision() {
        try (LyraSession session = LyraSession.open()) {
            EvaluationResult.CompilationFailure failure = assertInstanceOf(
                    EvaluationResult.CompilationFailure.class,
                    session.submit(EvaluationSource.of(
                            "bad.lyra", "let @pub answer :I32 = \"wrong\"")));

            assertTrue(failure.diagnostics().stream()
                    .anyMatch(value -> value.code().equals(CompilerDiagnosticCodes.TYPE_MISMATCH)));
            assertEquals(SessionRevision.initial(), failure.revision());
            assertEquals(SessionRevision.initial(), session.workspaceState().revision());
            assertTrue(session.workspaceState().bindings().isEmpty());
            assertTrue(session.workspaceState().moduleRevisions().isEmpty());
            assertEquals(SourceRecordStatus.COMPILATION_FAILURE,
                    session.sourceRecords().getFirst().status());
        }
    }

    @Test
    void runtimeFailureDoesNotPublishStagedMetadataAndLeavesSessionUsable() {
        try (LyraSession session = LyraSession.open()) {
            EvaluationResult.RuntimeFailure failure = assertInstanceOf(
                    EvaluationResult.RuntimeFailure.class,
                    session.submit(EvaluationSource.of(
                            "runtime-failure.lyra",
                            "let zero :I32 = 0 "
                                    + "let boom :Fn<;F64> = (=> | | (/ 1 zero)) "
                                    + "let @pub answer :F64 = ::boom[]")));

            assertEquals(new SessionRevision(0), failure.revision());
            assertEquals("LYR-ARITH", failure.code());
            assertFalse(failure.frames().isEmpty(), "execution failures retain their source frames");
            assertTrue(session.workspaceState().bindings().isEmpty());
            assertTrue(session.workspaceState().moduleRevisions().isEmpty());
            assertEquals(SourceRecordStatus.RUNTIME_FAILURE,
                    session.sourceRecords().getFirst().status());

            EvaluationResult.Success recovered = assertInstanceOf(
                    EvaluationResult.Success.class,
                    session.submit(EvaluationSource.of(
                            "recovered.lyra", "let @pub answer :I32 = 42")));
            assertEquals(new SessionRevision(1), recovered.revision());
        }
    }

    @Test
    void publicRedeclarationAndImportedLinkageAreStructuredFailures() {
        try (LyraSession session = LyraSession.open()) {
            assertInstanceOf(EvaluationResult.Success.class,
                    session.submit(EvaluationSource.of(
                            "first.lyra", "let @pub answer :I32 = 1")));
            WorkspaceState.Committed before = session.workspaceState();

            var externalReference = assertInstanceOf(EvaluationResult.Success.class,
                    session.submit(EvaluationSource.of("reference.lyra", "answer")));
            assertEquals("1", assertInstanceOf(ValueSnapshot.Scalar.class, externalReference.value().orElseThrow().data()).value());
            before = session.workspaceState();

            EvaluationResult.CompilationFailure redeclaration = assertInstanceOf(
                    EvaluationResult.CompilationFailure.class,
                    session.submit(EvaluationSource.of(
                            "second.lyra", "let @pub answer :I32 = 2")));
            assertEquals(CompilerDiagnosticCodes.RESOLVE_PUBLIC_REDECLARATION,
                    redeclaration.diagnostics().getFirst().code());
            assertEquals(before, session.workspaceState());

            EvaluationResult.Success imported = assertInstanceOf(
                    EvaluationResult.Success.class,
                    session.submit(EvaluationSource.of("import.lyra", "import std->io")));
            assertTrue(imported.diagnostics().isEmpty());
            assertEquals(new SessionRevision(3), imported.revision());
            assertEquals(new SessionRevision(3), session.workspaceState().revision());
            assertEquals(1, session.workspaceState().bindings().size());
        }
    }

    @Test
    void sourceOriginsAreRetainedAndCompilerDiagnosticsUseUtf16OriginOffsets() {
        String text = "let @pub answer :I32 = \"wrong\"";
        SourceOrigin origin = new SourceOrigin(
                "selection",
                Optional.of(URI.create("file:///workspace/demo.lyra")),
                Optional.of(4L),
                20,
                20 + text.length());
        try (LyraSession session = LyraSession.open()) {
            EvaluationResult.CompilationFailure failure = assertInstanceOf(
                    EvaluationResult.CompilationFailure.class,
                    session.submit(new EvaluationRequest(
                            EvaluationId.create(),
                            SessionRevision.initial(),
                            new EvaluationSource(origin, text))));

            Diagnostic diagnostic = failure.diagnostics().getFirst();
            assertEquals(SourceId.uri(origin.uri().orElseThrow()),
                    diagnostic.primarySpan().sourceId());
            assertTrue(diagnostic.primarySpan().startOffset() >= origin.originStartOffset());
            SourceRecord record = session.sourceRecords().getFirst();
            assertEquals(origin, record.source().origin());
            assertEquals(text, record.source().text());
        }
    }

    @Test
    void resetClearsScratchMetadataWithoutRollingBackRevisionOrSourceRecords() {
        try (LyraSession session = LyraSession.open()) {
            assertInstanceOf(EvaluationResult.Success.class,
                    session.submit(EvaluationSource.of(
                            "scratch.lyra", "let @pub answer :I32 = 1")));
            SessionRevision committedRevision = session.workspaceState().revision();

            session.reset();

            assertEquals(committedRevision, session.workspaceState().revision());
            assertTrue(session.workspaceState().bindings().isEmpty());
            assertTrue(session.workspaceState().moduleRevisions().isEmpty());
            assertEquals(1, session.sourceRecords().size());
            EvaluationResult.Success afterReset = assertInstanceOf(
                    EvaluationResult.Success.class,
                    session.submit(EvaluationSource.of(
                            "after-reset.lyra", "let @pub answer :I32 = 2")));
            assertEquals(committedRevision.next(), afterReset.revision());
        }
    }

    @Test
    void busyAdmissionAndIdentitySpecificCancellationRemainBounded() throws Exception {
        CountDownLatch resolverEntered = new CountDownLatch(1);
        CountDownLatch releaseResolver = new CountDownLatch(1);
        AtomicReference<LyraSession> sessionReference = new AtomicReference<>();
        SourceResolver resolver = logicalModule -> {
            resolverEntered.countDown();
            try {
                assertTrue(releaseResolver.await(5, TimeUnit.SECONDS),
                        "controller did not release the resolver");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("resolver interrupted", interrupted);
            }
            return Optional.empty();
        };
        SessionOptions options = SessionOptions.builder().resolver(resolver).build();
        try (LyraSession session = LyraSession.open(options)) {
            sessionReference.set(session);
            EvaluationId activeId = EvaluationId.create();
            AtomicReference<EvaluationResult> busyResult = new AtomicReference<>();
            AtomicReference<Boolean> cancelled = new AtomicReference<>(false);
            AtomicReference<Throwable> controllerFailure = new AtomicReference<>();
            Thread controller = new Thread(() -> {
                try {
                    assertTrue(resolverEntered.await(5, TimeUnit.SECONDS),
                            "compiler did not invoke the configured resolver");
                    busyResult.set(sessionReference.get().submit(
                            EvaluationSource.of("busy.lyra", "")));
                    assertFalse(sessionReference.get().cancel(EvaluationId.create()));
                    cancelled.set(sessionReference.get().cancel(activeId));
                } catch (Throwable failure) {
                    controllerFailure.set(failure);
                } finally {
                    releaseResolver.countDown();
                }
            });
            controller.start();

            EvaluationResult.Cancelled result = assertInstanceOf(
                    EvaluationResult.Cancelled.class,
                    session.submit(new EvaluationRequest(
                            activeId,
                            SessionRevision.initial(),
                            EvaluationSource.of("outer.lyra", "import missing"))));
            controller.join(10_000);

            assertFalse(controller.isAlive());
            assertNull(controllerFailure.get());
            assertTrue(cancelled.get());
            assertEquals(EvaluationStatus.BUSY, busyResult.get().status());
            assertEquals(activeId, result.cancellation().orElseThrow().evaluationId());
            assertEquals(SessionRevision.initial(), result.revision());
            assertEquals(SessionRevision.initial(), session.workspaceState().revision());
            assertTrue(session.workspaceState().bindings().isEmpty());
            assertEquals(SourceRecordStatus.CANCELLED,
                    session.sourceRecords().getFirst().status());
        }
    }

    @Test
    void ownerAndClosedMisuseAreDeterministicAndCloseCreatesNoWorkerThread() throws Exception {
        LyraSession session = LyraSession.open();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                session.submit(EvaluationSource.of("wrong-thread.lyra", ""));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        other.start();
        other.join();
        assertInstanceOf(LyraThreadException.class, failure.get());

        session.close();
        EvaluationResult.Closed closed = assertInstanceOf(
                EvaluationResult.Closed.class,
                session.submit(EvaluationSource.of("closed.lyra", "")));
        assertEquals(EvaluationStatus.CLOSED, closed.status());
        assertFalse(session.cancel(EvaluationId.create()));
        assertThrows(LyraLifecycleException.class, session::reset);
    }

    @Test
    void retainedSourceLimitsRejectWithoutEvictingEarlierOrigins() {
        SessionOptions options = SessionOptions.builder()
                .maxSourceRecords(1)
                .maxSourceCharacters(32)
                .build();
        try (LyraSession session = LyraSession.open(options)) {
            assertInstanceOf(EvaluationResult.Success.class,
                    session.submit(EvaluationSource.of("first.lyra", "")));
            EvaluationResult.CompilationFailure rejected = assertInstanceOf(
                    EvaluationResult.CompilationFailure.class,
                    session.submit(EvaluationSource.of("second.lyra", "")));
            assertEquals(CompilerDiagnosticCodes.EMIT_UNSUPPORTED_FEATURE,
                    rejected.diagnostics().getFirst().code());
            assertEquals(1, session.sourceRecords().size());
            assertEquals("first.lyra",
                    session.sourceRecords().getFirst().source().origin().label());
            assertEquals(new SessionRevision(1), session.workspaceState().revision());
        }
    }
}
