package io.mindspice.lyra.repl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for sequential reloads after retained callable imports. */
class ReloadSequenceReproTest {
    @Test
    void sequentialReloadsRetainOldCallablesAndAcceptChangedImportTopology() {
        var sources = new CrossSurfaceCorpus.Sources();
        var output = CrossSurfaceCorpus.output();
        try (var session = LyraSession.open(
                CrossSurfaceCorpus.options(sources, output).build())) {
            success(session.submit("counter.lyra",
                    "import counter import counter->{bump as oldBump}"));
            assertEquals("1", scalar(session, "counter->::bump[]"));
            sources.mutate("counter->v2");
            EvaluationResult.Success counterReload = success(session.reload("counter"));
            assertComplete(counterReload.initializerProgress());
            assertEquals("110", scalar(session, "counter->::bump[]"));
            assertEquals("2", scalar(session, "(oldBump)"));

            success(session.submit("errors.lyra",
                    "import errors import errors->{fail as oldFail}"));
            sources.mutate("errors->v2");
            EvaluationResult.Success errorsReload = success(session.reload("errors"));
            assertComplete(errorsReload.initializerProgress());
            assertInstanceOf(EvaluationResult.RuntimeFailure.class,
                    session.submit("old-error.lyra", "(oldFail 0)"));
            assertInstanceOf(EvaluationResult.RuntimeFailure.class,
                    session.submit("new-error.lyra", "errors->::late[]"));

            assertEquals("1", scalar(session,
                    "import top import top->{read as oldTopRead} top->::read[]"));
            sources.mutate("top->v2");
            EvaluationResult.Success topReload = success(session.reload("top"));
            assertComplete(topReload.initializerProgress());
            assertEquals("9", scalar(session, "top->::read[]"));
            assertEquals("1", scalar(session, "(oldTopRead)"));
        }
    }

    private static void assertComplete(InitializerProgress progress) {
        assertTrue(progress.scheduled().size() > 0, progress.toString());
        assertEquals(progress.scheduled(), progress.attempted());
        assertEquals(progress.scheduled(), progress.completed());
    }

    private static EvaluationResult.Success success(EvaluationResult result) {
        return assertInstanceOf(EvaluationResult.Success.class, result, result.toString());
    }

    private static String scalar(LyraSession session, String source) {
        EvaluationResult.Success result = success(session.submit("sequence.lyra", source));
        return assertInstanceOf(ValueSnapshot.Scalar.class,
                result.value().orElseThrow().data()).value();
    }
}
