package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.ModuleId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable eager-initialization dependency analysis for one typed module
 * graph.  An edge {@code A -> B} means that A's eager initialization may read
 * or execute a value from B, so B must initialize before A.
 */
public final class InitializationPlan implements ImmutablePhaseArtifact {
    private final List<ModuleId> modules;
    private final List<InitializationDependency> dependencies;
    private final List<ModuleId> order;
    private final List<InitializationCycle> cycles;
    private final Map<ModuleId, List<InitializationDependency>> dependenciesByModule;

    public InitializationPlan(
            List<ModuleId> modules,
            List<InitializationDependency> dependencies,
            List<ModuleId> initializationOrder,
            List<InitializationCycle> cycles) {
        this.modules = orderedModules(modules);
        this.dependencies = orderedDependencies(dependencies);
        this.order = orderedInitialization(this.modules, initializationOrder);
        this.cycles = copyCycles(cycles);
        Set<ModuleId> moduleSet = Set.copyOf(this.modules);
        validateOrderAndCycles(moduleSet);
        LinkedHashMap<ModuleId, List<InitializationDependency>> byModule = new LinkedHashMap<>();
        for (ModuleId module : this.modules) {
            ArrayList<InitializationDependency> values = new ArrayList<>();
            for (InitializationDependency dependency : this.dependencies) {
                if (dependency.fromModule().equals(module)) {
                    if (!moduleSet.contains(dependency.toModule())) {
                        throw new IllegalArgumentException("dependency targets an absent module");
                    }
                    values.add(dependency);
                }
            }
            byModule.put(module, List.copyOf(values));
        }
        this.dependenciesByModule = Collections.unmodifiableMap(byModule);
    }

    public static InitializationPlan empty(ModuleGraph graph) {
        Objects.requireNonNull(graph, "graph");
        List<ModuleId> modules = graph.modules().stream().map(ModuleGraph.Node::moduleId)
                .sorted().toList();
        return new InitializationPlan(modules, List.of(), modules, List.of());
    }

    public List<ModuleId> modules() {
        return modules;
    }

    public List<InitializationDependency> dependencies() {
        return dependencies;
    }

    public List<InitializationDependency> dependencyEdges() {
        return dependencies;
    }

    public List<InitializationDependency> dependenciesFrom(ModuleId module) {
        return dependenciesByModule.getOrDefault(Objects.requireNonNull(module, "module"), List.of());
    }

    public List<ModuleId> initializationOrder() {
        return order;
    }

    public List<ModuleId> topologicalOrder() {
        return order;
    }

    public List<ModuleId> moduleOrder() {
        return order;
    }

    public List<ModuleId> order() {
        return order;
    }

    public List<InitializationCycle> cycles() {
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
        return this == other
                || other instanceof InitializationPlan plan
                && modules.equals(plan.modules)
                && dependencies.equals(plan.dependencies)
                && order.equals(plan.order)
                && cycles.equals(plan.cycles);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modules, dependencies, order, cycles);
    }

    @Override
    public String toString() {
        return "InitializationPlan[modules=" + modules.size()
                + ", dependencies=" + dependencies.size()
                + ", cycles=" + cycles.size() + "]";
    }

    private static List<ModuleId> orderedModules(List<ModuleId> values) {
        Objects.requireNonNull(values, "modules");
        ArrayList<ModuleId> copy = new ArrayList<>();
        for (ModuleId value : values) {
            copy.add(Objects.requireNonNull(value, "modules must not contain null"));
        }
        copy.sort(Comparator.naturalOrder());
        if (new LinkedHashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException("initialization modules must be unique");
        }
        return List.copyOf(copy);
    }

    private static List<InitializationDependency> orderedDependencies(
            List<InitializationDependency> values) {
        Objects.requireNonNull(values, "dependencies");
        ArrayList<InitializationDependency> copy = new ArrayList<>();
        for (InitializationDependency value : values) {
            copy.add(Objects.requireNonNull(value, "dependencies must not contain null"));
        }
        copy.sort(InitializationDependency.comparator());
        return List.copyOf(new LinkedHashSet<>(copy));
    }

    private static List<ModuleId> orderedInitialization(
            List<ModuleId> modules,
            List<ModuleId> values) {
        Objects.requireNonNull(values, "initializationOrder");
        ArrayList<ModuleId> copy = new ArrayList<>();
        for (ModuleId value : values) {
            copy.add(Objects.requireNonNull(value, "initializationOrder must not contain null"));
        }
        if (!new LinkedHashSet<>(copy).equals(new LinkedHashSet<>(modules))) {
            throw new IllegalArgumentException("initialization order must cover every module exactly once");
        }
        return List.copyOf(copy);
    }

    private void validateOrderAndCycles(Set<ModuleId> moduleSet) {
        Map<ModuleId, Integer> positions = new LinkedHashMap<>();
        for (int index = 0; index < order.size(); index++) {
            positions.put(order.get(index), index);
        }
        for (InitializationCycle cycle : cycles) {
            if (!moduleSet.containsAll(cycle.modules())) {
                throw new IllegalArgumentException("initialization cycle names an absent module");
            }
            for (InitializationDependency dependency : cycle.dependencies()) {
                if (!cycle.modules().contains(dependency.fromModule())
                        || !cycle.modules().contains(dependency.toModule())) {
                    throw new IllegalArgumentException("initialization cycle contains an unrelated dependency");
                }
            }
        }
        if (cycles.isEmpty()) {
            for (InitializationDependency dependency : dependencies) {
                Integer from = positions.get(dependency.fromModule());
                Integer to = positions.get(dependency.toModule());
                if (from == null || to == null || to >= from) {
                    throw new IllegalArgumentException(
                            "initialization order does not place dependencies before dependents");
                }
            }
        }
    }

    private static List<InitializationCycle> copyCycles(List<InitializationCycle> values) {
        Objects.requireNonNull(values, "cycles");
        ArrayList<InitializationCycle> copy = new ArrayList<>();
        for (InitializationCycle value : values) {
            copy.add(Objects.requireNonNull(value, "cycles must not contain null"));
        }
        copy.sort(Comparator.comparing(cycle -> cycle.modules().getFirst()));
        return List.copyOf(copy);
    }
}
