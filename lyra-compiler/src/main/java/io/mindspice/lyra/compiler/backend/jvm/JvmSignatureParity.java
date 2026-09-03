package io.mindspice.lyra.compiler.backend.jvm;

import java.util.List;
import java.util.Objects;

/** Comparison of independent compiler/runtime signature mappings. */
record JvmSignatureParity(
        boolean matches,
        String compilerCanonicalSignature,
        String runtimeCanonicalSignature,
        String compilerDescriptor,
        String runtimeDescriptor,
        List<String> differences) {
    public JvmSignatureParity {
        Objects.requireNonNull(compilerCanonicalSignature, "compilerCanonicalSignature");
        Objects.requireNonNull(runtimeCanonicalSignature, "runtimeCanonicalSignature");
        Objects.requireNonNull(compilerDescriptor, "compilerDescriptor");
        Objects.requireNonNull(runtimeDescriptor, "runtimeDescriptor");
        differences = List.copyOf(differences);
        if (matches && !differences.isEmpty()) {
            throw new IllegalArgumentException("a matching signature parity result has differences");
        }
        if (!matches && differences.isEmpty()) {
            throw new IllegalArgumentException("a mismatching signature parity result needs a difference");
        }
    }

    public String compilerJvmDescriptor() {
        return compilerDescriptor;
    }

    public String runtimeJvmDescriptor() {
        return runtimeDescriptor;
    }

    public void requireMatch() {
        if (!matches) {
            throw new IllegalArgumentException("Lyra/JVM signature parity mismatch: " + differences);
        }
    }
}
