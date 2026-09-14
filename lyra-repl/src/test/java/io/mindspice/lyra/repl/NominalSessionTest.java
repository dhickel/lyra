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
                    "type-2.lyra", "let point :Point = :Point[23]")));
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
                    "factory-2.lyra", "let fresh :Counter = :Counter[41]")));
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
                new Probe("construction", "Nested", ":Nested[]", "box:.value:.value", "I32", "1"),
                new Probe("operator", "I32", "(+ 1I32 2I32)", "box:.value", "I32", "3"),
                new Probe("short circuit", "Bool", "(and #T #F)", "box:.value", "Bool", "false"),
                new Probe("block/declaration/rebinding", "I32",
                        "{ let @mut local :I32 = 1I32 local := 2I32 local }", "box:.value", "I32", "2"),
                new Probe("conditional", "I32", "(maybe -> 1I32 : 2I32)", "box:.value", "I32", "2"),
                new Probe("coalesce", "I32", "(maybe : 1I32)", "box:.value", "I32", "1"),
                new Probe("match", "I32", "(match 1I32 1I32 -> 1I32 _ -> 2I32)",
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
                        "inventory-construction.lyra", "let box :C = :C[]")));
                EvaluationResult.Success result = success(session.submit(EvaluationSource.of(
                        "inventory-observation.lyra", probe.expression())));
                assertEquals(probe.expectedType(), result.value().orElseThrow().canonicalType());
                assertScalar(probe.expected(), result);
            }
        }));
    }

    /**
     * The four issue-#7 retained Unit/intrinsic inventory cases flip together:
     * a producer generation that imports {@code std->io} declares the nominal,
     * a later generation constructs it (executing each member initializer
     * exactly once), and a third generation observes the exact Unit member
     * values and actually invokes the namespace-member callable.  Object
     * ownership now anchors to the session identity, and the callable read
     * carries occurrence-scoped route evidence.
     */
    @TestFactory
    Stream<DynamicTest> retainedUnitInitializerInventoryConstructsAndEvaluatesAcrossGenerations() {
        record Probe(String name, String type, String initializer, String expression,
                     String constructionOutput, String observationOutput) { }
        List<Probe> probes = List.of(
                new Probe("namespace member", "Fn<String;Unit>", "io->:.println",
                        "box::value[\"member\"]", "", "member\n"),
                new Probe("namespace direct call", "Unit", "io->::println[\"\"]",
                        "box:.value", "\n", ""),
                new Probe("iter", "Unit", "iter[(0I32..2I32:1I32) || ()]",
                        "box:.value", "", ""),
                new Probe("while", "Unit", "while[|| #F || ()]",
                        "box:.value", "", ""));
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
                // Construction executes the initializer exactly once.
                success(session.submit(EvaluationSource.of(
                        "unit-inventory-construction.lyra", "let box :C = :C[]")));
                assertEquals(probe.constructionOutput(), output.toString(StandardCharsets.UTF_8));
                // A later generation observes the exact Unit value; the
                // namespace-member case invokes the retained callable.
                EvaluationResult.Success observation = success(session.submit(EvaluationSource.of(
                        "unit-inventory-observation.lyra", probe.expression())));
                assertEquals("Unit", observation.value().orElseThrow().canonicalType());
                assertInstanceOf(ValueSnapshot.Unit.class, observation.value().orElseThrow().data());
                assertEquals(probe.constructionOutput() + probe.observationOutput(),
                        output.toString(StandardCharsets.UTF_8));
                // A second read still selects and authenticates the same slot.
                EvaluationResult.Success again = success(session.submit(EvaluationSource.of(
                        "unit-inventory-observation-again.lyra", probe.expression())));
                assertInstanceOf(ValueSnapshot.Unit.class, again.value().orElseThrow().data());
                assertEquals(probe.constructionOutput() + probe.observationOutput()
                        + probe.observationOutput(), output.toString(StandardCharsets.UTF_8));
            }
        }));
    }

    @Test
    void importedRawCallableStaysRejectedBeforeAndAfterDelegatedMemberUse() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SessionOptions options = SessionOptions.builder().ioEnvironment(new RuntimeIoEnvironment(
                new ByteArrayInputStream(new byte[0]), output, output, StandardCharsets.UTF_8)).build();
        try (LyraSession session = LyraSession.open(options)) {
            success(session.submit(EvaluationSource.of("anti-launder-producer.lyra", """
                    import std->io
                    class Box { let @pub printer :Fn<String;Unit> = io->:.println }
                    let @pub raw :Fn<String;Unit> = io->:.println
                    """)));
            success(session.submit(EvaluationSource.of(
                    "anti-launder-construction.lyra", "let box :Box = :Box[]")));
            // The same raw imported closure is rejected before any delegated use.
            EvaluationResult before = session.submit(EvaluationSource.of(
                    "anti-launder-before.lyra", "(raw \"before\")"));
            EvaluationResult.RuntimeFailure rawBefore = assertInstanceOf(
                    EvaluationResult.RuntimeFailure.class, before, before::toString);
            assertEquals("LYR-LINK", rawBefore.code());
            // The field-derived occurrence is separately usable.
            EvaluationResult.Success delegated = success(session.submit(EvaluationSource.of(
                    "anti-launder-delegated.lyra", "box::printer[\"delegated\"]")));
            assertInstanceOf(ValueSnapshot.Unit.class, delegated.value().orElseThrow().data());
            assertEquals("delegated\n", output.toString(StandardCharsets.UTF_8));
            // The delegated use did not globally bless the raw closure.
            EvaluationResult after = session.submit(EvaluationSource.of(
                    "anti-launder-after.lyra", "(raw \"after\")"));
            EvaluationResult.RuntimeFailure rawAfter = assertInstanceOf(
                    EvaluationResult.RuntimeFailure.class, after, after::toString);
            assertEquals("LYR-LINK", rawAfter.code());
            assertEquals("delegated\n", output.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void delegatedAndRawCallablesCoexistInAggregatesWithoutCrossBlessing() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SessionOptions options = SessionOptions.builder().ioEnvironment(new RuntimeIoEnvironment(
                new ByteArrayInputStream(new byte[0]), output, output, StandardCharsets.UTF_8)).build();
        try (LyraSession session = LyraSession.open(options)) {
            success(session.submit(EvaluationSource.of("coexist-producer.lyra", """
                    import std->io
                    class Box { let @pub printer :Fn<String;Unit> = io->:.println }
                    let @pub raw :Fn<String;Unit> = io->:.println
                    """)));
            success(session.submit(EvaluationSource.of(
                    "coexist-construction.lyra", "let box :Box = :Box[]")));
            success(session.submit(EvaluationSource.of("coexist-pair.lyra", """
                    let @pub pair :Tuple<Fn<String;Unit>,Fn<String;Unit>> = Tuple[box:.printer raw]
                    """)));
            // The routed occurrence executes from the same aggregate later.
            success(session.submit(EvaluationSource.of(
                    "coexist-routed.lyra", "(pair:.0 \"routed\")")));
            assertEquals("routed\n", output.toString(StandardCharsets.UTF_8));
            // The raw occurrence in the very same aggregate stays rejected.
            EvaluationResult result = session.submit(EvaluationSource.of(
                    "coexist-raw.lyra", "(pair:.1 \"raw\")"));
            EvaluationResult.RuntimeFailure failure = assertInstanceOf(
                    EvaluationResult.RuntimeFailure.class, result, result::toString);
            assertEquals("LYR-LINK", failure.code());
            assertEquals("routed\n", output.toString(StandardCharsets.UTF_8));
            // Later generations keep the same distinction.
            success(session.submit(EvaluationSource.of(
                    "coexist-routed-later.lyra", "(pair:.0 \"routed-again\")")));
            EvaluationResult later = session.submit(EvaluationSource.of(
                    "coexist-raw-later.lyra", "(pair:.1 \"again\")"));
            EvaluationResult.RuntimeFailure again = assertInstanceOf(
                    EvaluationResult.RuntimeFailure.class, later, later::toString);
            assertEquals("LYR-LINK", again.code());
            assertEquals("routed\nrouted-again\n", output.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void delegatedMemberCallablesSurviveSavedCapturedParameterReturnedAndReboundPropagation() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SessionOptions options = SessionOptions.builder().ioEnvironment(new RuntimeIoEnvironment(
                new ByteArrayInputStream(new byte[0]), output, output, StandardCharsets.UTF_8)).build();
        try (LyraSession session = LyraSession.open(options)) {
            success(session.submit(EvaluationSource.of("propagation-producer.lyra", """
                    import std->io
                    class Box { let @pub printer :Fn<String;Unit> = io->:.println }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "propagation-construction.lyra", "let box :Box = :Box[]")));
            // Assignment, aggregate storage, a callable-parameter transfer, a
            // callable-return transfer, and a captured invocation all keep
            // the exact carried route evidence.  Transporters are invoked in
            // their own generation; the importing graph intentionally has no
            // general cross-generation callable bridge.
            success(session.submit(EvaluationSource.of("propagation-save.lyra", """
                    let @pub saved :Fn<String;Unit> = box:.printer
                    let @pub bag :Array<Fn<String;Unit>> = Array<Fn<String;Unit>>[box:.printer]
                    let @pub round :Fn<String;Unit> = {
                        let held :Fn<String;Unit> = box:.printer
                        let mk :Fn<;Fn<String;Unit>> = (=> || held)
                        (mk)
                    }
                    { let apply :Fn<Fn<String;Unit>,String;Unit> = (=> |f s| (f s))
                      (apply box:.printer "param") }
                    { let held :Fn<String;Unit> = box:.printer
                      let cap :Fn<;Unit> = (=> || (held "captured")) (cap) }
                    """)));
            assertEquals("param\ncaptured\n", output.toString(StandardCharsets.UTF_8));
            success(session.submit(EvaluationSource.of("propagation-direct.lyra", "(saved \"direct\")")));
            success(session.submit(EvaluationSource.of("propagation-array.lyra", "(bag[0I32] \"array\")")));
            success(session.submit(EvaluationSource.of(
                    "propagation-returned.lyra", "(round \"returned\")")));
            assertEquals("param\ncaptured\ndirect\narray\nreturned\n",
                    output.toString(StandardCharsets.UTF_8));
            success(session.submit(EvaluationSource.of(
                    "propagation-later.lyra", "(saved \"later\")")));
            assertEquals("param\ncaptured\ndirect\narray\nreturned\nlater\n",
                    output.toString(StandardCharsets.UTF_8));
        }
    }

    @TestFactory
    Stream<DynamicTest> directRetainedMemberCallableReadsSurviveLambdaSummaries() {
        record Probe(String name, String expression) { }
        List<Probe> probes = List.of(
                new Probe("direct capture", """
                        { let cap :Fn<;I32> = (=> || (box:.f)) (cap) }
                        """),
                new Probe("direct return", """
                        { let mk :Fn<;Fn<;I32>> = (=> || box:.f)
                          let returned :Fn<;I32> = (mk)
                          (returned) }
                        """),
                new Probe("direct invocation", """
                        { let cap :Fn<;I32> = (=> || box::f[]) (cap) }
                        """));
        return probes.stream().map(probe -> DynamicTest.dynamicTest(probe.name(), () -> {
            try (LyraSession session = LyraSession.open()) {
                success(session.submit(EvaluationSource.of("lambda-member-producer.lyra", """
                        import std->io
                        class Box { let @pub f :Fn<;I32> = (=> || 7I32) }
                        """)));
                success(session.submit(EvaluationSource.of(
                        "lambda-member-construction.lyra", "let box :Box = :Box[]")));
                assertScalar("7", success(session.submit(EvaluationSource.of(
                        "lambda-member-observation.lyra", probe.expression()))));
            }
        }));
    }

    @Test
    void fieldReplacementKeepsSavedSelectionAndReadsSelectTheReplacement() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SessionOptions options = SessionOptions.builder().ioEnvironment(new RuntimeIoEnvironment(
                new ByteArrayInputStream(new byte[0]), output, output, StandardCharsets.UTF_8)).build();
        try (LyraSession session = LyraSession.open(options)) {
            success(session.submit(EvaluationSource.of("replacement-producer.lyra", """
                    import std->io
                    class Box { let @pub @mut printer :Fn<String;Unit> = io->:.println }
                    let @pub quiet :Fn<String;Unit> = (=> |s| ())
                    """)));
            success(session.submit(EvaluationSource.of(
                    "replacement-construction.lyra", "let @pub @mut box :Box = :Box[]")));
            success(session.submit(EvaluationSource.of(
                    "replacement-save.lyra", "let @pub saved :Fn<String;Unit> = box:.printer")));
            success(session.submit(EvaluationSource.of(
                    "replacement-write.lyra", "box:.printer := quiet")));
            assertEquals("", output.toString(StandardCharsets.UTF_8));
            // The saved occurrence keeps the originally selected value.
            success(session.submit(EvaluationSource.of("replacement-saved.lyra", "(saved \"old\")")));
            assertEquals("old\n", output.toString(StandardCharsets.UTF_8));
            // A new read selects and authenticates the replacement (silent).
            success(session.submit(EvaluationSource.of(
                    "replacement-read.lyra", "box::printer[\"new\"]")));
            success(session.submit(EvaluationSource.of(
                    "replacement-again.lyra", "(box:.printer \"again\")")));
            assertEquals("old\n", output.toString(StandardCharsets.UTF_8));
            // The saved selection is still the original value, not the slot.
            success(session.submit(EvaluationSource.of(
                    "replacement-saved-again.lyra", "(saved \"still-old\")")));
            assertEquals("old\nstill-old\n", output.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void delegatedReadsPreserveSelectedClosureIdentityWithoutAuthorizingRawUse() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("occurrence-producer.lyra", """
                    import std->io
                    class Box { let @pub @mut printer :Fn<String;Unit> = io->:.println }
                    let @pub raw :Fn<String;Unit> = io->:.println
                    """)));
            success(session.submit(EvaluationSource.of(
                    "occurrence-construction.lyra", "let @mut box :Box = :Box[]")));
            EvaluationResult.Success snapshot = success(session.submit(EvaluationSource.of(
                    "occurrence-snapshot.lyra", "box:.printer")));
            assertInstanceOf(ValueSnapshot.Function.class, snapshot.value().orElseThrow().data());
            EvaluationResult.Success aliases = success(session.submit(EvaluationSource.of(
                    "occurrence-snapshot-aliases.lyra",
                    "Tuple[box:.printer box:.printer]")));
            ValueSnapshot.Aggregate aliasTuple = assertInstanceOf(ValueSnapshot.Aggregate.class,
                    aliases.value().orElseThrow().data());
            assertInstanceOf(ValueSnapshot.Function.class, aliasTuple.elements().get(0).data());
            assertEquals(new ValueSnapshot.Reference("fn1"), aliasTuple.elements().get(1).data());
            assertScalar("true", success(session.submit(EvaluationSource.of(
                    "occurrence-repeat.lyra", "(eq? box:.printer box:.printer)"))));
            assertScalar("true", success(session.submit(EvaluationSource.of(
                    "occurrence-raw.lyra", "(eq? box:.printer raw)"))));
            assertScalar("true", success(session.submit(EvaluationSource.of(
                    "occurrence-same.lyra",
                    "{ let held :Fn<String;Unit> = box:.printer (eq? held held) }"))));
            success(session.submit(EvaluationSource.of(
                    "occurrence-replacement.lyra", "box:.printer := (=> |s| ())")));
            assertScalar("false", success(session.submit(EvaluationSource.of(
                    "occurrence-changed.lyra", "(eq? box:.printer raw)"))));
            EvaluationResult rejected = session.submit(EvaluationSource.of(
                    "occurrence-raw-invoke.lyra", "(raw \"still-raw\")"));
            assertEquals("LYR-LINK", assertInstanceOf(
                    EvaluationResult.RuntimeFailure.class, rejected, rejected::toString).code());
        }
    }

    @Test
    void consumerLambdaCanReplaceCallableFieldWhileSavedSelectionStaysOld() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("consumer-replacement-producer.lyra", """
                    import std->io
                    class Box { let @pub @mut f :Fn<;I32> = (=> || 1I32) }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "consumer-replacement-construction.lyra", "let @mut box :Box = :Box[]")));
            success(session.submit(EvaluationSource.of(
                    "consumer-replacement-save.lyra", "let saved :Fn<;I32> = box:.f")));
            success(session.submit(EvaluationSource.of(
                    "consumer-replacement-write.lyra", "box:.f := (=> || 2I32)")));
            assertScalar("1", success(session.submit(EvaluationSource.of(
                    "consumer-replacement-old.lyra", "(saved)"))));
            assertScalar("2", success(session.submit(EvaluationSource.of(
                    "consumer-replacement-new.lyra", "(box:.f)"))));
            assertScalar("2", success(session.submit(EvaluationSource.of(
                    "consumer-replacement-new-again.lyra", "box::f[]"))));
        }
    }

    @Test
    void nestedCallableLeavesInsideAggregateMembersStayRawAndRejected() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SessionOptions options = SessionOptions.builder().ioEnvironment(new RuntimeIoEnvironment(
                new ByteArrayInputStream(new byte[0]), output, output, StandardCharsets.UTF_8)).build();
        try (LyraSession session = LyraSession.open(options)) {
            success(session.submit(EvaluationSource.of("nested-producer.lyra", """
                    import std->io
                    class Box { let @pub pair :Tuple<Fn<String;Unit>,I32> = Tuple[io->:.println 7I32] }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "nested-construction.lyra", "let box :Box = :Box[]")));
            // The data leaf of the same member is still readable.
            assertScalar("7", success(session.submit(EvaluationSource.of(
                    "nested-data.lyra", "box:.pair:.1"))));
            // Only an exact callable field route carries delegation evidence;
            // a callable leaf selected through an aggregate projection stays
            // the raw imported closure and is rejected before invocation.
            EvaluationResult result = session.submit(EvaluationSource.of(
                    "nested-leaf.lyra", "(box:.pair:.0 \"nested\")"));
            EvaluationResult.RuntimeFailure failure = assertInstanceOf(
                    EvaluationResult.RuntimeFailure.class, result, result::toString);
            assertEquals("LYR-LINK", failure.code());
            assertEquals("", output.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void privateCallableMembersStayLexicallyPrivateAcrossGenerations() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("private-producer.lyra", """
                    import std->io
                    class Box { let printer :Fn<String;Unit> = io->:.println }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "private-construction.lyra", "let box :Box = :Box[]")));
            EvaluationResult result = session.submit(EvaluationSource.of(
                    "private-read.lyra", "box:.printer"));
            assertInstanceOf(EvaluationResult.CompilationFailure.class, result, result::toString);
        }
    }

    @Test
    void nestedNominalCallableRoutesDelegateThroughTheObjectAnchor() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SessionOptions options = SessionOptions.builder().ioEnvironment(new RuntimeIoEnvironment(
                new ByteArrayInputStream(new byte[0]), output, output, StandardCharsets.UTF_8)).build();
        try (LyraSession session = LyraSession.open(options)) {
            success(session.submit(EvaluationSource.of("nested-route-producer.lyra", """
                    import std->io
                    class Inner { let @pub printer :Fn<String;Unit> = io->:.println }
                    class Outer { let @pub inner :Inner = :Inner[] }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "nested-route-construction.lyra", "let outer :Outer = :Outer[]")));
            assertEquals("", output.toString(StandardCharsets.UTF_8));
            // The nested nominal rides the session anchor; its callable member
            // read carries its own occurrence-scoped route evidence.
            success(session.submit(EvaluationSource.of(
                    "nested-route-invoke.lyra", "outer:.inner::printer[\"nested\"]")));
            assertEquals("nested\n", output.toString(StandardCharsets.UTF_8));
            success(session.submit(EvaluationSource.of(
                    "nested-route-saved.lyra",
                    "{ let held :Fn<String;Unit> = outer:.inner:.printer (held \"saved-nested\") }")));
            assertEquals("nested\nsaved-nested\n", output.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void retainedMixedTupleInitializerPreservesScalarAndInvocableLambdaAcrossGenerations() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("mixed-tuple-producer.lyra", """
                    class C { let @pub x :Tuple<I32,Fn<;I32>> = Tuple[1 (=> || 2)] }
                    """)));
            success(session.submit(EvaluationSource.of("mixed-tuple-construction.lyra", "let box :C = :C[]")));
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

    /**
     * Runtime counterpart of the phase-4 crash shape: a {@code @nil}-element
     * array indexed inside a retained member initializer.  The selected member
     * evaluates to the non-nil element, and the retained array still exposes
     * both elements, including the exact nil value.
     */
    @Test
    void nilableElementArrayIndexInitializerConstructsAndObservesAcrossGenerations() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("nilable-index-producer.lyra", """
                    class C {
                        let @pub all :Array<@nil I32> = Array<@nil I32>[#NIL 1I32]
                        let @pub picked :@nil I32 = Array<@nil I32>[#NIL 1I32][1I32]
                    }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "nilable-index-construction.lyra", "let box :C = :C[]")));
            EvaluationResult.Success picked = success(session.submit(EvaluationSource.of(
                    "nilable-index-picked.lyra", "box:.picked")));
            assertEquals("1", assertInstanceOf(ValueSnapshot.Scalar.class,
                    picked.value().orElseThrow().data()).value());
            EvaluationResult.Success first = success(session.submit(EvaluationSource.of(
                    "nilable-index-first.lyra", "box:.all[0I32]")));
            assertInstanceOf(ValueSnapshot.Nil.class, first.value().orElseThrow().data());
            EvaluationResult.Success second = success(session.submit(EvaluationSource.of(
                    "nilable-index-second.lyra", "box:.all[1I32]")));
            assertEquals("1", assertInstanceOf(ValueSnapshot.Scalar.class,
                    second.value().orElseThrow().data()).value());
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
                        let @pub nested :Inner = :Inner[]
                    }
                    """)));
            success(session.submit(EvaluationSource.of("algebra-2.lyra", "let value :Defaults = :Defaults[]")));
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
                        let @pub nested :Inner = :Inner[Array<I32>[9I32]]
                        let @pub choice :Bool = (or candidate #F)
                        let @pub looped :Unit = while[|| #F || ()]
                    }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "typed-children-2.lyra", "let value :TypedDefaults = :TypedDefaults[]")));
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
                    "local-cell-2.lyra", "let value :CounterFactory = :CounterFactory[]")));
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
                    let first :Counter = :Counter[]
                    let second :Counter = :Counter[]
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
                            { selected := Array<I32>[9I32] 1I32 } -> 0I32
                            _ -> selected[0I32])
                    }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "alternative-scope-2.lyra", "let value :Choice = :Choice[]")));
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
            success(session.submit(EvaluationSource.of("nested-lambda-2.lyra", "let box :Box = :Box[]")));
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
            success(session.submit(EvaluationSource.of("callable-value-2.lyra", "let box :Box = :Box[]")));
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
            success(session.submit(EvaluationSource.of("shadow-3.lyra", "let box :Box = :Box[]")));
            assertScalar("41", success(session.submit(EvaluationSource.of(
                    "shadow-4.lyra", "box:.value"))));
        }
    }

    @Test
    void retainedFactoryObservesCurrentMethodSlotButSavedCallableKeepsOriginalSlot() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("slot-1.lyra", """
                    class Counter { let @pub @mut read :Fn<;I32> = (=> || 1I32) }
                    let @mut counter :Counter = :Counter[]
                    let saved :Fn<;I32> = counter:.read
                    class Box {
                        let @pub current :I32 = counter::read[]
                        let @pub original :I32 = (saved)
                    }
                    """)));
            success(session.submit(EvaluationSource.of("slot-2.lyra",
                    "counter:.read := (=> || 7I32)")));
            success(session.submit(EvaluationSource.of("slot-3.lyra", "let box :Box = :Box[]")));
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
            success(session.submit(EvaluationSource.of("callable-routes-2.lyra", "let routes :Routes = :Routes[]")));
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
                    let @pub matched :I32 = (match 1I32 1I32 -> 7I32 _ -> { writes := 99I32 0I32 })
                }
                """;
        try (LyraSession retained = LyraSession.open(options); LyraSession ordinary = LyraSession.open(options)) {
            success(retained.submit(EvaluationSource.of("lazy-retained-1.lyra", definitions)));
            assertScalar("777", success(retained.submit(EvaluationSource.of("lazy-retained-2.lyra", """
                    let choices :Choices = :Choices[]
                    (+ (* (+ (* choices:.conditional 10I32) choices:.coalesced) 10I32) choices:.matched)
                    """))));
            assertScalar("0", success(retained.submit(EvaluationSource.of("lazy-retained-3.lyra", "writes"))));

            assertScalar("777", success(ordinary.submit(EvaluationSource.of("lazy-ordinary.lyra", definitions + """
                    let choices :Choices = :Choices[]
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
                    "aggregate-factory-2.lyra", "let bag :Bag = :Bag[]")));
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
                    "nested-aggregate-factory-2.lyra", "let bag :Bag = :Bag[]")));
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
                    "aggregate-alias-factory-2.lyra", "let bag :Bag = :Bag[]")));
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
                    "aggregate-call-factory-2.lyra", "let bag :Bag = :Bag[]")));
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
                    "callable-call-factory-2.lyra", "let box :Box = :Box[]")));
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
                    let make :Fn<;Inner> = (=> || :Inner[])
                    class Outer { let @pub inner :Inner = ::make[] }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "nested-construction-2.lyra", "let outer :Outer = :Outer[]")));
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
                    "intrinsic-member-2.lyra", "let printer :Printer = :Printer[]")));
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
                    "intrinsic-default-2.lyra", "let printer :Printer = :Printer[]")));
            assertEquals("retained\n", output.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void retainedNilDefaultsKeepProducerCertifiedProvenance() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of(
                    "nil-default-1.lyra", "class Maybe { let @pub @nil value :I32 = #NIL }")));
            success(session.submit(EvaluationSource.of(
                    "nil-default-2.lyra", "let maybe :Maybe = :Maybe[]")));
            EvaluationResult.Success result = success(session.submit(EvaluationSource.of(
                    "nil-default-3.lyra", "maybe:.value")));
            assertInstanceOf(ValueSnapshot.Nil.class,
                    result.value().orElseThrow().data());
        }
    }

    /**
     * Issue #8: cross-generation nilable member reads must derive their
     * contracts independently at sealing and IR validation and execute under
     * current nil rules, without any valid form reaching {@code LYC-IR-003}.
     */
    @Test
    void nilableMemberReadContractsCompileAndExecuteAcrossGenerations() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of("nilable-member-1.lyra", """
                    class Empty { let @pub @nil n :I32 = #NIL }
                    class Full { let @pub @nil n :I32 = 5I32 }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "nilable-member-2.lyra", "let empty :Empty = :Empty[] let full :Full = :Full[]")));
            EvaluationResult.Success annotated = success(session.submit(EvaluationSource.of(
                    "nilable-member-3.lyra", """
                    let v :@nil I32 = empty:.n
                    v
                    """)));
            assertInstanceOf(ValueSnapshot.Nil.class, annotated.value().orElseThrow().data());
            assertScalar("0", success(session.submit(EvaluationSource.of(
                    "nilable-member-4.lyra", """
                    let v :@nil I32 = empty:.n
                    ((!= v #NIL) -> 1I32 : 0I32)
                    """))));
            assertScalar("1", success(session.submit(EvaluationSource.of(
                    "nilable-member-5.lyra", """
                    let v :@nil I32 = full:.n
                    ((!= v #NIL) -> 1I32 : 0I32)
                    """))));
            assertScalar("7", success(session.submit(EvaluationSource.of(
                    "nilable-member-6.lyra", "(empty:.n : 7I32)"))));
            assertScalar("5", success(session.submit(EvaluationSource.of(
                    "nilable-member-7.lyra", "(full:.n : 7I32)"))));
            assertScalar("5", success(session.submit(EvaluationSource.of(
                    "nilable-member-8.lyra", "(full:.n narrowed -> narrowed : 0I32)"))));
            assertScalar("0", success(session.submit(EvaluationSource.of(
                    "nilable-member-9.lyra", "(empty:.n narrowed -> narrowed : 0I32)"))));
            assertScalar("1", success(session.submit(EvaluationSource.of(
                    "nilable-member-10.lyra", "(match empty:.n #NIL -> 1I32 _ -> 0I32)"))));
            assertScalar("9", success(session.submit(EvaluationSource.of(
                    "nilable-member-11.lyra", "(match full:.n #NIL -> 1I32 _ -> 9I32)"))));
        }
    }

    @Test
    void nilableMemberReadNegativesStayStructuredAcrossGenerations() {
        try (LyraSession session = LyraSession.open()) {
            success(session.submit(EvaluationSource.of(
                    "nilable-negative-1.lyra", "class Empty { let @pub @nil n :I32 = #NIL }")));
            success(session.submit(EvaluationSource.of(
                    "nilable-negative-2.lyra", "let empty :Empty = :Empty[]")));

            EvaluationResult.CompilationFailure mismatched = assertInstanceOf(
                    EvaluationResult.CompilationFailure.class, session.submit(EvaluationSource.of(
                            "nilable-negative-3.lyra", "let v :I32 = empty:.n")));
            assertEquals(CompilerDiagnosticCodes.TYPE_MISMATCH,
                    mismatched.diagnostics().getFirst().code());
            assertTrue(mismatched.diagnostics().stream().noneMatch(diagnostic ->
                            diagnostic.code().value().equals("LYC-IR-003")),
                    "mismatched annotation reached the IR boundary");

            EvaluationResult.CompilationFailure inventedNarrowing = assertInstanceOf(
                    EvaluationResult.CompilationFailure.class, session.submit(EvaluationSource.of(
                            "nilable-negative-4.lyra", "(match empty:.n 4I32 -> 1I32 _ -> 0I32)")));
            assertEquals(CompilerDiagnosticCodes.TYPE_NIL_CONTEXT,
                    inventedNarrowing.diagnostics().getFirst().code());

            EvaluationResult.CompilationFailure nilReceiver = assertInstanceOf(
                    EvaluationResult.CompilationFailure.class, session.submit(EvaluationSource.of(
                            "nilable-negative-5.lyra", "let @nil maybe :Empty = #NIL let v :@nil I32 = maybe:.n")));
            assertEquals(CompilerDiagnosticCodes.TYPE_INVALID_ACCESS,
                    nilReceiver.diagnostics().getFirst().code());
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
                            let @mut bag :Bag = :Bag[]
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
                    "constructor-aggregate-2.lyra", "let bag :Bag = :Bag[Array<I32>[6 7]]")));
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
                    "constructor-effect-2.lyra", "let box :Box = :Box[]")));
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
                            self:.built := :Inner[]
                        })
                    }
                    """)));
            success(session.submit(EvaluationSource.of(
                    "constructor-compositions-2.lyra", "let box :Box = :Box[41]")));
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
                        Leaf = (=> |value :I32| { self:.deep := :Deep[value] })
                    }
                    class Middle {
                        let @pub leaf :Leaf
                        Middle = (=> |value :I32| { self:.leaf := :Leaf[value] })
                    }
                    class Outer {
                        let @pub middle :Middle
                        Outer = (=> || { self:.middle := :Middle[7] })
                    }
                    let producerOuter :Outer = :Outer[]
                    """)));
            success(session.submit(EvaluationSource.of(
                    "nested-constructor-contexts-2.lyra",
                    "let retainedOuter :Outer = :Outer[]")));
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
                    let ordered :Ordered = :Ordered[(::mark[1]) (::mark[2])]
                    let sequence :Sequence = :Sequence[::mark[6]]
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
                    let sameSubmission :Once = :Once[]
                    """)));
            assertScalar("13", success(session.submit(EvaluationSource.of(
                    "constructor-once-2.lyra", """
                    let first :Once = :Once[]
                    let second :Once = :Once[]
                    (+ (* rootEffects 10) defaultEffects)
                    """))));
            assertScalar("14", success(session.submit(EvaluationSource.of(
                    "constructor-once-3.lyra", """
                    let third :Once = :Once[]
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
                                :ArgumentFailure[::touch[4] (% 1 divisor)]
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
                            "let stagedDefault :DefaultFailure = :DefaultFailure[]")));
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
                            "let stagedConstructor :ConstructorFailure = :ConstructorFailure[]")));
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
                    let stagedArgument :ArgumentFailure = :ArgumentFailure[5 6]
                    let stagedDefault :DefaultFailure = :DefaultFailure[]
                    let stagedConstructor :ConstructorFailure = :ConstructorFailure[]
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
                    let outer :Outer = :Outer[]
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
                    "mutable-callable-default-2.lyra", "let first :C = :C[] first:.value"))));
            assertScalar("9", success(session.submit(EvaluationSource.of(
                    "mutable-callable-default-3.lyra", """
                            selected := (=> || 9I32)
                            let second :C = :C[]
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
                    let box :Box = :Box[]
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
                            let @mut counter :Counter = :Counter[]
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
                    "snapshot-struct.lyra", "struct Pair { let left :I32 let right :I32 } :Pair[2 3]")));
            ValueSnapshot.Aggregate struct = assertInstanceOf(ValueSnapshot.Aggregate.class,
                    structResult.value().orElseThrow().data());
            assertEquals(AggregateKind.STRUCT, struct.kind());
            assertEquals("Pair", struct.alias().orElseThrow());
            assertEquals(List.of("2", "3"), struct.elements().stream()
                    .map(ValueSnapshot::data).map(ValueSnapshot.Scalar.class::cast)
                    .map(ValueSnapshot.Scalar::value).toList());

            EvaluationResult.Success classResult = success(session.submit(EvaluationSource.of(
                    "snapshot-class.lyra", "class Secret { let value :I32 = 9 } :Secret[]")));
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
