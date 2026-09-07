package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Complete immutable closed typed IR for one checked semantic graph. */
public final class TypedIr implements ImmutablePhaseArtifact {
    /* Kept solely for the pre-Phase-12 test construction bridge.  IR semantics
       are copied into metadata and validation never derives facts from this
       object. */
    private final TypedSemanticGraph semanticGraph;
    private final List<IrModule> modules;
    private final Map<ModuleId, IrModule> modulesById;
    private final Map<ModuleId, SourceSnapshot> sourceSnapshots;
    private final IrProgramMetadata metadata;
    private final boolean validated;

    /**
     * Legacy negative-fixture constructor.  It creates an unvalidated
     * candidate; consumers must call {@link #requireValidated()} and the
     * builder-owned publication path is the only route to a validated value.
     */
    public TypedIr(TypedSemanticGraph semanticGraph, List<IrModule> modules) {
        this(semanticGraph, modules,
                IrProgramMetadata.from(Objects.requireNonNull(semanticGraph, "semanticGraph"),
                        Objects.requireNonNull(modules, "modules"), List.of(), List.of()),
                false);
    }

    /**
     * Candidate constructor retained for package/test invariant fixtures.  It
     * is never a validated publication and must not be handed to a consumer.
     */
    public TypedIr(
            TypedSemanticGraph semanticGraph,
            List<IrModule> modules,
            IrProgramMetadata metadata) {
        this(semanticGraph, modules, metadata, false);
    }

    TypedIr(
            TypedSemanticGraph semanticGraph,
            List<IrModule> modules,
            IrProgramMetadata metadata,
            boolean validated) {
        this.semanticGraph = Objects.requireNonNull(semanticGraph, "semanticGraph");
        Objects.requireNonNull(modules, "modules");
        ArrayList<IrModule> ordered = new ArrayList<>();
        for (IrModule module : modules) {
            ordered.add(Objects.requireNonNull(module, "modules must not contain null"));
        }
        ordered.sort(Comparator
                .comparing((IrModule module) -> module.moduleId().value())
                .thenComparing(module -> module.moduleId().isUri() ? 1 : 0));
        this.modules = List.copyOf(ordered);
        LinkedHashMap<ModuleId, IrModule> index = new LinkedHashMap<>();
        for (IrModule module : this.modules) {
            if (index.put(module.moduleId(), module) != null) {
                throw new IllegalArgumentException("duplicate IR module");
            }
        }
        this.modulesById = Collections.unmodifiableMap(index);
        LinkedHashMap<ModuleId, SourceSnapshot> snapshots = new LinkedHashMap<>();
        semanticGraph.resolvedGraph().moduleGraph().modules().stream()
                .sorted(Comparator.comparing(node -> node.moduleId().value()))
                .forEach(node -> snapshots.put(node.moduleId(), node.snapshot()));
        this.sourceSnapshots = Collections.unmodifiableMap(snapshots);
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.validated = validated;
    }

    static TypedIr candidate(
            TypedSemanticGraph semanticGraph,
            List<IrModule> modules,
            IrProgramMetadata metadata) {
        return new TypedIr(semanticGraph, modules, metadata, false);
    }

    static TypedIr publish(TypedIr candidate) {
        Objects.requireNonNull(candidate, "candidate");
        List<io.mindspice.lyra.compiler.diagnostic.Diagnostic> diagnostics =
                IrValidator.validateCandidate(candidate);
        if (!diagnostics.isEmpty()) {
            throw new IllegalArgumentException(
                    "cannot publish invalid typed IR: " + diagnostics);
        }
        return new TypedIr(candidate.semanticGraph, candidate.modules, candidate.metadata, true);
    }

    /** Compatibility view used only by existing invariant-fixture helpers. */
    @Deprecated
    public TypedSemanticGraph semanticGraph() {
        return semanticGraph;
    }

    public TypedSemanticGraph typedSemanticGraph() {
        return semanticGraph;
    }

    public List<IrModule> modules() {
        return modules;
    }

    public Optional<IrModule> module(ModuleId moduleId) {
        return Optional.ofNullable(modulesById.get(Objects.requireNonNull(moduleId, "moduleId")));
    }

    /** Immutable source projection retained for backend debug attributes only. */
    public Optional<SourceSnapshot> sourceSnapshot(ModuleId moduleId) {
        return Optional.ofNullable(sourceSnapshots.get(
                Objects.requireNonNull(moduleId, "moduleId")));
    }

    public IrModule rootModule() {
        return module(semanticGraph.resolvedGraph().moduleGraph().rootModule())
                .orElseThrow(() -> new IllegalStateException("typed IR has no root module"));
    }

    public IrModule root() {
        return rootModule();
    }

    public Optional<IrSessionExecution> sessionExecution() { return metadata.sessionExecution(); }

    public Optional<IrSessionExecution.ExternalAccess> externalAccess(ModuleId consumer, DeclarationId declaration) {
        return sessionExecution().flatMap(value -> value.access(consumer, declaration));
    }

    public IrProgramMetadata metadata() {
        return metadata;
    }

    public IrProgramMetadata programMetadata() {
        return metadata;
    }

    public List<IrDeclaration> declarations() {
        return metadata.declarations();
    }

    public List<IrReference> references() {
        return metadata.references();
    }

    public List<IrLambda> lambdas() {
        return metadata.lambdas();
    }

    public List<IrCapture> captures() {
        return metadata.captures();
    }

    public List<IrCell> cells() {
        return metadata.cells();
    }

    public List<IrExport> exports() {
        return metadata.exports();
    }

    public List<IrImportBinding> imports() {
        return metadata.imports();
    }

    public IrFunctionLinkage functionLinkage() {
        return metadata.functionLinkage();
    }

    public List<IrClosureInitialization> closureInitializations() {
        return metadata.closureInitializations();
    }

    public List<IrAggregateAllocation> aggregateAllocations() {
        return metadata.flowMetadata().aggregateAllocations();
    }

    public List<IrFailureSite> failureSites() {
        return metadata.failureSites();
    }

    public List<IrExpressionSite> expressionSites() {
        return metadata.expressionSites();
    }

    public List<IrEvaluationOrder> evaluationOrders() {
        return metadata.evaluationOrders();
    }

    public IrInitializationPlan initializationPlan() {
        return metadata.initializationPlan();
    }

    public List<ModuleId> initializationOrder() {
        return initializationPlan().initializationOrder();
    }

    public List<IrInitializationDependency> initializationDependencies() {
        return initializationPlan().dependencies();
    }

    public List<IrInitializationCycle> initializationCycles() {
        return initializationPlan().cycles();
    }

    public IrFlowMetadata flowMetadata() {
        return metadata.flowMetadata();
    }

    public io.mindspice.lyra.compiler.semantic.flow.SemanticFlowFacts semanticFlowFacts() {
        return flowMetadata().sourceFacts();
    }

    public io.mindspice.lyra.compiler.semantic.flow.SemanticFlowFacts flowFacts() {
        return semanticFlowFacts();
    }

    public List<DeclarationId> declarationIds() {
        return declarations().stream().map(IrDeclaration::id).toList();
    }

    public List<ReferenceId> referenceIds() {
        return references().stream().map(IrReference::id).toList();
    }

    public List<LambdaId> lambdaIds() {
        return lambdas().stream().map(IrLambda::id).toList();
    }

    public List<CaptureId> captureIds() {
        return captures().stream().map(IrCapture::id).toList();
    }

    public List<io.mindspice.lyra.compiler.identity.ExportId> exportIds() {
        return metadata.exportIds();
    }

    public Optional<IrModuleState> moduleState(ModuleId moduleId) {
        return module(moduleId).map(IrModule::state);
    }

    /** Whether this value was published by the builder's successful sealing path. */
    public boolean isValidated() {
        return validated;
    }

    public boolean validated() {
        return validated;
    }

    /**
     * Downstream phases use this gate instead of accepting an arbitrary
     * constructor-created candidate.
     */
    public TypedIr requireValidated() {
        return IrValidator.requireValidated(this);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof TypedIr ir
                && semanticGraph.equals(ir.semanticGraph)
                && modules.equals(ir.modules)
                && metadata.equals(ir.metadata)
                && validated == ir.validated;
    }

    @Override
    public int hashCode() {
        return Objects.hash(semanticGraph, modules, metadata, validated);
    }

    @Override
    public String toString() {
        return "TypedIr[modules=" + modules.size()
                + ", expressions=" + metadata.expressionSites().size()
                + ", validated=" + validated + "]";
    }
}
