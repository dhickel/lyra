package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * One compact source event emitted by canonical semantic flow analysis.
 *
 * <p>Events carry the value or transfer data relevant at a semantic boundary,
 * not a complete before/after snapshot for every expression.  They are an
 * internal compiler representation and have no runtime or serialization
 * meaning.</p>
 */
public record SemanticFlowEvent(
        Kind kind,
        ModuleId moduleId,
        SourceSpan span,
        Optional<DeclarationId> initializerDeclaration,
        Optional<DeclarationId> declarationId,
        Optional<DeclarationId> targetDeclaration,
        Optional<LambdaId> lambdaId,
        Optional<ReferenceId> referenceId,
        Optional<SummaryCallId> callId,
        Optional<ProjectionPath> route,
        ValueAlternatives value,
        List<CapturedCellWrite> writes,
        List<EagerEffectWitness> effects,
        Optional<FlowSiteId> siteId,
        Optional<CaptureId> captureId)
        implements Comparable<SemanticFlowEvent> {
    public enum Kind {
        DECLARATION,
        CALL,
        MUTATION,
        CAPTURE,
        EFFECT
    }

    public SemanticFlowEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(initializerDeclaration, "initializerDeclaration");
        Objects.requireNonNull(declarationId, "declarationId");
        Objects.requireNonNull(targetDeclaration, "targetDeclaration");
        Objects.requireNonNull(lambdaId, "lambdaId");
        Objects.requireNonNull(referenceId, "referenceId");
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(value, "value");
        writes = orderedWrites(writes);
        effects = orderedEffects(effects);
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(captureId, "captureId");
        if (!moduleId.sourceId().equals(span.sourceId())) {
            throw new IllegalArgumentException("flow event span belongs to another module");
        }
        route.ifPresent(Objects::requireNonNull);
    }

    /** Compatibility constructor for facts created before exact site links. */
    public SemanticFlowEvent(
            Kind kind,
            ModuleId moduleId,
            SourceSpan span,
            Optional<DeclarationId> initializerDeclaration,
            Optional<DeclarationId> declarationId,
            Optional<DeclarationId> targetDeclaration,
            Optional<LambdaId> lambdaId,
            Optional<ReferenceId> referenceId,
            Optional<SummaryCallId> callId,
            Optional<ProjectionPath> route,
            ValueAlternatives value,
            List<CapturedCellWrite> writes,
            List<EagerEffectWitness> effects) {
        this(kind, moduleId, span, initializerDeclaration, declarationId,
                targetDeclaration, lambdaId, referenceId, callId, route,
                value, writes, effects, Optional.empty(), Optional.empty());
    }

    /** Compatibility constructor for events that do not carry a value. */
    public SemanticFlowEvent(
            Kind kind,
            ModuleId moduleId,
            SourceSpan span,
            Optional<DeclarationId> initializerDeclaration,
            Optional<DeclarationId> declarationId,
            Optional<DeclarationId> targetDeclaration,
            Optional<LambdaId> lambdaId,
            Optional<ReferenceId> referenceId,
            Optional<SummaryCallId> callId,
            Optional<ProjectionPath> route,
            List<CapturedCellWrite> writes,
            List<EagerEffectWitness> effects) {
        this(kind, moduleId, span, initializerDeclaration, declarationId,
                targetDeclaration, lambdaId, referenceId, callId, route,
                ValueAlternatives.empty(), writes, effects, Optional.empty(), Optional.empty());
    }

    public Optional<DeclarationId> initializer() {
        return initializerDeclaration;
    }

    public SemanticFlowEvent withInitializer(Optional<DeclarationId> initializer) {
        return new SemanticFlowEvent(
                kind, moduleId, span, Objects.requireNonNull(initializer, "initializer"),
                declarationId, targetDeclaration, lambdaId, referenceId, callId, route,
                value, writes, effects, siteId, captureId);
    }

    public Optional<DeclarationId> declaration() {
        return declarationId;
    }

    public Optional<DeclarationId> target() {
        return targetDeclaration;
    }

    public Optional<SummaryCallId> call() {
        return callId;
    }

    public boolean isDeclaration() {
        return kind == Kind.DECLARATION;
    }

    public boolean isCall() {
        return kind == Kind.CALL;
    }

    public boolean isMutation() {
        return kind == Kind.MUTATION;
    }

    public boolean isCapture() {
        return kind == Kind.CAPTURE;
    }

    public boolean isEffect() {
        return kind == Kind.EFFECT;
    }

    public String canonicalKey() {
        StringBuilder result = new StringBuilder()
                .append(moduleId).append('/').append(span).append('/').append(kind);
        initializerDeclaration.ifPresent(value -> result.append("/initializer=").append(value));
        declarationId.ifPresent(value -> result.append("/declaration=").append(value));
        targetDeclaration.ifPresent(value -> result.append("/target=").append(value));
        lambdaId.ifPresent(value -> result.append("/lambda=").append(value));
        referenceId.ifPresent(value -> result.append("/reference=").append(value));
        callId.ifPresent(value -> result.append("/call=").append(value));
        route.ifPresent(value -> result.append("/route=").append(value));
        siteId.ifPresent(value -> result.append("/site=").append(value));
        captureId.ifPresent(value -> result.append("/capture-id=").append(value));
        result.append("/value=").append(value);
        for (CapturedCellWrite write : writes) {
            result.append("/write=").append(write);
        }
        for (EagerEffectWitness effect : effects) {
            result.append("/effect=").append(effect);
        }
        return result.toString();
    }

    @Override
    public int compareTo(SemanticFlowEvent other) {
        return canonicalKey().compareTo(
                Objects.requireNonNull(other, "other").canonicalKey());
    }

    @Override
    public String toString() {
        return canonicalKey();
    }

    private static <T> List<T> copy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<T> result = new ArrayList<>(values.size());
        for (T value : values) {
            result.add(Objects.requireNonNull(value, name + " must not contain null"));
        }
        return List.copyOf(result);
    }

    private static List<CapturedCellWrite> orderedWrites(List<CapturedCellWrite> values) {
        ArrayList<CapturedCellWrite> result = new ArrayList<>(copy(values, "writes"));
        result.sort(CapturedCellWrite::compareTo);
        if (new LinkedHashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException("event writes must not contain duplicates");
        }
        return List.copyOf(result);
    }

    private static List<EagerEffectWitness> orderedEffects(List<EagerEffectWitness> values) {
        List<EagerEffectWitness> copied = copy(values, "effects");
        TreeSet<EagerEffectWitness> ordered = new TreeSet<>(copied);
        if (ordered.size() != copied.size()) {
            throw new IllegalArgumentException("event effects must not contain duplicates");
        }
        return List.copyOf(ordered);
    }

    public static Comparator<SemanticFlowEvent> comparator() {
        return Comparator.naturalOrder();
    }
}
