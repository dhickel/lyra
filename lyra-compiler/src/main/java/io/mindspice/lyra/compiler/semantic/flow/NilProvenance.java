package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.Objects;

/** Exact source provenance for {@code #NIL} at one value-alternative route. */
public record NilProvenance(
        FlowSiteId sourceSite,
        SourceSpan sourceSpan,
        ProjectionPath route) implements Comparable<NilProvenance> {
    public NilProvenance {
        Objects.requireNonNull(sourceSite, "sourceSite");
        Objects.requireNonNull(sourceSpan, "sourceSpan");
        Objects.requireNonNull(route, "route");
    }

    public NilProvenance withRoute(ProjectionPath replacement) {
        return new NilProvenance(sourceSite, sourceSpan,
                Objects.requireNonNull(replacement, "replacement"));
    }

    public NilProvenance prefixedBy(ProjectionPath prefix) {
        return withRoute(Objects.requireNonNull(prefix, "prefix").compose(route));
    }

    public String canonicalKey() {
        return sourceSite + "@" + sourceSpan + "@" + route;
    }

    @Override
    public int compareTo(NilProvenance other) {
        return canonicalKey().compareTo(
                Objects.requireNonNull(other, "other").canonicalKey());
    }

    @Override
    public String toString() {
        return canonicalKey();
    }
}
