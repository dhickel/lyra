package io.mindspice.lyra.repl;

import java.util.Objects;
import java.util.UUID;

/** Opaque identity for one submitted evaluation request. */
public record EvaluationId(UUID value) implements Comparable<EvaluationId> {
    public EvaluationId {
        Objects.requireNonNull(value, "value");
    }

    public static EvaluationId create() {
        return new EvaluationId(UUID.randomUUID());
    }

    public static EvaluationId of(String value) {
        Objects.requireNonNull(value, "value");
        try {
            return new EvaluationId(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid evaluation identity: " + value, exception);
        }
    }

    public static EvaluationId of(UUID value) {
        return new EvaluationId(value);
    }

    @Override
    public int compareTo(EvaluationId other) {
        Objects.requireNonNull(other, "other");
        int most = Long.compareUnsigned(value.getMostSignificantBits(), other.value.getMostSignificantBits());
        return most != 0
                ? most
                : Long.compareUnsigned(value.getLeastSignificantBits(), other.value.getLeastSignificantBits());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
