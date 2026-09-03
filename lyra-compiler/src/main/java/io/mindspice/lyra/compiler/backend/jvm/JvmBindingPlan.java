package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.compiler.types.BindingContract;
import io.mindspice.lyra.compiler.types.BindingMutability;

import java.util.Objects;

/** ABI plan for a declaration, retaining binding-local {@code @mut} separately. */
record JvmBindingPlan(
        String canonicalBindingContract,
        BindingMutability bindingMutability,
        JvmTypePlan value) {
    public JvmBindingPlan {
        if (Objects.requireNonNull(canonicalBindingContract, "canonicalBindingContract").isBlank()) {
            throw new IllegalArgumentException("binding contract spelling must not be blank");
        }
        Objects.requireNonNull(bindingMutability, "bindingMutability");
        Objects.requireNonNull(value, "value");
        if (value.context().position() != JvmValuePosition.BINDING) {
            throw new IllegalArgumentException("binding plans must use the binding ABI position");
        }
        if (value.isMutable()) {
            throw new IllegalArgumentException("binding mutability must remain separate from the value type");
        }
        String expected = (bindingMutability.isMutable() ? "@mut" : "")
                + value.canonicalLyraType();
        if (!canonicalBindingContract.equals(expected)) {
            throw new IllegalArgumentException("binding contract metadata disagrees with value plan");
        }
    }

    public static JvmBindingPlan of(BindingContract contract, JvmTypePlan value) {
        Objects.requireNonNull(contract, "contract");
        return new JvmBindingPlan(contract.canonicalSpelling(), contract.mutability(), value);
    }

    public boolean isMutable() {
        return bindingMutability.isMutable();
    }

    public String canonicalSpelling() {
        return canonicalBindingContract + "|mutability="
                + bindingMutability.name().toLowerCase(java.util.Locale.ROOT)
                + "|value=" + value.canonicalSpelling();
    }

    public String canonical() {
        return canonicalSpelling();
    }
}
