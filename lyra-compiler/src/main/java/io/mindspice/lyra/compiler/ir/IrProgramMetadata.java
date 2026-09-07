package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ExportId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.semantic.ResolvedCapture;
import io.mindspice.lyra.compiler.semantic.ResolvedDeclaration;
import io.mindspice.lyra.compiler.semantic.ResolvedExport;
import io.mindspice.lyra.compiler.semantic.ResolvedLambda;
import io.mindspice.lyra.compiler.semantic.ResolvedReference;
import io.mindspice.lyra.compiler.semantic.TypedDeclaration;
import io.mindspice.lyra.compiler.semantic.TypedExpression;
import io.mindspice.lyra.compiler.semantic.TypedExpressionKind;
import io.mindspice.lyra.compiler.semantic.TypedFailureSite;
import io.mindspice.lyra.compiler.semantic.TypedLambda;
import io.mindspice.lyra.compiler.semantic.TypedReference;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.source.ModuleId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Immutable module-level data carried by a sealed typed IR.
 *
 * <p>This is intentionally an internal data projection.  It retains exact
 * semantic identities, topology, flow provenance, failure sites and schedule
 * data needed by a future emitter without retaining syntax as a backend input
 * or defining a serialized/public IR format.</p>
 */
public final class IrProgramMetadata implements ImmutablePhaseArtifact {
    private final List<IrDeclaration> declarations;
    private final List<IrReference> references;
    private final List<IrLambda> lambdas;
    private final List<IrCapture> captures;
    private final List<IrCell> cells;
    private final List<IrExport> exports;
    private final List<IrImportBinding> imports;
    private final IrFunctionLinkage functionLinkage;
    private final List<IrClosureInitialization> closureInitializations;
    private final List<IrFailureSite> failureSites;
    private final List<IrExpressionSite> expressionSites;
    private final List<IrEvaluationOrder> evaluationOrders;
    private final IrInitializationPlan initializationPlan;
    private final IrFlowMetadata flowMetadata;
    private final Optional<IrSessionExecution> sessionExecution;

    public IrProgramMetadata(
            List<IrDeclaration> declarations,
            List<IrReference> references,
            List<IrLambda> lambdas,
            List<IrCapture> captures,
            List<IrCell> cells,
            List<IrExport> exports,
            List<IrImportBinding> imports,
            IrFunctionLinkage functionLinkage,
            List<IrClosureInitialization> closureInitializations,
            List<IrFailureSite> failureSites,
            List<IrExpressionSite> expressionSites,
            List<IrEvaluationOrder> evaluationOrders,
            IrInitializationPlan initializationPlan,
            IrFlowMetadata flowMetadata) {
        this(declarations, references, lambdas, captures, cells, exports, imports, functionLinkage,
                closureInitializations, failureSites, expressionSites, evaluationOrders, initializationPlan,
                flowMetadata, Optional.empty());
    }

    private IrProgramMetadata(List<IrDeclaration> declarations, List<IrReference> references,
            List<IrLambda> lambdas, List<IrCapture> captures, List<IrCell> cells, List<IrExport> exports,
            List<IrImportBinding> imports, IrFunctionLinkage functionLinkage,
            List<IrClosureInitialization> closureInitializations, List<IrFailureSite> failureSites,
            List<IrExpressionSite> expressionSites, List<IrEvaluationOrder> evaluationOrders,
            IrInitializationPlan initializationPlan, IrFlowMetadata flowMetadata,
            Optional<IrSessionExecution> sessionExecution) {
        this.sessionExecution = Objects.requireNonNull(sessionExecution, "sessionExecution");
        this.declarations = ordered(declarations, Comparator.comparing(IrDeclaration::id),
                "declarations");
        this.references = ordered(references, Comparator.comparing(IrReference::id),
                "references");
        this.lambdas = ordered(lambdas, Comparator.comparing(IrLambda::id), "lambdas");
        this.captures = ordered(captures, Comparator.comparing(IrCapture::id), "captures");
        this.cells = ordered(cells, Comparator.comparing(IrCell::id), "cells");
        this.exports = ordered(exports, Comparator
                .comparing(IrExport::moduleId).thenComparing(IrExport::name)
                .thenComparing(IrExport::declarationId)
                .thenComparing(value -> value.exportId().map(Object::toString).orElse("")), "exports");
        this.imports = ordered(imports, Comparator.comparing(IrImportBinding::declarationId), "imports");
        this.functionLinkage = Objects.requireNonNull(functionLinkage, "functionLinkage");
        this.closureInitializations = ordered(closureInitializations,
                Comparator.comparing(IrClosureInitialization::lambdaId), "closureInitializations");
        this.failureSites = ordered(failureSites, Comparator.comparing(IrFailureSite::siteId),
                "failureSites");
        this.expressionSites = ordered(expressionSites, Comparator.comparing(IrExpressionSite::siteId),
                "expressionSites");
        this.evaluationOrders = ordered(evaluationOrders, Comparator.naturalOrder(),
                "evaluationOrders");
        this.initializationPlan = Objects.requireNonNull(initializationPlan, "initializationPlan");
        this.flowMetadata = Objects.requireNonNull(flowMetadata, "flowMetadata");
        requireUnique(this.declarations.stream().map(IrDeclaration::id).toList(),
                "duplicate IR declaration identity");
        requireUnique(this.references.stream().map(IrReference::id).toList(),
                "duplicate IR reference identity");
        requireUnique(this.lambdas.stream().map(IrLambda::id).toList(),
                "duplicate IR lambda identity");
        requireUnique(this.captures.stream().map(IrCapture::id).toList(),
                "duplicate IR capture identity");
        requireUnique(this.cells.stream().map(IrCell::id).toList(),
                "duplicate IR cell identity");
        requireUnique(this.imports.stream().map(IrImportBinding::declarationId).toList(),
                "duplicate IR import identity");
        requireUnique(this.exports.stream().map(value -> value.moduleId() + "::" + value.name()).toList(),
                "duplicate IR export name");
        requireUnique(this.failureSites.stream().map(IrFailureSite::siteId).toList(),
                "duplicate IR failure-site identity");
        requireUnique(this.closureInitializations.stream().map(IrClosureInitialization::lambdaId).toList(),
                "duplicate IR closure initialization");
        requireUnique(this.expressionSites.stream().map(IrExpressionSite::siteId).toList(),
                "duplicate IR expression site identity");
        List<String> evaluationKeys = this.evaluationOrders.stream()
                .map(value -> value.ownerSite().map(Object::toString).orElse("module")
                        + "@" + value.moduleId() + "@" + value.span())
                .toList();
        requireUnique(evaluationKeys, "duplicate IR expression evaluation order");
    }

    /** Builds the complete projection and installs builder-owned source correspondence. */
    static IrProgramMetadata from(
            TypedSemanticGraph graph,
            List<IrModule> modules,
            List<IrExpressionSite> expressionSites,
            List<IrEvaluationOrder> evaluationOrders) {
        return from(graph, modules, expressionSites, evaluationOrders, Optional.empty());
    }

    static IrProgramMetadata from(TypedSemanticGraph graph, List<IrModule> modules,
            List<IrExpressionSite> expressionSites, List<IrEvaluationOrder> evaluationOrders,
            Optional<IrSessionExecution> execution) {
        Objects.requireNonNull(graph, "graph");
        java.util.function.Predicate<ModuleId> emits = module -> execution.map(value -> value.emits(module)).orElse(true);
        Map<DeclarationId, TypedDeclaration> typedDeclarations = graph.declarations().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        TypedDeclaration::id, value -> value));
        List<IrDeclaration> declarations = new ArrayList<>();
        for (ResolvedDeclaration resolved : graph.resolvedGraph().declarations()) {
            TypedDeclaration typed = typedDeclarations.get(resolved.id());
            declarations.add(new IrDeclaration(
                    resolved.id(), resolved.name(), resolved.nameSpan(), resolved.span(),
                    resolved.moduleId(), resolved.scopeId(), resolved.kind(), resolved.visibility(),
                    resolved.bindingMutability(), typed == null ? resolved.effectiveContract() : typed.contract(),
                    resolved.initializerLambda(),
                    typed == null ? Optional.empty() : typed.initializer().map(graph::flowSiteId),
                    resolved.signaturePredeclared(), resolved.imported(), resolved.reExported(),
                    resolved.importedModule(), resolved.importedName(), resolved.originDeclaration(),
                    resolved.originExport(), resolved.replacementOf(), resolved.externalBinding()));
        }

        Map<ReferenceId, TypedReference> typedReferences = graph.references().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        TypedReference::id, value -> value));
        List<IrReference> references = new ArrayList<>();
        for (ResolvedReference resolved : graph.resolvedGraph().references()) {
            TypedReference typed = typedReferences.get(resolved.id());
            references.add(new IrReference(
                    resolved.id(), resolved.name(), resolved.span(), resolved.moduleId(),
                    resolved.scopeId(), resolved.kind(), typed == null ? Optional.empty() : typed.type(),
                    resolved.targetDeclaration(), resolved.targetModule(), resolved.targetExport(),
                    resolved.fromLambda(), resolved.capture(), graph.flowSiteId(resolved.id())));
        }

        List<IrImportBinding> imports = graph.resolvedGraph().imports().stream()
                .map(value -> IrImportBinding.from(value, execution)).toList();
        IrFunctionLinkage functionLinkage = IrFunctionLinkage.from(
                graph.resolvedGraph().functionLinkage());

        Map<LambdaId, TypedLambda> typedLambdas = graph.lambdas().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        TypedLambda::id, value -> value));
        Map<LambdaId, IrNode> loweredLambdaBodies = loweredLambdaBodies(modules);
        List<IrLambda> lambdas = new ArrayList<>();
        for (ResolvedLambda resolved : graph.resolvedGraph().lambdas()) {
            if (!emits.test(resolved.moduleId())) continue;
            TypedLambda typed = typedLambdas.get(resolved.id());
            if (typed == null) {
                throw new IllegalArgumentException("typed lambda is absent: " + resolved.id());
            }
            TypedExpression creation = graph.expressions().stream()
                    .filter(expression -> expression.kind() == TypedExpressionKind.LAMBDA)
                    .filter(expression -> expression.lambdaId().filter(resolved.id()::equals).isPresent())
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "lambda has no typed creation expression: " + resolved.id()));
            IrNode loweredBody = loweredLambdaBodies.get(resolved.id());
            if (loweredBody == null) {
                // The legacy public constructor is allowed to create a
                // negative candidate whose module tree was edited.  Recover
                // only this immutable body projection for diagnostics; this
                // is lowering, never semantic-flow reconstruction.
                loweredBody = TypedIrBuilder.lowerForValidation(graph, typed.body());
            }
            lambdas.add(new IrLambda(
                    resolved.id(), resolved.moduleId(), resolved.span(), resolved.bodySpan(),
                    resolved.scopeId(), resolved.signature().orElseThrow(), resolved.parameterIds(),
                    resolved.ownerDeclaration(), resolved.captures(),
                    loweredBody, graph.flowSiteId(creation)));
        }

        List<IrCapture> captures = new ArrayList<>();
        for (ResolvedCapture resolved : graph.resolvedGraph().captures()) {
            if (!emits.test(resolved.moduleId())) continue;
            captures.add(new IrCapture(
                    resolved.id(), resolved.lambdaId(), resolved.declarationId(), resolved.moduleId(),
                    resolved.span(), resolved.declarationSpan(), resolved.mode(), resolved.sharedCellId(),
                    resolved.references(), graph.contract(resolved.declarationId()).orElseThrow(),
                    graph.flowSiteId(resolved.id())));
        }
        List<IrCell> cells = buildCells(captures, graph);

        List<IrExport> exports = graph.resolvedGraph().exports().stream()
                .map(IrProgramMetadata::copyExport).toList();
        List<IrClosureInitialization> closureInitializations = closureInitializations(
                graph, functionLinkage).stream().filter(value -> emits.test(
                        graph.lambda(value.lambdaId()).orElseThrow().moduleId())).toList();
        List<IrFailureSite> failureSites = failureSites(graph).stream()
                .filter(value -> emits.test(value.moduleId())).toList();

        return new IrProgramMetadata(
                declarations, references, lambdas, captures, cells, exports, imports,
                functionLinkage, closureInitializations, failureSites,
                expressionSites, evaluationOrders,
                IrInitializationPlan.from(graph.initializationPlan(), graph).project(emits),
                IrFlowMetadata.from(graph.semanticFlowFacts(), graph), execution);
    }

    public Optional<IrSessionExecution> sessionExecution() { return sessionExecution; }

    public List<IrDeclaration> declarations() {
        return declarations;
    }

    public Optional<IrDeclaration> declaration(DeclarationId id) {
        Objects.requireNonNull(id, "id");
        return declarations.stream().filter(value -> value.id().equals(id)).findFirst();
    }

    public List<IrReference> references() {
        return references;
    }

    public Optional<IrReference> reference(ReferenceId id) {
        Objects.requireNonNull(id, "id");
        return references.stream().filter(value -> value.id().equals(id)).findFirst();
    }

    public List<IrLambda> lambdas() {
        return lambdas;
    }

    public Optional<IrLambda> lambda(LambdaId id) {
        Objects.requireNonNull(id, "id");
        return lambdas.stream().filter(value -> value.id().equals(id)).findFirst();
    }

    public List<IrCapture> captures() {
        return captures;
    }

    public Optional<IrCapture> capture(CaptureId id) {
        Objects.requireNonNull(id, "id");
        return captures.stream().filter(value -> value.id().equals(id)).findFirst();
    }

    public List<IrCell> cells() {
        return cells;
    }

    public List<IrExport> exports() {
        return exports;
    }

    public Optional<IrExport> export(ModuleId moduleId, String name) {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(name, "name");
        return exports.stream().filter(value -> value.moduleId().equals(moduleId)
                && value.name().equals(name)).findFirst();
    }

    public List<IrImportBinding> imports() {
        return imports;
    }

    public IrFunctionLinkage functionLinkage() {
        return functionLinkage;
    }

    public List<IrClosureInitialization> closureInitializations() {
        return closureInitializations;
    }

    public List<IrFailureSite> failureSites() {
        return failureSites;
    }

    public List<IrExpressionSite> expressionSites() {
        return expressionSites;
    }

    public List<IrEvaluationOrder> evaluationOrders() {
        return evaluationOrders;
    }

    public IrInitializationPlan initializationPlan() {
        return initializationPlan;
    }

    public List<io.mindspice.lyra.compiler.source.ModuleId> initializationOrder() {
        return initializationPlan.initializationOrder();
    }

    public List<IrInitializationDependency> initializationDependencies() {
        return initializationPlan.dependencies();
    }

    public List<IrInitializationCycle> initializationCycles() {
        return initializationPlan.cycles();
    }

    public IrFlowMetadata flowMetadata() {
        return flowMetadata;
    }

    public List<ExportId> exportIds() {
        return exports.stream().map(IrExport::exportId).flatMap(Optional::stream).toList();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof IrProgramMetadata metadata
                && declarations.equals(metadata.declarations)
                && references.equals(metadata.references)
                && lambdas.equals(metadata.lambdas)
                && captures.equals(metadata.captures)
                && cells.equals(metadata.cells)
                && exports.equals(metadata.exports)
                && imports.equals(metadata.imports)
                && functionLinkage.equals(metadata.functionLinkage)
                && closureInitializations.equals(metadata.closureInitializations)
                && failureSites.equals(metadata.failureSites)
                && expressionSites.equals(metadata.expressionSites)
                && evaluationOrders.equals(metadata.evaluationOrders)
                && initializationPlan.equals(metadata.initializationPlan)
                && flowMetadata.equals(metadata.flowMetadata)
                && sessionExecution.equals(metadata.sessionExecution);
    }

    @Override
    public int hashCode() {
        return Objects.hash(declarations, references, lambdas, captures, cells, exports,
                imports, functionLinkage, closureInitializations, failureSites,
                expressionSites, evaluationOrders, initializationPlan,
                flowMetadata, sessionExecution);
    }

    @Override
    public String toString() {
        return "IrProgramMetadata[declarations=" + declarations.size()
                + ", references=" + references.size()
                + ", lambdas=" + lambdas.size()
                + ", captures=" + captures.size()
                + ", imports=" + imports.size()
                + ", failures=" + failureSites.size() + "]";
    }

    private static IrExport copyExport(ResolvedExport value) {
        return new IrExport(value.name(), value.moduleId(), value.declarationId(), value.span(),
                value.contract(), value.functionSignature(), value.exportId(), value.reExport(),
                value.originModule(), value.originName(), value.originDeclaration(), value.originExport());
    }

    private static Map<LambdaId, IrNode> loweredLambdaBodies(List<IrModule> modules) {
        Objects.requireNonNull(modules, "modules");
        TreeMap<LambdaId, IrNode> result = new TreeMap<>();
        for (IrModule module : modules) {
            IrTraversal.walk(module.body(), node -> {
                if (node instanceof IrNode.Lambda lambda && lambda.lambdaId().isPresent()) {
                    IrNode previous = result.put(lambda.lambdaId().orElseThrow(), lambda.body());
                    if (previous != null && !previous.equals(lambda.body())) {
                        throw new IllegalArgumentException("lambda has multiple lowered bodies");
                    }
                }
            });
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<IrClosureInitialization> closureInitializations(
            TypedSemanticGraph graph, IrFunctionLinkage linkage) {
        List<IrClosureInitialization> result = new ArrayList<>();
        for (var lambda : graph.resolvedGraph().lambdas()) {
            List<io.mindspice.lyra.compiler.identity.DeclarationId> cells = lambda.captures().stream()
                    .map(id -> graph.resolvedGraph().capture(id).orElseThrow())
                    .map(value -> value.sharedCellId().orElse(null))
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .toList();
            boolean recursive = lambda.ownerDeclaration().flatMap(linkage::component)
                    .map(IrFunctionScc::recursive).orElse(false);
            result.add(new IrClosureInitialization(
                    lambda.id(), lambda.moduleId(), lambda.span(), lambda.scopeId(),
                    lambda.ownerDeclaration(), lambda.captures(), cells, recursive));
        }
        return List.copyOf(result);
    }

    private static List<IrCell> buildCells(
            List<IrCapture> captures, TypedSemanticGraph graph) {
        TreeMap<DeclarationId, List<IrCapture>> grouped = new TreeMap<>();
        for (IrCapture capture : captures) {
            capture.sharedCellId().ifPresent(cell -> grouped
                    .computeIfAbsent(cell, ignored -> new ArrayList<>()).add(capture));
        }
        List<IrCell> result = new ArrayList<>();
        for (Map.Entry<DeclarationId, List<IrCapture>> entry : grouped.entrySet()) {
            var declaration = graph.resolvedGraph().declaration(entry.getKey()).orElseThrow();
            result.add(new IrCell(entry.getKey(), entry.getKey(), declaration.moduleId(),
                    declaration.scopeId(), graph.contract(entry.getKey()).orElseThrow(),
                    entry.getValue().stream().map(IrCapture::id).sorted().toList(),
                    graph.semanticFlowFacts().valueAtDeclaration(entry.getKey())));
        }
        return List.copyOf(result);
    }

    private static List<IrFailureSite> failureSites(TypedSemanticGraph graph) {
        List<TypedFailureSite> semanticSites = graph.failureSites();
        List<TypedFailureSite> remaining = new ArrayList<>(semanticSites);
        List<IrFailureSite> result = new ArrayList<>();
        for (TypedExpression expression : graph.expressions()) {
            Optional<TypedFailureSite> site = TypedFailureSite.forExpression(expression);
            if (site.isEmpty()) {
                continue;
            }
            TypedFailureSite expected = site.orElseThrow();
            int index = remaining.indexOf(expected);
            if (index < 0) {
                throw new IllegalArgumentException("typed failure-site index has no source entry at "
                        + expression.span());
            }
            remaining.remove(index);
            result.add(new IrFailureSite(
                    graph.flowSiteId(expression),
                    ModuleId.fromSourceId(expression.span().sourceId()),
                    expression.span(), expression.type(), checkKind(expected),
                    expected.failureCode(), expected.expressionKind()));
        }
        if (!remaining.isEmpty()) {
            throw new IllegalArgumentException("typed failure-site index has an unrepresented entry");
        }
        result.sort(Comparator.comparing(IrFailureSite::siteId));
        return List.copyOf(result);
    }

    private static IrCheckKind checkKind(TypedFailureSite site) {
        return switch (site.kind()) {
            case ARITHMETIC -> IrCheckKind.ARITHMETIC;
            case DIVISION -> IrCheckKind.DIVISION;
            case CONVERSION -> IrCheckKind.EXPLICIT_CONVERSION;
            case BOUNDS -> IrCheckKind.BOUNDS;
        };
    }

    private static <T> List<T> ordered(List<T> values, Comparator<T> comparator, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<T> copy = new ArrayList<>();
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " must not contain null"));
        }
        copy.sort(comparator);
        return List.copyOf(copy);
    }

    private static <T> void requireUnique(List<T> values, String message) {
        if (new LinkedHashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(message);
        }
    }

}
