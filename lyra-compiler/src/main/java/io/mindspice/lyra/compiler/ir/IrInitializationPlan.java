package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.semantic.InitializationDependency;
import io.mindspice.lyra.compiler.semantic.InitializationPlan;
import io.mindspice.lyra.compiler.semantic.InitializationCycle;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.flow.EagerEffectFact;
import io.mindspice.lyra.compiler.source.ModuleId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * IR-owned immutable copy of the canonical eager initialization schedule.
 * This is a data projection, not a second dependency analyzer.
 */
public final class IrInitializationPlan implements ImmutablePhaseArtifact {
    private final List<ModuleId> modules;
    private final List<IrInitializationDependency> dependencies;
    private final List<ModuleId> initializationOrder;
    private final List<IrInitializationCycle> cycles;

    public IrInitializationPlan(
            List<ModuleId> modules,
            List<IrInitializationDependency> dependencies,
            List<ModuleId> initializationOrder,
            List<IrInitializationCycle> cycles) {
        this.modules = orderedModules(modules);
        this.dependencies = orderedDependencies(dependencies);
        this.initializationOrder = orderedOrder(this.modules, initializationOrder);
        this.cycles = orderedCycles(cycles);
        validateCyclesAndOrder();
    }

    /** Copies the plan and retains the producer-issued source-site paths from flow facts. */
    static IrInitializationPlan from(InitializationPlan source, TypedSemanticGraph graph) {
        Objects.requireNonNull(graph, "graph");
        return copy(source, dependency -> copyDependency(dependency, graph));
    }

    private static IrInitializationPlan copy(
            InitializationPlan source,
            java.util.function.Function<InitializationDependency, IrInitializationDependency> copier) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(copier, "copier");
        List<IrInitializationDependency> dependencies = source.dependencies().stream()
                .map(copier).toList();
        List<IrInitializationCycle> cycles = source.cycles().stream()
                .map(cycle -> new IrInitializationCycle(
                        cycle.modules(), cycle.dependencies().stream().map(copier).toList(),
                        cycle.primarySpan()))
                .toList();
        return new IrInitializationPlan(source.modules(), dependencies,
                source.initializationOrder(), cycles);
    }

    IrInitializationPlan project(java.util.function.Predicate<ModuleId> emits) {
        return new IrInitializationPlan(modules.stream().filter(emits).toList(),
                dependencies.stream().filter(value -> emits.test(value.fromModule())
                        && emits.test(value.toModule())).toList(),
                initializationOrder.stream().filter(emits).toList(),
                cycles.stream().filter(value -> value.modules().stream().allMatch(emits)).toList());
    }

    public List<ModuleId> modules() {
        return modules;
    }

    public List<IrInitializationDependency> dependencies() {
        return dependencies;
    }

    public List<IrInitializationDependency> dependencyEdges() {
        return dependencies;
    }

    public List<ModuleId> initializationOrder() {
        return initializationOrder;
    }

    public List<ModuleId> order() {
        return initializationOrder;
    }

    public List<IrInitializationCycle> cycles() {
        return cycles;
    }

    public boolean hasCycles() {
        return !cycles.isEmpty();
    }

    public boolean isAcyclic() {
        return cycles.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof IrInitializationPlan plan
                && modules.equals(plan.modules)
                && dependencies.equals(plan.dependencies)
                && initializationOrder.equals(plan.initializationOrder)
                && cycles.equals(plan.cycles);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modules, dependencies, initializationOrder, cycles);
    }

    @Override
    public String toString() {
        return "IrInitializationPlan[modules=" + modules.size()
                + ", dependencies=" + dependencies.size()
                + ", cycles=" + cycles.size() + "]";
    }

    private void validateCyclesAndOrder() {
        Set<ModuleId> moduleSet = Set.copyOf(modules);
        for (IrInitializationDependency dependency : dependencies) {
            if (!moduleSet.contains(dependency.fromModule())
                    || !moduleSet.contains(dependency.toModule())) {
                throw new IllegalArgumentException("initialization dependency names an absent module");
            }
        }
        if (!cycles.isEmpty()) {
            return;
        }
        Map<ModuleId, Integer> positions = new LinkedHashMap<>();
        for (int index = 0; index < initializationOrder.size(); index++) {
            positions.put(initializationOrder.get(index), index);
        }
        for (IrInitializationDependency dependency : dependencies) {
            if (positions.get(dependency.toModule()) >= positions.get(dependency.fromModule())) {
                throw new IllegalArgumentException("initialization order violates dependency edge");
            }
        }
    }

    private static IrInitializationDependency copyDependency(
            InitializationDependency dependency, TypedSemanticGraph graph) {
        List<List<io.mindspice.lyra.compiler.identity.FlowSiteId>> matches = graph.semanticFlowFacts()
                .eagerEffectFacts().stream()
                .filter(fact -> fact.initializerModule().equals(dependency.fromModule()))
                .filter(fact -> fact.initializerDeclaration().equals(dependency.initializerDeclaration()))
                .filter(fact -> fact.witness().targetModule().equals(dependency.toModule()))
                .filter(fact -> fact.witness().effectSpan().equals(dependency.effectSpan()))
                .filter(fact -> fact.witness().referenceId().equals(dependency.referenceId()))
                .filter(fact -> fact.witness().sourcePath().equals(dependency.sourcePath()))
                .map(EagerEffectFact::witness)
                .map(value -> value.sourceSitePath())
                .distinct()
                .toList();
        if (matches.size() != 1 || matches.getFirst().isEmpty()
                || matches.getFirst().size() != dependency.sourcePath().size()) {
            throw new IllegalArgumentException(
                    "initialization dependency has no unique complete producer source-site path");
        }
        return new IrInitializationDependency(dependency.fromModule(), dependency.toModule(),
                dependency.initializerDeclaration(), dependency.referenceId(),
                dependency.effectSpan(), dependency.sourcePath(), matches.getFirst());
    }

    private static List<ModuleId> orderedModules(List<ModuleId> values) {
        Objects.requireNonNull(values, "modules");
        ArrayList<ModuleId> result = copy(values, "modules");
        result.sort(Comparator.naturalOrder());
        if (new LinkedHashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException("initialization modules must be unique");
        }
        return List.copyOf(result);
    }

    private static List<IrInitializationDependency> orderedDependencies(
            List<IrInitializationDependency> values) {
        Objects.requireNonNull(values, "dependencies");
        ArrayList<IrInitializationDependency> result = copy(values, "dependencies");
        result.sort(IrInitializationDependency.comparator());
        if (new LinkedHashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException("initialization dependencies must be unique");
        }
        return List.copyOf(result);
    }

    private static List<ModuleId> orderedOrder(List<ModuleId> modules, List<ModuleId> values) {
        Objects.requireNonNull(values, "initializationOrder");
        ArrayList<ModuleId> result = copy(values, "initializationOrder");
        if (result.size() != modules.size()
                || new LinkedHashSet<>(result).size() != result.size()
                || !new LinkedHashSet<>(result).equals(new LinkedHashSet<>(modules))) {
            throw new IllegalArgumentException("initialization order must cover every module exactly once");
        }
        return List.copyOf(result);
    }

    private static List<IrInitializationCycle> orderedCycles(List<IrInitializationCycle> values) {
        Objects.requireNonNull(values, "cycles");
        ArrayList<IrInitializationCycle> result = copy(values, "cycles");
        result.sort(Comparator.naturalOrder());
        if (new LinkedHashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException("initialization cycles must be unique");
        }
        return List.copyOf(result);
    }

    private static <T> ArrayList<T> copy(List<T> values, String name) {
        ArrayList<T> result = new ArrayList<>();
        for (T value : values) {
            result.add(Objects.requireNonNull(value, name + " must not contain null"));
        }
        return result;
    }
}
