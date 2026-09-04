package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.runtime.ArtifactMetadata;
import io.mindspice.lyra.runtime.ArtifactSource;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Immutable output of the compiler. It contains class/artifact data only and
 * never owns a live module instance.
 */
public interface CompiledArtifact extends ArtifactSource {
    @Override
    ArtifactMetadata metadata();

    /** Writes the class-directory representation. */
    void writeClasses(Path output, WriteOptions options) throws IOException;

    /** Writes a deterministic thin or bundled JAR representation. */
    void writeJar(Path output, JarMode mode, WriteOptions options) throws IOException;

    default void writeClasses(Path output) throws IOException {
        writeClasses(output, WriteOptions.defaults());
    }

    default void writeJar(Path output, JarMode mode) throws IOException {
        writeJar(output, mode, WriteOptions.defaults());
    }
}
