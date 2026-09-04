package io.mindspice.lyra.compiler.api;

/** Unchecked failure of a compiler invariant, never a source diagnostic. */
public final class LyraCompilerBugException extends RuntimeException {
    public LyraCompilerBugException(String message) {
        super(message);
    }

    public LyraCompilerBugException(String message, Throwable cause) {
        super(message, cause);
    }
}
