package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.source.ModuleId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Complete immutable scope forest for the reachable module graph. */
public final class ScopeTree implements ImmutablePhaseArtifact {
    private final List<ResolvedScope> scopes;
    private final Map<ScopeId, ResolvedScope> byId;
    private final Map<ModuleId, ScopeId> moduleRoots;

    public ScopeTree(List<ResolvedScope> scopes, Map<ModuleId, ScopeId> moduleRoots) {
        Objects.requireNonNull(scopes, "scopes");
        Objects.requireNonNull(moduleRoots, "moduleRoots");

        List<ResolvedScope> ordered = new ArrayList<>();
        for (ResolvedScope scope : scopes) {
            ordered.add(Objects.requireNonNull(scope, "scopes must not contain null"));
        }
        ordered.sort(java.util.Comparator.comparing(ResolvedScope::id));

        LinkedHashMap<ScopeId, ResolvedScope> indexed = new LinkedHashMap<>();
        for (ResolvedScope scope : ordered) {
            if (indexed.put(scope.id(), scope) != null) {
                throw new IllegalArgumentException("duplicate scope identity: " + scope.id());
            }
        }

        List<Map.Entry<ModuleId, ScopeId>> roots = new ArrayList<>(moduleRoots.entrySet());
        roots.sort(Map.Entry.comparingByKey());
        LinkedHashMap<ModuleId, ScopeId> rootIndex = new LinkedHashMap<>();
        for (Map.Entry<ModuleId, ScopeId> entry : roots) {
            ModuleId module = Objects.requireNonNull(entry.getKey(), "module root key");
            ScopeId root = Objects.requireNonNull(entry.getValue(), "module root value");
            if (!indexed.containsKey(root)) {
                throw new IllegalArgumentException("module root is not present in scope tree: " + root);
            }
            if (rootIndex.put(module, root) != null) {
                throw new IllegalArgumentException("duplicate module root: " + module);
            }
        }

        Set<ScopeId> rootedScopes = new HashSet<>();
        for (ResolvedScope scope : ordered) {
            requireUnique(scope.children(), "duplicate child scope in " + scope.id());
            requireUnique(scope.declarations(), "duplicate declaration in " + scope.id());
            if (scope.kind() == ScopeKind.MODULE) {
                if (scope.parent().isPresent() || scope.ownerLambda().isPresent()) {
                    throw new IllegalArgumentException(
                            "module scopes cannot have parents or lambda owners");
                }
            } else {
                if (scope.parent().isEmpty()) {
                    throw new IllegalArgumentException("non-module scopes need a parent");
                }
                if (scope.kind() == ScopeKind.LAMBDA && scope.ownerLambda().isEmpty()) {
                    throw new IllegalArgumentException("lambda scopes need a lambda owner");
                }
            }
            scope.parent().ifPresent(parent -> {
                ResolvedScope parentScope = indexed.get(parent);
                if (parentScope == null || !parentScope.children().contains(scope.id())) {
                    throw new IllegalArgumentException("scope parent/child links are inconsistent");
                }
                if (!parentScope.moduleId().equals(scope.moduleId())) {
                    throw new IllegalArgumentException("child scope belongs to another module");
                }
                if (scope.span().startOffset() < parentScope.span().startOffset()
                        || scope.span().endOffset() > parentScope.span().endOffset()) {
                    throw new IllegalArgumentException(
                            "child scope span is outside its lexical parent");
                }
                if (scope.kind() != ScopeKind.LAMBDA
                        && !scope.ownerLambda().equals(parentScope.ownerLambda())) {
                    throw new IllegalArgumentException(
                            "nested scope changed its enclosing lambda owner");
                }
            });
            for (ScopeId child : scope.children()) {
                ResolvedScope childScope = indexed.get(child);
                if (childScope == null
                        || !childScope.parent().map(scope.id()::equals).orElse(false)) {
                    throw new IllegalArgumentException("scope child/parent links are inconsistent");
                }
            }
        }
        for (Map.Entry<ModuleId, ScopeId> entry : rootIndex.entrySet()) {
            ResolvedScope root = indexed.get(entry.getValue());
            if (root.kind() != ScopeKind.MODULE || !root.moduleId().equals(entry.getKey())) {
                throw new IllegalArgumentException("module root does not identify a module scope");
            }
            if (!rootedScopes.add(root.id())) {
                throw new IllegalArgumentException("one scope is the root of multiple modules");
            }
        }
        Set<ScopeId> moduleScopes = new HashSet<>();
        for (ResolvedScope scope : ordered) {
            if (scope.kind() == ScopeKind.MODULE) {
                moduleScopes.add(scope.id());
            }
        }
        if (!moduleScopes.equals(rootedScopes)) {
            throw new IllegalArgumentException(
                    "module roots do not cover exactly every module scope");
        }

        Set<ScopeId> reachable = new HashSet<>();
        Set<ScopeId> active = new HashSet<>();
        for (ScopeId root : rootedScopes) {
            visit(root, indexed, reachable, active);
        }
        if (reachable.size() != indexed.size()) {
            throw new IllegalArgumentException("scope tree contains disconnected scopes");
        }

        this.scopes = List.copyOf(ordered);
        this.byId = Collections.unmodifiableMap(indexed);
        this.moduleRoots = Collections.unmodifiableMap(rootIndex);
    }

    private static void visit(
            ScopeId id,
            Map<ScopeId, ResolvedScope> indexed,
            Set<ScopeId> reachable,
            Set<ScopeId> active) {
        if (!active.add(id)) {
            throw new IllegalArgumentException("scope tree contains a cycle at " + id);
        }
        if (!reachable.add(id)) {
            active.remove(id);
            return;
        }
        for (ScopeId child : indexed.get(id).children()) {
            visit(child, indexed, reachable, active);
        }
        active.remove(id);
    }

    private static <T> void requireUnique(List<T> values, String message) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(message);
        }
    }

    public List<ResolvedScope> scopes() {
        return scopes;
    }

    public List<ResolvedScope> all() {
        return scopes;
    }

    public Optional<ResolvedScope> scope(ScopeId id) {
        return Optional.ofNullable(byId.get(Objects.requireNonNull(id, "id")));
    }

    public ResolvedScope require(ScopeId id) {
        return scope(id).orElseThrow(() -> new IllegalArgumentException("unknown scope: " + id));
    }

    public Map<ModuleId, ScopeId> moduleRoots() {
        return moduleRoots;
    }

    public Optional<ScopeId> root(ModuleId moduleId) {
        return Optional.ofNullable(moduleRoots.get(Objects.requireNonNull(moduleId, "moduleId")));
    }

    public ScopeId rootScope(ModuleId moduleId) {
        return root(moduleId).orElseThrow(() -> new IllegalArgumentException(
                "module has no root scope: " + moduleId));
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof ScopeTree tree
                && scopes.equals(tree.scopes)
                && moduleRoots.equals(tree.moduleRoots);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scopes, moduleRoots);
    }

    @Override
    public String toString() {
        return "ScopeTree[scopes=" + scopes.size() + ", modules=" + moduleRoots.size() + "]";
    }
}
