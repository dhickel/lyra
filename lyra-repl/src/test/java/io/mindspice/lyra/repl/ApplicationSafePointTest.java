package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.api.AttachableCompileResult;
import io.mindspice.lyra.compiler.api.CompileProfile;
import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.runtime.LyraOwnerController;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.LyraThreadException;
import io.mindspice.lyra.runtime.ModuleHandle;
import io.mindspice.lyra.runtime.RootTypeRegistration;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Phase 07 generated application polling: attachable function/call/form/
 * self-tail-loop boundaries dispatch exactly one pending request on the
 * original owner, initialization never dispatches, and the admitted
 * evaluation context is reused without nested dispatch or double leases.
 */
class ApplicationSafePointTest {
    private static final String ROOT_SOURCE = "let @pub @mut count :I32 = 0\n"
            + "let @pub add :Fn<I32,I32;I32> = (=> |left right| (+ left right))\n"
            + "let @pub tick :Fn<;I32> = (=> | | count)\n"
            + "let @pub spin :Fn<I64;I64> = (=> |n :I64| ((== n 0) -> 0 : ::spin[(- n 1)]))\n"
            + "let @pub @mut selected :Fn<I32;I32> = (=> |value| (+ value 1))\n"
            + "let @pub replacement :Fn<I32;I32> = (=> |value| (+ value 2))\n"
            + "let @pub @mut values :Array<I32> = Array<I32>[1 2]\n"
            + "let @pub readFirst :Fn<;I32> = (=> | | values[0])\n"
            + "let @pub apply :Fn<Fn<I32;I32>,I32;I32> = (=> |f x| (f x))\n";

    @Test
    void generatedFunctionBoundaryServicesOnePendingRequestAndResumesTheCaller()
            throws Throwable {
        try (Fixture fixture = new Fixture(ROOT_SOURCE)) {
            DispatchedEvaluation first = fixture.attachment().submitDispatch(
                    "poll.lyra", "count := 42");
            assertFalse(first.isDone());
            // Idle initialized application code admits at most one pending
            // request; a second publication is Busy, not queued.
            DispatchedEvaluation second = fixture.attachment().submitDispatch(
                    "poll-2.lyra", "count := 1");
            assertInstanceOf(EvaluationResult.Busy.class, second.awaitResult());
            assertEquals(0, (int) fixture.binding("count").getter().invoke());
            // The function boundary of `tick` services the pending request and
            // then reads the mutated live root storage.
            assertEquals(42, (int) fixture.binding("tick").invocation().invoke());
            assertInstanceOf(EvaluationResult.Success.class, first.awaitResult());
            assertEquals(42, (int) fixture.binding("count").getter().invoke());
            assertFalse(fixture.attachment().poll());
        }
    }

    @Test
    void selfTailBackedgesServiceExactlyOneRequestAndAdmitNoNestedEvaluation()
            throws Throwable {
        try (Fixture fixture = new Fixture(ROOT_SOURCE)) {
            DispatchedEvaluation dispatched = fixture.attachment().submitDispatch(
                    "spin.lyra", """
                    count := 7
                    let loop :Fn<I64;I64> = (=> |n :I64| ((== n 0) -> 0 : ::loop[(- n 1)]))
                    (loop 2000000)
                    """);
            AtomicReference<Throwable> controlFailure = new AtomicReference<>();
            AtomicReference<EvaluationResult> nestedSync = new AtomicReference<>();
            AtomicReference<EvaluationResult> nestedDispatch = new AtomicReference<>();
            Thread control = new Thread(() -> {
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (System.nanoTime() < deadline) {
                        if (insideGeneratedExecution(ownerStackOwner(fixture))) {
                            break;
                        }
                        Thread.sleep(1);
                    }
                    assertTrue(insideGeneratedExecution(ownerStackOwner(fixture)));
                    // While the dispatched evaluation is active, both a direct
                    // synchronous submission and a second dispatch are Busy.
                    nestedSync.set(fixture.attachment().submit(
                            "nested-sync.lyra", "count := 99"));
                    nestedDispatch.set(fixture.attachment().submitDispatch(
                            "nested.lyra", "count := 99").awaitResult());
                } catch (Throwable failure) {
                    controlFailure.set(failure);
                }
            });
            control.start();
            // The self-tail backedge inside the root `spin` function services
            // the dispatched request; the caller then resumes its own loop.
            assertEquals(0L, (long) fixture.binding("spin").invocation().invoke(2_000_000L));
            control.join(30_000);
            assertFalse(control.isAlive());
            assertNull(controlFailure.get());
            assertInstanceOf(EvaluationResult.Success.class, dispatched.awaitResult());
            assertEquals(7, (int) fixture.binding("count").getter().invoke());
            assertInstanceOf(EvaluationResult.Busy.class, nestedSync.get());
            assertInstanceOf(EvaluationResult.Busy.class, nestedDispatch.get());
            // Exactly one lease was active and it was released on every path.
            LyraOwnerController controller = fixture.registration().controller();
            assertEquals(LyraOwnerController.Status.OPEN, controller.status());
            assertTrue(controller.currentEvaluation().isEmpty());
        }
    }

    @Test
    void explicitJavaHostPollServicesDispatchedWorkOnTheOwnerWithoutAnExecutor()
            throws Throwable {
        try (Fixture fixture = new Fixture(ROOT_SOURCE)) {
            DispatchedEvaluation dispatched = fixture.attachment().submitDispatch(
                    "host.lyra", "count := (add 20 22)");
            assertFalse(dispatched.isDone());
            assertTrue(fixture.attachment().poll());
            EvaluationResult result = dispatched.awaitResult();
            assertInstanceOf(EvaluationResult.Success.class, result);
            assertEquals(42, (int) fixture.binding("count").getter().invoke());
            assertFalse(fixture.attachment().poll());
            // The evaluation invoked the root closure `add`, whose generated
            // invocation performs the owner-thread check: a foreign-thread
            // execution would have failed with LYR-THREAD instead of success.
            // Status is safely published control metadata readable from any
            // thread without a root read.
            AtomicReference<Throwable> foreignFailure = new AtomicReference<>();
            AtomicReference<String> probe = new AtomicReference<>();
            Thread foreign = new Thread(() -> {
                try {
                    probe.set(dispatched.isDone() + ":" + dispatched.result().isPresent());
                    assertThrows(LyraThreadException.class,
                            () -> fixture.attachment().poll());
                } catch (Throwable failure) {
                    foreignFailure.set(failure);
                }
            });
            foreign.start();
            foreign.join();
            assertNull(foreignFailure.get());
            assertEquals("true:true", probe.get());
            // Direct synchronous submission remains owner-thread confined.
            Thread idleForeign = new Thread(() -> {
                try {
                    fixture.attachment().submit("foreign.lyra", "count := 1");
                } catch (Throwable failure) {
                    foreignFailure.set(failure);
                }
            });
            idleForeign.start();
            idleForeign.join();
            assertInstanceOf(LyraThreadException.class, foreignFailure.get());
        }
    }

    @Test
    void dispatchBoundariesAreInertDuringInitializationAndNeverServiceDuringInit()
            throws Throwable {
        String source = "let @pub add :Fn<I32,I32;I32> = (=> |left right| (+ left right))\n"
                + "let @pub answer :I32 = (add 40 2)\n"
                + "let @pub @mut count :I32 = 0\n";
        AttachableCompileResult.Success compiled = compiledRoot(source);
        // The generated dispatch boundary exists on closures (function/call/
        // form/self-tail positions); the module state defines the inert hook.
        Map<String, Integer> invocations =
                attachmentSafePointInvocations(compiled.artifact().classes());
        String stateClass = hookDefiningClass(compiled.artifact().classes());
        assertTrue(invocations.values().stream().anyMatch(count -> count >= 1),
                "root closures must contain dispatch boundaries: " + invocations);
        assertTrue(invocations.keySet().stream().anyMatch(key -> key.contains("$lyra$closure")),
                "closure dispatch boundaries are missing: " + invocations);
        assertFalse(stateClass.isBlank());
        var loaded = LyraRuntime.load(compiled.artifact());
        // Constructing the first root reaches the hook from the initializer's
        // call site (`answer = (add 40 2)`) while the lifecycle is still
        // INITIALIZING and no controller exists: initialization must never
        // dispatch, and the inert hook must not fail construction.
        var firstRoot = loaded.instantiate();
        try (ApplicationAttachment attachment = ApplicationAttachment.open(
                firstRoot, compiled.context(), SessionOptions.defaults())) {
            RootTypeRegistration registration = attachment.registration();
            assertEquals(42, (int) registration.requireBinding("answer")
                    .getter().invoke());
            DispatchedEvaluation pending = attachment.submitDispatch(
                    "pending.lyra", "count := 9");
            assertFalse(pending.isDone());
            // A second root's initializers reach the same inert hook while a
            // request is pending on the first service; construction must not
            // consume it.
            var secondRoot = loaded.instantiate();
            try {
                assertFalse(pending.isDone());
                assertEquals(0, (int) registration.requireBinding("count")
                        .getter().invoke());
            } finally {
                secondRoot.close();
            }
            assertTrue(attachment.poll());
            assertInstanceOf(EvaluationResult.Success.class, pending.awaitResult());
            assertEquals(9, (int) registration.requireBinding("count").getter().invoke());
        } finally {
            firstRoot.close();
            loaded.close();
        }
    }

    @Test
    void infiniteSelfTailMainServicesOneOwnerRequestThenResumes() throws Exception {
        DriverResult result = runDriver("infiniteSelfTailMain", Duration.ofSeconds(180));
        assertEquals(0, result.status(), "driver output:\n" + result.output());
        assertTrue(result.output().contains("READY"), result.output());
        assertTrue(result.output().contains("SERVICED"), result.output());
        assertTrue(result.output().contains("RESUMED"), result.output());
        assertFalse(result.output().contains("FAILED"), result.output());
    }

    @Test
    void mutableCallableAndAggregateProvenanceAcrossSafePointInterleaving()
            throws Throwable {
        try (Fixture fixture = new Fixture(ROOT_SOURCE)) {
            // A pending request compiles against the current live storage at
            // execution time, not against initializer facts.
            DispatchedEvaluation pending = fixture.attachment().submitDispatch(
                    "interleaved.lyra", "count := (readFirst)");
            fixture.binding("values").setter().orElseThrow().invoke(new int[]{50});
            Object rootReplacement = fixture.binding("replacement")
                    .functionValue().orElseThrow().invoke();
            fixture.binding("selected").setter().orElseThrow().invoke(rootReplacement);
            assertTrue(fixture.attachment().poll());
            assertInstanceOf(EvaluationResult.Success.class, pending.awaitResult());
            assertEquals(50, (int) fixture.binding("count").getter().invoke());
            // Session-side callable replacement is the current value for main.
            DispatchedEvaluation replace = fixture.attachment().submitDispatch(
                    "replace.lyra", "selected := (=> :I32 |value :I32| (* value 10))");
            assertTrue(fixture.attachment().poll());
            assertInstanceOf(EvaluationResult.Success.class, replace.awaitResult());
            assertEquals(30, (int) fixture.binding("selected").invocation().invoke(3));
            // Higher-order transfer through the real root function.
            DispatchedEvaluation higherOrder = fixture.attachment().submitDispatch(
                    "higher.lyra", "(apply (=> |x| (+ x 1)) 10)");
            assertTrue(fixture.attachment().poll());
            assertEquals("11", scalar(success(higherOrder.awaitResult())));
            // Element mutation through the public aggregate stays an ordinary
            // attachable-boundary diagnostic.
            DispatchedEvaluation element = fixture.attachment().submitDispatch(
                    "element.lyra", "values[0] := 9");
            assertTrue(fixture.attachment().poll());
            assertInstanceOf(EvaluationResult.CompilationFailure.class,
                    element.awaitResult());
        }
    }

    @Test
    void terminalOutcomesNeverPoisonTheControllerOrDoubleLease() throws Throwable {
        try (Fixture fixture = new Fixture(ROOT_SOURCE)) {
            LyraOwnerController controller = fixture.registration().controller();
            DispatchedEvaluation cancelled = fixture.attachment().submitDispatch(
                    "cancelled.lyra", "count := 99 let loop :Fn<;I64> = (=> || ::loop[]) (loop)");
            assertTrue(cancelled.cancel());
            assertTrue(fixture.attachment().poll());
            assertInstanceOf(EvaluationResult.Cancelled.class, cancelled.awaitResult());
            assertEquals(0, (int) fixture.binding("count").getter().invoke());
            assertEquals(LyraOwnerController.Status.OPEN, controller.status());
            assertTrue(controller.currentEvaluation().isEmpty());

            DispatchedEvaluation compileFailure = fixture.attachment().submitDispatch(
                    "bad.lyra", "absent");
            assertTrue(fixture.attachment().poll());
            assertInstanceOf(EvaluationResult.CompilationFailure.class,
                    compileFailure.awaitResult());
            assertEquals(LyraOwnerController.Status.OPEN, controller.status());
            assertTrue(controller.currentEvaluation().isEmpty());

            DispatchedEvaluation runtimeFailure = fixture.attachment().submitDispatch(
                    "bad.lyra", "count := 3 let zero :I32 = 0 (% 1 zero)");
            assertTrue(fixture.attachment().poll());
            assertInstanceOf(EvaluationResult.RuntimeFailure.class,
                    runtimeFailure.awaitResult());
            // Completed root writes survive the runtime failure.
            assertEquals(3, (int) fixture.binding("count").getter().invoke());
            assertEquals(LyraOwnerController.Status.OPEN, controller.status());
            assertTrue(controller.currentEvaluation().isEmpty());

            // Both the synchronous API and fresh dispatches still work.
            success(fixture.attachment().submit("after.lyra", "count := 5"));
            assertEquals(5, (int) fixture.binding("count").getter().invoke());
            DispatchedEvaluation fresh = fixture.attachment().submitDispatch(
                    "fresh.lyra", "(add 20 2)");
            assertTrue(fixture.attachment().poll());
            assertEquals("22", scalar(success(fresh.awaitResult())));
        }
    }

    /* Fixture and helpers. */

    private static AttachableCompileResult.Success compiledRoot(String source) {
        return assertInstanceOf(AttachableCompileResult.Success.class,
                LyraCompiler.compileAttachable(CompileRequest.builder()
                        .source("main.lyra", source)
                        .profile(CompileProfile.ATTACHABLE).build()));
    }

    private static final class Fixture implements AutoCloseable {
        private final AttachableCompileResult.Success compiled;
        private final io.mindspice.lyra.runtime.LoadedArtifact loaded;
        private final ModuleHandle root;
        private final ApplicationAttachment attachment;

        private Fixture(String source) {
            this(compiledRoot(source), null);
        }

        private Fixture(AttachableCompileResult.Success compiled, ModuleHandle existingRoot) {
            this.compiled = compiled;
            this.loaded = existingRoot == null ? LyraRuntime.load(compiled.artifact()) : null;
            this.root = existingRoot == null ? loaded.instantiate() : existingRoot;
            this.attachment = ApplicationAttachment.open(
                    root, compiled.context(), SessionOptions.defaults());
        }

        private ApplicationAttachment attachment() {
            return attachment;
        }

        private RootTypeRegistration registration() {
            return attachment.registration();
        }

        private RootTypeRegistration.Binding binding(String name) {
            return registration().requireBinding(name);
        }

        @Override
        public void close() {
            attachment.close();
            root.close();
            if (loaded != null) loaded.close();
        }
    }

    private static Thread ownerStackOwner(Fixture fixture) {
        return fixture.attachment().ownerThread();
    }

    private static boolean insideGeneratedExecution(Thread owner) {
        return java.util.Arrays.stream(owner.getStackTrace())
                .anyMatch(frame -> frame.getClassName().contains("$lyra$")
                        && frame.getMethodName().equals("invoke"));
    }

    private static Map<String, Integer> attachmentSafePointInvocations(
            Map<String, byte[]> classes) {
        Map<String, Integer> counts = new HashMap<>();
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            ClassModel model = ClassFile.of().parse(entry.getValue());
            int count = 0;
            for (MethodModel method : model.methods()) {
                CodeModel code = method.code().orElse(null);
                if (code == null) continue;
                for (var element : code) {
                    if (element instanceof InvokeInstruction invoke
                            && invoke.name().stringValue()
                            .equals("$lyra$attachmentSafePoint")) {
                        count++;
                    }
                }
            }
            if (count > 0) counts.put(entry.getKey(), count);
        }
        return counts;
    }

    private static String hookDefiningClass(Map<String, byte[]> classes) {
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            ClassModel model = ClassFile.of().parse(entry.getValue());
            for (MethodModel method : model.methods()) {
                if (method.methodName().stringValue()
                        .equals("$lyra$attachmentSafePoint")) {
                    return entry.getKey();
                }
            }
        }
        fail("attachable artifact has no attachment safe-point hook");
        return null;
    }

    private record DriverResult(int status, String output) {
    }

    private static DriverResult runDriver(String scenario, Duration timeout)
            throws Exception {
        Process process = new ProcessBuilder(javaCommand(), "-cp",
                System.getProperty("java.class.path"),
                SafePointMainDriver.class.getName(), scenario)
                .redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try {
                process.getInputStream().transferTo(output);
            } catch (IOException ignored) {
            }
        });
        reader.start();
        if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            reader.join(10_000);
            fail("safe-point driver did not terminate within " + timeout
                    + "; output so far:\n" + output.toString(StandardCharsets.UTF_8));
        }
        reader.join(10_000);
        return new DriverResult(process.exitValue(),
                output.toString(StandardCharsets.UTF_8));
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static EvaluationResult.Success success(EvaluationResult result) {
        return assertInstanceOf(EvaluationResult.Success.class, result,
                () -> "expected success: " + result);
    }

    private static String scalar(EvaluationResult.Success result) {
        return assertInstanceOf(ValueSnapshot.Scalar.class,
                result.value().orElseThrow().data()).value();
    }
}
