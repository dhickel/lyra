package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.types.ArrayType;

import java.util.Comparator;
import java.util.Objects;

/** One routed array identity and the immutable evidence proving its origin. */
public record AggregateIdentityFact(
        ArrayIdentity identity,
        ProjectionPath route,
        OwnershipWitness witness)
        implements Comparable<AggregateIdentityFact> {
    public AggregateIdentityFact {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(witness, "witness");
        if (!identity.ownerModule().equals(witness.ownerModule())) {
            throw new IllegalArgumentException(
                    "identity and ownership witness have different owner modules");
        }
        if (!identity.originDeclaration().equals(witness.originDeclaration())) {
            throw new IllegalArgumentException(
                    "identity and ownership witness have different origin declarations");
        }
        if (!identity.originExport().equals(witness.originExport())) {
            throw new IllegalArgumentException(
                    "identity and ownership witness have different exports");
        }
    }

    public ArrayIdentity arrayIdentity() {
        return identity;
    }

    public ProjectionPath path() {
        return route;
    }

    public OwnershipWitness ownershipWitness() {
        return witness;
    }

    public ArrayType arrayType() {
        return identity.arrayType();
    }

    public boolean isImported() {
        return identity.isImported();
    }

    public AggregateIdentityFact withRoute(ProjectionPath replacement) {
        return new AggregateIdentityFact(identity, replacement, witness);
    }

    public AggregateIdentityFact prefixedBy(ProjectionPath prefix) {
        return withRoute(Objects.requireNonNull(prefix, "prefix").compose(route));
    }

    public String canonicalKey() {
        return identity.canonicalKey() + "@" + route + "@" + witness.canonicalKey();
    }

    public static Comparator<AggregateIdentityFact> comparator() {
        return Comparator.naturalOrder();
    }

    @Override
    public int compareTo(AggregateIdentityFact other) {
        return canonicalKey().compareTo(
                Objects.requireNonNull(other, "other").canonicalKey());
    }
}
