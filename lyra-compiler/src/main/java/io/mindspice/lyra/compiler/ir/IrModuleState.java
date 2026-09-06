package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ExportId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.source.ModuleId;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Static module-state layout: bindings, imports, function slots, and exports. */
public record IrModuleState(
        ModuleId moduleId,
        ScopeId rootScope,
        List<DeclarationId> declarations,
        List<ReferenceId> references,
        List<LambdaId> lambdas,
        List<DeclarationId> imports,
        List<DeclarationId> functionSlots,
        List<DeclarationId> eagerDeclarations,
        List<ExportId> exports,
        List<DeclarationId> exportedDeclarations)
        implements ImmutablePhaseArtifact {
    public IrModuleState {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(rootScope, "rootScope");
        declarations = copy(declarations, "declarations");
        references = copy(references, "references");
        lambdas = copy(lambdas, "lambdas");
        imports = copy(imports, "imports");
        functionSlots = copy(functionSlots, "functionSlots");
        eagerDeclarations = copy(eagerDeclarations, "eagerDeclarations");
        exports = copy(exports, "exports");
        exportedDeclarations = copy(exportedDeclarations, "exportedDeclarations");
        if (!new java.util.LinkedHashSet<>(declarations).containsAll(imports)
                || !new java.util.LinkedHashSet<>(declarations).containsAll(functionSlots)
                || !new java.util.LinkedHashSet<>(declarations).containsAll(eagerDeclarations)
                || !new java.util.LinkedHashSet<>(declarations).containsAll(exportedDeclarations)) {
            throw new IllegalArgumentException("module state references an absent declaration");
        }
    }

    /** Compatibility constructor for the first module-state shape. */
    public IrModuleState(
            ModuleId moduleId,
            ScopeId rootScope,
            List<DeclarationId> declarations,
            List<ReferenceId> references,
            List<LambdaId> lambdas,
            List<DeclarationId> imports,
            List<DeclarationId> functionSlots,
            List<DeclarationId> eagerDeclarations,
            List<ExportId> exports) {
        this(moduleId, rootScope, declarations, references, lambdas, imports,
                functionSlots, eagerDeclarations, exports, List.of());
    }

    /** Compatibility constructor for the original eight-field module state. */
    public IrModuleState(
            ModuleId moduleId,
            ScopeId rootScope,
            List<DeclarationId> declarations,
            List<ReferenceId> references,
            List<LambdaId> lambdas,
            List<DeclarationId> functionSlots,
            List<DeclarationId> eagerDeclarations,
            List<ExportId> exports) {
        this(moduleId, rootScope, declarations, references, lambdas, List.of(),
                functionSlots, eagerDeclarations, exports, List.of());
    }

    public static IrModuleState empty(ModuleId moduleId, ScopeId rootScope) {
        return new IrModuleState(moduleId, rootScope, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
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

    public List<DeclarationId> importBindings() {
        return imports;
    }

    public List<DeclarationId> exportDeclarations() {
        return exportedDeclarations;
    }

    private static <T> List<T> copy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<T> copy = new ArrayList<>();
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " must not contain null"));
        }
        if (new java.util.LinkedHashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException(name + " must not contain duplicates");
        }
        return List.copyOf(copy);
    }
}
