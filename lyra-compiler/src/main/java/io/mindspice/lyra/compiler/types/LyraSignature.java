package io.mindspice.lyra.compiler.types;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A complete callable Lyra signature independent of any JVM descriptor.
 * Parameter and return contracts retain their Lyra qualifiers and composites.
 */
public final class LyraSignature {
    private final List<LyraType> parameterTypes;
    private final LyraType returnType;
    private final String canonical;

    public LyraSignature(List<? extends LyraType> parameterTypes, LyraType returnType) {
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

    public static LyraSignature of(
            List<? extends LyraType> parameterTypes, LyraType returnType) {
        return new LyraSignature(parameterTypes, returnType);
    }

    public static LyraSignature from(FunctionType functionType) {
        Objects.requireNonNull(functionType, "functionType");
        return new LyraSignature(functionType.parameterTypes(), functionType.returnType());
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

    public FunctionType asFunctionType() {
        return new FunctionType(parameterTypes, returnType);
    }

    public FunctionType functionType() {
        return asFunctionType();
    }

    public String canonicalSpelling() {
        return canonical;
    }

    public String canonical() {
        return canonical;
    }

    public String spelling() {
        return canonical;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof LyraSignature signature
                && parameterTypes.equals(signature.parameterTypes)
                && returnType.equals(signature.returnType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(parameterTypes, returnType);
    }

    @Override
    public String toString() {
        return canonical;
    }
}
