package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.diagnostic.Diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A terminal, immutable outcome for one request. The result variants make
 * status-specific data combinations explicit and carry compiler diagnostics
 * without introducing a second diagnostic model.
 */
public sealed interface EvaluationResult
        permits EvaluationResult.Success,
        EvaluationResult.CompilationFailure,
        EvaluationResult.RuntimeFailure,
        EvaluationResult.Cancelled,
        EvaluationResult.Busy,
        EvaluationResult.Closed {
    EvaluationRequest request();

    SessionRevision revision();

    EvaluationStatus status();

    default EvaluationId evaluationId() {
        return request().evaluationId();
    }

    default List<Diagnostic> diagnostics() {
        return List.of();
    }

    default Optional<ValueSnapshot> value() {
        return Optional.empty();
    }

    default Optional<Cancellation> cancellation() {
        return Optional.empty();
    }

    default Optional<String> failureSummary() {
        return Optional.empty();
    }

    /** Exact scheduled/attempted/completed initializer progress, if any. */
    default InitializerProgress initializerProgress() {
        return InitializerProgress.empty();
    }

    default boolean isTerminal() {
        return status().isTerminal();
    }

    record Success(
            EvaluationRequest request,
            SessionRevision revision,
            Optional<ValueSnapshot> value,
            List<Diagnostic> diagnostics,
            InitializerProgress initializerProgress) implements EvaluationResult {
        public Success {
            validateHeader(request, revision);
            value = Objects.requireNonNull(value, "value");
            diagnostics = copyDiagnostics(diagnostics);
            if (diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity().isError())) {
                throw new IllegalArgumentException(
                        "successful evaluation cannot contain an error diagnostic");
            }
            initializerProgress = Objects.requireNonNull(initializerProgress, "initializerProgress");
        }

        public Success(
                EvaluationRequest request,
                SessionRevision revision,
                Optional<ValueSnapshot> value,
                List<Diagnostic> diagnostics) {
            this(request, revision, value, diagnostics, InitializerProgress.empty());
        }

        @Override
        public InitializerProgress initializerProgress() {
            return initializerProgress;
        }

        @Override
        public EvaluationStatus status() {
            return EvaluationStatus.SUCCESS;
        }
    }

    record CompilationFailure(
            EvaluationRequest request,
            SessionRevision revision,
            List<Diagnostic> diagnostics,
            InitializerProgress initializerProgress) implements EvaluationResult {
        public CompilationFailure {
            validateHeader(request, revision);
            diagnostics = copyDiagnostics(diagnostics);
            if (diagnostics.isEmpty()
                    || diagnostics.stream().noneMatch(diagnostic -> diagnostic.severity().isError())) {
                throw new IllegalArgumentException(
                        "compilation failure needs an error diagnostic");
            }
            initializerProgress = Objects.requireNonNull(initializerProgress, "initializerProgress");
        }

        public CompilationFailure(EvaluationRequest request, SessionRevision revision,
                                  List<Diagnostic> diagnostics) {
            this(request, revision, diagnostics, InitializerProgress.empty());
        }

        @Override
        public EvaluationStatus status() {
            return EvaluationStatus.COMPILATION_FAILURE;
        }
    }

    record RuntimeFailure(
            EvaluationRequest request,
            SessionRevision revision,
            String summary,
            List<Diagnostic> diagnostics,
            String code,
            List<RuntimeFrame> frames,
            InitializerProgress initializerProgress) implements EvaluationResult {
        public RuntimeFailure {
            validateHeader(request, revision);
            summary = requireSummary(summary);
            diagnostics = copyDiagnostics(diagnostics);
            io.mindspice.lyra.runtime.LyraFailureCategory.fromCode(Objects.requireNonNull(code, "code"));
            frames = List.copyOf(Objects.requireNonNull(frames, "frames"));
            initializerProgress = Objects.requireNonNull(initializerProgress, "initializerProgress");
        }

        public RuntimeFailure(EvaluationRequest request, SessionRevision revision,
                              String summary, List<Diagnostic> diagnostics,
                              String code, List<RuntimeFrame> frames) {
            this(request, revision, summary, diagnostics, code, frames, InitializerProgress.empty());
        }

        public RuntimeFailure(EvaluationRequest request, SessionRevision revision,
                              String summary, List<Diagnostic> diagnostics) {
            this(request, revision, summary, diagnostics, "LYR-INTERNAL", List.of(),
                    InitializerProgress.empty());
        }

        @Override
        public InitializerProgress initializerProgress() {
            return initializerProgress;
        }

        @Override
        public EvaluationStatus status() {
            return EvaluationStatus.RUNTIME_FAILURE;
        }

        @Override
        public Optional<String> failureSummary() {
            return Optional.of(summary);
        }
    }

    record Cancelled(
            EvaluationRequest request,
            SessionRevision revision,
            Cancellation notice,
            InitializerProgress initializerProgress) implements EvaluationResult {
        public Cancelled {
            validateHeader(request, revision);
            notice = Objects.requireNonNull(notice, "notice");
            if (!notice.evaluationId().equals(request.evaluationId())) {
                throw new IllegalArgumentException(
                        "cancellation identity does not match evaluation request");
            }
            if (!notice.state().isObserved()) {
                throw new IllegalArgumentException(
                        "a terminal cancelled result needs an observed cancellation");
            }
            initializerProgress = Objects.requireNonNull(initializerProgress, "initializerProgress");
        }

        public Cancelled(
                EvaluationRequest request,
                SessionRevision revision,
                Cancellation notice) {
            this(request, revision, notice, InitializerProgress.empty());
        }

        @Override
        public InitializerProgress initializerProgress() {
            return initializerProgress;
        }

        @Override
        public EvaluationStatus status() {
            return EvaluationStatus.CANCELLED;
        }

        @Override
        public Optional<Cancellation> cancellation() {
            return Optional.of(notice);
        }
    }

    record Busy(
            EvaluationRequest request,
            SessionRevision revision,
            Optional<EvaluationId> activeEvaluationId) implements EvaluationResult {
        public Busy {
            validateHeader(request, revision);
            activeEvaluationId = Objects.requireNonNull(activeEvaluationId, "activeEvaluationId");
            if (activeEvaluationId.isPresent()
                    && activeEvaluationId.get().equals(request.evaluationId())) {
                throw new IllegalArgumentException(
                        "busy result cannot identify its own request as active");
            }
        }

        @Override
        public EvaluationStatus status() {
            return EvaluationStatus.BUSY;
        }
    }

    record Closed(
            EvaluationRequest request,
            SessionRevision revision,
            SessionLifecycleState lifecycleState) implements EvaluationResult {
        public Closed {
            validateHeader(request, revision);
            lifecycleState = Objects.requireNonNull(lifecycleState, "lifecycleState");
            if (lifecycleState != SessionLifecycleState.CLOSED) {
                throw new IllegalArgumentException(
                        "a closed result must carry the CLOSED session state");
            }
        }

        @Override
        public EvaluationStatus status() {
            return EvaluationStatus.CLOSED;
        }
    }

    private static void validateHeader(EvaluationRequest request, SessionRevision revision) {
        request = Objects.requireNonNull(request, "request");
        revision = Objects.requireNonNull(revision, "revision");
        if (revision.value() < request.revision().value()) {
            throw new IllegalArgumentException(
                    "result revision cannot precede the request revision");
        }
    }

    private static List<Diagnostic> copyDiagnostics(List<Diagnostic> values) {
        Objects.requireNonNull(values, "diagnostics");
        ArrayList<Diagnostic> copy = new ArrayList<>(values.size());
        for (Diagnostic value : values) {
            copy.add(Objects.requireNonNull(value, "diagnostics must not contain null"));
        }
        return List.copyOf(copy);
    }

    private static String requireSummary(String value) {
        Objects.requireNonNull(value, "summary");
        if (value.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException("summary must not contain control characters");
            }
        }
        return value;
    }
}
