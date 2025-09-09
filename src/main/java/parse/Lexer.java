package parse;

import util.Result;
import util.exceptions.CError;
import util.exceptions.InternalError;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class Lexer {
    private static final char EOF = '\0';

    private static final TokenType.SingleToken[] SINGLE_TOKENS = TokenType.getSingleTokens();
    private static final TokenType.DoubleToken[] DOUBLE_TOKENS = TokenType.getDoubleTokens();
    private static final TokenType.KeyWordToken[] KEYWORD_TOKENS = TokenType.getKeyWordTokens();
    private static final TokenType.ModifierToken[] MODIFIER_TOKENS = TokenType.getModifierTokens();


    public static Result<List<Token>, CError> process(String input) {
        var state = new State(input);

        while (state.hasNext()) {
            char c = state.advance();

            if (isSkippable(c)) { continue; }

            if (isNewLine(c)) {
                state.incLineNum();
                continue;
            }

            if (lexDoubleToken(state)) { continue; }
            if (lexSingleTokens(state)) { continue; }
            if (isNumeric(c) && lexNumber(state)) { continue; }
            if (lexWord(state)) { continue; }

            return Result.err(InternalError.of("Fatal error during lexing process"));
        }

        state.addToken(TokenType.Internal.EOF);
        return Result.ok(state.tokens);
    }

    private static boolean lexSingleTokens(State state) {
        for (TokenType.SingleToken t : SINGLE_TOKENS) {
            if (t.chr() == state.currChar) {
                return switch (t.tokenType()) {
                    case TokenType.Syntactic s when s == TokenType.Syntactic.DoubleQuote -> lexStringLiteral(state);
                    case TokenType.Syntactic s when s == TokenType.Syntactic.Ampersand -> lexModifier(state);
                    case TokenType.Operation o when o == TokenType.Operation.Minus && isNumeric(state.peek()) ->
                            lexNumber(state);
                    default -> state.addToken(t.tokenType());
                };
            }
        }
        return false;
    }

    private static boolean lexDoubleToken(State state) {
        if (!state.hasNext()) { return false; } //  Check that there is still another token
        char c1 = state.currChar;
        char c2 = state.peek();

        for (TokenType.DoubleToken t : DOUBLE_TOKENS) {
            if (t.matches(c1, c2)) {
                state.advance(); // Match found so fast-forward past 2nd token
                state.addToken(t.tokenType());
                return true;
            }
        }
        return false;
    }


    private static boolean lexStringLiteral(State state) {
        return false; // TODO implement
    }

    private static boolean lexModifier(State state) {
        return false; // TODO implement
    }

    private static boolean lexNumber(State state) {
        StringBuilder numString = new StringBuilder();

        // Handle negative sign
        if (state.currChar == '-') {
            numString.append('-');
            if (!state.hasNext() || !isNumeric(state.peek()) && state.peek() != '.') {
                return false; // Not a number, let it be handled as minus operator
            }
            state.advance();
        }

        // Handle leading decimal point
        boolean isFloat = false;
        if (state.currChar == '.') {
            isFloat = true;
            numString.append('.');
            if (!state.hasNext() || !isNumeric(state.peek())) {
                return false; // A lone '.' is not a valid number
            }
            state.advance();
        }

        // At this point, we must have at least one digit
        if (!isNumeric(state.currChar)) {
            return false;
        }

        // Append the current digit
        numString.append(state.currChar);

        // Continue collecting digits and at most one decimal point
        while (state.hasNext()) {
            char peek = state.peek();
            if (isNumeric(peek)) {
                numString.append(state.advance());
            } else if (peek == '.' && !isFloat) {
                isFloat = true;
                numString.append(state.advance());
                // After a decimal point, we need at least one digit
                if (!state.hasNext() || !isNumeric(state.peek())) {
                    // If no digit follows the decimal, treat it as end of number
                    // This handles cases like "3." which some languages allow
                    break;
                }
            } else if (isDefEnd(peek) || !isNumeric(peek)) {
                break; // End of number
            } else {
                break; // Any other character ends the number
            }
        }

        // Parse and create token
        if (isFloat) {
            double val = Double.parseDouble(numString.toString());
            state.addDataToken(TokenType.Literal.Float, new TokenData.FloatData(val));
        } else {
            long val = Long.parseLong(numString.toString());
            state.addDataToken(TokenType.Literal.Integer, new TokenData.IntegerData(val)); // Fixed: was FloatData
        }
        return true;
    }

    private static String lexToString(State state) {
        StringBuilder str = new StringBuilder();
        str.append(state.currChar);

        while (state.hasNext()) {
            char peek = state.peek();
            if (!isDefEnd(peek) && isAlphaNumeric(peek)) {
                str.append(state.advance());
            } else {
                break;
            }
        }
        return str.toString();
    }

    private static boolean lexWord(State state) {
        String word = lexToString(state);
        matchKeyword((word)).ifPresentOrElse(
                state::addToken,
                () -> state.addDataToken(TokenType.Literal.Identifier, new TokenData.StringData(word))
        );
        return true;
    }

    private static Optional<TokenType> matchKeyword(String s) {
        for (TokenType.KeyWordToken t : KEYWORD_TOKENS) {
            if (t.keyword().equals(s)) {
                return Optional.of(t.tokenType());
            }
        }
        return Optional.empty();
    }


    private static boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z')
               || (c >= 'A' && c <= 'Z')
               || c == '_';
    }

    private static boolean isNumeric(char c) {
        return (c >= '0' && c <= '9');
    }

    private static boolean isDefEnd(char c) {
        return switch (c) {
            case ' ', ')', '(', '[', ']', '\r', '\n', '\t', ',', EOF -> true;
            default -> false;
        };
    }


    private static boolean isAlphaNumeric(char c) {
        return isAlpha(c) || isNumeric(c);
    }


    private static boolean isSkippable(char c) {
        return switch (c) {
            case ' ', '\t', '\r', ',' -> true;
            default -> false;
        };
    }

    private static boolean isNewLine(char c) {
        return c == '\n';
    }

    private static class State {
        final String source;
        final List<Token> tokens;
        int lineNum = 1;
        int lineChar = 1;
        char currChar = EOF;
        int srcIndex = 0;

        public State(String source) {
            this.source = source;
            this.tokens = new ArrayList<>(source.length() / 4);
        }

        private boolean hasNext() {
            return srcIndex < source.length();
        }

        private void incLineNum() {
            lineNum++;
            lineChar = 1;
        }

        private char advance() {
            currChar = source.charAt(srcIndex++);
            lineChar++;
            return currChar;
        }

        private char peek() {
            return hasNext() ? source.charAt(srcIndex) : EOF;
        }

        private char peekN(int n) {
            int idx = srcIndex + (n - 1);
            return idx < source.length() ? source.charAt(idx) : EOF;
        }

        private boolean addToken(TokenType tokenType) {
            var token = new Token(tokenType, TokenData.EMPTY, lineNum, lineChar);
            tokens.add(token);
            return true;
        }

        private void addDataToken(TokenType tokenType, TokenData tokenData) {
            var token = new Token(tokenType, tokenData, lineNum, lineChar);
            tokens.add(token);
        }
    }


}