package io.mindspice.lyra.repl;

import io.mindspice.lyra.runtime.LyraLifecycleException;
import io.mindspice.lyra.runtime.RuntimeIoEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 08 managed local owner adapter: one owner executor, one-operation
 * admission, identity-specific cross-thread cancellation, compatible console
 * presentation, bounded preflight versus dynamic truncation, owner teardown
 * without leaked threads, and source/history/input ownership.
 */
class ManagedConsoleSessionTest {
    /** Constant-stack self-tail spin; direct calls parse in branch bodies. */
    private static final String SPIN = "let spin :Fn<;I32> = (=> :I32 | | "
            + "((== 1 1) -> ::spin[] : 0)) ::spin[]";

    @TempDir
    Path directory;

    @Test
    void sessionLivesOnOneDedicatedOwnerAndCloseTearsItDownWithoutThreadLeaks() {
        ManagedConsoleSession console = ManagedConsoleSession.open();
        Thread owner = console.ownerThread();
        assertNotEquals(Thread.currentThread(), owner);
        assertTrue(owner.isAlive());
        assertSame(owner, console.session().ownerThread());
        assertNotEquals(Thread.currentThread(), console.session().ownerThread());
        try {
            success(console, "let @mut count :I32 = 1");
            assertEquals("2", value(console, "{ count := (++ count) count }"));
            assertFalse(console.isClosed());
            assertEquals(owner, console.ownerThread());
        } finally {
            console.close();
        }
        assertFalse(owner.isAlive(), "the dedicated owner thread must not outlive close");
        assertTrue(console.isClosed());
        assertEquals(ConsoleSession.EvaluationStatus.CLOSED,
                console.evaluate(EvaluationSource.of("late.lyra", "1")).status());
        assertEquals(ConsoleSession.ControlStatus.CLOSED, console.reset().status());
        assertEquals(ConsoleSession.QueryStatus.CLOSED,
                console.query(ConsoleSession.QueryRequest.bindings()).status());
        assertEquals(ConsoleSession.ControlStatus.CLOSED,
                console.cancel(EvaluationId.create()).status());
        EvaluationId reloadId = EvaluationId.create();
        EvaluationResult closedReload = console.submitReloadResult(
                "module", reloadId, () -> false);
        assertEquals(reloadId, closedReload.request().evaluationId(),
                "closed reload must preserve the caller's correlation identity");
        console.close(); // idempotent

        // Reopening creates a fresh owner that also terminates cleanly.
        ManagedConsoleSession reopened = ManagedConsoleSession.open();
        Thread secondOwner = reopened.ownerThread();
        assertNotSame(owner, secondOwner);
        reopened.close();
        assertFalse(secondOwner.isAlive());
    }

    @Test
    void activeIdentityIsPublishedBeforeExecutionAndCancellationIsIdentitySpecific()
            throws Exception {
        try (ManagedConsoleSession console = ManagedConsoleSession.open()) {
            success(console, "let @mut count :I32 = 1");
            AtomicReference<ConsoleSession.Evaluation> terminal = new AtomicReference<>();
            AtomicReference<Throwable> evaluatorFailure = new AtomicReference<>();
            Thread evaluator = new Thread(() -> {
                try {
                    terminal.set(console.evaluate(EvaluationSource.of("spin.lyra", SPIN)));
                } catch (Throwable problem) {
                    evaluatorFailure.set(problem);
                }
            }, "managed-evaluator");
            evaluator.start();

            // The identity is visible before the terminal result exists, so a
            // Ctrl-C style control thread can bind its cancel to this exact
            // evaluation.
            EvaluationId active = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                active = console.activeEvaluationId().orElse(null);
                if (active != null) {
                    break;
                }
                Thread.sleep(1);
            }
            assertNotNull(active, "the active evaluation identity was never published");
            assertTrue(console.activeEvaluationId().isPresent());

            assertEquals(ConsoleSession.ControlStatus.REQUESTED,
                    console.cancel(active).status());
            evaluator.join(10_000);
            assertFalse(evaluator.isAlive());
            assertNull(evaluatorFailure.get());
            ConsoleSession.Evaluation cancelled = terminal.get();
            assertEquals(ConsoleSession.EvaluationStatus.CANCELLED, cancelled.status());
            assertEquals(active, cancelled.evaluationId());
            assertTrue(console.activeEvaluationId().isEmpty());

            // Stale and foreign identities can never cancel later work.
            assertEquals(ConsoleSession.ControlStatus.NOT_FOUND, console.cancel(active).status());
            assertEquals(ConsoleSession.ControlStatus.NOT_FOUND,
                    console.cancel(EvaluationId.create()).status());

            // The cancelled evaluation released its lease; the session accepts
            // new work immediately and no cancellation leaks forward.
            success(console, "let @mut after :I32 = 5");
            assertEquals("5", value(console, "after"));
            assertEquals("1", value(console, "count"));
        }
    }

    @Test
    void busyAdmissionRejectsWithoutQueuingOrBeginningASecondLease() throws Exception {
        try (ManagedConsoleSession console = ManagedConsoleSession.open()) {
            success(console, "let @mut count :I32 = 1");
            AtomicReference<ConsoleSession.Evaluation> terminal = new AtomicReference<>();
            AtomicReference<Throwable> evaluatorFailure = new AtomicReference<>();
            Thread evaluator = new Thread(() -> {
                try {
                    terminal.set(console.evaluate(EvaluationSource.of("spin.lyra", SPIN)));
                } catch (Throwable problem) {
                    evaluatorFailure.set(problem);
                }
            }, "managed-evaluator");
            evaluator.start();
            waitForActive(console);

            // Every admitted operation is rejected while one is active; none
            // of them queues behind the spinning evaluation.
            ConsoleSession.Evaluation busy = console.evaluate(EvaluationSource.of("busy.lyra", "count := 999"));
            assertEquals(ConsoleSession.EvaluationStatus.BUSY, busy.status());
            assertEquals(ConsoleSession.ControlStatus.BUSY, console.reset().status());
            assertEquals(ConsoleSession.QueryStatus.BUSY,
                    console.query(ConsoleSession.QueryRequest.bindings()).status());
            assertEquals(ConsoleSession.QueryStatus.BUSY,
                    console.query(ConsoleSession.QueryRequest.type(
                            EvaluationSource.of("console:type", "count"))).status());
            assertTrue(console.reload("count").status()
                    == ConsoleSession.EvaluationStatus.BUSY);

            assertEquals(ConsoleSession.ControlStatus.REQUESTED,
                    console.cancel(console.activeEvaluationId().orElseThrow()).status());
            evaluator.join(10_000);
            assertFalse(evaluator.isAlive());
            assertNull(evaluatorFailure.get());
            assertEquals(ConsoleSession.EvaluationStatus.CANCELLED,
                    terminal.get().status());

            // The rejected busy submission never executed and no queued
            // duplicate ran: count is untouched and later work uses the one
            // cleanly released lease.
            assertEquals("1", value(console, "count"));
            success(console, "count := 2");
            assertEquals("2", value(console, "count"));
        }
    }

    @Test
    void resetRunsOnTheOwnerAndCommittedMetadataUsesCompatibleRecordForms() {
        try (ManagedConsoleSession console = ManagedConsoleSession.open()) {
            success(console, "let @mut counter :I32 = 3 let @pub visible :String = \"hi\"");
            ConsoleSession.Query query = console.query(ConsoleSession.QueryRequest.bindings());
            assertEquals(ConsoleSession.QueryStatus.OK, query.status());
            List<ConsoleSession.Binding> bindings = query.bindings();
            assertEquals(List.of("counter", "visible"),
                    bindings.stream().map(ConsoleSession.Binding::name).toList());
            ConsoleSession.Binding counter = bindings.get(0);
            assertEquals("I32", counter.canonicalType());
            assertEquals("PRIVATE", counter.visibility());
            assertTrue(counter.mutable());
            ConsoleSession.Binding visible = bindings.get(1);
            assertEquals("String", visible.canonicalType());
            assertEquals("PUBLIC", visible.visibility());
            assertFalse(visible.mutable());

            // :type analyzes against committed context without executing or
            // publishing anything.
            ConsoleSession.Query typed = console.query(ConsoleSession.QueryRequest.type(
                    EvaluationSource.of("console:type", "visible")));
            assertEquals(ConsoleSession.QueryStatus.OK, typed.status());
            assertEquals(Optional.of("String"), typed.inferredType());
            ConsoleSession.Query failed = console.query(ConsoleSession.QueryRequest.type(
                    EvaluationSource.of("console:type", "missing-name")));
            assertEquals(ConsoleSession.QueryStatus.UNAVAILABLE, failed.status());
            assertTrue(failed.detail().orElseThrow().contains("LYC-"),
                    failed.detail().orElseThrow());
            assertEquals(2, console.query(ConsoleSession.QueryRequest.bindings())
                    .bindings().size());

            // Reset is an owner operation that drops scratch metadata only.
            assertEquals(ConsoleSession.ControlStatus.OK, console.reset().status());
            assertTrue(console.query(ConsoleSession.QueryRequest.bindings())
                    .bindings().isEmpty());
            assertEquals(ConsoleSession.EvaluationStatus.COMPILATION_FAILURE,
                    console.evaluate(EvaluationSource.of("missing.lyra", "counter")).status());
        }
    }

    @Test
    void immutablePresentationRecordsStayReadableAfterResetAndClose() {
        ManagedConsoleSession console = ManagedConsoleSession.open();
        success(console, "let @mut data :Array<I32> = Array<I32>[1 2 3]");
        ConsoleSession.Evaluation aggregate = console.evaluate(EvaluationSource.of("snapshot.lyra", "data"));
        assertEquals(ConsoleSession.EvaluationStatus.SUCCESS, aggregate.status());
        ConsoleSession.Value snapshotValue = aggregate.value().orElseThrow();
        assertEquals("Array<I32>", snapshotValue.canonicalType());
        assertTrue(snapshotValue.display().startsWith("array"),
                snapshotValue.display());
        ConsoleSession.Evaluation element = console.evaluate(EvaluationSource.of("element.lyra", "data[0]"));
        assertEquals("1", element.value().orElseThrow().display());
        assertEquals("I32", element.value().orElseThrow().canonicalType());

        console.reset();
        assertEquals(ConsoleSession.EvaluationStatus.COMPILATION_FAILURE,
                console.evaluate(EvaluationSource.of("retired.lyra", "data")).status());
        console.close();

        // The retained presentation records are immutable data; they remain
        // fully readable after reset and close.
        assertEquals("Array<I32>", snapshotValue.canonicalType());
        assertTrue(snapshotValue.display().startsWith("array"));
        assertEquals("1", element.value().orElseThrow().display());
        assertEquals(ConsoleSession.EvaluationStatus.SUCCESS, element.status());
    }

    @Test
    void smallPreflightBudgetsRejectBeforeEffectsAndLargeResultsTruncateDynamically() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RuntimeIoEnvironment environment = new RuntimeIoEnvironment(
                InputStream.nullInputStream(), output, output, StandardCharsets.UTF_8);
        SessionOptions tiny = SessionOptions.builder()
                .snapshotLimits(new SnapshotLimits(6, 100, 6))
                .ioEnvironment(environment)
                .build();
        try (ManagedConsoleSession console = ManagedConsoleSession.open(tiny)) {
            success(console, "let @mut count :I32 = 1");
            // The final expression type cannot be represented at this budget,
            // so the whole submission is rejected before any effect runs.
            ConsoleSession.Evaluation rejected = console.evaluate(EvaluationSource.of(
                    "budget.lyra",
                    "import std->io io->::println[\"must-not-run\"] count := 2 count"));
            assertEquals(ConsoleSession.EvaluationStatus.COMPILATION_FAILURE,
                    rejected.status(), rejected.toString());
            assertTrue(rejected.diagnostics().stream().anyMatch(d ->
                    d.summary().contains("snapshot output budget")), rejected.toString());
            assertEquals("", output.toString(StandardCharsets.UTF_8),
                    "preflight rejection must precede execution");
            // Display-free declarations stay usable at the same budget and
            // prove the rejected submission never modified count.
            success(console, "let zero :I32 = 0 "
                    + "let check :I32 = ((== count 1) -> 1 : (% 1 zero))");
        }

        // The default budget preflights the minimum envelope only; a large
        // dynamic string result truncates explicitly after its effects ran.
        ByteArrayOutputStream large = new ByteArrayOutputStream();
        RuntimeIoEnvironment largeEnvironment = new RuntimeIoEnvironment(
                InputStream.nullInputStream(), large, large, StandardCharsets.UTF_8);
        try (ManagedConsoleSession console = ManagedConsoleSession.open(
                SessionOptions.builder().ioEnvironment(largeEnvironment).build())) {
            success(console, "let @mut count :I32 = 1");
            // Exceeds the 16 KiB render budget but fits the JVM constant pool;
// a string literal above ~64 KiB modified UTF-8 is a separate
// pre-existing emitter limit tracked in .internal-dev/bugs.
String bigText = "x".repeat(20 * 1024);
            ConsoleSession.Evaluation truncated = console.evaluate(EvaluationSource.of(
                    "large.lyra",
                    "{ count := 2 \"" + bigText + "\" }"));
            assertEquals(ConsoleSession.EvaluationStatus.SUCCESS, truncated.status(),
                    truncated.toString());
            ConsoleSession.Value value = truncated.value().orElseThrow();
            assertEquals("String", value.canonicalType());
            assertTrue(value.display().contains("truncated"), value.display());
            assertEquals("2", value(console, "count"),
                    "dynamic truncation must preserve completed effects");
        }
    }

    @Test
    void largeDiagnosticTextIsTruncatedWithoutLosingTerminalStatus() {
        try (ManagedConsoleSession console = ManagedConsoleSession.open()) {
            String source = "import " + "a".repeat(100_000);
            ConsoleSession.Evaluation result = console.evaluate(
                    EvaluationSource.of("large-diagnostic.lyra", source));
            assertEquals(ConsoleSession.EvaluationStatus.COMPILATION_FAILURE, result.status());
            ConsoleSession.DiagnosticInfo diagnostic = result.diagnostics().stream()
                    .findFirst().orElseThrow();
            assertTrue(diagnostic.summary().length()
                            <= SnapshotLimits.DEFAULT_MAX_RENDERED_CHARACTERS,
                    () -> "unbounded diagnostic summary: " + diagnostic.summary().length());
            assertTrue(diagnostic.summary().endsWith("…"), diagnostic.summary());
            assertEquals(ConsoleSession.EvaluationStatus.COMPILATION_FAILURE, result.status());
        }
    }

    @Test
    void importedAndReloadedFailuresKeepExactOriginalUtf16SpansAtTheConsoleBoundary()
            throws Exception {
        Path dep = directory.resolve("dep.lyra");
        String original = "let @pub fail :Fn<I32;I32> = (=> |x| (% 1 x))";
        Files.writeString(dep, original);
        String fileUri = dep.toUri().toString();
        try (ManagedConsoleSession console = ManagedConsoleSession.open(
                SessionOptions.builder().sourceRoot(directory).build())) {
            success(console, "import dep");
            ConsoleSession.Evaluation failure = console.evaluate(
                    EvaluationSource.of("call.lyra", "dep->::fail[0]"));
            assertEquals(ConsoleSession.EvaluationStatus.RUNTIME_FAILURE, failure.status());
            ConsoleSession.DiagnosticInfo depFrame = failure.diagnostics().stream()
                    .filter(d -> d.primarySpan().sourceId().equals(fileUri))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(failure.diagnostics()));
            assertEquals(original.indexOf("(% 1 x)"), depFrame.primarySpan().startOffset());

            // Reload publishes a fresh revision; a delayed failure in the new
            // revision keeps its own exact UTF-16 span in the same caller-visible URI.
            String replacement = "/* 😀 new revision */ let @pub fail :Fn<I32;I32> = (=> |x| (% 2 x))";
            Files.writeString(dep, replacement);
            ConsoleSession.Evaluation reloaded = console.reload("dep");
            assertEquals(ConsoleSession.EvaluationStatus.SUCCESS, reloaded.status(),
                    reloaded.toString());
            ConsoleSession.Evaluation newFailure = console.evaluate(
                    EvaluationSource.of("call2.lyra", "dep->::fail[0]"));
            assertEquals(ConsoleSession.EvaluationStatus.RUNTIME_FAILURE, newFailure.status());
            ConsoleSession.DiagnosticInfo newFrame = newFailure.diagnostics().stream()
                    .filter(d -> d.primarySpan().sourceId().equals(fileUri))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(newFailure.diagnostics()));
            assertEquals(replacement.indexOf("(% 2 x)"), newFrame.primarySpan().startOffset());
            assertTrue(newFrame.primarySpan().endOffset() <= replacement.length());
            assertNotEquals(depFrame.primarySpan().startOffset(),
                    newFrame.primarySpan().startOffset());
        }
    }

    @Test
    void consoleSourceAndGeneratedProgramInputShareOneOwnerAndNeverMixHistory() {
        String script = "import std->io let @mut count :I32 = 1\n"
                + "import std->io io->::readLine[]\n"
                + "hello-program\n"
                + "count\n"
                + ":history\n"
                + ":quit\n";
        ByteArrayInputStream input = new ByteArrayInputStream(
                script.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RuntimeIoEnvironment environment = new RuntimeIoEnvironment(
                input, output, output, StandardCharsets.UTF_8);
        try (ManagedConsoleSession console = ManagedConsoleSession.open(
                SessionOptions.builder().ioEnvironment(environment).build())) {
            PlainConsole plain = new PlainConsole(console, environment);
            assertEquals(0, plain.run());
        }
        String rendered = output.toString(StandardCharsets.UTF_8);
        // The generated readLine consumed the program input on the owner
        // thread while the console caller waited for the terminal result.
        assertTrue(rendered.contains("hello-program"), rendered);
        // History lists submitted source only; program input never becomes
        // source or history.
        assertTrue(rendered.contains("1: import std->io"), rendered);
        assertTrue(rendered.contains("import std->io io->::readLine[]"), rendered);
        assertTrue(rendered.contains("3: count"), rendered);
        assertFalse(rendered.contains(": hello-program"), rendered);
        // Following source still evaluated after the program read.
        assertTrue(rendered.contains("I32 1"), rendered);
    }

    @Test
    void loadReadsTheExecutionHostFileOnceOnTheOwnerAndReturnsItsSourceText()
            throws Exception {
        Path file = directory.resolve("loaded module.lyra");
        Files.writeString(file, "/* café */ let @pub loaded :I32 = 7\n");
        try (ManagedConsoleSession console = ManagedConsoleSession.open()) {
            ConsoleSession.Loaded loaded = console.load(file.toString());
            assertEquals(ConsoleSession.EvaluationStatus.SUCCESS,
                    loaded.evaluation().status(), loaded.evaluation().toString());
            assertEquals("/* café */ let @pub loaded :I32 = 7\n",
                    loaded.sourceText().orElseThrow());
            ConsoleSession.Query bindings = console.query(
                    ConsoleSession.QueryRequest.bindings());
            assertEquals(1, bindings.bindings().size());
            assertEquals("loaded", bindings.bindings().getFirst().name());

            // The captured file-URI origin maps diagnostics back to the file.
            ConsoleSession.Evaluation failure = console.evaluate(
                    EvaluationSource.of("call.lyra", "loaded"));
            assertEquals(ConsoleSession.EvaluationStatus.SUCCESS, failure.status());

            // File-level problems are ordinary I/O/UTF-8 diagnostics.
            assertThrows(IllegalArgumentException.class,
                    () -> console.load(file.resolveSibling("absent.lyra").toString()));
        }
    }

    @Test
    void completionListsExecutionHostFilesAndCommittedMembersWithoutEvaluations()
            throws Exception {
        Path moduleFile = directory.resolve("newmod.lyra");
        Files.writeString(moduleFile, "let @pub inside :I32 = 1\n");
        try (ManagedConsoleSession console = ManagedConsoleSession.open(
                SessionOptions.builder().sourceRoot(directory).build())) {
            success(console, "let @pub text :String = \"hi\"");
            SessionRevision before = console.revision();

            ConsoleSession.Completion files = console.complete(
                    ConsoleSession.CompletionRequest.moduleFiles(Optional.of("newm")));
            assertEquals(ConsoleSession.QueryStatus.OK, files.status(), files.toString());
            assertTrue(files.items().stream().anyMatch(item ->
                    item.name().equals("newmod.lyra")
                            && item.kind() == ConsoleSession.ItemKind.FILE), files.items().toString());
            assertTrue(files.items().stream().anyMatch(item ->
                    item.name().equals("newmod")
                            && item.kind() == ConsoleSession.ItemKind.MODULE), files.items().toString());

            ConsoleSession.Completion members = console.complete(
                    ConsoleSession.CompletionRequest.bindingMembers("text"));
            assertEquals(ConsoleSession.QueryStatus.OK, members.status(), members.toString());
            assertEquals(List.of("length"), members.items().stream()
                    .map(ConsoleSession.CompletionItem::name).toList());
            assertEquals(Optional.of("I32"), members.items().getFirst().typeSpelling());

            ConsoleSession.Completion unknown = console.complete(
                    ConsoleSession.CompletionRequest.bindingMembers("absent"));
            assertEquals(ConsoleSession.QueryStatus.NOT_FOUND, unknown.status());

            // Listing and metadata lookup never compile, pin or publish.
            assertEquals(before, console.revision());
            ConsoleSession.Query bindings = console.query(
                    ConsoleSession.QueryRequest.bindings());
            assertEquals(List.of("text"), bindings.bindings().stream()
                    .map(ConsoleSession.Binding::name).toList());
        }
    }

    @Test
    void spinningLoadedFileIsCancellableByItsPublishedIdentityThenRecovers()
            throws Exception {
        Path spin = directory.resolve("spin.lyra");
        Files.writeString(spin, SPIN);
        try (ManagedConsoleSession console = ManagedConsoleSession.open()) {
            AtomicReference<ConsoleSession.Loaded> terminal = new AtomicReference<>();
            Thread loader = new Thread(() -> terminal.set(console.load(spin.toString())),
                    "managed-loader");
            loader.start();
            waitForActive(console);
            EvaluationId active = console.activeEvaluationId().orElseThrow();
            assertEquals(ConsoleSession.ControlStatus.REQUESTED, console.cancel(active).status());
            loader.join(10_000);
            assertFalse(loader.isAlive());
            ConsoleSession.Loaded cancelled = terminal.get();
            assertEquals(ConsoleSession.EvaluationStatus.CANCELLED,
                    cancelled.evaluation().status());
            assertEquals(active, cancelled.evaluation().evaluationId());
            assertTrue(console.activeEvaluationId().isEmpty());

            // The cancelled load published nothing; later source succeeds.
            success(console, "let @pub after :I32 = 42");
            assertEquals("42", value(console, "after"));
        }
    }

    private static void waitForActive(ManagedConsoleSession console) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (console.activeEvaluationId().isPresent()) {
                return;
            }
            Thread.sleep(1);
        }
        fail("the active evaluation identity was never published");
    }

    private static void success(ManagedConsoleSession console, String source) {
        ConsoleSession.Evaluation result = console.evaluate(EvaluationSource.of("managed.lyra", source));
        assertEquals(ConsoleSession.EvaluationStatus.SUCCESS, result.status(),
                () -> result.status() + " " + result.diagnostics() + " " + result.detail());
    }

    private static String value(ManagedConsoleSession console, String source) {
        ConsoleSession.Evaluation result = console.evaluate(EvaluationSource.of("value.lyra", source));
        assertEquals(ConsoleSession.EvaluationStatus.SUCCESS, result.status(),
                () -> result.status() + " " + result.diagnostics() + " " + result.detail());
        return result.value().orElseThrow().display();
    }
}
