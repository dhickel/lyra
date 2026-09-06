package io.mindspice.lyra.compiler.identity;

import java.util.Objects;

/** Deterministic compilation-local lexical-scope identity. */
public record ScopeId(long ordinal) implements Comparable<ScopeId> {
    public ScopeId {
        if (ordinal < 0) {
            throw new IllegalArgumentException("scope ordinal must not be negative");
        }
    }

    public long value() {
        return ordinal;
    }

    public long id() {
        return ordinal;
    }

    @Override
    public int compareTo(ScopeId other) {
        return Long.compare(ordinal, Objects.requireNonNull(other, "other").ordinal);
    }

    @Override
    public String toString() {
        return "scope#" + ordinal;
    }
}
