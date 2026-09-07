package io.mindspice.lyra.compiler.session;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.GenerationId;
import io.mindspice.lyra.compiler.identity.ProducerId;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable partition of one session compilation into new, reused and
 * borrowed producer work.
 *
 * <p>The plan is descriptive compiler data.  It does not claim that a NEW
 * producer has initialized successfully; the runtime must prepare and mark
 * actual instances independently.</p>
 */
public final class SessionExecutionPlan implements ImmutablePhaseArtifact {
    public enum WorkKind {
        NEW,
        REUSED,
        BORROWED
    }

    public record ModuleWork(
            ModuleId moduleId,
            Optional<LogicalModuleId> logicalModule,
            GenerationId generationId,
            ProducerId producerId,
            WorkKind kind,
            boolean scratch,
            List<DeclarationId> initializerDeclarations) {
        public ModuleWork {
            moduleId = Objects.requireNonNull(moduleId, "moduleId");
            logicalModule = Objects.requireNonNull(logicalModule, "logicalModule");
            generationId = Objects.requireNonNull(generationId, "generationId");
            producerId = Objects.requireNonNull(producerId, "producerId");
            kind = Objects.requireNonNull(kind, "kind");
            initializerDeclarations = copy(initializerDeclarations, "initializerDeclarations");
            if (scratch && kind != WorkKind.NEW) {
                throw new IllegalArgumentException("scratch work must be new");
            }
            if (!scratch && logicalModule.isEmpty()) {
                throw new IllegalArgumentException("dependency work needs a logical identity");
            }
            if (kind != WorkKind.NEW && !initializerDeclarations.isEmpty()) {
                throw new IllegalArgumentException(
                        "reused or borrowed work cannot contain initializer execution");
            }
        }

        public boolean isNew() {
            return kind == WorkKind.NEW;
        }

        public boolean isReused() {
            return kind == WorkKind.REUSED;
        }

        public boolean isBorrowed() {
            return kind == WorkKind.BORROWED;
        }

        public boolean hasInitializerWork() {
            return !initializerDeclarations.isEmpty();
        }

        private static <T> List<T> copy(List<T> values, String name) {
            Objects.requireNonNull(values, name);
            ArrayList<T> result = new ArrayList<>(values.size());
            for (T value : values) {
                result.add(Objects.requireNonNull(value, name + " must not contain null"));
            }
            if (result.stream().distinct().count() != result.size()) {
                throw new IllegalArgumentException(name + " must not contain duplicates");
            }
            return List.copyOf(result);
        }
    }

    private final List<ModuleWork> modules;
    private final Map<ModuleId, ModuleWork> modulesById;
    private final List<ModuleId> initializationOrder;
    private final String topologyRevision;

    public static SessionExecutionPlan empty(String topologyRevision) {
        return new SessionExecutionPlan(List.of(), List.of(), topologyRevision);
    }

    public SessionExecutionPlan(
            List<ModuleWork> modules,
            List<ModuleId> initializationOrder,
            String topologyRevision) {
        this(modules, initializationOrder, topologyRevision, Optional.empty());
    }

    public SessionExecutionPlan(List<ModuleWork> modules, List<ModuleId> initializationOrder,
            io.mindspice.lyra.compiler.semantic.TypedSemanticGraph graph) {
        this(modules, initializationOrder, graph.resolvedGraph().moduleGraph().revision(), Optional.of(graph));
    }

    private SessionExecutionPlan(List<ModuleWork> modules, List<ModuleId> initializationOrder,
            String topologyRevision, Optional<io.mindspice.lyra.compiler.semantic.TypedSemanticGraph> graph) {
        Objects.requireNonNull(modules, "modules");
        if (!modules.isEmpty() && graph.isEmpty()) {
            throw new IllegalArgumentException("nonempty execution plan needs its canonical typed graph");
        }
        ArrayList<ModuleWork> ordered = new ArrayList<>();
        for (ModuleWork value : modules) {
            ordered.add(Objects.requireNonNull(value, "modules must not contain null"));
        }
        ordered.sort(Comparator.comparing(ModuleWork::moduleId)
                .thenComparing(ModuleWork::generationId));
        this.modules = List.copyOf(ordered);
        if (ordered.stream().map(ModuleWork::producerId).distinct().count() != ordered.size()
                || ordered.stream().filter(ModuleWork::isNew).map(ModuleWork::generationId).distinct().count() > 1) {
            throw new IllegalArgumentException("producer identities must be unique and new work must share one graph generation");
        }
        LinkedHashMap<ModuleId, ModuleWork> index = new LinkedHashMap<>();
        for (ModuleWork value : this.modules) {
            if (index.put(value.moduleId(), value) != null) {
                throw new IllegalArgumentException("duplicate module work in execution plan");
            }
        }
        this.modulesById = Map.copyOf(index);
        this.initializationOrder = List.copyOf(Objects.requireNonNull(
                initializationOrder, "initializationOrder"));
        this.topologyRevision = token(topologyRevision, "topologyRevision");
        validate();
        graph.ifPresent(this::validateAgainst);
    }

    public void validateAgainst(io.mindspice.lyra.compiler.semantic.TypedSemanticGraph graph) {
        var topology = graph.resolvedGraph().moduleGraph();
        if (!topologyRevision.equals(topology.revision())
                || !modulesById.keySet().equals(java.util.Set.copyOf(topology.moduleIds()))
                || modules.stream().filter(ModuleWork::scratch).count() != 1
                || !module(topology.rootModule()).orElseThrow().scratch()) {
            throw new IllegalArgumentException("execution plan does not cover its canonical graph");
        }
        for (var work : modules) {
            var resolved = graph.resolvedGraph().module(work.moduleId()).orElseThrow();
            var retained = graph.resolvedGraph().retainedModules().module(work.moduleId());
            WorkKind expected = work.logicalModule().filter(LogicalModuleId::isStdIo).isPresent()
                    || retained.filter(value -> value.isApplicationOwned()).isPresent() ? WorkKind.BORROWED
                    : graph.resolvedGraph().isRetained(work.moduleId()) ? WorkKind.REUSED : WorkKind.NEW;
            var declarations = resolved.declarations().stream()
                    .map(id -> graph.resolvedGraph().declaration(id).orElseThrow())
                    .filter(value -> value.kind() == io.mindspice.lyra.compiler.semantic.DeclarationKind.LET
                            && value.scopeId().equals(resolved.rootScope()))
                    .map(io.mindspice.lyra.compiler.semantic.ResolvedDeclaration::id).toList();
            if (work.kind() != expected || !work.logicalModule().equals(resolved.logicalModule())
                    || !work.initializerDeclarations().equals(expected == WorkKind.NEW
                            ? declarations : List.of())
                    || graph.resolvedGraph().isRetained(work.moduleId())
                    && retained.filter(value -> !value.generationId().equals(work.generationId())
                            || !value.producerId().equals(work.producerId())).isPresent()) {
                throw new IllegalArgumentException("execution work disagrees with its exact producer");
            }
        }
        var canonical = graph.initializationOrder().stream()
                .filter(id -> module(id).orElseThrow().isNew() && !module(id).orElseThrow().scratch()).toList();
        if (!initializationOrder.equals(canonical)) {
            throw new IllegalArgumentException("execution order is not the exact canonical new-work order");
        }
    }

    public List<ModuleWork> modules() {
        return modules;
    }

    public Optional<ModuleWork> module(ModuleId moduleId) {
        return Optional.ofNullable(modulesById.get(Objects.requireNonNull(moduleId, "moduleId")));
    }

    public List<ModuleWork> newModules() {
        return modules.stream().filter(ModuleWork::isNew).toList();
    }

    public List<ModuleWork> reusedModules() {
        return modules.stream().filter(ModuleWork::isReused).toList();
    }

    public List<ModuleWork> borrowedModules() {
        return modules.stream().filter(ModuleWork::isBorrowed).toList();
    }

    public List<ModuleId> initializationOrder() {
        return initializationOrder;
    }

    public String topologyRevision() {
        return topologyRevision;
    }

    public boolean hasNewInitializerWork() {
        return modules.stream().anyMatch(value -> value.isNew() && !value.scratch());
    }

    private void validate() {
        for (ModuleId module : initializationOrder) {
            ModuleWork work = modulesById.get(Objects.requireNonNull(module, "initialization module"));
            if (work == null || !work.isNew() || work.scratch) {
                throw new IllegalArgumentException(
                        "initialization order contains non-new or empty module work: " + module);
            }
        }
        List<ModuleId> expected = modules.stream()
                .filter(value -> value.isNew() && !value.scratch)
                .map(ModuleWork::moduleId)
                .sorted()
                .toList();
        if (!initializationOrder.stream().sorted().toList().equals(expected)) {
            throw new IllegalArgumentException(
                    "initialization order does not cover exactly new initializer work");
        }
    }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof SessionExecutionPlan plan
                && modules.equals(plan.modules) && initializationOrder.equals(plan.initializationOrder)
                && topologyRevision.equals(plan.topologyRevision);
    }

    @Override public int hashCode() {
        return Objects.hash(modules, initializationOrder, topologyRevision);
    }

    private static String token(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
