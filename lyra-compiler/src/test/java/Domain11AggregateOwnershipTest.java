import org.junit.jupiter.api.Test;

import io.mindspice.lyra.compiler.ast.SyntaxProgram;
import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.diagnostic.Severity;
import io.mindspice.lyra.compiler.grammar.GrammarMatcher;
import io.mindspice.lyra.compiler.grammar.GrammarProgram;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.parse.Parser;
import io.mindspice.lyra.compiler.semantic.ResolvedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.SemanticResolver;
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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Focused Gate 11A.2 ownership and typed-route coverage. */
public final class Domain11AggregateOwnershipTest {
    @Test
    public void directAliasesAndDiagnosticShapeRemainImportedReadOnly() {
        ModuleGraph graph = pair(
                "ownership_direct",
                "import ownership_direct_lib->{values} let bad = (values[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]");
        PhaseResult<ResolvedSemanticGraph> first = resolve(graph);
        expectImportedFailure(first, graph, "values[0]");
        PhaseResult<ResolvedSemanticGraph> second = resolve(graph);
        expectImportedFailure(second, graph, "values[0]");
        check(first.diagnostics().equals(second.diagnostics()),
                "repeated ownership diagnostics retain deterministic complete shape");
        String source = graph.module(graph.rootModule()).orElseThrow().snapshot().text();
        check(first.diagnostics().getFirst().relatedSpans().getFirst().span().equals(
                        SourceSpan.of(graph.rootModule().sourceId(),
                                source.indexOf("values"), source.indexOf("values") + "values".length()))
                        && first.diagnostics().getFirst().relatedSpans().getFirst().label()
                        .equals("imported aggregate binding"),
                "the imported binding related span remains exact and labeled");

        expectImportedFailure(resolve(pair(
                "ownership_alias",
                "import ownership_alias_lib->{values} "
                        + "let @mut alias :Array<I32> = values let bad = (alias[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]")), null, "alias[0]");
    }

    @Test
    public void containmentProjectionAndUnknownSelectorsPreserveIdentityRoutes() {
        ModuleGraph tupleNested = pair(
                "ownership_tuple_nested",
                "import ownership_tuple_nested_lib->{values} "
                        + "let @mut outer :Tuple<Array<I32>,Array<I32>> = "
                        + "Tuple[values Array[0]] let ok = (outer:.1[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]");
        check(resolve(tupleNested) instanceof PhaseResult.Success<?>,
                "exact tuple siblings do not taint a distinct local member");

        expectImportedFailure(resolve(pair(
                "ownership_tuple_bad",
                "import ownership_tuple_bad_lib->{values} "
                        + "let @mut outer :Tuple<Array<I32>,Array<I32>> = "
                        + "Tuple[values Array[0]] let bad = (outer:.0[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]")), null, "outer:.0[0]");

        expectImportedFailure(resolve(pair(
                "ownership_imported_tuple",
                "import ownership_imported_tuple_lib->{pair} "
                        + "let @mut local :Tuple<Array<I32>,Array<I32>> = pair "
                        + "let bad = (local:.1[0] := 1)",
                "let @pub pair :Tuple<Array<I32>,Array<I32>> = Tuple[Array[0] Array[0]]")),
                null, "local:.1[0]");

        ModuleGraph arrayNested = pair(
                "ownership_array_nested",
                "import ownership_array_nested_lib->{values} "
                        + "let @mut outer :Array<Array<I32>> = Array[values Array[0]] "
                        + "let ok = (outer[1][0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]");
        check(resolve(arrayNested) instanceof PhaseResult.Success<?>,
                "exact array siblings preserve local nested-array mutation");

        ModuleGraph tupleUnknown = pair(
                "ownership_tuple_unknown",
                "import ownership_tuple_unknown_lib->{values} "
                        + "let @mut outer :Tuple<Array<I32>,Array<I32>> = "
                        + "Tuple[values Array[0]] let index :I32 = 0 "
                        + "let ok = (outer:.1[index] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]");
        check(resolve(tupleUnknown) instanceof PhaseResult.Success<?>,
                "an unknown array selector never overlaps an unrelated tuple member");

        expectImportedFailure(resolve(pair(
                "ownership_array_unknown",
                "import ownership_array_unknown_lib->{values} "
                        + "let @mut outer :Array<Array<I32>> = Array[values Array[0]] "
                        + "let index :I32 = 0 let bad = (outer[index][0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]")), null, "outer[index][0]");

        expectImportedFailure(resolve(pair(
                "ownership_imported_nested",
                "import ownership_imported_nested_lib->{matrix} "
                        + "let @mut local :Array<Array<I32>> = matrix "
                        + "let bad = (local[0][0] := 1)",
                "let @pub matrix :Array<Array<I32>> = Array[Array[0]]")),
                null, "local[0][0]");
    }

    @Test
    public void freshArraysTupleRootAndReplacementsKeepLocalPermissionSeparate() {
        check(resolve(single(
                "let @mut values :Array<I32> = Array[0] "
                        + "let ok = (values[0] := 1)")) instanceof PhaseResult.Success<?>,
                "fresh local arrays remain writable through a mutable root");
        PhaseResult<ResolvedSemanticGraph> directFresh = resolve(single(
                "let make :Fn<;Array<I32>> = (=> | | Array[0]) "
                        + "let bad = ((make)[0] := 1)"));
        check(directFresh instanceof PhaseResult.Failure<?> && directFresh.optionalValue().isEmpty()
                        && directFresh.diagnostics().getFirst().code()
                        .equals(CompilerDiagnosticCodes.RESOLVE_MUTATION_NOT_ALLOWED),
                "a fresh array without a mutable root is not misdiagnosed as imported");
        check(resolve(single(
                "let @mut tuple :Tuple<Array<I32>> = Tuple[Array[0]] "
                        + "let ok = (tuple:.0[0] := 1)")) instanceof PhaseResult.Success<?>,
                "a mutable tuple root permits nested array mutation");

        expectTypedFailure(single(
                "let @mut tuple :Tuple<Array<I32>> = Tuple[Array[0]] "
                        + "let bad = (tuple:.0 := Array[1])"), "LYC-TYPE-012");
        expectTypedFailure(single(
                "let @mut bad :Array<Array<I32>> = Array[Array[1I64]]"), "LYC-TYPE-004");

        check(resolve(pair(
                "ownership_replace_local",
                "import ownership_replace_local_lib->{values} "
                        + "let @mut alias :Array<I32> = values let local :Array<I32> = Array[0] "
                        + "let replaced = (alias := local) let ok = (alias[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]")) instanceof PhaseResult.Success<?>,
                "whole replacement discards an imported identity from the local binding");
        check(resolve(pair(
                "ownership_replace_element_local",
                "import ownership_replace_element_local_lib->{values} "
                        + "let @mut outer :Array<Array<I32>> = Array[values Array[0]] "
                        + "let local :Array<I32> = Array[0] let replaced = (outer[0] := local) "
                        + "let ok = (outer[0][0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]")) instanceof PhaseResult.Success<?>,
                "exact element replacement removes only the replaced imported route");
        expectImportedFailure(resolve(pair(
                "ownership_replace_imported",
                "import ownership_replace_imported_lib->{values} "
                        + "let local :Array<I32> = Array[0] let @mut alias :Array<I32> = local "
                        + "let replaced = (alias := values) let bad = (alias[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]")), null, "alias[0]");
    }

    @Test
    public void relatedOriginsStayOrderedWhenOneUnknownRouteOverlapsSeveralImports() {
        String source = "import ownership_related_lib->{first second} "
                + "let @mut outer :Array<Array<I32>> = Array[first second] "
                + "let index :I32 = 0 let bad = (outer[index][0] := 1)";
        ModuleGraph graph = pair(
                "ownership_related",
                source,
                "let @pub first :Array<I32> = Array[0] "
                        + "let @pub second :Array<I32> = Array[0]");
        PhaseResult<ResolvedSemanticGraph> result = resolve(graph);
        expectImportedFailure(result, null, "outer[index][0]");
        check(result.diagnostics().getFirst().relatedSpans().size() == 2,
                "an unknown route reports every compatible imported identity");
        check(result.diagnostics().getFirst().relatedSpans().get(0).span().startOffset()
                        < result.diagnostics().getFirst().relatedSpans().get(1).span().startOffset(),
                "related imported spans retain source order");
    }

    @Test
    public void narrowingCoalescingCallsAndCapturedRebindingsDoNotLaunderOwnership() {
        expectImportedFailure(resolve(pair(
                "ownership_narrow",
                "import ownership_narrow_lib->{values} "
                        + "let result = (values narrowed -> { "
                        + "let @mut alias :Array<I32> = narrowed (alias[0] := 1) } : ())",
                "let @pub @mut @nil values :Array<I32> = #NIL")), null, "alias[0]");

        expectImportedFailure(resolve(pair(
                "ownership_call",
                "import ownership_call_lib->{values} "
                        + "let id :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |value :Array<I32>| value) "
                        + "let @mut alias :Array<I32> = (id values) "
                        + "let bad = (alias[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]")), null, "alias[0]");

        expectImportedFailure(resolve(pair(
                "ownership_projected_call",
                "import ownership_projected_call_lib->{pair} "
                        + "let pick :Fn<Tuple<Array<I32>,I32>;Array<I32>> = "
                        + "(=> |value :Tuple<Array<I32>,I32>| value:.0) "
                        + "let @mut alias :Array<I32> = (pick pair) "
                        + "let bad = (alias[0] := 1)",
                "let @pub pair :Tuple<Array<I32>,I32> = Tuple[Array[0] 1]")),
                null, "alias[0]");

        expectImportedFailure(resolve(pair(
                "ownership_coalesce",
                "import ownership_coalesce_lib->{values} "
                        + "let @mut alias :Array<I32> = (values : Array[0]) "
                        + "let bad = (alias[0] := 1)",
                "let @pub @mut @nil values :Array<I32> = #NIL")), null, "alias[0]");

        expectImportedFailure(resolve(pair(
                "ownership_namespace",
                "import ownership_namespace_lib "
                        + "let bad = (ownership_namespace_lib->:.values[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]")), null,
                "ownership_namespace_lib->:.values[0]");

        expectImportedFailure(resolve(pair(
                "ownership_higher",
                "import ownership_higher_lib->{values id} "
                        + "let apply :Fn<Fn<Array<I32>;Array<I32>>,Array<I32>;Array<I32>> = "
                        + "(=> |f :Fn<Array<I32>;Array<I32>> items :Array<I32>| (f items)) "
                        + "let @mut alias :Array<I32> = (apply id values) "
                        + "let bad = (alias[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0] "
                        + "let @pub id :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| input)")), null, "alias[0]");

        expectImportedFailure(resolve(pair(
                "ownership_returned_capture",
                "import ownership_returned_capture_lib->{values} "
                        + "let make :Fn<Array<I32>;Fn<;Array<I32>>> = "
                        + "(=> |value :Array<I32>| (=> | | value)) "
                        + "let get :Fn<;Array<I32>> = (make values) "
                        + "let @mut alias :Array<I32> = (get) let bad = (alias[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]")), null, "alias[0]");

        expectImportedFailure(resolve(pair(
                "ownership_higher_mut",
                "import ownership_higher_mut_lib->{values} "
                        + "let update :Fn<@mut Array<I32>;Unit> = "
                        + "(=> |@mut items :Array<I32>| (items[0] := 1)) "
                        + "let apply :Fn<Fn<@mut Array<I32>;Unit>,Array<I32>;Unit> = "
                        + "(=> |f :Fn<@mut Array<I32>;Unit> items :Array<I32>| (f items)) "
                        + "let bad = (apply update values)",
                "let @pub @mut values :Array<I32> = Array[0]")), null, "values");

        check(resolve(pair(
                "ownership_capture_local",
                "import ownership_capture_local_lib->{values} "
                        + "let @mut alias :Array<I32> = values let local :Array<I32> = Array[0] "
                        + "let update :Fn<;Unit> = (=> | | (alias := local)) "
                        + "let invoked = (update) let ok = (alias[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]")) instanceof PhaseResult.Success<?>,
                "a known captured-cell replacement updates the shared binding flow");
        check(resolve(pair(
                "ownership_capture_return",
                "import ownership_capture_return_lib->{values} "
                        + "let @mut alias :Array<I32> = values "
                        + "let get :Fn<;Array<I32>> = (=> | | alias) "
                        + "let local :Array<I32> = Array[0] let changed = (alias := local) "
                        + "let @mut result :Array<I32> = (get) let ok = (result[0] := 1)",
                "let @pub @mut values :Array<I32> = Array[0]")) instanceof PhaseResult.Success<?>,
                "shared mutable captures observe whole-binding replacement at invocation time");
    }

    @Test
    public void selectedWritesDeferAliasWideStateToCanonicalFlow() {
        ModuleGraph localReplacement = pair(
                "ownership_aliaswide_local",
                "import ownership_aliaswide_local_lib->{values} "
                        + "let @mut outer :Array<Array<I32>> = Array[values] "
                        + "let @mut alias :Array<Array<I32>> = outer "
                        + "let local :Array<I32> = Array[0] "
                        + "let replaced = (outer[0] := local) "
                        + "let ok = (alias[0][0] := 1)",
                "let @pub values :Array<I32> = Array[0]");
        PhaseResult<ResolvedSemanticGraph> localResolved = resolve(localReplacement);
        check(localResolved instanceof PhaseResult.Success<?>,
                "selected resolver writes defer rather than using stale alias state");
        check(TypeChecker.check(((PhaseResult.Success<ResolvedSemanticGraph>)
                        localResolved).value()) instanceof PhaseResult.Success<?>,
                "canonical identity-wide propagation observes the local replacement");

        ModuleGraph importedReplacement = pair(
                "ownership_aliaswide_imported",
                "import ownership_aliaswide_imported_lib->{values} "
                        + "let @mut outer :Array<Array<I32>> = Array[Array[0]] "
                        + "let @mut alias :Array<Array<I32>> = outer "
                        + "let replaced = (outer[0] := values) "
                        + "let bad = (alias[0][0] := 1)",
                "let @pub values :Array<I32> = Array[0]");
        PhaseResult<ResolvedSemanticGraph> importedResolved = resolve(importedReplacement);
        check(importedResolved instanceof PhaseResult.Success<?>,
                "resolver alias state remains deliberately non-authoritative after a selected write");
        importedDiagnostic(TypeChecker.check(
                ((PhaseResult.Success<ResolvedSemanticGraph>) importedResolved).value()));
    }

    @Test
    public void canonicalBranchWritesJoinFromOneCallerBaseline() {
        ModuleGraph bothReplace = pair(
                "ownership_branch_both_local",
                "import ownership_branch_both_local_lib->{values} "
                        + "let @mut alias :Array<I32> = values "
                        + "let left :Array<I32> = Array[0] "
                        + "let right :Array<I32> = Array[1] "
                        + "let update :Fn<;Unit> = (=> | | "
                        + "(#T -> (alias := left) : (alias := right))) "
                        + "let called = (update) let ok = (alias[0] := 2)",
                "let @pub values :Array<I32> = Array[0]");
        PhaseResult<ResolvedSemanticGraph> resolved = resolve(bothReplace);
        check(resolved instanceof PhaseResult.Success<?>,
                "opaque call state is deferred by the resolver");
        check(TypeChecker.check(((PhaseResult.Success<ResolvedSemanticGraph>)
                        resolved).value()) instanceof PhaseResult.Success<?>,
                "writes in both branches replace the imported baseline on every reachable path");

        ModuleGraph oneReplace = pair(
                "ownership_branch_one_local",
                "import ownership_branch_one_local_lib->{values} "
                        + "let @mut alias :Array<I32> = values "
                        + "let local :Array<I32> = Array[0] "
                        + "let update :Fn<;Unit> = (=> | | "
                        + "(#T -> (alias := local) : ())) "
                        + "let called = (update) let bad = (alias[0] := 2)",
                "let @pub values :Array<I32> = Array[0]");
        PhaseResult<ResolvedSemanticGraph> oneResolved = resolve(oneReplace);
        check(oneResolved instanceof PhaseResult.Success<?>,
                "resolver does not infer effects from an opaque call");
        importedDiagnostic(TypeChecker.check(
                ((PhaseResult.Success<ResolvedSemanticGraph>) oneResolved).value()));
    }

    @Test
    public void typeLessPredicateBoundCallDefersOwnershipToCanonicalFlow() {
        ModuleGraph graph = pair(
                "ownership_predicate_callable_deferred",
                "import ownership_predicate_callable_deferred_lib->{values} "
                        + "let @mut alias :Array<I32> = values "
                        + "let left :Array<I32> = Array[0] "
                        + "let right :Array<I32> = Array[1] "
                        + "let update :Fn<;Unit> = (=> | | "
                        + "(#T -> (alias := left) : (alias := right))) "
                        + "let @nil candidate :Fn<;Unit> = update "
                        + "let called = (candidate callable -> (callable) : (alias := left)) "
                        + "let changed = (alias[0] := 2)",
                "let @pub values :Array<I32> = Array[0]");

        PhaseResult<ResolvedSemanticGraph> resolved = resolve(graph);
        check(resolved instanceof PhaseResult.Success<?>,
                "a predicate binding without resolver type metadata must still make its call opaque: "
                        + render(resolved));
        check(TypeChecker.check(((PhaseResult.Success<ResolvedSemanticGraph>)
                        resolved).value()) instanceof PhaseResult.Success<?>,
                "canonical flow observes local rebinding on every conditional path");
    }

    @Test
    public void typeLessPredicateBoundCallStillEnforcesCanonicalOwnership() {
        ModuleGraph graph = pair(
                "ownership_predicate_callable_rejected",
                "import ownership_predicate_callable_rejected_lib->{values} "
                        + "let @mut alias :Array<I32> = values "
                        + "let local :Array<I32> = Array[0] "
                        + "let update :Fn<;Unit> = (=> | | "
                        + "(#T -> (alias := local) : ())) "
                        + "let @nil candidate :Fn<;Unit> = update "
                        + "let called = (candidate callable -> (callable) : (alias := local)) "
                        + "let bad = (alias[0] := 2)",
                "let @pub values :Array<I32> = Array[0]");

        PhaseResult<ResolvedSemanticGraph> resolved = resolve(graph);
        check(resolved instanceof PhaseResult.Success<?>,
                "type-less predicate-bound calls defer ownership instead of using stale projection state: "
                        + render(resolved));
        Diagnostic diagnostic = importedDiagnostic(TypeChecker.check(
                ((PhaseResult.Success<ResolvedSemanticGraph>) resolved).value()));
        check(diagnostic.primarySpan().equals(spanOf(graph, "alias[0]")),
                "canonical typed flow still rejects a reachable imported aggregate after deferral");
    }

    @Test
    public void callEntryDeferralPreservesIndependentCallAndImportDiagnostics() {
        expectTypedFailure(single(
                "let value :I32 = 1 let bad = (value)"), "LYC-TYPE-007");
        expectTypedFailure(single(
                "let value :I32 = 1 let bad = ::value[]"), "LYC-TYPE-007");
        expectTypedFailure(pair(
                "ownership_namespace_non_callable",
                "import ownership_namespace_non_callable_lib "
                        + "let bad = ownership_namespace_non_callable_lib->::value[]",
                "let @pub value :I32 = 1"), "LYC-TYPE-007");

        PhaseResult<ResolvedSemanticGraph> importedDirect = resolve(pair(
                "ownership_call_then_direct_import",
                "import ownership_call_then_direct_import_lib->{values} "
                        + "let noop :Fn<;Unit> = (=> | | ()) "
                        + "let called = (noop) let bad = (values[0] := 1)",
                "let @pub values :Array<I32> = Array[0]"));
        check(importedDirect instanceof PhaseResult.Failure<?>
                        && importedDirect.diagnostics().getFirst().code()
                        .equals(CompilerDiagnosticCodes.RESOLVE_IMPORTED_MUTATION),
                "an independently imported direct binding remains an early resolver error after a call: "
                        + render(importedDirect));
    }

    @Test
    public void callMediatedOwnershipDefersToTheCanonicalTypedProducer() {
        ModuleGraph graph = pair(
                "ownership_canonical_deferred",
                "import ownership_canonical_deferred_lib->{values} "
                        + "let id :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |value :Array<I32>| value) "
                        + "let @mut alias :Array<I32> = (id values) "
                        + "let bad = (alias[0] := 1)",
                "let @pub values :Array<I32> = Array[0]");

        PhaseResult<ResolvedSemanticGraph> resolved = resolve(graph);
        check(resolved instanceof PhaseResult.Success<?>,
                "the resolver no longer replays callable bodies for ownership");
        PhaseResult<TypedSemanticGraph> typed = TypeChecker.check(
                ((PhaseResult.Success<ResolvedSemanticGraph>) resolved).value());
        Diagnostic diagnostic = importedDiagnostic(typed);
        check(diagnostic.primarySpan().equals(spanOf(graph, "alias[0]"))
                        && diagnostic.relatedSpans().getFirst().span()
                        .equals(spanOf(graph, "values"))
                        && diagnostic.relatedSpans().getFirst().label()
                        .equals("imported aggregate binding"),
                "canonical call transfer preserves the exact ownership diagnostic: "
                        + diagnostic.render());
    }

    @Test
    public void canonicalDiagnosticsRetainTheExactImportOccurrence() {
        ModuleGraph graph = pair(
                "ownership_exact_import_occurrence",
                "import ownership_exact_import_occurrence_lib->{"
                        + "values as first values as second} "
                        + "let id :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |value :Array<I32>| value) "
                        + "let @mut alias :Array<I32> = (id first) "
                        + "let unrelated :Array<I32> = second "
                        + "let bad = (alias[0] := 1)",
                "let @pub values :Array<I32> = Array[0]");
        PhaseResult<ResolvedSemanticGraph> resolved = resolve(graph);
        check(resolved instanceof PhaseResult.Success<?>,
                "call-mediated ownership reaches canonical analysis");
        Diagnostic diagnostic = importedDiagnostic(TypeChecker.check(
                ((PhaseResult.Success<ResolvedSemanticGraph>) resolved).value()));
        check(diagnostic.relatedSpans().size() == 1
                        && diagnostic.relatedSpans().getFirst().span()
                        .equals(spanOf(graph, "first")),
                "an unrelated alias of the same export must not replace or broaden the exact import witness: "
                        + diagnostic.render());
    }

    @Test
    public void foreignCallableResultsKeepTheCallBoundaryWitness() {
        ModuleGraph graph = pair(
                "ownership_foreign_result_boundary",
                "import ownership_foreign_result_boundary_lib->{get} "
                        + "let @mut result :Array<I32> = (get) "
                        + "let bad = (result[0] := 1)",
                "let hidden :Array<I32> = Array[0] "
                        + "let @pub get :Fn<;Array<I32>> = (=> | | hidden)");
        PhaseResult<ResolvedSemanticGraph> resolved = resolve(graph);
        check(resolved instanceof PhaseResult.Success<?>,
                "foreign callable result ownership is canonical typed flow");
        Diagnostic diagnostic = importedDiagnostic(TypeChecker.check(
                ((PhaseResult.Success<ResolvedSemanticGraph>) resolved).value()));
        check(diagnostic.relatedSpans().getFirst().span()
                        .equals(spanOfLast(graph, "get")),
                "a foreign hidden aggregate retains the callable boundary witness: "
                        + diagnostic.render());
    }

    @Test
    public void uninvokedBodiesUseSummaryRequirementsWithoutExecutingTheBody() {
        ModuleGraph graph = pair(
                "ownership_uninvoked_requirement",
                "import ownership_uninvoked_requirement_lib->{values} "
                        + "let id :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |value :Array<I32>| value) "
                        + "let invalid :Fn<;Unit> = (=> | | { "
                        + "let @mut alias :Array<I32> = (id values) "
                        + "alias[0] := 1 })",
                "let @pub values :Array<I32> = Array[0]");

        PhaseResult<ResolvedSemanticGraph> resolved = resolve(graph);
        check(resolved instanceof PhaseResult.Success<?>,
                "an opaque call in a lambda body is deferred without leaking body state");
        PhaseResult<TypedSemanticGraph> typed = TypeChecker.check(
                ((PhaseResult.Success<ResolvedSemanticGraph>) resolved).value());
        Diagnostic diagnostic = importedDiagnostic(typed);
        check(diagnostic.primarySpan().equals(spanOf(graph, "alias[0]")),
                "a precomputed summary requirement validates an uninvoked body without execution");
    }

    @Test
    public void computedNamespaceCallableKeepsUninvokedMutableArgumentOwnership() {
        ModuleId main = ModuleId.path("ownership_computed_mut_main.lyra");
        ModuleId functions = ModuleId.path(
                "ownership_computed_mut_functions.lyra");
        ModuleId data = ModuleId.path("ownership_computed_mut_data.lyra");
        LogicalModuleId functionsLogical = LogicalModuleId.parse(
                "ownership_computed_mut_functions");
        LogicalModuleId dataLogical = LogicalModuleId.parse(
                "ownership_computed_mut_data");
        ModuleGraph graph = graph(
                List.of(
                        module(main,
                                "import ownership_computed_mut_functions "
                                        + "import ownership_computed_mut_data->{values} "
                                        + "let invalid :Fn<;Unit> = (=> | | "
                                        + "ownership_computed_mut_functions->::computed[values])"),
                        module(functions,
                                "let make :Fn<;Fn<@mut Array<I32>;Unit>> = "
                                        + "(=> | | (=> |@mut items :Array<I32>| "
                                        + "(items[0] := 1))) "
                                        + "let @pub computed :Fn<@mut Array<I32>;Unit> = (make)"),
                        module(data, "let @pub values :Array<I32> = Array[0]")),
                main,
                List.of(
                        new ModuleGraph.Edge(main, functionsLogical, functions,
                                SourceSpan.of(main.sourceId(), 0, 1)),
                        new ModuleGraph.Edge(main, dataLogical, data,
                                SourceSpan.of(main.sourceId(), 1, 2))),
                Map.of(functionsLogical, functions, dataLogical, data));
        PhaseResult<ResolvedSemanticGraph> resolved = resolve(graph);
        check(resolved instanceof PhaseResult.Success<?>,
                "computed namespace ownership reaches canonical typed flow");
        Diagnostic diagnostic = importedDiagnostic(TypeChecker.check(
                ((PhaseResult.Success<ResolvedSemanticGraph>) resolved).value()));

        check(diagnostic.primarySpan().equals(spanOfLast(graph, "values")),
                "computed callable mutable arguments retain the exact source site: "
                        + diagnostic.render());
    }

    @Test
    public void returnedUninvokedClosuresMaterializeCapturedCallableRequirements() {
        ModuleGraph graph = pair(
                "ownership_returned_uninvoked",
                "import ownership_returned_uninvoked_lib->{values} "
                        + "let id :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |value :Array<I32>| value) "
                        + "let make :Fn<Fn<Array<I32>;Array<I32>>;Fn<;Unit>> = "
                        + "(=> |function :Fn<Array<I32>;Array<I32>>| (=> | | { "
                        + "let @mut alias :Array<I32> = (function values) "
                        + "alias[0] := 1 })) "
                        + "let invalid :Fn<;Unit> = (make id)",
                "let @pub values :Array<I32> = Array[0]");
        PhaseResult<ResolvedSemanticGraph> resolved = resolve(graph);
        check(resolved instanceof PhaseResult.Success<?>,
                "returned closure bodies are not source-replayed");
        Diagnostic diagnostic = importedDiagnostic(TypeChecker.check(
                ((PhaseResult.Success<ResolvedSemanticGraph>) resolved).value()));
        check(diagnostic.primarySpan().equals(spanOf(graph, "alias[0]")),
                "the returned closure requirement is materialized from its captured callable without body execution");
    }

    @Test
    public void unrelatedEarlierTypeFailureKeepsFirstDiagnosticPrecedence() {
        ModuleGraph graph = pair(
                "ownership_deferred_precedence",
                "import ownership_deferred_precedence_lib->{values} "
                        + "let accept :Fn<@mut Array<I32>;Unit> = "
                        + "(=> |@mut value :Array<I32>| ()) "
                        + "let invalid :Fn<Array<I32>;Unit> = "
                        + "(=> |value :Array<I32>| (accept value)) "
                        + "let id :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |value :Array<I32>| value) "
                        + "let @mut alias :Array<I32> = (id values) "
                        + "let bad = (alias[0] := 1)",
                "let @pub values :Array<I32> = Array[0]");
        PhaseResult<ResolvedSemanticGraph> resolved = resolve(graph);
        check(resolved instanceof PhaseResult.Success<?>,
                "both callable-dependent checks are deferred from resolution");
        PhaseResult<TypedSemanticGraph> typed = TypeChecker.check(
                ((PhaseResult.Success<ResolvedSemanticGraph>) resolved).value());
        check(typed instanceof PhaseResult.Failure<?>
                        && typed.diagnostics().getFirst().code()
                        .equals(CompilerDiagnosticCodes.TYPE_MISMATCH),
                "an unrelated earlier type error must not be displaced by a later ownership violation: "
                        + render(typed));
    }

    @Test
    public void nestedMutableArgumentRequirementsDoNotDependOnObservedWrites() {
        ModuleGraph graph = pair(
                "ownership_mutable_obligation",
                "import ownership_mutable_obligation_lib->{values} "
                        + "let accept :Fn<@mut Array<I32>;Unit> = "
                        + "(=> |@mut value :Array<I32>| ()) "
                        + "let apply :Fn<Array<I32>;Unit> = "
                        + "(=> |value :Array<I32>| (accept value)) "
                        + "let bad = (apply values)",
                "let @pub values :Array<I32> = Array[0]");

        PhaseResult<ResolvedSemanticGraph> resolved = resolve(graph);
        check(resolved instanceof PhaseResult.Success<?>,
                "higher-order mutable obligations are not source-replayed by resolution");
        PhaseResult<TypedSemanticGraph> typed = TypeChecker.check(
                ((PhaseResult.Success<ResolvedSemanticGraph>) resolved).value());
        Diagnostic diagnostic = importedDiagnostic(typed);
        String source = graph.module(graph.rootModule()).orElseThrow().snapshot().text();
        check(diagnostic.primarySpan().sourceId().equals(graph.rootModule().sourceId())
                        && source.substring(
                        diagnostic.primarySpan().startOffset(),
                        diagnostic.primarySpan().endOffset()).equals("value"),
                "the nested @mut obligation rejects imported ownership even when the callee writes nothing");
    }

    private static Diagnostic importedDiagnostic(PhaseResult<?> result) {
        check(result instanceof PhaseResult.Failure<?> && result.optionalValue().isEmpty(),
                "expected canonical imported-ownership failure: " + render(result));
        Diagnostic diagnostic = result.diagnostics().getFirst();
        check(diagnostic.code().equals(CompilerDiagnosticCodes.RESOLVE_IMPORTED_MUTATION)
                        && diagnostic.phase() == io.mindspice.lyra.compiler.diagnostic.Phase.RESOLVE
                        && diagnostic.severity() == Severity.ERROR,
                "canonical ownership retains the resolver diagnostic contract: "
                        + diagnostic.render());
        return diagnostic;
    }

    private static SourceSpan spanOf(ModuleGraph graph, String text) {
        String source = graph.module(graph.rootModule()).orElseThrow().snapshot().text();
        int start = source.indexOf(text);
        check(start >= 0, "fixture text is absent: " + text);
        return SourceSpan.of(graph.rootModule().sourceId(), start, start + text.length());
    }

    private static SourceSpan spanOfLast(ModuleGraph graph, String text) {
        String source = graph.module(graph.rootModule()).orElseThrow().snapshot().text();
        int start = source.lastIndexOf(text);
        check(start >= 0, "fixture text is absent: " + text);
        return SourceSpan.of(graph.rootModule().sourceId(), start, start + text.length());
    }

    private static void expectImportedFailure(
            PhaseResult<ResolvedSemanticGraph> result,
            ModuleGraph graph,
            String target) {
        PhaseResult<?> checked = result instanceof PhaseResult.Success<ResolvedSemanticGraph> success
                ? TypeChecker.check(success.value())
                : result;
        check(checked instanceof PhaseResult.Failure<?> && checked.optionalValue().isEmpty(),
                "expected an imported-ownership failure: " + render(checked));
        Diagnostic diagnostic = checked.diagnostics().getFirst();
        check(diagnostic.code().equals(CompilerDiagnosticCodes.RESOLVE_IMPORTED_MUTATION)
                        && diagnostic.phase() == io.mindspice.lyra.compiler.diagnostic.Phase.RESOLVE
                        && diagnostic.severity() == Severity.ERROR,
                "imported ownership uses the resolver diagnostic shape: " + diagnostic.render());
        if (graph != null) {
            int start = graph.module(graph.rootModule()).orElseThrow().snapshot()
                    .text().indexOf(target);
            check(start >= 0 && diagnostic.primarySpan().equals(SourceSpan.of(
                    graph.rootModule().sourceId(), start, start + target.length())),
                    "ownership primary span covers the complete target projection: "
                            + diagnostic.render());
        }
        check(!diagnostic.relatedSpans().isEmpty(),
                "ownership diagnostics retain an origin related span");
    }

    private static void expectTypedFailure(ModuleGraph graph, String code) {
        PhaseResult<ResolvedSemanticGraph> resolved = resolve(graph);
        check(resolved instanceof PhaseResult.Success<?>,
                "expected resolution before type failure: " + render(resolved));
        PhaseResult<TypedSemanticGraph> typed = TypeChecker.check(
                ((PhaseResult.Success<ResolvedSemanticGraph>) resolved).value());
        check(typed instanceof PhaseResult.Failure<?> && typed.optionalValue().isEmpty()
                        && typed.diagnostics().getFirst().code().value().equals(code),
                "unexpected typed failure: " + render(typed));
    }

    private static PhaseResult<ResolvedSemanticGraph> resolve(ModuleGraph graph) {
        return SemanticResolver.resolve(graph);
    }

    private static ModuleGraph single(String source) {
        ModuleId id = ModuleId.path("ownership_single.lyra");
        return graph(List.of(module(id, source)), id, List.of(), Map.of());
    }

    private static ModuleGraph pair(String stem, String mainSource, String librarySource) {
        ModuleId main = ModuleId.path(stem + "_main.lyra");
        ModuleId library = ModuleId.path(stem + "_lib.lyra");
        LogicalModuleId logical = LogicalModuleId.parse(stem + "_lib");
        return graph(
                List.of(module(main, mainSource), module(library, librarySource)),
                main,
                List.of(new ModuleGraph.Edge(
                        main, logical, library, SourceSpan.of(main.sourceId(), 0, 1))),
                Map.of(logical, library));
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

    @SuppressWarnings("unchecked")
    private static SyntaxProgram parse(SourceSnapshot snapshot) {
        PhaseResult<LexedSource> lexed = Lexer.lex(snapshot);
        if (!(lexed instanceof PhaseResult.Success<?> success)) {
            throw new AssertionError("lex failed: " + render(lexed));
        }
        PhaseResult<GrammarProgram> grammar = GrammarMatcher.match(
                ((PhaseResult.Success<LexedSource>) success).value());
        if (!(grammar instanceof PhaseResult.Success<?> grammarSuccess)) {
            throw new AssertionError("grammar failed: " + render(grammar));
        }
        PhaseResult<SyntaxProgram> parsed = Parser.parse(
                ((PhaseResult.Success<LexedSource>) success).value(),
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
