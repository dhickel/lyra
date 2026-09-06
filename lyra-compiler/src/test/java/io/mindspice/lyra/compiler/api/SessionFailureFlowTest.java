package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.semantic.flow.ValueAlternatives;
import io.mindspice.lyra.compiler.session.SessionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Failed execution may stop at any prefix, not at the successful final flow state. */
class SessionFailureFlowTest {
    @Test
    void attemptedCertificateIncludesOverwrittenIntermediateCallableAndCellWrites() {
        var first = compile("let @mut f :Fn<;I32> = (=> || 1) let get :Fn<;Fn<;I32>> = (=> || f)",
                SessionSnapshot.empty());
        var declaration = first.stagedSnapshot().bindings().get("f").declarationId();
        var attempted = compile("f := (=> :I32 || 2) let zero :I32 = 0 (% 1 zero) f := (=> :I32 || 3)",
                first.stagedSnapshot());
        Set<LambdaId> expected = new HashSet<>(lambdas(first.flowCertificate().value(declaration).orElseThrow()));
        attempted.typedGraph().lambdas().forEach(lambda -> expected.add(lambda.id()));
        assertEquals(3, expected.size());
        var retained = attempted.retainAttemptedFlow().flowCertificate().orElseThrow();
        assertTrue(lambdas(retained.value(declaration).orElseThrow()).containsAll(expected),
                "a failed attempt must retain the original, intermediate, and final callable alternatives");
        expected.forEach(lambda -> assertTrue(retained.certifiesLambda(lambda)));
        assertEquals(first.stagedSnapshot().bindings(), attempted.retainAttemptedFlow().bindings());
        assertEquals(first.revision(), attempted.retainAttemptedFlow().revision());
    }

    @Test
    void attemptedCertificateIncludesWritesInsideInvokedSummariesAndNestedBlocks() {
        var first = compile("let @mut f :Fn<;I32> = (=> || 1) let get :Fn<;Fn<;I32>> = (=> || f)",
                SessionSnapshot.empty());
        var id = first.stagedSnapshot().bindings().get("f").declarationId();
        var next = compile("""
                let intermediate :Fn<;I32> = (=> || 2)
                let set :Fn<;Bool> = (=> || {
                    f := intermediate
                    let zero :I32 = 0
                    (% 1 zero)
                    f := (=> :I32 || 3)
                    #T
                })
                { (set) }
                """, first.stagedSnapshot());
        Set<LambdaId> expected = new HashSet<>(lambdas(first.flowCertificate().value(id).orElseThrow()));
        expected.addAll(lambdas(next.flowCertificate().value(id).orElseThrow()));
        var intermediate = next.stagedSnapshot().bindings().get("intermediate").declarationId();
        expected.addAll(lambdas(next.flowCertificate().value(intermediate).orElseThrow()));
        assertEquals(3, expected.size(), () -> "expected candidates=" + expected
                + " final=" + next.flowCertificate().boundaryState()
                + " events=" + next.typedGraph().semanticFlowFacts().events());
        var proof = next.retainAttemptedFlow().flowCertificate().orElseThrow();
        assertTrue(lambdas(proof.value(id).orElseThrow()).containsAll(expected));
    }

    @Test
    void attemptedCertificateRetainsThePreCallValueOfAnEscapedLexicalCell() {
        var first = compile("""
                let pair :Tuple<Fn<;Fn<;I32>>,Fn<Fn<;I32>;Bool>> = {
                    let @mut local :Fn<;I32> = (=> || 1)
                    Tuple[(=> :Fn<;I32> || local) (=> :Bool |value :Fn<;I32>| { local := value #T })]
                }
                """, SessionSnapshot.empty());
        var cell = first.typedGraph().declarations().stream().filter(value -> value.name().equals("local"))
                .findFirst().orElseThrow().id();
        var second = compile("""
                let intermediate :Fn<;I32> = (=> || 2)
                let last :Fn<;I32> = (=> || 3)
                (pair:.1 intermediate)
                let zero :I32 = 0
                (% 1 zero)
                (pair:.1 last)
                """, first.stagedSnapshot());
        Set<LambdaId> expected = new HashSet<>(lambdas(first.flowCertificate().value(cell).orElseThrow()));
        for (String name : java.util.List.of("intermediate", "last")) {
            expected.addAll(lambdas(second.flowCertificate().value(
                    second.stagedSnapshot().bindings().get(name).declarationId()).orElseThrow()));
        }
        assertEquals(3, expected.size());
        var retained = second.retainAttemptedFlow().flowCertificate().orElseThrow();
        assertTrue(lambdas(retained.sharedCell(cell).orElseThrow()).containsAll(expected));
    }

    private static Set<LambdaId> lambdas(ValueAlternatives values) {
        return values.alternatives().stream().flatMap(value -> value.callableFlows().stream())
                .flatMap(value -> value.lambdaId().stream()).collect(Collectors.toSet());
    }

    private static SessionCompileResult.Success compile(String source, SessionSnapshot snapshot) {
        var result = LyraCompiler.compileSession(new SessionCompileRequest("failure-flow.lyra", source, snapshot));
        return assertInstanceOf(SessionCompileResult.Success.class, result, result.diagnostics().toString());
    }
}
