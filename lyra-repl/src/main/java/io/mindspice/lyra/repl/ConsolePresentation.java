package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.runtime.BindingMutability;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Package-private console-boundary presentation shared by the synchronous
 * local console target and the managed owner adapter. Only immutable
 * {@link ConsoleSession} record shapes cross this boundary; never live
 * values, session handles or transport state.
 */
final class ConsolePresentation {
    private ConsolePresentation() {
    }

    static ConsoleSession.Evaluation evaluation(EvaluationResult result) {
        Objects.requireNonNull(result, "result");
        var diagnostics = result.diagnostics().stream().map(ConsoleSession.DiagnosticInfo::from);
        if (result instanceof EvaluationResult.RuntimeFailure failure) {
            diagnostics = java.util.stream.Stream.concat(failure.frames().stream()
                    .map(frame -> ConsoleSession.DiagnosticInfo.from(failure, frame)), diagnostics);
        }
        return new ConsoleSession.Evaluation(
                result.evaluationId(),
                status(result.status()),
                result.revision(),
                diagnostics.toList(),
                result.value().map(ConsoleSession.Value::from),
                result.failureSummary().map(ConsolePresentation::boundedText));
    }

    static ConsoleSession.EvaluationStatus status(EvaluationStatus status) {
        return switch (status) {
            case SUCCESS -> ConsoleSession.EvaluationStatus.SUCCESS;
            case COMPILATION_FAILURE -> ConsoleSession.EvaluationStatus.COMPILATION_FAILURE;
            case RUNTIME_FAILURE -> ConsoleSession.EvaluationStatus.RUNTIME_FAILURE;
            case CANCELLED -> ConsoleSession.EvaluationStatus.CANCELLED;
            case BUSY -> ConsoleSession.EvaluationStatus.BUSY;
            case CLOSED -> ConsoleSession.EvaluationStatus.CLOSED;
        };
    }

    /** Committed declaration names/types as compatible immutable record forms. */
    static List<ConsoleSession.Binding> bindings(WorkspaceState.Committed state) {
        Objects.requireNonNull(state, "state");
        return state.bindings().values().stream()
                .sorted(Comparator.comparing(BindingMetadata::name))
                .map(binding -> new ConsoleSession.Binding(
                        binding.name(), binding.canonicalType(), binding.visibility().name(),
                        binding.mutability() == BindingMutability.MUTABLE))
                .toList();
    }

    static String renderDiagnostic(Diagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        ConsoleSession.DiagnosticInfo info = ConsoleSession.DiagnosticInfo.from(diagnostic);
        StringBuilder result = new StringBuilder()
                .append(info.severity()).append(' ')
                .append(info.code()).append(' ')
                .append(info.primarySpan().sourceId()).append(':')
                .append(info.primarySpan().startOffset()).append("..")
                .append(info.primarySpan().endOffset()).append(": ")
                .append(info.summary());
        info.relatedSpans().forEach(related -> result.append(" [")
                .append(related.label()).append(": ")
                .append(related.span()).append(']'));
        return boundedText(result.toString());
    }

    private static String boundedText(String value) {
        Objects.requireNonNull(value, "presentation text");
        int limit = SnapshotLimits.DEFAULT_MAX_RENDERED_CHARACTERS;
        StringBuilder result = new StringBuilder(Math.min(value.length(), limit));
        boolean truncated = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            String rendered;
            if (Character.isHighSurrogate(character)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                rendered = value.substring(index, index + 2);
                index++;
            } else if (Character.isISOControl(character) || Character.isSurrogate(character)) {
                rendered = String.format(java.util.Locale.ROOT, "\\u%04X", (int) character);
            } else {
                rendered = String.valueOf(character);
            }
            if (result.length() + rendered.length() > limit - 1) {
                truncated = true;
                break;
            }
            result.append(rendered);
        }
        if (truncated) result.append('…');
        return result.toString();
    }
}
