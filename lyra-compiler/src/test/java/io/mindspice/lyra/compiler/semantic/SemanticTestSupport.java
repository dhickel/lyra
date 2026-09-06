package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.semantic.flow.SemanticFlowFacts;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.BindingContract;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Test-only bridge for exercising the package-owned sealing boundary. */
public final class SemanticTestSupport {
    private SemanticTestSupport() {
    }

    public static TypedSemanticGraph seal(
            TypedSemanticGraph canonical,
            SemanticFlowFacts facts,
            InitializationPlan initializationPlan) {
        Objects.requireNonNull(canonical, "canonical");
        return TypedSemanticGraph.seal(
                canonical.sealingCore(),
                Objects.requireNonNull(facts, "facts"),
                Objects.requireNonNull(initializationPlan, "initializationPlan"));
    }

    public static TypedSemanticGraph seal(
            TypedSemanticGraph canonical,
            ResolvedSemanticGraph resolvedGraph,
            List<TypedModule> modules,
            List<TypedDeclaration> declarations,
            List<TypedReference> references,
            List<TypedLambda> lambdas,
            List<TypedConversion> conversions,
            List<TypedExpression> expressions,
            Map<DeclarationId, BindingContract> contractsByDeclaration,
            Map<SourceSpan, List<TypedExpression>> expressionsBySpan,
            List<TypedMutation> mutations,
            SemanticFlowFacts facts,
            InitializationPlan initializationPlan,
            List<TypedFailureSite> failureSites) {
        Objects.requireNonNull(canonical, "canonical");
        TypedSemanticCore core = new TypedSemanticCore(
                resolvedGraph, modules, declarations, references, lambdas, conversions,
                expressions, contractsByDeclaration, expressionsBySpan, mutations,
                failureSites, canonical.sealingCore().flowProvenance());
        return TypedSemanticGraph.seal(core, facts, initializationPlan);
    }

    public static TypedSemanticGraph seal(
            ResolvedSemanticGraph resolvedGraph,
            List<TypedModule> modules,
            List<TypedDeclaration> declarations,
            List<TypedReference> references,
            List<TypedLambda> lambdas,
            List<TypedConversion> conversions,
            List<TypedExpression> expressions,
            Map<DeclarationId, BindingContract> contractsByDeclaration,
            Map<SourceSpan, List<TypedExpression>> expressionsBySpan,
            List<TypedMutation> mutations,
            SemanticFlowFacts facts,
            InitializationPlan initializationPlan,
            List<TypedFailureSite> failureSites) {
        TypedSemanticCore core = new TypedSemanticCore(
                resolvedGraph, modules, declarations, references, lambdas, conversions,
                expressions, contractsByDeclaration, expressionsBySpan, mutations, failureSites);
        return TypedSemanticGraph.seal(core, facts, initializationPlan);
    }
}
