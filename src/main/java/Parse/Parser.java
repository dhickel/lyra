package Parse;


import Lang.AST.ASTNode;
import Lang.AST.MetaData;
import Lang.LangType;
import Lang.LineChar;
import Lang.Symbol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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

        private ASTNode parseGrammarPattern(GrammarForm form) throws ParseError {
            switch (form) {
                case GrammarForm.Expression expression -> { }
                case GrammarForm.Statement statement -> { }
                default -> throw ParseError.of(
                        peek(),
                        "Error<Internal>: GrammarForm to parse should have root of expression or statement"
                );
            }
        }

        private ASTNode parseStatement(GrammarForm.Statement statement) throws ParseError {
            return switch (statement) {
                case GrammarForm.Statement.Let let -> parseLetStatement(let);
                case GrammarForm.Statement.Reassign reassign -> parseReassignStatement(reassign);
            };
        }

        private ASTNode.Expression parseExpression(GrammarForm.Expression expression) {
            return switch (expression) {
                case GrammarForm.Expression.BlockExpr blockExpr -> parseBlockExpression(blockExpr);
                case GrammarForm.Expression.CondExpr condExpr -> parseCondExpression(condExpr);
                case GrammarForm.Expression.FExpr fExpr -> parseFExpression(fExpr);
                case GrammarForm.Expression.IterExpr iterExpr -> parseIterExpression(iterExpr);
                case GrammarForm.Expression.LambdaExpr lambdaExpr -> parseLambdaExpression(lambdaExpr);
                case GrammarForm.Expression.LambdaFormExpr lambdaFormExpr -> parseLambdaFormExpression(lambdaFormExpr);
                case GrammarForm.Expression.MatchExpr matchExpr -> parseMatchExpression(matchExpr);
                case GrammarForm.Expression.SExpr sExpr -> parseSExpression(sExpr);
                case GrammarForm.Expression.VExpr vExpr -> parseVExpression(vExpr);
            };
        }

        /*-----------
        | STATEMENTS |
         -----------*/

        // ::= 'let' Identifier { Modifier } [ ':' Type ] '=' Expr
        private ASTNode parseLetStatement(GrammarForm.Statement.Let letStatement) throws ParseError {
            LineChar lineChar = getLineChar();

            // ::= let
            consume(TokenType.Definition.Let);

            // ::= Identifier
            Symbol identifier = Symbol.ofResolved(parseIdentifier());

            // ::= [ ':' Type ]
            LangType type = letStatement.hasType()
                    ? parseType(true)
                    : LangType.UNDEFINED;

            // ::= { Modifier }
            List<ASTNode.Modifier> modifiers = parseModifiers(letStatement.modifierCount());

            // ::= '='
            consume(TokenType.Syntactic.Equal);

            // ::= Expr
            ASTNode.Expression assignment = parseExpression(letStatement.expression());

            return new ASTNode.Statement.Let(identifier, modifiers, assignment, MetaData.ofUnresolved(lineChar, type));
        }

        private ASTNode parseReassignStatement(GrammarForm.Statement.Reassign reassignStatement) {

        }




        /*------------
        | EXPRESSIONS |
        -------------*/

        private ASTNode.Expression parseBlockExpression(GrammarForm.Expression.BlockExpr blockExpression) {

        }

        private ASTNode.Expression parseCondExpression(GrammarForm.Expression.CondExpr condExpression) {

        }

        private ASTNode.Expression parseFExpression(GrammarForm.Expression.FExpr fExpression) {

        }

        private ASTNode.Expression parseSExpression(GrammarForm.Expression.SExpr sExpression) {

        }

        private ASTNode.Expression parseVExpression(GrammarForm.Expression.VExpr vExpression) {

        }

        private ASTNode.Expression parseIterExpression(GrammarForm.Expression.IterExpr iterExpression) {

        }

        private ASTNode.Expression parseMatchExpression(GrammarForm.Expression.MatchExpr matchExpression) {

        }

        private ASTNode.Expression parseLambdaExpression(GrammarForm.Expression.LambdaExpr lambdaExpression) {

        }

        private ASTNode.Expression parseLambdaFormExpression(GrammarForm.Expression.LambdaFormExpr lambdaFormExpression) {

        }

        /*--------
        | UTILITY |
         --------*/

        private String parseIdentifier() throws ParseError {
            return switch (consume(TokenType.IDENTIFIER)) {
                case Token(_, TokenData data, _, _) when data instanceof TokenData.StringData(String s) -> s;
                default -> throw ParseError.expected(peek(), "Identifier");
            };
        }

        private List<ASTNode.Modifier> parseModifiers(int count) throws ParseError {
            if (count == 0) { return List.of(); }

            List<ASTNode.Modifier> modifiers = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                if (advance().tokenType() instanceof TokenType.Modifier mod) {
                    modifiers.add(mod.astModValue);
                } else { ParseError.expected(peek(), "Modifier"); }
            }
            return modifiers;
        }


        /*------
        | TYPES |
         ______*/

        private LangType parseType(boolean consumeColon) throws ParseError {
            if (consumeColon) { consume(TokenType.Syntactic.Colon); }

            Token token = peek();
            return switch (token.tokenType()) {
                // Fn<Type>
                case TokenType.BuiltIn.Fn -> {
                    advance();
                    yield parseFunctionType();
                }
                // Array<Type>
                case TokenType.BuiltIn.Array -> {
                    advance();
                    yield parseArrayType();
                }
                // Type
                case TokenType.Literal.Identifier -> {
                    if (token.tokenData() instanceof TokenData.StringData(String identifier)) {
                        LangType type = parseTypeFromString(identifier);
                        advance();
                        yield type;
                    } else { throw ParseError.of(token, "Error<Internal>: Identifier should have StringData"); }
                }
                default -> throw ParseError.of(token, "Expected Type Identifier");
            };
        }

        // ::= '<' { Identifier } ';' Identifier } '>'
        private LangType parseFunctionType() throws ParseError {
            // ::= '<'
            consume(TokenType.LEFT_ANGLE_BRACKET);

            // ::= { Identifier }

            List<LangType> paramTypes = new ArrayList<>(5);
            while (peek().tokenType() == TokenType.IDENTIFIER) {
                LangType type = parseType(false);
                paramTypes.add(type);
            }

            // ::= ';'
            consume(TokenType.Syntactic.SemiColon);

            // ::= Identifier
            LangType rtnType = parseType(false);

            // ::= '>'
            consume(TokenType.RIGHT_ANGLE_BRACKET);

            return LangType.ofFunction(paramTypes, rtnType);
        }


        private LangType parseArrayType() throws ParseError {
            LangType type = parseType(false);
            return LangType.ofArray(type);
        }

        private static final Map<String, LangType> primitiveTypes =
                LangType.allPrimitives.stream().collect(Collectors.toUnmodifiableMap(
                        p -> p.getClass().getSimpleName(),
                        Function.identity()
                ));

        private LangType parseTypeFromString(String identifier) {
            return primitiveTypes.getOrDefault(identifier, new LangType.UserType(identifier));

        }


    }


}
