package io.mindspice.lyra.compiler.backend.jvm;

import java.util.List;
import java.util.Objects;

/** Comparison of one compiler/runtime Lyra type pair after independent mapping. */
record JvmTypeParity(
        boolean matches,
        String compilerCanonicalType,
        String runtimeCanonicalType,
        String compilerRepresentation,
        String runtimeRepresentation,
        List<String> differences) {
    public JvmTypeParity {
        Objects.requireNonNull(compilerCanonicalType, "compilerCanonicalType");
        Objects.requireNonNull(runtimeCanonicalType, "runtimeCanonicalType");
        Objects.requireNonNull(compilerRepresentation, "compilerRepresentation");
        Objects.requireNonNull(runtimeRepresentation, "runtimeRepresentation");
        differences = List.copyOf(differences);
        if (matches && !differences.isEmpty()) {
            throw new IllegalArgumentException("a matching type parity result has differences");
        }
        if (!matches && differences.isEmpty()) {
            throw new IllegalArgumentException("a mismatching type parity result needs a difference");
        }
    }

    public void requireMatch() {
        if (!matches) {
            throw new IllegalArgumentException("Lyra/JVM type parity mismatch: " + differences);
        }
    }
}
