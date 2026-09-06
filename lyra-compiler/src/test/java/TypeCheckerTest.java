import org.junit.jupiter.api.Test;

import io.mindspice.lyra.compiler.ast.SyntaxNode;
import io.mindspice.lyra.compiler.ast.SyntaxProgram;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.grammar.GrammarMatcher;
import io.mindspice.lyra.compiler.grammar.GrammarProgram;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.parse.Parser;
import io.mindspice.lyra.compiler.semantic.ResolvedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.SemanticResolver;
import io.mindspice.lyra.compiler.semantic.TypedExpressionKind;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.SemanticTestSupport;
import io.mindspice.lyra.compiler.semantic.TypeChecker;
import io.mindspice.lyra.compiler.ir.TypedIr;
import io.mindspice.lyra.compiler.ir.TypedIrBuilder;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Focused checks for the initial bidirectional type/IR phase. */
public final class TypeCheckerTest {
    @Test
    public void testScalarsDeclarationsOperatorsAndCalls() {
        TypedSemanticGraph typed = success(
                "let inc :Fn<I32;I64> = (=> |value| (+ value 1)) "
                        + "let direct = ::inc[1] let callable = (inc 2) "
                        + "let comparison = (< direct callable) let truth = (and #T comparison)");
        check(typed.declarations().stream().anyMatch(value -> value.name().equals("direct")
                        && value.contract().orElseThrow().valueType().equals(io.mindspice.lyra.compiler.types.PrimitiveType.I64)),
                "call result contracts synthesize from the function signature");
        check(typed.expressions().stream().anyMatch(value -> value.kind() == TypedExpressionKind.DIRECT_CALL),
                "direct calls retain a direct-call typed node");
        check(typed.expressions().stream().anyMatch(value -> value.kind() == TypedExpressionKind.CALLABLE_CALL),
                "callable values retain a callable-call typed node");
        check(typed.conversions().stream().anyMatch(value -> value.step()
                        == io.mindspice.lyra.compiler.types.ConversionStep.NUMERIC_WIDENING),
                "expected I64 call parameters insert only lossless widening");
    }

    @Test
    public void testContextualForcedAndNilTyping() {
        TypedSemanticGraph typed = success(
                "let contextual :I16 = 42 let forced :I8 = 42I8 let @nil inferredNil = 1 "
                        + "let @nil label :String = #NIL "
                        + "let joined = (label : \"fallback\") "
                        + "let branch :@nil I32 = (#T -> 1 : #NIL)");
        check(typed.contractsByDeclaration().values().stream().anyMatch(contract ->
                        contract.valueType().equals(io.mindspice.lyra.compiler.types.PrimitiveType.I16)),
                "unsuffixed integer adopts the expected exact type");
        check(typed.conversions().stream().anyMatch(value ->
                        value.step() == io.mindspice.lyra.compiler.types.ConversionStep.NIL_LIFT),
                "non-nil branch values are lifted only into an explicit @nil contract");
        failure("let value = #NIL", "LYC-TYPE-005");
        failure("let value :I8 = 300", "LYC-TYPE-004");
        failure("let value = (1 2)", "LYC-TYPE-007");
    }

    @Test
    public void testBlocksAndImmutableTypedIr() {
        TypedSemanticGraph typed = success(
                "let @mut value :I32 = 0 let result :I32 = "
                        + "{ let local :I16 = 1 value := (+ value local) value }");
        check(typed.modules().getFirst().forms().getLast().type()
                        == io.mindspice.lyra.compiler.types.PrimitiveType.UNIT,
                "top-level declaration operations are Unit");
        TypedIr ir = phaseSuccess(TypedIrBuilder.lower(typed));
        check(ir.modules().getFirst().body().forms().size() == 2,
                "IR preserves strict top-level sequence order");
        check(io.mindspice.lyra.compiler.ir.IrValidator.isValid(ir),
                "the published scalar IR passes its closed-graph validator");
        expectUnsupported(() -> typed.modules().clear());
        expectUnsupported(() -> ir.modules().clear());
    }

    @Test
    public void testPredicateBindingCompactLambdaAndNamespaceLinks() {
        TypedSemanticGraph typed = success(
                "let @nil value :I32 = #NIL "
                        + "let result = (value narrowed -> narrowed : 0) "
                        + "let apply :Fn<Fn<I32;I32>;I32> = "
                        + "(=> |function :Fn<I32;I32>| (function 1)) "
                        + "let called = (apply |x| x)");
        check(typed.declarations().stream().anyMatch(value -> value.name().equals("narrowed")
                        && value.contract().orElseThrow().valueType()
                        .equals(io.mindspice.lyra.compiler.types.PrimitiveType.I32)),
                "predicate bindings receive the narrowed base type");
        check(typed.expressions().stream().anyMatch(value ->
                        value.kind() == TypedExpressionKind.LAMBDA),
                "compact lambda arguments are checked against the complete Fn parameter");

        ModuleId mainId = ModuleId.path("type_main.lyra");
        ModuleId libraryId = ModuleId.path("type_library.lyra");
        io.mindspice.lyra.compiler.source.LogicalModuleId library =
                io.mindspice.lyra.compiler.source.LogicalModuleId.parse("type_library");
        ModuleGraph graph = CanonicalModuleGraph.create(
                mainId,
                List.of(
                        module(mainId, "import type_library import type_library->{value} "
                                + "let direct = type_library->::value[] "
                                + "let member = type_library->:.value"),
                        module(libraryId, "let @pub value :Fn<;I32> = (=> | | 1)")),
                List.of(
                        new ModuleGraph.Edge(mainId, library, libraryId,
                                io.mindspice.lyra.compiler.source.SourceSpan.of(
                                        mainId.sourceId(), 7, 19)),
                        new ModuleGraph.Edge(mainId, library, libraryId,
                                io.mindspice.lyra.compiler.source.SourceSpan.of(
                                        mainId.sourceId(), 20, 32))),
                Map.of(library, libraryId));
        PhaseResult<ResolvedSemanticGraph> resolved = SemanticResolver.resolve(graph);
        check(resolved instanceof PhaseResult.Success<?>, "namespace test resolves");
        TypedSemanticGraph namespaceTyped = ((PhaseResult.Success<TypedSemanticGraph>) TypeChecker.check(
                ((PhaseResult.Success<ResolvedSemanticGraph>) resolved).value())).value();
        check(namespaceTyped.expressions().stream().anyMatch(value ->
                        value.kind() == TypedExpressionKind.NAMESPACE_DIRECT_CALL)
                        && namespaceTyped.expressions().stream().anyMatch(value ->
                        value.kind() == TypedExpressionKind.NAMESPACE_MEMBER_ACCESS),
                "namespace direct and member access remain distinct typed links");
        check(io.mindspice.lyra.compiler.ir.IrValidator.isValid(
                        phaseSuccess(TypedIrBuilder.lower(namespaceTyped))),
                "namespace distinctions survive IR lowering");
    }

    @Test
    public void testAllScalarFormsAndBranchRules() {
        TypedSemanticGraph typed = success(
                "let bool = #T let text = \"lyra\" let character = 'x' let unit = () "
                        + "let integer = 42 let decimal = 1.5 "
                        + "let sum = (+ 1I8 2I16) let difference = (- 3) "
                        + "let minimum :I8 = (- 128) let forcedMinimum = (- 128I8) "
                        + "let quotient = (/ 4 2) let remainder = (% 5I16 2I8) "
                        + "let power = (^ 2I16 3I16) let increment = (++ integer) "
                        + "let conjunction = (and #T text) let negation = (not #F) "
                        + "let comparison = (<= 1I8 2I16) let equality = (== 1I8 1I16) "
                        + "let numericEquality = (== 1 2.0) "
                        + "let identity = (eq? (=> :I32 |x :I32| x) "
                        + "(=> :I32 |x :I32| x)) "
                        + "let only = (#T -> 1) let both = (#T -> 1 : 2) "
                        + "let mixedBranch = (#T -> 1 : 2.0) "
                        + "let nilBranch = (#T -> 1 : #NIL) "
                        + "let @nil maybe :I32 = #NIL let coalesced = (maybe : 0) "
                        + "let converted = String[integer] let length = text:.length");
        check(typed.declarations().stream().filter(value -> value.name().equals("integer"))
                        .findFirst().orElseThrow().contract().isPresent(),
                "scalar declarations receive complete contracts");
        check(typed.expressions().stream().anyMatch(value -> value.kind() == TypedExpressionKind.COALESCE),
                "nil coalescing is represented as a typed expression");
        check(typed.conversions().stream().anyMatch(value -> value.kind()
                        == io.mindspice.lyra.compiler.types.ConversionKind.EXPLICIT),
                "explicit String conversion is recorded");
        check(typed.expressions().stream().anyMatch(value ->
                        value.kind() == TypedExpressionKind.MEMBER_ACCESS),
                "built-in scalar member access is typed without erasing its distinction");
        check(io.mindspice.lyra.compiler.ir.IrValidator.isValid(
                        phaseSuccess(TypedIrBuilder.lower(typed))),
                "all scalar control nodes form a valid closed IR");
        success("let f :Fn<@mut I32;Unit> = (=> |@mut value :I32| () ) "
                + "let @mut value :I32 = 0 let call = (f value)");
        success("let @mut value :@nil I32 = #NIL let result = (value : 1)");
        success("let nullable :Fn<;@nil String> = (=> | | #NIL)");
    }

    @Test
    public void testConstantIntegerArithmeticFailuresAreCompileTimeDiagnostics() {
        failure("let bad = (+ 127I8 1I8)", "LYC-TYPE-008");
        failure("let bad = (- 0U8 1U8)", "LYC-TYPE-008");
        failure("let bad = (^ 2I8 (- 1I8))", "LYC-TYPE-008");
        failure("let bad = (* 64I8 2I8)", "LYC-TYPE-008");
        failure("let bad = (+ I8[1.0] 127I8)", "LYC-TYPE-008");
        failure("let bad = (/ 1I8 0I8)", "LYC-TYPE-008");
        failure("let bad = (% 1U8 0U8)", "LYC-TYPE-008");
        failure("let bad = (^ 2I8 7I8)", "LYC-TYPE-008");
        failure("let bad = (-- (- 128I8))", "LYC-TYPE-008");

        success("let minimum = (- 128I8) let power = (^ (- 2I8) 7I8) "
                + "let contextual :I8 = (+ 1 2) let quotient :F32 = (/ 4 2) "
                + "let @mut text :String = \"a\" let joined = (+ text text)");
        TypedIr runtimeChecked = phaseSuccess(TypedIrBuilder.lower(success(
                "let input :I8 = 127I8 let result = (+ input 1I8)")));
        check(containsRuntimeCheck(runtimeChecked.modules().getFirst().body()),
                "nonconstant integer arithmetic retains its runtime check");
    }

    @Test
    public void testExpectedNumericContextFlowsThroughNestedOperations() {
        TypedSemanticGraph typed = success(
                "let small :I8 = (+ (+ 1 2) 3) "
                        + "let floating :F32 = (+ (+ 1 2) 3) "
                        + "let nestedWidened :I16 = (+ (+ 1I8 2I8) 3I8) "
                        + "let nestedQuotient :F32 = (+ (/ 4 2) 1) "
                        + "let negated :I8 = (- (+ 1 2)) "
                        + "let base :I8 = 1 let widenedNegation :I16 = (- base)");
        io.mindspice.lyra.compiler.semantic.TypedExpression small = typed.declarations().stream()
                .filter(value -> value.name().equals("small"))
                .findFirst().orElseThrow().initializer().orElseThrow();
        io.mindspice.lyra.compiler.semantic.TypedExpression floating = typed.declarations().stream()
                .filter(value -> value.name().equals("floating"))
                .findFirst().orElseThrow().initializer().orElseThrow();
        io.mindspice.lyra.compiler.semantic.TypedExpression nestedWidened =
                typed.declarations().stream()
                        .filter(value -> value.name().equals("nestedWidened"))
                        .findFirst().orElseThrow().initializer().orElseThrow();
        io.mindspice.lyra.compiler.semantic.TypedExpression nestedQuotient =
                typed.declarations().stream()
                        .filter(value -> value.name().equals("nestedQuotient"))
                        .findFirst().orElseThrow().initializer().orElseThrow();
        io.mindspice.lyra.compiler.semantic.TypedExpression negated = typed.declarations().stream()
                .filter(value -> value.name().equals("negated"))
                .findFirst().orElseThrow().initializer().orElseThrow();
        io.mindspice.lyra.compiler.semantic.TypedExpression widenedNegation =
                typed.declarations().stream()
                        .filter(value -> value.name().equals("widenedNegation"))
                        .findFirst().orElseThrow().initializer().orElseThrow();
        check(small.type() == io.mindspice.lyra.compiler.types.PrimitiveType.I8
                        && small.children().getFirst().type()
                        == io.mindspice.lyra.compiler.types.PrimitiveType.I8,
                "I8 context reaches nested exact numeric operations");
        check(floating.type() == io.mindspice.lyra.compiler.types.PrimitiveType.F32
                        && floating.children().getFirst().type()
                        == io.mindspice.lyra.compiler.types.PrimitiveType.F32,
                "F32 context reaches nested exact numeric operations");
        check(nestedWidened.type() == io.mindspice.lyra.compiler.types.PrimitiveType.I16
                        && nestedWidened.children().getFirst().type()
                        == io.mindspice.lyra.compiler.types.PrimitiveType.I16,
                "lossless widening context reaches nested forced-literal operations");
        check(nestedQuotient.type() == io.mindspice.lyra.compiler.types.PrimitiveType.F32
                        && nestedQuotient.children().getFirst().type()
                        == io.mindspice.lyra.compiler.types.PrimitiveType.F32
                        && nestedQuotient.children().getFirst().children().stream()
                        .allMatch(child -> child.type()
                                == io.mindspice.lyra.compiler.types.PrimitiveType.I64),
                "nested integer division keeps integer operands before its F32 result context");
        check(negated.type() == io.mindspice.lyra.compiler.types.PrimitiveType.I8
                        && negated.children().getFirst().type()
                        == io.mindspice.lyra.compiler.types.PrimitiveType.I8,
                "unary minus forwards its expected numeric context to a nested operation");
        check(widenedNegation.type() == io.mindspice.lyra.compiler.types.PrimitiveType.I16
                        && widenedNegation.children().getFirst().type()
                        == io.mindspice.lyra.compiler.types.PrimitiveType.I16,
                "unary minus records legal contextual widening on a fixed operand");
        check(io.mindspice.lyra.compiler.ir.IrValidator.isValid(
                        phaseSuccess(TypedIrBuilder.lower(typed))),
                "nested contextually typed arithmetic lowers to valid exact-type IR");

        failure("let source :I64 = 1 let bad :I8 = (+ (+ source 1) 2)",
                "LYC-TYPE-001");
        failure("let bad :I8 = (+ (+ 127 1) 0)", "LYC-TYPE-008");
    }

    @Test
    public void testInferredNumericWideningPreservesNestedProvenance() {
        TypedSemanticGraph typed = success(
                "let arithmetic = (+ (+ 1I8 2I8) 3I16) "
                        + "let comparison = (< (+ 1I8 2I8) 3I16) "
                        + "let division = (/ (+ 1I8 2I8) 3I16) "
                        + "let conditional = (#T -> (+ 1I8 2I8) : 3I16)");
        io.mindspice.lyra.compiler.semantic.TypedExpression arithmetic = typed.declarations().stream()
                .filter(value -> value.name().equals("arithmetic"))
                .findFirst().orElseThrow().initializer().orElseThrow();
        io.mindspice.lyra.compiler.semantic.TypedExpression comparison = typed.declarations().stream()
                .filter(value -> value.name().equals("comparison"))
                .findFirst().orElseThrow().initializer().orElseThrow();
        io.mindspice.lyra.compiler.semantic.TypedExpression division = typed.declarations().stream()
                .filter(value -> value.name().equals("division"))
                .findFirst().orElseThrow().initializer().orElseThrow();
        io.mindspice.lyra.compiler.semantic.TypedExpression conditional = typed.declarations().stream()
                .filter(value -> value.name().equals("conditional"))
                .findFirst().orElseThrow().initializer().orElseThrow();

        check(arithmetic.type() == io.mindspice.lyra.compiler.types.PrimitiveType.I16,
                "inferred arithmetic selects the lossless outer common type");
        check(comparison.type() == io.mindspice.lyra.compiler.types.PrimitiveType.BOOL,
                "inferred comparison retains its Bool result");
        check(division.type() == io.mindspice.lyra.compiler.types.PrimitiveType.F64,
                "inferred integer division retains its operator-defined F64 result");
        check(conditional.type() == io.mindspice.lyra.compiler.types.PrimitiveType.I16,
                "inferred conditional selects the lossless branch type");

        List<io.mindspice.lyra.compiler.semantic.TypedExpression> widened = List.of(
                arithmetic.children().getFirst(),
                comparison.children().getFirst(),
                division.children().getFirst(),
                conditional.children().get(1));
        check(widened.stream().allMatch(value ->
                        value.kind() == TypedExpressionKind.CONVERSION
                                && value.type() == io.mindspice.lyra.compiler.types.PrimitiveType.I16
                                && value.children().size() == 1
                                && value.children().getFirst().kind() == TypedExpressionKind.OPERATOR
                                && value.children().getFirst().type()
                                == io.mindspice.lyra.compiler.types.PrimitiveType.I8
                                && value.span().equals(value.children().getFirst().span())
                                && value.conversion().filter(conversion ->
                                conversion.kind() == io.mindspice.lyra.compiler.types.ConversionKind.IMPLICIT
                                        && conversion.step()
                                        == io.mindspice.lyra.compiler.types.ConversionStep.NUMERIC_WIDENING
                                        && conversion.sourceType()
                                        == io.mindspice.lyra.compiler.types.PrimitiveType.I8
                                        && conversion.targetType()
                                        == io.mindspice.lyra.compiler.types.PrimitiveType.I16
                                        && conversion.span().equals(value.span())).isPresent()),
                "each inferred nested I8 operation retains one exact I8-to-I16 conversion");
        check(typed.conversions().size() == widened.size(),
                "inferred widening publishes only the four reachable conversion records");
        check(io.mindspice.lyra.compiler.ir.IrValidator.isValid(
                        phaseSuccess(TypedIrBuilder.lower(typed))),
                "inferred arithmetic, comparison, division, and branch widening lower to valid IR");
    }

    @Test
    public void testTypedGraphRejectsRootWideningForInferredDeclaration() {
        TypedSemanticGraph original = success("let x = (+ 1I8 2I8)");
        io.mindspice.lyra.compiler.semantic.TypedExpression declaration =
                original.modules().getFirst().forms().getFirst();
        io.mindspice.lyra.compiler.semantic.TypedExpression initializer =
                declaration.children().getFirst();
        io.mindspice.lyra.compiler.types.PrimitiveType forgedType =
                io.mindspice.lyra.compiler.types.PrimitiveType.I16;
        io.mindspice.lyra.compiler.semantic.TypedConversion forgedConversion =
                new io.mindspice.lyra.compiler.semantic.TypedConversion(
                        initializer.span(), initializer.type(), forgedType,
                        io.mindspice.lyra.compiler.types.ConversionKind.IMPLICIT,
                        io.mindspice.lyra.compiler.types.ConversionStep.NUMERIC_WIDENING);
        io.mindspice.lyra.compiler.semantic.TypedExpression forgedInitializer =
                new io.mindspice.lyra.compiler.semantic.TypedExpression(
                        TypedExpressionKind.CONVERSION, initializer.span(), forgedType,
                        List.of(initializer), java.util.Optional.empty(), java.util.Optional.empty(),
                        java.util.Optional.of(forgedConversion), java.util.Optional.empty(),
                        java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                        java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                        List.of(), java.util.Optional.empty());
        io.mindspice.lyra.compiler.semantic.TypedExpression forgedForm =
                copyTypedExpression(declaration, List.of(forgedInitializer));
        io.mindspice.lyra.compiler.identity.DeclarationId declarationId =
                declaration.declarationId().orElseThrow();
        io.mindspice.lyra.compiler.types.BindingContract forgedContract =
                io.mindspice.lyra.compiler.types.BindingContract.immutable(forgedType);
        List<io.mindspice.lyra.compiler.semantic.TypedDeclaration> forgedDeclarations =
                original.declarations().stream().map(value -> value.id().equals(declarationId)
                        ? new io.mindspice.lyra.compiler.semantic.TypedDeclaration(
                        value.id(), value.name(), value.span(), value.moduleId(), value.kind(),
                        java.util.Optional.of(forgedContract),
                        java.util.Optional.of(forgedInitializer), value.initializerLambda())
                        : value).toList();
        Map<io.mindspice.lyra.compiler.identity.DeclarationId,
                io.mindspice.lyra.compiler.types.BindingContract> forgedContracts =
                new java.util.LinkedHashMap<>(original.contractsByDeclaration());
        forgedContracts.put(declarationId, forgedContract);

        expectIllegalArgument(() -> rebuildTypedGraph(
                        original, List.of(forgedForm), forgedDeclarations,
                        original.references(), forgedContracts),
                "context-free initializer type");
    }

    @Test
    public void testIntegerDivisionKeepsIntegerOperandCommonTypeInFloatContext() {
        TypedSemanticGraph typed = success(
                "let single :F32 = (/ 4I8 2I16) let wide :F64 = (/ 8I8 2I16)");
        List<io.mindspice.lyra.compiler.semantic.TypedExpression> divisions =
                typed.expressions().stream().filter(expression ->
                        expression.kind() == TypedExpressionKind.OPERATOR
                                && expression.operator().orElse("").equals("/")).toList();
        check(divisions.size() == 2
                        && divisions.stream().allMatch(expression -> expression.children().stream()
                        .allMatch(child -> child.type()
                                == io.mindspice.lyra.compiler.types.PrimitiveType.I16)),
                "integer division first selects one lossless integer operand type");
        check(divisions.stream().anyMatch(expression -> expression.type()
                        == io.mindspice.lyra.compiler.types.PrimitiveType.F32)
                        && divisions.stream().anyMatch(expression -> expression.type()
                        == io.mindspice.lyra.compiler.types.PrimitiveType.F64),
                "integer division applies its F32/F64 result rule after integer common typing");
        check(io.mindspice.lyra.compiler.ir.IrValidator.isValid(
                        phaseSuccess(TypedIrBuilder.lower(typed))),
                "contextual integer division lowers with explicit integer widening");

        failure("let bad :F32 = (/ 1I8 0I16)", "LYC-TYPE-008",
                "constant integer division by zero");
        failure("let bad :F64 = (/ 1I8 0I16)", "LYC-TYPE-008",
                "constant integer division by zero");
    }

    @Test
    public void testTypedGraphRejectsTamperedInferredConditionalType() {
        TypedSemanticGraph original = success("let result = (#T -> 1 : 2)");
        io.mindspice.lyra.compiler.semantic.TypedExpression declaration =
                original.modules().getFirst().forms().getFirst();
        io.mindspice.lyra.compiler.semantic.TypedExpression conditional =
                declaration.children().getFirst();
        io.mindspice.lyra.compiler.types.PrimitiveType forgedType =
                io.mindspice.lyra.compiler.types.PrimitiveType.F32;
        io.mindspice.lyra.compiler.semantic.TypedExpression forgedThen = copyTypedExpression(
                conditional.children().get(1), forgedType, List.of());
        io.mindspice.lyra.compiler.semantic.TypedExpression forgedElse = copyTypedExpression(
                conditional.children().get(2), forgedType, List.of());
        io.mindspice.lyra.compiler.semantic.TypedExpression forgedConditional = copyTypedExpression(
                conditional, forgedType,
                List.of(conditional.children().getFirst(), forgedThen, forgedElse));
        io.mindspice.lyra.compiler.semantic.TypedExpression forgedForm = copyTypedExpression(
                declaration, List.of(forgedConditional));
        io.mindspice.lyra.compiler.identity.DeclarationId resultId =
                declaration.declarationId().orElseThrow();
        io.mindspice.lyra.compiler.types.BindingContract forgedContract =
                io.mindspice.lyra.compiler.types.BindingContract.immutable(forgedType);
        List<io.mindspice.lyra.compiler.semantic.TypedDeclaration> forgedDeclarations =
                original.declarations().stream().map(value -> value.id().equals(resultId)
                        ? new io.mindspice.lyra.compiler.semantic.TypedDeclaration(
                        value.id(), value.name(), value.span(), value.moduleId(), value.kind(),
                        java.util.Optional.of(forgedContract),
                        java.util.Optional.of(forgedConditional), value.initializerLambda())
                        : value).toList();
        Map<io.mindspice.lyra.compiler.identity.DeclarationId,
                io.mindspice.lyra.compiler.types.BindingContract> forgedContracts =
                new java.util.LinkedHashMap<>(original.contractsByDeclaration());
        forgedContracts.put(resultId, forgedContract);

        expectIllegalArgument(() -> rebuildTypedGraph(
                        original, List.of(forgedForm), forgedDeclarations,
                        original.references(), forgedContracts),
                "canonical branch type");
    }

    @Test
    public void testTypedGraphRejectsTamperedPredicateBindingType() {
        TypedSemanticGraph original = success(
                "let @nil value :I32 = #NIL let result = (value narrowed -> narrowed : 0)");
        io.mindspice.lyra.compiler.semantic.TypedExpression valueForm =
                original.modules().getFirst().forms().getFirst();
        io.mindspice.lyra.compiler.semantic.TypedExpression resultForm =
                original.modules().getFirst().forms().get(1);
        io.mindspice.lyra.compiler.semantic.TypedExpression conditional =
                resultForm.children().getFirst();
        io.mindspice.lyra.compiler.identity.DeclarationId bindingId =
                conditional.predicateBinding().orElseThrow();
        io.mindspice.lyra.compiler.identity.DeclarationId resultId =
                resultForm.declarationId().orElseThrow();
        io.mindspice.lyra.compiler.types.PrimitiveType forgedType =
                io.mindspice.lyra.compiler.types.PrimitiveType.F64;
        io.mindspice.lyra.compiler.types.BindingContract forgedContract =
                io.mindspice.lyra.compiler.types.BindingContract.immutable(forgedType);

        io.mindspice.lyra.compiler.semantic.TypedReference bindingReference =
                original.references().stream().filter(value ->
                        value.targetDeclaration().filter(bindingId::equals).isPresent())
                        .findFirst().orElseThrow();
        io.mindspice.lyra.compiler.semantic.TypedReference forgedReference =
                new io.mindspice.lyra.compiler.semantic.TypedReference(
                        bindingReference.id(), bindingReference.name(), bindingReference.span(),
                        bindingReference.moduleId(), bindingReference.scopeId(), bindingReference.kind(),
                        java.util.Optional.of(forgedType), bindingReference.targetDeclaration(),
                        bindingReference.targetModule(), bindingReference.targetExport(),
                        bindingReference.fromLambda(), bindingReference.capture());
        List<io.mindspice.lyra.compiler.semantic.TypedReference> forgedReferences =
                original.references().stream().map(value -> value.id().equals(forgedReference.id())
                        ? forgedReference : value).toList();

        io.mindspice.lyra.compiler.semantic.TypedExpression forgedThen = copyTypedExpression(
                conditional.children().get(1), forgedType, List.of());
        io.mindspice.lyra.compiler.semantic.TypedExpression forgedElse = copyTypedExpression(
                conditional.children().get(2), forgedType, List.of());
        io.mindspice.lyra.compiler.semantic.TypedExpression forgedConditional = copyTypedExpression(
                conditional, forgedType,
                List.of(conditional.children().getFirst(), forgedThen, forgedElse));
        io.mindspice.lyra.compiler.semantic.TypedExpression forgedResultForm = copyTypedExpression(
                resultForm, List.of(forgedConditional));
        List<io.mindspice.lyra.compiler.semantic.TypedDeclaration> forgedDeclarations =
                original.declarations().stream().map(value -> {
                    if (value.id().equals(bindingId)) {
                        return new io.mindspice.lyra.compiler.semantic.TypedDeclaration(
                                value.id(), value.name(), value.span(), value.moduleId(), value.kind(),
                                java.util.Optional.of(forgedContract), value.initializer(),
                                value.initializerLambda());
                    }
                    if (value.id().equals(resultId)) {
                        return new io.mindspice.lyra.compiler.semantic.TypedDeclaration(
                                value.id(), value.name(), value.span(), value.moduleId(), value.kind(),
                                java.util.Optional.of(forgedContract),
                                java.util.Optional.of(forgedConditional), value.initializerLambda());
                    }
                    return value;
                }).toList();
        Map<io.mindspice.lyra.compiler.identity.DeclarationId,
                io.mindspice.lyra.compiler.types.BindingContract> forgedContracts =
                new java.util.LinkedHashMap<>(original.contractsByDeclaration());
        forgedContracts.put(bindingId, forgedContract);
        forgedContracts.put(resultId, forgedContract);

        expectIllegalArgument(() -> rebuildTypedGraph(
                        original, List.of(valueForm, forgedResultForm), forgedDeclarations,
                        forgedReferences, forgedContracts),
                "exact immutable narrowed predicate type");
    }

    @Test
    public void testTypedGraphRejectsSameSpanFakeSourceNodes() {
        TypedSemanticGraph original = success("let value :I64 = (+ 1 2)");
        io.mindspice.lyra.compiler.semantic.TypedExpression declaration =
                original.modules().getFirst().forms().getFirst();
        io.mindspice.lyra.compiler.semantic.TypedExpression initializer =
                declaration.children().getFirst();
        io.mindspice.lyra.compiler.semantic.TypedExpression fakeInitializer =
                new io.mindspice.lyra.compiler.semantic.TypedExpression(
                        TypedExpressionKind.LITERAL, initializer.span(), initializer.type(), List.of(),
                        java.util.Optional.of(new io.mindspice.lyra.compiler.semantic.TypedLiteralValue.IntegerValue(
                                io.mindspice.lyra.compiler.types.ExactNumericLiteral.integer(
                                        java.math.BigInteger.valueOf(3)))),
                        java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                        java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                        java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                        List.of(), java.util.Optional.empty());
        io.mindspice.lyra.compiler.semantic.TypedExpression alteredDeclaration =
                copyTypedExpression(declaration, List.of(fakeInitializer));
        expectIllegalArgument(() -> rebuildTypedGraph(
                original, alteredDeclaration, fakeInitializer));

        io.mindspice.lyra.compiler.semantic.TypedExpression fakeTopLevel =
                new io.mindspice.lyra.compiler.semantic.TypedExpression(
                        TypedExpressionKind.LITERAL, declaration.span(), declaration.type(), List.of(),
                        java.util.Optional.of(new io.mindspice.lyra.compiler.semantic.TypedLiteralValue.UnitValue("()")),
                        java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                        java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                        java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                        List.of(), java.util.Optional.empty());
        expectIllegalArgument(() -> rebuildTypedGraph(original, fakeTopLevel));
    }

    @Test
    public void testTypedArtifactsAreDeterministic() {
        String source = "let @mut value :I32 = 0 let f :Fn<;I32> = (=> | | value)";
        TypedSemanticGraph first = success(source);
        TypedSemanticGraph second = success(source);
        check(first.equals(second), "repeated type checking preserves deterministic IDs and structure");
        TypedIr firstIr = phaseSuccess(TypedIrBuilder.lower(first));
        TypedIr secondIr = phaseSuccess(TypedIrBuilder.lower(second));
        check(firstIr.equals(secondIr), "repeated lowering preserves deterministic IR structure");
    }

    @Test
    public void testCapturedReferencesLowerToClosedCaptureNodes() {
        TypedSemanticGraph typed = success(
                "let @mut value :I32 = 0 let read :Fn<;I32> = (=> | | value)");
        TypedIr ir = phaseSuccess(TypedIrBuilder.lower(typed));
        check(io.mindspice.lyra.compiler.ir.IrValidator.isValid(ir),
                "captured lambda references remain valid after lowering");
        check(containsCaptureReference(ir.modules().getFirst().body()),
                "captured references use an explicit capture IR node");
    }

    @Test
    public void testValidatorRejectsIncompleteLinksAndArity() {
        TypedSemanticGraph typed = success("let value :I32 = 1");
        io.mindspice.lyra.compiler.source.SourceSpan span = typed.modules().getFirst().span();
        io.mindspice.lyra.compiler.ir.IrNode.Reference incomplete =
                new io.mindspice.lyra.compiler.ir.IrNode.Reference(
                        span, io.mindspice.lyra.compiler.types.PrimitiveType.I32,
                        java.util.Optional.empty(), java.util.Optional.empty(),
                        java.util.Optional.empty(),
                        io.mindspice.lyra.compiler.semantic.ReferenceKind.VALUE);
        io.mindspice.lyra.compiler.ir.IrNode.Sequence body =
                new io.mindspice.lyra.compiler.ir.IrNode.Sequence(
                        span, io.mindspice.lyra.compiler.types.PrimitiveType.UNIT,
                        List.of(incomplete));
        io.mindspice.lyra.compiler.ir.IrModule module = new io.mindspice.lyra.compiler.ir.IrModule(
                typed.modules().getFirst().moduleId(), typed.modules().getFirst().rootScope(), span, body);
        TypedIr invalid = new TypedIr(typed, List.of(module));
        check(io.mindspice.lyra.compiler.ir.IrValidator.validate(invalid).stream().anyMatch(diagnostic ->
                        diagnostic.code().value().equals("LYC-IR-003")),
                "validator rejects an IR reference without resolved identities");
    }

    @Test
    public void testValidatorRejectsOrderingAndUnrecordedConversions() {
        TypedSemanticGraph typed = success("let first :I64 = 1 let second :I64 = 2");
        TypedIr valid = phaseSuccess(TypedIrBuilder.lower(typed));
        io.mindspice.lyra.compiler.ir.IrModule original = valid.modules().getFirst();
        List<io.mindspice.lyra.compiler.ir.IrNode> reversed =
                new java.util.ArrayList<>(original.body().forms());
        java.util.Collections.reverse(reversed);
        io.mindspice.lyra.compiler.ir.IrModule reordered =
                new io.mindspice.lyra.compiler.ir.IrModule(
                        original.moduleId(), original.rootScope(), original.span(),
                        new io.mindspice.lyra.compiler.ir.IrNode.Sequence(
                                original.span(), io.mindspice.lyra.compiler.types.PrimitiveType.UNIT,
                                reversed));
        TypedIr invalidOrder = new TypedIr(typed, List.of(reordered));
        check(io.mindspice.lyra.compiler.ir.IrValidator.validate(invalidOrder).stream().anyMatch(
                        diagnostic -> diagnostic.code().value().equals("LYC-IR-006")),
                "validator rejects reordered strict evaluation");

        io.mindspice.lyra.compiler.ir.IrNode.Declaration declaration =
                (io.mindspice.lyra.compiler.ir.IrNode.Declaration) original.body().forms().getFirst();
        io.mindspice.lyra.compiler.ir.IrNode.Constant wrongType =
                new io.mindspice.lyra.compiler.ir.IrNode.Constant(
                        declaration.initializer().span(),
                        io.mindspice.lyra.compiler.types.PrimitiveType.I32,
                        new io.mindspice.lyra.compiler.ir.IrConstantValue.IntegerValue(
                                io.mindspice.lyra.compiler.types.ExactNumericLiteral.integer(
                                        java.math.BigInteger.ONE)));
        io.mindspice.lyra.compiler.ir.IrNode.Declaration incompleteConversion =
                new io.mindspice.lyra.compiler.ir.IrNode.Declaration(
                        declaration.span(), declaration.type(), declaration.declarationId(),
                        declaration.declarationKind(), declaration.contract(), wrongType);
        List<io.mindspice.lyra.compiler.ir.IrNode> altered =
                new java.util.ArrayList<>(original.body().forms());
        altered.set(0, incompleteConversion);
        TypedIr invalidConversion = new TypedIr(typed, List.of(new io.mindspice.lyra.compiler.ir.IrModule(
                original.moduleId(), original.rootScope(), original.span(),
                new io.mindspice.lyra.compiler.ir.IrNode.Sequence(
                        original.span(), io.mindspice.lyra.compiler.types.PrimitiveType.UNIT, altered))));
        check(io.mindspice.lyra.compiler.ir.IrValidator.validate(invalidConversion).stream().anyMatch(
                        diagnostic -> diagnostic.code().value().equals("LYC-IR-004")),
                "validator rejects an implicit conversion omitted from IR");
    }

    @Test
    public void testMutableUnaryMinusRecordsMutabilityDrop() {
        TypedSemanticGraph typed = success(
                "let @mut value :I32 = 1 let negated :I32 = (- value)");
        check(typed.conversions().stream().anyMatch(conversion -> conversion.step()
                        == io.mindspice.lyra.compiler.types.ConversionStep.MUTABILITY_DROP),
                "unary minus drops binding-local mutation permission explicitly");
        check(io.mindspice.lyra.compiler.ir.IrValidator.isValid(
                        phaseSuccess(TypedIrBuilder.lower(typed))),
                "mutable unary minus lowers to valid exact-type IR");
        check(io.mindspice.lyra.compiler.ir.IrValidator.isValid(
                        phaseSuccess(TypedIrBuilder.lower(success(
                                "let minimum :I8 = (- 128I8)")))),
                "signed-minimum magnitude remains valid only as unary-minus input");
    }

    @Test
    public void testValidatorRejectsMalformedConstants() {
        TypedIr integerBase = phaseSuccess(TypedIrBuilder.lower(success("let value :I8 = 1")));
        io.mindspice.lyra.compiler.ir.IrNode.Declaration integerDeclaration =
                firstDeclaration(integerBase);
        io.mindspice.lyra.compiler.ir.IrNode.Constant oversizedInteger =
                new io.mindspice.lyra.compiler.ir.IrNode.Constant(
                        integerDeclaration.initializer().span(),
                        io.mindspice.lyra.compiler.types.PrimitiveType.I8,
                        new io.mindspice.lyra.compiler.ir.IrConstantValue.IntegerValue(
                                io.mindspice.lyra.compiler.types.ExactNumericLiteral.integer(
                                        java.math.BigInteger.valueOf(300))));
        check(hasIrDiagnostic(withInitializer(integerBase, 0, oversizedInteger), "LYC-IR-008"),
                "validator rejects an out-of-range I8 constant");

        TypedIr decimalBase = phaseSuccess(TypedIrBuilder.lower(success("let value :F32 = 1.0F32")));
        io.mindspice.lyra.compiler.ir.IrNode.Declaration decimalDeclaration =
                firstDeclaration(decimalBase);
        io.mindspice.lyra.compiler.ir.IrNode.Constant oversizedDecimal =
                new io.mindspice.lyra.compiler.ir.IrNode.Constant(
                        decimalDeclaration.initializer().span(),
                        io.mindspice.lyra.compiler.types.PrimitiveType.F32,
                        new io.mindspice.lyra.compiler.ir.IrConstantValue.DecimalValue(
                                io.mindspice.lyra.compiler.types.ExactNumericLiteral.decimal(
                                        new java.math.BigDecimal("1e100"),
                                        io.mindspice.lyra.compiler.types.PrimitiveType.F32)));
        check(hasIrDiagnostic(withInitializer(decimalBase, 0, oversizedDecimal), "LYC-IR-008"),
                "validator rejects an out-of-range F32 decimal constant");
    }

    @Test
    public void testValidatorRejectsMalformedOperatorContracts() {
        TypedIr base = phaseSuccess(TypedIrBuilder.lower(success("let value :Bool = #T")));
        io.mindspice.lyra.compiler.source.SourceSpan span = firstDeclaration(base).initializer().span();
        io.mindspice.lyra.compiler.ir.IrNode.Constant i64 = integerConstant(
                span, io.mindspice.lyra.compiler.types.PrimitiveType.I64, 1);
        io.mindspice.lyra.compiler.ir.IrNode.Constant u64 = integerConstant(
                span, io.mindspice.lyra.compiler.types.PrimitiveType.U64, 1);
        io.mindspice.lyra.compiler.ir.IrNode.Operator relational =
                new io.mindspice.lyra.compiler.ir.IrNode.Operator(
                        span, io.mindspice.lyra.compiler.types.PrimitiveType.BOOL,
                        io.mindspice.lyra.compiler.lex.TokenKind.LESS, List.of(i64, u64));
        check(hasIrDiagnostic(withInitializer(base, 0, relational), "LYC-IR-008"),
                "validator rejects relational operands with no common numeric type");

        io.mindspice.lyra.compiler.ir.IrNode.Constant i32 = integerConstant(
                span, io.mindspice.lyra.compiler.types.PrimitiveType.I32, 1);
        io.mindspice.lyra.compiler.ir.IrNode.Constant string =
                new io.mindspice.lyra.compiler.ir.IrNode.Constant(
                        span, io.mindspice.lyra.compiler.types.PrimitiveType.STRING,
                        new io.mindspice.lyra.compiler.ir.IrConstantValue.StringValue("one"));
        io.mindspice.lyra.compiler.ir.IrNode.Operator equality =
                new io.mindspice.lyra.compiler.ir.IrNode.Operator(
                        span, io.mindspice.lyra.compiler.types.PrimitiveType.BOOL,
                        io.mindspice.lyra.compiler.lex.TokenKind.EQUAL_EQUAL, List.of(i32, string));
        check(hasIrDiagnostic(withInitializer(base, 0, equality), "LYC-IR-008"),
                "validator rejects value equality over incompatible scalar types");

        io.mindspice.lyra.compiler.ir.IrNode.ShortCircuit shortCircuit =
                new io.mindspice.lyra.compiler.ir.IrNode.ShortCircuit(
                        span, io.mindspice.lyra.compiler.types.PrimitiveType.BOOL,
                        io.mindspice.lyra.compiler.lex.TokenKind.XOR,
                        List.of(boolConstant(span, true), boolConstant(span, false)));
        check(hasIrDiagnostic(withInitializer(base, 0, shortCircuit), "LYC-IR-006"),
                "validator rejects an incompatible short-circuit operator");
    }

    @Test
    public void testValidatorRejectsUnrelatedPredicateBindingAndArithmeticCheck() {
        TypedIr predicateIr = phaseSuccess(TypedIrBuilder.lower(success(
                "let @nil maybe :I32 = #NIL let result = (maybe narrowed -> narrowed : 0)")));
        io.mindspice.lyra.compiler.ir.IrModule predicateModule = predicateIr.modules().getFirst();
        io.mindspice.lyra.compiler.ir.IrNode.Declaration maybe =
                (io.mindspice.lyra.compiler.ir.IrNode.Declaration) predicateModule.body().forms().getFirst();
        io.mindspice.lyra.compiler.ir.IrNode.Declaration result =
                (io.mindspice.lyra.compiler.ir.IrNode.Declaration) predicateModule.body().forms().get(1);
        io.mindspice.lyra.compiler.ir.IrNode.Branch branch =
                (io.mindspice.lyra.compiler.ir.IrNode.Branch) result.initializer();
        io.mindspice.lyra.compiler.ir.IrNode.Branch alteredBranch =
                new io.mindspice.lyra.compiler.ir.IrNode.Branch(
                        branch.span(), branch.type(), branch.predicate(), branch.thenBranch(),
                        branch.elseBranch(), maybe.declarationId());
        check(hasIrDiagnostic(withInitializer(predicateIr, 1, alteredBranch), "LYC-IR-003"),
                "validator rejects an unrelated declaration as a predicate binding");

        TypedIr boolIr = phaseSuccess(TypedIrBuilder.lower(success("let value :Bool = #T")));
        io.mindspice.lyra.compiler.source.SourceSpan span = firstDeclaration(boolIr).initializer().span();
        io.mindspice.lyra.compiler.ir.IrNode.Operator not =
                new io.mindspice.lyra.compiler.ir.IrNode.Operator(
                        span, io.mindspice.lyra.compiler.types.PrimitiveType.BOOL,
                        io.mindspice.lyra.compiler.lex.TokenKind.NOT,
                        List.of(boolConstant(span, true)));
        io.mindspice.lyra.compiler.ir.IrNode.RuntimeCheck invalidCheck =
                new io.mindspice.lyra.compiler.ir.IrNode.RuntimeCheck(
                        span, io.mindspice.lyra.compiler.types.PrimitiveType.BOOL,
                        io.mindspice.lyra.compiler.ir.IrCheckKind.ARITHMETIC,
                        "LYR-ARITH", not);
        check(hasIrDiagnostic(withInitializer(boolIr, 0, invalidCheck), "LYC-IR-008"),
                "arithmetic checks reject nonnumeric operators");

        TypedIr numericIr = phaseSuccess(TypedIrBuilder.lower(success("let value :I64 = 1")));
        io.mindspice.lyra.compiler.source.SourceSpan numericSpan =
                firstDeclaration(numericIr).initializer().span();
        io.mindspice.lyra.compiler.ir.IrNode.Operator plus =
                new io.mindspice.lyra.compiler.ir.IrNode.Operator(
                        numericSpan, io.mindspice.lyra.compiler.types.PrimitiveType.I64,
                        io.mindspice.lyra.compiler.lex.TokenKind.PLUS,
                        List.of(integerConstant(numericSpan,
                                        io.mindspice.lyra.compiler.types.PrimitiveType.I64, 1),
                                integerConstant(numericSpan,
                                        io.mindspice.lyra.compiler.types.PrimitiveType.I64, 2)));
        io.mindspice.lyra.compiler.ir.IrNode.RuntimeCheck wrongCategory =
                new io.mindspice.lyra.compiler.ir.IrNode.RuntimeCheck(
                        numericSpan, io.mindspice.lyra.compiler.types.PrimitiveType.I64,
                        io.mindspice.lyra.compiler.ir.IrCheckKind.ARITHMETIC,
                        "LYR-CONVERT", plus);
        check(hasIrDiagnostic(withInitializer(numericIr, 0, wrongCategory), "LYC-IR-008"),
                "arithmetic checks require the LYR-ARITH failure category");
    }

    @Test
    public void testNilCannotInventMutationAndEqualityUsesEveryNonNilOperand() {
        failure("let f :Fn<@mut @nil I32;Unit> = (=> |@mut @nil value :I32| ()) "
                + "let bad = (f #NIL)", "LYC-TYPE-001");

        TypedSemanticGraph typed = success(
                "let narrow = (== #NIL 1I8 2I16) "
                        + "let mixed = (== #NIL 1 2.0)");
        io.mindspice.lyra.compiler.types.LyraType nilI16 =
                io.mindspice.lyra.compiler.types.PrimitiveType.I16.nilable();
        io.mindspice.lyra.compiler.types.LyraType nilF64 =
                io.mindspice.lyra.compiler.types.PrimitiveType.F64.nilable();
        check(typed.expressions().stream().anyMatch(expression ->
                        expression.kind() == TypedExpressionKind.OPERATOR
                                && expression.operator().orElse("").equals("==")
                                && expression.children().size() == 3
                                && expression.children().stream().allMatch(child -> child.type().equals(nilI16))),
                "nil equality common-types all fixed integer operands before nil lifting");
        check(typed.expressions().stream().anyMatch(expression ->
                        expression.kind() == TypedExpressionKind.OPERATOR
                                && expression.operator().orElse("").equals("==")
                                && expression.children().size() == 3
                                && expression.children().stream().allMatch(child -> child.type().equals(nilF64))),
                "nil equality contextually types all exact mixed numeric operands before nil lifting");
        check(io.mindspice.lyra.compiler.ir.IrValidator.isValid(
                        phaseSuccess(TypedIrBuilder.lower(typed))),
                "variadic nil equality lowers to conversion-complete IR");
    }

    @Test
    public void testValidatorEnforcesShortCircuitAndIntegerDivisionContracts() {
        TypedIr boolBase = phaseSuccess(TypedIrBuilder.lower(success("let value :Bool = #T")));
        io.mindspice.lyra.compiler.source.SourceSpan boolSpan =
                firstDeclaration(boolBase).initializer().span();
        List<io.mindspice.lyra.compiler.ir.IrNode> truthOperands = List.of(
                boolConstant(boolSpan, true), boolConstant(boolSpan, false));
        io.mindspice.lyra.compiler.ir.IrNode.Operator eagerAnd =
                new io.mindspice.lyra.compiler.ir.IrNode.Operator(
                        boolSpan, io.mindspice.lyra.compiler.types.PrimitiveType.BOOL,
                        io.mindspice.lyra.compiler.lex.TokenKind.AND, truthOperands);
        io.mindspice.lyra.compiler.ir.IrNode.Operator eagerOr =
                new io.mindspice.lyra.compiler.ir.IrNode.Operator(
                        boolSpan, io.mindspice.lyra.compiler.types.PrimitiveType.BOOL,
                        io.mindspice.lyra.compiler.lex.TokenKind.OR, truthOperands);
        check(hasIrDiagnostic(withInitializer(boolBase, 0, eagerAnd), "LYC-IR-006"),
                "validator rejects eager and");
        check(hasIrDiagnostic(withInitializer(boolBase, 0, eagerOr), "LYC-IR-006"),
                "validator rejects eager or");
        check(io.mindspice.lyra.compiler.ir.IrValidator.isValid(
                        phaseSuccess(TypedIrBuilder.lower(success(
                                "let value :Bool = (xor #T #F)")))),
                "source-corresponding xor remains a legal eager truth operator");

        TypedIr divisionBase = phaseSuccess(TypedIrBuilder.lower(success(
                "let value :F64 = (/ 4I8 2I16)")));
        io.mindspice.lyra.compiler.source.SourceSpan divisionSpan =
                firstDeclaration(divisionBase).initializer().span();
        io.mindspice.lyra.compiler.ir.IrNode.Operator missingWidening =
                new io.mindspice.lyra.compiler.ir.IrNode.Operator(
                        divisionSpan, io.mindspice.lyra.compiler.types.PrimitiveType.F64,
                        io.mindspice.lyra.compiler.lex.TokenKind.SLASH,
                        List.of(integerConstant(divisionSpan,
                                        io.mindspice.lyra.compiler.types.PrimitiveType.I8, 4),
                                integerConstant(divisionSpan,
                                        io.mindspice.lyra.compiler.types.PrimitiveType.I16, 2)));
        check(hasIrDiagnostic(withInitializer(divisionBase, 0, missingWidening), "LYC-IR-004"),
                "integer division requires explicit IR widening to its common integer operand type");

        io.mindspice.lyra.compiler.ir.IrNode.Operator noCommonInteger =
                new io.mindspice.lyra.compiler.ir.IrNode.Operator(
                        divisionSpan, io.mindspice.lyra.compiler.types.PrimitiveType.F64,
                        io.mindspice.lyra.compiler.lex.TokenKind.SLASH,
                        List.of(integerConstant(divisionSpan,
                                        io.mindspice.lyra.compiler.types.PrimitiveType.I64, 4),
                                integerConstant(divisionSpan,
                                        io.mindspice.lyra.compiler.types.PrimitiveType.U64, 2)));
        check(hasIrDiagnostic(withInitializer(divisionBase, 0, noCommonInteger), "LYC-IR-008"),
                "integer division rejects operands with no lossless common integer type");
    }

    @Test
    public void testValidatorRequiresExactConversionEdges() {
        check(io.mindspice.lyra.compiler.ir.IrValidator.isValid(phaseSuccess(TypedIrBuilder.lower(success(
                        "let @mut source :I32 = 1 let converted :I64 = I64[source]")))),
                "explicit conversion drops binding-local mutation permission on its own adjacent edge");

        TypedIr nilBase = phaseSuccess(TypedIrBuilder.lower(success(
                "let @nil value :I64 = 1")));
        io.mindspice.lyra.compiler.source.SourceSpan nilSpan =
                firstDeclaration(nilBase).initializer().span();
        io.mindspice.lyra.compiler.ir.IrNode.Conversion skippedNilLift =
                new io.mindspice.lyra.compiler.ir.IrNode.Conversion(
                        nilSpan, io.mindspice.lyra.compiler.types.PrimitiveType.I64.nilable(),
                        io.mindspice.lyra.compiler.types.PrimitiveType.I8,
                        io.mindspice.lyra.compiler.types.ConversionKind.IMPLICIT,
                        io.mindspice.lyra.compiler.types.ConversionStep.NUMERIC_WIDENING,
                        integerConstant(nilSpan, io.mindspice.lyra.compiler.types.PrimitiveType.I8, 1));
        check(hasIrDiagnostic(withInitializer(nilBase, 0, skippedNilLift), "LYC-IR-004"),
                "one conversion node cannot skip nil lifting while widening numerically");

        TypedIr textBase = phaseSuccess(TypedIrBuilder.lower(success(
                "let value :String = \"one\"")));
        io.mindspice.lyra.compiler.source.SourceSpan textSpan =
                firstDeclaration(textBase).initializer().span();
        io.mindspice.lyra.compiler.ir.IrNode.Conversion wrongCategory =
                new io.mindspice.lyra.compiler.ir.IrNode.Conversion(
                        textSpan, io.mindspice.lyra.compiler.types.PrimitiveType.STRING,
                        io.mindspice.lyra.compiler.types.PrimitiveType.I32,
                        io.mindspice.lyra.compiler.types.ConversionKind.EXPLICIT,
                        io.mindspice.lyra.compiler.types.ConversionStep.NUMERIC_EXPLICIT,
                        integerConstant(textSpan, io.mindspice.lyra.compiler.types.PrimitiveType.I32, 1));
        check(hasIrDiagnostic(withInitializer(textBase, 0, wrongCategory), "LYC-IR-004"),
                "numeric explicit conversion labels cannot disguise text conversion edges");
    }

    @Test
    public void testValidatorEnforcesCallAndAccessShapeMatrix() {
        TypedIr local = phaseSuccess(TypedIrBuilder.lower(success(
                "let f :Fn<;I32> = (=> | | 1) let value = ::f[]")));
        io.mindspice.lyra.compiler.ir.IrNode.DirectCall localCall =
                (io.mindspice.lyra.compiler.ir.IrNode.DirectCall)
                        ((io.mindspice.lyra.compiler.ir.IrNode.Declaration)
                                local.modules().getFirst().body().forms().get(1)).initializer();
        io.mindspice.lyra.compiler.ir.IrNode.DirectCall falseNamespaceCall =
                new io.mindspice.lyra.compiler.ir.IrNode.DirectCall(
                        localCall.span(), localCall.type(), localCall.referenceId(),
                        localCall.targetDeclaration(), localCall.targetModule(), localCall.targetExport(),
                        java.util.Optional.of(io.mindspice.lyra.compiler.semantic.AccessKind.NAMESPACE_DIRECT_CALL),
                        java.util.Optional.empty(), localCall.arguments());
        check(hasIrDiagnostic(withInitializer(local, 1, falseNamespaceCall), "LYC-IR-008"),
                "local direct calls cannot masquerade as namespace calls");

        io.mindspice.lyra.compiler.ir.IrNode.DirectCall falseMemberCall =
                new io.mindspice.lyra.compiler.ir.IrNode.DirectCall(
                        localCall.span(), localCall.type(), localCall.referenceId(),
                        localCall.targetDeclaration(), localCall.targetModule(), localCall.targetExport(),
                        java.util.Optional.of(io.mindspice.lyra.compiler.semantic.AccessKind.MEMBER_CALL),
                        java.util.Optional.of(boolConstant(localCall.span(), true)), localCall.arguments());
        check(hasIrDiagnostic(withInitializer(local, 1, falseMemberCall), "LYC-IR-008"),
                "local direct-call references cannot masquerade as member calls");

        TypedIr member = phaseSuccess(TypedIrBuilder.lower(success(
                "let text :String = \"x\" let length = text:.length")));
        io.mindspice.lyra.compiler.ir.IrNode.Access memberAccess =
                (io.mindspice.lyra.compiler.ir.IrNode.Access)
                        ((io.mindspice.lyra.compiler.ir.IrNode.Declaration)
                                member.modules().getFirst().body().forms().get(1)).initializer();
        io.mindspice.lyra.compiler.ir.IrNode.Access falseNamespaceAccess =
                new io.mindspice.lyra.compiler.ir.IrNode.Access(
                        memberAccess.span(), memberAccess.type(),
                        io.mindspice.lyra.compiler.semantic.AccessKind.NAMESPACE_VALUE,
                        memberAccess.receiver(), memberAccess.referenceId(), memberAccess.declarationId(),
                        memberAccess.moduleId(), memberAccess.exportId(), memberAccess.memberName(),
                        memberAccess.tupleIndex());
        check(hasIrDiagnostic(withInitializer(member, 1, falseNamespaceAccess), "LYC-IR-008"),
                "member value access cannot masquerade as namespace value access");

        ModuleId mainId = ModuleId.path("matrix_main.lyra");
        ModuleId libraryId = ModuleId.path("matrix_library.lyra");
        io.mindspice.lyra.compiler.source.LogicalModuleId library =
                io.mindspice.lyra.compiler.source.LogicalModuleId.parse("matrix_library");
        ModuleGraph graph = CanonicalModuleGraph.create(
                mainId,
                List.of(
                        module(mainId, "import matrix_library "
                                + "let direct = matrix_library->::value[] "
                                + "let member = matrix_library->:.value"),
                        module(libraryId, "let @pub value :Fn<;I32> = (=> | | 1)")),
                List.of(new ModuleGraph.Edge(mainId, library, libraryId,
                        io.mindspice.lyra.compiler.source.SourceSpan.of(mainId.sourceId(), 7, 21))),
                Map.of(library, libraryId));
        PhaseResult<ResolvedSemanticGraph> resolved = SemanticResolver.resolve(graph);
        check(resolved instanceof PhaseResult.Success<?>, "shape-matrix namespace graph resolves");
        TypedSemanticGraph namespaceTyped = ((PhaseResult.Success<TypedSemanticGraph>) TypeChecker.check(
                ((PhaseResult.Success<ResolvedSemanticGraph>) resolved).value())).value();
        TypedIr namespace = phaseSuccess(TypedIrBuilder.lower(namespaceTyped));
        check(io.mindspice.lyra.compiler.ir.IrValidator.isValid(namespace),
                "well-formed namespace call and value access pass the shape matrix");
        io.mindspice.lyra.compiler.ir.IrModule mainModule = namespace.modules().stream()
                .filter(module -> module.moduleId().equals(mainId)).findFirst().orElseThrow();
        io.mindspice.lyra.compiler.ir.IrNode.DirectCall namespaceCall =
                (io.mindspice.lyra.compiler.ir.IrNode.DirectCall)
                        ((io.mindspice.lyra.compiler.ir.IrNode.Declaration)
                                mainModule.body().forms().getFirst()).initializer();
        io.mindspice.lyra.compiler.ir.IrNode.DirectCall malformedNamespaceCall =
                new io.mindspice.lyra.compiler.ir.IrNode.DirectCall(
                        namespaceCall.span(), namespaceCall.type(), namespaceCall.referenceId(),
                        namespaceCall.targetDeclaration(), namespaceCall.targetModule(), namespaceCall.targetExport(),
                        java.util.Optional.of(io.mindspice.lyra.compiler.semantic.AccessKind.MEMBER_CALL),
                        java.util.Optional.of(boolConstant(namespaceCall.span(), true)), namespaceCall.arguments());
        check(hasIrDiagnostic(withInitializer(namespace, 0, malformedNamespaceCall), "LYC-IR-008"),
                "namespace direct-call references reject member-call receiver combinations");
    }

    @Test
    public void testValidatorRequiresExactTypedSpansAndBlockScopes() {
        TypedIr arithmetic = phaseSuccess(TypedIrBuilder.lower(success(
                "let value :I64 = (+ 1 2)")));
        io.mindspice.lyra.compiler.ir.IrNode.Declaration declaration =
                firstDeclaration(arithmetic);
        io.mindspice.lyra.compiler.ir.IrNode.RuntimeCheck check =
                (io.mindspice.lyra.compiler.ir.IrNode.RuntimeCheck) declaration.initializer();
        io.mindspice.lyra.compiler.ir.IrNode.RuntimeCheck broadened =
                new io.mindspice.lyra.compiler.ir.IrNode.RuntimeCheck(
                        declaration.span(), check.type(), check.checkKind(),
                        check.failureCode(), check.operand());
        check(hasIrDiagnostic(withInitializer(arithmetic, 0, broadened), "LYC-IR-008"),
                "a contained but broadened IR span cannot replace the exact typed span");

        TypedIr blockIr = phaseSuccess(TypedIrBuilder.lower(success(
                "let value :I64 = { 1 }")));
        io.mindspice.lyra.compiler.ir.IrNode.Block block =
                (io.mindspice.lyra.compiler.ir.IrNode.Block) firstDeclaration(blockIr).initializer();
        io.mindspice.lyra.compiler.ir.IrNode.Block rootScopedBlock =
                new io.mindspice.lyra.compiler.ir.IrNode.Block(
                        block.span(), block.type(),
                        java.util.Optional.of(blockIr.modules().getFirst().rootScope()), block.forms());
        check(hasIrDiagnostic(withInitializer(blockIr, 0, rootScopedBlock), "LYC-IR-008"),
                "a block cannot reuse an existing module-root scope identity");
    }

    @Test
    public void testValidatorEnforcesMemberReceiverAndResultContracts() {
        TypedIr validMembers = phaseSuccess(TypedIrBuilder.lower(success(
                "let arrayLength :Fn<Array<I32>;I32> = (=> |value| value:.length) "
                        + "let tupleMember :Fn<Tuple<I32,String>;String> = (=> |value| value:.1)")));
        check(io.mindspice.lyra.compiler.ir.IrValidator.isValid(validMembers),
                "non-nil Array length and in-shape tuple members retain exact result types");
        io.mindspice.lyra.compiler.ir.IrNode.Lambda tupleLambda =
                (io.mindspice.lyra.compiler.ir.IrNode.Lambda)
                        ((io.mindspice.lyra.compiler.ir.IrNode.Declaration)
                                validMembers.modules().getFirst().body().forms().get(1)).initializer();
        io.mindspice.lyra.compiler.ir.IrNode.Access tupleAccess =
                (io.mindspice.lyra.compiler.ir.IrNode.Access) tupleLambda.body();
        io.mindspice.lyra.compiler.ir.IrNode.Access outOfShapeTupleAccess =
                new io.mindspice.lyra.compiler.ir.IrNode.Access(
                        tupleAccess.span(), tupleAccess.type(), tupleAccess.accessKind(),
                        tupleAccess.receiver(), tupleAccess.referenceId(), tupleAccess.declarationId(),
                        tupleAccess.moduleId(), tupleAccess.exportId(), java.util.Optional.empty(),
                        java.util.Optional.of(java.math.BigInteger.valueOf(2)));
        io.mindspice.lyra.compiler.ir.IrNode.Lambda malformedTupleLambda =
                new io.mindspice.lyra.compiler.ir.IrNode.Lambda(
                        tupleLambda.span(), tupleLambda.type(), tupleLambda.lambdaId(),
                        tupleLambda.signature(), tupleLambda.captures(), outOfShapeTupleAccess);
        check(hasIrDiagnostic(withInitializer(validMembers, 1, malformedTupleLambda), "LYC-IR-008"),
                "tuple member IR rejects an index outside its exact shape");
        failure("let bad = #T:.length", "LYC-TYPE-013");
        failure("let bad :Fn<Tuple<I32,String>;String> = (=> |value| value:.2)",
                "LYC-TYPE-013");

        TypedIr stringMember = phaseSuccess(TypedIrBuilder.lower(success(
                "let text :String = \"x\" let length = text:.length")));
        io.mindspice.lyra.compiler.ir.IrNode.Access access =
                (io.mindspice.lyra.compiler.ir.IrNode.Access)
                        ((io.mindspice.lyra.compiler.ir.IrNode.Declaration)
                                stringMember.modules().getFirst().body().forms().get(1)).initializer();
        io.mindspice.lyra.compiler.ir.IrNode.Access boolLength =
                new io.mindspice.lyra.compiler.ir.IrNode.Access(
                        access.span(), access.type(), access.accessKind(),
                        java.util.Optional.of(boolConstant(access.receiver().orElseThrow().span(), true)),
                        access.referenceId(), access.declarationId(), access.moduleId(), access.exportId(),
                        access.memberName(), access.tupleIndex());
        check(hasIrDiagnostic(withInitializer(stringMember, 1, boolLength), "LYC-IR-008"),
                "Bool.length is not a legal member-value IR operation");

        io.mindspice.lyra.compiler.ir.IrNode.Access wrongLengthType =
                new io.mindspice.lyra.compiler.ir.IrNode.Access(
                        access.span(), io.mindspice.lyra.compiler.types.PrimitiveType.BOOL,
                        access.accessKind(), access.receiver(), access.referenceId(),
                        access.declarationId(), access.moduleId(), access.exportId(),
                        access.memberName(), access.tupleIndex());
        check(hasIrDiagnostic(withInitializer(stringMember, 1, wrongLengthType), "LYC-IR-008"),
                "String.length cannot carry a non-I32 IR result type");
    }

    @Test
    public void testNilCompatibleFunctionEqualityLowersAndValidates() {
        TypedSemanticGraph typed = success(
                "let @nil function :Fn<;I32> = (=> | | 1) "
                        + "let absent = (== function #NIL) "
                        + "let direct = (!= (=> :I32 | | 1) #NIL)");
        List<io.mindspice.lyra.compiler.semantic.TypedExpression> equalities =
                typed.expressions().stream().filter(expression ->
                        expression.kind() == TypedExpressionKind.OPERATOR
                                && (expression.operator().orElse("").equals("==")
                                || expression.operator().orElse("").equals("!="))).toList();
        check(equalities.size() == 2 && equalities.stream().allMatch(expression ->
                        expression.children().stream().allMatch(child ->
                                child.type().isNilable()
                                        && child.type().withoutQualifiers()
                                        instanceof io.mindspice.lyra.compiler.types.FunctionType)),
                "function/#NIL equality retains one nilable function contract on every operand");
        check(io.mindspice.lyra.compiler.ir.IrValidator.isValid(
                        phaseSuccess(TypedIrBuilder.lower(typed))),
                "nil-compatible function value equality is valid closed IR");
        failure("let left :Fn<;I32> = (=> | | 1) "
                        + "let right :Fn<;I32> = (=> | | 2) let bad = (== left right)",
                "LYC-TYPE-008");
    }

    @Test
    public void testTypedGraphRejectsTamperedContextualCoalesce() {
        TypedSemanticGraph original = success("let result :@nil I32 = (#NIL : 0)");
        io.mindspice.lyra.compiler.semantic.TypedExpression declaration =
                original.modules().getFirst().forms().getFirst();
        io.mindspice.lyra.compiler.semantic.TypedExpression initializer =
                declaration.children().getFirst();
        check(initializer.kind() == TypedExpressionKind.CONVERSION
                        && initializer.children().getFirst().kind()
                        == TypedExpressionKind.COALESCE,
                "contextual coalesce fixture retains its outer nil lift");
        io.mindspice.lyra.compiler.semantic.TypedExpression coalesce =
                initializer.children().getFirst();
        io.mindspice.lyra.compiler.types.LyraType nilI16 =
                io.mindspice.lyra.compiler.types.PrimitiveType.I16.nilable();
        io.mindspice.lyra.compiler.semantic.TypedExpression forgedNil = copyTypedExpression(
                coalesce.children().getFirst(), nilI16, List.of());
        io.mindspice.lyra.compiler.semantic.TypedExpression forgedFallback = copyTypedExpression(
                coalesce.children().get(1),
                io.mindspice.lyra.compiler.types.PrimitiveType.I16, List.of());
        io.mindspice.lyra.compiler.semantic.TypedExpression forgedCoalesce = copyTypedExpression(
                coalesce, io.mindspice.lyra.compiler.types.PrimitiveType.I16,
                List.of(forgedNil, forgedFallback));

        io.mindspice.lyra.compiler.semantic.TypedConversion widening =
                new io.mindspice.lyra.compiler.semantic.TypedConversion(
                        coalesce.span(), io.mindspice.lyra.compiler.types.PrimitiveType.I16,
                        io.mindspice.lyra.compiler.types.PrimitiveType.I32,
                        io.mindspice.lyra.compiler.types.ConversionKind.IMPLICIT,
                        io.mindspice.lyra.compiler.types.ConversionStep.NUMERIC_WIDENING);
        io.mindspice.lyra.compiler.semantic.TypedExpression widened =
                new io.mindspice.lyra.compiler.semantic.TypedExpression(
                        TypedExpressionKind.CONVERSION, coalesce.span(),
                        io.mindspice.lyra.compiler.types.PrimitiveType.I32,
                        List.of(forgedCoalesce), java.util.Optional.empty(),
                        java.util.Optional.empty(), java.util.Optional.of(widening),
                        java.util.Optional.empty(), java.util.Optional.empty(),
                        java.util.Optional.empty(), java.util.Optional.empty(),
                        java.util.Optional.empty(), java.util.Optional.empty(),
                        java.util.Optional.empty(), List.of(), java.util.Optional.empty());
        io.mindspice.lyra.compiler.semantic.TypedConversion nilLift =
                new io.mindspice.lyra.compiler.semantic.TypedConversion(
                        coalesce.span(), io.mindspice.lyra.compiler.types.PrimitiveType.I32,
                        io.mindspice.lyra.compiler.types.PrimitiveType.I32.nilable(),
                        io.mindspice.lyra.compiler.types.ConversionKind.IMPLICIT,
                        io.mindspice.lyra.compiler.types.ConversionStep.NIL_LIFT);
        io.mindspice.lyra.compiler.semantic.TypedExpression forgedInitializer =
                new io.mindspice.lyra.compiler.semantic.TypedExpression(
                        TypedExpressionKind.CONVERSION, coalesce.span(),
                        io.mindspice.lyra.compiler.types.PrimitiveType.I32.nilable(),
                        List.of(widened), java.util.Optional.empty(), java.util.Optional.empty(),
                        java.util.Optional.of(nilLift), java.util.Optional.empty(),
                        java.util.Optional.empty(), java.util.Optional.empty(),
                        java.util.Optional.empty(), java.util.Optional.empty(),
                        java.util.Optional.empty(), java.util.Optional.empty(),
                        List.of(), java.util.Optional.empty());
        io.mindspice.lyra.compiler.semantic.TypedExpression forgedForm = copyTypedExpression(
                declaration, List.of(forgedInitializer));
        io.mindspice.lyra.compiler.identity.DeclarationId resultId =
                declaration.declarationId().orElseThrow();
        List<io.mindspice.lyra.compiler.semantic.TypedDeclaration> forgedDeclarations =
                original.declarations().stream().map(value -> value.id().equals(resultId)
                        ? new io.mindspice.lyra.compiler.semantic.TypedDeclaration(
                        value.id(), value.name(), value.span(), value.moduleId(), value.kind(),
                        value.contract(), java.util.Optional.of(forgedInitializer),
                        value.initializerLambda())
                        : value).toList();

        expectIllegalArgument(() -> rebuildTypedGraph(
                        original, List.of(forgedForm), forgedDeclarations,
                        original.references(), original.contractsByDeclaration()),
                "expected contextual type");
    }

    @Test
    public void testNegativeArityAndConversion() {
        failure("let f :Fn<I32;I32> = (=> |x| x) let bad = (f)", "LYC-TYPE-006");
        failure("let bad = String[#NIL]", "LYC-TYPE-005");
        failure("let bad = Bool[#T]", "LYC-TYPE-009");
        success("let minimum = I8[(- 128)] let rounded = F32[16777217]");
        failure("let bad = I8[300]", "LYC-TYPE-009");
        failure("let bad = I8[(- 129)]", "LYC-TYPE-009");
        failure("let @nil value :I32 = #NIL let bad = (+ value 1)", "LYC-TYPE-008");
        failure("let f :Fn<@mut I32;Unit> = (=> |@mut value :I32| ()) "
                + "let value :I32 = 0 let bad = (f value)", "LYC-TYPE-001");
    }

    private static TypedSemanticGraph success(String source) {
        PhaseResult<ResolvedSemanticGraph> resolved = SemanticResolver.resolve(singleGraph(source));
        if (!(resolved instanceof PhaseResult.Success<?> resolvedSuccess)) {
            throw new AssertionError("resolution failed: " + render(resolved));
        }
        PhaseResult<TypedSemanticGraph> typed = TypeChecker.check(
                ((PhaseResult.Success<ResolvedSemanticGraph>) resolvedSuccess).value());
        if (!(typed instanceof PhaseResult.Success<?> typedSuccess)) {
            throw new AssertionError("type checking failed: " + render(typed));
        }
        return ((PhaseResult.Success<TypedSemanticGraph>) typedSuccess).value();
    }

    private static TypedIr phaseSuccess(PhaseResult<TypedIr> result) {
        if (!(result instanceof PhaseResult.Success<?> success)) {
            throw new AssertionError("IR lowering failed: " + render(result));
        }
        return ((PhaseResult.Success<TypedIr>) success).value();
    }

    private static void failure(String source, String code) {
        failure(source, code, null);
    }

    private static void failure(String source, String code, String summaryFragment) {
        PhaseResult<ResolvedSemanticGraph> resolved = SemanticResolver.resolve(singleGraph(source));
        if (!(resolved instanceof PhaseResult.Success<?> resolvedSuccess)) {
            throw new AssertionError("resolution failed before type diagnostic: " + render(resolved));
        }
        PhaseResult<TypedSemanticGraph> typed = TypeChecker.check(
                ((PhaseResult.Success<ResolvedSemanticGraph>) resolvedSuccess).value());
        check(typed instanceof PhaseResult.Failure<?>, "expected type failure for " + source);
        check(typed.optionalValue().isEmpty(), "failed type checking publishes no partial graph");
        check(typed.diagnostics().getFirst().code().value().equals(code),
                "unexpected type diagnostic: " + render(typed));
        check(summaryFragment == null
                        || typed.diagnostics().getFirst().summary().contains(summaryFragment),
                "unexpected type diagnostic summary: " + render(typed));
    }

    private static ModuleGraph.Node module(ModuleId moduleId, String source) {
        SourceSnapshot snapshot = snapshotFor(moduleId, source);
        SyntaxProgram syntax = parse(snapshot);
        return new ModuleGraph.Node(
                moduleId,
                java.util.Optional.of(io.mindspice.lyra.compiler.source.LogicalModuleId
                        .fromSourceId(moduleId.sourceId())),
                snapshot,
                syntax,
                io.mindspice.lyra.compiler.source.ModuleRevision.compute(snapshot));
    }

    private static ModuleGraph singleGraph(String source) {
        ModuleId moduleId = ModuleId.path("type-test.lyra");
        SourceSnapshot snapshot = snapshotFor(moduleId, source);
        SyntaxProgram syntax = parse(snapshot);
        ModuleGraph.Node node = new ModuleGraph.Node(
                moduleId, java.util.Optional.empty(), snapshot, syntax,
                io.mindspice.lyra.compiler.source.ModuleRevision.compute(snapshot));
        return new ModuleGraph(moduleId, List.of(node), List.of(), Map.of());
    }

    private static SourceSnapshot snapshotFor(ModuleId moduleId, String source) {
        PhaseResult<SourceSnapshot> result = SourceSnapshot.capture(
                moduleId.sourceId(),
                PhysicalSourceKey.uri(URI.create("memory:" + moduleId.value())),
                source.getBytes(StandardCharsets.UTF_8));
        return ((PhaseResult.Success<SourceSnapshot>) result).value();
    }

    private static SyntaxProgram parse(SourceSnapshot snapshot) {
        LexedSource lexed = ((PhaseResult.Success<LexedSource>) Lexer.lex(snapshot)).value();
        GrammarProgram grammar = ((PhaseResult.Success<GrammarProgram>) GrammarMatcher.match(lexed)).value();
        return ((PhaseResult.Success<SyntaxProgram>) Parser.parse(lexed, grammar)).value();
    }

    private static String render(PhaseResult<?> result) {
        return result.diagnostics().stream().map(Diagnostic::render).toList().toString();
    }

    private static io.mindspice.lyra.compiler.ir.IrNode.Declaration firstDeclaration(TypedIr ir) {
        return (io.mindspice.lyra.compiler.ir.IrNode.Declaration)
                ir.modules().getFirst().body().forms().getFirst();
    }

    private static TypedSemanticGraph rebuildTypedGraph(
            TypedSemanticGraph original,
            io.mindspice.lyra.compiler.semantic.TypedExpression... forms) {
        io.mindspice.lyra.compiler.semantic.TypedModule module = original.modules().getFirst();
        io.mindspice.lyra.compiler.semantic.TypedModule alteredModule =
                new io.mindspice.lyra.compiler.semantic.TypedModule(
                        module.moduleId(), module.rootScope(), module.span(), List.of(forms[0]));
        List<io.mindspice.lyra.compiler.semantic.TypedDeclaration> declarations =
                new java.util.ArrayList<>(original.declarations());
        if (forms.length == 2 && forms[0].kind() == TypedExpressionKind.DECLARATION) {
            io.mindspice.lyra.compiler.semantic.TypedExpression declaration = forms[0];
            io.mindspice.lyra.compiler.identity.DeclarationId id =
                    declaration.declarationId().orElseThrow();
            io.mindspice.lyra.compiler.semantic.TypedDeclaration old =
                    original.declaration(id).orElseThrow();
            declarations.replaceAll(value -> value.id().equals(id)
                    ? new io.mindspice.lyra.compiler.semantic.TypedDeclaration(
                    old.id(), old.name(), old.span(), old.moduleId(), old.kind(), old.contract(),
                    java.util.Optional.of(forms[1]), old.initializerLambda())
                    : value);
        }
        List<io.mindspice.lyra.compiler.semantic.TypedExpression> expressions = List.of(forms);
        Map<io.mindspice.lyra.compiler.source.SourceSpan,
                List<io.mindspice.lyra.compiler.semantic.TypedExpression>> bySpan =
                new java.util.LinkedHashMap<>();
        for (io.mindspice.lyra.compiler.semantic.TypedExpression expression : expressions) {
            bySpan.computeIfAbsent(expression.span(), ignored -> new java.util.ArrayList<>())
                    .add(expression);
        }
        return SemanticTestSupport.seal(
                original, original.resolvedGraph(), List.of(alteredModule), declarations,
                original.references(), original.lambdas(), List.of(), expressions,
                original.contractsByDeclaration(), bySpan, original.mutations(),
                original.semanticFlowFacts(), original.initializationPlan(),
                io.mindspice.lyra.compiler.semantic.TypedFailureSite.fromExpressions(expressions));
    }

    private static TypedSemanticGraph rebuildTypedGraph(
            TypedSemanticGraph original,
            List<io.mindspice.lyra.compiler.semantic.TypedExpression> forms,
            List<io.mindspice.lyra.compiler.semantic.TypedDeclaration> declarations,
            List<io.mindspice.lyra.compiler.semantic.TypedReference> references,
            Map<io.mindspice.lyra.compiler.identity.DeclarationId,
                    io.mindspice.lyra.compiler.types.BindingContract> contracts) {
        io.mindspice.lyra.compiler.semantic.TypedModule module = original.modules().getFirst();
        io.mindspice.lyra.compiler.semantic.TypedModule alteredModule =
                new io.mindspice.lyra.compiler.semantic.TypedModule(
                        module.moduleId(), module.rootScope(), module.span(), forms);
        List<io.mindspice.lyra.compiler.semantic.TypedExpression> expressions =
                new java.util.ArrayList<>();
        List<io.mindspice.lyra.compiler.semantic.TypedConversion> conversions =
                new java.util.ArrayList<>();
        Map<io.mindspice.lyra.compiler.source.SourceSpan,
                List<io.mindspice.lyra.compiler.semantic.TypedExpression>> bySpan =
                new java.util.LinkedHashMap<>();
        for (io.mindspice.lyra.compiler.semantic.TypedExpression form : forms) {
            collectTypedExpressions(form, expressions, conversions, bySpan);
        }
        return SemanticTestSupport.seal(
                original, original.resolvedGraph(), List.of(alteredModule), declarations,
                references, original.lambdas(), conversions, expressions, contracts, bySpan,
                original.mutations(), original.semanticFlowFacts(), original.initializationPlan(),
                io.mindspice.lyra.compiler.semantic.TypedFailureSite.fromExpressions(expressions));
    }

    private static void collectTypedExpressions(
            io.mindspice.lyra.compiler.semantic.TypedExpression expression,
            List<io.mindspice.lyra.compiler.semantic.TypedExpression> expressions,
            List<io.mindspice.lyra.compiler.semantic.TypedConversion> conversions,
            Map<io.mindspice.lyra.compiler.source.SourceSpan,
                    List<io.mindspice.lyra.compiler.semantic.TypedExpression>> bySpan) {
        expressions.add(expression);
        expression.conversion().ifPresent(conversions::add);
        bySpan.computeIfAbsent(expression.span(), ignored -> new java.util.ArrayList<>())
                .add(expression);
        for (io.mindspice.lyra.compiler.semantic.TypedExpression child : expression.children()) {
            collectTypedExpressions(child, expressions, conversions, bySpan);
        }
    }

    private static io.mindspice.lyra.compiler.semantic.TypedExpression copyTypedExpression(
            io.mindspice.lyra.compiler.semantic.TypedExpression source,
            List<io.mindspice.lyra.compiler.semantic.TypedExpression> children) {
        return copyTypedExpression(source, source.type(), children);
    }

    private static io.mindspice.lyra.compiler.semantic.TypedExpression copyTypedExpression(
            io.mindspice.lyra.compiler.semantic.TypedExpression source,
            io.mindspice.lyra.compiler.types.LyraType type,
            List<io.mindspice.lyra.compiler.semantic.TypedExpression> children) {
        return new io.mindspice.lyra.compiler.semantic.TypedExpression(
                source.kind(), source.span(), type, children, source.literal(), source.link(),
                source.conversion(), source.operator(), source.memberName(), source.tupleIndex(),
                source.declarationId(), source.lambdaId(), source.scopeId(), source.signature(),
                source.captureIds(), source.predicateBinding());
    }

    private static TypedIr withInitializer(
            TypedIr ir, int declarationIndex, io.mindspice.lyra.compiler.ir.IrNode initializer) {
        io.mindspice.lyra.compiler.ir.IrModule original = ir.modules().getFirst();
        List<io.mindspice.lyra.compiler.ir.IrNode> forms =
                new java.util.ArrayList<>(original.body().forms());
        io.mindspice.lyra.compiler.ir.IrNode.Declaration declaration =
                (io.mindspice.lyra.compiler.ir.IrNode.Declaration) forms.get(declarationIndex);
        forms.set(declarationIndex, new io.mindspice.lyra.compiler.ir.IrNode.Declaration(
                declaration.span(), declaration.type(), declaration.declarationId(),
                declaration.declarationKind(), declaration.contract(), initializer));
        io.mindspice.lyra.compiler.ir.IrModule altered =
                new io.mindspice.lyra.compiler.ir.IrModule(
                        original.moduleId(), original.rootScope(), original.span(),
                        new io.mindspice.lyra.compiler.ir.IrNode.Sequence(
                                original.body().span(), original.body().type(), forms));
        List<io.mindspice.lyra.compiler.ir.IrModule> modules =
                new java.util.ArrayList<>(ir.modules());
        modules.set(0, altered);
        return new TypedIr(ir.semanticGraph(), modules);
    }

    private static io.mindspice.lyra.compiler.ir.IrNode.Constant integerConstant(
            io.mindspice.lyra.compiler.source.SourceSpan span,
            io.mindspice.lyra.compiler.types.PrimitiveType type,
            long value) {
        return new io.mindspice.lyra.compiler.ir.IrNode.Constant(
                span, type, new io.mindspice.lyra.compiler.ir.IrConstantValue.IntegerValue(
                io.mindspice.lyra.compiler.types.ExactNumericLiteral.integer(
                        java.math.BigInteger.valueOf(value))));
    }

    private static io.mindspice.lyra.compiler.ir.IrNode.Constant boolConstant(
            io.mindspice.lyra.compiler.source.SourceSpan span, boolean value) {
        return new io.mindspice.lyra.compiler.ir.IrNode.Constant(
                span, io.mindspice.lyra.compiler.types.PrimitiveType.BOOL,
                new io.mindspice.lyra.compiler.ir.IrConstantValue.BooleanValue(value));
    }

    private static boolean hasIrDiagnostic(TypedIr ir, String code) {
        return io.mindspice.lyra.compiler.ir.IrValidator.validate(ir).stream()
                .anyMatch(diagnostic -> diagnostic.code().value().equals(code));
    }

    private static boolean containsRuntimeCheck(io.mindspice.lyra.compiler.ir.IrNode node) {
        if (node instanceof io.mindspice.lyra.compiler.ir.IrNode.RuntimeCheck) {
            return true;
        }
        if (node instanceof io.mindspice.lyra.compiler.ir.IrNode.Sequence sequence) {
            return sequence.forms().stream().anyMatch(TypeCheckerTest::containsRuntimeCheck);
        }
        if (node instanceof io.mindspice.lyra.compiler.ir.IrNode.Declaration declaration) {
            return containsRuntimeCheck(declaration.initializer());
        }
        if (node instanceof io.mindspice.lyra.compiler.ir.IrNode.Block block) {
            return block.forms().stream().anyMatch(TypeCheckerTest::containsRuntimeCheck);
        }
        return false;
    }

    private static boolean containsCaptureReference(io.mindspice.lyra.compiler.ir.IrNode node) {
        if (node instanceof io.mindspice.lyra.compiler.ir.IrNode.CaptureReference) {
            return true;
        }
        if (node instanceof io.mindspice.lyra.compiler.ir.IrNode.Lambda lambda) {
            return containsCaptureReference(lambda.body());
        }
        if (node instanceof io.mindspice.lyra.compiler.ir.IrNode.Sequence sequence) {
            return sequence.forms().stream().anyMatch(TypeCheckerTest::containsCaptureReference);
        }
        if (node instanceof io.mindspice.lyra.compiler.ir.IrNode.Declaration declaration) {
            return containsCaptureReference(declaration.initializer());
        }
        if (node instanceof io.mindspice.lyra.compiler.ir.IrNode.Conversion conversion) {
            return containsCaptureReference(conversion.operand());
        }
        if (node instanceof io.mindspice.lyra.compiler.ir.IrNode.RuntimeCheck check) {
            return containsCaptureReference(check.operand());
        }
        return false;
    }

    private static void expectIllegalArgument(Runnable action) {
        expectIllegalArgument(action, null);
    }

    private static void expectIllegalArgument(Runnable action, String messageFragment) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            check(messageFragment == null || expected.getMessage().contains(messageFragment),
                    "unexpected invariant rejection: " + expected.getMessage());
            return;
        }
        throw new AssertionError("expected an invariant rejection");
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
