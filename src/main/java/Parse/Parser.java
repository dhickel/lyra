package Parse;


import Lang.AST.ASTNode;
import Lang.LineChar;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public interface Parser {
    Token peekN(int n);

    Token peek();

    void consumeN(int n);

    class SubParser implements Parser {
        private int index = 0;
        private final Function<Integer, Token> supplier;

        SubParser(Function<Integer, Token> tokenSupplier) {
            supplier = tokenSupplier;
        }

        // These are always indexed 1 in advanced, as the lang parser already applies a -1 to n
        @Override
        public Token peekN(int n) { return supplier.apply(index + n); }

        @Override
        public Token peek() { return supplier.apply(index + 1); }


        public Token advance() {
            index += 1;
            return supplier.apply(index - 1);
        }

        @Override
        public void consumeN(int n) { index += n; }
    }

    class ParseError extends Exception {
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

    class LangParser implements Parser {
        private final List<Token> tokens;
        private int current = 0;
        private final int end;
        private int depth = 0;
        List<String> warnings;
        String namespace;

        public LangParser(List<Token> tokens) {
            this.tokens = tokens;
            this.warnings = new ArrayList<>();
            this.namespace = "main";
            this.end = tokens.size();
        }

        /*--------------
        | STATE METHODS |
         --------------*/

        @Override
        public void consumeN(int n) { // Exception signature cant use, need better error handling
            throw new RuntimeException("Don't use this");
        }


        @Override
        public Token peekN(int n) {
            return tokens.get(current + (n - 1));
        }

        @Override
        public Token peek() {
            return tokens.get(current);
        }

        public boolean haveNext() {
            return current + 1 < end;
        }


        private void advancePastEndCheck() throws ParseError {
            if (!haveNext()) { throw ParseError.of(peek(), "Advanced Past End"); }
        }

        // Extra check to help catch common errors when parsing
        private void openContainerTokenCheck() throws ParseError {
            switch (peek().tokenType()) {
                case TokenType.Syntactic.LeftParen, TokenType.Syntactic.RightParen,
                     TokenType.Syntactic.LeftBrace, TokenType.Syntactic.RightBrace,
                     TokenType.Syntactic.LeftBracket, TokenType.Syntactic.RightBracket -> throw ParseError.of(
                        peek(),
                        "\"(, [, {, }, ], )\" should only be advanced via related consumer functions"
                );
                default -> { }
            }
        }

        private Token advance() throws ParseError {
            advancePastEndCheck();
            openContainerTokenCheck();
            current += 1;
            return tokens.get(current - 1);
        }

        private LineChar getLineChar() {
            Token token = peek();
            return new LineChar(token.line(), token.chr());
        }

        private void pushWarning(String warning) {
            warnings.add(warning);
        }

        private void consumeLeftParen() throws ParseError {
            if (!check(TokenType.Syntactic.LeftParen)) {
                throw ParseError.expected(peek(), "Left Paren");
            }
            current += 1;
            depth += 1;
        }

        private void consumeRightParen() throws ParseError {
            if (!check(TokenType.Syntactic.RightParen)) {
                throw ParseError.expected(peek(), "Right Paren");
            }
            current += 1;
            depth -= 1;
        }

        private void consumeLeftBracket() throws ParseError {
            if (!check(TokenType.Syntactic.LeftBracket)) {
                throw ParseError.expected(peek(), "Left Bracket");
            }
            current += 1;
        }

        private void consumeRightBracket() throws ParseError {
            if (!check(TokenType.Syntactic.RightBracket)) {
                throw ParseError.expected(peek(), "Right Bracket");
            }
            current += 1;
        }

        private void consumeLeftBrace() throws ParseError {
            if (!check(TokenType.Syntactic.LeftBrace)) {
                throw ParseError.expected(peek(), "Left Brace");
            }
            current += 1;
        }

        private void consumeRightBrace() throws ParseError {
            if (!check(TokenType.Syntactic.RightBrace)) {
                throw ParseError.expected(peek(), "Right Brace");
            }
            current += 1;
        }

        private boolean check(TokenType checkType) {
            if (haveNext()) {
                TokenType peekType = peek().tokenType();
                return checkType == peekType;
            } else { return false; }
        }

        private Token consume(TokenType tokenType) throws ParseError {
            advancePastEndCheck();
            openContainerTokenCheck();
            if (!check(tokenType)) { throw ParseError.expected(peek(), tokenType.toString()); }
            return advance();
        }

        /*--------------
        | ENTRY METHODS |
         --------------*/

        private ASTNode


    }


}
