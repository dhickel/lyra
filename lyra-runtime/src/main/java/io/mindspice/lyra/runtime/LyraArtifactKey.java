package io.mindspice.lyra.runtime;

import java.util.Objects;
import java.util.Optional;

/**
 * Opaque identity used to associate all module states loaded from one
 * generated artifact.  Only the runtime can create one.
 */
public final class LyraArtifactKey {
    private final Optional<OwnerThread> configuredOwner;

    LyraArtifactKey() {
        configuredOwner = Optional.empty();
    }

    LyraArtifactKey(OwnerThread owner) {
        configuredOwner = Optional.of(Objects.requireNonNull(owner, "owner"));
    }

    Optional<OwnerThread> configuredOwner() {
        return configuredOwner;
    }
}
