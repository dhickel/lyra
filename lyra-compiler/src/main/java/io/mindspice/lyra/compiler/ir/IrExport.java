package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ExportId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.BindingContract;
import io.mindspice.lyra.compiler.types.LyraSignature;

import java.util.Objects;
import java.util.Optional;

/** Resolved export and its exact re-export origin. */
public record IrExport(
        String name,
        ModuleId moduleId,
        DeclarationId declarationId,
        SourceSpan span,
        BindingContract contract,
        Optional<LyraSignature> functionSignature,
        Optional<ExportId> exportId,
        boolean reExport,
        ModuleId originModule,
        String originName,
        DeclarationId originDeclaration,
        Optional<ExportId> originExport)
        implements ImmutablePhaseArtifact {
    public IrExport {
        if (Objects.requireNonNull(name, "name").isEmpty()) {
            throw new IllegalArgumentException("export name must not be empty");
        }
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(declarationId, "declarationId");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(functionSignature, "functionSignature");
        Objects.requireNonNull(exportId, "exportId");
        Objects.requireNonNull(originModule, "originModule");
        if (Objects.requireNonNull(originName, "originName").isEmpty()) {
            throw new IllegalArgumentException("origin name must not be empty");
        }
        Objects.requireNonNull(originDeclaration, "originDeclaration");
        Objects.requireNonNull(originExport, "originExport");
        if (!moduleId.sourceId().equals(span.sourceId())) {
            throw new IllegalArgumentException("export span belongs to another module");
        }
        if (functionSignature.isPresent() != exportId.isPresent()
                || functionSignature.isPresent() != originExport.isPresent()) {
            throw new IllegalArgumentException(
                    "callable export signatures and identities must be present together");
        }
        functionSignature.ifPresent(signature -> {
            if (!signature.asFunctionType().equals(contract.valueType().withoutQualifiers())) {
                throw new IllegalArgumentException(
                        "export function signature does not match its binding contract");
            }
        });
        exportId.ifPresent(id -> {
            if (!id.moduleId().equals(moduleId)
                    || !id.exportName().equals(name)
                    || functionSignature.filter(id.signature()::equals).isEmpty()) {
                throw new IllegalArgumentException("export identity does not match its export");
            }
        });
        originExport.ifPresent(id -> {
            if (!id.moduleId().equals(originModule)
                    || !id.exportName().equals(originName)
                    || functionSignature.filter(id.signature()::equals).isEmpty()) {
                throw new IllegalArgumentException("origin export identity does not match its origin");
            }
        });
        if (!reExport && (!originModule.equals(moduleId) || !originName.equals(name)
                || !originDeclaration.equals(declarationId)
                || !originExport.equals(exportId))) {
            throw new IllegalArgumentException("a local export must originate at itself");
        }
    }

    public String exportName() {
        return name;
    }

    public boolean isFunction() {
        return functionSignature.isPresent();
    }

    public boolean isMutable() {
        return contract.isMutable();
    }
}
