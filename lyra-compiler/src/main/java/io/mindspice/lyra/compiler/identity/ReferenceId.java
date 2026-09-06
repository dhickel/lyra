package io.mindspice.lyra.compiler.identity;

import java.util.Objects;

/** Deterministic compilation-local identifier-reference identity. */
public record ReferenceId(long ordinal) implements Comparable<ReferenceId> {
    public ReferenceId {
        if (ordinal < 0) {
            throw new IllegalArgumentException("reference ordinal must not be negative");
        }
    }

    public long value() {
        return ordinal;
    }

    public long id() {
        return ordinal;
    }

    @Override
    public int compareTo(ReferenceId other) {
        return Long.compare(ordinal, Objects.requireNonNull(other, "other").ordinal);
    }

    @Override
    public String toString() {
        return "reference#" + ordinal;
    }
}
