package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.LyraType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Deterministic least-fixed-point solver for the finite callable-summary domain. */
public final class CallableSummarySolver {
    private CallableSummarySolver() {
    }

    public static CallableSummaryResult solve(Collection<CallableSummary> rawSummaries) {
        return solve(rawSummaries, Map.of(), SummaryLimits.DEFAULT);
    }

    public static CallableSummaryResult solve(
            Collection<CallableSummary> rawSummaries,
            Map<DeclarationId, LambdaId> lambdaByDeclaration) {
        return solve(rawSummaries, lambdaByDeclaration, SummaryLimits.DEFAULT);
    }

    public static CallableSummaryResult solve(
            Collection<CallableSummary> rawSummaries,
            Map<DeclarationId, LambdaId> lambdaByDeclaration,
            SummaryLimits limits) {
        return solve(rawSummaries, lambdaByDeclaration, Map.of(), limits);
    }

    public static CallableSummaryResult solve(
            Collection<CallableSummary> rawSummaries,
            Map<DeclarationId, LambdaId> lambdaByDeclaration,
            Map<DeclarationId, ModuleId> intrinsicDeclarations,
            SummaryLimits limits) {
        return solve(rawSummaries, lambdaByDeclaration, intrinsicDeclarations,
                Set.of(), Map.of(), limits);
    }

    static CallableSummaryResult solve(
            Collection<CallableSummary> rawSummaries,
            Map<DeclarationId, LambdaId> lambdaByDeclaration,
            Map<DeclarationId, ModuleId> intrinsicDeclarations,
            Set<DeclarationId> computedCallableDeclarations,
            Map<DeclarationId, List<LambdaId>> potentialCallableLambdas,
            SummaryLimits limits) {
        return solve(rawSummaries, lambdaByDeclaration, intrinsicDeclarations,
                computedCallableDeclarations, Set.of(), potentialCallableLambdas, limits);
    }

    static CallableSummaryResult solve(
            Collection<CallableSummary> rawSummaries,
            Map<DeclarationId, LambdaId> lambdaByDeclaration,
            Map<DeclarationId, ModuleId> intrinsicDeclarations,
            Set<DeclarationId> computedCallableDeclarations,
            Set<DeclarationId> externalCallableDeclarations,
            Map<DeclarationId, List<LambdaId>> potentialCallableLambdas,
            SummaryLimits limits) {
        return solve(rawSummaries, lambdaByDeclaration, intrinsicDeclarations, computedCallableDeclarations,
                externalCallableDeclarations, potentialCallableLambdas, limits, Set.of());
    }

    static CallableSummaryResult solve(Collection<CallableSummary> rawSummaries,
            Map<DeclarationId, LambdaId> lambdaByDeclaration, Map<DeclarationId, ModuleId> intrinsicDeclarations,
            Set<DeclarationId> computedCallableDeclarations, Set<DeclarationId> externalCallableDeclarations,
            Map<DeclarationId, List<LambdaId>> potentialCallableLambdas, SummaryLimits limits,
            Set<LambdaId> retained) {
        Objects.requireNonNull(rawSummaries, "rawSummaries");
        Objects.requireNonNull(lambdaByDeclaration, "lambdaByDeclaration");
        Objects.requireNonNull(intrinsicDeclarations, "intrinsicDeclarations");
        Objects.requireNonNull(computedCallableDeclarations,
                "computedCallableDeclarations");
        Objects.requireNonNull(externalCallableDeclarations,
                "externalCallableDeclarations");
        Objects.requireNonNull(potentialCallableLambdas,
                "potentialCallableLambdas");
        Objects.requireNonNull(limits, "limits");
        Optional<SourceSpan> firstSummarySpan = rawSummaries.stream()
                .filter(Objects::nonNull).map(CallableSummary::span).findFirst();
        try {
            TreeMap<LambdaId, CallableSummary> raw = index(rawSummaries);
            TreeMap<DeclarationId, LambdaId> declarations = new TreeMap<>();
            lambdaByDeclaration.forEach((declaration, lambda) -> {
                DeclarationId declarationId = Objects.requireNonNull(declaration, "declaration");
                LambdaId lambdaId = Objects.requireNonNull(lambda, "lambda");
                if (!raw.containsKey(lambdaId)) {
                    throw new MissingFactException(
                            "declaration links to a lambda without a summary: " + lambdaId,
                            raw.isEmpty() ? Optional.empty() : Optional.of(raw.firstEntry().getValue().span()));
                }
                declarations.put(declarationId, lambdaId);
            });
            TreeMap<DeclarationId, ModuleId> intrinsics = new TreeMap<>();
            intrinsicDeclarations.forEach((declaration, module) -> {
                DeclarationId declarationId = Objects.requireNonNull(
                        declaration, "intrinsic declaration");
                if (declarations.containsKey(declarationId)) {
                    throw new IllegalArgumentException(
                            "a declaration cannot identify both a lambda and an intrinsic");
                }
                intrinsics.put(declarationId,
                        Objects.requireNonNull(module, "intrinsic module"));
            });
            TreeSet<DeclarationId> computedDeclarations = new TreeSet<>();
            for (DeclarationId declaration : computedCallableDeclarations) {
                DeclarationId value = Objects.requireNonNull(
                        declaration, "computed callable declaration");
                if (declarations.containsKey(value) || intrinsics.containsKey(value)) {
                    throw new IllegalArgumentException(
                            "a computed callable declaration cannot be statically linked");
                }
                computedDeclarations.add(value);
            }
            TreeMap<DeclarationId, List<LambdaId>> potentialDeclarations =
                    new TreeMap<>();
            potentialCallableLambdas.forEach((declaration, lambdas) -> {
                DeclarationId declarationId = Objects.requireNonNull(
                        declaration, "potential callable declaration");
                if (!computedDeclarations.contains(declarationId)) {
                    throw new IllegalArgumentException(
                            "only computed callable declarations may retain potential SCC targets");
                }
                TreeSet<LambdaId> targets = new TreeSet<>();
                for (LambdaId lambda : Objects.requireNonNull(
                        lambdas, "potential callable lambdas")) {
                    LambdaId target = Objects.requireNonNull(
                            lambda, "potential callable lambda");
                    if (!raw.containsKey(target)) {
                        throw new MissingFactException(
                                "potential callable declaration links to a lambda without a summary: "
                                        + target,
                                raw.isEmpty() ? Optional.empty()
                                        : Optional.of(raw.firstEntry().getValue().span()));
                    }
                    targets.add(target);
                }
                if (targets.isEmpty()) {
                    throw new IllegalArgumentException(
                            "a potential callable declaration needs a lambda target");
                }
                potentialDeclarations.put(declarationId, List.copyOf(targets));
            });
            Map<LambdaId, List<LambdaId>> adjacency = buildAdjacency(
                    raw, declarations, intrinsics.keySet(), computedDeclarations,
                    externalCallableDeclarations, potentialDeclarations);
            List<CallableSummarySet.CallableScc> components = components(
                    raw.keySet(), adjacency, raw, declarations,
                    potentialDeclarations);
            Map<LambdaId, Integer> componentByLambda = componentIndex(components);
            Map<Integer, Set<Integer>> dependencies = componentDependencies(
                    raw, adjacency, componentByLambda);
            Map<Integer, CallableSummarySet.CallableScc> componentByOrdinal = new TreeMap<>();
            for (CallableSummarySet.CallableScc component : components) {
                componentByOrdinal.put(component.ordinal(), component);
            }
            TreeMap<LambdaId, CallableSummary> solved = new TreeMap<>();
            solved.putAll(raw);
            Set<Integer> completed = new HashSet<>();
            for (var component : components) {
                if (retained.containsAll(component.members())) completed.add(component.ordinal());
                else if (component.members().stream().anyMatch(retained::contains)) {
                    throw new IllegalArgumentException("new callable SCC cannot redefine retained producers");
                }
            }
            for (CallableSummarySet.CallableScc component : components) {
                solveComponent(component, dependencies, componentByOrdinal,
                        raw, declarations, intrinsics, computedDeclarations,
                        externalCallableDeclarations, solved, completed, limits);
            }
            return new CallableSummaryResult.Success(
                    new CallableSummarySet(
                            new ArrayList<>(solved.values()), declarations, intrinsics, components));
        } catch (TransferFailureException failure) {
            return new CallableSummaryResult.Failure(failure.failure());
        } catch (MissingFactException failure) {
            return CallableSummaryResult.failure(
                    CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                    failure.getMessage(), failure.span());
        } catch (SummaryLimits.SummaryDomainException failure) {
            return CallableSummaryResult.failure(
                    CallableSummaryResult.InternalFailure.Kind.DOMAIN_LIMIT,
                    failure.getMessage(), firstSummarySpan);
        } catch (NonConvergentException failure) {
            return CallableSummaryResult.failure(
                    CallableSummaryResult.InternalFailure.Kind.NON_CONVERGENT,
                    failure.getMessage(), failure.span());
        } catch (IllegalArgumentException failure) {
            return CallableSummaryResult.failure(
                    CallableSummaryResult.InternalFailure.Kind.INCONSISTENT_SUMMARY,
                    failure.getMessage() == null ? "invalid callable summary" : failure.getMessage(),
                    Optional.empty());
        }
    }

    private static TreeMap<LambdaId, CallableSummary> index(
            Collection<CallableSummary> values) {
        TreeMap<LambdaId, CallableSummary> result = new TreeMap<>();
        for (CallableSummary value : values) {
            CallableSummary summary = Objects.requireNonNull(value, "summary");
            if (result.put(summary.lambdaId(), summary) != null) {
                throw new IllegalArgumentException("more than one raw summary exists for "
                        + summary.lambdaId());
            }
        }
        return result;
    }

    private static Map<LambdaId, List<LambdaId>> buildAdjacency(
            Map<LambdaId, CallableSummary> raw,
            Map<DeclarationId, LambdaId> lambdaByDeclaration,
            Set<DeclarationId> intrinsicDeclarations,
            Set<DeclarationId> computedCallableDeclarations,
            Set<DeclarationId> externalCallableDeclarations,
            Map<DeclarationId, List<LambdaId>> potentialCallableLambdas) {
        TreeMap<LambdaId, List<LambdaId>> result = new TreeMap<>();
        for (Map.Entry<LambdaId, CallableSummary> entry : raw.entrySet()) {
            CallableSummary summary = entry.getValue();
            validateCallResultDependencies(summary);
            TreeSet<LambdaId> targets = new TreeSet<>();
            for (CallableCallReference call : summary.callReferences()) {
                targets.addAll(dependencyTargets(
                        call, lambdaByDeclaration, potentialCallableLambdas));
                validateCallShape(call, raw, lambdaByDeclaration,
                        intrinsicDeclarations, computedCallableDeclarations,
                        externalCallableDeclarations);
            }
            targets.removeIf(target -> !raw.containsKey(target));
            result.put(entry.getKey(), List.copyOf(targets));
        }
        return result;
    }

    private static void validateCallShape(
            CallableCallReference call,
            Map<LambdaId, CallableSummary> raw,
            Map<DeclarationId, LambdaId> lambdaByDeclaration,
            Set<DeclarationId> intrinsicDeclarations,
            Set<DeclarationId> computedCallableDeclarations,
            Set<DeclarationId> externalCallableDeclarations) {
        Set<LambdaId> targets = staticTargets(call, lambdaByDeclaration);
        boolean intrinsicTarget = hasIntrinsicTarget(call, intrinsicDeclarations);
        boolean callerDependentTarget = call.kind() == CallableCallReference.Kind.CONSTRUCTION || !call.targetParameterIndexes().isEmpty()
                || !call.targetCaptureIds().isEmpty()
                || containsCallResult(call.target())
                || containsCallerCallablePlaceholder(call.target())
                || containsComputedDeclaration(
                call.target(), computedCallableDeclarations)
                || containsExternalDeclaration(
                call.target(), externalCallableDeclarations);
        boolean invalidTargetFormula = call.target().formulas().stream()
                .anyMatch(formula -> !isRecoverableTargetFormula(
                        formula, lambdaByDeclaration, intrinsicDeclarations,
                        computedCallableDeclarations, externalCallableDeclarations));
        if (invalidTargetFormula) {
            throw new MissingFactException(
                    "typed callable reference contains an unrecoverable callable alternative",
                    Optional.of(call.span()));
        }
        if (targets.isEmpty() && !intrinsicTarget && !callerDependentTarget) {
            String message = call.kind() == CallableCallReference.Kind.DIRECT
                    || call.kind() == CallableCallReference.Kind.NAMESPACE
                    ? "direct callable reference has no reachable lambda summary"
                    : "typed callable reference has no recoverable callable alternative";
            throw new MissingFactException(message, Optional.of(call.span()));
        }
        for (LambdaId target : targets) {
            if (!raw.containsKey(target)) {
                throw new MissingFactException(
                        "callable reference targets a lambda without a summary: " + target,
                        Optional.of(call.span()));
            }
        }
    }

    private static void validateCallResultDependencies(
            CallableSummary summary) {
        TreeMap<SummaryCallId, CallableCallReference> calls = new TreeMap<>();
        for (CallableCallReference call : summary.callReferences()) {
            calls.put(call.id(), call);
        }
        for (CallableCallReference call : summary.callReferences()) {
            validateCallResultDependencies(call.target(), call, calls);
            call.repeat().ifPresent(repeat -> {
                repeat.predicate().ifPresent(value -> validateCallResultDependencies(value, call, calls));
                repeat.environment().values().forEach(value -> validateCallResultDependencies(value, call, calls));
            });
            for (FormulaAlternatives argument : call.arguments()) {
                validateCallResultDependencies(argument, call, calls);
            }
        }
        for (ValueFormula formula : summary.returnFormula().formulas()) {
            validateCallResultDependencies(formula, summary.span(), calls);
        }
        for (CapturedCellWrite write : summary.writes()) {
            for (ValueFormula formula : write.value().formulas()) {
                validateCallResultDependencies(formula, write.span(), calls);
            }
        }
    }

    private static void validateCallResultDependencies(
            FormulaAlternatives alternatives,
            CallableCallReference consumer,
            Map<SummaryCallId, CallableCallReference> calls) {
        for (ValueFormula formula : alternatives.formulas()) {
            validateCallResultDependencies(formula, consumer.span(), calls);
            for (SummaryCallId dependency : callResultDependencies(formula)) {
                if (dependency.sequence() >= consumer.id().sequence()) {
                    throw new MissingFactException(
                            "call result dependency does not precede its consuming call: "
                                    + dependency,
                            Optional.of(consumer.span()));
                }
            }
        }
    }

    private static void validateCallResultDependencies(
            ValueFormula formula,
            SourceSpan span,
            Map<SummaryCallId, CallableCallReference> calls) {
        for (SummaryCallId dependency : callResultDependencies(formula)) {
            if (!calls.containsKey(dependency)) {
                throw new MissingFactException(
                        "symbolic call result has no producer call: " + dependency,
                        Optional.of(span));
            }
        }
    }

    private static Set<SummaryCallId> callResultDependencies(
            ValueFormula formula) {
        TreeSet<SummaryCallId> result = new TreeSet<>();
        collectCallResultDependencies(formula, result);
        return result;
    }

    private static void collectCallResultDependencies(
            ValueFormula formula,
            Set<SummaryCallId> result) {
        if (formula instanceof ValueFormula.CallResult callResult) {
            result.add(callResult.callId());
        } else if (formula instanceof ValueFormula.Lambda lambda) {
            for (FormulaAlternatives captured : lambda.captures().values()) {
                for (ValueFormula value : captured.formulas()) {
                    collectCallResultDependencies(value, result);
                }
            }
        }
    }

    private static boolean containsCallResult(
            FormulaAlternatives alternatives) {
        return alternatives.formulas().stream()
                .anyMatch(formula -> !callResultDependencies(formula).isEmpty());
    }

    private static Set<LambdaId> staticTargets(
            CallableCallReference call,
            Map<DeclarationId, LambdaId> lambdaByDeclaration) {
        TreeSet<LambdaId> result = new TreeSet<>();
        call.targetLambda().ifPresent(result::add);
        call.targetDeclaration().map(lambdaByDeclaration::get)
                .filter(Objects::nonNull).ifPresent(result::add);
        for (ValueFormula formula : call.target().formulas()) {
            if (formula instanceof ValueFormula.Lambda lambda
                    && formula.resultRoute().isRoot()) {
                result.add(lambda.lambdaId());
            } else if (formula instanceof ValueFormula.Declaration declaration
                    && formula.resultRoute().isRoot()) {
                LambdaId target = lambdaByDeclaration.get(declaration.declarationId());
                if (target != null) {
                    result.add(target);
                }
            }
        }
        return result;
    }

    private static Set<LambdaId> dependencyTargets(
            CallableCallReference call,
            Map<DeclarationId, LambdaId> lambdaByDeclaration,
            Map<DeclarationId, List<LambdaId>> potentialCallableLambdas) {
        TreeSet<LambdaId> result = new TreeSet<>(
                staticTargets(call, lambdaByDeclaration));
        call.targetDeclaration().ifPresent(declaration -> result.addAll(
                potentialCallableLambdas.getOrDefault(declaration, List.of())));
        for (ValueFormula formula : call.target().formulas()) {
            if (formula instanceof ValueFormula.Declaration declaration
                    && formula.resultRoute().isRoot()) {
                result.addAll(potentialCallableLambdas.getOrDefault(
                        declaration.declarationId(), List.of()));
            }
        }
        return result;
    }

    private static boolean hasIntrinsicTarget(
            CallableCallReference call,
            Set<DeclarationId> intrinsicDeclarations) {
        if (call.targetDeclaration().filter(intrinsicDeclarations::contains).isPresent()) {
            return true;
        }
        return call.target().formulas().stream()
                .filter(ValueFormula.Declaration.class::isInstance)
                .map(ValueFormula.Declaration.class::cast)
                .filter(value -> value.resultRoute().isRoot())
                .anyMatch(value -> intrinsicDeclarations.contains(value.declarationId()));
    }

    private static boolean requiresCallerTargetSubstitution(
            CallableCallReference call,
            Set<DeclarationId> computedCallableDeclarations,
            Set<DeclarationId> externalCallableDeclarations) {
        return call.kind() == CallableCallReference.Kind.CONSTRUCTION || !call.targetParameterIndexes().isEmpty()
                || !call.targetCaptureIds().isEmpty()
                || containsCallerCallablePlaceholder(call.target())
                || containsComputedDeclaration(
                call.target(), computedCallableDeclarations)
                || containsExternalDeclaration(
                call.target(), externalCallableDeclarations);
    }

    private static boolean isRecoverableTargetFormula(
            ValueFormula formula,
            Map<DeclarationId, LambdaId> lambdaByDeclaration,
            Set<DeclarationId> intrinsicDeclarations,
            Set<DeclarationId> computedCallableDeclarations,
            Set<DeclarationId> externalCallableDeclarations) {
        if (!formula.resultRoute().isRoot()) {
            return false;
        }
        if (formula instanceof ValueFormula.Lambda
                || formula instanceof ValueFormula.Constructor
                || formula instanceof ValueFormula.Parameter
                || formula instanceof ValueFormula.Capture
                || formula instanceof ValueFormula.CallResult) {
            return true;
        }
        if (formula instanceof ValueFormula.Declaration declaration) {
            if (!declaration.declarationRoute().isRoot()
                    && formula.type().withoutQualifiers() instanceof FunctionType) {
                return true;
            }
            return lambdaByDeclaration.containsKey(declaration.declarationId())
                    || intrinsicDeclarations.contains(declaration.declarationId())
                    || computedCallableDeclarations.contains(
                    declaration.declarationId())
                    || externalCallableDeclarations.contains(declaration.declarationId());
        }
        return false;
    }

    private static boolean containsCallerCallablePlaceholder(
            FormulaAlternatives alternatives) {
        for (ValueFormula formula : alternatives.formulas()) {
            if ((formula instanceof ValueFormula.Parameter
                    || formula instanceof ValueFormula.Capture)
                    && CallableSummarySet.containsCallableType(formula.type())) {
                return true;
            }
            if (formula instanceof ValueFormula.Declaration declaration
                    && !declaration.declarationRoute().isRoot()
                    && formula.type().withoutQualifiers() instanceof FunctionType) {
                // A callable selected from an external aggregate/nominal root
                // has no static declaration-to-lambda edge. Preserve the exact
                // projection for canonical caller-time object/route recovery.
                return true;
            }
            if (formula instanceof ValueFormula.Lambda lambda
                    && lambda.captures().values().stream().anyMatch(
                    CallableSummarySolver::containsCallerCallablePlaceholder)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsExternalDeclaration(
            FormulaAlternatives alternatives,
            Set<DeclarationId> externalCallableDeclarations) {
        for (ValueFormula formula : alternatives.formulas()) {
            if (formula instanceof ValueFormula.Declaration declaration
                    && externalCallableDeclarations.contains(declaration.declarationId())) {
                return true;
            }
            if (formula instanceof ValueFormula.Lambda lambda
                    && lambda.captures().values().stream().anyMatch(
                    captured -> containsExternalDeclaration(
                            captured, externalCallableDeclarations))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsComputedDeclaration(
            FormulaAlternatives alternatives,
            Set<DeclarationId> computedCallableDeclarations) {
        for (ValueFormula formula : alternatives.formulas()) {
            if (formula instanceof ValueFormula.Declaration declaration
                    && computedCallableDeclarations.contains(
                    declaration.declarationId())) {
                return true;
            }
            if (formula instanceof ValueFormula.Lambda lambda
                    && lambda.captures().values().stream().anyMatch(
                    captured -> containsComputedDeclaration(
                            captured, computedCallableDeclarations))) {
                return true;
            }
        }
        return false;
    }

    private static List<CallableSummarySet.CallableScc> components(
            Set<LambdaId> nodes,
            Map<LambdaId, List<LambdaId>> adjacency,
            Map<LambdaId, CallableSummary> summaries,
            Map<DeclarationId, LambdaId> lambdaByDeclaration,
            Map<DeclarationId, List<LambdaId>> potentialCallableLambdas) {
        Tarjan tarjan = new Tarjan(adjacency);
        for (LambdaId node : nodes.stream().sorted().toList()) {
            tarjan.visit(node);
        }
        ArrayList<List<LambdaId>> members = new ArrayList<>(tarjan.components());
        members.sort(Comparator.comparing(list -> list.stream().min(Comparator.naturalOrder()).orElseThrow()));
        ArrayList<CallableSummarySet.CallableScc> result = new ArrayList<>();
        for (int ordinal = 0; ordinal < members.size(); ordinal++) {
            List<LambdaId> componentMembers = members.get(ordinal);
            TreeSet<SummaryCallId> links = new TreeSet<>();
            boolean recursive = componentMembers.size() > 1;
            Set<LambdaId> memberSet = Set.copyOf(componentMembers);
            for (LambdaId member : componentMembers) {
                for (CallableCallReference call : summaries.get(member).callReferences()) {
                    Set<LambdaId> targets = dependencyTargets(
                            call, lambdaByDeclaration, potentialCallableLambdas);
                    if (targets.stream().anyMatch(memberSet::contains)) {
                        links.add(call.id());
                        recursive |= targets.contains(member);
                    }
                }
            }
            result.add(new CallableSummarySet.CallableScc(
                    ordinal, componentMembers, List.copyOf(links), recursive));
        }
        return List.copyOf(result);
    }

    private static Map<LambdaId, Integer> componentIndex(
            List<CallableSummarySet.CallableScc> components) {
        TreeMap<LambdaId, Integer> result = new TreeMap<>();
        for (CallableSummarySet.CallableScc component : components) {
            for (LambdaId member : component.members()) {
                if (result.put(member, component.ordinal()) != null) {
                    throw new IllegalArgumentException("lambda belongs to two callable SCCs");
                }
            }
        }
        return result;
    }

    private static Map<Integer, Set<Integer>> componentDependencies(
            Map<LambdaId, CallableSummary> raw,
            Map<LambdaId, List<LambdaId>> adjacency,
            Map<LambdaId, Integer> componentByLambda) {
        TreeMap<Integer, Set<Integer>> result = new TreeMap<>();
        for (LambdaId source : raw.keySet()) {
            int sourceComponent = componentByLambda.get(source);
            Set<Integer> dependencies = result.computeIfAbsent(
                    sourceComponent, ignored -> new TreeSet<>());
            for (LambdaId target : adjacency.getOrDefault(source, List.of())) {
                int targetComponent = componentByLambda.get(target);
                if (targetComponent != sourceComponent) {
                    dependencies.add(targetComponent);
                }
            }
        }
        return result;
    }

    private static void solveComponent(
            CallableSummarySet.CallableScc component,
            Map<Integer, Set<Integer>> dependencies,
            Map<Integer, CallableSummarySet.CallableScc> componentByOrdinal,
            Map<LambdaId, CallableSummary> raw,
            Map<DeclarationId, LambdaId> lambdaByDeclaration,
            Map<DeclarationId, ModuleId> intrinsicDeclarations,
            Set<DeclarationId> computedCallableDeclarations,
            Set<DeclarationId> externalCallableDeclarations,
            Map<LambdaId, CallableSummary> solved,
            Set<Integer> completed,
            SummaryLimits limits) {
        int ordinal = component.ordinal();
        if (completed.contains(ordinal)) {
            return;
        }
        // Components are collapsed before this routine is called, so a
        // dependency walk can never encounter an unfinished recursive SCC.
        for (Integer dependency : dependencies.getOrDefault(ordinal, Set.of()).stream().sorted().toList()) {
            CallableSummarySet.CallableScc dependencyComponent = componentByOrdinal.get(dependency);
            if (dependencyComponent != null) {
                solveComponent(dependencyComponent, dependencies, componentByOrdinal,
                        raw, lambdaByDeclaration, intrinsicDeclarations,
                        computedCallableDeclarations, externalCallableDeclarations,
                        solved, completed, limits);
            }
        }

        Set<SummaryCallId> recursiveCalls = Set.copyOf(component.links());
        int iteration = 0;
        while (true) {
            iteration++;
            if (iteration > limits.maxFixedPointIterations()) {
                LambdaId first = component.members().getFirst();
                throw new NonConvergentException(
                        "callable summary SCC did not converge",
                        Optional.of(raw.get(first).span()));
            }
            boolean stable = true;
            TreeMap<LambdaId, CallableSummary> next = new TreeMap<>(solved);
            for (LambdaId member : component.members()) {
                CallableSummary current = solved.get(member);
                CallableSummary candidate = derive(raw.get(member), solved,
                        lambdaByDeclaration, intrinsicDeclarations,
                        computedCallableDeclarations, externalCallableDeclarations,
                        recursiveCalls, limits);
                if (!sameSemanticState(current, candidate)) {
                    stable = false;
                }
                next.put(member, candidate);
            }
            solved.putAll(next);
            if (stable) {
                for (LambdaId member : component.members()) {
                    CallableSummary current = solved.get(member);
                    solved.put(member, current.solved(
                            current.returnFormula(), current.writes(),
                            current.ownershipRequirements(), current.eagerEffects(), iteration));
                }
                completed.add(ordinal);
                return;
            }
        }
    }

    private static CallableSummary derive(
            CallableSummary raw,
            Map<LambdaId, CallableSummary> solved,
            Map<DeclarationId, LambdaId> lambdaByDeclaration,
            Map<DeclarationId, ModuleId> intrinsicDeclarations,
            Set<DeclarationId> computedCallableDeclarations,
            Set<DeclarationId> externalCallableDeclarations,
            Set<SummaryCallId> recursiveCalls,
            SummaryLimits limits) {
        ArrayList<ValueFormula> formulas = new ArrayList<>();
        ArrayList<FormulaAlternatives.ExactOverride> formulaOverrides = new ArrayList<>();
        ArrayList<EagerEffectWitness> effects = new ArrayList<>(raw.eagerEffects());
        Map<SummaryCallId, CallableCallReference> calls = new TreeMap<>();
        Map<SummaryCallId, SummaryTransferResult.Success> transfers = new TreeMap<>();
        ArrayList<CallableCallReference> materializedCalls = new ArrayList<>();
        CallableSummarySet transferSet = new CallableSummarySet(
                new ArrayList<>(solved.values()), lambdaByDeclaration,
                intrinsicDeclarations);
        for (CallableCallReference call : raw.callReferences()) {
            calls.put(call.id(), call);
        }
        for (CallableCallReference call : raw.callReferences()) {
            FormulaAlternatives target = substituteKnownCallResults(
                    call.target(), calls, transfers, call.span(), limits);
            ArrayList<FormulaAlternatives> arguments = new ArrayList<>();
            for (FormulaAlternatives argument : call.arguments()) {
                arguments.add(substituteKnownCallResults(
                        argument, calls, transfers, call.span(), limits));
            }
            CallableCallReference materialized = call.withTransferFormulas(
                    target, arguments);
            materializedCalls.add(materialized);
            boolean deferredCallResult = containsCallResult(target)
                    || arguments.stream().anyMatch(CallableSummarySolver::containsCallResult);
            boolean deferredComputedDeclaration = containsComputedDeclaration(
                    target, computedCallableDeclarations)
                    || arguments.stream().anyMatch(argument ->
                    containsComputedDeclaration(
                            argument, computedCallableDeclarations));
            boolean deferredCallerCallable = containsCallerCallablePlaceholder(target)
                    || arguments.stream().anyMatch(
                    CallableSummarySolver::containsCallerCallablePlaceholder);
            boolean deferredExternalCallable = containsExternalDeclaration(
                    target, externalCallableDeclarations)
                    || arguments.stream().anyMatch(argument ->
                    containsExternalDeclaration(argument, externalCallableDeclarations));
            if (call.repeat().isEmpty() && !deferredCallResult
                    && !transferSet.requiresHeapTransfer(materialized, new LinkedHashSet<>())
                    && !deferredComputedDeclaration
                    && !deferredCallerCallable
                    && !deferredExternalCallable
                    && !requiresCallerTargetSubstitution(
                    materialized, computedCallableDeclarations,
                    externalCallableDeclarations)) {
                SummaryTransferResult transfer = transferSet.invoke(
                        target, arguments, call.span());
                if (transfer instanceof SummaryTransferResult.Failure failure) {
                    throw new TransferFailureException(failure.failure());
                }
                SummaryTransferResult.Success success = (SummaryTransferResult.Success) transfer;
                transfers.put(call.id(), success);
                addMaterializedCallWitnesses(
                        effects, raw, materialized, solved, lambdaByDeclaration);
                for (EagerEffectWitness effect : success.effects()) {
                    effects.add(effect.through(raw.moduleId(), call, limits));
                }
            }
        }
        for (ValueFormula formula : raw.returnFormula().formulas()) {
            List<ValueFormula> replacements = substituteKnownCallResults(
                    formula, calls, transfers, raw.span(), limits, new LinkedHashSet<>());
            formulas.addAll(replacements);
            formulaOverrides.addAll(raw.returnFormula().alternatives()
                    .rebaseOverrides(formula, replacements));
        }
        ArrayList<CapturedCellWrite> substitutedRawWrites = new ArrayList<>();
        for (CapturedCellWrite write : raw.writes()) {
            ArrayList<ValueFormula> values = new ArrayList<>();
            ArrayList<FormulaAlternatives.ExactOverride> valueOverrides = new ArrayList<>();
            for (ValueFormula value : write.value().formulas()) {
                List<ValueFormula> replacements = substituteKnownCallResults(
                        value, calls, transfers, write.span(), limits, new LinkedHashSet<>());
                values.addAll(replacements);
                valueOverrides.addAll(write.value().rebaseOverrides(value, replacements));
            }
            limits.requireFormulaAlternatives(new LinkedHashSet<>(values).size());
            substitutedRawWrites.add(new CapturedCellWrite(
                    write.sequence(), write.captureId(), write.parameterIndex(),
                    write.declarationId(), write.sharedCellId(), write.kind(), write.route(),
                    new FormulaAlternatives(
                            write.value().rootType(), values, valueOverrides), write.span(), write.operationSite()));
        }
        List<CapturedCellWrite> writes = rebaseWritesInCallerOrder(
                raw, transfers, substitutedRawWrites, recursiveCalls, limits);
        ArrayList<OwnershipRequirement> rawOwnershipRequirements =
                new ArrayList<>();
        for (OwnershipRequirement requirement : raw.ownershipRequirements()) {
            ArrayList<ValueFormula> values = new ArrayList<>();
            ArrayList<FormulaAlternatives.ExactOverride> overrides = new ArrayList<>();
            for (ValueFormula value : requirement.value().formulas()) {
                List<ValueFormula> replacements = substituteKnownCallResults(
                        value, calls, transfers, requirement.span(), limits,
                        new LinkedHashSet<>());
                values.addAll(replacements);
                overrides.addAll(requirement.value()
                        .rebaseOverrides(value, replacements));
            }
            if (!values.isEmpty()) {
                rawOwnershipRequirements.add(requirement.withValue(
                        new FormulaAlternatives(
                                requirement.value().rootType(), values, overrides)));
            }
        }
        List<OwnershipRequirement> ownershipRequirements =
                rebaseOwnershipInCallerOrder(
                        raw, transfers, rawOwnershipRequirements, recursiveCalls, limits);
        for (ValueFormula formula : formulas) {
            requireProjectionDepth(formula, limits);
        }
        for (CapturedCellWrite write : writes) {
            limits.requireProjectionDepth(write.route().depth());
            for (ValueFormula value : write.value().formulas()) {
                requireProjectionDepth(value, limits);
            }
        }
        limits.requireFormulaAlternatives(uniqueFormulaCount(formulas));
        limits.requireWrites(new LinkedHashSet<>(writes).size());
        limits.requireOwnershipRequirements(
                ownershipRequirements.size());
        limits.requireEffects(uniqueEffectCount(effects));
        return new CallableSummary(
                raw.lambdaId(), raw.moduleId(), raw.span(), raw.scopeId(), raw.signature(),
                raw.parameters(), raw.captures(),
                new CallableSummary.ReturnFormula(new FormulaAlternatives(
                        raw.signature().returnType(), formulas, formulaOverrides)),
                writes, materializedCalls, ownershipRequirements, effects,
                raw.normalizedBody(), false, 0, limits);
    }

    private static void addMaterializedCallWitnesses(
            List<EagerEffectWitness> effects,
            CallableSummary owner,
            CallableCallReference call,
            Map<LambdaId, CallableSummary> summaries,
            Map<DeclarationId, LambdaId> lambdaByDeclaration) {
        EagerEffectWitness.Kind kind = switch (call.kind()) {
            case DIRECT -> EagerEffectWitness.Kind.DIRECT_CALL;
            case NAMESPACE -> EagerEffectWitness.Kind.NAMESPACE_CALL;
            case CALLABLE -> EagerEffectWitness.Kind.CALLABLE_CALL;
            case PARAMETER -> EagerEffectWitness.Kind.PARAMETER_CALL;
            case CAPTURE -> EagerEffectWitness.Kind.CAPTURE_CALL;
            case CONSTRUCTION -> EagerEffectWitness.Kind.DIRECT_CALL;
        };
        for (LambdaId target : staticTargets(call, lambdaByDeclaration)) {
            CallableSummary callee = summaries.get(target);
            if (callee == null || owner.moduleId().equals(callee.moduleId())) {
                continue;
            }
            io.mindspice.lyra.compiler.identity.FlowSiteId site =
                    call.siteId().orElseThrow();
            effects.add(new EagerEffectWitness(
                    owner.moduleId(), callee.moduleId(), kind, call.span(),
                    call.targetDeclaration(), call.referenceId(), Optional.of(target),
                    List.of(call.span()), List.of(), false,
                    Optional.of(site), List.of(site)));
        }
    }

    private static FormulaAlternatives substituteKnownCallResults(
            FormulaAlternatives alternatives,
            Map<SummaryCallId, CallableCallReference> calls,
            Map<SummaryCallId, SummaryTransferResult.Success> transfers,
            SourceSpan ownerSpan,
            SummaryLimits limits) {
        ArrayList<ValueFormula> formulas = new ArrayList<>();
        ArrayList<FormulaAlternatives.ExactOverride> formulaOverrides = new ArrayList<>();
        for (ValueFormula formula : alternatives.formulas()) {
            List<ValueFormula> replacements = substituteKnownCallResults(
                    formula, calls, transfers, ownerSpan, limits,
                    new LinkedHashSet<>());
            formulas.addAll(replacements);
            formulaOverrides.addAll(alternatives.rebaseOverrides(formula, replacements));
        }
        limits.requireFormulaAlternatives(new LinkedHashSet<>(formulas).size());
        return new FormulaAlternatives(
                alternatives.rootType(), formulas, formulaOverrides);
    }

    private static List<CapturedCellWrite> rebaseWritesInCallerOrder(
            CallableSummary raw,
            Map<SummaryCallId, SummaryTransferResult.Success> transfers,
            List<CapturedCellWrite> rawWrites,
            Set<SummaryCallId> recursiveCalls,
            SummaryLimits limits) {
        TreeMap<Integer, List<CapturedCellWrite>> writesByEvent = new TreeMap<>();
        // A recursive edge is one abstract invocation, not an instruction to
        // append the callee trace for every unfolding. Retain the first
        // source-ordered occurrence of each recursive operation and join later
        // value snapshots into it. The operation identity includes the
        // cell/route/source site, so distinct writes to one cell are never
        // collapsed.
        TreeSet<String> recursiveWriteKeys = new TreeSet<>();
        for (CallableCallReference call : raw.callReferences()) {
            SummaryTransferResult.Success transfer = transfers.get(call.id());
            List<CapturedCellWrite> writes = transfer == null
                    ? List.of() : transfer.writes();
            if (recursiveCalls.contains(call.id())) {
                writes.forEach(write -> recursiveWriteKeys.add(write.operationKey()));
            }
            addWriteEvent(writesByEvent, call.id().sequence(), writes);
        }
        for (CapturedCellWrite write : rawWrites) {
            addWriteEvent(writesByEvent, write.sequence(), List.of(write));
        }

        ArrayList<OrderedWrite> ordered = new ArrayList<>();
        TreeMap<String, Integer> firstRecursiveWrite = new TreeMap<>();
        for (Map.Entry<Integer, List<CapturedCellWrite>> event
                : writesByEvent.entrySet()) {
            for (CapturedCellWrite write : event.getValue()) {
                String operationKey = write.operationKey();
                if (!recursiveWriteKeys.contains(operationKey)) {
                    ordered.add(new OrderedWrite(event.getKey(), write));
                    continue;
                }
                Integer first = firstRecursiveWrite.get(operationKey);
                if (first == null) {
                    firstRecursiveWrite.put(operationKey, ordered.size());
                    ordered.add(new OrderedWrite(event.getKey(), write));
                    continue;
                }
                OrderedWrite previous = ordered.get(first);
                FormulaAlternatives joined = previous.write().value().join(write.value());
                limits.requireFormulaAlternatives(joined.size());
                ordered.set(first, new OrderedWrite(
                        previous.eventSequence(), previous.write().withValue(joined)));
            }
        }
        return assignWriteSequences(ordered, limits);
    }

    private static List<OwnershipRequirement> rebaseOwnershipInCallerOrder(
            CallableSummary raw,
            Map<SummaryCallId, SummaryTransferResult.Success> transfers,
            List<OwnershipRequirement> rawRequirements,
            Set<SummaryCallId> recursiveCalls,
            SummaryLimits limits) {
        TreeMap<Integer, List<OwnershipRequirement>> requirementsByEvent =
                new TreeMap<>();
        // Apply the same finite recursive-edge widening to ownership evidence:
        // retain the first source-ordered occurrence and join later value
        // snapshots by source operation identity. Distinct source sites remain
        // separate obligations.
        TreeSet<String> recursiveRequirementKeys = new TreeSet<>();
        for (CallableCallReference call : raw.callReferences()) {
            SummaryTransferResult.Success transfer = transfers.get(call.id());
            List<OwnershipRequirement> requirements = transfer == null
                    ? List.of() : transfer.ownershipRequirements();
            if (recursiveCalls.contains(call.id())) {
                requirements.forEach(requirement ->
                        recursiveRequirementKeys.add(requirement.operationKey()));
            }
            addOwnershipEvent(
                    requirementsByEvent, call.id().sequence(), requirements);
        }
        for (OwnershipRequirement requirement : rawRequirements) {
            addOwnershipEvent(
                    requirementsByEvent, requirement.sequence(),
                    List.of(requirement));
        }

        ArrayList<OrderedOwnership> ordered = new ArrayList<>();
        TreeMap<String, Integer> firstRecursiveRequirement = new TreeMap<>();
        for (Map.Entry<Integer, List<OwnershipRequirement>> event
                : requirementsByEvent.entrySet()) {
            for (OwnershipRequirement requirement : event.getValue()) {
                String operationKey = requirement.operationKey();
                if (!recursiveRequirementKeys.contains(operationKey)) {
                    ordered.add(new OrderedOwnership(event.getKey(), requirement));
                    continue;
                }
                Integer first = firstRecursiveRequirement.get(operationKey);
                if (first == null) {
                    firstRecursiveRequirement.put(operationKey, ordered.size());
                    ordered.add(new OrderedOwnership(event.getKey(), requirement));
                    continue;
                }
                OrderedOwnership previous = ordered.get(first);
                FormulaAlternatives joined = previous.requirement().value().join(
                        requirement.value());
                limits.requireFormulaAlternatives(joined.size());
                ordered.set(first, new OrderedOwnership(
                        previous.eventSequence(), previous.requirement().withValue(joined)));
            }
        }
        ArrayList<OwnershipRequirement> rebased = new ArrayList<>();
        TreeMap<Integer, Integer> eventIndexes = new TreeMap<>();
        for (OrderedOwnership occurrence : ordered) {
            int index = eventIndexes.getOrDefault(occurrence.eventSequence(), 0);
            eventIndexes.put(occurrence.eventSequence(), index + 1);
            rebased.add(occurrence.requirement().withSequence(
                    CapturedCellWrite.sequenceAtEvent(
                            occurrence.eventSequence(), index, limits)));
        }
        TreeMap<String, OwnershipRequirement> unique = new TreeMap<>();
        for (OwnershipRequirement requirement : rebased) {
            unique.merge(requirement.semanticKey(), requirement,
                    (left, right) -> left.sequence() <= right.sequence()
                            ? left : right);
        }
        return unique.values().stream()
                .sorted(OwnershipRequirement::compareTo)
                .toList();
    }

    private static List<CapturedCellWrite> assignWriteSequences(
            List<OrderedWrite> occurrences,
            SummaryLimits limits) {
        ArrayList<CapturedCellWrite> result = new ArrayList<>();
        TreeMap<Integer, Integer> eventIndexes = new TreeMap<>();
        for (OrderedWrite occurrence : occurrences) {
            int index = eventIndexes.getOrDefault(occurrence.eventSequence(), 0);
            eventIndexes.put(occurrence.eventSequence(), index + 1);
            result.add(occurrence.write().withSequence(
                    CapturedCellWrite.sequenceAtEvent(
                            occurrence.eventSequence(), index, limits)));
        }
        return List.copyOf(result);
    }

    private record OrderedWrite(
            int eventSequence,
            CapturedCellWrite write) {
    }

    private record OrderedOwnership(
            int eventSequence,
            OwnershipRequirement requirement) {
    }

    private static void addOwnershipEvent(
            Map<Integer, List<OwnershipRequirement>> requirementsByEvent,
            int sequence,
            List<OwnershipRequirement> requirements) {
        if (requirementsByEvent.put(
                sequence, List.copyOf(requirements)) != null) {
            throw new IllegalArgumentException(
                    "callable ownership events share source-order sequence "
                            + sequence);
        }
    }

    private static void addWriteEvent(
            Map<Integer, List<CapturedCellWrite>> writesByEvent,
            int sequence,
            List<CapturedCellWrite> writes) {
        if (writesByEvent.put(sequence, List.copyOf(writes)) != null) {
            throw new IllegalArgumentException(
                    "callable summary events share source-order sequence " + sequence);
        }
    }

    private static List<ValueFormula> substituteKnownCallResults(
            ValueFormula formula,
            Map<SummaryCallId, CallableCallReference> calls,
            Map<SummaryCallId, SummaryTransferResult.Success> transfers,
            SourceSpan ownerSpan,
            SummaryLimits limits,
            Set<SummaryCallId> activeCalls) {
        if (formula instanceof ValueFormula.Lambda lambda) {
            TreeMap<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> captures =
                    new TreeMap<>();
            for (Map.Entry<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> entry
                    : lambda.captures().entrySet()) {
                ArrayList<ValueFormula> values = new ArrayList<>();
                ArrayList<FormulaAlternatives.ExactOverride> valueOverrides = new ArrayList<>();
                for (ValueFormula captured : entry.getValue().formulas()) {
                    List<ValueFormula> replacements = substituteKnownCallResults(
                            captured, calls, transfers, ownerSpan, limits, activeCalls);
                    values.addAll(replacements);
                    valueOverrides.addAll(entry.getValue()
                            .rebaseOverrides(captured, replacements));
                }
                if (values.isEmpty()) {
                    throw new MissingFactException(
                            "nested callable capture has no reachable value fact",
                            Optional.of(ownerSpan));
                }
                limits.requireFormulaAlternatives(new LinkedHashSet<>(values).size());
                captures.put(entry.getKey(), new FormulaAlternatives(
                        entry.getValue().rootType(), values, valueOverrides));
            }
            return List.of(lambda.withCaptures(captures));
        }
        if (!(formula instanceof ValueFormula.CallResult callResult)) {
            return List.of(formula);
        }
        CallableCallReference call = calls.get(callResult.callId());
        if (call == null) {
            return List.of(callResult);
        }
        SummaryTransferResult.Success transfer = transfers.get(call.id());
        if (transfer == null || !activeCalls.add(call.id())) {
            return List.of(callResult);
        }
        try {
            FormulaAlternatives selected;
            try {
                selected = transfer.returnValue().select(callResult.callRoute());
            } catch (IllegalArgumentException failure) {
                throw new MissingFactException(
                        "call result route is incompatible with the reachable callee return",
                        Optional.of(call.span()));
            }
            if (selected.isEmpty()) {
                throw new MissingFactException(
                        "call result route has no reachable callee return fact",
                        Optional.of(call.span()));
            }
            ArrayList<ValueFormula> result = new ArrayList<>();
            for (ValueFormula selectedFormula : selected.formulas()) {
                for (ValueFormula substituted : substituteKnownCallResults(
                        selectedFormula, calls, transfers, ownerSpan, limits, activeCalls)) {
                    Set<SummaryCallId> unresolved = callResultDependencies(substituted);
                    if (unresolved.stream().anyMatch(dependency -> !calls.containsKey(dependency))
                            || unresolved.contains(callResult.callId())) {
                        // A callee-local recursive terminal cannot be resolved
                        // from this owner's call table. Keep the producer term
                        // so caller-time substitution can retry the complete call.
                        return List.of(callResult);
                    }
                    ProjectionPath route = callResult.resultRoute()
                            .compose(substituted.resultRoute());
                    limits.requireProjectionDepth(route.depth());
                    result.add(throughCall(substituted, call.id()).withResultRoute(route));
                }
            }
            return List.copyOf(result);
        } finally {
            activeCalls.remove(call.id());
        }
    }

    private static ValueFormula throughCall(
            ValueFormula formula, SummaryCallId call) {
        if (formula instanceof ValueFormula.FreshAllocation fresh) {
            return fresh.throughCall(call);
        }
        if (formula instanceof ValueFormula.Lambda lambda) {
            TreeMap<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> captures =
                    new TreeMap<>();
            lambda.captures().forEach((capture, values) -> {
                List<ValueFormula> nested = values.formulas().stream()
                        .map(value -> throughCall(value, call)).toList();
                List<FormulaAlternatives.ExactOverride> overrides = values.exactOverrides().stream()
                        .map(override -> new FormulaAlternatives.ExactOverride(
                                throughCall(override.formula(), call), override.route()))
                        .toList();
                captures.put(capture, new FormulaAlternatives(
                        values.rootType(), nested, overrides));
            });
            return lambda.withCaptures(captures).throughCall(call);
        }
        return formula;
    }

    private static List<ValueFormula> expand(
            ValueFormula formula,
            ProjectionPath selectedCallRoute,
            ProjectionPath outerResultRoute,
            CallableCallReference call,
            CallableSummary callee,
            SummaryLimits limits) {
        ValueFormula projected = projectCalleeFormula(
                formula, selectedCallRoute, ValueAlternative.typeAt(
                        callee.signature().returnType(), selectedCallRoute));
        if (projected == null) {
            return List.of();
        }
        if (projected instanceof ValueFormula.CallResult) {
            // Keep recursive and unresolved nested call terms as finite terminals.
            return List.of(projected.withResultRoute(
                    outerResultRoute.compose(projected.resultRoute())));
        }
        if (projected instanceof ValueFormula.Parameter parameter) {
            if (parameter.parameterIndex() >= call.arguments().size()) {
                throw new MissingFactException(
                        "callee parameter formula has no corresponding call argument",
                        Optional.of(call.span()));
            }
            FormulaAlternatives argument;
            try {
                argument = call.arguments().get(parameter.parameterIndex())
                        .select(parameter.parameterRoute());
            } catch (IllegalArgumentException failure) {
                throw new MissingFactException(
                        "callee parameter route is incompatible with its call argument",
                        Optional.of(call.span()));
            }
            if (argument.isEmpty()) {
                throw new MissingFactException(
                        "callee parameter route has no caller-visible argument fact",
                        Optional.of(call.span()));
            }
            ArrayList<ValueFormula> result = new ArrayList<>();
            for (ValueFormula argumentFormula : argument.formulas()) {
                ProjectionPath route = outerResultRoute
                        .compose(parameter.resultRoute())
                        .compose(argumentFormula.resultRoute());
                limits.requireProjectionDepth(route.depth());
                result.add(argumentFormula.withResultRoute(route));
            }
            return result;
        }
        if (projected instanceof ValueFormula.Lambda lambda) {
            TreeMap<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> captures =
                    new TreeMap<>();
            for (Map.Entry<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> entry
                    : lambda.captures().entrySet()) {
                ArrayList<ValueFormula> values = new ArrayList<>();
                ArrayList<FormulaAlternatives.ExactOverride> valueOverrides = new ArrayList<>();
                for (ValueFormula captured : entry.getValue().formulas()) {
                    List<ValueFormula> replacements = substituteEnvironmentFormula(
                            captured, call, limits);
                    values.addAll(replacements);
                    valueOverrides.addAll(entry.getValue()
                            .rebaseOverrides(captured, replacements));
                }
                if (values.isEmpty()) {
                    throw new MissingFactException(
                            "nested callable capture has no caller-visible value",
                            Optional.of(call.span()));
                }
                captures.put(entry.getKey(), new FormulaAlternatives(
                        entry.getValue().rootType(), values, valueOverrides));
            }
            ValueFormula substituted = lambda.withCaptures(captures);
            return List.of(substituted.withResultRoute(
                    outerResultRoute.compose(substituted.resultRoute())));
        }
        if (projected instanceof ValueFormula.Capture capture) {
            for (ValueFormula target : call.target().formulas()) {
                if (!(target instanceof ValueFormula.Lambda lambda)) {
                    continue;
                }
                FormulaAlternatives captured = lambda.captures().get(capture.captureId());
                if (captured == null) {
                    continue;
                }
                FormulaAlternatives selected = captured.select(capture.captureRoute());
                if (selected.isEmpty()) {
                    throw new MissingFactException(
                            "callee capture route has no caller-visible value",
                            Optional.of(call.span()));
                }
                ArrayList<ValueFormula> result = new ArrayList<>();
                for (ValueFormula capturedFormula : selected.formulas()) {
                    result.add(capturedFormula.withResultRoute(
                            outerResultRoute.compose(projected.resultRoute())
                                    .compose(capturedFormula.resultRoute())));
                }
                return result;
            }
            throw new MissingFactException(
                    "callee capture formula has no captured caller value",
                    Optional.of(call.span()));
        }
        return List.of(projected.withResultRoute(
                outerResultRoute.compose(projected.resultRoute())));
    }

    private static List<ValueFormula> substituteEnvironmentFormula(
            ValueFormula formula,
            CallableCallReference call,
            SummaryLimits limits) {
        if (formula instanceof ValueFormula.Parameter parameter) {
            if (parameter.parameterIndex() >= call.arguments().size()) {
                throw new MissingFactException(
                        "nested callable capture parameter has no argument",
                        Optional.of(call.span()));
            }
            FormulaAlternatives selected = call.arguments().get(parameter.parameterIndex())
                    .select(parameter.parameterRoute());
            if (selected.isEmpty()) {
                throw new MissingFactException(
                        "nested callable capture parameter has no value",
                        Optional.of(call.span()));
            }
            return selected.formulas();
        }
        if (formula instanceof ValueFormula.Capture capture) {
            for (ValueFormula target : call.target().formulas()) {
                if (target instanceof ValueFormula.Lambda lambda) {
                    FormulaAlternatives selected = lambda.captures().get(capture.captureId());
                    if (selected != null) {
                        return selected.select(capture.captureRoute()).formulas();
                    }
                }
            }
            throw new MissingFactException(
                    "nested callable capture has no enclosing capture fact",
                    Optional.of(call.span()));
        }
        if (formula instanceof ValueFormula.Lambda lambda) {
            TreeMap<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> captures =
                    new TreeMap<>();
            for (Map.Entry<io.mindspice.lyra.compiler.identity.CaptureId, FormulaAlternatives> entry
                    : lambda.captures().entrySet()) {
                ArrayList<ValueFormula> values = new ArrayList<>();
                ArrayList<FormulaAlternatives.ExactOverride> valueOverrides = new ArrayList<>();
                for (ValueFormula captured : entry.getValue().formulas()) {
                    List<ValueFormula> replacements = substituteEnvironmentFormula(
                            captured, call, limits);
                    values.addAll(replacements);
                    valueOverrides.addAll(entry.getValue()
                            .rebaseOverrides(captured, replacements));
                }
                captures.put(entry.getKey(), new FormulaAlternatives(
                        entry.getValue().rootType(), values, valueOverrides));
            }
            return List.of(lambda.withCaptures(captures));
        }
        return List.of(formula);
    }

    private static ValueFormula projectCalleeFormula(
            ValueFormula formula,
            ProjectionPath selection,
            LyraType selectedType) {
        if (selection.isRoot()) {
            return formula;
        }
        ProjectionPath route = formula.resultRoute();
        if (!selection.overlaps(route)) {
            return null;
        }
        if (route.depth() >= selection.depth()) {
            return formula.withResultRoute(route.suffix(selection.depth()));
        }
        if (formula instanceof ValueFormula.Parameter parameter) {
            return new ValueFormula.Parameter(parameter.declarationId(),
                    parameter.parameterIndex(), parameter.parameterRoute().compose(selection),
                    ProjectionPath.root(), selectedType);
        }
        if (formula instanceof ValueFormula.Capture capture) {
            return new ValueFormula.Capture(capture.captureId(), capture.declarationId(),
                    capture.sharedCellId(), capture.captureRoute().compose(selection),
                    ProjectionPath.root(), selectedType);
        }
        if (formula instanceof ValueFormula.Declaration declaration) {
            return declaration.attachableBoundary()
                    ? new ValueFormula.Declaration(declaration.declarationId(), declaration.moduleId(),
                    declaration.declarationRoute().compose(selection), ProjectionPath.root(),
                    selectedType, true)
                    : new ValueFormula.Declaration(declaration.declarationId(), declaration.moduleId(),
                    declaration.declarationRoute().compose(selection), ProjectionPath.root(),
                    selectedType);
        }
        if (formula instanceof ValueFormula.CallResult call) {
            return new ValueFormula.CallResult(call.callId(), selectedType,
                    call.callRoute().compose(selection), ProjectionPath.root());
        }
        if (formula instanceof ValueFormula.Opaque) {
            return new ValueFormula.Opaque(selectedType, ProjectionPath.root(),
                    "projected from " + route);
        }
        return null;
    }

    private static void requireProjectionDepth(
            ValueFormula formula,
            SummaryLimits limits) {
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
                for (ValueFormula value : captured.formulas()) {
                    requireProjectionDepth(value, limits);
                }
            }
        }
    }

    private static int uniqueFormulaCount(List<ValueFormula> values) {
        return new LinkedHashSet<>(values).size();
    }

    private static int uniqueEffectCount(List<EagerEffectWitness> values) {
        return new TreeSet<>(values).size();
    }

    private static boolean sameSemanticState(
            CallableSummary left,
            CallableSummary right) {
        return left.returnFormula().equals(right.returnFormula())
                && left.writes().equals(right.writes())
                && left.ownershipRequirements().equals(
                        right.ownershipRequirements())
                && left.eagerEffects().equals(right.eagerEffects())
                && left.callReferences().equals(right.callReferences());
    }

    private static final class Tarjan {
        private final Map<LambdaId, List<LambdaId>> adjacency;
        private final Map<LambdaId, Integer> index = new HashMap<>();
        private final Map<LambdaId, Integer> lowLink = new HashMap<>();
        private final Deque<LambdaId> stack = new ArrayDeque<>();
        private final Set<LambdaId> onStack = new HashSet<>();
        private final List<List<LambdaId>> components = new ArrayList<>();
        private int nextIndex;

        private Tarjan(Map<LambdaId, List<LambdaId>> adjacency) {
            this.adjacency = adjacency;
        }

        private void visit(LambdaId node) {
            if (index.containsKey(node)) {
                return;
            }
            index.put(node, nextIndex);
            lowLink.put(node, nextIndex);
            nextIndex++;
            stack.push(node);
            onStack.add(node);
            for (LambdaId target : adjacency.getOrDefault(node, List.of())) {
                if (!index.containsKey(target)) {
                    visit(target);
                    lowLink.put(node, Math.min(lowLink.get(node), lowLink.get(target)));
                } else if (onStack.contains(target)) {
                    lowLink.put(node, Math.min(lowLink.get(node), index.get(target)));
                }
            }
            if (lowLink.get(node).equals(index.get(node))) {
                ArrayList<LambdaId> component = new ArrayList<>();
                LambdaId target;
                do {
                    target = stack.pop();
                    onStack.remove(target);
                    component.add(target);
                } while (!target.equals(node));
                component.sort(Comparator.naturalOrder());
                components.add(List.copyOf(component));
            }
        }

        private List<List<LambdaId>> components() {
            return List.copyOf(components);
        }
    }

    private static final class TransferFailureException extends RuntimeException {
        private final CallableSummaryResult.InternalFailure failure;

        private TransferFailureException(
                CallableSummaryResult.InternalFailure failure) {
            super(Objects.requireNonNull(failure, "failure").message());
            this.failure = failure;
        }

        private CallableSummaryResult.InternalFailure failure() {
            return failure;
        }
    }

    private static final class MissingFactException extends RuntimeException {
        private final Optional<SourceSpan> span;

        private MissingFactException(String message, Optional<SourceSpan> span) {
            super(Objects.requireNonNull(message, "message"));
            this.span = Objects.requireNonNull(span, "span");
        }

        private Optional<SourceSpan> span() {
            return span;
        }
    }

    private static final class NonConvergentException extends RuntimeException {
        private final Optional<SourceSpan> span;

        private NonConvergentException(String message, Optional<SourceSpan> span) {
            super(Objects.requireNonNull(message, "message"));
            this.span = Objects.requireNonNull(span, "span");
        }

        private Optional<SourceSpan> span() {
            return span;
        }
    }
}
