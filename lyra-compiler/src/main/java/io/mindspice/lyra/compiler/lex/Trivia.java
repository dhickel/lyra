package io.mindspice.lyra.compiler.lex;

import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.Objects;

/**
 * Immutable source text ignored by the grammar but retained for diagnostics
 * and spacing-sensitive syntax.
 */
public record Trivia(TriviaKind kind, String lexeme, SourceSpan span) {
    public Trivia {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(lexeme, "lexeme");
        Objects.requireNonNull(span, "span");
        if (lexeme.isEmpty()) {
            throw new IllegalArgumentException("trivia lexeme must not be empty");
        }
        if (span.length() != lexeme.length()) {
            throw new IllegalArgumentException("trivia span must cover its UTF-16 lexeme");
        }
    }

    public boolean isWhitespace() {
        return kind == TriviaKind.WHITESPACE;
    }

    public boolean isComment() {
        return kind.isComment();
    }

    public String text() {
        return lexeme;
    }
}
