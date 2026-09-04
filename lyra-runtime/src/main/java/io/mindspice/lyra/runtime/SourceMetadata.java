package io.mindspice.lyra.runtime;

import java.util.Objects;
import java.util.Optional;

/** Immutable source identity/hash entry recorded by an emitted artifact. */
public final class SourceMetadata implements Comparable<SourceMetadata> {
    private final SourceId sourceId;
    private final String sourceLabel;
    private final String sha256;
    private final Optional<String> entryName;

    public SourceMetadata(SourceId sourceId, String sourceLabel, String sha256,
                          Optional<String> entryName) {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.sourceLabel = text(sourceLabel, "sourceLabel");
        this.sha256 = revision(sha256, "sha256");
        this.entryName = Objects.requireNonNull(entryName, "entryName")
                .map(value -> entry(value));
    }

    public SourceMetadata(SourceId sourceId, String sourceLabel, String sha256) {
        this(sourceId, sourceLabel, sha256, Optional.empty());
    }

    public SourceMetadata(String sourceId, String sourceLabel, String sha256,
                          Optional<String> entryName) {
        this(SourceId.of(sourceId), sourceLabel, sha256, entryName);
    }

    public SourceId sourceId() {
        return sourceId;
    }

    public SourceId id() {
        return sourceId;
    }

    public String sourceLabel() {
        return sourceLabel;
    }

    public String label() {
        return sourceLabel;
    }

    public String sha256() {
        return sha256;
    }

    public String hash() {
        return sha256;
    }

    public Optional<String> entryName() {
        return entryName;
    }

    public Optional<String> entry() {
        return entryName;
    }

    public boolean includesSource() {
        return entryName.isPresent();
    }

    @Override
    public int compareTo(SourceMetadata other) {
        return sourceId.compareTo(Objects.requireNonNull(other, "other").sourceId);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof SourceMetadata source
                && sourceId.equals(source.sourceId)
                && sourceLabel.equals(source.sourceLabel)
                && sha256.equals(source.sha256)
                && entryName.equals(source.entryName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceId, sourceLabel, sha256, entryName);
    }

    @Override
    public String toString() {
        return sourceId + "@" + sha256;
    }

    private static String text(String value, String field) {
        CanonicalJson.requireUtf8(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(field + " contains a control character");
            }
        }
        if (value.startsWith("/") || value.startsWith("\\\\")
                || value.matches("[A-Za-z]:[/\\\\].*")) {
            throw new IllegalArgumentException(field + " must not be an absolute path");
        }
        return value;
    }

    private static String revision(String value, String field) {
        text(value, field);
        if (!ModuleRevision.isRevision(value)) {
            throw new IllegalArgumentException("invalid " + field + ": " + value);
        }
        return value;
    }

    private static String entry(String value) {
        CanonicalJson.requireUtf8(value, "entryName");
        if (value.isBlank() || value.startsWith("/") || value.indexOf('\\') >= 0
                || value.contains("//") || value.startsWith("./")
                || value.contains("../") || value.endsWith("/..")
                || value.endsWith("/") || !value.startsWith("META-INF/lyra/sources/")) {
            throw new IllegalArgumentException("invalid source entry name: " + value);
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException("invalid source entry name: " + value);
            }
        }
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("invalid source entry name: " + value);
            }
        }
        return value;
    }
}
