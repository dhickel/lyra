package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** A deterministic strongly connected component of eager module dependencies. */
public record InitializationCycle(
        List<ModuleId> modules,
        List<InitializationDependency> dependencies,
        SourceSpan primarySpan) {
    public InitializationCycle {
        Objects.requireNonNull(modules, "modules");
        Objects.requireNonNull(dependencies, "dependencies");
        Objects.requireNonNull(primarySpan, "primarySpan");
        ArrayList<ModuleId> moduleCopy = new ArrayList<>();
        for (ModuleId module : modules) {
            moduleCopy.add(Objects.requireNonNull(module, "modules must not contain null"));
        }
        moduleCopy.sort(Comparator.naturalOrder());
        if (moduleCopy.isEmpty()) {
            throw new IllegalArgumentException("initialization cycle must contain a module");
        }
        modules = List.copyOf(moduleCopy);
        ArrayList<InitializationDependency> dependencyCopy = new ArrayList<>();
        for (InitializationDependency dependency : dependencies) {
            dependencyCopy.add(Objects.requireNonNull(dependency, "dependencies must not contain null"));
        }
        dependencyCopy.sort(InitializationDependency.comparator());
        dependencies = List.copyOf(dependencyCopy);
    }

    public List<ModuleId> members() {
        return modules;
    }

    public SourceSpan span() {
        return primarySpan;
    }
}
