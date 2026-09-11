package io.mindspice.lyra.compiler.grammar;

/**
 * Syntax-only productions emitted by the grammar phase.
 *
 * <p>The set deliberately contains no semantic, type-resolution, or deferred
 * language productions.  A descriptor kind says how a later parser replay
 * should interpret the token range; it does not say whether names or members
 * are semantically valid.</p>
 */
public enum ProductionKind {
    PROGRAM,
    EOF,

    IMPORT_DECLARATION,
    IMPORT_PATH,
    IMPORT_ALIAS,
    IMPORT_SELECTION,
    IMPORT_ITEM,

    LET_BINDING,
    REASSIGNMENT,
    PREFIX_ASSIGNMENT,
    BLOCK,
    CONDITIONAL,
    PREDICATE_BINDING,
    COALESCE,
    MATCH,
    MATCH_ARM,

    IDENTIFIER,
    LITERAL,
    TYPE_NAME,
    UNIT_LITERAL,

    LAMBDA,
    COMPACT_LAMBDA,
    PARAMETER_LIST,
    PARAMETER,

    TYPE_ANNOTATION,
    RETURN_ANNOTATION,
    TYPE_CONTRACT,
    PRIMITIVE_TYPE,
    ARRAY_TYPE,
    TUPLE_TYPE,
    FUNCTION_TYPE,
    TYPE_ARGUMENT_LIST,
    TYPE_SEPARATOR,

    CALLABLE_CALL,
    CALL_CONTENT,
    CALL_TARGET,
    DIRECT_CALL,
    MEMBER_ACCESS,
    NAMESPACE_MEMBER_ACCESS,
    NAMESPACE_DIRECT_CALL,
    NAMESPACE_PATH,
    INDEX_ACCESS,

    ARGUMENT_LIST,
    ARGUMENT,
    OPERATOR_OPERANDS,
    OPERATOR,
    OPERATOR_S_EXPRESSION,
    OPERATOR_BRACKET,

    ARRAY_LITERAL,
    TUPLE_LITERAL,
    TYPE_CONVERSION,

    MEMBER_NAME,
    MODIFIER,
    COMMA
}
