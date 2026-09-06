package io.mindspice.lyra.repl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PersistentScalarTest {
    @Test
    void privateCounterUsesItsOriginalTypedStorage() {
        try (var session = LyraSession.open()) {
            var declaration = success(session, "let @mut count :I32 = 1");
            assertTrue(declaration.value().isEmpty());
            BindingMetadata first = session.workspaceState().bindings().get("count");
            success(session, "count := 2");
            assertEquals("2", scalar(success(session, "count")));
            assertEquals(first, session.workspaceState().bindings().get("count"));
            assertEquals("I32", success(session, "count").value().orElseThrow().canonicalType());
            success(session, "count := (+ count 40)");
            assertEquals("42", scalar(success(session, "count")));
        }
    }

    @Test
    void replacementSelectsNewStorageWhileClosuresInTheNewUnitKeepTheirSelectedOldLink() {
        try (var session = LyraSession.open()) {
            success(session, "let @mut count :I32 = 1");
            BindingMetadata first = session.workspaceState().bindings().get("count");
            assertEquals("2", scalar(success(session,
                    "let increment :Fn<;I32> = (=> || { count := (+ count 1) count }) (increment)")));
            assertEquals("2", scalar(success(session, "count")));
            success(session, "let count :String = \"replacement\"");
            assertNotEquals(first.identity(), session.workspaceState().bindings().get("count").identity());
            assertEquals("replacement", scalar(success(session, "count")));
        }
    }

    @Test
    void originalSharedCellRemainsTheStorageAcrossSubmissions() {
        try (var session = LyraSession.open()) {
            success(session, "let @mut count :I32 = 1 let increment :Fn<;I32> = (=> || { count := (++ count) count }) (increment)");
            success(session, "count := 8");
            assertEquals("8", scalar(success(session, "count")));
        }
    }

    @Test
    void compileAndRuntimeFailuresDoNotPublishButCompletedMutationSurvives() {
        try (var session = LyraSession.open()) {
            success(session, "let @mut count :I32 = 1");
            var before = session.workspaceState();
            assertInstanceOf(EvaluationResult.CompilationFailure.class,
                    session.submit("failure.lyra", "count := 7 let invalid :Bool = 1"));
            assertEquals(before, session.workspaceState());
            assertEquals("1", scalar(success(session, "count")));
            var revision = session.workspaceState().revision();
            assertInstanceOf(EvaluationResult.RuntimeFailure.class, session.submit("failure.lyra",
                    "count := 7 let hidden = 9 let zero :I32 = 0 (% 1 zero)"));
            assertEquals(revision, session.workspaceState().revision());
            assertFalse(session.workspaceState().bindings().containsKey("hidden"));
            assertEquals("7", scalar(success(session, "count")));
            success(session, "let hidden :I32 = 4");
            assertEquals("4", scalar(success(session, "hidden")));
        }
    }

    @Test
    void exactScalarPayloadsAndNilSurviveAssignment() {
        for (String[] fixture : new String[][] {
                {"I8", "1", "127"}, {"I16", "1", "32767"}, {"I32", "1", "2147483647"},
                {"I64", "1", "9223372036854775807"}, {"U8", "1", "255"}, {"U16", "1", "65535"},
                {"U32", "1", "4294967295"}, {"U64", "1", "18446744073709551615"},
                {"F32", "1.0", "2.5"}, {"F64", "1.0", "2.5"}, {"Bool", "#F", "#T"},
                {"Char", "'a'", "'b'"}, {"String", "\"a\"", "\"b\""}, {"Unit", "()", "()"}}) {
            try (var session = LyraSession.open()) {
                success(session, "let @mut @nil value :" + fixture[0] + " = #NIL");
                assertInstanceOf(ValueSnapshot.Nil.class, success(session, "value").value().orElseThrow().data());
                success(session, "value := " + fixture[1]);
                assertFalse(success(session, "value").value().orElseThrow().data() instanceof ValueSnapshot.Nil);
                success(session, "value := " + fixture[2]);
                var actual = success(session, "value");
                assertEquals("@nil" + fixture[0], actual.value().orElseThrow().canonicalType());
                if (!fixture[0].equals("Unit")) {
                    String expected = switch (fixture[0]) {
                        case "Bool" -> "true";
                        case "Char", "String" -> "b";
                        default -> fixture[2];
                    };
                    assertEquals(expected, scalar(actual));
                }
                success(session, "value := #NIL");
                assertInstanceOf(ValueSnapshot.Nil.class, success(session, "value").value().orElseThrow().data());
            }
        }
    }

    @Test
    void immutableAndPublicProtectionAndResetRemainEnforced() {
        try (var session = LyraSession.open()) {
            success(session, "let @pub fixed :I32 = 3");
            assertInstanceOf(EvaluationResult.CompilationFailure.class, session.submit("bad.lyra", "fixed := 4"));
            assertInstanceOf(EvaluationResult.CompilationFailure.class, session.submit("bad.lyra", "let fixed = 4"));
            success(session, "let items :Array<I32> = Array<I32>[1]");
            assertInstanceOf(ValueSnapshot.Aggregate.class, success(session, "items").value().orElseThrow().data());
            session.reset();
            assertTrue(session.workspaceState().bindings().isEmpty());
            assertInstanceOf(EvaluationResult.CompilationFailure.class, session.submit("gone.lyra", "fixed"));
            success(session, "let fixed :I32 = 4");
            assertEquals("4", scalar(success(session, "fixed")));
        }
    }

    private static EvaluationResult.Success success(LyraSession session, String source) {
        EvaluationResult result = session.submit("scalar.lyra", source);
        return assertInstanceOf(EvaluationResult.Success.class, result, source + " => " + result);
    }

    private static String scalar(EvaluationResult.Success result) {
        return assertInstanceOf(ValueSnapshot.Scalar.class, result.value().orElseThrow().data()).value();
    }
}
