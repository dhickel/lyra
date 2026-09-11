import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.compiler.api.SessionCompileRequest;
import io.mindspice.lyra.compiler.api.SessionCompileResult;
import io.mindspice.lyra.compiler.ast.SyntaxProgram;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.grammar.GrammarMatcher;
import io.mindspice.lyra.compiler.grammar.GrammarProgram;
import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.parse.Parser;
import io.mindspice.lyra.compiler.semantic.ResolvedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.SemanticResolver;
import io.mindspice.lyra.compiler.semantic.TypedDeclaration;
import io.mindspice.lyra.compiler.semantic.TypedExpression;
import io.mindspice.lyra.compiler.semantic.TypedLambda;
import io.mindspice.lyra.compiler.semantic.TypedLink;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.TypeChecker;
import io.mindspice.lyra.compiler.semantic.flow.CallableCallReference;
import io.mindspice.lyra.compiler.semantic.flow.CallableSummary;
import io.mindspice.lyra.compiler.semantic.flow.CallableSummaryCompiler;
import io.mindspice.lyra.compiler.semantic.flow.CallableSummaryResult;
import io.mindspice.lyra.compiler.semantic.flow.CallableSummarySet;
import io.mindspice.lyra.compiler.semantic.flow.CapturedCellWrite;
import io.mindspice.lyra.compiler.semantic.flow.CallableSummarySolver;
import io.mindspice.lyra.compiler.semantic.flow.EagerEffectWitness;
import io.mindspice.lyra.compiler.semantic.flow.FormulaAlternatives;
import io.mindspice.lyra.compiler.semantic.flow.FreshAllocationSite;
import io.mindspice.lyra.compiler.semantic.flow.ProjectionPath;
import io.mindspice.lyra.compiler.semantic.flow.NormalizedExpression;
import io.mindspice.lyra.compiler.semantic.flow.OwnershipRequirement;
import io.mindspice.lyra.compiler.semantic.flow.SummaryCallId;
import io.mindspice.lyra.compiler.semantic.flow.SummaryLimits;
import io.mindspice.lyra.compiler.semantic.flow.SummaryTransferResult;
import io.mindspice.lyra.compiler.semantic.flow.TypedExpressionNormalizer;
import io.mindspice.lyra.compiler.semantic.flow.ValueFormula;
import io.mindspice.lyra.compiler.session.SessionSnapshot;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.ModuleRevision;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.PrimitiveType;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Direct Gate 11D.1 coverage for the internal symbolic callable-summary model. */
public final class CallableSummaryTest {
    @Test
    public void parameterFreshAndSelectedReturnsRemainTypedAndRouted() {
        TypedSemanticGraph graph = typed(
                "let identity :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| input) "
                        + "let fresh :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| Array[0]) "
                        + "let pick :Fn<Tuple<Array<I32>,I32>;Array<I32>> = "
                        + "(=> |input :Tuple<Array<I32>,I32>| input:.0)");
        CallableSummarySet summaries = summaries(graph);

        CallableSummary identity = summaryFor(graph, summaries, "identity");
        TypedLambda identityLambda = graph.lambdas().stream()
                .filter(lambda -> lambda.id().equals(identity.lambdaId()))
                .findFirst().orElseThrow();
        NormalizedExpression normalized = TypedExpressionNormalizer.normalize(identityLambda);
        assertEquals(io.mindspice.lyra.compiler.semantic.TypedExpressionKind.REFERENCE,
                normalized.kind());
        assertEquals(identityLambda.body().type(), normalized.type());
        ValueFormula.Parameter identityReturn = assertInstanceOf(
                ValueFormula.Parameter.class, identity.returnFormula().formulas().stream()
                        .filter(formula -> formula.resultRoute().isRoot())
                        .findFirst().orElseThrow());
        assertEquals(0, identityReturn.parameterIndex());
        assertTrue(identityReturn.parameterRoute().isRoot());
        assertEquals(DeclarationId.class, identity.parameters().getFirst().declarationId().getClass());

        CallableSummary fresh = summaryFor(graph, summaries, "fresh");
        assertTrue(fresh.returnFormula().formulas().stream()
                .anyMatch(ValueFormula.FreshAllocation.class::isInstance));
        assertFalse(fresh.returnFormula().formulas().stream()
                .filter(formula -> formula.resultRoute().isRoot())
                .anyMatch(ValueFormula.Parameter.class::isInstance),
                "a fresh result must not inherit the parameter identity");

        CallableSummary pick = summaryFor(graph, summaries, "pick");
        ValueFormula.Parameter selected = assertInstanceOf(
                ValueFormula.Parameter.class, pick.returnFormula().formulas().stream()
                        .filter(formula -> formula.resultRoute().isRoot())
                        .findFirst().orElseThrow());
        assertEquals(io.mindspice.lyra.compiler.semantic.flow.ProjectionPath.tupleMember(0),
                selected.parameterRoute());
        assertEquals(ArrayType.of(PrimitiveType.I32), selected.type().withoutQualifiers());
    }

    @Test
    public void transitiveIdentityReturnIsSubstitutedThroughKnownCalls() {
        TypedSemanticGraph graph = typed(
                "let identity :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| input) "
                        + "let wrapper :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| (identity input))");
        CallableSummary wrapper = summaryFor(graph, summaries(graph), "wrapper");
        assertTrue(wrapper.calls().stream()
                .anyMatch(call -> call.kind() == CallableCallReference.Kind.CALLABLE));
        assertTrue(wrapper.returnFormula().formulas().stream()
                .anyMatch(formula -> formula instanceof ValueFormula.Parameter
                        && formula.resultRoute().isRoot()),
                wrapper.returnFormula().formulas().toString());
    }

    @Test
    public void callableResultCanBeInvokedAsTheNextCallTarget() {
        TypedSemanticGraph graph = typed(
                "let make :Fn<;Fn<;I32>> = (=> | | (=> | | 7)) "
                        + "let invokeFactory :Fn<Fn<;Fn<;I32>>;I32> = "
                        + "(=> |factory :Fn<;Fn<;I32>>| ((factory))) "
                        + "let caller :Fn<;I32> = (=> | | ((make)))");
        CallableSummarySet summaries = summaries(graph);
        CallableSummary caller = summaryFor(graph, summaries, "caller");

        assertEquals(2, caller.calls().size());
        assertTrue(caller.calls().get(1).target().formulas().stream()
                .allMatch(ValueFormula.Lambda.class::isInstance),
                caller.calls().get(1).target().formulas().toString());
        assertTrue(caller.returnFormula().formulas().stream()
                .anyMatch(ValueFormula.Scalar.class::isInstance),
                caller.returnFormula().formulas().toString());
        assertFalse(caller.returnFormula().formulas().stream()
                .anyMatch(ValueFormula.CallResult.class::isInstance),
                "a callable result with a known producer must materialize during solving");

        SummaryTransferResult.Success applied = assertInstanceOf(
                SummaryTransferResult.Success.class,
                summaries.invoke(caller.lambdaId(), List.of(), caller.span()));
        assertTrue(applied.returnValue().formulas().stream()
                .anyMatch(ValueFormula.Scalar.class::isInstance));

        CallableSummary make = summaryFor(graph, summaries, "make");
        CallableSummary invokeFactory = summaryFor(graph, summaries, "invokeFactory");
        assertTrue(invokeFactory.returnFormula().formulas().stream()
                .anyMatch(ValueFormula.CallResult.class::isInstance),
                "a caller-provided producer remains deferred in its parameterized summary");
        SummaryTransferResult.Success deferred = assertInstanceOf(
                SummaryTransferResult.Success.class,
                summaries.invoke(invokeFactory.lambdaId(), List.of(
                        FormulaAlternatives.singleton(new ValueFormula.Lambda(
                                make.lambdaId(), make.signature().asFunctionType()))),
                        invokeFactory.span()));
        assertTrue(deferred.returnValue().formulas().stream()
                .anyMatch(ValueFormula.Scalar.class::isInstance));

        SummaryTransferResult.Failure missing = assertInstanceOf(
                SummaryTransferResult.Failure.class,
                summaries.invoke(invokeFactory.lambdaId(), List.of(
                        FormulaAlternatives.singleton(new ValueFormula.Opaque(
                                make.signature().asFunctionType(),
                                "caller factory without summary identity"))),
                        invokeFactory.span()));
        assertEquals(CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                missing.failure().kind());
    }

    @Test
    public void callableResultCanFlowAsAHigherOrderArgument() {
        TypedSemanticGraph graph = typed(
                "let make :Fn<I32;Fn<;I32>> = "
                        + "(=> |value :I32| (=> | | value)) "
                        + "let apply :Fn<Fn<;I32>;I32> = "
                        + "(=> |f :Fn<;I32>| (f)) "
                        + "let caller :Fn<I32;I32> = "
                        + "(=> |value :I32| (apply (make value)))");
        CallableSummarySet summaries = summaries(graph);
        CallableSummary caller = summaryFor(graph, summaries, "caller");

        ValueFormula.Lambda producedArgument = assertInstanceOf(
                ValueFormula.Lambda.class,
                caller.calls().get(1).arguments().getFirst().only());
        assertTrue(producedArgument.captures().values().stream()
                .flatMap(value -> value.formulas().stream())
                .anyMatch(formula -> formula instanceof ValueFormula.Parameter parameter
                        && parameter.parameterIndex() == 0),
                producedArgument.captures().toString());
        assertTrue(caller.returnFormula().formulas().stream()
                .anyMatch(formula -> formula instanceof ValueFormula.Parameter parameter
                        && parameter.parameterIndex() == 0
                        && parameter.parameterRoute().isRoot()
                        && parameter.resultRoute().isRoot()),
                caller.returnFormula().formulas().toString());
        assertFalse(caller.returnFormula().formulas().stream()
                .anyMatch(ValueFormula.CallResult.class::isInstance),
                "a known higher-order producer must materialize without losing caller substitution");

        FormulaAlternatives actual = FormulaAlternatives.singleton(
                ValueFormula.Scalar.literal(PrimitiveType.I32, "caller-value"));
        SummaryTransferResult.Success applied = assertInstanceOf(
                SummaryTransferResult.Success.class,
                summaries.invoke(caller.lambdaId(), List.of(actual), caller.span()));
        assertEquals(actual, applied.returnValue());
    }

    @Test
    public void higherOrderParameterInvocationIsAnExplicitCallReference() {
        TypedSemanticGraph graph = typed(
                "let apply :Fn<Fn<Array<I32>;Array<I32>>,Array<I32>;Array<I32>> = "
                        + "(=> |f :Fn<Array<I32>;Array<I32>> items :Array<I32>| (f items))");
        CallableSummary summary = summaryFor(graph, summaries(graph), "apply");
        CallableCallReference call = summary.calls().stream().findFirst().orElseThrow();
        assertEquals(CallableCallReference.Kind.PARAMETER, call.kind());
        assertTrue(call.targetParameterIndex().isPresent());
        assertEquals(0, call.targetParameterIndex().orElseThrow());
        assertEquals(1, call.arguments().size());
        assertInstanceOf(ValueFormula.Parameter.class,
                call.arguments().getFirst().only());
    }

    @Test
    public void callableParameterUnionsRemainSymbolicUntilCallerTransfer() {
        TypedSemanticGraph graph = typed(
                "let one :Fn<I32;I32> = (=> |value :I32| 1) "
                        + "let two :Fn<I32;I32> = (=> |value :I32| 2) "
                        + "let applyEither :Fn<Bool,Fn<I32;I32>,Fn<I32;I32>,I32;I32> = "
                        + "(=> |flag :Bool left :Fn<I32;I32> right :Fn<I32;I32> value :I32| { "
                        + "let @mut selected :Fn<I32;I32> = left "
                        + "let ignored = (flag -> (selected := left) : (selected := right)) "
                        + "(selected value) })");
        CallableSummarySet summaries = summaries(graph);
        CallableSummary applyEither = summaryFor(graph, summaries, "applyEither");
        CallableCallReference call = applyEither.calls().getFirst();

        assertEquals(CallableCallReference.Kind.PARAMETER, call.kind());
        assertEquals(List.of(1, 2), call.targetParameterIndexes());
        assertTrue(call.targetParameterIndex().isEmpty(),
                "a placeholder union must not invent a singular parameter target");
        assertTrue(call.target().formulas().stream()
                .allMatch(ValueFormula.Parameter.class::isInstance));

        CallableSummary one = summaryFor(graph, summaries, "one");
        CallableSummary two = summaryFor(graph, summaries, "two");
        SummaryTransferResult.Success applied = assertInstanceOf(
                SummaryTransferResult.Success.class,
                summaries.invoke(applyEither.lambdaId(), List.of(
                                FormulaAlternatives.singleton(
                                        new ValueFormula.Scalar(PrimitiveType.BOOL)),
                                FormulaAlternatives.singleton(new ValueFormula.Lambda(
                                        one.lambdaId(), one.signature().asFunctionType())),
                                FormulaAlternatives.singleton(new ValueFormula.Lambda(
                                        two.lambdaId(), two.signature().asFunctionType())),
                                FormulaAlternatives.singleton(
                                        new ValueFormula.Scalar(PrimitiveType.I32))),
                        applyEither.span()));
        assertEquals(2, applied.returnValue().size());
        assertTrue(applied.returnValue().formulas().stream()
                .allMatch(ValueFormula.Scalar.class::isInstance));
    }

    @Test
    public void mixedKnownAndParameterTargetsDeferUntilCallerTransfer() {
        TypedSemanticGraph graph = typed(
                "let one :Fn<I32;I32> = (=> |value :I32| 1) "
                        + "let two :Fn<I32;I32> = (=> |value :I32| 2) "
                        + "let applyEither :Fn<Bool,Fn<I32;I32>,I32;I32> = "
                        + "(=> |flag :Bool fallback :Fn<I32;I32> value :I32| { "
                        + "let @mut selected :Fn<I32;I32> = one "
                        + "let ignored = (flag -> (selected := one) : (selected := fallback)) "
                        + "(selected value) })");
        CallableSummarySet summaries = summaries(graph);
        CallableSummary applyEither = summaryFor(graph, summaries, "applyEither");
        CallableCallReference call = applyEither.calls().getFirst();

        assertEquals(CallableCallReference.Kind.CALLABLE, call.kind());
        assertEquals(List.of(1), call.targetParameterIndexes());
        assertTrue(call.target().formulas().stream()
                .anyMatch(ValueFormula.Lambda.class::isInstance));
        assertTrue(call.target().formulas().stream()
                .anyMatch(ValueFormula.Parameter.class::isInstance));
        assertTrue(applyEither.returnFormula().formulas().stream()
                .anyMatch(ValueFormula.CallResult.class::isInstance),
                "caller-dependent target unions must remain symbolic in the solved summary");

        CallableSummary two = summaryFor(graph, summaries, "two");
        SummaryTransferResult.Success applied = assertInstanceOf(
                SummaryTransferResult.Success.class,
                summaries.invoke(applyEither.lambdaId(), List.of(
                                FormulaAlternatives.singleton(
                                        new ValueFormula.Scalar(PrimitiveType.BOOL)),
                                FormulaAlternatives.singleton(new ValueFormula.Lambda(
                                        two.lambdaId(), two.signature().asFunctionType())),
                                FormulaAlternatives.singleton(
                                        new ValueFormula.Scalar(PrimitiveType.I32))),
                        applyEither.span()));
        assertEquals(2, applied.returnValue().size());
        assertTrue(applied.returnValue().formulas().stream()
                .allMatch(ValueFormula.Scalar.class::isInstance));

        SummaryTransferResult missing = summaries.invoke(
                applyEither.lambdaId(), List.of(
                        FormulaAlternatives.singleton(
                                new ValueFormula.Scalar(PrimitiveType.BOOL)),
                        FormulaAlternatives.singleton(new ValueFormula.Opaque(
                                two.signature().asFunctionType(),
                                "caller callable without summary identity")),
                        FormulaAlternatives.singleton(
                                new ValueFormula.Scalar(PrimitiveType.I32))),
                applyEither.span());
        SummaryTransferResult.Failure missingFailure = assertInstanceOf(
                SummaryTransferResult.Failure.class, missing);
        assertEquals(CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                missingFailure.failure().kind());
    }

    @Test
    public void mixedKnownAndCaptureTargetsDeferUntilClosureInvocation() {
        TypedSemanticGraph graph = typed(
                "let two :Fn<I32;I32> = (=> |value :I32| 2) "
                        + "let make :Fn<Fn<I32;I32>;Fn<Bool,I32;I32>> = "
                        + "(=> |fallback :Fn<I32;I32>| "
                        + "(=> |flag :Bool value :I32| { "
                        + "let one :Fn<I32;I32> = (=> |item :I32| 1) "
                        + "let @mut selected :Fn<I32;I32> = one "
                        + "let ignored = (flag -> (selected := one) : (selected := fallback)) "
                        + "(selected value) }))");
        CallableSummarySet summaries = summaries(graph);
        CallableSummary inner = summaries.orderedSummaries().stream()
                .filter(summary -> summary.calls().stream().anyMatch(call ->
                        !call.targetCaptureIds().isEmpty()
                                && call.target().formulas().stream()
                                .anyMatch(ValueFormula.Lambda.class::isInstance)))
                .findFirst().orElseThrow();
        CallableCallReference call = inner.calls().getFirst();

        assertEquals(CallableCallReference.Kind.CALLABLE, call.kind());
        assertEquals(1, call.targetCaptureIds().size());
        assertTrue(call.target().formulas().stream()
                .anyMatch(ValueFormula.Capture.class::isInstance));
        assertTrue(inner.returnFormula().formulas().stream()
                .anyMatch(ValueFormula.CallResult.class::isInstance),
                "caller-dependent capture unions must remain symbolic in the solved summary");

        CallableSummary make = summaryFor(graph, summaries, "make");
        CallableSummary two = summaryFor(graph, summaries, "two");
        SummaryTransferResult.Success made = assertInstanceOf(
                SummaryTransferResult.Success.class,
                summaries.invoke(make.lambdaId(), List.of(
                                FormulaAlternatives.singleton(new ValueFormula.Lambda(
                                        two.lambdaId(), two.signature().asFunctionType()))),
                        make.span()));
        SummaryTransferResult.Success applied = assertInstanceOf(
                SummaryTransferResult.Success.class,
                summaries.invoke(made.returnValue(), List.of(
                        FormulaAlternatives.singleton(
                                new ValueFormula.Scalar(PrimitiveType.BOOL)),
                        FormulaAlternatives.singleton(
                                new ValueFormula.Scalar(PrimitiveType.I32))), make.span()));
        assertEquals(2, applied.returnValue().size());
        assertTrue(applied.returnValue().formulas().stream()
                .allMatch(ValueFormula.Scalar.class::isInstance));
    }

    @Test
    public void callableCaptureUnionsRemainSymbolicUntilClosureInvocation() {
        TypedSemanticGraph graph = typed(
                "let one :Fn<I32;I32> = (=> |value :I32| 1) "
                        + "let two :Fn<I32;I32> = (=> |value :I32| 2) "
                        + "let make :Fn<Fn<I32;I32>,Fn<I32;I32>;Fn<Bool,I32;I32>> = "
                        + "(=> |left :Fn<I32;I32> right :Fn<I32;I32>| "
                        + "(=> |flag :Bool value :I32| { "
                        + "let @mut selected :Fn<I32;I32> = left "
                        + "let ignored = (flag -> (selected := left) : (selected := right)) "
                        + "(selected value) }))");
        CallableSummarySet summaries = summaries(graph);
        CallableSummary inner = summaries.orderedSummaries().stream()
                .filter(summary -> summary.calls().stream()
                        .anyMatch(call -> call.kind() == CallableCallReference.Kind.CAPTURE))
                .findFirst().orElseThrow();
        CallableCallReference call = inner.calls().getFirst();

        assertEquals(2, call.targetCaptureIds().size());
        assertTrue(call.targetCaptureId().isEmpty(),
                "a placeholder union must not invent a singular capture target");
        assertTrue(call.target().formulas().stream()
                .allMatch(ValueFormula.Capture.class::isInstance));

        CallableSummary make = summaryFor(graph, summaries, "make");
        CallableSummary one = summaryFor(graph, summaries, "one");
        CallableSummary two = summaryFor(graph, summaries, "two");
        SummaryTransferResult.Success made = assertInstanceOf(
                SummaryTransferResult.Success.class,
                summaries.invoke(make.lambdaId(), List.of(
                                FormulaAlternatives.singleton(new ValueFormula.Lambda(
                                        one.lambdaId(), one.signature().asFunctionType())),
                                FormulaAlternatives.singleton(new ValueFormula.Lambda(
                                        two.lambdaId(), two.signature().asFunctionType()))),
                        make.span()));
        SummaryTransferResult.Success applied = assertInstanceOf(
                SummaryTransferResult.Success.class,
                summaries.invoke(made.returnValue(), List.of(
                        FormulaAlternatives.singleton(
                                new ValueFormula.Scalar(PrimitiveType.BOOL)),
                        FormulaAlternatives.singleton(
                                new ValueFormula.Scalar(PrimitiveType.I32))), make.span()));
        assertEquals(2, applied.returnValue().size());
        assertTrue(applied.returnValue().formulas().stream()
                .allMatch(ValueFormula.Scalar.class::isInstance));
    }

    @Test
    public void mutableParameterWritesRebaseToTheCallerCaptureCell() {
        TypedSemanticGraph graph = typed(
                "let update :Fn<@mut Array<I32>;Unit> = "
                        + "(=> |items| (items[0] := 1)) "
                        + "let outer :Fn<@mut Array<I32>;Fn<;Unit>> = "
                        + "(=> |items| (=> | | (update items)))");
        CallableSummarySet summaries = summaries(graph);
        CallableSummary update = summaryFor(graph, summaries, "update");
        CallableSummary inner = summaries.orderedSummaries().stream()
                .filter(summary -> summary.signature().arity() == 0)
                .findFirst().orElseThrow();
        CallableSummary.CapturePlaceholder innerCapture = inner.captures().stream()
                .filter(CallableSummary.CapturePlaceholder::isSharedCell)
                .findFirst().orElseThrow();
        ValueFormula.Capture callArgument = assertInstanceOf(
                ValueFormula.Capture.class,
                inner.calls().getFirst().arguments().getFirst().only());

        assertEquals(innerCapture.sharedCellId(), callArgument.sharedCellId());

        CaptureId callerCapture = new CaptureId(10_000);
        DeclarationId callerDeclaration = new DeclarationId(10_001);
        DeclarationId callerCell = new DeclarationId(10_002);
        FormulaAlternatives callerFact = FormulaAlternatives.singleton(
                new ValueFormula.Capture(
                        callerCapture, callerDeclaration, Optional.of(callerCell),
                        ProjectionPath.root(), ProjectionPath.root(),
                        ArrayType.of(PrimitiveType.I32)));
        CallableSummary.CapturePlaceholder callableCapture = inner.captures().stream()
                .filter(capture -> !capture.isSharedCell())
                .findFirst().orElseThrow();
        FormulaAlternatives callableFact = FormulaAlternatives.singleton(
                new ValueFormula.Lambda(
                        update.lambdaId(), update.signature().asFunctionType()));
        SummaryTransferResult appliedResult = summaries.invoke(
                inner.lambdaId(), List.of(),
                Map.of(innerCapture.captureId(), callerFact,
                        callableCapture.captureId(), callableFact),
                inner.span());
        SummaryTransferResult.Success applied = assertInstanceOf(
                SummaryTransferResult.Success.class, appliedResult, appliedResult::toString);

        assertEquals(1, applied.writes().size());
        assertEquals(callerCapture, applied.writes().getFirst().capture());
        assertEquals(Optional.of(callerCell),
                applied.writes().getFirst().sharedCellId());
        assertEquals(ProjectionPath.arrayElement(0),
                applied.writes().getFirst().route());
    }

    @Test
    public void directCallableParameterTargetRemainsCallerParameterized() {
        TypedSemanticGraph graph = typed(
                "let value :Fn<;I32> = (=> | | 7) "
                        + "let invoke :Fn<Fn<;I32>;I32> = "
                        + "(=> |f :Fn<;I32>| ::f[])");
        CallableSummarySet summaries = summaries(graph);
        CallableSummary value = summaryFor(graph, summaries, "value");
        CallableSummary invoke = summaryFor(graph, summaries, "invoke");
        CallableCallReference call = invoke.calls().getFirst();

        assertEquals(CallableCallReference.Kind.DIRECT, call.kind());
        assertEquals(List.of(0), call.targetParameterIndexes());
        assertInstanceOf(ValueFormula.Parameter.class, call.target().only());
        SummaryTransferResult.Success applied = assertInstanceOf(
                SummaryTransferResult.Success.class,
                summaries.invoke(invoke.lambdaId(), List.of(
                        FormulaAlternatives.singleton(new ValueFormula.Lambda(
                                value.lambdaId(), value.signature().asFunctionType()))),
                        invoke.span()));
        assertTrue(applied.returnValue().formulas().stream()
                .anyMatch(ValueFormula.Scalar.class::isInstance));
    }

    @Test
    public void directCallableParameterSubstitutionRetainsDirectCallEffects() {
        String stem = "summary_direct_parameter_effect";
        ModuleId main = ModuleId.path(stem + "_main.lyra");
        ModuleId library = ModuleId.path(stem + "_lib.lyra");
        LogicalModuleId logical = LogicalModuleId.parse(stem + "_lib");
        ModuleGraph graph = graph(
                List.of(
                        module(main, "import " + stem + "_lib->{effectful} "
                                + "let applyDirect :Fn<Fn<;I32>;I32> = "
                                + "(=> |f :Fn<;I32>| ::f[])", main),
                        module(library,
                                "let @pub effectful :Fn<;I32> = (=> | | 7)",
                                library)),
                main,
                List.of(new ModuleGraph.Edge(main, logical, library,
                        SourceSpan.of(main.sourceId(), 0, 1))),
                Map.of(logical, library));
        TypedSemanticGraph typed = typed(graph);
        CallableSummarySet summaries = summaries(typed);
        CallableSummary applyDirect = summaryFor(
                typed, summaries, "applyDirect");
        CallableSummary effectful = summaryFor(typed, summaries, "effectful");
        SummaryTransferResult.Success applied = assertInstanceOf(
                SummaryTransferResult.Success.class,
                summaries.invoke(applyDirect.lambdaId(), List.of(
                        FormulaAlternatives.singleton(new ValueFormula.Lambda(
                                effectful.lambdaId(),
                                effectful.signature().asFunctionType()))),
                        applyDirect.span()));

        assertTrue(applied.effects().stream().anyMatch(effect ->
                effect.kind() == EagerEffectWitness.Kind.DIRECT_CALL
                        && effect.targetModule().equals(library)
                        && effect.targetLambda()
                        .filter(effectful.lambdaId()::equals).isPresent()));
    }

    @Test
    public void directCallableCaptureTargetRemainsCallerParameterized() {
        TypedSemanticGraph graph = typed(
                "let value :Fn<;I32> = (=> | | 7) "
                        + "let makeInvoker :Fn<Fn<;I32>;Fn<;I32>> = "
                        + "(=> |f :Fn<;I32>| (=> | | ::f[]))");
        CallableSummarySet summaries = summaries(graph);
        CallableSummary value = summaryFor(graph, summaries, "value");
        CallableSummary makeInvoker = summaryFor(graph, summaries, "makeInvoker");
        CallableSummary inner = summaries.orderedSummaries().stream()
                .filter(summary -> summary.calls().stream().anyMatch(call ->
                        call.kind() == CallableCallReference.Kind.DIRECT
                                && !call.targetCaptureIds().isEmpty()))
                .findFirst().orElseThrow();

        assertInstanceOf(ValueFormula.Capture.class, inner.calls().getFirst().target().only());
        SummaryTransferResult.Success created = assertInstanceOf(
                SummaryTransferResult.Success.class,
                summaries.invoke(makeInvoker.lambdaId(), List.of(
                        FormulaAlternatives.singleton(new ValueFormula.Lambda(
                                value.lambdaId(), value.signature().asFunctionType()))),
                        makeInvoker.span()));
        SummaryTransferResult.Success applied = assertInstanceOf(
                SummaryTransferResult.Success.class,
                summaries.invoke(created.returnValue(), List.of(), inner.span()));
        assertTrue(applied.returnValue().formulas().stream()
                .anyMatch(ValueFormula.Scalar.class::isInstance));
    }

    @Test
    public void directCallableResultTargetRemainsCallerParameterized() {
        TypedSemanticGraph graph = typed(
                "let make :Fn<;Fn<;I32>> = (=> | | (=> | | 7)) "
                        + "let invokeFactory :Fn<Fn<;Fn<;I32>>;I32> = "
                        + "(=> |factory :Fn<;Fn<;I32>>| { "
                        + "let produced :Fn<;I32> = (factory) "
                        + "let invoked :I32 = ::produced[] invoked })");
        CallableSummarySet summaries = summaries(graph);
        CallableSummary make = summaryFor(graph, summaries, "make");
        CallableSummary invokeFactory = summaryFor(graph, summaries, "invokeFactory");
        CallableCallReference direct = invokeFactory.calls().stream()
                .filter(call -> call.kind() == CallableCallReference.Kind.DIRECT)
                .findFirst().orElseThrow();

        assertTrue(direct.target().formulas().stream()
                .anyMatch(ValueFormula.CallResult.class::isInstance));
        SummaryTransferResult.Success applied = assertInstanceOf(
                SummaryTransferResult.Success.class,
                summaries.invoke(invokeFactory.lambdaId(), List.of(
                        FormulaAlternatives.singleton(new ValueFormula.Lambda(
                                make.lambdaId(), make.signature().asFunctionType()))),
                        invokeFactory.span()));
        assertTrue(applied.returnValue().formulas().stream()
                .anyMatch(ValueFormula.Scalar.class::isInstance));
    }

    @Test
    public void higherOrderTransferSubstitutesADirectCallableParameterCall() {
        TypedSemanticGraph graph = typed(
                "let value :Fn<I32;I32> = (=> |input :I32| input) "
                        + "let applyDirect :Fn<Fn<I32;I32>,I32;I32> = "
                        + "(=> |f :Fn<I32;I32> input :I32| ::f[input]) "
                        + "let caller :Fn<I32;I32> = "
                        + "(=> |input :I32| (applyDirect value input))");
        CallableSummarySet summaries = summaries(graph);
        CallableSummary caller = summaryFor(graph, summaries, "caller");

        assertTrue(summaryFor(graph, summaries, "applyDirect").calls().stream()
                .anyMatch(call -> call.kind() == CallableCallReference.Kind.DIRECT
                        && call.targetParameterIndexes().equals(List.of(0))));
        assertTrue(caller.returnFormula().formulas().stream()
                .anyMatch(formula -> formula instanceof ValueFormula.Parameter parameter
                        && parameter.parameterIndex() == 0));
        FormulaAlternatives actual = FormulaAlternatives.singleton(
                ValueFormula.Scalar.literal(PrimitiveType.I32, "higher-order-value"));
        SummaryTransferResult.Success applied = assertInstanceOf(
                SummaryTransferResult.Success.class,
                summaries.invoke(caller.lambdaId(), List.of(actual), caller.span()));
        assertEquals(actual, applied.returnValue());
    }

    @Test
    public void unresolvedDirectDeclarationTargetStillFailsSummarySolving() {
        TypedSemanticGraph graph = typed(
                "let invoke :Fn<Fn<;I32>;I32> = "
                        + "(=> |f :Fn<;I32>| ::f[])");
        CallableSummary source = summaryFor(graph, summaries(graph), "invoke");
        CallableCallReference original = source.calls().getFirst();
        DeclarationId missingDeclaration = new DeclarationId(90_100);
        FunctionType function = (FunctionType) original.target()
                .rootType().withoutQualifiers();
        CallableCallReference missingCall = new CallableCallReference(
                original.id(), CallableCallReference.Kind.DIRECT, original.span(),
                original.referenceId(), Optional.of(missingDeclaration),
                original.targetModule(), original.targetExport(), Optional.empty(),
                List.of(), List.of(),
                FormulaAlternatives.singleton(new ValueFormula.Declaration(
                        missingDeclaration, Optional.of(source.moduleId()),
                        ProjectionPath.root(), ProjectionPath.root(), function)),
                original.arguments(), original.siteId());
        CallableSummary forged = new CallableSummary(
                source.lambdaId(), source.moduleId(), source.span(), source.scopeId(),
                source.signature(), source.parameters(), source.captures(),
                source.returnFormula(), source.writes(), List.of(missingCall),
                source.eagerEffects(), source.normalizedBody());

        CallableSummaryResult.Failure failure = assertInstanceOf(
                CallableSummaryResult.Failure.class,
                CallableSummarySolver.solve(List.of(forged), Map.of()));
        assertEquals(CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                failure.failure().kind());
        assertEquals(original.span(), failure.failure().span().orElseThrow());
    }

    @Test
    public void applyingAParameterizedSummarySubstitutesCallerFacts() {
        TypedSemanticGraph graph = typed(
                "let identity :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| input)");
        CallableSummarySet summaries = summaries(graph);
        CallableSummary identity = summaryFor(graph, summaries, "identity");
        FunctionType function = identity.signature().asFunctionType();
        FormulaAlternatives target = FormulaAlternatives.singleton(
                new ValueFormula.Lambda(identity.lambdaId(), function));
        FormulaAlternatives argument = FormulaAlternatives.singleton(
                new ValueFormula.FreshAllocation(
                        new FreshAllocationSite(identity.lambdaId(), identity.span(), 100),
                        ArrayType.of(PrimitiveType.I32)));
        SummaryTransferResult applied = summaries.invoke(
                target, List.of(argument), identity.span());
        SummaryTransferResult.Success success = assertInstanceOf(
                SummaryTransferResult.Success.class, applied);
        assertEquals(argument, success.returnValue());
    }

    @Test
    public void exactFormulaOverrideMasksAncestorsButBranchJoinRestoresMayAlternatives() {
        FunctionType function = FunctionType.of(List.of(), PrimitiveType.I32);
        ArrayType functions = ArrayType.of(function);
        FormulaAlternatives original = FormulaAlternatives.singleton(
                new ValueFormula.Parameter(
                        new DeclarationId(10_100), 0, ProjectionPath.root(),
                        ProjectionPath.root(), functions));
        FormulaAlternatives replacement = FormulaAlternatives.singleton(
                new ValueFormula.Lambda(new LambdaId(10_101), function));

        FormulaAlternatives exact = original.replaceExact(
                ProjectionPath.arrayElement(0), replacement);
        FormulaAlternatives selected = exact.select(ProjectionPath.arrayElement(0));
        assertEquals(1, selected.size());
        assertInstanceOf(ValueFormula.Lambda.class, selected.only());

        FormulaAlternatives branchJoined = original.join(exact)
                .select(ProjectionPath.arrayElement(0));
        assertTrue(branchJoined.formulas().stream()
                .anyMatch(ValueFormula.Parameter.class::isInstance));
        assertTrue(branchJoined.formulas().stream()
                .anyMatch(ValueFormula.Lambda.class::isInstance));

        ArrayType nestedFunctions = ArrayType.of(functions);
        ValueFormula.Parameter nestedOriginalFormula = new ValueFormula.Parameter(
                new DeclarationId(10_102), 0, ProjectionPath.root(),
                ProjectionPath.root(), nestedFunctions);
        FormulaAlternatives nestedOriginal = FormulaAlternatives.singleton(
                nestedOriginalFormula);
        FormulaAlternatives wholeElement = FormulaAlternatives.singleton(
                new ValueFormula.Parameter(
                        new DeclarationId(10_103), 1, ProjectionPath.root(),
                        ProjectionPath.root(), functions));
        FormulaAlternatives left = nestedOriginal.replaceExact(
                ProjectionPath.arrayElement(0), wholeElement);
        FormulaAlternatives right = nestedOriginal.replaceExact(
                ProjectionPath.arrayElement(0).compose(ProjectionPath.arrayElement(0)),
                replacement);
        FormulaAlternatives differentlyMaskedBranches = left.join(right).select(
                ProjectionPath.arrayElement(0).compose(ProjectionPath.arrayElement(0)));

        assertEquals(2, differentlyMaskedBranches.size());
        assertTrue(differentlyMaskedBranches.formulas().stream()
                .noneMatch(nestedOriginalFormula::equals));
        assertTrue(differentlyMaskedBranches.formulas().stream()
                .anyMatch(formula -> formula instanceof ValueFormula.Parameter parameter
                        && parameter.declarationId().equals(new DeclarationId(10_103))));
        assertTrue(differentlyMaskedBranches.formulas().stream()
                .anyMatch(ValueFormula.Lambda.class::isInstance));
    }

    @Test
    public void unknownCallableSelectionUnionsOnlyReachableCompatibleAlternatives() {
        TypedSemanticGraph graph = typed(
                "let identity :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| input) "
                        + "let fresh :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| Array[0])");
        CallableSummarySet summaries = summaries(graph);
        CallableSummary identity = summaryFor(graph, summaries, "identity");
        CallableSummary fresh = summaryFor(graph, summaries, "fresh");
        FunctionType function = identity.signature().asFunctionType();
        ArrayType functionsType = ArrayType.of(function);
        FormulaAlternatives reachable = FormulaAlternatives.of(
                functionsType,
                new ValueFormula.Lambda(identity.lambdaId(), function,
                        ProjectionPath.arrayElement(0), Map.of()),
                new ValueFormula.Lambda(fresh.lambdaId(), function,
                        ProjectionPath.arrayElement(1), Map.of()));
        FormulaAlternatives selected = reachable.select(ProjectionPath.unknownArrayElement());
        FormulaAlternatives argument = FormulaAlternatives.singleton(
                new ValueFormula.Opaque(ArrayType.of(PrimitiveType.I32), "caller array"));
        SummaryTransferResult result = summaries.invoke(
                selected, List.of(argument), identity.span());
        SummaryTransferResult.Success success = assertInstanceOf(
                SummaryTransferResult.Success.class, result);
        assertTrue(success.returnValue().size() >= 2,
                "each reachable alternative contributes its finite return formulas");

        FormulaAlternatives onlyIdentity = FormulaAlternatives.of(
                function, new ValueFormula.Lambda(identity.lambdaId(), function));
        SummaryTransferResult.Success precise = assertInstanceOf(
                SummaryTransferResult.Success.class,
                summaries.invoke(onlyIdentity, List.of(argument), identity.span()));
        assertEquals(1, precise.returnValue().size(),
                "callable selection must not scan unrelated signature-compatible lambdas");
    }

    @Test
    public void mixedKnownAndUnknownCallableTargetsFailExplicitly() {
        TypedSemanticGraph graph = typed(
                "let identity :Fn<I32;I32> = (=> |value :I32| value)");
        CallableSummarySet summaries = summaries(graph);
        CallableSummary identity = summaryFor(graph, summaries, "identity");
        FunctionType function = identity.signature().asFunctionType();
        FormulaAlternatives mixed = FormulaAlternatives.of(
                function,
                new ValueFormula.Lambda(identity.lambdaId(), function),
                new ValueFormula.Opaque(function, "compatible callable without identity"));

        SummaryTransferResult result = summaries.invoke(
                mixed,
                List.of(FormulaAlternatives.singleton(
                        new ValueFormula.Scalar(PrimitiveType.I32))),
                identity.span());
        SummaryTransferResult.Failure failure = assertInstanceOf(
                SummaryTransferResult.Failure.class, result);
        assertEquals(CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                failure.failure().kind());
    }

    @Test
    public void capturedWritesAreOrderedAndPreserveExactRoutes() {
        TypedSemanticGraph graph = typed(
                "let outer :Fn<@mut Array<Array<I32>>;Fn<;Unit>> = "
                        + "(=> |cell| (=> | | { "
                        + "let first :Array<I32> = Array[0] "
                        + "let a = (cell[0] := first) "
                        + "let second :Array<I32> = Array[1] "
                        + "let b = (cell[0] := second) }))");
        CallableSummarySet summaries = summaries(graph);
        CallableSummary inner = graph.lambdas().stream()
                .filter(lambda -> lambda.signature().arity() == 0)
                .map(lambda -> summaries.summary(lambda.id()).orElseThrow())
                .findFirst().orElseThrow();
        assertEquals(2, inner.capturedCellWrites().size());
        assertTrue(inner.capturedCellWrites().get(0).sequence()
                < inner.capturedCellWrites().get(1).sequence());
        assertEquals(io.mindspice.lyra.compiler.semantic.flow.ProjectionPath.arrayElement(0),
                inner.capturedCellWrites().getFirst().route());
        assertTrue(inner.capturedCellWrites().stream().allMatch(
                write -> write.kind() == io.mindspice.lyra.compiler.semantic.flow.CapturedCellWrite.Kind.EXACT_ROUTE));
    }

    @Test
    public void transferredWritesAreRebasedIntoCallerEvaluationOrder() {
        TypedSemanticGraph graph = typed(
                "let writeTwo :Fn<@mut Array<I32>;Unit> = "
                        + "(=> |items| (items[0] := 2)) "
                        + "let writeOne :Fn<@mut Array<I32>;Unit> = "
                        + "(=> |items| (items[0] := 1)) "
                        + "let caller :Fn<@mut Array<I32>;Unit> = "
                        + "(=> |items| { "
                        + "let first = (writeTwo items) "
                        + "let middle = (items[0] := 3) "
                        + "(writeOne items) })");
        CallableSummarySet summaries = summaries(graph);
        CallableSummary writeTwo = summaryFor(graph, summaries, "writeTwo");
        CallableSummary writeOne = summaryFor(graph, summaries, "writeOne");
        CallableSummary caller = summaryFor(graph, summaries, "caller");

        assertEquals(3, caller.parameterWrites().size());
        SourceSpan callerWrite = caller.parameterWrites().stream()
                .map(io.mindspice.lyra.compiler.semantic.flow.CapturedCellWrite::span)
                .filter(span -> caller.span().contains(span.startOffset()))
                .findFirst().orElseThrow();
        assertEquals(List.of(
                        writeTwo.parameterWrites().getFirst().span(),
                        callerWrite,
                        writeOne.parameterWrites().getFirst().span()),
                caller.parameterWrites().stream()
                        .map(io.mindspice.lyra.compiler.semantic.flow.CapturedCellWrite::span)
                        .toList());
        assertTrue(caller.parameterWrites().get(0).sequence()
                < caller.parameterWrites().get(1).sequence());
        assertTrue(caller.parameterWrites().get(1).sequence()
                < caller.parameterWrites().get(2).sequence());

        FormulaAlternatives callerArgument = FormulaAlternatives.singleton(
                new ValueFormula.Parameter(
                        caller.parameters().getFirst().declarationId(), 0,
                        ProjectionPath.root(), ProjectionPath.root(),
                        ArrayType.of(PrimitiveType.I32)));
        SummaryTransferResult.Success applied = assertInstanceOf(
                SummaryTransferResult.Success.class,
                summaries.invoke(caller.lambdaId(), List.of(callerArgument), caller.span()));
        assertEquals(List.of(
                        writeTwo.parameterWrites().getFirst().span(),
                        callerWrite,
                        writeOne.parameterWrites().getFirst().span()),
                applied.writes().stream()
                        .map(io.mindspice.lyra.compiler.semantic.flow.CapturedCellWrite::span)
                        .toList());
        assertTrue(applied.writes().get(0).sequence()
                < applied.writes().get(1).sequence());
        assertTrue(applied.writes().get(1).sequence()
                < applied.writes().get(2).sequence());
    }

    @Test
    public void matchSummariesJoinLazyArmValuesAndCapturedWrites() {
        TypedSemanticGraph graph = typed(
                "let @mut trace :I32 = 0 "
                        + "let choose :Fn<I32;I32> = (=> |value| "
                        + "(match { trace := 1 value } "
                        + "?? { trace := 2 0I32 } -> { trace := 3 10I32 } "
                        + "?? _ -> { trace := 4 20I32 }))");
        CallableSummary summary = summaryFor(graph, summaries(graph), "choose");
        assertTrue(summary.isFixedPoint());
        assertTrue(summary.returnFormula().formulas().size() >= 2,
                "match result arms must remain joined alternatives in the callable summary");
        assertFalse(summary.capturedCellWrites().isEmpty(),
                "match subject, patterns, and selected results must retain captured writes");
        assertTrue(summary.capturedCellWrites().stream().allMatch(write ->
                        write.route().equals(ProjectionPath.root())),
                "match branch-write joins must retain exact root routes");
    }

    @Test
    public void recursiveCapturedCellWritesWidenByOperationWithoutGrowingTheDomain() {
        TypedSemanticGraph graph = typed(
                "let @mut count :I32 = 0 "
                        + "let loop :Fn<;I32> = (=> || { count := 7 (loop) })");
        SummaryLimits oneWrite = SummaryLimits.of(
                8, 8, 1, 8, 8, 8, 32);

        CallableSummaryResult result = CallableSummaryCompiler.compile(graph, oneWrite);
        CallableSummarySet solved = assertInstanceOf(
                CallableSummaryResult.Success.class, result, () -> render(result)).value();
        CallableSummary loop = summaryFor(graph, solved, "loop");
        CapturedCellWrite write = assertInstanceOf(
                CapturedCellWrite.class, loop.capturedCellWrites().getFirst());
        CallableSummary.CapturePlaceholder capture = loop.captures().stream()
                .filter(CallableSummary.CapturePlaceholder::isSharedCell)
                .findFirst().orElseThrow();

        assertTrue(loop.isFixedPoint());
        assertEquals(1, loop.capturedCellWrites().size());
        assertEquals(capture.captureId(), write.capture());
        assertEquals(capture.sharedCellId(), write.sharedCellId());
        assertTrue(write.value().formulas().stream()
                .anyMatch(ValueFormula.Scalar.class::isInstance));
    }

    @Test
    public void recursiveCallableWritesKeepDistinctSourceOperationsAndHonorTheWriteLimit() {
        String source = "let @mut count :I32 = 0 "
                + "let loop :Fn<;I32> = (=> || { "
                + "count := 1 (loop) count := 2 count })";
        TypedSemanticGraph graph = typed(source);
        CallableSummary loop = summaryFor(graph, summaries(graph), "loop");

        assertEquals(2, loop.capturedCellWrites().size());
        assertTrue(loop.capturedCellWrites().get(0).span().startOffset()
                < loop.capturedCellWrites().get(1).span().startOffset());
        assertTrue(loop.capturedCellWrites().get(0).sequence()
                < loop.capturedCellWrites().get(1).sequence());
        assertTrue(loop.capturedCellWrites().stream()
                .allMatch(write -> write.sharedCellId().isPresent()));

        SummaryLimits oneWrite = SummaryLimits.of(
                8, 8, 1, 8, 8, 8, 32);
        CallableSummaryResult result = CallableSummaryCompiler.compile(
                graph, oneWrite);
        CallableSummaryResult.Failure failure = assertInstanceOf(
                CallableSummaryResult.Failure.class, result);
        assertEquals(CallableSummaryResult.InternalFailure.Kind.DOMAIN_LIMIT,
                failure.failure().kind());
    }

    @Test
    public void recursiveOwnershipRequirementsKeepDistinctSourceSitesAndHonorTheWriteLimit() {
        String source = "let accept :Fn<@mut Array<I32>;Unit> = "
                + "(=> |@mut values :Array<I32>| ()) "
                + "let recurse :Fn<@mut Array<I32>;Unit> = "
                + "(=> |@mut values :Array<I32>| { "
                + "let first = (accept values) "
                + "let second = (recurse values) "
                + "let third = (accept values) })";
        TypedSemanticGraph graph = typed(source);
        CallableSummary recurse = summaryFor(graph, summaries(graph), "recurse");

        assertEquals(3, recurse.ownershipRequirements().size());
        assertTrue(recurse.ownershipRequirements().get(0).span().startOffset()
                < recurse.ownershipRequirements().get(1).span().startOffset());
        assertTrue(recurse.ownershipRequirements().get(1).span().startOffset()
                < recurse.ownershipRequirements().get(2).span().startOffset());
        assertTrue(recurse.ownershipRequirements().get(0).sequence()
                < recurse.ownershipRequirements().get(1).sequence());
        assertTrue(recurse.ownershipRequirements().get(1).sequence()
                < recurse.ownershipRequirements().get(2).sequence());

        SummaryLimits twoRequirements = SummaryLimits.of(
                8, 8, 2, 8, 8, 8, 32);
        CallableSummaryResult result = CallableSummaryCompiler.compile(
                graph, twoRequirements);
        CallableSummaryResult.Failure failure = assertInstanceOf(
                CallableSummaryResult.Failure.class, result);
        assertEquals(CallableSummaryResult.InternalFailure.Kind.DOMAIN_LIMIT,
                failure.failure().kind());
    }

    @Test
    public void sessionCompilerAcceptsARecursiveCapturedCellSummary() {
        SessionCompileResult result = LyraCompiler.compileSession(
                new SessionCompileRequest(
                        "recursive-cell.lyra",
                        "let @mut count :I32 = 0 "
                                + "let @pub loop :Fn<;I32> = (=> || { count := 7 (loop) }) "
                                + "let @pub read :Fn<;I32> = (=> || count)",
                        SessionSnapshot.empty()));
        SessionCompileResult.Success success = assertInstanceOf(
                SessionCompileResult.Success.class, result,
                () -> result.diagnostics().toString());
        CallableSummary loop = summaryFor(
                success.typedGraph(), success.flowCertificate().callableSummaries(), "loop");
        assertTrue(loop.isFixedPoint());
        assertEquals(1, loop.capturedCellWrites().size());
    }

    @Test
    public void returnedClosuresRetainSubstitutedCapturePlaceholders() {
        TypedSemanticGraph graph = typed(
                "let outer :Fn<Array<I32>;Fn<;Array<I32>>> = "
                        + "(=> |input :Array<I32>| (=> | | input))");
        CallableSummarySet summaries = summaries(graph);
        CallableSummary outer = summaryFor(graph, summaries, "outer");
        ValueFormula.Lambda closure = assertInstanceOf(
                ValueFormula.Lambda.class, outer.returnFormula().formulas().stream()
                        .filter(formula -> formula instanceof ValueFormula.Lambda)
                        .findFirst().orElseThrow());
        FormulaAlternatives actual = FormulaAlternatives.singleton(
                new ValueFormula.Opaque(ArrayType.of(PrimitiveType.I32), "actual"));
        SummaryTransferResult.Success applied = assertInstanceOf(
                SummaryTransferResult.Success.class,
                summaries.invoke(FormulaAlternatives.singleton(new ValueFormula.Lambda(
                                outer.lambdaId(), outer.signature().asFunctionType())),
                        List.of(actual), outer.span()));
        ValueFormula.Lambda substituted = assertInstanceOf(
                ValueFormula.Lambda.class, applied.returnValue().formulas().stream()
                        .filter(formula -> formula instanceof ValueFormula.Lambda)
                        .findFirst().orElseThrow());
        assertEquals(actual, substituted.captures().get(closure.captures().keySet().stream()
                .findFirst().orElseThrow()));
    }

    @Test
    public void capturedCallableInvocationRemainsParameterized() {
        TypedSemanticGraph graph = typed(
                "let outer :Fn<Fn<;I32>;Fn<;I32>> = "
                        + "(=> |f :Fn<;I32>| (=> | | (f)))");
        CallableSummarySet summaries = summaries(graph);
        CallableSummary inner = graph.lambdas().stream()
                .filter(lambda -> lambda.signature().arity() == 0)
                .map(lambda -> summaries.summary(lambda.id()).orElseThrow())
                .findFirst().orElseThrow();
        assertTrue(inner.calls().stream()
                .anyMatch(call -> call.kind() == CallableCallReference.Kind.CAPTURE));
    }

    @Test
    public void ownershipRequirementsShareCanonicalSummarySubstitution() {
        TypedSemanticGraph graph = typed(
                "let accept :Fn<@mut Array<I32>;Unit> = "
                        + "(=> |@mut values :Array<I32>| ()) "
                        + "let mutate :Fn<@mut Array<I32>;Unit> = "
                        + "(=> |@mut values :Array<I32>| (values[0] := 1)) "
                        + "let apply :Fn<@mut Array<I32>;Unit> = "
                        + "(=> |@mut values :Array<I32>| (accept values))");
        CallableSummarySet summaries = summaries(graph);
        CallableSummary mutate = summaryFor(graph, summaries, "mutate");
        CallableSummary apply = summaryFor(graph, summaries, "apply");

        assertTrue(mutate.ownershipRequirements().stream().anyMatch(requirement ->
                requirement.kind() == OwnershipRequirement.Kind.AGGREGATE_MUTATION));
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> mutate.ownershipRequirements().clear());
        OwnershipRequirement mutableArgument = apply.ownershipRequirements().stream()
                .filter(requirement -> requirement.kind()
                        == OwnershipRequirement.Kind.MUTABLE_ARGUMENT)
                .findFirst().orElseThrow();
        assertTrue(mutableArgument.value().formulas().stream()
                .anyMatch(ValueFormula.Parameter.class::isInstance));

        FormulaAlternatives actual = FormulaAlternatives.singleton(
                new ValueFormula.Declaration(
                        new DeclarationId(80_000), Optional.of(apply.moduleId()),
                        ProjectionPath.root(), ProjectionPath.root(),
                        ArrayType.of(PrimitiveType.I32)));
        SummaryTransferResult.Success transferred = assertInstanceOf(
                SummaryTransferResult.Success.class,
                summaries.invoke(apply.lambdaId(), List.of(actual), apply.span()));
        assertTrue(transferred.ownershipRequirements().stream()
                .flatMap(requirement -> requirement.value().formulas().stream())
                .anyMatch(actual.only()::equals),
                transferred.ownershipRequirements().toString());
    }

    @Test
    public void recursiveSummariesRetainFiniteOwnershipRequirements() {
        TypedSemanticGraph graph = typed(
                "let accept :Fn<@mut Array<I32>;Unit> = "
                        + "(=> |@mut values :Array<I32>| ()) "
                        + "let recurse :Fn<@mut Array<I32>;Unit> = "
                        + "(=> |@mut values :Array<I32>| { "
                        + "let accepted = (accept values) (recurse values) })");
        CallableSummary summary = summaryFor(graph, summaries(graph), "recurse");

        assertTrue(summary.isFixedPoint());
        assertTrue(summary.ownershipRequirements().stream().anyMatch(requirement ->
                requirement.kind() == OwnershipRequirement.Kind.MUTABLE_ARGUMENT));
        assertTrue(summary.ownershipRequirements().size()
                        <= SummaryLimits.DEFAULT.maxWrites(),
                "recursive ownership obligations remain inside the finite summary domain");
    }

    @Test
    public void mutableParameterWritesRemainParameterized() {
        TypedSemanticGraph graph = typed(
                "let update :Fn<@mut Array<Array<I32>>;Unit> = "
                        + "(=> |items| (items[0] := Array[0]))");
        CallableSummary summary = summaryFor(graph, summaries(graph), "update");
        assertEquals(1, summary.parameterWrites().size());
        assertEquals(0, summary.parameterWrites().getFirst().parameter());
        assertEquals(ProjectionPath.arrayElement(0),
                summary.parameterWrites().getFirst().route());
    }

    @Test
    public void concreteAggregateArgumentsConsumeNonEscapingMutableParameterWrites() {
        TypedSemanticGraph graph = typed(
                "let update :Fn<@mut Array<I32>;Unit> = "
                        + "(=> |items| (items[0] := 1))");
        CallableSummarySet summaries = summaries(graph);
        CallableSummary update = summaryFor(graph, summaries, "update");
        ArrayType array = ArrayType.of(PrimitiveType.I32);
        FormulaAlternatives fresh = FormulaAlternatives.singleton(
                new ValueFormula.FreshAllocation(
                        new FreshAllocationSite(update.lambdaId(), update.span(), 900), array));
        FormulaAlternatives declaration = FormulaAlternatives.singleton(
                new ValueFormula.Declaration(
                        new DeclarationId(90_000), Optional.of(update.moduleId()),
                        ProjectionPath.root(), ProjectionPath.root(), array));

        SummaryTransferResult.Success freshResult = assertInstanceOf(
                SummaryTransferResult.Success.class,
                summaries.invoke(update.lambdaId(), List.of(fresh), update.span()));
        SummaryTransferResult.Success declarationResult = assertInstanceOf(
                SummaryTransferResult.Success.class,
                summaries.invoke(update.lambdaId(), List.of(declaration), update.span()));
        assertTrue(freshResult.writes().isEmpty(),
                "a write into a caller-local fresh aggregate does not escape the summary");
        assertTrue(declarationResult.writes().isEmpty(),
                "a concrete declaration argument has no outer parameter/cell write target");
    }

    @Test
    public void directNamespaceAndCallableReferencesRemainDistinct() {
        TypedSemanticGraph directGraph = typed(
                "let id :Fn<I32;I32> = (=> |value :I32| value) "
                        + "let callable :Fn<I32;I32> = (=> |value :I32| (id value)) "
                        + "let direct :Fn<I32;I32> = (=> |value :I32| ::id[value])");
        CallableSummarySet direct = summaries(directGraph);
        assertTrue(summaryFor(directGraph, direct, "callable").calls().stream()
                .anyMatch(call -> call.kind() == CallableCallReference.Kind.CALLABLE));
        assertTrue(summaryFor(directGraph, direct, "direct").calls().stream()
                .anyMatch(call -> call.kind() == CallableCallReference.Kind.DIRECT));

        String stem = "summary_namespace";
        ModuleId main = ModuleId.path(stem + "_main.lyra");
        ModuleId library = ModuleId.path(stem + "_lib.lyra");
        LogicalModuleId logical = LogicalModuleId.parse(stem + "_lib");
        ModuleGraph graph = graph(
                List.of(
                        module(main, "import " + stem + "_lib "
                                + "let result :Fn<;I32> = "
                                + "(=> | | " + stem + "_lib->::value[])",
                                main),
                        module(library, "let @pub value :Fn<;I32> = (=> | | 1)", library)),
                main,
                List.of(new ModuleGraph.Edge(main, logical, library,
                        SourceSpan.of(main.sourceId(), 0, 1))),
                Map.of(logical, library));
        TypedSemanticGraph typed = typed(graph);
        CallableSummarySet namespace = summaries(typed);
        assertTrue(summaryFor(typed, namespace, "result").calls().stream()
                .anyMatch(call -> call.kind() == CallableCallReference.Kind.NAMESPACE));
    }

    @Test
    public void selectiveImportedFunctionAliasesResolveToOriginLambdas() {
        String stem = "summary_selective_callable";
        ModuleId main = ModuleId.path(stem + "_main.lyra");
        ModuleId library = ModuleId.path(stem + "_lib.lyra");
        LogicalModuleId logical = LogicalModuleId.parse(stem + "_lib");
        ModuleGraph graph = graph(
                List.of(
                        module(main, "import " + stem + "_lib->{origin as alias} "
                                + "let viaValue :Fn<I32;I32> = "
                                + "(=> |value :I32| (alias value)) "
                                + "let viaDirect :Fn<I32;I32> = "
                                + "(=> |value :I32| ::alias[value])", main),
                        module(library, "let offset :I32 = 1 "
                                + "let @pub origin :Fn<I32;I32> = "
                                + "(=> |value :I32| (+ value offset))", library)),
                main,
                List.of(new ModuleGraph.Edge(main, logical, library,
                        SourceSpan.of(main.sourceId(), 0, 1))),
                Map.of(logical, library));
        TypedSemanticGraph typed = typed(graph);
        CallableSummarySet summaries = summaries(typed);
        LambdaId origin = typed.declarations().stream()
                .filter(declaration -> declaration.moduleId().equals(library)
                        && declaration.name().equals("origin"))
                .flatMap(declaration -> declaration.initializerLambda().stream())
                .findFirst().orElseThrow();

        CallableCallReference valueCall = summaryFor(typed, summaries, "viaValue")
                .calls().getFirst();
        CallableCallReference directCall = summaryFor(typed, summaries, "viaDirect")
                .calls().getFirst();
        assertEquals(CallableCallReference.Kind.CALLABLE, valueCall.kind());
        assertEquals(CallableCallReference.Kind.DIRECT, directCall.kind());
        for (CallableCallReference call : List.of(valueCall, directCall)) {
            assertEquals(Optional.of(origin), call.targetLambda());
            assertEquals(Optional.of(library), call.targetModule());
            ValueFormula.Lambda target = assertInstanceOf(
                    ValueFormula.Lambda.class, call.target().only());
            assertEquals(origin, target.lambdaId());
            assertFalse(target.captures().isEmpty(),
                    "the imported alias must retain the origin closure environment");
            assertTrue(target.captures().values().stream()
                    .flatMap(value -> value.formulas().stream())
                    .anyMatch(value -> value instanceof ValueFormula.Declaration declaration
                            && declaration.moduleId().filter(library::equals).isPresent()));
        }
    }

    @Test
    public void computedImportedCallableDeclarationsStaySymbolicUntilCanonicalFlow() {
        String stem = "summary_computed_callable";
        ModuleId main = ModuleId.path(stem + "_main.lyra");
        ModuleId library = ModuleId.path(stem + "_lib.lyra");
        LogicalModuleId logical = LogicalModuleId.parse(stem + "_lib");
        ModuleGraph graph = graph(
                List.of(
                        module(main, "import " + stem + "_lib "
                                + "let returnValue :Fn<;Fn<;I32>> = "
                                + "(=> | | " + stem + "_lib->:.computed) "
                                + "let callableCall :Fn<;I32> = "
                                + "(=> | | (" + stem + "_lib->:.computed)) "
                                + "let namespaceCall :Fn<;I32> = "
                                + "(=> | | " + stem + "_lib->::computed[])", main),
                        module(library,
                                "let make :Fn<;Fn<;I32>> = "
                                        + "(=> | | (=> | | 7)) "
                                        + "let @pub computed :Fn<;I32> = (make)",
                                library)),
                main,
                List.of(new ModuleGraph.Edge(main, logical, library,
                        SourceSpan.of(main.sourceId(), 0, 1))),
                Map.of(logical, library));
        TypedSemanticGraph typed = typed(graph);
        CallableSummarySet summaries = summaries(typed);
        CallableSummary returned = summaryFor(typed, summaries, "returnValue");
        CallableSummary callable = summaryFor(typed, summaries, "callableCall");
        CallableSummary namespace = summaryFor(typed, summaries, "namespaceCall");

        assertInstanceOf(ValueFormula.Declaration.class,
                returned.returnFormula().formulas().stream()
                        .filter(formula -> formula.resultRoute().isRoot())
                        .findFirst().orElseThrow());
        assertEquals(CallableCallReference.Kind.CALLABLE,
                callable.calls().getFirst().kind());
        assertEquals(CallableCallReference.Kind.NAMESPACE,
                namespace.calls().getFirst().kind());
        assertTrue(List.of(callable, namespace).stream()
                .flatMap(summary -> summary.calls().stream())
                .allMatch(call -> call.target().formulas().stream()
                        .anyMatch(ValueFormula.Declaration.class::isInstance)));

        SummaryTransferResult.Failure missing = assertInstanceOf(
                SummaryTransferResult.Failure.class,
                summaries.invoke(namespace.lambdaId(), List.of(), namespace.span()));
        assertEquals(CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                missing.failure().kind(),
                "a computed declaration still needs the canonical caller value");
    }

    @Test
    public void recursiveCallableSccReachesAStableLeastPoint() {
        TypedSemanticGraph graph = typed(
                "let f :Fn<I32;I32> = (=> |value :I32| (g value)) "
                        + "let g :Fn<I32;I32> = (=> |value :I32| (f value))");
        CallableSummarySet summaries = summaries(graph);
        assertEquals(1, summaries.sccs().size());
        assertTrue(summaries.sccs().getFirst().recursive());
        assertTrue(summaries.orderedSummaries().stream()
                .allMatch(CallableSummary::isFixedPoint));
        assertTrue(summaries.orderedSummaries().stream()
                .allMatch(summary -> summary.calls().size() == 1));
        assertTrue(summaries.orderedSummaries().stream()
                .allMatch(summary -> summary.eagerEffects().isEmpty()),
                "function-only recursion is retained as calls, not as an eager module effect");
    }

    @Test
    public void mutableDirectFunctionRetainsItsRecursiveSummaryScc() {
        TypedSemanticGraph graph = typed(
                "let @mut recurse :Fn<;I32> = (=> | | ::recurse[]) "
                        + "let value :I32 = (recurse)");
        CallableSummarySet summaries = summaries(graph);
        CallableSummary recurse = summaryFor(graph, summaries, "recurse");
        CallableSummarySet.CallableScc component = summaries.sccs().stream()
                .filter(value -> value.members().contains(recurse.lambdaId()))
                .findFirst().orElseThrow();

        assertTrue(component.recursive());
        assertEquals(List.of(recurse.calls().getFirst().id()), component.links());
        assertTrue(graph.initializationPlan().cycles().isEmpty());
    }

    @Test
    public void escapedLocalMutableRecursiveFunctionKeepsItsCallableFact() {
        TypedSemanticGraph graph = typed(
                "let make :Fn<;Fn<;I32>> = (=> | | { "
                        + "let @mut recurse :Fn<;I32> = (=> | | ::recurse[]) "
                        + "recurse }) "
                        + "let escaped :Fn<;I32> = (make) "
                        + "let value :I32 = (escaped)");

        assertTrue(graph.initializationPlan().cycles().isEmpty());
        assertTrue(graph.semanticFlowFacts().valueAtDeclaration(
                graph.declarations().stream()
                        .filter(value -> value.name().equals("escaped"))
                        .findFirst().orElseThrow().id()).orElseThrow()
                .alternatives().stream()
                .flatMap(value -> value.callableFlows().stream())
                .anyMatch(callable -> callable.lambdaId().isPresent()));
    }

    @Test
    public void transitiveValueEffectsSurviveFunctionRecursion() {
        String stem = "summary_effect";
        ModuleId main = ModuleId.path(stem + "_main.lyra");
        ModuleId library = ModuleId.path(stem + "_lib.lyra");
        LogicalModuleId logical = LogicalModuleId.parse(stem + "_lib");
        ModuleGraph graph = graph(
                List.of(
                        module(main, "import " + stem + "_lib->{value} "
                                + "let read :Fn<;I32> = (=> | | value) "
                                + "let outer :Fn<;I32> = (=> | | (read))", main),
                        module(library, "let @pub value :I32 = 1", library)),
                main,
                List.of(new ModuleGraph.Edge(main, logical, library,
                        SourceSpan.of(main.sourceId(), 0, 1))),
                Map.of(logical, library));
        TypedSemanticGraph typed = typed(graph);
        CallableSummarySet summaries = summaries(typed);
        CallableSummary outer = summaryFor(typed, summaries, "outer");
        assertTrue(outer.eagerEffects().stream()
                .anyMatch(effect -> effect.targetModule().equals(library)
                        && effect.fromModule().equals(main)));
        assertTrue(outer.eagerEffects().stream()
                .allMatch(effect -> effect.sourcePath().size() <= SummaryLimits.DEFAULT.maxWitnessPathDepth()));
    }

    @Test
    public void recursiveSccRetainsTransitiveEffectsWithoutPathExplosion() {
        String stem = "summary_recursive_effect";
        ModuleId main = ModuleId.path(stem + "_main.lyra");
        ModuleId library = ModuleId.path(stem + "_lib.lyra");
        LogicalModuleId logical = LogicalModuleId.parse(stem + "_lib");
        ModuleGraph graph = graph(
                List.of(
                        module(main, "import " + stem + "_lib->{value} "
                                + "let f :Fn<;I32> = (=> | | (g)) "
                                + "let g :Fn<;I32> = (=> | | { let ignored = (f) value })", main),
                        module(library, "let @pub value :I32 = 1", library)),
                main,
                List.of(new ModuleGraph.Edge(main, logical, library,
                        SourceSpan.of(main.sourceId(), 0, 1))),
                Map.of(logical, library));
        TypedSemanticGraph typed = typed(graph);
        CallableSummarySet summaries = summaries(typed);
        assertEquals(1, summaries.sccs().size());
        assertTrue(summaries.sccs().getFirst().recursive());
        assertTrue(summaries.orderedSummaries().stream()
                .allMatch(summary -> summary.eagerEffects().stream()
                        .anyMatch(effect -> effect.targetModule().equals(library))));
        assertTrue(summaries.orderedSummaries().stream()
                .flatMap(summary -> summary.eagerEffects().stream())
                .allMatch(effect -> effect.sourcePath().size()
                        <= SummaryLimits.DEFAULT.maxWitnessPathDepth()));
    }

    @Test
    public void repeatedCompilationAndInputOrderHaveOneCanonicalEncoding() {
        TypedSemanticGraph graph = typed(
                "let first :Fn<I32;I32> = (=> |value :I32| value) "
                        + "let second :Fn<I32;I32> = (=> |value :I32| (first value))");
        CallableSummaryResult one = CallableSummaryCompiler.compile(graph);
        CallableSummaryResult two = CallableSummaryCompiler.compile(graph);
        assertTrue(one instanceof CallableSummaryResult.Success);
        assertEquals(one.optionalValue().orElseThrow().canonicalKey(),
                two.optionalValue().orElseThrow().canonicalKey());
        assertEquals(one.optionalValue().orElseThrow(), two.optionalValue().orElseThrow());
    }

    @Test
    public void canonicalSummaryIdentityIncludesNormalizedLinksAndScopes() {
        TypedSemanticGraph graph = typed(
                "let identity :Fn<I32;I32> = (=> |value :I32| value)");
        CallableSummary sourceSummary = summaryFor(graph, summaries(graph), "identity");
        NormalizedExpression sourceBody = sourceSummary.normalizedBody();
        DeclarationId declaration = new DeclarationId(50_000);
        TypedLink originalLink = TypedLink.value(
                new ReferenceId(50_000), declaration, Optional.empty(), Optional.empty());
        TypedLink distinctLink = TypedLink.value(
                new ReferenceId(50_001), declaration, Optional.empty(), Optional.empty());
        ScopeId originalScope = new ScopeId(50_000);

        NormalizedExpression body = withLinkAndScope(
                sourceBody, Optional.of(originalLink), Optional.of(originalScope));
        NormalizedExpression linkedBody = withLinkAndScope(
                sourceBody, Optional.of(distinctLink), Optional.of(originalScope));
        NormalizedExpression scopedBody = withLinkAndScope(
                sourceBody, Optional.of(originalLink), Optional.of(new ScopeId(50_001)));
        CallableSummary summary = withNormalizedBody(sourceSummary, body);
        CallableSummary linkedSummary = withNormalizedBody(sourceSummary, linkedBody);
        CallableSummary scopedSummary = withNormalizedBody(sourceSummary, scopedBody);

        assertFalse(body.canonicalKey().equals(linkedBody.canonicalKey()));
        assertFalse(body.canonicalKey().equals(scopedBody.canonicalKey()));
        assertFalse(summary.equals(linkedSummary));
        assertFalse(summary.equals(scopedSummary));
        assertEquals(3, new java.util.HashSet<>(List.of(
                summary, linkedSummary, scopedSummary)).size());
    }

    @Test
    public void conditionalReturnsRetainBothReachableBranchAlternatives() {
        TypedSemanticGraph graph = typed(
                "let choose :Fn<Bool;I32> = (=> |flag :Bool| (flag -> 1 : 2))");
        CallableSummary summary = summaryFor(graph, summaries(graph), "choose");
        assertEquals(2, summary.returnFormula().formulas().stream()
                .filter(formula -> formula instanceof ValueFormula.Scalar)
                .count());
    }

    @Test
    public void finiteDomainReportsOverflowInsteadOfDroppingAlternatives() {
        TypedSemanticGraph graph = typed(
                "let choose :Fn<Bool;I32> = (=> |flag :Bool| (flag -> 1 : 2))");
        SummaryLimits oneAlternative = SummaryLimits.of(1, 256, 256, 512, 64, 64, 512);
        CallableSummaryResult result = CallableSummaryCompiler.compile(graph, oneAlternative);
        assertTrue(result instanceof CallableSummaryResult.Failure);
        CallableSummaryResult.Failure failure = assertInstanceOf(
                CallableSummaryResult.Failure.class, result);
        assertEquals(CallableSummaryResult.InternalFailure.Kind.DOMAIN_LIMIT,
                failure.failure().kind());
        assertFalse(result.optionalValue().isPresent());
    }

    @Test
    public void solverPreservesDomainLimitFromKnownCallableTransfer() {
        TypedSemanticGraph graph = typed(
                "let choose :Fn<Bool,I32,I32;I32> = "
                        + "(=> |flag :Bool left :I32 right :I32| "
                        + "(flag -> left : right)) "
                        + "let caller :Fn<Bool,Bool;I32> = "
                        + "(=> |first :Bool second :Bool| "
                        + "(choose first (second -> 1 : 2) (second -> 3 : 4)))");
        SummaryLimits twoAlternatives = SummaryLimits.of(
                2, 256, 256, 512, 64, 64, 512);

        CallableSummaryResult result = CallableSummaryCompiler.compile(
                graph, twoAlternatives);
        CallableSummaryResult.Failure failure = assertInstanceOf(
                CallableSummaryResult.Failure.class, result);
        assertEquals(CallableSummaryResult.InternalFailure.Kind.DOMAIN_LIMIT,
                failure.failure().kind());
    }

    @Test
    public void nestedTransferPreservesTheOriginalInternalFailure() {
        TypedSemanticGraph graph = typed(
                "let callee :Fn<I32;I32> = (=> |value :I32| value) "
                        + "let outer :Fn<;I32> = (=> | | (callee 1))");
        CallableSummarySet solved = summaries(graph);
        CallableSummary callee = summaryFor(graph, solved, "callee");
        CallableSummary outer = summaryFor(graph, solved, "outer");
        SummaryCallId callId = outer.calls().getFirst().id();
        SourceSpan nestedCallSpan = outer.calls().getFirst().span();
        FunctionType forgedTargetType = FunctionType.of(
                List.of(PrimitiveType.BOOL), PrimitiveType.I32);
        CallableCallReference nestedCall = new CallableCallReference(
                callId,
                CallableCallReference.Kind.CALLABLE,
                nestedCallSpan,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(callee.lambdaId()),
                List.of(),
                List.of(),
                FormulaAlternatives.singleton(new ValueFormula.Lambda(
                        callee.lambdaId(), forgedTargetType)),
                List.of(FormulaAlternatives.singleton(
                        new ValueFormula.Scalar(PrimitiveType.BOOL))));
        CallableSummary inconsistentOuter = new CallableSummary(
                outer.lambdaId(), outer.moduleId(), outer.span(), outer.scopeId(),
                outer.signature(), outer.parameters(), outer.captures(),
                new CallableSummary.ReturnFormula(FormulaAlternatives.singleton(
                        new ValueFormula.CallResult(callId, PrimitiveType.I32))),
                List.of(), List.of(nestedCall), List.of(), outer.normalizedBody(),
                outer.isFixedPoint(), outer.fixedPointIterations(), outer.limits());
        CallableSummarySet inconsistent = new CallableSummarySet(
                List.of(callee, inconsistentOuter), solved.lambdaByDeclaration());

        SummaryTransferResult result = inconsistent.invoke(
                inconsistentOuter.lambdaId(), List.of(), inconsistentOuter.span());
        SummaryTransferResult.Failure failure = assertInstanceOf(
                SummaryTransferResult.Failure.class, result);
        assertEquals(CallableSummaryResult.InternalFailure.Kind.INCONSISTENT_SUMMARY,
                failure.failure().kind());
        assertEquals(nestedCallSpan, failure.failure().span().orElseThrow());
        assertTrue(failure.failure().message().startsWith("call argument 0 does not match"));
    }

    @Test
    public void nestedHigherOrderReturnsAndStaticWritesTransferToTheCaller() {
        TypedSemanticGraph graph = typed(
                "let identity :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| input) "
                        + "let apply :Fn<Fn<Array<I32>;Array<I32>>,Array<I32>;Array<I32>> = "
                        + "(=> |f :Fn<Array<I32>;Array<I32>> items :Array<I32>| (f items)) "
                        + "let mutate :Fn<@mut Array<Array<I32>>;Unit> = "
                        + "(=> |items| (items[0] := Array[1])) "
                        + "let wrapper :Fn<@mut Array<Array<I32>>;Array<I32>> = "
                        + "(=> |items| { let ignored = (mutate items) (apply identity items[0]) })");
        CallableSummary wrapper = summaryFor(graph, summaries(graph), "wrapper");

        assertTrue(wrapper.returnFormula().formulas().stream()
                .anyMatch(formula -> formula instanceof ValueFormula.Parameter parameter
                        && parameter.parameterIndex() == 0
                        && parameter.parameterRoute().equals(ProjectionPath.arrayElement(0))
                        && parameter.resultRoute().isRoot()),
                wrapper.returnFormula().formulas().toString());
        assertTrue(wrapper.parameterWrites().stream()
                .anyMatch(write -> write.parameter() == 0
                        && write.route().equals(ProjectionPath.arrayElement(0))),
                wrapper.parameterWrites().toString());
    }

    @Test
    public void nestedProjectionRebasingUsesOnlyTheSuffixBelowAnEmbeddedFormula() {
        TypedSemanticGraph graph = typed(
                "let select :Fn<Tuple<Array<Array<I32>>,I32>;Array<I32>> = "
                        + "(=> |input :Tuple<Array<Array<I32>>,I32>| input:.0[0]) "
                        + "let wrapper :Fn<Array<Array<I32>>;Array<I32>> = "
                        + "(=> |input :Array<Array<I32>>| (select Tuple[input 0]))");
        CallableSummarySet summaries = summaries(graph);
        CallableSummary wrapper = summaryFor(graph, summaries, "wrapper");
        ValueFormula.Parameter routed = assertInstanceOf(
                ValueFormula.Parameter.class,
                wrapper.returnFormula().formulas().stream()
                        .filter(formula -> formula instanceof ValueFormula.Parameter parameter
                                && parameter.parameterIndex() == 0
                                && parameter.resultRoute().isRoot())
                        .findFirst().orElseThrow());

        assertEquals(ProjectionPath.arrayElement(0), routed.parameterRoute(),
                "the tuple container route is already represented by the formula result route");

        ArrayType nested = ArrayType.of(ArrayType.of(PrimitiveType.I32));
        FormulaAlternatives callerValue = FormulaAlternatives.singleton(
                new ValueFormula.Opaque(nested, "caller nested array"));
        SummaryTransferResult transferred = summaries.invoke(
                wrapper.lambdaId(), List.of(callerValue), wrapper.span());
        SummaryTransferResult.Success success = assertInstanceOf(
                SummaryTransferResult.Success.class, transferred, transferred::toString);
        assertEquals(ArrayType.of(PrimitiveType.I32),
                success.returnValue().rootType().withoutQualifiers());
    }

    @Test
    public void mixedMissingSelectionCannotBeHiddenByAValidReturnAlternative() {
        TypedSemanticGraph graph = typed(
                "let choose :Fn<Bool,Array<Array<I32>>;Array<I32>> = "
                        + "(=> |flag :Bool items :Array<Array<I32>>| "
                        + "(flag -> items[0] : Array[7]))");
        CallableSummarySet summaries = summaries(graph);
        CallableSummary choose = summaryFor(graph, summaries, "choose");
        ArrayType nested = ArrayType.of(ArrayType.of(PrimitiveType.I32));
        FormulaAlternatives incomplete = FormulaAlternatives.of(
                nested,
                new ValueFormula.FreshAllocation(
                        new FreshAllocationSite(choose.lambdaId(), choose.span(), 700), nested),
                new ValueFormula.Opaque(ArrayType.of(PrimitiveType.I32),
                        ProjectionPath.arrayElement(1), "only sibling is known"));

        SummaryTransferResult result = summaries.invoke(
                choose.lambdaId(),
                List.of(FormulaAlternatives.singleton(new ValueFormula.Scalar(PrimitiveType.BOOL)),
                        incomplete),
                choose.span());
        SummaryTransferResult.Failure failure = assertInstanceOf(
                SummaryTransferResult.Failure.class, result);
        assertEquals(CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                failure.failure().kind());
    }

    @Test
    public void knownLambdaApplicationEnforcesTheFormulaDomainLimit() {
        TypedSemanticGraph graph = typed(
                "let identity :Fn<I32;I32> = (=> |value :I32| value)");
        CallableSummarySet summaries = summaries(graph);
        CallableSummary identity = summaryFor(graph, summaries, "identity");
        ArrayList<ValueFormula> alternatives = new ArrayList<>();
        for (int index = 0; index <= SummaryLimits.DEFAULT.maxFormulaAlternatives(); index++) {
            alternatives.add(ValueFormula.Scalar.literal(PrimitiveType.I32, "value-" + index));
        }

        SummaryTransferResult result = summaries.invoke(
                identity.lambdaId(),
                List.of(new FormulaAlternatives(PrimitiveType.I32, alternatives)),
                identity.span());
        SummaryTransferResult.Failure failure = assertInstanceOf(
                SummaryTransferResult.Failure.class, result);
        assertEquals(CallableSummaryResult.InternalFailure.Kind.DOMAIN_LIMIT,
                failure.failure().kind());
    }

    @Test
    public void dynamicTargetApplicationEnforcesTheFormulaDomainBeforeEnumeration() {
        FunctionType function = FunctionType.of(List.of(), PrimitiveType.I32);
        ArrayList<ValueFormula> alternatives = new ArrayList<>();
        for (int index = 0; index <= SummaryLimits.DEFAULT.maxFormulaAlternatives(); index++) {
            alternatives.add(new ValueFormula.Declaration(
                    new DeclarationId(10_000 + index), function));
        }
        SourceSpan span = SourceSpan.of(SourceId.path("bounded-target.lyra"), 0, 1);

        SummaryTransferResult result = CallableSummarySet.empty().invoke(
                new FormulaAlternatives(function, alternatives), List.of(), span);
        SummaryTransferResult.Failure failure = assertInstanceOf(
                SummaryTransferResult.Failure.class, result);
        assertEquals(CallableSummaryResult.InternalFailure.Kind.DOMAIN_LIMIT,
                failure.failure().kind());
    }

    @Test
    public void knownCallResultsAreSubstitutedInsideClosureCapturesAndWriteValues() {
        TypedSemanticGraph graph = typed(
                "let identity :Fn<Array<I32>;Array<I32>> = "
                        + "(=> |input :Array<I32>| input) "
                        + "let outer :Fn<Array<I32>;Fn<;Array<I32>>> = "
                        + "(=> |input :Array<I32>| { "
                        + "let selected :Array<I32> = (identity input) "
                        + "(=> | | selected) }) "
                        + "let update :Fn<@mut Array<Array<I32>>,Array<I32>;Unit> = "
                        + "(=> |items value :Array<I32>| "
                        + "(items[0] := (identity value)))");
        CallableSummarySet summaries = summaries(graph);
        CallableSummary outer = summaryFor(graph, summaries, "outer");
        ValueFormula.Lambda closure = assertInstanceOf(
                ValueFormula.Lambda.class, outer.returnFormula().formulas().stream()
                        .filter(ValueFormula.Lambda.class::isInstance)
                        .findFirst().orElseThrow());
        assertTrue(closure.captures().values().stream()
                .flatMap(value -> value.formulas().stream())
                .anyMatch(value -> value instanceof ValueFormula.Parameter parameter
                        && parameter.parameterIndex() == 0));
        assertFalse(closure.captures().values().stream()
                .flatMap(value -> value.formulas().stream())
                .anyMatch(ValueFormula.CallResult.class::isInstance));

        CallableSummary update = summaryFor(graph, summaries, "update");
        assertTrue(update.writes().stream()
                .flatMap(write -> write.value().formulas().stream())
                .anyMatch(value -> value instanceof ValueFormula.Parameter parameter
                        && parameter.parameterIndex() == 1));
        assertFalse(update.writes().stream()
                .flatMap(write -> write.value().formulas().stream())
                .anyMatch(ValueFormula.CallResult.class::isInstance));
    }

    @Test
    public void higherOrderStaticTransferPreservesTransitiveEagerEffects() {
        String stem = "summary_transferred_effect";
        ModuleId main = ModuleId.path(stem + "_main.lyra");
        ModuleId library = ModuleId.path(stem + "_lib.lyra");
        LogicalModuleId logical = LogicalModuleId.parse(stem + "_lib");
        ModuleGraph graph = graph(
                List.of(
                        module(main, "import " + stem + "_lib->{value} "
                                + "let read :Fn<;I32> = (=> | | value) "
                                + "let apply :Fn<Fn<;I32>;I32> = "
                                + "(=> |f :Fn<;I32>| (f)) "
                                + "let outer :Fn<;I32> = (=> | | (apply read))", main),
                        module(library, "let @pub value :I32 = 1", library)),
                main,
                List.of(new ModuleGraph.Edge(main, logical, library,
                        SourceSpan.of(main.sourceId(), 0, 1))),
                Map.of(logical, library));
        TypedSemanticGraph typed = typed(graph);
        CallableSummary outer = summaryFor(typed, summaries(typed), "outer");

        assertTrue(outer.eagerEffects().stream()
                .anyMatch(effect -> effect.targetModule().equals(library)
                        && effect.callPath().size() >= 2),
                outer.eagerEffects().toString());
    }

    @Test
    public void importedPureHigherOrderCallEmitsOneCandidateModuleWitness() {
        String stem = "summary_imported_higher_order_pure";
        ModuleId main = ModuleId.path(stem + "_main.lyra");
        ModuleId library = ModuleId.path(stem + "_lib.lyra");
        LogicalModuleId logical = LogicalModuleId.parse(stem + "_lib");
        ModuleGraph graph = graph(
                List.of(
                        module(main, "import " + stem + "_lib->{pure} "
                                + "let apply :Fn<Fn<;I32>;I32> = "
                                + "(=> |f :Fn<;I32>| (f)) "
                                + "let outer :Fn<;I32> = (=> | | (apply pure))", main),
                        module(library,
                                "let @pub pure :Fn<;I32> = (=> | | 1)", library)),
                main,
                List.of(new ModuleGraph.Edge(main, logical, library,
                        SourceSpan.of(main.sourceId(), 0, 1))),
                Map.of(logical, library));
        TypedSemanticGraph typed = typed(graph);
        CallableSummarySet summaries = summaries(typed);
        CallableSummary outer = summaryFor(typed, summaries, "outer");
        LambdaId pure = typed.declarations().stream()
                .filter(declaration -> declaration.moduleId().equals(library)
                        && declaration.name().equals("pure"))
                .flatMap(declaration -> declaration.initializerLambda().stream())
                .findFirst().orElseThrow();

        List<EagerEffectWitness> witnesses = outer.eagerEffects().stream()
                .filter(effect -> effect.kind() == EagerEffectWitness.Kind.PARAMETER_CALL
                        && effect.fromModule().equals(main)
                        && effect.targetModule().equals(library)
                        && effect.targetLambda().filter(pure::equals).isPresent())
                .toList();
        assertEquals(1, witnesses.size(), outer.eagerEffects().toString());
        assertTrue(witnesses.getFirst().sourcePath().size() >= 2,
                "the witness retains both the outer and higher-order call sites");
    }

    @Test
    public void returnedClosuresTransferFreshAndDeclarationBackedCellWrites() {
        TypedSemanticGraph freshGraph = typed(
                "let make :Fn<;Fn<;Unit>> = (=> | | { "
                        + "let @mut cell :Array<I32> = Array[0] "
                        + "(=> | | (cell[0] := 1)) })");
        CallableSummarySet freshSummaries = summaries(freshGraph);
        CallableSummary make = summaryFor(freshGraph, freshSummaries, "make");
        SummaryTransferResult.Success made = assertInstanceOf(
                SummaryTransferResult.Success.class,
                freshSummaries.invoke(make.lambdaId(), List.of(), make.span()));
        SummaryTransferResult.Success freshWrite = assertInstanceOf(
                SummaryTransferResult.Success.class,
                freshSummaries.invoke(made.returnValue(), List.of(), make.span()));
        assertTrue(freshWrite.writes().stream()
                .anyMatch(write -> write.isCaptureWrite()
                        && write.sharedCellId().isPresent()
                        && write.route().equals(ProjectionPath.arrayElement(0))),
                freshWrite.writes().toString());

        TypedSemanticGraph declarationGraph = typed(
                "let @mut cell :Array<I32> = Array[0] "
                        + "let write :Fn<;Unit> = (=> | | (cell[0] := 1)) "
                        + "let call :Fn<;Unit> = (=> | | (write))");
        CallableSummarySet declarationSummaries = summaries(declarationGraph);
        CallableSummary call = summaryFor(declarationGraph, declarationSummaries, "call");
        TypedDeclaration cell = declarationGraph.declarations().stream()
                .filter(value -> value.name().equals("cell"))
                .findFirst().orElseThrow();
        assertTrue(call.writes().stream()
                .anyMatch(write -> write.declarationId().equals(cell.id())
                        && write.sharedCellId().filter(cell.id()::equals).isPresent()
                        && write.route().equals(ProjectionPath.arrayElement(0))),
                call.writes().toString());
    }

    @Test
    public void missingWellTypedCallableFactIsAnExplicitInternalFailure() {
        FunctionType function = FunctionType.of(List.of(), PrimitiveType.I32);
        LambdaId missing = new LambdaId(9999);
        FormulaAlternatives target = FormulaAlternatives.singleton(
                new ValueFormula.Lambda(missing, function));
        SourceSpan span = SourceSpan.of(SourceId.path("missing-summary.lyra"), 4, 5);
        SummaryTransferResult result = CallableSummarySet.empty().invoke(target, List.of(), span);
        SummaryTransferResult.Failure failure = assertInstanceOf(
                SummaryTransferResult.Failure.class, result);
        assertEquals(CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                failure.failure().kind());
        assertEquals("LYC-IR-008", failure.failure().diagnostic().orElseThrow().code().value());
    }

    private static NormalizedExpression withLinkAndScope(
            NormalizedExpression expression,
            Optional<TypedLink> link,
            Optional<ScopeId> scopeId) {
        return new NormalizedExpression(
                expression.kind(), expression.span(), expression.type(), expression.children(),
                link, expression.declarationId(), expression.lambdaId(), scopeId,
                expression.projectionStep(), expression.callKind(), expression.captureIds(),
                expression.literal(), expression.conversion(), expression.operator(),
                expression.memberName(), expression.tupleIndex(), expression.signature(),
                expression.predicateBinding());
    }

    private static CallableSummary withNormalizedBody(
            CallableSummary summary,
            NormalizedExpression body) {
        return new CallableSummary(
                summary.lambdaId(), summary.moduleId(), summary.span(), summary.scopeId(),
                summary.signature(), summary.parameters(), summary.captures(),
                summary.returnFormula(), summary.writes(), summary.callReferences(),
                summary.eagerEffects(), body, summary.isFixedPoint(),
                summary.fixedPointIterations(), summary.limits());
    }

    private static CallableSummarySet summaries(TypedSemanticGraph graph) {
        CallableSummaryResult result = CallableSummaryCompiler.compile(graph);
        if (!(result instanceof CallableSummaryResult.Success success)) {
            throw new AssertionError("summary compilation failed: " + render(result));
        }
        return success.value();
    }

    private static CallableSummary summaryFor(
            TypedSemanticGraph graph,
            CallableSummarySet summaries,
            String name) {
        TypedDeclaration declaration = graph.declarations().stream()
                .filter(value -> value.name().equals(name))
                .filter(value -> value.kind() == io.mindspice.lyra.compiler.semantic.DeclarationKind.LET)
                .findFirst().orElseThrow();
        LambdaId lambda = declaration.initializerLambda().orElseThrow();
        return summaries.summary(lambda).orElseThrow();
    }

    private static TypedSemanticGraph typed(String source) {
        return typed(singleGraph(source));
    }

    private static TypedSemanticGraph typed(ModuleGraph graph) {
        PhaseResult<ResolvedSemanticGraph> resolved = SemanticResolver.resolve(graph);
        if (!(resolved instanceof PhaseResult.Success<?> success)) {
            throw new AssertionError("resolution failed: " + render(resolved));
        }
        PhaseResult<TypedSemanticGraph> typed = TypeChecker.check(
                ((PhaseResult.Success<ResolvedSemanticGraph>) success).value());
        if (!(typed instanceof PhaseResult.Success<?> successTyped)) {
            throw new AssertionError("type checking failed: " + render(typed));
        }
        return ((PhaseResult.Success<TypedSemanticGraph>) successTyped).value();
    }

    private static ModuleGraph singleGraph(String source) {
        ModuleId main = ModuleId.path("summary_main.lyra");
        return graph(List.of(module(main, source, main)), main, List.of(), Map.of());
    }

    private static ModuleGraph graph(
            List<ModuleGraph.Node> modules,
            ModuleId root,
            List<ModuleGraph.Edge> edges,
            Map<LogicalModuleId, ModuleId> logicalModules) {
        return CanonicalModuleGraph.create(root, modules, edges, logicalModules);
    }

    private static ModuleGraph.Node module(
            ModuleId id,
            String source,
            ModuleId ignored) {
        SourceSnapshot snapshot = snapshot(id.sourceId(), source);
        SyntaxProgram syntax = parse(snapshot);
        return new ModuleGraph.Node(
                id,
                id.isPath() ? Optional.of(LogicalModuleId.fromSourceId(id.sourceId())) : Optional.empty(),
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
                tokens, ((PhaseResult.Success<GrammarProgram>) grammarSuccess).value());
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

    private static String render(CallableSummaryResult result) {
        if (result instanceof CallableSummaryResult.Failure failure) {
            return failure.failure().kind() + ": " + failure.failure().message()
                    + " " + result.diagnostics().stream().map(Diagnostic::render).toList();
        }
        return result.diagnostics().stream().map(Diagnostic::render).toList().toString();
    }

    private static String render(PhaseResult<?> result) {
        return result.diagnostics().stream().map(Diagnostic::render).toList().toString();
    }
}
