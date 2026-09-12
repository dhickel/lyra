package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.IdentityAllocator;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.semantic.TypedExpression;
import io.mindspice.lyra.compiler.semantic.TypedExpressionKind;
import io.mindspice.lyra.compiler.semantic.TypedLambda;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.DeclarationVisibility;
import io.mindspice.lyra.compiler.semantic.ResolvedNominal;
import io.mindspice.lyra.compiler.semantic.flow.AggregateIdentityFact;
import io.mindspice.lyra.compiler.semantic.flow.ArrayIdentity;
import io.mindspice.lyra.compiler.semantic.flow.BindingFlowState;
import io.mindspice.lyra.compiler.semantic.flow.BindingFlowValue;
import io.mindspice.lyra.compiler.semantic.flow.CallableFlow;
import io.mindspice.lyra.compiler.semantic.flow.CallableSummary;
import io.mindspice.lyra.compiler.semantic.flow.CallableSummarySet;
import io.mindspice.lyra.compiler.semantic.flow.EagerEffectWitness;
import io.mindspice.lyra.compiler.semantic.flow.FormulaAlternatives;
import io.mindspice.lyra.compiler.semantic.flow.FreshAllocationSite;
import io.mindspice.lyra.compiler.semantic.flow.NominalObjectFact;
import io.mindspice.lyra.compiler.semantic.flow.ValueFormula;
import io.mindspice.lyra.compiler.semantic.flow.ValueAlternative;
import io.mindspice.lyra.compiler.semantic.flow.ValueAlternatives;
import io.mindspice.lyra.compiler.session.ExternalBinding;
import io.mindspice.lyra.compiler.session.SessionSnapshot;
import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.BindingContract;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.LyraType;
import io.mindspice.lyra.compiler.types.TupleType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Compiler-issued proof of one source-local session generation.
 *
 * <p>This is deliberately not a storage capability.  It contains immutable
 * semantic evidence used by a later compilation: the retained binding/cell
 * state, solved callable summaries, and exact producer identities.  Runtime
 * code must authenticate live storage separately before executing an artifact.
 * There is no public constructor or public issuing operation; only the
 * session compiler can issue a certificate from a sealed typed graph.</p>
 */
public final class SessionFlowCertificate {
    private final IdentityAllocator allocator;
    private final BindingFlowState boundaryState;
    private final CallableSummarySet callableSummaries;
    private final Map<String, ExternalBinding> certifiedBindings;
    private final Set<CallableProofKey> callableProofs;
    private final Set<AggregateProofKey> aggregateProofs;
    private final Map<FreshAllocationSite, AllocationProvenance> allocationProvenance;
    private final Map<String, RetainedNominal> retainedNominals;
    private final Map<String, String> nominalNames;
    private final Set<SourceId> sourceIds;
    private final int generationCount;

    private SessionFlowCertificate(
            IdentityAllocator allocator,
            BindingFlowState boundaryState,
            CallableSummarySet callableSummaries,
            Map<String, ExternalBinding> certifiedBindings,
            Set<CallableProofKey> callableProofs,
            Set<AggregateProofKey> aggregateProofs,
            Map<FreshAllocationSite, AllocationProvenance> allocationProvenance,
            Map<String, RetainedNominal> retainedNominals,
            Map<String, String> nominalNames,
            Set<SourceId> sourceIds,
            int generationCount) {
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.boundaryState = Objects.requireNonNull(boundaryState, "boundaryState");
        this.callableSummaries = Objects.requireNonNull(callableSummaries, "callableSummaries");
        this.certifiedBindings = immutableBindings(certifiedBindings);
        this.callableProofs = Set.copyOf(Objects.requireNonNull(callableProofs, "callableProofs"));
        this.aggregateProofs = Set.copyOf(Objects.requireNonNull(aggregateProofs, "aggregateProofs"));
        this.allocationProvenance = immutableAllocationProvenance(allocationProvenance);
        this.retainedNominals = immutableRetainedNominals(retainedNominals);
        this.nominalNames = immutableNominalNames(nominalNames, this.retainedNominals);
        this.sourceIds = Set.copyOf(Objects.requireNonNull(sourceIds, "sourceIds"));
        if (generationCount < 1) {
            throw new IllegalArgumentException("a session flow certificate needs a generation");
        }
        this.generationCount = generationCount;
    }

    /** Persistent identity point consumed by the next session submission. */
    public IdentityAllocator allocator() {
        return allocator;
    }

    /** Immutable retained binding and shared-cell state at the generation boundary. */
    public BindingFlowState boundaryState() {
        return boundaryState;
    }

    /** All producer-certified callable summaries retained through this generation. */
    public CallableSummarySet callableSummaries() {
        return callableSummaries;
    }

    /** Number of source-local generations represented by this proof. */
    public int generationCount() {
        return generationCount;
    }

    /** Returns whether this proof already uses a compiler source identity. */
    public boolean containsSourceId(SourceId sourceId) {
        return sourceIds.contains(Objects.requireNonNull(sourceId, "sourceId"));
    }

    /** Returns the retained value for a declaration identity, if it is present. */
    public Optional<ValueAlternatives> value(DeclarationId declaration) {
        Objects.requireNonNull(declaration, "declaration");
        return boundaryState.binding(declaration).map(BindingFlowValue::alternatives);
    }

    /** Alias used by semantic flow callers. */
    public Optional<ValueAlternatives> valueFor(DeclarationId declaration) {
        return value(declaration);
    }

    /** Returns the retained shared-cell snapshot, if it is present. */
    public Optional<ValueAlternatives> sharedCell(DeclarationId cell) {
        return boundaryState.sharedCell(cell);
    }

    /**
     * Checks whether a mutable cell is retained by this proof, including a cell
     * that is reachable only through a persisted closure capture.  Such cells
     * are intentionally not promoted to named bindings in the certificate.
     */
    public boolean certifiesSharedCell(DeclarationId cell, BindingContract contract) {
        Objects.requireNonNull(cell, "cell");
        Objects.requireNonNull(contract, "contract");
        Set<Object> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        return certifiesSharedCell(boundaryState, cell, contract, visited);
    }

    private boolean certifiesSharedCell(
            BindingFlowState state,
            DeclarationId cell,
            BindingContract contract,
            Set<Object> visited) {
        if (!visited.add(state)) {
            return false;
        }
        ValueAlternatives direct = state.sharedCells().get(cell);
        if (direct != null && valuesHaveType(direct, contract.valueType())) {
            return true;
        }
        for (BindingFlowValue binding : state.bindings().values()) {
            if (certifiesSharedCell(binding.alternatives(), cell, contract, visited)) {
                return true;
            }
        }
        for (ValueAlternatives values : state.sharedCells().values()) {
            if (certifiesSharedCell(values, cell, contract, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean certifiesSharedCell(
            ValueAlternatives values,
            DeclarationId cell,
            BindingContract contract,
            Set<Object> visited) {
        if (!visited.add(values)) {
            return false;
        }
        for (ValueAlternative alternative : values.alternatives()) {
            for (CallableFlow callable : alternative.callableFlows()) {
                ValueAlternatives snapshot = callable.sharedCellSnapshots().get(cell);
                if (snapshot != null && valuesHaveType(snapshot, contract.valueType())
                        && callable.lambdaId().flatMap(callableSummaries::summary)
                        .flatMap(summary -> summary.captures().stream()
                                .filter(capture -> capture.sharedCellId()
                                        .filter(cell::equals).isPresent())
                                .filter(capture -> capture.contract().equals(contract))
                                .findFirst())
                        .isPresent()) {
                    return true;
                }
                if (certifiesSharedCellInCallable(callable, cell, contract, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean certifiesSharedCellInCallable(
            CallableFlow callable,
            DeclarationId cell,
            BindingContract contract,
            Set<Object> visited) {
        if (!visited.add(callable)) {
            return false;
        }
        for (ValueAlternatives values : callable.capturedValues().values()) {
            if (certifiesSharedCell(values, cell, contract, visited)) {
                return true;
            }
        }
        for (ValueAlternatives values : callable.sharedCellSnapshots().values()) {
            if (certifiesSharedCell(values, cell, contract, visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean valuesHaveType(ValueAlternatives values, LyraType type) {
        return !values.isEmpty() && values.alternatives().stream().allMatch(value ->
                value.type().withoutQualifiers().equals(type.withoutQualifiers()));
    }

    /**
     * Checks the exact producer-issued contract for a named external binding.
     * Name, type, declaration identity, authority, origin, and storage metadata
     * must all match the certified record.  This method never authenticates a
     * runtime storage location.
     */
    public boolean certifiesBinding(ExternalBinding binding) {
        Objects.requireNonNull(binding, "binding");
        ExternalBinding certified = certifiedBindings.get(binding.name());
        if (!binding.equals(certified)) {
            return false;
        }
        BindingFlowValue value = boundaryState.bindings().get(binding.declarationId());
        if (value == null || !value.contract().equals(binding.contract())
                || value.alternatives().isEmpty()) {
            return false;
        }
        return !containsCallableType(binding.type())
                || value.alternatives().alternatives().stream()
                .flatMap(alternative -> alternative.callableFlows().stream())
                .allMatch(this::certifiesCallable);
    }

    /** True only for a callable value (including a routed aggregate callable) in this proof. */
    public boolean certifiesCallable(CallableFlow callable) {
        Objects.requireNonNull(callable, "callable");
        return callableProofs.contains(CallableProofKey.of(callable));
    }

    /**
     * Checks a callable transferred from a certified callable invocation.  Its
     * capture values may be new invocation arguments, so exact boundary-flow
     * equality is intentionally not required; the producer still supplies the
     * lambda identity and complete capture contracts.
     */
    public boolean certifiesCallableTransfer(CallableFlow callable) {
        Objects.requireNonNull(callable, "callable");
        if (certifiesCallable(callable)) {
            return true;
        }
        if (callable.isIntrinsic()) {
            return false;
        }
        CallableSummary summary = callable.lambda()
                .flatMap(callableSummaries::summary).orElse(null);
        return summary != null && captureContractsMatch(
                summary, callable.capturedValues(), callable.sharedCellSnapshots());
    }

    /** True when the producer proof contains at least one closure for a lambda identity. */
    public boolean certifiesLambda(LambdaId lambda) {
        Objects.requireNonNull(lambda, "lambda");
        return callableProofs.stream().anyMatch(value -> value.lambda().filter(lambda::equals).isPresent());
    }

    /**
     * Checks the producer-owned terminal witness of an effect propagated from
     * a retained callable. The current generation may prepend its own call
     * path, so only the certified terminal suffix must match.
     */
    public boolean certifiesEffect(EagerEffectWitness candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return callableSummaries.orderedSummaries().stream()
                .flatMap(summary -> summary.eagerEffects().stream())
                .anyMatch(certified -> sameEffectIdentity(certified, candidate)
                        && hasCertifiedSuffix(certified, candidate));
    }

    /** Returns whether a source span/site pair belongs to a retained effect path. */
    public boolean certifiesEffectPathEntry(SourceSpan span, FlowSiteId site) {
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(site, "site");
        for (CallableSummary summary : callableSummaries.orderedSummaries()) {
            for (EagerEffectWitness effect : summary.eagerEffects()) {
                for (int index = 0; index < effect.sourcePath().size(); index++) {
                    if (effect.sourcePath().get(index).equals(span)
                            && effect.sourceSitePath().size() > index
                            && effect.sourceSitePath().get(index).equals(site)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean sameEffectIdentity(
            EagerEffectWitness certified,
            EagerEffectWitness candidate) {
        return certified.targetModule().equals(candidate.targetModule())
                && certified.kind().equals(candidate.kind())
                && certified.effectSpan().equals(candidate.effectSpan())
                && certified.effectSite().equals(candidate.effectSite())
                && certified.targetDeclaration().equals(candidate.targetDeclaration())
                && certified.referenceId().equals(candidate.referenceId())
                && (certified.targetLambda().equals(candidate.targetLambda())
                || candidate.kind() != EagerEffectWitness.Kind.VALUE_READ
                && candidate.targetDeclaration().flatMap(boundaryState::binding)
                        .filter(value -> value.contract().isMutable())
                        .stream().flatMap(value -> value.callableFlows().stream())
                        .anyMatch(value -> value.lambdaId().isPresent()
                                && value.lambdaId().equals(candidate.targetLambda())));
    }

    private static boolean hasCertifiedSuffix(
            EagerEffectWitness certified,
            EagerEffectWitness candidate) {
        int size = certified.sourcePath().size();
        if (candidate.sourcePath().size() < size
                || candidate.sourceSitePath().size() < certified.sourceSitePath().size()) {
            return false;
        }
        return candidate.sourcePath().subList(candidate.sourcePath().size() - size,
                        candidate.sourcePath().size()).equals(certified.sourcePath())
                && candidate.sourceSitePath().subList(
                        candidate.sourceSitePath().size() - certified.sourceSitePath().size(),
                        candidate.sourceSitePath().size()).equals(certified.sourceSitePath());
    }

    /**
     * Finds a producer closure after a symbolic formula has lost its route or
     * creation-site wrapper. Shared-cell snapshots may have advanced, but the
     * lambda identity, immutable captures, and cell identities must remain
     * exact.
     */
    public Optional<CallableFlow> matchingCallable(
            LambdaId lambda,
            Map<DeclarationId, ValueAlternatives> capturedValues,
            Set<DeclarationId> sharedCells) {
        Objects.requireNonNull(sharedCells, "sharedCells");
        TreeMap<DeclarationId, ValueAlternatives> snapshots = new TreeMap<>();
        for (DeclarationId cell : sharedCells) {
            snapshots.put(cell, ValueAlternatives.empty());
        }
        return matchingCallable(lambda, capturedValues, snapshots);
    }

    /**
     * Resolves a callable that may have been produced by invoking a retained
     * callable rather than stored as a named boundary value.  The lambda code
     * and capture contracts still come from the producer certificate, while
     * the invocation supplies the exact immutable and shared-cell snapshots.
     */
    public Optional<CallableFlow> matchingCallable(
            LambdaId lambda,
            Map<DeclarationId, ValueAlternatives> capturedValues,
            Map<DeclarationId, ValueAlternatives> sharedCellSnapshots) {
        Objects.requireNonNull(lambda, "lambda");
        Objects.requireNonNull(capturedValues, "capturedValues");
        Objects.requireNonNull(sharedCellSnapshots, "sharedCellSnapshots");
        Optional<CallableFlow> direct = findCallable(
                boundaryState, lambda, capturedValues, sharedCellSnapshots.keySet());
        if (direct.isPresent()) {
            return direct;
        }
        CallableSummary summary = callableSummaries.summary(lambda).orElse(null);
        if (summary == null || !captureContractsMatch(
                summary, capturedValues, sharedCellSnapshots)) {
            return Optional.empty();
        }
        return Optional.of(CallableFlow.atRoot(
                lambda, capturedValues, sharedCellSnapshots));
    }

    private static boolean captureContractsMatch(
            CallableSummary summary,
            Map<DeclarationId, ValueAlternatives> capturedValues,
            Map<DeclarationId, ValueAlternatives> sharedCellSnapshots) {
        Set<DeclarationId> expectedImmutable = new TreeSet<>();
        Set<DeclarationId> expectedCells = new TreeSet<>();
        for (CallableSummary.CapturePlaceholder capture : summary.captures()) {
            if (capture.isSharedCell()) {
                expectedCells.add(capture.cellId().orElseThrow());
            } else {
                expectedImmutable.add(capture.declarationId());
            }
        }
        if (!expectedImmutable.equals(capturedValues.keySet())
                || !expectedCells.equals(sharedCellSnapshots.keySet())) {
            return false;
        }
        for (CallableSummary.CapturePlaceholder capture : summary.captures()) {
            ValueAlternatives values = capture.isSharedCell()
                    ? sharedCellSnapshots.get(capture.cellId().orElseThrow())
                    : capturedValues.get(capture.declarationId());
            if (values == null || values.isEmpty()
                    || !valuesHaveType(values, capture.contract().valueType())) {
                return false;
            }
        }
        return true;
    }

    /** True only for a retained producer aggregate identity. */
    public boolean certifiesAggregate(AggregateIdentityFact fact) {
        Objects.requireNonNull(fact, "fact");
        AggregateProofKey candidate = AggregateProofKey.of(fact);
        return aggregateProofs.contains(candidate)
                || aggregateProofs.stream().anyMatch(candidate::sameOrigin);
    }

    /** True only for an exact retained nominal allocation and current heap entry. */
    public boolean certifiesObject(NominalObjectFact fact) {
        Objects.requireNonNull(fact, "fact");
        var state = boundaryState.objects().get(fact.identity());
        return state != null && state.schema().type().equals(fact.identity().type())
                && fact.ownership().ownerModule().equals(fact.identity().ownerModule())
                && fact.ownership().originSite().equals(
                Optional.of(fact.identity().allocationSite()));
    }

    /** Exact certified binding metadata retained by this proof. */
    public Map<String, ExternalBinding> certifiedBindings() {
        return certifiedBindings;
    }

    /** Exact producer metadata for callable-local fresh allocations. */
    public Map<FreshAllocationSite, AllocationProvenance> allocationProvenances() {
        return allocationProvenance;
    }

    /** Exact nominal definitions retained for later member/type resolution. */
    public Map<String, RetainedNominal> retainedNominals() {
        return retainedNominals;
    }

    /** Current source names for retained session-local nominal definitions. */
    public Map<String, String> nominalNames() {
        return nominalNames;
    }

    /** Returns exact producer metadata for one retained fresh allocation site. */
    public Optional<AllocationProvenance> allocationProvenance(FreshAllocationSite site) {
        return Optional.ofNullable(allocationProvenance.get(
                Objects.requireNonNull(site, "site")));
    }

    /**
     * Internal compiler issuance path.  The package boundary is intentional:
     * callers can transport and inspect immutable evidence, but cannot mint a
     * certificate from type-only metadata or a reconstructed graph.
     */
    static SessionFlowCertificate issue(
            SessionSnapshot predecessorSnapshot,
            TypedSemanticGraph graph,
            Map<String, ExternalBinding> exposedBindings,
            boolean retainPrefixes) {
        return issue(predecessorSnapshot, graph, exposedBindings, retainPrefixes,
                (state, ignored) -> state);
    }

    /**
     * Issues a proof with an explicit boundary transform applied after the
     * canonical overlay.  The attachment path uses this to convert public
     * root aggregate bindings into conservative imported/boundary values
     * before any session compilation consumes the proof.
     */
    static SessionFlowCertificate issue(
            SessionSnapshot predecessorSnapshot,
            TypedSemanticGraph graph,
            Map<String, ExternalBinding> exposedBindings,
            boolean retainPrefixes,
            java.util.function.BiFunction<BindingFlowState, TypedSemanticGraph, BindingFlowState> boundaryTransform) {
        Objects.requireNonNull(predecessorSnapshot, "predecessorSnapshot");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(exposedBindings, "exposedBindings");
        Objects.requireNonNull(boundaryTransform, "boundaryTransform");

        SessionFlowCertificate predecessor = predecessorSnapshot.flowCertificate().orElse(null);
        SessionFlowCertificate graphPredecessor = graph.resolvedGraph()
                .sessionFlowCertificate().orElse(null);
        if (predecessor != graphPredecessor) {
            throw new IllegalArgumentException(
                    "typed graph was not resolved against the supplied session certificate");
        }
        if (predecessor != null && !graph.allocator().dominates(predecessor.allocator())) {
            throw new IllegalArgumentException("session identity allocator regressed");
        }

        BindingFlowState boundary = overlay(
                predecessor == null ? BindingFlowState.empty() : predecessor.boundaryState(),
                graph, retainPrefixes);
        if (retainPrefixes && predecessor != null) {
            boundary = predecessor.boundaryState().join(boundary);
        }
        boundary = boundaryTransform.apply(boundary, graph);
        // Transferred captured writes update stable cells. Publish that current
        // value through any corresponding named binding as well, never its old initializer.
        TreeMap<DeclarationId, BindingFlowValue> currentBindings = new TreeMap<>(boundary.bindings());
        TreeMap<DeclarationId, ValueAlternatives> currentCells = new TreeMap<>(boundary.sharedCells());
        currentCells.replaceAll((id, value) -> {
            BindingFlowValue binding = currentBindings.get(id);
            if (binding == null) return value;
            ValueAlternatives current = retainPrefixes ? binding.alternatives().join(value) : value;
            currentBindings.put(id, binding.withAlternatives(current));
            return current;
        });
        boundary = BindingFlowState.of(currentBindings, currentCells, boundary.objects());
        TreeMap<String, ExternalBinding> bindings = new TreeMap<>();
        if (predecessor != null) {
            bindings.putAll(predecessor.certifiedBindings);
        }
        for (Map.Entry<String, ExternalBinding> entry : exposedBindings.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "exposed binding name");
            ExternalBinding binding = Objects.requireNonNull(entry.getValue(), "exposed binding");
            if (!name.equals(binding.name())) {
                throw new IllegalArgumentException("exposed binding map key does not match its name");
            }
            BindingFlowValue value = boundary.bindings().get(binding.declarationId());
            if (value == null || !value.contract().equals(binding.contract())
                    || value.alternatives().isEmpty()) {
                throw new IllegalArgumentException(
                        "exposed binding has no sealed retained value: " + binding.name());
            }
            bindings.put(name, binding);
        }

        Set<CallableProofKey> callableProofs = new LinkedHashSet<>();
        Set<AggregateProofKey> aggregateProofs = new LinkedHashSet<>();
        if (predecessor != null) {
            callableProofs.addAll(predecessor.callableProofs);
            aggregateProofs.addAll(predecessor.aggregateProofs);
        }
        collectProofs(boundary, callableProofs, aggregateProofs);
        CallableSummarySet summaries = predecessor == null
                ? graph.semanticFlowFacts().callableSummaries()
                : CallableSummarySet.combine(
                        predecessor.callableSummaries(),
                        graph.semanticFlowFacts().callableSummaries());
        TreeMap<FreshAllocationSite, AllocationProvenance> allocations = new TreeMap<>();
        if (predecessor != null) {
            allocations.putAll(predecessor.allocationProvenance);
        }
        Set<SourceId> sourceIds = new LinkedHashSet<>();
        if (predecessor != null) {
            sourceIds.addAll(predecessor.sourceIds);
        }
        graph.modules().forEach(module -> sourceIds.add(module.moduleId().sourceId()));
        allocationProvenance(graph, allocations).forEach((site, provenance) -> {
            AllocationProvenance previous = allocations.putIfAbsent(site, provenance);
            if (previous != null && !previous.equals(provenance)) {
                throw new IllegalArgumentException(
                        "generation allocation provenance disagrees about site: " + site);
            }
        });
        TreeMap<String, RetainedNominal> nominals = new TreeMap<>();
        TreeMap<String, String> nominalNames = new TreeMap<>();
        if (predecessor != null) {
            nominals.putAll(predecessor.retainedNominals);
            nominalNames.putAll(predecessor.nominalNames);
        }
        ModuleId root = graph.resolvedGraph().moduleGraph().rootModule();
        for (ResolvedNominal nominal : graph.resolvedGraph().nominals()) {
            var declaration = graph.resolvedGraph().declaration(nominal.declaration()).orElseThrow();
            if (!declaration.moduleId().equals(root)
                    || !declaration.scopeId().equals(graph.resolvedGraph()
                    .module(root).orElseThrow().rootScope())) {
                continue;
            }
            String canonical = nominal.schema().type().canonicalSpelling();
            nominals.put(canonical, new RetainedNominal(
                    declaration.name(), nominal, declaration.visibility(),
                    nominal.members().stream().map(member -> graph.resolvedGraph()
                            .declaration(member).orElseThrow().initializerLambda()).toList()));
            nominalNames.put(declaration.name(), canonical);
        }
        return new SessionFlowCertificate(
                graph.allocator(), boundary, summaries, bindings,
                callableProofs, aggregateProofs, allocations, nominals, nominalNames, sourceIds,
                predecessor == null ? 1 : predecessor.generationCount + 1);
    }

    private static BindingFlowState overlay(
            BindingFlowState predecessor, TypedSemanticGraph graph, boolean retainPrefixes) {
        Map<ModuleId, BindingFlowState> states = retainPrefixes
                ? graph.semanticFlowFacts().attemptedStates() : graph.semanticFlowFacts().finalStates();
        if (retainPrefixes) {
            BindingFlowState attempted = predecessor;
            for (var state : states.values()) attempted = attempted.join(state);
            return attempted;
        }
        TreeMap<DeclarationId, BindingFlowValue> bindings = new TreeMap<>(predecessor.bindings());
        TreeMap<DeclarationId, ValueAlternatives> cells = new TreeMap<>(predecessor.sharedCells());
        var objects = new TreeMap<>(predecessor.objects());
        // Dependencies precede the submission. Map iteration must never choose which producer write wins.
        var root = graph.resolvedGraph().moduleGraph().rootModule();
        List<ModuleId> order = new ArrayList<>(graph.initializationOrder().stream().filter(id -> !id.equals(root)).toList());
        order.add(root);
        for (var module : order) {
            BindingFlowState state = states.get(module);
            if (state == null) continue;
            bindings.putAll(state.bindings());
            cells.putAll(state.sharedCells());
            objects.putAll(state.objects());
        }
        return BindingFlowState.of(bindings, cells, objects);
    }

    private static void collectProofs(
            BindingFlowState state,
            Set<CallableProofKey> callables,
            Set<AggregateProofKey> aggregates) {
        state.bindings().values().forEach(value -> collectProofs(
                value.alternatives(), callables, aggregates));
        state.sharedCells().values().forEach(value -> collectProofs(
                value, callables, aggregates));
        state.objects().values().forEach(object -> object.fields().values()
                .forEach(value -> collectProofs(value, callables, aggregates)));
    }

    private static void collectProofs(
            ValueAlternatives values,
            Set<CallableProofKey> callables,
            Set<AggregateProofKey> aggregates) {
        values.alternatives().forEach(value -> {
            value.aggregateIdentities().forEach(fact -> {
                aggregates.add(AggregateProofKey.of(fact));
            });
            value.callableFlows().forEach(callable -> {
                if (callables.add(CallableProofKey.of(callable))) {
                    callable.capturedValues().values().forEach(captured ->
                            collectProofs(captured, callables, aggregates));
                    callable.sharedCellSnapshots().values().forEach(captured ->
                            collectProofs(captured, callables, aggregates));
                }
            });
        });
    }

    private static Optional<CallableFlow> findCallable(
            BindingFlowState state,
            LambdaId lambda,
            Map<DeclarationId, ValueAlternatives> capturedValues,
            Set<DeclarationId> sharedCells) {
        for (BindingFlowValue binding : state.bindings().values()) {
            Optional<CallableFlow> found = findCallable(
                    binding.alternatives(), lambda, capturedValues, sharedCells);
            if (found.isPresent()) {
                return found;
            }
        }
        for (ValueAlternatives values : state.sharedCells().values()) {
            Optional<CallableFlow> found = findCallable(
                    values, lambda, capturedValues, sharedCells);
            if (found.isPresent()) {
                return found;
            }
        }
        for (var object : state.objects().values()) {
            for (ValueAlternatives values : object.fields().values()) {
                Optional<CallableFlow> found = findCallable(
                        values, lambda, capturedValues, sharedCells);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<CallableFlow> findCallable(
            ValueAlternatives values,
            LambdaId lambda,
            Map<DeclarationId, ValueAlternatives> capturedValues,
            Set<DeclarationId> sharedCells) {
        for (ValueAlternative alternative : values.alternatives()) {
            for (CallableFlow callable : alternative.callableFlows()) {
                if (callable.lambdaId().filter(lambda::equals).isPresent()
                        && callable.capturedValues().equals(capturedValues)
                        && callable.sharedCellSnapshots().keySet().equals(sharedCells)) {
                    return Optional.of(callable);
                }
                Optional<CallableFlow> nested = findCallable(
                        callable.capturedValues(), lambda, capturedValues, sharedCells);
                if (nested.isPresent()) {
                    return nested;
                }
                nested = findCallable(
                        callable.sharedCellSnapshots(), lambda, capturedValues, sharedCells);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<CallableFlow> findCallable(
            Map<DeclarationId, ValueAlternatives> values,
            LambdaId lambda,
            Map<DeclarationId, ValueAlternatives> capturedValues,
            Set<DeclarationId> sharedCells) {
        for (ValueAlternatives nestedValues : values.values()) {
            Optional<CallableFlow> found = findCallable(
                    nestedValues, lambda, capturedValues, sharedCells);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static boolean containsCallableType(LyraType type) {
        LyraType value = Objects.requireNonNull(type, "type").withoutQualifiers();
        if (value instanceof FunctionType) {
            return true;
        }
        if (value instanceof ArrayType array) {
            return containsCallableType(array.elementType());
        }
        if (value instanceof TupleType tuple) {
            return tuple.memberTypes().stream().anyMatch(
                    SessionFlowCertificate::containsCallableType);
        }
        return false;
    }

    private static Map<FreshAllocationSite, AllocationProvenance> allocationProvenance(
            TypedSemanticGraph graph,
            Map<FreshAllocationSite, AllocationProvenance> inherited) {
        TreeSet<FreshAllocationSite> sites = new TreeSet<>();
        for (CallableSummary summary : graph.semanticFlowFacts()
                .callableSummaries().orderedSummaries()) {
            collectFreshSites(summary.returnFormula().alternatives(), sites);
            for (var write : summary.writes()) {
                collectFreshSites(write.value(), sites);
            }
            for (var call : summary.callReferences()) {
                collectFreshSites(call.target(), sites);
                call.arguments().forEach(value -> collectFreshSites(value, sites));
            }
            for (var requirement : summary.ownershipRequirements()) {
                collectFreshSites(requirement.value(), sites);
            }
        }
        TreeMap<FreshAllocationSite, AllocationProvenance> result = new TreeMap<>();
        for (FreshAllocationSite site : sites) {
            TypedLambda owner = graph.lambda(site.ownerLambda()).orElse(null);
            if (owner == null) {
                if (inherited.containsKey(site)) {
                    continue;
                }
                throw new IllegalArgumentException(
                        "summary allocation belongs to an absent lambda: " + site);
            }
            ArrayList<TypedExpression> allocations = new ArrayList<>();
            collectLambdaAllocations(owner.body(), allocations);
            if (site.ordinal() >= allocations.size()) {
                throw new IllegalArgumentException(
                        "summary allocation ordinal is absent from its typed lambda body: " + site);
            }
            TypedExpression expression = allocations.get(site.ordinal());
            if (!expression.span().equals(site.span())
                    || !(expression.type().withoutQualifiers() instanceof ArrayType arrayType)) {
                throw new IllegalArgumentException(
                        "summary allocation site does not match its typed lambda body: " + site);
            }
            result.put(site, new AllocationProvenance(
                    owner.moduleId(), graph.flowScopeId(expression), expression.span(),
                    graph.flowSiteId(expression), arrayType));
        }
        return result;
    }

    private static void collectFreshSites(
            FormulaAlternatives alternatives,
            Set<FreshAllocationSite> destination) {
        for (ValueFormula formula : alternatives.formulas()) {
            collectFreshSites(formula, destination);
        }
    }

    private static void collectFreshSites(
            ValueFormula formula,
            Set<FreshAllocationSite> destination) {
        if (formula instanceof ValueFormula.FreshAllocation fresh) {
            destination.add(fresh.allocationSite());
        } else if (formula instanceof ValueFormula.Lambda lambda) {
            lambda.captures().values().forEach(value -> collectFreshSites(value, destination));
        }
    }

    private static void collectLambdaAllocations(
            TypedExpression expression,
            List<TypedExpression> destination) {
        if (expression.kind() == TypedExpressionKind.LAMBDA) {
            return;
        }
        for (TypedExpression child : expression.children()) {
            collectLambdaAllocations(child, destination);
        }
        if (expression.kind() == TypedExpressionKind.ARRAY_LITERAL) {
            destination.add(expression);
        }
    }

    private static Map<FreshAllocationSite, AllocationProvenance> immutableAllocationProvenance(
            Map<FreshAllocationSite, AllocationProvenance> values) {
        Objects.requireNonNull(values, "allocationProvenance");
        TreeMap<FreshAllocationSite, AllocationProvenance> ordered = new TreeMap<>();
        values.forEach((site, provenance) -> ordered.put(
                Objects.requireNonNull(site, "allocation site"),
                Objects.requireNonNull(provenance, "allocation provenance")));
        return Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
    }

    private static Map<String, ExternalBinding> immutableBindings(
            Map<String, ExternalBinding> values) {
        Objects.requireNonNull(values, "certifiedBindings");
        TreeMap<String, ExternalBinding> ordered = new TreeMap<>();
        values.forEach((name, binding) -> {
            if (!Objects.requireNonNull(name, "certified binding name")
                    .equals(Objects.requireNonNull(binding, "certified binding").name())) {
                throw new IllegalArgumentException("certified binding map key does not match its name");
            }
            ordered.put(name, binding);
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
    }

    private static Map<String, RetainedNominal> immutableRetainedNominals(
            Map<String, RetainedNominal> values) {
        Objects.requireNonNull(values, "retainedNominals");
        TreeMap<String, RetainedNominal> ordered = new TreeMap<>();
        values.forEach((canonical, nominal) -> {
            RetainedNominal value = Objects.requireNonNull(nominal, "retained nominal");
            if (!Objects.requireNonNull(canonical, "retained nominal key")
                    .equals(value.nominal().schema().type().canonicalSpelling())) {
                throw new IllegalArgumentException("retained nominal key differs from its schema");
            }
            ordered.put(canonical, value);
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
    }

    private static Map<String, String> immutableNominalNames(
            Map<String, String> values, Map<String, RetainedNominal> nominals) {
        Objects.requireNonNull(values, "nominalNames");
        TreeMap<String, String> ordered = new TreeMap<>();
        values.forEach((name, canonical) -> {
            if (name.isBlank() || !nominals.containsKey(canonical)
                    || !nominals.get(canonical).name().equals(name)) {
                throw new IllegalArgumentException("retained nominal name has no exact definition");
            }
            ordered.put(name, canonical);
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
    }

    /** Compiler-only retained schema/member identity; never a runtime capability. */
    public record RetainedNominal(
            String name, ResolvedNominal nominal, DeclarationVisibility visibility,
            List<Optional<LambdaId>> memberInitializerLambdas) {
        public RetainedNominal {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(nominal, "nominal");
            Objects.requireNonNull(visibility, "visibility");
            memberInitializerLambdas = List.copyOf(memberInitializerLambdas);
            if (memberInitializerLambdas.size() != nominal.members().size()) {
                throw new IllegalArgumentException("retained nominal initializer inventory differs");
            }
            if (!name.equals(nominal.schema().type().id().name())) {
                throw new IllegalArgumentException("retained nominal name differs from its identity");
            }
        }
    }

    /** Producer-owned metadata needed to lower a retained callable allocation. */
    public record AllocationProvenance(
            ModuleId moduleId,
            ScopeId scopeId,
            io.mindspice.lyra.compiler.source.SourceSpan sourceSpan,
            FlowSiteId originSite,
            ArrayType arrayType) {
        public AllocationProvenance {
            Objects.requireNonNull(moduleId, "moduleId");
            Objects.requireNonNull(scopeId, "scopeId");
            Objects.requireNonNull(sourceSpan, "sourceSpan");
            Objects.requireNonNull(originSite, "originSite");
            Objects.requireNonNull(arrayType, "arrayType");
            if (!moduleId.sourceId().equals(sourceSpan.sourceId())) {
                throw new IllegalArgumentException(
                        "allocation provenance span belongs to another module");
            }
        }

        public FlowSiteId flowSite() {
            return originSite;
        }
    }

    private record CallableProofKey(
            Optional<LambdaId> lambda,
            Optional<DeclarationId> intrinsic,
            Map<DeclarationId, ValueAlternatives> capturedValues,
            Set<DeclarationId> sharedCells,
            Optional<FlowSiteId> creationSite) {
        private CallableProofKey {
            lambda = Objects.requireNonNull(lambda, "lambda");
            intrinsic = Objects.requireNonNull(intrinsic, "intrinsic");
            capturedValues = Map.copyOf(Objects.requireNonNull(capturedValues, "capturedValues"));
            sharedCells = Set.copyOf(Objects.requireNonNull(sharedCells, "sharedCells"));
            creationSite = Objects.requireNonNull(creationSite, "creationSite");
        }

        private static CallableProofKey of(CallableFlow callable) {
            return new CallableProofKey(
                    callable.lambdaId(), callable.intrinsicDeclarationId(),
                    callable.capturedValues(), callable.sharedCellSnapshots().keySet(),
                    callable.creationSite());
        }
    }

    private record AggregateProofKey(
            ArrayIdentity identity,
            io.mindspice.lyra.compiler.semantic.flow.ProjectionPath route,
            String originKey) {
        private AggregateProofKey {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(originKey, "originKey");
        }

        private static AggregateProofKey of(AggregateIdentityFact fact) {
            var witness = fact.witness();
            String origin = witness.ownerModule() + "/" + witness.originDeclaration()
                    + "/" + witness.scopeId() + "/" + witness.sourceSpan()
                    + "/" + witness.originExport() + "/" + witness.originSite();
            return new AggregateProofKey(fact.identity(), fact.route(), origin);
        }

        private boolean sameOrigin(AggregateProofKey other) {
            return identity.equals(other.identity)
                    && route.equals(other.route)
                    && originKey.equals(other.originKey);
        }
    }
}
