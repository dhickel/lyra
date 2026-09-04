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
    private final AtomicBoolean valid = new AtomicBoolean(true);
    private final AtomicLong closureOrdinals = new AtomicLong();

    LyraOwnershipToken(ModuleLifecycle lifecycle, OwnerThread owner,
                       Optional<ModuleId> moduleId, Object artifactKey) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId");
        this.artifactKey = Objects.requireNonNull(artifactKey, "artifactKey");
    }

    boolean isValid() {
        owner.check();
        return valid.get();
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

    void checkIoAccess() {
        owner.check();
        LifecycleState state = lifecycle.rawState();
        if (state == LifecycleState.FAILED) {
            throw new LyraInitializationException("module initialization failed", java.util.List.of(), lifecycle.failureCauseUnchecked());
        }
        if (state == LifecycleState.CLOSED || !valid.get()) {
            throw new LyraClosedException("module-owned I/O authority is closed or invalidated");
        }
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
    }

    boolean sameArtifact(LyraOwnershipToken other) {
        return other != null && artifactKey == other.artifactKey;
    }

    boolean sameModule(LyraOwnershipToken other) {
        return other != null && this == other;
    }

    ModuleLifecycle lifecycle() {
        return lifecycle;
    }

}
