package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.Objects;

/** One function-body reference that links two predeclared function signatures. */
public record FunctionSignatureLink(
        DeclarationId from,
        DeclarationId to,
        ReferenceId referenceId,
        SourceSpan span) {
    public FunctionSignatureLink {
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
}
