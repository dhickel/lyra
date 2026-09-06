package io.mindspice.lyra.compiler.source;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** An immutable configured filesystem source root. */
public record SourceRoot(Path path) {
    public SourceRoot {
        Objects.requireNonNull(path, "path");
        path = path.toAbsolutePath().normalize();
    }

    public static SourceRoot of(Path path) {
        return new SourceRoot(path);
    }

    public static SourceRoot from(Path path) {
        return new SourceRoot(path);
    }

    /** The absolute lexical path supplied to the configuration. */
    public Path absolutePath() {
        return path;
    }

    /**
     * Returns the canonical directory identity used for root alias
     * deduplication.  The root must already exist.
     */
    public Path canonicalPath() throws IOException {
        return path.toRealPath();
    }

    @Override
    public String toString() {
        return path.toString();
    }
}
