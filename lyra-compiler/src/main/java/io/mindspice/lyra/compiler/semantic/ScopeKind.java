/** Lexical regions published by semantic resolution. */
package io.mindspice.lyra.compiler.semantic;

public enum ScopeKind {
    MODULE,
    NOMINAL,
    LAMBDA,
    BLOCK,
    CONDITIONAL_BRANCH
}
