package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Source evidence for an eager value read or callable effect in a summary.
 *
 * <p>A witness keeps both source spans and the finite callable path used to
 * reach the effect.  A repeated call identity closes the path as recursive
 * instead of growing an unbounded recursive witness.</p>
 */
public record EagerEffectWitness(
        ModuleId fromModule,
        ModuleId targetModule,
        Kind kind,
        SourceSpan effectSpan,
        Optional<DeclarationId> targetDeclaration,
        Optional<ReferenceId> referenceId,
        Optional<LambdaId> targetLambda,
        List<SourceSpan> sourcePath,
        List<SummaryCallId> callPath,
        boolean recursive,
        Optional<FlowSiteId> effectSite,
        List<FlowSiteId> sourceSitePath)
        implements Comparable<EagerEffectWitness> {
    public enum Kind {
        VALUE_READ,
        DIRECT_CALL,
        NAMESPACE_CALL,
        CALLABLE_CALL,
        PARAMETER_CALL,
        CAPTURE_CALL
    }

    public EagerEffectWitness {
        Objects.requireNonNull(fromModule, "fromModule");
        Objects.requireNonNull(targetModule, "targetModule");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(effectSpan, "effectSpan");
        Objects.requireNonNull(targetDeclaration, "targetDeclaration");
        Objects.requireNonNull(referenceId, "referenceId");
        Objects.requireNonNull(targetLambda, "targetLambda");
        sourcePath = copy(sourcePath, "sourcePath");
        callPath = copy(callPath, "callPath");
        Objects.requireNonNull(effectSite, "effectSite");
        sourceSitePath = copy(sourceSitePath, "sourceSitePath");
        if (sourcePath.isEmpty()) {
            sourcePath = List.of(effectSpan);
        }
        if (!sourcePath.contains(effectSpan)) {
            throw new IllegalArgumentException("effect witness path must include its effect span");
        }
        if (effectSite.isPresent() != !sourceSitePath.isEmpty()) {
            throw new IllegalArgumentException(
                    "effect-site provenance requires both a site and source-site path");
        }
        if (!sourceSitePath.isEmpty() && sourceSitePath.size() != sourcePath.size()) {
            throw new IllegalArgumentException(
                    "effect source-site and source-span paths must have equal lengths");
        }
        if (!sourceSitePath.isEmpty()
                && !sourceSitePath.getLast().equals(effectSite.orElseThrow())) {
            throw new IllegalArgumentException(
                    "effect source-site path must end at its effect site");
        }
    }

    /** Compatibility constructor for witnesses without exact flow-site links. */
    public EagerEffectWitness(
            ModuleId fromModule,
            ModuleId targetModule,
            Kind kind,
            SourceSpan effectSpan,
            Optional<DeclarationId> targetDeclaration,
            Optional<ReferenceId> referenceId,
            Optional<LambdaId> targetLambda,
            List<SourceSpan> sourcePath,
            List<SummaryCallId> callPath,
            boolean recursive) {
        this(fromModule, targetModule, kind, effectSpan, targetDeclaration,
                referenceId, targetLambda, sourcePath, callPath, recursive,
                Optional.empty(), List.of());
    }

    public EagerEffectWitness(
            ModuleId fromModule,
            ModuleId targetModule,
            Kind kind,
            SourceSpan effectSpan,
            Optional<DeclarationId> targetDeclaration,
            Optional<ReferenceId> referenceId) {
        this(fromModule, targetModule, kind, effectSpan, targetDeclaration,
                referenceId, Optional.empty(), List.of(effectSpan), List.of(), false,
                Optional.empty(), List.of());
    }

    public ModuleId sourceModule() {
        return fromModule;
    }

    public ModuleId dependencyModule() {
        return targetModule;
    }

    public SourceSpan span() {
        return effectSpan;
    }

    public List<SourceSpan> spans() {
        return sourcePath;
    }

    public boolean isValueRead() {
        return kind == Kind.VALUE_READ;
    }

    public boolean isCall() {
        return kind != Kind.VALUE_READ;
    }

    /**
     * Prefixes a callee witness with a caller call site.  Recursive paths are
     * represented once with a recursive marker; non-recursive paths are
     * bounded explicitly and fail rather than truncating evidence.
     */
    public EagerEffectWitness through(
            CallableCallReference call,
            SummaryLimits limits) {
        return through(fromModule, call, limits);
    }

    /** Prefixes a witness and attributes the resulting effect to the caller module. */
    public EagerEffectWitness through(
            ModuleId callerModule,
            CallableCallReference call,
            SummaryLimits limits) {
        Objects.requireNonNull(callerModule, "callerModule");
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(limits, "limits");
        if (callPath.contains(call.id())) {
            return new EagerEffectWitness(callerModule, targetModule, kind, effectSpan,
                    targetDeclaration, referenceId, targetLambda, sourcePath, callPath, true,
                    effectSite, sourceSitePath);
        }
        limits.requireWitnessPathDepth(sourcePath.size() + 1);
        ArrayList<SourceSpan> spans = new ArrayList<>(sourcePath.size() + 1);
        spans.add(call.span());
        spans.addAll(sourcePath);
        ArrayList<SummaryCallId> calls = new ArrayList<>(callPath.size() + 1);
        calls.add(call.id());
        calls.addAll(callPath);
        ArrayList<FlowSiteId> sites = new ArrayList<>();
        if (!sourceSitePath.isEmpty()) {
            FlowSiteId callSite = call.siteId().orElseThrow(() ->
                    new IllegalArgumentException(
                            "canonical effect propagation call has no flow-site identity"));
            sites.add(callSite);
            sites.addAll(sourceSitePath);
        }
        return new EagerEffectWitness(callerModule, targetModule, kind, effectSpan,
                targetDeclaration, referenceId, targetLambda, spans, calls, recursive,
                effectSite, sites);
    }

    public String canonicalKey() {
        StringBuilder result = new StringBuilder()
                .append(fromModule).append("->").append(targetModule)
                .append('/').append(kind)
                .append('/').append(effectSpan)
                .append("/recursive=").append(recursive);
        targetDeclaration.ifPresent(value -> result.append("/decl=").append(value));
        referenceId.ifPresent(value -> result.append("/reference=").append(value));
        targetLambda.ifPresent(value -> result.append("/lambda=").append(value));
        for (SourceSpan span : sourcePath) {
            result.append("/span=").append(span);
        }
        for (SummaryCallId call : callPath) {
            result.append("/call=").append(call);
        }
        effectSite.ifPresent(value -> result.append("/effect-site=").append(value));
        for (FlowSiteId site : sourceSitePath) {
            result.append("/site=").append(site);
        }
        return result.toString();
    }

    @Override
    public int compareTo(EagerEffectWitness other) {
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
}
