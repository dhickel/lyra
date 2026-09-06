package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ExportId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.BindingContract;
import io.mindspice.lyra.compiler.types.BindingMutability;
import io.mindspice.lyra.compiler.types.LyraSignature;

import java.util.Objects;
import java.util.Optional;

/**
 * A fully identified name in the single Lyra value/module namespace.
 *
 * <p>Type slots are optional because declaration collection intentionally does
 * not perform expression inference.  A slot is present when syntax or a
 * complete lambda contract supplied it; phase 9 may fill the remaining local
 * slots without changing this declaration identity.</p>
 */
public record ResolvedDeclaration(
        DeclarationId id,
        String name,
        SourceSpan nameSpan,
        SourceSpan span,
        ModuleId moduleId,
        ScopeId scopeId,
        DeclarationKind kind,
        DeclarationVisibility visibility,
        BindingMutability bindingMutability,
        Optional<BindingContract> declaredContract,
        Optional<BindingContract> inferredContract,
        Optional<BindingContract> effectiveContract,
        Optional<LyraSignature> functionSignature,
        Optional<LambdaId> initializerLambda,
        boolean signaturePredeclared,
        boolean imported,
        boolean reExported,
        Optional<ModuleId> importedModule,
        Optional<String> importedName,
        Optional<DeclarationId> originDeclaration,
        Optional<ExportId> originExport,
        Optional<DeclarationId> replacementOf,
        Optional<io.mindspice.lyra.compiler.session.ExternalBinding> externalBinding) {
    public ResolvedDeclaration {
        Objects.requireNonNull(id, "id");
        requireText(name, "name");
        Objects.requireNonNull(nameSpan, "nameSpan");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(bindingMutability, "bindingMutability");
        Objects.requireNonNull(declaredContract, "declaredContract");
        Objects.requireNonNull(inferredContract, "inferredContract");
        Objects.requireNonNull(effectiveContract, "effectiveContract");
        Objects.requireNonNull(functionSignature, "functionSignature");
        Objects.requireNonNull(initializerLambda, "initializerLambda");
        Objects.requireNonNull(importedModule, "importedModule");
        Objects.requireNonNull(importedName, "importedName");
        Objects.requireNonNull(originDeclaration, "originDeclaration");
        Objects.requireNonNull(originExport, "originExport");
        Objects.requireNonNull(replacementOf, "replacementOf");
        Objects.requireNonNull(externalBinding, "externalBinding");
        if ((kind == DeclarationKind.EXTERNAL) != externalBinding.isPresent()) {
            throw new IllegalArgumentException("external declaration requires exact storage linkage metadata");
        }
        externalBinding.ifPresent(binding -> {
            if (!binding.declarationId().equals(id) || !binding.name().equals(name)
                    || !effectiveContract.equals(Optional.of(binding.contract()))
                    || bindingMutability != binding.mutability() || imported || signaturePredeclared) {
                throw new IllegalArgumentException("external declaration contract differs from its binding");
            }
        });
        if (!moduleId.sourceId().equals(nameSpan.sourceId())
                || !moduleId.sourceId().equals(span.sourceId())) {
            throw new IllegalArgumentException("declaration spans belong to another module");
        }
        if (kind == DeclarationKind.IMPORT_MODULE && importedName.isPresent()) {
            throw new IllegalArgumentException("module imports do not have selected names");
        }
        if (kind == DeclarationKind.IMPORT_VALUE && importedName.isEmpty()) {
            throw new IllegalArgumentException("selective imports need selected names");
        }
        if (!imported && (importedModule.isPresent() || importedName.isPresent()
                || originDeclaration.isPresent() || originExport.isPresent())) {
            throw new IllegalArgumentException("non-import declarations cannot carry import linkage");
        }
        if (reExported && !imported) {
            throw new IllegalArgumentException("only an import binding can be a re-export");
        }
        if (reExported && visibility != DeclarationVisibility.PUBLIC) {
            throw new IllegalArgumentException("a re-export must be public");
        }
    }

    public ResolvedDeclaration(DeclarationId id, String name, SourceSpan nameSpan, SourceSpan span,
            ModuleId moduleId, ScopeId scopeId, DeclarationKind kind, DeclarationVisibility visibility,
            BindingMutability bindingMutability, Optional<BindingContract> declaredContract,
            Optional<BindingContract> inferredContract, Optional<BindingContract> effectiveContract,
            Optional<LyraSignature> functionSignature, Optional<LambdaId> initializerLambda,
            boolean signaturePredeclared, boolean imported, boolean reExported,
            Optional<ModuleId> importedModule, Optional<String> importedName,
            Optional<DeclarationId> originDeclaration, Optional<ExportId> originExport,
            Optional<DeclarationId> replacementOf) {
        this(id, name, nameSpan, span, moduleId, scopeId, kind, visibility, bindingMutability,
                declaredContract, inferredContract, effectiveContract, functionSignature, initializerLambda,
                signaturePredeclared, imported, reExported, importedModule, importedName, originDeclaration,
                originExport, replacementOf, Optional.empty());
    }

    public DeclarationId declarationId() {
        return id;
    }

    public String identifier() {
        return name;
    }

    public boolean isPublic() {
        return visibility == DeclarationVisibility.PUBLIC;
    }

    public boolean isMutable() {
        return bindingMutability.isMutable();
    }

    public boolean isImported() {
        return imported;
    }

    public boolean isReExport() {
        return reExported;
    }

    public Optional<BindingContract> contract() {
        return effectiveContract;
    }

    public Optional<BindingContract> typeContract() {
        return effectiveContract;
    }

    public Optional<BindingContract> declaredType() {
        return declaredContract;
    }

    public Optional<BindingContract> inferredType() {
        return inferredContract;
    }

    public Optional<BindingContract> effectiveType() {
        return effectiveContract;
    }

    public Optional<ModuleId> importedFrom() {
        return importedModule;
    }

    public Optional<DeclarationId> replacedDeclaration() {
        return replacementOf;
    }

    public Optional<LyraSignature> signature() {
        return functionSignature;
    }

    public boolean isFunction() {
        return functionSignature.isPresent();
    }

    public boolean isSignaturePredeclared() {
        return signaturePredeclared;
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
    }
}
