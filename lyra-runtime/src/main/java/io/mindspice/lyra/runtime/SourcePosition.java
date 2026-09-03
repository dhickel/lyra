package io.mindspice.lyra.runtime;

/** One-based rendered line/column for a zero-based UTF-16 offset. */
public record SourcePosition(int offset, int line, int column) {
    public SourcePosition {
        if (offset < 0 || line < 1 || column < 1) {
            throw new IllegalArgumentException("invalid source position: " + offset + ", "
                    + line + ", " + column);
        }
    }

    public int utf16Offset() {
        return offset;
    }

    @Override
    public String toString() {
        return line + ":" + column;
    }
}
