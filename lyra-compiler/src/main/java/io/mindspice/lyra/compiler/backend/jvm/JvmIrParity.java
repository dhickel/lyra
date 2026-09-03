package io.mindspice.lyra.compiler.backend.jvm;

import java.util.List;
import java.util.Objects;

/** Result of the pre-emission descriptor parity audit for a validated typed IR. */
record JvmIrParity(boolean matches, List<String> differences) {
    public JvmIrParity {
        differences = List.copyOf(Objects.requireNonNull(differences, "differences"));
        if (matches && !differences.isEmpty()) {
            throw new IllegalArgumentException("a matching IR parity result has differences");
        }
        if (!matches && differences.isEmpty()) {
            throw new IllegalArgumentException("a mismatching IR parity result needs a difference");
        }
    }

    public void requireMatch() {
        if (!matches) {
            throw new IllegalArgumentException("typed IR/JVM ABI parity mismatch: " + differences);
        }
    }
}
