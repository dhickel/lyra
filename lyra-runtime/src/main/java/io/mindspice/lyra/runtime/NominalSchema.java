package io.mindspice.lyra.runtime;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Exact ordered nominal ABI contracts, without live instances or executable code. */
public record NominalSchema(NominalType type, Kind kind, List<Member> members,
                            List<LyraType> constructorParameters) {
    public enum Kind { STRUCT, CLASS }

    public record Member(String name, LyraType type, boolean publicAccess,
                         boolean mutable, boolean hasInitializer) {
        public Member {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("invalid member name");
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
            if (!names.add(member.name())) throw new IllegalArgumentException("duplicate nominal member");
        }
        if (kind == Kind.STRUCT && !constructorParameters.equals(members.stream()
                .filter(member -> !member.hasInitializer()).map(Member::type).toList())) {
            throw new IllegalArgumentException("struct constructor differs from required fields");
        }
        FunctionType.of(constructorParameters, type);
    }
}
