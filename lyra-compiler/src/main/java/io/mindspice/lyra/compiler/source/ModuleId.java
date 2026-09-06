package io.mindspice.lyra.compiler.source;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

/** Stable external identity of a source module. */
public record ModuleId(SourceId sourceId) implements Comparable<ModuleId> {
    public ModuleId {
        Objects.requireNonNull(sourceId, "sourceId");
    }

    public ModuleId(String value) {
        this(SourceId.of(value));
    }

    public static ModuleId of(String value) {
        return new ModuleId(value);
    }

    public static ModuleId path(String relativePath) {
        return new ModuleId(SourceId.path(relativePath));
    }

    public static ModuleId uri(URI uri) {
        return new ModuleId(SourceId.uri(uri));
    }

    public static ModuleId fromSourceId(SourceId sourceId) {
        return new ModuleId(sourceId);
    }

    public String value() {
        return sourceId.value();
    }

    public boolean isUri() {
        return sourceId.isUri();
    }

    public boolean isPath() {
        return sourceId.isPath();
    }

    public Path asPath() {
        return sourceId.asPath();
    }

    public URI asUri() {
        return sourceId.asUri();
    }

    @Override
    public int compareTo(ModuleId other) {
        Objects.requireNonNull(other, "other");
        int valueComparison = value().compareTo(other.value());
        if (valueComparison != 0) {
            return valueComparison;
        }
        return Boolean.compare(isUri(), other.isUri());
    }

    @Override
    public String toString() {
        return value();
    }
}
