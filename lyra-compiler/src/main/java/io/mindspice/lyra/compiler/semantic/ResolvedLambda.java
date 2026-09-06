package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.LyraSignature;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable semantic identity and extracted signature of one lambda expression. */
public record ResolvedLambda(
        LambdaId id,
        ModuleId moduleId,
        SourceSpan span,
        ScopeId scopeId,
        SourceSpan bodySpan,
        Optional<LyraSignature> signature,
        List<DeclarationId> parameterIds,
        Optional<DeclarationId> ownerDeclaration,
        List<CaptureId> captures,
        boolean signatureComplete) {
    public ResolvedLambda {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(bodySpan, "bodySpan");
        Objects.requireNonNull(signature, "signature");
        parameterIds = copy(parameterIds, "parameterIds");
        Objects.requireNonNull(ownerDeclaration, "ownerDeclaration");
        captures = copy(captures, "captures");
        if (!moduleId.sourceId().equals(span.sourceId())
                || !moduleId.sourceId().equals(bodySpan.sourceId())) {
            throw new IllegalArgumentException("lambda spans belong to another module");
        }
        if (signatureComplete != signature.isPresent()) {
            throw new IllegalArgumentException("lambda signature state is inconsistent");
        }
    }

    public LambdaId lambdaId() {
        return id;
    }

    public Optional<LyraSignature> functionSignature() {
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
