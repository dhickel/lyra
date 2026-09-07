package io.mindspice.lyra.runtime;

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
}
