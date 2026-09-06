package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.ast.SyntaxNode;
import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.LyraType;
import io.mindspice.lyra.compiler.types.TupleType;
import io.mindspice.lyra.compiler.types.TypeQualifier;
import io.mindspice.lyra.compiler.types.TypeRules;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The pure structural expected-type law used by both semantic type consumers.
 *
 * <p>A context is attached to a semantic child position, rather than to an
 * entire enclosing expression.  This distinction is what keeps a nil member
 * of a tuple or array from making unrelated members nilable.  The law knows
 * only syntax and types; it performs no resolution, publication, or
 * diagnostic side effects.</p>
 */
final class StructuralContextPlan {
    private StructuralContextPlan() {
    }

    /** Semantic roles whose children can receive a structural context. */
    enum Role {
        BLOCK_FINAL,
        ARRAY_ELEMENT,
        TUPLE_MEMBER,
        CONDITIONAL_THEN,
        CONDITIONAL_ELSE,
        COALESCE_VALUE,
        COALESCE_FALLBACK,
        EQUALITY_OPERAND
    }

    /** A role plus its positional index where the role is indexed. */
    record ChildPosition(Role role, int index) {
        ChildPosition {
            Objects.requireNonNull(role, "role");
            if (index < -1) {
                throw new IllegalArgumentException("child position index must not be below -1");
            }
            if (requiresIndex(role) && index < 0) {
                throw new IllegalArgumentException(role + " requires a child index");
            }
            if (!requiresIndex(role) && index != -1) {
                throw new IllegalArgumentException(role + " does not have a child index");
            }
        }

        static ChildPosition blockFinal() {
            return new ChildPosition(Role.BLOCK_FINAL, -1);
        }

        static ChildPosition arrayElement(int index) {
            return new ChildPosition(Role.ARRAY_ELEMENT, index);
        }

        static ChildPosition tupleMember(int index) {
            return new ChildPosition(Role.TUPLE_MEMBER, index);
        }

        static ChildPosition conditionalThen() {
            return new ChildPosition(Role.CONDITIONAL_THEN, -1);
        }

        static ChildPosition conditionalElse() {
            return new ChildPosition(Role.CONDITIONAL_ELSE, -1);
        }

        static ChildPosition coalesceValue() {
            return new ChildPosition(Role.COALESCE_VALUE, -1);
        }

        static ChildPosition coalesceFallback() {
            return new ChildPosition(Role.COALESCE_FALLBACK, -1);
        }

        static ChildPosition equalityOperand(int index) {
            return new ChildPosition(Role.EQUALITY_OPERAND, index);
        }

        private static boolean requiresIndex(Role role) {
            return role == Role.ARRAY_ELEMENT
                    || role == Role.TUPLE_MEMBER
                    || role == Role.EQUALITY_OPERAND;
        }
    }

    /**
     * Returns the expected contract for one semantic child position.
     * {@code expectedParent} is an enclosing declared/contextual contract;
     * {@code peerType} is an independently synthesized sibling shape.
     */
    static Optional<LyraType> expectedFor(
            ChildPosition position,
            SyntaxNode.Expression child,
            Optional<LyraType> expectedParent,
            Optional<LyraType> peerType) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(child, "child");
        Objects.requireNonNull(expectedParent, "expectedParent");
        Objects.requireNonNull(peerType, "peerType");

        return switch (position.role()) {
            case BLOCK_FINAL, CONDITIONAL_THEN, CONDITIONAL_ELSE -> {
                if (expectedParent.isPresent()) {
                    yield expectedParent;
                }
                yield peerType.flatMap(peer -> containsContextFreeNil(child)
                        ? expectedFromPeer(child, peer)
                        : Optional.of(peer));
            }
            case ARRAY_ELEMENT -> expectedAggregateElement(
                    child, expectedParent, peerType, position.index(), false);
            case TUPLE_MEMBER -> expectedAggregateElement(
                    child, expectedParent, peerType, position.index(), true);
            case COALESCE_VALUE -> expectedCoalesceValue(expectedParent);
            case COALESCE_FALLBACK -> expectedCoalesceFallback(expectedParent);
            case EQUALITY_OPERAND -> peerType.flatMap(peer -> expectedFromPeer(child, peer));
        };
    }

    /**
     * Applies the structural law to a source expression when a peer supplies
     * its base shape.  Only nilability at source-selected positions is added;
     * outer mutation permission is never invented.
     */
    static Optional<LyraType> expectedFromPeer(
            SyntaxNode.Expression source,
            LyraType peerType) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(peerType, "peerType");
        return derive(source, peerType);
    }

    /** Returns true for a result-producing path containing an unresolved nil. */
    static boolean containsContextFreeNil(SyntaxNode.Expression expression) {
        Objects.requireNonNull(expression, "expression");
        if (expression instanceof SyntaxNode.NilLiteral) {
            return true;
        }
        if (expression instanceof SyntaxNode.Block block) {
            return finalExpression(block)
                    .map(StructuralContextPlan::containsContextFreeNil)
                    .orElse(false);
        }
        if (expression instanceof SyntaxNode.ArrayLiteral array) {
            return array.elements().stream().anyMatch(StructuralContextPlan::containsContextFreeNil);
        }
        if (expression instanceof SyntaxNode.TupleLiteral tuple) {
            return tuple.elements().stream().anyMatch(StructuralContextPlan::containsContextFreeNil);
        }
        if (expression instanceof SyntaxNode.Conditional conditional) {
            return containsContextFreeNil(conditional.thenExpression())
                    || conditional.elseExpression()
                    .map(StructuralContextPlan::containsContextFreeNil)
                    .orElse(false);
        }
        if (expression instanceof SyntaxNode.Coalesce coalesce) {
            return containsContextFreeNil(coalesce.value())
                    || containsContextFreeNil(coalesce.fallback());
        }
        return false;
    }

    /** Returns true only when the expression's result is a bare, untyped nil. */
    static boolean isContextFreeNil(SyntaxNode.Expression expression) {
        Objects.requireNonNull(expression, "expression");
        if (expression instanceof SyntaxNode.NilLiteral) {
            return true;
        }
        return expression instanceof SyntaxNode.Block block
                && finalExpression(block).map(StructuralContextPlan::isContextFreeNil).orElse(false);
    }

    /**
     * Returns true when no source position in the expression can provide a
     * value base without an enclosing expected/peer contract.  A nil nested in
     * a homogeneous array can be resolved by a non-nil sibling; a nil tuple
     * member cannot borrow the base of another tuple position.
     */
    static boolean isBaseLessNil(SyntaxNode.Expression expression) {
        Objects.requireNonNull(expression, "expression");
        if (expression instanceof SyntaxNode.NilLiteral) {
            return true;
        }
        if (expression instanceof SyntaxNode.Block block) {
            return finalExpression(block)
                    .map(StructuralContextPlan::isBaseLessNil)
                    .orElse(false);
        }
        if (expression instanceof SyntaxNode.ArrayLiteral array) {
            return !array.elements().isEmpty()
                    && array.elements().stream().allMatch(StructuralContextPlan::isBaseLessNil);
        }
        if (expression instanceof SyntaxNode.TupleLiteral tuple) {
            return !tuple.elements().isEmpty()
                    && tuple.elements().stream().anyMatch(StructuralContextPlan::isBaseLessNil);
        }
        if (expression instanceof SyntaxNode.Conditional conditional) {
            if (conditional.elseExpression().isEmpty()) {
                return isBaseLessNil(conditional.thenExpression());
            }
            return isBaseLessNil(conditional.thenExpression())
                    && isBaseLessNil(conditional.elseExpression().orElseThrow());
        }
        if (expression instanceof SyntaxNode.Coalesce coalesce) {
            // Coalescing does not make a bare value-side #NIL inferable.  Its
            // value role still needs an expected @nil contract.
            return isBaseLessNil(coalesce.value())
                    || isBaseLessNil(coalesce.fallback());
        }
        return false;
    }

    /** Returns the first source span of an unresolved nil on a value path. */
    static Optional<SourceSpan> firstContextFreeNilSpan(SyntaxNode.Expression expression) {
        Objects.requireNonNull(expression, "expression");
        if (expression instanceof SyntaxNode.NilLiteral) {
            return Optional.of(expression.span());
        }
        if (expression instanceof SyntaxNode.Block block) {
            return finalExpression(block).flatMap(StructuralContextPlan::firstContextFreeNilSpan);
        }
        if (expression instanceof SyntaxNode.ArrayLiteral array) {
            return array.elements().stream()
                    .map(StructuralContextPlan::firstContextFreeNilSpan)
                    .flatMap(Optional::stream)
                    .findFirst();
        }
        if (expression instanceof SyntaxNode.TupleLiteral tuple) {
            return tuple.elements().stream()
                    .map(StructuralContextPlan::firstContextFreeNilSpan)
                    .flatMap(Optional::stream)
                    .findFirst();
        }
        if (expression instanceof SyntaxNode.Conditional conditional) {
            return firstContextFreeNilSpan(conditional.thenExpression())
                    .or(() -> conditional.elseExpression()
                            .flatMap(StructuralContextPlan::firstContextFreeNilSpan));
        }
        if (expression instanceof SyntaxNode.Coalesce coalesce) {
            return firstContextFreeNilSpan(coalesce.value())
                    .or(() -> firstContextFreeNilSpan(coalesce.fallback()));
        }
        return Optional.empty();
    }

    /** True when a source expression can receive a recursive structural plan. */
    static boolean canRecheckWithStructuralContext(SyntaxNode.Expression expression) {
        Objects.requireNonNull(expression, "expression");
        if (expression instanceof SyntaxNode.ArrayLiteral
                || expression instanceof SyntaxNode.TupleLiteral
                || expression instanceof SyntaxNode.Conditional
                || expression instanceof SyntaxNode.Coalesce) {
            return true;
        }
        return expression instanceof SyntaxNode.Block block
                && finalExpression(block)
                .map(StructuralContextPlan::canRecheckWithStructuralContext)
                .orElse(false);
    }

    /**
     * Folds source-derived homogeneous shapes.  Numeric values use the
     * ordinary TypeRules common type; arrays and tuples fold recursively so a
     * nested literal can be made homogeneous without flattening its shape.
     */
    static Optional<LyraType> foldHomogeneous(List<? extends LyraType> types) {
        Objects.requireNonNull(types, "types");
        if (types.isEmpty() || types.stream().anyMatch(Objects::isNull)) {
            return Optional.empty();
        }
        if (types.stream().allMatch(type -> type.withoutQualifiers() instanceof ArrayType)) {
            boolean nilable = types.stream().anyMatch(LyraType::isNilable);
            List<LyraType> elements = types.stream()
                    .map(type -> ((ArrayType) type.withoutQualifiers()).elementType())
                    .toList();
            return foldHomogeneous(elements).map(ArrayType::of)
                    .map(type -> nilable ? type.nilable() : type);
        }
        if (types.stream().allMatch(type -> type.withoutQualifiers() instanceof TupleType)) {
            boolean nilable = types.stream().anyMatch(LyraType::isNilable);
            List<TupleType> tuples = types.stream()
                    .map(type -> (TupleType) type.withoutQualifiers())
                    .toList();
            int arity = tuples.getFirst().arity();
            if (tuples.stream().anyMatch(tuple -> tuple.arity() != arity)) {
                return Optional.empty();
            }
            List<LyraType> members = new ArrayList<>(arity);
            for (int index = 0; index < arity; index++) {
                int memberIndex = index;
                List<LyraType> memberTypes = tuples.stream()
                        .map(tuple -> tuple.memberType(memberIndex))
                        .toList();
                Optional<LyraType> common = foldHomogeneous(memberTypes);
                if (common.isEmpty()) {
                    return Optional.empty();
                }
                members.add(common.orElseThrow());
            }
            LyraType result = TupleType.of(members);
            return Optional.of(nilable ? result.nilable() : result);
        }
        return TypeRules.commonType(types);
    }

    /**
     * Independently synthesizes one structural value from source syntax.  The
     * callback is consulted only for atomic source expressions; aggregate and
     * control structures are folded by this class so incomplete child shapes
     * can borrow a base from a sibling position.
     */
    static Optional<LyraType> synthesize(
            SyntaxNode.Expression source,
            Function<SyntaxNode.Expression, Optional<LyraType>> atomicType) {
        return synthesize(source, atomicType, ignored -> Optional.empty(), ignored -> Optional.empty());
    }

    static Optional<LyraType> synthesize(
            SyntaxNode.Expression source,
            Function<SyntaxNode.Expression, Optional<LyraType>> atomicType,
            Function<SyntaxNode.Type, Optional<LyraType>> explicitType) {
        return synthesize(source, atomicType, explicitType, ignored -> Optional.empty());
    }

    static Optional<LyraType> synthesize(
            SyntaxNode.Expression source,
            Function<SyntaxNode.Expression, Optional<LyraType>> atomicType,
            Function<SyntaxNode.Type, Optional<LyraType>> explicitType,
            Function<SyntaxNode.Expression, Optional<LyraType>> conditionalType) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(atomicType, "atomicType");
        Objects.requireNonNull(explicitType, "explicitType");
        Objects.requireNonNull(conditionalType, "conditionalType");
        return resolvePeers(List.of(sourceShape(source, atomicType, explicitType, conditionalType)));
    }

    /** Independently folds one homogeneous array's source element peers. */
    static Optional<LyraType> synthesizeArrayElements(
            List<SyntaxNode.Expression> elements,
            Function<SyntaxNode.Expression, Optional<LyraType>> atomicType) {
        return synthesizeArrayElements(
                elements, atomicType, ignored -> Optional.empty(), ignored -> Optional.empty());
    }

    static Optional<LyraType> synthesizeArrayElements(
            List<SyntaxNode.Expression> elements,
            Function<SyntaxNode.Expression, Optional<LyraType>> atomicType,
            Function<SyntaxNode.Type, Optional<LyraType>> explicitType) {
        return synthesizeArrayElements(elements, atomicType, explicitType, ignored -> Optional.empty());
    }

    static Optional<LyraType> synthesizeArrayElements(
            List<SyntaxNode.Expression> elements,
            Function<SyntaxNode.Expression, Optional<LyraType>> atomicType,
            Function<SyntaxNode.Type, Optional<LyraType>> explicitType,
            Function<SyntaxNode.Expression, Optional<LyraType>> conditionalType) {
        Objects.requireNonNull(elements, "elements");
        Objects.requireNonNull(atomicType, "atomicType");
        Objects.requireNonNull(explicitType, "explicitType");
        Objects.requireNonNull(conditionalType, "conditionalType");
        return resolvePeers(elements.stream()
                .map(element -> sourceShape(element, atomicType, explicitType, conditionalType))
                .toList());
    }

    /** Independently folds peer expressions, including partial tuple/array shapes. */
    static Optional<LyraType> synthesizePeers(
            List<SyntaxNode.Expression> peers,
            Function<SyntaxNode.Expression, Optional<LyraType>> atomicType) {
        return synthesizePeers(
                peers, atomicType, ignored -> Optional.empty(), ignored -> Optional.empty());
    }

    static Optional<LyraType> synthesizePeers(
            List<SyntaxNode.Expression> peers,
            Function<SyntaxNode.Expression, Optional<LyraType>> atomicType,
            Function<SyntaxNode.Type, Optional<LyraType>> explicitType) {
        return synthesizePeers(peers, atomicType, explicitType, ignored -> Optional.empty());
    }

    static Optional<LyraType> synthesizePeers(
            List<SyntaxNode.Expression> peers,
            Function<SyntaxNode.Expression, Optional<LyraType>> atomicType,
            Function<SyntaxNode.Type, Optional<LyraType>> explicitType,
            Function<SyntaxNode.Expression, Optional<LyraType>> conditionalType) {
        Objects.requireNonNull(peers, "peers");
        Objects.requireNonNull(atomicType, "atomicType");
        Objects.requireNonNull(explicitType, "explicitType");
        Objects.requireNonNull(conditionalType, "conditionalType");
        return resolvePeers(peers.stream()
                .map(peer -> sourceShape(peer, atomicType, explicitType, conditionalType))
                .toList());
    }

    /** Merges only structural nilability, retaining the base shape of {@code base}. */
    static LyraType mergeNilShape(LyraType base, LyraType shaped) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(shaped, "shaped");
        LyraType baseCore = base.withoutQualifiers();
        LyraType shapedCore = shaped.withoutQualifiers();
        LyraType result;
        if (baseCore instanceof ArrayType baseArray && shapedCore instanceof ArrayType shapedArray) {
            if (!baseArray.equals(shapedArray)) {
                LyraType mergedElement = mergeNilShape(
                        baseArray.elementType(), shapedArray.elementType());
                result = ArrayType.of(mergedElement);
            } else {
                result = baseArray;
            }
        } else if (baseCore instanceof TupleType baseTuple
                && shapedCore instanceof TupleType shapedTuple
                && baseTuple.arity() == shapedTuple.arity()) {
            List<LyraType> members = new ArrayList<>(baseTuple.arity());
            for (int index = 0; index < baseTuple.arity(); index++) {
                members.add(mergeNilShape(
                        baseTuple.memberType(index), shapedTuple.memberType(index)));
            }
            result = TupleType.of(members);
        } else {
            result = baseCore;
        }
        if (base.isNilable() || shaped.isNilable()) {
            result = result.nilable();
        }
        return result;
    }

    private enum ShapeKind {
        UNKNOWN,
        VALUE,
        ARRAY,
        TUPLE,
        BLOCK,
        CONDITIONAL,
        COALESCE
    }

    private record SourceShape(
            ShapeKind kind,
            Optional<LyraType> type,
            List<SourceShape> children) {
        private SourceShape {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(type, "type");
            children = List.copyOf(Objects.requireNonNull(children, "children"));
        }

        private static SourceShape unknown() {
            return new SourceShape(ShapeKind.UNKNOWN, Optional.empty(), List.of());
        }

        private static SourceShape value(LyraType type) {
            return new SourceShape(ShapeKind.VALUE, Optional.of(type), List.of());
        }
    }

    private static SourceShape sourceShape(
            SyntaxNode.Expression source,
            Function<SyntaxNode.Expression, Optional<LyraType>> atomicType,
            Function<SyntaxNode.Type, Optional<LyraType>> explicitType,
            Function<SyntaxNode.Expression, Optional<LyraType>> conditionalType) {
        if (source instanceof SyntaxNode.NilLiteral) {
            return SourceShape.unknown();
        }
        if (source instanceof SyntaxNode.Block block) {
            Optional<SyntaxNode.Expression> finalValue = finalExpression(block);
            return finalValue.map(value -> new SourceShape(
                            ShapeKind.BLOCK, Optional.empty(),
                            List.of(sourceShape(value, atomicType, explicitType, conditionalType))))
                    .orElseGet(() -> SourceShape.value(io.mindspice.lyra.compiler.types.PrimitiveType.UNIT));
        }
        if (source instanceof SyntaxNode.ArrayLiteral array) {
            if (array.explicitType().isPresent()) {
                return array.explicitType().flatMap(explicitType)
                        .map(SourceShape::value)
                        .orElseGet(SourceShape::unknown);
            }
            return new SourceShape(
                    ShapeKind.ARRAY,
                    Optional.empty(),
                    array.elements().stream()
                            .map(value -> sourceShape(value, atomicType, explicitType, conditionalType))
                            .toList());
        }
        if (source instanceof SyntaxNode.TupleLiteral tuple) {
            if (tuple.explicitType().isPresent()) {
                return tuple.explicitType().flatMap(explicitType)
                        .map(SourceShape::value)
                        .orElseGet(SourceShape::unknown);
            }
            return new SourceShape(
                    ShapeKind.TUPLE,
                    Optional.empty(),
                    tuple.elements().stream()
                            .map(value -> sourceShape(value, atomicType, explicitType, conditionalType))
                            .toList());
        }
        if (source instanceof SyntaxNode.Conditional conditional) {
            if (conditional.elseExpression().isEmpty()) {
                return SourceShape.value(io.mindspice.lyra.compiler.types.PrimitiveType.UNIT);
            }
            Optional<LyraType> synthesized = conditionalType.apply(source);
            if (synthesized.isPresent()) {
                return SourceShape.value(synthesized.orElseThrow());
            }
            return new SourceShape(
                    ShapeKind.CONDITIONAL,
                    Optional.empty(),
                    List.of(
                            sourceShape(conditional.thenExpression(), atomicType, explicitType, conditionalType),
                            sourceShape(conditional.elseExpression().orElseThrow(), atomicType,
                                    explicitType, conditionalType)));
        }
        if (source instanceof SyntaxNode.Coalesce coalesce) {
            return new SourceShape(
                    ShapeKind.COALESCE,
                    Optional.empty(),
                    List.of(sourceShape(coalesce.value(), atomicType, explicitType, conditionalType),
                            sourceShape(coalesce.fallback(), atomicType, explicitType, conditionalType)));
        }
        return atomicType.apply(source)
                .map(SourceShape::value)
                .orElseGet(SourceShape::unknown);
    }

    private static Optional<LyraType> resolvePeers(List<SourceShape> rawShapes) {
        List<SourceShape> shapes = new ArrayList<>();
        for (SourceShape shape : rawShapes) {
            if (!appendPeerShapes(shape, shapes)) {
                return Optional.empty();
            }
        }
        if (shapes.isEmpty()) {
            return Optional.empty();
        }

        List<SourceShape> known = shapes.stream()
                .filter(shape -> shape.kind() != ShapeKind.UNKNOWN)
                .toList();
        boolean unknown = known.size() != shapes.size();
        if (known.isEmpty()) {
            return Optional.empty();
        }

        boolean arrays = known.stream().allMatch(StructuralContextPlan::isArrayShape);
        if (arrays) {
            Optional<LyraType> result = resolveArrayPeers(known);
            return result.map(value -> unknown ? value.nilable() : value);
        }
        boolean tuples = known.stream().allMatch(StructuralContextPlan::isTupleShape);
        if (tuples) {
            Optional<LyraType> result = resolveTuplePeers(known);
            return result.map(value -> unknown ? value.nilable() : value);
        }
        if (known.stream().anyMatch(shape -> shape.kind() != ShapeKind.VALUE)) {
            return Optional.empty();
        }
        List<LyraType> types = known.stream().map(shape -> shape.type().orElseThrow()).toList();
        Optional<LyraType> common = foldHomogeneous(types);
        if (common.isEmpty()) {
            return Optional.empty();
        }
        LyraType result = common.orElseThrow();
        return Optional.of(unknown ? result.nilable() : result);
    }

    private static boolean appendPeerShapes(
            SourceShape shape,
            List<SourceShape> destination) {
        switch (shape.kind()) {
            case BLOCK, CONDITIONAL -> {
                for (SourceShape child : shape.children()) {
                    if (!appendPeerShapes(child, destination)) {
                        return false;
                    }
                }
            }
            case COALESCE -> {
                if (shape.children().isEmpty()) {
                    return false;
                }
                Optional<LyraType> value = resolvePeers(List.of(shape.children().getFirst()));
                if (value.isEmpty() || !value.orElseThrow().isNilable()) {
                    return false;
                }
                destination.add(SourceShape.value(value.orElseThrow().withoutQualifiers()));
            }
            default -> destination.add(shape);
        }
        return true;
    }

    private static Optional<LyraType> resolveArrayPeers(List<SourceShape> shapes) {
        List<SourceShape> elements = new ArrayList<>();
        boolean nilable = false;
        for (SourceShape shape : shapes) {
            SourceShape array = asArrayShape(shape);
            if (array.type().isPresent() && array.type().orElseThrow().isNilable()) {
                nilable = true;
            }
            if (array.children().isEmpty()) {
                LyraType type = array.type().orElse(null);
                if (type == null || !(type.withoutQualifiers() instanceof ArrayType value)) {
                    return Optional.empty();
                }
                elements.add(SourceShape.value(value.elementType()));
            } else {
                elements.addAll(array.children());
            }
        }
        Optional<LyraType> element = resolvePeers(elements);
        if (element.isEmpty()) {
            return Optional.empty();
        }
        LyraType result = ArrayType.of(element.orElseThrow());
        return Optional.of(nilable ? result.nilable() : result);
    }

    private static Optional<LyraType> resolveTuplePeers(List<SourceShape> shapes) {
        List<SourceShape> tuples = shapes.stream()
                .map(StructuralContextPlan::asTupleShape).toList();
        int arity = tuples.getFirst().children().size();
        if (tuples.stream().anyMatch(tuple -> tuple.children().size() != arity)) {
            return Optional.empty();
        }
        List<LyraType> members = new ArrayList<>(arity);
        for (int index = 0; index < arity; index++) {
            int memberIndex = index;
            Optional<LyraType> member = resolvePeers(tuples.stream()
                    .map(tuple -> tuple.children().get(memberIndex))
                    .toList());
            if (member.isEmpty()) {
                return Optional.empty();
            }
            members.add(member.orElseThrow());
        }
        boolean nilable = tuples.stream().anyMatch(tuple ->
                tuple.type().isPresent() && tuple.type().orElseThrow().isNilable());
        LyraType result = TupleType.of(members);
        return Optional.of(nilable ? result.nilable() : result);
    }

    private static boolean isArrayShape(SourceShape shape) {
        return shape.kind() == ShapeKind.ARRAY
                || shape.kind() == ShapeKind.VALUE
                && shape.type().map(type -> type.withoutQualifiers() instanceof ArrayType).orElse(false);
    }

    private static boolean isTupleShape(SourceShape shape) {
        return shape.kind() == ShapeKind.TUPLE
                || shape.kind() == ShapeKind.VALUE
                && shape.type().map(type -> type.withoutQualifiers() instanceof TupleType).orElse(false);
    }

    private static SourceShape asArrayShape(SourceShape shape) {
        if (shape.kind() == ShapeKind.ARRAY) {
            return shape;
        }
        LyraType type = shape.type().orElseThrow();
        ArrayType array = (ArrayType) type.withoutQualifiers();
        return new SourceShape(ShapeKind.ARRAY, Optional.of(type),
                List.of(SourceShape.value(array.elementType())));
    }

    private static SourceShape asTupleShape(SourceShape shape) {
        if (shape.kind() == ShapeKind.TUPLE) {
            return shape;
        }
        LyraType type = shape.type().orElseThrow();
        TupleType tuple = (TupleType) type.withoutQualifiers();
        return new SourceShape(
                ShapeKind.TUPLE,
                Optional.of(type),
                tuple.memberTypes().stream().map(SourceShape::value).toList());
    }

    private static Optional<LyraType> expectedAggregateElement(
            SyntaxNode.Expression child,
            Optional<LyraType> expectedParent,
            Optional<LyraType> peerType,
            int index,
            boolean tuple) {
        Optional<LyraType> parent = expectedParent.isPresent()
                ? expectedParent.map(LyraType::withoutQualifiers)
                : peerType.map(LyraType::withoutQualifiers);
        if (parent.isEmpty()) {
            return Optional.empty();
        }
        LyraType base = parent.orElseThrow();
        LyraType member;
        if (tuple) {
            if (!(base instanceof TupleType tupleType) || index >= tupleType.arity()) {
                return Optional.empty();
            }
            member = tupleType.memberType(index);
        } else {
            if (!(base instanceof ArrayType arrayType)) {
                return Optional.empty();
            }
            member = arrayType.elementType();
        }
        if (expectedParent.isPresent() || !containsContextFreeNil(child)) {
            return Optional.of(member);
        }
        return expectedFromPeer(child, member);
    }

    private static Optional<LyraType> expectedCoalesceValue(Optional<LyraType> expectedParent) {
        if (expectedParent.isEmpty() || !expectedParent.orElseThrow().isNilable()) {
            return Optional.empty();
        }
        LyraType expected = withoutMutability(expectedParent.orElseThrow());
        return Optional.of(expected.withoutQualifiers().nilable());
    }

    private static Optional<LyraType> expectedCoalesceFallback(Optional<LyraType> expectedParent) {
        return expectedParent.map(value -> value.withoutQualifiers());
    }

    private static Optional<LyraType> derive(
            SyntaxNode.Expression source,
            LyraType peerType) {
        LyraType peer = peerType.withoutQualifiers();
        boolean outerNilable = peerType.isNilable();
        if (source instanceof SyntaxNode.NilLiteral) {
            return Optional.of(peer.nilable());
        }
        if (source instanceof SyntaxNode.Block block) {
            return finalExpression(block)
                    .map(finalExpression -> derive(finalExpression, peerType))
                    .orElse(Optional.of(peerType));
        }
        if (source instanceof SyntaxNode.ArrayLiteral array) {
            if (!(peer instanceof ArrayType peerArray)) {
                return Optional.of(peer.nilable());
            }
            LyraType element = peerArray.elementType();
            for (SyntaxNode.Expression child : array.elements()) {
                if (!containsContextFreeNil(child)) {
                    continue;
                }
                Optional<LyraType> shaped = derive(child, element);
                if (shaped.isEmpty()) {
                    return Optional.empty();
                }
                element = mergeNilShape(element, shaped.orElseThrow());
            }
            LyraType result = ArrayType.of(element);
            return Optional.of(outerNilable ? result.nilable() : result);
        }
        if (source instanceof SyntaxNode.TupleLiteral tuple) {
            if (!(peer instanceof TupleType peerTuple)
                    || peerTuple.arity() != tuple.elements().size()) {
                return Optional.of(peer.nilable());
            }
            List<LyraType> members = new ArrayList<>(peerTuple.memberTypes());
            for (int index = 0; index < tuple.elements().size(); index++) {
                SyntaxNode.Expression child = tuple.elements().get(index);
                if (!containsContextFreeNil(child)) {
                    continue;
                }
                Optional<LyraType> shaped = derive(child, members.get(index));
                if (shaped.isEmpty()) {
                    return Optional.empty();
                }
                members.set(index, mergeNilShape(members.get(index), shaped.orElseThrow()));
            }
            LyraType result = TupleType.of(members);
            return Optional.of(outerNilable ? result.nilable() : result);
        }
        if (source instanceof SyntaxNode.Conditional conditional) {
            if (conditional.elseExpression().isEmpty()) {
                return Optional.of(peer);
            }
            LyraType result = peerType;
            for (SyntaxNode.Expression branch : List.of(
                    conditional.thenExpression(), conditional.elseExpression().orElseThrow())) {
                if (!containsContextFreeNil(branch)) {
                    continue;
                }
                Optional<LyraType> shaped = derive(branch, peerType);
                if (shaped.isEmpty()) {
                    return Optional.empty();
                }
                result = mergeNilShape(result, shaped.orElseThrow());
            }
            return Optional.of(result);
        }
        if (source instanceof SyntaxNode.Coalesce) {
            // The coalesce result is its non-nil fallback/value base.  Its
            // children receive separate COALESCE_VALUE/FALLBACK contexts.
            return Optional.of(peer);
        }
        return Optional.of(peer);
    }

    private static Optional<SyntaxNode.Expression> finalExpression(SyntaxNode.Block block) {
        if (block.forms().isEmpty()) {
            return Optional.empty();
        }
        SyntaxNode.Form form = block.forms().getLast();
        return form instanceof SyntaxNode.Expression expression
                ? Optional.of(expression) : Optional.empty();
    }

    private static LyraType withoutMutability(LyraType type) {
        if (!type.hasQualifier(TypeQualifier.MUT)) {
            return type;
        }
        return type.withoutQualifiers().withQualifier(TypeQualifier.NIL);
    }
}
