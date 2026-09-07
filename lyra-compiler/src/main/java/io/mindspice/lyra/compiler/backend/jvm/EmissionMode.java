package io.mindspice.lyra.compiler.backend.jvm;

import io.mindspice.lyra.runtime.ArtifactProfile;

/** Exact generated-bytecode mode selected before emission. */
public enum EmissionMode {
    NORMAL(ArtifactProfile.NORMAL),
    SESSION(ArtifactProfile.SESSION),
    ATTACHABLE(ArtifactProfile.ATTACHABLE);

    private final ArtifactProfile artifactProfile;

    EmissionMode(ArtifactProfile artifactProfile) {
        this.artifactProfile = artifactProfile;
    }

    public ArtifactProfile artifactProfile() {
        return artifactProfile;
    }

    public boolean attachable() {
        return this == ATTACHABLE;
    }

    public boolean session() {
        return this == SESSION;
    }
}
