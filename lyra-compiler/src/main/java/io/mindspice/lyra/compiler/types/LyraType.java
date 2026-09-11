package io.mindspice.lyra.compiler.types;

import java.util.List;
import java.util.Set;

/**
 * A complete immutable Lyra value type.
 *
 * <p>This model deliberately contains only current language types.  In
 * particular, there is no universal, dynamic, or user-generic
 * type implementation.  The methods are abstract rather than default methods
 * so primitive enum initialization cannot recursively observe null interface
 * constants.</p>
 */
public sealed interface LyraType
        permits PrimitiveType, ArrayType, RangeType, TupleType, FunctionType, QualifiedType, NominalType {
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

    /** Returns the canonical spelling, with no whitespace. */
    String canonicalSpelling();

    String canonical();

    String spelling();

    boolean isPrimitive();

    boolean isComposite();

    boolean isNumeric();

    boolean isInteger();

    boolean isFloating();

    /** True when this contract admits {@code #NIL}. */
    boolean isNilable();

    boolean isNullable();

    /** True when this contract carries a mutable parameter/value qualifier. */
    boolean isMutable();

    boolean hasQualifier(TypeQualifier qualifier);

    /** Returns the unqualified base type; nested function contracts remain intact. */
    LyraType withoutQualifiers();

    LyraType baseType();

    LyraType withQualifier(TypeQualifier qualifier);

    LyraType withQualifiers(Set<TypeQualifier> qualifiers);

    LyraType nilable();

    LyraType mutable();

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
