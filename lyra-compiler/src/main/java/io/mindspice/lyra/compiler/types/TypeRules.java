package io.mindspice.lyra.compiler.types;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Parser-independent static operations on current-scope Lyra types.
 *
 * <p>Composite types are invariant.  The only implicit numeric edges are the
 * lossless edges stated by the language contract; all other numeric changes
 * require an explicit conversion.</p>
 */
public final class TypeRules {
    private static final List<PrimitiveType> NUMERIC_CANDIDATES = List.of(
            PrimitiveType.I8,
            PrimitiveType.U8,
            PrimitiveType.I16,
            PrimitiveType.U16,
            PrimitiveType.I32,
            PrimitiveType.U32,
            PrimitiveType.I64,
            PrimitiveType.U64,
            PrimitiveType.F32,
            PrimitiveType.F64);
    private static final List<PrimitiveType> SCALAR_TEXT_INPUTS = List.of(
            PrimitiveType.I8,
            PrimitiveType.I16,
            PrimitiveType.I32,
            PrimitiveType.I64,
            PrimitiveType.U8,
            PrimitiveType.U16,
            PrimitiveType.U32,
            PrimitiveType.U64,
            PrimitiveType.F32,
            PrimitiveType.F64,
            PrimitiveType.BOOL,
            PrimitiveType.CHAR,
            PrimitiveType.STRING,
            PrimitiveType.UNIT);

    private TypeRules() {
    }

    public static boolean isNumeric(LyraType type) {
        return Objects.requireNonNull(type, "type").withoutQualifiers()
                instanceof PrimitiveType primitive && primitive.isNumeric();
    }

    public static boolean isInteger(LyraType type) {
        return Objects.requireNonNull(type, "type").withoutQualifiers()
                instanceof PrimitiveType primitive && primitive.isInteger();
    }

    public static boolean isFloating(LyraType type) {
        return Objects.requireNonNull(type, "type").withoutQualifiers()
                instanceof PrimitiveType primitive && primitive.isFloating();
    }

    /** Returns every implicit numeric destination, including the identity destination. */
    public static List<PrimitiveType> implicitNumericTargets(PrimitiveType source) {
        Objects.requireNonNull(source, "source");
        if (!source.isNumeric()) {
            return List.of();
        }
        return NUMERIC_CANDIDATES.stream()
                .filter(target -> canImplicitlyWiden(source, target))
                .toList();
    }

    public static boolean canImplicitlyWiden(PrimitiveType source, PrimitiveType target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (!source.isNumeric() || !target.isNumeric()) {
            return false;
        }
        if (source == target) {
            return true;
        }

        NumericDomain sourceDomain = source.numericDomain().orElseThrow();
        NumericDomain targetDomain = target.numericDomain().orElseThrow();
        return sourceDomain.canImplicitlyWidenTo(targetDomain);
    }

    public static boolean isLosslessWidening(PrimitiveType source, PrimitiveType target) {
        return canImplicitlyWiden(source, target) && source != target;
    }

    public static ConversionDecision classify(LyraType source, LyraType target) {
        return implicitConversion(source, target);
    }

    public static ConversionDecision implicitConversion(LyraType source, LyraType target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        TypeParts sourceParts = TypeParts.of(source);
        TypeParts targetParts = TypeParts.of(target);

        if (targetParts.mutable && !sourceParts.mutable) {
            return incompatible(source, target, "target requires @mut but source has no mutation permission");
        }
        if (sourceParts.nilable && !targetParts.nilable) {
            return incompatible(source, target, "a nilable source cannot be used as a non-nilable target");
        }

        ArrayList<ConversionStep> steps = new ArrayList<>();
        if (sourceParts.mutable && !targetParts.mutable) {
            steps.add(ConversionStep.MUTABILITY_DROP);
        }
        if (!sourceParts.nilable && targetParts.nilable) {
            steps.add(ConversionStep.NIL_LIFT);
        }

        if (sourceParts.base.equals(targetParts.base)) {
            return decision(source, target, steps, "matching Lyra contract");
        }
        if (sourceParts.base instanceof PrimitiveType sourcePrimitive
                && targetParts.base instanceof PrimitiveType targetPrimitive
                && canImplicitlyWiden(sourcePrimitive, targetPrimitive)) {
            steps.add(ConversionStep.NUMERIC_WIDENING);
            return decision(source, target, steps, "lossless numeric widening");
        }
        return incompatible(source, target, "composite types are invariant and no implicit widening applies");
    }

    public static boolean canImplicitlyConvert(LyraType source, LyraType target) {
        return implicitConversion(source, target).allowed();
    }

    public static boolean canImplicitlyWiden(LyraType source, LyraType target) {
        ConversionDecision decision = implicitConversion(source, target);
        return decision.allowed()
                && !source.equals(target)
                && decision.steps().contains(ConversionStep.NUMERIC_WIDENING);
    }

    /**
     * Classifies a type-bracket conversion.  Numeric conversions are value and
     * range checked by later semantic/runtime phases; this model only records
     * whether the conversion kind is legal.
     */
    public static ConversionDecision explicitConversion(LyraType source, LyraType target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        ConversionDecision implicit = implicitConversion(source, target);
        if (implicit.allowed()) {
            return implicit;
        }

        TypeParts sourceParts = TypeParts.of(source);
        TypeParts targetParts = TypeParts.of(target);
        if (targetParts.mutable && !sourceParts.mutable) {
            return incompatible(source, target, "target requires @mut but source has no mutation permission");
        }
        if (sourceParts.nilable && !targetParts.nilable) {
            return incompatible(source, target, "a nilable source must be narrowed before conversion");
        }
        if (sourceParts.nilable && !sourceParts.base.equals(targetParts.base)) {
            return incompatible(source, target, "a nilable source must be narrowed before explicit conversion");
        }

        ArrayList<ConversionStep> steps = new ArrayList<>();
        if (sourceParts.mutable && !targetParts.mutable) {
            steps.add(ConversionStep.MUTABILITY_DROP);
        }
        if (!sourceParts.nilable && targetParts.nilable) {
            steps.add(ConversionStep.NIL_LIFT);
        }

        if (sourceParts.base instanceof PrimitiveType sourcePrimitive
                && targetParts.base instanceof PrimitiveType targetPrimitive) {
            if (sourcePrimitive.isNumeric() && targetPrimitive.isNumeric()) {
                steps.add(ConversionStep.NUMERIC_EXPLICIT);
                return explicit(source, target, steps, "explicit numeric conversion");
            }
            if (targetPrimitive == PrimitiveType.STRING
                    && SCALAR_TEXT_INPUTS.contains(sourcePrimitive)) {
                steps.add(ConversionStep.TEXT_EXPLICIT);
                return explicit(source, target, steps, "explicit scalar text conversion");
            }
        }
        return incompatible(source, target, "no explicit conversion exists for these Lyra types");
    }

    public static boolean canExplicitlyConvert(LyraType source, LyraType target) {
        return explicitConversion(source, target).allowed();
    }

    public static boolean isExplicitlyConvertible(LyraType source, LyraType target) {
        return canExplicitlyConvert(source, target);
    }

    public static Optional<LyraType> commonType(Collection<? extends LyraType> types) {
        Objects.requireNonNull(types, "types");
        if (types.isEmpty()) {
            return Optional.empty();
        }
        List<LyraType> copied = types.stream()
                .map(type -> Objects.requireNonNull(type, "types must not contain null"))
                .toList();
        if (copied.stream().allMatch(TypeRules::isNumeric)) {
            return commonNumericType(copied);
        }

        TypeParts first = TypeParts.of(copied.getFirst());
        boolean nilable = copied.stream().anyMatch(type -> TypeParts.of(type).nilable);
        boolean mutable = copied.stream().allMatch(type -> TypeParts.of(type).mutable);
        LyraType candidate = qualify(first.base, mutable, nilable);
        if (copied.stream().allMatch(type -> canImplicitlyConvert(type, candidate))) {
            return Optional.of(candidate);
        }
        return Optional.empty();
    }

    public static Optional<LyraType> commonType(LyraType... types) {
        Objects.requireNonNull(types, "types");
        return commonType(List.of(types));
    }

    public static Optional<LyraType> leastCommonType(Collection<? extends LyraType> types) {
        return commonType(types);
    }

    public static Optional<LyraType> common(Collection<? extends LyraType> types) {
        return commonType(types);
    }

    public static Optional<LyraType> commonNumericType(Collection<? extends LyraType> types) {
        Objects.requireNonNull(types, "types");
        if (types.isEmpty()) {
            return Optional.empty();
        }
        List<LyraType> copied = types.stream()
                .map(type -> Objects.requireNonNull(type, "types must not contain null"))
                .toList();
        if (!copied.stream().allMatch(TypeRules::isNumeric)) {
            return Optional.empty();
        }
        boolean nilable = copied.stream().anyMatch(type -> TypeParts.of(type).nilable);
        boolean mutable = copied.stream().allMatch(type -> TypeParts.of(type).mutable);
        for (PrimitiveType candidatePrimitive : NUMERIC_CANDIDATES) {
            LyraType candidate = qualify(candidatePrimitive, mutable, nilable);
            if (copied.stream().allMatch(type -> canImplicitlyConvert(type, candidate))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    public static Optional<LyraType> commonNumericType(LyraType... types) {
        Objects.requireNonNull(types, "types");
        return commonNumericType(List.of(types));
    }

    public static Optional<PrimitiveType> commonNumericPrimitiveType(
            Collection<PrimitiveType> types) {
        Objects.requireNonNull(types, "types");
        if (types.isEmpty() || types.stream().anyMatch(Objects::isNull)
                || types.stream().anyMatch(type -> !type.isNumeric())) {
            return Optional.empty();
        }
        for (PrimitiveType candidate : NUMERIC_CANDIDATES) {
            if (types.stream().allMatch(type -> canImplicitlyWiden(type, candidate))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static LyraType qualify(LyraType base, boolean mutable, boolean nilable) {
        if (mutable && nilable) {
            return QualifiedType.of(base, TypeQualifier.MUT, TypeQualifier.NIL);
        }
        if (mutable) {
            return QualifiedType.of(base, TypeQualifier.MUT);
        }
        if (nilable) {
            return QualifiedType.of(base, TypeQualifier.NIL);
        }
        return base;
    }

    private static ConversionDecision decision(
            LyraType source,
            LyraType target,
            List<ConversionStep> steps,
            String reason) {
        if (steps.isEmpty()) {
            return new ConversionDecision(
                    source, target, ConversionKind.IDENTITY, List.of(), reason);
        }
        return new ConversionDecision(source, target, ConversionKind.IMPLICIT, steps, reason);
    }

    private static ConversionDecision explicit(
            LyraType source,
            LyraType target,
            List<ConversionStep> steps,
            String reason) {
        return new ConversionDecision(source, target, ConversionKind.EXPLICIT, steps, reason);
    }

    private static ConversionDecision incompatible(
            LyraType source, LyraType target, String reason) {
        return new ConversionDecision(
                source, target, ConversionKind.INCOMPATIBLE, List.of(), reason);
    }

    private record TypeParts(LyraType base, boolean mutable, boolean nilable) {
        private static TypeParts of(LyraType type) {
            return new TypeParts(
                    type.withoutQualifiers(),
                    type.hasQualifier(TypeQualifier.MUT),
                    type.hasQualifier(TypeQualifier.NIL));
        }
    }
}
