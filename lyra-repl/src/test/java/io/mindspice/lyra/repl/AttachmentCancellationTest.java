package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.api.AttachableCompileResult;
import io.mindspice.lyra.compiler.api.CompileProfile;
import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.runtime.LyraLifecycleException;
import io.mindspice.lyra.runtime.LyraOwnerController;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.ModuleHandle;
import io.mindspice.lyra.runtime.RootTypeRegistration;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 07 attachment cancellation: exact queued/active request binding,
 * controller-generation scoping, cooperative observation inside root calls,
 * and usable service/resumed main after every terminal outcome.
 */
class AttachmentCancellationTest {
    private static final String ROOT_SOURCE = "let @pub @mut count :I32 = 0\n"
            + "let @pub add :Fn<I32,I32;I32> = (=> |left right| (+ left right))\n"
            + "let @pub spinRoot :Fn<I64;I64> = (=> |n :I64| ((== n 0) -> 0 : ::spinRoot[(- n 1)]))\n";

    @Test
    void queuedCancellationExecutesNoSourceAndLeavesServiceUsable() throws Throwable {
        try (Fixture fixture = new Fixture(ROOT_SOURCE)) {
            DispatchedEvaluation cancelled = fixture.attachment().submitDispatch(
                    "queued.lyra", "count := 99 let loop :Fn<;I64> = (=> || ::loop[]) (loop)");
            assertFalse(cancelled.isDone());
            assertTrue(cancelled.cancel());
            assertTrue(fixture.attachment().poll());
            assertInstanceOf(EvaluationResult.Cancelled.class, cancelled.awaitResult());
            // The queued request executed no source: no root write occurred.
            assertEquals(0, (int) fixture.binding("count").getter().invoke());
            // Terminal cancellation never poisons the controller.
            LyraOwnerController controller = fixture.registration().controller();
            assertEquals(LyraOwnerController.Status.OPEN, controller.status());
            assertTrue(controller.currentEvaluation().isEmpty());
            // Later synchronous and dispatched work succeeds.
            success(fixture.attachment().submit("after.lyra", "count := 5"));
            DispatchedEvaluation fresh = fixture.attachment().submitDispatch(
                    "fresh.lyra", "(add 20 2)");
            assertTrue(fixture.attachment().poll());
            assertEquals("22", scalar(success(fresh.awaitResult())));
        }
    }

    @Test
    void cancellationDuringSourceEvaluationPreservesCompletedRootWrites()
            throws Throwable {
        try (Fixture fixture = new Fixture(ROOT_SOURCE)) {
            DispatchedEvaluation dispatched = fixture.attachment().submitDispatch(
                    "cancelled.lyra", """
                    count := 55
                    let loop :Fn<;I64> = (=> || ::loop[])
                    (loop)
                    """);
            AtomicReference<Throwable> controlFailure = new AtomicReference<>();
            AtomicBoolean cancelledInsideExecution = new AtomicBoolean();
            Thread control = new Thread(() -> {
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (System.nanoTime() < deadline) {
                        if (insideGeneratedExecution(fixture.attachment().ownerThread())) {
                            cancelledInsideExecution.set(
                                    fixture.attachment().cancel(
                                            dispatched.evaluationId()));
                            return;
                        }
                        Thread.sleep(1);
                    }
                } catch (Throwable failure) {
                    controlFailure.set(failure);
                }
            });
            control.start();
            try {
                assertTrue(fixture.attachment().poll());
            } finally {
                fixture.attachment().cancel(dispatched.evaluationId());
                control.join(31_000);
            }
            assertFalse(control.isAlive());
            assertNull(controlFailure.get());
            assertTrue(cancelledInsideExecution.get());
            assertInstanceOf(EvaluationResult.Cancelled.class, dispatched.awaitResult());
            // The completed write before the loop survives cancellation.
            assertEquals(55, (int) fixture.binding("count").getter().invoke());
            // Later work is admitted normally.
            DispatchedEvaluation later = fixture.attachment().submitDispatch(
                    "later.lyra", "count := 7");
            assertTrue(fixture.attachment().poll());
            assertInstanceOf(EvaluationResult.Success.class, later.awaitResult());
            assertEquals(7, (int) fixture.binding("count").getter().invoke());
        }
    }

    @Test
    void cancellationInsideRootCallsTargetsOnlyThatEvaluation() throws Throwable {
        try (Fixture fixture = new Fixture(ROOT_SOURCE)) {
            DispatchedEvaluation dispatched = fixture.attachment().submitDispatch(
                    "root-call.lyra", "(spinRoot 2000000000)");
            AtomicReference<Throwable> controlFailure = new AtomicReference<>();
            AtomicBoolean observedInsideRootCall = new AtomicBoolean();
            Thread control = new Thread(() -> {
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (System.nanoTime() < deadline) {
                        if (insideGeneratedExecution(fixture.attachment().ownerThread())) {
                            // The submission body is only the root call, so
                            // once a generated closure frame exists the
                            // evaluation is inside the root function.
                            observedInsideRootCall.set(true);
                            assertTrue(fixture.attachment().cancel(
                                    dispatched.evaluationId()));
                            return;
                        }
                        Thread.sleep(1);
                    }
                } catch (Throwable failure) {
                    controlFailure.set(failure);
                }
            });
            control.start();
            try {
                assertTrue(fixture.attachment().poll());
            } finally {
                fixture.attachment().cancel(dispatched.evaluationId());
                control.join(31_000);
            }
            assertFalse(control.isAlive());
            assertNull(controlFailure.get());
            assertTrue(observedInsideRootCall.get());
            assertInstanceOf(EvaluationResult.Cancelled.class, dispatched.awaitResult());
            // Only the dispatched evaluation was cancelled: the service and
            // the live root remain fully usable, and main-side calls still run.
            LyraOwnerController controller = fixture.registration().controller();
            assertEquals(LyraOwnerController.Status.OPEN, controller.status());
            assertTrue(controller.currentEvaluation().isEmpty());
            assertEquals(30, (int) fixture.binding("add").invocation().invoke(10, 20));
            DispatchedEvaluation later = fixture.attachment().submitDispatch(
                    "later.lyra", "count := 12");
            assertTrue(fixture.attachment().poll());
            assertInstanceOf(EvaluationResult.Success.class, later.awaitResult());
            assertEquals(12, (int) fixture.binding("count").getter().invoke());
        }
    }

    @Test
    void cancellationBeforeAdmissionAndAfterCompletionNeverSticks() throws Throwable {
        try (Fixture fixture = new Fixture(ROOT_SOURCE)) {
            // No active request: cancellation matches nothing.
            assertFalse(fixture.attachment().cancel(EvaluationId.create()));
            DispatchedEvaluation completed = fixture.attachment().submitDispatch(
                    "done.lyra", "(add 2 3)");
            assertTrue(fixture.attachment().poll());
            EvaluationResult result = completed.awaitResult();
            assertInstanceOf(EvaluationResult.Success.class, result);
            // After completion the exact identity is no longer cancellable
            // and the terminal result is unchanged.
            assertFalse(completed.cancel());
            assertFalse(fixture.attachment().cancel(completed.evaluationId()));
            assertEquals("5", scalar(success(completed.awaitResult())));
            // A late cancel cannot affect the next, unrelated request.
            DispatchedEvaluation next = fixture.attachment().submitDispatch(
                    "next.lyra", "(add 40 2)");
            assertTrue(fixture.attachment().poll());
            assertEquals("42", scalar(success(next.awaitResult())));
        }
    }

    @Test
    void staleDispatchHandleCannotCancelAReusedRequestIdentity() throws Throwable {
        try (Fixture fixture = new Fixture(ROOT_SOURCE)) {
            EvaluationId reused = EvaluationId.create();
            DispatchedEvaluation first = fixture.attachment().submitDispatch(
                    new EvaluationRequest(reused, fixture.attachment().currentRevision(),
                            EvaluationSource.of("first.lyra", "(add 1 2)")));
            assertTrue(fixture.attachment().poll());
            assertInstanceOf(EvaluationResult.Success.class, first.awaitResult());

            DispatchedEvaluation second = fixture.attachment().submitDispatch(
                    new EvaluationRequest(reused, fixture.attachment().currentRevision(),
                            EvaluationSource.of("second.lyra", "count := 9")));
            // The old handle has the same caller UUID but is not the admitted
            // operation, so its late cancellation cannot reach the new work.
            assertFalse(first.cancel());
            assertTrue(second.cancel());
            assertTrue(fixture.attachment().poll());
            assertInstanceOf(EvaluationResult.Cancelled.class, second.awaitResult());
            assertEquals(0, (int) fixture.binding("count").getter().invoke());
        }
    }

    @Test
    void disconnectAndReopenBindsCancellationToTheServiceGeneration()
            throws Throwable {
        AttachableCompileResult.Success compiled = compiledRoot(ROOT_SOURCE);
        var loaded = LyraRuntime.load(compiled.artifact());
        var root = loaded.instantiate();
        ApplicationAttachment first = open(compiled, root);
        DispatchedEvaluation completed = first.submitDispatch("one.lyra", "(add 1 2)");
        assertTrue(first.poll());
        assertInstanceOf(EvaluationResult.Success.class, completed.awaitResult());
        // Close while a queued request is active is rejected, never stranding
        // the request without a terminal outcome.
        DispatchedEvaluation pending = first.submitDispatch("two.lyra", "count := 9");
        assertThrows(LyraLifecycleException.class, first::close);
        assertTrue(pending.cancel());
        assertTrue(first.poll());
        assertInstanceOf(EvaluationResult.Cancelled.class, pending.awaitResult());
        first.close();
        try {
            // Stale handles and stale identities cannot touch the new
            // generation's work.
            assertFalse(completed.cancel());
            assertFalse(pending.cancel());
            assertFalse(first.cancel(completed.evaluationId()));
            try (ApplicationAttachment reopened = open(compiled, root)) {
                DispatchedEvaluation fresh = reopened.submitDispatch(
                        "fresh.lyra", "count := 3");
                assertTrue(reopened.poll());
                assertInstanceOf(EvaluationResult.Success.class, fresh.awaitResult());
                assertEquals(3, (int) reopened.registration()
                        .requireBinding("count").getter().invoke());
            }
        } finally {
            root.close();
            loaded.close();
        }
    }

    @Test
    void controllerCloseCompletesAnAttachmentDispatchAndReleasesAdmission()
            throws Throwable {
        try (Fixture fixture = new Fixture(ROOT_SOURCE)) {
            RootTypeRegistration registration = fixture.registration();
            var countGetter = fixture.binding("count").getter();
            DispatchedEvaluation pending = fixture.attachment().submitDispatch(
                    "closed.lyra", "count := 9");
            assertFalse(pending.isDone());

            // A host may close the registration directly. The controller must
            // publish the queued request's terminal state back to the
            // attachment instead of leaving its active slot stranded.
            registration.close();
            assertInstanceOf(EvaluationResult.Closed.class, pending.awaitResult());
            assertFalse(pending.cancel());
            assertInstanceOf(EvaluationResult.Closed.class,
                    fixture.attachment().submit("after-close.lyra", "count"));

            // The attachment can still tear down its workspace after the
            // controller-side close, and no operation was executed.
            assertEquals(0, (int) countGetter.invoke());
        }
    }

    @Test
    void reentrantSubmitResetAndCloseNeverBeginASecondEvaluation() throws Throwable {
        try (Fixture fixture = new Fixture(ROOT_SOURCE)) {
            AtomicReference<EvaluationResult> nestedSubmit = new AtomicReference<>();
            AtomicReference<Throwable> resetFailure = new AtomicReference<>();
            AtomicReference<Throwable> closeFailure = new AtomicReference<>();
            LyraOwnerController.Dispatch hostOperation = fixture.registration().controller()
                    .dispatch(() -> {
                        nestedSubmit.set(fixture.attachment().submit(
                                "nested.lyra", "count := 99"));
                        try {
                            fixture.attachment().reset();
                        } catch (Throwable failure) {
                            resetFailure.set(failure);
                        }
                        try {
                            fixture.attachment().close();
                        } catch (Throwable failure) {
                            closeFailure.set(failure);
                        }
                    });

            assertTrue(fixture.attachment().poll());
            assertEquals(LyraOwnerController.DispatchStatus.COMPLETED,
                    hostOperation.status());
            assertInstanceOf(EvaluationResult.Busy.class, nestedSubmit.get());
            assertInstanceOf(LyraLifecycleException.class, resetFailure.get());
            assertInstanceOf(LyraLifecycleException.class, closeFailure.get());
            assertEquals(0, (int) fixture.binding("count").getter().invoke());
            success(fixture.attachment().submit("after-reentrant.lyra", "count := 7"));
            assertEquals(7, (int) fixture.binding("count").getter().invoke());
        }
    }

    @Test
    void rootCloseRetiresServiceAndDuplicateSimultaneousServiceIsRejected()
            throws Throwable {
        AttachableCompileResult.Success compiled = compiledRoot(ROOT_SOURCE);
        var loaded = LyraRuntime.load(compiled.artifact());
        var root = loaded.instantiate();
        ApplicationAttachment attachment = open(compiled, root);
        try {
            assertThrows(LyraLifecycleException.class,
                    () -> open(compiled, root));
            // The root cannot retire under a live service: the registration
            // must close first so no request is ever stranded.
            assertThrows(LyraLifecycleException.class, root::close);
            attachment.close();
            // Root close retires the dependent producers and the retained
            // structural domain.
            root.close();
            assertInstanceOf(EvaluationResult.Closed.class,
                    attachment.submit("late.lyra", "count"));
            DispatchedEvaluation late = attachment.submitDispatch(
                    "late.lyra", "count := 1");
            assertInstanceOf(EvaluationResult.Closed.class, late.awaitResult());
        } finally {
            attachment.close();
            if (!root.isClosed()) root.close();
            loaded.close();
        }
    }

    /* Fixture and helpers. */

    private static AttachableCompileResult.Success compiledRoot(String source) {
        return assertInstanceOf(AttachableCompileResult.Success.class,
                LyraCompiler.compileAttachable(CompileRequest.builder()
                        .source("main.lyra", source)
                        .profile(CompileProfile.ATTACHABLE).build()));
    }

    private static ApplicationAttachment open(AttachableCompileResult.Success compiled,
                                              ModuleHandle root) {
        return ApplicationAttachment.open(root, compiled.context(),
                SessionOptions.defaults());
    }

    private static final class Fixture implements AutoCloseable {
        private final AttachableCompileResult.Success compiled;
        private final io.mindspice.lyra.runtime.LoadedArtifact loaded;
        private final ModuleHandle root;
        private final ApplicationAttachment attachment;

        private Fixture(String source) {
            compiled = compiledRoot(source);
            loaded = LyraRuntime.load(compiled.artifact());
            root = loaded.instantiate();
            attachment = ApplicationAttachment.open(
                    root, compiled.context(), SessionOptions.defaults());
        }

        private ApplicationAttachment attachment() {
            return attachment;
        }

        private RootTypeRegistration registration() {
            return attachment.registration();
        }

        private RootTypeRegistration.Binding binding(String name) {
            return attachment.registration().requireBinding(name);
        }

        @Override
        public void close() {
            attachment.close();
            root.close();
            loaded.close();
        }
    }

    private static boolean insideGeneratedExecution(Thread owner) {
        return java.util.Arrays.stream(owner.getStackTrace())
                .anyMatch(frame -> frame.getClassName().contains("$lyra$")
                        && frame.getMethodName().equals("invoke"));
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
