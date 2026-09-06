package io.mindspice.lyra.compiler.semantic;

/** The closed set of current-language expression and operation forms. */
public enum TypedExpressionKind {
    LITERAL,
    REFERENCE,
    DECLARATION,
    REBINDING,
    BLOCK,
    CONDITIONAL,
    COALESCE,
    LAMBDA,
    CALLABLE_CALL,
    DIRECT_CALL,
    MEMBER_ACCESS,
    NAMESPACE_MEMBER_ACCESS,
    NAMESPACE_DIRECT_CALL,
    ARRAY_LITERAL,
    TUPLE_LITERAL,
    INDEX_ACCESS,
    OPERATOR,
    SHORT_CIRCUIT,
    CONVERSION,
    NARROWING
}
