package io.mindspice.lyra.repl;

import java.util.Objects;

/** A zero-based, end-exclusive range measured in UTF-16 code units. */
public record Utf16Range(int startOffset, int endOffset) {
    public Utf16Range {
        if (startOffset < 0 || endOffset < startOffset) {
            throw new IllegalArgumentException(
                    "invalid UTF-16 range [" + startOffset + ", " + endOffset + ")");
        }
    }

    public static Utf16Range at(int offset) {
        return new Utf16Range(offset, offset);
    }

    public static Utf16Range of(int startOffset, int endOffset) {
        return new Utf16Range(startOffset, endOffset);
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

    public boolean encloses(Utf16Range other) {
        Objects.requireNonNull(other, "other");
        return startOffset <= other.startOffset && other.endOffset <= endOffset;
    }

    @Override
    public String toString() {
        return startOffset + ".." + endOffset;
    }
}
