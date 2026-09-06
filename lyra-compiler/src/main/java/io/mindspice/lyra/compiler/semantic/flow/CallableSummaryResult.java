package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Explicit result for the internal callable-summary phase.
 *
 * <p>Failures are semantic-artifact failures, not new source-language
 * diagnostics.  When source evidence exists they reuse the established
 * internal graph diagnostic code; callers still receive the structured reason
 * and failure kind rather than an effect-free fallback.</p>
 */
public sealed interface CallableSummaryResult
        permits CallableSummaryResult.Success, CallableSummaryResult.Failure {
    List<Diagnostic> diagnostics();

    Optional<CallableSummarySet> optionalValue();

    default boolean isSuccess() {
        return this instanceof Success;
    }

    default boolean isFailure() {
        return this instanceof Failure;
    }

    record Success(CallableSummarySet value) implements CallableSummaryResult {
        public Success {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public List<Diagnostic> diagnostics() {
            return List.of();
        }

        @Override
        public Optional<CallableSummarySet> optionalValue() {
            return Optional.of(value);
        }
    }

    record Failure(InternalFailure failure) implements CallableSummaryResult {
        public Failure {
            Objects.requireNonNull(failure, "failure");
        }

        @Override
        public List<Diagnostic> diagnostics() {
            return failure.diagnostic().map(List::of).orElseGet(List::of);
        }

        @Override
        public Optional<CallableSummarySet> optionalValue() {
            return Optional.empty();
        }
    }

    /** A non-source diagnostic reason for a missing or invalid internal fact. */
    record InternalFailure(
            Kind kind,
            String message,
            Optional<SourceSpan> span,
            Optional<Diagnostic> diagnostic) {
        public enum Kind {
            MISSING_CALLABLE_FACT,
            INVALID_TYPED_EXPRESSION,
            DOMAIN_LIMIT,
            INCONSISTENT_SUMMARY,
            NON_CONVERGENT
        }

        public InternalFailure {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(message, "message");
            if (message.isBlank()) {
                throw new IllegalArgumentException("internal failure message must not be blank");
            }
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(diagnostic, "diagnostic");
            diagnostic.ifPresent(value -> {
                if (!value.severity().isError()) {
                    throw new IllegalArgumentException("internal failure diagnostic must be an error");
                }
                if (span.isPresent() && !span.orElseThrow().equals(value.primarySpan())) {
                    throw new IllegalArgumentException("internal failure span and diagnostic disagree");
                }
            });
        }

        public static InternalFailure of(
                Kind kind, String message, Optional<SourceSpan> span) {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(span, "span");
            Optional<Diagnostic> diagnostic = span.map(value -> Diagnostic.error(
                    CompilerDiagnosticCodes.IR_INVALID_GRAPH,
                    value,
                    "internal callable summary failure: " + message));
            return new InternalFailure(kind, message, span, diagnostic);
        }

        public static InternalFailure at(
                Kind kind, String message, SourceSpan span) {
            return of(kind, message, Optional.of(Objects.requireNonNull(span, "span")));
        }
    }

    static CallableSummaryResult failure(
            InternalFailure.Kind kind, String message, Optional<SourceSpan> span) {
        return new Failure(InternalFailure.of(kind, message, span));
    }
}
