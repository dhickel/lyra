package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.Objects;
import java.util.Optional;

/** Immutable typed copy of one resolver-proven mutation authorization. */
public record TypedMutation(
        ModuleId moduleId,
        SourceSpan span,
        MutationKind kind,
        DeclarationId rootDeclaration,
        Optional<ReferenceId> rootReference) {
    public TypedMutation {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(rootDeclaration, "rootDeclaration");
        Objects.requireNonNull(rootReference, "rootReference");
        if (!moduleId.sourceId().equals(span.sourceId())) {
            throw new IllegalArgumentException("mutation span belongs to another module");
        }
    }

    public static TypedMutation from(ResolvedMutation mutation) {
        Objects.requireNonNull(mutation, "mutation");
        return new TypedMutation(
                mutation.moduleId(), mutation.span(), mutation.kind(),
                mutation.rootDeclaration(), mutation.rootReference());
    }

    public DeclarationId declaration() {
        return rootDeclaration;
    }

    public boolean isArrayElement() {
        return kind == MutationKind.ARRAY_ELEMENT;
    }
}
