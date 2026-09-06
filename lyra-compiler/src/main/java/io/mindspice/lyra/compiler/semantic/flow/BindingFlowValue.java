package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.types.BindingContract;

import java.util.List;
import java.util.Objects;

/** A binding contract paired with its immutable current value alternatives. */
public record BindingFlowValue(
        BindingContract contract,
        ValueAlternatives alternatives) {
    public BindingFlowValue {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(alternatives, "alternatives");
    }

    public boolean isMutable() {
        return contract.isMutable();
    }

    public BindingFlowValue withAlternatives(ValueAlternatives replacement) {
        return new BindingFlowValue(contract, replacement);
    }

    /** Deterministic callable transfer alternatives carried by this binding. */
    public List<CallableFlow> callableFlows() {
        return alternatives.alternatives().stream()
                .flatMap(value -> value.callableFlows().stream())
                .distinct()
                .sorted(CallableFlow::compareTo)
                .toList();
    }
}
