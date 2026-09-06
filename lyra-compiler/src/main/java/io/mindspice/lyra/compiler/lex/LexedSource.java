package io.mindspice.lyra.compiler.lex;

import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.source.SourceSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Complete immutable output of the lexical phase.  A successful value always
 * contains exactly one final EOF token and never contains a partial token
 * stream.
 */
public record LexedSource(
        SourceSnapshot snapshot,
        List<Token> tokens,
        List<Diagnostic> diagnostics) implements ImmutablePhaseArtifact {
    public LexedSource {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(tokens, "tokens");
        Objects.requireNonNull(diagnostics, "diagnostics");
        tokens = List.copyOf(tokens);
        diagnostics = List.copyOf(diagnostics);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("lexed source must contain EOF");
        }

        int eofIndex = tokens.size() - 1;
        for (int index = 0; index < tokens.size(); index++) {
            Token token = Objects.requireNonNull(tokens.get(index), "tokens must not contain null");
            token.span().validateAgainst(snapshot);
            if (!token.lexeme().equals(snapshot.text().substring(
                    token.span().startOffset(), token.span().endOffset()))) {
                throw new IllegalArgumentException("token lexeme does not match its source span");
            }
            if (token.kind() == TokenKind.EOF && index != eofIndex) {
                throw new IllegalArgumentException("EOF must be the final token");
            }
            for (Trivia trivia : token.leadingTrivia()) {
                Objects.requireNonNull(trivia, "leading trivia must not contain null");
                trivia.span().validateAgainst(snapshot);
                if (!trivia.lexeme().equals(snapshot.text().substring(
                        trivia.span().startOffset(), trivia.span().endOffset()))) {
                    throw new IllegalArgumentException("trivia lexeme does not match its source span");
                }
            }
        }
        if (tokens.get(eofIndex).kind() != TokenKind.EOF) {
            throw new IllegalArgumentException("lexed source must end in EOF");
        }
        for (Diagnostic diagnostic : diagnostics) {
            Objects.requireNonNull(diagnostic, "diagnostics must not contain null");
            diagnostic.validateAgainst(snapshot);
        }
    }

    public SourceSnapshot source() {
        return snapshot;
    }

    /** All retained whitespace and comments in source order. */
    public List<Trivia> trivia() {
        List<Trivia> result = new ArrayList<>();
        for (Token token : tokens) {
            result.addAll(token.leadingTrivia());
        }
        return List.copyOf(result);
    }

    public Token eof() {
        return tokens.get(tokens.size() - 1);
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity().isError());
    }
}
