package io.mindspice.lyra.runtime;

/** Immutable range data. Traversal state belongs to the caller, never to this value. */
public record LyraRange(long start, long end, long step, boolean inclusive, int bits) {
    public LyraRange {
        if (bits != 8 && bits != 16 && bits != 32 && bits != 64) {
            throw new IllegalArgumentException("range width must be 8, 16, 32 or 64");
        }
        if (bits != 64) {
            long min = -(1L << (bits - 1));
            long max = (1L << (bits - 1)) - 1;
            if (start < min || start > max || end < min || end > max || step < min || step > max) {
                throw new IllegalArgumentException("range data does not fit its signed element type");
            }
        }
        if (step == 0) {
            throw new LyraArithmeticException("a range step must not be zero");
        }
    }

    private boolean containsPosition(long value) {
        return step > 0 ? inclusive ? value <= end : value < end
                : inclusive ? value >= end : value > end;
    }

    public boolean isEmpty() {
        return !containsPosition(start);
    }

    /** Tests before adding, including when the mathematical successor exceeds signed long. */
    public boolean hasSuccessor(long value) {
        if (step > 0 && value > Long.MAX_VALUE - step
                || step < 0 && value < Long.MIN_VALUE - step) {
            return false;
        }
        return containsPosition(value + step);
    }
}
