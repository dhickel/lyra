package io.mindspice.lyra.repl.remote;

import io.mindspice.lyra.repl.EvaluationId;
import io.mindspice.lyra.repl.EvaluationRequest;
import io.mindspice.lyra.repl.EvaluationResult;
import io.mindspice.lyra.repl.SessionRevision;

import java.util.Objects;
import java.util.UUID;

/**
 * Owner-confined session seam used by the protocol service. Evaluation,
 * reset, revision and query methods are called only from the supplied owner
 * dispatcher. {@link #cancel(EvaluationId)} is the sole cross-thread control
 * hook and must be safe for an authenticated controller to call.
 */
public interface RemoteSessionAdapter {
    UUID sessionId();

    SessionRevision revision();

    EvaluationResult evaluate(EvaluationRequest request, RemoteCancellation cancellation);

    default boolean cancel(EvaluationId evaluationId) {
        Objects.requireNonNull(evaluationId, "evaluationId");
        return false;
    }

    void reset();

    default RemoteQuery.Result query(RemoteQuery query) {
        Objects.requireNonNull(query, "query");
        return RemoteQuery.Result.unavailable(
                "the attached session does not expose this console query");
    }
}
