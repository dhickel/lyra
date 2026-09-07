package io.mindspice.lyra.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Immutable owner-thread snapshot of generated session initializer progress.
 * Declaration ordinals are compiler-issued identities, not names or storage
 * addresses.
 */
public record SessionInitializationProgress(Map<ModuleId, ModuleProgress> modules) {
    public SessionInitializationProgress {
        Objects.requireNonNull(modules, "modules");
        TreeMap<ModuleId, ModuleProgress> copy = new TreeMap<>();
        modules.forEach((module, progress) -> copy.put(
                Objects.requireNonNull(module, "modules must not contain null keys"),
                Objects.requireNonNull(progress, "modules must not contain null values")));
        modules = Map.copyOf(copy);
    }

    public static SessionInitializationProgress empty() {
        return new SessionInitializationProgress(Map.of());
    }

    public record ModuleProgress(Set<Long> attempted, Set<Long> completed) {
        public ModuleProgress {
            attempted = Set.copyOf(Objects.requireNonNull(attempted, "attempted"));
            completed = Set.copyOf(Objects.requireNonNull(completed, "completed"));
            if (attempted.stream().anyMatch(id -> id == null || id < 0)
                    || completed.stream().anyMatch(id -> id == null || id < 0)) {
                throw new IllegalArgumentException("initializer identities must be non-negative");
            }
            if (!attempted.containsAll(completed)) {
                throw new IllegalArgumentException("completed initializers must have been attempted");
            }
        }
    }
}
