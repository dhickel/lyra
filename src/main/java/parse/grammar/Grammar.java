package parse;

import parse.grammar.GrammarForm;
import parse.grammar.GrammarMatch;
import util.Result;
import util.exceptions.CompExcept;
import util.exceptions.InternalException;
import util.exceptions.InvalidGrammarException;

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


    public static Result<GrammarMatch, CompExcept> findNextMatch(Parser p) {
        return isStatement(p)
                .flatMap((GrammarMatch match) -> match.isFound()
                        ? Result.ok(match)
                        : Result.err(InternalException.of("Statement found nothing")))
                .orElse(() -> isExpression(p)
                        .flatMap((GrammarMatch match) -> match.isFound()
                                ? Result.ok(match)
                                : Result.err(InternalException.of("Statement found nothing"))))
                .orElse(() -> Result.err(
                        InvalidGrammarException.expected(p.peek(), "Statement or Expression")));
    }




    /*-----------
    | STATEMENTS |
    ------------*/

    private static final List<GrammarCheck<Parser, Result<GrammarMatch, CompExcept>>> STATEMENT_CHECKS = List.of(
            Grammar::isLetStatement, Grammar::isReAssignStatement
    );


    private static Result<GrammarMatch, CompExcept> isStatement(Parser p) {
        for (var form : STATEMENT_CHECKS) {
            var result = form.check(p);
            if (result.isErr()) return result;
            if (result.unwrap().isFound()) return result;
        }
        return Result.ok(GrammarMatch.NONE);
    }


    // ::= 'let' { Modifier } Identifier [ (':' Type) ] '=' Expr
    private static Result<GrammarMatch, CompExcept> isLetStatement(Parser p) {
        boolean hasType = false;

        // ::= 'let'
        if (!matchToken(p, MATCH_LET)) { return Result.ok(GrammarMatch.NONE); }

        // ::= { Modifier }
        int modifierCount = hasModifiers(p);

        // ::= Identifier
        if (!matchToken(p, MATCH_IDENTIFIER)) {
            return Result.err(InvalidGrammarException.expected(p.peek(), "Identifier"));
        }

        // ::= (':' Type)
        if (matchToken(p, MATCH_COLON)) {
            var typeResult = isType(p);
            if (typeResult instanceof Result.Err<Boolean, CompExcept>(InvalidGrammarException error)) {
                return new Result.Err<>(error);
            }
            if (typeResult.unwrap()) { // is Ok and true
                hasType = true;
            } else { // is Ok and false
                return Result.err(InvalidGrammarException.expected(p.peek(), "Type"));
            }
        }

        // ::= '='
        if (!matchToken(p, MATCH_EQUAL)) { return Result.err(InvalidGrammarException.expected(p.peek(), "=")); }

        // ::= Expr
        if (isExpression(p) instanceof Result.Ok(GrammarMatch.Found(GrammarForm.Expression expr))) {
            return Result.ok(GrammarMatch.of(new GrammarForm.Statement.Let(hasType, modifierCount, expr)));
        } else { return Result.err(InvalidGrammarException.expected(p.peek(), "Expression")); }
    }

    // ::= Identifier ':=' Expr // TODO better error when = is used for assignment on accident
    private static Result<GrammarMatch, CompExcept> isReAssignStatement(Parser p) {
        // ::= Identifier ':='
        if (!matchTokens(p, List.of(MATCH_IDENTIFIER, MATCH_REASSIGN))) { return Result.ok(GrammarMatch.NONE); }

        var expressionResult = isExpression(p);
        if (expressionResult.isErr()) return expressionResult;

        if (expressionResult.unwrap() instanceof GrammarMatch.Found(GrammarForm.Expression expr)) {
            return GrammarMatch.of(new GrammarForm.Statement.Reassign(expr)).intoResult();
        } else { return Result.err(InvalidGrammarException.expected(p.peek(), "Expression")); }
    }

    /*------------
    | EXPRESSIONS |
    -------------*/

    private static final List<GrammarCheck<Parser, Result<GrammarMatch, CompExcept>>> EXPRESSION_CHECKS = List.of(
            Grammar::isBlockExpression, Grammar::isLambdaExpression, Grammar::isLambdaForm,
            Grammar::isBExpression, Grammar::isSExpression, Grammar::isVExpression, Grammar::isFExpression
    );


    private static Result<GrammarMatch, CompExcept> isExpression(Parser p) {
        for (var form : EXPRESSION_CHECKS) {
            var result = form.check(p);
            if (result.isErr()) return result;
            if (result.unwrap().isFound()) return result;
        }
        return Result.ok(GrammarMatch.NONE);
    }

    // ::= '{' { Expr | Stmnt } '}'
    private static Result<GrammarMatch, CompExcept> isBlockExpression(Parser p) {
        if (!matchToken(p, MATCH_LEFT_BRACE)) { return Result.ok(GrammarMatch.NONE); }

        List<GrammarForm> blockMembers = new ArrayList<>(5);

        // Collect inner Expressions/Statements
        while (!matchToken(p, MATCH_RIGHT_BRACE)) {
            var nextMatch = findNextMatch(p);
            if (nextMatch.isErr()) return nextMatch;
            if (nextMatch.unwrap() instanceof GrammarMatch.Found(GrammarForm form)) {
                blockMembers.add(form);
            }
        }

        return Result.ok(GrammarMatch.of(new GrammarForm.Expression.BlockExpr(blockMembers)));
    }

    // ::=  '=>' [ (':' Type) ] '|' { Parameters } '|' Expr
    private static Result<GrammarMatch, CompExcept> isLambdaExpression(Parser p) {
        if (!matchTokens(p, List.of(MATCH_LEFT_PAREN, MATCH_LAMBDA_ARROW))) { return Result.ok(GrammarMatch.NONE); }

        // ::= (':' Type)
        boolean hasType = false;
        if (matchToken(p, MATCH_COLON)) {
            var typeResult = isType(p);
            if (typeResult.isErr()) return typeResult.map(b -> null);
            if (!typeResult.unwrap()) return Result.err(InvalidGrammarException.expected(p.peek(), "Type"));
            hasType = true;
        }

        // ::= ('|' { Parameter } '|')
        var lambdaFormResult = isLambdaForm(p);
        if (lambdaFormResult.isErr()) return lambdaFormResult;

        if (lambdaFormResult.unwrap() instanceof GrammarMatch.Found(GrammarForm.Expression.LambdaFormExpr expr)) {
            // ::= ')'
            if (!matchToken(p, MATCH_RIGHT_PAREN)) {
                return Result.err(InvalidGrammarException.expected(p.peek(), ")"));
            }
            return Result.ok(GrammarMatch.of(new GrammarForm.Expression.LambdaExpr(hasType, expr)));
        } else { return Result.err(InvalidGrammarException.expected(p.peek(), "Expression")); }
    }


    // : '(' Expr | Operation  { Expr } ')';
    private static Result<GrammarMatch, CompExcept> isSExpression(Parser p) {
        // ::= '('
        if (!matchToken(p, MATCH_LEFT_PAREN)) { return Result.ok(GrammarMatch.NONE); }

        // ::= Expr | Operation
        Result<GrammarForm.Operation, CompExcept> operationResult = isOperator(p)
                ? Result.ok(new GrammarForm.Operation.Op())
                : isExpression(p).map(grammarMatch -> {
            if (grammarMatch instanceof GrammarMatch.Found(GrammarForm.Expression expr)) {
                return new GrammarForm.Operation.ExprOp(expr);
            }
            return null; // Should not happen if isExpression returns Found
        });

        if (operationResult.isErr()) return operationResult.map(o -> null);
        GrammarForm.Operation operation = operationResult.unwrap();
        if (operation == null) return Result.err(InvalidGrammarException.expected(p.peek(), "Expression"));


        // Check if predicate form ::= '->' Expr [ ':' Expr ]
        var predicateFormResult = isPredicateForm(p);
        if (predicateFormResult.isErr()) return predicateFormResult;

        if (predicateFormResult.unwrap() instanceof GrammarMatch.Found(GrammarForm.PredicateForm form)) {
            if (operation instanceof GrammarForm.Operation.ExprOp(GrammarForm.Expression expression)) {
                return Result.ok(GrammarMatch.of(new GrammarForm.Expression.CondExpr(expression, form)));
            } else {
                return Result.err(InvalidGrammarException.expected(p.peek(), "Expression, Invalid conditional placement"));
            }
        }

        // ::= { Expr }
        List<GrammarForm.Expression> operands = new ArrayList<>();
        while (true) {
            var expressionResult = isExpression(p);
            if (expressionResult.isErr()) return expressionResult.map(e -> null);
            if (expressionResult.unwrap() instanceof GrammarMatch.Found(GrammarForm.Expression expr)) {
                operands.add(expr);
            } else {
                break;
            }
        }

        // ::= ')'
        if (!matchToken(p, MATCH_RIGHT_PAREN)) { return Result.err(InvalidGrammarException.expected(p.peek(), ")")); }

        return Result.ok(GrammarMatch.of(new GrammarForm.Expression.SExpr(operation, operands)));
    }

    private static Result<GrammarMatch, CompExcept> isVExpression(Parser p) {
        if (!(p.peek().tokenType() instanceof TokenType.Literal)) { return GrammarMatch.NONE.intoResult(); }

        // Make the following token is not call or member access (An identity is also a value)
        return switch (p.peekN(2).tokenType()) {
            case TokenType.Syntactic.Arrow, TokenType.Syntactic.DoubleColon,
                 TokenType.Syntactic.ColonDot, TokenType.Syntactic.LeftBracket -> GrammarMatch.NONE.intoResult();
            default -> {
                p.consumeN(1);
                yield Result.ok(GrammarMatch.of(new GrammarForm.Expression.VExpr()));
            }
        };
    }

    // FIXME not sure if this is correct with the identifier check before chain?
// ::= {[ NamespaceChain ] [ Identifier ] [ MemberAccessChain ]}-
    private static Result<GrammarMatch, CompExcept> isFExpression(Parser p) {
        int nameSpaceCount = hasNamespaceDepth(p);
        // boolean hasIdentifier = matchToken(p, MATCH_IDENTIFIER);
        var accessChainResult = isMemberAccessChain(p);
        if (accessChainResult.isErr()) return accessChainResult;


        return accessChainResult.unwrap() instanceof GrammarMatch.Found(GrammarForm.AccessChain chain)
                ? Result.ok(GrammarMatch.of(new GrammarForm.Expression.MExpr(nameSpaceCount, chain.accessChain())))
                : Result.ok(GrammarMatch.NONE);

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

    // Note: Slight sematic discrepancy, even an in-scope method call will still be an access chain of one,
// even though technically it's not a member access
//  { { FieldAccess | MethodCall } [ MethodAccess ] }-
    private static Result<GrammarMatch, CompExcept> isMemberAccessChain(Parser p) {
        List<GrammarForm.MemberAccess> accessChain = new ArrayList<>(5);

        while (true) {
            if (isIdentifierAccess(p)) {
                accessChain.add(new GrammarForm.MemberAccess.Identifier());
                continue;
            }

            // No Field or Method Access found, chain ends.
            if (!isFunctionAccess(p)) { break; }

            // Method Access found, check if it is a method call
            if (matchToken(p, MATCH_LEFT_BRACKET)) {
                var argumentsResult = isArguments(p);
                if (argumentsResult.isErr()) return argumentsResult;

                GrammarForm.Arguments arguments = argumentsResult.unwrap() instanceof GrammarMatch.Found(
                        GrammarForm.Arguments args
                )
                        ? args
                        : GrammarForm.Arguments.EMPTY;

                if (matchToken(p, MATCH_RIGHT_BRACKET)) {
                    // Add method call to chain
                    accessChain.add(new GrammarForm.MemberAccess.FunctionCall(arguments.args()));
                } else { return Result.err(InvalidGrammarException.expected(p.peek(), "]")); }

            } else { // Is method access (identity)
                // Ensure no more accesses are left in the chain, as an identity call must be terminal
                if (isFunctionAccess(p) || isIdentifierAccess(p)) { // These consume, not best for a throw check FIXME
                    return Result.err(InvalidGrammarException.expected(
                            p.peekN(0),
                            "End of F-Expr (Method identity calls must come last)"
                    ));
                }
                // Valid identity call position
                accessChain.add(new GrammarForm.MemberAccess.FunctionAccess());
                break;
            }
        }

        return Result.ok(accessChain.isEmpty()
                ? GrammarMatch.NONE
                : GrammarMatch.of(new GrammarForm.MemberAccess.AccessChain(accessChain)));
    }




    /* ---------
    | SUB FORMS |
     ----------*/


    // ::= { Modifier } Identifier [ Type ]
    private static Result<GrammarMatch, CompExcept> isParameter(Parser p) {
        // ::= { Modifier }
        int modifierCount = hasModifiers(p);

        // ::= Identifier
        if (!matchToken(p, MATCH_IDENTIFIER)) {
            if (modifierCount == 0) {
                return Result.ok(GrammarMatch.NONE);
            } else { return Result.err(InvalidGrammarException.expected(p.peek(), "Identifier following modifier")); }
        }

        // ::= [ (':' Type) ]
        boolean hasType = false;
        if (matchToken(p, MATCH_COLON)) {
            var typeResult = isType(p);
            if (typeResult.isErr()) return typeResult.map(b -> null);
            if (!typeResult.unwrap()) return Result.err(InvalidGrammarException.expected(p.peek(), "Type"));
            hasType = true;
        }

        return Result.ok(GrammarMatch.of(new GrammarForm.Param(modifierCount, hasType)));
    }

    // ::= { Parameter }
    private static Result<GrammarMatch, CompExcept> isParameters(Parser p) {
        List<GrammarForm.Param> params = new ArrayList<>(5);
        while (true) {
            var parameterResult = isParameter(p);
            if (parameterResult.isErr()) return parameterResult;
            if (parameterResult.unwrap() instanceof GrammarMatch.Found(GrammarForm.Param form)) {
                params.add(form);
            } else {
                break;
            }
        }
        return Result.ok(GrammarMatch.of(new GrammarForm.Parameters(params)));
    }


    private static int hasModifiers(Parser p) {
        return matchMultiple(p, MATCH_MODIFIER);
    }

    private static boolean isOperator(Parser p) {
        return matchToken(p, MATCH_OPERATION);
    }

// ::= { Modifier } Expr

    private static Result<GrammarMatch, CompExcept> isArgument(Parser p) {
        // ::= { Modifier }
        int modifierCount = matchMultiple(p, MATCH_MODIFIER);

        // ::= Expr
        var expressionResult = isExpression(p);
        if (expressionResult.isErr()) return expressionResult;
        return expressionResult.map(grammarMatch -> {
            if (grammarMatch instanceof GrammarMatch.Found(GrammarForm.Expression expr)) {
                return GrammarMatch.of(new GrammarForm.Arg(modifierCount, expr));
            }
            return GrammarMatch.NONE;
        });
    }

    // ::= { Argument }
    private static Result<GrammarMatch, CompExcept> isArguments(Parser p) {
        List<GrammarForm.Arg> arguments = new ArrayList<>(5);
        while (true) {
            var argumentResult = isArgument(p);
            if (argumentResult.isErr()) return argumentResult;
            if (argumentResult.unwrap() instanceof GrammarMatch.Found(GrammarForm.Arg arg)) {
                arguments.add(arg);
            } else {
                break;
            }
        }
        return Result.ok(GrammarMatch.of(new GrammarForm.Arguments(arguments)));
    }


    /*--------------
    | SPECIAL FORMS |
    |--------------*/

    // TODO add grammar
    private static Result<GrammarMatch, CompExcept> isPredicateForm(Parser p) {
        if (p.peek().tokenType() != TokenType.RIGHT_ARROW && p.peek().tokenType() != TokenType.Syntactic.Colon) {
            return Result.ok(GrammarMatch.NONE);
        }

        Optional<GrammarForm.Expression> thenForm = Optional.empty();
        if (p.peek().tokenType() == TokenType.RIGHT_ARROW) {
            matchToken(p, MATCH_RIGHT_ARROW);
            var expressionResult = isExpression(p);
            if (expressionResult.isErr()) return expressionResult;
            if (expressionResult.unwrap() instanceof GrammarMatch.Found(GrammarForm.Expression expr)) {
                thenForm = Optional.of(expr);
            } else {
                return Result.err(InvalidGrammarException.expected(p.peek(), "Expression"));
            }
        }

        Optional<GrammarForm.Expression> elseForm = Optional.empty();
        if (p.peek().tokenType() == TokenType.Syntactic.Colon) {
            matchToken(p, MATCH_COLON);
            var expressionResult = isExpression(p);
            if (expressionResult.isErr()) return expressionResult;
            if (expressionResult.unwrap() instanceof GrammarMatch.Found(GrammarForm.Expression expr)) {
                elseForm = Optional.of(expr);
            } else {
                return Result.err(InvalidGrammarException.expected(p.peek(), "Expression"));
            }
        }
        return Result.ok(GrammarMatch.of(new GrammarForm.PredicateForm(thenForm, elseForm)));
    }

    // ::= ('|' { Parameter } '|' Expr)
    private static Result<GrammarMatch, CompExcept> isLambdaForm(Parser p) {
        if (!matchToken(p, MATCH_BAR)) { return Result.ok(GrammarMatch.NONE); }

        var paramsResult = isParameters(p);
        if (paramsResult.isErr()) return paramsResult;
        GrammarForm.Parameters params = paramsResult.unwrap() instanceof GrammarMatch.Found(
                GrammarForm.Parameters paramForm
        )
                ? paramForm
                : GrammarForm.Parameters.EMPTY;

        if (!matchToken(p, MATCH_BAR)) { return Result.err(InvalidGrammarException.expected(p.peek(), "|")); }

        var expressionResult = isExpression(p);
        if (expressionResult.isErr()) return expressionResult;
        if (expressionResult.unwrap() instanceof GrammarMatch.Found(GrammarForm.Expression expr)) {
            return Result.ok(GrammarMatch.of(new GrammarForm.Expression.LambdaFormExpr(params, expr)));
        } else { return Result.err(InvalidGrammarException.expected(p.peek(), "Expression")); }
    }

    private static Result<GrammarMatch, CompExcept> isBExpression(Parser p) {
        return Result.ok(GrammarMatch.NONE);
    }


    /*------
    | TYPES |
    |------*/
    private static Result<Boolean, CompExcept> isType(Parser p) {
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

    private static Result<Boolean, CompExcept> isFuncType(Parser p) {
        Token t = p.peek();

        if (t.tokenType() == TokenType.LEFT_ANGLE_BRACKET) {
            p.consumeN(1);
        } else { return Result.err(InvalidGrammarException.expected(t, "<")); }

        while (true) {
            var isTypeResult = isType(p);
            if (isTypeResult.isErr()) return isTypeResult;
            if (!isTypeResult.unwrap()) break;
        }

        t = p.peek();
        if (t.tokenType() == TokenType.Syntactic.SemiColon) {
            p.consumeN(1);
            var isTypeResult = isType(p);
            if (isTypeResult.isErr()) return isTypeResult;
            if (!isTypeResult.unwrap()) { return Result.err(InvalidGrammarException.expected(t, "<t1, t1; !ERROR!>")); }
        } else { return Result.err(InvalidGrammarException.expected(t, ";")); }

        t = p.peek();
        if (t.tokenType() == TokenType.RIGHT_ANGLE_BRACKET) {
            p.consumeN(1);
            return Result.ok(true);
        } else { return Result.err(InvalidGrammarException.expected(t, ">")); }
    }


    private static Result<Boolean, CompExcept> isArrayType(Parser p) {
        Token t = p.peek();

        if (t.tokenType() == TokenType.LEFT_ANGLE_BRACKET) {
            p.consumeN(1);
        } else { return Result.err(InvalidGrammarException.expected(t, "<")); }

        var isTypeResult = isType(p);
        if (isTypeResult.isErr()) {
            return isTypeResult;
        }
        if (!isTypeResult.unwrap()) {
            return Result.err(InvalidGrammarException.expected(t, "Array<!ERROR!>"));
        }

        t = p.peek();
        if (t.tokenType() == TokenType.RIGHT_ANGLE_BRACKET) {
            p.consumeN(1);
            return Result.ok(true);
        } else { return Result.err(InvalidGrammarException.expected(t, ">")); }

    }

}
