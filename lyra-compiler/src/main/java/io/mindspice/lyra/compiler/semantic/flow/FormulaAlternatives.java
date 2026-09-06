package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.types.LyraType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * A canonical finite set of symbolic value formulas for one typed root value.
 * No insertion order or hash-map iteration order can affect its identity.
 */
public record FormulaAlternatives(
        LyraType rootType,
        List<ValueFormula> formulas,
        List<ExactOverride> exactOverrides) implements Iterable<ValueFormula> {
    public record ExactOverride(ValueFormula formula, ProjectionPath route)
            implements Comparable<ExactOverride> {
        public ExactOverride {
            Objects.requireNonNull(formula, "formula");
            Objects.requireNonNull(route, "route");
        }

        @Override
        public int compareTo(ExactOverride other) {
            int formulaOrder = formula.compareTo(other.formula);
            return formulaOrder != 0 ? formulaOrder : route.compareTo(other.route);
        }
    }

    public FormulaAlternatives {
        Objects.requireNonNull(rootType, "rootType");
        Objects.requireNonNull(formulas, "formulas");
        Objects.requireNonNull(exactOverrides, "exactOverrides");
        LinkedHashSet<ValueFormula> unique = new LinkedHashSet<>();
        for (ValueFormula formula : formulas) {
            ValueFormula value = Objects.requireNonNull(
                    formula, "formulas must not contain null");
            validateRoute(rootType, value);
            unique.add(value);
        }
        ArrayList<ValueFormula> ordered = new ArrayList<>(unique);
        ordered.sort(ValueFormula::compareTo);
        formulas = List.copyOf(ordered);
        LinkedHashSet<ExactOverride> uniqueOverrides = new LinkedHashSet<>();
        for (ExactOverride override : exactOverrides) {
            ExactOverride value = Objects.requireNonNull(
                    override, "exactOverrides must not contain null");
            if (!unique.contains(value.formula())) {
                throw new IllegalArgumentException(
                        "formula override names an absent formula: " + value.formula());
            }
            if (value.route().isRoot() || !value.route().isExact()) {
                throw new IllegalArgumentException(
                        "formula override must be a non-root exact route: " + value.route());
            }
            ValueAlternative.typeAt(rootType, value.route());
            uniqueOverrides.add(value);
        }
        exactOverrides = uniqueOverrides.stream().sorted().toList();
    }

    public FormulaAlternatives(LyraType rootType, List<ValueFormula> formulas) {
        this(rootType, formulas, List.of());
    }

    public static FormulaAlternatives empty(LyraType rootType) {
        return new FormulaAlternatives(rootType, List.of());
    }

    public static FormulaAlternatives singleton(ValueFormula formula) {
        Objects.requireNonNull(formula, "formula");
        return new FormulaAlternatives(formula.type(), List.of(formula));
    }

    public static FormulaAlternatives of(
            LyraType rootType, ValueFormula... formulas) {
        Objects.requireNonNull(formulas, "formulas");
        return new FormulaAlternatives(rootType, List.of(formulas));
    }

    public static FormulaAlternatives copyOf(
            LyraType rootType, Collection<? extends ValueFormula> formulas) {
        Objects.requireNonNull(formulas, "formulas");
        return new FormulaAlternatives(rootType, new ArrayList<>(formulas));
    }

    public boolean isEmpty() {
        return formulas.isEmpty();
    }

    public int size() {
        return formulas.size();
    }

    public ValueFormula only() {
        if (formulas.size() != 1) {
            throw new IllegalStateException(
                    "expected one formula alternative, found " + formulas.size());
        }
        return formulas.getFirst();
    }

    public List<ValueFormula> alternatives() {
        return formulas;
    }

    List<ExactOverride> rebaseOverrides(
            ValueFormula original,
            Collection<? extends ValueFormula> replacements) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(replacements, "replacements");
        ArrayList<ExactOverride> rebased = new ArrayList<>();
        for (ProjectionPath route : overrideRoutes(original)) {
            for (ValueFormula replacement : replacements) {
                rebased.add(new ExactOverride(
                        Objects.requireNonNull(replacement, "replacement"), route));
            }
        }
        return List.copyOf(rebased);
    }

    /** Joins reachable paths without inventing a new formula. */
    public FormulaAlternatives join(FormulaAlternatives other) {
        Objects.requireNonNull(other, "other");
        requireSameShape(other);
        ArrayList<ValueFormula> joined = new ArrayList<>(formulas.size() + other.size());
        joined.addAll(formulas);
        joined.addAll(other.formulas);
        ArrayList<ExactOverride> guaranteedOverrides = new ArrayList<>();
        LinkedHashSet<ValueFormula> joinedFormulas = new LinkedHashSet<>(joined);
        for (ValueFormula formula : joinedFormulas) {
            boolean leftHas = formulas.contains(formula);
            boolean rightHas = other.formulas.contains(formula);
            List<ProjectionPath> leftMasks = overrideRoutes(formula);
            List<ProjectionPath> rightMasks = other.overrideRoutes(formula);
            if (leftHas && rightHas) {
                for (ProjectionPath left : leftMasks) {
                    for (ProjectionPath right : rightMasks) {
                        if (left.isExactPrefixOf(right)) {
                            guaranteedOverrides.add(new ExactOverride(formula, right));
                        } else if (right.isExactPrefixOf(left)) {
                            guaranteedOverrides.add(new ExactOverride(formula, left));
                        }
                    }
                }
            } else {
                List<ProjectionPath> masks = leftHas ? leftMasks : rightMasks;
                masks.forEach(route -> guaranteedOverrides.add(
                        new ExactOverride(formula, route)));
            }
        }
        return new FormulaAlternatives(rootType, joined, guaranteedOverrides);
    }

    public FormulaAlternatives branchJoin(FormulaAlternatives other) {
        return join(other);
    }

    public FormulaAlternatives coalesceJoin(FormulaAlternatives other) {
        return withoutRootNil().join(Objects.requireNonNull(other, "other"));
    }

    public FormulaAlternatives withoutRootNil() {
        List<ValueFormula> retained = formulas.stream()
                .filter(formula -> !(formula instanceof ValueFormula.Scalar scalar
                        && scalar.isNil() && scalar.resultRoute().isRoot()))
                .toList();
        return new FormulaAlternatives(rootType, retained, exactOverrides.stream()
                .filter(override -> retained.contains(override.formula()))
                .toList());
    }

    /** Reuses the formulas under a qualifier-only result contract. */
    public FormulaAlternatives asType(LyraType resultType) {
        Objects.requireNonNull(resultType, "resultType");
        if (!rootType.withoutQualifiers().equals(resultType.withoutQualifiers())) {
            throw new IllegalArgumentException("formula root shape changed from "
                    + rootType + " to " + resultType);
        }
        return new FormulaAlternatives(resultType, formulas, exactOverrides);
    }

    /** Selects a typed route and rebases the selected formulas to its root. */
    public FormulaAlternatives select(ProjectionPath selection) {
        Objects.requireNonNull(selection, "selection");
        LyraType selectedType = ValueAlternative.typeAt(rootType, selection);
        ArrayList<ValueFormula> selected = new ArrayList<>();
        ArrayList<ExactOverride> selectedOverrides = new ArrayList<>();
        for (ValueFormula formula : formulas) {
            ProjectionPath route = formula.resultRoute();
            if (!selection.overlaps(route) || maskedAt(selection, formula)) {
                continue;
            }
            ValueFormula selectedFormula;
            if (route.depth() < selection.depth()) {
                // A source placeholder for an aggregate root still denotes a
                // value at every statically selected descendant unless a
                // guaranteed exact replacement masks that ancestor formula.
                ProjectionPath sourceSuffix = selection.suffix(route.depth());
                selectedFormula = projectRootPlaceholder(
                        formula, sourceSuffix, selectedType);
                if (selectedFormula == null) {
                    continue;
                }
            } else {
                selectedFormula = formula.withResultRoute(
                        route.suffix(selection.depth()));
            }
            selected.add(selectedFormula);
            for (ProjectionPath override : overrideRoutes(formula)) {
                if (selection.selects(override)
                        && override.depth() > selection.depth()) {
                    selectedOverrides.add(new ExactOverride(
                            selectedFormula, override.suffix(selection.depth())));
                }
            }
        }
        return new FormulaAlternatives(selectedType, selected, selectedOverrides);
    }

    /**
     * Prefixes every routed formula with a containing aggregate route.
     * {@code containingType} is required because the root shape changes when
     * a value is embedded below a tuple member or array element.
     */
    public FormulaAlternatives prefixInto(
            LyraType containingType,
            ProjectionPath prefix) {
        Objects.requireNonNull(containingType, "containingType");
        Objects.requireNonNull(prefix, "prefix");
        return new FormulaAlternatives(containingType,
                formulas.stream().map(value -> value.prefixedBy(prefix)).toList(),
                exactOverrides.stream().map(override -> new ExactOverride(
                        override.formula().prefixedBy(prefix),
                        override.route().prefixedBy(prefix))).toList());
    }

    /** Prefixes formulas without changing the abstract root shape. */
    public FormulaAlternatives prefixedBy(ProjectionPath prefix) {
        Objects.requireNonNull(prefix, "prefix");
        if (prefix.isRoot()) {
            return this;
        }
        throw new IllegalArgumentException(
                "prefixing a formula set needs its containing root type: " + prefix);
    }

    public FormulaAlternatives prefix(ProjectionPath prefix) {
        return prefixedBy(prefix);
    }

    /** Strongly replaces an exact route and preserves unrelated descendants. */
    public FormulaAlternatives replaceExact(
            ProjectionPath route, FormulaAlternatives replacement) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(replacement, "replacement");
        if (!route.isExact()) {
            throw new IllegalArgumentException("exact replacement route contains a wildcard: " + route);
        }
        LyraType target = ValueAlternative.typeAt(rootType, route);
        if (!target.withoutQualifiers().equals(replacement.rootType.withoutQualifiers())) {
            throw new IllegalArgumentException("replacement root shape does not match route " + route);
        }
        if (route.isRoot()) {
            return replacement.asType(rootType);
        }
        ArrayList<ValueFormula> result = new ArrayList<>();
        for (ValueFormula formula : formulas) {
            if (!route.selects(formula.resultRoute())) {
                result.add(formula);
            }
        }
        result.addAll(replacement.formulas.stream()
                .map(value -> value.prefixedBy(route)).toList());
        ArrayList<ExactOverride> overrides = new ArrayList<>();
        for (ExactOverride existing : exactOverrides) {
            if (result.contains(existing.formula())
                    && !route.selects(existing.route())) {
                overrides.add(existing);
            }
        }
        for (ValueFormula formula : formulas) {
            if (result.contains(formula)
                    && route.overlaps(formula.resultRoute())
                    && !route.selects(formula.resultRoute())) {
                overrides.add(new ExactOverride(formula, route));
            }
        }
        overrides.addAll(replacement.exactOverrides.stream()
                .map(value -> new ExactOverride(
                        value.formula().prefixedBy(route),
                        value.route().prefixedBy(route))).toList());
        return new FormulaAlternatives(rootType, result, overrides);
    }

    /** Weakly adds formulas under an unknown array selector. */
    public FormulaAlternatives replaceUnknown(
            ProjectionPath route, FormulaAlternatives replacement) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(replacement, "replacement");
        if (!route.containsWildcard()) {
            throw new IllegalArgumentException("unknown replacement needs a wildcard route: " + route);
        }
        LyraType target = ValueAlternative.typeAt(rootType, route);
        if (!target.withoutQualifiers().equals(replacement.rootType.withoutQualifiers())) {
            throw new IllegalArgumentException("replacement root shape does not match route " + route);
        }
        ArrayList<ValueFormula> result = new ArrayList<>(formulas);
        result.addAll(replacement.formulas.stream()
                .map(value -> value.prefixedBy(route)).toList());
        return new FormulaAlternatives(rootType, result, exactOverrides);
    }

    public String canonicalKey() {
        StringBuilder result = new StringBuilder(rootType.canonicalSpelling());
        for (ValueFormula formula : formulas) {
            result.append('|').append(formula.canonicalKey());
        }
        for (ExactOverride override : exactOverrides) {
            result.append("|override=").append(override.formula().canonicalKey())
                    .append('@').append(override.route());
        }
        return result.toString();
    }

    @Override
    public Iterator<ValueFormula> iterator() {
        return formulas.iterator();
    }

    @Override
    public String toString() {
        return canonicalKey();
    }

    private static ValueFormula projectRootPlaceholder(
            ValueFormula formula,
            ProjectionPath sourceSuffix,
            LyraType selectedType) {
        LyraType routedSource = ValueAlternative.typeAt(formula.type(), sourceSuffix);
        if (!routedSource.withoutQualifiers().equals(selectedType.withoutQualifiers())) {
            throw new IllegalArgumentException("formula source route " + sourceSuffix
                    + " produces " + routedSource + " instead of " + selectedType);
        }
        if (formula instanceof ValueFormula.Parameter parameter) {
            return new ValueFormula.Parameter(
                    parameter.declarationId(), parameter.parameterIndex(),
                    parameter.parameterRoute().compose(sourceSuffix),
                    ProjectionPath.root(), selectedType);
        }
        if (formula instanceof ValueFormula.Capture capture) {
            return new ValueFormula.Capture(
                    capture.captureId(), capture.declarationId(), capture.sharedCellId(),
                    capture.captureRoute().compose(sourceSuffix),
                    ProjectionPath.root(), selectedType);
        }
        if (formula instanceof ValueFormula.Declaration declaration) {
            return new ValueFormula.Declaration(
                    declaration.declarationId(), declaration.moduleId(),
                    declaration.declarationRoute().compose(sourceSuffix),
                    ProjectionPath.root(), selectedType);
        }
        if (formula instanceof ValueFormula.CallResult call) {
            return new ValueFormula.CallResult(
                    call.callId(), selectedType,
                    call.callRoute().compose(sourceSuffix), ProjectionPath.root());
        }
        if (formula instanceof ValueFormula.Opaque) {
            return new ValueFormula.Opaque(selectedType, ProjectionPath.root(),
                    "projected from " + formula.resultRoute());
        }
        return null;
    }

    private boolean maskedAt(
            ProjectionPath selection,
            ValueFormula formula) {
        if (!selection.isExact()) {
            return false;
        }
        ProjectionPath formulaRoute = formula.resultRoute();
        for (ProjectionPath override : overrideRoutes(formula)) {
            if (override.isExactPrefixOf(selection)
                    && override.overlaps(formulaRoute)
                    && !override.selects(formulaRoute)) {
                return true;
            }
        }
        return false;
    }

    private List<ProjectionPath> overrideRoutes(ValueFormula formula) {
        return exactOverrides.stream()
                .filter(override -> override.formula().equals(formula))
                .map(ExactOverride::route)
                .toList();
    }

    private void requireSameShape(FormulaAlternatives other) {
        if (!rootType.withoutQualifiers().equals(other.rootType.withoutQualifiers())) {
            throw new IllegalArgumentException("formula alternatives have different root shapes: "
                    + rootType + " and " + other.rootType);
        }
    }

    private static void validateRoute(LyraType rootType, ValueFormula formula) {
        LyraType routed;
        try {
            routed = ValueAlternative.typeAt(rootType, formula.resultRoute());
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("formula route is incompatible with its root type: "
                    + formula.resultRoute(), failure);
        }
        if (!routed.withoutQualifiers().equals(formula.type().withoutQualifiers())) {
            throw new IllegalArgumentException("formula type " + formula.type()
                    + " does not match its result route " + formula.resultRoute()
                    + " in " + rootType);
        }
    }
}
