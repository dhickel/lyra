package io.mindspice.lyra.repl;

import java.util.Objects;

/** Session-local identity of mutable storage shared by a binding and captures. */
public record StorageIdentity(long ordinal) implements Comparable<StorageIdentity> {
    public StorageIdentity {
        if (ordinal < 0) {
            throw new IllegalArgumentException("storage ordinal must not be negative: " + ordinal);
        }
    }

    public long value() {
        return ordinal;
    }

    @Override
    public int compareTo(StorageIdentity other) {
        return Long.compare(ordinal, Objects.requireNonNull(other, "other").ordinal);
    }

    @Override
    public String toString() {
        return "storage#" + ordinal;
    }
}
