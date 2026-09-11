package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.lex.TokenKind;
import io.mindspice.lyra.compiler.semantic.AccessKind;
import io.mindspice.lyra.compiler.semantic.DeclarationKind;
import io.mindspice.lyra.compiler.semantic.MutationKind;
import io.mindspice.lyra.compiler.semantic.ResolvedDeclaration;
import io.mindspice.lyra.compiler.semantic.ResolvedExport;
import io.mindspice.lyra.compiler.semantic.ResolvedLambda;
import io.mindspice.lyra.compiler.semantic.ResolvedModule;
import io.mindspice.lyra.compiler.semantic.TypedConversion;
import io.mindspice.lyra.compiler.semantic.TypedExpression;
import io.mindspice.lyra.compiler.semantic.TypedExpressionKind;
import io.mindspice.lyra.compiler.semantic.TypedLink;
import io.mindspice.lyra.compiler.semantic.TypedLiteralValue;
import io.mindspice.lyra.compiler.semantic.TypedMatch;
import io.mindspice.lyra.compiler.semantic.TypedModule;
import io.mindspice.lyra.compiler.semantic.TypedReference;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.flow.SemanticFlowEvent;
import io.mindspice.lyra.compiler.semantic.flow.ProjectionPath;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.ConversionKind;
import io.mindspice.lyra.compiler.types.LyraType;
import io.mindspice.lyra.compiler.types.PrimitiveType;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The sole semantic-to-IR lowering path.  It consumes the already sealed typed
 * graph, its canonical flow facts, and its frozen initialization plan.  It
 * never invokes a semantic analyzer or derives ownership/call flow from
 * source syntax.
 */
public final class TypedIrBuilder {
    private final TypedSemanticGraph typedGraph;
    private final Optional<io.mindspice.lyra.compiler.semantic.TypedSubmissionResult> submission;
    private final Optional<IrSessionExecution> execution;
    private final IdentityHashMap<TypedExpression, IrNode> loweredExpressions = new IdentityHashMap<>();
    private final Map<FlowSiteId, IrExpressionSite> expressionSites = new LinkedHashMap<>();
    private final List<IrEvaluationOrder> evaluationOrders = new ArrayList<>();
    private final IdentityHashMap<TypedExpression, Boolean> activeExpressions = new IdentityHashMap<>();

    public TypedIrBuilder(TypedSemanticGraph typedGraph) {
        this(typedGraph, false);
    }

    private TypedIrBuilder(TypedSemanticGraph typedGraph, boolean submission) {
        this(typedGraph, submission, Optional.empty());
    }

    private TypedIrBuilder(TypedSemanticGraph typedGraph, boolean submission, Optional<IrSessionExecution> execution) {
        this.execution = execution;
        this.typedGraph = Objects.requireNonNull(typedGraph, "typedGraph");
        this.submission = submission
                ? Optional.of(io.mindspice.lyra.compiler.semantic.TypedSubmissionResult.from(typedGraph))
                : Optional.empty();
    }

    public static PhaseResult<TypedIr> lowerSubmission(TypedSemanticGraph graph,
            io.mindspice.lyra.compiler.session.SessionExecutionPlan plan,
            io.mindspice.lyra.compiler.session.SessionModuleEnvironment environment) {
        return new TypedIrBuilder(graph, true, Optional.of(IrSessionExecution.from(graph, plan, environment))).run();
    }

    public static PhaseResult<TypedIr> lowerSubmission(TypedSemanticGraph graph) {
        return new TypedIrBuilder(graph, true).run();
    }

    public static PhaseResult<TypedIr> lower(TypedSemanticGraph typedGraph) {
        return new TypedIrBuilder(typedGraph).run();
    }

    public static PhaseResult<TypedIr> build(TypedSemanticGraph typedGraph) {
        return lower(typedGraph);
    }

    public static PhaseResult<TypedIr> toIr(TypedSemanticGraph typedGraph) {
        return lower(typedGraph);
    }

    public PhaseResult<TypedIr> lower() {
        return run();
    }

    /**
     * Compatibility hook used by the structural validator for legacy
     * negative fixtures.  It performs only syntax-tree lowering and never
     * invokes semantic flow analysis.
     */
    static IrNode lowerForValidation(TypedSemanticGraph typedGraph, TypedExpression expression) {
        return new TypedIrBuilder(typedGraph).lowerExpression(expression);
    }

    private PhaseResult<TypedIr> run() {
        // A builder may be reused for a deterministic retry, but no mutable
        // construction state is allowed to leak into a published artifact.
        loweredExpressions.clear();
        expressionSites.clear();
        evaluationOrders.clear();
        activeExpressions.clear();
        try {
            // Merely touching these artifacts here is intentional: a builder
            // must consume the producer-owned facts/plan, not recreate them.
            typedGraph.semanticFlowFacts();
            typedGraph.initializationPlan();
            typedGraph.failureSites();
            if (typedGraph.initializationPlan().hasCycles()) {
                SourceSpan cycleSpan = typedGraph.initializationPlan().cycles().getFirst().primarySpan();
                return PhaseResult.failure(Diagnostic.error(
                        CompilerDiagnosticCodes.MODULE_EAGER_INITIALIZATION_CYCLE,
                        cycleSpan, "canonical initialization plan contains an eager cycle"));
            }

            List<IrModule> modules = new ArrayList<>();
            for (TypedModule module : typedGraph.modules()) {
                if (execution.filter(value -> !value.emits(module.moduleId())).isPresent()) continue;
                List<IrNode> forms = new ArrayList<>();
                for (TypedExpression form : module.forms()) {
                    forms.add(lowerExpression(form));
                }
                IrNode.Sequence body = new IrNode.Sequence(
                        module.span(), PrimitiveType.UNIT, forms, Optional.empty());
                evaluationOrders.add(new IrEvaluationOrder(
                        Optional.empty(), module.moduleId(), module.span(),
                        IrEvaluationOrder.Kind.MODULE_SEQUENCE,
                        edgesFor(forms, IrEvaluationOrder.EdgeKind.STRICT)));
                modules.add(new IrModule(
                        module.moduleId(), module.rootScope(), module.span(), body,
                        moduleState(module), submission
                                .filter(value -> value.moduleId().equals(module.moduleId()))
                                .map(IrSubmissionResult::from)));
            }

            IrProgramMetadata metadata = IrProgramMetadata.from(
                    typedGraph, modules, List.copyOf(expressionSites.values()),
                    List.copyOf(evaluationOrders), execution);
            TypedIr candidate = TypedIr.candidate(typedGraph, modules, metadata);
            List<Diagnostic> diagnostics = IrValidator.validateCandidate(candidate);
            if (!diagnostics.isEmpty()) {
                return PhaseResult.failure(diagnostics);
            }
            return PhaseResult.success(TypedIr.publish(candidate));
        } catch (LoweringFailure failure) {
            return PhaseResult.failure(Diagnostic.error(
                    CompilerDiagnosticCodes.IR_INVALID_GRAPH, failure.span,
                    failure.getMessage()));
        } catch (RuntimeException failure) {
            SourceSpan span = typedGraph.modules().isEmpty()
                    ? typedGraph.resolvedGraph().moduleGraph()
                    .module(typedGraph.resolvedGraph().moduleGraph().rootModule())
                    .orElseThrow().program().span()
                    : typedGraph.modules().getFirst().span();
            return PhaseResult.failure(Diagnostic.error(
                    CompilerDiagnosticCodes.IR_INVALID_GRAPH, span,
                    "cannot seal typed IR: " + (failure.getMessage() == null
                            ? failure.getClass().getSimpleName() : failure.getMessage())));
        }
    }

    private IrNode lowerExpression(TypedExpression expression) {
        Objects.requireNonNull(expression, "expression");
        IrNode existing = loweredExpressions.get(expression);
        if (existing != null) {
            return existing;
        }
        if (activeExpressions.put(expression, Boolean.TRUE) != null) {
            throw new LoweringFailure(expression.span(), "typed expression graph is cyclic");
        }
        try {
            FlowSiteId site = typedGraph.flowSiteId(expression);
            IrNode lowered = switch (expression.kind()) {
                case LITERAL -> lowerLiteral(expression, site);
                case REFERENCE -> lowerReference(expression, site);
                case DECLARATION -> lowerDeclaration(expression, site);
                case NOMINAL_DECLARATION -> {
                    var proof = expression.nominalInitialization().orElseThrow();
                    proof.requireMatches(expression.declarationId().orElseThrow(), expression.children());
                    var nominal = proof.nominal();
                    yield new IrNode.NominalDeclaration(expression.span(), expression.type(), nominal.declaration(),
                            nominal.self(), nominal.schema(), nominal.members(), nominal.constructor(), lowerChildren(expression),
                            nominal.members(), Optional.of(site));
                }
                case CONSTRUCTION -> new IrNode.Construction(expression.span(),
                        (io.mindspice.lyra.compiler.types.NominalType) expression.type(), expression.declarationId().orElseThrow(),
                        expression.link().flatMap(TypedLink::referenceId), lowerChildren(expression), Optional.of(site));
                case REBINDING -> lowerRebinding(expression, site);
                case BLOCK -> lowerBlock(expression, site);
                case ARRAY_LITERAL -> lowerArrayLiteral(expression, site);
                case TUPLE_LITERAL -> lowerTupleLiteral(expression, site);
                case INDEX_ACCESS -> lowerIndexAccess(expression, site);
                case CONDITIONAL -> lowerConditional(expression, site);
                case COALESCE -> lowerCoalesce(expression, site);
                case MATCH -> lowerMatch(expression, site);
                case RANGE -> new IrNode.RuntimeCheck(expression.span(), expression.type(), IrCheckKind.ARITHMETIC,
                        "LYR-ARITH", new IrNode.Range(expression.span(), expression.type(), child(expression, 0),
                        child(expression, 1), child(expression, 2),
                        expression.operator().filter("..."::equals).isPresent(), Optional.empty()), Optional.of(site), Optional.of(site));
                case ITER, WHILE -> new IrNode.Loop(expression.span(), expression.type(),
                        expression.kind() == TypedExpressionKind.WHILE,
                        child(expression, 0), child(expression, 1), findCallId(site), Optional.of(site));
                case LAMBDA -> lowerLambda(expression, site);
                case CALLABLE_CALL -> lowerCallableCall(expression, site);
                case DIRECT_CALL, NAMESPACE_DIRECT_CALL -> lowerDirectCall(expression, site);
                case MEMBER_ACCESS, NAMESPACE_MEMBER_ACCESS -> lowerAccess(expression, site);
                case OPERATOR -> lowerOperator(expression, false, site);
                case SHORT_CIRCUIT -> lowerOperator(expression, true, site);
                case CONVERSION -> lowerConversion(expression, site);
                case NARROWING -> lowerNarrowing(expression, site);
            };
            if (lowered.siteId().filter(site::equals).isEmpty()) {
                throw new LoweringFailure(expression.span(),
                        "lowered source expression lost its flow-site identity");
            }
            loweredExpressions.put(expression, lowered);
            expressionSites.put(site, new IrExpressionSite(
                    site, ModuleId.fromSourceId(expression.span().sourceId()),
                    expression.span(), expression.type(), expression.kind(), lowered));
            evaluationOrders.add(evaluationOrder(expression, site));
            return lowered;
        } finally {
            activeExpressions.remove(expression);
        }
    }

    private IrNode lowerLiteral(TypedExpression expression, FlowSiteId site) {
        TypedLiteralValue value = expression.literalValue();
        IrConstantValue constant;
        if (value instanceof TypedLiteralValue.BooleanValue booleanValue) {
            constant = new IrConstantValue.BooleanValue(booleanValue.value());
        } else if (value instanceof TypedLiteralValue.NilValue) {
            constant = IrConstantValue.NilValue.INSTANCE;
        } else if (value instanceof TypedLiteralValue.IntegerValue integerValue) {
            constant = new IrConstantValue.IntegerValue(integerValue.exactValue());
        } else if (value instanceof TypedLiteralValue.DecimalValue decimalValue) {
            constant = new IrConstantValue.DecimalValue(decimalValue.exactValue());
        } else if (value instanceof TypedLiteralValue.StringValue stringValue) {
            constant = new IrConstantValue.StringValue(stringValue.value());
        } else if (value instanceof TypedLiteralValue.CharacterValue characterValue) {
            constant = new IrConstantValue.CharacterValue(characterValue.value());
        } else if (value instanceof TypedLiteralValue.UnitValue unitValue) {
            constant = new IrConstantValue.UnitValue(unitValue.spelling());
        } else {
            throw new LoweringFailure(expression.span(), "unknown typed literal value");
        }
        return new IrNode.Constant(expression.span(), expression.type(), constant, Optional.of(site));
    }

    private IrNode lowerReference(TypedExpression expression, FlowSiteId site) {
        TypedLink link = expression.link().orElseThrow(() ->
                new LoweringFailure(expression.span(), "reference has no typed link"));
        ReferenceId referenceId = link.referenceId().orElseThrow(() ->
                new LoweringFailure(expression.span(), "reference has no resolved reference identity"));
        DeclarationId declarationId = link.declarationId().orElseThrow(() ->
                new LoweringFailure(expression.span(), "reference has no resolved declaration identity"));
        TypedReference reference = typedGraph.reference(referenceId).orElseThrow(() ->
                new LoweringFailure(expression.span(), "reference identity is absent"));
        Optional<io.mindspice.lyra.compiler.identity.CaptureId> capture = reference.capture();
        if (capture.isPresent()) {
            return new IrNode.CaptureReference(
                    expression.span(), expression.type(), Optional.of(referenceId), capture,
                    Optional.of(declarationId), reference.kind(), Optional.of(site));
        }
        return new IrNode.Reference(
                expression.span(), expression.type(), Optional.of(referenceId),
                Optional.of(declarationId), Optional.empty(), reference.kind(), Optional.of(site));
    }

    private IrNode lowerDeclaration(TypedExpression expression, FlowSiteId site) {
        DeclarationId declarationId = expression.declarationId().orElseThrow(() ->
                new LoweringFailure(expression.span(), "declaration operation has no identity"));
        io.mindspice.lyra.compiler.semantic.TypedDeclaration declaration = typedGraph
                .declaration(declarationId).orElseThrow(() ->
                        new LoweringFailure(expression.span(), "declaration identity is absent"));
        return new IrNode.Declaration(
                expression.span(), expression.type(), Optional.of(declarationId),
                declaration.kind(), declaration.contract(), child(expression, 0), Optional.of(site));
    }

    private IrNode lowerRebinding(TypedExpression expression, FlowSiteId site) {
        DeclarationId declarationId = expression.declarationId().orElseThrow(() ->
                new LoweringFailure(expression.span(), "rebinding has no target declaration"));
        IrNode target = child(expression, 0);
        Optional<ReferenceId> rootReference = rootReference(target);
        Optional<MutationKind> mutationKind = Optional.empty();
        ProjectionPath route = targetRoute(expression.children().getFirst());
        for (io.mindspice.lyra.compiler.semantic.TypedMutation mutation : typedGraph.mutations()) {
            if (mutation.moduleId().equals(ModuleId.fromSourceId(expression.span().sourceId()))
                    && mutation.span().equals(expression.children().getFirst().span())
                    && mutation.rootDeclaration().equals(declarationId)
                    && mutation.rootReference().equals(rootReference)) {
                mutationKind = Optional.of(mutation.kind());
                route = mutation.kind() == MutationKind.REBINDING ? ProjectionPath.root() : route;
                break;
            }
        }
        return new IrNode.Rebinding(
                expression.span(), expression.type(), Optional.of(declarationId), rootReference,
                mutationKind, route, target, child(expression, 1), Optional.of(site));
    }

    private IrNode lowerBlock(TypedExpression expression, FlowSiteId site) {
        return new IrNode.Block(expression.span(), expression.type(), expression.scopeId(),
                lowerChildren(expression), Optional.of(site));
    }

    private IrNode lowerArrayLiteral(TypedExpression expression, FlowSiteId site) {
        return new IrNode.ArrayLiteral(expression.span(), expression.type(),
                lowerChildren(expression), Optional.of(site), Optional.of(site));
    }

    private IrNode lowerTupleLiteral(TypedExpression expression, FlowSiteId site) {
        return new IrNode.TupleLiteral(expression.span(), expression.type(),
                lowerChildren(expression), Optional.of(site));
    }

    private IrNode lowerIndexAccess(TypedExpression expression, FlowSiteId site) {
        if (expression.children().size() != 2) {
            throw new LoweringFailure(expression.span(), "index access has an invalid child count");
        }
        IrNode access = new IrNode.IndexAccess(
                expression.span(), expression.type(), child(expression, 0), child(expression, 1),
                indexRoute(expression.children().get(1)), Optional.empty());
        return new IrNode.RuntimeCheck(
                expression.span(), expression.type(), IrCheckKind.BOUNDS,
                "LYR-BOUNDS", access, Optional.of(site), Optional.of(site));
    }

    private IrNode lowerConditional(TypedExpression expression, FlowSiteId site) {
        List<TypedExpression> children = expression.children();
        if (children.size() != 2 && children.size() != 3) {
            throw new LoweringFailure(expression.span(), "conditional has an invalid child count");
        }
        IrNode predicate = lowerExpression(children.getFirst());
        IrNode thenBranch = lowerExpression(children.get(1));
        Optional<IrNode> elseBranch = children.size() == 3
                ? Optional.of(lowerExpression(children.get(2))) : Optional.empty();
        return new IrNode.Branch(expression.span(), expression.type(), predicate, thenBranch,
                elseBranch, expression.predicateBinding(), Optional.of(site));
    }

    private IrNode lowerMatch(TypedExpression expression, FlowSiteId site) {
        TypedMatch match = expression.match().orElseThrow(() ->
                new LoweringFailure(expression.span(), "match has no typed arm metadata"));
        Optional<IrNode> subject = match.subjectChild().isPresent()
                ? Optional.of(child(expression, match.subjectChild().getAsInt()))
                : Optional.empty();
        List<IrNode.MatchArm> arms = new ArrayList<>();
        for (TypedMatch.Arm arm : match.arms()) {
            arms.add(new IrNode.MatchArm(
                    arm.span(), arm.wildcard(),
                    arm.patternChild().isPresent()
                            ? Optional.of(child(expression, arm.patternChild().getAsInt()))
                            : Optional.empty(),
                    arm.guardChild().isPresent()
                            ? Optional.of(child(expression, arm.guardChild().getAsInt()))
                            : Optional.empty(),
                    child(expression, arm.resultChild()), arm.comparisonType()));
        }
        IrNode.MatchMode mode = match.mode() == TypedMatch.MatchMode.TRADITIONAL
                ? IrNode.MatchMode.TRADITIONAL : IrNode.MatchMode.CONDITIONAL;
        return new IrNode.Match(expression.span(), expression.type(), mode,
                subject, arms, Optional.of(site));
    }

    private IrNode lowerCoalesce(TypedExpression expression, FlowSiteId site) {
        if (expression.children().size() != 2) {
            throw new LoweringFailure(expression.span(), "coalesce has an invalid child count");
        }
        IrNode original = lowerExpression(expression.children().getFirst());
        IrNode.Narrowing narrowed = new IrNode.Narrowing(
                expression.children().getFirst().span(), expression.type(),
                expression.children().getFirst().type(), original, Optional.empty());
        // Coalescing observes the value-side nil state and selects a branch;
        // it is not an invocation-fatal conversion check.  The explicit
        // Narrowing node records the non-nil branch type without inventing a
        // LYR-CONVERT failure site that the semantic graph never produced.
        return new IrNode.Coalesce(expression.span(), expression.type(),
                expression.children().getFirst().type(), narrowed,
                lowerExpression(expression.children().get(1)), Optional.of(site));
    }

    private IrNode lowerLambda(TypedExpression expression, FlowSiteId site) {
        var lambdaId = expression.lambdaId().orElseThrow(() ->
                new LoweringFailure(expression.span(), "lambda has no identity"));
        ResolvedLambda resolved = typedGraph.resolvedGraph().lambda(lambdaId).orElseThrow(() ->
                new LoweringFailure(expression.span(), "lambda identity is absent"));
        return new IrNode.Lambda(expression.span(), expression.type(), Optional.of(lambdaId),
                expression.signature(), expression.captureIds(), Optional.of(resolved.scopeId()),
                resolved.ownerDeclaration(), child(expression, 0), Optional.of(site));
    }

    private IrNode lowerCallableCall(TypedExpression expression, FlowSiteId site) {
        if (expression.children().isEmpty()) {
            throw new LoweringFailure(expression.span(), "callable call has no target");
        }
        IrNode target = lowerExpression(expression.children().getFirst());
        List<IrNode> arguments = new ArrayList<>();
        for (TypedExpression child : expression.children().subList(1, expression.children().size())) {
            arguments.add(lowerExpression(child));
        }
        return new IrNode.CallableCall(expression.span(), expression.type(), target, arguments,
                findCallId(site), Optional.of(site));
    }

    private IrNode lowerDirectCall(TypedExpression expression, FlowSiteId site) {
        TypedLink link = expression.link().orElseThrow(() ->
                new LoweringFailure(expression.span(), "direct call has no typed link"));
        return new IrNode.DirectCall(
                expression.span(), expression.type(), link.referenceId(), link.declarationId(),
                link.moduleId(), link.exportId(), link.accessKind(), Optional.empty(),
                lowerChildren(expression), findCallId(site), Optional.of(site));
    }

    private IrNode lowerAccess(TypedExpression expression, FlowSiteId site) {
        TypedLink link = expression.link().orElse(new TypedLink(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        boolean member = expression.kind() == TypedExpressionKind.MEMBER_ACCESS;
        return new IrNode.Access(
                expression.span(), expression.type(),
                member ? AccessKind.MEMBER_VALUE : AccessKind.NAMESPACE_VALUE,
                member ? Optional.of(child(expression, 0)) : Optional.empty(),
                link.referenceId(), expression.declarationId().or(link::declarationId), link.moduleId(), link.exportId(),
                expression.memberName(), expression.tupleIndex(), Optional.of(site));
    }

    private IrNode lowerOperator(TypedExpression expression, boolean shortCircuit, FlowSiteId site) {
        TokenKind operator = operator(expression);
        List<IrNode> operands = lowerChildren(expression);
        IrNode node = shortCircuit
                ? new IrNode.ShortCircuit(expression.span(), expression.type(), operator, operands,
                Optional.empty())
                : new IrNode.Operator(expression.span(), expression.type(), operator, operands,
                Optional.empty());
        if (!shortCircuit && isCheckedNumericOperator(operator, expression.type())) {
            return new IrNode.RuntimeCheck(
                    expression.span(), expression.type(),
                    operator == TokenKind.SLASH ? IrCheckKind.DIVISION : IrCheckKind.ARITHMETIC,
                    "LYR-ARITH", node, Optional.of(site), Optional.of(site));
        }
        if (shortCircuit) {
            return new IrNode.ShortCircuit(expression.span(), expression.type(), operator,
                    operands, Optional.of(site));
        }
        return new IrNode.Operator(expression.span(), expression.type(), operator, operands,
                Optional.of(site));
    }

    private IrNode lowerConversion(TypedExpression expression, FlowSiteId site) {
        TypedConversion conversion = expression.conversion().orElseThrow(() ->
                new LoweringFailure(expression.span(), "conversion node has no conversion record"));
        boolean numericRuntimeCheck = conversion.kind() == ConversionKind.EXPLICIT
                && conversion.step() == io.mindspice.lyra.compiler.types.ConversionStep.NUMERIC_EXPLICIT;
        IrNode node = new IrNode.Conversion(
                expression.span(), expression.type(), conversion.sourceType(), conversion.kind(),
                conversion.step(), child(expression, 0),
                numericRuntimeCheck ? Optional.empty() : Optional.of(site));
        if (numericRuntimeCheck) {
            return new IrNode.RuntimeCheck(
                    expression.span(), expression.type(), IrCheckKind.EXPLICIT_CONVERSION,
                    "LYR-CONVERT", node, Optional.of(site), Optional.of(site));
        }
        return node;
    }

    private IrNode lowerNarrowing(TypedExpression expression, FlowSiteId site) {
        IrNode operand = child(expression, 0);
        return new IrNode.Narrowing(expression.span(), expression.type(), operand.type(), operand,
                Optional.of(site));
    }

    private List<IrNode> lowerChildren(TypedExpression expression) {
        List<IrNode> result = new ArrayList<>();
        for (TypedExpression child : expression.children()) {
            result.add(lowerExpression(child));
        }
        return List.copyOf(result);
    }

    private IrNode child(TypedExpression expression, int index) {
        if (index < 0 || index >= expression.children().size()) {
            throw new LoweringFailure(expression.span(), "typed expression child is missing");
        }
        return lowerExpression(expression.children().get(index));
    }

    private ProjectionPath indexRoute(TypedExpression index) {
        BigInteger value = constantInteger(index);
        return value != null && value.signum() >= 0
                && value.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0
                ? ProjectionPath.arrayElement(value.intValueExact())
                : ProjectionPath.unknownArrayElement();
    }

    private ProjectionPath targetRoute(TypedExpression target) {
        if (target.kind() == TypedExpressionKind.MEMBER_ACCESS && target.declarationId().isPresent()) {
            var type = (io.mindspice.lyra.compiler.types.NominalType) target.children().getFirst().type().withoutQualifiers();
            var nominal = typedGraph.resolvedGraph().nominals().stream().filter(value -> value.schema().type().equals(type))
                    .findFirst().orElseThrow();
            int index = nominal.members().indexOf(target.declarationId().orElseThrow());
            return targetRoute(target.children().getFirst()).append(
                    new io.mindspice.lyra.compiler.semantic.flow.ProjectionStep.NominalMember(type, index, target.type()));
        }
        if (target.kind() == TypedExpressionKind.INDEX_ACCESS && target.children().size() == 2) {
            return targetRoute(target.children().getFirst()).compose(
                    indexRoute(target.children().get(1)));
        }
        if (target.kind() == TypedExpressionKind.MEMBER_ACCESS
                && target.tupleIndex().isPresent() && !target.children().isEmpty()) {
            return targetRoute(target.children().getFirst()).compose(
                    ProjectionPath.tupleMember(target.tupleIndex().orElseThrow().intValueExact()));
        }
        return ProjectionPath.root();
    }

    private Optional<ReferenceId> rootReference(IrNode node) {
        if (node instanceof IrNode.Reference reference) {
            return reference.referenceId();
        }
        if (node instanceof IrNode.CaptureReference reference) {
            return reference.referenceId();
        }
        if (node instanceof IrNode.RuntimeCheck check) {
            return rootReference(check.operand());
        }
        if (node instanceof IrNode.IndexAccess index) {
            return rootReference(index.receiver());
        }
        if (node instanceof IrNode.Access access && access.receiver().isPresent()) {
            return rootReference(access.receiver().orElseThrow());
        }
        return Optional.empty();
    }

    private BigInteger constantInteger(TypedExpression expression) {
        if (expression.literal().orElse(null) instanceof TypedLiteralValue.IntegerValue value) {
            return value.exactValue().integerValue();
        }
        if (expression.kind() == TypedExpressionKind.OPERATOR
                && expression.operator().filter("-"::equals).isPresent()
                && expression.children().size() == 1) {
            BigInteger value = constantInteger(expression.children().getFirst());
            return value == null ? null : value.negate();
        }
        return null;
    }

    private Optional<io.mindspice.lyra.compiler.semantic.flow.SummaryCallId> findCallId(FlowSiteId site) {
        Optional<io.mindspice.lyra.compiler.semantic.flow.SummaryCallId> summaryCall =
                typedGraph.semanticFlowFacts().callableSummaries().orderedSummaries().stream()
                        .flatMap(summary -> summary.callReferences().stream())
                        .filter(call -> call.siteId().filter(site::equals).isPresent())
                        .map(io.mindspice.lyra.compiler.semantic.flow.CallableCallReference::callId)
                        .findFirst();
        if (summaryCall.isPresent()) {
            return summaryCall;
        }
        // Calls at eager module scope do not belong to a lambda summary.  Their
        // producer-issued expression site remains the complete call identity.
        return typedGraph.semanticFlowFacts().events().stream()
                .filter(event -> event.siteId().filter(site::equals).isPresent())
                .map(SemanticFlowEvent::callId)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private IrEvaluationOrder evaluationOrder(TypedExpression expression, FlowSiteId site) {
        IrEvaluationOrder.Kind kind = switch (expression.kind()) {
            case SHORT_CIRCUIT -> IrEvaluationOrder.Kind.SHORT_CIRCUIT;
            case CONDITIONAL -> IrEvaluationOrder.Kind.BRANCH;
            case COALESCE -> IrEvaluationOrder.Kind.COALESCE;
            case MATCH -> IrEvaluationOrder.Kind.MATCH;
            case NOMINAL_DECLARATION -> IrEvaluationOrder.Kind.INSTANCE_INITIALIZATION;
            default -> IrEvaluationOrder.Kind.STRICT;
        };
        List<IrEvaluationOrder.Edge> edges = new ArrayList<>();
        for (int index = 0; index < expression.children().size(); index++) {
            FlowSiteId childSite = typedGraph.flowSiteId(expression.children().get(index));
            IrEvaluationOrder.EdgeKind edgeKind = switch (kind) {
                case SHORT_CIRCUIT -> index == 0
                        ? IrEvaluationOrder.EdgeKind.STRICT
                        : IrEvaluationOrder.EdgeKind.SHORT_CIRCUIT_OPERAND;
                case BRANCH -> index == 0 ? IrEvaluationOrder.EdgeKind.STRICT
                        : index == 1 ? IrEvaluationOrder.EdgeKind.THEN_BRANCH
                        : IrEvaluationOrder.EdgeKind.ELSE_BRANCH;
                case COALESCE -> index == 0 ? IrEvaluationOrder.EdgeKind.NON_NIL_VALUE
                        : IrEvaluationOrder.EdgeKind.FALLBACK;
                case MATCH -> matchEdgeKind(expression.match().orElseThrow(), index);
                case INSTANCE_INITIALIZATION -> IrEvaluationOrder.EdgeKind.INSTANCE_INITIALIZER;
                default -> IrEvaluationOrder.EdgeKind.STRICT;
            };
            edges.add(new IrEvaluationOrder.Edge(index, childSite, edgeKind));
        }
        return new IrEvaluationOrder(Optional.of(site),
                ModuleId.fromSourceId(expression.span().sourceId()), expression.span(), kind, edges);
    }

    private IrEvaluationOrder.EdgeKind matchEdgeKind(TypedMatch match, int childIndex) {
        if (match.subjectChild().isPresent()
                && match.subjectChild().getAsInt() == childIndex) {
            return IrEvaluationOrder.EdgeKind.MATCH_SUBJECT;
        }
        for (TypedMatch.Arm arm : match.arms()) {
            if (arm.patternChild().isPresent()
                    && arm.patternChild().getAsInt() == childIndex) {
                return IrEvaluationOrder.EdgeKind.MATCH_PATTERN;
            }
            if (arm.guardChild().isPresent()
                    && arm.guardChild().getAsInt() == childIndex) {
                return IrEvaluationOrder.EdgeKind.MATCH_GUARD;
            }
            if (arm.resultChild() == childIndex) {
                return IrEvaluationOrder.EdgeKind.MATCH_RESULT;
            }
        }
        throw new LoweringFailure(typedGraph.modules().getFirst().span(),
                "typed match has an unclassified child");
    }

    private List<IrEvaluationOrder.Edge> edgesFor(
            List<IrNode> nodes, IrEvaluationOrder.EdgeKind kind) {
        List<IrEvaluationOrder.Edge> edges = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            IrNode node = nodes.get(index);
            FlowSiteId site = node.siteId().orElse(null);
            if (site == null) {
                throw new LoweringFailure(node.span(), "module form has no flow-site identity");
            }
            edges.add(new IrEvaluationOrder.Edge(index, site, kind));
        }
        return List.copyOf(edges);
    }

    private IrModuleState moduleState(TypedModule module) {
        ResolvedModule resolved = typedGraph.resolvedGraph().module(module.moduleId()).orElseThrow();
        List<DeclarationId> functionSlots = resolved.declarations().stream()
                .map(id -> typedGraph.declaration(id).orElseThrow())
                .filter(value -> value.kind() != DeclarationKind.EXTERNAL
                        && value.kind() != DeclarationKind.PARAMETER
                        && value.kind() != DeclarationKind.PREDICATE_BINDING)
                .filter(value -> value.contract().filter(contract -> contract.valueType().withoutQualifiers()
                        instanceof io.mindspice.lyra.compiler.types.FunctionType).isPresent())
                .map(io.mindspice.lyra.compiler.semantic.TypedDeclaration::id)
                .toList();
        List<DeclarationId> eager = resolved.declarations().stream()
                .map(id -> typedGraph.resolvedGraph().declaration(id).orElseThrow())
                .filter(value -> value.kind() == DeclarationKind.LET
                        && value.initializerLambda().isEmpty())
                .map(ResolvedDeclaration::id)
                .toList();
        List<DeclarationId> imports = resolved.imports().stream()
                .map(io.mindspice.lyra.compiler.semantic.ResolvedImportBinding::declarationId)
                .toList();
        List<ResolvedExport> moduleExports = typedGraph.resolvedGraph().exports().stream()
                .filter(value -> value.moduleId().equals(module.moduleId())).toList();
        List<io.mindspice.lyra.compiler.identity.ExportId> exports = moduleExports.stream()
                .map(ResolvedExport::exportId).flatMap(Optional::stream).sorted().toList();
        List<DeclarationId> exportedDeclarations = moduleExports.stream()
                .map(ResolvedExport::declarationId).toList();
        return new IrModuleState(module.moduleId(), module.rootScope(), resolved.declarations(),
                resolved.references(), resolved.lambdas(), imports, functionSlots, eager, exports,
                exportedDeclarations);
    }

    private TokenKind operator(TypedExpression expression) {
        String spelling = expression.operator().orElseThrow(() ->
                new LoweringFailure(expression.span(), "operator has no spelling"));
        return switch (spelling) {
            case "+" -> TokenKind.PLUS;
            case "-" -> TokenKind.MINUS;
            case "*" -> TokenKind.ASTERISK;
            case "/" -> TokenKind.SLASH;
            case "%" -> TokenKind.PERCENT;
            case "^" -> TokenKind.CARET;
            case "<" -> TokenKind.LESS;
            case "<=" -> TokenKind.LESS_EQUAL;
            case ">" -> TokenKind.GREATER;
            case ">=" -> TokenKind.GREATER_EQUAL;
            case "==" -> TokenKind.EQUAL_EQUAL;
            case "!=" -> TokenKind.NOT_EQUAL;
            case "eq?" -> TokenKind.IDENTITY_EQUAL;
            case "!eq?" -> TokenKind.IDENTITY_NOT_EQUAL;
            case "and" -> TokenKind.AND;
            case "or" -> TokenKind.OR;
            case "xor" -> TokenKind.XOR;
            case "not" -> TokenKind.NOT;
            case "++" -> TokenKind.INCREMENT;
            case "--" -> TokenKind.DECREMENT;
            default -> throw new LoweringFailure(expression.span(), "unknown operator spelling: " + spelling);
        };
    }

    private boolean isCheckedNumericOperator(TokenKind operator, LyraType resultType) {
        return resultType.isNumeric() && switch (operator) {
            case PLUS, MINUS, ASTERISK, SLASH, PERCENT, CARET, INCREMENT, DECREMENT -> true;
            default -> false;
        };
    }

    private static final class LoweringFailure extends RuntimeException {
        private final SourceSpan span;

        private LoweringFailure(SourceSpan span, String message) {
            super(message);
            this.span = span;
        }
    }
}
