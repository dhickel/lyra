import org.junit.jupiter.api.Test;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ExportId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.semantic.flow.AggregateIdentityFact;
import io.mindspice.lyra.compiler.semantic.flow.ArrayIdentity;
import io.mindspice.lyra.compiler.semantic.flow.BindingFlowState;
import io.mindspice.lyra.compiler.semantic.flow.BindingFlowValue;
import io.mindspice.lyra.compiler.semantic.flow.OwnershipWitness;
import io.mindspice.lyra.compiler.semantic.flow.ProjectionPath;
import io.mindspice.lyra.compiler.semantic.flow.ProjectionStep;
import io.mindspice.lyra.compiler.semantic.flow.ValueAlternative;
import io.mindspice.lyra.compiler.semantic.flow.ValueAlternatives;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.BindingContract;
import io.mindspice.lyra.compiler.types.LyraSignature;
import io.mindspice.lyra.compiler.types.PrimitiveType;
import io.mindspice.lyra.compiler.types.TupleType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Bounded, deterministic law coverage for the pure phase-11A flow kernel. */
public final class SemanticFlowAlgebraTest {
    private static final ModuleId MAIN = ModuleId.path("main.lyra");
    private static final ModuleId LIBRARY = ModuleId.path("library.lyra");
    private static final ArrayType I32_ARRAY = ArrayType.of(PrimitiveType.I32);

    @Test
    public void projectionRouteKindsAndOverlapAreExhaustive() {
        List<ProjectionStep> steps = List.of(
                new ProjectionStep.TupleMember(0),
                new ProjectionStep.TupleMember(1),
                new ProjectionStep.ArrayElement(0),
                new ProjectionStep.ArrayElement(1),
                ProjectionStep.unknownArrayElement());

        for (ProjectionStep left : steps) {
            for (ProjectionStep right : steps) {
                boolean expected = expectedStepOverlap(left, right);
                check(left.overlaps(right) == expected,
                        "unexpected selector overlap: " + left + " and " + right);
                check(left.overlaps(right) == right.overlaps(left),
                        "selector overlap must be symmetric");
            }
        }

        check(new ProjectionStep.TupleMember(0).isTupleMember(),
                "tuple selectors retain their distinct route kind");
        check(new ProjectionStep.ArrayElement(0).isArrayElement()
                        && !new ProjectionStep.ArrayElement(0).isWildcardArrayElement(),
                "exact array selectors retain their distinct route kind");
        check(ProjectionStep.unknownArrayElement().isArrayElement()
                        && ProjectionStep.unknownArrayElement().isWildcardArrayElement(),
                "unknown array selectors are explicit wildcards");
        check(!ProjectionStep.unknownArrayElement()
                        .overlaps(new ProjectionStep.TupleMember(0)),
                "wildcard array selectors never overlap tuple members");
    }

    @Test
    public void projectionCompositionSelectionAndOrderingAreDeterministic() {
        ProjectionPath root = ProjectionPath.root();
        ProjectionPath tupleZero = ProjectionPath.tupleMember(0);
        ProjectionPath tupleOne = ProjectionPath.tupleMember(1);
        ProjectionPath arrayZero = ProjectionPath.arrayElement(0);
        ProjectionPath arrayOne = ProjectionPath.arrayElement(1);
        ProjectionPath wildcard = ProjectionPath.unknownArrayElement();
        ProjectionPath nested = tupleZero.compose(arrayOne);

        check(root.compose(nested).equals(nested), "root composition is identity");
        check(tupleZero.compose(arrayOne).equals(nested),
                "composition preserves prefix-to-child order");
        check(arrayOne.prepend(ProjectionStep.tupleMember(0)).equals(nested),
                "prepending one selector preserves complete nesting");
        check(nested.prefixedBy(root).equals(nested),
                "root prefixing is identity");
        check(tupleZero.isPrefixOf(nested) && !nested.isPrefixOf(tupleZero),
                "nested routes have deterministic prefix direction");
        check(tupleZero.suffixAfter(tupleZero).orElseThrow().isRoot(),
                "selecting an exact route rebases to the root");
        check(tupleZero.suffixAfter(nested).isEmpty(),
                "a child route cannot be a prefix of its parent");

        check(root.selects(nested) && wildcard.selects(arrayZero)
                        && wildcard.selects(wildcard),
                "whole and wildcard paths select compatible descendants");
        check(!arrayZero.selects(wildcard) && !arrayZero.selects(arrayOne),
                "exact selection does not broaden to a wildcard or sibling");
        check(wildcard.overlaps(arrayZero) && !wildcard.overlaps(tupleZero),
                "path overlap retains array/tuple kind separation");
        check(tupleZero.compose(arrayZero).overlaps(tupleZero.compose(wildcard)),
                "nested wildcard paths overlap compatible exact paths");
        check(!tupleZero.compose(arrayZero).overlaps(tupleOne.compose(arrayZero)),
                "nested tuple siblings do not overlap");

        List<ProjectionPath> bounded = List.of(root, tupleZero, tupleOne, arrayZero, arrayOne, wildcard,
                tupleZero.compose(arrayZero), tupleZero.compose(wildcard));
        for (ProjectionPath first : bounded) {
            for (ProjectionPath second : bounded) {
                for (ProjectionPath third : bounded) {
                    check(first.compose(second).compose(third)
                                    .equals(first.compose(second.compose(third))),
                            "path composition must be associative");
                }
            }
        }

        List<ProjectionPath> unsorted = List.of(nested, wildcard, root, arrayOne, tupleZero, arrayZero);
        List<ProjectionPath> first = new ArrayList<>(unsorted);
        first.sort(ProjectionPath::compareTo);
        List<ProjectionPath> replay = new ArrayList<>(List.copyOf(unsorted).reversed());
        replay.sort(ProjectionPath::compareTo);
        check(first.equals(replay), "route ordering is independent of insertion order");
    }

    @Test
    public void identitiesWitnessesAndNestedValueAlternativesRemainTyped() {
        DeclarationId localDeclaration = new DeclarationId(1);
        DeclarationId importedDeclaration = new DeclarationId(2);
        ScopeId localScope = new ScopeId(3);
        ScopeId importedScope = new ScopeId(4);
        ArrayIdentity local = ArrayIdentity.localAllocation(MAIN, localDeclaration, I32_ARRAY);
        ExportId export = ExportId.of(
                LIBRARY,
                "values",
                LyraSignature.of(List.of(), I32_ARRAY));
        ArrayIdentity imported = ArrayIdentity.crossModuleOrigin(
                LIBRARY, importedDeclaration, export, I32_ARRAY);
        OwnershipWitness localWitness = OwnershipWitness.local(
                MAIN, localDeclaration, localScope, span(MAIN, 1, 2));
        OwnershipWitness importedWitness = OwnershipWitness.crossModule(
                LIBRARY, importedDeclaration, importedScope, span(LIBRARY, 5, 6), export);

        check(!local.isImported() && imported.isImported(),
                "local and cross-module origins are distinct identity kinds");
        check(local.equals(ArrayIdentity.localAllocation(MAIN, localDeclaration, I32_ARRAY)),
                "local allocation identity is canonical by module and allocation site");
        check(imported.equals(ArrayIdentity.crossModuleOrigin(
                        LIBRARY, importedDeclaration, export, I32_ARRAY)),
                "cross-module identity is canonical by owner and export origin");
        check(!local.equals(imported), "local and imported identities never collapse");

        AggregateIdentityFact localFact = new AggregateIdentityFact(
                local, ProjectionPath.root(), localWitness);
        AggregateIdentityFact importedFact = new AggregateIdentityFact(
                imported, ProjectionPath.root(), importedWitness);
        TupleType tupleType = TupleType.of(List.of(PrimitiveType.I32, I32_ARRAY));
        ArrayIdentity nestedIdentity = ArrayIdentity.localAllocation(
                MAIN, new DeclarationId(7), I32_ARRAY);
        AggregateIdentityFact nestedFact = new AggregateIdentityFact(
                nestedIdentity,
                ProjectionPath.tupleMember(1),
                OwnershipWitness.local(
                        MAIN, new DeclarationId(7), new ScopeId(8), span(MAIN, 9, 10)));
        ValueAlternative tuple = ValueAlternative.of(tupleType, List.of(nestedFact));
        ValueAlternative selected = tuple.select(ProjectionPath.tupleMember(1));
        check(selected.type().equals(I32_ARRAY)
                        && selected.aggregateIdentities().size() == 1
                        && selected.aggregateIdentities().getFirst().route().isRoot(),
                "nested selection preserves identity and rebases its route");

        ValueAlternatives alternatives = ValueAlternatives.of(
                ValueAlternative.scalar(PrimitiveType.STRING),
                ValueAlternative.array(imported, importedWitness),
                ValueAlternative.array(local, localWitness),
                ValueAlternative.array(local, localWitness));
        check(alternatives.size() == 3,
                "finite alternatives are canonicalized and duplicate-free");
        check(alternatives.equals(new ValueAlternatives(List.of(
                        ValueAlternative.array(local, localWitness),
                        ValueAlternative.array(imported, importedWitness),
                        ValueAlternative.scalar(PrimitiveType.STRING)))),
                "alternative ordering is deterministic");
        check(localFact.isImported() == false && importedFact.isImported(),
                "ownership is carried by identity facts, not binding mutability");
    }

    @Test
    public void snapshotsAreDeeplyImmutableAndBindingMutabilityIsSeparate() {
        DeclarationId declaration = new DeclarationId(0);
        ArrayIdentity identity = ArrayIdentity.localAllocation(MAIN, declaration, I32_ARRAY);
        OwnershipWitness witness = OwnershipWitness.local(
                MAIN, declaration, new ScopeId(0), span(MAIN, 0, 1));
        ValueAlternative value = ValueAlternative.array(identity, witness);
        ValueAlternatives alternatives = ValueAlternatives.singleton(value);
        BindingFlowState state = BindingFlowState.empty().bind(
                declaration,
                BindingContract.mutable(I32_ARRAY),
                alternatives);

        check(state.requireBinding(declaration).isMutable(),
                "binding-local mutation permission is retained on the binding entry");
        check(!state.requireBinding(declaration).alternatives().only()
                        .aggregateIdentities().getFirst().isImported(),
                "aggregate ownership is not inferred from binding-local mutability");
        expectUnsupported(() -> state.bindings().clear());
        expectUnsupported(() -> state.bindings().get(declaration).alternatives()
                .alternatives().clear());
        expectUnsupported(() -> state.bindings().get(declaration).alternatives().only()
                .aggregateIdentities().clear());
        expectUnsupported(() -> state.bindings().get(declaration).alternatives().only()
                .aggregateIdentities().getFirst().route().steps().clear());
        check(state.equals(BindingFlowState.of(new HashMap<>(Map.of(
                        declaration,
                        new BindingFlowValue(BindingContract.mutable(I32_ARRAY), alternatives))))),
                "rebuilding a snapshot from a mutable input map preserves value equality");
    }

    @Test
    public void strongWholeAndExactRouteUpdatesHaveBoundedSiblingEffects() {
        DeclarationId declaration = new DeclarationId(1);
        ArrayType nestedArrayType = ArrayType.of(I32_ARRAY);
        ArrayIdentity outer = ArrayIdentity.localAllocation(MAIN, declaration, nestedArrayType);
        ArrayIdentity left = ArrayIdentity.localAllocation(MAIN, new DeclarationId(2), I32_ARRAY);
        ArrayIdentity right = ArrayIdentity.localAllocation(MAIN, new DeclarationId(3), I32_ARRAY);
        ArrayIdentity replacement = ArrayIdentity.localAllocation(MAIN, new DeclarationId(4), I32_ARRAY);
        ValueAlternative current = ValueAlternative.of(nestedArrayType, List.of(
                fact(outer, ProjectionPath.root()),
                fact(left, ProjectionPath.arrayElement(0)),
                fact(right, ProjectionPath.arrayElement(1))));
        ValueAlternative newElement = ValueAlternative.array(replacement, localWitness(replacement));
        BindingFlowState initial = BindingFlowState.empty().bind(
                declaration,
                BindingContract.mutable(nestedArrayType),
                ValueAlternatives.singleton(current));

        BindingFlowState exact = initial.replaceExactRoute(
                declaration,
                ProjectionPath.arrayElement(0),
                ValueAlternatives.singleton(newElement));
        List<AggregateIdentityFact> exactFacts = exact.requireBinding(declaration)
                .alternatives().only().aggregateIdentities();
        check(containsRoute(exactFacts, ProjectionPath.root(), outer)
                        && !containsRoute(exactFacts, ProjectionPath.arrayElement(0), left)
                        && containsRoute(exactFacts, ProjectionPath.arrayElement(0), replacement)
                        && containsRoute(exactFacts, ProjectionPath.arrayElement(1), right),
                "exact replacement is strong only at its selected route");

        BindingFlowState whole = exact.replaceWholeBinding(
                declaration,
                ValueAlternatives.singleton(ValueAlternative.array(right, localWitness(right))));
        check(whole.requireBinding(declaration).alternatives().only().aggregateIdentities().size() == 1
                        && whole.requireBinding(declaration).alternatives().only()
                        .aggregateIdentities().getFirst().identity().equals(right),
                "whole replacement discards stale route alternatives");
        check(initial.requireBinding(declaration).alternatives().only().aggregateIdentities().size() == 3,
                "all strong updates leave the original snapshot unchanged");
    }

    @Test
    public void wildcardUpdatesAreWeakAndBranchJoinsAreMayStates() {
        DeclarationId declaration = new DeclarationId(10);
        ArrayType nestedArrayType = ArrayType.of(I32_ARRAY);
        ArrayIdentity left = ArrayIdentity.localAllocation(MAIN, new DeclarationId(11), I32_ARRAY);
        ArrayIdentity right = ArrayIdentity.localAllocation(MAIN, new DeclarationId(12), I32_ARRAY);
        ArrayIdentity replacement = ArrayIdentity.localAllocation(MAIN, new DeclarationId(13), I32_ARRAY);
        ValueAlternative current = ValueAlternative.of(nestedArrayType, List.of(
                fact(left, ProjectionPath.arrayElement(0)),
                fact(right, ProjectionPath.arrayElement(1))));
        BindingFlowState initial = BindingFlowState.empty().bind(
                declaration,
                BindingContract.mutable(nestedArrayType),
                ValueAlternatives.singleton(current));

        BindingFlowState weak = initial.replaceUnknownIndex(
                declaration,
                ProjectionPath.unknownArrayElement(),
                ValueAlternatives.singleton(ValueAlternative.array(
                        replacement, localWitness(replacement))));
        List<AggregateIdentityFact> weakFacts = weak.requireBinding(declaration)
                .alternatives().only().aggregateIdentities();
        check(containsRoute(weakFacts, ProjectionPath.arrayElement(0), left)
                        && containsRoute(weakFacts, ProjectionPath.arrayElement(1), right)
                        && containsRoute(weakFacts, ProjectionPath.unknownArrayElement(), replacement),
                "unknown-index replacement retains every prior possibility and adds a wildcard");
        check(initial.requireBinding(declaration).alternatives().only()
                        .aggregateIdentities().size() == 2,
                "weak replacement does not mutate its input snapshot");

        BindingFlowState first = initial.replaceWholeBinding(
                declaration,
                ValueAlternatives.singleton(ValueAlternative.array(left, localWitness(left))));
        BindingFlowState second = initial.replaceWholeBinding(
                declaration,
                ValueAlternatives.singleton(ValueAlternative.array(right, localWitness(right))));
        BindingFlowState joined = first.branchJoin(second);
        check(joined.equals(second.coalesceJoin(first)),
                "branch and coalesce joins are commutative may-state joins");
        check(joined.requireBinding(declaration).alternatives().size() == 2,
                "branch join retains both reachable alternatives");
        check(joined.equals(first.join(second)) && joined.equals(first.join(second).join(joined)),
                "join is idempotent and associative over the bounded state shape");
    }

    @Test
    public void stateAndAlternativeOrderingDoesNotDependOnInputCollections() {
        DeclarationId firstDeclaration = new DeclarationId(1);
        DeclarationId secondDeclaration = new DeclarationId(2);
        ArrayIdentity firstIdentity = ArrayIdentity.localAllocation(
                MAIN, firstDeclaration, I32_ARRAY);
        ArrayIdentity secondIdentity = ArrayIdentity.localAllocation(
                MAIN, secondDeclaration, I32_ARRAY);
        BindingFlowState forward = BindingFlowState.empty()
                .bind(firstDeclaration, BindingContract.immutable(I32_ARRAY),
                        ValueAlternatives.singleton(ValueAlternative.array(
                                firstIdentity, localWitness(firstIdentity))))
                .bind(secondDeclaration, BindingContract.immutable(I32_ARRAY),
                        ValueAlternatives.singleton(ValueAlternative.array(
                                secondIdentity, localWitness(secondIdentity))));
        Map<DeclarationId, BindingFlowValue> reverseInput =
                new HashMap<>();
        reverseInput.put(secondDeclaration, forward.requireBinding(secondDeclaration));
        reverseInput.put(firstDeclaration, forward.requireBinding(firstDeclaration));
        BindingFlowState reverse = BindingFlowState.of(reverseInput);

        check(List.copyOf(forward.bindings().keySet()).equals(
                        List.of(firstDeclaration, secondDeclaration)),
                "binding snapshots iterate in declaration-id order");
        check(forward.equals(reverse)
                        && forward.bindings().toString().equals(reverse.bindings().toString()),
                "state equality and rendering are insertion-order independent");
        check(new HashSet<>(forward.requireBinding(firstDeclaration).alternatives().alternatives())
                        .size() == 1,
                "finite alternative sets remain duplicate-free after reconstruction");
    }

    private static boolean expectedStepOverlap(ProjectionStep left, ProjectionStep right) {
        if (left instanceof ProjectionStep.TupleMember leftTuple
                && right instanceof ProjectionStep.TupleMember rightTuple) {
            return leftTuple.index() == rightTuple.index();
        }
        if (left.isTupleMember() || right.isTupleMember()) {
            return false;
        }
        if (left.isWildcardArrayElement() || right.isWildcardArrayElement()) {
            return true;
        }
        return ((ProjectionStep.ArrayElement) left).index()
                == ((ProjectionStep.ArrayElement) right).index();
    }

    private static AggregateIdentityFact fact(ArrayIdentity identity, ProjectionPath route) {
        return new AggregateIdentityFact(identity, route, localWitness(identity));
    }

    private static OwnershipWitness localWitness(ArrayIdentity identity) {
        return OwnershipWitness.local(
                identity.ownerModule(),
                identity.originDeclaration(),
                new ScopeId(identity.originDeclaration().ordinal()),
                span(identity.ownerModule(), 0, 1));
    }

    private static SourceSpan span(ModuleId module, int start, int end) {
        return SourceSpan.of(module.sourceId(), start, end);
    }

    private static boolean containsRoute(
            List<AggregateIdentityFact> facts,
            ProjectionPath route,
            ArrayIdentity identity) {
        return facts.stream().anyMatch(fact -> fact.route().equals(route)
                && fact.identity().equals(identity));
    }

    private static void expectUnsupported(Runnable action) {
        try {
            action.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("expected an immutable collection");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
