package io.mindspice.lyra.compiler.identity;

import java.util.Objects;

/** Compiler identity for the producer/storage owner of one retained module. */
public record ProducerId(long ordinal) implements Comparable<ProducerId> {
    public ProducerId {
        if (ordinal < 0) {
            throw new IllegalArgumentException("producer ordinal must not be negative");
        }
    }

    public long value() {
        return ordinal;
    }

    @Override
    public int compareTo(ProducerId other) {
        return Long.compare(ordinal, Objects.requireNonNull(other, "other").ordinal);
    }

    @Override
    public String toString() {
        return "producer#" + ordinal;
    }
}
