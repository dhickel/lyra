package io.mindspice.lyra.compiler.api;

import java.util.Objects;

/** Immutable UTF-16 source text plus passive source-origin mapping metadata. */
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

    public int mapOriginOffset(int localOffset) {
        if (localOffset < 0 || localOffset > text.length()) {
            throw new IllegalArgumentException("source offset outside submitted text");
        }
        return origin.mapOffset(localOffset);
    }

    private static void requireWellFormedUtf16(String value) {
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (Character.isHighSurrogate(c)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++index))) {
                    throw new IllegalArgumentException("source text contains an unpaired surrogate");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("source text contains an unpaired surrogate");
            }
        }
    }
}
