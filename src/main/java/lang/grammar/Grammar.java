package lang.grammar;

import lang.ast.ASTNode;
import parse.Parser;
import parse.Token;
import parse.TokenData;
import parse.TokenType;
import util.Result;
import util.exceptions.CError;
import util.exceptions.InternalError;
import util.exceptions.GrammarError;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.*;
import java.util.stream.Stream;

public class Grammar {
    public static final Predicate<Token> MATCH_FUNCTION_ACCESS = t -> t.tokenType() == TokenType.FUNCTION_ACCESS;
    public static final Predicate<Token> MATCH_IDENTIFIER_ACCESS = t -> t.tokenType() == TokenType.IDENTIFIER_ACCESS;
    public static final Predicate<Token> MATCH_LEFT_PAREN = t -> t.tokenType() == TokenType.LEFT_PAREN;
    public static final Predicate<Token> MATCH_RIGHT_PAREN = t -> t.tokenType() == TokenType.RIGHT_PAREN;
    public static final Predicate<Token> MATCH_LEFT_BRACKET = t -> t.tokenType() == TokenType.LEFT_BRACKET;
    public static final Predicate<Token> MATCH_RIGHT_BRACKET = t -> t.tokenType() == TokenType.RIGHT_BRACKET;
    public static final Predicate<Token> MATCH_LEFT_BRACE = t -> t.tokenType() == TokenType.LEFT_BRACE;
    public static final Predicate<Token> MATCH_RIGHT_BRACE = t -> t.tokenType() == TokenType.RIGHT_BRACE;
    public static final Predicate<Token> MATCH_LAMBDA_ARROW = t -> t.tokenType() == TokenType.LAMBDA_ARROW;
    public static final Predicate<Token> MATCH_RIGHT_ARROW = t -> t.tokenType() == TokenType.RIGHT_ARROW;
    public static final Predicate<Token> MATCH_IDENTIFIER = t -> t.tokenType() == TokenType.IDENTIFIER;
    public static final Predicate<Token> MATCH_VALUE = t -> t.tokenType() instanceof TokenType.Literal;
    public static final Predicate<Token> MATCH_MODIFIER = t -> t.tokenType() instanceof TokenType.Modifier;
    public static final Predicate<Token> MATCH_REASSIGN = t -> t.tokenType() == TokenType.REASSIGNMENT;
    public static final Predicate<Token> MATCH_IMPORT = t -> t.tokenType() == TokenType.IMPORT;
    public static final Predicate<Token> MATCH_AS = t -> t.tokenType() == TokenType.AS;
    public static final Predicate<Token> MATCH_LET = t -> t.tokenType() == TokenType.Definition.Let;
    public static final Predicate<Token> MATCH_COLON = t -> t.tokenType() == TokenType.Syntactic.Colon;
    public static final Predicate<Token> MATCH_EQUAL = t -> t.tokenType() == TokenType.Syntactic.Equal;
    public static final Predicate<Token> MATCH_BAR = t -> t.tokenType() == TokenType.BAR;
    public static final Predicate<Token> MATCH_OPERATION = t -> t.tokenType() instanceof TokenType.Operation;


    @FunctionalInterface
    public interface GrammarCheck<T, R> {
        R check(T t);
    }


    private static boolean matchTokens(Parser parser, List<Predicate<Token>> matcherFns) {
        for (int i = 0; i < matcherFns.size(); i++) {
            Token t = parser.peekN(i + 1); // 1-based index so +1
            if (!matcherFns.get(i).test(t)) { return false; }
        }
        parser.consumeN(matcherFns.size());
        return true;
    }

    private static boolean matchToken(Parser parser, Predicate<Token> matcherFn) {
        Token t = parser.peek(); // 1-based index so +1
        if (matcherFn.test(t)) {
            parser.consumeN(1);
            return true;
        } else { return false; }
    }

    private static int countWhileTrue(BooleanSupplier condition) {
        int count = 0;
        while (condition.getAsBoolean()) { count++; }
        return count;
    }


    private static int matchMultiple(Parser parser, List<Predicate<Token>> matcherFns) {
        return countWhileTrue(() -> matchTokens(parser, matcherFns));
    }

    private static int matchMultiple(Parser parser, Predicate<Token> matcherFn) {
        return countWhileTrue(() -> matchToken(parser, matcherFn));
    }


    // Note: Always check for existing token(s) before calling


    public static Result<GMatch, CError> findNextMatch(Parser p) {
        return isStatement(p)
                .flatMap((GMatch match) -> match.isFound()
                        ? Result.ok(match)
                        : Result.err(InternalError.of("Statement found nothing")))
                .orElse(() -> isExpression(p)
                        .flatMap((GMatch match) -> match.isFound()
                                ? Result.ok(match)
                                : Result.err(InternalError.of("Statement found nothing"))))
                .orElse(() -> Result.err(
                        GrammarError.expected(p.peek(), "Statement or Expression")));
    }




    /*-----------
    | STATEMENTS |
    ------------*/

    private static final List<GrammarCheck<Parser, Result<GMatch, CError>>> STATEMENT_CHECKS = List.of(
            Grammar::isLetStatement, Grammar::isReAssignStatement, Grammar::isImportStatement
    );


    private static Result<GMatch, CError> isStatement(Parser p) {
        return STATEMENT_CHECKS.stream()
                .map(f -> f.check(p))
                .filter(r -> (r.isOk() && r.unwrap().isFound()) || r.isErr())
                .findAny()
                .orElseGet(() -> Result.ok(GMatch.NONE));
    }

    //::= 'import' Identifier [ ( 'as' Identifier ) ]

    private static Result<GMatch, CError> isImportStatement(Parser p) {
        // ::= 'import' Identifier
        if (!matchTokens(p, List.of(MATCH_IMPORT, MATCH_IDENTIFIER))) { return Result.ok(GMatch.NONE); }

        // ::= [ ( 'as' Identifier ) ]
        return switch (matchToken(p, MATCH_AS)) {
            case true -> matchToken(p, MATCH_IDENTIFIER)
                    ? Result.ok(GMatch.of(new GForm.Stmt.Import(true)))
                    : Result.err(GrammarError.expected(p.peek(), "Identifier"));
            case false -> Result.ok(GMatch.of(new GForm.Stmt.Import(false)));
        };
    }


    // ::= 'let' { Modifier } Identifier [ (':' Type) ] '=' Expr
    private static Result<GMatch, CError> isLetStatement(Parser p) {
        boolean hasType = false;

        // ::= 'let'
        if (!matchToken(p, MATCH_LET)) { return Result.ok(GMatch.NONE); }

        // ::= { Modifier }
        int modifierCount = hasModifiers(p);

        // ::= Identifier
        if (!matchToken(p, MATCH_IDENTIFIER)) {
            return Result.err(GrammarError.expected(p.peek(), "Identifier"));
        }

        // ::= (':' Type)
        if (matchToken(p, MATCH_COLON)) {
            switch (isType(p)) {
                case Result.Ok(boolean foundType) when foundType -> hasType = true;
                case Result.Err<Boolean, CError> err -> { return Result.err(err.error()); }
                default -> { return Result.err(GrammarError.expected(p.peek(), "Type")); }
            }
        }

        // ::= '='
        if (!matchToken(p, MATCH_EQUAL)) { return Result.err(GrammarError.expected(p.peek(), "=")); }

        // ::= Expr
        if (isExpression(p) instanceof Result.Ok(GMatch.Found(GForm.Expr expr))) {
            return Result.ok(GMatch.of(new GForm.Stmt.Let(hasType, modifierCount, expr)));
        } else { return Result.err(GrammarError.expected(p.peek(), "Expression")); }
    }

    // ::= Identifier ':=' Expr // TODO better error when = is used for assignment on accident
    // NOTE: this only handles local reassignment
    private static Result<GMatch, CError> isReAssignStatement(Parser p) {
        // ::= Identifier ':='
        if (!matchTokens(p, List.of(MATCH_IDENTIFIER, MATCH_REASSIGN))) { return Result.ok(GMatch.NONE); }

        //Expr
        return switch (isExpression(p)) {
            case Result.Ok(GMatch.Found(GForm.Expr expr)) -> Result.ok(GMatch.of(new GForm.Stmt.Reassign(expr)));
            case Result.Err<GMatch, CError> err -> err;
            default -> Result.err(GrammarError.expected(p.peek(), "Expression"));
        };
    }

    /*------------
    | EXPRESSIONS |
    -------------*/

    private static final List<GrammarCheck<Parser, Result<GMatch, CError>>> EXPRESSION_CHECKS = List.of(
            Grammar::isBlockExpression, Grammar::isLambdaExpression, Grammar::isLambdaForm,
            Grammar::isBExpression, Grammar::isSExpression, Grammar::isVExpression, Grammar::isFExpression
    );


    private static Result<GMatch, CError> isExpression(Parser p) {
        return EXPRESSION_CHECKS.stream().map(form -> form.check(p))
                .filter(r -> (r.isOk() && r.unwrap().isFound()) || r.isErr())
                .findAny()
                .orElseGet(() -> Result.ok(GMatch.NONE));

    }

    // ::= '{' { Expr | Stmnt } '}'
    private static Result<GMatch, CError> isBlockExpression(Parser p) {
        if (!matchToken(p, MATCH_LEFT_BRACE)) { return Result.ok(GMatch.NONE); }

        List<GForm> blockMembers = new ArrayList<>(5);

        // Collect inner Expressions/Statements
        while (!matchToken(p, MATCH_RIGHT_BRACE)) {
            switch (findNextMatch(p)) {
                case Result.Ok(GMatch.Found(GForm form)) -> blockMembers.add(form);
                case Result.Err<GMatch, CError> err -> { return err; }
                default -> { return Result.err(GrammarError.expected(p.peek(), "Expression")); }
            }
        }
        return Result.ok(GMatch.of(new GForm.Expr.B(blockMembers)));
    }

    // ::=  '=>' [ (':' Type) ] '|' { Parameters } '|' Expr
    private static Result<GMatch, CError> isLambdaExpression(Parser p) {
        if (!matchTokens(p, List.of(MATCH_LEFT_PAREN, MATCH_LAMBDA_ARROW))) { return Result.ok(GMatch.NONE); }

        // ::= (':' Type)
        boolean hasType = false;

        // ::= (':' Type)
        if (matchToken(p, MATCH_COLON)) {
            switch (isType(p)) {
                case Result.Ok(boolean foundType) when foundType -> hasType = true;
                case Result.Err<Boolean, CError> err -> { return Result.err(err.error()); }
                default -> { return Result.err(GrammarError.expected(p.peek(), "Type")); }
            }
        }

        // ::= ('|' { Parameter } '|')
        return switch (isLambdaForm(p)) {
            case Result.Ok(GMatch.Found(GForm.Expr.LForm expr)) -> {
                // ::= ')'
                if (!matchToken(p, MATCH_RIGHT_PAREN)) {
                    yield Result.err(GrammarError.expected(p.peek(), ")"));
                }
                yield Result.ok(GMatch.of(new GForm.Expr.L(hasType, expr)));
            }
            case Result.Err<GMatch, CError> err -> err;
            default -> Result.err(GrammarError.expected(p.peek(), "Expression"));
        };
    }


    // : '(' Expr | Operation  { Expr } ')';
    private static Result<GMatch, CError> isSExpression(Parser p) {
        // ::= '('
        if (!matchToken(p, MATCH_LEFT_PAREN)) { return Result.ok(GMatch.NONE); }


        // ::= Expr | Operation
        GForm.Operation operation;
        if (isOperator(p)) {
            operation = new GForm.Operation.Op();
        } else {
            switch (isExpression(p)) {
                case Result.Ok(GMatch.Found(GForm.Expr expr)) -> operation = new GForm.Operation.ExprOp(expr);
                case Result.Err<GMatch, CError> err -> { return err; }
                default -> { return Result.err(GrammarError.expected(p.peek(), "Operation | Expression")); }
            }
        }

        // Check if predicate form ::= '->' Expr [ ':' Expr ]
        switch (isPredicateForm(p)) {
            case Result.Ok(GMatch.Found(GForm.PForm form)) -> {
                if (operation instanceof GForm.Operation.ExprOp(GForm.Expr expr)) {
                    return Result.ok(GMatch.of(new GForm.Expr.Cond(expr, form)));
                } else {
                    return Result.err(GrammarError.expected(p.peek(), "Expression; Invalid conditional placement"));
                }
            }
            case Result.Err<GMatch, CError> err -> { return err; }
            default -> { }
        }

        // ::= { Expr }
        List<GForm.Expr> operands = new ArrayList<>();
        loop:
        while (true) {
            switch (isExpression(p)) {
                case Result.Ok(GMatch.Found(GForm.Expr expr)) -> operands.add(expr);
                case Result.Err<GMatch, CError> err -> { return err; }
                default -> { break loop; }
            }
        }

        // ::= ')'
        if (!matchToken(p, MATCH_RIGHT_PAREN)) { return Result.err(GrammarError.expected(p.peek(), ")")); }

        return Result.ok(GMatch.of(new GForm.Expr.S(operation, operands)));
    }

    private static Result<GMatch, CError> isVExpression(Parser p) {
        if (!(p.peek().tokenType() instanceof TokenType.Literal)) { return GMatch.NONE.intoResult(); }

        // Make the following token is not call or member access (An identity is also a value)
        return switch (p.peekN(2).tokenType()) {
            case TokenType.Syntactic.Arrow, TokenType.Syntactic.DoubleColon,
                 TokenType.Syntactic.ColonDot, TokenType.Syntactic.LeftBracket -> Result.ok(GMatch.NONE);
            default -> {
                p.consumeN(1);
                yield Result.ok(GMatch.of(new GForm.Expr.V()));
            }
        };
    }

    // FIXME not sure if this is correct with the identifier check before chain?
    // ::= {[ NamespaceChain ] [ Identifier ] [ MemberAccessChain ]}-
    private static Result<GMatch, CError> isFExpression(Parser p) {
        int nameSpaceCount = hasNamespaceDepth(p);
        // boolean hasIdentifier = matchToken(p, MATCH_IDENTIFIER);

        return switch (isAccessChain(p)) {
            case Result.Err<GMatch, CError> err -> err;
            case Result.Ok(GMatch.Found(GForm.AccessChain chain)) ->
                    GMatch.of(new GForm.Expr.M(nameSpaceCount, chain.accessChain())).intoResult();
            default -> GMatch.NONE.intoResult();
        };


    }



    /*----------
    | ACCESSORS |
     ----------*/

    // ::= Identifier '->'
    private static boolean isNamespaceAccess(Parser p) {
        return matchTokens(p, List.of(MATCH_IDENTIFIER, MATCH_RIGHT_ARROW));
    }

    // ::= ':." Identifier
    private static boolean isIdentifierAccess(Parser p) {
        return matchTokens(p, List.of(MATCH_IDENTIFIER_ACCESS, MATCH_IDENTIFIER));
    }

    // ::= Identifier
    private static boolean isTypeAccess(Parser p) {
        return matchToken(p, MATCH_IDENTIFIER_ACCESS);
    }


    // ::= '::'[Identifier]
    private static boolean isFunctionAccess(Parser p) {
        return matchTokens(p, List.of(MATCH_FUNCTION_ACCESS, MATCH_IDENTIFIER));
    }

    // { NamespaceAccess }-
    private static int hasNamespaceDepth(Parser p) {
        return (int) Stream.generate(() -> isNamespaceAccess(p))
                .takeWhile(isFound -> isFound == true)
                .count();
    }

    static final List<TokenType> nonTypeAccess = List.of(
            TokenType.FUNCTION_ACCESS, TokenType.IDENTIFIER_ACCESS, TokenType.NAME_SPACE_ACCESS
    );

    //  { { FieldAccess | MethodCall } [ MethodAccess ] }-
    private static Result<GMatch, CError> isAccessChain(Parser p) {
        List<GForm.Access> accessChain = new ArrayList<>(5);

        while (true) {

            // TypeAccess must be only chain element
            if (isTypeAccess(p)) {
                accessChain.add(new GForm.Access.Type());
                return switch (nonTypeAccess.contains(p.peek().tokenType())) {
                    case true -> Result.err(GrammarError.expected(p.peek(), "End of chain after Type access"));
                    case false -> Result.ok(GMatch.of(new GForm.AccessChain(accessChain)));
                };

            }

            // Check for identifier Access
            if (isIdentifierAccess(p)) {
                accessChain.add(new GForm.Access.Identifier());
                continue;
            }

            if (isFunctionAccess(p)) {
                // Method Access found, check if it is a method call
                if (matchToken(p, MATCH_LEFT_BRACKET)) {

                    GForm.Arguments arguments = null;
                    switch (isArguments(p)) {
                        case Result.Err<GMatch, CError> err -> { return err; }
                        case Result.Ok(GMatch.Found(GForm.Arguments args)) -> arguments = args;
                        default -> arguments = GForm.Arguments.EMPTY;
                    }

                    if (matchToken(p, MATCH_RIGHT_BRACKET)) {
                        // Add method call to chain
                        accessChain.add(new GForm.Access.FuncCall(arguments.args()));
                    } else { return Result.err(GrammarError.expected(p.peek(), "]")); }

                } else { // Is method access (identity)
                    // Ensure no more accesses are left in the chain, as an identity call must be terminal
                    if (isFunctionAccess(p) || isIdentifierAccess(p)) { // These consume, not best for a throw check FIXME
                        return Result.err(GrammarError.expected(
                                p.peekN(0),
                                "End of F-Expr (Method identity calls must come last)"
                        ));
                    }
                    // Valid identity call position
                    accessChain.add(new GForm.Access.FunctionAccess());
                    break;
                }
            }


        }

        return Result.ok(accessChain.isEmpty()
                ? GMatch.NONE
                : GMatch.of(new GForm.AccessChain(accessChain)));
    }




    /* ---------
    | SUB FORMS |
     ----------*/


    // ::= { Modifier } Identifier [ Type ]
    private static Result<GMatch, CError> isParameter(Parser p) {
        // ::= { Modifier }
        int modifierCount = hasModifiers(p);

        // ::= Identifier
        if (!matchToken(p, MATCH_IDENTIFIER)) {
            if (modifierCount == 0) {
                return Result.ok(GMatch.NONE);
            } else {
                return Result.err(GrammarError.expected(p.peek(), "Identifier following modifier"));
            }
        }

        // ::= [ (':' Type) ]
        boolean hasType = false;
        if (matchToken(p, MATCH_COLON)) {
            switch (isType(p)) {
                case Result.Ok(boolean foundType) when foundType -> hasType = true;
                case Result.Err<Boolean, CError> err -> { return Result.err(err.error()); }
                default -> { return Result.err(GrammarError.expected(p.peek(), "Type")); }
            }
        }

        return Result.ok(GMatch.of(new GForm.Param(modifierCount, hasType)));
    }

    // ::= { Parameter }
    private static Result<GMatch, CError> isParameters(Parser p) {
        List<GForm.Param> params = new ArrayList<>(5);
        loop:
        while (true) {
            switch (isParameter(p)) {
                case Result.Err<GMatch, CError> err -> { return err; }
                case Result.Ok(GMatch.Found(GForm.Param form)) -> params.add(form);
                default -> { break loop; }
            }
        }
        return Result.ok(GMatch.of(new GForm.Parameters(params)));
    }


    private static int hasModifiers(Parser p) {
        return matchMultiple(p, MATCH_MODIFIER);
    }

    private static boolean isOperator(Parser p) {
        return matchToken(p, MATCH_OPERATION);
    }

// ::= { Modifier } Expr

    private static Result<GMatch, CError> isArgument(Parser p) {
        // ::= { Modifier }
        int modifierCount = matchMultiple(p, MATCH_MODIFIER);

        // ::= Expr
        return switch (isExpression(p)) {
            case Result.Err<GMatch, CError> err -> err;
            case Result.Ok(GMatch.Found(GForm.Expr expr)) -> GMatch.of(new GForm.Arg(modifierCount, expr)).intoResult();
            default -> GMatch.NONE.intoResult();
        };
    }

    // ::= { Argument }
    private static Result<GMatch, CError> isArguments(Parser p) {
        List<GForm.Arg> arguments = new ArrayList<>(5);
        while (true) {
            switch (isArgument(p)) {
                case Result.Err<GMatch, CError> err -> { return err; }
                case Result.Ok(GMatch.Found(GForm.Arg arg)) -> arguments.add(arg);
                default -> { return GMatch.of(new GForm.Arguments(arguments)).intoResult(); }
            }
        }
    }


    /*--------------
    | SPECIAL FORMS |
    |--------------*/

    // TODO add grammar
    private static Result<GMatch, CError> isPredicateForm(Parser p) {
        if (p.peek().tokenType() != TokenType.RIGHT_ARROW && p.peek().tokenType() != TokenType.Syntactic.Colon) {
            return Result.ok(GMatch.NONE);
        }

        Optional<GForm.Expr> thenForm = Optional.empty();
        if (matchToken(p, MATCH_RIGHT_ARROW)) {
            switch (isExpression(p)) {
                case Result.Err<GMatch, CError> err -> { return err; }
                case Result.Ok(GMatch.Found(GForm.Expr expr)) -> thenForm = Optional.of(expr);
                default -> { return Result.err(GrammarError.expected(p.peek(), "Expression")); }
            }
        }

        Optional<GForm.Expr> elseForm = Optional.empty();
        if (matchToken(p, MATCH_COLON)) {
            switch (isExpression(p)) {
                case Result.Err<GMatch, CError> err -> { return err; }
                case Result.Ok(GMatch.Found(GForm.Expr expr)) -> elseForm = Optional.of(expr);
                default -> { return Result.err(GrammarError.expected(p.peek(), "Expression")); }
            }
        }
        return Result.ok(GMatch.of(new GForm.PForm(thenForm, elseForm)));
    }

    // ::= ('|' { Parameter } '|' Expr)
    private static Result<GMatch, CError> isLambdaForm(Parser p) {
        if (!matchToken(p, MATCH_BAR)) { return Result.ok(GMatch.NONE); }

        GForm.Parameters params = null;
        switch (isParameters(p)) {
            case Result.Err<GMatch, CError> err -> { return err; }
            case Result.Ok(GMatch.Found(GForm.Parameters paramForm)) -> { params = paramForm; }
            default -> params = GForm.Parameters.EMPTY;
        }

        if (!matchToken(p, MATCH_BAR)) { return Result.err(GrammarError.expected(p.peek(), "|")); }


        return switch (isExpression(p)) {
            case Result.Err<GMatch, CError> err -> err;
            case Result.Ok(GMatch.Found(GForm.Expr expr)) -> GMatch.of(new GForm.Expr.LForm(params, expr)).intoResult();
            default -> Result.err(GrammarError.expected(p.peek(), "Expression"));
        };
    }

    private static Result<GMatch, CError> isBExpression(Parser p) {
        return Result.ok(GMatch.NONE);
    }


    /*------
    | TYPES |
    |------*/
    private static Result<Boolean, CError> isType(Parser p) {
        Token t = p.peek();

        return switch (t.tokenType()) {
            case TokenType.Literal.Identifier -> {
                p.consumeN(1);
                yield Result.ok(true);
            }
            case TokenType.BuiltIn.Fn -> {
                p.consumeN(1);
                yield isFuncType(p);
            }
            case TokenType.BuiltIn.Array -> {
                p.consumeN(1);
                yield isArrayType(p);
            }
            default -> Result.ok(false);
        };
    }

    private static Result<Boolean, CError> isFuncType(Parser p) {
        Token t = p.peek();

        if (t.tokenType() == TokenType.LEFT_ANGLE_BRACKET) {
            p.consumeN(1);
        } else { return Result.err(GrammarError.expected(t, "<")); }

        loop:
        while (true) {
            switch (isType(p)) {
                case Result.Err<Boolean, CError> err -> { return err; }
                case Result.Ok(Boolean found) when found -> { continue; }
                default -> { break loop; }
            }
        }

        switch (p.peek()) {
            case Token(TokenType tt, _, _, _) when tt == TokenType.Syntactic.SemiColon -> {
                p.consumeN(1);
                switch (isType(p)) {
                    case Result.Err<Boolean, CError> err -> { return err; }
                    case Result.Ok(Boolean found) when found -> { /* Do nothing confirmed for parse*/ }
                    default -> { return Result.err(GrammarError.expected(t, "<t1, t1; !ERROR!>")); }
                }
            }
            default -> { return Result.err(GrammarError.expected(t, ";")); }
        }

        t = p.peek();
        if (t.tokenType() == TokenType.RIGHT_ANGLE_BRACKET) {
            p.consumeN(1);
            return Result.ok(true);
        } else { return Result.err(GrammarError.expected(t, ">")); }
    }


    private static Result<Boolean, CError> isArrayType(Parser p) {
        Token t = p.peek();

        if (t.tokenType() == TokenType.LEFT_ANGLE_BRACKET) {
            p.consumeN(1);
        } else { return Result.err(GrammarError.expected(t, "<")); }


        switch (isType(p)) {
            case Result.Err<Boolean, CError> err -> { return err; }
            case Result.Ok<Boolean, CError> ok -> {/* Do nothing confirmed for parse*/ }
            default -> { return Result.err(GrammarError.expected(t, "Array<!ERROR!>")); }
        }

        t = p.peek();
        if (t.tokenType() == TokenType.RIGHT_ANGLE_BRACKET) {
            p.consumeN(1);
            return Result.ok(true);
        } else { return Result.err(GrammarError.expected(t, ">")); }

    }

}
