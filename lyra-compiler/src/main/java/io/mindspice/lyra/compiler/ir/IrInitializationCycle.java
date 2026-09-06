package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Structured cycle witness retained when a schedule cannot be published. */
public record IrInitializationCycle(
        List<ModuleId> modules,
        List<IrInitializationDependency> dependencies,
        SourceSpan primarySpan)
        implements ImmutablePhaseArtifact, Comparable<IrInitializationCycle> {
    public IrInitializationCycle {
        modules = copy(modules, "modules");
        dependencies = copy(dependencies, "dependencies");
        Objects.requireNonNull(primarySpan, "primarySpan");
        if (modules.isEmpty()) {
            throw new IllegalArgumentException("initialization cycle needs a module");
        }
        if (!modules.stream().anyMatch(module -> module.sourceId()
                .equals(primarySpan.sourceId()))) {
            throw new IllegalArgumentException("initialization cycle primary span is outside its modules");
        }
        ArrayList<ModuleId> orderedModules = new ArrayList<>(modules);
        orderedModules.sort(Comparator.naturalOrder());
        if (!modules.equals(orderedModules)
                || new LinkedHashSet<>(modules).size() != modules.size()) {
            throw new IllegalArgumentException("initialization cycle modules must be unique and canonical");
        }
        ArrayList<IrInitializationDependency> orderedDependencies = new ArrayList<>(dependencies);
        orderedDependencies.sort(IrInitializationDependency.comparator());
        if (!dependencies.equals(orderedDependencies)
                || new LinkedHashSet<>(dependencies).size() != dependencies.size()) {
            throw new IllegalArgumentException("initialization cycle dependencies must be unique and canonical");
        }
        if (!java.util.Set.copyOf(modules).containsAll(
                dependencies.stream().flatMap(value -> java.util.stream.Stream.of(
                        value.fromModule(), value.toModule())).toList())) {
            throw new IllegalArgumentException("cycle dependency is outside its module set");
        }
    }

    public IrInitializationCycle(
            List<ModuleId> modules,
            List<IrInitializationDependency> dependencies) {
        this(modules, dependencies, dependencies.isEmpty()
                ? throwMissingSpan() : dependencies.getFirst().effectSpan());
    }

    public ModuleId primaryModule() {
        return modules.getFirst();
    }

    public SourceSpan span() {
        return primarySpan;
    }

    @Override
    public int compareTo(IrInitializationCycle other) {
        IrInitializationCycle value = Objects.requireNonNull(other, "other");
        int result = primaryModule().compareTo(value.primaryModule());
        if (result != 0) {
            return result;
        }
        result = modules.toString().compareTo(value.modules.toString());
        if (result != 0) {
            return result;
        }
        result = dependencies.toString().compareTo(value.dependencies.toString());
        return result != 0 ? result : primarySpan.toString().compareTo(value.primarySpan.toString());
    }

    private static SourceSpan throwMissingSpan() {
        throw new IllegalArgumentException("an initialization cycle needs a primary span");
    }

    private static <T> List<T> copy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " must not contain null"));
        }
        return List.copyOf(copy);
    }
}
