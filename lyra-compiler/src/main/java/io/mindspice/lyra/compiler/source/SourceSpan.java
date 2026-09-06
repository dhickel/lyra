package io.mindspice.lyra.compiler.source;

import java.util.Objects;

/** A zero-based, end-exclusive span measured in decoded UTF-16 code units. */
public record SourceSpan(SourceId sourceId, int startOffset, int endOffset) {
    public SourceSpan {
        Objects.requireNonNull(sourceId, "sourceId");
        if (startOffset < 0 || endOffset < startOffset) {
            throw new IllegalArgumentException(
                    "invalid source span [" + startOffset + ", " + endOffset + ")");
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

    public SourcePosition startPosition(SourceSnapshot snapshot) {
        validateAgainst(snapshot);
        return snapshot.positionAt(startOffset);
    }

    public SourcePosition endPosition(SourceSnapshot snapshot) {
        validateAgainst(snapshot);
        return snapshot.positionAt(endOffset);
    }

    public int startLine(SourceSnapshot snapshot) {
        return startPosition(snapshot).line();
    }

    public int startColumn(SourceSnapshot snapshot) {
        return startPosition(snapshot).column();
    }

    public int endLine(SourceSnapshot snapshot) {
        return endPosition(snapshot).line();
    }

    public int endColumn(SourceSnapshot snapshot) {
        return endPosition(snapshot).column();
    }

    /** Returns a conventional one-based source location for the span start. */
    public String location(SourceSnapshot snapshot) {
        SourcePosition position = startPosition(snapshot);
        return sourceId + ":" + position.line() + ":" + position.column();
    }

    /** Ensures that this span belongs to and fits within a snapshot. */
    public void validateAgainst(SourceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!sourceId.equals(snapshot.sourceId())) {
            throw new IllegalArgumentException(
                    "span belongs to " + sourceId + " but snapshot belongs to " + snapshot.sourceId());
        }
        if (endOffset > snapshot.utf16Length()) {
            throw new IllegalArgumentException(
                    "span end " + endOffset + " exceeds source length " + snapshot.utf16Length());
        }
    }

    /** Covers both spans when they identify the same source. */
    public static SourceSpan cover(SourceSpan first, SourceSpan second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (!first.sourceId.equals(second.sourceId)) {
            throw new IllegalArgumentException("cannot cover spans from different sources");
        }
        return new SourceSpan(
                first.sourceId,
                Math.min(first.startOffset, second.startOffset),
                Math.max(first.endOffset, second.endOffset));
    }

    @Override
    public String toString() {
        return sourceId + ":" + startOffset + ".." + endOffset;
    }
}
