package io.mindspice.lyra.repl;

import io.mindspice.lyra.runtime.ArrayType;
import io.mindspice.lyra.runtime.FunctionType;
import io.mindspice.lyra.runtime.LyraType;
import io.mindspice.lyra.runtime.PrimitiveType;
import io.mindspice.lyra.runtime.TupleType;
import io.mindspice.lyra.runtime.TypeQualifier;

import java.math.BigInteger;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A bounded, immutable, data-only representation of one Lyra value.
 *
 * <p>Snapshots contain canonical Lyra types and display data, never live
 * values, Java handles, or executable callbacks. Child snapshots are checked
 * against the root's limits so a complete tree remains bounded.</p>
 */
public record ValueSnapshot(LyraType type, Data data, SnapshotLimits limits) {
    public ValueSnapshot {
        type = Objects.requireNonNull(type, "type");
        data = Objects.requireNonNull(data, "data");
        limits = Objects.requireNonNull(limits, "limits");
        validateAgainst(type, data, limits);
    }

    public ValueSnapshot(LyraType type, Data data) {
        this(type, data, SnapshotLimits.DEFAULT);
    }

    public static ValueSnapshot of(LyraType type, Data data) {
        return new ValueSnapshot(type, data);
    }

    public static ValueSnapshot of(LyraType type, Data data, SnapshotLimits limits) {
        return new ValueSnapshot(type, data, limits);
    }

    public static ValueSnapshot nil(LyraType type) {
        return of(type, new Nil());
    }

    public static ValueSnapshot unit(LyraType type) {
        return of(type, new Unit());
    }

    public static ValueSnapshot scalar(LyraType type, ScalarKind kind, String value) {
        return of(type, new Scalar(kind, value));
    }

    public static ValueSnapshot scalar(
            LyraType type, ScalarKind kind, String value, SnapshotLimits limits) {
        return of(type, new Scalar(kind, value), limits);
    }

    public static ValueSnapshot reference(LyraType type, String description) {
        return of(type, new Reference(description));
    }

    public static ValueSnapshot truncated(
            LyraType type, TruncationReason reason, String description) {
        return of(type, new Truncated(reason, Optional.of(description)));
    }

    public String canonicalType() {
        return type.canonicalSpelling();
    }

    /** Returns the conservative UTF-16 size of the data-only rendered form. */
    public int renderedCharacterCount() {
        return Math.toIntExact(renderedLength(type, data));
    }

    public boolean isTruncated() {
        return data instanceof Truncated
                || data instanceof Aggregate aggregate && aggregate.truncation().isPresent();
    }

    public boolean containsTruncation() {
        return containsTruncation(data);
    }

    /** Closed data variants used by a snapshot. */
    public sealed interface Data
            permits Nil, Unit, Scalar, Aggregate, Function, Reference, Truncated {
    }

    /** The distinct Lyra nil value. The enclosing type must be nilable. */
    public record Nil() implements Data {
    }

    /** The distinct Lyra Unit value. */
    public record Unit() implements Data {
    }

    /** A scalar value stored as canonical/display text rather than a Java value. */
    public record Scalar(ScalarKind kind, String value) implements Data {
        public Scalar {
            kind = Objects.requireNonNull(kind, "kind");
            value = Objects.requireNonNull(value, "value");
        }
    }

    /**
     * An array or tuple prefix. An optional alias is descriptive only, and a
     * truncation reason explicitly states why the expansion is incomplete.
     */
    public record Aggregate(
            AggregateKind kind,
            String identity,
            Optional<String> alias,
            List<ValueSnapshot> elements,
            Optional<TruncationReason> truncation) implements Data {
        public Aggregate {
            kind = Objects.requireNonNull(kind, "kind");
            identity = token(identity, "identity");
            alias = optionalText(alias, "alias");
            elements = copyElements(elements);
            truncation = Objects.requireNonNull(truncation, "truncation");
        }

        public Aggregate(AggregateKind kind, String identity, List<ValueSnapshot> elements) {
            this(kind, identity, Optional.empty(), elements, Optional.empty());
        }

        public Aggregate(
                AggregateKind kind,
                String identity,
                List<ValueSnapshot> elements,
                Optional<TruncationReason> truncation) {
            this(kind, identity, Optional.empty(), elements, truncation);
        }
    }

    /** A descriptive function value; no callable object crosses the boundary. */
    public record Function(String identity, Optional<String> description) implements Data {
        public Function {
            identity = token(identity, "identity");
            description = optionalText(description, "description");
        }

        public Function(String identity) {
            this(identity, Optional.empty());
        }
    }

    /** A bounded description of an aliased or cyclic value reference. */
    public record Reference(String description) implements Data {
        public Reference {
            description = token(description, "description");
        }
    }

    /** An explicit marker for data omitted by a snapshot budget. */
    public record Truncated(
            TruncationReason reason,
            Optional<String> description) implements Data {
        public Truncated {
            reason = Objects.requireNonNull(reason, "reason");
            description = optionalText(description, "description");
        }

        public Truncated(TruncationReason reason) {
            this(reason, Optional.empty());
        }
    }

    /** Package-visible so {@link SnapshotLimits} can validate an alternate budget. */
    static void validateAgainst(LyraType type, Data data, SnapshotLimits limits) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(limits, "limits");
        Set<ValueSnapshot> active = Collections.newSetFromMap(new IdentityHashMap<>());
        validateNode(type, data, limits, 0, active);
        long renderedLength = renderedLength(type, data);
        if (renderedLength > limits.maxRenderedCharacters()) {
            throw new IllegalArgumentException(
                    "snapshot rendered data exceeds maxRenderedCharacters: "
                            + renderedLength + " > " + limits.maxRenderedCharacters());
        }
    }

    private static void validateNode(
            LyraType type,
            Data data,
            SnapshotLimits limits,
            int depth,
            Set<ValueSnapshot> active) {
        if (depth > limits.maxDepth()) {
            throw new IllegalArgumentException(
                    "snapshot depth exceeds maxDepth: " + depth + " > " + limits.maxDepth());
        }
        if (type.hasQualifier(TypeQualifier.MUT)) {
            throw new IllegalArgumentException(
                    "snapshot type must not carry a binding @mut qualifier");
        }

        if (data instanceof Nil) {
            if (!type.isNilable()) {
                throw new IllegalArgumentException("nil snapshot requires a nilable type");
            }
        } else if (data instanceof Unit) {
            if (type.baseType() != PrimitiveType.UNIT) {
                throw new IllegalArgumentException("Unit snapshot requires a Unit type");
            }
        } else if (data instanceof Scalar scalar) {
            validateScalar(type, scalar);
        } else if (data instanceof Aggregate aggregate) {
            validateAggregate(type, aggregate, limits, depth, active);
        } else if (data instanceof Function function) {
            if (!(type.baseType() instanceof FunctionType)) {
                throw new IllegalArgumentException(
                        "function snapshot requires a function type: " + type);
            }
            if (function.identity().isBlank()) {
                throw new IllegalArgumentException("function identity must not be blank");
            }
        } else if (data instanceof Reference || data instanceof Truncated) {
            // The exact type is retained even when the value is not expanded.
        } else {
            throw new AssertionError("unhandled snapshot data: " + data.getClass());
        }
    }

    private static void validateScalar(LyraType type, Scalar scalar) {
        if (!(type.baseType() instanceof PrimitiveType primitive)) {
            throw new IllegalArgumentException(
                    "scalar snapshot requires a primitive type: " + type);
        }
        String value = scalar.value();
        switch (scalar.kind()) {
            case BOOLEAN -> {
                if (primitive != PrimitiveType.BOOL
                        || !(value.equals("true") || value.equals("false"))) {
                    throw new IllegalArgumentException("boolean snapshot data is not canonical");
                }
            }
            case CHARACTER -> {
                if (primitive != PrimitiveType.CHAR || value.length() != 1) {
                    throw new IllegalArgumentException(
                            "character snapshot data must contain one UTF-16 code unit");
                }
            }
            case STRING -> {
                if (primitive != PrimitiveType.STRING) {
                    throw new IllegalArgumentException("string snapshot data requires a String type");
                }
            }
            case SIGNED_INTEGER -> {
                if (!primitive.isSignedInteger()) {
                    throw new IllegalArgumentException(
                            "signed integer snapshot data requires a signed integer type");
                }
                validateInteger(value, primitive, true);
            }
            case UNSIGNED_INTEGER -> {
                if (!primitive.isUnsignedInteger()) {
                    throw new IllegalArgumentException(
                            "unsigned integer snapshot data requires an unsigned integer type");
                }
                validateInteger(value, primitive, false);
            }
            case FLOAT -> {
                if (!primitive.isFloating()) {
                    throw new IllegalArgumentException("float snapshot data requires a floating type");
                }
                validateFloat(value, primitive);
            }
        }
    }

    private static void validateInteger(String value, PrimitiveType type, boolean signed) {
        if (value.isEmpty() || containsWhitespace(value)) {
            throw new IllegalArgumentException("integer snapshot data must be canonical decimal text");
        }
        int firstDigit = value.charAt(0) == '-' ? 1 : 0;
        if (!signed && firstDigit == 1) {
            throw new IllegalArgumentException("unsigned integer snapshot data must not be negative");
        }
        if (firstDigit == value.length()) {
            throw new IllegalArgumentException("integer snapshot data has no digits");
        }
        for (int index = firstDigit; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                throw new IllegalArgumentException("integer snapshot data must be decimal text");
            }
        }
        if (value.length() > firstDigit + 1 && value.charAt(firstDigit) == '0') {
            throw new IllegalArgumentException("integer snapshot data must not have leading zeroes");
        }
        BigInteger number;
        try {
            number = new BigInteger(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("integer snapshot data is not valid", exception);
        }
        if (!number.toString().equals(value)) {
            throw new IllegalArgumentException("integer snapshot data is not canonical decimal text");
        }
        BigInteger minimum;
        BigInteger maximum;
        if (signed) {
            minimum = BigInteger.ONE.shiftLeft(type.bitWidth() - 1).negate();
            maximum = BigInteger.ONE.shiftLeft(type.bitWidth() - 1).subtract(BigInteger.ONE);
        } else {
            minimum = BigInteger.ZERO;
            maximum = BigInteger.ONE.shiftLeft(type.bitWidth()).subtract(BigInteger.ONE);
        }
        if (number.compareTo(minimum) < 0 || number.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    "integer snapshot data is outside " + type + " range: " + value);
        }
    }

    private static void validateFloat(String value, PrimitiveType type) {
        if (value.isEmpty() || containsWhitespace(value)) {
            throw new IllegalArgumentException("float snapshot data must not be blank or padded");
        }
        try {
            String canonical;
            if (type == PrimitiveType.F32) {
                float parsed = Float.parseFloat(value);
                if (!Float.isFinite(parsed)) {
                    throw new IllegalArgumentException("F32 snapshot data must be finite");
                }
                canonical = canonicalFloat(Float.toString(parsed));
            } else {
                double parsed = Double.parseDouble(value);
                if (!Double.isFinite(parsed)) {
                    throw new IllegalArgumentException("F64 snapshot data must be finite");
                }
                canonical = canonicalFloat(Double.toString(parsed));
            }
            if (!canonical.equals(value)) {
                throw new IllegalArgumentException(
                        "float snapshot data must use canonical decimal text: " + value);
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("float snapshot data is not valid: " + value, exception);
        }
    }

    private static String canonicalFloat(String value) {
        int exponent = value.indexOf('E');
        if (exponent < 0) {
            return value;
        }
        String mantissa = value.substring(0, exponent);
        String exponentText = value.substring(exponent + 1);
        boolean negative = exponentText.startsWith("-");
        if (negative || exponentText.startsWith("+")) {
            exponentText = exponentText.substring(1);
        }
        int first = 0;
        while (first + 1 < exponentText.length() && exponentText.charAt(first) == '0') {
            first++;
        }
        String normalized = exponentText.substring(first);
        return mantissa + "e" + (negative ? "-" : "") + normalized;
    }

    private static void validateAggregate(
            LyraType type,
            Aggregate aggregate,
            SnapshotLimits limits,
            int depth,
            Set<ValueSnapshot> active) {
        if (aggregate.elements().size() > limits.maxAggregateElements()) {
            throw new IllegalArgumentException(
                    "aggregate snapshot exceeds maxAggregateElements: "
                            + aggregate.elements().size() + " > " + limits.maxAggregateElements());
        }
        LyraType base = type.baseType();
        if (aggregate.kind() == AggregateKind.ARRAY) {
            if (!(base instanceof ArrayType arrayType)) {
                throw new IllegalArgumentException("array snapshot requires an array type: " + type);
            }
            for (ValueSnapshot element : aggregate.elements()) {
                validateChild(element, arrayType.elementType(), limits, depth, active);
            }
        } else {
            if (!(base instanceof TupleType tupleType)) {
                throw new IllegalArgumentException("tuple snapshot requires a tuple type: " + type);
            }
            if (aggregate.truncation().isEmpty()
                    && aggregate.elements().size() != tupleType.arity()) {
                throw new IllegalArgumentException(
                        "an untruncated tuple snapshot must contain every tuple member");
            }
            if (aggregate.elements().size() > tupleType.arity()) {
                throw new IllegalArgumentException("tuple snapshot contains too many members");
            }
            for (int index = 0; index < aggregate.elements().size(); index++) {
                validateChild(elementAt(aggregate, index), tupleType.memberType(index),
                        limits, depth, active);
            }
        }
    }

    private static ValueSnapshot elementAt(Aggregate aggregate, int index) {
        return aggregate.elements().get(index);
    }

    private static void validateChild(
            ValueSnapshot child,
            LyraType expectedType,
            SnapshotLimits limits,
            int depth,
            Set<ValueSnapshot> active) {
        Objects.requireNonNull(child, "aggregate elements must not contain null");
        if (!expectedType.equals(child.type())) {
            throw new IllegalArgumentException(
                    "aggregate element type " + child.type()
                            + " does not match " + expectedType);
        }
        if (!active.add(child)) {
            throw new IllegalArgumentException("snapshot data contains a cyclic object graph");
        }
        validateNode(child.type(), child.data(), limits, depth + 1, active);
        active.remove(child);
    }

    static long renderedLength(LyraType type, Data data) {
        long result = saturatingAdd(type.canonicalSpelling().length(), 1);
        if (data instanceof Nil) {
            return saturatingAdd(result, 3);
        }
        if (data instanceof Unit) {
            return saturatingAdd(result, 4);
        }
        if (data instanceof Scalar scalar) {
            long valueLength = scalar.kind() == ScalarKind.STRING
                    || scalar.kind() == ScalarKind.CHARACTER
                    ? saturatingAdd(escapedLength(scalar.value()), 2)
                    : scalar.value().length();
            return saturatingAdd(result, valueLength);
        }
        if (data instanceof Aggregate aggregate) {
            result = saturatingAdd(result, saturatingAdd(escapedLength(aggregate.identity()), 4));
            if (aggregate.alias().isPresent()) {
                result = saturatingAdd(result, saturatingAdd(escapedLength(aggregate.alias().get()), 1));
            }
            for (ValueSnapshot element : aggregate.elements()) {
                result = saturatingAdd(result,
                        saturatingAdd(renderedLength(element.type(), element.data()), 1));
            }
            if (aggregate.truncation().isPresent()) {
                result = saturatingAdd(result, aggregate.truncation().get().name().length() + 12L);
            }
            return result;
        }
        if (data instanceof Function function) {
            result = saturatingAdd(result, saturatingAdd(escapedLength(function.identity()), 4));
            return function.description().isPresent()
                    ? saturatingAdd(result, saturatingAdd(escapedLength(function.description().get()), 1))
                    : result;
        }
        if (data instanceof Reference reference) {
            return saturatingAdd(result, saturatingAdd(escapedLength(reference.description()), 12));
        }
        Truncated truncated = (Truncated) data;
        result = saturatingAdd(result, truncated.reason().name().length() + 14L);
        return truncated.description().isPresent()
                ? saturatingAdd(result, saturatingAdd(escapedLength(truncated.description().get()), 1))
                : result;
    }

    private static boolean containsTruncation(Data data) {
        if (data instanceof Truncated) {
            return true;
        }
        if (data instanceof Aggregate aggregate) {
            return aggregate.truncation().isPresent()
                    || aggregate.elements().stream().anyMatch(element -> containsTruncation(element.data()));
        }
        return false;
    }

    private static long escapedLength(String value) {
        long length = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\' || character == '"') {
                length = saturatingAdd(length, 2);
            } else if (character < 0x20 || character == 0x7F
                    || Character.isSurrogate(character)) {
                length = saturatingAdd(length, 6);
            } else {
                length = saturatingAdd(length, 1);
            }
        }
        return length;
    }

    private static long saturatingAdd(long first, long second) {
        if (second > 0 && first > Long.MAX_VALUE - second) {
            return Long.MAX_VALUE;
        }
        return first + second;
    }

    private static List<ValueSnapshot> copyElements(List<ValueSnapshot> values) {
        Objects.requireNonNull(values, "elements");
        for (ValueSnapshot value : values) {
            Objects.requireNonNull(value, "elements must not contain null");
        }
        return List.copyOf(values);
    }

    private static Optional<String> optionalText(Optional<String> value, String field) {
        Objects.requireNonNull(value, field);
        return value.map(text -> token(text, field));
    }

    private static String token(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character)) {
                throw new IllegalArgumentException(field + " must not contain control characters");
            }
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(field + " contains an unpaired surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException(field + " contains an unpaired surrogate");
            }
        }
        return value;
    }

    private static boolean containsWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }
}
