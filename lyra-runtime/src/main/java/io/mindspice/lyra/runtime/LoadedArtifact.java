package io.mindspice.lyra.runtime;

/** A verified, loaded artifact and its explicitly owned module instances. */
public interface LoadedArtifact extends AutoCloseable {
    /** Metadata read and validated before any generated class was defined. */
    ArtifactMetadata metadata();

    /** Instantiates the requested module facade on the calling owner thread. */
    ModuleHandle instantiate(ModuleId rootModule);

    /** Instantiates the artifact root module. */
    default ModuleHandle instantiate() {
        return instantiate(metadata().rootModuleId());
    }

    /** Returns whether this loading context has been closed. */
    default boolean isClosed() {
        return false;
    }

    /** Closes the context after all instances have been closed. */
    @Override
    void close();
}
