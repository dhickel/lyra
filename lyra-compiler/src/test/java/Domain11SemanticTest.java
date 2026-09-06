import org.junit.jupiter.api.Test;

import io.mindspice.lyra.compiler.ast.SyntaxProgram;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.grammar.GrammarMatcher;
import io.mindspice.lyra.compiler.grammar.GrammarProgram;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.parse.Parser;
import io.mindspice.lyra.compiler.semantic.CaptureMode;
import io.mindspice.lyra.compiler.semantic.FunctionScc;
import io.mindspice.lyra.compiler.semantic.InitializationPlan;
import io.mindspice.lyra.compiler.semantic.TypedExpression;
import io.mindspice.lyra.compiler.semantic.TypedExpressionKind;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.SemanticTestSupport;
import io.mindspice.lyra.compiler.semantic.TypeChecker;
import io.mindspice.lyra.compiler.semantic.ResolvedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.SemanticResolver;
import io.mindspice.lyra.compiler.ir.IrValidator;
import io.mindspice.lyra.compiler.ir.TypedIrBuilder;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.ModuleRevision;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.BindingContract;
import io.mindspice.lyra.compiler.types.BindingMutability;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.PrimitiveType;
import io.mindspice.lyra.compiler.types.TupleType;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Focused assertion-grade coverage for the domain-11 semantic completion. */
public final class Domain11SemanticTest {
    @Test
    public void testArraysTuplesIndexingAliasesAndIr() {
        TypedSemanticGraph typed = success(
                "let array = Array[1 2] "
                        + "let alias = array "
                        + "let @mut writable = alias "
                        + "let changed = (writable[0] := 3) "
                        + "let tuple = Tuple<I32,String>[1 \"x\"] "
                        + "let first = tuple:.0 let second = tuple:.1 "
                        + "let text = \"a\\uD83D\\uDE00\" let codeUnit = text[1] let length = text:.length");
        check(contract(typed, "array").valueType().equals(ArrayType.of(PrimitiveType.I32)),
                "untyped integer arrays default to Array<I32>");
        check(contract(typed, "alias").valueType().equals(ArrayType.of(PrimitiveType.I32))
                        && contract(typed, "writable").isMutable(),
                "array aliases preserve one value identity while mutability remains binding-local");
        check(contract(typed, "tuple").valueType()
                        .equals(TupleType.of(List.of(PrimitiveType.I32, PrimitiveType.STRING))),
                "explicit tuple shapes retain ordered member types");
        check(contract(typed, "first").valueType() == PrimitiveType.I32
                        && contract(typed, "second").valueType() == PrimitiveType.STRING
                        && contract(typed, "codeUnit").valueType() == PrimitiveType.CHAR
                        && contract(typed, "length").valueType() == PrimitiveType.I32,
                "tuple fields and UTF-16 String indexing/length have exact result types");
        check(typed.expressions().stream().anyMatch(value ->
                        value.kind() == TypedExpressionKind.INDEX_ACCESS)
                        && typed.failureSites().stream().anyMatch(value ->
                        value.failureCode().equals("LYR-BOUNDS")),
                "indexing has a closed typed node and a later bounds failure site");
        check(typed.mutations().size() == 1
                        && typed.mutations().getFirst().isArrayElement()
                        && typed.mutations().getFirst().rootReference().isPresent()
                        && typed.declaration(typed.mutations().getFirst().rootDeclaration())
                        .orElseThrow().name().equals("writable"),
                "array mutation preserves the mutable root declaration and reference through indexing");
        check(IrValidator.isValid(phaseSuccess(TypedIrBuilder.lower(typed))),
                "aggregate construction, indexing, and array mutation lower to valid closed IR");
        failure("let array = Array[1 2] let bad = (array[0] := 3)", "LYC-RESOLVE-021");
        failure("let @mut root :Array<I32> = Array[1] let alias = root "
                + "let bad = (alias[0] := 2)", "LYC-RESOLVE-021");
        failure("let bad = Tuple<I32,String>[1]", "LYC-TYPE-006");
        failure("let bad = Tuple[1 #NIL]", "LYC-TYPE-005");
        failure("let bad = \"x\"[(- 1)]", "LYC-TYPE-013");
        failure("let left = Tuple[1] let right = Tuple[1] let bad = (eq? left right)",
                "LYC-TYPE-008");
    }

    @Test
    public void testMutableArrayParametersAndImportedOwnership() {
        TypedSemanticGraph parameter = success(
                "let update :Fn<@mut Array<I32>;Unit> = "
                        + "(=> |@mut values :Array<I32>| (values[0] := 1))");
        check(parameter.mutations().size() == 1
                        && parameter.mutations().getFirst().isArrayElement()
                        && parameter.declaration(parameter.mutations().getFirst().rootDeclaration())
                        .orElseThrow().name().equals("values"),
                "a mutable array parameter authorizes mutation through its parameter root");

        ModuleId main = ModuleId.path("main.lyra");
        ModuleId library = ModuleId.path("library.lyra");
        LogicalModuleId libraryLogical = LogicalModuleId.parse("library");
        ModuleGraph imported = graph(
                List.of(
                        module(main, "import library->{values} "
                                + "let bad = (values[0] := 1)"),
                        module(library, "let @pub @mut values :Array<I32> = Array[0]")),
                main,
                List.of(new ModuleGraph.Edge(
                        main, libraryLogical, library, SourceSpan.of(main.sourceId(), 0, 1))),
                Map.of(libraryLogical, library));
        PhaseResult<ResolvedSemanticGraph> result = SemanticResolver.resolve(imported);
        check(result instanceof PhaseResult.Failure<?> && result.optionalValue().isEmpty()
                        && result.diagnostics().getFirst().code().value().equals("LYC-RESOLVE-022"),
                "an imported exported mutable array remains read-only to the importing module");

        ModuleGraph importedAlias = graph(
                List.of(
                        module(main, "import library->{values} "
                                + "let @mut alias :Array<I32> = values "
                                + "let bad = (alias[0] := 1)"),
                        module(library, "let @pub @mut values :Array<I32> = Array[0]")),
                main,
                List.of(new ModuleGraph.Edge(
                        main, libraryLogical, library, SourceSpan.of(main.sourceId(), 0, 1))),
                Map.of(libraryLogical, library));
        PhaseResult<ResolvedSemanticGraph> aliasResult = SemanticResolver.resolve(importedAlias);
        check(aliasResult instanceof PhaseResult.Failure<?> && aliasResult.optionalValue().isEmpty()
                        && aliasResult.diagnostics().getFirst().code().value()
                        .equals("LYC-RESOLVE-022")
                        && !aliasResult.diagnostics().getFirst().relatedSpans().isEmpty()
                        && aliasResult.diagnostics().getFirst().relatedSpans().getFirst().span()
                        .sourceId().equals(main.sourceId()),
                "a local @mut alias cannot launder imported array ownership");

        ModuleGraph mutableArgumentAlias = graph(
                List.of(
                        module(main, "import library->{values} "
                                + "let update :Fn<@mut Array<I32>;Unit> = "
                                + "(=> |items| (items[0] := 1)) "
                                + "let @mut alias :Array<I32> = values "
                                + "let bad = (update alias)"),
                        module(library, "let @pub @mut values :Array<I32> = Array[0]")),
                main,
                List.of(new ModuleGraph.Edge(
                        main, libraryLogical, library, SourceSpan.of(main.sourceId(), 0, 1))),
                Map.of(libraryLogical, library));
        PhaseResult<?> argumentResult = ownershipResult(mutableArgumentAlias);
        check(argumentResult instanceof PhaseResult.Failure<?>
                        && argumentResult.optionalValue().isEmpty()
                        && argumentResult.diagnostics().getFirst().code().value()
                        .equals("LYC-RESOLVE-022"),
                "an imported array alias cannot be passed as a local @mut capability");

        ModuleGraph narrowedAlias = graph(
                List.of(
                        module(main, "import library->{values} "
                                + "let result = (values narrowed -> { "
                                + "let @mut alias :Array<I32> = narrowed "
                                + "(alias[0] := 1) } : ())"),
                        module(library,
                                "let @pub @mut @nil values :Array<I32> = #NIL")),
                main,
                List.of(new ModuleGraph.Edge(
                        main, libraryLogical, library, SourceSpan.of(main.sourceId(), 0, 1))),
                Map.of(libraryLogical, library));
        PhaseResult<ResolvedSemanticGraph> narrowedResult = SemanticResolver.resolve(narrowedAlias);
        check(narrowedResult instanceof PhaseResult.Failure<?>
                        && narrowedResult.optionalValue().isEmpty()
                        && narrowedResult.diagnostics().getFirst().code().value()
                        .equals("LYC-RESOLVE-022"),
                "predicate narrowing preserves imported aggregate ownership: "
                        + render(narrowedResult));

        ModuleGraph localContainerMutation = graph(
                List.of(
                        module(main, "import library->{values} "
                                + "let @mut outer :Array<Array<I32>> = Array[values] "
                                + "let replacement :Array<I32> = Array[1] "
                                + "let changed = (outer[0] := replacement)"),
                        module(library, "let @pub @mut values :Array<I32> = Array[0]")),
                main,
                List.of(new ModuleGraph.Edge(
                        main, libraryLogical, library, SourceSpan.of(main.sourceId(), 0, 1))),
                Map.of(libraryLogical, library));
        check(typeResult(localContainerMutation) instanceof PhaseResult.Success<?>,
                "a local outer array remains mutable without mutating its imported element");

        ModuleGraph nestedImportedMutation = graph(
                List.of(
                        module(main, "import library->{values} "
                                + "let @mut outer :Array<Array<I32>> = Array[values] "
                                + "let bad = (outer[0][0] := 1)"),
                        module(library, "let @pub @mut values :Array<I32> = Array[0]")),
                main,
                List.of(new ModuleGraph.Edge(
                        main, libraryLogical, library, SourceSpan.of(main.sourceId(), 0, 1))),
                Map.of(libraryLogical, library));
        PhaseResult<ResolvedSemanticGraph> nestedResult = SemanticResolver.resolve(
                nestedImportedMutation);
        check(nestedResult instanceof PhaseResult.Failure<?>
                        && nestedResult.diagnostics().getFirst().code().value()
                        .equals("LYC-RESOLVE-022"),
                "aggregate containment cannot launder imported nested array identity");

        ModuleGraph insertedImportedMutation = graph(
                List.of(
                        module(main, "import library->{values} "
                                + "let @mut outer :Array<Array<I32>> = Array[Array[0]] "
                                + "let changed = (outer[0] := values) "
                                + "let bad = (outer[0][0] := 1)"),
                        module(library, "let @pub @mut values :Array<I32> = Array[0]")),
                main,
                List.of(new ModuleGraph.Edge(
                        main, libraryLogical, library, SourceSpan.of(main.sourceId(), 0, 1))),
                Map.of(libraryLogical, library));
        PhaseResult<?> insertedResult = ownershipResult(
                insertedImportedMutation);
        check(insertedResult instanceof PhaseResult.Failure<?>
                        && insertedResult.diagnostics().getFirst().code().value()
                        .equals("LYC-RESOLVE-022"),
                "array-element assignment propagates imported nested ownership");
    }

    @Test
    public void testAggregateProvenanceFlowAndTupleNestedMutation() {
        TypedSemanticGraph tuple = success(
                "let @mut tuple :Tuple<Array<I32>> = Tuple[Array[0]] "
                        + "let changed = (tuple:.0[0] := 1)");
        check(tuple.mutations().size() == 1
                        && tuple.mutations().getFirst().rootDeclaration().equals(
                        tuple.declarations().stream()
                                .filter(value -> value.name().equals("tuple"))
                                .findFirst().orElseThrow().id())
                        && tuple.mutations().getFirst().isArrayElement(),
                "a mutable tuple root authorizes mutation of an identity-bearing array field");
        check(IrValidator.isValid(phaseSuccess(TypedIrBuilder.lower(tuple))),
                "tuple-field array mutation retains a valid closed IR root link");
        failure("let tuple :Tuple<Array<I32>> = Tuple[Array[0]] "
                        + "let bad = (tuple:.0[0] := 1)",
                "LYC-RESOLVE-021");
        failure("let @mut tuple :Tuple<Array<I32>> = Tuple[Array[0]] "
                        + "let bad = (tuple:.0 := Array[1])",
                "LYC-TYPE-012");

        ModuleId main = ModuleId.path("provenance_main.lyra");
        ModuleId library = ModuleId.path("provenance_library.lyra");
        LogicalModuleId libraryLogical = LogicalModuleId.parse("provenance_library");
        List<ModuleGraph.Edge> edge = List.of(new ModuleGraph.Edge(
                main, libraryLogical, library, SourceSpan.of(main.sourceId(), 0, 1)));
        Map<LogicalModuleId, ModuleId> logicalModules = Map.of(libraryLogical, library);

        ModuleGraph narrowed = graph(
                List.of(
                        module(main, "import provenance_library->{values} "
                                + "let @mut alias = (values narrowed -> narrowed : Array[0]) "
                                + "let changed = (alias[0] := 1)"),
                        module(library,
                                "let @pub @mut @nil values :Array<I32> = #NIL")),
                main, edge, logicalModules);
        PhaseResult<ResolvedSemanticGraph> narrowedResult = SemanticResolver.resolve(narrowed);
        check(narrowedResult instanceof PhaseResult.Failure<?>
                        && narrowedResult.optionalValue().isEmpty()
                        && narrowedResult.diagnostics().getFirst().code().value()
                        .equals("LYC-RESOLVE-022")
                        && !narrowedResult.diagnostics().getFirst().relatedSpans().isEmpty(),
                "imported aggregate provenance survives a predicate branch with no resolver-time type");

        ModuleGraph called = graph(
                List.of(
                        module(main, "import provenance_library->{values} "
                                + "let id :Fn<Array<I32>;Array<I32>> = "
                                + "(=> |value :Array<I32>| value) "
                                + "let @mut alias = (id values) "
                                + "let changed = (alias[0] := 1)"),
                        module(library,
                                "let @pub @mut values :Array<I32> = Array[0]")),
                main, edge, logicalModules);
        PhaseResult<?> calledResult = ownershipResult(called);
        check(calledResult instanceof PhaseResult.Failure<?>
                        && calledResult.optionalValue().isEmpty()
                        && calledResult.diagnostics().getFirst().code().value()
                        .equals("LYC-RESOLVE-022")
                        && !calledResult.diagnostics().getFirst().relatedSpans().isEmpty(),
                "identity-preserving calls retain imported aggregate ownership");

        ModuleGraph importedCalled = graph(
                List.of(
                        module(main, "import provenance_library->{values id} "
                                + "let @mut alias = (id values) "
                                + "let changed = (alias[0] := 1)"),
                        module(library,
                                "let @pub @mut values :Array<I32> = Array[0] "
                                        + "let @pub id :Fn<Array<I32>;Array<I32>> = "
                                        + "(=> |value :Array<I32>| value)")),
                main, edge, logicalModules);
        PhaseResult<?> importedCalledResult = ownershipResult(importedCalled);
        check(importedCalledResult instanceof PhaseResult.Failure<?>
                        && importedCalledResult.optionalValue().isEmpty()
                        && importedCalledResult.diagnostics().getFirst().code().value()
                        .equals("LYC-RESOLVE-022"),
                "identity-preserving imported functions retain aggregate ownership");

        ModuleGraph freshCall = graph(
                List.of(
                        module(main, "import provenance_library->{values} "
                                + "let make :Fn<Array<I32>;Array<I32>> = "
                                + "(=> |ignored :Array<I32>| Array[0]) "
                                + "let @mut alias = (make values) "
                                + "let changed = (alias[0] := 1)"),
                        module(library,
                                "let @pub @mut values :Array<I32> = Array[0]")),
                main, edge, logicalModules);
        check(typeResult(freshCall) instanceof PhaseResult.Success<?>,
                "a statically fresh aggregate call does not receive blanket imported taint");

        ModuleGraph nestedUnknown = graph(
                List.of(
                        module(main, "import provenance_library->{values} "
                                + "let @mut outer = (values narrowed -> Array[narrowed] : Array[Array[0]]) "
                                + "let changed = (outer[0][0] := 1)"),
                        module(library,
                                "let @pub @mut @nil values :Array<I32> = #NIL")),
                main, edge, logicalModules);
        PhaseResult<ResolvedSemanticGraph> nestedUnknownResult =
                SemanticResolver.resolve(nestedUnknown);
        check(nestedUnknownResult instanceof PhaseResult.Failure<?>
                        && nestedUnknownResult.optionalValue().isEmpty()
                        && nestedUnknownResult.diagnostics().getFirst().code().value()
                        .equals("LYC-RESOLVE-022"),
                "unknown resolver-time aggregate depth remains protected through nested indexing");

        ModuleGraph localAfterImported = graph(
                List.of(
                        module(main, "import provenance_library->{values} "
                                + "let @mut alias = values "
                                + "let local :Array<I32> = Array[0] "
                                + "let replaced = (alias := local) "
                                + "let changed = (alias[0] := 1)"),
                        module(library,
                                "let @pub @mut values :Array<I32> = Array[0]")),
                main, edge, logicalModules);
        check(typeResult(localAfterImported) instanceof PhaseResult.Success<?>,
                "a local aggregate reassigned after an imported value uses current provenance only");

        ModuleGraph importedAfterLocal = graph(
                List.of(
                        module(main, "import provenance_library->{values} "
                                + "let local :Array<I32> = Array[0] "
                                + "let @mut alias = local "
                                + "let replaced = (alias := values) "
                                + "let changed = (alias[0] := 1)"),
                        module(library,
                                "let @pub @mut values :Array<I32> = Array[0]")),
                main, edge, logicalModules);
        PhaseResult<ResolvedSemanticGraph> importedAfterLocalResult =
                SemanticResolver.resolve(importedAfterLocal);
        check(importedAfterLocalResult instanceof PhaseResult.Failure<?>
                        && importedAfterLocalResult.optionalValue().isEmpty()
                        && importedAfterLocalResult.diagnostics().getFirst().code().value()
                        .equals("LYC-RESOLVE-022")
                        && !importedAfterLocalResult.diagnostics().getFirst().relatedSpans().isEmpty(),
                "reassigning a local aggregate from an imported value restores the ownership diagnostic");
    }

    @Test
    public void testPhase11ValidatorCounterexamples() {
        ModuleId higherOrderMain = ModuleId.path("counterexample_higher_order_main.lyra");
        ModuleId higherOrderLibrary = ModuleId.path("counterexample_higher_order_library.lyra");
        LogicalModuleId higherOrderLogical = LogicalModuleId.parse("counterexample_higher_order_library");
        ModuleGraph higherOrder = graph(
                List.of(
                        module(higherOrderMain, "import counterexample_higher_order_library->{values id} "
                                + "let funcs :Array<Fn<Array<I32>;Array<I32>>> = Array[id] "
                                + "let @mut returned :Array<I32> = (funcs[0] values) "
                                + "let changed = (returned[0] := 1)"),
                        module(higherOrderLibrary,
                                "let @pub @mut values :Array<I32> = Array[0] "
                                        + "let @pub id :Fn<Array<I32>;Array<I32>> = "
                                        + "(=> |input :Array<I32>| input)")),
                higherOrderMain,
                List.of(new ModuleGraph.Edge(
                        higherOrderMain, higherOrderLogical, higherOrderLibrary,
                        SourceSpan.of(higherOrderMain.sourceId(), 0, 1))),
                Map.of(higherOrderLogical, higherOrderLibrary));
        PhaseResult<?> higherOrderResult = ownershipResult(higherOrder);
        check(higherOrderResult instanceof PhaseResult.Failure<?> && higherOrderResult.optionalValue().isEmpty()
                        && higherOrderResult.diagnostics().getFirst().code().value()
                        .equals("LYC-RESOLVE-022"),
                "function-valued aggregate indexing preserves imported argument provenance: "
                        + render(higherOrderResult));
        ModuleGraph tupleHigherOrder = graph(
                List.of(
                        module(higherOrderMain, "import counterexample_higher_order_library->{values id} "
                                + "let funcs :Tuple<Fn<Array<I32>;Array<I32>>> = Tuple[id] "
                                + "let @mut returned :Array<I32> = (funcs:.0 values) "
                                + "let changed = (returned[0] := 1)"),
                        module(higherOrderLibrary,
                                "let @pub @mut values :Array<I32> = Array[0] "
                                        + "let @pub id :Fn<Array<I32>;Array<I32>> = "
                                        + "(=> |input :Array<I32>| input)")),
                higherOrderMain,
                List.of(new ModuleGraph.Edge(
                        higherOrderMain, higherOrderLogical, higherOrderLibrary,
                        SourceSpan.of(higherOrderMain.sourceId(), 0, 1))),
                Map.of(higherOrderLogical, higherOrderLibrary));
        PhaseResult<?> tupleHigherOrderResult = ownershipResult(tupleHigherOrder);
        check(tupleHigherOrderResult instanceof PhaseResult.Failure<?>
                        && tupleHigherOrderResult.optionalValue().isEmpty()
                        && tupleHigherOrderResult.diagnostics().getFirst().code().value()
                        .equals("LYC-RESOLVE-022"),
                "function-valued tuple projections preserve imported argument provenance: "
                        + render(tupleHigherOrderResult));

        ModuleId branchMain = ModuleId.path("counterexample_branch_main.lyra");
        ModuleId branchLibrary = ModuleId.path("counterexample_branch_library.lyra");
        LogicalModuleId branchLogical = LogicalModuleId.parse("counterexample_branch_library");
        ModuleGraph branchJoin = graph(
                List.of(
                        module(branchMain, "import counterexample_branch_library->{values} "
                                + "let @mut alias :Array<I32> = values "
                                + "let local :Array<I32> = Array[1] "
                                + "let ignored = (#T -> { alias := local } : ()) "
                                + "let changed = (alias[0] := 2)"),
                        module(branchLibrary, "let @pub @mut values :Array<I32> = Array[0]")),
                branchMain,
                List.of(new ModuleGraph.Edge(
                        branchMain, branchLogical, branchLibrary,
                        SourceSpan.of(branchMain.sourceId(), 0, 1))),
                Map.of(branchLogical, branchLibrary));
        PhaseResult<ResolvedSemanticGraph> branchResult = SemanticResolver.resolve(branchJoin);
        check(branchResult instanceof PhaseResult.Failure<?> && branchResult.optionalValue().isEmpty()
                        && branchResult.diagnostics().getFirst().code().value()
                        .equals("LYC-RESOLVE-022"),
                "conditional rebinding joins imported and local aggregate origins: "
                        + render(branchResult));

        TypedSemanticGraph localBranches = success(
                "let @mut alias :Array<I32> = Array[0] "
                        + "let first :Array<I32> = Array[1] let second :Array<I32> = Array[2] "
                        + "let ignored = (#T -> { alias := first } : { alias := second }) "
                        + "let changed = (alias[0] := 3)");
        check(contract(localBranches, "alias").isMutable(),
                "conditional joins keep a local aggregate mutable when every branch is local");

        ModuleId rebindMain = ModuleId.path("counterexample_rebind_main.lyra");
        ModuleId rebindLibrary = ModuleId.path("counterexample_rebind_library.lyra");
        LogicalModuleId rebindLogical = LogicalModuleId.parse("counterexample_rebind_library");
        ModuleGraph rebindImported = graph(
                List.of(
                        module(rebindMain, "import counterexample_rebind_library->{values id} "
                                + "let fresh :Fn<Array<I32>;Array<I32>> = "
                                + "(=> |input :Array<I32>| Array[0]) "
                                + "let @mut f :Fn<Array<I32>;Array<I32>> = fresh "
                                + "let changed = (f := id) "
                                + "let @mut returned :Array<I32> = (f values) "
                                + "let changedReturned = (returned[0] := 1)"),
                        module(rebindLibrary,
                                "let @pub @mut values :Array<I32> = Array[0] "
                                        + "let @pub id :Fn<Array<I32>;Array<I32>> = "
                                        + "(=> |input :Array<I32>| input)")),
                rebindMain,
                List.of(new ModuleGraph.Edge(
                        rebindMain, rebindLogical, rebindLibrary,
                        SourceSpan.of(rebindMain.sourceId(), 0, 1))),
                Map.of(rebindLogical, rebindLibrary));
        PhaseResult<?> rebindImportedResult = ownershipResult(rebindImported);
        check(rebindImportedResult instanceof PhaseResult.Failure<?>
                        && rebindImportedResult.optionalValue().isEmpty()
                        && rebindImportedResult.diagnostics().getFirst().code().value()
                        .equals("LYC-RESOLVE-022"),
                "function rebinding uses the current imported identity-preserving candidate: "
                        + render(rebindImportedResult));
        ModuleGraph rebindFresh = graph(
                List.of(
                        module(rebindMain, "import counterexample_rebind_library->{values id} "
                                + "let fresh :Fn<Array<I32>;Array<I32>> = "
                                + "(=> |input :Array<I32>| Array[0]) "
                                + "let @mut f :Fn<Array<I32>;Array<I32>> = id "
                                + "let changed = (f := fresh) "
                                + "let @mut returned :Array<I32> = (f values) "
                                + "let changedReturned = (returned[0] := 1)"),
                        module(rebindLibrary,
                                "let @pub @mut values :Array<I32> = Array[0] "
                                        + "let @pub id :Fn<Array<I32>;Array<I32>> = "
                                        + "(=> |input :Array<I32>| input)")),
                rebindMain,
                List.of(new ModuleGraph.Edge(
                        rebindMain, rebindLogical, rebindLibrary,
                        SourceSpan.of(rebindMain.sourceId(), 0, 1))),
                Map.of(rebindLogical, rebindLibrary));
        check(typeResult(rebindFresh) instanceof PhaseResult.Success<?>,
                "reassigning an identity-preserving function to a fresh function is not over-rejected");
        check(typeResult(singleGraph(
                        "let fresh :Fn<Array<I32>;Array<I32>> = "
                                + "(=> |input :Array<I32>| Array[0]) "
                                + "let funcs :Array<Fn<Array<I32>;Array<I32>>> = Array[fresh] "
                                + "let @mut returned :Array<I32> = (funcs[0] Array[1]) "
                                + "let changed = (returned[0] := 1)"))
                        instanceof PhaseResult.Success<?>,
                "fresh aggregate function results remain legal through aggregate indexing");

        ModuleId closureMain = ModuleId.path("counterexample_closure_main.lyra");
        ModuleId closureLibrary = ModuleId.path("counterexample_closure_library.lyra");
        LogicalModuleId closureLogical = LogicalModuleId.parse("counterexample_closure_library");
        ModuleGraph closureResult = graph(
                List.of(
                        module(closureMain, "import counterexample_closure_library->{values} "
                                + "let @mut alias :Array<I32> = values "
                                + "let update :Fn<;Unit> = (=> | | { "
                                + "let local :Array<I32> = Array[0] alias := local }) "
                                + "let bad = (alias[0] := 1)"),
                        module(closureLibrary, "let @pub @mut values :Array<I32> = Array[0]")),
                closureMain,
                List.of(new ModuleGraph.Edge(
                        closureMain, closureLogical, closureLibrary,
                        SourceSpan.of(closureMain.sourceId(), 0, 1))),
                Map.of(closureLogical, closureLibrary));
        PhaseResult<ResolvedSemanticGraph> closureResolution = SemanticResolver.resolve(closureResult);
        check(closureResolution instanceof PhaseResult.Failure<?>
                        && closureResolution.optionalValue().isEmpty()
                        && closureResolution.diagnostics().getFirst().code().value()
                        .equals("LYC-RESOLVE-022"),
                "non-executed closure-body rebinding does not rewrite enclosing aggregate flow: "
                        + render(closureResolution));

        ModuleId tupleMain = ModuleId.path("counterexample_tuple_main.lyra");
        ModuleId tupleDependency = ModuleId.path("counterexample_tuple_dependency.lyra");
        LogicalModuleId tupleLogical = LogicalModuleId.parse("counterexample_tuple_dependency");
        ModuleGraph tupleCycle = graph(
                List.of(
                        module(tupleMain, "import counterexample_tuple_dependency "
                                + "let @pub seed :I32 = 1 "
                                + "let original :Fn<;I32> = (=> | | 1) "
                                + "let replacement :Fn<;I32> = "
                                + "(=> | | counterexample_tuple_dependency->:.value) "
                                + "let @mut functions :Array<Fn<;I32>> = Array[original] "
                                + "let tuple :Tuple<Array<Fn<;I32>>> = Tuple[functions] "
                                + "let @mut tupleAlias :Tuple<Array<Fn<;I32>>> = tuple "
                                + "let changed = (tupleAlias:.0[0] := replacement) "
                                + "let @pub value :I32 = (functions[0])"),
                        module(tupleDependency,
                                "import counterexample_tuple_main "
                                        + "let @pub value :I32 = counterexample_tuple_main->:.seed")),
                tupleMain,
                List.of(
                        new ModuleGraph.Edge(tupleMain, tupleLogical, tupleDependency,
                                SourceSpan.of(tupleMain.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(tupleDependency,
                                LogicalModuleId.parse("counterexample_tuple_main"), tupleMain,
                                SourceSpan.of(tupleDependency.sourceId(), 0, 1))),
                Map.of(tupleLogical, tupleDependency,
                        LogicalModuleId.parse("counterexample_tuple_main"), tupleMain));
        PhaseResult<TypedSemanticGraph> tupleCycleResult = typeResult(tupleCycle);
        check(tupleCycleResult instanceof PhaseResult.Failure<?> && tupleCycleResult.optionalValue().isEmpty()
                        && tupleCycleResult.diagnostics().getFirst().code().value()
                        .equals("LYC-MODULE-004"),
                "tuple-container aliases retain callable member replacement effects: "
                        + render(tupleCycleResult));
        ModuleGraph unrelatedTupleMember = graph(
                List.of(
                        module(tupleMain, "import counterexample_tuple_dependency "
                                + "let @pub seed :I32 = 1 "
                                + "let original :Fn<;I32> = (=> | | 1) "
                                + "let replacement :Fn<;I32> = "
                                + "(=> | | counterexample_tuple_dependency->:.value) "
                                + "let @mut first :Array<Fn<;I32>> = Array[original] "
                                + "let @mut second :Array<Fn<;I32>> = Array[original] "
                                + "let tuple :Tuple<Array<Fn<;I32>>,Array<Fn<;I32>>> = Tuple[first second] "
                                + "let @mut tupleAlias :Tuple<Array<Fn<;I32>>,Array<Fn<;I32>>> = tuple "
                                + "let changed = (tupleAlias:.0[0] := replacement) "
                                + "let @pub value :I32 = (second[0])"),
                        module(tupleDependency,
                                "import counterexample_tuple_main "
                                        + "let @pub value :I32 = counterexample_tuple_main->:.seed")),
                tupleMain,
                List.of(
                        new ModuleGraph.Edge(tupleMain, tupleLogical, tupleDependency,
                                SourceSpan.of(tupleMain.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(tupleDependency,
                                LogicalModuleId.parse("counterexample_tuple_main"), tupleMain,
                                SourceSpan.of(tupleDependency.sourceId(), 0, 1))),
                Map.of(tupleLogical, tupleDependency,
                        LogicalModuleId.parse("counterexample_tuple_main"), tupleMain));
        PhaseResult<TypedSemanticGraph> unrelatedTupleMemberResult =
                typeResult(unrelatedTupleMember);
        check(unrelatedTupleMemberResult instanceof PhaseResult.Success<?>,
                "tuple member replacement does not taint a distinct callable member: "
                        + render(unrelatedTupleMemberResult));

        ModuleId callMain = ModuleId.path("counterexample_call_main.lyra");
        ModuleId callDependency = ModuleId.path("counterexample_call_dependency.lyra");
        LogicalModuleId callLogical = LogicalModuleId.parse("counterexample_call_dependency");
        ModuleGraph callCycle = graph(
                List.of(
                        module(callMain, "import counterexample_call_dependency "
                                + "let @pub seed :I32 = 1 "
                                + "let original :Fn<;I32> = (=> | | 1) "
                                + "let replacement :Fn<;I32> = "
                                + "(=> | | counterexample_call_dependency->:.value) "
                                + "let source :Array<Fn<;I32>> = Array[original] "
                                + "let maker :Fn<Array<Fn<;I32>>;Array<Fn<;I32>>> = "
                                + "(=> |input :Array<Fn<;I32>>| input) "
                                + "let @mut alias :Array<Fn<;I32>> = (maker source) "
                                + "let changed = (alias[0] := replacement) "
                                + "let @pub value :I32 = (source[0])"),
                        module(callDependency,
                                "import counterexample_call_main "
                                        + "let @pub value :I32 = counterexample_call_main->:.seed")),
                callMain,
                List.of(
                        new ModuleGraph.Edge(callMain, callLogical, callDependency,
                                SourceSpan.of(callMain.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(callDependency,
                                LogicalModuleId.parse("counterexample_call_main"), callMain,
                                SourceSpan.of(callDependency.sourceId(), 0, 1))),
                Map.of(callLogical, callDependency,
                        LogicalModuleId.parse("counterexample_call_main"), callMain));
        PhaseResult<TypedSemanticGraph> callCycleResult = typeResult(callCycle);
        check(callCycleResult instanceof PhaseResult.Failure<?> && callCycleResult.optionalValue().isEmpty()
                        && callCycleResult.diagnostics().getFirst().code().value()
                        .equals("LYC-MODULE-004"),
                "identity-preserving aggregate calls retain callable member effects: "
                        + render(callCycleResult));

        String directCallSource = "import counterexample_call_dependency "
                + "let @pub seed :I32 = 1 "
                + "let original :Fn<;I32> = (=> | | 1) "
                + "let replacement :Fn<;I32> = "
                + "(=> | | counterexample_call_dependency->:.value) "
                + "let source :Array<Fn<;I32>> = Array[original] "
                + "let maker :Fn<Array<Fn<;I32>>;Array<Fn<;I32>>> = "
                + "(=> |input :Array<Fn<;I32>>| input) "
                + "let @mut alias :Array<Fn<;I32>> = ::maker[source] "
                + "let changed = (alias[0] := replacement) "
                + "let @pub value :I32 = (source[0])";
        ModuleGraph directCallCycle = graph(
                List.of(
                        module(callMain, directCallSource),
                        module(callDependency,
                                "import counterexample_call_main "
                                        + "let @pub value :I32 = counterexample_call_main->:.seed")),
                callMain,
                List.of(
                        new ModuleGraph.Edge(callMain, callLogical, callDependency,
                                SourceSpan.of(callMain.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(callDependency,
                                LogicalModuleId.parse("counterexample_call_main"), callMain,
                                SourceSpan.of(callDependency.sourceId(), 0, 1))),
                Map.of(callLogical, callDependency,
                        LogicalModuleId.parse("counterexample_call_main"), callMain));
        PhaseResult<TypedSemanticGraph> directCallCycleResult = typeResult(directCallCycle);
        check(directCallCycleResult instanceof PhaseResult.Failure<?>
                        && directCallCycleResult.optionalValue().isEmpty()
                        && directCallCycleResult.diagnostics().getFirst().code().value()
                        .equals("LYC-MODULE-004"),
                "direct aggregate identity-preserving calls retain callable member effects: "
                        + render(directCallCycleResult));

        ModuleId namespaceFactory = ModuleId.path("counterexample_namespace_factory.lyra");
        LogicalModuleId namespaceFactoryLogical = LogicalModuleId.parse("counterexample_namespace_factory");
        ModuleGraph namespaceCallCycle = graph(
                List.of(
                        module(callMain, "import counterexample_call_dependency "
                                + "import counterexample_namespace_factory "
                                + "let @pub seed :I32 = 1 "
                                + "let original :Fn<;I32> = (=> | | 1) "
                                + "let replacement :Fn<;I32> = "
                                + "(=> | | counterexample_call_dependency->:.value) "
                                + "let source :Array<Fn<;I32>> = Array[original] "
                                + "let @mut alias :Array<Fn<;I32>> = "
                                + "counterexample_namespace_factory->::maker[source] "
                                + "let changed = (alias[0] := replacement) "
                                + "let @pub value :I32 = (source[0])"),
                        module(callDependency,
                                "import counterexample_call_main "
                                        + "let @pub value :I32 = counterexample_call_main->:.seed"),
                        module(namespaceFactory,
                                "let @pub maker :Fn<Array<Fn<;I32>>;Array<Fn<;I32>>> = "
                                        + "(=> |input :Array<Fn<;I32>>| input)")),
                callMain,
                List.of(
                        new ModuleGraph.Edge(callMain, callLogical, callDependency,
                                SourceSpan.of(callMain.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(callMain, namespaceFactoryLogical, namespaceFactory,
                                SourceSpan.of(callMain.sourceId(), 1, 2)),
                        new ModuleGraph.Edge(callDependency,
                                LogicalModuleId.parse("counterexample_call_main"), callMain,
                                SourceSpan.of(callDependency.sourceId(), 0, 1))),
                Map.of(callLogical, callDependency, namespaceFactoryLogical, namespaceFactory,
                        LogicalModuleId.parse("counterexample_call_main"), callMain));
        PhaseResult<TypedSemanticGraph> namespaceCallCycleResult = typeResult(namespaceCallCycle);
        check(namespaceCallCycleResult instanceof PhaseResult.Failure<?>
                        && namespaceCallCycleResult.optionalValue().isEmpty()
                        && namespaceCallCycleResult.diagnostics().getFirst().code().value()
                        .equals("LYC-MODULE-004"),
                "namespace aggregate identity-preserving calls retain callable member effects: "
                        + render(namespaceCallCycleResult));

        check(typeResult(singleGraph(
                        "let @pub value :I32 = ::f[] "
                                + "let f :Fn<;I32> = (=> | | value)"))
                        instanceof PhaseResult.Failure<?> failure
                        && failure.optionalValue().isEmpty()
                        && failure.diagnostics().getFirst().code().value().equals("LYC-MODULE-004"),
                "same-module eager value reads through predeclared function slots form a cycle");
        PhaseResult<TypedSemanticGraph> inferredConditional = typeResult(singleGraph(
                "let result = (#T -> { #NIL } : 1)"));
        check(inferredConditional instanceof PhaseResult.Success<?>
                        && contract(((PhaseResult.Success<TypedSemanticGraph>) inferredConditional).value(), "result")
                        .valueType().equals(PrimitiveType.I64.nilable()),
                "inferred conditional block nil unifies with the non-nil branch as @nil I64");
        check(typeResult(singleGraph(
                        "let nested = (#T -> { let local :I32 = 1 { #NIL } } : 1)"))
                        instanceof PhaseResult.Success<?>,
                "nested inferred nil blocks receive the established branch contract");
    }

    @Test
    public void testContextualMutableParametersAndCaptures() {
        TypedSemanticGraph contextual = success(
                "let scalar :Fn<@mut I32;Unit> = (=> |value| (value := 1)) "
                        + "let array :Fn<@mut Array<I32>;Unit> = "
                        + "(=> |values| (values[0] := 1))");
        check(contextual.mutations().size() == 2
                        && contextual.resolvedGraph().declarations().stream()
                        .filter(value -> value.name().equals("value")
                                || value.name().equals("values"))
                        .allMatch(value -> value.bindingMutability() == BindingMutability.MUTABLE),
                "omitted lambda parameter contracts inherit contextual @mut permission");
        check(((FunctionType) contract(contextual, "scalar").valueType()).parameterType(0).isMutable()
                        && ((FunctionType) contract(contextual, "array").valueType())
                        .parameterType(0).isMutable(),
                "contextual scalar and array signatures retain @mut parameter qualifiers");

        TypedSemanticGraph captured = success(
                "let outer :Fn<@mut I32;Fn<;Unit>> = "
                        + "(=> |value| (=> | | (value := 1)))");
        check(captured.resolvedGraph().captures().size() == 1
                        && captured.resolvedGraph().captures().getFirst().mode()
                        == CaptureMode.SHARED_MUTABLE_CELL
                        && captured.resolvedGraph().captures().getFirst().sharedCellId().isPresent(),
                "a contextually mutable parameter captured by a nested closure uses one shared cell");
        check(IrValidator.isValid(phaseSuccess(TypedIrBuilder.lower(captured))),
                "contextual mutable captures lower to valid closed IR");

        TypedSemanticGraph compact = success(
                "let apply :Fn<Fn<@mut I32;Unit>;Unit> = "
                        + "(=> |callback| { let @mut local :I32 = 0 (callback local) }) "
                        + "let result = (apply |value| (value := 1))");
        check(compact.mutations().stream().anyMatch(mutation ->
                        compact.declaration(mutation.rootDeclaration())
                                .filter(value -> value.name().equals("value")).isPresent()),
                "compact contextual lambdas inherit omitted @mut parameter contracts");

        failure("let f :Fn<I32;Unit> = (=> |@mut value| ())", "LYC-RESOLVE-016");
        failure("let f :Fn<@mut I32;Unit> = (=> |value :I32| ())", "LYC-RESOLVE-016");

        ModuleId main = ModuleId.path("mut_main.lyra");
        ModuleId library = ModuleId.path("mut_library.lyra");
        LogicalModuleId libraryLogical = LogicalModuleId.parse("mut_library");
        ModuleGraph imported = graph(
                List.of(
                        module(main, "import mut_library->{values} "
                                + "let update :Fn<@mut Array<I32>;Unit> = "
                                + "(=> |items| (items[0] := 1)) "
                                + "let bad = (update values)"),
                        module(library,
                                "let @pub @mut values :Array<I32> = Array[0]")),
                main,
                List.of(new ModuleGraph.Edge(
                        main, libraryLogical, library,
                        SourceSpan.of(main.sourceId(), 0, 1))),
                Map.of(libraryLogical, library));
        PhaseResult<?> importedResult = ownershipResult(imported);
        check(importedResult instanceof PhaseResult.Failure<?>
                        && importedResult.optionalValue().isEmpty()
                        && importedResult.diagnostics().getFirst().code().value()
                        .equals("LYC-RESOLVE-022")
                        && !importedResult.diagnostics().getFirst().relatedSpans().isEmpty(),
                "an imported mutable export cannot satisfy a local @mut parameter contract");
    }

    @Test
    public void testBlockLocalFunctionCapturesAndModuleSlots() {
        TypedSemanticGraph immutable = success(
                "let make :Fn<;Fn<;I32>> = (=> | | { "
                        + "let outer :Fn<;I32> = (=> | | 1) "
                        + "let maker :Fn<;I32> = (=> | | ::outer[]) maker })");
        var immutableOuter = immutable.declarations().stream()
                .filter(value -> value.name().equals("outer")).findFirst().orElseThrow();
        check(immutable.resolvedGraph().captures().stream().anyMatch(capture ->
                        capture.declarationId().equals(immutableOuter.id())
                                && capture.mode() == CaptureMode.IMMUTABLE_VALUE),
                "an escaping block-local function captures the linked local function value");

        TypedSemanticGraph mutable = success(
                "let make :Fn<;Fn<;I32>> = (=> | | { "
                        + "let @mut outer :Fn<;I32> = (=> | | 1) "
                        + "let maker :Fn<;I32> = (=> | | ::outer[]) maker })");
        var mutableOuter = mutable.declarations().stream()
                .filter(value -> value.name().equals("outer")).findFirst().orElseThrow();
        check(mutable.resolvedGraph().captures().stream().anyMatch(capture ->
                        capture.declarationId().equals(mutableOuter.id())
                                && capture.mode() == CaptureMode.SHARED_MUTABLE_CELL
                                && capture.sharedCellId().filter(mutableOuter.id()::equals).isPresent()),
                "an escaping block-local @mut function captures its shared cell");

        TypedSemanticGraph moduleSlots = success(
                "let first :Fn<;I32> = (=> | | ::second[]) "
                        + "let second :Fn<;I32> = (=> | | ::first[])");
        check(moduleSlots.resolvedGraph().captures().isEmpty()
                        && moduleSlots.resolvedGraph().functionLinkage().components().stream()
                        .anyMatch(component -> component.recursive()
                                && component.declarations().size() == 2),
                "module-scope mutual recursion remains linked without closure captures");
    }

    @Test
    public void testNilTruthBranchesCoalescingAndModifiers() {
        TypedSemanticGraph typed = success(
                "let @nil maybe :Array<I32> = #NIL "
                        + "let selected = (maybe value -> value[0] : 0) "
                        + "let fallback = (maybe : Array<I32>[]) "
                        + "let truth = (and maybe (or #F \"x\")) "
                        + "let only = (\"\" -> ()) "
                        + "let f :Fn<@mut Array<I32>;@nil String> = "
                        + "(=> |@mut values :Array<I32>| #NIL)");
        check(contract(typed, "selected").valueType() == PrimitiveType.I32
                        && contract(typed, "fallback").valueType().equals(ArrayType.of(PrimitiveType.I32)),
                "nil predicate binding narrows arrays and coalescing returns the non-nil base type");
        check(typed.declarations().stream().anyMatch(value -> value.name().equals("value")
                        && value.contract().orElseThrow().valueType().equals(ArrayType.of(PrimitiveType.I32))),
                "truthy predicate binding is an immutable lexical declaration in the narrowed branch");
        check(typed.expressions().stream().anyMatch(value ->
                        value.kind() == TypedExpressionKind.SHORT_CIRCUIT)
                        && typed.expressions().stream().anyMatch(value ->
                        value.kind() == TypedExpressionKind.CONDITIONAL
                                && value.type() == PrimitiveType.UNIT),
                "and/or short circuit and then-only conditionals retain their semantic forms");
        check(contract(typed, "f").valueType() instanceof FunctionType,
                "@mut parameter and @nil return modifiers remain in the function contract");
        failure("let @nil value :I32 = #NIL let bad = (+ value 1)", "LYC-TYPE-008");
        failure("let @nil value :Array<I32> = #NIL let bad = value[0]", "LYC-TYPE-013");
        failure("let bad = (#NIL : 0)", "LYC-TYPE-005");
        failure("let bad = (and #NIL #T)", "LYC-TYPE-005");
        failure("let bad = (< \"a\" \"b\")", "LYC-TYPE-008");
        failure("let value :Fn<;I32> = (=> | | 1) let bad = (== value value)",
                "LYC-TYPE-008");
    }

    @Test
    public void testContextualNilCoalescing() {
        TypedSemanticGraph typed = success(
                "let scalar :@nil I32 = (#NIL : 0) "
                        + "let nested :@nil Array<@nil I32> = (#NIL : Array[#NIL]) "
                        + "let nestedCoalesce :@nil I32 = ((#NIL : 0) : 1) "
                        + "let block :@nil I32 = ({ #NIL } : 0) "
                        + "let @nil small :I16 = #NIL "
                        + "let declared :@nil I32 = (small : 0)");
        check(contract(typed, "scalar").valueType().equals(PrimitiveType.I32.nilable())
                        && contract(typed, "nested").valueType()
                        .equals(ArrayType.of(PrimitiveType.I32.nilable()).nilable())
                        && contract(typed, "nestedCoalesce").valueType()
                        .equals(PrimitiveType.I32.nilable())
                        && contract(typed, "block").valueType()
                        .equals(PrimitiveType.I32.nilable())
                        && contract(typed, "declared").valueType()
                        .equals(PrimitiveType.I32.nilable()),
                "declared and nested @nil contexts type #NIL coalesce values exactly");
        var scalar = typed.declarations().stream()
                .filter(value -> value.name().equals("scalar"))
                .findFirst().orElseThrow().initializer().orElseThrow();
        check(scalar.type().isNilable()
                        && scalar.kind() == TypedExpressionKind.CONVERSION
                        && scalar.children().getFirst().kind() == TypedExpressionKind.COALESCE
                        && !scalar.children().getFirst().type().isNilable(),
                "coalescing narrows to a non-nil base before the declared outer nil lift");
        check(IrValidator.isValid(phaseSuccess(TypedIrBuilder.lower(typed))),
                "contextual and nested coalescing lower to valid closed IR");
        failure("let bad :I32 = (#NIL : 0)", "LYC-TYPE-005");
        failure("let bad :@nil I32 = (#NIL : \"wrong\")", "LYC-TYPE-001");
        failure("let bad = #NIL", "LYC-TYPE-005");
    }

    @Test
    public void testCheckedNumericOperatorsConversionsAndFailureSites() {
        TypedSemanticGraph typed = success(
                "let signed :I8 = 127I8 let widened = (+ signed 1I8) "
                        + "let unsigned :U8 = 1U8 let quotient :F32 = (/ unsigned 2U8) "
                        + "let remainder = (% 5U16 2U8) let power = (^ 2I16 3I16) "
                        + "let decimal = (+ 1.0F32 2.0F32) "
                        + "let numeric = I16[unsigned] "
                        + "let text = String[unsigned] let character = String['x'] let unit = String[()] ");
        check(contract(typed, "widened").valueType() == PrimitiveType.I8
                        && contract(typed, "quotient").valueType() == PrimitiveType.F32
                        && contract(typed, "remainder").valueType() == PrimitiveType.U16
                        && contract(typed, "power").valueType() == PrimitiveType.I16,
                "checked signed/unsigned arithmetic, integer division, remainder, and power retain exact types");
        check(typed.failureSites().stream().anyMatch(value -> value.failureCode().equals("LYR-ARITH"))
                        && typed.failureSites().stream().anyMatch(value -> value.kind().name().equals("DIVISION"))
                        && typed.failureSites().stream().anyMatch(value -> value.kind().name().equals("CONVERSION")),
                "arithmetic, division, and explicit-conversion failure sites are recorded without execution");
        check(IrValidator.isValid(phaseSuccess(TypedIrBuilder.lower(typed))),
                "numeric and text conversions lower to validated failure-site IR");
        failure("let bad = (+ 127I8 1I8)", "LYC-TYPE-008");
        failure("let bad = (- 0U8 1U8)", "LYC-TYPE-008");
        failure("let bad = (/ 1.0 0.0)", "LYC-TYPE-008");
        failure("let bad = (+ 1.0e308 1.0e308)", "LYC-TYPE-008");
        failure("let bad = (^ 2I8 (- 1I8))", "LYC-TYPE-008");
        failure("let bad = I32[(+ 1.0 2.5)]", "LYC-TYPE-009");
        failure("let bad = String[Array<I32>[]]", "LYC-TYPE-009");
    }

    @Test
    public void testFunctionCapturesAccessLegalityAndExpectedAggregateLambdas() {
        TypedSemanticGraph typed = success(
                "let @mut value :I32 = 0 "
                        + "let f :Fn<;Unit> = (=> | | (value := 1)) "
                        + "let functions :Array<Fn<I32;I32>> = Array[|x| x] "
                        + "let callback = functions[0] let result = (callback 1) "
                        + "let tuple :Tuple<Fn<;I32>> = Tuple[(=> | | 1)] "
                        + "let called = (tuple:.0)");
        check(typed.lambdas().stream().anyMatch(value -> !value.captures().isEmpty()),
                "captured bindings retain capture identities");
        check(typed.expressions().stream().anyMatch(value -> value.kind() == TypedExpressionKind.CALLABLE_CALL)
                        && contract(typed, "called").valueType() == PrimitiveType.I32,
                "function-valued aggregate fields and indexing remain callable values");
        check(IrValidator.isValid(phaseSuccess(TypedIrBuilder.lower(typed))),
                "captures and function-valued aggregates retain closed IR correspondence");
        failure("let value :I32 = 1 let bad = value:.length", "LYC-TYPE-013");
        failure("let value :I32 = 1 let bad = value::method[]", "LYC-TYPE-013");
        failure("let value :I32 = 1 let bad = value:.method", "LYC-TYPE-013");
        failure("let tuple :Tuple<I32,String> = Tuple<I32,String>[1 \"x\"] let bad = tuple:.2",
                "LYC-TYPE-013");
        failure("let f :Fn<I32;I32> = (=> |x| x) let bad = (f)", "LYC-TYPE-006");
    }

    @Test
    public void testConservativeInitializationPlanAndCycles() {
        ModuleId a = ModuleId.path("a.lyra");
        ModuleId b = ModuleId.path("b.lyra");
        ModuleId c = ModuleId.path("c.lyra");
        LogicalModuleId al = LogicalModuleId.parse("a");
        LogicalModuleId bl = LogicalModuleId.parse("b");
        LogicalModuleId cl = LogicalModuleId.parse("c");
        ModuleGraph graph = graph(
                List.of(
                        module(a, "import b let @pub value :I32 = b->::f[]"),
                        module(b, "import c let @pub f :Fn<;I32> = (=> | | c->::g[])"),
                        module(c, "let @pub g :Fn<;I32> = (=> | | 1)")),
                a,
                List.of(
                        new ModuleGraph.Edge(a, bl, b, SourceSpan.of(a.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(b, cl, c, SourceSpan.of(b.sourceId(), 0, 1))),
                Map.of(al, a, bl, b, cl, c));
        TypedSemanticGraph typed = typeSuccess(graph);
        InitializationPlan plan = typed.initializationPlan();
        check(plan.cycles().isEmpty()
                        && plan.initializationOrder().equals(List.of(b, c, a)),
                "eager initialization order is dependency-first and stable for unconstrained modules");
        check(plan.dependencies().stream().anyMatch(value ->
                        value.fromModule().equals(a) && value.toModule().equals(b))
                        && plan.dependencies().stream().anyMatch(value ->
                        value.fromModule().equals(a) && value.toModule().equals(c)),
                "transitively executed cross-module calls retain root initializer dependency spans");
        check(plan.dependencies().stream().allMatch(value -> !value.sourcePath().isEmpty()),
                "initialization dependencies retain non-empty source/effect paths");
        TypedSemanticGraph replay = typeSuccess(graph);
        check(plan.equals(replay.initializationPlan()),
                "initialization side artifacts are deterministic and immutable");

        ModuleGraph functions = graph(
                List.of(
                        module(a, "import b->{g} let @pub f :Fn<;I32> = (=> | | ::g[])"),
                        module(b, "import a->{f} let @pub g :Fn<;I32> = (=> | | ::f[])")),
                a,
                List.of(
                        new ModuleGraph.Edge(a, bl, b, SourceSpan.of(a.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(b, al, a, SourceSpan.of(b.sourceId(), 0, 1))),
                Map.of(al, a, bl, b));
        TypedSemanticGraph functionGraph = typeSuccess(functions);
        check(functionGraph.initializationPlan().cycles().isEmpty()
                        && functionGraph.initializationPlan().dependencies().isEmpty()
                        && functionGraph.resolvedGraph().functionLinkage().components().stream()
                        .anyMatch(FunctionScc::recursive),
                "function-signature SCCs do not become eager value cycles");

        ModuleGraph values = graph(
                List.of(
                        module(a, "import b->{value as bv} let @pub value :I32 = bv"),
                        module(b, "import a->{value as av} let @pub value :I32 = av")),
                a,
                List.of(
                        new ModuleGraph.Edge(a, bl, b, SourceSpan.of(a.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(b, al, a, SourceSpan.of(b.sourceId(), 0, 1))),
                Map.of(al, a, bl, b));
        PhaseResult<TypedSemanticGraph> cycle = typeResult(values);
        check(cycle instanceof PhaseResult.Failure<?> && cycle.optionalValue().isEmpty()
                        && cycle.diagnostics().getFirst().code().value().equals("LYC-MODULE-004")
                        && !cycle.diagnostics().getFirst().relatedSpans().isEmpty(),
                "eager value cycles fail with a structured module diagnostic and related spans");
    }

    @Test
    public void testFunctionValuedCallsExposeTransitiveEagerDependencies() {
        ModuleId a = ModuleId.path("a.lyra");
        ModuleId b = ModuleId.path("b.lyra");
        ModuleId c = ModuleId.path("c.lyra");
        ModuleId d = ModuleId.path("d.lyra");
        LogicalModuleId al = LogicalModuleId.parse("a");
        LogicalModuleId bl = LogicalModuleId.parse("b");
        LogicalModuleId cl = LogicalModuleId.parse("c");
        LogicalModuleId dl = LogicalModuleId.parse("d");
        ModuleGraph graph = graph(
                List.of(
                        module(a, "import b let @pub value :I32 = (b->::factory[])"),
                        module(b, "import c "
                                + "let make :Fn<;Fn<;I32>> = (=> | | c->:.g) "
                                + "let @pub factory :Fn<;Fn<;I32>> = (=> | | ::make[])"),
                        module(c, "import d let @pub g :Fn<;I32> = (=> | | d->:.value)"),
                        module(d, "let @pub value :I32 = 1")),
                a,
                List.of(
                        new ModuleGraph.Edge(a, bl, b, SourceSpan.of(a.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(b, cl, c, SourceSpan.of(b.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(c, dl, d, SourceSpan.of(c.sourceId(), 0, 1))),
                Map.of(al, a, bl, b, cl, c, dl, d));
        InitializationPlan plan = typeSuccess(graph).initializationPlan();
        check(plan.dependencies().stream().anyMatch(value ->
                        value.fromModule().equals(a) && value.toModule().equals(d)),
                "function-valued local/namespace direct and callable calls follow the returned function body: "
                        + plan.dependencies());
        check(plan.dependencies().stream()
                        .filter(value -> value.fromModule().equals(a))
                        .allMatch(value -> !value.sourcePath().isEmpty()),
                "function-valued call dependency paths remain source-backed");

        ModuleGraph cycleGraph = graph(
                List.of(
                        module(a, "import b let @pub seed :I32 = 1 "
                                + "let @pub value :I32 = (b->::factory[])"),
                        module(b, "import c "
                                + "let make :Fn<;Fn<;I32>> = (=> | | c->:.g) "
                                + "let @pub factory :Fn<;Fn<;I32>> = (=> | | ::make[])"),
                        module(c, "import d let @pub g :Fn<;I32> = (=> | | d->:.value)"),
                        module(d, "import a let @pub value :I32 = a->:.seed")),
                a,
                List.of(
                        new ModuleGraph.Edge(a, bl, b, SourceSpan.of(a.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(b, cl, c, SourceSpan.of(b.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(c, dl, d, SourceSpan.of(c.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(d, al, a, SourceSpan.of(d.sourceId(), 0, 1))),
                Map.of(al, a, bl, b, cl, c, dl, d));
        PhaseResult<TypedSemanticGraph> cycle = typeResult(cycleGraph);
        check(cycle instanceof PhaseResult.Failure<?> && cycle.optionalValue().isEmpty()
                        && cycle.diagnostics().getFirst().code().value().equals("LYC-MODULE-004")
                        && !cycle.diagnostics().getFirst().relatedSpans().isEmpty(),
                "transitive effects hidden behind function-valued calls participate in cycle detection");
    }

    @Test
    public void testAliasedFunctionValuesExposeInitializationEffects() {
        ModuleId a = ModuleId.path("alias_a.lyra");
        ModuleId b = ModuleId.path("alias_b.lyra");
        ModuleId c = ModuleId.path("alias_c.lyra");
        ModuleId d = ModuleId.path("alias_d.lyra");
        LogicalModuleId al = LogicalModuleId.parse("alias_a");
        LogicalModuleId bl = LogicalModuleId.parse("alias_b");
        LogicalModuleId cl = LogicalModuleId.parse("alias_c");
        LogicalModuleId dl = LogicalModuleId.parse("alias_d");
        ModuleGraph graph = graph(
                List.of(
                        module(a, "import alias_b "
                                + "let first :Fn<;I32> = alias_b->:.direct "
                                + "let alias :Fn<;I32> = first "
                                + "let fromCall :Fn<;I32> = alias_b->::factory[] "
                                + "let @pub one :I32 = (alias) "
                                + "let @pub two :I32 = (fromCall)"),
                        module(b, "import alias_c import alias_d "
                                + "let @pub direct :Fn<;I32> = (=> | | alias_d->:.value) "
                                + "let @pub factory :Fn<;Fn<;I32>> = "
                                + "(=> | | alias_c->:.effect)"),
                        module(c, "import alias_d let @pub effect :Fn<;I32> = "
                                + "(=> | | alias_d->:.value)"),
                        module(d, "let @pub value :I32 = 1")),
                a,
                List.of(
                        new ModuleGraph.Edge(a, bl, b, SourceSpan.of(a.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(b, cl, c, SourceSpan.of(b.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(b, dl, d, SourceSpan.of(b.sourceId(), 1, 2)),
                        new ModuleGraph.Edge(c, dl, d, SourceSpan.of(c.sourceId(), 0, 1))),
                Map.of(al, a, bl, b, cl, c, dl, d));
        InitializationPlan plan = typeSuccess(graph).initializationPlan();
        check(plan.dependencies().stream().anyMatch(value ->
                        value.fromModule().equals(a) && value.toModule().equals(d)),
                "invoked function values initialized through reference aliases and calls expose effects");
        check(plan.dependencies().stream()
                        .filter(value -> value.fromModule().equals(a))
                        .allMatch(value -> !value.sourcePath().isEmpty()),
                "aliased function dependency paths remain deterministic and source-backed");

        ModuleGraph cycleGraph = graph(
                List.of(
                        module(a, "import alias_b let @pub seed :I32 = 1 "
                                + "let fromCall :Fn<;I32> = alias_b->::factory[] "
                                + "let @pub value :I32 = (fromCall)"),
                        module(b, "import alias_c let @pub factory :Fn<;Fn<;I32>> = "
                                + "(=> | | alias_c->:.effect)"),
                        module(c, "import alias_d let @pub effect :Fn<;I32> = "
                                + "(=> | | alias_d->:.value)"),
                        module(d, "import alias_a let @pub value :I32 = alias_a->:.seed")),
                a,
                List.of(
                        new ModuleGraph.Edge(a, bl, b, SourceSpan.of(a.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(b, cl, c, SourceSpan.of(b.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(c, dl, d, SourceSpan.of(c.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(d, al, a, SourceSpan.of(d.sourceId(), 0, 1))),
                Map.of(al, a, bl, b, cl, c, dl, d));
        PhaseResult<TypedSemanticGraph> cycle = typeResult(cycleGraph);
        check(cycle instanceof PhaseResult.Failure<?> && cycle.optionalValue().isEmpty()
                        && cycle.diagnostics().getFirst().code().value().equals("LYC-MODULE-004")
                        && !cycle.diagnostics().getFirst().relatedSpans().isEmpty(),
                "effects reached through call-initialized aliases participate in eager cycle rejection");
    }

    @Test
    public void testReassignedFunctionValuesExposeInitializationEffects() {
        ModuleId a = ModuleId.path("rebind_a.lyra");
        ModuleId d = ModuleId.path("rebind_d.lyra");
        LogicalModuleId al = LogicalModuleId.parse("rebind_a");
        LogicalModuleId dl = LogicalModuleId.parse("rebind_d");
        String source = "import rebind_d "
                + "let original :Fn<;I32> = (=> | | 1) "
                + "let replacement :Fn<;I32> = (=> | | rebind_d->:.value) "
                + "let @mut selected :Fn<;I32> = original "
                + "let changed = (selected := replacement) "
                + "let @mut functions :Array<Fn<;I32>> = Array[original] "
                + "let changedArray = (functions[0] := replacement) "
                + "let chosen :Fn<;I32> = functions[0] "
                + "let @pub one :I32 = (selected) "
                + "let @pub two :I32 = (chosen)";
        ModuleGraph graph = graph(
                List.of(module(a, source), module(d, "let @pub value :I32 = 1")),
                a,
                List.of(new ModuleGraph.Edge(
                        a, dl, d, SourceSpan.of(a.sourceId(), 0, 1))),
                Map.of(al, a, dl, d));
        TypedSemanticGraph typed = typeSuccess(graph);
        InitializationPlan plan = typed.initializationPlan();
        var one = typed.declarations().stream()
                .filter(value -> value.name().equals("one")).findFirst().orElseThrow().id();
        var two = typed.declarations().stream()
                .filter(value -> value.name().equals("two")).findFirst().orElseThrow().id();
        check(plan.dependencies().stream().anyMatch(value ->
                        value.fromModule().equals(a) && value.toModule().equals(d)
                                && value.initializerDeclaration().filter(one::equals).isPresent())
                        && plan.dependencies().stream().anyMatch(value ->
                        value.fromModule().equals(a) && value.toModule().equals(d)
                                && value.initializerDeclaration().filter(two::equals).isPresent()),
                "direct and array-element function reassignments remain conservative call candidates: "
                        + plan.dependencies().stream().map(dependency -> dependency.initializerDeclaration()
                        .map(id -> typed.declaration(id).map(value -> value.name()).orElse(id.toString()))
                        .orElse("none"))
                        .toList() + " " + plan.dependencies());

        ModuleGraph cycleGraph = graph(
                List.of(
                        module(a, "import rebind_d let @pub seed :I32 = 1 "
                                + "let original :Fn<;I32> = (=> | | 1) "
                                + "let replacement :Fn<;I32> = "
                                + "(=> | | rebind_d->:.value) "
                                + "let @mut selected :Fn<;I32> = original "
                                + "let changed = (selected := replacement) "
                                + "let @pub value :I32 = (selected)"),
                        module(d, "import rebind_a let @pub value :I32 = rebind_a->:.seed")),
                a,
                List.of(
                        new ModuleGraph.Edge(a, dl, d, SourceSpan.of(a.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(d, al, a, SourceSpan.of(d.sourceId(), 0, 1))),
                Map.of(al, a, dl, d));
        PhaseResult<TypedSemanticGraph> cycle = typeResult(cycleGraph);
        check(cycle instanceof PhaseResult.Failure<?> && cycle.optionalValue().isEmpty()
                        && cycle.diagnostics().getFirst().code().value().equals("LYC-MODULE-004"),
                "reassigned function effects cannot evade eager cycle detection");
    }

    @Test
    public void testAggregateAliasMutationExposesInitializationEffects() {
        ModuleId a = ModuleId.path("aggregate_a.lyra");
        ModuleId d = ModuleId.path("aggregate_d.lyra");
        LogicalModuleId al = LogicalModuleId.parse("aggregate_a");
        LogicalModuleId dl = LogicalModuleId.parse("aggregate_d");
        String source = "import aggregate_d "
                + "let original :Fn<;I32> = (=> | | 1) "
                + "let replacement :Fn<;I32> = (=> | | aggregate_d->:.value) "
                + "let @mut functions :Array<Fn<;I32>> = Array[original] "
                + "let @mut alias :Array<Fn<;I32>> = functions "
                + "let changed = (alias[0] := replacement) "
                + "let @pub value :I32 = (functions[0])";
        ModuleGraph graph = graph(
                List.of(module(a, source), module(d, "let @pub value :I32 = 1")),
                a,
                List.of(new ModuleGraph.Edge(a, dl, d, SourceSpan.of(a.sourceId(), 0, 1))),
                Map.of(al, a, dl, d));
        InitializationPlan plan = typeSuccess(graph).initializationPlan();
        check(plan.dependencies().stream().anyMatch(dependency ->
                        dependency.fromModule().equals(a) && dependency.toModule().equals(d)
                                && !dependency.sourcePath().isEmpty()),
                "array-element replacements through an identity alias remain callable candidates");

        ModuleGraph cycleGraph = graph(
                List.of(
                        module(a, "import aggregate_d let @pub seed :I32 = 1 "
                                + source.substring("import aggregate_d ".length())),
                        module(d, "import aggregate_a "
                                + "let @pub value :I32 = aggregate_a->:.seed")),
                a,
                List.of(
                        new ModuleGraph.Edge(a, dl, d, SourceSpan.of(a.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(d, al, a, SourceSpan.of(d.sourceId(), 0, 1))),
                Map.of(al, a, dl, d));
        PhaseResult<TypedSemanticGraph> cycle = typeResult(cycleGraph);
        check(cycle instanceof PhaseResult.Failure<?> && cycle.optionalValue().isEmpty()
                        && cycle.diagnostics().getFirst().code().value().equals("LYC-MODULE-004")
                        && !cycle.diagnostics().getFirst().relatedSpans().isEmpty(),
                "an eager cycle hidden by an array identity alias is rejected");

        ModuleGraph aggregateArgumentCycle = graph(
                List.of(
                        module(a, "import aggregate_d let @pub seed :I32 = 1 "
                                + "let replacement :Fn<;I32> = "
                                + "(=> | | aggregate_d->:.value) "
                                + "let functions :Array<Fn<;I32>> = Array[replacement] "
                                + "let invoke :Fn<Array<Fn<;I32>>;I32> = "
                                + "(=> |items| (items[0])) "
                                + "let @pub value :I32 = (invoke functions)"),
                        module(d, "import aggregate_a "
                                + "let @pub value :I32 = aggregate_a->:.seed")),
                a,
                List.of(
                        new ModuleGraph.Edge(a, dl, d, SourceSpan.of(a.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(d, al, a, SourceSpan.of(d.sourceId(), 0, 1))),
                Map.of(al, a, dl, d));
        PhaseResult<TypedSemanticGraph> argumentCycle = typeResult(aggregateArgumentCycle);
        check(argumentCycle instanceof PhaseResult.Failure<?>
                        && argumentCycle.optionalValue().isEmpty()
                        && argumentCycle.diagnostics().getFirst().code().value()
                        .equals("LYC-MODULE-004"),
                "callable aggregate arguments cannot hide eager effects");

        ModuleGraph returnedAliasCycle = graph(
                List.of(
                        module(a, "import aggregate_d let @pub seed :I32 = 1 "
                                + "let original :Fn<;I32> = (=> | | 1) "
                                + "let replacement :Fn<;I32> = "
                                + "(=> | | aggregate_d->:.value) "
                                + "let @mut functions :Array<Fn<;I32>> = Array[original] "
                                + "let factory :Fn<;Array<Fn<;I32>>> = "
                                + "(=> | | functions) "
                                + "let @mut alias :Array<Fn<;I32>> = ::factory[] "
                                + "let changed = (alias[0] := replacement) "
                                + "let @pub value :I32 = (functions[0])"),
                        module(d, "import aggregate_a "
                                + "let @pub value :I32 = aggregate_a->:.seed")),
                a,
                List.of(
                        new ModuleGraph.Edge(a, dl, d, SourceSpan.of(a.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(d, al, a, SourceSpan.of(d.sourceId(), 0, 1))),
                Map.of(al, a, dl, d));
        PhaseResult<TypedSemanticGraph> returnedCycle = typeResult(returnedAliasCycle);
        check(returnedCycle instanceof PhaseResult.Failure<?>
                        && returnedCycle.optionalValue().isEmpty()
                        && returnedCycle.diagnostics().getFirst().code().value()
                        .equals("LYC-MODULE-004"),
                "statically returned array identities preserve alias mutation candidates");

        ModuleGraph projectedAliasCycle = graph(
                List.of(
                        module(a, "import aggregate_d let @pub seed :I32 = 1 "
                                + "let original :Fn<;I32> = (=> | | 1) "
                                + "let replacement :Fn<;I32> = "
                                + "(=> | | aggregate_d->:.value) "
                                + "let @mut functions :Array<Fn<;I32>> = Array[original] "
                                + "let tuple :Tuple<Array<Fn<;I32>>> = Tuple[functions] "
                                + "let @mut alias :Array<Fn<;I32>> = tuple:.0 "
                                + "let changed = (alias[0] := replacement) "
                                + "let @pub value :I32 = (functions[0])"),
                        module(d, "import aggregate_a "
                                + "let @pub value :I32 = aggregate_a->:.seed")),
                a,
                List.of(
                        new ModuleGraph.Edge(a, dl, d, SourceSpan.of(a.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(d, al, a, SourceSpan.of(d.sourceId(), 0, 1))),
                Map.of(al, a, dl, d));
        PhaseResult<TypedSemanticGraph> projectedCycle = typeResult(projectedAliasCycle);
        check(projectedCycle instanceof PhaseResult.Failure<?>
                        && projectedCycle.optionalValue().isEmpty()
                        && projectedCycle.diagnostics().getFirst().code().value()
                        .equals("LYC-MODULE-004"),
                "tuple projection preserves the identity of an aliased callable array");
    }

    @Test
    public void testMaxGateSemanticRepairs() {
        ModuleGraph exactPaths = pair(
                "max_path",
                "import max_path_lib->{values} "
                        + "let @mut selected :Tuple<Array<I32>,Array<I32>> = "
                        + "Tuple[values Array[0]] "
                        + "let tupleChanged = (selected:.1[0] := 1) "
                        + "let @mut nested :Array<Array<I32>> = Array[values Array[0]] "
                        + "let arrayChanged = (nested[1][0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]");
        check(typeResult(exactPaths) instanceof PhaseResult.Success<?>,
                "exact tuple and nested-array selector paths do not taint local siblings");

        ModuleGraph higherOrderImported = pair(
                "max_higher",
                "import max_higher_lib->{values id} "
                        + "let apply :Fn<Fn<Array<I32>;Array<I32>>,Array<I32>;Array<I32>> = "
                        + "(=> |f :Fn<Array<I32>;Array<I32>> items :Array<I32>| (f items)) "
                        + "let @mut returned :Array<I32> = (apply id values) "
                        + "let changed = (returned[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0] "
                        + "let @pub id :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| input)");
        expectResolutionFailure(higherOrderImported, "LYC-RESOLVE-022");
        check(typeResult(singleGraph(
                "let apply :Fn<Fn<Array<I32>;Array<I32>>,Array<I32>;Array<I32>> = "
                        + "(=> |f :Fn<Array<I32>;Array<I32>> items :Array<I32>| (f items)) "
                        + "let id :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| input) "
                        + "let local :Array<I32> = Array[0] "
                        + "let @mut returned :Array<I32> = (apply id local) "
                        + "let changed = (returned[0] := 1)")) instanceof PhaseResult.Success<?>,
                "higher-order parameter provenance leaves fresh/local values writable");

        expectResolutionFailure(pair(
                "max_replacement",
                "import max_replacement_lib->{values id} "
                        + "let fresh :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| Array[0]) "
                        + "let @mut funcs :Array<Fn<Array<I32>;Array<I32>>> = Array[fresh] "
                        + "let replaced = (funcs[0] := id) "
                        + "let @mut returned :Array<I32> = (funcs[0] values) "
                        + "let changed = (returned[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0] "
                        + "let @pub id :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| input)"),
                "LYC-RESOLVE-022");

        expectResolutionFailure(pair(
                "max_whole_replacement",
                "import max_whole_replacement_lib->{values id} "
                        + "let fresh :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| Array[0]) "
                        + "let @mut funcs :Array<Fn<Array<I32>;Array<I32>>> = Array[fresh] "
                        + "let replacement :Array<Fn<Array<I32>;Array<I32>>> = Array[id] "
                        + "let replaced = (funcs := replacement) "
                        + "let @mut returned :Array<I32> = (funcs[0] values) "
                        + "let changed = (returned[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0] "
                        + "let @pub id :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| input)"),
                "LYC-RESOLVE-022");

        expectResolutionFailure(pair(
                "max_coalesce",
                "import max_coalesce_lib->{values} "
                        + "let @mut @nil alias :Array<I32> = values "
                        + "let local :Array<I32> = Array[0] "
                        + "let selected = (alias : { alias := local local }) "
                        + "let changed = (alias[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]"),
                "LYC-RESOLVE-022");

        expectResolutionFailure(pair(
                "max_branch",
                "import max_branch_lib->{values} "
                        + "let f :Fn<;Array<I32>> = (=> | | { "
                        + "let @mut alias :Array<I32> = Array[0] "
                        + "let local :Array<I32> = Array[1] "
                        + "let ignored = (#T -> { alias := values } : { alias := local }) "
                        + "alias }) "
                        + "let @mut returned :Array<I32> = (f) "
                        + "let changed = (returned[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]"),
                "LYC-RESOLVE-022");
        check(typeResult(singleGraph(
                "let f :Fn<;Array<I32>> = (=> | | { "
                        + "let @mut alias :Array<I32> = Array[0] "
                        + "let first :Array<I32> = Array[1] "
                        + "let second :Array<I32> = Array[2] "
                        + "let ignored = (#T -> { alias := first } : { alias := second }) "
                        + "alias }) "
                        + "let @mut returned :Array<I32> = (f) "
                        + "let changed = (returned[0] := 1)")) instanceof PhaseResult.Success<?>,
                "conditional return provenance permits a value when every branch is local");

        expectTypeFailure(pair(
                "max_param_cycle",
                "import max_param_cycle_lib let @pub seed :I32 = 1 "
                        + "let original :Fn<;I32> = (=> | | 1) "
                        + "let replacement :Fn<;I32> = "
                        + "(=> | | max_param_cycle_lib->:.value) "
                        + "let producer :Fn<;Array<Fn<;I32>>> = "
                        + "(=> | | Array[replacement]) "
                        + "let consumer :Fn<Fn<;Array<Fn<;I32>>>;I32> = "
                        + "(=> |make :Fn<;Array<Fn<;I32>>>| { "
                        + "let functions :Array<Fn<;I32>> = (make) "
                        + "(functions[0]) }) "
                        + "let @pub value :I32 = (consumer producer)",
                "import max_param_cycle_main "
                        + "let @pub value :I32 = max_param_cycle_main->:.seed"),
                "LYC-MODULE-004");

        check(typeResult(pair(
                "max_nested_array",
                "import max_nested_array_lib let @pub seed :I32 = 1 "
                        + "let original :Fn<;I32> = (=> | | 1) "
                        + "let replacement :Fn<;I32> = "
                        + "(=> | | max_nested_array_lib->:.value) "
                        + "let @pub value :I32 = "
                        + "(Array[Array[original replacement]][0][0])",
                "import max_nested_array_main "
                        + "let @pub value :I32 = max_nested_array_main->:.seed")) instanceof PhaseResult.Success<?>,
                "nested array callable discovery follows every selected index");

        check(typeResult(pair(
                "max_nested_tuple",
                "import max_nested_tuple_lib let @pub seed :I32 = 1 "
                        + "let original :Fn<;I32> = (=> | | 1) "
                        + "let replacement :Fn<;I32> = "
                        + "(=> | | max_nested_tuple_lib->:.value) "
                        + "let @mut first :Array<Fn<;I32>> = Array[original] "
                        + "let @mut second :Array<Fn<;I32>> = Array[original] "
                        + "let inner :Tuple<Array<Fn<;I32>>,Array<Fn<;I32>>> = Tuple[first second] "
                        + "let @mut alias :Tuple<"
                        + "Tuple<Array<Fn<;I32>>,Array<Fn<;I32>>>,"
                        + "Tuple<Array<Fn<;I32>>,Array<Fn<;I32>>>> = Tuple[inner inner] "
                        + "let changed = (alias:.0:.0[0] := replacement) "
                        + "let @pub value :I32 = (alias:.0:.1[0])",
                "import max_nested_tuple_main "
                        + "let @pub value :I32 = max_nested_tuple_main->:.seed")) instanceof PhaseResult.Success<?>,
                "nested tuple alias routes do not taint unrelated callable members");

        check(typeResult(pair(
                "max_source_order",
                "import max_source_order_lib let @pub seed :I32 = 1 "
                        + "let original :Fn<;I32> = (=> | | 1) "
                        + "let replacement :Fn<;I32> = "
                        + "(=> | | max_source_order_lib->:.value) "
                        + "let @mut f :Fn<;I32> = original "
                        + "let @pub value :I32 = (f) "
                        + "let changed = (f := replacement)",
                "import max_source_order_main "
                        + "let @pub value :I32 = max_source_order_main->:.seed")) instanceof PhaseResult.Success<?>,
                "a call observes only function candidates assigned before its source position");

        expectTypeFailure(pair(
                "max_shared_tuple",
                "import max_shared_tuple_lib let @pub seed :I32 = 1 "
                        + "let original :Fn<;I32> = (=> | | 1) "
                        + "let replacement :Fn<;I32> = "
                        + "(=> | | max_shared_tuple_lib->:.value) "
                        + "let @mut funcs :Array<Fn<;I32>> = Array[original] "
                        + "let tuple :Tuple<Array<Fn<;I32>>,Array<Fn<;I32>>> = Tuple[funcs funcs] "
                        + "let @mut alias :Tuple<Array<Fn<;I32>>,Array<Fn<;I32>>> = tuple "
                        + "let changed = (alias:.0[0] := replacement) "
                        + "let @pub value :I32 = (alias:.1[0])",
                "import max_shared_tuple_main "
                        + "let @pub value :I32 = max_shared_tuple_main->:.seed"),
                "LYC-MODULE-004");

        TypedSemanticGraph compositeNil = success(
                "let result = (#T -> Array[#NIL] : Array<@nil I32>[1])");
        check(contract(compositeNil, "result").valueType()
                        .equals(ArrayType.of(PrimitiveType.I32.nilable())),
                "inferred composite nil branches derive nested element context from their peer");
    }

    @Test
    public void testIndependentMaxGateSemanticRepairs() {
        TypedSemanticGraph branchAssignments = success(
                "let @mut funcs :Array<Fn<;I32>> = Array[(=> | | 0)] "
                        + "let first :Fn<;I32> = (=> | | 1) "
                        + "let second :Fn<;I32> = (=> | | 2) "
                        + "let ignored = (#T -> { funcs[0] := first } : { funcs[0] := second }) "
                        + "let result :I32 = (funcs[0])");
        check(contract(branchAssignments, "funcs").isMutable()
                        && branchAssignments.expressions().stream().anyMatch(expression ->
                        expression.kind() == TypedExpressionKind.REBINDING),
                "branch aggregate assignments use mutable internal flow collections and publish no implementation exception");

        TypedSemanticGraph thenOnly = success(
                "let f :Fn<;Unit> = (=> | | (#T -> 1))");
        TypedExpression thenOnlyConditional = thenOnly.expressions().stream()
                .filter(expression -> expression.kind() == TypedExpressionKind.CONDITIONAL)
                .findFirst().orElseThrow();
        check(thenOnlyConditional.type() == PrimitiveType.UNIT
                        && thenOnlyConditional.children().get(1).type() == PrimitiveType.I64,
                "then-only conditionals type their value branch independently and remain Unit-valued");

        TypedSemanticGraph inferredCompositeNil = success(
                "let result = (#T -> Array[#NIL] : Array[1])");
        check(contract(inferredCompositeNil, "result").valueType()
                        .equals(ArrayType.of(PrimitiveType.I32.nilable()))
                        && inferredCompositeNil.expressions().stream().anyMatch(expression ->
                        expression.kind() == TypedExpressionKind.LITERAL
                                && expression.literal().orElse(null)
                                instanceof io.mindspice.lyra.compiler.semantic.TypedLiteralValue.NilValue
                                && expression.type().equals(PrimitiveType.I32.nilable())),
                "composite branch nil inference recursively applies the peer base contract");

        TypedSemanticGraph blockNilArray = success("let values = Array[{ #NIL } 1]");
        check(contract(blockNilArray, "values").valueType()
                        .equals(ArrayType.of(PrimitiveType.I32.nilable())),
                "array inference discovers a block-final nil and contextualizes its final expression");
        TypedSemanticGraph blockNilEquality = success("let equal = (== { #NIL } 1)");
        check(contract(blockNilEquality, "equal").valueType() == PrimitiveType.BOOL,
                "equality discovers a block-final nil and derives its peer base type");

        ModuleGraph capturedRebinding = pair(
                "max_captured_rebinding",
                "import max_captured_rebinding_lib->{values} "
                        + "let @mut alias :Array<I32> = values "
                        + "let local :Array<I32> = Array[0] "
                        + "let update :Fn<;Unit> = (=> | | (alias := local)) "
                        + "let invoked = (update) "
                        + "let changed = (alias[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]");
        check(typeResult(capturedRebinding) instanceof PhaseResult.Success<?>,
                "a known closure call transfers captured-cell rebinding back to caller flow");

        ModuleGraph capturedJoin = pair(
                "max_captured_join",
                "import max_captured_join_lib->{values} "
                        + "let @mut alias :Array<I32> = values "
                        + "let local :Array<I32> = Array[0] "
                        + "let update :Fn<;Unit> = (=> | | (#T -> { (alias := local) } : ())) "
                        + "let invoked = (update) "
                        + "let changed = (alias[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]");
        expectResolutionFailure(capturedJoin, "LYC-RESOLVE-022");

        check(typeResult(pair(
                "max_source_order_replacement",
                "import max_source_order_replacement_lib let @pub seed :I32 = 1 "
                        + "let original :Fn<;I32> = (=> | | max_source_order_replacement_lib->:.value) "
                        + "let replacement :Fn<;I32> = (=> | | 1) "
                        + "let @mut selected :Fn<;I32> = original "
                        + "let changed = (selected := replacement) "
                        + "let @pub value :I32 = (selected)",
                "import max_source_order_replacement_main "
                        + "let @pub value :I32 = max_source_order_replacement_main->:.seed"))
                instanceof PhaseResult.Success<?>,
                "an unconditional straight-line function replacement discards stale initializer candidates");

        expectTypeFailure(pair(
                "max_persisted_closure_call",
                "import max_persisted_closure_call_lib let @pub seed :I32 = 1 "
                        + "let original :Fn<;I32> = (=> | | 1) "
                        + "let replacement :Fn<;I32> = "
                        + "(=> | | max_persisted_closure_call_lib->:.value) "
                        + "let @mut selected :Fn<;I32> = original "
                        + "let rebind :Fn<;Unit> = (=> | | (selected := replacement)) "
                        + "let invoked = (rebind) "
                        + "let @pub value :I32 = (selected)",
                "import max_persisted_closure_call_main "
                        + "let @pub value :I32 = max_persisted_closure_call_main->:.seed"),
                "LYC-MODULE-004");

        expectTypeFailure(pair(
                "max_projected_function_values",
                "import max_projected_function_values_lib let @pub seed :I32 = 1 "
                        + "let replacement :Fn<;I32> = "
                        + "(=> | | max_projected_function_values_lib->:.value) "
                        + "let make :Fn<;Array<Fn<;I32>>> = "
                        + "(=> | | Array[replacement]) "
                        + "let makers :Array<Fn<;Array<Fn<;I32>>>> = Array[make] "
                        + "let produced :Array<Fn<;I32>> = (makers[0]) "
                        + "let @pub value :I32 = (produced[0])",
                "import max_projected_function_values_main "
                        + "let @pub value :I32 = max_projected_function_values_main->:.seed"),
                "LYC-MODULE-004");

        check(typeResult(singleGraph(
                "let make :Fn<;Fn<;I32>> = (=> | | { "
                        + "let f :Fn<;I32> = (=> | | ::g[]) "
                        + "let g :Fn<;I32> = (=> | | ::f[]) f })")) instanceof PhaseResult.Success<?>,
                "mutually recursive local lambda slots are lazy captures until an actual call executes them");
    }

    @Test
    public void testInitializationPlanCannotBeForged() {
        ModuleId a = ModuleId.path("plan_a.lyra");
        ModuleId b = ModuleId.path("plan_b.lyra");
        LogicalModuleId al = LogicalModuleId.parse("plan_a");
        LogicalModuleId bl = LogicalModuleId.parse("plan_b");
        ModuleGraph sourceGraph = graph(
                List.of(
                        module(a, "import plan_b let @pub value :I32 = plan_b->:.value"),
                        module(b, "let @pub value :I32 = 1")),
                a,
                List.of(new ModuleGraph.Edge(a, bl, b, SourceSpan.of(a.sourceId(), 0, 1))),
                Map.of(al, a, bl, b));
        TypedSemanticGraph valid = typeSuccess(sourceGraph);
        check(!valid.initializationPlan().dependencies().isEmpty(),
                "the fixture has a non-empty canonical initialization plan");
        check(java.util.Arrays.stream(TypedSemanticGraph.class.getConstructors()).findAny().isEmpty(),
                "typed graph construction is package-owned");

        boolean rejected = false;
        try {
            SemanticTestSupport.seal(
                    valid, valid.semanticFlowFacts(),
                    InitializationPlan.empty(valid.resolvedGraph().moduleGraph()));
        } catch (IllegalArgumentException expected) {
            rejected = expected.getMessage().contains("canonical eager dependency analysis");
        }
        check(rejected, "a forged empty initialization schedule cannot publish");

        TypedSemanticGraph reconstructed = SemanticTestSupport.seal(
                valid, valid.semanticFlowFacts(), valid.initializationPlan());
        check(reconstructed.equals(valid),
                "the complete constructor preserves legitimate canonical construction");
    }

    @Test
    public void testNoPartialAggregateOrCycleArtifacts() {
        PhaseResult<TypedSemanticGraph> result = typeResult(singleGraph(
                "let bad = Array[1I64 2U64]"));
        check(result instanceof PhaseResult.Failure<?> && result.optionalValue().isEmpty(),
                "incompatible aggregate element types publish no typed artifact");
        failure("let bad = Array[1I64 2U64]", "LYC-TYPE-018");
        failure("let bad = Array[|x| x]", "LYC-RESOLVE-017");
        check(success("let empty = Array<I32>[] let unit = Array[] let tuple = Tuple[]")
                        .declarations().stream().anyMatch(value -> value.name().equals("unit")
                                && value.contract().orElseThrow().valueType() == PrimitiveType.UNIT),
                "typed empty arrays and bare Array[]/Tuple[] Unit spellings remain distinct");
    }

    private static TypedSemanticGraph success(String source) {
        return typeSuccess(singleGraph(source));
    }

    private static TypedSemanticGraph typeSuccess(ModuleGraph graph) {
        PhaseResult<TypedSemanticGraph> result = typeResult(graph);
        if (!(result instanceof PhaseResult.Success<?> success)) {
            throw new AssertionError("expected typed success: " + render(result));
        }
        @SuppressWarnings("unchecked")
        PhaseResult.Success<TypedSemanticGraph> value =
                (PhaseResult.Success<TypedSemanticGraph>) success;
        return value.value();
    }

    private static PhaseResult<TypedSemanticGraph> typeResult(ModuleGraph graph) {
        PhaseResult<ResolvedSemanticGraph> resolved = SemanticResolver.resolve(graph);
        if (resolved instanceof PhaseResult.Failure<?>) {
            throw new AssertionError("resolution failed: " + render(resolved));
        }
        return TypeChecker.check(((PhaseResult.Success<ResolvedSemanticGraph>) resolved).value());
    }

    private static void failure(String source, String code) {
        PhaseResult<ResolvedSemanticGraph> resolved = SemanticResolver.resolve(singleGraph(source));
        if (resolved instanceof PhaseResult.Failure<?>) {
            check(resolved.optionalValue().isEmpty()
                            && resolved.diagnostics().getFirst().code().value().equals(code),
                    "unexpected resolution diagnostic: " + render(resolved));
            return;
        }
        PhaseResult<TypedSemanticGraph> result = TypeChecker.check(
                ((PhaseResult.Success<ResolvedSemanticGraph>) resolved).value());
        check(result instanceof PhaseResult.Failure<?> && result.optionalValue().isEmpty(),
                "expected typed failure: " + source + " -> " + render(result));
        check(result.diagnostics().getFirst().code().value().equals(code),
                "unexpected diagnostic: " + render(result));
    }

    private static BindingContract contract(TypedSemanticGraph graph, String name) {
        return graph.declarations().stream()
                .filter(value -> value.name().equals(name))
                .findFirst().orElseThrow().contract().orElseThrow();
    }

    private static io.mindspice.lyra.compiler.ir.TypedIr phaseSuccess(
            PhaseResult<io.mindspice.lyra.compiler.ir.TypedIr> result) {
        if (!(result instanceof PhaseResult.Success<?> success)) {
            throw new AssertionError("expected IR success: " + render(result));
        }
        @SuppressWarnings("unchecked")
        PhaseResult.Success<io.mindspice.lyra.compiler.ir.TypedIr> value =
                (PhaseResult.Success<io.mindspice.lyra.compiler.ir.TypedIr>) success;
        return value.value();
    }

    private static ModuleGraph pair(
            String stem,
            String mainSource,
            String librarySource) {
        ModuleId main = ModuleId.path(stem + "_main.lyra");
        ModuleId library = ModuleId.path(stem + "_lib.lyra");
        LogicalModuleId mainLogical = LogicalModuleId.parse(stem + "_main");
        LogicalModuleId libraryLogical = LogicalModuleId.parse(stem + "_lib");
        List<ModuleGraph.Node> nodes = List.of(
                module(main, mainSource), module(library, librarySource));
        Map<LogicalModuleId, ModuleId> modules = Map.of(
                mainLogical, main, libraryLogical, library);
        List<ModuleGraph.Edge> edges = nodes.stream()
                .flatMap(node -> node.program().imports().stream().map(importDeclaration -> {
                    LogicalModuleId logical = LogicalModuleId.fromImportPath(
                            importDeclaration.path());
                    ModuleId target = Optional.ofNullable(modules.get(logical)).orElseThrow();
                    return new ModuleGraph.Edge(
                            node.moduleId(), logical, target,
                            importDeclaration.path().span());
                }))
                .toList();
        return graph(nodes, main, edges, modules);
    }

    private static PhaseResult<?> ownershipResult(ModuleGraph graph) {
        PhaseResult<ResolvedSemanticGraph> resolved = SemanticResolver.resolve(graph);
        return resolved instanceof PhaseResult.Success<ResolvedSemanticGraph> success
                ? TypeChecker.check(success.value())
                : resolved;
    }

    private static void expectResolutionFailure(ModuleGraph graph, String code) {
        PhaseResult<ResolvedSemanticGraph> resolved = SemanticResolver.resolve(graph);
        PhaseResult<?> result = resolved;
        if (resolved instanceof PhaseResult.Success<ResolvedSemanticGraph> success
                && code.equals("LYC-RESOLVE-022")) {
            // Call/capture-mediated imported ownership is now decided by the
            // canonical typed flow producer while retaining its resolver code.
            result = TypeChecker.check(success.value());
        }
        check(result instanceof PhaseResult.Failure<?> && result.optionalValue().isEmpty()
                        && result.diagnostics().getFirst().code().value().equals(code),
                "unexpected semantic result: " + render(result));
    }

    private static void expectTypeFailure(ModuleGraph graph, String code) {
        PhaseResult<ResolvedSemanticGraph> resolved = SemanticResolver.resolve(graph);
        check(resolved instanceof PhaseResult.Success<?>,
                "expected resolution success before type failure: " + render(resolved));
        PhaseResult<TypedSemanticGraph> result = TypeChecker.check(
                ((PhaseResult.Success<ResolvedSemanticGraph>) resolved).value());
        check(result instanceof PhaseResult.Failure<?> && result.optionalValue().isEmpty()
                        && result.diagnostics().getFirst().code().value().equals(code),
                "unexpected type result: " + render(result));
    }

    private static ModuleGraph singleGraph(String source) {
        ModuleId id = ModuleId.path("domain11.lyra");
        return graph(List.of(module(id, source)), id, List.of(), Map.of());
    }

    private static ModuleGraph graph(
            List<ModuleGraph.Node> modules,
            ModuleId root,
            List<ModuleGraph.Edge> edges,
            Map<LogicalModuleId, ModuleId> logicalModules) {
        return CanonicalModuleGraph.create(root, modules, edges, logicalModules);
    }

    private static ModuleGraph.Node module(ModuleId id, String source) {
        SourceSnapshot snapshot = snapshot(id.sourceId(), source);
        SyntaxProgram syntax = parse(snapshot);
        return new ModuleGraph.Node(
                id,
                Optional.of(LogicalModuleId.fromSourceId(id.sourceId())),
                snapshot,
                syntax,
                ModuleRevision.compute(snapshot));
    }

    private static SyntaxProgram parse(SourceSnapshot snapshot) {
        PhaseResult<LexedSource> lexed = Lexer.lex(snapshot);
        if (!(lexed instanceof PhaseResult.Success<?> success)) {
            throw new AssertionError("lex failed: " + render(lexed));
        }
        LexedSource source = ((PhaseResult.Success<LexedSource>) success).value();
        PhaseResult<GrammarProgram> grammar = GrammarMatcher.match(source);
        if (!(grammar instanceof PhaseResult.Success<?> grammarSuccess)) {
            throw new AssertionError("grammar failed: " + render(grammar));
        }
        GrammarProgram matched = ((PhaseResult.Success<GrammarProgram>) grammarSuccess).value();
        PhaseResult<SyntaxProgram> parsed = Parser.parse(source, matched);
        if (!(parsed instanceof PhaseResult.Success<?> parsedSuccess)) {
            throw new AssertionError("parse failed: " + render(parsed));
        }
        return ((PhaseResult.Success<SyntaxProgram>) parsedSuccess).value();
    }

    private static SourceSnapshot snapshot(SourceId id, String source) {
        PhaseResult<SourceSnapshot> result = SourceSnapshot.capture(
                id,
                PhysicalSourceKey.uri(URI.create("memory:" + id.value().replace('/', '_'))),
                source.getBytes(StandardCharsets.UTF_8));
        if (!(result instanceof PhaseResult.Success<?> success)) {
            throw new AssertionError("source capture failed: " + render(result));
        }
        return ((PhaseResult.Success<SourceSnapshot>) success).value();
    }

    private static String render(PhaseResult<?> result) {
        return result.diagnostics().stream().map(Diagnostic::render).toList().toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
