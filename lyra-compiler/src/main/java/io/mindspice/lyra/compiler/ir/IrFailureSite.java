package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.semantic.TypedExpressionKind;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.LyraType;

import java.util.Objects;

/** Exact runtime-check identity and category retained by the closed IR. */
public record IrFailureSite(
        FlowSiteId siteId,
        ModuleId moduleId,
        SourceSpan span,
        LyraType type,
        IrCheckKind checkKind,
        String failureCode,
        TypedExpressionKind expressionKind)
        implements ImmutablePhaseArtifact, Comparable<IrFailureSite> {
    public IrFailureSite {
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(checkKind, "checkKind");
        if (Objects.requireNonNull(failureCode, "failureCode").isBlank()) {
            throw new IllegalArgumentException("failure code must not be blank");
        }
        Objects.requireNonNull(expressionKind, "expressionKind");
        if (!moduleId.sourceId().equals(span.sourceId())) {
            throw new IllegalArgumentException("failure site belongs to another module");
        }
    }

    public FlowSiteId failureSiteId() {
        return siteId;
    }

    public String code() {
        return failureCode;
    }

    public String category() {
        return failureCode;
    }

    @Override
    public int compareTo(IrFailureSite other) {
        return siteId.compareTo(Objects.requireNonNull(other, "other").siteId);
    }
}
