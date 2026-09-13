package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.semantic.flow.EagerCycleWitness;
import io.mindspice.lyra.compiler.semantic.flow.EagerEffectFact;
import io.mindspice.lyra.compiler.semantic.flow.EagerEffectWitness;
import io.mindspice.lyra.compiler.semantic.flow.SemanticFlowFacts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Narrow planner over canonical eager-effect facts.
 *
 * <p>All typed expression, callable, aggregate, capture, and source-order
 * reconstruction belongs to {@link SemanticFlowAnalyzer}.  This class keeps
 * only the independent initialization dependency, SCC, cycle-witness, and
 * deterministic topological-order algorithms required by the existing
 * {@link InitializationPlan} API.</p>
 */
final class InitializationAnalyzer {
    private InitializationAnalyzer() {
    }

    /** Plans only from already canonical facts; no expression walk occurs here. */
    static Analysis plan(TypedSemanticGraph graph, SemanticFlowFacts facts) {
        return plan((TypedSemanticInput) Objects.requireNonNull(graph, "graph"), facts);
    }

    /** Package-owned planner entry point for the pre-seal typed core. */
    static Analysis plan(TypedSemanticCore core, SemanticFlowFacts facts) {
        return plan((TypedSemanticInput) Objects.requireNonNull(core, "core"), facts);
    }

    private static Analysis plan(TypedSemanticInput graph, SemanticFlowFacts facts) {
        return new Planner(
                Objects.requireNonNull(graph, "graph"),
                Objects.requireNonNull(facts, "facts")).run();
    }

    record Analysis(InitializationPlan plan, Optional<InitializationCycle> firstCycle) {
        Analysis {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(firstCycle, "firstCycle");
        }
    }

    private static final class Planner {
        private final TypedSemanticInput graph;
        private final SemanticFlowFacts facts;
        private final List<ModuleId> modules;
        private final LinkedHashSet<InitializationDependency> dependencies = new LinkedHashSet<>();
        private final Map<InitializationDependency, EagerEffectWitness.Kind> dependencyKinds =
                new LinkedHashMap<>();
        private final Map<InitializationDependency, Optional<DeclarationId>> dependencyTargets =
                new LinkedHashMap<>();

        private Planner(TypedSemanticInput graph, SemanticFlowFacts facts) {
            this.graph = Objects.requireNonNull(graph, "graph");
            this.facts = Objects.requireNonNull(facts, "facts");
            this.modules = graph.resolvedGraph().moduleGraph().modules().stream()
                    .map(value -> value.moduleId()).sorted().toList();
        }

        private Analysis run() {
            collectDependencies();
            Map<ModuleId, Set<ModuleId>> adjacency = adjacency();
            List<InitializationCycle> rawCycles = cycles(adjacency);
            removeFunctionOnlyCycleEdges(rawCycles);
            adjacency = adjacency();
            List<InitializationCycle> sccCycles = cycles(adjacency);
            List<InitializationCycle> cycles = new ArrayList<>(sccCycles);
            Set<List<ModuleId>> knownCycles = new HashSet<>(cycles.stream()
                    .map(InitializationCycle::modules).toList());
            for (EagerCycleWitness witness : facts.eagerCycles()) {
                List<ModuleId> members = matchingScc(witness, sccCycles)
                        .map(InitializationCycle::modules)
                        .orElseGet(() -> witness.modules().stream().sorted().toList());
                if (knownCycles.add(members)) {
                    List<InitializationDependency> edges = dependencies.stream()
                            .filter(edge -> members.contains(edge.fromModule())
                                    && members.contains(edge.toModule()))
                            .sorted(InitializationDependency.comparator())
                            .toList();
                    cycles.add(new InitializationCycle(members, edges, witness.primarySpan()));
                }
            }
            cycles.sort(Comparator.comparing(cycle -> cycle.modules().getFirst()));
            List<ModuleId> order = cycles.isEmpty() ? topologicalOrder(adjacency) : modules;
            InitializationPlan plan = new InitializationPlan(
                    modules, new ArrayList<>(dependencies), order, cycles);
            return new Analysis(plan, cycles.stream().findFirst());
        }

        private Optional<InitializationCycle> matchingScc(
                EagerCycleWitness witness,
                List<InitializationCycle> sccCycles) {
            Optional<ModuleId> repeatedModule = witness.activeDeclaration()
                    .flatMap(graph.resolvedGraph()::declaration)
                    .map(ResolvedDeclaration::moduleId);
            if (repeatedModule.isEmpty()) {
                return Optional.empty();
            }
            return sccCycles.stream()
                    .filter(cycle -> cycle.modules().contains(repeatedModule.orElseThrow())
                            && witness.modules().containsAll(cycle.modules()))
                    .findFirst();
        }

        private void collectDependencies() {
            for (EagerEffectFact fact : facts.eagerEffectFacts()) {
                EagerEffectWitness witness = fact.witness();
                ModuleId from = fact.initializerModule();
                ModuleId target = witness.targetModule();
                boolean certifiedPredecessor = !modules.contains(target)
                        && graph.resolvedGraph().sessionFlowCertificate()
                        .map(certificate -> certificate.containsSourceId(target.sourceId()))
                        .orElse(false);
                if (from.equals(target) || graph.resolvedGraph().isRetained(from)
                        || !modules.contains(target)
                        && (graph.resolvedGraph().retainedModules().module(target).isPresent()
                        || certifiedPredecessor)) {
                    continue;
                }
                InitializationDependency dependency = new InitializationDependency(
                        from,
                        target,
                        fact.initializerDeclaration(),
                        witness.referenceId(),
                        witness.effectSpan(),
                        witness.sourcePath());
                dependencies.add(dependency);
                dependencyKinds.merge(dependency, witness.kind(),
                        (left, right) -> left == EagerEffectWitness.Kind.VALUE_READ
                                ? left : right);
                dependencyTargets.merge(dependency, witness.targetDeclaration(),
                        (left, right) -> left.isPresent() ? left : right);
            }
        }

        private void removeFunctionOnlyCycleEdges(
                List<InitializationCycle> rawCycles) {
            for (InitializationCycle cycle : rawCycles) {
                if (!functionOnly(cycle)) {
                    continue;
                }
                dependencies.removeIf(dependency ->
                        cycle.modules().contains(dependency.fromModule())
                                && cycle.modules().contains(dependency.toModule()));
                dependencyKinds.keySet().removeIf(dependency ->
                        cycle.modules().contains(dependency.fromModule())
                                && cycle.modules().contains(dependency.toModule()));
                dependencyTargets.keySet().removeIf(dependency ->
                        cycle.modules().contains(dependency.fromModule())
                                && cycle.modules().contains(dependency.toModule()));
            }
        }

        private boolean functionOnly(InitializationCycle cycle) {
            if (cycle.modules().size() < 2 || cycle.dependencies().isEmpty()) {
                return false;
            }
            for (InitializationDependency dependency : cycle.dependencies()) {
                EagerEffectWitness.Kind kind = dependencyKinds.get(dependency);
                if (kind == null) {
                    return false;
                }
                if (kind == EagerEffectWitness.Kind.VALUE_READ
                        && dependencyTargets.getOrDefault(dependency, Optional.empty())
                        .map(this::isLazyFunctionSlot)
                        .orElse(false)) {
                    continue;
                }
                if (kind == EagerEffectWitness.Kind.VALUE_READ) {
                    return false;
                }
            }
            return true;
        }

        private boolean isLazyFunctionSlot(DeclarationId declaration) {
            ResolvedDeclaration resolved = graph.resolvedGraph().declaration(declaration).orElse(null);
            return resolved != null
                    && resolved.signaturePredeclared()
                    && graph.declaration(declaration)
                    .map(value -> value.initializerLambda().isPresent())
                    .orElse(false);
        }

        private Map<ModuleId, Set<ModuleId>> adjacency() {
            Map<ModuleId, Set<ModuleId>> result = new LinkedHashMap<>();
            for (ModuleId module : modules) {
                result.put(module, new LinkedHashSet<>());
            }
            for (InitializationDependency dependency : dependencies) {
                Set<ModuleId> targets = result.get(dependency.fromModule());
                if (targets == null || !result.containsKey(dependency.toModule())) {
                    throw new IllegalStateException("canonical effect names an absent module");
                }
                targets.add(dependency.toModule());
            }
            return result;
        }

        private List<InitializationCycle> cycles(Map<ModuleId, Set<ModuleId>> adjacency) {
            Map<ModuleId, Integer> index = new HashMap<>();
            Map<ModuleId, Integer> low = new HashMap<>();
            Set<ModuleId> onStack = new HashSet<>();
            Deque<ModuleId> stack = new ArrayDeque<>();
            List<List<ModuleId>> components = new ArrayList<>();
            int[] next = {0};
            for (ModuleId module : modules) {
                if (!index.containsKey(module)) {
                    connect(module, adjacency, index, low, onStack, stack, next, components);
                }
            }
            ArrayList<InitializationCycle> result = new ArrayList<>();
            for (List<ModuleId> component : components) {
                component.sort(Comparator.naturalOrder());
                Set<ModuleId> members = Set.copyOf(component);
                List<InitializationDependency> edges = dependencies.stream()
                        .filter(edge -> members.contains(edge.fromModule())
                                && members.contains(edge.toModule()))
                        .sorted(InitializationDependency.comparator())
                        .toList();
                boolean selfCycle = component.size() == 1 && edges.stream()
                        .anyMatch(edge -> edge.fromModule().equals(edge.toModule()));
                if (component.size() > 1 || selfCycle) {
                    var module = graph.resolvedGraph().moduleGraph()
                            .module(component.getFirst()).orElseThrow();
                    var primary = edges.isEmpty()
                            ? module.program().span() : edges.getFirst().effectSpan();
                    result.add(new InitializationCycle(component, edges, primary));
                }
            }
            result.sort(Comparator.comparing(cycle -> cycle.modules().getFirst()));
            return List.copyOf(result);
        }

        private void connect(
                ModuleId current,
                Map<ModuleId, Set<ModuleId>> adjacency,
                Map<ModuleId, Integer> index,
                Map<ModuleId, Integer> low,
                Set<ModuleId> onStack,
                Deque<ModuleId> stack,
                int[] next,
                List<List<ModuleId>> components) {
            index.put(current, next[0]);
            low.put(current, next[0]);
            next[0]++;
            stack.addLast(current);
            onStack.add(current);
            List<ModuleId> targets = new ArrayList<>(adjacency.getOrDefault(current, Set.of()));
            targets.sort(Comparator.naturalOrder());
            for (ModuleId target : targets) {
                if (!index.containsKey(target)) {
                    connect(target, adjacency, index, low, onStack, stack, next, components);
                    low.put(current, Math.min(low.get(current), low.get(target)));
                } else if (onStack.contains(target)) {
                    low.put(current, Math.min(low.get(current), index.get(target)));
                }
            }
            if (low.get(current).equals(index.get(current))) {
                ArrayList<ModuleId> component = new ArrayList<>();
                ModuleId value;
                do {
                    value = stack.removeLast();
                    onStack.remove(value);
                    component.add(value);
                } while (!value.equals(current));
                components.add(component);
            }
        }

        private List<ModuleId> topologicalOrder(Map<ModuleId, Set<ModuleId>> adjacency) {
            Map<ModuleId, Integer> remaining = new LinkedHashMap<>();
            for (ModuleId module : modules) {
                remaining.put(module, adjacency.getOrDefault(module, Set.of()).size());
            }
            ArrayList<ModuleId> result = new ArrayList<>();
            Set<ModuleId> emitted = new HashSet<>();
            while (result.size() < modules.size()) {
                ModuleId next = modules.stream()
                        .filter(module -> !emitted.contains(module) && remaining.get(module) == 0)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "acyclic initialization graph has no ready module"));
                emitted.add(next);
                result.add(next);
                for (ModuleId module : modules) {
                    if (adjacency.getOrDefault(module, Set.of()).contains(next)) {
                        remaining.computeIfPresent(module, (ignored, count) -> count - 1);
                    }
                }
            }
            return List.copyOf(result);
        }
    }
}
