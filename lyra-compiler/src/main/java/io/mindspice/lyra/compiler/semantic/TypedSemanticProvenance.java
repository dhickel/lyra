package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.ast.SyntaxNode;
import io.mindspice.lyra.compiler.ast.SyntaxProgram;
import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.lex.ModifierKind;
import io.mindspice.lyra.compiler.lex.TokenKind;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.BindingContract;
import io.mindspice.lyra.compiler.types.ConversionKind;
import io.mindspice.lyra.compiler.types.ConversionStep;
import io.mindspice.lyra.compiler.types.ExactNumericLiteral;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.LiteralTyping;
import io.mindspice.lyra.compiler.types.LyraSignature;
import io.mindspice.lyra.compiler.types.LyraType;
import io.mindspice.lyra.compiler.types.PrimitiveType;
import io.mindspice.lyra.compiler.types.TupleType;
import io.mindspice.lyra.compiler.types.TypePosition;
import io.mindspice.lyra.compiler.types.TypeQualifier;
import io.mindspice.lyra.compiler.types.TypeRules;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Proves that a published typed graph is the complete checked image of its resolved syntax graph. */
final class TypedSemanticProvenance {
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

    private final TypedSemanticGraph typed;
    private final ResolvedSemanticGraph resolved;
    private final IdentityHashMap<TypedExpression, Boolean> reachable = new IdentityHashMap<>();
    private final IdentityHashMap<TypedExpression, Boolean> signedMinimumLiterals = new IdentityHashMap<>();
    private final Map<SourceSpan, List<TypedExpression>> reachableBySpan = new LinkedHashMap<>();
    private final List<TypedConversion> reachableConversions = new ArrayList<>();
    private final Map<DeclarationId, TypedExpression> declarationInitializers = new HashMap<>();
    private final Map<LambdaId, TypedExpression> lambdaBodies = new HashMap<>();
    private final Map<DeclarationId, LyraType> sourceDeclarationTypes = new HashMap<>();
    private final Set<DeclarationId> sourceTypeInProgress = new java.util.HashSet<>();

    private TypedSemanticProvenance(TypedSemanticGraph typed) {
        this.typed = Objects.requireNonNull(typed, "typed");
        this.resolved = typed.resolvedGraph();
    }

    static void validate(TypedSemanticGraph typed) {
        new TypedSemanticProvenance(typed).run();
    }

    private void run() {
        validateModulesAndSourceTrees();
        validateDeclarations();
        validateReferences();
        validateLambdas();
        validateCaptureReferenceIndexes();
        validatePublishedTopology();
    }

    private void validateModulesAndSourceTrees() {
        for (TypedModule module : typed.modules()) {
            ResolvedModule resolvedModule = resolved.module(module.moduleId()).orElseThrow(() -> invalid(
                    "typed module is absent from its resolved graph"));
            SyntaxProgram program = resolved.moduleGraph().module(module.moduleId()).orElseThrow().program();
            require(module.rootScope().equals(resolvedModule.rootScope()),
                    "typed module root scope changed after resolution");
            require(module.span().equals(program.span()),
                    "typed module span does not equal its source-program span");
            ResolvedScope rootScope = resolved.scopeTree().scope(module.rootScope()).orElseThrow(() -> invalid(
                    "typed module root scope is absent"));
            require(rootScope.kind() == ScopeKind.MODULE && rootScope.span().equals(program.span()),
                    "typed module root scope kind/span does not match its source program");
            require(module.forms().size() == program.forms().size(),
                    "typed module does not contain exactly one operation per source form");
            for (int index = 0; index < module.forms().size(); index++) {
                TypedExpression form = module.forms().get(index);
                collect(form);
                validateForm(program.forms().get(index), form, module.moduleId());
            }
        }
    }

    private void validateDeclarations() {
        for (TypedDeclaration declaration : typed.declarations()) {
            ResolvedDeclaration source = resolved.declaration(declaration.id()).orElseThrow(() -> invalid(
                    "typed declaration is absent from resolution: " + declaration.id()));
            require(declaration.name().equals(source.name())
                            && declaration.span().equals(source.span())
                            && declaration.moduleId().equals(source.moduleId())
                            && declaration.kind() == source.kind()
                            && declaration.initializerLambda().equals(source.initializerLambda()),
                    "typed declaration metadata changed after resolution: " + declaration.id());
            Optional<BindingContract> indexed = typed.contract(declaration.id());
            require(declaration.contract().equals(indexed),
                    "typed declaration and contract index disagree: " + declaration.id());
            source.effectiveContract().ifPresent(contract -> require(
                    declaration.contract().filter(contract::equals).isPresent(),
                    "declared contract changed during type checking: " + declaration.id()));
            if (source.kind() == DeclarationKind.IMPORT_MODULE) {
                require(declaration.contract().isEmpty(),
                        "module namespace declaration acquired a value contract");
            }

            if (source.kind() == DeclarationKind.LET || source.kind() == DeclarationKind.MEMBER
                    && declarationInitializers.containsKey(source.id())) {
                TypedExpression expected = declarationInitializers.get(source.id());
                require(expected != null && declaration.initializer().orElse(null) == expected,
                        "typed let initializer is not the source declaration child: " + source.id());
                BindingContract contract = declaration.contract().orElseThrow(() -> invalid(
                        "typed let has no complete contract: " + source.id()));
                require(expected.type().equals(contract.valueType()),
                        "typed let initializer does not end at its binding contract: " + source.id());
            } else {
                require(declaration.initializer().isEmpty(),
                        "non-let typed declaration has an initializer");
            }
        }
        for (Map.Entry<DeclarationId, BindingContract> entry : typed.contractsByDeclaration().entrySet()) {
            TypedDeclaration declaration = typed.declaration(entry.getKey()).orElseThrow(() -> invalid(
                    "contract index names an absent typed declaration"));
            require(declaration.contract().filter(entry.getValue()::equals).isPresent(),
                    "contract index and typed declaration disagree");
        }
    }

    private void validateReferences() {
        for (TypedReference reference : typed.references()) {
            ResolvedReference source = resolved.reference(reference.id()).orElseThrow(() -> invalid(
                    "typed reference is absent from resolution: " + reference.id()));
            require(reference.name().equals(source.name())
                            && reference.span().equals(source.span())
                            && reference.moduleId().equals(source.moduleId())
                            && reference.scopeId().equals(source.scopeId())
                            && reference.kind() == source.kind()
                            && reference.targetDeclaration().equals(source.targetDeclaration())
                            && reference.targetModule().equals(source.targetModule())
                            && reference.targetExport().equals(source.targetExport())
                            && reference.fromLambda().equals(source.fromLambda())
                            && reference.capture().equals(source.capture()),
                    "typed reference metadata changed after resolution: " + reference.id());
            if (source.kind() == ReferenceKind.MODULE_NAMESPACE) {
                require(reference.type().isEmpty(),
                        "module namespace reference acquired a value type");
                continue;
            }
            DeclarationId targetId = source.targetDeclaration().orElseThrow(() -> invalid(
                    "value reference has no declaration target"));
            ResolvedDeclaration target = resolved.declaration(targetId).orElse(null);
            BindingContract contract = typed.contract(targetId).orElse(null);
            if (target == null || contract == null) {
                var retained = retainedProducer(targetId, reference.moduleId()).orElseThrow(() -> invalid(
                        "value reference target has no retained producer contract"));
                target = retained.producerGraph().resolvedGraph().declaration(targetId).orElseThrow();
                contract = retained.producerGraph().contract(targetId).orElseThrow();
            }
            LyraType expected = contract.valueType();
            if (target.isMutable() && !target.imported()
                    && target.kind() != DeclarationKind.IMPORT_MODULE
                    && target.moduleId().equals(reference.moduleId())) {
                expected = expected.withQualifier(TypeQualifier.MUT);
            }
            require(reference.type().filter(expected::equals).isPresent(),
                    "typed reference type does not match its declaration contract: " + reference.id());
        }
    }

    private Optional<io.mindspice.lyra.compiler.session.SessionModuleEnvironment.ModuleRecord>
            retainedProducer(DeclarationId declaration, ModuleId referringModule) {
        if (!resolved.isRetained(referringModule)) {
            return Optional.empty();
        }
        return resolved.retainedModules().module(referringModule)
                .filter(record -> record.producerGraph().declaration(declaration).isPresent());
    }

    private void validateLambdas() {
        for (TypedLambda lambda : typed.lambdas()) {
            ResolvedLambda source = resolved.lambda(lambda.id()).orElseThrow(() -> invalid(
                    "typed lambda is absent from resolution: " + lambda.id()));
            require(lambda.moduleId().equals(source.moduleId())
                            && lambda.span().equals(source.span())
                            && lambda.scopeId().equals(source.scopeId())
                            && source.signature().filter(lambda.signature()::equals).isPresent()
                            && lambda.parameterIds().equals(source.parameterIds())
                            && lambda.captures().equals(source.captures()),
                    "typed lambda metadata changed after resolution: " + lambda.id());
            TypedExpression expectedBody = lambdaBodies.get(lambda.id());
            require(expectedBody != null && lambda.body() == expectedBody,
                    "typed lambda body is not its source lambda child: " + lambda.id());
            require(lambda.body().type().equals(lambda.signature().returnType()),
                    "typed lambda body does not end at its return contract");
            ResolvedScope scope = resolved.scopeTree().scope(lambda.scopeId()).orElseThrow();
            require(scope.kind() == ScopeKind.LAMBDA
                            && scope.ownerLambda().filter(lambda.id()::equals).isPresent()
                            && scope.span().equals(lambda.span()),
                    "typed lambda scope kind/span/owner does not match resolution");
        }
    }

    private void validateCaptureReferenceIndexes() {
        Map<CaptureId, List<ReferenceId>> referencesByCapture = new LinkedHashMap<>();
        for (TypedReference reference : typed.references()) {
            reference.capture().ifPresent(captureId -> {
                ResolvedCapture capture = resolved.capture(captureId).orElseThrow(() -> invalid(
                        "typed reference names an absent capture"));
                require(reference.fromLambda().filter(capture.lambdaId()::equals).isPresent()
                                && reference.targetDeclaration()
                                .filter(capture.declarationId()::equals).isPresent(),
                        "typed capture reference changed owner or declaration");
                referencesByCapture.computeIfAbsent(captureId, ignored -> new ArrayList<>())
                        .add(reference.id());
            });
        }
        for (ResolvedCapture capture : resolved.captures()) {
            require(capture.references().equals(
                            referencesByCapture.getOrDefault(capture.id(), List.of())),
                    "typed capture/reference indexes are incomplete or out of source order");
        }
    }

    private void validatePublishedTopology() {
        require(reachable.size() == typed.expressions().size(),
                "typed expression index contains skipped, duplicate, or unreachable expressions");
        IdentityHashMap<TypedExpression, Boolean> indexed = new IdentityHashMap<>();
        for (TypedExpression expression : typed.expressions()) {
            require(indexed.put(expression, Boolean.TRUE) == null && reachable.containsKey(expression),
                    "typed expression index does not contain the exact published expression objects");
        }

        require(reachableBySpan.keySet().equals(typed.expressionsBySpan().keySet()),
                "typed expression span index has missing or extra spans");
        for (Map.Entry<SourceSpan, List<TypedExpression>> entry : reachableBySpan.entrySet()) {
            List<TypedExpression> actual = typed.expressionsBySpan().get(entry.getKey());
            require(actual != null && sameIdentities(entry.getValue(), actual),
                    "typed expression span index contains a foreign or missing expression");
        }

        Map<TypedConversion, Integer> expectedConversions = counts(reachableConversions);
        require(expectedConversions.equals(counts(typed.conversions())),
                "typed conversion index does not exactly match reachable conversion expressions");
    }

    private void collect(TypedExpression expression) {
        if (reachable.put(expression, Boolean.TRUE) != null) {
            throw invalid("typed source tree contains a shared or cyclic expression object");
        }
        validateExpressionShape(expression);
        reachableBySpan.computeIfAbsent(expression.span(), ignored -> new ArrayList<>()).add(expression);
        expression.conversion().ifPresent(reachableConversions::add);
        for (TypedExpression child : expression.children()) {
            collect(child);
        }
    }

    private void validateForm(SyntaxNode.Form syntax, TypedExpression expression, ModuleId moduleId) {
        if (syntax instanceof SyntaxNode.NominalDeclaration declaration) {
            var nominal = resolved.nominals().stream().filter(value -> resolved.declaration(value.declaration())
                    .orElseThrow().nameSpan().equals(declaration.name().span())).findFirst()
                    .orElseThrow(() -> invalid("source nominal has no resolved schema"));
            require(expression.kind() == TypedExpressionKind.NOMINAL_DECLARATION
                            && expression.type() == PrimitiveType.UNIT && expression.span().equals(declaration.span())
                            && expression.declarationId().equals(Optional.of(nominal.declaration()))
                            && expression.scopeId().equals(Optional.of(resolved.declaration(nominal.self()).orElseThrow().scopeId()))
                            && expression.nominalInitialization().isPresent(),
                    "typed nominal declaration differs from its source schema");
            expression.nominalInitialization().orElseThrow().requireMatches(nominal.declaration(), expression.children());
            int index = 0;
            for (int memberIndex = 0; memberIndex < declaration.members().size(); memberIndex++) {
                var member = declaration.members().get(memberIndex);
                if (member.initializer().isEmpty()) continue;
                require(index < expression.children().size(), "nominal field initializer is missing");
                TypedExpression initializer = expression.children().get(index++);
                require(declarationInitializers.put(nominal.members().get(memberIndex), initializer) == null,
                        "nominal member initializer occurs more than once");
                validateSourceExpression(member.initializer().orElseThrow(), initializer, moduleId,
                        Optional.of(nominal.schema().members().get(memberIndex).type()));
            }
            if (declaration.constructor().isPresent()) {
                require(index < expression.children().size(), "nominal constructor is missing");
                validateSourceExpression(declaration.constructor().orElseThrow().initializer(), expression.children().get(index++),
                        moduleId, Optional.of(FunctionType.of(nominal.schema().constructorParameters(), PrimitiveType.UNIT)));
            }
            require(index == expression.children().size(), "nominal declaration carries foreign initializers");
            return;
        }
        if (syntax instanceof SyntaxNode.LetBinding let) {
            require(expression.kind() == TypedExpressionKind.DECLARATION
                            && expression.span().equals(let.span())
                            && expression.type() == PrimitiveType.UNIT
                            && expression.children().size() == 1,
                    "typed let operation does not match its source declaration");
            ResolvedDeclaration declaration = resolved.declarations().stream()
                    .filter(value -> value.kind() == DeclarationKind.LET
                            && value.nameSpan().equals(let.name().span())
                            && value.moduleId().equals(moduleId))
                    .findFirst().orElseThrow(() -> invalid("source let has no resolved declaration"));
            require(expression.declarationId().filter(declaration.id()::equals).isPresent(),
                    "typed let operation carries the wrong declaration identity");
            require(expression.link().filter(link -> link.equals(new TypedLink(
                    Optional.empty(), Optional.of(declaration.id()), Optional.empty(),
                    Optional.empty(), Optional.empty()))).isPresent(),
                    "typed let operation carries a foreign link");
            TypedExpression initializer = expression.children().getFirst();
            require(declarationInitializers.put(declaration.id(), initializer) == null,
                    "typed declaration identity occurs more than once in source order");
            Optional<LyraType> expected = declaration.declaredContract()
                    .map(BindingContract::valueType);
            validateSourceExpression(let.initializer(), initializer, moduleId, expected);
            if (expected.isEmpty()) {
                validateInferredDeclaration(let, declaration, initializer);
            }
            return;
        }
        require(syntax instanceof SyntaxNode.Expression,
                "typed graph contains an unsupported source form");
        validateSourceExpression((SyntaxNode.Expression) syntax, expression, moduleId);
    }

    private void validateInferredDeclaration(
            SyntaxNode.LetBinding syntax,
            ResolvedDeclaration declaration,
            TypedExpression initializer) {
        LyraType synthesized = synthesizedTypeWithoutContext(syntax.initializer())
                .orElseThrow(() -> invalid(
                        "inferred declaration initializer has no context-free type"));
        LyraType normalized = synthesized.withoutQualifiers();
        boolean nilable = synthesized.isNilable() || syntax.modifiers().stream()
                .anyMatch(modifier -> modifier.kind() == ModifierKind.NILABLE);
        if (nilable) {
            normalized = normalized.nilable();
        }
        BindingContract expectedContract = new BindingContract(
                normalized, declaration.bindingMutability());
        require(initializer.type().equals(normalized)
                        && typed.contract(declaration.id()).filter(expectedContract::equals).isPresent(),
                "inferred typed declaration contract/initializer does not match its normalized "
                        + "context-free initializer type");
    }

    private void validateSourceExpression(
            SyntaxNode.Expression syntax,
            TypedExpression published,
            ModuleId moduleId) {
        validateSourceExpression(syntax, published, moduleId, Optional.empty());
    }

    private void validateSourceExpression(
            SyntaxNode.Expression syntax,
            TypedExpression published,
            ModuleId moduleId,
            Optional<LyraType> expectedType) {
        TypedExpression expression = unwrapImplicitConversions(syntax, published);
        if (expectedType.isPresent()) {
            require(published.type().equals(expectedType.orElseThrow()),
                    "typed expression does not end at its expected contextual type");
        }
        require(expression.span().equals(syntax.span()),
                "typed expression does not retain its exact originating source span");

        if (expression.kind() == TypedExpressionKind.CONSTRUCTION) {
            List<SyntaxNode.Expression> arguments;
            SyntaxNode.Expression target;
            if (syntax instanceof SyntaxNode.BracketApplication application) {
                target = application.target();
                arguments = application.arguments().arguments();
            } else if (syntax instanceof SyntaxNode.IndexAccess index) {
                target = index.receiver();
                arguments = List.of(index.index());
            } else {
                throw invalid("typed construction has no bracket source");
            }
            var origin = resolved.syntaxLinks().stream().filter(link -> link.kind() == SyntaxLinkKind.CALL
                            && link.span().equals(syntax.span())).flatMap(link -> link.declarationId().stream())
                    .flatMap(id -> resolved.nominals().stream().filter(value -> value.declaration().equals(id)))
                    .findFirst().orElseThrow(() -> invalid("construction has no resolved nominal origin"));
            ResolvedReference reference;
            if (target instanceof SyntaxNode.Identifier identifier) {
                reference = findReference(identifier.span(), identifier.name(), ReferenceKind.VALUE);
            } else if (target instanceof SyntaxNode.NamespaceMemberAccess namespace) {
                reference = findReference(namespace.member().span(), namespace.member().name(), ReferenceKind.NAMESPACE_MEMBER);
            } else throw invalid("construction target is not a type name");
            requireReferenceLink(expression, reference, Optional.empty());
            require(expression.declarationId().equals(Optional.of(origin.declaration()))
                            && expression.type().equals(origin.schema().type())
                            && expression.signature().equals(Optional.of(FunctionType.of(origin.schema().constructorParameters(),
                            origin.schema().type()).signature()))
                            && arguments.size() == origin.schema().constructorParameters().size()
                            && expression.children().size() == arguments.size(),
                    "construction differs from its exact source schema");
            for (int index = 0; index < arguments.size(); index++) {
                validateSourceExpression(arguments.get(index), expression.children().get(index), moduleId,
                        Optional.of(origin.schema().constructorParameters().get(index)));
            }
            return;
        }

        if (CallbackLoop.of(syntax).isPresent()) {
            TypedExpressionKind kind = CallbackLoop.of(syntax).orElseThrow() == CallbackLoop.ITER
                    ? TypedExpressionKind.ITER : TypedExpressionKind.WHILE;
            require(expression.kind() == kind && CallbackLoop.valid(kind, expression.type(),
                    expression.children().stream().map(TypedExpression::type).toList()),
                    "typed callback loop differs from its reserved source contract");
            List<SyntaxNode.Expression> arguments = CallbackLoop.arguments(syntax);
            require(arguments.size() == 2, "callback loop source arity changed");
            for (int index = 0; index < 2; index++) {
                TypedExpression child = expression.children().get(index);
                validateSourceExpression(arguments.get(index), child, moduleId,
                        index == 1 || kind == TypedExpressionKind.WHILE ? Optional.of(child.type()) : Optional.empty());
            }
            return;
        }

        if (syntax instanceof SyntaxNode.Identifier identifier) {
            require(expression.kind() == TypedExpressionKind.REFERENCE
                            && expression.children().isEmpty(),
                    "identifier source did not produce one typed reference");
            ResolvedReference reference = findReference(
                    identifier.span(), identifier.name(), ReferenceKind.VALUE);
            requireReferenceLink(expression, reference, Optional.empty());
            require(expression.type().equals(typed.reference(reference.id()).orElseThrow().valueType()),
                    "typed identifier type does not match its typed reference");
            require(expression.captureIds().equals(reference.capture().map(List::of).orElseGet(List::of)),
                    "typed identifier capture identity changed");
            return;
        }
        if (syntax instanceof SyntaxNode.Literal literal) {
            validateLiteral(literal, expression, expectedType);
            return;
        }
        if (syntax instanceof SyntaxNode.Block block) {
            require(expression.kind() == TypedExpressionKind.BLOCK
                            && expression.children().size() == block.forms().size(),
                    "typed block children do not match its source forms");
            ResolvedScope scope = expression.scopeId().flatMap(resolved.scopeTree()::scope)
                    .orElseThrow(() -> invalid("typed block has no resolved scope"));
            require(scope.kind() == ScopeKind.BLOCK
                            && scope.span().equals(block.span())
                            && scope.moduleId().equals(moduleId),
                    "typed block scope kind/span/module does not match its source block");
            for (int index = 0; index < block.forms().size(); index++) {
                SyntaxNode.Form form = block.forms().get(index);
                if (index == block.forms().size() - 1
                        && form instanceof SyntaxNode.Expression finalExpression) {
                    validateSourceExpression(finalExpression, expression.children().get(index),
                            moduleId, expectedType);
                } else {
                    validateForm(form, expression.children().get(index), moduleId);
                }
            }
            LyraType expected = expression.children().isEmpty()
                    ? PrimitiveType.UNIT : expression.children().getLast().type();
            require(expression.type().equals(expected),
                    "typed block type does not equal its final form/Unit");
            return;
        }
        if (syntax instanceof SyntaxNode.Conditional conditional) {
            int expectedChildren = conditional.elseBranch().isPresent() ? 3 : 2;
            require(expression.kind() == TypedExpressionKind.CONDITIONAL
                            && expression.children().size() == expectedChildren,
                    "typed conditional does not match its source branches");
            validateSourceExpression(conditional.predicate(), expression.children().getFirst(), moduleId);
            validatePredicateBinding(conditional, expression);
            if (conditional.elseBranch().isEmpty()) {
                // The effectful branch is intentionally validated without the
                // Unit result context.  Unit belongs to the conditional node,
                // not to the expression evaluated in its truthy branch.
                validateSourceExpression(conditional.thenBranch(), expression.children().get(1),
                        moduleId, Optional.empty());
                require(expression.type() == PrimitiveType.UNIT,
                        "then-only typed conditional is not Unit");
            } else {
                LyraType branchType = expectedType.isPresent()
                        ? expression.type() : inferredConditionalType(conditional);
                if (expectedType.isEmpty()) {
                    require(expression.type().equals(branchType),
                            "inferred typed conditional does not have its canonical branch type");
                }
                Optional<LyraType> thenExpected = conditionalBranchValidationContext(
                        conditional.thenBranch(), expectedType, branchType,
                        StructuralContextPlan.ChildPosition.conditionalThen());
                Optional<LyraType> elseExpected = conditionalBranchValidationContext(
                        conditional.elseBranch().orElseThrow(), expectedType, branchType,
                        StructuralContextPlan.ChildPosition.conditionalElse());
                validateSourceExpression(conditional.thenBranch(), expression.children().get(1),
                        moduleId, thenExpected);
                validateSourceExpression(conditional.elseBranch().orElseThrow(),
                        expression.children().get(2), moduleId, elseExpected);
                require(expression.children().get(1).type().equals(expression.type())
                                && expression.children().get(2).type().equals(expression.type()),
                        "typed conditional branches do not end at the result type");
            }
            require(truthTestable(expression.children().getFirst().type()),
                    "typed conditional predicate is not truth-testable");
            return;
        }
        if (syntax instanceof SyntaxNode.Match matchSyntax) {
            require(expression.kind() == TypedExpressionKind.MATCH,
                    "match source did not produce one typed match operation");
            TypedMatch match = expression.match().orElseThrow(() -> invalid(
                    "typed match has no closed arm metadata"));
            require(match.mode() == (matchSyntax.mode() == SyntaxNode.MatchMode.TRADITIONAL
                            ? TypedMatch.MatchMode.TRADITIONAL : TypedMatch.MatchMode.CONDITIONAL)
                            && match.arms().size() == matchSyntax.arms().size()
                            && match.childCount() == expression.children().size(),
                    "typed match metadata does not match its source mode/arms");
            if (matchSyntax.subject().isPresent()) {
                require(match.subjectChild().isPresent(), "traditional typed match has no subject child");
                validateSourceExpression(matchSyntax.subject().orElseThrow(),
                        expression.children().get(match.subjectChild().getAsInt()), moduleId);
            } else {
                require(match.subjectChild().isEmpty(), "conditional typed match invented a subject expression");
            }
            LyraType resultType = expectedType.isPresent() ? expression.type() : inferredMatchType(matchSyntax);
            require(expression.type().equals(resultType),
                    "typed match does not have its independently inferred result type");
            for (int index = 0; index < match.arms().size(); index++) {
                TypedMatch.Arm arm = match.arms().get(index);
                SyntaxNode.MatchArm sourceArm = matchSyntax.arms().get(index);
                require(arm.span().equals(sourceArm.span()) && arm.wildcard() == sourceArm.wildcard(),
                        "typed match arm does not retain its source role/span");
                if (sourceArm.pattern().isPresent()) {
                    require(arm.patternChild().isPresent(), "typed match arm lost its pattern/condition child");
                    TypedExpression pattern = expression.children().get(arm.patternChild().getAsInt());
                    if (matchSyntax.mode() == SyntaxNode.MatchMode.CONDITIONAL) {
                        validateSourceExpression(sourceArm.pattern().orElseThrow(), pattern, moduleId);
                        require(truthTestable(pattern.type()), "conditional match condition is not truth-testable");
                        require(arm.comparisonType().isEmpty(), "conditional match invented equality metadata");
                    } else {
                        LyraType comparison = inferredMatchComparison(
                                matchSyntax.subject().orElseThrow(), sourceArm.pattern().orElseThrow());
                        require(arm.comparisonType().filter(comparison::equals).isPresent(),
                                "typed match equality type is not source-derived");
                        validateSourceExpression(sourceArm.pattern().orElseThrow(), pattern, moduleId,
                                Optional.of(comparison));
                        TypedExpression subject = expression.children().get(match.subjectChild().getAsInt());
                        require(TypeRules.canImplicitlyConvert(subject.type(), comparison),
                                "typed match subject cannot reach its equality type");
                    }
                } else {
                    require(arm.patternChild().isEmpty() && arm.comparisonType().isEmpty(),
                            "typed wildcard match arm carries pattern/equality metadata");
                }
                if (sourceArm.guard().isPresent()) {
                    require(arm.guardChild().isPresent(), "typed match arm lost its guard child");
                    TypedExpression guard = expression.children().get(arm.guardChild().getAsInt());
                    validateSourceExpression(sourceArm.guard().orElseThrow(), guard, moduleId);
                    require(truthTestable(guard.type()), "typed match guard is not truth-testable");
                } else {
                    require(arm.guardChild().isEmpty(), "typed match arm invented a guard child");
                }
                validateSourceExpression(sourceArm.result(), expression.children().get(arm.resultChild()),
                        moduleId, Optional.of(resultType));
            }
            return;
        }
        if (syntax instanceof SyntaxNode.Coalesce coalesce) {
            require(expression.kind() == TypedExpressionKind.COALESCE
                            && expression.children().size() == 2,
                    "typed coalesce does not match its source operands");
            Optional<LyraType> valueExpected = StructuralContextPlan.expectedFor(
                    StructuralContextPlan.ChildPosition.coalesceValue(),
                    coalesce.value(), expectedType, Optional.empty());
            validateSourceExpression(
                    coalesce.value(), expression.children().getFirst(), moduleId, valueExpected);
            Optional<LyraType> sourceValue = valueExpected.isPresent()
                    ? valueExpected
                    : synthesizedTypeWithoutContext(coalesce.value());
            LyraType sourceContract = sourceValue.orElseThrow(() -> invalid(
                    "coalesce value has no independently derived nilable contract"));
            require(sourceContract.isNilable(),
                    "typed coalesce value is not independently nilable");
            LyraType sourceResult = narrowed(sourceContract);
            Optional<LyraType> fallbackExpected = StructuralContextPlan.expectedFor(
                    StructuralContextPlan.ChildPosition.coalesceFallback(),
                    coalesce.fallback(), Optional.of(sourceContract), Optional.empty());
            validateSourceExpression(coalesce.fallback(), expression.children().get(1),
                    moduleId, Optional.of(fallbackExpected.orElse(sourceResult)));
            require(!expression.type().isNilable()
                            && expression.children().getFirst().type().isNilable()
                            && narrowed(expression.children().getFirst().type()).equals(expression.type())
                            && expression.children().get(1).type().equals(expression.type())
                            && expression.type().equals(sourceResult),
                    "typed coalesce does not preserve its nilable value/non-nil result contract");
            valueExpected.ifPresent(value -> require(
                    narrowed(value).equals(expression.type()),
                    "typed coalesce value is incompatible with its expected result contract"));
            return;
        }
        if (syntax instanceof SyntaxNode.PrefixAssignment assignment) {
            validateRebinding(assignment.target(), assignment.value(), expression, moduleId);
            return;
        }
        if (syntax instanceof SyntaxNode.Reassignment assignment) {
            validateRebinding(assignment.target(), assignment.value(), expression, moduleId);
            return;
        }
        if (syntax instanceof SyntaxNode.Lambda lambda) {
            validateLambda(lambda.body(), lambda.span(), expression, moduleId);
            return;
        }
        if (syntax instanceof SyntaxNode.CompactLambda lambda) {
            validateLambda(lambda.body(), lambda.span(), expression, moduleId);
            return;
        }
        if (syntax instanceof SyntaxNode.CallableCall call) {
            require(expression.kind() == TypedExpressionKind.CALLABLE_CALL
                            && expression.children().size() == call.arguments().size() + 1,
                    "typed callable call does not match its source target/arguments");
            validateSourceExpression(call.target(), expression.children().getFirst(), moduleId);
            FunctionType function = requireFunctionType(expression.children().getFirst().type());
            require(call.arguments().size() == function.arity(),
                    "typed callable-call source arity changed from its target signature");
            for (int index = 0; index < call.arguments().size(); index++) {
                validateSourceExpression(call.arguments().get(index),
                        expression.children().get(index + 1), moduleId,
                        Optional.of(function.parameterType(index)));
            }
            validateCallContract(expression, expression.children().getFirst().type(), 1);
            return;
        }
        if (syntax instanceof SyntaxNode.DirectCall call) {
            if (call.receiver().isPresent()) {
                require(expression.kind() == TypedExpressionKind.CALLABLE_CALL
                                && expression.children().size() == call.argumentExpressions().size() + 1,
                        "member invocation must select its current callable slot");
                TypedExpression selected = expression.children().getFirst();
                require(selected.kind() == TypedExpressionKind.MEMBER_ACCESS && selected.children().size() == 1
                                && selected.memberName().equals(Optional.of(call.name().name()))
                                && selected.span().equals(call.span()), "member invocation selector differs from source");
                validateSourceExpression(call.receiver().orElseThrow(), selected.children().getFirst(), moduleId);
                validateMemberContract(selected, selected.children().getFirst().type());
                FunctionType function = requireFunctionType(selected.type());
                require(function.arity() == call.argumentExpressions().size(), "member invocation arity differs from its slot");
                for (int index = 0; index < function.arity(); index++) {
                    validateSourceExpression(call.argumentExpressions().get(index), expression.children().get(index + 1),
                            moduleId, Optional.of(function.parameterType(index)));
                }
                validateCallContract(expression, selected.type(), 1);
                return;
            }
            require(call.receiver().isEmpty()
                            && expression.kind() == TypedExpressionKind.DIRECT_CALL
                            && expression.children().size() == call.argumentExpressions().size(),
                    "typed direct call does not match its supported source shape");
            ResolvedReference reference = findReference(
                    call.name().span(), call.name().name(), ReferenceKind.DIRECT_CALL_TARGET);
            requireReferenceLink(expression, reference, Optional.empty());
            require(expression.declarationId().equals(reference.targetDeclaration()),
                    "typed direct call declaration identity changed from resolution");
            LyraType targetType = typed.reference(reference.id()).orElseThrow().valueType();
            FunctionType function = requireFunctionType(targetType);
            require(call.argumentExpressions().size() == function.arity(),
                    "typed direct-call source arity changed from its target signature");
            for (int index = 0; index < call.argumentExpressions().size(); index++) {
                validateSourceExpression(call.argumentExpressions().get(index),
                        expression.children().get(index), moduleId,
                        Optional.of(function.parameterType(index)));
            }
            validateCallContract(expression, targetType, 0);
            return;
        }
        if (syntax instanceof SyntaxNode.MemberAccess access) {
            require(expression.kind() == TypedExpressionKind.MEMBER_ACCESS
                            && expression.children().size() == 1
                            && expression.memberName().equals(access.member().identifier())
                            && expression.tupleIndex().equals(access.member().tupleIndex()),
                    "typed member access selector/receiver does not match source");
            validateSourceExpression(access.receiver(), expression.children().getFirst(), moduleId);
            validateMemberContract(expression, expression.children().getFirst().type());
            return;
        }
        if (syntax instanceof SyntaxNode.NamespaceMemberAccess access) {
            require(expression.kind() == TypedExpressionKind.NAMESPACE_MEMBER_ACCESS
                            && expression.children().isEmpty()
                            && expression.memberName().equals(access.member().identifier())
                            && expression.tupleIndex().equals(access.member().tupleIndex()),
                    "typed namespace member selector does not match source");
            ResolvedReference reference = findReference(
                    access.member().span(), access.member().name(), ReferenceKind.NAMESPACE_MEMBER);
            requireReferenceLink(expression, reference, Optional.of(AccessKind.NAMESPACE_VALUE));
            require(expression.type().equals(typed.reference(reference.id()).orElseThrow().valueType()),
                    "typed namespace access result does not match its export reference");
            return;
        }
        if (syntax instanceof SyntaxNode.NamespaceDirectCall call) {
            require(expression.kind() == TypedExpressionKind.NAMESPACE_DIRECT_CALL
                            && expression.children().size() == call.argumentExpressions().size(),
                    "typed namespace direct call does not match source arguments");
            ResolvedReference reference = findReference(
                    call.name().span(), call.name().name(), ReferenceKind.NAMESPACE_DIRECT_CALL);
            requireReferenceLink(expression, reference, Optional.of(AccessKind.NAMESPACE_DIRECT_CALL));
            require(expression.declarationId().equals(reference.targetDeclaration()),
                    "typed namespace call declaration identity changed from resolution");
            LyraType targetType = typed.reference(reference.id()).orElseThrow().valueType();
            FunctionType function = requireFunctionType(targetType);
            require(call.argumentExpressions().size() == function.arity(),
                    "typed namespace-call source arity changed from its target signature");
            for (int index = 0; index < call.argumentExpressions().size(); index++) {
                validateSourceExpression(call.argumentExpressions().get(index),
                        expression.children().get(index), moduleId,
                        Optional.of(function.parameterType(index)));
            }
            validateCallContract(expression, targetType, 0);
            return;
        }
        if (syntax instanceof SyntaxNode.ArrayLiteral array) {
            validateArrayLiteral(array, expression, moduleId, expectedType);
            return;
        }
        if (syntax instanceof SyntaxNode.Range range) {
            require(expression.kind() == TypedExpressionKind.RANGE && expression.children().size() == 3,
                    "typed range does not match its source bounds");
            require(expression.type() instanceof io.mindspice.lyra.compiler.types.RangeType,
                    "range construction must have a Range type");
            var rangeType = (io.mindspice.lyra.compiler.types.RangeType) expression.type();
            require(expression.operator().orElse("").equals(range.inclusive() ? "..." : ".."),
                    "range endpoint inclusion changed from source");
            var bounds = List.of(range.start(), range.end(), range.step());
            for (int index = 0; index < bounds.size(); index++) {
                validateSourceExpression(bounds.get(index), expression.children().get(index), moduleId,
                        Optional.of(rangeType.elementType()));
            }
            return;
        }
        if (syntax instanceof SyntaxNode.TupleLiteral tuple) {
            validateTupleLiteral(tuple, expression, moduleId, expectedType);
            return;
        }
        if (syntax instanceof SyntaxNode.IndexAccess index) {
            require(expression.kind() == TypedExpressionKind.INDEX_ACCESS
                            && expression.children().size() == 2,
                    "typed index access does not match its source receiver/index");
            validateSourceExpression(index.receiver(), expression.children().getFirst(), moduleId);
            validateSourceExpression(index.index(), expression.children().get(1), moduleId);
            validateIndexContract(expression, expression.children().getFirst().type(),
                    expression.children().get(1).type());
            return;
        }
        if (syntax instanceof SyntaxNode.OperatorSExpression operator) {
            validateOperator(operator.operator(), operator.operands(), expression, moduleId, expectedType);
            return;
        }
        if (syntax instanceof SyntaxNode.OperatorBracket operator) {
            validateOperator(operator.operator(), operator.operands(), expression, moduleId, expectedType);
            return;
        }
        if (syntax instanceof SyntaxNode.TypeConversion conversion) {
            require(expression.kind() == TypedExpressionKind.CONVERSION
                            && expression.conversion().filter(value -> value.kind() == ConversionKind.EXPLICIT)
                            .isPresent()
                            && expression.children().size() == 1,
                    "source type conversion did not produce one explicit typed conversion");
            PrimitiveType target = PrimitiveType.fromSpelling(conversion.targetPrimitive().name())
                    .orElseThrow(() -> invalid("source conversion target is not a primitive type"));
            require(expression.type() == target,
                    "typed explicit conversion target changed from source");
            validateSourceExpression(conversion.value(), expression.children().getFirst(), moduleId);
            return;
        }
        throw invalid("typed graph published an unsupported source expression: "
                + syntax.getClass().getSimpleName());
    }

    private TypedExpression unwrapImplicitConversions(
            SyntaxNode.Expression syntax,
            TypedExpression expression) {
        TypedExpression current = expression;
        while (current.kind() == TypedExpressionKind.CONVERSION
                && current.conversion().filter(value -> value.kind() == ConversionKind.IMPLICIT).isPresent()) {
            require(current.span().equals(syntax.span()) && current.children().size() == 1,
                    "implicit typed conversion lost its originating source span/operand");
            current = current.children().getFirst();
        }
        return current;
    }

    private void validateArrayLiteral(
            SyntaxNode.ArrayLiteral syntax,
            TypedExpression expression,
            ModuleId moduleId,
            Optional<LyraType> expectedType) {
        require(expression.kind() == TypedExpressionKind.ARRAY_LITERAL
                        && expression.children().size() == syntax.elements().size(),
                "typed array literal does not match its source elements");
        LyraType base = expression.type().withoutQualifiers();
        require(!expression.type().isNilable() && base instanceof ArrayType,
                "typed array literal does not have a non-nil Array type");
        ArrayType array = (ArrayType) base;
        if (syntax.explicitType().isPresent()) {
            require(syntaxType(syntax.explicitType().orElseThrow(), TypePosition.BINDING)
                            .withoutQualifiers().equals(array),
                    "typed array literal type prefix changed its element type");
        }
        if (expectedType.isPresent()) {
            LyraType expectedBase = expectedType.orElseThrow().withoutQualifiers();
            require(expectedBase instanceof ArrayType && expectedBase.equals(array),
                    "typed array literal does not match its expected invariant Array type");
        } else if (syntax.explicitType().isEmpty()) {
            LyraType inferredElement = inferredArrayType(syntax, expression, moduleId);
            LyraType inferredArray = ArrayType.of(inferredElement);
            require(inferredArray.equals(array),
                    "inferred array literal type is not the canonical source-derived type: expected "
                            + inferredArray + " but was " + array);
        }
        for (int index = 0; index < syntax.elements().size(); index++) {
            validateSourceExpression(syntax.elements().get(index), expression.children().get(index),
                    moduleId, Optional.of(array.elementType()));
        }
    }

    private void validateTupleLiteral(
            SyntaxNode.TupleLiteral syntax,
            TypedExpression expression,
            ModuleId moduleId,
            Optional<LyraType> expectedType) {
        require(expression.kind() == TypedExpressionKind.TUPLE_LITERAL
                        && expression.children().size() == syntax.elements().size(),
                "typed tuple literal does not match its source elements");
        LyraType base = expression.type().withoutQualifiers();
        require(!expression.type().isNilable() && base instanceof TupleType,
                "typed tuple literal does not have a non-nil Tuple type");
        TupleType tuple = (TupleType) base;
        require(!syntax.elements().isEmpty(),
                "only bare Tuple[] is Unit; typed tuple literals need elements");
        require(tuple.arity() == syntax.elements().size(),
                "typed tuple literal shape does not match its elements");
        if (syntax.explicitType().isPresent()) {
            require(syntaxType(syntax.explicitType().orElseThrow(), TypePosition.BINDING)
                            .withoutQualifiers().equals(tuple),
                    "typed tuple literal type prefix changed its shape");
        } else if (expectedType.isEmpty()) {
            require(inferredTupleType(syntax, expression, moduleId).equals(tuple),
                    "inferred tuple literal type is not the canonical source-derived shape");
        }
        if (expectedType.isPresent()) {
            LyraType expectedBase = expectedType.orElseThrow().withoutQualifiers();
            require(expectedBase instanceof TupleType && expectedBase.equals(tuple),
                    "typed tuple literal does not match its expected invariant Tuple type");
        }
        for (int index = 0; index < syntax.elements().size(); index++) {
            validateSourceExpression(syntax.elements().get(index), expression.children().get(index),
                    moduleId, Optional.of(tuple.memberType(index)));
        }
    }

    private void validateIndexContract(
            TypedExpression expression, LyraType receiverType, LyraType indexType) {
        require(!receiverType.isNilable() && !indexType.isNilable() && indexType.isInteger(),
                "typed index access receiver/index contract is invalid");
        LyraType base = receiverType.withoutQualifiers();
        LyraType expected = base == PrimitiveType.STRING
                ? PrimitiveType.CHAR
                : base instanceof ArrayType array ? array.elementType() : null;
        require(expected != null && expression.type().equals(expected),
                "typed index access result does not match its receiver contract");
    }

    private LyraType inferredArrayType(
            SyntaxNode.ArrayLiteral syntax,
            TypedExpression expression,
            ModuleId moduleId) {
        List<ExactNumericLiteral> literals = new ArrayList<>();
        List<PrimitiveType> fixed = new ArrayList<>();
        List<LyraType> nonNumeric = new ArrayList<>();
        List<SyntaxNode.Expression> deferred = new ArrayList<>();
        boolean hasNil = false;
        boolean allDirect = true;
        boolean allUnforced = true;
        boolean decimal = false;
        for (int index = 0; index < syntax.elements().size(); index++) {
            SyntaxNode.Expression source = syntax.elements().get(index);
            ExactNumericLiteral literal = directNumericLiteral(source);
            if (literal != null) {
                literals.add(literal);
                allUnforced &= literal.forcedType().isEmpty();
                decimal |= literal.isDecimal();
                continue;
            }
            Optional<LyraType> type = synthesizedTypeWithoutContext(source);
            if (type.isEmpty()) {
                require(StructuralContextPlan.containsContextFreeNil(source),
                        "inferred array element is not context-free");
                deferred.add(source);
                hasNil |= StructuralContextPlan.isContextFreeNil(source);
                continue;
            }
            allDirect = false;
            LyraType value = type.orElseThrow().withoutQualifiers();
            hasNil |= type.orElseThrow().isNilable();
            if (value.isNumeric()) {
                fixed.add((PrimitiveType) value);
            } else {
                nonNumeric.add(type.orElseThrow());
            }
        }
        if (literals.isEmpty() && fixed.isEmpty() && nonNumeric.isEmpty()) {
            Optional<LyraType> folded = StructuralContextPlan.synthesizeArrayElements(
                    syntax.elements(), this::synthesizeAtomicTypeWithoutContext,
                    type -> Optional.of(syntaxType(type, TypePosition.BINDING).withoutQualifiers()),
                    candidate -> candidate instanceof SyntaxNode.Conditional nested
                            ? synthesizeStructuralConditional(nested)
                            : Optional.empty());
            if (folded.isPresent()) {
                return folded.orElseThrow();
            }
        }
        require(!literals.isEmpty() || !fixed.isEmpty() || !nonNumeric.isEmpty(),
                "inferred array has no non-nil element type");
        LyraType result;
        if (nonNumeric.isEmpty()) {
            List<PrimitiveType> candidates = numericCandidates(literals, fixed);
            require(!candidates.isEmpty(), "inferred array elements have no common numeric type");
            PrimitiveType preferred = decimal ? PrimitiveType.F64 : PrimitiveType.I32;
            result = allDirect && allUnforced && candidates.contains(preferred)
                    ? preferred : candidates.getFirst();
            if (hasNil) {
                result = result.nilable();
            }
        } else {
            require(literals.isEmpty(), "inferred array mixes numeric and nonnumeric elements");
            result = StructuralContextPlan.foldHomogeneous(nonNumeric)
                    .orElseThrow(() -> invalid("inferred array elements have no common type"));
            if (hasNil && !result.isNilable()) {
                result = result.nilable();
            }
        }
        for (SyntaxNode.Expression source : deferred) {
            LyraType context = StructuralContextPlan.expectedFromPeer(source, result)
                    .orElseThrow(() -> invalid("inferred array element has no compatible peer shape"));
            result = StructuralContextPlan.mergeNilShape(result, context);
        }
        return result;
    }

    private LyraType inferredTupleType(
            SyntaxNode.TupleLiteral syntax,
            TypedExpression expression,
            ModuleId moduleId) {
        List<LyraType> members = new ArrayList<>();
        for (int index = 0; index < syntax.elements().size(); index++) {
            SyntaxNode.Expression source = syntax.elements().get(index);
            require(!(source instanceof SyntaxNode.NilLiteral),
                    "inferred tuple member cannot be an untyped #NIL");
            ExactNumericLiteral literal = directNumericLiteral(source);
            LyraType type = literal != null
                    ? LiteralTyping.infer(literal).orElseThrow(() -> invalid(
                    "inferred tuple numeric member has no default type"))
                    : synthesizedTypeWithoutContext(source)
                    .orElseThrow(() -> invalid("inferred tuple member is not context-free"));
            members.add(removeMutableQualifier(type));
        }
        return TupleType.of(members);
    }

    private List<PrimitiveType> numericCandidates(
            List<ExactNumericLiteral> literals,
            List<PrimitiveType> fixed) {
        List<PrimitiveType> result = new ArrayList<>();
        for (PrimitiveType candidate : NUMERIC_CANDIDATES) {
            boolean fits = literals.stream().allMatch(literal -> literal.forcedType().isPresent()
                    ? LiteralTyping.representableAs(literal, literal.forcedType().orElseThrow())
                    && TypeRules.canImplicitlyWiden(literal.forcedType().orElseThrow(), candidate)
                    : LiteralTyping.representableAs(literal, candidate));
            fits &= fixed.stream().allMatch(type -> TypeRules.canImplicitlyWiden(type, candidate));
            if (fits) {
                result.add(candidate);
            }
        }
        return result;
    }

    private LyraType syntaxType(SyntaxNode.Type syntax, TypePosition position) {
        if (syntax instanceof SyntaxNode.TypeContract contract) {
            LyraType base = syntaxType(contract.baseType(), position);
            Set<TypeQualifier> qualifiers = EnumSet.noneOf(TypeQualifier.class);
            for (SyntaxNode.Modifier modifier : contract.modifiers()) {
                if (modifier.kind() == ModifierKind.MUTABLE) {
                    require(position.permitsMutableQualifier(),
                            "@mut is not legal in this syntax type position");
                    require(qualifiers.add(TypeQualifier.MUT), "duplicate @mut type modifier");
                } else if (modifier.kind() == ModifierKind.NILABLE) {
                    require(!base.isNilable() && qualifiers.add(TypeQualifier.NIL),
                            "duplicate @nil type modifier");
                } else {
                    throw invalid("unknown type modifier");
                }
            }
            return base.withQualifiers(qualifiers);
        }
        if (syntax instanceof SyntaxNode.PrimitiveType primitive) {
            return PrimitiveType.fromSpelling(primitive.name())
                    .orElseThrow(() -> invalid("unknown primitive type"));
        }
        if (syntax instanceof SyntaxNode.ArrayType array) {
            return ArrayType.of(syntaxType(array.elementType(), TypePosition.NESTED_VALUE));
        }
        if (syntax instanceof SyntaxNode.RangeType range) {
            return io.mindspice.lyra.compiler.types.RangeType.of(
                    syntaxType(range.elementType(), TypePosition.NESTED_VALUE));
        }
        if (syntax instanceof SyntaxNode.TupleType tuple) {
            return TupleType.of(tuple.elementTypes().stream()
                    .map(type -> syntaxType(type, TypePosition.NESTED_VALUE)).toList());
        }
        if (syntax instanceof SyntaxNode.FunctionType function) {
            List<LyraType> parameters = function.parameterTypes().stream()
                    .map(type -> syntaxType(type, TypePosition.PARAMETER)).toList();
            return FunctionType.of(parameters, syntaxType(function.returnType(), TypePosition.RETURN));
        }
        throw invalid("unknown syntax type");
    }

    private void validateLiteral(
            SyntaxNode.Literal syntax,
            TypedExpression expression,
            Optional<LyraType> expectedType) {
        require(expression.kind() == TypedExpressionKind.LITERAL
                        && expression.children().isEmpty()
                        && expression.literal().isPresent(),
                "source literal did not produce exactly one typed literal");
        TypedLiteralValue value = expression.literal().orElseThrow();
        if (syntax instanceof SyntaxNode.BooleanLiteral literal) {
            require(expression.type() == PrimitiveType.BOOL
                            && value.equals(new TypedLiteralValue.BooleanValue(literal.value())),
                    "typed Boolean literal value/type changed");
        } else if (syntax instanceof SyntaxNode.NilLiteral) {
            LyraType expected = expectedType.filter(LyraType::isNilable)
                    .orElseThrow(() -> invalid("typed nil literal has no expected nilable contract"));
            LyraType nilType = expected.withoutQualifiers().nilable();
            require(expression.type().equals(nilType)
                            && value instanceof TypedLiteralValue.NilValue,
                    "typed nil literal lacks its exact expected nilable contract");
        } else if (syntax instanceof SyntaxNode.IntegerLiteral literal) {
            ExactNumericLiteral exact = ExactNumericLiteral.integer(literal.value(), literal.suffix());
            PrimitiveType primitive = expression.type().withoutQualifiers()
                    instanceof PrimitiveType type ? type : null;
            Optional<PrimitiveType> inferred = expectedType.filter(LyraType::isNumeric)
                    .flatMap(expected -> LiteralTyping.infer(exact, expected));
            if (inferred.isEmpty() && expectedType.filter(LyraType::isNumeric).isEmpty()) {
                inferred = LiteralTyping.infer(exact);
            }
            boolean signedMinimum = signedMinimumLiterals.containsKey(expression);
            require(primitive != null && primitive.isNumeric()
                            && (signedMinimum || inferred.filter(primitive::equals).isPresent())
                            && value.equals(new TypedLiteralValue.IntegerValue(exact)),
                    "typed integer literal exact value/type changed");
        } else if (syntax instanceof SyntaxNode.FloatLiteral literal) {
            ExactNumericLiteral exact = ExactNumericLiteral.decimal(literal.value(), literal.suffix());
            PrimitiveType primitive = expression.type().withoutQualifiers()
                    instanceof PrimitiveType type ? type : null;
            Optional<PrimitiveType> inferred = expectedType.filter(LyraType::isNumeric)
                    .flatMap(expected -> LiteralTyping.infer(exact, expected));
            if (inferred.isEmpty() && expectedType.filter(LyraType::isNumeric).isEmpty()) {
                inferred = LiteralTyping.infer(exact);
            }
            require(primitive != null && primitive.isFloating()
                            && inferred.filter(primitive::equals).isPresent()
                            && value.equals(new TypedLiteralValue.DecimalValue(exact)),
                    "typed decimal literal exact value/type changed");
        } else if (syntax instanceof SyntaxNode.StringLiteral literal) {
            require(expression.type() == PrimitiveType.STRING
                            && value.equals(new TypedLiteralValue.StringValue(literal.value())),
                    "typed String literal value/type changed");
        } else if (syntax instanceof SyntaxNode.CharacterLiteral literal) {
            require(expression.type() == PrimitiveType.CHAR
                            && value.equals(new TypedLiteralValue.CharacterValue(literal.value())),
                    "typed Char literal value/type changed");
        } else if (syntax instanceof SyntaxNode.UnitLiteral literal) {
            require(expression.type() == PrimitiveType.UNIT
                            && value.equals(new TypedLiteralValue.UnitValue(literal.spelling())),
                    "typed Unit literal value/type changed");
        } else {
            throw invalid("unknown source literal kind");
        }
    }

    private void validateRebinding(
            SyntaxNode.Expression targetSyntax,
            SyntaxNode.Expression valueSyntax,
            TypedExpression expression,
            ModuleId moduleId) {
        require(expression.kind() == TypedExpressionKind.REBINDING
                        && expression.type() == PrimitiveType.UNIT
                        && expression.children().size() == 2
                        && expression.declarationId().isPresent(),
                "typed rebinding does not match its source target/value");
        TypedExpression targetExpression = expression.children().getFirst();
        validateSourceExpression(targetSyntax, targetExpression, moduleId);
        DeclarationId target = mutationRootDeclaration(targetExpression);
        require(target != null, "typed rebinding target has no declaration link");
        BindingContract contract = typed.contract(target).orElseThrow(() -> invalid(
                "typed rebinding target has no contract"));
        boolean nominalField = targetExpression.kind() == TypedExpressionKind.MEMBER_ACCESS
                && targetExpression.declarationId().isPresent();
        LyraType valueType = targetSyntax instanceof SyntaxNode.IndexAccess || nominalField
                ? targetExpression.type() : contract.valueType();
        validateSourceExpression(valueSyntax, expression.children().get(1),
                moduleId, Optional.of(valueType));
        TypedLink targetLink = mutationRootLink(targetExpression).orElseThrow(() -> invalid(
                "typed rebinding target has no root link"));
        require(expression.declarationId().filter(target::equals).isPresent()
                        && expression.link().equals(Optional.of(targetLink)),
                "typed rebinding target identity/link changed: expected " + targetLink
                        + " but was " + expression.link());
        require(expression.children().get(1).type().equals(valueType),
                "typed rebinding value does not end at its target contract");
        ReferenceId rootReference = targetLink.referenceId().orElseThrow(() -> invalid(
                "typed rebinding root link has no canonical reference identity"));
        MutationKind mutationKind = targetSyntax instanceof SyntaxNode.IndexAccess
                ? MutationKind.ARRAY_ELEMENT : nominalField ? MutationKind.MEMBER_FIELD : MutationKind.REBINDING;
        long matchingMutations = typed.mutations().stream()
                .filter(mutation -> mutation.kind() == mutationKind
                        && mutation.moduleId().equals(moduleId)
                        && mutation.span().equals(targetSyntax.span())
                        && mutation.rootDeclaration().equals(target)
                        && mutation.rootReference().equals(Optional.of(rootReference)))
                .count();
        require(matchingMutations == 1,
                "typed rebinding does not have exactly one canonical resolver mutation authorization");
    }

    private DeclarationId mutationRootDeclaration(TypedExpression expression) {
        if (expression.kind() == TypedExpressionKind.REFERENCE) {
            return expression.link().flatMap(TypedLink::declarationId).orElse(null);
        }
        if ((expression.kind() == TypedExpressionKind.INDEX_ACCESS
                || expression.kind() == TypedExpressionKind.MEMBER_ACCESS)
                && !expression.children().isEmpty()) {
            return mutationRootDeclaration(expression.children().getFirst());
        }
        return null;
    }

    private Optional<TypedLink> mutationRootLink(TypedExpression expression) {
        if (expression.kind() == TypedExpressionKind.REFERENCE) {
            return expression.link();
        }
        if ((expression.kind() == TypedExpressionKind.INDEX_ACCESS
                || expression.kind() == TypedExpressionKind.MEMBER_ACCESS)
                && !expression.children().isEmpty()) {
            return mutationRootLink(expression.children().getFirst());
        }
        return Optional.empty();
    }

    private void validateLambda(
            SyntaxNode.Expression bodySyntax,
            SourceSpan lambdaSpan,
            TypedExpression expression,
            ModuleId moduleId) {
        require(expression.kind() == TypedExpressionKind.LAMBDA
                        && expression.span().equals(lambdaSpan)
                        && expression.children().size() == 1
                        && expression.lambdaId().isPresent()
                        && expression.signature().isPresent(),
                "typed lambda does not match its source body/identity/signature");
        LambdaId id = expression.lambdaId().orElseThrow();
        ResolvedLambda source = resolved.lambda(id).orElseThrow(() -> invalid(
                "typed lambda identity is unresolved"));
        require(source.span().equals(lambdaSpan)
                        && source.signature().equals(expression.signature())
                        && source.captures().equals(expression.captureIds())
                        && expression.type().equals(expression.signature().orElseThrow().asFunctionType()),
                "typed lambda identity/signature/captures changed from resolution");
        TypedExpression body = expression.children().getFirst();
        require(lambdaBodies.put(id, body) == null,
                "typed lambda identity occurs more than once");
        validateSourceExpression(bodySyntax, body, moduleId,
                Optional.of(expression.signature().orElseThrow().returnType()));
        require(body.type().equals(expression.signature().orElseThrow().returnType()),
                "typed lambda body does not end at its return contract");
    }

    private FunctionType requireFunctionType(LyraType targetType) {
        require(!targetType.isNilable()
                        && targetType.withoutQualifiers() instanceof FunctionType,
                "typed call target is not a non-nil function contract");
        return (FunctionType) targetType.withoutQualifiers();
    }

    private void validateCallContract(
            TypedExpression expression,
            LyraType targetType,
            int argumentOffset) {
        FunctionType function = requireFunctionType(targetType);
        require(expression.children().size() - argumentOffset == function.arity(),
                "typed call arity changed from its function signature");
        for (int index = 0; index < function.arity(); index++) {
            require(expression.children().get(index + argumentOffset).type()
                            .equals(function.parameterType(index)),
                    "typed call argument does not end at its parameter contract");
        }
        require(expression.type().equals(function.returnType()),
                "typed call result changed from its function signature");
    }

    private void validateOperator(
            SyntaxNode.Operator sourceOperator,
            List<SyntaxNode.Expression> sourceOperands,
            TypedExpression expression,
            ModuleId moduleId,
            Optional<LyraType> expectedType) {
        TokenKind token = sourceOperator.tokenKind();
        TypedExpressionKind expectedKind = token == TokenKind.AND || token == TokenKind.OR
                ? TypedExpressionKind.SHORT_CIRCUIT : TypedExpressionKind.OPERATOR;
        require(expression.kind() == expectedKind
                        && expression.operator().filter(sourceOperator.spelling()::equals).isPresent()
                        && expression.children().size() == sourceOperands.size(),
                "typed operator kind/spelling/operands changed from source");
        if (token == TokenKind.MINUS && sourceOperands.size() == 1
                && sourceOperands.getFirst() instanceof SyntaxNode.IntegerLiteral integer) {
            TypedExpression literal = unwrapImplicitConversions(integer, expression.children().getFirst());
            if (literal.type().withoutQualifiers() instanceof PrimitiveType primitive
                    && primitive.isInteger()) {
                ExactNumericLiteral exact = ExactNumericLiteral.integer(integer.value(), integer.suffix());
                if (!LiteralTyping.representableAs(exact, primitive)
                        && LiteralTyping.representableAs(exact.negated(), primitive)) {
                    signedMinimumLiterals.put(literal, Boolean.TRUE);
                }
            }
        }
        boolean appliesExpectedToOperands = operatorAppliesExpectedToOperands(
                token, sourceOperands, expression, expectedType);
        Optional<LyraType> equalityTarget = token == TokenKind.EQUAL_EQUAL
                || token == TokenKind.NOT_EQUAL
                ? equalityTarget(sourceOperands) : Optional.empty();
        for (int index = 0; index < sourceOperands.size(); index++) {
            SyntaxNode.Expression sourceOperand = sourceOperands.get(index);
            TypedExpression typedOperand = expression.children().get(index);
            boolean directlyContextual = operatorContextualizesDirectOperand(
                    token, sourceOperand);
            Optional<LyraType> structuralEqualityExpected = equalityTarget.flatMap(target ->
                    equalityOperandContext(sourceOperand, target));
            Optional<LyraType> operandExpected = structuralEqualityExpected.isPresent()
                    ? structuralEqualityExpected
                    : appliesExpectedToOperands || directlyContextual
                    ? Optional.of(typedOperand.type())
                    : Optional.empty();
            validateSourceExpression(sourceOperand, typedOperand, moduleId, operandExpected);
        }
        List<LyraType> operandTypes = expression.children().stream().map(TypedExpression::type).toList();
        validateNumericSelection(
                token, sourceOperands, expression, expectedType);
        switch (token) {
            case AND, OR, XOR -> require(expression.type() == PrimitiveType.BOOL
                            && expression.children().stream().allMatch(value -> truthTestable(value.type())),
                    "typed truth operator contract is invalid");
            case NOT -> require(expression.type() == PrimitiveType.BOOL
                            && expression.children().size() == 1
                            && truthTestable(expression.children().getFirst().type()),
                    "typed not contract is invalid");
            case LESS, LESS_EQUAL, GREATER, GREATER_EQUAL -> require(
                    expression.type() == PrimitiveType.BOOL
                            && commonNonNilNumeric(operandTypes).isPresent(),
                    "typed relational operator has no common numeric contract");
            case EQUAL_EQUAL, NOT_EQUAL -> require(expression.type() == PrimitiveType.BOOL
                            && (TypeRules.commonType(operandTypes).isPresent()
                            || operandTypes.stream().anyMatch(value ->
                            value.withoutQualifiers() == PrimitiveType.BOOL)
                            && operandTypes.stream().allMatch(TypedSemanticProvenance::truthTestable)),
                    "typed value equality has no common operand contract");
            case IDENTITY_EQUAL, IDENTITY_NOT_EQUAL -> {
                LyraType base = operandTypes.getFirst().withoutQualifiers();
                require(expression.type() == PrimitiveType.BOOL
                                && (base instanceof FunctionType || base instanceof ArrayType)
                                && operandTypes.stream().allMatch(value -> !value.isNilable()
                                && value.withoutQualifiers().equals(base)),
                        "typed identity equality contract is invalid");
            }
            case PLUS -> {
                boolean strings = operandTypes.stream().allMatch(value ->
                        !value.isNilable()
                                && value.withoutQualifiers() == PrimitiveType.STRING);
                require(strings ? expression.type() == PrimitiveType.STRING
                                : expression.type().isNumeric()
                                && !expression.type().isNilable()
                                && operandTypes.stream().allMatch(expression.type()::equals),
                        "typed plus contract is invalid");
            }
            case MINUS, ASTERISK, PERCENT, CARET, INCREMENT, DECREMENT -> require(
                    expression.type().isNumeric() && !expression.type().isNilable()
                            && operandTypes.stream().allMatch(expression.type()::equals)
                            && (token != TokenKind.PERCENT || expression.type().isInteger()),
                    "typed numeric operator contract is invalid");
            case SLASH -> {
                boolean integers = operandTypes.stream().allMatch(LyraType::isInteger);
                require(expression.type().isNumeric() && !expression.type().isNilable()
                                && (integers
                                ? expression.type().isFloating()
                                && commonNonNilNumeric(operandTypes).isPresent()
                                : operandTypes.stream().allMatch(expression.type()::equals)),
                        "typed division contract is invalid");
            }
            default -> throw invalid("typed source operator is unsupported");
        }
    }

    private Optional<LyraType> equalityTarget(
            List<SyntaxNode.Expression> sourceOperands) {
        List<LyraType> known = new ArrayList<>();
        List<ExactNumericLiteral> literals = new ArrayList<>();
        List<PrimitiveType> fixed = new ArrayList<>();
        List<SyntaxNode.Expression> unresolved = new ArrayList<>();
        boolean allDirect = true;
        boolean allUnforced = true;
        boolean decimal = false;
        for (SyntaxNode.Expression source : sourceOperands) {
            ExactNumericLiteral literal = directNumericLiteral(source);
            if (literal != null) {
                literals.add(literal);
                allUnforced &= literal.forcedType().isEmpty();
                decimal |= literal.isDecimal();
                continue;
            }
            Optional<LyraType> type = synthesizedTypeWithoutContext(source);
            if (type.isEmpty()) {
                unresolved.add(source);
                continue;
            }
            allDirect = false;
            LyraType normalized = type.orElseThrow().withoutQualifiers();
            known.add(type.orElseThrow());
            if (normalized instanceof PrimitiveType primitive && primitive.isNumeric()) {
                fixed.add(primitive);
            }
        }
        if (known.isEmpty() && literals.isEmpty()) {
            return StructuralContextPlan.synthesizePeers(
                    sourceOperands,
                    this::synthesizeAtomicTypeWithoutContext,
                    type -> Optional.of(syntaxType(type, TypePosition.BINDING).withoutQualifiers()),
                    candidate -> candidate instanceof SyntaxNode.Conditional nested
                            ? synthesizeStructuralConditional(nested)
                            : Optional.empty());
        }
        boolean numeric = !fixed.isEmpty() || !literals.isEmpty();
        if (numeric && (fixed.size() + literals.size() != known.size() + literals.size()
                || known.stream().anyMatch(type -> !(type.withoutQualifiers()
                instanceof PrimitiveType primitive) || !primitive.isNumeric()))) {
            return Optional.empty();
        }
        LyraType common;
        if (numeric) {
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
                for (PrimitiveType primitive : fixed) {
                    fits &= TypeRules.canImplicitlyWiden(primitive, candidate);
                }
                if (fits) {
                    candidates.add(candidate);
                }
            }
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            PrimitiveType defaultType = decimal ? PrimitiveType.F64 : PrimitiveType.I64;
            common = allDirect && allUnforced && candidates.contains(defaultType)
                    ? defaultType : candidates.getFirst();
        } else {
            common = StructuralContextPlan.foldHomogeneous(known).orElse(null);
            if (common == null) {
                return Optional.empty();
            }
        }
        common = common.withoutQualifiers();
        LyraType target = common;
        for (SyntaxNode.Expression source : unresolved) {
            Optional<LyraType> shaped = StructuralContextPlan.expectedFromPeer(source, common);
            if (shaped.isEmpty()) {
                return Optional.empty();
            }
            target = StructuralContextPlan.mergeNilShape(target, shaped.orElseThrow());
        }
        return Optional.of(target);
    }

    private Optional<LyraType> equalityOperandContext(
            SyntaxNode.Expression source,
            LyraType target) {
        if (directNumericLiteral(source) != null
                || StructuralContextPlan.containsContextFreeNil(source)
                || StructuralContextPlan.canRecheckWithStructuralContext(source)) {
            return Optional.of(target);
        }
        return Optional.empty();
    }

    private Optional<LyraType> conditionalBranchValidationContext(
            SyntaxNode.Expression sourceBranch,
            Optional<LyraType> conditionalExpected,
            LyraType branchType,
            StructuralContextPlan.ChildPosition position) {
        boolean needsContext = conditionalExpected.isPresent()
                || directNumericLiteral(sourceBranch) != null
                || StructuralContextPlan.containsContextFreeNil(sourceBranch)
                || StructuralContextPlan.canRecheckWithStructuralContext(sourceBranch);
        if (!needsContext) {
            return Optional.empty();
        }
        return StructuralContextPlan.expectedFor(
                position, sourceBranch, conditionalExpected, Optional.of(branchType));
    }

    private boolean operatorContextualizesDirectOperand(
            TokenKind operator,
            SyntaxNode.Expression sourceOperand) {
        if (StructuralContextPlan.isContextFreeNil(sourceOperand)) {
            return operator == TokenKind.EQUAL_EQUAL || operator == TokenKind.NOT_EQUAL;
        }
        if (directNumericLiteral(sourceOperand) == null) {
            return false;
        }
        return switch (operator) {
            case PLUS, MINUS, ASTERISK, SLASH, PERCENT, CARET, INCREMENT, DECREMENT,
                    LESS, LESS_EQUAL, GREATER, GREATER_EQUAL, EQUAL_EQUAL, NOT_EQUAL -> true;
            default -> false;
        };
    }

    private boolean operatorAppliesExpectedToOperands(
            TokenKind operator,
            List<SyntaxNode.Expression> sourceOperands,
            TypedExpression expression,
            Optional<LyraType> expectedType) {
        if (expectedType.filter(LyraType::isNumeric).isEmpty()) {
            return false;
        }
        boolean arithmetic = switch (operator) {
            case PLUS, MINUS, ASTERISK, SLASH, PERCENT, CARET, INCREMENT, DECREMENT -> true;
            default -> false;
        };
        return arithmetic && (operator != TokenKind.SLASH
                || !allIntegerInputs(sourceOperands, expression.children()));
    }

    private void validateNumericSelection(
            TokenKind operator,
            List<SyntaxNode.Expression> sourceOperands,
            TypedExpression expression,
            Optional<LyraType> expectedType) {
        if (operator == TokenKind.MINUS && sourceOperands.size() == 1) {
            validateUnaryMinusSelection(
                    sourceOperands.getFirst(), expression, expectedType);
            return;
        }
        boolean arithmetic = switch (operator) {
            case PLUS, MINUS, ASTERISK, SLASH, PERCENT, CARET, INCREMENT, DECREMENT -> true;
            default -> false;
        };
        boolean relational = switch (operator) {
            case LESS, LESS_EQUAL, GREATER, GREATER_EQUAL -> true;
            default -> false;
        };
        boolean equality = operator == TokenKind.EQUAL_EQUAL || operator == TokenKind.NOT_EQUAL;
        if (!arithmetic && !relational && !equality) {
            return;
        }
        if (operator == TokenKind.PLUS && expression.type() == PrimitiveType.STRING) {
            return;
        }

        List<SyntaxNode.Expression> numericSyntax = new ArrayList<>();
        List<TypedExpression> numericOperands = new ArrayList<>();
        boolean containsNil = false;
        for (int index = 0; index < sourceOperands.size(); index++) {
            if (StructuralContextPlan.isContextFreeNil(sourceOperands.get(index))) {
                containsNil = true;
                continue;
            }
            TypedExpression core = unwrapImplicitConversions(
                    sourceOperands.get(index), expression.children().get(index));
            if (!core.type().isNumeric()) {
                if (equality) {
                    return;
                }
                throw invalid("typed numeric operator has a nonnumeric source operand");
            }
            numericSyntax.add(sourceOperands.get(index));
            numericOperands.add(expression.children().get(index));
        }
        boolean integerDivision = operator == TokenKind.SLASH
                && allIntegerInputs(numericSyntax, numericOperands);
        Optional<LyraType> commonExpected = arithmetic && !integerDivision
                ? expectedType : Optional.empty();
        PrimitiveType common = expectedNumericCommon(
                numericSyntax,
                numericOperands,
                commonExpected,
                operator == TokenKind.PERCENT || integerDivision).orElseThrow(() -> invalid(
                        "typed numeric operator has no source-derived common type"));

        LyraType operandType = containsNil ? common.nilable() : common;
        require(expression.children().stream().allMatch(value -> value.type().equals(operandType)),
                "typed numeric operands do not end at their source-derived common type");
        if (relational || equality) {
            require(expression.type() == PrimitiveType.BOOL,
                    "typed comparison/equality result is not Bool");
            return;
        }

        if (integerDivision) {
            PrimitiveType divisionType = expectedType.map(LyraType::withoutQualifiers)
                    .filter(PrimitiveType.class::isInstance)
                    .map(PrimitiveType.class::cast)
                    .filter(value -> value == PrimitiveType.F32)
                    .orElse(PrimitiveType.F64);
            require(expression.type() == divisionType,
                    "typed integer division result does not follow its F32/F64 context rule");
        } else {
            require(expression.type() == common,
                    "typed numeric result is not its source-derived common type");
        }
    }

    private void validateUnaryMinusSelection(
            SyntaxNode.Expression sourceOperand,
            TypedExpression expression,
            Optional<LyraType> expectedType) {
        ExactNumericLiteral literal = directNumericLiteral(sourceOperand);
        if (literal != null) {
            ExactNumericLiteral negated = literal.negated();
            Optional<PrimitiveType> inferred = expectedType.filter(LyraType::isNumeric)
                    .flatMap(expected -> LiteralTyping.infer(negated, expected));
            if (inferred.isEmpty() && expectedType.filter(LyraType::isNumeric).isEmpty()) {
                inferred = LiteralTyping.infer(negated);
            }
            require(inferred.filter(expression.type()::equals).isPresent(),
                    "typed unary-minus result does not match contextual exact-literal typing");
            return;
        }
        TypedExpression core = unwrapImplicitConversions(
                sourceOperand, expression.children().getFirst());
        PrimitiveType contextualType = expectedType.map(LyraType::withoutQualifiers)
                .filter(PrimitiveType.class::isInstance)
                .map(PrimitiveType.class::cast)
                .filter(PrimitiveType::isNumeric)
                .filter(expected -> TypeRules.canImplicitlyConvert(core.type(), expected))
                .orElse(null);
        LyraType resultType = contextualType == null
                ? core.type().withoutQualifiers() : contextualType;
        require(core.type().isNumeric() && !core.type().isNilable()
                        && expression.type().equals(resultType),
                "typed unary-minus result does not match its contextual operand type");
    }

    private Optional<PrimitiveType> expectedNumericCommon(
            List<SyntaxNode.Expression> sourceOperands,
            List<TypedExpression> typedOperands,
            Optional<LyraType> expectedType,
            boolean integerOnly) {
        require(!sourceOperands.isEmpty() && sourceOperands.size() == typedOperands.size(),
                "numeric source/typed operand lists disagree");
        boolean allDirect = true;
        boolean allUnforced = true;
        boolean decimal = false;
        List<ExactNumericLiteral> literals = new ArrayList<>();
        List<PrimitiveType> fixed = new ArrayList<>();
        for (int index = 0; index < sourceOperands.size(); index++) {
            ExactNumericLiteral literal = directNumericLiteral(sourceOperands.get(index));
            if (literal != null) {
                literals.add(literal);
                allUnforced &= literal.forcedType().isEmpty();
                decimal |= literal.isDecimal();
            } else {
                allDirect = false;
                LyraType coreType = unwrapImplicitConversions(
                        sourceOperands.get(index), typedOperands.get(index)).type();
                require(coreType.isNumeric(),
                        "fixed numeric operand is not a primitive numeric type");
                fixed.add((PrimitiveType) coreType.withoutQualifiers());
            }
        }
        List<PrimitiveType> candidates = new ArrayList<>();
        for (PrimitiveType candidate : NUMERIC_CANDIDATES) {
            if (integerOnly && !candidate.isInteger()) {
                continue;
            }
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
            for (PrimitiveType primitive : fixed) {
                fits &= TypeRules.canImplicitlyWiden(primitive, candidate);
            }
            if (fits) {
                candidates.add(candidate);
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        PrimitiveType expected = expectedType.map(LyraType::withoutQualifiers)
                .filter(PrimitiveType.class::isInstance)
                .map(PrimitiveType.class::cast)
                .filter(PrimitiveType::isNumeric)
                .orElse(null);
        if (expected != null && candidates.contains(expected)) {
            return Optional.of(expected);
        }
        if (allDirect && allUnforced && expectedType.isEmpty()) {
            PrimitiveType defaultType = decimal ? PrimitiveType.F64 : PrimitiveType.I64;
            if (candidates.contains(defaultType)) {
                return Optional.of(defaultType);
            }
        }
        return Optional.of(candidates.getFirst());
    }

    private boolean allIntegerInputs(
            List<SyntaxNode.Expression> sourceOperands,
            List<TypedExpression> typedOperands) {
        for (int index = 0; index < sourceOperands.size(); index++) {
            ExactNumericLiteral literal = directNumericLiteral(sourceOperands.get(index));
            if (literal != null) {
                if (!literal.isInteger()) {
                    return false;
                }
            } else if (!unwrapImplicitConversions(
                    sourceOperands.get(index), typedOperands.get(index)).type().isInteger()) {
                return false;
            }
        }
        return true;
    }

    private static ExactNumericLiteral directNumericLiteral(SyntaxNode.Expression expression) {
        if (expression instanceof SyntaxNode.IntegerLiteral integer) {
            return ExactNumericLiteral.integer(integer.value(), integer.suffix());
        }
        if (expression instanceof SyntaxNode.FloatLiteral decimal) {
            return ExactNumericLiteral.decimal(decimal.value(), decimal.suffix());
        }
        return null;
    }

    private LyraType inferredConditionalType(SyntaxNode.Conditional syntax) {
        if (syntax.elseBranch().isEmpty()) {
            return PrimitiveType.UNIT;
        }
        Optional<LyraType> synthesized = synthesizedTypeWithoutContext(syntax);
        return synthesized.orElseThrow(() -> invalid(
                        "inferred conditional branches do not provide a complete peer shape"));
    }

    private Optional<LyraType> synthesizedTypeWithoutContext(SyntaxNode.Expression syntax) {
        var constructed = resolved.syntaxLinks().stream().filter(link -> link.kind() == SyntaxLinkKind.CALL
                        && link.span().equals(syntax.span())).flatMap(link -> link.declarationId().stream())
                .flatMap(id -> resolved.nominals().stream().filter(value -> value.declaration().equals(id)))
                .map(value -> (LyraType) value.schema().type()).findFirst();
        if (constructed.isPresent()) return constructed;
        if (syntax instanceof SyntaxNode.ArrayLiteral
                || syntax instanceof SyntaxNode.TupleLiteral
                || syntax instanceof SyntaxNode.Block) {
            Optional<LyraType> established = synthesizeAtomicTypeWithoutContext(syntax);
            if (established.isPresent()) {
                return established;
            }
        }
        if (syntax instanceof SyntaxNode.Conditional conditional
                && conditional.elseBranch().isPresent()) {
            Optional<LyraType> conditionalShape = synthesizeStructuralConditional(conditional);
            if (conditionalShape.isPresent()) {
                return conditionalShape;
            }
        }
        if (syntax instanceof SyntaxNode.Match match) {
            Optional<LyraType> matchShape = synthesizeStructuralMatch(match);
            if (matchShape.isPresent()) {
                return matchShape;
            }
        }
        return StructuralContextPlan.synthesize(
                syntax,
                this::synthesizeAtomicTypeWithoutContext,
                type -> Optional.of(syntaxType(type, TypePosition.BINDING).withoutQualifiers()),
                this::synthesizeControlResult);
    }

    private Optional<LyraType> synthesizeAtomicTypeWithoutContext(
            SyntaxNode.Expression syntax) {
        if (syntax instanceof SyntaxNode.Range range) {
            return numericCommonWithoutContext(List.of(range.start(), range.end(), range.step()), false)
                    .map(io.mindspice.lyra.compiler.types.RangeType::of);
        }
        if (syntax instanceof SyntaxNode.NilLiteral) {
            return Optional.empty();
        }
        if (syntax instanceof SyntaxNode.IntegerLiteral integer) {
            return LiteralTyping.infer(ExactNumericLiteral.integer(integer.value(), integer.suffix()))
                    .map(value -> (LyraType) value);
        }
        if (syntax instanceof SyntaxNode.FloatLiteral decimal) {
            return LiteralTyping.infer(ExactNumericLiteral.decimal(decimal.value(), decimal.suffix()))
                    .map(value -> (LyraType) value);
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
        if (syntax instanceof SyntaxNode.Identifier identifier) {
            ResolvedReference reference = findReference(
                    identifier.span(), identifier.name(), ReferenceKind.VALUE);
            return sourceValueType(reference);
        }
        if (syntax instanceof SyntaxNode.Block block) {
            if (block.forms().isEmpty()) {
                return Optional.of(PrimitiveType.UNIT);
            }
            SyntaxNode.Form finalForm = block.forms().getLast();
            return finalForm instanceof SyntaxNode.Expression finalExpression
                    ? synthesizedTypeWithoutContext(finalExpression)
                    : Optional.of(PrimitiveType.UNIT);
        }
        if (syntax instanceof SyntaxNode.Conditional conditional) {
            if (conditional.elseBranch().isEmpty()) {
                return Optional.of(PrimitiveType.UNIT);
            }
            Optional<LyraType> conditionalShape = synthesizeStructuralConditional(conditional);
            if (conditionalShape.isPresent()) {
                return conditionalShape;
            }
            if (StructuralContextPlan.isBaseLessNil(conditional)) {
                return Optional.empty();
            }
            return Optional.of(inferredConditionalType(conditional));
        }
        if (syntax instanceof SyntaxNode.Match match) {
            return synthesizeStructuralMatch(match);
        }
        if (syntax instanceof SyntaxNode.ArrayLiteral array) {
            if (array.explicitType().isPresent()) {
                return Optional.of(syntaxType(array.explicitType().orElseThrow(), TypePosition.BINDING)
                        .withoutQualifiers());
            }
            if (array.elements().isEmpty()
                    || StructuralContextPlan.isBaseLessNil(array)) {
                return Optional.empty();
            }
            LyraType element = inferredArrayType(array, null, null);
            return Optional.of(ArrayType.of(element));
        }
        if (syntax instanceof SyntaxNode.TupleLiteral tuple) {
            if (tuple.elements().isEmpty()
                    || StructuralContextPlan.isBaseLessNil(tuple)) {
                return Optional.empty();
            }
            if (tuple.explicitType().isPresent()) {
                return Optional.of(syntaxType(tuple.explicitType().orElseThrow(), TypePosition.BINDING)
                        .withoutQualifiers());
            }
            return Optional.of(inferredTupleType(tuple, null, null));
        }
        if (syntax instanceof SyntaxNode.IndexAccess index) {
            LyraType receiver = synthesizedTypeWithoutContext(index.receiver())
                    .orElseThrow(() -> invalid("index receiver has no context-free type"));
            LyraType base = receiver.withoutQualifiers();
            if (base == PrimitiveType.STRING) {
                return Optional.of(PrimitiveType.CHAR);
            }
            if (base instanceof ArrayType array) {
                return Optional.of(array.elementType());
            }
            throw invalid("index receiver is not a String or Array");
        }
        if (syntax instanceof SyntaxNode.Coalesce coalesce) {
            Optional<LyraType> value = synthesizedTypeWithoutContext(coalesce.value());
            if (value.isEmpty() || !value.orElseThrow().isNilable()) {
                return Optional.empty();
            }
            return Optional.of(value.orElseThrow().withoutQualifiers());
        }
        if (syntax instanceof SyntaxNode.Lambda lambda) {
            return lambdaAt(lambda.span()).flatMap(ResolvedLambda::signature)
                    .map(LyraSignature::asFunctionType);
        }
        if (syntax instanceof SyntaxNode.CompactLambda) {
            return Optional.empty();
        }
        if (syntax instanceof SyntaxNode.CallableCall call) {
            if (CallbackLoop.of(call).isPresent()) return Optional.of(PrimitiveType.UNIT);
            return synthesizedTypeWithoutContext(call.target()).flatMap(this::callResultType);
        }
        if (syntax instanceof SyntaxNode.DirectCall call) {
            if (CallbackLoop.of(call).isPresent()) return Optional.of(PrimitiveType.UNIT);
            ResolvedReference reference = findReference(
                    call.name().span(), call.name().name(), ReferenceKind.DIRECT_CALL_TARGET);
            return sourceValueType(reference).flatMap(this::callResultType);
        }
        if (syntax instanceof SyntaxNode.NamespaceDirectCall call) {
            ResolvedReference reference = findReference(
                    call.name().span(), call.name().name(), ReferenceKind.NAMESPACE_DIRECT_CALL);
            return sourceValueType(reference).flatMap(this::callResultType);
        }
        if (syntax instanceof SyntaxNode.MemberAccess access) {
            return synthesizedTypeWithoutContext(access.receiver())
                    .flatMap(receiver -> memberType(receiver, access.member()));
        }
        if (syntax instanceof SyntaxNode.NamespaceMemberAccess access) {
            ResolvedReference reference = findReference(
                    access.member().span(), access.member().name(), ReferenceKind.NAMESPACE_MEMBER);
            return sourceValueType(reference);
        }
        if (syntax instanceof SyntaxNode.PrefixAssignment
                || syntax instanceof SyntaxNode.Reassignment) {
            return Optional.of(PrimitiveType.UNIT);
        }
        if (syntax instanceof SyntaxNode.TypeConversion conversion) {
            return PrimitiveType.fromSpelling(conversion.targetPrimitive().name())
                    .map(value -> (LyraType) value);
        }
        if (syntax instanceof SyntaxNode.OperatorSExpression operator) {
            return Optional.of(operatorTypeWithoutContext(operator.operator(), operator.operands()));
        }
        if (syntax instanceof SyntaxNode.OperatorBracket operator) {
            return Optional.of(operatorTypeWithoutContext(operator.operator(), operator.operands()));
        }
        return Optional.empty();
    }

    private Optional<LyraType> synthesizeStructuralConditional(
            SyntaxNode.Conditional conditional) {
        if (conditional.elseBranch().isEmpty()) {
            return Optional.of(PrimitiveType.UNIT);
        }
        Optional<LyraType> thenType = synthesizedTypeWithoutContext(conditional.thenBranch());
        Optional<LyraType> elseType = synthesizedTypeWithoutContext(
                conditional.elseBranch().orElseThrow());
        if (thenType.isEmpty() && elseType.isEmpty()) {
            return Optional.empty();
        }
        if (thenType.isEmpty()) {
            return StructuralContextPlan.expectedFromPeer(
                    conditional.thenBranch(), elseType.orElseThrow());
        }
        if (elseType.isEmpty()) {
            return StructuralContextPlan.expectedFromPeer(
                    conditional.elseBranch().orElseThrow(), thenType.orElseThrow());
        }
        if (thenType.orElseThrow().isNumeric() && elseType.orElseThrow().isNumeric()
                && !thenType.orElseThrow().isNilable()
                && !elseType.orElseThrow().isNilable()) {
            return numericCommonWithoutContext(
                    List.of(conditional.thenBranch(), conditional.elseBranch().orElseThrow()),
                    false).map(value -> (LyraType) value);
        }
        return StructuralContextPlan.foldHomogeneous(List.of(
                thenType.orElseThrow(), elseType.orElseThrow()));
    }

    private Optional<LyraType> synthesizeStructuralMatch(SyntaxNode.Match match) {
        List<SyntaxNode.Expression> results = match.arms().stream()
                .map(SyntaxNode.MatchArm::result).toList();
        List<Optional<LyraType>> shapes = results.stream()
                .map(this::synthesizedTypeWithoutContext).toList();
        List<LyraType> known = shapes.stream().flatMap(Optional::stream)
                .map(TypedSemanticProvenance::removeMutableQualifier).toList();
        if (known.isEmpty()) {
            return StructuralContextPlan.synthesizePeers(
                    results, this::synthesizeAtomicTypeWithoutContext,
                    type -> Optional.of(syntaxType(type, TypePosition.BINDING).withoutQualifiers()),
                    this::synthesizeControlResult);
        }
        LyraType common;
        if (known.size() == results.size() && known.stream().allMatch(LyraType::isNumeric)
                && known.stream().noneMatch(LyraType::isNilable)) {
            common = numericCommonWithoutContext(results, false)
                    .map(value -> (LyraType) value).orElse(null);
        } else {
            common = StructuralContextPlan.foldHomogeneous(known).orElse(null);
        }
        if (common == null) {
            return Optional.empty();
        }
        for (int index = 0; index < results.size(); index++) {
            if (shapes.get(index).isEmpty()
                    || StructuralContextPlan.containsContextFreeNil(results.get(index))) {
                Optional<LyraType> shaped = StructuralContextPlan.expectedFromPeer(results.get(index), common);
                if (shaped.isEmpty()) {
                    return Optional.empty();
                }
                common = StructuralContextPlan.mergeNilShape(common, shaped.orElseThrow());
            }
        }
        return Optional.of(common);
    }

    private Optional<LyraType> synthesizeControlResult(SyntaxNode.Expression expression) {
        if (expression instanceof SyntaxNode.Conditional conditional) {
            return synthesizeStructuralConditional(conditional);
        }
        if (expression instanceof SyntaxNode.Match match) {
            return synthesizeStructuralMatch(match);
        }
        return Optional.empty();
    }

    private LyraType inferredMatchType(SyntaxNode.Match match) {
        return synthesizeStructuralMatch(match).orElseThrow(() -> invalid(
                "inferred match results do not provide a complete peer shape"));
    }

    private LyraType inferredMatchComparison(
            SyntaxNode.Expression subject, SyntaxNode.Expression pattern) {
        Optional<LyraType> subjectType = synthesizedTypeWithoutContext(subject)
                .map(TypedSemanticProvenance::removeMutableQualifier);
        if (subjectType.isEmpty()) {
            throw invalid("match subject has no independently synthesized type");
        }
        if (StructuralContextPlan.isContextFreeNil(pattern)) {
            require(subjectType.orElseThrow().isNilable(), "#NIL match pattern has a non-nilable subject");
            return subjectType.orElseThrow();
        }
        LyraType patternType = synthesizedTypeWithoutContext(pattern)
                .map(TypedSemanticProvenance::removeMutableQualifier)
                .orElseThrow(() -> invalid("match pattern has no independently synthesized type"));
        if (subjectType.orElseThrow().isNumeric() && patternType.isNumeric()
                && !subjectType.orElseThrow().isNilable() && !patternType.isNilable()) {
            PrimitiveType subjectPrimitive = (PrimitiveType) subjectType.orElseThrow().withoutQualifiers();
            ExactNumericLiteral literal = directNumericLiteral(pattern);
            if (literal == null) {
                return TypeRules.commonNumericType(subjectPrimitive, patternType)
                        .orElseThrow(() -> invalid("match equality has no common numeric type"));
            }
            for (PrimitiveType candidate : NUMERIC_CANDIDATES) {
                boolean subjectFits = TypeRules.canImplicitlyWiden(subjectPrimitive, candidate);
                boolean patternFits = literal.forcedType().isPresent()
                        ? LiteralTyping.representableAs(literal, literal.forcedType().orElseThrow())
                        && TypeRules.canImplicitlyWiden(
                                literal.forcedType().orElseThrow(), candidate)
                        : LiteralTyping.representableAs(literal, candidate);
                if (subjectFits && patternFits) {
                    return candidate;
                }
            }
            throw invalid("match equality has no common numeric type");
        }
        return StructuralContextPlan.foldHomogeneous(List.of(subjectType.orElseThrow(), patternType))
                .orElseThrow(() -> invalid("match equality has no compatible value type"));
    }

    private Optional<LyraType> synthesizeNumericConditional(
            SyntaxNode.Conditional conditional) {
        Optional<LyraType> thenType = synthesizedTypeWithoutContext(conditional.thenBranch());
        Optional<LyraType> elseType = synthesizedTypeWithoutContext(
                conditional.elseBranch().orElseThrow());
        if (thenType.isEmpty() || elseType.isEmpty()
                || !thenType.orElseThrow().isNumeric()
                || !elseType.orElseThrow().isNumeric()
                || thenType.orElseThrow().isNilable()
                || elseType.orElseThrow().isNilable()) {
            return Optional.empty();
        }
        return numericCommonWithoutContext(
                List.of(conditional.thenBranch(), conditional.elseBranch().orElseThrow()),
                false).map(value -> (LyraType) value);
    }

    private Optional<LyraType> sourceValueType(ResolvedReference reference) {
        DeclarationId declarationId = reference.targetDeclaration().orElse(null);
        if (declarationId == null) {
            return Optional.empty();
        }
        Optional<LyraType> type = sourceDeclarationType(declarationId);
        if (type.isEmpty()) {
            return Optional.empty();
        }
        ResolvedDeclaration declaration = resolved.declaration(declarationId).orElseThrow();
        if (declaration.bindingMutability().isMutable()
                && !declaration.imported()
                && declaration.kind() != DeclarationKind.IMPORT_MODULE
                && declaration.moduleId().equals(reference.moduleId())) {
            return Optional.of(type.orElseThrow().mutable());
        }
        return type;
    }

    private Optional<LyraType> sourceDeclarationType(DeclarationId id) {
        LyraType cached = sourceDeclarationTypes.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        if (!sourceTypeInProgress.add(id)) {
            return Optional.empty();
        }
        try {
            ResolvedDeclaration declaration = resolved.declaration(id).orElseThrow();
            if (declaration.effectiveContract().isPresent()) {
                LyraType type = declaration.effectiveContract().orElseThrow().valueType();
                sourceDeclarationTypes.put(id, type);
                return Optional.of(type);
            }
            if (declaration.kind() == DeclarationKind.PREDICATE_BINDING) {
                Optional<SyntaxNode.Conditional> conditional = findPredicateConditional(declaration.nameSpan());
                if (conditional.isEmpty()) {
                    return Optional.empty();
                }
                Optional<LyraType> predicate = synthesizedTypeWithoutContext(
                        conditional.orElseThrow().predicate());
                if (predicate.isEmpty()) {
                    return Optional.empty();
                }
                LyraType narrowed = predicate.orElseThrow().withoutQualifiers();
                sourceDeclarationTypes.put(id, narrowed);
                return Optional.of(narrowed);
            }
            Optional<SyntaxNode.LetBinding> source = findSourceLet(declaration.nameSpan());
            if (source.isEmpty()) {
                return Optional.empty();
            }
            Optional<LyraType> initializer = synthesizedTypeWithoutContext(source.orElseThrow().initializer());
            if (initializer.isEmpty()) {
                return Optional.empty();
            }
            LyraType type = removeMutableQualifier(initializer.orElseThrow());
            if (source.orElseThrow().modifiers().stream()
                    .anyMatch(modifier -> modifier.kind() == ModifierKind.NILABLE)) {
                type = type.isNilable() ? type : type.nilable();
            }
            sourceDeclarationTypes.put(id, type);
            return Optional.of(type);
        } finally {
            sourceTypeInProgress.remove(id);
        }
    }

    private Optional<LyraType> callResultType(LyraType type) {
        LyraType base = type.withoutQualifiers();
        return base instanceof FunctionType function
                ? Optional.of(function.returnType())
                : Optional.empty();
    }

    private Optional<ResolvedLambda> lambdaAt(SourceSpan span) {
        return resolved.lambdas().stream().filter(lambda -> lambda.span().equals(span)).findFirst();
    }

    private Optional<LyraType> memberType(
            LyraType receiver,
            SyntaxNode.MemberName member) {
        if (receiver.isNilable()) {
            return Optional.empty();
        }
        LyraType base = receiver.withoutQualifiers();
        if (member.isIdentifier() && member.name().equals("length")
                && (base == PrimitiveType.STRING || base instanceof ArrayType)) {
            return Optional.of(PrimitiveType.I32);
        }
        if (member.isTupleIndex() && base instanceof TupleType tuple) {
            BigInteger index = member.index();
            if (index.bitLength() <= 31 && index.intValue() < tuple.arity()) {
                return Optional.of(tuple.memberType(index.intValue()));
            }
        }
        return Optional.empty();
    }

    private Optional<SyntaxNode.LetBinding> findSourceLet(SourceSpan nameSpan) {
        for (var module : resolved.moduleGraph().modules()) {
            Optional<SyntaxNode.LetBinding> found = module.program().forms().stream()
                    .map(form -> findSourceLet(form, nameSpan))
                    .flatMap(Optional::stream)
                    .findFirst();
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private Optional<SyntaxNode.LetBinding> findSourceLet(
            SyntaxNode.Form form,
            SourceSpan nameSpan) {
        if (form instanceof SyntaxNode.LetBinding let) {
            if (let.name().span().equals(nameSpan)) {
                return Optional.of(let);
            }
            return findSourceLet(let.initializer(), nameSpan);
        }
        if (form instanceof SyntaxNode.Reassignment assignment) {
            return findSourceLet(assignment.target(), nameSpan)
                    .or(() -> findSourceLet(assignment.value(), nameSpan));
        }
        return form instanceof SyntaxNode.Expression expression
                ? findSourceLet(expression, nameSpan) : Optional.empty();
    }

    private Optional<SyntaxNode.LetBinding> findSourceLet(
            SyntaxNode.Expression expression,
            SourceSpan nameSpan) {
        if (expression instanceof SyntaxNode.Block block) {
            for (SyntaxNode.Form form : block.forms()) {
                Optional<SyntaxNode.LetBinding> found = findSourceLet(form, nameSpan);
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        }
        if (expression instanceof SyntaxNode.Lambda lambda) {
            return findSourceLet(lambda.body(), nameSpan);
        }
        if (expression instanceof SyntaxNode.CompactLambda lambda) {
            return findSourceLet(lambda.body(), nameSpan);
        }
        if (expression instanceof SyntaxNode.Conditional conditional) {
            return findSourceLet(conditional.predicate(), nameSpan)
                    .or(() -> findSourceLet(conditional.thenBranch(), nameSpan))
                    .or(() -> conditional.elseBranch().flatMap(value -> findSourceLet(value, nameSpan)));
        }
        if (expression instanceof SyntaxNode.Match match) {
            Optional<SyntaxNode.LetBinding> found = match.subject()
                    .flatMap(value -> findSourceLet(value, nameSpan));
            if (found.isPresent()) {
                return found;
            }
            for (SyntaxNode.MatchArm arm : match.arms()) {
                found = arm.pattern().flatMap(value -> findSourceLet(value, nameSpan))
                        .or(() -> arm.guard().flatMap(value -> findSourceLet(value, nameSpan)))
                        .or(() -> findSourceLet(arm.result(), nameSpan));
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        }
        if (expression instanceof SyntaxNode.Coalesce coalesce) {
            return findSourceLet(coalesce.value(), nameSpan)
                    .or(() -> findSourceLet(coalesce.fallback(), nameSpan));
        }
        if (expression instanceof SyntaxNode.PrefixAssignment assignment) {
            return findSourceLet(assignment.target(), nameSpan)
                    .or(() -> findSourceLet(assignment.value(), nameSpan));
        }
        if (expression instanceof SyntaxNode.Reassignment assignment) {
            return findSourceLet(assignment.target(), nameSpan)
                    .or(() -> findSourceLet(assignment.value(), nameSpan));
        }
        if (expression instanceof SyntaxNode.CallableCall call) {
            return findSourceLet(call.target(), nameSpan)
                    .or(() -> call.arguments().stream()
                            .map(value -> findSourceLet(value, nameSpan))
                            .flatMap(Optional::stream).findFirst());
        }
        if (expression instanceof SyntaxNode.DirectCall call) {
            return call.receiver().flatMap(value -> findSourceLet(value, nameSpan))
                    .or(() -> call.argumentExpressions().stream()
                            .map(value -> findSourceLet(value, nameSpan))
                            .flatMap(Optional::stream).findFirst());
        }
        if (expression instanceof SyntaxNode.NamespaceDirectCall call) {
            return call.argumentExpressions().stream()
                    .map(value -> findSourceLet(value, nameSpan))
                    .flatMap(Optional::stream).findFirst();
        }
        if (expression instanceof SyntaxNode.MemberAccess access) {
            return findSourceLet(access.receiver(), nameSpan);
        }
        if (expression instanceof SyntaxNode.IndexAccess access) {
            return findSourceLet(access.receiver(), nameSpan)
                    .or(() -> findSourceLet(access.index(), nameSpan));
        }
        if (expression instanceof SyntaxNode.OperatorSExpression operator) {
            return operator.operands().stream()
                    .map(value -> findSourceLet(value, nameSpan))
                    .flatMap(Optional::stream).findFirst();
        }
        if (expression instanceof SyntaxNode.OperatorBracket operator) {
            return operator.operands().stream()
                    .map(value -> findSourceLet(value, nameSpan))
                    .flatMap(Optional::stream).findFirst();
        }
        if (expression instanceof SyntaxNode.TypeConversion conversion) {
            return findSourceLet(conversion.value(), nameSpan);
        }
        if (expression instanceof SyntaxNode.ArrayLiteral array) {
            return array.elements().stream()
                    .map(value -> findSourceLet(value, nameSpan))
                    .flatMap(Optional::stream).findFirst();
        }
        if (expression instanceof SyntaxNode.Range range) {
            return List.of(range.start(), range.end(), range.step()).stream()
                    .map(value -> findSourceLet(value, nameSpan))
                    .flatMap(Optional::stream).findFirst();
        }
        if (expression instanceof SyntaxNode.TupleLiteral tuple) {
            return tuple.elements().stream()
                    .map(value -> findSourceLet(value, nameSpan))
                    .flatMap(Optional::stream).findFirst();
        }
        return Optional.empty();
    }

    private Optional<SyntaxNode.Conditional> findPredicateConditional(SourceSpan bindingSpan) {
        for (var module : resolved.moduleGraph().modules()) {
            Optional<SyntaxNode.Conditional> found = module.program().forms().stream()
                    .map(form -> findPredicateConditional(form, bindingSpan))
                    .flatMap(Optional::stream)
                    .findFirst();
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private Optional<SyntaxNode.Conditional> findPredicateConditional(
            SyntaxNode.Form form,
            SourceSpan bindingSpan) {
        if (form instanceof SyntaxNode.LetBinding let) {
            return findPredicateConditional(let.initializer(), bindingSpan);
        }
        if (form instanceof SyntaxNode.Reassignment assignment) {
            return findPredicateConditional(assignment.target(), bindingSpan)
                    .or(() -> findPredicateConditional(assignment.value(), bindingSpan));
        }
        return form instanceof SyntaxNode.Expression expression
                ? findPredicateConditional(expression, bindingSpan) : Optional.empty();
    }

    private Optional<SyntaxNode.Conditional> findPredicateConditional(
            SyntaxNode.Expression expression,
            SourceSpan bindingSpan) {
        if (expression instanceof SyntaxNode.Block block) {
            for (SyntaxNode.Form form : block.forms()) {
                Optional<SyntaxNode.Conditional> found =
                        findPredicateConditional(form, bindingSpan);
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        }
        if (expression instanceof SyntaxNode.Lambda lambda) {
            return findPredicateConditional(lambda.body(), bindingSpan);
        }
        if (expression instanceof SyntaxNode.CompactLambda lambda) {
            return findPredicateConditional(lambda.body(), bindingSpan);
        }
        if (expression instanceof SyntaxNode.Conditional conditional) {
            if (conditional.binding().filter(value -> value.name().span().equals(bindingSpan)).isPresent()) {
                return Optional.of(conditional);
            }
            return findPredicateConditional(conditional.predicate(), bindingSpan)
                    .or(() -> findPredicateConditional(conditional.thenBranch(), bindingSpan))
                    .or(() -> conditional.elseBranch()
                            .flatMap(value -> findPredicateConditional(value, bindingSpan)));
        }
        if (expression instanceof SyntaxNode.Match match) {
            Optional<SyntaxNode.Conditional> found = match.subject()
                    .flatMap(value -> findPredicateConditional(value, bindingSpan));
            if (found.isPresent()) {
                return found;
            }
            for (SyntaxNode.MatchArm arm : match.arms()) {
                found = arm.pattern().flatMap(value -> findPredicateConditional(value, bindingSpan))
                        .or(() -> arm.guard().flatMap(value ->
                                findPredicateConditional(value, bindingSpan)))
                        .or(() -> findPredicateConditional(arm.result(), bindingSpan));
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        }
        if (expression instanceof SyntaxNode.Coalesce coalesce) {
            return findPredicateConditional(coalesce.value(), bindingSpan)
                    .or(() -> findPredicateConditional(coalesce.fallback(), bindingSpan));
        }
        if (expression instanceof SyntaxNode.PrefixAssignment assignment) {
            return findPredicateConditional(assignment.target(), bindingSpan)
                    .or(() -> findPredicateConditional(assignment.value(), bindingSpan));
        }
        if (expression instanceof SyntaxNode.Reassignment assignment) {
            return findPredicateConditional(assignment.target(), bindingSpan)
                    .or(() -> findPredicateConditional(assignment.value(), bindingSpan));
        }
        if (expression instanceof SyntaxNode.CallableCall call) {
            return findPredicateConditional(call.target(), bindingSpan)
                    .or(() -> call.arguments().stream()
                            .map(value -> findPredicateConditional(value, bindingSpan))
                            .flatMap(Optional::stream).findFirst());
        }
        if (expression instanceof SyntaxNode.DirectCall call) {
            return call.receiver().flatMap(value -> findPredicateConditional(value, bindingSpan))
                    .or(() -> call.argumentExpressions().stream()
                            .map(value -> findPredicateConditional(value, bindingSpan))
                            .flatMap(Optional::stream).findFirst());
        }
        if (expression instanceof SyntaxNode.NamespaceDirectCall call) {
            return call.argumentExpressions().stream()
                    .map(value -> findPredicateConditional(value, bindingSpan))
                    .flatMap(Optional::stream).findFirst();
        }
        if (expression instanceof SyntaxNode.MemberAccess access) {
            return findPredicateConditional(access.receiver(), bindingSpan);
        }
        if (expression instanceof SyntaxNode.NamespaceMemberAccess) {
            return Optional.empty();
        }
        if (expression instanceof SyntaxNode.IndexAccess access) {
            return findPredicateConditional(access.receiver(), bindingSpan)
                    .or(() -> findPredicateConditional(access.index(), bindingSpan));
        }
        if (expression instanceof SyntaxNode.OperatorSExpression operator) {
            return operator.operands().stream()
                    .map(value -> findPredicateConditional(value, bindingSpan))
                    .flatMap(Optional::stream).findFirst();
        }
        if (expression instanceof SyntaxNode.OperatorBracket operator) {
            return operator.operands().stream()
                    .map(value -> findPredicateConditional(value, bindingSpan))
                    .flatMap(Optional::stream).findFirst();
        }
        if (expression instanceof SyntaxNode.TypeConversion conversion) {
            return findPredicateConditional(conversion.value(), bindingSpan);
        }
        if (expression instanceof SyntaxNode.ArrayLiteral array) {
            return array.elements().stream()
                    .map(value -> findPredicateConditional(value, bindingSpan))
                    .flatMap(Optional::stream).findFirst();
        }
        if (expression instanceof SyntaxNode.Range range) {
            return List.of(range.start(), range.end(), range.step()).stream()
                    .map(value -> findPredicateConditional(value, bindingSpan))
                    .flatMap(Optional::stream).findFirst();
        }
        if (expression instanceof SyntaxNode.TupleLiteral tuple) {
            return tuple.elements().stream()
                    .map(value -> findPredicateConditional(value, bindingSpan))
                    .flatMap(Optional::stream).findFirst();
        }
        return Optional.empty();
    }

    private LyraType operatorTypeWithoutContext(
            SyntaxNode.Operator sourceOperator,
            List<SyntaxNode.Expression> sourceOperands) {
        TokenKind operator = sourceOperator.tokenKind();
        if (operator == TokenKind.AND || operator == TokenKind.OR || operator == TokenKind.XOR
                || operator == TokenKind.NOT || operator == TokenKind.LESS
                || operator == TokenKind.LESS_EQUAL || operator == TokenKind.GREATER
                || operator == TokenKind.GREATER_EQUAL || operator == TokenKind.EQUAL_EQUAL
                || operator == TokenKind.NOT_EQUAL || operator == TokenKind.IDENTITY_EQUAL
                || operator == TokenKind.IDENTITY_NOT_EQUAL) {
            return PrimitiveType.BOOL;
        }
        if (operator == TokenKind.PLUS) {
            boolean strings = true;
            for (int index = 0; index < sourceOperands.size(); index++) {
                Optional<LyraType> operandType = synthesizedTypeWithoutContext(
                        sourceOperands.get(index));
                strings &= operandType.filter(type -> !type.isNilable()
                        && type.withoutQualifiers() == PrimitiveType.STRING).isPresent();
            }
            if (strings) {
                return PrimitiveType.STRING;
            }
        }
        if (operator == TokenKind.MINUS && sourceOperands.size() == 1) {
            ExactNumericLiteral literal = directNumericLiteral(sourceOperands.getFirst());
            if (literal != null) {
                return LiteralTyping.infer(literal.negated()).orElseThrow(() -> invalid(
                        "context-free unary-minus literal has no canonical type"));
            }
            LyraType operandType = synthesizedTypeWithoutContext(
                    sourceOperands.getFirst())
                    .orElseThrow(() -> invalid(
                            "context-free unary minus has an untyped operand"));
            require(operandType.isNumeric() && !operandType.isNilable(),
                    "context-free unary minus has a nonnumeric operand");
            return operandType.withoutQualifiers();
        }
        boolean integerInputs = operator == TokenKind.SLASH
                && integerInputsWithoutContext(sourceOperands);
        PrimitiveType common = numericCommonWithoutContext(
                sourceOperands,
                operator == TokenKind.PERCENT || integerInputs).orElseThrow(() -> invalid(
                "context-free numeric operator has no canonical common type"));
        return integerInputs ? PrimitiveType.F64 : common;
    }

    private Optional<PrimitiveType> numericCommonWithoutContext(
            List<SyntaxNode.Expression> sourceOperands,
            boolean integerOnly) {
        require(!sourceOperands.isEmpty(),
                "context-free numeric source operand list is empty");
        boolean allDirect = true;
        boolean allUnforced = true;
        boolean decimal = false;
        List<ExactNumericLiteral> literals = new ArrayList<>();
        List<PrimitiveType> fixed = new ArrayList<>();
        for (int index = 0; index < sourceOperands.size(); index++) {
            ExactNumericLiteral literal = directNumericLiteral(sourceOperands.get(index));
            if (literal != null) {
                literals.add(literal);
                allUnforced &= literal.forcedType().isEmpty();
                decimal |= literal.isDecimal();
                continue;
            }
            allDirect = false;
            LyraType type = synthesizedTypeWithoutContext(
                    sourceOperands.get(index))
                    .orElseThrow(() -> invalid(
                            "context-free numeric operand cannot be #NIL"));
            if (!type.isNumeric() || type.isNilable()) {
                return Optional.empty();
            }
            fixed.add((PrimitiveType) type.withoutQualifiers());
        }
        List<PrimitiveType> candidates = new ArrayList<>();
        for (PrimitiveType candidate : NUMERIC_CANDIDATES) {
            if (integerOnly && !candidate.isInteger()) {
                continue;
            }
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
            for (PrimitiveType primitive : fixed) {
                fits &= TypeRules.canImplicitlyWiden(primitive, candidate);
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

    private boolean integerInputsWithoutContext(
            List<SyntaxNode.Expression> sourceOperands) {
        for (int index = 0; index < sourceOperands.size(); index++) {
            ExactNumericLiteral literal = directNumericLiteral(sourceOperands.get(index));
            if (literal != null) {
                if (!literal.isInteger()) {
                    return false;
                }
                continue;
            }
            Optional<LyraType> type = synthesizedTypeWithoutContext(
                    sourceOperands.get(index));
            if (type.isEmpty() || !type.orElseThrow().isInteger()) {
                return false;
            }
        }
        return true;
    }

    private void validateMemberContract(TypedExpression expression, LyraType receiverType) {
        require(!receiverType.isNilable(),
                "typed member receiver is nilable");
        LyraType base = receiverType.withoutQualifiers();
        if (base instanceof io.mindspice.lyra.compiler.types.NominalType nominalType) {
            var nominal = resolved.nominals().stream().filter(value -> value.schema().type().equals(nominalType))
                    .findFirst().orElseThrow(() -> invalid("nominal member receiver has no schema"));
            int index = expression.declarationId().map(nominal.members()::indexOf).orElse(-1);
            require(index >= 0 && expression.tupleIndex().isEmpty(), "nominal member has no exact declaration identity");
            var member = nominal.schema().members().get(index);
            require(expression.memberName().equals(Optional.of(member.name())) && expression.type().equals(member.type()),
                    "nominal member does not match its exact schema slot");
            var declaration = resolved.declaration(nominal.declaration()).orElseThrow();
            require(member.publicAccess() || declaration.span().sourceId().equals(expression.span().sourceId())
                            && declaration.span().startOffset() <= expression.span().startOffset()
                            && declaration.span().endOffset() >= expression.span().endOffset(),
                    "private nominal member escapes its declaring lexical scope");
            return;
        }
        require(expression.declarationId().isEmpty(), "structural member carries a nominal field identity");
        if (expression.memberName().filter("length"::equals).isPresent()
                && (base == PrimitiveType.STRING || base instanceof ArrayType)) {
            require(expression.type() == PrimitiveType.I32 && expression.tupleIndex().isEmpty(),
                    "String/Array length member does not return I32");
            return;
        }
        if (expression.tupleIndex().isPresent() && base instanceof TupleType tuple) {
            BigInteger index = expression.tupleIndex().orElseThrow();
            require(index.bitLength() <= 31 && index.intValue() < tuple.arity()
                            && expression.memberName().isEmpty()
                            && expression.type().equals(tuple.memberType(index.intValue())),
                    "tuple member index/result type does not match its shape");
            return;
        }
        throw invalid("typed member is not legal for its receiver type");
    }

    private void validatePredicateBinding(
            SyntaxNode.Conditional syntax,
            TypedExpression expression) {
        if (syntax.binding().isEmpty()) {
            require(expression.predicateBinding().isEmpty(),
                    "typed conditional invented a predicate binding");
            return;
        }
        SyntaxNode.PredicateBinding binding = syntax.binding().orElseThrow();
        DeclarationId id = expression.predicateBinding().orElseThrow(() -> invalid(
                "source predicate binding has no typed declaration identity"));
        ResolvedDeclaration declaration = resolved.declaration(id).orElseThrow();
        require(declaration.kind() == DeclarationKind.PREDICATE_BINDING
                        && declaration.name().equals(binding.name().name())
                        && declaration.nameSpan().equals(binding.name().span()),
                "typed predicate binding identity does not match source");
        ResolvedScope scope = resolved.scopeTree().scope(declaration.scopeId()).orElseThrow();
        require(scope.kind() == ScopeKind.CONDITIONAL_BRANCH
                        && scope.span().equals(syntax.thenBranch().span())
                        && scope.declarations().contains(id),
                "typed predicate binding scope does not match its truthy branch");
        LyraType predicateType = synthesizedTypeWithoutContext(syntax.predicate())
                .orElseThrow(() -> invalid(
                        "predicate binding predicate has no source-derived type"));
        BindingContract narrowedContract = BindingContract.immutable(
                predicateType.withoutQualifiers());
        require(typed.contract(id).filter(narrowedContract::equals).isPresent(),
                "typed predicate binding contract is not the exact immutable narrowed predicate type");
    }

    private void requireReferenceLink(
            TypedExpression expression,
            ResolvedReference reference,
            Optional<AccessKind> accessKind) {
        TypedLink expected = new TypedLink(
                Optional.of(reference.id()), reference.targetDeclaration(),
                reference.targetModule(), reference.targetExport(), accessKind);
        require(expression.link().filter(expected::equals).isPresent(),
                "typed expression link does not match its resolved reference");
    }

    private ResolvedReference findReference(SourceSpan span, String name, ReferenceKind kind) {
        return resolved.references().stream()
                .filter(value -> value.span().equals(span)
                        && value.name().equals(name)
                        && value.kind() == kind)
                .findFirst().orElseThrow(() -> invalid(
                        "typed source expression has no matching resolved reference"));
    }

    private void validateExpressionShape(TypedExpression expression) {
        Set<Metadata> allowed = switch (expression.kind()) {
            case LITERAL -> EnumSet.of(Metadata.LITERAL);
            case REFERENCE -> EnumSet.of(Metadata.LINK, Metadata.CAPTURES);
            case DECLARATION, REBINDING -> EnumSet.of(Metadata.LINK, Metadata.DECLARATION);
            case NOMINAL_DECLARATION -> EnumSet.of(Metadata.DECLARATION, Metadata.SCOPE);
            case CONSTRUCTION -> EnumSet.of(Metadata.LINK, Metadata.DECLARATION, Metadata.SIGNATURE);
            case BLOCK -> EnumSet.of(Metadata.SCOPE);
            case CONDITIONAL -> EnumSet.of(Metadata.PREDICATE_BINDING);
            case MATCH -> EnumSet.of(Metadata.MATCH);
            case COALESCE, CALLABLE_CALL, ITER, WHILE, ARRAY_LITERAL, TUPLE_LITERAL,
                    INDEX_ACCESS, NARROWING -> EnumSet.noneOf(Metadata.class);
            case LAMBDA -> EnumSet.of(Metadata.LAMBDA, Metadata.SIGNATURE, Metadata.CAPTURES);
            case DIRECT_CALL, NAMESPACE_DIRECT_CALL -> EnumSet.of(Metadata.LINK, Metadata.DECLARATION);
            case MEMBER_ACCESS -> EnumSet.of(Metadata.MEMBER_NAME, Metadata.TUPLE_INDEX, Metadata.DECLARATION);
            case NAMESPACE_MEMBER_ACCESS -> EnumSet.of(
                    Metadata.LINK, Metadata.MEMBER_NAME, Metadata.TUPLE_INDEX);
            case OPERATOR, SHORT_CIRCUIT, RANGE -> EnumSet.of(Metadata.OPERATOR);
            case CONVERSION -> EnumSet.of(Metadata.CONVERSION);
        };
        rejectForeignMetadata(expression, allowed);
        switch (expression.kind()) {
            case LITERAL -> require(expression.literal().isPresent() && expression.children().isEmpty(),
                    "typed literal metadata/children are incomplete");
            case REFERENCE -> require(expression.link().isPresent() && expression.children().isEmpty(),
                    "typed reference metadata/children are incomplete");
            case DECLARATION -> require(expression.link().isPresent()
                            && expression.declarationId().isPresent()
                            && expression.children().size() == 1,
                    "typed declaration metadata/children are incomplete");
            case REBINDING -> require(expression.link().isPresent()
                            && expression.declarationId().isPresent()
                            && expression.children().size() == 2,
                    "typed rebinding metadata/children are incomplete");
            case BLOCK -> require(expression.scopeId().isPresent(),
                    "typed block has no scope identity");
            case CONDITIONAL -> require(expression.children().size() == 2
                            || expression.children().size() == 3,
                    "typed conditional has an invalid child count");
            case COALESCE -> require(expression.children().size() == 2,
                    "typed coalesce has an invalid child count");
            case MATCH -> require(expression.match().isPresent()
                            && expression.match().orElseThrow().childCount()
                            == expression.children().size(),
                    "typed match metadata/children are incomplete");
            case ARRAY_LITERAL -> require(!expression.type().isNilable()
                            && expression.children().stream().allMatch(Objects::nonNull)
                            && expression.type().withoutQualifiers() instanceof ArrayType,
                    "typed array literal shape/type is incomplete");
            case TUPLE_LITERAL -> require(!expression.type().isNilable()
                            && expression.children().stream().allMatch(Objects::nonNull)
                            && expression.type().withoutQualifiers() instanceof TupleType
                            && !expression.children().isEmpty(),
                    "typed tuple literal shape/type is incomplete");
            case INDEX_ACCESS -> require(expression.children().size() == 2,
                    "typed index access has an invalid child count");
            case LAMBDA -> require(expression.lambdaId().isPresent()
                            && expression.signature().isPresent()
                            && expression.children().size() == 1,
                    "typed lambda metadata/children are incomplete");
            case CALLABLE_CALL -> require(!expression.children().isEmpty(),
                    "typed callable call has no target");
            case DIRECT_CALL, NAMESPACE_DIRECT_CALL -> require(expression.link().isPresent()
                            && expression.link().orElseThrow().referenceId().isPresent(),
                    "typed direct call has no resolved link");
            case MEMBER_ACCESS -> require(expression.children().size() == 1
                            && oneSelector(expression),
                    "typed member access metadata/children are incomplete");
            case NAMESPACE_MEMBER_ACCESS -> require(expression.children().isEmpty()
                            && expression.link().isPresent()
                            && oneSelector(expression),
                    "typed namespace member metadata/children are incomplete");
            case OPERATOR, SHORT_CIRCUIT -> require(expression.operator().isPresent(),
                    "typed operator has no spelling");
            case CONVERSION -> {
                TypedConversion conversion = expression.conversion().orElseThrow(() -> invalid(
                        "typed conversion has no record"));
                require(expression.children().size() == 1
                                && conversion.span().equals(expression.span())
                                && conversion.sourceType().equals(expression.children().getFirst().type())
                                && conversion.targetType().equals(expression.type()),
                        "typed conversion record/source/target disagree");
                if (conversion.kind() == ConversionKind.IMPLICIT) {
                    var decision = TypeRules.implicitConversion(
                            conversion.sourceType(), conversion.targetType());
                    require(decision.kind() == ConversionKind.IMPLICIT
                                    && decision.steps().equals(List.of(conversion.step())),
                            "typed implicit conversion does not represent one exact type step");
                } else {
                    LyraType sourceBase = conversion.sourceType().withoutQualifiers();
                    LyraType targetBase = conversion.targetType().withoutQualifiers();
                    boolean numeric = sourceBase.isNumeric() && targetBase.isNumeric()
                            && conversion.step() == ConversionStep.NUMERIC_EXPLICIT;
                    boolean text = targetBase == PrimitiveType.STRING
                            && sourceBase instanceof PrimitiveType
                            && conversion.step() == ConversionStep.TEXT_EXPLICIT;
                    require(conversion.kind() == ConversionKind.EXPLICIT
                                    && !conversion.sourceType().isNilable()
                                    && !conversion.sourceType().isMutable()
                                    && conversion.targetType().equals(targetBase)
                                    && (numeric || text)
                                    && TypeRules.canExplicitlyConvert(
                                    conversion.sourceType(), conversion.targetType()),
                            "typed explicit conversion contract is invalid");
                }
            }
            case NARROWING -> require(expression.children().size() == 1
                            && expression.children().getFirst().type().isNilable()
                            && narrowed(expression.children().getFirst().type()).equals(expression.type()),
                    "typed narrowing source/target are inconsistent");
        }
    }

    private void rejectForeignMetadata(TypedExpression expression, Set<Metadata> allowed) {
        require((expression.kind() == TypedExpressionKind.NOMINAL_DECLARATION)
                        == expression.nominalInitialization().isPresent(),
                "nominal initialization proof is missing or foreign");
        checkMetadata(expression.literal().isPresent(), Metadata.LITERAL, allowed);
        checkMetadata(expression.link().isPresent(), Metadata.LINK, allowed);
        checkMetadata(expression.conversion().isPresent(), Metadata.CONVERSION, allowed);
        checkMetadata(expression.operator().isPresent(), Metadata.OPERATOR, allowed);
        checkMetadata(expression.memberName().isPresent(), Metadata.MEMBER_NAME, allowed);
        checkMetadata(expression.tupleIndex().isPresent(), Metadata.TUPLE_INDEX, allowed);
        checkMetadata(expression.declarationId().isPresent(), Metadata.DECLARATION, allowed);
        checkMetadata(expression.lambdaId().isPresent(), Metadata.LAMBDA, allowed);
        checkMetadata(expression.scopeId().isPresent(), Metadata.SCOPE, allowed);
        checkMetadata(expression.signature().isPresent(), Metadata.SIGNATURE, allowed);
        checkMetadata(!expression.captureIds().isEmpty(), Metadata.CAPTURES, allowed);
        checkMetadata(expression.predicateBinding().isPresent(), Metadata.PREDICATE_BINDING, allowed);
        checkMetadata(expression.match().isPresent(), Metadata.MATCH, allowed);
    }

    private void checkMetadata(boolean present, Metadata field, Set<Metadata> allowed) {
        require(!present || allowed.contains(field),
                "typed expression carries metadata foreign to its kind: " + field);
    }

    private static boolean oneSelector(TypedExpression expression) {
        return expression.memberName().isPresent() ^ expression.tupleIndex().isPresent();
    }

    private static Optional<PrimitiveType> commonNonNilNumeric(List<LyraType> types) {
        if (types.isEmpty() || types.stream().anyMatch(value ->
                !value.isNumeric() || value.isNilable())) {
            return Optional.empty();
        }
        return TypeRules.commonNumericPrimitiveType(types.stream()
                .map(value -> (PrimitiveType) value.withoutQualifiers()).toList());
    }

    private static LyraType narrowed(LyraType type) {
        return type.withoutQualifiers();
    }

    private static LyraType removeMutableQualifier(LyraType type) {
        Objects.requireNonNull(type, "type");
        if (!type.hasQualifier(TypeQualifier.MUT)) {
            return type;
        }
        EnumSet<TypeQualifier> qualifiers = EnumSet.noneOf(TypeQualifier.class);
        if (type.hasQualifier(TypeQualifier.NIL)) {
            qualifiers.add(TypeQualifier.NIL);
        }
        return type.withoutQualifiers().withQualifiers(qualifiers);
    }

    private static boolean truthTestable(LyraType type) {
        LyraType base = type.withoutQualifiers();
        return type.isNilable() || base instanceof PrimitiveType
                || base instanceof ArrayType || base instanceof TupleType
                || base instanceof FunctionType;
    }

    private static boolean sameIdentities(
            List<TypedExpression> expected,
            List<TypedExpression> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        IdentityHashMap<TypedExpression, Integer> counts = new IdentityHashMap<>();
        for (TypedExpression expression : expected) {
            counts.merge(expression, 1, Integer::sum);
        }
        for (TypedExpression expression : actual) {
            Integer count = counts.get(expression);
            if (count == null) {
                return false;
            }
            if (count == 1) {
                counts.remove(expression);
            } else {
                counts.put(expression, count - 1);
            }
        }
        return counts.isEmpty();
    }

    private static Map<TypedConversion, Integer> counts(List<TypedConversion> values) {
        Map<TypedConversion, Integer> counts = new HashMap<>();
        for (TypedConversion value : values) {
            counts.merge(value, 1, Integer::sum);
        }
        return counts;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw invalid(message);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private enum Metadata {
        LITERAL,
        LINK,
        CONVERSION,
        OPERATOR,
        MEMBER_NAME,
        TUPLE_INDEX,
        DECLARATION,
        LAMBDA,
        SCOPE,
        SIGNATURE,
        CAPTURES,
        PREDICATE_BINDING,
        MATCH
    }
}
