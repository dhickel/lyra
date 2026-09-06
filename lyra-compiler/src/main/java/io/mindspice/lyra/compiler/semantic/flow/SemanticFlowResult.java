package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.diagnostic.Diagnostic;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Explicit result for canonical semantic-flow analysis. */
public sealed interface SemanticFlowResult
        permits SemanticFlowResult.Success, SemanticFlowResult.Failure {
    List<Diagnostic> diagnostics();

    Optional<SemanticFlowFacts> optionalValue();

    default boolean isSuccess() {
        return this instanceof Success;
    }

    default boolean isFailure() {
        return this instanceof Failure;
    }

    record Success(SemanticFlowFacts value) implements SemanticFlowResult {
        public Success {
            Objects.requireNonNull(value, "value");
        }

        public SemanticFlowFacts facts() {
            return value;
        }

        @Override
        public List<Diagnostic> diagnostics() {
            return List.of();
        }

        @Override
        public Optional<SemanticFlowFacts> optionalValue() {
            return Optional.of(value);
        }
    }

    record Failure(CallableSummaryResult.InternalFailure failure)
            implements SemanticFlowResult {
        public Failure {
            Objects.requireNonNull(failure, "failure");
        }

        @Override
        public List<Diagnostic> diagnostics() {
            return failure.diagnostic().map(List::of).orElseGet(List::of);
        }

        @Override
        public Optional<SemanticFlowFacts> optionalValue() {
            return Optional.empty();
        }
    }
}
