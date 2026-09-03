package io.mindspice.lyra.runtime;

import java.util.Objects;
import java.util.Optional;

/** Optional decoded source material used only for rendering, never for span validity. */
public final class SourceData {
    private final SourceId sourceId;
    private final Optional<String> label;
    private final String text;
    private final SourceLineIndex lineIndex;

    public SourceData(SourceId sourceId, String text) {
        this(sourceId, Optional.empty(), text);
    }

    public SourceData(SourceId sourceId, String label, String text) {
        this(sourceId, Optional.of(Objects.requireNonNull(label, "label")), text);
    }

    public SourceData(SourceId sourceId, Optional<String> label, String text) {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.label = Objects.requireNonNull(label, "label").map(value -> {
            CanonicalJson.requireUtf8(value, "label");
            if (value.isBlank()) {
                throw new IllegalArgumentException("source label must not be blank");
            }
            for (int index = 0; index < value.length(); index++) {
                if (Character.isISOControl(value.charAt(index))) {
                    throw new IllegalArgumentException("source label contains a control character");
                }
            }
            return value;
        });
        this.text = Objects.requireNonNull(text, "text");
        this.lineIndex = new SourceLineIndex(text);
    }

    public SourceId sourceId() {
        return sourceId;
    }

    public Optional<String> label() {
        return label;
    }

    public Optional<String> sourceLabel() {
        return label;
    }

    public String text() {
        return text;
    }

    public String sourceText() {
        return text;
    }

    public int utf16Length() {
        return text.length();
    }

    public SourceLineIndex lineIndex() {
        return lineIndex;
    }

    public SourcePosition positionAt(int offset) {
        return lineIndex.positionAt(offset);
    }

    public String lineText(int line) {
        return lineIndex.lineText(line);
    }

    /** Returns the source line containing the span start. */
    public String excerpt(SourceSpan span) {
        span.validateAgainst(this);
        return lineText(positionAt(span.startOffset()).line());
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof SourceData data
                && sourceId.equals(data.sourceId)
                && label.equals(data.label)
                && text.equals(data.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceId, label, text);
    }
}
