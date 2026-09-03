package io.mindspice.lyra.runtime;

import java.util.Arrays;
import java.util.Objects;

/** Immutable LF/CR/CRLF-aware line index over decoded UTF-16 text. */
public final class SourceLineIndex {
    private final String text;
    private final int[] lineStarts;
    private final int[] lineEnds;

    public SourceLineIndex(String text) {
        this.text = Objects.requireNonNull(text, "text");
        int[] starts = new int[Math.max(1, text.length() + 1)];
        int[] ends = new int[Math.max(1, text.length() + 1)];
        int line = 0;
        starts[0] = 0;
        int index = 0;
        while (index < text.length()) {
            char character = text.charAt(index);
            if (character == '\r' || character == '\n') {
                ends[line] = index;
                if (character == '\r' && index + 1 < text.length()
                        && text.charAt(index + 1) == '\n') {
                    index += 2;
                } else {
                    index++;
                }
                starts[++line] = index;
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
        return new SourcePosition(utf16Offset, line,
                utf16Offset - lineStarts[line - 1] + 1);
    }

    public int lineAt(int utf16Offset) {
        if (utf16Offset < 0 || utf16Offset > text.length()) {
            throw new IllegalArgumentException("UTF-16 offset outside source: " + utf16Offset);
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
