package util.exceptions;

import parse.Token;


public class InvalidGrammarException extends CompExcept {
    private final int line;
    private final int chr;

    private InvalidGrammarException(String message, int line, int column) {
        super(message);
        this.line = line;
        this.chr = column;
    }

    public static InvalidGrammarException expected(Token token, String expected) {
        return new InvalidGrammarException(
                String.format("Expected: %s, Found: %s", expected, token.tokenType()),
                token.line(),
                token.chr()
        );
    }

    public int line() { return line; }

    public int column() { return chr; }


}
