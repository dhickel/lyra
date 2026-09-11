package io.mindspice.lyra.compiler.types;

import io.mindspice.lyra.compiler.identity.NominalTypeId;

import java.util.Objects;
import java.util.Set;

/** An exact declaration reference. Member schemas are separate, allowing recursive layouts. */
public record NominalType(NominalTypeId id) implements LyraType {
    public NominalType {
        Objects.requireNonNull(id, "id");
    }

    @Override public String canonicalSpelling() { return "Nominal<" + id.stableHash() + ">"; }
    @Override public String canonical() { return canonicalSpelling(); }
    @Override public String spelling() { return canonicalSpelling(); }
    @Override public boolean isPrimitive() { return false; }
    @Override public boolean isComposite() { return true; }
    @Override public boolean isNumeric() { return false; }
    @Override public boolean isInteger() { return false; }
    @Override public boolean isFloating() { return false; }
    @Override public boolean isNilable() { return false; }
    @Override public boolean isNullable() { return false; }
    @Override public boolean isMutable() { return false; }
    @Override public boolean hasQualifier(TypeQualifier qualifier) {
        Objects.requireNonNull(qualifier, "qualifier");
        return false;
    }
    @Override public LyraType withoutQualifiers() { return this; }
    @Override public LyraType baseType() { return this; }
    @Override public LyraType withQualifier(TypeQualifier qualifier) { return QualifiedType.of(this, qualifier); }
    @Override public LyraType withQualifiers(Set<TypeQualifier> qualifiers) {
        Objects.requireNonNull(qualifiers, "qualifiers");
        return qualifiers.isEmpty() ? this : QualifiedType.of(this, qualifiers);
    }
    @Override public LyraType nilable() { return QualifiedType.nilable(this); }
    @Override public LyraType mutable() { return QualifiedType.mutable(this); }
    @Override public String toString() { return canonicalSpelling(); }
}
