package Parse;

import java.util.Optional;

public record Token(
        TokenType tokenType,
        TokenData tokenData,
        int line,
        int chr
) { }
