package io.mindspice.lyra.runtime;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** An immutable Lyra type with canonical {@code @mut}/{@code @nil} qualifiers. */
public final class QualifiedType implements LyraType {
    private final LyraType baseType;
    private final Set<TypeQualifier> qualifiers;
    private final List<TypeQualifier> qualifierList;
    private final String canonical;

    public QualifiedType(LyraType baseType, Set<TypeQualifier> qualifiers) {
        Objects.requireNonNull(baseType, "baseType");
        Objects.requireNonNull(qualifiers, "qualifiers");
        if (qualifiers.isEmpty()) {
            throw new IllegalArgumentException("a qualified type needs a qualifier");
        }

        EnumSet<TypeQualifier> combined = EnumSet.noneOf(TypeQualifier.class);
        for (TypeQualifier qualifier : qualifiers) {
            if (!combined.add(Objects.requireNonNull(qualifier, "qualifiers must not contain null"))) {
                throw new IllegalArgumentException("duplicate qualifier: " + qualifier.spelling());
            }
        }
        LyraType unqualified = baseType;
        while (unqualified instanceof QualifiedType nested) {
            for (TypeQualifier qualifier : nested.qualifiers) {
                if (!combined.add(qualifier)) {
                    throw new IllegalArgumentException("duplicate qualifier: " + qualifier.spelling());
                }
            }
            unqualified = nested.baseType;
        }
        this.baseType = unqualified;
        this.qualifiers = Collections.unmodifiableSet(EnumSet.copyOf(combined));
        this.qualifierList = List.of(TypeQualifier.MUT, TypeQualifier.NIL).stream()
                .filter(this.qualifiers::contains).toList();
        StringBuilder spelling = new StringBuilder();
        for (TypeQualifier qualifier : qualifierList) {
            spelling.append(qualifier.spelling());
        }
        this.canonical = spelling.append(unqualified.canonicalSpelling()).toString();
    }

    public QualifiedType(LyraType baseType, TypeQualifier... qualifiers) {
        this(baseType, distinctQualifiers(qualifiers));
    }

    public static QualifiedType of(LyraType baseType, TypeQualifier qualifier) {
        return new QualifiedType(baseType, Objects.requireNonNull(qualifier, "qualifier"));
    }

    public static QualifiedType of(LyraType baseType, TypeQualifier... qualifiers) {
        return new QualifiedType(baseType, qualifiers);
    }

    public static QualifiedType of(LyraType baseType, Set<TypeQualifier> qualifiers) {
        return new QualifiedType(baseType, qualifiers);
    }

    public static QualifiedType nilable(LyraType baseType) {
        return of(baseType, TypeQualifier.NIL);
    }

    public static QualifiedType mutable(LyraType baseType) {
        return of(baseType, TypeQualifier.MUT);
    }

    @Override
    public String canonicalSpelling() {
        return canonical;
    }

    @Override
    public boolean isPrimitive() {
        return baseType.isPrimitive();
    }

    @Override
    public boolean isComposite() {
        return baseType.isComposite();
    }

    @Override
    public boolean isNumeric() {
        return baseType.isNumeric();
    }

    @Override
    public boolean isInteger() {
        return baseType.isInteger();
    }

    @Override
    public boolean isFloating() {
        return baseType.isFloating();
    }

    @Override
    public boolean isNilable() {
        return hasQualifier(TypeQualifier.NIL);
    }

    @Override
    public boolean isMutable() {
        return hasQualifier(TypeQualifier.MUT);
    }

    @Override
    public boolean hasQualifier(TypeQualifier qualifier) {
        return qualifiers.contains(Objects.requireNonNull(qualifier, "qualifier"));
    }

    @Override
    public LyraType withoutQualifiers() {
        return baseType;
    }

    @Override
    public LyraType baseType() {
        return baseType;
    }

    public Set<TypeQualifier> qualifiers() {
        return qualifiers;
    }

    public List<TypeQualifier> qualifierList() {
        return qualifierList;
    }

    @Override
    public LyraType withQualifier(TypeQualifier qualifier) {
        Objects.requireNonNull(qualifier, "qualifier");
        if (hasQualifier(qualifier)) {
            return this;
        }
        EnumSet<TypeQualifier> combined = EnumSet.copyOf(qualifiers);
        combined.add(qualifier);
        return new QualifiedType(baseType, combined);
    }

    @Override
    public LyraType withQualifiers(Set<TypeQualifier> additionalQualifiers) {
        Objects.requireNonNull(additionalQualifiers, "additionalQualifiers");
        if (additionalQualifiers.isEmpty()) {
            return this;
        }
        EnumSet<TypeQualifier> combined = EnumSet.copyOf(qualifiers);
        for (TypeQualifier qualifier : additionalQualifiers) {
            combined.add(Objects.requireNonNull(qualifier, "additionalQualifiers must not contain null"));
        }
        return new QualifiedType(baseType, combined);
    }

    @Override
    public LyraType nilable() {
        return withQualifier(TypeQualifier.NIL);
    }

    @Override
    public LyraType mutable() {
        return withQualifier(TypeQualifier.MUT);
    }

    /** Validates qualifier legality at a function/signature position. */
    public void validateFor(TypePosition position) {
        Objects.requireNonNull(position, "position");
        if (isMutable() && !position.permitsMutableQualifier()) {
            throw new IllegalArgumentException(
                    "@mut is not legal in a " + position.name().toLowerCase() + " type position");
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof QualifiedType qualified
                && baseType.equals(qualified.baseType)
                && qualifiers.equals(qualified.qualifiers);
    }

    @Override
    public int hashCode() {
        return 31 * baseType.hashCode() + qualifiers.hashCode();
    }

    @Override
    public String toString() {
        return canonical;
    }

    private static Set<TypeQualifier> distinctQualifiers(TypeQualifier[] qualifiers) {
        Objects.requireNonNull(qualifiers, "qualifiers");
        EnumSet<TypeQualifier> distinct = EnumSet.noneOf(TypeQualifier.class);
        for (TypeQualifier qualifier : qualifiers) {
            TypeQualifier nonNull = Objects.requireNonNull(qualifier, "qualifiers must not contain null");
            if (!distinct.add(nonNull)) {
                throw new IllegalArgumentException("duplicate qualifier: " + nonNull.spelling());
            }
        }
        return distinct;
    }
}
