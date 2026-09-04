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
        configuredOwner = Optional.ofNullable(owner);
        this.ioEnvironment = Objects.requireNonNull(ioEnvironment, "ioEnvironment");
    }

    Optional<OwnerThread> configuredOwner() {
        return configuredOwner;
    }

    RuntimeIoEnvironment ioEnvironment() {
        return ioEnvironment;
    }
}
