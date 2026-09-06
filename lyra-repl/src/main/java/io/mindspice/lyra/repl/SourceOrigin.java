package io.mindspice.lyra.repl;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * Passive source-location metadata supplied by an evaluation caller.
 * Offsets identify the UTF-16 range occupied by the submitted source in the
 * caller's document; they do not identify or retain a document object.
 */
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
            URI value = Objects.requireNonNull(uri.get(), "uri value").normalize();
            if (!value.isAbsolute()) {
                throw new IllegalArgumentException("source URI must be absolute: " + value);
            }
            if (value.getRawFragment() != null) {
                throw new IllegalArgumentException("source URI must not contain a fragment: " + value);
            }
            uri = Optional.of(value);
        }
        if (documentVersion.isPresent() && documentVersion.get() < 0) {
            throw new IllegalArgumentException(
                    "document version must not be negative: " + documentVersion.get());
        }
        if (originStartOffset < 0 || originEndOffset < originStartOffset) {
            throw new IllegalArgumentException(
                    "invalid origin UTF-16 range ["
                            + originStartOffset + ", " + originEndOffset + ")");
        }
    }

    public SourceOrigin(String label, int originStartOffset, int originEndOffset) {
        this(label, Optional.empty(), Optional.empty(), originStartOffset, originEndOffset);
    }

    public static SourceOrigin forText(String label, int utf16Length) {
        if (utf16Length < 0) {
            throw new IllegalArgumentException("UTF-16 length must not be negative: " + utf16Length);
        }
        return new SourceOrigin(label, Optional.empty(), Optional.empty(), 0, utf16Length);
    }

    public Utf16Range originRange() {
        return new Utf16Range(originStartOffset, originEndOffset);
    }

    public int originLength() {
        return originEndOffset - originStartOffset;
    }

    /** Maps a local source offset, including its end boundary, to origin space. */
    public int mapOffset(int localOffset) {
        if (localOffset < 0 || localOffset > originLength()) {
            throw new IllegalArgumentException(
                    "local UTF-16 offset outside origin range: " + localOffset);
        }
        return Math.toIntExact((long) originStartOffset + localOffset);
    }

    /** Maps a local source range to the caller's origin range. */
    public Utf16Range mapRange(Utf16Range localRange) {
        Objects.requireNonNull(localRange, "localRange");
        if (localRange.endOffset() > originLength()) {
            throw new IllegalArgumentException(
                    "local UTF-16 range exceeds origin length: " + localRange);
        }
        return new Utf16Range(mapOffset(localRange.startOffset()), mapOffset(localRange.endOffset()));
    }

    private static String text(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(field + " must not contain control characters");
            }
            if (Character.isHighSurrogate(value.charAt(index))) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(field + " contains an unpaired surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(value.charAt(index))) {
                throw new IllegalArgumentException(field + " contains an unpaired surrogate");
            }
        }
        return value;
    }
}
