package Parse;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.*;
import java.util.stream.Gatherer;
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

    public static class InvalidGrammarException extends Exception {
        private final int line;
        private final int chr;

        private InvalidGrammarException(String message, int line, int column) {
            super(message);
            this.line = line;
            this.chr = column;
        }

        public static InvalidGrammarException expected(Token token, String expected) {
            return new InvalidGrammarException(
                    String.format("Expected: %s, Found: %s", expected, token.tokenType()),
                    token.line(),
                    token.chr()
            );
        }

        public int line() { return line; }

        public int column() { return chr; }
    }


    public sealed interface MatchResult {
        MatchResult NONE = new None();

        record Found(GrammarForm form) implements MatchResult { }

        record None() implements MatchResult { }

        static MatchResult of(GrammarForm form) {
            return new Found(form);
        }

        default boolean isFound() {
            return this instanceof Found;
        }

        default boolean isNone() {
            return this instanceof None;
        }

        static <T extends GrammarForm> Gatherer<MatchResult, Void, T> takeWhileFoundOfMatch(Class<T> type) {
            return Gatherer.of(Gatherer.Integrator.of((state, match, downstream) ->
                    switch (match) {
                        case Found(var form) when type.isInstance(form) -> downstream.push(type.cast(form));
                        default -> false; // Stop processing
                    }));
        }
    }

    @FunctionalInterface
    public interface GrammarCheck<T, R> {
        R check(T t) throws InvalidGrammarException;
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
    public static MatchResult findNextMatch(Parser p) throws InvalidGrammarException {
        if (isStatement(p) instanceof MatchResult.Found found) { return found; }
        if (isExpression(p) instanceof MatchResult.Found found) { return found; }

        // Method should only be called when there are unconsumed tokens, if tokens exist and are not expr/stmt then
        // invalid grammar is present
        throw InvalidGrammarException.expected(p.peek(), "Statement or Expression: Parse Error");
    }

    /*-----------
    | STATEMENTS |
    ------------*/

    private static final List<GrammarCheck<Parser, MatchResult>> STATEMENT_CHECKS = List.of(
            Grammar::isLetStatement, Grammar::isReAssignStatement
    );


    private static MatchResult isStatement(Parser p) throws InvalidGrammarException {
        for (var form : STATEMENT_CHECKS) {
            if (form.check(p) instanceof MatchResult.Found found) { return found; }
        }
        return MatchResult.NONE;
    }


    // ::= 'let' { Modifier } Identifier [ (':' Type) ] '=' Expr
    private static MatchResult isLetStatement(Parser p) throws InvalidGrammarException {
        boolean hasType = false;

        // ::= 'let'
        if (!matchToken(p, MATCH_LET)) { return MatchResult.NONE; }

        // ::= { Modifier }
        int modifierCount = hasModifiers(p);

        // ::= Identifier
        if (!matchToken(p, MATCH_IDENTIFIER)) { throw InvalidGrammarException.expected(p.peek(), "Identifier"); }

        // ::= (':' Type)
        if (matchToken(p, MATCH_COLON)) {
            if (isType(p)) {
                hasType = true;
            } else { throw InvalidGrammarException.expected(p.peek(), "Type"); }
        } else { throw InvalidGrammarException.expected(p.peek(), ":"); }

        // ::= '='
        if (!matchToken(p, MATCH_EQUAL)) { throw InvalidGrammarException.expected(p.peek(), "="); }

        // ::= Expr
        if (isExpression(p) instanceof MatchResult.Found(GrammarForm.Expression expr)) {
            return MatchResult.of(new GrammarForm.Statement.Let(hasType, modifierCount, expr));
        } else { throw InvalidGrammarException.expected(p.peek(), "Expression"); }
    }

    // ::= Identifier ':=' Expr // TODO better error when = is used for assignment on accident
    private static MatchResult isReAssignStatement(Parser p) throws InvalidGrammarException {
        // ::= Identifier ':='
        if (!matchTokens(p, List.of(MATCH_IDENTIFIER, MATCH_REASSIGN))) { return MatchResult.NONE; }

        if (isExpression(p) instanceof MatchResult.Found(GrammarForm.Expression expr)) {
            return MatchResult.of(new GrammarForm.Statement.Reassign(expr));
        } else { throw InvalidGrammarException.expected(p.peek(), "Expression"); }
    }

    /*------------
    | EXPRESSIONS |
    -------------*/

    private static final List<GrammarCheck<Parser, MatchResult>> EXPRESSION_CHECKS = List.of(
            Grammar::isBlockExpression, Grammar::isLambdaExpression, Grammar::isLambdaForm,
            Grammar::isBExpression, Grammar::isSExpression, Grammar::isVExpression, Grammar::isFExpression
    );


    private static MatchResult isExpression(Parser p) throws InvalidGrammarException {
        for (var form : EXPRESSION_CHECKS) {
            if (form.check(p) instanceof MatchResult.Found found) { return found; }
        }
        return MatchResult.NONE;
    }

    // ::= '{' { Expr | Stmnt } '}'
    private static MatchResult isBlockExpression(Parser p) throws InvalidGrammarException {
        if (!matchToken(p, MATCH_LEFT_BRACE)) { return MatchResult.NONE; }

        List<MatchResult> blockMembers = new ArrayList<>(5);

        // Collect inner Expressions/Statements
        while (!matchToken(p, MATCH_RIGHT_BRACE)) { blockMembers.add(findNextMatch(p)); }

        // Yummy recursive patterns, these are best left as a more concrete form and not a result for inner assignments
        List<GrammarForm> memberForms = blockMembers.stream()
                .filter(m -> m instanceof MatchResult.Found)
                .map(f -> ((MatchResult.Found) f).form)
                .toList();

        return MatchResult.of(new GrammarForm.Expression.BlockExpr(memberForms));
    }

    // ::=  '=>' [ (':' Type) ] '|' { Parameters } '|' Expr
    private static MatchResult isLambdaExpression(Parser p) throws InvalidGrammarException {
        if (!matchTokens(p, List.of(MATCH_LEFT_PAREN, MATCH_LAMBDA_ARROW))) { return MatchResult.NONE; }

        // ::= (':' Type)
        boolean hasType = switch (matchToken(p, MATCH_COLON)) {
            case true -> {
                if (isType(p)) {
                    yield true; // If we have a colon, we need a type
                } else { throw InvalidGrammarException.expected(p.peek(), "Type"); }
            }
            case false -> false;
        };

        // ::= ('|' { Parameter } '|')
        if (isLambdaForm(p) instanceof MatchResult.Found(GrammarForm.Expression.LambdaFormExpr expr)) {
            // ::= ')'
            if (!matchToken(p, MATCH_RIGHT_PAREN)) { throw InvalidGrammarException.expected(p.peek(), ")"); }
            return MatchResult.of(new GrammarForm.Expression.LambdaExpr(hasType, expr));
        } else { throw InvalidGrammarException.expected(p.peek(), "Expression"); }
    }


    // : '(' Expr | Operation  { Expr } ')';
    private static MatchResult isSExpression(Parser p) throws InvalidGrammarException {
        // ::= '('
        if (!matchToken(p, MATCH_LEFT_PAREN)) { return MatchResult.NONE; }

        // ::= Expr | Operation
        GrammarForm.Operation operation = switch (p) {
            case Parser _ when isOperator(p) -> new GrammarForm.Operation.Op();
            case Parser _ when isExpression(p) instanceof MatchResult.Found(GrammarForm.Expression expr) ->
                    new GrammarForm.Operation.ExprOp(expr);
            default -> throw InvalidGrammarException.expected(p.peek(), "Expression");
        };

        // Check if predicate form ::= '->' Expr [ ':' Expr ]
        if (isPredicateForm(p) instanceof MatchResult.Found(GrammarForm.PredicateForm form)) {
            if (operation instanceof GrammarForm.Operation.ExprOp(GrammarForm.Expression expression)) {
                return MatchResult.of(new GrammarForm.Expression.CondExpr(expression, form));
            } else { throw InvalidGrammarException.expected(p.peek(), "Expression, Invalid conditional placement"); }
        }

        // ::= { Expr }
        List<GrammarForm.Expression> operands = new ArrayList<>();
        while ((isExpression(p) instanceof MatchResult.Found(GrammarForm.Expression expr))) {
            operands.add(expr);
        }

        // ::= ')'
        if (!matchToken(p, MATCH_RIGHT_PAREN)) { throw InvalidGrammarException.expected(p.peek(), ")"); }

        return MatchResult.of(new GrammarForm.Expression.SExpr(operation, operands));
    }

    private static MatchResult isVExpression(Parser p) {
        if (!(p.peek().tokenType() instanceof TokenType.Literal)) { return MatchResult.NONE; }

        // Make the following token is not call or member access (An identity is also a value)
        return switch (p.peekN(2).tokenType()) {
            case TokenType.Syntactic.Arrow, TokenType.Syntactic.DoubleQuote,
                 TokenType.Syntactic.Colon, TokenType.Syntactic.LeftBracket -> MatchResult.NONE;
            default -> MatchResult.of(new GrammarForm.Expression.VExpr());
        };
    }

    // FIXME not sure if this is correct with the identifier check before chain?
    // ::= {[ NamespaceChain ] [ Identifier ] [ MemberAccessChain ]}-
    private static MatchResult isFExpression(Parser p) throws InvalidGrammarException {
        int nameSpaceCount = hasNamespaceDepth(p);
        // boolean hasIdentifier = matchToken(p, MATCH_IDENTIFIER);
        MatchResult accessChain = isMemberAccessChain(p);

        return accessChain instanceof MatchResult.Found(GrammarForm.AccessChain chain)
                ? MatchResult.of(new GrammarForm.Expression.MExpr(nameSpaceCount, chain.accessChain()))
                : MatchResult.NONE;

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
    private static MatchResult isMemberAccessChain(Parser p) throws InvalidGrammarException {
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
                GrammarForm.Arguments arguments = isArguments(p) instanceof MatchResult.Found(
                        GrammarForm.Arguments args
                )
                        ? args
                        : GrammarForm.Arguments.EMPTY;

                if (matchToken(p, MATCH_RIGHT_BRACKET)) {
                    // Add method call to chain
                    accessChain.add(new GrammarForm.MemberAccess.FunctionCall(arguments.args()));
                } else { throw InvalidGrammarException.expected(p.peek(), "]"); }

            } else { // Is method access (identity)
                // Ensure no more accesses are left in the chain, as an identity call must be terminal
                if (isFunctionAccess(p) || isIdentifierAccess(p)) { // These consume, not best for a throw check FIXME
                    throw InvalidGrammarException.expected(
                            p.peekN(0),
                            "End of F-Expr (Method identity calls must come last)"
                    );
                }
                // Valid identity call position
                accessChain.add(new GrammarForm.MemberAccess.FunctionAccess());
                break;
            }
        }

        return accessChain.isEmpty()
                ? MatchResult.NONE
                : MatchResult.of(new GrammarForm.MemberAccess.AccessChain(accessChain));
    }




    /* ---------
    | SUB FORMS |
     ----------*/


    // ::= { Modifier } Identifier [ Type ]
    private static MatchResult isParameter(Parser p) throws InvalidGrammarException {
        // ::= { Modifier }
        int modifierCount = hasModifiers(p);

        // ::= Identifier
        if (!matchToken(p, MATCH_IDENTIFIER)) {
            if (modifierCount == 0) {
                return MatchResult.NONE;
            } else { throw InvalidGrammarException.expected(p.peek(), "Identifier following modifier"); }
        }

        // ::= [ (':' Type) ]
        boolean hasType = switch (matchToken(p, MATCH_COLON)) {
            case true -> {
                if (isType(p)) {
                    yield true; // If we have a colon, we need a type
                } else { throw InvalidGrammarException.expected(p.peek(), "Type"); }
            }
            case false -> false;
        };

        return MatchResult.of(new GrammarForm.Param(modifierCount, hasType));
    }

    // ::= { Parameter }
    private static MatchResult isParameters(Parser p) throws InvalidGrammarException {
        List<GrammarForm.Param> params = new ArrayList<>(5);
        while (isParameter(p) instanceof MatchResult.Found(GrammarForm.Param form)) {
            params.add(form);
        }
        return MatchResult.of(new GrammarForm.Parameters(params));
    }


    private static int hasModifiers(Parser p) {
        return matchMultiple(p, MATCH_MODIFIER);
    }

    private static boolean isOperator(Parser p) {
        return matchToken(p, MATCH_OPERATION);
    }

// ::= { Modifier } Expr

    private static MatchResult isArgument(Parser p) throws InvalidGrammarException {
        // ::= { Modifier }
        int modifierCount = matchMultiple(p, MATCH_MODIFIER);

        // ::= Expr
        return (isExpression(p) instanceof MatchResult.Found(GrammarForm.Expression expr))
                ? MatchResult.of(new GrammarForm.Arg(modifierCount, expr))
                : MatchResult.NONE;
    }

    // ::= { Argument }
    private static MatchResult isArguments(Parser p) throws InvalidGrammarException {
        List<GrammarForm.Arg> arguments = new ArrayList<>(5);
        while (isArgument(p) instanceof MatchResult.Found(GrammarForm.Arg arg)) {
            arguments.add(arg);
        }
        return MatchResult.of(new GrammarForm.Arguments(arguments));
    }


    /*--------------
    | SPECIAL FORMS |
    |--------------*/

    // TODO add grammar
    private static MatchResult isPredicateForm(Parser p) throws InvalidGrammarException {
        if (!matchToken(p, MATCH_RIGHT_ARROW) && !matchToken(p, MATCH_COLON)) { return MatchResult.NONE; }


        Optional<GrammarForm.Expression> thenForm = switch (matchToken(p, MATCH_RIGHT_ARROW)) {
            case boolean found when found && isExpression(p) instanceof MatchResult.Found(
                    GrammarForm.Expression expr
            ) -> Optional.of(expr);
            case boolean found when found -> throw InvalidGrammarException.expected(p.peek(), "Expression");
            default -> Optional.empty();
        };

        Optional<GrammarForm.Expression> elseForm = switch (matchToken(p, MATCH_COLON)) {
            case boolean found when found && isExpression(p) instanceof MatchResult.Found(
                    GrammarForm.Expression expr
            ) -> Optional.of(expr);
            case boolean found when found -> throw InvalidGrammarException.expected(p.peek(), "Expression");
            default -> Optional.empty();
        };
        return MatchResult.of(new GrammarForm.PredicateForm(thenForm, elseForm));
    }

    // ::= ('|' { Parameter } '|' Expr)
    private static MatchResult isLambdaForm(Parser p) throws InvalidGrammarException {
        if (!matchToken(p, MATCH_BAR)) { return MatchResult.NONE; }

        GrammarForm.Parameters params = isParameters(p) instanceof MatchResult.Found(GrammarForm.Parameters paramForm)
                ? paramForm
                : GrammarForm.Parameters.EMPTY;

        if (!matchToken(p, MATCH_BAR)) { throw InvalidGrammarException.expected(p.peek(), "|"); }

        if (isExpression(p) instanceof MatchResult.Found(GrammarForm.Expression expr)) {
            return MatchResult.of(new GrammarForm.Expression.LambdaFormExpr(params, expr));
        } else { throw InvalidGrammarException.expected(p.peek(), "Expression"); }
    }

    private static MatchResult isBExpression(Parser p) {
        return MatchResult.NONE;
    }


    /*------
    | TYPES |
    |------*/
    private static boolean isType(Parser p) throws InvalidGrammarException {
        Token t = p.peek();

        return switch (t.tokenType()) {
            case TokenType.Literal.Identifier -> {
                p.consumeN(1);
                yield true;
            }
            case TokenType.BuiltIn.Fn -> {
                p.consumeN(1);
                if (isFuncType(p)) {
                    yield true;
                } else { throw InvalidGrammarException.expected(t, "Fn<Type>"); }
            }
            case TokenType.BuiltIn.Array -> {
                p.consumeN(1);
                if (isArrayType(p)) {
                    yield true;
                } else { throw InvalidGrammarException.expected(t, "Array<Type>"); }
            }
            default -> false;
        };
    }

    private static boolean isFuncType(Parser p) throws InvalidGrammarException {
        Token t = p.peek();

        if (t.tokenType() == TokenType.LEFT_ANGLE_BRACKET) {
            p.consumeN(1);
        } else { throw InvalidGrammarException.expected(t, "<"); }

        while (isType(p)) {/*Spin through param types */}

        t = p.peek();
        if (t.tokenType() == TokenType.Syntactic.SemiColon) {
            p.consumeN(1);
            if (!isType(p)) { throw InvalidGrammarException.expected(t, "<t1, t1; !ERROR!>"); }
        } else { throw InvalidGrammarException.expected(t, ";"); }

        t = p.peek();
        if (t.tokenType() == TokenType.RIGHT_ANGLE_BRACKET) {
            p.consumeN(1);
            return true;
        } else { throw InvalidGrammarException.expected(t, ">"); }
    }


    private static boolean isArrayType(Parser p) throws InvalidGrammarException {
        Token t = p.peek();

        if (t.tokenType() == TokenType.LEFT_ANGLE_BRACKET) {
            p.consumeN(1);
        } else { throw InvalidGrammarException.expected(t, "<"); }

        if (!isType(p)) {
            throw InvalidGrammarException.expected(t, "Array<!ERROR!>");
        }

        t = p.peek();
        if (t.tokenType() == TokenType.RIGHT_ANGLE_BRACKET) {
            p.consumeN(1);
            return true;
        } else { throw InvalidGrammarException.expected(t, ">"); }

    }

}
