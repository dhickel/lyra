package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import java.util.Objects;

/** One complete module body plus its resolved static state layout. */
public record IrModule(
        ModuleId moduleId,
        ScopeId rootScope,
        SourceSpan span,
        IrNode.Sequence body,
        IrModuleState state,
        java.util.Optional<IrSubmissionResult> submissionResult)
        implements ImmutablePhaseArtifact {
    public IrModule {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(rootScope, "rootScope");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(submissionResult, "submissionResult");
        if (!moduleId.sourceId().equals(span.sourceId())
                || !moduleId.sourceId().equals(body.span().sourceId())) {
            throw new IllegalArgumentException("IR module spans belong to another module");
        }
        if (!state.moduleId().equals(moduleId) || !state.rootScope().equals(rootScope)) {
            throw new IllegalArgumentException("IR module state belongs to another module");
        }
    }

    public IrModule(ModuleId moduleId, ScopeId rootScope, SourceSpan span,
                    IrNode.Sequence body, IrModuleState state) {
        this(moduleId, rootScope, span, body, state, java.util.Optional.empty());
    }

    /** Compatibility constructor for negative-validator fixtures. */
    public IrModule(
            ModuleId moduleId,
            ScopeId rootScope,
            SourceSpan span,
            IrNode.Sequence body) {
        this(moduleId, rootScope, span, body, IrModuleState.empty(moduleId, rootScope));
    }

    public IrNode.Sequence topLevel() {
        return body;
    }

    public IrModuleState moduleState() {
        return state;
    }

    public java.util.List<io.mindspice.lyra.compiler.identity.DeclarationId> declarationIds() {
        return state.declarations();
    }

    public java.util.List<io.mindspice.lyra.compiler.identity.ExportId> exportIds() {
        return state.exports();
    }

    public java.util.List<io.mindspice.lyra.compiler.identity.DeclarationId> functionSlots() {
        return state.functionSlots();
    }

    public java.util.List<io.mindspice.lyra.compiler.identity.DeclarationId> eagerDeclarations() {
        return state.eagerDeclarations();
    }
}
