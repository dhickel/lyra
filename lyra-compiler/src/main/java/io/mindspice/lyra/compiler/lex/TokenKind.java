package io.mindspice.lyra.compiler.lex;

/**
 * The complete lexical vocabulary of the current Lyra language.
 *
 * <p>Context-sensitive distinctions such as whether a comma is legal in a
 * particular list are deliberately left to later phases.  The lexer records
 * the comma as {@link #COMMA} rather than discarding it.</p>
 */
public enum TokenKind {
    EOF,

    IDENTIFIER,
    LET,
    STRUCT,
    CLASS,
    IMPORT,
    AS,
    MATCH,
    ITER,
    WHILE,
    WHEN,
    TYPE_NAME,
    MODIFIER,

    BOOLEAN_LITERAL,
    NIL_LITERAL,
    INTEGER_LITERAL,
    FLOAT_LITERAL,
    STRING_LITERAL,
    CHAR_LITERAL,

    PLUS,
    MINUS,
    ASTERISK,
    SLASH,
    CARET,
    PERCENT,
    LESS,
    LESS_EQUAL,
    GREATER,
    GREATER_EQUAL,
    EQUAL,
    EQUAL_EQUAL,
    NOT_EQUAL,
    IDENTITY_EQUAL,
    IDENTITY_NOT_EQUAL,
    AND,
    OR,
    XOR,
    NOT,
    INCREMENT,
    DECREMENT,

    COLON_EQUAL,
    DOUBLE_QUESTION,
    RANGE_EXCLUSIVE,
    RANGE_INCLUSIVE,
    ARROW,
    COLON_DOT,
    DOUBLE_COLON,
    LAMBDA_ARROW,

    LEFT_PAREN,
    RIGHT_PAREN,
    LEFT_BRACE,
    RIGHT_BRACE,
    LEFT_BRACKET,
    RIGHT_BRACKET,
    COLON,
    SEMICOLON,
    BAR,
    COMMA,
    PERIOD;

    public boolean isLiteral() {
        return switch (this) {
            case BOOLEAN_LITERAL, NIL_LITERAL, INTEGER_LITERAL, FLOAT_LITERAL,
                    STRING_LITERAL, CHAR_LITERAL -> true;
            default -> false;
        };
    }

    /** Reserved call heads using ordinary callback argument syntax. */
    public boolean isCallbackLoopKeyword() {
        return this == ITER || this == WHILE;
    }

    public boolean isOperator() {
        return switch (this) {
            case PLUS, MINUS, ASTERISK, SLASH, CARET, PERCENT,
                    LESS, LESS_EQUAL, GREATER, GREATER_EQUAL,
                    EQUAL_EQUAL, NOT_EQUAL, IDENTITY_EQUAL, IDENTITY_NOT_EQUAL,
                    AND, OR, XOR, NOT, INCREMENT, DECREMENT -> true;
            default -> false;
        };
    }

    public boolean isAccessor() {
        return this == ARROW || this == COLON_DOT || this == DOUBLE_COLON;
    }

    public boolean isPunctuation() {
        return switch (this) {
            case COLON_EQUAL, DOUBLE_QUESTION, RANGE_EXCLUSIVE, RANGE_INCLUSIVE, ARROW, COLON_DOT, DOUBLE_COLON, LAMBDA_ARROW,
                    LEFT_PAREN, RIGHT_PAREN, LEFT_BRACE, RIGHT_BRACE,
                    LEFT_BRACKET, RIGHT_BRACKET, COLON, SEMICOLON, BAR,
                    COMMA, PERIOD, EQUAL -> true;
            default -> false;
        };
    }
}
