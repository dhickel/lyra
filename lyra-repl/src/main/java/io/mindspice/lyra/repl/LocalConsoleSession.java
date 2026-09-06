package io.mindspice.lyra.repl;

import io.mindspice.lyra.runtime.BindingMutability;

import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;
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
        EvaluationResult result = session.submit(Objects.requireNonNull(source, "source"));
        var diagnostics = result.diagnostics().stream().map(DiagnosticInfo::from);
        if (result instanceof EvaluationResult.RuntimeFailure failure) {
            diagnostics = java.util.stream.Stream.concat(failure.frames().stream()
                    .map(frame -> DiagnosticInfo.from(failure, frame)), diagnostics);
        }
        return new Evaluation(
                result.evaluationId(),
                status(result.status()),
                result.revision(),
                diagnostics.toList(),
                result.value().map(Value::from),
                result.failureSummary());
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
                                .map(LocalConsoleSession::renderDiagnostic)
                                .collect(Collectors.joining("; "));
                return new Query(QueryStatus.UNAVAILABLE, List.of(), Optional.empty(),
                        Optional.of(detail));
            }
            return new Query(QueryStatus.OK, List.of(), type.canonicalType(), Optional.empty());
        }
        Map<String, BindingMetadata> bindings = session.workspaceState().bindings();
        List<Binding> result = bindings.values().stream()
                .sorted(Comparator.comparing(BindingMetadata::name))
                .map(binding -> new Binding(
                        binding.name(), binding.canonicalType(), binding.visibility().name(),
                        binding.mutability() == BindingMutability.MUTABLE))
                .toList();
        return new Query(QueryStatus.OK, result, Optional.empty(), Optional.empty());
    }

    private static ConsoleSession.EvaluationStatus status(
            io.mindspice.lyra.repl.EvaluationStatus status) {
        return switch (status) {
            case SUCCESS -> ConsoleSession.EvaluationStatus.SUCCESS;
            case COMPILATION_FAILURE -> ConsoleSession.EvaluationStatus.COMPILATION_FAILURE;
            case RUNTIME_FAILURE -> ConsoleSession.EvaluationStatus.RUNTIME_FAILURE;
            case CANCELLED -> ConsoleSession.EvaluationStatus.CANCELLED;
            case BUSY -> ConsoleSession.EvaluationStatus.BUSY;
            case CLOSED -> ConsoleSession.EvaluationStatus.CLOSED;
        };
    }

    private static String renderDiagnostic(
            io.mindspice.lyra.compiler.diagnostic.Diagnostic diagnostic) {
        io.mindspice.lyra.compiler.source.SourceSpan span = diagnostic.primarySpan();
        StringBuilder result = new StringBuilder()
                .append(diagnostic.severity()).append(' ')
                .append(diagnostic.code().value()).append(' ')
                .append(span.sourceId()).append(':')
                .append(span.startOffset()).append("..")
                .append(span.endOffset()).append(": ")
                .append(sanitize(diagnostic.summary()));
        diagnostic.relatedSpans().forEach(related -> result.append(" [")
                .append(sanitize(related.label())).append(": ")
                .append(related.span()).append(']'));
        return result.toString();
    }

    private static String sanitize(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                result.append(character).append(value.charAt(++index));
            } else if (Character.isISOControl(character) || Character.isSurrogate(character)) {
                result.append(String.format(java.util.Locale.ROOT,
                        "\\u%04X", (int) character));
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

}
