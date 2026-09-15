package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.ast.SyntaxNode;
import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ExportId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.lex.ModifierKind;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.BindingContract;
import io.mindspice.lyra.compiler.types.BindingMutability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Structural source/module topology audit for one frozen resolved graph. */
final class ResolvedTopologyValidator {
    private final ResolvedSemanticGraph resolved;
    private final ModuleGraph moduleGraph;
    private final Map<ExportKey, ResolvedExport> exportsByKey = new LinkedHashMap<>();
    private final Map<DeclarationId, ResolvedExport> exportsByDeclaration = new LinkedHashMap<>();
    private final Map<DeclarationId, ResolvedImportBinding> importsByDeclaration =
            new LinkedHashMap<>();
    private final Map<ReferenceId, SyntaxLink> referenceLinks = new LinkedHashMap<>();
    private final Map<ScopeId, Integer> scopeDepths = new HashMap<>();
    private final SelfAliasProvenance selfAliasProvenance;

    private ResolvedTopologyValidator(ResolvedSemanticGraph resolved) {
        this.resolved = Objects.requireNonNull(resolved, "resolved");
        this.moduleGraph = resolved.moduleGraph();
        this.selfAliasProvenance = SelfAliasProvenance.of(resolved);
    }

    static void validate(ResolvedSemanticGraph resolved) {
        new ResolvedTopologyValidator(resolved).run();
    }

    private void run() {
        validateSourceHeaderCoverage();
        indexAndValidateExports();
        indexAndValidateImports();
        validateExportOrigins();
        validateImportBindingLinks();
        validateReferenceLinks();
        validateReferenceSourceAuthority();
        validateReferenceTargets();
        validateCaptures();
        validateMutations();
    }

    private void validateSourceHeaderCoverage() {
        List<ImportShape> expectedImports = new ArrayList<>();
        List<HeaderEdge> expectedEdges = new ArrayList<>();
        for (ModuleGraph.Node node : moduleGraph.modules()) {
            ResolvedModule module = resolved.module(node.moduleId()).orElseThrow(() -> invalid(
                    "source module is absent from resolved topology"));
            if (!module.logicalModule().equals(node.logicalModule())) {
                throw invalid("resolved module logical identity changed from its source graph");
            }
            if (resolved.isRetained(node.moduleId())) {
                var producer = resolved.retainedModules().module(node.moduleId()).orElseThrow();
                if (!producer.source().equals(node.snapshot())
                        || !producer.resolvedModule().imports().equals(module.imports())) {
                    throw invalid("retained source header differs from its exact producer");
                }
                continue;
            }
            for (SyntaxNode.ImportDeclaration syntax : node.program().imports()) {
                LogicalModuleId logical = logicalModule(syntax);
                ModuleId target = moduleGraph.moduleFor(logical).orElseThrow(() -> invalid(
                        "source import has no logical-module target"));
                expectedEdges.add(new HeaderEdge(
                        node.moduleId(), logical, target, syntax.path().span()));
                boolean reExport = syntax.modifiers().stream()
                        .anyMatch(modifier -> modifier.kind() == ModifierKind.PUBLIC);
                if (syntax.selection().isEmpty()) {
                    if (reExport) {
                        throw invalid("a namespace import cannot be a re-export");
                    }
                    String localName = syntax.alias().map(SyntaxNode.ImportAlias::name)
                            .orElse(syntax.path().finalSegment());
                    SourceSpan localNameSpan = syntax.alias()
                            .map(alias -> alias.alias().span())
                            .orElseGet(() -> syntax.path().segments().getLast().span());
                    expectedImports.add(new ImportShape(
                            node.moduleId(), localName, localNameSpan, syntax.span(), syntax.span(),
                            logical, target, ImportBindingKind.MODULE_NAMESPACE, Optional.empty(),
                            syntax.alias().map(SyntaxNode.ImportAlias::name), false));
                } else {
                    for (SyntaxNode.ImportItem item : syntax.selection().orElseThrow().items()) {
                        String localName = item.alias().map(SyntaxNode.ImportAlias::name)
                                .orElse(item.importedName());
                        SourceSpan localNameSpan = item.alias()
                                .map(alias -> alias.alias().span())
                                .orElseGet(() -> item.name().span());
                        expectedImports.add(new ImportShape(
                                node.moduleId(), localName, localNameSpan, item.span(), syntax.span(),
                                logical, target, ImportBindingKind.SELECTIVE_VALUE,
                                Optional.of(item.importedName()),
                                item.alias().map(SyntaxNode.ImportAlias::name), reExport));
                    }
                }
            }
        }

        List<HeaderEdge> actualEdges = moduleGraph.edges().stream()
                .map(edge -> new HeaderEdge(
                        edge.from(), edge.logicalTarget(), edge.target(), edge.importSpan()))
                .toList();
        if (!counts(expectedEdges).equals(counts(actualEdges))) {
            throw invalid("module edges do not cover exactly every source import header");
        }

        List<ImportShape> actualImports = new ArrayList<>();
        for (ResolvedModule module : resolved.modules()) {
            if (resolved.isRetained(module.moduleId())) continue;
            for (ResolvedImportBinding binding : module.imports()) {
                if (binding.retained()) {
                    continue;
                }
                ResolvedDeclaration declaration = resolved.declaration(binding.declarationId())
                        .orElseThrow(() -> invalid(
                                "module import has no local declaration"));
                actualImports.add(new ImportShape(
                        module.moduleId(), binding.localName(), binding.localNameSpan(),
                        declaration.span(), binding.importSpan(), binding.logicalModule(),
                        binding.targetModule(), binding.kind(), binding.importedName(),
                        binding.aliasName(), binding.reExport()));
            }
        }
        if (!counts(expectedImports).equals(counts(actualImports))) {
            throw invalid("resolved imports do not cover exactly every source header binding");
        }
    }

    private void indexAndValidateExports() {
        for (ResolvedExport export : resolved.exports()) {
            ExportKey key = new ExportKey(export.moduleId(), export.name());
            if (exportsByKey.put(key, export) != null) {
                throw invalid("module contains duplicate export names");
            }
            if (exportsByDeclaration.put(export.declarationId(), export) != null) {
                throw invalid("declaration is indexed by multiple exports");
            }
            ResolvedDeclaration declaration = resolved.declaration(export.declarationId())
                    .orElseThrow(() -> invalid(
                            "export declaration is absent from resolved declarations"));
            if (!declaration.moduleId().equals(export.moduleId())
                    || !declaration.name().equals(export.name())
                    || !declaration.span().equals(export.span())
                    || declaration.visibility() != DeclarationVisibility.PUBLIC
                    || declaration.reExported() != export.reExport()
                    || !declaration.functionSignature().equals(export.functionSignature())) {
                throw invalid("export and local declaration linkage is inconsistent");
            }
            Optional<ExportId> canonicalId = export.functionSignature()
                    .map(signature -> ExportId.of(export.moduleId(), export.name(), signature));
            if (!export.exportId().equals(canonicalId)) {
                throw invalid("export identity is not canonical for its module/name/signature");
            }
            if (!export.reExport()) {
                if (declaration.imported()
                        || declaration.kind() != DeclarationKind.LET
                        && declaration.kind() != DeclarationKind.NOMINAL
                        && declaration.kind() != DeclarationKind.INTRINSIC_EXPORT
                        || declaration.effectiveContract().filter(export.contract()::equals).isEmpty()
                        || !export.originModule().equals(export.moduleId())
                        || !export.originName().equals(export.name())
                        || !export.originDeclaration().equals(export.declarationId())
                        || !export.originExport().equals(export.exportId())) {
                    throw invalid("local export origin is not its exact public declaration");
                }
            } else if (!declaration.imported()
                    || declaration.kind() != DeclarationKind.IMPORT_VALUE
                    || !declaration.reExported()) {
                throw invalid("re-export is not owned by a public selective import declaration");
            }
        }

        for (ResolvedDeclaration declaration : resolved.declarations()) {
            boolean mustExport = declaration.visibility() == DeclarationVisibility.PUBLIC
                    && (declaration.kind() == DeclarationKind.LET
                    || declaration.kind() == DeclarationKind.NOMINAL
                    || declaration.kind() == DeclarationKind.INTRINSIC_EXPORT
                    || declaration.kind() == DeclarationKind.IMPORT_VALUE
                    && declaration.reExported());
            if (mustExport != exportsByDeclaration.containsKey(declaration.id())) {
                throw invalid("public declaration/export indexes are not reciprocal");
            }
        }
    }

    private void indexAndValidateImports() {
        for (ResolvedModule module : resolved.modules()) {
            for (ResolvedImportBinding binding : module.imports()) {
                if (importsByDeclaration.put(binding.declarationId(), binding) != null) {
                    throw invalid("import declaration is indexed by multiple import bindings");
                }
                ResolvedDeclaration declaration = resolved.declaration(binding.declarationId())
                        .orElseThrow(() -> invalid(
                                "import declaration is absent from resolved declarations"));
                DeclarationKind expectedKind = binding.kind() == ImportBindingKind.MODULE_NAMESPACE
                        ? DeclarationKind.IMPORT_MODULE : DeclarationKind.IMPORT_VALUE;
                DeclarationVisibility expectedVisibility = binding.reExport()
                        ? DeclarationVisibility.PUBLIC : DeclarationVisibility.PRIVATE;
                if (!declaration.moduleId().equals(module.moduleId())
                        || !declaration.name().equals(binding.localName())
                        || !declaration.nameSpan().equals(binding.localNameSpan())
                        || declaration.kind() != expectedKind
                        || declaration.visibility() != expectedVisibility
                        || declaration.bindingMutability() != BindingMutability.IMMUTABLE
                        || !declaration.imported()
                        || declaration.reExported() != binding.reExport()
                        || !declaration.importedModule().equals(Optional.of(binding.targetModule()))
                        || !declaration.importedName().equals(binding.importedName())
                        || !declaration.originDeclaration().equals(binding.targetDeclaration())
                        || !declaration.originExport().equals(binding.targetExport())
                        || declaration.declaredContract().isPresent()
                        || declaration.inferredContract().isPresent()) {
                    throw invalid("import binding and local declaration linkage is inconsistent");
                }
                if (binding.kind() == ImportBindingKind.MODULE_NAMESPACE) {
                    if (binding.reExport()
                            || binding.targetDeclaration().isPresent()
                            || binding.targetExport().isPresent()
                            || declaration.effectiveContract().isPresent()
                            || declaration.functionSignature().isPresent()) {
                        throw invalid("namespace import carries selective-value linkage");
                    }
                    continue;
                }

                String importedName = binding.importedName().orElseThrow(() -> invalid(
                        "selective import has no imported name"));
                ResolvedExport target = importTarget(binding);
                if (target == null) {
                    throw invalid("selective import does not target an existing public export");
                }
                BindingContract importedContract = BindingContract.immutable(
                        target.contract().valueType());
                if (!binding.targetDeclaration().equals(Optional.of(target.originDeclaration()))
                        || !binding.targetExport().equals(target.exportId())
                        || declaration.effectiveContract().filter(importedContract::equals).isEmpty()
                        || !declaration.functionSignature().equals(target.functionSignature())) {
                    throw invalid("selective import target/export/declaration linkage is inconsistent");
                }
                Optional<ResolvedDeclaration> resolvedOrigin = resolved.declaration(
                        target.originDeclaration());
                if (resolvedOrigin.isPresent()) {
                    if (!resolvedOrigin.orElseThrow().moduleId().equals(target.originModule())) {
                        throw invalid("selective import origin declaration belongs to another module");
                    }
                } else if (binding.producerContract().flatMap(contract -> contract.exports().stream()
                        .filter(value -> value.export().equals(target))
                        .findFirst()).isEmpty()
                        && resolved.retainedModules().module(module.moduleId())
                        .flatMap(record -> record.producerGraph().resolvedGraph()
                                .declaration(target.originDeclaration()))
                        .filter(origin -> origin.moduleId().equals(target.originModule())).isEmpty()) {
                    throw invalid("selective import origin declaration is absent");
                }
            }
        }

        for (ResolvedDeclaration declaration : resolved.declarations()) {
            boolean importKind = declaration.kind() == DeclarationKind.IMPORT_MODULE
                    || declaration.kind() == DeclarationKind.IMPORT_VALUE;
            if (declaration.imported() != importKind
                    || importKind != importsByDeclaration.containsKey(declaration.id())) {
                throw invalid("import declaration/index membership is not reciprocal");
            }
        }
    }

    private void validateExportOrigins() {
        for (ResolvedExport export : resolved.exports()) {
            if (!export.reExport()) {
                continue;
            }
            ResolvedImportBinding binding = importsByDeclaration.get(export.declarationId());
            if (binding == null || !binding.reExport()
                    || binding.kind() != ImportBindingKind.SELECTIVE_VALUE) {
                throw invalid("re-export has no exact source import binding");
            }
            ResolvedExport target = importTarget(binding);
            if (target == null
                    || !export.contract().equals(target.contract())
                    || !export.functionSignature().equals(target.functionSignature())
                    || !export.originModule().equals(target.originModule())
                    || !export.originName().equals(target.originName())
                    || !export.originDeclaration().equals(target.originDeclaration())
                    || !export.originExport().equals(target.originExport())) {
                throw invalid("re-export does not preserve its immediate target export origin");
            }

            ResolvedExport ultimate = ultimateOrigin(export);
            if (!export.originModule().equals(ultimate.moduleId())
                    || !export.originName().equals(ultimate.name())
                    || !export.originDeclaration().equals(ultimate.declarationId())
                    || !export.originExport().equals(ultimate.exportId())) {
                throw invalid("re-export does not resolve to one canonical local origin");
            }
        }
    }

    private ResolvedExport importTarget(ResolvedImportBinding binding) {
        String importedName = binding.importedName().orElseThrow(() -> invalid(
                "selective import has no imported name"));
        if (binding.retained() && binding.producerContract().isPresent()) {
            return binding.producerContract().orElseThrow().exports().stream()
                    .filter(value -> value.export().name().equals(importedName))
                    .map(io.mindspice.lyra.compiler.session.SessionModuleContract.Export::export)
                    .findFirst().orElse(null);
        }
        ResolvedExport target = exportsByKey.get(new ExportKey(binding.targetModule(), importedName));
        ResolvedExport retained = retainedProducerExport(binding, importedName);
        if (retained != null && (target == null
                || !binding.targetDeclaration().equals(Optional.of(target.originDeclaration()))
                || !binding.targetExport().equals(target.exportId()))) {
            return retained;
        }
        return target;
    }

    private ResolvedExport retainedProducerExport(
            ResolvedImportBinding binding, String name) {
        return resolved.declaration(binding.declarationId())
                .flatMap(declaration -> resolved.retainedModules().module(declaration.moduleId()))
                .flatMap(record -> record.producerGraph().resolvedGraph().module(binding.targetModule()))
                .flatMap(module -> module.export(name))
                .orElse(null);
    }

    private ResolvedExport ultimateOrigin(ResolvedExport source) {
        ResolvedExport current = source;
        Set<ExportKey> visited = new HashSet<>();
        while (current.reExport()) {
            ExportKey key = new ExportKey(current.moduleId(), current.name());
            if (!visited.add(key)) {
                throw invalid("re-export topology contains an origin cycle");
            }
            ResolvedImportBinding binding = importsByDeclaration.get(current.declarationId());
            if (binding == null) {
                throw invalid("re-export chain has no import binding");
            }
            current = importTarget(binding);
            if (current == null) {
                throw invalid("re-export chain targets an absent export");
            }
        }
        return current;
    }

    private void validateImportBindingLinks() {
        Map<DeclarationId, SyntaxLink> links = new HashMap<>();
        for (SyntaxLink link : resolved.syntaxLinks()) {
            if (link.kind() != SyntaxLinkKind.IMPORT_BINDING) {
                continue;
            }
            DeclarationId declarationId = link.declarationId().orElseThrow(() -> invalid(
                    "import-binding syntax link has no declaration"));
            if (links.put(declarationId, link) != null) {
                throw invalid("import binding has multiple syntax links");
            }
            ResolvedImportBinding binding = importsByDeclaration.get(declarationId);
            if (binding == null
                    || binding.retained()
                    || !link.span().equals(binding.localNameSpan())
                    || link.referenceId().isPresent()
                    || link.lambdaId().isPresent()
                    || !link.moduleId().equals(Optional.of(binding.targetModule()))
                    || !link.exportId().equals(binding.targetExport())
                    || link.accessKind().isPresent()) {
                throw invalid("import-binding syntax link disagrees with its import record");
            }
        }
        Set<DeclarationId> sourceImportDeclarations = importsByDeclaration.values().stream()
                .filter(binding -> !binding.retained())
                .map(ResolvedImportBinding::declarationId)
                .collect(java.util.stream.Collectors.toSet());
        if (!links.keySet().equals(sourceImportDeclarations)) {
            throw invalid("import-binding syntax links are incomplete");
        }
    }

    private void validateReferenceLinks() {
        for (SyntaxLink link : resolved.syntaxLinks()) {
            if (link.kind() != SyntaxLinkKind.REFERENCE) {
                continue;
            }
            ReferenceId referenceId = link.referenceId().orElseThrow(() -> invalid(
                    "reference syntax link has no reference identity"));
            if (referenceLinks.put(referenceId, link) != null) {
                throw invalid("reference has multiple canonical syntax links");
            }
            ResolvedReference reference = resolved.reference(referenceId).orElseThrow(() -> invalid(
                    "reference syntax link names an absent reference"));
            if (!link.span().equals(reference.span())
                    || !link.declarationId().equals(reference.targetDeclaration())
                    || link.lambdaId().isPresent()
                    || !link.moduleId().equals(reference.targetModule())
                    || !link.exportId().equals(reference.targetExport())
                    || link.accessKind().isPresent()) {
                throw invalid("reference syntax link disagrees with its resolved reference");
            }
        }
        Set<ReferenceId> expected = resolved.references().stream()
                .map(ResolvedReference::id)
                .collect(java.util.stream.Collectors.toSet());
        if (!referenceLinks.keySet().equals(expected)) {
            throw invalid("reference syntax links do not cover exactly every reference");
        }
    }

    private void validateReferenceSourceAuthority() {
        resolved.referenceTopology().ifPresent(authority -> {
            if (!authority.scopeTree().equals(resolved.scopeTree())) {
                throw invalid(
                        "scope ancestry differs from the source-authoritative producer topology");
            }
            if (!authority.declarations().equals(resolved.declarations())) {
                throw invalid(
                        "declarations differ from the source-authoritative binding topology");
            }
            if (!authority.references().equals(resolved.references())) {
                throw invalid(
                        "resolved references differ from the source-authoritative producer index");
            }
            if (!authority.captures().equals(resolved.captures())) {
                throw invalid(
                        "resolved captures differ from the source-authoritative producer index");
            }
            if (!authority.referencesByDeclaration().equals(
                    referencesByDeclaration(resolved.references()))) {
                throw invalid(
                        "declaration/reference indexes differ from the source-authoritative binding index");
            }
            if (!authority.referencesByScope().equals(
                    referencesByScope(resolved.references()))) {
                throw invalid(
                        "scope/reference indexes differ from the source-authoritative lexical index");
            }
            if (!authority.referencesByCapture().equals(
                    referencesByCapture(resolved.captures()))) {
                throw invalid(
                        "capture/reference indexes differ from the source-authoritative capture index");
            }
            if (!authority.captureByReference().equals(
                    captureByReference(resolved.references()))) {
                throw invalid(
                        "reference/capture indexes differ from the source-authoritative capture index");
            }
        });
    }

    private void validateReferenceTargets() {
        for (ResolvedReference reference : resolved.references()) {
            ResolvedScope sourceScope = canonicalSourceScope(reference);
            if (!reference.scopeId().equals(sourceScope.id())
                    || !reference.fromLambda().equals(sourceScope.ownerLambda())) {
                throw invalid(
                        "reference scope/owner is not its exact syntax-derived lexical scope");
            }
            validateLexicalTarget(reference, sourceScope);

            if (reference.kind() == ReferenceKind.MODULE_NAMESPACE) {
                if (reference.targetExport().isPresent()) {
                    throw invalid("module namespace reference carries an export identity");
                }
                if (reference.targetDeclaration().isPresent()) {
                    ResolvedDeclaration declaration = resolved.declaration(
                            reference.targetDeclaration().orElseThrow()).orElseThrow();
                    if (declaration.kind() != DeclarationKind.IMPORT_MODULE
                            || !declaration.name().equals(reference.name())
                            || !declaration.importedModule().equals(reference.targetModule())) {
                        throw invalid("module namespace reference disagrees with its import declaration");
                    }
                } else {
                    ModuleId target = reference.targetModule().orElseThrow(() -> invalid(
                            "module namespace reference has no target module"));
                    ResolvedModule owner = resolved.module(reference.moduleId()).orElseThrow();
                    boolean imported = owner.imports().stream().anyMatch(binding ->
                            binding.kind() == ImportBindingKind.MODULE_NAMESPACE
                                    && binding.aliasName().isEmpty()
                                    && binding.logicalModule().value().equals(reference.name())
                                    && binding.targetModule().equals(target));
                    if (!imported) {
                        throw invalid("qualified namespace reference has no source import");
                    }
                }
                continue;
            }

            if (reference.kind() == ReferenceKind.NAMESPACE_MEMBER
                    || reference.kind() == ReferenceKind.NAMESPACE_DIRECT_CALL) {
                ModuleId targetModule = reference.targetModule().orElseThrow(() -> invalid(
                        "namespace member reference has no target module"));
                ResolvedExport target = exportsByKey.get(
                        new ExportKey(targetModule, reference.name()));
                if (target == null
                        || !reference.targetDeclaration().equals(
                        Optional.of(target.originDeclaration()))
                        || !reference.targetExport().equals(target.exportId())) {
                    target = retainedNamespaceExport(reference, targetModule);
                }
                if (target == null
                        || !reference.targetDeclaration().equals(
                        Optional.of(target.originDeclaration()))
                        || !reference.targetExport().equals(target.exportId())) {
                    throw invalid("namespace member reference disagrees with its target export");
                }
                continue;
            }

            ResolvedDeclaration target = resolved.declaration(
                    reference.targetDeclaration().orElseThrow(() -> invalid(
                            "value reference has no declaration target"))).orElseThrow();
            if (!target.name().equals(reference.name())) {
                throw invalid("value reference name disagrees with its declaration target");
            }
            if (target.imported()) {
                if (target.kind() != DeclarationKind.IMPORT_VALUE
                        || !reference.targetModule().equals(target.importedModule())
                        || !reference.targetExport().equals(target.originExport())) {
                    throw invalid("imported value reference disagrees with its import declaration");
                }
            } else if (!target.moduleId().equals(reference.moduleId())
                    || reference.targetModule().isPresent()
                    || reference.targetExport().isPresent()) {
                throw invalid("local value reference carries foreign module/export linkage");
            }
        }
    }

    private ResolvedExport retainedNamespaceExport(
            ResolvedReference reference, ModuleId targetModule) {
        return resolved.retainedModules().module(reference.moduleId())
                .flatMap(record -> record.producerGraph().resolvedGraph()
                        .module(targetModule))
                .flatMap(module -> module.export(reference.name()))
                .orElse(null);
    }

    private ResolvedScope canonicalSourceScope(ResolvedReference reference) {
        int deepest = -1;
        ResolvedScope canonical = null;
        boolean ambiguous = false;
        for (ResolvedScope scope : resolved.scopeTree().scopes()) {
            if (!scope.moduleId().equals(reference.moduleId())
                    || !contains(scope.span(), reference.span())) {
                continue;
            }
            int depth = scopeDepth(scope);
            if (depth > deepest) {
                deepest = depth;
                canonical = scope;
                ambiguous = false;
            } else if (depth == deepest) {
                ambiguous = true;
            }
        }
        if (canonical == null || ambiguous) {
            throw invalid("reference source site has no unique syntax-derived lexical scope");
        }
        return canonical;
    }

    private int scopeDepth(ResolvedScope scope) {
        Integer known = scopeDepths.get(scope.id());
        if (known != null) {
            return known;
        }
        int depth = scope.parent()
                .map(parent -> scopeDepth(resolved.scopeTree().require(parent)) + 1)
                .orElse(0);
        scopeDepths.put(scope.id(), depth);
        return depth;
    }

    private void validateLexicalTarget(
            ResolvedReference reference,
            ResolvedScope sourceScope) {
        boolean lexical = reference.kind() == ReferenceKind.VALUE
                || reference.kind() == ReferenceKind.DIRECT_CALL_TARGET
                || reference.kind() == ReferenceKind.MODULE_NAMESPACE
                && reference.targetDeclaration().isPresent();
        if (!lexical) {
            return;
        }
        ResolvedDeclaration canonical = lexicalDeclaration(
                reference.name(), reference.span().startOffset(), sourceScope);
        if (canonical == null
                || !reference.targetDeclaration().equals(Optional.of(canonical.id()))) {
            throw invalid(
                    "reference target is not its source-authoritative lexical binding");
        }
        if (reference.kind() == ReferenceKind.MODULE_NAMESPACE
                && canonical.kind() != DeclarationKind.IMPORT_MODULE) {
            throw invalid("module namespace reference does not select a namespace import");
        }
        if (reference.kind() != ReferenceKind.MODULE_NAMESPACE
                && canonical.kind() == DeclarationKind.IMPORT_MODULE) {
            throw invalid("value reference selects a module namespace declaration");
        }
    }

    private ResolvedDeclaration lexicalDeclaration(
            String name,
            int sourceOffset,
            ResolvedScope startingScope) {
        return LexicalBindingLookup.select(
                sourceOffset, startingScope,
                scope -> scope.declarations().stream()
                        .map(id -> resolved.declaration(id).orElseThrow())
                        .filter(declaration -> declaration.name().equals(name))
                        .toList(),
                scope -> scope.parent()
                        .map(parent -> resolved.scopeTree().require(parent))
                        .orElse(null),
                ResolvedDeclaration::kind,
                declaration -> declaration.nameSpan().startOffset(),
                ResolvedDeclaration::signaturePredeclared);
    }

    private static boolean contains(SourceSpan container, SourceSpan value) {
        return container.sourceId().equals(value.sourceId())
                && container.startOffset() <= value.startOffset()
                && value.endOffset() <= container.endOffset();
    }

    private static Map<DeclarationId, List<ReferenceId>> referencesByDeclaration(
            List<ResolvedReference> references) {
        Map<DeclarationId, List<ReferenceId>> result = new LinkedHashMap<>();
        for (ResolvedReference reference : references) {
            reference.targetDeclaration().ifPresent(declaration -> result
                    .computeIfAbsent(declaration, ignored -> new ArrayList<>())
                    .add(reference.id()));
        }
        result.replaceAll((ignored, values) -> values.stream().sorted().toList());
        return result;
    }

    private static Map<ScopeId, List<ReferenceId>> referencesByScope(
            List<ResolvedReference> references) {
        Map<ScopeId, List<ReferenceId>> result = new LinkedHashMap<>();
        for (ResolvedReference reference : references) {
            result.computeIfAbsent(reference.scopeId(), ignored -> new ArrayList<>())
                    .add(reference.id());
        }
        result.replaceAll((ignored, values) -> values.stream().sorted().toList());
        return result;
    }

    private static Map<CaptureId, List<ReferenceId>> referencesByCapture(
            List<ResolvedCapture> captures) {
        Map<CaptureId, List<ReferenceId>> result = new LinkedHashMap<>();
        for (ResolvedCapture capture : captures) {
            result.put(capture.id(), List.copyOf(capture.references()));
        }
        return result;
    }

    private static Map<ReferenceId, CaptureId> captureByReference(
            List<ResolvedReference> references) {
        Map<ReferenceId, CaptureId> result = new LinkedHashMap<>();
        for (ResolvedReference reference : references) {
            reference.capture().ifPresent(capture -> result.put(reference.id(), capture));
        }
        return result;
    }

    private void validateCaptures() {
        Map<CaptureKey, ResolvedCapture> capturesByKey = new LinkedHashMap<>();
        for (ResolvedCapture capture : resolved.captures()) {
            CaptureKey key = new CaptureKey(capture.lambdaId(), capture.declarationId());
            if (capturesByKey.put(key, capture) != null) {
                throw invalid("capture topology contains duplicate lambda/declaration slots");
            }
        }

        Map<CaptureKey, List<ReferenceId>> sourceReferences = new LinkedHashMap<>();
        Map<CaptureKey, List<ReferenceId>> directReferences = new LinkedHashMap<>();
        List<ResolvedReference> sourceOrderedReferences = resolved.references().stream()
                .sorted(Comparator.comparing(ResolvedReference::moduleId)
                        .thenComparingInt(reference -> reference.span().startOffset())
                        .thenComparingInt(reference -> reference.span().endOffset())
                        .thenComparing(ResolvedReference::id))
                .toList();
        for (ResolvedReference reference : sourceOrderedReferences) {
            List<CaptureKey> chain = sourceCaptureChain(reference);
            if (chain.isEmpty()) {
                if (reference.capture().isPresent()) {
                    throw invalid("source reference carries a capture outside its lexical capture chain");
                }
                continue;
            }

            CaptureKey directKey = chain.getFirst();
            ResolvedCapture directCapture = capturesByKey.get(directKey);
            if (directCapture == null
                    || !reference.capture().equals(Optional.of(directCapture.id()))) {
                throw invalid("source reference is missing its exact direct capture linkage");
            }
            directReferences.computeIfAbsent(directKey, ignored -> new ArrayList<>())
                    .add(reference.id());
            for (CaptureKey key : chain) {
                sourceReferences.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(reference.id());
            }
        }

        if (!capturesByKey.keySet().equals(sourceReferences.keySet())) {
            throw invalid("resolved captures do not cover exactly every source capture chain");
        }
        for (Map.Entry<CaptureKey, List<ReferenceId>> entry : sourceReferences.entrySet()) {
            CaptureKey key = entry.getKey();
            ResolvedCapture capture = capturesByKey.get(key);
            ResolvedLambda lambda = resolved.lambda(key.lambdaId()).orElseThrow(() -> invalid(
                    "source capture names an absent owner lambda"));
            ResolvedDeclaration declaration = resolved.declaration(
                    key.declarationId()).orElseThrow(() -> invalid(
                    "source capture names an absent declaration"));
            ResolvedScope lambdaScope = resolved.scopeTree().require(lambda.scopeId());
            List<ReferenceId> expectedDirect = directReferences.getOrDefault(key, List.of());
            ResolvedReference sourceReference = resolved.reference(entry.getValue().getFirst())
                    .orElseThrow();
            boolean exactSourceScopes = entry.getValue().stream()
                    .map(referenceId -> resolved.reference(referenceId).orElseThrow())
                    .allMatch(reference -> isAncestor(
                                    lambdaScope,
                                    resolved.scopeTree().require(reference.scopeId()))
                            && contains(lambda.span(), reference.span()));
            CaptureMode expectedMode = declaration.bindingMutability().isMutable()
                    ? CaptureMode.SHARED_MUTABLE_CELL : CaptureMode.IMMUTABLE_VALUE;
            Optional<DeclarationId> expectedCell = expectedMode == CaptureMode.SHARED_MUTABLE_CELL
                    ? Optional.of(declaration.id()) : Optional.empty();
            if (!capture.moduleId().equals(lambda.moduleId())
                    || lambdaScope.kind() != ScopeKind.LAMBDA
                    || lambdaScope.ownerLambda().filter(lambda.id()::equals).isEmpty()
                    || !lambdaScope.span().equals(lambda.span())
                    || !capture.declarationSpan().equals(declaration.span())
                    || !capture.span().equals(sourceReference.span())
                    || !exactSourceScopes
                    || capture.mode() != expectedMode
                    || !capture.sharedCellId().equals(expectedCell)
                    || !capture.references().equals(expectedDirect)) {
                throw invalid(
                        "capture metadata and direct-reference index are not source-authoritative");
            }
        }
    }

    private List<CaptureKey> sourceCaptureChain(ResolvedReference reference) {
        if (reference.fromLambda().isEmpty()
                || reference.targetDeclaration().isEmpty()
                || reference.kind() == ReferenceKind.NAMESPACE_MEMBER
                || reference.kind() == ReferenceKind.NAMESPACE_DIRECT_CALL) {
            return List.of();
        }
        ResolvedLambda current = resolved.lambda(reference.fromLambda().orElseThrow())
                .orElseThrow(() -> invalid("source reference names an absent lambda owner"));
        ResolvedDeclaration target = resolved.declaration(
                reference.targetDeclaration().orElseThrow()).orElseThrow(() -> invalid(
                "source reference names an absent declaration"));
        boolean rebindingTarget = resolved.mutations().stream()
                .anyMatch(mutation -> mutation.rootReference().filter(reference.id()::equals).isPresent());
        if (current.ownerDeclaration().filter(target.id()::equals).isPresent()) {
            return List.of();
        }
        if (target.kind() == DeclarationKind.IMPORT_MODULE
                || target.kind() == DeclarationKind.EXTERNAL
                || current.ownerDeclaration().isPresent() && isModuleLinkedFunction(target)) {
            return List.of();
        }

        ResolvedScope useScope = resolved.scopeTree().require(reference.scopeId());
        ResolvedScope targetScope = resolved.scopeTree().require(target.scopeId());
        if (target.kind() == DeclarationKind.SELF
                && targetScope.kind() == ScopeKind.LAMBDA) {
            List<CaptureKey> contextual = new ArrayList<>();
            Optional<LambdaId> owner = targetScope.ownerLambda();
            while (current != null) {
                contextual.add(new CaptureKey(current.id(), target.id()));
                if (owner.filter(current.id()::equals).isPresent()) break;
                current = parentLambda(current).orElse(null);
            }
            if (owner.isPresent() && current == null) {
                throw invalid("contextual self capture does not reach its replacement lambda");
            }
            return List.copyOf(contextual);
        }
        if (!isAncestor(targetScope, useScope)
                || targetScope.ownerLambda().equals(reference.fromLambda())) {
            return List.of();
        }

        Optional<LambdaId> targetOwner = targetScope.ownerLambda();
        List<CaptureKey> result = new ArrayList<>();
        while (current != null
                && (targetOwner.isEmpty() || !current.id().equals(targetOwner.orElseThrow()))) {
            if (current.ownerDeclaration().filter(target.id()::equals).isEmpty() || rebindingTarget) {
                result.add(new CaptureKey(current.id(), target.id()));
            }
            current = parentLambda(current).orElse(null);
        }
        if (targetOwner.isPresent() && current == null) {
            throw invalid("source capture chain does not reach the declaration owner lambda");
        }
        return List.copyOf(result);
    }

    private boolean isModuleLinkedFunction(ResolvedDeclaration declaration) {
        ResolvedScope scope = resolved.scopeTree().require(declaration.scopeId());
        DeclarationId target = declaration.originDeclaration().orElse(declaration.id());
        return scope.kind() == ScopeKind.MODULE
                && resolved.functionLinkage().signatures().containsKey(target);
    }

    private Optional<ResolvedLambda> parentLambda(ResolvedLambda lambda) {
        return resolved.scopeTree().require(lambda.scopeId()).parent()
                .flatMap(parent -> resolved.scopeTree().require(parent).ownerLambda())
                .flatMap(resolved::lambda);
    }

    private boolean isAncestor(ResolvedScope possibleAncestor, ResolvedScope scope) {
        ResolvedScope current = scope;
        while (current != null) {
            if (current.id().equals(possibleAncestor.id())) {
                return true;
            }
            current = current.parent()
                    .map(parent -> resolved.scopeTree().require(parent))
                    .orElse(null);
        }
        return false;
    }

    private void validateMutations() {
        List<SourceMutationSite> sourceSites = new ArrayList<>();
        for (ModuleGraph.Node node : moduleGraph.modules()) {
            for (SyntaxNode.Form form : node.program().forms()) {
                collectMutationSites(form, node.moduleId(), sourceSites);
            }
        }
        Map<MutationKey, SourceMutationSite> expected = new LinkedHashMap<>();
        for (SourceMutationSite site : sourceSites) {
            if (expected.put(site.key(), site) != null) {
                throw invalid("source contains duplicate mutation topology sites");
            }
        }
        Map<MutationKey, ResolvedMutation> actual = new LinkedHashMap<>();
        for (ResolvedMutation mutation : resolved.mutations()) {
            MutationKey key = new MutationKey(
                    mutation.moduleId(), mutation.span(), mutation.kind());
            if (actual.put(key, mutation) != null) {
                throw invalid("resolved mutation index contains duplicate source sites");
            }
        }
        if (!actual.keySet().equals(expected.keySet())) {
            throw invalid("resolved mutations do not cover exactly every source assignment");
        }

        Map<SourceSpan, List<SyntaxLink>> linksBySpan = new HashMap<>();
        for (SyntaxLink link : referenceLinks.values()) {
            linksBySpan.computeIfAbsent(link.span(), ignored -> new ArrayList<>()).add(link);
        }
        for (Map.Entry<MutationKey, SourceMutationSite> entry : expected.entrySet()) {
            SourceMutationSite site = entry.getValue();
            ResolvedMutation mutation = actual.get(entry.getKey());
            List<SyntaxLink> rootLinks = linksBySpan.getOrDefault(
                    site.rootReferenceSpan(), List.of());
            if (rootLinks.size() != 1) {
                throw invalid("source mutation root does not have one canonical reference link");
            }
            ReferenceId rootReferenceId = rootLinks.getFirst().referenceId().orElseThrow();
            ResolvedReference rootReference = resolved.reference(rootReferenceId).orElseThrow();
            ResolvedDeclaration rootDeclaration = rootReference.targetDeclaration()
                    .flatMap(resolved::declaration).orElseThrow(() -> invalid(
                            "source mutation root reference has no declaration"));
            boolean nominalSelf = resolved.nominals().stream()
                    .anyMatch(value -> value.self().equals(rootDeclaration.id()));
            boolean contextualSelf = resolved.scopeTree().require(rootDeclaration.scopeId()).kind()
                    == ScopeKind.LAMBDA;
            boolean constructorSelfMutation = mutation.kind() != MutationKind.REBINDING
                    && resolved.nominals().stream().anyMatch(value ->
                    value.self().equals(rootDeclaration.id())
                            && value.constructor().equals(rootReference.fromLambda()));
            boolean permittedImmutableSelfMutation = rootDeclaration.kind() == DeclarationKind.SELF
                    && (mutation.kind() == MutationKind.MEMBER_FIELD
                    && (nominalSelf || contextualSelf) || constructorSelfMutation);
            if (!mutation.rootReference().equals(Optional.of(rootReferenceId))
                    || !mutation.rootDeclaration().equals(rootDeclaration.id())
                    || rootReference.kind() != ReferenceKind.VALUE
                    || !rootReference.moduleId().equals(site.moduleId())
                    || !rootReference.span().equals(site.rootReferenceSpan())
                    || !rootDeclaration.moduleId().equals(site.moduleId())
                    || rootDeclaration.imported()
                    || rootDeclaration.bindingMutability() != BindingMutability.MUTABLE
                    && !permittedImmutableSelfMutation) {
                throw invalid("mutation root/reference/source assignment linkage is inconsistent");
            }
            if (mutation.kind() == MutationKind.MEMBER_FIELD) {
                var members = resolved.syntaxLinks().stream().filter(link -> link.span().equals(mutation.span())
                        && link.kind() == SyntaxLinkKind.ACCESS && link.declarationId().isPresent())
                        .map(link -> resolved.declaration(link.declarationId().orElseThrow()).orElseThrow()).toList();
                if (members.size() != 1 || members.getFirst().kind() != DeclarationKind.MEMBER) {
                    throw invalid("member mutation lacks one exact member link");
                }
                var field = members.getFirst();
                var definition = resolved.nominals().stream().filter(value -> value.members().contains(field.id()))
                        .findFirst().orElseThrow(() -> invalid("member mutation has no declaring schema"));
                if (!field.isMutable() && !(definition.self().equals(rootDeclaration.id())
                        && definition.constructor().isPresent()
                        && definition.constructor().equals(rootReference.fromLambda()))) {
                    throw invalid("immutable member mutation is not constructor initialization");
                }
            }
            rootDeclaration.externalBinding().ifPresent(binding -> {
                boolean allowed = mutation.kind() == MutationKind.REBINDING
                        ? binding.allowsRebinding() : binding.allowsAggregateMutation();
                if (!allowed) throw invalid("external mutation exceeds the supplied assignment authority");
            });
            if (rootDeclaration.kind() != DeclarationKind.SELF
                    && mutation.kind() == MutationKind.ARRAY_ELEMENT
                    && !selfAliasProvenance.permitsMutation(resolved, mutation)) {
                throw invalid("array-element mutation through a self alias is not constructor-owned");
            }
        }
    }

    private static void collectMutationSites(
            SyntaxNode.Form form,
            ModuleId moduleId,
            List<SourceMutationSite> destination) {
        if (form instanceof SyntaxNode.LetBinding declaration) {
            collectMutationSites(declaration.initializer(), moduleId, destination);
            return;
        }
        if (form instanceof SyntaxNode.NominalDeclaration declaration) {
            declaration.members().forEach(member -> member.initializer().ifPresent(initializer ->
                    collectMutationSites(initializer, moduleId, destination)));
            declaration.constructor().ifPresent(constructor ->
                    collectMutationSites(constructor.initializer(), moduleId, destination));
            return;
        }
        collectMutationSites((SyntaxNode.Expression) form, moduleId, destination);
    }

    private static void collectMutationSites(
            SyntaxNode.Expression expression,
            ModuleId moduleId,
            List<SourceMutationSite> destination) {
        if (expression instanceof SyntaxNode.Block block) {
            for (SyntaxNode.Form form : block.forms()) {
                collectMutationSites(form, moduleId, destination);
            }
            return;
        }
        if (expression instanceof SyntaxNode.Lambda lambda) {
            collectMutationSites(lambda.body(), moduleId, destination);
            return;
        }
        if (expression instanceof SyntaxNode.CompactLambda lambda) {
            collectMutationSites(lambda.body(), moduleId, destination);
            return;
        }
        if (expression instanceof SyntaxNode.Conditional conditional) {
            collectMutationSites(conditional.predicate(), moduleId, destination);
            collectMutationSites(conditional.thenExpression(), moduleId, destination);
            conditional.elseExpression().ifPresent(value ->
                    collectMutationSites(value, moduleId, destination));
            return;
        }
        if (expression instanceof SyntaxNode.Coalesce coalesce) {
            collectMutationSites(coalesce.value(), moduleId, destination);
            collectMutationSites(coalesce.fallback(), moduleId, destination);
            return;
        }
        if (expression instanceof SyntaxNode.Match match) {
            collectMutationSites(match.subject(), moduleId, destination);
            collectArmMutationSites(match.arms(), moduleId, destination);
            return;
        }
        if (expression instanceof SyntaxNode.Cond cond) {
            collectArmMutationSites(cond.arms(), moduleId, destination);
            return;
        }
        if (expression instanceof SyntaxNode.ExplicitConstruction construction) {
            for (SyntaxNode.Expression argument : construction.arguments().expressions()) {
                collectMutationSites(argument, moduleId, destination);
            }
            return;
        }
        if (expression instanceof SyntaxNode.PrefixAssignment assignment) {
            destination.add(mutationSite(moduleId, assignment.target()));
            collectMutationSites(assignment.target(), moduleId, destination);
            collectMutationSites(assignment.value(), moduleId, destination);
            return;
        }
        if (expression instanceof SyntaxNode.Reassignment assignment) {
            destination.add(mutationSite(moduleId, assignment.target()));
            collectMutationSites(assignment.target(), moduleId, destination);
            collectMutationSites(assignment.value(), moduleId, destination);
            return;
        }
        if (expression instanceof SyntaxNode.CallableCall call) {
            collectMutationSites(call.target(), moduleId, destination);
            for (SyntaxNode.Expression argument : call.arguments()) {
                collectMutationSites(argument, moduleId, destination);
            }
            return;
        }
        if (expression instanceof SyntaxNode.Range range) {
            collectMutationSites(range.start(), moduleId, destination);
            collectMutationSites(range.end(), moduleId, destination);
            collectMutationSites(range.step(), moduleId, destination);
            return;
        }
        if (expression instanceof SyntaxNode.DirectCall call) {
            call.receiver().ifPresent(value -> collectMutationSites(value, moduleId, destination));
            for (SyntaxNode.Expression argument : call.argumentExpressions()) {
                collectMutationSites(argument, moduleId, destination);
            }
            return;
        }
        if (expression instanceof SyntaxNode.MemberAccess access) {
            collectMutationSites(access.receiver(), moduleId, destination);
            return;
        }
        if (expression instanceof SyntaxNode.NamespaceDirectCall call) {
            for (SyntaxNode.Expression argument : call.argumentExpressions()) {
                collectMutationSites(argument, moduleId, destination);
            }
            return;
        }
        if (expression instanceof SyntaxNode.IndexAccess access) {
            collectMutationSites(access.receiver(), moduleId, destination);
            collectMutationSites(access.index(), moduleId, destination);
            return;
        }
        if (expression instanceof SyntaxNode.BracketApplication application) {
            collectMutationSites(application.target(), moduleId, destination);
            application.arguments().expressions().forEach(argument -> collectMutationSites(argument, moduleId, destination));
            return;
        }
        if (expression instanceof SyntaxNode.OperatorSExpression operator) {
            for (SyntaxNode.Expression operand : operator.operands()) {
                collectMutationSites(operand, moduleId, destination);
            }
            return;
        }
        if (expression instanceof SyntaxNode.OperatorBracket operator) {
            for (SyntaxNode.Expression operand : operator.operands()) {
                collectMutationSites(operand, moduleId, destination);
            }
            return;
        }
        if (expression instanceof SyntaxNode.ArrayLiteral array) {
            for (SyntaxNode.Expression element : array.elements()) {
                collectMutationSites(element, moduleId, destination);
            }
            return;
        }
        if (expression instanceof SyntaxNode.TupleLiteral tuple) {
            for (SyntaxNode.Expression element : tuple.elements()) {
                collectMutationSites(element, moduleId, destination);
            }
            return;
        }
        if (expression instanceof SyntaxNode.TypeConversion conversion) {
            collectMutationSites(conversion.value(), moduleId, destination);
            return;
        }
        if (!(expression instanceof SyntaxNode.Identifier)
                && !(expression instanceof SyntaxNode.Literal)
                && !(expression instanceof SyntaxNode.NamespaceMemberAccess)) {
            throw invalid("unrecognized source expression in mutation topology");
        }
    }
        static void collectArmMutationSites(
                List<SyntaxNode.MatchArm> arms,
                ModuleId moduleId,
                List<SourceMutationSite> destination) {
            for (SyntaxNode.MatchArm arm : arms) {
                arm.pattern().ifPresent(value -> collectMutationSites(value, moduleId, destination));
                arm.guard().ifPresent(value -> collectMutationSites(value, moduleId, destination));
                collectMutationSites(arm.result(), moduleId, destination);
            }
        }

    private static SourceMutationSite mutationSite(
            ModuleId moduleId,
            SyntaxNode.Expression target) {
        SyntaxNode.Identifier root = mutationRoot(target);
        if (root == null) {
            throw invalid("source mutation has no canonical identifier root");
        }
        MutationKind kind = target instanceof SyntaxNode.MemberAccess access && access.member().isIdentifier()
                ? MutationKind.MEMBER_FIELD : target instanceof SyntaxNode.IndexAccess
                ? MutationKind.ARRAY_ELEMENT : MutationKind.REBINDING;
        return new SourceMutationSite(moduleId, target.span(), kind, root.span());
    }

    private static SyntaxNode.Identifier mutationRoot(SyntaxNode.Expression expression) {
        if (expression instanceof SyntaxNode.Identifier identifier) {
            return identifier;
        }
        if (expression instanceof SyntaxNode.IndexAccess access) {
            return mutationRoot(access.receiver());
        }
        if (expression instanceof SyntaxNode.MemberAccess access) {
            return mutationRoot(access.receiver());
        }
        return null;
    }

    private static LogicalModuleId logicalModule(SyntaxNode.ImportDeclaration syntax) {
        try {
            return LogicalModuleId.fromImportPath(syntax.path());
        } catch (IllegalArgumentException failure) {
            throw invalid("source import contains an invalid logical module path");
        }
    }

    private static <T> Map<T, Integer> counts(List<T> values) {
        Map<T, Integer> counts = new HashMap<>();
        for (T value : values) {
            counts.merge(value, 1, Integer::sum);
        }
        return counts;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private record HeaderEdge(
            ModuleId source,
            LogicalModuleId logical,
            ModuleId target,
            SourceSpan importSpan) {
    }

    private record ImportShape(
            ModuleId ownerModule,
            String localName,
            SourceSpan localNameSpan,
            SourceSpan declarationSpan,
            SourceSpan importSpan,
            LogicalModuleId logicalModule,
            ModuleId targetModule,
            ImportBindingKind kind,
            Optional<String> importedName,
            Optional<String> aliasName,
            boolean reExport) {
    }

    private record ExportKey(ModuleId moduleId, String name) {
    }

    private record CaptureKey(LambdaId lambdaId, DeclarationId declarationId) {
    }

    private record MutationKey(
            ModuleId moduleId,
            SourceSpan targetSpan,
            MutationKind kind) {
    }

    private record SourceMutationSite(
            ModuleId moduleId,
            SourceSpan targetSpan,
            MutationKind kind,
            SourceSpan rootReferenceSpan) {
        private MutationKey key() {
            return new MutationKey(moduleId, targetSpan, kind);
        }
    }
}
