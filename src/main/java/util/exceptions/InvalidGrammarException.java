package util.exceptions;

import lang.grammar.GMatch;
import parse.Token;
import util.Result;



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

    public Result<GMatch, CompExcept> intoResult() {
        return Result.err(this);
    }

    public int line() { return line; }

    public int column() { return chr; }


}
