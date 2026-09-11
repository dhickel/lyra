package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.api.SessionFlowCertificate;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.semantic.flow.AggregateIdentityFact;
import io.mindspice.lyra.compiler.semantic.flow.CallableCallReference;
import io.mindspice.lyra.compiler.semantic.flow.CallableFlow;
import io.mindspice.lyra.compiler.semantic.flow.CallableSummary;
import io.mindspice.lyra.compiler.semantic.flow.EagerEffectFact;
import io.mindspice.lyra.compiler.semantic.flow.EagerEffectWitness;
import io.mindspice.lyra.compiler.semantic.flow.FormulaAlternatives;
import io.mindspice.lyra.compiler.semantic.flow.NilProvenance;
import io.mindspice.lyra.compiler.semantic.flow.NormalizedExpression;
import io.mindspice.lyra.compiler.semantic.flow.OwnershipWitness;
import io.mindspice.lyra.compiler.semantic.flow.ProjectionPath;
import io.mindspice.lyra.compiler.semantic.flow.SemanticFlowEvent;
import io.mindspice.lyra.compiler.semantic.flow.SemanticFlowFacts;
import io.mindspice.lyra.compiler.semantic.flow.SummaryCallId;
import io.mindspice.lyra.compiler.semantic.flow.TypedExpressionNormalizer;
import io.mindspice.lyra.compiler.semantic.flow.ValueAlternative;
import io.mindspice.lyra.compiler.semantic.flow.ValueAlternatives;
import io.mindspice.lyra.compiler.semantic.flow.ValueFormula;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.LyraType;
import io.mindspice.lyra.compiler.types.TupleType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Schema and topology audit for producer-certified semantic-flow facts.
 *
 * <p>The exact expected artifact is owned by {@link SemanticFlowAnalyzer} and
 * is compared before this audit runs. This class therefore checks explicit
 * site/ID/route ownership and complete structural boundary coverage only. It
 * never rebuilds value flow, call transfer, captures, allocations, effects, or
 * evaluator state from source.</p>
 */
final class SemanticFlowFactValidator {
    private SemanticFlowFactValidator() {
    }

    static void validate(TypedSemanticGraph graph, SemanticFlowFacts facts) {
        new Validator(
                Objects.requireNonNull(graph, "graph"),
                Objects.requireNonNull(facts, "facts")).run();
    }

    private static final class Validator {
        private final TypedSemanticGraph graph;
        private final TypedSemanticCore core;
        private final SemanticFlowFacts facts;
        private final Map<LambdaId, TypedLambda> lambdas = new TreeMap<>();
        private final Map<LambdaId, CallableSummary> producerSummaries = new TreeMap<>();
        private final Map<CaptureId, ResolvedCapture> captures = new TreeMap<>();
        private final Map<DeclarationId, ResolvedDeclaration> declarations = new TreeMap<>();
        private final Set<ModuleId> modules = new TreeSet<>();
        private final Optional<SessionFlowCertificate> sessionCertificate;

        private Validator(TypedSemanticGraph graph, SemanticFlowFacts facts) {
            this.graph = graph;
            this.core = graph.sealingCore();
            this.facts = facts;
            this.sessionCertificate = graph.resolvedGraph().sessionFlowCertificate();
            graph.lambdas().forEach(lambda -> lambdas.put(lambda.id(), lambda));
            graph.resolvedGraph().captures().forEach(capture -> captures.put(capture.id(), capture));
            graph.resolvedGraph().declarations().forEach(
                    declaration -> declarations.put(declaration.id(), declaration));
            graph.resolvedGraph().retainedModules().producers().forEach(record -> {
                record.producerGraph().resolvedGraph().declarations().forEach(declaration ->
                        declarations.putIfAbsent(declaration.id(), declaration));
                record.callableSummaries().summaries().forEach((lambda, summary) ->
                        producerSummaries.putIfAbsent(lambda, summary));
            });
            graph.modules().stream().map(TypedModule::moduleId).forEach(modules::add);
        }

        private void run() {
            validateNormalizedRoots();
            validateSummaries();
            validateEventsAndRootCoverage();
            validateDeclarationValues();
            validateFinalStates();
            validateEffects();
            validateCycles();
        }

        private void validateNormalizedRoots() {
            TreeSet<NormalizedExpression> expected = new TreeSet<>();
            for (TypedModule module : graph.modules()) {
                for (TypedExpression form : module.forms()) {
                    expected.add(TypedExpressionNormalizer.normalize(form));
                }
            }
            require(List.copyOf(expected).equals(facts.normalizedExpressions()),
                    "normalized roots do not cover the typed module roots exactly");
        }

        private void validateSummaries() {
            require(facts.callableSummaries().summaries().keySet().equals(lambdas.keySet()),
                    "callable summaries do not cover every typed lambda exactly");
            for (TypedLambda lambda : lambdas.values()) {
                CallableSummary summary = facts.callableSummaries().summary(lambda.id())
                        .orElseThrow(() -> invalid("missing callable summary for " + lambda.id()));
                require(summary.lambdaId().equals(lambda.id())
                                && summary.moduleId().equals(lambda.moduleId())
                                && summary.span().equals(lambda.span())
                                && summary.scopeId().equals(lambda.scopeId())
                                && summary.signature().equals(lambda.signature()),
                        "callable summary owner metadata changed");
                require(summary.normalizedBody().equals(
                                TypedExpressionNormalizer.normalize(lambda.body())),
                        "callable summary body does not match its typed lambda");
                require(summary.isFixedPoint() && summary.fixedPointIterations() > 0,
                        "published callable summary is not solved");

                require(summary.parameters().size() == lambda.parameterIds().size(),
                        "callable parameter coverage changed");
                for (int index = 0; index < lambda.parameterIds().size(); index++) {
                    CallableSummary.ParameterPlaceholder parameter = summary.parameters().get(index);
                    require(parameter.index() == index
                                    && parameter.declarationId().equals(lambda.parameterIds().get(index))
                                    && graph.contract(parameter.declarationId())
                                    .filter(parameter.contract()::equals).isPresent(),
                            "callable parameter provenance changed");
                }

                ResolvedLambda resolved = graph.resolvedGraph().lambda(lambda.id()).orElseThrow();
                Set<CaptureId> expectedCaptures = resolved.captures().stream()
                        .filter(id -> {
                            ResolvedCapture capture = captures.get(id);
                            return capture != null && !isModuleDeclaration(capture.declarationId());
                        })
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
                Set<CaptureId> actualCaptures = summary.captures().stream()
                        .map(CallableSummary.CapturePlaceholder::captureId)
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
                require(expectedCaptures.equals(actualCaptures),
                        "callable capture placeholder coverage changed");

                Map<SummaryCallId, CallableCallReference> calls = new TreeMap<>();
                for (CallableCallReference call : summary.callReferences()) {
                    require(calls.put(call.id(), call) == null,
                            "callable summary contains a duplicate call identity");
                    require(call.id().ownerLambda().equals(lambda.id()),
                            "callable call belongs to another lambda");
                    requireSite(call.siteId(), call.span(), "callable call");
                    TypedExpression source = graph.expressionsAt(call.span()).stream()
                            .filter(expression -> graph.flowSiteId(expression).equals(call.siteId().orElseThrow()))
                            .findFirst().orElseThrow(() -> invalid("call has no originating typed expression"));
                    boolean loop = source.kind() == TypedExpressionKind.ITER || source.kind() == TypedExpressionKind.WHILE;
                    require(loop == call.repeat().isPresent(), "call repetition metadata changed from source");
                    call.repeat().ifPresent(repeat -> require(repeat.predicate().isPresent()
                            == (source.kind() == TypedExpressionKind.WHILE), "loop predicate mode changed from source"));
                }
                for (CallableCallReference call : summary.callReferences()) {
                    validateFormulaAlternatives(call.target(), summary, calls);
                    call.repeat().flatMap(CallableCallReference.Repeat::predicate)
                            .ifPresent(value -> validateFormulaAlternatives(value, summary, calls));
                    call.repeat().ifPresent(repeat -> repeat.environment().values().forEach(
                            value -> validateFormulaAlternatives(value, summary, calls)));
                    call.arguments().forEach(value ->
                            validateFormulaAlternatives(value, summary, calls));
                }
                Set<FlowSiteId> expectedCallSites = new TreeSet<>();
                collectCallSites(lambda.body(), expectedCallSites);
                Set<FlowSiteId> actualCallSites = summary.callReferences().stream()
                        .map(call -> call.siteId().orElseThrow())
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
                require(expectedCallSites.equals(actualCallSites),
                        "callable call-site coverage changed");

                validateFormulaAlternatives(summary.returnFormula().alternatives(), summary, calls);
                summary.writes().forEach(write ->
                        validateFormulaAlternatives(write.value(), summary, calls));
                summary.ownershipRequirements().forEach(requirement -> {
                    requireSite(
                            Optional.of(requirement.siteId()), requirement.span(),
                            "callable ownership requirement");
                    validateFormulaAlternatives(
                            requirement.value(), summary, calls);
                });
                summary.eagerEffects().forEach(this::validateWitness);
            }
        }

        private void collectCallSites(TypedExpression expression, Set<FlowSiteId> destination) {
            if (expression.kind() == TypedExpressionKind.LAMBDA) {
                return;
            }
            if (isCall(expression)) {
                destination.add(graph.flowSiteId(expression));
            }
            expression.children().forEach(child -> collectCallSites(child, destination));
        }

        private void validateFormulaAlternatives(
                FormulaAlternatives alternatives,
                CallableSummary owner,
                Map<SummaryCallId, CallableCallReference> calls) {
            require(!alternatives.isEmpty(), "published formula alternatives are empty");
            for (ValueFormula formula : alternatives.formulas()) {
                try {
                    LyraType routed = ValueAlternative.typeAt(
                            alternatives.rootType(), formula.resultRoute());
                    require(routed.withoutQualifiers().equals(formula.type().withoutQualifiers()),
                            "formula result route changed type");
                } catch (IllegalArgumentException invalidRoute) {
                    throw invalid("formula result route is incompatible with its root type");
                }
                if (formula instanceof ValueFormula.Parameter parameter) {
                    require(parameter.parameterIndex() < owner.parameters().size()
                                    && owner.parameters().get(parameter.parameterIndex())
                                    .declarationId().equals(parameter.declarationId()),
                            "formula parameter binding changed");
                } else if (formula instanceof ValueFormula.Capture capture) {
                    require(owner.captures().stream().anyMatch(value ->
                                    value.captureId().equals(capture.captureId())
                                            && value.declarationId().equals(capture.declarationId())
                                            && value.sharedCellId().equals(capture.sharedCellId())),
                            "formula capture binding changed");
                } else if (formula instanceof ValueFormula.Declaration declaration) {
                    require(declarations.containsKey(declaration.declarationId()),
                            "formula declaration is foreign");
                } else if (formula instanceof ValueFormula.Lambda lambda) {
                    if (lambdas.containsKey(lambda.lambdaId())) {
                        lambda.captures().forEach((captureId, value) -> {
                            ResolvedCapture capture = captures.get(captureId);
                            require(capture != null
                                            && capture.lambdaId().equals(lambda.lambdaId()),
                                    "formula lambda capture belongs to another lambda");
                            validateFormulaAlternatives(value, owner, calls);
                        });
                    } else {
                        // A retained callable may be produced by a borrowed
                        // transitive application module that is not a node in
                        // this submission graph.  Admit it only from the exact
                        // producer certificate, never from a matching type or
                        // guessed target.
                        CallableSummary producer = producerSummaries.get(lambda.lambdaId());
                        require(producer != null,
                                "formula lambda has no retained producer certificate");
                        require(producer.signature().asFunctionType()
                                        .equals(lambda.functionType()),
                                "retained formula lambda type changed");
                        Set<CaptureId> expected = producer.captures().stream()
                                .map(CallableSummary.CapturePlaceholder::captureId)
                                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
                        require(expected.equals(lambda.captures().keySet()),
                                "retained formula lambda captures changed");
                        lambda.captures().forEach((captureId, value) -> {
                            CallableSummary.CapturePlaceholder capture = producer.captures().stream()
                                    .filter(candidate -> candidate.captureId().equals(captureId))
                                    .findFirst().orElseThrow();
                            require(value.rootType().withoutQualifiers()
                                            .equals(capture.contract().valueType().withoutQualifiers()),
                                    "retained formula lambda capture type changed");
                            validateFormulaAlternatives(value, owner, calls);
                        });
                    }
                } else if (formula instanceof ValueFormula.CallResult result) {
                    require(calls.containsKey(result.callId()),
                            "formula call result names a foreign call");
                    calls.get(result.callId()).repeat().ifPresent(repeat -> require(
                            ValueAlternative.typeAt(repeat.snapshotType(), result.callRoute()).withoutQualifiers()
                                    .equals(result.type().withoutQualifiers()), "loop snapshot projection changed type"));
                } else if (formula instanceof ValueFormula.FreshAllocation fresh) {
                    require(lambdas.containsKey(fresh.allocationSite().ownerLambda()),
                            "fresh allocation belongs to a foreign lambda");
                } else if (formula instanceof ValueFormula.Scalar scalar && scalar.isNil()) {
                    requireSite(scalar.nilSourceSite(), scalar.nilSourceSpan().orElseThrow(),
                            "summary nil source");
                }
            }
        }

        private void validateEventsAndRootCoverage() {
            Set<EventRequirement> expected = new HashSet<>();
            for (TypedModule module : graph.modules()) {
                module.forms().forEach(form -> collectRootRequirements(form, expected));
            }
            Set<EventRequirement> actual = new HashSet<>();
            for (SemanticFlowEvent event : facts.events()) {
                validateEvent(event);
                switch (event.kind()) {
                    case DECLARATION, MUTATION, CALL -> actual.add(new EventRequirement(
                            event.kind(), event.siteId().orElseThrow(), Optional.empty()));
                    case CAPTURE -> actual.add(new EventRequirement(
                            event.kind(), event.siteId().orElseThrow(), event.captureId()));
                    case EFFECT -> {
                    }
                }
            }
            require(actual.equals(expected),
                    "semantic event coverage does not match typed root boundaries exactly");
        }

        private void collectRootRequirements(
                TypedExpression expression,
                Set<EventRequirement> destination) {
            FlowSiteId site = graph.flowSiteId(expression);
            switch (expression.kind()) {
                case DECLARATION -> destination.add(new EventRequirement(
                        SemanticFlowEvent.Kind.DECLARATION, site, Optional.empty()));
                case REBINDING -> destination.add(new EventRequirement(
                        SemanticFlowEvent.Kind.MUTATION, site, Optional.empty()));
                case CALLABLE_CALL, DIRECT_CALL, NAMESPACE_DIRECT_CALL, ITER, WHILE ->
                        destination.add(new EventRequirement(
                                SemanticFlowEvent.Kind.CALL, site, Optional.empty()));
                case LAMBDA -> {
                    LambdaId lambdaId = expression.lambdaId().orElseThrow();
                    TypedLambda lambda = lambdas.get(lambdaId);
                    for (CaptureId captureId : lambda.captures()) {
                        ResolvedCapture capture = captures.get(captureId);
                        if (capture != null && !isModuleDeclaration(capture.declarationId())) {
                            FlowSiteId captureSite = capture.references().stream().findFirst()
                                    .map(graph::flowSiteId)
                                    .orElseGet(() -> graph.flowSiteId(capture.id()));
                            destination.add(new EventRequirement(
                                    SemanticFlowEvent.Kind.CAPTURE,
                                    captureSite,
                                    Optional.of(captureId)));
                        }
                    }
                    return;
                }
                default -> {
                }
            }
            expression.children().forEach(child ->
                    collectRootRequirements(child, destination));
        }

        private void validateEvent(SemanticFlowEvent event) {
            if (!modules.contains(event.moduleId()) && event.kind() == SemanticFlowEvent.Kind.EFFECT) {
                require(event.effects().size() == 1, "retained effect event needs one exact witness");
                var witness = event.effects().getFirst();
                require(sessionCertificate.map(value -> value.certifiesEffect(witness)).orElse(false)
                                || graph.resolvedGraph().retainedModules().producers().stream()
                                .anyMatch(record -> record.producerGraph().semanticFlowFacts().events().contains(event)),
                        "retained effect event has no producer certificate: " + witness);
                var expected = new SemanticFlowEvent(SemanticFlowEvent.Kind.EFFECT,
                        ModuleId.fromSourceId(witness.effectSpan().sourceId()), witness.effectSpan(),
                        event.initializerDeclaration(), Optional.empty(), witness.targetDeclaration(),
                        witness.targetLambda(), witness.referenceId(), Optional.empty(), Optional.empty(),
                        ValueAlternatives.empty(), List.of(), List.of(witness), witness.effectSite(), Optional.empty());
                require(event.equals(expected), "retained effect event changed its producer-qualified shape");
                event.initializerDeclaration().ifPresent(this::requireDeclaration);
                validateWitness(witness);
                return;
            }
            require(modules.contains(event.moduleId()), "event module is foreign");
            requireSite(event.siteId(), event.span(), "semantic event");
            event.initializerDeclaration().ifPresent(this::requireDeclaration);
            event.declarationId().ifPresent(this::requireDeclaration);
            event.targetDeclaration().ifPresent(this::requireDeclaration);
            event.lambdaId().ifPresent(lambda -> require(
                    lambdas.containsKey(lambda)
                            || graph.resolvedGraph().sessionFlowCertificate()
                            .map(value -> value.certifiesLambda(lambda)).orElse(false),
                    "event lambda is foreign and uncertified"));
            event.referenceId().ifPresent(reference -> require(
                    graph.resolvedGraph().reference(reference).isPresent(),
                    "event reference is foreign"));
            event.callId().ifPresent(call -> require(
                    facts.callableSummaries().summary(call.ownerLambda()).stream()
                            .flatMap(summary -> summary.callReferences().stream())
                            .anyMatch(reference -> reference.id().equals(call)),
                    "event summary-call identity is foreign"));
            if (event.kind() == SemanticFlowEvent.Kind.CAPTURE) {
                CaptureId id = event.captureId().orElseThrow(() ->
                        invalid("capture event has no exact capture identity"));
                ResolvedCapture capture = captures.get(id);
                require(capture != null
                                && event.lambdaId().filter(capture.lambdaId()::equals).isPresent()
                                && event.referenceId().equals(capture.references().stream().findFirst())
                                && event.span().equals(capture.span()),
                        "capture event provenance changed");
            } else {
                require(event.captureId().isEmpty(),
                        "non-capture event carries a capture identity");
            }
            try {
                validateValues(event.value());
            } catch (IllegalArgumentException invalidValue) {
                throw invalid("event " + event.kind() + " at " + event.span()
                        + " has invalid value provenance " + event.value() + ": "
                        + invalidValue.getMessage());
            }
            event.effects().forEach(this::validateWitness);
        }

        private void validateDeclarationValues() {
            Set<DeclarationId> expected = graph.modules().stream()
                    .flatMap(module -> module.forms().stream())
                    .filter(expression -> expression.kind() == TypedExpressionKind.DECLARATION)
                    .map(expression -> expression.declarationId().orElseThrow())
                    .collect(java.util.stream.Collectors.toSet());
            require(facts.declarationValues().keySet().containsAll(expected),
                    "declaration value coverage is incomplete");
            facts.declarationValues().forEach((declaration, values) -> {
                requireDeclaration(declaration);
                validateValues(values);
                require(facts.events().stream().anyMatch(event ->
                                event.kind() == SemanticFlowEvent.Kind.DECLARATION
                                        && event.declarationId().filter(declaration::equals).isPresent()
                                        && event.value().equals(values)),
                        "declaration value disagrees with its producer event");
            });
        }

        private void validateValues(ValueAlternatives values) {
            for (ValueAlternative value : values) {
                validateIdentityCoverage(value, value.type(), ProjectionPath.root());
                for (NilProvenance nil : value.nilProvenance()) {
                    requireSite(Optional.of(nil.sourceSite()), nil.sourceSpan(), "nil source");
                    require(ValueAlternative.typeAt(value.type(), nil.route()).isNilable(),
                            "nil provenance route is not nilable");
                }
                value.aggregateIdentities().forEach(this::validateAggregate);
                value.callableFlows().forEach(this::validateCallable);
            }
        }

        private void validateIdentityCoverage(
                ValueAlternative value,
                LyraType type,
                ProjectionPath route) {
            if (value.nilProvenance().stream().anyMatch(nil -> nil.route().equals(route))) {
                return;
            }
            LyraType shape = type.withoutQualifiers();
            if (shape instanceof ArrayType) {
                require(value.aggregateIdentities().stream()
                                .anyMatch(fact -> fact.route().equals(route)),
                        "array route has neither an identity nor route-specific nil provenance: "
                                + route + " in " + value);
            } else if (shape instanceof FunctionType) {
                require(value.callableFlows().stream()
                                .anyMatch(callable -> callable.route().equals(route)),
                        "function route has neither a callable nor route-specific nil provenance: "
                                + route);
            } else if (shape instanceof TupleType tuple) {
                for (int index = 0; index < tuple.arity(); index++) {
                    validateIdentityCoverage(value, tuple.memberType(index),
                            route.compose(ProjectionPath.tupleMember(index)));
                }
            }
        }

        private void validateAggregate(AggregateIdentityFact fact) {
            OwnershipWitness witness = fact.witness();
            boolean inherited = graph.resolvedGraph().sessionFlowCertificate()
                    .map(value -> value.certifiesAggregate(fact)).orElse(false);
            if (inherited) {
                // The producer already validated the allocation site, owner
                // scope, and source provenance.  A later graph may not own
                // those source objects, so do not reinterpret them as local.
                return;
            }
            require(modules.contains(fact.identity().ownerModule()),
                    "aggregate owner module is foreign");
            ResolvedScope scope = graph.resolvedGraph().scopeTree().scope(witness.scopeId())
                    .orElseThrow(() -> invalid("aggregate owner scope is foreign"));
            require(scope.moduleId().equals(witness.ownerModule()),
                    "aggregate owner scope belongs to another module");
            require(witness.sourceSpan().sourceId().equals(
                            witness.ownerModule().sourceId()),
                    "aggregate allocation origin belongs to another module");
            if (fact.identity() instanceof io.mindspice.lyra.compiler.semantic.flow.ArrayIdentity.SessionOrigin session) {
                ResolvedDeclaration declaration = declarations.get(session.originDeclaration());
                boolean certified = declaration != null
                        && declaration.externalBinding().isPresent()
                        && graph.resolvedGraph().sessionFlowCertificate()
                        .map(value -> value.certifiesBinding(
                                declaration.externalBinding().orElseThrow()))
                        .orElse(false);
                require(declaration != null && declaration.externalBinding().isPresent()
                                && (declaration.externalBinding().orElseThrow().supportsSessionStorage()
                                || certified),
                        "session array origin has no certified external declaration");
                require(declaration.moduleId().equals(session.ownerModule())
                                && declaration.scopeId().equals(witness.scopeId())
                                && declaration.span().equals(witness.sourceSpan())
                                && witness.originSite().isEmpty(),
                        "session array witness must identify its exact external contract, not an allocation site");
                require(ValueAlternative.typeAt(declaration.externalBinding().orElseThrow().type(),
                                session.sourceRoute()).withoutQualifiers().equals(session.arrayType()),
                        "session array origin route disagrees with its external contract");
            } else {
                require(witness.originSite().isPresent()
                                && core.ownsFlowSite(
                                witness.originSite().orElseThrow(), witness.sourceSpan()),
                        "aggregate allocation origin has no exact canonical flow site");
            }
            require(graph.modules().stream().anyMatch(module ->
                            module.moduleId().sourceId().equals(witness.useSpan().sourceId())),
                    "aggregate use site belongs to a foreign module");
            require(witness.ownerModule().equals(fact.identity().ownerModule())
                            && witness.originDeclaration().equals(
                            fact.identity().originDeclaration())
                            && witness.originExport().equals(fact.identity().originExport()),
                    "aggregate identity and ownership record disagree");
        }

        private void validateCallable(CallableFlow callable) {
            if (callable.isIntrinsic()) {
                require(callable.creationSite().isEmpty(),
                        "intrinsic callable has a lambda creation site");
                if (!declarations.containsKey(callable.intrinsicDeclarationId().orElseThrow())) {
                    require(graph.resolvedGraph().sessionFlowCertificate()
                                    .map(value -> value.certifiesCallable(callable)).orElse(false),
                            "intrinsic callable is foreign and uncertified");
                } else {
                    requireDeclaration(callable.intrinsicDeclarationId().orElseThrow());
                }
                return;
            }
            LambdaId lambdaId = callable.lambdaId().orElseThrow();
            TypedLambda lambda = lambdas.get(lambdaId);
            if (lambda == null) {
                SessionFlowCertificate certificate = graph.resolvedGraph()
                        .sessionFlowCertificate()
                        .filter(value -> value.certifiesCallableTransfer(callable))
                        .orElseThrow(() -> invalid(
                                "callable lambda is foreign and uncertified"));
                CallableSummary summary = certificate.callableSummaries().summary(lambdaId)
                        .orElseThrow(() -> invalid(
                                "certified callable has no retained summary: " + lambdaId));
                validateCertifiedCallableCaptures(callable, summary);
                return;
            }
            FlowSiteId creation = callable.creationSite().orElseThrow(() ->
                    invalid("callable has no exact creation-site identity"));
            require(core.ownsFlowSite(creation, lambda.span()),
                    "callable creation site does not identify its lambda expression");

            Set<DeclarationId> expectedValues = new TreeSet<>();
            Set<DeclarationId> expectedCells = new TreeSet<>();
            for (CaptureId captureId : lambda.captures()) {
                ResolvedCapture capture = captures.get(captureId);
                if (capture == null || isModuleDeclaration(capture.declarationId())) {
                    continue;
                }
                if (capture.isSharedCell()) {
                    expectedCells.add(capture.sharedCellId().orElseThrow());
                } else {
                    expectedValues.add(capture.declarationId());
                }
            }
            require(callable.capturedValues().keySet().equals(expectedValues)
                            && callable.sharedCellSnapshots().keySet().equals(expectedCells),
                    "callable capture binding coverage changed");
            callable.capturedValues().values().forEach(this::validateValues);
            callable.sharedCellSnapshots().values().forEach(this::validateValues);
        }

        private void validateCertifiedCallableCaptures(
                CallableFlow callable,
                CallableSummary summary) {
            Set<DeclarationId> expectedValues = summary.captures().stream()
                    .filter(value -> !value.isSharedCell())
                    .map(CallableSummary.CapturePlaceholder::declarationId)
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            Set<DeclarationId> expectedCells = summary.captures().stream()
                    .filter(CallableSummary.CapturePlaceholder::isSharedCell)
                    .map(value -> value.cellId().orElseThrow())
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            require(callable.capturedValues().keySet().equals(expectedValues)
                            && callable.sharedCellSnapshots().keySet().equals(expectedCells),
                    "certified callable capture binding coverage changed");
            for (CallableSummary.CapturePlaceholder capture : summary.captures()) {
                ValueAlternatives values = capture.isSharedCell()
                        ? callable.sharedCellSnapshots().get(capture.cellId().orElseThrow())
                        : callable.capturedValues().get(capture.declarationId());
                require(values != null && !values.isEmpty(),
                        "certified callable capture snapshot is empty");
                require(values.alternatives().stream().allMatch(value ->
                                value.type().withoutQualifiers().equals(
                                        capture.type().withoutQualifiers())),
                        "certified callable capture type changed");
                validateValues(values);
            }
        }

        private void validateFinalStates() {
            facts.finalStates().forEach((module, state) -> {
                require(modules.contains(module), "final state names a foreign module");
                state.bindings().forEach((declaration, value) -> {
                    if (declarations.containsKey(declaration)) {
                        Optional<io.mindspice.lyra.compiler.types.BindingContract> expected =
                                graph.contract(declaration)
                                        .or(() -> declarations.get(declaration).contract());
                        require(expected.filter(value.contract()::equals).isPresent(),
                                "final state binding contract changed: " + declaration
                                        + " expected=" + expected.orElse(null)
                                        + " actual=" + value.contract());
                    } else {
                        // A retained closure can update a producer-owned cell
                        // whose declaration was lexically replaced and is not
                        // a declaration of this generation.  The predecessor
                        // certificate must own the exact declaration and
                        // contract; its value is a boundary fact, not a value
                        // that this validation pass may reconstruct.
                        SessionFlowCertificate certificate = graph.resolvedGraph()
                                .sessionFlowCertificate().orElse(null);
                        require(certificate != null
                                        && (certificate.boundaryState().binding(declaration)
                                        .filter(previous -> previous.contract().equals(value.contract()))
                                        .isPresent()
                                        || certificate.certifiesSharedCell(
                                        declaration, value.contract())),
                                "final state binding is foreign and uncertified: " + declaration);
                    }
                    // Final-state alternatives are a retained may-state
                    // overlay. Their source-event provenance is audited above;
                    // boundary normalization may deliberately replace local
                    // aggregate identities with a session-origin contract.
                });
            });
        }

        private void validateEffects() {
            TreeSet<EagerEffectFact> expectedFromEvents = new TreeSet<>();
            for (SemanticFlowEvent event : facts.events()) {
                for (EagerEffectWitness witness : event.effects()) {
                    expectedFromEvents.add(new EagerEffectFact(
                            witness.fromModule(), event.initializerDeclaration(), witness));
                }
            }
            require(expectedFromEvents.equals(new TreeSet<>(facts.eagerEffectFacts())),
                    "eager-effect index does not match producer event coverage");
            facts.eagerEffectFacts().forEach(effect -> {
                require(modules.contains(effect.initializerModule()),
                        "effect initializer module is foreign");
                effect.initializerDeclaration().ifPresent(this::requireDeclaration);
                validateWitness(effect.witness());
                require(facts.events().stream().anyMatch(event ->
                                event.kind() == SemanticFlowEvent.Kind.EFFECT
                                        && event.initializerDeclaration().equals(
                                        effect.initializerDeclaration())
                                        && event.effects().contains(effect.witness())),
                        "effect fact has no exact effect event");
            });
        }

        private void validateWitness(EagerEffectWitness witness) {
            var retainedProducer = graph.resolvedGraph().retainedModules().producers().stream()
                    .map(record -> record.producerGraph()).distinct()
                    .filter(producer -> producer.semanticFlowFacts().eagerEffectFacts().stream()
                            .anyMatch(effect -> effect.witness().equals(witness))).findFirst();
            boolean certificateOwned = sessionCertificate
                    .map(certificate -> certificate.certifiesEffect(witness)).orElse(false);
            boolean producerCertified = certificateOwned || retainedProducer.isPresent();
            require((modules.contains(witness.fromModule())
                            || graph.resolvedGraph().retainedModules()
                            .module(witness.fromModule()).isPresent())
                            && (modules.contains(witness.targetModule())
                            || graph.resolvedGraph().retainedModules()
                            .module(witness.targetModule()).isPresent()),
                    "eager effect names a foreign module");
            require(core.ownsFlowSite(witness.effectSite().orElseThrow(), witness.effectSpan())
                            || certificateOwned && sessionCertificate.orElseThrow()
                            .certifiesEffectPathEntry(witness.effectSpan(),
                                    witness.effectSite().orElseThrow())
                            || retainedProducer.map(producer -> producer.sealingCore().ownsFlowSite(
                                    witness.effectSite().orElseThrow(), witness.effectSpan())).orElse(false),
                    "eager effect flow-site identity does not match its source span");
            require(witness.sourcePath().size() == witness.sourceSitePath().size(),
                    "eager effect site/span path lengths differ");
            for (int index = 0; index < witness.sourcePath().size(); index++) {
                boolean owned = core.ownsFlowSite(
                        witness.sourceSitePath().get(index), witness.sourcePath().get(index));
                var span = witness.sourcePath().get(index);
                var site = witness.sourceSitePath().get(index);
                boolean certified = certificateOwned && sessionCertificate.orElseThrow()
                        .certifiesEffectPathEntry(span, site)
                        || retainedProducer.map(producer -> producer.sealingCore().ownsFlowSite(site, span))
                        .orElse(false);
                require(owned || certified,
                        "eager effect path site does not match its source span");
            }
            require(!witness.sourcePath().isEmpty()
                            && witness.sourcePath().getLast().equals(witness.effectSpan())
                            && witness.sourceSitePath().getLast().equals(
                            witness.effectSite().orElseThrow()),
                    "eager effect path has the wrong terminal site");
            witness.targetDeclaration().ifPresent(declaration -> require(
                    declarations.containsKey(declaration) || producerCertified
                            || graph.resolvedGraph().retainedModules().module(witness.targetModule())
                            .flatMap(record -> record.producerGraph().declaration(declaration)).isPresent(),
                    "eager effect declaration is foreign"));
            witness.referenceId().ifPresent(reference -> require(
                    graph.resolvedGraph().reference(reference).isPresent() || producerCertified,
                    "eager effect reference is foreign"));
            witness.targetLambda().ifPresent(lambda -> require(
                    lambdas.containsKey(lambda) || producerCertified
                            || graph.resolvedGraph().retainedModules().module(witness.targetModule())
                            .flatMap(record -> record.producerGraph().lambda(lambda)).isPresent(),
                    "eager effect lambda is foreign"));
            for (SummaryCallId call : witness.callPath()) {
                CallableCallReference reference = facts.callableSummaries()
                        .summary(call.ownerLambda()).stream()
                        .flatMap(summary -> summary.callReferences().stream())
                        .filter(value -> value.id().equals(call))
                        .findFirst()
                        .orElseGet(() -> sessionCertificate
                                .flatMap(certificate -> certificate.callableSummaries()
                                        .summary(call.ownerLambda()))
                                .flatMap(summary -> summary.callReferences().stream()
                                        .filter(value -> value.id().equals(call)).findFirst())
                                .orElseThrow(() ->
                                        invalid("eager effect path names a foreign call")));
                require(witness.sourceSitePath().contains(
                                reference.siteId().orElseThrow()),
                        "eager effect call path is not bound to its exact source site");
            }
        }

        private void validateCycles() {
            facts.eagerCycles().forEach(cycle -> {
                require(cycle.modules().stream().allMatch(modules::contains),
                        "eager cycle names a foreign module");
                cycle.activeDeclaration().ifPresent(this::requireDeclaration);
            });
        }

        private void requireSite(
                Optional<FlowSiteId> site,
                SourceSpan span,
                String description) {
            require(site.isPresent(), description + " has no exact flow-site identity");
            if (!core.ownsFlowSite(site.orElseThrow(), span)) {
                throw invalid(description + " flow-site identity does not match its source span");
            }
        }

        private void requireDeclaration(DeclarationId declaration) {
            require(declarations.containsKey(declaration),
                    "fact declaration identity is foreign: " + declaration);
        }

        private boolean isModuleDeclaration(DeclarationId declaration) {
            ResolvedDeclaration value = declarations.get(declaration);
            return value != null && value.kind() == DeclarationKind.IMPORT_MODULE;
        }

        private static boolean isCall(TypedExpression expression) {
            return expression.kind() == TypedExpressionKind.CALLABLE_CALL
                    || expression.kind() == TypedExpressionKind.ITER
                    || expression.kind() == TypedExpressionKind.WHILE
                    || expression.kind() == TypedExpressionKind.DIRECT_CALL
                    || expression.kind() == TypedExpressionKind.NAMESPACE_DIRECT_CALL;
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw invalid(message);
            }
        }

        private static IllegalArgumentException invalid(String message) {
            return new IllegalArgumentException("invalid semantic flow facts: " + message);
        }

        private record EventRequirement(
                SemanticFlowEvent.Kind kind,
                FlowSiteId site,
                Optional<CaptureId> capture) {
        }
    }
}
