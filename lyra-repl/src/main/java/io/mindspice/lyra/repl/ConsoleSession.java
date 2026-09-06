package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Transport-neutral console target used by both local and attached consoles.
 * Implementations exchange bounded metadata and snapshots only; no live value
 * or transport handle crosses this boundary.
 */
public interface ConsoleSession extends AutoCloseable {
    /** Owner-confined console tooling over an existing session; does not transfer ownership. */
    static ConsoleSession local(LyraSession session) {
        return new LocalConsoleSession(session);
    }

    /** Immutable revision metadata; callable from a transport control thread. */
    SessionRevision revision();

    Evaluation evaluate(EvaluationSource source);

    Control cancel(EvaluationId evaluationId);

    Control reset();

    Query query(QueryRequest request);

    /** Reload has no wire operation and remains explicitly unavailable here. */
    default Control reload() {
        return Control.unavailable(SessionRevision.initial(),
                "reload is unavailable without persistent live linkage; no source was replayed");
    }

    /** The console does not own its target. Implementations may override for attached clients. */
    default void close() {
    }

    record Evaluation(
            EvaluationId evaluationId,
            EvaluationStatus status,
            SessionRevision revision,
            List<DiagnosticInfo> diagnostics,
            Optional<Value> value,
            Optional<String> detail) {
        public Evaluation {
            evaluationId = Objects.requireNonNull(evaluationId, "evaluationId");
            status = Objects.requireNonNull(status, "status");
            revision = Objects.requireNonNull(revision, "revision");
            diagnostics = copyDiagnostics(diagnostics);
            value = Objects.requireNonNull(value, "value");
            detail = copyOptionalText(detail, "detail");
        }


    }

    enum EvaluationStatus {
        SUCCESS,
        COMPILATION_FAILURE,
        RUNTIME_FAILURE,
        CANCELLED,
        BUSY,
        CLOSED,
        UNAVAILABLE,
        REVISION_CONFLICT,
        REJECTED,
        EXPIRED
    }

    record Control(
            ControlStatus status,
            SessionRevision revision,
            Optional<String> detail) {
        public Control {
            status = Objects.requireNonNull(status, "status");
            revision = Objects.requireNonNull(revision, "revision");
            detail = copyOptionalText(detail, "detail");
        }

        public static Control unavailable(SessionRevision revision, String detail) {
            return new Control(ControlStatus.UNAVAILABLE, revision, Optional.of(detail));
        }
    }

    enum ControlStatus {
        OK,
        REQUESTED,
        ALREADY_TERMINAL,
        NOT_FOUND,
        BUSY,
        REVISION_CONFLICT,
        CANCELLED,
        UNAVAILABLE,
        CLOSED,
        DISCONNECTED
    }

    record QueryRequest(Kind kind, Optional<EvaluationSource> source) {
        public QueryRequest {
            kind = Objects.requireNonNull(kind, "kind");
            source = Objects.requireNonNull(source, "source");
            if (kind == Kind.TYPE && source.isEmpty()) {
                throw new IllegalArgumentException("type query needs source");
            }
            if (kind != Kind.TYPE && source.isPresent()) {
                throw new IllegalArgumentException("source is only valid for type queries");
            }
        }

        public static QueryRequest bindings() {
            return new QueryRequest(Kind.BINDINGS, Optional.empty());
        }

        public static QueryRequest type(EvaluationSource source) {
            return new QueryRequest(Kind.TYPE, Optional.of(source));
        }

        public enum Kind {
            BINDINGS,
            TYPE
        }
    }

    record Query(
            QueryStatus status,
            List<Binding> bindings,
            Optional<String> inferredType,
            Optional<String> detail) {
        public Query {
            status = Objects.requireNonNull(status, "status");
            bindings = copyBindings(bindings);
            inferredType = copyOptionalText(inferredType, "inferredType");
            detail = copyOptionalText(detail, "detail");
        }

        public static Query unavailable(String detail) {
            return new Query(QueryStatus.UNAVAILABLE, List.of(), Optional.empty(), Optional.of(detail));
        }
    }

    enum QueryStatus {
        OK,
        NOT_FOUND,
        EXPIRED,
        BUSY,
        UNAVAILABLE,
        CLOSED,
        DISCONNECTED
    }

    record Binding(String name, String canonicalType, String visibility, boolean mutable) {
        public Binding {
            name = token(name, "binding name");
            canonicalType = token(canonicalType, "binding type");
            visibility = token(visibility, "binding visibility");
        }
    }

    /** A source-mapped diagnostic that is safe to render without source text. */
    record DiagnosticInfo(
            String code,
            String severity,
            String summary,
            Span primarySpan,
            List<RelatedSpan> relatedSpans) {
        public DiagnosticInfo {
            code = token(code, "diagnostic code");
            severity = token(severity, "diagnostic severity");
            summary = text(summary, "diagnostic summary");
            primarySpan = Objects.requireNonNull(primarySpan, "primarySpan");
            relatedSpans = copyRelatedSpans(relatedSpans);
        }

        public static DiagnosticInfo from(Diagnostic diagnostic) {
            Objects.requireNonNull(diagnostic, "diagnostic");
            return new DiagnosticInfo(
                    diagnostic.code().value(),
                    diagnostic.severity().name(),
                    diagnostic.summary(),
                    Span.from(diagnostic.primarySpan()),
                    diagnostic.relatedSpans().stream()
                            .map(RelatedSpan::from)
                            .toList());
        }

        public static DiagnosticInfo from(EvaluationResult.RuntimeFailure failure, RuntimeFrame frame) {
            String source = frame.origin().uri().map(Object::toString).orElse(frame.origin().label());
            Span span = new Span(source, frame.span().startOffset(), frame.span().endOffset());
            var related = new java.util.ArrayList<RelatedSpan>();
            frame.origin().documentVersion().ifPresent(version ->
                    related.add(new RelatedSpan(span, "source version " + version)));
            frame.excerpt().filter(value -> !value.isEmpty()).ifPresent(value ->
                    related.add(new RelatedSpan(span, escapedExcerpt(value))));
            return new DiagnosticInfo(failure.code(), "ERROR",
                    failure.summary() + " in " + frame.functionName(), span, related);
        }

        private static String escapedExcerpt(String value) {
            StringBuilder text = new StringBuilder();
            int limit = Math.min(value.length(), 128);
            if (limit < value.length() && Character.isHighSurrogate(value.charAt(limit - 1))) limit--;
            for (int index = 0; index < limit; index++) {
                char c = value.charAt(index);
                if (Character.isHighSurrogate(c) && index + 1 < limit
                        && Character.isLowSurrogate(value.charAt(index + 1))) {
                    text.append(c).append(value.charAt(++index));
                } else if (Character.isISOControl(c) || Character.isSurrogate(c)) {
                    text.append(String.format(java.util.Locale.ROOT, "\\u%04X", (int) c));
                } else text.append(c);
            }
            if (limit < value.length()) text.append('…');
            return text.toString();
        }

        public String render() {
            StringBuilder rendered = new StringBuilder()
                    .append(severity).append(' ').append(code).append(' ')
                    .append(primarySpan.sourceId()).append(':')
                    .append(primarySpan.startOffset()).append("..")
                    .append(primarySpan.endOffset()).append(": ")
                    .append(summary);
            for (RelatedSpan related : relatedSpans) {
                rendered.append('\n').append("  ").append(related.label())
                        .append(": ").append(related.span());
            }
            return rendered.toString();
        }
    }

    record RelatedSpan(Span span, String label) {
        public RelatedSpan {
            span = Objects.requireNonNull(span, "span");
            label = text(label, "related span label");
        }

        private static RelatedSpan from(io.mindspice.lyra.compiler.diagnostic.RelatedSpan value) {
            return new RelatedSpan(Span.from(value.span()), value.label());
        }
    }

    record Span(String sourceId, int startOffset, int endOffset) {
        public Span {
            sourceId = text(sourceId, "source id");
            if (startOffset < 0 || endOffset < startOffset) {
                throw new IllegalArgumentException("invalid diagnostic span");
            }
        }

        private static Span from(SourceSpan value) {
            return new Span(value.sourceId().toString(), value.startOffset(), value.endOffset());
        }

        @Override
        public String toString() {
            return sourceId + ":" + startOffset + ".." + endOffset;
        }
    }

    /** A rendered, data-only snapshot suitable for the console output stream. */
    record Value(String canonicalType, String display) {
        public Value {
            canonicalType = token(canonicalType, "canonicalType");
            display = text(display, "display");
        }

        public String renderLine() {
            return canonicalType + " " + display + "\n";
        }

        static Value from(ValueSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "snapshot");
            return new Value(snapshot.canonicalType(), display(snapshot.data()));
        }

        private static String display(ValueSnapshot.Data data) {
            return switch (data) {
                case ValueSnapshot.Nil ignored -> "#NIL";
                case ValueSnapshot.Unit ignored -> "Unit";
                case ValueSnapshot.Scalar scalar -> scalar(scalar);
                case ValueSnapshot.Aggregate aggregate -> aggregate.identity();
                case ValueSnapshot.Function function -> function.identity();
                case ValueSnapshot.Reference reference -> reference.description();
                case ValueSnapshot.Truncated truncated ->
                        "<truncated:" + truncated.reason() + ">";
            };
        }

        private static String scalar(ValueSnapshot.Scalar scalar) {
            return switch (scalar.kind()) {
                case STRING -> "\"" + escape(scalar.value(), '"') + "\"";
                case CHARACTER -> "'" + escape(scalar.value(), '\'') + "'";
                case BOOLEAN, SIGNED_INTEGER, UNSIGNED_INTEGER, FLOAT -> scalar.value();
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
    }

    private static List<DiagnosticInfo> copyDiagnostics(List<DiagnosticInfo> values) {
        Objects.requireNonNull(values, "diagnostics");
        ArrayList<DiagnosticInfo> copy = new ArrayList<>(values.size());
        for (DiagnosticInfo value : values) {
            copy.add(Objects.requireNonNull(value, "diagnostics must not contain null"));
        }
        return List.copyOf(copy);
    }

    private static List<Binding> copyBindings(List<Binding> values) {
        Objects.requireNonNull(values, "bindings");
        ArrayList<Binding> copy = new ArrayList<>(values.size());
        for (Binding value : values) {
            copy.add(Objects.requireNonNull(value, "bindings must not contain null"));
        }
        return List.copyOf(copy);
    }

    private static List<RelatedSpan> copyRelatedSpans(List<RelatedSpan> values) {
        Objects.requireNonNull(values, "relatedSpans");
        ArrayList<RelatedSpan> copy = new ArrayList<>(values.size());
        for (RelatedSpan value : values) {
            copy.add(Objects.requireNonNull(value, "related spans must not contain null"));
        }
        return List.copyOf(copy);
    }

    private static Optional<String> copyOptionalText(Optional<String> value, String field) {
        Objects.requireNonNull(value, field).ifPresent(item -> text(item, field));
        return value;
    }

    private static String token(String value, String field) {
        String result = text(value, field);
        if (result.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return result;
    }

    private static String text(String value, String field) {
        Objects.requireNonNull(value, field);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character)) {
                throw new IllegalArgumentException(field + " must not contain control characters");
            }
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(field + " contains an unpaired surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException(field + " contains an unpaired surrogate");
            }
        }
        return value;
    }
}
