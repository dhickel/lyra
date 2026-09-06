package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.semantic.flow.CallableCallReference;
import io.mindspice.lyra.compiler.semantic.flow.CallableFlow;
import io.mindspice.lyra.compiler.semantic.flow.CallableSummary;
import io.mindspice.lyra.compiler.semantic.flow.EagerEffectFact;
import io.mindspice.lyra.compiler.semantic.flow.EagerEffectWitness;
import io.mindspice.lyra.compiler.semantic.flow.EagerCycleWitness;
import io.mindspice.lyra.compiler.semantic.flow.FreshAllocationSite;
import io.mindspice.lyra.compiler.semantic.flow.NilProvenance;
import io.mindspice.lyra.compiler.semantic.flow.SemanticFlowEvent;
import io.mindspice.lyra.compiler.semantic.flow.SemanticFlowFacts;
import io.mindspice.lyra.compiler.semantic.flow.ValueAlternative;
import io.mindspice.lyra.compiler.semantic.flow.ValueAlternatives;
import io.mindspice.lyra.compiler.semantic.flow.CapturedCellWrite;
import io.mindspice.lyra.compiler.semantic.TypedExpression;
import io.mindspice.lyra.compiler.semantic.TypedExpressionKind;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.identity.DeclarationId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Explicit IR projection of the canonical flow artifact.
 *
 * <p>The immutable source facts are retained as an internal provenance input,
 * not as a serialized IR format.  The indexed projections make the pieces
 * needed by later lowering visible without asking a backend to reinterpret
 * source syntax or run another flow evaluator.</p>
 */
public final class IrFlowMetadata implements ImmutablePhaseArtifact {
    private final SemanticFlowFacts sourceFacts;
    private final List<SemanticFlowEvent> events;
    private final List<EagerEffectFact> eagerEffectFacts;
    private final List<EagerEffectWitness> eagerEffects;
    private final List<CallableSummary> summaries;
    private final List<CallableCallReference> callReferences;
    private final List<CapturedCellWrite> cellWrites;
    private final List<CallableFlow> callableFlows;
    private final List<FreshAllocationSite> freshAllocationSites;
    private final List<EagerCycleWitness> eagerCycles;
    private final List<IrAggregateProvenance> aggregateProvenance;
    private final List<IrAggregateAllocation> aggregateAllocations;
    private final List<NilProvenance> nilProvenance;
    private final Map<FlowSiteId, List<SemanticFlowEvent>> eventsBySite;
    private final Map<DeclarationId, ValueAlternatives> declarationValues;

    private IrFlowMetadata(
            SemanticFlowFacts sourceFacts,
            List<SemanticFlowEvent> events,
            List<EagerEffectFact> eagerEffectFacts,
            List<EagerEffectWitness> eagerEffects,
            List<CallableSummary> summaries,
            List<CallableCallReference> callReferences,
            List<CapturedCellWrite> cellWrites,
            List<CallableFlow> callableFlows,
            List<FreshAllocationSite> freshAllocationSites,
            List<EagerCycleWitness> eagerCycles,
            List<IrAggregateProvenance> aggregateProvenance,
            List<IrAggregateAllocation> aggregateAllocations,
            List<NilProvenance> nilProvenance,
            Map<FlowSiteId, List<SemanticFlowEvent>> eventsBySite,
            Map<DeclarationId, ValueAlternatives> declarationValues) {
        this.sourceFacts = Objects.requireNonNull(sourceFacts, "sourceFacts");
        this.events = copy(events, "events");
        this.eagerEffectFacts = copy(eagerEffectFacts, "eagerEffectFacts");
        this.eagerEffects = copy(eagerEffects, "eagerEffects");
        this.summaries = copy(summaries, "summaries");
        this.callReferences = copy(callReferences, "callReferences");
        this.cellWrites = copy(cellWrites, "cellWrites");
        this.callableFlows = copy(callableFlows, "callableFlows");
        this.freshAllocationSites = copy(freshAllocationSites, "freshAllocationSites");
        this.eagerCycles = copy(eagerCycles, "eagerCycles");
        this.aggregateProvenance = copy(aggregateProvenance, "aggregateProvenance");
        this.aggregateAllocations = copy(aggregateAllocations, "aggregateAllocations");
        this.nilProvenance = copy(nilProvenance, "nilProvenance");
        this.eventsBySite = immutableEventMap(eventsBySite);
        this.declarationValues = immutableMap(declarationValues, "declarationValues");
    }

    static IrFlowMetadata from(SemanticFlowFacts facts, TypedSemanticGraph graph) {
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(graph, "graph");
        TreeMap<FlowSiteId, List<SemanticFlowEvent>> bySite = new TreeMap<>();
        TreeSet<IrAggregateProvenance> aggregates = new TreeSet<>();
        TreeSet<NilProvenance> nils = new TreeSet<>();
        TreeSet<CallableCallReference> calls = new TreeSet<>();
        TreeSet<CallableFlow> callableFlows = new TreeSet<>();
        TreeSet<FreshAllocationSite> freshSites = new TreeSet<>();
        List<CapturedCellWrite> writes = new ArrayList<>();
        for (SemanticFlowEvent event : facts.events()) {
            event.siteId().ifPresent(site -> bySite
                    .computeIfAbsent(site, ignored -> new ArrayList<>()).add(event));
            collectAlternatives(event.value(), aggregates, nils, callableFlows);
            writes.addAll(event.writes());
        }
        for (CallableSummary summary : facts.callableSummaries().orderedSummaries()) {
            calls.addAll(summary.callReferences());
            writes.addAll(summary.writes());
            collectFormulaAlternatives(summary.returnFormula().alternatives(), freshSites);
            for (var write : summary.writes()) {
                collectFormulaAlternatives(write.value(), freshSites);
            }
            for (var call : summary.callReferences()) {
                collectFormulaAlternatives(call.target(), freshSites);
                call.arguments().forEach(value -> collectFormulaAlternatives(value, freshSites));
            }
        }
        facts.declarationValues().values().forEach(value -> collectAlternatives(
                value, aggregates, nils, callableFlows));
        List<IrAggregateAllocation> allocations = aggregateAllocations(
                graph, List.copyOf(aggregates));
        return new IrFlowMetadata(
                facts,
                facts.events(),
                facts.eagerEffectFacts(),
                facts.eagerEffects(),
                facts.callableSummaries().orderedSummaries(),
                List.copyOf(calls),
                writes,
                List.copyOf(callableFlows),
                List.copyOf(freshSites),
                facts.eagerCycles(),
                List.copyOf(aggregates),
                allocations,
                List.copyOf(nils),
                bySite,
                facts.declarationValues());
    }

    /** Internal exact source artifact; no serialization or backend SPI is defined. */
    public SemanticFlowFacts sourceFacts() {
        return sourceFacts;
    }

    public SemanticFlowFacts facts() {
        return sourceFacts;
    }

    public io.mindspice.lyra.compiler.semantic.flow.CallableSummarySet callableSummaries() {
        return sourceFacts.callableSummaries();
    }

    public List<io.mindspice.lyra.compiler.semantic.flow.NormalizedExpression> normalizedExpressions() {
        return sourceFacts.normalizedExpressions();
    }

    public List<SemanticFlowEvent> events() {
        return events;
    }

    public List<EagerEffectFact> eagerEffectFacts() {
        return eagerEffectFacts;
    }

    public List<EagerEffectWitness> eagerEffects() {
        return eagerEffects;
    }

    public List<EagerEffectWitness> eagerEffectWitnesses() {
        return eagerEffects;
    }

    public List<CallableSummary> summaries() {
        return summaries;
    }

    public List<CallableSummary> callableSummaryRecords() {
        return summaries;
    }

    public List<CallableCallReference> callReferences() {
        return callReferences;
    }

    public List<CapturedCellWrite> cellWrites() {
        return cellWrites;
    }

    public List<CallableFlow> callableFlows() {
        return callableFlows;
    }

    public List<FreshAllocationSite> freshAllocationSites() {
        return freshAllocationSites;
    }

    public List<EagerCycleWitness> eagerCycleWitnesses() {
        return eagerCycles;
    }

    public List<EagerCycleWitness> eagerCycles() {
        return eagerCycles;
    }

    public List<IrAggregateProvenance> aggregateProvenance() {
        return aggregateProvenance;
    }

    public List<IrAggregateAllocation> aggregateAllocations() {
        return aggregateAllocations;
    }

    public List<NilProvenance> nilProvenance() {
        return nilProvenance;
    }

    public Map<FlowSiteId, List<SemanticFlowEvent>> eventsBySite() {
        return eventsBySite;
    }

    public Map<DeclarationId, ValueAlternatives> declarationValues() {
        return declarationValues;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof IrFlowMetadata metadata
                && sourceFacts.equals(metadata.sourceFacts)
                && events.equals(metadata.events)
                && eagerEffectFacts.equals(metadata.eagerEffectFacts)
                && eagerEffects.equals(metadata.eagerEffects)
                && summaries.equals(metadata.summaries)
                && callReferences.equals(metadata.callReferences)
                && cellWrites.equals(metadata.cellWrites)
                && callableFlows.equals(metadata.callableFlows)
                && freshAllocationSites.equals(metadata.freshAllocationSites)
                && eagerCycles.equals(metadata.eagerCycles)
                && aggregateProvenance.equals(metadata.aggregateProvenance)
                && aggregateAllocations.equals(metadata.aggregateAllocations)
                && nilProvenance.equals(metadata.nilProvenance)
                && eventsBySite.equals(metadata.eventsBySite)
                && declarationValues.equals(metadata.declarationValues);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceFacts, events, eagerEffectFacts, eagerEffects, summaries,
                callReferences, cellWrites, callableFlows, freshAllocationSites, eagerCycles,
                aggregateProvenance, aggregateAllocations, nilProvenance, eventsBySite,
                declarationValues);
    }

    public boolean isExact(SemanticFlowFacts facts) {
        // The producer's frozen fact object is the provenance authority.  A
        // structurally equal copy is useful as data but cannot certify that it
        // was the artifact consumed for this typed graph.
        return sourceFacts == Objects.requireNonNull(facts, "facts");
    }

    private static List<IrAggregateAllocation> aggregateAllocations(
            TypedSemanticGraph graph,
            List<IrAggregateProvenance> provenance) {
        List<IrAggregateAllocation> result = new ArrayList<>();
        for (TypedExpression expression : graph.expressions()) {
            if (expression.kind() != TypedExpressionKind.ARRAY_LITERAL
                    || !(expression.type().withoutQualifiers() instanceof ArrayType arrayType)) {
                continue;
            }
            FlowSiteId site = graph.flowSiteId(expression);
            List<IrAggregateProvenance> occurrences = provenance.stream()
                    .filter(value -> value.originSite().filter(site::equals).isPresent())
                    .toList();
            // Arrays created inside a lambda are fresh per invocation and are
            // represented by the producer's FreshAllocation formula rather
            // than a declaration-owned aggregate identity.  Keep the source
            // allocation record, but leave its canonical identity empty;
            // rejecting it here would make valid closure-local arrays
            // impossible to lower.
            Optional<io.mindspice.lyra.compiler.semantic.flow.ArrayIdentity> identity =
                    occurrences.stream().map(IrAggregateProvenance::identity).findFirst();
            result.add(new IrAggregateAllocation(site,
                    io.mindspice.lyra.compiler.source.ModuleId.fromSourceId(expression.span().sourceId()),
                    expression.span(), arrayType, occurrences, identity));
        }
        result.sort(IrAggregateAllocation::compareTo);
        return List.copyOf(result);
    }

    private static void collectAlternatives(
            ValueAlternatives alternatives,
            TreeSet<IrAggregateProvenance> aggregates,
            TreeSet<NilProvenance> nils,
            TreeSet<CallableFlow> callables) {
        for (ValueAlternative alternative : alternatives) {
            for (var aggregate : alternative.aggregateIdentities()) {
                if (aggregate.witness().originSite().isEmpty()
                        && !(aggregate.identity() instanceof io.mindspice.lyra.compiler.semantic.flow.ArrayIdentity.SessionOrigin)) {
                    throw new IllegalArgumentException(
                            "aggregate provenance has no producer-issued origin site");
                }
                aggregates.add(IrAggregateProvenance.from(aggregate));
            }
            callables.addAll(alternative.callableFlows());
            nils.addAll(alternative.nilProvenance());
        }
    }

    private static void collectFormulaAlternatives(
            io.mindspice.lyra.compiler.semantic.flow.FormulaAlternatives alternatives,
            TreeSet<FreshAllocationSite> freshSites) {
        // Keep producer-issued symbolic allocation sites.  The IR never
        // reruns the analyzer or invents an allocation ordinal.
        Objects.requireNonNull(alternatives, "alternatives");
        for (var formula : alternatives.formulas()) {
            collectFormula(formula, freshSites);
        }
    }

    private static void collectFormula(
            io.mindspice.lyra.compiler.semantic.flow.ValueFormula formula,
            TreeSet<FreshAllocationSite> freshSites) {
        if (formula instanceof io.mindspice.lyra.compiler.semantic.flow.ValueFormula.FreshAllocation fresh) {
            freshSites.add(fresh.allocationSite());
        } else if (formula instanceof io.mindspice.lyra.compiler.semantic.flow.ValueFormula.Lambda lambda) {
            lambda.captures().values().forEach(value -> value.formulas()
                    .forEach(nested -> collectFormula(nested, freshSites)));
        }
    }

    private static <T> List<T> copy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<T> copy = new ArrayList<>();
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " must not contain null"));
        }
        return List.copyOf(copy);
    }

    private static Map<FlowSiteId, List<SemanticFlowEvent>> immutableEventMap(
            Map<FlowSiteId, List<SemanticFlowEvent>> values) {
        Objects.requireNonNull(values, "eventsBySite");
        TreeMap<FlowSiteId, List<SemanticFlowEvent>> ordered = new TreeMap<>();
        values.forEach((site, events) -> ordered.put(
                Objects.requireNonNull(site, "event site"), List.copyOf(copy(events, "events at site"))));
        return Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> values, String name) {
        Objects.requireNonNull(values, name);
        List<Map.Entry<K, V>> entries = new ArrayList<>(values.entrySet());
        entries.sort((left, right) -> String.valueOf(left.getKey()).compareTo(String.valueOf(right.getKey())));
        LinkedHashMap<K, V> result = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : entries) {
            result.put(Objects.requireNonNull(entry.getKey(), name + " key"),
                    Objects.requireNonNull(entry.getValue(), name + " value"));
        }
        return Collections.unmodifiableMap(result);
    }
}
