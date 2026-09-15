package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.LyraType;
import io.mindspice.lyra.compiler.types.TupleType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Complete immutable index of exactly one solved summary per reachable lambda.
 * Callable selection is performed only from the supplied finite target value;
 * this class never scans all signatures as a substitute for a missing fact.
 */
public final class CallableSummarySet implements ImmutablePhaseArtifact {
    private final Map<LambdaId, CallableSummary> summaries;
    private final Map<DeclarationId, LambdaId> lambdaByDeclaration;
    private final Map<DeclarationId, ModuleId> intrinsicDeclarations;
    private final List<CallableScc> components;
    private final Set<DeclarationId> deferredCallableDeclarations;
    private final SummaryLimits limits;

    public CallableSummarySet(
            List<CallableSummary> summaries,
            Map<DeclarationId, LambdaId> lambdaByDeclaration,
            Map<DeclarationId, ModuleId> intrinsicDeclarations,
            List<CallableScc> components) {
        this(summaries, lambdaByDeclaration, intrinsicDeclarations, components, Set.of());
    }

    public CallableSummarySet(
            List<CallableSummary> summaries,
            Map<DeclarationId, LambdaId> lambdaByDeclaration,
            Map<DeclarationId, ModuleId> intrinsicDeclarations,
            List<CallableScc> components,
            Set<DeclarationId> deferredCallableDeclarations) {
        Objects.requireNonNull(summaries, "summaries");
        Objects.requireNonNull(lambdaByDeclaration, "lambdaByDeclaration");
        Objects.requireNonNull(intrinsicDeclarations, "intrinsicDeclarations");
        Objects.requireNonNull(components, "components");
        Objects.requireNonNull(deferredCallableDeclarations, "deferredCallableDeclarations");

        TreeMap<LambdaId, CallableSummary> ordered = new TreeMap<>();
        for (CallableSummary summary : summaries) {
            CallableSummary value = Objects.requireNonNull(summary, "summary");
            if (ordered.put(value.lambdaId(), value) != null) {
                throw new IllegalArgumentException("more than one summary exists for " + value.lambdaId());
            }
        }
        TreeMap<DeclarationId, LambdaId> declarationIndex = new TreeMap<>();
        lambdaByDeclaration.forEach((declaration, lambda) -> {
            DeclarationId declarationId = Objects.requireNonNull(declaration, "declaration");
            LambdaId lambdaId = Objects.requireNonNull(lambda, "lambda");
            if (!ordered.containsKey(lambdaId)) {
                throw new IllegalArgumentException("declaration links to an absent lambda summary");
            }
            if (declarationIndex.put(declarationId, lambdaId) != null) {
                throw new IllegalArgumentException("duplicate declaration-to-lambda link");
            }
        });
        TreeMap<DeclarationId, ModuleId> intrinsicIndex = new TreeMap<>();
        intrinsicDeclarations.forEach((declaration, module) -> {
            DeclarationId declarationId = Objects.requireNonNull(declaration, "intrinsic declaration");
            ModuleId moduleId = Objects.requireNonNull(module, "intrinsic module");
            if (declarationIndex.containsKey(declarationId)) {
                throw new IllegalArgumentException(
                        "a declaration cannot identify both a lambda and an intrinsic");
            }
            intrinsicIndex.put(declarationId, moduleId);
        });
        ArrayList<CallableScc> orderedComponents = new ArrayList<>();
        for (CallableScc component : components) {
            orderedComponents.add(Objects.requireNonNull(component, "component"));
        }
        orderedComponents.sort(Comparator.comparingInt(CallableScc::ordinal));
        TreeSet<Integer> componentOrdinals = new TreeSet<>();
        LinkedHashSet<LambdaId> componentMembers = new LinkedHashSet<>();
        for (CallableScc component : orderedComponents) {
            if (!componentOrdinals.add(component.ordinal())) {
                throw new IllegalArgumentException("duplicate callable SCC ordinal");
            }
            for (LambdaId member : component.members()) {
                if (!ordered.containsKey(member) || !componentMembers.add(member)) {
                    throw new IllegalArgumentException("callable SCC membership is incomplete or duplicated");
                }
            }
        }
        if (!componentMembers.equals(ordered.keySet())) {
            throw new IllegalArgumentException("callable SCCs do not cover every summary");
        }

        this.summaries = Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
        this.lambdaByDeclaration = Collections.unmodifiableMap(new LinkedHashMap<>(declarationIndex));
        this.intrinsicDeclarations = Collections.unmodifiableMap(new LinkedHashMap<>(intrinsicIndex));
        TreeSet<DeclarationId> deferred = new TreeSet<>();
        for (DeclarationId declaration : deferredCallableDeclarations) {
            DeclarationId value = Objects.requireNonNull(declaration, "deferred callable declaration");
            if (!intrinsicIndex.containsKey(value) && !declarationIndex.containsKey(value)) {
                deferred.add(value);
            }
        }
        this.components = List.copyOf(orderedComponents);
        this.deferredCallableDeclarations = Collections.unmodifiableSet(deferred);
        this.limits = ordered.isEmpty()
                ? SummaryLimits.DEFAULT
                : ordered.firstEntry().getValue().limits();
        for (CallableSummary summary : ordered.values()) {
            if (!summary.limits().equals(this.limits)) {
                throw new IllegalArgumentException("summaries use different finite domains");
            }
        }
    }

    public CallableSummarySet(
            List<CallableSummary> summaries,
            Map<DeclarationId, LambdaId> lambdaByDeclaration,
            List<CallableScc> components) {
        this(summaries, lambdaByDeclaration, Map.of(), components);
    }

    public CallableSummarySet(
            List<CallableSummary> summaries,
            Map<DeclarationId, LambdaId> lambdaByDeclaration,
            Map<DeclarationId, ModuleId> intrinsicDeclarations) {
        this(summaries, lambdaByDeclaration, intrinsicDeclarations,
                singletonComponents(summaries));
    }

    public CallableSummarySet(
            List<CallableSummary> summaries,
            Map<DeclarationId, LambdaId> lambdaByDeclaration) {
        this(summaries, lambdaByDeclaration, Map.of(), singletonComponents(summaries));
    }

    public CallableSummarySet(List<CallableSummary> summaries) {
        this(summaries, Map.of());
    }

    public static CallableSummarySet empty() {
        return new CallableSummarySet(List.of(), Map.of(), Map.of(), List.of());
    }

    /**
     * Combines independently sealed generation summaries without rebuilding
     * any body or scanning signatures.  Generation identities are monotonic,
     * so duplicate lambda identities are accepted only when the proof record
     * is byte-for-byte equal.
     */
    public static CallableSummarySet combine(
            CallableSummarySet predecessor,
            CallableSummarySet current) {
        Objects.requireNonNull(predecessor, "predecessor");
        Objects.requireNonNull(current, "current");
        TreeMap<LambdaId, CallableSummary> summaries = new TreeMap<>();
        predecessor.summaries.forEach(summaries::put);
        current.summaries.forEach((lambda, summary) -> {
            CallableSummary previous = summaries.putIfAbsent(lambda, summary);
            if (previous != null && !previous.equals(summary)) {
                throw new IllegalArgumentException(
                        "generation summaries disagree about lambda identity: " + lambda);
            }
        });
        TreeMap<DeclarationId, LambdaId> declarations = new TreeMap<>(
                predecessor.lambdaByDeclaration);
        current.lambdaByDeclaration.forEach((declaration, lambda) -> {
            LambdaId previous = declarations.putIfAbsent(declaration, lambda);
            if (previous != null && !previous.equals(lambda)) {
                throw new IllegalArgumentException(
                        "generation summaries disagree about declaration identity: " + declaration);
            }
        });
        TreeMap<DeclarationId, ModuleId> intrinsics = new TreeMap<>(
                predecessor.intrinsicDeclarations);
        current.intrinsicDeclarations.forEach((declaration, module) -> {
            ModuleId previous = intrinsics.putIfAbsent(declaration, module);
            if (previous != null && !previous.equals(module)) {
                throw new IllegalArgumentException(
                        "generation summaries disagree about intrinsic identity: " + declaration);
            }
        });
        declarations.keySet().forEach(intrinsics::remove);
        TreeSet<DeclarationId> deferred = new TreeSet<>(predecessor.deferredCallableDeclarations);
        deferred.addAll(current.deferredCallableDeclarations);
        deferred.removeAll(declarations.keySet());
        deferred.removeAll(intrinsics.keySet());
        List<CallableSummary> orderedSummaries = new ArrayList<>(summaries.values());
        return new CallableSummarySet(
                orderedSummaries, declarations, intrinsics,
                singletonComponents(orderedSummaries), deferred);
    }

    public Set<DeclarationId> deferredCallableDeclarations() {
        return deferredCallableDeclarations;
    }

    public Map<LambdaId, CallableSummary> summaries() {
        return summaries;
    }

    public Map<LambdaId, CallableSummary> byLambda() {
        return summaries;
    }

    public Optional<CallableSummary> summary(LambdaId lambdaId) {
        return Optional.ofNullable(summaries.get(Objects.requireNonNull(lambdaId, "lambdaId")));
    }

    public Optional<CallableSummary> get(LambdaId lambdaId) {
        return summary(lambdaId);
    }

    public List<CallableSummary> orderedSummaries() {
        return List.copyOf(summaries.values());
    }

    /**
     * Retains the solved summaries belonging to one typed graph while keeping
     * their producer-computed SCC topology.  Session analysis may solve a
     * current graph together with inherited producer summaries, but the
     * published phase artifact must still cover exactly the graph's typed
     * lambdas rather than leaking borrowed summaries into its validator.
     */
    CallableSummarySet select(Set<LambdaId> selectedLambdas,
                              Set<DeclarationId> selectedDeclarations,
                              Set<DeclarationId> selectedIntrinsics) {
        Objects.requireNonNull(selectedLambdas, "selectedLambdas");
        Objects.requireNonNull(selectedDeclarations, "selectedDeclarations");
        Objects.requireNonNull(selectedIntrinsics, "selectedIntrinsics");
        List<CallableSummary> selected = summaries.values().stream()
                .filter(summary -> selectedLambdas.contains(summary.lambdaId()))
                .toList();
        Map<DeclarationId, LambdaId> declarations = new TreeMap<>();
        lambdaByDeclaration.forEach((declaration, lambda) -> {
            if (selectedDeclarations.contains(declaration)
                    && selectedLambdas.contains(lambda)) {
                declarations.put(declaration, lambda);
            }
        });
        Map<DeclarationId, ModuleId> intrinsics = new TreeMap<>();
        intrinsicDeclarations.forEach((declaration, module) -> {
            if (selectedIntrinsics.contains(declaration)) {
                intrinsics.put(declaration, module);
            }
        });
        List<CallableScc> selectedComponents = new ArrayList<>();
        int nextOrdinal = components.stream()
                .mapToInt(CallableScc::ordinal).max().orElse(-1) + 1;
        for (CallableScc component : components) {
            if (component.members().stream().allMatch(selectedLambdas::contains)) {
                selectedComponents.add(component);
                continue;
            }
            // A solved component may mix current-graph lambdas with
            // certificate-only partners (for example, one module of a pinned
            // import cycle is reused while the other stays certificate-only).
            // The whole component cannot be published, but every selected
            // member summary must still be covered, so each selected member
            // becomes a singleton component of its already-solved summary.
            // The solver recomputes components from scratch each generation,
            // so this published topology never drives a later fixed point.
            for (LambdaId member : component.members().stream().sorted().toList()) {
                if (selectedLambdas.contains(member)) {
                    selectedComponents.add(new CallableScc(
                            nextOrdinal++, List.of(member), List.of(), false));
                }
            }
        }
        List<CallableScc> orderedComponents = new ArrayList<>(selectedComponents);
        orderedComponents.sort(Comparator.comparingInt(CallableScc::ordinal));
        TreeSet<DeclarationId> deferred = new TreeSet<>(deferredCallableDeclarations);
        deferred.retainAll(selectedDeclarations);
        return new CallableSummarySet(selected, declarations, intrinsics, orderedComponents, deferred);
    }

    public Map<DeclarationId, LambdaId> lambdaByDeclaration() {
        return lambdaByDeclaration;
    }

    public Optional<LambdaId> lambdaForDeclaration(DeclarationId declarationId) {
        return Optional.ofNullable(lambdaByDeclaration.get(
                Objects.requireNonNull(declarationId, "declarationId")));
    }

    public Optional<CallableSummary> summaryForDeclaration(DeclarationId declarationId) {
        return lambdaForDeclaration(declarationId).flatMap(this::summary);
    }

    public Map<DeclarationId, ModuleId> intrinsicDeclarations() {
        return intrinsicDeclarations;
    }

    public List<CallableScc> components() {
        return components;
    }

    public List<CallableScc> sccs() {
        return components;
    }

    /** The reachable callable alternatives explicitly present in a target value. */
    public List<LambdaId> reachableAlternatives(FormulaAlternatives target) {
        Objects.requireNonNull(target, "target");
        TreeSet<LambdaId> result = new TreeSet<>();
        for (ValueFormula formula : target.formulas()) {
            if (!formula.resultRoute().isRoot()) {
                continue;
            }
            if (formula instanceof ValueFormula.Lambda lambda) {
                result.add(lambda.lambdaId());
            } else if (formula instanceof ValueFormula.Declaration declaration) {
                LambdaId lambda = lambdaByDeclaration.get(declaration.declarationId());
                if (lambda != null) {
                    result.add(lambda);
                }
            }
        }
        return List.copyOf(result);
    }

    public SummaryLimits limits() {
        return limits;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof CallableSummarySet set
                && summaries.equals(set.summaries)
                && lambdaByDeclaration.equals(set.lambdaByDeclaration)
                && intrinsicDeclarations.equals(set.intrinsicDeclarations)
                && components.equals(set.components)
                && deferredCallableDeclarations.equals(set.deferredCallableDeclarations);
    }

    @Override
    public int hashCode() {
        return Objects.hash(summaries, lambdaByDeclaration, intrinsicDeclarations, components,
                deferredCallableDeclarations);
    }

    @Override
    public String toString() {
        return canonicalKey();
    }

    public String canonicalKey() {
        StringBuilder result = new StringBuilder();
        for (CallableSummary summary : summaries.values()) {
            result.append("summary=").append(summary.canonicalKey()).append('\n');
        }
        for (Map.Entry<DeclarationId, LambdaId> entry : lambdaByDeclaration.entrySet()) {
            result.append("declaration=").append(entry.getKey()).append("->")
                    .append(entry.getValue()).append('\n');
        }
        for (Map.Entry<DeclarationId, ModuleId> entry : intrinsicDeclarations.entrySet()) {
            result.append("intrinsic=").append(entry.getKey()).append("->")
                    .append(entry.getValue()).append('\n');
        }
        for (CallableScc component : components) {
            result.append("scc=").append(component.canonicalKey()).append('\n');
        }
        for (DeclarationId declaration : deferredCallableDeclarations) {
            result.append("deferred=").append(declaration).append('\n');
        }
        return result.toString();
    }

    /**
     * Applies the finite target alternatives visible at one call site.  Each
     * candidate starts from the same argument/capture facts before results are
     * joined, so an unknown selector is a finite may-union rather than a
     * global signature scan.
     */
    public SummaryTransferResult invoke(
            FormulaAlternatives target,
            List<FormulaAlternatives> arguments,
            SourceSpan callSpan) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(callSpan, "callSpan");
        try {
            limits.requireFormulaAlternatives(target.size());
            requireProjectionDepth(target);
            for (FormulaAlternatives argument : arguments) {
                limits.requireFormulaAlternatives(argument.size());
                requireProjectionDepth(argument);
            }
        } catch (SummaryLimits.SummaryDomainException failure) {
            return failure(CallableSummaryResult.InternalFailure.Kind.DOMAIN_LIMIT,
                    failure.getMessage(), callSpan);
        }
        if (!(target.rootType().withoutQualifiers() instanceof FunctionType function)) {
            return failure(CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                    "typed call target has no function contract", callSpan);
        }
        if (arguments.size() != function.arity()) {
            return failure(CallableSummaryResult.InternalFailure.Kind.INCONSISTENT_SUMMARY,
                    "call argument count does not match its function contract", callSpan);
        }

        TargetResolution resolution = targetAlternatives(target);
        if (!resolution.complete()) {
            return failure(CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                    "a compatible reachable callable alternative has no recoverable summary identity",
                    callSpan);
        }
        List<TargetAlternative> candidates = resolution.alternatives();
        if (candidates.isEmpty()) {
            return failure(CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                    "no compatible reachable callable alternative was available at the call site",
                    callSpan);
        }

        FormulaAlternatives joinedValue = null;
        ArrayList<CapturedCellWrite> writes = new ArrayList<>();
        ArrayList<OwnershipRequirement> ownershipRequirements = new ArrayList<>();
        ArrayList<EagerEffectWitness> effects = new ArrayList<>();
        for (TargetAlternative candidate : candidates) {
            SummaryTransferResult.Success success;
            if (candidate.isIntrinsic()) {
                success = new SummaryTransferResult.Success(
                        FormulaAlternatives.singleton(
                                new ValueFormula.Scalar(function.returnType())),
                        List.of(), List.of());
            } else {
                CallableSummary summary = summaries.get(candidate.lambdaId().orElseThrow());
                if (summary == null) {
                    return failure(CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                            "reachable callable alternative has no solved summary: "
                                    + candidate.lambdaId().orElseThrow(), callSpan);
                }
                if (!summary.signature().asFunctionType().equals(function)) {
                    return failure(CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                            "reachable callable alternative has no matching solved summary: "
                                    + candidate.lambdaId().orElseThrow(), callSpan);
                }
                SummaryTransferResult applied = applySummary(
                        summary, arguments, arguments, candidate.captures(), callSpan,
                        declaration -> Optional.empty());
                if (applied instanceof SummaryTransferResult.Failure failure) {
                    return failure;
                }
                success = (SummaryTransferResult.Success) applied;
            }
            joinedValue = joinedValue == null
                    ? success.returnValue()
                    : joinedValue.join(success.returnValue());
            writes.addAll(success.writes());
            ownershipRequirements.addAll(success.ownershipRequirements());
            effects.addAll(success.effects());
        }
        if (joinedValue == null) {
            return failure(CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                    "reachable callable alternatives have no matching solved summary", callSpan);
        }
        try {
            limits.requireFormulaAlternatives(joinedValue.size());
            limits.requireWrites(new LinkedHashSet<>(writes).size());
            limits.requireOwnershipRequirements(new LinkedHashSet<>(
                    ownershipRequirements).size());
            limits.requireEffects(new TreeSet<>(effects).size());
        } catch (SummaryLimits.SummaryDomainException failure) {
            return failure(CallableSummaryResult.InternalFailure.Kind.DOMAIN_LIMIT,
                    failure.getMessage(), callSpan);
        }
        return new SummaryTransferResult.Success(
                joinedValue, writes, ownershipRequirements, effects);
    }

    /** Applies a known lambda identity without pretending other signatures are reachable. */
    public SummaryTransferResult invoke(
            LambdaId lambdaId,
            List<FormulaAlternatives> arguments,
            Map<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> captures,
            SourceSpan callSpan) {
        return invoke(lambdaId, arguments, arguments, captures, callSpan);
    }

    /**
     * Applies calls and values from {@code arguments} while independently
     * rebasing mutable-parameter writes through {@code writeArguments}.
     */
    public SummaryTransferResult invoke(
            LambdaId lambdaId,
            List<FormulaAlternatives> arguments,
            List<FormulaAlternatives> writeArguments,
            Map<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> captures,
            SourceSpan callSpan) {
        Objects.requireNonNull(lambdaId, "lambdaId");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(writeArguments, "writeArguments");
        Objects.requireNonNull(captures, "captures");
        Objects.requireNonNull(callSpan, "callSpan");
        CallableSummary summary = summaries.get(lambdaId);
        if (summary == null) {
            return failure(CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                    "no solved callable summary exists for " + lambdaId, callSpan);
        }
        return applySummary(summary, arguments, writeArguments, captures,
                callSpan, declaration -> Optional.empty());
    }

    /**
     * Canonical typed-flow entry point for substituting current declaration
     * values that cannot be fixed while compiling a parameterized summary.
     * The resolver supplies the exact source-ordered declaration value; it
     * must never recover a target by compatible-signature search.
     */
    public SummaryTransferResult invoke(
            LambdaId lambdaId,
            List<FormulaAlternatives> arguments,
            List<FormulaAlternatives> writeArguments,
            Map<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> captures,
            SourceSpan callSpan,
            Function<ValueFormula.Declaration, Optional<FormulaAlternatives>>
                    declarationResolver) {
        Objects.requireNonNull(lambdaId, "lambdaId");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(writeArguments, "writeArguments");
        Objects.requireNonNull(captures, "captures");
        Objects.requireNonNull(callSpan, "callSpan");
        Objects.requireNonNull(declarationResolver, "declarationResolver");
        CallableSummary summary = summaries.get(lambdaId);
        if (summary == null) {
            return failure(CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                    "no solved callable summary exists for " + lambdaId, callSpan);
        }
        return applySummary(summary, arguments, writeArguments, captures,
                callSpan, declarationResolver);
    }

    public SummaryTransferResult invoke(
            LambdaId lambdaId,
            List<FormulaAlternatives> arguments,
            SourceSpan callSpan) {
        return invoke(lambdaId, arguments, Map.of(), callSpan);
    }

    public SummaryTransferResult apply(
            LambdaId lambdaId,
            List<FormulaAlternatives> arguments,
            Map<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> captures,
            SourceSpan callSpan) {
        return invoke(lambdaId, arguments, captures, callSpan);
    }

    private SummaryTransferResult applySummary(
            CallableSummary summary,
            List<FormulaAlternatives> arguments,
            List<FormulaAlternatives> writeArguments,
            Map<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> captures,
            SourceSpan callSpan,
            Function<ValueFormula.Declaration, Optional<FormulaAlternatives>>
                    declarationResolver) {
        return applySummary(summary, arguments, writeArguments, captures,
                callSpan, declarationResolver, new LinkedHashSet<>(), List.of());
    }

    private SummaryTransferResult applySummary(
            CallableSummary summary,
            List<FormulaAlternatives> arguments,
            List<FormulaAlternatives> writeArguments,
            Map<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> captures,
            SourceSpan callSpan,
            Function<ValueFormula.Declaration, Optional<FormulaAlternatives>>
                    declarationResolver,
            LinkedHashSet<LambdaId> active,
            List<SummaryCallId> invocationPath) {
        if (arguments.size() != summary.signature().arity()
                || writeArguments.size() != arguments.size()) {
            return failure(CallableSummaryResult.InternalFailure.Kind.INCONSISTENT_SUMMARY,
                    "call arguments do not match " + summary.lambdaId(), callSpan);
        }
        for (int index = 0; index < arguments.size(); index++) {
            if (!arguments.get(index).rootType().withoutQualifiers()
                    .equals(summary.signature().parameterType(index).withoutQualifiers())
                    || !writeArguments.get(index).rootType().withoutQualifiers()
                    .equals(summary.signature().parameterType(index).withoutQualifiers())) {
                return failure(CallableSummaryResult.InternalFailure.Kind.INCONSISTENT_SUMMARY,
                        "call argument " + index + " does not match " + summary.lambdaId(), callSpan);
            }
        }
        boolean entered = false;
        try {
            limits.requireFixedPointIteration(active.size() + 1);
            if (!active.add(summary.lambdaId())) {
                return failure(CallableSummaryResult.InternalFailure.Kind.NON_CONVERGENT,
                        "recursive callable application re-entered " + summary.lambdaId(), callSpan);
            }
            entered = true;
            for (FormulaAlternatives argument : arguments) {
                limits.requireFormulaAlternatives(argument.size());
                requireProjectionDepth(argument);
            }
            for (FormulaAlternatives argument : writeArguments) {
                limits.requireFormulaAlternatives(argument.size());
                requireProjectionDepth(argument);
            }
            for (FormulaAlternatives capture : captures.values()) {
                limits.requireFormulaAlternatives(capture.size());
                requireProjectionDepth(capture);
            }
            ArrayList<ValueFormula> returned = new ArrayList<>();
            ArrayList<FormulaAlternatives.ExactOverride> returnedOverrides = new ArrayList<>();
            ArrayList<CapturedCellWrite> writes = new ArrayList<>();
            ArrayList<OwnershipRequirement> ownershipRequirements = new ArrayList<>();
            ArrayList<EagerEffectWitness> effects = new ArrayList<>();
            LinkedHashSet<SummaryCallId> appliedCalls = new CallMemo(invocationPath);
            boolean ordered = declarationResolver instanceof SummaryObjectResolver resolver
                    && resolver.orderedEffects();
            if (ordered) {
                // Summary sequences are event-major, including writes materialized
                // from static callees. Heap-dependent calls must run between them.
                java.util.TreeMap<Integer, List<Object>> schedule = new java.util.TreeMap<>();
                for (CallableCallReference call : summary.callReferences()) {
                    if (!isMaterializedStaticCall(call)) schedule.computeIfAbsent(
                            CapturedCellWrite.sequenceAtEvent(call.id().sequence(), 0, limits),
                            ignored -> new ArrayList<>()).add(call);
                }
                for (CapturedCellWrite write : summary.writes()) schedule.computeIfAbsent(
                        write.sequence(), ignored -> new ArrayList<>()).add(write);
                for (List<Object> event : schedule.values()) for (Object operation : event) {
                    if (operation instanceof CallableCallReference call) {
                        if (applyCall(call, arguments, writeArguments, captures, declarationResolver,
                                summary, callSpan, active, writes, ownershipRequirements, effects,
                                appliedCalls).isEmpty()) {
                            return failure(CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                                    "an ordered call has no caller fact", call.span());
                        }
                        appliedCalls.add(call.id());
                    } else {
                        CapturedCellWrite write = (CapturedCellWrite) operation;
                        Optional<FormulaAlternatives> value = substituteAlternatives(write.value(),
                                arguments, writeArguments, captures, declarationResolver, summary,
                                callSpan, active, writes, ownershipRequirements, effects, appliedCalls);
                        Optional<List<CapturedCellWrite>> transferred = value.flatMap(replacement ->
                                transferWriteTarget(write, replacement, writeArguments, captures,
                                        summary, declarationResolver));
                        if (transferred.isEmpty()) return failure(
                                CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                                "an ordered write has no caller fact", write.span());
                        for (CapturedCellWrite replacement : transferred.orElseThrow()) {
                            ((SummaryObjectResolver) declarationResolver).applyWrite(replacement, summary, appliedCalls);
                            writes.add(replacement);
                        }
                        limits.requireWrites(new LinkedHashSet<>(writes).size());
                    }
                }
            }
            for (ValueFormula formula : summary.returnFormula().formulas()) {
                Optional<List<ValueFormula>> substituted = substituteFormula(
                        formula, arguments, writeArguments, captures, declarationResolver,
                        summary, callSpan, active, writes, ownershipRequirements,
                        effects, appliedCalls);
                if (substituted.isEmpty()) {
                    return failure(CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                            "a symbolic return formula has no caller fact: "
                                    + formula + " in " + summary.lambdaId(), callSpan);
                }
                List<ValueFormula> replacements = substituted.orElseThrow();
                returned.addAll(replacements);
                returnedOverrides.addAll(summary.returnFormula().alternatives()
                        .rebaseOverrides(formula, replacements));
                limits.requireFormulaAlternatives(new LinkedHashSet<>(returned).size());
            }
            if (returned.isEmpty()) {
                return failure(CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                        "callable return formulas have no caller-visible value fact", callSpan);
            }
            for (CallableCallReference call : summary.callReferences()) {
                if (appliedCalls.add(call.id())) {
                    if (isMaterializedStaticCall(call)) {
                        continue;
                    }
                    Optional<FormulaAlternatives> ignored = applyCall(
                            call, arguments, writeArguments, captures, declarationResolver,
                            summary, callSpan, active, writes, ownershipRequirements,
                            effects, appliedCalls);
                    if (ignored.isEmpty() && !hasOnlyActiveTargets(
                            call, arguments, writeArguments, captures,
                            declarationResolver, summary, callSpan, active)) {
                        return failure(CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                                "a reachable nested call has no callable fact", call.span());
                    }
                }
            }
            FormulaAlternatives returnValue = new FormulaAlternatives(
                    summary.signature().returnType(), returned, returnedOverrides);
            requireProjectionDepth(returnValue);
            for (CapturedCellWrite write : ordered ? List.<CapturedCellWrite>of() : summary.writes()) {
                ArrayList<ValueFormula> values = new ArrayList<>();
                ArrayList<FormulaAlternatives.ExactOverride> valueOverrides = new ArrayList<>();
                for (ValueFormula value : write.value().formulas()) {
                    Optional<List<ValueFormula>> replacement = substituteFormula(
                            value, arguments, writeArguments, captures, declarationResolver,
                            summary, callSpan, active, writes, ownershipRequirements,
                            effects, appliedCalls);
                    if (replacement.isEmpty()) {
                        return failure(CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                                "a symbolic write has no caller fact", callSpan);
                    }
                    List<ValueFormula> replacements = replacement.orElseThrow();
                    values.addAll(replacements);
                    valueOverrides.addAll(write.value().rebaseOverrides(value, replacements));
                    limits.requireFormulaAlternatives(new LinkedHashSet<>(values).size());
                }
                if (values.isEmpty()) {
                    return failure(CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                            "a symbolic write has no caller-visible value", callSpan);
                }
                FormulaAlternatives transferredValue = new FormulaAlternatives(
                        write.value().rootType(), values, valueOverrides);
                requireProjectionDepth(transferredValue);
                Optional<List<CapturedCellWrite>> transferred = transferWriteTarget(
                        write, transferredValue, writeArguments, captures, summary,
                        declarationResolver);
                if (transferred.isEmpty()) {
                    return failure(CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                            "a symbolic write target has no caller fact", callSpan);
                }
                writes.addAll(transferred.orElseThrow());
                limits.requireWrites(new LinkedHashSet<>(writes).size());
            }
            for (OwnershipRequirement requirement : summary.ownershipRequirements()) {
                Optional<FormulaAlternatives> transferred = substituteAlternatives(
                        requirement.value(), arguments, writeArguments, captures,
                        declarationResolver, summary, callSpan, active, writes,
                        ownershipRequirements, effects, appliedCalls);
                if (transferred.isEmpty()) {
                    return failure(
                            CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                            "a symbolic ownership requirement has no caller fact",
                            requirement.span());
                }
                ownershipRequirements.add(requirement.withValue(
                        transferred.orElseThrow()));
                limits.requireOwnershipRequirements(new LinkedHashSet<>(
                        ownershipRequirements).size());
            }
            limits.requireFormulaAlternatives(returnValue.size());
            effects.addAll(summary.eagerEffects());
            limits.requireEffects(new TreeSet<>(effects).size());
            for (EagerEffectWitness effect : effects) {
                limits.requireWitnessPathDepth(effect.sourcePath().size());
            }
            return new SummaryTransferResult.Success(
                    returnValue, writes, ownershipRequirements, effects);
        } catch (NestedTransferFailure failure) {
            return failure.result();
        } catch (SummaryLimits.SummaryDomainException failure) {
            return failure(CallableSummaryResult.InternalFailure.Kind.DOMAIN_LIMIT,
                    failure.getMessage(), callSpan);
        } catch (IllegalArgumentException failure) {
            return failure(CallableSummaryResult.InternalFailure.Kind.INCONSISTENT_SUMMARY,
                    failure.getMessage() == null ? "callable substitution is inconsistent" : failure.getMessage(),
                    callSpan);
        } finally {
            if (entered) {
                active.remove(summary.lambdaId());
            }
        }
    }

    private Optional<List<ValueFormula>> substituteFormula(
            ValueFormula formula,
            List<FormulaAlternatives> arguments,
            List<FormulaAlternatives> writeArguments,
            Map<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> captures,
            Function<ValueFormula.Declaration, Optional<FormulaAlternatives>> declarationResolver,
            CallableSummary summary, SourceSpan callSpan, LinkedHashSet<LambdaId> active,
            List<CapturedCellWrite> transferredWrites,
            List<OwnershipRequirement> transferredOwnershipRequirements,
            List<EagerEffectWitness> transferredEffects, LinkedHashSet<SummaryCallId> appliedCalls) {
        var substituted = substituteFormulaValue(formula, arguments, writeArguments, captures, declarationResolver,
                summary, callSpan, active, transferredWrites, transferredOwnershipRequirements, transferredEffects, appliedCalls);
        if (substituted.isEmpty()) return substituted;
        ArrayList<ValueFormula> values = new ArrayList<>();
        for (ValueFormula value : substituted.orElseThrow()) {
            if (value instanceof ValueFormula.ObjectReference object && !object.sourceRoute().isRoot()) {
                if (!(declarationResolver instanceof SummaryObjectResolver resolver)) return Optional.empty();
                var selected = resolver.resolveObject(object);
                if (selected.isEmpty()) return Optional.empty();
                selected.orElseThrow().formulas().forEach(term -> values.add(term.prefixedBy(object.resultRoute())));
            } else values.add(value);
        }
        limits.requireFormulaAlternatives(values.size());
        return Optional.of(List.copyOf(values));
    }

    private Optional<List<ValueFormula>> substituteFormulaValue(
            ValueFormula formula,
            List<FormulaAlternatives> arguments,
            List<FormulaAlternatives> writeArguments,
            Map<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> captures,
            Function<ValueFormula.Declaration, Optional<FormulaAlternatives>>
                    declarationResolver,
            CallableSummary summary,
            SourceSpan callSpan,
            LinkedHashSet<LambdaId> active,
            List<CapturedCellWrite> transferredWrites,
            List<OwnershipRequirement> transferredOwnershipRequirements,
            List<EagerEffectWitness> transferredEffects,
            LinkedHashSet<SummaryCallId> appliedCalls) {
        if (formula instanceof ValueFormula.Parameter parameter) {
            if (parameter.parameterIndex() >= arguments.size()) {
                return Optional.empty();
            }
            FormulaAlternatives selected;
            try {
                selected = arguments.get(parameter.parameterIndex()).select(parameter.parameterRoute());
            } catch (IllegalArgumentException failure) {
                return Optional.empty();
            }
            if (selected.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(selected.formulas().stream()
                    .map(value -> value.prefixedBy(parameter.resultRoute())).toList());
        }
        if (formula instanceof ValueFormula.Capture capture) {
            FormulaAlternatives value = captures.get(capture.captureId());
            if (value == null) {
                return Optional.empty();
            }
            try {
                FormulaAlternatives selected = value.select(capture.captureRoute());
                if (selected.isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(selected.formulas().stream()
                        .map(term -> term.prefixedBy(capture.resultRoute())).toList());
            } catch (IllegalArgumentException failure) {
                return Optional.empty();
            }
        }
        if (formula instanceof ValueFormula.Declaration declaration
                && (formula.type().withoutQualifiers() instanceof FunctionType
                    || formula.type().withoutQualifiers() instanceof ArrayType
                    || formula.type().withoutQualifiers() instanceof TupleType)) {
            Optional<FormulaAlternatives> resolved = declarationResolver.apply(
                    declaration);
            if (resolved.isPresent()) {
                FormulaAlternatives selected;
                try {
                    selected = resolved.orElseThrow().select(
                            declaration.declarationRoute());
                } catch (IllegalArgumentException failure) {
                    return Optional.empty();
                }
                if (selected.isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(selected.formulas().stream()
                        .map(value -> value.prefixedBy(
                                declaration.resultRoute()))
                        .toList());
            }
        }
        if (formula instanceof ValueFormula.CallResult callResult && summary != null) {
            CallableCallReference call = summary.callReferences().stream()
                    .filter(reference -> reference.id().equals(callResult.callId()))
                    .findFirst().orElse(null);
            if (call == null) {
                return Optional.empty();
            }
            appliedCalls.add(call.id());
            boolean materialized = isMaterializedStaticCall(call);
            Optional<FormulaAlternatives> applied = applyCall(
                    call, arguments, writeArguments, captures, declarationResolver,
                    summary, callSpan, active,
                    materialized ? new ArrayList<>() : transferredWrites,
                    materialized ? new ArrayList<>() : transferredOwnershipRequirements,
                    materialized ? new ArrayList<>() : transferredEffects,
                    appliedCalls);
            if (applied.isEmpty()) {
                return hasOnlyActiveTargets(
                        call, arguments, writeArguments, captures,
                        declarationResolver, summary, callSpan, active)
                        ? Optional.of(List.of(callResult)) : Optional.empty();
            }
            FormulaAlternatives selected;
            try {
                selected = applied.orElseThrow().select(callResult.callRoute());
            } catch (IllegalArgumentException failure) {
                return Optional.empty();
            }
            if (selected.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(selected.formulas().stream()
                    .map(value -> value.prefixedBy(callResult.resultRoute())).toList());
        }
        if (formula instanceof ValueFormula.Lambda lambda) {
            TreeMap<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> substituted =
                    new TreeMap<>();
            for (Map.Entry<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> entry
                    : lambda.captures().entrySet()) {
                ArrayList<ValueFormula> values = new ArrayList<>();
                ArrayList<FormulaAlternatives.ExactOverride> valueOverrides = new ArrayList<>();
                for (ValueFormula captured : entry.getValue().formulas()) {
                    Optional<List<ValueFormula>> replacement = substituteFormula(
                            captured, arguments, writeArguments, captures,
                            declarationResolver, summary, callSpan, active,
                            transferredWrites, transferredOwnershipRequirements,
                            transferredEffects, appliedCalls);
                    if (replacement.isEmpty()) {
                        return Optional.empty();
                    }
                    List<ValueFormula> replacements = replacement.orElseThrow();
                    values.addAll(replacements);
                    valueOverrides.addAll(entry.getValue()
                            .rebaseOverrides(captured, replacements));
                    limits.requireFormulaAlternatives(new LinkedHashSet<>(values).size());
                }
                if (values.isEmpty()) {
                    return Optional.empty();
                }
                substituted.put(entry.getKey(), new FormulaAlternatives(
                        entry.getValue().rootType(), values, valueOverrides));
            }
            return Optional.of(List.of(lambda.withCaptures(substituted)));
        }
        return Optional.of(List.of(formula));
    }

    private Optional<List<CapturedCellWrite>> transferWriteTarget(
            CapturedCellWrite write,
            FormulaAlternatives value,
            List<FormulaAlternatives> arguments,
            Map<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> captures,
            CallableSummary owner,
            Function<ValueFormula.Declaration, Optional<FormulaAlternatives>>
                    declarationResolver) {
        if (write.isDeclarationWrite()) return Optional.of(List.of(write.withValue(value)));
        FormulaAlternatives target;
        if (write.isParameterWrite()) {
            if (write.parameter() >= arguments.size()) {
                return Optional.empty();
            }
            target = arguments.get(write.parameter());
        } else {
            target = captures.get(write.capture());
            if (target == null) {
                // A propagated nested-cell write can reference a capture of
                // the origin callee instead of the current caller.  Resolve
                // the cell through the declaration resolver.  When the cell
                // belongs to a foreign module without facts in this
                // compilation, the write is an already-witnessed external
                // module effect and is skipped; it never fabricates a
                // session-owned state update.
                Optional<FormulaAlternatives> resolved = owner.captures().stream()
                        .filter(capture -> capture.captureId().equals(write.capture()))
                        .filter(capture -> capture.sharedCellId().equals(
                                write.sharedCellId()))
                        .findFirst()
                        .flatMap(capture -> declarationResolver.apply(
                                new ValueFormula.Declaration(
                                        capture.declarationId(), Optional.empty(),
                                        ProjectionPath.root(), ProjectionPath.root(),
                                        capture.contract().valueType())));
                if (resolved.isEmpty() || resolved.orElseThrow().isEmpty()) {
                    return Optional.of(List.of());
                }
                target = resolved.orElseThrow();
            }
        }
        ArrayList<CapturedCellWrite> result = new ArrayList<>();
        boolean ignoredNonEscapingTarget = false;
        for (ValueFormula formula : target.formulas()) {
            if (!formula.resultRoute().isRoot()) {
                continue;
            }
            if (formula instanceof ValueFormula.Parameter parameter) {
                ProjectionPath route = parameter.parameterRoute().compose(write.route());
                result.add(CapturedCellWrite.parameter(
                        write.sequence(), parameter.parameterIndex(), parameter.declarationId(),
                        writeKind(route), route, value, write.span()));
            } else if (formula instanceof ValueFormula.Capture capture) {
                ProjectionPath route = capture.captureRoute().compose(write.route());
                result.add(capture.sharedCellId().isPresent()
                        ? CapturedCellWrite.capture(
                        write.sequence(), capture.captureId(), capture.declarationId(),
                        capture.sharedCellId().orElseThrow(), writeKind(route), route,
                        value, write.span())
                        : CapturedCellWrite.captureAggregate(
                        write.sequence(), capture.captureId(), capture.declarationId(),
                        writeKind(route), route, value, write.span()));
            } else if (write.isCaptureWrite() && formula instanceof ValueFormula.ObjectReference object) {
                ProjectionPath route = object.sourceRoute().compose(write.route());
                result.add(CapturedCellWrite.captureAggregate(write.sequence(), write.capture(),
                        object.object().ownership().originDeclaration(), writeKind(route), route, value, write.span()));
            } else if (write.isCaptureWrite()
                    && formula instanceof ValueFormula.FreshAllocation fresh) {
                ProjectionPath route = fresh.resultRoute().compose(write.route());
                result.add(write.isCaptureCellWrite()
                        ? CapturedCellWrite.capture(
                        write.sequence(), write.capture(), write.declarationId(),
                        write.sharedCellId().orElseThrow(), writeKind(route), route,
                        value, write.span())
                        : CapturedCellWrite.captureAggregate(
                        write.sequence(), write.capture(), write.declarationId(),
                        writeKind(route), route, value, write.span()));
            } else if (write.isCaptureWrite()
                    && formula instanceof ValueFormula.Declaration declaration) {
                ProjectionPath route = declaration.declarationRoute().compose(write.route());
                result.add(write.isCaptureCellWrite()
                        ? CapturedCellWrite.capture(
                        write.sequence(), write.capture(), declaration.declarationId(),
                        write.sharedCellId().orElseThrow(), writeKind(route), route,
                        value, write.span())
                        : CapturedCellWrite.captureAggregate(
                        write.sequence(), write.capture(), declaration.declarationId(),
                        writeKind(route), route, value, write.span()));
            } else if (write.isParameterWrite()
                    && (formula instanceof ValueFormula.Scalar
                    || formula instanceof ValueFormula.Declaration
                    || formula instanceof ValueFormula.FreshAllocation)) {
                // A callback may mutate a scalar or aggregate local created
                // inside the summarized caller.  Declaration/fresh terms carry
                // concrete aggregate identity but no outer parameter/cell
                // target, so the write is complete and non-escaping rather
                // than a missing callable fact.
                ignoredNonEscapingTarget = true;
            } else if (write.isCaptureCellWrite()) {
                // A captured mutable cell may currently contain a callable or
                // scalar value. Its stable cell identity is the write target.
                ProjectionPath route = formula.resultRoute().compose(write.route());
                result.add(CapturedCellWrite.capture(
                        write.sequence(), write.capture(), write.declarationId(),
                        write.sharedCellId().orElseThrow(), writeKind(route), route,
                        value, write.span()));
            } else {
                return Optional.empty();
            }
        }
        return result.isEmpty() && !ignoredNonEscapingTarget
                ? Optional.empty() : Optional.of(result.stream()
                        .map(valueWrite -> valueWrite.withOperationSite(write.operationSite())).toList());
    }

    private static CapturedCellWrite.Kind writeKind(ProjectionPath route) {
        return route.containsWildcard() ? CapturedCellWrite.Kind.UNKNOWN_ROUTE
                : route.isRoot() ? CapturedCellWrite.Kind.WHOLE
                : CapturedCellWrite.Kind.EXACT_ROUTE;
    }

    private static final class CallMemo extends LinkedHashSet<SummaryCallId> {
        private final Map<SummaryCallId, FormulaAlternatives> values = new java.util.HashMap<>();
        private final List<SummaryCallId> invocationPath;

        private CallMemo(List<SummaryCallId> invocationPath) {
            this.invocationPath = List.copyOf(invocationPath);
        }
    }

    private static List<SummaryCallId> callPath(
            LinkedHashSet<SummaryCallId> activation, SummaryCallId call) {
        ArrayList<SummaryCallId> path = new ArrayList<>();
        if (activation instanceof CallMemo memo) path.addAll(memo.invocationPath);
        path.add(call);
        return List.copyOf(path);
    }

    private Optional<FormulaAlternatives> applyCall(
            CallableCallReference call, List<FormulaAlternatives> arguments,
            List<FormulaAlternatives> writeArguments,
            Map<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> captures,
            Function<ValueFormula.Declaration, Optional<FormulaAlternatives>> declarationResolver,
            CallableSummary owner, SourceSpan callSpan, LinkedHashSet<LambdaId> active,
            List<CapturedCellWrite> writes, List<OwnershipRequirement> ownershipRequirements,
            List<EagerEffectWitness> effects, LinkedHashSet<SummaryCallId> appliedCalls) {
        CallMemo memo = declarationResolver instanceof SummaryObjectResolver resolver
                && resolver.orderedEffects() && appliedCalls instanceof CallMemo cache ? cache : null;
        if (memo != null && memo.values.containsKey(call.id())) return Optional.of(memo.values.get(call.id()));
        Optional<FormulaAlternatives> result = evaluateCall(call, arguments, writeArguments, captures,
                declarationResolver, owner, callSpan, active, writes, ownershipRequirements, effects, appliedCalls);
        if (memo != null) result.ifPresent(value -> memo.values.put(call.id(), value));
        return result;
    }

    private Optional<FormulaAlternatives> evaluateCall(
            CallableCallReference call,
            List<FormulaAlternatives> arguments,
            List<FormulaAlternatives> writeArguments,
            Map<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> captures,
            Function<ValueFormula.Declaration, Optional<FormulaAlternatives>>
                    declarationResolver,
            CallableSummary owner,
            SourceSpan callSpan,
            LinkedHashSet<LambdaId> active,
            List<CapturedCellWrite> writes,
            List<OwnershipRequirement> ownershipRequirements,
            List<EagerEffectWitness> effects,
            LinkedHashSet<SummaryCallId> appliedCalls) {
        Optional<FormulaAlternatives> target = substituteAlternatives(
                call.target(), arguments, writeArguments, captures,
                declarationResolver, owner, callSpan, active, writes,
                ownershipRequirements, effects, appliedCalls);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        ArrayList<FormulaAlternatives> callArguments = new ArrayList<>();
        ArrayList<FormulaAlternatives> callWriteArguments = new ArrayList<>();
        for (FormulaAlternatives argument : call.arguments()) {
            Optional<FormulaAlternatives> substituted = substituteAlternatives(
                    argument, arguments, writeArguments, captures,
                    declarationResolver, owner, callSpan, active, writes,
                    ownershipRequirements, effects, appliedCalls);
            Optional<FormulaAlternatives> writeTarget = substituteAlternatives(
                    argument, writeArguments, writeArguments, captures,
                    declarationResolver, owner, callSpan, active,
                    new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                    declarationResolver instanceof SummaryObjectResolver resolver && resolver.orderedEffects()
                            ? appliedCalls : new LinkedHashSet<>());
            if (substituted.isEmpty() || writeTarget.isEmpty()) {
                return Optional.empty();
            }
            callArguments.add(substituted.orElseThrow());
            callWriteArguments.add(writeTarget.orElseThrow());
        }
        if (call.kind() == CallableCallReference.Kind.CONSTRUCTION) {
            return declarationResolver instanceof SummaryObjectResolver resolver
                    ? resolver.construct(call, callArguments, appliedCalls,
                    callPath(appliedCalls, call.id())) : Optional.empty();
        }
        if (call.repeat().isPresent()) {
            Optional<FormulaAlternatives> predicate = Optional.empty();
            if (call.repeat().orElseThrow().predicate().isPresent()) {
                predicate = substituteAlternatives(call.repeat().orElseThrow().predicate().orElseThrow(),
                        arguments, writeArguments, captures, declarationResolver, owner, callSpan, active,
                        writes, ownershipRequirements, effects, appliedCalls);
                if (predicate.isEmpty()) return Optional.empty();
            }
            Map<DeclarationId, FormulaAlternatives> environment = new TreeMap<>();
            for (var entry : call.repeat().orElseThrow().environment().entrySet()) {
                Optional<FormulaAlternatives> value = substituteAlternatives(entry.getValue(), arguments,
                        writeArguments, captures, declarationResolver, owner, callSpan, active,
                        writes, ownershipRequirements, effects, appliedCalls);
                if (value.isEmpty()) return Optional.empty();
                environment.put(entry.getKey(), value.orElseThrow());
            }
            return applyRepeatedCall(call, target.orElseThrow(), predicate, callArguments, environment,
                    declarationResolver, owner, active, writes, ownershipRequirements, effects,
                    appliedCalls);
        }
        TargetResolution resolution = targetAlternatives(target.orElseThrow());
        if (!resolution.complete() || resolution.alternatives().isEmpty()) {
            return Optional.empty();
        }
        FormulaAlternatives joined = null;
        boolean activeTarget = false;
        int transferredWriteIndex = 0;
        int transferredOwnershipIndex = 0;
        for (TargetAlternative candidate : resolution.alternatives()) {
            if (candidate.isIntrinsic()) {
                addCandidateCallWitness(effects, owner, call, Optional.empty(),
                        candidate.moduleId().orElseThrow());
                FormulaAlternatives intrinsicValue = FormulaAlternatives.singleton(
                        new ValueFormula.Scalar(((FunctionType) target.orElseThrow()
                                .rootType().withoutQualifiers()).returnType()));
                joined = joined == null ? intrinsicValue : joined.join(intrinsicValue);
                continue;
            }
            LambdaId targetLambda = candidate.lambdaId().orElseThrow();
            CallableSummary callee = summaries.get(targetLambda);
            if (callee == null) {
                return Optional.empty();
            }
            addCandidateCallWitness(effects, owner, call, Optional.of(targetLambda), callee.moduleId());
            if (active.contains(targetLambda)) {
                activeTarget = true;
                continue;
            }
            SummaryTransferResult result = applySummary(
                    callee, callArguments, callWriteArguments,
                    candidate.captures(), call.span(), declarationResolver, active,
                    callPath(appliedCalls, call.id()));
            if (result instanceof SummaryTransferResult.Failure failure) {
                throw new NestedTransferFailure(failure);
            }
            SummaryTransferResult.Success success = (SummaryTransferResult.Success) result;
            FormulaAlternatives returned = throughCall(success.returnValue(), call.id());
            joined = joined == null ? returned : joined.join(returned);
            for (CapturedCellWrite write : success.writes()) {
                writes.add(write.withValue(throughCall(write.value(), call.id()))
                        .withSequence(CapturedCellWrite.sequenceAtEvent(
                                call.id().sequence(), transferredWriteIndex++, limits)));
            }
            for (OwnershipRequirement requirement
                    : success.ownershipRequirements()) {
                ownershipRequirements.add(requirement.withValue(
                                throughCall(requirement.value(), call.id()))
                        .withSequence(CapturedCellWrite.sequenceAtEvent(
                                call.id().sequence(),
                                transferredOwnershipIndex++, limits)));
            }
            for (EagerEffectWitness effect : success.effects()) {
                addEffect(effects, effect.through(owner.moduleId(), call, limits));
            }
            limits.requireWrites(new LinkedHashSet<>(writes).size());
            limits.requireOwnershipRequirements(new LinkedHashSet<>(
                    ownershipRequirements).size());
            limits.requireEffects(new TreeSet<>(effects).size());
        }
        if (activeTarget) {
            FunctionType function = (FunctionType) target.orElseThrow()
                    .rootType().withoutQualifiers();
            FormulaAlternatives recursive = FormulaAlternatives.singleton(
                    new ValueFormula.CallResult(call.id(), function.returnType()));
            joined = joined == null ? recursive : joined.join(recursive);
            limits.requireFormulaAlternatives(joined.size());
        }
        return Optional.ofNullable(joined);
    }

    private FormulaAlternatives throughCall(
            FormulaAlternatives values, SummaryCallId call) {
        ArrayList<ValueFormula> formulas = new ArrayList<>(values.size());
        Map<ValueFormula, ValueFormula> replacements = new java.util.HashMap<>();
        for (ValueFormula formula : values.formulas()) {
            ValueFormula replacement = throughCall(formula, call);
            formulas.add(replacement);
            replacements.put(formula, replacement);
        }
        List<FormulaAlternatives.ExactOverride> overrides = values.exactOverrides().stream()
                .map(override -> new FormulaAlternatives.ExactOverride(
                        replacements.get(override.formula()), override.route()))
                .toList();
        return new FormulaAlternatives(values.rootType(), formulas, overrides);
    }

    private ValueFormula throughCall(ValueFormula formula, SummaryCallId call) {
        if (formula instanceof ValueFormula.FreshAllocation fresh) {
            return fresh.throughCall(call);
        }
        if (formula instanceof ValueFormula.Lambda lambda) {
            TreeMap<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> captures =
                    new TreeMap<>();
            lambda.captures().forEach((capture, value) ->
                    captures.put(capture, throughCall(value, call)));
            return lambda.withCaptures(captures).throughCall(call);
        }
        return formula;
    }

    private Optional<FormulaAlternatives> applyRepeatedCall(
            CallableCallReference call, FormulaAlternatives action, Optional<FormulaAlternatives> predicate,
            List<FormulaAlternatives> arguments, Map<DeclarationId, FormulaAlternatives> environment,
            Function<ValueFormula.Declaration, Optional<FormulaAlternatives>> resolver,
            CallableSummary owner, LinkedHashSet<LambdaId> active,
            List<CapturedCellWrite> writes, List<OwnershipRequirement> requirements,
            List<EagerEffectWitness> effects,
            LinkedHashSet<SummaryCallId> appliedCalls) {
        RepeatState state = new RepeatState(resolver);
        state.cells.putAll(environment);
        environment.values().forEach(state::seed);
        state.seed(action);
        predicate.ifPresent(state::seed);
        Map<String, CapturedCellWrite> repeatedWrites = new TreeMap<>();
        TreeSet<OwnershipRequirement> repeatedRequirements = new TreeSet<>();
        TreeSet<EagerEffectWitness> repeatedEffects = new TreeSet<>();
        for (int iteration = 1; ; iteration++) {
            limits.requireFixedPointIteration(iteration);
            String before = state.key();
            ArrayList<FormulaAlternatives> callbacks = new ArrayList<>();
            predicate.ifPresent(callbacks::add);
            callbacks.add(action);
            for (int index = 0; index < callbacks.size(); index++) {
                FormulaAlternatives selected = state.refresh(callbacks.get(index), new LinkedHashSet<>());
                TargetResolution resolution = targetAlternatives(selected);
                if (!resolution.complete() || resolution.alternatives().isEmpty()) return Optional.empty();
                for (TargetAlternative candidate : resolution.alternatives()) {
                    if (candidate.isIntrinsic()) {
                        addCandidateCallWitness(effects, owner, call, Optional.empty(), candidate.moduleId().orElseThrow());
                        continue;
                    }
                    LambdaId id = candidate.lambdaId().orElseThrow();
                    CallableSummary callee = summaries.get(id);
                    if (callee == null) return Optional.empty();
                    List<FormulaAlternatives> actual = index == callbacks.size() - 1 ? arguments : List.of();
                    SummaryTransferResult result = applySummary(callee, actual, actual, candidate.captures(),
                            call.span(), state::resolve, active,
                            callPath(appliedCalls, call.id()));
                    if (result instanceof SummaryTransferResult.Failure failed) throw new NestedTransferFailure(failed);
                    SummaryTransferResult.Success success = (SummaryTransferResult.Success) result;
                    addCandidateCallWitness(effects, owner, call, Optional.of(id), callee.moduleId());
                    for (CapturedCellWrite write : success.writes()) {
                        CapturedCellWrite joined = state.apply(write);
                        String key = write.operationSite() + "/" + write.declarationId() + "/" + write.kind() + "/" + write.route();
                        CapturedCellWrite previous = repeatedWrites.get(key);
                        if (previous != null) joined = new CapturedCellWrite(joined.sequence(), joined.captureId(),
                                joined.parameterIndex(), joined.declarationId(), joined.sharedCellId(), joined.kind(),
                                joined.route(), previous.value().join(joined.value()), joined.span(), joined.operationSite());
                        repeatedWrites.put(key, joined);
                    }
                    repeatedRequirements.addAll(success.ownershipRequirements());
                    repeatedEffects.addAll(success.effects());
                }
            }
            limits.requireWrites(repeatedWrites.size());
            limits.requireOwnershipRequirements(repeatedRequirements.size());
            limits.requireEffects(repeatedEffects.size());
            if (before.equals(state.key())) break;
        }
        int writeIndex = 0;
        for (CapturedCellWrite write : repeatedWrites.values()) {
            writes.add(write.withSequence(CapturedCellWrite.sequenceAtEvent(call.id().sequence(), writeIndex++, limits)));
        }
        int requirementIndex = 0;
        for (OwnershipRequirement requirement : repeatedRequirements) {
            requirements.add(requirement.withSequence(CapturedCellWrite.sequenceAtEvent(
                    call.id().sequence(), requirementIndex++, limits)));
        }
        for (EagerEffectWitness effect : repeatedEffects) addEffect(effects, effect.through(owner.moduleId(), call, limits));
        var snapshotType = call.repeat().orElseThrow().snapshotType();
        ArrayList<ValueFormula> snapshot = new ArrayList<>();
        snapshot.add(new ValueFormula.Scalar(snapshotType));
        snapshot.add(new ValueFormula.Scalar(io.mindspice.lyra.compiler.types.PrimitiveType.UNIT,
                ProjectionPath.tupleMember(0)));
        int snapshotIndex = 1;
        for (var entry : environment.entrySet()) {
            var route = ProjectionPath.tupleMember(snapshotIndex++);
            var value = state.refresh(state.cells.getOrDefault(entry.getKey(), entry.getValue()), new LinkedHashSet<>());
            snapshot.addAll(value.formulas().stream().map(formula -> formula.prefixedBy(route)).toList());
        }
        return Optional.of(new FormulaAlternatives(snapshotType, snapshot));
    }

    /** Monotone closure/cell environment for the zero-or-more repetition boundary. */
    private final class RepeatState {
        private final Function<ValueFormula.Declaration, Optional<FormulaAlternatives>> resolver;
        private final Map<DeclarationId, FormulaAlternatives> cells = new TreeMap<>();
        private final Map<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> captured = new TreeMap<>();
        private final Map<io.mindspice.lyra.compiler.identity.CaptureId, CallableSummary.CapturePlaceholder> metadata = new TreeMap<>();

        RepeatState(Function<ValueFormula.Declaration, Optional<FormulaAlternatives>> resolver) {
            this.resolver = resolver;
        }

        String key() { return cells.toString() + captured; }

        void seed(FormulaAlternatives values) {
            for (ValueFormula formula : values.formulas()) {
                if (!(formula instanceof ValueFormula.Lambda lambda)) continue;
                CallableSummary summary = summaries.get(lambda.lambdaId());
                if (summary == null) continue;
                for (CallableSummary.CapturePlaceholder capture : summary.captures()) {
                    FormulaAlternatives value = lambda.captures().get(capture.captureId());
                    if (value == null) continue;
                    metadata.put(capture.captureId(), capture);
                    FormulaAlternatives previous = captured.get(capture.captureId());
                    captured.merge(capture.captureId(), value, FormulaAlternatives::join);
                    if (capture.isSharedCell()) cells.merge(capture.sharedCellId().orElseThrow(), value, FormulaAlternatives::join);
                    if (previous == null || !previous.join(value).equals(previous)) seed(value);
                }
            }
        }

        Optional<FormulaAlternatives> resolve(ValueFormula.Declaration declaration) {
            FormulaAlternatives value = cells.get(declaration.declarationId());
            if (value == null) {
                Optional<FormulaAlternatives> selected = resolver.apply(declaration);
                if (selected.isEmpty()) return selected;
                value = selected.orElseThrow();
                if (declaration.declarationRoute().isRoot()) cells.put(declaration.declarationId(), value);
                seed(value);
            } else if (!declaration.declarationRoute().isRoot()) {
                value = value.select(declaration.declarationRoute()).asType(declaration.type());
            }
            return Optional.of(refresh(value, new LinkedHashSet<>()));
        }

        FormulaAlternatives refresh(FormulaAlternatives values, Set<io.mindspice.lyra.compiler.identity.CaptureId> activeCaptures) {
            ArrayList<ValueFormula> updated = new ArrayList<>();
            for (ValueFormula formula : values.formulas()) {
                if (!(formula instanceof ValueFormula.Lambda lambda)) { updated.add(formula); continue; }
                seed(FormulaAlternatives.singleton(lambda));
                var replacements = new TreeMap<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives>();
                for (var entry : lambda.captures().entrySet()) {
                    var info = metadata.get(entry.getKey());
                    FormulaAlternatives value = info != null && info.isSharedCell()
                            ? cells.getOrDefault(info.sharedCellId().orElseThrow(), entry.getValue())
                            : captured.getOrDefault(entry.getKey(), entry.getValue());
                    if (activeCaptures.add(entry.getKey())) {
                        value = refresh(value, activeCaptures);
                        activeCaptures.remove(entry.getKey());
                    }
                    replacements.put(entry.getKey(), value);
                }
                updated.add(lambda.withCaptures(replacements));
            }
            FormulaAlternatives result = new FormulaAlternatives(values.rootType(), updated, values.exactOverrides());
            limits.requireFormulaAlternatives(result.size());
            requireProjectionDepth(result);
            return result;
        }

        CapturedCellWrite apply(CapturedCellWrite write) {
            DeclarationId identity = write.sharedCellId().orElse(write.declarationId());
            FormulaAlternatives original = cells.get(identity);
            if (original == null && write.captureId().isPresent()) original = captured.get(write.captureId().orElseThrow());
            if (original == null) {
                // A declaration may only be read by a nested callback; retrieve its exact caller fact.
                for (CallableSummary summary : summaries.values()) {
                    for (var capture : summary.captures()) {
                        if (capture.declarationId().equals(write.declarationId())) {
                            original = resolver.apply(new ValueFormula.Declaration(write.declarationId(),
                                    capture.type())).orElse(null);
                            if (original != null) break;
                        }
                    }
                    if (original != null) break;
                }
            }
            FormulaAlternatives value = write.value();
            seed(value);
            if (original != null) {
                FormulaAlternatives old = write.route().isRoot() ? original : original.select(write.route()).asType(value.rootType());
                value = old.join(value);
                FormulaAlternatives next = write.route().isRoot() ? value
                        : write.route().containsWildcard() ? original.replaceUnknown(write.route(), value)
                        : original.replaceExact(write.route(), value);
                cells.put(identity, next);
                if (!write.route().isRoot()) {
                    FormulaAlternatives before = original;
                    FormulaAlternatives replacement = value;
                    captured.replaceAll((id, existing) -> updateAliases(existing, before, write.route(), replacement));
                    cells.replaceAll((id, existing) -> updateAliases(existing, before, write.route(), replacement));
                }
            } else {
                if (!write.route().isRoot()) throw new NestedTransferFailure(failure(
                        CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                        "repeated aggregate write has no caller identity", write.span()));
                cells.merge(identity, value, FormulaAlternatives::join);
            }
            return new CapturedCellWrite(write.sequence(), write.captureId(), write.parameterIndex(),
                    write.declarationId(), write.sharedCellId(), write.kind(), write.route(), value,
                    write.span(), write.operationSite());
        }

        private FormulaAlternatives updateAliases(FormulaAlternatives existing, FormulaAlternatives target,
                                                   ProjectionPath route, FormulaAlternatives replacement) {
            FormulaAlternatives result = existing;
            for (ValueFormula source : target.formulas()) {
                if (!(source.type().withoutQualifiers() instanceof ArrayType)
                        || source.resultRoute().depth() >= route.depth()
                        || !route.steps().subList(0, source.resultRoute().depth()).equals(source.resultRoute().steps())) continue;
                for (ValueFormula candidate : existing.formulas()) {
                    if (!candidate.withResultRoute(ProjectionPath.root()).equals(source.withResultRoute(ProjectionPath.root()))) continue;
                    ProjectionPath selected = candidate.resultRoute().compose(route.suffix(source.resultRoute().depth()));
                    FormulaAlternatives old = result.select(selected).asType(replacement.rootType());
                    result = selected.containsWildcard() ? result.replaceUnknown(selected, old.join(replacement))
                            : result.replaceExact(selected, old.join(replacement));
                }
            }
            return result;
        }
    }

    private void addCandidateCallWitness(
            List<EagerEffectWitness> effects,
            CallableSummary owner,
            CallableCallReference call,
            Optional<LambdaId> targetLambda,
            ModuleId targetModule) {
        if (owner.moduleId().equals(targetModule)) {
            return;
        }
        EagerEffectWitness.Kind kind = switch (call.kind()) {
            case DIRECT -> EagerEffectWitness.Kind.DIRECT_CALL;
            case NAMESPACE -> EagerEffectWitness.Kind.NAMESPACE_CALL;
            case CALLABLE -> EagerEffectWitness.Kind.CALLABLE_CALL;
            case PARAMETER -> EagerEffectWitness.Kind.PARAMETER_CALL;
            case CAPTURE -> EagerEffectWitness.Kind.CAPTURE_CALL;
            case CONSTRUCTION -> EagerEffectWitness.Kind.DIRECT_CALL;
        };
        io.mindspice.lyra.compiler.identity.FlowSiteId site =
                call.siteId().orElseThrow();
        addEffect(effects, new EagerEffectWitness(
                owner.moduleId(), targetModule, kind, call.span(),
                call.targetDeclaration(), call.referenceId(), targetLambda,
                List.of(call.span()), List.of(), false,
                Optional.of(site), List.of(site)));
    }

    private void addEffect(
            List<EagerEffectWitness> effects,
            EagerEffectWitness effect) {
        if (!effects.contains(effect)) {
            effects.add(effect);
        }
        limits.requireEffects(new TreeSet<>(effects).size());
    }

    private boolean isMaterializedStaticCall(CallableCallReference call) {
        if (requiresHeapTransfer(call, new LinkedHashSet<>())) return false;
        if (call.repeat().isPresent()) return false;
        if (containsCallResult(call.target())
                || call.arguments().stream().anyMatch(this::containsCallResult)
                || containsCallerResolvedCallable(call.target())
                || call.arguments().stream().anyMatch(
                this::containsCallerResolvedCallable)) {
            return false;
        }
        TargetResolution resolution = targetAlternatives(call.target());
        return resolution.complete()
                && !resolution.alternatives().isEmpty()
                && resolution.alternatives().stream()
                .allMatch(alternative -> alternative.isIntrinsic()
                        || summaries.containsKey(alternative.lambdaId().orElseThrow()));
    }

    public boolean requiresHeapTransfer(LambdaId lambda) {
        return requiresHeapTransfer(lambda, new LinkedHashSet<>());
    }

    private boolean requiresHeapTransfer(LambdaId lambda, java.util.Set<LambdaId> visited) {
        if (!visited.add(lambda)) return false;
        CallableSummary summary = summaries.get(lambda);
        return summary != null && summary.callReferences().stream()
                .anyMatch(call -> requiresHeapTransfer(call, visited));
    }

    boolean requiresHeapTransfer(CallableCallReference call, java.util.Set<LambdaId> visited) {
        if (call.kind() == CallableCallReference.Kind.CONSTRUCTION) return true;
        TargetResolution resolution = targetAlternatives(call.target());
        return resolution.alternatives().stream().anyMatch(target -> !target.isIntrinsic()
                && requiresHeapTransfer(target.lambdaId().orElseThrow(), visited));
    }

    private boolean containsCallerResolvedCallable(
            FormulaAlternatives alternatives) {
        for (ValueFormula formula : alternatives.formulas()) {
            if ((formula instanceof ValueFormula.Parameter
                    || formula instanceof ValueFormula.Capture)
                    && containsCallableType(formula.type())) {
                return true;
            }
            if (formula instanceof ValueFormula.Declaration declaration
                    && declaration.declarationRoute().isRoot()
                    && formula.type().withoutQualifiers() instanceof FunctionType
                    && !lambdaByDeclaration.containsKey(
                    declaration.declarationId())
                    && !intrinsicDeclarations.containsKey(
                    declaration.declarationId())) {
                return true;
            }
            if (formula instanceof ValueFormula.Lambda lambda
                    && lambda.captures().values().stream().anyMatch(
                    this::containsCallerResolvedCallable)) {
                return true;
            }
        }
        return false;
    }

    static boolean containsCallableType(LyraType type) {
        LyraType value = Objects.requireNonNull(type, "type").withoutQualifiers();
        if (value instanceof FunctionType) {
            return true;
        }
        if (value instanceof ArrayType array) {
            return containsCallableType(array.elementType());
        }
        if (value instanceof TupleType tuple) {
            return tuple.memberTypes().stream().anyMatch(
                    CallableSummarySet::containsCallableType);
        }
        return false;
    }

    private boolean containsCallResult(FormulaAlternatives alternatives) {
        for (ValueFormula formula : alternatives.formulas()) {
            if (formula instanceof ValueFormula.CallResult) {
                return true;
            }
            if (formula instanceof ValueFormula.Lambda lambda
                    && lambda.captures().values().stream()
                    .anyMatch(this::containsCallResult)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasOnlyActiveTargets(
            CallableCallReference call,
            List<FormulaAlternatives> arguments,
            List<FormulaAlternatives> writeArguments,
            Map<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> captures,
            Function<ValueFormula.Declaration, Optional<FormulaAlternatives>>
                    declarationResolver,
            CallableSummary owner,
            SourceSpan callSpan,
            LinkedHashSet<LambdaId> active) {
        Optional<FormulaAlternatives> target = substituteAlternatives(
                call.target(), arguments, writeArguments, captures,
                declarationResolver, owner, callSpan, active, new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), new LinkedHashSet<>());
        TargetResolution resolution = target.map(this::targetAlternatives)
                .orElseGet(TargetResolution::empty);
        return (resolution.complete()
                && !resolution.alternatives().isEmpty()
                && resolution.alternatives().stream()
                .allMatch(value -> value.lambdaId().map(active::contains).orElse(false)))
                || isDeferredCallableTarget(target.orElse(null));
    }

    private boolean isDeferredCallableTarget(FormulaAlternatives target) {
        return target != null && !target.formulas().isEmpty()
                && target.formulas().stream().allMatch(formula ->
                formula.resultRoute().isRoot()
                        && formula.type().withoutQualifiers() instanceof FunctionType
                        && formula instanceof ValueFormula.Declaration declaration
                        && deferredCallableDeclarations.contains(declaration.declarationId()));
    }

    private Optional<FormulaAlternatives> substituteAlternatives(
            FormulaAlternatives source,
            List<FormulaAlternatives> arguments,
            List<FormulaAlternatives> writeArguments,
            Map<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> captures,
            Function<ValueFormula.Declaration, Optional<FormulaAlternatives>>
                    declarationResolver,
            CallableSummary owner,
            SourceSpan callSpan,
            LinkedHashSet<LambdaId> active,
            List<CapturedCellWrite> writes,
            List<OwnershipRequirement> ownershipRequirements,
            List<EagerEffectWitness> effects,
            LinkedHashSet<SummaryCallId> appliedCalls) {
        ArrayList<ValueFormula> formulas = new ArrayList<>();
        ArrayList<FormulaAlternatives.ExactOverride> formulaOverrides = new ArrayList<>();
        for (ValueFormula formula : source.formulas()) {
            Optional<List<ValueFormula>> substituted = substituteFormula(
                    formula, arguments, writeArguments, captures,
                    declarationResolver, owner, callSpan, active, writes,
                    ownershipRequirements, effects, appliedCalls);
            if (substituted.isEmpty()) {
                return Optional.empty();
            }
            List<ValueFormula> replacements = substituted.orElseThrow();
            formulas.addAll(replacements);
            formulaOverrides.addAll(source.rebaseOverrides(formula, replacements));
            limits.requireFormulaAlternatives(new LinkedHashSet<>(formulas).size());
        }
        if (formulas.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new FormulaAlternatives(
                source.rootType(), formulas, formulaOverrides));
    }

    private void requireProjectionDepth(FormulaAlternatives alternatives) {
        for (ValueFormula formula : alternatives.formulas()) {
            limits.requireProjectionDepth(formula.resultRoute().depth());
            if (formula instanceof ValueFormula.Parameter parameter) {
                limits.requireProjectionDepth(parameter.parameterRoute().depth());
            } else if (formula instanceof ValueFormula.Capture capture) {
                limits.requireProjectionDepth(capture.captureRoute().depth());
            } else if (formula instanceof ValueFormula.Declaration declaration) {
                limits.requireProjectionDepth(declaration.declarationRoute().depth());
            } else if (formula instanceof ValueFormula.CallResult call) {
                limits.requireProjectionDepth(call.callRoute().depth());
            } else if (formula instanceof ValueFormula.Lambda lambda) {
                for (FormulaAlternatives captured : lambda.captures().values()) {
                    limits.requireFormulaAlternatives(captured.size());
                    requireProjectionDepth(captured);
                }
            }
        }
    }

    private TargetResolution targetAlternatives(FormulaAlternatives target) {
        TreeMap<String, TargetAlternative> ordered = new TreeMap<>();
        boolean complete = true;
        for (ValueFormula formula : target.formulas()) {
            if (!formula.resultRoute().isRoot()) {
                complete = false;
                continue;
            }
            if (formula instanceof ValueFormula.Lambda lambda) {
                TargetAlternative alternative = TargetAlternative.lambda(
                        lambda.lambdaId(), lambda.captures());
                ordered.put(alternative.canonicalKey(), alternative);
            } else if (formula instanceof ValueFormula.Declaration declaration) {
                LambdaId lambda = lambdaByDeclaration.get(declaration.declarationId());
                ModuleId intrinsicModule = intrinsicDeclarations.get(declaration.declarationId());
                if (lambda != null) {
                    TargetAlternative alternative = TargetAlternative.lambda(lambda, Map.of());
                    ordered.put(alternative.canonicalKey(), alternative);
                } else if (intrinsicModule != null) {
                    TargetAlternative alternative = TargetAlternative.intrinsic(
                            declaration.declarationId(), intrinsicModule);
                    ordered.put(alternative.canonicalKey(), alternative);
                } else {
                    // A declaration/fresh-allocation formula projected out of
                    // an aggregate root records that aggregate's identity; it
                    // does not assert that the selected member is callable.
                    // Do not let that identity placeholder poison a finite
                    // callable union when a routed callable fact is present.
                    if (!declaration.declarationRoute().isRoot()
                            && formula.type().withoutQualifiers() instanceof FunctionType) {
                        continue;
                    }
                    complete = false;
                }
            } else if (formula instanceof ValueFormula.FreshAllocation fresh
                    && !fresh.resultRoute().isRoot()
                    && formula.type().withoutQualifiers() instanceof FunctionType) {
                // Same rule for a fresh aggregate allocation projected to a
                // member: only an explicit callable term can be a call target.
                continue;
            } else {
                complete = false;
            }
        }
        return new TargetResolution(List.copyOf(ordered.values()), complete);
    }

    private record TargetResolution(
            List<TargetAlternative> alternatives,
            boolean complete) {
        private TargetResolution {
            alternatives = List.copyOf(alternatives);
        }

        private static TargetResolution empty() {
            return new TargetResolution(List.of(), false);
        }
    }

    private record TargetAlternative(
            Optional<LambdaId> lambdaId,
            Optional<DeclarationId> intrinsicDeclaration,
            Optional<ModuleId> moduleId,
            Map<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> captures) {
        private TargetAlternative {
            Objects.requireNonNull(lambdaId, "lambdaId");
            Objects.requireNonNull(intrinsicDeclaration, "intrinsicDeclaration");
            Objects.requireNonNull(moduleId, "moduleId");
            Objects.requireNonNull(captures, "captures");
            if (lambdaId.isPresent() == intrinsicDeclaration.isPresent()) {
                throw new IllegalArgumentException(
                        "a target alternative must identify one lambda or intrinsic");
            }
            if (intrinsicDeclaration.isPresent()
                    && (moduleId.isEmpty() || !captures.isEmpty())) {
                throw new IllegalArgumentException(
                        "an intrinsic target needs a module and cannot capture values");
            }
            TreeMap<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> ordered = new TreeMap<>();
            captures.forEach((capture, value) -> ordered.put(
                    Objects.requireNonNull(capture, "capture"),
                    Objects.requireNonNull(value, "capture value")));
            captures = Collections.unmodifiableMap(ordered);
        }

        private static TargetAlternative lambda(
                LambdaId lambda,
                Map<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> captures) {
            return new TargetAlternative(Optional.of(lambda), Optional.empty(),
                    Optional.empty(), captures);
        }

        private static TargetAlternative intrinsic(
                DeclarationId declaration,
                ModuleId module) {
            return new TargetAlternative(Optional.empty(), Optional.of(declaration),
                    Optional.of(module), Map.of());
        }

        private boolean isIntrinsic() {
            return intrinsicDeclaration.isPresent();
        }

        private String canonicalKey() {
            return lambdaId.map(Object::toString)
                    .orElseGet(() -> "intrinsic:" + intrinsicDeclaration.orElseThrow())
                    + ":" + captures;
        }
    }

    private SummaryTransferResult.Failure failure(
            CallableSummaryResult.InternalFailure.Kind kind,
            String message,
            SourceSpan span) {
        return new SummaryTransferResult.Failure(
                CallableSummaryResult.InternalFailure.at(kind, message, span));
    }

    private static final class NestedTransferFailure extends RuntimeException {
        private final SummaryTransferResult.Failure result;

        private NestedTransferFailure(SummaryTransferResult.Failure result) {
            super(Objects.requireNonNull(result, "result").failure().message());
            this.result = result;
        }

        private SummaryTransferResult.Failure result() {
            return result;
        }
    }

    static List<CallableScc> singletonComponentsForTransfer(
            Collection<CallableSummary> summaries) {
        Objects.requireNonNull(summaries, "summaries");
        return singletonComponents(new ArrayList<>(summaries));
    }

    private static List<CallableScc> singletonComponents(List<CallableSummary> summaries) {
        Objects.requireNonNull(summaries, "summaries");
        ArrayList<CallableSummary> ordered = new ArrayList<>(summaries);
        ordered.sort(Comparator.comparing(CallableSummary::lambdaId));
        ArrayList<CallableScc> result = new ArrayList<>();
        int ordinal = 0;
        for (CallableSummary summary : ordered) {
            result.add(new CallableScc(ordinal++, List.of(summary.lambdaId()), List.of(), false));
        }
        return result;
    }

    /** One deterministic callable dependency SCC. */
    public record CallableScc(
            int ordinal,
            List<LambdaId> members,
            List<SummaryCallId> links,
            boolean recursive) {
        public CallableScc {
            if (ordinal < 0) {
                throw new IllegalArgumentException("callable SCC ordinal must not be negative");
            }
            Objects.requireNonNull(members, "members");
            Objects.requireNonNull(links, "links");
            TreeSet<LambdaId> orderedMembers = new TreeSet<>();
            for (LambdaId member : members) {
                orderedMembers.add(Objects.requireNonNull(member, "member"));
            }
            if (orderedMembers.isEmpty()) {
                throw new IllegalArgumentException("callable SCC needs a member");
            }
            members = List.copyOf(orderedMembers);
            TreeSet<SummaryCallId> orderedLinks = new TreeSet<>();
            for (SummaryCallId link : links) {
                orderedLinks.add(Objects.requireNonNull(link, "link"));
            }
            links = List.copyOf(orderedLinks);
        }

        public List<LambdaId> declarations() {
            return members;
        }

        public String canonicalKey() {
            return ordinal + ":" + members + ":" + links + ":recursive=" + recursive;
        }
    }
}
