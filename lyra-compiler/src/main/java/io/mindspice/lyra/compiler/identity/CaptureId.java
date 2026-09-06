package io.mindspice.lyra.compiler.identity;

import java.util.Objects;

/** Deterministic compilation-local closure-capture identity. */
public record CaptureId(long ordinal) implements Comparable<CaptureId> {
    public CaptureId {
        if (ordinal < 0) {
            throw new IllegalArgumentException("capture ordinal must not be negative");
        }
    }

    public long value() {
        return ordinal;
    }

    public long id() {
        return ordinal;
    }

    @Override
    public int compareTo(CaptureId other) {
        return Long.compare(ordinal, Objects.requireNonNull(other, "other").ordinal);
    }

    @Override
    public String toString() {
        return "capture#" + ordinal;
    }
}
