package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.identity.LambdaId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.types.ArrayType;
import io.mindspice.lyra.compiler.types.FunctionType;
import io.mindspice.lyra.compiler.types.LyraType;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One JVM-independent symbolic value term in a callable summary.
 *
 * <p>Every term describes a value at {@link #resultRoute()} in the summary's
 * returned value.  Parameter, capture, and declaration terms retain the route
 * at which the source value was read.  Aggregate literals contribute a
 * {@link FreshAllocation} term at their root and child terms at their routed
 * members.  The domain is intentionally finite and contains no recursively
 * expanding syntax tree.</p>
 */
public sealed interface ValueFormula extends Comparable<ValueFormula>
        permits ValueFormula.Parameter,
                ValueFormula.Capture,
                ValueFormula.Declaration,
                ValueFormula.Lambda,
                ValueFormula.FreshAllocation,
                ValueFormula.Scalar,
                ValueFormula.CallResult,
                ValueFormula.Opaque {
    LyraType type();

    ProjectionPath resultRoute();

    /** Returns a copy whose value is routed at {@code route} in its result. */
    ValueFormula withResultRoute(ProjectionPath route);

    default ProjectionPath route() {
        return resultRoute();
    }

    default ValueFormula prefixedBy(ProjectionPath prefix) {
        return withResultRoute(Objects.requireNonNull(prefix, "prefix").compose(resultRoute()));
    }

    /** Stable textual ordering key for canonical finite sets. */
    String canonicalKey();

    @Override
    default int compareTo(ValueFormula other) {
        return canonicalKey().compareTo(
                Objects.requireNonNull(other, "other").canonicalKey());
    }

    /** A positional lambda parameter placeholder. */
    record Parameter(
            DeclarationId declarationId,
            int parameterIndex,
            ProjectionPath parameterRoute,
            ProjectionPath resultRoute,
            LyraType type) implements ValueFormula {
        public Parameter {
            Objects.requireNonNull(declarationId, "declarationId");
            if (parameterIndex < 0) {
                throw new IllegalArgumentException("parameter index must not be negative");
            }
            Objects.requireNonNull(parameterRoute, "parameterRoute");
            Objects.requireNonNull(resultRoute, "resultRoute");
            Objects.requireNonNull(type, "type");
        }

        public Parameter(
                int parameterIndex,
                DeclarationId declarationId,
                LyraType type) {
            this(declarationId, parameterIndex, ProjectionPath.root(),
                    ProjectionPath.root(), type);
        }

        public DeclarationId parameter() {
            return declarationId;
        }

        public ProjectionPath sourceRoute() {
            return parameterRoute;
        }

        @Override
        public Parameter withResultRoute(ProjectionPath route) {
            return new Parameter(declarationId, parameterIndex, parameterRoute,
                    Objects.requireNonNull(route, "route"), type);
        }

        public Parameter withParameterRoute(ProjectionPath route) {
            return new Parameter(declarationId, parameterIndex,
                    Objects.requireNonNull(route, "route"), resultRoute, type);
        }

        @Override
        public String canonicalKey() {
            return "parameter/" + parameterIndex + "/" + declarationId
                    + "/source=" + parameterRoute + "/result=" + resultRoute
                    + "/type=" + type.canonicalSpelling();
        }

        @Override
        public String toString() {
            return canonicalKey();
        }
    }

    /** A lexical capture placeholder, including mutable shared-cell identity. */
    record Capture(
            CaptureId captureId,
            DeclarationId declarationId,
            Optional<DeclarationId> sharedCellId,
            ProjectionPath captureRoute,
            ProjectionPath resultRoute,
            LyraType type) implements ValueFormula {
        public Capture {
            Objects.requireNonNull(captureId, "captureId");
            Objects.requireNonNull(declarationId, "declarationId");
            Objects.requireNonNull(sharedCellId, "sharedCellId");
            Objects.requireNonNull(captureRoute, "captureRoute");
            Objects.requireNonNull(resultRoute, "resultRoute");
            Objects.requireNonNull(type, "type");
        }

        /** Compatibility constructor for an immutable or metadata-free capture. */
        public Capture(
                CaptureId captureId,
                DeclarationId declarationId,
                ProjectionPath captureRoute,
                ProjectionPath resultRoute,
                LyraType type) {
            this(captureId, declarationId, Optional.empty(), captureRoute, resultRoute, type);
        }

        public ProjectionPath sourceRoute() {
            return captureRoute;
        }

        public Optional<DeclarationId> cellId() {
            return sharedCellId;
        }

        public boolean isSharedCell() {
            return sharedCellId.isPresent();
        }

        public Capture withCaptureRoute(ProjectionPath route) {
            return new Capture(captureId, declarationId, sharedCellId,
                    Objects.requireNonNull(route, "route"), resultRoute, type);
        }

        @Override
        public Capture withResultRoute(ProjectionPath route) {
            return new Capture(captureId, declarationId, sharedCellId, captureRoute,
                    Objects.requireNonNull(route, "route"), type);
        }

        @Override
        public String canonicalKey() {
            return "capture/" + captureId + "/" + declarationId
                    + "/cell=" + sharedCellId.map(Object::toString).orElse("-")
                    + "/source=" + captureRoute + "/result=" + resultRoute
                    + "/type=" + type.canonicalSpelling();
        }

        @Override
        public String toString() {
            return canonicalKey();
        }
    }

    /** A source declaration value whose initializer is outside this lambda. */
    record Declaration(
            DeclarationId declarationId,
            Optional<ModuleId> moduleId,
            ProjectionPath declarationRoute,
            ProjectionPath resultRoute,
            LyraType type,
            boolean attachableBoundary) implements ValueFormula {
        public Declaration {
            Objects.requireNonNull(declarationId, "declarationId");
            Objects.requireNonNull(moduleId, "moduleId");
            Objects.requireNonNull(declarationRoute, "declarationRoute");
            Objects.requireNonNull(resultRoute, "resultRoute");
            Objects.requireNonNull(type, "type");
        }

        public Declaration(
                DeclarationId declarationId,
                Optional<ModuleId> moduleId,
                ProjectionPath declarationRoute,
                ProjectionPath resultRoute,
                LyraType type) {
            this(declarationId, moduleId, declarationRoute, resultRoute, type, false);
        }

        public Declaration(
                DeclarationId declarationId,
                LyraType type) {
            this(declarationId, Optional.empty(), ProjectionPath.root(),
                    ProjectionPath.root(), type, false);
        }

        public ProjectionPath sourceRoute() {
            return declarationRoute;
        }

        public Declaration withDeclarationRoute(ProjectionPath route) {
            return new Declaration(declarationId, moduleId,
                    Objects.requireNonNull(route, "route"), resultRoute, type,
                    attachableBoundary);
        }

        public Declaration withAttachableBoundary() {
            return new Declaration(declarationId, moduleId, declarationRoute,
                    resultRoute, type, true);
        }

        @Override
        public Declaration withResultRoute(ProjectionPath route) {
            return new Declaration(declarationId, moduleId, declarationRoute,
                    Objects.requireNonNull(route, "route"), type, attachableBoundary);
        }

        @Override
        public String canonicalKey() {
            return "declaration/" + declarationId + "/module="
                    + moduleId.map(ModuleId::toString).orElse("unknown")
                    + "/source=" + declarationRoute + "/result=" + resultRoute
                    + "/attachable=" + attachableBoundary
                    + "/type=" + type.canonicalSpelling();
        }

        @Override
        public String toString() {
            return canonicalKey();
        }
    }

    /** A lambda value, including the symbolic environment captured at creation. */
    record Lambda(
            LambdaId lambdaId,
            FunctionType functionType,
            ProjectionPath resultRoute,
            Map<CaptureId, FormulaAlternatives> capturedValues) implements ValueFormula {
        public Lambda {
            Objects.requireNonNull(lambdaId, "lambdaId");
            Objects.requireNonNull(functionType, "functionType");
            Objects.requireNonNull(resultRoute, "resultRoute");
            capturedValues = immutableCaptures(capturedValues);
        }

        public Lambda(
                LambdaId lambdaId,
                FunctionType functionType) {
            this(lambdaId, functionType, ProjectionPath.root(), Map.of());
        }

        @Override
        public FunctionType type() {
            return functionType;
        }

        public FunctionType functionType() {
            return functionType;
        }

        public Map<CaptureId, FormulaAlternatives> captures() {
            return capturedValues;
        }

        public Lambda withCaptures(Map<CaptureId, FormulaAlternatives> captures) {
            return new Lambda(lambdaId, functionType, resultRoute, captures);
        }

        @Override
        public Lambda withResultRoute(ProjectionPath route) {
            return new Lambda(lambdaId, functionType,
                    Objects.requireNonNull(route, "route"), capturedValues);
        }

        @Override
        public String canonicalKey() {
            StringBuilder result = new StringBuilder("lambda/")
                    .append(lambdaId)
                    .append("/result=").append(resultRoute)
                    .append("/type=").append(functionType.canonicalSpelling());
            capturedValues.forEach((capture, value) -> result
                    .append("/capture=").append(capture).append(':').append(value));
            return result.toString();
        }

        @Override
        public String toString() {
            return canonicalKey();
        }

        private static Map<CaptureId, FormulaAlternatives> immutableCaptures(
                Map<CaptureId, FormulaAlternatives> values) {
            Objects.requireNonNull(values, "capturedValues");
            TreeMap<CaptureId, FormulaAlternatives> ordered = new TreeMap<>();
            values.forEach((capture, formula) -> ordered.put(
                    Objects.requireNonNull(capture, "capture id"),
                    Objects.requireNonNull(formula, "capture formula")));
            return Collections.unmodifiableMap(ordered);
        }
    }

    /** A fresh identity-bearing array allocation at a source allocation site. */
    record FreshAllocation(
            FreshAllocationSite allocationSite,
            ArrayType arrayType,
            ProjectionPath resultRoute) implements ValueFormula {
        public FreshAllocation {
            Objects.requireNonNull(allocationSite, "allocationSite");
            Objects.requireNonNull(arrayType, "arrayType");
            Objects.requireNonNull(resultRoute, "resultRoute");
        }

        public FreshAllocation(
                FreshAllocationSite allocationSite,
                ArrayType arrayType) {
            this(allocationSite, arrayType, ProjectionPath.root());
        }

        @Override
        public ArrayType type() {
            return arrayType;
        }

        public FreshAllocationSite site() {
            return allocationSite;
        }

        @Override
        public FreshAllocation withResultRoute(ProjectionPath route) {
            return new FreshAllocation(allocationSite, arrayType,
                    Objects.requireNonNull(route, "route"));
        }

        @Override
        public String canonicalKey() {
            return "fresh/" + allocationSite + "/result=" + resultRoute
                    + "/type=" + arrayType.canonicalSpelling();
        }

        @Override
        public String toString() {
            return canonicalKey();
        }
    }

    /** A scalar or aggregate shape term with no tracked identity source. */
    record Scalar(
            LyraType type,
            ProjectionPath resultRoute,
            Optional<String> literalKey,
            Optional<FlowSiteId> nilSourceSite,
            Optional<SourceSpan> nilSourceSpan) implements ValueFormula {
        public Scalar {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(resultRoute, "resultRoute");
            Objects.requireNonNull(literalKey, "literalKey");
            Objects.requireNonNull(nilSourceSite, "nilSourceSite");
            Objects.requireNonNull(nilSourceSpan, "nilSourceSpan");
            literalKey.ifPresent(value -> {
                if (value.isEmpty()) {
                    throw new IllegalArgumentException("literal key must not be empty");
                }
            });
            if (nilSourceSite.isPresent() != nilSourceSpan.isPresent()) {
                throw new IllegalArgumentException(
                        "nil scalar provenance needs both a site identity and span");
            }
        }

        public Scalar(LyraType type, ProjectionPath resultRoute) {
            this(type, resultRoute, Optional.empty(), Optional.empty(), Optional.empty());
        }

        public Scalar(LyraType type) {
            this(type, ProjectionPath.root(), Optional.empty(), Optional.empty(), Optional.empty());
        }

        public static Scalar literal(LyraType type, String literalKey) {
            return new Scalar(type, ProjectionPath.root(),
                    Optional.of(Objects.requireNonNull(literalKey, "literalKey")),
                    Optional.empty(), Optional.empty());
        }

        public static Scalar nil(
                LyraType type, FlowSiteId sourceSite, SourceSpan sourceSpan) {
            return new Scalar(type, ProjectionPath.root(), Optional.of("#NIL"),
                    Optional.of(Objects.requireNonNull(sourceSite, "sourceSite")),
                    Optional.of(Objects.requireNonNull(sourceSpan, "sourceSpan")));
        }

        public boolean isNil() {
            return nilSourceSite.isPresent();
        }

        @Override
        public Scalar withResultRoute(ProjectionPath route) {
            return new Scalar(type, Objects.requireNonNull(route, "route"), literalKey,
                    nilSourceSite, nilSourceSpan);
        }

        @Override
        public String canonicalKey() {
            return "scalar/" + type.canonicalSpelling() + "/result=" + resultRoute
                    + "/literal=" + literalKey.orElse("-")
                    + "/nil-site=" + nilSourceSite.map(Object::toString).orElse("-")
                    + "/nil-span=" + nilSourceSpan.map(Object::toString).orElse("-");
        }

        @Override
        public String toString() {
            return canonicalKey();
        }
    }

    /** A call result retained as a finite terminal when the call is unresolved statically. */
    record CallResult(
            SummaryCallId callId,
            LyraType type,
            ProjectionPath callRoute,
            ProjectionPath resultRoute) implements ValueFormula {
        public CallResult {
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(callRoute, "callRoute");
            Objects.requireNonNull(resultRoute, "resultRoute");
        }

        /** A call result at its complete returned-value root. */
        public CallResult(SummaryCallId callId, LyraType type) {
            this(callId, type, ProjectionPath.root(), ProjectionPath.root());
        }

        /** Compatibility constructor whose third route is the outer result route. */
        public CallResult(SummaryCallId callId, LyraType type, ProjectionPath resultRoute) {
            this(callId, type, ProjectionPath.root(), resultRoute);
        }

        public ProjectionPath sourceRoute() {
            return callRoute;
        }

        public CallResult withCallRoute(ProjectionPath route) {
            return new CallResult(callId, type, Objects.requireNonNull(route, "route"), resultRoute);
        }

        @Override
        public CallResult withResultRoute(ProjectionPath route) {
            return new CallResult(callId, type, callRoute,
                    Objects.requireNonNull(route, "route"));
        }

        @Override
        public String canonicalKey() {
            return "call-result/" + callId + "/source=" + callRoute
                    + "/result=" + resultRoute
                    + "/type=" + type.canonicalSpelling();
        }

        @Override
        public String toString() {
            return canonicalKey();
        }
    }

    /** A finite top value used only where a typed result has no tracked identity source. */
    record Opaque(
            LyraType type,
            ProjectionPath resultRoute,
            String reason) implements ValueFormula {
        public Opaque {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(resultRoute, "resultRoute");
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("opaque formula reason must not be blank");
            }
        }

        public Opaque(LyraType type, String reason) {
            this(type, ProjectionPath.root(), reason);
        }

        @Override
        public Opaque withResultRoute(ProjectionPath route) {
            return new Opaque(type, Objects.requireNonNull(route, "route"), reason);
        }

        @Override
        public String canonicalKey() {
            return "opaque/" + type.canonicalSpelling() + "/result=" + resultRoute
                    + "/reason=" + reason;
        }

        @Override
        public String toString() {
            return canonicalKey();
        }
    }
}
