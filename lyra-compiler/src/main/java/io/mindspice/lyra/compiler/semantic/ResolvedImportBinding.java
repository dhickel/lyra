package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ExportId;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.Objects;
import java.util.Optional;

/** One immutable local binding introduced by a header import. */
public record ResolvedImportBinding(
        DeclarationId declarationId,
        String localName,
        SourceSpan localNameSpan,
        SourceSpan importSpan,
        LogicalModuleId logicalModule,
        ModuleId targetModule,
        ImportBindingKind kind,
        Optional<String> importedName,
        Optional<String> aliasName,
        boolean reExport,
        Optional<DeclarationId> targetDeclaration,
        Optional<ExportId> targetExport,
        boolean retained,
        Optional<io.mindspice.lyra.compiler.session.SessionModuleContract> producerContract) {
    public ResolvedImportBinding {
        Objects.requireNonNull(declarationId, "declarationId");
        requireText(localName, "localName");
        Objects.requireNonNull(localNameSpan, "localNameSpan");
        Objects.requireNonNull(importSpan, "importSpan");
        Objects.requireNonNull(logicalModule, "logicalModule");
        Objects.requireNonNull(targetModule, "targetModule");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(importedName, "importedName");
        Objects.requireNonNull(aliasName, "aliasName");
        Objects.requireNonNull(targetDeclaration, "targetDeclaration");
        Objects.requireNonNull(targetExport, "targetExport");
        Objects.requireNonNull(producerContract, "producerContract");
        producerContract.ifPresent(contract -> new io.mindspice.lyra.compiler.session.SessionImport(
                localName, logicalModule, targetModule, contract.producer().revision(), kind, importedName,
                aliasName, targetDeclaration, targetExport, reExport, Optional.of(contract)));
        if (!localNameSpan.sourceId().equals(importSpan.sourceId())) {
            throw new IllegalArgumentException("import binding name and import spans disagree");
        }
        if (kind == ImportBindingKind.MODULE_NAMESPACE && importedName.isPresent()) {
            throw new IllegalArgumentException("module imports do not have a selected export name");
        }
        if (kind == ImportBindingKind.SELECTIVE_VALUE && importedName.isEmpty()) {
            throw new IllegalArgumentException("selective imports need an imported name");
        }
        if (!reExport && targetExport.isPresent() && targetDeclaration.isEmpty()) {
            throw new IllegalArgumentException("an export link needs its target declaration");
        }
    }

    public ResolvedImportBinding(DeclarationId declarationId, String localName, SourceSpan localNameSpan,
            SourceSpan importSpan, LogicalModuleId logicalModule, ModuleId targetModule, ImportBindingKind kind,
            Optional<String> importedName, Optional<String> aliasName, boolean reExport,
            Optional<DeclarationId> targetDeclaration, Optional<ExportId> targetExport, boolean retained) {
        this(declarationId, localName, localNameSpan, importSpan, logicalModule, targetModule, kind,
                importedName, aliasName, reExport, targetDeclaration, targetExport, retained, Optional.empty());
    }

    /** True when this binding was restored from session metadata, not source text. */
    public boolean isRetained() {
        return retained;
    }

    /** Compatibility constructor for source-header import records. */
    public ResolvedImportBinding(
            DeclarationId declarationId,
            String localName,
            SourceSpan localNameSpan,
            SourceSpan importSpan,
            LogicalModuleId logicalModule,
            ModuleId targetModule,
            ImportBindingKind kind,
            Optional<String> importedName,
            Optional<String> aliasName,
            boolean reExport,
            Optional<DeclarationId> targetDeclaration,
            Optional<ExportId> targetExport) {
        this(declarationId, localName, localNameSpan, importSpan, logicalModule, targetModule,
                kind, importedName, aliasName, reExport, targetDeclaration, targetExport, false);
    }

    public DeclarationId id() {
        return declarationId;
    }

    public String name() {
        return localName;
    }

    public LogicalModuleId requestedModule() {
        return logicalModule;
    }

    public ModuleId module() {
        return targetModule;
    }

    public boolean isModuleNamespace() {
        return kind == ImportBindingKind.MODULE_NAMESPACE;
    }

    public boolean isSelective() {
        return kind == ImportBindingKind.SELECTIVE_VALUE;
    }

    public boolean isReExport() {
        return reExport;
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
    }
}
