package io.mindspice.lyra.compiler.grammar;

import java.util.List;
import java.util.Objects;

/**
 * One immutable, parser-replay descriptor.
 *
 * <p>Ranges are token-index ranges, not character ranges.  They are
 * end-exclusive and include the syntactic delimiters belonging to the
 * production.  Punctuation that is not a child is retained in
 * {@link #metadata()} so replay never has to rediscover a grammar decision.</p>
 */
public final class GrammarDescriptor {
    private final ProductionKind kind;
    private final int startTokenIndex;
    private final int endTokenIndex;
    private final List<GrammarDescriptor> children;
    private final DescriptorMetadata metadata;

    public GrammarDescriptor(
            ProductionKind kind,
            int startTokenIndex,
            int endTokenIndex,
            List<GrammarDescriptor> children,
            DescriptorMetadata metadata) {
        this.kind = Objects.requireNonNull(kind, "kind");
        if (startTokenIndex < 0) {
            throw new IllegalArgumentException("descriptor start must be non-negative");
        }
        if (endTokenIndex < startTokenIndex) {
            throw new IllegalArgumentException("descriptor end must not precede its start");
        }
        this.startTokenIndex = startTokenIndex;
        this.endTokenIndex = endTokenIndex;
        Objects.requireNonNull(children, "children");
        this.children = List.copyOf(children);
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        validateChildren();
        validateMetadataIndices();
    }

    public GrammarDescriptor(
            ProductionKind kind,
            int startTokenIndex,
            int endTokenIndex,
            List<GrammarDescriptor> children) {
        this(kind, startTokenIndex, endTokenIndex, children, DescriptorMetadata.NONE);
    }

    public ProductionKind kind() {
        return kind;
    }

    public int startTokenIndex() {
        return startTokenIndex;
    }

    public int endTokenIndex() {
        return endTokenIndex;
    }

    public int tokenLength() {
        return endTokenIndex - startTokenIndex;
    }

    public List<GrammarDescriptor> children() {
        return children;
    }

    public DescriptorMetadata metadata() {
        return metadata;
    }

    /** Alias useful to replay code that calls a descriptor's range a span. */
    public int start() {
        return startTokenIndex;
    }

    /** Alias useful to replay code that calls a descriptor's range a span. */
    public int end() {
        return endTokenIndex;
    }

    public boolean containsRange(int start, int end) {
        return start >= startTokenIndex && end >= start && end <= endTokenIndex;
    }

    /**
     * Asserts the exact range consumed by parser replay for this production.
     * A mismatch is an invariant violation, not a source diagnostic.
     */
    public void assertConsumed(int consumedStartTokenIndex, int consumedEndTokenIndex) {
        if (consumedStartTokenIndex != startTokenIndex
                || consumedEndTokenIndex != endTokenIndex) {
            throw new IllegalStateException(
                    "replay consumed ["
                            + consumedStartTokenIndex
                            + ", "
                            + consumedEndTokenIndex
                            + ") for "
                            + kind
                            + " but descriptor records ["
                            + startTokenIndex
                            + ", "
                            + endTokenIndex
                            + ")");
        }
    }

    /** Asserts replay consumed this descriptor from its recorded start. */
    public void assertConsumed(int consumedEndTokenIndex) {
        assertConsumed(startTokenIndex, consumedEndTokenIndex);
    }

    /** Recursively rechecks the immutable tree's containment and sibling order. */
    public void validateStructure() {
        validateChildren();
        validateMetadataIndices();
        for (GrammarDescriptor child : children) {
            child.validateStructure();
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GrammarDescriptor descriptor)) {
            return false;
        }
        return startTokenIndex == descriptor.startTokenIndex
                && endTokenIndex == descriptor.endTokenIndex
                && kind == descriptor.kind
                && children.equals(descriptor.children)
                && metadata.equals(descriptor.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, startTokenIndex, endTokenIndex, children, metadata);
    }

    @Override
    public String toString() {
        return kind + "[" + startTokenIndex + "," + endTokenIndex + ")";
    }

    private void validateChildren() {
        int previousEnd = startTokenIndex;
        for (GrammarDescriptor child : children) {
            Objects.requireNonNull(child, "children must not contain null");
            if (child.startTokenIndex < startTokenIndex
                    || child.endTokenIndex > endTokenIndex) {
                throw new IllegalArgumentException(
                        "child "
                                + child.kind
                                + " lies outside parent "
                                + kind
                                + " range");
            }
            if (child.startTokenIndex < previousEnd) {
                throw new IllegalArgumentException(
                        "sibling descriptor ranges overlap inside " + kind);
            }
            previousEnd = child.endTokenIndex;
        }
    }

    private void validateMetadataIndices() {
        validateMetadataIndex(metadata.openingTokenIndex(), "openingTokenIndex");
        validateMetadataIndex(metadata.closingTokenIndex(), "closingTokenIndex");
        validateMetadataIndex(metadata.primaryTokenIndex(), "primaryTokenIndex");
        for (Integer index : metadata.commaTokenIndices()) {
            validateMetadataIndex(index, "commaTokenIndices");
        }
        for (Integer index : metadata.modifierTokenIndices()) {
            validateMetadataIndex(index, "modifierTokenIndices");
        }
        for (Integer index : metadata.operatorTokenIndices()) {
            validateMetadataIndex(index, "operatorTokenIndices");
        }
    }

    private void validateMetadataIndex(int index, String name) {
        if (index >= 0 && (index < startTokenIndex || index >= endTokenIndex)) {
            throw new IllegalArgumentException(
                    name + " index " + index + " lies outside " + kind + " range");
        }
    }
}
