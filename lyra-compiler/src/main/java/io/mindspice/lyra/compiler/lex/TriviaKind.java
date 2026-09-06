package io.mindspice.lyra.compiler.lex;

/** Kinds of source text retained between lexical tokens. */
public enum TriviaKind {
    WHITESPACE,
    LINE_COMMENT,
    BLOCK_COMMENT;

    public boolean isComment() {
        return this == LINE_COMMENT || this == BLOCK_COMMENT;
    }
}
