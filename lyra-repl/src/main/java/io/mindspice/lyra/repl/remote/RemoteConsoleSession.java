package io.mindspice.lyra.repl.remote;

import io.mindspice.lyra.repl.ConsoleSession;
import io.mindspice.lyra.repl.EvaluationId;
import io.mindspice.lyra.repl.EvaluationSource;
import io.mindspice.lyra.repl.SessionRevision;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Console target for an authenticated attached session. It is deliberately
 * limited to the existing fixed RemoteClient operations and never forwards
 * process streams or resubmits source after a disconnect.
 */
public final class RemoteConsoleSession implements ConsoleSession {
    private final RemoteClient client;
    private final Map<EvaluationId, RemoteRequest> activeRequests = new ConcurrentHashMap<>();

    private RemoteConsoleSession(RemoteClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public static RemoteConsoleSession of(RemoteClient client) {
        return new RemoteConsoleSession(client);
    }

    public static RemoteConsoleSession connect(RemoteEndpoint endpoint) throws IOException {
        return of(RemoteClient.connect(endpoint));
    }

    public RemoteClient client() {
        return client;
    }

    @Override
    public SessionRevision revision() {
        return client.revision();
    }

    public boolean isConnected() {
        return client.isConnected();
    }

    /** The single in-flight evaluation admitted by the attached server, if any. */
    public Optional<EvaluationId> activeEvaluationId() {
        return activeRequests.keySet().stream().findFirst();
    }

    /** Requests cancellation through the existing request identity, if active. */
    public Control cancelActive() {
        return activeEvaluationId()
                .map(this::cancel)
                .orElse(new Control(ControlStatus.NOT_FOUND, client.revision(),
                        Optional.of("no attached evaluation is active")));
    }

    /** Explicit reconnect only; no source is submitted by this operation. */
    public void reconnect() throws IOException {
        client.reconnect();
    }

    /**
     * Queries a retained request by identity after reconnect. The source is
     * never available to or replayed by this method.
     */
    public RequestStatus status(EvaluationId evaluationId) {
        Objects.requireNonNull(evaluationId, "evaluationId");
        try {
            ProtocolMessage.QueryResult result = await(client.queryRequest(evaluationId.value()));
            return status(evaluationId, result);
        } catch (IOException | RuntimeException failure) {
            RequestState state = client.isConnected()
                    ? RequestState.UNAVAILABLE : RequestState.DISCONNECTED;
            return new RequestStatus(evaluationId, 0, state, client.revision(),
                    Optional.of(message(failure)));
        }
    }

    @Override
    public Evaluation evaluate(EvaluationSource source) {
        Objects.requireNonNull(source, "source");
        EvaluationId fallback = EvaluationId.create();
        if (!client.isConnected()) {
            return cancelled(fallback, "remote connection is disconnected; evaluation cancelled");
        }

        final RemoteRequest request;
        try {
            request = client.submit(source);
        } catch (IOException | RuntimeException failure) {
            return unavailable(fallback, failure);
        }
        EvaluationId evaluationId = EvaluationId.of(request.requestId());
        activeRequests.put(evaluationId, request);
        try {
            return awaitEvaluation(request);
        } finally {
            activeRequests.remove(evaluationId, request);
        }
    }

    @Override
    public Control cancel(EvaluationId evaluationId) {
        Objects.requireNonNull(evaluationId, "evaluationId");
        RemoteRequest request = activeRequests.get(evaluationId);
        if (request == null) {
            RequestStatus status = status(evaluationId);
            return switch (status.state()) {
                case SUCCESS, COMPILATION_FAILURE, RUNTIME_FAILURE, CANCELLED,
                        BUSY, CLOSED, UNAVAILABLE, REVISION_CONFLICT, REJECTED, EXPIRED
                        -> new Control(ControlStatus.ALREADY_TERMINAL, status.revision(),
                        status.detail());
                case NOT_FOUND -> new Control(ControlStatus.NOT_FOUND, status.revision(),
                        status.detail());
                case DISCONNECTED -> new Control(ControlStatus.DISCONNECTED,
                        status.revision(), status.detail());
                case QUEUED, RUNNING, CANCELLATION_REQUESTED -> {
                    try {
                        ProtocolMessage.CancelResult result = await(
                                client.cancel(evaluationId.value()));
                        yield new Control(map(result.status()),
                                new SessionRevision(result.revision()), result.detail());
                    } catch (IOException | RuntimeException failure) {
                        yield disconnectedControl(failure);
                    }
                }
            };
        }
        try {
            ProtocolMessage.CancelResult result = await(request.cancel());
            return new Control(map(result.status()), new SessionRevision(result.revision()),
                    result.detail());
        } catch (IOException | RuntimeException failure) {
            return disconnectedControl(failure);
        }
    }

    @Override
    public Control reset() {
        try {
            ProtocolMessage.ResetResult result = await(client.reset(client.revision().value()));
            return new Control(map(result.status()), new SessionRevision(result.revision()),
                    result.detail());
        } catch (IOException | RuntimeException failure) {
            return disconnectedControl(failure);
        }
    }

    @Override
    public Query query(QueryRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            CompletableFuture<ProtocolMessage.QueryResult> future = switch (request.kind()) {
                case BINDINGS -> client.queryBindings();
                case TYPE -> client.queryType(request.source().orElseThrow());
            };
            ProtocolMessage.QueryResult result = await(future);
            return map(result);
        } catch (IOException | RuntimeException failure) {
            QueryStatus status = client.isConnected()
                    ? QueryStatus.UNAVAILABLE : QueryStatus.DISCONNECTED;
            return new Query(status, List.of(), Optional.empty(),
                    Optional.of(message(failure)));
        }
    }

    @Override
    public Control reload() {
        return Control.unavailable(client.revision(),
                "reload is unavailable over the attached protocol; no source was replayed");
    }

    @Override
    public void close() {
        client.close();
    }

    private Evaluation awaitEvaluation(RemoteRequest request) {
        while (true) {
            try {
                return map(request.result().get(100, TimeUnit.MILLISECONDS));
            } catch (TimeoutException timeout) {
                if (request.status() == ProtocolMessage.RemoteStatus.DISCONNECTED
                        || !client.isConnected()) {
                    return cancelledResult(request,
                            "remote connection disconnected; evaluation cancelled");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                try {
                    request.cancel();
                } catch (IOException | RuntimeException ignored) {
                    // The request remains tracked by RemoteClient; no source is retried.
                }
                return cancelledResult(request,
                        "evaluation wait interrupted; cancellation was requested");
            } catch (ExecutionException failure) {
                if (request.status() == ProtocolMessage.RemoteStatus.DISCONNECTED
                        || !client.isConnected()) {
                    return cancelledResult(request,
                            "remote connection disconnected; evaluation cancelled");
                }
                return unavailableResult(request, cause(failure));
            }
        }
    }

    private <T> T await(CompletableFuture<T> future) throws IOException {
        Objects.requireNonNull(future, "future");
        while (true) {
            try {
                return future.get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeout) {
                if (!client.isConnected()) {
                    throw new IOException("remote connection disconnected");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("remote operation interrupted", interrupted);
            } catch (ExecutionException failure) {
                Throwable cause = cause(failure);
                if (cause instanceof IOException io) {
                    throw io;
                }
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IOException("remote operation failed", cause);
            }
        }
    }

    private static Evaluation map(ProtocolMessage.Result result) {
        return new Evaluation(
                EvaluationId.of(result.requestId()),
                map(result.status()),
                new SessionRevision(result.revision()),
                result.diagnostics().stream().map(RemoteConsoleSession::diagnostic).toList(),
                result.value().map(RemoteConsoleSession::value),
                result.failureSummary());
    }

    private static Query map(ProtocolMessage.QueryResult result) {
        QueryStatus status = switch (result.status()) {
            case OK -> QueryStatus.OK;
            case NOT_FOUND -> QueryStatus.NOT_FOUND;
            case EXPIRED -> QueryStatus.EXPIRED;
            case BUSY -> QueryStatus.BUSY;
            case UNAVAILABLE -> QueryStatus.UNAVAILABLE;
            case CLOSED -> QueryStatus.CLOSED;
        };
        return new Query(status,
                result.bindings().stream()
                        .map(binding -> new Binding(binding.name(), binding.canonicalType(),
                                binding.visibility(), binding.mutable()))
                        .toList(),
                result.inferredType(), result.detail());
    }

    private static RequestStatus status(EvaluationId id, ProtocolMessage.QueryResult result) {
        if (result.terminalResult().isPresent()) {
            ProtocolMessage.Result terminal = result.terminalResult().orElseThrow();
            return new RequestStatus(id, terminal.sequence(), mapState(terminal.status()),
                    new SessionRevision(terminal.revision()), terminal.failureSummary());
        }
        if (result.request().isPresent()) {
            ProtocolMessage.RequestSnapshot snapshot = result.request().orElseThrow();
            return new RequestStatus(id, snapshot.sequence(), mapState(snapshot.status()),
                    new SessionRevision(snapshot.revision()), snapshot.detail());
        }
        RequestState state = switch (result.status()) {
            case OK -> RequestState.UNAVAILABLE;
            case NOT_FOUND -> RequestState.NOT_FOUND;
            case EXPIRED -> RequestState.EXPIRED;
            case BUSY -> RequestState.BUSY;
            case UNAVAILABLE -> RequestState.UNAVAILABLE;
            case CLOSED -> RequestState.CLOSED;
        };
        return new RequestStatus(id, 0, state, new SessionRevision(result.revision()), result.detail());
    }

    private static ConsoleSession.DiagnosticInfo diagnostic(ProtocolMessage.Diagnostic value) {
        return new ConsoleSession.DiagnosticInfo(value.code(), value.severity(), value.summary(),
                new ConsoleSession.Span(value.primarySpan().sourceId(),
                        value.primarySpan().startOffset(), value.primarySpan().endOffset()),
                value.relatedSpans().stream()
                        .map(related -> new ConsoleSession.RelatedSpan(
                                new ConsoleSession.Span(related.span().sourceId(),
                                        related.span().startOffset(), related.span().endOffset()),
                                related.label()))
                        .toList());
    }

    private static ConsoleSession.Value value(ProtocolMessage.ValueSnapshot snapshot) {
        return new ConsoleSession.Value(snapshot.canonicalType(), display(snapshot.data()));
    }

    private static String display(ProtocolMessage.ValueData data) {
        return switch (data) {
            case ProtocolMessage.Nil ignored -> "#NIL";
            case ProtocolMessage.Unit ignored -> "Unit";
            case ProtocolMessage.Scalar scalar -> scalar(scalar);
            case ProtocolMessage.Aggregate aggregate -> aggregate.identity();
            case ProtocolMessage.Function function -> function.identity();
            case ProtocolMessage.Reference reference -> reference.description();
            case ProtocolMessage.Truncated truncated -> "<truncated:" + truncated.reason() + ">";
        };
    }

    private static String scalar(ProtocolMessage.Scalar scalar) {
        return switch (scalar.scalarKind()) {
            case "STRING" -> "\"" + escape(scalar.value(), '"') + "\"";
            case "CHARACTER" -> "'" + escape(scalar.value(), '\'') + "'";
            default -> scalar.value();
        };
    }

    private static String escape(String value, char quote) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character == quote) {
                        result.append('\\');
                    }
                    if (character < 0x20 || character == 0x7F
                            || Character.isSurrogate(character)) {
                        result.append(String.format(java.util.Locale.ROOT,
                                "\\u%04X", (int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.toString();
    }

    private static EvaluationStatus map(ProtocolMessage.RemoteStatus status) {
        return switch (status) {
            case SUCCESS -> EvaluationStatus.SUCCESS;
            case COMPILATION_FAILURE -> EvaluationStatus.COMPILATION_FAILURE;
            case RUNTIME_FAILURE -> EvaluationStatus.RUNTIME_FAILURE;
            case CANCELLED, DISCONNECTED -> EvaluationStatus.CANCELLED;
            case BUSY -> EvaluationStatus.BUSY;
            case CLOSED -> EvaluationStatus.CLOSED;
            case UNAVAILABLE -> EvaluationStatus.UNAVAILABLE;
            case REVISION_CONFLICT -> EvaluationStatus.REVISION_CONFLICT;
            case REJECTED -> EvaluationStatus.REJECTED;
            case EXPIRED -> EvaluationStatus.EXPIRED;
            case QUEUED, RUNNING, CANCELLATION_REQUESTED -> EvaluationStatus.UNAVAILABLE;
        };
    }

    private static ControlStatus map(ProtocolMessage.ControlStatus status) {
        return switch (status) {
            case OK -> ControlStatus.OK;
            case REQUESTED -> ControlStatus.REQUESTED;
            case ALREADY_TERMINAL -> ControlStatus.ALREADY_TERMINAL;
            case NOT_FOUND -> ControlStatus.NOT_FOUND;
            case BUSY -> ControlStatus.BUSY;
            case REVISION_CONFLICT -> ControlStatus.REVISION_CONFLICT;
            case CANCELLED -> ControlStatus.CANCELLED;
            case UNAVAILABLE -> ControlStatus.UNAVAILABLE;
            case CLOSED -> ControlStatus.CLOSED;
        };
    }

    private static RequestState mapStateToRequest(ProtocolMessage.RemoteStatus status) {
        return switch (status) {
            case QUEUED -> RequestState.QUEUED;
            case RUNNING -> RequestState.RUNNING;
            case CANCELLATION_REQUESTED -> RequestState.CANCELLATION_REQUESTED;
            case SUCCESS -> RequestState.SUCCESS;
            case COMPILATION_FAILURE -> RequestState.COMPILATION_FAILURE;
            case RUNTIME_FAILURE -> RequestState.RUNTIME_FAILURE;
            case CANCELLED -> RequestState.CANCELLED;
            case BUSY -> RequestState.BUSY;
            case CLOSED -> RequestState.CLOSED;
            case UNAVAILABLE -> RequestState.UNAVAILABLE;
            case REVISION_CONFLICT -> RequestState.REVISION_CONFLICT;
            case REJECTED -> RequestState.REJECTED;
            case EXPIRED -> RequestState.EXPIRED;
            case DISCONNECTED -> RequestState.DISCONNECTED;
        };
    }

    private static RequestState mapState(ProtocolMessage.RemoteStatus status) {
        return mapStateToRequest(status);
    }

    private Evaluation cancelled(EvaluationId id, String detail) {
        return new Evaluation(id, EvaluationStatus.CANCELLED, client.revision(),
                List.of(), Optional.empty(), Optional.of(detail));
    }

    private Evaluation unavailable(EvaluationId id, Throwable failure) {
        EvaluationStatus status = client.isConnected()
                ? EvaluationStatus.UNAVAILABLE : EvaluationStatus.CANCELLED;
        String detail = client.isConnected()
                ? message(failure) : "remote connection disconnected; evaluation cancelled";
        return new Evaluation(id, status, client.revision(), List.of(), Optional.empty(),
                Optional.of(detail));
    }

    private Evaluation cancelledResult(RemoteRequest request, String detail) {
        return new Evaluation(EvaluationId.of(request.requestId()), EvaluationStatus.CANCELLED,
                client.revision(), List.of(), Optional.empty(), Optional.of(detail));
    }

    private Evaluation unavailableResult(RemoteRequest request, Throwable failure) {
        EvaluationStatus status = client.isConnected()
                ? EvaluationStatus.UNAVAILABLE : EvaluationStatus.CANCELLED;
        String detail = client.isConnected()
                ? message(failure) : "remote connection disconnected; evaluation cancelled";
        return new Evaluation(EvaluationId.of(request.requestId()), status, client.revision(),
                List.of(), Optional.empty(), Optional.of(detail));
    }

    private Control disconnectedControl(Throwable failure) {
        return new Control(client.isConnected() ? ControlStatus.UNAVAILABLE : ControlStatus.DISCONNECTED,
                client.revision(), Optional.of(message(failure)));
    }

    private static Throwable cause(ExecutionException failure) {
        Throwable cause = failure.getCause();
        return cause == null ? failure : cause;
    }

    private static String message(Throwable failure) {
        String value = failure.getMessage();
        return value == null || value.isBlank() ? failure.getClass().getSimpleName() : value;
    }

    public enum RequestState {
        QUEUED,
        RUNNING,
        CANCELLATION_REQUESTED,
        SUCCESS,
        COMPILATION_FAILURE,
        RUNTIME_FAILURE,
        CANCELLED,
        BUSY,
        CLOSED,
        UNAVAILABLE,
        REVISION_CONFLICT,
        REJECTED,
        EXPIRED,
        NOT_FOUND,
        DISCONNECTED
    }

    public record RequestStatus(
            EvaluationId evaluationId,
            long sequence,
            RequestState state,
            SessionRevision revision,
            Optional<String> detail) {
        public RequestStatus {
            evaluationId = Objects.requireNonNull(evaluationId, "evaluationId");
            if (sequence < 0) {
                throw new IllegalArgumentException("request sequence must not be negative");
            }
            state = Objects.requireNonNull(state, "state");
            revision = Objects.requireNonNull(revision, "revision");
            detail = Objects.requireNonNull(detail, "detail");
        }
    }
}
