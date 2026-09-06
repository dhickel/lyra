package io.mindspice.lyra.compiler.semantic;

import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.types.LyraType;
import io.mindspice.lyra.compiler.types.PrimitiveType;

import java.util.Objects;
import java.util.Optional;

/** The final source form's value contract, separate from Unit module initialization. */
public record TypedSubmissionResult(ModuleId moduleId, Optional<FlowSiteId> site, LyraType type) {
    public TypedSubmissionResult {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(type, "type");
        if (type.isMutable()) {
            throw new IllegalArgumentException("submission results do not grant binding authority");
        }
    }

    public static TypedSubmissionResult from(TypedSemanticGraph graph) {
        Objects.requireNonNull(graph, "graph");
        ModuleId root = graph.resolvedGraph().moduleGraph().rootModule();
        var forms = graph.module(root).orElseThrow().forms();
        if (forms.isEmpty()) {
            return new TypedSubmissionResult(root, Optional.empty(), PrimitiveType.UNIT);
        }
        TypedExpression last = forms.getLast();
        LyraType value = last.type().withoutQualifiers();
        if (last.type().isNilable()) value = value.nilable();
        return new TypedSubmissionResult(root, Optional.of(graph.flowSiteId(last)), value);
    }
}
