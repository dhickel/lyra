package io.mindspice.lyra.repl;

import io.mindspice.lyra.runtime.PrimitiveType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExecutedSnapshotTest {
    @Test
    void rangesRemainBoundedDataAndPersistInsideAggregates() {
        var type = new io.mindspice.lyra.runtime.RangeType(PrimitiveType.I8);
        assertEquals("(127...-128:-1)", scalar(ValueSnapshot.scalar(type, ScalarKind.RANGE, "(127...-128:-1)")));
        for (String invalid : List.of("(0..3:0)", "(0..128:1)", "(00..3:1)", "(-0..3:1)", "(0....3:1)")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ValueSnapshot.scalar(type, ScalarKind.RANGE, invalid), invalid);
        }
        assertThrows(IllegalArgumentException.class,
                () -> ValueSnapshot.scalar(PrimitiveType.I8, ScalarKind.RANGE, "(0..3:1)"));
        try (var session = LyraSession.open()) {
            success(session, "let ranges = Array[(0I8..3I8:1I8)]");
            var aggregate = assertInstanceOf(ValueSnapshot.Aggregate.class, value(session, "ranges").data());
            assertEquals("(0..3:1)", scalar(aggregate.elements().getFirst()));
            assertEquals("3", scalar(value(session,
                    "let @mut n :I32 = 0 ::iter[ranges[0] || { n := (++ n) }] n")));
        }
    }

    @Test
    void resultsComeFromExecutedExpressionsAndDeclarationsHaveNoSeparateResult() {
        try (var session = LyraSession.open()) {
            assertTrue(success(session, "let @mut count :I32 = 1").value().isEmpty());
            assertEquals("2", scalar(value(session, "{ count := (++ count) count }")));
            assertEquals("2", scalar(value(session, "count")), "reading the result must not execute the entry point again");
            assertEquals(PrimitiveType.I32, value(session, "count").type());
            assertInstanceOf(ValueSnapshot.Unit.class, value(session, "()").data());
            assertFalse(session.workspaceState().bindings().containsKey("res0"));
        }
    }

    @Test
    void scalarsPreserveUnsignedMathematicalValuesAndUtf16CodeUnits() {
        try (var session = LyraSession.open()) {
            for (String text : List.of("255U8", "65535U16", "4294967295U32", "18446744073709551615U64")) {
                var snapshot = value(session, text);
                assertEquals(ScalarKind.UNSIGNED_INTEGER, assertInstanceOf(ValueSnapshot.Scalar.class, snapshot.data()).kind());
                assertEquals(text.substring(0, text.indexOf('U')), scalar(snapshot));
            }
            assertEquals("\ud800", scalar(value(session, "'\\uD800'")));
            assertEquals("a\u0000\ud800\udfff", scalar(value(session, "\"a\\0\\uD800\\uDFFF\"")));
            assertEquals("-0.0", scalar(value(session, "(- 0.0F64)")));
            assertEquals("true", scalar(value(session, "#T")));
            assertInstanceOf(ValueSnapshot.Nil.class, value(session, "{ let @nil n :I32 = #NIL n }").data());
            assertInstanceOf(ValueSnapshot.Nil.class, value(session, "{ let @nil n :Tuple<I32,String> = #NIL n }").data());
            assertInstanceOf(ValueSnapshot.Nil.class, value(session, "{ let @nil n :Fn<;I32> = #NIL n }").data());
        }
    }

    @Test
    void arraysTuplesFunctionsAndAliasesAreDataOnlyAndOrdered() {
        try (var session = LyraSession.open()) {
            var tuple = assertInstanceOf(ValueSnapshot.Aggregate.class, value(session,
                    "let a :Array<I32> = Array<I32>[3 1 2] let f :Fn<;I32> = (=> || 4) Tuple[a a f f ()]").data());
            assertEquals(AggregateKind.TUPLE, tuple.kind());
            var array = assertInstanceOf(ValueSnapshot.Aggregate.class, tuple.elements().get(0).data());
            assertEquals(List.of("3", "1", "2"), array.elements().stream().map(ExecutedSnapshotTest::scalar).toList());
            assertEquals(array.identity(), assertInstanceOf(ValueSnapshot.Reference.class, tuple.elements().get(1).data()).description());
            var function = assertInstanceOf(ValueSnapshot.Function.class, tuple.elements().get(2).data());
            assertEquals(function.identity(), assertInstanceOf(ValueSnapshot.Reference.class, tuple.elements().get(3).data()).description());
            assertInstanceOf(ValueSnapshot.Unit.class, tuple.elements().get(4).data());
            // A function snapshot must not invoke a function whose body would fail.
            assertInstanceOf(ValueSnapshot.Function.class, value(session,
                    "let fail :Fn<I32;I32> = (=> |x| (% 1 x)) fail").data());
        }
    }

    @Test
    void displayBudgetsBoundWidthDepthAndEscapedOutput() {
        for (SnapshotLimits limits : List.of(new SnapshotLimits(0, 100, 1000),
                new SnapshotLimits(6, 2, 1000), new SnapshotLimits(6, 100, 110))) {
            try (var session = LyraSession.open(SessionOptions.builder().snapshotLimits(limits).build())) {
                var value = value(session, "Array<Array<I32>>[Array<I32>[1 2 3] Array<I32>[4] Array<I32>[5]]");
                assertTrue(value.containsTruncation());
                assertTrue(value.renderedCharacterCount() <= limits.maxRenderedCharacters());
                limits.validate(value);
            }
        }
        try (var session = LyraSession.open(SessionOptions.builder().snapshotLimits(new SnapshotLimits(6, 100, 60)).build())) {
            assertInstanceOf(ValueSnapshot.Truncated.class, value(session, "\"" + "\\uD800".repeat(30) + "\"").data());
        }
    }

    @Test
    void impossibleSnapshotBudgetRejectsBeforeMutatingRetainedStorage() {
        try (var session = LyraSession.open(SessionOptions.builder().snapshotLimits(new SnapshotLimits(6, 100, 1)).build())) {
            success(session, "let @mut count :I32 = 1");
            var before = session.workspaceState();
            assertInstanceOf(EvaluationResult.CompilationFailure.class, session.submit("budget.lyra", "count := 7 count"));
            assertEquals(before, session.workspaceState());
            // A declaration needs no displayed result, and its initializer
            // proves that the rejected source did not modify count.
            success(session, "let zero :I32 = 0 let check :I32 = ((== count 1) -> 1 : (% 1 zero))");
        }
    }

    @Test
    void returnedSnapshotRemainsUsableAfterGenerationResetAndClose() {
        ValueSnapshot snapshot;
        try (var session = LyraSession.open()) {
            snapshot = value(session, "Tuple[1I32 \"text\"]");
            session.reset();
        }
        assertEquals("Tuple<I32,String>", snapshot.canonicalType());
        assertEquals("text", scalar(assertInstanceOf(ValueSnapshot.Aggregate.class, snapshot.data()).elements().get(1)));
    }

    private static EvaluationResult.Success success(LyraSession session, String source) {
        var result = session.submit("snapshot.lyra", source);
        return assertInstanceOf(EvaluationResult.Success.class, result, result.toString());
    }
    private static ValueSnapshot value(LyraSession session, String source) { return success(session, source).value().orElseThrow(); }
    private static String scalar(ValueSnapshot value) { return assertInstanceOf(ValueSnapshot.Scalar.class, value.data()).value(); }
}
