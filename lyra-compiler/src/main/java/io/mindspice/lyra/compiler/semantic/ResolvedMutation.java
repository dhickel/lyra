package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.Objects;
import java.util.Optional;

/** Immutable record of an authorized assignment root. */
public record ResolvedMutation(
        ModuleId moduleId,
        SourceSpan span,
        MutationKind kind,
        DeclarationId rootDeclaration,
        Optional<ReferenceId> rootReference) {
    public ResolvedMutation {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(rootDeclaration, "rootDeclaration");
        Objects.requireNonNull(rootReference, "rootReference");
        if (!moduleId.sourceId().equals(span.sourceId())) {
            throw new IllegalArgumentException("mutation span belongs to another module");
        }
    }

    public DeclarationId declaration() {
        return rootDeclaration;
    }

    public boolean isArrayElement() {
        return kind == MutationKind.ARRAY_ELEMENT;
    }
}
