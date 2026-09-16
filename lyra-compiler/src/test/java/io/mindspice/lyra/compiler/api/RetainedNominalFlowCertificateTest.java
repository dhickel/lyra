package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.semantic.TypedDeclaration;
import io.mindspice.lyra.compiler.semantic.TypedExpression;
import io.mindspice.lyra.compiler.semantic.TypedExpressionKind;
import io.mindspice.lyra.compiler.semantic.flow.AggregateIdentityFact;
import io.mindspice.lyra.compiler.semantic.flow.NilProvenance;
import io.mindspice.lyra.compiler.semantic.flow.NominalObjectFact;
import io.mindspice.lyra.compiler.semantic.flow.OwnershipWitness;
import io.mindspice.lyra.compiler.semantic.flow.ProjectionPath;
import io.mindspice.lyra.compiler.semantic.flow.ProjectionStep;
import io.mindspice.lyra.compiler.semantic.flow.RetainedAllocationDerivation;
import io.mindspice.lyra.compiler.semantic.flow.ValueAlternative;
import io.mindspice.lyra.compiler.semantic.flow.ValueAlternatives;
import io.mindspice.lyra.compiler.session.SessionSnapshot;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.PrimitiveType;
import io.mindspice.lyra.compiler.types.TupleType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public certificate coverage for closed retained-nominal initializer proofs. */
final class RetainedNominalFlowCertificateTest {
    @Test
    void legalityInventoryGuardsEveryCurrentMemberInitializerKind() {
        record Probe(String name, String type, String initializer, TypedExpressionKind kind,
                     boolean legal, String code) { }
        List<Probe> probes = List.of(
                new Probe("literal", "I32", "1I32", TypedExpressionKind.LITERAL, true, ""),
                new Probe("reference", "I32", "external", TypedExpressionKind.REFERENCE, true, ""),
                new Probe("member access", "I32", "self:.seed", TypedExpressionKind.MEMBER_ACCESS, true, ""),
                new Probe("array length", "I32", "Array<I32>[1I32]:.length", TypedExpressionKind.MEMBER_ACCESS, true, ""),
                new Probe("string length", "I32", "\"x\":.length", TypedExpressionKind.MEMBER_ACCESS, true, ""),
                new Probe("lambda", "Fn<;I32>", "(=> || 1I32)", TypedExpressionKind.LAMBDA, true, ""),
                new Probe("callable call", "I32", "(zero)", TypedExpressionKind.CALLABLE_CALL, true, ""),
                new Probe("direct call", "I32", "::inc[1I32]", TypedExpressionKind.DIRECT_CALL, true, ""),
                new Probe("namespace member", "Fn<String;Unit>", "io->:.println", TypedExpressionKind.NAMESPACE_MEMBER_ACCESS, true, ""),
                new Probe("namespace direct call", "Unit", "io->::println[\"\"]", TypedExpressionKind.NAMESPACE_DIRECT_CALL, true, ""),
                new Probe("array", "Array<I32>", "Array<I32>[1I32]", TypedExpressionKind.ARRAY_LITERAL, true, ""),
                new Probe("tuple", "Tuple<I32>", "Tuple[1I32]", TypedExpressionKind.TUPLE_LITERAL, true, ""),
                new Probe("construction", "Nested", ":Nested[]", TypedExpressionKind.CONSTRUCTION, true, ""),
                new Probe("operator", "I32", "(+ 1I32 2I32)", TypedExpressionKind.OPERATOR, true, ""),
                new Probe("short circuit", "Bool", "(and #T #F)", TypedExpressionKind.SHORT_CIRCUIT, true, ""),
                new Probe("block/declaration/rebinding", "I32", "{ let @mut local :I32 = 1I32 local := 2I32 local }", TypedExpressionKind.BLOCK, true, ""),
                new Probe("conditional", "I32", "(maybe -> 1I32 : 2I32)", TypedExpressionKind.CONDITIONAL, true, ""),
                new Probe("nominal conditional", "Bool", "(externalNominal -> #T : #F)", TypedExpressionKind.CONDITIONAL, true, ""),
                new Probe("nominal short circuit", "Bool", "(and externalNominal #T)", TypedExpressionKind.SHORT_CIRCUIT, true, ""),
                new Probe("nominal match guard", "Bool", "(match 1I32 _ when externalNominal -> #T _ -> #F)", TypedExpressionKind.MATCH, true, ""),
                new Probe("nominal cond", "Bool", "(cond externalNominal -> #T _ -> #F)", TypedExpressionKind.COND, true, ""),
                new Probe("coalesce", "I32", "(maybe : 1I32)", TypedExpressionKind.COALESCE, true, ""),
                new Probe("match", "I32", "(match 1I32 1I32 -> 1I32 _ -> 2I32)", TypedExpressionKind.MATCH, true, ""),
                new Probe("cond", "I32", "(cond maybe -> 1I32 _ -> 2I32)", TypedExpressionKind.COND, true, ""),
                new Probe("array index", "I32", "Array<I32>[1I32][0I32]", TypedExpressionKind.INDEX_ACCESS, true, ""),
                new Probe("string index", "Char", "\"x\"[0I32]", TypedExpressionKind.INDEX_ACCESS, true, ""),
                new Probe("conversion", "I32", "I32[1I16]", TypedExpressionKind.CONVERSION, true, ""),
                new Probe("predicate narrowing", "I32", "(maybe narrowed -> narrowed : 0I32)", TypedExpressionKind.CONDITIONAL, true, ""),
                new Probe("range", "Range<I32>", "(0I32..2I32:1I32)", TypedExpressionKind.RANGE, true, ""),
                new Probe("iter", "Unit", "iter[(0I32..2I32:1I32) || ()]", TypedExpressionKind.ITER, true, ""),
                new Probe("while", "Unit", "while[|| #F || ()]", TypedExpressionKind.WHILE, true, ""),
                new Probe("nominal declaration", "Unit", "struct Invalid { value :I32 }", TypedExpressionKind.NOMINAL_DECLARATION, false, "LYC-PARSE-001"),
                new Probe("top-level declaration", "I32", "let local :I32 = 1I32", TypedExpressionKind.DECLARATION, false, "LYC-PARSE-001"),
                new Probe("top-level rebinding", "I32", "local := 1I32", TypedExpressionKind.REBINDING, false, "LYC-PARSE-003"));
        for (Probe probe : probes) {
            String source = "import std->io let inc :Fn<I32;I32> = (=> |value| value) let zero :Fn<;I32> = (=> || 0I32) let external :I32 = 1I32 "
                    + "let @nil maybe :I32 = #NIL class Nested { value :I32 = 1I32 } let externalNominal :Nested = :Nested[] class C { "
                    + "seed :I32 = 1I32 value :" + probe.type() + " = " + probe.initializer() + " }";
            SessionCompileResult result = LyraCompiler.compileSession(
                    new SessionCompileRequest("inventory-" + probe.name() + ".lyra", source,
                            SessionSnapshot.empty()));
            if (probe.legal()) {
                SessionCompileResult.Success success = assertInstanceOf(SessionCompileResult.Success.class, result,
                        probe.name() + ": " + result.diagnostics());
                var kinds = success.typedGraph().expressions().stream().map(TypedExpression::kind).toList();
                assertTrue(kinds.stream().anyMatch(probe.kind()::equals),
                        probe.name() + " did not produce " + probe.kind());
                if (probe.name().equals("block/declaration/rebinding")) {
                    assertTrue(kinds.contains(TypedExpressionKind.DECLARATION));
                    assertTrue(kinds.contains(TypedExpressionKind.REBINDING));
                }
                compile("inventory-consumer-" + probe.name() + ".lyra",
                        "let value :C = :C[]", success.stagedSnapshot());
            } else {
                SessionCompileResult.Failure failure = assertInstanceOf(SessionCompileResult.Failure.class, result);
                assertEquals(probe.code(), failure.diagnostics().getFirst().code().value(), probe.name());
            }
        }
    }

    @Test
    void alternativesIssueExplicitPrefixAndIndependentBranchProofs() {
        SessionCompileResult.Success producer = compile("alternative-shape.lyra", """
                let @nil maybe :I32 = 7I32
                class Choices {
                    conditional :I32 = (#T -> 1I32 : 2I32)
                    unitConditional :Unit = (#T -> ())
                    coalesced :I32 = (maybe : 3I32)
                    matched :I32 = (match 1I32 1I32 -> 4I32 _ -> 5I32)
                    conditioned :I32 = (cond #F -> 6I32 _ -> 7I32)
                }
                """, SessionSnapshot.empty());
        SessionFlowCertificate.RetainedNominal choices = nominal(certificate(producer), "Choices");
        var conditional = assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Alternative.class,
                transfer(choices, "conditional"));
        var unit = assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Alternative.class,
                transfer(choices, "unitConditional"));
        var coalesced = assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Alternative.class,
                transfer(choices, "coalesced"));
        var matched = assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Alternative.class,
                transfer(choices, "matched"));
        var conditioned = assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Alternative.class,
                transfer(choices, "conditioned"));

        assertEquals(SessionFlowCertificate.RetainedInitializerTransfer.AlternativeKind.CONDITIONAL, conditional.kind());
        assertEquals(1, conditional.prefix().size());
        assertEquals(2, conditional.branches().size());
        assertTrue(conditional.branches().stream().allMatch(branch -> branch.selectors().isEmpty()
                && branch.result().isPresent()));
        assertTrue(unit.branches().get(1).result().isEmpty());
        assertEquals(SessionFlowCertificate.RetainedInitializerTransfer.AlternativeKind.COALESCE, coalesced.kind());
        assertEquals(1, coalesced.prefix().size());
        assertEquals(1, coalesced.branches().size());
        assertTrue(coalesced.branches().getFirst().result().isPresent());
        assertEquals(SessionFlowCertificate.RetainedInitializerTransfer.AlternativeKind.MATCH, matched.kind());
        assertEquals(1, matched.prefix().size());
        assertEquals(2, matched.branches().size());
        assertEquals(1, matched.branches().getFirst().selectors().size());
        assertTrue(matched.branches().getFirst().result().isPresent());
        assertTrue(matched.branches().get(1).selectors().isEmpty());
        assertEquals(SessionFlowCertificate.RetainedInitializerTransfer.AlternativeKind.MATCH,
                conditioned.kind());
        assertTrue(conditioned.prefix().isEmpty());
        assertEquals(1, conditioned.branches().getFirst().selectors().size());
        assertTrue(conditioned.branches().getLast().wildcard());
    }

    @Test
    void retainedAlternativeBranchesCarryTheSameObservableEffectsAsOrdinaryAnalysis() {
        String definitions = """
                import std->io
                let @nil maybe :I32 = 7I32
                let conditionalEffect :Fn<;I32> = (=> || { io->::println["conditional"] 0I32 })
                let coalesceEffect :Fn<;I32> = (=> || { io->::println["coalesce"] 0I32 })
                let matchEffect :Fn<;I32> = (=> || { io->::println["match"] 0I32 })
                let condEffect :Fn<;I32> = (=> || { io->::println["cond"] 0I32 })
                class Choices {
                    conditional :I32 = (#T -> 1I32 : (conditionalEffect))
                    coalesced :I32 = (maybe : (coalesceEffect))
                    matched :I32 = (match 1I32 1I32 -> 1I32 _ -> (matchEffect))
                    conditioned :I32 = (cond #T -> 1I32 _ -> (condEffect))
                }
                """;
        SessionCompileResult.Success producer = compile("alternative-effects-producer.lyra", definitions,
                SessionSnapshot.empty());
        SessionCompileResult.Success retained = compile("alternative-effects-consumer.lyra",
                "let choices :Choices = :Choices[]", producer.stagedSnapshot());
        SessionCompileResult.Success ordinary = compile("alternative-effects-ordinary.lyra",
                definitions + "\nlet choices :Choices = :Choices[]", SessionSnapshot.empty());
        assertFalse(retained.typedGraph().semanticFlowFacts().eagerEffectFacts().isEmpty());
        assertEquals(ordinary.typedGraph().semanticFlowFacts().eagerEffectFacts().size(),
                retained.typedGraph().semanticFlowFacts().eagerEffectFacts().size());
    }

    @Test
    void retainedCallableRoutesAndCallableValueCallsKeepExactClosedProofs() {
        SessionCompileResult.Success producer = compile("callable-routes-proof.lyra", """
                let selected :Fn<;I32> = (=> || 3I32)
                class Routes {
                    call :I32 = (selected)
                    chosen :Fn<;I32> = (cond #T -> selected _ -> (=> || 4I32))
                    array :Array<Fn<;I32>> = Array<Fn<;I32>>[(=> || 5I32)]
                    tuple :Tuple<Fn<;I32>,Fn<;I32>> = Tuple[(=> || 6I32) (=> || 7I32)]
                }
                """, SessionSnapshot.empty());
        SessionFlowCertificate.RetainedNominal routes = nominal(certificate(producer), "Routes");
        assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.CallableCall.class,
                transfer(routes, "call"));
        var chosen = assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Alternative.class,
                transfer(routes, "chosen"));
        assertEquals(SessionFlowCertificate.RetainedInitializerTransfer.AlternativeKind.MATCH,
                chosen.kind());
        assertTrue(chosen.branches().stream().allMatch(branch -> branch.result().isPresent()));
        var array = assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                transfer(routes, "array"));
        var tuple = assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                transfer(routes, "tuple"));
        assertEquals(1, array.elements().size());
        assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Lambda.class, array.elements().getFirst());
        assertEquals(2, tuple.elements().size());
        tuple.elements().forEach(element -> assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Lambda.class, element));
    }

    @Test
    void retainedConstructionCertificationRejectsUnderivableRoutes() {
        var producer = compile("object-route-producer.lyra", """
                class Nested { @pub value :I32 = 7I32 }
                let make :Fn<;Tuple<Nested>> = (=> || Tuple[:Nested[]])
                class Holder { @pub value :Tuple<Nested> = ::make[] }
                """, SessionSnapshot.empty());
        var certificate = certificate(producer);
        var construction = certificate.callableSummaries().orderedSummaries().stream()
                .flatMap(summary -> summary.callReferences().stream())
                .map(certificate::retainedConstruction).flatMap(Optional::stream)
                .findFirst().orElseThrow();
        var root = new NominalObjectFact(
                new io.mindspice.lyra.compiler.semantic.flow.NominalObjectIdentity(
                        construction.moduleId(), construction.site(), construction.nominalType()),
                ProjectionPath.root(), OwnershipWitness.local(construction.moduleId(),
                construction.allocation(), construction.scopeId(), construction.call().span())
                .withOriginSite(construction.site()));
        assertTrue(certificate.certifiesObject(root));
        assertTrue(certificate.certifiesObject(root.prefixedBy(ProjectionPath.tupleMember(0))));
        assertFalse(certificate.certifiesObject(root.prefixedBy(ProjectionPath.tupleMember(99))));
        assertFalse(certificate.certifiesObject(root.prefixedBy(ProjectionPath.arrayElement(0))));
        assertFalse(certificate.certifiesObject(root.prefixedBy(ProjectionPath.unknownArrayElement())));
        compile("object-route-consumer.lyra", "let holder :Holder = :Holder[]", producer.stagedSnapshot());
    }

    @Test
    void derivedCertificationRejectsDiscardedSequenceAllocations() {
        SessionCompileResult.Success producer = compile("discarded-allocation-producer.lyra", """
                class Box {
                    @pub values :Array<I32> = {
                        let discarded :Array<I32> = Array<I32>[1 2]
                        Array<I32>[3 4]
                    }
                }
                """, SessionSnapshot.empty());
        SessionFlowCertificate.RetainedNominal box = nominal(certificate(producer), "Box");
        var sequence = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Sequence.class,
                transfer(box, "values"));
        var discarded = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Declare.class,
                sequence.steps().getFirst());
        var discardedArray = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                discarded.initializer()).allocation().orElseThrow();
        var returnedArray = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                sequence.steps().getLast()).allocation().orElseThrow();
        SessionCompileResult.Success consumer = compile("discarded-allocation-consumer.lyra",
                "let box :Box = :Box[]", producer.stagedSnapshot());
        TypedExpression construction = consumer.typedGraph().expressions().stream()
                .filter(expression -> expression.kind() == TypedExpressionKind.CONSTRUCTION)
                .findFirst().orElseThrow();
        var context = consumer.typedGraph().flowSiteId(construction);

        assertFalse(certificate(producer).certifiesDerivedAggregate(
                derivedArrayFact(discardedArray, context, construction.span()),
                context, construction, Set.of()));
        assertTrue(certificate(producer).certifiesDerivedAggregate(
                derivedArrayFact(returnedArray, context, construction.span()),
                context, construction, Set.of()));
    }

    @Test
    void derivedCertificationFollowsTheExactShadowedResultBinding() {
        SessionCompileResult.Success producer = compile("shadowed-allocation-producer.lyra", """
                class Box {
                    @pub values :Array<I32> = {
                        let selected :Array<I32> = Array<I32>[1]
                        {
                            let selected :Array<I32> = Array<I32>[2]
                            selected
                        }
                    }
                }
                """, SessionSnapshot.empty());
        var outer = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Sequence.class,
                transfer(nominal(certificate(producer), "Box"), "values"));
        var shadowed = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Declare.class,
                outer.steps().getFirst());
        var shadowedArray = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                shadowed.initializer()).allocation().orElseThrow();
        var inner = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Sequence.class,
                outer.steps().getLast());
        var selected = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Declare.class,
                inner.steps().getFirst());
        var selectedArray = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                selected.initializer()).allocation().orElseThrow();
        SessionCompileResult.Success consumer = compile("shadowed-allocation-consumer.lyra",
                "let box :Box = :Box[]", producer.stagedSnapshot());
        TypedExpression construction = consumer.typedGraph().expressions().stream()
                .filter(expression -> expression.kind() == TypedExpressionKind.CONSTRUCTION)
                .findFirst().orElseThrow();
        var context = consumer.typedGraph().flowSiteId(construction);

        assertFalse(certificate(producer).certifiesDerivedAggregate(
                derivedArrayFact(shadowedArray, context, construction.span()),
                context, construction, Set.of()));
        assertTrue(certificate(producer).certifiesDerivedAggregate(
                derivedArrayFact(selectedArray, context, construction.span()),
                context, construction, Set.of()));
    }

    @Test
    void rebindThenTupleRetainedInitializerCompilesWithExactMemberRouting() {
        // A conversion wrapping a sequence-local reference must keep the
        // post-rebind aggregate identity through tuple-member routing; the
        // consumer compilation used to die with an internal
        // "aggregate owner module is foreign" bug exception.
        SessionCompileResult.Success producer = compile("rebind-tuple-producer.lyra", """
                class Holder {
                    @pub pair :Tuple<Array<I32>,I32> = {
                        let @mut selected :Array<I32> = Array<I32>[1 2]
                        Tuple[selected 3]
                    }
                }
                """, SessionSnapshot.empty());
        SessionFlowCertificate.RetainedNominal holder = nominal(certificate(producer), "Holder");
        var sequence = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Sequence.class,
                transfer(holder, "pair"));
        var declared = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Declare.class,
                sequence.steps().getFirst());
        var selectedArray = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                declared.initializer()).allocation().orElseThrow();
        var tuple = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                sequence.steps().getLast());
        var converted = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Apply.class,
                tuple.elements().getFirst());
        assertTrue(converted.kind() == SessionFlowCertificate.RetainedInitializerTransfer.ApplyKind.CONVERSION
                        || converted.kind() == SessionFlowCertificate.RetainedInitializerTransfer.ApplyKind.NARROWING,
                "the tuple element is the conversion over the sequence reference");
        assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Reference.class,
                converted.operands().getLast());

        SessionCompileResult.Success consumer = compile("rebind-tuple-consumer.lyra",
                "let holder :Holder = :Holder[]", producer.stagedSnapshot());
        TypedExpression construction = consumer.typedGraph().expressions().stream()
                .filter(expression -> expression.kind() == TypedExpressionKind.CONSTRUCTION)
                .findFirst().orElseThrow();
        var context = consumer.typedGraph().flowSiteId(construction);
        AggregateIdentityFact expected = derivedCrossModuleArrayFact(
                selectedArray, context, construction.span()).prefixedBy(
                ProjectionPath.tupleMember(0));
        assertTrue(certificate(producer).certifiesDerivedAggregate(
                expected, context, construction, Set.of()));

        var pairValue = certificate(consumer).boundaryState().objects().values().stream()
                .filter(object -> object.schema().type().id().name().equals("Holder"))
                .findFirst().orElseThrow().fields().get(0).only();
        assertEquals(List.of(expected), pairValue.aggregateIdentities(),
                "the consumer state keeps the exact producer array at tuple route .0");
    }

    @Test
    void derivedCertificationRejectsOverwrittenRebindAllocations() {
        SessionCompileResult.Success producer = compile("overwritten-rebind-producer.lyra", """
                class Box {
                    @pub values :Array<I32> = {
                        let @mut selected :Array<I32> = Array<I32>[1 2]
                        selected := Array<I32>[3 4]
                        selected := Array<I32>[5 6]
                        selected
                    }
                }
                """, SessionSnapshot.empty());
        var sequence = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Sequence.class,
                transfer(nominal(certificate(producer), "Box"), "values"));
        var initial = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Declare.class,
                        sequence.steps().get(0)).initializer()).allocation().orElseThrow();
        var overwritten = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Rebind.class,
                        sequence.steps().get(1)).value()).allocation().orElseThrow();
        var current = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Rebind.class,
                        sequence.steps().get(2)).value()).allocation().orElseThrow();
        SessionCompileResult.Success consumer = compile("overwritten-rebind-consumer.lyra",
                "let box :Box = :Box[]", producer.stagedSnapshot());
        TypedExpression construction = consumer.typedGraph().expressions().stream()
                .filter(expression -> expression.kind() == TypedExpressionKind.CONSTRUCTION)
                .findFirst().orElseThrow();
        var context = consumer.typedGraph().flowSiteId(construction);

        assertFalse(certificate(producer).certifiesDerivedAggregate(
                derivedArrayFact(initial, context, construction.span()),
                context, construction, Set.of()));
        assertFalse(certificate(producer).certifiesDerivedAggregate(
                derivedArrayFact(overwritten, context, construction.span()),
                context, construction, Set.of()),
                "an intermediate rebind value overwritten before the result must not certify");
        assertTrue(certificate(producer).certifiesDerivedAggregate(
                derivedArrayFact(current, context, construction.span()),
                context, construction, Set.of()));
        assertEquals(List.of(derivedCrossModuleArrayFact(
                        current, context, construction.span())),
                certificate(consumer).boundaryState().objects().values().stream()
                        .filter(object -> object.schema().type().id().name().equals("Box"))
                        .findFirst().orElseThrow().fields().get(0).only()
                        .aggregateIdentities(),
                "only the final returned rebind value inhabits the consumer field");
    }

    @Test
    void derivedCertificationKeepsReachableRebindPositives() {
        // The value of the final returned binding still certifies after a
        // rebind chain resolves through the sequence bindings.
        SessionCompileResult.Success returned = compile("returned-rebind-producer.lyra", """
                class Box {
                    @pub values :Array<I32> = {
                        let @mut selected :Array<I32> = Array<I32>[1 2]
                        selected := Array<I32>[3 4]
                        selected
                    }
                }
                """, SessionSnapshot.empty());
        var returnedSequence = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Sequence.class,
                transfer(nominal(certificate(returned), "Box"), "values"));
        var first = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Declare.class,
                        returnedSequence.steps().get(0)).initializer()).allocation().orElseThrow();
        var second = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Rebind.class,
                        returnedSequence.steps().get(1)).value()).allocation().orElseThrow();
        SessionCompileResult.Success returnedConsumer = compile("returned-rebind-consumer.lyra",
                "let box :Box = :Box[]", returned.stagedSnapshot());
        TypedExpression returnedConstruction = returnedConsumer.typedGraph().expressions().stream()
                .filter(expression -> expression.kind() == TypedExpressionKind.CONSTRUCTION)
                .findFirst().orElseThrow();
        var returnedContext = returnedConsumer.typedGraph().flowSiteId(returnedConstruction);
        assertFalse(certificate(returned).certifiesDerivedAggregate(
                derivedArrayFact(first, returnedContext, returnedConstruction.span()),
                returnedContext, returnedConstruction, Set.of()));
        assertTrue(certificate(returned).certifiesDerivedAggregate(
                derivedArrayFact(second, returnedContext, returnedConstruction.span()),
                returnedContext, returnedConstruction, Set.of()));

        // A rebind that writes into an object field is a genuine state write
        // and keeps certifying the written value.
        SessionCompileResult.Success fieldWrite = compile("field-write-rebind-producer.lyra", """
                class Box {
                    @pub @mut sink :Array<I32> = Array<I32>[0]
                    @pub values :Array<I32> = {
                        self:.sink := Array<I32>[1 2]
                        Array<I32>[3 4]
                    }
                }
                """, SessionSnapshot.empty());
        var writtenSequence = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Sequence.class,
                transfer(nominal(certificate(fieldWrite), "Box"), "values"));
        var writtenRebind = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Rebind.class,
                writtenSequence.steps().get(0));
        assertFalse(writtenRebind.target().route().isRoot(),
                "the field write target keeps its nominal member route");
        var written = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                writtenRebind.value()).allocation().orElseThrow();
        SessionCompileResult.Success fieldConsumer = compile("field-write-rebind-consumer.lyra",
                "let box :Box = :Box[]", fieldWrite.stagedSnapshot());
        TypedExpression fieldConstruction = fieldConsumer.typedGraph().expressions().stream()
                .filter(expression -> expression.kind() == TypedExpressionKind.CONSTRUCTION)
                .findFirst().orElseThrow();
        var fieldContext = fieldConsumer.typedGraph().flowSiteId(fieldConstruction);
        assertTrue(certificate(fieldWrite).certifiesDerivedAggregate(
                derivedArrayFact(written, fieldContext, fieldConstruction.span()),
                fieldContext, fieldConstruction, Set.of()));
        var sinkValue = certificate(fieldConsumer).boundaryState().objects().values().stream()
                .filter(object -> object.schema().type().id().name().equals("Box"))
                .findFirst().orElseThrow().fields().get(0).only();
        assertEquals(List.of(derivedCrossModuleArrayFact(
                        written, fieldContext, fieldConstruction.span())),
                sinkValue.aggregateIdentities(),
                "the written array lands in the consumer sink field");
    }

    @Test
    void derivedObjectCertificationRequiresReturnOrWriteDestinations() {
        // A nominal object argument the callee neither returns nor writes
        // must not mint a certified derived object identity.
        SessionCompileResult.Success ignored = compile("ignored-object-argument-producer.lyra", """
                class Inner { @pub value :I32 = 5 }
                let pick :Fn<Inner;Inner> = (=> |ignored| :Inner[])
                class Box {
                    @pub nested :Inner = ::pick[:Inner[]]
                }
                """, SessionSnapshot.empty());
        SessionCompileResult.Success ignoredConsumer = compile("ignored-object-argument-consumer.lyra",
                "let box :Box = :Box[]", ignored.stagedSnapshot());
        assertFalse(derivedArgumentObjectCertified(ignored, ignoredConsumer, "nested"),
                "an object argument the summary ignores must not certify");

        // Returned and written arguments keep certifying.
        SessionCompileResult.Success returned = compile("returned-object-argument-producer.lyra", """
                class Inner { @pub value :I32 = 5 }
                let keep :Fn<Inner;Inner> = (=> |kept| kept)
                class Box {
                    @pub nested :Inner = ::keep[:Inner[]]
                }
                """, SessionSnapshot.empty());
        SessionCompileResult.Success returnedConsumer = compile("returned-object-argument-consumer.lyra",
                "let box :Box = :Box[]", returned.stagedSnapshot());
        assertTrue(derivedArgumentObjectCertified(returned, returnedConsumer, "nested"),
                "an object argument the summary returns must certify");

        SessionCompileResult.Success written = compile("written-object-argument-producer.lyra", """
                class Inner { @pub value :I32 = 5 }
                let @mut @nil sink :Inner = #NIL
                let store :Fn<Inner;Unit> = (=> |kept| { sink := kept })
                class Box {
                    @pub nested :Inner = { ::store[:Inner[]] :Inner[] }
                }
                """, SessionSnapshot.empty());
        SessionCompileResult.Success writtenConsumer = compile("written-object-argument-consumer.lyra",
                "let box :Box = :Box[]", written.stagedSnapshot());
        assertTrue(derivedArgumentObjectCertified(written, writtenConsumer, "nested"),
                "an object argument the summary writes must certify");
    }

    private static boolean derivedArgumentObjectCertified(
            SessionCompileResult.Success producer, SessionCompileResult.Success consumer,
            String memberName) {
        var certificate = certificate(producer);
        var box = certificate.retainedNominals().values().stream()
                .filter(value -> value.name().equals("Box")).findFirst().orElseThrow();
        var transfer = transfer(box, memberName);
        SessionFlowCertificate.RetainedInitializerTransfer.Call call;
        if (transfer instanceof SessionFlowCertificate.RetainedInitializerTransfer.Call direct) {
            call = direct;
        } else {
            call = assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Call.class,
                    assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Sequence.class,
                            transfer).steps().get(0));
        }
        var argument = assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Construct.class,
                call.arguments().getFirst());
        var site = argument.site();
        TypedExpression construction = consumer.typedGraph().expressions().stream()
                .filter(expression -> expression.kind() == TypedExpressionKind.CONSTRUCTION)
                .findFirst().orElseThrow();
        FlowSiteId context = consumer.typedGraph().flowSiteId(construction);
        var derivedSite = RetainedAllocationDerivation.objectSite(context, site.site());
        var derivedAllocation = RetainedAllocationDerivation.objectAllocation(context, site.site());
        NominalObjectFact fact = new NominalObjectFact(
                new io.mindspice.lyra.compiler.semantic.flow.NominalObjectIdentity(
                        site.moduleId(), derivedSite, site.nominalType()),
                ProjectionPath.root(), OwnershipWitness.local(
                        site.moduleId(), derivedAllocation, site.scopeId(), site.span())
                .withOriginSite(derivedSite).atUse(construction.span()));
        return certificate.certifiesDerivedObject(fact, context, construction, Set.of());
    }

    @Test
    void derivedCertificationFollowsOnlyCallArgumentsReturnedByTheSummary() {
        SessionCompileResult.Success producer = compile("call-result-allocation-producer.lyra", """
                let choose :Fn<Array<I32>,Array<I32>;Array<I32>> =
                    (=> |selected discarded| selected)
                class Box {
                    @pub values :Array<I32> =
                        ::choose[Array<I32>[1] Array<I32>[2]]
                }
                """, SessionSnapshot.empty());
        var call = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Call.class,
                transfer(nominal(certificate(producer), "Box"), "values"));
        var selectedArray = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                call.arguments().getFirst()).allocation().orElseThrow();
        var discardedArray = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                call.arguments().get(1)).allocation().orElseThrow();
        SessionCompileResult.Success consumer = compile("call-result-allocation-consumer.lyra",
                "let box :Box = :Box[]", producer.stagedSnapshot());
        TypedExpression construction = consumer.typedGraph().expressions().stream()
                .filter(expression -> expression.kind() == TypedExpressionKind.CONSTRUCTION)
                .findFirst().orElseThrow();
        var context = consumer.typedGraph().flowSiteId(construction);

        assertTrue(certificate(producer).certifiesDerivedAggregate(
                derivedArrayFact(selectedArray, context, construction.span()),
                context, construction, Set.of()));
        assertFalse(certificate(producer).certifiesDerivedAggregate(
                derivedArrayFact(discardedArray, context, construction.span()),
                context, construction, Set.of()));
    }

    @Test
    void derivedCertificationFollowsOnlyConstructorArgumentsWrittenToState() {
        SessionCompileResult.Success producer = compile("constructor-argument-allocation-producer.lyra", """
                class Box {
                    @pub values :Array<I32>
                    Box = (=> |selected :Array<I32> discarded :Array<I32>| {
                        self:.values := selected
                    })
                }
                class Outer {
                    @pub box :Box =
                        :Box[Array<I32>[1] Array<I32>[2]]
                }
                """, SessionSnapshot.empty());
        var construction = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Construct.class,
                transfer(nominal(certificate(producer), "Outer"), "box"));
        var selectedArray = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                construction.arguments().getFirst()).allocation().orElseThrow();
        var discardedArray = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                construction.arguments().get(1)).allocation().orElseThrow();
        SessionCompileResult.Success consumer = compile(
                "constructor-argument-allocation-consumer.lyra",
                "let outer :Outer = :Outer[]", producer.stagedSnapshot());
        TypedExpression outerConstruction = consumer.typedGraph().expressions().stream()
                .filter(expression -> expression.kind() == TypedExpressionKind.CONSTRUCTION)
                .findFirst().orElseThrow();
        var context = consumer.typedGraph().flowSiteId(outerConstruction);

        assertTrue(certificate(producer).certifiesDerivedAggregate(
                derivedArrayFact(selectedArray, context, outerConstruction.span()),
                context, outerConstruction, Set.of()));
        assertFalse(certificate(producer).certifiesDerivedAggregate(
                derivedArrayFact(discardedArray, context, outerConstruction.span()),
                context, outerConstruction, Set.of()));
    }

    @Test
    void derivedCertificationRejectsStaticallyUnselectedAlternativeAllocations() {
        SessionCompileResult.Success producer = compile("unselected-allocation-producer.lyra", """
                class Box {
                    @pub values :Array<I32> =
                        (#T -> Array<I32>[3] : Array<I32>[4])
                }
                """, SessionSnapshot.empty());
        SessionFlowCertificate.RetainedNominal box = nominal(certificate(producer), "Box");
        var alternative = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Alternative.class,
                transfer(box, "values"));
        var selectedArray = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                alternative.branches().getFirst().result().orElseThrow().transfer())
                .allocation().orElseThrow();
        var unselectedArray = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                alternative.branches().get(1).result().orElseThrow().transfer())
                .allocation().orElseThrow();
        SessionCompileResult.Success consumer = compile("unselected-allocation-consumer.lyra",
                "let box :Box = :Box[]", producer.stagedSnapshot());
        TypedExpression construction = consumer.typedGraph().expressions().stream()
                .filter(expression -> expression.kind() == TypedExpressionKind.CONSTRUCTION)
                .findFirst().orElseThrow();
        var context = consumer.typedGraph().flowSiteId(construction);

        assertTrue(certificate(producer).certifiesDerivedAggregate(
                derivedArrayFact(selectedArray, context, construction.span()),
                context, construction, Set.of()));
        assertFalse(certificate(producer).certifiesDerivedAggregate(
                derivedArrayFact(unselectedArray, context, construction.span()),
                context, construction, Set.of()));
    }

    @Test
    void retainedCallableCertificationRejectsUnderivableRoutes() {
        var producer = compile("callable-route-producer.lyra", """
                let selected :Fn<;I32> = (=> || 3I32)
                class Holder {
                    @pub value :Tuple<Fn<;I32>> = Tuple[selected]
                    @pub array :Array<Fn<;I32>> = Array<Fn<;I32>>[selected]
                }
                """, SessionSnapshot.empty());
        var certificate = certificate(producer);
        var callable = certificate.valueFor(producer.stagedSnapshot().bindings()
                .get("selected").declarationId()).orElseThrow().only().callableFlows().getFirst();
        assertTrue(certificate.certifiesCallable(callable));
        var forged = callable.prefixedBy(ProjectionPath.tupleMember(99));
        assertFalse(certificate.certifiesCallable(forged));
        assertFalse(certificate.certifiesCallableTransfer(forged));
        assertTrue(certificate.certifiesCallableTransfer(callable.prefixedBy(ProjectionPath.tupleMember(0))));
        assertTrue(certificate.certifiesCallableTransfer(callable.prefixedBy(ProjectionPath.arrayElement(0))));
        assertFalse(certificate.certifiesCallableTransfer(callable.prefixedBy(ProjectionPath.arrayElement(99))));
        assertFalse(certificate.certifiesCallableTransfer(callable.prefixedBy(ProjectionPath.unknownArrayElement())));
    }

    @Test
    void retainedCallableRoutesDoNotMixArgumentsOfSeparateCalls() {
        var producer = compile("callable-argument-routes.lyra", """
                let first :Fn<;I32> = (=> || 1I32)
                let second :Fn<;I32> = (=> || 2I32)
                let identity :Fn<Fn<;I32>;Fn<;I32>> = (=> |value| value)
                class Holder {
                    @pub root :Fn<;I32> = ::identity[first]
                    @pub tuple :Tuple<Fn<;I32>> = Tuple[::identity[second]]
                }
                """, SessionSnapshot.empty());
        var certificate = certificate(producer);
        var first = certificate.valueFor(producer.stagedSnapshot().bindings().get("first").declarationId())
                .orElseThrow().only().callableFlows().getFirst();
        var second = certificate.valueFor(producer.stagedSnapshot().bindings().get("second").declarationId())
                .orElseThrow().only().callableFlows().getFirst();
        assertFalse(certificate.certifiesCallableTransfer(first.prefixedBy(ProjectionPath.tupleMember(0))));
        assertTrue(certificate.certifiesCallableTransfer(second.prefixedBy(ProjectionPath.tupleMember(0))));
    }

    @Test
    void retainedCallableReprojectionUsesConsumerSourceRoutes() {
        var producer = compile("callable-reprojection-producer.lyra", """
                let selected :Fn<;I32> = (=> || 3I32)
                class Holder {
                    @pub value :Tuple<Fn<;I32>> = Tuple[selected]
                    @pub array :Array<Fn<;I32>> = Array<Fn<;I32>>[selected]
                }
                """, SessionSnapshot.empty());
        compile("callable-route-consumer.lyra", """
                let holder :Holder = :Holder[]
                let projected :Fn<;I32> = holder:.value:.0
                let wrapped :Tuple<I32,Tuple<Fn<;I32>>> = Tuple[1I32 Tuple[projected]]
                """, producer.stagedSnapshot());
    }

    @Test
    void retainedCallableFactoryAllocationCallsKeepDistinctFiniteCallSites() {
        SessionCompileResult.Success producer = compile("allocator-proof.lyra", """
                let make :Fn<;Array<I32>> = (=> || Array<I32>[1I32])
                class Pair { values :Tuple<Array<I32>,Array<I32>> = Tuple[::make[], ::make[]] }
                """, SessionSnapshot.empty());
        var tuple = assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                transfer(nominal(certificate(producer), "Pair"), "values"));
        assertEquals(2, tuple.elements().size());
        var first = assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Call.class,
                tuple.elements().getFirst());
        var second = assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Call.class,
                tuple.elements().get(1));
        assertEquals(first.target(), second.target());
        assertFalse(first == second, "the two source construction routes remain distinct proof nodes");
        assertFalse(first.site().equals(second.site()),
                "distinct producer call sites retain distinct finite invocation contexts");
        assertEquals(1, certificate(producer).allocationProvenances().size(),
                "both calls intentionally share one certified source allocation");
        assertEquals(first.function(), second.function(),
                "separate calls retain the same producer callable contract");
    }

    @Test
    void nestedRetainedCallsToOneAllocatorRemainDistinctByProducerCallPath() {
        SessionCompileResult.Success producer = compile("nested-allocator-producer.lyra", """
                let allocate :Fn<;Array<I32>> = (=> || Array<I32>[1I32])
                let makePair :Fn<;Tuple<Array<I32>,Array<I32>>> =
                    (=> || Tuple[::allocate[], ::allocate[]])
                class Pair { @pub values :Tuple<Array<I32>,Array<I32>> = ::makePair[] }
                """, SessionSnapshot.empty());
        SessionCompileResult.Success consumer = compile("nested-allocator-consumer.lyra",
                "let pair :Pair = :Pair[]", producer.stagedSnapshot());
        var pair = certificate(consumer).boundaryState().objects().values().stream()
                .filter(object -> object.schema().type().id().name().equals("Pair"))
                .findFirst().orElseThrow();
        Set<Object> identities = pair.fields().get(0).only().aggregateIdentities().stream()
                .map(AggregateIdentityFact::identity).collect(Collectors.toSet());
        assertEquals(2, identities.size(),
                "distinct finite producer call paths must not collapse one allocator identity");
    }

    @Test
    void retainedFreshAllocationsAreScopedByConstructionAndPreserveAliases() {
        SessionCompileResult.Success producer = compile("fresh-producer.lyra", """
                let shared :Array<I32> = Array<I32>[5I32]
                let sharedValue :Fn<;Array<I32>> = (=> || shared)
                class Fresh {
                    @pub values :Array<I32> = Array<I32>[1I32]
                    @pub alias :Array<I32> = self:.values
                }
                class Shared { @pub values :Array<I32> = ::sharedValue[] }
                """, SessionSnapshot.empty());
        SessionCompileResult.Success consumer = compile("fresh-consumer.lyra", """
                let first :Fresh = :Fresh[]
                let second :Fresh = :Fresh[]
                let sharedFirst :Shared = :Shared[]
                let sharedSecond :Shared = :Shared[]
                """, producer.stagedSnapshot());
        SessionFlowCertificate certificate = certificate(consumer);

        var freshObjects = certificate.boundaryState().objects().values().stream()
                .filter(object -> object.schema().type().id().name().equals("Fresh")).toList();
        assertEquals(2, freshObjects.size());
        Set<Object> freshArrays = freshObjects.stream()
                .map(object -> object.fields().get(0).only().aggregateIdentities().getFirst().identity())
                .collect(Collectors.toSet());
        assertEquals(2, freshArrays.size(), "separate constructions need separate abstract arrays");
        freshObjects.forEach(object -> assertEquals(
                object.fields().get(0).only().aggregateIdentities().getFirst().identity(),
                object.fields().get(1).only().aggregateIdentities().getFirst().identity(),
                "a self-field alias must retain the same derived identity"));

        var sharedObjects = certificate.boundaryState().objects().values().stream()
                .filter(object -> object.schema().type().id().name().equals("Shared")).toList();
        assertEquals(2, sharedObjects.size());
        assertEquals(1, sharedObjects.stream()
                .map(object -> object.fields().get(0).only().aggregateIdentities().getFirst().identity())
                .collect(Collectors.toSet()).size(),
                "a declaration-returned producer array is shared, not freshened");
    }

    @Test
    void retainedFreshAllocationsDifferAcrossGenerationsAndNestedConstructionSites() {
        SessionCompileResult.Success producer = compile("nested-fresh-producer.lyra", """
                class Inner { @pub values :Array<I32> = Array<I32>[1I32] }
                class Outer {
                    @pub left :Inner = :Inner[]
                    @pub right :Inner = :Inner[]
                }
                """, SessionSnapshot.empty());
        SessionCompileResult.Success second = compile(
                "nested-fresh-second.lyra", "let first :Outer = :Outer[]", producer.stagedSnapshot());
        Set<Object> secondArrays = allArrayIdentities(certificate(second));
        SessionCompileResult.Success third = compile(
                "nested-fresh-third.lyra", "let second :Outer = :Outer[]", second.stagedSnapshot());
        Set<Object> thirdArrays = allArrayIdentities(certificate(third));
        assertTrue(thirdArrays.size() > secondArrays.size(),
                "another generation must derive new construction-scoped arrays");

        var innerObjects = certificate(second).boundaryState().objects().entrySet().stream()
                .filter(entry -> entry.getValue().schema().type().id().name().equals("Inner")).toList();
        assertEquals(2, innerObjects.size(), "two nested construction nodes remain distinct");
        assertEquals(2, innerObjects.stream().map(Map.Entry::getKey).collect(Collectors.toSet()).size());
        assertEquals(2, innerObjects.stream()
                .map(entry -> entry.getValue().fields().get(0).only()
                        .aggregateIdentities().getFirst().identity())
                .collect(Collectors.toSet()).size(),
                "nested defaults derive from their distinct nested object contexts");
    }

    @Test
    void retainedProjectedFreshValuesAreRebasedAndCertifiedAtRoot() {
        SessionCompileResult.Success producer = compile("projected-producer.lyra", """
                class Inner { value :I32 = 1I32 }
                class Box {
                    values :Array<I32> = Tuple[Array<I32>[1I32]]:.0
                    nested :Inner = Tuple[:Inner[]]:.0
                }
                """, SessionSnapshot.empty());
        SessionCompileResult.Success consumer = compile(
                "projected-consumer.lyra", "let box :Box = :Box[]", producer.stagedSnapshot());
        var box = certificate(consumer).boundaryState().objects().entrySet().stream()
                .filter(entry -> entry.getValue().schema().type().id().name().equals("Box"))
                .findFirst().orElseThrow().getValue();
        assertTrue(box.fields().get(0).only().aggregateIdentities().stream()
                .allMatch(fact -> fact.route().isRoot()));
        assertTrue(box.fields().get(1).only().objects().stream()
                .allMatch(fact -> fact.route().isRoot()));
    }

    @Test
    void derivedCertificationLegacyOverloadFailsClosedWithoutAnExactConsumerTarget() {
        SessionCompileResult.Success producer = compile("exact-target-producer.lyra", """
                class Left { values :Array<I32> = Array<I32>[1I32] }
                """, SessionSnapshot.empty());
        SessionCompileResult.Success consumer = compile("exact-target-consumer.lyra",
                "let leftValue :Left = :Left[]", producer.stagedSnapshot());
        TypedExpression left = consumer.typedGraph().expressions().stream()
                .filter(expression -> expression.kind() == TypedExpressionKind.CONSTRUCTION)
                .findFirst().orElseThrow();
        AggregateIdentityFact leftFact = certificate(consumer).boundaryState().objects().values().stream()
                .filter(object -> object.schema().type().id().name().equals("Left"))
                .flatMap(object -> object.fields().values().stream())
                .flatMap(values -> values.only().aggregateIdentities().stream())
                .findFirst().orElseThrow();
        assertFalse(certificate(producer).certifiesDerivedAggregate(
                leftFact, consumer.typedGraph().flowSiteId(left), left.span()),
                "certification without an exact consumer target must fail closed");
    }

    @Test
    void currentGenerationWrappersCarryRetainedAllocationContextsThroughAllCallableRoutes() {
        SessionCompileResult.Success producer = compile("wrapper-context-producer.lyra", """
                let make :Fn<;Array<I32>> = (=> || Array<I32>[1I32])
                """, SessionSnapshot.empty());
        SessionCompileResult.Success consumer = compile("wrapper-context-consumer.lyra", """
                let direct :Fn<;Array<I32>> = (=> || ::make[])
                let invoke :Fn<Fn<;Array<I32>>;Array<I32>> = (=> |factory| (factory))
                let parameter :Fn<;Array<I32>> = (=> || ::invoke[make])
                let capture :Fn<Fn<;Array<I32>>;Fn<;Array<I32>>> =
                    (=> |factory| (=> || (factory)))
                let captured :Fn<;Array<I32>> = ::capture[make]
                class Outer {
                    directValue :Array<I32> = ::direct[]
                    parameterValue :Array<I32> = ::parameter[]
                    capturedValue :Array<I32> = ::captured[]
                }
                let outer :Outer = :Outer[]
                """, producer.stagedSnapshot());
        var outer = certificate(consumer).boundaryState().objects().values().stream()
                .filter(object -> object.schema().type().id().name().equals("Outer"))
                .findFirst().orElseThrow();
        assertEquals(3, outer.fields().values().stream()
                .flatMap(values -> values.only().aggregateIdentities().stream())
                .map(AggregateIdentityFact::identity).distinct().count());
    }

    @Test
    void capturedCurrentWrapperCarriesContextToRetainedObjectFactory() {
        SessionCompileResult.Success producer = compile("wrapper-object-producer.lyra", """
                class Inner { value :I32 = 1I32 }
                let make :Fn<;Inner> = (=> || :Inner[])
                """, SessionSnapshot.empty());
        SessionCompileResult.Success consumer = compile("wrapper-object-consumer.lyra", """
                let capture :Fn<Fn<;Inner>;Fn<;Inner>> =
                    (=> |factory| (=> || (factory)))
                let captured :Fn<;Inner> = ::capture[make]
                let inner :Inner = ::captured[]
                """, producer.stagedSnapshot());
        NominalObjectFact inner = certificate(consumer).boundaryState().bindings().values().stream()
                .flatMap(binding -> binding.alternatives().alternatives().stream())
                .flatMap(value -> value.objects().stream())
                .filter(fact -> fact.identity().type().id().name().equals("Inner"))
                .findFirst().orElseThrow();
        assertEquals("wrapper-object-producer.lyra",
                inner.identity().ownerModule().sourceId().value());
    }

    @Test
    void nestedReturnedCallableIsCertifiedOnlyThroughItsReachableSummaryChain() {
        SessionCompileResult.Success producer = compile("nested-callable-proof-1.lyra", """
                let make :Fn<;Fn<;I32>> = (=> || (=> || 14I32))
                """, SessionSnapshot.empty());
        Set<io.mindspice.lyra.compiler.identity.LambdaId> lambdas = producer.typedGraph()
                .lambdas().stream().map(value -> value.id()).collect(Collectors.toSet());
        assertEquals(2, lambdas.size());
        lambdas.forEach(lambda -> assertTrue(certificate(producer).certifiesLambda(lambda)));
        assertFalse(certificate(producer).certifiesLambda(
                new io.mindspice.lyra.compiler.identity.LambdaId(Long.MAX_VALUE)));

        compile("nested-callable-proof-2.lyra", """
                let wrapper :Fn<;Fn<;I32>> = (=> || ::make[])
                class Box { read :Fn<;I32> = ::wrapper[] }
                let box :Box = :Box[]
                """, producer.stagedSnapshot());
    }

    @Test
    void retainedRepeatedConstructionSiteUsesOneFiniteAbstractIdentity() {
        var context = new io.mindspice.lyra.compiler.identity.FlowSiteId(41);
        var producerSite = new io.mindspice.lyra.compiler.identity.FlowSiteId(17);
        Set<DeclarationId> repeated = java.util.stream.IntStream.range(0, 100)
                .mapToObj(ignored -> RetainedAllocationDerivation.arrayAllocation(context, producerSite))
                .collect(Collectors.toSet());
        assertEquals(1, repeated.size(),
                "dynamic repetition at one finite analysis site reuses one identity");
        assertFalse(repeated.contains(RetainedAllocationDerivation.arrayAllocation(
                new io.mindspice.lyra.compiler.identity.FlowSiteId(42), producerSite)));
    }

    @Test
    void retainedDerivedAllocationTagsCannotAliasOrdinarySourceOrSummaryAllocations() {
        var context = new io.mindspice.lyra.compiler.identity.FlowSiteId(41);
        var producerSite = new io.mindspice.lyra.compiler.identity.FlowSiteId(17);
        DeclarationId source = new DeclarationId(Long.MAX_VALUE - producerSite.ordinal());
        DeclarationId summary = new DeclarationId(Long.MAX_VALUE / 2L - producerSite.ordinal());
        Set<DeclarationId> retained = Set.of(
                RetainedAllocationDerivation.arrayAllocation(context, producerSite),
                RetainedAllocationDerivation.objectAllocation(context, producerSite),
                RetainedAllocationDerivation.sharedCell(context, new DeclarationId(9)));

        assertTrue(RetainedAllocationDerivation.isOrdinarySourceAllocation(
                producerSite, source));
        assertTrue(RetainedAllocationDerivation.isOrdinarySummaryAllocation(
                producerSite, summary));
        assertFalse(retained.contains(source));
        assertFalse(retained.contains(summary));
        assertEquals(3, retained.size());
        assertThrows(IllegalArgumentException.class, () ->
                RetainedAllocationDerivation.arrayAllocation(
                        RetainedAllocationDerivation.objectSite(context, producerSite),
                        RetainedAllocationDerivation.objectSite(context, producerSite)));
    }

    @Test
    void retainedCallableEffectsAreAttributedToTheConsumerConstructionSite() {
        SessionCompileResult.Success producer = compile("effect-producer.lyra", """
                import std->io
                let initialize :Fn<;I32> = (=> || { io->::println["retained"] 7 })
                class Box { value :I32 = ::initialize[] }
                """, SessionSnapshot.empty());

        SessionCompileResult.Success consumer = compile(
                "effect-consumer.lyra", "let box :Box = :Box[]", producer.stagedSnapshot());

        var effects = consumer.typedGraph().semanticFlowFacts().eagerEffectFacts();
        assertEquals(1, effects.size());
        var witness = effects.getFirst().witness();
        assertEquals(consumer.moduleGraph().rootModule(), witness.fromModule());
        assertEquals(SourceId.path("effect-consumer.lyra"),
                witness.sourcePath().getFirst().sourceId());
        assertEquals(SourceId.path("effect-producer.lyra"),
                witness.effectSpan().sourceId());
        assertEquals(witness.effectSpan(), witness.sourcePath().getLast());
        assertEquals(witness.effectSite().orElseThrow(),
                witness.sourceSitePath().getLast());
    }

    @Test
    void retainedConstructorSummaryCertifiesNestedObjectsAndTupleArrayWrites() {
        SessionCompileResult.Success producer = compile("constructor-summary-producer.lyra", """
                let record :Fn<I32;I32> = (=> |value| value)
                class Inner { @pub value :I32 = 5 }
                class Box {
                    @pub built :Inner
                    @pub @mut nested :Tuple<Array<I32>,I32> =
                        Tuple[Array<I32>[1 2] 3]
                    Box = (=> || {
                        self:.nested:.0[1] := ::record[9]
                        self:.built := :Inner[]
                    })
                }
                """, SessionSnapshot.empty());
        SessionFlowCertificate.RetainedNominal box = nominal(certificate(producer), "Box");
        assertTrue(box.constructorLambda().isPresent());

        SessionCompileResult.Success consumer = compile("constructor-summary-consumer.lyra",
                "let box :Box = :Box[]", producer.stagedSnapshot());
        TypedExpression boxConstruction = consumer.typedGraph().expressions().stream()
                .filter(expression -> expression.kind() == TypedExpressionKind.CONSTRUCTION)
                .findFirst().orElseThrow();
        var boxContext = consumer.typedGraph().flowSiteId(boxConstruction);
        var finalState = consumer.typedGraph().semanticFlowFacts()
                .finalState(consumer.moduleGraph().rootModule()).orElseThrow();
        var boxState = finalState.objects().entrySet().stream()
                .filter(entry -> entry.getKey().type().equals(box.nominal().schema().type()))
                .filter(entry -> entry.getKey().allocationSite().equals(boxContext))
                .map(Map.Entry::getValue).reduce((left, right) -> {
                    throw new AssertionError("consumer construction produced duplicate Box identities");
                }).orElseThrow();
        int builtField = java.util.stream.IntStream.range(
                        0, box.nominal().schema().members().size())
                .filter(index -> box.nominal().schema().members().get(index).name().equals("built"))
                .findFirst().orElseThrow();
        int nestedField = java.util.stream.IntStream.range(
                        0, box.nominal().schema().members().size())
                .filter(index -> box.nominal().schema().members().get(index).name().equals("nested"))
                .findFirst().orElseThrow();

        var nestedTransfer = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                transfer(box, "nested"));
        var nestedArray = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                nestedTransfer.elements().getFirst()).allocation().orElseThrow();
        AggregateIdentityFact expectedArray = derivedCrossModuleArrayFact(
                nestedArray, boxContext, boxConstruction.span()).prefixedBy(
                ProjectionPath.tupleMember(0));
        ValueAlternative nestedValue = boxState.fields().get(nestedField).only();
        assertEquals(nestedTransfer.type(), nestedValue.type());
        assertEquals(List.of(expectedArray), nestedValue.aggregateIdentities(),
                "only the exact producer array at tuple route .0 may inhabit Box.nested");

        SessionFlowCertificate certificate = certificate(producer);
        var constructorSummary = certificate.callableSummaries()
                .summary(box.constructorLambda().orElseThrow()).orElseThrow();
        var innerCall = constructorSummary.callReferences().stream()
                .map(certificate::retainedConstruction).flatMap(Optional::stream)
                .filter(construction -> construction.nominalType().id().name().equals("Inner"))
                .findFirst().orElseThrow();
        var call = innerCall.call();
        var invocation = RetainedAllocationDerivation.invocationContext(
                boxContext, call.id(), call.siteId().orElseThrow());
        var innerSite = RetainedAllocationDerivation.objectSite(invocation, innerCall.site());
        var innerAllocation = RetainedAllocationDerivation.objectAllocation(
                invocation, innerCall.site());
        NominalObjectFact expectedInner = new NominalObjectFact(
                new io.mindspice.lyra.compiler.semantic.flow.NominalObjectIdentity(
                        innerCall.moduleId(), innerSite, innerCall.nominalType()),
                ProjectionPath.root(), OwnershipWitness.local(
                        innerCall.moduleId(), innerAllocation, innerCall.scopeId(),
                        innerCall.call().span()).withOriginSite(innerSite)
                        .atUse(boxConstruction.span()));
        ValueAlternative builtValue = boxState.fields().get(builtField).only();
        assertEquals(innerCall.nominalType(), builtValue.type());
        assertEquals(List.of(expectedInner), builtValue.objects(),
                "only the exact constructor-summary Inner may inhabit Box.built");
    }

    @Test
    void issuesClosedTransfersForEveryInitializedClassAndStructMember() {
        SessionCompileResult.Success producer = producer();
        SessionFlowCertificate certificate = certificate(producer);
        SessionFlowCertificate.RetainedNominal defaults = nominal(certificate, "Defaults");
        SessionFlowCertificate.RetainedNominal data = nominal(certificate, "Data");

        assertCoverage(defaults);
        assertCoverage(data);
        assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Value.class,
                transfer(defaults, "literal"));
        assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Reference.class,
                transfer(defaults, "alias"));
        assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Lambda.class,
                transfer(defaults, "lambda"));
        assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Call.class,
                transfer(defaults, "called"));
        assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                transfer(data, "array"));
        assertTrue(memberInitializer(data, "required").isEmpty());
    }

    @Test
    void lambdaAndDirectCallTransfersKeepTypedGraphIdentities() {
        SessionCompileResult.Success producer = producer();
        SessionFlowCertificate.RetainedNominal defaults = nominal(certificate(producer), "Defaults");
        TypedDeclaration lambda = member(producer, defaults, "lambda");
        TypedDeclaration called = member(producer, defaults, "called");
        TypedExpression callExpression = called.initializer().orElseThrow();

        var lambdaTransfer = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Lambda.class,
                transfer(defaults, "lambda"));
        var callTransfer = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Call.class,
                transfer(defaults, "called"));
        assertEquals(lambda.initializerLambda().orElseThrow(), lambdaTransfer.lambda());
        assertEquals(callExpression.link().orElseThrow().declarationId().orElseThrow(),
                callTransfer.target());
        assertEquals(callExpression.children().size(), callTransfer.function().arity());
    }

    @Test
    void nilCertificationAcceptsOnlyExactProducerProvenance() {
        SessionCompileResult.Success producer = compile("nil-proof.lyra",
                "class Maybe { @nil value :I32 = #NIL }", SessionSnapshot.empty());
        SessionFlowCertificate certificate = certificate(producer);
        var value = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Value.class,
                transfer(nominal(certificate, "Maybe"), "value"));
        NilProvenance nil = value.value().alternatives().getFirst()
                .nilProvenance().getFirst();
        NilProvenance foreign = new NilProvenance(
                nil.sourceSite(), SourceSpan.at(SourceId.path("foreign.lyra"), 0),
                nil.route());

        assertTrue(certificate.certifiesNil(nil));
        assertFalse(certificate.certifiesNil(foreign));
    }

    @Test
    void arrayLiteralTransferKeepsProducerModuleAndFlowSiteIdentity() {
        SessionCompileResult.Success producer = producer();
        SessionFlowCertificate.RetainedNominal data = nominal(certificate(producer), "Data");
        TypedExpression initializer = member(producer, data, "array").initializer().orElseThrow();
        var value = assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                transfer(data, "array"));
        var allocation = value.allocation().orElseThrow();
        assertEquals(producer.moduleGraph().rootModule(), allocation.moduleId());
        assertEquals(producer.typedGraph().flowSiteId(initializer), allocation.site());
    }

    @Test
    void laterConstructionCarriesProducerInitializerTransfersForward() {
        SessionCompileResult.Success first = producer();
        String canonical = nominal(certificate(first), "Defaults").nominal().schema().type()
                .canonicalSpelling();
        List<Optional<SessionFlowCertificate.RetainedInitializerTransfer>> expected =
                certificate(first).retainedNominals().get(canonical).memberInitializers();
        SessionCompileResult.Success second = compile("second.lyra", "let value :Defaults = :Defaults[]",
                first.stagedSnapshot());
        SessionCompileResult.Success third = compile("third.lyra", "let value :Defaults = :Defaults[]",
                second.stagedSnapshot());

        assertEquals(expected, certificate(third).retainedNominals().get(canonical)
                .memberInitializers());
    }

    @Test
    void certificateCollectionsAndTransferRecordsAreDeeplyImmutable() {
        SessionCompileResult.Success producer = producer();
        SessionFlowCertificate certificate = certificate(producer);
        SessionFlowCertificate.RetainedNominal defaults = nominal(certificate, "Defaults");
        var call = assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Call.class,
                transfer(defaults, "called"));
        Map<String, SessionFlowCertificate.RetainedNominal> nominals = certificate.retainedNominals();
        List<Optional<SessionFlowCertificate.RetainedInitializerTransfer>> initializers =
                defaults.memberInitializers();
        var allocations = certificate.allocationProvenances();

        assertThrows(UnsupportedOperationException.class, nominals::clear);
        assertThrows(UnsupportedOperationException.class, () -> initializers.clear());
        assertThrows(UnsupportedOperationException.class, allocations::clear);
        allocations.forEach((site, value) ->
                assertEquals(value, certificate.allocationProvenance(site).orElseThrow()));
        assertThrows(UnsupportedOperationException.class,
                () -> call.arguments().add(call.arguments().getFirst()));

        List<Optional<SessionFlowCertificate.RetainedInitializerTransfer>> suppliedInitializers =
                new ArrayList<>(initializers);
        SessionFlowCertificate.RetainedNominal copiedNominal =
                new SessionFlowCertificate.RetainedNominal(defaults.name(), defaults.nominal(),
                        defaults.visibility(), defaults.constructorLambda(), suppliedInitializers);
        List<SessionFlowCertificate.RetainedInitializerTransfer> suppliedArguments =
                new ArrayList<>(call.arguments());
        List<Optional<io.mindspice.lyra.compiler.semantic.flow.WriteTarget>> suppliedTargets =
                new ArrayList<>(call.argumentTargets());
        var copiedCall = new SessionFlowCertificate.RetainedInitializerTransfer.Call(
                call.target(), call.function(), call.span(), call.site(),
                suppliedArguments, suppliedTargets);
        suppliedInitializers.clear();
        suppliedArguments.clear();
        suppliedTargets.clear();

        assertEquals(initializers, copiedNominal.memberInitializers());
        assertEquals(call.arguments(), copiedCall.arguments());
        assertEquals(call.argumentTargets(), copiedCall.argumentTargets());
        assertEquals(allocations, certificate.allocationProvenances());
    }

    @Test
    void closedProofRecordsRejectInvalidConstruction() {
        SessionCompileResult.Success producer = producer();
        SessionFlowCertificate certificate = certificate(producer);
        SessionFlowCertificate.RetainedNominal defaults = nominal(certificate, "Defaults");
        var provenance = certificate.allocationProvenances().values().stream().findFirst().orElseThrow();
        var call = assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Call.class,
                transfer(defaults, "called"));
        List<Optional<SessionFlowCertificate.RetainedInitializerTransfer>> absent =
                new ArrayList<>(defaults.memberInitializers());
        absent.set(0, Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.RetainedNominal(defaults.name(), defaults.nominal(),
                        defaults.visibility(), defaults.constructorLambda(), List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.RetainedNominal(defaults.name(), defaults.nominal(),
                        defaults.visibility(), defaults.constructorLambda(), absent));
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.RetainedNominal("Other", defaults.nominal(),
                        defaults.visibility(), defaults.constructorLambda(),
                        defaults.memberInitializers()));
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.AllocationProvenance(provenance.moduleId(),
                        provenance.scopeId(), SourceSpan.at(SourceId.path("foreign.lyra"), 0),
                        provenance.originSite(), provenance.arrayType(), provenance.allocation()));
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.AllocationProvenance(provenance.moduleId(),
                        provenance.scopeId(), provenance.sourceSpan(), provenance.originSite(),
                        provenance.arrayType(), new DeclarationId(provenance.allocation().ordinal() + 1)));
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.RetainedInitializerTransfer.Call(call.target(),
                        call.function(), call.span(), call.site(), List.of(), call.argumentTargets()));
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.RetainedInitializerTransfer.Value(
                        PrimitiveType.I32, ValueAlternatives.empty()));
    }

    @Test
    void closedTransferRecordsRejectForgedChildTypesAndSchemas() {
        SessionCompileResult.Success compiled = compile("forged-transfer.lyra", """
                class Inner { value :I32 = 1I32 }
                class Holder {
                    value :I32 = 1I32
                    nested :Inner = :Inner[]
                    looped :Unit = while[|| #F || ()]
                }
                """, SessionSnapshot.empty());
        SessionFlowCertificate.RetainedNominal holder = nominal(certificate(compiled), "Holder");
        var i32 = new SessionFlowCertificate.RetainedInitializerTransfer.Value(
                PrimitiveType.I32, ValueAlternatives.singleton(ValueAlternative.scalar(PrimitiveType.I32)));
        var i64 = new SessionFlowCertificate.RetainedInitializerTransfer.Value(
                PrimitiveType.I64, ValueAlternatives.singleton(ValueAlternative.scalar(PrimitiveType.I64)));
        var bool = new SessionFlowCertificate.RetainedInitializerTransfer.Value(
                PrimitiveType.BOOL, ValueAlternatives.singleton(ValueAlternative.scalar(PrimitiveType.BOOL)));
        var string = new SessionFlowCertificate.RetainedInitializerTransfer.Value(
                PrimitiveType.STRING, ValueAlternatives.singleton(ValueAlternative.scalar(PrimitiveType.STRING)));
        var narrowing = new SessionFlowCertificate.RetainedInitializerTransfer.Apply(
                SessionFlowCertificate.RetainedInitializerTransfer.ApplyKind.NARROWING,
                PrimitiveType.I32, List.of(i64));
        assertEquals(PrimitiveType.I32, narrowing.type(),
                "the closed transfer inventory retains semantic narrowing when one is issued");
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.RetainedInitializerTransfer.Alternative.Step(
                        PrimitiveType.I32, i64));
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.RetainedInitializerTransfer.Apply(
                        SessionFlowCertificate.RetainedInitializerTransfer.ApplyKind.OPERATOR,
                        PrimitiveType.I32, Optional.of("not-an-operator"), List.of(i32)));
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.RetainedInitializerTransfer.Apply(
                        SessionFlowCertificate.RetainedInitializerTransfer.ApplyKind.OPERATOR,
                        PrimitiveType.BOOL, Optional.of("+"), List.of(i32, i32)));

        var boolStep = new SessionFlowCertificate.RetainedInitializerTransfer.Alternative.Step(
                PrimitiveType.BOOL, bool);
        var i32Step = new SessionFlowCertificate.RetainedInitializerTransfer.Alternative.Step(
                PrimitiveType.I32, i32);
        var i64Step = new SessionFlowCertificate.RetainedInitializerTransfer.Alternative.Step(
                PrimitiveType.I64, i64);
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.RetainedInitializerTransfer.Alternative(
                        SessionFlowCertificate.RetainedInitializerTransfer.AlternativeKind.CONDITIONAL,
                        PrimitiveType.I64, List.of(boolStep), List.of(
                        new SessionFlowCertificate.RetainedInitializerTransfer.Alternative.Branch(
                                Optional.empty(), Optional.empty(), false, Optional.of(i32Step)),
                        new SessionFlowCertificate.RetainedInitializerTransfer.Alternative.Branch(
                                Optional.empty(), Optional.empty(), false, Optional.of(i64Step))),
                        Optional.empty()));
        var stringStep = new SessionFlowCertificate.RetainedInitializerTransfer.Alternative.Step(
                PrimitiveType.STRING, string);
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.RetainedInitializerTransfer.Alternative(
                        SessionFlowCertificate.RetainedInitializerTransfer.AlternativeKind.MATCH,
                        PrimitiveType.I32, List.of(i32Step), List.of(
                        new SessionFlowCertificate.RetainedInitializerTransfer.Alternative.Branch(
                                Optional.of(stringStep), Optional.empty(), false, Optional.of(i32Step)),
                        new SessionFlowCertificate.RetainedInitializerTransfer.Alternative.Branch(
                                Optional.empty(), Optional.empty(), true, Optional.of(i32Step))),
                        Optional.empty()));
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.RetainedInitializerTransfer.Declare(
                        PrimitiveType.UNIT, new DeclarationId(10),
                        io.mindspice.lyra.compiler.types.BindingContract.mutable(PrimitiveType.I64),
                        Optional.empty(), i32));
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.RetainedInitializerTransfer.Declare(
                        PrimitiveType.UNIT, new DeclarationId(10),
                        io.mindspice.lyra.compiler.types.BindingContract.mutable(PrimitiveType.I64),
                        Optional.of(new DeclarationId(10)), i64));
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.RetainedInitializerTransfer.Rebind(
                        PrimitiveType.UNIT,
                        new io.mindspice.lyra.compiler.semantic.flow.WriteTarget(
                                new DeclarationId(11), ProjectionPath.root()),
                        PrimitiveType.I64, PrimitiveType.I64, Optional.empty(), i32));

        List<Optional<SessionFlowCertificate.RetainedInitializerTransfer>> wrongMembers =
                new ArrayList<>(holder.memberInitializers());
        wrongMembers.set(0, Optional.of(i64));
        assertThrows(IllegalArgumentException.class, () -> new SessionFlowCertificate.RetainedNominal(
                holder.name(), holder.nominal(), holder.visibility(), holder.constructorLambda(), wrongMembers));

        var nested = assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Construct.class,
                transfer(holder, "nested"));
        var site = nested.site();
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.RetainedInitializerTransfer.ConstructionSite(
                        site.nominalDeclaration(), site.schema(), site.moduleId(), site.scopeId(), site.span(),
                        site.site(), site.allocation(), List.of(PrimitiveType.I32), site.argumentTargets()));
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.RetainedInitializerTransfer.ConstructionSite(
                        site.nominalDeclaration(), site.schema(), site.moduleId(), site.scopeId(), site.span(),
                        RetainedAllocationDerivation.objectSite(
                                new io.mindspice.lyra.compiler.identity.FlowSiteId(4), site.site()),
                        site.allocation(), site.argumentTypes(), site.argumentTargets()));

        var loop = assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Loop.class,
                transfer(holder, "looped"));
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.RetainedInitializerTransfer.Loop(
                        loop.kind(), loop.type(), loop.span(), loop.site(), loop.input(), loop.action(),
                        Optional.of(FunctionType.of(List.of(), PrimitiveType.I32)), loop.actionFunction()));
    }

    @Test
    void derivedAllocationCertificationRejectsAnotherConsumerContext() {
        SessionCompileResult.Success producer = compile(
                "derived-proof-producer.lyra",
                "class Inner { value :I32 = 1I32 } class Box { values :Array<I32> = Array<I32>[1I32] inner :Inner = :Inner[] }",
                SessionSnapshot.empty());
        SessionCompileResult.Success consumer = compile(
                "derived-proof-consumer.lyra", "let box :Box = :Box[]", producer.stagedSnapshot());
        TypedExpression construction = consumer.typedGraph().expressions().stream()
                .filter(expression -> expression.kind() == TypedExpressionKind.CONSTRUCTION)
                .findFirst().orElseThrow();
        AggregateIdentityFact aggregate = certificate(consumer).boundaryState().objects().values().stream()
                .filter(object -> object.schema().type().id().name().equals("Box"))
                .flatMap(object -> object.fields().values().stream())
                .flatMap(values -> values.alternatives().stream())
                .flatMap(value -> value.aggregateIdentities().stream())
                .findFirst().orElseThrow();
        assertTrue(certificate(producer).certifiesDerivedAggregate(aggregate,
                consumer.typedGraph().flowSiteId(construction), construction, Set.of()));
        assertFalse(certificate(producer).certifiesDerivedAggregate(aggregate,
                new io.mindspice.lyra.compiler.identity.FlowSiteId(
                        consumer.typedGraph().flowSiteId(construction).ordinal() + 1),
                construction, Set.of()));
        assertFalse(certificate(producer).certifiesDerivedAggregate(
                aggregate, consumer.typedGraph().flowSiteId(construction), construction.span()),
                "context/span-only certification must fail closed without the consumer expression");

        NominalObjectFact object = certificate(consumer).boundaryState().objects().values().stream()
                .filter(state -> state.schema().type().id().name().equals("Box"))
                .flatMap(state -> state.fields().values().stream())
                .flatMap(values -> values.alternatives().stream())
                .flatMap(value -> value.objects().stream())
                .filter(fact -> fact.identity().type().id().name().equals("Inner"))
                .findFirst().orElseThrow();
        assertTrue(certificate(producer).certifiesDerivedObject(object,
                consumer.typedGraph().flowSiteId(construction), construction, Set.of()));
        assertFalse(certificate(producer).certifiesDerivedObject(object,
                new io.mindspice.lyra.compiler.identity.FlowSiteId(
                        consumer.typedGraph().flowSiteId(construction).ordinal() + 1),
                construction, Set.of()));
    }

    @Test
    void certifiedProofPredicatesRejectForgedWitnessesAndUseSites() {
        SessionCompileResult.Success producer = compile("holder.lyra", """
                class Holder { @pub values :Array<I32> = Array<I32>[1 2] }
                let @mut holder :Holder = :Holder[]
                """, SessionSnapshot.empty());
        SessionFlowCertificate certificate = certificate(producer);
        List<ValueAlternative> values = new ArrayList<>();
        certificate.boundaryState().bindings().values().forEach(value ->
                values.addAll(value.alternatives().alternatives()));
        certificate.boundaryState().objects().values().forEach(object ->
                object.fields().values().forEach(field ->
                        values.addAll(field.alternatives())));
        NominalObjectFact object = values.stream().flatMap(value -> value.objects().stream())
                .findFirst().orElseThrow();
        AggregateIdentityFact aggregate = values.stream()
                .flatMap(value -> value.aggregateIdentities().stream())
                .findFirst().orElseThrow();

        assertTrue(certificate.certifiesObject(object));
        assertTrue(certificate.certifiesObjectUse(object));
        OwnershipWitness objectWitness = object.ownership();
        NominalObjectFact forgedObject = new NominalObjectFact(object.identity(), object.route(),
                OwnershipWitness.local(objectWitness.ownerModule(),
                                new DeclarationId(objectWitness.originDeclaration().ordinal() + 1),
                                objectWitness.scopeId(), objectWitness.sourceSpan())
                        .withOriginSite(objectWitness.originSite().orElseThrow()));
        assertFalse(certificate.certifiesObject(forgedObject));
        assertFalse(certificate.certifiesObject(new NominalObjectFact(object.identity(),
                io.mindspice.lyra.compiler.semantic.flow.ProjectionPath.tupleMember(1),
                object.ownership())), "a forged object route must not be certified");
        NominalObjectFact foreignObjectUse = new NominalObjectFact(
                object.identity(), object.route(), object.ownership().atUse(
                SourceSpan.at(SourceId.path("foreign.lyra"), 0)));
        assertTrue(certificate.certifiesObject(foreignObjectUse));
        assertFalse(certificate.certifiesObjectUse(foreignObjectUse));

        assertTrue(certificate.certifiesAggregate(aggregate));
        assertTrue(certificate.certifiesAggregateUse(aggregate));
        AggregateIdentityFact foreignUse = new AggregateIdentityFact(aggregate.identity(),
                aggregate.route(), aggregate.witness().atUse(
                        SourceSpan.at(SourceId.path("foreign.lyra"), 0)));
        assertTrue(certificate.certifiesAggregate(foreignUse));
        assertFalse(certificate.certifiesAggregateUse(foreignUse));

        // A use span that names a real certified generation source but is not a
        // certified use site must not be accepted either.
        SourceId certifiedSource = aggregate.witness().sourceSpan().sourceId();
        assertTrue(certificate.containsSourceId(certifiedSource));
        AggregateIdentityFact inventedUse = new AggregateIdentityFact(aggregate.identity(),
                aggregate.route(), aggregate.witness().atUse(
                        SourceSpan.at(certifiedSource, 999)));
        assertTrue(certificate.certifiesAggregate(inventedUse));
        assertFalse(certificate.certifiesAggregateUse(inventedUse));
    }

    /**
     * Replaces the obsolete negative test whose input became legal.  The
     * contract it guarded is still pinned: legal initializer compositions are
     * first-class across generations, and the retained-initializer preflight
     * remains a no-false-positive fail-closed guard rather than a rejection of
     * valid source.
     */
    @Test
    void supportedInitializerCompositionsCompileAndTheFailClosedGuardStaysSilent() {
        String[] legalForms = {
            "(+ 1 2)",
            "{ let a :I32 = 1 a }",
            "((> 1 0) -> 1 : 2)",
            "Array<I32>[1 2][0]",
            "Tuple[1 (=> || 2)]"
        };
        for (int index = 0; index < legalForms.length; index++) {
            String label = "legal-" + index + ".lyra";
            SessionCompileResult.Success producer = compile(label,
                    "class C" + index + " { @pub x :"
                            + (index == 4 ? "Tuple<I32,Fn<;I32>>" : "I32") + " = "
                            + legalForms[index] + " }", SessionSnapshot.empty());
            assertTrue(SessionFlowCertificate.retainedInitializerDiagnostic(
                            producer.typedGraph()).isEmpty(),
                    "retained initializer guard rejected a legal form: " + legalForms[index]);
        }
        SessionCompileResult.Success producer = compile("supported-guard.lyra", """
                class Guard { @pub x :I32 = (+ 1 2) }
                """, SessionSnapshot.empty());
        assertTrue(SessionFlowCertificate.retainedInitializerDiagnostic(
                producer.typedGraph()).isEmpty());
        SessionCompileResult.Success consumer = compile("supported-guard-2.lyra",
                "let guard :Guard = :Guard[]", producer.stagedSnapshot());
        assertInstanceOf(SessionCompileResult.Success.class, consumer);
    }

    /**
     * The phase-4 crash shape: a {@code @nil}-element array indexed inside a
     * retained member initializer.  The projection result type and the routed
     * element type both carry the nil qualifier; the certificate must issue
     * and replay that projection instead of rejecting valid source with an
     * INCONSISTENT_SUMMARY compiler crash.  The composite base keeps both
     * elements: the exact {@code #NIL} provenance and the widened {@code 1I32}
     * literal, and the constructed member selects the non-nil element.
     */
    @Test
    void nilableElementArrayIndexInitializerTransfersAndConstructsAcrossGenerations() {
        SessionCompileResult.Success producer = compile("nilable-index-producer.lyra", """
                class C { @pub x :@nil I32 = Array<@nil I32>[#NIL 1I32][1I32] }
                """, SessionSnapshot.empty());
        SessionFlowCertificate.RetainedNominal retained = nominal(certificate(producer), "C");
        var project = assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Project.class,
                transfer(retained, "x"));
        assertEquals(PrimitiveType.I32.nilable(), project.type());
        assertEquals(ProjectionPath.of(ProjectionStep.arrayElement(1)), project.route());
        assertTrue(project.index().isPresent());
        var base = assertInstanceOf(SessionFlowCertificate.RetainedInitializerTransfer.Composite.class,
                project.base());
        assertEquals(2, base.elements().size());
        var nilElement = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Value.class,
                base.elements().getFirst());
        assertEquals(PrimitiveType.I32.nilable(), nilElement.type());
        assertEquals(1, nilElement.value().alternatives().size());
        assertFalse(nilElement.value().alternatives().getFirst().nilProvenance().isEmpty(),
                "the #NIL element must keep its exact nil provenance");
        var oneElement = assertInstanceOf(
                SessionFlowCertificate.RetainedInitializerTransfer.Apply.class,
                base.elements().get(1));
        assertEquals(SessionFlowCertificate.RetainedInitializerTransfer.ApplyKind.CONVERSION,
                oneElement.kind());
        assertEquals(1, oneElement.operands().size());
        assertEquals(PrimitiveType.I32, oneElement.operands().getFirst().type());

        SessionCompileResult.Success consumer = compile("nilable-index-consumer.lyra",
                "let value :C = :C[]", producer.stagedSnapshot());
        ValueAlternative field = certificate(consumer).boundaryState().objects().values().stream()
                .filter(object -> object.schema().type().id().name().equals("C"))
                .flatMap(object -> object.fields().values().stream())
                .flatMap(values -> values.alternatives().stream())
                .findFirst().orElseThrow();
        assertEquals(PrimitiveType.I32.nilable(), field.type());
        assertTrue(field.nilProvenance().isEmpty(),
                "the selected 1I32 element is non-nil, so the member value carries no nil provenance");
    }

    /**
     * A retained composite member that stores a {@code #NIL} element must keep
     * certifying that element after construction prefixes it with the element
     * route.  Before the prefix-aware nil walk this valid source crashed the
     * consumer generation with a flow-site/span mismatch.
     */
    @Test
    void nilableElementCompositeMemberCertifiesNilAcrossGenerations() {
        SessionCompileResult.Success producer = compile("nilable-composite-producer.lyra", """
                class C { @pub all :Array<@nil I32> = Array<@nil I32>[#NIL 1I32] }
                """, SessionSnapshot.empty());
        SessionCompileResult.Success consumer = compile("nilable-composite-consumer.lyra",
                "let value :C = :C[]", producer.stagedSnapshot());
        ValueAlternative field = certificate(consumer).boundaryState().objects().values().stream()
                .filter(object -> object.schema().type().id().name().equals("C"))
                .flatMap(object -> object.fields().values().stream())
                .flatMap(values -> values.alternatives().stream())
                .findFirst().orElseThrow();
        assertEquals(ArrayType.of(PrimitiveType.I32.nilable()), field.type());
        assertEquals(1, field.nilProvenance().size());
        assertEquals(ProjectionPath.arrayElement(0), field.nilProvenance().getFirst().route());
    }

    @Test
    void projectTransfersRejectGenuinelyIncompatibleRoutes() {
        var arrayType = ArrayType.of(PrimitiveType.I32);
        var nilableArrayType = ArrayType.of(PrimitiveType.I32.nilable());
        var tupleType = TupleType.of(List.of(PrimitiveType.I32));
        var base = new SessionFlowCertificate.RetainedInitializerTransfer.Value(
                arrayType, ValueAlternatives.singleton(ValueAlternative.scalar(arrayType)));
        var nilableBase = new SessionFlowCertificate.RetainedInitializerTransfer.Value(
                nilableArrayType, ValueAlternatives.singleton(ValueAlternative.scalar(nilableArrayType)));
        var tupleBase = new SessionFlowCertificate.RetainedInitializerTransfer.Value(
                tupleType, ValueAlternatives.singleton(ValueAlternative.scalar(tupleType)));
        var index = new SessionFlowCertificate.RetainedInitializerTransfer.Value(
                PrimitiveType.I32, ValueAlternatives.singleton(ValueAlternative.scalar(PrimitiveType.I32)));

        // A route reaching an I32 element cannot certify a Char result.
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.RetainedInitializerTransfer.Project(
                        PrimitiveType.CHAR, base, arrayType,
                        SessionFlowCertificate.RetainedInitializerTransfer.ProjectionKind.ROUTE,
                        ProjectionPath.of(ProjectionStep.arrayElement(1)), Optional.of(index)));
        // A route reaching a @nil element cannot certify a non-nilable result.
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.RetainedInitializerTransfer.Project(
                        PrimitiveType.I32, nilableBase, nilableArrayType,
                        SessionFlowCertificate.RetainedInitializerTransfer.ProjectionKind.ROUTE,
                        ProjectionPath.of(ProjectionStep.arrayElement(1)), Optional.of(index)));
        // A tuple-member route cannot carry an index transfer.
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.RetainedInitializerTransfer.Project(
                        PrimitiveType.I32, tupleBase, tupleType,
                        SessionFlowCertificate.RetainedInitializerTransfer.ProjectionKind.ROUTE,
                        ProjectionPath.of(ProjectionStep.tupleMember(0)), Optional.of(index)));
        // A root route is not a projection.
        assertThrows(IllegalArgumentException.class, () ->
                new SessionFlowCertificate.RetainedInitializerTransfer.Project(
                        PrimitiveType.I32, base, arrayType,
                        SessionFlowCertificate.RetainedInitializerTransfer.ProjectionKind.ROUTE,
                        ProjectionPath.root(), Optional.of(index)));
    }

    private static SessionCompileResult.Success producer() {
        return compile("producer.lyra", """
                let increment :Fn<I32;I32> = (=> |value| (+ value 1))
                class Defaults {
                    literal :I32 = 7
                    alias :I32 = self:.literal
                    lambda :Fn<;Array<I32>> = (=> || Array<I32>[3])
                    called :I32 = ::increment[4]
                }
                struct Data {
                    required :I32
                    array :Array<I32> = Array<I32>[1 2]
                }
                """, SessionSnapshot.empty());
    }

    private static SessionCompileResult.Success compile(
            String label, String source, SessionSnapshot snapshot) {
        SessionCompileResult result = LyraCompiler.compileSession(
                new SessionCompileRequest(label, source, snapshot));
        return assertInstanceOf(SessionCompileResult.Success.class, result,
                result.diagnostics().toString());
    }

    private static SessionFlowCertificate certificate(SessionCompileResult.Success compiled) {
        return compiled.flowCertificate();
    }

    private static AggregateIdentityFact derivedArrayFact(
            SessionFlowCertificate.RetainedInitializerTransfer.ArrayAllocation allocation,
            io.mindspice.lyra.compiler.identity.FlowSiteId context,
            SourceSpan useSpan) {
        DeclarationId derived = RetainedAllocationDerivation.arrayAllocation(
                context, allocation.site());
        return new AggregateIdentityFact(
                io.mindspice.lyra.compiler.semantic.flow.ArrayIdentity.localAllocation(
                        allocation.moduleId(), derived, allocation.type()),
                ProjectionPath.root(), OwnershipWitness.local(
                        allocation.moduleId(), derived, allocation.scopeId(), allocation.span())
                .withOriginSite(allocation.site()).atUse(useSpan));
    }

    private static AggregateIdentityFact derivedCrossModuleArrayFact(
            SessionFlowCertificate.RetainedInitializerTransfer.ArrayAllocation allocation,
            io.mindspice.lyra.compiler.identity.FlowSiteId context,
            SourceSpan useSpan) {
        DeclarationId derived = RetainedAllocationDerivation.arrayAllocation(
                context, allocation.site());
        var export = io.mindspice.lyra.compiler.identity.ExportId.of(
                allocation.moduleId(), "_flow_" + derived.ordinal(),
                io.mindspice.lyra.compiler.types.LyraSignature.of(
                        List.of(), allocation.type()));
        return new AggregateIdentityFact(
                io.mindspice.lyra.compiler.semantic.flow.ArrayIdentity.crossModuleOrigin(
                        allocation.moduleId(), derived, export, allocation.type()),
                ProjectionPath.root(), OwnershipWitness.crossModule(
                        allocation.moduleId(), derived, allocation.scopeId(),
                        allocation.span(), export).withOriginSite(allocation.site())
                .atUse(useSpan));
    }

    private static Set<Object> allArrayIdentities(SessionFlowCertificate certificate) {
        return certificate.boundaryState().objects().values().stream()
                .flatMap(object -> object.fields().values().stream())
                .flatMap(values -> values.alternatives().stream())
                .flatMap(value -> value.aggregateIdentities().stream())
                .map(AggregateIdentityFact::identity)
                .collect(Collectors.toSet());
    }

    private static SessionFlowCertificate.RetainedNominal nominal(
            SessionFlowCertificate certificate, String name) {
        return certificate.retainedNominals().values().stream()
                .filter(value -> value.name().equals(name)).findFirst().orElseThrow();
    }

    private static TypedDeclaration member(SessionCompileResult.Success compiled,
            SessionFlowCertificate.RetainedNominal nominal, String name) {
        return nominal.nominal().members().stream().map(compiled.typedGraph()::declaration)
                .flatMap(Optional::stream).filter(value -> value.name().equals(name))
                .findFirst().orElseThrow();
    }

    private static SessionFlowCertificate.RetainedInitializerTransfer transfer(
            SessionFlowCertificate.RetainedNominal nominal, String memberName) {
        return memberInitializer(nominal, memberName).orElseThrow();
    }

    private static Optional<SessionFlowCertificate.RetainedInitializerTransfer> memberInitializer(
            SessionFlowCertificate.RetainedNominal nominal, String memberName) {
        for (int index = 0; index < nominal.nominal().members().size(); index++) {
            if (memberName.equals(nominal.nominal().schema().members().get(index).name())) {
                return nominal.memberInitializer(index);
            }
        }
        throw new IllegalArgumentException("unknown member: " + memberName);
    }

    private static void assertCoverage(SessionFlowCertificate.RetainedNominal nominal) {
        assertEquals(nominal.nominal().schema().members().size(),
                nominal.memberInitializers().size());
        for (int index = 0; index < nominal.memberInitializers().size(); index++) {
            assertEquals(nominal.nominal().schema().members().get(index).hasInitializer(),
                    nominal.memberInitializer(index).isPresent());
        }
    }
}
