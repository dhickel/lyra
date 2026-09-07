package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.api.AttachableCompileResult;
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
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.ModuleHandle;
import io.mindspice.lyra.runtime.RootTypeRegistration;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 06 retained-context lifetime: root-held session values written before
 * success, failure or cancellation survive reset, service close, detach and
 * reopen on the shared root-lifetime structural domain, and root close
 * retires every retained producer.
 */
class AttachedValueLifetimeTest {
    private static final String ROOT_SOURCE = "let @pub @mut count :I32 = 1\n"
            + "let @pub @mut pair :Tuple<I32,String> = Tuple[1 \"one\"]\n"
            + "let @pub @mut selected :Fn<I32;I32> = (=> |value| (+ value 1))\n"
            + "let @pub readCount :Fn<;I32> = (=> | | count)\n";

    private static AttachableCompileResult.Success compiledRoot() {
        return assertInstanceOf(AttachableCompileResult.Success.class,
                LyraCompiler.compileAttachable(CompileRequest.builder()
                        .source("main.lyra", ROOT_SOURCE)
                        .profile(CompileProfile.ATTACHABLE).build()));
    }

    @Test
    void tupleAndFunctionValuesShareTheRootLifetimeStructuralDomainAcrossReopen()
            throws Throwable {
        AttachableCompileResult.Success compiled = compiledRoot();
        var loaded = LyraRuntime.load(compiled.artifact());
        var root = loaded.instantiate();
        ApplicationAttachment first = open(compiled, root);
        RootTypeRegistration registration = first.registration();
        var rootLifetime = registration.rootLifetime();
        Object oldPair = registration.requireBinding("pair").getter().invoke();
        Object oldSelected = registration.requireBinding("selected").functionValue().orElseThrow().invoke();
        success(first, "pair := Tuple[7 \"seven\"]");
        success(first, "selected := (=> :I32 |value :I32| (* value 3))");
        Object newPair = registration.requireBinding("pair").getter().invoke();
        Object newSelected = registration.requireBinding("selected").functionValue().orElseThrow().invoke();
        first.close();
        try {
            // The old and new values were created in different structural
            // generations but share the root-lifetime type domain.
            assertSame(oldPair.getClass(), newPair.getClass());
            // Function interfaces are structural and shared by the root
            // lifetime domain; closure implementation classes remain
            // generation-local so each lambda retains its own authority.
            assertSame(oldSelected.getClass().getInterfaces()[0],
                    newSelected.getClass().getInterfaces()[0]);
            assertEquals(7, tupleComponent(newPair, 0));
            assertEquals("seven", tupleComponent(newPair, 1));
            assertEquals(1, tupleComponent(oldPair, 0));
            assertEquals(9, invokeFunction(newSelected, 3));
            assertEquals(4, invokeFunction(oldSelected, 3));
            // Reopening reuses the same domain and reads the live values.
            try (ApplicationAttachment reopened = open(compiled, root)) {
                assertSame(rootLifetime, reopened.registration().rootLifetime());
                var snapshot = success(reopened, "pair");
                assertInstanceOf(ValueSnapshot.Aggregate.class,
                        snapshot.value().orElseThrow().data());
                assertEquals("9", scalar(success(reopened, "(selected 3)")));
                success(reopened, "selected := (=> :I32 |value :I32| (+ value 100))");
                assertEquals(103, reopened.registration().requireBinding("selected")
                        .invocation().invoke(3));
            }
        } finally {
            root.close();
            loaded.close();
        }
    }

    @Test
    void valuesStoredBeforeRuntimeFailureSurviveAndRemainReadable() throws Throwable {
        AttachableCompileResult.Success compiled = compiledRoot();
        var loaded = LyraRuntime.load(compiled.artifact());
        var root = loaded.instantiate();
        ApplicationAttachment first = open(compiled, root);
        RootTypeRegistration registration = first.registration();
        EvaluationResult failure = first.submit("failure.lyra", """
                pair := Tuple[5 "five"]
                selected := (=> :I32 |value :I32| (+ value 50))
                let zero :I32 = 0
                (% 1 zero)
                """);
        assertInstanceOf(EvaluationResult.RuntimeFailure.class, failure);
        Object storedPair = registration.requireBinding("pair").getter().invoke();
        Object storedSelected = registration.requireBinding("selected")
                .functionValue().orElseThrow().invoke();
        first.close();
        try {
            assertEquals(5, tupleComponent(storedPair, 0));
            assertEquals("five", tupleComponent(storedPair, 1));
            assertEquals(53, invokeFunction(storedSelected, 3));
            try (ApplicationAttachment reopened = open(compiled, root)) {
                var snapshot = success(reopened, "pair");
                assertEquals("Tuple<I32,String>", snapshot.value().orElseThrow().canonicalType());
                assertEquals("53", scalar(success(reopened, "(selected 3)")));
            }
        } finally {
            root.close();
            loaded.close();
        }
    }

    @Test
    void valuesStoredBeforeCancellationSurviveWithoutCapturingEndedTokens() throws Throwable {
        AttachableCompileResult.Success compiled = compiledRoot();
        var loaded = LyraRuntime.load(compiled.artifact());
        var root = loaded.instantiate();
        try (ApplicationAttachment attachment = open(compiled, root)) {
            RootTypeRegistration registration = attachment.registration();
            var request = new EvaluationRequest(EvaluationId.create(),
                    attachment.currentRevision(), EvaluationSource.of("cancelled.lyra", """
                    pair := Tuple[9 "nine"]
                    selected := (=> :I32 |value :I32| (+ value 9))
                    let spin :Fn<;I32> = (=> || (spin))
                    (spin)
                    count := 99
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
            // The escaped values survive and the staged tail never ran.
            assertEquals(9, tupleComponent(
                    registration.requireBinding("pair").getter().invoke(), 0));
            assertEquals(12, registration.requireBinding("selected").invocation().invoke(3));
            assertEquals(1, registration.requireBinding("count").getter().invoke());
            // No ended cancellation token is captured: later evaluations and
            // main-side calls of the retained closure behave normally.
            success(attachment, "count := 5");
            assertEquals(12, registration.requireBinding("selected").invocation().invoke(3));
            assertEquals("5", scalar(success(attachment, "count")));
        } finally {
            root.close();
            loaded.close();
        }
    }

    @Test
    void transitiveBorrowedGraphReusesTheOriginalProducersAndRejectsReload() throws Throwable {
        AttachableCompileResult.Success compiled = assertInstanceOf(
                AttachableCompileResult.Success.class,
                LyraCompiler.compileAttachable(CompileRequest.builder()
                        .source("main.lyra", "import top\n"
                                + "let @pub total :Fn<;I32> = (=> | | top->::total[])\n")
                        .resolver(SourceResolver.memory(
                                ResolvedSource.memory("top",
                                        URI.create("memory:top.lyra"),
                                        "import leaf\n"
                                                + "let @pub total :Fn<;I32> = (=> | | leaf->::read[])\n"),
                                ResolvedSource.memory("leaf",
                                        URI.create("memory:leaf.lyra"),
                                        "let @mut hidden :I32 = 40\n"
                                                + "let @pub read :Fn<;I32> = (=> | | hidden)\n")))
                        .profile(CompileProfile.ATTACHABLE).build()));
        var loaded = LyraRuntime.load(compiled.artifact());
        var root = loaded.instantiate();
        try (ApplicationAttachment attachment = open(compiled, root)) {
            // The whole transitive application graph is borrowed; imports
            // reach through it to the original leaf state.
            assertEquals("40", scalar(success(attachment, "import top top->::total[]")));
            assertEquals("40", scalar(success(attachment, "(total)")));
        } finally {
            root.close();
            loaded.close();
        }
        // Neither the transitive dependency nor the top module is reloadable.
        for (String target : new String[]{"top", "leaf"}) {
            SessionCompileResult reloadAttempt = LyraCompiler.compileSession(
                    SessionCompileRequest.builder()
                            .source("reload.lyra", "")
                            .sourceId(io.mindspice.lyra.compiler.source.SourceId.path(
                                    "repl/reload-app.lyra"))
                            .snapshot(compiled.context().initialSnapshot())
                            .reloadModule(LogicalModuleId.parse(target))
                            .reloadImportAlias("__reloaded")
                            .build());
            SessionCompileResult.Failure reloadFailure =
                    assertInstanceOf(SessionCompileResult.Failure.class, reloadAttempt);
            assertEquals(CompilerDiagnosticCodes.SESSION_EXTERNAL_BINDING_UNSUPPORTED,
                    reloadFailure.diagnostics().getFirst().code());
        }
    }

    @Test
    void resetPreservesRootHeldSessionProducersWhileDroppingScratchNames() throws Throwable {
        AttachableCompileResult.Success compiled = compiledRoot();
        var loaded = LyraRuntime.load(compiled.artifact());
        var root = loaded.instantiate();
        try (ApplicationAttachment attachment = open(compiled, root)) {
            RootTypeRegistration registration = attachment.registration();
            success(attachment, "let @mut cell :I32 = 21\n"
                    + "let reader :Fn<;I32> = (=> | | cell)\n"
                    + "selected := (=> :I32 |value :I32| (* (reader) value))\n");
            Object stored = registration.requireBinding("selected")
                    .functionValue().orElseThrow().invoke();
            attachment.reset();
            assertEquals(21 * 2, invokeFunction(stored, 2));
            assertInstanceOf(EvaluationResult.CompilationFailure.class,
                    attachment.submit("bad.lyra", "(reader)"));
            // The retained producer keeps its original cell identity.
            success(attachment, "let @mut cell :I32 = 31\n"
                    + "let reader :Fn<;I32> = (=> | | cell)\n");
            assertEquals(21 * 4, invokeFunction(stored, 4));
            assertEquals("31", scalar(success(attachment, "(reader)")));
        } finally {
            root.close();
            loaded.close();
        }
    }

    @Test
    void independentRootsKeepIndependentRetainedValuesAcrossServiceLifetimes()
            throws Throwable {
        AttachableCompileResult.Success compiled = compiledRoot();
        var loaded = LyraRuntime.load(compiled.artifact());
        var firstRoot = loaded.instantiate();
        var secondRoot = loaded.instantiate();
        ApplicationAttachment first = open(compiled, firstRoot);
        ApplicationAttachment second = open(compiled, secondRoot);
        success(first, "selected := (=> :I32 |value :I32| (+ value 1))");
        success(second, "selected := (=> :I32 |value :I32| (+ value 2))");
        Object firstClosure = first.registration().requireBinding("selected")
                .functionValue().orElseThrow().invoke();
        Object secondClosure = second.registration().requireBinding("selected")
                .functionValue().orElseThrow().invoke();
        first.close();
        try {
            assertEquals(4, invokeFunction(firstClosure, 3));
            assertEquals(5, invokeFunction(secondClosure, 3));
            try (ApplicationAttachment reopened = open(compiled, firstRoot)) {
                assertEquals("4", scalar(success(reopened, "(selected 3)")));
                assertEquals("5", scalar(success(second, "(selected 3)")));
                assertNotSame(reopened.registration().rootLifetime(),
                        second.registration().rootLifetime());
            }
        } finally {
            second.close();
            firstRoot.close();
            secondRoot.close();
            loaded.close();
        }
    }

    @Test
    void rootCloseRetiresEveryRetainedProducerAndRejectsReopen() throws Throwable {
        AttachableCompileResult.Success compiled = compiledRoot();
        var loaded = LyraRuntime.load(compiled.artifact());
        var root = loaded.instantiate();
        ApplicationAttachment attachment = open(compiled, root);
        success(attachment, "selected := (=> :I32 |value :I32| (+ value 1000))");
        Object stored = attachment.registration().requireBinding("selected")
                .functionValue().orElseThrow().invoke();
        attachment.close();
        try {
            assertEquals(1001, invokeFunction(stored, 1));
        } finally {
            root.close();
        }
        assertThrows(LyraClosedException.class, () -> invokeFunction(stored, 1));
        // A closed root cannot host a new service, even with the correct context.
        assertThrows(LyraClosedException.class,
                () -> ApplicationAttachment.open(root, compiled.context(),
                        SessionOptions.defaults()));
        assertInstanceOf(EvaluationResult.Closed.class,
                attachment.submit("late.lyra", "count"));
        loaded.close();
    }

    private static ApplicationAttachment open(AttachableCompileResult.Success compiled,
                                              ModuleHandle root) {
        return ApplicationAttachment.open(root, compiled.context(), SessionOptions.defaults());
    }

    private static int invokeFunction(Object closure, int value) throws Throwable {
        Class<?> interfaceType = closure.getClass().getInterfaces()[0];
        try {
            return (int) interfaceType.getMethod("invoke", int.class).invoke(closure, value);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static Object tupleComponent(Object tuple, int index) throws Throwable {
        try {
            return tuple.getClass().getMethod("$lyra$get$" + index).invoke(tuple);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static EvaluationResult.Success success(ApplicationAttachment attachment, String source) {
        EvaluationResult result = attachment.submit("lifetime.lyra", source);
        return assertInstanceOf(EvaluationResult.Success.class, result, source + " => " + result);
    }

    private static String scalar(EvaluationResult.Success result) {
        return assertInstanceOf(ValueSnapshot.Scalar.class,
                result.value().orElseThrow().data()).value();
    }
}
