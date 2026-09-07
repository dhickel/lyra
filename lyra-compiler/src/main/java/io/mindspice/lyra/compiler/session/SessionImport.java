package io.mindspice.lyra.compiler.session;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ExportId;
import io.mindspice.lyra.compiler.semantic.ImportBindingKind;
import io.mindspice.lyra.compiler.semantic.ResolvedImportBinding;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.ModuleRevision;

import java.util.Objects;
import java.util.Optional;

/**
 * One previously committed import binding.
 *
 * <p>The four-argument constructor is retained for the original namespace
 * alias API.  The richer shape also preserves selected-import and re-export
 * provenance, so a later compiler operation can resolve a committed alias
 * without reconstructing or replaying the original header.</p>
 */
public record SessionImport(
        String name,
        LogicalModuleId logicalModule,
        ModuleId moduleId,
        String revision,
        ImportBindingKind kind,
        Optional<String> importedName,
        Optional<String> aliasName,
        Optional<DeclarationId> targetDeclaration,
        Optional<ExportId> targetExport,
        boolean reExport,
        Optional<SessionModuleContract> moduleContract) {
    public SessionImport {
        name = token(name, "name");
        logicalModule = Objects.requireNonNull(logicalModule, "logicalModule");
        moduleId = Objects.requireNonNull(moduleId, "moduleId");
        revision = Objects.requireNonNull(revision, "revision");
        if (!ModuleRevision.isRevision(revision)) {
            throw new IllegalArgumentException("module revision must be a SHA-256 hexadecimal value");
        }
        kind = Objects.requireNonNull(kind, "kind");
        importedName = copyName(importedName, "importedName");
        aliasName = copyName(aliasName, "aliasName");
        targetDeclaration = Objects.requireNonNull(targetDeclaration, "targetDeclaration");
        targetExport = Objects.requireNonNull(targetExport, "targetExport");
        if (kind == ImportBindingKind.MODULE_NAMESPACE && importedName.isPresent()) {
            throw new IllegalArgumentException("a namespace import has no selected export name");
        }
        if (kind == ImportBindingKind.SELECTIVE_VALUE && importedName.isEmpty()) {
            throw new IllegalArgumentException("a selected import needs an imported export name");
        }
        if (reExport && kind != ImportBindingKind.SELECTIVE_VALUE) {
            throw new IllegalArgumentException("only selected imports can be re-exported");
        }
        moduleContract = Objects.requireNonNull(moduleContract, "moduleContract");
        if (moduleContract.isPresent()) {
            var contract = moduleContract.orElseThrow();
            if (!contract.producer().logicalModule().equals(logicalModule)
                    || !contract.producer().moduleId().equals(moduleId)
                    || !contract.producer().revision().equals(revision)) {
                throw new IllegalArgumentException("import module contract does not match its producer");
            }
            if (kind == ImportBindingKind.SELECTIVE_VALUE) {
                String selected = importedName.orElseThrow();
                var export = contract.exports().stream().filter(value -> value.export().name().equals(selected))
                        .findFirst().orElseThrow().export();
                if (!targetDeclaration.equals(Optional.of(export.originDeclaration()))
                        || !targetExport.equals(export.exportId())) {
                    throw new IllegalArgumentException("selected import lost its exact export target");
                }
            }
        }
    }

    public SessionImport(String name, LogicalModuleId logicalModule, ModuleId moduleId, String revision,
            ImportBindingKind kind, Optional<String> importedName, Optional<DeclarationId> targetDeclaration,
            Optional<ExportId> targetExport, boolean reExport) {
        this(name, logicalModule, moduleId, revision, kind, importedName, Optional.empty(), targetDeclaration,
                targetExport, reExport, Optional.empty());
    }

    public SessionImport(String name, LogicalModuleId logicalModule, ModuleId moduleId, String revision,
            ImportBindingKind kind, Optional<String> importedName, Optional<String> aliasName,
            Optional<DeclarationId> targetDeclaration, Optional<ExportId> targetExport, boolean reExport) {
        this(name, logicalModule, moduleId, revision, kind, importedName, aliasName, targetDeclaration,
                targetExport, reExport, Optional.empty());
    }

    /** Compatibility constructor for a committed namespace alias. */
    public SessionImport(
            String name,
            LogicalModuleId logicalModule,
            ModuleId moduleId,
            String revision) {
        this(name, logicalModule, moduleId, revision,
                ImportBindingKind.MODULE_NAMESPACE, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), false, Optional.empty());
    }

    public static SessionImport from(ResolvedImportBinding binding, String revision) {
        Objects.requireNonNull(binding, "binding");
        return new SessionImport(
                binding.localName(), binding.logicalModule(), binding.targetModule(), revision,
                binding.kind(), binding.importedName(), binding.aliasName(), binding.targetDeclaration(),
                binding.targetExport(), binding.reExport());
    }

    public static SessionImport from(ResolvedImportBinding binding, SessionModuleEnvironment environment) {
        var contract = SessionModuleContract.from(environment, binding.targetModule());
        return new SessionImport(binding.localName(), binding.logicalModule(), binding.targetModule(),
                contract.producer().revision(), binding.kind(), binding.importedName(), binding.aliasName(),
                binding.targetDeclaration(), binding.targetExport(), binding.reExport(), Optional.of(contract));
    }

    public boolean isModuleNamespace() {
        return kind == ImportBindingKind.MODULE_NAMESPACE;
    }

    public boolean isSelective() {
        return kind == ImportBindingKind.SELECTIVE_VALUE;
    }

    public String importedExportName() {
        return importedName.orElseThrow(() ->
                new IllegalStateException("namespace import has no selected export"));
    }

    private static Optional<String> copyName(Optional<String> value, String field) {
        Objects.requireNonNull(value, field);
        return value.map(name -> token(name, field));
    }

    private static String token(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(field + " must not contain control characters");
            }
        }
        return value;
    }
}
