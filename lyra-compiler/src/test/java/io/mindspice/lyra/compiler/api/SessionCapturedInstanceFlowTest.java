package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.session.SessionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SessionCapturedInstanceFlowTest {
    @Test
    void independentFactoryCellsDoNotLoseEachOthersCallableAlternatives() {
        var first = compile("""
                let one :Fn<;I32> = (=> || 1)
                let two :Fn<;I32> = (=> || 2)
                let make :Fn<Fn<;I32>;Tuple<Fn<;Fn<;I32>>,Fn<Fn<;I32>;Bool>>> = (=> |initial| {
                    let @mut cell :Fn<;I32> = initial
                    Tuple[(=> :Fn<;I32> || cell) (=> :Bool |value :Fn<;I32>| { cell := value #T })]
                })
                let left = (make one)
                let right = (make two)
                """, SessionSnapshot.empty());
        var second = compile("let observedLeft = (left:.0)", first.stagedSnapshot());
        var third = compile("let observedRight = (right:.0)", second.stagedSnapshot());
        assertTrue(lambdas(third, "observedRight").containsAll(lambdas(first, "two")));
        var fourth = compile("(left:.1 one)", third.stagedSnapshot());
        var fifth = compile("let stillRight = (right:.0)", fourth.stagedSnapshot());
        assertTrue(lambdas(fifth, "stillRight").containsAll(lambdas(first, "two")));
    }

    private static Set<LambdaId> lambdas(SessionCompileResult.Success value, String name) {
        return value.flowCertificate().value(value.stagedSnapshot().binding(name).orElseThrow().declarationId())
                .orElseThrow().alternatives().stream().flatMap(valueFlow -> valueFlow.callableFlows().stream())
                .flatMap(callable -> callable.lambdaId().stream())
                .collect(Collectors.toSet());
    }

    private static SessionCompileResult.Success compile(String source, SessionSnapshot snapshot) {
        var result = LyraCompiler.compileSession(new SessionCompileRequest(
                "factory-" + snapshot.revision().value() + ".lyra", source, snapshot));
        return assertInstanceOf(SessionCompileResult.Success.class, result, result.diagnostics().toString());
    }
}
