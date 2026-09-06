package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ExportId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.source.ModuleId;

import java.util.Objects;
import java.util.Optional;

/** A fully resolved link retained on a typed expression. */
public record TypedLink(
        Optional<ReferenceId> referenceId,
        Optional<DeclarationId> declarationId,
        Optional<ModuleId> moduleId,
        Optional<ExportId> exportId,
        Optional<AccessKind> accessKind) implements ImmutablePhaseArtifact {
    public TypedLink {
        Objects.requireNonNull(referenceId, "referenceId");
        Objects.requireNonNull(declarationId, "declarationId");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(exportId, "exportId");
        Objects.requireNonNull(accessKind, "accessKind");
    }

    public static TypedLink value(
            ReferenceId referenceId,
            DeclarationId declarationId,
            Optional<ModuleId> moduleId,
            Optional<ExportId> exportId) {
        return new TypedLink(
                Optional.of(Objects.requireNonNull(referenceId, "referenceId")),
                Optional.of(Objects.requireNonNull(declarationId, "declarationId")),
                Objects.requireNonNull(moduleId, "moduleId"),
                Objects.requireNonNull(exportId, "exportId"),
                Optional.empty());
    }

    public static TypedLink access(
            ReferenceId referenceId,
            Optional<DeclarationId> declarationId,
            Optional<ModuleId> moduleId,
            Optional<ExportId> exportId,
            AccessKind accessKind) {
        return new TypedLink(
                Optional.of(Objects.requireNonNull(referenceId, "referenceId")),
                Objects.requireNonNull(declarationId, "declarationId"),
                Objects.requireNonNull(moduleId, "moduleId"),
                Objects.requireNonNull(exportId, "exportId"),
                Optional.of(Objects.requireNonNull(accessKind, "accessKind")));
    }
}
