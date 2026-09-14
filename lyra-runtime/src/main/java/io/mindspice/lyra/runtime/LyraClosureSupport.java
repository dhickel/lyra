package io.mindspice.lyra.runtime;

import java.util.Objects;

/** Narrow generated-code boundary for checking Lyra-owned function values. */
public final class LyraClosureSupport {
    private LyraClosureSupport() {
    }

    /**
     * Rejects arbitrary Java objects/SAMs and accepts only a closure carrying
     * the expected artifact ownership or explicitly linked source-local
     * session authority, and the exact Lyra signature.
     */
    public static LyraClosure requireAuthenticated(Object candidate,
                                                    LyraClosureAuthority expectedOwner,
                                                    LyraSignature expectedSignature) {
        Objects.requireNonNull(expectedOwner, "expectedOwner");
        Objects.requireNonNull(expectedSignature, "expectedSignature");
        expectedOwner.checkOpen();
        if (candidate instanceof LyraNominalMemberDelegate delegate) {
            return delegate.requireRoute(expectedOwner, expectedSignature, false);
        }
        if (!(candidate instanceof LyraClosure closure)) {
            throw new LyraLinkException("function value is not an authenticated Lyra closure");
        }
        if (!expectedOwner.authenticates(closure)) {
            throw new LyraLinkException("function value belongs to an unrelated Lyra artifact or session");
        }
        closure.checkInvocation(expectedSignature);
        return closure;
    }

    /** Authentication boundary used only by generated closure invoke methods. */
    public static LyraClosure requireAuthenticatedForGeneratedInvocation(
            Object candidate,
            LyraClosureAuthority expectedOwner,
            LyraSignature expectedSignature) {
        Objects.requireNonNull(expectedOwner, "expectedOwner");
        Objects.requireNonNull(expectedSignature, "expectedSignature");
        expectedOwner.token().checkGeneratedInvocation();
        if (candidate instanceof LyraNominalMemberDelegate delegate) {
            return delegate.requireRoute(expectedOwner, expectedSignature, true);
        }
        if (!(candidate instanceof LyraClosure closure)) {
            throw new LyraLinkException("function value is not an authenticated Lyra closure");
        }
        if (!expectedOwner.authenticates(closure)) {
            throw new LyraLinkException("function value belongs to an unrelated Lyra artifact or session");
        }
        closure.authority().token().checkGeneratedInvocation();
        if (!closure.signature().equals(expectedSignature)) {
            throw new LyraLinkException("Lyra closure signature does not match the required contract");
        }
        return closure;
    }

    /**
     * Compares the logical identity of two callable values without granting
     * invocation authority or returning either callable. Route delegates use
     * the identity of their one raw selected closure; nil compares only with
     * nil. Lifecycle and route checks remain mandatory at invocation/storage
     * boundaries and are deliberately not performed here.
     */
    public static boolean sameIdentity(Object left, Object right) {
        if (left == null || right == null) return left == right;
        return identityOf(left).equals(identityOf(right));
    }

    private static LyraClosureIdentity identityOf(Object candidate) {
        if (candidate instanceof LyraClosure closure) return closure.identity();
        if (candidate instanceof LyraNominalMemberDelegate delegate) {
            return delegate.selectedIdentity();
        }
        throw new LyraLinkException("function value has no Lyra closure identity");
    }

    public static LyraClosure requireAuthenticated(LyraClosure candidate,
                                                    LyraClosureAuthority expectedOwner,
                                                    LyraSignature expectedSignature) {
        return requireAuthenticated((Object) candidate, expectedOwner, expectedSignature);
    }
}
