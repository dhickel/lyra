package io.mindspice.lyra.compiler.grammar;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Immutable token-index metadata retained by a replay descriptor.
 *
 * <p>{@code -1} means that a singular delimiter or primary token is not
 * present.  Indexed collections are sorted in source order and contain no
 * duplicates.  The descriptor owning this metadata additionally verifies that
 * every index is inside its own range.</p>
 */
public record DescriptorMetadata(
        int openingTokenIndex,
        int closingTokenIndex,
        int primaryTokenIndex,
        List<Integer> commaTokenIndices,
        List<Integer> modifierTokenIndices,
        List<Integer> operatorTokenIndices) {
    public static final DescriptorMetadata NONE = new DescriptorMetadata(
            -1, -1, -1, List.of(), List.of(), List.of());

    public DescriptorMetadata {
        validateSingularIndex(openingTokenIndex, "openingTokenIndex");
        validateSingularIndex(closingTokenIndex, "closingTokenIndex");
        validateSingularIndex(primaryTokenIndex, "primaryTokenIndex");
        commaTokenIndices = normalizedIndices(commaTokenIndices, "commaTokenIndices");
        modifierTokenIndices = normalizedIndices(modifierTokenIndices, "modifierTokenIndices");
        operatorTokenIndices = normalizedIndices(operatorTokenIndices, "operatorTokenIndices");
    }

    public OptionalInt openingToken() {
        return optionalIndex(openingTokenIndex);
    }

    public OptionalInt closingToken() {
        return optionalIndex(closingTokenIndex);
    }

    public OptionalInt primaryToken() {
        return optionalIndex(primaryTokenIndex);
    }

    public boolean hasDelimiters() {
        return openingTokenIndex >= 0 && closingTokenIndex >= 0;
    }

    private static void validateSingularIndex(int index, String name) {
        if (index < -1) {
            throw new IllegalArgumentException(name + " must be -1 or a token index");
        }
    }

    private static List<Integer> normalizedIndices(List<Integer> indices, String name) {
        Objects.requireNonNull(indices, name);
        int previous = -1;
        for (Integer index : indices) {
            Objects.requireNonNull(index, name + " must not contain null");
            if (index < 0) {
                throw new IllegalArgumentException(name + " must contain non-negative token indices");
            }
            if (index <= previous) {
                throw new IllegalArgumentException(name + " must be strictly increasing");
            }
            previous = index;
        }
        return List.copyOf(indices);
    }

    private static OptionalInt optionalIndex(int index) {
        return index < 0 ? OptionalInt.empty() : OptionalInt.of(index);
    }
}
