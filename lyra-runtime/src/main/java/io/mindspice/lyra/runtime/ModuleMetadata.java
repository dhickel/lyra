package io.mindspice.lyra.runtime;

import java.util.Objects;

/** Immutable compatibility metadata for one emitted module. */
public final class ModuleMetadata implements Comparable<ModuleMetadata> {
    private final ModuleId id;
    private final ModuleRevision revision;
    private final String sourceLabel;

    public ModuleMetadata(ModuleId id, ModuleRevision revision, String sourceLabel) {
        this.id = Objects.requireNonNull(id, "id");
        this.revision = Objects.requireNonNull(revision, "revision");
        this.sourceLabel = requireText(sourceLabel, "sourceLabel");
    }

    public ModuleMetadata(ModuleId id, String revision, String sourceLabel) {
        this(id, ModuleRevision.of(revision), sourceLabel);
    }

    public static ModuleMetadata of(ModuleId id, ModuleRevision revision, String sourceLabel) {
        return new ModuleMetadata(id, revision, sourceLabel);
    }

    public static ModuleMetadata fromSource(ModuleId id, byte[] canonicalUtf8Bytes, String sourceLabel) {
        return new ModuleMetadata(id, ModuleRevision.compute(canonicalUtf8Bytes), sourceLabel);
    }

    public ModuleId id() {
        return id;
    }

    public ModuleId moduleId() {
        return id;
    }

    public ModuleRevision revision() {
        return revision;
    }

    public String sourceLabel() {
        return sourceLabel;
    }

    public String label() {
        return sourceLabel;
    }

    @Override
    public int compareTo(ModuleMetadata other) {
        return id.compareTo(Objects.requireNonNull(other, "other").id);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ModuleMetadata metadata
                && id.equals(metadata.id) && revision.equals(metadata.revision)
                && sourceLabel.equals(metadata.sourceLabel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, revision, sourceLabel);
    }

    @Override
    public String toString() {
        return id + "@" + revision;
    }

    private static String requireText(String value, String field) {
        CanonicalJson.requireUtf8(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(field + " contains a control character");
            }
        }
        if (value.startsWith("/") || value.matches("[A-Za-z]:[/\\\\].*")) {
            throw new IllegalArgumentException(field + " must not be an absolute path");
        }
        return value;
    }
}
