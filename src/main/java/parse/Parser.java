package parse;


import lang.ast.ASTNode;
import lang.ast.MetaData;
import lang.LangType;
import lang.LineChar;
import lang.Symbol;
import lang.grammar.Grammar;
import lang.grammar.GrammarForm;
import lang.grammar.GrammarMatch;
import util.Result;
import util.exceptions.CompExcept;
import util.exceptions.ParseError;

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


        private Result<Void, CompExcept> advancePastEndCheck() {
            if (!haveNext()) { return Result.err(ParseError.of(peek(), "Advanced Past End")); }
            return Result.ok(null);
        }

        // Extra check to help catch common errors when parsing
        private Result<Void, CompExcept> openContainerTokenCheck() {
            switch (peek().tokenType()) {
                case TokenType.Syntactic.LeftParen, TokenType.Syntactic.RightParen,
                     TokenType.Syntactic.LeftBrace, TokenType.Syntactic.RightBrace,
                     TokenType.Syntactic.LeftBracket, TokenType.Syntactic.RightBracket -> {
                    return Result.err(ParseError.of(
                            peek(),
                            "\"(, [, {, }, ], )\" should only be advanced via related consumer functions"
                    ));
                }
                default -> { return Result.ok(null); }
            }
        }

        private Result<Token, CompExcept> advance() {
            return advancePastEndCheck()
                    .flatMap(v -> openContainerTokenCheck())
                    .map(v -> {
                        current += 1;
                        return tokens.get(current - 1);
                    });
        }

        private LineChar getLineChar() {
            Token token = peek();
            return new LineChar(token.line(), token.chr());
        }

        private void pushWarning(String warning) {
            warnings.add(warning);
        }

        private Result<Void, CompExcept> consumeLeftParen() {
            if (!check(TokenType.Syntactic.LeftParen)) {
                return Result.err(ParseError.expected(peek(), "Left Paren"));
            }
            current += 1;
            depth += 1;
            return Result.ok(null);
        }

        private Result<Void, CompExcept> consumeRightParen() {
            if (!check(TokenType.Syntactic.RightParen)) {
                return Result.err(ParseError.expected(peek(), "Right Paren"));
            }
            current += 1;
            depth -= 1;
            return Result.ok(null);
        }

        private Result<Void, CompExcept> consumeLeftBracket() {
            if (!check(TokenType.Syntactic.LeftBracket)) {
                return Result.err(ParseError.expected(peek(), "Left Bracket"));
            }
            current += 1;
            return Result.ok(null);
        }

        private Result<Void, CompExcept> consumeRightBracket() {
            if (!check(TokenType.Syntactic.RightBracket)) {
                return Result.err(ParseError.expected(peek(), "Right Bracket"));
            }
            current += 1;
            return Result.ok(null);
        }

        private Result<Void, CompExcept> consumeLeftBrace() {
            if (!check(TokenType.Syntactic.LeftBrace)) {
                return Result.err(ParseError.expected(peek(), "Left Brace"));
            }
            current += 1;
            return Result.ok(null);
        }

        private Result<Void, CompExcept> consumeRightBrace() {
            if (!check(TokenType.Syntactic.RightBrace)) {
                return Result.err(ParseError.expected(peek(), "Right Brace"));
            }
            current += 1;
            return Result.ok(null);
        }

        private boolean check(TokenType checkType) {
            if (haveNext()) {
                TokenType peekType = peek().tokenType();
                return checkType == peekType;
            } else { return false; }
        }

        private Result<Token, CompExcept> consume(TokenType tokenType) {
            return advancePastEndCheck()
                    .flatMap(v -> openContainerTokenCheck())
                    .flatMap(v -> {
                        if (!check(tokenType)) {
                            return Result.err(ParseError.expected(peek(), tokenType.toString()));
                        }
                        return advance();
                    });
        }

        /*--------------
        | ENTRY METHODS |
         --------------*/

        public Result<ASTNode.CompilationUnit, CompExcept> process() {
            List<ASTNode> rootExpressions = new ArrayList<>();

            while (haveNext()) {
                var subParser = new SubParser(this::peekN);
                Result<ASTNode, CompExcept> nodeResult = Grammar.findNextMatch(subParser)
                        .flatMap(match -> switch (match) {
                            case GrammarMatch.Found(var form) -> parseGrammarPattern(form);
                            case GrammarMatch.None _ -> Result.err(ParseError.of(peek(), "Valid Grammar Form"));
                        });

                switch (nodeResult) {
                    case Result.Ok(ASTNode node) -> rootExpressions.add(node);
                    case Result.Err(CompExcept e) -> {
                        return Result.err(e);
                    }
                }
            }

            return Result.ok(new ASTNode.CompilationUnit(rootExpressions));
        }

        public Result<ASTNode, CompExcept> parseGrammarPattern(GrammarForm form) {
            return switch (form) {
                case GrammarForm.Expression expression -> parseExpression(expression).map(e -> e);
                case GrammarForm.Statement statement -> parseStatement(statement).map(s -> s);
                default -> Result.err(ParseError.of(
                        peek(),
                        "Error<Internal>: GrammarForm to parse should have root of expression or statement"
                ));
            };
        }

        private Result<ASTNode, CompExcept> parseStatement(GrammarForm.Statement statement) {
            return switch (statement) {
                case GrammarForm.Statement.Let let -> parseLetStatement(let).map(s -> s);
                case GrammarForm.Statement.Reassign reassign -> parseReassignStatement(reassign).map(s -> s);
            };
        }

        private Result<ASTNode.Expression, CompExcept> parseExpression(GrammarForm.Expression expression) {
            return switch (expression) {
                case GrammarForm.Expression.BlockExpr blockExpr -> parseBlockExpression(blockExpr);
                case GrammarForm.Expression.CondExpr condExpr -> parseCondExpression(condExpr);
                case GrammarForm.Expression.MExpr mExpr -> parseMExpression(mExpr);
                case GrammarForm.Expression.IterExpr iterExpr -> parseIterExpression(iterExpr);
                case GrammarForm.Expression.LambdaExpr lambdaExpr -> parseLambdaExpression(lambdaExpr);
                case GrammarForm.Expression.LambdaFormExpr lambdaFormExpr ->
                        parseLambdaFormExpression(lambdaFormExpr).map(e -> e);
                case GrammarForm.Expression.MatchExpr matchExpr -> parseMatchExpression(matchExpr);
                case GrammarForm.Expression.SExpr sExpr -> parseSExpression(sExpr);
                case GrammarForm.Expression.VExpr vExpr -> parseVExpression(vExpr);
            };
        }

        /*-----------
        | STATEMENTS |
         -----------*/

        // ::= 'let' Identifier { Modifier } [ ':' Type ] '=' Expr
        private Result<ASTNode.Statement.Let, CompExcept> parseLetStatement(GrammarForm.Statement.Let letStatement) {
            final LineChar lineChar = getLineChar();

            return consume(TokenType.Definition.Let)
                    .flatMap(let -> parseIdentifier())
                    .flatMap(identifierStr -> {
                        final Symbol identifier = Symbol.ofResolved(identifierStr);
                        final Result<LangType, CompExcept> typeResult = letStatement.hasType()
                                ? parseType(true)
                                : Result.ok(LangType.UNDEFINED);

                        return typeResult.flatMap(type ->
                                parseModifiers(letStatement.modifierCount())
                                        .flatMap(modifiers -> consume(TokenType.Syntactic.Equal)
                                                .flatMap(eq -> parseExpression(letStatement.expression())
                                                        .map(assignment -> new ASTNode.Statement.Let(
                                                                identifier,
                                                                modifiers,
                                                                assignment,
                                                                MetaData.ofUnresolved(lineChar, type)
                                                        ))
                                                )
                                        )
                        );
                    });
        }

        // ::= Identifier ':=' Expr
        // Note: this is only for local identifiers and can be considered a sugared form of reassignment
        private Result<ASTNode.Statement.Assign, CompExcept> parseReassignStatement(GrammarForm.Statement.Reassign reassignStatement) {
            final LineChar lineChar = getLineChar();

            return parseIdentifier()
                    .flatMap(identifierStr -> consume(TokenType.REASSIGNMENT)
                            .flatMap(reassign -> parseExpression(reassignStatement.assignment())
                                    .map(assignment -> new ASTNode.Statement.Assign(
                                            Symbol.ofUnresolved(identifierStr),
                                            assignment,
                                            MetaData.ofUnresolved(lineChar, LangType.UNDEFINED)
                                    ))
                            )
                    );
        }




        /*------------
        | EXPRESSIONS |
        -------------*/


        private Result<List<ASTNode>, CompExcept> parseBlockMembers(List<GrammarForm> members) {
            List<ASTNode> contents = new ArrayList<>(members.size());
            for (var member : members) {
                switch (parseGrammarPattern(member)) {
                    case Result.Ok(ASTNode node) -> contents.add(node);
                    case Result.Err(CompExcept e) -> { return Result.err(e); }
                }
            }
            return Result.ok(contents);
        }

        // ::= '{' { Expr | Stmnt } '}'
        private Result<ASTNode.Expression, CompExcept> parseBlockExpression(GrammarForm.Expression.BlockExpr
                                                                                    blockExpression) {
            LineChar lineChar = getLineChar();

            return consumeLeftBrace()
                    .flatMap(lBrace -> parseBlockMembers(blockExpression.members()))
                    .flatMap(contents -> consumeRightBrace()
                            .map(rBrace -> new ASTNode.Expression.BExpr(contents, MetaData.ofUnresolved(lineChar, LangType.UNDEFINED)))
                    );
        }

        private Result<ASTNode.Expression, CompExcept> parseCondExpression(GrammarForm.Expression.CondExpr
                                                                                   condExpression) {
            LineChar lineChar = getLineChar();

            return consumeLeftParen()
                    .flatMap(lParen -> parseExpression(condExpression.predicateExpression())
                            .flatMap(predicate -> parsePredicateForm(condExpression.predicateForm())
                                    .flatMap(form -> consumeRightParen()
                                            .map(rParen -> new ASTNode.Expression.PExpr(predicate, form, MetaData.ofUnresolved(lineChar, LangType.UNDEFINED)))
                                    )
                            )
                    );
        }

        private Result<Optional<ASTNode.Expression>, CompExcept> parseThenForm(Optional<GrammarForm.Expression> thenForm) {
            if (thenForm.isEmpty()) {
                return Result.ok(Optional.empty());
            }
            return consume(TokenType.RIGHT_ARROW)
                    .flatMap(arrow -> parseExpression(thenForm.get()))
                    .map(Optional::of);
        }

        private Result<Optional<ASTNode.Expression>, CompExcept> parseElseForm(Optional<GrammarForm.Expression> elseForm) {
            if (elseForm.isEmpty()) {
                return Result.ok(Optional.empty());
            }
            return consume(TokenType.Syntactic.Colon)
                    .flatMap(colon -> parseExpression(elseForm.get()))
                    .map(Optional::of);
        }

        // ::= '->' Expr [ ':' Expr ]
        private Result<ASTNode.Expression.PredicateForm, CompExcept> parsePredicateForm(GrammarForm.PredicateForm
                                                                                                predicateForm) {
            LineChar lineChar = getLineChar();

            return parseThenForm(predicateForm.thenForm())
                    .flatMap(thenExpr -> parseElseForm(predicateForm.elseForm())
                            .map(elseExpr -> new ASTNode.Expression.PredicateForm(thenExpr, elseExpr, MetaData.ofUnresolved(lineChar, LangType.UNDEFINED)))
                    );
        }

        //::=  [ NamespaceAccess ] [ AccessChain ]
        private Result<ASTNode.Expression, CompExcept> parseMExpression(GrammarForm.Expression.MExpr mExpression) {
            LineChar lineChar = getLineChar();

            return parseNamesAccess(mExpression.namespaceDepth())
                    .flatMap(namespaceAccesses -> parseAccessChain(mExpression.accessChain())
                            .map(accessChain -> {
                                List<ASTNode.AccessType> allAccesses = new ArrayList<>(namespaceAccesses.size() + accessChain.size());
                                allAccesses.addAll(namespaceAccesses);
                                allAccesses.addAll(accessChain);
                                return new ASTNode.Expression.MExpr(allAccesses, MetaData.ofUnresolved(lineChar, LangType.UNDEFINED));
                            })
                    );
        }

        // ::= '(' Expr | Operation { Expr }
        private Result<ASTNode.Expression, CompExcept> parseSExpression(GrammarForm.Expression.SExpr sExpression) {
            LineChar lineChar = getLineChar();

            Result<ASTNode.Expression, CompExcept> parsedExprResult = switch (sExpression.operation()) {
                case GrammarForm.Operation.ExprOp exprOp ->
                        parseExpression(exprOp.expression()).flatMap(expression ->
                                parseOperands(sExpression.operands()).map(operands ->
                                        new ASTNode.Expression.SExpr(expression, operands, MetaData.ofUnresolved(lineChar, LangType.UNDEFINED))
                                )
                        );
                case GrammarForm.Operation.Op op ->
                        parseOperation().flatMap(operation ->
                                parseOperands(sExpression.operands()).map(operands ->
                                        new ASTNode.Expression.OExpr(operation, operands, MetaData.ofUnresolved(lineChar, LangType.UNDEFINED))
                                )
                        );
            };

            return consumeLeftParen()
                    .flatMap(lParen -> parsedExprResult)
                    .flatMap(expr -> consumeRightParen().map(rParen -> expr));
        }

        //TODO add array and tuple literals
        // This is also messy af
        private Result<ASTNode.Expression, CompExcept> parseVExpression(GrammarForm.Expression.VExpr vExpression) {
            LineChar lineChar = getLineChar();

            return advance().flatMap(token -> switch (token) {
                case Token(TokenType.Literal.True, _, _, _) ->
                        Result.ok(new ASTNode.Expression.VExpr(new ASTNode.Value.Bool(true), MetaData.ofResolved(lineChar, LangType.BOOL)));

                case Token(TokenType.Literal.False, _, _, _) ->
                        Result.ok(new ASTNode.Expression.VExpr(new ASTNode.Value.Bool(false), MetaData.ofResolved(lineChar, LangType.BOOL)));

                case Token(TokenType.Literal.Float, TokenData.FloatData fd, _, _) ->
                        Result.ok(new ASTNode.Expression.VExpr(new ASTNode.Value.F64(fd.data()), MetaData.ofResolved(lineChar, LangType.F64)));

                case Token(TokenType.Literal.Integer, TokenData.IntegerData id, _, _) ->
                        Result.ok(new ASTNode.Expression.VExpr(
                                new ASTNode.Value.F64(id.data()), MetaData.ofResolved(lineChar, LangType.I64)
                        ));

                case Token(TokenType.Literal.Identifier, TokenData.StringData sd, _, _) ->
                        Result.ok(new ASTNode.Expression.VExpr(new ASTNode.Value.Identifier(
                                Symbol.ofUnresolved(sd.data())), MetaData.ofResolved(lineChar, LangType.UNDEFINED)
                        ));

                case Token(TokenType.Literal.Nil, _, _, _) ->
                        Result.ok(new ASTNode.Expression.VExpr(new ASTNode.Value.Nil(), MetaData.ofResolved(lineChar, LangType.NIL)));

                default -> Result.err(ParseError.expected(token, "Value Expression"));
            });
        }

        private Result<ASTNode.Expression, CompExcept> parseIterExpression(GrammarForm.Expression.IterExpr
                                                                                   iterExpression) {
            throw new UnsupportedOperationException("Iter not implemented");
        }

        private Result<ASTNode.Expression, CompExcept> parseMatchExpression(GrammarForm.Expression.MatchExpr
                                                                                    matchExpression) {
            throw new UnsupportedOperationException("Match not implemented");
        }

        private Result<ASTNode.Expression, CompExcept> parseLambdaExpression(GrammarForm.Expression.LambdaExpr
                                                                                     lambdaExpression) {
            final LineChar lineChar = getLineChar();
            final Result<LangType, CompExcept> typeResult = lambdaExpression.hasType()
                    ? parseType(true)
                    : Result.ok(LangType.UNDEFINED);

            return consumeLeftParen()
                    .flatMap(lParen -> consume(TokenType.LAMBDA_ARROW))
                    .flatMap(arrow -> typeResult)
                    .flatMap(type -> parseLambdaFormExpression(lambdaExpression.form())
                            .flatMap(lambda -> consumeRightParen()
                                    .map(rParen -> new ASTNode.Expression.LExpr(
                                            lambda.parameters(),
                                            lambda.body(),
                                            false,
                                            MetaData.ofUnresolved(lineChar, type)
                                    ))
                            )
                    );
        }

        // ::= '|' { Parameter } '|' Expr
        private Result<ASTNode.Expression.LExpr, CompExcept> parseLambdaFormExpression
                (GrammarForm.Expression.LambdaFormExpr lambdaFormExpr) {
            final LineChar lineChar = getLineChar();

            return consume(TokenType.BAR)
                    .flatMap(bar1 -> parseParameters(lambdaFormExpr.parameters().params()))
                    .flatMap(parameters -> consume(TokenType.BAR)
                            .flatMap(bar2 -> parseExpression(lambdaFormExpr.expression())
                                    .map(expr -> new ASTNode.Expression.LExpr(
                                            parameters,
                                            expr,
                                            true,
                                            MetaData.ofUnresolved(lineChar, LangType.UNDEFINED)
                                    ))
                            )
                    );
        }

        /*--------
        | UTILITY |
         --------*/

        private Result<String, CompExcept> parseIdentifier() {
            return consume(TokenType.IDENTIFIER).flatMap(token -> switch (token) {
                case Token(_, TokenData.StringData(String s), _, _) -> Result.ok(s);
                default -> Result.err(ParseError.expected(token, "Identifier"));
            });
        }

        private Result<List<ASTNode.Modifier>, CompExcept> parseModifiers(int count) {
            if (count == 0) {
                return Result.ok(List.of());
            }

            List<ASTNode.Modifier> modifiers = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                switch (advance()) {
                    case Result.Ok(Token(TokenType.Modifier mod, _, _, _)) -> modifiers.add(mod.astModValue);
                    case Result.Ok(Token token) -> {
                        return Result.err(ParseError.expected(token, "Modifier"));
                    }
                    case Result.Err(CompExcept e) -> {
                        return Result.err(e);
                    }
                }
            }
            return Result.ok(modifiers);
        }

        private Result<List<ASTNode.Expression>, CompExcept> parseOperands
                (List<GrammarForm.Expression> operands) {
            List<ASTNode.Expression> parsedOperands = new ArrayList<>(operands.size());
            for (var opr : operands) {
                switch (parseExpression(opr)) {
                    case Result.Ok(ASTNode.Expression expr) -> parsedOperands.add(expr);
                    case Result.Err(CompExcept e) -> {
                        return Result.err(e);
                    }
                    // This default is to satisfy the compiler, as it can't know that Expression is sealed
                    default -> {
                        return Result.err(ParseError.of(peek(), "Should not be reachable"));
                    }
                }
            }
            return Result.ok(parsedOperands);
        }

        private Result<ASTNode.Operation, CompExcept> parseOperation() {
            return advance().flatMap(token -> switch (token.tokenType()) {
                case TokenType.Operation op -> Result.ok(op.astOpValue);
                default -> Result.err(ParseError.expected(token, "Operation"));
            });
        }

        private Result<List<ASTNode.Argument>, CompExcept> parseArguments(List<GrammarForm.Arg> argForms) {
            if (argForms.isEmpty()) {
                return Result.ok(List.of());
            }

            List<ASTNode.Argument> arguments = new ArrayList<>(argForms.size());
            for (var arg : argForms) {
                Result<ASTNode.Argument, CompExcept> argumentResult =
                        parseModifiers(arg.modifierCount()).flatMap(modifiers ->
                                parseExpression(arg.expression()).map(expression ->
                                        new ASTNode.Argument(modifiers, expression)
                                )
                        );

                switch (argumentResult) {
                    case Result.Ok(ASTNode.Argument argument) -> arguments.add(argument);
                    case Result.Err(CompExcept e) -> {
                        return Result.err(e);
                    }
                }
            }
            return Result.ok(arguments);
        }

        private Result<List<ASTNode.Parameter>, CompExcept> parseParameters(List<GrammarForm.Param> paramForms) {
            if (paramForms.isEmpty()) {
                return Result.ok(List.of());
            }

            List<ASTNode.Parameter> parameters = new ArrayList<>(paramForms.size());

            for (var param : paramForms) {
                Result<LangType, CompExcept> typeResult = param.hasType()
                        ? parseType(true)
                        : Result.ok(LangType.UNDEFINED);

                Result<ASTNode.Parameter, CompExcept> parameterResult =
                        parseModifiers(param.modifierCount()).flatMap(modifiers ->
                                parseIdentifier().flatMap(identifierStr ->
                                        typeResult.map(type ->
                                                new ASTNode.Parameter(modifiers, Symbol.ofResolved(identifierStr), type)
                                        )
                                )
                        );

                switch (parameterResult) {
                    case Result.Ok(ASTNode.Parameter p) -> parameters.add(p);
                    case Result.Err(CompExcept e) -> {
                        return Result.err(e);
                    }
                }
            }
            return Result.ok(parameters);
        }


        /*----------
        | Accessors |
         ----------*/

        private Result<List<ASTNode.AccessType>, CompExcept> parseNamesAccess(int count) {
            if (count == 0) {
                return Result.ok(List.of());
            }

            List<ASTNode.AccessType> accesses = new ArrayList<>(count);
            for (int i = 0; i < count; ++i) {
                Result<ASTNode.AccessType.Namespace, CompExcept> accessResult = advance().flatMap(token -> switch (token) {
                    case Token(TokenType.Literal.Identifier, TokenData.StringData(String s), _, _) ->
                            consume(TokenType.NAME_SPACE_ACCESS)
                                    .map(t -> new ASTNode.AccessType.Namespace(Symbol.ofUnresolved(s)));
                    default -> Result.err(ParseError.expected(token, "Namespace Identifier"));
                });

                switch (accessResult) {
                    case Result.Ok(ASTNode.AccessType.Namespace acc) -> accesses.add(acc);
                    case Result.Err(CompExcept e) -> {
                        return Result.err(e);
                    }
                }
            }
            return Result.ok(accesses);
        }

        private Result<List<ASTNode.AccessType>, CompExcept> parseAccessChain
                (List<GrammarForm.AccessType> accessForms) {
            if (accessForms.isEmpty()) {
                return Result.ok(List.of());
            }

            List<ASTNode.AccessType> accesses = new ArrayList<>(accessForms.size());
            for (var acc : accessForms) {
                Result<ASTNode.AccessType, CompExcept> accessResult = switch (acc) {
                    case GrammarForm.AccessType.Identifier _, GrammarForm.AccessType.FunctionAccessType _ ->
                            consume(acc instanceof GrammarForm.AccessType.Identifier
                                    ? TokenType.IDENTIFIER_ACCESS
                                    : TokenType.FUNCTION_ACCESS
                            ).flatMap(token -> parseIdentifier()
                                    .map(id -> new ASTNode.AccessType.Identifier(Symbol.ofUnresolved(id)))
                            );

                    case GrammarForm.AccessType.FunctionCall fc ->
                            consume(TokenType.FUNCTION_ACCESS).flatMap(token ->
                                    parseIdentifier().flatMap(id ->
                                            consumeLeftBracket().flatMap(lb ->
                                                    parseArguments(fc.arguments()).flatMap(args ->
                                                            consumeRightBracket().map(rb ->
                                                                    new ASTNode.AccessType.FunctionCall(Symbol.ofUnresolved(id), args)
                                                            )
                                                    )
                                            )
                                    )
                            );
                };

                switch (accessResult) {
                    case Result.Ok(ASTNode.AccessType access) -> accesses.add(access);
                    case Result.Err(CompExcept e) -> {
                        return Result.err(e);
                    }
                }
            }
            return Result.ok(accesses);
        }


        /*------
        | TYPES |
         ______*/

        private Result<LangType, CompExcept> parseType(boolean consumeColon) {
            Result<Token, CompExcept> start = consumeColon
                    ? consume(TokenType.Syntactic.Colon)
                    : Result.ok(peek());

            return start.flatMap(token -> switch (peek().tokenType()) {
                case TokenType.BuiltIn.Fn -> advance().flatMap(v -> parseFunctionType());
                case TokenType.BuiltIn.Array -> advance().flatMap(v -> parseArrayType());
                case TokenType.Literal.Identifier -> advance().flatMap(idToken -> {
                    if (idToken.tokenData() instanceof TokenData.StringData(String identifier)) {
                        return Result.ok(parseTypeFromString(identifier));
                    } else {
                        return Result.err(ParseError.of(idToken, "Error<Internal>: Identifier should have StringData"));
                    }
                });
                default -> Result.err(ParseError.of(peek(), "Expected Type Identifier"));
            });
        }

        private Result<List<LangType>, CompExcept> parseParamTypes() {
            List<LangType> paramTypes = new ArrayList<>(5);
            while (peek().tokenType() == TokenType.IDENTIFIER) {
                switch (parseType(false)) {
                    case Result.Ok(LangType type) -> paramTypes.add(type);
                    case Result.Err(CompExcept e) -> {
                        return Result.err(e);
                    }
                }
            }
            return Result.ok(paramTypes);
        }

        // ::= '<' { Identifier } ';' Identifier } '>'
        private Result<LangType, CompExcept> parseFunctionType() {
            return consume(TokenType.LEFT_ANGLE_BRACKET)
                    .flatMap(l -> parseParamTypes())
                    .flatMap(paramTypes -> consume(TokenType.Syntactic.SemiColon)
                            .flatMap(semi -> parseType(false))
                            .flatMap(rtnType -> consume(TokenType.RIGHT_ANGLE_BRACKET)
                                    .map(r -> LangType.ofFunction(paramTypes, rtnType))
                            )
                    );
        }


        private Result<LangType, CompExcept> parseArrayType() {
            return consume(TokenType.LEFT_ANGLE_BRACKET)
                    .flatMap(l -> parseType(false))
                    .flatMap(type -> consume(TokenType.RIGHT_ANGLE_BRACKET)
                            .map(r -> LangType.ofArray(type))
                    );
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
