package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.source.ModuleId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Complete immutable output of canonical typed semantic flow/eager analysis.
 *
 * <p>The artifact intentionally stores compact boundary events, solved
 * callable summaries, and eager witnesses.  It does not retain a before/after
 * state for every expression, nor does it expose a final mutable interpreter
 * state.  The representation is compiler-internal, JVM-independent, and not
 * a serialized or stable Java product API.</p>
 */
public final class SemanticFlowFacts implements ImmutablePhaseArtifact {
    private final CallableSummarySet summaries;
    private final List<NormalizedExpression> normalizedExpressions;
    private final List<SemanticFlowEvent> events;
    private final List<EagerEffectFact> eagerEffectFacts;
    private final List<EagerEffectWitness> eagerEffects;
    private final List<EagerCycleWitness> eagerCycles;
    private final Map<DeclarationId, ValueAlternatives> declarationValues;
    private final Map<ModuleId, BindingFlowState> finalStates;
    private final Map<ModuleId, BindingFlowState> attemptedStates;
    /** False for legacy compatibility constructors that do not carry state maps. */
    private final boolean stateMetadataSpecified;
    private final Map<DeclarationId, List<EagerEffectWitness>> effectsByInitializer;

    public SemanticFlowFacts(
            CallableSummarySet summaries,
            List<SemanticFlowEvent> events,
            List<EagerEffectFact> eagerEffectFacts,
            List<EagerCycleWitness> eagerCycles,
            Map<DeclarationId, ValueAlternatives> declarationValues) {
        this.summaries = Objects.requireNonNull(summaries, "summaries");
        this.normalizedExpressions = List.of();
        this.events = orderedEvents(events);
        this.eagerEffectFacts = orderedEffectFacts(eagerEffectFacts);
        this.eagerEffects = orderedEffects(this.eagerEffectFacts);
        this.eagerCycles = orderedCycles(eagerCycles);
        this.declarationValues = orderedValues(declarationValues);
        this.finalStates = Map.of();
        this.attemptedStates = Map.of();
        this.stateMetadataSpecified = false;
        this.effectsByInitializer = indexEffects(this.eagerEffectFacts);
    }

    /** Constructor including normalized source-expression adapters. */
    public SemanticFlowFacts(
            CallableSummarySet summaries,
            List<NormalizedExpression> normalizedExpressions,
            List<SemanticFlowEvent> events,
            List<EagerEffectFact> eagerEffectFacts,
            List<EagerCycleWitness> eagerCycles,
            Map<DeclarationId, ValueAlternatives> declarationValues) {
        this(summaries, normalizedExpressions, events, eagerEffectFacts, eagerCycles,
                declarationValues, Map.of(), Map.of(), false);
    }

    /** Canonical constructor retaining the final state of every executed module frame. */
    public SemanticFlowFacts(
            CallableSummarySet summaries,
            List<NormalizedExpression> normalizedExpressions,
            List<SemanticFlowEvent> events,
            List<EagerEffectFact> eagerEffectFacts,
            List<EagerCycleWitness> eagerCycles,
            Map<DeclarationId, ValueAlternatives> declarationValues,
            Map<ModuleId, BindingFlowState> finalStates) {
        this(summaries, normalizedExpressions, events, eagerEffectFacts, eagerCycles,
                declarationValues, finalStates, Map.of(), true);
    }

    /** Final success state and conservative states at all possible execution prefixes. */
    public SemanticFlowFacts(
            CallableSummarySet summaries,
            List<NormalizedExpression> normalizedExpressions,
            List<SemanticFlowEvent> events,
            List<EagerEffectFact> eagerEffectFacts,
            List<EagerCycleWitness> eagerCycles,
            Map<DeclarationId, ValueAlternatives> declarationValues,
            Map<ModuleId, BindingFlowState> finalStates,
            Map<ModuleId, BindingFlowState> attemptedStates) {
        this(summaries, normalizedExpressions, events, eagerEffectFacts, eagerCycles,
                declarationValues, finalStates, attemptedStates, true);
    }

    private SemanticFlowFacts(
            CallableSummarySet summaries,
            List<NormalizedExpression> normalizedExpressions,
            List<SemanticFlowEvent> events,
            List<EagerEffectFact> eagerEffectFacts,
            List<EagerCycleWitness> eagerCycles,
            Map<DeclarationId, ValueAlternatives> declarationValues,
            Map<ModuleId, BindingFlowState> finalStates,
            Map<ModuleId, BindingFlowState> attemptedStates,
            boolean stateMetadataSpecified) {
        this.summaries = Objects.requireNonNull(summaries, "summaries");
        this.normalizedExpressions = orderedNormalized(normalizedExpressions);
        this.events = orderedEvents(events);
        this.eagerEffectFacts = orderedEffectFacts(eagerEffectFacts);
        this.eagerEffects = orderedEffects(this.eagerEffectFacts);
        this.eagerCycles = orderedCycles(eagerCycles);
        this.declarationValues = orderedValues(declarationValues);
        this.finalStates = orderedFinalStates(finalStates);
        this.attemptedStates = orderedFinalStates(attemptedStates);
        this.stateMetadataSpecified = stateMetadataSpecified;
        this.effectsByInitializer = indexEffects(this.eagerEffectFacts);
    }

    /** Compatibility constructor for callers without cycle/value indexes. */
    public SemanticFlowFacts(
            CallableSummarySet summaries,
            List<SemanticFlowEvent> events,
            List<EagerEffectFact> eagerEffectFacts) {
        this(summaries, events, eagerEffectFacts, List.of(), Map.of());
    }

    public CallableSummarySet callableSummaries() {
        return summaries;
    }

    public CallableSummarySet summaries() {
        return summaries;
    }

    public CallableSummarySet callableSummarySet() {
        return summaries;
    }

    /** One normalized tree per source-root form, in deterministic order. */
    public List<NormalizedExpression> normalizedExpressions() {
        return normalizedExpressions;
    }

    public List<NormalizedExpression> normalized() {
        return normalizedExpressions;
    }

    public List<SemanticFlowEvent> events() {
        return events;
    }

    public List<SemanticFlowEvent> semanticEvents() {
        return events;
    }

    public List<EagerEffectFact> eagerEffectFacts() {
        return eagerEffectFacts;
    }

    public List<EagerEffectFact> effects() {
        return eagerEffectFacts;
    }

    /** Unique witnesses, independent of the initializer attribution index. */
    public List<EagerEffectWitness> eagerEffects() {
        return eagerEffects;
    }

    public List<EagerEffectWitness> eagerEffectWitnesses() {
        return eagerEffects;
    }

    public List<EagerCycleWitness> eagerCycles() {
        return eagerCycles;
    }

    public List<EagerCycleWitness> cycleWitnesses() {
        return eagerCycles;
    }

    /** Values at declaration events, not a final-state reconstruction. */
    public Map<DeclarationId, ValueAlternatives> declarationValues() {
        return declarationValues;
    }

    public Optional<ValueAlternatives> valueAtDeclaration(DeclarationId declaration) {
        return Optional.ofNullable(declarationValues.get(
                Objects.requireNonNull(declaration, "declaration")));
    }

    /** Final retained state for each module frame after canonical source-order flow. */
    public Map<ModuleId, BindingFlowState> finalStates() {
        return finalStates;
    }

    public Map<ModuleId, BindingFlowState> attemptedStates() {
        return attemptedStates;
    }

    public Optional<BindingFlowState> finalState(ModuleId module) {
        return Optional.ofNullable(finalStates.get(Objects.requireNonNull(module, "module")));
    }

    /** Effects attributed to each source initializer in deterministic order. */
    public Map<DeclarationId, List<EagerEffectWitness>> effectsByInitializer() {
        return effectsByInitializer;
    }

    public Map<DeclarationId, List<EagerEffectWitness>> initializerEffects() {
        return effectsByInitializer;
    }

    public List<EagerEffectWitness> effectsFor(DeclarationId declaration) {
        return effectsByInitializer.getOrDefault(
                Objects.requireNonNull(declaration, "declaration"), List.of());
    }

    public List<SemanticFlowEvent> eventsAt(ModuleId module) {
        Objects.requireNonNull(module, "module");
        return events.stream().filter(event -> event.moduleId().equals(module)).toList();
    }

    public String canonicalKey() {
        StringBuilder result = new StringBuilder();
        for (NormalizedExpression normalized : normalizedExpressions) {
            result.append("normalized=").append(normalized).append('\n');
        }
        for (SemanticFlowEvent event : events) {
            result.append("event=").append(event).append('\n');
        }
        for (EagerEffectFact effect : eagerEffectFacts) {
            result.append("effect=").append(effect).append('\n');
        }
        for (EagerCycleWitness cycle : eagerCycles) {
            result.append("cycle=").append(cycle).append('\n');
        }
        for (Map.Entry<DeclarationId, ValueAlternatives> entry : declarationValues.entrySet()) {
            result.append("value=").append(entry.getKey()).append('=').append(entry.getValue())
                    .append('\n');
        }
        for (Map.Entry<ModuleId, BindingFlowState> entry : finalStates.entrySet()) {
            result.append("final=").append(entry.getKey()).append('=').append(entry.getValue())
                    .append('\n');
        }
        for (Map.Entry<ModuleId, BindingFlowState> entry : attemptedStates.entrySet()) {
            result.append("attempt=").append(entry.getKey()).append('=').append(entry.getValue())
                    .append('\n');
        }
        if (!stateMetadataSpecified) {
            result.append("state-metadata=unspecified\n");
        }
        result.append("summaries=").append(summaries.canonicalKey());
        return result.toString();
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof SemanticFlowFacts facts
                && summaries.equals(facts.summaries)
                && normalizedExpressions.equals(facts.normalizedExpressions)
                && events.equals(facts.events)
                && eagerEffectFacts.equals(facts.eagerEffectFacts)
                && eagerCycles.equals(facts.eagerCycles)
                && declarationValues.equals(facts.declarationValues)
                && (!stateMetadataSpecified || !facts.stateMetadataSpecified
                        || (finalStates.equals(facts.finalStates)
                        && attemptedStates.equals(facts.attemptedStates)));
    }

    @Override
    public int hashCode() {
        // Legacy compatibility instances intentionally compare as unspecified
        // state metadata, so state maps cannot participate in the hash contract.
        return Objects.hash(summaries, normalizedExpressions, events,
                eagerEffectFacts, eagerCycles, declarationValues);
    }

    @Override
    public String toString() {
        return "SemanticFlowFacts[events=" + events.size()
                + ", effects=" + eagerEffectFacts.size()
                + ", cycles=" + eagerCycles.size()
                + ", finalStates=" + finalStates.size()
                + ", attemptedStates=" + attemptedStates.size()
                + ", summaries=" + summaries.orderedSummaries().size() + "]";
    }

    private static List<NormalizedExpression> orderedNormalized(
            List<NormalizedExpression> values) {
        Objects.requireNonNull(values, "normalizedExpressions");
        TreeSet<NormalizedExpression> ordered = new TreeSet<>();
        for (NormalizedExpression value : values) {
            if (!ordered.add(Objects.requireNonNull(value,
                    "normalizedExpressions must not contain null"))) {
                throw new IllegalArgumentException(
                        "normalizedExpressions must not contain duplicates");
            }
        }
        return List.copyOf(ordered);
    }

    private static List<SemanticFlowEvent> orderedEvents(List<SemanticFlowEvent> values) {
        Objects.requireNonNull(values, "events");
        TreeSet<SemanticFlowEvent> ordered = new TreeSet<>();
        for (SemanticFlowEvent value : values) {
            if (!ordered.add(Objects.requireNonNull(value,
                    "events must not contain null"))) {
                throw new IllegalArgumentException("events must not contain duplicates");
            }
        }
        return List.copyOf(ordered);
    }

    private static List<EagerEffectFact> orderedEffectFacts(List<EagerEffectFact> values) {
        Objects.requireNonNull(values, "eagerEffectFacts");
        TreeSet<EagerEffectFact> ordered = new TreeSet<>();
        for (EagerEffectFact value : values) {
            if (!ordered.add(Objects.requireNonNull(value,
                    "eagerEffectFacts must not contain null"))) {
                throw new IllegalArgumentException(
                        "eagerEffectFacts must not contain duplicates");
            }
        }
        return List.copyOf(ordered);
    }

    private static List<EagerEffectWitness> orderedEffects(List<EagerEffectFact> values) {
        TreeSet<EagerEffectWitness> ordered = new TreeSet<>();
        for (EagerEffectFact value : values) {
            ordered.add(value.witness());
        }
        return List.copyOf(ordered);
    }

    private static List<EagerCycleWitness> orderedCycles(List<EagerCycleWitness> values) {
        Objects.requireNonNull(values, "eagerCycles");
        TreeSet<EagerCycleWitness> ordered = new TreeSet<>();
        for (EagerCycleWitness value : values) {
            if (!ordered.add(Objects.requireNonNull(value,
                    "eagerCycles must not contain null"))) {
                throw new IllegalArgumentException(
                        "eagerCycles must not contain duplicates");
            }
        }
        return List.copyOf(ordered);
    }

    private static Map<DeclarationId, ValueAlternatives> orderedValues(
            Map<DeclarationId, ValueAlternatives> values) {
        Objects.requireNonNull(values, "declarationValues");
        TreeMap<DeclarationId, ValueAlternatives> ordered = new TreeMap<>();
        values.forEach((declaration, value) -> ordered.put(
                Objects.requireNonNull(declaration, "declaration value key"),
                Objects.requireNonNull(value, "declaration value")));
        return Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
    }

    private static Map<ModuleId, BindingFlowState> orderedFinalStates(
            Map<ModuleId, BindingFlowState> values) {
        Objects.requireNonNull(values, "finalStates");
        TreeMap<ModuleId, BindingFlowState> ordered = new TreeMap<>();
        values.forEach((module, state) -> ordered.put(
                Objects.requireNonNull(module, "final state module"),
                Objects.requireNonNull(state, "final state")));
        return Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
    }

    private static Map<DeclarationId, List<EagerEffectWitness>> indexEffects(
            List<EagerEffectFact> values) {
        TreeMap<DeclarationId, List<EagerEffectWitness>> indexed = new TreeMap<>();
        for (EagerEffectFact value : values) {
            value.initializerDeclaration().ifPresent(declaration ->
                    indexed.computeIfAbsent(declaration, ignored -> new ArrayList<>())
                            .add(value.witness()));
        }
        LinkedHashMap<DeclarationId, List<EagerEffectWitness>> result = new LinkedHashMap<>();
        indexed.forEach((declaration, effects) -> {
            TreeSet<EagerEffectWitness> ordered = new TreeSet<>(effects);
            result.put(declaration, List.copyOf(ordered));
        });
        return Collections.unmodifiableMap(result);
    }
}
