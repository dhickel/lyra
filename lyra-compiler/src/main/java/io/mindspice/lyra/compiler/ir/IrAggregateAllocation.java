package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.semantic.flow.ArrayIdentity;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.ArrayType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One source array allocation and its producer-certified occurrence routes. */
public record IrAggregateAllocation(
        FlowSiteId siteId,
        ModuleId moduleId,
        SourceSpan span,
        ArrayType arrayType,
        List<IrAggregateProvenance> occurrences,
        Optional<ArrayIdentity> canonicalIdentity)
        implements ImmutablePhaseArtifact, Comparable<IrAggregateAllocation> {
    public IrAggregateAllocation {
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(arrayType, "arrayType");
        occurrences = copy(occurrences);
        if (new LinkedHashSet<>(occurrences).size() != occurrences.size()) {
            throw new IllegalArgumentException("aggregate occurrences must be unique");
        }
        ArrayList<IrAggregateProvenance> orderedOccurrences = new ArrayList<>(occurrences);
        orderedOccurrences.sort(IrAggregateProvenance::compareTo);
        if (!occurrences.equals(orderedOccurrences)) {
            throw new IllegalArgumentException("aggregate occurrences must be canonical");
        }
        Objects.requireNonNull(canonicalIdentity, "canonicalIdentity");
        if (!moduleId.sourceId().equals(span.sourceId())) {
            throw new IllegalArgumentException("aggregate allocation belongs to another module");
        }
        canonicalIdentity.ifPresent(identity -> {
            if (!identity.arrayType().equals(arrayType)) {
                throw new IllegalArgumentException("aggregate allocation identity has another array type");
            }
        });
        for (IrAggregateProvenance occurrence : occurrences) {
            if (occurrence.originSite().filter(siteId::equals).isEmpty()) {
                throw new IllegalArgumentException("aggregate occurrence belongs to another allocation site");
            }
        }
    }

    public FlowSiteId allocationSite() {
        return siteId;
    }

    public List<IrAggregateProvenance> provenance() {
        return occurrences;
    }

    @Override
    public int compareTo(IrAggregateAllocation other) {
        return siteId.compareTo(Objects.requireNonNull(other, "other").siteId);
    }

    private static <T> List<T> copy(List<T> values) {
        Objects.requireNonNull(values, "occurrences");
        ArrayList<T> result = new ArrayList<>();
        for (T value : values) {
            result.add(Objects.requireNonNull(value, "occurrences must not contain null"));
        }
        return List.copyOf(result);
    }
}
