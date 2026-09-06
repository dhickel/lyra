package io.mindspice.lyra.compiler.diagnostic;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable phase boundary result. A failure carries diagnostics only and can
 * never expose a partial phase artifact.
 */
public sealed interface PhaseResult<T extends ImmutablePhaseArtifact>
        permits PhaseResult.Success, PhaseResult.Failure {
    List<Diagnostic> diagnostics();

    default boolean isSuccess() {
        return this instanceof Success<T>;
    }

    default boolean isFailure() {
        return this instanceof Failure<T>;
    }

    default Optional<T> optionalValue() {
        if (this instanceof Success<T> success) {
            return Optional.of(success.value());
        }
        return Optional.empty();
    }

    static <T extends ImmutablePhaseArtifact> Success<T> success(T value) {
        return new Success<>(value, List.of());
    }

    static <T extends ImmutablePhaseArtifact> Success<T> success(T value, List<Diagnostic> diagnostics) {
        return new Success<>(value, diagnostics);
    }

    static <T extends ImmutablePhaseArtifact> Failure<T> failure(Diagnostic diagnostic) {
        return new Failure<>(List.of(diagnostic));
    }

    static <T extends ImmutablePhaseArtifact> Failure<T> failure(List<Diagnostic> diagnostics) {
        return new Failure<>(diagnostics);
    }

    record Success<T extends ImmutablePhaseArtifact>(T value, List<Diagnostic> diagnostics) implements PhaseResult<T> {
        public Success {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(diagnostics, "diagnostics");
            diagnostics = List.copyOf(diagnostics);
            if (diagnostics.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("diagnostics must not contain null");
            }
            if (diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity().isError())) {
                throw new IllegalArgumentException("successful phase result cannot contain an error");
            }
        }
    }

    record Failure<T extends ImmutablePhaseArtifact>(List<Diagnostic> diagnostics) implements PhaseResult<T> {
        public Failure {
            Objects.requireNonNull(diagnostics, "diagnostics");
            diagnostics = List.copyOf(diagnostics);
            if (diagnostics.isEmpty()) {
                throw new IllegalArgumentException("failed phase result needs a diagnostic");
            }
            if (diagnostics.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("diagnostics must not contain null");
            }
            if (diagnostics.stream().noneMatch(diagnostic -> diagnostic.severity().isError())) {
                throw new IllegalArgumentException("failed phase result needs an error diagnostic");
            }
        }
    }
}
