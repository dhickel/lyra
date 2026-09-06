package io.mindspice.lyra.repl;

/** Monotonic, session-local namespace revision. */
public record SessionRevision(long value) implements Comparable<SessionRevision> {
    public SessionRevision {
        if (value < 0) {
            throw new IllegalArgumentException("session revision must not be negative: " + value);
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
        return Long.compare(value, java.util.Objects.requireNonNull(other, "other").value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
