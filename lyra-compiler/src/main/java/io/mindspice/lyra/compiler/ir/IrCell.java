package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.types.BindingContract;
import io.mindspice.lyra.compiler.semantic.flow.ValueAlternatives;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** One specialized shared mutable capture cell planned by semantic flow. */
public record IrCell(
        DeclarationId id,
        DeclarationId declarationId,
        ModuleId moduleId,
        ScopeId scopeId,
        BindingContract contract,
        List<CaptureId> captures,
        java.util.Optional<ValueAlternatives> initialValue)
        implements ImmutablePhaseArtifact {
    public IrCell {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(declarationId, "declarationId");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(contract, "contract");
        captures = copy(captures, "captures");
        ArrayList<CaptureId> orderedCaptures = new ArrayList<>(captures);
        orderedCaptures.sort(Comparator.naturalOrder());
        if (!captures.equals(orderedCaptures)
                || new LinkedHashSet<>(captures).size() != captures.size()) {
            throw new IllegalArgumentException("cell captures must be unique and canonical");
        }
        Objects.requireNonNull(initialValue, "initialValue");
        if (!id.equals(declarationId)) {
            throw new IllegalArgumentException("capture cell identity must be its declaration identity");
        }
        if (captures.isEmpty()) {
            throw new IllegalArgumentException("a shared capture cell needs a capture");
        }
    }

    public IrCell(
            DeclarationId id,
            DeclarationId declarationId,
            ModuleId moduleId,
            ScopeId scopeId,
            BindingContract contract,
            List<CaptureId> captures) {
        this(id, declarationId, moduleId, scopeId, contract, captures,
                java.util.Optional.empty());
    }

    public DeclarationId cellId() {
        return id;
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
