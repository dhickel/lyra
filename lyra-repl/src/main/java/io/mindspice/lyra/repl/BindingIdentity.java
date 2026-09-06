package io.mindspice.lyra.repl;

import java.util.Objects;

/** Session-local identity of a lexical declaration. */
public record BindingIdentity(long ordinal) implements Comparable<BindingIdentity> {
    public BindingIdentity {
        if (ordinal < 0) {
            throw new IllegalArgumentException("binding ordinal must not be negative: " + ordinal);
        }
    }

    public long value() {
        return ordinal;
    }

    @Override
    public int compareTo(BindingIdentity other) {
        return Long.compare(ordinal, Objects.requireNonNull(other, "other").ordinal);
    }

    @Override
    public String toString() {
        return "binding#" + ordinal;
    }
}
