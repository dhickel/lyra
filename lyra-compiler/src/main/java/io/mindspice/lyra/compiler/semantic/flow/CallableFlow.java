package io.mindspice.lyra.compiler.semantic.flow;

import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.identity.LambdaId;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One callable value alternative at a routed aggregate position.
 *
 * <p>The target identifies either the source lambda transfer to apply or one
 * explicitly modeled intrinsic summary. Immutable captures retain their
 * creation-time values. Shared mutable captures instead retain a stable cell
 * identity plus the cell's creation-time snapshot; invocation may replace that
 * snapshot from the caller's immutable {@link BindingFlowState}. Keeping both
 * forms on the value alternative means callable selection, replacement, and
 * joins use the same flow algebra as aggregate ownership.</p>
 */
public record CallableFlow(
        Optional<LambdaId> lambdaId,
        Optional<DeclarationId> intrinsicDeclarationId,
        ProjectionPath route,
        Map<DeclarationId, ValueAlternatives> capturedValues,
        Map<DeclarationId, ValueAlternatives> sharedCellSnapshots,
        Optional<FlowSiteId> creationSite,
        Optional<FlowSiteId> retainedCellContext)
        implements Comparable<CallableFlow> {
    public CallableFlow {
        Objects.requireNonNull(lambdaId, "lambdaId");
        Objects.requireNonNull(intrinsicDeclarationId, "intrinsicDeclarationId");
        if (lambdaId.isPresent() == intrinsicDeclarationId.isPresent()) {
            throw new IllegalArgumentException(
                    "a callable flow must identify exactly one lambda or intrinsic");
        }
        Objects.requireNonNull(route, "route");
        capturedValues = immutableSnapshots(capturedValues, "captured");
        sharedCellSnapshots = immutableSnapshots(sharedCellSnapshots, "shared cell");
        Objects.requireNonNull(creationSite, "creationSite");
        Objects.requireNonNull(retainedCellContext, "retainedCellContext");
        if (intrinsicDeclarationId.isPresent()
                && (!capturedValues.isEmpty() || !sharedCellSnapshots.isEmpty())) {
            throw new IllegalArgumentException("an intrinsic callable cannot capture values");
        }
        for (DeclarationId cell : sharedCellSnapshots.keySet()) {
            if (capturedValues.containsKey(cell)) {
                throw new IllegalArgumentException(
                        "a capture cannot be both an immutable value and shared cell: " + cell);
            }
        }
    }

    /** Compatibility constructor for facts without a retained cell scope. */
    public CallableFlow(
            Optional<LambdaId> lambdaId,
            Optional<DeclarationId> intrinsicDeclarationId,
            ProjectionPath route,
            Map<DeclarationId, ValueAlternatives> capturedValues,
            Map<DeclarationId, ValueAlternatives> sharedCellSnapshots,
            Optional<FlowSiteId> creationSite) {
        this(lambdaId, intrinsicDeclarationId, route, capturedValues,
                sharedCellSnapshots, creationSite, Optional.empty());
    }

    /** Compatibility constructor for facts without an exact creation site. */
    public CallableFlow(
            Optional<LambdaId> lambdaId,
            Optional<DeclarationId> intrinsicDeclarationId,
            ProjectionPath route,
            Map<DeclarationId, ValueAlternatives> capturedValues,
            Map<DeclarationId, ValueAlternatives> sharedCellSnapshots) {
        this(lambdaId, intrinsicDeclarationId, route, capturedValues,
                sharedCellSnapshots, Optional.empty(), Optional.empty());
    }

    public CallableFlow(
            LambdaId lambdaId,
            ProjectionPath route,
            Map<DeclarationId, ValueAlternatives> capturedValues) {
        this(Optional.of(Objects.requireNonNull(lambdaId, "lambdaId")),
                Optional.empty(), route, capturedValues, Map.of(), Optional.empty(), Optional.empty());
    }

    public CallableFlow(
            LambdaId lambdaId,
            ProjectionPath route,
            Map<DeclarationId, ValueAlternatives> capturedValues,
            Map<DeclarationId, ValueAlternatives> sharedCellSnapshots) {
        this(Optional.of(Objects.requireNonNull(lambdaId, "lambdaId")),
                Optional.empty(), route, capturedValues, sharedCellSnapshots,
                Optional.empty(), Optional.empty());
    }

    public static CallableFlow atRoot(
            LambdaId lambdaId, Map<DeclarationId, ValueAlternatives> capturedValues) {
        return new CallableFlow(lambdaId, ProjectionPath.root(), capturedValues);
    }

    public static CallableFlow atRoot(
            LambdaId lambdaId,
            Map<DeclarationId, ValueAlternatives> capturedValues,
            Map<DeclarationId, ValueAlternatives> sharedCellSnapshots) {
        return new CallableFlow(
                lambdaId, ProjectionPath.root(), capturedValues, sharedCellSnapshots);
    }

    public static CallableFlow atRoot(
            LambdaId lambdaId,
            Map<DeclarationId, ValueAlternatives> capturedValues,
            Map<DeclarationId, ValueAlternatives> sharedCellSnapshots,
            FlowSiteId creationSite) {
        return new CallableFlow(
                Optional.of(Objects.requireNonNull(lambdaId, "lambdaId")),
                Optional.empty(), ProjectionPath.root(), capturedValues,
                sharedCellSnapshots,
                Optional.of(Objects.requireNonNull(creationSite, "creationSite")), Optional.empty());
    }

    public static CallableFlow intrinsicAtRoot(DeclarationId declarationId) {
        return new CallableFlow(
                Optional.empty(),
                Optional.of(Objects.requireNonNull(declarationId, "declarationId")),
                ProjectionPath.root(),
                Map.of(),
                Map.of(),
                Optional.empty(), Optional.empty());
    }

    public Optional<LambdaId> lambda() {
        return lambdaId;
    }

    public boolean isIntrinsic() {
        return intrinsicDeclarationId.isPresent();
    }

    public CallableFlow withRoute(ProjectionPath replacement) {
        return new CallableFlow(
                lambdaId, intrinsicDeclarationId, replacement,
                capturedValues, sharedCellSnapshots, creationSite, retainedCellContext);
    }

    public CallableFlow prefixedBy(ProjectionPath prefix) {
        return withRoute(Objects.requireNonNull(prefix, "prefix").compose(route));
    }

    /** Marks producer-local shared cells as scoped to one retained construction/invocation. */
    public CallableFlow withRetainedCellContext(FlowSiteId context) {
        if (sharedCellSnapshots.isEmpty()) return this;
        return new CallableFlow(lambdaId, intrinsicDeclarationId, route,
                capturedValues, sharedCellSnapshots, creationSite,
                Optional.of(Objects.requireNonNull(context, "context")));
    }

    /** Replaces snapshots only for stable cells already captured by this callable. */
    public CallableFlow withSharedCellSnapshots(
            Map<DeclarationId, ValueAlternatives> currentCells) {
        Objects.requireNonNull(currentCells, "currentCells");
        if (sharedCellSnapshots.isEmpty()) {
            return this;
        }
        TreeMap<DeclarationId, ValueAlternatives> refreshed =
                new TreeMap<>(sharedCellSnapshots);
        refreshed.replaceAll((cell, snapshot) ->
                currentCells.getOrDefault(cell, snapshot));
        return new CallableFlow(
                lambdaId, intrinsicDeclarationId, route,
                capturedValues, refreshed, creationSite, retainedCellContext);
    }

    public String canonicalKey() {
        String identity = lambdaId
                .map(value -> "lambda:" + value)
                .orElseGet(() -> "intrinsic:" + intrinsicDeclarationId.orElseThrow());
        StringBuilder result = new StringBuilder(identity).append('@').append(route);
        for (Map.Entry<DeclarationId, ValueAlternatives> entry : capturedValues.entrySet()) {
            result.append("/capture:").append(entry.getKey()).append('=').append(entry.getValue());
        }
        for (Map.Entry<DeclarationId, ValueAlternatives> entry : sharedCellSnapshots.entrySet()) {
            result.append("/cell:").append(entry.getKey()).append('=').append(entry.getValue());
        }
        creationSite.ifPresent(value -> result.append("/site:").append(value));
        retainedCellContext.ifPresent(value -> result.append("/retained-cell-context:").append(value));
        return result.toString();
    }

    private static Map<DeclarationId, ValueAlternatives> immutableSnapshots(
            Map<DeclarationId, ValueAlternatives> snapshots,
            String description) {
        Objects.requireNonNull(snapshots, description + " snapshots");
        TreeMap<DeclarationId, ValueAlternatives> ordered = new TreeMap<>();
        snapshots.forEach((declaration, values) -> ordered.put(
                Objects.requireNonNull(declaration, description + " declaration"),
                Objects.requireNonNull(values, description + " values")));
        return Collections.unmodifiableMap(ordered);
    }

    @Override
    public int compareTo(CallableFlow other) {
        return canonicalKey().compareTo(
                Objects.requireNonNull(other, "other").canonicalKey());
    }
}
