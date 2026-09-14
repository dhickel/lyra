package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.ast.SyntaxNode;
import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.diagnostic.RelatedSpan;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.lex.ModifierKind;
import io.mindspice.lyra.compiler.lex.TokenKind;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.NominalType;
import io.mindspice.lyra.compiler.types.RangeType;
import io.mindspice.lyra.compiler.types.BindingContract;
import io.mindspice.lyra.compiler.types.BindingMutability;
import io.mindspice.lyra.compiler.types.ConversionDecision;
import io.mindspice.lyra.compiler.types.ConversionKind;
import io.mindspice.lyra.compiler.types.ConversionStep;
import io.mindspice.lyra.compiler.types.ExactNumericLiteral;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.LyraSignature;
import io.mindspice.lyra.compiler.types.LyraType;
import io.mindspice.lyra.compiler.types.LiteralTyping;
import io.mindspice.lyra.compiler.types.PrimitiveType;
import io.mindspice.lyra.compiler.types.TupleType;
import io.mindspice.lyra.compiler.types.TypePosition;
import io.mindspice.lyra.compiler.types.TypeQualifier;
import io.mindspice.lyra.compiler.types.TypeRules;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Complete bidirectional checker for the current JVM-independent language
 * contract. Expected types flow into literals, lambdas, calls, aggregates,
 * branches, and conversions; the frozen graph retains every source operation.
 * The mutable maps in this class never cross the phase boundary.
 */
public final class TypeChecker {
    private static final List<PrimitiveType> NUMERIC_CANDIDATES = List.of(
            PrimitiveType.I8,
            PrimitiveType.U8,
            PrimitiveType.I16,
            PrimitiveType.U16,
            PrimitiveType.I32,
            PrimitiveType.U32,
            PrimitiveType.I64,
            PrimitiveType.U64,
            PrimitiveType.F32,
            PrimitiveType.F64);

    private final ResolvedSemanticGraph resolvedGraph;
    private final boolean attachableBoundary;

    public TypeChecker(ResolvedSemanticGraph resolvedGraph) {
        this(resolvedGraph, false);
    }

    public TypeChecker(ResolvedSemanticGraph resolvedGraph, boolean attachableBoundary) {
        this.resolvedGraph = Objects.requireNonNull(resolvedGraph, "resolvedGraph");
        this.attachableBoundary = attachableBoundary;
    }

    public static PhaseResult<TypedSemanticGraph> check(ResolvedSemanticGraph graph) {
        return new TypeChecker(graph).run();
    }

    /** Attachable compilations treat public @mut root reads as safe-point boundaries. */
    public static PhaseResult<TypedSemanticGraph> check(
            ResolvedSemanticGraph graph, boolean attachableBoundary) {
        return new TypeChecker(graph, attachableBoundary).run();
    }

    public static PhaseResult<TypedSemanticGraph> typeCheck(ResolvedSemanticGraph graph) {
        return check(graph);
    }

    public static PhaseResult<TypedSemanticGraph> process(ResolvedSemanticGraph graph) {
        return check(graph);
    }

    public static PhaseResult<TypedSemanticGraph> analyze(ResolvedSemanticGraph graph) {
        return check(graph);
    }

    /** Convenience pipeline entry point for callers holding only a resolved module graph. */
    public static PhaseResult<TypedSemanticGraph> check(ModuleGraph graph) {
        PhaseResult<ResolvedSemanticGraph> resolved = SemanticResolver.resolve(graph);
        if (resolved instanceof PhaseResult.Failure<ResolvedSemanticGraph>) {
            return PhaseResult.failure(resolved.diagnostics());
        }
        return check(resolved.optionalValue().orElseThrow());
    }

    public PhaseResult<TypedSemanticGraph> check() {
        return run();
    }

    public PhaseResult<TypedSemanticGraph> typeCheck() {
        return run();
    }

    public PhaseResult<TypedSemanticGraph> analyze() {
        return run();
    }

    private PhaseResult<TypedSemanticGraph> run() {
        resolvedGraph.validateSourceAuthority();
        State state = new State(resolvedGraph, attachableBoundary);
        state.initializeContracts();
        if (state.failed()) {
            return PhaseResult.failure(state.diagnostic());
        }
        state.checkModules();
        if (state.failed()) {
            return PhaseResult.failure(state.diagnostic());
        }
        TypedSemanticGraph typed = state.freeze();
        if (state.failed()) {
            return PhaseResult.failure(state.diagnostic());
        }
        return PhaseResult.success(typed);
    }

    private static final class State {
        private final boolean attachableBoundary;
        private final ResolvedSemanticGraph graph;
        private final Map<DeclarationId, ResolvedDeclaration> declarations = new LinkedHashMap<>();
        private final Map<ReferenceId, ResolvedReference> references = new LinkedHashMap<>();
        private final Map<LambdaId, ResolvedLambda> lambdas = new LinkedHashMap<>();
        private final Map<DeclarationId, LyraType> declarationTypes = new LinkedHashMap<>();
        private final Map<DeclarationId, BindingContract> typedContracts = new LinkedHashMap<>();
        private final Map<DeclarationId, TypedDeclaration> typedDeclarations = new LinkedHashMap<>();
        private final Map<DeclarationId, TypedExpression> initializers = new LinkedHashMap<>();
        private final Map<LambdaId, TypedLambda> typedLambdas = new LinkedHashMap<>();
        private final Map<ReferenceId, TypedReference> typedReferences = new LinkedHashMap<>();
        private final Map<ModuleId, TypedModule> typedModules = new LinkedHashMap<>();
        private Diagnostic diagnostic;
        /** Type mismatch deferred only long enough for canonical ownership precedence. */
        private Diagnostic deferredMutableArgumentDiagnostic;

        private State(ResolvedSemanticGraph graph) {
            this(graph, false);
        }

        private State(ResolvedSemanticGraph graph, boolean attachableBoundary) {
            this.graph = graph;
            this.attachableBoundary = attachableBoundary;
            for (ResolvedDeclaration declaration : graph.declarations()) {
                declarations.put(declaration.id(), declaration);
            }
            for (ResolvedReference reference : graph.references()) {
                references.put(reference.id(), reference);
            }
            for (ResolvedLambda lambda : graph.lambdas()) {
                lambdas.put(lambda.id(), lambda);
            }
        }

        private void initializeContracts() {
            for (ResolvedDeclaration declaration : graph.declarations()) {
                declaration.effectiveContract().ifPresent(contract -> {
                    typedContracts.put(declaration.id(), contract);
                    declarationTypes.put(declaration.id(), expressionType(declaration, contract.valueType()));
                });
            }
        }

        private ScopeId blockScopeAt(SourceSpan span) {
            return graph.scopeTree().scopes().stream()
                    .filter(scope -> scope.kind() == ScopeKind.BLOCK && scope.span().equals(span))
                    .map(ResolvedScope::id)
                    .findFirst()
                    .orElse(null);
        }

        private void checkModules() {
            for (ResolvedModule module : graph.modules()) {
                if (!graph.isRetained(module.moduleId())) continue;
                var producer = graph.retainedModules().module(module.moduleId()).orElseThrow().producerGraph();
                typedModules.put(module.moduleId(), producer.module(module.moduleId()).orElseThrow());
                for (var declaration : producer.declarations()) {
                    if (!declaration.moduleId().equals(module.moduleId())) continue;
                    typedDeclarations.put(declaration.id(), declaration);
                    declaration.initializer().ifPresent(value -> initializers.put(declaration.id(), value));
                    declaration.contract().ifPresent(contract -> {
                        typedContracts.put(declaration.id(), contract);
                        declarationTypes.put(declaration.id(), expressionType(
                                declarations.get(declaration.id()), contract.valueType()));
                    });
                }
                producer.references().stream().filter(value -> value.moduleId().equals(module.moduleId()))
                        .forEach(value -> typedReferences.put(value.id(), value));
                producer.lambdas().stream().filter(value -> value.moduleId().equals(module.moduleId()))
                        .forEach(value -> typedLambdas.put(value.id(), value));
            }
            for (ResolvedModule resolvedModule : graph.modules()) {
                ModuleId moduleId = resolvedModule.moduleId();
                if (graph.isRetained(moduleId)) continue;
                io.mindspice.lyra.compiler.ast.SyntaxProgram program = graph.moduleGraph()
                        .module(moduleId).orElseThrow().program();
                List<TypedExpression> forms = new ArrayList<>();
                for (SyntaxNode.ImportDeclaration ignored : program.imports()) {
                    // Imports have no executable value expression. Their typed declarations are
                    // frozen below from the resolved import contracts.
                }
                for (SyntaxNode.Form form : program.forms()) {
                    TypedExpression typed = checkForm(form, moduleId, Optional.empty());
                    if (typed == null) {
                        return;
                    }
                    forms.add(typed);
                }
                typedModules.put(moduleId, new TypedModule(
                        moduleId,
                        resolvedModule.rootScope(),
                        program.span(),
                        forms));
            }
        }

        private TypedExpression checkForm(
                SyntaxNode.Form form,
                ModuleId moduleId,
                Optional<LyraType> expected) {
            if (form instanceof SyntaxNode.LetBinding let) {
                return checkLet(let, moduleId);
            }
            if (form instanceof SyntaxNode.NominalDeclaration nominal) {
                return checkNominal(nominal, moduleId);
            }
            if (form instanceof SyntaxNode.Expression expression) {
                ExprResult result = checkExpression(expression, expected, moduleId);
                return result == null ? null : result.expression();
            }
            fail(CompilerDiagnosticCodes.TYPE_UNSUPPORTED_CONSTRUCT,
                    form.span(), "source form has no typed semantic representation");
            return null;
        }

        private TypedExpression checkLet(SyntaxNode.LetBinding syntax, ModuleId moduleId) {
            DeclarationId id = declarationIdAt(syntax.name().span(), DeclarationKind.LET);
            ResolvedDeclaration declaration = declarations.get(id);
            if (declaration == null) {
                fail(CompilerDiagnosticCodes.TYPE_UNRESOLVED_LINK,
                        syntax.name().span(), "declaration has no resolved identity");
                return null;
            }
            Optional<LyraType> expected = typedContracts.containsKey(id)
                    ? Optional.of(typedContracts.get(id).valueType())
                    : Optional.empty();
            ExprResult initializer = checkExpression(syntax.initializer(), expected, moduleId);
            if (initializer == null) {
                return null;
            }

            BindingContract contract = typedContracts.get(id);
            if (contract == null) {
                LyraType valueType = removeMutableQualifier(initializer.type());
                if (syntax.modifiers().stream().anyMatch(
                        modifier -> modifier.kind() == ModifierKind.NILABLE)) {
                    valueType = valueType.isNilable() ? valueType : valueType.nilable();
                }
                try {
                    contract = new BindingContract(valueType, declaration.bindingMutability());
                } catch (IllegalArgumentException failure) {
                    fail(CompilerDiagnosticCodes.TYPE_INVALID_BINDING,
                            syntax.name().span(), failure.getMessage());
                    return null;
                }
                typedContracts.put(id, contract);
                declarationTypes.put(id, expressionType(declaration, valueType));
            } else {
                declarationTypes.put(id, expressionType(declaration, contract.valueType()));
            }
            ExprResult typedInitializer = coerce(
                    initializer, contract.valueType(), syntax.initializer().span());
            if (typedInitializer == null) {
                return null;
            }
            initializers.put(id, typedInitializer.expression());
            TypedDeclaration typedDeclaration = new TypedDeclaration(
                    id,
                    declaration.name(),
                    declaration.span(),
                    moduleId,
                    declaration.kind(),
                    Optional.of(contract),
                    Optional.of(typedInitializer.expression()),
                    declaration.initializerLambda());
            typedDeclarations.put(id, typedDeclaration);
            return node(
                    TypedExpressionKind.DECLARATION,
                    syntax.span(),
                    PrimitiveType.UNIT,
                    List.of(typedInitializer.expression()),
                    Optional.empty(),
                    Optional.of(new TypedLink(
                            Optional.empty(), Optional.of(id), Optional.empty(), Optional.empty(), Optional.empty())),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(id), Optional.empty(), Optional.empty(), Optional.empty(),
                    List.of(), Optional.empty());
        }

        private TypedExpression checkNominal(SyntaxNode.NominalDeclaration syntax, ModuleId moduleId) {
            DeclarationId id = declarationIdAt(syntax.name().span(), DeclarationKind.NOMINAL);
            ResolvedNominal nominal = graph.nominals().stream().filter(value -> value.declaration().equals(id))
                    .findFirst().orElseThrow();
            List<TypedExpression> children = new ArrayList<>();
            for (int index = 0; index < syntax.members().size(); index++) {
                var member = syntax.members().get(index);
                if (member.initializer().isEmpty()) continue;
                DeclarationId memberId = nominal.members().get(index);
                ExprResult value = checkExpression(member.initializer().orElseThrow(),
                        Optional.of(nominal.schema().members().get(index).type()), moduleId);
                if (value == null) return null;
                initializers.put(memberId, value.expression());
                children.add(value.expression());
            }
            if (syntax.constructor().isPresent()) {
                ExprResult constructor = checkExpression(syntax.constructor().orElseThrow().initializer(),
                        Optional.of(FunctionType.of(nominal.schema().constructorParameters(), PrimitiveType.UNIT)), moduleId);
                if (constructor == null) return null;
                children.add(constructor.expression());
            }
            var proof = NominalInitializationProof.analyze(graph, nominal, children);
            if (proof instanceof PhaseResult.Failure<NominalInitializationProof>) {
                fail(proof.diagnostics().getFirst());
                return null;
            }
            return new TypedExpression(TypedExpressionKind.NOMINAL_DECLARATION, syntax.span(), PrimitiveType.UNIT,
                    children, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.of(id), Optional.empty(),
                    Optional.of(declarations.get(nominal.self()).scopeId()), Optional.empty(), List.of(), Optional.empty(),
                    Optional.empty(), proof.optionalValue());
        }

        private ExprResult checkExpression(
                SyntaxNode.Expression syntax,
                Optional<LyraType> expected,
                ModuleId moduleId) {
            ExprResult result;
            if (syntax instanceof SyntaxNode.Identifier identifier) {
                result = checkIdentifier(identifier, moduleId);
            } else if (syntax instanceof SyntaxNode.Literal literal) {
                result = checkLiteral(literal, expected, moduleId);
            } else if (syntax instanceof SyntaxNode.Block block) {
                result = checkBlock(block, expected, moduleId);
            } else if (syntax instanceof SyntaxNode.Conditional conditional) {
                result = checkConditional(conditional, expected, moduleId);
            } else if (syntax instanceof SyntaxNode.Coalesce coalesce) {
                result = checkCoalesce(coalesce, expected, moduleId);
            } else if (syntax instanceof SyntaxNode.Match match) {
                result = checkMatch(match, expected, moduleId);
            } else if (syntax instanceof SyntaxNode.Cond cond) {
                result = checkCond(cond, expected, moduleId);
            } else if (syntax instanceof SyntaxNode.ExplicitConstruction construction) {
                result = checkConstruction(
                        construction.namespacePath(), construction.typeName(),
                        construction.arguments().expressions(), construction.span(), moduleId);
            } else if (syntax instanceof SyntaxNode.Range range) {
                result = checkRange(range, expected, moduleId);
            } else if (syntax instanceof SyntaxNode.PrefixAssignment assignment) {
                result = checkRebinding(assignment.target(), assignment.value(), assignment.span(), moduleId);
            } else if (syntax instanceof SyntaxNode.Reassignment assignment) {
                result = checkRebinding(assignment.target(), assignment.value(), assignment.span(), moduleId);
            } else if (syntax instanceof SyntaxNode.Lambda lambda) {
                result = checkLambda(lambda, expected, moduleId);
            } else if (syntax instanceof SyntaxNode.CompactLambda lambda) {
                result = checkCompactLambda(lambda, expected, moduleId);
            } else if (syntax instanceof SyntaxNode.CallableCall call) {
                result = checkCallableCall(call, moduleId);
            } else if (syntax instanceof SyntaxNode.DirectCall call) {
                result = checkDirectCall(call, moduleId);
            } else if (syntax instanceof SyntaxNode.MemberAccess access) {
                result = checkMemberAccess(access, moduleId);
            } else if (syntax instanceof SyntaxNode.NamespaceMemberAccess access) {
                result = checkNamespaceMemberAccess(access, moduleId);
            } else if (syntax instanceof SyntaxNode.NamespaceDirectCall call) {
                result = checkNamespaceDirectCall(call, moduleId);
            } else if (syntax instanceof SyntaxNode.OperatorSExpression operator) {
                result = checkOperator(operator.operator(), operator.operands(), operator.span(), moduleId, expected);
            } else if (syntax instanceof SyntaxNode.OperatorBracket operator) {
                result = checkOperator(operator.operator(), operator.operands(), operator.span(), moduleId, expected);
            } else if (syntax instanceof SyntaxNode.TypeConversion conversion) {
                result = checkExplicitConversion(conversion, moduleId);
            } else if (syntax instanceof SyntaxNode.ArrayLiteral array) {
                result = checkArrayLiteral(array, expected, moduleId);
            } else if (syntax instanceof SyntaxNode.TupleLiteral tuple) {
                result = checkTupleLiteral(tuple, expected, moduleId);
            } else if (syntax instanceof SyntaxNode.IndexAccess index) {
                result = checkIndexAccess(index, moduleId);
            } else {
                fail(CompilerDiagnosticCodes.TYPE_UNSUPPORTED_CONSTRUCT,
                        syntax.span(), "source expression has no typed semantic representation");
                return null;
            }
            if (result == null) {
                return null;
            }
            return expected.isPresent()
                    ? coerce(result, expected.orElseThrow(), syntax.span())
                    : result;
        }

        private ExprResult checkLiteral(
                SyntaxNode.Literal literal,
                Optional<LyraType> expected,
                ModuleId moduleId) {
            if (literal instanceof SyntaxNode.BooleanLiteral booleanLiteral) {
                return finishLiteral(
                        literal,
                        PrimitiveType.BOOL,
                        new TypedLiteralValue.BooleanValue(booleanLiteral.value()));
            }
            if (literal instanceof SyntaxNode.NilLiteral) {
                if (expected.isEmpty() || !expected.orElseThrow().isNilable()) {
                    fail(CompilerDiagnosticCodes.TYPE_NIL_CONTEXT,
                            literal.span(), "#NIL requires an expected @nil value contract");
                    return null;
                }
                // Nil supplies absence under an existing nilable contract, but
                // it never supplies binding-local mutation permission.
                return finishLiteral(
                        literal,
                        removeMutableQualifier(expected.orElseThrow()),
                        TypedLiteralValue.NilValue.INSTANCE);
            }
            if (literal instanceof SyntaxNode.IntegerLiteral integer) {
                ExactNumericLiteral exact = ExactNumericLiteral.integer(integer.value(), integer.suffix());
                Optional<PrimitiveType> inferred = expected
                        .filter(State::isNumericType)
                        .flatMap(value -> LiteralTyping.infer(exact, value));
                if (expected.isEmpty() || inferred.isEmpty()) {
                    if (expected.isPresent() && isNumericType(expected.orElseThrow())) {
                        fail(CompilerDiagnosticCodes.TYPE_INVALID_LITERAL,
                                literal.span(), "integer literal is not exactly representable as the expected numeric type");
                        return null;
                    }
                    inferred = LiteralTyping.infer(exact);
                }
                if (inferred.isEmpty()) {
                    fail(CompilerDiagnosticCodes.TYPE_INVALID_LITERAL,
                            literal.span(), "integer literal is outside its exact Lyra type range");
                    return null;
                }
                ExprResult value = finishLiteral(
                        literal,
                        inferred.orElseThrow(),
                        new TypedLiteralValue.IntegerValue(exact));
                return expected.isPresent() ? coerce(value, expected.orElseThrow(), literal.span()) : value;
            }
            if (literal instanceof SyntaxNode.FloatLiteral decimal) {
                ExactNumericLiteral exact = ExactNumericLiteral.decimal(decimal.value(), decimal.suffix());
                Optional<PrimitiveType> inferred = expected
                        .filter(State::isNumericType)
                        .flatMap(value -> LiteralTyping.infer(exact, value));
                if (expected.isEmpty() || inferred.isEmpty()) {
                    if (expected.isPresent() && isNumericType(expected.orElseThrow())) {
                        fail(CompilerDiagnosticCodes.TYPE_INVALID_LITERAL,
                                literal.span(), "decimal literal is not exactly representable as the expected numeric type");
                        return null;
                    }
                    inferred = LiteralTyping.infer(exact);
                }
                if (inferred.isEmpty()) {
                    fail(CompilerDiagnosticCodes.TYPE_INVALID_LITERAL,
                            literal.span(), "decimal literal is outside its exact floating type range");
                    return null;
                }
                ExprResult value = finishLiteral(
                        literal,
                        inferred.orElseThrow(),
                        new TypedLiteralValue.DecimalValue(exact));
                return expected.isPresent() ? coerce(value, expected.orElseThrow(), literal.span()) : value;
            }
            if (literal instanceof SyntaxNode.StringLiteral string) {
                ExprResult value = finishLiteral(
                        literal,
                        PrimitiveType.STRING,
                        new TypedLiteralValue.StringValue(string.value()));
                return expected.isPresent() ? coerce(value, expected.orElseThrow(), literal.span()) : value;
            }
            if (literal instanceof SyntaxNode.CharacterLiteral character) {
                ExprResult value = finishLiteral(
                        literal,
                        PrimitiveType.CHAR,
                        new TypedLiteralValue.CharacterValue(character.value()));
                return expected.isPresent() ? coerce(value, expected.orElseThrow(), literal.span()) : value;
            }
            if (literal instanceof SyntaxNode.UnitLiteral unit) {
                ExprResult value = finishLiteral(
                        literal,
                        PrimitiveType.UNIT,
                        new TypedLiteralValue.UnitValue(unit.spelling()));
                return expected.isPresent() ? coerce(value, expected.orElseThrow(), literal.span()) : value;
            }
            fail(CompilerDiagnosticCodes.TYPE_INVALID_LITERAL,
                    literal.span(), "literal has no type in the current type regime");
            return null;
        }

        private ExprResult finishLiteral(
                SyntaxNode.Expression syntax,
                LyraType type,
                TypedLiteralValue literal) {
            return result(node(
                    TypedExpressionKind.LITERAL,
                    syntax.span(),
                    type,
                    List.of(),
                    Optional.of(literal),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(), Optional.empty()));
        }

        private ExprResult checkRange(SyntaxNode.Range syntax, Optional<LyraType> expected,
                                      ModuleId moduleId) {
            Optional<LyraType> elementExpected = expected.map(LyraType::withoutQualifiers)
                    .filter(RangeType.class::isInstance).map(type -> ((RangeType) type).elementType());
            NumericOperands numeric = numericOperands(List.of(syntax.start(), syntax.end(), syntax.step()),
                    elementExpected, TokenKind.PLUS, moduleId);
            if (numeric == null) {
                return null;
            }
            RangeType rangeType;
            try {
                rangeType = RangeType.of(numeric.commonType);
            } catch (IllegalArgumentException invalid) {
                fail(CompilerDiagnosticCodes.TYPE_MISMATCH, syntax.span(), invalid.getMessage());
                return null;
            }
            BigInteger step = constantIntegerValue(numeric.expressions.get(2));
            if (BigInteger.ZERO.equals(step)) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_BINDING, syntax.step().span(),
                        "a range step must not be zero");
                return null;
            }
            return result(node(TypedExpressionKind.RANGE, syntax.span(), rangeType,
                    numeric.expressions, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(syntax.inclusive() ? "..." : ".."), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    List.of(), Optional.empty()));
        }

        private ExprResult checkArrayLiteral(
                SyntaxNode.ArrayLiteral syntax,
                Optional<LyraType> expected,
                ModuleId moduleId) {
            LyraType expectedBase = expected.map(LyraType::withoutQualifiers).orElse(null);
            ArrayType explicit = null;
            if (syntax.explicitType().isPresent()) {
                LyraType resolved = typeFromSyntax(syntax.explicitType().orElseThrow(), TypePosition.BINDING);
                if (resolved == null || !(resolved.withoutQualifiers() instanceof ArrayType)) {
                    if (!failed()) {
                        fail(CompilerDiagnosticCodes.TYPE_INVALID_BINDING,
                                syntax.span(), "array literal prefix is not an Array type");
                    }
                    return null;
                }
                explicit = (ArrayType) resolved.withoutQualifiers();
            }
            if (expectedBase != null && !(expectedBase instanceof ArrayType)) {
                fail(CompilerDiagnosticCodes.TYPE_MISMATCH,
                        syntax.span(), "array literal requires an Array value contract");
                return null;
            }
            ArrayType target;
            if (explicit != null) {
                if (expectedBase instanceof ArrayType expectedArray
                        && !explicit.equals(expectedArray)) {
                    fail(CompilerDiagnosticCodes.TYPE_MISMATCH,
                            syntax.span(), "explicit array element type does not match its expected Array contract");
                    return null;
                }
                target = explicit;
            } else if (expectedBase instanceof ArrayType expectedArray) {
                target = expectedArray;
            } else {
                LyraType elementType = inferArrayElementType(syntax.elements(), moduleId);
                if (elementType == null) {
                    return null;
                }
                try {
                    target = ArrayType.of(elementType);
                } catch (IllegalArgumentException failure) {
                    fail(CompilerDiagnosticCodes.TYPE_INVALID_BINDING,
                            syntax.span(), failure.getMessage());
                    return null;
                }
            }

            List<TypedExpression> elements = new ArrayList<>();
            for (int index = 0; index < syntax.elements().size(); index++) {
                SyntaxNode.Expression element = syntax.elements().get(index);
                Optional<LyraType> elementExpected = StructuralContextPlan.expectedFor(
                        StructuralContextPlan.ChildPosition.arrayElement(index),
                        element, Optional.of(target), Optional.empty());
                ExprResult typed = checkExpression(
                        element, Optional.of(elementExpected.orElse(target.elementType())), moduleId);
                if (typed == null) {
                    return null;
                }
                elements.add(typed.expression());
            }
            return result(node(
                    TypedExpressionKind.ARRAY_LITERAL,
                    syntax.span(),
                    target,
                    elements,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), List.of(), Optional.empty()));
        }

        private ExprResult checkTupleLiteral(
                SyntaxNode.TupleLiteral syntax,
                Optional<LyraType> expected,
                ModuleId moduleId) {
            LyraType expectedBase = expected.map(LyraType::withoutQualifiers).orElse(null);
            TupleType explicit = null;
            if (syntax.explicitType().isPresent()) {
                LyraType resolved = typeFromSyntax(syntax.explicitType().orElseThrow(), TypePosition.BINDING);
                if (resolved == null || !(resolved.withoutQualifiers() instanceof TupleType)) {
                    if (!failed()) {
                        fail(CompilerDiagnosticCodes.TYPE_INVALID_BINDING,
                                syntax.span(), "tuple literal prefix is not a Tuple type");
                    }
                    return null;
                }
                explicit = (TupleType) resolved.withoutQualifiers();
            }
            if (syntax.elements().isEmpty()) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_LITERAL,
                        syntax.span(), "only bare Tuple[] is Unit; a typed tuple needs elements");
                return null;
            }
            if (expectedBase != null && !(expectedBase instanceof TupleType)) {
                fail(CompilerDiagnosticCodes.TYPE_MISMATCH,
                        syntax.span(), "tuple literal requires a Tuple value contract");
                return null;
            }
            TupleType target;
            if (explicit != null) {
                if (syntax.elements().size() != explicit.arity()) {
                    fail(CompilerDiagnosticCodes.TYPE_INVALID_ARITY,
                            syntax.span(), "tuple literal has " + syntax.elements().size()
                                    + " element(s), expected " + explicit.arity());
                    return null;
                }
                if (expectedBase instanceof TupleType expectedTuple
                        && !explicit.equals(expectedTuple)) {
                    fail(CompilerDiagnosticCodes.TYPE_MISMATCH,
                            syntax.span(), "explicit tuple shape does not match its expected Tuple contract");
                    return null;
                }
                target = explicit;
            } else if (expectedBase instanceof TupleType expectedTuple) {
                if (syntax.elements().size() != expectedTuple.arity()) {
                    fail(CompilerDiagnosticCodes.TYPE_INVALID_ARITY,
                            syntax.span(), "tuple literal has " + syntax.elements().size()
                                    + " element(s), expected " + expectedTuple.arity());
                    return null;
                }
                target = expectedTuple;
            } else {
                List<LyraType> members = new ArrayList<>();
                for (SyntaxNode.Expression element : syntax.elements()) {
                    Optional<LyraType> synthesized = synthesizeTypeWithoutContext(element, moduleId);
                    if (synthesized.isEmpty()) {
                        if (failed()) {
                            return null;
                        }
                        fail(CompilerDiagnosticCodes.TYPE_NIL_CONTEXT,
                                element.span(), "#NIL requires an expected tuple member contract");
                        return null;
                    }
                    members.add(removeMutableQualifier(synthesized.orElseThrow()));
                }
                try {
                    target = TupleType.of(members);
                } catch (IllegalArgumentException failure) {
                    fail(CompilerDiagnosticCodes.TYPE_INVALID_BINDING,
                            syntax.span(), failure.getMessage());
                    return null;
                }
            }

            List<TypedExpression> elements = new ArrayList<>();
            for (int index = 0; index < syntax.elements().size(); index++) {
                SyntaxNode.Expression element = syntax.elements().get(index);
                Optional<LyraType> memberExpected = StructuralContextPlan.expectedFor(
                        StructuralContextPlan.ChildPosition.tupleMember(index),
                        element, Optional.of(target), Optional.empty());
                ExprResult typed = checkExpression(
                        element, Optional.of(memberExpected.orElse(target.memberType(index))), moduleId);
                if (typed == null) {
                    return null;
                }
                elements.add(typed.expression());
            }
            return result(node(
                    TypedExpressionKind.TUPLE_LITERAL,
                    syntax.span(),
                    target,
                    elements,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), List.of(), Optional.empty()));
        }

        private ExprResult checkIndexAccess(
                SyntaxNode.IndexAccess syntax,
                ModuleId moduleId) {
            ExprResult receiver = checkExpression(syntax.receiver(), Optional.empty(), moduleId);
            if (receiver == null) {
                return null;
            }
            if (receiver.type().isNilable()) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_ACCESS,
                        syntax.receiver().span(), "nilable values must be narrowed before indexing");
                return null;
            }
            LyraType base = receiver.type().withoutQualifiers();
            LyraType elementType;
            if (base == PrimitiveType.STRING) {
                elementType = PrimitiveType.CHAR;
            } else if (base instanceof ArrayType array) {
                elementType = array.elementType();
            } else {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_ACCESS,
                        syntax.receiver().span(), "only String and Array values can be indexed");
                return null;
            }
            ExprResult index = checkExpression(syntax.index(), Optional.empty(), moduleId);
            if (index == null) {
                return null;
            }
            if (index.type().isNilable() || !index.type().isInteger()) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_ACCESS,
                        syntax.index().span(), "an index must be a non-nil integer");
                return null;
            }
            BigInteger constant = constantIntegerValue(index.expression());
            if (constant != null) {
                if (constant.signum() < 0) {
                    fail(CompilerDiagnosticCodes.TYPE_INVALID_ACCESS,
                            syntax.index().span(), "an index must not be negative");
                    return null;
                }
                if (constant.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                    fail(CompilerDiagnosticCodes.TYPE_INVALID_ACCESS,
                            syntax.index().span(), "index exceeds the maximum I32 collection position");
                    return null;
                }
            }
            return result(node(
                    TypedExpressionKind.INDEX_ACCESS,
                    syntax.span(),
                    elementType,
                    List.of(receiver.expression(), index.expression()),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), List.of(), Optional.empty()));
        }

        private Optional<ResolvedNominal> constructionAt(SourceSpan span) {
            return graph.syntaxLinks().stream().filter(link -> link.kind() == SyntaxLinkKind.CALL && link.span().equals(span))
                    .flatMap(link -> link.declarationId().stream())
                    .flatMap(id -> graph.nominals().stream().filter(value -> value.declaration().equals(id))).findFirst();
        }

        private ExprResult checkConstruction(
                Optional<SyntaxNode.NamespacePath> namespacePath,
                SyntaxNode.Identifier typeName,
                List<SyntaxNode.Expression> arguments,
                SourceSpan span,
                ModuleId moduleId) {
            ResolvedNominal nominal = constructionAt(span).orElse(null);
            if (nominal == null) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_ACCESS, span, "construction requires a declared type name");
                return null;
            }
            ResolvedReference reference;
            if (namespacePath.isPresent()) {
                typeNamespacePath(namespacePath.orElseThrow(), moduleId);
                reference = findReference(typeName.span(), typeName.name(), ReferenceKind.NAMESPACE_MEMBER);
            } else {
                reference = findReference(typeName.span(), typeName.name(), ReferenceKind.VALUE);
            }
            if (reference == null || typeReference(reference, moduleId) == null) return null;
            var signature = FunctionType.of(nominal.schema().constructorParameters(), nominal.schema().type()).signature();
            var values = checkArguments(arguments, signature, moduleId, span);
            if (values == null) return null;
            return result(node(TypedExpressionKind.CONSTRUCTION, span, nominal.schema().type(), values,
                    Optional.empty(), Optional.of(link(reference, Optional.empty())), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.of(nominal.declaration()), Optional.empty(), Optional.empty(),
                    Optional.of(signature), List.of(), Optional.empty()));
        }

        private LyraType inferArrayElementType(
                List<SyntaxNode.Expression> elements,
                ModuleId moduleId) {
            if (elements.isEmpty()) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_LITERAL,
                        graph.moduleGraph().module(moduleId).orElseThrow().program().span(),
                        "an untyped empty array has no element type; use Array<T>[]");
                return null;
            }
            ArrayShape shape = collectArrayShape(elements, moduleId);
            if (shape == null) {
                return null;
            }
            if (shape.literals().isEmpty() && shape.fixed().isEmpty()
                    && shape.nonNumeric().isEmpty()) {
                Optional<LyraType> folded = StructuralContextPlan.synthesizeArrayElements(
                        elements,
                        element -> synthesizeAtomicTypeWithoutContext(element, moduleId),
                        type -> Optional.ofNullable(typeFromSyntax(type, TypePosition.BINDING)),
                        expression -> synthesizeControlResult(expression, moduleId));
                if (folded.isPresent()) {
                    return folded.orElseThrow();
                }
                Optional<SourceSpan> nilSpan = elements.stream()
                        .map(StructuralContextPlan::firstContextFreeNilSpan)
                        .flatMap(Optional::stream)
                        .findFirst();
                fail(CompilerDiagnosticCodes.TYPE_NIL_CONTEXT,
                        nilSpan.orElse(shape.deferred().isEmpty()
                                ? elements.getFirst().span() : shape.deferred().getFirst().span()),
                        "an array of only #NIL values has no element base type");
                return null;
            }
            LyraType selected;
            if (shape.nonNumeric().isEmpty()) {
                List<PrimitiveType> candidates = numericCandidates(shape.literals(), shape.fixed());
                if (candidates.isEmpty()) {
                    fail(CompilerDiagnosticCodes.TYPE_NO_COMMON_NUMERIC_TYPE,
                            elements.getFirst().span(), "array elements have no losslessly common numeric type");
                    return null;
                }
                PrimitiveType defaultType = shape.decimal() ? PrimitiveType.F64 : PrimitiveType.I32;
                selected = shape.allDirect() && shape.allUnforced() && candidates.contains(defaultType)
                        ? defaultType : candidates.getFirst();
                if (shape.hasNil()) {
                    selected = selected.nilable();
                }
            } else {
                if (!shape.literals().isEmpty()) {
                    fail(CompilerDiagnosticCodes.TYPE_MISMATCH,
                            elements.getFirst().span(), "array elements mix numeric and non-numeric values");
                    return null;
                }
                Optional<LyraType> common = StructuralContextPlan.foldHomogeneous(shape.nonNumeric());
                if (common.isEmpty()) {
                    fail(CompilerDiagnosticCodes.TYPE_MISMATCH,
                            elements.getFirst().span(), "array elements do not have one invariant common type");
                    return null;
                }
                selected = removeMutableQualifier(common.orElseThrow());
                if (shape.hasNil() && !selected.isNilable()) {
                    selected = selected.nilable();
                }
            }

            for (SyntaxNode.Expression deferred : shape.deferred()) {
                Optional<LyraType> context = StructuralContextPlan.expectedFromPeer(deferred, selected);
                if (context.isEmpty()) {
                    fail(CompilerDiagnosticCodes.TYPE_NIL_CONTEXT,
                            deferred.span(), "#NIL requires an expected structural value contract");
                    return null;
                }
                selected = StructuralContextPlan.mergeNilShape(selected, context.orElseThrow());
            }
            return selected;
        }

        /** Collects source-derived non-nil peers before applying one array context. */
        private ArrayShape collectArrayShape(
                List<SyntaxNode.Expression> elements,
                ModuleId moduleId) {
            boolean hasNil = false;
            boolean allDirect = true;
            boolean allUnforced = true;
            boolean decimal = false;
            List<ExactNumericLiteral> literals = new ArrayList<>();
            List<PrimitiveType> fixed = new ArrayList<>();
            List<LyraType> nonNumeric = new ArrayList<>();
            List<SyntaxNode.Expression> deferred = new ArrayList<>();
            for (SyntaxNode.Expression element : elements) {
                ExactNumericLiteral literal = exactNumericLiteral(element);
                if (literal != null) {
                    literals.add(literal);
                    allUnforced &= literal.forcedType().isEmpty();
                    decimal |= literal.isDecimal();
                    continue;
                }
                Optional<LyraType> synthesized = synthesizeTypeWithoutContext(element, moduleId);
                if (synthesized.isEmpty()) {
                    if (failed()) {
                        return null;
                    }
                    if (StructuralContextPlan.containsContextFreeNil(element)) {
                        deferred.add(element);
                        hasNil |= StructuralContextPlan.isContextFreeNil(element);
                        continue;
                    }
                    // Let the ordinary checker own the diagnostic for a
                    // non-nil expression that cannot be synthesized here.
                    ExprResult value = checkExpression(element, Optional.empty(), moduleId);
                    if (value == null) {
                        return null;
                    }
                    synthesized = Optional.of(value.type());
                }
                allDirect = false;
                LyraType type = removeMutableQualifier(synthesized.orElseThrow());
                hasNil |= type.isNilable();
                if (isNumericType(type)) {
                    fixed.add((PrimitiveType) type.withoutQualifiers());
                } else {
                    nonNumeric.add(type);
                }
            }
            return new ArrayShape(
                    List.copyOf(literals), List.copyOf(fixed), List.copyOf(nonNumeric),
                    List.copyOf(deferred), hasNil, allDirect, allUnforced, decimal);
        }

        /** Context-free source synthesis used only to find an independent peer shape. */
        private Optional<LyraType> synthesizeTypeWithoutContext(
                SyntaxNode.Expression syntax,
                ModuleId moduleId) {
            if (syntax instanceof SyntaxNode.ArrayLiteral
                    || syntax instanceof SyntaxNode.TupleLiteral
                    || syntax instanceof SyntaxNode.Block) {
                Optional<LyraType> established = synthesizeAtomicTypeWithoutContext(
                        syntax, moduleId);
                if (established.isPresent()) {
                    return established;
                }
                if (failed()) {
                    return Optional.empty();
                }
            }
            if (syntax instanceof SyntaxNode.Conditional conditional
                    && conditional.elseExpression().isPresent()) {
                Optional<LyraType> conditionalShape = synthesizeStructuralConditional(
                        conditional, moduleId);
                if (conditionalShape.isPresent()) {
                    return conditionalShape;
                }
            }
            if (syntax instanceof SyntaxNode.Match match) {
                Optional<LyraType> matchShape = synthesizeStructuralMatch(match, moduleId);
                if (matchShape.isPresent()) {
                    return matchShape;
                }
            }
            if (syntax instanceof SyntaxNode.Cond cond) {
                Optional<LyraType> condShape = synthesizeStructuralCond(cond, moduleId);
                if (condShape.isPresent()) {
                    return condShape;
                }
            }
            return StructuralContextPlan.synthesize(
                    syntax,
                    atomicType -> synthesizeAtomicTypeWithoutContext(atomicType, moduleId),
                    type -> Optional.ofNullable(typeFromSyntax(type, TypePosition.BINDING)),
                    expression -> synthesizeControlResult(expression, moduleId));
        }

        private Optional<LyraType> synthesizeAtomicTypeWithoutContext(
                SyntaxNode.Expression syntax,
                ModuleId moduleId) {
            if (syntax instanceof SyntaxNode.NilLiteral) {
                return Optional.empty();
            }
            ExactNumericLiteral literal = exactNumericLiteral(syntax);
            if (literal != null) {
                return LiteralTyping.infer(literal).map(value -> (LyraType) value);
            }
            if (syntax instanceof SyntaxNode.BooleanLiteral) {
                return Optional.of(PrimitiveType.BOOL);
            }
            if (syntax instanceof SyntaxNode.StringLiteral) {
                return Optional.of(PrimitiveType.STRING);
            }
            if (syntax instanceof SyntaxNode.CharacterLiteral) {
                return Optional.of(PrimitiveType.CHAR);
            }
            if (syntax instanceof SyntaxNode.UnitLiteral) {
                return Optional.of(PrimitiveType.UNIT);
            }
            if (syntax instanceof SyntaxNode.Block block) {
                if (block.forms().isEmpty()) {
                    return Optional.of(PrimitiveType.UNIT);
                }
                SyntaxNode.Form finalForm = block.forms().getLast();
                return finalForm instanceof SyntaxNode.Expression expression
                        ? synthesizeTypeWithoutContext(expression, moduleId)
                        : Optional.of(PrimitiveType.UNIT);
            }
            if (syntax instanceof SyntaxNode.ArrayLiteral array) {
                if (array.explicitType().isPresent()) {
                    LyraType explicit = typeFromSyntax(
                            array.explicitType().orElseThrow(), TypePosition.BINDING);
                    return explicit == null
                            ? Optional.empty() : Optional.of(explicit.withoutQualifiers());
                }
                if (array.elements().isEmpty()) {
                    return Optional.empty();
                }
                ArrayShape shape = collectArrayShape(array.elements(), moduleId);
                if (shape == null || failed()) {
                    return Optional.empty();
                }
                if (shape.literals().isEmpty() && shape.fixed().isEmpty()
                        && shape.nonNumeric().isEmpty()) {
                    return Optional.empty();
                }
                LyraType selected;
                if (shape.nonNumeric().isEmpty()) {
                    List<PrimitiveType> candidates = numericCandidates(shape.literals(), shape.fixed());
                    if (candidates.isEmpty()) {
                        return Optional.empty();
                    }
                    PrimitiveType defaultType = shape.decimal() ? PrimitiveType.F64 : PrimitiveType.I32;
                    selected = shape.allDirect() && shape.allUnforced() && candidates.contains(defaultType)
                            ? defaultType : candidates.getFirst();
                    if (shape.hasNil()) {
                        selected = selected.nilable();
                    }
                } else {
                    if (!shape.literals().isEmpty()) {
                        return Optional.empty();
                    }
                    Optional<LyraType> common = StructuralContextPlan.foldHomogeneous(shape.nonNumeric());
                    if (common.isEmpty()) {
                        return Optional.empty();
                    }
                    selected = common.orElseThrow();
                    if (shape.hasNil() && !selected.isNilable()) {
                        selected = selected.nilable();
                    }
                }
                for (SyntaxNode.Expression deferred : shape.deferred()) {
                    Optional<LyraType> context = StructuralContextPlan.expectedFromPeer(deferred, selected);
                    if (context.isEmpty()) {
                        return Optional.empty();
                    }
                    selected = StructuralContextPlan.mergeNilShape(selected, context.orElseThrow());
                }
                return Optional.of(ArrayType.of(selected));
            }
            if (syntax instanceof SyntaxNode.TupleLiteral tuple) {
                if (tuple.elements().isEmpty()) {
                    return Optional.empty();
                }
                if (tuple.explicitType().isPresent()) {
                    LyraType explicit = typeFromSyntax(
                            tuple.explicitType().orElseThrow(), TypePosition.BINDING);
                    return explicit == null
                            ? Optional.empty() : Optional.of(explicit.withoutQualifiers());
                }
                List<LyraType> members = new ArrayList<>();
                for (SyntaxNode.Expression element : tuple.elements()) {
                    Optional<LyraType> member = synthesizeTypeWithoutContext(element, moduleId);
                    if (member.isEmpty()) {
                        return Optional.empty();
                    }
                    members.add(removeMutableQualifier(member.orElseThrow()));
                }
                return Optional.of(TupleType.of(members));
            }
            if (syntax instanceof SyntaxNode.Conditional conditional) {
                if (conditional.elseExpression().isEmpty()) {
                    return Optional.of(PrimitiveType.UNIT);
                }
                Optional<LyraType> conditionalShape = synthesizeStructuralConditional(
                        conditional, moduleId);
                if (conditionalShape.isPresent()) {
                    return conditionalShape;
                }
                Optional<LyraType> thenType = synthesizeTypeWithoutContext(
                        conditional.thenExpression(), moduleId);
                Optional<LyraType> elseType = synthesizeTypeWithoutContext(
                        conditional.elseExpression().orElseThrow(), moduleId);
                if (thenType.isEmpty() && elseType.isEmpty()) {
                    return Optional.empty();
                }
                if (thenType.isEmpty()) {
                    return StructuralContextPlan.expectedFromPeer(
                            conditional.thenExpression(), elseType.orElseThrow());
                }
                if (elseType.isEmpty()) {
                    return StructuralContextPlan.expectedFromPeer(
                            conditional.elseExpression().orElseThrow(), thenType.orElseThrow());
                }
                return commonSourceShape(thenType.orElseThrow(), elseType.orElseThrow());
            }
            if (syntax instanceof SyntaxNode.Match match) {
                return synthesizeStructuralMatch(match, moduleId);
            }
            if (syntax instanceof SyntaxNode.Cond cond) {
                return synthesizeStructuralCond(cond, moduleId);
            }
            if (syntax instanceof SyntaxNode.Coalesce coalesce) {
                // A value-side #NIL still needs an expected @nil context.  An
                // inferred coalesce therefore cannot manufacture that context
                // from its fallback.
                Optional<LyraType> value = synthesizeTypeWithoutContext(
                        coalesce.value(), moduleId);
                if (value.isEmpty() || !value.orElseThrow().isNilable()) {
                    return Optional.empty();
                }
                return Optional.of(value.orElseThrow().withoutQualifiers());
            }
            if (StructuralContextPlan.containsContextFreeNil(syntax)) {
                return Optional.empty();
            }
            ExprResult checked = checkExpression(syntax, Optional.empty(), moduleId);
            return checked == null ? Optional.empty() : Optional.of(checked.type());
        }

        private Optional<LyraType> synthesizeStructuralConditional(
                SyntaxNode.Conditional conditional,
                ModuleId moduleId) {
            if (conditional.elseExpression().isEmpty()) {
                return Optional.of(PrimitiveType.UNIT);
            }
            Optional<LyraType> thenType = synthesizeTypeWithoutContext(
                    conditional.thenExpression(), moduleId);
            Optional<LyraType> elseType = synthesizeTypeWithoutContext(
                    conditional.elseExpression().orElseThrow(), moduleId);
            if (thenType.isEmpty() && elseType.isEmpty()) {
                return Optional.empty();
            }
            if (thenType.isEmpty()) {
                return StructuralContextPlan.expectedFromPeer(
                        conditional.thenExpression(), elseType.orElseThrow());
            }
            if (elseType.isEmpty()) {
                return StructuralContextPlan.expectedFromPeer(
                        conditional.elseExpression().orElseThrow(), thenType.orElseThrow());
            }
            if (thenType.orElseThrow().isNumeric() && elseType.orElseThrow().isNumeric()
                    && !thenType.orElseThrow().isNilable()
                    && !elseType.orElseThrow().isNilable()) {
                return numericCommon(
                        List.of(conditional.thenExpression(),
                                conditional.elseExpression().orElseThrow()),
                        List.of(
                                new ExprResult(thenType.orElseThrow(), null),
                                new ExprResult(elseType.orElseThrow(), null)));
            }
            return StructuralContextPlan.foldHomogeneous(List.of(
                    removeMutableQualifier(thenType.orElseThrow()),
                    removeMutableQualifier(elseType.orElseThrow())));
        }

        private Optional<LyraType> synthesizeStructuralMatch(
                SyntaxNode.Match match,
                ModuleId moduleId) {
            return synthesizeStructuralArms(match.arms(), moduleId);
        }

        private Optional<LyraType> synthesizeStructuralCond(
                SyntaxNode.Cond cond,
                ModuleId moduleId) {
            return synthesizeStructuralArms(cond.arms(), moduleId);
        }

        private Optional<LyraType> synthesizeStructuralArms(
                List<SyntaxNode.MatchArm> arms,
                ModuleId moduleId) {
            List<SyntaxNode.Expression> results = arms.stream()
                    .map(SyntaxNode.MatchArm::result).toList();
            List<Optional<LyraType>> shapes = results.stream()
                    .map(result -> synthesizeTypeWithoutContext(result, moduleId)).toList();
            List<LyraType> known = shapes.stream().flatMap(Optional::stream)
                    .map(State::removeMutableQualifier).toList();
            if (known.isEmpty()) {
                return StructuralContextPlan.synthesizePeers(
                        results,
                        expression -> synthesizeAtomicTypeWithoutContext(expression, moduleId),
                        type -> Optional.ofNullable(typeFromSyntax(type, TypePosition.BINDING)),
                        expression -> synthesizeControlResult(expression, moduleId));
            }
            LyraType common;
            if (known.size() == results.size() && known.stream().allMatch(State::isNumericType)
                    && known.stream().noneMatch(LyraType::isNilable)) {
                List<ExprResult> values = known.stream()
                        .map(type -> new ExprResult(type, null)).toList();
                common = numericCommon(results, values).orElse(null);
            } else {
                common = StructuralContextPlan.foldHomogeneous(known).orElse(null);
            }
            if (common == null) {
                return Optional.empty();
            }
            for (int index = 0; index < results.size(); index++) {
                if (shapes.get(index).isEmpty()
                        || StructuralContextPlan.containsContextFreeNil(results.get(index))) {
                    Optional<LyraType> shaped = StructuralContextPlan.expectedFromPeer(
                            results.get(index), common);
                    if (shaped.isEmpty()) {
                        return Optional.empty();
                    }
                    common = StructuralContextPlan.mergeNilShape(common, shaped.orElseThrow());
                }
            }
            return Optional.of(common);
        }

        private Optional<LyraType> synthesizeNumericConditional(
                SyntaxNode.Conditional conditional,
                ModuleId moduleId) {
            Optional<LyraType> thenType = synthesizeTypeWithoutContext(
                    conditional.thenExpression(), moduleId);
            Optional<LyraType> elseType = synthesizeTypeWithoutContext(
                    conditional.elseExpression().orElseThrow(), moduleId);
            if (thenType.isEmpty() || elseType.isEmpty()
                    || !isNumericType(thenType.orElseThrow())
                    || !isNumericType(elseType.orElseThrow())
                    || thenType.orElseThrow().isNilable()
                    || elseType.orElseThrow().isNilable()) {
                return Optional.empty();
            }
            return numericCommon(
                    List.of(conditional.thenExpression(), conditional.elseExpression().orElseThrow()),
                    List.of(
                            new ExprResult(thenType.orElseThrow(), null),
                            new ExprResult(elseType.orElseThrow(), null)));
        }

        private Optional<LyraType> commonSourceShape(LyraType left, LyraType right) {
            return StructuralContextPlan.foldHomogeneous(List.of(
                    removeMutableQualifier(left), removeMutableQualifier(right)));
        }

        private record ArrayShape(
                List<ExactNumericLiteral> literals,
                List<PrimitiveType> fixed,
                List<LyraType> nonNumeric,
                List<SyntaxNode.Expression> deferred,
                boolean hasNil,
                boolean allDirect,
                boolean allUnforced,
                boolean decimal) {
        }

        private List<PrimitiveType> numericCandidates(
                List<ExactNumericLiteral> literals,
                List<PrimitiveType> fixed) {
            List<PrimitiveType> candidates = new ArrayList<>();
            for (PrimitiveType candidate : NUMERIC_CANDIDATES) {
                boolean fits = true;
                for (ExactNumericLiteral literal : literals) {
                    if (literal.forcedType().isPresent()) {
                        PrimitiveType forced = literal.forcedType().orElseThrow();
                        fits &= LiteralTyping.representableAs(literal, forced)
                                && TypeRules.canImplicitlyWiden(forced, candidate);
                    } else {
                        fits &= LiteralTyping.representableAs(literal, candidate);
                    }
                }
                for (PrimitiveType type : fixed) {
                    fits &= TypeRules.canImplicitlyWiden(type, candidate);
                }
                if (fits) {
                    candidates.add(candidate);
                }
            }
            return candidates;
        }

        private LyraType typeFromSyntax(SyntaxNode.Type syntax, TypePosition position) {
            try {
                if (syntax instanceof SyntaxNode.TypeContract contract) {
                    EnumSet<ModifierKind> seen = EnumSet.noneOf(ModifierKind.class);
                    LyraType base = typeFromSyntax(contract.baseType(), position);
                    if (base == null) {
                        return null;
                    }
                    for (SyntaxNode.Modifier modifier : contract.modifiers()) {
                        if (!seen.add(modifier.kind())) {
                            fail(CompilerDiagnosticCodes.TYPE_INVALID_BINDING,
                                    modifier.span(), "duplicate type modifier " + modifier.spelling());
                            return null;
                        }
                        if (modifier.kind() == ModifierKind.MUTABLE) {
                            if (!position.permitsMutableQualifier()) {
                                fail(CompilerDiagnosticCodes.TYPE_INVALID_BINDING,
                                        modifier.span(), "@mut is not legal in this nested type position");
                                return null;
                            }
                            base = base.mutable();
                        } else if (modifier.kind() == ModifierKind.NILABLE) {
                            if (base.isNilable()) {
                                fail(CompilerDiagnosticCodes.TYPE_INVALID_BINDING,
                                        modifier.span(), "@nil is specified more than once");
                                return null;
                            }
                            base = base.nilable();
                        } else {
                            fail(CompilerDiagnosticCodes.TYPE_INVALID_BINDING,
                                    modifier.span(), "unknown type modifier");
                            return null;
                        }
                    }
                    return base;
                }
                if (syntax instanceof SyntaxNode.PrimitiveType primitive) {
                    PrimitiveType value = PrimitiveType.fromSpelling(primitive.name()).orElse(null);
                    if (value == null) {
                        fail(CompilerDiagnosticCodes.TYPE_INVALID_BINDING,
                                primitive.span(), "unknown primitive type: " + primitive.name());
                    }
                    return value;
                }
                if (syntax instanceof SyntaxNode.NamedType named) {
                    return graph.syntaxLinks().stream().filter(link -> link.kind() == SyntaxLinkKind.TYPE
                                    && link.span().equals(named.span()) && link.declarationId().isPresent())
                            .map(link -> graph.declaration(link.declarationId().orElseThrow()).orElseThrow()
                                    .effectiveContract().orElseThrow().valueType())
                            .findFirst().orElseThrow(() -> new IllegalStateException("named type lacks exact resolved declaration"));
                }
                if (syntax instanceof SyntaxNode.ArrayType array) {
                    return ArrayType.of(typeFromSyntax(array.elementType(), TypePosition.NESTED_VALUE));
                }
                if (syntax instanceof SyntaxNode.RangeType range) {
                    LyraType element = typeFromSyntax(range.elementType(), TypePosition.NESTED_VALUE);
                    return element == null ? null : RangeType.of(element);
                }
                if (syntax instanceof SyntaxNode.TupleType tuple) {
                    List<LyraType> members = new ArrayList<>();
                    for (SyntaxNode.Type member : tuple.elementTypes()) {
                        LyraType value = typeFromSyntax(member, TypePosition.NESTED_VALUE);
                        if (value == null) {
                            return null;
                        }
                        members.add(value);
                    }
                    return TupleType.of(members);
                }
                if (syntax instanceof SyntaxNode.FunctionType function) {
                    List<LyraType> parameters = new ArrayList<>();
                    for (SyntaxNode.Type parameter : function.parameterTypes()) {
                        LyraType value = typeFromSyntax(parameter, TypePosition.PARAMETER);
                        if (value == null) {
                            return null;
                        }
                        parameters.add(value);
                    }
                    LyraType returnType = typeFromSyntax(function.returnType(), TypePosition.RETURN);
                    return returnType == null ? null : FunctionType.of(parameters, returnType);
                }
                fail(CompilerDiagnosticCodes.TYPE_INVALID_BINDING,
                        syntax.span(), "unknown syntax type");
                return null;
            } catch (IllegalArgumentException failure) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_BINDING,
                        syntax.span(), failure.getMessage());
                return null;
            }
        }

        private ExprResult checkIdentifier(SyntaxNode.Identifier identifier, ModuleId moduleId) {
            ResolvedReference reference = findReference(
                    identifier.span(), identifier.name(), ReferenceKind.VALUE);
            if (reference == null) {
                return null;
            }
            if (isNominalName(reference)) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_ACCESS, identifier.span(), "a nominal type name must be constructed with brackets");
                return null;
            }
            TypedReference typedReference = typeReference(reference, moduleId);
            if (typedReference == null || typedReference.type().isEmpty()) {
                return null;
            }
            LyraType type = typedReference.valueType();
            return result(node(
                    TypedExpressionKind.REFERENCE,
                    identifier.span(),
                    type,
                    List.of(),
                    Optional.empty(),
                    Optional.of(link(reference, Optional.empty())),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    reference.capture().map(List::of).orElseGet(List::of), Optional.empty()));
        }

        private ExprResult checkBlock(
                SyntaxNode.Block block,
                Optional<LyraType> expected,
                ModuleId moduleId) {
            ScopeId scopeId = blockScopeAt(block.span());
            if (scopeId == null) {
                fail(CompilerDiagnosticCodes.TYPE_UNRESOLVED_LINK,
                        block.span(), "block has no resolved lexical scope");
                return null;
            }
            List<TypedExpression> forms = new ArrayList<>();
            LyraType blockType = PrimitiveType.UNIT;
            for (int index = 0; index < block.forms().size(); index++) {
                SyntaxNode.Form form = block.forms().get(index);
                boolean finalExpression = index == block.forms().size() - 1
                        && form instanceof SyntaxNode.Expression;
                Optional<LyraType> finalExpected = finalExpression
                        ? StructuralContextPlan.expectedFor(
                        StructuralContextPlan.ChildPosition.blockFinal(),
                        (SyntaxNode.Expression) form, expected, Optional.empty())
                        : Optional.empty();
                TypedExpression typed = checkForm(
                        form,
                        moduleId,
                        finalExpected);
                if (typed == null) {
                    return null;
                }
                forms.add(typed);
                blockType = finalExpression ? typed.type() : PrimitiveType.UNIT;
            }
            return result(node(
                    TypedExpressionKind.BLOCK,
                    block.span(),
                    blockType,
                    forms,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(scopeId), Optional.empty(),
                    List.of(), Optional.empty()));
        }

        private ExprResult checkConditional(
                SyntaxNode.Conditional conditional,
                Optional<LyraType> expected,
                ModuleId moduleId) {
            ExprResult predicate = checkExpression(conditional.predicate(), Optional.empty(), moduleId);
            if (predicate == null) {
                return null;
            }
            if (!truthTestable(predicate.type())) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_TRUTH_TEST,
                        conditional.predicate().span(), "conditional predicate is not truth-testable");
                return null;
            }

            Optional<DeclarationId> predicateBinding = Optional.empty();
            Optional<LyraType> previousBindingType = Optional.empty();
            if (conditional.binding().isPresent()) {
                SyntaxNode.PredicateBinding syntaxBinding = conditional.binding().orElseThrow();
                DeclarationId bindingId = declarationIdAt(
                        syntaxBinding.name().span(), DeclarationKind.PREDICATE_BINDING);
                if (bindingId == null) {
                    fail(CompilerDiagnosticCodes.TYPE_UNRESOLVED_LINK,
                            syntaxBinding.span(), "predicate binding has no resolved identity");
                    return null;
                }
                predicateBinding = Optional.of(bindingId);
                LyraType narrowed = narrowedType(predicate.type());
                ResolvedDeclaration bindingDeclaration = declarations.get(bindingId);
                if (bindingDeclaration == null) {
                    fail(CompilerDiagnosticCodes.TYPE_UNRESOLVED_LINK,
                            syntaxBinding.span(), "predicate binding declaration is absent from the graph");
                    return null;
                }
                try {
                    typedContracts.put(bindingId, BindingContract.immutable(removeMutableQualifier(narrowed)));
                } catch (IllegalArgumentException failure) {
                    fail(CompilerDiagnosticCodes.TYPE_INVALID_BINDING,
                            syntaxBinding.span(), failure.getMessage());
                    return null;
                }
                previousBindingType = Optional.ofNullable(declarationTypes.put(
                        bindingId, removeMutableQualifier(narrowed)));
            }

            Optional<DeclarationId> bindingToRestore = predicateBinding;
            Optional<LyraType> previousTypeToRestore = previousBindingType;
            try {
                if (conditional.elseExpression().isEmpty()) {
                    // A then-only conditional is always Unit-valued.  Its
                    // branch is still checked independently so its effects and
                    // source-derived type are retained.
                    ExprResult thenBranch = checkExpression(
                            conditional.thenExpression(), Optional.empty(), moduleId);
                    if (thenBranch == null) {
                        return null;
                    }
                    return result(node(
                            TypedExpressionKind.CONDITIONAL,
                            conditional.span(),
                            PrimitiveType.UNIT,
                            List.of(predicate.expression(), thenBranch.expression()),
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                            List.of(), predicateBinding));
                }

                SyntaxNode.Expression thenSyntax = conditional.thenExpression();
                SyntaxNode.Expression elseSyntax = conditional.elseExpression().orElseThrow();
                if (expected.isPresent()) {
                    Optional<LyraType> thenExpected = StructuralContextPlan.expectedFor(
                            StructuralContextPlan.ChildPosition.conditionalThen(),
                            thenSyntax, expected, Optional.empty());
                    Optional<LyraType> elseExpected = StructuralContextPlan.expectedFor(
                            StructuralContextPlan.ChildPosition.conditionalElse(),
                            elseSyntax, expected, Optional.empty());
                    ExprResult thenBranch = checkExpression(
                            thenSyntax, thenExpected.or(() -> expected), moduleId);
                    if (thenBranch == null) {
                        return null;
                    }
                    ExprResult elseBranch = checkExpression(
                            elseSyntax, elseExpected.or(() -> expected), moduleId);
                    if (elseBranch == null) {
                        return null;
                    }
                    return result(node(
                            TypedExpressionKind.CONDITIONAL,
                            conditional.span(),
                            expected.orElseThrow(),
                            List.of(predicate.expression(), thenBranch.expression(), elseBranch.expression()),
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                            List.of(), predicateBinding));
                }

                // First synthesize only source-derived peers.  A structurally
                // nil branch is deliberately not checked without a contract;
                // the non-nil peer supplies its exact base and shape below.
                Optional<LyraType> thenShape = synthesizeTypeWithoutContext(thenSyntax, moduleId);
                if (failed()) {
                    return null;
                }
                Optional<LyraType> elseShape = synthesizeTypeWithoutContext(elseSyntax, moduleId);
                if (failed()) {
                    return null;
                }
                ExprResult thenInitial = thenShape.isPresent()
                        ? checkExpression(thenSyntax, Optional.empty(), moduleId) : null;
                if (failed()) {
                    return null;
                }
                ExprResult elseInitial = elseShape.isPresent()
                        ? checkExpression(elseSyntax, Optional.empty(), moduleId) : null;
                if (failed()) {
                    return null;
                }
                if (thenShape.isEmpty() && elseShape.isEmpty()) {
                    Optional<LyraType> folded = StructuralContextPlan.synthesizePeers(
                            List.of(thenSyntax, elseSyntax),
                            expression -> synthesizeAtomicTypeWithoutContext(expression, moduleId),
                            type -> Optional.ofNullable(typeFromSyntax(type, TypePosition.BINDING)),
                            candidate -> synthesizeControlResult(candidate, moduleId));
                    if (folded.isEmpty()) {
                        fail(CompilerDiagnosticCodes.TYPE_NIL_CONTEXT,
                                StructuralContextPlan.firstContextFreeNilSpan(thenSyntax)
                                        .orElse(thenSyntax.span()),
                                "two structurally nil branches do not provide a base value type");
                        return null;
                    }
                    ExprResult thenBranch = checkExpression(thenSyntax, Optional.of(folded.orElseThrow()), moduleId);
                    if (thenBranch == null) {
                        return null;
                    }
                    ExprResult elseBranch = checkExpression(elseSyntax, Optional.of(folded.orElseThrow()), moduleId);
                    if (elseBranch == null) {
                        return null;
                    }
                    return result(node(
                            TypedExpressionKind.CONDITIONAL,
                            conditional.span(),
                            folded.orElseThrow(),
                            List.of(predicate.expression(), thenBranch.expression(), elseBranch.expression()),
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                            List.of(), predicateBinding));
                }

                LyraType branchType;
                if (thenShape.isEmpty()) {
                    branchType = StructuralContextPlan.expectedFromPeer(
                            thenSyntax, elseShape.orElseThrow()).orElse(null);
                } else if (elseShape.isEmpty()) {
                    branchType = StructuralContextPlan.expectedFromPeer(
                            elseSyntax, thenShape.orElseThrow()).orElse(null);
                } else if (thenInitial != null && elseInitial != null
                        && !StructuralContextPlan.containsContextFreeNil(thenSyntax)
                        && !StructuralContextPlan.containsContextFreeNil(elseSyntax)) {
                    branchType = numericCommon(
                            List.of(thenSyntax, elseSyntax),
                            List.of(thenInitial, elseInitial))
                            .orElseGet(() -> StructuralContextPlan.foldHomogeneous(
                                    List.of(thenShape.orElseThrow(), elseShape.orElseThrow()))
                                    .orElse(null));
                } else {
                    branchType = StructuralContextPlan.foldHomogeneous(
                            List.of(thenShape.orElseThrow(), elseShape.orElseThrow()))
                            .orElse(null);
                    if (branchType != null) {
                        if (StructuralContextPlan.containsContextFreeNil(thenSyntax)) {
                            branchType = StructuralContextPlan.mergeNilShape(
                                    branchType,
                                    StructuralContextPlan.expectedFromPeer(
                                            thenSyntax, branchType).orElse(branchType));
                        }
                        if (StructuralContextPlan.containsContextFreeNil(elseSyntax)) {
                            branchType = StructuralContextPlan.mergeNilShape(
                                    branchType,
                                    StructuralContextPlan.expectedFromPeer(
                                            elseSyntax, branchType).orElse(branchType));
                        }
                    }
                }
                if (branchType == null) {
                    fail(CompilerDiagnosticCodes.TYPE_INVALID_BRANCH,
                            thenSyntax.span(),
                            "conditional branches have no exact or losslessly widened common type");
                    return null;
                }
                ExprResult thenBranch = thenInitial == null
                        ? checkExpression(thenSyntax, Optional.of(branchType), moduleId)
                        : contextualBranch(thenSyntax, thenInitial, branchType, moduleId);
                if (thenBranch == null) {
                    return null;
                }
                ExprResult elseBranch = elseInitial == null
                        ? checkExpression(elseSyntax, Optional.of(branchType), moduleId)
                        : contextualBranch(elseSyntax, elseInitial, branchType, moduleId);
                if (elseBranch == null) {
                    return null;
                }
                return result(node(
                        TypedExpressionKind.CONDITIONAL,
                        conditional.span(),
                        branchType,
                        List.of(predicate.expression(), thenBranch.expression(), elseBranch.expression()),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        List.of(), predicateBinding));
            } finally {
                if (bindingToRestore.isPresent()) {
                    DeclarationId binding = bindingToRestore.orElseThrow();
                    if (previousTypeToRestore.isPresent()) {
                        declarationTypes.put(binding, previousTypeToRestore.orElseThrow());
                    } else {
                        declarationTypes.remove(binding);
                    }
                }
            }
        }

        private ExprResult checkMatch(
                SyntaxNode.Match match,
                Optional<LyraType> expected,
                ModuleId moduleId) {
            ExprResult subject = checkExpression(match.subject(), Optional.empty(), moduleId);
            if (subject == null) {
                return null;
            }
            return checkArms(
                    match.subject(), subject, match.arms(), false, expected, moduleId, match.span());
        }

        private ExprResult checkCond(
                SyntaxNode.Cond cond,
                Optional<LyraType> expected,
                ModuleId moduleId) {
            return checkArms(
                    null, null, cond.arms(), true, expected, moduleId, cond.span());
        }

        private ExprResult checkArms(
                SyntaxNode.Expression subjectSyntax,
                ExprResult subject,
                List<SyntaxNode.MatchArm> arms,
                boolean conditional,
                Optional<LyraType> expected,
                ModuleId moduleId,
                SourceSpan span) {
            List<ExprResult> patterns = new ArrayList<>();
            List<ExprResult> guards = new ArrayList<>();
            List<Optional<LyraType>> comparisonTypes = new ArrayList<>();
            for (SyntaxNode.MatchArm arm : arms) {
                ExprResult pattern = null;
                Optional<LyraType> comparisonType = Optional.empty();
                if (arm.pattern().isPresent()) {
                    if (conditional) {
                        pattern = checkExpression(arm.pattern().orElseThrow(), Optional.empty(), moduleId);
                        if (pattern == null) {
                            return null;
                        }
                        if (!truthTestable(pattern.type())) {
                            fail(CompilerDiagnosticCodes.TYPE_INVALID_TRUTH_TEST,
                                    arm.pattern().orElseThrow().span(),
                                    "cond condition is not truth-testable");
                            return null;
                        }
                    } else {
                        PatternTyping typed = checkMatchPattern(
                                subjectSyntax, subject,
                                arm.pattern().orElseThrow(), moduleId);
                        if (typed == null) {
                            return null;
                        }
                        pattern = typed.pattern();
                        comparisonType = Optional.of(typed.comparisonType());
                    }
                }
                ExprResult guard = null;
                if (arm.guard().isPresent()) {
                    guard = checkExpression(arm.guard().orElseThrow(), Optional.empty(), moduleId);
                    if (guard == null) {
                        return null;
                    }
                    if (!truthTestable(guard.type())) {
                        fail(CompilerDiagnosticCodes.TYPE_INVALID_TRUTH_TEST,
                                arm.guard().orElseThrow().span(),
                                "match guard is not truth-testable");
                        return null;
                    }
                }
                patterns.add(pattern);
                guards.add(guard);
                comparisonTypes.add(comparisonType);
            }

            MatchResults results = checkMatchResults(arms, span, expected, moduleId);
            if (results == null) {
                return null;
            }
            List<TypedExpression> children = new ArrayList<>();
            OptionalInt subjectIndex = OptionalInt.empty();
            if (subject != null) {
                subjectIndex = OptionalInt.of(children.size());
                children.add(subject.expression());
            }
            List<TypedMatch.Arm> typedArms = new ArrayList<>();
            for (int index = 0; index < arms.size(); index++) {
                SyntaxNode.MatchArm arm = arms.get(index);
                OptionalInt patternIndex = OptionalInt.empty();
                if (patterns.get(index) != null) {
                    patternIndex = OptionalInt.of(children.size());
                    children.add(patterns.get(index).expression());
                }
                OptionalInt guardIndex = OptionalInt.empty();
                if (guards.get(index) != null) {
                    guardIndex = OptionalInt.of(children.size());
                    children.add(guards.get(index).expression());
                }
                int resultIndex = children.size();
                children.add(results.values().get(index).expression());
                typedArms.add(new TypedMatch.Arm(
                        arm.span(), arm.wildcard(), patternIndex, guardIndex,
                        resultIndex, comparisonTypes.get(index)));
            }
            TypedMatch metadata = new TypedMatch(
                    conditional ? TypedMatch.MatchMode.CONDITIONAL
                            : TypedMatch.MatchMode.TRADITIONAL,
                    subjectIndex, typedArms);
            return result(matchNode(
                    conditional ? TypedExpressionKind.COND : TypedExpressionKind.MATCH,
                    span, results.type(), children, metadata));
        }

        private PatternTyping checkMatchPattern(
                SyntaxNode.Expression subjectSyntax,
                ExprResult subject,
                SyntaxNode.Expression patternSyntax,
                ModuleId moduleId) {
            Optional<LyraType> shape = synthesizeTypeWithoutContext(patternSyntax, moduleId);
            if (failed()) {
                return null;
            }
            ExprResult pattern;
            if (shape.isEmpty()) {
                if (!subject.type().isNilable()
                        || !StructuralContextPlan.isContextFreeNil(patternSyntax)) {
                    fail(CompilerDiagnosticCodes.TYPE_NIL_CONTEXT, patternSyntax.span(),
                            "match #NIL pattern needs a nilable subject contract");
                    return null;
                }
                pattern = checkExpression(patternSyntax,
                        Optional.of(removeMutableQualifier(subject.type())), moduleId);
            } else {
                pattern = checkExpression(patternSyntax, Optional.empty(), moduleId);
            }
            if (pattern == null) {
                return null;
            }
            boolean nilPattern = StructuralContextPlan.isContextFreeNil(patternSyntax);
            if (subject.type().isNilable() || pattern.type().isNilable()) {
                if (!subject.type().isNilable() || !nilPattern) {
                    fail(CompilerDiagnosticCodes.TYPE_NIL_CONTEXT, patternSyntax.span(),
                            "nilable match subjects may only use #NIL equality before narrowing");
                    return null;
                }
                LyraType comparison = removeMutableQualifier(subject.type());
                ExprResult converted = contextualBranch(
                        patternSyntax, pattern, comparison, moduleId);
                return converted == null ? null : new PatternTyping(converted, comparison);
            }
            if (subject.type().withoutQualifiers() instanceof FunctionType
                    || pattern.type().withoutQualifiers() instanceof FunctionType) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_OPERATOR, patternSyntax.span(),
                        "function values require identity equality rather than match value equality");
                return null;
            }
            LyraType common;
            if (isNumericType(subject.type()) && isNumericType(pattern.type())) {
                common = matchNumericCommon(subject, patternSyntax, pattern).orElse(null);
                if (common == null) {
                    fail(CompilerDiagnosticCodes.TYPE_NO_COMMON_NUMERIC_TYPE, patternSyntax.span(),
                            "match subject and pattern have no losslessly common numeric type");
                    return null;
                }
            } else if (isNumericType(subject.type()) || isNumericType(pattern.type())) {
                fail(CompilerDiagnosticCodes.TYPE_MISMATCH, patternSyntax.span(),
                        "match subject and pattern do not have compatible equality types");
                return null;
            } else {
                common = StructuralContextPlan.foldHomogeneous(List.of(
                        removeMutableQualifier(subject.type()),
                        removeMutableQualifier(pattern.type()))).orElse(null);
                if (common == null) {
                    fail(CompilerDiagnosticCodes.TYPE_INVALID_OPERATOR, patternSyntax.span(),
                            "match subject and pattern do not have compatible equality types");
                    return null;
                }
            }
            common = removeMutableQualifier(common);
            if (!TypeRules.canImplicitlyConvert(subject.type(), common)) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_OPERATOR, patternSyntax.span(),
                        "match equality would require converting the subject unsafely");
                return null;
            }
            ExprResult converted = contextualBranch(patternSyntax, pattern, common, moduleId);
            return converted == null ? null : new PatternTyping(converted, common);
        }

        private Optional<LyraType> matchNumericCommon(
                ExprResult subject,
                SyntaxNode.Expression patternSyntax,
                ExprResult pattern) {
            PrimitiveType subjectType = (PrimitiveType) subject.type().withoutQualifiers();
            ExactNumericLiteral literal = exactNumericLiteral(patternSyntax);
            if (literal == null) {
                return TypeRules.commonNumericType(subjectType, pattern.type().withoutQualifiers());
            }
            for (PrimitiveType candidate : NUMERIC_CANDIDATES) {
                boolean subjectFits = TypeRules.canImplicitlyWiden(subjectType, candidate);
                boolean patternFits = literal.forcedType().isPresent()
                        ? LiteralTyping.representableAs(literal, literal.forcedType().orElseThrow())
                        && TypeRules.canImplicitlyWiden(
                                literal.forcedType().orElseThrow(), candidate)
                        : LiteralTyping.representableAs(literal, candidate);
                if (subjectFits && patternFits) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        }

        private MatchResults checkMatchResults(
                List<SyntaxNode.MatchArm> arms,
                SourceSpan armSpan,
                Optional<LyraType> expected,
                ModuleId moduleId) {
            List<SyntaxNode.Expression> syntax = arms.stream()
                    .map(SyntaxNode.MatchArm::result).toList();
            if (expected.isPresent()) {
                List<ExprResult> values = new ArrayList<>();
                for (int index = 0; index < syntax.size(); index++) {
                    Optional<LyraType> resultExpected = StructuralContextPlan.expectedFor(
                            StructuralContextPlan.ChildPosition.matchResult(index),
                            syntax.get(index), expected, Optional.empty());
                    ExprResult value = checkExpression(syntax.get(index),
                            resultExpected.or(() -> expected), moduleId);
                    if (value == null) {
                        return null;
                    }
                    values.add(value);
                }
                return new MatchResults(expected.orElseThrow(), List.copyOf(values));
            }

            List<Optional<LyraType>> shapes = new ArrayList<>();
            List<ExprResult> initial = new ArrayList<>();
            for (SyntaxNode.Expression result : syntax) {
                Optional<LyraType> shape = synthesizeTypeWithoutContext(result, moduleId);
                if (failed()) {
                    return null;
                }
                shapes.add(shape.map(State::removeMutableQualifier));
                ExprResult value = shape.isPresent()
                        ? checkExpression(result, Optional.empty(), moduleId) : null;
                if (failed()) {
                    return null;
                }
                initial.add(value);
            }
            List<LyraType> known = shapes.stream().flatMap(Optional::stream).toList();
            LyraType resultType;
            if (known.isEmpty()) {
                resultType = StructuralContextPlan.synthesizePeers(
                        syntax,
                        expression -> synthesizeAtomicTypeWithoutContext(expression, moduleId),
                        type -> Optional.ofNullable(typeFromSyntax(type, TypePosition.BINDING)),
                        expression -> synthesizeControlResult(expression, moduleId))
                        .orElse(null);
            } else if (known.stream().allMatch(State::isNumericType)
                    && shapes.stream().allMatch(Optional::isPresent)
                    && syntax.stream().noneMatch(StructuralContextPlan::containsContextFreeNil)) {
                resultType = numericCommon(syntax, initial).orElse(null);
            } else {
                resultType = StructuralContextPlan.foldHomogeneous(known).orElse(null);
            }
            if (resultType != null) {
                for (int index = 0; index < syntax.size(); index++) {
                    SyntaxNode.Expression result = syntax.get(index);
                    if (shapes.get(index).isEmpty()
                            || StructuralContextPlan.containsContextFreeNil(result)) {
                        Optional<LyraType> shaped = StructuralContextPlan.expectedFromPeer(
                                result, resultType);
                        if (shaped.isEmpty()) {
                            resultType = null;
                            break;
                        }
                        resultType = StructuralContextPlan.mergeNilShape(
                                resultType, shaped.orElseThrow());
                    }
                }
            }
            if (resultType == null) {
                SourceSpan span = syntax.stream()
                        .map(StructuralContextPlan::firstContextFreeNilSpan)
                        .flatMap(Optional::stream).findFirst().orElse(syntax.getFirst().span());
                fail(known.isEmpty() ? CompilerDiagnosticCodes.TYPE_NIL_CONTEXT
                                : CompilerDiagnosticCodes.TYPE_INVALID_BRANCH,
                        span, "match results have no exact or losslessly widened common type");
                return null;
            }
            List<ExprResult> values = new ArrayList<>();
            for (int index = 0; index < syntax.size(); index++) {
                ExprResult value = initial.get(index);
                value = value == null
                        ? checkExpression(syntax.get(index), Optional.of(resultType), moduleId)
                        : contextualBranch(syntax.get(index), value, resultType, moduleId);
                if (value == null) {
                    return null;
                }
                values.add(value);
            }
            return new MatchResults(resultType, List.copyOf(values));
        }

        private Optional<LyraType> synthesizeControlResult(
                SyntaxNode.Expression expression,
                ModuleId moduleId) {
            if (expression instanceof SyntaxNode.Conditional conditional) {
                return synthesizeStructuralConditional(conditional, moduleId);
            }
            if (expression instanceof SyntaxNode.Match match) {
                return synthesizeStructuralMatch(match, moduleId);
            }
            if (expression instanceof SyntaxNode.Cond cond) {
                return synthesizeStructuralCond(cond, moduleId);
            }
            return Optional.empty();
        }

        private record PatternTyping(ExprResult pattern, LyraType comparisonType) {
        }

        private record MatchResults(LyraType type, List<ExprResult> values) {
        }

        private ExprResult contextualBranch(
                SyntaxNode.Expression syntax,
                ExprResult value,
                LyraType target,
                ModuleId moduleId) {
            if (value.type().equals(target)) {
                return value;
            }
            if (exactNumericLiteral(syntax) != null
                    || StructuralContextPlan.canRecheckWithStructuralContext(syntax)) {
                // Composite contracts are invariant.  A collection/tuple
                // literal can nevertheless be constructed directly under the
                // branch's recursively derived nil contract; an already
                // typed reference cannot be widened through that boundary.
                return checkExpression(syntax, Optional.of(target), moduleId);
            }
            return coerce(value, target, syntax.span());
        }

        private Optional<LyraType> numericCommon(
                List<SyntaxNode.Expression> syntax,
                List<ExprResult> values) {
            if (syntax.size() != values.size() || syntax.isEmpty()) {
                return Optional.empty();
            }
            boolean allDirect = true;
            boolean allUnforced = true;
            boolean decimal = false;
            List<PrimitiveType> fixed = new ArrayList<>();
            List<ExactNumericLiteral> literals = new ArrayList<>();
            for (int index = 0; index < values.size(); index++) {
                ExactNumericLiteral literal = exactNumericLiteral(syntax.get(index));
                if (literal != null) {
                    literals.add(literal);
                    allUnforced &= literal.forcedType().isEmpty();
                    decimal |= literal.isDecimal();
                } else {
                    allDirect = false;
                    LyraType type = values.get(index).type();
                    if (!isNumericType(type) || type.isNilable()) {
                        return Optional.empty();
                    }
                    fixed.add((PrimitiveType) type.withoutQualifiers());
                }
            }
            List<PrimitiveType> candidates = new ArrayList<>();
            for (PrimitiveType candidate : NUMERIC_CANDIDATES) {
                boolean fits = true;
                for (ExactNumericLiteral literal : literals) {
                    if (literal.forcedType().isPresent()) {
                        PrimitiveType forced = literal.forcedType().orElseThrow();
                        fits &= LiteralTyping.representableAs(literal, forced)
                                && TypeRules.canImplicitlyWiden(forced, candidate);
                    } else {
                        fits &= LiteralTyping.representableAs(literal, candidate);
                    }
                }
                for (PrimitiveType type : fixed) {
                    fits &= TypeRules.canImplicitlyWiden(type, candidate);
                }
                if (fits) {
                    candidates.add(candidate);
                }
            }
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            if (allDirect && allUnforced) {
                PrimitiveType defaultType = decimal ? PrimitiveType.F64 : PrimitiveType.I64;
                if (candidates.contains(defaultType)) {
                    return Optional.of(defaultType);
                }
            }
            return Optional.of(candidates.getFirst());
        }

        private ExprResult checkCoalesce(
                SyntaxNode.Coalesce coalesce,
                Optional<LyraType> expected,
                ModuleId moduleId) {
            // A contextual nilable result supplies the otherwise unknowable
            // base contract for #NIL on the value side.  A non-nil context
            // must not be promoted to @nil merely to make #NIL legal.
            Optional<LyraType> valueExpected = StructuralContextPlan.expectedFor(
                    StructuralContextPlan.ChildPosition.coalesceValue(),
                    coalesce.value(), expected, Optional.empty());
            ExprResult value = checkExpression(coalesce.value(), valueExpected, moduleId);
            if (value == null) {
                return null;
            }
            if (!value.type().isNilable()) {
                fail(CompilerDiagnosticCodes.TYPE_NIL_CONTEXT,
                        coalesce.value().span(), "nil coalescing requires an @nil value");
                return null;
            }
            LyraType narrowed = removeMutableQualifier(narrowedType(value.type()));
            Optional<LyraType> fallbackExpected = StructuralContextPlan.expectedFor(
                    StructuralContextPlan.ChildPosition.coalesceFallback(),
                    coalesce.fallback(), Optional.of(value.type()), Optional.empty());
            ExprResult fallback = checkExpression(
                    coalesce.fallback(), Optional.of(fallbackExpected.orElse(narrowed)), moduleId);
            if (fallback == null) {
                return null;
            }
            return result(node(
                    TypedExpressionKind.COALESCE,
                    coalesce.span(),
                    narrowed,
                    List.of(value.expression(), fallback.expression()),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    List.of(), Optional.empty()));
        }

        private ExprResult checkRebinding(
                SyntaxNode.Expression targetSyntax,
                SyntaxNode.Expression valueSyntax,
                SourceSpan span,
                ModuleId moduleId) {
            boolean aggregateElement = targetSyntax instanceof SyntaxNode.IndexAccess;
            boolean memberField = targetSyntax instanceof SyntaxNode.MemberAccess access && access.member().isIdentifier();
            if (!aggregateElement && !memberField && !(targetSyntax instanceof SyntaxNode.Identifier)) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_REBINDING,
                        targetSyntax.span(), "assignment target must be a binding or array element");
                return null;
            }
            ExprResult target = checkExpression(targetSyntax, Optional.empty(), moduleId);
            if (target == null) {
                return null;
            }
            DeclarationId declarationId = mutationRoot(target.expression());
            if (declarationId == null) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_REBINDING,
                        targetSyntax.span(), "assignment target is not a resolved mutable root");
                return null;
            }
            ResolvedDeclaration declaration = declarations.get(declarationId);
            if (declaration == null || declaration.bindingMutability() != BindingMutability.MUTABLE
                    && !(declaration.kind() == DeclarationKind.SELF && (aggregateElement || memberField))) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_REBINDING,
                        targetSyntax.span(), "assignment requires an @mut binding root");
                return null;
            }
            BindingContract contract = typedContracts.get(declarationId);
            if (contract == null) {
                fail(CompilerDiagnosticCodes.TYPE_UNTYPED_EXPRESSION,
                        targetSyntax.span(), "assignment target has no complete type");
                return null;
            }
            TypedLink canonicalRootLink = rootLink(target.expression()).orElse(null);
            ReferenceId canonicalRootReference = canonicalRootLink == null
                    ? null : canonicalRootLink.referenceId().orElse(null);
            if (canonicalRootReference == null) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_REBINDING,
                        targetSyntax.span(),
                        "assignment target has no canonical resolver root reference");
                return null;
            }
            if (memberField) {
                if (target.expression().declarationId().isEmpty()
                        || declarations.get(target.expression().declarationId().orElseThrow()).kind() != DeclarationKind.MEMBER
                        || !graph.mutations().stream().anyMatch(mutation -> mutation.kind() == MutationKind.MEMBER_FIELD
                        && mutation.span().equals(targetSyntax.span()) && mutation.rootDeclaration().equals(declarationId)
                        && mutation.rootReference().equals(Optional.of(canonicalRootReference)))) {
                    fail(CompilerDiagnosticCodes.TYPE_INVALID_REBINDING, targetSyntax.span(), "field write lacks exact member authorization");
                    return null;
                }
            } else if (aggregateElement) {
                boolean immediateArrayElement = target.expression().children().size() == 2
                        && !target.expression().children().getFirst().type().isNilable()
                        && target.expression().children().getFirst().type().withoutQualifiers()
                        instanceof ArrayType array
                        && array.elementType().equals(target.type());
                if (!immediateArrayElement) {
                    fail(CompilerDiagnosticCodes.TYPE_INVALID_REBINDING,
                            targetSyntax.span(), "array-element assignment target is not an Array element");
                    return null;
                }
                if (!graph.mutations().stream().anyMatch(mutation ->
                        mutation.kind() == MutationKind.ARRAY_ELEMENT
                                && mutation.moduleId().equals(moduleId)
                                && mutation.span().equals(targetSyntax.span())
                                && mutation.rootDeclaration().equals(declarationId)
                                && mutation.rootReference().equals(
                                Optional.of(canonicalRootReference)))) {
                    fail(CompilerDiagnosticCodes.TYPE_INVALID_REBINDING,
                            targetSyntax.span(), "array-element assignment lacks resolver mutation authorization");
                    return null;
                }
            } else if (!graph.mutations().stream().anyMatch(mutation ->
                    mutation.kind() == MutationKind.REBINDING
                            && mutation.moduleId().equals(moduleId)
                            && mutation.span().equals(targetSyntax.span())
                            && mutation.rootDeclaration().equals(declarationId)
                            && mutation.rootReference().equals(
                            Optional.of(canonicalRootReference)))) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_REBINDING,
                        targetSyntax.span(), "assignment lacks resolver mutation authorization");
                return null;
            }
            LyraType targetValueType = aggregateElement || memberField ? target.type() : contract.valueType();
            ExprResult value = checkExpression(valueSyntax, Optional.of(targetValueType), moduleId);
            if (value == null) {
                return null;
            }
            TypedLink mutationLink = canonicalRootLink;
            return result(node(
                    TypedExpressionKind.REBINDING,
                    span,
                    PrimitiveType.UNIT,
                    List.of(target.expression(), value.expression()),
                    Optional.empty(),
                    Optional.of(mutationLink),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(declarationId), Optional.empty(), Optional.empty(), Optional.empty(),
                    List.of(), Optional.empty()));
        }

        private DeclarationId mutationRoot(TypedExpression target) {
            if (target.kind() == TypedExpressionKind.REFERENCE) {
                return target.link().flatMap(TypedLink::declarationId).orElse(null);
            }
            if ((target.kind() == TypedExpressionKind.INDEX_ACCESS
                    || target.kind() == TypedExpressionKind.MEMBER_ACCESS)
                    && !target.children().isEmpty()) {
                return mutationRoot(target.children().getFirst());
            }
            return null;
        }

        private Optional<TypedLink> rootLink(TypedExpression target) {
            if (target.kind() == TypedExpressionKind.REFERENCE) {
                return target.link();
            }
            if ((target.kind() == TypedExpressionKind.INDEX_ACCESS
                    || target.kind() == TypedExpressionKind.MEMBER_ACCESS)
                    && !target.children().isEmpty()) {
                return rootLink(target.children().getFirst());
            }
            return Optional.empty();
        }

        private ExprResult checkLambda(
                SyntaxNode.Lambda syntax,
                Optional<LyraType> expected,
                ModuleId moduleId) {
            LambdaId id = lambdaIdAt(syntax.span());
            ResolvedLambda resolved = id == null ? null : lambdas.get(id);
            if (resolved == null || resolved.signature().isEmpty()) {
                fail(CompilerDiagnosticCodes.TYPE_INCOMPLETE_LAMBDA,
                        syntax.span(), "lambda has no complete resolved function signature");
                return null;
            }
            LyraSignature signature = resolved.signature().orElseThrow();
            if (expected.isPresent()) {
                LyraType expectedBase = expected.orElseThrow().withoutQualifiers();
                if (!(expectedBase instanceof FunctionType expectedFunction)
                        || !expectedFunction.signature().equals(signature)) {
                    fail(CompilerDiagnosticCodes.TYPE_MISMATCH,
                            syntax.span(), "lambda signature does not match its expected Fn contract");
                    return null;
                }
            }
            for (DeclarationId parameter : resolved.parameterIds()) {
                BindingContract contract = typedContracts.get(parameter);
                if (contract == null) {
                    ResolvedDeclaration declaration = declarations.get(parameter);
                    if (declaration == null || declaration.effectiveContract().isEmpty()) {
                        fail(CompilerDiagnosticCodes.TYPE_UNTYPED_EXPRESSION,
                                syntax.span(), "lambda parameter has no complete type");
                        return null;
                    }
                    contract = declaration.effectiveContract().orElseThrow();
                    typedContracts.put(parameter, contract);
                    declarationTypes.put(parameter, expressionType(declaration, contract.valueType()));
                }
            }
            ExprResult body = checkExpression(syntax.body(), Optional.of(signature.returnType()), moduleId);
            if (body == null) {
                return null;
            }
            TypedLambda typedLambda = new TypedLambda(
                    id,
                    moduleId,
                    syntax.span(),
                    resolved.scopeId(),
                    signature,
                    resolved.parameterIds(),
                    resolved.captures(),
                    body.expression());
            typedLambdas.put(id, typedLambda);
            return result(node(
                    TypedExpressionKind.LAMBDA,
                    syntax.span(),
                    signature.asFunctionType(),
                    List.of(body.expression()),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.of(id), Optional.empty(), Optional.of(signature),
                    resolved.captures(), Optional.empty()));
        }

        private ExprResult checkCompactLambda(
                SyntaxNode.CompactLambda syntax,
                Optional<LyraType> expected,
                ModuleId moduleId) {
            if (expected.isEmpty() || !(expected.orElseThrow().withoutQualifiers() instanceof FunctionType)) {
                fail(CompilerDiagnosticCodes.TYPE_INCOMPLETE_LAMBDA,
                        syntax.span(), "compact lambda requires a complete expected Fn type");
                return null;
            }
            LambdaId id = lambdaIdAt(syntax.span());
            ResolvedLambda resolved = id == null ? null : lambdas.get(id);
            if (resolved == null || resolved.signature().isEmpty()) {
                fail(CompilerDiagnosticCodes.TYPE_INCOMPLETE_LAMBDA,
                        syntax.span(), "compact lambda has no complete resolved function signature");
                return null;
            }
            LyraSignature signature = resolved.signature().orElseThrow();
            FunctionType expectedFunction = (FunctionType) expected.orElseThrow().withoutQualifiers();
            if (!expectedFunction.signature().equals(signature)) {
                fail(CompilerDiagnosticCodes.TYPE_MISMATCH,
                        syntax.span(), "compact lambda signature does not match its expected Fn contract");
                return null;
            }
            ExprResult body = checkExpression(syntax.body(), Optional.of(signature.returnType()), moduleId);
            if (body == null) {
                return null;
            }
            TypedLambda typedLambda = new TypedLambda(
                    id,
                    moduleId,
                    syntax.span(),
                    resolved.scopeId(),
                    signature,
                    resolved.parameterIds(),
                    resolved.captures(),
                    body.expression());
            typedLambdas.put(id, typedLambda);
            return result(node(
                    TypedExpressionKind.LAMBDA,
                    syntax.span(),
                    signature.asFunctionType(),
                    List.of(body.expression()),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.of(id), Optional.empty(), Optional.of(signature),
                    resolved.captures(), Optional.empty()));
        }

        private FunctionType callableType(LyraType type, SourceSpan span) {
            if (type.isNilable()) {
                fail(CompilerDiagnosticCodes.TYPE_NOT_CALLABLE,
                        span, "a nilable function value must be narrowed before it is called");
                return null;
            }
            LyraType base = type.withoutQualifiers();
            if (!(base instanceof FunctionType function)) {
                fail(CompilerDiagnosticCodes.TYPE_NOT_CALLABLE,
                        span, "expression is not callable");
                return null;
            }
            return function;
        }

        private ExprResult checkCallableCall(SyntaxNode.CallableCall call, ModuleId moduleId) {
            if (CallbackLoop.of(call).isPresent()) return checkLoop(call, moduleId);
            ExprResult target = checkExpression(call.target(), Optional.empty(), moduleId);
            if (target == null) {
                return null;
            }
            FunctionType function = callableType(target.type(), call.target().span());
            if (function == null) {
                return null;
            }
            List<TypedExpression> arguments = checkArguments(
                    call.argumentExpressions(), function.signature(), moduleId, call.span());
            if (arguments == null) {
                return null;
            }
            ArrayList<TypedExpression> children = new ArrayList<>();
            children.add(target.expression());
            children.addAll(arguments);
            return result(node(
                    TypedExpressionKind.CALLABLE_CALL,
                    call.span(),
                    function.returnType(),
                    children,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    List.of(), Optional.empty()));
        }

        private ExprResult checkDirectCall(SyntaxNode.DirectCall call, ModuleId moduleId) {
            if (CallbackLoop.of(call).isPresent()) return checkLoop(call, moduleId);
            if (call.receiver().isPresent()) {
                ExprResult receiver = checkExpression(call.receiver().orElseThrow(), Optional.empty(), moduleId);
                if (receiver == null) {
                    return null;
                }
                TypedExpression selected = nominalMember(receiver.expression(), call.name().name(), call.name().span(), call.span());
                if (selected == null) return null;
                FunctionType function = callableType(selected.type(), call.span());
                if (function == null) return null;
                var arguments = checkArguments(call.argumentExpressions(), function.signature(), moduleId, call.span());
                if (arguments == null) return null;
                List<TypedExpression> children = new ArrayList<>();
                children.add(selected); children.addAll(arguments);
                return result(node(TypedExpressionKind.CALLABLE_CALL, call.span(), function.returnType(), children,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(), Optional.empty()));
            }
            ResolvedReference reference = findReference(
                    call.name().span(), call.name().name(), ReferenceKind.DIRECT_CALL_TARGET);
            if (reference == null) {
                return null;
            }
            TypedReference typedReference = typeReference(reference, moduleId);
            if (typedReference == null || typedReference.type().isEmpty()) {
                return null;
            }
            FunctionType function = callableType(typedReference.valueType(), call.span());
            if (function == null) {
                return null;
            }
            List<TypedExpression> arguments = checkArguments(
                    call.argumentExpressions(), function.signature(), moduleId, call.span());
            if (arguments == null) {
                return null;
            }
            return result(node(
                    TypedExpressionKind.DIRECT_CALL,
                    call.span(),
                    function.returnType(),
                    arguments,
                    Optional.empty(),
                    Optional.of(link(reference, Optional.empty())),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    reference.targetDeclaration(), Optional.empty(), Optional.empty(), Optional.empty(),
                    List.of(), Optional.empty()));
        }
        private ExprResult checkLoop(SyntaxNode.Expression syntax, ModuleId moduleId) {
            CallbackLoop loop = CallbackLoop.of(syntax).orElseThrow();
            List<SyntaxNode.Expression> arguments = CallbackLoop.arguments(syntax);
            if (arguments.size() != 2) {
                fail(CompilerDiagnosticCodes.TYPE_MISMATCH, syntax.span(), "callback loops require two arguments");
                return null;
            }
            ExprResult first = checkExpression(arguments.getFirst(), loop == CallbackLoop.WHILE
                    ? Optional.of(CallbackLoop.predicateType()) : Optional.empty(), moduleId);
            if (first == null) return null;
            Optional<LyraType> expected = Optional.empty();
            if (loop == CallbackLoop.WHILE || CallbackLoop.anonymousArity(arguments.getLast()) == 0) {
                expected = Optional.of(CallbackLoop.actionType());
            } else if (CallbackLoop.anonymousArity(arguments.getLast()) > 0
                    && first.type() instanceof RangeType range) {
                expected = Optional.of(new FunctionType(List.of(range.elementType()), PrimitiveType.UNIT));
            }
            ExprResult action = checkExpression(arguments.getLast(), expected, moduleId);
            if (action == null) return null;
            TypedExpressionKind kind = loop == CallbackLoop.ITER ? TypedExpressionKind.ITER : TypedExpressionKind.WHILE;
            if (!CallbackLoop.valid(kind, PrimitiveType.UNIT, List.of(first.type(), action.type()))) {
                fail(CompilerDiagnosticCodes.TYPE_MISMATCH, syntax.span(), loop == CallbackLoop.WHILE
                        ? "while requires Fn<;Bool> and Fn<;Unit>"
                        : "iter requires Range<T> and Fn<T;Unit> or Fn<;Unit>");
                return null;
            }
            return result(node(kind, syntax.span(), PrimitiveType.UNIT,
                    List.of(first.expression(), action.expression()),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    List.of(), Optional.empty()));
        }

        private boolean isNominalName(ResolvedReference reference) {
            ResolvedDeclaration declaration = reference.targetDeclaration().map(declarations::get).orElse(null);
            var seen = new java.util.HashSet<DeclarationId>();
            while (declaration != null && seen.add(declaration.id())) {
                if (declaration.kind() == DeclarationKind.NOMINAL) return true;
                declaration = declaration.originDeclaration().map(declarations::get).orElse(null);
            }
            return false;
        }

        private ExprResult checkNamespaceMemberAccess(
                SyntaxNode.NamespaceMemberAccess access,
                ModuleId moduleId) {
            typeNamespacePath(access.path(), moduleId);
            ResolvedReference reference = findReference(
                    access.member().span(), access.member().name(), ReferenceKind.NAMESPACE_MEMBER);
            if (reference == null) {
                return null;
            }
            if (isNominalName(reference)) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_ACCESS, access.span(), "a nominal type name must be constructed with brackets");
                return null;
            }
            TypedReference typedReference = typeReference(reference, moduleId);
            if (typedReference == null || typedReference.type().isEmpty()) {
                return null;
            }
            return result(node(
                    TypedExpressionKind.NAMESPACE_MEMBER_ACCESS,
                    access.span(),
                    typedReference.valueType(),
                    List.of(),
                    Optional.empty(),
                    Optional.of(link(reference, Optional.of(AccessKind.NAMESPACE_VALUE))),
                    Optional.empty(), Optional.empty(), Optional.of(access.member().name()),
                    access.member().tupleIndex(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    List.of(), Optional.empty()));
        }

        private ExprResult checkNamespaceDirectCall(
                SyntaxNode.NamespaceDirectCall call,
                ModuleId moduleId) {
            typeNamespacePath(call.path(), moduleId);
            ResolvedReference reference = findReference(
                    call.name().span(), call.name().name(), ReferenceKind.NAMESPACE_DIRECT_CALL);
            if (reference == null) {
                return null;
            }
            TypedReference typedReference = typeReference(reference, moduleId);
            if (typedReference == null || typedReference.type().isEmpty()) {
                return null;
            }
            FunctionType function = callableType(typedReference.valueType(), call.span());
            if (function == null) {
                return null;
            }
            List<TypedExpression> arguments = checkArguments(
                    call.argumentExpressions(), function.signature(), moduleId, call.span());
            if (arguments == null) {
                return null;
            }
            return result(node(
                    TypedExpressionKind.NAMESPACE_DIRECT_CALL,
                    call.span(),
                    function.returnType(),
                    arguments,
                    Optional.empty(),
                    Optional.of(link(reference, Optional.of(AccessKind.NAMESPACE_DIRECT_CALL))),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    reference.targetDeclaration(), Optional.empty(), Optional.empty(), Optional.empty(),
                    List.of(), Optional.empty()));
        }

        private void typeNamespacePath(SyntaxNode.NamespacePath path, ModuleId moduleId) {
            ResolvedReference reference = findReferenceByKind(path.span(), ReferenceKind.MODULE_NAMESPACE);
            if (reference != null) {
                typeReference(reference, moduleId);
            }
        }

        private ExprResult checkMemberAccess(SyntaxNode.MemberAccess access, ModuleId moduleId) {
            ExprResult receiver = checkExpression(access.receiver(), Optional.empty(), moduleId);
            if (receiver == null) {
                return null;
            }
            if (receiver.type().isNilable()) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_ACCESS,
                        access.receiver().span(), "nilable values must be narrowed before member access");
                return null;
            }
            LyraType base = receiver.type().withoutQualifiers();
            if (base instanceof NominalType && access.member().isIdentifier()) {
                TypedExpression member = nominalMember(receiver.expression(), access.member().name(), access.member().span(), access.span());
                return member == null ? null : result(member);
            }
            LyraType memberType = null;
            if (access.member().isIdentifier()
                    && access.member().name().equals("length")
                    && (base == PrimitiveType.STRING || base instanceof ArrayType)) {
                memberType = PrimitiveType.I32;
            } else if (access.member().isTupleIndex() && base instanceof TupleType tuple) {
                BigInteger index = access.member().index();
                if (index.bitLength() > 31 || index.intValue() >= tuple.arity()) {
                    fail(CompilerDiagnosticCodes.TYPE_INVALID_ACCESS,
                            access.member().span(), "tuple member index is outside the tuple shape");
                    return null;
                }
                memberType = tuple.memberType(index.intValue());
            }
            if (memberType == null) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_ACCESS,
                        access.member().span(), "member is not available on the statically known type");
                return null;
            }
            return result(node(
                    TypedExpressionKind.MEMBER_ACCESS,
                    access.span(),
                    memberType,
                    List.of(receiver.expression()),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    access.member().identifier(), access.member().tupleIndex(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), List.of(), Optional.empty()));
        }

        private TypedExpression nominalMember(TypedExpression receiver, String name, SourceSpan nameSpan, SourceSpan span) {
            if (receiver.type().isNilable() || !(receiver.type().withoutQualifiers() instanceof NominalType nominalType)) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_ACCESS, nameSpan, "member access requires a non-nil nominal receiver");
                return null;
            }
            var nominal = graph.nominals().stream().filter(value -> value.schema().type().equals(nominalType)).findFirst().orElseThrow();
            int index = -1;
            for (int candidate = 0; candidate < nominal.schema().members().size(); candidate++) {
                if (nominal.schema().members().get(candidate).name().equals(name)) index = candidate;
            }
            if (index < 0) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_ACCESS, nameSpan, "unknown nominal member: " + name);
                return null;
            }
            var member = nominal.schema().members().get(index);
            var declaration = declarations.get(nominal.declaration());
            boolean lexicalAccess = declaration.span().sourceId().equals(span.sourceId())
                    && declaration.span().startOffset() <= span.startOffset() && declaration.span().endOffset() >= span.endOffset();
            if (!member.publicAccess() && !lexicalAccess) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_ACCESS, nameSpan, "member is private: " + name);
                return null;
            }
            return node(TypedExpressionKind.MEMBER_ACCESS, span, member.type(), List.of(receiver), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.of(name), Optional.empty(), Optional.of(nominal.members().get(index)),
                    Optional.empty(), Optional.empty(), Optional.empty(), List.of(), Optional.empty());
        }

        private List<TypedExpression> checkArguments(
                List<SyntaxNode.Expression> arguments,
                LyraSignature signature,
                ModuleId moduleId,
                SourceSpan callSpan) {
            if (arguments.size() != signature.arity()) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_ARITY,
                        callSpan,
                        "call has " + arguments.size() + " argument(s), expected " + signature.arity());
                return null;
            }
            List<TypedExpression> result = new ArrayList<>();
            for (int index = 0; index < arguments.size(); index++) {
                LyraType parameterType = signature.parameterType(index);
                ExprResult argument = checkExpression(
                        arguments.get(index), Optional.of(parameterType), moduleId);
                if (argument == null
                        && parameterType.isMutable()
                        && diagnostic != null
                        && diagnostic.code().equals(
                        CompilerDiagnosticCodes.TYPE_MISMATCH)) {
                    Diagnostic mutableMismatch = diagnostic;
                    diagnostic = null;
                    argument = checkExpression(
                            arguments.get(index),
                            Optional.of(removeMutableQualifier(parameterType)),
                            moduleId);
                    if (argument != null
                            && deferredMutableArgumentDiagnostic == null) {
                        // Imported ownership historically precedes this
                        // mutability mismatch. Keep the fully typed base value
                        // only until canonical flow can either issue the exact
                        // ownership diagnostic or restore this original error.
                        deferredMutableArgumentDiagnostic = mutableMismatch;
                    }
                }
                if (argument == null) {
                    return null;
                }
                result.add(argument.expression());
            }
            return List.copyOf(result);
        }


        private ExprResult checkExplicitConversion(
                SyntaxNode.TypeConversion conversion,
                ModuleId moduleId) {
            PrimitiveType target = PrimitiveType.fromSpelling(
                    ((SyntaxNode.PrimitiveType) conversion.targetType()).name()).orElse(null);
            if (target == null) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_CONVERSION,
                        conversion.targetType().span(), "conversion target is not a known primitive type");
                return null;
            }
            ExprResult value = checkExpression(conversion.value(), Optional.empty(), moduleId);
            if (value == null) {
                return null;
            }
            if (value.type().isNilable()) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_CONVERSION,
                        conversion.value().span(), "nilable values must be narrowed before conversion");
                return null;
            }
            value = coerce(value, removeMutableQualifier(value.type()), conversion.value().span());
            if (value == null) {
                return null;
            }
            LyraType sourceBase = value.type().withoutQualifiers();
            boolean numericConversion = sourceBase instanceof PrimitiveType sourcePrimitive
                    && sourcePrimitive.isNumeric() && target.isNumeric();
            boolean textConversion = target == PrimitiveType.STRING
                    && sourceBase instanceof PrimitiveType;
            if (!numericConversion && !textConversion) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_CONVERSION,
                        conversion.span(), "only numeric and primitive-to-String conversions are supported");
                return null;
            }
            ConversionDecision decision = TypeRules.explicitConversion(value.type(), target);
            if (!decision.allowed()) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_CONVERSION,
                        conversion.span(), decision.reason());
                return null;
            }
            TypedLiteralValue constant = constantValue(value.expression());
            if (constant instanceof TypedLiteralValue.IntegerValue integer
                    && target.isNumeric()
                    && !constantRepresentableAs(integer.exactValue(), target)) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_CONVERSION,
                        conversion.span(), "constant integer conversion is outside the target range");
                return null;
            }
            if (constant instanceof TypedLiteralValue.DecimalValue decimal
                    && target.isNumeric()
                    && !constantRepresentableAs(decimal.exactValue(), target)) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_CONVERSION,
                        conversion.span(), "constant decimal conversion is not value-preserving or is outside the target range");
                return null;
            }
            ConversionStep step = target == PrimitiveType.STRING
                    ? ConversionStep.TEXT_EXPLICIT
                    : ConversionStep.NUMERIC_EXPLICIT;
            TypedConversion conversionRecord = new TypedConversion(
                    conversion.span(), value.type(), target, ConversionKind.EXPLICIT, step);
            return result(node(
                    TypedExpressionKind.CONVERSION,
                    conversion.span(),
                    target,
                    List.of(value.expression()),
                    Optional.empty(), Optional.empty(), Optional.of(conversionRecord), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), List.of(), Optional.empty()));
        }

        private ExprResult checkOperator(
                SyntaxNode.Operator operator,
                List<SyntaxNode.Expression> operands,
                SourceSpan span,
                ModuleId moduleId,
                Optional<LyraType> expected) {
            TokenKind kind = operator.tokenKind();
            if (kind == TokenKind.AND || kind == TokenKind.OR || kind == TokenKind.XOR) {
                if (operands.size() < 2) {
                    fail(CompilerDiagnosticCodes.TYPE_INVALID_ARITY,
                            span, operator.spelling() + " requires at least two operands");
                    return null;
                }
                List<TypedExpression> typed = new ArrayList<>();
                for (SyntaxNode.Expression operand : operands) {
                    ExprResult value = checkExpression(operand, Optional.empty(), moduleId);
                    if (value == null) {
                        return null;
                    }
                    if (!truthTestable(value.type())) {
                        fail(CompilerDiagnosticCodes.TYPE_INVALID_TRUTH_TEST,
                                operand.span(), "operand is not truth-testable");
                        return null;
                    }
                    typed.add(value.expression());
                }
                return result(node(
                        kind == TokenKind.XOR ? TypedExpressionKind.OPERATOR : TypedExpressionKind.SHORT_CIRCUIT,
                        span,
                        PrimitiveType.BOOL,
                        typed,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(operator.spelling()),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), List.of(), Optional.empty()));
            }
            if (kind == TokenKind.NOT) {
                if (operands.size() != 1) {
                    fail(CompilerDiagnosticCodes.TYPE_INVALID_ARITY,
                            span, "not requires exactly one operand");
                    return null;
                }
                ExprResult value = checkExpression(operands.getFirst(), Optional.empty(), moduleId);
                if (value == null) {
                    return null;
                }
                if (!truthTestable(value.type())) {
                    fail(CompilerDiagnosticCodes.TYPE_INVALID_TRUTH_TEST,
                            operands.getFirst().span(), "operand is not truth-testable");
                    return null;
                }
                return result(node(
                        TypedExpressionKind.OPERATOR,
                        span,
                        PrimitiveType.BOOL,
                        List.of(value.expression()),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(operator.spelling()),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), List.of(), Optional.empty()));
            }
            if (kind == TokenKind.EQUAL_EQUAL || kind == TokenKind.NOT_EQUAL
                    || kind == TokenKind.IDENTITY_EQUAL || kind == TokenKind.IDENTITY_NOT_EQUAL) {
                return checkEquality(operator, operands, span, moduleId);
            }
            if (kind == TokenKind.LESS || kind == TokenKind.LESS_EQUAL
                    || kind == TokenKind.GREATER || kind == TokenKind.GREATER_EQUAL) {
                if (operands.size() < 2) {
                    fail(CompilerDiagnosticCodes.TYPE_INVALID_ARITY,
                            span, operator.spelling() + " requires at least two operands");
                    return null;
                }
                NumericOperands numeric = numericOperands(operands, Optional.empty(), kind, moduleId);
                if (numeric == null) {
                    return null;
                }
                return result(node(
                        TypedExpressionKind.OPERATOR,
                        span,
                        PrimitiveType.BOOL,
                        numeric.expressions,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(operator.spelling()),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), List.of(), Optional.empty()));
            }
            if (kind == TokenKind.PLUS || kind == TokenKind.ASTERISK || kind == TokenKind.MINUS
                    || kind == TokenKind.SLASH || kind == TokenKind.PERCENT || kind == TokenKind.CARET
                    || kind == TokenKind.INCREMENT || kind == TokenKind.DECREMENT) {
                return checkNumericOrStringOperator(operator, operands, span, moduleId, expected);
            }
            fail(CompilerDiagnosticCodes.TYPE_INVALID_OPERATOR,
                    span, "operator is not a current Lyra operator");
            return null;
        }

        private ExprResult checkNumericOrStringOperator(
                SyntaxNode.Operator operator,
                List<SyntaxNode.Expression> operands,
                SourceSpan span,
                ModuleId moduleId,
                Optional<LyraType> expected) {
            TokenKind kind = operator.tokenKind();
            int minimum = switch (kind) {
                case PLUS, ASTERISK -> 2;
                case MINUS, SLASH -> 1;
                default -> 1;
            };
            int maximum = switch (kind) {
                case PERCENT, CARET, INCREMENT, DECREMENT -> 2;
                default -> Integer.MAX_VALUE;
            };
            if (operands.size() < minimum || operands.size() > maximum
                    || ((kind == TokenKind.INCREMENT || kind == TokenKind.DECREMENT)
                    && operands.size() != 1)
                    || (kind == TokenKind.PERCENT || kind == TokenKind.CARET) && operands.size() != 2) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_ARITY,
                        span, operator.spelling() + " has invalid operand arity");
                return null;
            }
            if (kind == TokenKind.MINUS && operands.size() == 1) {
                return checkUnaryMinus(operator, operands.getFirst(), span, moduleId, expected);
            }
            if (kind == TokenKind.PLUS) {
                List<ExprResult> values = new ArrayList<>();
                boolean allStrings = true;
                for (SyntaxNode.Expression operand : operands) {
                    ExprResult value = checkExpression(operand, Optional.empty(), moduleId);
                    if (value == null) {
                        return null;
                    }
                    allStrings &= value.type().withoutQualifiers() == PrimitiveType.STRING
                            && !value.type().isNilable();
                    values.add(value);
                }
                if (allStrings) {
                    List<ExprResult> strings = new ArrayList<>();
                    for (ExprResult value : values) {
                        ExprResult string = coerce(value, PrimitiveType.STRING, value.expression().span());
                        if (string == null) {
                            return null;
                        }
                        strings.add(string);
                    }
                    return result(node(
                            TypedExpressionKind.OPERATOR,
                            span,
                            PrimitiveType.STRING,
                            strings.stream().map(ExprResult::expression).toList(),
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(operator.spelling()),
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.empty(), List.of(), Optional.empty()));
                }
            }
            NumericOperands numeric = numericOperands(operands, expected, kind, moduleId);
            if (numeric == null) {
                return null;
            }
            PrimitiveType resultType = numeric.commonType;
            if (kind == TokenKind.SLASH && numeric.allInteger) {
                LyraType expectedBase = expected.map(LyraType::withoutQualifiers).orElse(null);
                resultType = expectedBase == PrimitiveType.F32 ? PrimitiveType.F32 : PrimitiveType.F64;
            }
            if ((kind == TokenKind.PERCENT) && !resultType.isInteger()) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_OPERATOR,
                        span, "remainder requires an integer common type");
                return null;
            }
            if (!validateConstantIntegerOperation(kind, numeric.expressions, numeric.commonType, span)
                    || !validateConstantFloatingOperation(kind, numeric.expressions, resultType, span)) {
                return null;
            }
            return result(node(
                    TypedExpressionKind.OPERATOR,
                    span,
                    resultType,
                    numeric.expressions,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(operator.spelling()),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), List.of(), Optional.empty()));
        }

        private ExprResult checkUnaryMinus(
                SyntaxNode.Operator operator,
                SyntaxNode.Expression operand,
                SourceSpan span,
                ModuleId moduleId,
                Optional<LyraType> expected) {
            ExactNumericLiteral literal = exactNumericLiteral(operand);
            if (literal != null) {
                ExactNumericLiteral negated = literal.negated();
                Optional<PrimitiveType> inferred = expected
                        .filter(State::isNumericType)
                        .flatMap(value -> LiteralTyping.infer(negated, value));
                if (inferred.isEmpty() && expected.isPresent()
                        && isNumericType(expected.orElseThrow())) {
                    fail(CompilerDiagnosticCodes.TYPE_INVALID_LITERAL,
                            operand.span(), "negated numeric literal is not exactly representable as the expected type");
                    return null;
                }
                if (inferred.isEmpty()) {
                    inferred = LiteralTyping.infer(negated);
                }
                if (inferred.isEmpty()) {
                    fail(CompilerDiagnosticCodes.TYPE_INVALID_LITERAL,
                            operand.span(), "negated numeric literal is outside its exact type range");
                    return null;
                }
                // The sign is an operator, so retain the unsigned magnitude
                // as the operand and let the unary operator produce negated.
                // This also permits the signed minimum magnitude (for example
                // -128I8) without inventing a second literal spelling.
                ExprResult value = finishLiteral(
                        operand,
                        inferred.orElseThrow(),
                        literal.isInteger()
                                ? new TypedLiteralValue.IntegerValue(literal)
                                : new TypedLiteralValue.DecimalValue(literal));
                if (!validateConstantIntegerOperation(
                        TokenKind.MINUS, List.of(value.expression()), inferred.orElseThrow(), span)
                        || !validateConstantFloatingOperation(
                        TokenKind.MINUS, List.of(value.expression()), inferred.orElseThrow(), span)) {
                    return null;
                }
                return result(node(
                        TypedExpressionKind.OPERATOR,
                        span,
                        inferred.orElseThrow(),
                        List.of(value.expression()),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(operator.spelling()),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), List.of(), Optional.empty()));
            }
            ExprResult value = checkExpression(operand, Optional.empty(), moduleId);
            if (value == null) {
                return null;
            }
            if (!isNumericType(value.type()) || value.type().isNilable()) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_OPERATOR,
                        operand.span(), "unary minus requires a non-nil numeric operand");
                return null;
            }
            PrimitiveType expectedPrimitive = expectedNumericPrimitive(expected);
            if (expectedPrimitive != null
                    && value.type().withoutQualifiers() != expectedPrimitive) {
                value = checkExpression(operand, Optional.of(expectedPrimitive), moduleId);
                if (value == null) {
                    return null;
                }
            }
            LyraType resultType = value.type().withoutQualifiers();
            ExprResult operandValue = coerce(value, resultType, operand.span());
            if (operandValue == null) {
                return null;
            }
            if (!validateConstantIntegerOperation(
                    TokenKind.MINUS,
                    List.of(operandValue.expression()),
                    (PrimitiveType) resultType,
                    span)
                    || !validateConstantFloatingOperation(
                    TokenKind.MINUS,
                    List.of(operandValue.expression()),
                    (PrimitiveType) resultType,
                    span)) {
                return null;
            }
            return result(node(
                    TypedExpressionKind.OPERATOR,
                    span,
                    resultType,
                    List.of(operandValue.expression()),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(operator.spelling()),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), List.of(), Optional.empty()));
        }

        private boolean validateConstantIntegerOperation(
                TokenKind operator,
                List<TypedExpression> operands,
                PrimitiveType operandType,
                SourceSpan span) {
            if (!operandType.isInteger()) {
                return true;
            }
            List<BigInteger> values = new ArrayList<>();
            for (TypedExpression operand : operands) {
                BigInteger value = constantIntegerValue(operand);
                if (value == null) {
                    return true;
                }
                values.add(value);
            }
            var domain = operandType.numericDomain().orElseThrow();
            BigInteger result;
            switch (operator) {
                case PLUS -> {
                    result = BigInteger.ZERO;
                    for (BigInteger value : values) {
                        result = result.add(value);
                        if (!domain.contains(result)) {
                            return constantArithmeticFailure(span,
                                    "constant integer addition overflows " + operandType);
                        }
                    }
                }
                case MINUS -> {
                    result = values.getFirst();
                    if (values.size() == 1) {
                        result = result.negate();
                        if (!domain.contains(result)) {
                            return constantArithmeticFailure(span,
                                    "constant integer negation overflows or underflows " + operandType);
                        }
                    } else {
                        for (BigInteger value : values.subList(1, values.size())) {
                            result = result.subtract(value);
                            if (!domain.contains(result)) {
                                return constantArithmeticFailure(span,
                                        "constant integer subtraction overflows or underflows " + operandType);
                            }
                        }
                    }
                }
                case ASTERISK -> {
                    result = BigInteger.ONE;
                    for (BigInteger value : values) {
                        result = result.multiply(value);
                        if (!domain.contains(result)) {
                            return constantArithmeticFailure(span,
                                    "constant integer multiplication overflows " + operandType);
                        }
                    }
                }
                case SLASH -> {
                    int firstDivisor = values.size() == 1 ? 0 : 1;
                    for (int index = firstDivisor; index < values.size(); index++) {
                        if (values.get(index).signum() == 0) {
                            return constantArithmeticFailure(span,
                                    "constant integer division by zero");
                        }
                    }
                }
                case PERCENT -> {
                    if (values.get(1).signum() == 0) {
                        return constantArithmeticFailure(span,
                                "constant integer remainder by zero");
                    }
                    result = values.getFirst().remainder(values.get(1));
                    if (!domain.contains(result)) {
                        return constantArithmeticFailure(span,
                                "constant integer remainder is outside " + operandType);
                    }
                }
                case CARET -> {
                    BigInteger exponent = values.get(1);
                    if (exponent.signum() < 0) {
                        return constantArithmeticFailure(span,
                                "constant integer exponent must not be negative");
                    }
                    result = exactIntegerPower(values.getFirst(), exponent);
                    if (result == null || !domain.contains(result)) {
                        return constantArithmeticFailure(span,
                                "constant integer power overflows " + operandType);
                    }
                }
                case INCREMENT -> {
                    result = values.getFirst().add(BigInteger.ONE);
                    if (!domain.contains(result)) {
                        return constantArithmeticFailure(span,
                                "constant integer increment overflows " + operandType);
                    }
                }
                case DECREMENT -> {
                    result = values.getFirst().subtract(BigInteger.ONE);
                    if (!domain.contains(result)) {
                        return constantArithmeticFailure(span,
                                "constant integer decrement underflows " + operandType);
                    }
                }
                default -> {
                    return true;
                }
            }
            return true;
        }

        private boolean constantArithmeticFailure(SourceSpan span, String summary) {
            fail(CompilerDiagnosticCodes.TYPE_INVALID_OPERATOR, span, summary);
            return false;
        }

        private static BigInteger exactIntegerPower(BigInteger base, BigInteger exponent) {
            if (exponent.signum() < 0) {
                return null;
            }
            if (exponent.signum() == 0) {
                return BigInteger.ONE;
            }
            if (base.signum() == 0) {
                return BigInteger.ZERO;
            }
            if (base.equals(BigInteger.ONE)) {
                return BigInteger.ONE;
            }
            if (base.equals(BigInteger.ONE.negate())) {
                return exponent.testBit(0) ? BigInteger.ONE.negate() : BigInteger.ONE;
            }
            // Every current integer result has at most 64 bits.  For |base| >= 2,
            // an exponent above 64 cannot fit any current integer domain.  This
            // bound avoids enormous allocation while retaining exact arithmetic.
            if (exponent.compareTo(BigInteger.valueOf(64)) > 0) {
                return null;
            }
            return base.pow(exponent.intValueExact());
        }

        private static BigInteger constantIntegerValue(TypedExpression expression) {
            if (expression.type().isInteger()
                    && expression.literal().orElse(null)
                    instanceof TypedLiteralValue.IntegerValue integer) {
                return integer.exactValue().integerValue();
            }
            if (expression.kind() == TypedExpressionKind.CONVERSION
                    && expression.type().isInteger()
                    && expression.children().size() == 1) {
                TypedExpression operand = expression.children().getFirst();
                BigInteger integer = constantIntegerValue(operand);
                if (integer != null) {
                    return integer;
                }
                if (operand.literal().orElse(null)
                        instanceof TypedLiteralValue.IntegerValue literal) {
                    return literal.exactValue().integerValue();
                }
                if (operand.literal().orElse(null)
                        instanceof TypedLiteralValue.DecimalValue literal) {
                    try {
                        return literal.exactValue().decimalValue().toBigIntegerExact();
                    } catch (ArithmeticException notIntegral) {
                        return null;
                    }
                }
                return null;
            }
            if (expression.kind() != TypedExpressionKind.OPERATOR
                    || !expression.type().isInteger()
                    || expression.operator().isEmpty()) {
                return null;
            }
            List<BigInteger> values = new ArrayList<>();
            for (TypedExpression child : expression.children()) {
                BigInteger value = constantIntegerValue(child);
                if (value == null) {
                    return null;
                }
                values.add(value);
            }
            try {
                return switch (expression.operator().orElseThrow()) {
                    case "+" -> values.stream().reduce(BigInteger.ZERO, BigInteger::add);
                    case "*" -> values.stream().reduce(BigInteger.ONE, BigInteger::multiply);
                    case "-" -> values.size() == 1
                            ? values.getFirst().negate()
                            : values.subList(1, values.size()).stream()
                            .reduce(values.getFirst(), BigInteger::subtract);
                    case "%" -> values.getFirst().remainder(values.get(1));
                    case "^" -> exactIntegerPower(values.getFirst(), values.get(1));
                    case "++" -> values.getFirst().add(BigInteger.ONE);
                    case "--" -> values.getFirst().subtract(BigInteger.ONE);
                    default -> null;
                };
            } catch (ArithmeticException invalidConstant) {
                return null;
            }
        }

        private boolean validateConstantFloatingOperation(
                TokenKind operator,
                List<TypedExpression> operands,
                PrimitiveType resultType,
                SourceSpan span) {
            if (!resultType.isFloating()) {
                return true;
            }
            List<Double> values = new ArrayList<>();
            for (TypedExpression operand : operands) {
                Double value = constantFloatingValue(operand);
                if (value == null) {
                    return true;
                }
                values.add(value);
            }
            Double result = evaluateFloating(operator, values, resultType == PrimitiveType.F32);
            if (result == null || !Double.isFinite(result)) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_OPERATOR,
                        span, "constant floating operation produces a non-finite result");
                return false;
            }
            return true;
        }

        private static Double constantFloatingValue(TypedExpression expression) {
            if (expression.literal().orElse(null) instanceof TypedLiteralValue.IntegerValue integer) {
                return integer.exactValue().integerValue().doubleValue();
            }
            if (expression.literal().orElse(null) instanceof TypedLiteralValue.DecimalValue decimal) {
                return decimal.exactValue().decimalValue().doubleValue();
            }
            if (expression.kind() == TypedExpressionKind.CONVERSION
                    && expression.children().size() == 1) {
                Double value = constantFloatingValue(expression.children().getFirst());
                if (value == null) {
                    return null;
                }
                return expression.type().withoutQualifiers() == PrimitiveType.F32
                        ? (double) value.floatValue() : value;
            }
            if (expression.kind() == TypedExpressionKind.OPERATOR
                    && expression.operator().isPresent()
                    && expression.type().withoutQualifiers() instanceof PrimitiveType primitive
                    && primitive.isFloating()) {
                List<Double> values = new ArrayList<>();
                for (TypedExpression child : expression.children()) {
                    Double value = constantFloatingValue(child);
                    if (value == null) {
                        return null;
                    }
                    values.add(value);
                }
                return evaluateFloating(
                        floatingToken(expression.operator().orElseThrow()),
                        values, primitive == PrimitiveType.F32);
            }
            if (expression.type().isInteger()) {
                BigInteger integer = constantIntegerValue(expression);
                return integer == null ? null : integer.doubleValue();
            }
            return null;
        }

        private static TokenKind floatingToken(String spelling) {
            return switch (spelling) {
                case "+" -> TokenKind.PLUS;
                case "-" -> TokenKind.MINUS;
                case "*" -> TokenKind.ASTERISK;
                case "/" -> TokenKind.SLASH;
                case "^" -> TokenKind.CARET;
                case "++" -> TokenKind.INCREMENT;
                case "--" -> TokenKind.DECREMENT;
                default -> throw new IllegalArgumentException("not a floating operator: " + spelling);
            };
        }

        private static Double evaluateFloating(
                TokenKind operator, List<Double> values, boolean f32) {
            if (values.isEmpty()) {
                return null;
            }
            double result = values.getFirst();
            switch (operator) {
                case PLUS -> {
                    for (int index = 1; index < values.size(); index++) {
                        result = f32 ? (double) ((float) result + values.get(index).floatValue())
                                : result + values.get(index);
                        if (!Double.isFinite(result)) {
                            return result;
                        }
                    }
                }
                case ASTERISK -> {
                    for (int index = 1; index < values.size(); index++) {
                        result = f32 ? (double) ((float) result * values.get(index).floatValue())
                                : result * values.get(index);
                        if (!Double.isFinite(result)) {
                            return result;
                        }
                    }
                }
                case MINUS -> {
                    if (values.size() == 1) {
                        result = f32 ? (double) (-values.getFirst().floatValue()) : -result;
                    } else {
                        for (int index = 1; index < values.size(); index++) {
                            result = f32 ? (double) ((float) result - values.get(index).floatValue())
                                    : result - values.get(index);
                            if (!Double.isFinite(result)) {
                                return result;
                            }
                        }
                    }
                }
                case SLASH -> {
                    if (values.size() == 1) {
                        result = f32 ? (double) (1.0f / values.getFirst().floatValue())
                                : 1.0d / result;
                    } else {
                        for (int index = 1; index < values.size(); index++) {
                            result = f32 ? (double) ((float) result / values.get(index).floatValue())
                                    : result / values.get(index);
                            if (!Double.isFinite(result)) {
                                return result;
                            }
                        }
                    }
                }
                case CARET -> result = f32
                        ? (double) ((float) Math.pow(values.getFirst().floatValue(),
                        values.get(1).floatValue()))
                        : Math.pow(values.getFirst(), values.get(1));
                case INCREMENT -> result = f32
                        ? (double) (values.getFirst().floatValue() + 1.0f) : result + 1.0d;
                case DECREMENT -> result = f32
                        ? (double) (values.getFirst().floatValue() - 1.0f) : result - 1.0d;
                default -> {
                    return null;
                }
            }
            return result;
        }

        private ExprResult checkEquality(
                SyntaxNode.Operator operator,
                List<SyntaxNode.Expression> operands,
                SourceSpan span,
                ModuleId moduleId) {
            if (operands.size() < 2) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_ARITY,
                        span, operator.spelling() + " requires at least two operands");
                return null;
            }
            boolean identity = operator.tokenKind() == TokenKind.IDENTITY_EQUAL
                    || operator.tokenKind() == TokenKind.IDENTITY_NOT_EQUAL;
            if (identity) {
                List<ExprResult> values = new ArrayList<>();
                for (SyntaxNode.Expression operand : operands) {
                    ExprResult value = checkExpression(operand, Optional.empty(), moduleId);
                    if (value == null) {
                        return null;
                    }
                    values.add(value);
                }
                if (values.stream().anyMatch(value -> value.type().isNilable())) {
                    fail(CompilerDiagnosticCodes.TYPE_NIL_CONTEXT,
                            span, "nilable values must be narrowed before identity comparison");
                    return null;
                }
                LyraType first = values.getFirst().type().withoutQualifiers();
                if (!identityBearing(first)
                        || values.stream().anyMatch(value -> !value.type().withoutQualifiers().equals(first))) {
                    fail(CompilerDiagnosticCodes.TYPE_INVALID_OPERATOR,
                            span, "identity equality requires one identical identity-bearing type");
                    return null;
                }
                return result(node(
                        TypedExpressionKind.OPERATOR,
                        span,
                        PrimitiveType.BOOL,
                        values.stream().map(ExprResult::expression).toList(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(operator.spelling()),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), List.of(), Optional.empty()));
            }

            List<Optional<LyraType>> shapes = new ArrayList<>();
            List<ExprResult> initial = new ArrayList<>();
            List<SyntaxNode.Expression> unresolved = new ArrayList<>();
            for (SyntaxNode.Expression operand : operands) {
                Optional<LyraType> shape = synthesizeTypeWithoutContext(operand, moduleId);
                if (failed()) {
                    return null;
                }
                shapes.add(shape.map(State::removeMutableQualifier));
                if (shape.isEmpty()) {
                    unresolved.add(operand);
                    initial.add(null);
                    continue;
                }
                ExprResult value = checkExpression(operand, Optional.empty(), moduleId);
                if (value == null) {
                    return null;
                }
                initial.add(value);
            }
            List<LyraType> known = shapes.stream().flatMap(Optional::stream).toList();
            boolean truthEquality = known.stream().anyMatch(value ->
                    value.withoutQualifiers() == PrimitiveType.BOOL)
                    && known.stream().allMatch(State::truthTestable)
                    && unresolved.stream().allMatch(SyntaxNode.NilLiteral.class::isInstance);
            if (truthEquality) {
                List<ExprResult> values = new ArrayList<>();
                for (int index = 0; index < operands.size(); index++) {
                    ExprResult value = initial.get(index);
                    if (value == null) {
                        value = checkExpression(operands.get(index),
                                Optional.of(PrimitiveType.BOOL.nilable()), moduleId);
                    }
                    if (value == null) {
                        return null;
                    }
                    values.add(value);
                }
                return result(node(
                        TypedExpressionKind.OPERATOR,
                        span,
                        PrimitiveType.BOOL,
                        values.stream().map(ExprResult::expression).toList(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(operator.spelling()),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), List.of(), Optional.empty()));
            }
            if (known.isEmpty()) {
                Optional<LyraType> folded = StructuralContextPlan.synthesizePeers(
                        operands,
                        expression -> synthesizeAtomicTypeWithoutContext(expression, moduleId),
                        type -> Optional.ofNullable(typeFromSyntax(type, TypePosition.BINDING)),
                        expression -> synthesizeControlResult(expression, moduleId));
                if (folded.isPresent()) {
                    List<ExprResult> values = new ArrayList<>();
                    for (int index = 0; index < operands.size(); index++) {
                        ExprResult value = checkExpression(
                                operands.get(index), Optional.of(folded.orElseThrow()), moduleId);
                        if (value == null) {
                            return null;
                        }
                        values.add(value);
                    }
                    return result(node(
                            TypedExpressionKind.OPERATOR,
                            span,
                            PrimitiveType.BOOL,
                            values.stream().map(ExprResult::expression).toList(),
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(operator.spelling()),
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.empty(), List.of(), Optional.empty()));
                }
                fail(CompilerDiagnosticCodes.TYPE_NIL_CONTEXT,
                        span, "#NIL equality needs a non-nil operand to establish its base type");
                return null;
            }
            if (unresolved.isEmpty() && initial.stream()
                    .anyMatch(value -> value.type().isNilable())) {
                fail(CompilerDiagnosticCodes.TYPE_NIL_CONTEXT,
                        span, "nilable values may only be compared with #NIL before narrowing");
                return null;
            }
            if (unresolved.isEmpty() && initial.stream().allMatch(value ->
                    value.type().withoutQualifiers() instanceof FunctionType)) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_OPERATOR,
                        span, "function values require identity equality (eq?) rather than value equality");
                return null;
            }

            LyraType common;
            if (known.stream().allMatch(State::isNumericType)) {
                List<SyntaxNode.Expression> numericSyntax = new ArrayList<>();
                List<ExprResult> numericValues = new ArrayList<>();
                for (int index = 0; index < operands.size(); index++) {
                    if (shapes.get(index).isEmpty()) {
                        continue;
                    }
                    numericSyntax.add(operands.get(index));
                    ExprResult value = initial.get(index);
                    numericValues.add(new ExprResult(
                            value.type().withoutQualifiers(), value.expression()));
                }
                common = numericCommon(numericSyntax, numericValues).orElse(null);
                if (common == null) {
                    fail(CompilerDiagnosticCodes.TYPE_NO_COMMON_NUMERIC_TYPE,
                            span, "equality operands have no losslessly common numeric type");
                    return null;
                }
            } else if (known.stream().anyMatch(State::isNumericType)) {
                fail(CompilerDiagnosticCodes.TYPE_INVALID_OPERATOR,
                        span, "equality operands do not have compatible types");
                return null;
            } else {
                common = StructuralContextPlan.foldHomogeneous(known)
                        .orElse(null);
                if (common == null) {
                    fail(CompilerDiagnosticCodes.TYPE_INVALID_OPERATOR,
                            span, "equality operands do not have compatible types");
                    return null;
                }
            }
            common = removeMutableQualifier(common);
            LyraType target = common;
            for (SyntaxNode.Expression nilOperand : unresolved) {
                Optional<LyraType> context = StructuralContextPlan.expectedFromPeer(nilOperand, common);
                if (context.isEmpty()) {
                    fail(CompilerDiagnosticCodes.TYPE_NIL_CONTEXT,
                            nilOperand.span(), "#NIL equality needs a compatible peer shape");
                    return null;
                }
                target = StructuralContextPlan.mergeNilShape(target, context.orElseThrow());
            }

            List<ExprResult> values = new ArrayList<>();
            for (int index = 0; index < operands.size(); index++) {
                ExprResult value = initial.get(index);
                if (value == null) {
                    Optional<LyraType> context = StructuralContextPlan.expectedFor(
                            StructuralContextPlan.ChildPosition.equalityOperand(index),
                            operands.get(index), Optional.empty(), Optional.of(common));
                    value = checkExpression(
                            operands.get(index), Optional.of(context.orElse(target)), moduleId);
                } else if (!value.type().equals(target)) {
                    value = contextualBranch(operands.get(index), value, target, moduleId);
                }
                if (value == null) {
                    return null;
                }
                values.add(value);
            }
            return result(node(
                    TypedExpressionKind.OPERATOR,
                    span,
                    PrimitiveType.BOOL,
                    values.stream().map(ExprResult::expression).toList(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(operator.spelling()),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), List.of(), Optional.empty()));
        }

        private boolean identityBearing(LyraType type) {
            LyraType base = type.withoutQualifiers();
            if (base instanceof FunctionType || base instanceof ArrayType) return true;
            if (!(base instanceof NominalType nominal)) return false;
            return graph.nominals().stream()
                    .filter(value -> value.schema().type().equals(nominal))
                    .map(value -> value.schema().kind())
                    .anyMatch(io.mindspice.lyra.compiler.types.NominalSchema.Kind.CLASS::equals);
        }

        private NumericOperands numericOperands(
                List<SyntaxNode.Expression> operands,
                Optional<LyraType> expected,
                TokenKind operator,
                ModuleId moduleId) {
            List<NumericInput> inputs = new ArrayList<>();
            boolean allDirectLiterals = true;
            boolean allLiteralInputsUnforced = true;
            boolean allInteger = true;
            for (SyntaxNode.Expression operand : operands) {
                ExactNumericLiteral literal = exactNumericLiteral(operand);
                if (literal != null) {
                    inputs.add(NumericInput.literal(operand, literal));
                    allLiteralInputsUnforced &= literal.forcedType().isEmpty();
                    allInteger &= literal.isInteger();
                    continue;
                }
                allDirectLiterals = false;
                ExprResult value = checkExpression(operand, Optional.empty(), moduleId);
                if (value == null) {
                    return null;
                }
                if (!isNumericType(value.type()) || value.type().isNilable()) {
                    fail(CompilerDiagnosticCodes.TYPE_INVALID_OPERATOR,
                            operand.span(), "numeric operator operand is not a non-nil numeric value");
                    return null;
                }
                PrimitiveType primitive = (PrimitiveType) value.type().withoutQualifiers();
                inputs.add(NumericInput.value(operand, value, primitive));
                allInteger &= primitive.isInteger();
            }

            boolean integerDivision = operator == TokenKind.SLASH && allInteger;
            PrimitiveType contextualType = integerDivision
                    ? null : expectedNumericPrimitive(expected);
            if (contextualType != null) {
                List<NumericInput> contextualInputs = new ArrayList<>(inputs.size());
                for (NumericInput input : inputs) {
                    if (input.literal != null || input.primitive == contextualType) {
                        contextualInputs.add(input);
                        continue;
                    }
                    ExprResult value = checkExpression(
                            input.syntax, Optional.of(contextualType), moduleId);
                    if (value == null) {
                        return null;
                    }
                    contextualInputs.add(NumericInput.value(
                            input.syntax, value,
                            (PrimitiveType) value.type().withoutQualifiers()));
                }
                inputs = contextualInputs;
            }

            List<NumericInput> selectedInputs = List.copyOf(inputs);
            boolean integerOnly = operator == TokenKind.PERCENT || integerDivision;
            List<PrimitiveType> candidates = new ArrayList<>();
            for (PrimitiveType candidate : NUMERIC_CANDIDATES) {
                if (integerOnly && !candidate.isInteger()) {
                    continue;
                }
                boolean fits = true;
                for (NumericInput input : selectedInputs) {
                    if (input.literal != null) {
                        if (input.literal.forcedType().isPresent()) {
                            PrimitiveType forced = input.literal.forcedType().orElseThrow();
                            fits &= LiteralTyping.representableAs(input.literal, forced)
                                    && TypeRules.canImplicitlyWiden(forced, candidate);
                        } else {
                            fits &= LiteralTyping.representableAs(input.literal, candidate);
                        }
                    } else {
                        fits &= TypeRules.canImplicitlyWiden(input.primitive, candidate);
                    }
                    if (!fits) {
                        break;
                    }
                }
                if (fits) {
                    candidates.add(candidate);
                }
            }
            if (candidates.isEmpty()) {
                fail(CompilerDiagnosticCodes.TYPE_NO_COMMON_NUMERIC_TYPE,
                        operands.getFirst().span(), "numeric operands have no losslessly common type");
                return null;
            }
            PrimitiveType common;
            if (contextualType != null && candidates.contains(contextualType)) {
                common = contextualType;
            } else if (allDirectLiterals && allLiteralInputsUnforced && contextualType == null) {
                boolean decimal = selectedInputs.stream().anyMatch(input -> input.literal != null
                        && input.literal.isDecimal());
                PrimitiveType defaultType = decimal ? PrimitiveType.F64 : PrimitiveType.I64;
                if (candidates.contains(defaultType)) {
                    common = defaultType;
                } else {
                    common = candidates.getFirst();
                }
            } else {
                common = candidates.getFirst();
            }
            List<TypedExpression> typedExpressions = new ArrayList<>();
            for (NumericInput input : selectedInputs) {
                ExprResult value = input.value;
                if (input.literal != null) {
                    value = checkExpression(input.syntax, Optional.of(common), moduleId);
                } else {
                    value = coerce(value, common, input.syntax.span());
                }
                if (value == null) {
                    return null;
                }
                typedExpressions.add(value.expression());
            }
            return new NumericOperands(common, allInteger, List.copyOf(typedExpressions));
        }

        private static PrimitiveType expectedNumericPrimitive(Optional<LyraType> expected) {
            return expected.map(LyraType::withoutQualifiers)
                    .filter(PrimitiveType.class::isInstance)
                    .map(PrimitiveType.class::cast)
                    .filter(PrimitiveType::isNumeric)
                    .orElse(null);
        }

        private ExprResult coerce(ExprResult value, LyraType target, SourceSpan span) {
            if (value.type().equals(target)) {
                return value;
            }
            ConversionDecision decision = TypeRules.implicitConversion(value.type(), target);
            if (!decision.allowed()) {
                fail(CompilerDiagnosticCodes.TYPE_MISMATCH,
                        span, "cannot use " + value.type().canonicalSpelling()
                                + " where " + target.canonicalSpelling() + " is required");
                return null;
            }
            ExprResult current = value;
            for (ConversionStep step : decision.steps()) {
                LyraType next = applyStep(current.type(), target, step);
                if (next.equals(current.type())) {
                    continue;
                }
                TypedConversion record = new TypedConversion(
                        span, current.type(), next, ConversionKind.IMPLICIT, step);
                current = result(node(
                        TypedExpressionKind.CONVERSION,
                        span,
                        next,
                        List.of(current.expression()),
                        Optional.empty(), Optional.empty(), Optional.of(record), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), List.of(), Optional.empty()));
            }
            if (!current.type().equals(target)) {
                LyraType next = target;
                TypedConversion record = new TypedConversion(
                        span, current.type(), next, ConversionKind.IMPLICIT, ConversionStep.NUMERIC_WIDENING);
                current = result(node(
                        TypedExpressionKind.CONVERSION,
                        span,
                        next,
                        List.of(current.expression()),
                        Optional.empty(), Optional.empty(), Optional.of(record), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), List.of(), Optional.empty()));
            }
            return current;
        }

        private LyraType applyStep(LyraType current, LyraType target, ConversionStep step) {
            return switch (step) {
                case NIL_LIFT -> addQualifier(current, TypeQualifier.NIL);
                case MUTABILITY_DROP -> removeQualifier(current, TypeQualifier.MUT);
                case NUMERIC_WIDENING -> withTargetQualifiers(
                        ((PrimitiveType) current.withoutQualifiers()).numericDomain().isPresent()
                                ? target.withoutQualifiers()
                                : target,
                        current,
                        target);
                case NUMERIC_EXPLICIT, TEXT_EXPLICIT -> target;
            };
        }

        private LyraType withTargetQualifiers(LyraType widened, LyraType current, LyraType target) {
            LyraType result = widened;
            if (current.hasQualifier(TypeQualifier.MUT) && target.hasQualifier(TypeQualifier.MUT)) {
                result = result.withQualifier(TypeQualifier.MUT);
            }
            if (current.hasQualifier(TypeQualifier.NIL) && target.hasQualifier(TypeQualifier.NIL)) {
                result = result.withQualifier(TypeQualifier.NIL);
            }
            return result;
        }

        private TypedReference typeReference(ResolvedReference reference, ModuleId moduleId) {
            TypedReference existing = typedReferences.get(reference.id());
            if (existing != null) {
                return existing;
            }
            Optional<LyraType> type = Optional.empty();
            if (reference.kind() != ReferenceKind.MODULE_NAMESPACE) {
                DeclarationId targetId = reference.targetDeclaration().orElse(null);
                if (targetId == null) {
                    fail(CompilerDiagnosticCodes.TYPE_UNRESOLVED_LINK,
                            reference.span(), "value reference has no resolved declaration identity");
                    return null;
                }
                ResolvedDeclaration declaration = declarations.get(targetId);
                if (declaration == null) {
                    fail(CompilerDiagnosticCodes.TYPE_UNRESOLVED_LINK,
                            reference.span(), "reference target declaration is absent from the graph");
                    return null;
                }
                BindingContract contract = typedContracts.get(targetId);
                LyraType valueType = contract == null
                        ? null
                        : expressionTypeForUse(declaration, contract.valueType(), moduleId);
                if (valueType == null) {
                    fail(CompilerDiagnosticCodes.TYPE_UNTYPED_EXPRESSION,
                            reference.span(), "reference target does not have a complete type");
                    return null;
                }
                type = Optional.of(valueType);
            }
            TypedReference created = new TypedReference(
                    reference.id(),
                    reference.name(),
                    reference.span(),
                    reference.moduleId(),
                    reference.scopeId(),
                    reference.kind(),
                    type,
                    reference.targetDeclaration(),
                    reference.targetModule(),
                    reference.targetExport(),
                    reference.fromLambda(),
                    reference.capture());
            typedReferences.put(reference.id(), created);
            return created;
        }

        private TypedLink link(ResolvedReference reference, Optional<AccessKind> accessKind) {
            return new TypedLink(
                    Optional.of(reference.id()),
                    reference.targetDeclaration(),
                    reference.targetModule(),
                    reference.targetExport(),
                    accessKind);
        }

        private ResolvedReference findReference(
                SourceSpan span,
                String name,
                ReferenceKind kind) {
            for (ResolvedReference reference : graph.references()) {
                if (reference.span().equals(span)
                        && reference.kind() == kind
                        && reference.name().equals(name)) {
                    return reference;
                }
            }
            fail(CompilerDiagnosticCodes.TYPE_UNRESOLVED_LINK,
                    span, "typed expression has no matching resolved reference");
            return null;
        }

        private ResolvedReference findReferenceByKind(SourceSpan span, ReferenceKind kind) {
            for (ResolvedReference reference : graph.references()) {
                if (reference.span().equals(span) && reference.kind() == kind) {
                    return reference;
                }
            }
            fail(CompilerDiagnosticCodes.TYPE_UNRESOLVED_LINK,
                    span, "typed namespace access has no module reference");
            return null;
        }

        private DeclarationId declarationIdAt(SourceSpan span, DeclarationKind kind) {
            return graph.declarations().stream()
                    .filter(value -> value.nameSpan().equals(span) && value.kind() == kind)
                    .map(ResolvedDeclaration::id)
                    .findFirst()
                    .orElse(null);
        }

        private LambdaId lambdaIdAt(SourceSpan span) {
            return graph.lambdas().stream()
                    .filter(value -> value.span().equals(span))
                    .map(ResolvedLambda::id)
                    .findFirst()
                    .orElse(null);
        }

        private TypedExpression node(
                TypedExpressionKind kind,
                SourceSpan span,
                LyraType type,
                List<TypedExpression> children,
                Optional<TypedLiteralValue> literal,
                Optional<TypedLink> link,
                Optional<TypedConversion> conversion,
                Optional<String> operator,
                Optional<String> memberName,
                Optional<BigInteger> tupleIndex,
                Optional<DeclarationId> declarationId,
                Optional<LambdaId> lambdaId,
                Optional<ScopeId> scopeId,
                Optional<LyraSignature> signature,
                List<io.mindspice.lyra.compiler.identity.CaptureId> captureIds,
                Optional<DeclarationId> predicateBinding) {
            return new TypedExpression(
                    kind, span, type, children, literal, link, conversion, operator,
                    memberName, tupleIndex, declarationId, lambdaId, scopeId, signature,
                    captureIds, predicateBinding, Optional.empty());
        }

        private TypedExpression matchNode(
                TypedExpressionKind kind,
                SourceSpan span,
                LyraType type,
                List<TypedExpression> children,
                TypedMatch match) {
            return new TypedExpression(
                    kind, span, type, children,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.of(match));
        }

        private ExprResult result(TypedExpression expression) {
            return new ExprResult(expression.type(), expression);
        }

        private TypedSemanticGraph freeze() {
            for (ResolvedDeclaration declaration : graph.declarations()) {
                if (!typedDeclarations.containsKey(declaration.id())) {
                    Optional<BindingContract> contract = Optional.ofNullable(typedContracts.get(declaration.id()));
                    Optional<TypedExpression> initializer = Optional.ofNullable(initializers.get(declaration.id()));
                    if (declaration.kind() == DeclarationKind.LET && initializer.isEmpty()) {
                        throw new IllegalStateException("let declaration was not type checked: " + declaration.id());
                    }
                    typedDeclarations.put(declaration.id(), new TypedDeclaration(
                            declaration.id(),
                            declaration.name(),
                            declaration.span(),
                            declaration.moduleId(),
                            declaration.kind(),
                            contract,
                            initializer,
                            declaration.initializerLambda()));
                }
            }
            for (ResolvedLambda lambda : graph.lambdas()) {
                if (!typedLambdas.containsKey(lambda.id())) {
                    throw new IllegalStateException("lambda was not type checked: " + lambda.id());
                }
            }
            for (ResolvedReference reference : graph.references()) {
                if (!typedReferences.containsKey(reference.id())) {
                    if (reference.kind() == ReferenceKind.MODULE_NAMESPACE) {
                        typeReference(reference, reference.moduleId());
                    } else {
                        throw new IllegalStateException("reference was not type checked: " + reference.id());
                    }
                }
            }
            Map<DeclarationId, BindingContract> contracts = new LinkedHashMap<>(typedContracts);
            List<TypedExpression> publishedExpressions = new ArrayList<>();
            List<TypedConversion> publishedConversions = new ArrayList<>();
            Map<SourceSpan, List<TypedExpression>> bySpan = new LinkedHashMap<>();
            IdentityHashMap<TypedExpression, Boolean> seen = new IdentityHashMap<>();
            for (TypedModule module : typedModules.values()) {
                for (TypedExpression form : module.forms()) {
                    collectPublishedExpression(
                            form, seen, publishedExpressions, publishedConversions, bySpan);
                }
            }
            TypedSemanticCore core = new TypedSemanticCore(
                    graph,
                    new ArrayList<>(typedModules.values()),
                    new ArrayList<>(typedDeclarations.values()),
                    new ArrayList<>(typedReferences.values()),
                    new ArrayList<>(typedLambdas.values()),
                    publishedConversions,
                    publishedExpressions,
                    contracts,
                    bySpan,
                    graph.mutations().stream().map(TypedMutation::from).toList(),
                    TypedFailureSite.fromExpressions(publishedExpressions));

            // This is the one canonical typed flow/eager evaluation.  The
            // planner and sealer below consume its immutable result and never
            // reconstruct or rerun the evaluator.
            SemanticFlowAnalyzer.PublicationResult flowResult =
                    SemanticFlowAnalyzer.analyzeForPublication(core, attachableBoundary);
            if (flowResult instanceof SemanticFlowAnalyzer.PublicationDiagnosticFailure failure) {
                Diagnostic ownership = failure.diagnostic();
                if (deferredMutableArgumentDiagnostic != null
                        && precedes(
                        deferredMutableArgumentDiagnostic.primarySpan(),
                        ownership.primarySpan())) {
                    fail(deferredMutableArgumentDiagnostic);
                } else {
                    fail(ownership);
                }
                return null;
            }
            if (flowResult instanceof SemanticFlowAnalyzer.PublicationFailure failure) {
                throw new IllegalStateException(
                        "canonical semantic flow analysis failed: " + failure.failure().kind()
                                + ": " + failure.failure().message());
            }
            SemanticFlowAnalyzer.PublicationSuccess success =
                    (SemanticFlowAnalyzer.PublicationSuccess) flowResult;
            if (deferredMutableArgumentDiagnostic != null) {
                fail(deferredMutableArgumentDiagnostic);
                return null;
            }
            core = success.core();
            io.mindspice.lyra.compiler.semantic.flow.SemanticFlowFacts facts = success.facts();
            InitializationAnalyzer.Analysis analysis = InitializationAnalyzer.plan(core, facts);
            if (analysis.firstCycle().isPresent()) {
                InitializationCycle cycle = analysis.firstCycle().orElseThrow();
                List<RelatedSpan> related = cycle.dependencies().stream()
                        .skip(1)
                        .map(dependency -> RelatedSpan.of(
                                dependency.effectSpan(), "eager dependency in initialization cycle"))
                        .toList();
                fail(CompilerDiagnosticCodes.MODULE_EAGER_INITIALIZATION_CYCLE,
                        cycle.primarySpan(),
                        "eager module initialization cycle: "
                                + cycle.modules().stream().map(ModuleId::toString).toList(),
                        related);
                return null;
            }
            return TypedSemanticGraph.seal(core, facts, analysis.plan());
        }

        private static void collectPublishedExpression(
                TypedExpression expression,
                IdentityHashMap<TypedExpression, Boolean> seen,
                List<TypedExpression> publishedExpressions,
                List<TypedConversion> publishedConversions,
                Map<SourceSpan, List<TypedExpression>> expressionsBySpan) {
            if (seen.put(expression, Boolean.TRUE) != null) {
                throw new IllegalStateException("typed expression tree contains shared or cyclic nodes");
            }
            publishedExpressions.add(expression);
            expressionsBySpan.computeIfAbsent(expression.span(), ignored -> new ArrayList<>())
                    .add(expression);
            expression.conversion().ifPresent(publishedConversions::add);
            for (TypedExpression child : expression.children()) {
                collectPublishedExpression(
                        child, seen, publishedExpressions, publishedConversions, expressionsBySpan);
            }
        }

        private boolean failed() {
            return diagnostic != null;
        }

        private Diagnostic diagnostic() {
            return diagnostic;
        }

        private boolean precedes(SourceSpan left, SourceSpan right) {
            if (left.equals(right)) {
                return false;
            }
            int leftSource = sourceOrder(left);
            int rightSource = sourceOrder(right);
            if (leftSource != rightSource) {
                return leftSource < rightSource;
            }
            return left.startOffset() < right.startOffset()
                    || left.startOffset() == right.startOffset()
                    && left.endOffset() < right.endOffset();
        }

        private int sourceOrder(SourceSpan span) {
            for (int index = 0; index < graph.modules().size(); index++) {
                if (graph.modules().get(index).moduleId().sourceId()
                        .equals(span.sourceId())) {
                    return index;
                }
            }
            return Integer.MAX_VALUE;
        }

        private void fail(Diagnostic value) {
            if (diagnostic == null) {
                diagnostic = Objects.requireNonNull(value, "value");
            }
        }

        private void fail(io.mindspice.lyra.compiler.diagnostic.DiagnosticCode code,
                          SourceSpan span,
                          String message) {
            if (diagnostic == null) {
                diagnostic = Diagnostic.error(code, span, message);
            }
        }

        private void fail(io.mindspice.lyra.compiler.diagnostic.DiagnosticCode code,
                          SourceSpan span,
                          String message,
                          List<io.mindspice.lyra.compiler.diagnostic.RelatedSpan> related) {
            if (diagnostic == null) {
                diagnostic = Diagnostic.error(code, span, message, related);
            }
        }

        private static LyraType expressionType(ResolvedDeclaration declaration, LyraType valueType) {
            return expressionTypeForUse(declaration, valueType, declaration.moduleId());
        }

        private static LyraType expressionTypeForUse(
                ResolvedDeclaration declaration, LyraType valueType, ModuleId useModule) {
            if (declaration.bindingMutability() == BindingMutability.MUTABLE
                    && !declaration.imported()
                    && declaration.kind() != DeclarationKind.IMPORT_MODULE
                    && declaration.moduleId().equals(useModule)) {
                return valueType.withQualifier(TypeQualifier.MUT);
            }
            return valueType;
        }

        private static LyraType removeMutableQualifier(LyraType type) {
            return removeQualifier(type, TypeQualifier.MUT);
        }

        private static LyraType narrowedType(LyraType type) {
            return removeQualifier(type, TypeQualifier.NIL);
        }

        private static LyraType addQualifier(LyraType type, TypeQualifier qualifier) {
            return type.withQualifier(qualifier);
        }

        private static LyraType removeQualifier(LyraType type, TypeQualifier qualifier) {
            LyraType base = type.withoutQualifiers();
            Set<TypeQualifier> qualifiers = EnumSet.noneOf(TypeQualifier.class);
            if (type.hasQualifier(TypeQualifier.MUT) && qualifier != TypeQualifier.MUT) {
                qualifiers.add(TypeQualifier.MUT);
            }
            if (type.hasQualifier(TypeQualifier.NIL) && qualifier != TypeQualifier.NIL) {
                qualifiers.add(TypeQualifier.NIL);
            }
            return base.withQualifiers(qualifiers);
        }

        private static boolean isNumericType(LyraType type) {
            return TypeRules.isNumeric(type);
        }

        private static boolean truthTestable(LyraType type) {
            LyraType base = type.withoutQualifiers();
            return type.isNilable()
                    || base instanceof PrimitiveType
                    || base instanceof ArrayType
                    || base instanceof TupleType
                    || base instanceof FunctionType;
        }


        private static TypedLiteralValue constantValue(TypedExpression expression) {
            if (expression.literal().isPresent()) {
                return expression.literal().orElseThrow();
            }
            BigInteger integerValue = constantIntegerValue(expression);
            if (integerValue != null) {
                return new TypedLiteralValue.IntegerValue(
                        ExactNumericLiteral.integer(integerValue));
            }
            if (expression.kind() == TypedExpressionKind.OPERATOR
                    && expression.operator().orElse("").equals("-")
                    && expression.children().size() == 1) {
                TypedLiteralValue child = constantValue(expression.children().getFirst());
                if (child instanceof TypedLiteralValue.DecimalValue decimal) {
                    return new TypedLiteralValue.DecimalValue(decimal.exactValue().negated());
                }
            }
            if (expression.type().isFloating()) {
                Double value = constantFloatingValue(expression);
                if (value != null && Double.isFinite(value)) {
                    BigDecimal decimal = expression.type().withoutQualifiers() == PrimitiveType.F32
                            ? new BigDecimal(Float.toString(value.floatValue()))
                            : BigDecimal.valueOf(value);
                    return new TypedLiteralValue.DecimalValue(
                            ExactNumericLiteral.decimal(decimal));
                }
            }
            return null;
        }

        private static boolean constantRepresentableAs(
                ExactNumericLiteral literal, PrimitiveType target) {
            if (literal.isInteger()) {
                if (target.isInteger()) {
                    return target.numericDomain().orElseThrow().contains(literal.integerValue());
                }
                if (target == PrimitiveType.F32) {
                    float converted = literal.integerValue().floatValue();
                    return Float.isFinite(converted)
                            && (literal.integerValue().signum() == 0 || converted != 0.0f);
                }
                double converted = literal.integerValue().doubleValue();
                return Double.isFinite(converted)
                        && (literal.integerValue().signum() == 0 || converted != 0.0d);
            }
            if (!target.isInteger()) {
                return LiteralTyping.representableAs(literal, target);
            }
            try {
                return target.numericDomain().orElseThrow().contains(
                        literal.decimalValue().toBigIntegerExact());
            } catch (ArithmeticException notIntegral) {
                return false;
            }
        }

        private static ExactNumericLiteral exactNumericLiteral(SyntaxNode.Expression expression) {
            if (expression instanceof SyntaxNode.IntegerLiteral integer) {
                return ExactNumericLiteral.integer(integer.value(), integer.suffix());
            }
            if (expression instanceof SyntaxNode.FloatLiteral decimal) {
                return ExactNumericLiteral.decimal(decimal.value(), decimal.suffix());
            }
            return null;
        }
    }

    private record ExprResult(LyraType type, TypedExpression expression) {
    }

    private record NumericInput(
            SyntaxNode.Expression syntax,
            ExactNumericLiteral literal,
            ExprResult value,
            PrimitiveType primitive) {
        private static NumericInput literal(SyntaxNode.Expression syntax, ExactNumericLiteral literal) {
            return new NumericInput(syntax, literal, null, null);
        }

        private static NumericInput value(
                SyntaxNode.Expression syntax, ExprResult value, PrimitiveType primitive) {
            return new NumericInput(syntax, null, value, primitive);
        }
    }

    private record NumericOperands(
            PrimitiveType commonType,
            boolean allInteger,
            List<TypedExpression> expressions) {
    }

}
