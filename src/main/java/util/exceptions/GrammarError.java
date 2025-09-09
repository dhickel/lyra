package util.exceptions;

import lang.grammar.GMatch;
import parse.Token;
import util.Result;



public class GrammarError extends CError {
    private final int line;
    private final int chr;

    private GrammarError(String message, int line, int column) {
        super(message);
        this.line = line;
        this.chr = column;
    }

    public static GrammarError expected(Token token, String expected) {
        return new GrammarError(
                String.format("Expected: %s, Found: %s", expected, token.tokenType()),
                token.line(),
                token.chr()
        );
    }

    public Result<GMatch, CError> intoResult() {
        return Result.err(this);
    }

    public int line() { return line; }

    public int column() { return chr; }


}
