package parse;

public record Token(
        TokenType tokenType,
        TokenData tokenData,
        int line,
        int chr
) { }
