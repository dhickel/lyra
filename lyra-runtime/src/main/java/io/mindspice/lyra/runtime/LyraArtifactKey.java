package io.mindspice.lyra.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Opaque identity used to associate all module states loaded from one
 * generated artifact.  Only the runtime can create one.
 */
public final class LyraArtifactKey {
    private final Optional<OwnerThread> configuredOwner;
    private final RuntimeIoEnvironment ioEnvironment;
    private final SessionStorageDomain.Linkage sessionLinkage;
    private final SessionStorageDomain.RootLifetime rootLifetime;
    private final boolean deferredSubmission;
    /** One prepared session graph's actual state shells, never ordinary instances. */
    private final Map<ModuleId, Object> preparedStates = new HashMap<>();
    /** Lifecycles for the same prepared graph, used for exact progress reporting. */
    private final Map<ModuleId, ModuleLifecycle> preparedLifecycles = new HashMap<>();

    LyraArtifactKey() {
        this(null, RuntimeIoEnvironment.defaults());
    }

    LyraArtifactKey(RuntimeIoEnvironment ioEnvironment) {
        this(null, ioEnvironment);
    }

    LyraArtifactKey(OwnerThread owner) {
        this(owner, RuntimeIoEnvironment.defaults());
    }

    LyraArtifactKey(OwnerThread owner, RuntimeIoEnvironment ioEnvironment) {
        this(owner, ioEnvironment, null);
    }

    LyraArtifactKey(OwnerThread owner, RuntimeIoEnvironment ioEnvironment, SessionStorageDomain.Linkage linkage) {
        this(owner, ioEnvironment, linkage, false);
    }

    LyraArtifactKey(OwnerThread owner, RuntimeIoEnvironment ioEnvironment,
                    SessionStorageDomain.Linkage linkage, boolean deferredSubmission) {
        this(owner, ioEnvironment, linkage, deferredSubmission,
                linkage == null ? null : linkage.rootLifetime());
    }

    LyraArtifactKey(OwnerThread owner, RuntimeIoEnvironment ioEnvironment,
                    SessionStorageDomain.Linkage linkage, boolean deferredSubmission,
                    SessionStorageDomain.RootLifetime rootLifetime) {
        if (deferredSubmission && linkage == null) {
            throw new IllegalArgumentException("deferred submission requires authenticated linkage");
        }
        if (linkage != null && linkage.rootLifetime() != rootLifetime) {
            throw new IllegalArgumentException("artifact key root lifetime disagrees with session linkage");
        }
        configuredOwner = Optional.ofNullable(owner);
        this.ioEnvironment = Objects.requireNonNull(ioEnvironment, "ioEnvironment");
        this.sessionLinkage = linkage;
        this.rootLifetime = rootLifetime;
        this.deferredSubmission = deferredSubmission;
    }

    boolean deferredSubmission() {
        return deferredSubmission;
    }

    SessionStorageDomain.Linkage sessionLinkage() {
        return sessionLinkage;
    }

    SessionStorageDomain.RootLifetime rootLifetime() {
        return rootLifetime;
    }

    Optional<OwnerThread> configuredOwner() {
        return configuredOwner;
    }

    RuntimeIoEnvironment ioEnvironment() {
        return ioEnvironment;
    }

    /** Called by generated state constructors during prepared-graph allocation. */
    public void registerModuleLifecycle(ModuleId moduleId, ModuleLifecycle lifecycle) {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(lifecycle, "lifecycle");
        if (!deferredSubmission) return;
        configuredOwner.ifPresent(OwnerThread::check);
        ModuleLifecycle previous = preparedLifecycles.putIfAbsent(moduleId, lifecycle);
        if (previous != null && previous != lifecycle) {
            throw new LyraLinkException("prepared graph allocated a module lifecycle twice");
        }
    }

    /** Returns initializer progress for this exact prepared graph. */
    SessionInitializationProgress initializationProgress() {
        if (!deferredSubmission) return SessionInitializationProgress.empty();
        configuredOwner.ifPresent(OwnerThread::check);
        Map<ModuleId, SessionInitializationProgress.ModuleProgress> progress = new HashMap<>();
        preparedLifecycles.forEach((module, lifecycle) -> progress.put(module,
                new SessionInitializationProgress.ModuleProgress(
                        lifecycle.attemptedSessionBindings(),
                        lifecycle.initializedSessionBindings())));
        return new SessionInitializationProgress(progress);
    }

    /** Called by generated state constructors during prepared-graph allocation. */
    public void registerModuleState(ModuleId moduleId, Object state) {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(state, "state");
        if (!deferredSubmission && rootLifetime == null) return;
        configuredOwner.ifPresent(OwnerThread::check);
        Object previous = preparedStates.putIfAbsent(moduleId, state);
        if (previous != null && previous != state) {
            throw new LyraLinkException("prepared graph allocated a module state twice");
        }
    }

    /** Returns the exact state shell from this prepared graph or attachable root. */
    public Object moduleState(ModuleId moduleId) {
        Objects.requireNonNull(moduleId, "moduleId");
        if (!deferredSubmission && rootLifetime == null) {
            throw new LyraLinkException("ordinary artifact key has no prepared module graph");
        }
        configuredOwner.ifPresent(OwnerThread::check);
        Object state = preparedStates.get(moduleId);
        if (state == null) {
            throw new LyraLinkException("prepared graph has no module state: " + moduleId);
        }
        return state;
    }

    void clearPreparedStates() {
        preparedStates.clear();
        preparedLifecycles.clear();
    }
}
