package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.ast.SyntaxProgram;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.grammar.GrammarMatcher;
import io.mindspice.lyra.compiler.grammar.GrammarProgram;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.lex.TokenKind;
import io.mindspice.lyra.compiler.parse.Parser;
import io.mindspice.lyra.compiler.semantic.ResolvedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.SemanticResolver;
import io.mindspice.lyra.compiler.semantic.TypeChecker;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.source.ModuleRevision;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.types.PrimitiveType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused Phase-12 sealing and exhaustive traversal coverage. */
public final class TypedIrTest {
    @Test
    public void completeArtifactRetainsTypedOperationsAndFrozenMetadata() {
        TypedSemanticGraph typed = typed(
                "let @mut value :I32 = 0 "
                        + "let @pub fn :Fn<I32;I32> = (=> |x| (+ x 1)) "
                        + "let array = Array[1 2] "
                        + "let tuple = Tuple[fn array] "
                        + "let direct = ::fn[1] "
                        + "let callable = (fn 2) "
                        + "let branch = (#T -> direct : callable) "
                        + "let @nil maybe :I32 = #NIL "
                        + "let coalesced = (maybe : 4) "
                        + "let converted = I64[value] "
                        + "let changed = (value := (++ value))");
        TypedIr ir = phase(TypedIrBuilder.lower(typed));

        assertTrue(ir.isValidated());
        assertTrue(IrValidator.isValid(ir));
        assertSame(ir, IrValidator.requireValidated(ir));
        assertEquals(typed.declarations().size(), ir.declarations().size());
        assertEquals(typed.references().size(), ir.references().size());
        assertEquals(typed.lambdas().size(), ir.lambdas().size());
        assertEquals(typed.resolvedGraph().captures().size(), ir.captures().size());
        assertEquals(typed.failureSites().size(), ir.failureSites().size());
        assertEquals(typed.initializationPlan().initializationOrder(),
                ir.initializationPlan().initializationOrder());
        assertEquals(1, ir.exports().size());
        assertEquals(1, ir.rootModule().state().exportDeclarations().size());
        assertEquals(typed.semanticFlowFacts(), ir.flowMetadata().sourceFacts());
        assertFalse(ir.expressionSites().isEmpty());
        assertFalse(ir.evaluationOrders().isEmpty());
        assertFalse(ir.aggregateAllocations().isEmpty());
        assertTrue(ir.failureSites().stream().anyMatch(value ->
                value.checkKind() == IrCheckKind.BOUNDS) || typed.failureSites().stream()
                .noneMatch(value -> value.failureCode().equals("LYR-BOUNDS")));
    }

    @Test
    public void capturesCellsAndClosureInitializationArePublished() {
        TypedSemanticGraph typed = typed(
                "let @mut value :I32 = 0 "
                        + "let read :Fn<;I32> = (=> | | value)");
        TypedIr ir = phase(TypedIrBuilder.lower(typed));
        assertEquals(typed.resolvedGraph().captures().size(), ir.captures().size());
        assertTrue(ir.captures().stream().anyMatch(IrCapture::isSharedCell));
        assertEquals(1, ir.cells().size());
        assertEquals(ir.cells().getFirst().id(), ir.captures().stream()
                .filter(IrCapture::isSharedCell).findFirst().orElseThrow()
                .sharedCellId().orElseThrow());
        assertTrue(ir.closureInitializations().stream().anyMatch(value ->
                value.ownerDeclaration().isPresent() && !value.recursive()));
    }

    @Test
    public void everyClosedIrVariantIsProducedBySupportedSource() {
        TypedIr ir = phase(TypedIrBuilder.lower(typed(
                "let @mut value :I32 = 0 "
                        + "let fn :Fn<I32;I32> = (=> |x| (+ x 1)) "
                        + "let captured :Fn<;I32> = (=> | | value) "
                        + "let array = Array[1 2] let tuple = Tuple[fn array] "
                        + "let index = array[0] let length = array:.length "
                        + "let direct = ::fn[1] let callable = (fn 2) "
                        + "let @nil maybe :I32 = 1 let coalesced = (maybe : 4) "
                        + "let converted = I64[value] let guard = (and #T #F) "
                        + "let branch = (#T -> direct : callable) let matched = (match value ?? 0 -> 1 ?? _ -> 2) "
                        + "let block = { 1 } let changed = (value := (++ value))")));
        Set<Class<?>> variants = new HashSet<>();
        IrTraversal.preOrder(ir.rootModule().body()).forEach(node -> variants.add(node.getClass()));
        assertEquals(Set.of(IrNode.class.getPermittedSubclasses()), variants,
                "Every new IR operation needs a source fixture that reaches lowering and the core conformance/fuzz suites");
        assertTrue(variants.containsAll(Set.of(
                IrNode.Constant.class, IrNode.Reference.class, IrNode.CaptureReference.class,
                IrNode.Declaration.class, IrNode.Rebinding.class, IrNode.Sequence.class,
                IrNode.Block.class, IrNode.ArrayLiteral.class, IrNode.TupleLiteral.class,
                IrNode.IndexAccess.class, IrNode.Operator.class, IrNode.ShortCircuit.class,
                IrNode.Conversion.class, IrNode.Narrowing.class, IrNode.Branch.class,
                IrNode.Coalesce.class, IrNode.Match.class, IrNode.DirectCall.class, IrNode.CallableCall.class,
                IrNode.Lambda.class, IrNode.Access.class, IrNode.RuntimeCheck.class)));
    }

    @Test
    public void coalesceAndTextConversionDoNotInventRuntimeFailures() {
        TypedIr coalesce = phase(TypedIrBuilder.lower(typed(
                "let @nil maybe :I32 = 1 let value = (maybe : 2)")));
        IrNode.Coalesce node = IrTraversal.preOrder(coalesce.rootModule().body()).stream()
                .filter(IrNode.Coalesce.class::isInstance)
                .map(IrNode.Coalesce.class::cast)
                .findFirst().orElseThrow();
        assertTrue(node.value() instanceof IrNode.Narrowing);
        assertTrue(coalesce.failureSites().stream().noneMatch(site ->
                site.span().equals(node.span())));
        assertTrue(IrTraversal.preOrder(coalesce.rootModule().body()).stream()
                .noneMatch(value -> value instanceof IrNode.RuntimeCheck check
                        && check.checkKind() == IrCheckKind.EXPLICIT_CONVERSION));

        TypedSemanticGraph textTyped = typed("let value = String[1]");
        TypedIr text = phase(TypedIrBuilder.lower(textTyped));
        IrNode initializer = ((IrNode.Declaration) text.rootModule().body().forms().getFirst())
                .initializer();
        assertTrue(initializer instanceof IrNode.Conversion);
        assertTrue(textTyped.failureSites().isEmpty());
        assertTrue(text.failureSites().isEmpty());
        assertTrue(IrValidator.isValid(text));
    }

    @Test
    public void moduleExportsImportsAndInitializationOrderAreRetained() {
        ModuleId main = ModuleId.path("phase12_main.lyra");
        ModuleId library = ModuleId.path("phase12_library.lyra");
        ModuleGraph.Node mainNode = module(main,
                "import phase12_library let result = phase12_library->::number[] "
                        + "let fnValue = phase12_library->:.number");
        ModuleGraph.Node libraryNode = module(library,
                "let @pub number :Fn<;I32> = (=> | | 1)");
        LogicalModuleId logical = LogicalModuleId.fromSourceId(library.sourceId());
        ModuleGraph graph = new ModuleGraph(main, List.of(mainNode, libraryNode),
                List.of(new ModuleGraph.Edge(main, logical, library,
                        mainNode.program().imports().getFirst().path().span())),
                java.util.Map.of(logical, library));
        ResolvedSemanticGraph resolved = success(SemanticResolver.resolve(graph));
        TypedSemanticGraph typed = success(TypeChecker.check(resolved));
        TypedIr ir = phase(TypedIrBuilder.lower(typed));
        assertEquals(2, ir.modules().size());
        assertEquals(List.of(library, main), ir.initializationOrder());
        assertEquals(1, ir.exports().size());
        assertEquals(1, ir.imports().size());
        assertTrue(IrTraversal.preOrder(ir.rootModule().body()).stream()
                .anyMatch(IrNode.DirectCall.class::isInstance));
        assertTrue(IrTraversal.preOrder(ir.rootModule().body()).stream()
                .anyMatch(value -> value instanceof IrNode.Access access
                        && access.accessKind() == io.mindspice.lyra.compiler.semantic.AccessKind.NAMESPACE_VALUE));
        assertTrue(IrValidator.isValid(ir));
    }

    @Test
    public void publishedIrCollectionsAreDeeplyImmutable() {
        TypedIr ir = phase(TypedIrBuilder.lower(typed(
                "let @mut value :I32 = 0 let array = Array[1] "
                        + "let f :Fn<;I32> = (=> | | value)")));
        assertThrows(UnsupportedOperationException.class, () -> ir.modules().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> ir.rootModule().body().forms().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> ir.metadata().declarations().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> ir.flowMetadata().events().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> ir.initializationPlan().dependencies().clear());
    }

    @Test
    public void aggregatesCapturesCellsAndRecursiveLinkageRetainRoutes() {
        TypedIr ir = phase(TypedIrBuilder.lower(typed(
                "let @mut value :I32 = 0 "
                        + "let read :Fn<;I32> = (=> | | value) "
                        + "let f :Fn<;I32> = (=> | | ::f[]) "
                        + "let packed = Tuple[Array[1 2]]")));
        assertEquals(1, ir.cells().size());
        assertTrue(ir.flowMetadata().callableFlows().stream()
                .anyMatch(flow -> flow.sharedCellSnapshots().containsKey(
                        ir.cells().getFirst().declarationId())));
        assertTrue(ir.functionLinkage().components().stream()
                .anyMatch(IrFunctionScc::recursive));
        assertTrue(ir.closureInitializations().stream()
                .anyMatch(IrClosureInitialization::recursive));
        IrAggregateAllocation allocation = ir.aggregateAllocations().stream()
                .filter(value -> value.span().startOffset() > 0)
                .findFirst().orElseThrow();
        assertTrue(allocation.canonicalIdentity().isPresent());
        assertTrue(allocation.provenance().stream().allMatch(value ->
                value.path().depth() == 1 && value.path().steps().getFirst().isTupleMember()));
    }

    @Test
    public void visitorWalksEveryDescendantInExplicitPreOrder() {
        TypedIr ir = phase(TypedIrBuilder.lower(typed(
                "let @mut value :I32 = 0 let fn :Fn<I32;I32> = (=> |x| (+ x 1)) "
                        + "let array = Array[1 2] let tuple = Tuple[fn array] "
                        + "let guard = (and #T #F) let result = (#T -> (fn 1) : array[0])")));
        IrNode root = ir.rootModule().body();
        List<IrNode> expected = IrTraversal.preOrder(root);
        List<IrNode> visited = new ArrayList<>();
        Set<IrNode> unique = new HashSet<>();
        IrVisitor.walk(root, new IrVisitor<Void>() {
            private Void add(IrNode node) {
                assertTrue(unique.add(node));
                visited.add(node);
                return null;
            }

            public Void visitConstant(IrNode.Constant node) { return add(node); }
            public Void visitReference(IrNode.Reference node) { return add(node); }
            public Void visitCaptureReference(IrNode.CaptureReference node) { return add(node); }
            public Void visitDeclaration(IrNode.Declaration node) { return add(node); }
            public Void visitRebinding(IrNode.Rebinding node) { return add(node); }
            public Void visitSequence(IrNode.Sequence node) { return add(node); }
            public Void visitBlock(IrNode.Block node) { return add(node); }
            public Void visitArrayLiteral(IrNode.ArrayLiteral node) { return add(node); }
            public Void visitTupleLiteral(IrNode.TupleLiteral node) { return add(node); }
            public Void visitIndexAccess(IrNode.IndexAccess node) { return add(node); }
            public Void visitOperator(IrNode.Operator node) { return add(node); }
            public Void visitShortCircuit(IrNode.ShortCircuit node) { return add(node); }
            public Void visitConversion(IrNode.Conversion node) { return add(node); }
            public Void visitNarrowing(IrNode.Narrowing node) { return add(node); }
            public Void visitBranch(IrNode.Branch node) { return add(node); }
            public Void visitCoalesce(IrNode.Coalesce node) { return add(node); }
            public Void visitMatch(IrNode.Match node) { return add(node); }
            public Void visitDirectCall(IrNode.DirectCall node) { return add(node); }
            public Void visitCallableCall(IrNode.CallableCall node) { return add(node); }
            public Void visitLambda(IrNode.Lambda node) { return add(node); }
            public Void visitAccess(IrNode.Access node) { return add(node); }
            public Void visitRuntimeCheck(IrNode.RuntimeCheck node) { return add(node); }
        });
        assertEquals(expected, visited);
        assertEquals(expected.size(), IrTraversal.preOrder(root).size());
        assertTrue(expected.stream().anyMatch(node -> node instanceof IrNode.ShortCircuit)
                || expected.stream().anyMatch(node -> node instanceof IrNode.Branch));
        IrEvaluationOrder shortCircuitOrder = ir.evaluationOrders().stream()
                .filter(order -> order.kind() == IrEvaluationOrder.Kind.SHORT_CIRCUIT)
                .findFirst().orElseThrow();
        assertEquals(IrEvaluationOrder.EdgeKind.STRICT,
                shortCircuitOrder.edges().getFirst().kind());
        assertEquals(IrEvaluationOrder.EdgeKind.SHORT_CIRCUIT_OPERAND,
                shortCircuitOrder.edges().get(1).kind());
    }

    @Test
    public void matchIrRetainsClosedModesArmRolesAndLazyEvaluationEdges() {
        TypedIr ir = phase(TypedIrBuilder.lower(typed(
                "let subject :I32 = 2 let guard :Bool = #T "
                        + "let value = (match subject ?? 1 when guard -> 10 ?? _ -> 20)")));
        IrNode.Match match = IrTraversal.preOrder(ir.rootModule().body()).stream()
                .filter(IrNode.Match.class::isInstance).map(IrNode.Match.class::cast)
                .findFirst().orElseThrow();
        assertEquals(IrNode.MatchMode.TRADITIONAL, match.mode());
        assertTrue(match.subject().isPresent());
        assertEquals(2, match.arms().size());
        assertTrue(match.arms().getFirst().pattern().isPresent()
                && match.arms().getFirst().guard().isPresent());
        assertTrue(match.arms().getLast().wildcard()
                && match.arms().getLast().guard().isEmpty());
        IrEvaluationOrder order = ir.evaluationOrders().stream()
                .filter(value -> value.kind() == IrEvaluationOrder.Kind.MATCH)
                .findFirst().orElseThrow();
        assertEquals(List.of(
                        IrEvaluationOrder.EdgeKind.MATCH_SUBJECT,
                        IrEvaluationOrder.EdgeKind.MATCH_PATTERN,
                        IrEvaluationOrder.EdgeKind.MATCH_GUARD,
                        IrEvaluationOrder.EdgeKind.MATCH_RESULT,
                        IrEvaluationOrder.EdgeKind.MATCH_RESULT),
                order.edges().stream().map(IrEvaluationOrder.Edge::kind).toList());
    }

    @Test
    public void callableSummaryIdentityIsRetainedForLambdaCalls() {
        TypedIr ir = phase(TypedIrBuilder.lower(typed(
                "let f :Fn<I32;I32> = (=> |x| (+ x 1)) "
                        + "let g :Fn<I32;I32> = (=> |x| (f x))")));
        IrNode.CallableCall call = IrTraversal.preOrder(ir.rootModule().body()).stream()
                .filter(IrNode.CallableCall.class::isInstance)
                .map(IrNode.CallableCall.class::cast)
                .findFirst().orElseThrow();
        assertTrue(call.callId().isPresent());
        assertTrue(ir.flowMetadata().callReferences().stream()
                .anyMatch(reference -> reference.callId().equals(call.callId().orElseThrow())
                        && reference.siteId().filter(call.siteId().orElseThrow()::equals).isPresent()));
    }

    @Test
    public void constructorCandidatesCannotReachAConsumer() {
        TypedIr valid = phase(TypedIrBuilder.lower(typed("let value :I32 = 1")));
        TypedIr candidate = new TypedIr(valid.semanticGraph(), valid.modules());
        assertFalse(candidate.isValidated());
        assertThrows(IllegalStateException.class, candidate::requireValidated);
        assertFalse(IrValidator.validate(candidate).isEmpty());
    }

    @Test
    public void validatorRejectsNonFiniteF32AndOutOfRangeExplicitConversion() {
        TypedIr base = phase(TypedIrBuilder.lower(typed("let value :F32 = 1.0F32")));
        SourceSpan span = base.rootModule().body().forms().getFirst().span();
        IrNode.Constant huge = new IrNode.Constant(span, PrimitiveType.F32,
                new IrConstantValue.DecimalValue(
                        io.mindspice.lyra.compiler.types.ExactNumericLiteral.decimal(
                                new BigDecimal("3e38"), PrimitiveType.F32)));
        IrNode.Constant two = new IrNode.Constant(span, PrimitiveType.F32,
                new IrConstantValue.DecimalValue(
                        io.mindspice.lyra.compiler.types.ExactNumericLiteral.decimal(
                                new BigDecimal("2.0"), PrimitiveType.F32)));
        IrNode.Operator overflow = new IrNode.Operator(
                span, PrimitiveType.F32, TokenKind.ASTERISK, List.of(huge, two));
        assertTrue(IrValidator.validate(replaceFirstInitializer(base, overflow)).stream()
                .anyMatch(diagnostic -> diagnostic.summary().contains("checked/trapping")));

        IrNode.Constant integer = new IrNode.Constant(span, PrimitiveType.I64,
                new IrConstantValue.IntegerValue(
                        io.mindspice.lyra.compiler.types.ExactNumericLiteral.integer(
                                java.math.BigInteger.TEN.pow(100))));
        IrNode.Conversion conversion = new IrNode.Conversion(
                span, PrimitiveType.F32, PrimitiveType.I64,
                io.mindspice.lyra.compiler.types.ConversionKind.EXPLICIT,
                io.mindspice.lyra.compiler.types.ConversionStep.NUMERIC_EXPLICIT, integer);
        assertTrue(IrValidator.validate(replaceFirstInitializer(base, conversion)).stream()
                .anyMatch(diagnostic -> diagnostic.summary().contains("exact target contract")));
    }

    @Test
    public void initializationSchedulesRejectDuplicateEntries() {
        ModuleId first = ModuleId.path("first.lyra");
        ModuleId second = ModuleId.path("second.lyra");
        assertThrows(IllegalArgumentException.class, () -> new IrInitializationPlan(
                List.of(first, second), List.of(), List.of(first, first, second), List.of()));
    }

    @Test
    public void metadataCoverageIsRequiredForASealedCandidate() {
        TypedIr valid = phase(TypedIrBuilder.lower(typed("let value :I32 = 1")));
        IrProgramMetadata source = valid.metadata();
        IrProgramMetadata incomplete = new IrProgramMetadata(
                source.declarations(), source.references(), source.lambdas(), source.captures(),
                source.cells(), source.exports(), source.imports(), source.functionLinkage(),
                source.closureInitializations(), List.of(), List.of(), List.of(),
                source.initializationPlan(), source.flowMetadata());
        TypedIr candidate = new TypedIr(valid.semanticGraph(), valid.modules(), incomplete);
        assertTrue(IrValidator.validate(candidate).stream().anyMatch(diagnostic ->
                diagnostic.code().value().equals("LYC-IR-005")
                        || diagnostic.code().value().equals("LYC-IR-008")));
    }

    @Test
    public void repeatedLoweringHasStableEqualityAndHash() {
        TypedSemanticGraph first = typed("let value :I64 = (+ 1 2)");
        TypedSemanticGraph second = typed("let value :I64 = (+ 1 2)");
        TypedIr firstIr = phase(TypedIrBuilder.lower(first));
        TypedIr secondIr = phase(TypedIrBuilder.lower(second));
        assertEquals(firstIr, secondIr);
        assertEquals(firstIr.hashCode(), secondIr.hashCode());
        assertEquals(firstIr.metadata(), secondIr.metadata());
    }

    private static TypedSemanticGraph typed(String source) {
        ModuleId id = ModuleId.path("phase12.lyra");
        ModuleGraph.Node node = module(id, source);
        ResolvedSemanticGraph resolved = success(SemanticResolver.resolve(
                new ModuleGraph(id, List.of(node), List.of(), java.util.Map.of())));
        return success(TypeChecker.check(resolved));
    }

    private static ModuleGraph.Node module(ModuleId id, String source) {
        SourceSnapshot snapshot = success(SourceSnapshot.capture(
                id.sourceId(), PhysicalSourceKey.uri(URI.create("memory:" + id.value())),
                source.getBytes(StandardCharsets.UTF_8)));
        LexedSource lexed = success(Lexer.lex(snapshot));
        GrammarProgram grammar = success(GrammarMatcher.match(lexed));
        SyntaxProgram syntax = success(Parser.parse(lexed, grammar));
        return new ModuleGraph.Node(id,
                java.util.Optional.of(LogicalModuleId.fromSourceId(id.sourceId())), snapshot,
                syntax, ModuleRevision.compute(snapshot));
    }

    private static <T extends io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact>
    T success(PhaseResult<T> result) {
        if (!(result instanceof PhaseResult.Success<T> success)) {
            throw new AssertionError(result.diagnostics().toString());
        }
        return success.value();
    }

    private static TypedIr phase(PhaseResult<TypedIr> result) {
        return success(result);
    }

    private static TypedIr replaceFirstInitializer(TypedIr ir, IrNode initializer) {
        IrModule original = ir.rootModule();
        IrNode.Declaration declaration = (IrNode.Declaration) original.body().forms().getFirst();
        ArrayList<IrNode> forms = new ArrayList<>(original.body().forms());
        forms.set(0, new IrNode.Declaration(
                declaration.span(), declaration.type(), declaration.declarationId(),
                declaration.declarationKind(), declaration.contract(), initializer));
        IrModule altered = new IrModule(original.moduleId(), original.rootScope(), original.span(),
                new IrNode.Sequence(original.span(), PrimitiveType.UNIT, forms));
        return new TypedIr(ir.semanticGraph(), List.of(altered));
    }

    private static void assertSame(Object expected, Object actual) {
        assertTrue(expected == actual);
    }
}
