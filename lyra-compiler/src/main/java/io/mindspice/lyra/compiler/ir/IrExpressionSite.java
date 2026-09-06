package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.semantic.TypedExpressionKind;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.LyraType;

import java.util.Objects;

/** One-to-one source-expression-to-IR correspondence entry. */
public record IrExpressionSite(
        FlowSiteId siteId,
        ModuleId moduleId,
        SourceSpan span,
        LyraType type,
        TypedExpressionKind kind,
        IrNode node)
        implements ImmutablePhaseArtifact, Comparable<IrExpressionSite> {
    public IrExpressionSite {
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(node, "node");
        if (!moduleId.sourceId().equals(span.sourceId())
                || !span.equals(node.span())
                || !type.equals(node.type())) {
            throw new IllegalArgumentException("IR expression-site metadata does not match its node");
        }
        if (node.siteId().filter(siteId::equals).isEmpty()) {
            throw new IllegalArgumentException("IR expression-site node does not carry its site identity");
        }
    }

    @Override
    public int compareTo(IrExpressionSite other) {
        return siteId.compareTo(Objects.requireNonNull(other, "other").siteId);
    }
}
