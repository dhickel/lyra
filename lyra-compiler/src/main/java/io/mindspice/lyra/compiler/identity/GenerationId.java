package io.mindspice.lyra.compiler.identity;

import java.util.Objects;

/**
 * Compiler identity for one retained session module graph generation.
 *
 * <p>This identity is deliberately separate from a logical module name and
 * from its source revision.  A reload may therefore retain an old generation
 * while compiling a new generation from the same source origin.</p>
 */
public record GenerationId(long ordinal) implements Comparable<GenerationId> {
    public GenerationId {
        if (ordinal < 0) {
            throw new IllegalArgumentException("generation ordinal must not be negative");
        }
    }

    public long value() {
        return ordinal;
    }

    @Override
    public int compareTo(GenerationId other) {
        return Long.compare(ordinal, Objects.requireNonNull(other, "other").ordinal);
    }

    @Override
    public String toString() {
        return "generation#" + ordinal;
    }
}
