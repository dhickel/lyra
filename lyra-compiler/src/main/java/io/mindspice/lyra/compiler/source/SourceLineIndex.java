package io.mindspice.lyra.compiler.source;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable UTF-16 line index for a decoded source string.
 *
 * <p>Lines are one-based. LF, CR, and CRLF are treated as line endings; a
 * CRLF pair contributes one line break and both code units remain part of the
 * source offset space.</p>
 */
public final class SourceLineIndex {
    private final String text;
    private final int[] lineStarts;
    private final int[] lineEnds;

    SourceLineIndex(String text) {
        this.text = Objects.requireNonNull(text, "text");

        int capacity = Math.max(1, text.length() + 1);
        int[] starts = new int[capacity];
        int[] ends = new int[capacity];
        int line = 0;
        starts[line] = 0;
        int index = 0;
        while (index < text.length()) {
            char character = text.charAt(index);
            if (character == '\r' || character == '\n') {
                ends[line] = index;
                if (character == '\r'
                        && index + 1 < text.length()
                        && text.charAt(index + 1) == '\n') {
                    index += 2;
                } else {
                    index++;
                }
                line++;
                starts[line] = index;
            } else {
                index++;
            }
        }
        ends[line] = text.length();

        lineStarts = Arrays.copyOf(starts, line + 1);
        lineEnds = Arrays.copyOf(ends, line + 1);
    }

    public int lineCount() {
        return lineStarts.length;
    }

    public SourcePosition positionAt(int utf16Offset) {
        int line = lineAt(utf16Offset);
        return new SourcePosition(
                utf16Offset,
                line,
                utf16Offset - lineStarts[line - 1] + 1);
    }

    public int lineAt(int utf16Offset) {
        if (utf16Offset < 0 || utf16Offset > text.length()) {
            throw new IllegalArgumentException(
                    "UTF-16 offset outside source: " + utf16Offset);
        }
        int index = Arrays.binarySearch(lineStarts, utf16Offset);
        if (index >= 0) {
            return index + 1;
        }
        return -index - 1;
    }

    public int columnAt(int utf16Offset) {
        return positionAt(utf16Offset).column();
    }

    public int lineStartOffset(int line) {
        checkLine(line);
        return lineStarts[line - 1];
    }

    /** Returns the offset immediately before the line ending, or source length. */
    public int lineEndOffset(int line) {
        checkLine(line);
        return lineEnds[line - 1];
    }

    public String lineText(int line) {
        checkLine(line);
        return text.substring(lineStarts[line - 1], lineEnds[line - 1]);
    }

    private void checkLine(int line) {
        if (line < 1 || line > lineStarts.length) {
            throw new IllegalArgumentException("line outside source: " + line);
        }
    }
}
