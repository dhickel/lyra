package io.mindspice.lyra.runtime;

import java.util.Objects;

/**
 * Non-SAM base contract for generated Lyra closures.  Typed generated
 * subclasses provide their exact invocation methods; this class only carries
 * authenticated identity, signature, and lifecycle checks.
 */
public abstract class LyraClosure {
    private final LyraClosureAuthority authority;
    private final LyraClosureIdentity identity;
    private final LyraSignature signature;

    protected LyraClosure(LyraClosureAuthority authority, LyraSignature signature) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.signature = Objects.requireNonNull(signature, "signature");
        authority.ensureCreationAllowed();
        this.identity = authority.nextClosureIdentity();
    }

    public final LyraClosureIdentity identity() {
        return identity;
    }

    public final LyraSignature signature() {
        return signature;
    }

    public final boolean isValid() {
        return authority.isValid();
    }

    /** Checks the owning thread and current module state. */
    public final void checkInvocation() {
        authority.checkOpen();
    }

    /**
     * Generated invoke methods use this boundary while an eager initializer is
     * allowed to call an already-linked function slot. Unpublished closures
     * cannot escape the factory during INITIALIZING.
     */
    protected final void checkInvocationFromGeneratedCode() {
        authority.token().checkGeneratedInvocation();
    }

    /** Checks ownership/lifecycle and exact canonical signature parity. */
    public final void checkInvocation(LyraSignature expectedSignature) {
        Objects.requireNonNull(expectedSignature, "expectedSignature");
        authority.checkOpen();
        if (!signature.equals(expectedSignature)) {
            throw new LyraLinkException("Lyra closure signature does not match the required contract");
        }
    }

    final LyraClosureAuthority authority() {
        return authority;
    }
}
