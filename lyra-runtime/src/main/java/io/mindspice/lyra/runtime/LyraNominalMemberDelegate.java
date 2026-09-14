package io.mindspice.lyra.runtime;

import java.util.Objects;

/**
 * Base for generated occurrence-scoped nominal member delegates. A generated
 * subclass implements the exact structural function interface of one callable
 * nominal member and accepts only an opaque route issued by an authenticated
 * generated member boundary.
 *
 * <p>The delegate retains one raw selected closure plus a flat immutable,
 * identity-deduplicated list of exact field-route dependencies. It never
 * re-reads a field, and never remains usable after any source object, selected
 * producer, session epoch, or root lifetime retires.</p>
 */
public abstract class LyraNominalMemberDelegate {
    private final LyraNominalObject.CallableMemberRoute route;

    /** Generated delegate constructors pass opaque runtime-issued evidence. */
    protected LyraNominalMemberDelegate(Object routeEvidence) {
        route = LyraNominalObject.claimCallableMemberRoute(routeEvidence);
    }

    /** Exact raw value selected by the route; never re-read from any slot. */
    protected final Object target() {
        return route.target();
    }

    /** Generated delegate invoke methods preserve direct Java invocation checks. */
    protected final void checkRouteInvocation() {
        route.checkUsable();
    }

    /** Route-scoped authentication for generated and Java-facing call boundaries. */
    final LyraClosure requireRoute(LyraClosureAuthority caller, LyraSignature expectedSignature,
                                   boolean generated) {
        return requireSelection(caller, expectedSignature, generated).target();
    }

    /** Internal normalization used when another exact member route selects this delegate. */
    final LyraNominalObject.CallableSelection requireSelection(
            LyraClosureAuthority caller, LyraSignature expectedSignature, boolean generated) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(expectedSignature, "expectedSignature");
        return route.requireSelection(caller, expectedSignature, generated);
    }

    /** Bounded snapshot/description checks never invoke user code. */
    public final void checkUsable(LyraSignature expectedSignature) {
        Objects.requireNonNull(expectedSignature, "expectedSignature");
        route.checkUsable(expectedSignature);
    }

    /** Logical identity inspection is deliberately non-authorizing. */
    final LyraClosureIdentity selectedIdentity() {
        return route.target().identity();
    }

    /** Package-private test seam proving route normalization remains bounded. */
    final int routeDependencyCount() {
        return route.dependencies().size();
    }
}
