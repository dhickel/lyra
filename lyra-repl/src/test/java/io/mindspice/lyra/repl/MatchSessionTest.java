package io.mindspice.lyra.repl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MatchSessionTest {
    @Test
    void matchSelectedClosuresRetainCellsAcrossSubmissions() {
        try (var session = LyraSession.open()) {
            success(session, """
                    let @mut count :I32 = 0
                    let choose :Fn<Bool;Fn<;I32>> = (=> |enabled|
                      (match enabled
                        #T -> (=> || { count := (+ count 1) count })
                        _ -> (=> || count)))
                    """);
            success(session, "let next :Fn<;I32> = (choose #T)");
            assertEquals("1", scalar(session, "(next)"));
            success(session, "count := 40");
            assertEquals("41", scalar(session, "(next)"));
            success(session, "let count :String = \"replacement\"");
            assertEquals("42", scalar(session, "(next)"));
            assertEquals("replacement", scalar(session, "count"));
        }
    }

    @Test
    void conditionalMatchPreservesLazyEffectsAndFailedPublication() {
        try (var session = LyraSession.open()) {
            success(session, "let @mut count :I32 = 0 let zero :I32 = 0");
            assertEquals("7", scalar(session, """
                    (cond
                      { count := (+ count 1) #F } -> (% 1 zero)
                      { count := (+ count 1) #T } -> 7I32
                      { count := 99 #T } -> 8I32
                      _ -> 9I32)
                    """));
            assertEquals("2", scalar(session, "count"));
            var before = session.workspaceState();
            var result = session.submit("match-failure.lyra", """
                    let unpublished :I32 = (match count
                      2 when { count := (+ count 1) #T } -> (% 1 zero)
                      _ -> 0)
                    """);
            assertEquals("LYR-ARITH", assertInstanceOf(EvaluationResult.RuntimeFailure.class, result).code());
            assertEquals(before, session.workspaceState());
            assertEquals("3", scalar(session, "count"));
            assertInstanceOf(EvaluationResult.CompilationFailure.class,
                    session.submit("missing.lyra", "unpublished"));
        }
    }

    private static EvaluationResult.Success success(LyraSession session, String source) {
        var result = session.submit("match.lyra", source);
        return assertInstanceOf(EvaluationResult.Success.class, result, source + " => " + result);
    }

    private static String scalar(LyraSession session, String source) {
        return assertInstanceOf(ValueSnapshot.Scalar.class,
                success(session, source).value().orElseThrow().data()).value();
    }
}
