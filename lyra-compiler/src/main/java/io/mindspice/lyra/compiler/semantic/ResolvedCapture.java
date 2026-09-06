package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One capture slot shared by all references to a declaration in one lambda. */
public record ResolvedCapture(
        CaptureId id,
        LambdaId lambdaId,
        DeclarationId declarationId,
        ModuleId moduleId,
        SourceSpan span,
        SourceSpan declarationSpan,
        CaptureMode mode,
        Optional<DeclarationId> sharedCellId,
        List<io.mindspice.lyra.compiler.identity.ReferenceId> references) {
    public ResolvedCapture {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(lambdaId, "lambdaId");
        Objects.requireNonNull(declarationId, "declarationId");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(declarationSpan, "declarationSpan");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(sharedCellId, "sharedCellId");
        references = copy(references, "references");
        if (!moduleId.sourceId().equals(span.sourceId())) {
            throw new IllegalArgumentException("capture span belongs to another module");
        }
        if (mode == CaptureMode.SHARED_MUTABLE_CELL && sharedCellId.isEmpty()) {
            throw new IllegalArgumentException("mutable captures need a shared cell identity");
        }
        if (mode == CaptureMode.IMMUTABLE_VALUE && sharedCellId.isPresent()) {
            throw new IllegalArgumentException("immutable captures cannot have a shared cell");
        }
    }

    public CaptureId captureId() {
        return id;
    }

    public DeclarationId declaration() {
        return declarationId;
    }

    public boolean isSharedCell() {
        return mode == CaptureMode.SHARED_MUTABLE_CELL;
    }

    public Optional<DeclarationId> cellId() {
        return sharedCellId;
    }

    private static <T> List<T> copy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        for (T value : values) {
            Objects.requireNonNull(value, name + " must not contain null");
        }
        return List.copyOf(values);
    }
}
