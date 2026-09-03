package io.mindspice.lyra.runtime;

import java.util.Objects;
import java.util.Optional;

/** Immutable related source information attached to a runtime failure. */
public final class RelatedSource {
    private final String relation;
    private final SourceSpan span;
    private final Optional<SourceData> sourceData;
    private final Optional<String> sourceLabel;

    public RelatedSource(String relation, SourceSpan span) {
        this(relation, span, Optional.empty(), Optional.empty());
    }

    public RelatedSource(String relation, SourceSpan span, SourceData sourceData) {
        this(relation, span, Optional.of(Objects.requireNonNull(sourceData, "sourceData")),
                Objects.requireNonNull(sourceData, "sourceData").label());
    }

    public RelatedSource(String relation, SourceSpan span, String sourceLabel) {
        this(relation, span, Optional.empty(), Optional.of(requireLabel(sourceLabel)));
    }

    public RelatedSource(String relation, SourceSpan span, Optional<SourceData> sourceData) {
        this(relation, span, sourceData,
                Objects.requireNonNull(sourceData, "sourceData").flatMap(SourceData::label));
    }

    private RelatedSource(String relation, SourceSpan span, Optional<SourceData> sourceData,
                          Optional<String> sourceLabel) {
        this.relation = CanonicalJson.requireUtf8(relation, "relation");
        if (relation.isBlank()) {
            throw new IllegalArgumentException("relation must not be blank");
        }
        for (int index = 0; index < relation.length(); index++) {
            if (Character.isISOControl(relation.charAt(index))) {
                throw new IllegalArgumentException("relation contains a control character");
            }
        }
        this.span = Objects.requireNonNull(span, "span");
        this.sourceData = Objects.requireNonNull(sourceData, "sourceData").map(data -> {
            if (!span.sourceId().equals(data.sourceId())) {
                throw new IllegalArgumentException("related span and source data have different source IDs");
            }
            span.validateAgainst(data);
            return data;
        });
        this.sourceLabel = Objects.requireNonNull(sourceLabel, "sourceLabel").map(RelatedSource::requireLabel);
    }

    public String relation() {
        return relation;
    }

    public SourceSpan span() {
        return span;
    }

    public Optional<SourceData> sourceData() {
        return sourceData;
    }

    public Optional<String> sourceLabel() {
        return sourceLabel;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof RelatedSource source
                && relation.equals(source.relation) && span.equals(source.span)
                && sourceData.equals(source.sourceData)
                && sourceLabel.equals(source.sourceLabel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(relation, span, sourceData, sourceLabel);
    }

    @Override
    public String toString() {
        return render();
    }

    public String render() {
        if (sourceData.isEmpty()) {
            return relation + ": " + sourceLabel.map(label -> label + ":"
                    + span.startOffset() + ".." + span.endOffset()).orElse(span.toString());
        }
        SourceData source = sourceData.orElseThrow();
        SourcePosition position = source.positionAt(span.startOffset());
        return relation + ": " + source.label().orElse(span.sourceId().value()) + ":"
                + position.line() + ":" + position.column();
    }

    private static String requireLabel(String value) {
        CanonicalJson.requireUtf8(value, "sourceLabel");
        if (value.isBlank()) throw new IllegalArgumentException("sourceLabel must not be blank");
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException("sourceLabel contains a control character");
            }
        }
        return value;
    }
}
