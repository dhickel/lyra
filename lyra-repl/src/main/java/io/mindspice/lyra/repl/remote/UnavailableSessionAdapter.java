package io.mindspice.lyra.repl.remote;

import io.mindspice.lyra.repl.EvaluationId;
import io.mindspice.lyra.repl.EvaluationRequest;
import io.mindspice.lyra.repl.EvaluationResult;
import io.mindspice.lyra.repl.SessionRevision;

import java.util.Objects;
import java.util.UUID;

/** Explicit closed/unavailable seam for an artifact without live session linkage. */
public final class UnavailableSessionAdapter implements RemoteSessionAdapter {
    private final UUID sessionId;
    private final String detail;

    public UnavailableSessionAdapter(UUID sessionId, String detail) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.detail = ProtocolValues.text(detail, "detail", 4096);
    }

    @Override
    public UUID sessionId() {
        return sessionId;
    }

    @Override
    public SessionRevision revision() {
        return SessionRevision.initial();
    }

    @Override
    public EvaluationResult evaluate(EvaluationRequest request, RemoteCancellation cancellation) {
        throw new RemoteSessionUnavailableException(detail);
    }

    @Override
    public boolean cancel(EvaluationId evaluationId) {
        Objects.requireNonNull(evaluationId, "evaluationId");
        return false;
    }

    @Override
    public void reset() {
        throw new RemoteSessionUnavailableException(detail);
    }

    @Override
    public RemoteQuery.Result query(RemoteQuery query) {
        Objects.requireNonNull(query, "query");
        return RemoteQuery.Result.unavailable(detail);
    }
}
