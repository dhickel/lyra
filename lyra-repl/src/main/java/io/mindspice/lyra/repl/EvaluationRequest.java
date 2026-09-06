package io.mindspice.lyra.repl;

import java.util.Objects;

/** Immutable source submission identity and the namespace revision it targets. */
public record EvaluationRequest(
        EvaluationId evaluationId,
        SessionRevision revision,
        EvaluationSource source) {
    public EvaluationRequest {
        evaluationId = Objects.requireNonNull(evaluationId, "evaluationId");
        revision = Objects.requireNonNull(revision, "revision");
        source = Objects.requireNonNull(source, "source");
    }

    public EvaluationRequest(EvaluationSource source) {
        this(EvaluationId.create(), SessionRevision.initial(), source);
    }

    public EvaluationId identity() {
        return evaluationId;
    }

    /** Alias identifying the committed namespace against which this request is admitted. */
    public SessionRevision baseRevision() {
        return revision;
    }

    public String sourceText() {
        return source.text();
    }
}
