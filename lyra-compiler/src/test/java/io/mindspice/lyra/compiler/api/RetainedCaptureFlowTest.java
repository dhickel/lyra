package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.semantic.flow.BindingFlowState;
import io.mindspice.lyra.compiler.semantic.flow.SemanticFlowEvent;
import io.mindspice.lyra.compiler.session.SessionSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Canonical compiler coverage for producer-owned captured-cell flow facts. */
final class RetainedCaptureFlowTest {
    @Test
    void retainedSharedCellUsesProducerSummaryAfterLexicalReplacement() {
        SessionCompileResult.Success producer = success(
                "producer.lyra",
                "let @mut count :I32 = 1 "
                        + "let add :Fn<I32;I32> = "
                        + "(=> |n| { count := (+ count n) count })",
                SessionSnapshot.empty());
        DeclarationId count = producer.stagedSnapshot().binding("count")
                .orElseThrow().declarationId();
        DeclarationId add = producer.stagedSnapshot().binding("add")
                .orElseThrow().declarationId();

        SessionCompileResult.Success replacement = success(
                "replacement.lyra", "let count :String = \"new lexical binding\"",
                producer.stagedSnapshot());
        SessionCompileResult.Success consumer = success(
                "consumer.lyra", "(add 1)", replacement.stagedSnapshot());

        assertTrue(consumer.typedIr().isValidated());
        BindingFlowState state = consumer.typedGraph().semanticFlowFacts()
                .finalStates().values().stream().findFirst().orElseThrow();
        assertEquals(producer.stagedSnapshot().binding("count").orElseThrow().contract(),
                state.binding(count).orElseThrow().contract());
        assertTrue(state.sharedCell(count).isPresent(),
                "the retained producer cell must remain in the canonical boundary state");
        assertFalse(state.binding(count).orElseThrow().alternatives().isEmpty());

        var retained = state.binding(add).orElseThrow().alternatives().alternatives().stream()
                .flatMap(value -> value.callableFlows().stream())
                .findFirst().orElseThrow();
        assertTrue(consumer.resolvedGraph().sessionFlowCertificate().orElseThrow()
                .certifiesCallable(retained));
        assertTrue(consumer.typedGraph().semanticFlowFacts().events().stream()
                .filter(event -> event.kind() == SemanticFlowEvent.Kind.CALL)
                .flatMap(event -> event.writes().stream())
                .anyMatch(write -> write.isCaptureCellWrite()
                        && write.declarationId().equals(count)));
    }

    private static SessionCompileResult.Success success(
            String label, String source, SessionSnapshot snapshot) {
        return assertInstanceOf(SessionCompileResult.Success.class,
                LyraCompiler.compileSession(new SessionCompileRequest(label, source, snapshot)));
    }
}
