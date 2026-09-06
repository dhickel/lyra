package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.semantic.CaptureMode;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.BindingContract;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exact capture topology, including the shared mutable-cell boundary. */
public record IrCapture(
        CaptureId id,
        LambdaId lambdaId,
        DeclarationId declarationId,
        ModuleId moduleId,
        SourceSpan span,
        SourceSpan declarationSpan,
        CaptureMode mode,
        Optional<DeclarationId> sharedCellId,
        List<ReferenceId> references,
        BindingContract contract,
        FlowSiteId siteId)
        implements ImmutablePhaseArtifact {
    public IrCapture {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(lambdaId, "lambdaId");
        Objects.requireNonNull(declarationId, "declarationId");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(declarationSpan, "declarationSpan");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(sharedCellId, "sharedCellId");
        references = copy(references, "references");
        ArrayList<ReferenceId> orderedReferences = new ArrayList<>(references);
        orderedReferences.sort(Comparator.naturalOrder());
        if (!references.equals(orderedReferences)
                || new LinkedHashSet<>(references).size() != references.size()) {
            throw new IllegalArgumentException("capture references must be unique and canonical");
        }
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(siteId, "siteId");
        if (mode == CaptureMode.SHARED_MUTABLE_CELL && sharedCellId.isEmpty()) {
            throw new IllegalArgumentException("shared capture needs a cell identity");
        }
        if (mode == CaptureMode.IMMUTABLE_VALUE && sharedCellId.isPresent()) {
            throw new IllegalArgumentException("immutable capture cannot have a cell identity");
        }
    }

    public CaptureId captureId() {
        return id;
    }

    public boolean isSharedCell() {
        return mode == CaptureMode.SHARED_MUTABLE_CELL;
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
