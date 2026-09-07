package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.session.SessionExecutionPlan;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.ModuleHandle;
import io.mindspice.lyra.runtime.SessionInitializationProgress.ModuleProgress;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Exact progress for the initializer work of one session submission.
 * Scheduled entries come from the sealed execution plan; attempted and
 * completed entries come from generated lifecycle callbacks.
 */
public record InitializerProgress(
        List<Initializer> scheduled,
        List<Initializer> attempted,
        List<Initializer> completed) {
    public InitializerProgress {
        scheduled = copy(scheduled, "scheduled");
        attempted = copy(attempted, "attempted");
        completed = copy(completed, "completed");
        Set<Initializer> scheduledSet = Set.copyOf(scheduled);
        if (!scheduledSet.containsAll(attempted) || !scheduledSet.containsAll(completed)) {
            throw new IllegalArgumentException("initializer progress exceeds the execution plan");
        }
        if (!Set.copyOf(attempted).containsAll(completed)) {
            throw new IllegalArgumentException("completed initializers must have been attempted");
        }
    }

    public static InitializerProgress empty() {
        return new InitializerProgress(List.of(), List.of(), List.of());
    }

    static InitializerProgress scheduled(SessionExecutionPlan plan) {
        return new InitializerProgress(entries(plan), List.of(), List.of());
    }

    static InitializerProgress actual(SessionExecutionPlan plan, ModuleHandle module) {
        Objects.requireNonNull(module, "module");
        var runtime = LyraRuntime.sessionInitializationProgress(module).modules();
        List<Initializer> entries = entries(plan);
        List<Initializer> attempted = entries.stream()
                .filter(entry -> contains(runtime, entry, true)).toList();
        List<Initializer> completed = entries.stream()
                .filter(entry -> contains(runtime, entry, false)).toList();
        return new InitializerProgress(entries, attempted, completed);
    }

    private static boolean contains(
            java.util.Map<io.mindspice.lyra.runtime.ModuleId, ModuleProgress> progress,
            Initializer entry, boolean attempted) {
        ModuleProgress module = progress.get(toRuntimeModuleId(entry.moduleId()));
        if (module == null) return false;
        return (attempted ? module.attempted() : module.completed())
                .contains(entry.declarationId().ordinal());
    }

    private static List<Initializer> entries(SessionExecutionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        List<Initializer> entries = new ArrayList<>();
        for (ModuleId moduleId : plan.initializationOrder()) {
            plan.module(moduleId).ifPresent(work -> add(entries, work));
        }
        // The synthetic submission root is not part of initializationOrder,
        // but its declarations still execute after dependency initializers.
        plan.modules().stream().filter(SessionExecutionPlan.ModuleWork::scratch)
                .findFirst().ifPresent(work -> add(entries, work));
        return List.copyOf(entries);
    }

    private static void add(List<Initializer> entries, SessionExecutionPlan.ModuleWork work) {
        work.initializerDeclarations().stream()
                .map(id -> new Initializer(work.moduleId(), id)).forEach(entries::add);
    }

    private static io.mindspice.lyra.runtime.ModuleId toRuntimeModuleId(ModuleId moduleId) {
        return moduleId.isUri()
                ? io.mindspice.lyra.runtime.ModuleId.uri(moduleId.asUri())
                : io.mindspice.lyra.runtime.ModuleId.path(moduleId.value());
    }

    private static <T> List<T> copy(List<T> values, String label) {
        Objects.requireNonNull(values, label);
        ArrayList<T> copy = new ArrayList<>(values.size());
        for (T value : values) copy.add(Objects.requireNonNull(value, label + " must not contain null"));
        return List.copyOf(copy);
    }

    /** One exact compiler declaration in one module's initializer schedule. */
    public record Initializer(ModuleId moduleId, DeclarationId declarationId) {
        public Initializer {
            Objects.requireNonNull(moduleId, "moduleId");
            Objects.requireNonNull(declarationId, "declarationId");
        }
    }
}
