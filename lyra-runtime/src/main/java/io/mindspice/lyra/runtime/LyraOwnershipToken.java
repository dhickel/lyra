package io.mindspice.lyra.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opaque runtime-owned authority for one artifact/module instance.  There is
 * no public constructor or factory; generated closure subclasses receive the
 * authority only from their owning module lifecycle.
 */
final class LyraOwnershipToken {
    private final ModuleLifecycle lifecycle;
    private final OwnerThread owner;
    private final Optional<ModuleId> moduleId;
    private final Object artifactKey;
    private final SessionStorageDomain.RootLifetime rootLifetime;
    private final AtomicBoolean valid = new AtomicBoolean(true);
    private final AtomicLong closureOrdinals = new AtomicLong();

    LyraOwnershipToken(ModuleLifecycle lifecycle, OwnerThread owner,
                       Optional<ModuleId> moduleId, Object artifactKey) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId");
        this.artifactKey = Objects.requireNonNull(artifactKey, "artifactKey");
        this.rootLifetime = artifactKey instanceof LyraArtifactKey key
                ? key.rootLifetime() : null;
    }

    boolean isValid() {
        owner.check();
        SessionStorageDomain.Linkage linkage = sessionLinkage();
        return valid.get() && (linkage == null || linkage.isActive())
                && (rootLifetime == null || !rootLifetime.isClosed());
    }

    Thread ownerThread() {
        return owner.thread();
    }

    Optional<ModuleId> moduleId() {
        return moduleId;
    }

    void invalidate() {
        valid.set(false);
    }

    LyraClosureIdentity nextClosureIdentity() {
        ensureCreationAllowed();
        return new LyraClosureIdentity(this, closureOrdinals.getAndIncrement());
    }

    void checkUsable() {
        checkUsable(false);
    }

    void checkGeneratedInvocation() {
        checkUsable(true);
    }

    void applicationSafePoint() {
        owner.check();
        lifecycle.applicationSafePoint();
    }

    void checkIoAccess() {
        owner.check();
        LifecycleState state = lifecycle.rawState();
        if (state == LifecycleState.FAILED) {
            throw new LyraInitializationException("module initialization failed", java.util.List.of(), lifecycle.failureCauseUnchecked());
        }
        if (state == LifecycleState.CLOSED || !valid.get()) {
            throw new LyraClosedException("module-owned I/O authority is closed or invalidated");
        }
        checkSession();
        if (state != LifecycleState.OPEN && state != LifecycleState.INITIALIZING) {
            throw new LyraLifecycleException("module-owned I/O authority is not open");
        }
    }

    RuntimeIoEnvironment ioEnvironment() {
        return lifecycle.ioEnvironment();
    }

    private void checkUsable(boolean allowInitializing) {
        owner.check();
        LifecycleState state = lifecycle.rawState();
        if (state == LifecycleState.FAILED) {
            throw new LyraInitializationException("module initialization failed", java.util.List.of(), lifecycle.failureCauseUnchecked());
        }
        if (state == LifecycleState.CLOSED || !valid.get()) {
            throw new LyraClosedException("module-owned closure is closed or invalidated");
        }
        checkSession();
        if (state != LifecycleState.OPEN
                && !(allowInitializing && state == LifecycleState.INITIALIZING)) {
            throw new LyraLifecycleException("module-owned closure is not open");
        }
    }

    void ensureCreationAllowed() {
        owner.check();
        LifecycleState state = lifecycle.rawState();
        if (state == LifecycleState.FAILED) {
            throw new LyraInitializationException("module initialization failed", java.util.List.of(), lifecycle.failureCauseUnchecked());
        }
        if (state == LifecycleState.CLOSED || !valid.get()) {
            throw new LyraClosedException("module-owned closure is closed or invalidated");
        }
        checkSession();
    }

    LyraSignature resolveSignature(String canonical) {
        ensureCreationAllowed();
        if (artifactKey instanceof LyraArtifactKey key) return key.resolveSignature(canonical);
        try {
            return LyraSignature.parse(canonical);
        } catch (IllegalArgumentException failure) {
            throw new LyraLinkException("signature has no producer schema environment", java.util.List.of(), failure);
        }
    }

    boolean sameArtifact(LyraOwnershipToken other) {
        return other != null && artifactKey == other.artifactKey;
    }

    /**
     * Only runtime-loaded source-local submissions in the same active trusted
     * domain may interchange closures.  A producer must still pass its exact
     * lifecycle checks; ordinary artifact keys and foreign SAM values never
     * enter this optional bridge.
     */
    boolean sameSession(LyraOwnershipToken other) {
        if (other == null) return false;
        SessionStorageDomain.Linkage linkage = sessionLinkage();
        SessionStorageDomain.Linkage otherLinkage = other.sessionLinkage();
        // One explicitly registered root domain may interchange closures
        // between the attachable root itself and session generations pinned
        // to that root's lifetime.  Two independent roots never share the
        // bridge because their root-lifetime objects are distinct.
        SessionStorageDomain.RootLifetime mine = rootLifetime();
        SessionStorageDomain.RootLifetime theirs = other.rootLifetime();
        if (mine != null && mine == theirs) {
            if (linkage == null && otherLinkage == null) return false;
            other.checkUsable();
            return true;
        }
        if (linkage == null || !linkage.authenticates(otherLinkage)) return false;
        other.checkUsable();
        return true;
    }

    private SessionStorageDomain.RootLifetime rootLifetime() {
        if (rootLifetime != null) return rootLifetime;
        return artifactKey instanceof LyraArtifactKey key ? key.rootLifetime() : null;
    }

    private SessionStorageDomain.Linkage sessionLinkage() {
        return artifactKey instanceof LyraArtifactKey key ? key.sessionLinkage() : null;
    }

    private void checkSession() {
        SessionStorageDomain.Linkage linkage = sessionLinkage();
        if (linkage != null) {
            linkage.checkOpen();
        } else if (rootLifetime != null && rootLifetime.isClosed()) {
            throw new LyraClosedException("root lifetime is closed");
        }
    }

    boolean sameModule(LyraOwnershipToken other) {
        return other != null && this == other;
    }

    ModuleLifecycle lifecycle() {
        return lifecycle;
    }

}
