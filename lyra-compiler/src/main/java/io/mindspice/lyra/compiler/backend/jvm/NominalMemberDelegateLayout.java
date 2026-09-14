package io.mindspice.lyra.compiler.backend.jvm;

import java.util.Objects;

/**
 * Exact typed shape of one occurrence-scoped callable member route delegate:
 * the declaring nominal representation, the declaration-order field index,
 * and the implemented structural function interface/signature.
 */
record NominalMemberDelegateLayout(NominalClassLayout nominal, int fieldIndex,
                                   String functionInterface, JvmSignaturePlan signature) {
    NominalMemberDelegateLayout {
        Objects.requireNonNull(nominal, "nominal");
        if (fieldIndex < 0) throw new IllegalArgumentException("negative nominal field index");
        Objects.requireNonNull(functionInterface, "functionInterface");
        Objects.requireNonNull(signature, "signature");
        if (signature.boundary() != JvmAbiBoundary.JAVA_VISIBLE) {
            throw new IllegalArgumentException("nominal member delegate needs the Java-visible signature");
        }
    }

    String binaryName(JvmTypeNameTable names) {
        return names.nominalMemberDelegateBinaryName(
                nominal.schema().type().canonicalSpelling(), fieldIndex);
    }
}
