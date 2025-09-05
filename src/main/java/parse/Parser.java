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


        private Result<Boolean, CompExcept> advancePastEndCheck() {
            return !haveNext()
                    ? Result.err(ParseError.of(peek(), "Advanced Past End"))
                    : Result.ok(true);
        }

        // Extra check to help catch common errors when parsing
        private Result<Boolean, CompExcept> openContainerTokenCheck() {
            return switch (peek().tokenType()) {
                case TokenType.Syntactic.LeftParen, TokenType.Syntactic.RightParen,
                     TokenType.Syntactic.LeftBrace, TokenType.Syntactic.RightBrace,
                     TokenType.Syntactic.LeftBracket, TokenType.Syntactic.RightBracket -> Result.err(ParseError.of(
                        peek(),
                        "\"(, [, {, }, ], )\" should only be advanced via related consumer functions"
                ));
                default -> Result.ok(true);
            };
        }

        private Result<Token, CompExcept> advance() {
            switch (advancePastEndCheck()) {
                case Result.Err<Boolean, CompExcept> err -> { return err.castErr(); }
                case Result.Ok<Boolean, CompExcept> _ -> {
                    return switch (openContainerTokenCheck()) {
                        case Result.Err<Boolean, CompExcept> err -> err.castErr();
                        case Result.Ok<Boolean, CompExcept> _ -> {
                            current += 1;
                            yield Result.ok(tokens.get(current - 1));
                        }
                    };
                }
            }
        }

        private LineChar getLineChar() {
            Token token = peek();
            return new LineChar(token.line(), token.chr());
        }

        private void pushWarning(String warning) {
            warnings.add(warning);
        }

        private Result<Boolean, CompExcept> consumeLeftParen() {
            if (!check(TokenType.Syntactic.LeftParen)) {
                return Result.err(ParseError.expected(peek(), "Left Paren"));
            }
            current += 1;
            depth += 1;
            return Result.ok(true);
        }

        private Result<Boolean, CompExcept> consumeRightParen() {
            if (!check(TokenType.Syntactic.RightParen)) {
                return Result.err(ParseError.expected(peek(), "Right Paren"));
            }
            current += 1;
            depth -= 1;
            return Result.ok(true);
        }

        private Result<Boolean, CompExcept> consumeLeftBracket() {
            if (!check(TokenType.Syntactic.LeftBracket)) {
                return Result.err(ParseError.expected(peek(), "Left Bracket"));
            }
            current += 1;
            return Result.ok(true);
        }

        private Result<Boolean, CompExcept> consumeRightBracket() {
            if (!check(TokenType.Syntactic.RightBracket)) {
                return Result.err(ParseError.expected(peek(), "Right Bracket"));
            }
            current += 1;
            return Result.ok(true);
        }

        private Result<Boolean, CompExcept> consumeLeftBrace() {
            if (!check(TokenType.Syntactic.LeftBrace)) {
                return Result.err(ParseError.expected(peek(), "Left Brace"));
            }
            current += 1;
            return Result.ok(true);
        }

        private Result<Boolean, CompExcept> consumeRightBrace() {
            if (!check(TokenType.Syntactic.RightBrace)) {
                return Result.err(ParseError.expected(peek(), "Right Brace"));
            }
            current += 1;
            return Result.ok(true);
        }

        private boolean check(TokenType checkType) {
            if (haveNext()) {
                TokenType peekType = peek().tokenType();
                return checkType == peekType;
            } else { return false; }
        }

        private Result<Token, CompExcept> consume(TokenType tokenType) {
            switch (advancePastEndCheck()) {
                case Result.Err<Boolean, CompExcept> err -> { return err.castErr(); }
                case Result.Ok<Boolean, CompExcept> _ -> {
                    return switch (openContainerTokenCheck()) {
                        case Result.Err<Boolean, CompExcept> err -> err.castErr();
                        case Result.Ok<Boolean, CompExcept> _ when check(tokenType) -> advance();
                        default -> Result.err(ParseError.expected(peek(), tokenType.toString()));
                    };
                }
            }
        }

        /*--------------
        | ENTRY METHODS |
         --------------*/

        public Result<ASTNode.CompilationUnit, CompExcept> process() {
            List<ASTNode> rootExpressions = new ArrayList<>();

            while (haveNext()) {
                var subParser = new SubParser(this::peekN);
                switch (Grammar.findNextMatch(subParser)) {
                    case Result.Err<GrammarMatch, CompExcept> err -> { return err.castErr(); }
                    case Result.Ok(GrammarMatch.Found(var form)) -> {
                        switch (parseGrammarPattern(form)) {
                            case Result.Err<ASTNode, CompExcept> err -> { return err.castErr(); }
                            case Result.Ok(ASTNode node) -> rootExpressions.add(node);
                        }
                    }
                    case Result.Ok(GrammarMatch.None _) -> {
                        return Result.err(ParseError.of(peek(), "Valid Grammar Form"));
                    }
                }
            }
            return Result.ok(new ASTNode.CompilationUnit(rootExpressions));
        }

        public Result<ASTNode, CompExcept> parseGrammarPattern(GrammarForm form) {
            return switch (form) {
                case GrammarForm.Expression expression -> parseExpression(expression).map(Function.identity());
                case GrammarForm.Statement statement -> parseStatement(statement).map(Function.identity());
                default -> Result.err(ParseError.of(
                        peek(),
                        "Error<Internal>: GrammarForm to parse should have root of expression or statement"
                ));
            };
        }

        private Result<ASTNode, CompExcept> parseStatement(GrammarForm.Statement statement) {
            return switch (statement) {
                case GrammarForm.Statement.Let let -> parseLetStatement(let).map(Function.identity());
                case GrammarForm.Statement.Reassign reassign ->
                        parseReassignStatement(reassign).map(Function.identity());
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
            LineChar lineChar = getLineChar();

            // ::= let
            if (consume(TokenType.Definition.Let).isErr()) { return consume(TokenType.Definition.Let).castErr(); }

            // ::= Identifier
            var identifierResult = parseIdentifier();
            if (identifierResult.isErr()) { return identifierResult.castErr(); }
            Symbol identifier = Symbol.ofResolved(identifierResult.unwrap());

            // ::= [ ':' Type ]
            Result<LangType, CompExcept> typeResult = letStatement.hasType()
                    ? parseType(true)
                    : Result.ok(LangType.UNDEFINED);
            if (typeResult.isErr()) { return typeResult.castErr(); }

            // ::= { Modifier }
            var modifiersResult = parseModifiers(letStatement.modifierCount());
            if (modifiersResult.isErr()) { return modifiersResult.castErr(); }

            // ::= '='
            if (consume(TokenType.Syntactic.Equal).isErr()) { return consume(TokenType.Syntactic.Equal).castErr(); }

            // ::= Expr
            var assignmentResult = parseExpression(letStatement.expression());
            if (assignmentResult.isErr()) { return assignmentResult.castErr(); }

            return Result.ok(new ASTNode.Statement.Let(
                    identifier,
                    modifiersResult.unwrap(),
                    assignmentResult.unwrap(),
                    MetaData.ofUnresolved(lineChar,
                            typeResult.unwrap()))
            );
        }

        // ::= Identifier ':=' Expr
        // Note: this is only for local identifiers and can be considered a sugared form of reassignment
        private Result<ASTNode.Statement.Assign, CompExcept> parseReassignStatement(
                GrammarForm.Statement.Reassign reassignStatement
        ) {
            LineChar lineChar = getLineChar();

            // ::= Identifier
            var identifierResult = parseIdentifier();
            if (identifierResult.isErr()) { return identifierResult.castErr(); }
            Symbol identifier = Symbol.ofUnresolved(identifierResult.unwrap());

            // ::= ':='
            if (consume(TokenType.REASSIGNMENT).isErr()) { return consume(TokenType.REASSIGNMENT).castErr(); }

            // ::= Expr
            var assignmentResult = parseExpression(reassignStatement.assignment());
            if (assignmentResult.isErr()) { return assignmentResult.castErr(); }

            return Result.ok(new ASTNode.Statement.Assign(
                    identifier,
                    assignmentResult.unwrap(),
                    MetaData.ofUnresolved(lineChar, LangType.UNDEFINED))
            );
        }




        /*------------
        | EXPRESSIONS |
        -------------*/

        // ::= '{' { Expr | Stmnt } '}'
        private Result<ASTNode.Expression, CompExcept> parseBlockExpression(
                GrammarForm.Expression.BlockExpr blockExpression
        ) {
            LineChar lineChar = getLineChar();

            // ::= '{'
            if (consumeLeftBrace().isErr()) { return consumeLeftBrace().castErr(); }

            // ::= { Expr | Stmnt }
            List<ASTNode> contents = new ArrayList<>(blockExpression.members().size());
            for (var member : blockExpression.members()) {
                switch (parseGrammarPattern(member)) {
                    case Result.Err<ASTNode, CompExcept> err -> { return err.castErr(); }
                    case Result.Ok(ASTNode node) -> contents.add(node);
                }
            }

            // ::= '}'
            if (consumeRightBrace().isErr()) { return consumeRightBrace().castErr(); }

            return Result.ok(new ASTNode.Expression.BExpr(contents, MetaData.ofUnresolved(lineChar, LangType.UNDEFINED)));
        }

        private Result<ASTNode.Expression, CompExcept> parseCondExpression(GrammarForm.Expression.CondExpr
                                                                                   condExpression) {
            LineChar lineChar = getLineChar();

            // ::= '('
            if (consumeLeftParen().isErr()) { return consumeLeftParen().castErr(); }

            // ::= Expr
            var predicateResult = parseExpression(condExpression.predicateExpression());
            if (predicateResult.isErr()) { return predicateResult.castErr(); }

            // ::= '->' Expr [ ':' Expr ]
            var predicateFormResult = parsePredicateForm(condExpression.predicateForm());
            if (predicateFormResult.isErr()) { return predicateFormResult.castErr(); }

            // ::= ')'
            if (consumeRightParen().isErr()) { return consumeRightParen().castErr(); }

            return Result.ok(new ASTNode.Expression.PExpr(
                    predicateResult.unwrap(),
                    predicateFormResult.unwrap(),
                    MetaData.ofUnresolved(lineChar, LangType.UNDEFINED))
            );
        }


        // ::= '->' Expr [ ':' Expr ]
        private Result<ASTNode.Expression.PredicateForm, CompExcept> parsePredicateForm(
                GrammarForm.PredicateForm predicateForm
        ) {
            LineChar lineChar = getLineChar();

            Optional<ASTNode.Expression> thenExpr;
            if (predicateForm.thenForm().isPresent()) {
                switch (consume(TokenType.RIGHT_ARROW)) {
                    case Result.Err<Token, CompExcept> err -> { return err.castErr(); }
                    case Result.Ok<Token, CompExcept> _ -> {
                        switch (parseExpression(predicateForm.thenForm().get())) {
                            case Result.Err<ASTNode.Expression, CompExcept> err -> { return err.castErr(); }
                            case Result.Ok(ASTNode.Expression expr) -> thenExpr = Optional.of(expr);
                        }
                    }
                }
            } else { thenExpr = Optional.empty(); }

            Optional<ASTNode.Expression> elseExpr;
            if (predicateForm.elseForm().isPresent()) {
                switch (consume(TokenType.Syntactic.Colon)) {
                    case Result.Err<Token, CompExcept> err -> { return err.castErr(); }
                    case Result.Ok<Token, CompExcept> _ -> {
                        switch (parseExpression(predicateForm.elseForm().get())) {
                            case Result.Err<ASTNode.Expression, CompExcept> err -> { return err.castErr(); }
                            case Result.Ok(ASTNode.Expression expr) -> elseExpr = Optional.of(expr);
                        }
                    }
                }
            } else { elseExpr = Optional.empty(); }

            return Result.ok(new ASTNode.Expression.PredicateForm(
                    thenExpr,
                    elseExpr,
                    MetaData.ofUnresolved(lineChar, LangType.UNDEFINED))
            );
        }

        //::=  [ NamespaceAccess ] [ AccessChain ]
        private Result<ASTNode.Expression, CompExcept> parseMExpression(GrammarForm.Expression.MExpr mExpression) {
            LineChar lineChar = getLineChar();

            List<ASTNode.AccessType> accesses =
                    new ArrayList<>(mExpression.namespaceDepth() + mExpression.accessChain().size());

            // ::= [ NamespaceAccess ]
            var namespaceResult = parseNamesAccess(mExpression.namespaceDepth());
            if (namespaceResult.isErr()) { return namespaceResult.castErr(); }
            accesses.addAll(namespaceResult.unwrap());

            // ::= [ AccessChain ]
            var accessChainResult = parseAccessChain(mExpression.accessChain());
            if (accessChainResult.isErr()) { return accessChainResult.castErr(); }
            accesses.addAll(accessChainResult.unwrap());

            return Result.ok(new ASTNode.Expression.MExpr(accesses, MetaData.ofUnresolved(lineChar, LangType.UNDEFINED)));
        }

        // ::= '(' Expr | Operation { Expr }
        private Result<ASTNode.Expression, CompExcept> parseSExpression(GrammarForm.Expression.SExpr sExpression) {
            LineChar lineChar = getLineChar();

            // ::= '('
            switch (consumeLeftParen()) {
                case Result.Err<Boolean, CompExcept> err -> { return err.castErr(); }
                case Result.Ok<Boolean, CompExcept> _ -> { /* continue */ }
            }

            // Expr | Operation { Expr }
            Result<ASTNode.Expression, CompExcept> parsedExprResult = switch (sExpression.operation()) {
                case GrammarForm.Operation.ExprOp exprOp -> {
                    var expressionResult = parseExpression(exprOp.expression());
                    if (expressionResult.isErr()) yield expressionResult.castErr();

                    var operandsResult = parseOperands(sExpression.operands());
                    if (operandsResult.isErr()) yield operandsResult.castErr();

                    yield Result.ok(new ASTNode.Expression.SExpr(
                            expressionResult.unwrap(),
                            operandsResult.unwrap(),
                            MetaData.ofUnresolved(lineChar, LangType.UNDEFINED)
                    ));
                }

                case GrammarForm.Operation.Op op -> {
                    var opResult = parseOperation();
                    if (opResult.isErr()) yield opResult.castErr();

                    var operandsResult = parseOperands(sExpression.operands());
                    if (operandsResult.isErr()) yield operandsResult.castErr();

                    yield Result.ok(new ASTNode.Expression.OExpr(
                            opResult.unwrap(),
                            operandsResult.unwrap(),
                            MetaData.ofUnresolved(lineChar, LangType.UNDEFINED)
                    ));
                }
            };

            if (parsedExprResult.isErr()) return parsedExprResult;

            // ::= ')'
            return switch (consumeRightParen()) {
                case Result.Err<Boolean, CompExcept> err -> err.castErr();
                case Result.Ok<Boolean, CompExcept> _ -> parsedExprResult;
            };
        }

        //TODO add array and tuple literals
        // This is also messy af

        // @formatter:off
        private Result<ASTNode.Expression, CompExcept> parseVExpression(GrammarForm.Expression.VExpr vExpression) {
            LineChar lineChar = getLineChar();

            var tokenResult = advance();
            if (tokenResult.isErr()) return tokenResult.castErr();
            Token token = tokenResult.unwrap();
            return switch (token) {
                case Token(TokenType.Literal lit, _, _, _) when lit == TokenType.Literal.True ->
                        Result.ok(new ASTNode.Expression.VExpr(new ASTNode.Value.Bool(true), MetaData.ofResolved(lineChar, LangType.BOOL)));
                case Token(TokenType.Literal lit, _, _, _) when lit == TokenType.Literal.False ->
                        Result.ok(new ASTNode.Expression.VExpr(new ASTNode.Value.Bool(false), MetaData.ofResolved(lineChar, LangType.BOOL)));

                case Token(TokenType.Literal lit, TokenData.FloatData fd, _, _) when lit == TokenType.Literal.Float ->
                        Result.ok(new ASTNode.Expression.VExpr(new ASTNode.Value.F64(fd.data()), MetaData.ofResolved(lineChar, LangType.F64)));

                case Token(TokenType.Literal lit, TokenData.IntegerData id, _, _) when lit == TokenType.Literal.Integer ->
                        Result.ok(new ASTNode.Expression.VExpr(new ASTNode.Value.F64(id.data()), MetaData.ofResolved(lineChar, LangType.I64)));

                case Token(TokenType.Literal lit, TokenData.StringData id, _, _) when lit == TokenType.Literal.Identifier ->
                        Result.ok(new ASTNode.Expression.VExpr(new ASTNode.Value.Identifier(Symbol.ofUnresolved(id.data())), MetaData.ofResolved(lineChar, LangType.UNDEFINED)));

                case Token(TokenType.Literal lit, TokenData.StringData id, _, _) when lit == TokenType.Literal.Nil ->
                        Result.ok(new ASTNode.Expression.VExpr(new ASTNode.Value.Nil(), MetaData.ofResolved(lineChar, LangType.NIL)));

                default -> Result.err(ParseError.expected(token, "Value Expression"));
            };
        }
        // @formatter:on

        private Result<ASTNode.Expression, CompExcept> parseIterExpression(GrammarForm.Expression.IterExpr iterExpression) {
            throw new UnsupportedOperationException("Iter not implemented");
        }

        private Result<ASTNode.Expression, CompExcept> parseMatchExpression(GrammarForm.Expression.MatchExpr matchExpression) {
            throw new UnsupportedOperationException("Match not implemented");
        }

        private Result<ASTNode.Expression, CompExcept> parseLambdaExpression(GrammarForm.Expression.LambdaExpr lambdaExpression) {
            LineChar lineChar = getLineChar();

            // ::= '('
            if (consumeLeftParen().isErr()) { return consumeLeftParen().castErr(); }

            // ::= '=>'
            if (consume(TokenType.LAMBDA_ARROW).isErr()) { return consume(TokenType.LAMBDA_ARROW).castErr(); }

            // ::= [ ':' Type ]
            Result<LangType, CompExcept> typeResult = lambdaExpression.hasType()
                    ? parseType(true)
                    : Result.ok(LangType.UNDEFINED);
            if (typeResult.isErr()) { return typeResult.castErr(); }

            // ::= '|' { Parameter } '|' Expr
            var lambdaResult = parseLambdaFormExpression(lambdaExpression.form());
            if (lambdaResult.isErr()) { return lambdaResult.castErr(); }

            // ::= ')'
            if (consumeRightParen().isErr()) { return consumeRightParen().castErr(); }

            return Result.ok(new ASTNode.Expression.LExpr(
                    lambdaResult.unwrap().parameters(),
                    lambdaResult.unwrap().body(),
                    false,
                    MetaData.ofUnresolved(lineChar, typeResult.unwrap())
            ));
        }

        // ::= '|' { Parameter } '|' Expr
        private Result<ASTNode.Expression.LExpr, CompExcept> parseLambdaFormExpression
        (GrammarForm.Expression.LambdaFormExpr lambdaFormExpr) {
            LineChar lineChar = getLineChar();

            // ::= '|'
            if (consume(TokenType.BAR).isErr()) { return consume(TokenType.BAR).castErr(); }

            // ::= { Parameter }
            var parametersResult = parseParameters(lambdaFormExpr.parameters().params());
            if (parametersResult.isErr()) { return parametersResult.castErr(); }

            // ::= '|'
            if (consume(TokenType.BAR).isErr()) { return consume(TokenType.BAR).castErr(); }

            // ::= Expr
            var exprResult = parseExpression(lambdaFormExpr.expression());
            if (exprResult.isErr()) { return exprResult.castErr(); }

            return Result.ok(new ASTNode.Expression.LExpr(
                    parametersResult.unwrap(),
                    exprResult.unwrap(),
                    true,
                    MetaData.ofUnresolved(lineChar, LangType.UNDEFINED))
            );
        }

        /*--------
        | UTILITY |
         --------*/

        private Result<String, CompExcept> parseIdentifier() {
            return switch (consume(TokenType.IDENTIFIER)) {
                case Result.Err<Token, CompExcept> err -> err.castErr();
                case Result.Ok(Token(_, TokenData data, _, _)) when data instanceof TokenData.StringData(String s) ->
                        Result.ok(s);
                case Result.Ok<Token, CompExcept> _ -> Result.err(ParseError.expected(peek(), "Identifier"));
            };
        }

        private Result<List<ASTNode.Modifier>, CompExcept> parseModifiers(int count) {
            if (count == 0) { return Result.ok(List.of()); }

            List<ASTNode.Modifier> modifiers = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                switch (advance()) {
                    case Result.Err<Token, CompExcept> err -> { return err.castErr(); }
                    case Result.Ok(Token token) when token.tokenType() instanceof TokenType.Modifier mod ->
                            modifiers.add(mod.astModValue);
                    case Result.Ok<Token, CompExcept> _ -> {
                        return Result.err(ParseError.expected(peek(), "Modifier"));
                    }
                }
            }
            return Result.ok(modifiers);
        }

        private Result<List<ASTNode.Expression>, CompExcept> parseOperands(List<GrammarForm.Expression> operands) {
            List<ASTNode.Expression> parsedOperands = new ArrayList<>(operands.size());
            for (var opr : operands) {
                switch (parseExpression(opr)) {
                    case Result.Err<ASTNode.Expression, CompExcept> err -> { return err.castErr(); }
                    case Result.Ok(ASTNode.Expression expr) -> parsedOperands.add(expr);
                }
            }
            return Result.ok(parsedOperands);
        }

        private Result<ASTNode.Operation, CompExcept> parseOperation() {
            return switch (advance()) {
                case Result.Err<Token, CompExcept> err -> err.castErr();
                case Result.Ok(Token token) when token.tokenType() instanceof TokenType.Operation op ->
                        Result.ok(op.astOpValue);
                case Result.Ok<Token, CompExcept> _ -> Result.err(ParseError.expected(peek(), "Operation"));
            };
        }

        private Result<List<ASTNode.Argument>, CompExcept> parseArguments(List<GrammarForm.Arg> argForms) {
            if (argForms.isEmpty()) { return Result.ok(List.of()); }

            List<ASTNode.Argument> arguments = new ArrayList<>(argForms.size());
            for (var arg : argForms) {
                switch (parseModifiers(arg.modifierCount())) {
                    case Result.Err<List<ASTNode.Modifier>, CompExcept> err -> { return err.castErr(); }
                    case Result.Ok(List<ASTNode.Modifier> mods) -> {
                        switch (parseExpression(arg.expression())) {
                            case Result.Err<ASTNode.Expression, CompExcept> err -> { return err.castErr(); }
                            case Result.Ok(ASTNode.Expression expr) -> arguments.add(new ASTNode.Argument(mods, expr));
                        }
                    }
                }
            }
            return Result.ok(arguments);
        }

        private Result<List<ASTNode.Parameter>, CompExcept> parseParameters(List<GrammarForm.Param> paramForms) {
            if (paramForms.isEmpty()) { return Result.ok(List.of()); }

            List<ASTNode.Parameter> parameters = new ArrayList<>(paramForms.size());

            for (var param : paramForms) {
                switch (parseModifiers(param.modifierCount())) {
                    case Result.Err<List<ASTNode.Modifier>, CompExcept> err -> { return err.castErr(); }
                    case Result.Ok(List<ASTNode.Modifier> mods) -> {
                        switch (parseIdentifier()) {
                            case Result.Err<String, CompExcept> err -> { return err.castErr(); }
                            case Result.Ok(String identifier) -> {
                                Result<LangType, CompExcept> typeResult = param.hasType()
                                        ? parseType(true)
                                        : Result.ok(LangType.UNDEFINED);
                                if (typeResult.isErr()) { return typeResult.castErr(); }
                                parameters.add(new ASTNode.Parameter(mods, Symbol.ofResolved(identifier), typeResult.unwrap()));
                            }
                        }
                    }
                }
            }
            return Result.ok(parameters);
        }


        /*----------
        | Accessors |
         ----------*/

        // @formatter:off
        private Result<List<ASTNode.AccessType>, CompExcept> parseNamesAccess(int count) {
            if (count == 0) { return Result.ok(List.of()); }

            List<ASTNode.AccessType> accesses = new ArrayList<>(count);
            for (int i = 0; i < count; ++i) {
                switch (advance()) {
                    case Result.Err<Token, CompExcept> err -> { return err.castErr(); }
                    case Result.Ok(Token(TokenType tt, TokenData.StringData str, _, _)) when tt == TokenType.Literal.Identifier -> {
                        accesses.add(new ASTNode.AccessType.Namespace(Symbol.ofUnresolved(str.data())));
                        if (consume(TokenType.NAME_SPACE_ACCESS) instanceof Result.Err<Token,CompExcept> err) { return err.castErr(); }
                    }
                    default -> { return Result.err(ParseError.expected(peek(), "Namespace Identifier")); }
                    }
                }

            return Result.ok(accesses);
        }
        // @formatter:on

        private Result<List<ASTNode.AccessType>, CompExcept> parseAccessChain(List<GrammarForm.AccessType> accessForms) {
            if (accessForms.isEmpty()) { return Result.ok(List.of()); }

            List<ASTNode.AccessType> accesses = new ArrayList<>(accessForms.size());
            for (var acc : accessForms) {
                switch (acc) {
                    case GrammarForm.AccessType.Identifier _ -> {
                        switch (consume(TokenType.IDENTIFIER_ACCESS)) {
                            case Result.Err<Token, CompExcept> err -> { return err.castErr(); }
                            case Result.Ok<Token, CompExcept> _ -> {
                                switch (parseIdentifier()) {
                                    case Result.Err<String, CompExcept> err -> { return err.castErr(); }
                                    case Result.Ok(String identifier) -> {
                                        Symbol symbol = Symbol.ofUnresolved(identifier);
                                        accesses.add(new ASTNode.AccessType.Identifier(symbol));
                                    }
                                }
                            }
                        }
                    }
                    case GrammarForm.AccessType.FunctionAccessType _ -> {
                        switch (consume(TokenType.FUNCTION_ACCESS)) {
                            case Result.Err<Token, CompExcept> err -> { return err.castErr(); }
                            case Result.Ok<Token, CompExcept> _ -> {
                                switch (parseIdentifier()) {
                                    case Result.Err<String, CompExcept> err -> { return err.castErr(); }
                                    case Result.Ok(String identifier) -> {
                                        Symbol symbol = Symbol.ofUnresolved(identifier);
                                        accesses.add(new ASTNode.AccessType.Identifier(symbol));
                                    }
                                }
                            }
                        }
                    }
                    case GrammarForm.AccessType.FunctionCall fc -> {
                        switch (consume(TokenType.FUNCTION_ACCESS)) {
                            case Result.Err<Token, CompExcept> err -> { return err.castErr(); }
                            case Result.Ok<Token, CompExcept> _ -> {
                                switch (parseIdentifier()) {
                                    case Result.Err<String, CompExcept> err -> { return err.castErr(); }
                                    case Result.Ok(String identifier) -> {
                                        Symbol symbol = Symbol.ofUnresolved(identifier);

                                        if (consumeLeftBracket().isErr()) {
                                            return consumeLeftBracket().castErr();
                                        }

                                        switch (parseArguments(fc.arguments())) {
                                            case Result.Err<List<ASTNode.Argument>, CompExcept> err -> {
                                                return err.castErr();
                                            }
                                            case Result.Ok(List<ASTNode.Argument> args) -> {
                                                if (consumeRightBracket().isErr()) {
                                                    return consumeRightBracket().castErr();
                                                }
                                                accesses.add(new ASTNode.AccessType.FunctionCall(symbol, args));
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return Result.ok(accesses);
        }





        /*------
        | TYPES |
         ______*/

        private Result<LangType, CompExcept> parseType(boolean consumeColon) {
            if (consumeColon) {
                switch (consume(TokenType.Syntactic.Colon)) {
                    case Result.Err<Token, CompExcept> err -> { return err.castErr(); }
                    case Result.Ok<Token, CompExcept> _ -> { /* continue */ }
                }
            }

            Token token = peek();
            return switch (token.tokenType()) {
                case TokenType.BuiltIn.Fn -> {
                    switch (advance()) {
                        case Result.Err<Token, CompExcept> err -> { yield err.castErr(); }
                        case Result.Ok<Token, CompExcept> _ -> { yield parseFunctionType(); }
                    }
                }
                case TokenType.BuiltIn.Array -> {
                    switch (advance()) {
                        case Result.Err<Token, CompExcept> err -> { yield err.castErr(); }
                        case Result.Ok<Token, CompExcept> _ -> { yield parseArrayType(); }
                    }
                }
                case TokenType.Literal.Identifier -> {
                    if (token.tokenData() instanceof TokenData.StringData(String identifier)) {
                        LangType type = parseTypeFromString(identifier);
                        switch (advance()) {
                            case Result.Err<Token, CompExcept> err -> { yield err.castErr(); }
                            case Result.Ok<Token, CompExcept> v -> { yield Result.ok(type); }
                        }
                    } else { yield Result.err(ParseError.of(token, "Expected Type Identifier")); }
                }
                default -> Result.err(ParseError.of(token, "Expected Type Identifier"));
            };
        }

        // ::= '<' { Identifier } ';' Identifier } '>'
        private Result<LangType, CompExcept> parseFunctionType() {
            if (consume(TokenType.LEFT_ANGLE_BRACKET).isErr())
                return consume(TokenType.LEFT_ANGLE_BRACKET).castErr();

            List<LangType> paramTypes = new ArrayList<>(5);
            while (peek().tokenType() == TokenType.IDENTIFIER) {
                switch (parseType(false)) {
                    case Result.Err<LangType, CompExcept> err -> { return err.castErr(); }
                    case Result.Ok(LangType type) -> paramTypes.add(type);
                }
            }

            switch (consume(TokenType.Syntactic.SemiColon)) {
                case Result.Err<Token, CompExcept> err -> { return err.castErr(); }
                case Result.Ok<Token, CompExcept> v -> {/*Do nothing validation/consumption check */ }
            }

            return switch (parseType(false)) {
                case Result.Err<LangType, CompExcept> err -> err.castErr();
                case Result.Ok(LangType type) -> switch (consume(TokenType.RIGHT_ANGLE_BRACKET)) {
                    case Result.Err<Token, CompExcept> err -> err.castErr();
                    case Result.Ok<Token, CompExcept> _ -> Result.ok(LangType.ofFunction(paramTypes, type));
                };
            };
        }


        private Result<LangType, CompExcept> parseArrayType() {
            if (consume(TokenType.LEFT_ANGLE_BRACKET) instanceof Result.Err<Token, CompExcept> err) {
                return err.castErr();
            }

            return switch(parseType(false)) {
                case Result.Err<LangType, CompExcept> err -> err.castErr();
                case Result.Ok(LangType type)  -> {
                    if (consume(TokenType.RIGHT_ANGLE_BRACKET) instanceof Result.Err<Token, CompExcept> err) {
                        yield err.castErr();
                    }
                    yield Result.ok(LangType.ofArray(type));
                }
            };
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
