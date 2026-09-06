package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.identity.IdentityAllocator;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.BindingContract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Package-owned immutable typed semantic input used between type checking and
 * final graph sealing.
 *
 * <p>The core has no initialization plan or flow facts.  Consequently it
 * cannot be mistaken for a published phase artifact, and canonical flow can
 * consume it exactly once before {@link TypedSemanticGraph#seal} publishes the
 * complete graph.</p>
 */
final class TypedSemanticCore implements TypedSemanticInput {
    private final ResolvedSemanticGraph resolvedGraph;
    private final List<TypedModule> modules;
    private final List<TypedDeclaration> declarations;
    private final List<TypedReference> references;
    private final List<TypedLambda> lambdas;
    private final List<TypedConversion> conversions;
    private final List<TypedExpression> expressions;
    private final Map<DeclarationId, BindingContract> contractsByDeclaration;
    private final Map<SourceSpan, List<TypedExpression>> expressionsBySpan;
    private final List<TypedMutation> mutations;
    private final List<TypedFailureSite> failureSites;
    private final Map<ModuleId, TypedModule> modulesById;
    private final Map<DeclarationId, TypedDeclaration> declarationsById;
    private final Map<ReferenceId, TypedReference> referencesById;
    private final Map<LambdaId, TypedLambda> lambdasById;
    private final Map<TypedExpression, FlowSiteId> flowSitesByExpression;
    private final Map<ReferenceId, FlowSiteId> flowSitesByReference;
    private final Map<CaptureId, FlowSiteId> flowSitesByCapture;
    private final Map<FlowSiteId, SourceSpan> spansByFlowSite;
    private final Map<FlowSiteId, ScopeId> scopesByFlowSite;
    private final IdentityAllocator allocator;
    private final Optional<SemanticFlowAnalyzer.Provenance> flowProvenance;

    TypedSemanticCore(
            ResolvedSemanticGraph resolvedGraph,
            List<TypedModule> modules,
            List<TypedDeclaration> declarations,
            List<TypedReference> references,
            List<TypedLambda> lambdas,
            List<TypedConversion> conversions,
            List<TypedExpression> expressions,
            Map<DeclarationId, BindingContract> contractsByDeclaration,
            Map<SourceSpan, List<TypedExpression>> expressionsBySpan,
            List<TypedMutation> mutations,
            List<TypedFailureSite> failureSites) {
        this(resolvedGraph, modules, declarations, references, lambdas, conversions,
                expressions, contractsByDeclaration, expressionsBySpan, mutations,
                failureSites, Optional.empty());
    }

    TypedSemanticCore(
            ResolvedSemanticGraph resolvedGraph,
            List<TypedModule> modules,
            List<TypedDeclaration> declarations,
            List<TypedReference> references,
            List<TypedLambda> lambdas,
            List<TypedConversion> conversions,
            List<TypedExpression> expressions,
            Map<DeclarationId, BindingContract> contractsByDeclaration,
            Map<SourceSpan, List<TypedExpression>> expressionsBySpan,
            List<TypedMutation> mutations,
            List<TypedFailureSite> failureSites,
            Optional<SemanticFlowAnalyzer.Provenance> flowProvenance) {
        this.resolvedGraph = Objects.requireNonNull(resolvedGraph, "resolvedGraph");
        this.modules = ordered(modules, Comparator
                .comparing((TypedModule value) -> value.moduleId().value())
                .thenComparing(value -> value.moduleId().isUri() ? 1 : 0), "modules");
        this.declarations = ordered(declarations, Comparator.comparing(TypedDeclaration::id),
                "declarations");
        this.references = ordered(references, Comparator.comparing(TypedReference::id),
                "references");
        this.lambdas = ordered(lambdas, Comparator.comparing(TypedLambda::id), "lambdas");
        this.conversions = ordered(conversions, TypedSemanticCore::compareConversions,
                "conversions");
        this.expressions = ordered(expressions, TypedSemanticCore::compareExpressions,
                "expressions");
        this.contractsByDeclaration = copyMap(contractsByDeclaration, "contractsByDeclaration");
        this.expressionsBySpan = copyExpressionMap(expressionsBySpan);
        this.mutations = orderedMutations(mutations);
        this.failureSites = copy(failureSites, "failureSites");

        this.modulesById = indexModules(this.modules);
        this.declarationsById = indexDeclarations(this.declarations);
        this.referencesById = indexReferences(this.references);
        this.lambdasById = indexLambdas(this.lambdas);
        FlowSiteIndex flowSites = indexFlowSites(this.modules, resolvedGraph.allocator());
        this.flowSitesByExpression = flowSites.byExpression();
        this.flowSitesByReference = flowSites.byReference();
        this.flowSitesByCapture = flowSites.byCapture();
        this.spansByFlowSite = flowSites.spansById();
        this.scopesByFlowSite = flowSites.scopesById();
        this.allocator = flowSites.allocator();
        if (this.flowSitesByExpression.size() != this.expressions.size()) {
            throw new IllegalArgumentException(
                    "flow-site index does not cover exactly every published expression");
        }
        this.flowProvenance = Objects.requireNonNull(flowProvenance, "flowProvenance");
    }

    @Override
    public ResolvedSemanticGraph resolvedGraph() {
        return resolvedGraph;
    }

    @Override
    public IdentityAllocator allocator() {
        return allocator;
    }

    @Override
    public List<TypedModule> modules() {
        return modules;
    }

    @Override
    public Optional<TypedModule> module(ModuleId moduleId) {
        return Optional.ofNullable(modulesById.get(Objects.requireNonNull(moduleId, "moduleId")));
    }

    @Override
    public List<TypedDeclaration> declarations() {
        return declarations;
    }

    @Override
    public Optional<TypedDeclaration> declaration(DeclarationId declarationId) {
        return Optional.ofNullable(declarationsById.get(
                Objects.requireNonNull(declarationId, "declarationId")));
    }

    @Override
    public List<TypedReference> references() {
        return references;
    }

    @Override
    public Optional<TypedReference> reference(ReferenceId referenceId) {
        return Optional.ofNullable(referencesById.get(
                Objects.requireNonNull(referenceId, "referenceId")));
    }

    @Override
    public List<TypedLambda> lambdas() {
        return lambdas;
    }

    @Override
    public Optional<TypedLambda> lambda(LambdaId lambdaId) {
        return Optional.ofNullable(lambdasById.get(Objects.requireNonNull(lambdaId, "lambdaId")));
    }

    @Override
    public List<TypedExpression> expressions() {
        return expressions;
    }

    @Override
    public FlowSiteId flowSiteId(TypedExpression expression) {
        FlowSiteId site = flowSitesByExpression.get(
                Objects.requireNonNull(expression, "expression"));
        if (site == null) {
            throw new IllegalArgumentException(
                    "typed expression is absent from the canonical flow-site index");
        }
        return site;
    }

    @Override
    public FlowSiteId flowSiteId(ReferenceId referenceId) {
        FlowSiteId site = flowSitesByReference.get(
                Objects.requireNonNull(referenceId, "referenceId"));
        if (site == null) {
            throw new IllegalArgumentException("reference has no canonical flow site");
        }
        return site;
    }

    @Override
    public FlowSiteId flowSiteId(CaptureId captureId) {
        FlowSiteId site = flowSitesByCapture.get(
                Objects.requireNonNull(captureId, "captureId"));
        if (site == null) {
            throw new IllegalArgumentException("capture has no canonical flow site");
        }
        return site;
    }

    @Override
    public ScopeId flowScopeId(TypedExpression expression) {
        FlowSiteId site = flowSiteId(expression);
        ScopeId scope = scopesByFlowSite.get(site);
        if (scope == null) {
            throw new IllegalArgumentException(
                    "typed expression has no canonical lexical owner scope");
        }
        return scope;
    }

    boolean ownsFlowSite(FlowSiteId site, SourceSpan span) {
        return Objects.requireNonNull(span, "span").equals(
                spansByFlowSite.get(Objects.requireNonNull(site, "site")));
    }

    @Override
    public Map<DeclarationId, BindingContract> contractsByDeclaration() {
        return contractsByDeclaration;
    }

    @Override
    public Optional<BindingContract> contract(DeclarationId declarationId) {
        return Optional.ofNullable(contractsByDeclaration.get(
                Objects.requireNonNull(declarationId, "declarationId")));
    }

    @Override
    public Map<SourceSpan, List<TypedExpression>> expressionsBySpan() {
        return expressionsBySpan;
    }

    @Override
    public List<TypedMutation> mutations() {
        return mutations;
    }

    @Override
    public List<TypedFailureSite> failureSites() {
        return failureSites;
    }

    List<TypedConversion> conversions() {
        return conversions;
    }

    Map<ModuleId, TypedModule> modulesById() {
        return modulesById;
    }

    Map<DeclarationId, TypedDeclaration> declarationsById() {
        return declarationsById;
    }

    Map<ReferenceId, TypedReference> referencesById() {
        return referencesById;
    }

    Map<LambdaId, TypedLambda> lambdasById() {
        return lambdasById;
    }

    Optional<SemanticFlowAnalyzer.Provenance> flowProvenance() {
        return flowProvenance;
    }

    TypedSemanticCore withFlowProvenance(SemanticFlowAnalyzer.Provenance provenance) {
        if (flowProvenance.isPresent()) {
            throw new IllegalStateException("typed semantic core is already flow-certified");
        }
        return new TypedSemanticCore(
                resolvedGraph, modules, declarations, references, lambdas, conversions,
                expressions, contractsByDeclaration, expressionsBySpan, mutations,
                failureSites, Optional.of(Objects.requireNonNull(provenance, "provenance")));
    }

    private static int compareExpressions(TypedExpression left, TypedExpression right) {
        return Comparator.comparing((TypedExpression value) -> value.span().sourceId().value())
                .thenComparingInt(value -> value.span().startOffset())
                .thenComparingInt(value -> value.span().endOffset())
                .thenComparing(value -> value.kind().name())
                .thenComparing(value -> value.type().canonicalSpelling())
                .compare(left, right);
    }

    private static int compareConversions(TypedConversion left, TypedConversion right) {
        return Comparator.comparing((TypedConversion value) -> value.span().sourceId().value())
                .thenComparingInt(value -> value.span().startOffset())
                .thenComparingInt(value -> value.span().endOffset())
                .thenComparing(value -> value.sourceType().canonicalSpelling())
                .thenComparing(value -> value.targetType().canonicalSpelling())
                .thenComparing(value -> value.kind().name())
                .thenComparing(value -> value.step().name())
                .compare(left, right);
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

    private static <T> List<T> ordered(List<T> values, Comparator<T> comparator, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<T> copy = new ArrayList<>();
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " must not contain null"));
        }
        copy.sort(comparator);
        return List.copyOf(copy);
    }

    private static <T> List<T> copy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<T> copy = new ArrayList<>();
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " must not contain null"));
        }
        return List.copyOf(copy);
    }

    private static <K, V> Map<K, V> copyMap(Map<K, V> values, String name) {
        Objects.requireNonNull(values, name);
        LinkedHashMap<K, V> copy = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : values.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), name + " key"),
                    Objects.requireNonNull(entry.getValue(), name + " value"));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<SourceSpan, List<TypedExpression>> copyExpressionMap(
            Map<SourceSpan, List<TypedExpression>> values) {
        Objects.requireNonNull(values, "expressionsBySpan");
        List<Map.Entry<SourceSpan, List<TypedExpression>>> entries = new ArrayList<>(values.entrySet());
        entries.sort(Map.Entry.comparingByKey(Comparator
                .comparing((SourceSpan span) -> span.sourceId().value())
                .thenComparingInt(SourceSpan::startOffset)
                .thenComparingInt(SourceSpan::endOffset)));
        LinkedHashMap<SourceSpan, List<TypedExpression>> copy = new LinkedHashMap<>();
        for (Map.Entry<SourceSpan, List<TypedExpression>> entry : entries) {
            SourceSpan span = Objects.requireNonNull(entry.getKey(), "expression span");
            List<TypedExpression> expressions = Objects.requireNonNull(
                    entry.getValue(), "expressions at span");
            ArrayList<TypedExpression> expressionCopy = new ArrayList<>();
            for (TypedExpression expression : expressions) {
                expressionCopy.add(Objects.requireNonNull(expression,
                        "expression at span"));
            }
            copy.put(span, List.copyOf(expressionCopy));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<ModuleId, TypedModule> indexModules(List<TypedModule> values) {
        LinkedHashMap<ModuleId, TypedModule> result = new LinkedHashMap<>();
        for (TypedModule value : values) {
            if (result.put(value.moduleId(), value) != null) {
                throw new IllegalArgumentException("duplicate typed module");
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<DeclarationId, TypedDeclaration> indexDeclarations(
            List<TypedDeclaration> values) {
        LinkedHashMap<DeclarationId, TypedDeclaration> result = new LinkedHashMap<>();
        for (TypedDeclaration value : values) {
            if (result.put(value.id(), value) != null) {
                throw new IllegalArgumentException("duplicate typed declaration");
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<ReferenceId, TypedReference> indexReferences(List<TypedReference> values) {
        LinkedHashMap<ReferenceId, TypedReference> result = new LinkedHashMap<>();
        for (TypedReference value : values) {
            if (result.put(value.id(), value) != null) {
                throw new IllegalArgumentException("duplicate typed reference");
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<LambdaId, TypedLambda> indexLambdas(List<TypedLambda> values) {
        LinkedHashMap<LambdaId, TypedLambda> result = new LinkedHashMap<>();
        for (TypedLambda value : values) {
            if (result.put(value.id(), value) != null) {
                throw new IllegalArgumentException("duplicate typed lambda");
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private FlowSiteIndex indexFlowSites(
            List<TypedModule> modules, IdentityAllocator initialAllocator) {
        IdentityHashMap<TypedExpression, FlowSiteId> byExpression = new IdentityHashMap<>();
        LinkedHashMap<ReferenceId, FlowSiteId> byReference = new LinkedHashMap<>();
        LinkedHashMap<CaptureId, FlowSiteId> byCapture = new LinkedHashMap<>();
        LinkedHashMap<FlowSiteId, SourceSpan> spansById = new LinkedHashMap<>();
        LinkedHashMap<FlowSiteId, ScopeId> scopesById = new LinkedHashMap<>();
        IdentityAllocator[] next = {
                Objects.requireNonNull(initialAllocator, "initialAllocator")};
        for (TypedModule module : modules) {
            for (TypedExpression form : module.forms()) {
                indexFlowSite(
                        form, module.rootScope(), byExpression, spansById, scopesById, next);
            }
        }
        for (ResolvedReference reference : resolvedGraph.references()) {
            FlowSiteId id = allocateFlowSite(next);
            byReference.put(reference.id(), id);
            spansById.put(id, reference.span());
            scopesById.put(id, reference.scopeId());
        }
        for (ResolvedCapture capture : resolvedGraph.captures()) {
            FlowSiteId id = allocateFlowSite(next);
            byCapture.put(capture.id(), id);
            spansById.put(id, capture.span());
            ScopeId scope = resolvedGraph.lambda(capture.lambdaId()).orElseThrow().scopeId();
            scopesById.put(id, scope);
        }
        return new FlowSiteIndex(
                Collections.unmodifiableMap(byExpression),
                Collections.unmodifiableMap(byReference),
                Collections.unmodifiableMap(byCapture),
                Collections.unmodifiableMap(spansById),
                Collections.unmodifiableMap(scopesById),
                next[0]);
    }

    private static FlowSiteId allocateFlowSite(IdentityAllocator[] allocator) {
        IdentityAllocator.Allocation<FlowSiteId> allocation = allocator[0].allocateFlowSite();
        allocator[0] = allocation.next();
        return allocation.id();
    }

    private void indexFlowSite(
            TypedExpression expression,
            ScopeId ownerScope,
            IdentityHashMap<TypedExpression, FlowSiteId> byExpression,
            Map<FlowSiteId, SourceSpan> spansById,
            Map<FlowSiteId, ScopeId> scopesById,
            IdentityAllocator[] next) {
        if (byExpression.containsKey(expression)) {
            throw new IllegalArgumentException(
                    "typed expression tree contains a shared or cyclic flow site");
        }
        ResolvedScope owner = resolvedGraph.scopeTree().require(ownerScope);
        if (!owner.moduleId().sourceId().equals(expression.span().sourceId())) {
            throw new IllegalArgumentException(
                    "typed expression flow site belongs to another owner scope");
        }
        FlowSiteId id = allocateFlowSite(next);
        byExpression.put(expression, id);
        spansById.put(id, expression.span());
        scopesById.put(id, ownerScope);

        if (expression.kind() == TypedExpressionKind.LAMBDA) {
            LambdaId lambdaId = expression.lambdaId().orElseThrow(() ->
                    new IllegalArgumentException("lambda flow site has no lambda identity"));
            TypedLambda lambda = lambdasById.get(lambdaId);
            if (lambda == null || expression.children().size() != 1) {
                throw new IllegalArgumentException("lambda flow site is incomplete");
            }
            indexFlowSite(expression.children().getFirst(), lambda.scopeId(),
                    byExpression, spansById, scopesById, next);
            return;
        }
        if (expression.kind() == TypedExpressionKind.BLOCK) {
            ScopeId blockScope = expression.scopeId().orElseThrow(() ->
                    new IllegalArgumentException("block flow site has no lexical scope"));
            for (TypedExpression child : expression.children()) {
                indexFlowSite(child, blockScope, byExpression, spansById, scopesById, next);
            }
            return;
        }
        if (expression.kind() == TypedExpressionKind.CONDITIONAL
                && expression.predicateBinding().isPresent()) {
            indexFlowSite(expression.children().getFirst(), ownerScope,
                    byExpression, spansById, scopesById, next);
            ScopeId branchScope = resolvedGraph.declaration(
                            expression.predicateBinding().orElseThrow())
                    .orElseThrow().scopeId();
            indexFlowSite(expression.children().get(1), branchScope,
                    byExpression, spansById, scopesById, next);
            for (TypedExpression child : expression.children().subList(
                    2, expression.children().size())) {
                indexFlowSite(child, ownerScope,
                        byExpression, spansById, scopesById, next);
            }
            return;
        }
        for (TypedExpression child : expression.children()) {
            indexFlowSite(child, ownerScope, byExpression, spansById, scopesById, next);
        }
    }

    private record FlowSiteIndex(
            Map<TypedExpression, FlowSiteId> byExpression,
            Map<ReferenceId, FlowSiteId> byReference,
            Map<CaptureId, FlowSiteId> byCapture,
            Map<FlowSiteId, SourceSpan> spansById,
            Map<FlowSiteId, ScopeId> scopesById,
            IdentityAllocator allocator) {
    }
}
