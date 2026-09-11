package io.mindspice.lyra.runtime;

import java.util.Objects;
import java.util.Set;

/** Invariant nominal reference contract, independent of its recursive field schema. */
public final class NominalType implements LyraType {
    private final NominalTypeId id;
    private final String canonical;

    public NominalType(NominalTypeId id) {
        this.id = Objects.requireNonNull(id, "id");
        canonical = "Nominal<" + id.stableHash() + ">";
    }

    public NominalTypeId id() { return id; }
    @Override public String canonicalSpelling() { return canonical; }
    @Override public boolean isPrimitive() { return false; }
    @Override public boolean isComposite() { return true; }
    @Override public boolean isNumeric() { return false; }
    @Override public boolean isInteger() { return false; }
    @Override public boolean isFloating() { return false; }
    @Override public boolean isNilable() { return false; }
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
    @Override public boolean equals(Object other) { return other instanceof NominalType type && id.equals(type.id); }
    @Override public int hashCode() { return id.hashCode(); }
    @Override public String toString() { return canonical; }
}
