package io.mindspice.lyra.compiler.artifact;

/** Internal immutable publication options; the public Phase-19 type is not present yet. */
final class ArtifactWriteOptions {
    private final boolean force;

    private ArtifactWriteOptions(boolean force) {
        this.force = force;
    }

    static ArtifactWriteOptions defaults() {
        return new ArtifactWriteOptions(false);
    }

    static Builder builder() {
        return new Builder();
    }

    boolean force() {
        return force;
    }

    static final class Builder {
        private boolean force;

        Builder force(boolean value) {
            force = value;
            return this;
        }

        ArtifactWriteOptions build() {
            return new ArtifactWriteOptions(force);
        }
    }
}
