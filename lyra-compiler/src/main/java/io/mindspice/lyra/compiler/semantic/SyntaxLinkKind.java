/** Category of a syntax-to-semantic link. */
package io.mindspice.lyra.compiler.semantic;

public enum SyntaxLinkKind {
    PROGRAM,
    IMPORT,
    IMPORT_PATH,
    IMPORT_BINDING,
    DECLARATION,
    PARAMETER,
    PREDICATE_BINDING,
    REFERENCE,
    TYPE,
    LAMBDA,
    BLOCK,
    CONDITIONAL,
    MATCH,
    COND,
    ACCESS,
    CALL,
    LITERAL,
    EXPRESSION
}
