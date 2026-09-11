package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ExportId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.identity.ReferenceId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.FunctionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeSet;

/**
 * One ordered call reference retained by a parameterized callable summary.
 * Direct, namespace, callable-value, and higher-order parameter calls remain
 * distinct so later flow evaluation never has to rediscover call syntax.
 * Parameter and capture targets are canonical symbolic unions; caller-time
 * substitution resolves every member against the supplied finite facts.
 */
public record CallableCallReference(
        SummaryCallId id,
        Kind kind,
        SourceSpan span,
        Optional<ReferenceId> referenceId,
        Optional<DeclarationId> targetDeclaration,
        Optional<ModuleId> targetModule,
        Optional<ExportId> targetExport,
        Optional<LambdaId> targetLambda,
        List<Integer> targetParameterIndexes,
        List<CaptureId> targetCaptureIds,
        FormulaAlternatives target,
        List<FormulaAlternatives> arguments,
        Optional<FlowSiteId> siteId,
        Optional<Repeat> repeat)
        implements Comparable<CallableCallReference> {
    public enum Kind {
        DIRECT,
        NAMESPACE,
        CALLABLE,
        PARAMETER,
        CAPTURE,
        CONSTRUCTION
    }

    /** Repetition retains its selected action and optional pre-test predicate. */
    public record Repeat(Optional<FormulaAlternatives> predicate,
                         java.util.Map<DeclarationId, FormulaAlternatives> environment) {
        public Repeat {
            Objects.requireNonNull(predicate, "predicate");
            environment = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(environment));
            if (predicate.isPresent() && !predicate.orElseThrow().rootType().equals(
                    io.mindspice.lyra.compiler.semantic.CallbackLoop.predicateType())) {
                throw new IllegalArgumentException("loop predicate contract differs from Fn<;Bool>");
            }
        }

        public io.mindspice.lyra.compiler.types.TupleType snapshotType() {
            java.util.ArrayList<io.mindspice.lyra.compiler.types.LyraType> types = new java.util.ArrayList<>();
            types.add(io.mindspice.lyra.compiler.types.PrimitiveType.UNIT);
            environment.values().forEach(value -> types.add(value.rootType()));
            return new io.mindspice.lyra.compiler.types.TupleType(types);
        }
    }

    public CallableCallReference(SummaryCallId id, Kind kind, SourceSpan span,
            Optional<ReferenceId> referenceId, Optional<DeclarationId> targetDeclaration,
            Optional<ModuleId> targetModule, Optional<ExportId> targetExport, Optional<LambdaId> targetLambda,
            List<Integer> targetParameterIndexes, List<CaptureId> targetCaptureIds,
            FormulaAlternatives target, List<FormulaAlternatives> arguments, Optional<FlowSiteId> siteId) {
        this(id, kind, span, referenceId, targetDeclaration, targetModule, targetExport, targetLambda,
                targetParameterIndexes, targetCaptureIds, target, arguments, siteId, Optional.empty());
    }

    /** Compatibility constructor before capture-target identity was explicit. */
    public CallableCallReference(
            SummaryCallId id,
            Kind kind,
            SourceSpan span,
            Optional<ReferenceId> referenceId,
            Optional<DeclarationId> targetDeclaration,
            Optional<ModuleId> targetModule,
            Optional<ExportId> targetExport,
            Optional<LambdaId> targetLambda,
            OptionalInt targetParameterIndex,
            FormulaAlternatives target,
            List<FormulaAlternatives> arguments) {
        this(id, kind, span, referenceId, targetDeclaration, targetModule, targetExport,
                targetLambda, targetParameterIndex, Optional.empty(), target, arguments);
    }

    /** Compatibility constructor for the former singular placeholder metadata. */
    public CallableCallReference(
            SummaryCallId id,
            Kind kind,
            SourceSpan span,
            Optional<ReferenceId> referenceId,
            Optional<DeclarationId> targetDeclaration,
            Optional<ModuleId> targetModule,
            Optional<ExportId> targetExport,
            Optional<LambdaId> targetLambda,
            OptionalInt targetParameterIndex,
            Optional<CaptureId> targetCaptureId,
            FormulaAlternatives target,
            List<FormulaAlternatives> arguments) {
        this(id, kind, span, referenceId, targetDeclaration, targetModule, targetExport,
                targetLambda,
                symbolicParameterIndexes(target, targetParameterIndex),
                symbolicCaptureIds(target, targetCaptureId),
                target, arguments);
    }

    /** Compatibility constructor for facts created before exact flow-site links. */
    public CallableCallReference(
            SummaryCallId id,
            Kind kind,
            SourceSpan span,
            Optional<ReferenceId> referenceId,
            Optional<DeclarationId> targetDeclaration,
            Optional<ModuleId> targetModule,
            Optional<ExportId> targetExport,
            Optional<LambdaId> targetLambda,
            List<Integer> targetParameterIndexes,
            List<CaptureId> targetCaptureIds,
            FormulaAlternatives target,
            List<FormulaAlternatives> arguments) {
        this(id, kind, span, referenceId, targetDeclaration, targetModule,
                targetExport, targetLambda, targetParameterIndexes,
                targetCaptureIds, target, arguments, Optional.empty());
    }

    public CallableCallReference {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(referenceId, "referenceId");
        Objects.requireNonNull(targetDeclaration, "targetDeclaration");
        Objects.requireNonNull(targetModule, "targetModule");
        Objects.requireNonNull(targetExport, "targetExport");
        Objects.requireNonNull(targetLambda, "targetLambda");
        targetParameterIndexes = orderedParameterIndexes(targetParameterIndexes);
        targetCaptureIds = orderedCaptureIds(targetCaptureIds);
        Objects.requireNonNull(target, "target");
        arguments = copy(arguments, "arguments");
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(repeat, "repeat");
        if (!targetParameterIndexes.equals(symbolicParameterIndexes(
                target, OptionalInt.empty()))) {
            throw new IllegalArgumentException(
                    "target parameter placeholders do not match the target formulas");
        }
        if (!targetCaptureIds.equals(symbolicCaptureIds(target, Optional.empty()))) {
            throw new IllegalArgumentException(
                    "target capture placeholders do not match the target formulas");
        }
        if (!(target.rootType().withoutQualifiers() instanceof FunctionType function)) {
            throw new IllegalArgumentException("call target is not a function type");
        }
        if (arguments.size() != function.arity()) {
            throw new IllegalArgumentException("call argument count does not match target signature");
        }
        if (kind == Kind.DIRECT || kind == Kind.NAMESPACE) {
            if (targetDeclaration.isEmpty()) {
                throw new IllegalArgumentException(kind + " calls need a target declaration");
            }
        }
        if (kind == Kind.CONSTRUCTION && (targetDeclaration.isEmpty() || targetLambda.isPresent()
                || referenceId.isEmpty() || siteId.isEmpty() || repeat.isPresent()
                || target.formulas().size() != 1 || !(target.only() instanceof ValueFormula.Constructor constructor)
                || !targetDeclaration.equals(Optional.of(constructor.declaration())))) {
            throw new IllegalArgumentException("construction call must have one exact source-issued factory target");
        }
        if (kind != Kind.CONSTRUCTION && target.formulas().stream().anyMatch(ValueFormula.Constructor.class::isInstance)) {
            throw new IllegalArgumentException("nominal constructor targets cannot be invoked as user-callable values");
        }
        if (kind == Kind.PARAMETER
                && (targetParameterIndexes.isEmpty()
                || target.formulas().stream().anyMatch(
                formula -> !(formula instanceof ValueFormula.Parameter)))) {
            throw new IllegalArgumentException(
                    "parameter calls need only parameter placeholder targets");
        }
        if (kind == Kind.CAPTURE
                && (targetCaptureIds.isEmpty()
                || target.formulas().stream().anyMatch(
                formula -> !(formula instanceof ValueFormula.Capture)))) {
            throw new IllegalArgumentException(
                    "capture calls need only capture placeholder targets");
        }
    }

    public SummaryCallId callId() {
        return id;
    }

    public Kind referenceKind() {
        return kind;
    }

    public List<FormulaAlternatives> argumentFormulas() {
        return arguments;
    }

    /**
     * Returns the same source call with solver-materialized target and argument
     * formulas. Unresolved parameter, capture, and call-result dependencies
     * remain symbolic for caller-time substitution.
     */
    CallableCallReference withTransferFormulas(
            FormulaAlternatives materializedTarget,
            List<FormulaAlternatives> materializedArguments) {
        Objects.requireNonNull(materializedTarget, "materializedTarget");
        Objects.requireNonNull(materializedArguments, "materializedArguments");
        return new CallableCallReference(
                id, kind, span, referenceId, targetDeclaration, targetModule,
                targetExport, targetLambda,
                symbolicParameterIndexes(materializedTarget, OptionalInt.empty()),
                symbolicCaptureIds(materializedTarget, Optional.empty()),
                materializedTarget, materializedArguments, siteId, repeat);
    }

    public boolean isDirect() {
        return kind == Kind.DIRECT;
    }

    public boolean isNamespace() {
        return kind == Kind.NAMESPACE;
    }

    public boolean isCallableValue() {
        return kind == Kind.CALLABLE || kind == Kind.PARAMETER;
    }

    public boolean isCallable() {
        return isCallableValue();
    }

    public boolean isParameterInvocation() {
        return kind == Kind.PARAMETER;
    }

    public boolean isCaptureInvocation() {
        return kind == Kind.CAPTURE;
    }

    public boolean isHigherOrderParameterInvocation() {
        return kind == Kind.PARAMETER;
    }

    /** The singular parameter target when the symbolic union has one member. */
    public OptionalInt targetParameterIndex() {
        return targetParameterIndexes.size() == 1
                ? OptionalInt.of(targetParameterIndexes.getFirst())
                : OptionalInt.empty();
    }

    /** The singular capture target when the symbolic union has one member. */
    public Optional<CaptureId> targetCaptureId() {
        return targetCaptureIds.size() == 1
                ? Optional.of(targetCaptureIds.getFirst())
                : Optional.empty();
    }

    public OptionalInt parameterIndex() {
        return targetParameterIndex();
    }

    public Optional<CaptureId> captureId() {
        return targetCaptureId();
    }

    public String canonicalKey() {
        StringBuilder result = new StringBuilder(id.canonicalKey())
                .append('/').append(kind)
                .append('/').append(span.sourceId()).append(':')
                .append(span.startOffset()).append("..").append(span.endOffset())
                .append("/target=").append(target.canonicalKey());
        targetDeclaration.ifPresent(value -> result.append("/decl=").append(value));
        targetModule.ifPresent(value -> result.append("/module=").append(value));
        targetExport.ifPresent(value -> result.append("/export=").append(value));
        targetLambda.ifPresent(value -> result.append("/lambda=").append(value));
        targetParameterIndexes.forEach(
                value -> result.append("/parameter=").append(value));
        targetCaptureIds.forEach(
                value -> result.append("/capture=").append(value));
        referenceId.ifPresent(value -> result.append("/reference=").append(value));
        siteId.ifPresent(value -> result.append("/site=").append(value));
        repeat.ifPresent(value -> result.append("/repeat=").append(value));
        for (FormulaAlternatives argument : arguments) {
            result.append("/arg=").append(argument.canonicalKey());
        }
        return result.toString();
    }

    @Override
    public int compareTo(CallableCallReference other) {
        CallableCallReference value = Objects.requireNonNull(other, "other");
        int sourceOrder = id.compareTo(value.id);
        return sourceOrder != 0
                ? sourceOrder : canonicalKey().compareTo(value.canonicalKey());
    }

    @Override
    public String toString() {
        return canonicalKey();
    }

    private static List<Integer> symbolicParameterIndexes(
            FormulaAlternatives target,
            OptionalInt explicitTarget) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(explicitTarget, "explicitTarget");
        TreeSet<Integer> indexes = new TreeSet<>();
        for (ValueFormula formula : target.formulas()) {
            if (formula instanceof ValueFormula.Parameter parameter
                    && formula.resultRoute().isRoot()) {
                indexes.add(parameter.parameterIndex());
            }
        }
        explicitTarget.ifPresent(index -> {
            if (!indexes.contains(index)) {
                throw new IllegalArgumentException(
                        "explicit target parameter is absent from the target formulas");
            }
        });
        return List.copyOf(indexes);
    }

    private static List<CaptureId> symbolicCaptureIds(
            FormulaAlternatives target,
            Optional<CaptureId> explicitTarget) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(explicitTarget, "explicitTarget");
        TreeSet<CaptureId> captures = new TreeSet<>();
        for (ValueFormula formula : target.formulas()) {
            if (formula instanceof ValueFormula.Capture capture
                    && formula.resultRoute().isRoot()) {
                captures.add(capture.captureId());
            }
        }
        explicitTarget.ifPresent(capture -> {
            if (!captures.contains(capture)) {
                throw new IllegalArgumentException(
                        "explicit target capture is absent from the target formulas");
            }
        });
        return List.copyOf(captures);
    }

    private static List<Integer> orderedParameterIndexes(List<Integer> values) {
        Objects.requireNonNull(values, "targetParameterIndexes");
        TreeSet<Integer> ordered = new TreeSet<>();
        for (Integer value : values) {
            int index = Objects.requireNonNull(
                    value, "targetParameterIndexes must not contain null");
            if (index < 0) {
                throw new IllegalArgumentException(
                        "target parameter index must not be negative");
            }
            ordered.add(index);
        }
        return List.copyOf(ordered);
    }

    private static List<CaptureId> orderedCaptureIds(List<CaptureId> values) {
        Objects.requireNonNull(values, "targetCaptureIds");
        TreeSet<CaptureId> ordered = new TreeSet<>();
        for (CaptureId value : values) {
            ordered.add(Objects.requireNonNull(
                    value, "targetCaptureIds must not contain null"));
        }
        return List.copyOf(ordered);
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
