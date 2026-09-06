package io.mindspice.lyra.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Assertion-grade coverage for the opt-in runtime execution-control slice. */
public final class RuntimeControlTest {
    @Test
    void liveControllerOperationsRemainOwnerThreadConfined() throws Exception {
        LyraOwnerController controller = new LyraOwnerController();
        List<Throwable> failures = new ArrayList<>();
        Thread foreign = new Thread(() -> {
            for (Runnable operation : List.<Runnable>of(
                    () -> controller.status(),
                    () -> controller.isClosed(),
                    controller::safePoint,
                    controller::poll,
                    () -> controller.beginEvaluation(),
                    controller::close)) {
                try {
                    operation.run();
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }
        });
        foreign.start();
        foreign.join();

        assertEquals(6, failures.size());
        assertTrue(failures.stream().allMatch(LyraThreadException.class::isInstance));
        assertEquals(LyraOwnerController.Status.OPEN, controller.status());
        controller.close();
    }

    @Test
    void cancellationCanBeRequestedFromAnotherThreadAndObservedOnlyAtAnActiveSafePoint()
            throws Exception {
        LyraOwnerController controller = new LyraOwnerController();
        LyraOwnerController.EvaluationLease lease = controller.beginEvaluation();
        AtomicReference<Throwable> requesterFailure = new AtomicReference<>();
        AtomicReference<Boolean> requested = new AtomicReference<>();
        Thread canceller = new Thread(() -> {
            try {
                requested.set(controller.requestCancellation(lease.evaluationId()));
            } catch (Throwable failure) {
                requesterFailure.set(failure);
            }
        });
        canceller.start();
        canceller.join();

        assertNull(requesterFailure.get());
        assertEquals(Boolean.TRUE, requested.get());
        assertEquals(LyraCancellationState.REQUESTED, lease.cancellation().state());
        LyraCancellationException signal = assertThrows(
                LyraCancellationException.class, controller::safePoint);
        assertEquals(LyraFailureCategory.CANCEL, signal.category());
        assertEquals("LYR-CANCEL", signal.code());
        assertEquals(lease.evaluationId(), signal.evaluationId());
        assertEquals(LyraCancellationState.OBSERVED, lease.cancellation().state());

        // Observation is terminal for this lease. Generated code cannot
        // accidentally continue by swallowing one signal at a single point.
        assertThrows(LyraCancellationException.class, controller::safePoint);
        lease.close();
        controller.close();
    }

    @Test
    void explicitPollRunsOneRequestOnTheOwnerAndRejectsNestedDispatch() {
        LyraOwnerController controller = new LyraOwnerController();
        AtomicReference<Thread> executedOn = new AtomicReference<>();
        LyraOwnerController.Dispatch dispatch = controller.dispatch(() -> {
            executedOn.set(Thread.currentThread());
            assertThrows(LyraLifecycleException.class, controller::beginEvaluation);
            assertThrows(LyraLifecycleException.class,
                    () -> controller.dispatch(() -> { }));
            assertEquals(LyraOwnerController.Status.EVALUATING, controller.status());
        });

        assertEquals(LyraOwnerController.DispatchStatus.PENDING, dispatch.status());
        assertNull(executedOn.get());
        assertTrue(controller.poll());
        assertSame(Thread.currentThread(), executedOn.get());
        assertEquals(LyraOwnerController.DispatchStatus.COMPLETED, dispatch.status());
        assertTrue(dispatch.isDone());
        assertFalse(controller.poll());
        controller.close();
    }

    @Test
    void applicationCancellationIsSeparateAndDoesNotPoisonLaterEvaluations()
            throws Exception {
        LyraOwnerController controller = new LyraOwnerController();
        LyraCancellation application = controller.applicationCancellation();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread canceller = new Thread(() -> {
            try {
                assertTrue(controller.requestApplicationCancellation());
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        canceller.start();
        canceller.join();

        assertNull(failure.get());
        assertEquals(LyraCancellation.Scope.APPLICATION, application.scope());
        assertTrue(application.isCancellationRequested());

        LyraOwnerController.EvaluationLease first = controller.beginEvaluation();
        assertDoesNotThrow(controller::safePoint);
        first.close();

        LyraOwnerController.EvaluationLease second = controller.beginEvaluation();
        assertDoesNotThrow(controller::safePoint);
        assertTrue(second.cancellation().scope() == LyraCancellation.Scope.EVALUATION);
        second.close();
        controller.close();
    }

    @Test
    void pendingCancellationDoesNotExecuteAndControllerCloseCleansUpIdempotently() {
        LyraOwnerController controller = new LyraOwnerController();
        AtomicBoolean ran = new AtomicBoolean();
        LyraCancellation application = controller.applicationCancellation();
        LyraOwnerController.Dispatch pending = controller.dispatch(() -> ran.set(true));
        assertTrue(pending.cancel());
        assertEquals(LyraOwnerController.DispatchStatus.CANCELLED, pending.status());
        assertFalse(controller.poll());
        assertFalse(ran.get());

        LyraOwnerController.Dispatch retained = controller.dispatch(() -> ran.set(true));
        controller.close();
        assertDoesNotThrow(controller::close);
        assertEquals(LyraOwnerController.Status.CLOSED, controller.status());
        assertTrue(controller.isClosed());
        assertEquals(LyraOwnerController.DispatchStatus.CLOSED, retained.status());
        assertTrue(application.isClosed());
        assertFalse(ran.get());

        assertThrows(LyraClosedException.class, controller::safePoint);
        assertThrows(LyraClosedException.class, controller::beginEvaluation);
        assertThrows(LyraClosedException.class, controller::poll);
        assertThrows(LyraClosedException.class, () -> controller.dispatch(() -> { }));
        assertThrows(LyraClosedException.class, controller::requestCancellation);
        assertThrows(LyraClosedException.class, controller::requestApplicationCancellation);
        assertFalse(retained.cancel());
    }

    @Test
    void closeDoesNotTerminateAnUnrelatedThread() throws Exception {
        LyraOwnerController controller = new LyraOwnerController();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean reachedAfterCancellation = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            entered.countDown();
            try {
                release.await();
                reachedAfterCancellation.set(true);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        worker.start();
        entered.await();

        LyraOwnerController.EvaluationLease lease = controller.beginEvaluation();
        assertTrue(lease.requestCancellation());
        assertThrows(LyraCancellationException.class, controller::safePoint);
        assertTrue(worker.isAlive());
        lease.close();
        controller.close();

        release.countDown();
        worker.join();
        assertTrue(reachedAfterCancellation.get());
        assertFalse(worker.isAlive());
    }

    @Test
    void attachedLifecycleStillGatesControlWithoutChangingItsStateMachine() {
        ModuleLifecycle lifecycle = new ModuleLifecycle();
        LyraOwnerController controller = new LyraOwnerController(lifecycle);

        assertThrows(LyraLifecycleException.class, controller::beginEvaluation);
        assertEquals(LifecycleState.INITIALIZING, lifecycle.state());
        lifecycle.open();
        LyraOwnerController.EvaluationLease lease = controller.beginEvaluation();
        assertEquals(LyraOwnerController.Status.EVALUATING, controller.status());
        lease.close();
        lifecycle.close();
        assertEquals(LifecycleState.CLOSED, lifecycle.state());
        assertThrows(LyraClosedException.class, controller::safePoint);
        controller.close();
    }
}
