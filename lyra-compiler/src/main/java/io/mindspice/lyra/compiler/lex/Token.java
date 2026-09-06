package io.mindspice.lyra.compiler.lex;

import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A deeply immutable lexical token with its exact source spelling and trivia. */
public record Token(
        TokenKind kind,
        String lexeme,
        SourceSpan span,
        List<Trivia> leadingTrivia,
        TokenValue value) {
    public Token {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(lexeme, "lexeme");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(leadingTrivia, "leadingTrivia");
        Objects.requireNonNull(value, "value");
        leadingTrivia = List.copyOf(leadingTrivia);
        if (span.length() != lexeme.length()) {
            throw new IllegalArgumentException("token span must cover its UTF-16 lexeme");
        }
        if (kind == TokenKind.EOF && (!lexeme.isEmpty() || !span.isEmpty())) {
            throw new IllegalArgumentException("EOF must have an empty zero-width spelling");
        }
        if (kind.isLiteral() != (value instanceof LiteralValue)) {
            throw new IllegalArgumentException("literal token/value kinds do not agree");
        }
        if (kind == TokenKind.IDENTIFIER && !(value instanceof TokenValue.Identifier)) {
            throw new IllegalArgumentException("identifier token must carry an identifier value");
        }
        if (kind == TokenKind.MODIFIER && !(value instanceof TokenValue.Modifier)) {
            throw new IllegalArgumentException("modifier token must carry a modifier value");
        }
    }

    public Token(TokenKind kind, String lexeme, SourceSpan span) {
        this(kind, lexeme, span, List.of(), TokenValue.None.INSTANCE);
    }

    /** Compatibility-style name for consumers that call the category token kind. */
    public TokenKind tokenKind() {
        return kind;
    }

    public String text() {
        return lexeme;
    }

    public SourceSpan sourceSpan() {
        return span;
    }

    public List<Trivia> trivia() {
        return leadingTrivia;
    }

    public boolean hasLeadingTrivia() {
        return !leadingTrivia.isEmpty();
    }

    /** True only when actual whitespace, rather than only a comment, precedes this token. */
    public boolean hasLeadingWhitespace() {
        return leadingTrivia.stream().anyMatch(Trivia::isWhitespace);
    }

    public boolean precededByWhitespace() {
        return hasLeadingWhitespace();
    }

    /** True when no whitespace or comment was retained between this and the prior token. */
    public boolean isAdjacentToPrevious() {
        return leadingTrivia.isEmpty();
    }

    public Optional<LiteralValue> literal() {
        return value instanceof LiteralValue literal
                ? Optional.of(literal)
                : Optional.empty();
    }

    public Optional<LiteralValue> literalValue() {
        return literal();
    }

    public Optional<String> identifier() {
        return value instanceof TokenValue.Identifier identifier
                ? Optional.of(identifier.name())
                : Optional.empty();
    }

    public Optional<ModifierKind> modifier() {
        return value instanceof TokenValue.Modifier modifier
                ? Optional.of(modifier.kind())
                : Optional.empty();
    }

    /** Returns the exact source substring after validating the token's source identity. */
    public String sourceText(SourceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        span.validateAgainst(snapshot);
        return snapshot.text().substring(span.startOffset(), span.endOffset());
    }
}
