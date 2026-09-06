package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.identity.IdentityAllocator;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.BindingContract;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Internal read-only view consumed by the phase-11 flow engines.
 *
 * <p>This is deliberately a compiler-internal seam.  It is not serialized,
 * JVM-specific, or a stable product API.  The package-owned pre-seal core and
 * the published typed graph both implement it so canonical flow analysis can
 * run before a graph is sealed without manufacturing a bootstrap graph.</p>
 */
public sealed interface TypedSemanticInput
        permits TypedSemanticCore, TypedSemanticGraph {
    ResolvedSemanticGraph resolvedGraph();

    List<TypedModule> modules();

    Optional<TypedModule> module(ModuleId moduleId);

    List<TypedDeclaration> declarations();

    Optional<TypedDeclaration> declaration(DeclarationId declarationId);

    List<TypedReference> references();

    Optional<TypedReference> reference(ReferenceId referenceId);

    List<TypedLambda> lambdas();

    Optional<TypedLambda> lambda(LambdaId lambdaId);

    List<TypedExpression> expressions();

    /** Exact deterministic provenance identity for a typed expression object. */
    FlowSiteId flowSiteId(TypedExpression expression);

    /** Canonical lexical owner scope of a typed expression site. */
    ScopeId flowScopeId(TypedExpression expression);

    FlowSiteId flowSiteId(ReferenceId referenceId);

    FlowSiteId flowSiteId(CaptureId captureId);

    Map<DeclarationId, BindingContract> contractsByDeclaration();

    Optional<BindingContract> contract(DeclarationId declarationId);

    /** Persistent identity point after this input's canonical flow sites. */
    IdentityAllocator allocator();

    Map<SourceSpan, List<TypedExpression>> expressionsBySpan();

    List<TypedMutation> mutations();

    List<TypedFailureSite> failureSites();
}
