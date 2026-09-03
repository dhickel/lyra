package io.mindspice.lyra.runtime;

import java.util.Objects;

/** Narrow generated-code boundary for checking Lyra-owned function values. */
public final class LyraClosureSupport {
    private LyraClosureSupport() {
    }

    /**
     * Rejects arbitrary Java objects/SAMs and accepts only a closure carrying
     * the expected artifact ownership and exact Lyra signature.
     */
    public static LyraClosure requireAuthenticated(Object candidate,
                                                    LyraClosureAuthority expectedOwner,
                                                    LyraSignature expectedSignature) {
        Objects.requireNonNull(expectedOwner, "expectedOwner");
        Objects.requireNonNull(expectedSignature, "expectedSignature");
        expectedOwner.checkOpen();
        if (!(candidate instanceof LyraClosure closure)) {
            throw new LyraLinkException("function value is not an authenticated Lyra closure");
        }
        if (!expectedOwner.authenticates(closure)) {
            throw new LyraLinkException("function value belongs to a different Lyra artifact");
        }
        closure.checkInvocation(expectedSignature);
        return closure;
    }

    public static LyraClosure requireAuthenticated(LyraClosure candidate,
                                                    LyraClosureAuthority expectedOwner,
                                                    LyraSignature expectedSignature) {
        return requireAuthenticated((Object) candidate, expectedOwner, expectedSignature);
    }
}
