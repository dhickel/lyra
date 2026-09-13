package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Exact bounded derivation of a consumer-scoped retained identity.
 *
 * <p>The producer key remains source/compiler provenance. The consumer context
 * is one finite semantic-analysis site: repeated execution at that site reuses
 * one abstract identity, while another construction, invocation path, or cell
 * scope receives another identity. Tagged ranges stay disjoint from ordinary
 * source and summary identities.</p>
 */
public record RetainedAllocationDerivation(
        Kind kind, FlowSiteId consumerContext, String producerKey, long ordinal) {

    public enum Kind {
        INVOCATION_CONTEXT,
        ARRAY,
        OBJECT_SITE,
        OBJECT_ALLOCATION,
        SHARED_CELL
    }

    private static final long PAYLOAD_MASK = 0x0fff_ffff_ffff_ffffL;
    private static final long TAG_MASK = 0x7000_0000_0000_0000L;
    private static final long SOURCE_LIMIT = 0x1000_0000_0000_0000L;
    private static final long SHARED_CELL_BASE = 0x1000_0000_0000_0000L;
    private static final long OBJECT_ALLOCATION_BASE = 0x2000_0000_0000_0000L;
    private static final long SUMMARY_ALLOCATION_BASE = 0x3000_0000_0000_0000L;
    private static final long CONTEXT_BASE = 0x4000_0000_0000_0000L;
    private static final long OBJECT_SITE_BASE = 0x5000_0000_0000_0000L;
    private static final long ARRAY_BASE = 0x6000_0000_0000_0000L;
    private static final long SOURCE_ALLOCATION_BASE = 0x7000_0000_0000_0000L;
    private static final byte[] DOMAIN = "LYRA-RETAINED-ALLOCATION/2".getBytes(StandardCharsets.UTF_8);

    public RetainedAllocationDerivation {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(consumerContext, "consumerContext");
        Objects.requireNonNull(producerKey, "producerKey");
        if (!isConsumerContext(consumerContext)) {
            throw new IllegalArgumentException(
                    "retained identity consumer context is outside its reserved site domain");
        }
        if (producerKey.isEmpty() || ordinal != deriveOrdinal(kind, consumerContext, producerKey)) {
            throw new IllegalArgumentException(
                    "retained identity derivation differs from its exact inputs");
        }
    }

    public static RetainedAllocationDerivation of(
            Kind kind, FlowSiteId consumerContext, FlowSiteId producerSite) {
        return of(kind, consumerContext, siteKey(producerSite));
    }

    private static RetainedAllocationDerivation of(
            Kind kind, FlowSiteId consumerContext, String producerKey) {
        return new RetainedAllocationDerivation(kind, consumerContext, producerKey,
                deriveOrdinal(kind, consumerContext, producerKey));
    }

    public static FlowSiteId invocationContext(
            FlowSiteId consumerContext, FlowSiteId producerCallSite) {
        return new FlowSiteId(of(Kind.INVOCATION_CONTEXT, consumerContext,
                siteKey(producerCallSite)).ordinal);
    }

    /** Binds an invocation step to both its summary identity and exact source site. */
    public static FlowSiteId invocationContext(
            FlowSiteId consumerContext, SummaryCallId call, FlowSiteId producerCallSite) {
        Objects.requireNonNull(call, "call");
        return new FlowSiteId(of(Kind.INVOCATION_CONTEXT, consumerContext,
                "summary/" + call.canonicalKey() + "/" + siteKey(producerCallSite)).ordinal);
    }

    public static DeclarationId arrayAllocation(
            FlowSiteId consumerContext, FlowSiteId producerAllocationSite) {
        return new DeclarationId(of(Kind.ARRAY, consumerContext,
                siteKey(producerAllocationSite)).ordinal);
    }

    public static FlowSiteId objectSite(
            FlowSiteId consumerContext, FlowSiteId producerConstructionSite) {
        return new FlowSiteId(of(Kind.OBJECT_SITE, consumerContext,
                siteKey(producerConstructionSite)).ordinal);
    }

    public static DeclarationId objectAllocation(
            FlowSiteId consumerContext, FlowSiteId producerConstructionSite) {
        return new DeclarationId(of(Kind.OBJECT_ALLOCATION, consumerContext,
                siteKey(producerConstructionSite)).ordinal);
    }

    public static DeclarationId sharedCell(
            FlowSiteId consumerContext, DeclarationId producerCell) {
        Objects.requireNonNull(producerCell, "producerCell");
        if (!isOrdinarySourceIdentity(producerCell.ordinal())) {
            throw new IllegalArgumentException(
                    "retained shared cell producer is outside the source identity domain");
        }
        return new DeclarationId(of(Kind.SHARED_CELL, consumerContext,
                "cell/" + producerCell.ordinal()).ordinal);
    }

    /** True only for a graph-issued source site, never a derived context/site. */
    public static boolean isOrdinarySourceSite(FlowSiteId site) {
        return site != null && isOrdinarySourceIdentity(site.ordinal());
    }

    /** True only for a graph-issued source declaration/cell identity. */
    public static boolean isOrdinarySourceDeclaration(DeclarationId declaration) {
        return declaration != null && isOrdinarySourceIdentity(declaration.ordinal());
    }

    /** Exact ordinary array/object allocation formula for one graph source site. */
    public static boolean isOrdinarySourceAllocation(
            FlowSiteId site, DeclarationId allocation) {
        return isOrdinarySourceSite(site) && allocation != null
                && (allocation.ordinal() & TAG_MASK) == SOURCE_ALLOCATION_BASE
                && allocation.ordinal() == Long.MAX_VALUE - site.ordinal();
    }

    /** Exact session-summary allocation formula for one graph source site. */
    public static boolean isOrdinarySummaryAllocation(
            FlowSiteId site, DeclarationId allocation) {
        return isOrdinarySourceSite(site) && allocation != null
                && (allocation.ordinal() & TAG_MASK) == SUMMARY_ALLOCATION_BASE
                && allocation.ordinal() == Long.MAX_VALUE / 2L - site.ordinal();
    }

    private static boolean isOrdinarySourceIdentity(long ordinal) {
        return ordinal >= 0 && ordinal < SOURCE_LIMIT;
    }

    private static boolean isConsumerContext(FlowSiteId context) {
        long tag = context.ordinal() & TAG_MASK;
        return isOrdinarySourceSite(context)
                || tag == CONTEXT_BASE || tag == OBJECT_SITE_BASE;
    }

    private static String siteKey(FlowSiteId site) {
        if (!isOrdinarySourceSite(site)) {
            throw new IllegalArgumentException(
                    "retained producer site is outside the source identity domain");
        }
        return "site/" + site.ordinal();
    }

    private static long deriveOrdinal(
            Kind kind, FlowSiteId consumerContext, String producerKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DOMAIN);
            digest.update((byte) kind.ordinal());
            digest.update(ByteBuffer.allocate(Long.BYTES)
                    .putLong(consumerContext.ordinal()).array());
            byte[] key = producerKey.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(key.length).array());
            digest.update(key);
            long payload = ByteBuffer.wrap(digest.digest()).getLong() & PAYLOAD_MASK;
            return base(kind) | payload;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static long base(Kind kind) {
        return switch (kind) {
            case INVOCATION_CONTEXT -> CONTEXT_BASE;
            case ARRAY -> ARRAY_BASE;
            case OBJECT_SITE -> OBJECT_SITE_BASE;
            case OBJECT_ALLOCATION -> OBJECT_ALLOCATION_BASE;
            case SHARED_CELL -> SHARED_CELL_BASE;
        };
    }
}
