package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ExportId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.semantic.ReferenceKind;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.LyraType;

import java.util.Objects;
import java.util.Optional;

/** Immutable resolved-reference table entry retained by the closed IR. */
public record IrReference(
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
        Optional<CaptureId> capture,
        FlowSiteId siteId)
        implements ImmutablePhaseArtifact {
    public IrReference {
        Objects.requireNonNull(id, "id");
        if (Objects.requireNonNull(name, "name").isEmpty()) {
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
        Objects.requireNonNull(siteId, "siteId");
    }

    public ReferenceId referenceId() {
        return id;
    }
}
