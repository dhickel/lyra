package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.LyraSignature;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Complete lambda linkage and body entry retained by the closed IR. */
public record IrLambda(
        LambdaId id,
        ModuleId moduleId,
        SourceSpan span,
        SourceSpan bodySpan,
        ScopeId scopeId,
        LyraSignature signature,
        List<DeclarationId> parameterIds,
        Optional<DeclarationId> ownerDeclaration,
        List<CaptureId> captures,
        IrNode body,
        FlowSiteId siteId)
        implements ImmutablePhaseArtifact {
    public IrLambda {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(bodySpan, "bodySpan");
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(signature, "signature");
        parameterIds = copy(parameterIds, "parameterIds");
        Objects.requireNonNull(ownerDeclaration, "ownerDeclaration");
        captures = copy(captures, "captures");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(siteId, "siteId");
        if (parameterIds.size() != signature.arity()) {
            throw new IllegalArgumentException("lambda parameter count does not match its signature");
        }
    }

    public LambdaId lambdaId() {
        return id;
    }

    public List<DeclarationId> parameters() {
        return parameterIds;
    }

    public List<CaptureId> captureIds() {
        return captures;
    }

    private static <T> List<T> copy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " must not contain null"));
        }
        return List.copyOf(copy);
    }
}
