package io.mindspice.lyra.compiler.ir;

import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.identity.CaptureId;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.ExportId;
import io.mindspice.lyra.compiler.identity.FlowSiteId;
import io.mindspice.lyra.compiler.identity.GenerationId;
import io.mindspice.lyra.compiler.identity.ProducerId;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.types.LyraSignature;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Producer-issued proof that one callable target has an authenticated storage
 * route.  This is deliberately a data-only IR fact: the emitter may use it to
 * remove a redundant invocation authentication, but it cannot manufacture or
 * extend the authority represented by the route.
 *
 * <p>The route kind names the complete entry/write boundary which the semantic
 * phase established.  {@link IrValidator} independently recomputes the
 * expected proof from the resolved graph before this fact can reach a backend;
 * declaration identity, JVM shape, spelling, and callable identity alone are
 * never sufficient.</p>
 */
public record CallableStorageRouteProof(
        RouteKind route,
        DeclarationId declarationId,
        Optional<CaptureId> captureId,
        Optional<ModuleId> moduleId,
        Optional<ExportId> exportId,
        Optional<ProducerId> producerId,
        Optional<GenerationId> generationId,
        OptionalInt memberIndex,
        Optional<FlowSiteId> receiverSite,
        LyraSignature signature,
        FlowSiteId targetSite) implements ImmutablePhaseArtifact {
    public CallableStorageRouteProof {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(declarationId, "declarationId");
        Objects.requireNonNull(captureId, "captureId");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(exportId, "exportId");
        Objects.requireNonNull(producerId, "producerId");
        Objects.requireNonNull(generationId, "generationId");
        Objects.requireNonNull(memberIndex, "memberIndex");
        Objects.requireNonNull(receiverSite, "receiverSite");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(targetSite, "targetSite");
        if (moduleId.isPresent() != exportId.isPresent()) {
            throw new IllegalArgumentException("module and export route metadata must be paired");
        }
        if (producerId.isPresent() != generationId.isPresent()) {
            throw new IllegalArgumentException("session producer and generation metadata must be paired");
        }
        boolean nominal = route == RouteKind.NOMINAL_MEMBER_GETTER;
        if (nominal != memberIndex.isPresent() || nominal != receiverSite.isPresent()) {
            throw new IllegalArgumentException(
                    "nominal member route metadata disagrees with its route kind");
        }
        boolean capture = route == RouteKind.CAPTURE_VALUE || route == RouteKind.SHARED_CELL;
        if (capture != captureId.isPresent()) {
            throw new IllegalArgumentException(
                    "capture route metadata disagrees with its route kind");
        }
        if (route == RouteKind.IMPORTED_STATE && moduleId.isEmpty()) {
            throw new IllegalArgumentException(
                    "imported storage routes need exact module/export metadata");
        }
        if ((route == RouteKind.SESSION_LINK) != producerId.isPresent()) {
            throw new IllegalArgumentException(
                    "session producer/generation metadata must occur exactly on session routes");
        }
        if (nominal && (moduleId.isPresent() || captureId.isPresent()
                || producerId.isPresent())) {
            throw new IllegalArgumentException(
                    "nominal member routes cannot carry import, capture, or session metadata");
        }
        if ((route == RouteKind.LOCAL_BINDING || route == RouteKind.PARAMETER_ENTRY
                || route == RouteKind.EXTERNAL_BINDING)
                && (moduleId.isPresent() || captureId.isPresent()
                || producerId.isPresent())) {
            throw new IllegalArgumentException(
                    "local, parameter, and external-binding routes cannot carry foreign route metadata");
        }
        if (capture && (moduleId.isPresent() || producerId.isPresent())) {
            throw new IllegalArgumentException(
                    "capture routes are exact closure-storage routes, not import/session routes");
        }
    }

    public enum RouteKind {
        /** A local declaration whose initializer/write boundary authenticates it. */
        LOCAL_BINDING,
        /** A function parameter authenticated at the generated callable entry. */
        PARAMETER_ENTRY,
        /** An immutable callable captured after its producer route was authenticated. */
        CAPTURE_VALUE,
        /** A callable captured through a compiler-owned shared mutable cell. */
        SHARED_CELL,
        /** A module state/export route with an exact resolved declaration/export link. */
        IMPORTED_STATE,
        /** A retained imported-module accessor with an exact producer capability. */
        SESSION_LINK,
        /** A compiler-certified external binding backed by an exact live session accessor. */
        EXTERNAL_BINDING,
        /** A compiler-owned intrinsic runtime entry point. */
        INTRINSIC,
        /** A nominal getter that authenticates the exact receiver/member route. */
        NOMINAL_MEMBER_GETTER
    }
}
