package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ExportId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.LyraType;

import java.util.Objects;
import java.util.Optional;

/** A resolved reference after assigning its exact value type. */
public record TypedReference(
        ReferenceId id,
        String name,
        SourceSpan span,
        ModuleId moduleId,
        ScopeId scopeId,
        ReferenceKind kind,
        Optional<LyraType> type,
        Optional<DeclarationId> targetDeclaration,
        Optional<ModuleId> targetModule,
        Optional<ExportId> targetExport,
        Optional<LambdaId> fromLambda,
        Optional<CaptureId> capture) implements ImmutablePhaseArtifact {
    public TypedReference {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("reference name must not be empty");
        }
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(targetDeclaration, "targetDeclaration");
        Objects.requireNonNull(targetModule, "targetModule");
        Objects.requireNonNull(targetExport, "targetExport");
        Objects.requireNonNull(fromLambda, "fromLambda");
        Objects.requireNonNull(capture, "capture");
        if (!moduleId.sourceId().equals(span.sourceId())) {
            throw new IllegalArgumentException("typed reference span belongs to another module");
        }
        if (kind != ReferenceKind.MODULE_NAMESPACE && type.isEmpty()) {
            throw new IllegalArgumentException("value references require an exact value type");
        }
        if (kind == ReferenceKind.MODULE_NAMESPACE && targetModule.isEmpty()) {
            throw new IllegalArgumentException("module namespace references require a module target");
        }
    }

    public ReferenceId referenceId() {
        return id;
    }

    public LyraType valueType() {
        return type.orElseThrow(() -> new IllegalStateException(
                "a module namespace reference has no value type"));
    }
}
