package Parse;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class Grammar {
    public static final Predicate<Token> MATCH_METHOD_ACCESS = t -> t.tokenType() == TokenType.METHOD_SPACE_ACCESS;
    public static final Predicate<Token> MATCH_FIELD_ACCESS = t -> t.tokenType() == TokenType.FIELD_SPACE_ACCESS;
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

        record Found(Form form) implements MatchResult { }

        record None() implements MatchResult { }

        static MatchResult of(Form form) {
            return new Found(form);
        }

        default boolean isFound() {
            return this instanceof MatchResult.Found;
        }

        default boolean isNone() {
            return this instanceof MatchResult.None;
        }
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


    public static MatchResult findNextMatch() {
        return null;
    }

    /* STATEMENTS  */


    private static MatchResult isStatement(Parser p) {
        return null;
    }


    // ::= 'let' { Modifier } Identifier [ (':' Type) ] '=' Expr
    private static MatchResult isLetStatement(Parser p) throws InvalidGrammarException {
        boolean hasType = false;

        // ::= 'let'
        if (!matchToken(p, MATCH_LET)) {
            return MatchResult.NONE;
        }

        // ::= { Modifier }
        int modifierCount = isModifiers(p);

        // ::= Identifier
        if (!matchToken(p, MATCH_IDENTIFIER)) {
            throw InvalidGrammarException.expected(p.peek(), "Identifier");
        }

        // ::= (':' Type)
        if (matchToken(p, MATCH_COLON)) {
            if (isType(p)) {
                hasType = true;
            } else { throw InvalidGrammarException.expected(p.peek(), "Type"); }
        } else {
            throw InvalidGrammarException.expected(p.peek(), ":");
        }

        // ::= '='
        if (!matchToken(p, MATCH_EQUAL)) {
            throw InvalidGrammarException.expected(p.peek(), "=");
        }

        // ::= Expr


    }


    /* EXPRESSIONS */
    private static MatchResult isExpression(Parser p) {
        return null;
    }

    // ::= '{' { Expr | Stmnt } '}'
    private static MatchResult isBlockExpression(Parser p) {
        if (!matchToken(p, MATCH_LEFT_BRACE)) { return MatchResult.NONE; }

        List<MatchResult> blockMembers = new ArrayList<>(5);

        // Collect inner Expressions/Statements
        while (!matchToken(p, MATCH_RIGHT_BRACE)) { blockMembers.add(findNextMatch()); }

        // Yummy recursive patterns, these are best left as a more concrete form and not a result for inner assignments
        List<Form> memberForms = blockMembers.stream()
                .filter(m -> m instanceof MatchResult.Found)
                .map(f -> ((MatchResult.Found) f).form)
                .toList();

        return MatchResult.of(new Form.Expression.BlockExpr(memberForms));
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
        if (isLambdaForm(p) instanceof MatchResult.Found(Form.LambdaForm expr)) {
            // ::= ')'
            if (!matchToken(p, MATCH_RIGHT_PAREN)) { throw InvalidGrammarException.expected(p.peek(), ")"); }
            return MatchResult.of(new Form.Expression.LambdaExpr(hasType, expr));
        } else { throw InvalidGrammarException.expected(p.peek(), "Expression"); }
    }

    // ::= ('|' { Parameter } '|' Expr)
    private static MatchResult isLambdaForm(Parser p) throws InvalidGrammarException {
        if (!matchToken(p, MATCH_BAR)) { return MatchResult.NONE; }

        Form.Parameters params = isParameters(p) instanceof MatchResult.Found(Form.Parameters paramForm)
                ? paramForm
                : Form.Parameters.EMPTY;

        if (!matchToken(p, MATCH_BAR)) { throw InvalidGrammarException.expected(p.peek(), "|"); }

        if (isExpression(p) instanceof MatchResult.Found(Form.Expression expr)) {
            return MatchResult.of(new Form.LambdaForm(params, expr));
        } else { throw InvalidGrammarException.expected(p.peek(), "Expression"); }
    }

    private static MatchResult isBExpression(Parser p) {
        return MatchResult.NONE;
    }

    private static MatchResult isSExpression(Parser p) {

    }


    private static MatchResult isParameter(Parser p) throws InvalidGrammarException {
        // ::= { Modifier }
        int modifierCount = isModifiers(p);

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

        return MatchResult.of(new Form.Param(modifierCount, hasType));
    }

    private static MatchResult isParameters(Parser p) throws InvalidGrammarException {
        List<Form.Param> params = new ArrayList<>(5);

        while (isParameter(p) instanceof MatchResult.Found(Form.Param form)) {
            params.add(form);
        }
        return MatchResult.of(new Form.Parameters(params));
    }


    private static int isModifiers(Parser p) {
        return matchMultiple(p, MATCH_MODIFIER);
    }

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
