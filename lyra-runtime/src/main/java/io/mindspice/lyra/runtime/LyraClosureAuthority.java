package io.mindspice.lyra.runtime;

import java.util.Objects;
import java.util.Optional;

/** Opaque capability used by generated closure classes to authenticate ownership. */
public final class LyraClosureAuthority {
    private final LyraOwnershipToken token;

    LyraClosureAuthority(LyraOwnershipToken token) {
        this.token = Objects.requireNonNull(token, "token");
    }

    public boolean isValid() {
        return token.isValid();
    }

    public Thread ownerThread() {
        return token.ownerThread();
    }

    public Optional<ModuleId> moduleId() {
        return token.moduleId();
    }

    /** Checks owner thread and OPEN state before generated code accesses state. */
    public void checkOpen() {
        token.checkUsable();
    }

    /** Exact metadata resolution scoped to this producer, with owner/lifecycle checks. */
    public LyraSignature resolveSignature(String canonical) { return token.resolveSignature(canonical); }

    public NominalType resolveNominalType(String canonical) { return token.resolveNominalSchema(canonical).type(); }

    public boolean sameArtifact(LyraClosureAuthority other) {
        return other != null && token.sameArtifact(other.token);
    }

    LyraClosureIdentity nextClosureIdentity() {
        return token.nextClosureIdentity();
    }

    void ensureCreationAllowed() {
        token.ensureCreationAllowed();
    }

    boolean authenticates(LyraClosure closure) {
        return closure != null && (token.sameArtifact(closure.authority().token)
                || token.sameSession(closure.authority().token));
    }

    LyraOwnershipToken token() {
        return token;
    }
}
