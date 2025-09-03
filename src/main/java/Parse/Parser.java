package Parse;


import Lang.AST.ASTNode;
import Lang.AST.MetaData;
import Lang.LangType;
import Lang.LineChar;
import Lang.Symbol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

        public ASTNode.CompilationUnit process() throws Grammar.InvalidGrammarException, ParseError {
            List<ASTNode> rootExpressions = new ArrayList<>();

            while (haveNext()) {
                var subParser = new SubParser(this::peekN);
                switch (Grammar.findNextMatch(subParser)) {
                    case Grammar.MatchResult.Found(var form) -> rootExpressions.add(parseGrammarPattern(form));
                    case Grammar.MatchResult.None _ -> throw ParseError.of(peek(), "Valid Grammar Form");
                }
            }
            return new ASTNode.CompilationUnit(rootExpressions);
        }

        public ASTNode parseGrammarPattern(GrammarForm form) throws ParseError {
            return switch (form) {
                case GrammarForm.Expression expression -> parseExpression(expression);
                case GrammarForm.Statement statement -> parseStatement(statement);
                default -> throw ParseError.of(
                        peek(),
                        "Error<Internal>: GrammarForm to parse should have root of expression or statement"
                );
            };
        }

        private ASTNode parseStatement(GrammarForm.Statement statement) throws ParseError {
            return switch (statement) {
                case GrammarForm.Statement.Let let -> parseLetStatement(let);
                case GrammarForm.Statement.Reassign reassign -> parseReassignStatement(reassign);
            };
        }

        private ASTNode.Expression parseExpression(GrammarForm.Expression expression) throws ParseError {
            return switch (expression) {
                case GrammarForm.Expression.BlockExpr blockExpr -> parseBlockExpression(blockExpr);
                case GrammarForm.Expression.CondExpr condExpr -> parseCondExpression(condExpr);
                case GrammarForm.Expression.MExpr mExpr -> parseMExpression(mExpr);
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
            Symbol identifier = Symbol.ofResolved(parseIdentifier()); // Resolved as we've defined it here, it exists

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

        // ::= Identifier ':=' Expr
        // Note: this is only for local identifiers and can be considered a sugared form of reassignment
        private ASTNode parseReassignStatement(GrammarForm.Statement.Reassign reassignStatement) throws ParseError {
            LineChar lineChar = getLineChar();

            // ::= Identifier
            Symbol identifier = Symbol.ofUnresolved(parseIdentifier()); //Unresolved, as we don't know if symbol exists

            // ::= ':='
            consume(TokenType.REASSIGNMENT);

            // ::= Expr
            ASTNode.Expression assignment = parseExpression(reassignStatement.assignment());
            return new ASTNode.Statement.Assign(identifier, assignment, MetaData.ofUnresolved(lineChar, LangType.UNDEFINED));
        }




        /*------------
        | EXPRESSIONS |
        -------------*/


        // ::= '{' { Expr | Stmnt } '}'
        private ASTNode.Expression parseBlockExpression(GrammarForm.Expression.BlockExpr blockExpression) throws ParseError {
            LineChar lineChar = getLineChar();

            // ::= '{'
            consumeLeftBrace();

            //::= { Expr | Stmnt }
            List<ASTNode> contents = new ArrayList<>(blockExpression.members().size());
            for (var member : blockExpression.members()) { contents.add(parseGrammarPattern(member)); }

            // ::= '{'
            consumeRightBrace();

            return new ASTNode.Expression.BExpr(contents, MetaData.ofUnresolved(lineChar, LangType.UNDEFINED));
        }

        private ASTNode.Expression parseCondExpression(GrammarForm.Expression.CondExpr condExpression) throws ParseError {
            LineChar lineChar = getLineChar();

            // ::= '('
            consumeLeftParen();

            // ::= Expr
            ASTNode.Expression predicate = parseExpression(condExpression.predicateExpression());

            // ::= '->' Expr [ ':' Expr ]
            ASTNode.Expression.PredicateForm predicateForm = parsePredicateForm(condExpression.predicateForm());

            // ::= ')'
            consumeRightParen();

            return new ASTNode.Expression.PExpr(predicate, predicateForm, MetaData.ofUnresolved(lineChar, LangType.UNDEFINED));
        }


        // ::= '->' Expr [ ':' Expr ]
        private ASTNode.Expression.PredicateForm parsePredicateForm(GrammarForm.PredicateForm predicateForm) throws ParseError {
            LineChar lineChar = getLineChar();

            Optional<ASTNode.Expression> thenExpr;
            if (predicateForm.thenForm().isPresent()) {
                consume(TokenType.RIGHT_ARROW);
                thenExpr = Optional.of(parseExpression(predicateForm.thenForm().get()));
            } else {
                thenExpr = Optional.empty();
            }

            Optional<ASTNode.Expression> elseExpr;
            if (predicateForm.elseForm().isPresent()) {
                consume(TokenType.Syntactic.Colon);
                elseExpr = Optional.of(parseExpression(predicateForm.elseForm().get()));
            } else {
                elseExpr = Optional.empty();
            }

            return new ASTNode.Expression.PredicateForm(thenExpr, elseExpr, MetaData.ofUnresolved(lineChar, LangType.UNDEFINED));
        }

        //::=  [ NamespaceAccess ] [ AccessChain ]
        private ASTNode.Expression parseMExpression(GrammarForm.Expression.MExpr mExpression) throws ParseError {
            LineChar lineChar = getLineChar();

            List<ASTNode.AccessType> accesses =
                    new ArrayList<>(mExpression.namespaceDepth() + mExpression.accessChain().size());

            // ::= [ NamespaceAccess ]
            accesses.addAll(parseNamesAccess(mExpression.namespaceDepth()));

            // ::= [ AccessChain ]
            accesses.addAll(parseAccessChain(mExpression.accessChain()));

            return new ASTNode.Expression.MExpr(accesses, MetaData.ofUnresolved(lineChar, LangType.UNDEFINED));
        }

        // ::= '(' Expr | Operation { Expr }
        private ASTNode.Expression parseSExpression(GrammarForm.Expression.SExpr sExpression) throws ParseError {
            LineChar lineChar = getLineChar();

            // ::= '('
            consumeLeftParen();

            // Expr | Operation { Expr }
            ASTNode.Expression parsedExpr = switch (sExpression.operation()) {
                case GrammarForm.Operation.ExprOp exprOp -> {
                    ASTNode.Expression expression = parseExpression(exprOp.expression());
                    List<ASTNode.Expression> operands = parseOperands(sExpression.operands());
                    yield new ASTNode.Expression.SExpr(
                            expression, operands, MetaData.ofUnresolved(lineChar, LangType.UNDEFINED)
                    );
                }
                case GrammarForm.Operation.Op op -> {
                    ASTNode.Operation parsedOp = parseOperation();
                    List<ASTNode.Expression> operands = parseOperands(sExpression.operands());
                    yield new ASTNode.Expression.OExpr(
                            parsedOp, operands, MetaData.ofUnresolved(lineChar, LangType.UNDEFINED)
                    );
                }
            };

            // ::= ')'
            consumeRightParen();

            return parsedExpr;
        }

        //TODO add array and tuple literals
        // This is also messy af
        private ASTNode.Expression parseVExpression(GrammarForm.Expression.VExpr vExpression) throws ParseError {
            LineChar lineChar = getLineChar();

            Token token = advance();
            return switch (token) {
                case Token(TokenType.Literal lit, _, _, _) when lit == TokenType.Literal.True ->
                        new ASTNode.Expression.VExpr(new ASTNode.Value.Bool(true), MetaData.ofResolved(lineChar, LangType.BOOL));

                case Token(TokenType.Literal lit, _, _, _) when lit == TokenType.Literal.False ->
                        new ASTNode.Expression.VExpr(new ASTNode.Value.Bool(false), MetaData.ofResolved(lineChar, LangType.BOOL));

                case Token(
                        TokenType.Literal lit, TokenData.FloatData fd, _, _
                ) when lit == TokenType.Literal.Float ->
                        new ASTNode.Expression.VExpr(new ASTNode.Value.F64(fd.data()), MetaData.ofResolved(lineChar, LangType.F64));

                case Token(
                        TokenType.Literal lit, TokenData.IntegerData id, _, _
                ) when lit == TokenType.Literal.Integer -> new ASTNode.Expression.VExpr(
                        new ASTNode.Value.F64(id.data()), MetaData.ofResolved(lineChar, LangType.I64)
                );

                case Token(
                        TokenType.Literal lit, TokenData.StringData id, _, _
                ) when lit == TokenType.Literal.Identifier -> new ASTNode.Expression.VExpr(new ASTNode.Value.Identifier(
                        Symbol.ofUnresolved(id.data())), MetaData.ofResolved(lineChar, LangType.UNDEFINED)
                );

                case Token(
                        TokenType.Literal lit, TokenData.StringData id, _, _
                ) when lit == TokenType.Literal.Nil ->
                        new ASTNode.Expression.VExpr(new ASTNode.Value.Nil(), MetaData.ofResolved(lineChar, LangType.NIL));

                default -> throw ParseError.expected(token, "Value Expression");
            };
        }

        private ASTNode.Expression parseIterExpression(GrammarForm.Expression.IterExpr iterExpression) {
            throw new UnsupportedOperationException("Iter not implemented");
        }

        private ASTNode.Expression parseMatchExpression(GrammarForm.Expression.MatchExpr matchExpression) {
            throw new UnsupportedOperationException("Match not implemented");
        }

        private ASTNode.Expression parseLambdaExpression(GrammarForm.Expression.LambdaExpr lambdaExpression) throws ParseError {
            LineChar lineChar = getLineChar();

            // ::= '('
            consumeLeftParen();

            // ::=  '=>'
            consume(TokenType.LAMBDA_ARROW);

            // ::= [ (':' Type) ]
            LangType type = lambdaExpression.hasType()
                    ? parseType(true)
                    : LangType.UNDEFINED;

            // ::= '|' { Parameter } '|' Expr
            ASTNode.Expression.LExpr lambda = parseLambdaFormExpression(lambdaExpression.form());

            // ::= ')'
            consumeRightParen();

            return new ASTNode.Expression.LExpr(
                    lambda.parameters(), lambda.body(), false, MetaData.ofUnresolved(lineChar, type)
            );
        }

        // ::= '|' { Parameter } '|' Expr
        private ASTNode.Expression.LExpr parseLambdaFormExpression(GrammarForm.Expression.LambdaFormExpr lambdaFormExpr) throws ParseError {
            LineChar lineChar = getLineChar();

            // ::= '|'
            consume(TokenType.BAR);

            // ::= { Parameter }
            List<ASTNode.Parameter> parameters = parseParameters(lambdaFormExpr.parameters().params());

            // ::= '|'
            consume(TokenType.BAR);
            // ::= Expr
            ASTNode.Expression expr = parseExpression(lambdaFormExpr.expression());

            return new ASTNode.Expression.LExpr(parameters, expr, true, MetaData.ofUnresolved(lineChar, LangType.UNDEFINED));
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
                } else { throw ParseError.expected(peek(), "Modifier"); }
            }
            return modifiers;
        }

        private List<ASTNode.Expression> parseOperands(List<GrammarForm.Expression> operands) throws ParseError {
            List<ASTNode.Expression> parsedOperands = new ArrayList<>(operands.size());
            for (var opr : operands) { parsedOperands.add(parseExpression(opr)); }
            return parsedOperands;
        }

        private ASTNode.Operation parseOperation() throws ParseError {
            switch (advance().tokenType()) {
                case TokenType.Operation op -> { return op.astOpValue; }
                default -> throw ParseError.expected(peek(), "Operation");
            }
        }

        private List<ASTNode.Argument> parseArguments(List<GrammarForm.Arg> argForms) throws ParseError {
            if (argForms.isEmpty()) { return List.of(); }

            List<ASTNode.Argument> arguments = new ArrayList<>(argForms.size());
            for (var arg : argForms) {
                List<ASTNode.Modifier> modifiers = parseModifiers(arg.modifierCount());
                ASTNode.Expression expression = parseExpression(arg.expression());
                arguments.add(new ASTNode.Argument(modifiers, expression));
            }
            return arguments;
        }

        private List<ASTNode.Parameter> parseParameters(List<GrammarForm.Param> paramForms) throws ParseError {
            if (paramForms.isEmpty()) { return List.of(); }

            List<ASTNode.Parameter> parameters = new ArrayList<>(paramForms.size());

            for (var param : paramForms) {
                List<ASTNode.Modifier> modifiers = parseModifiers(param.modifierCount());
                Symbol identifier = Symbol.ofResolved(parseIdentifier()); // Resolved/Defined

                LangType type = param.hasType()
                        ? parseType(true)
                        : LangType.UNDEFINED;

                parameters.add(new ASTNode.Parameter(modifiers, identifier, type));
            }

            return parameters;

        }


        /*----------
        | Accessors |
         ----------*/

        private List<ASTNode.AccessType> parseNamesAccess(int count) throws ParseError {
            if (count == 0) { return List.of(); }

            List<ASTNode.AccessType> accesses = new ArrayList<>(count);
            for (int i = 0; i < count; ++i) {
                switch (advance()) {
                    case Token(
                            TokenType t, TokenData.StringData str, _, _
                    ) when t == TokenType.Literal.Identifier -> {
                        accesses.add(new ASTNode.AccessType.Namespace(Symbol.ofUnresolved(str.data())));
                        consume(TokenType.NAME_SPACE_ACCESS);
                    }
                    default -> throw ParseError.expected(peek(), "Namespace Identifier");
                }
            }
            return accesses;
        }

        private List<ASTNode.AccessType> parseAccessChain(List<GrammarForm.MemberAccess> accessForms) throws
                ParseError {
            if (accessForms.isEmpty()) { return List.of(); }

            List<ASTNode.AccessType> accesses = new ArrayList<>(accessForms.size());
            for (var acc : accessForms) {
                switch (acc) {
                    case GrammarForm.MemberAccess.Identifier _, GrammarForm.MemberAccess.FunctionAccess _ -> {
                        consume(acc instanceof GrammarForm.MemberAccess.Identifier
                                ? TokenType.IDENTIFIER_ACCESS
                                : TokenType.FUNCTION_ACCESS
                        );
                        Symbol identifier = Symbol.ofUnresolved(parseIdentifier()); //unresolved as we haven't validated existence
                        accesses.add(new ASTNode.AccessType.Identifier(identifier));
                    }
                    case GrammarForm.MemberAccess.FunctionCall fc -> {
                        consume(TokenType.FUNCTION_ACCESS);
                        Symbol identifier = Symbol.ofUnresolved(parseIdentifier()); //unresolved as we haven't validated existence
                        consumeLeftBracket();
                        List<ASTNode.Argument> arguments = parseArguments(fc.arguments());
                        consumeRightBracket();
                        accesses.add(new ASTNode.AccessType.FunctionCall(identifier, arguments));
                    }
                }
            }
            return accesses;
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
