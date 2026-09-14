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
import io.mindspice.lyra.compiler.semantic.CallbackLoop;
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
import io.mindspice.lyra.compiler.semantic.flow.RetainedAllocationDerivation;
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
import io.mindspice.lyra.compiler.types.NominalSchema;
import io.mindspice.lyra.compiler.types.NominalType;
import io.mindspice.lyra.compiler.types.PrimitiveType;
import io.mindspice.lyra.compiler.types.RangeType;
import io.mindspice.lyra.compiler.types.TupleType;
import io.mindspice.lyra.compiler.types.TypeQualifier;
import io.mindspice.lyra.compiler.types.TypeRules;

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
    private final Set<RouteProof> routeProofs;
    private final Set<AggregateProofKey> aggregateProofs;
    private final Set<ObjectProofKey> objectProofs;
    private final Set<SourceSpan> aggregateUseSpans;
    private final Set<SourceSpan> objectUseSpans;
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
            Set<RouteProof> routeProofs,
            Set<AggregateProofKey> aggregateProofs,
            Set<ObjectProofKey> objectProofs,
            Set<SourceSpan> aggregateUseSpans,
            Set<SourceSpan> objectUseSpans,
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
        this.routeProofs = Set.copyOf(Objects.requireNonNull(routeProofs, "routeProofs"));
        this.aggregateProofs = Set.copyOf(Objects.requireNonNull(aggregateProofs, "aggregateProofs"));
        this.objectProofs = Set.copyOf(Objects.requireNonNull(objectProofs, "objectProofs"));
        this.aggregateUseSpans = Set.copyOf(
                Objects.requireNonNull(aggregateUseSpans, "aggregateUseSpans"));
        this.objectUseSpans = Set.copyOf(
                Objects.requireNonNull(objectUseSpans, "objectUseSpans"));
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
        return routeProofs.contains(RouteProof.of(callable))
                && callableProofs.contains(CallableProofKey.of(callable));
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
        return routeProofs.contains(RouteProof.of(callable))
                && certifiesCallableTransferIdentity(callable);
    }

    /**
     * Link-resolvability predicate for a foreign callable: its lambda identity
     * and capture contracts are certified, but no route is asserted.  Route
     * exactness is enforced by the semantic flow validator, so IR link
     * validation uses this narrower question rather than accepting a route it
     * cannot resolve.
     */
    public boolean certifiesLinkedCallable(CallableFlow callable) {
        Objects.requireNonNull(callable, "callable");
        return certifiesCallableTransferIdentity(callable);
    }

    private boolean certifiesCallableTransferIdentity(CallableFlow callable) {
        CallableProofKey key = CallableProofKey.of(callable);
        if (callableProofs.stream().anyMatch(proof -> proof.atRoute(callable.route()).equals(key))) return true;
        if (callable.isIntrinsic()) {
            return false;
        }
        CallableSummary summary = callable.lambda()
                .flatMap(callableSummaries::summary).orElse(null);
        return summary != null && captureContractsMatch(
                summary, callable.capturedValues(), callable.sharedCellSnapshots());
    }

    /**
     * True when the producer proof contains an exact retained evidence chain
     * to this lambda. Besides boundary closures and literal transfer nodes, a
     * reachable callable summary may expose a nested callable in its solved
     * formulas or through one of its exact certified call targets.
     */
    public boolean certifiesLambda(LambdaId lambda) {
        Objects.requireNonNull(lambda, "lambda");
        if (directlyCertifiesLambda(lambda)) {
            return true;
        }
        TreeSet<LambdaId> visited = new TreeSet<>();
        java.util.ArrayDeque<LambdaId> pending = new java.util.ArrayDeque<>();
        callableSummaries.orderedSummaries().stream()
                .map(CallableSummary::lambdaId)
                .filter(this::directlyCertifiesLambda)
                .forEach(pending::addLast);
        while (!pending.isEmpty()) {
            LambdaId current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            CallableSummary summary = callableSummaries.summary(current).orElse(null);
            if (summary == null) {
                continue;
            }
            if (summaryCertifiesLambda(summary, lambda)) {
                return true;
            }
            for (CallableCallReference call : summary.callReferences()) {
                for (LambdaId target : retainedCallTargets(call)) {
                    if (target.equals(lambda)) {
                        return true;
                    }
                    if (!visited.contains(target)) {
                        pending.addLast(target);
                    }
                }
            }
        }
        return false;
    }

    private boolean directlyCertifiesLambda(LambdaId lambda) {
        return callableProofs.stream().anyMatch(value ->
                value.lambda().filter(lambda::equals).isPresent())
                || retainedNominals.values().stream().anyMatch(nominal ->
                nominal.constructorLambda().filter(lambda::equals).isPresent()
                        || nominal.memberInitializers().stream()
                        .flatMap(Optional::stream)
                        .anyMatch(initializer -> certifiesLambda(initializer, lambda)));
    }

    private static boolean summaryCertifiesLambda(
            CallableSummary summary, LambdaId lambda) {
        if (containsLambda(summary.returnFormula().alternatives(), lambda)
                || summary.writes().stream().anyMatch(write ->
                containsLambda(write.value(), lambda))
                || summary.ownershipRequirements().stream().anyMatch(requirement ->
                containsLambda(requirement.value(), lambda))) {
            return true;
        }
        return summary.callReferences().stream().anyMatch(call ->
                containsLambda(call.target(), lambda)
                        || call.arguments().stream().anyMatch(argument ->
                        containsLambda(argument, lambda)));
    }

    private static boolean containsLambda(
            FormulaAlternatives alternatives, LambdaId lambda) {
        return alternatives.formulas().stream().anyMatch(formula ->
                containsLambda(formula, lambda));
    }

    private static boolean containsLambda(ValueFormula formula, LambdaId lambda) {
        return formula instanceof ValueFormula.Lambda value
                && (value.lambdaId().equals(lambda)
                || value.capturedValues().values().stream().anyMatch(capture ->
                containsLambda(capture, lambda)));
    }

    private static boolean certifiesLambda(
            RetainedInitializerTransfer initializer, LambdaId lambda) {
        return switch (initializer) {
            case RetainedInitializerTransfer.Lambda value -> value.lambda().equals(lambda);
            case RetainedInitializerTransfer.Reference ignored -> false;
            case RetainedInitializerTransfer.Value ignored -> false;
            case RetainedInitializerTransfer.Call call -> call.arguments().stream()
                    .anyMatch(argument -> certifiesLambda(argument, lambda));
            case RetainedInitializerTransfer.Composite composite -> composite.elements().stream()
                    .anyMatch(element -> certifiesLambda(element, lambda));
            case RetainedInitializerTransfer.Apply apply -> apply.operands().stream()
                    .anyMatch(operand -> certifiesLambda(operand, lambda));
            case RetainedInitializerTransfer.Alternative alternative -> alternative.prefix().stream()
                    .anyMatch(step -> certifiesLambda(step.transfer(), lambda))
                    || alternative.branches().stream().anyMatch(branch ->
                    branch.selectors().stream().anyMatch(step -> certifiesLambda(step.transfer(), lambda))
                            || branch.result().stream().anyMatch(step -> certifiesLambda(step.transfer(), lambda)));
            case RetainedInitializerTransfer.Sequence sequence -> sequence.steps().stream()
                    .anyMatch(step -> certifiesLambda(step, lambda));
            case RetainedInitializerTransfer.Declare declare ->
                    certifiesLambda(declare.initializer(), lambda);
            case RetainedInitializerTransfer.Rebind rebind -> certifiesLambda(rebind.value(), lambda);
            case RetainedInitializerTransfer.Project project -> certifiesLambda(project.base(), lambda)
                    || project.index().stream().anyMatch(value -> certifiesLambda(value, lambda));
            case RetainedInitializerTransfer.Construct construct -> construct.arguments().stream()
                    .anyMatch(argument -> certifiesLambda(argument, lambda));
            case RetainedInitializerTransfer.CallableCall call -> certifiesLambda(call.target(), lambda)
                    || call.arguments().stream().anyMatch(argument -> certifiesLambda(argument, lambda));
            case RetainedInitializerTransfer.Loop loop -> certifiesLambda(loop.input(), lambda)
                    || certifiesLambda(loop.action(), lambda);
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

    /** Finds one exact retained producer aggregate occurrence by identity and route. */
    public Optional<AggregateIdentityFact> matchingAggregate(
            DeclarationId origin, ArrayType type, ProjectionPath route) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(route, "route");
        return java.util.stream.Stream.concat(
                        boundaryState.bindings().values().stream()
                                .flatMap(binding -> binding.alternatives().alternatives().stream()),
                        java.util.stream.Stream.concat(
                                boundaryState.sharedCells().values().stream()
                                        .flatMap(values -> values.alternatives().stream()),
                                boundaryState.objects().values().stream()
                                        .flatMap(object -> object.fields().values().stream())
                                        .flatMap(values -> values.alternatives().stream())))
                .flatMap(value -> value.aggregateIdentities().stream())
                .filter(fact -> fact.identity().originDeclaration().equals(origin)
                        && fact.identity().arrayType().equals(type)
                        && fact.route().equals(route))
                .findFirst();
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
                construction.certifies(fact, routeProofs));
    }

    /** True only for an object occurrence published at an exact certified use span. */
    public boolean certifiesObjectUse(NominalObjectFact fact) {
        Objects.requireNonNull(fact, "fact");
        SourceSpan use = fact.ownership().useSpan();
        return use.equals(fact.ownership().sourceSpan()) || objectUseSpans.contains(use);
    }

    /**
     * Validates an exact consumer-scoped aggregate derivation. The supplied
     * context is a current graph-owned construction/call site; this proof then
     * walks only its closed retained transfer inventory and exact producer
     * allocation records. No unmatched allocation token is accepted.
     */
    public boolean certifiesDerivedAggregate(
            AggregateIdentityFact fact, FlowSiteId consumerContext, SourceSpan consumerSpan) {
        return false;
    }

    /**
     * Exact consumer-expression form used by the semantic fact sealer. Calls
     * supply the concrete producer lambda alternatives recorded for this exact
     * graph site; constructions bind directly to their retained nominal.
     */
    public boolean certifiesDerivedAggregate(
            AggregateIdentityFact fact, FlowSiteId consumerContext,
            TypedExpression consumer, Set<LambdaId> callableTargets) {
        return certifiesDerivedAggregate(fact, consumerContext, consumer,
                callableTargets, CallableSummarySet.empty());
    }

    /**
     * Exact form for a current graph wrapper whose sealed local summary reaches
     * predecessor evidence. Supplemental summaries authorize no producer fact;
     * they only establish the exact path from the current consumer site.
     */
    public boolean certifiesDerivedAggregate(
            AggregateIdentityFact fact, FlowSiteId consumerContext,
            TypedExpression consumer, Set<LambdaId> callableTargets,
            CallableSummarySet currentSummaries) {
        Objects.requireNonNull(consumer, "consumer");
        currentSummaries = Objects.requireNonNull(currentSummaries, "currentSummaries");
        callableTargets = Set.copyOf(Objects.requireNonNull(callableTargets, "callableTargets"));
        if (!fact.witness().useSpan().equals(consumer.span())) return false;
        if (consumer.kind() == TypedExpressionKind.CONSTRUCTION) {
            if (!(consumer.type().withoutQualifiers() instanceof NominalType nominalType)
                    || consumer.declarationId().isEmpty()) return false;
            RetainedNominal nominal = retainedNominals.get(nominalType.canonicalSpelling());
            if (nominal == null || !nominal.nominal().declaration().equals(
                    consumer.declarationId().orElseThrow())) return false;
            boolean memberDerived = nominal.memberInitializers().stream().flatMap(Optional::stream)
                    .anyMatch(transfer -> matchesDerivedAggregate(
                            transfer, consumerContext, ProjectionPath.root(), fact));
            if (memberDerived || nominal.constructorLambda().isEmpty()) return memberDerived;
            return matchesSummaryDerivedAggregate(fact, consumerContext, ProjectionPath.root(),
                    nominal.constructorLambda().orElseThrow(), currentSummaries);
        }
        if (!isCallableConsumer(consumer.kind()) || callableTargets.isEmpty()) return false;
        CallableSummarySet localEvidence = currentSummaries;
        return callableTargets.stream().allMatch(lambda -> certifiesLambda(lambda)
                        || localEvidence.summary(lambda).isPresent())
                && callableTargets.stream().anyMatch(lambda -> matchesSummaryDerivedAggregate(
                        fact, consumerContext, ProjectionPath.root(), lambda,
                        localEvidence));
    }

    /**
     * Exact current-call form retaining the selected callable alternatives and
     * their creation-time captures. This is required when a local wrapper
     * reaches predecessor evidence through a parameter or capture placeholder.
     */
    public boolean certifiesDerivedAggregateFromCallables(
            AggregateIdentityFact fact, FlowSiteId consumerContext,
            TypedExpression consumer, Set<CallableFlow> callableTargets,
            CallableSummarySet currentSummaries,
            Map<DeclarationId, ValueAlternatives> currentValues) {
        Objects.requireNonNull(fact, "fact");
        Objects.requireNonNull(consumerContext, "consumerContext");
        Objects.requireNonNull(consumer, "consumer");
        callableTargets = Set.copyOf(Objects.requireNonNull(
                callableTargets, "callableTargets"));
        currentSummaries = Objects.requireNonNull(currentSummaries, "currentSummaries");
        currentValues = Map.copyOf(Objects.requireNonNull(currentValues, "currentValues"));
        if (!fact.witness().useSpan().equals(consumer.span())
                || !isCallableConsumer(consumer.kind())
                || callableTargets.isEmpty()) return false;
        CallableSummarySet localEvidence = currentSummaries;
        Map<DeclarationId, ValueAlternatives> declarationEvidence = currentValues;
        List<CallableEvidence> targets = callableTargets.stream()
                .filter(callable -> callable.route().isRoot())
                .map(callable -> callableEvidence(
                        callable, localEvidence, declarationEvidence))
                .flatMap(Optional::stream).toList();
        if (targets.size() != callableTargets.size()) return false;
        return targets.stream().allMatch(target -> certifiesLambda(target.lambda())
                        || localEvidence.summary(target.lambda()).isPresent())
                && targets.stream().anyMatch(target -> matchesCallableDerivedAggregate(
                        fact, consumerContext, ProjectionPath.root(), target,
                        localEvidence, declarationEvidence, new LinkedHashSet<>()));
    }

    /** Exact construction-target compatibility form. */
    public boolean certifiesDerivedAggregate(
            AggregateIdentityFact fact, FlowSiteId consumerContext, SourceSpan consumerSpan,
            Optional<NominalType> constructedType) {
        Objects.requireNonNull(fact, "fact");
        Objects.requireNonNull(consumerContext, "consumerContext");
        Objects.requireNonNull(consumerSpan, "consumerSpan");
        constructedType = Objects.requireNonNull(constructedType, "constructedType");
        if (!fact.witness().useSpan().equals(consumerSpan)
                || constructedType.isEmpty()) return false;
        Iterable<RetainedNominal> candidates = Optional.ofNullable(retainedNominals.get(
                constructedType.orElseThrow().canonicalSpelling())).stream().toList();
        for (RetainedNominal nominal : candidates) {
            for (RetainedInitializerTransfer transfer : nominal.memberInitializers()
                    .stream().flatMap(Optional::stream).toList()) {
                if (matchesDerivedAggregate(transfer, consumerContext,
                        ProjectionPath.root(), fact)) return true;
            }
        }
        return false;
    }

    private boolean matchesDerivedAggregate(
            RetainedInitializerTransfer transfer, FlowSiteId context,
            ProjectionPath prefix, AggregateIdentityFact fact) {
        return switch (transfer) {
            case RetainedInitializerTransfer.Lambda ignored -> false;
            case RetainedInitializerTransfer.Reference ignored -> false;
            case RetainedInitializerTransfer.Value ignored -> false;
            case RetainedInitializerTransfer.Composite composite -> {
                boolean matched = composite.allocation().stream().anyMatch(allocation ->
                        matchesDerivedAggregate(fact, context, allocation, prefix));
                for (int index = 0; !matched && index < composite.elements().size(); index++) {
                    ProjectionPath member = composite.arrayLiteral()
                            ? ProjectionPath.arrayElement(index) : ProjectionPath.tupleMember(index);
                    matched = matchesDerivedAggregate(composite.elements().get(index), context,
                            prefix.compose(member), fact);
                }
                yield matched;
            }
            case RetainedInitializerTransfer.Call call -> {
                Set<LambdaId> targets = retainedTargetLambdas(
                        call.target(), call.function());
                boolean matched = matchesDerivedAggregateArguments(
                        fact, context, prefix, call.arguments(), targets);
                FlowSiteId invocation = RetainedAllocationDerivation.invocationContext(context, call.site());
                yield matched || targets.stream().anyMatch(lambda ->
                        matchesSummaryDerivedAggregate(
                                fact, invocation, prefix, lambda));
            }
            case RetainedInitializerTransfer.CallableCall call -> {
                Set<LambdaId> targets = retainedTargetLambdas(call.target());
                boolean matched = matchesDerivedAggregateArguments(
                        fact, context, prefix, call.arguments(), targets);
                FlowSiteId invocation = RetainedAllocationDerivation.invocationContext(context, call.site());
                yield matched || targets.stream().anyMatch(lambda ->
                        matchesSummaryDerivedAggregate(
                                fact, invocation, prefix, lambda));
            }
            case RetainedInitializerTransfer.Apply apply ->
                    (apply.kind() == RetainedInitializerTransfer.ApplyKind.CONVERSION
                            || apply.kind() == RetainedInitializerTransfer.ApplyKind.NARROWING)
                            && matchesDerivedAggregate(
                            apply.operands().getLast(), context, prefix, fact);
            case RetainedInitializerTransfer.Alternative alternative ->
                    (alternative.kind() == RetainedInitializerTransfer.AlternativeKind.COALESCE
                            && alternative.prefix().stream().anyMatch(step -> matchesDerivedAggregate(
                            step.transfer(), context, prefix, fact)))
                            || java.util.stream.IntStream.range(0, alternative.branches().size())
                            .filter(alternative.reachableBranches()::contains)
                            .mapToObj(alternative.branches()::get)
                            .anyMatch(branch -> branch.result().stream().anyMatch(step ->
                                    matchesDerivedAggregate(step.transfer(), context, prefix, fact)));
            case RetainedInitializerTransfer.Sequence sequence -> matchesDerivedAggregateSequence(
                    sequence, context, prefix, fact, Map.of(), new LinkedHashSet<>());
            case RetainedInitializerTransfer.Declare ignored -> false;
            case RetainedInitializerTransfer.Rebind rebind -> matchesDerivedAggregate(
                    rebind.value(), context, prefix, fact);
            case RetainedInitializerTransfer.Project project -> {
                AggregateIdentityFact sourceFact = project.kind() == RetainedInitializerTransfer.ProjectionKind.ROUTE
                        ? new AggregateIdentityFact(fact.identity(), project.route(), fact.witness()) : fact;
                yield matchesDerivedAggregate(project.base(), context,
                        project.kind() == RetainedInitializerTransfer.ProjectionKind.ROUTE
                                ? ProjectionPath.root() : prefix, sourceFact);
            }
            case RetainedInitializerTransfer.Construct construct -> {
                FlowSiteId nested = RetainedAllocationDerivation.objectSite(
                        context, construct.site().site());
                RetainedNominal nominal = retainedNominals.get(
                        construct.site().nominalType().canonicalSpelling());
                boolean matched = construct.site().schema().kind() == NominalSchema.Kind.STRUCT
                        && construct.arguments().stream().anyMatch(argument ->
                        matchesDerivedAggregate(argument, context, ProjectionPath.root(), fact));
                if (!matched && nominal != null && nominal.constructorLambda().isPresent()) {
                    matched = matchesDerivedAggregateArguments(
                            fact, context, ProjectionPath.root(), construct.arguments(),
                            Set.of(nominal.constructorLambda().orElseThrow()));
                }
                if (!matched && nominal != null) {
                    matched = nominal.memberInitializers().stream().flatMap(Optional::stream)
                            .anyMatch(initializer -> matchesDerivedAggregate(
                                    initializer, nested, ProjectionPath.root(), fact));
                    if (!matched && nominal.constructorLambda().isPresent()) {
                        matched = matchesSummaryDerivedAggregate(
                                fact, nested, ProjectionPath.root(),
                                nominal.constructorLambda().orElseThrow());
                    }
                }
                yield matched;
            }
            case RetainedInitializerTransfer.Loop loop -> {
                FlowSiteId invocation = RetainedAllocationDerivation.invocationContext(context, loop.site());
                yield matchesDerivedAggregate(loop.input(), context, prefix, fact)
                        || matchesDerivedAggregate(loop.action(), context, prefix, fact)
                        || java.util.stream.Stream.concat(
                                retainedTargetLambdas(loop.input()).stream(),
                                retainedTargetLambdas(loop.action()).stream())
                        .anyMatch(lambda -> matchesSummaryDerivedAggregate(
                                fact, invocation, prefix, lambda));
            }
        };
    }

    private boolean matchesDerivedAggregateArguments(
            AggregateIdentityFact fact, FlowSiteId context,
            ProjectionPath prefix, List<RetainedInitializerTransfer> arguments,
            Set<LambdaId> targets) {
        Optional<ProjectionPath> destination = prefix.suffixOf(fact.route());
        if (destination.isEmpty()) return false;
        for (LambdaId target : targets) {
            CallableSummary summary = callableSummaries.summary(target).orElse(null);
            if (summary == null) continue;
            List<FormulaAlternatives> destinations = new ArrayList<>();
            destinations.add(summary.returnFormula().alternatives());
            summary.writes().forEach(write -> destinations.add(write.value()));
            for (FormulaAlternatives formulas : destinations) {
                FormulaAlternatives selected;
                try {
                    selected = formulas.select(destination.orElseThrow());
                } catch (IllegalArgumentException invalidRoute) {
                    continue;
                }
                for (ValueFormula formula : selected.formulas()) {
                    if (!(formula instanceof ValueFormula.Parameter parameter)
                            || parameter.parameterIndex() >= arguments.size()) continue;
                    AggregateIdentityFact sourceFact = new AggregateIdentityFact(
                            fact.identity(), parameter.parameterRoute(), fact.witness());
                    if (matchesDerivedAggregate(
                            arguments.get(parameter.parameterIndex()), context,
                            ProjectionPath.root(), sourceFact)) return true;
                }
            }
        }
        return false;
    }

    private boolean matchesDerivedObjectArguments(
            NominalObjectFact fact, FlowSiteId context,
            ProjectionPath prefix, List<RetainedInitializerTransfer> arguments,
            Set<LambdaId> targets) {
        Optional<ProjectionPath> destination = prefix.suffixOf(fact.route());
        if (destination.isEmpty()) return false;
        for (LambdaId target : targets) {
            CallableSummary summary = callableSummaries.summary(target).orElse(null);
            if (summary == null) continue;
            List<FormulaAlternatives> destinations = new ArrayList<>();
            destinations.add(summary.returnFormula().alternatives());
            summary.writes().forEach(write -> destinations.add(write.value()));
            for (FormulaAlternatives formulas : destinations) {
                FormulaAlternatives selected;
                try {
                    selected = formulas.select(destination.orElseThrow());
                } catch (IllegalArgumentException invalidRoute) {
                    continue;
                }
                for (ValueFormula formula : selected.formulas()) {
                    if (!(formula instanceof ValueFormula.Parameter parameter)
                            || parameter.parameterIndex() >= arguments.size()) continue;
                    NominalObjectFact sourceFact = new NominalObjectFact(
                            fact.identity(), parameter.parameterRoute(), fact.ownership());
                    if (matchesDerivedObject(
                            arguments.get(parameter.parameterIndex()), context,
                            ProjectionPath.root(), sourceFact)) return true;
                }
            }
        }
        return false;
    }

    private boolean matchesDerivedAggregateSequence(
            RetainedInitializerTransfer.Sequence sequence, FlowSiteId context,
            ProjectionPath prefix, AggregateIdentityFact fact,
            Map<DeclarationId, RetainedInitializerTransfer> inheritedBindings,
            Set<DeclarationId> activeBindings) {
        TreeMap<DeclarationId, RetainedInitializerTransfer> bindings =
                new TreeMap<>(inheritedBindings);
        for (int index = 0; index < sequence.steps().size(); index++) {
            RetainedInitializerTransfer step = sequence.steps().get(index);
            if (index == sequence.steps().size() - 1) {
                return matchesDerivedAggregateResult(
                        step, context, prefix, fact, bindings, activeBindings);
            }
            if (step instanceof RetainedInitializerTransfer.Declare declare) {
                bindings.put(declare.declaration(), declare.initializer());
            } else if (step instanceof RetainedInitializerTransfer.Rebind rebind) {
                // A rebind into an aggregate/object slot is a genuine state
                // write and certifies its value; a rebind of a root binding is
                // only local flow that may be overwritten before the result,
                // so it updates the binding map and lets the final result walk
                // decide which stored value is actually reachable.
                if (!rebind.target().route().isRoot()
                        && matchesDerivedAggregateResult(
                        rebind.value(), context, prefix, fact, bindings, activeBindings)) {
                    return true;
                }
                if (rebind.target().route().isRoot()) {
                    bindings.put(rebind.target().declaration(), rebind.value());
                }
            }
        }
        return false;
    }

    private boolean matchesDerivedAggregateResult(
            RetainedInitializerTransfer transfer, FlowSiteId context,
            ProjectionPath prefix, AggregateIdentityFact fact,
            Map<DeclarationId, RetainedInitializerTransfer> bindings,
            Set<DeclarationId> activeBindings) {
        if (transfer instanceof RetainedInitializerTransfer.Sequence sequence) {
            return matchesDerivedAggregateSequence(
                    sequence, context, prefix, fact, bindings, activeBindings);
        }
        if (transfer instanceof RetainedInitializerTransfer.Reference reference) {
            if (!activeBindings.add(reference.declaration())) return false;
            try {
                RetainedInitializerTransfer source = bindings.get(reference.declaration());
                if (source == null) return false;
                AggregateIdentityFact sourceFact = reference.route().isRoot() ? fact
                        : new AggregateIdentityFact(
                        fact.identity(), reference.route(), fact.witness());
                return matchesDerivedAggregateResult(source, context,
                        reference.route().isRoot() ? prefix : ProjectionPath.root(),
                        sourceFact, bindings, activeBindings);
            } finally {
                activeBindings.remove(reference.declaration());
            }
        }
        if (transfer instanceof RetainedInitializerTransfer.Composite composite) {
            boolean matched = composite.allocation().stream().anyMatch(allocation ->
                    matchesDerivedAggregate(fact, context, allocation, prefix));
            for (int index = 0; !matched && index < composite.elements().size(); index++) {
                ProjectionPath member = composite.arrayLiteral()
                        ? ProjectionPath.arrayElement(index) : ProjectionPath.tupleMember(index);
                matched = matchesDerivedAggregateResult(
                        composite.elements().get(index), context,
                        prefix.compose(member), fact, bindings, activeBindings);
            }
            return matched;
        }
        if (transfer instanceof RetainedInitializerTransfer.Alternative alternative) {
            boolean matched = alternative.kind()
                    == RetainedInitializerTransfer.AlternativeKind.COALESCE
                    && alternative.prefix().stream().anyMatch(step ->
                    matchesDerivedAggregateResult(
                            step.transfer(), context, prefix, fact, bindings, activeBindings));
            if (matched) return true;
            return java.util.stream.IntStream.range(0, alternative.branches().size())
                    .filter(alternative.reachableBranches()::contains)
                    .mapToObj(alternative.branches()::get)
                    .anyMatch(branch -> branch.result().stream().anyMatch(step ->
                            matchesDerivedAggregateResult(
                                    step.transfer(), context, prefix, fact,
                                    bindings, activeBindings)));
        }
        if (transfer instanceof RetainedInitializerTransfer.Project project) {
            AggregateIdentityFact sourceFact = project.kind()
                    == RetainedInitializerTransfer.ProjectionKind.ROUTE
                    ? new AggregateIdentityFact(
                    fact.identity(), project.route(), fact.witness()) : fact;
            return matchesDerivedAggregateResult(project.base(), context,
                    project.kind() == RetainedInitializerTransfer.ProjectionKind.ROUTE
                            ? ProjectionPath.root() : prefix,
                    sourceFact, bindings, activeBindings);
        }
        if (transfer instanceof RetainedInitializerTransfer.Apply apply) {
            // Conversions and narrowings pass their operand through; the
            // operand itself may be a sequence-local reference whose identity
            // only the enclosing sequence bindings can resolve.
            if (apply.kind() != RetainedInitializerTransfer.ApplyKind.CONVERSION
                    && apply.kind() != RetainedInitializerTransfer.ApplyKind.NARROWING) {
                return false;
            }
            return matchesDerivedAggregateResult(
                    apply.operands().getLast(), context, prefix, fact,
                    bindings, activeBindings);
        }
        return matchesDerivedAggregate(transfer, context, prefix, fact);
    }

    private boolean matchesCallableDerivedAggregate(
            AggregateIdentityFact fact, FlowSiteId context,
            ProjectionPath prefix, CallableEvidence evidence,
            CallableSummarySet currentSummaries,
            Map<DeclarationId, ValueAlternatives> currentValues,
            Set<CallableEvidenceVisit> active) {
        CallableEvidenceVisit visit = new CallableEvidenceVisit(
                evidence.lambda(), context);
        if (!active.add(visit)) return false;
        try {
            if (matchesSummaryDerivedAggregateAllocations(
                    fact, context, prefix, evidence.lambda(), currentSummaries)) {
                return true;
            }
            CallableSummary summary = evidenceSummary(
                    evidence.lambda(), currentSummaries);
            if (summary == null) return false;
            for (CallableCallReference call : summary.callReferences()) {
                if (call.siteId().isEmpty()) continue;
                FlowSiteId invocation = RetainedAllocationDerivation.invocationContext(
                        context, call.id(), call.siteId().orElseThrow());
                if (call.kind() == CallableCallReference.Kind.CONSTRUCTION) {
                    RetainedConstruction construction = retainedConstructions.get(call.id());
                    if (construction == null) continue;
                    FlowSiteId objectContext = RetainedAllocationDerivation.objectSite(
                            invocation, construction.site());
                    RetainedNominal nominal = retainedNominals.get(
                            construction.nominalType().canonicalSpelling());
                    if (nominal != null) {
                        if (nominal.memberInitializers().stream()
                                .flatMap(Optional::stream).anyMatch(initializer ->
                                        matchesDerivedAggregate(initializer, objectContext,
                                                ProjectionPath.root(), fact))) return true;
                        if (nominal.constructorLambda().isPresent()
                                && matchesSummaryDerivedAggregate(
                                fact, objectContext, ProjectionPath.root(),
                                nominal.constructorLambda().orElseThrow(),
                                currentSummaries)) return true;
                    }
                    continue;
                }
                List<Set<CallableEvidence>> arguments = new ArrayList<>();
                boolean complete = true;
                for (FormulaAlternatives argument : call.arguments()) {
                    Set<CallableEvidence> resolved = resolveCallableEvidence(
                            argument, evidence, currentSummaries, currentValues);
                    arguments.add(resolved);
                    if (argument.rootType().withoutQualifiers() instanceof FunctionType
                            && resolved.isEmpty()) complete = false;
                }
                if (!complete) continue;
                Set<CallableEvidence> targets = resolveCallableEvidence(
                        call.target(), evidence, currentSummaries, currentValues);
                for (CallableEvidence target : targets) {
                    CallableEvidence invoked = new CallableEvidence(
                            target.lambda(), arguments, target.captures());
                    if (matchesCallableDerivedAggregate(
                            fact, invocation, prefix, invoked, currentSummaries,
                            currentValues, active)) return true;
                }
            }
            return false;
        } finally {
            active.remove(visit);
        }
    }

    private Optional<CallableEvidence> callableEvidence(
            CallableFlow callable, CallableSummarySet currentSummaries,
            Map<DeclarationId, ValueAlternatives> currentValues) {
        if (callable.lambdaId().isEmpty()) return Optional.empty();
        LambdaId lambda = callable.lambdaId().orElseThrow();
        CallableSummary summary = evidenceSummary(lambda, currentSummaries);
        if (summary == null) return Optional.empty();
        TreeMap<io.mindspice.lyra.compiler.identity.CaptureId, Set<CallableEvidence>> captures =
                new TreeMap<>();
        for (CallableSummary.CapturePlaceholder placeholder : summary.captures()) {
            ValueAlternatives values = placeholder.isSharedCell()
                    ? callable.sharedCellSnapshots().get(placeholder.cellId().orElseThrow())
                    : callable.capturedValues().get(placeholder.declarationId());
            if (values == null) return Optional.empty();
            Set<CallableEvidence> resolved = callableEvidence(
                    values, currentSummaries, currentValues);
            if (placeholder.type().withoutQualifiers() instanceof FunctionType
                    && resolved.isEmpty()) return Optional.empty();
            captures.put(placeholder.captureId(), resolved);
        }
        return Optional.of(new CallableEvidence(lambda, List.of(), captures));
    }

    private Set<CallableEvidence> callableEvidence(
            ValueAlternatives values, CallableSummarySet currentSummaries,
            Map<DeclarationId, ValueAlternatives> currentValues) {
        LinkedHashSet<CallableEvidence> result = new LinkedHashSet<>();
        for (ValueAlternative alternative : values.alternatives()) {
            for (CallableFlow callable : alternative.callableFlows()) {
                if (!callable.route().isRoot()) continue;
                callableEvidence(callable, currentSummaries, currentValues)
                        .ifPresent(result::add);
            }
        }
        return Set.copyOf(result);
    }

    private Set<CallableEvidence> resolveCallableEvidence(
            FormulaAlternatives formulas, CallableEvidence environment,
            CallableSummarySet currentSummaries,
            Map<DeclarationId, ValueAlternatives> currentValues) {
        LinkedHashSet<CallableEvidence> result = new LinkedHashSet<>();
        for (ValueFormula formula : formulas.formulas()) {
            if (formula instanceof ValueFormula.Lambda lambda) {
                TreeMap<io.mindspice.lyra.compiler.identity.CaptureId, Set<CallableEvidence>> captures =
                        new TreeMap<>();
                boolean complete = true;
                for (var entry : lambda.captures().entrySet()) {
                    Set<CallableEvidence> resolved = resolveCallableEvidence(
                            entry.getValue(), environment, currentSummaries, currentValues);
                    if (entry.getValue().rootType().withoutQualifiers() instanceof FunctionType
                            && resolved.isEmpty()) complete = false;
                    captures.put(entry.getKey(), resolved);
                }
                if (complete) result.add(new CallableEvidence(
                        lambda.lambdaId(), List.of(), captures));
            } else if (formula instanceof ValueFormula.Declaration declaration) {
                ValueAlternatives values = currentValues.get(declaration.declarationId());
                if (values == null) values = boundaryState.sharedCell(
                        declaration.declarationId()).or(() -> boundaryState.binding(
                        declaration.declarationId()).map(BindingFlowValue::alternatives))
                        .orElse(null);
                if (values != null) {
                    try {
                        result.addAll(callableEvidence(
                                values.select(declaration.declarationRoute()),
                                currentSummaries, currentValues));
                    } catch (IllegalArgumentException invalidRoute) {
                        return Set.of();
                    }
                }
            } else if (formula instanceof ValueFormula.Parameter parameter) {
                if (!parameter.parameterRoute().isRoot()
                        || parameter.parameterIndex() >= environment.parameters().size()) {
                    return Set.of();
                }
                result.addAll(environment.parameters().get(parameter.parameterIndex()));
            } else if (formula instanceof ValueFormula.Capture capture) {
                if (!capture.captureRoute().isRoot()) return Set.of();
                result.addAll(environment.captures().getOrDefault(
                        capture.captureId(), Set.of()));
            }
        }
        return Set.copyOf(result);
    }

    private record CallableEvidence(
            LambdaId lambda,
            List<Set<CallableEvidence>> parameters,
            Map<io.mindspice.lyra.compiler.identity.CaptureId, Set<CallableEvidence>> captures) {
        private CallableEvidence {
            Objects.requireNonNull(lambda, "lambda");
            parameters = List.copyOf(parameters);
            TreeMap<io.mindspice.lyra.compiler.identity.CaptureId, Set<CallableEvidence>> copy =
                    new TreeMap<>();
            captures.forEach((capture, values) -> copy.put(capture, Set.copyOf(values)));
            captures = Collections.unmodifiableMap(copy);
        }
    }

    private record CallableEvidenceVisit(LambdaId lambda, FlowSiteId context) {
        private CallableEvidenceVisit {
            Objects.requireNonNull(lambda, "lambda");
            Objects.requireNonNull(context, "context");
        }
    }

    private boolean matchesSummaryDerivedAggregate(
            AggregateIdentityFact fact, FlowSiteId context,
            ProjectionPath prefix, LambdaId rootLambda) {
        return matchesSummaryDerivedAggregate(
                fact, context, prefix, rootLambda, CallableSummarySet.empty());
    }

    private boolean matchesSummaryDerivedAggregate(
            AggregateIdentityFact fact, FlowSiteId context,
            ProjectionPath prefix, LambdaId rootLambda,
            CallableSummarySet currentSummaries) {
        if (matchesSummaryDerivedAggregateAllocations(
                fact, context, prefix, rootLambda, currentSummaries)) return true;
        return matchesSummaryDerivedAggregateInConstructions(
                fact, context, prefix, rootLambda, new LinkedHashSet<>(),
                currentSummaries);
    }

    private boolean matchesSummaryDerivedAggregateInConstructions(
            AggregateIdentityFact fact, FlowSiteId context, ProjectionPath prefix,
            LambdaId lambda, Set<LambdaId> active,
            CallableSummarySet currentSummaries) {
        if (!active.add(lambda)) return false;
        CallableSummary summary = evidenceSummary(lambda, currentSummaries);
        if (summary == null) return false;
        try {
            for (CallableCallReference call : summary.callReferences()) {
                if (call.siteId().isEmpty()) continue;
                FlowSiteId invocation = RetainedAllocationDerivation.invocationContext(
                        context, call.id(), call.siteId().orElseThrow());
                if (call.kind() == CallableCallReference.Kind.CONSTRUCTION) {
                    RetainedConstruction construction = retainedConstructions.get(call.id());
                    if (construction == null) continue;
                    FlowSiteId objectContext = RetainedAllocationDerivation.objectSite(
                            invocation, construction.site());
                    RetainedNominal nominal = retainedNominals.get(
                            construction.nominalType().canonicalSpelling());
                    if (nominal != null) {
                        if (nominal.memberInitializers().stream()
                                .flatMap(Optional::stream).anyMatch(initializer ->
                                        matchesDerivedAggregate(initializer, objectContext,
                                                ProjectionPath.root(), fact))) return true;
                        if (nominal.constructorLambda().isPresent()) {
                            LambdaId constructor = nominal.constructorLambda().orElseThrow();
                            if (matchesSummaryDerivedAggregateAllocations(
                                    fact, objectContext, ProjectionPath.root(), constructor,
                                    currentSummaries)
                                    || matchesSummaryDerivedAggregateInConstructions(
                                    fact, objectContext, ProjectionPath.root(), constructor,
                                    active, currentSummaries)) return true;
                        }
                    }
                } else {
                    for (LambdaId target : retainedCallTargets(call, currentSummaries)) {
                        if (matchesSummaryDerivedAggregateAllocations(
                                fact, invocation, prefix, target, currentSummaries)
                                || matchesSummaryDerivedAggregateInConstructions(
                                fact, invocation, prefix, target, active,
                                currentSummaries)) return true;
                    }
                }
            }
            return false;
        } finally {
            active.remove(lambda);
        }
    }

    private boolean matchesSummaryDerivedAggregateAllocations(
            AggregateIdentityFact fact, FlowSiteId context,
            ProjectionPath prefix, LambdaId rootLambda,
            CallableSummarySet currentSummaries) {
        CallableSummary summary = evidenceSummary(rootLambda, currentSummaries);
        if (summary == null) return false;
        List<ValueFormula.FreshAllocation> allocations = new ArrayList<>();
        collectAllocationFormulas(summary.returnFormula().alternatives(), allocations);
        summary.writes().forEach(write -> collectAllocationFormulas(write.value(), allocations));
        summary.ownershipRequirements().forEach(requirement ->
                collectAllocationFormulas(requirement.value(), allocations));
        for (ValueFormula.FreshAllocation fresh : allocations) {
            AllocationProvenance provenance = allocationProvenance.get(fresh.allocationSite());
            FlowSiteId derivedContext = summaryPathContext(
                    rootLambda, context, fresh.invocationPath(),
                    fresh.allocationSite().ownerLambda(), currentSummaries);
            if (provenance != null && derivedContext != null
                    && matchesDerivedAggregate(fact, derivedContext, provenance,
                    prefix.compose(fresh.resultRoute()))) return true;
        }
        return false;
    }

    private FlowSiteId summaryPathContext(
            LambdaId root, FlowSiteId context, List<SummaryCallId> path,
            LambdaId finalOwner) {
        return summaryPathContext(root, context, path, finalOwner,
                CallableSummarySet.empty());
    }

    private FlowSiteId summaryPathContext(
            LambdaId root, FlowSiteId context, List<SummaryCallId> path,
            LambdaId finalOwner, CallableSummarySet currentSummaries) {
        LambdaId current = root;
        FlowSiteId result = context;
        for (int index = 0; index < path.size(); index++) {
            SummaryCallId id = path.get(index);
            if (!id.ownerLambda().equals(current)) return null;
            CallableSummary owner = evidenceSummary(current, currentSummaries);
            if (owner == null) return null;
            CallableCallReference call = owner.callReferences().stream()
                    .filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
            if (call == null || call.siteId().isEmpty()) return null;
            LambdaId next = index + 1 < path.size()
                    ? path.get(index + 1).ownerLambda() : finalOwner;
            if (!retainedCallTargets(call, currentSummaries).contains(next)) return null;
            result = RetainedAllocationDerivation.invocationContext(
                    result, id, call.siteId().orElseThrow());
            current = next;
        }
        return current.equals(finalOwner) ? result : null;
    }

    private Set<LambdaId> retainedTargetLambdas(
            DeclarationId declaration, FunctionType function) {
        ValueAlternatives values = boundaryState.sharedCell(declaration)
                .or(() -> boundaryState.binding(declaration).map(BindingFlowValue::alternatives))
                .orElse(null);
        if (values == null) {
            return callableSummaries.lambdaForDeclaration(declaration)
                    .filter(lambda -> callableSummaries.summary(lambda)
                            .map(summary -> summary.signature().asFunctionType().equals(function))
                            .orElse(false)).stream().collect(java.util.stream.Collectors.toSet());
        }
        return callableLambdas(values);
    }

    private Set<LambdaId> retainedTargetLambdas(
            RetainedInitializerTransfer transfer) {
        if (transfer instanceof RetainedInitializerTransfer.Lambda lambda) {
            return Set.of(lambda.lambda());
        }
        if (transfer instanceof RetainedInitializerTransfer.Reference reference) {
            ValueAlternatives values = boundaryState.sharedCell(reference.declaration())
                    .or(() -> boundaryState.binding(reference.declaration())
                            .map(BindingFlowValue::alternatives)).orElse(null);
            if (values == null) return Set.of();
            try {
                return callableLambdas(values.select(reference.route()));
            } catch (IllegalArgumentException invalidRoute) {
                return Set.of();
            }
        }
        if (transfer instanceof RetainedInitializerTransfer.Value value) {
            return callableLambdas(value.value());
        }
        if (transfer instanceof RetainedInitializerTransfer.Project project
                && project.kind() == RetainedInitializerTransfer.ProjectionKind.ROUTE) {
            return retainedTargetLambdas(project.base());
        }
        if (transfer instanceof RetainedInitializerTransfer.Sequence sequence) {
            return retainedTargetLambdas(sequence.steps().getLast());
        }
        if (transfer instanceof RetainedInitializerTransfer.Alternative alternative) {
            TreeSet<LambdaId> result = new TreeSet<>();
            alternative.branches().forEach(branch -> branch.result().ifPresent(step ->
                    result.addAll(retainedTargetLambdas(step.transfer()))));
            return Set.copyOf(result);
        }
        return Set.of();
    }

    private static Set<LambdaId> callableLambdas(ValueAlternatives values) {
        return values.alternatives().stream()
                .flatMap(value -> value.callableFlows().stream())
                .filter(callable -> callable.route().isRoot())
                .flatMap(callable -> callable.lambdaId().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Set<LambdaId> retainedCallTargets(CallableCallReference call) {
        return retainedCallTargets(call, CallableSummarySet.empty());
    }

    private Set<LambdaId> retainedCallTargets(
            CallableCallReference call, CallableSummarySet currentSummaries) {
        TreeSet<LambdaId> result = new TreeSet<>();
        call.targetLambda().ifPresent(result::add);
        for (ValueFormula formula : call.target().formulas()) {
            if (formula instanceof ValueFormula.Lambda lambda) {
                result.add(lambda.lambdaId());
            } else if (formula instanceof ValueFormula.Declaration declaration) {
                currentSummaries.lambdaForDeclaration(declaration.declarationId())
                        .or(() -> callableSummaries.lambdaForDeclaration(
                        declaration.declarationId())).ifPresent(result::add);
                ValueAlternatives values = boundaryState.sharedCell(declaration.declarationId())
                        .or(() -> boundaryState.binding(declaration.declarationId())
                                .map(BindingFlowValue::alternatives)).orElse(null);
                if (values != null) result.addAll(callableLambdas(values));
            }
        }
        return Set.copyOf(result);
    }

    private CallableSummary evidenceSummary(
            LambdaId lambda, CallableSummarySet currentSummaries) {
        return currentSummaries.summary(lambda)
                .or(() -> callableSummaries.summary(lambda)).orElse(null);
    }

    private static boolean isCallableConsumer(TypedExpressionKind kind) {
        return kind == TypedExpressionKind.DIRECT_CALL
                || kind == TypedExpressionKind.NAMESPACE_DIRECT_CALL
                || kind == TypedExpressionKind.CALLABLE_CALL
                || kind == TypedExpressionKind.ITER
                || kind == TypedExpressionKind.WHILE;
    }

    private static boolean matchesDerivedAggregate(
            AggregateIdentityFact fact, FlowSiteId context,
            RetainedInitializerTransfer.ArrayAllocation provenance, ProjectionPath route) {
        DeclarationId expected = RetainedAllocationDerivation.arrayAllocation(
                context, provenance.site());
        var export = io.mindspice.lyra.compiler.identity.ExportId.of(
                provenance.moduleId(), "_flow_" + expected.ordinal(),
                LyraSignature.of(List.of(), provenance.type()));
        OwnershipWitness witness = fact.witness();
        boolean identity = fact.identity().originDeclaration().equals(expected)
                && fact.identity().ownerModule().equals(provenance.moduleId())
                && fact.identity().arrayType().equals(provenance.type())
                && (fact.identity() instanceof ArrayIdentity.LocalAllocation
                && fact.identity().originExport().isEmpty()
                || fact.identity() instanceof ArrayIdentity.CrossModuleOrigin
                && fact.identity().originExport().equals(Optional.of(export)));
        return identity && fact.route().equals(route)
                && witness.ownerModule().equals(provenance.moduleId())
                && witness.originDeclaration().equals(expected)
                && witness.scopeId().equals(provenance.scopeId())
                && witness.sourceSpan().equals(provenance.span())
                && witness.originSite().equals(Optional.of(provenance.site()))
                && witness.originExport().equals(fact.identity().originExport());
    }

    private static boolean matchesDerivedAggregate(
            AggregateIdentityFact fact, FlowSiteId context,
            AllocationProvenance provenance, ProjectionPath route) {
        DeclarationId derived = RetainedAllocationDerivation.arrayAllocation(
                context, provenance.originSite());
        var export = io.mindspice.lyra.compiler.identity.ExportId.of(
                provenance.moduleId(), "_flow_" + derived.ordinal(),
                LyraSignature.of(List.of(), provenance.arrayType()));
        OwnershipWitness witness = fact.witness();
        boolean identity = fact.identity().ownerModule().equals(provenance.moduleId())
                && fact.identity().originDeclaration().equals(derived)
                && fact.identity().arrayType().equals(provenance.arrayType())
                && (fact.identity() instanceof ArrayIdentity.LocalAllocation
                && fact.identity().originExport().isEmpty()
                || fact.identity() instanceof ArrayIdentity.CrossModuleOrigin
                && fact.identity().originExport().equals(Optional.of(export)));
        return identity && fact.route().equals(route)
                && witness.ownerModule().equals(provenance.moduleId())
                && witness.originDeclaration().equals(derived)
                && witness.scopeId().equals(provenance.scopeId())
                && witness.sourceSpan().equals(provenance.sourceSpan())
                && witness.originSite().equals(Optional.of(provenance.originSite()))
                && witness.originExport().equals(fact.identity().originExport());
    }

    /** Validates a consumer-scoped nominal-object derivation from a certified producer site. */
    public boolean certifiesDerivedObject(
            NominalObjectFact fact, FlowSiteId consumerContext, SourceSpan consumerSpan) {
        return false;
    }

    /** Exact consumer-expression form used by the semantic fact sealer. */
    public boolean certifiesDerivedObject(
            NominalObjectFact fact, FlowSiteId consumerContext,
            TypedExpression consumer, Set<LambdaId> callableTargets) {
        Objects.requireNonNull(fact, "fact");
        Objects.requireNonNull(consumer, "consumer");
        callableTargets = Set.copyOf(Objects.requireNonNull(callableTargets, "callableTargets"));
        if (!fact.ownership().useSpan().equals(consumer.span())) return false;
        if (consumer.kind() == TypedExpressionKind.CONSTRUCTION) {
            if (!(consumer.type().withoutQualifiers() instanceof NominalType nominalType)
                    || consumer.declarationId().isEmpty()) return false;
            RetainedNominal nominal = retainedNominals.get(nominalType.canonicalSpelling());
            if (nominal == null || !nominal.nominal().declaration().equals(
                    consumer.declarationId().orElseThrow())) return false;
            boolean memberDerived = nominal.memberInitializers().stream().flatMap(Optional::stream)
                    .anyMatch(transfer -> matchesDerivedObject(
                            transfer, consumerContext, ProjectionPath.root(), fact));
            if (memberDerived || nominal.constructorLambda().isEmpty()) return memberDerived;
            return matchesSummaryDerivedObject(fact, consumerContext, ProjectionPath.root(),
                    nominal.constructorLambda().orElseThrow(), new LinkedHashSet<>());
        }
        if (!isCallableConsumer(consumer.kind()) || callableTargets.isEmpty()) return false;
        return callableTargets.stream().allMatch(this::certifiesLambda)
                && callableTargets.stream().anyMatch(lambda -> matchesSummaryDerivedObject(
                        fact, consumerContext, ProjectionPath.root(), lambda,
                        new LinkedHashSet<>()));
    }

    /** Exact current-call object form retaining selected callable captures. */
    public boolean certifiesDerivedObjectFromCallables(
            NominalObjectFact fact, FlowSiteId consumerContext,
            TypedExpression consumer, Set<CallableFlow> callableTargets,
            CallableSummarySet currentSummaries,
            Map<DeclarationId, ValueAlternatives> currentValues) {
        Objects.requireNonNull(fact, "fact");
        Objects.requireNonNull(consumerContext, "consumerContext");
        Objects.requireNonNull(consumer, "consumer");
        callableTargets = Set.copyOf(Objects.requireNonNull(
                callableTargets, "callableTargets"));
        currentSummaries = Objects.requireNonNull(currentSummaries, "currentSummaries");
        currentValues = Map.copyOf(Objects.requireNonNull(currentValues, "currentValues"));
        if (!fact.ownership().useSpan().equals(consumer.span())
                || !isCallableConsumer(consumer.kind())
                || callableTargets.isEmpty()) return false;
        CallableSummarySet localEvidence = currentSummaries;
        Map<DeclarationId, ValueAlternatives> declarationEvidence = currentValues;
        List<CallableEvidence> targets = callableTargets.stream()
                .filter(callable -> callable.route().isRoot())
                .map(callable -> callableEvidence(
                        callable, localEvidence, declarationEvidence))
                .flatMap(Optional::stream).toList();
        if (targets.size() != callableTargets.size()) return false;
        return targets.stream().allMatch(target -> certifiesLambda(target.lambda())
                        || localEvidence.summary(target.lambda()).isPresent())
                && targets.stream().anyMatch(target -> matchesCallableDerivedObject(
                        fact, consumerContext, ProjectionPath.root(), target,
                        localEvidence, declarationEvidence, new LinkedHashSet<>()));
    }

    /** Exact construction-target compatibility form. */
    public boolean certifiesDerivedObject(
            NominalObjectFact fact, FlowSiteId consumerContext, SourceSpan consumerSpan,
            Optional<NominalType> constructedType) {
        Objects.requireNonNull(fact, "fact");
        Objects.requireNonNull(consumerContext, "consumerContext");
        Objects.requireNonNull(consumerSpan, "consumerSpan");
        constructedType = Objects.requireNonNull(constructedType, "constructedType");
        if (!fact.ownership().useSpan().equals(consumerSpan)
                || constructedType.isEmpty()) return false;
        Iterable<RetainedNominal> candidates = Optional.ofNullable(retainedNominals.get(
                constructedType.orElseThrow().canonicalSpelling())).stream().toList();
        for (RetainedNominal nominal : candidates) {
            for (RetainedInitializerTransfer transfer : nominal.memberInitializers()
                    .stream().flatMap(Optional::stream).toList()) {
                if (matchesDerivedObject(transfer, consumerContext, ProjectionPath.root(), fact)) return true;
            }
        }
        return false;
    }

    private boolean matchesDerivedObject(
            RetainedInitializerTransfer transfer, FlowSiteId context,
            ProjectionPath prefix, NominalObjectFact fact) {
        return switch (transfer) {
            case RetainedInitializerTransfer.Construct construct -> {
                boolean matched = matchesDerivedObject(fact, context, construct.site(), prefix);
                FlowSiteId nested = RetainedAllocationDerivation.objectSite(context, construct.site().site());
                RetainedNominal nominal = retainedNominals.get(
                        construct.site().nominalType().canonicalSpelling());
                if (!matched && nominal != null) {
                    matched = nominal.memberInitializers().stream()
                            .flatMap(Optional::stream).anyMatch(initializer -> matchesDerivedObject(
                                    initializer, nested, ProjectionPath.root(), fact));
                    if (!matched && nominal.constructorLambda().isPresent()) {
                        matched = matchesSummaryDerivedObject(
                                fact, nested, ProjectionPath.root(),
                                nominal.constructorLambda().orElseThrow(), new LinkedHashSet<>());
                    }
                }
                yield matched || construct.arguments().stream().anyMatch(argument ->
                        matchesDerivedObject(argument, context, prefix, fact));
            }
            case RetainedInitializerTransfer.Composite composite -> {
                boolean matched = false;
                for (int index = 0; !matched && index < composite.elements().size(); index++) {
                    ProjectionPath member = composite.arrayLiteral()
                            ? ProjectionPath.arrayElement(index) : ProjectionPath.tupleMember(index);
                    matched = matchesDerivedObject(composite.elements().get(index), context,
                            prefix.compose(member), fact);
                }
                yield matched;
            }
            case RetainedInitializerTransfer.Call call -> {
                Set<LambdaId> targets = retainedTargetLambdas(
                        call.target(), call.function());
                boolean matched = matchesDerivedObjectArguments(
                        fact, context, prefix, call.arguments(), targets);
                FlowSiteId invocation = RetainedAllocationDerivation.invocationContext(context, call.site());
                yield matched || targets.stream().anyMatch(lambda ->
                        matchesSummaryDerivedObject(
                                fact, invocation, prefix, lambda, new LinkedHashSet<>()));
            }
            case RetainedInitializerTransfer.CallableCall call -> {
                Set<LambdaId> targets = retainedTargetLambdas(call.target());
                boolean matched = matchesDerivedObjectArguments(
                        fact, context, prefix, call.arguments(), targets);
                FlowSiteId invocation = RetainedAllocationDerivation.invocationContext(context, call.site());
                yield matchesDerivedObject(call.target(), context, prefix, fact)
                        || matched
                        || targets.stream()
                        .anyMatch(lambda -> matchesSummaryDerivedObject(
                                fact, invocation, prefix, lambda, new LinkedHashSet<>()));
            }
            case RetainedInitializerTransfer.Apply apply -> apply.operands().stream().anyMatch(operand ->
                    matchesDerivedObject(operand, context, prefix, fact));
            case RetainedInitializerTransfer.Alternative alternative -> alternative.prefix().stream()
                    .anyMatch(step -> matchesDerivedObject(step.transfer(), context, prefix, fact))
                    || alternative.branches().stream().anyMatch(branch ->
                    branch.selectors().stream().anyMatch(step -> matchesDerivedObject(
                            step.transfer(), context, prefix, fact))
                            || branch.result().stream().anyMatch(step -> matchesDerivedObject(
                            step.transfer(), context, prefix, fact)));
            case RetainedInitializerTransfer.Sequence sequence -> sequence.steps().stream().anyMatch(step ->
                    matchesDerivedObject(step, context, prefix, fact));
            case RetainedInitializerTransfer.Declare declare -> matchesDerivedObject(
                    declare.initializer(), context, prefix, fact);
            case RetainedInitializerTransfer.Rebind rebind -> matchesDerivedObject(
                    rebind.value(), context, prefix, fact);
            case RetainedInitializerTransfer.Project project -> {
                NominalObjectFact sourceFact = project.kind() == RetainedInitializerTransfer.ProjectionKind.ROUTE
                        ? new NominalObjectFact(fact.identity(), project.route(), fact.ownership()) : fact;
                yield matchesDerivedObject(project.base(), context,
                        project.kind() == RetainedInitializerTransfer.ProjectionKind.ROUTE
                                ? ProjectionPath.root() : prefix, sourceFact)
                        || project.index().stream().anyMatch(index -> matchesDerivedObject(
                        index, context, prefix, fact));
            }
            case RetainedInitializerTransfer.Loop loop -> {
                FlowSiteId invocation = RetainedAllocationDerivation.invocationContext(context, loop.site());
                yield matchesDerivedObject(loop.input(), context, prefix, fact)
                        || matchesDerivedObject(loop.action(), context, prefix, fact)
                        || java.util.stream.Stream.concat(
                                retainedTargetLambdas(loop.input()).stream(),
                                retainedTargetLambdas(loop.action()).stream())
                        .anyMatch(lambda -> matchesSummaryDerivedObject(
                                fact, invocation, prefix, lambda, new LinkedHashSet<>()));
            }
            case RetainedInitializerTransfer.Lambda ignored -> false;
            case RetainedInitializerTransfer.Value ignored -> false;
            case RetainedInitializerTransfer.Reference ignored -> false;
        };
    }

    private boolean matchesCallableDerivedObject(
            NominalObjectFact fact, FlowSiteId context, ProjectionPath route,
            CallableEvidence evidence, CallableSummarySet currentSummaries,
            Map<DeclarationId, ValueAlternatives> currentValues,
            Set<CallableEvidenceVisit> active) {
        CallableEvidenceVisit visit = new CallableEvidenceVisit(
                evidence.lambda(), context);
        if (!active.add(visit)) return false;
        try {
            CallableSummary summary = evidenceSummary(
                    evidence.lambda(), currentSummaries);
            if (summary == null) return false;
            for (CallableCallReference call : summary.callReferences()) {
                if (call.siteId().isEmpty()) continue;
                FlowSiteId invocation = RetainedAllocationDerivation.invocationContext(
                        context, call.id(), call.siteId().orElseThrow());
                if (call.kind() == CallableCallReference.Kind.CONSTRUCTION) {
                    RetainedConstruction construction = retainedConstructions.get(call.id());
                    if (construction != null
                            && matchesDerivedObject(fact, invocation, construction, route)) {
                        return true;
                    }
                    if (construction != null) {
                        FlowSiteId objectContext = RetainedAllocationDerivation.objectSite(
                                invocation, construction.site());
                        RetainedNominal nominal = retainedNominals.get(
                                construction.nominalType().canonicalSpelling());
                        if (nominal != null) {
                            if (nominal.memberInitializers().stream()
                                    .flatMap(Optional::stream).anyMatch(initializer ->
                                            matchesDerivedObject(initializer, objectContext,
                                                    ProjectionPath.root(), fact))) return true;
                            if (nominal.constructorLambda().isPresent()
                                    && matchesSummaryDerivedObject(
                                    fact, objectContext, ProjectionPath.root(),
                                    nominal.constructorLambda().orElseThrow(),
                                    new LinkedHashSet<>())) return true;
                        }
                    }
                    continue;
                }
                List<Set<CallableEvidence>> arguments = new ArrayList<>();
                boolean complete = true;
                for (FormulaAlternatives argument : call.arguments()) {
                    Set<CallableEvidence> resolved = resolveCallableEvidence(
                            argument, evidence, currentSummaries, currentValues);
                    arguments.add(resolved);
                    if (argument.rootType().withoutQualifiers() instanceof FunctionType
                            && resolved.isEmpty()) complete = false;
                }
                if (!complete) continue;
                Set<CallableEvidence> targets = resolveCallableEvidence(
                        call.target(), evidence, currentSummaries, currentValues);
                for (CallableEvidence target : targets) {
                    CallableEvidence invoked = new CallableEvidence(
                            target.lambda(), arguments, target.captures());
                    if (matchesCallableDerivedObject(
                            fact, invocation, route, invoked, currentSummaries,
                            currentValues, active)) return true;
                }
            }
            return false;
        } finally {
            active.remove(visit);
        }
    }

    private boolean matchesSummaryDerivedObject(
            NominalObjectFact fact, FlowSiteId context, ProjectionPath route,
            LambdaId lambda, Set<LambdaId> active) {
        if (!active.add(lambda)) return false;
        CallableSummary summary = callableSummaries.summary(lambda).orElse(null);
        if (summary == null) return false;
        try {
            for (CallableCallReference call : summary.callReferences()) {
                if (call.siteId().isEmpty()) continue;
                FlowSiteId invocation = RetainedAllocationDerivation.invocationContext(
                        context, call.id(), call.siteId().orElseThrow());
                if (call.kind() == CallableCallReference.Kind.CONSTRUCTION) {
                    RetainedConstruction construction = retainedConstructions.get(call.id());
                    if (construction != null
                            && matchesDerivedObject(fact, invocation, construction, route)) {
                        return true;
                    }
                    if (construction != null) {
                        FlowSiteId objectContext = RetainedAllocationDerivation.objectSite(
                                invocation, construction.site());
                        RetainedNominal nominal = retainedNominals.get(
                                construction.nominalType().canonicalSpelling());
                        if (nominal != null) {
                            if (nominal.memberInitializers().stream()
                                    .flatMap(Optional::stream).anyMatch(initializer ->
                                            matchesDerivedObject(initializer, objectContext,
                                                    ProjectionPath.root(), fact))) return true;
                            if (nominal.constructorLambda().isPresent()
                                    && matchesSummaryDerivedObject(
                                    fact, objectContext, ProjectionPath.root(),
                                    nominal.constructorLambda().orElseThrow(), active)) return true;
                        }
                    }
                } else {
                    for (LambdaId target : retainedCallTargets(call)) {
                        if (matchesSummaryDerivedObject(
                                fact, invocation, route, target, active)) return true;
                    }
                }
            }
            return false;
        } finally {
            active.remove(lambda);
        }
    }

    private static boolean matchesDerivedObject(
            NominalObjectFact fact, FlowSiteId context,
            RetainedInitializerTransfer.ConstructionSite site, ProjectionPath route) {
        FlowSiteId derivedSite = RetainedAllocationDerivation.objectSite(context, site.site());
        DeclarationId derivedAllocation = RetainedAllocationDerivation.objectAllocation(context, site.site());
        OwnershipWitness witness = fact.ownership();
        return fact.identity().ownerModule().equals(site.moduleId())
                && fact.identity().allocationSite().equals(derivedSite)
                && fact.identity().type().equals(site.nominalType())
                && fact.route().equals(route)
                && witness.ownerModule().equals(site.moduleId())
                && witness.originDeclaration().equals(derivedAllocation)
                && witness.scopeId().equals(site.scopeId())
                && witness.sourceSpan().equals(site.span())
                && witness.originExport().isEmpty()
                && witness.originSite().equals(Optional.of(derivedSite));
    }

    private boolean matchesDerivedObject(
            NominalObjectFact fact, FlowSiteId context,
            RetainedConstruction construction, ProjectionPath route) {
        FlowSiteId derivedSite = RetainedAllocationDerivation.objectSite(context, construction.site());
        DeclarationId derivedAllocation = RetainedAllocationDerivation.objectAllocation(context, construction.site());
        OwnershipWitness witness = fact.ownership();
        return fact.identity().ownerModule().equals(construction.moduleId())
                && fact.identity().allocationSite().equals(derivedSite)
                && fact.identity().type().equals(construction.nominalType())
                && route.suffixOf(fact.route()).filter(suffix -> routeProofs.contains(
                        new RouteProof(construction.site(), suffix))).isPresent()
                && witness.ownerModule().equals(construction.moduleId())
                && witness.originDeclaration().equals(derivedAllocation)
                && witness.scopeId().equals(construction.scopeId())
                && witness.sourceSpan().equals(construction.call().span())
                && witness.originExport().isEmpty()
                && witness.originSite().equals(Optional.of(derivedSite));
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
        Set<SourceSpan> objectUseSpans = new LinkedHashSet<>();
        if (predecessor != null) {
            callableProofs.addAll(predecessor.callableProofs);
            aggregateProofs.addAll(predecessor.aggregateProofs);
            objectProofs.addAll(predecessor.objectProofs);
            aggregateUseSpans.addAll(predecessor.aggregateUseSpans);
            objectUseSpans.addAll(predecessor.objectUseSpans);
        }
        collectProofs(boundary, callableProofs, aggregateProofs, objectProofs,
                aggregateUseSpans, objectUseSpans);
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
                        aggregateUseSpans, objectUseSpans))));
        Set<RouteProof> routes = new LinkedHashSet<>();
        if (predecessor != null) routes.addAll(predecessor.routeProofs);
        routes.addAll(new RouteDerivation(boundary, summaries, nominals, constructions, graph).derive());
        return new SessionFlowCertificate(
                graph.allocator(), boundary, summaries, bindings,
                callableProofs, routes, aggregateProofs, objectProofs, aggregateUseSpans,
                objectUseSpans,
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
            Set<SourceSpan> aggregateUses,
            Set<SourceSpan> objectUses) {
        state.bindings().values().forEach(value -> collectProofs(
                value.alternatives(), callables, aggregates, objects,
                aggregateUses, objectUses));
        state.sharedCells().values().forEach(value -> collectProofs(
                value, callables, aggregates, objects, aggregateUses, objectUses));
        state.objects().values().forEach(object -> object.fields().values()
                .forEach(value -> collectProofs(
                        value, callables, aggregates, objects,
                        aggregateUses, objectUses)));
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
            LyraType type = declaration.contract().orElseThrow().valueType();
            if (!(type.withoutQualifiers() instanceof FunctionType function)) {
                return Optional.empty();
            }
            return Optional.of(new RetainedInitializerTransfer.Lambda(
                    declaration.initializerLambda().orElseThrow(), function));
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
            if (!(initializer.type().withoutQualifiers() instanceof FunctionType function)) {
                return Optional.empty();
            }
            return Optional.of(new RetainedInitializerTransfer.Lambda(
                    declaration.initializerLambda().orElseThrow(), function));
        }
        return retainedTransfer(initializer, graph);
    }

    /** Builds one bounded transfer node; it never retains a producer expression. */
    private static Optional<RetainedInitializerTransfer> retainedTransfer(
            TypedExpression expression, TypedSemanticInput graph) {
        if (expression.kind() == TypedExpressionKind.LAMBDA) {
            if (!(expression.type().withoutQualifiers() instanceof FunctionType function)) {
                return Optional.empty();
            }
            return expression.lambdaId().map(lambda ->
                            new RetainedInitializerTransfer.Lambda(lambda, function))
                    .map(RetainedInitializerTransfer.class::cast);
        }
        if (expression.kind() == TypedExpressionKind.DIRECT_CALL
                || expression.kind() == TypedExpressionKind.NAMESPACE_DIRECT_CALL) {
            return retainedInitializerCall(expression, graph).map(RetainedInitializerTransfer.class::cast);
        }
        if (expression.kind() == TypedExpressionKind.CALLABLE_CALL) {
            return retainedCallableCall(expression, graph).map(RetainedInitializerTransfer.class::cast);
        }
        Optional<RetainedInitializerTransfer.Reference> reference = retainedReference(expression, graph);
        if (reference.isPresent()) return Optional.of(reference.orElseThrow());
        if (expression.kind() == TypedExpressionKind.LITERAL) {
            return retainedLiteralValue(expression, graph)
                    .map(value -> new RetainedInitializerTransfer.Value(expression.type(), value))
                    .map(RetainedInitializerTransfer.class::cast);
        }
        return switch (expression.kind()) {
            case ARRAY_LITERAL, TUPLE_LITERAL -> retainedComposite(expression, graph);
            case OPERATOR, SHORT_CIRCUIT, CONVERSION, NARROWING, RANGE -> retainedApply(expression, graph);
            case CONDITIONAL, COALESCE, MATCH, COND -> retainedAlternative(expression, graph);
            case BLOCK -> retainedSequence(expression, graph);
            case DECLARATION -> retainedDeclare(expression, graph);
            case REBINDING -> retainedRebind(expression, graph);
            case INDEX_ACCESS, MEMBER_ACCESS -> retainedProject(expression, graph);
            case CONSTRUCTION -> retainedConstruct(expression, graph);
            case ITER, WHILE -> retainedLoop(expression, graph);
            default -> Optional.empty();
        };
    }

    private static Optional<RetainedInitializerTransfer> retainedComposite(
            TypedExpression expression, TypedSemanticInput graph) {
        // Every array allocation is instantiated in the consumer construction
        // context, so even all-literal composites retain their ordered child proof.
        List<RetainedInitializerTransfer> elements = retainedChildren(expression.children(), graph);
        if (elements == null) return Optional.empty();
        Optional<RetainedInitializerTransfer.ArrayAllocation> allocation = expression.kind()
                == TypedExpressionKind.ARRAY_LITERAL
                ? Optional.of(RetainedInitializerTransfer.ArrayAllocation.of(expression, graph))
                : Optional.empty();
        return Optional.of(new RetainedInitializerTransfer.Composite(
                expression.type(), expression.kind() == TypedExpressionKind.ARRAY_LITERAL,
                elements, allocation));
    }

    private static Optional<RetainedInitializerTransfer> retainedApply(
            TypedExpression expression, TypedSemanticInput graph) {
        List<RetainedInitializerTransfer> operands = retainedChildren(expression.children(), graph);
        if (operands == null) return Optional.empty();
        return Optional.of(new RetainedInitializerTransfer.Apply(
                RetainedInitializerTransfer.ApplyKind.of(expression.kind()), expression.type(),
                expression.operator(), operands));
    }

    private static Optional<RetainedInitializerTransfer> retainedAlternative(
            TypedExpression expression, TypedSemanticInput graph) {
        List<RetainedInitializerTransfer> transfers = retainedChildren(expression.children(), graph);
        if (transfers == null) return Optional.empty();
        java.util.function.IntFunction<RetainedInitializerTransfer.Alternative.Step> step = index ->
                new RetainedInitializerTransfer.Alternative.Step(expression.children().get(index).type(),
                        transfers.get(index));
        if (expression.kind() == TypedExpressionKind.CONDITIONAL) {
            if (expression.children().size() != 2 && expression.children().size() != 3) return Optional.empty();
            Optional<RetainedInitializerTransfer.Alternative.PredicateBinding> binding = expression.predicateBinding()
                    .flatMap(id -> graph.contract(id).map(contract ->
                            new RetainedInitializerTransfer.Alternative.PredicateBinding(id, contract)));
            if (expression.predicateBinding().isPresent() && binding.isEmpty()) return Optional.empty();
            Set<Integer> reachableBranches = expression.children().getFirst().literal()
                    .filter(io.mindspice.lyra.compiler.semantic.TypedLiteralValue.BooleanValue.class::isInstance)
                    .map(io.mindspice.lyra.compiler.semantic.TypedLiteralValue.BooleanValue.class::cast)
                    .map(value -> Set.of(value.value() ? 0 : 1))
                    .orElseGet(() -> Set.of(0, 1));
            return Optional.of(new RetainedInitializerTransfer.Alternative(
                    RetainedInitializerTransfer.AlternativeKind.CONDITIONAL, expression.type(),
                    List.of(step.apply(0)), List.of(
                            new RetainedInitializerTransfer.Alternative.Branch(
                                    Optional.empty(), Optional.empty(), false, Optional.of(step.apply(1))),
                            new RetainedInitializerTransfer.Alternative.Branch(
                                    Optional.empty(), Optional.empty(), false, expression.children().size() == 3
                                    ? Optional.of(step.apply(2)) : Optional.empty())), binding,
                    reachableBranches));
        }
        if (expression.kind() == TypedExpressionKind.COALESCE) {
            if (expression.children().size() != 2) return Optional.empty();
            return Optional.of(new RetainedInitializerTransfer.Alternative(
                    RetainedInitializerTransfer.AlternativeKind.COALESCE, expression.type(),
                    List.of(step.apply(0)), List.of(new RetainedInitializerTransfer.Alternative.Branch(
                            Optional.empty(), Optional.empty(), false, Optional.of(step.apply(1)))), Optional.empty()));
        }
        var match = expression.match().orElse(null);
        if (match == null) return Optional.empty();
        List<RetainedInitializerTransfer.Alternative.Step> prefix = match.subjectChild().isPresent()
                ? List.of(step.apply(match.subjectChild().getAsInt())) : List.of();
        List<RetainedInitializerTransfer.Alternative.Branch> branches = new ArrayList<>();
        for (var arm : match.arms()) {
            branches.add(new RetainedInitializerTransfer.Alternative.Branch(
                    arm.patternChild().stream().mapToObj(step).findFirst(),
                    arm.guardChild().stream().mapToObj(step).findFirst(),
                    arm.wildcard(), Optional.of(step.apply(arm.resultChild()))));
        }
        return Optional.of(new RetainedInitializerTransfer.Alternative(
                RetainedInitializerTransfer.AlternativeKind.of(expression.kind()), expression.type(),
                prefix, branches, Optional.empty()));
    }

    private static Optional<RetainedInitializerTransfer> retainedSequence(
            TypedExpression expression, TypedSemanticInput graph) {
        List<RetainedInitializerTransfer> steps = retainedChildren(expression.children(), graph);
        return steps == null ? Optional.empty() : Optional.of(
                new RetainedInitializerTransfer.Sequence(expression.type(), steps));
    }

    private static Optional<RetainedInitializerTransfer> retainedDeclare(
            TypedExpression expression, TypedSemanticInput graph) {
        if (expression.children().size() != 1 || expression.declarationId().isEmpty()) return Optional.empty();
        DeclarationId declaration = expression.declarationId().orElseThrow();
        BindingContract contract = graph.contract(declaration).orElse(null);
        if (contract == null) return Optional.empty();
        List<DeclarationId> cells = graph.resolvedGraph().captures().stream()
                .filter(capture -> capture.declarationId().equals(declaration) && capture.isSharedCell())
                .map(capture -> capture.sharedCellId().orElseThrow()).distinct().toList();
        if (cells.size() > 1) return Optional.empty();
        return retainedTransfer(expression.children().getFirst(), graph).map(value ->
                new RetainedInitializerTransfer.Declare(expression.type(), declaration,
                        contract, cells.stream().findFirst(), value))
                .map(RetainedInitializerTransfer.class::cast);
    }

    private static Optional<RetainedInitializerTransfer> retainedRebind(
            TypedExpression expression, TypedSemanticInput graph) {
        if (expression.children().size() != 2) return Optional.empty();
        Optional<WriteTarget> target = WriteTarget.of(expression.children().getFirst(), graph);
        return target.flatMap(path -> {
            BindingContract rootContract = graph.contract(path.declaration()).orElse(null);
            if (rootContract == null) return Optional.empty();
            LyraType targetType;
            try {
                targetType = ValueAlternative.typeAt(rootContract.valueType(), path.route());
            } catch (IllegalArgumentException invalidRoute) {
                return Optional.empty();
            }
            List<DeclarationId> cells = graph.resolvedGraph().captures().stream()
                    .filter(capture -> capture.declarationId().equals(path.declaration()) && capture.isSharedCell())
                    .map(capture -> capture.sharedCellId().orElseThrow()).distinct().toList();
            if (cells.size() > 1) return Optional.empty();
            return retainedTransfer(expression.children().get(1), graph)
                    .map(value -> new RetainedInitializerTransfer.Rebind(expression.type(), path,
                            rootContract.valueType(), targetType, cells.stream().findFirst(), value))
                    .map(RetainedInitializerTransfer.class::cast);
        });
    }

    private static Optional<RetainedInitializerTransfer> retainedProject(
            TypedExpression expression, TypedSemanticInput graph) {
        if (expression.children().isEmpty()) return Optional.empty();
        Optional<RetainedInitializerTransfer> base = retainedTransfer(expression.children().getFirst(), graph);
        if (base.isEmpty()) return Optional.empty();
        if (expression.kind() == TypedExpressionKind.MEMBER_ACCESS && expression.tupleIndex().isPresent()) {
            return Optional.of(new RetainedInitializerTransfer.Project(expression.type(), base.orElseThrow(),
                    expression.children().getFirst().type(), RetainedInitializerTransfer.ProjectionKind.ROUTE,
                    ProjectionPath.tupleMember(expression.tupleIndex().orElseThrow().intValueExact()), Optional.empty()));
        }
        if (expression.kind() == TypedExpressionKind.MEMBER_ACCESS && expression.declarationId().isPresent()
                && expression.children().getFirst().type().withoutQualifiers() instanceof NominalType owner) {
            var nominal = graph.resolvedGraph().nominals().stream()
                    .filter(value -> value.schema().type().equals(owner)).findFirst().orElse(null);
            if (nominal == null) return Optional.empty();
            int index = nominal.members().indexOf(expression.declarationId().orElseThrow());
            if (index < 0) return Optional.empty();
            return Optional.of(new RetainedInitializerTransfer.Project(expression.type(), base.orElseThrow(),
                    expression.children().getFirst().type(), RetainedInitializerTransfer.ProjectionKind.ROUTE,
                    ProjectionPath.of(new io.mindspice.lyra.compiler.semantic.flow.ProjectionStep.NominalMember(
                            owner, index, nominal.schema().members().get(index).type())), Optional.empty()));
        }
        if (expression.kind() == TypedExpressionKind.INDEX_ACCESS && expression.children().size() == 2) {
            boolean stringIndex = expression.children().getFirst().type().withoutQualifiers()
                    == PrimitiveType.STRING;
            return retainedTransfer(expression.children().get(1), graph).map(index ->
                    new RetainedInitializerTransfer.Project(expression.type(), base.orElseThrow(),
                            expression.children().getFirst().type(), stringIndex
                            ? RetainedInitializerTransfer.ProjectionKind.STRING_INDEX
                            : RetainedInitializerTransfer.ProjectionKind.ROUTE,
                            stringIndex ? ProjectionPath.root()
                                    : ProjectionPath.of(indexRoute(expression.children().get(1))), Optional.of(index)))
                    .map(RetainedInitializerTransfer.class::cast);
        }
        if (expression.kind() == TypedExpressionKind.MEMBER_ACCESS
                && expression.children().size() == 1
                && "length".equals(expression.memberName().orElse(null))
                && (expression.children().getFirst().type().withoutQualifiers() == PrimitiveType.STRING
                || expression.children().getFirst().type().withoutQualifiers() instanceof ArrayType)) {
            return Optional.of(new RetainedInitializerTransfer.Project(expression.type(), base.orElseThrow(),
                    expression.children().getFirst().type(), RetainedInitializerTransfer.ProjectionKind.LENGTH,
                    ProjectionPath.root(), Optional.empty()));
        }
        return Optional.empty();
    }

    private static Optional<RetainedInitializerTransfer> retainedConstruct(
            TypedExpression expression, TypedSemanticInput graph) {
        if (expression.declarationId().isEmpty()) return Optional.empty();
        List<RetainedInitializerTransfer> arguments = retainedChildren(expression.children(), graph);
        if (arguments == null) return Optional.empty();
        NominalSchema schema = graph.resolvedGraph().nominals().stream()
                .filter(nominal -> nominal.declaration().equals(expression.declarationId().orElseThrow()))
                .map(nominal -> nominal.schema()).findFirst().orElse(null);
        if (schema == null) return Optional.empty();
        return Optional.of(new RetainedInitializerTransfer.Construct(
                new RetainedInitializerTransfer.ConstructionSite(expression.declarationId().orElseThrow(), schema,
                        ModuleId.fromSourceId(expression.span().sourceId()), graph.flowScopeId(expression),
                        expression.span(), graph.flowSiteId(expression),
                        new DeclarationId(Long.MAX_VALUE - graph.flowSiteId(expression).ordinal()),
                        expression.children().stream().map(TypedExpression::type).toList(),
                        expression.children().stream().map(argument -> WriteTarget.of(argument, graph)).toList()), arguments));
    }

    private static Optional<RetainedInitializerTransfer> retainedLoop(
            TypedExpression expression, TypedSemanticInput graph) {
        if (expression.children().size() != 2) return Optional.empty();
        return retainedTransfer(expression.children().getFirst(), graph).flatMap(input ->
                retainedTransfer(expression.children().getLast(), graph).map(action ->
                        new RetainedInitializerTransfer.Loop(expression.kind(), expression.type(),
                                expression.span(), graph.flowSiteId(expression), input, action,
                                expression.kind() == TypedExpressionKind.WHILE
                                        ? Optional.of((FunctionType) expression.children().getFirst().type().withoutQualifiers())
                                        : Optional.empty(),
                                (FunctionType) expression.children().getLast().type().withoutQualifiers()))
                        .map(RetainedInitializerTransfer.class::cast));
    }

    private static List<RetainedInitializerTransfer> retainedChildren(
            List<TypedExpression> children, TypedSemanticInput graph) {
        List<RetainedInitializerTransfer> result = new ArrayList<>();
        for (TypedExpression child : children) {
            Optional<RetainedInitializerTransfer> transfer = retainedTransfer(child, graph);
            if (transfer.isEmpty()) return null;
            result.add(transfer.orElseThrow());
        }
        return List.copyOf(result);
    }

    private static io.mindspice.lyra.compiler.semantic.flow.ProjectionStep indexRoute(
            TypedExpression index) {
        if (index.literal().orElse(null) instanceof io.mindspice.lyra.compiler.semantic.TypedLiteralValue.IntegerValue integer) {
            java.math.BigInteger value = integer.exactValue().integerValue();
            if (value.signum() >= 0 && value.bitLength() <= 31) {
                return io.mindspice.lyra.compiler.semantic.flow.ProjectionStep.arrayElement(value.intValue());
            }
        }
        return io.mindspice.lyra.compiler.semantic.flow.ProjectionStep.unknownArrayElement();
    }

    /**
     * Derives an exact declaration projection.  The route records nominal member
     * slots and named tuple members so the consumer resolves the original
     * declaration identity against the fresh receiver or the current shared
     * cell, never against current source spelling.
     */
    private static Optional<RetainedInitializerTransfer.Reference> retainedReference(
            TypedExpression expression, TypedSemanticInput graph) {
        if (expression.kind() == TypedExpressionKind.REFERENCE
                || expression.kind() == TypedExpressionKind.NAMESPACE_MEMBER_ACCESS) {
            return expression.link()
                    .flatMap(io.mindspice.lyra.compiler.semantic.TypedLink::declarationId)
                    .map(declaration -> new RetainedInitializerTransfer.Reference(
                            declaration, expression.type(), ProjectionPath.root(), expression.type()));
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
                    base.orElseThrow().declaration(), base.orElseThrow().declarationType(),
                    route, expression.type()));
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
                base.orElseThrow().declaration(), base.orElseThrow().declarationType(),
                route, expression.type()));
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
                target, function, initializer.span(), graph.flowSiteId(initializer), arguments, targets));
    }

    /**
     * Argument transfer for a retained initializer call.  The current closed
     * algebra covers literal values, declaration projections and nested direct
     * calls; a form outside it leaves the enclosing call without a transfer.
     */
    private static Optional<RetainedInitializerTransfer> retainedArgumentTransfer(
            TypedExpression argument, TypedSemanticInput graph) {
        return retainedTransfer(argument, graph);
    }

    private static Optional<RetainedInitializerTransfer.CallableCall> retainedCallableCall(
            TypedExpression expression, TypedSemanticInput graph) {
        if (expression.children().isEmpty()
                || !(expression.children().getFirst().type().withoutQualifiers() instanceof FunctionType function)) {
            return Optional.empty();
        }
        Optional<RetainedInitializerTransfer> target = retainedTransfer(expression.children().getFirst(), graph);
        List<RetainedInitializerTransfer> arguments = retainedChildren(
                expression.children().subList(1, expression.children().size()), graph);
        if (target.isEmpty() || arguments == null || arguments.size() != function.arity()) return Optional.empty();
        return Optional.of(new RetainedInitializerTransfer.CallableCall(target.orElseThrow(), function,
                expression.span(), graph.flowSiteId(expression), arguments,
                expression.children().subList(1, expression.children().size()).stream()
                        .map(argument -> WriteTarget.of(argument, graph)).toList()));
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
                NominalSchema schema = graph.resolvedGraph().nominals().stream()
                        .filter(nominal -> nominal.declaration().equals(
                                source.declarationId().orElseThrow()))
                        .map(ResolvedNominal::schema).findFirst().orElseThrow();
                RetainedConstruction construction = new RetainedConstruction(
                        call, schema, summary.moduleId(), graph.flowScopeId(source),
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
            Set<SourceSpan> aggregateUses,
            Set<SourceSpan> objectUses) {
        collectTransferProofs(transfer, ProjectionPath.root(), callables,
                aggregates, objects, aggregateUses, objectUses);
    }

    private static void collectTransferProofs(
            RetainedInitializerTransfer transfer, ProjectionPath prefix,
            Set<CallableProofKey> callables, Set<AggregateProofKey> aggregates,
            Set<ObjectProofKey> objects, Set<SourceSpan> aggregateUses,
            Set<SourceSpan> objectUses) {
        switch (transfer) {
            case RetainedInitializerTransfer.Lambda ignored -> { }
            case RetainedInitializerTransfer.Reference ignored -> { }
            case RetainedInitializerTransfer.Value value -> value.value().alternatives().forEach(alternative -> {
                alternative.callableFlows().forEach(callable -> callables.add(
                        CallableProofKey.of(callable.prefixedBy(prefix))));
                alternative.aggregateIdentities().forEach(fact -> aggregates.add(
                        AggregateProofKey.of(fact.prefixedBy(prefix))));
                alternative.objects().forEach(fact -> {
                    objects.add(ObjectProofKey.of(fact.prefixedBy(prefix)));
                    objectUses.add(fact.ownership().useSpan());
                });
                alternative.aggregateIdentities().forEach(fact ->
                        aggregateUses.add(fact.witness().useSpan()));
            });
            case RetainedInitializerTransfer.Call call -> call.arguments().forEach(argument ->
                    collectTransferProofs(argument, prefix, callables, aggregates,
                            objects, aggregateUses, objectUses));
            case RetainedInitializerTransfer.Composite composite -> {
                for (int index = 0; index < composite.elements().size(); index++) {
                    ProjectionPath member = composite.arrayLiteral()
                            ? ProjectionPath.arrayElement(index) : ProjectionPath.tupleMember(index);
                    collectTransferProofs(composite.elements().get(index), prefix.compose(member),
                            callables, aggregates, objects, aggregateUses, objectUses);
                }
                composite.allocation().ifPresent(allocation -> {
                    AggregateIdentityFact fact = new AggregateIdentityFact(
                            ArrayIdentity.localAllocation(allocation.moduleId(), allocation.allocation(), allocation.type()),
                            prefix, OwnershipWitness.local(allocation.moduleId(), allocation.allocation(),
                                    allocation.scopeId(), allocation.span()).withOriginSite(allocation.site()));
                    aggregates.add(AggregateProofKey.of(fact));
                    var export = io.mindspice.lyra.compiler.identity.ExportId.of(allocation.moduleId(),
                            "_flow_" + allocation.allocation().ordinal(), LyraSignature.of(List.of(), allocation.type()));
                    aggregates.add(AggregateProofKey.of(new AggregateIdentityFact(
                            ArrayIdentity.crossModuleOrigin(allocation.moduleId(), allocation.allocation(), export,
                                    allocation.type()), prefix, OwnershipWitness.crossModule(
                                    allocation.moduleId(), allocation.allocation(), allocation.scopeId(), allocation.span(), export)
                                    .withOriginSite(allocation.site()))));
                    aggregateUses.add(allocation.span());
                });
            }
            case RetainedInitializerTransfer.Apply apply -> apply.operands().forEach(operand ->
                    collectTransferProofs(operand, prefix, callables, aggregates,
                            objects, aggregateUses, objectUses));
            case RetainedInitializerTransfer.Alternative alternative -> {
                alternative.prefix().forEach(step -> collectTransferProofs(
                        step.transfer(), prefix, callables, aggregates,
                        objects, aggregateUses, objectUses));
                alternative.branches().forEach(branch -> {
                    branch.selectors().forEach(step -> collectTransferProofs(
                            step.transfer(), prefix, callables, aggregates,
                            objects, aggregateUses, objectUses));
                    branch.result().ifPresent(step -> collectTransferProofs(
                            step.transfer(), prefix, callables, aggregates,
                            objects, aggregateUses, objectUses));
                });
            }
            case RetainedInitializerTransfer.Sequence sequence -> sequence.steps().forEach(step ->
                    collectTransferProofs(step, prefix, callables, aggregates,
                            objects, aggregateUses, objectUses));
            case RetainedInitializerTransfer.Declare declare -> collectTransferProofs(
                    declare.initializer(), prefix, callables, aggregates,
                    objects, aggregateUses, objectUses);
            case RetainedInitializerTransfer.Rebind rebind -> collectTransferProofs(
                    rebind.value(), prefix, callables, aggregates,
                    objects, aggregateUses, objectUses);
            case RetainedInitializerTransfer.Project project -> {
                collectTransferProofs(project.base(), prefix, callables, aggregates,
                        objects, aggregateUses, objectUses);
                project.index().ifPresent(index -> collectTransferProofs(
                        index, prefix, callables, aggregates,
                        objects, aggregateUses, objectUses));
            }
            case RetainedInitializerTransfer.Construct construct -> {
                construct.arguments().forEach(argument -> collectTransferProofs(
                        argument, prefix, callables, aggregates,
                        objects, aggregateUses, objectUses));
                var site = construct.site();
                objects.add(ObjectProofKey.of(new NominalObjectFact(
                        new io.mindspice.lyra.compiler.semantic.flow.NominalObjectIdentity(
                                site.moduleId(), site.site(), site.nominalType()), prefix,
                        OwnershipWitness.local(site.moduleId(), site.allocation(), site.scopeId(), site.span())
                                .withOriginSite(site.site()))));
                objectUses.add(site.span());
            }
            case RetainedInitializerTransfer.CallableCall call -> {
                collectTransferProofs(call.target(), prefix, callables, aggregates,
                        objects, aggregateUses, objectUses);
                call.arguments().forEach(argument -> collectTransferProofs(
                        argument, prefix, callables, aggregates,
                        objects, aggregateUses, objectUses));
            }
            case RetainedInitializerTransfer.Loop loop -> {
                collectTransferProofs(loop.input(), prefix, callables, aggregates,
                        objects, aggregateUses, objectUses);
                collectTransferProofs(loop.action(), prefix, callables, aggregates,
                        objects, aggregateUses, objectUses);
            }
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
        return containsNilRoute(transfer, nil, ProjectionPath.root(), nil.route(), visited);
    }

    /**
     * Prefix-aware transfer walk.  The consumer replays a retained composite by
     * prefixing each element's nil provenance with the element route, and a
     * retained projection by rebasing selected nil provenance below the
     * projection route with the same wildcard overlap semantics as ordinary
     * evaluation.  Certification must follow the same rebasing instead of
     * demanding an exact root-relative route; site and span stay exact.
     */
    private static boolean containsNilRoute(
            RetainedInitializerTransfer transfer, NilProvenance nil,
            ProjectionPath prefix, ProjectionPath route, Set<Object> visited) {
        return switch (transfer) {
            case RetainedInitializerTransfer.Lambda ignored -> false;
            case RetainedInitializerTransfer.Reference ignored -> false;
            case RetainedInitializerTransfer.Value value -> {
                if (prefix.isRoot()) {
                    yield containsNil(value.value(), nil, visited);
                }
                ProjectionPath remaining = route.suffix(prefix.depth());
                yield value.value().alternatives().stream().anyMatch(alternative ->
                        alternative.nilProvenance().stream().anyMatch(candidate ->
                                candidate.sourceSite().equals(nil.sourceSite())
                                        && candidate.sourceSpan().equals(nil.sourceSpan())
                                        && routesOverlap(candidate.route(), remaining)));
            }
            case RetainedInitializerTransfer.Call call -> call.arguments().stream()
                    .anyMatch(argument -> containsNilRoute(argument, nil, prefix, route, visited));
            case RetainedInitializerTransfer.Composite composite -> {
                ProjectionPath remaining = route.suffix(prefix.depth());
                boolean matched = false;
                for (int index = 0; !matched && index < composite.elements().size(); index++) {
                    ProjectionPath member = composite.arrayLiteral()
                            ? ProjectionPath.arrayElement(index)
                            : ProjectionPath.tupleMember(index);
                    if (!remaining.isRoot()
                            && member.steps().getFirst().overlaps(remaining.steps().getFirst())) {
                        matched = containsNilRoute(composite.elements().get(index), nil,
                                prefix.compose(member), route, visited);
                    }
                }
                yield matched;
            }
            case RetainedInitializerTransfer.Apply apply -> apply.operands().stream()
                    .anyMatch(operand -> containsNilRoute(operand, nil, prefix, route, visited));
            case RetainedInitializerTransfer.Alternative alternative -> alternative.prefix().stream()
                    .anyMatch(step -> containsNilRoute(step.transfer(), nil, prefix, route, visited))
                    || alternative.branches().stream().anyMatch(branch ->
                    branch.selectors().stream().anyMatch(step ->
                            containsNilRoute(step.transfer(), nil, prefix, route, visited))
                            || branch.result().stream().anyMatch(step ->
                            containsNilRoute(step.transfer(), nil, prefix, route, visited)));
            case RetainedInitializerTransfer.Sequence sequence -> sequence.steps().stream()
                    .anyMatch(step -> containsNilRoute(step, nil, prefix, route, visited));
            case RetainedInitializerTransfer.Declare declare ->
                    containsNilRoute(declare.initializer(), nil, prefix, route, visited);
            case RetainedInitializerTransfer.Rebind rebind ->
                    containsNilRoute(rebind.value(), nil, prefix, route, visited);
            case RetainedInitializerTransfer.Project project -> {
                ProjectionPath remaining = route.suffix(prefix.depth());
                ProjectionPath baseRoute = prefix.compose(project.route().compose(remaining));
                boolean matched = containsNilRoute(project.base(), nil, prefix, baseRoute, visited);
                if (!matched && project.index().isPresent()) {
                    matched = containsNilRoute(project.index().orElseThrow(),
                            nil, prefix, route, visited);
                }
                yield matched;
            }
            case RetainedInitializerTransfer.Construct construct -> construct.arguments().stream()
                    .anyMatch(argument -> containsNilRoute(argument, nil, prefix, route, visited));
            case RetainedInitializerTransfer.CallableCall call ->
                    containsNilRoute(call.target(), nil, prefix, route, visited)
                            || call.arguments().stream().anyMatch(argument ->
                            containsNilRoute(argument, nil, prefix, route, visited));
            case RetainedInitializerTransfer.Loop loop ->
                    containsNilRoute(loop.input(), nil, prefix, route, visited)
                            || containsNilRoute(loop.action(), nil, prefix, route, visited);
        };
    }

    /** Same-depth stepwise overlap, mirroring ordinary selection rebasing. */
    private static boolean routesOverlap(ProjectionPath left, ProjectionPath right) {
        if (left.depth() != right.depth()) {
            return false;
        }
        for (int index = 0; index < left.depth(); index++) {
            if (!left.steps().get(index).overlaps(right.steps().get(index))) {
                return false;
            }
        }
        return true;
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
            Set<SourceSpan> aggregateUses,
            Set<SourceSpan> objectUses) {
        values.alternatives().forEach(value -> {
            value.aggregateIdentities().forEach(fact -> {
                aggregates.add(AggregateProofKey.of(fact));
                aggregateUses.add(fact.witness().useSpan());
            });
            value.objects().forEach(fact -> {
                objects.add(ObjectProofKey.of(fact));
                objectUses.add(fact.ownership().useSpan());
            });
            value.callableFlows().forEach(callable -> {
                if (callables.add(CallableProofKey.of(callable))) {
                    callable.capturedValues().values().forEach(captured ->
                            collectProofs(captured, callables, aggregates, objects,
                                    aggregateUses, objectUses));
                    callable.sharedCellSnapshots().values().forEach(captured ->
                            collectProofs(captured, callables, aggregates, objects,
                                    aggregateUses, objectUses));
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
                Optional<RetainedInitializerTransfer> initializer = Objects.requireNonNull(
                        memberInitializers.get(index), "retained member initializer");
                if (initializer.isPresent()
                        != nominal.schema().members().get(index).hasInitializer()) {
                    throw new IllegalArgumentException(
                            "retained nominal initializer coverage differs from its schema");
                }
                if (initializer.isPresent() && !TypeRules.canImplicitlyConvert(
                        initializer.orElseThrow().type(),
                        nominal.schema().members().get(index).type())) {
                    throw new IllegalArgumentException(
                            "retained nominal initializer type differs from its member schema");
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
        /** Exact resolved result type carried by every closed transfer node. */
        LyraType type();

        /** A producer-certified lambda literal; its captures are re-resolved on use. */
        record Lambda(LambdaId lambda, FunctionType type) implements RetainedInitializerTransfer {
            public Lambda {
                Objects.requireNonNull(lambda, "lambda");
                Objects.requireNonNull(type, "type");
            }
        }

        /** A closed abstract value with no dependency on the consumer's receiver. */
        record Value(LyraType type, ValueAlternatives value) implements RetainedInitializerTransfer {
            public Value {
                Objects.requireNonNull(type, "type");
                Objects.requireNonNull(value, "value");
                if (value.isEmpty() || !valuesHaveType(value, type)) {
                    throw new IllegalArgumentException("retained initializer value/type differs");
                }
            }
        }

        /**
         * An exact producer declaration projection resolved by declaration
         * identity, not by current spelling.  The route carries nominal member
         * slots so the fresh receiver or the current shared cell supplies the
         * transferred value.
         */
        record Reference(DeclarationId declaration, LyraType declarationType,
                         ProjectionPath route, LyraType type)
                implements RetainedInitializerTransfer {
            public Reference {
                Objects.requireNonNull(declaration, "declaration");
                Objects.requireNonNull(declarationType, "declarationType");
                Objects.requireNonNull(route, "route");
                Objects.requireNonNull(type, "type");
                LyraType selected;
                try {
                    selected = ValueAlternative.typeAt(declarationType, route);
                } catch (IllegalArgumentException invalidRoute) {
                    throw new IllegalArgumentException(
                            "retained declaration projection route differs", invalidRoute);
                }
                if (!compatibleType(selected, type)) {
                    throw new IllegalArgumentException(
                            "retained declaration projection type differs");
                }
            }

            /** Compatibility form is intentionally root-only; routed proofs need their root contract. */
            public Reference(DeclarationId declaration, ProjectionPath route, LyraType type) {
                this(declaration, type, route, type);
                if (!route.isRoot()) {
                    throw new IllegalArgumentException(
                            "routed retained references need their declaration root type");
                }
            }
        }

        /** A direct or namespace direct call to a producer-certified function. */
        record Call(
                DeclarationId target, FunctionType function, SourceSpan span, FlowSiteId site,
                List<RetainedInitializerTransfer> arguments,
                List<Optional<WriteTarget>> argumentTargets)
                implements RetainedInitializerTransfer {
            public Call {
                Objects.requireNonNull(target, "target");
                Objects.requireNonNull(function, "function");
                Objects.requireNonNull(span, "span");
                Objects.requireNonNull(site, "site");
                arguments = checkedTransfers(arguments, "arguments");
                argumentTargets = checkedTargets(argumentTargets, function.arity());
                if (!RetainedAllocationDerivation.isOrdinarySourceDeclaration(target)
                        || !RetainedAllocationDerivation.isOrdinarySourceSite(site)
                        || arguments.size() != function.arity()) {
                    throw new IllegalArgumentException("retained initializer call target/site/arity differs");
                }
                requireArgumentTypes(function, arguments);
            }

            @Override public LyraType type() { return function.returnType(); }
        }

        /** Ordered dynamic aggregate members with a producer-certified array allocation when needed. */
        record Composite(LyraType type, boolean arrayLiteral, List<RetainedInitializerTransfer> elements,
                         Optional<ArrayAllocation> allocation) implements RetainedInitializerTransfer {
            public Composite {
                Objects.requireNonNull(type, "type");
                elements = checkedTransfers(elements, "elements");
                allocation = Objects.requireNonNull(allocation, "allocation");
                LyraType plain = type.withoutQualifiers();
                boolean valid = arrayLiteral == allocation.isPresent();
                if (valid && arrayLiteral) {
                    valid = plain instanceof ArrayType array
                            && allocation.orElseThrow().type().equals(array);
                    if (valid) {
                        ArrayType array = (ArrayType) plain;
                        for (RetainedInitializerTransfer element : elements) {
                            valid &= sameType(element.type(), array.elementType());
                        }
                    }
                } else if (valid) {
                    valid = plain instanceof TupleType tuple && tuple.arity() == elements.size();
                    if (valid) {
                        TupleType tuple = (TupleType) plain;
                        for (int index = 0; index < elements.size(); index++) {
                            valid &= sameType(elements.get(index).type(), tuple.memberType(index));
                        }
                    }
                }
                if (!valid) throw new IllegalArgumentException("retained composite type/shape differs");
            }
        }

        enum ApplyKind {
            OPERATOR, SHORT_CIRCUIT, CONVERSION, NARROWING, RANGE;
            static ApplyKind of(TypedExpressionKind kind) {
                return switch (kind) {
                    case OPERATOR -> OPERATOR;
                    case SHORT_CIRCUIT -> SHORT_CIRCUIT;
                    case CONVERSION -> CONVERSION;
                    case NARROWING -> NARROWING;
                    case RANGE -> RANGE;
                    default -> throw new IllegalArgumentException("not an apply transfer: " + kind);
                };
            }
        }

        /** Strictly ordered scalar/range application. */
        record Apply(ApplyKind kind, LyraType type, Optional<String> operation,
                     List<RetainedInitializerTransfer> operands)
                implements RetainedInitializerTransfer {
            public Apply {
                Objects.requireNonNull(kind, "kind");
                Objects.requireNonNull(type, "type");
                operation = Objects.requireNonNull(operation, "operation");
                operation.ifPresent(value -> {
                    if (value.isBlank()) throw new IllegalArgumentException(
                            "retained apply operation is blank");
                });
                operands = checkedTransfers(operands, "operands");
                if (operands.isEmpty()) throw new IllegalArgumentException("retained apply has no operands");
                List<RetainedInitializerTransfer> checkedOperands = operands;
                boolean valid = switch (kind) {
                    case CONVERSION -> operation.isEmpty() && operands.size() == 1
                            && TypeRules.canExplicitlyConvert(operands.getFirst().type(), type);
                    case NARROWING -> operation.isEmpty() && operands.size() == 1
                            && (TypeRules.canExplicitlyConvert(operands.getFirst().type(), type)
                            || operands.getFirst().type().hasQualifier(TypeQualifier.NIL)
                            && !type.hasQualifier(TypeQualifier.NIL)
                            && operands.getFirst().type().withoutQualifiers()
                            .equals(type.withoutQualifiers()));
                    case SHORT_CIRCUIT -> operation.filter(value -> value.equals("and")
                                    || value.equals("or")).isPresent()
                            && operands.size() >= 2 && type.equals(PrimitiveType.BOOL)
                            && operands.stream().allMatch(operand -> truthTestable(operand.type()));
                    case RANGE -> operation.filter(value -> value.equals("..")
                                    || value.equals("...")).isPresent()
                            && operands.size() == 3
                            && type.withoutQualifiers() instanceof RangeType range
                            && operands.stream().allMatch(operand ->
                            sameType(operand.type(), range.elementType()));
                    case OPERATOR -> operation.filter(value ->
                            validOperatorApply(value, type, checkedOperands)).isPresent();
                };
                if (!valid) throw new IllegalArgumentException(
                        "retained apply operation/type/arity differs");
            }

            public Apply(ApplyKind kind, LyraType type,
                         List<RetainedInitializerTransfer> operands) {
                this(kind, type, Optional.empty(), operands);
            }
        }

        enum AlternativeKind {
            CONDITIONAL, COALESCE, MATCH;
            static AlternativeKind of(TypedExpressionKind kind) {
                return switch (kind) {
                    case CONDITIONAL -> CONDITIONAL;
                    case COALESCE -> COALESCE;
                    case MATCH, COND -> MATCH;
                    default -> throw new IllegalArgumentException("not an alternative transfer: " + kind);
                };
            }
        }

        /**
         * Closed lazy-join metadata. Prefixes are evaluated once; every branch
         * starts from that exact post-prefix state rather than another branch's
         * result. Selector steps are arm-local match tests and precede only that
         * arm's result.
         */
        record Alternative(AlternativeKind kind, LyraType type, List<Step> prefix,
                           List<Branch> branches, Optional<PredicateBinding> predicateBinding,
                           Set<Integer> reachableBranches)
                implements RetainedInitializerTransfer {
            public record Step(LyraType type, RetainedInitializerTransfer transfer) {
                public Step {
                    Objects.requireNonNull(type, "type");
                    Objects.requireNonNull(transfer, "transfer");
                    if (!type.equals(transfer.type())) {
                        throw new IllegalArgumentException("retained alternative step type differs from its transfer");
                    }
                }
            }

            public record Branch(Optional<Step> pattern, Optional<Step> guard,
                                 boolean wildcard, Optional<Step> result) {
                public Branch {
                    pattern = Objects.requireNonNull(pattern, "pattern");
                    guard = Objects.requireNonNull(guard, "guard");
                    result = Objects.requireNonNull(result, "result");
                    if (wildcard && pattern.isPresent()) {
                        throw new IllegalArgumentException(
                                "retained wildcard arm has a pattern transfer");
                    }
                }

                public List<Step> selectors() {
                    return java.util.stream.Stream.concat(pattern.stream(), guard.stream()).toList();
                }
            }

            public record PredicateBinding(DeclarationId declaration, BindingContract contract) {
                public PredicateBinding {
                    Objects.requireNonNull(declaration, "declaration");
                    Objects.requireNonNull(contract, "contract");
                }
            }

            public Alternative {
                Objects.requireNonNull(kind, "kind");
                Objects.requireNonNull(type, "type");
                prefix = List.copyOf(Objects.requireNonNull(prefix, "prefix"));
                branches = List.copyOf(Objects.requireNonNull(branches, "branches"));
                predicateBinding = Objects.requireNonNull(predicateBinding, "predicateBinding");
                reachableBranches = Set.copyOf(Objects.requireNonNull(
                        reachableBranches, "reachableBranches"));
                int branchCount = branches.size();
                if (prefix.stream().anyMatch(Objects::isNull) || branches.stream().anyMatch(Objects::isNull)
                        || reachableBranches.isEmpty()
                        || reachableBranches.stream().anyMatch(index -> index < 0 || index >= branchCount)) {
                    throw new IllegalArgumentException("retained alternative contains invalid reachability");
                }
                switch (kind) {
                    case CONDITIONAL -> {
                        boolean bindingValid = prefix.size() == 1 && (predicateBinding.isEmpty()
                                || predicateBinding.orElseThrow().contract().valueType().equals(
                                withoutNil(prefix.getFirst().type())));
                        if (prefix.size() != 1 || branches.size() != 2
                                || branches.stream().anyMatch(branch -> !branch.selectors().isEmpty()
                                || branch.wildcard())
                                || branches.getFirst().result().isEmpty()
                                || !truthTestable(prefix.getFirst().type())
                                || !sameType(branches.getFirst().result().orElseThrow().type(), type)
                                || branches.get(1).result().stream().anyMatch(result ->
                                !sameType(result.type(), type))
                                || branches.get(1).result().isEmpty()
                                && !type.equals(PrimitiveType.UNIT)
                                || !bindingValid) {
                            throw new IllegalArgumentException("retained conditional has an invalid prefix, branch or result type");
                        }
                    }
                    case COALESCE -> {
                        if (prefix.size() != 1 || branches.size() != 1
                                || !branches.getFirst().selectors().isEmpty()
                                || branches.getFirst().wildcard()
                                || branches.getFirst().result().isEmpty()
                                || prefix.size() == 1
                                && (!prefix.getFirst().type().hasQualifier(TypeQualifier.NIL)
                                || !sameType(withoutNil(prefix.getFirst().type()), type))
                                || !sameType(branches.getFirst().result().orElseThrow().type(), type)
                                || predicateBinding.isPresent()) {
                            throw new IllegalArgumentException("retained coalesce has an invalid prefix, branch or result type");
                        }
                    }
                    case MATCH -> {
                        Branch fallback = branches.isEmpty() ? null : branches.getLast();
                        if (prefix.size() > 1 || branches.isEmpty() || predicateBinding.isPresent()
                                || fallback == null || !fallback.wildcard()
                                || fallback.guard().isPresent()
                                || branches.stream().anyMatch(branch ->
                                !branch.wildcard() && branch.pattern().isEmpty())
                                || branches.subList(0, branches.size() - 1).stream()
                                .anyMatch(branch -> branch.wildcard() && branch.guard().isEmpty())
                                || prefix.isEmpty() && branches.stream()
                                .anyMatch(branch -> branch.guard().isPresent())
                                || branches.stream().anyMatch(branch -> branch.result().isEmpty()
                                || !sameType(branch.result().orElseThrow().type(), type))
                                || !validMatchSelectors(prefix, branches)) {
                            throw new IllegalArgumentException("retained match has an invalid prefix, arm or result type");
                        }
                    }
                }
            }

            public Alternative(
                    AlternativeKind kind, LyraType type, List<Step> prefix,
                    List<Branch> branches, Optional<PredicateBinding> predicateBinding) {
                this(kind, type, prefix, branches, predicateBinding,
                        java.util.stream.IntStream.range(0, branches.size()).boxed()
                                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
            }
        }

        /** Ordered lexical block forms, whose final form provides the value. */
        record Sequence(LyraType type, List<RetainedInitializerTransfer> steps)
                implements RetainedInitializerTransfer {
            public Sequence {
                Objects.requireNonNull(type, "type");
                steps = checkedTransfers(steps, "steps");
                if (steps.isEmpty() || !sameType(steps.getLast().type(), type)) {
                    throw new IllegalArgumentException("retained sequence result type differs from its final step");
                }
            }
        }

        /** A producer declaration identity and exact shared-cell contract introduced by a retained block. */
        record Declare(LyraType type, DeclarationId declaration, BindingContract contract,
                       Optional<DeclarationId> sharedCell,
                       RetainedInitializerTransfer initializer) implements RetainedInitializerTransfer {
            public Declare {
                Objects.requireNonNull(type, "type");
                Objects.requireNonNull(declaration, "declaration");
                Objects.requireNonNull(contract, "contract");
                sharedCell = Objects.requireNonNull(sharedCell, "sharedCell");
                Objects.requireNonNull(initializer, "initializer");
                if (!type.equals(PrimitiveType.UNIT)
                        || !sameType(initializer.type(), contract.valueType())
                        || sharedCell.isPresent() && (!contract.isMutable()
                        || sharedCell.orElseThrow().equals(declaration))) {
                    throw new IllegalArgumentException("retained declaration type/cell contract differs");
                }
            }
        }

        /** A routed producer storage update introduced by a retained block. */
        record Rebind(LyraType type, WriteTarget target, LyraType rootType,
                      LyraType targetType, Optional<DeclarationId> sharedCell,
                      RetainedInitializerTransfer value)
                implements RetainedInitializerTransfer {
            public Rebind {
                Objects.requireNonNull(type, "type");
                Objects.requireNonNull(target, "target");
                Objects.requireNonNull(rootType, "rootType");
                Objects.requireNonNull(targetType, "targetType");
                sharedCell = Objects.requireNonNull(sharedCell, "sharedCell");
                Objects.requireNonNull(value, "value");
                LyraType routed;
                try {
                    routed = ValueAlternative.typeAt(rootType, target.route());
                } catch (IllegalArgumentException invalidRoute) {
                    throw new IllegalArgumentException("retained rebind route differs from its root type", invalidRoute);
                }
                if (!type.equals(PrimitiveType.UNIT) || !routed.equals(targetType)
                        || !sameType(value.type(), targetType)
                        || sharedCell.isPresent()
                        && sharedCell.orElseThrow().equals(target.declaration())) {
                    throw new IllegalArgumentException("retained rebind value/result type differs");
                }
            }
        }

        enum ProjectionKind { ROUTE, LENGTH, STRING_INDEX }

        /** A routed projection or the exact array/string length operation. */
        record Project(LyraType type, RetainedInitializerTransfer base, LyraType baseType,
                       ProjectionKind kind, ProjectionPath route,
                       Optional<RetainedInitializerTransfer> index) implements RetainedInitializerTransfer {
            public Project {
                Objects.requireNonNull(type, "type");
                Objects.requireNonNull(base, "base");
                Objects.requireNonNull(baseType, "baseType");
                Objects.requireNonNull(kind, "kind");
                Objects.requireNonNull(route, "route");
                index = Objects.requireNonNull(index, "index");
                if (!sameType(base.type(), baseType)) {
                    throw new IllegalArgumentException("retained projection base type differs");
                }
                if (kind == ProjectionKind.LENGTH) {
                    LyraType plain = baseType.withoutQualifiers();
                    if (!route.isRoot() || index.isPresent() || !sameType(type, PrimitiveType.I32)
                            || plain != PrimitiveType.STRING && !(plain instanceof ArrayType)) {
                        throw new IllegalArgumentException("retained length projection differs");
                    }
                } else if (kind == ProjectionKind.STRING_INDEX) {
                    LyraType indexType = index.map(value -> value.type()).orElse(null);
                    if (!route.isRoot() || indexType == null
                            || baseType.withoutQualifiers() != PrimitiveType.STRING
                            || indexType.isNilable() || !indexType.isInteger()
                            || !sameType(type, PrimitiveType.CHAR)) {
                        throw new IllegalArgumentException("retained string index projection differs");
                    }
                } else {
                    LyraType routed;
                    try {
                        routed = ValueAlternative.typeAt(baseType, route);
                    } catch (IllegalArgumentException invalidRoute) {
                        throw new IllegalArgumentException("retained projection route differs from its base type", invalidRoute);
                    }
                    // The resolved element/member type and the access result
                    // type must agree exactly, including nilability: a route
                    // that reaches a @nil element may only certify a nilable
                    // result, and an incompatible route stays rejected.
                    if (route.isRoot() || !sameType(routed, type)
                            || index.isPresent() != route.steps().getLast().isArrayElement()
                            || index.stream().anyMatch(value ->
                            value.type().isNilable() || !value.type().isInteger())) {
                        throw new IllegalArgumentException(
                                "retained projection route/index/result differs: " + route);
                    }
                }
            }
        }

        /** Exact source identity required to initialize a nested retained nominal. */
        record ConstructionSite(DeclarationId nominalDeclaration, NominalSchema schema,
                                ModuleId moduleId, ScopeId scopeId, SourceSpan span,
                                FlowSiteId site, DeclarationId allocation,
                                List<LyraType> argumentTypes,
                                List<Optional<WriteTarget>> argumentTargets) {
            public ConstructionSite {
                Objects.requireNonNull(nominalDeclaration, "nominalDeclaration");
                Objects.requireNonNull(schema, "schema");
                Objects.requireNonNull(moduleId, "moduleId");
                Objects.requireNonNull(scopeId, "scopeId");
                Objects.requireNonNull(span, "span");
                Objects.requireNonNull(site, "site");
                Objects.requireNonNull(allocation, "allocation");
                argumentTypes = List.copyOf(Objects.requireNonNull(argumentTypes, "argumentTypes"));
                argumentTargets = List.copyOf(Objects.requireNonNull(argumentTargets, "argumentTargets"));
                if (!moduleId.sourceId().equals(span.sourceId())
                        || !schema.type().id().module().moduleId().equals(moduleId)
                        || !RetainedAllocationDerivation.isOrdinarySourceDeclaration(
                        nominalDeclaration)
                        || !RetainedAllocationDerivation.isOrdinarySourceAllocation(
                        site, allocation)
                        || !argumentTypes.equals(schema.constructorParameters())
                        || argumentTargets.size() != argumentTypes.size()
                        || argumentTargets.stream().anyMatch(Objects::isNull)) {
                    throw new IllegalArgumentException("retained construction source/schema/arguments differ");
                }
            }

            public NominalType nominalType() { return schema.type(); }
        }

        /** Nested construction routed through the producer-bound nominal factory. */
        record Construct(ConstructionSite site, List<RetainedInitializerTransfer> arguments)
                implements RetainedInitializerTransfer {
            public Construct {
                Objects.requireNonNull(site, "site");
                arguments = checkedTransfers(arguments, "arguments");
                boolean valid = site.argumentTargets().size() == arguments.size();
                for (int index = 0; valid && index < arguments.size(); index++) {
                    valid = sameType(arguments.get(index).type(), site.argumentTypes().get(index));
                }
                if (!valid) throw new IllegalArgumentException("retained construction arity/type differs");
            }

            @Override public LyraType type() { return site.nominalType(); }
        }

        /** A callable-value call, with target evaluation before left-to-right arguments. */
        record CallableCall(RetainedInitializerTransfer target, FunctionType function,
                            SourceSpan span, FlowSiteId site,
                            List<RetainedInitializerTransfer> arguments,
                            List<Optional<WriteTarget>> argumentTargets) implements RetainedInitializerTransfer {
            public CallableCall {
                Objects.requireNonNull(target, "target");
                Objects.requireNonNull(function, "function");
                Objects.requireNonNull(span, "span");
                Objects.requireNonNull(site, "site");
                arguments = checkedTransfers(arguments, "arguments");
                argumentTargets = checkedTargets(argumentTargets, function.arity());
                if (!RetainedAllocationDerivation.isOrdinarySourceSite(site)
                        || !sameCallableType(target.type(), function)
                        || arguments.size() != function.arity()) {
                    throw new IllegalArgumentException("retained callable target/arity differs");
                }
                requireArgumentTypes(function, arguments);
            }

            @Override public LyraType type() { return function.returnType(); }
        }

        /** Callback-loop selection and contracts, retained without callback source bodies. */
        record Loop(TypedExpressionKind kind, LyraType type, SourceSpan span, FlowSiteId site,
                    RetainedInitializerTransfer input, RetainedInitializerTransfer action,
                    Optional<FunctionType> inputFunction,
                    FunctionType actionFunction) implements RetainedInitializerTransfer {
            public Loop {
                if (kind != TypedExpressionKind.ITER && kind != TypedExpressionKind.WHILE) {
                    throw new IllegalArgumentException("retained loop kind is invalid");
                }
                Objects.requireNonNull(type, "type");
                Objects.requireNonNull(span, "span");
                Objects.requireNonNull(site, "site");
                Objects.requireNonNull(input, "input");
                Objects.requireNonNull(action, "action");
                inputFunction = Objects.requireNonNull(inputFunction, "inputFunction");
                Objects.requireNonNull(actionFunction, "actionFunction");
                if (!RetainedAllocationDerivation.isOrdinarySourceSite(site)
                        || (kind == TypedExpressionKind.WHILE) != inputFunction.isPresent()
                        || !sameType(type, PrimitiveType.UNIT)
                        || !sameCallableType(action.type(), actionFunction)
                        || !CallbackLoop.valid(kind, type, List.of(input.type(), action.type()))
                        || kind == TypedExpressionKind.WHILE
                        && !sameCallableType(input.type(), inputFunction.orElseThrow())) {
                    throw new IllegalArgumentException("retained loop input/action contract differs");
                }
            }
        }

        /** Exact producer array allocation identity used by a dynamic composite. */
        record ArrayAllocation(ModuleId moduleId, ScopeId scopeId, SourceSpan span, FlowSiteId site,
                               DeclarationId allocation, ArrayType type) {
            public ArrayAllocation {
                Objects.requireNonNull(moduleId, "moduleId");
                Objects.requireNonNull(scopeId, "scopeId");
                Objects.requireNonNull(span, "span");
                Objects.requireNonNull(site, "site");
                Objects.requireNonNull(allocation, "allocation");
                Objects.requireNonNull(type, "type");
                if (!moduleId.sourceId().equals(span.sourceId())
                        || !RetainedAllocationDerivation.isOrdinarySourceAllocation(
                        site, allocation)) {
                    throw new IllegalArgumentException("retained array allocation source/identity differs");
                }
            }
            static ArrayAllocation of(TypedExpression expression, TypedSemanticInput graph) {
                if (!(expression.type().withoutQualifiers() instanceof ArrayType type)) {
                    throw new IllegalArgumentException("retained array allocation has no array type");
                }
                FlowSiteId site = graph.flowSiteId(expression);
                return new ArrayAllocation(ModuleId.fromSourceId(expression.span().sourceId()),
                        graph.flowScopeId(expression), expression.span(), site,
                        new DeclarationId(Long.MAX_VALUE - site.ordinal()), type);
            }
        }

        private static boolean validOperatorApply(
                String operation, LyraType type,
                List<RetainedInitializerTransfer> operands) {
            List<LyraType> operandTypes = operands.stream()
                    .map(RetainedInitializerTransfer::type).toList();
            int arity = operands.size();
            boolean validArity = switch (operation) {
                case "+", "*", "<", "<=", ">", ">=", "==", "!=", "eq?", "!eq?", "xor" -> arity >= 2;
                case "-", "/" -> arity >= 1;
                case "%", "^" -> arity == 2;
                case "not", "++", "--" -> arity == 1;
                default -> false;
            };
            if (!validArity) {
                return false;
            }
            return switch (operation) {
                case "xor" -> sameType(type, PrimitiveType.BOOL)
                        && operandTypes.stream().allMatch(
                        RetainedInitializerTransfer::truthTestable);
                case "not" -> sameType(type, PrimitiveType.BOOL)
                        && truthTestable(operandTypes.getFirst());
                case "<", "<=", ">", ">=" -> sameType(type, PrimitiveType.BOOL)
                        && operandTypes.stream().noneMatch(LyraType::isNilable)
                        && TypeRules.commonNumericType(operandTypes).isPresent();
                case "==", "!=" -> sameType(type, PrimitiveType.BOOL)
                        && (TypeRules.commonType(operandTypes).isPresent()
                        || operandTypes.stream().anyMatch(value ->
                        value.withoutQualifiers() == PrimitiveType.BOOL)
                        && operandTypes.stream().allMatch(
                        RetainedInitializerTransfer::truthTestable));
                case "eq?", "!eq?" -> {
                    LyraType base = operandTypes.getFirst().withoutQualifiers();
                    yield sameType(type, PrimitiveType.BOOL)
                            && (base instanceof FunctionType
                            || base instanceof ArrayType
                            || base instanceof NominalType)
                            && operandTypes.stream().allMatch(value -> !value.isNilable()
                            && value.withoutQualifiers().equals(base));
                }
                case "+" -> operandTypes.stream().allMatch(value ->
                        !value.isNilable()
                                && value.withoutQualifiers() == PrimitiveType.STRING)
                        ? sameType(type, PrimitiveType.STRING)
                        : type.isNumeric() && !type.isNilable()
                        && operandTypes.stream().allMatch(type::equals);
                case "-", "*", "^", "++", "--" -> type.isNumeric()
                        && !type.isNilable()
                        && operandTypes.stream().allMatch(type::equals);
                case "%" -> type.isInteger() && !type.isNilable()
                        && operandTypes.stream().allMatch(type::equals);
                case "/" -> {
                    boolean integers = operandTypes.stream().allMatch(LyraType::isInteger);
                    yield type.isNumeric() && !type.isNilable()
                            && (integers
                            ? type.isFloating()
                            && TypeRules.commonNumericType(operandTypes).isPresent()
                            : operandTypes.stream().allMatch(type::equals));
                }
                default -> false;
            };
        }

        private static boolean validMatchSelectors(
                List<Alternative.Step> prefix,
                List<Alternative.Branch> branches) {
            for (Alternative.Branch branch : branches) {
                if (branch.guard().stream().anyMatch(guard ->
                        !truthTestable(guard.type()))) {
                    return false;
                }
                if (branch.pattern().isEmpty()) {
                    continue;
                }
                LyraType pattern = branch.pattern().orElseThrow().type();
                if (prefix.isEmpty()) {
                    if (!truthTestable(pattern)) {
                        return false;
                    }
                } else if (TypeRules.commonType(
                        List.of(prefix.getFirst().type(), pattern)).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        private static boolean truthTestable(LyraType type) {
            LyraType base = type.withoutQualifiers();
            return type.isNilable() || base instanceof PrimitiveType
                    || base instanceof ArrayType || base instanceof TupleType
                    || base instanceof FunctionType;
        }

        private static List<RetainedInitializerTransfer> checkedTransfers(
                List<RetainedInitializerTransfer> values, String name) {
            values = List.copyOf(Objects.requireNonNull(values, name));
            if (values.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException(name + " contains null");
            return values;
        }

        private static boolean sameType(LyraType left, LyraType right) {
            return left.equals(right);
        }

        private static boolean sameCallableType(LyraType target, FunctionType function) {
            return target.withoutQualifiers().equals(function);
        }

        private static boolean compatibleType(LyraType source, LyraType target) {
            return TypeRules.canImplicitlyConvert(source, target);
        }

        private static LyraType withoutNil(LyraType type) {
            LyraType base = type.withoutQualifiers();
            return type.hasQualifier(TypeQualifier.MUT)
                    ? base.withQualifier(TypeQualifier.MUT) : base;
        }

        private static void requireArgumentTypes(
                FunctionType function, List<RetainedInitializerTransfer> arguments) {
            for (int index = 0; index < arguments.size(); index++) {
                if (!compatibleType(arguments.get(index).type(), function.parameterType(index))) {
                    throw new IllegalArgumentException("retained callable argument type differs at " + index);
                }
            }
        }

        private static List<Optional<WriteTarget>> checkedTargets(
                List<Optional<WriteTarget>> values, int arity) {
            values = List.copyOf(Objects.requireNonNull(values, "argumentTargets"));
            if (values.size() != arity || values.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("retained argument targets differ");
            }
            return values;
        }
    }

    /**
     * Exact bounded source evidence for a nominal construction nested in a
     * callable summary.  It is static compiler proof only; runtime construction
     * still uses the authenticated producer-bound factory.
     */
    public record RetainedConstruction(
            CallableCallReference call,
            NominalSchema schema,
            ModuleId moduleId,
            ScopeId scopeId,
            DeclarationId allocation,
            List<Optional<WriteTarget>> argumentTargets) {
        public RetainedConstruction {
            Objects.requireNonNull(call, "call");
            Objects.requireNonNull(schema, "schema");
            Objects.requireNonNull(moduleId, "moduleId");
            Objects.requireNonNull(scopeId, "scopeId");
            Objects.requireNonNull(allocation, "allocation");
            argumentTargets = List.copyOf(argumentTargets);
            if (call.kind() != CallableCallReference.Kind.CONSTRUCTION
                    || call.siteId().isEmpty()
                    || !RetainedAllocationDerivation.isOrdinarySourceSite(
                    call.siteId().orElseThrow())
                    || call.targetDeclaration().isEmpty()
                    || !RetainedAllocationDerivation.isOrdinarySourceDeclaration(
                    call.targetDeclaration().orElseThrow())
                    || !(call.target().only() instanceof ValueFormula.Constructor constructor)
                    || !call.targetDeclaration().orElseThrow().equals(constructor.declaration())
                    || !schema.type().equals(constructor.nominalType())
                    || !schema.type().id().module().moduleId().equals(moduleId)
                    || !schema.constructorParameters().equals(call.arguments().stream()
                    .map(FormulaAlternatives::rootType).toList())
                    || argumentTargets.size() != call.arguments().size()
                    || !RetainedAllocationDerivation.isOrdinarySourceAllocation(
                    call.siteId().orElseThrow(), allocation)
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
            return schema.type();
        }

        private boolean certifies(NominalObjectFact fact, Set<RouteProof> routes) {
            OwnershipWitness witness = fact.ownership();
            return routes.contains(new RouteProof(site(), fact.route()))
                    && fact.identity().ownerModule().equals(moduleId)
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
            if (!moduleId.sourceId().equals(sourceSpan.sourceId())
                    || !RetainedAllocationDerivation.isOrdinarySummaryAllocation(
                    originSite, allocation)) {
                throw new IllegalArgumentException(
                        "allocation provenance source/identity differs");
            }
        }

        public FlowSiteId flowSite() {
            return originSite;
        }
    }

    /**
     * A consumer may re-project a retained callable through its own typed source.
     * Build that finite route proof once, independently of the candidate flow
     * facts; identity/capture authentication still belongs to this certificate.
     */
    public java.util.function.Predicate<CallableFlow> callableTransferVerifier(
            TypedSemanticInput consumer, CallableSummarySet currentSummaries) {
        Objects.requireNonNull(consumer, "consumer");
        if (consumer.resolvedGraph().sessionFlowCertificate().orElse(null) != this) {
            throw new IllegalArgumentException("callable route consumer has a different predecessor");
        }
        Set<RouteProof> derived = new RouteDerivation(boundaryState,
                CallableSummarySet.combine(callableSummaries, currentSummaries),
                retainedNominals, retainedConstructions, consumer).derive();
        return callable -> (routeProofs.contains(RouteProof.of(callable))
                || derived.contains(RouteProof.of(callable)))
                && certifiesCallableTransferIdentity(callable);
    }

    /** Identity-only atoms and exact routes, never executable retained values. */
    private record RouteProof(Object origin, ProjectionPath route) {
        private RouteProof {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(route, "route");
            if (!(origin instanceof LambdaId || origin instanceof DeclarationId || origin instanceof FlowSiteId)) {
                throw new IllegalArgumentException("route proof needs an exact lambda, intrinsic or construction site");
            }
        }

        private static RouteProof of(CallableFlow callable) {
            return new RouteProof(callable.lambdaId().<Object>map(value -> value)
                    .orElseGet(() -> callable.intrinsicDeclarationId().orElseThrow()), callable.route());
        }
    }

    /**
     * Finite projection reachability over already-issued transfer/summary edges.
     * This does not evaluate values, calls, captures, writes or control flow: it
     * propagates identity atoms only, selecting and prefixing their exact routes.
     * Nodes/edges are discarded after issuance; only the closed route set remains.
     */
    private static final class RouteDerivation {
        private final CallableSummarySet summaries;
        private final Map<String, ResolvedNominal> nominals = new TreeMap<>();
        private final List<RouteNode> nodes = new ArrayList<>();
        private final Map<DeclarationId, RouteNode> declarations = new TreeMap<>();
        private final Map<LambdaId, RouteNode> returns = new TreeMap<>();
        private final Map<SummaryCallId, RouteNode> calls = new TreeMap<>();
        private final List<RouteEdge> edges = new ArrayList<>();
        private final List<RouteInvocation> invocations = new ArrayList<>();
        private final Map<TypedExpression, RouteNode> expressions = new IdentityHashMap<>();

        private static final class RouteNode {
            private final LyraType type;
            private final Set<RouteProof> proofs = new LinkedHashSet<>();
            private RouteNode(LyraType type) { this.type = type; }
        }
        private record RouteEdge(RouteNode source, RouteNode target,
                                 ProjectionPath select, ProjectionPath prefix) { }
        private record RouteInvocation(RouteNode target, List<RouteNode> arguments,
                                       RouteNode result) { }

        private RouteDerivation(BindingFlowState boundary, CallableSummarySet summaries,
                                Map<String, RetainedNominal> nominals,
                                Map<SummaryCallId, RetainedConstruction> constructions,
                                TypedSemanticInput graph) {
            this.summaries = summaries;
            nominals.forEach((name, nominal) -> this.nominals.put(name, nominal.nominal()));
            graph.resolvedGraph().nominals().forEach(nominal -> this.nominals.putIfAbsent(
                    nominal.schema().type().canonicalSpelling(), nominal));
            graph.contractsByDeclaration().forEach((id, contract) -> declaration(id, contract.valueType()));
            summaries.orderedSummaries().forEach(summary -> {
                returns.put(summary.lambdaId(), node(summary.signature().returnType()));
                summary.parameters().forEach(parameter -> declaration(parameter.declarationId(), parameter.type()));
                summary.captures().forEach(capture -> {
                    RouteNode captured = declaration(capture.declarationId(), capture.type());
                    capture.sharedCellId().ifPresent(cell -> edge(declaration(cell, capture.type()), captured));
                });
                summary.callReferences().forEach(call -> calls.put(call.id(), node(((FunctionType) call.target().rootType().withoutQualifiers()).returnType())));
            });
            boundary.bindings().forEach((id, value) -> values(value.alternatives(), declaration(id, value.contract().valueType())));
            boundary.sharedCells().forEach((id, value) -> values(value, declaration(id, value.alternatives().getFirst().type())));
            boundary.objects().values().forEach(object -> {
                ResolvedNominal nominal = this.nominals.get(object.schema().type().canonicalSpelling());
                if (nominal != null) object.fields().forEach((index, value) -> values(value,
                        declaration(nominal.members().get(index), object.schema().members().get(index).type())));
            });
            nominals.values().forEach(nominal -> {
                for (int index = 0; index < nominal.memberInitializers().size(); index++) {
                    int member = index;
                    nominal.memberInitializer(index).ifPresent(transfer -> edge(transfer(transfer),
                            declaration(nominal.nominal().members().get(member), transfer.type())));
                }
            });
            graph.declarations().forEach(value -> {
                RouteNode target = declaration(value.id(), value.contract().map(BindingContract::valueType).orElse(null));
                value.initializerLambda().ifPresent(lambda -> target.proofs.add(new RouteProof(lambda, ProjectionPath.root())));
                value.initializer().ifPresent(initializer -> edge(expression(initializer, graph), target));
            });
            // Current source is inspected transiently, never stored in a certificate
            // or forced into a producer-only construction evidence record.
            graph.expressions().forEach(expression -> expression(expression, graph));
            summaries.orderedSummaries().forEach(summary -> {
                edge(formulas(summary.returnFormula().alternatives()), returns.get(summary.lambdaId()));
                summary.writes().forEach(write -> formulas(write.value()));
                summary.ownershipRequirements().forEach(requirement -> formulas(requirement.value()));
                for (CallableCallReference call : summary.callReferences()) {
                    RouteNode result = calls.get(call.id());
                    List<RouteNode> arguments = call.arguments().stream().map(this::formulas).toList();
                    if (call.kind() == CallableCallReference.Kind.CONSTRUCTION) {
                        RetainedConstruction construction = constructions.get(call.id());
                        if (construction != null) result.proofs.add(new RouteProof(construction.site(), ProjectionPath.root()));
                        else graph.expressions().stream().filter(expression -> expression.kind() == TypedExpressionKind.CONSTRUCTION
                                && call.siteId().filter(graph.flowSiteId(expression)::equals).isPresent()
                                && expression.span().equals(call.span())
                                && expression.declarationId().equals(call.targetDeclaration()))
                                .findFirst().ifPresent(expression -> edge(expression(expression, graph), result));
                    } else {
                        RouteNode target = formulas(call.target());
                        call.targetLambda().ifPresent(lambda -> target.proofs.add(new RouteProof(lambda, ProjectionPath.root())));
                        invocations.add(new RouteInvocation(target, arguments, result));
                    }
                }
            });
        }

        private RouteNode node(LyraType type) {
            RouteNode node = new RouteNode(type);
            nodes.add(node);
            return node;
        }

        private RouteNode declaration(DeclarationId id, LyraType type) {
            return declarations.computeIfAbsent(id, ignored -> node(type));
        }

        private void edge(RouteNode source, RouteNode target) {
            edges.add(new RouteEdge(source, target, ProjectionPath.root(), ProjectionPath.root()));
        }

        private void select(RouteNode source, ProjectionPath route, RouteNode target, ProjectionPath prefix) {
            // Nominal heap members are separately certified storage, not flattened
            // object atoms. Resolve only the exact schema/member selector issued
            // by the source; structural selectors retain ordinary suffix semantics.
            for (int index = 0; index < route.steps().size(); index++) {
                if (route.steps().get(index) instanceof io.mindspice.lyra.compiler.semantic.flow.ProjectionStep.NominalMember member) {
                    ResolvedNominal nominal = nominals.get(member.owner().canonicalSpelling());
                    if (nominal != null) {
                        select(declaration(nominal.members().get(member.index()), member.type()),
                                route.suffix(index + 1), target, prefix);
                    }
                    return;
                }
            }
            edges.add(new RouteEdge(source, target, route, prefix));
        }

        private void values(ValueAlternatives values, RouteNode node) {
            for (ValueAlternative value : values) {
                value.objects().forEach(object -> node.proofs.add(new RouteProof(
                        object.identity().allocationSite(), object.route())));
                value.callableFlows().forEach(callable -> {
                    node.proofs.add(RouteProof.of(callable));
                    callable.capturedValues().forEach((id, captured) -> values(captured,
                            declaration(id, captured.alternatives().getFirst().type())));
                    callable.sharedCellSnapshots().forEach((id, captured) -> values(captured,
                            declaration(id, captured.alternatives().getFirst().type())));
                });
            }
        }

        private RouteNode transfer(RetainedInitializerTransfer transfer) {
            RouteNode result = node(transfer.type());
            switch (transfer) {
                case RetainedInitializerTransfer.Lambda lambda -> result.proofs.add(new RouteProof(lambda.lambda(), ProjectionPath.root()));
                case RetainedInitializerTransfer.Value value -> values(value.value(), result);
                case RetainedInitializerTransfer.Reference reference -> select(
                        declaration(reference.declaration(), reference.declarationType()), reference.route(), result, ProjectionPath.root());
                case RetainedInitializerTransfer.Composite composite -> {
                    for (int index = 0; index < composite.elements().size(); index++) {
                        edges.add(new RouteEdge(transfer(composite.elements().get(index)), result, ProjectionPath.root(),
                                composite.arrayLiteral() ? ProjectionPath.arrayElement(index) : ProjectionPath.tupleMember(index)));
                    }
                }
                case RetainedInitializerTransfer.Project project -> {
                    RouteNode base = transfer(project.base());
                    project.index().ifPresent(this::transfer);
                    if (project.kind() == RetainedInitializerTransfer.ProjectionKind.ROUTE)
                        select(base, project.route(), result, ProjectionPath.root());
                }
                case RetainedInitializerTransfer.Construct construct -> {
                    result.proofs.add(new RouteProof(construct.site().site(), ProjectionPath.root()));
                    construct.arguments().forEach(this::transfer);
                }
                case RetainedInitializerTransfer.Call call -> invocations.add(new RouteInvocation(
                        declaration(call.target(), call.function()), call.arguments().stream().map(this::transfer).toList(), result));
                case RetainedInitializerTransfer.CallableCall call -> invocations.add(new RouteInvocation(
                        transfer(call.target()), call.arguments().stream().map(this::transfer).toList(), result));
                case RetainedInitializerTransfer.Sequence sequence -> {
                    List<RouteNode> steps = sequence.steps().stream().map(this::transfer).toList();
                    edge(steps.getLast(), result);
                }
                case RetainedInitializerTransfer.Declare declare -> edge(transfer(declare.initializer()),
                        declaration(declare.declaration(), declare.contract().valueType()));
                case RetainedInitializerTransfer.Rebind rebind -> {
                    RouteNode value = transfer(rebind.value());
                    writeRoute(value, rebind.target(), rebind.rootType());
                    edge(value, result);
                }
                case RetainedInitializerTransfer.Alternative alternative -> {
                    alternative.prefix().forEach(step -> {
                        RouteNode value = transfer(step.transfer());
                        if (alternative.kind() == RetainedInitializerTransfer.AlternativeKind.COALESCE) edge(value, result);
                    });
                    alternative.branches().forEach(branch -> {
                        branch.selectors().forEach(step -> transfer(step.transfer()));
                        branch.result().ifPresent(step -> edge(transfer(step.transfer()), result));
                    });
                }
                case RetainedInitializerTransfer.Apply apply -> {
                    List<RouteNode> operands = apply.operands().stream().map(this::transfer).toList();
                    if (apply.kind() == RetainedInitializerTransfer.ApplyKind.CONVERSION
                            || apply.kind() == RetainedInitializerTransfer.ApplyKind.NARROWING) edge(operands.getLast(), result);
                }
                case RetainedInitializerTransfer.Loop loop -> {
                    transfer(loop.input());
                    transfer(loop.action());
                }
            }
            return result;
        }

        private RouteNode expression(TypedExpression expression, TypedSemanticInput graph) {
            RouteNode existing = expressions.get(expression);
            if (existing != null) return existing;
            RouteNode result = node(expression.type());
            expressions.put(expression, result);
            List<RouteNode> children = expression.children().stream().map(child -> expression(child, graph)).toList();
            Optional<RetainedInitializerTransfer.Reference> reference = retainedReference(expression, graph);
            if (reference.isPresent()) {
                var value = reference.orElseThrow();
                select(declaration(value.declaration(), value.declarationType()), value.route(), result, ProjectionPath.root());
                return result;
            }
            switch (expression.kind()) {
                case LAMBDA -> expression.lambdaId().ifPresent(lambda -> result.proofs.add(new RouteProof(lambda, ProjectionPath.root())));
                case CONSTRUCTION -> result.proofs.add(new RouteProof(graph.flowSiteId(expression), ProjectionPath.root()));
                case ARRAY_LITERAL, TUPLE_LITERAL -> {
                    for (int index = 0; index < children.size(); index++) edges.add(new RouteEdge(children.get(index), result,
                            ProjectionPath.root(), expression.kind() == TypedExpressionKind.ARRAY_LITERAL
                            ? ProjectionPath.arrayElement(index) : ProjectionPath.tupleMember(index)));
                }
                case CALLABLE_CALL -> invocations.add(new RouteInvocation(children.getFirst(), children.subList(1, children.size()), result));
                case DIRECT_CALL, NAMESPACE_DIRECT_CALL -> expression.link().flatMap(value -> value.declarationId())
                        .ifPresent(id -> invocations.add(new RouteInvocation(declaration(id, null), children, result)));
                case BLOCK, CONVERSION, NARROWING -> { if (!children.isEmpty()) edge(children.getLast(), result); }
                case CONDITIONAL -> {
                    for (int index = 1; index < children.size(); index++) edge(children.get(index), result);
                    expression.predicateBinding().ifPresent(id -> edge(children.getFirst(), declaration(id,
                            graph.contract(id).orElseThrow().valueType())));
                }
                case COALESCE -> children.forEach(child -> edge(child, result));
                case MATCH, COND -> expression.match().orElseThrow().arms().forEach(arm -> edge(children.get(arm.resultChild()), result));
                case DECLARATION -> expression.declarationId().ifPresent(id -> {
                    if (!children.isEmpty()) edge(children.getFirst(), declaration(id, graph.contract(id).orElseThrow().valueType()));
                });
                case REBINDING -> {
                    if (children.size() == 2) {
                        WriteTarget.of(expression.children().getFirst(), graph).ifPresent(target -> writeRoute(children.get(1), target,
                                graph.contract(target.declaration()).orElseThrow().valueType()));
                        edge(children.get(1), result);
                    }
                }
                case INDEX_ACCESS -> {
                    if (expression.children().getFirst().type().withoutQualifiers() instanceof ArrayType)
                        select(children.getFirst(), ProjectionPath.of(indexRoute(expression.children().get(1))), result, ProjectionPath.root());
                }
                case MEMBER_ACCESS -> {
                    if (expression.tupleIndex().isPresent()) select(children.getFirst(),
                            ProjectionPath.tupleMember(expression.tupleIndex().orElseThrow().intValueExact()), result, ProjectionPath.root());
                    else expression.declarationId().ifPresent(id -> edge(declaration(id, expression.type()), result));
                }
                default -> { }
            }
            return result;
        }

        private void writeRoute(RouteNode value, WriteTarget write, LyraType rootType) {
            RouteNode target = declaration(write.declaration(), rootType);
            ProjectionPath route = write.route();
            for (int index = 0; index < route.steps().size(); index++) {
                if (route.steps().get(index) instanceof io.mindspice.lyra.compiler.semantic.flow.ProjectionStep.NominalMember member) {
                    ResolvedNominal nominal = nominals.get(member.owner().canonicalSpelling());
                    if (nominal != null) target = declaration(nominal.members().get(member.index()), member.type());
                    route = route.suffix(index + 1);
                    break;
                }
            }
            edges.add(new RouteEdge(value, target, ProjectionPath.root(), route));
        }

        private RouteNode formulas(FormulaAlternatives alternatives) {
            RouteNode result = node(alternatives.rootType());
            for (ValueFormula formula : alternatives.formulas()) {
                switch (formula) {
                    case ValueFormula.Lambda lambda -> {
                        result.proofs.add(new RouteProof(lambda.lambdaId(), lambda.resultRoute()));
                        CallableSummary summary = summaries.summary(lambda.lambdaId()).orElse(null);
                        lambda.capturedValues().forEach((id, value) -> {
                            RouteNode capture = formulas(value);
                            if (summary != null) summary.captures().stream()
                                    .filter(placeholder -> placeholder.captureId().equals(id)).findFirst()
                                    .ifPresent(placeholder -> edge(capture,
                                            declaration(placeholder.declarationId(), placeholder.type())));
                        });
                    }
                    case ValueFormula.Declaration declaration -> select(declaration(declaration.declarationId(), null),
                            declaration.declarationRoute(), result, declaration.resultRoute());
                    case ValueFormula.Parameter parameter -> select(declaration(parameter.declarationId(), null),
                            parameter.parameterRoute(), result, parameter.resultRoute());
                    case ValueFormula.Capture capture -> select(declaration(capture.declarationId(), null),
                            capture.captureRoute(), result, capture.resultRoute());
                    case ValueFormula.CallResult call -> {
                        RouteNode source = calls.get(call.callId());
                        if (source != null) select(source, call.callRoute(), result, call.resultRoute());
                    }
                    case ValueFormula.ObjectReference object -> {
                        if (object.sourceRoute().isRoot()) result.proofs.add(new RouteProof(
                                object.object().identity().allocationSite(), object.resultRoute()));
                        else select(node(object.object().identity().type()), object.sourceRoute(), result, object.resultRoute());
                    }
                    case ValueFormula.Constructor ignored -> { }
                    case ValueFormula.FreshAllocation ignored -> { }
                    case ValueFormula.Scalar ignored -> { }
                    case ValueFormula.Opaque ignored -> { }
                }
            }
            return result;
        }

        private Set<RouteProof> derive() {
            Set<RouteEdge> connected = new LinkedHashSet<>(edges);
            boolean changed;
            do {
                changed = false;
                for (RouteInvocation invocation : invocations) {
                    for (RouteProof target : invocation.target().proofs) {
                        if (!target.route().isRoot() || !(target.origin() instanceof LambdaId lambda)) continue;
                        CallableSummary summary = summaries.summary(lambda).orElse(null);
                        if (summary == null || summary.parameters().size() != invocation.arguments().size()) continue;
                        changed |= connected.add(new RouteEdge(returns.get(lambda), invocation.result(), ProjectionPath.root(), ProjectionPath.root()));
                        // Parameter routes belong to this invocation, never to a
                        // global parameter bucket shared by unrelated call sites.
                        for (ValueFormula formula : summary.returnFormula().alternatives().formulas()) {
                            if (formula instanceof ValueFormula.Parameter parameter) {
                                changed |= connected.add(new RouteEdge(invocation.arguments().get(parameter.parameterIndex()),
                                        invocation.result(), parameter.parameterRoute(), parameter.resultRoute()));
                            }
                        }
                    }
                }
                for (RouteEdge edge : connected) {
                    for (RouteProof proof : List.copyOf(edge.source().proofs)) {
                        Optional<ProjectionPath> suffix = edge.select().suffixOf(proof.route());
                        if (suffix.isEmpty()) continue;
                        RouteProof routed = new RouteProof(proof.origin(), edge.prefix().compose(suffix.orElseThrow()));
                        if (accepts(edge.target(), routed)) changed |= edge.target().proofs.add(routed);
                    }
                }
            } while (changed);
            Set<RouteProof> result = new LinkedHashSet<>();
            nodes.forEach(node -> result.addAll(node.proofs));
            return Set.copyOf(result);
        }

        private static boolean accepts(RouteNode node, RouteProof proof) {
            if (node.type == null) return true;
            try {
                LyraType type = ValueAlternative.typeAt(node.type, proof.route()).withoutQualifiers();
                return proof.origin() instanceof FlowSiteId ? type instanceof NominalType : type instanceof FunctionType;
            } catch (IllegalArgumentException invalidRoute) {
                return false;
            }
        }
    }

    private record CallableProofKey(
            Optional<LambdaId> lambda,
            Optional<DeclarationId> intrinsic,
            ProjectionPath route,
            Map<DeclarationId, ValueAlternatives> capturedValues,
            Set<DeclarationId> sharedCells,
            Optional<FlowSiteId> creationSite,
            Optional<FlowSiteId> retainedCellContext) {
        private CallableProofKey {
            lambda = Objects.requireNonNull(lambda, "lambda");
            intrinsic = Objects.requireNonNull(intrinsic, "intrinsic");
            route = Objects.requireNonNull(route, "route");
            capturedValues = Map.copyOf(Objects.requireNonNull(capturedValues, "capturedValues"));
            sharedCells = Set.copyOf(Objects.requireNonNull(sharedCells, "sharedCells"));
            creationSite = Objects.requireNonNull(creationSite, "creationSite");
            retainedCellContext = Objects.requireNonNull(
                    retainedCellContext, "retainedCellContext");
        }

        private CallableProofKey atRoute(ProjectionPath route) {
            return new CallableProofKey(lambda, intrinsic, route, capturedValues,
                    sharedCells, creationSite, retainedCellContext);
        }

        private static CallableProofKey of(CallableFlow callable) {
            return new CallableProofKey(
                    callable.lambdaId(), callable.intrinsicDeclarationId(), callable.route(),
                    callable.capturedValues(), callable.sharedCellSnapshots().keySet(),
                    callable.creationSite(), callable.retainedCellContext());
        }
    }

    /**
     * Exact object-allocation proof key.  Unlike a heap-state lookup, this
     * records the full ownership witness, so a forged witness for a real
     * identity is not certified.
     */
    private record ObjectProofKey(
            io.mindspice.lyra.compiler.semantic.flow.NominalObjectIdentity identity,
            ProjectionPath route,
            String originKey) {
        private ObjectProofKey {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(originKey, "originKey");
        }

        private static ObjectProofKey of(NominalObjectFact fact) {
            return new ObjectProofKey(fact.identity(), fact.route(),
                    ownershipKey(fact.ownership()));
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
