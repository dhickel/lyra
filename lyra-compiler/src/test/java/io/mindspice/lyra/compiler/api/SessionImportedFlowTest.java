package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.session.SessionSnapshot;
import io.mindspice.lyra.compiler.session.SessionExecutionPlan;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ResolvedSource;
import io.mindspice.lyra.compiler.source.SourceResolver;
import io.mindspice.lyra.compiler.semantic.flow.SemanticFlowEvent;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SessionImportedFlowTest {
    @Test
    void laterDiamondAndReexportsRetainTheActualSelectedProducer() {
        var base = source("base", "let @pub value :I32 = 4 let @pub read :Fn<;I32> = (=> || value)");
        var left = source("left", "import @pub base->{value read}");
        var right = source("right", "import @pub base->{value as other read}");
        var first = compile("first.lyra", "import left as l import left->{read as selected}", SessionSnapshot.empty(), base, left);
        var original = first.snapshot().environment().module(base.logicalModule()).orElseThrow();
        var second = compile("second.lyra", "import right let result :I32 = (selected) (+ result right->:.other)", first.snapshot(), right);
        assertEquals(SessionExecutionPlan.WorkKind.REUSED, second.executionPlan().module(original.moduleId()).orElseThrow().kind());
        assertEquals(1, second.executionPlan().initializationOrder().size());
        assertEquals(right.sourceId(), second.executionPlan().initializationOrder().getFirst().sourceId());
        var selected = second.snapshot().importAlias("selected").orElseThrow();
        var contract = selected.moduleContract().orElseThrow();
        var read = contract.exports().stream().filter(value -> value.export().name().equals("read")).findFirst().orElseThrow();
        assertEquals(original.producerId(), read.origin().producerId());
        assertEquals(original.generationId(), read.origin().generationId());
        assertEquals(original.revision(), read.origin().revision());
        assertEquals(original.export("read").orElseThrow().originDeclaration(), selected.targetDeclaration().orElseThrow());
        assertEquals(first.snapshot().importAlias("selected"), second.snapshot().importAlias("selected"));
        var third = compile("third.lyra", "import left as l import left->{read as selected} (selected)", second.snapshot());
        assertTrue(third.executionPlan().initializationOrder().isEmpty());
        assertEquals(1, third.typedIr().modules().size());
        assertTrue(third.typedIr().imports().stream().allMatch(value -> value.producerContract().isPresent()));
        var conflict = LyraCompiler.compileSession(SessionCompileRequest.builder().source("bad.lyra", "import right as l")
                .snapshot(third.snapshot()).build());
        assertInstanceOf(SessionCompileResult.Failure.class, conflict);
        assertTrue(conflict.diagnostics().stream().anyMatch(value -> value.code().equals(
                io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes.RESOLVE_IMPORT_NAME_CONFLICT)), conflict.diagnostics().toString());
    }

    @Test
    void importedCallableAggregatesAndOwnershipSurviveLaterSubmissions() {
        var source = source("aggregate", """
                let @pub items :Array<I32> = Array<I32>[1]
                let @pub bundle :Tuple<Fn<;Array<I32>>,Array<Fn<I32;I32>>> =
                    Tuple[(=> || items) Array<Fn<I32;I32>>[(=> |n| (+ n 1))]]
                """);
        var first = compile("first.lyra", "import aggregate", SessionSnapshot.empty(), source);
        var second = compile("second.lyra", "let bundle = aggregate->:.bundle let items = bundle:.0 let f = bundle:.1[0] (f 3)", first.snapshot());
        assertEquals("I32", second.typedIr().rootModule().submissionResult().orElseThrow().type().canonicalSpelling());
        for (String text : List.of("let @mut items = aggregate->:.items items[0] := 9",
                "let bundle = aggregate->:.bundle let get = bundle:.0 let @mut items = (get) items[0] := 9")) {
            var failure = LyraCompiler.compileSession(SessionCompileRequest.builder().source("bad.lyra", text).snapshot(second.snapshot()).build());
            assertInstanceOf(SessionCompileResult.Failure.class, failure);
            assertTrue(failure.diagnostics().stream().anyMatch(value -> value.summary().contains("import")), failure.diagnostics().toString());
        }
    }

    @Test
    void mutableImportedCallableUsesTheCurrentProducerStateNotItsInitializerOrSameSignature() {
        var source = source("mutable", """
                let @pub @mut selected :Fn<;I32> = (=> || 1)
                let @pub choose :Fn<;Unit> = (=> || { selected := (=> :I32 || 2) () })
                """);
        var first = compile("first.lyra", "import mutable let read :Fn<;I32> = (=> || mutable->::selected[])", SessionSnapshot.empty(), source);
        var producer = first.snapshot().environment().module(source.logicalModule()).orElseThrow();
        var selected = producer.export("selected").orElseThrow().originDeclaration();
        var original = first.flowCertificate().value(selected).orElseThrow().alternatives().stream()
                .flatMap(value -> value.callableFlows().stream()).map(value -> value.lambdaId().orElseThrow()).findFirst().orElseThrow();
        var second = compile("second.lyra", "mutable->::choose[] mutable->::selected[]", first.snapshot());
        var after = second.flowCertificate().value(selected).orElseThrow().alternatives().stream()
                .flatMap(value -> value.callableFlows().stream()).map(value -> value.lambdaId().orElseThrow()).distinct().toList();
        assertEquals(1, after.size());
        assertNotEquals(original, after.getFirst());
        var third = compile("third.lyra", "(read) mutable->::selected[]", second.snapshot());
        var calls = third.typedGraph().semanticFlowFacts().eventsAt(third.moduleGraph().rootModule()).stream()
                .filter(value -> value.kind() == SemanticFlowEvent.Kind.CALL).toList();
        assertTrue(calls.stream().anyMatch(value -> value.lambdaId().filter(after.getFirst()::equals).isPresent()), calls.toString());
        assertTrue(calls.stream().noneMatch(value -> value.lambdaId().filter(original::equals).isPresent()));
    }

    @Test
    void discoveryReadsNewSourcesOnceAndDoesNotResolvePinnedTransitiveDependencies() {
        var base = source("base", "let @pub value :I32 = 3");
        var first = compile("first.lyra", "import base", SessionSnapshot.empty(), base);
        var left = source("left", "import base let @pub value :I32 = base->:.value");
        var right = source("right", "import base let @pub value :I32 = base->:.value");
        var calls = new AtomicInteger();
        SourceResolver resolver = logical -> {
            calls.incrementAndGet();
            assertNotEquals(base.logicalModule(), logical, "existing pins precede provider discovery");
            return List.of(left, right).stream().filter(value -> value.logicalModule().equals(logical)).findFirst();
        };
        var result = success(SessionCompileRequest.builder().source("second.lyra", "import left import right (+ left->:.value right->:.value)")
                .snapshot(first.snapshot()).resolver(resolver).build());
        assertEquals(2, calls.get());
        assertEquals(2, result.executionPlan().initializationOrder().size());
        assertEquals(4, result.moduleGraph().modules().size());
        assertEquals(3, result.typedIr().modules().size());
        assertEquals(1, result.executionPlan().newModules().stream().map(value -> value.generationId()).distinct().count());
        var reused = compile("third.lyra", "(+ left->:.value right->:.value)", result.snapshot());
        assertTrue(reused.executionPlan().initializationOrder().isEmpty());
        assertEquals(1, reused.typedIr().modules().size());
    }

    @Test
    void retainedMutableReexportHasAnExactCompiledFacadeContract() {
        var base = source("mutable_base", "let @pub @mut value :I32 = 1");
        var first = compile("first.lyra", "import mutable_base", SessionSnapshot.empty(), base);
        var middle = source("mutable_middle", "import @pub mutable_base->{value}");
        var second = compile("second.lyra", "import mutable_middle mutable_middle->:.value", first.snapshot(), middle);
        assertTrue(second.artifact().metadata().exports().stream().anyMatch(value -> value.name().equals("value")));
        var imported = second.snapshot().environment().module(middle.logicalModule()).orElseThrow().exports().getFirst();
        assertTrue(imported.contract().isMutable());
        assertEquals(first.snapshot().environment().module(base.logicalModule()).orElseThrow().moduleId(), imported.originModule());
    }

    private static ResolvedSource source(String name, String text) {
        return ResolvedSource.memory(LogicalModuleId.parse(name), URI.create("memory://imported/" + name + ".lyra"), text.getBytes(StandardCharsets.UTF_8));
    }

    private static SessionCompileResult.Success compile(String name, String text, SessionSnapshot snapshot, ResolvedSource... sources) {
        return success(SessionCompileRequest.builder().source(name, text).snapshot(snapshot).resolver(logical ->
                List.of(sources).stream().filter(value -> value.logicalModule().equals(logical)).findFirst()).build());
    }

    private static SessionCompileResult.Success success(SessionCompileRequest request) {
        var result = LyraCompiler.compileSession(request);
        return assertInstanceOf(SessionCompileResult.Success.class, result, () -> result.diagnostics().toString());
    }
}
