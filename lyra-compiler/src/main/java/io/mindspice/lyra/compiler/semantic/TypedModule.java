package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.List;
import java.util.Objects;

/** Typed top-level evaluation order for one source module. */
public record TypedModule(
        ModuleId moduleId,
        ScopeId rootScope,
        SourceSpan span,
        List<TypedExpression> forms) implements ImmutablePhaseArtifact {
    public TypedModule {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(rootScope, "rootScope");
        Objects.requireNonNull(span, "span");
        forms = copy(forms, "forms");
        if (!moduleId.sourceId().equals(span.sourceId())) {
            throw new IllegalArgumentException("typed module span belongs to another module");
        }
        for (TypedExpression form : forms) {
            if (!moduleId.sourceId().equals(form.span().sourceId())) {
                throw new IllegalArgumentException("typed module form belongs to another module");
            }
        }
    }

    public List<TypedExpression> topLevelForms() {
        return forms;
    }

    private static <T> List<T> copy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        for (T value : values) {
            Objects.requireNonNull(value, name + " must not contain null");
        }
        return List.copyOf(values);
    }
}
