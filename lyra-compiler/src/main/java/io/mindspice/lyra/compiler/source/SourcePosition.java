package io.mindspice.lyra.compiler.source;

/** A one-based rendered location for a zero-based UTF-16 source offset. */
public record SourcePosition(int offset, int line, int column) {
    public SourcePosition {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative: " + offset);
        }
        if (line < 1) {
            throw new IllegalArgumentException("line must be one-based: " + line);
        }
        if (column < 1) {
            throw new IllegalArgumentException("column must be one-based: " + column);
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
