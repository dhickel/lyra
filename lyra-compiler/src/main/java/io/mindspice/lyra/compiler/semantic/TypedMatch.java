package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.LyraType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/** Closed child-role metadata for one typed lazy match expression. */
public record TypedMatch(
        MatchMode mode,
        OptionalInt subjectChild,
        List<Arm> arms) implements ImmutablePhaseArtifact {

    public enum MatchMode {
        TRADITIONAL,
        CONDITIONAL
    }

    public record Arm(
            SourceSpan span,
            boolean wildcard,
            OptionalInt patternChild,
            OptionalInt guardChild,
            int resultChild,
            Optional<LyraType> comparisonType) implements ImmutablePhaseArtifact {
        public Arm {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(patternChild, "patternChild");
            Objects.requireNonNull(guardChild, "guardChild");
            Objects.requireNonNull(comparisonType, "comparisonType");
            if (resultChild < 0) {
                throw new IllegalArgumentException("match result child index must not be negative");
            }
            if (wildcard == patternChild.isPresent()) {
                throw new IllegalArgumentException("match arm must have exactly one wildcard or pattern child");
            }
        }

        public List<Integer> childIndexes() {
            ArrayList<Integer> indexes = new ArrayList<>(3);
            patternChild.ifPresent(indexes::add);
            guardChild.ifPresent(indexes::add);
            indexes.add(resultChild);
            return List.copyOf(indexes);
        }
    }

    public TypedMatch {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(subjectChild, "subjectChild");
        arms = List.copyOf(Objects.requireNonNull(arms, "arms"));
        if ((mode == MatchMode.TRADITIONAL) != subjectChild.isPresent()) {
            throw new IllegalArgumentException("traditional typed match alone has a subject child");
        }
        if (arms.isEmpty()) {
            throw new IllegalArgumentException("typed match requires a fallback arm");
        }
        Set<Integer> indexes = new HashSet<>();
        subjectChild.ifPresent(indexes::add);
        for (int armIndex = 0; armIndex < arms.size(); armIndex++) {
            Arm arm = Objects.requireNonNull(arms.get(armIndex), "arms must not contain null");
            for (Integer child : arm.childIndexes()) {
                if (child < 0 || !indexes.add(child)) {
                    throw new IllegalArgumentException("typed match child roles overlap or are negative");
                }
            }
            if (mode == MatchMode.CONDITIONAL
                    && (arm.guardChild().isPresent() || arm.comparisonType().isPresent())) {
                throw new IllegalArgumentException("conditional typed match cannot carry guard/equality metadata");
            }
            if (mode == MatchMode.TRADITIONAL
                    && !arm.wildcard() && arm.comparisonType().isEmpty()) {
                throw new IllegalArgumentException("traditional match pattern needs an equality type");
            }
            if (arm.wildcard() && arm.comparisonType().isPresent()) {
                throw new IllegalArgumentException("wildcard match arm cannot carry an equality type");
            }
            if (arm.wildcard() && arm.guardChild().isEmpty() && armIndex != arms.size() - 1) {
                throw new IllegalArgumentException("unconditional wildcard must be the final typed match arm");
            }
        }
        Arm fallback = arms.getLast();
        if (!fallback.wildcard() || fallback.guardChild().isPresent()) {
            throw new IllegalArgumentException("typed match requires a final unconditional wildcard");
        }
        int childCount = indexes.size();
        for (int index = 0; index < childCount; index++) {
            if (!indexes.contains(index)) {
                throw new IllegalArgumentException("typed match child roles must be dense and source ordered");
            }
        }
    }

    public int childCount() {
        int count = subjectChild.isPresent() ? 1 : 0;
        for (Arm arm : arms) {
            count += arm.childIndexes().size();
        }
        return count;
    }
}
