package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.diagnostic.Diagnostic;

import java.util.List;
import java.util.Objects;

/** Immutable public result of one complete compilation attempt. */
public sealed interface CompileResult permits CompileResult.Success, CompileResult.Failure {
    List<Diagnostic> diagnostics();

    default boolean isSuccess() {
        return this instanceof Success;
    }

    default boolean isFailure() {
        return this instanceof Failure;
    }

    record Success(CompiledArtifact artifact, List<Diagnostic> diagnostics)
            implements CompileResult {
        public Success {
            Objects.requireNonNull(artifact, "artifact");
            diagnostics = copyDiagnostics(diagnostics);
            if (diagnostics.stream().anyMatch(value -> value.severity().isError())) {
                throw new IllegalArgumentException("successful compilation cannot contain an error diagnostic");
            }
        }
    }

    record Failure(List<Diagnostic> diagnostics) implements CompileResult {
        public Failure {
            diagnostics = copyDiagnostics(diagnostics);
            if (diagnostics.isEmpty()) {
                throw new IllegalArgumentException("failed compilation needs a diagnostic");
            }
            if (diagnostics.stream().noneMatch(value -> value.severity().isError())) {
                throw new IllegalArgumentException("failed compilation needs an error diagnostic");
            }
        }
    }

    private static List<Diagnostic> copyDiagnostics(List<Diagnostic> values) {
        Objects.requireNonNull(values, "diagnostics");
        for (Diagnostic value : values) {
            Objects.requireNonNull(value, "diagnostics must not contain null");
        }
        return List.copyOf(values);
    }
}
