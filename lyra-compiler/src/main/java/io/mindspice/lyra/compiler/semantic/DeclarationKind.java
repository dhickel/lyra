/** Kinds of names in Lyra's single identifier namespace. */
package io.mindspice.lyra.compiler.semantic;

public enum DeclarationKind {
    LET,
    /** A typed value supplied by the persistent session namespace. */
    EXTERNAL,
    PARAMETER,
    IMPORT_MODULE,
    IMPORT_VALUE,
    PREDICATE_BINDING,
    INTRINSIC_EXPORT
}
