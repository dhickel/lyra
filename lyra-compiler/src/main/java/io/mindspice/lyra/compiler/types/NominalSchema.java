package io.mindspice.lyra.compiler.types;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable ordered member contracts; neither runtime authority nor constructor flow facts. */
public record NominalSchema(NominalType type, Kind kind, List<Member> members,
                            List<LyraType> constructorParameters) {
    public enum Kind { STRUCT, CLASS }

    public record Member(String name, LyraType type, boolean publicAccess,
                         BindingMutability mutability, boolean hasInitializer) {
        public Member {
            Objects.requireNonNull(name, "name");
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("invalid nominal member name: " + name);
            }
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(mutability, "mutability");
            ArrayType.rejectMutableNestedContract(type);
        }

        public boolean method() { return type.withoutQualifiers() instanceof FunctionType; }
    }

    public NominalSchema {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(kind, "kind");
        members = List.copyOf(members);
        constructorParameters = List.copyOf(constructorParameters);
        var names = new HashSet<String>();
        for (Member member : members) {
            if (!names.add(member.name())) {
                throw new IllegalArgumentException("duplicate nominal member: " + member.name());
            }
        }
        // Struct construction has no user-defined parameter signature.
        if (kind == Kind.STRUCT && !constructorParameters.equals(members.stream()
                .filter(member -> !member.hasInitializer()).map(Member::type).toList())) {
            throw new IllegalArgumentException("struct constructor must match its required fields in order");
        }
        FunctionType.of(constructorParameters, type);
    }

    public Optional<Member> member(String name) {
        Objects.requireNonNull(name, "name");
        return members.stream().filter(member -> member.name().equals(name)).findFirst();
    }
}
