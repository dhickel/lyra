package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One source-ordered ownership obligation produced with a callable summary.
 *
 * <p>The canonical summary compiler records the value whose aggregate identity
 * must remain locally owned at an array-mutation or {@code @mut} argument site.
 * Summary solving and invocation substitute the same formulas used for return
 * and write transfer; the typed flow producer alone turns a materialized
 * imported identity into a source diagnostic. This is internal, compilation-
 * local semantic data and has no runtime or serialization meaning.</p>
 */
public record OwnershipRequirement(
        int sequence,
        Kind kind,
        SourceSpan span,
        FlowSiteId siteId,
        FormulaAlternatives value) implements Comparable<OwnershipRequirement> {
    public enum Kind {
        AGGREGATE_MUTATION,
        MUTABLE_ARGUMENT
    }

    public OwnershipRequirement {
        if (sequence < 0) {
            throw new IllegalArgumentException(
                    "ownership requirement sequence must not be negative");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(value, "value");
        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    "ownership requirement needs a symbolic value");
        }
    }

    OwnershipRequirement withValue(FormulaAlternatives replacement) {
        return new OwnershipRequirement(
                sequence, kind, span, siteId,
                Objects.requireNonNull(replacement, "replacement"));
    }

    OwnershipRequirement withSequence(int replacement) {
        return new OwnershipRequirement(
                replacement, kind, span, siteId, value);
    }

    /**
     * Substitutes creation-time capture facts without evaluating a lambda body.
     * Parameter and unresolved call-result terms remain symbolic until an
     * actual summary invocation supplies them.
     */
    public Optional<OwnershipRequirement> substituteCaptures(
            Map<CaptureId, FormulaAlternatives> captures) {
        Objects.requireNonNull(captures, "captures");
        ArrayList<ValueFormula> formulas = new ArrayList<>();
        ArrayList<FormulaAlternatives.ExactOverride> overrides = new ArrayList<>();
        for (ValueFormula formula : value.formulas()) {
            List<ValueFormula> replacements = substituteCapture(formula, captures);
            if (replacements.isEmpty()) {
                return Optional.empty();
            }
            formulas.addAll(replacements);
            overrides.addAll(value.rebaseOverrides(formula, replacements));
        }
        return formulas.isEmpty()
                ? Optional.empty()
                : Optional.of(withValue(new FormulaAlternatives(
                value.rootType(), formulas, overrides)));
    }

    private static List<ValueFormula> substituteCapture(
            ValueFormula formula,
            Map<CaptureId, FormulaAlternatives> captures) {
        if (formula instanceof ValueFormula.Capture capture) {
            FormulaAlternatives value = captures.get(capture.captureId());
            if (value == null) {
                return List.of();
            }
            try {
                FormulaAlternatives selected = value.select(capture.captureRoute());
                return selected.formulas().stream()
                        .map(term -> term.prefixedBy(capture.resultRoute()))
                        .toList();
            } catch (IllegalArgumentException incompatibleRoute) {
                return List.of();
            }
        }
        if (formula instanceof ValueFormula.Lambda lambda) {
            java.util.TreeMap<CaptureId, FormulaAlternatives> nested =
                    new java.util.TreeMap<>();
            for (Map.Entry<CaptureId, FormulaAlternatives> entry
                    : lambda.captures().entrySet()) {
                ArrayList<ValueFormula> values = new ArrayList<>();
                ArrayList<FormulaAlternatives.ExactOverride> overrides =
                        new ArrayList<>();
                for (ValueFormula captured : entry.getValue().formulas()) {
                    List<ValueFormula> replacements = substituteCapture(
                            captured, captures);
                    if (replacements.isEmpty()) {
                        return List.of();
                    }
                    values.addAll(replacements);
                    overrides.addAll(entry.getValue()
                            .rebaseOverrides(captured, replacements));
                }
                nested.put(entry.getKey(), new FormulaAlternatives(
                        entry.getValue().rootType(), values, overrides));
            }
            return List.of(new ValueFormula.Lambda(
                    lambda.lambdaId(), lambda.functionType(),
                    lambda.resultRoute(), nested));
        }
        return List.of(formula);
    }

    String semanticKey() {
        return operationKey() + "/" + value.canonicalKey();
    }

    /** Stable source obligation identity independent of substituted values/order. */
    String operationKey() {
        return kind + "/" + span + "/" + siteId;
    }

    public String canonicalKey() {
        return String.format("%08d/%s", sequence, semanticKey());
    }

    @Override
    public int compareTo(OwnershipRequirement other) {
        Objects.requireNonNull(other, "other");
        int order = Integer.compare(sequence, other.sequence);
        return order != 0 ? order : canonicalKey().compareTo(other.canonicalKey());
    }

    @Override
    public String toString() {
        return canonicalKey();
    }
}
