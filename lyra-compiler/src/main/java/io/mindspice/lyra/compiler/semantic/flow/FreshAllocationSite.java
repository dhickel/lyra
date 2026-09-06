package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.Objects;

/**
 * A compile-local symbolic allocation site inside one lambda body.
 *
 * <p>The site is deliberately not a runtime identity or a serialized IR ID.
 * The traversal ordinal disambiguates two allocation expressions with the
 * same source span shape while the lambda and span retain deterministic source
 * evidence.</p>
 */
public record FreshAllocationSite(
        LambdaId ownerLambda,
        SourceSpan span,
        int ordinal) implements Comparable<FreshAllocationSite> {
    public FreshAllocationSite {
        Objects.requireNonNull(ownerLambda, "ownerLambda");
        Objects.requireNonNull(span, "span");
        if (ordinal < 0) {
            throw new IllegalArgumentException("allocation ordinal must not be negative");
        }
    }

    public LambdaId lambdaId() {
        return ownerLambda;
    }

    public int sequence() {
        return ordinal;
    }

    public String canonicalKey() {
        String sourceKind = span.sourceId().isUri() ? "uri:" : "path:";
        return ownerLambda + "@" + sourceKind + span.sourceId().value() + ":"
                + span.startOffset() + ".." + span.endOffset() + "#" + ordinal;
    }

    @Override
    public int compareTo(FreshAllocationSite other) {
        return canonicalKey().compareTo(
                Objects.requireNonNull(other, "other").canonicalKey());
    }

    @Override
    public String toString() {
        return canonicalKey();
    }
}
