package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.LyraType;
import io.mindspice.lyra.compiler.types.TupleType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * One finite may-value alternative. Aggregate identities and callable transfer
 * values are retained at their exact routes; binding mutability is not encoded
 * here.
 */
public record ValueAlternative(
        LyraType type,
        List<AggregateIdentityFact> aggregateIdentities,
        List<CallableFlow> callableFlows,
        List<NilProvenance> nilProvenance,
        List<NominalObjectFact> objects)
        implements Comparable<ValueAlternative> {
    public ValueAlternative {
        Objects.requireNonNull(type, "type");
        aggregateIdentities = canonicalFacts(aggregateIdentities);
        callableFlows = canonicalCallables(callableFlows);
        nilProvenance = canonicalNils(nilProvenance);
        objects = List.copyOf(objects).stream().distinct().sorted().toList();
        for (NominalObjectFact object : objects) {
            if (!typeAt(type, object.route()).withoutQualifiers().equals(object.identity().type())) {
                throw new IllegalArgumentException("object identity route does not denote its exact nominal type");
            }
        }
        for (AggregateIdentityFact fact : aggregateIdentities) {
            LyraType projected = typeAt(type, fact.route()).withoutQualifiers();
            if (!(projected instanceof ArrayType array)
                    || !array.equals(fact.identity().arrayType())) {
                throw new IllegalArgumentException(
                        "aggregate identity route does not denote its array type: " + fact.route());
            }
        }
        for (CallableFlow callable : callableFlows) {
            if (!(typeAt(type, callable.route()).withoutQualifiers() instanceof FunctionType)) {
                throw new IllegalArgumentException(
                        "callable flow route does not denote a function type: " + callable.route());
            }
        }
        for (NilProvenance nil : nilProvenance) {
            if (!typeAt(type, nil.route()).isNilable()) {
                throw new IllegalArgumentException(
                        "nil provenance route does not denote a nilable contract: " + nil.route());
            }
        }
    }

    public ValueAlternative(LyraType type, List<AggregateIdentityFact> aggregateIdentities,
            List<CallableFlow> callableFlows, List<NilProvenance> nilProvenance) {
        this(type, aggregateIdentities, callableFlows, nilProvenance, List.of());
    }

    public static ValueAlternative object(NominalObjectIdentity identity, OwnershipWitness witness) {
        return new ValueAlternative(identity.type(), List.of(), List.of(), List.of(),
                List.of(new NominalObjectFact(identity, ProjectionPath.root(), witness)));
    }

    /** Compatibility constructor for callers without route-specific nil provenance. */
    public ValueAlternative(
            LyraType type,
            List<AggregateIdentityFact> aggregateIdentities,
            List<CallableFlow> callableFlows) {
        this(type, aggregateIdentities, callableFlows, List.of());
    }

    /** Compatibility constructor for aggregate-only callers. */
    public ValueAlternative(
            LyraType type,
            List<? extends AggregateIdentityFact> aggregateIdentities) {
        this(type, new ArrayList<>(aggregateIdentities), List.of(), List.of());
    }

    public static ValueAlternative scalar(LyraType type) {
        return new ValueAlternative(type, List.of(), List.of(), List.of());
    }

    public static ValueAlternative nil(LyraType type, NilProvenance provenance) {
        return new ValueAlternative(type, List.of(), List.of(),
                List.of(Objects.requireNonNull(provenance, "provenance")));
    }

    public static ValueAlternative array(ArrayIdentity identity, OwnershipWitness witness) {
        Objects.requireNonNull(identity, "identity");
        return new ValueAlternative(
                identity.arrayType(),
                List.of(new AggregateIdentityFact(identity, ProjectionPath.root(), witness)),
                List.of(),
                List.of());
    }

    public static ValueAlternative callable(LyraType type, CallableFlow callable) {
        return new ValueAlternative(type, List.of(), List.of(
                Objects.requireNonNull(callable, "callable")), List.of());
    }

    public static ValueAlternative of(
            LyraType type, List<? extends AggregateIdentityFact> aggregateIdentities) {
        return new ValueAlternative(
                type, new ArrayList<>(aggregateIdentities), List.of(), List.of());
    }

    public static ValueAlternative of(
            LyraType type,
            List<? extends AggregateIdentityFact> aggregateIdentities,
            List<? extends CallableFlow> callableFlows) {
        return new ValueAlternative(
                type,
                new ArrayList<>(aggregateIdentities),
                new ArrayList<>(callableFlows),
                List.of());
    }

    public static ValueAlternative of(
            LyraType type,
            List<? extends AggregateIdentityFact> aggregateIdentities,
            List<? extends CallableFlow> callableFlows,
            List<? extends NilProvenance> nilProvenance) {
        return new ValueAlternative(
                type,
                new ArrayList<>(aggregateIdentities),
                new ArrayList<>(callableFlows),
                new ArrayList<>(nilProvenance));
    }

    public boolean carriesAggregateIdentity() {
        return !aggregateIdentities.isEmpty();
    }

    public static ValueAlternative of(LyraType type, List<? extends AggregateIdentityFact> identities,
            List<? extends CallableFlow> callables, List<? extends NilProvenance> nils,
            List<? extends NominalObjectFact> objects) {
        return new ValueAlternative(type, List.copyOf(identities), List.copyOf(callables), List.copyOf(nils), List.copyOf(objects));
    }

    public List<AggregateIdentityFact> identities() {
        return aggregateIdentities;
    }

    public List<CallableFlow> callables() {
        return callableFlows;
    }

    public List<CallableFlow> callableFlows() {
        return callableFlows;
    }

    /** Returns the statically projected Lyra type, preserving its qualifiers. */
    public static LyraType typeAt(LyraType type, ProjectionPath route) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(route, "route");
        LyraType current = type;
        for (ProjectionStep step : route.steps()) {
            LyraType shape = current.withoutQualifiers();
            if (step instanceof ProjectionStep.NominalMember member) {
                if (!shape.equals(member.owner())) {
                    throw new IllegalArgumentException("nominal member route belongs to another type: " + route);
                }
                current = member.type();
            } else if (step instanceof ProjectionStep.TupleMember member) {
                if (!(shape instanceof TupleType tuple) || member.index() >= tuple.arity()) {
                    throw new IllegalArgumentException(
                            "tuple member route is incompatible with " + type + ": " + route);
                }
                current = tuple.memberType(member.index());
            } else {
                if (!(shape instanceof ArrayType array)) {
                    throw new IllegalArgumentException(
                            "array route is incompatible with " + type + ": " + route);
                }
                current = array.elementType();
            }
        }
        return current;
    }

    /** Selects a nested value and rebases identities below the selected route. */
    public ValueAlternative select(ProjectionPath selection) {
        Objects.requireNonNull(selection, "selection");
        LyraType selectedType = typeAt(type, selection);
        ArrayList<AggregateIdentityFact> selectedFacts = new ArrayList<>();
        for (AggregateIdentityFact fact : aggregateIdentities) {
            ProjectionPath factRoute = fact.route();
            if (factRoute.depth() < selection.depth() || !selection.overlaps(factRoute)) {
                continue;
            }
            selectedFacts.add(fact.withRoute(factRoute.suffix(selection.depth())));
        }
        ArrayList<CallableFlow> selectedCallables = new ArrayList<>();
        for (CallableFlow callable : callableFlows) {
            ProjectionPath callableRoute = callable.route();
            if (callableRoute.depth() < selection.depth() || !selection.overlaps(callableRoute)) {
                continue;
            }
            selectedCallables.add(callable.withRoute(callableRoute.suffix(selection.depth())));
        }
        ArrayList<NilProvenance> selectedNils = new ArrayList<>();
        for (NilProvenance nil : nilProvenance) {
            ProjectionPath nilRoute = nil.route();
            if (nilRoute.depth() < selection.depth() || !selection.overlaps(nilRoute)) {
                continue;
            }
            selectedNils.add(nil.withRoute(nilRoute.suffix(selection.depth())));
        }
        return new ValueAlternative(
                selectedType, selectedFacts, selectedCallables, selectedNils,
                objects.stream().filter(fact -> fact.route().depth() >= selection.depth() && selection.overlaps(fact.route()))
                        .map(fact -> fact.withRoute(fact.route().suffix(selection.depth()))).toList());
    }

    /**
     * Replaces an exact selected route, preserving the identity facts of
     * unrelated siblings and ancestors.
     */
    ValueAlternative replaceExact(ProjectionPath route, ValueAlternative replacement) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(replacement, "replacement");
        if (!route.isExact()) {
            throw new IllegalArgumentException("exact replacement route contains a wildcard: " + route);
        }
        LyraType targetType = typeAt(type, route);
        if (!targetType.equals(replacement.type())) {
            throw new IllegalArgumentException(
                    "replacement type " + replacement.type() + " does not match route type " + targetType);
        }
        if (route.isRoot()) {
            return replacement;
        }
        ArrayList<AggregateIdentityFact> facts = new ArrayList<>();
        for (AggregateIdentityFact fact : aggregateIdentities) {
            if (!route.selects(fact.route())) {
                facts.add(fact);
            }
        }
        for (AggregateIdentityFact fact : replacement.aggregateIdentities()) {
            facts.add(fact.prefixedBy(route));
        }
        ArrayList<CallableFlow> callables = new ArrayList<>();
        for (CallableFlow callable : callableFlows) {
            if (!route.selects(callable.route())) {
                callables.add(callable);
            }
        }
        for (CallableFlow callable : replacement.callableFlows()) {
            callables.add(callable.prefixedBy(route));
        }
        ArrayList<NilProvenance> nils = new ArrayList<>();
        for (NilProvenance nil : nilProvenance) {
            if (!route.selects(nil.route())) {
                nils.add(nil);
            }
        }
        for (NilProvenance nil : replacement.nilProvenance()) {
            nils.add(nil.prefixedBy(route));
        }
        List<NominalObjectFact> replacedObjects = new ArrayList<>(objects.stream()
                .filter(fact -> !route.selects(fact.route())).toList());
        replacement.objects.forEach(fact -> replacedObjects.add(fact.prefixedBy(route)));
        return new ValueAlternative(type, facts, callables, nils, replacedObjects);
    }

    /** Removes all facts below an exact route without adding a replacement. */
    ValueAlternative clearExact(ProjectionPath route) {
        Objects.requireNonNull(route, "route");
        if (!route.isExact()) {
            throw new IllegalArgumentException("exact clear route contains a wildcard: " + route);
        }
        typeAt(type, route);
        if (route.isRoot()) {
            return new ValueAlternative(type, List.of(), List.of(), List.of());
        }
        return new ValueAlternative(
                type,
                aggregateIdentities.stream()
                        .filter(fact -> !route.selects(fact.route()))
                        .toList(),
                callableFlows.stream()
                        .filter(callable -> !route.selects(callable.route()))
                        .toList(),
                nilProvenance.stream()
                        .filter(nil -> !route.selects(nil.route()))
                        .toList(), objects.stream().filter(fact -> !route.selects(fact.route())).toList());
    }

    /** Adds a wildcard replacement while retaining every previous possibility. */
    ValueAlternative replaceUnknown(ProjectionPath route, ValueAlternative replacement) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(replacement, "replacement");
        if (!route.containsWildcard()) {
            throw new IllegalArgumentException("unknown-index replacement needs a wildcard route: " + route);
        }
        LyraType targetType = typeAt(type, route);
        if (!targetType.equals(replacement.type())) {
            throw new IllegalArgumentException(
                    "replacement type " + replacement.type() + " does not match route type " + targetType);
        }
        ArrayList<AggregateIdentityFact> facts = new ArrayList<>(aggregateIdentities);
        for (AggregateIdentityFact fact : replacement.aggregateIdentities()) {
            facts.add(fact.prefixedBy(route));
        }
        ArrayList<CallableFlow> callables = new ArrayList<>(callableFlows);
        for (CallableFlow callable : replacement.callableFlows()) {
            callables.add(callable.prefixedBy(route));
        }
        ArrayList<NilProvenance> nils = new ArrayList<>(nilProvenance);
        for (NilProvenance nil : replacement.nilProvenance()) {
            nils.add(nil.prefixedBy(route));
        }
        List<NominalObjectFact> joinedObjects = new ArrayList<>(objects);
        replacement.objects.forEach(fact -> joinedObjects.add(fact.prefixedBy(route)));
        return new ValueAlternative(type, facts, callables, nils, joinedObjects);
    }

    private static List<AggregateIdentityFact> canonicalFacts(
            List<? extends AggregateIdentityFact> facts) {
        Objects.requireNonNull(facts, "aggregateIdentities");
        LinkedHashSet<AggregateIdentityFact> unique = new LinkedHashSet<>();
        for (AggregateIdentityFact fact : facts) {
            unique.add(Objects.requireNonNull(fact, "aggregateIdentities must not contain null"));
        }
        ArrayList<AggregateIdentityFact> sorted = new ArrayList<>(unique);
        sorted.sort(AggregateIdentityFact.comparator());
        return List.copyOf(sorted);
    }

    private static List<CallableFlow> canonicalCallables(
            List<? extends CallableFlow> callables) {
        Objects.requireNonNull(callables, "callableFlows");
        LinkedHashSet<CallableFlow> unique = new LinkedHashSet<>();
        for (CallableFlow callable : callables) {
            unique.add(Objects.requireNonNull(callable, "callableFlows must not contain null"));
        }
        ArrayList<CallableFlow> sorted = new ArrayList<>(unique);
        sorted.sort(CallableFlow::compareTo);
        return List.copyOf(sorted);
    }

    private static List<NilProvenance> canonicalNils(
            List<? extends NilProvenance> values) {
        Objects.requireNonNull(values, "nilProvenance");
        LinkedHashSet<NilProvenance> unique = new LinkedHashSet<>();
        for (NilProvenance value : values) {
            unique.add(Objects.requireNonNull(value,
                    "nilProvenance must not contain null"));
        }
        return unique.stream().sorted().toList();
    }

    public static Comparator<ValueAlternative> comparator() {
        return Comparator.naturalOrder();
    }

    @Override
    public int compareTo(ValueAlternative other) {
        Objects.requireNonNull(other, "other");
        int typeComparison = type.canonicalSpelling().compareTo(other.type.canonicalSpelling());
        if (typeComparison != 0) {
            return typeComparison;
        }
        int countComparison = Integer.compare(
                aggregateIdentities.size(), other.aggregateIdentities.size());
        if (countComparison != 0) {
            return countComparison;
        }
        for (int index = 0; index < aggregateIdentities.size(); index++) {
            int factComparison = aggregateIdentities.get(index)
                    .compareTo(other.aggregateIdentities.get(index));
            if (factComparison != 0) {
                return factComparison;
            }
        }
        countComparison = Integer.compare(callableFlows.size(), other.callableFlows.size());
        if (countComparison != 0) {
            return countComparison;
        }
        for (int index = 0; index < callableFlows.size(); index++) {
            int callableComparison = callableFlows.get(index)
                    .compareTo(other.callableFlows.get(index));
            if (callableComparison != 0) {
                return callableComparison;
            }
        }
        countComparison = Integer.compare(nilProvenance.size(), other.nilProvenance.size());
        if (countComparison != 0) {
            return countComparison;
        }
        for (int index = 0; index < nilProvenance.size(); index++) {
            int nilComparison = nilProvenance.get(index)
                    .compareTo(other.nilProvenance.get(index));
            if (nilComparison != 0) {
                return nilComparison;
            }
        }
        countComparison = Integer.compare(objects.size(), other.objects.size());
        if (countComparison != 0) return countComparison;
        for (int index = 0; index < objects.size(); index++) {
            int compared = objects.get(index).compareTo(other.objects.get(index));
            if (compared != 0) return compared;
        }
        return 0;
    }
}
