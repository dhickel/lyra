package io.mindspice.lyra.compiler.parse;

/** Unchecked compiler invariant failure during grammar-descriptor replay. */
public final class ParserInvariantException extends IllegalStateException {
    public ParserInvariantException(String message) {
        super(message);
    }

    public ParserInvariantException(String message, Throwable cause) {
        super(message, cause);
    }
}
