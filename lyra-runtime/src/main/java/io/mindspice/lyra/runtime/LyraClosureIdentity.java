package io.mindspice.lyra.runtime;

import java.util.Objects;

/** Identity of one dynamic Lyra lambda evaluation. */
public final class LyraClosureIdentity {
    private final LyraOwnershipToken owner;
    private final long ordinal;

    LyraClosureIdentity(LyraOwnershipToken owner, long ordinal) {
        this.owner = Objects.requireNonNull(owner, "owner");
        if (ordinal < 0) {
            throw new IllegalArgumentException("closure ordinal must be non-negative");
        }
        this.ordinal = ordinal;
    }

    public long ordinal() {
        return ordinal;
    }

    public long sequence() {
        return ordinal;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof LyraClosureIdentity identity
                && owner == identity.owner && ordinal == identity.ordinal;
    }

    @Override
    public int hashCode() {
        return 31 * System.identityHashCode(owner) + Long.hashCode(ordinal);
    }

    @Override
    public String toString() {
        return "closure#" + ordinal;
    }
}
