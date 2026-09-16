package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.api.AttachableCompileResult;
import io.mindspice.lyra.compiler.api.AttachableRootContext;
import io.mindspice.lyra.compiler.api.CompileProfile;
import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.compiler.api.SessionCompileRequest;
import io.mindspice.lyra.compiler.api.SessionCompileResult;
import io.mindspice.lyra.compiler.api.SourceResolver;
import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ResolvedSource;
import io.mindspice.lyra.runtime.LyraClosedException;
import io.mindspice.lyra.runtime.LyraLifecycleException;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.RootTypeRegistration;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 06 live public-root workspace: real typed root storage, exact
 * accessor semantics, ordinary protection rules, application dependency
 * borrowing, service ownership and root-lifetime retention.
 */
class ApplicationAttachmentTest {
    private static final String ROOT_SOURCE = "let hidden :I32 = 99\n"
            + "let @pub @mut count :I32 = 1\n"
            + "let @pub readCount :Fn<;I32> = (=> | | count)\n"
            + "let @pub @mut selected :Fn<I32;I32> = (=> |value| (+ value 1))\n"
            + "let @pub replacement :Fn<I32;I32> = (=> |value| (+ value 2))\n"
            + "let @pub @mut values :Array<I32> = Array<I32>[1 2]\n"
            + "let @pub readFirst :Fn<;I32> = (=> | | values[0])\n"
            + "let @pub apply :Fn<Fn<I32;I32>,I32;I32> = (=> |f x| (f x))\n"
            + "let @pub add :Fn<I32,I32;I32> = (=> |left right| (+ left right))\n";

    private static AttachableCompileResult.Success compiledRoot() {
        return assertInstanceOf(AttachableCompileResult.Success.class,
                LyraCompiler.compileAttachable(CompileRequest.builder()
                        .source("main.lyra", ROOT_SOURCE)
                        .profile(CompileProfile.ATTACHABLE).build()));
    }

    @Test
    void publicScalarReadsAndWritesTargetTheOriginalRootStorage() throws Throwable {
        AttachableCompileResult.Success compiled = compiledRoot();
        var loaded = LyraRuntime.load(compiled.artifact());
        var root = loaded.instantiate();
        try (ApplicationAttachment attachment = open(compiled, root)) {
            RootTypeRegistration registration = attachment.registration();
            assertEquals("1", scalar(success(attachment, "count")));
            success(attachment, "count := 41");
            // The real application facade observes the evaluation write.
            assertEquals(41, registration.requireBinding("count").getter().invoke());
            assertEquals("41", scalar(success(attachment, "(readCount)")));
            // Main-side writes are the current state for later evaluations.
            registration.requireBinding("count").setter().orElseThrow().invoke(7);
            assertEquals("7", scalar(success(attachment, "count")));
            assertEquals("7", scalar(success(attachment, "(readCount)")));
            assertEquals("42", scalar(success(attachment, "(+ count 35)")));
        } finally {
            root.close();
            loaded.close();
        }
    }

    @Test
    void publicFunctionValuesUseRealInstancesInBothDirections() throws Throwable {
        AttachableCompileResult.Success compiled = compiledRoot();
        var loaded = LyraRuntime.load(compiled.artifact());
        var root = loaded.instantiate();
        try (ApplicationAttachment attachment = open(compiled, root)) {
            RootTypeRegistration registration = attachment.registration();
            // Root function invoked from the attached scope.
            assertEquals("11", scalar(success(attachment, "(add 5 6)")));
            // Session function value replaces the live root binding; the real
            // application facade invokes the session closure afterwards.
            success(attachment, "selected := (=> :I32 |value :I32| (* value 10))");
            assertEquals(30, registration.requireBinding("selected").invocation().invoke(3));
            // Main-side replacement is observed by later session calls.
            Object rootReplacement = registration.requireBinding("replacement")
                    .functionValue().orElseThrow().invoke();
            registration.requireBinding("selected").setter().orElseThrow()
                    .invoke(rootReplacement);
            assertEquals("5", scalar(success(attachment, "(selected 3)")));
            // Higher-order transfer through the real root function.
            assertEquals("10", scalar(success(attachment,
                    "(apply (=> |value| (* value 5)) 2)")));
        } finally {
            root.close();
            loaded.close();
        }
    }

    @Test
    void privateMissingImmutableAndPublicRedeclarationRemainOrdinaryDiagnostics() {
        AttachableCompileResult.Success compiled = compiledRoot();
        var loaded = LyraRuntime.load(compiled.artifact());
        var root = loaded.instantiate();
        try (ApplicationAttachment attachment = open(compiled, root)) {
            // Private names are not part of the public root scope.
            assertInstanceOf(EvaluationResult.CompilationFailure.class,
                    attachment.submit("bad.lyra", "hidden"));
            // Missing names stay missing.
            assertInstanceOf(EvaluationResult.CompilationFailure.class,
                    attachment.submit("bad.lyra", "absent"));
            // Immutable public names accept no assignment.
            assertInstanceOf(EvaluationResult.CompilationFailure.class,
                    attachment.submit("bad.lyra", "add := replacement"));
            // Public root names cannot be redeclared by scratch code.
            EvaluationResult redeclared = attachment.submit("bad.lyra", "let count :I32 = 5");
            EvaluationResult.CompilationFailure failure =
                    assertInstanceOf(EvaluationResult.CompilationFailure.class, redeclared);
            assertEquals(CompilerDiagnosticCodes.RESOLVE_PUBLIC_REDECLARATION,
                    failure.diagnostics().getFirst().code());
            // Ordinary scratch declarations remain available.
            success(attachment, "let @mut local :I32 = 1");
            success(attachment, "local := 2");
            assertEquals("2", scalar(success(attachment, "local")));
        } finally {
            if (!root.isClosed()) root.close();
            loaded.close();
        }
    }

    @Test
    void publicAggregateValuesAreLiveAndElementMutationIsRejected() throws Throwable {
        AttachableCompileResult.Success compiled = compiledRoot();
        var loaded = LyraRuntime.load(compiled.artifact());
        var root = loaded.instantiate();
        try (ApplicationAttachment attachment = open(compiled, root)) {
            RootTypeRegistration registration = attachment.registration();
            assertEquals("1", scalar(success(attachment, "(readFirst)")));
            // Whole-binding replacement changes the actual root storage.
            success(attachment, "values := Array<I32>[7 8]");
            assertEquals("7", scalar(success(attachment, "(readFirst)")));
            int[] live = (int[]) registration.requireBinding("values").getter().invoke();
            assertEquals(7, live[0]);
            // Main replaces the array; the session reads the current value.
            registration.requireBinding("values").setter().orElseThrow()
                    .invoke(new int[]{50});
            assertEquals("50", scalar(success(attachment, "(readFirst)")));
            // Element mutation through the public root binding stays rejected,
            // directly and through a scratch alias.
            assertInstanceOf(EvaluationResult.CompilationFailure.class,
                    attachment.submit("bad.lyra", "values[0] := 9"));
            EvaluationResult aliased = attachment.submit("bad.lyra",
                    "let @mut alias :Array<I32> = values alias[0] := 9");
            assertInstanceOf(EvaluationResult.CompilationFailure.class, aliased);
        } finally {
            root.close();
            loaded.close();
        }
    }

    @Test
    void importedAppDependenciesAreBorrowedAndNeverReloadable() throws Throwable {
        AttachableCompileResult.Success compiled = assertInstanceOf(
                AttachableCompileResult.Success.class,
                LyraCompiler.compileAttachable(CompileRequest.builder()
                        .source("main.lyra", "import dep\n"
                                + "let @pub answer :I32 = dep->:.value\n"
                                + "let @pub bump :Fn<;I32> = (=> | | dep->::next[])\n")
                        .resolver(SourceResolver.memory(ResolvedSource.memory("dep",
                                URI.create("memory:dep.lyra"),
                                "let @mut hidden :I32 = 40\n"
                                        + "let @pub value :I32 = hidden\n"
                                        + "let @pub next :Fn<;I32> = (=> | | { hidden := (++ hidden) hidden })\n")))
                        .profile(CompileProfile.ATTACHABLE).build()));
        AttachableRootContext context = compiled.context();
        var loaded = LyraRuntime.load(compiled.artifact());
        var root = loaded.instantiate();
        try (ApplicationAttachment attachment = open(compiled, root)) {
            // The dependency is reachable through ordinary session imports and
            // executes against the original application-owned state.
            assertEquals("40", scalar(success(attachment, "import dep dep->:.value")));
            assertEquals("41", scalar(success(attachment, "import dep dep->::next[]")));
            assertEquals("42", scalar(success(attachment, "(bump)")));
        } finally {
            if (!root.isClosed()) root.close();
            loaded.close();
        }
        // The compiler rejects reloads of application-owned modules.
        var initial = context.initialSnapshot();
        SessionCompileResult reloadAttempt = LyraCompiler.compileSession(
                SessionCompileRequest.builder()
                        .source("reload.lyra", "")
                        .sourceId(io.mindspice.lyra.compiler.source.SourceId.path(
                                "repl/reload-app.lyra"))
                        .snapshot(initial)
                        .reloadModule(LogicalModuleId.parse("dep"))
                        .reloadImportAlias("__reloaded")
                        .build());
        SessionCompileResult.Failure reloadFailure =
                assertInstanceOf(SessionCompileResult.Failure.class, reloadAttempt);
        assertEquals(CompilerDiagnosticCodes.SESSION_EXTERNAL_BINDING_UNSUPPORTED,
                reloadFailure.diagnostics().getFirst().code());
    }

    @Test
    void duplicateSimultaneousServiceIsRejectedAndIndependentRootsStayIndependent() throws Throwable {
        AttachableCompileResult.Success compiled = compiledRoot();
        var loaded = LyraRuntime.load(compiled.artifact());
        var root = loaded.instantiate();
        try (ApplicationAttachment first = open(compiled, root)) {
            assertThrows(LyraLifecycleException.class,
                    () -> ApplicationAttachment.open(root, compiled.context(),
                            SessionOptions.defaults()));
            // A second root instance from the same artifact is independent.
            var secondRoot = loaded.instantiate();
            try (ApplicationAttachment second = open(compiled, secondRoot)) {
                success(first, "count := 100");
                assertEquals("1", scalar(success(second, "count")));
                success(second, "count := 200");
                assertEquals("100", scalar(success(first, "count")));
                assertNotSame(first.registration().rootLifetime(),
                        second.registration().rootLifetime());
            } finally {
                secondRoot.close();
            }
        } finally {
            root.close();
            loaded.close();
        }
    }

    @Test
    void compilationAndRuntimeFailuresPublishNothingButKeepCompletedRootWrites() {
        AttachableCompileResult.Success compiled = compiledRoot();
        var loaded = LyraRuntime.load(compiled.artifact());
        var root = loaded.instantiate();
        try (ApplicationAttachment attachment = open(compiled, root)) {
            assertInstanceOf(EvaluationResult.CompilationFailure.class,
                    attachment.submit("bad.lyra", "count := 7 let invalid :Bool = 1"));
            assertEquals("1", scalar(success(attachment, "count")));
            assertInstanceOf(EvaluationResult.RuntimeFailure.class,
                    attachment.submit("bad.lyra",
                            "count := 7 let hidden :I32 = 42 let zero :I32 = 0 (% 1 zero)"));
            assertEquals("7", scalar(success(attachment, "count")));
            // Staged names never publish after failure; a fresh declaration is clean.
            success(attachment, "let hidden :I32 = 4");
            assertEquals("4", scalar(success(attachment, "hidden")));
        } finally {
            if (!root.isClosed()) root.close();
            loaded.close();
        }
    }

    @Test
    void cancelledEvaluationKeepsCompletedRootWritesAndEscapedValues() throws Throwable {
        AttachableCompileResult.Success compiled = compiledRoot();
        var loaded = LyraRuntime.load(compiled.artifact());
        var root = loaded.instantiate();
        try (ApplicationAttachment attachment = open(compiled, root)) {
            RootTypeRegistration registration = attachment.registration();
            var request = new EvaluationRequest(EvaluationId.create(),
                    attachment.currentRevision(), EvaluationSource.of("cancelled.lyra", """
                    count := 55
                    let hidden :I32 = 42
                    selected := (=> :I32 |value :I32| hidden)
                    let spin :Fn<;I32> = (=> || (spin))
                    (spin)
                    count := 1
                    """));
            Thread ownerThread = Thread.currentThread();
            AtomicReference<Throwable> controlFailure = new AtomicReference<>();
            Thread control = new Thread(() -> {
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (System.nanoTime() < deadline) {
                        if (java.util.Arrays.stream(ownerThread.getStackTrace()).anyMatch(frame ->
                                frame.getClassName().contains("$lyra$")
                                        && frame.getMethodName().equals("invoke"))) {
                            assertTrue(attachment.cancel(request.evaluationId()));
                            return;
                        }
                        Thread.sleep(1);
                    }
                    throw new AssertionError(
                            "generated execution never reached the cancellation boundary");
                } catch (Throwable failure) {
                    controlFailure.set(failure);
                } finally {
                    attachment.cancel(request.evaluationId());
                }
            });
            control.start();
            try {
                assertInstanceOf(EvaluationResult.Cancelled.class, attachment.submit(request));
            } finally {
                control.join(11_000);
            }
            assertFalse(control.isAlive());
            assertNull(controlFailure.get());
            // Completed root mutation survives; staged scratch names do not.
            assertEquals("55", scalar(success(attachment, "count")));
            assertEquals(55, registration.requireBinding("count").getter().invoke());
            // The escaped session closure stored into the root binding before
            // cancellation remains invokable by the real application code.
            assertEquals(42, registration.requireBinding("selected").invocation().invoke(1));
            assertEquals("42", scalar(success(attachment, "(selected 1)")));
            success(attachment, "let hidden :I32 = 4");
            assertEquals("4", scalar(success(attachment, "hidden")));
        } finally {
            root.close();
            loaded.close();
        }
    }

    @Test
    void attachedSourceFactoryConstructsAndPublishesIntoLaterSessionEvaluation() throws Throwable {
        AttachableCompileResult.Success compiled = assertInstanceOf(
                AttachableCompileResult.Success.class,
                LyraCompiler.compileAttachable(CompileRequest.builder().source(
                        "attached-factory-root.lyra", """
                        class RootBox {
                            @pub value :I32
                            RootBox = (=> |value :I32| { self:.value := value })
                        }
                        let @pub makeBox :Fn<I32;RootBox> =
                            (=> |value| :RootBox[value])
                        """).profile(CompileProfile.ATTACHABLE).build()));
        var loaded = LyraRuntime.load(compiled.artifact());
        var root = loaded.instantiate();
        try (ApplicationAttachment attachment = open(compiled, root)) {
            success(attachment, "let attachedBox :RootBox = ::makeBox[42]");
            assertEquals("42", scalar(success(attachment, "attachedBox:.value")));

            Object javaBox = attachment.registration().requireBinding("makeBox")
                    .invocation().invoke(7);
            assertEquals(7, javaBox.getClass().getMethod("$lyra$public$get$0")
                    .invoke(javaBox));
        } finally {
            root.close();
            loaded.close();
        }
    }

    @Test
    void attachedRetainedFactoryCancellationKeepsPriorRootEffectsAndRootLifetimeAuthority()
            throws Throwable {
        AttachableCompileResult.Success compiled = compiledRoot();
        var loaded = LyraRuntime.load(compiled.artifact());
        var root = loaded.instantiate();
        BlockingInput input = new BlockingInput();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SessionOptions options = SessionOptions.builder().ioEnvironment(
                new io.mindspice.lyra.runtime.RuntimeIoEnvironment(
                        input, output, output, StandardCharsets.UTF_8)).build();
        ApplicationAttachment attachment = ApplicationAttachment.open(root, compiled.context(), options);
        Object retained = null;
        try {
            success(attachment, """
                    import std->io
                    let makeInstalled :Fn<I32;Fn<I32;I32>> =
                        (=> |captured| (=> |ignored| captured))
                    class WaitBox {
                        @pub value :I32 = 9
                        WaitBox = (=> |captured :I32| {
                            selected := ::makeInstalled[captured]
                            let @nil ignored :String = io->::readLine[]
                        })
                    }
                    """);
            DispatchedEvaluation construction = attachment.submitDispatch(
                    "attached-retained-construction.lyra",
                    "let stagedBox :WaitBox = :WaitBox[42]");
            AtomicReference<Throwable> controlFailure = new AtomicReference<>();
            Thread control = new Thread(() -> {
                try {
                    assertTrue(input.awaitRead(), "constructor never reached its deterministic I/O gate");
                    assertTrue(construction.cancel());
                } catch (Throwable failure) {
                    controlFailure.set(failure);
                } finally {
                    input.release();
                }
            }, "retained-constructor-cancel");
            control.start();
            try {
                assertTrue(attachment.poll());
            } finally {
                input.release();
                control.join(11_000);
            }
            assertFalse(control.isAlive());
            assertNull(controlFailure.get());
            assertInstanceOf(EvaluationResult.Cancelled.class, construction.awaitResult());

            // The constructor's completed root write survives, while its
            // staged object name and partial receiver do not publish.
            assertEquals(42, attachment.registration().requireBinding("selected")
                    .invocation().invoke(0));
            assertInstanceOf(EvaluationResult.CompilationFailure.class,
                    attachment.submit("attached-retained-unpublished.lyra", "stagedBox"));

            // The same retained producer and session remain usable. The input
            // gate now returns EOF, allowing one real retained construction.
            success(attachment, "let stagedBox :WaitBox = :WaitBox[7]");
            assertEquals(7, attachment.registration().requireBinding("selected")
                    .invocation().invoke(0));
            retained = attachment.registration().requireBinding("selected")
                    .functionValue().orElseThrow().invoke();
            attachment.close();
            assertEquals(7, invokeFunction(retained, 0));
        } finally {
            attachment.close();
            root.close();
        }
        Object closedRetained = retained;
        assertThrows(LyraClosedException.class, () -> invokeFunction(closedRetained, 0));
        loaded.close();
    }

    @Test
    void olderCapturedAliasesKeepTheirProducerLinksAcrossGenerations() throws Throwable {
        AttachableCompileResult.Success compiled = compiledRoot();
        var loaded = LyraRuntime.load(compiled.artifact());
        var root = loaded.instantiate();
        try (ApplicationAttachment attachment = open(compiled, root)) {
            RootTypeRegistration registration = attachment.registration();
            // Generation 1: a scratch closure capturing a scratch cell.
            success(attachment, "let @mut cell :I32 = 10\n"
                    + "let reader :Fn<;I32> = (=> | | cell)\n");
            // Generation 2: replace the root function with a closure that
            // invokes the older captured alias through its retained producer.
            success(attachment, "selected := (=> :I32 |value :I32| (+ (reader) value))");
            assertEquals(12, registration.requireBinding("selected").invocation().invoke(2));
            // The older cell link stays the exact original storage.
            success(attachment, "cell := 100");
            assertEquals(103, registration.requireBinding("selected").invocation().invoke(3));
            assertEquals("100", scalar(success(attachment, "(reader)")));
        } finally {
            root.close();
            loaded.close();
        }
    }

    @Test
    void resetPreservesRootMutationsAndDropsOnlyScratchState() throws Throwable {
        AttachableCompileResult.Success compiled = compiledRoot();
        var loaded = LyraRuntime.load(compiled.artifact());
        var root = loaded.instantiate();
        try (ApplicationAttachment attachment = open(compiled, root)) {
            success(attachment, "count := 9");
            success(attachment, "let scratch :I32 = 1");
            var revision = attachment.currentRevision();
            attachment.reset();
            // Revision is never rewound; root state and root names survive.
            assertTrue(attachment.currentRevision().value() >= revision.value());
            assertEquals("9", scalar(success(attachment, "count")));
            assertInstanceOf(EvaluationResult.CompilationFailure.class,
                    attachment.submit("bad.lyra", "scratch"));
            // A fresh scratch declaration of the same name is clean storage.
            success(attachment, "let scratch :I32 = 2");
            assertEquals("2", scalar(success(attachment, "scratch")));
            assertEquals(9, attachment.registration().requireBinding("count").getter().invoke());
        } finally {
            root.close();
            loaded.close();
        }
    }

    @Test
    void serviceCloseLeavesRootValuesAndDomainAliveAndAllowsReopen() throws Throwable {
        AttachableCompileResult.Success compiled = compiledRoot();
        var loaded = LyraRuntime.load(compiled.artifact());
        var root = loaded.instantiate();
        ApplicationAttachment first = open(compiled, root);
        var lifetime = first.registration().rootLifetime();
        success(first, "count := 33");
        success(first, "selected := (=> :I32 |value :I32| (+ value 100))");
        success(first, "let scratch :I32 = 1");
        Object selected = first.registration().requireBinding("selected")
                .functionValue().orElseThrow().invoke();
        first.close();
        try {
            // The root, its retained values and the structural domain survive.
            assertFalse(root.isClosed());
            assertEquals(100 + 5, invokeFunction(selected, 5));
            assertInstanceOf(EvaluationResult.Closed.class,
                    first.submit("late.lyra", "count"));
            // Reopening reuses the same retained domain with a fresh workspace.
            try (ApplicationAttachment reopened = open(compiled, root)) {
                assertSame(lifetime, reopened.registration().rootLifetime());
                assertEquals("33", scalar(success(reopened, "count")));
                assertEquals("105", scalar(success(reopened, "(selected 5)")));
                // Scratch names from the closed service are not resurrected.
                assertInstanceOf(EvaluationResult.CompilationFailure.class,
                        reopened.submit("bad.lyra", "scratch"));
                success(reopened, "count := 44");
            }
        } finally {
            root.close();
            assertTrue(lifetime.isClosed());
            assertThrows(LyraClosedException.class, () -> invokeFunction(selected, 5));
            loaded.close();
        }
    }

    @Test
    void rootCloseRetiresProducersAndInvalidatesRetainedClosures() throws Throwable {
        AttachableCompileResult.Success compiled = compiledRoot();
        var loaded = LyraRuntime.load(compiled.artifact());
        var root = loaded.instantiate();
        ApplicationAttachment attachment = open(compiled, root);
        success(attachment, "selected := (=> :I32 |value :I32| (+ value 1000))");
        Object selected = attachment.registration().requireBinding("selected")
                .functionValue().orElseThrow().invoke();
        attachment.close();
        try {
            assertEquals(1001, invokeFunction(selected, 1));
        } finally {
            root.close();
        }
        // Root close retires the retained session producer: the escaped
        // closure is invalid even though the attachment service ended earlier.
        assertThrows(LyraClosedException.class, () -> invokeFunction(selected, 1));
        assertInstanceOf(EvaluationResult.Closed.class,
                attachment.submit("late.lyra", "count"));
        loaded.close();
    }

    private static ApplicationAttachment open(AttachableCompileResult.Success compiled,
                                              io.mindspice.lyra.runtime.ModuleHandle root) {
        return ApplicationAttachment.open(root, compiled.context(), SessionOptions.defaults());
    }

    private static final class BlockingInput extends InputStream {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private boolean delivered;

        boolean awaitRead() throws InterruptedException {
            return entered.await(10, TimeUnit.SECONDS);
        }

        void release() {
            released.countDown();
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (delivered) return -1;
            entered.countDown();
            try {
                if (!released.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("timed out waiting to release deterministic constructor input");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("constructor input interrupted", failure);
            }
            target[offset] = (byte) '\n';
            delivered = true;
            return 1;
        }
    }

    private static int invokeFunction(Object closure, int value) throws Throwable {
        Class<?> interfaceType = closure.getClass().getInterfaces()[0];
        try {
            return (int) interfaceType.getMethod("invoke", int.class).invoke(closure, value);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static EvaluationResult.Success success(ApplicationAttachment attachment, String source) {
        EvaluationResult result = attachment.submit("attachment.lyra", source);
        return assertInstanceOf(EvaluationResult.Success.class, result, source + " => " + result);
    }

    private static String scalar(EvaluationResult.Success result) {
        return assertInstanceOf(ValueSnapshot.Scalar.class,
                result.value().orElseThrow().data()).value();
    }
}
