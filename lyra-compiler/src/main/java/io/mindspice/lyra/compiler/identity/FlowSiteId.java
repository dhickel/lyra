package io.mindspice.lyra.compiler.identity;

import java.util.Objects;

/**
 * Deterministic compilation-local identity of one typed source-expression site.
 *
 * <p>Flow-site identities are internal provenance keys. They are allocated
 * once from typed module/source order, are not serialized, and are not a
 * cross-build compatibility API.</p>
 */
public record FlowSiteId(long ordinal) implements Comparable<FlowSiteId> {
    public FlowSiteId {
        if (ordinal < 0) {
            throw new IllegalArgumentException("flow-site ordinal must not be negative");
        }
    }

    public long value() {
        return ordinal;
    }

    @Override
    public int compareTo(FlowSiteId other) {
        return Long.compare(ordinal, Objects.requireNonNull(other, "other").ordinal);
    }

    @Override
    public String toString() {
        return "flow-site#" + ordinal;
    }
}
