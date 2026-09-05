package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/** Passive caller location metadata for a session submission. */
public record SourceOrigin(
        String label,
        Optional<URI> uri,
        Optional<Long> documentVersion,
        int originStartOffset,
        int originEndOffset) {
    public SourceOrigin {
        label = text(label, "label");
        uri = Objects.requireNonNull(uri, "uri");
        documentVersion = Objects.requireNonNull(documentVersion, "documentVersion");
        if (uri.isPresent()) {
            URI value = Objects.requireNonNull(uri.orElseThrow(), "uri").normalize();
            if (!value.isAbsolute()) {
                throw new IllegalArgumentException("source URI must be absolute: " + value);
            }
            if (value.getRawFragment() != null) {
                throw new IllegalArgumentException("source URI must not contain a fragment: " + value);
            }
            uri = Optional.of(value);
        }
        if (documentVersion.isPresent() && documentVersion.orElseThrow() < 0) {
            throw new IllegalArgumentException("document version must not be negative");
        }
        if (originStartOffset < 0 || originEndOffset < originStartOffset) {
            throw new IllegalArgumentException("invalid origin UTF-16 range");
        }
    }

    public SourceOrigin(String label, int originStartOffset, int originEndOffset) {
        this(label, Optional.empty(), Optional.empty(), originStartOffset, originEndOffset);
    }

    public static SourceOrigin forText(String label, int utf16Length) {
        if (utf16Length < 0) {
            throw new IllegalArgumentException("UTF-16 length must not be negative");
        }
        return new SourceOrigin(label, 0, utf16Length);
    }

    public int originLength() {
        return originEndOffset - originStartOffset;
    }

    public int mapOffset(int localOffset) {
        if (localOffset < 0 || localOffset > originLength()) {
            throw new IllegalArgumentException("local UTF-16 offset outside origin");
        }
        return Math.addExact(originStartOffset, localOffset);
    }

    public SourceSpan map(SourceSpan local, SourceId fallbackSource) {
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(fallbackSource, "fallbackSource");
        SourceId source = uri.map(SourceId::uri).orElse(fallbackSource);
        return SourceSpan.of(source, mapOffset(local.startOffset()), mapOffset(local.endOffset()));
    }

    private static String text(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (Character.isISOControl(c)) {
                throw new IllegalArgumentException(field + " must not contain control characters");
            }
            if (Character.isHighSurrogate(c)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++index))) {
                    throw new IllegalArgumentException(field + " contains an unpaired surrogate");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException(field + " contains an unpaired surrogate");
            }
        }
        return value;
    }
}
