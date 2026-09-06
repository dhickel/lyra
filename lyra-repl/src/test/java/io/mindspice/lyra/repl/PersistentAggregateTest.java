package io.mindspice.lyra.repl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PersistentAggregateTest {
    @Test
    void arraysKeepAliasesWhileRebindingAndPrivateReplacementSelectNewStorage() {
        try (var session = LyraSession.open()) {
            success(session, "let @mut items :Array<I32> = Array<I32>[1 2]");
            var original = session.workspaceState().bindings().get("items");
            success(session, "let @mut alias :Array<I32> = items");
            assertEquals("true", scalar(session, "(eq? items alias)"));
            success(session, "items[0] := 42");
            assertEquals("42", scalar(session, "alias[0]"));
            success(session, "items := Array<I32>[8]");
            assertEquals(original, session.workspaceState().bindings().get("items"));
            assertEquals("false", scalar(session, "(eq? items alias)"));
            assertEquals("42", scalar(session, "alias[0]"));
            success(session, "let items :String = \"replacement\"");
            assertNotEquals(original.identity(), session.workspaceState().bindings().get("items").identity());
            success(session, "alias[1] := 9");
            assertEquals("9", scalar(session, "alias[1]"));
        }
    }

    @Test
    void tupleAbiAndNestedArraysAreSharedAcrossGenerationsAndHigherOrderLocalCalls() {
        try (var session = LyraSession.open()) {
            success(session, "let @mut pair :Tuple<Array<I32>,String> = Tuple[Array<I32>[1 2] \"old\"]");
            success(session, "let @mut alias :Tuple<Array<I32>,String> = pair");
            success(session, "pair:.0[0] := 42");
            assertEquals("42", scalar(session, "alias:.0[0]"));
            assertEquals("42", scalar(session,
                    "let pick :Fn<Tuple<Array<I32>,String>;I32> = (=> |p| p:.0[0]) (pick pair)"));
            assertEquals("42", scalar(session,
                    "let pick :Fn<;I32> = (=> || pair:.0[0]) (pick)"));
            assertEquals("42", scalar(session,
                    "let get :Fn<;Tuple<Array<I32>,String>> = (=> || pair) let result = (get) result:.0[0]"));
            success(session, "let update :Fn<;Unit> = (=> || (pair:.0[1] := 9)) (update)");
            assertEquals("9", scalar(session, "alias:.0[1]"));
            success(session, "pair := Tuple[Array<I32>[7] \"new\"]");
            assertEquals("42", scalar(session, "alias:.0[0]"));
            assertEquals("7", scalar(session, "pair:.0[0]"));
            success(session, "let @mut nested :Array<Tuple<Array<I32>,String>> = Array<Tuple<Array<I32>,String>>[pair]");
            assertEquals("new", scalar(session, "nested[0]:.1"));
            success(session, "nested[0] := alias");
            assertEquals("42", scalar(session, "nested[0]:.0[0]"));
            var snapshot = value(session, "Tuple[alias:.0 alias:.0]");
            var tuple = assertInstanceOf(ValueSnapshot.Aggregate.class, snapshot.data());
            var array = assertInstanceOf(ValueSnapshot.Aggregate.class, tuple.elements().getFirst().data());
            assertEquals(array.identity(), assertInstanceOf(ValueSnapshot.Reference.class,
                    tuple.elements().getLast().data()).description());
        }
    }

    @Test
    void completedMutationsAndNewDataEscapingFailedSubmissionsSurviveWithoutPublishingNames() {
        try (var session = LyraSession.open()) {
            success(session, "let @mut items :Array<Tuple<I32,String>> = Array<Tuple<I32,String>>[Tuple[1 \"one\"]]");
            var before = session.workspaceState();
            assertInstanceOf(EvaluationResult.CompilationFailure.class, session.submit("bad.lyra",
                    "items[0] := Tuple[7 \"not run\"] let invalid :Bool = 1"));
            assertEquals(before, session.workspaceState());
            assertEquals("1", scalar(session, "items[0]:.0"));
            before = session.workspaceState();
            assertInstanceOf(EvaluationResult.RuntimeFailure.class, session.submit("fail.lyra",
                    "let hidden = Array<Tuple<I32,String>>[Tuple[42 \"escaped\"]] items := hidden let zero :I32 = 0 (% 1 zero)"));
            assertEquals(before, session.workspaceState());
            assertEquals("42", scalar(session, "items[0]:.0"));
            assertEquals("escaped", scalar(session, "items[0]:.1"));
            assertFalse(session.workspaceState().bindings().containsKey("hidden"));
            assertInstanceOf(EvaluationResult.RuntimeFailure.class, session.submit("bounds.lyra",
                    "items[0] := Tuple[9 \"completed\"] items[8]"));
            assertEquals("9", scalar(session, "items[0]:.0"));
        }
    }

    @Test
    void exactNullableAndUnsignedAggregateContractsPersist() {
        try (var session = LyraSession.open()) {
            success(session, "let @mut @nil pair :Tuple<Array<@nil U64>,@nil String> = #NIL");
            assertInstanceOf(ValueSnapshot.Nil.class, value(session, "pair").data());
            success(session, "pair := Tuple[Array<@nil U64>[18446744073709551615 #NIL] #NIL]");
            var tuple = assertInstanceOf(ValueSnapshot.Aggregate.class, value(session, "pair").data());
            var array = assertInstanceOf(ValueSnapshot.Aggregate.class, tuple.elements().getFirst().data());
            assertEquals("18446744073709551615", assertInstanceOf(ValueSnapshot.Scalar.class,
                    array.elements().getFirst().data()).value());
            assertInstanceOf(ValueSnapshot.Nil.class, array.elements().getLast().data());
            success(session, "pair := #NIL");
            assertInstanceOf(ValueSnapshot.Nil.class, value(session, "pair").data());
        }
    }

    @Test
    void mutationTypingRemainsIntactCallableSnapshotsAreDataOnlyAndResetRetiresData() {
        ValueSnapshot retained;
        try (var session = LyraSession.open()) {
            success(session, "let @pub fixed :Array<I32> = Array<I32>[1]");
            for (String source : List.of("fixed[0] := 2", "fixed := Array<I32>[2]", "let fixed = 2")) {
                assertInstanceOf(EvaluationResult.CompilationFailure.class, session.submit("immutable.lyra", source));
            }
            success(session, "let @mut pair :Tuple<I32,String> = Tuple[1 \"a\"]");
            for (String source : List.of("pair := Tuple[1 #T]", "pair:.0 := 2")) {
                assertInstanceOf(EvaluationResult.CompilationFailure.class, session.submit("types.lyra", source));
            }
            success(session, "let f :Fn<;I32> = (=> || 1) let fs = Array<Fn<;I32>>[f] let wrapped = Tuple[f]");
            assertInstanceOf(ValueSnapshot.Function.class, value(session, "f").data());
            for (String source : List.of("fs", "wrapped")) {
                var aggregate = assertInstanceOf(ValueSnapshot.Aggregate.class, value(session, source).data());
                assertInstanceOf(ValueSnapshot.Function.class, aggregate.elements().getFirst().data());
            }
            assertEquals("1", scalar(session, "(fs[0])"));
            retained = value(session, "pair");
            session.reset();
            assertTrue(session.workspaceState().bindings().isEmpty());
            assertInstanceOf(EvaluationResult.CompilationFailure.class, session.submit("retired.lyra", "pair"));
            success(session, "let pair :Tuple<I32,String> = Tuple[2 \"b\"]");
            assertEquals("2", scalar(session, "pair:.0"));
        }
        assertEquals("1", assertInstanceOf(ValueSnapshot.Scalar.class,
                assertInstanceOf(ValueSnapshot.Aggregate.class, retained.data()).elements().getFirst().data()).value());
    }

    private static EvaluationResult.Success success(LyraSession session, String source) {
        var result = session.submit("aggregate.lyra", source);
        return assertInstanceOf(EvaluationResult.Success.class, result, source + " => " + result);
    }

    private static ValueSnapshot value(LyraSession session, String source) {
        return success(session, source).value().orElseThrow();
    }

    private static String scalar(LyraSession session, String source) {
        return assertInstanceOf(ValueSnapshot.Scalar.class, value(session, source).data()).value();
    }
}
