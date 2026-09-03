package io.mindspice.lyra.runtime;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

/** Stable external identity of a source module. */
public final class ModuleId implements Comparable<ModuleId> {
    private final SourceId sourceId;

    public ModuleId(SourceId sourceId) {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
    }

    public ModuleId(String value) {
        this(parse(value));
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

    public SourceId sourceId() {
        return sourceId;
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

    public String canonicalSpelling() {
        return (isUri() ? "uri:" : "path:") + value();
    }

    public String canonical() {
        return canonicalSpelling();
    }

    @Override
    public int compareTo(ModuleId other) {
        return canonicalSpelling().compareTo(Objects.requireNonNull(other, "other").canonicalSpelling());
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ModuleId module && sourceId.equals(module.sourceId);
    }

    @Override
    public int hashCode() {
        return sourceId.hashCode();
    }

    @Override
    public String toString() {
        return value();
    }

    private static SourceId parse(String value) {
        Objects.requireNonNull(value, "value");
        if (value.startsWith("path:")) {
            return SourceId.path(value.substring("path:".length()));
        }
        if (value.startsWith("uri:")) {
            return SourceId.uri(URI.create(value.substring("uri:".length())));
        }
        return SourceId.of(value);
    }
}
