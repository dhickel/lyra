package io.mindspice.lyra.compiler.semantic.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable, canonically ordered path from a binding root to a projected
 * value.  The empty path denotes the complete binding value.
 */
public record ProjectionPath(List<ProjectionStep> steps)
        implements Comparable<ProjectionPath> {
    public ProjectionPath {
        Objects.requireNonNull(steps, "steps");
        ArrayList<ProjectionStep> copy = new ArrayList<>(steps.size());
        for (ProjectionStep step : steps) {
            copy.add(Objects.requireNonNull(step, "steps must not contain null"));
        }
        steps = List.copyOf(copy);
    }

    public static ProjectionPath root() {
        return new ProjectionPath(List.of());
    }

    public static ProjectionPath empty() {
        return root();
    }

    public static ProjectionPath of(List<? extends ProjectionStep> steps) {
        Objects.requireNonNull(steps, "steps");
        return new ProjectionPath(new ArrayList<>(steps));
    }

    public static ProjectionPath of(ProjectionStep... steps) {
        Objects.requireNonNull(steps, "steps");
        return new ProjectionPath(List.of(steps));
    }

    public static ProjectionPath tupleMember(int index) {
        return root().append(ProjectionStep.tupleMember(index));
    }

    public static ProjectionPath arrayElement(int index) {
        return root().append(ProjectionStep.arrayElement(index));
    }

    public static ProjectionPath unknownArrayElement() {
        return root().append(ProjectionStep.unknownArrayElement());
    }

    public static ProjectionPath wildcardArrayElement() {
        return unknownArrayElement();
    }

    public boolean isRoot() {
        return steps.isEmpty();
    }

    public int depth() {
        return steps.size();
    }

    public boolean containsWildcard() {
        return steps.stream().anyMatch(ProjectionStep::isWildcardArrayElement);
    }

    public boolean isExact() {
        return !containsWildcard();
    }

    /** Appends one nested selector. */
    public ProjectionPath append(ProjectionStep step) {
        Objects.requireNonNull(step, "step");
        ArrayList<ProjectionStep> result = new ArrayList<>(steps.size() + 1);
        result.addAll(steps);
        result.add(step);
        return new ProjectionPath(result);
    }

    /** Appends every selector in {@code suffix}, preserving route order. */
    public ProjectionPath compose(ProjectionPath suffix) {
        Objects.requireNonNull(suffix, "suffix");
        if (suffix.isRoot()) {
            return this;
        }
        ArrayList<ProjectionStep> result = new ArrayList<>(steps.size() + suffix.steps.size());
        result.addAll(steps);
        result.addAll(suffix.steps);
        return new ProjectionPath(result);
    }

    /** Prepends one selector to this path. */
    public ProjectionPath prepend(ProjectionStep step) {
        Objects.requireNonNull(step, "step");
        ArrayList<ProjectionStep> result = new ArrayList<>(steps.size() + 1);
        result.add(step);
        result.addAll(steps);
        return new ProjectionPath(result);
    }

    /** Returns a path with {@code prefix} prepended to this path. */
    public ProjectionPath prefixedBy(ProjectionPath prefix) {
        return Objects.requireNonNull(prefix, "prefix").compose(this);
    }

    /**
     * Returns whether this path selects the value denoted by {@code candidate}.
     * A wildcard selects exact array elements as well as other wildcard
     * selections; an exact selector never selects a wildcard.
     */
    public boolean selects(ProjectionPath candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (steps.size() > candidate.steps.size()) {
            return false;
        }
        for (int index = 0; index < steps.size(); index++) {
            if (!steps.get(index).selects(candidate.steps.get(index))) {
                return false;
            }
        }
        return true;
    }

    /** Alias expressing the prefix interpretation of {@link #selects}. */
    public boolean isPrefixOf(ProjectionPath candidate) {
        return selects(candidate);
    }

    /** Returns whether two paths have a non-empty abstract intersection. */
    public boolean overlaps(ProjectionPath other) {
        Objects.requireNonNull(other, "other");
        int commonDepth = Math.min(steps.size(), other.steps.size());
        for (int index = 0; index < commonDepth; index++) {
            if (!steps.get(index).overlaps(other.steps.get(index))) {
                return false;
            }
        }
        return true;
    }

    /** Returns whether this path is an exact prefix of {@code candidate}. */
    public boolean isExactPrefixOf(ProjectionPath candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (!isExact() || steps.size() > candidate.steps.size()) {
            return false;
        }
        for (int index = 0; index < steps.size(); index++) {
            if (!steps.get(index).equals(candidate.steps.get(index))) {
                return false;
            }
        }
        return true;
    }

    /** Returns the suffix of {@code selected} after this selected prefix. */
    public Optional<ProjectionPath> suffixOf(ProjectionPath selected) {
        Objects.requireNonNull(selected, "selected");
        if (!selects(selected)) {
            return Optional.empty();
        }
        return Optional.of(new ProjectionPath(selected.steps.subList(steps.size(), selected.steps.size())));
    }

    /** Returns the suffix after this path when this path selects {@code selected}. */
    public Optional<ProjectionPath> suffixAfter(ProjectionPath prefix) {
        return Objects.requireNonNull(prefix, "prefix").suffixOf(this);
    }

    /** Returns a suffix by physical depth, for internal route rebasing. */
    public ProjectionPath suffix(int fromIndex) {
        if (fromIndex < 0 || fromIndex > steps.size()) {
            throw new IndexOutOfBoundsException("invalid projection suffix index: " + fromIndex);
        }
        return new ProjectionPath(steps.subList(fromIndex, steps.size()));
    }

    @Override
    public int compareTo(ProjectionPath other) {
        Objects.requireNonNull(other, "other");
        int commonDepth = Math.min(steps.size(), other.steps.size());
        for (int index = 0; index < commonDepth; index++) {
            int comparison = steps.get(index).compareTo(other.steps.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(steps.size(), other.steps.size());
    }

    @Override
    public String toString() {
        if (isRoot()) {
            return "$";
        }
        StringBuilder result = new StringBuilder("$");
        for (ProjectionStep step : steps) {
            result.append(step);
        }
        return result.toString();
    }
}
