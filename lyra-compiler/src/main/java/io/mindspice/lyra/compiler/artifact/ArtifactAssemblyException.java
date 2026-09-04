package io.mindspice.lyra.compiler.artifact;

/** Invariant or compatibility failure while assembling an internal artifact. */
final class ArtifactAssemblyException extends IllegalArgumentException {
    ArtifactAssemblyException(String message) {
        super(message);
    }

    ArtifactAssemblyException(String message, Throwable cause) {
        super(message, cause);
    }
}
