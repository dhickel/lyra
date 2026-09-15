package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.semantic.AccessKind;
import io.mindspice.lyra.compiler.semantic.DeclarationKind;
import io.mindspice.lyra.compiler.semantic.MutationKind;
import io.mindspice.lyra.compiler.semantic.ReferenceKind;
import io.mindspice.lyra.compiler.semantic.ResolvedCapture;
import io.mindspice.lyra.compiler.semantic.ResolvedDeclaration;
import io.mindspice.lyra.compiler.semantic.ResolvedModule;
import io.mindspice.lyra.compiler.semantic.ScopeKind;
import io.mindspice.lyra.compiler.semantic.TypedExpression;
import io.mindspice.lyra.compiler.semantic.TypedExpressionKind;
import io.mindspice.lyra.compiler.semantic.TypedMatch;
import io.mindspice.lyra.compiler.semantic.TypedReference;
import io.mindspice.lyra.compiler.semantic.TypedModule;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.flow.ProjectionPath;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.BindingContract;
import io.mindspice.lyra.compiler.types.ConversionDecision;
import io.mindspice.lyra.compiler.types.ConversionKind;
import io.mindspice.lyra.compiler.types.ConversionStep;
import io.mindspice.lyra.compiler.types.ExactNumericLiteral;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.LiteralTyping;
import io.mindspice.lyra.compiler.types.LyraType;
import io.mindspice.lyra.compiler.types.NominalType;
import io.mindspice.lyra.compiler.types.PrimitiveType;
import io.mindspice.lyra.compiler.types.TupleType;
import io.mindspice.lyra.compiler.types.TypeRules;
import io.mindspice.lyra.compiler.lex.TokenKind;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;

/** Structural and semantic invariant checker for the closed typed IR. */
public final class IrValidator {
    private IrValidator() {
    }

    /** Returns deterministic structured diagnostics; an empty list means valid, sealed IR. */
    public static List<Diagnostic> validate(TypedIr ir) {
        Objects.requireNonNull(ir, "ir");
        try {
            ArrayList<Diagnostic> diagnostics = new ArrayList<>(new State(ir).run());
            if (!ir.isValidated()) {
                diagnostics.add(Diagnostic.error(
                        CompilerDiagnosticCodes.IR_INVALID_GRAPH,
                        publicationSpan(ir),
                        "unvalidated typed IR cannot reach a downstream phase"));
            }
            return List.copyOf(diagnostics);
        } catch (RuntimeException failure) {
            return List.of(Diagnostic.error(
                    CompilerDiagnosticCodes.IR_INVALID_GRAPH,
                    publicationSpan(ir),
                    "cannot validate typed IR: " + failureSummary(failure)));
        }
    }

    /** Package-owned pre-publication validation used by {@link TypedIrBuilder}. */
    static List<Diagnostic> validateCandidate(TypedIr ir) {
        Objects.requireNonNull(ir, "ir");
        try {
            return new State(ir).run();
        } catch (RuntimeException failure) {
            return List.of(Diagnostic.error(
                    CompilerDiagnosticCodes.IR_INVALID_GRAPH,
                    publicationSpan(ir),
                    "cannot validate typed IR candidate: " + failureSummary(failure)));
        }
    }

    public static boolean isValid(TypedIr ir) {
        return validate(ir).isEmpty();
    }

    public static PhaseResult<TypedIr> validatePhase(TypedIr ir) {
        Objects.requireNonNull(ir, "ir");
        List<Diagnostic> diagnostics = validate(ir);
        return diagnostics.isEmpty()
                ? PhaseResult.success(ir)
                : PhaseResult.failure(diagnostics);
    }

    public static PhaseResult<TypedIr> check(TypedIr ir) {
        return validatePhase(ir);
    }

    private static SourceSpan publicationSpan(TypedIr ir) {
        TypedSemanticGraph graph = ir.semanticGraph();
        return graph.modules().isEmpty()
                ? graph.resolvedGraph().moduleGraph()
                .module(graph.resolvedGraph().moduleGraph().rootModule())
                .orElseThrow().program().span()
                : graph.modules().getFirst().span();
    }

    /** Consumer gate for later backend phases. */
    public static TypedIr requireValidated(TypedIr ir) {
        Objects.requireNonNull(ir, "ir");
        if (!ir.isValidated()) {
            throw new IllegalStateException("unvalidated typed IR cannot reach a consumer");
        }
        List<Diagnostic> diagnostics = validate(ir);
        if (!diagnostics.isEmpty()) {
            throw new IllegalStateException("validated typed IR failed invariant validation: " + diagnostics);
        }
        return ir;
    }

    private static String failureSummary(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }

    private static final class State {
        private final TypedIr ir;
        private final TypedSemanticGraph semantic;
        private final io.mindspice.lyra.compiler.semantic.SelfAliasProvenance selfAliasProvenance;
        private final List<Diagnostic> diagnostics = new ArrayList<>();
        private final Set<ModuleId> modules = new TreeSet<>();
        private final Set<DeclarationId> declarations = new TreeSet<>();
        private final Set<ReferenceId> references = new TreeSet<>();
        private final Map<FlowSiteId, TypedExpression> expressionsBySite = new LinkedHashMap<>();
        private final Map<FlowSiteId, SourceSpan> flowSiteSpans = new LinkedHashMap<>();
        private final Map<FlowSiteId, IrNode> nodesBySite = new LinkedHashMap<>();
        private final IrProgramMetadata expectedMetadata;
        private final Set<IrNode.Constant> signedMinimumOperands =
                Collections.newSetFromMap(new IdentityHashMap<>());

        private State(TypedIr ir) {
            this.ir = ir;
            this.semantic = ir.semanticGraph();
            this.selfAliasProvenance = io.mindspice.lyra.compiler.semantic.SelfAliasProvenance
                    .of(semantic.resolvedGraph());
            semantic.modules().forEach(module -> modules.add(module.moduleId()));
            semantic.declarations().forEach(declaration -> declarations.add(declaration.id()));
            semantic.references().forEach(reference -> references.add(reference.id()));
            for (TypedExpression expression : semantic.expressions()) {
                FlowSiteId site = semantic.flowSiteId(expression);
                if (emits(ModuleId.fromSourceId(expression.span().sourceId()))
                        && expressionsBySite.put(site, expression) != null) {
                    add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, expression.span(),
                            "typed expressions share one flow-site identity");
                }
                flowSiteSpans.put(site, expression.span());
            }
            for (var reference : semantic.references()) {
                flowSiteSpans.put(semantic.flowSiteId(reference.id()), reference.span());
            }
            for (var capture : semantic.resolvedGraph().captures()) {
                flowSiteSpans.put(semantic.flowSiteId(capture.id()), capture.span());
            }
            ir.sessionExecution().ifPresent(execution -> {
                if (execution.environment().typedGraph().orElseThrow() != semantic) {
                    throw new IllegalArgumentException("IR execution projection belongs to another semantic graph");
                }
                execution.plan().validateAgainst(semantic);
            });
            expectedMetadata = IrProgramMetadata.from(semantic, ir.modules(), List.of(), List.of(), ir.sessionExecution());
        }

        private boolean emits(ModuleId module) {
            return ir.sessionExecution().map(value -> value.emits(module)).orElse(true);
        }

        private List<Diagnostic> run() {
            validateProgramMetadata();
            if (ir.modules().size() != semantic.modules().stream().filter(value -> emits(value.moduleId())).count()) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, rootSpan(),
                        "IR does not contain exactly one module body for every typed module");
            }
            Set<ModuleId> seen = new HashSet<>();
            for (IrModule module : ir.modules()) {
                if (!modules.contains(module.moduleId()) || !emits(module.moduleId())) {
                    add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, module.span(),
                            "IR module identity is absent from the typed semantic graph");
                }
                if (!seen.add(module.moduleId())) {
                    add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, module.span(),
                            "IR contains a duplicate module body");
                }
                if (semantic.resolvedGraph().scopeTree().scope(module.rootScope()).isEmpty()) {
                    add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, module.span(),
                            "IR module root scope is unresolved");
                }
                var typedModule = semantic.module(module.moduleId()).orElse(null);
                if (typedModule == null) {
                    add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, module.span(),
                            "IR module has no typed source module");
                } else {
                    if (!typedModule.rootScope().equals(module.rootScope())) {
                        add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, module.span(),
                                "IR module root scope does not match the typed source module");
                    }
                    if (!typedModule.span().equals(module.span())) {
                        add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, module.span(),
                                "IR module span does not exactly match its typed source module");
                    }
                    var rootScope = semantic.resolvedGraph().scopeTree()
                            .scope(module.rootScope()).orElse(null);
                    if (rootScope == null || rootScope.kind() != ScopeKind.MODULE
                            || !rootScope.span().equals(typedModule.span())) {
                        add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, module.span(),
                                "IR module scope kind/span does not match its typed source module");
                    }
                    if (typedModule.forms().size() != module.body().forms().size()) {
                        add(CompilerDiagnosticCodes.IR_UNSUPPORTED_NODE, module.span(),
                                "IR skipped one or more top-level typed source forms");
                    } else {
                        for (int index = 0; index < typedModule.forms().size(); index++) {
                            TypedExpression typedForm = typedModule.forms().get(index);
                            IrNode irForm = module.body().forms().get(index);
                            if (!typedForm.span().equals(irForm.span())) {
                                add(CompilerDiagnosticCodes.IR_EVALUATION_ORDER, irForm.span(),
                                        "IR top-level order/span does not match the typed source module");
                            }
                            try {
                                IrNode expected = TypedIrBuilder.lowerForValidation(semantic, typedForm);
                                if (!expected.equals(irForm)) {
                                    add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, irForm.span(),
                                            "IR operation is not the exact lowering of its originating typed expression");
                                }
                            } catch (RuntimeException failure) {
                                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, typedForm.span(),
                                        "typed expression has no complete closed-IR correspondence");
                            }
                        }
                    }
                }
                if (module.submissionResult().isPresent()) {
                    var expected = io.mindspice.lyra.compiler.semantic.TypedSubmissionResult.from(semantic);
                    if (!expected.moduleId().equals(module.moduleId())
                            || !IrSubmissionResult.from(expected).equals(module.submissionResult().orElseThrow())) {
                        add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, module.span(),
                                "submission result is not the exact final typed source form");
                    }
                }
                if (module.body().type() != PrimitiveType.UNIT) {
                    add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, module.span(),
                            "module initialization sequences must have Unit type");
                }
                if (!module.moduleId().sourceId().equals(module.body().span().sourceId())) {
                    add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, module.span(),
                            "IR module body belongs to another source");
                }
                if (!module.span().equals(module.body().span())) {
                    add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, module.span(),
                            "IR module body must retain the complete module source span");
                }
                validateNode(module.body(), module.span(), true);
            }
            for (ModuleId module : modules) {
                if (emits(module) && !seen.contains(module)) {
                    add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, rootSpan(),
                            "typed module has no IR body: " + module);
                }
            }
            return List.copyOf(diagnostics);
        }

        private void validateProgramMetadata() {
            IrProgramMetadata actual = ir.metadata();
            if (!actual.declarations().equals(expectedMetadata.declarations())
                    || !actual.references().equals(expectedMetadata.references())
                    || !actual.lambdas().equals(expectedMetadata.lambdas())
                    || !actual.captures().equals(expectedMetadata.captures())
                    || !actual.cells().equals(expectedMetadata.cells())
                    || !actual.exports().equals(expectedMetadata.exports())
                    || !actual.imports().equals(expectedMetadata.imports())
                    || !actual.functionLinkage().equals(expectedMetadata.functionLinkage())
                    || !actual.closureInitializations().equals(expectedMetadata.closureInitializations())
                    || !actual.flowMetadata().aggregateAllocations()
                    .equals(expectedMetadata.flowMetadata().aggregateAllocations())) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, rootSpan(),
                        "IR linkage/topology metadata is not the exact resolved semantic projection");
            }
            if (!actual.failureSites().equals(expectedMetadata.failureSites())) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, rootSpan(),
                        "IR failure-site coverage does not match the typed source graph");
            }
            if (!actual.initializationPlan().equals(expectedMetadata.initializationPlan())) {
                add(CompilerDiagnosticCodes.IR_EVALUATION_ORDER, rootSpan(),
                        "IR initialization schedule is not the frozen canonical plan");
            }
            if (actual.initializationPlan().hasCycles()) {
                add(CompilerDiagnosticCodes.MODULE_EAGER_INITIALIZATION_CYCLE,
                        actual.initializationPlan().cycles().getFirst().primarySpan(),
                        "typed IR cannot publish an eager initialization cycle");
            }
            if (!actual.flowMetadata().isExact(semantic.semanticFlowFacts())
                    || !actual.flowMetadata().equals(expectedMetadata.flowMetadata())) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, rootSpan(),
                        "IR flow metadata is not the exact producer-owned flow artifact");
            }
            validateFlowSiteEvidence(actual.flowMetadata());

            nodesBySite.clear();
            for (IrModule module : ir.modules()) {
                IrTraversal.walk(module.body(), node -> node.siteId().ifPresent(site -> {
                    if (nodesBySite.put(site, node) != null) {
                        add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                                "more than one IR source node carries a flow site");
                    }
                }));
                IrModuleState expectedState = expectedModuleState(module.moduleId());
                if (expectedState == null || !module.state().equals(expectedState)) {
                    add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, module.span(),
                            "IR module state is not the exact resolved module projection");
                }
            }

            if (actual.expressionSites().size() != expressionsBySite.size()) {
                add(CompilerDiagnosticCodes.IR_UNSUPPORTED_NODE, rootSpan(),
                        "IR expression-site index does not cover every typed expression exactly once");
            }
            Set<FlowSiteId> seenExpressionSites = new HashSet<>();
            for (IrExpressionSite site : actual.expressionSites()) {
                TypedExpression expression = expressionsBySite.get(site.siteId());
                IrNode actualNode = nodesBySite.get(site.siteId());
                if (expression == null || actualNode == null) {
                    add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, site.span(),
                            "IR expression-site identity is not a typed source site");
                    continue;
                }
                seenExpressionSites.add(site.siteId());
                if (!site.moduleId().equals(ModuleId.fromSourceId(expression.span().sourceId()))
                        || !site.span().equals(expression.span())
                        || !site.type().equals(expression.type())
                        || site.kind() != expression.kind()
                        || actualNode != site.node()
                        || !actualNode.equals(site.node())) {
                    add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, site.span(),
                            "IR expression-site metadata does not retain exact source identity/type/span");
                }
            }
            if (!seenExpressionSites.equals(expressionsBySite.keySet())) {
                add(CompilerDiagnosticCodes.IR_UNSUPPORTED_NODE, rootSpan(),
                        "IR expression-site index has missing or extra source identities");
            }

            List<IrEvaluationOrder> expectedOrders = expectedEvaluationOrders();
            if (!actual.evaluationOrders().equals(expectedOrders)) {
                add(CompilerDiagnosticCodes.IR_EVALUATION_ORDER, rootSpan(),
                        "IR evaluation edges do not match explicit typed child order/control flow");
            }
            Set<FlowSiteId> runtimeSites = new HashSet<>();
            for (IrModule module : ir.modules()) {
                IrTraversal.walk(module.body(), node -> {
                    if (node instanceof IrNode.RuntimeCheck check) {
                        check.failureSiteId().ifPresent(runtimeSites::add);
                    }
                });
            }
            for (IrFailureSite failure : actual.failureSites()) {
                if (!runtimeSites.contains(failure.siteId())
                        && failure.expressionKind() != TypedExpressionKind.NARROWING) {
                    add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, failure.span(),
                            "IR failure site has no corresponding explicit runtime check");
                }
            }
        }

        private boolean retainedEffectSite(FlowSiteId site, SourceSpan span) {
            return semantic.resolvedGraph().sessionFlowCertificate()
                    .map(value -> value.certifiesEffectPathEntry(span, site)).orElse(false)
                    || semantic.resolvedGraph().retainedModules().producers().stream()
                    .flatMap(record -> record.producerGraph().semanticFlowFacts().eagerEffectFacts().stream())
                    .map(fact -> fact.witness()).anyMatch(witness -> {
                        for (int index = 0; index < witness.sourceSitePath().size(); index++) {
                            if (witness.sourceSitePath().get(index).equals(site)
                                    && witness.sourcePath().get(index).equals(span)) return true;
                        }
                        return false;
                    });
        }

        /**
         * Retained nil provenance is accepted only when the exact producer
         * evidence (site, span and route) is carried by the compiler-issued
         * session certificate.  A mismatched span for a current-graph site is
         * never certified; only provenance whose site is absent from this
         * graph can fall back to the certificate.
         */
        private boolean retainedNilSite(io.mindspice.lyra.compiler.semantic.flow.NilProvenance nil) {
            if (flowSiteSpans.containsKey(nil.sourceSite())) {
                return false;
            }
            return semantic.resolvedGraph().sessionFlowCertificate()
                    .map(certificate -> certificate.certifiesNil(nil))
                    .orElse(false);
        }

        private void validateFlowSiteEvidence(IrFlowMetadata metadata) {
            for (var event : metadata.events()) {
                if (event.siteId().isEmpty()) {
                    add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, event.span(),
                            "flow event has no producer-issued source-site identity");
                    continue;
                }
                FlowSiteId site = event.siteId().orElseThrow();
                SourceSpan expected = flowSiteSpans.get(site);
                if ((expected == null || !expected.equals(event.span()))
                        && !(event.kind() == io.mindspice.lyra.compiler.semantic.flow.SemanticFlowEvent.Kind.EFFECT
                        && retainedEffectSite(site, event.span()))) {
                    add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, event.span(),
                            "flow event site does not identify its exact source span");
                }
            }
            for (var summary : metadata.summaries()) {
                for (var call : summary.callReferences()) {
                    if (call.siteId().isEmpty()) {
                        add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, call.span(),
                                "summary call has no producer-issued source-site identity");
                        continue;
                    }
                    FlowSiteId site = call.siteId().orElseThrow();
                    SourceSpan expected = flowSiteSpans.get(site);
                    if (expected == null || !expected.equals(call.span())) {
                        add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, call.span(),
                                "summary call site does not identify its exact source span");
                    }
                }
            }
            for (var dependency : ir.initializationPlan().dependencies()) {
                if (dependency.sourceSitePath().isEmpty()) {
                    add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, dependency.effectSpan(),
                            "initialization dependency has no producer source-site path");
                } else if (dependency.sourceSitePath().size() != dependency.sourcePath().size()) {
                    add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, dependency.effectSpan(),
                            "initialization dependency source-site and source-span paths differ");
                } else {
                    for (int index = 0; index < dependency.sourceSitePath().size(); index++) {
                        SourceSpan expected = flowSiteSpans.get(dependency.sourceSitePath().get(index));
                        if ((expected == null || !expected.equals(dependency.sourcePath().get(index)))
                                && !retainedEffectSite(dependency.sourceSitePath().get(index), dependency.sourcePath().get(index))) {
                            add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK,
                                    dependency.sourcePath().get(index),
                                    "initialization dependency path has foreign source provenance");
                        }
                    }
                }
            }
            for (var effect : metadata.eagerEffects()) {
                if (effect.sourceSitePath().isEmpty()) {
                    add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, effect.effectSpan(),
                            "effect witness has no producer source-site path");
                } else if (effect.sourceSitePath().size() != effect.sourcePath().size()) {
                    add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, effect.effectSpan(),
                            "effect source-site and source-span paths have different lengths");
                } else {
                    for (int index = 0; index < effect.sourceSitePath().size(); index++) {
                        SourceSpan expected = flowSiteSpans.get(effect.sourceSitePath().get(index));
                        if ((expected == null || !expected.equals(effect.sourcePath().get(index)))
                                && !retainedEffectSite(effect.sourceSitePath().get(index), effect.sourcePath().get(index))) {
                            add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK,
                                    effect.sourcePath().get(index),
                                    "effect path does not retain exact producer site provenance");
                        }
                    }
                }
            }
            var retainedCallables = semantic.resolvedGraph().sessionFlowCertificate()
                    .map(certificate -> certificate.callableTransferVerifier(
                            semantic, semantic.flowFacts().callableSummaries()))
                    .orElse(null);
            for (var callable : metadata.callableFlows()) {
                if (callable.lambdaId().isEmpty()) {
                    continue;
                }
                // The semantic validator owns creation-site exactness for a
                // lambda of this generation.  Only a foreign (retained) lambda
                // falls back to predecessor-certificate or exact
                // consumer-derived route evidence.
                boolean retained = retainedCallables != null
                        && semantic.resolvedGraph().sessionFlowCertificate()
                        .filter(certificate -> certificate.callableSummaries()
                                .summary(callable.lambdaId().orElseThrow()).isPresent())
                        .map(certificate -> retainedCallables.test(callable)
                                || certificate.certifiesLinkedCallable(callable))
                        .orElse(false);
                if (callable.creationSite().isEmpty()) {
                    if (!retained) {
                        add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, rootSpan(),
                                "lambda callable flow has no producer creation site");
                    }
                    continue;
                }
                TypedExpression creation = expressionsBySite.get(callable.creationSite().orElseThrow());
                if (creation == null || creation.kind() != TypedExpressionKind.LAMBDA
                        || creation.lambdaId().filter(callable.lambdaId().orElseThrow()::equals).isEmpty()) {
                    if (!retained) {
                        add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, rootSpan(),
                                "callable flow creation site does not identify its lambda creation");
                    }
                }
            }
            for (var nil : metadata.nilProvenance()) {
                SourceSpan expected = flowSiteSpans.get(nil.sourceSite());
                if ((expected == null || !expected.equals(nil.sourceSpan()))
                        && !retainedNilSite(nil)) {
                    add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, nil.sourceSpan(),
                            "nil provenance does not retain its exact source site");
                }
            }
            for (var aggregate : metadata.aggregateProvenance()) {
                if (aggregate.identity() instanceof io.mindspice.lyra.compiler.semantic.flow.ArrayIdentity.SessionOrigin session) {
                    var declaration = ir.declarations().stream()
                            .filter(value -> value.id().equals(session.originDeclaration())).findFirst().orElse(null);
                    boolean certifiedExternal = declaration != null
                            && declaration.externalBinding().isPresent()
                            && semantic.resolvedGraph().sessionFlowCertificate()
                            .map(certificate -> certificate.certifiesBinding(
                                    declaration.externalBinding().orElseThrow()))
                            .orElse(false);
                    if (declaration == null || declaration.externalBinding().isEmpty()
                            || !(declaration.externalBinding().orElseThrow().supportsSessionStorage()
                            || certifiedExternal)
                            || !declaration.moduleId().equals(session.ownerModule())
                            || !declaration.scopeId().equals(aggregate.witness().scopeId())
                            || !declaration.span().equals(aggregate.witness().sourceSpan())
                            || aggregate.originSite().isPresent()
                            || !io.mindspice.lyra.compiler.semantic.flow.ValueAlternative.typeAt(
                                    declaration.externalBinding().orElseThrow().type(), session.sourceRoute())
                                    .withoutQualifiers().equals(session.arrayType())) {
                        add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, aggregate.witness().sourceSpan(),
                                "session aggregate provenance does not retain its exact external contract");
                    }
                    continue;
                }
                // Imported facts whose owner module lies outside the current
                // submission graph (registered-root storage, older retained
                // producers) cannot point at a flow site of this graph.  The
                // compiler-issued certificate retains the exact producer
                // evidence instead; the session never re-synthesizes a
                // session-owned identity for such storage.
                if (!modules.contains(aggregate.identity().ownerModule())) {
                    var fact = new io.mindspice.lyra.compiler.semantic.flow.AggregateIdentityFact(
                            aggregate.identity(), aggregate.route(), aggregate.witness());
                    boolean sealedCurrentFact = semantic.flowFacts().events().stream()
                            .filter(event -> event.span().equals(aggregate.witness().useSpan()))
                            .flatMap(event -> event.value().alternatives().stream())
                            .flatMap(value -> value.aggregateIdentities().stream())
                            .anyMatch(fact::equals);
                    boolean certified = aggregate.identity().isImported()
                            && (sealedCurrentFact
                            || semantic.resolvedGraph().sessionFlowCertificate()
                            .map(certificate -> certificate.certifiesAggregate(fact))
                            .orElse(false));
                    if (!certified) {
                        add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK,
                                aggregate.witness().sourceSpan(),
                                "out-of-graph aggregate provenance is not producer-certified");
                    }
                    continue;
                }
                SourceSpan expected = aggregate.originSite().map(flowSiteSpans::get).orElse(null);
                if (expected == null || !expected.equals(aggregate.witness().sourceSpan())) {
                    add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK,
                            aggregate.witness().sourceSpan(),
                            "aggregate provenance does not retain its exact origin site");
                }
            }
        }

        private IrModuleState expectedModuleState(ModuleId moduleId) {
            ResolvedModule resolved = semantic.resolvedGraph().module(moduleId).orElse(null);
            if (resolved == null) {
                return null;
            }
            List<DeclarationId> functionSlots = resolved.declarations().stream()
                    .map(id -> semantic.declaration(id).orElseThrow())
                    .filter(value -> value.kind() != DeclarationKind.EXTERNAL
                            && value.kind() != DeclarationKind.PARAMETER
                            && value.kind() != DeclarationKind.PREDICATE_BINDING)
                    .filter(value -> value.contract().filter(contract -> contract.valueType().withoutQualifiers()
                            instanceof FunctionType).isPresent())
                    .map(io.mindspice.lyra.compiler.semantic.TypedDeclaration::id)
                    .toList();
            List<DeclarationId> eager = resolved.declarations().stream()
                    .map(id -> semantic.resolvedGraph().declaration(id).orElseThrow())
                    .filter(value -> value.kind() == DeclarationKind.LET
                            && value.initializerLambda().isEmpty())
                    .map(io.mindspice.lyra.compiler.semantic.ResolvedDeclaration::id)
                    .toList();
            List<DeclarationId> imports = resolved.imports().stream()
                    .map(io.mindspice.lyra.compiler.semantic.ResolvedImportBinding::declarationId)
                    .toList();
            List<io.mindspice.lyra.compiler.semantic.ResolvedExport> moduleExports = semantic.resolvedGraph()
                    .exports().stream().filter(value -> value.moduleId().equals(moduleId)).toList();
            List<io.mindspice.lyra.compiler.identity.ExportId> exports = moduleExports.stream()
                    .map(value -> value.exportId()).flatMap(Optional::stream).sorted().toList();
            List<DeclarationId> exportedDeclarations = moduleExports.stream()
                    .map(io.mindspice.lyra.compiler.semantic.ResolvedExport::declarationId).toList();
            return new IrModuleState(moduleId, resolved.rootScope(), resolved.declarations(),
                    resolved.references(), resolved.lambdas(), imports, functionSlots, eager, exports,
                    exportedDeclarations);
        }

        private List<IrEvaluationOrder> expectedEvaluationOrders() {
            ArrayList<IrEvaluationOrder> result = new ArrayList<>();
            for (TypedModule module : semantic.modules()) {
                if (!emits(module.moduleId())) continue;
                List<IrEvaluationOrder.Edge> edges = new ArrayList<>();
                for (int index = 0; index < module.forms().size(); index++) {
                    edges.add(new IrEvaluationOrder.Edge(index,
                            semantic.flowSiteId(module.forms().get(index)),
                            IrEvaluationOrder.EdgeKind.STRICT));
                }
                result.add(new IrEvaluationOrder(Optional.empty(), module.moduleId(), module.span(),
                        IrEvaluationOrder.Kind.MODULE_SEQUENCE, edges));
            }
            for (TypedExpression expression : semantic.expressions()) {
                FlowSiteId site = semantic.flowSiteId(expression);
                if (!emits(ModuleId.fromSourceId(expression.span().sourceId()))) continue;
                IrEvaluationOrder.Kind kind = switch (expression.kind()) {
                    case SHORT_CIRCUIT -> IrEvaluationOrder.Kind.SHORT_CIRCUIT;
                    case CONDITIONAL -> IrEvaluationOrder.Kind.BRANCH;
                    case COALESCE -> IrEvaluationOrder.Kind.COALESCE;
                    case MATCH, COND -> IrEvaluationOrder.Kind.MATCH;
                    case NOMINAL_DECLARATION -> IrEvaluationOrder.Kind.INSTANCE_INITIALIZATION;
                    default -> IrEvaluationOrder.Kind.STRICT;
                };
                List<IrEvaluationOrder.Edge> edges = new ArrayList<>();
                for (int index = 0; index < expression.children().size(); index++) {
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
                    edges.add(new IrEvaluationOrder.Edge(index,
                            semantic.flowSiteId(expression.children().get(index)), edgeKind));
                }
                result.add(new IrEvaluationOrder(Optional.of(site),
                        ModuleId.fromSourceId(expression.span().sourceId()), expression.span(), kind, edges));
            }
            result.sort(Comparator.naturalOrder());
            return List.copyOf(result);
        }

        private void validateNode(IrNode node, SourceSpan parentSpan, boolean rootSequence) {
            if (node == null) {
                add(CompilerDiagnosticCodes.IR_MISSING_SPAN, parentSpan,
                        "IR contains a null node");
                return;
            }
            if (node.span() == null) {
                add(CompilerDiagnosticCodes.IR_MISSING_SPAN, parentSpan,
                        "IR node has no source span");
                return;
            }
            if (node.type() == null) {
                add(CompilerDiagnosticCodes.IR_MISSING_TYPE, node.span(),
                        "IR node has no exact Lyra type");
                return;
            }
            validateSourceSite(node);
            if (!parentSpan.sourceId().equals(node.span().sourceId())) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "IR child belongs to another source");
            }
            if (node.span().startOffset() < parentSpan.startOffset()
                    || node.span().endOffset() > parentSpan.endOffset()) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "IR child span lies outside its enclosing operation");
            }
            switch (node) {
                case IrNode.Constant constant -> validateConstant(constant);
                case IrNode.Reference reference -> validateReference(reference);
                case IrNode.CaptureReference reference -> validateCaptureReference(reference);
                case IrNode.Declaration declaration -> validateDeclaration(declaration);
                case IrNode.NominalDeclaration declaration -> validateNominalDeclaration(declaration);
                case IrNode.Construction construction -> validateConstruction(construction);
                case IrNode.Rebinding rebinding -> validateRebinding(rebinding);
                case IrNode.Sequence sequence -> {
                    validateOrdered(sequence.forms(), sequence.span());
                    for (IrNode child : sequence.forms()) {
                        validateNode(child, sequence.span(), false);
                    }
                    if (sequence.forms().isEmpty() && sequence.type() != PrimitiveType.UNIT) {
                        add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, sequence.span(),
                                "an empty sequence must have Unit type");
                    }
                }
                case IrNode.Block block -> validateBlock(block);
                case IrNode.ArrayLiteral array -> validateArrayLiteral(array);
                case IrNode.TupleLiteral tuple -> validateTupleLiteral(tuple);
                case IrNode.Range range -> {
                    if (!(range.type() instanceof io.mindspice.lyra.compiler.types.RangeType type)) {
                        add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, range.span(),
                                "range construction requires an unqualified Range type");
                    } else {
                        for (IrNode bound : range.childrenInEvaluationOrder()) {
                            if (!bound.type().equals(type.elementType())) {
                                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, bound.span(),
                                        "range bounds and step must match the element type");
                            }
                        }
                    }
                    for (IrNode bound : range.childrenInEvaluationOrder()) {
                        validateNode(bound, range.span(), false);
                    }
                }
                case IrNode.Loop loop -> {
                    validateCallIdentity(loop.callId(), loop.siteId(), loop.span());
                    if (!io.mindspice.lyra.compiler.semantic.CallbackLoop.valid(
                            loop.conditionControlled()
                                    ? io.mindspice.lyra.compiler.semantic.TypedExpressionKind.WHILE
                                    : io.mindspice.lyra.compiler.semantic.TypedExpressionKind.ITER,
                            loop.type(), List.of(loop.input().type(), loop.action().type()))) {
                        add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, loop.span(), "invalid callback loop contract");
                    }
                    validateNode(loop.input(), loop.span(), false);
                    validateNode(loop.action(), loop.span(), false);
                }
                case IrNode.IndexAccess index -> validateIndexAccess(index);
                case IrNode.Operator operator -> validateOperator(operator);
                case IrNode.ShortCircuit shortCircuit -> validateShortCircuit(shortCircuit);
                case IrNode.Conversion conversion -> validateConversion(conversion);
                case IrNode.Narrowing narrowing -> validateNarrowing(narrowing);
                case IrNode.Branch branch -> validateBranch(branch);
                case IrNode.Coalesce coalesce -> validateCoalesce(coalesce);
                case IrNode.Match match -> validateMatch(match);
                case IrNode.DirectCall call -> validateDirectCall(call);
                case IrNode.CallableCall call -> validateCallableCall(call);
                case IrNode.Lambda lambda -> validateLambda(lambda);
                case IrNode.Access access -> validateAccess(access);
                case IrNode.RuntimeCheck check -> validateRuntimeCheck(check);
            }
        }

        private void validateSourceSite(IrNode node) {
            node.siteId().ifPresent(site -> {
                TypedExpression expression = expressionsBySite.get(site);
                if (expression == null
                        || !expression.span().equals(node.span())
                        || !expression.type().equals(node.type())) {
                    add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                            "IR node carries an unknown or mismatched flow-site identity");
                }
            });
        }

        private void validateConstant(IrNode.Constant node) {
            if (!(node.value() instanceof IrConstantValue.NilValue) && node.type().isNilable()) {
                add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, node.span(),
                        "non-nil constants must be lifted through an explicit conversion node");
            }
            switch (node.value()) {
                case IrConstantValue.BooleanValue ignored -> requireBase(node, PrimitiveType.BOOL);
                case IrConstantValue.NilValue ignored -> {
                    if (!node.type().isNilable() || node.type().isMutable()) {
                        add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                                "nil constant must use an immutable @nil type");
                    }
                }
                case IrConstantValue.IntegerValue value -> {
                    LyraType constantType = node.type().withoutQualifiers();
                    boolean representable = constantType instanceof PrimitiveType primitive
                            && primitive.isNumeric()
                            && LiteralTyping.representableAs(value.exactValue(), primitive);
                    if (!representable && !validSignedMinimumOperand(node, value)) {
                        add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                                "integer constant does not have a representable numeric type");
                    }
                    if (value.exactValue().forcedType().isPresent()
                            && !value.exactValue().forcedType().orElseThrow()
                            .equals(constantType)) {
                        // The literal may be wrapped by a conversion, but an
                        // unwrapped constant retains its forced source type.
                        add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                                "integer constant forced type and IR type disagree");
                    }
                }
                case IrConstantValue.DecimalValue value -> {
                    LyraType constantType = node.type().withoutQualifiers();
                    if (!(constantType instanceof PrimitiveType primitive)
                            || !primitive.isFloating()
                            || !LiteralTyping.representableAs(value.exactValue(), primitive)) {
                        add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                                "decimal constant does not have a representable floating type");
                    }
                    if (value.exactValue().forcedType().isPresent()
                            && !value.exactValue().forcedType().orElseThrow().equals(constantType)) {
                        add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                                "decimal constant forced type and IR type disagree");
                    }
                }
                case IrConstantValue.StringValue ignored -> requireBase(node, PrimitiveType.STRING);
                case IrConstantValue.CharacterValue ignored -> requireBase(node, PrimitiveType.CHAR);
                case IrConstantValue.UnitValue ignored -> requireBase(node, PrimitiveType.UNIT);
            }
        }

        private void validateReference(IrNode.Reference node) {
            ReferenceId referenceId = requireReference(node.referenceId(), node.span());
            DeclarationId declarationId = requireDeclaration(node.targetDeclaration(), node.span());
            if (node.referenceKind() == ReferenceKind.MODULE_NAMESPACE) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                        "a module namespace cannot be emitted as a value reference");
            }
            if (referenceId != null && declarationId != null) {
                TypedReference typed = semantic.reference(referenceId).orElse(null);
                if (typed == null || typed.kind() != node.referenceKind()
                        || typed.type().isEmpty()
                        || !typed.type().orElseThrow().equals(node.type())
                        || typed.targetDeclaration().isEmpty()
                        || !typed.targetDeclaration().orElseThrow().equals(declarationId)) {
                    add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                            "IR reference and declaration links disagree");
                }
            }
            node.capture().ifPresent(capture -> {
                if (semantic.resolvedGraph().capture(capture).isEmpty()) {
                    add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                            "IR capture identity is unresolved");
                }
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "captured values must use the explicit capture-reference node");
            });
        }

        private void validateCaptureReference(IrNode.CaptureReference node) {
            ReferenceId referenceId = requireReference(node.referenceId(), node.span());
            DeclarationId declarationId = requireDeclaration(node.declarationId(), node.span());
            if (node.captureId().isEmpty()
                    || semantic.resolvedGraph().capture(node.captureId().orElseThrow()).isEmpty()) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                        "capture reference has no resolved capture identity");
            }
            if (referenceId != null && declarationId != null) {
                TypedReference reference = semantic.reference(referenceId).orElse(null);
                if (reference == null || reference.kind() != node.referenceKind()
                        || reference.type().isEmpty()
                        || !reference.type().orElseThrow().equals(node.type())
                        || reference.capture().isEmpty()
                        || !reference.capture().orElseThrow().equals(node.captureId().orElse(null))
                        || reference.targetDeclaration().isEmpty()
                        || !reference.targetDeclaration().orElseThrow().equals(declarationId)) {
                    add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                            "capture reference links do not match the resolved graph");
                }
            }
        }

        private void validateNominalDeclaration(IrNode.NominalDeclaration node) {
            var nominal = semantic.resolvedGraph().nominals().stream()
                    .filter(value -> value.declaration().equals(node.declarationId())).findFirst().orElse(null);
            TypedExpression source = node.siteId().map(expressionsBySite::get).orElse(null);
            if (nominal == null || !nominal.schema().equals(node.schema())
                    || !nominal.self().equals(node.self()) || !nominal.members().equals(node.members())
                    || !nominal.constructor().equals(node.constructor())
                    || !node.initializedFields().equals(node.members())
                    || source == null || source.kind() != TypedExpressionKind.NOMINAL_DECLARATION
                    || source.nominalInitialization().isEmpty()) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                        "nominal initialization must retain its exact schema and producer-issued completion proof");
                return;
            }
            source.nominalInitialization().orElseThrow().requireMatches(node.declarationId(), source.children());
            if (node.initializers().size() != source.children().size()) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(), "nominal initializer inventory differs from its proof");
            }
            for (int child = 0; child < Math.min(node.initializers().size(), source.children().size()); child++) {
                if (!node.initializers().get(child).siteId().equals(Optional.of(semantic.flowSiteId(source.children().get(child))))) {
                    add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(), "nominal initializer order differs from its certified source tree");
                }
            }
            int index = 0;
            for (var member : node.schema().members()) {
                if (member.hasInitializer()) {
                    if (index >= node.initializers().size()
                            || !assignable(node.initializers().get(index), member.type())) {
                        add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, node.span(),
                                "nominal field initializer does not match its exact contract");
                    }
                    index++;
                }
            }
            if (node.constructor().isPresent()
                    && (index >= node.initializers().size()
                    || !(node.initializers().get(index) instanceof IrNode.Lambda lambda)
                    || !lambda.lambdaId().equals(node.constructor()))) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(), "nominal constructor identity differs from its schema");
            }
            // Defaults execute in field order, then the constructor, independently
            // of where the constructor declaration occurs textually in the class.
            // Exact proof-bound child sites above certify this role-based order.
            node.initializers().forEach(child -> validateNode(child, node.span(), false));
        }

        private void validateConstruction(IrNode.Construction node) {
            var nominal = semantic.resolvedGraph().nominals().stream()
                    .filter(value -> value.declaration().equals(node.declarationId())).findFirst().orElse(null);
            TypedExpression source = node.siteId().map(expressionsBySite::get).orElse(null);
            TypedReference reference = node.referenceId().flatMap(semantic::reference).orElse(null);
            if (nominal == null || !nominal.schema().type().equals(node.type())
                    || source == null || source.kind() != TypedExpressionKind.CONSTRUCTION
                    || !source.declarationId().equals(Optional.of(node.declarationId()))
                    || reference == null || !reference.type().equals(Optional.of(node.type()))
                    || !source.link().flatMap(value -> value.referenceId()).equals(node.referenceId())) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(), "construction has an unresolved nominal origin");
                return;
            }
            var parameters = nominal.schema().constructorParameters();
            if (parameters.size() != node.arguments().size()) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(), "constructor arity differs from its schema");
            } else {
                for (int index = 0; index < parameters.size(); index++) {
                    if (!assignable(node.arguments().get(index), parameters.get(index))) {
                        add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, node.span(),
                                "constructor argument does not match its exact parameter contract");
                    }
                }
            }
            validateOrdered(node.arguments(), node.span());
            node.arguments().forEach(child -> validateNode(child, node.span(), false));
        }

        private void validateDeclaration(IrNode.Declaration node) {
            DeclarationId id = requireDeclaration(node.declarationId(), node.span());
            if (node.declarationKind() != DeclarationKind.LET) {
                add(CompilerDiagnosticCodes.IR_UNSUPPORTED_NODE, node.span(),
                        "only executable let declarations may occur in an IR sequence");
            }
            BindingContract contract = node.contract().orElse(null);
            if (contract == null) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                        "declaration has no complete binding contract");
            } else {
                if (id != null) {
                    BindingContract typedContract = semantic.contract(id).orElse(null);
                    if (typedContract == null || !typedContract.equals(contract)) {
                        add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                                "declaration contract does not match the typed graph");
                    }
                }
                if (!assignable(node.initializer(), contract.valueType())) {
                    add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, node.span(),
                            "declaration initializer is not assignable with a recorded conversion");
                }
            }
            if (node.type() != PrimitiveType.UNIT) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "declaration operations must have Unit type");
            }
            validateNode(node.initializer(), node.span(), false);
        }

        private void validateRebinding(IrNode.Rebinding node) {
            DeclarationId id = requireDeclaration(node.targetDeclaration(), node.span());
            if (node.type() != PrimitiveType.UNIT) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "rebindings must have Unit type");
            }
            validateNode(node.target(), node.span(), false);
            validateNode(node.value(), node.span(), false);
            validateOrdered(List.of(node.target(), node.value()), node.span());
            boolean directBinding = node.target() instanceof IrNode.Reference
                    || node.target() instanceof IrNode.CaptureReference;
            boolean checkedArrayElement = node.target() instanceof IrNode.RuntimeCheck check
                    && check.checkKind() == IrCheckKind.BOUNDS
                    && check.operand() instanceof IrNode.IndexAccess;
            boolean nominalField = node.target() instanceof IrNode.Access access
                    && access.accessKind() == AccessKind.MEMBER_VALUE && access.declarationId().isPresent()
                    && access.receiver().isPresent() && access.receiver().orElseThrow().type().withoutQualifiers()
                    instanceof io.mindspice.lyra.compiler.types.NominalType;
            Optional<DeclarationId> target = rebindingTargetDeclaration(node.target());
            if ((!directBinding && !checkedArrayElement && !nominalField) || target.isEmpty()) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "rebinding target must be a resolved binding or checked array element");
            } else if (id != null && !target.orElseThrow().equals(id)) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                        "rebinding target link does not match its declaration identity");
            }
            validateRebindingMetadata(node, id);
            if (id != null) {
                BindingContract contract = semantic.contract(id).orElse(null);
                if (contract == null) {
                    add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                            "reassignment target has no typed contract");
                } else {
                    LyraType assignmentType = contract.valueType();
                    if (nominalField) assignmentType = node.target().type();
                    if (node.target() instanceof IrNode.RuntimeCheck check
                            && check.checkKind() == IrCheckKind.BOUNDS
                            && check.operand() instanceof IrNode.IndexAccess index) {
                        assignmentType = index.type();
                    }
                    if (!assignable(node.value(), assignmentType)) {
                        add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, node.span(),
                                "reassignment value is not assignable with a recorded conversion");
                    }
                }
                semantic.resolvedGraph().declaration(id).ifPresent(declaration -> {
                    boolean constructorSelfMutation = declaration.kind() == DeclarationKind.SELF
                            && node.mutationKind().filter(kind -> kind != MutationKind.REBINDING).isPresent()
                            && node.rootReference().flatMap(semantic.resolvedGraph()::reference)
                            .flatMap(io.mindspice.lyra.compiler.semantic.ResolvedReference::fromLambda)
                            .filter(lambda -> semantic.resolvedGraph().nominals().stream().anyMatch(nominal ->
                                    nominal.self().equals(declaration.id())
                                            && nominal.constructor().filter(lambda::equals).isPresent()))
                            .isPresent();
                    boolean permittedImmutableSelfMutation = declaration.kind() == DeclarationKind.SELF
                            && (nominalField || constructorSelfMutation);
                    if (!declaration.isMutable() && !permittedImmutableSelfMutation) {
                        add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                                "IR rebinds an immutable declaration");
                    }
                    if (declaration.kind() != DeclarationKind.SELF
                            && node.mutationKind().filter(kind -> kind == MutationKind.ARRAY_ELEMENT).isPresent()) {
                        semantic.resolvedGraph().mutations().stream()
                                .filter(mutation -> mutation.span().equals(node.target().span()))
                                .findFirst()
                                .filter(mutation -> !selfAliasProvenance.permitsMutation(
                                        semantic.resolvedGraph(), mutation))
                                .ifPresent(mutation -> add(CompilerDiagnosticCodes.IR_INVALID_GRAPH,
                                        node.span(),
                                        "IR array-element mutation through a self alias is not constructor-owned"));
                    }
                });
            }
        }

        private void validateRebindingMetadata(IrNode.Rebinding node, DeclarationId id) {
            if (node.rootReference().isEmpty() || node.mutationKind().isEmpty()) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                        "rebinding is missing its canonical root reference or mutation kind");
                return;
            }
            FlowSiteId site = node.siteId().orElse(null);
            var event = site == null ? null : semantic.semanticFlowFacts().events().stream()
                    .filter(value -> value.siteId().filter(site::equals).isPresent())
                    .filter(value -> value.kind() == io.mindspice.lyra.compiler.semantic.flow.SemanticFlowEvent.Kind.MUTATION)
                    .findFirst().orElse(null);
            if (id == null
                    || event != null && (event.declarationId().filter(id::equals).isEmpty()
                    || event.referenceId().isPresent()
                    && !event.referenceId().equals(node.rootReference())
                    || !event.route().orElse(ProjectionPath.root()).equals(node.route()))
                    || !semantic.mutations().stream().anyMatch(mutation ->
                    mutation.span().equals(node.target().span())
                            && mutation.rootDeclaration().equals(id)
                            && mutation.rootReference().equals(node.rootReference())
                            && mutation.kind().equals(node.mutationKind().orElse(null)))) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                        "rebinding does not retain the exact canonical mutation provenance");
            }
        }

        private void validateArrayLiteral(IrNode.ArrayLiteral node) {
            validateOrdered(node.elements(), node.span());
            if (node.siteId().isEmpty()
                    || node.allocationSite().isEmpty()
                    || !node.siteId().equals(node.allocationSite())) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                        "array allocation is missing its canonical allocation flow site");
            }
            for (IrNode element : node.elements()) {
                validateNode(element, node.span(), false);
            }
            if (node.type().isNilable() || !(node.type().withoutQualifiers() instanceof ArrayType array)) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "array literal must have one non-nil homogeneous Array type");
                return;
            }
            if (node.elements().stream().anyMatch(element -> !assignable(element, array.elementType()))) {
                add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, node.span(),
                        "array element conversion is missing or changes the invariant element type");
            }
        }

        private void validateTupleLiteral(IrNode.TupleLiteral node) {
            validateOrdered(node.elements(), node.span());
            for (IrNode element : node.elements()) {
                validateNode(element, node.span(), false);
            }
            if (node.type().isNilable() || !(node.type().withoutQualifiers() instanceof TupleType tuple)) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "tuple literal must have one non-nil fixed Tuple shape");
                return;
            }
            if (node.elements().size() != tuple.arity()
                    || java.util.stream.IntStream.range(0, node.elements().size())
                    .anyMatch(index -> !assignable(node.elements().get(index), tuple.memberType(index)))) {
                add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, node.span(),
                        "tuple element shape or conversion does not match its exact Tuple type");
            }
        }

        private void validateIndexAccess(IrNode.IndexAccess node) {
            validateNode(node.receiver(), node.span(), false);
            validateNode(node.index(), node.span(), false);
            validateOrdered(List.of(node.receiver(), node.index()), node.span());
            if (node.receiver().type().isNilable() || node.index().type().isNilable()
                    || !node.index().type().isInteger()) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "index access requires a non-nil String/Array receiver and integer index");
                return;
            }
            LyraType receiver = node.receiver().type().withoutQualifiers();
            LyraType expected = receiver == PrimitiveType.STRING
                    ? PrimitiveType.CHAR
                    : receiver instanceof ArrayType array ? array.elementType() : null;
            if (expected == null || !node.type().equals(expected)) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "index access result does not match its receiver type");
            }
            if (node.route().isRoot() || node.route().depth() != 1
                    || node.route().steps().getFirst().isTupleMember()) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "index access has no explicit array projection route");
            }
        }

        private Optional<DeclarationId> rebindingTargetDeclaration(IrNode target) {
            if (target instanceof IrNode.Reference reference) {
                return reference.targetDeclaration();
            }
            if (target instanceof IrNode.CaptureReference reference) {
                return reference.declarationId();
            }
            if (target instanceof IrNode.RuntimeCheck check
                    && check.checkKind() == IrCheckKind.BOUNDS) {
                return rebindingTargetDeclaration(check.operand());
            }
            if (target instanceof IrNode.IndexAccess access) {
                return rebindingTargetDeclaration(access.receiver());
            }
            if (target instanceof IrNode.Access access
                    && access.accessKind() == AccessKind.MEMBER_VALUE
                    && access.receiver().isPresent()) {
                return rebindingTargetDeclaration(access.receiver().orElseThrow());
            }
            return Optional.empty();
        }

        private void validateBlock(IrNode.Block node) {
            var scope = node.scopeId().flatMap(
                    semantic.resolvedGraph().scopeTree()::scope).orElse(null);
            if (scope == null) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                        "block has no resolved scope identity");
            } else if (scope.kind() != ScopeKind.BLOCK || !scope.span().equals(node.span())) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "block scope kind/span does not exactly match the typed source block");
            }
            validateOrdered(node.forms(), node.span());
            for (IrNode child : node.forms()) {
                validateNode(child, node.span(), false);
            }
            LyraType expected = node.forms().isEmpty()
                    ? PrimitiveType.UNIT
                    : node.forms().getLast().type();
            if (!node.type().equals(expected)) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "block type does not match its final expression/Unit result");
            }
        }

        private void validateOperator(IrNode.Operator node) {
            validateOrdered(node.operands(), node.span());
            IrNode.Constant signedMinimum = node.operator() == TokenKind.MINUS
                    && node.operands().size() == 1
                    && node.type().equals(node.operands().getFirst().type())
                    && node.operands().getFirst() instanceof IrNode.Constant constant
                    ? constant : null;
            if (signedMinimum != null) {
                signedMinimumOperands.add(signedMinimum);
            }
            try {
                for (IrNode operand : node.operands()) {
                    validateNode(operand, node.span(), false);
                }
            } finally {
                if (signedMinimum != null) {
                    signedMinimumOperands.remove(signedMinimum);
                }
            }
            validateOperatorShape(node.operator(), node.operands().size(), node.span(), false);
            if (node.operator() == TokenKind.AND || node.operator() == TokenKind.OR) {
                add(CompilerDiagnosticCodes.IR_EVALUATION_ORDER, node.span(),
                        "and/or must use an explicit short-circuit IR node");
            }
            validateOperatorSemantics(node);
            validateConstantArithmetic(node);
            if (node.operator() == TokenKind.AND || node.operator() == TokenKind.OR
                    || node.operator() == TokenKind.XOR || node.operator() == TokenKind.NOT) {
                if (node.type() != PrimitiveType.BOOL) {
                    add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                            "truth operators must return Bool");
                }
            }
        }

        private void validateOperatorSemantics(IrNode.Operator node) {
            List<IrNode> operands = node.operands();
            boolean allNumeric = operands.stream().allMatch(value ->
                    value.type().isNumeric() && !value.type().isNilable());
            boolean allTruthTestable = operands.stream().allMatch(value -> truthTestable(value.type()));
            switch (node.operator()) {
                case AND, OR, XOR -> {
                    if (!allTruthTestable || node.type() != PrimitiveType.BOOL) {
                        add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                                "truth operator operands/result are not well typed");
                    }
                }
                case NOT -> {
                    if (operands.size() != 1 || !allTruthTestable || node.type() != PrimitiveType.BOOL) {
                        add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                                "not operands/result are not well typed");
                    }
                }
                case LESS, LESS_EQUAL, GREATER, GREATER_EQUAL -> {
                    Optional<PrimitiveType> common = commonNumericOperandType(operands);
                    if (!allNumeric || node.type() != PrimitiveType.BOOL || common.isEmpty()) {
                        add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                                "relational operator operands/result have no compatible numeric contract");
                    } else if (operands.stream().anyMatch(value ->
                            !assignable(value, common.orElseThrow()))) {
                        add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, node.span(),
                                "relational operand widening is missing");
                    }
                }
                case EQUAL_EQUAL, NOT_EQUAL -> validateValueEquality(node);
                case IDENTITY_EQUAL, IDENTITY_NOT_EQUAL -> {
                    LyraType identityType = operands.isEmpty()
                            ? null : operands.getFirst().type().withoutQualifiers();
                    boolean sameIdentityType = identityType != null
                            && operands.stream().allMatch(value -> !value.type().isNilable()
                            && value.type().withoutQualifiers().equals(identityType));
                    if (node.type() != PrimitiveType.BOOL || operands.isEmpty()
                            || !sameIdentityType
                            || !identityBearing(identityType)) {
                        add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                                "identity equality requires one identity-bearing operand type");
                    }
                }
                case PLUS -> {
                    boolean allStrings = operands.stream().allMatch(value ->
                            value.type().withoutQualifiers() == PrimitiveType.STRING
                                    && !value.type().isNilable());
                    if ((!allStrings && !allNumeric) || (allStrings
                            ? node.type().withoutQualifiers() != PrimitiveType.STRING
                            : !node.type().isNumeric() || node.type().isNilable())) {
                        add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                                "plus operands/result are not well typed");
                    } else if (!allStrings && operands.stream().anyMatch(
                            value -> !assignable(value, node.type()))) {
                        add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, node.span(),
                                "numeric plus operand widening is missing");
                    }
                }
                case MINUS, ASTERISK, CARET, INCREMENT, DECREMENT -> {
                    if (!allNumeric || !node.type().isNumeric() || node.type().isNilable()) {
                        add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                                "numeric operator operands/result are not well typed");
                    } else if (operands.stream().anyMatch(value -> !assignable(value, node.type()))) {
                        add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, node.span(),
                                "numeric operator operand widening is missing");
                    }
                }
                case PERCENT -> {
                    if (!allNumeric || operands.stream().anyMatch(value -> !value.type().isInteger())
                            || !node.type().isInteger() || node.type().isNilable()) {
                        add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                                "remainder operands/result are not integer typed");
                    } else if (operands.stream().anyMatch(value -> !assignable(value, node.type()))) {
                        add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, node.span(),
                                "remainder operand widening is missing");
                    }
                }
                case SLASH -> {
                    boolean allInteger = operands.stream().allMatch(value -> value.type().isInteger());
                    if (!allNumeric || !node.type().isNumeric() || node.type().isNilable()
                            || allInteger && !node.type().isFloating()) {
                        add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                                "division operands/result are not well typed");
                    } else if (allInteger) {
                        Optional<PrimitiveType> common = commonNumericOperandType(operands);
                        if (common.isEmpty()) {
                            add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                                    "integer division operands have no common integer contract");
                        } else if (operands.stream().anyMatch(value ->
                                !assignable(value, common.orElseThrow()))) {
                            add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, node.span(),
                                    "integer division operand widening is missing");
                        }
                    } else if (operands.stream().anyMatch(
                            value -> !assignable(value, node.type()))) {
                        add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, node.span(),
                                "division operand widening is missing");
                    }
                }
                default -> add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "IR contains an unsupported operator");
            }
        }

        private void validateConstantArithmetic(IrNode.Operator node) {
            if (!node.type().isNumeric() || node.operands().isEmpty()) {
                return;
            }
            List<BigInteger> integers = node.operands().stream()
                    .map(this::constantInteger)
                    .toList();
            if (integers.stream().allMatch(Objects::nonNull)
                    && node.operands().stream().allMatch(value -> value.type().isInteger())) {
                if ((node.operator() == TokenKind.PERCENT || node.operator() == TokenKind.CARET)
                        && integers.size() != 2) {
                    return;
                }
                PrimitiveType domainType = node.operator() == TokenKind.SLASH
                        ? null : node.type().withoutQualifiers() instanceof PrimitiveType primitive
                        && primitive.isInteger() ? primitive : null;
                BigInteger result = integers.getFirst();
                try {
                    switch (node.operator()) {
                        case PLUS -> {
                            result = BigInteger.ZERO;
                            for (BigInteger value : integers) {
                                result = result.add(value);
                                if (domainType != null && !domainType.numericDomain().orElseThrow().contains(result)) {
                                    constantArithmeticError(node);
                                    return;
                                }
                            }
                        }
                        case MINUS -> {
                            if (integers.size() == 1) {
                                result = result.negate();
                                if (domainType != null && !domainType.numericDomain().orElseThrow().contains(result)
                                        && !LiteralTyping.representableAs(
                                        io.mindspice.lyra.compiler.types.ExactNumericLiteral.integer(result.negate()),
                                        domainType)) {
                                    constantArithmeticError(node);
                                    return;
                                }
                            } else {
                                for (BigInteger value : integers.subList(1, integers.size())) {
                                    result = result.subtract(value);
                                    if (domainType != null && !domainType.numericDomain().orElseThrow().contains(result)) {
                                        constantArithmeticError(node);
                                        return;
                                    }
                                }
                            }
                        }
                        case ASTERISK -> {
                            result = integers.getFirst();
                            for (BigInteger value : integers.subList(1, integers.size())) {
                                result = result.multiply(value);
                                if (domainType != null && !domainType.numericDomain().orElseThrow().contains(result)) {
                                    constantArithmeticError(node);
                                    return;
                                }
                            }
                        }
                        case PERCENT -> {
                            if (integers.get(1).signum() == 0) {
                                constantArithmeticError(node);
                                return;
                            }
                            result = integers.getFirst().remainder(integers.get(1));
                            if (domainType != null && !domainType.numericDomain().orElseThrow().contains(result)) {
                                constantArithmeticError(node);
                            }
                        }
                        case CARET -> {
                            if (integers.get(1).signum() < 0
                                    || integers.get(1).bitLength() > 31) {
                                constantArithmeticError(node);
                                return;
                            }
                            result = integers.getFirst().pow(integers.get(1).intValueExact());
                            if (domainType != null && !domainType.numericDomain().orElseThrow().contains(result)) {
                                constantArithmeticError(node);
                            }
                        }
                        case INCREMENT -> {
                            result = result.add(BigInteger.ONE);
                            if (domainType != null && !domainType.numericDomain().orElseThrow().contains(result)) {
                                constantArithmeticError(node);
                            }
                        }
                        case DECREMENT -> {
                            result = result.subtract(BigInteger.ONE);
                            if (domainType != null && !domainType.numericDomain().orElseThrow().contains(result)) {
                                constantArithmeticError(node);
                            }
                        }
                        case SLASH -> {
                            if (integers.size() > 1 && integers.subList(1, integers.size()).stream()
                                    .anyMatch(value -> value.signum() == 0)) {
                                constantArithmeticError(node);
                            }
                        }
                        default -> {
                            // Non-arithmetic operators are checked by their
                            // ordinary type contracts above.
                        }
                    }
                } catch (ArithmeticException | IllegalArgumentException failure) {
                    constantArithmeticError(node);
                }
                return;
            }

            List<Double> floating = node.operands().stream()
                    .map(this::constantFloating)
                    .toList();
            if (floating.stream().allMatch(Objects::nonNull)) {
                if (node.operator() == TokenKind.CARET && floating.size() != 2) {
                    return;
                }
                boolean singlePrecision = node.type().withoutQualifiers() == PrimitiveType.F32;
                double result = floating.getFirst();
                try {
                    switch (node.operator()) {
                        case PLUS -> {
                            result = 0.0d;
                            for (double value : floating) {
                                result = floatingStep(result, value, '+', singlePrecision);
                                if (!finiteResult(result, singlePrecision)) {
                                    constantArithmeticError(node);
                                    return;
                                }
                            }
                        }
                        case MINUS -> {
                            if (floating.size() == 1) {
                                result = floatingStep(0.0d, result, '-', singlePrecision);
                            } else {
                                for (double value : floating.subList(1, floating.size())) {
                                    result = floatingStep(result, value, '-', singlePrecision);
                                    if (!finiteResult(result, singlePrecision)) {
                                        constantArithmeticError(node);
                                        return;
                                    }
                                }
                            }
                        }
                        case ASTERISK -> {
                            for (double value : floating.subList(1, floating.size())) {
                                result = floatingStep(result, value, '*', singlePrecision);
                                if (!finiteResult(result, singlePrecision)) {
                                    constantArithmeticError(node);
                                    return;
                                }
                            }
                        }
                        case SLASH -> {
                            for (double value : floating.subList(1, floating.size())) {
                                result = floatingStep(result, value, '/', singlePrecision);
                                if (!finiteResult(result, singlePrecision)) {
                                    constantArithmeticError(node);
                                    return;
                                }
                            }
                        }
                        case CARET -> {
                            result = floatingPower(result, floating.get(1), singlePrecision);
                            if (!finiteResult(result, singlePrecision)) {
                                constantArithmeticError(node);
                                return;
                            }
                        }
                        case INCREMENT -> {
                            result = floatingStep(result, 1.0d, '+', singlePrecision);
                            if (!finiteResult(result, singlePrecision)) {
                                constantArithmeticError(node);
                                return;
                            }
                        }
                        case DECREMENT -> {
                            result = floatingStep(result, 1.0d, '-', singlePrecision);
                            if (!finiteResult(result, singlePrecision)) {
                                constantArithmeticError(node);
                                return;
                            }
                        }
                        default -> { return; }
                    }
                } catch (ArithmeticException failure) {
                    constantArithmeticError(node);
                }
            }
        }

        private void constantArithmeticError(IrNode.Operator node) {
            add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                    "constant arithmetic violates the checked/trapping operation contract");
        }

        private static double floatingStep(
                double left, double right, char operator, boolean singlePrecision) {
            if (!singlePrecision) {
                return switch (operator) {
                    case '+' -> left + right;
                    case '-' -> left - right;
                    case '*' -> left * right;
                    case '/' -> left / right;
                    default -> throw new AssertionError(operator);
                };
            }
            float leftValue = (float) left;
            float rightValue = (float) right;
            return switch (operator) {
                case '+' -> (double) (leftValue + rightValue);
                case '-' -> (double) (leftValue - rightValue);
                case '*' -> (double) (leftValue * rightValue);
                case '/' -> (double) (leftValue / rightValue);
                default -> throw new AssertionError(operator);
            };
        }

        private static double floatingPower(
                double left, double right, boolean singlePrecision) {
            if (singlePrecision) {
                return (double) (float) Math.pow((float) left, (float) right);
            }
            return Math.pow(left, right);
        }

        private static boolean finiteResult(double value, boolean singlePrecision) {
            return singlePrecision ? Float.isFinite((float) value) : Double.isFinite(value);
        }

        private BigInteger constantInteger(IrNode node) {
            if (node instanceof IrNode.Constant constant
                    && constant.value() instanceof IrConstantValue.IntegerValue value) {
                return value.exactValue().integerValue();
            }
            if (node instanceof IrNode.Conversion conversion
                    && conversion.type().isInteger()) {
                return constantInteger(conversion.operand());
            }
            if (node instanceof IrNode.RuntimeCheck check) {
                return constantInteger(check.operand());
            }
            return null;
        }

        private Double constantFloating(IrNode node) {
            if (node instanceof IrNode.Constant constant
                    && constant.value() instanceof IrConstantValue.DecimalValue value) {
                return value.exactValue().decimalValue().doubleValue();
            }
            if (node instanceof IrNode.Conversion conversion
                    && conversion.type().isFloating()) {
                BigInteger integer = constantInteger(conversion.operand());
                if (integer != null) {
                    return integer.doubleValue();
                }
            }
            if (node instanceof IrNode.RuntimeCheck check) {
                return constantFloating(check.operand());
            }
            return null;
        }

        private void validateValueEquality(IrNode.Operator node) {
            List<IrNode> operands = node.operands();
            if (node.type() != PrimitiveType.BOOL) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "value equality result is not Bool");
            }
            if (operands.isEmpty()) {
                return;
            }
            boolean truthEquality = operands.stream().anyMatch(value ->
                    value.type().withoutQualifiers() == PrimitiveType.BOOL)
                    && operands.stream().allMatch(value -> truthTestable(value.type()));
            if (truthEquality) {
                return;
            }
            boolean allNumeric = operands.stream().allMatch(value ->
                    value.type().isNumeric() && !value.type().isNilable());
            if (allNumeric) {
                Optional<PrimitiveType> common = commonNumericOperandType(operands);
                if (common.isEmpty()) {
                    add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                            "value equality operands have no common numeric contract");
                } else if (operands.stream().anyMatch(value ->
                        !assignable(value, common.orElseThrow()))) {
                    add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, node.span(),
                            "value equality operand conversion is missing");
                }
                return;
            }
            boolean containsNil = operands.stream().anyMatch(value ->
                    value instanceof IrNode.Constant constant
                            && constant.value() instanceof IrConstantValue.NilValue);
            if (!containsNil && operands.stream().anyMatch(value -> value.type().isNilable())) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "nilable value equality requires an explicit nil operand");
                return;
            }
            boolean allFunctionValues = operands.stream().allMatch(value ->
                    value.type().withoutQualifiers() instanceof FunctionType);
            if (!containsNil && allFunctionValues) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "function values require identity equality when no nil operand is present");
                return;
            }
            Optional<LyraType> common = TypeRules.commonType(
                    operands.stream().map(IrNode::type).toList());
            if (common.isEmpty()) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "value equality operands do not have compatible types");
            } else if (containsNil && (!common.orElseThrow().isNilable()
                    || allFunctionValues
                    && !(common.orElseThrow().withoutQualifiers() instanceof FunctionType))) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "nil equality does not retain one nil-compatible common contract");
            } else if (operands.stream().anyMatch(value ->
                    !assignable(value, common.orElseThrow()))) {
                add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, node.span(),
                        "value equality operand conversion is missing");
            }
        }

        private Optional<PrimitiveType> commonNumericOperandType(List<IrNode> operands) {
            if (operands.isEmpty() || operands.stream().anyMatch(value ->
                    !value.type().isNumeric() || value.type().isNilable())) {
                return Optional.empty();
            }
            return TypeRules.commonNumericPrimitiveType(operands.stream()
                    .map(value -> (PrimitiveType) value.type().withoutQualifiers())
                    .toList());
        }

        private void validateShortCircuit(IrNode.ShortCircuit node) {
            validateOrdered(node.operands(), node.span());
            for (IrNode operand : node.operands()) {
                validateNode(operand, node.span(), false);
            }
            validateOperatorShape(node.operator(), node.operands().size(), node.span(), true);
            if (node.type() != PrimitiveType.BOOL) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "short-circuit truth operators must return Bool");
            }
            if (node.operands().stream().anyMatch(value -> !truthTestable(value.type()))) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "short-circuit operand is not truth-testable");
            }
        }

        private void validateConversion(IrNode.Conversion node) {
            validateNode(node.operand(), node.span(), false);
            if (!node.operand().type().equals(node.sourceType())) {
                add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, node.span(),
                        "conversion source type does not match its operand");
            }
            if (node.kind() == ConversionKind.IMPLICIT) {
                ConversionDecision decision = TypeRules.implicitConversion(
                        node.sourceType(), node.type());
                if (decision.kind() != ConversionKind.IMPLICIT
                        || !decision.steps().equals(List.of(node.step()))) {
                    add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, node.span(),
                            "implicit conversion must represent exactly one adjacent type step");
                }
            } else if (node.kind() == ConversionKind.EXPLICIT) {
                LyraType sourceBase = node.sourceType().withoutQualifiers();
                LyraType targetBase = node.type().withoutQualifiers();
                boolean exactContract = !node.sourceType().isNilable()
                        && !node.sourceType().isMutable()
                        && node.type().equals(targetBase);
                boolean numeric = sourceBase.isNumeric() && targetBase.isNumeric()
                        && node.step() == ConversionStep.NUMERIC_EXPLICIT;
                boolean text = targetBase == PrimitiveType.STRING
                        && sourceBase instanceof PrimitiveType
                        && node.step() == ConversionStep.TEXT_EXPLICIT;
                if (!exactContract || (!numeric && !text)
                        || !TypeRules.canExplicitlyConvert(node.sourceType(), node.type())) {
                    add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, node.span(),
                            "explicit conversion source, target, or category is inconsistent");
                }
            } else {
                add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, node.span(),
                        "IR conversion cannot have identity/incompatible classification");
            }
            validateConstantConversion(node);
        }

        private void validateConstantConversion(IrNode.Conversion node) {
            ExactNumericLiteral literal = constantLiteral(node.operand());
            if (literal == null || !(node.type().withoutQualifiers() instanceof PrimitiveType target)
                    || !target.isNumeric()) {
                return;
            }
            boolean valid;
            if (literal.isInteger() && target.isInteger()) {
                valid = target.numericDomain().orElseThrow().contains(literal.integerValue());
            } else if (literal.isDecimal() && target.isInteger()) {
                try {
                    valid = target.numericDomain().orElseThrow().contains(
                            literal.decimalValue().toBigIntegerExact());
                } catch (ArithmeticException notIntegral) {
                    valid = false;
                }
            } else if (node.kind() == ConversionKind.EXPLICIT && literal.isInteger()
                    && target.isFloating()) {
                if (target == PrimitiveType.F32) {
                    float value = literal.integerValue().floatValue();
                    valid = Float.isFinite(value)
                            && (literal.integerValue().signum() == 0 || value != 0.0f);
                } else {
                    double value = literal.integerValue().doubleValue();
                    valid = Double.isFinite(value)
                            && (literal.integerValue().signum() == 0 || value != 0.0d);
                }
            } else {
                valid = LiteralTyping.representableAs(literal, target);
            }
            if (!valid) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "constant conversion is outside the exact target contract");
            }
        }

        private ExactNumericLiteral constantLiteral(IrNode node) {
            if (node instanceof IrNode.Constant constant) {
                return switch (constant.value()) {
                    case IrConstantValue.IntegerValue value -> value.exactValue();
                    case IrConstantValue.DecimalValue value -> value.exactValue();
                    default -> null;
                };
            }
            if (node instanceof IrNode.RuntimeCheck check) {
                return constantLiteral(check.operand());
            }
            return null;
        }

        private void validateNarrowing(IrNode.Narrowing node) {
            validateNode(node.operand(), node.span(), false);
            if (!node.operand().type().equals(node.sourceType())
                    || !node.sourceType().isNilable()
                    || node.type().isNilable()
                    || !narrowed(node.sourceType()).equals(node.type())) {
                add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, node.span(),
                        "nil narrowing is not explicit or has inconsistent source/target types");
            }
        }

        private void validateBranch(IrNode.Branch node) {
            validateNode(node.predicate(), node.span(), false);
            validateNode(node.thenBranch(), node.span(), false);
            node.elseBranch().ifPresent(value -> validateNode(value, node.span(), false));
            validateOrdered(node.elseBranch().isPresent()
                    ? List.of(node.predicate(), node.thenBranch(), node.elseBranch().orElseThrow())
                    : List.of(node.predicate(), node.thenBranch()), node.span());
            if (!truthTestable(node.predicate().type())) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "branch predicate is not truth-testable");
            }
            if (node.elseBranch().isEmpty()) {
                if (node.type() != PrimitiveType.UNIT) {
                    add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                            "then-only branch must have Unit type");
                }
            } else if (!assignable(node.thenBranch(), node.type())
                    || !assignable(node.elseBranch().orElseThrow(), node.type())) {
                add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, node.span(),
                        "conditional branch conversion is missing");
            }
            node.predicateBinding().ifPresent(id -> validatePredicateBinding(node, id));
        }

        private void validatePredicateBinding(IrNode.Branch node, DeclarationId id) {
            if (requireDeclaration(Optional.of(id), node.span()) == null) {
                return;
            }
            var resolved = semantic.resolvedGraph().declaration(id).orElse(null);
            var typed = semantic.declaration(id).orElse(null);
            BindingContract expected = BindingContract.immutable(narrowed(node.predicate().type()));
            if (resolved == null || typed == null
                    || resolved.kind() != DeclarationKind.PREDICATE_BINDING
                    || typed.kind() != DeclarationKind.PREDICATE_BINDING
                    || typed.contract().isEmpty()
                    || !typed.contract().orElseThrow().equals(expected)) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                        "branch predicate binding is not the narrowed semantic declaration");
                return;
            }
            var scope = semantic.resolvedGraph().scopeTree().scope(resolved.scopeId()).orElse(null);
            if (scope == null || scope.kind() != io.mindspice.lyra.compiler.semantic.ScopeKind.CONDITIONAL_BRANCH
                    || !scope.declarations().contains(id)
                    || !scope.span().equals(node.thenBranch().span())) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                        "predicate binding is not scoped to this truthy branch");
                return;
            }
            boolean escapedReference = semantic.references().stream()
                    .filter(reference -> reference.targetDeclaration().map(id::equals).orElse(false))
                    .anyMatch(reference -> !spanContains(node.thenBranch().span(), reference.span())
                            || !scopeContains(resolved.scopeId(), reference.scopeId()));
            if (escapedReference) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                        "predicate binding reference escapes its truthy branch");
            }
        }

        private boolean scopeContains(
                io.mindspice.lyra.compiler.identity.ScopeId ancestor,
                io.mindspice.lyra.compiler.identity.ScopeId candidate) {
            var current = semantic.resolvedGraph().scopeTree().scope(candidate).orElse(null);
            while (current != null) {
                if (current.id().equals(ancestor)) {
                    return true;
                }
                current = current.parent().flatMap(
                        semantic.resolvedGraph().scopeTree()::scope).orElse(null);
            }
            return false;
        }

        private static boolean spanContains(SourceSpan outer, SourceSpan inner) {
            return outer.sourceId().equals(inner.sourceId())
                    && inner.startOffset() >= outer.startOffset()
                    && inner.endOffset() <= outer.endOffset();
        }

        private void validateMatch(IrNode.Match node) {
            node.subject().ifPresent(value -> validateNode(value, node.span(), false));
            List<IrNode> ordered = new ArrayList<>();
            node.subject().ifPresent(ordered::add);
            for (IrNode.MatchArm arm : node.arms()) {
                if (!spanContains(node.span(), arm.span())) {
                    add(CompilerDiagnosticCodes.IR_EVALUATION_ORDER, arm.span(),
                            "match arm span escapes its match expression");
                }
                ArrayList<IrNode> tests = new ArrayList<>();
                arm.pattern().ifPresent(tests::add);
                arm.guard().ifPresent(tests::add);
                for (IrNode child : tests) {
                    if (!spanContains(arm.span(), child.span())) {
                        add(CompilerDiagnosticCodes.IR_EVALUATION_ORDER, child.span(),
                                "match arm span does not enclose its test child");
                    }
                }
                if (!spanContains(arm.span(), arm.result().span())) {
                    add(CompilerDiagnosticCodes.IR_EVALUATION_ORDER, arm.result().span(),
                            "match arm span does not enclose its result");
                }
                arm.pattern().ifPresent(value -> {
                    validateNode(value, node.span(), false);
                    ordered.add(value);
                });
                arm.guard().ifPresent(value -> {
                    validateNode(value, node.span(), false);
                    ordered.add(value);
                    if (!truthTestable(value.type())) {
                        add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, value.span(),
                                "match guard is not truth-testable");
                    }
                });
                validateNode(arm.result(), node.span(), false);
                ordered.add(arm.result());
                if (!assignable(arm.result(), node.type())) {
                    add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, arm.result().span(),
                            "match result conversion is missing");
                }
                if (node.mode() == IrNode.MatchMode.CONDITIONAL
                        && arm.pattern().isPresent()
                        && !truthTestable(arm.pattern().orElseThrow().type())) {
                    add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, arm.pattern().orElseThrow().span(),
                            "conditional match condition is not truth-testable");
                }
                if (node.mode() == IrNode.MatchMode.TRADITIONAL
                        && arm.pattern().isPresent()) {
                    LyraType comparison = arm.comparisonType().orElse(null);
                    if (comparison == null
                            || !arm.pattern().orElseThrow().type().equals(comparison)
                            || !TypeRules.canImplicitlyConvert(
                            node.subject().orElseThrow().type(), comparison)) {
                        add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, arm.span(),
                                "match equality conversion is missing or inconsistent");
                    }
                }
            }
            validateOrdered(ordered, node.span());
        }

        private static IrEvaluationOrder.EdgeKind matchEdgeKind(
                TypedMatch match, int childIndex) {
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
            throw new IllegalArgumentException("typed match has an unclassified child");
        }

        private void validateCoalesce(IrNode.Coalesce node) {
            validateNode(node.value(), node.span(), false);
            validateNode(node.fallback(), node.span(), false);
            validateOrdered(List.of(node.value(), node.fallback()), node.span());
            if (!node.sourceType().isNilable()
                    || !node.value().type().equals(node.type())
                    || !(node.value() instanceof IrNode.Narrowing narrowing)
                    || !narrowing.sourceType().equals(node.sourceType())
                    || !narrowed(node.sourceType()).equals(node.type())) {
                add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, node.span(),
                        "coalescing does not contain an explicit nil narrowing edge");
            }
            if (!assignable(node.fallback(), node.type())) {
                add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, node.span(),
                        "coalescing fallback is not assignable with a recorded conversion");
            }
        }

        private void validateDirectCall(IrNode.DirectCall node) {
            validateCallIdentity(node.callId(), node.siteId(), node.span());
            node.receiver().ifPresent(value -> validateNode(value, node.span(), false));
            for (IrNode argument : node.arguments()) {
                validateNode(argument, node.span(), false);
            }
            List<IrNode> evaluation = new ArrayList<>();
            node.receiver().ifPresent(evaluation::add);
            evaluation.addAll(node.arguments());
            validateOrdered(evaluation, node.span());
            ReferenceId referenceId = requireReference(node.referenceId(), node.span());
            DeclarationId declarationId = requireDeclaration(node.targetDeclaration(), node.span());
            if (referenceId == null || declarationId == null) {
                return;
            }
            TypedReference reference = semantic.reference(referenceId).orElse(null);
            if (reference == null || reference.kind() != ReferenceKind.DIRECT_CALL_TARGET
                    && reference.kind() != ReferenceKind.NAMESPACE_DIRECT_CALL) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                        "direct call target is not a resolved direct-call reference");
                return;
            }
            if (reference.targetDeclaration().isEmpty()
                    || !reference.targetDeclaration().orElseThrow().equals(declarationId)) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                        "direct call target declaration does not match its reference");
            }
            validateCallSignature(reference.type(), node.arguments(), node.type(), node.span());
            IrReference irReference = ir.metadata().reference(referenceId).orElse(null);
            Optional<CallableStorageRouteProof> expected = irReference == null
                    ? Optional.empty()
                    : expectedNamedCallableStorageProof(
                            irReference.type().orElse(null), declarationId, Optional.of(referenceId),
                            irReference.capture(), irReference.targetModule(),
                            irReference.targetExport(), irReference.moduleId(), irReference.siteId());
            if (!expected.equals(node.storageRouteProof())) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                        expected.isPresent()
                                ? "direct call target is missing its exact authenticated storage-route proof"
                                : "direct call target carries an unproved storage-route proof");
            }
            if (!reference.targetModule().equals(node.targetModule())
                    || !reference.targetExport().equals(node.targetExport())) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                        "direct call module/export links do not match its resolved reference");
            }
            if (node.targetModule().isPresent() != node.targetExport().isPresent()) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                        "direct call module and export links must be present together");
            }

            AccessKind accessKind = node.accessKind().orElse(null);
            // Nominal direct method syntax lowers to a callable call over the
            // exact member getter. DirectCall retains only local/namespace names;
            // accepting MEMBER_CALL here would erase receiver selection.
            if (reference.kind() == ReferenceKind.DIRECT_CALL_TARGET) {
                if (accessKind != null || node.receiver().isPresent()) {
                    add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                            "local direct calls cannot carry an access kind or receiver");
                }
            } else if (accessKind != AccessKind.NAMESPACE_DIRECT_CALL
                    || node.receiver().isPresent()
                    || node.targetModule().isEmpty()
                    || node.targetExport().isEmpty()) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "namespace direct call shape does not match its resolved namespace link");
            }
        }

        private void validateCallableCall(IrNode.CallableCall node) {
            validateCallIdentity(node.callId(), node.siteId(), node.span());
            validateNode(node.target(), node.span(), false);
            for (IrNode argument : node.arguments()) {
                validateNode(argument, node.span(), false);
            }
            List<IrNode> evaluation = new ArrayList<>();
            evaluation.add(node.target());
            evaluation.addAll(node.arguments());
            validateOrdered(evaluation, node.span());
            validateCallSignature(Optional.ofNullable(node.target().type()),
                    node.arguments(), node.type(), node.span());

            // The optimizer is allowed to trust only a producer-issued proof
            // tied to this exact target occurrence. Recompute the proof from
            // resolved semantic metadata here rather than trusting the
            // lowering/backend classifier or any declaration/JVM shape.
            Optional<CallableStorageRouteProof> expected = expectedCallableStorageProof(node.target());
            if (!expected.equals(node.storageRouteProof())) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                        expected.isPresent()
                                ? "callable target is missing its exact authenticated storage-route proof"
                                : "callable target carries an unproved storage-route proof");
            }
        }

        private Optional<CallableStorageRouteProof> expectedCallableStorageProof(IrNode target) {
            if (target.type().isNilable()
                    || !(target.type().withoutQualifiers() instanceof FunctionType function)
                    || target.siteId().isEmpty()) {
                return Optional.empty();
            }
            FlowSiteId site = target.siteId().orElseThrow();
            if (target instanceof IrNode.Access access
                    && access.accessKind() == AccessKind.MEMBER_VALUE
                    && access.declarationId().isPresent()
                    && access.receiver().isPresent()
                    && access.receiver().orElseThrow().siteId().isPresent()
                    && access.receiver().orElseThrow().type().withoutQualifiers()
                    instanceof NominalType receiver) {
                DeclarationId memberId = access.declarationId().orElseThrow();
                ResolvedDeclaration member = semantic.resolvedGraph()
                        .declaration(memberId).orElse(null);
                OptionalInt index = semantic.resolvedGraph().nominals().stream()
                        .filter(nominal -> nominal.schema().type().equals(receiver))
                        .flatMap(nominal -> java.util.stream.IntStream.range(0, nominal.members().size())
                                .filter(candidate -> nominal.members().get(candidate)
                                        .equals(memberId)).boxed())
                        .findFirst().stream().mapToInt(Integer::intValue).findFirst();
                if (member != null && member.kind() == DeclarationKind.MEMBER
                        && member.effectiveContract()
                        .map(value -> value.valueType().equals(target.type())).orElse(false)
                        && index.isPresent()) {
                    return Optional.of(new CallableStorageRouteProof(
                            CallableStorageRouteProof.RouteKind.NOMINAL_MEMBER_GETTER,
                            memberId, Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty(), index,
                            access.receiver().orElseThrow().siteId(), function.signature(), site));
                }
                return Optional.empty();
            }
            if (!(target instanceof IrNode.Reference)
                    && !(target instanceof IrNode.CaptureReference)
                    && !(target instanceof IrNode.Access access
                    && access.accessKind() == AccessKind.NAMESPACE_VALUE)) {
                return Optional.empty();
            }
            DeclarationId declarationId;
            Optional<ReferenceId> referenceId;
            Optional<CaptureId> captureId;
            Optional<ModuleId> targetModule;
            Optional<io.mindspice.lyra.compiler.identity.ExportId> targetExport;
            ModuleId consumerModule;
            if (target instanceof IrNode.Reference reference) {
                declarationId = reference.targetDeclaration().orElse(null);
                referenceId = reference.referenceId();
                captureId = reference.capture();
            } else if (target instanceof IrNode.CaptureReference reference) {
                declarationId = reference.declarationId().orElse(null);
                referenceId = reference.referenceId();
                captureId = reference.captureId();
            } else {
                IrNode.Access access = (IrNode.Access) target;
                declarationId = access.declarationId().orElse(null);
                referenceId = access.referenceId();
                captureId = Optional.empty();
            }
            IrReference resolved = referenceId.flatMap(ir.metadata()::reference).orElse(null);
            if (resolved == null || declarationId == null) return Optional.empty();
            targetModule = resolved.targetModule();
            targetExport = resolved.targetExport();
            consumerModule = resolved.moduleId();
            return expectedNamedCallableStorageProof(target.type(), declarationId, referenceId,
                    captureId, targetModule, targetExport, consumerModule, site);
        }

        private Optional<CallableStorageRouteProof> expectedNamedCallableStorageProof(
                LyraType targetType, DeclarationId declarationId,
                Optional<ReferenceId> referenceId, Optional<CaptureId> captureId,
                Optional<ModuleId> targetModule,
                Optional<io.mindspice.lyra.compiler.identity.ExportId> targetExport,
                ModuleId consumerModule, FlowSiteId site) {
            if (targetType == null || targetType.isNilable()
                    || !(targetType.withoutQualifiers() instanceof FunctionType function)
                    || targetModule.isPresent() != targetExport.isPresent()) {
                return Optional.empty();
            }
            ResolvedDeclaration declaration = semantic.resolvedGraph()
                    .declaration(declarationId).orElse(null);
            if (declaration == null || declaration.effectiveContract().isEmpty()
                    || !declaration.effectiveContract().orElseThrow().valueType()
                    .withoutQualifiers().equals(function)) {
                return Optional.empty();
            }
            if (isIntrinsicDeclaration(declarationId)) {
                return Optional.of(new CallableStorageRouteProof(
                        CallableStorageRouteProof.RouteKind.INTRINSIC, declarationId,
                        Optional.empty(), targetModule, targetExport,
                        Optional.empty(), Optional.empty(), OptionalInt.empty(), Optional.empty(),
                        function.signature(), site));
            }
            if (captureId.isPresent()) {
                ResolvedCapture capture = semantic.resolvedGraph()
                        .capture(captureId.orElseThrow()).orElse(null);
                TypedReference typedReference = referenceId.flatMap(semantic::reference).orElse(null);
                if (capture == null || !capture.declarationId().equals(declarationId)
                        || typedReference == null
                        || typedReference.fromLambda().filter(capture.lambdaId()::equals).isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(new CallableStorageRouteProof(
                        capture.isSharedCell()
                                ? CallableStorageRouteProof.RouteKind.SHARED_CELL
                                : CallableStorageRouteProof.RouteKind.CAPTURE_VALUE,
                        declarationId, captureId, Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), OptionalInt.empty(), Optional.empty(),
                        function.signature(), site));
            }
            var externalAccess = ir.sessionExecution().flatMap(value -> value.access(
                    consumerModule, declaration.originDeclaration().orElse(declarationId)));
            CallableStorageRouteProof.RouteKind route;
            if (declaration.kind() == DeclarationKind.PARAMETER) {
                route = CallableStorageRouteProof.RouteKind.PARAMETER_ENTRY;
            } else if (declaration.externalBinding().isPresent()) {
                boolean certified = semantic.resolvedGraph().sessionFlowCertificate()
                        .map(certificate -> certificate.certifiesExternalCallableStorageRoute(
                                declaration.externalBinding().orElseThrow()))
                        .orElse(false);
                if (!certified) {
                    return Optional.empty();
                }
                route = CallableStorageRouteProof.RouteKind.EXTERNAL_BINDING;
            } else if (externalAccess.isPresent()) {
                route = CallableStorageRouteProof.RouteKind.SESSION_LINK;
            } else if (declaration.imported() || targetModule.isPresent()) {
                if (targetModule.isEmpty()) return Optional.empty();
                route = CallableStorageRouteProof.RouteKind.IMPORTED_STATE;
            } else if (declaration.kind() == DeclarationKind.LET
                    || declaration.kind() == DeclarationKind.SELF) {
                route = CallableStorageRouteProof.RouteKind.LOCAL_BINDING;
            } else {
                return Optional.empty();
            }
            boolean metadataFreeRoute = route == CallableStorageRouteProof.RouteKind.PARAMETER_ENTRY
                    || route == CallableStorageRouteProof.RouteKind.LOCAL_BINDING
                    || route == CallableStorageRouteProof.RouteKind.EXTERNAL_BINDING;
            return Optional.of(new CallableStorageRouteProof(route, declarationId,
                    Optional.empty(), metadataFreeRoute ? Optional.empty() : targetModule,
                    metadataFreeRoute ? Optional.empty() : targetExport,
                    externalAccess.map(value -> value.target().origin().producerId()),
                    externalAccess.map(value -> value.target().origin().generationId()),
                    OptionalInt.empty(), Optional.empty(), function.signature(), site));
        }

        private boolean isIntrinsicDeclaration(DeclarationId start) {
            DeclarationId current = start;
            Set<DeclarationId> visited = new HashSet<>();
            while (current != null && visited.add(current)) {
                ResolvedDeclaration declaration = semantic.resolvedGraph().declaration(current).orElse(null);
                if (declaration == null) return false;
                if (declaration.kind() == DeclarationKind.INTRINSIC_EXPORT) return true;
                current = declaration.originDeclaration().orElse(null);
            }
            return false;
        }

        private void validateCallIdentity(
                Optional<io.mindspice.lyra.compiler.semantic.flow.SummaryCallId> callId,
                Optional<FlowSiteId> site,
                SourceSpan span) {
            List<io.mindspice.lyra.compiler.semantic.flow.SummaryCallId> matches = site.stream()
                    .flatMap(value -> semantic.semanticFlowFacts().callableSummaries()
                            .orderedSummaries().stream()
                            .flatMap(summary -> summary.callReferences().stream())
                            .filter(call -> call.siteId().filter(value::equals).isPresent())
                            .map(io.mindspice.lyra.compiler.semantic.flow.CallableCallReference::callId))
                    .toList();
            if (matches.size() > 1 || matches.size() == 1
                    && !callId.equals(Optional.of(matches.getFirst()))
                    || matches.isEmpty() && callId.isPresent()) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, span,
                        "IR call identity does not match the producer callable summary");
            }
        }

        private void validateCallSignature(
                Optional<LyraType> targetType,
                List<IrNode> arguments,
                LyraType resultType,
                SourceSpan span) {
            if (targetType.isEmpty() || targetType.orElseThrow().isNilable()
                    || !(targetType.orElseThrow().withoutQualifiers() instanceof FunctionType function)) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, span,
                        "call target is not a complete non-nil function type");
                return;
            }
            if (arguments.size() != function.arity()) {
                add(CompilerDiagnosticCodes.IR_INVALID_ARITY, span,
                        "IR call arity does not match its function signature");
                return;
            }
            for (int index = 0; index < arguments.size(); index++) {
                if (!assignable(arguments.get(index), function.parameterType(index))) {
                    add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, arguments.get(index).span(),
                            "IR call argument is not assignable with a recorded conversion");
                }
            }
            if (!resultType.equals(function.returnType())) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, span,
                        "IR call result type does not match its function signature");
            }
        }

        private void validateLambda(IrNode.Lambda node) {
            validateNode(node.body(), node.span(), false);
            if (node.lambdaId().isEmpty() || node.signature().isEmpty()) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                        "lambda is missing its resolved identity or signature");
                return;
            }
            var typedLambda = semantic.lambda(node.lambdaId().orElseThrow()).orElse(null);
            if (typedLambda == null) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                        "lambda identity is absent from the typed graph");
            } else if (!typedLambda.signature().equals(node.signature().orElseThrow())
                    || !typedLambda.captures().equals(node.captures())
                    || !node.scopeId().equals(Optional.of(typedLambda.scopeId()))
                    || !node.ownerDeclaration().equals(semantic.resolvedGraph().lambda(
                    node.lambdaId().orElseThrow()).orElseThrow().ownerDeclaration())) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "lambda signature, scope, owner, or capture list does not match the typed graph");
            }
            if (!node.type().equals(node.signature().orElseThrow().asFunctionType())) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "lambda node type and signature disagree");
            }
            if (typedLambda != null && node.signature().orElseThrow().arity()
                    != typedLambda.parameterIds().size()) {
                add(CompilerDiagnosticCodes.IR_INVALID_ARITY, node.span(),
                        "lambda parameter count does not match its signature");
            }
            if (!assignable(node.body(), node.signature().orElseThrow().returnType())) {
                add(CompilerDiagnosticCodes.IR_UNRECORDED_CONVERSION, node.span(),
                        "lambda body is not assignable with a recorded conversion");
            }
            for (var capture : node.captures()) {
                if (semantic.resolvedGraph().capture(capture).isEmpty()) {
                    add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                            "lambda capture identity is absent from the typed graph");
                }
            }
        }

        private void validateAccess(IrNode.Access node) {
            node.receiver().ifPresent(value -> validateNode(value, node.span(), false));
            TypedReference reference = null;
            if (node.referenceId().isPresent()) {
                ReferenceId id = requireReference(node.referenceId(), node.span());
                reference = id == null ? null : semantic.reference(id).orElse(null);
                if (reference == null
                        || reference.type().isEmpty()
                        || !reference.type().orElseThrow().equals(node.type())
                        || !reference.targetModule().equals(node.moduleId())
                        || !reference.targetDeclaration().equals(node.declarationId())
                        || !reference.targetExport().equals(node.exportId())) {
                    add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                            "access link does not match its resolved reference");
                }
            }
            node.declarationId().ifPresent(id -> requireDeclaration(Optional.of(id), node.span()));
            node.moduleId().ifPresent(module -> {
                if (!modules.contains(module)) {
                    add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                            "access module identity is unresolved");
                }
            });

            switch (node.accessKind()) {
                case MEMBER_VALUE -> {
                    boolean oneSelector = node.memberName().isPresent() ^ node.tupleIndex().isPresent();
                    if (node.receiver().isEmpty() || !oneSelector
                            || node.referenceId().isPresent()
                            || node.moduleId().isPresent()
                            || node.exportId().isPresent()) {
                        add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                                "member value access has an invalid receiver, selector, or resolved link");
                    } else {
                        validateMemberValueContract(node, node.receiver().orElseThrow());
                    }
                }
                case NAMESPACE_VALUE -> {
                    if (node.receiver().isPresent()
                            || node.referenceId().isEmpty()
                            || node.declarationId().isEmpty()
                            || node.moduleId().isEmpty()
                            || node.memberName().isEmpty()
                            || node.tupleIndex().isPresent()
                            || reference == null
                            || reference.kind() != ReferenceKind.NAMESPACE_MEMBER
                            || !reference.name().equals(node.memberName().orElse(null))) {
                        add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                                "namespace value access shape does not match its resolved namespace link");
                    }
                }
                case MEMBER_CALL, NAMESPACE_DIRECT_CALL -> add(
                        CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "call access kinds cannot be represented by a value-access node");
            }
        }

        private void validateMemberValueContract(IrNode.Access node, IrNode receiver) {
            if (receiver.type().isNilable()) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "member value receiver must be non-nil");
                return;
            }
            LyraType base = receiver.type().withoutQualifiers();
            if (base instanceof io.mindspice.lyra.compiler.types.NominalType nominalType) {
                var nominal = semantic.resolvedGraph().nominals().stream()
                        .filter(value -> value.schema().type().equals(nominalType)).findFirst().orElse(null);
                int index = nominal == null ? -1 : node.declarationId().map(nominal.members()::indexOf).orElse(-1);
                if (index < 0 || node.tupleIndex().isPresent()
                        || !node.memberName().equals(Optional.of(nominal.schema().members().get(index).name()))
                        || !node.type().equals(nominal.schema().members().get(index).type())) {
                    add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(), "nominal member differs from its exact schema slot");
                }
                TypedExpression source = node.siteId().map(expressionsBySite::get).orElse(null);
                if (source == null || source.kind() != TypedExpressionKind.MEMBER_ACCESS
                        || !source.declarationId().equals(node.declarationId())) {
                    add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(), "nominal access lacks its authorized source member");
                } else if (source.children().size() != 1
                        || !receiver.siteId().equals(Optional.of(
                        semantic.flowSiteId(source.children().getFirst())))) {
                    add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                            "nominal access receiver differs from its authorized source occurrence");
                }
                return;
            }
            if (node.declarationId().isPresent()) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(), "structural access carries a nominal field identity");
            }
            if (node.memberName().filter("length"::equals).isPresent()
                    && (base == PrimitiveType.STRING || base instanceof ArrayType)) {
                if (node.tupleIndex().isPresent() || node.type() != PrimitiveType.I32) {
                    add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                            "String/Array length must return I32");
                }
                return;
            }
            if (node.tupleIndex().isPresent() && base instanceof TupleType tuple) {
                var index = node.tupleIndex().orElseThrow();
                if (node.memberName().isPresent()
                        || index.signum() < 0
                        || index.bitLength() > 31
                        || index.intValue() >= tuple.arity()
                        || !node.type().equals(tuple.memberType(index.intValue()))) {
                    add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                            "tuple member index/result type does not match its exact shape");
                }
                return;
            }
            add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                    "member selector is not legal for the statically known receiver type");
        }

        private boolean assignable(IrNode value, LyraType target) {
            if (value.type().equals(target)) {
                return true;
            }
            if (!(value instanceof IrNode.Conversion conversion)
                    || !conversion.type().equals(target)) {
                return false;
            }
            return TypeRules.canImplicitlyConvert(conversion.sourceType(), target)
                    || TypeRules.canExplicitlyConvert(conversion.sourceType(), target);
        }

        private void validateRuntimeCheck(IrNode.RuntimeCheck node) {
            validateNode(node.operand(), node.span(), false);
            if (!node.type().equals(node.operand().type())) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "runtime check changes type without an explicit conversion node");
            }
            if (node.siteId().isEmpty()) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                        "runtime check has no source-site identity");
            }
            if (node.failureSiteId().isEmpty()) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                        "runtime check has no exact failure-site identity");
            } else {
                IrFailureSite failure = ir.failureSites().stream()
                        .filter(value -> value.siteId().equals(node.failureSiteId().orElseThrow()))
                        .findFirst().orElse(null);
                if (failure == null || !failure.span().equals(node.span())
                        || !failure.type().equals(node.type())
                        || failure.checkKind() != node.checkKind()
                        || !failure.failureCode().equals(node.failureCode())) {
                    add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                            "runtime check does not match its exact failure-site record");
                }
            }
            if (node.siteId().isPresent()
                    && node.failureSiteId().isPresent()
                    && node.failureSiteId().filter(node.siteId().orElseThrow()::equals).isEmpty()) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, node.span(),
                        "source-owned runtime checks must use their failure site as their source site");
            }
            boolean validSite = switch (node.checkKind()) {
                case ARITHMETIC -> node.operand() instanceof IrNode.Range || node.operand() instanceof IrNode.Operator operator
                        && isCheckedArithmeticOperator(operator.operator())
                        && operator.type().isNumeric() && !operator.type().isNilable();
                case DIVISION -> node.operand() instanceof IrNode.Operator operator
                        && operator.operator() == TokenKind.SLASH
                        && operator.type().isNumeric() && !operator.type().isNilable();
                case EXPLICIT_CONVERSION -> node.operand() instanceof IrNode.Conversion conversion
                        && conversion.kind() == ConversionKind.EXPLICIT
                        && conversion.step() == ConversionStep.NUMERIC_EXPLICIT;
                case BOUNDS -> node.operand() instanceof IrNode.IndexAccess;
            };
            String expectedFailure = switch (node.checkKind()) {
                case ARITHMETIC, DIVISION -> "LYR-ARITH";
                case EXPLICIT_CONVERSION -> "LYR-CONVERT";
                case BOUNDS -> "LYR-BOUNDS";
            };
            if (!validSite || !node.failureCode().equals(expectedFailure)) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "runtime check has an incompatible operation or failure category");
            }
        }

        private boolean validSignedMinimumOperand(
                IrNode.Constant node, IrConstantValue.IntegerValue value) {
            if (!signedMinimumOperands.contains(node)
                    || value.exactValue().integerValue().signum() <= 0
                    || !(node.type().withoutQualifiers() instanceof PrimitiveType primitive)
                    || !primitive.isInteger()
                    || LiteralTyping.representableAs(value.exactValue(), primitive)) {
                return false;
            }
            return LiteralTyping.representableAs(value.exactValue().negated(), primitive);
        }

        private static boolean isCheckedArithmeticOperator(TokenKind operator) {
            return switch (operator) {
                case PLUS, MINUS, ASTERISK, PERCENT, CARET, INCREMENT, DECREMENT -> true;
                default -> false;
            };
        }

        private void validateOperatorShape(
                TokenKind operator, int arity, SourceSpan span, boolean shortCircuit) {
            int minimum = switch (operator) {
                case PLUS, ASTERISK, AND, OR, XOR, EQUAL_EQUAL, NOT_EQUAL,
                        IDENTITY_EQUAL, IDENTITY_NOT_EQUAL, LESS, LESS_EQUAL,
                        GREATER, GREATER_EQUAL -> 2;
                case MINUS, SLASH -> 1;
                case NOT, INCREMENT, DECREMENT -> 1;
                case PERCENT, CARET -> 2;
                default -> -1;
            };
            int maximum = switch (operator) {
                case PERCENT, CARET, NOT, INCREMENT, DECREMENT -> minimum;
                default -> Integer.MAX_VALUE;
            };
            if (minimum < 0 || arity < minimum || arity > maximum) {
                add(CompilerDiagnosticCodes.IR_INVALID_ARITY, span,
                        "IR operator arity is not legal for " + operator);
            }
            if (shortCircuit && operator != TokenKind.AND && operator != TokenKind.OR) {
                add(CompilerDiagnosticCodes.IR_EVALUATION_ORDER, span,
                        "IR short-circuit node uses a non-short-circuit operator");
            }
        }

        private void validateOrdered(List<IrNode> nodes, SourceSpan parent) {
            int previousStart = -1;
            for (IrNode node : nodes) {
                if (node == null) {
                    add(CompilerDiagnosticCodes.IR_MISSING_SPAN, parent, "IR evaluation list contains a null node");
                    continue;
                }
                if (!parent.sourceId().equals(node.span().sourceId())) {
                    add(CompilerDiagnosticCodes.IR_EVALUATION_ORDER, node.span(),
                            "evaluation order crosses source modules");
                }
                if (node.span().startOffset() < previousStart) {
                    add(CompilerDiagnosticCodes.IR_EVALUATION_ORDER, node.span(),
                            "IR child evaluation order is not left-to-right");
                }
                previousStart = node.span().startOffset();
            }
        }

        private ReferenceId requireReference(Optional<ReferenceId> id, SourceSpan span) {
            if (id.isEmpty() || !references.contains(id.orElseThrow())) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, span,
                        "IR node is missing a resolved reference identity");
                return null;
            }
            return id.orElseThrow();
        }

        private DeclarationId requireDeclaration(Optional<DeclarationId> id, SourceSpan span) {
            if (id.isEmpty() || !declarations.contains(id.orElseThrow())) {
                add(CompilerDiagnosticCodes.IR_UNRESOLVED_LINK, span,
                        "IR node is missing a resolved declaration identity");
                return null;
            }
            return id.orElseThrow();
        }

        private void requireBase(IrNode node, PrimitiveType expected) {
            if (!node.type().withoutQualifiers().equals(expected)) {
                add(CompilerDiagnosticCodes.IR_INVALID_GRAPH, node.span(),
                        "IR constant type does not match its constant value");
            }
        }

        private SourceSpan rootSpan() {
            return semantic.modules().isEmpty()
                    ? semantic.resolvedGraph().moduleGraph().module(
                    semantic.resolvedGraph().moduleGraph().rootModule()).orElseThrow().program().span()
                    : semantic.modules().getFirst().span();
        }

        private void add(io.mindspice.lyra.compiler.diagnostic.DiagnosticCode code,
                         SourceSpan span,
                         String summary) {
            diagnostics.add(Diagnostic.error(code, span, summary));
        }

        private static LyraType narrowed(LyraType type) {
            return type.withoutQualifiers();
        }

        private boolean identityBearing(LyraType type) {
            if (type instanceof FunctionType || type instanceof ArrayType) return true;
            if (!(type instanceof io.mindspice.lyra.compiler.types.NominalType nominal)) return false;
            return semantic.resolvedGraph().nominals().stream()
                    .filter(value -> value.schema().type().equals(nominal))
                    .map(value -> value.schema().kind())
                    .anyMatch(io.mindspice.lyra.compiler.types.NominalSchema.Kind.CLASS::equals);
        }

        private static boolean truthTestable(LyraType type) {
            LyraType base = type.withoutQualifiers();
            return type.isNilable() || base instanceof PrimitiveType
                    || base instanceof io.mindspice.lyra.compiler.types.ArrayType
                    || base instanceof io.mindspice.lyra.compiler.types.TupleType
                    || base instanceof FunctionType || base instanceof NominalType;
        }
    }
}
