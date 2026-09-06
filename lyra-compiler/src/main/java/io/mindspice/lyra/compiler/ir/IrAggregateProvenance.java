package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.semantic.flow.AggregateIdentityFact;
import io.mindspice.lyra.compiler.semantic.flow.ArrayIdentity;
import io.mindspice.lyra.compiler.semantic.flow.OwnershipWitness;
import io.mindspice.lyra.compiler.semantic.flow.ProjectionPath;

import java.util.Objects;

/**
 * Aggregate identity and route provenance copied from canonical semantic flow.
 * Origin and use spans remain separate so a later alias cannot masquerade as
 * the allocation site.
 */
public record IrAggregateProvenance(
        ArrayIdentity identity,
        ProjectionPath route,
        OwnershipWitness witness,
        java.util.Optional<FlowSiteId> originSite)
        implements ImmutablePhaseArtifact, Comparable<IrAggregateProvenance> {
    public IrAggregateProvenance {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(witness, "witness");
        Objects.requireNonNull(originSite, "originSite");
        if (!witness.originSite().equals(originSite)
                || originSite.isEmpty() != (identity instanceof ArrayIdentity.SessionOrigin)) {
            throw new IllegalArgumentException("aggregate origin site does not match its witness");
        }
    }

    public static IrAggregateProvenance from(AggregateIdentityFact fact) {
        Objects.requireNonNull(fact, "fact");
        return new IrAggregateProvenance(fact.identity(), fact.route(), fact.witness(),
                fact.witness().originSite());
    }

    public ArrayIdentity arrayIdentity() {
        return identity;
    }

    public ProjectionPath path() {
        return route;
    }

    @Override
    public int compareTo(IrAggregateProvenance other) {
        return canonicalKey().compareTo(Objects.requireNonNull(other, "other").canonicalKey());
    }

    public String canonicalKey() {
        return identity.canonicalKey() + "@" + route + "@" + witness.canonicalKey()
                + "@" + originSite;
    }
}
