package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.identity.LambdaId;

import java.util.Objects;

/** Deterministic source-order identity for a call operation in one lambda summary. */
public record SummaryCallId(
        LambdaId ownerLambda,
        int ordinal) implements Comparable<SummaryCallId> {
    public SummaryCallId {
        Objects.requireNonNull(ownerLambda, "ownerLambda");
        if (ordinal < 0) {
            throw new IllegalArgumentException("call ordinal must not be negative");
        }
    }

    public LambdaId lambdaId() {
        return ownerLambda;
    }

    public int sequence() {
        return ordinal;
    }

    public String canonicalKey() {
        return ownerLambda + "#call" + ordinal;
    }

    @Override
    public int compareTo(SummaryCallId other) {
        Objects.requireNonNull(other, "other");
        int owner = ownerLambda.compareTo(other.ownerLambda);
        return owner != 0 ? owner : Integer.compare(ordinal, other.ordinal);
    }

    @Override
    public String toString() {
        return canonicalKey();
    }
}
