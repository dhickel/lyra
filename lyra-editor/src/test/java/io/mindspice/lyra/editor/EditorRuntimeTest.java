package io.mindspice.lyra.editor;

import io.mindspice.lyra.repl.ConsoleSession;
import com.sun.jdi.request.StepRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class EditorRuntimeTest {
    @TempDir Path root;
    @Test void sourceCompilesInSeparateJvmAndReplRetainsInitializedStateAndNonExecutingTypeQueries() throws Exception {
        Path source = root.resolve("main.lyra");
        Files.writeString(source, "import std->io as io\nio->::println[\"initialized\"]\n"
                + "let @mut count :I32 = 0\nlet next :Fn<;I32> = (=> || { count := (+ count 1) count })\n");
        StringBuffer output = new StringBuffer();
        try (var engine = EditorRuntime.start(WorkspaceSettings.open(root), false, output::append, pause -> {}, status -> {})) {
            success(engine.load(source));
            assertEquals("1", success(engine.evaluate("::next[]")).value().orElseThrow().display());
            assertEquals("2", success(engine.evaluate("::next[]")).value().orElseThrow().display());
            assertEquals(ConsoleSession.QueryStatus.OK, engine.type("count := 99").status());
            assertEquals("2", success(engine.evaluate("count")).value().orElseThrow().display());
            assertTrue(engine.bindings().bindings().stream().anyMatch(binding -> binding.name().equals("next")));
            assertEquals(1, output.toString().split("initialized", -1).length - 1);
        }
    }
    @Test void programInputIsSeparateFromReplAndCancellationLeavesSessionUsable() throws Exception {
        try (var engine = EditorRuntime.start(WorkspaceSettings.open(root), false, text -> {}, pause -> {}, status -> {});
             var worker = Executors.newSingleThreadExecutor()) {
            success(engine.evaluate("import std->io as io"));
            Future<ConsoleSession.Evaluation> reading = worker.submit(() -> engine.evaluate("io->::readLine[]"));
            engine.input("hello input");
            assertTrue(success(reading.get(15, TimeUnit.SECONDS)).value().orElseThrow().display().contains("hello input"));
            Future<ConsoleSession.Evaluation> spin = worker.submit(() -> engine.evaluate("let spin :Fn<;I32> = (=> :I32 | | ((== 1 1) -> ::spin[] : 0)) ::spin[]"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!spin.isDone() && System.nanoTime() < deadline) { engine.cancel(); Thread.sleep(30); }
            var cancelled = spin.get(5, TimeUnit.SECONDS);
            assertEquals(ConsoleSession.EvaluationStatus.CANCELLED, cancelled.status(), cancelled.toString());
            assertEquals("42", success(engine.evaluate("(+ 40 2)")).value().orElseThrow().display());
        }
    }
    @Test void debuggerStopsAtRealFunctionLineShowsArgumentAndStepsBeforeFinishing() throws Exception {
        Path source = root.resolve("main.lyra");
        Files.writeString(source, "let twice :Fn<I32;I32> = (=> |x| {\n    let doubled = (+ x x)\n    (+ doubled 1)\n})\n");
        BlockingQueue<Debugger.Pause> pauses = new LinkedBlockingQueue<>();
        StringBuffer output = new StringBuffer();
        try (var engine = EditorRuntime.start(WorkspaceSettings.open(root), true, output::append, pauses::add, output::append);
             var worker = Executors.newSingleThreadExecutor()) {
            success(engine.load(source));
            Debugger debugger = engine.debugger().orElseThrow();
            debugger.stopAtEntry(source, 2, 4);
            Future<ConsoleSession.Evaluation> run = worker.submit(() -> engine.evaluate("::twice[20]"));
            Debugger.Pause first = pauses.poll(15, TimeUnit.SECONDS);
            assertNotNull(first, output.toString());
            assertEquals(source, first.frames().getFirst().file());
            assertEquals(2, first.frames().getFirst().line());
            assertTrue(first.frames().getFirst().variables().values().stream().anyMatch(value -> value.equals("20")), first.toString());
            assertFalse(run.isDone());
            debugger.step(StepRequest.STEP_OVER);
            Debugger.Pause second = pauses.poll(10, TimeUnit.SECONDS);
            assertNotNull(second, output.toString());
            assertEquals(3, second.frames().getFirst().line());
            debugger.resume();
            assertEquals("41", success(run.get(10, TimeUnit.SECONDS)).value().orElseThrow().display());
        }
    }
    @Test void attachingSharesRealStateAndDetachingKeepsTheHostAlive() throws Exception {
        try (var session = io.mindspice.lyra.repl.LyraSession.open();
             var owner = new io.mindspice.lyra.runtime.LyraOwnerController();
             var worker = Executors.newSingleThreadExecutor()) {
            var local = ConsoleSession.local(session);
            success(local.evaluate(io.mindspice.lyra.repl.EvaluationSource.of("host", "let @pub @mut count :I32 = 1")));
            try (var server = io.mindspice.lyra.repl.remote.RemoteServer.open(io.mindspice.lyra.repl.remote.LyraSessionAdapter.of(session), owner)) {
            Future<?> attachedWork = worker.submit(() -> {
                try (var attached = EditorRuntime.attach(server.endpoint().address().host(), server.endpoint().address().port())) {
                    assertTrue(attached.isAttached());
                    success(attached.evaluate("count := 7"));
                    assertEquals("7", success(attached.evaluate("count")).value().orElseThrow().display());
                    assertThrows(java.io.IOException.class, () -> attached.input("must not reach host stdin"));
                } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!attachedWork.isDone() && System.nanoTime() < deadline) { server.poll(); Thread.sleep(2); }
            attachedWork.get(1, TimeUnit.SECONDS);
            assertTrue(server.isOpen());
            assertEquals("7", success(local.evaluate(io.mindspice.lyra.repl.EvaluationSource.of("host", "count"))).value().orElseThrow().display());
            }
        }
    }
    @Test void compileFailurePreservesSessionAndRuntimeFailureRetainsSourceLocations() throws Exception {
        Path file = root.resolve("broken.lyra");
        Files.writeString(file, "let zero :I32 = 0\nlet fail :Fn<;I32> = (=> || (% 1 zero))");
        try (var engine = EditorRuntime.start(WorkspaceSettings.open(root), false, text -> {}, pause -> {}, status -> {})) {
            success(engine.load(file));
            var failed = engine.evaluate("::fail[]");
            assertEquals(ConsoleSession.EvaluationStatus.RUNTIME_FAILURE, failed.status());
            assertTrue(failed.diagnostics().stream().anyMatch(diagnostic -> diagnostic.primarySpan().sourceId().equals(file.toUri().toString())), failed.toString());
            assertEquals(ConsoleSession.EvaluationStatus.COMPILATION_FAILURE, engine.evaluate("let wrong :I32 = \"text\"").status());
            assertEquals("0", success(engine.evaluate("zero")).value().orElseThrow().display());
        }
    }
    @Test void inferredFunctionAliasStopsInItsActualBody() throws Exception {
        Path source = root.resolve("alias.lyra");
        Files.writeString(source, "let answer :Fn<;I32> = (=> || 42)\nlet alias = answer\n");
        BlockingQueue<Debugger.Pause> pauses = new LinkedBlockingQueue<>();
        try (var engine = EditorRuntime.start(WorkspaceSettings.open(root), true, text -> {}, pauses::add, status -> {});
             var worker = Executors.newSingleThreadExecutor()) {
            success(engine.load(source));
            Debugger debugger = engine.debugger().orElseThrow();
            debugger.stopAtNextFunction();
            Future<ConsoleSession.Evaluation> run = worker.submit(() -> engine.evaluate("::alias[]"));
            var pause = pauses.poll(15, TimeUnit.SECONDS);
            assertNotNull(pause);
            assertEquals(source, pause.frames().getFirst().file());
            assertEquals(1, pause.frames().getFirst().line());
            debugger.resume();
            assertEquals("42", success(run.get(10, TimeUnit.SECONDS)).value().orElseThrow().display());
        }
    }
    @Test void shippedExampleRunsMainRetainsStateAndBreaksInsideImportedModule() throws Exception {
        Path example = Path.of("../examples/editor").toRealPath();
        Path source = example.resolve("main.lyra"), math = example.resolve("math.lyra");
        var analysis = new LanguageService().analyze(source, Files.readString(source), List.of(example), Map.of(), true);
        assertTrue(analysis.valid(), analysis.diagnostics().toString());
        BlockingQueue<Debugger.Pause> pauses = new LinkedBlockingQueue<>();
        StringBuffer output = new StringBuffer();
        try (var engine = EditorRuntime.start(WorkspaceSettings.open(example), true, output::append, pauses::add, status -> {});
             var worker = Executors.newSingleThreadExecutor()) {
            success(engine.load(source));
            Debugger debugger = engine.debugger().orElseThrow();
            debugger.setBreakpoints(Set.of(new Debugger.Breakpoint(math, 3)));
            Future<ConsoleSession.Evaluation> run = worker.submit(() -> engine.evaluate("::calculate[20]"));
            var pause = pauses.poll(15, TimeUnit.SECONDS);
            assertNotNull(pause, output.toString());
            assertEquals(math, pause.frames().getFirst().file());
            assertEquals(3, pause.frames().getFirst().line());
            debugger.setBreakpoints(Set.of()); debugger.resume();
            assertEquals("41", success(run.get(10, TimeUnit.SECONDS)).value().orElseThrow().display());
            assertEquals("0", success(engine.evaluate("::main[Array<String>[]]")).value().orElseThrow().display());
            assertEquals("\"Hello, Lyra\"", success(engine.evaluate("::greet[\"Lyra\"]")).value().orElseThrow().display());
            assertEquals("1", success(engine.evaluate("visits")).value().orElseThrow().display());
            assertTrue(output.toString().contains("Welcome to Lyra Editor"), output.toString());
        }
    }
    private static ConsoleSession.Evaluation success(ConsoleSession.Evaluation evaluation) {
        assertEquals(ConsoleSession.EvaluationStatus.SUCCESS, evaluation.status(), evaluation.toString()); return evaluation;
    }
}
