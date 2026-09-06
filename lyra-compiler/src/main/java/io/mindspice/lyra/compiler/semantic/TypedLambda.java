package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.LyraSignature;

import java.util.List;
import java.util.Objects;

/** A lambda whose complete signature and body have both been checked. */
public record TypedLambda(
        LambdaId id,
        ModuleId moduleId,
        SourceSpan span,
        ScopeId scopeId,
        LyraSignature signature,
        List<DeclarationId> parameterIds,
        List<CaptureId> captures,
        TypedExpression body) implements ImmutablePhaseArtifact {
    public TypedLambda {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(signature, "signature");
        parameterIds = copy(parameterIds, "parameterIds");
        captures = copy(captures, "captures");
        Objects.requireNonNull(body, "body");
        if (!moduleId.sourceId().equals(span.sourceId())
                || !moduleId.sourceId().equals(body.span().sourceId())) {
            throw new IllegalArgumentException("typed lambda spans belong to another module");
        }
        if (parameterIds.size() != signature.arity()) {
            throw new IllegalArgumentException("typed lambda parameter count does not match its signature");
        }
    }

    public LambdaId lambdaId() {
        return id;
    }

    public LyraSignature functionSignature() {
        return signature;
    }

    public List<DeclarationId> parameters() {
        return parameterIds;
    }

    public List<CaptureId> captureIds() {
        return captures;
    }

    private static <T> List<T> copy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        for (T value : values) {
            Objects.requireNonNull(value, name + " must not contain null");
        }
        return List.copyOf(values);
    }
}
