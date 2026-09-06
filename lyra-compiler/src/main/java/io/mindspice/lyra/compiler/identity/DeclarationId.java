package io.mindspice.lyra.compiler.identity;

import java.util.Objects;

/** Deterministic compilation-local declaration identity. */
public record DeclarationId(long ordinal) implements Comparable<DeclarationId> {
    public DeclarationId {
        if (ordinal < 0) {
            throw new IllegalArgumentException("declaration ordinal must not be negative");
        }
    }

    public long value() {
        return ordinal;
    }

    public long id() {
        return ordinal;
    }

    @Override
    public int compareTo(DeclarationId other) {
        return Long.compare(ordinal, Objects.requireNonNull(other, "other").ordinal);
    }

    @Override
    public String toString() {
        return "declaration#" + ordinal;
    }
}
