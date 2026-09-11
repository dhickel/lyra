package io.mindspice.lyra.compiler.semantic.flow;

import java.util.Optional;
import java.util.function.Function;

/** Caller-owned exact heap lookup used while substituting a callable summary. */
public interface SummaryObjectResolver extends Function<ValueFormula.Declaration, Optional<FormulaAlternatives>> {
    boolean orderedEffects();
    void applyWrite(CapturedCellWrite write, CallableSummary owner, Object activation);
    Optional<FormulaAlternatives> resolveObject(ValueFormula.ObjectReference reference);
    Optional<FormulaAlternatives> construct(CallableCallReference call, java.util.List<FormulaAlternatives> arguments,
                                          Object activation);
}
