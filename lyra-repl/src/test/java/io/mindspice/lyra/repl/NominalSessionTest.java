package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.runtime.NominalType;
import io.mindspice.lyra.runtime.RuntimeIoEnvironment;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cross-generation execution and bounded snapshot coverage for nominal values. */
class NominalSessionTest {
    @Test
    void retainedTypeNamesConstructTheExactOriginalNominalType() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of(
                    "type-1.lyra", "struct Point { let value :I32 }")));
            success(session.submit(EvaluationSource.of(
                    "type-2.lyra", "let point :Point = Point[23]")));
            EvaluationResult.Success result = success(
                    session.submit(EvaluationSource.of("type-3.lyra", "point:.value")));
            assertEquals("23", assertInstanceOf(ValueSnapshot.Scalar.class,
                    result.value().orElseThrow().data()).value());
        }
    }

    @Test
    void retainedClassFactoriesExecuteOriginalInitializersAndConstructor() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("factory-1.lyra", """
                    class Counter {
                        let @pub @mut value :I32 = 0
                        let @pub read :Fn<;I32> = (=> || self:.value)
                        Counter = (=> |start :I32| { self:.value := start })
                    }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "factory-2.lyra", "let fresh :Counter = Counter[41]")));
            EvaluationResult.Success result = success(session.submit(EvaluationSource.of(
                    "factory-3.lyra", "fresh::read[]")));
            assertEquals("41", assertInstanceOf(ValueSnapshot.Scalar.class,
                    result.value().orElseThrow().data()).value());
        }
    }

    /** Runtime counterpart of RetainedNominalFlowCertificateTest's legal initializer inventory. */
    @TestFactory
    Stream<DynamicTest> retainedScalarInitializerInventoryConstructsAndEvaluatesAcrossGenerations() {
        record Probe(String name, String type, String initializer, String expression,
                     String expectedType, String expected) { }
        List<Probe> probes = List.of(
                new Probe("literal", "I32", "1I32", "box:.value", "I32", "1"),
                new Probe("reference", "I32", "external", "box:.value", "I32", "1"),
                new Probe("member access", "I32", "self:.seed", "box:.value", "I32", "1"),
                new Probe("array length", "I32", "Array<I32>[1I32]:.length", "box:.value", "I32", "1"),
                new Probe("string length", "I32", "\"x\":.length", "box:.value", "I32", "1"),
                new Probe("lambda", "Fn<;I32>", "(=> || 1I32)", "box::value[]", "I32", "1"),
                new Probe("callable call", "I32", "(zero)", "box:.value", "I32", "0"),
                new Probe("direct call", "I32", "::inc[1I32]", "box:.value", "I32", "1"),
                new Probe("array", "Array<I32>", "Array<I32>[1I32]", "box:.value[0I32]", "I32", "1"),
                new Probe("tuple", "Tuple<I32>", "Tuple[1I32]", "box:.value:.0", "I32", "1"),
                new Probe("construction", "Nested", "Nested[]", "box:.value:.value", "I32", "1"),
                new Probe("operator", "I32", "(+ 1I32 2I32)", "box:.value", "I32", "3"),
                new Probe("short circuit", "Bool", "(and #T #F)", "box:.value", "Bool", "false"),
                new Probe("block/declaration/rebinding", "I32",
                        "{ let @mut local :I32 = 1I32 local := 2I32 local }", "box:.value", "I32", "2"),
                new Probe("conditional", "I32", "(maybe -> 1I32 : 2I32)", "box:.value", "I32", "2"),
                new Probe("coalesce", "I32", "(maybe : 1I32)", "box:.value", "I32", "1"),
                new Probe("match", "I32", "(match 1I32 ?? 1I32 -> 1I32 ?? _ -> 2I32)",
                        "box:.value", "I32", "1"),
                new Probe("array index", "I32", "Array<I32>[1I32][0I32]", "box:.value", "I32", "1"),
                new Probe("string index", "Char", "\"x\"[0I32]", "box:.value", "Char", "x"),
                new Probe("conversion", "I32", "I32[1I16]", "box:.value", "I32", "1"),
                new Probe("predicate narrowing", "I32", "(maybe narrowed -> narrowed : 0I32)",
                        "box:.value", "I32", "0"),
                new Probe("range", "Range<I32>", "(0I32..2I32:1I32)",
                        "box:.value", "Range<I32>", "(0..2:1)"),
                new Probe("contextual operator composition", "I32", "(+ 1 2)", "box:.value", "I32", "3"),
                new Probe("immutable block declaration composition", "I32", "{ let a :I32 = 1 a }",
                        "box:.value", "I32", "1"),
                new Probe("predicate operator composition", "I32", "((> 1 0) -> 1 : 2)",
                        "box:.value", "I32", "1"),
                new Probe("array indexing composition", "I32", "Array<I32>[1 2][0]",
                        "box:.value", "I32", "1"));
        return probes.stream().map(probe -> DynamicTest.dynamicTest(probe.name(), () -> {
            try (LyraSession session = LyraSession.open()) {
                success(session.submit(EvaluationSource.of("inventory-producer.lyra", """
                        let inc :Fn<I32;I32> = (=> |value| value)
                        let zero :Fn<;I32> = (=> || 0I32)
                        let external :I32 = 1I32
                        let @nil maybe :I32 = #NIL
                        class Nested { let @pub value :I32 = 1I32 }
                        class C {
                            let seed :I32 = 1I32
                            let @pub value :%s = %s
                        }
                        """.formatted(probe.type(), probe.initializer()))));
                success(session.submit(EvaluationSource.of(
                        "inventory-construction.lyra", "let box :C = C[]")));
                EvaluationResult.Success result = success(session.submit(EvaluationSource.of(
                        "inventory-observation.lyra", probe.expression())));
                assertEquals(probe.expectedType(), result.value().orElseThrow().canonicalType());
                assertScalar(probe.expected(), result);
            }
        }));
    }

    /**
     * Unit-typed (and effect-performing) member initializers are transferred
     * and constructed in a later generation, but observing the retained object
     * one generation after that currently fails at runtime with {@code LYR-LINK}.
     * The defect predates the phase-2 transfer algebra work and is tracked as
     * `.internal-dev/bugs/retained-nominal/unit-initializer-later-observation-linkage.md`
     * (GitHub issue #7).  These cases pin the observed structured failure so the
     * gap stays visible; flip them to value assertions when the linkage defect is
     * fixed.
     */
    @TestFactory
    Stream<DynamicTest> retainedUnitInitializerInventoryDocumentsKnownObservationLinkageGap() {
        record Probe(String name, String type, String initializer, String expression,
                     String constructionOutput) { }
        List<Probe> probes = List.of(
                new Probe("namespace member", "Fn<String;Unit>", "io->:.println",
                        "box::value[\"member\"]", ""),
                new Probe("namespace direct call", "Unit", "io->::println[\"\"]",
                        "box:.value", "\n"),
                new Probe("iter", "Unit", "::iter[(0I32..2I32:1I32) || ()]",
                        "box:.value", ""),
                new Probe("while", "Unit", "::while[|| #F || ()]",
                        "box:.value", ""));
        return probes.stream().map(probe -> DynamicTest.dynamicTest(probe.name(), () -> {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            SessionOptions options = SessionOptions.builder().ioEnvironment(new RuntimeIoEnvironment(
                    new ByteArrayInputStream(new byte[0]), output, output, StandardCharsets.UTF_8)).build();
            try (LyraSession session = LyraSession.open(options)) {
                success(session.submit(EvaluationSource.of("unit-inventory-producer.lyra", """
                        import std->io
                        class C { let @pub value :%s = %s }
                        """.formatted(probe.type(), probe.initializer()))));
                assertEquals("", output.toString(StandardCharsets.UTF_8));
                success(session.submit(EvaluationSource.of(
                        "unit-inventory-construction.lyra", "let box :C = C[]")));
                assertEquals(probe.constructionOutput(), output.toString(StandardCharsets.UTF_8));
                EvaluationResult observation = session.submit(EvaluationSource.of(
                        "unit-inventory-observation.lyra", probe.expression()));
                EvaluationResult.RuntimeFailure failure = assertInstanceOf(
                        EvaluationResult.RuntimeFailure.class, observation, observation::toString);
                assertEquals("LYR-LINK", failure.code());
                assertEquals("nominal object belongs to an unrelated artifact or session",
                        failure.summary());
            }
        }));
    }

    @Test
    void retainedMixedTupleInitializerPreservesScalarAndInvocableLambdaAcrossGenerations() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("mixed-tuple-producer.lyra", """
                    class C { let @pub x :Tuple<I32,Fn<;I32>> = Tuple[1 (=> || 2)] }
                    """)));
            success(session.submit(EvaluationSource.of("mixed-tuple-construction.lyra", "let box :C = C[]")));
            EvaluationResult.Success result = success(session.submit(EvaluationSource.of(
                    "mixed-tuple-observation.lyra", """
                    let observed :Tuple<I32,I32> = Tuple[box:.x:.0 (box:.x:.1)]
                    observed
                    """)));
            ValueSnapshot.Aggregate tuple = assertInstanceOf(ValueSnapshot.Aggregate.class,
                    result.value().orElseThrow().data());
            assertEquals(AggregateKind.TUPLE, tuple.kind());
            assertEquals(List.of("I32", "I32"), tuple.elements().stream()
                    .map(ValueSnapshot::canonicalType).toList());
            assertEquals(List.of("1", "2"), tuple.elements().stream()
                    .map(ValueSnapshot::data).map(ValueSnapshot.Scalar.class::cast)
                    .map(ValueSnapshot.Scalar::value).toList());
        }
    }

    @Test
    void retainedInitializersTransferClosedExpressionAlgebraAcrossGenerations() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("algebra-1.lyra", """
                    let inc :Fn<I32;I32> = (=> |value| (+ value 1I32))
                    let source :Array<I32> = Array<I32>[1I32, ::inc[1I32]]
                    class Inner { let @pub value :I32 = 9I32 }
                    class Defaults {
                        let @pub op :I32 = (+ ::inc[1I32] 2I32)
                        let @pub converted :I32 = I32[3I16]
                        let @pub values :Array<I32> = Array<I32>[1I32, ::inc[1I32]]
                        let @pub index :I32 = source[1I32]
                        let @pub tuple :Tuple<I32,I32> = Tuple[1I32, ::inc[2I32]]
                        let @pub branch :I32 = (#T -> ::inc[3I32] : 0I32)
                        let @pub block :I32 = { let @mut local :I32 = 1I32 local := ::inc[local] local }
                        let @pub nested :Inner = Inner[]
                    }
                    """)));
            success(session.submit(EvaluationSource.of("algebra-2.lyra", "let value :Defaults = Defaults[]")));
            EvaluationResult.Success result = success(session.submit(EvaluationSource.of("algebra-3.lyra", """
                    (+ (* value:.op 1000000I32) (* value:.converted 100000I32)
                       (* value:.index 10000I32) (* value:.tuple:.1 1000I32)
                       (* value:.branch 100I32) (* value:.block 10I32) value:.nested:.value)
                    """)));
            assertEquals("4323429", assertInstanceOf(ValueSnapshot.Scalar.class,
                    result.value().orElseThrow().data()).value());
        }
    }

    @Test
    void retainedStructuredChildrenKeepTheirExactResolvedTypes() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("typed-children-1.lyra", """
                    struct Inner { let values :Array<I32> }
                    let candidate :Fn<;I32> = (=> || 3I32)
                    class TypedDefaults {
                        let @pub callable :Fn<;I32> = {
                            let @mut selected :Fn<;I32> = (=> || 1I32)
                            selected := (=> || 7I32)
                            selected
                        }
                        let @pub values :Array<I32> = {
                            let @mut selected :Array<I32> = Array<I32>[1I32]
                            selected := Array<I32>[8I32]
                            selected
                        }
                        let @pub nested :Inner = Inner[Array<I32>[9I32]]
                        let @pub choice :Bool = (or candidate #F)
                        let @pub looped :Unit = ::while[|| #F || ()]
                    }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "typed-children-2.lyra", "let value :TypedDefaults = TypedDefaults[]")));
            assertScalar("7", success(session.submit(EvaluationSource.of(
                    "typed-children-3.lyra", "value::callable[]"))));
            assertScalar("8", success(session.submit(EvaluationSource.of(
                    "typed-children-4.lyra", "value:.values[0I32]"))));
            assertScalar("9", success(session.submit(EvaluationSource.of(
                    "typed-children-5.lyra", "value:.nested:.values[0I32]"))));
        }
    }

    @Test
    void retainedMutableLocalCaptureKeepsItsExactSharedCell() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("local-cell-1.lyra", """
                    let make :Fn<;Fn<;I32>> = (=> || {
                        let @mut value :I32 = 1I32
                        let readLocal :Fn<;I32> = (=> || value)
                        value := 12I32
                        readLocal
                    })
                    class CounterFactory { let @pub read :Fn<;I32> = ::make[] }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "local-cell-2.lyra", "let value :CounterFactory = CounterFactory[]")));
            assertScalar("12", success(session.submit(EvaluationSource.of(
                    "local-cell-3.lyra", "value::read[]"))));
        }
    }

    @Test
    void retainedProducerLocalCellsAliasWithinOneConstructionButNotAcrossInstances() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("two-cells-1.lyra", """
                    let make :Fn<;Tuple<Fn<;I32>,Fn<;Unit>>> = (=> || {
                        let @mut value :I32 = 1I32
                        Tuple[(=> || value) (=> || { value := (+ value 1I32) })]
                    })
                    class Counter { let @pub operations :Tuple<Fn<;I32>,Fn<;Unit>> = ::make[] }
                    """)));
            success(session.submit(EvaluationSource.of("two-cells-2.lyra", """
                    let first :Counter = Counter[]
                    let second :Counter = Counter[]
                    """)));
            success(session.submit(EvaluationSource.of(
                    "two-cells-3.lyra", "(first:.operations:.1)")));
            assertScalar("21", success(session.submit(EvaluationSource.of("two-cells-4.lyra", """
                    (+ (* (first:.operations:.0) 10I32) (second:.operations:.0))
                    """))));
        }
    }

    @Test
    void retainedAlternativesRemovePredicateScopeAndKeepMatchSelectorContinuation() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("alternative-scope-1.lyra", """
                    let @nil maybe :I32 = 4I32
                    let @mut selected :Array<I32> = Array<I32>[1I32]
                    class Choice {
                        let @pub narrowed :I32 = (maybe present -> present : 0I32)
                        let @pub matched :I32 = (match 0I32
                            ?? { selected := Array<I32>[9I32] 1I32 } -> 0I32
                            ?? _ -> selected[0I32])
                    }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "alternative-scope-2.lyra", "let value :Choice = Choice[]")));
            assertScalar("4", success(session.submit(EvaluationSource.of(
                    "alternative-scope-3.lyra", "value:.narrowed"))));
            assertScalar("9", success(session.submit(EvaluationSource.of(
                    "alternative-scope-4.lyra", "value:.matched"))));
        }
    }

    @Test
    void retainedNestedLambdaCallArgumentExecutesInLaterGeneration() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("nested-lambda-1.lyra", """
                    let apply :Fn<Fn<;I32>;I32> = (=> |callable| (callable))
                    class Box { let @pub value :I32 = ::apply[(=> || 31I32)] }
                    """)));
            success(session.submit(EvaluationSource.of("nested-lambda-2.lyra", "let box :Box = Box[]")));
            assertScalar("31", success(session.submit(EvaluationSource.of(
                    "nested-lambda-3.lyra", "box:.value"))));
        }
    }

    @Test
    void retainedCallableValueCallExecutesInLaterGeneration() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("callable-value-1.lyra", """
                    let selected :Fn<;I32> = (=> || 32I32)
                    class Box { let @pub value :I32 = (selected) }
                    """)));
            success(session.submit(EvaluationSource.of("callable-value-2.lyra", "let box :Box = Box[]")));
            assertScalar("32", success(session.submit(EvaluationSource.of(
                    "callable-value-3.lyra", "box:.value"))));
        }
    }

    @Test
    void retainedFactoryReferenceSurvivesLaterLexicalShadowing() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("shadow-1.lyra", """
                    let source :Fn<;I32> = (=> || 41I32)
                    class Box { let @pub value :I32 = ::source[] }
                    """)));
            success(session.submit(EvaluationSource.of("shadow-2.lyra",
                    "let source :Fn<;I32> = (=> || 99I32)")));
            success(session.submit(EvaluationSource.of("shadow-3.lyra", "let box :Box = Box[]")));
            assertScalar("41", success(session.submit(EvaluationSource.of(
                    "shadow-4.lyra", "box:.value"))));
        }
    }

    @Test
    void retainedFactoryObservesCurrentMethodSlotButSavedCallableKeepsOriginalSlot() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("slot-1.lyra", """
                    class Counter { let @pub @mut read :Fn<;I32> = (=> || 1I32) }
                    let @mut counter :Counter = Counter[]
                    let saved :Fn<;I32> = counter:.read
                    class Box {
                        let @pub current :I32 = counter::read[]
                        let @pub original :I32 = (saved)
                    }
                    """)));
            success(session.submit(EvaluationSource.of("slot-2.lyra",
                    "counter:.read := (=> || 7I32)")));
            success(session.submit(EvaluationSource.of("slot-3.lyra", "let box :Box = Box[]")));
            assertScalar("71", success(session.submit(EvaluationSource.of(
                    "slot-4.lyra", "(+ (* box:.current 10I32) box:.original)"))));
        }
    }

    @Test
    void retainedCallableArrayAndTupleDefaultsKeepFunctionAndNilRoutes() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("callable-routes-1.lyra", """
                    class Routes {
                        let @pub array :Array<Fn<;I32>> = Array<Fn<;I32>>[(=> || 5I32)]
                        let @pub tuple :Tuple<Fn<;I32>,Fn<;I32>> = Tuple[(=> || 6I32) (=> || 7I32)]
                    }
                    """)));
            success(session.submit(EvaluationSource.of("callable-routes-2.lyra", "let routes :Routes = Routes[]")));
            assertScalar("18", success(session.submit(EvaluationSource.of(
                    "callable-routes-3.lyra", "(+ (+ (routes:.array[0I32]) (routes:.tuple:.0)) (routes:.tuple:.1))"))));
        }
    }

    @Test
    void retainedAlternativesKeepUnselectedWritesAndEffectsOutOfRuntimeWhileMatchingOrdinaryResults() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SessionOptions options = SessionOptions.builder().ioEnvironment(new RuntimeIoEnvironment(
                new ByteArrayInputStream(new byte[0]), output, output, StandardCharsets.UTF_8)).build();
        String definitions = """
                let @mut writes :I32 = 0I32
                let @nil maybe :I32 = 7I32
                class Choices {
                    let @pub conditional :I32 = (#T -> 7I32 : { writes := 99I32 0I32 })
                    let @pub coalesced :I32 = (maybe : { writes := 99I32 0I32 })
                    let @pub matched :I32 = (match 1I32 ?? 1I32 -> 7I32 ?? _ -> { writes := 99I32 0I32 })
                }
                """;
        try (LyraSession retained = LyraSession.open(options); LyraSession ordinary = LyraSession.open(options)) {
            success(retained.submit(EvaluationSource.of("lazy-retained-1.lyra", definitions)));
            assertScalar("777", success(retained.submit(EvaluationSource.of("lazy-retained-2.lyra", """
                    let choices :Choices = Choices[]
                    (+ (* (+ (* choices:.conditional 10I32) choices:.coalesced) 10I32) choices:.matched)
                    """))));
            assertScalar("0", success(retained.submit(EvaluationSource.of("lazy-retained-3.lyra", "writes"))));

            assertScalar("777", success(ordinary.submit(EvaluationSource.of("lazy-ordinary.lyra", definitions + """
                    let choices :Choices = Choices[]
                    (+ (* (+ (* choices:.conditional 10I32) choices:.coalesced) 10I32) choices:.matched)
                    """))));
            assertScalar("0", success(ordinary.submit(EvaluationSource.of("lazy-ordinary-writes.lyra", "writes"))));
        }
        assertEquals("", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void retainedClassFactoriesPreserveAggregateDefaultProvenance() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("aggregate-factory-1.lyra", """
                    class Bag { let @pub values :Array<I32> = Array<I32>[1 2] }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "aggregate-factory-2.lyra", "let bag :Bag = Bag[]")));
            EvaluationResult.Success result = success(session.submit(EvaluationSource.of(
                    "aggregate-factory-3.lyra", "bag:.values[1]")));
            assertEquals("2", assertInstanceOf(ValueSnapshot.Scalar.class,
                    result.value().orElseThrow().data()).value());
        }
    }

    @Test
    void retainedClassFactoriesPreserveNestedAggregateDefaultProvenance() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("nested-aggregate-factory-1.lyra", """
                    class Bag { let @pub values :Tuple<Array<I32>,I32> = Tuple[Array<I32>[3 4] 5] }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "nested-aggregate-factory-2.lyra", "let bag :Bag = Bag[]")));
            EvaluationResult.Success result = success(session.submit(EvaluationSource.of(
                    "nested-aggregate-factory-3.lyra", "bag:.values:.0[1]")));
            assertEquals("4", assertInstanceOf(ValueSnapshot.Scalar.class,
                    result.value().orElseThrow().data()).value());
        }
    }

    @Test
    void retainedClassFactoriesPreserveAggregateDefaultAliases() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("aggregate-alias-factory-1.lyra", """
                    class Bag {
                        let @pub values :Array<I32> = Array<I32>[8 9]
                        let @pub alias :Array<I32> = self:.values
                    }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "aggregate-alias-factory-2.lyra", "let bag :Bag = Bag[]")));
            EvaluationResult.Success result = success(session.submit(EvaluationSource.of(
                    "aggregate-alias-factory-3.lyra", "bag:.alias[1]")));
            assertEquals("9", assertInstanceOf(ValueSnapshot.Scalar.class,
                    result.value().orElseThrow().data()).value());
        }
    }

    @Test
    void retainedClassFactoriesPreserveAggregateReturningDefaultCalls() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("aggregate-call-factory-1.lyra", """
                    let make :Fn<;Array<I32>> = (=> || Array<I32>[10 11])
                    class Bag { let @pub values :Array<I32> = ::make[] }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "aggregate-call-factory-2.lyra", "let bag :Bag = Bag[]")));
            EvaluationResult.Success result = success(session.submit(EvaluationSource.of(
                    "aggregate-call-factory-3.lyra", "bag:.values[1]")));
            assertEquals("11", assertInstanceOf(ValueSnapshot.Scalar.class,
                    result.value().orElseThrow().data()).value());
        }
    }

    @Test
    void retainedClassFactoriesPreserveCallableReturningDefaultCalls() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("callable-call-factory-1.lyra", """
                    let make :Fn<;Fn<;I32>> = (=> || (=> || 14))
                    class Box { let @pub read :Fn<;I32> = ::make[] }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "callable-call-factory-2.lyra", "let box :Box = Box[]")));
            EvaluationResult.Success result = success(session.submit(EvaluationSource.of(
                    "callable-call-factory-3.lyra", "box::read[]")));
            assertEquals("14", assertInstanceOf(ValueSnapshot.Scalar.class,
                    result.value().orElseThrow().data()).value());
        }
    }

    @Test
    void retainedCallableSummariesResolveNestedProducerConstructions() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("nested-construction-1.lyra", """
                    class Inner { let @pub value :I32 = 7 }
                    let make :Fn<;Inner> = (=> || Inner[])
                    class Outer { let @pub inner :Inner = ::make[] }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "nested-construction-2.lyra", "let outer :Outer = Outer[]")));
            EvaluationResult.Success result = success(session.submit(EvaluationSource.of(
                    "nested-construction-3.lyra", "outer:.inner:.value")));
            assertEquals("7", assertInstanceOf(ValueSnapshot.Scalar.class,
                    result.value().orElseThrow().data()).value());
        }
    }

    @Test
    void retainedNamespaceMemberCallableConstructsWithoutUnsupportedTransfer() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SessionOptions options = SessionOptions.builder().ioEnvironment(new RuntimeIoEnvironment(
                new ByteArrayInputStream(new byte[0]), output, output, StandardCharsets.UTF_8)).build();
        try (LyraSession session = LyraSession.open(options)) {
            success(session.submit(EvaluationSource.of("intrinsic-member-1.lyra", """
                    import std->io
                    class Printer { let @pub print :Fn<String;Unit> = io->:.println }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "intrinsic-member-2.lyra", "let printer :Printer = Printer[]")));
            assertEquals("", output.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void retainedNamespaceIntrinsicDefaultsResolveAndExecute() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SessionOptions options = SessionOptions.builder()
                .ioEnvironment(new RuntimeIoEnvironment(
                        new ByteArrayInputStream(new byte[0]), output, output,
                        StandardCharsets.UTF_8))
                .build();
        try (LyraSession session = LyraSession.open(options)) {
            success(session.submit(EvaluationSource.of("intrinsic-default-1.lyra", """
                    import std->io
                    class Printer { let @pub printed :Unit = io->::println["retained"] }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "intrinsic-default-2.lyra", "let printer :Printer = Printer[]")));
            assertEquals("retained\n", output.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void retainedNilDefaultsKeepProducerCertifiedProvenance() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of(
                    "nil-default-1.lyra", "class Maybe { let @pub @nil value :I32 = #NIL }")));
            success(session.submit(EvaluationSource.of(
                    "nil-default-2.lyra", "let maybe :Maybe = Maybe[]")));
            EvaluationResult.Success result = success(session.submit(EvaluationSource.of(
                    "nil-default-3.lyra", "maybe:.value")));
            assertInstanceOf(ValueSnapshot.Nil.class,
                    result.value().orElseThrow().data());
        }
    }

    @Test
    void retainedAggregateDefaultsRemainForeignForConsumerMutation() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("foreign-default-1.lyra", """
                    class Bag { let @pub values :Array<I32> = Array<I32>[1 2] }
                    """)));

            EvaluationResult.CompilationFailure failure = assertInstanceOf(
                    EvaluationResult.CompilationFailure.class,
                    session.submit(EvaluationSource.of("foreign-default-2.lyra", """
                            let @mut bag :Bag = Bag[]
                            bag:.values[0] := 3
                            """)));

            assertEquals(CompilerDiagnosticCodes.RESOLVE_IMPORTED_MUTATION,
                    failure.diagnostics().getFirst().code());
        }
    }

    @Test
    void retainedConstructorsPreserveAggregateFieldWrites() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("constructor-aggregate-1.lyra", """
                    class Bag {
                        let @pub values :Array<I32>
                        Bag = (=> |values :Array<I32>| { self:.values := values })
                    }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "constructor-aggregate-2.lyra", "let bag :Bag = Bag[Array<I32>[6 7]]")));
            EvaluationResult.Success result = success(session.submit(EvaluationSource.of(
                    "constructor-aggregate-3.lyra", "bag:.values[1]")));
            assertEquals("7", assertInstanceOf(ValueSnapshot.Scalar.class,
                    result.value().orElseThrow().data()).value());
        }
    }

    @Test
    void retainedConstructorsTransferCapturedStateEffects() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("constructor-effect-1.lyra", """
                    let @mut selected :Fn<;I32> = (=> || 0)
                    class Box { Box = (=> || { selected := (=> || 12) }) }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "constructor-effect-2.lyra", "let box :Box = Box[]")));
            EvaluationResult.Success result = success(session.submit(EvaluationSource.of(
                    "constructor-effect-3.lyra", "::selected[]")));
            assertEquals("12", assertInstanceOf(ValueSnapshot.Scalar.class,
                    result.value().orElseThrow().data()).value());
        }
    }

    @Test
    void retainedConstructorsInstallCapturedAndHelperReturnedClosuresAndNestedAggregateWrites() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("constructor-compositions-1.lyra", """
                    let @mut effects :I32 = 0
                    let record :Fn<I32;I32> = (=> |value| { effects := (++ effects) value })
                    let make :Fn<I32;Fn<;I32>> = (=> |value| (=> || (+ value 1)))
                    class Inner { let @pub value :I32 = 5 }
                    class Box {
                        let @pub @mut captured :Fn<;I32> = (=> || 0)
                        let @pub @mut returned :Fn<;I32> = (=> || 0)
                        let @pub @mut nested :Tuple<Array<I32>,I32> = Tuple[Array<I32>[1 2] 3]
                        let @pub built :Inner
                        Box = (=> |value :I32| {
                            self:.captured := (=> || value)
                            self:.returned := ::make[value]
                            self:.nested:.0[1] := ::record[9]
                            self:.built := Inner[]
                        })
                    }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "constructor-compositions-2.lyra", "let box :Box = Box[41]")));
            EvaluationResult.Success result = success(session.submit(EvaluationSource.of(
                    "constructor-compositions-3.lyra", """
                    let observed :Tuple<I32,I32,I32,I32,I32> =
                        Tuple[box::captured[] box::returned[] box:.nested:.0[1]
                              box:.built:.value effects]
                    observed
                    """)));
            ValueSnapshot.Aggregate tuple = assertInstanceOf(ValueSnapshot.Aggregate.class,
                    result.value().orElseThrow().data());
            assertEquals(List.of("41", "42", "9", "5", "1"), tuple.elements().stream()
                    .map(ValueSnapshot::data).map(ValueSnapshot.Scalar.class::cast)
                    .map(ValueSnapshot.Scalar::value).toList());
        }
    }

    @Test
    void retainedConstructorsRecursivelyUseExactNestedObjectContexts() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("nested-constructor-contexts-1.lyra", """
                    class Deep {
                        let @pub @mut values :Array<I32> = Array<I32>[0]
                        Deep = (=> |value :I32| { self:.values[0] := value })
                    }
                    class Leaf {
                        let @pub deep :Deep
                        Leaf = (=> |value :I32| { self:.deep := Deep[value] })
                    }
                    class Middle {
                        let @pub leaf :Leaf
                        Middle = (=> |value :I32| { self:.leaf := Leaf[value] })
                    }
                    class Outer {
                        let @pub middle :Middle
                        Outer = (=> || { self:.middle := Middle[7] })
                    }
                    let producerOuter :Outer = Outer[]
                    """)));
            success(session.submit(EvaluationSource.of(
                    "nested-constructor-contexts-2.lyra",
                    "let retainedOuter :Outer = Outer[]")));
            EvaluationResult.Success observed = success(session.submit(EvaluationSource.of(
                    "nested-constructor-contexts-3.lyra", """
                    let result :Tuple<I32,I32> =
                        Tuple[producerOuter:.middle:.leaf:.deep:.values[0]
                              retainedOuter:.middle:.leaf:.deep:.values[0]]
                    result
                    """)));
            ValueSnapshot.Aggregate tuple = assertInstanceOf(ValueSnapshot.Aggregate.class,
                    observed.value().orElseThrow().data());
            assertEquals(List.of("7", "7"), tuple.elements().stream()
                    .map(ValueSnapshot::data).map(ValueSnapshot.Scalar.class::cast)
                    .map(ValueSnapshot.Scalar::value).toList());
        }
    }

    @Test
    void retainedConstructionOrderIsObservableAcrossArgumentsRequiredFieldsDefaultsAndConstructor() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("constructor-order-1.lyra", """
                    let @mut order :I32 = 0
                    let mark :Fn<I32;I32> = (=> |digit| {
                        order := (+ (* order 10) digit)
                        digit
                    })
                    class Ordered {
                        let @pub first :I32 = ::mark[3]
                        let @pub second :I32 = ::mark[4]
                        Ordered = (=> |left :I32 right :I32| {
                            let ignored :I32 = ::mark[(+ self:.first 2)]
                        })
                    }
                    struct Sequence {
                        let required :I32
                        let afterRequired :I32 = ::mark[(+ self:.required 1)]
                    }
                    """)));
            success(session.submit(EvaluationSource.of("constructor-order-2.lyra", """
                    let ordered :Ordered = Ordered[::mark[1], ::mark[2]]
                    let sequence :Sequence = Sequence[::mark[6]]
                    """)));
            assertScalar("1234567", success(session.submit(EvaluationSource.of(
                    "constructor-order-3.lyra", "order"))));
        }
    }

    @Test
    void retainedFactoriesInitializeRootsOnceAndDefaultsOncePerConstruction() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("constructor-once-1.lyra", """
                    let @mut rootEffects :I32 = 0
                    let initializeRoot :Fn<;I32> = (=> || {
                        rootEffects := (++ rootEffects)
                        rootEffects
                    })
                    let rootValue :I32 = ::initializeRoot[]
                    let @mut defaultEffects :I32 = 0
                    class Once {
                        let @pub value :I32 = {
                            defaultEffects := (++ defaultEffects)
                            defaultEffects
                        }
                    }
                    let sameSubmission :Once = Once[]
                    """)));
            assertScalar("13", success(session.submit(EvaluationSource.of(
                    "constructor-once-2.lyra", """
                    let first :Once = Once[]
                    let second :Once = Once[]
                    (+ (* rootEffects 10) defaultEffects)
                    """))));
            assertScalar("14", success(session.submit(EvaluationSource.of(
                    "constructor-once-3.lyra", """
                    let third :Once = Once[]
                    (+ (* rootEffects 10) defaultEffects)
                    """))));
        }
    }

    @Test
    void retainedConstructionFailuresPublishNothingPreserveEffectsAndKeepProducerUsable() {
        try (LyraSession session = LyraSession.open()) {
            String producerSource = """
                    let @mut effects :I32 = 0
                    let @mut divisor :I32 = 0
                    let touch :Fn<I32;I32> = (=> |digit| {
                        effects := (+ (* effects 10) digit)
                        digit
                    })
                    class ArgumentFailure {
                        let @pub value :I32 = 1
                        ArgumentFailure = (=> |left :I32 right :I32| { () })
                    }
                    class DefaultFailure {
                        let @pub first :I32 = ::touch[1]
                        let @pub broken :I32 = (% 8 divisor)
                    }
                    class ConstructorFailure {
                        let @pub value :I32 = ::touch[2]
                        ConstructorFailure = (=> || {
                            let marker :I32 = ::touch[3]
                            let ignored :I32 = (% 9 divisor)
                        })
                    }
                    """;
            success(session.submit(EvaluationSource.of(
                    "constructor-failures-producer.lyra", producerSource)));

            String typeFailureSource = "effects := 9 let invalid :Bool = 1";
            EvaluationResult.CompilationFailure typeFailure = assertInstanceOf(
                    EvaluationResult.CompilationFailure.class,
                    session.submit(EvaluationSource.of(
                            "constructor-type-failure.lyra", typeFailureSource)));
            var typeFailureSpan = typeFailure.diagnostics().getFirst().primarySpan();
            assertEquals(typeFailureSource.lastIndexOf('1'), typeFailureSpan.startOffset());
            assertEquals(typeFailureSource.length(), typeFailureSpan.endOffset());
            assertScalar("0", success(session.submit(EvaluationSource.of(
                    "constructor-type-failure-observe.lyra", "effects"))));

            EvaluationResult.RuntimeFailure argumentFailure = assertInstanceOf(
                    EvaluationResult.RuntimeFailure.class,
                    session.submit(EvaluationSource.of("constructor-argument-failure.lyra", """
                            let stagedArgument :ArgumentFailure =
                                ArgumentFailure[::touch[4] (% 1 divisor)]
                            """)));
            assertEquals("LYR-ARITH", argumentFailure.code());
            assertTrue(argumentFailure.frames().stream().anyMatch(frame ->
                            frame.origin().label().equals("constructor-argument-failure.lyra")
                                    && frame.excerpt().orElse("").contains("(% 1 divisor)")),
                    argumentFailure.toString());
            assertScalar("4", success(session.submit(EvaluationSource.of(
                    "constructor-argument-effects.lyra", "effects"))));
            assertInstanceOf(EvaluationResult.CompilationFailure.class,
                    session.submit(EvaluationSource.of(
                            "constructor-argument-unpublished.lyra", "stagedArgument")));

            EvaluationResult.RuntimeFailure defaultFailure = assertInstanceOf(
                    EvaluationResult.RuntimeFailure.class,
                    session.submit(EvaluationSource.of("constructor-default-failure.lyra",
                            "let stagedDefault :DefaultFailure = DefaultFailure[]")));
            assertEquals("LYR-ARITH", defaultFailure.code());
            assertProducerFrame(defaultFailure, producerSource,
                    "(% 8 divisor)", 13, 28);
            assertScalar("41", success(session.submit(EvaluationSource.of(
                    "constructor-default-effects.lyra", "effects"))));
            assertInstanceOf(EvaluationResult.CompilationFailure.class,
                    session.submit(EvaluationSource.of(
                            "constructor-default-unpublished.lyra", "stagedDefault")));

            EvaluationResult.RuntimeFailure constructorFailure = assertInstanceOf(
                    EvaluationResult.RuntimeFailure.class,
                    session.submit(EvaluationSource.of("constructor-body-failure.lyra",
                            "let stagedConstructor :ConstructorFailure = ConstructorFailure[]")));
            assertEquals("LYR-ARITH", constructorFailure.code());
            assertProducerFrame(constructorFailure, producerSource,
                    "(% 9 divisor)", 19, 28);
            assertScalar("4123", success(session.submit(EvaluationSource.of(
                    "constructor-body-effects.lyra", "effects"))));
            assertInstanceOf(EvaluationResult.CompilationFailure.class,
                    session.submit(EvaluationSource.of(
                            "constructor-body-unpublished.lyra", "stagedConstructor")));

            success(session.submit(EvaluationSource.of("constructor-recovery.lyra", """
                    divisor := 1
                    let stagedArgument :ArgumentFailure = ArgumentFailure[5 6]
                    let stagedDefault :DefaultFailure = DefaultFailure[]
                    let stagedConstructor :ConstructorFailure = ConstructorFailure[]
                    """)));
            assertScalar("4123123", success(session.submit(EvaluationSource.of(
                    "constructor-recovery-observe.lyra", "effects"))));
        }
    }

    @Test
    void currentGenerationWrappersDeriveRetainedAllocationsThroughDirectParameterAndCaptureCalls() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("wrapper-allocation-1.lyra", """
                    let make :Fn<;Array<I32>> = (=> || Array<I32>[1I32])
                    """)));
            success(session.submit(EvaluationSource.of("wrapper-allocation-2.lyra", """
                    let direct :Fn<;Array<I32>> = (=> || ::make[])
                    let invoke :Fn<Fn<;Array<I32>>;Array<I32>> = (=> |factory| (factory))
                    let parameter :Fn<;Array<I32>> = (=> || ::invoke[make])
                    let capture :Fn<Fn<;Array<I32>>;Fn<;Array<I32>>> =
                        (=> |factory| (=> || (factory)))
                    let captured :Fn<;Array<I32>> = ::capture[make]
                    class Outer {
                        let @pub directValue :Array<I32> = ::direct[]
                        let @pub parameterValue :Array<I32> = ::parameter[]
                        let @pub capturedValue :Array<I32> = ::captured[]
                    }
                    let outer :Outer = Outer[]
                    """)));
            assertScalar("3", success(session.submit(EvaluationSource.of(
                    "wrapper-allocation-3.lyra", """
                            (+ outer:.directValue[0] outer:.parameterValue[0]
                               outer:.capturedValue[0])
                            """))));
        }
    }

    @Test
    void retainedMutableCallableDefaultUsesTheValueSelectedAtEachConstruction() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("mutable-callable-default-1.lyra", """
                    let @mut selected :Fn<;I32> = (=> || 1I32)
                    class C { let @pub value :I32 = (selected) }
                    """)));
            assertScalar("1", success(session.submit(EvaluationSource.of(
                    "mutable-callable-default-2.lyra", "let first :C = C[] first:.value"))));
            assertScalar("9", success(session.submit(EvaluationSource.of(
                    "mutable-callable-default-3.lyra", """
                            selected := (=> || 9I32)
                            let second :C = C[]
                            second:.value
                            """))));
        }
    }

    @Test
    void nestedCallableReturnedThroughCurrentWrapperRemainsExactlyCertified() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("nested-returned-callable-1.lyra", """
                    let make :Fn<;Fn<;I32>> = (=> || (=> || 14I32))
                    """)));
            success(session.submit(EvaluationSource.of("nested-returned-callable-2.lyra", """
                    let wrapper :Fn<;Fn<;I32>> = (=> || ::make[])
                    class Box { let @pub read :Fn<;I32> = ::wrapper[] }
                    let box :Box = Box[]
                    """)));
            assertScalar("14", success(session.submit(EvaluationSource.of(
                    "nested-returned-callable-3.lyra", "box::read[]"))));
        }
    }

    @Test
    void objectStateAndReplacedMethodsPersistAcrossThreeSubmissions() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of(
                    "nominal-1.lyra", """
                            class Counter {
                                let @pub @mut value :I32 = 1
                                let @pub @mut read :Fn<;I32> = (=> || self:.value)
                            }
                            let @mut counter :Counter = Counter[]
                            let saved :Fn<;I32> = counter:.read
                            """)));

            success(session.submit(EvaluationSource.of(
                    "nominal-2.lyra", """
                            counter:.value := 7
                            counter:.read := (=> || (+ self:.value 10))
                            """)));

            EvaluationResult.Success result = success(session.submit(EvaluationSource.of(
                    "nominal-3.lyra", "(+ (* (saved) 100) counter::read[])")));
            ValueSnapshot snapshot = result.value().orElseThrow();
            assertEquals("I32", snapshot.canonicalType());
            assertEquals("717", assertInstanceOf(ValueSnapshot.Scalar.class, snapshot.data()).value());
            assertTrue(session.workspaceState().bindings().get("counter").type().baseType()
                    instanceof NominalType);
        }
    }

    @Test
    void nominalSnapshotsExposeStructDataButNotPrivateClassState() {
        try (LyraSession session = LyraSession.open()) {
            EvaluationResult.Success structResult = success(session.submit(EvaluationSource.of(
                    "snapshot-struct.lyra", "struct Pair { let left :I32 let right :I32 } Pair[2 3]")));
            ValueSnapshot.Aggregate struct = assertInstanceOf(ValueSnapshot.Aggregate.class,
                    structResult.value().orElseThrow().data());
            assertEquals(AggregateKind.STRUCT, struct.kind());
            assertEquals("Pair", struct.alias().orElseThrow());
            assertEquals(List.of("2", "3"), struct.elements().stream()
                    .map(ValueSnapshot::data).map(ValueSnapshot.Scalar.class::cast)
                    .map(ValueSnapshot.Scalar::value).toList());

            EvaluationResult.Success classResult = success(session.submit(EvaluationSource.of(
                    "snapshot-class.lyra", "class Secret { let value :I32 = 9 } Secret[]")));
            ValueSnapshot.Aggregate clazz = assertInstanceOf(ValueSnapshot.Aggregate.class,
                    classResult.value().orElseThrow().data());
            assertEquals(AggregateKind.CLASS, clazz.kind());
            assertEquals("Secret", clazz.alias().orElseThrow());
            assertTrue(clazz.elements().isEmpty());
        }
    }

    private static void assertScalar(String expected, EvaluationResult.Success result) {
        assertEquals(expected, assertInstanceOf(ValueSnapshot.Scalar.class,
                result.value().orElseThrow().data()).value());
    }

    private static void assertProducerFrame(
            EvaluationResult.RuntimeFailure failure, String producerSource,
            String expression, int expectedLine, int expectedColumn) {
        RuntimeFrame frame = failure.frames().stream()
                .filter(candidate -> candidate.origin().label().equals(
                        "constructor-failures-producer.lyra"))
                .filter(candidate -> candidate.excerpt().orElse("").equals(expression))
                .findFirst().orElseThrow(() -> new AssertionError(failure.toString()));
        int start = producerSource.indexOf(expression);
        assertEquals(start, frame.span().startOffset());
        assertEquals(start + expression.length(), frame.span().endOffset());
        assertEquals(0, frame.origin().originStartOffset());
        assertEquals(producerSource.length(), frame.origin().originEndOffset());
        int line = 1 + (int) producerSource.substring(0, frame.span().startOffset()).chars()
                .filter(character -> character == '\n').count();
        int lineStart = producerSource.lastIndexOf('\n', start - 1);
        int column = start - lineStart;
        assertEquals(expectedLine, line);
        assertEquals(expectedColumn, column);
    }

    private static EvaluationResult.Success success(EvaluationResult result) {
        return assertInstanceOf(EvaluationResult.Success.class, result, result::toString);
    }
}
