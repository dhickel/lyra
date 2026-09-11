package io.mindspice.lyra.compiler.semantic.flow;

import java.util.Optional;
import java.util.function.Function;

/** Caller-owned exact heap lookup used while substituting a callable summary. */
public interface SummaryObjectResolver extends Function<ValueFormula.Declaration, Optional<FormulaAlternatives>> {
    Optional<FormulaAlternatives> resolveObject(ValueFormula.ObjectReference reference);
}
