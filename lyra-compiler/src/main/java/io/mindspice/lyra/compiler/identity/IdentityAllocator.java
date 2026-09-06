package io.mindspice.lyra.compiler.identity;

import java.util.Objects;

/**
 * Persistent allocator for all compilation-local identities.
 *
 * <p>Allocation is functional: an operation returns the ID and the next
 * allocator snapshot.  No mutable counter or mutable collection is exposed,
 * so one allocator snapshot can be retained and reused by later phases.</p>
 */
public record IdentityAllocator(
        long nextScopeOrdinal,
        long nextDeclarationOrdinal,
        long nextReferenceOrdinal,
        long nextLambdaOrdinal,
        long nextCaptureOrdinal,
        long nextFlowSiteOrdinal) {
    public IdentityAllocator {
        requireNonNegative(nextScopeOrdinal, "nextScopeOrdinal");
        requireNonNegative(nextDeclarationOrdinal, "nextDeclarationOrdinal");
        requireNonNegative(nextReferenceOrdinal, "nextReferenceOrdinal");
        requireNonNegative(nextLambdaOrdinal, "nextLambdaOrdinal");
        requireNonNegative(nextCaptureOrdinal, "nextCaptureOrdinal");
        requireNonNegative(nextFlowSiteOrdinal, "nextFlowSiteOrdinal");
    }

    /** Compatibility constructor for callers created before flow sites joined the allocator. */
    public IdentityAllocator(
            long nextScopeOrdinal,
            long nextDeclarationOrdinal,
            long nextReferenceOrdinal,
            long nextLambdaOrdinal,
            long nextCaptureOrdinal) {
        this(nextScopeOrdinal, nextDeclarationOrdinal, nextReferenceOrdinal,
                nextLambdaOrdinal, nextCaptureOrdinal, 0);
    }

    public static IdentityAllocator initial() {
        return new IdentityAllocator(0, 0, 0, 0, 0, 0);
    }

    public static IdentityAllocator empty() {
        return initial();
    }

    public Allocation<ScopeId> allocateScope() {
        return allocate(new ScopeId(nextScopeOrdinal),
                new IdentityAllocator(
                        increment(nextScopeOrdinal),
                        nextDeclarationOrdinal,
                        nextReferenceOrdinal,
                        nextLambdaOrdinal,
                        nextCaptureOrdinal,
                        nextFlowSiteOrdinal));
    }

    public Allocation<DeclarationId> allocateDeclaration() {
        return allocate(new DeclarationId(nextDeclarationOrdinal),
                new IdentityAllocator(
                        nextScopeOrdinal,
                        increment(nextDeclarationOrdinal),
                        nextReferenceOrdinal,
                        nextLambdaOrdinal,
                        nextCaptureOrdinal,
                        nextFlowSiteOrdinal));
    }

    public Allocation<ReferenceId> allocateReference() {
        return allocate(new ReferenceId(nextReferenceOrdinal),
                new IdentityAllocator(
                        nextScopeOrdinal,
                        nextDeclarationOrdinal,
                        increment(nextReferenceOrdinal),
                        nextLambdaOrdinal,
                        nextCaptureOrdinal,
                        nextFlowSiteOrdinal));
    }

    public Allocation<LambdaId> allocateLambda() {
        return allocate(new LambdaId(nextLambdaOrdinal),
                new IdentityAllocator(
                        nextScopeOrdinal,
                        nextDeclarationOrdinal,
                        nextReferenceOrdinal,
                        increment(nextLambdaOrdinal),
                        nextCaptureOrdinal,
                        nextFlowSiteOrdinal));
    }

    public Allocation<CaptureId> allocateCapture() {
        return allocate(new CaptureId(nextCaptureOrdinal),
                new IdentityAllocator(
                        nextScopeOrdinal,
                        nextDeclarationOrdinal,
                        nextReferenceOrdinal,
                        nextLambdaOrdinal,
                        increment(nextCaptureOrdinal),
                        nextFlowSiteOrdinal));
    }

    public Allocation<FlowSiteId> allocateFlowSite() {
        return allocate(new FlowSiteId(nextFlowSiteOrdinal),
                new IdentityAllocator(
                        nextScopeOrdinal,
                        nextDeclarationOrdinal,
                        nextReferenceOrdinal,
                        nextLambdaOrdinal,
                        nextCaptureOrdinal,
                        increment(nextFlowSiteOrdinal)));
    }

    /** Returns whether this allocator has not regressed any identity stream. */
    public boolean dominates(IdentityAllocator other) {
        Objects.requireNonNull(other, "other");
        return nextScopeOrdinal >= other.nextScopeOrdinal
                && nextDeclarationOrdinal >= other.nextDeclarationOrdinal
                && nextReferenceOrdinal >= other.nextReferenceOrdinal
                && nextLambdaOrdinal >= other.nextLambdaOrdinal
                && nextCaptureOrdinal >= other.nextCaptureOrdinal
                && nextFlowSiteOrdinal >= other.nextFlowSiteOrdinal;
    }

    private static <T> Allocation<T> allocate(T id, IdentityAllocator next) {
        return new Allocation<>(id, next);
    }

    private static long increment(long value) {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException("compilation-local identity space exhausted");
        }
        return value + 1;
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    /** One immutable allocation result. */
    public record Allocation<T>(T id, IdentityAllocator next) {
        public Allocation {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(next, "next");
        }

        public T value() {
            return id;
        }

        public IdentityAllocator allocator() {
            return next;
        }
    }
}
