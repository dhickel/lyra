package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.ast.SyntaxProgram;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.grammar.GrammarMatcher;
import io.mindspice.lyra.compiler.grammar.GrammarProgram;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.parse.Parser;
import io.mindspice.lyra.compiler.semantic.flow.AggregateIdentityFact;
import io.mindspice.lyra.compiler.semantic.flow.CallableCallReference;
import io.mindspice.lyra.compiler.semantic.flow.CallableFlow;
import io.mindspice.lyra.compiler.semantic.flow.CallableSummary;
import io.mindspice.lyra.compiler.semantic.flow.CallableSummarySet;
import io.mindspice.lyra.compiler.semantic.flow.EagerEffectFact;
import io.mindspice.lyra.compiler.semantic.flow.EagerEffectWitness;
import io.mindspice.lyra.compiler.semantic.flow.FormulaAlternatives;
import io.mindspice.lyra.compiler.semantic.flow.OwnershipWitness;
import io.mindspice.lyra.compiler.semantic.flow.SemanticFlowEvent;
import io.mindspice.lyra.compiler.semantic.flow.SemanticFlowFacts;
import io.mindspice.lyra.compiler.semantic.flow.ProjectionPath;
import io.mindspice.lyra.compiler.semantic.flow.ValueAlternative;
import io.mindspice.lyra.compiler.semantic.flow.ValueAlternatives;
import io.mindspice.lyra.compiler.semantic.flow.ValueFormula;
import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.ModuleRevision;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.LyraType;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused Gate 11E coverage for the package-owned typed graph sealer. */
public final class Domain11SealingTest {
    @Test
    public void publicationAttachesImmutableFactsAndNestedCollections() {
        TypedSemanticGraph graph = typed(
                "let @mut values :Array<I32> = Array[0] "
                        + "let changed = (values[0] := 1)");
        SemanticFlowFacts facts = graph.semanticFlowFacts();

        assertFalse(facts.events().isEmpty());
        assertTrue(facts.events().stream().anyMatch(event ->
                event.kind() == SemanticFlowEvent.Kind.MUTATION));
        assertThrows(UnsupportedOperationException.class, () -> facts.events().clear());
        assertThrows(UnsupportedOperationException.class, () -> facts.callableSummaries()
                .summaries().clear());
        assertThrows(UnsupportedOperationException.class, () -> facts.declarationValues().clear());
        assertThrows(UnsupportedOperationException.class, () -> facts.effectsByInitializer().clear());
        facts.events().stream().flatMap(event -> event.writes().stream()).findFirst()
                .ifPresent(write -> assertThrows(UnsupportedOperationException.class,
                        () -> write.value().formulas().clear()));
    }

    @Test
    public void graphConstructionIsSealedAndFactsArePartOfDeterministicIdentity() {
        assertEquals(0, TypedSemanticGraph.class.getConstructors().length);
        assertThrows(NoSuchMethodException.class,
                () -> TypedSemanticGraph.class.getMethod("withInitializationPlan",
                        InitializationPlan.class));

        String source = "let first :I32 = 1 let second :I32 = (+ first 2)";
        TypedSemanticGraph first = typed(source);
        TypedSemanticGraph second = typed(source);
        assertEquals(first.semanticFlowFacts(), second.semanticFlowFacts());
        assertEquals(first.initializationPlan(), second.initializationPlan());
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());

        SemanticFlowFacts originalFacts = first.semanticFlowFacts();
        SemanticFlowFacts exactCopy = new SemanticFlowFacts(
                originalFacts.callableSummaries(), originalFacts.normalizedExpressions(),
                originalFacts.events(), originalFacts.eagerEffectFacts(),
                originalFacts.eagerCycles(), originalFacts.declarationValues());
        TypedSemanticGraph rebuilt = seal(first, exactCopy, first.initializationPlan());
        assertEquals(first, rebuilt);
    }

    @Test
    public void reconstructedResolvedGraphCannotEnterTheProducerOwnedSealingPath() {
        TypedSemanticGraph graph = typed("let value :I32 = 1");
        ResolvedSemanticGraph source = graph.resolvedGraph();
        ResolvedSemanticGraph reconstructed = new ResolvedSemanticGraph(
                source.moduleGraph(), source.scopeTree(), source.modules(),
                source.declarations(), source.references(), source.lambdas(),
                source.imports(), source.exports(), source.captures(),
                source.mutations(), source.syntaxLinks(), source.functionLinkage());
        assertEquals(source, reconstructed);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(reconstructed));
        assertTrue(failure.getMessage().contains(
                "no source-authoritative topology"));
    }

    @Test
    public void producerCertificationCannotBeReusedByAnEqualCoreClone() {
        TypedSemanticGraph graph = typed("let value :I32 = 1");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                SemanticTestSupport.seal(
                        graph, graph.resolvedGraph(), graph.modules(), graph.declarations(),
                        graph.references(), graph.lambdas(), graph.conversions(),
                        graph.expressions(), graph.contractsByDeclaration(),
                        graph.expressionsBySpan(), graph.mutations(),
                        graph.semanticFlowFacts(), graph.initializationPlan(),
                        graph.failureSites()));

        assertTrue(failure.getMessage().contains("another typed semantic core instance"));
    }

    @Test
    public void missingForeignAndWrongRouteFactsCannotBeSealed() {
        TypedSemanticGraph graph = typed(
                "let @mut values :Array<I32> = Array[0] "
                        + "let changed = (values[0] := 1)");
        SemanticFlowFacts source = graph.semanticFlowFacts();

        SemanticFlowFacts missingEvents = new SemanticFlowFacts(
                source.callableSummaries(), source.normalizedExpressions(), List.of(),
                source.eagerEffectFacts(), source.eagerCycles(), source.declarationValues());
        assertThrows(IllegalArgumentException.class,
                () -> seal(graph, missingEvents, graph.initializationPlan()));

        SemanticFlowEvent mutation = source.events().stream()
                .filter(event -> event.kind() == SemanticFlowEvent.Kind.MUTATION)
                .findFirst().orElseThrow();
        SemanticFlowEvent wrongRoute = new SemanticFlowEvent(
                mutation.kind(), mutation.moduleId(), mutation.span(),
                mutation.initializerDeclaration(), mutation.declarationId(),
                mutation.targetDeclaration(), mutation.lambdaId(), mutation.referenceId(),
                mutation.callId(), Optional.of(ProjectionPath.arrayElement(1)),
                mutation.value(), mutation.writes(), mutation.effects());
        List<SemanticFlowEvent> events = new ArrayList<>(source.events());
        events.remove(mutation);
        events.add(wrongRoute);
        SemanticFlowFacts wrongRouteFacts = new SemanticFlowFacts(
                source.callableSummaries(), source.normalizedExpressions(), events,
                source.eagerEffectFacts(), source.eagerCycles(), source.declarationValues());
        assertThrows(IllegalArgumentException.class,
                () -> seal(graph, wrongRouteFacts, graph.initializationPlan()));

        TypedSemanticGraph foreign = typed("let other :I32 = 1");
        assertThrows(IllegalArgumentException.class,
                () -> seal(graph, foreign.semanticFlowFacts(), graph.initializationPlan()));

        TypedSemanticGraph dependent = typed(pair());
        assertTrue(!dependent.initializationPlan().dependencies().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> seal(dependent, dependent.semanticFlowFacts(),
                        InitializationPlan.empty(dependent.resolvedGraph().moduleGraph())));
    }

    @Test
    public void missingAndForgedSummaryMetadataCannotBeSealed() {
        TypedSemanticGraph graph = typed(
                "let @mut value :I32 = 0 "
                        + "let f :Fn<;I32> = (=> | | value)");
        SemanticFlowFacts source = graph.semanticFlowFacts();
        CallableSummary summary = source.callableSummaries().orderedSummaries().getFirst();

        CallableSummary wrongScope = new CallableSummary(
                summary.lambdaId(), summary.moduleId(), summary.span(), new ScopeId(999),
                summary.signature(), summary.parameters(), summary.captures(),
                summary.returnFormula(), summary.writes(), summary.callReferences(),
                summary.eagerEffects(), summary.normalizedBody(), true,
                summary.fixedPointIterations(), summary.limits());
        CallableSummarySet wrongScopeSet = new CallableSummarySet(
                List.of(wrongScope), source.callableSummaries().lambdaByDeclaration(),
                source.callableSummaries().intrinsicDeclarations(),
                source.callableSummaries().components());
        SemanticFlowFacts wrongScopeFacts = new SemanticFlowFacts(
                wrongScopeSet, source.normalizedExpressions(), source.events(),
                source.eagerEffectFacts(), source.eagerCycles(), source.declarationValues());
        assertThrows(IllegalArgumentException.class,
                () -> seal(graph, wrongScopeFacts, graph.initializationPlan()));

        SemanticFlowFacts missingSummary = new SemanticFlowFacts(
                CallableSummarySet.empty(), source.normalizedExpressions(), source.events(),
                source.eagerEffectFacts(), source.eagerCycles(), source.declarationValues());
        assertThrows(IllegalArgumentException.class,
                () -> seal(graph, missingSummary, graph.initializationPlan()));
    }

    @Test
    public void ownershipWitnessCannotBorrowAnUnrelatedMutationSpan() {
        TypedSemanticGraph graph = typed(
                "let values :Array<I32> = Array[0] "
                        + "let @mut other :Array<I32> = Array[1] "
                        + "let changed = (other[0] := 2)");
        SemanticFlowFacts source = graph.semanticFlowFacts();
        SemanticFlowEvent declaration = source.events().stream()
                .filter(event -> event.kind() == SemanticFlowEvent.Kind.DECLARATION
                        && !event.value().isEmpty()
                        && !event.value().only().aggregateIdentities().isEmpty())
                .findFirst().orElseThrow();
        SemanticFlowEvent mutation = source.events().stream()
                .filter(event -> event.kind() == SemanticFlowEvent.Kind.MUTATION)
                .findFirst().orElseThrow();
        ValueAlternatives forgedValue = withWitnessSpan(
                declaration.value(), mutation.span());
        SemanticFlowEvent forgedDeclaration = withValue(declaration, forgedValue);
        List<SemanticFlowEvent> events = replaceEvent(
                source.events(), declaration, forgedDeclaration);
        TreeMap<DeclarationId, ValueAlternatives> values =
                new TreeMap<>(source.declarationValues());
        values.put(declaration.declarationId().orElseThrow(), forgedValue);
        SemanticFlowFacts forged = new SemanticFlowFacts(
                source.callableSummaries(), source.normalizedExpressions(), events,
                source.eagerEffectFacts(), source.eagerCycles(), values);

        assertThrows(IllegalArgumentException.class,
                () -> seal(graph, forged, graph.initializationPlan()));
    }

    @Test
    public void sameSignatureLambdaCannotReplaceTheInitializerIdentityOrEvent() {
        TypedSemanticGraph graph = typed(
                "let first :Fn<;I32> = (=> | | 1) "
                        + "let second :Fn<;I32> = (=> | | 2)");
        SemanticFlowFacts source = graph.semanticFlowFacts();
        List<SemanticFlowEvent> declarations = source.events().stream()
                .filter(event -> event.kind() == SemanticFlowEvent.Kind.DECLARATION
                        && !event.value().isEmpty()
                        && !event.value().only().callableFlows().isEmpty())
                .toList();
        SemanticFlowEvent first = declarations.getFirst();
        SemanticFlowEvent second = declarations.get(1);
        LambdaId wrongLambda = second.lambdaId().orElseThrow();
        ValueAlternatives forgedValue = withLambda(first.value(), wrongLambda);
        TreeMap<DeclarationId, ValueAlternatives> values =
                new TreeMap<>(source.declarationValues());
        values.put(first.declarationId().orElseThrow(), forgedValue);
        SemanticFlowEvent wrongValueEvent = withValue(first, forgedValue);
        SemanticFlowFacts wrongValue = new SemanticFlowFacts(
                source.callableSummaries(), source.normalizedExpressions(),
                replaceEvent(source.events(), first, wrongValueEvent),
                source.eagerEffectFacts(), source.eagerCycles(), values);
        assertThrows(IllegalArgumentException.class,
                () -> seal(graph, wrongValue, graph.initializationPlan()));

        SemanticFlowEvent forgedEvent = new SemanticFlowEvent(
                first.kind(), first.moduleId(), first.span(), first.initializerDeclaration(),
                first.declarationId(), first.targetDeclaration(), Optional.of(wrongLambda),
                first.referenceId(), first.callId(), first.route(), first.value(),
                first.writes(), first.effects());
        SemanticFlowFacts wrongEvent = new SemanticFlowFacts(
                source.callableSummaries(), source.normalizedExpressions(),
                replaceEvent(source.events(), first, forgedEvent),
                source.eagerEffectFacts(), source.eagerCycles(), source.declarationValues());
        assertThrows(IllegalArgumentException.class,
                () -> seal(graph, wrongEvent, graph.initializationPlan()));
    }

    @Test
    public void scalarAggregateAndFunctionAlternativesCannotEraseRequiredIdentities() {
        TypedSemanticGraph arrayGraph = typed("let values :Array<I32> = Array[0]");
        SemanticFlowEvent arrayEvent = declarationEvent(arrayGraph);
        ValueAlternatives scalarArray = ValueAlternatives.singleton(
                ValueAlternative.scalar(arrayEvent.value().only().type()));
        assertThrows(IllegalArgumentException.class,
                () -> sealWithReplacedDeclarationValue(arrayGraph, arrayEvent, scalarArray));

        TypedSemanticGraph functionGraph = typed(
                "let value :Fn<;I32> = (=> | | 1)");
        SemanticFlowEvent functionEvent = declarationEvent(functionGraph);
        ValueAlternatives scalarFunction = ValueAlternatives.singleton(
                ValueAlternative.scalar(functionEvent.value().only().type()));
        assertThrows(IllegalArgumentException.class,
                () -> sealWithReplacedDeclarationValue(
                        functionGraph, functionEvent, scalarFunction));
    }

    @Test
    public void aggregateRoutesWitnessSitesAndScopesAreCanonical() {
        TypedSemanticGraph tupleGraph = typed(
                "let value :Tuple<Array<I32>,Array<I32>> = "
                        + "Tuple[Array[0] Array[1]]");
        SemanticFlowEvent tupleEvent = declarationEvent(tupleGraph);
        ValueAlternative tuple = tupleEvent.value().only();
        assertEquals(2, tuple.aggregateIdentities().size());
        List<AggregateIdentityFact> swapped = List.of(
                tuple.aggregateIdentities().get(0).withRoute(
                        tuple.aggregateIdentities().get(1).route()),
                tuple.aggregateIdentities().get(1).withRoute(
                        tuple.aggregateIdentities().get(0).route()));
        ValueAlternatives wrongRoutes = ValueAlternatives.singleton(
                new ValueAlternative(tuple.type(), swapped, tuple.callableFlows()));
        assertThrows(IllegalArgumentException.class,
                () -> sealWithReplacedDeclarationValue(
                        tupleGraph, tupleEvent, wrongRoutes));

        TypedSemanticGraph spanGraph = typed(
                "let values :Array<I32> = Array[0] "
                        + "let function :Fn<;I32> = (=> | | 1) "
                        + "let called :I32 = ::function[]");
        SemanticFlowEvent values = spanGraph.semanticFlowFacts().events().stream()
                .filter(event -> event.kind() == SemanticFlowEvent.Kind.DECLARATION
                        && event.value().alternatives().stream().anyMatch(value ->
                        !value.aggregateIdentities().isEmpty()))
                .findFirst().orElseThrow();
        SourceSpan unrelatedFunctionReference = spanGraph.resolvedGraph().references().stream()
                .filter(reference -> reference.targetDeclaration().stream().anyMatch(target ->
                        spanGraph.contract(target).stream().anyMatch(contract ->
                                contract.valueType().withoutQualifiers()
                                        instanceof io.mindspice.lyra.compiler.types.FunctionType)))
                .map(ResolvedReference::span).findFirst().orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> sealWithReplacedDeclarationValue(
                spanGraph, values, withWitnessSpan(values.value(), unrelatedFunctionReference)));

        TypedSemanticGraph imported = typed(aggregatePair());
        SemanticFlowEvent importedValue = imported.semanticFlowFacts().events().stream()
                .filter(event -> event.kind() == SemanticFlowEvent.Kind.DECLARATION
                        && event.moduleId().equals(imported.resolvedGraph().moduleGraph().rootModule())
                        && event.value().alternatives().stream().anyMatch(value ->
                        value.aggregateIdentities().stream().anyMatch(
                                AggregateIdentityFact::isImported)))
                .findFirst().orElseThrow();
        ScopeId foreignExistingScope = imported.resolvedGraph().scopeTree().rootScope(
                imported.resolvedGraph().moduleGraph().rootModule());
        assertThrows(IllegalArgumentException.class, () -> sealWithReplacedDeclarationValue(
                imported, importedValue,
                withWitnessScope(importedValue.value(), foreignExistingScope)));
    }

    @Test
    public void summaryTargetsCallResultsAndCaptureSnapshotsKeepExactIdentities() {
        TypedSemanticGraph targetGraph = typed(
                "let first :Fn<;I32> = (=> | | 1) "
                        + "let second :Fn<;I32> = (=> | | 2) "
                        + "let caller :Fn<;I32> = (=> | | ::first[])");
        SemanticFlowFacts targetFacts = targetGraph.semanticFlowFacts();
        CallableSummary caller = targetFacts.callableSummaries().orderedSummaries().stream()
                .filter(summary -> !summary.callReferences().isEmpty())
                .findFirst().orElseThrow();
        CallableCallReference sourceCall = caller.callReferences().getFirst();
        LambdaId wrongLambda = targetGraph.declarations().stream()
                .filter(declaration -> targetGraph.resolvedGraph().declaration(declaration.id())
                        .filter(value -> value.name().equals("second")).isPresent())
                .flatMap(declaration -> declaration.initializerLambda().stream())
                .findFirst().orElseThrow();
        FormulaAlternatives wrongTarget = FormulaAlternatives.singleton(
                new ValueFormula.Lambda(wrongLambda,
                        ((io.mindspice.lyra.compiler.types.FunctionType)
                                sourceCall.target().rootType().withoutQualifiers())));
        CallableCallReference wrongCall = new CallableCallReference(
                sourceCall.id(), sourceCall.kind(), sourceCall.span(),
                sourceCall.referenceId(), sourceCall.targetDeclaration(),
                sourceCall.targetModule(), sourceCall.targetExport(),
                Optional.of(wrongLambda), sourceCall.targetParameterIndexes(),
                sourceCall.targetCaptureIds(), wrongTarget, sourceCall.arguments());
        CallableSummary wrongTargetSummary = replaceCall(caller, sourceCall, wrongCall);
        assertThrows(IllegalArgumentException.class, () -> seal(
                targetGraph, withSummary(targetFacts, caller, wrongTargetSummary),
                targetGraph.initializationPlan()));

        TypedSemanticGraph resultGraph = typed(
                "let first :Fn<;I32> = (=> | | 1) "
                        + "let second :Fn<;I32> = (=> | | 2) "
                        + "let caller :Fn<;I32> = (=> | | { "
                        + "let ignored :I32 = (first) (second) })");
        SemanticFlowFacts resultFacts = resultGraph.semanticFlowFacts();
        CallableSummary resultSummary = resultFacts.callableSummaries().orderedSummaries().stream()
                .filter(summary -> summary.callReferences().size() == 2)
                .findFirst().orElseThrow();
        ValueFormula.CallResult wrongResult = new ValueFormula.CallResult(
                resultSummary.callReferences().getFirst().id(),
                resultSummary.returnFormula().resultType());
        CallableSummary wrongResultSummary = new CallableSummary(
                resultSummary.lambdaId(), resultSummary.moduleId(), resultSummary.span(),
                resultSummary.scopeId(), resultSummary.signature(), resultSummary.parameters(),
                resultSummary.captures(), new CallableSummary.ReturnFormula(
                resultSummary.returnFormula().resultType(),
                FormulaAlternatives.singleton(wrongResult)),
                resultSummary.writes(), resultSummary.callReferences(),
                resultSummary.eagerEffects(), resultSummary.normalizedBody(), true,
                resultSummary.fixedPointIterations(), resultSummary.limits());
        assertThrows(IllegalArgumentException.class, () -> seal(
                resultGraph, withSummary(resultFacts, resultSummary, wrongResultSummary),
                resultGraph.initializationPlan()));

        TypedSemanticGraph captureGraph = typed(
                "let first :Array<I32> = Array[0] "
                        + "let second :Array<I32> = Array[1] "
                        + "let value :Fn<;Array<I32>> = (=> | | first)");
        SemanticFlowFacts captureFacts = captureGraph.semanticFlowFacts();
        SemanticFlowEvent closure = captureFacts.events().stream()
                .filter(event -> event.kind() == SemanticFlowEvent.Kind.DECLARATION
                        && event.lambdaId().isPresent()
                        && event.value().alternatives().stream().anyMatch(value ->
                        !value.callableFlows().isEmpty()))
                .findFirst().orElseThrow();
        CallableFlow callable = closure.value().only().callableFlows().getFirst();
        DeclarationId captureKey = callable.capturedValues().keySet().iterator().next();
        DeclarationId second = captureGraph.resolvedGraph().declarations().stream()
                .filter(declaration -> declaration.name().equals("second"))
                .map(ResolvedDeclaration::id).findFirst().orElseThrow();
        TreeMap<DeclarationId, ValueAlternatives> wrongSnapshots =
                new TreeMap<>(callable.capturedValues());
        wrongSnapshots.put(captureKey,
                captureFacts.declarationValues().get(second));
        CallableFlow wrongSnapshot = new CallableFlow(
                callable.lambdaId(), callable.intrinsicDeclarationId(), callable.route(),
                wrongSnapshots, callable.sharedCellSnapshots());
        ValueAlternatives wrongClosure = ValueAlternatives.singleton(new ValueAlternative(
                closure.value().only().type(), closure.value().only().aggregateIdentities(),
                List.of(wrongSnapshot)));
        assertThrows(IllegalArgumentException.class, () ->
                sealWithReplacedDeclarationValue(captureGraph, closure, wrongClosure));

        TypedSemanticGraph callResultGraph = typed(
                "let make :Fn<;Fn<;I32>> = (=> | | (=> | | 1)) "
                        + "let unrelated :Fn<;I32> = (=> | | 2) "
                        + "let result :Fn<;I32> = (make)");
        SemanticFlowFacts callResultFacts = callResultGraph.semanticFlowFacts();
        SemanticFlowEvent callResult = callResultFacts.events().stream()
                .filter(event -> event.kind() == SemanticFlowEvent.Kind.CALL
                        && event.value().alternatives().stream().anyMatch(value ->
                        !value.callableFlows().isEmpty()))
                .findFirst().orElseThrow();
        LambdaId unrelated = callResultGraph.resolvedGraph().declarations().stream()
                .filter(declaration -> declaration.name().equals("unrelated"))
                .flatMap(declaration -> declaration.initializerLambda().stream())
                .findFirst().orElseThrow();
        SemanticFlowEvent wrongCallResult = withValue(
                callResult, withLambda(callResult.value(), unrelated));
        SemanticFlowFacts forgedCallResult = new SemanticFlowFacts(
                callResultFacts.callableSummaries(),
                callResultFacts.normalizedExpressions(),
                replaceEvent(callResultFacts.events(), callResult, wrongCallResult),
                callResultFacts.eagerEffectFacts(), callResultFacts.eagerCycles(),
                callResultFacts.declarationValues());
        assertThrows(IllegalArgumentException.class, () -> seal(
                callResultGraph, forgedCallResult,
                callResultGraph.initializationPlan()));
    }

    @Test
    public void nestedSummaryCaptureSnapshotCannotUseSameTypedSiblingValue() {
        TypedSemanticGraph graph = typed(
                "let first :Array<I32> = Array[0] "
                        + "let second :Array<I32> = Array[1] "
                        + "let make :Fn<;Fn<;Array<I32>>> = "
                        + "(=> | | (=> | | first))");
        SemanticFlowFacts source = graph.semanticFlowFacts();
        ResolvedDeclaration make = graph.resolvedGraph().declarations().stream()
                .filter(declaration -> declaration.name().equals("make"))
                .findFirst().orElseThrow();
        CallableSummary summary = source.callableSummaries()
                .summary(make.initializerLambda().orElseThrow()).orElseThrow();
        ValueFormula.Lambda returned = summary.returnFormula().formulas().stream()
                .filter(ValueFormula.Lambda.class::isInstance)
                .map(ValueFormula.Lambda.class::cast)
                .filter(lambda -> !lambda.captures().isEmpty())
                .findFirst().orElseThrow();
        CaptureId capture = returned.captures().keySet().iterator().next();
        ResolvedDeclaration second = graph.resolvedGraph().declarations().stream()
                .filter(declaration -> declaration.name().equals("second"))
                .findFirst().orElseThrow();
        LyraType captureType = returned.captures().get(capture).rootType();
        TreeMap<CaptureId, FormulaAlternatives> captures =
                new TreeMap<>(returned.captures());
        captures.put(capture, FormulaAlternatives.singleton(
                new ValueFormula.Declaration(
                        second.id(), Optional.of(second.moduleId()),
                        ProjectionPath.root(), ProjectionPath.root(), captureType)));
        ValueFormula.Lambda forgedReturn = new ValueFormula.Lambda(
                returned.lambdaId(), returned.functionType(),
                returned.resultRoute(), captures);
        CallableSummary forged = new CallableSummary(
                summary.lambdaId(), summary.moduleId(), summary.span(), summary.scopeId(),
                summary.signature(), summary.parameters(), summary.captures(),
                new CallableSummary.ReturnFormula(
                        summary.returnFormula().resultType(),
                        FormulaAlternatives.singleton(forgedReturn)),
                summary.writes(), summary.callReferences(), summary.eagerEffects(),
                summary.normalizedBody(), true, summary.fixedPointIterations(),
                summary.limits());

        assertThrows(IllegalArgumentException.class, () -> seal(
                graph, withSummary(source, summary, forged), graph.initializationPlan()));
    }

    @Test
    public void fabricatedValueReadCannotCreateAPlannerAcceptedDependency() {
        TypedSemanticGraph graph = typed(pairWithoutRead());
        SemanticFlowFacts source = graph.semanticFlowFacts();
        SemanticFlowEvent declaration = declarationEvent(graph);
        ModuleId main = graph.resolvedGraph().moduleGraph().rootModule();
        ResolvedDeclaration target = graph.resolvedGraph().declarations().stream()
                .filter(value -> !value.moduleId().equals(main)
                        && value.kind() == DeclarationKind.LET)
                .findFirst().orElseThrow();
        SourceSpan fabricatedSite = declaration.span();
        EagerEffectWitness witness = new EagerEffectWitness(
                main, target.moduleId(), EagerEffectWitness.Kind.VALUE_READ,
                fabricatedSite, Optional.of(target.id()), Optional.empty(), Optional.empty(),
                List.of(fabricatedSite), List.of(), false);
        SemanticFlowEvent forgedDeclaration = new SemanticFlowEvent(
                declaration.kind(), declaration.moduleId(), declaration.span(),
                declaration.initializerDeclaration(), declaration.declarationId(),
                declaration.targetDeclaration(), declaration.lambdaId(),
                declaration.referenceId(), declaration.callId(), declaration.route(),
                declaration.value(), declaration.writes(), List.of(witness));
        SemanticFlowEvent forgedEffect = new SemanticFlowEvent(
                SemanticFlowEvent.Kind.EFFECT, main, fabricatedSite,
                declaration.initializerDeclaration(), Optional.empty(), Optional.of(target.id()),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                ValueAlternatives.empty(), List.of(), List.of(witness));
        List<SemanticFlowEvent> events = replaceEvent(
                source.events(), declaration, forgedDeclaration);
        events = new ArrayList<>(events);
        events.add(forgedEffect);
        SemanticFlowFacts forged = new SemanticFlowFacts(
                source.callableSummaries(), source.normalizedExpressions(), events,
                List.of(new EagerEffectFact(main,
                        declaration.initializerDeclaration(), witness)),
                source.eagerCycles(), source.declarationValues());
        InitializationPlan matchingForgedPlan = InitializationAnalyzer.plan(graph, forged).plan();
        assertFalse(matchingForgedPlan.dependencies().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> seal(graph, forged, matchingForgedPlan));
    }

    @Test
    public void nilProvenanceIsAlternativeAndRouteSpecific() {
        TypedSemanticGraph graph = typed(
                "let value :Tuple<@nil Array<I32>,Array<I32>> = "
                        + "Tuple[#NIL Array[0]]");
        SemanticFlowEvent declaration = declarationEvent(graph);
        ValueAlternative value = declaration.value().only();
        ProjectionPath nilRoute = ProjectionPath.tupleMember(0);
        ProjectionPath arrayRoute = ProjectionPath.tupleMember(1);

        assertTrue(value.nilProvenance().stream().anyMatch(nil ->
                nil.route().equals(nilRoute)));
        assertTrue(value.aggregateIdentities().stream().anyMatch(identity ->
                identity.route().equals(arrayRoute)));
        assertTrue(value.nilProvenance().stream().allMatch(nil ->
                graph.sealingCore().ownsFlowSite(nil.sourceSite(), nil.sourceSpan())));

        ValueAlternatives forged = ValueAlternatives.singleton(
                ValueAlternative.of(value.type(), List.of(), value.callableFlows(),
                        value.nilProvenance()));
        assertThrows(IllegalArgumentException.class, () ->
                sealWithReplacedDeclarationValue(graph, declaration, forged));
    }

    @Test
    public void producerRecordRejectsMutuallyConsistentMissingAndDuplicateEffects() {
        TypedSemanticGraph graph = typed(pair());
        SemanticFlowFacts source = graph.semanticFlowFacts();
        assertFalse(source.eagerEffectFacts().isEmpty());
        source.eagerEffectFacts().forEach(effect -> {
            assertTrue(effect.witness().effectSite().isPresent());
            assertEquals(effect.witness().sourcePath().size(),
                    effect.witness().sourceSitePath().size());
        });

        List<SemanticFlowEvent> strippedEvents = source.events().stream()
                .filter(event -> event.kind() != SemanticFlowEvent.Kind.EFFECT)
                .map(event -> withEffects(event, List.of()))
                .toList();
        SemanticFlowFacts stripped = new SemanticFlowFacts(
                source.callableSummaries(), source.normalizedExpressions(), strippedEvents,
                List.of(), source.eagerCycles(), source.declarationValues());
        InitializationPlan matchingEmptyPlan = InitializationAnalyzer.plan(graph, stripped).plan();
        assertTrue(matchingEmptyPlan.dependencies().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> seal(graph, stripped, matchingEmptyPlan));

        EagerEffectFact effect = source.eagerEffectFacts().getFirst();
        assertThrows(IllegalArgumentException.class, () -> new SemanticFlowFacts(
                source.callableSummaries(), source.normalizedExpressions(), source.events(),
                List.of(effect, effect), source.eagerCycles(), source.declarationValues()));
    }

    @Test
    public void callableEffectTargetsAndWitnessPathsCannotBeMutuallyForged() {
        TypedSemanticGraph graph = typed(callableEffectPair());
        SemanticFlowFacts source = graph.semanticFlowFacts();
        EagerEffectWitness witness = source.eagerEffectFacts().stream()
                .map(EagerEffectFact::witness)
                .filter(effect -> effect.targetLambda().isPresent())
                .findFirst().orElseThrow();
        LambdaId sibling = graph.resolvedGraph().declarations().stream()
                .filter(declaration -> declaration.name().equals("second"))
                .flatMap(declaration -> declaration.initializerLambda().stream())
                .findFirst().orElseThrow();
        EagerEffectWitness wrongTarget = new EagerEffectWitness(
                witness.fromModule(), witness.targetModule(), witness.kind(),
                witness.effectSpan(), witness.targetDeclaration(), witness.referenceId(),
                Optional.of(sibling), witness.sourcePath(), witness.callPath(),
                witness.recursive(), witness.effectSite(), witness.sourceSitePath());
        SemanticFlowFacts targetForged = replaceWitness(source, witness, wrongTarget);
        InitializationPlan targetPlan = InitializationAnalyzer.plan(graph, targetForged).plan();
        assertThrows(IllegalArgumentException.class,
                () -> seal(graph, targetForged, targetPlan));

        ArrayList<SourceSpan> spans = new ArrayList<>();
        spans.add(witness.sourcePath().getFirst());
        spans.addAll(witness.sourcePath());
        ArrayList<io.mindspice.lyra.compiler.identity.FlowSiteId> sites = new ArrayList<>();
        sites.add(witness.sourceSitePath().getFirst());
        sites.addAll(witness.sourceSitePath());
        EagerEffectWitness extraPath = new EagerEffectWitness(
                witness.fromModule(), witness.targetModule(), witness.kind(),
                witness.effectSpan(), witness.targetDeclaration(), witness.referenceId(),
                witness.targetLambda(), spans, witness.callPath(), witness.recursive(),
                witness.effectSite(), sites);
        SemanticFlowFacts pathForged = replaceWitness(source, witness, extraPath);
        InitializationPlan pathPlan = InitializationAnalyzer.plan(graph, pathForged).plan();
        assertThrows(IllegalArgumentException.class,
                () -> seal(graph, pathForged, pathPlan));
    }

    @Test
    public void ownershipKeepsCanonicalAllocationOriginAcrossAliases() {
        TypedSemanticGraph graph = typed(
                "let source :Array<I32> = Array[0] "
                        + "let alias :Array<I32> = source");
        SourceSpan allocation = graph.expressions().stream()
                .filter(expression -> expression.kind() == TypedExpressionKind.ARRAY_LITERAL)
                .map(TypedExpression::span).findFirst().orElseThrow();
        ResolvedDeclaration alias = graph.resolvedGraph().declarations().stream()
                .filter(declaration -> declaration.name().equals("alias"))
                .findFirst().orElseThrow();
        SemanticFlowEvent aliasEvent = graph.semanticFlowFacts().events().stream()
                .filter(event -> event.kind() == SemanticFlowEvent.Kind.DECLARATION
                        && event.declarationId().filter(alias.id()::equals).isPresent())
                .findFirst().orElseThrow();
        AggregateIdentityFact identity = aliasEvent.value().only()
                .aggregateIdentities().getFirst();
        assertEquals(allocation, identity.witness().sourceSpan());
        assertTrue(identity.witness().originSite().isPresent());
        assertTrue(graph.sealingCore().ownsFlowSite(
                identity.witness().originSite().orElseThrow(), allocation));

        SourceSpan aliasReference = graph.resolvedGraph().references().stream()
                .filter(reference -> reference.span().sourceId().equals(allocation.sourceId())
                        && reference.targetDeclaration().isPresent())
                .map(ResolvedReference::span).findFirst().orElseThrow();
        ValueAlternatives forged = withWitnessSpan(aliasEvent.value(), aliasReference);
        assertThrows(IllegalArgumentException.class, () ->
                sealWithReplacedDeclarationValue(graph, aliasEvent, forged));
    }

    @Test
    public void lambdaParametersCannotBeReownedBySameTypedSiblingLambdas() {
        TypedSemanticGraph graph = typed(
                "let first :Fn<I32;I32> = (=> |value| value) "
                        + "let second :Fn<I32;I32> = (=> |value| value)");
        ResolvedSemanticGraph resolved = graph.resolvedGraph();
        ResolvedLambda first = resolved.lambdas().getFirst();
        ResolvedLambda second = resolved.lambdas().get(1);
        ResolvedLambda wrong = new ResolvedLambda(
                first.id(), first.moduleId(), first.span(), first.scopeId(),
                first.bodySpan(), first.signature(), second.parameterIds(),
                first.ownerDeclaration(), first.captures(), first.signatureComplete());
        List<ResolvedLambda> lambdas = new ArrayList<>(resolved.lambdas());
        lambdas.set(0, wrong);

        assertThrows(IllegalArgumentException.class, () -> new ResolvedSemanticGraph(
                resolved.moduleGraph(), resolved.scopeTree(), resolved.modules(),
                resolved.declarations(), resolved.references(), lambdas,
                resolved.imports(), resolved.exports(), resolved.captures(),
                resolved.mutations(), resolved.syntaxLinks(), resolved.functionLinkage()));
    }

    @Test
    public void directCaptureCannotSurviveReciprocalSourceReferenceRemoval() {
        TypedSemanticGraph graph = typed(
                "let value :I32 = 1 let closure :Fn<;I32> = (=> | | value)");
        ResolvedSemanticGraph resolved = graph.resolvedGraph();
        ResolvedReference reference = resolved.references().stream()
                .filter(value -> value.capture().isPresent())
                .findFirst().orElseThrow();
        ResolvedCapture capture = resolved.capture(reference.capture().orElseThrow())
                .orElseThrow();
        ResolvedReference uncaptured = withCapture(reference, Optional.empty());
        ResolvedCapture unreferenced = withCaptureReferences(capture, List.of());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> rebuildResolved(
                        resolved,
                        replaceValue(resolved.references(), reference, uncaptured),
                        replaceValue(resolved.captures(), capture, unreferenced)));
        assertTrue(failure.getMessage().contains("exact direct capture linkage"));
    }

    @Test
    public void captureReferenceIndexesRejectWrongMissingExtraAndDuplicateEntries() {
        TypedSemanticGraph graph = typed(
                "let value :I32 = 1 "
                        + "let closure :Fn<I32;I32> = "
                        + "(=> |local :I32| (+ value value local))");
        ResolvedSemanticGraph resolved = graph.resolvedGraph();
        ResolvedCapture capture = resolved.captures().getFirst();
        assertEquals(2, capture.references().size());
        ResolvedReference first = resolved.reference(capture.references().getFirst()).orElseThrow();
        ResolvedReference second = resolved.reference(capture.references().getLast()).orElseThrow();
        assertEquals(first.targetDeclaration(), second.targetDeclaration());
        assertEquals(first.fromLambda(), second.fromLambda());

        ResolvedCapture reordered = withCaptureReferences(
                capture, List.of(second.id(), first.id()));
        IllegalArgumentException reorderedFailure = assertThrows(
                IllegalArgumentException.class, () -> rebuildResolved(
                        resolved, resolved.references(),
                        replaceValue(resolved.captures(), capture, reordered)));
        assertTrue(reorderedFailure.getMessage().contains("source-authoritative"));

        ResolvedCapture wrongSpan = new ResolvedCapture(
                capture.id(), capture.lambdaId(), capture.declarationId(), capture.moduleId(),
                second.span(), capture.declarationSpan(), capture.mode(),
                capture.sharedCellId(), capture.references());
        IllegalArgumentException spanFailure = assertThrows(
                IllegalArgumentException.class, () -> rebuildResolved(
                        resolved, resolved.references(),
                        replaceValue(resolved.captures(), capture, wrongSpan)));
        assertTrue(spanFailure.getMessage().contains("source-authoritative"));

        ResolvedCapture missing = withCaptureReferences(
                capture, List.of(capture.references().getLast()));
        assertThrows(IllegalArgumentException.class, () -> rebuildResolved(
                resolved, resolved.references(),
                replaceValue(resolved.captures(), capture, missing)));

        ResolvedReference local = resolved.references().stream()
                .filter(reference -> reference.fromLambda().equals(first.fromLambda())
                        && reference.capture().isEmpty())
                .findFirst().orElseThrow();
        ResolvedCapture extra = withCaptureReferences(
                capture, List.of(first.id(), second.id(), local.id()));
        assertThrows(IllegalArgumentException.class, () -> rebuildResolved(
                resolved, resolved.references(),
                replaceValue(resolved.captures(), capture, extra)));

        ResolvedCapture duplicate = withCaptureReferences(
                capture, List.of(first.id(), second.id(), second.id()));
        assertThrows(IllegalArgumentException.class, () -> rebuildResolved(
                resolved, resolved.references(),
                replaceValue(resolved.captures(), capture, duplicate)));
    }

    @Test
    public void canonicalCaptureTopologyCoversMultipleNestedMutableAndImportedReferences() {
        TypedSemanticGraph graph = typed(
                "let immutable :I32 = 1 "
                        + "let @mut shared :I32 = 0 "
                        + "let reader :Fn<;I32> = (=> | | (+ immutable immutable)) "
                        + "let writer :Fn<;I32> = (=> | | { "
                        + "shared := (+ shared 1) shared }) "
                        + "let observer :Fn<;I32> = (=> | | shared) "
                        + "let outer :Fn<;Fn<;I32>> = (=> | | (=> | | immutable))");
        ResolvedSemanticGraph resolved = graph.resolvedGraph();
        ResolvedDeclaration immutable = resolved.declarations().stream()
                .filter(declaration -> declaration.name().equals("immutable"))
                .findFirst().orElseThrow();
        ResolvedDeclaration shared = resolved.declarations().stream()
                .filter(declaration -> declaration.name().equals("shared"))
                .findFirst().orElseThrow();

        ResolvedCapture repeated = resolved.captures().stream()
                .filter(capture -> capture.declarationId().equals(immutable.id())
                        && capture.references().size() == 2)
                .findFirst().orElseThrow();
        assertEquals(repeated.references().stream().sorted().toList(), repeated.references());
        assertEquals(CaptureMode.IMMUTABLE_VALUE, repeated.mode());
        assertTrue(repeated.sharedCellId().isEmpty());

        List<ResolvedCapture> sharedCaptures = resolved.captures().stream()
                .filter(capture -> capture.declarationId().equals(shared.id()))
                .toList();
        assertEquals(2, sharedCaptures.size());
        assertTrue(sharedCaptures.stream().allMatch(capture ->
                capture.mode() == CaptureMode.SHARED_MUTABLE_CELL
                        && capture.sharedCellId().equals(Optional.of(shared.id()))
                        && !capture.references().isEmpty()));

        ResolvedCapture transitive = resolved.captures().stream()
                .filter(capture -> capture.declarationId().equals(immutable.id())
                        && capture.references().isEmpty())
                .findFirst().orElseThrow();
        assertTrue(resolved.references().stream().anyMatch(reference ->
                reference.targetDeclaration().equals(Optional.of(immutable.id()))
                        && reference.span().equals(transitive.span())
                        && reference.capture().isPresent()));

        ResolvedReferenceTopology authority = resolved.referenceTopology().orElseThrow();
        assertEquals(resolved.captures(), authority.captures());
        assertEquals(resolved.captures().stream().collect(java.util.stream.Collectors.toMap(
                        ResolvedCapture::id, ResolvedCapture::references)),
                authority.referencesByCapture());

        TypedSemanticGraph imported = typed(captureImportPair());
        ResolvedCapture importedCapture = imported.resolvedGraph().captures().stream()
                .filter(capture -> imported.resolvedGraph().declaration(capture.declarationId())
                        .orElseThrow().kind() == DeclarationKind.IMPORT_VALUE)
                .findFirst().orElseThrow();
        assertEquals(1, importedCapture.references().size());
        assertTrue(imported.initializationPlan().dependencies().stream().anyMatch(dependency ->
                dependency.toModule().equals(
                        ModuleId.path("gate11e_capture_library.lyra"))));
    }

    @Test
    public void sourceHeadersImportsAndReExportOriginsAreExactAndReciprocal() {
        TypedSemanticGraph graph = typed(topologyGraph());
        ResolvedSemanticGraph resolved = graph.resolvedGraph();
        ModuleId mainId = resolved.moduleGraph().rootModule();
        ModuleId otherId = ModuleId.path("gate11e_topology_other.lyra");
        ResolvedModule main = resolved.module(mainId).orElseThrow();
        List<ModuleGraph.Edge> missingEdges = new ArrayList<>(
                resolved.moduleGraph().edges());
        missingEdges.removeFirst();
        ModuleGraph missingHeaderEdge = new ModuleGraph(
                mainId, resolved.moduleGraph().modules(), missingEdges,
                resolved.moduleGraph().logicalModules());
        IllegalArgumentException edgeFailure = assertThrows(
                IllegalArgumentException.class, () -> rebuildResolved(
                        resolved, missingHeaderEdge));
        assertTrue(edgeFailure.getMessage().contains("every source import header"));

        ModuleGraph.Edge canonicalEdge = resolved.moduleGraph().edges().getFirst();
        SourceSpan canonicalSpan = resolved.moduleGraph().module(canonicalEdge.from())
                .orElseThrow().program().imports().stream()
                .filter(declaration -> declaration.path().span().equals(
                        canonicalEdge.importSpan()))
                .findFirst().orElseThrow().path().span();
        assertEquals(canonicalSpan, canonicalEdge.importSpan());

        SourceSpan fullHeaderSpan = resolved.moduleGraph().module(canonicalEdge.from())
                .orElseThrow().program().imports().getFirst().span();
        assertFalse(fullHeaderSpan.equals(canonicalEdge.importSpan()));
        ModuleGraph.Edge mutatedSpan = new ModuleGraph.Edge(
                canonicalEdge.from(), canonicalEdge.logicalTarget(), canonicalEdge.target(),
                fullHeaderSpan);
        ModuleGraph mutatedSpanGraph = new ModuleGraph(
                mainId, resolved.moduleGraph().modules(),
                replaceValue(resolved.moduleGraph().edges(), canonicalEdge, mutatedSpan),
                resolved.moduleGraph().logicalModules());
        assertFalse(canonicalEdge.equals(mutatedSpan));
        assertFalse(resolved.moduleGraph().equals(mutatedSpanGraph));
        assertEquals(2, new java.util.HashSet<>(
                List.of(canonicalEdge, mutatedSpan)).size());
        assertThrows(IllegalArgumentException.class, () -> rebuildResolved(
                resolved, mutatedSpanGraph));

        ModuleGraph.Edge retargetedEdge = new ModuleGraph.Edge(
                canonicalEdge.from(), canonicalEdge.logicalTarget(), otherId,
                canonicalEdge.importSpan());
        ModuleGraph retargetedEdgeGraph = new ModuleGraph(
                mainId, resolved.moduleGraph().modules(),
                replaceValue(resolved.moduleGraph().edges(), canonicalEdge, retargetedEdge),
                resolved.moduleGraph().logicalModules());
        assertThrows(IllegalArgumentException.class, () -> rebuildResolved(
                resolved, retargetedEdgeGraph));

        List<ModuleGraph.Edge> sameTargetEdges = resolved.moduleGraph().edges().stream()
                .filter(edge -> edge.logicalTarget().equals(canonicalEdge.logicalTarget())
                        && edge.target().equals(canonicalEdge.target()))
                .toList();
        assertTrue(sameTargetEdges.size() >= 2);
        ModuleGraph.Edge retargetedSpan = new ModuleGraph.Edge(
                canonicalEdge.from(), canonicalEdge.logicalTarget(), canonicalEdge.target(),
                sameTargetEdges.get(1).importSpan());
        ModuleGraph retargetedSpanGraph = new ModuleGraph(
                mainId, resolved.moduleGraph().modules(),
                replaceValue(resolved.moduleGraph().edges(), canonicalEdge, retargetedSpan),
                resolved.moduleGraph().logicalModules());
        assertThrows(IllegalArgumentException.class, () -> rebuildResolved(
                resolved, retargetedSpanGraph));

        List<ModuleGraph.Edge> duplicateEdges = new ArrayList<>(
                resolved.moduleGraph().edges());
        duplicateEdges.add(canonicalEdge);
        ModuleGraph extraEdgeGraph = new ModuleGraph(
                mainId, resolved.moduleGraph().modules(), duplicateEdges,
                resolved.moduleGraph().logicalModules());
        assertThrows(IllegalArgumentException.class, () -> rebuildResolved(
                resolved, extraEdgeGraph));

        assertThrows(NullPointerException.class, () -> new ModuleGraph.Edge(
                canonicalEdge.from(), canonicalEdge.logicalTarget(), canonicalEdge.target(), null));
        assertThrows(IllegalArgumentException.class, () -> new ModuleGraph.Edge(
                canonicalEdge.from(), canonicalEdge.logicalTarget(), canonicalEdge.target(),
                SourceSpan.at(otherId.sourceId(), 0)));

        ResolvedImportBinding selected = main.imports().stream()
                .filter(binding -> binding.localName().equals("value"))
                .findFirst().orElseThrow();

        ResolvedImportBinding retargeted = new ResolvedImportBinding(
                selected.declarationId(), selected.localName(), selected.localNameSpan(),
                selected.importSpan(), selected.logicalModule(), otherId, selected.kind(),
                selected.importedName(), selected.aliasName(), selected.reExport(),
                selected.targetDeclaration(), selected.targetExport());
        ResolvedModule retargetedMain = withImports(
                main, replaceValue(main.imports(), selected, retargeted));
        IllegalArgumentException retargetFailure = assertThrows(
                IllegalArgumentException.class, () -> rebuildResolved(
                        resolved, replaceValue(resolved.modules(), main, retargetedMain),
                        resolved.declarations(), resolved.references(), resolved.lambdas(),
                        replaceValue(resolved.imports(), selected, retargeted),
                        resolved.exports(), resolved.mutations()));
        assertTrue(retargetFailure.getMessage().contains("source header binding"));

        ResolvedModule missingMain = withImports(
                main, main.imports().stream().filter(value -> !value.equals(selected)).toList());
        IllegalArgumentException missingFailure = assertThrows(
                IllegalArgumentException.class, () -> rebuildResolved(
                        resolved, replaceValue(resolved.modules(), main, missingMain),
                        resolved.declarations(), resolved.references(), resolved.lambdas(),
                        resolved.imports().stream().filter(value -> !value.equals(selected)).toList(),
                        resolved.exports(), resolved.mutations()));
        assertTrue(missingFailure.getMessage().contains("source header binding"));

        ResolvedExport exposed = main.exports().stream()
                .filter(export -> export.name().equals("exposed"))
                .findFirst().orElseThrow();
        ResolvedExport other = resolved.export(otherId, "value").orElseThrow();
        ResolvedExport forgedOrigin = new ResolvedExport(
                exposed.name(), exposed.moduleId(), exposed.declarationId(), exposed.span(),
                exposed.contract(), exposed.functionSignature(), exposed.exportId(), true,
                other.originModule(), other.originName(), other.originDeclaration(),
                other.originExport());
        ResolvedModule forgedMain = withExports(
                main, replaceValue(main.exports(), exposed, forgedOrigin));
        IllegalArgumentException originFailure = assertThrows(
                IllegalArgumentException.class, () -> rebuildResolved(
                        resolved, replaceValue(resolved.modules(), main, forgedMain),
                        resolved.declarations(), resolved.references(), resolved.lambdas(),
                        resolved.imports(), replaceValue(
                        resolved.exports(), exposed, forgedOrigin), resolved.mutations()));
        assertTrue(originFailure.getMessage().contains("target export origin"));

        assertTrue(exposed.reExport());
        assertEquals(ModuleId.path("gate11e_topology_origin.lyra"), exposed.originModule());
    }

    @Test
    public void declarationAndInitializerLambdaOwnershipIsBidirectional() {
        TypedSemanticGraph graph = typed(
                "let owner :Fn<;I32> = (=> | | 1) let extra :I32 = 0");
        ResolvedSemanticGraph resolved = graph.resolvedGraph();
        ResolvedDeclaration owner = resolved.declarations().stream()
                .filter(declaration -> declaration.name().equals("owner"))
                .findFirst().orElseThrow();
        ResolvedDeclaration extra = resolved.declarations().stream()
                .filter(declaration -> declaration.name().equals("extra"))
                .findFirst().orElseThrow();
        ResolvedLambda lambda = resolved.lambda(owner.initializerLambda().orElseThrow())
                .orElseThrow();

        ResolvedLambda ownerless = withOwnerDeclaration(lambda, Optional.empty());
        IllegalArgumentException ownerlessFailure = assertThrows(
                IllegalArgumentException.class, () -> rebuildResolved(
                        resolved, resolved.modules(), resolved.declarations(),
                        resolved.references(), replaceValue(
                        resolved.lambdas(), lambda, ownerless), resolved.imports(),
                        resolved.exports(), resolved.mutations()));
        assertTrue(ownerlessFailure.getMessage().contains("initializer-lambda ownership"));

        ResolvedDeclaration duplicateOwner = withInitializerLambda(
                extra, Optional.of(lambda.id()));
        IllegalArgumentException duplicateFailure = assertThrows(
                IllegalArgumentException.class, () -> rebuildResolved(
                        resolved, resolved.modules(),
                        replaceValue(resolved.declarations(), extra, duplicateOwner),
                        resolved.references(), resolved.lambdas(), resolved.imports(),
                        resolved.exports(), resolved.mutations()));
        assertTrue(duplicateFailure.getMessage().contains("initializer-lambda ownership"));

        ResolvedDeclaration absentLambda = withInitializerLambda(
                extra, Optional.of(new LambdaId(Long.MAX_VALUE - 20)));
        IllegalArgumentException absentFailure = assertThrows(
                IllegalArgumentException.class, () -> rebuildResolved(
                        resolved, resolved.modules(),
                        replaceValue(resolved.declarations(), extra, absentLambda),
                        resolved.references(), resolved.lambdas(), resolved.imports(),
                        resolved.exports(), resolved.mutations()));
        assertTrue(absentFailure.getMessage().contains("initializer-lambda ownership"));

        TypedSemanticGraph multiModule = typed(topologyGraph());
        ResolvedSemanticGraph multiResolved = multiModule.resolvedGraph();
        ResolvedLambda foreignLambda = multiResolved.lambdas().stream()
                .findFirst().orElseThrow();
        ResolvedDeclaration foreignDeclaration = multiResolved.declarations().stream()
                .filter(declaration -> declaration.moduleId().equals(
                        ModuleId.path("gate11e_topology_origin.lyra"))
                        && declaration.name().equals("value"))
                .findFirst().orElseThrow();
        ResolvedDeclaration foreignOwner = withInitializerLambda(
                foreignDeclaration, Optional.of(foreignLambda.id()));
        IllegalArgumentException foreignFailure = assertThrows(
                IllegalArgumentException.class, () -> rebuildResolved(
                        multiResolved, multiResolved.modules(), replaceValue(
                        multiResolved.declarations(), foreignDeclaration, foreignOwner),
                        multiResolved.references(), multiResolved.lambdas(),
                        multiResolved.imports(), multiResolved.exports(),
                        multiResolved.mutations()));
        assertTrue(foreignFailure.getMessage().contains("initializer-lambda ownership"));

        TypedSemanticGraph anonymous = typed(
                "let make :Fn<;Fn<;I32>> = (=> | | (=> | | 1))");
        assertEquals(1, anonymous.resolvedGraph().lambdas().stream()
                .filter(value -> value.ownerDeclaration().isEmpty()).count());
    }

    @Test
    public void mutationUsesTheExactSourceAssignmentsCanonicalRootReference() {
        TypedSemanticGraph graph = typed(
                "let @mut value :I32 = 0 let observed :I32 = value "
                        + "let changed = (value := 1)");
        ResolvedSemanticGraph resolved = graph.resolvedGraph();
        ResolvedMutation mutation = resolved.mutations().getFirst();
        ResolvedReference canonical = resolved.reference(
                mutation.rootReference().orElseThrow()).orElseThrow();
        ResolvedReference unrelated = resolved.references().stream()
                .filter(reference -> !reference.id().equals(canonical.id())
                        && reference.targetDeclaration().equals(
                        canonical.targetDeclaration()))
                .findFirst().orElseThrow();
        ResolvedMutation substituted = new ResolvedMutation(
                mutation.moduleId(), mutation.span(), mutation.kind(),
                mutation.rootDeclaration(), Optional.of(unrelated.id()));

        IllegalArgumentException substitutedFailure = assertThrows(
                IllegalArgumentException.class, () -> rebuildResolved(
                        resolved, resolved.modules(), resolved.declarations(),
                        resolved.references(), resolved.lambdas(), resolved.imports(),
                        resolved.exports(), List.of(substituted)));
        assertTrue(substitutedFailure.getMessage().contains(
                "mutation root/reference/source assignment linkage"));
        IllegalArgumentException missingFailure = assertThrows(
                IllegalArgumentException.class, () -> rebuildResolved(
                        resolved, resolved.modules(), resolved.declarations(),
                        resolved.references(), resolved.lambdas(), resolved.imports(),
                        resolved.exports(), List.of()));
        assertTrue(missingFailure.getMessage().contains("exactly every source assignment"));
        IllegalArgumentException duplicateFailure = assertThrows(
                IllegalArgumentException.class, () -> rebuildResolved(
                        resolved, resolved.modules(), resolved.declarations(),
                        resolved.references(), resolved.lambdas(), resolved.imports(),
                        resolved.exports(), List.of(mutation, mutation)));
        assertTrue(duplicateFailure.getMessage().contains("duplicate source sites"));

        assertEquals(mutation.rootDeclaration(), canonical.targetDeclaration().orElseThrow());
        assertTrue(canonical.span().startOffset() >= mutation.span().startOffset()
                && canonical.span().endOffset() <= mutation.span().endOffset());
    }

    @Test
    public void sameNameReferenceCannotBeRetargetedPastItsSourceOrderWithUpdatedSyntaxLink() {
        TypedSemanticGraph graph = typed(
                "let value :I32 = 1 let before :I32 = value "
                        + "let value :I32 = 2 let after :I32 = value");
        ResolvedSemanticGraph resolved = graph.resolvedGraph();
        List<ResolvedDeclaration> declarations = resolved.declarations().stream()
                .filter(declaration -> declaration.name().equals("value"))
                .toList();
        List<ResolvedReference> references = resolved.references().stream()
                .filter(reference -> reference.name().equals("value"))
                .toList();
        assertEquals(2, declarations.size());
        assertEquals(2, references.size());
        assertEquals(declarations.getFirst().id(),
                references.getFirst().targetDeclaration().orElseThrow());
        assertEquals(declarations.getLast().id(),
                references.getLast().targetDeclaration().orElseThrow());

        ResolvedReference source = references.getFirst();
        ResolvedDeclaration sibling = declarations.getLast();
        ResolvedReference retargeted = new ResolvedReference(
                source.id(), source.name(), source.span(), source.moduleId(),
                source.scopeId(), source.kind(), Optional.of(sibling.id()),
                source.targetModule(), source.targetExport(), source.fromLambda(),
                source.capture());
        SyntaxLink sourceLink = resolved.syntaxLinks().stream()
                .filter(link -> link.kind() == SyntaxLinkKind.REFERENCE
                        && link.referenceId().filter(source.id()::equals).isPresent())
                .findFirst().orElseThrow();
        SyntaxLink retargetedLink = SyntaxLink.reference(
                sourceLink.span(), source.id(), Optional.of(sibling.id()),
                source.targetModule(), source.targetExport());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> {
                    ResolvedSemanticGraph forged = new ResolvedSemanticGraph(
                            resolved.moduleGraph(), resolved.scopeTree(), resolved.modules(),
                            resolved.declarations(), replaceValue(
                            resolved.references(), source, retargeted), resolved.lambdas(),
                            resolved.imports(), resolved.exports(), resolved.captures(),
                            resolved.mutations(), replaceValue(
                            resolved.syntaxLinks(), sourceLink, retargetedLink),
                            resolved.functionLinkage());
                    TypeChecker.check(forged);
                });
        assertTrue(failure.getMessage().contains(
                "source-authoritative lexical binding"));
    }

    @Test
    public void referenceCannotBorrowASiblingScopeWithTheSameLambdaOwner() {
        TypedSemanticGraph graph = typed(
                "let value :I32 = 1 "
                        + "let reader :Fn<;I32> = (=> | | { "
                        + "let left :I32 = { value } "
                        + "let right :I32 = { value } left })");
        ResolvedSemanticGraph resolved = graph.resolvedGraph();
        List<ResolvedReference> references = resolved.references().stream()
                .filter(reference -> reference.name().equals("value"))
                .toList();
        assertEquals(2, references.size());
        ResolvedReference source = references.getFirst();
        ResolvedReference sibling = references.getLast();
        assertFalse(source.scopeId().equals(sibling.scopeId()));
        assertEquals(source.moduleId(), sibling.moduleId());
        assertEquals(source.fromLambda(), sibling.fromLambda());
        assertTrue(source.fromLambda().isPresent());
        assertEquals(source.capture(), sibling.capture());

        ResolvedReference substituted = new ResolvedReference(
                source.id(), source.name(), source.span(), source.moduleId(),
                sibling.scopeId(), source.kind(), source.targetDeclaration(),
                source.targetModule(), source.targetExport(), source.fromLambda(),
                source.capture());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> {
                    ResolvedSemanticGraph forged = new ResolvedSemanticGraph(
                            resolved.moduleGraph(), resolved.scopeTree(), resolved.modules(),
                            resolved.declarations(), replaceValue(
                            resolved.references(), source, substituted), resolved.lambdas(),
                            resolved.imports(), resolved.exports(), resolved.captures(),
                            resolved.mutations(), resolved.syntaxLinks(),
                            resolved.functionLinkage());
                    TypeChecker.check(forged);
                });
        assertTrue(failure.getMessage().contains(
                "exact syntax-derived lexical scope"));
    }

    @Test
    public void canonicalTopologyAcceptsShadowingNestedScopesImportsAndLambdaReferences() {
        TypedSemanticGraph shadowed = typed(
                "let value :I32 = 1 let before :I32 = value "
                        + "let value :I32 = 2 "
                        + "let nested :I32 = { let value :I32 = 3 { value } } "
                        + "let after :I32 = value "
                        + "let reader :Fn<;I32> = (=> | | { value })");
        List<ResolvedDeclaration> values = shadowed.resolvedGraph().declarations().stream()
                .filter(declaration -> declaration.name().equals("value"))
                .toList();
        List<ResolvedReference> valueReferences = shadowed.resolvedGraph().references().stream()
                .filter(reference -> reference.name().equals("value"))
                .toList();
        assertEquals(List.of(
                        values.get(0).id(), values.get(2).id(),
                        values.get(1).id(), values.get(1).id()),
                valueReferences.stream()
                        .map(reference -> reference.targetDeclaration().orElseThrow())
                        .toList());
        ResolvedReference lambdaReference = valueReferences.getLast();
        assertTrue(lambdaReference.fromLambda().isPresent());
        assertTrue(lambdaReference.capture().isPresent());

        TypedSemanticGraph selectiveImport = typed(aggregatePair());
        assertTrue(selectiveImport.resolvedGraph().references().stream().anyMatch(reference ->
                reference.kind() == ReferenceKind.VALUE
                        && reference.targetDeclaration().stream().anyMatch(target ->
                        selectiveImport.resolvedGraph().declaration(target)
                                .orElseThrow().kind() == DeclarationKind.IMPORT_VALUE)));
        TypedSemanticGraph namespaceImport = typed(pair());
        assertTrue(namespaceImport.resolvedGraph().references().stream().anyMatch(reference ->
                reference.kind() == ReferenceKind.MODULE_NAMESPACE));
        assertTrue(namespaceImport.resolvedGraph().references().stream().anyMatch(reference ->
                reference.kind() == ReferenceKind.NAMESPACE_MEMBER));
    }

    @Test
    public void sourceBoundariesCarryProducerIssuedSiteAndCaptureLinks() {
        TypedSemanticGraph graph = typed(
                "let value :I32 = 1 "
                        + "let closure :Fn<;I32> = (=> | | value) "
                        + "let called :I32 = (closure)");
        assertTrue(graph.semanticFlowFacts().events().stream()
                .allMatch(event -> event.siteId().isPresent()));
        assertTrue(graph.semanticFlowFacts().events().stream()
                .filter(event -> event.kind() == SemanticFlowEvent.Kind.CAPTURE)
                .allMatch(event -> event.captureId().isPresent()));
        assertTrue(graph.semanticFlowFacts().events().stream()
                .flatMap(event -> event.value().alternatives().stream())
                .flatMap(value -> value.callableFlows().stream())
                .filter(callable -> !callable.isIntrinsic())
                .allMatch(callable -> callable.creationSite().isPresent()));
        assertTrue(graph.semanticFlowFacts().callableSummaries().orderedSummaries().stream()
                .flatMap(summary -> summary.callReferences().stream())
                .allMatch(call -> call.siteId().isPresent()));
    }

    @Test
    public void scopeForestRejectsIncompleteDisconnectedCyclicAndWrongOwnershipTopology() {
        TypedSemanticGraph graph = typed(
                "let value :Fn<;I32> = (=> | | { 1 })");
        ScopeTree source = graph.resolvedGraph().scopeTree();
        assertThrows(IllegalArgumentException.class,
                () -> new ScopeTree(source.scopes(), Map.of()));

        ResolvedScope root = source.require(source.moduleRoots().values().iterator().next());
        ResolvedScope duplicateChild = new ResolvedScope(
                root.id(), root.parent(), root.kind(), root.moduleId(), root.ownerLambda(),
                root.span(), root.declarations(), List.of(
                root.children().getFirst(), root.children().getFirst()));
        assertThrows(IllegalArgumentException.class, () -> new ScopeTree(
                replaceScope(source.scopes(), root, duplicateChild), source.moduleRoots()));

        ScopeId first = new ScopeId(Long.MAX_VALUE - 10);
        ScopeId second = new ScopeId(Long.MAX_VALUE - 11);
        LambdaId owner = graph.lambdas().getFirst().id();
        ResolvedScope cycleFirst = new ResolvedScope(
                first, Optional.of(second), ScopeKind.BLOCK, root.moduleId(),
                Optional.of(owner), root.span(), List.of(), List.of(second));
        ResolvedScope cycleSecond = new ResolvedScope(
                second, Optional.of(first), ScopeKind.BLOCK, root.moduleId(),
                Optional.of(owner), root.span(), List.of(), List.of(first));
        List<ResolvedScope> cyclic = new ArrayList<>(source.scopes());
        cyclic.add(cycleFirst);
        cyclic.add(cycleSecond);
        assertThrows(IllegalArgumentException.class,
                () -> new ScopeTree(cyclic, source.moduleRoots()));

        ResolvedScope wrongOwnerRoot = new ResolvedScope(
                root.id(), root.parent(), root.kind(), root.moduleId(), Optional.of(owner),
                root.span(), root.declarations(), root.children());
        assertThrows(IllegalArgumentException.class, () -> new ScopeTree(
                replaceScope(source.scopes(), root, wrongOwnerRoot), source.moduleRoots()));

        ResolvedSemanticGraph resolved = graph.resolvedGraph();
        ResolvedModule module = resolved.modules().getFirst();
        ResolvedModule wrongRoot = new ResolvedModule(
                module.moduleId(), module.logicalModule(),
                graph.lambdas().getFirst().scopeId(), module.declarations(),
                module.references(), module.lambdas(), module.imports(), module.exports());
        assertThrows(IllegalArgumentException.class, () -> new ResolvedSemanticGraph(
                resolved.moduleGraph(), resolved.scopeTree(), List.of(wrongRoot),
                resolved.declarations(), resolved.references(), resolved.lambdas(),
                resolved.imports(), resolved.exports(), resolved.captures(),
                resolved.mutations(), resolved.syntaxLinks(), resolved.functionLinkage()));
    }

    @Test
    public void factsOnlyPlannerReconstructsTheSuppliedPlanWithoutEvaluator() {
        long before = SemanticFlowAnalyzer.canonicalAnalysisCount();
        TypedSemanticGraph graph = typed("let value :I32 = 1");
        long after = SemanticFlowAnalyzer.canonicalAnalysisCount();
        SemanticFlowFacts facts = graph.semanticFlowFacts();
        // Publication already succeeded with one canonical evaluator run;
        // the facts-only planner is the path used by graph sealing.
        assertEquals(before + 1, after);
        assertEquals(facts, graph.flowFacts());
        assertTrue(graph.initializationPlan().isAcyclic());
    }

    private static ResolvedSemanticGraph rebuildResolved(
            ResolvedSemanticGraph source,
            ModuleGraph moduleGraph) {
        return new ResolvedSemanticGraph(
                moduleGraph, source.scopeTree(), source.modules(), source.declarations(),
                source.references(), source.lambdas(), source.imports(), source.exports(),
                source.captures(), source.mutations(), source.syntaxLinks(),
                source.functionLinkage());
    }

    private static ResolvedSemanticGraph rebuildResolved(
            ResolvedSemanticGraph source,
            List<ResolvedReference> references,
            List<ResolvedCapture> captures) {
        return new ResolvedSemanticGraph(
                source.moduleGraph(), source.scopeTree(), source.modules(),
                source.declarations(), references, source.lambdas(), source.imports(),
                source.exports(), captures, source.mutations(), source.syntaxLinks(),
                source.functionLinkage());
    }

    private static ResolvedSemanticGraph rebuildResolved(
            ResolvedSemanticGraph source,
            List<ResolvedModule> modules,
            List<ResolvedDeclaration> declarations,
            List<ResolvedReference> references,
            List<ResolvedLambda> lambdas,
            List<ResolvedImportBinding> imports,
            List<ResolvedExport> exports,
            List<ResolvedMutation> mutations) {
        return new ResolvedSemanticGraph(
                source.moduleGraph(), source.scopeTree(), modules, declarations, references,
                lambdas, imports, exports, source.captures(), mutations,
                source.syntaxLinks(), source.functionLinkage());
    }

    private static ResolvedModule withImports(
            ResolvedModule source,
            List<ResolvedImportBinding> imports) {
        return new ResolvedModule(
                source.moduleId(), source.logicalModule(), source.rootScope(),
                source.declarations(), source.references(), source.lambdas(),
                imports, source.exports());
    }

    private static ResolvedModule withExports(
            ResolvedModule source,
            List<ResolvedExport> exports) {
        return new ResolvedModule(
                source.moduleId(), source.logicalModule(), source.rootScope(),
                source.declarations(), source.references(), source.lambdas(),
                source.imports(), exports);
    }

    private static ResolvedReference withCapture(
            ResolvedReference source,
            Optional<CaptureId> capture) {
        return new ResolvedReference(
                source.id(), source.name(), source.span(), source.moduleId(), source.scopeId(),
                source.kind(), source.targetDeclaration(), source.targetModule(),
                source.targetExport(), source.fromLambda(), capture);
    }

    private static ResolvedCapture withCaptureReferences(
            ResolvedCapture source,
            List<ReferenceId> references) {
        return new ResolvedCapture(
                source.id(), source.lambdaId(), source.declarationId(), source.moduleId(),
                source.span(), source.declarationSpan(), source.mode(), source.sharedCellId(),
                references);
    }

    private static ResolvedLambda withOwnerDeclaration(
            ResolvedLambda source,
            Optional<DeclarationId> owner) {
        return new ResolvedLambda(
                source.id(), source.moduleId(), source.span(), source.scopeId(),
                source.bodySpan(), source.signature(), source.parameterIds(), owner,
                source.captures(), source.signatureComplete());
    }

    private static ResolvedDeclaration withInitializerLambda(
            ResolvedDeclaration source,
            Optional<LambdaId> initializerLambda) {
        return new ResolvedDeclaration(
                source.id(), source.name(), source.nameSpan(), source.span(),
                source.moduleId(), source.scopeId(), source.kind(), source.visibility(),
                source.bindingMutability(), source.declaredContract(),
                source.inferredContract(), source.effectiveContract(),
                source.functionSignature(), initializerLambda, source.signaturePredeclared(),
                source.imported(), source.reExported(), source.importedModule(),
                source.importedName(), source.originDeclaration(), source.originExport(),
                source.replacementOf());
    }

    private static <T> List<T> replaceValue(
            List<T> values,
            T source,
            T replacement) {
        ArrayList<T> result = new ArrayList<>(values);
        int index = result.indexOf(source);
        if (index < 0) {
            throw new IllegalArgumentException("replacement source is absent");
        }
        result.set(index, replacement);
        return List.copyOf(result);
    }

    private static SemanticFlowEvent declarationEvent(TypedSemanticGraph graph) {
        ModuleId root = graph.resolvedGraph().moduleGraph().rootModule();
        return graph.semanticFlowFacts().events().stream()
                .filter(event -> event.kind() == SemanticFlowEvent.Kind.DECLARATION
                        && event.moduleId().equals(root))
                .findFirst().orElseThrow();
    }

    private static TypedSemanticGraph sealWithReplacedDeclarationValue(
            TypedSemanticGraph graph,
            SemanticFlowEvent declaration,
            ValueAlternatives replacement) {
        SemanticFlowFacts source = graph.semanticFlowFacts();
        TreeMap<DeclarationId, ValueAlternatives> values =
                new TreeMap<>(source.declarationValues());
        values.put(declaration.declarationId().orElseThrow(), replacement);
        SemanticFlowFacts forged = new SemanticFlowFacts(
                source.callableSummaries(), source.normalizedExpressions(),
                replaceEvent(source.events(), declaration, withValue(declaration, replacement)),
                source.eagerEffectFacts(), source.eagerCycles(), values);
        return seal(graph, forged, graph.initializationPlan());
    }

    private static SemanticFlowFacts withSummary(
            SemanticFlowFacts source,
            CallableSummary original,
            CallableSummary replacement) {
        List<CallableSummary> summaries = new ArrayList<>(
                source.callableSummaries().orderedSummaries());
        summaries.remove(original);
        summaries.add(replacement);
        CallableSummarySet set = new CallableSummarySet(
                summaries, source.callableSummaries().lambdaByDeclaration(),
                source.callableSummaries().intrinsicDeclarations(),
                source.callableSummaries().components());
        return new SemanticFlowFacts(
                set, source.normalizedExpressions(), source.events(),
                source.eagerEffectFacts(), source.eagerCycles(),
                source.declarationValues());
    }

    private static SemanticFlowFacts replaceWitness(
            SemanticFlowFacts source,
            EagerEffectWitness original,
            EagerEffectWitness replacement) {
        List<SemanticFlowEvent> events = source.events().stream()
                .map(event -> withEffects(event, event.effects().stream()
                        .map(effect -> effect.equals(original) ? replacement : effect)
                        .toList()))
                .toList();
        List<EagerEffectFact> effects = source.eagerEffectFacts().stream()
                .map(fact -> fact.witness().equals(original)
                        ? new EagerEffectFact(
                        fact.initializerModule(), fact.initializerDeclaration(), replacement)
                        : fact)
                .toList();
        return new SemanticFlowFacts(
                source.callableSummaries(), source.normalizedExpressions(), events,
                effects, source.eagerCycles(), source.declarationValues());
    }

    private static CallableSummary replaceCall(
            CallableSummary summary,
            CallableCallReference original,
            CallableCallReference replacement) {
        List<CallableCallReference> calls = new ArrayList<>(summary.callReferences());
        calls.remove(original);
        calls.add(replacement);
        return new CallableSummary(
                summary.lambdaId(), summary.moduleId(), summary.span(), summary.scopeId(),
                summary.signature(), summary.parameters(), summary.captures(),
                summary.returnFormula(), summary.writes(), calls, summary.eagerEffects(),
                summary.normalizedBody(), true, summary.fixedPointIterations(),
                summary.limits());
    }

    private static List<ResolvedScope> replaceScope(
            List<ResolvedScope> scopes,
            ResolvedScope original,
            ResolvedScope replacement) {
        ArrayList<ResolvedScope> result = new ArrayList<>(scopes);
        result.remove(original);
        result.add(replacement);
        return result;
    }

    private static ValueAlternatives withWitnessSpan(
            ValueAlternatives source,
            SourceSpan span) {
        return new ValueAlternatives(source.alternatives().stream().map(value ->
                new ValueAlternative(value.type(), value.aggregateIdentities().stream()
                        .map(fact -> new AggregateIdentityFact(
                                fact.identity(), fact.route(), new OwnershipWitness(
                                fact.witness().ownerModule(), fact.witness().originDeclaration(),
                                fact.witness().scopeId(), span,
                                fact.witness().originExport())))
                        .toList(), value.callableFlows())).toList());
    }

    private static ValueAlternatives withWitnessScope(
            ValueAlternatives source,
            ScopeId scope) {
        return new ValueAlternatives(source.alternatives().stream().map(value ->
                new ValueAlternative(value.type(), value.aggregateIdentities().stream()
                        .map(fact -> new AggregateIdentityFact(
                                fact.identity(), fact.route(), new OwnershipWitness(
                                fact.witness().ownerModule(), fact.witness().originDeclaration(),
                                scope, fact.witness().sourceSpan(),
                                fact.witness().originExport())))
                        .toList(), value.callableFlows())).toList());
    }

    private static ValueAlternatives withLambda(
            ValueAlternatives source,
            LambdaId lambda) {
        return new ValueAlternatives(source.alternatives().stream().map(value ->
                new ValueAlternative(value.type(), value.aggregateIdentities(),
                        value.callableFlows().stream().map(callable -> new CallableFlow(
                                Optional.of(lambda), Optional.empty(), callable.route(),
                                callable.capturedValues(), callable.sharedCellSnapshots()))
                                .toList())).toList());
    }

    private static SemanticFlowEvent withEffects(
            SemanticFlowEvent source,
            List<EagerEffectWitness> effects) {
        return new SemanticFlowEvent(
                source.kind(), source.moduleId(), source.span(),
                source.initializerDeclaration(), source.declarationId(),
                source.targetDeclaration(), source.lambdaId(), source.referenceId(),
                source.callId(), source.route(), source.value(), source.writes(), effects,
                source.siteId(), source.captureId());
    }

    private static SemanticFlowEvent withValue(
            SemanticFlowEvent source,
            ValueAlternatives value) {
        return new SemanticFlowEvent(
                source.kind(), source.moduleId(), source.span(),
                source.initializerDeclaration(), source.declarationId(),
                source.targetDeclaration(), source.lambdaId(), source.referenceId(),
                source.callId(), source.route(), value, source.writes(), source.effects(),
                source.siteId(), source.captureId());
    }

    private static List<SemanticFlowEvent> replaceEvent(
            List<SemanticFlowEvent> events,
            SemanticFlowEvent source,
            SemanticFlowEvent replacement) {
        ArrayList<SemanticFlowEvent> result = new ArrayList<>(events);
        result.remove(source);
        result.add(replacement);
        return result;
    }

    private static TypedSemanticGraph seal(
            TypedSemanticGraph original,
            SemanticFlowFacts facts,
            InitializationPlan plan) {
        return SemanticTestSupport.seal(original, facts, plan);
    }

    private static TypedSemanticGraph typed(String source) {
        return typed(single(source));
    }

    private static TypedSemanticGraph typed(ModuleGraph moduleGraph) {
        PhaseResult<TypedSemanticGraph> result = TypeChecker.check(moduleGraph);
        assertTrue(result instanceof PhaseResult.Success<?>, result.diagnostics().toString());
        return ((PhaseResult.Success<TypedSemanticGraph>) result).value();
    }

    private static ModuleGraph single(String source) {
        ModuleId moduleId = ModuleId.path("gate11e.lyra");
        ModuleGraph.Node node = node(moduleId, source);
        return new ModuleGraph(moduleId, List.of(node), List.of(), Map.of());
    }

    private static ModuleGraph topologyGraph() {
        ModuleId main = ModuleId.path("gate11e_topology_main.lyra");
        ModuleId origin = ModuleId.path("gate11e_topology_origin.lyra");
        ModuleId other = ModuleId.path("gate11e_topology_other.lyra");
        LogicalModuleId originLogical = LogicalModuleId.parse("gate11e_topology_origin");
        LogicalModuleId otherLogical = LogicalModuleId.parse("gate11e_topology_other");
        List<ModuleGraph.Node> nodes = List.of(node(main,
                        "import gate11e_topology_origin->{value} "
                                + "import @pub gate11e_topology_origin->{value as exposed} "
                                + "import gate11e_topology_other "
                                + "let owner :Fn<;I32> = (=> | | 1)"),
                node(origin, "let @pub value :I32 = 1"),
                node(other, "let @pub value :I32 = 2"));
        Map<LogicalModuleId, ModuleId> modules = Map.of(
                originLogical, origin, otherLogical, other);
        List<ModuleGraph.Edge> edges = nodes.getFirst().program().imports().stream()
                .map(importDeclaration -> {
                    LogicalModuleId logical = LogicalModuleId.fromImportPath(
                            importDeclaration.path());
                    return new ModuleGraph.Edge(
                            main, logical, modules.get(logical),
                            importDeclaration.path().span());
                })
                .toList();
        return new ModuleGraph(main, nodes, edges, modules);
    }

    private static ModuleGraph aggregatePair() {
        ModuleId main = ModuleId.path("gate11e_array_main.lyra");
        ModuleId library = ModuleId.path("gate11e_array_lib.lyra");
        LogicalModuleId logical = LogicalModuleId.parse("gate11e_array_lib");
        ModuleGraph.Node mainNode = node(main, "import gate11e_array_lib->{values} "
                + "let local :Array<I32> = values");
        ModuleGraph.Node libraryNode = node(
                library, "let @pub values :Array<I32> = Array[0]");
        return new ModuleGraph(main, List.of(mainNode, libraryNode),
                List.of(importEdge(mainNode, logical, library)), Map.of(logical, library));
    }

    private static ModuleGraph callableEffectPair() {
        ModuleId main = ModuleId.path("gate11e_callable_main.lyra");
        ModuleId library = ModuleId.path("gate11e_callable_lib.lyra");
        LogicalModuleId logical = LogicalModuleId.parse("gate11e_callable_lib");
        ModuleGraph.Node mainNode = node(main,
                "import gate11e_callable_lib->{first second} "
                        + "let value :I32 = ::first[]");
        ModuleGraph.Node libraryNode = node(library,
                "let @pub first :Fn<;I32> = (=> | | 1) "
                        + "let @pub second :Fn<;I32> = (=> | | 2)");
        return new ModuleGraph(main, List.of(mainNode, libraryNode),
                List.of(importEdge(mainNode, logical, library)), Map.of(logical, library));
    }

    private static ModuleGraph pairWithoutRead() {
        ModuleId main = ModuleId.path("gate11e_unused_main.lyra");
        ModuleId library = ModuleId.path("gate11e_unused_library.lyra");
        LogicalModuleId logical = LogicalModuleId.parse("gate11e_unused_library");
        ModuleGraph.Node mainNode = node(
                main, "import gate11e_unused_library let value :I32 = 1");
        ModuleGraph.Node libraryNode = node(
                library, "let @pub dependency :I32 = 2");
        return new ModuleGraph(main, List.of(mainNode, libraryNode),
                List.of(importEdge(mainNode, logical, library)), Map.of(logical, library));
    }

    private static ModuleGraph captureImportPair() {
        ModuleId main = ModuleId.path("gate11e_capture_main.lyra");
        ModuleId library = ModuleId.path("gate11e_capture_library.lyra");
        LogicalModuleId logical = LogicalModuleId.parse("gate11e_capture_library");
        ModuleGraph.Node mainNode = node(main,
                "import gate11e_capture_library->{value} "
                        + "let closure :Fn<;I32> = (=> | | value) "
                        + "let result :I32 = (closure)");
        ModuleGraph.Node libraryNode = node(library, "let @pub value :I32 = 1");
        return new ModuleGraph(main, List.of(mainNode, libraryNode),
                List.of(importEdge(mainNode, logical, library)), Map.of(logical, library));
    }

    private static ModuleGraph pair() {
        ModuleId main = ModuleId.path("gate11e_main.lyra");
        ModuleId library = ModuleId.path("gate11e_library.lyra");
        LogicalModuleId logical = LogicalModuleId.parse("gate11e_library");
        ModuleGraph.Node mainNode = node(main, "import gate11e_library "
                + "let @pub value :I32 = gate11e_library->:.value");
        ModuleGraph.Node libraryNode = node(library, "let @pub value :I32 = 1");
        return new ModuleGraph(main, List.of(mainNode, libraryNode),
                List.of(importEdge(mainNode, logical, library)), Map.of(logical, library));
    }

    private static ModuleGraph.Edge importEdge(
            ModuleGraph.Node source,
            LogicalModuleId logical,
            ModuleId target) {
        SourceSpan span = source.program().imports().stream()
                .filter(declaration -> LogicalModuleId.fromImportPath(declaration.path())
                        .equals(logical))
                .map(declaration -> declaration.path().span())
                .findFirst()
                .orElseThrow();
        return new ModuleGraph.Edge(source.moduleId(), logical, target, span);
    }

    private static ModuleGraph.Node node(ModuleId moduleId, String source) {
        PhaseResult<SourceSnapshot> snapshot = SourceSnapshot.capture(
                moduleId.sourceId(),
                PhysicalSourceKey.uri(URI.create("memory:" + moduleId.value())),
                source.getBytes(StandardCharsets.UTF_8));
        SourceSnapshot value = ((PhaseResult.Success<SourceSnapshot>) snapshot).value();
        LexedSource lexed = ((PhaseResult.Success<LexedSource>) Lexer.lex(value)).value();
        GrammarProgram grammar = ((PhaseResult.Success<GrammarProgram>) GrammarMatcher.match(lexed)).value();
        SyntaxProgram program = ((PhaseResult.Success<SyntaxProgram>) Parser.parse(
                lexed, grammar)).value();
        return new ModuleGraph.Node(
                moduleId, Optional.empty(), value, program,
                ModuleRevision.compute(value));
    }
}
