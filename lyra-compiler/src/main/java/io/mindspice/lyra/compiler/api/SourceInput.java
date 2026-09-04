package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.source.SourceId;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** One immutable in-memory root source supplied to the compiler API. */
public record SourceInput(SourceId sourceId, String text) {
    public SourceInput {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(text, "text");
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= text.length()
                        || !Character.isLowSurrogate(text.charAt(index + 1))) {
                    throw new IllegalArgumentException("source text contains an unpaired surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("source text contains an unpaired surrogate");
            }
        }
    }

    public SourceInput(String sourceId, String text) {
        this(SourceId.of(sourceId), text);
    }

    public byte[] utf8Bytes() {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    public byte[] bytes() {
        return utf8Bytes();
    }
}
