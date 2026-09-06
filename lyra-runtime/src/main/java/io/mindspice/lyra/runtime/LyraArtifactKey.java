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
        if (deferredSubmission && linkage == null) {
            throw new IllegalArgumentException("deferred submission requires authenticated linkage");
        }
        configuredOwner = Optional.ofNullable(owner);
        this.ioEnvironment = Objects.requireNonNull(ioEnvironment, "ioEnvironment");
        this.sessionLinkage = linkage;
        this.deferredSubmission = deferredSubmission;
    }

    boolean deferredSubmission() {
        return deferredSubmission;
    }

    SessionStorageDomain.Linkage sessionLinkage() {
        return sessionLinkage;
    }

    Optional<OwnerThread> configuredOwner() {
        return configuredOwner;
    }

    RuntimeIoEnvironment ioEnvironment() {
        return ioEnvironment;
    }
}
