package io.mindspice.lyra.runtime;

import java.util.List;
import java.util.Set;

/**
 * Immutable canonical Lyra type contract used at the runtime boundary.
 *
 * <p>This representation is independent of JVM descriptors.  In particular,
 * signedness, qualifiers, and nilability are retained even when two contracts
 * happen to use the same JVM representation.</p>
 *
 * <p>This interface declares no static constant fields.  Primitive constants
 * are owned by {@link PrimitiveType} and must be referenced through that enum.
 * This interface declares default methods, so when initialization begins with
 * {@link PrimitiveType}, JLS 12.4.2 initializes this interface first and a
 * constant field initialized from that enum would observe its constants as
 * null.  Initializing this interface first instead reads a completed enum, so
 * such a field would be wrong in only one of the two orders.</p>
 */
public sealed interface LyraType
        permits PrimitiveType, ArrayType, RangeType, TupleType, FunctionType, QualifiedType, NominalType {
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

    /** Parses an exact canonical type against a closed nominal schema environment. */
    static LyraType parse(String canonicalSpelling, NominalTypeEnvironment nominals) {
        return LyraTypeParser.parse(canonicalSpelling, nominals);
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
