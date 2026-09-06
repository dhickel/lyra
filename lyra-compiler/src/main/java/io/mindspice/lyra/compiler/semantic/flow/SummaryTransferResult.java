package io.mindspice.lyra.compiler.semantic.flow;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/** Result of applying one or more callable summary alternatives to caller facts. */
public sealed interface SummaryTransferResult
        permits SummaryTransferResult.Success, SummaryTransferResult.Failure {
    Optional<FormulaAlternatives> optionalValue();

    record Success(
            FormulaAlternatives returnValue,
            List<CapturedCellWrite> writes,
            List<OwnershipRequirement> ownershipRequirements,
            List<EagerEffectWitness> effects) implements SummaryTransferResult {
        public Success(
                FormulaAlternatives returnValue,
                List<CapturedCellWrite> writes,
                List<EagerEffectWitness> effects) {
            this(returnValue, writes, List.of(), effects);
        }

        public Success {
            Objects.requireNonNull(returnValue, "returnValue");
            writes = canonicalWrites(writes);
            ownershipRequirements = canonicalOwnershipRequirements(
                    ownershipRequirements);
            effects = canonicalEffects(effects);
        }

        @Override
        public Optional<FormulaAlternatives> optionalValue() {
            return Optional.of(returnValue);
        }

        public FormulaAlternatives value() {
            return returnValue;
        }

        private static List<CapturedCellWrite> canonicalWrites(List<CapturedCellWrite> values) {
            Objects.requireNonNull(values, "writes");
            LinkedHashSet<CapturedCellWrite> unique = new LinkedHashSet<>();
            for (CapturedCellWrite value : values) {
                unique.add(Objects.requireNonNull(value, "write"));
            }
            ArrayList<CapturedCellWrite> result = new ArrayList<>(unique);
            result.sort(CapturedCellWrite::compareTo);
            return List.copyOf(result);
        }

        private static List<OwnershipRequirement> canonicalOwnershipRequirements(
                List<OwnershipRequirement> values) {
            Objects.requireNonNull(values, "ownershipRequirements");
            java.util.TreeMap<String, OwnershipRequirement> unique =
                    new java.util.TreeMap<>();
            for (OwnershipRequirement value : values) {
                OwnershipRequirement requirement = Objects.requireNonNull(
                        value, "ownershipRequirement");
                unique.merge(requirement.semanticKey(), requirement,
                        (left, right) -> left.sequence() <= right.sequence()
                                ? left : right);
            }
            ArrayList<OwnershipRequirement> result = new ArrayList<>(unique.values());
            result.sort(OwnershipRequirement::compareTo);
            return List.copyOf(result);
        }

        private static List<EagerEffectWitness> canonicalEffects(List<EagerEffectWitness> values) {
            Objects.requireNonNull(values, "effects");
            TreeSet<EagerEffectWitness> unique = new TreeSet<>();
            for (EagerEffectWitness value : values) {
                unique.add(Objects.requireNonNull(value, "effect"));
            }
            return List.copyOf(unique);
        }
    }

    record Failure(CallableSummaryResult.InternalFailure failure)
            implements SummaryTransferResult {
        public Failure {
            Objects.requireNonNull(failure, "failure");
        }

        @Override
        public Optional<FormulaAlternatives> optionalValue() {
            return Optional.empty();
        }
    }
}
