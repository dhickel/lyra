package io.mindspice.lyra.compiler.types;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** An invariant positional Lyra function type, written canonically as {@code Fn<P...;R>}. */
public final class FunctionType implements LyraType {
    private final List<LyraType> parameterTypes;
    private final LyraType returnType;
    private final String canonical;

    public FunctionType(List<? extends LyraType> parameterTypes, LyraType returnType) {
        Objects.requireNonNull(parameterTypes, "parameterTypes");
        ArrayList<LyraType> copied = new ArrayList<>(parameterTypes.size());
        for (LyraType parameterType : parameterTypes) {
            LyraType nonNull = Objects.requireNonNull(
                    parameterType, "parameterTypes must not contain null");
            if (nonNull instanceof QualifiedType qualified) {
                qualified.validateFor(TypePosition.PARAMETER);
            }
            copied.add(nonNull);
        }
        this.parameterTypes = List.copyOf(copied);
        this.returnType = Objects.requireNonNull(returnType, "returnType");
        if (returnType instanceof QualifiedType qualified) {
            qualified.validateFor(TypePosition.RETURN);
        }

        String parameters = this.parameterTypes.stream()
                .map(LyraType::canonicalSpelling)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        this.canonical = "Fn<" + parameters + ";" + returnType.canonicalSpelling() + ">";
    }

    public static FunctionType of(
            List<? extends LyraType> parameterTypes, LyraType returnType) {
        return new FunctionType(parameterTypes, returnType);
    }

    public List<LyraType> parameterTypes() {
        return parameterTypes;
    }

    public List<LyraType> parameters() {
        return parameterTypes;
    }

    public int arity() {
        return parameterTypes.size();
    }

    public LyraType parameterType(int index) {
        return parameterTypes.get(index);
    }

    public LyraType returnType() {
        return returnType;
    }

    public LyraType resultType() {
        return returnType;
    }

    public LyraSignature signature() {
        return new LyraSignature(parameterTypes, returnType);
    }

    @Override
    public String canonicalSpelling() {
        return canonical;
    }

    @Override
    public String canonical() {
        return canonical;
    }

    @Override
    public String spelling() {
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
    public boolean isNullable() {
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
        return this == other
                || other instanceof FunctionType function
                && parameterTypes.equals(function.parameterTypes)
                && returnType.equals(function.returnType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(FunctionType.class, parameterTypes, returnType);
    }

    @Override
    public String toString() {
        return canonical;
    }
}
