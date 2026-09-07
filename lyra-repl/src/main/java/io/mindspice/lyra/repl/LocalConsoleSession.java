package io.mindspice.lyra.repl;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Console target backed by the owner-confined local session. */
final class LocalConsoleSession implements ConsoleSession {
    private final LyraSession session;

    LocalConsoleSession(LyraSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    @Override
    public SessionRevision revision() {
        return session.currentRevision();
    }

    @Override
    public Evaluation evaluate(EvaluationSource source) {
        return ConsolePresentation.evaluation(
                session.submit(Objects.requireNonNull(source, "source")));
    }

    @Override
    public Control cancel(EvaluationId evaluationId) {
        boolean requested = session.cancel(Objects.requireNonNull(evaluationId, "evaluationId"));
        return new Control(requested ? ControlStatus.REQUESTED : ControlStatus.NOT_FOUND,
                revision(), Optional.empty());
    }

    @Override
    public Control reset() {
        if (session.lifecycleState() == SessionLifecycleState.CLOSED) {
            return new Control(ControlStatus.CLOSED, revision(),
                    Optional.of("session is closed"));
        }
        session.reset();
        return new Control(ControlStatus.OK, revision(), Optional.empty());
    }

    @Override
    public Control reload() {
        if (session.lifecycleState() == SessionLifecycleState.CLOSED) {
            return new Control(ControlStatus.CLOSED, revision(),
                    Optional.of("session is closed"));
        }
        return Control.unavailable(revision(),
                "reload is unavailable without persistent live linkage; no source was replayed");
    }

    @Override
    public Query query(QueryRequest request) {
        Objects.requireNonNull(request, "request");
        if (session.lifecycleState() == SessionLifecycleState.CLOSED) {
            return new Query(QueryStatus.CLOSED, List.of(), Optional.empty(),
                    Optional.of("session is closed"));
        }
        if (session.isBusy()) {
            return new Query(QueryStatus.BUSY, List.of(), Optional.empty(),
                    Optional.of("the local session is busy"));
        }
        if (request.kind() == QueryRequest.Kind.TYPE) {
            LyraSession.TypeQuery type = session.type(request.source().orElseThrow());
            if (type.busy()) {
                return new Query(QueryStatus.BUSY, List.of(), Optional.empty(),
                        Optional.of("the local session is busy"));
            }
            if (type.canonicalType().isEmpty()) {
                String detail = type.diagnostics().isEmpty()
                        ? "type query failed; source was not executed"
                        : type.diagnostics().stream()
                                .map(ConsolePresentation::renderDiagnostic)
                                .collect(java.util.stream.Collectors.joining("; "));
                return new Query(QueryStatus.UNAVAILABLE, List.of(), Optional.empty(),
                        Optional.of(detail));
            }
            return new Query(QueryStatus.OK, List.of(), type.canonicalType(), Optional.empty());
        }
        return new Query(QueryStatus.OK,
                ConsolePresentation.bindings(session.workspaceState()),
                Optional.empty(), Optional.empty());
    }

}
