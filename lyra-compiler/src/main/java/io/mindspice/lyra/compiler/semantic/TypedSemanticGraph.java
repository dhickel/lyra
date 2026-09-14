package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.semantic.flow.SemanticFlowFacts;
import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.identity.IdentityAllocator;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.BindingContract;
import io.mindspice.lyra.compiler.types.TupleType;
import io.mindspice.lyra.compiler.types.TypeQualifier;
import io.mindspice.lyra.compiler.types.TypeRules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Complete immutable output of bidirectional type checking.  It is separate
 * from both the syntax tree and the lowering IR, while retaining the one
 * resolved graph's identities for every link.  Canonical phase-11 flow facts
 * and the initialization plan are sealed together; the flow artifact remains
 * an internal, JVM-independent, non-serialized compiler representation.
 */
public final class TypedSemanticGraph implements ImmutablePhaseArtifact, TypedSemanticInput {
    private final TypedSemanticCore sealingCore;
    private final ResolvedSemanticGraph resolvedGraph;
    private final List<TypedModule> modules;
    private final List<TypedDeclaration> declarations;
    private final List<TypedReference> references;
    private final List<TypedLambda> lambdas;
    private final List<TypedConversion> conversions;
    private final List<TypedExpression> expressions;
    private final Map<DeclarationId, BindingContract> contractsByDeclaration;
    private final Map<ModuleId, TypedModule> modulesById;
    private final Map<DeclarationId, TypedDeclaration> declarationsById;
    private final Map<ReferenceId, TypedReference> referencesById;
    private final Map<LambdaId, TypedLambda> lambdasById;
    private final Map<SourceSpan, List<TypedExpression>> expressionsBySpan;
    private final List<TypedMutation> mutations;
    private final InitializationPlan initializationPlan;
    private final List<TypedFailureSite> failureSites;
    private final SemanticFlowFacts flowFacts;

    /**
     * The only package-owned publication path for a complete typed graph.
     * Facts and the derived initialization plan are supplied by the one
     * canonical flow/planning pass; this method never performs semantic
     * evaluation itself.
     */
    static TypedSemanticGraph seal(
            TypedSemanticCore core,
            SemanticFlowFacts facts,
            InitializationPlan initializationPlan) {
        return new TypedSemanticGraph(
                Objects.requireNonNull(core, "core"),
                Objects.requireNonNull(facts, "facts"),
                Objects.requireNonNull(initializationPlan, "initializationPlan"));
    }

    private TypedSemanticGraph(
            TypedSemanticCore core,
            SemanticFlowFacts facts,
            InitializationPlan initializationPlan) {
        this.sealingCore = Objects.requireNonNull(core, "core");
        this.resolvedGraph = core.resolvedGraph();
        this.modules = core.modules();
        this.declarations = core.declarations();
        this.references = core.references();
        this.lambdas = core.lambdas();
        this.conversions = core.conversions();
        this.expressions = core.expressions();
        this.contractsByDeclaration = core.contractsByDeclaration();
        this.expressionsBySpan = core.expressionsBySpan();
        this.mutations = core.mutations();
        this.failureSites = core.failureSites();
        this.modulesById = core.modulesById();
        this.declarationsById = core.declarationsById();
        this.referencesById = core.referencesById();
        this.lambdasById = core.lambdasById();
        this.flowFacts = Objects.requireNonNull(facts, "facts");
        this.initializationPlan = Objects.requireNonNull(initializationPlan, "initializationPlan");

        resolvedGraph.validateSourceAuthority();
        validateCompleteMembership();
        TypedSemanticProvenance.validate(this);
        core.flowProvenance().orElseThrow(() -> new IllegalArgumentException(
                        "typed semantic core has no canonical flow producer record"))
                .validate(core, flowFacts);
        SemanticFlowFactValidator.validate(this, flowFacts);
        validateCanonicalInitializationPlan();
    }

    TypedSemanticCore sealingCore() {
        return sealingCore;
    }

    public ResolvedSemanticGraph resolvedGraph() {
        return resolvedGraph;
    }

    public ResolvedSemanticGraph resolved() {
        return resolvedGraph;
    }

    public ResolvedSemanticGraph graph() {
        return resolvedGraph;
    }

    @Override
    public IdentityAllocator allocator() {
        return sealingCore.allocator();
    }

    public List<TypedModule> modules() {
        return modules;
    }

    public Optional<TypedModule> module(ModuleId moduleId) {
        return Optional.ofNullable(modulesById.get(Objects.requireNonNull(moduleId, "moduleId")));
    }

    public List<TypedDeclaration> declarations() {
        return declarations;
    }

    public Optional<TypedDeclaration> declaration(DeclarationId id) {
        return Optional.ofNullable(declarationsById.get(Objects.requireNonNull(id, "id")));
    }

    public List<TypedReference> references() {
        return references;
    }

    public Optional<TypedReference> reference(ReferenceId id) {
        return Optional.ofNullable(referencesById.get(Objects.requireNonNull(id, "id")));
    }

    public List<TypedLambda> lambdas() {
        return lambdas;
    }

    public Optional<TypedLambda> lambda(LambdaId id) {
        return Optional.ofNullable(lambdasById.get(Objects.requireNonNull(id, "id")));
    }

    public List<TypedConversion> conversions() {
        return conversions;
    }

    public List<TypedExpression> expressions() {
        return expressions;
    }

    @Override
    public FlowSiteId flowSiteId(TypedExpression expression) {
        return sealingCore.flowSiteId(expression);
    }

    @Override
    public ScopeId flowScopeId(TypedExpression expression) {
        return sealingCore.flowScopeId(expression);
    }

    @Override
    public FlowSiteId flowSiteId(ReferenceId referenceId) {
        return sealingCore.flowSiteId(referenceId);
    }

    @Override
    public FlowSiteId flowSiteId(CaptureId captureId) {
        return sealingCore.flowSiteId(captureId);
    }

    public List<TypedMutation> mutations() {
        return mutations;
    }

    public List<TypedMutation> mutationSites() {
        return mutations;
    }

    public InitializationPlan initializationPlan() {
        return initializationPlan;
    }

    public InitializationPlan initialization() {
        return initializationPlan;
    }

    public List<ModuleId> initializationOrder() {
        return initializationPlan.initializationOrder();
    }

    public List<InitializationCycle> initializationCycles() {
        return initializationPlan.cycles();
    }

    public List<InitializationDependency> initializationDependencies() {
        return initializationPlan.dependencies();
    }

    /** Read-only canonical phase-11 flow facts attached at publication. */
    public SemanticFlowFacts semanticFlowFacts() {
        return flowFacts;
    }

    /** Alias for compiler phases that refer to the artifact as flow facts. */
    public SemanticFlowFacts flowFacts() {
        return flowFacts;
    }

    public List<TypedFailureSite> failureSites() {
        return failureSites;
    }

    public List<TypedFailureSite> runtimeFailureSites() {
        return failureSites;
    }

    public Map<DeclarationId, BindingContract> contractsByDeclaration() {
        return contractsByDeclaration;
    }

    public Optional<BindingContract> contract(DeclarationId id) {
        return Optional.ofNullable(contractsByDeclaration.get(Objects.requireNonNull(id, "id")));
    }

    public Optional<io.mindspice.lyra.compiler.types.LyraType> typeOf(DeclarationId id) {
        return contract(id).map(BindingContract::valueType);
    }

    public Optional<io.mindspice.lyra.compiler.types.LyraType> typeOf(ReferenceId id) {
        return reference(id).flatMap(TypedReference::type);
    }

    public List<TypedExpression> expressionsAt(SourceSpan span) {
        Objects.requireNonNull(span, "span");
        return expressionsBySpan.getOrDefault(span, List.of());
    }

    public Map<SourceSpan, List<TypedExpression>> expressionsBySpan() {
        return expressionsBySpan;
    }

    private void validateCanonicalInitializationPlan() {
        InitializationAnalyzer.Analysis canonical = InitializationAnalyzer.plan(this, flowFacts);
        if (canonical.firstCycle().isPresent()) {
            throw new IllegalArgumentException(
                    "a typed semantic graph with an eager initialization cycle cannot publish");
        }
        if (!initializationPlan.equals(canonical.plan())) {
            throw new IllegalArgumentException(
                    "initialization plan does not match canonical eager dependency analysis");
        }
    }

    private void validateCompleteMembership() {
        if (modules.size() != resolvedGraph.modules().size()) {
            throw new IllegalArgumentException("typed graph does not cover every resolved module");
        }
        for (TypedModule module : modules) {
            if (resolvedGraph.module(module.moduleId()).isEmpty()) {
                throw new IllegalArgumentException("typed module is absent from the resolved graph");
            }
            if (resolvedGraph.scopeTree().scope(module.rootScope()).isEmpty()) {
                throw new IllegalArgumentException("typed module root scope is absent from the resolved graph");
            }
            var sourceProgram = resolvedGraph.moduleGraph().module(module.moduleId()).orElseThrow().program();
            if (module.forms().size() != sourceProgram.forms().size()) {
                throw new IllegalArgumentException("typed module skipped a top-level source form");
            }
            for (int index = 0; index < module.forms().size(); index++) {
                if (!module.forms().get(index).span().equals(sourceProgram.forms().get(index).span())) {
                    throw new IllegalArgumentException("typed module form does not preserve source order/span");
                }
            }
        }

        if (declarations.size() != resolvedGraph.declarations().size()) {
            throw new IllegalArgumentException("typed graph does not cover every resolved declaration");
        }
        for (TypedDeclaration declaration : declarations) {
            ResolvedDeclaration resolved = resolvedGraph.declaration(declaration.id())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "typed declaration is absent from the resolved graph: " + declaration.id()));
            declaration.contract().ifPresent(contract -> {
                if (contract.valueType().hasQualifier(TypeQualifier.MUT)) {
                    throw new IllegalArgumentException("typed binding contract duplicates @mut");
                }
            });
            if (resolved.kind() != DeclarationKind.IMPORT_MODULE
                    && declaration.contract().isEmpty()) {
                throw new IllegalArgumentException(
                        "value declaration has no complete typed contract: " + declaration.id());
            }
            if (declaration.initializer().isPresent()
                    && resolved.kind() != DeclarationKind.LET && resolved.kind() != DeclarationKind.MEMBER) {
                throw new IllegalArgumentException("non-let declaration has an initializer");
            }
            declaration.initializerLambda().ifPresent(lambda -> {
                if (!lambdasById.containsKey(lambda)) {
                    throw new IllegalArgumentException("initializer lambda is absent from typed graph");
                }
                if (declaration.initializer().isEmpty()) {
                    throw new IllegalArgumentException("initializer lambda has no initializer expression");
                }
            });
        }

        if (references.size() != resolvedGraph.references().size()) {
            throw new IllegalArgumentException("typed graph does not cover every resolved reference");
        }
        for (TypedReference reference : references) {
            ResolvedReference resolved = resolvedGraph.reference(reference.id())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "typed reference is absent from the resolved graph: " + reference.id()));
            reference.targetDeclaration().ifPresent(target -> {
                if (resolvedGraph.declaration(target).isEmpty()
                        && !retainedProducerContains(target, reference.moduleId())) {
                    requireDeclaration(target);
                }
            });
            reference.targetModule().ifPresent(target -> {
                if (resolvedGraph.module(target).isEmpty()
                        && (!reference.targetModule().equals(resolved.targetModule())
                        || resolvedGraph.retainedModules().module(target).isEmpty())) {
                    throw new IllegalArgumentException("typed reference targets an absent module");
                }
            });
            reference.capture().ifPresent(target -> requireCapture(target));
            if (resolved.kind() != reference.kind()) {
                throw new IllegalArgumentException("typed reference kind changed during type checking");
            }
            if (resolved.kind() != ReferenceKind.MODULE_NAMESPACE && reference.type().isEmpty()) {
                throw new IllegalArgumentException("value reference has no exact type");
            }
        }

        if (lambdas.size() != resolvedGraph.lambdas().size()) {
            throw new IllegalArgumentException("typed graph does not cover every resolved lambda");
        }
        for (TypedLambda lambda : lambdas) {
            ResolvedLambda resolved = resolvedGraph.lambda(lambda.id())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "typed lambda is absent from the resolved graph: " + lambda.id()));
            if (resolved.signature().isEmpty()
                    || !resolved.signature().orElseThrow().equals(lambda.signature())) {
                throw new IllegalArgumentException("typed lambda signature does not match resolution");
            }
            for (DeclarationId parameter : lambda.parameterIds()) {
                requireDeclaration(parameter);
            }
            for (CaptureId capture : lambda.captures()) {
                requireCapture(capture);
            }
        }
        for (Map.Entry<DeclarationId, BindingContract> entry : contractsByDeclaration.entrySet()) {
            requireDeclaration(entry.getKey());
            Objects.requireNonNull(entry.getValue(), "typed contract");
        }
        for (ResolvedDeclaration declaration : resolvedGraph.declarations()) {
            if (declaration.kind() != DeclarationKind.IMPORT_MODULE
                    && !contractsByDeclaration.containsKey(declaration.id())) {
                throw new IllegalArgumentException("typed contract index is incomplete");
            }
        }
        for (TypedConversion conversion : conversions) {
            boolean knownSource = resolvedGraph.moduleGraph().moduleIds().stream()
                    .anyMatch(id -> id.sourceId().equals(conversion.span().sourceId()));
            if (!knownSource) {
                throw new IllegalArgumentException("conversion belongs to an absent source");
            }
        }
        for (TypedExpression expression : expressions) {
            validateExpression(expression);
        }
        for (TypedExpression expression : expressions) {
            expression.conversion().ifPresent(conversion -> {
                if (!conversions.contains(conversion)) {
                    throw new IllegalArgumentException("typed expression contains an unrecorded conversion");
                }
            });
        }
        for (TypedConversion conversion : conversions) {
            boolean published = expressions.stream()
                    .map(TypedExpression::conversion)
                    .anyMatch(value -> value.filter(conversion::equals).isPresent());
            if (!published) {
                throw new IllegalArgumentException("typed conversion record has no conversion expression");
            }
        }

        List<TypedMutation> resolvedMutations = orderedMutations(
                resolvedGraph.mutations().stream().map(TypedMutation::from).toList());
        if (!mutations.equals(resolvedMutations)) {
            throw new IllegalArgumentException("typed mutation index does not match resolution");
        }
        Set<ModuleId> resolvedModules = resolvedGraph.modules().stream()
                .map(ResolvedModule::moduleId).collect(java.util.stream.Collectors.toSet());
        if (!initializationPlan.modules().equals(resolvedModules.stream().sorted().toList())) {
            throw new IllegalArgumentException("initialization plan does not cover the resolved modules");
        }
        List<TypedFailureSite> expectedFailureSites = TypedFailureSite.fromExpressions(expressions);
        if (!failureSites.equals(expectedFailureSites)) {
            throw new IllegalArgumentException("typed failure-site index is not source complete");
        }
    }

    private void validateExpression(TypedExpression expression) {
        if (expression.span() == null || expression.type() == null) {
            throw new IllegalArgumentException("typed expressions require a span and exact type");
        }
        expression.link().ifPresent(link -> {
            link.referenceId().ifPresent(id -> {
                if (!referencesById.containsKey(id)) {
                    throw new IllegalArgumentException("typed expression has an unknown reference link");
                }
            });
            link.declarationId().ifPresent(id -> {
                Optional<ResolvedReference> reference = link.referenceId()
                        .flatMap(resolvedGraph::reference);
                if (resolvedGraph.declaration(id).isEmpty()
                        && (reference.isEmpty()
                        || !retainedProducerContains(id, reference.orElseThrow().moduleId()))) {
                    requireDeclaration(id);
                }
            });
            link.moduleId().ifPresent(module -> {
                if (!modulesById.containsKey(module)
                        && resolvedGraph.retainedModules().module(module).isEmpty()) {
                    throw new IllegalArgumentException("typed expression has an unknown module link");
                }
            });
        });
        expression.conversion().ifPresent(conversion -> {
            if (!conversion.targetType().equals(expression.type())
                    || expression.children().size() != 1
                    || !conversion.sourceType().equals(expression.children().getFirst().type())) {
                throw new IllegalArgumentException("typed conversion source/target and expression type disagree");
            }
        });
        expression.lambdaId().ifPresent(id -> {
            if (!lambdasById.containsKey(id)) {
                throw new IllegalArgumentException("typed expression has an unknown lambda link");
            }
        });
        expression.declarationId().ifPresent(id -> {
            if (!retainedProducerContains(id, ModuleId.fromSourceId(expression.span().sourceId()))) {
                requireDeclaration(id);
            }
        });
        expression.predicateBinding().ifPresent(this::requireDeclaration);
        if ((expression.kind() == TypedExpressionKind.NOMINAL_DECLARATION)
                != expression.nominalInitialization().isPresent()) {
            throw new IllegalArgumentException("nominal initialization proof is missing or foreign");
        }
        if ((expression.kind() == TypedExpressionKind.MATCH
                || expression.kind() == TypedExpressionKind.COND)
                != expression.match().isPresent()) {
            throw new IllegalArgumentException("typed match metadata is missing or foreign");
        }
        for (CaptureId capture : expression.captureIds()) {
            requireCapture(capture);
        }
        for (TypedExpression child : expression.children()) {
            Objects.requireNonNull(child, "typed expression child");
            if (!child.span().sourceId().equals(expression.span().sourceId())) {
                throw new IllegalArgumentException("typed expression child belongs to another source");
            }
        }
        switch (expression.kind()) {
            case LITERAL -> {
                if (expression.literal().isEmpty()) {
                    throw new IllegalArgumentException("typed literal has no constant value");
                }
            }
            case REFERENCE -> {
                if (expression.link().isEmpty() || expression.link().orElseThrow().referenceId().isEmpty()) {
                    throw new IllegalArgumentException("typed reference has no resolved link");
                }
            }
            case DECLARATION -> {
                if (expression.declarationId().isEmpty() || expression.children().size() != 1) {
                    throw new IllegalArgumentException("typed declaration operation is incomplete");
                }
            }
            case REBINDING -> {
                if (expression.declarationId().isEmpty() || expression.children().size() != 2) {
                    throw new IllegalArgumentException("typed rebinding operation is incomplete");
                }
            }
            case BLOCK -> {
                if (expression.scopeId().isEmpty()) {
                    throw new IllegalArgumentException("typed block has no resolved scope");
                }
            }
            case CONDITIONAL -> {
                if (expression.children().size() != 2 && expression.children().size() != 3) {
                    throw new IllegalArgumentException("typed conditional has an invalid branch count");
                }
            }
            case COALESCE -> {
                if (expression.children().size() != 2) {
                    throw new IllegalArgumentException("typed coalesce has an invalid child count");
                }
            }
            case MATCH, COND -> {
                TypedMatch match = expression.match().orElseThrow();
                if (match.childCount() != expression.children().size()) {
                    throw new IllegalArgumentException("typed match child roles are incomplete");
                }
                if ((expression.kind() == TypedExpressionKind.COND)
                        != (match.mode() == TypedMatch.MatchMode.CONDITIONAL)) {
                    throw new IllegalArgumentException("typed lazy-arm metadata mode does not match its source form");
                }
                if (match.subjectChild().isPresent()) {
                    TypedExpression subject = expression.children().get(match.subjectChild().getAsInt());
                    for (TypedMatch.Arm arm : match.arms()) {
                        if (arm.patternChild().isPresent()) {
                            TypedExpression pattern = expression.children().get(
                                    arm.patternChild().getAsInt());
                            if (!pattern.type().equals(arm.comparisonType().orElseThrow())
                                    || !TypeRules.canImplicitlyConvert(
                                    subject.type(), arm.comparisonType().orElseThrow())) {
                                throw new IllegalArgumentException("typed match equality contract is inconsistent");
                            }
                        }
                    }
                }
                for (TypedMatch.Arm arm : match.arms()) {
                    if (!containsSpan(expression.span(), arm.span())) {
                        throw new IllegalArgumentException("typed match arm span escapes its match expression");
                    }
                    for (Integer childIndex : arm.childIndexes()) {
                        if (!containsSpan(arm.span(), expression.children().get(childIndex).span())) {
                            throw new IllegalArgumentException("typed match arm span does not enclose its child");
                        }
                    }
                    if (!expression.children().get(arm.resultChild()).type().equals(expression.type())) {
                        throw new IllegalArgumentException("typed match result does not end at its result type");
                    }
                }
            }
            case LAMBDA -> {
                if (expression.lambdaId().isEmpty()
                        || expression.signature().isEmpty()
                        || expression.children().size() != 1) {
                    throw new IllegalArgumentException("typed lambda is incomplete");
                }
            }
            case CALLABLE_CALL -> {
                if (expression.children().isEmpty()) {
                    throw new IllegalArgumentException("typed callable call has no target");
                }
            }
            case DIRECT_CALL, NAMESPACE_DIRECT_CALL -> {
                if (expression.link().isEmpty()
                        || expression.link().orElseThrow().referenceId().isEmpty()) {
                    throw new IllegalArgumentException("typed direct call has no resolved link");
                }
            }
            case MEMBER_ACCESS -> {
                if (expression.children().size() != 1
                        || (expression.memberName().isEmpty() && expression.tupleIndex().isEmpty())) {
                    throw new IllegalArgumentException("typed member access is incomplete");
                }
            }
            case ARRAY_LITERAL -> {
                if (expression.type().isNilable()
                        || !(expression.type().withoutQualifiers() instanceof ArrayType)) {
                    throw new IllegalArgumentException("typed array literal is incomplete");
                }
            }
            case RANGE -> {
                if (!(expression.type() instanceof io.mindspice.lyra.compiler.types.RangeType range)
                        || expression.children().size() != 3
                        || expression.children().stream().anyMatch(child -> !child.type().equals(range.elementType()))
                        || expression.operator().filter(value -> value.equals("..") || value.equals("...")).isEmpty()) {
                    throw new IllegalArgumentException("typed range construction is incomplete");
                }
            }
            case ITER, WHILE -> {
                if (!CallbackLoop.valid(expression.kind(), expression.type(),
                        expression.children().stream().map(TypedExpression::type).toList())) {
                    throw new IllegalArgumentException("typed callback loop has an invalid contract");
                }
            }
            case TUPLE_LITERAL -> {
                if (expression.type().isNilable()
                        || !(expression.type().withoutQualifiers() instanceof TupleType)
                        || expression.children().isEmpty()) {
                    throw new IllegalArgumentException("typed tuple literal is incomplete");
                }
            }
            case INDEX_ACCESS -> {
                if (expression.children().size() != 2) {
                    throw new IllegalArgumentException("typed index access is incomplete");
                }
            }
            case NAMESPACE_MEMBER_ACCESS -> {
                if (expression.link().isEmpty()
                        || expression.link().orElseThrow().referenceId().isEmpty()) {
                    throw new IllegalArgumentException("typed namespace access has no resolved link");
                }
            }
            case OPERATOR, SHORT_CIRCUIT -> {
                if (expression.operator().isEmpty()) {
                    throw new IllegalArgumentException("typed operator has no source spelling");
                }
            }
            case CONVERSION -> {
                if (expression.conversion().isEmpty() || expression.children().size() != 1) {
                    throw new IllegalArgumentException("typed conversion is incomplete");
                }
            }
            case NARROWING -> {
                if (expression.children().size() != 1) {
                    throw new IllegalArgumentException("typed narrowing is incomplete");
                }
            }
        }
    }

    private static boolean containsSpan(SourceSpan outer, SourceSpan inner) {
        return outer.sourceId().equals(inner.sourceId())
                && inner.startOffset() >= outer.startOffset()
                && inner.endOffset() <= outer.endOffset();
    }

    private boolean retainedProducerContains(DeclarationId declaration, ModuleId referringModule) {
        if (!resolvedGraph.isRetained(referringModule)) {
            return false;
        }
        return resolvedGraph.retainedModules().module(referringModule)
                .map(record -> record.producerGraph().declaration(declaration).isPresent())
                .orElse(false);
    }

    private void requireDeclaration(DeclarationId id) {
        if (resolvedGraph.declaration(id).isEmpty()) {
            throw new IllegalArgumentException("unknown declaration identity: " + id);
        }
    }

    private void requireCapture(CaptureId id) {
        if (resolvedGraph.capture(id).isEmpty()) {
            throw new IllegalArgumentException("unknown capture identity: " + id);
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof TypedSemanticGraph graph
                && resolvedGraph.equals(graph.resolvedGraph)
                && modules.equals(graph.modules)
                && declarations.equals(graph.declarations)
                && references.equals(graph.references)
                && lambdas.equals(graph.lambdas)
                && conversions.equals(graph.conversions)
                && expressions.equals(graph.expressions)
                && contractsByDeclaration.equals(graph.contractsByDeclaration)
                && expressionsBySpan.equals(graph.expressionsBySpan)
                && mutations.equals(graph.mutations)
                && flowFacts.equals(graph.flowFacts)
                && initializationPlan.equals(graph.initializationPlan)
                && failureSites.equals(graph.failureSites)
                && allocator().equals(graph.allocator());
    }

    @Override
    public int hashCode() {
        return Objects.hash(resolvedGraph, modules, declarations, references, lambdas,
                conversions, expressions, contractsByDeclaration, expressionsBySpan,
                mutations, flowFacts, initializationPlan, failureSites, allocator());
    }

    @Override
    public String toString() {
        return "TypedSemanticGraph[modules=" + modules.size()
                + ", declarations=" + declarations.size()
                + ", references=" + references.size()
                + ", lambdas=" + lambdas.size()
                + ", conversions=" + conversions.size()
                + ", dependencies=" + initializationPlan.dependencies().size() + "]";
    }

    private static List<TypedMutation> orderedMutations(List<TypedMutation> values) {
        Objects.requireNonNull(values, "mutations");
        ArrayList<TypedMutation> copy = new ArrayList<>();
        for (TypedMutation value : values) {
            copy.add(Objects.requireNonNull(value, "mutations must not contain null"));
        }
        copy.sort(Comparator.comparing(TypedMutation::moduleId)
                .thenComparingInt(value -> value.span().startOffset())
                .thenComparingInt(value -> value.span().endOffset())
                .thenComparing(value -> value.kind().name())
                .thenComparing(TypedMutation::rootDeclaration));
        return List.copyOf(copy);
    }

}
