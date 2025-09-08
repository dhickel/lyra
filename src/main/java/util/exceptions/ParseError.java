package util.exceptions;

import parse.Token;

public class ParseError extends Error {
    private final int line;
    private final int chr;

    private ParseError(String message, int line, int column) {
        super(message);
        this.line = line;
        this.chr = column;
    }

    public static ParseError of(Token token, String msg) {
        return new ParseError(
                msg,
                token.line(),
                token.chr()
        );
    }

    public static ParseError expected(Token token, String expected) {
        return new ParseError(
                String.format("Expected: %s, Found: %s", expected, token.tokenType()),
                token.line(),
                token.chr()
        );
    }

    public int line() { return line; }

    public int column() { return chr; }
}
