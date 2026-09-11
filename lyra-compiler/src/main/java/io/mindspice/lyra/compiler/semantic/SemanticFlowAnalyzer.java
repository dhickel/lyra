package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.api.SessionFlowCertificate;
import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.RelatedSpan;
import io.mindspice.lyra.compiler.semantic.flow.AggregateIdentityFact;
import io.mindspice.lyra.compiler.semantic.flow.ArrayIdentity;
import io.mindspice.lyra.compiler.semantic.flow.CallableFlow;
import io.mindspice.lyra.compiler.semantic.flow.CallableSummary;
import io.mindspice.lyra.compiler.semantic.flow.CallableSummaryCompiler;
import io.mindspice.lyra.compiler.semantic.flow.CallableSummaryResult;
import io.mindspice.lyra.compiler.semantic.flow.CallableSummarySet;
import io.mindspice.lyra.compiler.semantic.flow.CapturedCellWrite;
import io.mindspice.lyra.compiler.semantic.flow.EagerCycleWitness;
import io.mindspice.lyra.compiler.semantic.flow.EagerEffectFact;
import io.mindspice.lyra.compiler.semantic.flow.EagerEffectWitness;
import io.mindspice.lyra.compiler.semantic.flow.FreshAllocationSite;
import io.mindspice.lyra.compiler.semantic.flow.FormulaAlternatives;
import io.mindspice.lyra.compiler.semantic.flow.NilProvenance;
import io.mindspice.lyra.compiler.semantic.flow.NormalizedExpression;
import io.mindspice.lyra.compiler.semantic.flow.OwnershipRequirement;
import io.mindspice.lyra.compiler.semantic.flow.OwnershipWitness;
import io.mindspice.lyra.compiler.semantic.flow.ProjectionPath;
import io.mindspice.lyra.compiler.semantic.flow.ProjectionStep;
import io.mindspice.lyra.compiler.semantic.flow.SemanticFlowEvent;
import io.mindspice.lyra.compiler.semantic.flow.SemanticFlowFacts;
import io.mindspice.lyra.compiler.semantic.flow.SemanticFlowResult;
import io.mindspice.lyra.compiler.semantic.flow.SummaryLimits;
import io.mindspice.lyra.compiler.semantic.flow.SummaryTransferResult;
import io.mindspice.lyra.compiler.semantic.flow.TypedExpressionNormalizer;
import io.mindspice.lyra.compiler.semantic.flow.ValueAlternative;
import io.mindspice.lyra.compiler.semantic.flow.ValueAlternatives;
import io.mindspice.lyra.compiler.semantic.flow.ValueFormula;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Canonical typed flow/effect analysis for the current semantic graph.
 *
 * <p>The analyzer is deliberately separate from resolution, bidirectional
 * typing, and initialization graph planning.  It consumes the complete typed
 * graph and the solved parameterized callable summaries, evaluates source
 * initializers in order, and publishes only compact immutable boundary facts.
 * It is JVM-independent and is not a serialized or public runtime model.</p>
 */
public final class SemanticFlowAnalyzer {
    private static final AtomicLong CANONICAL_ANALYSIS_COUNT = new AtomicLong();

    private SemanticFlowAnalyzer() {
    }

    public static SemanticFlowResult analyze(TypedSemanticGraph graph) {
        return analyze(graph, SummaryLimits.DEFAULT);
    }

    public static SemanticFlowResult analyze(
            TypedSemanticGraph graph,
            SummaryLimits limits) {
        return analyzeInput(Objects.requireNonNull(graph, "graph"), limits);
    }

    /** Package-owned entry point for the pre-seal typed semantic core. */
    static SemanticFlowResult analyze(TypedSemanticCore core) {
        return analyzeInput(Objects.requireNonNull(core, "core"), SummaryLimits.DEFAULT);
    }

    static SemanticFlowResult analyze(
            TypedSemanticCore core,
            SummaryLimits limits) {
        return analyzeInput(Objects.requireNonNull(core, "core"), limits);
    }

    /**
     * Runs the canonical producer once and returns a core certified against the
     * exact immutable facts it produced. The expected record is retained as a
     * package-owned compilation-local authority; sealing compares exact data
     * and never reconstructs semantic flow.
     */
    static PublicationResult analyzeForPublication(TypedSemanticCore core) {
        return analyzeForPublication(core, false);
    }

    /**
     * Attachable compilations model dispatch safe points as explicit effect
     * boundaries: public {@code @mut} root bindings may hold externally
     * written values, so reads of those bindings carry conservative
     * producer-backed facts instead of initializer-only ownership assumptions.
     */
    static PublicationResult analyzeForPublication(TypedSemanticCore core,
                                                   boolean attachableBoundary) {
        TypedSemanticCore source = Objects.requireNonNull(core, "core");
        Provenance provenance = new Provenance();
        TypedSemanticCore certifiedCore = source.withFlowProvenance(provenance);
        provenance.bind(certifiedCore);
        SemanticFlowResult result;
        try {
            result = analyzeInput(
                    certifiedCore, SummaryLimits.DEFAULT, true, attachableBoundary);
        } catch (OwnershipFailure failure) {
            return new PublicationDiagnosticFailure(failure.diagnostic());
        }
        if (result instanceof SemanticFlowResult.Failure failure) {
            return new PublicationFailure(failure.failure());
        }
        SemanticFlowFacts facts = ((SemanticFlowResult.Success) result).value();
        provenance.complete(facts);
        return new PublicationSuccess(certifiedCore, facts);
    }

    sealed interface PublicationResult permits PublicationSuccess,
            PublicationFailure, PublicationDiagnosticFailure {
    }

    record PublicationSuccess(
            TypedSemanticCore core,
            SemanticFlowFacts facts) implements PublicationResult {
        PublicationSuccess {
            Objects.requireNonNull(core, "core");
            Objects.requireNonNull(facts, "facts");
        }
    }

    record PublicationFailure(
            CallableSummaryResult.InternalFailure failure) implements PublicationResult {
        PublicationFailure {
            Objects.requireNonNull(failure, "failure");
        }
    }

    record PublicationDiagnosticFailure(
            Diagnostic diagnostic) implements PublicationResult {
        PublicationDiagnosticFailure {
            Objects.requireNonNull(diagnostic, "diagnostic");
        }
    }

    /** Exact producer-owned authority used only by the package sealer. */
    static final class Provenance {
        private final CertificationIdentity certification = new CertificationIdentity();
        private SemanticFlowFacts expectedFacts;

        private Provenance() {
        }

        private synchronized void bind(TypedSemanticCore certifiedCore) {
            certification.bind(certifiedCore);
        }

        private synchronized void complete(SemanticFlowFacts facts) {
            if (!certification.isBound() || expectedFacts != null) {
                throw new IllegalStateException(
                        "canonical flow certification is not in its producer-owned build state");
            }
            expectedFacts = Objects.requireNonNull(facts, "facts");
        }

        synchronized void validate(TypedSemanticCore actualCore, SemanticFlowFacts actualFacts) {
            if (!certification.owns(Objects.requireNonNull(actualCore, "actualCore"))) {
                throw new IllegalArgumentException(
                        "canonical flow certification belongs to another typed semantic core instance");
            }
            if (expectedFacts == null) {
                throw new IllegalArgumentException(
                        "canonical flow certification was not completed by its producer");
            }
            if (!expectedFacts.equals(Objects.requireNonNull(actualFacts, "actualFacts"))) {
                throw new IllegalArgumentException(
                        "semantic flow facts do not match the canonical producer record");
            }
        }

        /** Private, one-shot object identity; never exposed as phase data or API. */
        private static final class CertificationIdentity {
            private TypedSemanticCore certifiedCore;

            private void bind(TypedSemanticCore core) {
                if (certifiedCore != null) {
                    throw new IllegalStateException(
                            "canonical flow certification is already bound to a core");
                }
                certifiedCore = Objects.requireNonNull(core, "core");
            }

            private boolean isBound() {
                return certifiedCore != null;
            }

            private boolean owns(TypedSemanticCore core) {
                return certifiedCore == core;
            }
        }
    }

    private static SemanticFlowResult analyzeInput(
            TypedSemanticInput graph,
            SummaryLimits limits) {
        return analyzeInput(graph, limits, false);
    }

    private static SemanticFlowResult analyzeInput(
            TypedSemanticInput graph,
            SummaryLimits limits,
            boolean publishSourceDiagnostics) {
        return analyzeInput(graph, limits, publishSourceDiagnostics, false);
    }

    private static SemanticFlowResult analyzeInput(
            TypedSemanticInput graph,
            SummaryLimits limits,
            boolean publishSourceDiagnostics,
            boolean attachableBoundary) {
        Objects.requireNonNull(graph, "graph");
        CANONICAL_ANALYSIS_COUNT.incrementAndGet();
        Objects.requireNonNull(limits, "limits");
        try {
            CallableSummaryResult summaryResult = CallableSummaryCompiler.compile(graph, limits);
            if (summaryResult instanceof CallableSummaryResult.Failure failure) {
                return new SemanticFlowResult.Failure(failure.failure());
            }
            CallableSummarySet localSummaries = ((CallableSummaryResult.Success) summaryResult).value();
            CallableSummarySet effectiveSummaries = graph.resolvedGraph()
                    .sessionFlowCertificate()
                    .map(certificate -> CallableSummarySet.combine(
                            certificate.callableSummaries(), localSummaries))
                    .orElse(localSummaries);
            // A reloaded graph may still contain selective imports bound to an
            // older producer with the same logical/module source identity.
            // Their exact producer contracts carry the old callable summaries;
            // retain those proofs in the canonical flow domain rather than
            // pretending that the fresh graph owns the old lambdas.
            for (ResolvedImportBinding binding : graph.resolvedGraph().imports()) {
                if (binding.producerContract().isEmpty()) continue;
                var contract = binding.producerContract().orElseThrow();
                CallableSummarySet producerSummaries = contract.exports().stream()
                        .map(io.mindspice.lyra.compiler.session.SessionModuleContract.Export::callableSummaries)
                        .reduce(CallableSummarySet.empty(), CallableSummarySet::combine);
                effectiveSummaries = CallableSummarySet.combine(
                        effectiveSummaries, producerSummaries);
            }
            return new SemanticFlowResult.Success(
                    new Engine(graph, localSummaries, effectiveSummaries, limits,
                            attachableBoundary).run());
        } catch (OwnershipFailure failure) {
            if (publishSourceDiagnostics) {
                throw failure;
            }
            return new SemanticFlowResult.Failure(
                    CallableSummaryResult.InternalFailure.at(
                            CallableSummaryResult.InternalFailure.Kind.INCONSISTENT_SUMMARY,
                            "published graph contains an imported ownership violation",
                            failure.diagnostic().primarySpan()));
        } catch (FlowFailure failure) {
            return new SemanticFlowResult.Failure(failure.failure());
        } catch (IllegalArgumentException failure) {
            return new SemanticFlowResult.Failure(
                    CallableSummaryResult.InternalFailure.of(
                            CallableSummaryResult.InternalFailure.Kind.INCONSISTENT_SUMMARY,
                            failure.getMessage() == null
                                    ? "invalid canonical semantic flow input" : failure.getMessage(),
                            Optional.empty()));
        } catch (RuntimeException failure) {
            return new SemanticFlowResult.Failure(
                    CallableSummaryResult.InternalFailure.of(
                            CallableSummaryResult.InternalFailure.Kind.INCONSISTENT_SUMMARY,
                            failure.getMessage() == null
                                    ? "canonical semantic flow evaluation failed" : failure.getMessage(),
                            Optional.empty()));
        }
    }

    /** Package-private test instrumentation for the single-evaluation gate. */
    static long canonicalAnalysisCount() {
        return CANONICAL_ANALYSIS_COUNT.get();
    }

    public static SemanticFlowResult process(TypedSemanticGraph graph) {
        return analyze(graph);
    }

    public static SemanticFlowResult flow(TypedSemanticGraph graph) {
        return analyze(graph);
    }

    /**
     * Convenience form for package-local compiler phases.  An internal
     * semantic-artifact failure is never converted into an effect-free plan.
     */
    public static SemanticFlowFacts analyzeFacts(TypedSemanticGraph graph) {
        SemanticFlowResult result = analyze(graph);
        if (result instanceof SemanticFlowResult.Success success) {
            return success.value();
        }
        SemanticFlowResult.Failure failure = (SemanticFlowResult.Failure) result;
        throw new IllegalStateException(
                "canonical semantic flow analysis failed: " + failure.failure().kind()
                        + ": " + failure.failure().message());
    }

    public static SemanticFlowFacts facts(TypedSemanticGraph graph) {
        return analyzeFacts(graph);
    }

    private static final class Engine {
        private final TypedSemanticInput graph;
        /** Attachable compilations treat public @mut root reads as safe-point boundaries. */
        private final boolean attachableBoundary;
        /** Summaries authored by the current graph, retained in published facts. */
        private final CallableSummarySet localSummaries;
        /** Current plus producer-certified predecessor summaries used for transfer. */
        private final CallableSummarySet summaries;
        private final SummaryLimits limits;
        private final Map<DeclarationId, TypedDeclaration> declarations = new TreeMap<>();
        private final Map<LambdaId, TypedLambda> lambdas = new TreeMap<>();
        private final Map<LambdaId, FlowSiteId> lambdaCreationSites = new TreeMap<>();
        private final Map<ReferenceId, TypedReference> references = new TreeMap<>();
        private final Map<CaptureId, ResolvedCapture> captures = new TreeMap<>();
        private final Map<DeclarationId, ResolvedDeclaration> resolvedDeclarations = new TreeMap<>();
        private final Map<ModuleId, TypedModule> modules = new TreeMap<>();
        private final Map<TypedExpression, DeclarationId> arrayAllocationIds =
                new java.util.IdentityHashMap<>();
        private final Map<DeclarationId, TypedExpression> arrayAllocationExpressions = new TreeMap<>();
        private final Map<FreshAllocationSite, DeclarationId> summaryAllocationIds = new TreeMap<>();
        private final Map<DeclarationId, ScopeId> allocationScopes = new TreeMap<>();
        private final Map<DeclarationId, SourceSpan> allocationSpans = new TreeMap<>();
        private final Map<DeclarationId, FlowSiteId> allocationFlowSites = new TreeMap<>();
        private final Map<DeclarationId, List<CaptureBinding>> sharedCaptures = new TreeMap<>();
        private final Map<DeclarationId, ValueAlternatives> declarationValues =
                new TreeMap<>();
        private final Map<ModuleId, Map<ArrayIdentity, Set<SourceSpan>>>
                ownershipDiagnosticOrigins = new TreeMap<>();
        private final Map<OwnershipOccurrence, Set<SourceSpan>>
                ownershipOccurrenceOrigins = new TreeMap<>();
        /** True while the canonical engine is executing a top-level initializer. */
        private boolean initializationEvaluation;

        private Engine(
                TypedSemanticInput graph,
                CallableSummarySet localSummaries,
                CallableSummarySet summaries,
                SummaryLimits limits,
                boolean attachableBoundary) {
            this.graph = Objects.requireNonNull(graph, "graph");
            this.localSummaries = Objects.requireNonNull(localSummaries, "localSummaries");
            this.summaries = Objects.requireNonNull(summaries, "summaries");
            this.limits = Objects.requireNonNull(limits, "limits");
            this.attachableBoundary = attachableBoundary;
            for (TypedDeclaration declaration : graph.declarations()) {
                if (declarations.put(declaration.id(), declaration) != null) {
                    throw new IllegalArgumentException("duplicate typed declaration identity");
                }
            }
            for (TypedLambda lambda : graph.lambdas()) {
                if (lambdas.put(lambda.id(), lambda) != null) {
                    throw new IllegalArgumentException("duplicate typed lambda identity");
                }
            }
            for (TypedExpression expression : graph.expressions()) {
                expression.lambdaId().ifPresent(lambda -> {
                    if (expression.kind() == TypedExpressionKind.LAMBDA
                            && lambdaCreationSites.put(lambda, graph.flowSiteId(expression)) != null) {
                        throw new IllegalArgumentException(
                                "lambda has multiple typed creation sites");
                    }
                });
            }
            for (TypedReference reference : graph.references()) {
                if (references.put(reference.id(), reference) != null) {
                    throw new IllegalArgumentException("duplicate typed reference identity");
                }
            }
            for (ResolvedCapture capture : graph.resolvedGraph().captures()) {
                if (captures.put(capture.id(), capture) != null) {
                    throw new IllegalArgumentException("duplicate capture identity");
                }
                if (capture.isSharedCell()) {
                    sharedCaptures.computeIfAbsent(capture.declarationId(), ignored -> new ArrayList<>())
                            .add(new CaptureBinding(
                                    capture.id(), capture.declarationId(),
                                    capture.sharedCellId().orElseThrow()));
                }
            }
            for (ResolvedDeclaration declaration : graph.resolvedGraph().declarations()) {
                resolvedDeclarations.put(declaration.id(), declaration);
            }
            for (TypedModule module : graph.modules()) {
                if (modules.put(module.moduleId(), module) != null) {
                    throw new IllegalArgumentException("duplicate typed module identity");
                }
            }
            assignArrayAllocationIds();
            assignSummaryAllocationIds();
        }

        private SemanticFlowFacts run() {
            List<NormalizedExpression> normalized = new ArrayList<>();
            for (TypedModule module : modules.values()) {
                for (TypedExpression form : module.forms()) {
                    normalized.add(TypedExpressionNormalizer.normalize(form));
                }
            }
            RootContext context = new RootContext();
            for (TypedModule module : modules.values()) {
                context.run(module.moduleId());
            }
            return new SemanticFlowFacts(
                    localSummaries, normalized, context.events(), context.effects(),
                    context.cycles(), context.declarationValues(), context.finalStates(),
                    context.attemptedStates);
        }

        private void assignArrayAllocationIds() {
            List<TypedExpression> arrays = graph.expressions().stream()
                    .filter(expression -> expression.kind() == TypedExpressionKind.ARRAY_LITERAL)
                    .sorted(expressionComparator())
                    .toList();
            for (int index = 0; index < arrays.size(); index++) {
                TypedExpression expression = arrays.get(index);
                DeclarationId allocation = new DeclarationId(Long.MAX_VALUE -
                        (graph.resolvedGraph().isSessionGraph() ? graph.flowSiteId(expression).ordinal() : index));
                arrayAllocationIds.put(expression, allocation);
                arrayAllocationExpressions.put(allocation, expression);
                allocationScopes.put(allocation, graph.flowScopeId(expression));
                allocationSpans.put(allocation, expression.span());
                allocationFlowSites.put(allocation, graph.flowSiteId(expression));
            }
        }

        private void assignSummaryAllocationIds() {
            TreeSet<FreshAllocationSite> sites = new TreeSet<>();
            for (CallableSummary summary : summaries.orderedSummaries()) {
                collectFreshSites(summary.returnFormula().alternatives(), sites);
                for (CapturedCellWrite write : summary.writes()) {
                    collectFreshSites(write.value(), sites);
                }
                for (var requirement : summary.ownershipRequirements()) {
                    collectFreshSites(requirement.value(), sites);
                }
                for (var call : summary.callReferences()) {
                    collectFreshSites(call.target(), sites);
                    for (FormulaAlternatives argument : call.arguments()) {
                        collectFreshSites(argument, sites);
                    }
                }
            }
            int index = 0;
            for (FreshAllocationSite site : sites) {
                DeclarationId allocation = new DeclarationId(Long.MAX_VALUE / 2L - index);
                summaryAllocationIds.put(site, allocation);
                TypedLambda owner = lambdas.get(site.ownerLambda());
                if (owner == null) {
                    // Retained predecessor summaries remain available for
                    // transfer, but their allocation sites are resolved by
                    // the predecessor certificate rather than this graph.
                    continue;
                }
                List<TypedExpression> allocations = new ArrayList<>();
                collectLambdaAllocations(owner.body(), allocations);
                if (site.ordinal() >= allocations.size()
                        || !allocations.get(site.ordinal()).span().equals(site.span())) {
                    throw new IllegalArgumentException(
                            "summary allocation site does not match its typed lambda body");
                }
                TypedExpression expression = allocations.get(site.ordinal());
                if (graph.resolvedGraph().isSessionGraph()) {
                    allocation = new DeclarationId(Long.MAX_VALUE / 2L - graph.flowSiteId(expression).ordinal());
                    summaryAllocationIds.put(site, allocation);
                }
                allocationScopes.put(allocation, graph.flowScopeId(expression));
                allocationSpans.put(allocation, site.span());
                allocationFlowSites.put(allocation, graph.flowSiteId(expression));
                index++;
            }
        }

        private void collectFreshSites(
                FormulaAlternatives alternatives,
                Set<FreshAllocationSite> destination) {
            for (ValueFormula formula : alternatives.formulas()) {
                collectFreshSites(formula, destination);
            }
        }

        private void collectFreshSites(
                ValueFormula formula,
                Set<FreshAllocationSite> destination) {
            if (formula instanceof ValueFormula.FreshAllocation fresh) {
                destination.add(fresh.allocationSite());
            } else if (formula instanceof ValueFormula.Lambda lambda) {
                lambda.captures().values().forEach(value -> collectFreshSites(value, destination));
            }
        }

        private void collectLambdaAllocations(
                TypedExpression expression,
                List<TypedExpression> destination) {
            if (expression.kind() == TypedExpressionKind.LAMBDA) {
                return;
            }
            for (TypedExpression child : expression.children()) {
                collectLambdaAllocations(child, destination);
            }
            if (expression.kind() == TypedExpressionKind.ARRAY_LITERAL) {
                destination.add(expression);
            }
        }

        private Comparator<TypedExpression> expressionComparator() {
            return Comparator.comparing((TypedExpression expression) -> expression.span().sourceId().value())
                    .thenComparingInt(expression -> expression.span().startOffset())
                    .thenComparingInt(expression -> expression.span().endOffset())
                    .thenComparing(expression -> expression.kind().name())
                    .thenComparing(expression -> expression.type().canonicalSpelling());
        }

        private FlowSiteId siteForReference(
                Optional<ReferenceId> referenceId,
                Optional<DeclarationId> targetDeclaration,
                SourceSpan span) {
            List<TypedExpression> matches = graph.expressions().stream()
                    .filter(expression -> expression.span().equals(span))
                    .filter(expression -> referenceId.isEmpty()
                            || expression.link().flatMap(TypedLink::referenceId)
                            .equals(referenceId))
                    .filter(expression -> targetDeclaration.isEmpty()
                            || expression.link().flatMap(TypedLink::declarationId)
                            .map(this::originDeclaration)
                            .equals(targetDeclaration.map(this::originDeclaration)))
                    .toList();
            if (matches.size() != 1) {
                throw failure(
                        CallableSummaryResult.InternalFailure.Kind.INVALID_TYPED_EXPRESSION,
                        "source provenance does not identify exactly one typed flow site: "
                                + span + " reference=" + referenceId
                                + " target=" + targetDeclaration + " matches=" + matches,
                        span);
            }
            return graph.flowSiteId(matches.getFirst());
        }

        private FlowSiteId siteForCapture(ResolvedCapture capture) {
            return capture.references().stream().findFirst()
                    .map(graph::flowSiteId)
                    .orElseGet(() -> graph.flowSiteId(capture.id()));
        }

        private FlowSiteId siteForEffect(EagerEffectWitness effect) {
            return effect.effectSite().orElseGet(() -> siteForReference(
                    effect.referenceId(), effect.targetDeclaration(), effect.effectSpan()));
        }

        private final class RootContext {
            private final Map<ModuleId, Frame> frames = new TreeMap<>();
            private final Map<ModuleId, io.mindspice.lyra.compiler.semantic.flow.BindingFlowState>
                    attemptedStates = new TreeMap<>();

            private void rememberPrefix(Frame frame,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state) {
                attemptedStates.merge(frame.module.moduleId(), state,
                        io.mindspice.lyra.compiler.semantic.flow.BindingFlowState::join);
            }
            private final Deque<ActiveDeclaration> activeDeclarations = new ArrayDeque<>();
            private final LinkedHashSet<SemanticFlowEvent> events = new LinkedHashSet<>();
            private final LinkedHashSet<EagerEffectFact> effects = new LinkedHashSet<>();
            private final LinkedHashSet<EagerCycleWitness> cycles = new LinkedHashSet<>();

            private void run(ModuleId module) {
                Frame frame = frame(Objects.requireNonNull(module, "module"));
                while (frame.nextForm < frame.forms.size()) {
                    executeNext(frame);
                }
            }

            private List<SemanticFlowEvent> events() {
                return List.copyOf(events);
            }

            private List<EagerEffectFact> effects() {
                return List.copyOf(effects);
            }

            private List<EagerCycleWitness> cycles() {
                return List.copyOf(cycles);
            }

            private Map<DeclarationId, ValueAlternatives> declarationValues() {
                return Map.copyOf(declarationValues);
            }

            private Map<ModuleId, io.mindspice.lyra.compiler.semantic.flow.BindingFlowState>
            finalStates() {
                TreeMap<ModuleId, io.mindspice.lyra.compiler.semantic.flow.BindingFlowState> result =
                        new TreeMap<>();
                frames.forEach((module, frame) -> result.put(module, frame.state));
                return Map.copyOf(result);
            }

            private Frame frame(ModuleId module) {
                Frame existing = frames.get(module);
                if (existing != null) {
                    return existing;
                }
                TypedModule typedModule = modules.get(module);
                if (typedModule == null) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                            "flow refers to an absent typed module: " + module,
                            graph.resolvedGraph().moduleGraph().module(module)
                                    .map(value -> value.program().span())
                                    .orElseThrow());
                }
                Frame created = new Frame(typedModule);
                if (graph.resolvedGraph().isRetained(module)) {
                    var record = graph.resolvedGraph().retainedModules().module(module).orElseThrow();
                    var original = graph.resolvedGraph().retainedModules().flowFacts()
                            .flatMap(facts -> facts.finalState(module)).orElseGet(() -> record.finalState().orElseThrow());
                    var boundary = graph.resolvedGraph().sessionFlowCertificate()
                            .map(SessionFlowCertificate::boundaryState).orElse(original);
                    var bindings = new TreeMap<>(original.bindings());
                    bindings.replaceAll((id, value) -> boundary.bindings().getOrDefault(id, value));
                    var cells = new TreeMap<>(original.sharedCells());
                    cells.replaceAll((id, value) -> boundary.sharedCells().getOrDefault(id, value));
                    created.state = io.mindspice.lyra.compiler.semantic.flow.BindingFlowState.of(bindings, cells);
                    created.state.bindings().forEach((id, value) -> created.values.put(id, value.alternatives()));
                    created.nextForm = created.forms.size();
                    var facts = record.producerGraph().semanticFlowFacts();
                    events.addAll(facts.events().stream().filter(event ->
                            event.kind() == SemanticFlowEvent.Kind.EFFECT
                                    ? event.effects().stream().anyMatch(effect -> effect.fromModule().equals(module))
                                    : event.moduleId().equals(module)).toList());
                    effects.addAll(facts.eagerEffectFacts().stream()
                            .filter(value -> value.initializerModule().equals(module)).toList());
                    facts.declarationValues().forEach((id, value) -> {
                        if (record.resolvedModule().declarations().contains(id)) declarationValues.put(id, value);
                    });
                }
                // External declarations carry initialized type/ownership
                // contracts, not old initializer or fresh allocation facts.
                for (ResolvedDeclaration declaration : resolvedDeclarations.values()) {
                    if (declaration.kind() != DeclarationKind.EXTERNAL
                            || !declaration.moduleId().equals(module)
                            || !declaration.scopeId().equals(typedModule.rootScope())) {
                        continue;
                    }
                    created.state = bind(created.state, declaration.id(),
                            externalValue(declaration), declaration.span());
                }
                frames.put(module, created);
                rememberPrefix(created, created.state);
                return created;
            }

            private Eval executeNext(Frame frame) {
                if (frame.nextForm >= frame.forms.size()) {
                    return new Eval(
                            scalarValue(PrimitiveType.UNIT), frame.state, List.of(), List.of());
                }
                int index = frame.nextForm++;
                TypedExpression form = frame.forms.get(index);
                Optional<DeclarationId> initializer = form.kind() == TypedExpressionKind.DECLARATION
                        ? form.declarationId() : Optional.empty();
                boolean previousInitializationEvaluation = initializationEvaluation;
                // A session scratch root executes one evaluation against live
                // registered-root storage, not protected initializer code:
                // conservative attachable-boundary facts must never be
                // exempted from ownership checks for its top-level forms.
                // Real module initializers (ordinary graphs and session-owned
                // dependencies) keep the initialization exemption.
                initializationEvaluation = !(graph.resolvedGraph().isSessionGraph()
                        && frame.module.moduleId().equals(
                        graph.resolvedGraph().moduleGraph().rootModule()));
                Eval result;
                try {
                    result = evaluate(form, frame, frame.state);
                } finally {
                    initializationEvaluation = previousInitializationEvaluation;
                }
                frame.state = result.state;
                initializer.ifPresent(id -> {
                    frame.values.put(id, frame.state.binding(id)
                            .map(value -> value.alternatives())
                            .orElse(result.value));
                    declarationValues.put(id, frame.values.get(id));
                });
                for (SemanticFlowEvent event : result.events) {
                    events.add(event.withInitializer(initializer));
                }
                for (EagerEffectWitness effect : result.effects) {
                    if (!effect.fromModule().equals(frame.module.moduleId())) {
                        throw new IllegalStateException(
                                "flow effect is not attributed to its executing module");
                    }
                    effects.add(new EagerEffectFact(
                            frame.module.moduleId(), initializer, effect));
                    events.add(new SemanticFlowEvent(
                            SemanticFlowEvent.Kind.EFFECT,
                            ModuleId.fromSourceId(effect.effectSpan().sourceId()),
                            effect.effectSpan(),
                            initializer,
                            Optional.empty(),
                            effect.targetDeclaration(),
                            effect.targetLambda(),
                            effect.referenceId(),
                            Optional.empty(),
                            Optional.empty(),
                            ValueAlternatives.empty(),
                            List.of(),
                            List.of(effect),
                            Optional.of(siteForEffect(effect)),
                            Optional.empty()));
                }
                return result;
            }

            private Lookup ensure(
                    Frame frame,
                    DeclarationId requested,
                    SourceSpan useSpan) {
                ResolvedDeclaration resolved = resolvedDeclarations.get(requested);
                if (resolved != null && resolved.kind() == DeclarationKind.PARAMETER) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                            "flow attempted to materialize an inactive parameter: "
                                    + requested + " use=" + useSpan
                                    + " active=" + activeDeclarations,
                            useSpan);
                }
                if (resolved != null && resolved.kind() == DeclarationKind.IMPORT_VALUE
                        && resolved.originDeclaration().isPresent()) {
                    ModuleId ownerModule = resolved.importedModule().orElse(
                            resolvedDeclarations.get(resolved.originDeclaration().orElseThrow())
                                    .moduleId());
                    return ensure(frame(ownerModule), resolved.originDeclaration().orElseThrow(), useSpan);
                }
                DeclarationId declarationId = originDeclaration(requested);
                Frame ownerFrame = frame;
                TypedDeclaration declaration = declarations.get(declarationId);
                if (declaration != null && !declaration.moduleId().equals(frame.module.moduleId())) {
                    ownerFrame = frame(declaration.moduleId());
                }
                ValueAlternatives current = ownerFrame.state.binding(declarationId)
                        .map(value -> value.alternatives()).orElse(null);
                if (current != null && !current.isEmpty()) {
                    return new Lookup(current, List.of(), false);
                }
                current = ownerFrame.values.get(declarationId);
                if (current != null && !current.isEmpty()) {
                    return new Lookup(current, List.of(), false);
                }
                if (activeDeclarations.stream().anyMatch(active ->
                        active.declaration().equals(declarationId))) {
                    recordCycle(declarationId, useSpan);
                    return new Lookup(scalarValue(contractType(declarationId, useSpan)), List.of(), false);
                }
                if (declaration == null) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                            "flow refers to an absent declaration: " + declarationId,
                            useSpan);
                }
                if (isIntrinsic(declarationId)) {
                    LyraType type = contractType(declarationId, useSpan);
                    if (!(type.withoutQualifiers() instanceof FunctionType function)) {
                        throw failure(
                                CallableSummaryResult.InternalFailure.Kind.INCONSISTENT_SUMMARY,
                                "intrinsic declaration is not callable", useSpan);
                    }
                    ValueAlternatives intrinsic = ValueAlternatives.singleton(
                            ValueAlternative.callable(
                                    type, CallableFlow.intrinsicAtRoot(declarationId)));
                    ownerFrame.state = bind(ownerFrame.state, declarationId, intrinsic, useSpan);
                    ownerFrame.values.put(declarationId, intrinsic);
                    // Intrinsic exports are compiler-owned call targets, not
                    // source declarations.  They have no producer declaration
                    // event, so keep them out of the event-backed value index;
                    // callable formula lowering resolves them through the
                    // intrinsic summary index instead.
                    return new Lookup(intrinsic, List.of(), true);
                }
                if (graph.resolvedGraph().isRetained(ownerFrame.module.moduleId())) {
                    throw failure(CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                            "retained producer has no initialized boundary value: " + declarationId, useSpan);
                }
                if (isLazyFunctionSlot(declarationId)) {
                    // A mutable recursive function owns a real shared cell.
                    // Publish its exact identity internally before resolving
                    // captures so the cell edge closes without recursive construction.
                    LambdaId id = declaration.initializerLambda().orElseThrow();
                    if (lambdas.get(id).captures().stream().map(captures::get)
                            .anyMatch(capture -> capture.mode() == CaptureMode.SHARED_MUTABLE_CELL
                                    && capture.declarationId().equals(declarationId))) {
                        ValueAlternatives identity = ValueAlternatives.singleton(ValueAlternative.callable(
                                contractType(declarationId, useSpan), CallableFlow.atRoot(id, Map.of(), Map.of(),
                                        Objects.requireNonNull(lambdaCreationSites.get(id)))));
                        ownerFrame.state = bind(ownerFrame.state, declarationId, identity, useSpan);
                    }
                    Eval lambda = lambdaValueForDeclaration(declaration, ownerFrame, ownerFrame.state, useSpan);
                    ownerFrame.state = bind(ownerFrame.state, declarationId, lambda.value, useSpan);
                    ownerFrame.values.put(declarationId, lambda.value);
                    declarationValues.put(declarationId, lambda.value);
                    return new Lookup(lambda.value, lambda.effects, true);
                }
                int formIndex = ownerFrame.formIndex(declarationId);
                if (formIndex >= 0 && formIndex >= ownerFrame.nextForm) {
                    ArrayList<EagerEffectWitness> executedEffects = new ArrayList<>();
                    while (ownerFrame.nextForm <= formIndex) {
                        executedEffects.addAll(executeNext(ownerFrame).effects);
                    }
                    ValueAlternatives value = ownerFrame.values.get(declarationId);
                    if (value != null) {
                        return new Lookup(value, distinctEffects(executedEffects), true);
                    }
                }
                if (declaration.initializer().isEmpty()) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                            "declaration has no recoverable initializer value: " + declarationId
                                    + " (" + declaration.name() + ", " + declaration.kind()
                                    + ", " + declaration.moduleId() + ")",
                            useSpan);
                }
                ActiveDeclaration active = new ActiveDeclaration(
                        declarationId, ownerFrame.module.moduleId(), declaration.span());
                activeDeclarations.addLast(active);
                try {
                    Eval initializer = evaluate(
                            declaration.initializer().orElseThrow(),
                            ownerFrame,
                            ownerFrame.state);
                    ownerFrame.state = bind(
                            initializer.state, declarationId, initializer.value, useSpan);
                    ownerFrame.values.put(declarationId,
                            ownerFrame.state.requireBinding(declarationId).alternatives());
                    declarationValues.put(declarationId, ownerFrame.values.get(declarationId));
                    return new Lookup(ownerFrame.values.get(declarationId), initializer.effects, true);
                } finally {
                    activeDeclarations.removeLastOccurrence(active);
                }
            }

            private Lookup readValue(
                    TypedExpression expression,
                    Frame frame,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state,
                    boolean addCrossModuleRead) {
                TypedLink link = expression.link().orElseThrow(() -> failure(
                        CallableSummaryResult.InternalFailure.Kind.INVALID_TYPED_EXPRESSION,
                        "value expression has no typed link", expression.span()));
                DeclarationId requested = link.declarationId().orElseThrow(() -> failure(
                        CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                        "value expression has no declaration target", expression.span()));
                ResolvedDeclaration requestedDeclaration = resolvedDeclarations.get(requested);
                DeclarationId target = originDeclaration(requested);
                ModuleId targetModule = link.moduleId().orElseGet(() -> requestedDeclaration != null
                        ? requestedDeclaration.importedModule().orElseGet(() -> declarationModule(target))
                        : declarationModule(target));
                Lookup lookup = retainedImportLookup(requestedDeclaration, expression.span())
                        .orElseGet(() -> {
                            Frame targetFrame = frame(targetModule);
                            return (targetModule.equals(frame.module.moduleId())
                                    || graph.resolvedGraph().isRetained(targetModule))
                                    && state.binding(target).isPresent()
                                    ? new Lookup(state.requireBinding(target).alternatives(), List.of(), false)
                                    : ensure(targetFrame, target, expression.span());
                        });
                List<EagerEffectWitness> effects = new ArrayList<>();
                for (EagerEffectWitness effect : lookup.effects) {
                    effects.add(attribute(
                            effect, frame.module.moduleId(), expression.span(),
                            graph.flowSiteId(expression)));
                }
                ValueAlternatives values = lookup.value;
                if (targetModule != null && !targetModule.equals(frame.module.moduleId())) {
                    if (addCrossModuleRead) {
                        FlowSiteId site = graph.flowSiteId(expression);
                        effects.add(new EagerEffectWitness(
                                frame.module.moduleId(), targetModule,
                                EagerEffectWitness.Kind.VALUE_READ,
                                expression.span(), Optional.of(declarations.containsKey(target) ? target : requested),
                                link.referenceId(), Optional.empty(),
                                List.of(expression.span()), List.of(), false,
                                Optional.of(site), List.of(site)));
                    }
                    values = imported(
                            values, targetModule, target, link.exportId(), expression.span());
                    rememberOwnershipDiagnosticOrigins(
                            values, requested, frame.module.moduleId(), expression.span());
                }
                // Root initialization is not dispatchable.  Keep direct
                // top-level reads tied to their exact initializer facts; the
                // boundary is applied when a value is captured by a callable
                // that can execute after publication.
                return new Lookup(values, distinctEffects(effects), lookup.executed);
            }

            /**
             * In attachable compilations the live contents of a public
             * {@code @mut} root value binding may be replaced by evaluation
             * writes at any dispatch safe point.  Reads therefore carry
             * conservative boundary facts for aggregates instead of assuming
             * the initializer allocation is still the binding's value.
             */
            private ValueAlternatives attachableBoundaryValues(
                    ValueAlternatives values,
                    DeclarationId declaration,
                    SourceSpan useSpan) {
                if (values.isEmpty() || !attachableRootMutableBinding(declaration)) {
                    return values;
                }
                ModuleId root = graph.resolvedGraph().moduleGraph().rootModule();
                Optional<io.mindspice.lyra.compiler.identity.ExportId> export =
                        graph.resolvedGraph().declaration(declaration)
                                .flatMap(value -> graph.resolvedGraph()
                                        .export(root, value.name()))
                                .flatMap(ResolvedExport::exportId);
                return attachableBoundary(values, root, declaration, export, useSpan);
            }

            private Optional<Lookup> retainedImportLookup(
                    ResolvedDeclaration declaration, SourceSpan useSpan) {
                if (declaration == null || declaration.kind() != DeclarationKind.IMPORT_VALUE
                        || declaration.originDeclaration().isEmpty()) {
                    return Optional.empty();
                }
                var binding = graph.resolvedGraph().imports().stream()
                        .filter(value -> value.declarationId().equals(declaration.id()))
                        .findFirst().orElse(null);
                if (binding == null || binding.producerContract().isEmpty()) {
                    return Optional.empty();
                }
                var contract = binding.producerContract().orElseThrow();
                String name = binding.importedName().orElseThrow();
                var export = contract.exports().stream()
                        .filter(value -> value.export().name().equals(name))
                        .findFirst().orElseThrow(() -> failure(
                                CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                                "retained import has no producer export: " + name, useSpan));
                var value = export.boundaryState().binding(export.declaration().id())
                        .orElseThrow(() -> failure(
                                CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                                "retained import has no initialized producer value: " + name,
                                useSpan));
                return Optional.of(new Lookup(value.alternatives(), List.of(), false));
            }

            private Eval evaluate(
                    TypedExpression expression,
                    Frame frame,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state) {
                rememberPrefix(frame, state);
                Eval result = switch (expression.kind()) {
                    case LITERAL -> literal(expression, state);
                    case REFERENCE -> reference(expression, frame, state);
                    case DECLARATION -> declaration(expression, frame, state);
                    case REBINDING -> rebinding(expression, frame, state);
                    case BLOCK -> block(expression, frame, state);
                    case CONDITIONAL -> conditional(expression, frame, state);
                    case COALESCE -> coalesce(expression, frame, state);
                    case MATCH -> match(expression, frame, state);
                    case ITER, WHILE -> loop(expression, frame, state);
                    case LAMBDA -> lambdaExpression(expression, frame, state);
                    case CALLABLE_CALL -> callableCall(expression, frame, state);
                    case DIRECT_CALL, NAMESPACE_DIRECT_CALL -> directCall(expression, frame, state);
                    case MEMBER_ACCESS -> member(expression, frame, state);
                    case NAMESPACE_MEMBER_ACCESS -> namespaceMember(expression, frame, state);
                    case ARRAY_LITERAL -> array(expression, frame, state);
                    case TUPLE_LITERAL -> tuple(expression, frame, state);
                    case INDEX_ACCESS -> index(expression, frame, state);
                    case OPERATOR, SHORT_CIRCUIT, RANGE -> operator(expression, frame, state);
                    case CONVERSION, NARROWING -> unary(expression, frame, state);
                };
                requireFlowDomain(result.value, expression.span());
                requireEffectDomain(result.effects, expression.span());
                rememberPrefix(frame, result.state);
                return result;
            }

            private void requireFlowDomain(
                    ValueAlternatives values,
                    SourceSpan span) {
                if (values.size() > limits.maxFormulaAlternatives()) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.DOMAIN_LIMIT,
                            "flow alternatives exceed the finite semantic domain: "
                                    + values.size() + " > " + limits.maxFormulaAlternatives(),
                            span);
                }
                for (ValueAlternative value : values) {
                    int symbolicFacts = value.aggregateIdentities().size()
                            + value.callableFlows().size()
                            + value.nilProvenance().size();
                    if (symbolicFacts > limits.maxFormulaAlternatives()) {
                        throw failure(
                                CallableSummaryResult.InternalFailure.Kind.DOMAIN_LIMIT,
                                "flow symbolic facts exceed the finite semantic domain: "
                                        + symbolicFacts + " > " + limits.maxFormulaAlternatives(),
                                span);
                    }
                    for (var fact : value.aggregateIdentities()) {
                        requireProjectionDepth(fact.route(), span);
                    }
                    for (NilProvenance nil : value.nilProvenance()) {
                        requireProjectionDepth(nil.route(), span);
                    }
                    for (CallableFlow callable : value.callableFlows()) {
                        requireProjectionDepth(callable.route(), span);
                        for (ValueAlternatives captured : callable.capturedValues().values()) {
                            requireFlowDomain(captured, span);
                        }
                        for (ValueAlternatives captured : callable.sharedCellSnapshots().values()) {
                            requireFlowDomain(captured, span);
                        }
                    }
                }
            }

            private void requireProjectionDepth(
                    ProjectionPath route,
                    SourceSpan span) {
                if (route.depth() > limits.maxProjectionDepth()) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.DOMAIN_LIMIT,
                            "flow projection depth exceeds the finite semantic domain: "
                                    + route.depth() + " > " + limits.maxProjectionDepth(),
                            span);
                }
            }

            private void requireEffectDomain(
                    List<EagerEffectWitness> effects,
                    SourceSpan span) {
                if (effects.size() > limits.maxEffects()) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.DOMAIN_LIMIT,
                            "flow eager effects exceed the finite semantic domain: "
                                    + effects.size() + " > " + limits.maxEffects(),
                            span);
                }
                for (EagerEffectWitness effect : effects) {
                    if (effect.sourcePath().size() > limits.maxWitnessPathDepth()) {
                        throw failure(
                                CallableSummaryResult.InternalFailure.Kind.DOMAIN_LIMIT,
                                "flow witness path exceeds the finite semantic domain",
                                span);
                    }
                }
            }

            private Eval literal(
                    TypedExpression expression,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state) {
                ValueAlternatives value = expression.literal().orElse(null)
                        instanceof TypedLiteralValue.NilValue
                        ? nilValue(expression)
                        : scalarValue(expression.type());
                return new Eval(value, state, List.of(), List.of());
            }

            private Eval reference(
                    TypedExpression expression,
                    Frame frame,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state) {
                Lookup lookup = readValue(expression, frame, state, true);
                return new Eval(lookup.value, state, List.of(), lookup.effects);
            }

            private Eval namespaceMember(
                    TypedExpression expression,
                    Frame frame,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state) {
                Lookup lookup = readValue(expression, frame, state, true);
                return new Eval(lookup.value, state, List.of(), lookup.effects);
            }

            private Eval declaration(
                    TypedExpression expression,
                    Frame frame,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state) {
                if (expression.children().size() != 1 || expression.declarationId().isEmpty()) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.INVALID_TYPED_EXPRESSION,
                            "declaration operation is incomplete", expression.span());
                }
                DeclarationId declaration = expression.declarationId().orElseThrow();
                ActiveDeclaration active = new ActiveDeclaration(
                        declaration, frame.module.moduleId(), expression.span());
                activeDeclarations.addLast(active);
                Eval initializer;
                try {
                    initializer = evaluate(expression.children().getFirst(), frame, state);
                } finally {
                    activeDeclarations.removeLastOccurrence(active);
                }
                BindingContract contract = graph.contract(declaration).orElseThrow(() -> failure(
                        CallableSummaryResult.InternalFailure.Kind.INVALID_TYPED_EXPRESSION,
                        "declaration has no typed contract", expression.span()));
                ValueAlternatives value = retag(initializer.value, contract.valueType());
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState next = bind(
                        initializer.state, declaration, value, expression.span());
                ArrayList<SemanticFlowEvent> events = new ArrayList<>(initializer.events);
                events.add(new SemanticFlowEvent(
                        SemanticFlowEvent.Kind.DECLARATION,
                        frame.module.moduleId(),
                        expression.span(),
                        Optional.empty(),
                        Optional.of(declaration),
                        Optional.empty(),
                        expression.children().getFirst().lambdaId(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        value,
                        List.of(),
                        initializer.effects,
                        Optional.of(graph.flowSiteId(expression)),
                        Optional.empty()));
                return new Eval(
                        scalarValue(expression.type()), next, events, initializer.effects);
            }

            private Eval rebinding(
                    TypedExpression expression,
                    Frame frame,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state) {
                if (expression.children().size() != 2) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.INVALID_TYPED_EXPRESSION,
                            "rebinding operation is incomplete", expression.span());
                }
                TypedExpression targetExpression = expression.children().getFirst();
                Eval target = evaluate(targetExpression, frame, state);
                TargetPath path = targetPath(targetExpression);
                if (path == null) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.INVALID_TYPED_EXPRESSION,
                            "typed rebinding has no canonical mutation target", expression.span());
                }
                rejectImportedAggregateMutation(
                        target.state, path, targetExpression.span(),
                        frame.module.moduleId());
                Eval value = evaluate(expression.children().get(1), frame, target.state);
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState updated = applyUpdate(
                        value.state, path, value.value, expression.span());
                ArrayList<SemanticFlowEvent> events = new ArrayList<>();
                events.addAll(target.events);
                events.addAll(value.events);
                events.add(new SemanticFlowEvent(
                        SemanticFlowEvent.Kind.MUTATION,
                        frame.module.moduleId(),
                        expression.span(),
                        Optional.empty(),
                        Optional.of(path.declaration),
                        Optional.empty(),
                        Optional.empty(),
                        expression.children().getFirst().link()
                                .flatMap(TypedLink::referenceId),
                        Optional.empty(),
                        Optional.of(path.route),
                        value.value,
                        List.of(),
                        List.of(),
                        Optional.of(graph.flowSiteId(expression)),
                        Optional.empty()));
                return new Eval(scalarValue(expression.type()), updated, events,
                        concat(target.effects, value.effects));
            }

            private Eval block(
                    TypedExpression expression,
                    Frame frame,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state) {
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState current = state;
                ValueAlternatives value = scalarValue(expression.type());
                ArrayList<SemanticFlowEvent> events = new ArrayList<>();
                ArrayList<EagerEffectWitness> effects = new ArrayList<>();
                for (TypedExpression child : expression.children()) {
                    Eval next = evaluate(child, frame, current);
                    current = next.state;
                    value = next.value;
                    events.addAll(next.events);
                    effects.addAll(next.effects);
                }
                return new Eval(retag(value, expression.type()), current, events, distinctEffects(effects));
            }

            private Eval conditional(
                    TypedExpression expression,
                    Frame frame,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state) {
                Eval predicate = evaluate(expression.children().getFirst(), frame, state);
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState thenState = predicate.state;
                List<SemanticFlowEvent> predicateEvents = predicate.events;
                if (expression.predicateBinding().isPresent()) {
                    DeclarationId binding = expression.predicateBinding().orElseThrow();
                    ValueAlternatives predicateValue = retag(
                            predicate.value, graph.contract(binding)
                                    .map(BindingContract::valueType)
                                    .orElse(predicate.value.alternatives().isEmpty()
                                            ? PrimitiveType.UNIT : predicate.value.alternatives().getFirst().type()));
                    thenState = bindOrReplace(thenState, binding, predicateValue, expression.span());
                }
                Eval thenBranch = evaluate(
                        expression.children().get(1), frame, thenState);
                Eval elseBranch = expression.children().size() > 2
                        ? evaluate(expression.children().get(2), frame, predicate.state)
                        : new Eval(scalarValue(PrimitiveType.UNIT), predicate.state, List.of(), List.of());
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState joined = thenBranch.state
                        .branchJoin(elseBranch.state);
                ArrayList<SemanticFlowEvent> events = new ArrayList<>();
                events.addAll(predicateEvents);
                events.addAll(thenBranch.events);
                events.addAll(elseBranch.events);
                ArrayList<EagerEffectWitness> effects = new ArrayList<>();
                effects.addAll(predicate.effects);
                effects.addAll(thenBranch.effects);
                effects.addAll(elseBranch.effects);
                if (expression.children().size() == 2) {
                    return new Eval(scalarValue(PrimitiveType.UNIT), joined, events,
                            distinctEffects(effects));
                }
                ValueAlternatives value = thenBranch.value.join(elseBranch.value);
                return new Eval(retag(value, expression.type()), joined, events,
                        distinctEffects(effects));
            }

            private Eval match(
                    TypedExpression expression,
                    Frame frame,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state) {
                TypedMatch match = expression.match().orElseThrow(() -> failure(
                        CallableSummaryResult.InternalFailure.Kind.INVALID_TYPED_EXPRESSION,
                        "match operation has no typed arm metadata", expression.span()));
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState continuation = state;
                ArrayList<SemanticFlowEvent> events = new ArrayList<>();
                ArrayList<EagerEffectWitness> effects = new ArrayList<>();
                if (match.subjectChild().isPresent()) {
                    Eval subject = evaluate(expression.children().get(
                            match.subjectChild().getAsInt()), frame, continuation);
                    continuation = subject.state;
                    events.addAll(subject.events);
                    effects.addAll(subject.effects);
                }
                ArrayList<io.mindspice.lyra.compiler.semantic.flow.BindingFlowState> resultStates =
                        new ArrayList<>();
                ValueAlternatives resultValues = ValueAlternatives.empty();
                for (TypedMatch.Arm arm : match.arms()) {
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState tested = continuation;
                    if (arm.patternChild().isPresent()) {
                        Eval pattern = evaluate(expression.children().get(
                                arm.patternChild().getAsInt()), frame, continuation);
                        tested = pattern.state;
                        events.addAll(pattern.events);
                        effects.addAll(pattern.effects);
                    }
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState selected = tested;
                    if (arm.guardChild().isPresent()) {
                        Eval guard = evaluate(expression.children().get(
                                arm.guardChild().getAsInt()), frame, tested);
                        selected = guard.state;
                        events.addAll(guard.events);
                        effects.addAll(guard.effects);
                        continuation = arm.wildcard()
                                ? guard.state : tested.branchJoin(guard.state);
                    } else {
                        continuation = tested;
                    }
                    Eval result = evaluate(expression.children().get(arm.resultChild()), frame, selected);
                    resultStates.add(result.state);
                    resultValues = resultValues.join(result.value);
                    events.addAll(result.events);
                    effects.addAll(result.effects);
                }
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState joined =
                        resultStates.getFirst();
                for (int index = 1; index < resultStates.size(); index++) {
                    joined = joined.branchJoin(resultStates.get(index));
                }
                return new Eval(retag(resultValues, expression.type()), joined, events,
                        distinctEffects(effects));
            }

            private Eval coalesce(
                    TypedExpression expression,
                    Frame frame,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state) {
                TypedExpression valueExpression = expression.children().getFirst();
                Eval value = evaluate(valueExpression, frame, state);
                Optional<TypedExpression> nilSource = nilLiteralSource(valueExpression);
                if (nilSource.isPresent() && valueExpression.type().isNilable()) {
                    TypedExpression source = nilSource.orElseThrow();
                    value = new Eval(
                            ValueAlternatives.singleton(ValueAlternative.nil(
                                    valueExpression.type(), new NilProvenance(
                                    graph.flowSiteId(source), source.span(),
                                    ProjectionPath.root()))),
                            value.state, value.events, value.effects);
                }
                Eval fallback = evaluate(expression.children().get(1), frame, value.state);
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState joined = value.state
                        .coalesceJoin(fallback.state);
                ArrayList<SemanticFlowEvent> events = new ArrayList<>();
                events.addAll(value.events);
                events.addAll(fallback.events);
                ValueAlternatives result = retag(
                        value.value.coalesceJoin(fallback.value), expression.type());
                return new Eval(result, joined, events,
                        distinctEffects(concat(value.effects, fallback.effects)));
            }

            private Eval lambdaExpression(
                    TypedExpression expression,
                    Frame frame,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state) {
                LambdaId id = expression.lambdaId().orElseThrow(() -> failure(
                        CallableSummaryResult.InternalFailure.Kind.INVALID_TYPED_EXPRESSION,
                        "lambda expression has no identity", expression.span()));
                TypedLambda lambda = lambdas.get(id);
                if (lambda == null) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                            "lambda expression links to an absent lambda", expression.span());
                }
                return lambdaValue(lambda, expression, frame, state);
            }

            private Eval lambdaValueForDeclaration(
                    TypedDeclaration declaration,
                    Frame frame,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state,
                    SourceSpan useSpan) {
                LambdaId lambdaId = declaration.initializerLambda().orElseThrow(() -> failure(
                        CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                        "predeclared function has no initializer lambda", useSpan));
                TypedLambda lambda = lambdas.get(lambdaId);
                if (lambda == null) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                            "predeclared function links to an absent lambda", useSpan);
                }
                TypedExpression initializer = declaration.initializer().orElseThrow(() -> failure(
                        CallableSummaryResult.InternalFailure.Kind.INVALID_TYPED_EXPRESSION,
                        "predeclared function has no initializer expression", useSpan));
                return lambdaValue(lambda, initializer, frame, state);
            }

            private Eval lambdaValue(
                    TypedLambda lambda,
                    TypedExpression expression,
                    Frame frame,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState input) {
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state = input;
                Map<DeclarationId, ValueAlternatives> immutable = new TreeMap<>();
                Map<DeclarationId, ValueAlternatives> cells = new TreeMap<>();
                ArrayList<SemanticFlowEvent> events = new ArrayList<>();
                ArrayList<EagerEffectWitness> effects = new ArrayList<>();
                for (CaptureId captureId : lambda.captures()) {
                    ResolvedCapture capture = captures.get(captureId);
                    if (capture == null) {
                        throw failure(
                                CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                                "lambda capture is absent from the resolved graph", expression.span());
                    }
                    if (resolvedDeclarations.get(capture.declarationId()) != null
                            && resolvedDeclarations.get(capture.declarationId()).kind()
                            == DeclarationKind.IMPORT_MODULE) {
                        continue;
                    }
                    DeclarationId capturedDeclaration = capture.declarationId();
                    DeclarationId declaration = originDeclaration(capturedDeclaration);
                    Lookup lookup;
                    Optional<io.mindspice.lyra.compiler.semantic.flow.BindingFlowValue>
                            currentCapture = state.binding(declaration);
                    if (currentCapture.isPresent()) {
                        lookup = new Lookup(
                                currentCapture.orElseThrow().alternatives(),
                                List.of(), false);
                    } else {
                        ResolvedDeclaration missing = resolvedDeclarations.get(declaration);
                        if (missing != null
                                && missing.kind() == DeclarationKind.PARAMETER) {
                            throw failure(
                                    CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                                    "lambda creation has no active parameter capture: lambda="
                                            + lambda.id() + " capture=" + capture.id()
                                            + " declaration=" + declaration
                                            + " expression=" + expression.span()
                                            + " bindings=" + state.bindings().keySet(),
                                    capture.span());
                        }
                        lookup = ensure(frame, declaration, capture.span());
                    }
                    state = bindOrReplace(state, declaration, lookup.value, capture.span());
                    ModuleId capturedModule = declarationModule(declaration);
                    ValueAlternatives capturedValue = attachableBoundaryValues(
                            lookup.value, declaration, capture.span());
                    ArrayList<EagerEffectWitness> captureEffects = new ArrayList<>();
                    FlowSiteId captureSite = siteForCapture(capture);
                    if (!capturedModule.equals(frame.module.moduleId())) {
                        captureEffects.add(new EagerEffectWitness(
                                frame.module.moduleId(), capturedModule,
                                EagerEffectWitness.Kind.VALUE_READ,
                                capture.span(), Optional.of(declaration),
                                capture.references().stream().findFirst(), Optional.empty(),
                                List.of(capture.span()), List.of(), false,
                                Optional.of(captureSite), List.of(captureSite)));
                        capturedValue = imported(
                                capturedValue, capturedModule, declaration,
                                resolvedDeclarations.get(declaration) == null
                                        ? Optional.empty()
                                        : resolvedDeclarations.get(declaration).originExport(),
                                capture.span());
                    }
                    captureEffects.addAll(lookup.effects.stream()
                            .map(effect -> attribute(
                                    effect, frame.module.moduleId(), capture.span(), captureSite))
                            .toList());
                    captureEffects = new ArrayList<>(distinctEffects(captureEffects));
                    effects.addAll(captureEffects);
                    if (capture.mode() == CaptureMode.SHARED_MUTABLE_CELL) {
                        DeclarationId cell = capture.sharedCellId().orElseThrow();
                        cells.put(cell, capturedValue);
                    } else {
                        immutable.put(capturedDeclaration, capturedValue);
                    }
                    events.add(new SemanticFlowEvent(
                            SemanticFlowEvent.Kind.CAPTURE,
                            frame.module.moduleId(),
                            capture.span(),
                            Optional.empty(),
                            Optional.of(declaration),
                            Optional.empty(),
                            Optional.of(lambda.id()),
                            capture.references().stream().findFirst(),
                            Optional.empty(),
                            Optional.empty(),
                            capturedValue,
                            List.of(),
                            captureEffects,
                            Optional.of(siteForCapture(capture)),
                            Optional.of(capture.id())));
                }
                CallableFlow callable = CallableFlow.atRoot(
                        lambda.id(), immutable, cells,
                        Objects.requireNonNull(lambdaCreationSites.get(lambda.id()),
                                "lambda creation site"));
                CallableSummary summary = summaries.summary(lambda.id()).orElseThrow(() -> failure(
                        CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                        "created lambda has no solved ownership summary", expression.span()));
                Map<CaptureId, FormulaAlternatives> captureFormulas = captureFormulas(
                        callable, summary, state, frame.module.moduleId(), expression.span());
                List<OwnershipRequirement> creationRequirements =
                        materializeCreationOwnershipRequirements(
                                summary, captureFormulas, expression.span());
                rejectImportedOwnershipRequirements(
                        creationRequirements, frame.module.moduleId(),
                        diagnosticOrigins(List.of(), callable,
                                frame.module.moduleId()));
                ValueAlternatives value = ValueAlternatives.singleton(
                        ValueAlternative.of(expression.type(), List.of(), List.of(callable)));
                return new Eval(value, state, events, distinctEffects(effects));
            }

            private Eval loop(TypedExpression expression, Frame frame,
                              io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state) {
                Eval input = evaluate(expression.children().getFirst(), frame, state);
                Eval action = evaluate(expression.children().getLast(), frame, input.state);
                var head = action.state;
                var exits = action.state;
                ArrayList<SemanticFlowEvent> events = new ArrayList<>(input.events);
                events.addAll(action.events);
                ArrayList<EagerEffectWitness> effects = new ArrayList<>(input.effects);
                effects.addAll(action.effects);
                boolean conditionControlled = expression.kind() == TypedExpressionKind.WHILE;
                FunctionType actionType = (FunctionType) expression.children().getLast().type();
                List<ValueAlternatives> arguments = actionType.arity() == 0 ? List.of()
                        : List.of(scalarValue(actionType.parameterType(0)));
                for (int iteration = 1; ; iteration++) {
                    if (iteration > limits.maxFixedPointIterations()) throw failure(
                            CallableSummaryResult.InternalFailure.Kind.DOMAIN_LIMIT,
                            "callback loop exceeds the finite flow fixed-point budget", expression.span());
                    var continuing = head;
                    if (conditionControlled) {
                        Eval predicate = invokeLoopCallback(expression, input.value,
                                (FunctionType) expression.children().getFirst().type(), List.of(), frame, continuing);
                        continuing = predicate.state;
                        exits = iteration == 1 ? continuing : exits.join(continuing);
                        events.addAll(predicate.events);
                        effects.addAll(predicate.effects);
                    }
                    Eval body = invokeLoopCallback(expression, action.value, actionType, arguments, frame, continuing);
                    events.addAll(body.events);
                    effects.addAll(body.effects);
                    var next = head.join(body.state);
                    if (!conditionControlled) exits = next;
                    if (next.equals(head)) break;
                    head = next;
                }
                return new Eval(scalarValue(expression.type()), exits,
                        List.copyOf(new java.util.LinkedHashSet<>(events)), distinctEffects(effects));
            }

            private Eval invokeLoopCallback(TypedExpression expression, ValueAlternatives target,
                                            FunctionType function, List<ValueAlternatives> arguments, Frame frame,
                                            io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state) {
                var candidates = callableCandidates(refresh(target, state));
                if (candidates.isEmpty()) throw failure(
                        CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                        "callback loop has no recoverable callable fact", expression.span());
                FormulaAlternatives[] formulas = arguments.stream()
                        .map(value -> toFormulas(value, frame.module.moduleId(), expression.span()))
                        .toArray(FormulaAlternatives[]::new);
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState joined = null;
                ValueAlternatives values = ValueAlternatives.empty();
                ArrayList<SemanticFlowEvent> events = new ArrayList<>();
                ArrayList<EagerEffectWitness> effects = new ArrayList<>();
                for (CallableFlow candidate : candidates) {
                    // Range parameters are immutable primitives and cannot be write targets.
                    CallBranch branch = invokeCandidate(candidate, expression, List.of(), arguments,
                            formulas, frame, state, function, List.of());
                    joined = joined == null ? branch.state : joined.join(branch.state);
                    values = values.join(branch.value);
                    events.addAll(branch.events);
                    effects.addAll(branch.effects);
                }
                return new Eval(values, joined, events, distinctEffects(effects));
            }

            private Eval callableCall(
                    TypedExpression expression,
                    Frame frame,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state) {
                Eval target = evaluate(expression.children().getFirst(), frame, state);
                return invokeCall(expression, target, expression.children().subList(1, expression.children().size()),
                        frame);
            }

            private Eval directCall(
                    TypedExpression expression,
                    Frame frame,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state) {
                TypedLink link = expression.link().orElseThrow(() -> failure(
                        CallableSummaryResult.InternalFailure.Kind.INVALID_TYPED_EXPRESSION,
                        "direct call has no typed link", expression.span()));
                DeclarationId targetDeclaration = link.declarationId().orElseThrow(() -> failure(
                        CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                        "direct call has no target declaration", expression.span()));
                Lookup lookup = readValue(expression, frame, state, false);
                Eval target = new Eval(lookup.value, state, List.of(), lookup.effects);
                return invokeCall(expression, target, expression.children(), frame);
            }

            private Eval invokeCall(
                    TypedExpression expression,
                    Eval target,
                    List<TypedExpression> argumentExpressions,
                    Frame frame) {
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state = target.state;
                ArrayList<ValueAlternatives> argumentValues = new ArrayList<>();
                ArrayList<SemanticFlowEvent> events = new ArrayList<>(target.events);
                ArrayList<EagerEffectWitness> effects = new ArrayList<>(target.effects);
                for (TypedExpression argument : argumentExpressions) {
                    Eval evaluated = evaluate(argument, frame, state);
                    state = evaluated.state;
                    argumentValues.add(evaluated.value);
                    events.addAll(evaluated.events);
                    effects.addAll(evaluated.effects);
                }
                FunctionType function = expression.kind() == TypedExpressionKind.CALLABLE_CALL
                        ? expression.children().getFirst().type().withoutQualifiers()
                        instanceof FunctionType value ? value : null
                        : callTargetType(expression, frame);
                if (function == null) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                            "typed call has no function contract", expression.span());
                }
                if (argumentValues.size() != function.arity()) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.INCONSISTENT_SUMMARY,
                            "typed call argument count does not match its function contract",
                            expression.span());
                }
                rejectImportedMutableArguments(
                        function, argumentExpressions, argumentValues,
                        frame.module.moduleId());
                ValueAlternatives refreshed = refresh(target.value, state);
                List<CallableFlow> candidates = callableCandidates(refreshed);
                if (candidates.isEmpty()) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                            "well-typed call has no recoverable callable fact",
                            expression.span());
                }
                FormulaAlternatives[] actualFormulas = new FormulaAlternatives[argumentValues.size()];
                for (int index = 0; index < argumentValues.size(); index++) {
                    actualFormulas[index] = toFormulas(
                            argumentValues.get(index), frame.module.moduleId(), expression.span());
                }
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState joinedState = null;
                ValueAlternatives joinedValue = ValueAlternatives.empty();
                ArrayList<EagerEffectWitness> allCallEffects = new ArrayList<>();
                for (CallableFlow candidate : candidates) {
                    CallBranch branch = invokeCandidate(
                            candidate, expression, argumentExpressions,
                            argumentValues, actualFormulas, frame, state, function, effects);
                    joinedState = joinedState == null
                            ? branch.state : joinedState.join(branch.state);
                    joinedValue = joinedValue.join(branch.value);
                    allCallEffects.addAll(branch.effects);
                    events.addAll(branch.events);
                }
                if (joinedState == null || joinedValue.isEmpty()) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                            "callable alternatives produced no recoverable result fact",
                            expression.span());
                }
                return new Eval(
                        retag(joinedValue, expression.type()),
                        joinedState,
                        events,
                        distinctEffects(concat(effects, allCallEffects)));
            }

            private FunctionType callTargetType(TypedExpression expression, Frame frame) {
                Optional<DeclarationId> declaration = expression.link()
                        .flatMap(TypedLink::declarationId);
                if (declaration.isEmpty()) {
                    return null;
                }
                return graph.contract(originDeclaration(declaration.orElseThrow()))
                        .map(BindingContract::valueType)
                        .map(LyraType::withoutQualifiers)
                        .filter(FunctionType.class::isInstance)
                        .map(FunctionType.class::cast)
                        .orElseGet(() -> expression.link()
                                .flatMap(TypedLink::referenceId)
                                .flatMap(id -> Optional.ofNullable(references.get(id)))
                                .flatMap(TypedReference::type)
                                .map(LyraType::withoutQualifiers)
                                .filter(FunctionType.class::isInstance)
                                .map(FunctionType.class::cast)
                                .orElse(null));
            }

            private CallBranch invokeCandidate(
                    CallableFlow candidate,
                    TypedExpression call,
                    List<TypedExpression> argumentExpressions,
                    List<ValueAlternatives> argumentValues,
                    FormulaAlternatives[] actualFormulas,
                    Frame frame,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState baseline,
                    FunctionType function,
                    List<EagerEffectWitness> precedingEffects) {
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state = seedCells(
                        baseline, candidate, call.span());
                rememberPrefix(frame, state);
                ArrayList<EagerEffectWitness> effects = new ArrayList<>(precedingEffects);
                ArrayList<SemanticFlowEvent> events = new ArrayList<>();
                ArrayList<CapturedCellWrite> writes = new ArrayList<>();
                ModuleId targetModule = callableModule(candidate, call.span());
                boolean predecessorCallable = candidate.lambdaId().isPresent()
                        && !lambdas.containsKey(candidate.lambdaId().orElseThrow());
                if (!predecessorCallable
                        && !targetModule.equals(frame.module.moduleId())
                        && graph.module(targetModule).isPresent()) {
                    effects.add(callWitness(call, candidate, targetModule));
                }
                ValueAlternatives value;
                if (candidate.isIntrinsic()) {
                    value = scalarValue(call.type());
                } else {
                    LambdaId lambdaId = candidate.lambdaId().orElseThrow();
                    CallableSummary summary = summaries.summary(lambdaId).orElseThrow(() -> failure(
                            CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                            "recoverable callable has no solved summary: " + lambdaId,
                            call.span()));
                    Map<CaptureId, FormulaAlternatives> captureFormulas = captureFormulas(
                            candidate, summary, state, frame.module.moduleId(), call.span());
                    List<FormulaAlternatives> invocationArguments =
                            java.util.Arrays.asList(actualFormulas);
                    List<FormulaAlternatives> writeArguments = new ArrayList<>();
                    for (int index = 0; index < actualFormulas.length; index++) {
                        int parameterIndex = index;
                        boolean written = summary.parameterWrites().stream()
                                .anyMatch(write -> write.parameter() == parameterIndex);
                        writeArguments.add(written
                                ? writeTargetArgument(index, function.parameterType(index),
                                argumentExpressions.get(index), actualFormulas[index])
                                : actualFormulas[index]);
                    }
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState
                            declarationState = state;
                    Map<DeclarationId, FormulaAlternatives> declarationFormulas =
                            new TreeMap<>();
                    SummaryTransferResult transfer = summaries.invoke(
                            lambdaId, invocationArguments, writeArguments,
                            captureFormulas, call.span(), declaration ->
                            resolveCallableDeclaration(
                                    declaration, candidate, frame,
                                    declarationState, call, effects,
                                    declarationFormulas));
                    if (transfer instanceof SummaryTransferResult.Failure failure) {
                        throw new FlowFailure(failure.failure());
                    }
                    SummaryTransferResult.Success success = (SummaryTransferResult.Success) transfer;
                    Map<OwnershipOriginKey, Set<SourceSpan>> diagnosticOrigins =
                            diagnosticOrigins(
                                    argumentValues, candidate,
                                    frame.module.moduleId());
                    for (OwnershipRequirement requirement
                            : success.ownershipRequirements()) {
                        addCallBoundaryOrigins(
                                diagnosticOrigins,
                                fromOwnershipFormulas(
                                        requirement.value(),
                                        frame.module.moduleId(),
                                        requirement.span()),
                                call, frame.module.moduleId());
                    }
                    rejectImportedOwnershipRequirements(
                            success.ownershipRequirements(), frame.module.moduleId(),
                            diagnosticOrigins, Optional.of(targetModule), initializationEvaluation);
                    value = fromFormulas(
                            success.returnValue(), frame.module.moduleId(), call.span());
                    addCallBoundaryOrigins(
                            diagnosticOrigins, value, call,
                            frame.module.moduleId());
                    rememberTransferredOwnershipOrigins(
                            value, frame.module.moduleId(), diagnosticOrigins);
                    for (CapturedCellWrite write : success.writes()) {
                        ValueAlternatives replacement = fromFormulas(
                                write.value(), frame.module.moduleId(), write.span());
                        addCallBoundaryOrigins(
                                diagnosticOrigins, replacement, call,
                                frame.module.moduleId());
                        rememberTransferredOwnershipOrigins(
                                replacement, frame.module.moduleId(), diagnosticOrigins);
                        state = applyTransferredWrite(
                                state, summary, write, replacement,
                                argumentExpressions, call.span());
                        // Calls can fail/cancel between ordered writes, not only on return.
                        rememberPrefix(frame, state);
                        writes.add(write);
                    }
                    for (EagerEffectWitness effect : success.effects()) {
                        if (predecessorCallable || graph.module(effect.targetModule()).isEmpty()) {
                            // A predecessor callable may retain effects owned
                            // by its source-local generation. They are already
                            // certified in that predecessor and must not be
                            // reinterpreted as an initialization edge of this
                            // graph, whose module set cannot name that owner.
                            continue;
                        }
                        FlowSiteId callSite = graph.flowSiteId(call);
                        EagerEffectWitness attributed = attribute(
                                effect, frame.module.moduleId(), call.span(), callSite);
                        effects.addAll(expandEffect(
                                attributed, frame, state, call.span(), callSite));
                    }
                }
                events.add(new SemanticFlowEvent(
                        SemanticFlowEvent.Kind.CALL,
                        frame.module.moduleId(),
                        call.span(),
                        Optional.empty(),
                        Optional.empty(),
                        call.link().flatMap(TypedLink::declarationId),
                        candidate.lambdaId(),
                        call.link().flatMap(TypedLink::referenceId),
                        Optional.empty(),
                        Optional.empty(),
                        value,
                        writes,
                        effects,
                        Optional.of(graph.flowSiteId(call)),
                        Optional.empty()));
                return new CallBranch(value, state, writes, distinctEffects(effects), events);
            }

            private Optional<FormulaAlternatives> resolveCallableDeclaration(
                    ValueFormula.Declaration formula,
                    CallableFlow currentCallable,
                    Frame callerFrame,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state,
                    TypedExpression call,
                    List<EagerEffectWitness> effects,
                    Map<DeclarationId, FormulaAlternatives> resolvedFormulas) {
                DeclarationId requested = formula.declarationId();
                boolean callable = formula.type().withoutQualifiers() instanceof FunctionType;
                ResolvedDeclaration external = resolvedDeclarations.get(requested);
                if (!callable && (external == null || external.externalBinding().isEmpty())) {
                    return Optional.empty();
                }
                FormulaAlternatives cached = resolvedFormulas.get(requested);
                if (cached != null) {
                    return Optional.of(cached);
                }
                DeclarationId declaration = originDeclaration(requested);
                Optional<ModuleId> formulaOwner = formula.moduleId();
                boolean foreignOwner = formulaOwner.isPresent()
                        && (!modules.containsKey(formulaOwner.orElseThrow())
                        || graph.resolvedGraph().isRetained(formulaOwner.orElseThrow()));
                if (resolvedDeclarations.get(declaration) == null || foreignOwner) {
                    Optional<ValueAlternatives> retainedCell = state.sharedCell(declaration).or(() -> Optional.ofNullable(
                            currentCallable.sharedCellSnapshots().get(declaration)));
                    if (retainedCell.isPresent()) {
                        FormulaAlternatives resolved = toFormulas(
                                retainedCell.orElseThrow(), callerFrame.module.moduleId(), call.span());
                        try {
                            FormulaAlternatives selected = resolved.select(
                                    formula.declarationRoute());
                            if (selected.isEmpty()
                                    || !selected.rootType().withoutQualifiers().equals(
                                    formula.type().withoutQualifiers())
                                    || callable && !hasCanonicalCallableValue(selected)) {
                                return Optional.empty();
                            }
                        } catch (IllegalArgumentException incompatibleRoute) {
                            return Optional.empty();
                        }
                        resolvedFormulas.put(requested, resolved);
                        return Optional.of(resolved);
                    }
                    Optional<SessionFlowCertificate> certificate = graph.resolvedGraph()
                            .sessionFlowCertificate();
                    Optional<ValueAlternatives> retained = state.binding(declaration)
                            .map(value -> value.alternatives()).or(() -> certificate.flatMap(value -> value.value(declaration)));
                    if (retained.isPresent()) {
                        FormulaAlternatives resolved = toFormulas(
                                retained.orElseThrow(), callerFrame.module.moduleId(), call.span());
                        try {
                            FormulaAlternatives selected = resolved.select(
                                    formula.declarationRoute());
                            if (selected.isEmpty()
                                    || !selected.rootType().withoutQualifiers().equals(
                                    formula.type().withoutQualifiers())
                                    || callable && !hasCanonicalCallableValue(selected)) {
                                return Optional.empty();
                            }
                        } catch (IllegalArgumentException incompatibleRoute) {
                            return Optional.empty();
                        }
                        resolvedFormulas.put(requested, resolved);
                        return Optional.of(resolved);
                    }
                }
                ModuleId owner = formulaOwner.orElseGet(
                        () -> declarationModule(declaration));
                Frame ownerFrame = frame(owner);
                Lookup lookup;
                if (owner.equals(callerFrame.module.moduleId())
                        && state.binding(declaration).isPresent()) {
                    lookup = new Lookup(
                            state.requireBinding(declaration).alternatives(),
                            List.of(), false);
                } else if (currentCallable.lambdaId()
                        .flatMap(graph.resolvedGraph()::lambda)
                        .flatMap(ResolvedLambda::ownerDeclaration)
                        .map(value -> originDeclaration(value))
                        .filter(declaration::equals).isPresent()) {
                    CallableFlow self = currentCallable.withRoute(
                            ProjectionPath.root());
                    lookup = new Lookup(
                            ValueAlternatives.singleton(ValueAlternative.callable(
                                    formula.type(), self)), List.of(), false);
                } else {
                    lookup = ensure(ownerFrame, declaration, call.span());
                }
                FlowSiteId callSite = graph.flowSiteId(call);
                for (EagerEffectWitness effect : lookup.effects) {
                    effects.add(attribute(
                            effect, callerFrame.module.moduleId(), call.span(),
                            callSite));
                }
                FormulaAlternatives resolved = toFormulas(
                        lookup.value, callerFrame.module.moduleId(), call.span());
                try {
                    FormulaAlternatives selected = resolved.select(
                            formula.declarationRoute());
                    if (selected.isEmpty()
                            || !selected.rootType().withoutQualifiers().equals(
                            formula.type().withoutQualifiers())
                            || callable && !hasCanonicalCallableValue(selected)) {
                        return Optional.empty();
                    }
                } catch (IllegalArgumentException incompatibleRoute) {
                    return Optional.empty();
                }
                resolvedFormulas.put(requested, resolved);
                return Optional.of(resolved);
            }

            private boolean hasCanonicalCallableValue(
                    FormulaAlternatives alternatives) {
                return !alternatives.isEmpty()
                        && alternatives.formulas().stream().allMatch(formula -> {
                            if (formula instanceof ValueFormula.Lambda lambda) {
                                return summaries.summary(lambda.lambdaId()).isPresent();
                            }
                            if (formula instanceof ValueFormula.Declaration declaration) {
                                return summaries.lambdaForDeclaration(
                                        declaration.declarationId()).isPresent()
                                        || summaries.intrinsicDeclarations().containsKey(
                                        declaration.declarationId());
                            }
                            return formula instanceof ValueFormula.Scalar scalar
                                    && scalar.isNil();
                        });
            }

            private List<EagerEffectWitness> expandEffect(
                    EagerEffectWitness effect,
                    Frame currentFrame,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState currentState,
                    SourceSpan throughSpan,
                    FlowSiteId throughSite) {
                ArrayList<EagerEffectWitness> result = new ArrayList<>();
                result.add(effect);
                if (effect.kind() != EagerEffectWitness.Kind.VALUE_READ
                        || effect.targetDeclaration().isEmpty()) {
                    return result;
                }
                DeclarationId targetDeclaration = effect.targetDeclaration().orElseThrow();
                ResolvedDeclaration resolvedTarget = resolvedDeclarations.get(
                        targetDeclaration);
                if (resolvedTarget != null
                        && resolvedTarget.kind() == DeclarationKind.PARAMETER) {
                    // The callable/capture formulas already carry this value.
                    // A parameter read is not a module initializer lookup.
                    return result;
                }
                ModuleId targetModule = effect.targetModule();
                // An effect in retained code describes an operation on initialized producer state,
                // not permission to evaluate that declaration's original initializer again.
                if (graph.resolvedGraph().isRetained(targetModule)) return result;
                Frame targetFrame = frame(targetModule);
                Lookup lookup = targetFrame.module.moduleId().equals(currentFrame.module.moduleId())
                        && currentState.binding(effect.targetDeclaration().orElseThrow()).isPresent()
                        ? new Lookup(currentState.requireBinding(
                                targetDeclaration).alternatives(), List.of(), false)
                        : ensure(targetFrame, targetDeclaration, effect.effectSpan());
                for (EagerEffectWitness nested : lookup.effects) {
                    result.add(attribute(
                            nested, currentFrame.module.moduleId(), throughSpan, throughSite));
                }
                return distinctEffects(result);
            }

            private TargetPath targetForAggregateOrigin(
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state,
                    CapturedCellWrite write) {
                for (Map.Entry<DeclarationId,
                        io.mindspice.lyra.compiler.semantic.flow.BindingFlowValue> entry
                        : state.bindings().entrySet()) {
                    for (var fact : facts(entry.getValue().alternatives())) {
                        if (fact.identity().originDeclaration().equals(write.declarationId())) {
                            return new TargetPath(
                                    entry.getKey(), fact.route().compose(write.route()));
                        }
                    }
                }
                throw failure(
                        CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                        "captured aggregate write has no caller identity occurrence",
                        write.span());
            }

            private DeclarationId declarationForCell(
                    CallableSummary summary,
                    DeclarationId cell,
                    DeclarationId fallback,
                    SourceSpan span) {
                return summary.captures().stream()
                        .filter(capture -> capture.sharedCellId()
                                .filter(cell::equals).isPresent())
                        .map(CallableSummary.CapturePlaceholder::declarationId)
                        .map(Engine.this::originDeclaration)
                        .findFirst()
                        .orElseGet(() -> fallback);
            }

            private BindingContract transferredCellContract(
                    CallableSummary summary,
                    CapturedCellWrite write,
                    DeclarationId declaration) {
                Optional<BindingContract> summaryContract = summary.captures().stream()
                        .filter(value -> value.sharedCellId()
                                .filter(cell -> write.sharedCellId().filter(cell::equals).isPresent())
                                .isPresent())
                        .map(CallableSummary.CapturePlaceholder::contract)
                        .findFirst();
                if (summaryContract.isPresent()) {
                    return summaryContract.orElseThrow();
                }
                DeclarationId origin = originDeclaration(declaration);
                Optional<BindingContract> graphContract = graph.contract(origin);
                if (graphContract.isPresent()) {
                    return graphContract.orElseThrow();
                }
                Optional<BindingContract> predecessorContract = graph.resolvedGraph()
                        .sessionFlowCertificate()
                        .flatMap(certificate -> certificate.boundaryState().binding(origin))
                        .map(io.mindspice.lyra.compiler.semantic.flow.BindingFlowValue::contract);
                if (predecessorContract.isPresent()) {
                    return predecessorContract.orElseThrow();
                }
                throw failure(
                        CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                        "captured write has no certified binding contract: " + declaration,
                        write.span());
            }

            private io.mindspice.lyra.compiler.semantic.flow.BindingFlowState seedCells(
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState baseline,
                    CallableFlow candidate,
                    SourceSpan span) {
                if (candidate.lambdaId().isEmpty()) {
                    return baseline;
                }
                CallableSummary summary = summaries.summary(
                        candidate.lambdaId().orElseThrow()).orElseThrow(() -> failure(
                        CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                        "callable shared-cell snapshot has no certified summary: "
                                + candidate.lambdaId().orElseThrow(), span));
                Map<DeclarationId, CallableSummary.CapturePlaceholder> sharedCaptures =
                        retainedSharedCaptures(summary, candidate, span);
                if (candidate.sharedCellSnapshots().isEmpty()) {
                    return baseline;
                }
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState result = baseline;
                for (Map.Entry<DeclarationId, ValueAlternatives> entry
                        : candidate.sharedCellSnapshots().entrySet()) {
                    DeclarationId cell = entry.getKey();
                    CallableSummary.CapturePlaceholder capture = sharedCaptures.get(cell);
                    ValueAlternatives snapshot = Objects.requireNonNull(
                            entry.getValue(), "retained shared-cell snapshot");
                    requireRetainedCaptureType(capture, snapshot, span);
                    result = result.replaceSharedCell(cell, snapshot);
                    DeclarationId declaration = originDeclaration(capture.declarationId());
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowValue existing =
                            result.bindings().get(declaration);
                    if (existing != null) {
                        if (!existing.contract().equals(capture.contract())) {
                            throw failure(
                                    CallableSummaryResult.InternalFailure.Kind.INCONSISTENT_SUMMARY,
                                    "retained callable cell contract disagrees with the current "
                                            + "flow binding: " + declaration,
                                    span);
                        }
                        result = result.replaceWholeBinding(declaration, snapshot);
                    } else {
                        // The declaration may have been lexically replaced in
                        // the current generation and therefore is absent from
                        // its resolved graph.  Use the producer-certified
                        // capture contract rather than fabricating a current
                        // TypedLambda or asking the current graph for a
                        // contract it cannot own.
                        result = result.bind(declaration, capture.contract(), snapshot);
                    }
                }
                return result;
            }

            /**
             * Resolves retained cells from the producer-certified summary.  A
             * prior-generation lambda is intentionally absent from this
             * graph's typed-lambda map; reconstructing a TypedLambda here
             * would fabricate source ownership and would lose its certified
             * capture contract.  The summary placeholders are the exact
             * immutable contract transported with the retained callable.
             */
            private Map<DeclarationId, CallableSummary.CapturePlaceholder>
            retainedSharedCaptures(
                    CallableSummary summary,
                    CallableFlow candidate,
                    SourceSpan span) {
                TreeMap<DeclarationId, CallableSummary.CapturePlaceholder> result = new TreeMap<>();
                for (CallableSummary.CapturePlaceholder capture : summary.captures()) {
                    if (capture.isSharedCell()) {
                        result.put(capture.cellId().orElseThrow(), capture);
                    }
                }
                if (!result.keySet().equals(candidate.sharedCellSnapshots().keySet())) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.INCONSISTENT_SUMMARY,
                            "retained callable shared-cell coverage disagrees with its certified "
                                    + "capture contract: lambda=" + summary.lambdaId()
                                    + " expected=" + result.keySet()
                                    + " actual=" + candidate.sharedCellSnapshots().keySet(),
                            span);
                }
                return result;
            }

            private void requireRetainedCaptureType(
                    CallableSummary.CapturePlaceholder capture,
                    ValueAlternatives snapshot,
                    SourceSpan span) {
                if (capture == null || snapshot.isEmpty()
                        || snapshot.alternatives().stream().anyMatch(value ->
                        !value.type().withoutQualifiers().equals(
                                capture.type().withoutQualifiers()))) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.INCONSISTENT_SUMMARY,
                            "retained callable shared-cell value disagrees with its certified "
                                    + "capture contract",
                            span);
                }
            }

            private Map<CaptureId, FormulaAlternatives> captureFormulas(
                    CallableFlow candidate,
                    CallableSummary summary,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state,
                    ModuleId module,
                    SourceSpan span) {
                TreeMap<CaptureId, FormulaAlternatives> result = new TreeMap<>();
                for (CallableSummary.CapturePlaceholder placeholder : summary.captures()) {
                    ValueAlternatives values;
                    if (placeholder.isSharedCell()) {
                        values = candidate.sharedCellSnapshots().get(placeholder.cellId().orElseThrow());
                        if (values == null) {
                            values = state.sharedCell(placeholder.cellId().orElseThrow()).orElse(null);
                        }
                    } else {
                        values = candidate.capturedValues().get(placeholder.declarationId());
                    }
                    if (values == null || values.isEmpty()) {
                        throw failure(
                                CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                                "callable capture has no creation-time value fact: "
                                        + placeholder.captureId(),
                                span);
                    }
                    result.put(placeholder.captureId(), toFormulas(values, module, span));
                }
                return result;
            }

            private FormulaAlternatives writeTargetArgument(
                    int index,
                    LyraType type,
                    TypedExpression argument,
                    FormulaAlternatives actual) {
                TargetPath target = targetPath(argument);
                if (target == null) {
                    return actual;
                }
                return FormulaAlternatives.singleton(new ValueFormula.Parameter(
                        originDeclaration(target.declaration), index,
                        ProjectionPath.root(), ProjectionPath.root(), type));
            }

            private io.mindspice.lyra.compiler.semantic.flow.BindingFlowState applyTransferredWrite(
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state,
                    CallableSummary summary,
                    CapturedCellWrite write,
                    ValueAlternatives replacement,
                    List<TypedExpression> argumentExpressions,
                    SourceSpan callSpan) {
                if (write.isDeclarationWrite()) {
                    DeclarationId declaration = write.declarationId();
                    if (state.binding(declaration).isEmpty()) {
                        var prior = graph.resolvedGraph().sessionFlowCertificate()
                                .flatMap(certificate -> certificate.boundaryState().binding(declaration))
                                .orElseThrow(() -> failure(
                                        CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                                        "retained storage write has no certified binding", write.span()));
                        state = state.bind(declaration, prior.contract(), prior.alternatives());
                    }
                    if (write.isWhole() && !state.requireBinding(declaration).contract().isMutable()) {
                        throw failure(CallableSummaryResult.InternalFailure.Kind.INCONSISTENT_SUMMARY,
                                "retained storage write targets an immutable binding", write.span());
                    }
                    return applyUpdate(state, new TargetPath(declaration, write.route()), replacement, write.span());
                }
                if (write.isCaptureWrite()) {
                    TargetPath target = write.isCaptureCellWrite()
                            ? new TargetPath(declarationForCell(
                            summary, write.sharedCellId().orElseThrow(),
                            write.declarationId(), write.span()), write.route())
                            : targetForAggregateOrigin(state, write);
                    DeclarationId declaration = target.declaration;
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState seeded = state;
                    if (seeded.binding(declaration).isEmpty()) {
                        BindingContract contract = transferredCellContract(
                                summary, write, declaration);
                        ValueAlternatives initial = write.sharedCellId()
                                .flatMap(state::sharedCell)
                                .orElseGet(() -> scalarValue(contract.valueType()));
                        seeded = seeded.bind(declaration, contract, initial);
                    }
                    if (write.isCaptureCellWrite()
                            && !seeded.requireBinding(declaration).contract().equals(
                            transferredCellContract(summary, write, declaration))) {
                        throw failure(
                                CallableSummaryResult.InternalFailure.Kind.INCONSISTENT_SUMMARY,
                                "captured write binding contract disagrees with its certified cell",
                                write.span());
                    }
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState updated = applyUpdate(
                            seeded, target, replacement, write.span());
                    return write.isCaptureCellWrite()
                            ? updated.replaceSharedCell(write.sharedCellId().orElseThrow(),
                            updated.requireBinding(declaration).alternatives())
                            : updated;
                }
                int index = write.parameter();
                if (index < 0 || index >= argumentExpressions.size()) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.INCONSISTENT_SUMMARY,
                            "transferred parameter write has no caller argument", callSpan);
                }
                TargetPath target = targetPath(argumentExpressions.get(index));
                if (target == null) {
                    return state;
                }
                return applyUpdate(
                        state,
                        new TargetPath(target.declaration, target.route.compose(write.route())),
                        replacement,
                        write.span());
            }

            private Eval member(
                    TypedExpression expression,
                    Frame frame,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state) {
                Eval receiver = evaluate(expression.children().getFirst(), frame, state);
                if (expression.tupleIndex().isPresent()) {
                    ProjectionPath route = ProjectionPath.tupleMember(
                            expression.tupleIndex().orElseThrow().intValueExact());
                    return new Eval(selectOrScalar(receiver.value, route, expression.type()),
                            receiver.state, receiver.events, receiver.effects);
                }
                return new Eval(scalarValue(expression.type()), receiver.state,
                        receiver.events, receiver.effects);
            }

            private Eval index(
                    TypedExpression expression,
                    Frame frame,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state) {
                Eval receiver = evaluate(expression.children().getFirst(), frame, state);
                Eval index = evaluate(expression.children().get(1), frame, receiver.state);
                if (expression.children().getFirst().type().withoutQualifiers() == PrimitiveType.STRING) {
                    return new Eval(scalarValue(expression.type()), index.state,
                            concat(receiver.events, index.events),
                            distinctEffects(concat(receiver.effects, index.effects)));
                }
                ProjectionPath route = ProjectionPath.of(indexStep(expression.children().get(1)));
                return new Eval(selectOrScalar(receiver.value, route, expression.type()),
                        index.state,
                        concat(receiver.events, index.events),
                        distinctEffects(concat(receiver.effects, index.effects)));
            }

            private Eval array(
                    TypedExpression expression,
                    Frame frame,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state) {
                if (!(expression.type().withoutQualifiers() instanceof ArrayType arrayType)) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.INVALID_TYPED_EXPRESSION,
                            "array literal has no array type", expression.span());
                }
                List<PartialValue> combinations = List.of(
                        new PartialValue(List.of(), List.of(), List.of()));
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState current = state;
                ArrayList<SemanticFlowEvent> events = new ArrayList<>();
                ArrayList<EagerEffectWitness> effects = new ArrayList<>();
                for (int index = 0; index < expression.children().size(); index++) {
                    TypedExpression child = expression.children().get(index);
                    Eval evaluated = evaluate(child, frame, current);
                    current = evaluated.state;
                    events.addAll(evaluated.events);
                    effects.addAll(evaluated.effects);
                    ArrayList<PartialValue> next = new ArrayList<>();
                    for (PartialValue prefix : combinations) {
                        for (ValueAlternative member : evaluated.value.alternatives()) {
                            ProjectionPath route = ProjectionPath.arrayElement(index);
                            ArrayList<io.mindspice.lyra.compiler.semantic.flow.AggregateIdentityFact> facts =
                                    new ArrayList<>(prefix.facts);
                            facts.addAll(member.aggregateIdentities().stream()
                                    .map(fact -> fact.prefixedBy(route)).toList());
                            ArrayList<CallableFlow> callables = new ArrayList<>(prefix.callables);
                            callables.addAll(member.callableFlows().stream()
                                    .map(callable -> callable.prefixedBy(route)).toList());
                            ArrayList<NilProvenance> nils = new ArrayList<>(prefix.nils);
                            nils.addAll(member.nilProvenance().stream()
                                    .map(nil -> nil.prefixedBy(route)).toList());
                            next.add(new PartialValue(facts, callables, nils));
                        }
                    }
                    requireCombinationCount(next.size(), child.span());
                    combinations = next;
                }
                DeclarationId allocation = arrayAllocationIds.get(expression);
                if (allocation == null) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.INVALID_TYPED_EXPRESSION,
                            "array allocation has no canonical identity", expression.span());
                }
                ScopeId scope = graph.flowScopeId(expression);
                OwnershipWitness witness = OwnershipWitness.local(
                                frame.module.moduleId(), allocation, scope, expression.span())
                        .withOriginSite(graph.flowSiteId(expression));
                ArrayList<ValueAlternative> values = new ArrayList<>();
                if (combinations.isEmpty()) {
                    combinations = List.of(new PartialValue(
                            List.of(), List.of(), List.of()));
                }
                ArrayIdentity identity = ArrayIdentity.localAllocation(
                        frame.module.moduleId(), allocation, arrayType);
                for (PartialValue combination : combinations) {
                    ArrayList<io.mindspice.lyra.compiler.semantic.flow.AggregateIdentityFact> facts =
                            new ArrayList<>(combination.facts);
                    facts.add(new io.mindspice.lyra.compiler.semantic.flow.AggregateIdentityFact(
                            identity, ProjectionPath.root(), witness));
                    values.add(ValueAlternative.of(
                            arrayType, facts, combination.callables, combination.nils));
                }
                return new Eval(new ValueAlternatives(values), current, events,
                        distinctEffects(effects));
            }

            private Eval tuple(
                    TypedExpression expression,
                    Frame frame,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state) {
                if (!(expression.type().withoutQualifiers() instanceof TupleType tupleType)) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.INVALID_TYPED_EXPRESSION,
                            "tuple literal has no tuple type", expression.span());
                }
                List<PartialValue> combinations = List.of(
                        new PartialValue(List.of(), List.of(), List.of()));
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState current = state;
                ArrayList<SemanticFlowEvent> events = new ArrayList<>();
                ArrayList<EagerEffectWitness> effects = new ArrayList<>();
                for (int index = 0; index < expression.children().size(); index++) {
                    Eval evaluated = evaluate(expression.children().get(index), frame, current);
                    current = evaluated.state;
                    events.addAll(evaluated.events);
                    effects.addAll(evaluated.effects);
                    ArrayList<PartialValue> next = new ArrayList<>();
                    ProjectionPath route = ProjectionPath.tupleMember(index);
                    for (PartialValue prefix : combinations) {
                        for (ValueAlternative member : evaluated.value.alternatives()) {
                            ArrayList<io.mindspice.lyra.compiler.semantic.flow.AggregateIdentityFact> facts =
                                    new ArrayList<>(prefix.facts);
                            facts.addAll(member.aggregateIdentities().stream()
                                    .map(fact -> fact.prefixedBy(route)).toList());
                            ArrayList<CallableFlow> callables = new ArrayList<>(prefix.callables);
                            callables.addAll(member.callableFlows().stream()
                                    .map(callable -> callable.prefixedBy(route)).toList());
                            ArrayList<NilProvenance> nils = new ArrayList<>(prefix.nils);
                            nils.addAll(member.nilProvenance().stream()
                                    .map(nil -> nil.prefixedBy(route)).toList());
                            next.add(new PartialValue(facts, callables, nils));
                        }
                    }
                    requireCombinationCount(next.size(), expression.children().get(index).span());
                    combinations = next;
                }
                ArrayList<ValueAlternative> values = new ArrayList<>();
                for (PartialValue combination : combinations) {
                    values.add(ValueAlternative.of(
                            tupleType, combination.facts, combination.callables,
                            combination.nils));
                }
                return new Eval(new ValueAlternatives(values), current, events,
                        distinctEffects(effects));
            }

            private Eval operator(
                    TypedExpression expression,
                    Frame frame,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state) {
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState continuing = state;
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState resultState = null;
                ArrayList<SemanticFlowEvent> events = new ArrayList<>();
                ArrayList<EagerEffectWitness> effects = new ArrayList<>();
                for (TypedExpression child : expression.children()) {
                    Eval evaluated = evaluate(child, frame, continuing);
                    events.addAll(evaluated.events);
                    effects.addAll(evaluated.effects);
                    continuing = evaluated.state;
                    resultState = resultState == null
                            ? continuing
                            : expression.kind() == TypedExpressionKind.SHORT_CIRCUIT
                            ? resultState.join(continuing)
                            : continuing;
                }
                return new Eval(scalarValue(expression.type()),
                        resultState == null ? state : resultState, events,
                        distinctEffects(effects));
            }

            private void requireCombinationCount(int count, SourceSpan span) {
                if (count > limits.maxFormulaAlternatives()) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.DOMAIN_LIMIT,
                            "aggregate combinations exceed the finite semantic domain: "
                                    + count + " > " + limits.maxFormulaAlternatives(), span);
                }
            }

            private Eval unary(
                    TypedExpression expression,
                    Frame frame,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state) {
                Eval child = evaluate(expression.children().getFirst(), frame, state);
                Optional<TypedExpression> nilSource = nilLiteralSource(
                        expression.children().getFirst());
                if (nilSource.isPresent() && expression.type().isNilable()) {
                    TypedExpression source = nilSource.orElseThrow();
                    ValueAlternatives nil = ValueAlternatives.singleton(ValueAlternative.nil(
                            expression.type(), new NilProvenance(
                            graph.flowSiteId(source), source.span(), ProjectionPath.root())));
                    return new Eval(nil, child.state, child.events, child.effects);
                }
                if (expression.kind() == TypedExpressionKind.CONVERSION
                        && !child.value.alternatives().isEmpty()
                        && !child.value.alternatives().getFirst().type().withoutQualifiers()
                        .equals(expression.type().withoutQualifiers())
                        && child.value.alternatives().stream()
                        .allMatch(value -> value.nilProvenance().isEmpty())) {
                    return new Eval(scalarValue(expression.type()), child.state,
                            child.events, child.effects);
                }
                return new Eval(retag(child.value, expression.type()), child.state,
                        child.events, child.effects);
            }

            private io.mindspice.lyra.compiler.semantic.flow.BindingFlowState applyUpdate(
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state,
                    TargetPath target,
                    ValueAlternatives replacement,
                    SourceSpan span) {
                DeclarationId declaration = originDeclaration(target.declaration);
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowValue currentValue =
                        state.bindings().get(declaration);
                if (currentValue == null) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                            "mutation target has no current flow value", span);
                }
                ValueAlternatives current = currentValue.alternatives();
                LyraType routeType;
                try {
                    routeType = ValueAlternative.typeAt(
                            current.alternatives().getFirst().type(), target.route);
                } catch (RuntimeException failure) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.INCONSISTENT_SUMMARY,
                            "mutation route is not compatible with its binding", span);
                }
                ValueAlternatives normalized = retag(replacement, routeType);
                if (target.route.isRoot()) {
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState updated =
                            state.replaceWholeBinding(declaration, normalized);
                    return synchronizeCell(updated, declaration, normalized);
                }
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState direct = target.route.containsWildcard()
                        ? state.replaceUnknownIndex(declaration, target.route, normalized)
                        : state.replaceExactRoute(declaration, target.route, normalized);
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState updated =
                        propagateSharedIdentity(state, direct, declaration, target.route, normalized, span);
                return synchronizeCell(updated, declaration,
                        updated.requireBinding(declaration).alternatives());
            }

            private io.mindspice.lyra.compiler.semantic.flow.BindingFlowState propagateSharedIdentity(
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState before,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState direct,
                    DeclarationId declaration,
                    ProjectionPath targetRoute,
                    ValueAlternatives replacement,
                    SourceSpan span) {
                if (!(targetRoute.steps().getLast() instanceof ProjectionStep.ArrayElement)
                        && !(targetRoute.steps().getLast()
                        instanceof ProjectionStep.UnknownArrayElement)) {
                    return direct;
                }
                ProjectionPath targetContainer = ProjectionPath.of(
                        targetRoute.steps().subList(0, targetRoute.depth() - 1));
                List<IdentityOccurrence> targetOccurrences = occurrences(
                        before, declaration, targetContainer);
                if (targetOccurrences.isEmpty()) {
                    return direct;
                }
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState result = direct;
                for (IdentityOccurrence selected : targetOccurrences) {
                    ProjectionPath relative = targetRoute.suffix(selected.route.depth());
                    ArrayIdentity identity = selected.identity;
                    for (Map.Entry<DeclarationId,
                            io.mindspice.lyra.compiler.semantic.flow.BindingFlowValue> entry
                            : before.bindings().entrySet()) {
                        for (var fact : facts(entry.getValue().alternatives())) {
                            boolean mayAlias = fact.identity() instanceof ArrayIdentity.SessionOrigin
                                    && identity instanceof ArrayIdentity.SessionOrigin
                                    && fact.identity().arrayType().equals(identity.arrayType());
                            if (!fact.identity().equals(identity) && !mayAlias) {
                                continue;
                            }
                            ProjectionPath route = fact.route().compose(relative);
                            try {
                                LyraType type = ValueAlternative.typeAt(
                                        entry.getValue().alternatives().alternatives().getFirst().type(), route);
                                ValueAlternatives value = retag(replacement, type);
                                var replaced = route.containsWildcard()
                                        ? result.replaceUnknownIndex(entry.getKey(), route, value)
                                        : result.replaceExactRoute(entry.getKey(), route, value);
                                // Different external routes can refer to the
                                // same live array, but are not must-alias facts.
                                result = mayAlias && !fact.identity().equals(identity)
                                        ? result.join(replaced) : replaced;

                            } catch (IllegalArgumentException ignored) {
                                // A sibling with a different static shape is
                                // not an occurrence of this identity route.
                            }
                        }
                    }
                }
                return result;
            }

            private List<IdentityOccurrence> occurrences(
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state,
                    DeclarationId declaration,
                    ProjectionPath container) {
                ArrayList<IdentityOccurrence> result = new ArrayList<>();
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowValue binding =
                        state.bindings().get(originDeclaration(declaration));
                if (binding == null) {
                    return result;
                }
                for (var fact : facts(binding.alternatives())) {
                    if (fact.route().isExactPrefixOf(container)
                            || fact.route().depth() == container.depth()
                            && fact.route().overlaps(container)) {
                        result.add(new IdentityOccurrence(fact.identity(), fact.route()));
                    }
                }
                return result;
            }

            private io.mindspice.lyra.compiler.semantic.flow.BindingFlowState synchronizeCell(
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state,
                    DeclarationId declaration,
                    ValueAlternatives value) {
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState result = state.sharedCell(declaration).isPresent()
                        ? state.replaceSharedCell(declaration, value) : state;
                for (CaptureBinding capture : sharedCaptures.getOrDefault(declaration, List.of())) {
                    result = result.replaceSharedCell(capture.cellId(), value);
                }
                return result;
            }

            private TargetPath targetPath(TypedExpression expression) {
                if (expression.kind() == TypedExpressionKind.REFERENCE) {
                    return expression.link().flatMap(TypedLink::declarationId)
                            .map(id -> new TargetPath(id, ProjectionPath.root())).orElse(null);
                }
                if (expression.kind() == TypedExpressionKind.INDEX_ACCESS
                        && expression.children().size() == 2) {
                    TargetPath parent = targetPath(expression.children().getFirst());
                    return parent == null ? null : new TargetPath(
                            parent.declaration,
                            parent.route.compose(ProjectionPath.of(
                                    indexStep(expression.children().get(1)))));
                }
                if (expression.kind() == TypedExpressionKind.MEMBER_ACCESS
                        && expression.tupleIndex().isPresent()) {
                    TargetPath parent = targetPath(expression.children().getFirst());
                    return parent == null ? null : new TargetPath(
                            parent.declaration,
                            parent.route.compose(ProjectionPath.tupleMember(
                                    expression.tupleIndex().orElseThrow().intValueExact())));
                }
                if ((expression.kind() == TypedExpressionKind.CONVERSION
                        || expression.kind() == TypedExpressionKind.NARROWING)
                        && !expression.children().isEmpty()) {
                    return targetPath(expression.children().getFirst());
                }
                return null;
            }

            private ProjectionStep indexStep(TypedExpression index) {
                if (index.literal().orElse(null)
                        instanceof TypedLiteralValue.IntegerValue integer) {
                    BigInteger value = integer.exactValue().integerValue();
                    if (value.signum() >= 0 && value.bitLength() <= 31) {
                        return ProjectionStep.arrayElement(value.intValue());
                    }
                }
                return ProjectionStep.unknownArrayElement();
            }

            private ValueAlternatives selectOrScalar(
                    ValueAlternatives values,
                    ProjectionPath route,
                    LyraType type) {
                try {
                    ValueAlternatives selected = values.select(route);
                    return selected.isEmpty() ? scalarValue(type) : retag(selected, type);
                } catch (IllegalArgumentException ignored) {
                    return scalarValue(type);
                }
            }

            private List<CallableFlow> callableCandidates(ValueAlternatives values) {
                TreeSet<CallableFlow> result = new TreeSet<>();
                for (ValueAlternative value : values) {
                    result.addAll(value.callableFlows());
                }
                return List.copyOf(result);
            }

            private ValueAlternatives refresh(
                    ValueAlternatives values,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state) {
                return new ValueAlternatives(values.alternatives().stream()
                        .map(value -> ValueAlternative.of(
                                value.type(),
                                value.aggregateIdentities(),
                                value.callableFlows().stream()
                                        .map(callable -> refreshCallable(callable, state))
                                        .toList(),
                                value.nilProvenance()))
                        .toList());
            }

            private CallableFlow refreshCallable(
                    CallableFlow callable,
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state) {
                TreeMap<DeclarationId, ValueAlternatives> captured = new TreeMap<>();
                callable.capturedValues().forEach((declaration, value) ->
                        captured.put(declaration, refresh(value, state)));
                TreeMap<DeclarationId, ValueAlternatives> cells = new TreeMap<>();
                callable.sharedCellSnapshots().forEach((cell, value) ->
                        cells.put(cell, state.sharedCell(cell).orElse(refresh(value, state))));
                return new CallableFlow(
                        callable.lambdaId(), callable.intrinsicDeclarationId(), callable.route(),
                        captured, cells, callable.creationSite());
            }

            private ModuleId callableModule(CallableFlow callable, SourceSpan span) {
                if (callable.isIntrinsic()) {
                    DeclarationId intrinsic = callable.intrinsicDeclarationId().orElseThrow();
                    ModuleId module = resolvedDeclarations.containsKey(intrinsic)
                            ? declarationModule(intrinsic)
                            : summaries.intrinsicDeclarations().get(intrinsic);
                    if (module == null) {
                        throw failure(
                                CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                                "intrinsic callable has no certified module: " + intrinsic, span);
                    }
                    return module;
                }
                LambdaId lambdaId = callable.lambdaId().orElseThrow(() -> failure(
                        CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                        "callable has neither intrinsic nor lambda identity", span));
                TypedLambda lambda = lambdas.get(lambdaId);
                if (lambda != null) {
                    return lambda.moduleId();
                }
                CallableSummary summary = summaries.summary(lambdaId).orElseThrow(() -> failure(
                        CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                        "callable links to an absent typed lambda: " + lambdaId, span));
                return summary.moduleId();
            }

            private EagerEffectWitness callWitness(
                    TypedExpression call,
                    CallableFlow callable,
                    ModuleId targetModule) {
                EagerEffectWitness.Kind kind = switch (call.kind()) {
                    case DIRECT_CALL -> EagerEffectWitness.Kind.DIRECT_CALL;
                    case NAMESPACE_DIRECT_CALL -> EagerEffectWitness.Kind.NAMESPACE_CALL;
                    case CALLABLE_CALL -> EagerEffectWitness.Kind.CALLABLE_CALL;
                    default -> EagerEffectWitness.Kind.CALLABLE_CALL;
                };
                FlowSiteId site = graph.flowSiteId(call);
                return new EagerEffectWitness(
                        ModuleId.fromSourceId(call.span().sourceId()), targetModule, kind,
                        call.span(), call.link().flatMap(TypedLink::declarationId),
                        call.link().flatMap(TypedLink::referenceId), callable.lambdaId(),
                        List.of(call.span()), List.of(), false,
                        Optional.of(site), List.of(site));
            }

            private io.mindspice.lyra.compiler.semantic.flow.BindingFlowState bind(
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state,
                    DeclarationId declaration,
                    ValueAlternatives values,
                    SourceSpan span) {
                BindingContract contract = graph.contract(declaration).orElseThrow(() -> failure(
                        CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                        "flow binding has no typed contract: " + declaration, span));
                ValueAlternatives normalized = retag(values, contract.valueType());
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState result = state.binding(declaration)
                        .isPresent()
                        ? state.replaceWholeBinding(declaration, normalized)
                        : state.bind(declaration, contract, normalized);
                return synchronizeCell(result, declaration, normalized);
            }

            private io.mindspice.lyra.compiler.semantic.flow.BindingFlowState bindOrReplace(
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state,
                    DeclarationId declaration,
                    ValueAlternatives values,
                    SourceSpan span) {
                return bind(state, originDeclaration(declaration), values, span);
            }

            private EagerEffectWitness attribute(
                    EagerEffectWitness effect,
                    ModuleId from,
                    SourceSpan prefix,
                    FlowSiteId prefixSite) {
                ArrayList<SourceSpan> path = new ArrayList<>();
                ArrayList<FlowSiteId> sites = new ArrayList<>();
                if (!effect.sourcePath().isEmpty() && !effect.sourcePath().getFirst().equals(prefix)) {
                    path.add(prefix);
                    sites.add(prefixSite);
                }
                path.addAll(effect.sourcePath());
                sites.addAll(effect.sourceSitePath());
                if (path.isEmpty()) {
                    path.add(prefix);
                    sites.add(prefixSite);
                }
                return new EagerEffectWitness(
                        from, effect.targetModule(), effect.kind(), effect.effectSpan(),
                        effect.targetDeclaration(), effect.referenceId(), effect.targetLambda(),
                        path, effect.callPath(), effect.recursive(),
                        effect.effectSite(), sites);
            }

            private void recordCycle(DeclarationId declaration, SourceSpan span) {
                LinkedHashSet<ModuleId> modulesInPath = new LinkedHashSet<>();
                ArrayList<SourceSpan> path = new ArrayList<>();
                boolean inCycle = false;
                for (ActiveDeclaration active : activeDeclarations) {
                    if (active.declaration().equals(declaration)) {
                        inCycle = true;
                    }
                    if (!inCycle) {
                        continue;
                    }
                    modulesInPath.add(active.module());
                    path.add(active.span());
                }
                modulesInPath.add(declarationModule(declaration));
                path.add(span);
                cycles.add(new EagerCycleWitness(
                        modulesInPath.stream().sorted().toList(), span,
                        Optional.of(declaration), path));
            }
        }

        private List<OwnershipRequirement> materializeCreationOwnershipRequirements(
                CallableSummary summary,
                Map<CaptureId, FormulaAlternatives> captures,
                SourceSpan span) {
            List<FormulaAlternatives> symbolicArguments = summary.parameters().stream()
                    .map(parameter -> FormulaAlternatives.singleton(
                            new ValueFormula.Parameter(
                                    parameter.declarationId(), parameter.index(),
                                    ProjectionPath.root(), ProjectionPath.root(),
                                    parameter.type())))
                    .toList();
            SummaryTransferResult transfer = summaries.invoke(
                    summary.lambdaId(), symbolicArguments, captures, span);
            if (transfer instanceof SummaryTransferResult.Success success) {
                return success.ownershipRequirements();
            }
            // Unknown higher-order parameters or recursive re-entry need an
            // actual invocation. Capture-only requirements remain
            // independently checkable at closure creation.
            return summary.ownershipRequirements().stream()
                    .map(requirement -> requirement.substituteCaptures(captures))
                    .flatMap(Optional::stream)
                    .toList();
        }

        private void rejectImportedAggregateMutation(
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state,
                TargetPath target,
                SourceSpan span,
                ModuleId module) {
            if (target.route().isRoot()) {
                return;
            }
            ProjectionStep last = target.route().steps().getLast();
            if (!(last instanceof ProjectionStep.ArrayElement)
                    && !(last instanceof ProjectionStep.UnknownArrayElement)) {
                return;
            }
            ProjectionPath container = ProjectionPath.of(
                    target.route().steps().subList(0, target.route().depth() - 1));
            io.mindspice.lyra.compiler.semantic.flow.BindingFlowValue binding =
                    state.bindings().get(originDeclaration(target.declaration()));
            if (binding == null) {
                return;
            }
            List<AggregateIdentityFact> imported = facts(binding.alternatives()).stream()
                    .filter(AggregateIdentityFact::isImported)
                    .filter(fact -> fact.route().isPrefixOf(container)
                            || fact.route().depth() == container.depth()
                            && fact.route().overlaps(container))
                    .filter(fact -> !initializationEvaluation
                            || !(fact.identity() instanceof ArrayIdentity.AttachableBoundary))
                    .distinct()
                    .sorted(AggregateIdentityFact.comparator())
                    .toList();
            boolean attachable = imported.stream().anyMatch(fact ->
                    fact.identity() instanceof ArrayIdentity.AttachableBoundary);
            rejectImportedOwnership(
                    imported, span, module, attachable
                            ? "a public mutable root binding may hold an externally written "
                            + "aggregate; its contents are read-only across attachment safe points"
                            : "an imported binding is read-only in the importing module",
                    attachable ? "public mutable root binding" : "imported aggregate binding");
        }

        private void rejectImportedMutableArguments(
                FunctionType function,
                List<TypedExpression> arguments,
                List<ValueAlternatives> values,
                ModuleId module) {
            int count = Math.min(
                    Math.min(arguments.size(), values.size()), function.arity());
            for (int index = 0; index < count; index++) {
                if (!function.parameterType(index).isMutable()) {
                    continue;
                }
                List<AggregateIdentityFact> imported = importedFacts(values.get(index)).stream()
                        .filter(fact -> !initializationEvaluation
                                || !(fact.identity() instanceof ArrayIdentity.AttachableBoundary))
                        .toList();
                rejectImportedOwnership(
                        imported, arguments.get(index).span(), module,
                        "an imported aggregate alias cannot grant local mutation permission");
            }
        }

        private void rejectImportedOwnershipRequirements(
                List<OwnershipRequirement> requirements,
                ModuleId module,
                Map<OwnershipOriginKey, Set<SourceSpan>> diagnosticOrigins) {
            rejectImportedOwnershipRequirements(
                    requirements, module, diagnosticOrigins, Optional.empty());
        }

        private void rejectImportedOwnershipRequirements(
                List<OwnershipRequirement> requirements,
                ModuleId module,
                Map<OwnershipOriginKey, Set<SourceSpan>> diagnosticOrigins,
                Optional<ModuleId> callableModule) {
            rejectImportedOwnershipRequirements(requirements, module, diagnosticOrigins,
                    callableModule, false);
        }

        private void rejectImportedOwnershipRequirements(
                List<OwnershipRequirement> requirements,
                ModuleId module,
                Map<OwnershipOriginKey, Set<SourceSpan>> diagnosticOrigins,
                Optional<ModuleId> callableModule,
                boolean initialization) {
            for (OwnershipRequirement requirement : requirements.stream()
                    .sorted(OwnershipRequirement::compareTo).toList()) {
                ValueAlternatives values = fromOwnershipFormulas(
                        requirement.value(), module, requirement.span());


                rememberTransferredOwnershipOrigins(
                        values, module, diagnosticOrigins);
                List<AggregateIdentityFact> imported = importedFacts(values).stream()
                        .filter(fact -> !initialization
                                || !(fact.identity() instanceof ArrayIdentity.AttachableBoundary))
                        .filter(fact -> requirement.kind() != OwnershipRequirement.Kind.AGGREGATE_MUTATION
                                || callableModule.isEmpty()
                                || !fact.identity().ownerModule().equals(callableModule.orElseThrow())
                                || fact.identity() instanceof ArrayIdentity.AttachableBoundary)
                        .toList();
                boolean attachableBoundary = imported.stream().anyMatch(fact ->
                        fact.identity() instanceof ArrayIdentity.AttachableBoundary);
                String message = attachableBoundary
                        ? "a public mutable root binding may hold an externally written "
                        + "aggregate; its contents are read-only across attachment safe points"
                        : requirement.kind()
                        == OwnershipRequirement.Kind.MUTABLE_ARGUMENT
                        ? "an imported aggregate alias cannot grant local mutation permission"
                        : "an imported binding is read-only in the importing module";
                rejectImportedOwnership(imported, requirement.span(), module, message,
                        attachableBoundary ? "public mutable root binding"
                                : "imported aggregate binding");
            }
        }

        private Map<OwnershipOriginKey, Set<SourceSpan>> diagnosticOrigins(
                List<ValueAlternatives> arguments,
                CallableFlow callable,
                ModuleId module) {
            TreeMap<OwnershipOriginKey, Set<SourceSpan>> result = new TreeMap<>();
            for (ValueAlternatives argument : arguments) {
                addDiagnosticOrigins(result, argument, module);
            }
            callable.capturedValues().values().forEach(values ->
                    addDiagnosticOrigins(result, values, module));
            callable.sharedCellSnapshots().values().forEach(values ->
                    addDiagnosticOrigins(result, values, module));
            return result;
        }

        private void addDiagnosticOrigins(
                Map<OwnershipOriginKey, Set<SourceSpan>> destination,
                ValueAlternatives values,
                ModuleId module) {
            for (AggregateIdentityFact fact : importedFacts(values)) {
                Set<SourceSpan> origins = destination.computeIfAbsent(
                        ownershipOriginKey(fact), ignored -> new TreeSet<>(
                                sourceSpanComparator()));
                origins.addAll(ownershipDiagnosticSpans(fact, module));
            }
        }

        private void addCallBoundaryOrigins(
                Map<OwnershipOriginKey, Set<SourceSpan>> destination,
                ValueAlternatives values,
                TypedExpression call,
                ModuleId module) {
            SourceSpan boundary = call.kind() == TypedExpressionKind.CALLABLE_CALL
                    && !call.children().isEmpty()
                    ? call.children().getFirst().span() : call.span();
            for (AggregateIdentityFact fact : importedFacts(values)) {
                if (!fact.identity().ownerModule().equals(module)) {
                    OwnershipOriginKey key = ownershipOriginKey(fact);
                    if (!destination.containsKey(key)) {
                        TreeSet<SourceSpan> origins = new TreeSet<>(
                                sourceSpanComparator());
                        origins.add(boundary);
                        destination.put(key, origins);
                    }
                }
            }
        }

        private void rememberTransferredOwnershipOrigins(
                ValueAlternatives values,
                ModuleId module,
                Map<OwnershipOriginKey, Set<SourceSpan>> originsByIdentity) {
            for (AggregateIdentityFact fact : importedFacts(values)) {
                Set<SourceSpan> origins = originsByIdentity.get(
                        ownershipOriginKey(fact));
                if (origins == null || origins.isEmpty()) {
                    continue;
                }
                ownershipOccurrenceOrigins.put(
                        new OwnershipOccurrence(
                                module, fact.identity(),
                                fact.ownershipWitness().useSpan()),
                        Set.copyOf(origins));
            }
        }

        private OwnershipOriginKey ownershipOriginKey(
                AggregateIdentityFact fact) {
            return new OwnershipOriginKey(
                    fact.identity().ownerModule(),
                    fact.identity().originDeclaration(),
                    fact.identity().arrayType());
        }

        private List<AggregateIdentityFact> importedFacts(ValueAlternatives values) {
            return facts(values).stream()
                    .filter(AggregateIdentityFact::isImported)
                    .distinct()
                    .sorted(AggregateIdentityFact.comparator())
                    .toList();
        }

        private void rejectImportedOwnership(
                List<AggregateIdentityFact> imported,
                SourceSpan span,
                ModuleId module,
                String message) {
            rejectImportedOwnership(imported, span, module, message,
                    "imported aggregate binding");
        }

        private void rejectImportedOwnership(
                List<AggregateIdentityFact> imported,
                SourceSpan span,
                ModuleId module,
                String message,
                String originLabel) {
            if (imported.isEmpty()) {
                return;
            }
            ArrayList<RelatedSpan> related = new ArrayList<>();
            for (AggregateIdentityFact fact : imported) {
                List<SourceSpan> origins = ownershipDiagnosticSpans(
                        fact, module);
                for (SourceSpan origin : origins) {
                    related.add(RelatedSpan.of(
                            origin, originLabel));
                }
            }
            related = new ArrayList<>(new LinkedHashSet<>(related));
            related.sort(Comparator
                    .comparing((RelatedSpan value) -> value.span().sourceId().value())
                    .thenComparingInt(value -> value.span().startOffset())
                    .thenComparingInt(value -> value.span().endOffset())
                    .thenComparing(RelatedSpan::label));
            throw new OwnershipFailure(Diagnostic.error(
                    CompilerDiagnosticCodes.RESOLVE_IMPORTED_MUTATION,
                    span, message, related));
        }

        private void rememberOwnershipDiagnosticOrigins(
                ValueAlternatives values,
                DeclarationId requested,
                ModuleId useModule,
                SourceSpan occurrence) {
            ResolvedDeclaration requestedDeclaration =
                    resolvedDeclarations.get(requested);
            for (AggregateIdentityFact fact : importedFacts(values)) {
                Set<SourceSpan> origins = ownershipDiagnosticOrigins
                        .computeIfAbsent(useModule, ignored -> new TreeMap<>())
                        .computeIfAbsent(fact.identity(), ignored -> new TreeSet<>(
                                sourceSpanComparator()));
                if (requestedDeclaration != null
                        && requestedDeclaration.imported()
                        && requestedDeclaration.moduleId().equals(useModule)) {
                    origins.add(requestedDeclaration.nameSpan());
                } else {
                    origins.addAll(importBindingsFor(fact, useModule));
                }
                if (origins.isEmpty()) {
                    origins.add(occurrence);
                }
                ownershipOccurrenceOrigins.put(
                        new OwnershipOccurrence(
                                useModule, fact.identity(),
                                fact.ownershipWitness().useSpan()),
                        Set.copyOf(origins));
            }
        }

        private List<SourceSpan> ownershipDiagnosticSpans(
                AggregateIdentityFact fact,
                ModuleId module) {
            Set<SourceSpan> occurrence = ownershipOccurrenceOrigins.get(
                    new OwnershipOccurrence(
                            module, fact.identity(),
                            fact.ownershipWitness().useSpan()));
            if (occurrence != null && !occurrence.isEmpty()) {
                return occurrence.stream().sorted(sourceSpanComparator()).toList();
            }
            Set<SourceSpan> remembered = ownershipDiagnosticOrigins
                    .getOrDefault(module, Map.of())
                    .get(fact.identity());
            if (remembered != null && !remembered.isEmpty()) {
                return List.copyOf(remembered);
            }
            List<SourceSpan> imports = importBindingsFor(fact, module);
            if (!imports.isEmpty()) {
                return imports;
            }
            return List.of(fact.ownershipWitness().useSpan());
        }

        private List<SourceSpan> importBindingsFor(
                AggregateIdentityFact fact,
                ModuleId module) {
            return resolvedDeclarations.values().stream()
                    .filter(ResolvedDeclaration::imported)
                    .filter(declaration -> declaration.moduleId().equals(module))
                    .filter(declaration -> declaration.originDeclaration()
                            .filter(fact.identity().originDeclaration()::equals)
                            .isPresent()
                            || declaration.kind() == DeclarationKind.IMPORT_MODULE
                            && declaration.importedModule()
                            .filter(fact.identity().ownerModule()::equals)
                            .isPresent())
                    .map(ResolvedDeclaration::nameSpan)
                    .distinct()
                    .sorted(sourceSpanComparator())
                    .toList();
        }

        private Comparator<SourceSpan> sourceSpanComparator() {
            return Comparator.comparing(
                            (SourceSpan value) -> value.sourceId().value())
                    .thenComparingInt(SourceSpan::startOffset)
                    .thenComparingInt(SourceSpan::endOffset);
        }

        private ValueAlternatives imported(
                ValueAlternatives values,
                ModuleId ownerModule,
                DeclarationId originDeclaration,
                Optional<io.mindspice.lyra.compiler.identity.ExportId> export,
                SourceSpan witnessSpan) {
            return boundaryValues(values, ownerModule, originDeclaration, export,
                    witnessSpan, false);
        }

        /**
         * Attachable dispatch boundary: the current contents of a public
         * {@code @mut} root binding may be externally written aggregates, so
         * local allocation facts are replaced by conservative boundary
         * identities instead of initializer-only ownership assumptions.
         */
        private ValueAlternatives attachableBoundary(
                ValueAlternatives values,
                ModuleId ownerModule,
                DeclarationId originDeclaration,
                Optional<io.mindspice.lyra.compiler.identity.ExportId> export,
                SourceSpan witnessSpan) {
            return boundaryValues(values, ownerModule, originDeclaration, export,
                    witnessSpan, true);
        }

        private ValueAlternatives boundaryValues(
                ValueAlternatives values,
                ModuleId ownerModule,
                DeclarationId originDeclaration,
                Optional<io.mindspice.lyra.compiler.identity.ExportId> export,
                SourceSpan witnessSpan,
                boolean attachable) {
            if (values.isEmpty()) {
                return values;
            }
            ArrayList<ValueAlternative> alternatives = new ArrayList<>();
            for (ValueAlternative value : values) {
                ArrayList<io.mindspice.lyra.compiler.semantic.flow.AggregateIdentityFact> facts = new ArrayList<>();
                for (var fact : value.aggregateIdentities()) {
                    if (fact.isImported()) {
                        facts.add(fact);
                        continue;
                    }
                    io.mindspice.lyra.compiler.identity.ExportId exportId;
                    ModuleId identityOwner;
                    DeclarationId identityOrigin;
                    if (attachable) {
                        exportId = export
                                .filter(id -> id.moduleId().equals(ownerModule))
                                .orElseGet(() -> exportFor(ownerModule,
                                        originDeclaration, fact.identity().arrayType()));
                        identityOwner = ownerModule;
                        identityOrigin = originDeclaration;
                    } else {
                        exportId = export
                                .filter(id -> id.moduleId().equals(fact.identity().ownerModule()))
                                .orElseGet(() -> exportFor(fact.identity().ownerModule(),
                                        fact.identity().originDeclaration(), fact.identity().arrayType()));
                        identityOwner = fact.identity().ownerModule();
                        identityOrigin = fact.identity().originDeclaration();
                    }
                    ArrayIdentity identity = attachable
                            ? ArrayIdentity.attachableBoundary(
                            identityOwner, identityOrigin, exportId, fact.identity().arrayType())
                            : ArrayIdentity.crossModuleOrigin(
                            identityOwner, identityOrigin, exportId, fact.identity().arrayType());
                    OwnershipWitness witness = OwnershipWitness.crossModule(
                                    identityOwner, identityOrigin,
                                    fact.ownershipWitness().scopeId(),
                                    fact.ownershipWitness().sourceSpan(), exportId)
                            .atUse(witnessSpan);
                    if (fact.ownershipWitness().originSite().isPresent()) {
                        witness = witness.withOriginSite(
                                fact.ownershipWitness().originSite().orElseThrow());
                    }
                    facts.add(new io.mindspice.lyra.compiler.semantic.flow.AggregateIdentityFact(
                            identity, fact.route(), witness));
                }
                addPotentialBoundaryFacts(
                        value.type(), ProjectionPath.root(), ownerModule,
                        originDeclaration, export, witnessSpan, facts, attachable);
                alternatives.add(ValueAlternative.of(
                        value.type(), facts, value.callableFlows(), value.nilProvenance()));
            }
            return new ValueAlternatives(alternatives);
        }

        private void addPotentialImportedFacts(
                LyraType type,
                ProjectionPath route,
                ModuleId ownerModule,
                DeclarationId originDeclaration,
                Optional<io.mindspice.lyra.compiler.identity.ExportId> export,
                SourceSpan useSpan,
                List<AggregateIdentityFact> facts) {
            addPotentialBoundaryFacts(type, route, ownerModule, originDeclaration,
                    export, useSpan, facts, false);
        }

        private void addPotentialBoundaryFacts(
                LyraType type,
                ProjectionPath route,
                ModuleId ownerModule,
                DeclarationId originDeclaration,
                Optional<io.mindspice.lyra.compiler.identity.ExportId> export,
                SourceSpan useSpan,
                List<AggregateIdentityFact> facts,
                boolean attachable) {
            LyraType base = type.withoutQualifiers();
            if (base instanceof ArrayType array) {
                boolean represented = facts.stream().anyMatch(fact ->
                        fact.route().depth() == route.depth()
                                && fact.route().overlaps(route));
                if (!represented) {
                    io.mindspice.lyra.compiler.identity.ExportId exportId = export
                            .filter(id -> id.moduleId().equals(ownerModule))
                            .orElseGet(() -> exportFor(
                                    ownerModule, originDeclaration, array));
                    OwnershipWitness witness = OwnershipWitness.crossModule(
                                    ownerModule, originDeclaration,
                                    declarationScope(originDeclaration, useSpan),
                                    ownershipOriginSpan(originDeclaration, useSpan),
                                    exportId)
                            .atUse(useSpan)
                            .withOriginSite(ownershipOriginSite(
                                    originDeclaration, useSpan));
                    facts.add(new AggregateIdentityFact(
                            attachable
                                    ? ArrayIdentity.attachableBoundary(
                                    ownerModule, originDeclaration, exportId, array)
                                    : ArrayIdentity.crossModuleOrigin(
                                    ownerModule, originDeclaration, exportId, array),
                            route, witness));
                }
                addPotentialBoundaryFacts(
                        array.elementType(),
                        route.append(ProjectionStep.unknownArrayElement()),
                        ownerModule, originDeclaration, export, useSpan, facts, attachable);
                return;
            }
            if (base instanceof TupleType tuple) {
                for (int index = 0; index < tuple.arity(); index++) {
                    addPotentialBoundaryFacts(
                            tuple.memberType(index),
                            route.append(ProjectionStep.tupleMember(index)),
                            ownerModule, originDeclaration, export, useSpan, facts, attachable);
                }
            }
        }

        /** Exact predicate for the attachable safe-point effect boundary. */
        private boolean attachableRootMutableBinding(DeclarationId declaration) {
            if (!attachableBoundary) {
                return false;
            }
            ResolvedDeclaration resolved = resolvedDeclarations.get(declaration);
            if (resolved == null
                    || !resolved.isPublic()
                    || !resolved.isMutable()
                    || resolved.kind() != DeclarationKind.LET) {
                return false;
            }
            ModuleId root = graph.resolvedGraph().moduleGraph().rootModule();
            return resolved.moduleId().equals(root);
        }

        private io.mindspice.lyra.compiler.identity.ExportId exportFor(
                ModuleId module,
                DeclarationId declaration,
                ArrayType type) {
            return graph.resolvedGraph().declaration(declaration)
                    .flatMap(value -> graph.resolvedGraph().export(module, value.name()))
                    .flatMap(ResolvedExport::exportId)
                    .orElseGet(() -> io.mindspice.lyra.compiler.identity.ExportId.of(
                            module, "_flow_" + declaration.ordinal(),
                            LyraSignature.of(List.of(), type)));
        }

        private void requireProjectionDepth(
                ProjectionPath route,
                SourceSpan span) {
            if (route.depth() > limits.maxProjectionDepth()) {
                throw failure(
                        CallableSummaryResult.InternalFailure.Kind.DOMAIN_LIMIT,
                        "flow projection depth exceeds the finite semantic domain: "
                                + route.depth() + " > " + limits.maxProjectionDepth(),
                        span);
            }
        }

        private FormulaAlternatives toFormulas(
                ValueAlternatives values,
                ModuleId contextModule,
                SourceSpan span) {
            ArrayList<ValueFormula> formulas = new ArrayList<>();
            for (ValueAlternative value : values) {
                for (var fact : value.aggregateIdentities()) {
                    ValueFormula.Declaration formula = new ValueFormula.Declaration(
                            fact.identity().originDeclaration(),
                            Optional.of(fact.identity().ownerModule()),
                            fact.identity() instanceof ArrayIdentity.SessionOrigin session
                                    ? session.sourceRoute() : ProjectionPath.root(),
                            fact.route(), fact.identity().arrayType());
                    formulas.add(fact.identity() instanceof ArrayIdentity.AttachableBoundary
                            ? formula.withAttachableBoundary() : formula);
                }
                for (CallableFlow callable : value.callableFlows()) {
                    formulas.add(callableFormula(callable, value.type(), contextModule, span));
                }
                for (NilProvenance nil : value.nilProvenance()) {
                    formulas.add(ValueFormula.Scalar.nil(
                                    ValueAlternative.typeAt(value.type(), nil.route()),
                                    nil.sourceSite(), nil.sourceSpan())
                            .withResultRoute(nil.route()));
                }
                if (value.aggregateIdentities().isEmpty()
                        && value.callableFlows().isEmpty()
                        && value.nilProvenance().isEmpty()) {
                    formulas.add(new ValueFormula.Scalar(value.type()));
                }
            }
            if (formulas.isEmpty()) {
                throw failure(
                        CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                        "value has no finite symbolic alternatives", span);
            }
            if (formulas.size() > limits.maxFormulaAlternatives()) {
                throw failure(
                        CallableSummaryResult.InternalFailure.Kind.DOMAIN_LIMIT,
                        "symbolic caller alternatives exceed the finite semantic domain: "
                                + formulas.size() + " > " + limits.maxFormulaAlternatives(),
                        span);
            }
            for (ValueFormula formula : formulas) {
                requireProjectionDepth(formula.resultRoute(), span);
            }
            return new FormulaAlternatives(values.alternatives().getFirst().type(), formulas);
        }

        private ValueFormula callableFormula(
                CallableFlow callable,
                LyraType rootType,
                ModuleId contextModule,
                SourceSpan span) {
            ProjectionPath route = callable.route();
            LyraType type = ValueAlternative.typeAt(rootType, route);
            if (!(type.withoutQualifiers() instanceof FunctionType function)) {
                throw failure(
                        CallableSummaryResult.InternalFailure.Kind.INCONSISTENT_SUMMARY,
                        "callable flow route is not a function", span);
            }
            if (callable.isIntrinsic()) {
                return new ValueFormula.Declaration(
                        callable.intrinsicDeclarationId().orElseThrow(),
                        Optional.of(declarationModule(callable.intrinsicDeclarationId().orElseThrow())),
                        ProjectionPath.root(), route, function);
            }
            LambdaId lambdaId = callable.lambdaId().orElseThrow();
            CallableSummary summary = summaries.summary(lambdaId).orElseThrow(() -> failure(
                    CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                    "callable formula has no solved summary: " + lambdaId, span));
            TreeMap<CaptureId, FormulaAlternatives> captured = new TreeMap<>();
            for (CallableSummary.CapturePlaceholder placeholder : summary.captures()) {
                ValueAlternatives values = placeholder.isSharedCell()
                        ? callable.sharedCellSnapshots().get(placeholder.cellId().orElseThrow())
                        : callable.capturedValues().get(placeholder.declarationId());
                if (values == null || values.isEmpty()) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                            "callable formula has no captured value for lambda "
                                    + lambdaId + " capture " + placeholder.captureId(), span);
                }
                captured.put(placeholder.captureId(), toFormulas(values, contextModule, span));
            }
            return new ValueFormula.Lambda(lambdaId, function, route, captured);
        }

        private CallableFlow callableFromFormula(
                ValueFormula.Lambda formula,
                ModuleId contextModule,
                SourceSpan span) {
            LambdaId lambdaId = formula.lambdaId();
            CallableSummary summary = summaries.summary(lambdaId).orElseThrow(() -> failure(
                    CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                    "returned lambda has no solved ownership summary: " + lambdaId, span));
            TreeMap<DeclarationId, ValueAlternatives> immutable = new TreeMap<>();
            TreeMap<DeclarationId, ValueAlternatives> cells = new TreeMap<>();
            for (CallableSummary.CapturePlaceholder placeholder : summary.captures()) {
                FormulaAlternatives capturedFormula = formula.capturedValues()
                        .get(placeholder.captureId());
                if (capturedFormula == null) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                            "returned lambda has no formula for capture: "
                                    + placeholder.captureId(), span);
                }
                ValueAlternatives values = fromFormulas(
                        capturedFormula, contextModule, span);
                if (placeholder.isSharedCell()) {
                    cells.put(placeholder.cellId().orElseThrow(), values);
                } else {
                    immutable.put(placeholder.declarationId(), values);
                }
            }
            TypedLambda local = lambdas.get(lambdaId);
            if (local != null) {
                return new CallableFlow(
                        Optional.of(lambdaId), Optional.empty(), formula.resultRoute(),
                        immutable, cells,
                        Optional.ofNullable(lambdaCreationSites.get(lambdaId)));
            }
            SessionFlowCertificate certificate = graph.resolvedGraph()
                    .sessionFlowCertificate().orElseThrow(() -> failure(
                            CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                            "returned lambda is foreign and has no predecessor certificate: "
                                    + lambdaId, span));
            CallableFlow producer = certificate.matchingCallable(
                    lambdaId, immutable, cells).orElseThrow(() -> failure(
                            CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                            "returned lambda has no producer-certified closure: " + lambdaId,
                            span));
            return producer.withRoute(formula.resultRoute())
                    .withSharedCellSnapshots(cells);
        }

        private ValueAlternatives fromFormulas(
                FormulaAlternatives alternatives,
                ModuleId contextModule,
                SourceSpan span) {
            return fromFormulas(alternatives, contextModule, span, true, false);
        }

        private ValueAlternatives fromOwnershipFormulas(
                FormulaAlternatives alternatives,
                ModuleId contextModule,
                SourceSpan span) {
            return fromFormulas(alternatives, contextModule, span, false, true);
        }

        private ValueAlternatives fromFormulas(
                FormulaAlternatives alternatives,
                ModuleId contextModule,
                SourceSpan span,
                boolean validateReturnedCallables,
                boolean applyAttachableBoundary) {
            ArrayList<io.mindspice.lyra.compiler.semantic.flow.AggregateIdentityFact> facts = new ArrayList<>();
            ArrayList<CallableFlow> callables = new ArrayList<>();
            ArrayList<NilProvenance> nils = new ArrayList<>();
            for (ValueFormula formula : alternatives.formulas()) {
                if (formula instanceof ValueFormula.FreshAllocation fresh) {
                    DeclarationId allocation = summaryAllocationIds.get(fresh.allocationSite());
                    if (allocation == null) {
                        throw failure(
                                CallableSummaryResult.InternalFailure.Kind.INCONSISTENT_SUMMARY,
                                "fresh summary allocation has no canonical identity", span);
                    }
                    ModuleId owner = lambdas.get(fresh.allocationSite().ownerLambda()).moduleId();
                    ArrayType arrayType = fresh.arrayType();
                    if (owner.equals(contextModule)) {
                        OwnershipWitness witness = OwnershipWitness.local(
                                        owner, allocation, allocationScopes.get(allocation),
                                        fresh.allocationSite().span())
                                .withOriginSite(allocationFlowSites.get(allocation));
                        facts.add(new io.mindspice.lyra.compiler.semantic.flow.AggregateIdentityFact(
                                ArrayIdentity.localAllocation(owner, allocation, arrayType),
                                fresh.resultRoute(), witness));
                    } else {
                        io.mindspice.lyra.compiler.identity.ExportId export = exportFor(owner, allocation, arrayType);
                        OwnershipWitness witness = OwnershipWitness.crossModule(
                                        owner, allocation, allocationScopes.get(allocation),
                                        fresh.allocationSite().span(), export)
                                .atUse(span)
                                .withOriginSite(allocationFlowSites.get(allocation));
                        facts.add(new io.mindspice.lyra.compiler.semantic.flow.AggregateIdentityFact(
                                ArrayIdentity.crossModuleOrigin(owner, allocation, export, arrayType),
                                fresh.resultRoute(), witness));
                    }
                } else if (formula instanceof ValueFormula.Declaration declaration
                        && formula.type().withoutQualifiers() instanceof ArrayType arrayType) {
                    ModuleId owner = declaration.moduleId().orElse(contextModule);
                    ResolvedDeclaration external = resolvedDeclarations.get(declaration.declarationId());
                    if (external != null && external.externalBinding().isPresent()) {
                        // A registered-root certificate may carry conservative
                        // imported facts for externally owned aggregate
                        // contents.  Preserve them across the formula bridge
                        // so mutation requirements never re-synthesize
                        // session-owned identities for root storage.
                        var certificate = graph.resolvedGraph().sessionFlowCertificate();
                        Optional<AggregateIdentityFact> imported = certificate
                                .filter(value -> value.certifiesBinding(
                                        external.externalBinding().orElseThrow()))
                                .flatMap(value -> value.valueFor(declaration.declarationId()))
                                .flatMap(values -> values.alternatives().stream()
                                        .flatMap(alternative -> alternative
                                                .aggregateIdentities().stream())
                                        .filter(fact -> fact.isImported()
                                                && fact.route().equals(
                                                declaration.declarationRoute()))
                                        .findFirst())
                                .map(fact -> fact.route().equals(declaration.resultRoute())
                                        ? fact : fact.withRoute(declaration.resultRoute()));
                        if (imported.isPresent()) {
                            facts.add(imported.orElseThrow());
                            continue;
                        }
                        facts.add(sessionArrayFact(external, declaration.declarationRoute(),
                                declaration.resultRoute(), arrayType, span));
                        continue;
                    }
                    SourceSpan originSpan = ownershipOriginSpan(
                            declaration.declarationId(), span);
                    io.mindspice.lyra.compiler.identity.ExportId export = null;
                    if (declaration.attachableBoundary()
                            || applyAttachableBoundary
                            && attachableRootMutableBinding(declaration.declarationId())) {
                        export = graph.resolvedGraph().declaration(declaration.declarationId())
                                .flatMap(value -> graph.resolvedGraph()
                                        .export(owner, value.name()))
                                .flatMap(ResolvedExport::exportId)
                                .orElseGet(() -> exportFor(
                                        owner, declaration.declarationId(), arrayType));
                        OwnershipWitness witness = OwnershipWitness.crossModule(
                                        owner, declaration.declarationId(),
                                        declarationScope(declaration.declarationId(), span),
                                        originSpan, export)
                                .atUse(span)
                                .withOriginSite(ownershipOriginSite(
                                        declaration.declarationId(), span));
                        facts.add(new io.mindspice.lyra.compiler.semantic.flow.AggregateIdentityFact(
                                ArrayIdentity.attachableBoundary(
                                        owner, declaration.declarationId(), export, arrayType),
                                declaration.resultRoute(), witness));
                    } else if (owner.equals(contextModule)) {
                        OwnershipWitness witness = OwnershipWitness.local(
                                        owner, declaration.declarationId(),
                                        declarationScope(declaration.declarationId(), span),
                                        originSpan)
                                .atUse(span)
                                .withOriginSite(ownershipOriginSite(
                                        declaration.declarationId(), span));
                        facts.add(new io.mindspice.lyra.compiler.semantic.flow.AggregateIdentityFact(
                                ArrayIdentity.localAllocation(
                                        owner, declaration.declarationId(), arrayType),
                                declaration.resultRoute(), witness));
                    } else {
                        io.mindspice.lyra.compiler.identity.ExportId crossExport = exportFor(
                                owner, declaration.declarationId(), arrayType);
                        OwnershipWitness witness = OwnershipWitness.crossModule(
                                        owner, declaration.declarationId(),
                                        declarationScope(declaration.declarationId(), span),
                                        originSpan, crossExport)
                                .atUse(span)
                                .withOriginSite(ownershipOriginSite(
                                        declaration.declarationId(), span));
                        facts.add(new io.mindspice.lyra.compiler.semantic.flow.AggregateIdentityFact(
                                ArrayIdentity.crossModuleOrigin(
                                        owner, declaration.declarationId(), crossExport, arrayType),
                                declaration.resultRoute(), witness));
                    }
                } else if (formula instanceof ValueFormula.Declaration declaration
                        && declaration.declarationRoute().isRoot()
                        && formula.type().withoutQualifiers() instanceof FunctionType) {
                    ValueAlternatives canonical = declarationValues.get(
                            originDeclaration(declaration.declarationId()));
                    if (canonical != null && !canonical.isEmpty()) {
                        ValueAlternatives selected;
                        try {
                            selected = canonical.select(
                                    declaration.declarationRoute());
                        } catch (IllegalArgumentException incompatibleRoute) {
                            throw failure(
                                    CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                                    "function declaration formula has no callable route: "
                                            + declaration.declarationId(), span);
                        }
                        boolean complete = !selected.isEmpty()
                                && selected.alternatives().stream().allMatch(value ->
                                value.callableFlows().stream().anyMatch(callable ->
                                        callable.route().isRoot()));
                        if (!complete) {
                            throw failure(
                                    CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                                    "function declaration value has no canonical callable identity: "
                                            + declaration.declarationId(), span);
                        }
                        for (CallableFlow callable : selected.alternatives().stream()
                                .flatMap(value -> value.callableFlows().stream())
                                .filter(value -> value.route().isRoot())
                                .distinct().sorted().toList()) {
                            callables.add(callable.withRoute(
                                    declaration.resultRoute()));
                        }
                    } else {
                        Optional<LambdaId> lambda = summaries.lambdaForDeclaration(
                                declaration.declarationId());
                        if (lambda.isPresent()) {
                            callables.add(new CallableFlow(
                                    lambda, Optional.empty(), declaration.resultRoute(),
                                    Map.of(), Map.of(), Optional.ofNullable(
                                    lambdaCreationSites.get(lambda.orElseThrow()))));
                        } else if (summaries.intrinsicDeclarations()
                                .containsKey(declaration.declarationId())) {
                            callables.add(new CallableFlow(
                                    Optional.empty(), Optional.of(
                                    declaration.declarationId()),
                                    declaration.resultRoute(), Map.of(), Map.of()));
                        } else {
                            throw failure(
                                    CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                                    "function declaration formula has no callable identity: "
                                            + declaration.declarationId(), span);
                        }
                    }
                } else if (formula instanceof ValueFormula.Lambda lambda
                        && validateReturnedCallables) {
                    CallableFlow returnedCallable = callableFromFormula(
                            lambda, contextModule, span);
                    CallableSummary returnedSummary = summaries.summary(
                            lambda.lambdaId()).orElseThrow(() -> failure(
                            CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                            "returned lambda has no solved ownership summary", span));
                    rejectImportedOwnershipRequirements(
                            materializeCreationOwnershipRequirements(
                                    returnedSummary, lambda.capturedValues(), span),
                            contextModule,
                            diagnosticOrigins(
                                    List.of(), returnedCallable, contextModule));
                    callables.add(returnedCallable);
                } else if (formula instanceof ValueFormula.Scalar scalar && scalar.isNil()) {
                    LyraType nilType = ValueAlternative.typeAt(
                            alternatives.rootType(), scalar.resultRoute());
                    if (nilType.isNilable()) {
                        nils.add(new NilProvenance(
                                scalar.nilSourceSite().orElseThrow(),
                                scalar.nilSourceSpan().orElseThrow(),
                                scalar.resultRoute()));
                    }
                }
            }
            if (facts.isEmpty() && callables.isEmpty() && nils.isEmpty()) {
                if (validateReturnedCallables
                        && alternatives.rootType().withoutQualifiers()
                        instanceof FunctionType) {
                    throw failure(
                            CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                            "function value has no canonical callable identity", span);
                }
                return scalarValue(alternatives.rootType());
            }
            return new ValueAlternatives(List.of(
                    ValueAlternative.of(
                            alternatives.rootType(), facts, callables, nils)));
        }

        private ScopeId declarationScope(DeclarationId declaration, SourceSpan span) {
            ResolvedDeclaration value = resolvedDeclarations.get(declaration);
            if (value != null) {
                return value.scopeId();
            }
            ScopeId allocation = allocationScopes.get(declaration);
            if (allocation != null) {
                return allocation;
            }
            throw failure(
                    CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                    "flow allocation has no canonical owner scope: " + declaration,
                    span);
        }

        private SourceSpan ownershipOriginSpan(
                DeclarationId declaration,
                SourceSpan useSpan) {
            SourceSpan allocation = allocationSpans.get(declaration);
            if (allocation != null) {
                return allocation;
            }
            TypedDeclaration typed = declarations.get(declaration);
            if (typed != null) {
                return typed.initializer().map(TypedExpression::span).orElse(typed.span());
            }
            ResolvedDeclaration resolved = resolvedDeclarations.get(declaration);
            if (resolved != null) {
                return resolved.span();
            }
            throw failure(
                    CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                    "flow allocation has no canonical origin span: " + declaration,
                    useSpan);
        }

        private Optional<TypedExpression> nilLiteralSource(TypedExpression expression) {
            if (expression.literal().orElse(null) instanceof TypedLiteralValue.NilValue) {
                return Optional.of(expression);
            }
            if ((expression.kind() == TypedExpressionKind.CONVERSION
                    || expression.kind() == TypedExpressionKind.NARROWING)
                    && expression.children().size() == 1) {
                return nilLiteralSource(expression.children().getFirst());
            }
            if (expression.kind() == TypedExpressionKind.BLOCK
                    && !expression.children().isEmpty()) {
                return nilLiteralSource(expression.children().getLast());
            }
            return Optional.empty();
        }

        private FlowSiteId ownershipOriginSite(
                DeclarationId declaration,
                SourceSpan useSpan) {
            FlowSiteId allocation = allocationFlowSites.get(declaration);
            if (allocation != null) {
                return allocation;
            }
            TypedDeclaration typed = declarations.get(declaration);
            if (typed != null && typed.initializer().isPresent()) {
                return graph.flowSiteId(typed.initializer().orElseThrow());
            }
            ResolvedDeclaration resolved = resolvedDeclarations.get(declaration);
            if (resolved != null && resolved.originDeclaration().isPresent()
                    && !resolved.originDeclaration().orElseThrow().equals(declaration)) {
                return ownershipOriginSite(
                        resolved.originDeclaration().orElseThrow(), useSpan);
            }
            throw failure(
                    CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                    "flow allocation has no canonical origin site: " + declaration,
                    useSpan);
        }

        private ValueAlternatives externalValue(ResolvedDeclaration declaration) {
            LyraType type = contractType(declaration.id(), declaration.span());
            Optional<SessionFlowCertificate> certificate = graph.resolvedGraph()
                    .sessionFlowCertificate();
            if (certificate.isPresent()
                    && certificate.orElseThrow().certifiesBinding(
                    declaration.externalBinding().orElseThrow())) {
                ValueAlternatives retained = certificate.orElseThrow()
                        .valueFor(declaration.id())
                        .orElseThrow(() -> failure(
                                CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                                "certified external binding has no retained value: "
                                        + declaration.name(), declaration.span()));
                return normalizeCertifiedExternalValue(declaration, type, retained);
            }
            List<AggregateIdentityFact> facts = new ArrayList<>();
            externalArrayFacts(declaration, type, ProjectionPath.root(), facts);
            return ValueAlternatives.singleton(ValueAlternative.of(type, facts));
        }

        private ValueAlternatives normalizeCertifiedExternalValue(
                ResolvedDeclaration declaration,
                LyraType type,
                ValueAlternatives retained) {
            if (retained.isEmpty()) {
                throw failure(
                        CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                        "certified external binding has no value alternatives: "
                                + declaration.name(), declaration.span());
            }
            ArrayList<ValueAlternative> normalized = new ArrayList<>();
            for (ValueAlternative alternative : retained.alternatives()) {
                List<AggregateIdentityFact> facts = new ArrayList<>();
                certifiedExternalArrayFacts(
                        declaration, alternative.type(), ProjectionPath.root(),
                        alternative, facts);
                normalized.add(ValueAlternative.of(
                        type, facts, alternative.callableFlows(), alternative.nilProvenance()));
            }
            return new ValueAlternatives(normalized);
        }

        private void certifiedExternalArrayFacts(
                ResolvedDeclaration declaration,
                LyraType type,
                ProjectionPath route,
                ValueAlternative alternative,
                List<AggregateIdentityFact> facts) {
            requireProjectionDepth(route, declaration.span());
            if (hasNilAtOrAbove(alternative, route)) {
                return;
            }
            if (type.withoutQualifiers() instanceof ArrayType array) {
                // A certified producer may carry conservative imported facts
                // (cross-module or attachable-boundary identities) for values
                // that crossed a registered-root boundary.  Those facts are
                // deliberately preserved: the session never inherits local
                // ownership of externally owned aggregate contents.
                Optional<AggregateIdentityFact> imported = alternative.aggregateIdentities()
                        .stream()
                        .filter(fact -> fact.route().equals(route))
                        .filter(AggregateIdentityFact::isImported)
                        .findFirst();
                if (imported.isPresent()) {
                    facts.add(imported.orElseThrow());
                } else {
                    facts.add(new AggregateIdentityFact(
                            new ArrayIdentity.SessionOrigin(
                                    declaration.moduleId(), declaration.id(), route, array),
                            route,
                            OwnershipWitness.local(
                                    declaration.moduleId(), declaration.id(),
                                    declaration.scopeId(), declaration.span()).atUse(declaration.span())));
                }
                certifiedExternalArrayFacts(
                        declaration, array.elementType(),
                        route.append(ProjectionStep.unknownArrayElement()), alternative, facts);
            } else if (type.withoutQualifiers() instanceof TupleType tuple) {
                for (int index = 0; index < tuple.arity(); index++) {
                    certifiedExternalArrayFacts(
                            declaration, tuple.memberType(index),
                            route.append(ProjectionStep.tupleMember(index)), alternative, facts);
                }
            }
        }

        private boolean hasNilAtOrAbove(ValueAlternative alternative, ProjectionPath route) {
            return alternative.nilProvenance().stream().anyMatch(nil ->
                    nil.route().isExactPrefixOf(route));
        }

        private void externalArrayFacts(ResolvedDeclaration declaration, LyraType type,
                                        ProjectionPath route, List<AggregateIdentityFact> facts) {
            requireProjectionDepth(route, declaration.span());
            if (type.withoutQualifiers() instanceof ArrayType array) {
                facts.add(sessionArrayFact(declaration, route, route, array, declaration.span()));
                externalArrayFacts(declaration, array.elementType(),
                        route.append(ProjectionStep.unknownArrayElement()), facts);
            } else if (type.withoutQualifiers() instanceof TupleType tuple) {
                for (int index = 0; index < tuple.arity(); index++) {
                    externalArrayFacts(declaration, tuple.memberType(index),
                            route.append(ProjectionStep.tupleMember(index)), facts);
                }
            }
        }

        private AggregateIdentityFact sessionArrayFact(ResolvedDeclaration declaration,
                ProjectionPath sourceRoute, ProjectionPath resultRoute, ArrayType array, SourceSpan use) {
            if (!declaration.externalBinding().orElseThrow().supportsSessionStorage()
                    || !ValueAlternative.typeAt(contractType(declaration.id(), declaration.span()), sourceRoute)
                    .withoutQualifiers().equals(array)) {
                throw failure(CallableSummaryResult.InternalFailure.Kind.INCONSISTENT_SUMMARY,
                        "external array route has no certified session data contract", use);
            }
            return new AggregateIdentityFact(new ArrayIdentity.SessionOrigin(declaration.moduleId(),
                    declaration.id(), sourceRoute, array), resultRoute,
                    OwnershipWitness.local(declaration.moduleId(), declaration.id(),
                            declaration.scopeId(), declaration.span()).atUse(use));
        }

        private ValueAlternatives scalarValue(LyraType type) {
            return ValueAlternatives.singleton(ValueAlternative.scalar(type));
        }

        private ValueAlternatives nilValue(TypedExpression expression) {
            return ValueAlternatives.singleton(ValueAlternative.nil(
                    expression.type(), new NilProvenance(
                    graph.flowSiteId(expression), expression.span(), ProjectionPath.root())));
        }

        private ValueAlternatives retag(ValueAlternatives values, LyraType type) {
            if (values.isEmpty()) {
                return scalarValue(type);
            }
            return new ValueAlternatives(values.alternatives().stream()
                    .map(value -> ValueAlternative.of(
                            type,
                            value.aggregateIdentities(),
                            value.callableFlows(),
                            value.nilProvenance().stream().filter(nil -> {
                                try {
                                    return ValueAlternative.typeAt(type, nil.route()).isNilable();
                                } catch (IllegalArgumentException incompatibleRoute) {
                                    return false;
                                }
                            }).toList()))
                    .toList());
        }

        private List<io.mindspice.lyra.compiler.semantic.flow.AggregateIdentityFact> facts(
                ValueAlternatives values) {
            return values.alternatives().stream()
                    .flatMap(value -> value.aggregateIdentities().stream())
                    .toList();
        }

        private List<EagerEffectWitness> distinctEffects(
                List<EagerEffectWitness> values) {
            return new TreeSet<>(values).stream().toList();
        }

        private static <T> List<T> concat(List<T> left, List<T> right) {
            ArrayList<T> result = new ArrayList<>(left.size() + right.size());
            result.addAll(left);
            result.addAll(right);
            return List.copyOf(result);
        }

        private DeclarationId originDeclaration(DeclarationId declaration) {
            ResolvedDeclaration resolved = resolvedDeclarations.get(declaration);
            return resolved == null ? declaration
                    : resolved.originDeclaration().orElse(declaration);
        }

        private ModuleId declarationModule(DeclarationId declaration) {
            ResolvedDeclaration resolved = resolvedDeclarations.get(declaration);
            if (resolved != null) {
                return resolved.importedModule().orElse(resolved.moduleId());
            }
            TypedDeclaration typed = declarations.get(declaration);
            if (typed != null) {
                return typed.moduleId();
            }
            throw failure(
                    CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                    "declaration has no owning module: " + declaration,
                    graph.resolvedGraph().moduleGraph().module(
                            graph.resolvedGraph().moduleGraph().rootModule()).orElseThrow()
                            .program().span());
        }

        private LyraType contractType(DeclarationId declaration, SourceSpan span) {
            return graph.contract(originDeclaration(declaration))
                    .map(BindingContract::valueType)
                    .orElseThrow(() -> failure(
                            CallableSummaryResult.InternalFailure.Kind.MISSING_CALLABLE_FACT,
                            "declaration has no contract: " + declaration, span));
        }

        private boolean isIntrinsic(DeclarationId declaration) {
            ResolvedDeclaration resolved = resolvedDeclarations.get(declaration);
            return resolved != null && resolved.kind() == DeclarationKind.INTRINSIC_EXPORT;
        }

        private boolean isLazyFunctionSlot(DeclarationId declaration) {
            ResolvedDeclaration resolved = resolvedDeclarations.get(declaration);
            TypedDeclaration typed = declarations.get(originDeclaration(declaration));
            return resolved != null
                    && resolved.signaturePredeclared()
                    && typed != null
                    && typed.initializerLambda().isPresent();
        }

        private static final class Frame {
            private final TypedModule module;
            private final List<TypedExpression> forms;
            private final Map<DeclarationId, Integer> formIndexes = new TreeMap<>();
            private final Map<DeclarationId, ValueAlternatives> values = new TreeMap<>();
            private io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state =
                    io.mindspice.lyra.compiler.semantic.flow.BindingFlowState.empty();
            private int nextForm;

            private Frame(TypedModule module) {
                this.module = module;
                this.forms = module.forms();
                for (int index = 0; index < forms.size(); index++) {
                    int formIndex = index;
                    forms.get(index).declarationId()
                            .ifPresent(id -> formIndexes.put(id, formIndex));
                }
            }

            private int formIndex(DeclarationId declaration) {
                return formIndexes.getOrDefault(declaration, -1);
            }
        }

        private record Eval(
                ValueAlternatives value,
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state,
                List<SemanticFlowEvent> events,
                List<EagerEffectWitness> effects) {
            private Eval {
                Objects.requireNonNull(value, "value");
                Objects.requireNonNull(state, "state");
                events = List.copyOf(events);
                effects = List.copyOf(effects);
            }
        }

        private record Lookup(
                ValueAlternatives value,
                List<EagerEffectWitness> effects,
                boolean executed) {
            private Lookup {
                Objects.requireNonNull(value, "value");
                effects = List.copyOf(effects);
            }
        }

        private record ActiveDeclaration(
                DeclarationId declaration,
                ModuleId module,
                SourceSpan span) {
        }

        private record TargetPath(DeclarationId declaration, ProjectionPath route) {
        }

        private record IdentityOccurrence(ArrayIdentity identity, ProjectionPath route) {
        }

        private record OwnershipOriginKey(
                ModuleId module,
                DeclarationId declaration,
                ArrayType type) implements Comparable<OwnershipOriginKey> {
            @Override
            public int compareTo(OwnershipOriginKey other) {
                int moduleOrder = module.compareTo(other.module);
                if (moduleOrder != 0) {
                    return moduleOrder;
                }
                int declarationOrder = declaration.compareTo(other.declaration);
                return declarationOrder != 0
                        ? declarationOrder
                        : type.canonicalSpelling().compareTo(
                        other.type.canonicalSpelling());
            }
        }

        private record OwnershipOccurrence(
                ModuleId module,
                ArrayIdentity identity,
                SourceSpan useSpan) implements Comparable<OwnershipOccurrence> {
            @Override
            public int compareTo(OwnershipOccurrence other) {
                int moduleOrder = module.compareTo(other.module);
                if (moduleOrder != 0) {
                    return moduleOrder;
                }
                int identityOrder = identity.compareTo(other.identity);
                if (identityOrder != 0) {
                    return identityOrder;
                }
                int sourceOrder = useSpan.sourceId().value().compareTo(
                        other.useSpan.sourceId().value());
                if (sourceOrder != 0) {
                    return sourceOrder;
                }
                int startOrder = Integer.compare(
                        useSpan.startOffset(), other.useSpan.startOffset());
                return startOrder != 0 ? startOrder : Integer.compare(
                        useSpan.endOffset(), other.useSpan.endOffset());
            }
        }

        private record CaptureBinding(
                CaptureId captureId,
                DeclarationId declarationId,
                DeclarationId cellId) {
        }

        private record PartialValue(
                List<io.mindspice.lyra.compiler.semantic.flow.AggregateIdentityFact> facts,
                List<CallableFlow> callables,
                List<NilProvenance> nils) {
            private PartialValue {
                facts = List.copyOf(facts);
                callables = List.copyOf(callables);
                nils = List.copyOf(nils);
            }
        }

        private record CallBranch(
                ValueAlternatives value,
                io.mindspice.lyra.compiler.semantic.flow.BindingFlowState state,
                List<CapturedCellWrite> writes,
                List<EagerEffectWitness> effects,
                List<SemanticFlowEvent> events) {
            private CallBranch {
                writes = List.copyOf(writes);
                effects = List.copyOf(effects);
                events = List.copyOf(events);
            }
        }
    }

    private static FlowFailure failure(
            CallableSummaryResult.InternalFailure.Kind kind,
            String message,
            SourceSpan span) {
        return new FlowFailure(CallableSummaryResult.InternalFailure.at(kind, message, span));
    }

    private static final class OwnershipFailure extends RuntimeException {
        private final Diagnostic diagnostic;

        private OwnershipFailure(Diagnostic diagnostic) {
            super(Objects.requireNonNull(diagnostic, "diagnostic").summary());
            this.diagnostic = diagnostic;
        }

        private Diagnostic diagnostic() {
            return diagnostic;
        }
    }

    private static final class FlowFailure extends RuntimeException {
        private final CallableSummaryResult.InternalFailure failure;

        private FlowFailure(CallableSummaryResult.InternalFailure failure) {
            super(Objects.requireNonNull(failure, "failure").message());
            this.failure = failure;
        }

        private CallableSummaryResult.InternalFailure failure() {
            return failure;
        }
    }
}
