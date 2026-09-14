package io.mindspice.lyra.compiler.types;

import java.util.List;
import java.util.Set;

/**
 * A complete immutable Lyra value type.
 *
 * <p>This model deliberately contains only current language types.  In
 * particular, there is no universal, dynamic, or user-generic
 * type implementation.  The methods are abstract rather than default methods
 * so primitive enum initialization can never recursively observe null
 * interface constants.</p>
 *
 * <p>This interface declares no static constant fields.  Primitive constants
 * are owned by {@link PrimitiveType} and must be referenced through that enum.
 * A constant field on this interface initialized from an enum that implements
 * it would be unsafe whenever this interface declares a default method: JLS
 * 12.4.2 initializes a class's superinterfaces that declare at least one
 * default method before the class, so this interface would be initialized
 * while {@link PrimitiveType}'s constants were still null.  The abstract
 * method set above currently avoids that trigger, but the field must not
 * return, because its safety would then depend on this interface never
 * gaining a default method.</p>
 */
public sealed interface LyraType
        permits PrimitiveType, ArrayType, RangeType, TupleType, FunctionType, QualifiedType, NominalType {
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
