package io.mindspice.lyra.repl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase-13 common-corpus coverage for the managed single-owner console adapter. */
class CrossSurfaceManagedConsoleTest {
    @Test
    void commonCorpusExecutesOnTheManagedConsoleOwner() {
        var sources = new CrossSurfaceCorpus.Sources();
        var output = CrossSurfaceCorpus.output();
        try (var console = ManagedConsoleSession.open(
                CrossSurfaceCorpus.options(sources, output).build())) {
            long revision = console.revision().value();
            for (CrossSurfaceCorpus.Step step : CrossSurfaceCorpus.program()) {
                if (CrossSurfaceCorpus.isMutation(step)) {
                    sources.mutate(CrossSurfaceCorpus.mutation(step));
                    continue;
                }
                ConsoleSession.Evaluation result = CrossSurfaceCorpus.isReload(step)
                        ? console.reload(CrossSurfaceCorpus.reloadTarget(step))
                        : console.evaluate(EvaluationSource.of(step.label() + ".lyra", step.source()));
                assertExpectation(step, result);
                if (result.status() == ConsoleSession.EvaluationStatus.SUCCESS) {
                    assertTrue(result.revision().value() >= revision, step.label());
                    revision = result.revision().value();
                }
                assertTrue(console.activeEvaluationId().isEmpty(), step.label());
            }
            assertTrue(CrossSurfaceCorpus.text(output).contains("héllo 😀\n"));
        }
    }

    private static void assertExpectation(CrossSurfaceCorpus.Step step,
                                          ConsoleSession.Evaluation result) {
        switch (step.expectation()) {
            case SUCCESS_NO_VALUE -> {
                assertEquals(ConsoleSession.EvaluationStatus.SUCCESS,
                        result.status(), step.label() + " => " + result);
            }
            case SUCCESS_SCALAR -> {
                assertEquals(ConsoleSession.EvaluationStatus.SUCCESS,
                        result.status(), step.label() + " => " + result);
                String display = result.value().orElseThrow().display();
                String expected = step.expected().equals("replacement")
                        || step.expected().equals("one")
                        || step.expected().equals("prog-input")
                        ? "\"" + step.expected() + "\"" : step.expected();
                assertEquals(expected, display, step.label());
            }
            case SUCCESS_FUNCTION -> {
                assertEquals(ConsoleSession.EvaluationStatus.SUCCESS,
                        result.status(), step.label() + " => " + result);
                assertFalse(result.value().orElseThrow().display().isBlank(), step.label());
            }
            case SUCCESS_AGGREGATE_TRUNCATED -> {
                assertEquals(ConsoleSession.EvaluationStatus.SUCCESS,
                        result.status(), step.label() + " => " + result);
                assertEquals("Array<I32>", result.value().orElseThrow().canonicalType());
            }
            case OWNERSHIP_DIAGNOSTIC -> {
                assertEquals(ConsoleSession.EvaluationStatus.COMPILATION_FAILURE,
                        result.status(), step.label() + " => " + result);
                assertTrue(result.diagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.code().equals(step.expected())),
                        step.label() + " => " + result);
            }
            case RUNTIME_DIVISION -> {
                assertEquals(ConsoleSession.EvaluationStatus.RUNTIME_FAILURE,
                        result.status(), step.label() + " => " + result);
                assertTrue(result.detail().orElse("").contains("division by zero"),
                        step.label() + " => " + result);
            }
        }
    }
}
