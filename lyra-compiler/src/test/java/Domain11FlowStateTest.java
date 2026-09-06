import org.junit.jupiter.api.Test;

import io.mindspice.lyra.compiler.ast.SyntaxProgram;
import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.grammar.GrammarMatcher;
import io.mindspice.lyra.compiler.grammar.GrammarProgram;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.parse.Parser;
import io.mindspice.lyra.compiler.semantic.ResolvedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.SemanticResolver;
import io.mindspice.lyra.compiler.semantic.TypeChecker;
import io.mindspice.lyra.compiler.semantic.flow.BindingFlowState;
import io.mindspice.lyra.compiler.semantic.flow.CallableFlow;
import io.mindspice.lyra.compiler.semantic.flow.ValueAlternative;
import io.mindspice.lyra.compiler.semantic.flow.ValueAlternatives;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.ModuleRevision;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.BindingContract;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.PrimitiveType;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Focused Gate 11B resolver flow and callable-transfer coverage. */
public final class Domain11FlowStateTest {
    @Test
    public void callableAlternativesUseTheSameDeterministicRouteUpdates() {
        DeclarationId binding = new DeclarationId(1);
        FunctionType function = FunctionType.of(List.of(), PrimitiveType.I32);
        ArrayType array = ArrayType.of(function);
        CallableFlow first = CallableFlow.atRoot(new LambdaId(1), Map.of())
                .prefixedBy(io.mindspice.lyra.compiler.semantic.flow.ProjectionPath.arrayElement(0));
        CallableFlow replacement = CallableFlow.atRoot(new LambdaId(2), Map.of())
                .prefixedBy(io.mindspice.lyra.compiler.semantic.flow.ProjectionPath.arrayElement(0));
        BindingFlowState state = BindingFlowState.empty().bind(
                binding,
                BindingContract.mutable(array),
                ValueAlternatives.singleton(ValueAlternative.of(
                        array, List.of(), List.of(first))));
        check(state.requireBinding(binding).callableFlows().equals(List.of(first)),
                "callable transfer data is explicit on the immutable binding state");
        check(state.requireBinding(binding).alternatives().select(
                        io.mindspice.lyra.compiler.semantic.flow.ProjectionPath.arrayElement(0))
                        .only().callableFlows().stream()
                        .anyMatch(value -> value.lambdaId().equals(Optional.of(new LambdaId(1)))),
                "callable selection rebases through the aggregate route algebra");
        BindingFlowState updated = state.replaceExactRoute(
                binding,
                io.mindspice.lyra.compiler.semantic.flow.ProjectionPath.arrayElement(0),
                ValueAlternatives.singleton(ValueAlternative.callable(
                        function, CallableFlow.atRoot(new LambdaId(2), Map.of()))));
        check(updated.requireBinding(binding).callableFlows().equals(List.of(replacement)),
                "exact callable route replacement discards only the selected old value");
    }

    @Test
    public void sourceOrderUsesTheCallableValueVisibleAtEachCallSite() {
        PhaseResult<ResolvedSemanticGraph> result = resolve(pair(
                "flow_source_order",
                "import flow_source_order_lib->{values id} "
                        + "let fresh :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| Array[0]) "
                        + "let @mut selected :Fn<Array<I32>;Array<I32>> = fresh "
                        + "let @mut before :Array<I32> = (selected values) "
                        + "let beforeWrite = (before[0] := 1) "
                        + "let replaced = (selected := id) "
                        + "let @mut after :Array<I32> = (selected values) "
                        + "let afterWrite = (after[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0] "
                        + "let @pub id :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| input)"));
        expectImportedFailure(result);
    }

    @Test
    public void wholeExactAndUnknownAssignmentsUseOneBindingFlowState() {
        check(resolve(pair(
                "flow_exact_update",
                "import flow_exact_update_lib->{values} "
                        + "let @mut outer :Array<Array<I32>> = Array[values Array[0]] "
                        + "let local :Array<I32> = Array[1] "
                        + "let changed = (outer[0] := local) "
                        + "let ok = (outer[0][0] := 2)",
                "let @pub @mut values :Array<I32> = Array[0]"))
                        instanceof PhaseResult.Success<?>,
                "an exact route replacement preserves local mutation permission");

        expectImportedFailure(resolve(pair(
                "flow_unknown_update",
                "import flow_unknown_update_lib->{values} "
                        + "let @mut outer :Array<Array<I32>> = Array[values Array[0]] "
                        + "let local :Array<I32> = Array[1] let index :I32 = 0 "
                        + "let changed = (outer[index] := local) "
                        + "let bad = (outer[0][0] := 2)",
                "let @pub @mut values :Array<I32> = Array[0]")));

        check(resolve(pair(
                "flow_whole_update",
                "import flow_whole_update_lib->{values} "
                        + "let @mut alias :Array<I32> = values "
                        + "let local :Array<I32> = Array[0] let changed = (alias := local) "
                        + "let ok = (alias[0] := 2)",
                "let @pub @mut values :Array<I32> = Array[0]"))
                        instanceof PhaseResult.Success<?>,
                "a whole replacement discards stale imported alternatives");
    }

    @Test
    public void branchAndCoalesceJoinsRetainEveryReachableAlternative() {
        expectImportedFailure(resolve(pair(
                "flow_branch_join",
                "import flow_branch_join_lib->{values} "
                        + "let @mut alias :Array<I32> = Array[0] "
                        + "let imported :Array<I32> = values "
                        + "let ignored = (#T -> { alias := imported } : { alias := alias }) "
                        + "let bad = (alias[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]")));

        expectImportedFailure(resolve(pair(
                "flow_coalesce_join",
                "import flow_coalesce_join_lib->{values} "
                        + "let @mut @nil alias :Array<I32> = #NIL "
                        + "let ignored = (alias : { alias := values Array[0] }) "
                        + "let bad = (alias[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]")));
    }

    @Test
    public void lambdaCreationDoesNotRunItsBodyButInvocationDoes() {
        expectImportedFailure(resolve(pair(
                "flow_lambda_creation",
                "import flow_lambda_creation_lib->{values} "
                        + "let @mut alias :Array<I32> = values "
                        + "let update :Fn<;Unit> = (=> | | { "
                        + "let local :Array<I32> = Array[0] alias := local }) "
                        + "let bad = (alias[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]")));

        check(resolve(pair(
                "flow_lambda_invocation",
                "import flow_lambda_invocation_lib->{values} "
                        + "let @mut alias :Array<I32> = values "
                        + "let update :Fn<;Unit> = (=> | | { "
                        + "let local :Array<I32> = Array[0] alias := local }) "
                        + "let called = (update) let ok = (alias[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]"))
                        instanceof PhaseResult.Success<?>,
                "a known invocation applies its captured shared-cell write to the caller");
    }

    @Test
    public void shortCircuitOperatorsRetainTheSkippedCallableEffectPath() {
        expectImportedFailure(resolve(pair(
                "flow_short_circuit_or",
                "import flow_short_circuit_or_lib->{values} "
                        + "let @mut alias :Array<I32> = values "
                        + "let update :Fn<;Unit> = (=> | | { "
                        + "let local :Array<I32> = Array[0] alias := local }) "
                        + "let ignored = (or #T (update)) "
                        + "let bad = (alias[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]")));

        expectImportedFailure(resolve(pair(
                "flow_short_circuit_and",
                "import flow_short_circuit_and_lib->{values} "
                        + "let @mut alias :Array<I32> = values "
                        + "let update :Fn<;Unit> = (=> | | { "
                        + "let local :Array<I32> = Array[0] alias := local }) "
                        + "let ignored = and[#F (update)] "
                        + "let bad = (alias[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]")));
    }

    @Test
    public void intrinsicCallsDoNotRecoverLaterSameSignatureLambdas() {
        expectImportedFailure(resolve(intrinsicGraph(
                "flow_intrinsic_reachability",
                "import std->io->{println} "
                        + "import flow_intrinsic_reachability_lib->{values} "
                        + "let @mut alias :Array<I32> = values "
                        + "let called = (println \"ok\") "
                        + "let dangerous :Fn<String;Unit> = (=> |text :String| { "
                        + "let local :Array<I32> = Array[0] alias := local }) "
                        + "let bad = (alias[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]")));
    }

    @Test
    public void parameterResultAndHigherOrderTransfersSubstituteArgumentFlow() {
        expectImportedFailure(resolve(pair(
                "flow_identity_result",
                "import flow_identity_result_lib->{values id} "
                        + "let @mut result :Array<I32> = (id values) "
                        + "let bad = (result[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0] "
                        + "let @pub id :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| input)")));

        check(resolve(pair(
                "flow_fresh_result",
                "import flow_fresh_result_lib->{values} "
                        + "let fresh :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| Array[0]) "
                        + "let @mut result :Array<I32> = (fresh values) "
                        + "let ok = (result[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]"))
                        instanceof PhaseResult.Success<?>,
                "a fresh callable result does not inherit argument ownership");

        expectImportedFailure(resolve(pair(
                "flow_higher_order",
                "import flow_higher_order_lib->{values id} "
                        + "let apply :Fn<Fn<Array<I32>;Array<I32>>,Array<I32>;Array<I32>> = "
                        + "(=> |f :Fn<Array<I32>;Array<I32>> items :Array<I32>| (f items)) "
                        + "let @mut result :Array<I32> = (apply id values) "
                        + "let bad = (result[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0] "
                        + "let @pub id :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| input)")));
    }

    @Test
    public void mutableParameterAggregateWritesReachCallerAliases() {
        PhaseResult<ResolvedSemanticGraph> result = resolve(pair(
                "flow_parameter_write",
                "import flow_parameter_write_lib->{values id} "
                        + "let replacement :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| Array[0]) "
                        + "let @mut functions :Array<Fn<Array<I32>;Array<I32>>> = Array[id] "
                        + "let update :Fn<@mut Array<Fn<Array<I32>;Array<I32>>>;Unit> = "
                        + "(=> |@mut items :Array<Fn<Array<I32>;Array<I32>>>| "
                        + "(items[0] := replacement)) "
                        + "let called = (update functions) "
                        + "let @mut result :Array<I32> = (functions[0] values) "
                        + "let ok = (result[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0] "
                        + "let @pub id :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| input)"));
        check(result instanceof PhaseResult.Success<?>,
                "a mutable aggregate parameter updates the caller's aliased route: "
                        + render(result));
    }

    @Test
    public void unknownCallableSelectionUnionsEveryCompatibleValueAlternative() {
        expectImportedFailure(resolve(pair(
                "flow_unknown_callable",
                "import flow_unknown_callable_lib->{values id} "
                        + "let fresh :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| Array[0]) "
                        + "let functions :Array<Fn<Array<I32>;Array<I32>>> = Array[id fresh] "
                        + "let index :I32 = 0 "
                        + "let @mut result :Array<I32> = (functions[index] values) "
                        + "let bad = (result[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0] "
                        + "let @pub id :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| input)")));
    }

    @Test
    public void exportedCallableAliasesRetainTheirOriginFlow() {
        expectImportedFailure(resolve(pair(
                "flow_exported_callable_alias",
                "import flow_exported_callable_alias_lib->{values alias} "
                        + "let @mut result :Array<I32> = (alias values) "
                        + "let bad = (result[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0] "
                        + "let identity :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| input) "
                        + "let @pub alias :Fn<Array<I32>;Array<I32>> = identity")));
    }

    @Test
    public void exportedCallableAggregatesRetainExactProjectionRoutes() {
        String arrayLibrary = "let identity :Fn<Array<I32>;Array<I32>> = "
                + "(=> |input :Array<I32>| input) "
                + "let fresh :Fn<Array<I32>;Array<I32>> = "
                + "(=> |input :Array<I32>| Array[0]) "
                + "let @pub functions :Array<Fn<Array<I32>;Array<I32>>> = "
                + "Array[identity fresh]";
        PhaseResult<ResolvedSemanticGraph> exactArrayIdentity = resolve(pair(
                "flow_exported_callable_array_identity",
                "import flow_exported_callable_array_identity_lib->{functions} "
                        + "let input :Array<I32> = Array[0] "
                        + "let @mut result :Array<I32> = (functions[0] input) "
                        + "let ok = (result[0] := 1)",
                arrayLibrary));
        check(exactArrayIdentity instanceof PhaseResult.Success<?>,
                "an exact exported array projection invokes only the identity member: "
                        + render(exactArrayIdentity));
        expectImportedFailure(resolve(pair(
                "flow_exported_callable_array_fresh",
                "import flow_exported_callable_array_fresh_lib->{functions} "
                        + "let input :Array<I32> = Array[0] "
                        + "let @mut result :Array<I32> = (functions[1] input) "
                        + "let bad = (result[0] := 1)",
                arrayLibrary)));

        String tupleLibrary = "let fresh :Fn<Array<I32>;Array<I32>> = "
                + "(=> |input :Array<I32>| Array[0]) "
                + "let identity :Fn<Array<I32>;Array<I32>> = "
                + "(=> |input :Array<I32>| input) "
                + "let @pub functions :Tuple<Fn<Array<I32>;Array<I32>>,"
                + "Fn<Array<I32>;Array<I32>>> = Tuple[fresh identity]";
        PhaseResult<ResolvedSemanticGraph> exactTupleIdentity = resolve(pair(
                "flow_exported_callable_tuple_identity",
                "import flow_exported_callable_tuple_identity_lib->{functions} "
                        + "let input :Array<I32> = Array[0] "
                        + "let @mut result :Array<I32> = (functions:.1 input) "
                        + "let ok = (result[0] := 1)",
                tupleLibrary));
        check(exactTupleIdentity instanceof PhaseResult.Success<?>,
                "an exact exported tuple projection invokes only the identity member: "
                        + render(exactTupleIdentity));
        expectImportedFailure(resolve(pair(
                "flow_exported_callable_tuple_fresh",
                "import flow_exported_callable_tuple_fresh_lib->{functions} "
                        + "let input :Array<I32> = Array[0] "
                        + "let @mut result :Array<I32> = (functions:.0 input) "
                        + "let bad = (result[0] := 1)",
                tupleLibrary)));
    }

    @Test
    public void escapingClosuresShareUpdatedMutableCellSnapshots() {
        PhaseResult<ResolvedSemanticGraph> result = resolve(pair(
                "flow_escaping_shared_cell",
                "import flow_escaping_shared_cell_lib->{values} "
                        + "let make :Fn<;Tuple<Fn<;Unit>,Fn<;Array<I32>>>> = (=> | | { "
                        + "let @mut cell :Array<I32> = values "
                        + "let write :Fn<;Unit> = (=> | | { "
                        + "let local :Array<I32> = Array[0] cell := local }) "
                        + "let read :Fn<;Array<I32>> = (=> | | cell) "
                        + "Tuple[write read] }) "
                        + "let pair :Tuple<Fn<;Unit>,Fn<;Array<I32>>> = (make) "
                        + "let write :Fn<;Unit> = pair:.0 "
                        + "let read :Fn<;Array<I32>> = pair:.1 "
                        + "let called = (write) "
                        + "let @mut result :Array<I32> = (read) "
                        + "let ok = (result[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]"));
        check(result instanceof PhaseResult.Success<?>,
                "escaping sibling closures observe one updated shared cell: " + render(result));

        PhaseResult<ResolvedSemanticGraph> updatedBeforeEscape = resolve(pair(
                "flow_updated_cell_escape",
                "import flow_updated_cell_escape_lib->{values} "
                        + "let make :Fn<;Fn<;Array<I32>>> = (=> | | { "
                        + "let @mut cell :Array<I32> = values "
                        + "let read :Fn<;Array<I32>> = (=> | | cell) "
                        + "let local :Array<I32> = Array[0] "
                        + "let changed = (cell := local) read }) "
                        + "let read :Fn<;Array<I32>> = (make) "
                        + "let @mut result :Array<I32> = (read) "
                        + "let ok = (result[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]"));
        check(updatedBeforeEscape instanceof PhaseResult.Success<?>,
                "a returned closure carries the cell snapshot current at escape: "
                        + render(updatedBeforeEscape));
    }

    @Test
    public void foreignCallableCapturesRemainProtectedAtTheCallBoundary() {
        expectImportedFailure(resolve(pair(
                "flow_foreign_capture",
                "import flow_foreign_capture_lib->{get} "
                        + "let @mut result :Array<I32> = (get) "
                        + "let bad = (result[0] := 1)",
                "let hidden = Array[0] "
                        + "let @pub get :Fn<;Array<I32>> = (=> | | hidden)")));
    }

    @Test
    public void callableBodiesCanReturnNamespaceValuesWithoutLosingOwnership() {
        expectImportedFailure(resolve(pair(
                "flow_namespace_value_return",
                "import flow_namespace_value_return_lib "
                        + "let get :Fn<;Array<I32>> = "
                        + "(=> | | flow_namespace_value_return_lib->:.values) "
                        + "let @mut result :Array<I32> = (get) "
                        + "let bad = (result[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]")));
    }

    @Test
    public void nestedKnownCallbacksPreserveCallerSharedCellWrites() {
        check(resolve(pair(
                "flow_nested_callback",
                "import flow_nested_callback_lib->{run} "
                        + "let @mut alias :Array<I32> = Array[0] "
                        + "let update :Fn<;Unit> = (=> | | { "
                        + "let local :Array<I32> = Array[1] alias := local }) "
                        + "let called = (run update) let ok = (alias[0] := 2)",
                "let @pub run :Fn<Fn<;Unit>;Unit> = (=> |f :Fn<;Unit>| (f))"))
                        instanceof PhaseResult.Success<?>,
                "nested callback transfer preserves the caller's shared-cell update");
    }

    @Test
    public void directAndNamespaceCallsUseTheSameCallableTransfer() {
        expectImportedFailure(resolve(pair(
                "flow_direct_call",
                "import flow_direct_call_lib->{values id} "
                        + "let @mut result :Array<I32> = ::id[values] "
                        + "let bad = (result[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0] "
                        + "let @pub id :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| input)")));

        expectImportedFailure(resolve(pair(
                "flow_namespace_call",
                "import flow_namespace_call_lib import flow_namespace_call_lib->{values} "
                        + "let @mut result :Array<I32> = flow_namespace_call_lib->::id[values] "
                        + "let bad = (result[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0] "
                        + "let @pub id :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| input)")));
    }

    private static void expectImportedFailure(
            PhaseResult<ResolvedSemanticGraph> result) {
        PhaseResult<?> checked = result instanceof PhaseResult.Success<ResolvedSemanticGraph> success
                ? TypeChecker.check(success.value())
                : result;
        check(checked instanceof PhaseResult.Failure<?> && checked.optionalValue().isEmpty(),
                "expected imported-flow failure: " + render(checked));
        check(checked.diagnostics().getFirst().code()
                        .equals(CompilerDiagnosticCodes.RESOLVE_IMPORTED_MUTATION),
                "expected imported ownership diagnostic: " + render(checked));
    }

    private static PhaseResult<ResolvedSemanticGraph> resolve(ModuleGraph graph) {
        return SemanticResolver.resolve(graph);
    }

    private static ModuleGraph pair(
            String stem,
            String mainSource,
            String librarySource) {
        ModuleId main = ModuleId.path(stem + "_main.lyra");
        ModuleId library = ModuleId.path(stem + "_lib.lyra");
        LogicalModuleId logical = LogicalModuleId.parse(stem + "_lib");
        ModuleGraph.Node mainNode = module(main, mainSource);
        ModuleGraph.Node libraryNode = module(library, librarySource);
        List<ModuleGraph.Edge> edges = mainNode.program().imports().stream()
                .filter(declaration -> LogicalModuleId.fromImportPath(declaration.path())
                        .equals(logical))
                .map(declaration -> new ModuleGraph.Edge(
                        main, logical, library, declaration.path().span()))
                .toList();
        return new ModuleGraph(
                main, List.of(mainNode, libraryNode), edges, Map.of(logical, library));
    }

    private static ModuleGraph intrinsicGraph(
            String stem,
            String mainSource,
            String librarySource) {
        ModuleId main = ModuleId.path(stem + "_main.lyra");
        ModuleId library = ModuleId.path(stem + "_lib.lyra");
        ModuleId intrinsic = ModuleId.uri(URI.create("lyra:intrinsic/std/io"));
        LogicalModuleId libraryLogical = LogicalModuleId.parse(stem + "_lib");
        return CanonicalModuleGraph.create(
                main,
                List.of(module(main, mainSource), module(library, librarySource), module(intrinsic, "")),
                List.of(
                        new ModuleGraph.Edge(
                                main, LogicalModuleId.STD_IO, intrinsic,
                                io.mindspice.lyra.compiler.source.SourceSpan.of(
                                        main.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(
                                main, libraryLogical, library,
                                io.mindspice.lyra.compiler.source.SourceSpan.of(
                                        main.sourceId(), 0, 1))),
                Map.of(LogicalModuleId.STD_IO, intrinsic, libraryLogical, library));
    }

    private static ModuleGraph.Node module(ModuleId id, String source) {
        SourceSnapshot snapshot = snapshot(id.sourceId(), source);
        SyntaxProgram syntax = parse(snapshot);
        return new ModuleGraph.Node(
                id,
                id.isPath()
                        ? Optional.of(LogicalModuleId.fromSourceId(id.sourceId()))
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
        LexedSource tokens = ((PhaseResult.Success<LexedSource>) lexedSuccess).value();
        PhaseResult<GrammarProgram> grammar = GrammarMatcher.match(tokens);
        if (!(grammar instanceof PhaseResult.Success<?> grammarSuccess)) {
            throw new AssertionError("grammar failed: " + render(grammar));
        }
        PhaseResult<SyntaxProgram> parsed = Parser.parse(
                tokens,
                ((PhaseResult.Success<GrammarProgram>) grammarSuccess).value());
        if (!(parsed instanceof PhaseResult.Success<?> parsedSuccess)) {
            throw new AssertionError("parse failed: " + render(parsed));
        }
        return ((PhaseResult.Success<SyntaxProgram>) parsedSuccess).value();
    }

    @SuppressWarnings("unchecked")
    private static SourceSnapshot snapshot(SourceId id, String source) {
        PhaseResult<SourceSnapshot> captured = SourceSnapshot.capture(
                id,
                PhysicalSourceKey.uri(URI.create("memory:" + id.value().replace('/', '_'))),
                source.getBytes(StandardCharsets.UTF_8));
        if (!(captured instanceof PhaseResult.Success<?> success)) {
            throw new AssertionError("source capture failed: " + render(captured));
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
