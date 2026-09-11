package io.mindspice.lyra.runtime;

import java.util.List;
import java.util.Set;

/**
 * Immutable canonical Lyra type contract used at the runtime boundary.
 *
 * <p>This representation is independent of JVM descriptors.  In particular,
 * signedness, qualifiers, and nilability are retained even when two contracts
 * happen to use the same JVM representation.</p>
 */
public sealed interface LyraType
        permits PrimitiveType, ArrayType, RangeType, TupleType, FunctionType, QualifiedType {
    PrimitiveType I8 = PrimitiveType.I8;
    PrimitiveType I16 = PrimitiveType.I16;
    PrimitiveType I32 = PrimitiveType.I32;
    PrimitiveType I64 = PrimitiveType.I64;
    PrimitiveType U8 = PrimitiveType.U8;
    PrimitiveType U16 = PrimitiveType.U16;
    PrimitiveType U32 = PrimitiveType.U32;
    PrimitiveType U64 = PrimitiveType.U64;
    PrimitiveType F32 = PrimitiveType.F32;
    PrimitiveType F64 = PrimitiveType.F64;
    PrimitiveType BOOL = PrimitiveType.BOOL;
    PrimitiveType CHAR = PrimitiveType.CHAR;
    PrimitiveType STRING = PrimitiveType.STRING;
    PrimitiveType UNIT = PrimitiveType.UNIT;

    String canonicalSpelling();

    default String canonical() {
        return canonicalSpelling();
    }

    default String spelling() {
        return canonicalSpelling();
    }

    boolean isPrimitive();

    boolean isComposite();

    boolean isNumeric();

    boolean isInteger();

    boolean isFloating();

    boolean isNilable();

    default boolean isNullable() {
        return isNilable();
    }

    boolean isMutable();

    boolean hasQualifier(TypeQualifier qualifier);

    LyraType withoutQualifiers();

    LyraType baseType();

    LyraType withQualifier(TypeQualifier qualifier);

    LyraType withQualifiers(Set<TypeQualifier> qualifiers);

    LyraType nilable();

    LyraType mutable();

    static LyraType parse(String canonicalSpelling) {
        return LyraTypeParser.parse(canonicalSpelling);
    }

    static LyraType fromCanonical(String canonicalSpelling) {
        return parse(canonicalSpelling);
    }

    static ArrayType array(LyraType elementType) {
        return ArrayType.of(elementType);
    }

    static TupleType tuple(List<? extends LyraType> memberTypes) {
        return TupleType.of(memberTypes);
    }

    static FunctionType function(List<? extends LyraType> parameterTypes, LyraType returnType) {
        return FunctionType.of(parameterTypes, returnType);
    }

    static FunctionType fn(List<? extends LyraType> parameterTypes, LyraType returnType) {
        return function(parameterTypes, returnType);
    }

    static QualifiedType qualified(LyraType baseType, TypeQualifier... qualifiers) {
        return QualifiedType.of(baseType, qualifiers);
    }
}
