package io.mindspice.lyra.compiler.semantic.flow;

import java.util.Objects;

/** A reference to abstract object storage at an exact aggregate route. */
public record NominalObjectFact(NominalObjectIdentity identity, ProjectionPath route, OwnershipWitness ownership)
        implements Comparable<NominalObjectFact> {
    public NominalObjectFact {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(ownership, "ownership");
        if (!identity.ownerModule().equals(ownership.ownerModule())
                || !ownership.originSite().equals(java.util.Optional.of(identity.allocationSite()))) {
            throw new IllegalArgumentException("object ownership does not match its allocation identity");
        }
    }
    public NominalObjectFact prefixedBy(ProjectionPath prefix) {
        return new NominalObjectFact(identity, prefix.compose(route), ownership);
    }
    public NominalObjectFact withRoute(ProjectionPath route) { return new NominalObjectFact(identity, route, ownership); }
    @Override public int compareTo(NominalObjectFact other) {
        int compared = identity.compareTo(other.identity);
        if (compared != 0) return compared;
        compared = route.compareTo(other.route);
        return compared != 0 ? compared : ownership.compareTo(other.ownership);
    }
}
