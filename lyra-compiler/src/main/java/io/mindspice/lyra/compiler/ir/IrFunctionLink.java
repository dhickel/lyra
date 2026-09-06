package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.Objects;

/** One source call edge in the predeclared function-signature graph. */
public record IrFunctionLink(
        DeclarationId from,
        DeclarationId to,
        ReferenceId referenceId,
        SourceSpan span)
        implements ImmutablePhaseArtifact, Comparable<IrFunctionLink> {
    public IrFunctionLink {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(referenceId, "referenceId");
        Objects.requireNonNull(span, "span");
    }

    public DeclarationId source() {
        return from;
    }

    public DeclarationId target() {
        return to;
    }

    @Override
    public int compareTo(IrFunctionLink other) {
        IrFunctionLink value = Objects.requireNonNull(other, "other");
        int result = from.compareTo(value.from);
        if (result != 0) {
            return result;
        }
        result = to.compareTo(value.to);
        if (result != 0) {
            return result;
        }
        result = referenceId.compareTo(value.referenceId);
        return result != 0 ? result : span.toString().compareTo(value.span.toString());
    }
}
