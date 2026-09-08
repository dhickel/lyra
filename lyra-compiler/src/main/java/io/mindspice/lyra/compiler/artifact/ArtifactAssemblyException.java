package io.mindspice.lyra.compiler.artifact;

/**
 * Structured packaging/compatibility failure while assembling an artifact.
 *
 * <p>Debug publications use this type for the actionable packaging errors:
 * missing or conflicting production closure inventories, invalid bundled
 * entries, and malformed publication layouts.  Ordinary source diagnostics
 * remain result data; this exception is the packaging boundary.</p>
 */
public final class ArtifactAssemblyException extends IllegalArgumentException {
    ArtifactAssemblyException(String message) {
        super(message);
    }

    ArtifactAssemblyException(String message, Throwable cause) {
        super(message, cause);
    }
}
