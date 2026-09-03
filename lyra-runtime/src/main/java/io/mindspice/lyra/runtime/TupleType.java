package io.mindspice.lyra.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** An invariant heterogeneous fixed-shape Lyra tuple type. */
public final class TupleType implements LyraType {
    private final List<LyraType> memberTypes;
    private final String canonical;

    public TupleType(List<? extends LyraType> memberTypes) {
        Objects.requireNonNull(memberTypes, "memberTypes");
        if (memberTypes.isEmpty()) {
            throw new IllegalArgumentException("Tuple[] is Unit; an empty tuple type is not distinct");
        }
        ArrayList<LyraType> copied = new ArrayList<>(memberTypes.size());
        for (LyraType memberType : memberTypes) {
            LyraType nonNull = Objects.requireNonNull(memberType, "memberTypes must not contain null");
            ArrayType.rejectMutableNestedContract(nonNull);
            copied.add(nonNull);
        }
        this.memberTypes = List.copyOf(copied);
        this.canonical = "Tuple<" + this.memberTypes.stream()
                .map(LyraType::canonicalSpelling).reduce((left, right) -> left + "," + right)
                .orElseThrow() + ">";
    }

    public static TupleType of(List<? extends LyraType> memberTypes) {
        return new TupleType(memberTypes);
    }

    public List<LyraType> memberTypes() {
        return memberTypes;
    }

    public List<LyraType> elementTypes() {
        return memberTypes;
    }

    public int arity() {
        return memberTypes.size();
    }

    public LyraType memberType(int index) {
        return memberTypes.get(index);
    }

    @Override
    public String canonicalSpelling() {
        return canonical;
    }

    @Override
    public boolean isPrimitive() {
        return false;
    }

    @Override
    public boolean isComposite() {
        return true;
    }

    @Override
    public boolean isNumeric() {
        return false;
    }

    @Override
    public boolean isInteger() {
        return false;
    }

    @Override
    public boolean isFloating() {
        return false;
    }

    @Override
    public boolean isNilable() {
        return false;
    }

    @Override
    public boolean isMutable() {
        return false;
    }

    @Override
    public boolean hasQualifier(TypeQualifier qualifier) {
        Objects.requireNonNull(qualifier, "qualifier");
        return false;
    }

    @Override
    public LyraType withoutQualifiers() {
        return this;
    }

    @Override
    public LyraType baseType() {
        return this;
    }

    @Override
    public LyraType withQualifier(TypeQualifier qualifier) {
        return QualifiedType.of(this, qualifier);
    }

    @Override
    public LyraType withQualifiers(java.util.Set<TypeQualifier> qualifiers) {
        Objects.requireNonNull(qualifiers, "qualifiers");
        return qualifiers.isEmpty() ? this : QualifiedType.of(this, qualifiers);
    }

    @Override
    public LyraType nilable() {
        return QualifiedType.nilable(this);
    }

    @Override
    public LyraType mutable() {
        return QualifiedType.mutable(this);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof TupleType tuple && memberTypes.equals(tuple.memberTypes);
    }

    @Override
    public int hashCode() {
        return 31 * TupleType.class.hashCode() + memberTypes.hashCode();
    }

    @Override
    public String toString() {
        return canonical;
    }
}
