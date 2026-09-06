package io.mindspice.lyra.compiler.semantic.flow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** A deterministic finite set of immutable value alternatives. */
public record ValueAlternatives(List<ValueAlternative> alternatives)
        implements Iterable<ValueAlternative> {
    public ValueAlternatives {
        Objects.requireNonNull(alternatives, "alternatives");
        LinkedHashSet<ValueAlternative> unique = new LinkedHashSet<>();
        for (ValueAlternative alternative : alternatives) {
            unique.add(Objects.requireNonNull(
                    alternative, "alternatives must not contain null"));
        }
        ArrayList<ValueAlternative> sorted = new ArrayList<>(unique);
        sorted.sort(ValueAlternative.comparator());
        alternatives = List.copyOf(sorted);
    }

    public static ValueAlternatives empty() {
        return new ValueAlternatives(List.of());
    }

    public static ValueAlternatives singleton(ValueAlternative alternative) {
        return new ValueAlternatives(List.of(alternative));
    }

    public static ValueAlternatives of(ValueAlternative... alternatives) {
        Objects.requireNonNull(alternatives, "alternatives");
        return new ValueAlternatives(List.of(alternatives));
    }

    public static ValueAlternatives copyOf(Collection<? extends ValueAlternative> alternatives) {
        Objects.requireNonNull(alternatives, "alternatives");
        return new ValueAlternatives(new ArrayList<>(alternatives));
    }

    public boolean isEmpty() {
        return alternatives.isEmpty();
    }

    public int size() {
        return alternatives.size();
    }

    public ValueAlternative only() {
        if (alternatives.size() != 1) {
            throw new IllegalStateException("expected one value alternative, found " + alternatives.size());
        }
        return alternatives.getFirst();
    }

    public ValueAlternatives join(ValueAlternatives other) {
        Objects.requireNonNull(other, "other");
        ArrayList<ValueAlternative> joined = new ArrayList<>(alternatives.size() + other.size());
        joined.addAll(alternatives);
        joined.addAll(other.alternatives);
        return new ValueAlternatives(joined);
    }

    public ValueAlternatives coalesceJoin(ValueAlternatives other) {
        Objects.requireNonNull(other, "other");
        ValueAlternatives nonNil = new ValueAlternatives(alternatives.stream()
                .filter(value -> value.nilProvenance().stream()
                        .noneMatch(nil -> nil.route().isRoot()))
                .toList());
        return nonNil.join(other);
    }

    public ValueAlternatives select(ProjectionPath selection) {
        Objects.requireNonNull(selection, "selection");
        return new ValueAlternatives(alternatives.stream().map(value -> value.select(selection)).toList());
    }

    ValueAlternatives replaceExact(ProjectionPath route, ValueAlternatives replacement) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(replacement, "replacement");
        if (alternatives.isEmpty()) {
            return this;
        }
        if (replacement.isEmpty()) {
            return new ValueAlternatives(alternatives.stream()
                    .map(value -> value.clearExact(route))
                    .toList());
        }
        ArrayList<ValueAlternative> updated = new ArrayList<>();
        for (ValueAlternative current : alternatives) {
            for (ValueAlternative next : replacement.alternatives) {
                updated.add(current.replaceExact(route, next));
            }
        }
        return new ValueAlternatives(updated);
    }

    ValueAlternatives replaceUnknown(ProjectionPath route, ValueAlternatives replacement) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(replacement, "replacement");
        if (alternatives.isEmpty() || replacement.isEmpty()) {
            return this;
        }
        ArrayList<ValueAlternative> updated = new ArrayList<>();
        for (ValueAlternative current : alternatives) {
            for (ValueAlternative next : replacement.alternatives) {
                updated.add(current.replaceUnknown(route, next));
            }
        }
        return new ValueAlternatives(updated);
    }

    @Override
    public Iterator<ValueAlternative> iterator() {
        return alternatives.iterator();
    }
}
