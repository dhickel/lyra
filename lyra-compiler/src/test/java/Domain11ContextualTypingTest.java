import io.mindspice.lyra.compiler.ast.SyntaxProgram;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.Phase;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.grammar.GrammarMatcher;
import io.mindspice.lyra.compiler.grammar.GrammarProgram;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.parse.Parser;
import io.mindspice.lyra.compiler.semantic.ResolvedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.SemanticResolver;
import io.mindspice.lyra.compiler.semantic.TypedExpression;
import io.mindspice.lyra.compiler.semantic.TypedConversion;
import io.mindspice.lyra.compiler.semantic.TypedExpressionKind;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.SemanticTestSupport;
import io.mindspice.lyra.compiler.semantic.TypedDeclaration;
import io.mindspice.lyra.compiler.semantic.TypedModule;
import io.mindspice.lyra.compiler.semantic.TypeChecker;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.ModuleRevision;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.BindingContract;
import io.mindspice.lyra.compiler.types.ConversionKind;
import io.mindspice.lyra.compiler.types.ConversionStep;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.LyraType;
import io.mindspice.lyra.compiler.types.PrimitiveType;
import io.mindspice.lyra.compiler.types.TupleType;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused Gate 11C coverage for structural contextual typing and provenance. */
public final class Domain11ContextualTypingTest {
    @Test
    public void declaredAndBlockContextsReachExactNestedNilPositions() {
        TypedSemanticGraph typed = success(
                "let direct :@nil I32 = #NIL "
                        + "let block :@nil I32 = { let local :I32 = 1 #NIL } "
                        + "let nested :@nil Tuple<@nil I32,I64> = Tuple[#NIL 1]");

        assertEquals(PrimitiveType.I32.nilable(), contract(typed, "direct").valueType());
        assertEquals(PrimitiveType.I32.nilable(), contract(typed, "block").valueType());
        assertEquals(
                TupleType.of(List.of(PrimitiveType.I32.nilable(), PrimitiveType.I64)).nilable(),
                contract(typed, "nested").valueType());

        TypedExpression block = declarationInitializer(typed, "block");
        assertEquals(TypedExpressionKind.BLOCK, block.kind());
        assertEquals(PrimitiveType.I32.nilable(), block.children().getLast().type());
    }

    @Test
    public void conditionalArrayTupleAndEqualityPeersFoldHomogeneously() {
        TypedSemanticGraph typed = success(
                "let conditional = (#T -> Tuple[#NIL 1] : Tuple[2 3]) "
                        + "let condValue = (cond #T -> Tuple[#NIL 1] _ -> Tuple[2 3]) "
                        + "let arrays = Array[Array[#NIL] Array[1]] "
                        + "let equality = (== Array[Tuple[#NIL 1]] Array[Tuple[2 3]])");

        LyraType expectedConditional = TupleType.of(
                List.of(PrimitiveType.I64.nilable(), PrimitiveType.I64));
        assertEquals(expectedConditional, contract(typed, "conditional").valueType());
        assertEquals(expectedConditional, contract(typed, "condValue").valueType());
        assertEquals(
                ArrayType.of(ArrayType.of(PrimitiveType.I32.nilable())),
                contract(typed, "arrays").valueType());
        assertEquals(PrimitiveType.BOOL, contract(typed, "equality").valueType());

        TypedExpression equality = declarationInitializer(typed, "equality");
        TypedExpression left = equality.children().getFirst();
        assertEquals(
                ArrayType.of(TupleType.of(List.of(PrimitiveType.I64.nilable(), PrimitiveType.I64))),
                left.type());
        assertTrue(left.children().getFirst().children().getFirst().type()
                .equals(PrimitiveType.I64.nilable()));
    }

    @Test
    public void coalesceRolesNarrowingConversionsAndThenOnlyUnitRemainDistinct() {
        TypedSemanticGraph typed = success(
                "let coalesced :@nil Array<@nil I32> = (#NIL : Array[#NIL]) "
                        + "let @nil maybe :I32 = #NIL "
                        + "let narrowed = (maybe value -> I32[value] : 0) "
                        + "let effect :Fn<;Unit> = (=> | | (#T -> (+ 1 2)))");

        assertEquals(
                ArrayType.of(PrimitiveType.I32.nilable()).nilable(),
                contract(typed, "coalesced").valueType());
        assertEquals(PrimitiveType.I32, contract(typed, "narrowed").valueType());
        FunctionType effect = (FunctionType) contract(typed, "effect").valueType();
        assertEquals(PrimitiveType.UNIT, effect.returnType());

        TypedExpression thenOnly = typed.expressions().stream()
                .filter(expression -> expression.kind() == TypedExpressionKind.CONDITIONAL
                        && expression.children().size() == 2)
                .findFirst().orElseThrow();
        assertEquals(PrimitiveType.UNIT, thenOnly.type());
        assertEquals(PrimitiveType.I64, thenOnly.children().get(1).type());
    }

    @Test
    public void baseLessStructuralNilFailsAtTypePhaseWithoutAnArtifact() {
        assertFailure("let bad = #NIL", "#NIL");
        assertFailure("let bad = Array[Array[#NIL]]", "#NIL");
        assertFailure("let bad = Tuple[#NIL 1]", "#NIL");
        assertFailure("let bad = (#T -> #NIL : #NIL)", "#NIL");
        assertFailure("let bad = (cond #T -> #NIL _ -> #NIL)", "#NIL");
        assertFailure("let bad = (#NIL : 1)", "#NIL");
        assertFailure("let bad = (#T -> #NIL)", "#NIL");
        assertFailure("let bad = (== #NIL #NIL)", "==");
    }

    @Test
    public void nullableAggregatePeersAndInferredNullableMembersRemainStable() {
        TypedSemanticGraph aggregate = success(
                "let result = (#T -> #NIL : Array[1]) "
                        + "let shaped :@nil Array<@nil I32> = "
                        + "(#T -> Array[#NIL] : Array[1])");
        assertEquals(
                ArrayType.of(PrimitiveType.I32).nilable(),
                contract(aggregate, "result").valueType());
        assertEquals(
                ArrayType.of(PrimitiveType.I32.nilable()).nilable(),
                contract(aggregate, "shaped").valueType());

        TypedSemanticGraph nested = success(
                "let maybe :@nil I32 = #NIL let pair = Tuple[maybe 1]");
        assertEquals(
                TupleType.of(List.of(PrimitiveType.I32.nilable(), PrimitiveType.I64)),
                contract(nested, "pair").valueType());

        TypedSemanticGraph inferred = success(
                "let inner = (#T -> #NIL : 1) let copy = inner");
        assertEquals(PrimitiveType.I64.nilable(), contract(inferred, "inner").valueType());
        assertEquals(PrimitiveType.I64.nilable(), contract(inferred, "copy").valueType());
    }

    @Test
    public void condSuppliesStructuralAndCallbackContexts() {
        TypedSemanticGraph typed = success(
                "let nested = Array[(cond #T -> Tuple[#NIL 1] _ -> Tuple[2 3])] "
                        + "let peerNested = Array[(cond #T -> #NIL _ -> #NIL) 1] "
                        + "iter[(0..1:1) (cond #T -> |value| {} _ -> |value| {})]");
        assertEquals(
                ArrayType.of(TupleType.of(List.of(
                        PrimitiveType.I64.nilable(), PrimitiveType.I64))),
                contract(typed, "nested").valueType());
        assertEquals(ArrayType.of(PrimitiveType.I32.nilable()),
                contract(typed, "peerNested").valueType());
        assertTrue(typed.expressions().stream()
                .anyMatch(expression -> expression.kind() == TypedExpressionKind.ITER));
    }

    @Test
    public void repeatedStructuralAnalysisIsDeterministic() {
        String source = "let result = (#T -> Array[Tuple[#NIL 1]] : Array[Tuple[2 3]])";
        TypedSemanticGraph first = success(source);
        TypedSemanticGraph second = success(source);
        assertEquals(first, second);
        assertEquals(
                ArrayType.of(TupleType.of(List.of(PrimitiveType.I64.nilable(), PrimitiveType.I64))),
                contract(first, "result").valueType());
    }

    @Test
    public void provenanceRebuildsPeerShapesInsteadOfTrustingPublishedTypes() {
        TypedSemanticGraph original = success(
                "let result = (#T -> Array[#NIL] : Array[1])");
        TypedExpression declarationForm = original.modules().getFirst().forms().getFirst();
        TypedExpression conditional = declarationForm.children().getFirst();
        ArrayType forgedArray = ArrayType.of(PrimitiveType.F64.nilable());
        TypedExpression forgedNil = copy(
                conditional.children().get(1).children().getFirst(),
                PrimitiveType.F64.nilable(), List.of());
        TypedExpression forgedThen = copy(
                conditional.children().get(1), forgedArray, List.of(forgedNil));
        TypedExpression originalConversion = conditional.children().get(2)
                .children().getFirst();
        TypedExpression forgedLiteral = copy(
                originalConversion.children().getFirst(), PrimitiveType.F64, List.of());
        TypedConversion forgedConversion = new TypedConversion(
                originalConversion.span(), PrimitiveType.F64, PrimitiveType.F64.nilable(),
                ConversionKind.IMPLICIT, ConversionStep.NIL_LIFT);
        TypedExpression forgedElseConversion = copy(
                originalConversion, PrimitiveType.F64.nilable(), List.of(forgedLiteral),
                Optional.of(forgedConversion));
        TypedExpression forgedElse = copy(
                conditional.children().get(2), forgedArray, List.of(forgedElseConversion));
        TypedExpression forgedConditional = copy(
                conditional, forgedArray,
                List.of(conditional.children().getFirst(), forgedThen, forgedElse));
        TypedExpression forgedForm = copy(declarationForm, declarationForm.type(),
                List.of(forgedConditional));

        assertThrowsIllegalArgument(() -> rebuild(original, forgedForm),
                "canonical branch type");
    }

    private static TypedExpression copy(
            TypedExpression source,
            LyraType type,
            List<TypedExpression> children) {
        return copy(source, type, children, source.conversion());
    }

    private static TypedExpression copy(
            TypedExpression source,
            LyraType type,
            List<TypedExpression> children,
            Optional<TypedConversion> conversion) {
        return new TypedExpression(
                source.kind(), source.span(), type, children, source.literal(), source.link(),
                conversion, source.operator(), source.memberName(), source.tupleIndex(),
                source.declarationId(), source.lambdaId(), source.scopeId(), source.signature(),
                source.captureIds(), source.predicateBinding());
    }

    private static TypedSemanticGraph rebuild(
            TypedSemanticGraph original,
            TypedExpression forgedForm) {
        TypedModule module = original.modules().getFirst();
        TypedModule forgedModule = new TypedModule(
                module.moduleId(), module.rootScope(), module.span(), List.of(forgedForm));
        DeclarationId resultId = forgedForm.declarationId().orElseThrow();
        TypedDeclaration old = original.declaration(resultId).orElseThrow();
        TypedDeclaration forgedDeclaration = new TypedDeclaration(
                old.id(), old.name(), old.span(), old.moduleId(), old.kind(), old.contract(),
                Optional.of(forgedForm.children().getFirst()), old.initializerLambda());
        List<TypedDeclaration> declarations = original.declarations().stream()
                .map(value -> value.id().equals(resultId) ? forgedDeclaration : value)
                .toList();
        List<TypedExpression> expressions = new ArrayList<>();
        List<TypedConversion> conversions = new ArrayList<>();
        Map<SourceSpan, List<TypedExpression>> bySpan = new LinkedHashMap<>();
        collect(forgedForm, expressions, conversions, bySpan);
        return SemanticTestSupport.seal(
                original, original.resolvedGraph(), List.of(forgedModule), declarations,
                original.references(), original.lambdas(), conversions, expressions,
                original.contractsByDeclaration(), bySpan, original.mutations(),
                original.semanticFlowFacts(), original.initializationPlan(),
                io.mindspice.lyra.compiler.semantic.TypedFailureSite.fromExpressions(expressions));
    }

    private static void collect(
            TypedExpression expression,
            List<TypedExpression> expressions,
            List<TypedConversion> conversions,
            Map<SourceSpan, List<TypedExpression>> bySpan) {
        expressions.add(expression);
        expression.conversion().ifPresent(conversions::add);
        bySpan.computeIfAbsent(expression.span(), ignored -> new ArrayList<>()).add(expression);
        expression.children().forEach(child -> collect(child, expressions, conversions, bySpan));
    }

    private static void assertThrowsIllegalArgument(
            Runnable action, String messageFragment) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains(messageFragment), expected.getMessage());
            return;
        }
        throw new AssertionError("expected an independent provenance rejection");
    }

    private static BindingContract contract(TypedSemanticGraph graph, String name) {
        return graph.declarations().stream()
                .filter(declaration -> declaration.name().equals(name))
                .findFirst().orElseThrow().contract().orElseThrow();
    }

    private static TypedExpression declarationInitializer(
            TypedSemanticGraph graph, String name) {
        return graph.declarations().stream()
                .filter(declaration -> declaration.name().equals(name))
                .findFirst().orElseThrow().initializer().orElseThrow();
    }

    private static void assertFailure(String source, String primaryFragment) {
        PhaseResult<TypedSemanticGraph> result = typeResult(source);
        assertInstanceOf(PhaseResult.Failure.class, result);
        assertTrue(result.optionalValue().isEmpty(), "failed typing must not publish a graph");
        Diagnostic diagnostic = result.diagnostics().getFirst();
        assertEquals(CompilerDiagnosticCodes.TYPE_NIL_CONTEXT, diagnostic.code());
        assertEquals(Phase.TYPE, diagnostic.phase());
        assertEquals("contextual.lyra", diagnostic.primarySpan().sourceId().value());
        assertTrue(diagnostic.summary().contains("#NIL")
                        || source.substring(diagnostic.primarySpan().startOffset(),
                        diagnostic.primarySpan().endOffset()).contains(primaryFragment),
                diagnostic.render());
        assertTrue(diagnostic.relatedSpans().isEmpty(), diagnostic.render());
    }

    private static TypedSemanticGraph success(String source) {
        PhaseResult<TypedSemanticGraph> result = typeResult(source);
        assertInstanceOf(PhaseResult.Success.class, result, result.diagnostics().toString());
        return result.optionalValue().orElseThrow();
    }

    private static PhaseResult<TypedSemanticGraph> typeResult(String source) {
        ModuleId moduleId = ModuleId.path("contextual.lyra");
        SourceSnapshot snapshot = SourceSnapshot.capture(
                moduleId.sourceId(),
                PhysicalSourceKey.uri(URI.create("memory:contextual")),
                source.getBytes(StandardCharsets.UTF_8)).optionalValue().orElseThrow();
        PhaseResult<LexedSource> lexed = Lexer.lex(snapshot);
        LexedSource tokens = lexed.optionalValue().orElseThrow();
        PhaseResult<GrammarProgram> grammar = GrammarMatcher.match(tokens);
        GrammarProgram matched = grammar.optionalValue().orElseThrow();
        PhaseResult<SyntaxProgram> parsed = Parser.parse(tokens, matched);
        SyntaxProgram program = parsed.optionalValue().orElseThrow();
        ModuleGraph.Node node = new ModuleGraph.Node(
                moduleId,
                Optional.empty(),
                snapshot,
                program,
                ModuleRevision.compute(snapshot));
        PhaseResult<ResolvedSemanticGraph> resolved = SemanticResolver.resolve(
                new ModuleGraph(moduleId, List.of(node), List.of(), Map.of()));
        assertTrue(resolved instanceof PhaseResult.Success<?>, resolved.diagnostics().toString());
        return TypeChecker.check(resolved.optionalValue().orElseThrow());
    }
}
