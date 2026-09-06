package io.mindspice.lyra.repl.remote;

import io.mindspice.lyra.repl.Cancellation;
import io.mindspice.lyra.repl.ConsoleSession;
import io.mindspice.lyra.repl.EvaluationId;
import io.mindspice.lyra.repl.EvaluationRequest;
import io.mindspice.lyra.repl.EvaluationResult;
import io.mindspice.lyra.repl.LyraSession;
import io.mindspice.lyra.repl.SessionRevision;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Explicit embedding adapter for the currently available public session API.
 * Queries share the local console's metadata-only, non-executing path; no
 * live handles or alternate evaluator cross the transport.
 */
public final class LyraSessionAdapter implements RemoteSessionAdapter, AutoCloseable {
    private final LyraSession session;
    private final ConsoleSession console;
    private final UUID sessionId = UUID.randomUUID();

    private LyraSessionAdapter(LyraSession session) {
        this.session = Objects.requireNonNull(session, "session");
        this.console = ConsoleSession.local(session);
    }

    public static LyraSessionAdapter of(LyraSession session) {
        return new LyraSessionAdapter(session);
    }

    @Override
    public UUID sessionId() {
        return sessionId;
    }

    @Override
    public SessionRevision revision() {
        return console.revision();
    }

    @Override
    public EvaluationResult evaluate(EvaluationRequest request, RemoteCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        if (!cancellation.admit()) {
            if (!cancellation.isRequested()) {
                throw new IllegalStateException("evaluation cancellation token was already admitted");
            }
            cancellation.observe();
            return new EvaluationResult.Cancelled(request, revision(),
                    Cancellation.observed(request.evaluationId()));
        }
        return session.submit(request, cancellation::isRequested);
    }

    @Override
    public boolean cancel(EvaluationId evaluationId) {
        return session.cancel(Objects.requireNonNull(evaluationId, "evaluationId"));
    }

    @Override
    public void reset() {
        session.reset();
    }

    @Override
    public RemoteQuery.Result query(RemoteQuery query) {
        Objects.requireNonNull(query, "query");
        var request = query.kind() == RemoteQuery.Kind.BINDINGS
                ? ConsoleSession.QueryRequest.bindings()
                : ConsoleSession.QueryRequest.type(query.source().orElseThrow());
        var result = console.query(request);
        var status = switch (result.status()) {
            case OK -> RemoteQuery.Status.OK;
            case CLOSED -> RemoteQuery.Status.CLOSED;
            default -> RemoteQuery.Status.UNAVAILABLE;
        };
        if (result.bindings().stream().anyMatch(value -> value.name().length() > 1024
                || value.canonicalType().length() > 4096)
                || result.inferredType().filter(value -> value.length() > 4096).isPresent()) {
            return RemoteQuery.Result.unavailable("query metadata exceeds the transport limit");
        }
        var bindings = result.bindings().stream().limit(RemoteProtocol.MAX_QUERY_BINDINGS)
                .map(value -> new RemoteBinding(value.name(), value.canonicalType(), value.visibility(), value.mutable()))
                .toList();
        Optional<String> detail = result.detail().map(value -> {
            if (value.length() <= 4096) return value;
            int end = Character.isHighSurrogate(value.charAt(4094)) ? 4094 : 4095;
            return value.substring(0, end) + "…";
        });
        if (result.bindings().size() > bindings.size()) detail = Optional.of("binding listing truncated");
        return new RemoteQuery.Result(status, bindings, result.inferredType(), detail);
    }

    /** Closes the explicitly supplied session on its owner thread. */
    @Override
    public void close() {
        session.close();
    }
}
