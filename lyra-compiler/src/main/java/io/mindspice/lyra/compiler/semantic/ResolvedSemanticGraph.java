package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.identity.IdentityAllocator;
import io.mindspice.lyra.compiler.api.SessionFlowCertificate;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.ModuleId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Complete immutable result of declaration collection and semantic resolution.
 * Syntax nodes remain in the module graph; this artifact contains only IDs,
 * contracts, links, and source spans derived from them.
 */
public final class ResolvedSemanticGraph implements ImmutablePhaseArtifact {
    private final ModuleGraph moduleGraph;
    private final ScopeTree scopeTree;
    private final List<ResolvedModule> modules;
    private final List<ResolvedDeclaration> declarations;
    private final List<ResolvedReference> references;
    private final List<ResolvedLambda> lambdas;
    private final List<ResolvedImportBinding> imports;
    private final List<ResolvedExport> exports;
    private final List<ResolvedCapture> captures;
    private final List<ResolvedMutation> mutations;
    private final List<SyntaxLink> syntaxLinks;
    private final FunctionSignatureLinkage functionLinkage;
    private final Map<ModuleId, ResolvedModule> modulesById;
    private final Map<DeclarationId, ResolvedDeclaration> declarationsById;
    private final Map<ReferenceId, ResolvedReference> referencesById;
    private final Map<LambdaId, ResolvedLambda> lambdasById;
    private final Map<CaptureId, ResolvedCapture> capturesById;
    private final Optional<ResolvedReferenceTopology> referenceTopology;
    private final Optional<SessionFlowCertificate> sessionFlowCertificate;
    private final boolean sessionGraph;
    private final IdentityAllocator allocator;

    public ResolvedSemanticGraph(
            ModuleGraph moduleGraph,
            ScopeTree scopeTree,
            List<ResolvedModule> modules,
            List<ResolvedDeclaration> declarations,
            List<ResolvedReference> references,
            List<ResolvedLambda> lambdas,
            List<ResolvedImportBinding> imports,
            List<ResolvedExport> exports,
            List<ResolvedCapture> captures,
            List<ResolvedMutation> mutations,
            List<SyntaxLink> syntaxLinks,
            FunctionSignatureLinkage functionLinkage) {
        this(moduleGraph, scopeTree, modules, declarations, references, lambdas,
                imports, exports, captures, mutations, syntaxLinks, functionLinkage,
                Optional.empty(), Optional.empty(), IdentityAllocator.initial(), false);
    }

    static ResolvedSemanticGraph publish(
            ModuleGraph moduleGraph,
            ScopeTree scopeTree,
            List<ResolvedModule> modules,
            List<ResolvedDeclaration> declarations,
            List<ResolvedReference> references,
            List<ResolvedLambda> lambdas,
            List<ResolvedImportBinding> imports,
            List<ResolvedExport> exports,
            List<ResolvedCapture> captures,
            List<ResolvedMutation> mutations,
            List<SyntaxLink> syntaxLinks,
            FunctionSignatureLinkage functionLinkage,
            ResolvedReferenceTopology referenceTopology) {
        return publish(moduleGraph, scopeTree, modules, declarations, references, lambdas,
                imports, exports, captures, mutations, syntaxLinks, functionLinkage,
                referenceTopology, Optional.empty(), IdentityAllocator.initial(), false);
    }

    static ResolvedSemanticGraph publish(
            ModuleGraph moduleGraph,
            ScopeTree scopeTree,
            List<ResolvedModule> modules,
            List<ResolvedDeclaration> declarations,
            List<ResolvedReference> references,
            List<ResolvedLambda> lambdas,
            List<ResolvedImportBinding> imports,
            List<ResolvedExport> exports,
            List<ResolvedCapture> captures,
            List<ResolvedMutation> mutations,
            List<SyntaxLink> syntaxLinks,
            FunctionSignatureLinkage functionLinkage,
            ResolvedReferenceTopology referenceTopology,
            IdentityAllocator allocator) {
        return publish(moduleGraph, scopeTree, modules, declarations, references, lambdas,
                imports, exports, captures, mutations, syntaxLinks, functionLinkage,
                referenceTopology, Optional.empty(), allocator, false);
    }

    static ResolvedSemanticGraph publish(
            ModuleGraph moduleGraph,
            ScopeTree scopeTree,
            List<ResolvedModule> modules,
            List<ResolvedDeclaration> declarations,
            List<ResolvedReference> references,
            List<ResolvedLambda> lambdas,
            List<ResolvedImportBinding> imports,
            List<ResolvedExport> exports,
            List<ResolvedCapture> captures,
            List<ResolvedMutation> mutations,
            List<SyntaxLink> syntaxLinks,
            FunctionSignatureLinkage functionLinkage,
            ResolvedReferenceTopology referenceTopology,
            Optional<SessionFlowCertificate> sessionFlowCertificate,
            IdentityAllocator allocator) {
        return publish(moduleGraph, scopeTree, modules, declarations, references, lambdas,
                imports, exports, captures, mutations, syntaxLinks, functionLinkage,
                referenceTopology, sessionFlowCertificate, allocator, false);
    }

    static ResolvedSemanticGraph publish(
            ModuleGraph moduleGraph,
            ScopeTree scopeTree,
            List<ResolvedModule> modules,
            List<ResolvedDeclaration> declarations,
            List<ResolvedReference> references,
            List<ResolvedLambda> lambdas,
            List<ResolvedImportBinding> imports,
            List<ResolvedExport> exports,
            List<ResolvedCapture> captures,
            List<ResolvedMutation> mutations,
            List<SyntaxLink> syntaxLinks,
            FunctionSignatureLinkage functionLinkage,
            ResolvedReferenceTopology referenceTopology,
            Optional<SessionFlowCertificate> sessionFlowCertificate,
            IdentityAllocator allocator,
            boolean sessionGraph) {
        ResolvedReferenceTopology authority = Objects.requireNonNull(
                referenceTopology, "referenceTopology");
        ResolvedSemanticGraph graph = new ResolvedSemanticGraph(
                moduleGraph, scopeTree, modules, declarations, references, lambdas,
                imports, exports, captures, mutations, syntaxLinks, functionLinkage,
                Optional.of(authority), sessionFlowCertificate, allocator, sessionGraph);
        authority.bind(graph);
        return graph;
    }

    private ResolvedSemanticGraph(
            ModuleGraph moduleGraph,
            ScopeTree scopeTree,
            List<ResolvedModule> modules,
            List<ResolvedDeclaration> declarations,
            List<ResolvedReference> references,
            List<ResolvedLambda> lambdas,
            List<ResolvedImportBinding> imports,
            List<ResolvedExport> exports,
            List<ResolvedCapture> captures,
            List<ResolvedMutation> mutations,
            List<SyntaxLink> syntaxLinks,
            FunctionSignatureLinkage functionLinkage,
            Optional<ResolvedReferenceTopology> referenceTopology,
            Optional<SessionFlowCertificate> sessionFlowCertificate,
            IdentityAllocator allocator,
            boolean sessionGraph) {
        this.moduleGraph = Objects.requireNonNull(moduleGraph, "moduleGraph");
        this.scopeTree = Objects.requireNonNull(scopeTree, "scopeTree");
        this.modules = ordered(modules, Comparator.comparing(ResolvedModule::moduleId), "modules");
        this.declarations = ordered(declarations, Comparator.comparing(ResolvedDeclaration::id), "declarations");
        this.references = ordered(references, Comparator.comparing(ResolvedReference::id), "references");
        this.lambdas = ordered(lambdas, Comparator.comparing(ResolvedLambda::id), "lambdas");
        this.imports = copy(imports, "imports");
        this.exports = copy(exports, "exports");
        this.captures = ordered(captures, Comparator.comparing(ResolvedCapture::id), "captures");
        this.mutations = copy(mutations, "mutations");
        this.syntaxLinks = copy(syntaxLinks, "syntaxLinks");
        this.functionLinkage = Objects.requireNonNull(functionLinkage, "functionLinkage");
        this.referenceTopology = Objects.requireNonNull(
                referenceTopology, "referenceTopology");
        this.sessionFlowCertificate = Objects.requireNonNull(
                sessionFlowCertificate, "sessionFlowCertificate");
        this.sessionGraph = sessionGraph;
        this.allocator = Objects.requireNonNull(allocator, "allocator");

        this.modulesById = indexModules(this.modules);
        this.declarationsById = indexDeclarations(this.declarations);
        this.referencesById = indexReferences(this.references);
        this.lambdasById = indexLambdas(this.lambdas);
        this.capturesById = indexCaptures(this.captures);
        validateMembership();
    }

    /** Compatibility constructor for callers that do not yet consume mutation records. */
    public ResolvedSemanticGraph(
            ModuleGraph moduleGraph,
            ScopeTree scopeTree,
            List<ResolvedModule> modules,
            List<ResolvedDeclaration> declarations,
            List<ResolvedReference> references,
            List<ResolvedLambda> lambdas,
            List<ResolvedImportBinding> imports,
            List<ResolvedExport> exports,
            List<ResolvedCapture> captures,
            List<SyntaxLink> syntaxLinks,
            FunctionSignatureLinkage functionLinkage) {
        this(moduleGraph, scopeTree, modules, declarations, references, lambdas,
                imports, exports, captures, List.of(), syntaxLinks, functionLinkage);
    }

    public ModuleGraph moduleGraph() {
        return moduleGraph;
    }

    public ModuleGraph graph() {
        return moduleGraph;
    }

    public ScopeTree scopeTree() {
        return scopeTree;
    }

    public ScopeTree scopes() {
        return scopeTree;
    }

    public List<ResolvedModule> modules() {
        return modules;
    }

    public Optional<ResolvedModule> module(ModuleId moduleId) {
        return Optional.ofNullable(modulesById.get(Objects.requireNonNull(moduleId, "moduleId")));
    }

    public List<ResolvedDeclaration> declarations() {
        return declarations;
    }

    public Optional<ResolvedDeclaration> declaration(DeclarationId id) {
        return Optional.ofNullable(declarationsById.get(Objects.requireNonNull(id, "id")));
    }

    public List<ResolvedReference> references() {
        return references;
    }

    public Optional<ResolvedReference> reference(ReferenceId id) {
        return Optional.ofNullable(referencesById.get(Objects.requireNonNull(id, "id")));
    }

    public List<ResolvedLambda> lambdas() {
        return lambdas;
    }

    public Optional<ResolvedLambda> lambda(LambdaId id) {
        return Optional.ofNullable(lambdasById.get(Objects.requireNonNull(id, "id")));
    }

    public List<ResolvedImportBinding> imports() {
        return imports;
    }

    public List<ResolvedImportBinding> importBindings() {
        return imports;
    }

    public List<ResolvedExport> exports() {
        return exports;
    }

    public Optional<ResolvedExport> export(ModuleId moduleId, String name) {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(name, "name");
        return exports.stream().filter(value -> value.moduleId().equals(moduleId)
                && value.name().equals(name)).findFirst();
    }

    public List<ResolvedCapture> captures() {
        return captures;
    }

    public List<ResolvedMutation> mutations() {
        return mutations;
    }

    public Optional<ResolvedCapture> capture(CaptureId id) {
        return Optional.ofNullable(capturesById.get(Objects.requireNonNull(id, "id")));
    }

    public List<SyntaxLink> syntaxLinks() {
        return syntaxLinks;
    }

    public List<SyntaxLink> links() {
        return syntaxLinks;
    }

    public FunctionSignatureLinkage functionLinkage() {
        return functionLinkage;
    }

    public FunctionSignatureLinkage functionSignatures() {
        return functionLinkage;
    }

    /** Persistent allocator state after all identities in this graph were allocated. */
    public IdentityAllocator allocator() {
        return allocator;
    }

    /** Compiler-issued predecessor proof used for certified session linkage. */
    public Optional<SessionFlowCertificate> sessionFlowCertificate() {
        return sessionFlowCertificate;
    }

    /** True when this graph was resolved through the session compiler path. */
    public boolean isSessionGraph() {
        return sessionGraph;
    }

    Optional<ResolvedReferenceTopology> referenceTopology() {
        return referenceTopology;
    }

    void validateSourceAuthority() {
        referenceTopology.orElseThrow(() -> new IllegalArgumentException(
                        "resolved semantic graph has no source-authoritative topology"))
                .validateOwner(this);
    }

    private void validateMembership() {
        Set<ModuleId> graphModules = Set.copyOf(moduleGraph.moduleIds());
        if (!modulesById.keySet().equals(graphModules)) {
            throw new IllegalArgumentException("semantic graph does not cover exactly every module");
        }
        if (!scopeTree.moduleRoots().keySet().equals(graphModules)) {
            throw new IllegalArgumentException("scope roots do not cover exactly every module");
        }
        for (ResolvedModule module : modules) {
            ScopeId root = scopeTree.root(module.moduleId()).orElseThrow(() ->
                    new IllegalArgumentException("semantic module has no scope-tree root"));
            if (!module.rootScope().equals(root)) {
                throw new IllegalArgumentException(
                        "semantic module root disagrees with the scope tree");
            }
            requireUnique(module.declarations(), "duplicate declaration in semantic module");
            requireUnique(module.references(), "duplicate reference in semantic module");
            requireUnique(module.lambdas(), "duplicate lambda in semantic module");
        }

        Set<DeclarationId> scopedDeclarations = new HashSet<>();
        for (ResolvedScope scope : scopeTree.scopes()) {
            for (DeclarationId declarationId : scope.declarations()) {
                ResolvedDeclaration declaration = declarationsById.get(declarationId);
                if (declaration == null
                        || !declaration.scopeId().equals(scope.id())
                        || !declaration.moduleId().equals(scope.moduleId())) {
                    throw new IllegalArgumentException(
                            "scope declaration membership or ownership is inconsistent");
                }
                if (!scopedDeclarations.add(declarationId)) {
                    throw new IllegalArgumentException(
                            "declaration belongs to multiple lexical scopes");
                }
            }
        }
        if (!scopedDeclarations.equals(declarationsById.keySet())) {
            throw new IllegalArgumentException("scope tree does not cover every declaration");
        }

        for (ResolvedDeclaration declaration : declarations) {
            ResolvedScope scope = scopeTree.require(declaration.scopeId());
            if (!scope.moduleId().equals(declaration.moduleId())) {
                throw new IllegalArgumentException("declaration scope belongs to another module");
            }
            if (declaration.kind() == DeclarationKind.PARAMETER
                    && scope.kind() != ScopeKind.LAMBDA) {
                throw new IllegalArgumentException(
                        "parameter declaration does not belong to a lambda scope");
            }
            if (declaration.kind() == DeclarationKind.PREDICATE_BINDING
                    && scope.kind() != ScopeKind.CONDITIONAL_BRANCH) {
                throw new IllegalArgumentException(
                        "predicate binding does not belong to its conditional scope");
            }
            if ((declaration.kind() == DeclarationKind.IMPORT_MODULE
                    || declaration.kind() == DeclarationKind.IMPORT_VALUE
                    || declaration.kind() == DeclarationKind.INTRINSIC_EXPORT
                    || declaration.kind() == DeclarationKind.EXTERNAL)
                    && scope.kind() != ScopeKind.MODULE) {
                throw new IllegalArgumentException(
                        "module-owned declaration belongs to a nested scope");
            }
        }
        for (ResolvedReference reference : references) {
            ResolvedScope scope = scopeTree.require(reference.scopeId());
            if (!scope.moduleId().equals(reference.moduleId())
                    || !scope.ownerLambda().equals(reference.fromLambda())) {
                throw new IllegalArgumentException("reference scope ownership is inconsistent");
            }
            reference.targetDeclaration().ifPresent(target -> {
                if (!declarationsById.containsKey(target)) {
                    throw new IllegalArgumentException("reference target is absent from declarations");
                }
            });
            reference.targetModule().ifPresent(target -> {
                if (!moduleGraph.module(target).isPresent()) {
                    throw new IllegalArgumentException("reference module target is absent from graph");
                }
            });
        }
        Set<LambdaId> declarationOwnedLambdas = new HashSet<>();
        for (ResolvedDeclaration declaration : declarations) {
            declaration.initializerLambda().ifPresent(lambdaId -> {
                ResolvedLambda lambda = lambdasById.get(lambdaId);
                if (declaration.kind() != DeclarationKind.LET
                        || lambda == null
                        || !lambda.moduleId().equals(declaration.moduleId())
                        || lambda.ownerDeclaration().filter(declaration.id()::equals).isEmpty()
                        || !declarationOwnedLambdas.add(lambdaId)) {
                    throw new IllegalArgumentException(
                            "declaration initializer-lambda ownership is inconsistent");
                }
            });
        }

        Set<DeclarationId> lambdaParameters = new HashSet<>();
        Set<CaptureId> lambdaCaptures = new HashSet<>();
        Set<ScopeId> lambdaScopes = new HashSet<>();
        for (ResolvedLambda lambda : lambdas) {
            ResolvedScope scope = scopeTree.require(lambda.scopeId());
            if (scope.kind() != ScopeKind.LAMBDA
                    || !scope.moduleId().equals(lambda.moduleId())
                    || scope.ownerLambda().filter(lambda.id()::equals).isEmpty()
                    || !lambdaScopes.add(scope.id())) {
                throw new IllegalArgumentException("lambda scope ownership is inconsistent");
            }
            requireUnique(lambda.parameterIds(), "lambda contains duplicate parameters");
            for (DeclarationId parameterId : lambda.parameterIds()) {
                ResolvedDeclaration parameter = declarationsById.get(parameterId);
                if (parameter == null
                        || parameter.kind() != DeclarationKind.PARAMETER
                        || !parameter.moduleId().equals(lambda.moduleId())
                        || !parameter.scopeId().equals(lambda.scopeId())
                        || !lambdaParameters.add(parameterId)) {
                    throw new IllegalArgumentException(
                            "lambda parameter ownership is inconsistent");
                }
            }
            requireUnique(lambda.captures(), "lambda contains duplicate captures");
            for (CaptureId capture : lambda.captures()) {
                if (!lambdaCaptures.add(capture)) {
                    throw new IllegalArgumentException(
                            "capture belongs to multiple lambdas");
                }
            }
            lambda.ownerDeclaration().ifPresent(owner -> {
                ResolvedDeclaration declaration = declarationsById.get(owner);
                if (declaration == null
                        || !declaration.moduleId().equals(lambda.moduleId())
                        || declaration.initializerLambda().filter(lambda.id()::equals).isEmpty()) {
                    throw new IllegalArgumentException(
                            "lambda owner declaration linkage is inconsistent");
                }
            });
        }
        Set<DeclarationId> allParameters = declarations.stream()
                .filter(value -> value.kind() == DeclarationKind.PARAMETER)
                .map(ResolvedDeclaration::id)
                .collect(java.util.stream.Collectors.toSet());
        if (!lambdaParameters.equals(allParameters)) {
            throw new IllegalArgumentException(
                    "lambda parameter indexes do not cover every parameter declaration");
        }
        for (ResolvedScope scope : scopeTree.scopes()) {
            scope.ownerLambda().ifPresent(owner -> {
                ResolvedLambda lambda = lambdasById.get(owner);
                if (lambda == null || !lambda.moduleId().equals(scope.moduleId())) {
                    throw new IllegalArgumentException(
                            "scope names a foreign lambda owner");
                }
                if (scope.kind() == ScopeKind.LAMBDA
                        && !lambda.scopeId().equals(scope.id())) {
                    throw new IllegalArgumentException(
                            "lambda scope names another lambda owner");
                }
            });
        }

        Set<CaptureId> captureIds = new HashSet<>();
        Set<ReferenceId> capturedReferences = new HashSet<>();
        Set<String> captureKeys = new HashSet<>();
        for (ResolvedCapture capture : captures) {
            ResolvedLambda lambda = lambdasById.get(capture.lambdaId());
            ResolvedDeclaration declaration = declarationsById.get(capture.declarationId());
            if (lambda == null || declaration == null
                    || !capture.moduleId().equals(lambda.moduleId())
                    || !lambda.captures().contains(capture.id())
                    || !captureIds.add(capture.id())
                    || !captureKeys.add(capture.lambdaId() + "/" + capture.declarationId())) {
                throw new IllegalArgumentException(
                        "capture ownership is inconsistent");
            }
            if (!capture.declarationSpan().equals(declaration.span())) {
                throw new IllegalArgumentException(
                        "capture declaration span does not match its declaration");
            }
            if (capture.isSharedCell()
                    && capture.sharedCellId().filter(capture.declarationId()::equals).isEmpty()) {
                throw new IllegalArgumentException(
                        "shared capture cell is not its captured declaration");
            }
            requireUnique(capture.references(), "capture contains duplicate references");
            // An intermediate closure may carry a transitive capture for a
            // nested lambda without owning the final source reference itself.
            for (ReferenceId referenceId : capture.references()) {
                ResolvedReference reference = referencesById.get(referenceId);
                if (reference == null
                        || reference.capture().filter(capture.id()::equals).isEmpty()
                        || reference.fromLambda().filter(capture.lambdaId()::equals).isEmpty()
                        || reference.targetDeclaration()
                        .filter(capture.declarationId()::equals).isEmpty()
                        || !capturedReferences.add(referenceId)) {
                    throw new IllegalArgumentException(
                            "capture reference ownership is inconsistent");
                }
            }
        }
        if (!captureIds.equals(lambdaCaptures)) {
            throw new IllegalArgumentException(
                    "lambda capture indexes do not cover every capture");
        }
        Set<ReferenceId> indexedCapturedReferences = references.stream()
                .filter(reference -> reference.capture().isPresent())
                .map(ResolvedReference::id)
                .collect(java.util.stream.Collectors.toSet());
        if (!capturedReferences.equals(indexedCapturedReferences)) {
            throw new IllegalArgumentException(
                    "capture reference indexes are incomplete");
        }

        Set<DeclarationId> moduleDeclarations = new HashSet<>();
        Set<ReferenceId> moduleReferences = new HashSet<>();
        Set<LambdaId> moduleLambdas = new HashSet<>();
        Set<ResolvedImportBinding> moduleImports = new HashSet<>();
        Set<ResolvedExport> moduleExports = new HashSet<>();
        for (ResolvedModule module : modules) {
            collectOwned(module.declarations(), moduleDeclarations,
                    id -> declarationsById.get(id) == null
                            || !declarationsById.get(id).moduleId().equals(module.moduleId()),
                    "module declaration membership is inconsistent");
            collectOwned(module.references(), moduleReferences,
                    id -> referencesById.get(id) == null
                            || !referencesById.get(id).moduleId().equals(module.moduleId()),
                    "module reference membership is inconsistent");
            collectOwned(module.lambdas(), moduleLambdas,
                    id -> lambdasById.get(id) == null
                            || !lambdasById.get(id).moduleId().equals(module.moduleId()),
                    "module lambda membership is inconsistent");
            collectOwned(module.imports(), moduleImports,
                    value -> {
                        ResolvedDeclaration declaration = declarationsById.get(
                                value.declarationId());
                        return declaration == null
                                || !declaration.moduleId().equals(module.moduleId())
                                || !moduleGraph.module(value.targetModule()).isPresent();
                    },
                    "module import membership is inconsistent");
            collectOwned(module.exports(), moduleExports,
                    value -> !value.moduleId().equals(module.moduleId())
                            || !declarationsById.containsKey(value.declarationId()),
                    "module export membership is inconsistent");
        }
        requireUnique(imports, "global import index contains duplicates");
        requireUnique(exports, "global export index contains duplicates");
        if (!moduleDeclarations.equals(declarationsById.keySet())
                || !moduleReferences.equals(referencesById.keySet())
                || !moduleLambdas.equals(lambdasById.keySet())
                || !moduleImports.equals(Set.copyOf(imports))
                || !moduleExports.equals(Set.copyOf(exports))) {
            throw new IllegalArgumentException("semantic module indexes are incomplete");
        }
        ResolvedTopologyValidator.validate(this);
    }

    private static <T> void requireUnique(List<T> values, String message) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static <T> void collectOwned(
            List<T> values,
            Set<T> destination,
            java.util.function.Predicate<T> wrongOwner,
            String message) {
        for (T value : values) {
            if (wrongOwner.test(value) || !destination.add(value)) {
                throw new IllegalArgumentException(message);
            }
        }
    }

    private static Map<ModuleId, ResolvedModule> indexModules(List<ResolvedModule> values) {
        LinkedHashMap<ModuleId, ResolvedModule> result = new LinkedHashMap<>();
        for (ResolvedModule value : values) {
            if (result.put(value.moduleId(), value) != null) {
                throw new IllegalArgumentException("duplicate semantic module");
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<DeclarationId, ResolvedDeclaration> indexDeclarations(
            List<ResolvedDeclaration> values) {
        LinkedHashMap<DeclarationId, ResolvedDeclaration> result = new LinkedHashMap<>();
        for (ResolvedDeclaration value : values) {
            if (result.put(value.id(), value) != null) {
                throw new IllegalArgumentException("duplicate semantic declaration");
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<ReferenceId, ResolvedReference> indexReferences(List<ResolvedReference> values) {
        LinkedHashMap<ReferenceId, ResolvedReference> result = new LinkedHashMap<>();
        for (ResolvedReference value : values) {
            if (result.put(value.id(), value) != null) {
                throw new IllegalArgumentException("duplicate semantic reference");
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<LambdaId, ResolvedLambda> indexLambdas(List<ResolvedLambda> values) {
        LinkedHashMap<LambdaId, ResolvedLambda> result = new LinkedHashMap<>();
        for (ResolvedLambda value : values) {
            if (result.put(value.id(), value) != null) {
                throw new IllegalArgumentException("duplicate semantic lambda");
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<CaptureId, ResolvedCapture> indexCaptures(List<ResolvedCapture> values) {
        LinkedHashMap<CaptureId, ResolvedCapture> result = new LinkedHashMap<>();
        for (ResolvedCapture value : values) {
            if (result.put(value.id(), value) != null) {
                throw new IllegalArgumentException("duplicate semantic capture");
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static <T> List<T> ordered(List<T> values, Comparator<T> comparator, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<T> copied = new ArrayList<>();
        for (T value : values) {
            copied.add(Objects.requireNonNull(value, name + " must not contain null"));
        }
        copied.sort(comparator);
        return List.copyOf(copied);
    }

    private static <T> List<T> copy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        for (T value : values) {
            Objects.requireNonNull(value, name + " must not contain null");
        }
        return List.copyOf(values);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof ResolvedSemanticGraph graph
                && moduleGraph.equals(graph.moduleGraph)
                && scopeTree.equals(graph.scopeTree)
                && modules.equals(graph.modules)
                && declarations.equals(graph.declarations)
                && references.equals(graph.references)
                && lambdas.equals(graph.lambdas)
                && imports.equals(graph.imports)
                && exports.equals(graph.exports)
                && captures.equals(graph.captures)
                && mutations.equals(graph.mutations)
                && syntaxLinks.equals(graph.syntaxLinks)
                && functionLinkage.equals(graph.functionLinkage)
                && sessionGraph == graph.sessionGraph;
    }

    @Override
    public int hashCode() {
        return Objects.hash(moduleGraph, scopeTree, modules, declarations, references,
                lambdas, imports, exports, captures, mutations, syntaxLinks, functionLinkage,
                sessionGraph);
    }

    @Override
    public String toString() {
        return "ResolvedSemanticGraph[modules=" + modules.size()
                + ", declarations=" + declarations.size()
                + ", references=" + references.size()
                + ", captures=" + captures.size() + "]";
    }
}
