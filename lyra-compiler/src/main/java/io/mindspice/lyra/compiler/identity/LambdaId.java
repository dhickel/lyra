package io.mindspice.lyra.compiler.identity;

import java.util.Objects;

/** Deterministic compilation-local lambda-expression identity. */
public record LambdaId(long ordinal) implements Comparable<LambdaId> {
    public LambdaId {
        if (ordinal < 0) {
            throw new IllegalArgumentException("lambda ordinal must not be negative");
        }
    }

    public long value() {
        return ordinal;
    }

    public long id() {
        return ordinal;
    }

    @Override
    public int compareTo(LambdaId other) {
        return Long.compare(ordinal, Objects.requireNonNull(other, "other").ordinal);
    }

    @Override
    public String toString() {
        return "lambda#" + ordinal;
    }
}
