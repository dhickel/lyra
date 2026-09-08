package io.mindspice.lyra.repl.remote;

import io.mindspice.lyra.repl.EvaluationId;
import io.mindspice.lyra.repl.EvaluationRequest;
import io.mindspice.lyra.repl.EvaluationResult;
import io.mindspice.lyra.repl.EvaluationSource;
import io.mindspice.lyra.repl.SessionRevision;

import java.util.Objects;
import java.util.UUID;

/**
 * Owner-confined session seam used by the protocol service. Evaluation,
 * load, reload, reset, completion, revision and query methods are called
 * only from the supplied owner dispatcher. {@link #cancel(EvaluationId)} is
 * the sole cross-thread control hook and must be safe for a connected
 * controller to call. The seam carries no credential surface of any kind.
 */
public interface RemoteSessionAdapter {
    UUID sessionId();

    SessionRevision revision();

    EvaluationResult evaluate(EvaluationRequest request, RemoteCancellation cancellation);

    /**
     * Loads one UTF-8 server-side file exactly once into a captured
     * file-URI source and submits it exactly once. Implementations must
     * preflight source and envelope bounds before any Lyra effect runs.
     */
    default EvaluationResult load(ProtocolMessage.LoadRequest request,
                                  RemoteCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        throw new RemoteSessionUnavailableException(
                "load is unavailable on the attached session");
    }

    /**
     * Submits a source captured by the owner operation. Built-in adapters
     * override this overload so the server-side file is never reread.
     */
    default EvaluationResult load(ProtocolMessage.LoadRequest request,
                                  EvaluationSource source,
                                  RemoteCancellation cancellation) {
        Objects.requireNonNull(source, "source");
        return load(request, cancellation);
    }

    /**
     * Loads with the server's outbound frame bound. Built-in adapters use
     * this seam to capture and preflight the file once; older adapters retain
     * source compatibility through the two-argument overload.
     */
    default EvaluationResult load(ProtocolMessage.LoadRequest request,
                                  RemoteCancellation cancellation,
                                  int maxFrameBytes) {
        return load(request, cancellation);
    }

    /** Reloads one retained module by logical name or namespace alias exactly once. */
    default EvaluationResult reload(ProtocolMessage.ReloadRequest request,
                                    RemoteCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        throw new RemoteSessionUnavailableException(
                "reload is unavailable on the attached session");
    }

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

    default RemoteCompletion.Result complete(RemoteCompletion request) {
        Objects.requireNonNull(request, "request");
        return RemoteCompletion.Result.unavailable(
                "the attached session does not expose completion");
    }
}
