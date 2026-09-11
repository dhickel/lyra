package io.mindspice.lyra.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Complete immutable callable signature, independent of a JVM descriptor. */
public final class LyraSignature {
    private final List<LyraType> parameterTypes;
    private final LyraType returnType;
    private final String canonical;

    public LyraSignature(List<? extends LyraType> parameterTypes, LyraType returnType) {
        Objects.requireNonNull(parameterTypes, "parameterTypes");
        ArrayList<LyraType> copied = new ArrayList<>(parameterTypes.size());
        for (LyraType parameterType : parameterTypes) {
            LyraType nonNull = Objects.requireNonNull(parameterType,
                    "parameterTypes must not contain null");
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
        this.canonical = new FunctionType(this.parameterTypes, this.returnType).canonicalSpelling();
    }

    public static LyraSignature of(List<? extends LyraType> parameterTypes, LyraType returnType) {
        return new LyraSignature(parameterTypes, returnType);
    }

    public static LyraSignature from(FunctionType functionType) {
        Objects.requireNonNull(functionType, "functionType");
        return new LyraSignature(functionType.parameterTypes(), functionType.returnType());
    }

    public static LyraSignature parse(String canonicalSpelling) {
        return parse(canonicalSpelling, NominalTypeEnvironment.empty());
    }

    public static LyraSignature parse(String canonicalSpelling, NominalTypeEnvironment nominals) {
        LyraType parsed = LyraType.parse(canonicalSpelling, nominals);
        if (!(parsed instanceof FunctionType function)) {
            throw new IllegalArgumentException("signature must be an unqualified Fn type: "
                    + canonicalSpelling);
        }
        if (!function.canonicalSpelling().equals(canonicalSpelling)) {
            throw new IllegalArgumentException("signature is not canonical: " + canonicalSpelling);
        }
        return from(function);
    }

    public static LyraSignature fromCanonical(String canonicalSpelling) {
        return parse(canonicalSpelling);
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
        return this == other || other instanceof LyraSignature signature
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
