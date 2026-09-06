package io.mindspice.lyra.repl;

import java.util.Objects;

/** Immutable submitted source text together with passive origin metadata. */
public record EvaluationSource(SourceOrigin origin, String text) {
    public EvaluationSource {
        origin = Objects.requireNonNull(origin, "origin");
        text = Objects.requireNonNull(text, "text");
        requireWellFormedUtf16(text);
        if (origin.originLength() != text.length()) {
            throw new IllegalArgumentException(
                    "origin UTF-16 length " + origin.originLength()
                            + " does not match source length " + text.length());
        }
    }

    public EvaluationSource(String text, SourceOrigin origin) {
        this(origin, text);
    }

    public static EvaluationSource of(String label, String text) {
        Objects.requireNonNull(text, "text");
        return new EvaluationSource(SourceOrigin.forText(label, text.length()), text);
    }

    public int utf16Length() {
        return text.length();
    }

    public Utf16Range range() {
        return new Utf16Range(0, text.length());
    }

    public int mapOriginOffset(int sourceOffset) {
        validateSourceOffset(sourceOffset);
        return origin.mapOffset(sourceOffset);
    }

    public Utf16Range mapOriginRange(Utf16Range sourceRange) {
        Objects.requireNonNull(sourceRange, "sourceRange");
        if (sourceRange.endOffset() > text.length()) {
            throw new IllegalArgumentException(
                    "source UTF-16 range exceeds source length: " + sourceRange);
        }
        return origin.mapRange(sourceRange);
    }

    private void validateSourceOffset(int offset) {
        if (offset < 0 || offset > text.length()) {
            throw new IllegalArgumentException("source UTF-16 offset outside source: " + offset);
        }
    }

    private static void requireWellFormedUtf16(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("source text contains an unpaired surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("source text contains an unpaired surrogate");
            }
        }
    }
}
