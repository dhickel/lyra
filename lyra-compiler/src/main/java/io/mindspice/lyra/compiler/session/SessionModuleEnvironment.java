package io.mindspice.lyra.compiler.session;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.GenerationId;
import io.mindspice.lyra.compiler.identity.ProducerId;
import io.mindspice.lyra.compiler.semantic.ResolvedExport;
import io.mindspice.lyra.compiler.semantic.ResolvedImportBinding;
import io.mindspice.lyra.compiler.semantic.ResolvedModule;
import io.mindspice.lyra.compiler.semantic.TypedModule;
import io.mindspice.lyra.compiler.semantic.flow.BindingFlowState;
import io.mindspice.lyra.compiler.semantic.flow.CallableSummarySet;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.ResolvedSource;
import io.mindspice.lyra.compiler.source.SourceConfiguration;
import io.mindspice.lyra.compiler.source.SourceRoot;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.semantic.flow.SemanticFlowFacts;
import io.mindspice.lyra.compiler.semantic.ResolvedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Immutable compiler-owned metadata for retained session modules.
 *
 * <p>A module environment is deliberately not a runtime registry.  It records
 * the source revision and the sealed semantic facts that describe a producer;
 * a runtime owner must still bind the producer to an actual initialized
 * instance before executing a submission.  In particular, a
 * {@link PinnedModule} is only source/revision metadata and is not sufficient
 * to manufacture one of these records.</p>
 */
public final class SessionModuleEnvironment implements ImmutablePhaseArtifact {
    /** Ownership of the producer described by a compiler environment entry. */
    public enum Ownership {
        SESSION,
        APPLICATION,
        INTRINSIC
    }

    /** One retained module's source, semantic facts, and qualified identities. */
    public record ModuleRecord(
            LogicalModuleId logicalModule,
            ModuleId moduleId,
            SourceSnapshot source,
            String revision,
            GenerationId generationId,
            ProducerId producerId,
            Ownership ownership,
            ResolvedModule resolvedModule,
            TypedModule typedModule,
            List<ResolvedExport> exports,
            List<ResolvedImportBinding> imports,
            List<LogicalModuleId> dependencies,
            List<DeclarationId> initializerDeclarations,
            Optional<BindingFlowState> finalState,
            Optional<BindingFlowState> attemptedState,
            CallableSummarySet callableSummaries,
            TypedSemanticGraph producerGraph,
            List<SourceRoot> sourceRoots,
            Map<String, String> revisionOptions) {
        public ModuleRecord {
            logicalModule = Objects.requireNonNull(logicalModule, "logicalModule");
            moduleId = Objects.requireNonNull(moduleId, "moduleId");
            source = Objects.requireNonNull(source, "source");
            if (!moduleId.sourceId().equals(source.sourceId())) {
                throw new IllegalArgumentException(
                        "module record identity does not match its source snapshot");
            }
            if (!io.mindspice.lyra.compiler.source.ModuleRevision.isRevision(revision)) {
                throw new IllegalArgumentException(
                        "module record revision must be a SHA-256 hexadecimal value");
            }
            generationId = Objects.requireNonNull(generationId, "generationId");
            producerId = Objects.requireNonNull(producerId, "producerId");
            ownership = Objects.requireNonNull(ownership, "ownership");
            resolvedModule = Objects.requireNonNull(resolvedModule, "resolvedModule");
            typedModule = Objects.requireNonNull(typedModule, "typedModule");
            if (!resolvedModule.moduleId().equals(moduleId)
                    || !typedModule.moduleId().equals(moduleId)) {
                throw new IllegalArgumentException(
                        "module record semantic facts belong to another module");
            }
            exports = copy(exports, "exports");
            imports = copy(imports, "imports");
            dependencies = copy(dependencies, "dependencies");
            initializerDeclarations = copy(initializerDeclarations, "initializerDeclarations");
            finalState = Objects.requireNonNull(finalState, "finalState");
            attemptedState = Objects.requireNonNull(attemptedState, "attemptedState");
            callableSummaries = Objects.requireNonNull(callableSummaries, "callableSummaries");
            if (ownership == Ownership.INTRINSIC && !logicalModule.isStdIo()) {
                throw new IllegalArgumentException(
                        "only std->io may use intrinsic module ownership");
            }
            producerGraph = Objects.requireNonNull(producerGraph, "producerGraph");
            sourceRoots = copyRoots(sourceRoots);
            revisionOptions = copyOptions(revisionOptions);
            var node = producerGraph.resolvedGraph().moduleGraph().module(moduleId).orElseThrow();
            if (!node.snapshot().equals(source) || !node.revision().equals(revision)
                    || !node.logicalModule().equals(Optional.of(logicalModule))
                    || producerGraph.resolvedGraph().module(moduleId).orElseThrow() != resolvedModule
                    || producerGraph.module(moduleId).orElseThrow() != typedModule
                    || !resolvedModule.exports().equals(exports)
                    || !resolvedModule.imports().equals(imports)
                    || !producerGraph.semanticFlowFacts().finalState(moduleId).equals(finalState)
                    || !Optional.ofNullable(producerGraph.semanticFlowFacts().attemptedStates().get(moduleId))
                            .equals(attemptedState)
                    || !producerGraph.semanticFlowFacts().callableSummaries().equals(callableSummaries)) {
                throw new IllegalArgumentException("module record does not match its sealed producer facts");
            }
            String expectedRevision = ownership == Ownership.INTRINSIC
                    ? node.revision()
                    : io.mindspice.lyra.compiler.source.ModuleRevision.compute(source, revisionOptions);
            if (!expectedRevision.equals(revision)) {
                throw new IllegalArgumentException("producer revision does not match source and options");
            }
            var expectedDependencies = producerGraph.resolvedGraph().moduleGraph().importsFrom(moduleId)
                    .stream().map(ModuleGraph.Edge::logicalTarget).distinct().sorted().toList();
            var resolvedProducer = producerGraph.resolvedGraph();
            var rootScope = resolvedModule.rootScope();
            var expectedInitializers = resolvedModule.declarations().stream()
                    .map(id -> resolvedProducer.declaration(id).orElseThrow())
                    .filter(value -> value.kind() == io.mindspice.lyra.compiler.semantic.DeclarationKind.LET
                            && value.scopeId().equals(rootScope))
                    .map(io.mindspice.lyra.compiler.semantic.ResolvedDeclaration::id).toList();
            if (!dependencies.equals(expectedDependencies) || !initializerDeclarations.equals(expectedInitializers)) {
                throw new IllegalArgumentException("producer dependency or initialization coverage is not exact");
            }
        }

        public String logicalName() {
            return logicalModule.value();
        }

        public PhysicalSourceKey physicalSource() {
            return source.physicalKey();
        }

        public List<ResolvedExport> exportContracts() {
            return exports;
        }

        public Optional<ResolvedExport> export(String name) {
            Objects.requireNonNull(name, "name");
            return exports.stream().filter(value -> value.name().equals(name)).findFirst();
        }

        public List<ResolvedImportBinding> importBindings() {
            return imports;
        }

        public boolean isIntrinsic() {
            return ownership == Ownership.INTRINSIC;
        }

        public boolean isApplicationOwned() {
            return ownership == Ownership.APPLICATION;
        }

        private static <T> List<T> copy(List<T> values, String name) {
            Objects.requireNonNull(values, name);
            ArrayList<T> result = new ArrayList<>(values.size());
            for (T value : values) {
                result.add(Objects.requireNonNull(value, name + " must not contain null"));
            }
            return List.copyOf(result);
        }
    }

    private static final Comparator<ModuleRecord> MODULE_ORDER = Comparator
            .comparing(ModuleRecord::logicalModule)
            .thenComparing(ModuleRecord::moduleId)
            .thenComparing(ModuleRecord::generationId);

    private final List<ModuleRecord> modules;
    private final Map<LogicalModuleId, ModuleRecord> modulesByLogical;
    private final Map<ModuleId, ModuleRecord> modulesById;
    private final List<SourceRoot> sourceRoots;
    private final Map<String, String> revisionOptions;
    private final List<SourceSnapshot> sourceInventory;
    private final List<ResolvedSource> resolvedInputs;
    private final Optional<ModuleGraph> resolutionTopology;
    private final Optional<ResolvedSemanticGraph> resolvedGraph;
    private final Optional<TypedSemanticGraph> typedGraph;
    private final Optional<SemanticFlowFacts> flowFacts;

    public SessionModuleEnvironment() {
        this(List.of(), List.of(), Map.of(), List.of(), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    public SessionModuleEnvironment(
            List<ModuleRecord> modules,
            List<SourceRoot> sourceRoots,
            Map<String, String> revisionOptions,
            List<SourceSnapshot> sourceInventory,
            List<ResolvedSource> resolvedInputs,
            Optional<ModuleGraph> resolutionTopology,
            Optional<ResolvedSemanticGraph> resolvedGraph,
            Optional<TypedSemanticGraph> typedGraph,
            Optional<SemanticFlowFacts> flowFacts) {
        Objects.requireNonNull(modules, "modules");
        ArrayList<ModuleRecord> ordered = new ArrayList<>();
        for (ModuleRecord module : modules) {
            ordered.add(Objects.requireNonNull(module, "modules must not contain null"));
        }
        ordered.sort(MODULE_ORDER);
        Map<GenerationId, TypedSemanticGraph> generations = new LinkedHashMap<>();
        for (var record : ordered) {
            var previous = generations.putIfAbsent(record.generationId(), record.producerGraph());
            if (previous != null && previous != record.producerGraph()) {
                throw new IllegalArgumentException("graph generation identifies different producer graphs");
            }
        }
        if (ordered.stream().map(ModuleRecord::producerId).distinct().count() != ordered.size()) {
            throw new IllegalArgumentException("module producers must be unique");
        }
        this.modules = List.copyOf(ordered);
        this.modulesByLogical = indexLogical(this.modules);
        this.modulesById = indexModule(this.modules);
        this.sourceRoots = copyRoots(sourceRoots);
        this.revisionOptions = copyOptions(revisionOptions);
        this.sourceInventory = copySnapshots(sourceInventory);
        this.resolvedInputs = copySources(resolvedInputs);
        this.resolutionTopology = Objects.requireNonNull(resolutionTopology, "resolutionTopology");
        this.resolvedGraph = Objects.requireNonNull(resolvedGraph, "resolvedGraph");
        this.typedGraph = Objects.requireNonNull(typedGraph, "typedGraph");
        this.flowFacts = Objects.requireNonNull(flowFacts, "flowFacts");
        validateCoverage();
    }

    /** Builds the source-context portion for a newly sealed graph. */
    public static SessionModuleEnvironment from(
            ModuleGraph topology,
            ResolvedSemanticGraph resolved,
            TypedSemanticGraph typed,
            SourceConfiguration configuration,
            List<ModuleRecord> modules) {
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(resolved, "resolved");
        Objects.requireNonNull(typed, "typed");
        Objects.requireNonNull(configuration, "configuration");
        Map<ModuleId, ModuleGraph.Node> nodes = new LinkedHashMap<>();
        for (var producer : modules) {
            producer.producerGraph().resolvedGraph().moduleGraph().modules()
                    .forEach(node -> nodes.put(node.moduleId(), node));
        }
        topology.modules().forEach(node -> nodes.put(node.moduleId(), node));
        List<SourceSnapshot> snapshots = nodes.values().stream().map(ModuleGraph.Node::snapshot).toList();
        List<ResolvedSource> inputs = nodes.values().stream().filter(node -> node.logicalModule().isPresent())
                .map(node -> ResolvedSource.fromSnapshot(node.logicalModule().orElseThrow(), node.snapshot())).toList();
        return new SessionModuleEnvironment(
                modules,
                configuration.sourceRoots(),
                configuration.revisionOptions().values(),
                snapshots,
                inputs,
                Optional.of(topology),
                Optional.of(resolved),
                Optional.of(typed),
                Optional.of(typed.semanticFlowFacts()));
    }

    public static SessionModuleEnvironment empty() {
        return new SessionModuleEnvironment();
    }

    public List<ModuleRecord> modules() {
        return modules;
    }

    public List<ModuleRecord> orderedModules() {
        return modules;
    }

    public Optional<ModuleRecord> module(LogicalModuleId logicalModule) {
        return Optional.ofNullable(modulesByLogical.get(
                Objects.requireNonNull(logicalModule, "logicalModule")));
    }

    public Optional<ModuleRecord> module(ModuleId moduleId) {
        return Optional.ofNullable(modulesById.get(Objects.requireNonNull(moduleId, "moduleId")));
    }

    public Map<LogicalModuleId, ModuleRecord> modulesByLogical() {
        return modulesByLogical;
    }

    public Map<ModuleId, ModuleRecord> modulesById() {
        return modulesById;
    }

    public Optional<ResolvedExport> export(LogicalModuleId module, String name) {
        return module(module).flatMap(value -> value.export(name));
    }

    public List<SourceRoot> sourceRoots() {
        return sourceRoots;
    }

    public Map<String, String> revisionOptions() {
        return revisionOptions;
    }

    /** All captured source snapshots needed to reproduce this environment. */
    public List<SourceSnapshot> sourceInventory() {
        return sourceInventory;
    }

    /** Provider candidates captured while resolving this environment. */
    public List<ResolvedSource> resolvedInputs() {
        return resolvedInputs;
    }

    public Optional<ModuleGraph> resolutionTopology() {
        return resolutionTopology;
    }

    public Optional<ResolvedSemanticGraph> resolvedGraph() {
        return resolvedGraph;
    }

    public Optional<TypedSemanticGraph> typedGraph() {
        return typedGraph;
    }

    public Optional<SemanticFlowFacts> flowFacts() {
        return flowFacts;
    }

    /** Returns the highest generation identity retained by this environment. */
    public Optional<GenerationId> latestGeneration() {
        return modules.stream().map(ModuleRecord::generationId).max(Comparator.naturalOrder());
    }

    /** Returns the highest producer identity retained by this environment. */
    public Optional<ProducerId> latestProducer() {
        return modules.stream().map(ModuleRecord::producerId).max(Comparator.naturalOrder());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SessionModuleEnvironment environment)) {
            return false;
        }
        return modules.equals(environment.modules)
                && sourceRoots.equals(environment.sourceRoots)
                && revisionOptions.equals(environment.revisionOptions)
                && sourceInventory.equals(environment.sourceInventory)
                && resolvedInputs.equals(environment.resolvedInputs)
                && resolutionTopology.equals(environment.resolutionTopology)
                && resolvedGraph.equals(environment.resolvedGraph)
                && typedGraph.equals(environment.typedGraph)
                && flowFacts.equals(environment.flowFacts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modules, sourceRoots, revisionOptions, sourceInventory,
                resolvedInputs, resolutionTopology, resolvedGraph, typedGraph, flowFacts);
    }

    private void validateCoverage() {
        if (resolutionTopology.isPresent() != resolvedGraph.isPresent()
                || resolvedGraph.isPresent() != typedGraph.isPresent()
                || typedGraph.isPresent() != flowFacts.isPresent()
                || (!modules.isEmpty() && typedGraph.isEmpty())) {
            throw new IllegalArgumentException("environment needs complete sealed source/semantic/flow context");
        }
        typedGraph.ifPresent(typed -> {
            if (typed.resolvedGraph() != resolvedGraph.orElseThrow()
                    || typed.semanticFlowFacts() != flowFacts.orElseThrow()) {
                throw new IllegalArgumentException("environment contains mixed semantic/flow domains");
            }
        });
        Map<io.mindspice.lyra.compiler.source.SourceId, SourceSnapshot> inventory = new LinkedHashMap<>();
        for (var snapshot : sourceInventory) {
            if (inventory.put(snapshot.sourceId(), snapshot) != null) {
                throw new IllegalArgumentException("duplicate captured source identity");
            }
        }
        Map<LogicalModuleId, ResolvedSource> inputs = new LinkedHashMap<>();
        for (var input : resolvedInputs) {
            if (inputs.put(input.logicalModule(), input) != null
                    || !inventory.containsKey(input.sourceId())
                    || !input.equals(ResolvedSource.fromSnapshot(input.logicalModule(), inventory.get(input.sourceId())))) {
                throw new IllegalArgumentException("resolved input inventory is not exact");
            }
        }
        resolutionTopology.ifPresent(topology -> topology.modules().forEach(node -> {
            if (!node.snapshot().equals(inventory.get(node.sourceId()))) {
                throw new IllegalArgumentException("topology source is absent or changed in inventory");
            }
            if (!node.moduleId().equals(topology.rootModule()) && !modulesById.containsKey(node.moduleId())) {
                throw new IllegalArgumentException("topology is missing a producer record");
            }
        }));
        for (ModuleRecord module : modules) {
            for (var node : module.producerGraph().resolvedGraph().moduleGraph().modules()) {
                if (!node.snapshot().equals(inventory.get(node.sourceId()))) {
                    throw new IllegalArgumentException("retained semantic source is absent or changed in inventory");
                }
            }
            if (!module.source().equals(inventory.get(module.source().sourceId()))
                    || !ResolvedSource.fromSnapshot(module.logicalModule(), module.source())
                            .equals(inputs.get(module.logicalModule()))) {
                throw new IllegalArgumentException("retained producer is not covered by captured inputs");
            }
            resolvedGraph.flatMap(graph -> graph.module(module.moduleId())).ifPresent(resolved -> {
                if (!resolved.equals(module.resolvedModule())
                        || typedGraph.orElseThrow().module(module.moduleId()).orElseThrow() != module.typedModule()) {
                    throw new IllegalArgumentException("current graph changed a retained producer identity domain");
                }
            });
            if (modulesByLogical.get(module.logicalModule()) != module
                    || modulesById.get(module.moduleId()) != module) {
                throw new IllegalArgumentException("module environment index is inconsistent");
            }
            if (!module.resolvedModule().exports().equals(module.exports())) {
                throw new IllegalArgumentException("module environment export facts are not sealed");
            }
            if (!module.resolvedModule().imports().equals(module.imports())) {
                throw new IllegalArgumentException("module environment import facts are not sealed");
            }
            for (LogicalModuleId dependency : module.dependencies()) {
                ModuleRecord target = modulesByLogical.get(dependency);
                if (target == null || !module.producerGraph().resolvedGraph().moduleGraph()
                        .moduleFor(dependency).equals(Optional.of(target.moduleId()))
                        || !module.producerGraph().module(target.moduleId()).orElseThrow()
                                .equals(target.typedModule())) {
                    throw new IllegalArgumentException("retained dependency producer is absent or changed: " + dependency);
                }
            }
        }
        resolutionTopology.ifPresent(topology -> {
            if (resolvedGraph.isPresent()
                    && resolvedGraph.orElseThrow().moduleGraph() != topology) {
                // Re-analysis can produce an equal topology object. Identity is
                // not part of the semantic contract, so equality is sufficient.
                if (!resolvedGraph.orElseThrow().moduleGraph().equals(topology)) {
                    throw new IllegalArgumentException(
                            "module environment topology disagrees with resolution");
                }
            }
            if (typedGraph.isPresent()
                    && typedGraph.orElseThrow().resolvedGraph().moduleGraph() != topology
                    && !typedGraph.orElseThrow().resolvedGraph().moduleGraph().equals(topology)) {
                throw new IllegalArgumentException(
                        "module environment topology disagrees with typing");
            }
        });
    }

    private static Map<LogicalModuleId, ModuleRecord> indexLogical(List<ModuleRecord> values) {
        TreeMap<LogicalModuleId, ModuleRecord> result = new TreeMap<>();
        for (ModuleRecord value : values) {
            if (result.put(value.logicalModule(), value) != null) {
                throw new IllegalArgumentException(
                        "duplicate logical module in session environment: "
                                + value.logicalModule());
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private static Map<ModuleId, ModuleRecord> indexModule(List<ModuleRecord> values) {
        TreeMap<ModuleId, ModuleRecord> result = new TreeMap<>();
        for (ModuleRecord value : values) {
            if (result.put(value.moduleId(), value) != null) {
                throw new IllegalArgumentException(
                        "duplicate module identity in session environment: " + value.moduleId());
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private static List<SourceRoot> copyRoots(List<SourceRoot> values) {
        Objects.requireNonNull(values, "sourceRoots");
        ArrayList<SourceRoot> result = new ArrayList<>();
        for (SourceRoot value : values) {
            result.add(Objects.requireNonNull(value, "sourceRoots must not contain null"));
        }
        return List.copyOf(result);
    }

    private static Map<String, String> copyOptions(Map<String, String> values) {
        Objects.requireNonNull(values, "revisionOptions");
        TreeMap<String, String> result = new TreeMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "revision option key");
            String value = Objects.requireNonNull(entry.getValue(), "revision option value");
            if (key.isBlank() || value.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException("invalid revision option");
            }
            if (result.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate revision option: " + key);
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private static List<SourceSnapshot> copySnapshots(List<SourceSnapshot> values) {
        Objects.requireNonNull(values, "sourceInventory");
        ArrayList<SourceSnapshot> result = new ArrayList<>();
        for (SourceSnapshot value : values) {
            result.add(Objects.requireNonNull(value, "sourceInventory must not contain null"));
        }
        result.sort(Comparator.comparing((SourceSnapshot value) -> value.sourceId().value())
                .thenComparing(value -> value.sourceId().isUri() ? 1 : 0));
        return List.copyOf(result);
    }

    private static List<ResolvedSource> copySources(List<ResolvedSource> values) {
        Objects.requireNonNull(values, "resolvedInputs");
        ArrayList<ResolvedSource> result = new ArrayList<>();
        for (ResolvedSource value : values) {
            result.add(Objects.requireNonNull(value, "resolvedInputs must not contain null"));
        }
        result.sort(Comparator.comparing(ResolvedSource::logicalModule)
                .thenComparing(value -> value.sourceId().value())
                .thenComparing(value -> value.sourceId().isUri() ? 1 : 0));
        return List.copyOf(result);
    }
}
