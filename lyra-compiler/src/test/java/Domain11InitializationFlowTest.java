import io.mindspice.lyra.compiler.ast.SyntaxProgram;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.parse.Parser;
import io.mindspice.lyra.compiler.semantic.ResolvedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.SemanticFlowAnalyzer;
import io.mindspice.lyra.compiler.semantic.TypedExpressionKind;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.grammar.GrammarMatcher;
import io.mindspice.lyra.compiler.grammar.GrammarProgram;
import io.mindspice.lyra.compiler.semantic.flow.CallableSummaryResult;
import io.mindspice.lyra.compiler.semantic.flow.EagerEffectWitness;
import io.mindspice.lyra.compiler.semantic.flow.SemanticFlowEvent;
import io.mindspice.lyra.compiler.semantic.flow.SemanticFlowFacts;
import io.mindspice.lyra.compiler.semantic.flow.SemanticFlowResult;
import io.mindspice.lyra.compiler.semantic.flow.ValueFormula;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.ModuleRevision;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.source.SourceSpan;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused Gate 11D.2 coverage for the canonical typed flow/eager handoff. */
public final class Domain11InitializationFlowTest {
    @Test
    public void factsAreImmutableDeterministicAndBoundaryCompact() {
        TypedSemanticGraph typed = typed(single("let @mut values :Array<I32> = Array[0] "
                + "let @mut alias :Array<I32> = values "
                + "let changed = (alias[0] := 1) "
                + "let result = values[0]"));

        SemanticFlowFacts first = facts(typed);
        SemanticFlowFacts second = facts(typed);

        assertEquals(first, second);
        assertFalse(first.normalizedExpressions().isEmpty());
        assertTrue(first.events().stream().anyMatch(event ->
                event.kind() == SemanticFlowEvent.Kind.DECLARATION));
        assertTrue(first.events().stream().anyMatch(event ->
                event.kind() == SemanticFlowEvent.Kind.MUTATION));
        assertTrue(first.events().stream().noneMatch(event ->
                event.value().getClass().getName().contains("BindingFlowState")));
        assertThrowsUnsupported(first);
        DeclarationId declaration = declaration(typed, "values");
        assertThrows(UnsupportedOperationException.class,
                () -> first.declarationValues().put(
                        declaration, first.valueAtDeclaration(declaration).orElseThrow()));
        assertThrows(UnsupportedOperationException.class,
                () -> first.effectsByInitializer().put(declaration, List.of()));
    }

    @Test
    public void shortCircuitContinuationDoesNotSeeTheSkippedPathState() {
        ModuleGraph graph = pair(
                "gate11d2_short_circuit",
                "import gate11d2_short_circuit_lib "
                        + "let original :Fn<;I32> = "
                        + "(=> | | gate11d2_short_circuit_lib->:.value) "
                        + "let replacement :Fn<;I32> = (=> | | 1) "
                        + "let @mut selected :Fn<;I32> = original "
                        + "let @pub result :Bool = "
                        + "(and #T (selected := replacement) (selected))",
                "let @pub value :I32 = 1");
        TypedSemanticGraph typed = typed(graph);
        ModuleId library = ModuleId.path("gate11d2_short_circuit_lib.lyra");

        assertTrue(facts(typed).effectsFor(declaration(typed, "result")).stream()
                .noneMatch(effect -> effect.targetModule().equals(library)));
    }

    @Test
    public void tupleAliasesObserveExactArrayElementReplacement() {
        ModuleGraph graph = pair(
                "gate11d2_tuple_alias",
                "import gate11d2_tuple_alias_lib "
                        + "let original :Fn<;I32> = "
                        + "(=> | | gate11d2_tuple_alias_lib->:.value) "
                        + "let replacement :Fn<;I32> = (=> | | 1) "
                        + "let functions :Array<Fn<;I32>> = Array[original] "
                        + "let @mut aliases :Tuple<Array<Fn<;I32>>,Array<Fn<;I32>>> = "
                        + "Tuple[functions functions] "
                        + "let changed = (aliases:.0[0] := replacement) "
                        + "let @pub result :I32 = (aliases:.1[0])",
                "let @pub value :I32 = 1");
        TypedSemanticGraph typed = typed(graph);
        ModuleId library = ModuleId.path("gate11d2_tuple_alias_lib.lyra");

        assertTrue(facts(typed).effectsFor(declaration(typed, "result")).stream()
                .noneMatch(effect -> effect.targetModule().equals(library)));
    }

    @Test
    public void missingTypedLambdaFactFailsExplicitlyWithoutPublishingFacts() throws Exception {
        TypedSemanticGraph typed = typed(single(
                "let function :Fn<;I32> = (=> | | 1) "
                        + "let @pub value :I32 = (function)"));
        java.lang.reflect.Field lambdas = TypedSemanticGraph.class.getDeclaredField("lambdas");
        java.lang.reflect.Field lambdasById = TypedSemanticGraph.class.getDeclaredField("lambdasById");
        lambdas.setAccessible(true);
        lambdasById.setAccessible(true);
        lambdas.set(typed, List.of());
        lambdasById.set(typed, Map.of());

        SemanticFlowResult.Failure failure = assertInstanceOf(
                SemanticFlowResult.Failure.class, SemanticFlowAnalyzer.analyze(typed));
        assertEquals(CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                failure.failure().kind());
        assertTrue(failure.optionalValue().isEmpty());
    }

    @Test
    public void localComputedFunctionValueCanBeReturnedAndCalledInsideLambdas() {
        TypedSemanticGraph typed = typed(single(
                "let make :Fn<I32;Fn<;I32>> = "
                        + "(=> |value :I32| (=> | | value)) "
                        + "let computed :Fn<;I32> = (make 7) "
                        + "let returnComputed :Fn<;Fn<;I32>> = (=> | | computed) "
                        + "let directCall :Fn<;I32> = (=> | | ::computed[]) "
                        + "let callableCall :Fn<;I32> = (=> | | (computed)) "
                        + "let returned :Fn<;I32> = (returnComputed) "
                        + "let @pub first :I32 = (returned) "
                        + "let @pub second :I32 = (directCall) "
                        + "let @pub third :I32 = (callableCall)"));
        SemanticFlowFacts flow = facts(typed);

        assertTrue(flow.valueAtDeclaration(declaration(typed, "returned"))
                .orElseThrow().alternatives().stream()
                .flatMap(value -> value.callableFlows().stream())
                .anyMatch(callable -> !callable.capturedValues().isEmpty()));
        assertTrue(typed.initializationPlan().cycles().isEmpty());
        assertTrue(flow.valueAtDeclaration(declaration(typed, "first")).isPresent());
        assertTrue(flow.valueAtDeclaration(declaration(typed, "second")).isPresent());
        assertTrue(flow.valueAtDeclaration(declaration(typed, "third")).isPresent());
    }

    @Test
    public void importedComputedFunctionValueCanBeReturnedInsideALambda() {
        ModuleGraph graph = pair(
                "gate11d2_computed_return",
                "import gate11d2_computed_return_lib "
                        + "let returnComputed :Fn<;Fn<;I32>> = "
                        + "(=> | | gate11d2_computed_return_lib->:.computed) "
                        + "let @pub returned :Fn<;I32> = (returnComputed)",
                "let decoy :Fn<;I32> = (=> | | 99) "
                        + "let make :Fn<I32;Fn<;I32>> = "
                        + "(=> |value :I32| (=> | | value)) "
                        + "let @pub computed :Fn<;I32> = (make 7)");
        TypedSemanticGraph typed = typed(graph);
        SemanticFlowFacts flow = facts(typed);
        ModuleId library = ModuleId.path("gate11d2_computed_return_lib.lyra");
        var returnedCallable = flow.valueAtDeclaration(declaration(typed, "returned"))
                .orElseThrow().alternatives().stream()
                .flatMap(value -> value.callableFlows().stream())
                .findFirst().orElseThrow();
        var computedCallable = flow.valueAtDeclaration(
                        declaration(typed, library, "computed"))
                .orElseThrow().alternatives().stream()
                .flatMap(value -> value.callableFlows().stream())
                .findFirst().orElseThrow();
        var decoy = typed.declarations().stream()
                .filter(value -> value.moduleId().equals(library)
                        && value.name().equals("decoy"))
                .flatMap(value -> value.initializerLambda().stream())
                .findFirst().orElseThrow();

        assertEquals(computedCallable.lambdaId(), returnedCallable.lambdaId());
        assertEquals(computedCallable.creationSite(), returnedCallable.creationSite());
        assertTrue(returnedCallable.creationSite().isPresent());
        assertFalse(returnedCallable.lambdaId().filter(decoy::equals).isPresent(),
                "a same-signature sibling is not a substitute for computed identity");
        assertFalse(returnedCallable.capturedValues().isEmpty());
        DeclarationId returned = declaration(typed, "returned");
        assertTrue(flow.effectsFor(returned).stream()
                .anyMatch(effect -> effect.targetModule().equals(library)));
        assertTrue(typed.initializationPlan().dependencies().stream()
                .anyMatch(edge -> edge.initializerDeclaration()
                        .filter(returned::equals).isPresent()
                        && edge.toModule().equals(library)));
    }

    @Test
    public void importedComputedNilableFunctionValueCanReturnNil() {
        ModuleGraph graph = pair(
                "gate11d2_computed_nilable_return",
                "import gate11d2_computed_nilable_return_lib "
                        + "let get :Fn<;@nil Fn<;I32>> = "
                        + "(=> | | gate11d2_computed_nilable_return_lib->:.computed) "
                        + "let @nil returned :Fn<;I32> = (get)",
                "let @pub @nil computed :Fn<;I32> = #NIL");
        TypedSemanticGraph typed = typed(graph);
        SemanticFlowFacts flow = facts(typed);
        DeclarationId returned = declaration(typed, "returned");
        ModuleId library = ModuleId.path(
                "gate11d2_computed_nilable_return_lib.lyra");

        assertTrue(flow.valueAtDeclaration(returned).orElseThrow()
                .alternatives().stream()
                .flatMap(value -> value.nilProvenance().stream())
                .anyMatch(nil -> nil.route().isRoot()
                        && nil.sourceSpan().sourceId().equals(library.sourceId())));
        assertTrue(typed.initializationPlan().dependencies().stream()
                .anyMatch(edge -> edge.initializerDeclaration()
                        .filter(returned::equals).isPresent()
                        && edge.toModule().equals(library)));
    }

    @Test
    public void importedComputedFunctionValueCanBeNamespaceCalledInsideALambda() {
        ModuleGraph graph = pair(
                "gate11d2_computed_namespace_call",
                "import gate11d2_computed_namespace_call_lib "
                        + "let namespaceCall :Fn<;I32> = "
                        + "(=> | | gate11d2_computed_namespace_call_lib->::computed[]) "
                        + "let callableCall :Fn<;I32> = "
                        + "(=> | | (gate11d2_computed_namespace_call_lib->:.computed)) "
                        + "let @pub namespaceResult :I32 = (namespaceCall) "
                        + "let @pub callableResult :I32 = (callableCall)",
                "let make :Fn<I32;Fn<;I32>> = "
                        + "(=> |value :I32| (=> | | value)) "
                        + "let @pub computed :Fn<;I32> = (make 7)");
        TypedSemanticGraph typed = typed(graph);
        ModuleId library = ModuleId.path(
                "gate11d2_computed_namespace_call_lib.lyra");

        SemanticFlowFacts flow = facts(typed);
        EagerEffectWitness namespaceEffect = flow.effectsFor(
                        declaration(typed, "namespaceResult")).stream()
                .filter(effect -> effect.targetModule().equals(library)
                        && effect.kind()
                        == EagerEffectWitness.Kind.NAMESPACE_CALL)
                .findFirst().orElseThrow();
        EagerEffectWitness callableEffect = flow.effectsFor(
                        declaration(typed, "callableResult")).stream()
                .filter(effect -> effect.targetModule().equals(library)
                        && effect.kind()
                        == EagerEffectWitness.Kind.CALLABLE_CALL)
                .findFirst().orElseThrow();

        assertTrue(namespaceEffect.effectSite().isPresent());
        assertFalse(namespaceEffect.sourceSitePath().isEmpty());
        assertTrue(callableEffect.effectSite().isPresent());
        assertFalse(callableEffect.sourceSitePath().isEmpty());
    }

    @Test
    public void importedComputedCallableFlowsThroughHigherOrderDirectInvocation() {
        ModuleGraph graph = pair(
                "gate11d2_computed_higher_order",
                "import gate11d2_computed_higher_order_lib->{computed} "
                        + "let applyDirect :Fn<Fn<;I32>;I32> = "
                        + "(=> |f :Fn<;I32>| ::f[]) "
                        + "let outer :Fn<;I32> = (=> | | (applyDirect computed)) "
                        + "let @pub result :I32 = (outer)",
                "let @mut cell :I32 = 0 "
                        + "let make :Fn<;Fn<;I32>> = "
                        + "(=> | | (=> | | { let changed = (cell := 1) cell })) "
                        + "let @pub computed :Fn<;I32> = (make)");
        TypedSemanticGraph typed = typed(graph);
        SemanticFlowFacts flow = facts(typed);
        ModuleId library = ModuleId.path(
                "gate11d2_computed_higher_order_lib.lyra");
        DeclarationId cell = declaration(typed, library, "cell");

        assertTrue(flow.effectsFor(declaration(typed, "result")).stream()
                .anyMatch(effect -> effect.targetModule().equals(library)));
        assertTrue(flow.events().stream()
                .filter(event -> event.kind() == SemanticFlowEvent.Kind.CALL)
                .flatMap(event -> event.writes().stream())
                .anyMatch(write -> write.declarationId().equals(cell)
                        && write.isCaptureCellWrite()),
                "deferred higher-order calls retain transferred shared-cell writes");
    }

    @Test
    public void importedProjectedFunctionInitializerKeepsItsExactCallableIdentity() {
        ModuleGraph graph = pair(
                "gate11d2_computed_projection",
                "import gate11d2_computed_projection_lib "
                        + "let invoke :Fn<;I32> = "
                        + "(=> | | gate11d2_computed_projection_lib->::computed[]) "
                        + "let @pub result :I32 = (invoke)",
                "let first :Fn<;I32> = (=> | | 1) "
                        + "let second :Fn<;I32> = (=> | | 2) "
                        + "let functions :Array<Fn<;I32>> = Array[first second] "
                        + "let @pub computed :Fn<;I32> = functions[0]");
        TypedSemanticGraph typed = typed(graph);
        SemanticFlowFacts flow = facts(typed);
        ModuleId library = ModuleId.path(
                "gate11d2_computed_projection_lib.lyra");
        var first = typed.declarations().stream()
                .filter(value -> value.moduleId().equals(library)
                        && value.name().equals("first"))
                .flatMap(value -> value.initializerLambda().stream())
                .findFirst().orElseThrow();
        var second = typed.declarations().stream()
                .filter(value -> value.moduleId().equals(library)
                        && value.name().equals("second"))
                .flatMap(value -> value.initializerLambda().stream())
                .findFirst().orElseThrow();
        var computed = flow.valueAtDeclaration(
                        declaration(typed, library, "computed"))
                .orElseThrow().alternatives().stream()
                .flatMap(value -> value.callableFlows().stream())
                .findFirst().orElseThrow();

        assertEquals(Optional.of(first), computed.lambdaId());
        assertFalse(computed.lambdaId().filter(second::equals).isPresent());
        assertTrue(flow.valueAtDeclaration(declaration(typed, "result")).isPresent());
    }

    @Test
    public void importedComputedCallablePreservesCapturedSharedCellWrites() {
        ModuleGraph graph = pair(
                "gate11d2_computed_shared_cell",
                "import gate11d2_computed_shared_cell_lib "
                        + "let invoke :Fn<;Unit> = "
                        + "(=> | | gate11d2_computed_shared_cell_lib->::computed[]) "
                        + "let called :Unit = (invoke)",
                "let @mut cell :I32 = 0 "
                        + "let make :Fn<;Fn<;Unit>> = "
                        + "(=> | | (=> | | (cell := 1))) "
                        + "let @pub computed :Fn<;Unit> = (make)");
        TypedSemanticGraph typed = typed(graph);
        ModuleId library = ModuleId.path(
                "gate11d2_computed_shared_cell_lib.lyra");
        DeclarationId cell = declaration(typed, library, "cell");

        assertTrue(facts(typed).events().stream()
                .filter(event -> event.kind() == SemanticFlowEvent.Kind.CALL)
                .flatMap(event -> event.writes().stream())
                .anyMatch(write -> write.declarationId().equals(cell)
                        && write.isCaptureCellWrite()));
    }

    @Test
    public void mutableDirectFunctionSlotUsesItsCurrentSourceOrderedIdentity() {
        TypedSemanticGraph typed = typed(single(
                "let @mut selected :Fn<;Fn<;I32>> = "
                        + "(=> | | (=> | | 1)) "
                        + "let replacement :Fn<;Fn<;I32>> = "
                        + "(=> | | (=> | | 2)) "
                        + "let changed :Unit = (selected := replacement) "
                        + "let invoke :Fn<;Fn<;I32>> = (=> | | ::selected[]) "
                        + "let result :Fn<;I32> = (invoke)"));
        SemanticFlowFacts flow = facts(typed);
        var replacement = flow.callableSummaries()
                .summaryForDeclaration(declaration(typed, "replacement"))
                .orElseThrow();
        var expected = replacement.returnFormula().formulas().stream()
                .filter(ValueFormula.Lambda.class::isInstance)
                .map(ValueFormula.Lambda.class::cast)
                .findFirst().orElseThrow();
        var actual = flow.valueAtDeclaration(declaration(typed, "result"))
                .orElseThrow().alternatives().stream()
                .flatMap(value -> value.callableFlows().stream())
                .findFirst().orElseThrow();

        var expectedSite = typed.expressions().stream()
                .filter(expression -> expression.lambdaId()
                        .filter(expected.lambdaId()::equals).isPresent())
                .map(typed::flowSiteId)
                .findFirst();
        assertEquals(Optional.of(expected.lambdaId()), actual.lambdaId());
        assertEquals(expectedSite, actual.creationSite());
    }

    @Test
    public void importedMutableFunctionSlotUsesItsCurrentNamespaceIdentity() {
        ModuleGraph graph = pair(
                "gate11d2_mutable_namespace_identity",
                "import gate11d2_mutable_namespace_identity_lib "
                        + "let invoke :Fn<;Fn<;I32>> = (=> | | "
                        + "gate11d2_mutable_namespace_identity_lib->::selected[]) "
                        + "let result :Fn<;I32> = (invoke)",
                "let @pub @mut selected :Fn<;Fn<;I32>> = "
                        + "(=> | | (=> | | 1)) "
                        + "let replacement :Fn<;Fn<;I32>> = "
                        + "(=> | | (=> | | 2)) "
                        + "let changed :Unit = (selected := replacement)");
        TypedSemanticGraph typed = typed(graph);
        SemanticFlowFacts flow = facts(typed);
        ModuleId library = ModuleId.path(
                "gate11d2_mutable_namespace_identity_lib.lyra");
        var replacement = flow.callableSummaries()
                .summaryForDeclaration(
                        declaration(typed, library, "replacement"))
                .orElseThrow();
        var expected = replacement.returnFormula().formulas().stream()
                .filter(ValueFormula.Lambda.class::isInstance)
                .map(ValueFormula.Lambda.class::cast)
                .findFirst().orElseThrow();
        var actual = flow.valueAtDeclaration(declaration(typed, "result"))
                .orElseThrow().alternatives().stream()
                .flatMap(value -> value.callableFlows().stream())
                .findFirst().orElseThrow();

        assertEquals(Optional.of(expected.lambdaId()), actual.lambdaId());
        assertTrue(flow.effectsFor(declaration(typed, "result")).stream()
                .anyMatch(effect -> effect.targetModule().equals(library)
                        && effect.kind()
                        == EagerEffectWitness.Kind.NAMESPACE_CALL));
    }

    @Test
    public void computedCallableReplacementKeepsSourceOrderedFunctionIdentities() {
        TypedSemanticGraph typed = typed(single(
                "let makeFirst :Fn<;Fn<;I32>> = (=> | | (=> | | 1)) "
                        + "let makeSecond :Fn<;Fn<;I32>> = (=> | | (=> | | 2)) "
                        + "let selected :Fn<;I32> = (makeFirst) "
                        + "let readOld :Fn<;Fn<;I32>> = (=> | | selected) "
                        + "let selected :Fn<;I32> = (makeSecond) "
                        + "let readNew :Fn<;Fn<;I32>> = (=> | | selected) "
                        + "let oldValue :Fn<;I32> = (readOld) "
                        + "let newValue :Fn<;I32> = (readNew)"));
        SemanticFlowFacts flow = facts(typed);
        var oldCallable = flow.valueAtDeclaration(declaration(typed, "oldValue"))
                .orElseThrow().alternatives().stream()
                .flatMap(value -> value.callableFlows().stream())
                .findFirst().orElseThrow();
        var newCallable = flow.valueAtDeclaration(declaration(typed, "newValue"))
                .orElseThrow().alternatives().stream()
                .flatMap(value -> value.callableFlows().stream())
                .findFirst().orElseThrow();

        assertFalse(oldCallable.lambdaId().equals(newCallable.lambdaId()),
                "same-signature computed values keep their exact source lambda identity");
        assertFalse(oldCallable.creationSite().equals(newCallable.creationSite()));
    }

    @Test
    public void higherOrderMutableParameterWritesUpdateTheCallerRoute() {
        ModuleGraph graph = pair(
                "gate11d2_parameter_write",
                "import gate11d2_parameter_write_lib->{values id} "
                        + "let replacement :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| Array[0]) "
                        + "let @mut functions :Array<Fn<Array<I32>;Array<I32>>> = Array[id] "
                        + "let update :Fn<@mut Array<Fn<Array<I32>;Array<I32>>>;Unit> = "
                        + "(=> |items| (items[0] := replacement)) "
                        + "let called = (update functions) "
                        + "let @mut result :Array<I32> = (functions[0] values) "
                        + "let changed = (result[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0] "
                        + "let @pub id :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| input)");
        TypedSemanticGraph typed = typed(graph);
        assertTrue(typed.initializationPlan().cycles().isEmpty());
        assertTrue(facts(typed).events().stream().anyMatch(event ->
                event.kind() == SemanticFlowEvent.Kind.MUTATION));
    }

    @Test
    public void mutableParameterAliasWritesRebaseToTheCallerExactRoute() {
        ModuleGraph graph = pair(
                "gate11d2_parameter_alias_write",
                "import gate11d2_parameter_alias_write_lib "
                        + "let original :Fn<;I32> = "
                        + "(=> | | gate11d2_parameter_alias_write_lib->::read[]) "
                        + "let replacement :Fn<;I32> = (=> | | 1) "
                        + "let @mut functions :Array<Fn<;I32>> = Array[original] "
                        + "let update :Fn<@mut Array<Fn<;I32>>;Unit> = (=> |items| { "
                        + "let @mut alias :Array<Fn<;I32>> = items "
                        + "alias[0] := replacement }) "
                        + "let called = (update functions) "
                        + "let @pub result :I32 = (functions[0])",
                "let @pub read :Fn<;I32> = (=> | | 1)");
        TypedSemanticGraph typed = typed(graph);
        SemanticFlowFacts flow = facts(typed);
        ModuleId library = ModuleId.path("gate11d2_parameter_alias_write_lib.lyra");

        assertTrue(flow.callableSummaries()
                .summaryForDeclaration(declaration(typed, "update")).orElseThrow()
                .parameterWrites().stream().anyMatch(write ->
                        write.parameter() == 0
                                && write.route().equals(
                                io.mindspice.lyra.compiler.semantic.flow.ProjectionPath
                                        .arrayElement(0))));
        assertTrue(flow.effectsFor(declaration(typed, "result")).stream()
                        .noneMatch(effect -> effect.targetModule().equals(library)),
                flow.eagerEffectFacts().toString());
    }

    @Test
    public void exactParameterAliasReplacementCallsOnlyTheReplacement() {
        ModuleGraph graph = pair(
                "gate11d2_exact_alias_call",
                "import gate11d2_exact_alias_call_lib "
                        + "let original :Fn<;I32> = "
                        + "(=> | | gate11d2_exact_alias_call_lib->::read[]) "
                        + "let replacement :Fn<;I32> = (=> | | 1) "
                        + "let @mut functions :Array<Fn<;I32>> = Array[original] "
                        + "let update :Fn<@mut Array<Fn<;I32>>;I32> = (=> |items| { "
                        + "let @mut alias :Array<Fn<;I32>> = items "
                        + "let changed = (alias[0] := replacement) "
                        + "(alias[0]) }) "
                        + "let @pub result :I32 = (update functions)",
                "let @pub read :Fn<;I32> = (=> | | 1)");
        TypedSemanticGraph typed = typed(graph);
        SemanticFlowFacts flow = facts(typed);
        ModuleId library = ModuleId.path("gate11d2_exact_alias_call_lib.lyra");

        assertTrue(flow.effectsFor(declaration(typed, "result")).stream()
                        .noneMatch(effect -> effect.targetModule().equals(library)),
                flow.eagerEffectFacts().toString());
        List<io.mindspice.lyra.compiler.semantic.flow.CapturedCellWrite> writes =
                flow.events().stream()
                        .filter(event -> event.kind() == SemanticFlowEvent.Kind.CALL)
                        .flatMap(event -> event.writes().stream())
                        .toList();
        assertEquals(1, writes.size());
        assertEquals(declaration(typed, "functions"), writes.getFirst().declarationId());
    }

    @Test
    public void mutableLocalAliasMayMutateAnImmutableCapturedArray() {
        ModuleGraph graph = pair(
                "gate11d2_immutable_capture_alias",
                "import gate11d2_immutable_capture_alias_lib "
                        + "let original :Fn<;I32> = "
                        + "(=> | | gate11d2_immutable_capture_alias_lib->::read[]) "
                        + "let replacement :Fn<;I32> = (=> | | 1) "
                        + "let values :Array<Fn<;I32>> = Array[original] "
                        + "let mutate :Fn<;Unit> = (=> | | { "
                        + "let @mut alias :Array<Fn<;I32>> = values "
                        + "alias[0] := replacement }) "
                        + "let called :Unit = (mutate) "
                        + "let @pub result :I32 = (values[0])",
                "let @pub read :Fn<;I32> = (=> | | 1)");
        TypedSemanticGraph typed = typed(graph);
        SemanticFlowFacts flow = facts(typed);
        var summary = flow.callableSummaries()
                .summaryForDeclaration(declaration(typed, "mutate")).orElseThrow();

        assertTrue(typed.initializationPlan().cycles().isEmpty());
        assertTrue(summary.capturedCellWrites().isEmpty());
        assertTrue(summary.writes().stream()
                .allMatch(write -> write.isCaptureAggregateWrite()
                        && write.sharedCellId().isEmpty()));
        assertTrue(flow.effectsFor(declaration(typed, "result")).stream()
                .noneMatch(effect -> effect.targetModule().equals(
                        ModuleId.path("gate11d2_immutable_capture_alias_lib.lyra"))));
    }

    @Test
    public void mutableCaptureAliasWritesPreserveWildcardRoutesAndReachableEffects() {
        ModuleId main = ModuleId.path("gate11d2_capture_alias_main.lyra");
        ModuleId left = ModuleId.path("gate11d2_capture_alias_left.lyra");
        ModuleId right = ModuleId.path("gate11d2_capture_alias_right.lyra");
        LogicalModuleId leftLogical = LogicalModuleId.parse("gate11d2_capture_alias_left");
        LogicalModuleId rightLogical = LogicalModuleId.parse("gate11d2_capture_alias_right");
        ModuleGraph graph = graph(
                List.of(
                        module(main, "import gate11d2_capture_alias_left "
                                + "import gate11d2_capture_alias_right "
                                + "let original :Fn<;I32> = "
                                + "(=> | | gate11d2_capture_alias_left->::read[]) "
                                + "let replacement :Fn<;I32> = "
                                + "(=> | | gate11d2_capture_alias_right->::read[]) "
                                + "let @mut functions :Array<Fn<;I32>> = Array[original] "
                                + "let index :I32 = 0 "
                                + "let update :Fn<;Unit> = (=> | | { "
                                + "let @mut alias :Array<Fn<;I32>> = functions "
                                + "alias[index] := replacement }) "
                                + "let called = (update) "
                                + "let @pub result :I32 = (functions[0])"),
                        module(left, "let @pub read :Fn<;I32> = (=> | | 1)"),
                        module(right, "let @pub read :Fn<;I32> = (=> | | 2)")),
                main,
                List.of(
                        new ModuleGraph.Edge(main, leftLogical, left,
                                SourceSpan.of(main.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(main, rightLogical, right,
                                SourceSpan.of(main.sourceId(), 1, 2))),
                Map.of(leftLogical, left, rightLogical, right));
        TypedSemanticGraph typed = typed(graph);
        SemanticFlowFacts flow = facts(typed);

        assertTrue(flow.callableSummaries()
                .summaryForDeclaration(declaration(typed, main, "update")).orElseThrow()
                .capturedCellWrites().stream().anyMatch(write ->
                        write.route().containsWildcard()));
        List<ModuleId> targets = flow.effectsFor(declaration(typed, main, "result")).stream()
                .map(EagerEffectWitness::targetModule)
                .filter(target -> target.equals(left) || target.equals(right))
                .distinct()
                .toList();
        assertEquals(List.of(left, right), targets, flow.eagerEffectFacts().toString());
    }

    @Test
    public void sourceOrderAndCompletedClosureEffectsUseCurrentCallableOnly() {
        ModuleGraph graph = pair(
                "gate11d2_order",
                "import gate11d2_order_lib "
                        + "let original :Fn<;I32> = (=> | | 1) "
                        + "let replacement :Fn<;I32> = "
                        + "(=> | | gate11d2_order_lib->:.value) "
                        + "let @mut selected :Fn<;I32> = original "
                        + "let @pub early :I32 = (selected) "
                        + "let changed = (selected := replacement) "
                        + "let @pub late :I32 = (selected)",
                "let @pub value :I32 = 1");
        TypedSemanticGraph typed = typed(graph);
        SemanticFlowFacts facts = facts(typed);
        DeclarationId early = declaration(typed, "early");
        DeclarationId late = declaration(typed, "late");

        assertTrue(facts.effectsFor(early).stream().noneMatch(effect ->
                effect.targetModule().equals(ModuleId.path("gate11d2_order_lib.lyra"))));
        assertTrue(facts.effectsFor(late).stream().anyMatch(effect ->
                effect.targetModule().equals(ModuleId.path("gate11d2_order_lib.lyra"))));
        assertTrue(typed.initializationPlan().dependencies().stream().anyMatch(edge ->
                edge.initializerDeclaration().filter(late::equals).isPresent()));
    }

    @Test
    public void dynamicFunctionProjectionUnionsEveryReachableAlternative() {
        ModuleId main = ModuleId.path("gate11d2_dynamic_main.lyra");
        ModuleId left = ModuleId.path("gate11d2_dynamic_left.lyra");
        ModuleId right = ModuleId.path("gate11d2_dynamic_right.lyra");
        LogicalModuleId leftLogical = LogicalModuleId.parse("gate11d2_dynamic_left");
        LogicalModuleId rightLogical = LogicalModuleId.parse("gate11d2_dynamic_right");
        ModuleGraph graph = graph(
                List.of(
                        module(main, "import gate11d2_dynamic_left import gate11d2_dynamic_right "
                                + "let left :Fn<;I32> = (=> | | gate11d2_dynamic_left->:.value) "
                                + "let right :Fn<;I32> = (=> | | gate11d2_dynamic_right->:.value) "
                                + "let functions :Array<Fn<;I32>> = Array[left right] "
                                + "let index :I32 = 0 "
                                + "let @pub value :I32 = (functions[index])"),
                        module(left, "let @pub value :I32 = 1"),
                        module(right, "let @pub value :I32 = 2")),
                main,
                List.of(
                        new ModuleGraph.Edge(main, leftLogical, left,
                                SourceSpan.of(main.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(main, rightLogical, right,
                                SourceSpan.of(main.sourceId(), 1, 2))),
                Map.of(leftLogical, left, rightLogical, right));
        TypedSemanticGraph typed = typed(graph);
        SemanticFlowFacts facts = facts(typed);
        List<ModuleId> targets = facts.effectsFor(declaration(typed, main, "value")).stream()
                .map(EagerEffectWitness::targetModule)
                .filter(target -> target.equals(left) || target.equals(right))
                .distinct()
                .toList();

        assertEquals(List.of(left, right), targets, facts.eagerEffectFacts().toString());
        assertTrue(typed.expressions().stream().anyMatch(expression ->
                expression.kind() == TypedExpressionKind.CALLABLE_CALL));
        List<io.mindspice.lyra.compiler.semantic.flow.EagerEffectFact> ordered =
                new java.util.ArrayList<>(facts.eagerEffectFacts());
        ordered.sort(java.util.Comparator.naturalOrder());
        assertEquals(ordered, facts.eagerEffectFacts());
    }

    @Test
    public void projectedCallableDeclarationPlaceholdersDoNotMaskReachableLambdas() {
        ModuleGraph graph = pair(
                "gate11d2_projected_callable",
                "import gate11d2_projected_callable_lib "
                        + "let read :Fn<;I32> = "
                        + "(=> | | gate11d2_projected_callable_lib->::read[]) "
                        + "let functions :Array<Fn<;I32>> = Array[read] "
                        + "let select :Fn<Array<Fn<;I32>>;Fn<;I32>> = "
                        + "(=> |items :Array<Fn<;I32>>| items[0]) "
                        + "let selected :Fn<;I32> = (select functions) "
                        + "let @pub result :I32 = (selected)",
                "let @pub read :Fn<;I32> = (=> | | 1)");
        TypedSemanticGraph typed = typed(graph);
        ModuleId library = ModuleId.path("gate11d2_projected_callable_lib.lyra");
        SemanticFlowFacts flow = facts(typed);

        assertTrue(flow.effectsFor(declaration(typed, "result")).stream()
                        .anyMatch(effect -> effect.targetModule().equals(library)),
                flow.eagerEffectFacts().toString());
    }

    @Test
    public void eagerCycleMembershipExcludesPreviouslyVisitedUnrelatedModules() {
        ModuleId main = ModuleId.path("gate11d2_cycle_main.lyra");
        ModuleId dependency = ModuleId.path("gate11d2_cycle_dependency.lyra");
        ModuleId unrelated = ModuleId.path("gate11d2_cycle_unrelated.lyra");
        LogicalModuleId dependencyLogical = LogicalModuleId.parse("gate11d2_cycle_dependency");
        LogicalModuleId unrelatedLogical = LogicalModuleId.parse("gate11d2_cycle_unrelated");
        ModuleGraph graph = graph(
                List.of(
                        module(main, "import gate11d2_cycle_dependency "
                                + "import gate11d2_cycle_unrelated "
                                + "let @pub value :I32 = gate11d2_cycle_dependency->:.value"),
                        module(dependency, "import gate11d2_cycle_main "
                                + "let @pub value :I32 = gate11d2_cycle_main->:.value"),
                        module(unrelated, "let @pub value :I32 = 1")),
                main,
                List.of(
                        new ModuleGraph.Edge(main, dependencyLogical, dependency,
                                SourceSpan.of(main.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(main, unrelatedLogical, unrelated,
                                SourceSpan.of(main.sourceId(), 1, 2)),
                        new ModuleGraph.Edge(dependency, LogicalModuleId.parse("gate11d2_cycle_main"), main,
                                SourceSpan.of(dependency.sourceId(), 0, 1))),
                Map.of(dependencyLogical, dependency, unrelatedLogical, unrelated,
                        LogicalModuleId.parse("gate11d2_cycle_main"), main));
        PhaseResult<TypedSemanticGraph> result = typedResult(graph);

        assertTrue(result instanceof PhaseResult.Failure<?>);
        assertTrue(result.diagnostics().getFirst().code().value().equals("LYC-MODULE-004"));
        assertTrue(result.diagnostics().getFirst().relatedSpans().stream()
                .allMatch(related -> !related.span().sourceId().equals(unrelated.sourceId())));
    }

    @Test
    public void eagerCycleWitnessUsesOnlyTheRepeatedDeclarationSuffix() {
        ModuleId outer = ModuleId.path("aaa_gate11d2_cycle_outer.lyra");
        ModuleId left = ModuleId.path("bbb_gate11d2_cycle_left.lyra");
        ModuleId right = ModuleId.path("ccc_gate11d2_cycle_right.lyra");
        LogicalModuleId leftLogical = LogicalModuleId.parse("bbb_gate11d2_cycle_left");
        LogicalModuleId rightLogical = LogicalModuleId.parse("ccc_gate11d2_cycle_right");
        LogicalModuleId outerLogical = LogicalModuleId.parse("aaa_gate11d2_cycle_outer");
        ModuleGraph graph = graph(
                List.of(
                        module(outer, "import bbb_gate11d2_cycle_left "
                                + "let @pub value :I32 = bbb_gate11d2_cycle_left->:.value"),
                        module(left, "import ccc_gate11d2_cycle_right "
                                + "let @pub value :I32 = ccc_gate11d2_cycle_right->:.value"),
                        module(right, "import aaa_gate11d2_cycle_outer "
                                + "import bbb_gate11d2_cycle_left "
                                + "let @pub value :I32 = bbb_gate11d2_cycle_left->:.value")),
                outer,
                List.of(
                        new ModuleGraph.Edge(outer, leftLogical, left,
                                SourceSpan.of(outer.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(left, rightLogical, right,
                                SourceSpan.of(left.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(right, outerLogical, outer,
                                SourceSpan.of(right.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(right, leftLogical, left,
                                SourceSpan.of(right.sourceId(), 1, 2))),
                Map.of(leftLogical, left, rightLogical, right, outerLogical, outer));

        PhaseResult<TypedSemanticGraph> result = typedResult(graph);
        Diagnostic diagnostic = result.diagnostics().getFirst();
        List<SourceSpan> related = diagnostic.relatedSpans().stream()
                .map(value -> value.span()).toList();

        assertTrue(result instanceof PhaseResult.Failure<?>);
        assertEquals("LYC-MODULE-004", diagnostic.code().value());
        assertTrue(diagnostic.summary().contains(left.toString()));
        assertTrue(diagnostic.summary().contains(right.toString()));
        assertFalse(diagnostic.summary().contains(outer.toString()));
        assertEquals(related.stream().distinct().count(), related.size());
        assertTrue(related.stream().allMatch(span ->
                span.sourceId().equals(left.sourceId()) || span.sourceId().equals(right.sourceId())));
    }

    @Test
    public void recursiveSummaryIntegrationRemainsFunctionOnlyWhenNoEagerValueIsRead() {
        TypedSemanticGraph typed = typed(single(
                "let f :Fn<;I32> = (=> | | ::f[]) "
                        + "let @pub value :I32 = (f)"));
        SemanticFlowFacts facts = facts(typed);

        assertTrue(facts.callableSummaries().orderedSummaries().stream()
                .allMatch(summary -> summary.isFixedPoint()));
        assertTrue(typed.initializationPlan().cycles().isEmpty());
    }

    @Test
    public void callableSummaryValueReadsStillExposeEagerValueCycles() {
        PhaseResult<TypedSemanticGraph> result = typedResult(single(
                "let @pub value :I32 = (f) "
                        + "let f :Fn<;I32> = (=> | | value)"));
        assertTrue(result instanceof PhaseResult.Failure<?>);
        assertEquals("LYC-MODULE-004", result.diagnostics().getFirst().code().value());
    }

    @Test
    public void completedClosureEffectsPersistThroughReturnedSiblingClosures() {
        ModuleGraph graph = pair(
                "gate11d2_completed_closure",
                "import gate11d2_completed_closure_lib->{values} "
                        + "let make :Fn<;Tuple<Fn<;Unit>,Fn<;Array<I32>>>> = (=> | | { "
                        + "let @mut cell :Array<I32> = values "
                        + "let write :Fn<;Unit> = (=> | | { "
                        + "let local :Array<I32> = Array[0] cell := local }) "
                        + "let read :Fn<;Array<I32>> = (=> | | cell) "
                        + "Tuple[write read] }) "
                        + "let pair :Tuple<Fn<;Unit>,Fn<;Array<I32>>> = (make) "
                        + "let write :Fn<;Unit> = pair:.0 "
                        + "let read :Fn<;Array<I32>> = pair:.1 "
                        + "let called = (write) "
                        + "let @mut result :Array<I32> = (read) "
                        + "let changed = (result[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]");
        TypedSemanticGraph typed = typed(graph);
        assertTrue(typed.initializationPlan().cycles().isEmpty());
        assertTrue(facts(typed).events().stream().anyMatch(event ->
                event.kind() == SemanticFlowEvent.Kind.CALL));
    }

    @Test
    public void summarizedShortCircuitContinuationUsesOnlyTheFullyEvaluatedPath() {
        ModuleGraph graph = pair(
                "gate11d2_summary_short_circuit",
                "import gate11d2_summary_short_circuit_lib "
                        + "let runner :Fn<;Bool> = (=> | | { "
                        + "let original :Fn<;I32> = "
                        + "(=> | | gate11d2_summary_short_circuit_lib->::read[]) "
                        + "let replacement :Fn<;I32> = (=> | | 1) "
                        + "let @mut selected :Fn<;I32> = original "
                        + "(and #T (selected := replacement) (selected)) }) "
                        + "let @pub result :Bool = (runner)",
                "let @pub read :Fn<;I32> = (=> | | 1)");
        TypedSemanticGraph typed = typed(graph);
        ModuleId library = ModuleId.path("gate11d2_summary_short_circuit_lib.lyra");

        assertTrue(facts(typed).effectsFor(declaration(typed, "result")).stream()
                .noneMatch(effect -> effect.targetModule().equals(library)));
    }

    @Test
    public void predicateBindingCarriesThePredicateCallableIntoTheTruthyBranch() {
        ModuleGraph graph = pair(
                "gate11d2_predicate_binding",
                "import gate11d2_predicate_binding_lib "
                        + "let original :Fn<;I32> = "
                        + "(=> | | gate11d2_predicate_binding_lib->::read[]) "
                        + "let runner :Fn<@nil Fn<;I32>;I32> = "
                        + "(=> |candidate| (candidate value -> (value) : 0)) "
                        + "let @pub result :I32 = (runner original)",
                "let @pub read :Fn<;I32> = (=> | | 1)");
        TypedSemanticGraph typed = typed(graph);
        ModuleId library = ModuleId.path("gate11d2_predicate_binding_lib.lyra");

        assertTrue(facts(typed).effectsFor(declaration(typed, "result")).stream()
                .anyMatch(effect -> effect.targetModule().equals(library)));
    }

    @Test
    public void summarizedDirectCallUsesTheCurrentLocallyReboundTarget() {
        ModuleGraph graph = pair(
                "gate11d2_direct_rebinding",
                "import gate11d2_direct_rebinding_lib "
                        + "let original :Fn<;I32> = (=> | | 1) "
                        + "let replacement :Fn<;I32> = "
                        + "(=> | | gate11d2_direct_rebinding_lib->::read[]) "
                        + "let @mut selected :Fn<;I32> = original "
                        + "let runner :Fn<;I32> = (=> | | { "
                        + "let changed = (selected := replacement) "
                        + "let invoked = ::selected[] invoked }) "
                        + "let @pub result :I32 = (runner)",
                "let @pub read :Fn<;I32> = (=> | | 1)");
        TypedSemanticGraph typed = typed(graph);
        ModuleId library = ModuleId.path("gate11d2_direct_rebinding_lib.lyra");

        SemanticFlowFacts flow = facts(typed);
        assertTrue(flow.effectsFor(declaration(typed, "result")).stream()
                        .anyMatch(effect -> effect.targetModule().equals(library)),
                flow.eagerEffectFacts().toString());
    }

    @Test
    public void nestedLambdaCreationRetainsItsEagerCaptureRead() {
        ModuleGraph graph = pair(
                "gate11d2_nested_capture",
                "import gate11d2_nested_capture_lib->{value} "
                        + "let make :Fn<;Fn<;I32>> = (=> | | (=> | | value)) "
                        + "let @pub created :Fn<;I32> = (make)",
                "let @pub value :I32 = 1");
        TypedSemanticGraph typed = typed(graph);
        ModuleId library = ModuleId.path("gate11d2_nested_capture_lib.lyra");

        SemanticFlowFacts flow = facts(typed);
        assertTrue(flow.events().stream()
                        .filter(event -> event.kind() == SemanticFlowEvent.Kind.CAPTURE)
                        .flatMap(event -> event.effects().stream())
                        .anyMatch(effect -> effect.kind() == EagerEffectWitness.Kind.VALUE_READ
                                && effect.targetModule().equals(library)),
                flow.events().toString());
    }

    @Test
    public void intrinsicCallablesRemainDiscoverableInsideSolvedSummaries() {
        ModuleGraph graph = intrinsicGraph(
                "gate11d2_intrinsic",
                "import std->io->{println} "
                        + "let apply :Fn<Fn<String;Unit>;Unit> = "
                        + "(=> |output| (output \"ok\")) "
                        + "let write :Fn<;Unit> = (=> | | (apply println)) "
                        + "let @pub result :Unit = (write)");
        TypedSemanticGraph typed = typed(graph);
        ModuleId intrinsic = ModuleId.uri(URI.create("lyra:intrinsic/std/io"));
        SemanticFlowFacts flow = facts(typed);

        assertFalse(flow.callableSummaries().intrinsicDeclarations().isEmpty());
        assertTrue(flow.effectsFor(declaration(typed, "result")).stream()
                .anyMatch(effect -> effect.targetModule().equals(intrinsic)
                        && effect.isCall()));
    }

    @Test
    public void crossModuleFunctionCallsDoNotBecomeEagerValueCycles() {
        ModuleId main = ModuleId.path("gate11d2_function_main.lyra");
        ModuleId dependency = ModuleId.path("gate11d2_function_dependency.lyra");
        LogicalModuleId dependencyLogical = LogicalModuleId.parse("gate11d2_function_dependency");
        LogicalModuleId mainLogical = LogicalModuleId.parse("gate11d2_function_main");
        ModuleGraph graph = graph(
                List.of(
                        module(main, "import gate11d2_function_dependency->{g} "
                                + "let @pub f :Fn<;I32> = (=> | | 1) "
                                + "let @pub value :I32 = (g)"),
                        module(dependency, "import gate11d2_function_main->{f} "
                                + "let @pub g :Fn<;I32> = (=> | | (f)) "
                                + "let @pub value :I32 = (f)")),
                main,
                List.of(
                        new ModuleGraph.Edge(main, dependencyLogical, dependency,
                                SourceSpan.of(main.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(dependency, mainLogical, main,
                                SourceSpan.of(dependency.sourceId(), 0, 1))),
                Map.of(dependencyLogical, dependency, mainLogical, main));

        TypedSemanticGraph typed = typed(graph);
        assertTrue(typed.initializationPlan().cycles().isEmpty());
    }

    private static void assertThrowsUnsupported(SemanticFlowFacts facts) {
        assertThrows(UnsupportedOperationException.class,
                () -> facts.events().add(facts.events().getFirst()));
        assertThrows(UnsupportedOperationException.class,
                () -> facts.normalizedExpressions().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> facts.eagerEffectFacts().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> facts.eagerCycles().clear());
    }

    private static SemanticFlowFacts facts(TypedSemanticGraph typed) {
        SemanticFlowResult result = SemanticFlowAnalyzer.analyze(typed);
        SemanticFlowResult.Success success = assertInstanceOf(
                SemanticFlowResult.Success.class, result);
        return success.value();
    }

    private static TypedSemanticGraph typed(ModuleGraph graph) {
        return phaseValue(typedResult(graph));
    }

    private static PhaseResult<TypedSemanticGraph> typedResult(ModuleGraph graph) {
        PhaseResult<ResolvedSemanticGraph> resolved =
                io.mindspice.lyra.compiler.semantic.SemanticResolver.resolve(graph);
        if (resolved instanceof PhaseResult.Failure<?>) {
            throw new AssertionError(render(resolved));
        }
        return io.mindspice.lyra.compiler.semantic.TypeChecker.check(
                phaseValue(resolved));
    }

    private static DeclarationId declaration(TypedSemanticGraph graph, String name) {
        return graph.declarations().stream()
                .filter(value -> value.name().equals(name))
                .findFirst().orElseThrow().id();
    }

    private static DeclarationId declaration(
            TypedSemanticGraph graph, ModuleId module, String name) {
        return graph.declarations().stream()
                .filter(value -> value.moduleId().equals(module) && value.name().equals(name))
                .findFirst().orElseThrow().id();
    }

    private static ModuleGraph single(String source) {
        ModuleId id = ModuleId.path("gate11d2_single.lyra");
        return graph(List.of(module(id, source)), id, List.of(), Map.of());
    }

    private static ModuleGraph pair(String stem, String mainSource, String librarySource) {
        ModuleId main = ModuleId.path(stem + "_main.lyra");
        ModuleId library = ModuleId.path(stem + "_lib.lyra");
        LogicalModuleId logical = LogicalModuleId.parse(stem + "_lib");
        return graph(List.of(module(main, mainSource), module(library, librarySource)),
                main,
                List.of(new ModuleGraph.Edge(main, logical, library,
                        SourceSpan.of(main.sourceId(), 0, 1))),
                Map.of(logical, library));
    }

    private static ModuleGraph intrinsicGraph(String stem, String mainSource) {
        ModuleId main = ModuleId.path(stem + "_main.lyra");
        ModuleId intrinsic = ModuleId.uri(URI.create("lyra:intrinsic/std/io"));
        return graph(
                List.of(module(main, mainSource), module(intrinsic, "")),
                main,
                List.of(new ModuleGraph.Edge(
                        main, LogicalModuleId.STD_IO, intrinsic,
                        SourceSpan.of(main.sourceId(), 0, 1))),
                Map.of(LogicalModuleId.STD_IO, intrinsic));
    }

    private static ModuleGraph graph(
            List<ModuleGraph.Node> nodes,
            ModuleId root,
            List<ModuleGraph.Edge> edges,
            Map<LogicalModuleId, ModuleId> logical) {
        return CanonicalModuleGraph.create(root, nodes, edges, logical);
    }

    private static ModuleGraph.Node module(ModuleId id, String source) {
        SourceSnapshot snapshot = snapshot(id.sourceId(), source);
        SyntaxProgram program = parse(snapshot);
        return new ModuleGraph.Node(id,
                id.isPath()
                        ? Optional.of(LogicalModuleId.fromSourceId(id.sourceId()))
                        : Optional.empty(),
                snapshot, program, ModuleRevision.compute(snapshot));
    }

    private static SyntaxProgram parse(SourceSnapshot snapshot) {
        LexedSource lexed = phaseValue(Lexer.lex(snapshot));
        GrammarProgram grammar = phaseValue(GrammarMatcher.match(lexed));
        return phaseValue(Parser.parse(lexed, grammar));
    }

    private static SourceSnapshot snapshot(SourceId id, String source) {
        return phaseValue(SourceSnapshot.capture(
                id,
                PhysicalSourceKey.uri(URI.create("memory:" + id.value().replace('/', '_'))),
                source.getBytes(StandardCharsets.UTF_8)));
    }

    private static <T extends io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact>
    T phaseValue(PhaseResult<T> result) {
        if (!(result instanceof PhaseResult.Success<T> success)) {
            throw new AssertionError(render(result));
        }
        return success.value();
    }

    private static String render(PhaseResult<?> result) {
        return result.diagnostics().stream().map(Diagnostic::render).toList().toString();
    }
}
