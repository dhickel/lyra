package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One ordered symbolic write to a captured cell, retained session storage, or mutable parameter.
 *
 * <p>The name emphasizes the closure case, while the parameter variant is
 * retained here because a mutable parameter write must be transferable to the
 * caller through the same summary operation. The sequence is a sparse,
 * caller-event-major order key so a later higher-order transfer can be placed
 * between already materialized writes without losing evaluation order.</p>
 */
public record CapturedCellWrite(
        int sequence,
        Optional<CaptureId> captureId,
        OptionalInt parameterIndex,
        DeclarationId declarationId,
        Optional<DeclarationId> sharedCellId,
        Kind kind,
        ProjectionPath route,
        FormulaAlternatives value,
        SourceSpan span,
        Optional<FlowSiteId> operationSite)
        implements Comparable<CapturedCellWrite> {
    public enum Kind {
        WHOLE,
        EXACT_ROUTE,
        UNKNOWN_ROUTE
    }

    public CapturedCellWrite {
        if (sequence < 0) {
            throw new IllegalArgumentException("write sequence must not be negative");
        }
        Objects.requireNonNull(captureId, "captureId");
        Objects.requireNonNull(parameterIndex, "parameterIndex");
        Objects.requireNonNull(declarationId, "declarationId");
        Objects.requireNonNull(sharedCellId, "sharedCellId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(operationSite, "operationSite");
        boolean declarationTarget = captureId.isEmpty() && parameterIndex.isEmpty()
                && sharedCellId.filter(declarationId::equals).isPresent() && operationSite.isPresent();
        if (captureId.isPresent() == parameterIndex.isPresent() && !declarationTarget) {
            throw new IllegalArgumentException(
                    "a symbolic write needs one capture, parameter, or exact retained storage target");
        }
        if (captureId.isPresent() && sharedCellId.isEmpty() && kind == Kind.WHOLE) {
            throw new IllegalArgumentException(
                    "whole captured writes need a shared cell identity");
        }
        if (parameterIndex.isPresent() && sharedCellId.isPresent()) {
            throw new IllegalArgumentException("parameter writes cannot carry a shared cell identity");
        }
        if (kind == Kind.WHOLE && !route.isRoot()) {
            throw new IllegalArgumentException("whole writes must use the root route");
        }
        if (kind == Kind.UNKNOWN_ROUTE && !route.containsWildcard()) {
            throw new IllegalArgumentException("unknown writes must contain a wildcard route");
        }
        if (kind != Kind.UNKNOWN_ROUTE && !route.isExact()) {
            throw new IllegalArgumentException("exact writes cannot contain a wildcard route");
        }
    }

    public CapturedCellWrite(int sequence, Optional<CaptureId> captureId, OptionalInt parameterIndex,
            DeclarationId declarationId, Optional<DeclarationId> sharedCellId, Kind kind,
            ProjectionPath route, FormulaAlternatives value, SourceSpan span) {
        this(sequence, captureId, parameterIndex, declarationId, sharedCellId, kind, route, value, span,
                Optional.empty());
    }

    public static CapturedCellWrite capture(
            int sequence,
            CaptureId captureId,
            DeclarationId declarationId,
            DeclarationId sharedCellId,
            Kind kind,
            ProjectionPath route,
            FormulaAlternatives value,
            SourceSpan span) {
        return new CapturedCellWrite(sequence, Optional.of(Objects.requireNonNull(captureId, "captureId")),
                OptionalInt.empty(), declarationId, Optional.of(sharedCellId), kind, route, value, span);
    }

    public static CapturedCellWrite captureAggregate(
            int sequence,
            CaptureId captureId,
            DeclarationId declarationId,
            Kind kind,
            ProjectionPath route,
            FormulaAlternatives value,
            SourceSpan span) {
        if (kind == Kind.WHOLE || route.isRoot()) {
            throw new IllegalArgumentException(
                    "aggregate capture writes must target a selected route");
        }
        return new CapturedCellWrite(
                sequence, Optional.of(Objects.requireNonNull(captureId, "captureId")),
                OptionalInt.empty(), declarationId, Optional.empty(), kind, route, value, span);
    }

    public static CapturedCellWrite parameter(
            int sequence,
            int parameterIndex,
            DeclarationId declarationId,
            Kind kind,
            ProjectionPath route,
            FormulaAlternatives value,
            SourceSpan span) {
        return new CapturedCellWrite(sequence, Optional.empty(), OptionalInt.of(parameterIndex),
                declarationId, Optional.empty(), kind, route, value, span);
    }

    /** A source-certified external binding retains its original declaration/storage identity. */
    public static CapturedCellWrite declaration(int sequence, DeclarationId declaration,
            Kind kind, ProjectionPath route, FormulaAlternatives value, SourceSpan span, FlowSiteId site) {
        return new CapturedCellWrite(sequence, Optional.empty(), OptionalInt.empty(), declaration,
                Optional.of(declaration), kind, route, value, span, Optional.of(site));
    }

    public boolean isDeclarationWrite() {
        return captureId.isEmpty() && parameterIndex.isEmpty();
    }

    public boolean isCaptureWrite() {
        return captureId.isPresent();
    }

    public boolean isParameterWrite() {
        return parameterIndex.isPresent();
    }

    public boolean isCaptureCellWrite() {
        return captureId.isPresent() && sharedCellId.isPresent();
    }

    public boolean isCaptureAggregateWrite() {
        return captureId.isPresent() && sharedCellId.isEmpty();
    }

    public CaptureId capture() {
        return captureId.orElseThrow(() -> new IllegalStateException("write is not a capture write"));
    }

    public int parameter() {
        return parameterIndex.orElseThrow(() -> new IllegalStateException("write is not a parameter write"));
    }

    public boolean isWhole() {
        return kind == Kind.WHOLE;
    }

    public boolean isUnknownRoute() {
        return kind == Kind.UNKNOWN_ROUTE;
    }

    CapturedCellWrite withSequence(int replacement) {
        return new CapturedCellWrite(
                replacement, captureId, parameterIndex, declarationId, sharedCellId,
                kind, route, value, span, operationSite);
    }

    CapturedCellWrite withValue(FormulaAlternatives replacement) {
        return new CapturedCellWrite(
                sequence, captureId, parameterIndex, declarationId, sharedCellId,
                kind, route, Objects.requireNonNull(replacement, "replacement"), span, operationSite);
    }

    CapturedCellWrite withOperationSite(Optional<FlowSiteId> site) {
        return new CapturedCellWrite(sequence, captureId, parameterIndex, declarationId, sharedCellId,
                kind, route, value, span, site);
    }

    /**
     * Stable identity of the source write operation, independent of the
     * caller-order sequence and the value substituted at that operation.
     * Recursive transfer uses this key to widen repeated applications without
     * conflating distinct source writes that happen to target the same cell.
     */
    String operationKey() {
        String owner = sharedCellId.isPresent()
                ? "cell/" + declarationId + "/" + sharedCellId.orElseThrow()
                : captureId.map(value -> "capture/" + value)
                .orElseGet(() -> "parameter/" + parameterIndex.orElse(-1))
                + "/" + declarationId;
        return owner + "/" + kind + "/" + route + "@"
                + operationSite.map(Object::toString).orElseGet(span::toString);
    }

    static int sequenceAtEvent(
            int eventSequence,
            int eventWriteIndex,
            SummaryLimits limits) {
        Objects.requireNonNull(limits, "limits");
        if (eventSequence < 0 || eventWriteIndex < 0) {
            throw new IllegalArgumentException("write event order must not be negative");
        }
        if (eventWriteIndex >= limits.maxWrites()) {
            throw new SummaryLimits.SummaryDomainException(
                    "writes in one caller event exceed the finite summary domain: "
                            + (eventWriteIndex + 1) + " > " + limits.maxWrites());
        }
        long rebased = (long) eventSequence * limits.maxWrites() + eventWriteIndex;
        if (rebased > Integer.MAX_VALUE) {
            throw new SummaryLimits.SummaryDomainException(
                    "caller write-order sequence exceeds the finite integer domain");
        }
        return (int) rebased;
    }

    public String canonicalKey() {
        return String.format("%08d/%s/%s/%s/%s/%s/%s@%s",
                sequence,
                captureId.map(Object::toString).orElse(isDeclarationWrite()
                        ? "declaration#" + declarationId.ordinal() : "parameter#" + parameterIndex.orElse(-1)),
                declarationId,
                sharedCellId.map(Object::toString).orElse("-"),
                kind,
                route,
                value.canonicalKey(),
                span) + operationSite.map(site -> "/site=" + site).orElse("");
    }

    @Override
    public int compareTo(CapturedCellWrite other) {
        Objects.requireNonNull(other, "other");
        int order = Integer.compare(sequence, other.sequence);
        return order != 0 ? order : canonicalKey().compareTo(other.canonicalKey());
    }

    @Override
    public String toString() {
        return canonicalKey();
    }
}
