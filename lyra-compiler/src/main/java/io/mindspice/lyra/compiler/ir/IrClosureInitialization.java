package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Explicit closure-slot initialization/linkage record.  It distinguishes a
 * function slot that may publish a recursive closure from an eager value
 * initializer that must not participate in a value cycle.
 */
public record IrClosureInitialization(
        LambdaId lambdaId,
        ModuleId moduleId,
        SourceSpan span,
        ScopeId scopeId,
        Optional<DeclarationId> ownerDeclaration,
        List<CaptureId> captures,
        List<DeclarationId> sharedCells,
        boolean recursive)
        implements ImmutablePhaseArtifact, Comparable<IrClosureInitialization> {
    public IrClosureInitialization {
        Objects.requireNonNull(lambdaId, "lambdaId");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(ownerDeclaration, "ownerDeclaration");
        captures = copy(captures, "captures");
        sharedCells = copy(sharedCells, "sharedCells");
        ArrayList<CaptureId> orderedCaptures = new ArrayList<>(captures);
        orderedCaptures.sort(Comparator.naturalOrder());
        ArrayList<DeclarationId> orderedCells = new ArrayList<>(sharedCells);
        orderedCells.sort(Comparator.naturalOrder());
        if (!captures.equals(orderedCaptures)
                || new LinkedHashSet<>(captures).size() != captures.size()
                || !sharedCells.equals(orderedCells)
                || new LinkedHashSet<>(sharedCells).size() != sharedCells.size()) {
            throw new IllegalArgumentException("closure initialization links must be unique and canonical");
        }
        if (!moduleId.sourceId().equals(span.sourceId())) {
            throw new IllegalArgumentException("closure initialization belongs to another module");
        }
    }

    public LambdaId id() {
        return lambdaId;
    }

    public List<DeclarationId> cellIds() {
        return sharedCells;
    }

    @Override
    public int compareTo(IrClosureInitialization other) {
        return lambdaId.compareTo(Objects.requireNonNull(other, "other").lambdaId);
    }

    private static <T> List<T> copy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<T> copy = new ArrayList<>();
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " must not contain null"));
        }
        return List.copyOf(copy);
    }
}
