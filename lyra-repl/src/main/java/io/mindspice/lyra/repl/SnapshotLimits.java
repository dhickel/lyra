package io.mindspice.lyra.repl;

import java.util.Objects;

/**
 * Fixed bounds for one immutable value snapshot. Bounds apply recursively to
 * every aggregate below the snapshot root.
 */
public record SnapshotLimits(
        int maxDepth,
        int maxAggregateElements,
        int maxRenderedCharacters) {
    public static final int DEFAULT_MAX_DEPTH = 6;
    public static final int DEFAULT_MAX_AGGREGATE_ELEMENTS = 100;
    public static final int DEFAULT_MAX_RENDERED_CHARACTERS = 16 * 1024;
    public static final SnapshotLimits DEFAULT = new SnapshotLimits(
            DEFAULT_MAX_DEPTH,
            DEFAULT_MAX_AGGREGATE_ELEMENTS,
            DEFAULT_MAX_RENDERED_CHARACTERS);

    public SnapshotLimits {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must not be negative: " + maxDepth);
        }
        if (maxAggregateElements < 0) {
            throw new IllegalArgumentException(
                    "maxAggregateElements must not be negative: " + maxAggregateElements);
        }
        if (maxRenderedCharacters <= 0) {
            throw new IllegalArgumentException(
                    "maxRenderedCharacters must be positive: " + maxRenderedCharacters);
        }
    }

    public int maxRenderedOutput() {
        return maxRenderedCharacters;
    }

    /** Re-checks a snapshot against these bounds without changing it. */
    public void validate(ValueSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        ValueSnapshot.validateAgainst(snapshot.type(), snapshot.data(), this);
    }
}
