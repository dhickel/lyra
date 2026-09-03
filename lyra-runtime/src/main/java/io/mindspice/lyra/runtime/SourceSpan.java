package io.mindspice.lyra.runtime;

import java.util.Objects;

/** Zero-based, end-exclusive span in decoded UTF-16 code units. */
public record SourceSpan(SourceId sourceId, int startOffset, int endOffset) {
    public SourceSpan {
        Objects.requireNonNull(sourceId, "sourceId");
        if (startOffset < 0 || endOffset < startOffset) {
            throw new IllegalArgumentException("invalid source span [" + startOffset + ", "
                    + endOffset + ")");
        }
    }

    public static SourceSpan at(SourceId sourceId, int offset) {
        return new SourceSpan(sourceId, offset, offset);
    }

    public static SourceSpan of(SourceId sourceId, int startOffset, int endOffset) {
        return new SourceSpan(sourceId, startOffset, endOffset);
    }

    public int start() {
        return startOffset;
    }

    public int end() {
        return endOffset;
    }

    public int length() {
        return endOffset - startOffset;
    }

    public boolean isEmpty() {
        return startOffset == endOffset;
    }

    public boolean contains(int offset) {
        return startOffset <= offset && offset < endOffset;
    }

    public boolean containsOrEndsAt(int offset) {
        return startOffset <= offset && offset <= endOffset;
    }

    public SourcePosition startPosition(SourceData source) {
        validateAgainst(source);
        return source.positionAt(startOffset);
    }

    public SourcePosition endPosition(SourceData source) {
        validateAgainst(source);
        return source.positionAt(endOffset);
    }

    public String location(SourceData source) {
        return sourceId + ":" + startPosition(source).line() + ":" + startPosition(source).column();
    }

    public void validateAgainst(SourceData source) {
        Objects.requireNonNull(source, "source");
        if (!sourceId.equals(source.sourceId())) {
            throw new IllegalArgumentException("span belongs to " + sourceId
                    + " but source belongs to " + source.sourceId());
        }
        if (endOffset > source.utf16Length()) {
            throw new IllegalArgumentException("span end exceeds source length: " + endOffset);
        }
    }

    public static SourceSpan cover(SourceSpan first, SourceSpan second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (!first.sourceId.equals(second.sourceId)) {
            throw new IllegalArgumentException("cannot cover spans from different sources");
        }
        return new SourceSpan(first.sourceId,
                Math.min(first.startOffset, second.startOffset),
                Math.max(first.endOffset, second.endOffset));
    }

    @Override
    public String toString() {
        return sourceId + ":" + startOffset + ".." + endOffset;
    }
}
