package io.mindspice.lyra.repl;

import io.mindspice.lyra.runtime.ArrayType;
import io.mindspice.lyra.runtime.FunctionType;
import io.mindspice.lyra.runtime.LyraClosure;
import io.mindspice.lyra.runtime.LyraClosureSupport;
import io.mindspice.lyra.runtime.LyraLinkException;
import io.mindspice.lyra.runtime.LyraNominalObject;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.LyraType;
import io.mindspice.lyra.runtime.LyraUnit;
import io.mindspice.lyra.runtime.ModuleHandle;
import io.mindspice.lyra.runtime.OwnerThread;
import io.mindspice.lyra.runtime.PrimitiveType;
import io.mindspice.lyra.runtime.NominalSchema;
import io.mindspice.lyra.runtime.NominalType;
import io.mindspice.lyra.runtime.NominalTypeEnvironment;
import io.mindspice.lyra.runtime.TupleType;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Owner-only, bounded inspection of generated results. Never invokes a source function or toString. */
final class SnapshotReader {
    private final OwnerThread owner = OwnerThread.capture();
    private final SnapshotLimits limits;
    private final Set<String> generatedClasses;
    private final NominalTypeEnvironment nominalSchemas;
    private final IdentityHashMap<Object, String> identities = new IdentityHashMap<>();
    private int remaining;

    private SnapshotReader(SnapshotLimits limits, Set<String> generatedClasses,
                           NominalTypeEnvironment nominalSchemas) {
        this.limits = limits;
        this.generatedClasses = Set.copyOf(generatedClasses);
        this.nominalSchemas = nominalSchemas;
        remaining = limits.maxRenderedCharacters();
    }

    static boolean canRepresent(LyraType type, SnapshotLimits limits) {
        return ValueSnapshot.renderedLength(type, new ValueSnapshot.Truncated(TruncationReason.RENDERED_OUTPUT))
                <= limits.maxRenderedCharacters();
    }

    static ValueSnapshot read(ModuleHandle module, LyraType type, SnapshotLimits limits,
                              Set<String> generatedClasses) {
        SnapshotReader reader = new SnapshotReader(limits, generatedClasses,
                module.metadata().nominalSchemas());
        return reader.value(type, LyraRuntime.readSubmissionResult(module, type), 0);
    }

    private ValueSnapshot value(LyraType type, Object value, int depth) {
        owner.check();
        if (value == null) return leaf(type, new ValueSnapshot.Nil());
        LyraType base = type.baseType();
        if (base instanceof PrimitiveType primitive) return scalar(type, primitive, value);
        if (base instanceof io.mindspice.lyra.runtime.RangeType rangeType) {
            if (!(value instanceof io.mindspice.lyra.runtime.LyraRange range)
                    || range.bits() != ((PrimitiveType) rangeType.elementType()).bitWidth()) {
                throw new LyraLinkException("range result does not match its element width");
            }
            return leaf(type, new ValueSnapshot.Scalar(ScalarKind.RANGE,
                    "(" + range.start() + (range.inclusive() ? "..." : "..") + range.end() + ":" + range.step() + ")"));
        }
        if (base instanceof FunctionType function) {
            if (value instanceof io.mindspice.lyra.runtime.LyraNominalMemberDelegate delegate) {
                delegate.checkUsable(function.signature());
            } else if (!(value instanceof LyraClosure closure) || !generatedClasses.contains(value.getClass().getName())) {
                throw new LyraLinkException("result is not a generated Lyra closure");
            } else {
                closure.checkInvocation(function.signature());
            }
            String previous = functionIdentity(value);
            if (previous != null) return leaf(type, new ValueSnapshot.Reference(previous));
            String identity = "fn" + (identities.size() + 1);
            identities.put(value, identity);
            return leaf(type, new ValueSnapshot.Function(identity));
        }

        if (base instanceof ArrayType || base instanceof NominalType) {
            String previous = identities.get(value);
            if (previous != null) return leaf(type, new ValueSnapshot.Reference(previous));
        }
        if (depth >= limits.maxDepth()) return leaf(type, new ValueSnapshot.Truncated(TruncationReason.DEPTH));
        NominalSchema nominal = base instanceof NominalType nominalType
                ? nominalSchemas.require(nominalType) : null;
        String identity = base instanceof ArrayType ? "array" + (identities.size() + 1)
                : nominal != null ? nominal.kind().name().toLowerCase() + (identities.size() + 1) : "tuple";
        if (base instanceof ArrayType || nominal != null) identities.put(value, identity);
        AggregateKind kind = base instanceof ArrayType ? AggregateKind.ARRAY
                : nominal == null ? AggregateKind.TUPLE
                : nominal.kind() == NominalSchema.Kind.STRUCT ? AggregateKind.STRUCT : AggregateKind.CLASS;
        List<Integer> visibleMembers = nominal == null ? List.of() : java.util.stream.IntStream
                .range(0, nominal.members().size())
                .filter(index -> nominal.members().get(index).publicAccess()).boxed().toList();
        int size = base instanceof ArrayType ? Array.getLength(value)
                : nominal != null ? visibleMembers.size() : ((TupleType) base).arity();
        // Reserve the largest aggregate suffix before descending. This prevents
        // wide/deep graphs from consuming an unbounded amount of traversal work.
        long overhead = ValueSnapshot.renderedLength(type, new ValueSnapshot.Aggregate(kind, identity,
                List.of(), Optional.of(TruncationReason.AGGREGATE_ELEMENTS)));
        if (overhead > remaining) return leaf(type, new ValueSnapshot.Truncated(TruncationReason.RENDERED_OUTPUT));
        remaining -= (int) overhead;
        List<ValueSnapshot> elements = new ArrayList<>();
        Optional<TruncationReason> truncated = Optional.empty();
        for (int index = 0; index < size; index++) {
            if (index == limits.maxAggregateElements()) {
                truncated = Optional.of(TruncationReason.AGGREGATE_ELEMENTS);
                break;
            }
            int memberIndex = nominal == null ? index : visibleMembers.get(index);
            LyraType element = base instanceof ArrayType array ? array.elementType()
                    : nominal != null ? nominal.members().get(memberIndex).type()
                    : ((TupleType) base).memberType(index);
            long minimum = ValueSnapshot.renderedLength(element,
                    new ValueSnapshot.Truncated(TruncationReason.RENDERED_OUTPUT));
            if (minimum + 1 > remaining) {
                truncated = Optional.of(TruncationReason.RENDERED_OUTPUT);
                break;
            }
            remaining--; // inter-element separator
            Object child = base instanceof ArrayType ? Array.get(value, index)
                    : nominal != null ? nominalField(value, nominal, memberIndex) : tupleField(value, index);
            elements.add(value(element, child, depth + 1));
        }
        return new ValueSnapshot(type, new ValueSnapshot.Aggregate(kind, identity,
                nominal == null ? Optional.empty() : Optional.of(nominal.type().id().name()),
                elements, truncated), limits);
    }

    /**
     * Delegates are fresh wrappers for each exact member occurrence, but
     * snapshots describe the selected Lyra closure rather than wrapper
     * allocation identity. Keep aggregate aliases stable without exposing
     * the closure identity itself or widening runtime authority.
     */
    private String functionIdentity(Object value) {
        String direct = identities.get(value);
        if (direct != null) return direct;
        for (var entry : identities.entrySet()) {
            Object seen = entry.getKey();
            if ((seen instanceof LyraClosure
                    || seen instanceof io.mindspice.lyra.runtime.LyraNominalMemberDelegate)
                    && LyraClosureSupport.sameIdentity(value, seen)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Object nominalField(Object object, NominalSchema schema, int index) {
        owner.check();
        if (!(object instanceof LyraNominalObject)
                || !generatedClasses.contains(object.getClass().getName())
                || !object.getClass().getSimpleName().equals(
                "$lyra$nominal$" + schema.type().id().stableHash())) {
            throw new LyraLinkException("result nominal is not its exact generated value class");
        }
        try {
            var getter = object.getClass().getMethod("$lyra$public$get$" + index);
            if (Modifier.isStatic(getter.getModifiers()) || getter.getParameterCount() != 0) {
                throw new LyraLinkException("result nominal has an invalid public component accessor");
            }
            return getter.invoke(object);
        } catch (ReflectiveOperationException failure) {
            throw new LyraLinkException("result nominal public component is unavailable", List.of(), failure);
        }
    }

    private Object tupleField(Object tuple, int index) {
        owner.check();
        if (!generatedClasses.contains(tuple.getClass().getName())
                || !tuple.getClass().getSimpleName().startsWith("$lyra$tuple$")) {
            throw new LyraLinkException("result tuple is not a generated value class");
        }
        try {
            Field field = tuple.getClass().getDeclaredField("$lyra$" + index);
            if (!Modifier.isPrivate(field.getModifiers()) || !Modifier.isFinal(field.getModifiers())
                    || Modifier.isStatic(field.getModifiers()) || !field.trySetAccessible()) {
                throw new LyraLinkException("result tuple has an invalid component layout");
            }
            return field.get(tuple);
        } catch (ReflectiveOperationException failure) {
            throw new LyraLinkException("result tuple component is unavailable", List.of(), failure);
        }
    }

    private ValueSnapshot scalar(LyraType type, PrimitiveType primitive, Object value) {
        if (primitive == PrimitiveType.STRING && ((String) value).length() > remaining) {
            return leaf(type, new ValueSnapshot.Truncated(TruncationReason.RENDERED_OUTPUT));
        }
        ValueSnapshot.Data data = switch (primitive) {
            case UNIT -> {
                if (value != LyraUnit.INSTANCE) throw new LyraLinkException("invalid Unit result");
                yield new ValueSnapshot.Unit();
            }
            case BOOL -> new ValueSnapshot.Scalar(ScalarKind.BOOLEAN, ((Boolean) value) ? "true" : "false");
            case CHAR -> new ValueSnapshot.Scalar(ScalarKind.CHARACTER, String.valueOf((Character) value));
            case STRING -> new ValueSnapshot.Scalar(ScalarKind.STRING, (String) value);
            case I8 -> signed(Byte.toString((Byte) value));
            case I16 -> signed(Short.toString((Short) value));
            case I32 -> signed(Integer.toString((Integer) value));
            case I64 -> signed(Long.toString((Long) value));
            case U8 -> unsigned(Integer.toString(Byte.toUnsignedInt((Byte) value)));
            case U16 -> unsigned(Integer.toString(Short.toUnsignedInt((Short) value)));
            case U32 -> unsigned(Integer.toUnsignedString((Integer) value));
            case U64 -> unsigned(Long.toUnsignedString((Long) value));
            case F32 -> new ValueSnapshot.Scalar(ScalarKind.FLOAT, Float.toString((Float) value).replace('E', 'e'));
            case F64 -> new ValueSnapshot.Scalar(ScalarKind.FLOAT, Double.toString((Double) value).replace('E', 'e'));
        };
        return leaf(type, data);
    }

    private static ValueSnapshot.Scalar signed(String value) {
        return new ValueSnapshot.Scalar(ScalarKind.SIGNED_INTEGER, value);
    }

    private static ValueSnapshot.Scalar unsigned(String value) {
        return new ValueSnapshot.Scalar(ScalarKind.UNSIGNED_INTEGER, value);
    }

    private ValueSnapshot leaf(LyraType type, ValueSnapshot.Data data) {
        long size = ValueSnapshot.renderedLength(type, data);
        if (size > remaining) {
            data = new ValueSnapshot.Truncated(TruncationReason.RENDERED_OUTPUT);
            size = ValueSnapshot.renderedLength(type, data);
        }
        if (size > remaining) throw new IllegalStateException("snapshot budget was not reserved");
        remaining -= (int) size;
        return new ValueSnapshot(type, data, limits);
    }
}
