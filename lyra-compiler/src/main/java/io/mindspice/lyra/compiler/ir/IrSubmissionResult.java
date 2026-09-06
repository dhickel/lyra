package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.semantic.TypedSubmissionResult;
import io.mindspice.lyra.compiler.types.LyraType;

import java.util.Objects;
import java.util.Optional;

/** Exact typed result of executing a new submission, never a module-body retyping. */
public record IrSubmissionResult(Optional<FlowSiteId> site, LyraType type) {
    public IrSubmissionResult {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(type, "type");
        if (type.isMutable()) throw new IllegalArgumentException("result carries binding authority");
    }

    public static IrSubmissionResult from(TypedSubmissionResult result) {
        return new IrSubmissionResult(result.site(), result.type());
    }
}
