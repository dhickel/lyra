package io.mindspice.lyra.compiler.semantic.flow;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * One typed selector in a semantic projection path.
 *
 * <p>Tuple members and array elements are deliberately different variants.
 * An unknown array index is represented by {@link UnknownArrayElement}; it is
 * not a tuple selector and it is not an exact array index.</p>
 */
public sealed interface ProjectionStep extends Comparable<ProjectionStep>
        permits ProjectionStep.TupleMember, ProjectionStep.ArrayElement,
        ProjectionStep.UnknownArrayElement, ProjectionStep.NominalMember {
    /** Stable ordering category used by canonical route ordering. */
    enum Kind {
        TUPLE_MEMBER,
        ARRAY_ELEMENT,
        UNKNOWN_ARRAY_ELEMENT,
        NOMINAL_MEMBER
    }

    Kind kind();

    /** Returns an exact index when this step denotes one. */
    OptionalInt exactIndex();

    default boolean isTupleMember() {
        return kind() == Kind.TUPLE_MEMBER;
    }

    default boolean isArrayElement() {
        return kind() == Kind.ARRAY_ELEMENT || kind() == Kind.UNKNOWN_ARRAY_ELEMENT;
    }

    default boolean isWildcardArrayElement() {
        return kind() == Kind.UNKNOWN_ARRAY_ELEMENT;
    }

    /**
     * Returns whether the two selectors can denote the same projected value.
     * Wildcards overlap exact array indices, but never tuple members.
     */
    default boolean overlaps(ProjectionStep other) {
        Objects.requireNonNull(other, "other");
        if (this instanceof NominalMember || other instanceof NominalMember) return equals(other);
        if (isTupleMember() || other.isTupleMember()) {
            return this instanceof TupleMember left
                    && other instanceof TupleMember right
                    && left.index() == right.index();
        }
        if (this instanceof UnknownArrayElement || other instanceof UnknownArrayElement) {
            return true;
        }
        return ((ArrayElement) this).index() == ((ArrayElement) other).index();
    }

    /** Returns whether this selector selects the value denoted by {@code other}. */
    default boolean selects(ProjectionStep other) {
        Objects.requireNonNull(other, "other");
        if (this instanceof NominalMember || other instanceof NominalMember) return equals(other);
        if (this instanceof TupleMember left) {
            return other instanceof TupleMember right && left.index() == right.index();
        }
        if (this instanceof ArrayElement left) {
            return other instanceof ArrayElement right && left.index() == right.index();
        }
        return other instanceof ArrayElement || other instanceof UnknownArrayElement;
    }

    static TupleMember tupleMember(int index) {
        return new TupleMember(index);
    }

    static ArrayElement arrayElement(int index) {
        return new ArrayElement(index);
    }

    static UnknownArrayElement unknownArrayElement() {
        return UnknownArrayElement.INSTANCE;
    }

    static UnknownArrayElement wildcardArrayElement() {
        return UnknownArrayElement.INSTANCE;
    }

    @Override
    default int compareTo(ProjectionStep other) {
        Objects.requireNonNull(other, "other");
        int kindComparison = Integer.compare(kind().ordinal(), other.kind().ordinal());
        if (kindComparison != 0) {
            return kindComparison;
        }
        if (this instanceof TupleMember left && other instanceof TupleMember right) {
            return Integer.compare(left.index(), right.index());
        }
        if (this instanceof ArrayElement left && other instanceof ArrayElement right) {
            return Integer.compare(left.index(), right.index());
        }
        if (this instanceof NominalMember left && other instanceof NominalMember right) {
            int owner = left.owner().canonicalSpelling().compareTo(right.owner().canonicalSpelling());
            if (owner != 0) return owner;
            int slot = Integer.compare(left.index(), right.index());
            return slot != 0 ? slot : left.type().canonicalSpelling().compareTo(right.type().canonicalSpelling());
        }
        return 0;
    }

    /** Exact nominal slot contract; validated against the closed schema at publication. */
    record NominalMember(io.mindspice.lyra.compiler.types.NominalType owner, int index,
                         io.mindspice.lyra.compiler.types.LyraType type) implements ProjectionStep {
        public NominalMember {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(type, "type");
            if (index < 0 || type.isMutable()) throw new IllegalArgumentException("invalid nominal slot contract");
        }
        @Override public Kind kind() { return Kind.NOMINAL_MEMBER; }
        @Override public OptionalInt exactIndex() { return OptionalInt.of(index); }
        @Override public String toString() { return ".{" + owner.canonicalSpelling() + ":" + index + ":" + type.canonicalSpelling() + "}"; }
    }

    /** An exact positional tuple-member selector. */
    record TupleMember(int index) implements ProjectionStep {
        public TupleMember {
            if (index < 0) {
                throw new IllegalArgumentException("tuple member index must not be negative");
            }
        }

        @Override
        public Kind kind() {
            return Kind.TUPLE_MEMBER;
        }

        @Override
        public OptionalInt exactIndex() {
            return OptionalInt.of(index);
        }

        @Override
        public String toString() {
            return "." + index;
        }
    }

    /** An exact positional array-element selector. */
    record ArrayElement(int index) implements ProjectionStep {
        public ArrayElement {
            if (index < 0) {
                throw new IllegalArgumentException("array element index must not be negative");
            }
        }

        @Override
        public Kind kind() {
            return Kind.ARRAY_ELEMENT;
        }

        @Override
        public OptionalInt exactIndex() {
            return OptionalInt.of(index);
        }

        @Override
        public String toString() {
            return "[" + index + "]";
        }
    }

    /** A wildcard array-element selector for an unknown index. */
    record UnknownArrayElement() implements ProjectionStep {
        private static final UnknownArrayElement INSTANCE = new UnknownArrayElement();

        @Override
        public Kind kind() {
            return Kind.UNKNOWN_ARRAY_ELEMENT;
        }

        @Override
        public OptionalInt exactIndex() {
            return OptionalInt.empty();
        }

        @Override
        public String toString() {
            return "[*]";
        }
    }
}
