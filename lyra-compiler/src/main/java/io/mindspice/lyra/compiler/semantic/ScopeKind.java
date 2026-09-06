/** Lexical regions published by semantic resolution. */
package io.mindspice.lyra.compiler.semantic;

public enum ScopeKind {
    MODULE,
    LAMBDA,
    BLOCK,
    CONDITIONAL_BRANCH
}
