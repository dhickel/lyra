package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.types.NominalSchema;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Declaration links for an exact schema. Initialization proof belongs to type checking. */
public record ResolvedNominal(DeclarationId declaration, DeclarationId self,
                              NominalSchema schema, List<DeclarationId> members,
                              Optional<LambdaId> constructor) {
    public ResolvedNominal {
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(self, "self");
        Objects.requireNonNull(schema, "schema");
        members = List.copyOf(members);
        Objects.requireNonNull(constructor, "constructor");
        if (members.size() != schema.members().size() || members.stream().distinct().count() != members.size()
                || members.contains(declaration) || members.contains(self) || declaration.equals(self)) {
            throw new IllegalArgumentException("nominal declaration identities disagree with schema");
        }
        if (schema.kind() == NominalSchema.Kind.STRUCT && constructor.isPresent()) {
            throw new IllegalArgumentException("structs cannot declare a constructor lambda");
        }
    }
}
