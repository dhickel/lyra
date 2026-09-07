package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.semantic.CaptureMode;
import io.mindspice.lyra.compiler.semantic.ResolvedCapture;
import io.mindspice.lyra.compiler.semantic.ResolvedDeclaration;
import io.mindspice.lyra.compiler.semantic.ResolvedLambda;
import io.mindspice.lyra.compiler.semantic.TypedDeclaration;
import io.mindspice.lyra.compiler.semantic.TypedExpression;
import io.mindspice.lyra.compiler.semantic.TypedExpressionKind;
import io.mindspice.lyra.compiler.semantic.TypedLambda;
import io.mindspice.lyra.compiler.semantic.TypedLink;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.TypedSemanticInput;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.BindingContract;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.LyraSignature;
import io.mindspice.lyra.compiler.types.LyraType;
import io.mindspice.lyra.compiler.types.PrimitiveType;
import io.mindspice.lyra.compiler.types.TupleType;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builds one complete symbolic summary for each typed lambda, then delegates
 * recursive closure to {@link CallableSummarySolver}.  This class is an
 * internal, JVM-independent support layer; it does not publish facts or alter
 * initialization analysis.
 */
public final class CallableSummaryCompiler {
    private CallableSummaryCompiler() {
    }

    public static CallableSummaryResult compile(TypedSemanticGraph graph) {
        return compile((TypedSemanticInput) Objects.requireNonNull(graph, "graph"),
                SummaryLimits.DEFAULT);
    }

    public static CallableSummaryResult analyze(TypedSemanticGraph graph) {
        return compile(graph);
    }

    public static CallableSummaryResult summarize(TypedSemanticGraph graph) {
        return compile(graph);
    }

    /** Preserved typed-graph entry point for existing compiler consumers. */
    public static CallableSummaryResult compile(
            TypedSemanticGraph graph,
            SummaryLimits limits) {
        return compile((TypedSemanticInput) Objects.requireNonNull(graph, "graph"), limits);
    }

    /** Internal overload used by the pre-seal typed semantic core. */
    public static CallableSummaryResult compile(
            TypedSemanticInput graph,
            SummaryLimits limits) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(limits, "limits");
        try {
            Context context = new Context(graph, limits);
            TreeMap<LambdaId, CallableSummary> raw = new TreeMap<>();
            Set<LambdaId> retained = new TreeSet<>();
            for (TypedLambda lambda : graph.lambdas().stream()
                    .sorted(java.util.Comparator.comparing(TypedLambda::id)).toList()) {
                CallableSummary summary = graph.resolvedGraph().isRetained(lambda.moduleId())
                        ? graph.resolvedGraph().retainedModules().module(lambda.moduleId()).orElseThrow()
                        .callableSummaries().summary(lambda.id()).orElseThrow()
                        : new LambdaBuilder(context, lambda).build();
                putSummary(raw, summary);
                if (graph.resolvedGraph().isRetained(lambda.moduleId())) {
                    retained.add(lambda.id());
                }
            }

            // A retained application module may call a lambda in a transitive
            // borrowed dependency that is not itself a node in the current
            // submission graph.  Its producer certificate is the only valid
            // source of that summary; treating the target as absent turns a
            // valid higher-order call into a compiler invariant failure.
            graph.resolvedGraph().sessionFlowCertificate().ifPresent(certificate -> {
                for (CallableSummary summary : certificate.callableSummaries().orderedSummaries()) {
                    putSummary(raw, summary);
                    retained.add(summary.lambdaId());
                }
            });
            TreeMap<DeclarationId, LambdaId> declarationLinks = new TreeMap<>(
                    graph.resolvedGraph().sessionFlowCertificate()
                            .map(value -> value.callableSummaries().lambdaByDeclaration())
                            .orElse(Map.of()));
            context.lambdaByDeclaration.forEach((declaration, lambda) -> {
                LambdaId previous = declarationLinks.putIfAbsent(declaration, lambda);
                if (previous != null && !previous.equals(lambda)) {
                    throw new IllegalArgumentException(
                            "callable declaration links disagree about " + declaration);
                }
            });
            TreeMap<DeclarationId, ModuleId> intrinsicLinks = new TreeMap<>(
                    graph.resolvedGraph().sessionFlowCertificate()
                            .map(value -> value.callableSummaries().intrinsicDeclarations())
                            .orElse(Map.of()));
            context.intrinsicDeclarations.forEach((declaration, module) -> {
                ModuleId previous = intrinsicLinks.putIfAbsent(declaration, module);
                if (previous != null && !previous.equals(module)) {
                    throw new IllegalArgumentException(
                            "callable intrinsic links disagree about " + declaration);
                }
            });
            CallableSummaryResult solved = CallableSummarySolver.solve(
                    raw.values(), declarationLinks, intrinsicLinks,
                    context.computedCallableDeclarations,
                    context.externalCallableDeclarations,
                    context.potentialCallableLambdas(), limits, retained);
            if (solved instanceof CallableSummaryResult.Success success) {
                Set<LambdaId> currentLambdas = graph.lambdas().stream()
                        .map(TypedLambda::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
                return new CallableSummaryResult.Success(success.value().select(
                        currentLambdas, context.lambdaByDeclaration.keySet(),
                        context.intrinsicDeclarations.keySet()));
            }
            return solved;
        } catch (SummaryFailureException failure) {
            return CallableSummaryResult.failure(
                    CallableSummaryResult.InternalFailure.Kind.INVALID_TYPED_EXPRESSION,
                    failure.getMessage(), Optional.of(failure.span()));
        } catch (SummaryLimits.SummaryDomainException failure) {
            Optional<SourceSpan> span = graph.lambdas().stream()
                    .map(TypedLambda::span).findFirst();
            return CallableSummaryResult.failure(
                    CallableSummaryResult.InternalFailure.Kind.DOMAIN_LIMIT,
                    failure.getMessage(), span);
        } catch (IllegalArgumentException failure) {
            return CallableSummaryResult.failure(
                    CallableSummaryResult.InternalFailure.Kind.INCONSISTENT_SUMMARY,
                    failure.getMessage() == null ? "invalid typed callable summary input" : failure.getMessage(),
                    Optional.empty());
        }
    }

    private static void putSummary(
            Map<LambdaId, CallableSummary> summaries,
            CallableSummary summary) {
        CallableSummary previous = summaries.putIfAbsent(summary.lambdaId(), summary);
        if (previous != null && !previous.equals(summary)) {
            throw new IllegalArgumentException(
                    "callable summary identity has conflicting producer facts: "
                            + summary.lambdaId());
        }
    }

    /** Alias used by phase-local callers that call the operation analysis. */
    public static CallableSummaryResult process(TypedSemanticGraph graph) {
        return compile(graph);
    }

    private static final class Context {
        private final TypedSemanticInput graph;
        private final SummaryLimits limits;
        private final Map<LambdaId, TypedLambda> lambdas = new TreeMap<>();
        private final Map<DeclarationId, LambdaId> initializerLambdaByDeclaration =
                new TreeMap<>();
        private final Map<DeclarationId, LambdaId> lambdaByDeclaration = new TreeMap<>();
        private final Map<DeclarationId, CallableDeclarationIdentity>
                callableIdentitiesByDeclaration = new TreeMap<>();
        private final Map<DeclarationId, TypedDeclaration> declarations = new TreeMap<>();
        private final Map<CaptureId, ResolvedCapture> captures = new TreeMap<>();
        private final Map<DeclarationId, ResolvedDeclaration> resolvedDeclarations = new TreeMap<>();
        private final Map<DeclarationId, ModuleId> intrinsicDeclarations = new TreeMap<>();
        private final Set<DeclarationId> computedCallableDeclarations = new TreeSet<>();
        private final Set<DeclarationId> externalCallableDeclarations = new TreeSet<>();

        private Context(TypedSemanticInput graph, SummaryLimits limits) {
            this.graph = graph;
            this.limits = limits;
            for (TypedLambda lambda : graph.lambdas()) {
                if (lambdas.put(lambda.id(), lambda) != null) {
                    throw new IllegalArgumentException("duplicate typed lambda identity");
                }
            }
            for (ResolvedDeclaration declaration : graph.resolvedGraph().declarations()) {
                if (resolvedDeclarations.put(declaration.id(), declaration) != null) {
                    throw new IllegalArgumentException("duplicate resolved declaration identity");
                }
                if (declaration.kind()
                        == io.mindspice.lyra.compiler.semantic.DeclarationKind.INTRINSIC_EXPORT) {
                    intrinsicDeclarations.put(declaration.id(), declaration.moduleId());
                }
                if (declaration.kind()
                        == io.mindspice.lyra.compiler.semantic.DeclarationKind.EXTERNAL
                        && graph.contract(declaration.id())
                        .map(BindingContract::valueType)
                        .filter(CallableSummarySet::containsCallableType)
                        .isPresent()) {
                    externalCallableDeclarations.add(declaration.id());
                }
            }
            boolean discoveredIntrinsicAlias;
            do {
                discoveredIntrinsicAlias = false;
                for (ResolvedDeclaration declaration : resolvedDeclarations.values()) {
                    if (declaration.kind()
                            != io.mindspice.lyra.compiler.semantic.DeclarationKind.IMPORT_VALUE
                            || declaration.originDeclaration().isEmpty()
                            || intrinsicDeclarations.containsKey(declaration.id())) {
                        continue;
                    }
                    ModuleId intrinsicModule = intrinsicDeclarations.get(
                            declaration.originDeclaration().orElseThrow());
                    if (intrinsicModule != null) {
                        intrinsicDeclarations.put(declaration.id(), intrinsicModule);
                        discoveredIntrinsicAlias = true;
                    }
                }
            } while (discoveredIntrinsicAlias);
            for (TypedDeclaration declaration : graph.declarations()) {
                if (declarations.put(declaration.id(), declaration) != null) {
                    throw new IllegalArgumentException("duplicate typed declaration identity");
                }
                declaration.initializerLambda().ifPresent(lambda -> {
                    if (initializerLambdaByDeclaration.put(
                            declaration.id(), lambda) != null) {
                        throw new IllegalArgumentException(
                                "duplicate declaration lambda linkage");
                    }
                });
            }
            for (DeclarationId declaration : declarations.keySet()) {
                CallableDeclarationIdentity identity = resolveCallableIdentity(
                        declaration, new TreeSet<>());
                if (identity.computed()) {
                    computedCallableDeclarations.add(declaration);
                }
            }
            for (Map.Entry<DeclarationId, CallableDeclarationIdentity> entry
                    : callableIdentitiesByDeclaration.entrySet()) {
                if (entry.getValue().lambdas().size() == 1
                        && !entry.getValue().computed()) {
                    lambdaByDeclaration.putIfAbsent(
                            entry.getKey(), entry.getValue().lambdas().getFirst());
                }
            }
            for (ResolvedCapture capture : graph.resolvedGraph().captures()) {
                if (captures.put(capture.id(), capture) != null) {
                    throw new IllegalArgumentException("duplicate capture identity");
                }
            }
        }

        private TypedLambda lambda(LambdaId id, SourceSpan span) {
            TypedLambda lambda = lambdas.get(id);
            if (lambda == null) {
                throw new SummaryFailureException("typed expression links to an absent lambda: " + id, span);
            }
            return lambda;
        }

        private BindingContract contract(DeclarationId id, SourceSpan span) {
            return graph.contract(id).orElseThrow(() ->
                    new SummaryFailureException(
                            "typed callable fact is missing a binding contract: " + id, span));
        }

        private io.mindspice.lyra.compiler.identity.FlowSiteId site(
                TypedExpression expression) {
            return graph.flowSiteId(Objects.requireNonNull(expression, "expression"));
        }

        private io.mindspice.lyra.compiler.identity.FlowSiteId site(
                ResolvedCapture capture) {
            return capture.references().stream().findFirst()
                    .map(graph::flowSiteId)
                    .orElseGet(() -> graph.flowSiteId(capture.id()));
        }

        private ResolvedCapture capture(CaptureId id, SourceSpan span) {
            ResolvedCapture capture = captures.get(id);
            if (capture == null) {
                throw new SummaryFailureException(
                        "typed expression links to an absent capture: " + id, span);
            }
            return capture;
        }

        private boolean isValueDeclaration(DeclarationId id) {
            ResolvedDeclaration declaration = resolvedDeclarations.get(id);
            return declaration == null || declaration.kind()
                    != io.mindspice.lyra.compiler.semantic.DeclarationKind.IMPORT_MODULE;
        }

        private ModuleId declarationModule(DeclarationId id, SourceSpan span) {
            ResolvedDeclaration declaration = resolvedDeclarations.get(id);
            if (declaration == null) {
                TypedDeclaration typed = declarations.get(id);
                if (typed == null) {
                    throw new SummaryFailureException(
                            "typed expression links to an absent declaration: " + id, span);
                }
                return typed.moduleId();
            }
            return declaration.importedFrom().orElse(declaration.moduleId());
        }

        private List<LambdaId> callableLambdas(DeclarationId declaration) {
            CallableDeclarationIdentity identity =
                    callableIdentitiesByDeclaration.get(declaration);
            return identity == null ? List.of() : identity.lambdas();
        }

        private CallableDeclarationIdentity resolveCallableIdentity(
                DeclarationId declaration,
                Set<DeclarationId> active) {
            CallableDeclarationIdentity known =
                    callableIdentitiesByDeclaration.get(declaration);
            if (known != null) {
                return known;
            }
            LyraType type = graph.contract(declaration)
                    .map(BindingContract::valueType)
                    .map(LyraType::withoutQualifiers)
                    .orElse(null);
            if (!(type instanceof FunctionType)) {
                CallableDeclarationIdentity empty =
                        CallableDeclarationIdentity.empty();
                callableIdentitiesByDeclaration.put(declaration, empty);
                return empty;
            }
            ResolvedDeclaration resolved = resolvedDeclarations.get(declaration);
            LambdaId direct = initializerLambdaByDeclaration.get(declaration);
            if (direct != null) {
                CallableDeclarationIdentity result = new CallableDeclarationIdentity(
                        List.of(direct), resolved != null && resolved.isMutable());
                callableIdentitiesByDeclaration.put(declaration, result);
                return result;
            }
            if (!active.add(declaration)) {
                return CallableDeclarationIdentity.computedValue();
            }
            CallableDeclarationIdentity result;
            try {
                if (resolved != null
                        && resolved.kind()
                        == io.mindspice.lyra.compiler.semantic.DeclarationKind.IMPORT_VALUE
                        && resolved.originDeclaration().isPresent()) {
                    result = resolveCallableIdentity(
                            resolved.originDeclaration().orElseThrow(), active);
                } else {
                    TypedDeclaration typed = declarations.get(declaration);
                    result = typed == null || typed.initializer().isEmpty()
                            ? CallableDeclarationIdentity.empty()
                            : collectCallableIdentity(
                            typed.initializer().orElseThrow(), active);
                }
                if (resolved != null && resolved.isMutable()) {
                    result = result.requiringCanonicalValue();
                }
            } finally {
                active.remove(declaration);
            }
            callableIdentitiesByDeclaration.put(declaration, result);
            return result;
        }

        // Classifies only whether syntax fixes an identity; current values,
        // effects, writes, and call results remain owned by canonical flow.
        private CallableDeclarationIdentity collectCallableIdentity(
                TypedExpression expression,
                Set<DeclarationId> active) {
            return switch (expression.kind()) {
                case LAMBDA -> expression.lambdaId()
                        .map(id -> CallableDeclarationIdentity.staticLambdas(
                                List.of(id)))
                        .orElseGet(CallableDeclarationIdentity::computedValue);
                case REFERENCE, NAMESPACE_MEMBER_ACCESS -> expression.link()
                        .flatMap(TypedLink::declarationId)
                        .map(declaration -> resolveCallableIdentity(
                                declaration, active))
                        .orElseGet(CallableDeclarationIdentity::computedValue);
                case CONVERSION, NARROWING -> expression.children().isEmpty()
                        ? CallableDeclarationIdentity.computedValue()
                        : collectCallableIdentity(
                        expression.children().getFirst(), active);
                case CONDITIONAL -> combineCallableIdentities(
                        expression.children().stream().skip(1)
                                .map(child -> collectCallableIdentity(child, active))
                                .toList()).requiringCanonicalValue();
                case COALESCE -> combineCallableIdentities(
                        expression.children().stream()
                                .map(child -> collectCallableIdentity(child, active))
                                .toList()).requiringCanonicalValue();
                default -> CallableDeclarationIdentity.computedValue();
            };
        }

        private CallableDeclarationIdentity combineCallableIdentities(
                List<CallableDeclarationIdentity> identities) {
            TreeSet<LambdaId> lambdas = new TreeSet<>();
            boolean computed = identities.isEmpty();
            for (CallableDeclarationIdentity identity : identities) {
                lambdas.addAll(identity.lambdas());
                computed |= identity.computed();
            }
            return new CallableDeclarationIdentity(
                    List.copyOf(lambdas), computed);
        }

        private record CallableDeclarationIdentity(
                List<LambdaId> lambdas,
                boolean computed) {
            private CallableDeclarationIdentity {
                lambdas = List.copyOf(new TreeSet<>(lambdas));
            }

            private static CallableDeclarationIdentity empty() {
                return new CallableDeclarationIdentity(List.of(), false);
            }

            private static CallableDeclarationIdentity staticLambdas(
                    List<LambdaId> lambdas) {
                return new CallableDeclarationIdentity(lambdas, false);
            }

            private static CallableDeclarationIdentity computedValue() {
                return new CallableDeclarationIdentity(List.of(), true);
            }

            private CallableDeclarationIdentity requiringCanonicalValue() {
                return computed ? this
                        : new CallableDeclarationIdentity(lambdas, true);
            }
        }

        private Map<DeclarationId, List<LambdaId>> potentialCallableLambdas() {
            // A mutable predeclared slot keeps its initializer lambda only as
            // a possible recursive SCC edge. It is not the slot's current
            // callable identity after source-ordered rebinding.
            TreeMap<DeclarationId, List<LambdaId>> result = new TreeMap<>();
            initializerLambdaByDeclaration.forEach((declaration, lambda) -> {
                if (computedCallableDeclarations.contains(declaration)) {
                    result.put(declaration, List.of(lambda));
                }
            });
            return Map.copyOf(result);
        }

        private FunctionType functionType(DeclarationId declaration, SourceSpan span) {
            List<LambdaId> callable = callableLambdas(declaration);
            if (!callable.isEmpty()) {
                return this.lambda(callable.getFirst(), span).signature().asFunctionType();
            }
            LyraType type = contract(declaration, span).valueType().withoutQualifiers();
            if (type instanceof FunctionType function) {
                return function;
            }
            throw new SummaryFailureException(
                    "call target declaration has no callable type: " + declaration, span);
        }
    }

    private static final class LambdaBuilder {
        private final Context context;
        private final TypedLambda lambda;
        private final Map<DeclarationId, Integer> parameterIndexes = new TreeMap<>();
        private final Map<CaptureId, ResolvedCapture> lambdaCaptures = new TreeMap<>();
        private final Set<DeclarationId> activeCaptureDeclarations = new LinkedHashSet<>();
        private final List<OwnershipRequirement> ownershipRequirements = new ArrayList<>();
        private int nextEventOrdinal;
        private int nextAllocationOrdinal;

        private LambdaBuilder(Context context, TypedLambda lambda) {
            this.context = context;
            this.lambda = lambda;
            for (int index = 0; index < lambda.parameterIds().size(); index++) {
                DeclarationId declaration = lambda.parameterIds().get(index);
                if (parameterIndexes.put(declaration, index) != null) {
                    throw new SummaryFailureException(
                            "lambda contains a duplicate parameter identity", lambda.span());
                }
            }
            for (CaptureId captureId : lambda.captures()) {
                ResolvedCapture capture = context.capture(captureId, lambda.span());
                if (context.isValueDeclaration(capture.declarationId())) {
                    lambdaCaptures.put(captureId, capture);
                }
            }
        }

        private CallableSummary build() {
            List<CallableSummary.ParameterPlaceholder> parameters = new ArrayList<>();
            for (int index = 0; index < lambda.parameterIds().size(); index++) {
                DeclarationId declaration = lambda.parameterIds().get(index);
                parameters.add(new CallableSummary.ParameterPlaceholder(
                        index, declaration, context.contract(declaration, lambda.span())));
            }

            List<CallableSummary.CapturePlaceholder> captures = new ArrayList<>();
            for (CaptureId captureId : lambda.captures()) {
                ResolvedCapture capture = lambdaCaptures.get(captureId);
                if (capture == null) {
                    continue;
                }
                captures.add(new CallableSummary.CapturePlaceholder(
                        capture.id(), capture.declarationId(), capture.mode(),
                        capture.sharedCellId(), context.contract(capture.declarationId(), capture.span())));
            }

            Map<DeclarationId, FormulaAlternatives> initialState = new TreeMap<>();
            for (CallableSummary.ParameterPlaceholder parameter : parameters) {
                initialState.put(parameter.declarationId(), new FormulaAlternatives(
                        parameter.type(), List.of(new ValueFormula.Parameter(
                                parameter.declarationId(), parameter.index(),
                                ProjectionPath.root(), ProjectionPath.root(), parameter.type()))));
            }
            for (CallableSummary.CapturePlaceholder capture : captures) {
                initialState.put(capture.declarationId(), new FormulaAlternatives(
                        capture.type(), List.of(new ValueFormula.Capture(
                                capture.captureId(), capture.declarationId(), capture.sharedCellId(),
                                ProjectionPath.root(), ProjectionPath.root(), capture.type()))));
            }

            for (ResolvedDeclaration declaration : context.graph.resolvedGraph().declarations()) {
                if (declaration.externalBinding().isPresent()
                        && declaration.moduleId().equals(lambda.moduleId())) {
                    // No initializer-derived facts cross a submission boundary.
                    // The data-storage profile excludes imported aggregates and
                    // callable-bearing values. Declaration formulas obtain their
                    // routed may-alias facts from the canonical caller state.
                    // A registered-root certificate may supply conservative
                    // imported data facts for externally owned aggregates;
                    // those bindings are admitted without claiming session
                    // ownership of their contents.
                    io.mindspice.lyra.compiler.session.ExternalBinding binding =
                            declaration.externalBinding().orElseThrow();
                    LyraType type = context.contract(declaration.id(), declaration.span()).valueType();
                    boolean certifiedExternalData = context.graph.resolvedGraph()
                            .sessionFlowCertificate()
                            .map(certificate -> certificate.certifiesBinding(binding))
                            .orElse(false);
                    if (!binding.supportsSessionStorage()
                            && !context.externalCallableDeclarations.contains(declaration.id())
                            && !certifiedExternalData) {
                        throw failure("external value flow has not been certified", declaration.span());
                    }
                    initialState.put(declaration.id(), type.withoutQualifiers() instanceof PrimitiveType
                            ? scalar(type).value()
                            : FormulaAlternatives.singleton(new ValueFormula.Declaration(
                                    declaration.id(), Optional.of(declaration.moduleId()),
                                    ProjectionPath.root(), ProjectionPath.root(), type)));
                }
            }
            if (context.graph.resolvedGraph().isSessionGraph()) {
                // A session module-root mutable binding is live state rather
                // than a lexical capture.  Seed only such cells; seeding
                // immutable callable declarations would change self-recursive
                // summary resolution.
                context.graph.resolvedGraph().module(lambda.moduleId()).ifPresent(module ->
                        module.declarations().stream()
                                .map(context.resolvedDeclarations::get)
                                .filter(Objects::nonNull)
                                .filter(ResolvedDeclaration::isMutable)
                                .filter(declaration -> declaration.kind()
                                        == io.mindspice.lyra.compiler.semantic.DeclarationKind.LET)
                                .filter(declaration -> lambdaCaptures.values().stream()
                                        .noneMatch(capture -> capture.declarationId()
                                                .equals(declaration.id())))
                                .forEach(declaration -> {
                                    LyraType type = context.contract(
                                            declaration.id(), declaration.span()).valueType();
                                    initialState.putIfAbsent(
                                            declaration.id(),
                                            type.withoutQualifiers() instanceof PrimitiveType
                                                    ? scalar(type).value()
                                                    : FormulaAlternatives.singleton(
                                                    new ValueFormula.Declaration(
                                                            declaration.id(),
                                                            Optional.of(declaration.moduleId()),
                                                            ProjectionPath.root(),
                                                            ProjectionPath.root(), type)));
                                }));
            }
            Eval body = evaluate(lambda.body(), initialState);
            validateFormulaPaths(body.value());
            for (CapturedCellWrite write : body.writes()) {
                context.limits.requireProjectionDepth(write.route().depth());
                validateFormulaPaths(write.value());
            }
            for (CallableCallReference call : body.calls()) {
                validateFormulaPaths(call.target());
                for (FormulaAlternatives argument : call.arguments()) {
                    validateFormulaPaths(argument);
                }
            }
            for (OwnershipRequirement requirement : ownershipRequirements) {
                validateFormulaPaths(requirement.value());
            }
            for (EagerEffectWitness effect : body.effects()) {
                context.limits.requireWitnessPathDepth(effect.sourcePath().size());
            }
            context.limits.requireCallReferences(body.calls().size());
            context.limits.requireWrites(body.writes().size());
            context.limits.requireOwnershipRequirements(
                    ownershipRequirements.size());
            context.limits.requireEffects(body.effects().size());
            FormulaAlternatives returnValues = ensureValue(
                    body.value().asType(lambda.signature().returnType()),
                    lambda.body().span(), "lambda return");
            NormalizedExpression normalized = TypedExpressionNormalizer.normalize(lambda.body());
            return new CallableSummary(
                    lambda.id(), lambda.moduleId(), lambda.span(), lambda.scopeId(), lambda.signature(),
                    parameters, captures,
                    new CallableSummary.ReturnFormula(lambda.signature().returnType(), returnValues),
                    body.writes(), body.calls(), ownershipRequirements,
                    body.effects(), normalized, false, 0, context.limits);
        }

        private Eval evaluate(
                TypedExpression expression,
                Map<DeclarationId, FormulaAlternatives> state) {
            return switch (expression.kind()) {
                case LITERAL -> literal(expression, state);
                case REFERENCE -> reference(expression, state);
                case NAMESPACE_MEMBER_ACCESS -> namespaceMember(expression, state);
                case DECLARATION -> declaration(expression, state);
                case REBINDING -> rebinding(expression, state);
                case BLOCK -> block(expression, state);
                case CONDITIONAL -> conditional(expression, state);
                case COALESCE -> coalesce(expression, state);
                case LAMBDA -> lambdaValue(expression, state);
                case CALLABLE_CALL -> callableCall(expression, state);
                case DIRECT_CALL -> directCall(expression, state, CallableCallReference.Kind.DIRECT);
                case NAMESPACE_DIRECT_CALL -> directCall(expression, state, CallableCallReference.Kind.NAMESPACE);
                case MEMBER_ACCESS -> member(expression, state);
                case ARRAY_LITERAL -> array(expression, state);
                case TUPLE_LITERAL -> tuple(expression, state);
                case INDEX_ACCESS -> index(expression, state);
                case OPERATOR, SHORT_CIRCUIT -> operator(expression, state);
                case CONVERSION, NARROWING -> unaryValue(expression, state);
            };
        }

        private Eval literal(
                TypedExpression expression,
                Map<DeclarationId, FormulaAlternatives> state) {
            ValueFormula.Scalar formula = expression.literal().orElse(null)
                    instanceof io.mindspice.lyra.compiler.semantic.TypedLiteralValue.NilValue
                    ? ValueFormula.Scalar.nil(
                    expression.type(), context.site(expression), expression.span())
                    : ValueFormula.Scalar.literal(expression.type(),
                    expression.literal().map(Object::toString).orElse("literal"));
            return value(expression, state, FormulaAlternatives.singleton(formula));
        }

        private Eval reference(
                TypedExpression expression,
                Map<DeclarationId, FormulaAlternatives> state) {
            TypedLink link = expression.link().orElseThrow(() ->
                    failure("reference has no typed link", expression.span()));
            DeclarationId declaration = link.declarationId().orElseThrow(() ->
                    failure("value reference has no declaration identity", expression.span()));
            FormulaAlternatives formulas = state.get(declaration);
            if (formulas == null) {
                formulas = declarationFormula(declaration, expression.type(), state, expression.span());
            } else {
                formulas = formulas.asType(expression.type());
            }
            boolean localOrParameter = state.containsKey(declaration)
                    && expression.captureIds().isEmpty();
            boolean eagerCallableValue = isCallableType(expression.type())
                    && context.computedCallableDeclarations.contains(declaration);
            List<EagerEffectWitness> effects = localOrParameter
                    || isCallableType(expression.type()) && !eagerCallableValue
                    ? List.of()
                    : List.of(valueRead(expression, link, declaration));
            return value(expression, state, formulas, List.of(), effects);
        }

        private Eval namespaceMember(
                TypedExpression expression,
                Map<DeclarationId, FormulaAlternatives> state) {
            TypedLink link = expression.link().orElseThrow(() ->
                    failure("namespace member has no typed link", expression.span()));
            DeclarationId declaration = link.declarationId().orElseThrow(() ->
                    failure("namespace value has no declaration identity", expression.span()));
            FormulaAlternatives formulas = declarationFormula(
                    declaration, expression.type(), state, expression.span());
            List<EagerEffectWitness> effects = isCallableType(expression.type())
                    && !context.computedCallableDeclarations.contains(declaration)
                    ? List.of()
                    : List.of(valueRead(expression, link, declaration));
            return value(expression, state, formulas, List.of(), effects);
        }

        private Eval declaration(
                TypedExpression expression,
                Map<DeclarationId, FormulaAlternatives> state) {
            if (expression.children().size() != 1 || expression.declarationId().isEmpty()) {
                throw failure("declaration operation is incomplete", expression.span());
            }
            Eval initializer = evaluate(expression.children().getFirst(), state);
            Map<DeclarationId, FormulaAlternatives> next = copyState(initializer.state());
            DeclarationId declaration = expression.declarationId().orElseThrow();
            next.put(declaration, initializer.value().asType(
                    context.contract(declaration, expression.span()).valueType()));
            return new Eval(
                    scalar(expression.type()).value(), next, initializer.writes(),
                    initializer.calls(), initializer.effects());
        }

        private Eval rebinding(
                TypedExpression expression,
                Map<DeclarationId, FormulaAlternatives> state) {
            if (expression.children().size() != 2) {
                throw failure("rebinding operation is incomplete", expression.span());
            }
            TypedExpression targetExpression = expression.children().getFirst();
            Eval target = evaluate(targetExpression, state);
            TargetPath targetPath = targetPath(targetExpression);
            FormulaAlternatives targetValue = target.state().get(targetPath.declaration());
            if (targetValue == null) {
                throw failure("rebind target has no current symbolic value", expression.span());
            }
            recordMutationOwnershipRequirement(
                    targetExpression, targetPath, targetValue);

            Eval value = evaluate(expression.children().get(1), target.state());
            FormulaAlternatives current = value.state().get(targetPath.declaration());
            if (current == null) {
                throw failure("rebind target has no current symbolic value", expression.span());
            }
            LyraType routeType = ValueAlternative.typeAt(
                    current.rootType(), targetPath.route());
            FormulaAlternatives replacement = value.value().asType(routeType);
            Map<DeclarationId, FormulaAlternatives> next = copyState(value.state());
            FormulaAlternatives updated;
            if (targetPath.route().containsWildcard()) {
                updated = current.replaceUnknown(targetPath.route(), replacement);
            } else {
                updated = current.replaceExact(targetPath.route(), replacement);
            }
            next.put(targetPath.declaration(), updated);

            ArrayList<CapturedCellWrite> writes = new ArrayList<>(target.writes());
            writes.addAll(value.writes());
            int firstTargetWrite = writes.size();
            int writeSequence = nextEventOrdinal++;
            Optional<CapturedCellWrite> symbolicWrite = targetPath.write(
                    context, expression, replacement, writeSequence);
            if (symbolicWrite.isPresent()) {
                writes.add(symbolicWrite.orElseThrow());
            } else if (parameterIndexes.containsKey(targetPath.declaration())) {
                writes.add(CapturedCellWrite.parameter(
                        writeSequence, parameterIndexes.get(targetPath.declaration()),
                        targetPath.declaration(), writeKind(targetPath.route()), targetPath.route(),
                        replacement, expression.span()));
            } else if (!targetPath.route().isRoot()) {
                writes.addAll(aliasOriginWrites(
                        current, targetPath.route(), replacement, writeSequence, expression.span()));
            }
            for (int index = firstTargetWrite; index < writes.size(); index++) {
                writes.set(index, writes.get(index).withOperationSite(
                        Optional.of(context.graph.flowSiteId(expression))));
            }
            return new Eval(scalar(expression.type()).value(), next, writes,
                    concat(target.calls(), value.calls()),
                    concat(target.effects(), value.effects()));
        }

        private Eval block(
                TypedExpression expression,
                Map<DeclarationId, FormulaAlternatives> state) {
            Map<DeclarationId, FormulaAlternatives> current = copyState(state);
            ArrayList<CapturedCellWrite> writes = new ArrayList<>();
            ArrayList<CallableCallReference> calls = new ArrayList<>();
            ArrayList<EagerEffectWitness> effects = new ArrayList<>();
            FormulaAlternatives result = null;
            for (TypedExpression child : expression.children()) {
                Eval evaluated = evaluate(child, current);
                current = evaluated.state();
                result = evaluated.value();
                writes.addAll(evaluated.writes());
                calls.addAll(evaluated.calls());
                effects.addAll(evaluated.effects());
            }
            if (result == null) {
                result = scalar(expression.type()).value();
            }
            return new Eval(result.asType(expression.type()), current, writes, calls, effects);
        }

        private Eval conditional(
                TypedExpression expression,
                Map<DeclarationId, FormulaAlternatives> state) {
            if (expression.children().size() != 2 && expression.children().size() != 3) {
                throw failure("conditional operation has an invalid branch count", expression.span());
            }
            Eval predicate = evaluate(expression.children().getFirst(), state);
            Map<DeclarationId, FormulaAlternatives> thenState = predicate.state();
            if (expression.predicateBinding().isPresent()) {
                DeclarationId binding = expression.predicateBinding().orElseThrow();
                thenState = copyState(thenState);
                thenState.put(binding, predicate.value().asType(
                        context.contract(binding, expression.span()).valueType()));
            }
            Eval thenBranch = evaluate(expression.children().get(1), thenState);
            ArrayList<CapturedCellWrite> writes = new ArrayList<>(predicate.writes());
            ArrayList<CallableCallReference> calls = new ArrayList<>(predicate.calls());
            calls.addAll(thenBranch.calls());
            ArrayList<EagerEffectWitness> effects = new ArrayList<>(predicate.effects());
            effects.addAll(thenBranch.effects());
            if (expression.children().size() == 2) {
                writes.addAll(joinBranchWrites(
                        thenBranch.writes(), List.of(), predicate.state()));
                return new Eval(scalar(expression.type()).value(),
                        joinStates(predicate.state(), thenBranch.state()), writes, calls, effects);
            }
            Eval elseBranch = evaluate(expression.children().get(2), predicate.state());
            writes.addAll(joinBranchWrites(
                    thenBranch.writes(), elseBranch.writes(), predicate.state()));
            calls.addAll(elseBranch.calls());
            effects.addAll(elseBranch.effects());
            FormulaAlternatives result = thenBranch.value().asType(expression.type())
                    .join(elseBranch.value().asType(expression.type()));
            return new Eval(result,
                    joinStates(thenBranch.state(), elseBranch.state()), writes, calls, effects);
        }

        private Eval coalesce(
                TypedExpression expression,
                Map<DeclarationId, FormulaAlternatives> state) {
            if (expression.children().size() != 2) {
                throw failure("coalesce operation has an invalid child count", expression.span());
            }
            Eval value = evaluate(expression.children().getFirst(), state);
            Eval fallback = evaluate(expression.children().get(1), value.state());
            FormulaAlternatives result = value.value().asType(expression.type())
                    .coalesceJoin(fallback.value().asType(expression.type()));
            ArrayList<CapturedCellWrite> writes = new ArrayList<>(value.writes());
            writes.addAll(joinBranchWrites(
                    fallback.writes(), List.of(), value.state()));
            return new Eval(result,
                    joinStates(value.state(), fallback.state()),
                    writes,
                    concat(value.calls(), fallback.calls()),
                    concat(value.effects(), fallback.effects()));
        }

        private Eval lambdaValue(
                TypedExpression expression,
                Map<DeclarationId, FormulaAlternatives> state) {
            LambdaId id = expression.lambdaId().orElseThrow(() ->
                    failure("lambda expression has no identity", expression.span()));
            TypedLambda nested = context.lambda(id, expression.span());
            FunctionType type = nested.signature().asFunctionType();
            ValueFormula.Lambda formula = new ValueFormula.Lambda(
                    id, type, ProjectionPath.root(), captureEnvironment(nested, state, expression.span()));
            // Creating a closure does not execute its body, but capture values
            // are read eagerly at creation and therefore remain effects of the
            // enclosing summary.
            return value(expression, state, FormulaAlternatives.singleton(formula),
                    List.of(), captureReadEffects(nested, state));
        }

        private Map<CaptureId, FormulaAlternatives> captureEnvironment(
                TypedLambda nested,
                Map<DeclarationId, FormulaAlternatives> state,
                SourceSpan span) {
            TreeMap<CaptureId, FormulaAlternatives> capturedValues = new TreeMap<>();
            for (CaptureId captureId : nested.captureIds()) {
                ResolvedCapture capture = context.capture(captureId, span);
                if (!context.isValueDeclaration(capture.declarationId())) {
                    continue;
                }
                FormulaAlternatives captured = state.get(capture.declarationId());
                if (captured == null) {
                    BindingContract contract = context.contract(capture.declarationId(), span);
                    if (!activeCaptureDeclarations.add(capture.declarationId())) {
                        // A recursive function slot may capture itself or a
                        // mutually recursive slot.  The declaration term is
                        // the finite fixed-point seed; it avoids expanding a
                        // closure environment indefinitely while preserving
                        // the callable identity for the solver.
                        captured = FormulaAlternatives.singleton(new ValueFormula.Declaration(
                                capture.declarationId(),
                                Optional.of(context.declarationModule(
                                        capture.declarationId(), span)),
                                ProjectionPath.root(), ProjectionPath.root(),
                                contract.valueType()));
                    } else {
                        try {
                            captured = declarationFormula(capture.declarationId(),
                                    contract.valueType(), state, span);
                        } finally {
                            activeCaptureDeclarations.remove(capture.declarationId());
                        }
                    }
                }
                capturedValues.put(captureId, captured);
            }
            return capturedValues;
        }

        private List<EagerEffectWitness> captureReadEffects(
                TypedLambda nested,
                Map<DeclarationId, FormulaAlternatives> state) {
            ArrayList<EagerEffectWitness> effects = new ArrayList<>();
            for (CaptureId captureId : nested.captureIds()) {
                ResolvedCapture capture = context.capture(captureId, nested.span());
                if (!context.isValueDeclaration(capture.declarationId())
                        || state.containsKey(capture.declarationId())) {
                    continue;
                }
                ModuleId target = context.declarationModule(
                        capture.declarationId(), capture.span());
                io.mindspice.lyra.compiler.identity.FlowSiteId site = context.site(capture);
                effects.add(new EagerEffectWitness(
                        lambda.moduleId(), target, EagerEffectWitness.Kind.VALUE_READ,
                        capture.span(), Optional.of(capture.declarationId()),
                        capture.references().stream().findFirst(), Optional.empty(),
                        List.of(capture.span()), List.of(), false,
                        Optional.of(site), List.of(site)));
            }
            return List.copyOf(new TreeSet<>(effects));
        }

        private Eval callableCall(
                TypedExpression expression,
                Map<DeclarationId, FormulaAlternatives> state) {
            if (expression.children().isEmpty()) {
                throw failure("callable call has no target", expression.span());
            }
            Eval target = evaluate(expression.children().getFirst(), state);
            Sequence arguments = evaluateSequential(
                    expression.children().subList(1, expression.children().size()), target.state());
            CallableCallReference.Kind kind = parameterTarget(target.value())
                    ? CallableCallReference.Kind.PARAMETER
                    : captureTarget(target.value())
                    ? CallableCallReference.Kind.CAPTURE
                    : CallableCallReference.Kind.CALLABLE;
            FunctionType function = (FunctionType) target.value()
                    .rootType().withoutQualifiers();
            recordMutableArgumentOwnershipRequirements(
                    expression.children().subList(1, expression.children().size()),
                    arguments.values(), function);
            SummaryCallId id = nextCall();
            CallableCallReference call = new CallableCallReference(
                    id, kind, expression.span(), Optional.empty(),
                    singleDeclaration(target.value()), targetModule(target.value()),
                    Optional.empty(), singleLambda(target.value()),
                    targetParameters(target.value()), targetCaptures(target.value()),
                    target.value(), arguments.values(), Optional.of(context.site(expression)));
            ArrayList<CallableCallReference> calls = new ArrayList<>();
            calls.addAll(target.calls());
            calls.addAll(arguments.calls());
            calls.add(call);
            ArrayList<EagerEffectWitness> effects = new ArrayList<>();
            effects.addAll(target.effects());
            effects.addAll(arguments.effects());
            effects.addAll(callEffects(call));
            return new Eval(
                    FormulaAlternatives.singleton(callResult(expression, id)), arguments.state(),
                    concat(target.writes(), arguments.writes()), calls, effects);
        }

        private Eval directCall(
                TypedExpression expression,
                Map<DeclarationId, FormulaAlternatives> state,
                CallableCallReference.Kind kind) {
            TypedLink link = expression.link().orElseThrow(() ->
                    failure("direct call has no typed link", expression.span()));
            DeclarationId declaration = link.declarationId().orElseThrow(() ->
                    failure("direct call has no target declaration", expression.span()));
            Sequence arguments = evaluateSequential(expression.children(), state);
            FunctionType function = context.functionType(declaration, expression.span());
            recordMutableArgumentOwnershipRequirements(
                    expression.children(), arguments.values(), function);
            FormulaAlternatives target = directTarget(
                    declaration, function, link, arguments.state(), expression.span());
            SummaryCallId id = nextCall();
            CallableCallReference call = new CallableCallReference(
                    id, kind, expression.span(), link.referenceId(), Optional.of(declaration),
                    link.moduleId().isPresent()
                            ? link.moduleId() : Optional.of(context.declarationModule(declaration, expression.span())),
                    link.exportId(), singleLambda(target), targetParameters(target),
                    targetCaptures(target), target, arguments.values(),
                    Optional.of(context.site(expression)));
            ArrayList<CallableCallReference> calls = new ArrayList<>(arguments.calls());
            calls.add(call);
            ArrayList<EagerEffectWitness> effects = new ArrayList<>(arguments.effects());
            effects.addAll(callEffects(call));
            return new Eval(FormulaAlternatives.singleton(callResult(expression, id)),
                    arguments.state(), arguments.writes(), calls, effects);
        }

        private Eval member(
                TypedExpression expression,
                Map<DeclarationId, FormulaAlternatives> state) {
            Eval receiver = evaluate(expression.children().getFirst(), state);
            if (expression.tupleIndex().isPresent()) {
                ProjectionPath route = ProjectionPath.tupleMember(
                        expression.tupleIndex().orElseThrow().intValueExact());
                FormulaAlternatives selected = selectedOrOpaque(
                        receiver.value(), route, expression.type(), expression.span());
                return new Eval(selected, receiver.state(), receiver.writes(),
                        receiver.calls(), receiver.effects());
            }
            return new Eval(scalar(expression.type()).value(), receiver.state(), receiver.writes(),
                    receiver.calls(), receiver.effects());
        }

        private Eval index(
                TypedExpression expression,
                Map<DeclarationId, FormulaAlternatives> state) {
            Eval receiver = evaluate(expression.children().getFirst(), state);
            Eval index = evaluate(expression.children().get(1), receiver.state());
            ProjectionPath route = indexRoute(expression.children().get(1), receiver.value().rootType());
            FormulaAlternatives selected;
            if (route.isRoot()) {
                selected = FormulaAlternatives.singleton(
                        ValueFormula.Scalar.literal(expression.type(), "index-result"));
            } else {
                selected = selectedOrOpaque(receiver.value(), route, expression.type(), expression.span());
            }
            return new Eval(selected, index.state(),
                    concat(receiver.writes(), index.writes()),
                    concat(receiver.calls(), index.calls()),
                    concat(receiver.effects(), index.effects()));
        }

        private Eval array(
                TypedExpression expression,
                Map<DeclarationId, FormulaAlternatives> state) {
            Sequence elements = evaluateSequential(expression.children(), state);
            if (!(expression.type().withoutQualifiers() instanceof ArrayType arrayType)) {
                throw failure("array literal has no Array type", expression.span());
            }
            FreshAllocationSite site = new FreshAllocationSite(
                    lambda.id(), expression.span(), nextAllocationOrdinal++);
            ArrayList<ValueFormula> formulas = new ArrayList<>();
            formulas.add(new ValueFormula.FreshAllocation(
                    site, arrayType, ProjectionPath.root()));
            for (int index = 0; index < elements.values().size(); index++) {
                ProjectionPath prefix = ProjectionPath.arrayElement(index);
                for (ValueFormula formula : elements.values().get(index).formulas()) {
                    formulas.add(formula.prefixedBy(prefix));
                }
            }
            context.limits.requireFormulaAlternatives(new LinkedHashSet<>(formulas).size());
            return new Eval(new FormulaAlternatives(expression.type(), formulas), elements.state(),
                    elements.writes(), elements.calls(), elements.effects());
        }

        private Eval tuple(
                TypedExpression expression,
                Map<DeclarationId, FormulaAlternatives> state) {
            if (!(expression.type().withoutQualifiers() instanceof TupleType)) {
                throw failure("tuple literal has no Tuple type", expression.span());
            }
            Sequence elements = evaluateSequential(expression.children(), state);
            ArrayList<ValueFormula> formulas = new ArrayList<>();
            formulas.add(new ValueFormula.Scalar(expression.type(), ProjectionPath.root()));
            for (int index = 0; index < elements.values().size(); index++) {
                ProjectionPath prefix = ProjectionPath.tupleMember(index);
                for (ValueFormula formula : elements.values().get(index).formulas()) {
                    formulas.add(formula.prefixedBy(prefix));
                }
            }
            context.limits.requireFormulaAlternatives(new LinkedHashSet<>(formulas).size());
            return new Eval(new FormulaAlternatives(expression.type(), formulas), elements.state(),
                    elements.writes(), elements.calls(), elements.effects());
        }

        private Eval operator(
                TypedExpression expression,
                Map<DeclarationId, FormulaAlternatives> state) {
            if (expression.kind() == TypedExpressionKind.SHORT_CIRCUIT
                    && expression.children().size() > 1) {
                Eval first = evaluate(expression.children().getFirst(), state);
                Map<DeclarationId, FormulaAlternatives> continuing = first.state();
                Map<DeclarationId, FormulaAlternatives> resultState = continuing;
                ArrayList<CapturedCellWrite> writes = new ArrayList<>(first.writes());
                ArrayList<CallableCallReference> calls = new ArrayList<>(first.calls());
                ArrayList<EagerEffectWitness> effects = new ArrayList<>(first.effects());
                for (TypedExpression child : expression.children().subList(1, expression.children().size())) {
                    Eval next = evaluate(child, continuing);
                    writes.addAll(joinBranchWrites(
                            next.writes(), List.of(), resultState));
                    calls.addAll(next.calls());
                    effects.addAll(next.effects());
                    continuing = next.state();
                    resultState = joinStates(resultState, continuing);
                }
                return new Eval(scalar(expression.type()).value(), resultState, writes, calls, effects);
            }
            Sequence children = evaluateSequential(expression.children(), state);
            return new Eval(scalar(expression.type()).value(), children.state(),
                    children.writes(), children.calls(), children.effects());
        }

        private Eval unaryValue(
                TypedExpression expression,
                Map<DeclarationId, FormulaAlternatives> state) {
            Eval child = evaluate(expression.children().getFirst(), state);
            FormulaAlternatives value;
            if (expression.kind() == TypedExpressionKind.CONVERSION
                    && !child.value().rootType().withoutQualifiers()
                    .equals(expression.type().withoutQualifiers())) {
                List<ValueFormula> nils = child.value().formulas().stream()
                        .filter(ValueFormula.Scalar.class::isInstance)
                        .map(ValueFormula.Scalar.class::cast)
                        .filter(ValueFormula.Scalar::isNil)
                        .map(nil -> ValueFormula.Scalar.nil(
                                expression.type(),
                                nil.nilSourceSite().orElseThrow(),
                                nil.nilSourceSpan().orElseThrow()))
                        .map(ValueFormula.class::cast)
                        .toList();
                // Numeric/text conversions do not carry aggregate identity.
                // Contextual nil conversion retains its exact source site.
                value = nils.isEmpty()
                        ? FormulaAlternatives.singleton(
                        ValueFormula.Scalar.literal(expression.type(), "conversion"))
                        : new FormulaAlternatives(expression.type(), nils);
            } else {
                value = child.value().asType(expression.type());
            }
            return new Eval(value, child.state(), child.writes(),
                    child.calls(), child.effects());
        }

        private Sequence evaluateSequential(
                List<TypedExpression> expressions,
                Map<DeclarationId, FormulaAlternatives> state) {
            Map<DeclarationId, FormulaAlternatives> current = copyState(state);
            ArrayList<FormulaAlternatives> values = new ArrayList<>();
            ArrayList<CapturedCellWrite> writes = new ArrayList<>();
            ArrayList<CallableCallReference> calls = new ArrayList<>();
            ArrayList<EagerEffectWitness> effects = new ArrayList<>();
            for (TypedExpression expression : expressions) {
                Eval evaluated = evaluate(expression, current);
                current = evaluated.state();
                values.add(evaluated.value());
                writes.addAll(evaluated.writes());
                calls.addAll(evaluated.calls());
                effects.addAll(evaluated.effects());
            }
            return new Sequence(values, current, writes, calls, effects);
        }

        private Eval value(
                TypedExpression expression,
                Map<DeclarationId, FormulaAlternatives> state,
                FormulaAlternatives value) {
            return value(expression, state, value, List.of(), List.of());
        }

        private Eval value(
                TypedExpression expression,
                Map<DeclarationId, FormulaAlternatives> state,
                FormulaAlternatives value,
                List<CapturedCellWrite> writes,
                List<EagerEffectWitness> effects) {
            return new Eval(value.asType(expression.type()), copyState(state), writes,
                    List.of(), effects);
        }

        private Eval scalar(LyraType type) {
            return new Eval(FormulaAlternatives.singleton(new ValueFormula.Scalar(type)),
                    Map.of(), List.of(), List.of(), List.of());
        }

        private FormulaAlternatives declarationFormula(
                DeclarationId declaration,
                LyraType type,
                Map<DeclarationId, FormulaAlternatives> state,
                SourceSpan span) {
            FormulaAlternatives current = state.get(declaration);
            if (current != null) {
                return current.asType(type);
            }
            List<LambdaId> targets = context.callableLambdas(declaration);
            if (!context.computedCallableDeclarations.contains(declaration)
                    && !targets.isEmpty()) {
                ArrayList<ValueFormula> formulas = new ArrayList<>();
                for (LambdaId target : targets) {
                    TypedLambda function = context.lambda(target, span);
                    formulas.add(new ValueFormula.Lambda(
                            target, function.signature().asFunctionType(), ProjectionPath.root(),
                            captureEnvironment(function, state, span)));
                }
                return new FormulaAlternatives(type, formulas);
            }
            return FormulaAlternatives.singleton(new ValueFormula.Declaration(
                    declaration, Optional.of(context.declarationModule(declaration, span)),
                    ProjectionPath.root(), ProjectionPath.root(), type));
        }

        private FormulaAlternatives directTarget(
                DeclarationId declaration,
                FunctionType function,
                TypedLink link,
                Map<DeclarationId, FormulaAlternatives> state,
                SourceSpan span) {
            FormulaAlternatives current = state.get(declaration);
            if (current != null) {
                return current.asType(function);
            }
            List<LambdaId> targets = context.callableLambdas(declaration);
            if (!context.computedCallableDeclarations.contains(declaration)
                    && !targets.isEmpty()) {
                ArrayList<ValueFormula> formulas = new ArrayList<>();
                for (LambdaId target : targets) {
                    formulas.add(new ValueFormula.Lambda(
                            target, function, ProjectionPath.root(), captureEnvironment(
                                    context.lambda(target, span), state, span)));
                }
                return new FormulaAlternatives(function, formulas);
            }
            return FormulaAlternatives.singleton(new ValueFormula.Declaration(
                    declaration,
                    link.moduleId(), ProjectionPath.root(), ProjectionPath.root(), function));
        }

        private ValueFormula.CallResult callResult(
                TypedExpression expression,
                SummaryCallId id) {
            return new ValueFormula.CallResult(id, expression.type(), ProjectionPath.root());
        }

        private SummaryCallId nextCall() {
            return new SummaryCallId(lambda.id(), nextEventOrdinal++);
        }

        private Optional<EagerEffectWitness> effect(
                TypedExpression expression,
                EagerEffectWitness.Kind kind,
                Optional<DeclarationId> declaration,
                Optional<io.mindspice.lyra.compiler.identity.ReferenceId> reference,
                Optional<LambdaId> targetLambda,
                ModuleId targetModule) {
            io.mindspice.lyra.compiler.identity.FlowSiteId site = context.site(expression);
            return Optional.of(new EagerEffectWitness(
                    lambda.moduleId(), targetModule, kind, expression.span(), declaration,
                    reference, targetLambda, List.of(expression.span()), List.of(), false,
                    Optional.of(site), List.of(site)));
        }

        private EagerEffectWitness valueRead(
                TypedExpression expression,
                TypedLink link,
                DeclarationId declaration) {
            ModuleId target = link.moduleId().orElseGet(
                    () -> context.declarationModule(declaration, expression.span()));
            return effect(expression, EagerEffectWitness.Kind.VALUE_READ,
                    Optional.of(declaration), link.referenceId(), Optional.empty(), target).orElseThrow();
        }

        private List<EagerEffectWitness> callEffects(CallableCallReference call) {
            ArrayList<EagerEffectWitness> result = new ArrayList<>();
            EagerEffectWitness.Kind kind = switch (call.kind()) {
                case DIRECT -> EagerEffectWitness.Kind.DIRECT_CALL;
                case NAMESPACE -> EagerEffectWitness.Kind.NAMESPACE_CALL;
                case CALLABLE -> EagerEffectWitness.Kind.CALLABLE_CALL;
                case PARAMETER -> EagerEffectWitness.Kind.PARAMETER_CALL;
                case CAPTURE -> EagerEffectWitness.Kind.CAPTURE_CALL;
            };
            if (call.targetModule().isPresent()
                    && !call.targetModule().orElseThrow().equals(lambda.moduleId())) {
                io.mindspice.lyra.compiler.identity.FlowSiteId site =
                        call.siteId().orElseThrow();
                result.add(new EagerEffectWitness(
                        lambda.moduleId(), call.targetModule().orElseThrow(), kind, call.span(),
                        call.targetDeclaration(), call.referenceId(), call.targetLambda(),
                        List.of(call.span()), List.of(), false,
                        Optional.of(site), List.of(site)));
            }
            for (ValueFormula formula : call.target().formulas()) {
                if (formula instanceof ValueFormula.Lambda target) {
                    ModuleId module = context.lambda(target.lambdaId(), call.span()).moduleId();
                    if (!module.equals(lambda.moduleId())) {
                        io.mindspice.lyra.compiler.identity.FlowSiteId site =
                                call.siteId().orElseThrow();
                        result.add(new EagerEffectWitness(
                                lambda.moduleId(), module, kind, call.span(), call.targetDeclaration(),
                                call.referenceId(), Optional.of(target.lambdaId()),
                                List.of(call.span()), List.of(), false,
                                Optional.of(site), List.of(site)));
                    }
                } else if (formula instanceof ValueFormula.Declaration declaration) {
                    ModuleId module = declaration.moduleId().orElseGet(
                            () -> context.declarationModule(declaration.declarationId(), call.span()));
                    if (!module.equals(lambda.moduleId())) {
                        io.mindspice.lyra.compiler.identity.FlowSiteId site =
                                call.siteId().orElseThrow();
                        result.add(new EagerEffectWitness(
                                lambda.moduleId(), module, kind, call.span(),
                                Optional.of(declaration.declarationId()), call.referenceId(),
                                Optional.empty(), List.of(call.span()), List.of(), false,
                                Optional.of(site), List.of(site)));
                    }
                }
            }
            return result;
        }

        private ProjectionPath indexRoute(
                TypedExpression index,
                LyraType receiverType) {
            if (!(receiverType.withoutQualifiers() instanceof ArrayType)) {
                return ProjectionPath.root();
            }
            if (index.literal().orElse(null)
                    instanceof io.mindspice.lyra.compiler.semantic.TypedLiteralValue.IntegerValue integer) {
                BigInteger value = integer.exactValue().integerValue();
                if (value.signum() >= 0 && value.bitLength() <= 31) {
                    return ProjectionPath.arrayElement(value.intValue());
                }
            }
            return ProjectionPath.unknownArrayElement();
        }

        private FormulaAlternatives selectedOrOpaque(
                FormulaAlternatives receiver,
                ProjectionPath route,
                LyraType type,
                SourceSpan span) {
            FormulaAlternatives selected = receiver.select(route).asType(type);
            return selected.isEmpty()
                    ? FormulaAlternatives.singleton(new ValueFormula.Opaque(type,
                    "no routed value fact at " + span))
                    : selected;
        }

        private TargetPath targetPath(TypedExpression expression) {
            if (expression.kind() == TypedExpressionKind.REFERENCE) {
                DeclarationId declaration = expression.link()
                        .flatMap(TypedLink::declarationId)
                        .orElseThrow(() -> failure("write target has no declaration", expression.span()));
                Optional<CaptureId> capture = expression.captureIds().stream().findFirst();
                return new TargetPath(declaration, capture, ProjectionPath.root());
            }
            if (expression.kind() == TypedExpressionKind.INDEX_ACCESS
                    && expression.children().size() == 2) {
                TargetPath parent = targetPath(expression.children().getFirst());
                ProjectionPath step = indexRoute(
                        expression.children().get(1), expression.children().getFirst().type());
                return new TargetPath(parent.declaration(), parent.capture(), parent.route().compose(step));
            }
            if (expression.kind() == TypedExpressionKind.MEMBER_ACCESS
                    && expression.tupleIndex().isPresent()) {
                TargetPath parent = targetPath(expression.children().getFirst());
                return new TargetPath(parent.declaration(), parent.capture(),
                        parent.route().compose(ProjectionPath.tupleMember(
                                expression.tupleIndex().orElseThrow().intValueExact())));
            }
            throw failure("write target is not a routed binding", expression.span());
        }

        private void recordMutableArgumentOwnershipRequirements(
                List<TypedExpression> arguments,
                List<FormulaAlternatives> values,
                FunctionType function) {
            int count = Math.min(
                    Math.min(arguments.size(), values.size()), function.arity());
            for (int index = 0; index < count; index++) {
                if (!function.parameterType(index).isMutable()
                        || values.get(index).isEmpty()) {
                    continue;
                }
                TypedExpression argument = arguments.get(index);
                ownershipRequirements.add(new OwnershipRequirement(
                        nextEventOrdinal++,
                        OwnershipRequirement.Kind.MUTABLE_ARGUMENT,
                        argument.span(), context.site(argument), values.get(index)));
            }
        }

        private void recordMutationOwnershipRequirement(
                TypedExpression target,
                TargetPath path,
                FormulaAlternatives current) {
            if (path.route().isRoot()) {
                return;
            }
            ProjectionStep last = path.route().steps().getLast();
            if (!(last instanceof ProjectionStep.ArrayElement)
                    && !(last instanceof ProjectionStep.UnknownArrayElement)) {
                return;
            }
            ProjectionPath container = ProjectionPath.of(
                    path.route().steps().subList(0, path.route().depth() - 1));
            FormulaAlternatives protectedValue;
            try {
                protectedValue = current.select(container);
            } catch (IllegalArgumentException incompatibleRoute) {
                throw failure(
                        "mutation ownership route is incompatible with its symbolic value",
                        target.span());
            }
            if (!protectedValue.isEmpty()) {
                ownershipRequirements.add(new OwnershipRequirement(
                        nextEventOrdinal++,
                        OwnershipRequirement.Kind.AGGREGATE_MUTATION,
                        target.span(), context.site(target), protectedValue));
            }
        }

        private List<CapturedCellWrite> aliasOriginWrites(
                FormulaAlternatives current,
                ProjectionPath targetRoute,
                FormulaAlternatives replacement,
                int sequence,
                SourceSpan span) {
            FormulaAlternatives selected = current.select(targetRoute);
            ArrayList<CapturedCellWrite> writes = new ArrayList<>();
            for (ValueFormula formula : selected.formulas()) {
                if (!formula.resultRoute().isRoot()) {
                    continue;
                }
                if (formula instanceof ValueFormula.Parameter parameter) {
                    ProjectionPath route = parameter.parameterRoute();
                    writes.add(CapturedCellWrite.parameter(
                            sequence, parameter.parameterIndex(), parameter.declarationId(),
                            writeKind(route), route, replacement, span));
                } else if (formula instanceof ValueFormula.Capture capture) {
                    ProjectionPath route = capture.captureRoute();
                    writes.add(capture.sharedCellId().isPresent()
                            ? CapturedCellWrite.capture(
                            sequence, capture.captureId(), capture.declarationId(),
                            capture.sharedCellId().orElseThrow(), writeKind(route), route,
                            replacement, span)
                            : CapturedCellWrite.captureAggregate(
                            sequence, capture.captureId(), capture.declarationId(),
                            writeKind(route), route, replacement, span));
                }
            }
            return List.copyOf(new LinkedHashSet<>(writes));
        }

        private boolean parameterTarget(FormulaAlternatives target) {
            return !target.formulas().isEmpty()
                    && target.formulas().stream().allMatch(ValueFormula.Parameter.class::isInstance);
        }

        private Optional<DeclarationId> singleDeclaration(FormulaAlternatives target) {
            TreeSet<DeclarationId> values = new TreeSet<>();
            for (ValueFormula formula : target.formulas()) {
                if (formula instanceof ValueFormula.Declaration declaration
                        && formula.resultRoute().isRoot()) {
                    values.add(declaration.declarationId());
                }
            }
            return values.size() == 1 ? Optional.of(values.first()) : Optional.empty();
        }

        private Optional<LambdaId> singleLambda(FormulaAlternatives target) {
            TreeSet<LambdaId> values = new TreeSet<>();
            for (ValueFormula formula : target.formulas()) {
                if (formula instanceof ValueFormula.Lambda lambda
                        && formula.resultRoute().isRoot()) {
                    values.add(lambda.lambdaId());
                }
            }
            return values.size() == 1 ? Optional.of(values.first()) : Optional.empty();
        }

        private List<CaptureId> targetCaptures(FormulaAlternatives target) {
            TreeSet<CaptureId> values = new TreeSet<>();
            for (ValueFormula formula : target.formulas()) {
                if (formula instanceof ValueFormula.Capture capture
                        && formula.resultRoute().isRoot()) {
                    values.add(capture.captureId());
                }
            }
            return List.copyOf(values);
        }

        private boolean captureTarget(FormulaAlternatives target) {
            return !target.formulas().isEmpty()
                    && target.formulas().stream().allMatch(ValueFormula.Capture.class::isInstance);
        }

        private List<Integer> targetParameters(FormulaAlternatives target) {
            TreeSet<Integer> values = new TreeSet<>();
            for (ValueFormula formula : target.formulas()) {
                if (formula instanceof ValueFormula.Parameter parameter
                        && formula.resultRoute().isRoot()) {
                    values.add(parameter.parameterIndex());
                }
            }
            return List.copyOf(values);
        }

        private Optional<ModuleId> targetModule(FormulaAlternatives target) {
            TreeSet<ModuleId> modules = new TreeSet<>();
            for (ValueFormula formula : target.formulas()) {
                if (formula instanceof ValueFormula.Lambda targetLambda) {
                    modules.add(context.lambda(targetLambda.lambdaId(), lambda.span()).moduleId());
                } else if (formula instanceof ValueFormula.Declaration declaration
                        && declaration.moduleId().isPresent()) {
                    modules.add(declaration.moduleId().orElseThrow());
                }
            }
            return modules.size() == 1 ? Optional.of(modules.first()) : Optional.empty();
        }

        private boolean isCallableType(LyraType type) {
            return type.withoutQualifiers() instanceof FunctionType;
        }

        private void validateFormulaPaths(FormulaAlternatives formulas) {
            for (ValueFormula formula : formulas.formulas()) {
                context.limits.requireProjectionDepth(formula.resultRoute().depth());
                if (formula instanceof ValueFormula.Parameter parameter) {
                    context.limits.requireProjectionDepth(parameter.parameterRoute().depth());
                } else if (formula instanceof ValueFormula.Capture capture) {
                    context.limits.requireProjectionDepth(capture.captureRoute().depth());
                } else if (formula instanceof ValueFormula.Declaration declaration) {
                    context.limits.requireProjectionDepth(declaration.declarationRoute().depth());
                } else if (formula instanceof ValueFormula.CallResult call) {
                    context.limits.requireProjectionDepth(call.callRoute().depth());
                } else if (formula instanceof ValueFormula.Lambda lambda) {
                    for (FormulaAlternatives captured : lambda.captures().values()) {
                        validateFormulaPaths(captured);
                    }
                }
            }
        }

        private FormulaAlternatives ensureValue(
                FormulaAlternatives value,
                SourceSpan span,
                String subject) {
            if (value.isEmpty()) {
                throw failure(subject + " has no symbolic value alternatives", span);
            }
            return value;
        }

        private Map<DeclarationId, FormulaAlternatives> copyState(
                Map<DeclarationId, FormulaAlternatives> state) {
            return new TreeMap<>(state);
        }

        private Map<DeclarationId, FormulaAlternatives> joinStates(
                Map<DeclarationId, FormulaAlternatives> left,
                Map<DeclarationId, FormulaAlternatives> right) {
            TreeMap<DeclarationId, FormulaAlternatives> result = new TreeMap<>(left);
            right.forEach((declaration, value) -> {
                FormulaAlternatives current = result.get(declaration);
                result.put(declaration, current == null ? value : current.join(value));
            });
            return result;
        }

        private static <T> List<T> concat(List<T> left, List<T> right) {
            ArrayList<T> result = new ArrayList<>(left.size() + right.size());
            result.addAll(left);
            result.addAll(right);
            return List.copyOf(result);
        }

        private static SummaryFailureException failure(String message, SourceSpan span) {
            return new SummaryFailureException(message, span);
        }

        private List<CapturedCellWrite> joinBranchWrites(
                List<CapturedCellWrite> left,
                List<CapturedCellWrite> right,
                Map<DeclarationId, FormulaAlternatives> baseline) {
            TreeMap<String, List<CapturedCellWrite>> leftByTarget = writesByTarget(left);
            TreeMap<String, List<CapturedCellWrite>> rightByTarget = writesByTarget(right);
            TreeSet<String> targets = new TreeSet<>(leftByTarget.keySet());
            targets.addAll(rightByTarget.keySet());
            ArrayList<CapturedCellWrite> result = new ArrayList<>();
            for (String target : targets) {
                List<CapturedCellWrite> leftWrites = leftByTarget.getOrDefault(
                        target, List.of());
                List<CapturedCellWrite> rightWrites = rightByTarget.getOrDefault(
                        target, List.of());
                CapturedCellWrite representative = !leftWrites.isEmpty()
                        ? leftWrites.getLast() : rightWrites.getLast();
                FormulaAlternatives value;
                if (!leftWrites.isEmpty() && !rightWrites.isEmpty()) {
                    value = leftWrites.getLast().value().join(
                            rightWrites.getLast().value());
                } else {
                    FormulaAlternatives original = writeTargetValue(
                            representative, baseline);
                    value = representative.value().join(original);
                }
                result.add(withWriteValue(representative, value));
            }
            result.sort(CapturedCellWrite::compareTo);
            return List.copyOf(result);
        }

        private TreeMap<String, List<CapturedCellWrite>> writesByTarget(
                List<CapturedCellWrite> writes) {
            TreeMap<String, List<CapturedCellWrite>> result = new TreeMap<>();
            for (CapturedCellWrite write : writes) {
                result.computeIfAbsent(writeTargetKey(write), ignored -> new ArrayList<>())
                        .add(write);
            }
            result.values().forEach(values -> values.sort(
                    CapturedCellWrite::compareTo));
            return result;
        }

        private String writeTargetKey(CapturedCellWrite write) {
            return write.captureId().map(value -> "capture/" + value)
                    .orElseGet(() -> "parameter/" + write.parameter())
                    + "/" + write.declarationId()
                    + "/" + write.sharedCellId().map(Object::toString).orElse("-")
                    + "/" + write.kind() + "/" + write.route();
        }

        private FormulaAlternatives writeTargetValue(
                CapturedCellWrite write,
                Map<DeclarationId, FormulaAlternatives> state) {
            FormulaAlternatives root = state.get(write.declarationId());
            if (root == null) {
                return write.value();
            }
            try {
                return root.select(write.route()).asType(
                        write.value().rootType());
            } catch (IllegalArgumentException incompatibleRoute) {
                return write.value();
            }
        }

        private CapturedCellWrite withWriteValue(
                CapturedCellWrite write,
                FormulaAlternatives value) {
            return new CapturedCellWrite(
                    write.sequence(), write.captureId(), write.parameterIndex(),
                    write.declarationId(), write.sharedCellId(), write.kind(),
                    write.route(), value, write.span());
        }

        private static CapturedCellWrite.Kind writeKind(ProjectionPath route) {
            return route.containsWildcard()
                    ? CapturedCellWrite.Kind.UNKNOWN_ROUTE
                    : route.isRoot()
                    ? CapturedCellWrite.Kind.WHOLE
                    : CapturedCellWrite.Kind.EXACT_ROUTE;
        }

        private record TargetPath(
                DeclarationId declaration,
                Optional<CaptureId> capture,
                ProjectionPath route) {
            private Optional<CapturedCellWrite> write(
                    Context context,
                    TypedExpression expression,
                    FormulaAlternatives replacement,
                    int sequence) {
                if (capture.isPresent()) {
                    ResolvedCapture resolved = context.capture(capture.orElseThrow(), expression.span());
                    if (resolved.mode() != CaptureMode.SHARED_MUTABLE_CELL
                            || resolved.sharedCellId().isEmpty()) {
                        throw new SummaryFailureException(
                                "typed write targets an immutable capture", expression.span());
                    }
                    CapturedCellWrite.Kind kind = writeKind(route);
                    return Optional.of(CapturedCellWrite.capture(
                            sequence, capture.orElseThrow(),
                            resolved.declarationId(), resolved.sharedCellId().orElseThrow(), kind,
                            route, replacement, expression.span()));
                }
                ResolvedDeclaration target = context.resolvedDeclarations.get(declaration);
                boolean moduleRoot = target != null
                        && context.graph.resolvedGraph().module(target.moduleId())
                        .map(module -> module.rootScope().equals(target.scopeId()))
                        .orElse(false);
                if (target != null && (target.externalBinding().isPresent()
                        || context.graph.resolvedGraph().isSessionGraph() && moduleRoot)) {
                    return Optional.of(CapturedCellWrite.declaration(sequence, declaration,
                            writeKind(route), route, replacement, expression.span(),
                            context.graph.flowSiteId(expression)));
                }
                // Parameter writes are added by the builder after it has the
                // parameter index map; this marker is intentionally absent here.
                return Optional.empty();
            }
        }

        private record Eval(
                FormulaAlternatives value,
                Map<DeclarationId, FormulaAlternatives> state,
                List<CapturedCellWrite> writes,
                List<CallableCallReference> calls,
                List<EagerEffectWitness> effects) {
            private Eval {
                Objects.requireNonNull(value, "value");
                Objects.requireNonNull(state, "state");
                writes = List.copyOf(writes);
                calls = List.copyOf(calls);
                effects = List.copyOf(effects);
            }
        }

        private record Sequence(
                List<FormulaAlternatives> values,
                Map<DeclarationId, FormulaAlternatives> state,
                List<CapturedCellWrite> writes,
                List<CallableCallReference> calls,
                List<EagerEffectWitness> effects) {
            private Sequence {
                values = List.copyOf(values);
                writes = List.copyOf(writes);
                calls = List.copyOf(calls);
                effects = List.copyOf(effects);
            }
        }
    }

    private static final class SummaryFailureException extends RuntimeException {
        private final SourceSpan span;

        private SummaryFailureException(String message, SourceSpan span) {
            super(Objects.requireNonNull(message, "message"));
            this.span = Objects.requireNonNull(span, "span");
        }

        private SourceSpan span() {
            return span;
        }
    }
}
