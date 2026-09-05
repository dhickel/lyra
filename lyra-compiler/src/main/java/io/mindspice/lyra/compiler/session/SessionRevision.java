package io.mindspice.lyra.compiler.session;

import java.util.Objects;

/** Monotonic immutable namespace revision owned by one compiler session. */
public record SessionRevision(long value) implements Comparable<SessionRevision> {
    public SessionRevision {
        if (value < 0) {
            throw new IllegalArgumentException("session revision must not be negative");
        }
    }

    public static SessionRevision initial() {
        return new SessionRevision(0);
    }

    public SessionRevision next() {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException("session revision exhausted");
        }
        return new SessionRevision(value + 1);
    }

    @Override
    public int compareTo(SessionRevision other) {
        return Long.compare(value, Objects.requireNonNull(other, "other").value);
    }
}
