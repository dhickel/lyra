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


        private Result<Void, ParseError> advancePastEndCheck() {
            if (!haveNext()) { return Result.err(ParseError.of(peek(), "Advanced Past End")); }
            return Result.ok(null);
        }

        // Extra check to help catch common errors when parsing
        private Result<Void, ParseError> openContainerTokenCheck() {
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

        private Result<Token, ParseError> advance() {
            if (advancePastEndCheck().isErr()) return advancePastEndCheck().map(v -> null);
            if (openContainerTokenCheck().isErr()) return openContainerTokenCheck().map(v -> null);
            current += 1;
            return Result.ok(tokens.get(current - 1));
        }

        private LineChar getLineChar() {
            Token token = peek();
            return new LineChar(token.line(), token.chr());
        }

        private void pushWarning(String warning) {
            warnings.add(warning);
        }

        private Result<Void, ParseError> consumeLeftParen() {
            if (!check(TokenType.Syntactic.LeftParen)) {
                return Result.err(ParseError.expected(peek(), "Left Paren"));
            }
            current += 1;
            depth += 1;
            return Result.ok(null);
        }

        private Result<Void, ParseError> consumeRightParen() {
            if (!check(TokenType.Syntactic.RightParen)) {
                return Result.err(ParseError.expected(peek(), "Right Paren"));
            }
            current += 1;
            depth -= 1;
            return Result.ok(null);
        }

        private Result<Void, ParseError> consumeLeftBracket() {
            if (!check(TokenType.Syntactic.LeftBracket)) {
                return Result.err(ParseError.expected(peek(), "Left Bracket"));
            }
            current += 1;
            return Result.ok(null);
        }

        private Result<Void, ParseError> consumeRightBracket() {
            if (!check(TokenType.Syntactic.RightBracket)) {
                return Result.err(ParseError.expected(peek(), "Right Bracket"));
            }
            current += 1;
            return Result.ok(null);
        }

        private Result<Void, ParseError> consumeLeftBrace() {
            if (!check(TokenType.Syntactic.LeftBrace)) {
                return Result.err(ParseError.expected(peek(), "Left Brace"));
            }
            current += 1;
            return Result.ok(null);
        }

        private Result<Void, ParseError> consumeRightBrace() {
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

        private Result<Token, ParseError> consume(TokenType tokenType) {
            if (advancePastEndCheck().isErr()) return advancePastEndCheck().map(v -> null);
            if (openContainerTokenCheck().isErr()) return openContainerTokenCheck().map(v -> null);
            if (!check(tokenType)) { return Result.err(ParseError.expected(peek(), tokenType.toString())); }
            return advance();
        }

        /*--------------
        | ENTRY METHODS |
         --------------*/

        public Result<ASTNode.CompilationUnit, Exception> process() {
            List<ASTNode> rootExpressions = new ArrayList<>();

            while (haveNext()) {
                var subParser = new SubParser(this::peekN);
                var findNextMatchResult = Grammar.findNextMatch(subParser);
                if(findNextMatchResult.isErr()) return findNextMatchResult.map(n -> null);

                switch (findNextMatchResult.unwrap()) {
                    case Grammar.MatchResult.Found(var form) -> {
                        var parseResult = parseGrammarPattern(form);
                        if(parseResult.isErr()) return parseResult.map(n -> null);
                        rootExpressions.add(parseResult.unwrap());
                    }
                    case Grammar.MatchResult.None _ -> {
                        return Result.err(ParseError.of(peek(), "Valid Grammar Form"));
                    }
                }
            }
            return Result.ok(new ASTNode.CompilationUnit(rootExpressions));
        }

        public Result<ASTNode, ParseError> parseGrammarPattern(GrammarForm form) {
            return switch (form) {
                case GrammarForm.Expression expression -> parseExpression(expression).map(e -> e);
                case GrammarForm.Statement statement -> parseStatement(statement).map(s -> s);
                default -> Result.err(ParseError.of(
                        peek(),
                        "Error<Internal>: GrammarForm to parse should have root of expression or statement"
                ));
            };
        }

        private Result<ASTNode, ParseError> parseStatement(GrammarForm.Statement statement) {
            return switch (statement) {
                case GrammarForm.Statement.Let let -> parseLetStatement(let).map(s -> s);
                case GrammarForm.Statement.Reassign reassign -> parseReassignStatement(reassign).map(s -> s);
            };
        }

        private Result<ASTNode.Expression, ParseError> parseExpression(GrammarForm.Expression expression) {
            return switch (expression) {
                case GrammarForm.Expression.BlockExpr blockExpr -> parseBlockExpression(blockExpr);
                case GrammarForm.Expression.CondExpr condExpr -> parseCondExpression(condExpr);
                case GrammarForm.Expression.MExpr mExpr -> parseMExpression(mExpr);
                case GrammarForm.Expression.IterExpr iterExpr -> parseIterExpression(iterExpr);
                case GrammarForm.Expression.LambdaExpr lambdaExpr -> parseLambdaExpression(lambdaExpr);
                case GrammarForm.Expression.LambdaFormExpr lambdaFormExpr -> parseLambdaFormExpression(lambdaFormExpr).map(e -> e);
                case GrammarForm.Expression.MatchExpr matchExpr -> parseMatchExpression(matchExpr);
                case GrammarForm.Expression.SExpr sExpr -> parseSExpression(sExpr);
                case GrammarForm.Expression.VExpr vExpr -> parseVExpression(vExpr);
            };
        }

        /*-----------
        | STATEMENTS |
         -----------*/

        // ::= 'let' Identifier { Modifier } [ ':' Type ] '=' Expr
        private Result<ASTNode.Statement.Let, ParseError> parseLetStatement(GrammarForm.Statement.Let letStatement) {
            LineChar lineChar = getLineChar();

            // ::= let
            if(consume(TokenType.Definition.Let).isErr()) return consume(TokenType.Definition.Let).map(t -> null);

            // ::= Identifier
            var identifierResult = parseIdentifier();
            if(identifierResult.isErr()) return identifierResult.map(s -> null);
            Symbol identifier = Symbol.ofResolved(identifierResult.unwrap()); // Resolved as we've defined it here, it exists

            // ::= [ ':' Type ]
            Result<LangType, ParseError> typeResult;
            if (letStatement.hasType()) {
                typeResult = parseType(true);
                if(typeResult.isErr()) return typeResult.map(t -> null);
            } else {
                typeResult = Result.ok(LangType.UNDEFINED);
            }


            // ::= { Modifier }
            var modifiersResult = parseModifiers(letStatement.modifierCount());
            if(modifiersResult.isErr()) return modifiersResult.map(m -> null);

            // ::= '='
            if(consume(TokenType.Syntactic.Equal).isErr()) return consume(TokenType.Syntactic.Equal).map(t -> null);

            // ::= Expr
            var assignmentResult = parseExpression(letStatement.expression());
            if(assignmentResult.isErr()) return assignmentResult.map(e -> null);

            return Result.ok(new ASTNode.Statement.Let(identifier, modifiersResult.unwrap(), assignmentResult.unwrap(), MetaData.ofUnresolved(lineChar, typeResult.unwrap())));
        }

        // ::= Identifier ':=' Expr
        // Note: this is only for local identifiers and can be considered a sugared form of reassignment
        private Result<ASTNode.Statement.Assign, ParseError> parseReassignStatement(GrammarForm.Statement.Reassign reassignStatement) {
            LineChar lineChar = getLineChar();

            // ::= Identifier
            var identifierResult = parseIdentifier();
            if(identifierResult.isErr()) return identifierResult.map(s -> null);
            Symbol identifier = Symbol.ofUnresolved(identifierResult.unwrap()); //Unresolved, as we don't know if symbol exists

            // ::= ':='
            if(consume(TokenType.REASSIGNMENT).isErr()) return consume(TokenType.REASSIGNMENT).map(t -> null);

            // ::= Expr
            var assignmentResult = parseExpression(reassignStatement.assignment());
            if(assignmentResult.isErr()) return assignmentResult.map(e -> null);
            return Result.ok(new ASTNode.Statement.Assign(identifier, assignmentResult.unwrap(), MetaData.ofUnresolved(lineChar, LangType.UNDEFINED)));
        }




        /*------------
        | EXPRESSIONS |
        -------------*/


        // ::= '{' { Expr | Stmnt } '}'
        private Result<ASTNode.Expression, ParseError> parseBlockExpression(GrammarForm.Expression.BlockExpr blockExpression) {
            LineChar lineChar = getLineChar();

            // ::= '{'
            if(consumeLeftBrace().isErr()) return consumeLeftBrace().map(v -> null);

            //::= { Expr | Stmnt }
            List<ASTNode> contents = new ArrayList<>(blockExpression.members().size());
            for (var member : blockExpression.members()) {
                var patternResult = parseGrammarPattern(member);
                if(patternResult.isErr()) return patternResult.map(p -> null);
                contents.add(patternResult.unwrap());
            }

            // ::= '{'
            if(consumeRightBrace().isErr()) return consumeRightBrace().map(v -> null);

            return Result.ok(new ASTNode.Expression.BExpr(contents, MetaData.ofUnresolved(lineChar, LangType.UNDEFINED)));
        }

        private Result<ASTNode.Expression, ParseError> parseCondExpression(GrammarForm.Expression.CondExpr condExpression) {
            LineChar lineChar = getLineChar();

            // ::= '('
            if(consumeLeftParen().isErr()) return consumeLeftParen().map(v -> null);

            // ::= Expr
            var predicateResult = parseExpression(condExpression.predicateExpression());
            if(predicateResult.isErr()) return predicateResult.map(e -> null);

            // ::= '->' Expr [ ':' Expr ]
            var predicateFormResult = parsePredicateForm(condExpression.predicateForm());
            if(predicateFormResult.isErr()) return predicateFormResult.map(e -> null);

            // ::= ')'
            if(consumeRightParen().isErr()) return consumeRightParen().map(v -> null);

            return Result.ok(new ASTNode.Expression.PExpr(predicateResult.unwrap(), predicateFormResult.unwrap(), MetaData.ofUnresolved(lineChar, LangType.UNDEFINED)));
        }


        // ::= '->' Expr [ ':' Expr ]
        private Result<ASTNode.Expression.PredicateForm, ParseError> parsePredicateForm(GrammarForm.PredicateForm predicateForm) {
            LineChar lineChar = getLineChar();

            Optional<ASTNode.Expression> thenExpr;
            if (predicateForm.thenForm().isPresent()) {
                if(consume(TokenType.RIGHT_ARROW).isErr()) return consume(TokenType.RIGHT_ARROW).map(t -> null);
                var thenResult = parseExpression(predicateForm.thenForm().get());
                if(thenResult.isErr()) return thenResult.map(e -> null);
                thenExpr = Optional.of(thenResult.unwrap());
            } else {
                thenExpr = Optional.empty();
            }

            Optional<ASTNode.Expression> elseExpr;
            if (predicateForm.elseForm().isPresent()) {
                if(consume(TokenType.Syntactic.Colon).isErr()) return consume(TokenType.Syntactic.Colon).map(t -> null);
                var elseResult = parseExpression(predicateForm.elseForm().get());
                if(elseResult.isErr()) return elseResult.map(e -> null);
                elseExpr = Optional.of(elseResult.unwrap());
            } else {
                elseExpr = Optional.empty();
            }

            return Result.ok(new ASTNode.Expression.PredicateForm(thenExpr, elseExpr, MetaData.ofUnresolved(lineChar, LangType.UNDEFINED)));
        }

        //::=  [ NamespaceAccess ] [ AccessChain ]
        private Result<ASTNode.Expression, ParseError> parseMExpression(GrammarForm.Expression.MExpr mExpression) {
            LineChar lineChar = getLineChar();

            List<ASTNode.AccessType> accesses =
                    new ArrayList<>(mExpression.namespaceDepth() + mExpression.accessChain().size());

            // ::= [ NamespaceAccess ]
            var namespaceAccessesResult = parseNamesAccess(mExpression.namespaceDepth());
            if(namespaceAccessesResult.isErr()) return namespaceAccessesResult.map(e -> null);
            accesses.addAll(namespaceAccessesResult.unwrap());

            // ::= [ AccessChain ]
            var accessChainResult = parseAccessChain(mExpression.accessChain());
            if(accessChainResult.isErr()) return accessChainResult.map(e -> null);
            accesses.addAll(accessChainResult.unwrap());

            return Result.ok(new ASTNode.Expression.MExpr(accesses, MetaData.ofUnresolved(lineChar, LangType.UNDEFINED)));
        }

        // ::= '(' Expr | Operation { Expr }
        private Result<ASTNode.Expression, ParseError> parseSExpression(GrammarForm.Expression.SExpr sExpression) {
            LineChar lineChar = getLineChar();

            // ::= '('
            if(consumeLeftParen().isErr()) return consumeLeftParen().map(v -> null);

            // Expr | Operation { Expr }
            Result<ASTNode.Expression, ParseError> parsedExprResult = switch (sExpression.operation()) {
                case GrammarForm.Operation.ExprOp exprOp -> {
                    var expressionResult = parseExpression(exprOp.expression());
                    if(expressionResult.isErr()) yield expressionResult.map(e -> null);
                    var operandsResult = parseOperands(sExpression.operands());
                    if(operandsResult.isErr()) yield operandsResult.map(o -> null);
                    yield Result.ok(new ASTNode.Expression.SExpr(
                            expressionResult.unwrap(), operandsResult.unwrap(), MetaData.ofUnresolved(lineChar, LangType.UNDEFINED)
                    ));
                }
                case GrammarForm.Operation.Op op -> {
                    var opResult = parseOperation();
                    if(opResult.isErr()) yield opResult.map(o -> null);
                    var operandsResult = parseOperands(sExpression.operands());
                    if(operandsResult.isErr()) yield operandsResult.map(o -> null);
                    yield Result.ok(new ASTNode.Expression.OExpr(
                            opResult.unwrap(), operandsResult.unwrap(), MetaData.ofUnresolved(lineChar, LangType.UNDEFINED)
                    ));
                }
            };

            if(parsedExprResult.isErr()) return parsedExprResult;

            // ::= ')'
            if(consumeRightParen().isErr()) return consumeRightParen().map(v -> null);

            return parsedExprResult;
        }

        //TODO add array and tuple literals
        // This is also messy af
        private Result<ASTNode.Expression, ParseError> parseVExpression(GrammarForm.Expression.VExpr vExpression) {
            LineChar lineChar = getLineChar();

            var tokenResult = advance();
            if(tokenResult.isErr()) return tokenResult.map(t -> null);
            Token token = tokenResult.unwrap();
            return switch (token) {
                case Token(TokenType.Literal lit, _, _, _) when lit == TokenType.Literal.True ->
                        Result.ok(new ASTNode.Expression.VExpr(new ASTNode.Value.Bool(true), MetaData.ofResolved(lineChar, LangType.BOOL)));

                case Token(TokenType.Literal lit, _, _, _) when lit == TokenType.Literal.False ->
                        Result.ok(new ASTNode.Expression.VExpr(new ASTNode.Value.Bool(false), MetaData.ofResolved(lineChar, LangType.BOOL)));

                case Token(
                        TokenType.Literal lit, TokenData.FloatData fd, _, _
                ) when lit == TokenType.Literal.Float ->
                        Result.ok(new ASTNode.Expression.VExpr(new ASTNode.Value.F64(fd.data()), MetaData.ofResolved(lineChar, LangType.F64)));

                case Token(
                        TokenType.Literal lit, TokenData.IntegerData id, _, _
                ) when lit == TokenType.Literal.Integer -> Result.ok(new ASTNode.Expression.VExpr(
                        new ASTNode.Value.F64(id.data()), MetaData.ofResolved(lineChar, LangType.I64)
                ));

                case Token(
                        TokenType.Literal lit, TokenData.StringData id, _, _
                ) when lit == TokenType.Literal.Identifier -> Result.ok(new ASTNode.Expression.VExpr(new ASTNode.Value.Identifier(
                        Symbol.ofUnresolved(id.data())), MetaData.ofResolved(lineChar, LangType.UNDEFINED)
                ));

                case Token(
                        TokenType.Literal lit, TokenData.StringData id, _, _
                ) when lit == TokenType.Literal.Nil ->
                        Result.ok(new ASTNode.Expression.VExpr(new ASTNode.Value.Nil(), MetaData.ofResolved(lineChar, LangType.NIL)));

                default -> Result.err(ParseError.expected(token, "Value Expression"));
            };
        }

        private Result<ASTNode.Expression, ParseError> parseIterExpression(GrammarForm.Expression.IterExpr iterExpression) {
            throw new UnsupportedOperationException("Iter not implemented");
        }

        private Result<ASTNode.Expression, ParseError> parseMatchExpression(GrammarForm.Expression.MatchExpr matchExpression) {
            throw new UnsupportedOperationException("Match not implemented");
        }

        private Result<ASTNode.Expression, ParseError> parseLambdaExpression(GrammarForm.Expression.LambdaExpr lambdaExpression) {
            LineChar lineChar = getLineChar();

            // ::= '('
            if(consumeLeftParen().isErr()) return consumeLeftParen().map(v -> null);

            // ::=  '=>'
            if(consume(TokenType.LAMBDA_ARROW).isErr()) return consume(TokenType.LAMBDA_ARROW).map(t -> null);

            // ::= [ (':' Type) ]
            Result<LangType, ParseError> typeResult;
            if (lambdaExpression.hasType()) {
                typeResult = parseType(true);
                if(typeResult.isErr()) return typeResult.map(t -> null);
            } else {
                typeResult = Result.ok(LangType.UNDEFINED);
            }

            // ::= '|' { Parameter } '|' Expr
            var lambdaResult = parseLambdaFormExpression(lambdaExpression.form());
            if(lambdaResult.isErr()) return lambdaResult.map(l -> null);

            // ::= ')'
            if(consumeRightParen().isErr()) return consumeRightParen().map(v -> null);

            return Result.ok(new ASTNode.Expression.LExpr(
                    lambdaResult.unwrap().parameters(), lambdaResult.unwrap().body(), false, MetaData.ofUnresolved(lineChar, typeResult.unwrap())
            ));
        }

        // ::= '|' { Parameter } '|' Expr
        private Result<ASTNode.Expression.LExpr, ParseError> parseLambdaFormExpression(GrammarForm.Expression.LambdaFormExpr lambdaFormExpr) {
            LineChar lineChar = getLineChar();

            // ::= '|'
            if(consume(TokenType.BAR).isErr()) return consume(TokenType.BAR).map(t -> null);

            // ::= { Parameter }
            var parametersResult = parseParameters(lambdaFormExpr.parameters().params());
            if(parametersResult.isErr()) return parametersResult.map(p -> null);

            // ::= '|'
            if(consume(TokenType.BAR).isErr()) return consume(TokenType.BAR).map(t -> null);
            // ::= Expr
            var exprResult = parseExpression(lambdaFormExpr.expression());
            if(exprResult.isErr()) return exprResult.map(e -> null);

            return Result.ok(new ASTNode.Expression.LExpr(parametersResult.unwrap(), exprResult.unwrap(), true, MetaData.ofUnresolved(lineChar, LangType.UNDEFINED)));
        }

        /*--------
        | UTILITY |
         --------*/

        private Result<String, ParseError> parseIdentifier() {
            var consumeResult = consume(TokenType.IDENTIFIER);
            if(consumeResult.isErr()) return consumeResult.map(t -> null);
            return switch (consumeResult.unwrap()) {
                case Token(_, TokenData data, _, _) when data instanceof TokenData.StringData(String s) -> Result.ok(s);
                default -> Result.err(ParseError.expected(peek(), "Identifier"));
            };
        }

        private Result<List<ASTNode.Modifier>, ParseError> parseModifiers(int count) {
            if (count == 0) { return Result.ok(List.of()); }

            List<ASTNode.Modifier> modifiers = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                var advanceResult = advance();
                if(advanceResult.isErr()) return advanceResult.map(t -> null);
                if (advanceResult.unwrap().tokenType() instanceof TokenType.Modifier mod) {
                    modifiers.add(mod.astModValue);
                } else { return Result.err(ParseError.expected(peek(), "Modifier")); }
            }
            return Result.ok(modifiers);
        }

        private Result<List<ASTNode.Expression>, ParseError> parseOperands(List<GrammarForm.Expression> operands) {
            List<ASTNode.Expression> parsedOperands = new ArrayList<>(operands.size());
            for (var opr : operands) {
                var operandResult = parseExpression(opr);
                if(operandResult.isErr()) return operandResult.map(o -> null);
                parsedOperands.add(operandResult.unwrap());
            }
            return Result.ok(parsedOperands);
        }

        private Result<ASTNode.Operation, ParseError> parseOperation() {
            var advanceResult = advance();
            if(advanceResult.isErr()) return advanceResult.map(t -> null);
            switch (advanceResult.unwrap().tokenType()) {
                case TokenType.Operation op -> { return Result.ok(op.astOpValue); }
                default -> { return Result.err(ParseError.expected(peek(), "Operation")); }
            }
        }

        private Result<List<ASTNode.Argument>, ParseError> parseArguments(List<GrammarForm.Arg> argForms) {
            if (argForms.isEmpty()) { return Result.ok(List.of()); }

            List<ASTNode.Argument> arguments = new ArrayList<>(argForms.size());
            for (var arg : argForms) {
                var modifiersResult = parseModifiers(arg.modifierCount());
                if(modifiersResult.isErr()) return modifiersResult.map(m -> null);
                var expressionResult = parseExpression(arg.expression());
                if(expressionResult.isErr()) return expressionResult.map(e -> null);
                arguments.add(new ASTNode.Argument(modifiersResult.unwrap(), expressionResult.unwrap()));
            }
            return Result.ok(arguments);
        }

        private Result<List<ASTNode.Parameter>, ParseError> parseParameters(List<GrammarForm.Param> paramForms) {
            if (paramForms.isEmpty()) { return Result.ok(List.of()); }

            List<ASTNode.Parameter> parameters = new ArrayList<>(paramForms.size());

            for (var param : paramForms) {
                var modifiersResult = parseModifiers(param.modifierCount());
                if(modifiersResult.isErr()) return modifiersResult.map(m -> null);
                var identifierResult = parseIdentifier();
                if(identifierResult.isErr()) return identifierResult.map(i -> null);
                Symbol identifier = Symbol.ofResolved(identifierResult.unwrap()); // Resolved/Defined

                Result<LangType, ParseError> typeResult;
                if (param.hasType()) {
                    typeResult = parseType(true);
                    if(typeResult.isErr()) return typeResult.map(t -> null);
                } else {
                    typeResult = Result.ok(LangType.UNDEFINED);
                }

                parameters.add(new ASTNode.Parameter(modifiersResult.unwrap(), identifier, typeResult.unwrap()));
            }

            return Result.ok(parameters);

        }


        /*----------
        | Accessors |
         ----------*/

        private Result<List<ASTNode.AccessType>, ParseError> parseNamesAccess(int count) {
            if (count == 0) { return Result.ok(List.of()); }

            List<ASTNode.AccessType> accesses = new ArrayList<>(count);
            for (int i = 0; i < count; ++i) {
                var advanceResult = advance();
                if(advanceResult.isErr()) return advanceResult.map(t -> null);
                switch (advanceResult.unwrap()) {
                    case Token(
                            TokenType t, TokenData.StringData str, _, _
                    ) when t == TokenType.Literal.Identifier -> {
                        accesses.add(new ASTNode.AccessType.Namespace(Symbol.ofUnresolved(str.data())));
                        if(consume(TokenType.NAME_SPACE_ACCESS).isErr()) return consume(TokenType.NAME_SPACE_ACCESS).map(c -> null);
                    }
                    default -> { return Result.err(ParseError.expected(peek(), "Namespace Identifier")); }
                }
            }
            return Result.ok(accesses);
        }

        private Result<List<ASTNode.AccessType>, ParseError> parseAccessChain(List<GrammarForm.MemberAccess> accessForms) {
            if (accessForms.isEmpty()) { return Result.ok(List.of()); }

            List<ASTNode.AccessType> accesses = new ArrayList<>(accessForms.size());
            for (var acc : accessForms) {
                switch (acc) {
                    case GrammarForm.MemberAccess.Identifier _, GrammarForm.MemberAccess.FunctionAccess _ -> {
                        if(consume(acc instanceof GrammarForm.MemberAccess.Identifier
                                ? TokenType.IDENTIFIER_ACCESS
                                : TokenType.FUNCTION_ACCESS
                        ).isErr()) return consume(acc instanceof GrammarForm.MemberAccess.Identifier
                                ? TokenType.IDENTIFIER_ACCESS
                                : TokenType.FUNCTION_ACCESS
                        ).map(c -> null);
                        var identifierResult = parseIdentifier();
                        if(identifierResult.isErr()) return identifierResult.map(i -> null);
                        Symbol identifier = Symbol.ofUnresolved(identifierResult.unwrap()); //unresolved as we haven't validated existence
                        accesses.add(new ASTNode.AccessType.Identifier(identifier));
                    }
                    case GrammarForm.MemberAccess.FunctionCall fc -> {
                        if(consume(TokenType.FUNCTION_ACCESS).isErr()) return consume(TokenType.FUNCTION_ACCESS).map(c -> null);
                        var identifierResult = parseIdentifier();
                        if(identifierResult.isErr()) return identifierResult.map(i -> null);
                        Symbol identifier = Symbol.ofUnresolved(identifierResult.unwrap()); //unresolved as we haven't validated existence
                        if(consumeLeftBracket().isErr()) return consumeLeftBracket().map(b -> null);
                        var argumentsResult = parseArguments(fc.arguments());
                        if(argumentsResult.isErr()) return argumentsResult.map(a -> null);
                        if(consumeRightBracket().isErr()) return consumeRightBracket().map(b -> null);
                        accesses.add(new ASTNode.AccessType.FunctionCall(identifier, argumentsResult.unwrap()));
                    }
                }
            }
            return Result.ok(accesses);
        }





        /*------
        | TYPES |
         ______*/

        private Result<LangType, ParseError> parseType(boolean consumeColon) {
            if (consumeColon) {
                var consumeResult = consume(TokenType.Syntactic.Colon);
                if(consumeResult.isErr()) return consumeResult.map(c -> null);
            }

            Token token = peek();
            return switch (token.tokenType()) {
                // Fn<Type>
                case TokenType.BuiltIn.Fn -> {
                    if(advance().isErr()) yield advance().map(a -> null);
                    yield parseFunctionType();
                }
                // Array<Type>
                case TokenType.BuiltIn.Array -> {
                    if(advance().isErr()) yield advance().map(a -> null);
                    yield parseArrayType();
                }
                // Type
                case TokenType.Literal.Identifier -> {
                    if (token.tokenData() instanceof TokenData.StringData(String identifier)) {
                        LangType type = parseTypeFromString(identifier);
                        if(advance().isErr()) yield advance().map(a -> null);
                        yield Result.ok(type);
                    } else { yield Result.err(ParseError.of(token, "Error<Internal>: Identifier should have StringData")); }
                }
                default -> Result.err(ParseError.of(token, "Expected Type Identifier"));
            };
        }

        // ::= '<' { Identifier } ';' Identifier } '>'
        private Result<LangType, ParseError> parseFunctionType() {
            // ::= '<'
            if(consume(TokenType.LEFT_ANGLE_BRACKET).isErr()) return consume(TokenType.LEFT_ANGLE_BRACKET).map(t -> null);

            // ::= { Identifier }

            List<LangType> paramTypes = new ArrayList<>(5);
            while (peek().tokenType() == TokenType.IDENTIFIER) {
                var typeResult = parseType(false);
                if(typeResult.isErr()) return typeResult;
                paramTypes.add(typeResult.unwrap());
            }

            // ::= ';'
            if(consume(TokenType.Syntactic.SemiColon).isErr()) return consume(TokenType.Syntactic.SemiColon).map(t -> null);

            // ::= Identifier
            var rtnTypeResult = parseType(false);
            if(rtnTypeResult.isErr()) return rtnTypeResult;

            // ::= '>'
            if(consume(TokenType.RIGHT_ANGLE_BRACKET).isErr()) return consume(TokenType.RIGHT_ANGLE_BRACKET).map(t -> null);

            return Result.ok(LangType.ofFunction(paramTypes, rtnTypeResult.unwrap()));
        }


        private Result<LangType, ParseError> parseArrayType() {
            var typeResult = parseType(false);
            if(typeResult.isErr()) return typeResult;
            return Result.ok(LangType.ofArray(typeResult.unwrap()));
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
