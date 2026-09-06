package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ScopeId;
import io.mindspice.lyra.compiler.semantic.CaptureMode;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.BindingContract;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.LyraSignature;
import io.mindspice.lyra.compiler.types.LyraType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeSet;

/**
 * One complete, ordered, parameterized symbolic transfer summary for a
 * lambda.  It is an internal compiler model and deliberately has no runtime,
 * JVM, serialization, or plug-in meaning.
 */
public final class CallableSummary implements Comparable<CallableSummary> {
    private final LambdaId lambdaId;
    private final ModuleId moduleId;
    private final SourceSpan span;
    private final ScopeId scopeId;
    private final LyraSignature signature;
    private final List<ParameterPlaceholder> parameters;
    private final List<CapturePlaceholder> captures;
    private final ReturnFormula returnFormula;
    private final List<CapturedCellWrite> writes;
    private final List<CallableCallReference> callReferences;
    private final List<OwnershipRequirement> ownershipRequirements;
    private final List<EagerEffectWitness> eagerEffects;
    private final NormalizedExpression normalizedBody;
    private final boolean fixedPoint;
    private final int fixedPointIterations;
    private final SummaryLimits limits;

    public CallableSummary(
            LambdaId lambdaId,
            ModuleId moduleId,
            SourceSpan span,
            ScopeId scopeId,
            LyraSignature signature,
            List<ParameterPlaceholder> parameters,
            List<CapturePlaceholder> captures,
            ReturnFormula returnFormula,
            List<CapturedCellWrite> writes,
            List<CallableCallReference> callReferences,
            List<EagerEffectWitness> eagerEffects,
            NormalizedExpression normalizedBody) {
        this(lambdaId, moduleId, span, scopeId, signature, parameters, captures,
                returnFormula, writes, callReferences, List.of(), eagerEffects,
                normalizedBody, false, 0, SummaryLimits.DEFAULT);
    }

    CallableSummary(
            LambdaId lambdaId,
            ModuleId moduleId,
            SourceSpan span,
            ScopeId scopeId,
            LyraSignature signature,
            List<ParameterPlaceholder> parameters,
            List<CapturePlaceholder> captures,
            ReturnFormula returnFormula,
            List<CapturedCellWrite> writes,
            List<CallableCallReference> callReferences,
            List<OwnershipRequirement> ownershipRequirements,
            List<EagerEffectWitness> eagerEffects,
            NormalizedExpression normalizedBody) {
        this(lambdaId, moduleId, span, scopeId, signature, parameters, captures,
                returnFormula, writes, callReferences, ownershipRequirements,
                eagerEffects, normalizedBody, false, 0, SummaryLimits.DEFAULT);
    }

    public CallableSummary(
            LambdaId lambdaId,
            ModuleId moduleId,
            SourceSpan span,
            ScopeId scopeId,
            LyraSignature signature,
            List<ParameterPlaceholder> parameters,
            List<CapturePlaceholder> captures,
            ReturnFormula returnFormula,
            List<CapturedCellWrite> writes,
            List<CallableCallReference> callReferences,
            List<EagerEffectWitness> eagerEffects,
            NormalizedExpression normalizedBody,
            boolean fixedPoint,
            int fixedPointIterations,
            SummaryLimits limits) {
        this(lambdaId, moduleId, span, scopeId, signature, parameters, captures,
                returnFormula, writes, callReferences, List.of(), eagerEffects,
                normalizedBody, fixedPoint, fixedPointIterations, limits);
    }

    CallableSummary(
            LambdaId lambdaId,
            ModuleId moduleId,
            SourceSpan span,
            ScopeId scopeId,
            LyraSignature signature,
            List<ParameterPlaceholder> parameters,
            List<CapturePlaceholder> captures,
            ReturnFormula returnFormula,
            List<CapturedCellWrite> writes,
            List<CallableCallReference> callReferences,
            List<OwnershipRequirement> ownershipRequirements,
            List<EagerEffectWitness> eagerEffects,
            NormalizedExpression normalizedBody,
            boolean fixedPoint,
            int fixedPointIterations,
            SummaryLimits limits) {
        this.lambdaId = Objects.requireNonNull(lambdaId, "lambdaId");
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId");
        this.span = Objects.requireNonNull(span, "span");
        this.scopeId = Objects.requireNonNull(scopeId, "scopeId");
        this.signature = Objects.requireNonNull(signature, "signature");
        this.parameters = orderedParameters(parameters, signature);
        this.captures = orderedCaptures(captures);
        this.returnFormula = Objects.requireNonNull(returnFormula, "returnFormula");
        if (!returnFormula.resultType().withoutQualifiers()
                .equals(signature.returnType().withoutQualifiers())) {
            throw new IllegalArgumentException("return formula type does not match signature");
        }
        if (returnFormula.alternatives().isEmpty()) {
            throw new IllegalArgumentException("a complete callable summary needs a return formula");
        }
        this.writes = orderedWrites(writes);
        this.callReferences = orderedCalls(callReferences, lambdaId);
        this.ownershipRequirements = orderedOwnershipRequirements(
                ownershipRequirements);
        this.eagerEffects = orderedEffects(eagerEffects);
        this.normalizedBody = Objects.requireNonNull(normalizedBody, "normalizedBody");
        this.fixedPoint = fixedPoint;
        if (fixedPointIterations < 0) {
            throw new IllegalArgumentException("fixed-point iteration count must not be negative");
        }
        this.fixedPointIterations = fixedPointIterations;
        this.limits = Objects.requireNonNull(limits, "limits");
        limits.requireFormulaAlternatives(returnFormula.alternatives().size());
        limits.requireWrites(this.writes.size());
        limits.requireCallReferences(this.callReferences.size());
        limits.requireOwnershipRequirements(
                this.ownershipRequirements.size());
        limits.requireEffects(this.eagerEffects.size());
        if (!moduleId.sourceId().equals(span.sourceId())
                || !moduleId.sourceId().equals(normalizedBody.span().sourceId())) {
            throw new IllegalArgumentException("callable summary spans belong to another module");
        }
    }

    public LambdaId lambdaId() {
        return lambdaId;
    }

    public LambdaId id() {
        return lambdaId;
    }

    public ModuleId moduleId() {
        return moduleId;
    }

    public SourceSpan span() {
        return span;
    }

    public ScopeId scopeId() {
        return scopeId;
    }

    public LyraSignature signature() {
        return signature;
    }

    public LyraSignature functionSignature() {
        return signature;
    }

    public List<ParameterPlaceholder> parameters() {
        return parameters;
    }

    public List<ParameterPlaceholder> parameterPlaceholders() {
        return parameters;
    }

    public List<CapturePlaceholder> captures() {
        return captures;
    }

    public List<CapturePlaceholder> capturePlaceholders() {
        return captures;
    }

    public ReturnFormula returnFormula() {
        return returnFormula;
    }

    public ReturnFormula returnFormulas() {
        return returnFormula;
    }

    public List<CapturedCellWrite> writes() {
        return writes;
    }

    public List<CapturedCellWrite> capturedCellWrites() {
        return writes.stream().filter(CapturedCellWrite::isCaptureCellWrite).toList();
    }

    public List<CapturedCellWrite> parameterWrites() {
        return writes.stream().filter(CapturedCellWrite::isParameterWrite).toList();
    }

    public List<CallableCallReference> callReferences() {
        return callReferences;
    }

    public List<CallableCallReference> calls() {
        return callReferences;
    }

    public List<OwnershipRequirement> ownershipRequirements() {
        return ownershipRequirements;
    }

    public List<EagerEffectWitness> eagerEffects() {
        return eagerEffects;
    }

    public List<EagerEffectWitness> effects() {
        return eagerEffects;
    }

    public NormalizedExpression normalizedBody() {
        return normalizedBody;
    }

    public boolean isFixedPoint() {
        return fixedPoint;
    }

    public boolean fixedPoint() {
        return fixedPoint;
    }

    public int fixedPointIterations() {
        return fixedPointIterations;
    }

    public SummaryLimits limits() {
        return limits;
    }

    public String canonicalKey() {
        StringBuilder result = new StringBuilder()
                .append(lambdaId).append('/').append(moduleId).append('/')
                .append(span).append('/').append(scopeId).append('/')
                .append(signature.canonicalSpelling());
        for (ParameterPlaceholder parameter : parameters) {
            result.append("/parameter=").append(parameter.canonicalKey());
        }
        for (CapturePlaceholder capture : captures) {
            result.append("/capture=").append(capture.canonicalKey());
        }
        result.append("/return=").append(returnFormula.canonicalKey());
        for (CapturedCellWrite write : writes) {
            result.append("/write=").append(write.canonicalKey());
        }
        for (CallableCallReference call : callReferences) {
            result.append("/call=").append(call.canonicalKey());
        }
        for (OwnershipRequirement requirement : ownershipRequirements) {
            result.append("/ownership=").append(requirement.canonicalKey());
        }
        for (EagerEffectWitness effect : eagerEffects) {
            result.append("/effect=").append(effect.canonicalKey());
        }
        result.append("/body=").append(normalizedBody.canonicalKey())
                .append("/limits=").append(limits)
                .append("/fixed=").append(fixedPoint)
                .append('/').append(fixedPointIterations);
        return result.toString();
    }

    /** Returns a solved copy with the same normalized source operations. */
    CallableSummary solved(
            ReturnFormula returnFormula,
            List<CapturedCellWrite> writes,
            List<OwnershipRequirement> ownershipRequirements,
            List<EagerEffectWitness> eagerEffects,
            int iterations) {
        return new CallableSummary(lambdaId, moduleId, span, scopeId, signature,
                parameters, captures, returnFormula, writes, callReferences,
                ownershipRequirements, eagerEffects, normalizedBody, true,
                iterations, limits);
    }

    /** Returns a copy whose source summary remains an explicit least-point seed. */
    CallableSummary withLimits(SummaryLimits replacement) {
        return new CallableSummary(lambdaId, moduleId, span, scopeId, signature,
                parameters, captures, returnFormula, writes, callReferences,
                ownershipRequirements, eagerEffects, normalizedBody, fixedPoint,
                fixedPointIterations,
                Objects.requireNonNull(replacement, "replacement"));
    }

    @Override
    public int compareTo(CallableSummary other) {
        return canonicalKey().compareTo(
                Objects.requireNonNull(other, "other").canonicalKey());
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof CallableSummary summary
                && canonicalKey().equals(summary.canonicalKey());
    }

    @Override
    public int hashCode() {
        return canonicalKey().hashCode();
    }

    @Override
    public String toString() {
        return canonicalKey();
    }

    /** One signature-positioned parameter placeholder. */
    public record ParameterPlaceholder(
            int index,
            DeclarationId declarationId,
            BindingContract contract) implements Comparable<ParameterPlaceholder> {
        public ParameterPlaceholder {
            if (index < 0) {
                throw new IllegalArgumentException("parameter index must not be negative");
            }
            Objects.requireNonNull(declarationId, "declarationId");
            Objects.requireNonNull(contract, "contract");
        }

        public int parameterIndex() {
            return index;
        }

        public DeclarationId parameter() {
            return declarationId;
        }

        public LyraType type() {
            return contract.valueType();
        }

        public String canonicalKey() {
            return index + ":" + declarationId + ":" + contract.canonicalSpelling();
        }

        @Override
        public int compareTo(ParameterPlaceholder other) {
            return canonicalKey().compareTo(
                    Objects.requireNonNull(other, "other").canonicalKey());
        }
    }

    /** One capture placeholder, retaining shared-cell identity when mutable. */
    public record CapturePlaceholder(
            CaptureId captureId,
            DeclarationId declarationId,
            CaptureMode mode,
            Optional<DeclarationId> sharedCellId,
            BindingContract contract) implements Comparable<CapturePlaceholder> {
        public CapturePlaceholder {
            Objects.requireNonNull(captureId, "captureId");
            Objects.requireNonNull(declarationId, "declarationId");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(sharedCellId, "sharedCellId");
            Objects.requireNonNull(contract, "contract");
            if (mode == CaptureMode.SHARED_MUTABLE_CELL && sharedCellId.isEmpty()) {
                throw new IllegalArgumentException("shared mutable captures need a cell identity");
            }
            if (mode == CaptureMode.IMMUTABLE_VALUE && sharedCellId.isPresent()) {
                throw new IllegalArgumentException("immutable captures cannot have a cell identity");
            }
        }

        public CaptureId id() {
            return captureId;
        }

        public DeclarationId declaration() {
            return declarationId;
        }

        public Optional<DeclarationId> cellId() {
            return sharedCellId;
        }

        public boolean isSharedCell() {
            return mode == CaptureMode.SHARED_MUTABLE_CELL;
        }

        public LyraType type() {
            return contract.valueType();
        }

        public String canonicalKey() {
            return captureId + ":" + declarationId + ":" + mode + ":"
                    + sharedCellId.map(Object::toString).orElse("-") + ":"
                    + contract.canonicalSpelling();
        }

        @Override
        public int compareTo(CapturePlaceholder other) {
            return canonicalKey().compareTo(
                    Objects.requireNonNull(other, "other").canonicalKey());
        }
    }

    /** The finite return-value formula alternatives for one callable. */
    public record ReturnFormula(
            LyraType resultType,
            FormulaAlternatives alternatives) {
        public ReturnFormula {
            Objects.requireNonNull(resultType, "resultType");
            Objects.requireNonNull(alternatives, "alternatives");
            if (!resultType.withoutQualifiers()
                    .equals(alternatives.rootType().withoutQualifiers())) {
                throw new IllegalArgumentException("return formula root type mismatch");
            }
        }

        public ReturnFormula(FormulaAlternatives alternatives) {
            this(Objects.requireNonNull(alternatives, "alternatives").rootType(), alternatives);
        }

        public ReturnFormula(
                LyraType resultType,
                List<? extends ValueFormula> formulas) {
            this(resultType, new FormulaAlternatives(
                    resultType, new java.util.ArrayList<>(formulas)));
        }

        public List<ValueFormula> formulas() {
            return alternatives.formulas();
        }

        public List<ValueFormula> values() {
            return formulas();
        }

        public String canonicalKey() {
            return resultType.canonicalSpelling() + ":" + alternatives.canonicalKey();
        }
    }

    private static List<ParameterPlaceholder> orderedParameters(
            List<ParameterPlaceholder> values,
            LyraSignature signature) {
        Objects.requireNonNull(values, "parameters");
        ArrayList<ParameterPlaceholder> result = new ArrayList<>();
        for (ParameterPlaceholder value : values) {
            result.add(Objects.requireNonNull(value, "parameters must not contain null"));
        }
        result.sort(Comparator.comparingInt(ParameterPlaceholder::index));
        if (result.size() != signature.arity()) {
            throw new IllegalArgumentException("summary parameter count does not match signature");
        }
        for (int index = 0; index < result.size(); index++) {
            if (result.get(index).index() != index
                    || !result.get(index).type().withoutQualifiers()
                    .equals(signature.parameterType(index).withoutQualifiers())) {
                throw new IllegalArgumentException("summary parameter placeholder order/type mismatch");
            }
        }
        return List.copyOf(result);
    }

    private static List<CapturePlaceholder> orderedCaptures(
            List<CapturePlaceholder> values) {
        Objects.requireNonNull(values, "captures");
        TreeSet<CapturePlaceholder> unique = new TreeSet<>();
        TreeSet<CaptureId> identities = new TreeSet<>();
        for (CapturePlaceholder value : values) {
            CapturePlaceholder capture = Objects.requireNonNull(
                    value, "captures must not contain null");
            if (!identities.add(capture.captureId())) {
                throw new IllegalArgumentException("duplicate capture placeholder: "
                        + capture.captureId());
            }
            unique.add(capture);
        }
        return List.copyOf(unique);
    }

    private static List<CapturedCellWrite> orderedWrites(
            List<CapturedCellWrite> values) {
        Objects.requireNonNull(values, "writes");
        LinkedHashSet<CapturedCellWrite> unique = new LinkedHashSet<>();
        for (CapturedCellWrite value : values) {
            unique.add(Objects.requireNonNull(value, "writes must not contain null"));
        }
        ArrayList<CapturedCellWrite> result = new ArrayList<>(unique);
        result.sort(Comparator.comparingInt(CapturedCellWrite::sequence)
                .thenComparing(CapturedCellWrite::canonicalKey));
        return List.copyOf(result);
    }

    private static List<OwnershipRequirement> orderedOwnershipRequirements(
            List<OwnershipRequirement> values) {
        Objects.requireNonNull(values, "ownershipRequirements");
        java.util.TreeMap<String, OwnershipRequirement> unique =
                new java.util.TreeMap<>();
        for (OwnershipRequirement value : values) {
            OwnershipRequirement requirement = Objects.requireNonNull(
                    value, "ownershipRequirements must not contain null");
            unique.merge(requirement.semanticKey(), requirement,
                    (left, right) -> left.sequence() <= right.sequence()
                            ? left : right);
        }
        ArrayList<OwnershipRequirement> result = new ArrayList<>(unique.values());
        result.sort(OwnershipRequirement::compareTo);
        return List.copyOf(result);
    }

    private static List<CallableCallReference> orderedCalls(
            List<CallableCallReference> values,
            LambdaId owner) {
        Objects.requireNonNull(values, "callReferences");
        LinkedHashSet<CallableCallReference> unique = new LinkedHashSet<>();
        for (CallableCallReference value : values) {
            CallableCallReference call = Objects.requireNonNull(
                    value, "callReferences must not contain null");
            if (!call.id().ownerLambda().equals(owner)) {
                throw new IllegalArgumentException("call reference belongs to another lambda");
            }
            unique.add(call);
        }
        ArrayList<CallableCallReference> result = new ArrayList<>(unique);
        result.sort(Comparator.comparing(CallableCallReference::id)
                .thenComparing(CallableCallReference::canonicalKey));
        return List.copyOf(result);
    }

    private static List<EagerEffectWitness> orderedEffects(
            List<EagerEffectWitness> values) {
        Objects.requireNonNull(values, "eagerEffects");
        TreeSet<EagerEffectWitness> unique = new TreeSet<>();
        for (EagerEffectWitness value : values) {
            unique.add(Objects.requireNonNull(value, "eagerEffects must not contain null"));
        }
        return List.copyOf(unique);
    }
}
