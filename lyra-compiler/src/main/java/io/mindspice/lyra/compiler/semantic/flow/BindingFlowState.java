package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.types.BindingContract;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Immutable source-position snapshot of binding values.
 *
 * <p>The binding contract, including binding-local {@code @mut}, is stored
 * separately from aggregate identities and callable transfer data carried by
 * each value alternative. Every update returns a new snapshot.</p>
 */
public final class BindingFlowState {
    private final NavigableMap<DeclarationId, BindingFlowValue> bindings;
    private final NavigableMap<DeclarationId, ValueAlternatives> sharedCells;
    private final NavigableMap<NominalObjectIdentity, NominalObjectState> objects;

    private BindingFlowState(
            Map<DeclarationId, BindingFlowValue> bindings,
            Map<DeclarationId, ValueAlternatives> sharedCells) {
        this(bindings, sharedCells, Map.of());
    }

    private BindingFlowState(Map<DeclarationId, BindingFlowValue> bindings,
            Map<DeclarationId, ValueAlternatives> sharedCells, Map<NominalObjectIdentity, NominalObjectState> objects) {
        this.bindings = immutableBindings(bindings);
        this.sharedCells = immutableCells(sharedCells);
        var copy = new TreeMap<NominalObjectIdentity, NominalObjectState>();
        Objects.requireNonNull(objects, "objects").forEach((identity, state) -> {
            if (!identity.type().equals(state.schema().type())) throw new IllegalArgumentException("object identity/schema mismatch");
            copy.put(identity, state);
        });
        this.objects = Collections.unmodifiableNavigableMap(copy);
    }

    public NavigableMap<NominalObjectIdentity, NominalObjectState> objects() { return objects; }

    public static BindingFlowState of(Map<DeclarationId, BindingFlowValue> bindings,
            Map<DeclarationId, ValueAlternatives> cells, Map<NominalObjectIdentity, NominalObjectState> objects) {
        return new BindingFlowState(bindings, cells, objects);
    }

    public BindingFlowState withObject(NominalObjectIdentity identity, NominalObjectState state) {
        var updated = new TreeMap<>(objects);
        updated.put(identity, state);
        return new BindingFlowState(bindings, sharedCells, updated);
    }

    /** Removes one compiler-synthetic lexical binding after its bounded use. */
    public BindingFlowState withoutBinding(DeclarationId declaration) {
        Objects.requireNonNull(declaration, "declaration");
        if (!bindings.containsKey(declaration)) return this;
        var updated = new TreeMap<>(bindings);
        updated.remove(declaration);
        return new BindingFlowState(updated, sharedCells, objects);
    }

    public static BindingFlowState empty() {
        return new BindingFlowState(Map.of(), Map.of());
    }

    public static BindingFlowState of(Map<DeclarationId, BindingFlowValue> bindings) {
        return new BindingFlowState(bindings, Map.of());
    }

    public static BindingFlowState of(
            Map<DeclarationId, BindingFlowValue> bindings,
            Map<DeclarationId, ValueAlternatives> sharedCells) {
        return new BindingFlowState(bindings, sharedCells);
    }

    /** Deterministically ordered, deeply immutable binding snapshot. */
    public NavigableMap<DeclarationId, BindingFlowValue> bindings() {
        return bindings;
    }

    public Optional<BindingFlowValue> binding(DeclarationId declaration) {
        return Optional.ofNullable(bindings.get(Objects.requireNonNull(declaration, "declaration")));
    }

    /** Stable shared-cell identities paired with their current immutable snapshots. */
    public NavigableMap<DeclarationId, ValueAlternatives> sharedCells() {
        return sharedCells;
    }

    public Optional<ValueAlternatives> sharedCell(DeclarationId cell) {
        return Optional.ofNullable(sharedCells.get(Objects.requireNonNull(cell, "cell")));
    }

    public BindingFlowValue requireBinding(DeclarationId declaration) {
        return binding(declaration).orElseThrow(() -> new IllegalArgumentException(
                "binding is not present in this flow snapshot: " + declaration));
    }

    /** Adds one new binding; duplicate identities are rejected rather than merged. */
    public BindingFlowState bind(
            DeclarationId declaration,
            BindingContract contract,
            ValueAlternatives alternatives) {
        Objects.requireNonNull(declaration, "declaration");
        if (bindings.containsKey(declaration)) {
            throw new IllegalArgumentException("binding is already present: " + declaration);
        }
        TreeMap<DeclarationId, BindingFlowValue> next = new TreeMap<>(bindings);
        next.put(declaration, new BindingFlowValue(contract, alternatives));
        return new BindingFlowState(next, sharedCells, objects);
    }

    /** Strongly publishes the latest immutable snapshot for one stable shared cell. */
    public BindingFlowState replaceSharedCell(
            DeclarationId cell,
            ValueAlternatives replacement) {
        Objects.requireNonNull(cell, "cell");
        Objects.requireNonNull(replacement, "replacement");
        TreeMap<DeclarationId, ValueAlternatives> next = new TreeMap<>(sharedCells);
        next.put(cell, replacement);
        return new BindingFlowState(bindings, next, objects);
    }

    /** Strongly replaces the complete binding value and discards all old alternatives. */
    public BindingFlowState replaceWholeBinding(
            DeclarationId declaration, ValueAlternatives replacement) {
        Objects.requireNonNull(replacement, "replacement");
        BindingFlowValue current = requireBinding(declaration);
        return withValue(declaration, current.withAlternatives(replacement));
    }

    /**
     * Strongly replaces one exact route, preserving unrelated siblings and
     * ancestor identities.  The route cannot contain an unknown selector.
     */
    public BindingFlowState replaceExactRoute(
            DeclarationId declaration,
            ProjectionPath route,
            ValueAlternatives replacement) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(replacement, "replacement");
        if (!route.isExact()) {
            throw new IllegalArgumentException(
                    "exact route replacement cannot contain an unknown selector: " + route);
        }
        if (route.isRoot()) {
            return replaceWholeBinding(declaration, replacement);
        }
        BindingFlowValue current = requireBinding(declaration);
        return withValue(declaration,
                current.withAlternatives(current.alternatives().replaceExact(route, replacement)));
    }

    /**
     * Weakly replaces an unknown-index route.  All prior alternatives remain
     * possible and the replacement alternatives are added at the wildcard
     * route.
     */
    public BindingFlowState replaceUnknownIndex(
            DeclarationId declaration,
            ProjectionPath route,
            ValueAlternatives replacement) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(replacement, "replacement");
        if (!route.containsWildcard()) {
            throw new IllegalArgumentException(
                    "unknown-index replacement needs a wildcard route: " + route);
        }
        BindingFlowValue current = requireBinding(declaration);
        return withValue(declaration,
                current.withAlternatives(current.alternatives().replaceUnknown(route, replacement)));
    }

    /** Conservative may-state join used for branches and coalescing. */
    public BindingFlowState join(BindingFlowState other) {
        Objects.requireNonNull(other, "other");
        TreeMap<DeclarationId, BindingFlowValue> joined = new TreeMap<>();
        joined.putAll(bindings);
        other.bindings.forEach((declaration, right) -> {
            BindingFlowValue left = joined.get(declaration);
            if (left == null) {
                joined.put(declaration, right);
                return;
            }
            if (!left.contract().equals(right.contract())) {
                throw new IllegalArgumentException(
                        "branch states disagree about binding contract: " + declaration);
            }
            joined.put(declaration,
                    new BindingFlowValue(left.contract(), left.alternatives().join(right.alternatives())));
        });
        TreeMap<DeclarationId, ValueAlternatives> joinedCells = new TreeMap<>(sharedCells);
        other.sharedCells.forEach((cell, right) -> {
            ValueAlternatives left = joinedCells.get(cell);
            joinedCells.put(cell, left == null ? right : left.join(right));
        });
        var joinedObjects = new TreeMap<>(objects);
        other.objects.forEach((identity, state) -> joinedObjects.merge(identity, state, NominalObjectState::join));
        return new BindingFlowState(joined, joinedCells, joinedObjects);
    }

    public BindingFlowState branchJoin(BindingFlowState other) {
        return join(other);
    }

    public BindingFlowState coalesceJoin(BindingFlowState other) {
        return join(other);
    }

    private BindingFlowState withValue(DeclarationId declaration, BindingFlowValue value) {
        TreeMap<DeclarationId, BindingFlowValue> next = new TreeMap<>(bindings);
        next.put(Objects.requireNonNull(declaration, "declaration"),
                Objects.requireNonNull(value, "value"));
        return new BindingFlowState(next, sharedCells, objects);
    }

    private static NavigableMap<DeclarationId, BindingFlowValue> immutableBindings(
            Map<DeclarationId, BindingFlowValue> bindings) {
        Objects.requireNonNull(bindings, "bindings");
        TreeMap<DeclarationId, BindingFlowValue> ordered = new TreeMap<>();
        bindings.forEach((declaration, value) -> ordered.put(
                Objects.requireNonNull(declaration, "binding declaration"),
                Objects.requireNonNull(value, "binding value")));
        return Collections.unmodifiableNavigableMap(ordered);
    }

    private static NavigableMap<DeclarationId, ValueAlternatives> immutableCells(
            Map<DeclarationId, ValueAlternatives> cells) {
        Objects.requireNonNull(cells, "sharedCells");
        TreeMap<DeclarationId, ValueAlternatives> ordered = new TreeMap<>();
        cells.forEach((cell, values) -> ordered.put(
                Objects.requireNonNull(cell, "shared cell identity"),
                Objects.requireNonNull(values, "shared cell snapshot")));
        return Collections.unmodifiableNavigableMap(ordered);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof BindingFlowState state
                && bindings.equals(state.bindings)
                && sharedCells.equals(state.sharedCells)
                && objects.equals(state.objects);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bindings, sharedCells, objects);
    }

    @Override
    public String toString() {
        return "bindings=" + bindings + ", sharedCells=" + sharedCells + (objects.isEmpty() ? "" : ", objects=" + objects);
    }
}
