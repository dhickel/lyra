package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Frozen semantic index for one reachable module. */
public record ResolvedModule(
        ModuleId moduleId,
        Optional<LogicalModuleId> logicalModule,
        ScopeId rootScope,
        List<DeclarationId> declarations,
        List<ReferenceId> references,
        List<LambdaId> lambdas,
        List<ResolvedImportBinding> imports,
        List<ResolvedExport> exports) implements ImmutablePhaseArtifact {
    public ResolvedModule {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(logicalModule, "logicalModule");
        Objects.requireNonNull(rootScope, "rootScope");
        declarations = copy(declarations, "declarations");
        references = copy(references, "references");
        lambdas = copy(lambdas, "lambdas");
        imports = copy(imports, "imports");
        exports = copy(exports, "exports");
    }

    public ScopeId scopeId() {
        return rootScope;
    }

    public List<DeclarationId> declarationIds() {
        return declarations;
    }

    public List<ReferenceId> referenceIds() {
        return references;
    }

    public List<LambdaId> lambdaIds() {
        return lambdas;
    }

    public Optional<ResolvedExport> export(String name) {
        Objects.requireNonNull(name, "name");
        return exports.stream().filter(export -> export.name().equals(name)).findFirst();
    }

    private static <T> List<T> copy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        for (T value : values) {
            Objects.requireNonNull(value, name + " must not contain null");
        }
        return List.copyOf(values);
    }
}
