import org.junit.jupiter.api.Test;

import io.mindspice.lyra.compiler.ast.SyntaxProgram;
import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.Phase;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.diagnostic.Severity;
import io.mindspice.lyra.compiler.grammar.GrammarMatcher;
import io.mindspice.lyra.compiler.grammar.GrammarProgram;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.parse.Parser;
import io.mindspice.lyra.compiler.semantic.CaptureMode;
import io.mindspice.lyra.compiler.semantic.FunctionScc;
import io.mindspice.lyra.compiler.semantic.ResolvedCapture;
import io.mindspice.lyra.compiler.semantic.ResolvedDeclaration;
import io.mindspice.lyra.compiler.semantic.ResolvedExport;
import io.mindspice.lyra.compiler.semantic.ResolvedImportBinding;
import io.mindspice.lyra.compiler.semantic.ResolvedLambda;
import io.mindspice.lyra.compiler.semantic.ResolvedReference;
import io.mindspice.lyra.compiler.semantic.ResolvedScope;
import io.mindspice.lyra.compiler.semantic.ResolvedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.ReferenceKind;
import io.mindspice.lyra.compiler.semantic.ScopeKind;
import io.mindspice.lyra.compiler.semantic.SemanticResolver;
import io.mindspice.lyra.compiler.semantic.TypedExpression;
import io.mindspice.lyra.compiler.semantic.TypedExpressionKind;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.TypeChecker;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.ModuleRevision;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.BindingMutability;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Assertion-grade semantic-resolution tests. */
public final class SemanticResolverTest {
    private static final String NAMESPACE_ARGUMENT_LIBRARY =
            "let @pub collect :Fn<Fn<I32;I32>,Fn<I32;I32>,I32,"
                    + "Tuple<Array<I32>,I32>,Unit;I32> = "
                    + "(=> |compact general scalar aggregate effect| 0) "
                    + "let @pub identity :Fn<I32;I32> = (=> |value| value) "
                    + "let @pub apply :Fn<Fn<I32;I32>;I32> = (=> |function| 0) "
                    + "let @pub acceptArray :Fn<Array<I32>;I32> = (=> |values| 0) "
                    + "let @pub acceptUnit :Fn<Unit;I32> = (=> |value| 0) "
                    + "let @pub @mut shared :Array<I32> = Array[0]";

    @Test
    public void testSourceOrderReplacementAndOneNamespace() {
        ResolvedSemanticGraph graph = success(
                "let first = 1 let before = first let first = 2 let after = first");
        List<ResolvedDeclaration> firsts = graph.declarations().stream()
                .filter(declaration -> declaration.name().equals("first"))
                .toList();
        check(firsts.size() == 2, "private declarations may replace a prior private name");
        List<ResolvedReference> references = graph.references();
        check(references.size() == 2, "both identifier reads receive reference identities");
        check(references.get(0).declaration().equals(firsts.get(0).id()),
                "an earlier read retains the earlier declaration identity");
        check(references.get(1).declaration().equals(firsts.get(1).id()),
                "a later read selects the replacement declaration identity");

        failure("let read = later let later = 1", CompilerDiagnosticCodes.RESOLVE_FORWARD_REFERENCE);
        failure("let @pub value :I32 = 1 let value = 2",
                CompilerDiagnosticCodes.RESOLVE_PUBLIC_REDECLARATION);
        failure("let @pub value = 1", CompilerDiagnosticCodes.RESOLVE_SIGNATURE_REQUIRED);
        failure("let value :@mut I32 = 1", CompilerDiagnosticCodes.RESOLVE_INVALID_MODIFIER);
    }

    @Test
    public void testImportsAliasesSelectionsReexportsAndVisibility() {
        LogicalModuleId library = LogicalModuleId.parse("library");
        ModuleId mainId = ModuleId.path("main.lyra");
        ModuleId libraryId = ModuleId.path("library.lyra");
        ModuleGraph graph = graph(
                List.of(
                        module(mainId, "import library as lib import library->{visible as v} "
                                + "import @pub library->{visible as exported} "
                                + "let value = v let qualified = lib->:.visible"),
                        module(libraryId, "let @pub visible :I32 = 1 let hidden :I32 = 2")),
                mainId,
                List.of(new ModuleGraph.Edge(
                        mainId, library, libraryId, SourceSpan.of(mainId.sourceId(), 7, 21)),
                        new ModuleGraph.Edge(
                                mainId, library, libraryId, SourceSpan.of(mainId.sourceId(), 30, 44)),
                        new ModuleGraph.Edge(
                                mainId, library, libraryId, SourceSpan.of(mainId.sourceId(), 66, 80))),
                Map.of(library, libraryId));

        ResolvedSemanticGraph resolved = graphSuccess(SemanticResolver.resolve(graph));
        check(resolved.imports().size() == 3, "direct and selective imports retain every binding");
        ResolvedImportBinding alias = resolved.imports().get(0);
        check(alias.isModuleNamespace() && alias.localName().equals("lib")
                        && alias.aliasName().orElseThrow().equals("lib"),
                "a direct import alias binds an immutable module namespace");
        ResolvedImportBinding selected = resolved.imports().get(1);
        check(selected.isSelective() && selected.localName().equals("v")
                        && selected.importedName().orElseThrow().equals("visible"),
                "a selective alias retains local and origin names");
        check(resolved.exports().stream().anyMatch(export -> export.moduleId().equals(mainId)
                        && export.name().equals("exported") && export.isReExport()),
                "@pub selective imports publish explicit re-exports only");

        ModuleGraph replacementGraph = graph(
                List.of(
                        module(mainId, "import library->{visible} let before = visible "
                                + "let visible = 2 let after = visible"),
                        module(libraryId, "let @pub visible :I32 = 1")),
                mainId,
                List.of(new ModuleGraph.Edge(
                        mainId, library, libraryId, SourceSpan.of(mainId.sourceId(), 7, 21))),
                Map.of(library, libraryId));
        ResolvedSemanticGraph replacement = graphSuccess(SemanticResolver.resolve(replacementGraph));
        List<ResolvedDeclaration> visibleDeclarations = replacement.declarations().stream()
                .filter(declaration -> declaration.moduleId().equals(mainId)
                        && declaration.name().equals("visible")).toList();
        check(visibleDeclarations.size() == 2
                        && visibleDeclarations.getLast().replacementOf()
                        .orElseThrow().equals(visibleDeclarations.getFirst().id())
                        && replacement.references().stream().filter(reference ->
                        reference.name().equals("visible")).map(ResolvedReference::declaration)
                        .toList().equals(List.of(visibleDeclarations.getFirst().id(),
                                visibleDeclarations.getLast().id())),
                "a later private declaration replaces only later reads of an imported name");
        check(resolved.references().stream().anyMatch(reference ->
                        reference.kind().name().equals("NAMESPACE_MEMBER")
                                && reference.targetModule().orElseThrow().equals(libraryId)),
                "namespace member access has an explicit module/export link");

        ModuleGraph aliasValueGraph = graph(
                List.of(
                        module(mainId, "import library as lib let value = lib"),
                        module(libraryId, "let @pub visible :I32 = 1")),
                mainId,
                List.of(new ModuleGraph.Edge(
                        mainId, library, libraryId, SourceSpan.of(mainId.sourceId(), 7, 21))),
                Map.of(library, libraryId));
        failure(aliasValueGraph, CompilerDiagnosticCodes.RESOLVE_INVALID_MODULE_ACCESS);

        ModuleGraph hiddenGraph = graph(
                List.of(
                        module(mainId, "import library->{hidden} let value = 1"),
                        module(libraryId, "let @pub visible :I32 = 1 let hidden :I32 = 2")),
                mainId,
                List.of(new ModuleGraph.Edge(
                        mainId, library, libraryId, SourceSpan.of(mainId.sourceId(), 7, 21))),
                Map.of(library, libraryId));
        failure(hiddenGraph, CompilerDiagnosticCodes.RESOLVE_IMPORT_NOT_PUBLIC);

        failure("import dependency->{x} import dependency->{x} let value = 1",
                CompilerDiagnosticCodes.RESOLVE_MISSING_MODULE);
    }

    @Test
    public void testTypedLambdaPredeclarationAndFunctionSccs() {
        ResolvedSemanticGraph self = success(
                "let self :Fn<;I32> = (=> | | ::self[])");
        check(self.lambdas().size() == 1 && self.lambdas().getFirst().signatureComplete(),
                "a complete expected Fn predeclares a lambda signature");
        check(self.functionLinkage().components().stream().anyMatch(FunctionScc::recursive),
                "self recursion is represented by a recursive signature SCC");
        check(self.captures().isEmpty(),
                "self-recursive function slots are linked rather than captured eagerly");

        ResolvedSemanticGraph mutual = success(
                "let first :Fn<;I32> = (=> | | ::second[]) "
                        + "let second :Fn<;I32> = (=> | | ::first[])");
        check(mutual.functionLinkage().components().stream()
                        .anyMatch(component -> component.recursive() && component.declarations().size() == 2),
                "forward and mutual calls link one deterministic function SCC");
        check(mutual.captures().isEmpty(),
                "mutually recursive function slots do not become eager value captures");

        ModuleId aId = ModuleId.path("a.lyra");
        ModuleId bId = ModuleId.path("b.lyra");
        LogicalModuleId a = LogicalModuleId.parse("a");
        LogicalModuleId b = LogicalModuleId.parse("b");
        ModuleGraph crossModule = graph(
                List.of(
                        module(aId, "import b->{g} let @pub f :Fn<;I32> = (=> | | ::g[])"),
                        module(bId, "import a->{f} let @pub g :Fn<;I32> = (=> | | ::f[])")),
                aId,
                List.of(
                        new ModuleGraph.Edge(aId, b, bId, SourceSpan.of(aId.sourceId(), 7, 13)),
                        new ModuleGraph.Edge(bId, a, aId, SourceSpan.of(bId.sourceId(), 7, 13))),
                Map.of(a, aId, b, bId));
        check(SemanticResolver.resolve(crossModule).optionalValue().orElseThrow()
                        .functionLinkage().components().stream()
                        .anyMatch(component -> component.recursive() && component.declarations().size() == 2),
                "imported function signatures link mutual recursion across module SCCs");

        check(success("let inferred = (=> :I32 |value :I32| value)")
                        .declarations().stream()
                        .filter(declaration -> declaration.name().equals("inferred"))
                        .findFirst().orElseThrow().inferredContract().isPresent(),
                "complete inline annotations populate the inferred function contract slot");
        success("let contextual :Fn<I32;I32> = (=> |value| value)");
        ResolvedSemanticGraph mutableParameter = success(
                "let f :Fn<@mut Array<I32>;Unit> = "
                        + "(=> |@mut values :Array<I32>| (values[0] := 1))");
        ResolvedDeclaration function = declaration(mutableParameter, "f");
        check(function.functionSignature().orElseThrow().canonicalSpelling()
                        .equals("Fn<@mutArray<I32>;Unit>"),
                "@mut parameter binding permission is retained in the function signature");
        check(success("let nullable = (=> @nil :String | | #NIL)")
                        .declarations().stream()
                        .filter(value -> value.name().equals("nullable"))
                        .findFirst().orElseThrow().functionSignature().orElseThrow()
                        .canonicalSpelling().equals("Fn<;@nilString>"),
                "@nil return modifiers are retained in extracted signatures");
        check(mutableParameter.mutations().size() == 1
                        && mutableParameter.declarations().stream()
                        .anyMatch(value -> value.name().equals("values") && value.isMutable()),
                "a mutable parameter authorizes aggregate mutation");
        failure("let incomplete = (=> |value :I32| value)",
                CompilerDiagnosticCodes.RESOLVE_SIGNATURE_REQUIRED);
        failure("let mismatch :Fn<I32;I32> = (=> :String |value :I32| value)",
                CompilerDiagnosticCodes.RESOLVE_INVALID_SIGNATURE);
    }

    @Test
    public void testCapturesAndSharedMutableCells() {
        ResolvedSemanticGraph graph = success(
                "let immutable :I32 = 1 let @mut mutable :I32 = 0 "
                        + "let first :Fn<;I32> = (=> | | immutable) "
                        + "let second :Fn<;I32> = (=> | | mutable)");
        check(graph.captures().size() == 2, "outer values used by lambdas become captures");
        ResolvedCapture immutable = graph.captures().stream()
                .filter(capture -> capture.declaration().equals(declaration(graph, "immutable").id()))
                .findFirst().orElseThrow();
        ResolvedCapture mutable = graph.captures().stream()
                .filter(capture -> capture.declaration().equals(declaration(graph, "mutable").id()))
                .findFirst().orElseThrow();
        check(immutable.mode() == CaptureMode.IMMUTABLE_VALUE && immutable.sharedCellId().isEmpty(),
                "immutable captures retain selected values without cells");
        check(mutable.mode() == CaptureMode.SHARED_MUTABLE_CELL
                        && mutable.sharedCellId().orElseThrow().equals(declaration(graph, "mutable").id()),
                "captured @mut bindings use their declaration identity as one shared cell");

        ResolvedSemanticGraph nested = success(
                "let @mut value :I32 = 0 let outer :Fn<;Fn<;I32>> = "
                        + "(=> | | (=> | | value))");
        check(nested.captures().size() == 2
                        && nested.captures().stream().allMatch(ResolvedCapture::isSharedCell),
                "nested closures receive transitive shared mutable capture cells");

        ResolvedSemanticGraph nestedFunction = success(
                "let @mut outer :Fn<;I32> = (=> | | 1) "
                        + "let maker :Fn<;Fn<;I32>> = (=> | | (=> | | ::outer[]))");
        check(nestedFunction.captures().stream()
                        .anyMatch(capture -> capture.isSharedCell()
                                && capture.declaration().equals(declaration(nestedFunction, "outer").id())),
                "nested closures capture enclosing predeclared functions through shared mutable cells");

        ResolvedSemanticGraph twoClosures = success(
                "let @mut value :I32 = 0 let a :Fn<;I32> = (=> | | value) "
                        + "let b :Fn<;I32> = (=> | | value)");
        List<ResolvedCapture> cells = twoClosures.captures().stream()
                .filter(ResolvedCapture::isSharedCell).toList();
        check(cells.size() == 2 && cells.get(0).sharedCellId().equals(cells.get(1).sharedCellId()),
                "separate closures capturing one mutable binding share one cell identity");
    }

    @Test
    public void testMutationAuthorizationAndImportedOwnership() {
        success("let @mut value :I32 = 0 let set :Fn<;Unit> = (=> | | (value := 1))");
        ResolvedSemanticGraph arrayMutation = success(
                "let @mut values :Array<I32> = Array<I32>[0] "
                        + "let set :Fn<;Unit> = (=> | | (values[0] := 1))");
        check(arrayMutation.mutations().size() == 1
                        && arrayMutation.mutations().getFirst().isArrayElement()
                        && arrayMutation.mutations().getFirst().declaration()
                        .equals(declaration(arrayMutation, "values").id()),
                "array mutation records and authorizes its mutable root declaration");
        failure("let value :I32 = 0 let set :Fn<;Unit> = (=> | | (value := 1))",
                CompilerDiagnosticCodes.RESOLVE_MUTATION_NOT_ALLOWED);

        LogicalModuleId library = LogicalModuleId.parse("library");
        ModuleId mainId = ModuleId.path("main.lyra");
        ModuleId libraryId = ModuleId.path("library.lyra");
        ModuleGraph graph = graph(
                List.of(
                        module(mainId, "import library->{value} let set :Fn<;Unit> = (=> | | (value := 1))"),
                        module(libraryId, "let @pub @mut value :I32 = 0")),
                mainId,
                List.of(new ModuleGraph.Edge(
                        mainId, library, libraryId, SourceSpan.of(mainId.sourceId(), 7, 21))),
                Map.of(library, libraryId));
        failure(graph, CompilerDiagnosticCodes.RESOLVE_IMPORTED_MUTATION);
    }

    @Test
    public void testAccessScopesAndExpectedAnonymousLambdas() {
        ResolvedSemanticGraph graph = success(
                "let fn :Fn<Fn<I32;I32>;I32> = (=> |callback :Fn<I32;I32>| (callback 1)) "
                        + "let callback :Fn<I32;I32> = (=> |x| x) "
                        + "let called = (fn (=> |x| x)) "
                        + "let direct = ::fn[callback] "
                        + "let array = Array<I32>[1 2] let indexed = array[0] "
                        + "let field = array:.length let converted = I32[1] "
                        + "let conditional = (#T value -> value : 0) "
                        + "let @nil label :String = #NIL let coalesced = (label : \"fallback\")");
        check(graph.scopeTree().scopes().stream()
                        .anyMatch(scope -> scope.kind().name().equals("MODULE"))
                        && graph.scopeTree().scopes().stream()
                        .anyMatch(scope -> scope.kind().name().equals("LAMBDA"))
                        && graph.scopeTree().scopes().stream()
                        .anyMatch(scope -> scope.kind().name().equals("BLOCK")) == false,
                "module and lambda scopes are allocated without inventing a block for scalar bodies");
        check(graph.lambdas().size() == 3,
                "named and anonymous lambda expressions each retain one lambda identity");
        success("let apply :Fn<Fn<I32;I32>;I32> = "
                + "(=> |function :Fn<I32;I32>| (function 1)) "
                + "let result = (apply |value| value)");
        check(graph.references().stream().anyMatch(reference ->
                        reference.kind().name().equals("DIRECT_CALL_TARGET"))
                        && graph.references().stream().anyMatch(reference ->
                        reference.kind().name().equals("VALUE")),
                "callable and direct call target distinctions survive resolution");
        check(graph.syntaxLinks().stream().anyMatch(link ->
                        link.accessKind().orElse(null) == io.mindspice.lyra.compiler.semantic.AccessKind.MEMBER_VALUE),
                "member access publishes an explicit access link");
        check(graph.declarations().stream().anyMatch(declaration ->
                        declaration.kind().name().equals("PREDICATE_BINDING")),
                "conditional predicate bindings are lexical declarations");
        PhaseResult<ResolvedSemanticGraph> unresolved = SemanticResolver.resolve(
                singleGraph("let value = missing"));
        check(unresolved.diagnostics().getFirst().primarySpan().equals(
                        SourceSpan.of(SourceId.path("test.lyra"), 12, 19)),
                "unresolved-name diagnostics point at the complete identifier span");
        check(unresolved.diagnostics().getFirst().relatedSpans().isEmpty(),
                "a name with no declaration has no fabricated related span");

        failure("let value = later let later = 1", CompilerDiagnosticCodes.RESOLVE_FORWARD_REFERENCE);
        check(SemanticResolver.resolve(singleGraph("let value = later let later = 1"))
                        .diagnostics().getFirst().relatedSpans().getFirst().span()
                        .equals(SourceSpan.of(SourceId.path("test.lyra"), 22, 27)),
                "forward-read diagnostics retain the later declaration related span");
    }

    @Test
    public void testNamespaceDirectCallCollectsEveryNestedArgumentExactlyOnce() {
        String source = "import library "
                + "let @mut values :Array<I32> = Array[0] "
                + "let captured :I32 = 7 "
                + "let collected = library->::collect["
                + "|compact| captured, "
                + "(=> |general| general), "
                + "{ let local :I32 = 1 local }, "
                + "Tuple[Array[1] { let element :I32 = 2 element }], "
                + "(values[0] := 3)] "
                + "let namespaceValue = library->:.identity "
                + "let namespaceDirect = library->::identity[1] "
                + "let localFunction :Fn<I32;I32> = (=> |value| value) "
                + "let direct = ::localFunction[2] "
                + "let callable = (localFunction 3)";
        ModuleGraph moduleGraph = namespaceArgumentGraph(source);
        ModuleId mainId = moduleGraph.rootModule();
        ResolvedSemanticGraph resolved = graphSuccess(SemanticResolver.resolve(moduleGraph));

        List<ResolvedLambda> mainLambdas = resolved.lambdas().stream()
                .filter(lambda -> lambda.moduleId().equals(mainId))
                .toList();
        check(mainLambdas.size() == 3,
                "compact, general, and control lambdas each receive one collected identity");
        for (int index = 1; index < mainLambdas.size(); index++) {
            check(mainLambdas.get(index - 1).id().compareTo(mainLambdas.get(index).id()) < 0
                            && mainLambdas.get(index - 1).span().startOffset()
                            < mainLambdas.get(index).span().startOffset(),
                    "nested lambda identities retain source collection order");
        }

        List<ResolvedScope> mainScopes = resolved.scopeTree().scopes().stream()
                .filter(scope -> scope.moduleId().equals(mainId))
                .toList();
        check(mainScopes.size() == 6
                        && mainScopes.stream().filter(scope -> scope.kind() == ScopeKind.MODULE).count() == 1
                        && mainScopes.stream().filter(scope -> scope.kind() == ScopeKind.LAMBDA).count() == 3
                        && mainScopes.stream().filter(scope -> scope.kind() == ScopeKind.BLOCK).count() == 2,
                "namespace arguments collect each lambda and block scope once without duplicates");
        check(resolved.declarations().stream()
                        .filter(declaration -> declaration.moduleId().equals(mainId)).count() == 14,
                "namespace argument collection creates each import, let, local, and parameter once");
        ResolvedCapture capture = resolved.captures().stream()
                .filter(value -> value.moduleId().equals(mainId))
                .findFirst().orElseThrow();
        check(resolved.captures().size() == 1
                        && capture.declaration().equals(declaration(resolved, "captured").id())
                        && source.substring(capture.span().startOffset(), capture.span().endOffset())
                        .equals("captured"),
                "a nested argument lambda owns its exact source capture once");
        check(resolved.mutations().size() == 1
                        && source.substring(
                                resolved.mutations().getFirst().span().startOffset(),
                                resolved.mutations().getFirst().span().endOffset()).equals("values[0]"),
                "the nested mutation retains its exact source target and is collected once");

        List<ResolvedReference> mainReferences = resolved.references().stream()
                .filter(reference -> reference.moduleId().equals(mainId))
                .toList();
        check(mainReferences.size() == 14
                        && mainReferences.stream().filter(reference ->
                        reference.kind() == ReferenceKind.MODULE_NAMESPACE).count() == 3
                        && mainReferences.stream().filter(reference ->
                        reference.kind() == ReferenceKind.NAMESPACE_DIRECT_CALL).count() == 2
                        && mainReferences.stream().filter(reference ->
                        reference.kind() == ReferenceKind.NAMESPACE_MEMBER).count() == 1
                        && mainReferences.stream().filter(reference ->
                        reference.kind() == ReferenceKind.DIRECT_CALL_TARGET).count() == 1
                        && mainReferences.stream().filter(reference ->
                        reference.kind() == ReferenceKind.VALUE).count() == 7,
                "nested arguments and sibling call forms resolve once with unchanged reference kinds");

        TypedSemanticGraph typed = typedSuccess(TypeChecker.check(resolved));
        List<TypedExpression> mainExpressions = typed.expressions().stream()
                .filter(expression -> expression.span().sourceId().equals(mainId.sourceId()))
                .toList();
        TypedExpression collected = mainExpressions.stream()
                .filter(expression -> expression.kind() == TypedExpressionKind.NAMESPACE_DIRECT_CALL
                        && expression.children().size() == 5)
                .findFirst().orElseThrow();
        check(collected.children().stream().map(TypedExpression::kind).toList().equals(List.of(
                        TypedExpressionKind.LAMBDA,
                        TypedExpressionKind.LAMBDA,
                        TypedExpressionKind.BLOCK,
                        TypedExpressionKind.TUPLE_LITERAL,
                        TypedExpressionKind.REBINDING)),
                "namespace direct-call arguments remain in exact source evaluation order");
        check(collected.children().getFirst().captureIds().equals(List.of(capture.id())),
                "typed namespace arguments retain the resolver-owned capture identity");
        check(collected.children().stream().map(argument -> source.substring(
                        argument.span().startOffset(), argument.span().endOffset())).toList().equals(List.of(
                        "|compact| captured",
                        "(=> |general| general)",
                        "{ let local :I32 = 1 local }",
                        "Tuple[Array[1] { let element :I32 = 2 element }]",
                        "(values[0] := 3)")),
                "nested namespace arguments retain their exact source spans");
        check(mainExpressions.stream().filter(expression ->
                        expression.kind() == TypedExpressionKind.LAMBDA).count() == 3
                        && mainExpressions.stream().filter(expression ->
                        expression.kind() == TypedExpressionKind.BLOCK).count() == 2
                        && mainExpressions.stream().filter(expression ->
                        expression.kind() == TypedExpressionKind.ARRAY_LITERAL).count() == 2
                        && mainExpressions.stream().filter(expression ->
                        expression.kind() == TypedExpressionKind.TUPLE_LITERAL).count() == 1
                        && mainExpressions.stream().filter(expression ->
                        expression.kind() == TypedExpressionKind.REBINDING).count() == 1,
                "typing visits every collected nested form exactly once");
        check(mainExpressions.stream().filter(expression ->
                        expression.kind() == TypedExpressionKind.NAMESPACE_DIRECT_CALL).count() == 2
                        && mainExpressions.stream().filter(expression ->
                        expression.kind() == TypedExpressionKind.NAMESPACE_MEMBER_ACCESS).count() == 1
                        && mainExpressions.stream().filter(expression ->
                        expression.kind() == TypedExpressionKind.DIRECT_CALL).count() == 1
                        && mainExpressions.stream().filter(expression ->
                        expression.kind() == TypedExpressionKind.CALLABLE_CALL).count() == 1,
                "namespace value, namespace direct, local direct, and callable call kinds remain distinct");
    }

    @Test
    public void testNamespaceDirectCallNestedArgumentFailuresStayStructured() {
        String duplicateLambda = "import library "
                + "let bad = library->::apply[|same same| same]";
        PhaseResult<ResolvedSemanticGraph> duplicateResult = SemanticResolver.resolve(
                namespaceArgumentGraph(duplicateLambda));
        int firstParameter = duplicateLambda.indexOf("same");
        int secondParameter = duplicateLambda.indexOf("same", firstParameter + "same".length());
        Diagnostic duplicate = structuredFailure(
                duplicateResult,
                CompilerDiagnosticCodes.RESOLVE_DUPLICATE_PARAMETER,
                SourceSpan.of(SourceId.path("namespace_argument_main.lyra"),
                        secondParameter, secondParameter + "same".length()),
                Phase.RESOLVE);
        check(duplicate.relatedSpans().size() == 1
                        && duplicate.relatedSpans().getFirst().span().equals(SourceSpan.of(
                        SourceId.path("namespace_argument_main.lyra"),
                        firstParameter, firstParameter + "same".length())),
                "a collected compact-lambda failure retains the first parameter witness");

        String invalidBlock = "import library "
                + "let bad = library->::identity[{ missing }]";
        PhaseResult<ResolvedSemanticGraph> blockResult = SemanticResolver.resolve(
                namespaceArgumentGraph(invalidBlock));
        int missing = invalidBlock.indexOf("missing");
        Diagnostic block = structuredFailure(
                blockResult,
                CompilerDiagnosticCodes.RESOLVE_UNRESOLVED_NAME,
                SourceSpan.of(SourceId.path("namespace_argument_main.lyra"),
                        missing, missing + "missing".length()),
                Phase.RESOLVE);
        check(block.relatedSpans().isEmpty(),
                "an unresolved name inside a collected block has no fabricated witness");

        String invalidMutation = "import library "
                + "let values :Array<I32> = Array[0] "
                + "let bad = library->::acceptUnit[(values[0] := 1)]";
        PhaseResult<ResolvedSemanticGraph> mutationResult = SemanticResolver.resolve(
                namespaceArgumentGraph(invalidMutation));
        int mutationTarget = invalidMutation.lastIndexOf("values[0]");
        Diagnostic mutation = structuredFailure(
                mutationResult,
                CompilerDiagnosticCodes.RESOLVE_MUTATION_NOT_ALLOWED,
                SourceSpan.of(SourceId.path("namespace_argument_main.lyra"),
                        mutationTarget, mutationTarget + "values[0]".length()),
                Phase.RESOLVE);
        int declarationName = invalidMutation.indexOf("values");
        check(mutation.relatedSpans().size() == 1
                        && mutation.relatedSpans().getFirst().span().equals(SourceSpan.of(
                        SourceId.path("namespace_argument_main.lyra"),
                        declarationName, declarationName + "values".length())),
                "an invalid nested mutation retains its immutable binding witness");

        String importedMutation = "import library->{shared} import library "
                + "let bad = library->::acceptUnit[(shared[0] := 1)]";
        PhaseResult<ResolvedSemanticGraph> importedMutationResult = SemanticResolver.resolve(
                namespaceArgumentGraph(importedMutation));
        int importedTarget = importedMutation.lastIndexOf("shared[0]");
        Diagnostic imported = structuredFailure(
                importedMutationResult,
                CompilerDiagnosticCodes.RESOLVE_IMPORTED_MUTATION,
                SourceSpan.of(SourceId.path("namespace_argument_main.lyra"),
                        importedTarget, importedTarget + "shared[0]".length()),
                Phase.RESOLVE);
        int importedBinding = importedMutation.indexOf("shared");
        check(imported.relatedSpans().size() == 1
                        && imported.relatedSpans().getFirst().span().equals(SourceSpan.of(
                        SourceId.path("namespace_argument_main.lyra"),
                        importedBinding, importedBinding + "shared".length())),
                "namespace argument collection preserves imported aggregate mutation witnesses");

        String invalidAggregate = "import library "
                + "let bad = library->::acceptArray[Array<I32>[\"wrong\"]]";
        PhaseResult<ResolvedSemanticGraph> aggregateResolved = SemanticResolver.resolve(
                namespaceArgumentGraph(invalidAggregate));
        ResolvedSemanticGraph resolved = graphSuccess(aggregateResolved);
        PhaseResult<TypedSemanticGraph> aggregateResult = TypeChecker.check(resolved);
        int wrong = invalidAggregate.indexOf("\"wrong\"");
        Diagnostic aggregate = structuredFailure(
                aggregateResult,
                CompilerDiagnosticCodes.TYPE_MISMATCH,
                SourceSpan.of(SourceId.path("namespace_argument_main.lyra"),
                        wrong, wrong + "\"wrong\"".length()),
                Phase.TYPE);
        check(aggregate.relatedSpans().isEmpty(),
                "an invalid nested aggregate reports its exact element without a partial graph");
    }

    @Test
    public void testIntrinsicModuleExportsParticipateInResolution() {
        ModuleId mainId = ModuleId.path("main.lyra");
        ModuleId intrinsicId = ModuleId.uri(URI.create("lyra:intrinsic/std/io"));
        LogicalModuleId stdIo = LogicalModuleId.STD_IO;
        ModuleGraph graph = graph(
                List.of(
                        module(mainId, "import std->io as io import std->io->{println} "
                                + "let call = io->::println[\"ok\"] let value = println"),
                        module(intrinsicId, "")),
                mainId,
                List.of(new ModuleGraph.Edge(
                        mainId, stdIo, intrinsicId, SourceSpan.of(mainId.sourceId(), 7, 14)),
                        new ModuleGraph.Edge(
                                mainId, stdIo, intrinsicId, SourceSpan.of(mainId.sourceId(), 23, 30))),
                Map.of(stdIo, intrinsicId));
        ResolvedSemanticGraph resolved = graphSuccess(SemanticResolver.resolve(graph));
        check(resolved.exports().stream().filter(export -> export.moduleId().equals(intrinsicId)).count() == 5,
                "the reserved std->io module exposes its pinned semantic signatures");
        check(resolved.references().stream().anyMatch(reference ->
                        reference.kind().name().equals("NAMESPACE_DIRECT_CALL")),
                "intrinsic namespace calls retain normal namespace access links");
    }

    @Test
    public void testChainedReexportOrigins() {
        ModuleId aId = ModuleId.path("a.lyra");
        ModuleId bId = ModuleId.path("b.lyra");
        ModuleId cId = ModuleId.path("c.lyra");
        LogicalModuleId a = LogicalModuleId.parse("a");
        LogicalModuleId b = LogicalModuleId.parse("b");
        ModuleGraph graph = graph(
                List.of(
                        module(aId, "let @pub f :Fn<I32;I32> = (=> |x :I32| x)"),
                        module(bId, "import @pub a->{f as g}"),
                        module(cId, "import @pub b->{g as h}")),
                cId,
                List.of(
                        new ModuleGraph.Edge(bId, a, aId, SourceSpan.of(bId.sourceId(), 15, 16)),
                        new ModuleGraph.Edge(cId, b, bId, SourceSpan.of(cId.sourceId(), 15, 16))),
                Map.of(a, aId, b, bId));
        ResolvedSemanticGraph resolved = graphSuccess(SemanticResolver.resolve(graph));
        ResolvedExport origin = resolved.export(aId, "f").orElseThrow();
        ResolvedExport middle = resolved.export(bId, "g").orElseThrow();
        ResolvedExport finalExport = resolved.export(cId, "h").orElseThrow();
        check(origin.exportId().isPresent() && middle.originExport().equals(origin.exportId())
                        && finalExport.originExport().equals(origin.exportId()),
                "chained re-exports retain the ultimate origin export identity");
    }

    @Test
    public void testDeterministicImmutablePublishedArtifact() {
        String source = "let @mut value :I32 = 0 let f :Fn<;I32> = (=> | | value)";
        ResolvedSemanticGraph first = success(source);
        ResolvedSemanticGraph second = success(source);
        check(first.equals(second)
                        && first.declarations().equals(second.declarations())
                        && first.references().equals(second.references())
                        && first.captures().equals(second.captures())
                        && first.scopeTree().scopes().equals(second.scopeTree().scopes()),
                "repeated resolution allocates deterministic IDs and graph records");
        expectUnsupported(() -> first.declarations().clear());
        expectUnsupported(() -> first.scopeTree().scopes().clear());
        expectUnsupported(() -> first.functionLinkage().signatures().clear());
    }

    private static ResolvedDeclaration declaration(ResolvedSemanticGraph graph, String name) {
        return graph.declarations().stream()
                .filter(declaration -> declaration.name().equals(name))
                .findFirst().orElseThrow();
    }

    private static void failure(String source, io.mindspice.lyra.compiler.diagnostic.DiagnosticCode code) {
        failure(singleGraph(source), code);
    }

    private static void failure(
            ModuleGraph graph,
            io.mindspice.lyra.compiler.diagnostic.DiagnosticCode code) {
        PhaseResult<ResolvedSemanticGraph> result = SemanticResolver.resolve(graph);
        check(result instanceof PhaseResult.Failure<?>,
                "expected semantic failure " + code + ": " + render(result));
        check(result.optionalValue().isEmpty(), "failed resolution publishes no semantic graph");
        check(result.diagnostics().getFirst().code().equals(code),
                "unexpected semantic diagnostic: " + render(result));
        check(result.diagnostics().getFirst().primarySpan().sourceId() != null,
                "semantic diagnostics retain a complete primary source span");
    }

    private static ResolvedSemanticGraph success(String source) {
        return graphSuccess(SemanticResolver.resolve(singleGraph(source)));
    }

    @SuppressWarnings("unchecked")
    private static ResolvedSemanticGraph graphSuccess(PhaseResult<ResolvedSemanticGraph> result) {
        if (!(result instanceof PhaseResult.Success<?> success)) {
            throw new AssertionError("expected semantic success: " + render(result));
        }
        return ((PhaseResult.Success<ResolvedSemanticGraph>) success).value();
    }

    @SuppressWarnings("unchecked")
    private static TypedSemanticGraph typedSuccess(PhaseResult<TypedSemanticGraph> result) {
        if (!(result instanceof PhaseResult.Success<?> success)) {
            throw new AssertionError("expected typed success: " + render(result));
        }
        return ((PhaseResult.Success<TypedSemanticGraph>) success).value();
    }

    private static Diagnostic structuredFailure(
            PhaseResult<?> result,
            io.mindspice.lyra.compiler.diagnostic.DiagnosticCode code,
            SourceSpan primarySpan,
            Phase phase) {
        check(result instanceof PhaseResult.Failure<?>,
                "expected structured failure " + code + ": " + render(result));
        check(result.optionalValue().isEmpty(),
                "failed nested namespace argument publishes no partial artifact");
        check(result.diagnostics().size() == 1,
                "nested namespace argument reports one first blocking diagnostic");
        Diagnostic diagnostic = result.diagnostics().getFirst();
        check(diagnostic.code().equals(code)
                        && diagnostic.phase() == phase
                        && diagnostic.severity() == Severity.ERROR
                        && diagnostic.primarySpan().equals(primarySpan),
                "unexpected nested namespace argument diagnostic: " + render(result));
        return diagnostic;
    }

    private static ModuleGraph namespaceArgumentGraph(String mainSource) {
        ModuleId mainId = ModuleId.path("namespace_argument_main.lyra");
        ModuleId libraryId = ModuleId.path("library.lyra");
        LogicalModuleId library = LogicalModuleId.parse("library");
        ModuleGraph.Node main = module(mainId, mainSource);
        ModuleGraph.Node dependency = module(libraryId, NAMESPACE_ARGUMENT_LIBRARY);
        check(!main.program().imports().isEmpty(),
                "namespace-argument fixture requires an import");
        List<ModuleGraph.Edge> edges = main.program().imports().stream()
                .map(importDeclaration -> new ModuleGraph.Edge(
                        mainId,
                        library,
                        libraryId,
                        importDeclaration.path().span()))
                .toList();
        return graph(
                List.of(main, dependency),
                mainId,
                edges,
                Map.of(library, libraryId));
    }

    private static ModuleGraph singleGraph(String source) {
        ModuleId moduleId = ModuleId.path("test.lyra");
        return graph(List.of(module(moduleId, source)), moduleId, List.of(), Map.of());
    }

    private static ModuleGraph graph(
            List<ModuleGraph.Node> nodes,
            ModuleId root,
            List<ModuleGraph.Edge> edges,
            Map<LogicalModuleId, ModuleId> logicalModules) {
        return CanonicalModuleGraph.create(root, nodes, edges, logicalModules);
    }

    private static ModuleGraph.Node module(ModuleId moduleId, String source) {
        SourceSnapshot snapshot = snapshot(moduleId.sourceId(), source);
        SyntaxProgram syntax = parse(snapshot);
        return new ModuleGraph.Node(
                moduleId,
                moduleId.isPath()
                        ? Optional.of(LogicalModuleId.fromSourceId(moduleId.sourceId()))
                        : Optional.empty(),
                snapshot,
                syntax,
                ModuleRevision.compute(snapshot));
    }

    @SuppressWarnings("unchecked")
    private static SyntaxProgram parse(SourceSnapshot snapshot) {
        PhaseResult<LexedSource> lexed = Lexer.lex(snapshot);
        if (!(lexed instanceof PhaseResult.Success<?> lexedSuccess)) {
            throw new AssertionError("lex failed: " + render(lexed));
        }
        LexedSource lexical = ((PhaseResult.Success<LexedSource>) lexedSuccess).value();
        PhaseResult<GrammarProgram> grammar = GrammarMatcher.match(lexical);
        if (!(grammar instanceof PhaseResult.Success<?> grammarSuccess)) {
            throw new AssertionError("grammar failed: " + render(grammar));
        }
        GrammarProgram matched = ((PhaseResult.Success<GrammarProgram>) grammarSuccess).value();
        PhaseResult<SyntaxProgram> parsed = Parser.parse(lexical, matched);
        if (!(parsed instanceof PhaseResult.Success<?> parsedSuccess)) {
            throw new AssertionError("parse failed: " + render(parsed));
        }
        return ((PhaseResult.Success<SyntaxProgram>) parsedSuccess).value();
    }

    @SuppressWarnings("unchecked")
    private static SourceSnapshot snapshot(SourceId sourceId, String source) {
        PhaseResult<SourceSnapshot> captured = SourceSnapshot.capture(
                sourceId,
                PhysicalSourceKey.uri(URI.create("memory:" + sourceId.value().replace('/', '_'))),
                source.getBytes(StandardCharsets.UTF_8));
        if (!(captured instanceof PhaseResult.Success<?> success)) {
            throw new AssertionError("source capture failed: " + render(captured));
        }
        return ((PhaseResult.Success<SourceSnapshot>) success).value();
    }

    private static String render(PhaseResult<?> result) {
        return result.diagnostics().stream().map(Diagnostic::render).toList().toString();
    }

    private static void expectUnsupported(Runnable action) {
        try {
            action.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("expected immutable collection");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
