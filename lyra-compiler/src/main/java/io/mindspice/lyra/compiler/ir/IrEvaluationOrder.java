package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Explicit evaluation edges for one IR operation.  Source spans are evidence,
 * never the ordering key: the ordinal and producer-issued child sites define
 * the order even when nested or repeated spans are equal.
 */
public record IrEvaluationOrder(
        Optional<FlowSiteId> ownerSite,
        ModuleId moduleId,
        SourceSpan span,
        Kind kind,
        List<Edge> edges)
        implements ImmutablePhaseArtifact, Comparable<IrEvaluationOrder> {
    public enum Kind {
        MODULE_SEQUENCE,
        STRICT,
        SHORT_CIRCUIT,
        BRANCH,
        COALESCE
    }

    public enum EdgeKind {
        STRICT,
        SHORT_CIRCUIT_OPERAND,
        THEN_BRANCH,
        ELSE_BRANCH,
        NON_NIL_VALUE,
        FALLBACK
    }

    public record Edge(int ordinal, FlowSiteId childSite, EdgeKind kind)
            implements ImmutablePhaseArtifact, Comparable<Edge> {
        public Edge {
            if (ordinal < 0) {
                throw new IllegalArgumentException("evaluation edge ordinal must not be negative");
            }
            Objects.requireNonNull(childSite, "childSite");
            Objects.requireNonNull(kind, "kind");
        }

        @Override
        public int compareTo(Edge other) {
            int result = Integer.compare(ordinal, Objects.requireNonNull(other, "other").ordinal);
            return result != 0 ? result : childSite.compareTo(other.childSite);
        }
    }

    public IrEvaluationOrder {
        Objects.requireNonNull(ownerSite, "ownerSite");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(kind, "kind");
        if (!moduleId.sourceId().equals(span.sourceId())) {
            throw new IllegalArgumentException("evaluation order belongs to another module");
        }
        edges = copy(edges, "edges");
        java.util.HashSet<FlowSiteId> childSites = new java.util.HashSet<>();
        for (int index = 0; index < edges.size(); index++) {
            if (edges.get(index).ordinal() != index) {
                throw new IllegalArgumentException("evaluation edges must be densely ordered");
            }
            Edge edge = edges.get(index);
            if (!childSites.add(edge.childSite())) {
                throw new IllegalArgumentException("evaluation edges must not repeat a child site");
            }
            EdgeKind expected = expectedEdgeKind(kind, index);
            if (expected != null && edge.kind() != expected) {
                throw new IllegalArgumentException(
                        "evaluation edge kind does not match its control-flow owner");
            }
        }
        if (kind == Kind.MODULE_SEQUENCE && ownerSite.isPresent()) {
            throw new IllegalArgumentException("module evaluation order has no expression owner site");
        }
        if (kind != Kind.MODULE_SEQUENCE && ownerSite.isEmpty()) {
            throw new IllegalArgumentException("expression evaluation order needs an owner site");
        }
    }

    public List<FlowSiteId> childSites() {
        return edges.stream().map(Edge::childSite).toList();
    }

    private static EdgeKind expectedEdgeKind(Kind kind, int index) {
        return switch (kind) {
            case MODULE_SEQUENCE, STRICT -> EdgeKind.STRICT;
            case SHORT_CIRCUIT -> index == 0
                    ? EdgeKind.STRICT : EdgeKind.SHORT_CIRCUIT_OPERAND;
            case BRANCH -> index == 0 ? EdgeKind.STRICT
                    : index == 1 ? EdgeKind.THEN_BRANCH : EdgeKind.ELSE_BRANCH;
            case COALESCE -> index == 0
                    ? EdgeKind.NON_NIL_VALUE : EdgeKind.FALLBACK;
        };
    }

    @Override
    public int compareTo(IrEvaluationOrder other) {
        IrEvaluationOrder value = Objects.requireNonNull(other, "other");
        int owner = ownerSite.map(FlowSiteId::value).orElse(-1L) == value.ownerSite.map(FlowSiteId::value).orElse(-1L)
                ? 0
                : Long.compare(ownerSite.map(FlowSiteId::value).orElse(-1L),
                        value.ownerSite.map(FlowSiteId::value).orElse(-1L));
        return owner != 0 ? owner : span.toString().compareTo(value.span.toString());
    }

    private static <T> List<T> copy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " must not contain null"));
        }
        return List.copyOf(copy);
    }
}
