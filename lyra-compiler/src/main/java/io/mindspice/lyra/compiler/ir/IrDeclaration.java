package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.types.BindingMutability;
import io.mindspice.lyra.compiler.semantic.DeclarationKind;
import io.mindspice.lyra.compiler.semantic.DeclarationVisibility;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.BindingContract;

import java.util.Objects;
import java.util.Optional;

/** Immutable declaration table entry retained by the closed IR. */
public record IrDeclaration(
        DeclarationId id,
        String name,
        SourceSpan nameSpan,
        SourceSpan span,
        ModuleId moduleId,
        ScopeId scopeId,
        DeclarationKind kind,
        DeclarationVisibility visibility,
        BindingMutability bindingMutability,
        Optional<BindingContract> contract,
        Optional<LambdaId> initializerLambda,
        Optional<FlowSiteId> initializerSite,
        boolean signaturePredeclared,
        boolean imported,
        boolean reExported,
        Optional<ModuleId> importedModule,
        Optional<String> importedName,
        Optional<DeclarationId> originDeclaration,
        Optional<io.mindspice.lyra.compiler.identity.ExportId> originExport,
        Optional<DeclarationId> replacementOf,
        Optional<io.mindspice.lyra.compiler.session.ExternalBinding> externalBinding)
        implements ImmutablePhaseArtifact {
    public IrDeclaration {
        Objects.requireNonNull(id, "id");
        requireText(name, "name");
        Objects.requireNonNull(nameSpan, "nameSpan");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(bindingMutability, "bindingMutability");
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(initializerLambda, "initializerLambda");
        Objects.requireNonNull(initializerSite, "initializerSite");
        Objects.requireNonNull(importedModule, "importedModule");
        Objects.requireNonNull(importedName, "importedName");
        Objects.requireNonNull(originDeclaration, "originDeclaration");
        Objects.requireNonNull(originExport, "originExport");
        Objects.requireNonNull(replacementOf, "replacementOf");
        Objects.requireNonNull(externalBinding, "externalBinding");
        if ((kind == DeclarationKind.EXTERNAL) != externalBinding.isPresent()) {
            throw new IllegalArgumentException("external IR declaration lost its typed storage identity");
        }
    }

    public DeclarationId declarationId() {
        return id;
    }

    public boolean isMutable() {
        return bindingMutability.isMutable();
    }

    public boolean isFunction() {
        return contract.filter(value -> value.valueType().withoutQualifiers()
                instanceof io.mindspice.lyra.compiler.types.FunctionType).isPresent();
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
    }
}
