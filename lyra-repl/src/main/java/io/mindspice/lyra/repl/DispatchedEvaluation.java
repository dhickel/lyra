package io.mindspice.lyra.repl;

import io.mindspice.lyra.runtime.LyraInternalException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handle for one admitted owner-dispatched evaluation.
 *
 * <p>The evaluation executes on the application owner thread at the next
 * generated safe point or explicit {@link ApplicationAttachment#poll()}.
 * This handle publishes the immutable terminal {@link EvaluationResult} from
 * any thread using safely published control metadata only; it never reads
 * live root storage.  Cancellation binds to the exact admitted request and
 * its controller generation, so a stale or disconnected cancel can never
 * affect later work.</p>
 */
public final class DispatchedEvaluation {
    private final ApplicationAttachment attachment;
    private final EvaluationRequest request;
    private final AtomicReference<EvaluationResult> terminal = new AtomicReference<>();
    private final AtomicReference<Throwable> internalFailure = new AtomicReference<>();
    private final CountDownLatch completion = new CountDownLatch(1);

    DispatchedEvaluation(ApplicationAttachment attachment, EvaluationRequest request) {
        this.attachment = Objects.requireNonNull(attachment, "attachment");
        this.request = Objects.requireNonNull(request, "request");
    }

    /** Creates an immediately terminal handle for a rejected submission. */
    static DispatchedEvaluation rejected(ApplicationAttachment attachment,
                                         EvaluationRequest request,
                                         EvaluationResult result) {
        DispatchedEvaluation handle = new DispatchedEvaluation(attachment, request);
        handle.publish(result);
        return handle;
    }

    public EvaluationId evaluationId() {
        return request.evaluationId();
    }

    public EvaluationRequest request() {
        return request;
    }

    public boolean isDone() {
        return completion.getCount() == 0;
    }

    /** Safely published terminal result, if the evaluation has completed. */
    public Optional<EvaluationResult> result() {
        return Optional.ofNullable(terminal.get());
    }

    /**
     * Blocks the calling (non-owner) thread until the owner publishes a
     * terminal outcome.  Expected outcomes are results; an internal failure
     * is rethrown rather than disguised as a result.
     */
    public EvaluationResult awaitResult() throws InterruptedException {
        completion.await();
        Throwable failure = internalFailure.get();
        if (failure != null) {
            rethrow(failure);
        }
        return Objects.requireNonNull(terminal.get(), "terminal result");
    }

    /**
     * Requests cooperative cancellation for exactly this evaluation.  Safe
     * from any thread; returns {@code false} once the evaluation is no longer
     * the attachment's active request or the service generation has changed.
     */
    public boolean cancel() {
        // Bind cancellation to this admitted handle, not only its caller
        // supplied UUID. A reused request identity must not let a late handle
        // cancel a later operation in the same service generation.
        return attachment.cancel(this);
    }

    void publish(EvaluationResult result) {
        Objects.requireNonNull(result, "result");
        terminal.set(result);
        completion.countDown();
    }

    void publishFailure(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        internalFailure.set(failure);
        completion.countDown();
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new LyraInternalException(
                "owner-dispatched evaluation failed", List.of(), List.of(), failure);
    }
}
