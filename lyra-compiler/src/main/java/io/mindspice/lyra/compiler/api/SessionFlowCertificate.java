package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.IdentityAllocator;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.semantic.TypedExpression;
import io.mindspice.lyra.compiler.semantic.TypedExpressionKind;
import io.mindspice.lyra.compiler.semantic.TypedLambda;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.TypedSemanticInput;
import io.mindspice.lyra.compiler.semantic.DeclarationVisibility;
import io.mindspice.lyra.compiler.semantic.ResolvedNominal;
import io.mindspice.lyra.compiler.semantic.flow.AggregateIdentityFact;
import io.mindspice.lyra.compiler.semantic.flow.ArrayIdentity;
import io.mindspice.lyra.compiler.semantic.flow.BindingFlowState;
import io.mindspice.lyra.compiler.semantic.flow.BindingFlowValue;
import io.mindspice.lyra.compiler.semantic.flow.CallableCallReference;
import io.mindspice.lyra.compiler.semantic.flow.CallableFlow;
import io.mindspice.lyra.compiler.semantic.flow.CallableSummary;
import io.mindspice.lyra.compiler.semantic.flow.CapturedCellWrite;
import io.mindspice.lyra.compiler.semantic.flow.CallableSummarySet;
import io.mindspice.lyra.compiler.semantic.flow.EagerEffectWitness;
import io.mindspice.lyra.compiler.semantic.flow.FormulaAlternatives;
import io.mindspice.lyra.compiler.semantic.flow.FreshAllocationSite;
import io.mindspice.lyra.compiler.semantic.flow.NominalObjectFact;
import io.mindspice.lyra.compiler.semantic.flow.NilProvenance;
import io.mindspice.lyra.compiler.semantic.flow.OwnershipWitness;
import io.mindspice.lyra.compiler.semantic.flow.ProjectionPath;
import io.mindspice.lyra.compiler.semantic.flow.SummaryCallId;
import io.mindspice.lyra.compiler.semantic.flow.ValueFormula;
import io.mindspice.lyra.compiler.semantic.flow.ValueAlternative;
import io.mindspice.lyra.compiler.semantic.flow.ValueAlternatives;
import io.mindspice.lyra.compiler.semantic.flow.WriteTarget;
import io.mindspice.lyra.compiler.session.ExternalBinding;
import io.mindspice.lyra.compiler.session.SessionSnapshot;
import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.BindingContract;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.LyraSignature;
import io.mindspice.lyra.compiler.types.LyraType;
import io.mindspice.lyra.compiler.types.NominalType;
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
    private final Set<ObjectProofKey> objectProofs;
    private final Set<SourceSpan> aggregateUseSpans;
    private final Map<FreshAllocationSite, AllocationProvenance> allocationProvenance;
    private final Map<SummaryCallId, RetainedConstruction> retainedConstructions;
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
            Set<ObjectProofKey> objectProofs,
            Set<SourceSpan> aggregateUseSpans,
            Map<FreshAllocationSite, AllocationProvenance> allocationProvenance,
            Map<SummaryCallId, RetainedConstruction> retainedConstructions,
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
        this.objectProofs = Set.copyOf(Objects.requireNonNull(objectProofs, "objectProofs"));
        this.aggregateUseSpans = Set.copyOf(
                Objects.requireNonNull(aggregateUseSpans, "aggregateUseSpans"));
        this.allocationProvenance = immutableAllocationProvenance(allocationProvenance);
        this.retainedConstructions = immutableRetainedConstructions(retainedConstructions);
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
        return callableProofs.stream().anyMatch(value -> value.lambda().filter(lambda::equals).isPresent())
                || retainedNominals.values().stream().anyMatch(nominal ->
                nominal.constructorLambda().filter(lambda::equals).isPresent()
                        || nominal.memberInitializers().stream()
                        .flatMap(Optional::stream)
                        .anyMatch(initializer -> certifiesLambda(initializer, lambda)));
    }

    private static boolean certifiesLambda(
            RetainedInitializerTransfer initializer, LambdaId lambda) {
        return switch (initializer) {
            case RetainedInitializerTransfer.Lambda value -> value.lambda().equals(lambda);
            case RetainedInitializerTransfer.Reference ignored -> false;
            case RetainedInitializerTransfer.Value ignored -> false;
            case RetainedInitializerTransfer.Call call -> call.arguments().stream()
                    .anyMatch(argument -> certifiesLambda(argument, lambda));
        };
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
                        && hasCertifiedSuffix(certified, candidate))
                || callableSummaries.orderedSummaries().stream()
                .flatMap(summary -> summary.callReferences().stream())
                .anyMatch(call -> certifiesDynamicEffect(call, candidate));
    }

    /**
     * True when the producer proof fixes this effect's concrete target lambda.
     * Dynamic parameter/capture/callable slots may accept a consumer callable,
     * but that callable must then be owned independently by the current graph
     * or another retained proof.
     */
    public boolean certifiesEffectTarget(EagerEffectWitness candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return callableSummaries.orderedSummaries().stream()
                .flatMap(summary -> summary.eagerEffects().stream())
                .anyMatch(certified -> certified.targetLambda().equals(candidate.targetLambda())
                        && sameEffectIdentity(certified, candidate)
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
            if (summary.callReferences().stream().anyMatch(call ->
                    call.siteId().filter(site::equals).isPresent()
                            && call.span().equals(span))) {
                return true;
            }
        }
        return false;
    }

    private static boolean certifiesDynamicEffect(
            CallableCallReference call,
            EagerEffectWitness candidate) {
        EagerEffectWitness.Kind expected = switch (call.kind()) {
            case CALLABLE -> EagerEffectWitness.Kind.CALLABLE_CALL;
            case PARAMETER -> EagerEffectWitness.Kind.PARAMETER_CALL;
            case CAPTURE -> EagerEffectWitness.Kind.CAPTURE_CALL;
            default -> null;
        };
        return expected != null
                && candidate.kind() == expected
                && call.span().equals(candidate.effectSpan())
                && call.siteId().equals(candidate.effectSite())
                && call.targetDeclaration().equals(candidate.targetDeclaration())
                && call.referenceId().equals(candidate.referenceId())
                && !candidate.sourcePath().isEmpty()
                && candidate.sourcePath().getLast().equals(call.span())
                && !candidate.sourceSitePath().isEmpty()
                && candidate.sourceSitePath().getLast().equals(call.siteId().orElseThrow());
    }

    private boolean sameEffectIdentity(
            EagerEffectWitness certified,
            EagerEffectWitness candidate) {
        boolean targetMatches = certified.targetModule().equals(candidate.targetModule())
                || candidate.targetModule().equals(candidate.fromModule());
        return targetMatches
                && certified.kind().equals(candidate.kind())
                && certified.effectSpan().equals(candidate.effectSpan())
                && certified.effectSite().equals(candidate.effectSite())
                && certified.targetDeclaration().equals(candidate.targetDeclaration())
                && certified.referenceId().equals(candidate.referenceId())
                && (certified.targetLambda().equals(candidate.targetLambda())
                || isDynamicCallableEffect(candidate.kind())
                || candidate.kind() != EagerEffectWitness.Kind.VALUE_READ
                && candidate.targetDeclaration().flatMap(boundaryState::binding)
                        .filter(value -> value.contract().isMutable())
                        .stream().flatMap(value -> value.callableFlows().stream())
                        .anyMatch(value -> value.lambdaId().isPresent()
                                && value.lambdaId().equals(candidate.targetLambda())));
    }

    private static boolean isDynamicCallableEffect(EagerEffectWitness.Kind kind) {
        return kind == EagerEffectWitness.Kind.CALLABLE_CALL
                || kind == EagerEffectWitness.Kind.PARAMETER_CALL
                || kind == EagerEffectWitness.Kind.CAPTURE_CALL;
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
                || aggregateProofs.stream().anyMatch(candidate::sameOrigin)
                || certifiesForeignAllocation(fact);
    }

    /**
     * Accepts the imported view of an exact producer allocation.  The retained
     * summary still names the producer allocation; only its consumer-facing
     * ownership class and use span change.  This never turns an arbitrary
     * cross-module identity into certified evidence.
     */
    private boolean certifiesForeignAllocation(AggregateIdentityFact fact) {
        if (!(fact.identity() instanceof ArrayIdentity.CrossModuleOrigin imported)) {
            return false;
        }
        var expectedExport = io.mindspice.lyra.compiler.identity.ExportId.of(
                imported.ownerModule(), "_flow_" + imported.originDeclaration().ordinal(),
                LyraSignature.of(List.of(), imported.arrayType()));
        OwnershipWitness witness = fact.ownershipWitness();
        if (!expectedExport.equals(imported.exportId())
                || !witness.ownerModule().equals(imported.ownerModule())
                || !witness.originDeclaration().equals(imported.originDeclaration())
                || !witness.originExport().equals(Optional.of(expectedExport))
                || witness.originSite().isEmpty()) {
            return false;
        }
        OwnershipWitness producerWitness = OwnershipWitness.local(
                        imported.ownerModule(), imported.originDeclaration(), witness.scopeId(),
                        witness.sourceSpan())
                .withOriginSite(witness.originSite().orElseThrow());
        AggregateProofKey producer = AggregateProofKey.of(new AggregateIdentityFact(
                ArrayIdentity.localAllocation(imported.ownerModule(),
                        imported.originDeclaration(), imported.arrayType()),
                fact.route(), producerWitness));
        return aggregateProofs.contains(producer);
    }

    /** True only for producer-certified nil provenance retained by this proof. */
    public boolean certifiesNil(NilProvenance nil) {
        Objects.requireNonNull(nil, "nil");
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        if (containsNil(boundaryState, nil, visited)) {
            return true;
        }
        for (RetainedNominal nominal : retainedNominals.values()) {
            if (nominal.memberInitializers().stream().flatMap(Optional::stream)
                    .anyMatch(transfer -> containsNil(transfer, nil, visited))) {
                return true;
            }
        }
        for (CallableSummary summary : callableSummaries.orderedSummaries()) {
            if (containsNil(summary.returnFormula().alternatives(), nil)
                    || summary.writes().stream().anyMatch(write -> containsNil(write.value(), nil))
                    || summary.callReferences().stream().anyMatch(call ->
                    containsNil(call.target(), nil)
                            || call.arguments().stream().anyMatch(value -> containsNil(value, nil)))) {
                return true;
            }
        }
        return false;
    }

    /** True only for an exact retained nominal allocation and current heap entry. */
    public boolean certifiesObject(NominalObjectFact fact) {
        Objects.requireNonNull(fact, "fact");
        return objectProofs.contains(ObjectProofKey.of(fact))
                || retainedConstructions.values().stream().anyMatch(construction ->
                construction.certifies(fact));
    }

    /**
     * Consumer-use check for a certified aggregate fact.  A certified identity
     * may be transported, but the occurrence that claims to use it must belong
     * to a generation source or be the exact producer allocation origin.  A use
     * site from an unrelated module is never certified.
     */
    public boolean certifiesAggregateUse(AggregateIdentityFact fact) {
        Objects.requireNonNull(fact, "fact");
        SourceSpan use = fact.witness().useSpan();
        return use.equals(fact.witness().sourceSpan())
                || aggregateUseSpans.contains(use);
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

    /** Resolves one exact producer construction call retained by its summary. */
    public Optional<RetainedConstruction> retainedConstruction(
            CallableCallReference call) {
        Objects.requireNonNull(call, "call");
        RetainedConstruction construction = retainedConstructions.get(call.id());
        return construction != null && construction.call().equals(call)
                ? Optional.of(construction) : Optional.empty();
    }

    /**
     * Returns the first source-local nominal initializer that cannot yet be
     * represented by the closed session transfer algebra.  The semantic flow
     * publication boundary calls this before certificate issuance so a valid
     * source form becomes a stable structured session diagnostic rather than
     * an exception outside a phase boundary.
     */
    public static Optional<Diagnostic> retainedInitializerDiagnostic(
            TypedSemanticInput graph) {
        Objects.requireNonNull(graph, "graph");
        if (!graph.resolvedGraph().isSessionGraph()) {
            return Optional.empty();
        }
        ModuleId root = graph.resolvedGraph().moduleGraph().rootModule();
        ScopeId rootScope = graph.resolvedGraph().module(root)
                .map(value -> value.rootScope()).orElse(null);
        if (rootScope == null) {
            return Optional.empty();
        }
        for (ResolvedNominal nominal : graph.resolvedGraph().nominals()) {
            var owner = graph.resolvedGraph().declaration(nominal.declaration()).orElse(null);
            if (owner == null || !owner.moduleId().equals(root)
                    || !owner.scopeId().equals(rootScope)) {
                continue;
            }
            for (int index = 0; index < nominal.members().size(); index++) {
                if (!nominal.schema().members().get(index).hasInitializer()) {
                    continue;
                }
                var member = graph.declaration(nominal.members().get(index)).orElse(null);
                if (member == null || member.initializer().isEmpty()
                        && member.initializerLambda().isEmpty()) {
                    // Restored definitions intentionally have no current source
                    // initializer; their predecessor transfer is checked on use.
                    continue;
                }
                if (currentInitializerEvidence(member, graph).isEmpty()) {
                    SourceSpan span = member.initializer().map(TypedExpression::span)
                            .orElse(member.span());
                    return Optional.of(Diagnostic.error(
                            CompilerDiagnosticCodes.SESSION_EXTERNAL_BINDING_UNSUPPORTED,
                            span,
                            "retained nominal member initializer '" + member.name()
                                    + "' cannot yet be transferred across session generations"));
                }
            }
        }
        return Optional.empty();
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
        Set<ObjectProofKey> objectProofs = new LinkedHashSet<>();
        Set<SourceSpan> aggregateUseSpans = new LinkedHashSet<>();
        if (predecessor != null) {
            callableProofs.addAll(predecessor.callableProofs);
            aggregateProofs.addAll(predecessor.aggregateProofs);
            objectProofs.addAll(predecessor.objectProofs);
            aggregateUseSpans.addAll(predecessor.aggregateUseSpans);
        }
        collectProofs(boundary, callableProofs, aggregateProofs, objectProofs, aggregateUseSpans);
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
        // Every allocation a retained callable can mint on later invocation must
        // already be certified here, because the consumer never re-executes the
        // producer body and must reproduce this exact identity and witness.
        collectSummaryAllocationProofs(graph, allocations, aggregateProofs);
        TreeMap<SummaryCallId, RetainedConstruction> constructions = new TreeMap<>();
        if (predecessor != null) {
            constructions.putAll(predecessor.retainedConstructions);
        }
        collectRetainedConstructions(graph).forEach((id, construction) -> {
            RetainedConstruction previous = constructions.putIfAbsent(id, construction);
            if (previous != null && !previous.equals(construction)) {
                throw new IllegalArgumentException(
                        "generation constructor provenance disagrees about call: " + id);
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
            RetainedNominal predecessorNominal = predecessor == null
                    ? null : predecessor.retainedNominals.get(canonical);
            List<Optional<RetainedInitializerTransfer>> initializers = new ArrayList<>();
            int evidence = 0;
            for (DeclarationId member : nominal.members()) {
                Optional<RetainedInitializerTransfer> transfer = currentInitializerEvidence(
                        graph.declaration(member).orElse(null), graph);
                if (transfer.isPresent()) {
                    evidence++;
                }
                initializers.add(transfer);
            }
            long initializedMembers = nominal.schema().members().stream()
                    .filter(io.mindspice.lyra.compiler.types.NominalSchema.Member::hasInitializer)
                    .count();
            if (evidence > 0 && evidence != initializedMembers) {
                throw new IllegalArgumentException(
                        "retained nominal initializer evidence is partial for " + canonical);
            }
            if (evidence == 0 && initializedMembers > 0) {
                // A later generation re-registers a retained nominal without its
                // producer initializers.  Carry the predecessor's exact certified
                // transfer forward; never fabricate one and never drop proof.
                RetainedNominal carried = predecessorNominal;
                if (carried == null || !sameRetainedIdentity(carried, nominal, declaration)) {
                    throw new IllegalArgumentException(
                            "retained nominal has no certified initializer transfer: " + canonical);
                }
                initializers = new ArrayList<>(carried.memberInitializers());
            }
            for (int index = 0; index < initializers.size(); index++) {
                if (initializers.get(index).isPresent()
                        != nominal.schema().members().get(index).hasInitializer()) {
                    throw new IllegalArgumentException(
                            "retained nominal initializer coverage differs from its schema: "
                                    + canonical);
                }
            }
            nominals.put(canonical, new RetainedNominal(
                    declaration.name(), nominal, declaration.visibility(),
                    nominal.constructor(), initializers));
            nominalNames.put(declaration.name(), canonical);
        }
        nominals.values().forEach(nominal -> nominal.memberInitializers().forEach(
                transfer -> transfer.ifPresent(value -> collectTransferProofs(
                        value, callableProofs, aggregateProofs, objectProofs,
                        aggregateUseSpans))));
        return new SessionFlowCertificate(
                graph.allocator(), boundary, summaries, bindings,
                callableProofs, aggregateProofs, objectProofs, aggregateUseSpans,
                allocations, constructions,
                nominals, nominalNames, sourceIds,
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
            Set<AggregateProofKey> aggregates,
            Set<ObjectProofKey> objects,
            Set<SourceSpan> aggregateUses) {
        state.bindings().values().forEach(value -> collectProofs(
                value.alternatives(), callables, aggregates, objects, aggregateUses));
        state.sharedCells().values().forEach(value -> collectProofs(
                value, callables, aggregates, objects, aggregateUses));
        state.objects().values().forEach(object -> object.fields().values()
                .forEach(value -> collectProofs(
                        value, callables, aggregates, objects, aggregateUses)));
    }

    /**
     * Checks predecessor continuity for a retained nominal that a later
     * generation re-registered without its producer initializers.  The
     * constructor identity is carried evidence: a restored nominal exposes no
     * constructor lambda because it is never recompiled.
     */
    private static boolean sameRetainedIdentity(
            RetainedNominal carried, ResolvedNominal nominal,
            io.mindspice.lyra.compiler.semantic.ResolvedDeclaration declaration) {
        ResolvedNominal previous = carried.nominal();
        return previous.declaration().equals(nominal.declaration())
                && previous.self().equals(nominal.self())
                && previous.schema().equals(nominal.schema())
                && previous.members().equals(nominal.members())
                && carried.name().equals(declaration.name())
                && carried.visibility() == declaration.visibility()
                && (nominal.constructor().isEmpty()
                || nominal.constructor().equals(previous.constructor()));
    }

    /**
     * Producer-certified transfer evidence available in the current graph.  A
     * retained nominal re-registered in a later generation exposes no
     * initializer here; the caller carries the predecessor transfer forward.
     */
    private static Optional<RetainedInitializerTransfer> currentInitializerEvidence(
            io.mindspice.lyra.compiler.semantic.TypedDeclaration declaration,
            TypedSemanticInput graph) {
        if (declaration == null) {
            return Optional.empty();
        }
        if (declaration.initializerLambda().isPresent()) {
            return Optional.of(new RetainedInitializerTransfer.Lambda(
                    declaration.initializerLambda().orElseThrow()));
        }
        return declaration.initializer().flatMap(value ->
                retainedInitializerTransfer(value, declaration, graph));
    }

    /**
     * Builds the closed producer-certified transfer for one retained member
     * initializer expression.  The result is a closed abstract value, an exact
     * declaration projection, or a certified call; arbitrary producer
     * expression trees are never retained.
     *
     * <p>Only forms whose transfer this generation can represent exactly are
     * issued.  A member initializer with no closed transfer is a proof gap and
     * fails issuance instead of receiving a fabricated scalar or type-shaped
     * placeholder.</p>
     */
    private static Optional<RetainedInitializerTransfer> retainedInitializerTransfer(
            TypedExpression initializer, io.mindspice.lyra.compiler.semantic.TypedDeclaration declaration,
            TypedSemanticInput graph) {
        if (declaration.initializerLambda().isPresent()) {
            return Optional.of(new RetainedInitializerTransfer.Lambda(
                    declaration.initializerLambda().orElseThrow()));
        }
        if (initializer.kind() == TypedExpressionKind.DIRECT_CALL
                || initializer.kind() == TypedExpressionKind.NAMESPACE_DIRECT_CALL) {
            return retainedInitializerCall(initializer, graph)
                    .map(RetainedInitializerTransfer.class::cast);
        }
        Optional<RetainedInitializerTransfer.Reference> reference =
                retainedReference(initializer, graph);
        if (reference.isPresent()) {
            return Optional.of(reference.orElseThrow());
        }
        return retainedLiteralValue(initializer, graph)
                .map(RetainedInitializerTransfer.Value::new);
    }

    /**
     * Derives an exact declaration projection.  The route records nominal member
     * slots and named tuple members so the consumer resolves the original
     * declaration identity against the fresh receiver or the current shared
     * cell, never against current source spelling.
     */
    private static Optional<RetainedInitializerTransfer.Reference> retainedReference(
            TypedExpression expression, TypedSemanticInput graph) {
        if (expression.kind() == TypedExpressionKind.REFERENCE) {
            return expression.link()
                    .flatMap(io.mindspice.lyra.compiler.semantic.TypedLink::declarationId)
                    .map(declaration -> new RetainedInitializerTransfer.Reference(
                            declaration, ProjectionPath.root()));
        }
        if (expression.kind() != TypedExpressionKind.MEMBER_ACCESS
                || expression.children().isEmpty()) {
            return Optional.empty();
        }
        Optional<RetainedInitializerTransfer.Reference> base =
                retainedReference(expression.children().getFirst(), graph);
        if (base.isEmpty()) {
            return Optional.empty();
        }
        if (expression.tupleIndex().isPresent()) {
            ProjectionPath route = base.orElseThrow().route().compose(
                    ProjectionPath.tupleMember(
                            expression.tupleIndex().orElseThrow().intValueExact()));
            return Optional.of(new RetainedInitializerTransfer.Reference(
                    base.orElseThrow().declaration(), route));
        }
        if (expression.declarationId().isEmpty()
                || !(expression.children().getFirst().type().withoutQualifiers()
                instanceof io.mindspice.lyra.compiler.types.NominalType owner)) {
            return Optional.empty();
        }
        var nominal = graph.resolvedGraph().nominals().stream()
                .filter(value -> value.schema().type().equals(owner))
                .findFirst().orElse(null);
        if (nominal == null) {
            return Optional.empty();
        }
        int index = nominal.members().indexOf(expression.declarationId().orElseThrow());
        if (index < 0) {
            // A method selection is a callable, not stored field data.
            return Optional.empty();
        }
        var step = new io.mindspice.lyra.compiler.semantic.flow.ProjectionStep.NominalMember(
                owner, index, nominal.schema().members().get(index).type());
        ProjectionPath route = base.orElseThrow().route().compose(
                ProjectionPath.of(step));
        return Optional.of(new RetainedInitializerTransfer.Reference(
                base.orElseThrow().declaration(), route));
    }

    /**
     * Closed abstract value for a literal or for a literal composition.  An
     * array literal carries its own allocation identity so alias relationships
     * inside one initializer value survive; a member whose transfer this
     * generation cannot represent is left without a transfer rather than
     * replaced by a fabricated type-shaped allocation.
     */
    private static Optional<ValueAlternatives> retainedLiteralValue(
            TypedExpression initializer, TypedSemanticInput graph) {
        if (initializer.kind() == TypedExpressionKind.LITERAL) {
            if (initializer.literal().orElse(null)
                    instanceof io.mindspice.lyra.compiler.semantic.TypedLiteralValue.NilValue) {
                return Optional.of(ValueAlternatives.singleton(ValueAlternative.nil(
                        initializer.type(), new NilProvenance(graph.flowSiteId(initializer),
                        initializer.span(), ProjectionPath.root()))));
            }
            return Optional.of(ValueAlternatives.singleton(
                    ValueAlternative.scalar(initializer.type())));
        }
        if (initializer.kind() != TypedExpressionKind.ARRAY_LITERAL
                && initializer.kind() != TypedExpressionKind.TUPLE_LITERAL) {
            return Optional.empty();
        }
        List<ValueAlternative> combinations = List.of(ValueAlternative.scalar(initializer.type()));
        for (int index = 0; index < initializer.children().size(); index++) {
            Optional<ValueAlternatives> child =
                    retainedLiteralValue(initializer.children().get(index), graph);
            if (child.isEmpty()) {
                return Optional.empty();
            }
            ProjectionPath route = initializer.kind() == TypedExpressionKind.ARRAY_LITERAL
                    ? ProjectionPath.arrayElement(index) : ProjectionPath.tupleMember(index);
            List<ValueAlternative> next = new ArrayList<>();
            for (ValueAlternative prefix : combinations) {
                for (ValueAlternative member : child.orElseThrow().alternatives()) {
                    var facts = new ArrayList<>(prefix.aggregateIdentities());
                    facts.addAll(member.aggregateIdentities().stream()
                            .map(value -> value.prefixedBy(route)).toList());
                    var callables = new ArrayList<>(prefix.callableFlows());
                    callables.addAll(member.callableFlows().stream()
                            .map(value -> value.prefixedBy(route)).toList());
                    var nils = new ArrayList<>(prefix.nilProvenance());
                    nils.addAll(member.nilProvenance().stream()
                            .map(value -> value.prefixedBy(route)).toList());
                    var objects = new ArrayList<>(prefix.objects());
                    objects.addAll(member.objects().stream()
                            .map(value -> value.prefixedBy(route)).toList());
                    next.add(ValueAlternative.of(initializer.type(), facts, callables, nils, objects));
                }
            }
            combinations = next;
        }
        if (initializer.kind() == TypedExpressionKind.ARRAY_LITERAL) {
            ArrayType array = (ArrayType) initializer.type().withoutQualifiers();
            FlowSiteId site = graph.flowSiteId(initializer);
            DeclarationId allocation = new DeclarationId(Long.MAX_VALUE - site.ordinal());
            var identity = ArrayIdentity.localAllocation(
                    ModuleId.fromSourceId(initializer.span().sourceId()), allocation, array);
            var witness = io.mindspice.lyra.compiler.semantic.flow.OwnershipWitness.local(
                    identity.ownerModule(), allocation, graph.flowScopeId(initializer), initializer.span())
                    .withOriginSite(site);
            combinations = combinations.stream().map(value -> {
                var facts = new ArrayList<>(value.aggregateIdentities());
                facts.add(new AggregateIdentityFact(identity, ProjectionPath.root(), witness));
                return ValueAlternative.of(initializer.type(), facts, value.callableFlows(),
                        value.nilProvenance(), value.objects());
            }).toList();
        }
        return Optional.of(new ValueAlternatives(combinations));
    }

    /**
     * Producer-certified direct or namespace call.  The proof keeps the exact
     * target declaration, its function contract, the closed transfers of the
     * producer arguments, and the bounded write-target route of each argument.
     */
    private static Optional<RetainedInitializerTransfer.Call> retainedInitializerCall(
            TypedExpression initializer, TypedSemanticInput graph) {
        DeclarationId target = initializer.link()
                .flatMap(io.mindspice.lyra.compiler.semantic.TypedLink::declarationId)
                .orElse(null);
        FunctionType function = target == null ? null : graph.contract(target)
                .map(BindingContract::valueType).map(LyraType::withoutQualifiers)
                .filter(FunctionType.class::isInstance).map(FunctionType.class::cast)
                .orElse(null);
        if (function == null || function.arity() != initializer.children().size()) {
            return Optional.empty();
        }
        List<RetainedInitializerTransfer> arguments = new ArrayList<>();
        List<Optional<WriteTarget>> targets = new ArrayList<>();
        for (TypedExpression argument : initializer.children()) {
            Optional<RetainedInitializerTransfer> transfer =
                    retainedArgumentTransfer(argument, graph);
            if (transfer.isEmpty()) {
                return Optional.empty();
            }
            arguments.add(transfer.orElseThrow());
            targets.add(WriteTarget.of(argument, graph));
        }
        return Optional.of(new RetainedInitializerTransfer.Call(
                target, function, arguments, targets));
    }

    /**
     * Argument transfer for a retained initializer call.  The current closed
     * algebra covers literal values, declaration projections and nested direct
     * calls; a form outside it leaves the enclosing call without a transfer.
     */
    private static Optional<RetainedInitializerTransfer> retainedArgumentTransfer(
            TypedExpression argument, TypedSemanticInput graph) {
        if (argument.kind() == TypedExpressionKind.DIRECT_CALL
                || argument.kind() == TypedExpressionKind.NAMESPACE_DIRECT_CALL) {
            return retainedInitializerCall(argument, graph)
                    .map(RetainedInitializerTransfer.class::cast);
        }
        Optional<RetainedInitializerTransfer.Reference> reference =
                retainedReference(argument, graph);
        if (reference.isPresent()) {
            return Optional.of(reference.orElseThrow());
        }
        return retainedLiteralValue(argument, graph)
                .map(RetainedInitializerTransfer.Value::new);
    }

    /**
     * Certifies the exact aggregate identity and ownership witness of every
     * fresh allocation a summary of this generation can return, write or
     * require.  A later generation reconstructs those facts verbatim from the
     * certified provenance instead of fabricating a type-shaped identity.
     */
    private static void collectSummaryAllocationProofs(
            TypedSemanticGraph graph,
            Map<FreshAllocationSite, AllocationProvenance> allocations,
            Set<AggregateProofKey> aggregateProofs) {
        for (CallableSummary summary : graph.semanticFlowFacts()
                .callableSummaries().orderedSummaries()) {
            if (graph.lambda(summary.lambdaId()).isEmpty()) {
                // Inherited summaries keep the proofs their producer certified.
                continue;
            }
            List<ValueFormula.FreshAllocation> fresh = new ArrayList<>();
            collectAllocationFormulas(summary.returnFormula().alternatives(), fresh);
            for (CapturedCellWrite write : summary.writes()) {
                collectAllocationFormulas(write.value(), fresh);
            }
            for (var requirement : summary.ownershipRequirements()) {
                collectAllocationFormulas(requirement.value(), fresh);
            }
            for (var call : summary.callReferences()) {
                collectAllocationFormulas(call.target(), fresh);
                call.arguments().forEach(argument ->
                        collectAllocationFormulas(argument, fresh));
            }
            for (ValueFormula.FreshAllocation formula : fresh) {
                AllocationProvenance provenance = allocations.get(formula.allocationSite());
                if (provenance == null) {
                    continue;
                }
                if (!provenance.arrayType().equals(formula.arrayType())) {
                    throw new IllegalArgumentException(
                            "summary allocation type differs from its provenance: "
                                    + formula.allocationSite());
                }
                aggregateProofs.add(AggregateProofKey.of(new AggregateIdentityFact(
                        ArrayIdentity.localAllocation(provenance.moduleId(),
                                provenance.allocation(), provenance.arrayType()),
                        formula.resultRoute(),
                        io.mindspice.lyra.compiler.semantic.flow.OwnershipWitness.local(
                                        provenance.moduleId(), provenance.allocation(),
                                        provenance.scopeId(), provenance.sourceSpan())
                                .withOriginSite(provenance.originSite()))));
            }
        }
    }

    /** Collects exact producer source evidence for construction calls in summaries. */
    private static Map<SummaryCallId, RetainedConstruction> collectRetainedConstructions(
            TypedSemanticGraph graph) {
        TreeMap<SummaryCallId, RetainedConstruction> result = new TreeMap<>();
        for (CallableSummary summary : graph.semanticFlowFacts()
                .callableSummaries().orderedSummaries()) {
            for (CallableCallReference call : summary.callReferences()) {
                if (call.kind() != CallableCallReference.Kind.CONSTRUCTION) {
                    continue;
                }
                FlowSiteId site = call.siteId().orElseThrow();
                TypedExpression source = graph.expressions().stream()
                        .filter(expression -> graph.flowSiteId(expression).equals(site))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException(
                                "constructor summary call has no canonical source site: " + call.id()));
                if (source.kind() != TypedExpressionKind.CONSTRUCTION
                        || !source.span().equals(call.span())
                        || !source.declarationId().equals(call.targetDeclaration())) {
                    throw new IllegalArgumentException(
                            "constructor summary call differs from its canonical source: " + call.id());
                }
                RetainedConstruction construction = new RetainedConstruction(
                        call, summary.moduleId(), graph.flowScopeId(source),
                        new DeclarationId(Long.MAX_VALUE - site.ordinal()),
                        source.children().stream().map(argument -> WriteTarget.of(argument, graph))
                                .toList());
                RetainedConstruction previous = result.putIfAbsent(call.id(), construction);
                if (previous != null && !previous.equals(construction)) {
                    throw new IllegalArgumentException(
                            "duplicate constructor summary call identity: " + call.id());
                }
            }
        }
        return result;
    }

    private static void collectAllocationFormulas(
            FormulaAlternatives alternatives,
            List<ValueFormula.FreshAllocation> destination) {
        for (ValueFormula formula : alternatives.formulas()) {
            collectAllocationFormulas(formula, destination);
        }
    }

    private static void collectAllocationFormulas(
            ValueFormula formula,
            List<ValueFormula.FreshAllocation> destination) {
        if (formula instanceof ValueFormula.FreshAllocation fresh) {
            destination.add(fresh);
        } else if (formula instanceof ValueFormula.Lambda lambda) {
            lambda.captures().values().forEach(value ->
                    collectAllocationFormulas(value, destination));
        }
    }

    private static void collectTransferProofs(
            RetainedInitializerTransfer transfer,
            Set<CallableProofKey> callables,
            Set<AggregateProofKey> aggregates,
            Set<ObjectProofKey> objects,
            Set<SourceSpan> aggregateUses) {
        switch (transfer) {
            case RetainedInitializerTransfer.Lambda ignored -> { }
            case RetainedInitializerTransfer.Reference ignored -> { }
            case RetainedInitializerTransfer.Value value ->
                    collectProofs(value.value(), callables, aggregates, objects, aggregateUses);
            case RetainedInitializerTransfer.Call call ->
                    call.arguments().forEach(argument -> collectTransferProofs(
                            argument, callables, aggregates, objects, aggregateUses));
        }
    }

    private static boolean containsNil(
            BindingFlowState state, NilProvenance nil, Set<Object> visited) {
        if (!visited.add(state)) {
            return false;
        }
        if (state.bindings().values().stream().anyMatch(value ->
                containsNil(value.alternatives(), nil, visited))
                || state.sharedCells().values().stream().anyMatch(value ->
                containsNil(value, nil, visited))) {
            return true;
        }
        return state.objects().values().stream().flatMap(object -> object.fields().values().stream())
                .anyMatch(value -> containsNil(value, nil, visited));
    }

    private static boolean containsNil(
            ValueAlternatives values, NilProvenance nil, Set<Object> visited) {
        if (!visited.add(values)) {
            return false;
        }
        for (ValueAlternative alternative : values.alternatives()) {
            if (alternative.nilProvenance().contains(nil)) {
                return true;
            }
            for (CallableFlow callable : alternative.callableFlows()) {
                if (!visited.add(callable)) {
                    continue;
                }
                if (callable.capturedValues().values().stream().anyMatch(value ->
                        containsNil(value, nil, visited))
                        || callable.sharedCellSnapshots().values().stream().anyMatch(value ->
                        containsNil(value, nil, visited))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsNil(
            RetainedInitializerTransfer transfer, NilProvenance nil, Set<Object> visited) {
        return switch (transfer) {
            case RetainedInitializerTransfer.Lambda ignored -> false;
            case RetainedInitializerTransfer.Reference ignored -> false;
            case RetainedInitializerTransfer.Value value ->
                    containsNil(value.value(), nil, visited);
            case RetainedInitializerTransfer.Call call -> call.arguments().stream()
                    .anyMatch(argument -> containsNil(argument, nil, visited));
        };
    }

    private static boolean containsNil(
            FormulaAlternatives alternatives, NilProvenance nil) {
        return alternatives.formulas().stream().anyMatch(formula ->
                formula instanceof ValueFormula.Scalar scalar
                        && scalar.isNil()
                        && scalar.nilSourceSite().filter(nil.sourceSite()::equals).isPresent()
                        && scalar.nilSourceSpan().filter(nil.sourceSpan()::equals).isPresent()
                        && scalar.resultRoute().equals(nil.route()));
    }

    private static void collectProofs(
            ValueAlternatives values,
            Set<CallableProofKey> callables,
            Set<AggregateProofKey> aggregates,
            Set<ObjectProofKey> objects,
            Set<SourceSpan> aggregateUses) {
        values.alternatives().forEach(value -> {
            value.aggregateIdentities().forEach(fact -> {
                aggregates.add(AggregateProofKey.of(fact));
                aggregateUses.add(fact.witness().useSpan());
            });
            value.objects().forEach(fact -> objects.add(ObjectProofKey.of(fact)));
            value.callableFlows().forEach(callable -> {
                if (callables.add(CallableProofKey.of(callable))) {
                    callable.capturedValues().values().forEach(captured ->
                            collectProofs(captured, callables, aggregates, objects, aggregateUses));
                    callable.sharedCellSnapshots().values().forEach(captured ->
                            collectProofs(captured, callables, aggregates, objects, aggregateUses));
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
                    graph.flowSiteId(expression), arrayType,
                    new DeclarationId(Long.MAX_VALUE / 2L
                            - graph.flowSiteId(expression).ordinal())));
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

    private static Map<SummaryCallId, RetainedConstruction> immutableRetainedConstructions(
            Map<SummaryCallId, RetainedConstruction> values) {
        Objects.requireNonNull(values, "retainedConstructions");
        TreeMap<SummaryCallId, RetainedConstruction> ordered = new TreeMap<>();
        values.forEach((id, construction) -> {
            RetainedConstruction value = Objects.requireNonNull(
                    construction, "retained construction");
            if (!Objects.requireNonNull(id, "retained construction id")
                    .equals(value.call().id())) {
                throw new IllegalArgumentException(
                        "retained construction key differs from its call identity");
            }
            ordered.put(id, value);
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
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
            Optional<LambdaId> constructorLambda,
            List<Optional<RetainedInitializerTransfer>> memberInitializers) {
        public RetainedNominal {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(nominal, "nominal");
            Objects.requireNonNull(visibility, "visibility");
            Objects.requireNonNull(constructorLambda, "constructorLambda");
            memberInitializers = List.copyOf(memberInitializers);
            if (memberInitializers.size() != nominal.members().size()) {
                throw new IllegalArgumentException("retained nominal initializer inventory differs");
            }
            for (int index = 0; index < memberInitializers.size(); index++) {
                if (memberInitializers.get(index).isPresent()
                        != nominal.schema().members().get(index).hasInitializer()) {
                    throw new IllegalArgumentException(
                            "retained nominal initializer coverage differs from its schema");
                }
            }
            if (!name.equals(nominal.schema().type().id().name())) {
                throw new IllegalArgumentException("retained nominal name differs from its identity");
            }
            if (!constructorLambda.equals(nominal.constructor())) {
                throw new IllegalArgumentException("retained nominal constructor differs from its definition");
            }
        }

        /** Returns the closed transfer of one member initializer, when it has one. */
        public Optional<RetainedInitializerTransfer> memberInitializer(int index) {
            return memberInitializers.get(index);
        }
    }

    /**
     * Closed, immutable producer-certified transfer for one retained member
     * initializer or for one argument of a retained initializer call.
     *
     * <p>This is a bounded compiler proof algebra, never an executable retained
     * source tree and never a second callable-summary interpreter.  Every
     * variant denotes exactly one producer fact: a certified lambda identity, a
     * closed abstract value, an exact declaration projection, or a certified
     * call whose result is reconstructed through ordinary summary invocation.</p>
     */
    public sealed interface RetainedInitializerTransfer {

        /** A producer-certified lambda literal; its captures are re-resolved on use. */
        record Lambda(LambdaId lambda) implements RetainedInitializerTransfer {
            public Lambda {
                Objects.requireNonNull(lambda, "lambda");
            }
        }

        /** A closed abstract value with no dependency on the consumer's receiver. */
        record Value(ValueAlternatives value) implements RetainedInitializerTransfer {
            public Value {
                Objects.requireNonNull(value, "value");
                if (value.isEmpty()) {
                    throw new IllegalArgumentException("retained initializer value is empty");
                }
            }
        }

        /**
         * An exact producer declaration projection resolved by declaration
         * identity, not by current spelling.  The route carries nominal member
         * slots so the fresh receiver or the current shared cell supplies the
         * transferred value.
         */
        record Reference(DeclarationId declaration, ProjectionPath route)
                implements RetainedInitializerTransfer {
            public Reference {
                Objects.requireNonNull(declaration, "declaration");
                Objects.requireNonNull(route, "route");
            }
        }

        /** A direct or namespace direct call to a producer-certified function. */
        record Call(
                DeclarationId target, FunctionType function,
                List<RetainedInitializerTransfer> arguments,
                List<Optional<WriteTarget>> argumentTargets)
                implements RetainedInitializerTransfer {
            public Call {
                Objects.requireNonNull(target, "target");
                Objects.requireNonNull(function, "function");
                arguments = List.copyOf(arguments);
                argumentTargets = List.copyOf(argumentTargets);
                if (arguments.size() != function.arity()
                        || argumentTargets.size() != function.arity()) {
                    throw new IllegalArgumentException("retained initializer call arity differs");
                }
            }
        }
    }

    /**
     * Exact bounded source evidence for a nominal construction nested in a
     * callable summary.  It is static compiler proof only; runtime construction
     * still uses the authenticated producer-bound factory.
     */
    public record RetainedConstruction(
            CallableCallReference call,
            ModuleId moduleId,
            ScopeId scopeId,
            DeclarationId allocation,
            List<Optional<WriteTarget>> argumentTargets) {
        public RetainedConstruction {
            Objects.requireNonNull(call, "call");
            Objects.requireNonNull(moduleId, "moduleId");
            Objects.requireNonNull(scopeId, "scopeId");
            Objects.requireNonNull(allocation, "allocation");
            argumentTargets = List.copyOf(argumentTargets);
            if (call.kind() != CallableCallReference.Kind.CONSTRUCTION
                    || call.siteId().isEmpty()
                    || call.targetDeclaration().isEmpty()
                    || !(call.target().only() instanceof ValueFormula.Constructor)
                    || argumentTargets.size() != call.arguments().size()
                    || !moduleId.sourceId().equals(call.span().sourceId())) {
                throw new IllegalArgumentException(
                        "retained construction evidence differs from its certified call");
            }
        }

        public FlowSiteId site() {
            return call.siteId().orElseThrow();
        }

        public DeclarationId target() {
            return call.targetDeclaration().orElseThrow();
        }

        public NominalType nominalType() {
            return ((ValueFormula.Constructor) call.target().only()).nominalType();
        }

        private boolean certifies(NominalObjectFact fact) {
            OwnershipWitness witness = fact.ownership();
            return fact.identity().ownerModule().equals(moduleId)
                    && fact.identity().allocationSite().equals(site())
                    && fact.identity().type().equals(nominalType())
                    && witness.ownerModule().equals(moduleId)
                    && witness.originDeclaration().equals(allocation)
                    && witness.scopeId().equals(scopeId)
                    && witness.sourceSpan().equals(call.span())
                    && witness.useSpan().equals(call.span())
                    && witness.originExport().isEmpty()
                    && witness.originSite().equals(Optional.of(site()));
        }
    }

    /** Producer-owned metadata needed to lower a retained callable allocation. */
    public record AllocationProvenance(
            ModuleId moduleId,
            ScopeId scopeId,
            io.mindspice.lyra.compiler.source.SourceSpan sourceSpan,
            FlowSiteId originSite,
            ArrayType arrayType,
            DeclarationId allocation) {
        public AllocationProvenance {
            Objects.requireNonNull(moduleId, "moduleId");
            Objects.requireNonNull(scopeId, "scopeId");
            Objects.requireNonNull(sourceSpan, "sourceSpan");
            Objects.requireNonNull(originSite, "originSite");
            Objects.requireNonNull(arrayType, "arrayType");
            Objects.requireNonNull(allocation, "allocation");
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

    /**
     * Exact object-allocation proof key.  Unlike a heap-state lookup, this
     * records the full ownership witness, so a forged witness for a real
     * identity is not certified.
     */
    private record ObjectProofKey(
            io.mindspice.lyra.compiler.semantic.flow.NominalObjectIdentity identity,
            String originKey) {
        private ObjectProofKey {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(originKey, "originKey");
        }

        private static ObjectProofKey of(NominalObjectFact fact) {
            return new ObjectProofKey(fact.identity(), ownershipKey(fact.ownership()));
        }
    }

    /** Canonical ownership-witness key shared by object and aggregate proofs. */
    private static String ownershipKey(
            io.mindspice.lyra.compiler.semantic.flow.OwnershipWitness witness) {
        return witness.ownerModule() + "/" + witness.originDeclaration()
                + "/" + witness.scopeId() + "/" + witness.sourceSpan()
                + "/" + witness.originExport() + "/" + witness.originSite();
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
            return new AggregateProofKey(fact.identity(), fact.route(),
                    ownershipKey(fact.witness()));
        }

        private boolean sameOrigin(AggregateProofKey other) {
            return identity.equals(other.identity)
                    && route.equals(other.route)
                    && originKey.equals(other.originKey);
        }
    }
}
