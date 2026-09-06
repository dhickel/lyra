package io.mindspice.lyra.compiler.diagnostic;

import io.mindspice.lyra.compiler.source.SourcePosition;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.List;
import java.util.Objects;

/** An immutable compiler diagnostic with one complete primary span. */
public record Diagnostic(
        DiagnosticCode code,
        Severity severity,
        String summary,
        SourceSpan primarySpan,
        List<RelatedSpan> relatedSpans) {
    public Diagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(summary, "summary");
        if (summary.isBlank()) {
            throw new IllegalArgumentException("diagnostic summary must not be blank");
        }
        Objects.requireNonNull(primarySpan, "primarySpan");
        if (primarySpan.sourceId() == null) {
            throw new IllegalArgumentException("primary span must have a source identity");
        }
        Objects.requireNonNull(relatedSpans, "relatedSpans");
        relatedSpans = List.copyOf(relatedSpans);
        if (relatedSpans.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("related spans must not contain null");
        }
    }

    public static Diagnostic of(
            DiagnosticCode code,
            Severity severity,
            String summary,
            SourceSpan primarySpan) {
        return new Diagnostic(code, severity, summary, primarySpan, List.of());
    }

    public static Diagnostic of(
            DiagnosticCode code,
            Severity severity,
            String summary,
            SourceSpan primarySpan,
            List<RelatedSpan> relatedSpans) {
        return new Diagnostic(code, severity, summary, primarySpan, relatedSpans);
    }

    public static Diagnostic error(
            DiagnosticCode code, SourceSpan primarySpan, String summary) {
        return of(code, Severity.ERROR, summary, primarySpan);
    }

    public static Diagnostic error(
            DiagnosticCode code,
            SourceSpan primarySpan,
            String summary,
            List<RelatedSpan> relatedSpans) {
        return of(code, Severity.ERROR, summary, primarySpan, relatedSpans);
    }

    public static Diagnostic warning(
            DiagnosticCode code, SourceSpan primarySpan, String summary) {
        return of(code, Severity.WARNING, summary, primarySpan);
    }

    public static Diagnostic info(
            DiagnosticCode code, SourceSpan primarySpan, String summary) {
        return of(code, Severity.INFO, summary, primarySpan);
    }

    public Phase phase() {
        return code.phase();
    }

    public String message() {
        return summary;
    }

    /** Validates the primary span against its source snapshot. */
    public void validateAgainst(SourceSnapshot snapshot) {
        primarySpan.validateAgainst(snapshot);
        for (RelatedSpan related : relatedSpans) {
            if (related.span().sourceId().equals(snapshot.sourceId())) {
                related.span().validateAgainst(snapshot);
            }
        }
    }

    /**
     * Renders a location-only diagnostic using UTF-16 offsets. This form is
     * safe when source text is unavailable.
     */
    public String render() {
        StringBuilder rendered = new StringBuilder()
                .append(severity)
                .append(' ')
                .append(code.value())
                .append(' ')
                .append(primarySpan.sourceId())
                .append(':')
                .append(primarySpan.startOffset())
                .append("..")
                .append(primarySpan.endOffset())
                .append(": ")
                .append(summary);
        appendRelatedOffsets(rendered);
        return rendered.toString();
    }

    /** Renders the primary source line and an UTF-16-counted caret marker. */
    public String render(SourceSnapshot snapshot) {
        validateAgainst(snapshot);
        SourcePosition start = snapshot.positionAt(primarySpan.startOffset());
        StringBuilder rendered = new StringBuilder()
                .append(severity)
                .append(' ')
                .append(code.value())
                .append(' ')
                .append(primarySpan.sourceId())
                .append(':')
                .append(start.line())
                .append(':')
                .append(start.column())
                .append(": ")
                .append(summary);

        String line = snapshot.lineIndex().lineText(start.line());
        int markerStart = Math.min(start.column() - 1, line.length());
        int markerEnd = Math.min(primarySpan.endOffset(), snapshot.lineIndex().lineEndOffset(start.line()));
        int markerLength = Math.max(1, markerEnd - primarySpan.startOffset());
        rendered.append('\n')
                .append(line)
                .append('\n')
                .append(" ".repeat(markerStart))
                .append("^".repeat(markerLength));
        appendRelatedLocations(rendered, snapshot);
        return rendered.toString();
    }

    private void appendRelatedOffsets(StringBuilder rendered) {
        for (RelatedSpan related : relatedSpans) {
            rendered.append('\n')
                    .append("  ")
                    .append(related.label())
                    .append(": ")
                    .append(related.span());
        }
    }

    private void appendRelatedLocations(StringBuilder rendered, SourceSnapshot snapshot) {
        for (RelatedSpan related : relatedSpans) {
            SourceSpan span = related.span();
            rendered.append('\n')
                    .append("  ")
                    .append(related.label())
                    .append(": ");
            if (span.sourceId().equals(snapshot.sourceId())) {
                SourcePosition position = snapshot.positionAt(
                        Math.min(span.startOffset(), snapshot.utf16Length()));
                rendered.append(span.sourceId())
                        .append(':')
                        .append(position.line())
                        .append(':')
                        .append(position.column());
            } else {
                rendered.append(span);
            }
        }
    }
}
