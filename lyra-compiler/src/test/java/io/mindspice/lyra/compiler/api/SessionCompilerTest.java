package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Phase;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.IdentityAllocator;
import io.mindspice.lyra.compiler.session.SessionRevision;
import io.mindspice.lyra.compiler.session.SessionSnapshot;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.PrimitiveType;
import io.mindspice.lyra.runtime.LyraRuntime;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SessionCompilerTest {
    @Test
    void emptySnapshotCompilesARealExpressionArtifact() {
        SessionCompileResult result = LyraCompiler.compileSession(
                new SessionCompileRequest("expression.lyra", "42", SessionSnapshot.empty()));
        SessionCompileResult.Success success = assertInstanceOf(
                SessionCompileResult.Success.class, result);

        assertEquals(SessionRevision.initial(), success.baseRevision());
        assertEquals(new SessionRevision(1), success.revision());
        assertEquals(success.revision(), success.stagedSnapshot().revision());
        assertTrue(success.stagedSnapshot().bindings().isEmpty());
        assertTrue(success.stagedDeclarations().isEmpty());
        assertTrue(success.resolvedGraph().declarations().isEmpty());
        assertTrue(success.typedIr().isValidated());
        assertNotNull(success.artifact());
        assertFalse(success.artifact().classes().isEmpty());

        try (var loaded = LyraRuntime.load(success.artifact());
             var module = loaded.instantiate()) {
            assertEquals(1, module.metadata().modules().size());
        }
    }

    @Test
    void emptySnapshotStagesPublicDeclarationMetadataAndContinuesIdentityAllocation() {
        SessionCompileResult.Success first = assertInstanceOf(
                SessionCompileResult.Success.class,
                LyraCompiler.compileSession(new SessionCompileRequest(
                        "first.lyra", "let @pub answer :I32 = 42", SessionSnapshot.empty())));
        DeclarationId binding = first.stagedSnapshot().binding("answer").orElseThrow().declarationId();
        assertEquals(PrimitiveType.I32,
                first.stagedSnapshot().binding("answer").orElseThrow().type());
        assertTrue(first.stagedDeclarations().contains(binding));
        assertEquals(binding, first.resolvedGraph().exports().getFirst().declarationId());
        assertEquals(first.resolvedGraph().allocator(), first.stagedSnapshot().allocator());

        SessionCompileResult.Success second = assertInstanceOf(
                SessionCompileResult.Success.class,
                LyraCompiler.compileSession(new SessionCompileRequest(
                        "second.lyra", "let next :I32 = 7", first.stagedSnapshot())));
        DeclarationId secondId = second.stagedDeclarations().getFirst();
        assertTrue(secondId.ordinal() > binding.ordinal());
        assertEquals(new SessionRevision(1), second.baseRevision());
        assertEquals(new SessionRevision(2), second.revision());
        assertEquals(first.stagedSnapshot().binding("answer").orElseThrow(),
                second.stagedSnapshot().binding("answer").orElseThrow());
    }

    @Test
    void referencesToPriorSessionStateAreExplicitlyRejectedWithoutFakeSuccess() {
        SessionCompileResult.Success first = assertInstanceOf(
                SessionCompileResult.Success.class,
                LyraCompiler.compileSession(new SessionCompileRequest(
                        "prior.lyra", "let @pub count :I32 = 1", SessionSnapshot.empty())));

        SessionCompileResult.Failure failure = assertInstanceOf(
                SessionCompileResult.Failure.class,
                LyraCompiler.compileSession(new SessionCompileRequest(
                        "use-prior.lyra", "count", first.stagedSnapshot())));
        assertEquals(1, failure.diagnostics().size());
        assertEquals(CompilerDiagnosticCodes.SESSION_EXTERNAL_BINDING_UNSUPPORTED,
                failure.diagnostics().getFirst().code());
        assertEquals(first.stagedSnapshot().revision(), failure.baseRevision());
    }

    @Test
    void syntaxFailureIsStructuredAndOriginMapped() {
        String text = "let =";
        URI uri = URI.create("file:///workspace/repl.lyra");
        EvaluationSource source = new EvaluationSource(
                new SourceOrigin("selection", Optional.of(uri), Optional.of(4L), 11, 11 + text.length()),
                text);
        SessionCompileResult.Failure failure = assertInstanceOf(
                SessionCompileResult.Failure.class,
                LyraCompiler.compileSession(new SessionCompileRequest(source, SessionSnapshot.empty())));

        assertFalse(failure.diagnostics().isEmpty());
        assertEquals(Phase.PARSE, failure.diagnostics().getFirst().phase());
        assertEquals(SourceId.uri(uri), failure.diagnostics().getFirst().primarySpan().sourceId());
        assertEquals(15, failure.diagnostics().getFirst().primarySpan().startOffset());
        assertEquals(SessionRevision.initial(), failure.baseRevision());
    }

    @Test
    void requestRequiresBaseRevisionToMatchSnapshot() {
        SessionSnapshot snapshot = new SessionSnapshot(
                new SessionRevision(1), Map.of(), Map.of(), IdentityAllocator.initial());
        assertThrows(IllegalArgumentException.class,
                () -> SessionCompileRequest.builder()
                        .source("mismatch.lyra", "42")
                        .baseRevision(SessionRevision.initial())
                        .snapshot(snapshot)
                        .build());
    }

    @Test
    void snapshotsRemainImmutableAcrossSessionCompilation() {
        SessionSnapshot empty = SessionSnapshot.empty();
        SessionCompileResult.Success result = assertInstanceOf(
                SessionCompileResult.Success.class,
                LyraCompiler.compileSession(new SessionCompileRequest(
                        "immutable.lyra", "let @pub value :I32 = 1", empty)));

        assertTrue(empty.bindings().isEmpty());
        assertEquals(SessionRevision.initial(), empty.revision());
        assertThrows(UnsupportedOperationException.class,
                () -> result.stagedSnapshot().bindings().clear());
        assertEquals(result.resolvedGraph().allocator(), result.stagedSnapshot().allocator());
    }

    @Test
    void normalCompilerStillUsesItsIndependentInitialIdentitySpace() {
        CompileResult.Success normal = assertInstanceOf(
                CompileResult.Success.class,
                LyraCompiler.compile(CompileRequest.source(
                        "normal.lyra", "let @pub value :I32 = 1")));
        assertEquals("path:normal.lyra", normal.artifact().metadata()
                .rootModuleId().canonicalSpelling());
    }
}
