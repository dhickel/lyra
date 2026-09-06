package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ExportId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.Objects;
import java.util.Optional;

/** One resolved source reference with its exact compilation-local identity. */
public record ResolvedReference(
        ReferenceId id,
        String name,
        SourceSpan span,
        ModuleId moduleId,
        ScopeId scopeId,
        ReferenceKind kind,
        Optional<DeclarationId> targetDeclaration,
        Optional<ModuleId> targetModule,
        Optional<ExportId> targetExport,
        Optional<LambdaId> fromLambda,
        Optional<CaptureId> capture) {
    public ResolvedReference {
        Objects.requireNonNull(id, "id");
        requireText(name, "name");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(targetDeclaration, "targetDeclaration");
        Objects.requireNonNull(targetModule, "targetModule");
        Objects.requireNonNull(targetExport, "targetExport");
        Objects.requireNonNull(fromLambda, "fromLambda");
        Objects.requireNonNull(capture, "capture");
        if (!moduleId.sourceId().equals(span.sourceId())) {
            throw new IllegalArgumentException("reference span belongs to another module");
        }
        if (kind == ReferenceKind.MODULE_NAMESPACE && targetModule.isEmpty()) {
            throw new IllegalArgumentException("module namespace references need a module target");
        }
        if (kind != ReferenceKind.MODULE_NAMESPACE
                && kind != ReferenceKind.NAMESPACE_MEMBER
                && kind != ReferenceKind.NAMESPACE_DIRECT_CALL
                && targetDeclaration.isEmpty()
                && targetExport.isEmpty()) {
            throw new IllegalArgumentException("value references need a declaration or export target");
        }
    }

    public ReferenceId referenceId() {
        return id;
    }

    public DeclarationId declaration() {
        return targetDeclaration.orElseThrow(() -> new IllegalStateException(
                "reference does not target a declaration"));
    }

    public boolean isCaptured() {
        return capture.isPresent();
    }

    public boolean isModuleNamespace() {
        return kind == ReferenceKind.MODULE_NAMESPACE;
    }

    public boolean isDirectCallTarget() {
        return kind == ReferenceKind.DIRECT_CALL_TARGET
                || kind == ReferenceKind.NAMESPACE_DIRECT_CALL;
    }

    public boolean isNamespaceAccess() {
        return kind == ReferenceKind.MODULE_NAMESPACE
                || kind == ReferenceKind.NAMESPACE_MEMBER
                || kind == ReferenceKind.NAMESPACE_DIRECT_CALL;
    }

    public Optional<CaptureId> captureId() {
        return capture;
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
    }
}
