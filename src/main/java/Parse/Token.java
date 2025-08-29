package Parse;

import java.util.Optional;

public record Token(
        TokenType tokenType,
        Optional<TokenData> tokenData,
        int line,
        int chr
) { }
