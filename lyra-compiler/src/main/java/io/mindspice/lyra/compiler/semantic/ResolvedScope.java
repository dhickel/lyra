package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One immutable lexical scope and its direct children. */
public record ResolvedScope(
        ScopeId id,
        Optional<ScopeId> parent,
        ScopeKind kind,
        ModuleId moduleId,
        Optional<LambdaId> ownerLambda,
        SourceSpan span,
        List<DeclarationId> declarations,
        List<ScopeId> children) {
    public ResolvedScope {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(ownerLambda, "ownerLambda");
        Objects.requireNonNull(span, "span");
        if (!moduleId.sourceId().equals(span.sourceId())) {
            throw new IllegalArgumentException("scope span belongs to another module");
        }
        declarations = copy(declarations, "declarations");
        children = copy(children, "children");
    }

    public Optional<ScopeId> parentScope() {
        return parent;
    }

    public Optional<LambdaId> lambdaId() {
        return ownerLambda;
    }

    public List<DeclarationId> declarationIds() {
        return declarations;
    }

    public List<ScopeId> childScopeIds() {
        return children;
    }

    private static <T> List<T> copy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        for (T value : values) {
            Objects.requireNonNull(value, name + " must not contain null");
        }
        return List.copyOf(values);
    }
}
