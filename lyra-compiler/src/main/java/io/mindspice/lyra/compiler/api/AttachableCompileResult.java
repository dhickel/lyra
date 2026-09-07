package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.diagnostic.Diagnostic;

import java.util.List;
import java.util.Objects;

/**
 * Immutable result of one attachable compilation, including the sealed
 * source-independent root context required to open a live attachment.
 */
public sealed interface AttachableCompileResult
        permits AttachableCompileResult.Success, AttachableCompileResult.Failure {
    List<Diagnostic> diagnostics();

    default boolean isSuccess() {
        return this instanceof Success;
    }

    default boolean isFailure() {
        return this instanceof Failure;
    }

    record Success(CompiledArtifact artifact, AttachableRootContext context,
                   List<Diagnostic> diagnostics) implements AttachableCompileResult {
        public Success {
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(context, "context");
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
            if (diagnostics.stream().anyMatch(value -> value.severity().isError())) {
                throw new IllegalArgumentException(
                        "successful attachable compilation cannot contain an error diagnostic");
            }
            if (artifact.metadata().artifactProfile()
                    != io.mindspice.lyra.runtime.ArtifactProfile.ATTACHABLE) {
                throw new IllegalArgumentException(
                        "attachable compilation must publish an attachable artifact");
            }
            if (!context.attachmentContext()
                    .equals(artifact.metadata().attachmentContext().orElseThrow())) {
                throw new IllegalArgumentException(
                        "attachable context disagrees with the published artifact");
            }
        }
    }

    record Failure(List<Diagnostic> diagnostics) implements AttachableCompileResult {
        public Failure {
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
            if (diagnostics.isEmpty()) {
                throw new IllegalArgumentException(
                        "failed attachable compilation needs a diagnostic");
            }
            if (diagnostics.stream().noneMatch(value -> value.severity().isError())) {
                throw new IllegalArgumentException(
                        "failed attachable compilation needs an error diagnostic");
            }
        }
    }
}
