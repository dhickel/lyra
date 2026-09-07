package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.backend.jvm.EmissionMode;
import io.mindspice.lyra.runtime.ArtifactProfile;

/**
 * Opt-in generated-code profile for a compilation request.
 *
 * <p>NORMAL is the unchanged ahead-of-time ABI.  SESSION is reserved for the
 * persistent submission pipeline and ATTACHABLE adds only the narrow
 * owner-thread application hook surface required by an explicitly registered
 * root.</p>
 */
public enum CompileProfile {
    NORMAL(ArtifactProfile.NORMAL),
    SESSION(ArtifactProfile.SESSION),
    ATTACHABLE(ArtifactProfile.ATTACHABLE);

    private final ArtifactProfile artifactProfile;

    CompileProfile(ArtifactProfile artifactProfile) {
        this.artifactProfile = artifactProfile;
    }

    public ArtifactProfile artifactProfile() {
        return artifactProfile;
    }

    public EmissionMode emissionMode() {
        return switch (this) {
            case NORMAL -> EmissionMode.NORMAL;
            case SESSION -> EmissionMode.SESSION;
            case ATTACHABLE -> EmissionMode.ATTACHABLE;
        };
    }

    public boolean isNormal() {
        return this == NORMAL;
    }

    public boolean isSession() {
        return this == SESSION;
    }

    public boolean isAttachable() {
        return this == ATTACHABLE;
    }

    public String canonicalSpelling() {
        return artifactProfile.canonicalSpelling();
    }
}
