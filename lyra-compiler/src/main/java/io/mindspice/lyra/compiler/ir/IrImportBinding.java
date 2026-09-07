package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ExportId;
import io.mindspice.lyra.compiler.semantic.ImportBindingKind;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.Objects;
import java.util.Optional;

/** Exact header-import binding retained for module/access lowering. */
public record IrImportBinding(
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
        Optional<io.mindspice.lyra.compiler.session.SessionModuleContract> producerContract)
        implements ImmutablePhaseArtifact, Comparable<IrImportBinding> {
    public IrImportBinding {
        Objects.requireNonNull(declarationId, "declarationId");
        if (Objects.requireNonNull(localName, "localName").isEmpty()) {
            throw new IllegalArgumentException("local import name must not be empty");
        }
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

    public IrImportBinding(DeclarationId declarationId, String localName, SourceSpan localNameSpan,
            SourceSpan importSpan, LogicalModuleId logicalModule, ModuleId targetModule, ImportBindingKind kind,
            Optional<String> importedName, Optional<String> aliasName, boolean reExport,
            Optional<DeclarationId> targetDeclaration, Optional<ExportId> targetExport) {
        this(declarationId, localName, localNameSpan, importSpan, logicalModule, targetModule, kind,
                importedName, aliasName, reExport, targetDeclaration, targetExport, false, Optional.empty());
    }

    public static IrImportBinding from(io.mindspice.lyra.compiler.semantic.ResolvedImportBinding value,
            Optional<IrSessionExecution> execution) {
        var contract = value.producerContract().or(() -> execution.map(value1 ->
                io.mindspice.lyra.compiler.session.SessionModuleContract.from(
                        value1.environment(), value.targetModule())));
        return new IrImportBinding(value.declarationId(), value.localName(), value.localNameSpan(),
                value.importSpan(), value.logicalModule(), value.targetModule(), value.kind(),
                value.importedName(), value.aliasName(), value.reExport(), value.targetDeclaration(),
                value.targetExport(), value.retained(), contract);
    }

    public static IrImportBinding from(io.mindspice.lyra.compiler.semantic.ResolvedImportBinding value) {
        Objects.requireNonNull(value, "value");
        return from(value, Optional.empty());
    }

    public DeclarationId id() {
        return declarationId;
    }

    @Override
    public int compareTo(IrImportBinding other) {
        return declarationId.compareTo(Objects.requireNonNull(other, "other").declarationId);
    }
}
