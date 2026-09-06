package io.mindspice.lyra.compiler.parse;

import io.mindspice.lyra.compiler.grammar.GrammarDescriptor;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.Token;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.source.SourceSpan;

import java.util.List;
import java.util.Objects;

/** Shared exact source/token operations used by parser replay. */
final class ReplaySource {
    private final SourceSnapshot snapshot;
    private final List<Token> tokens;

    ReplaySource(LexedSource lexedSource) {
        Objects.requireNonNull(lexedSource, "lexedSource");
        this.snapshot = lexedSource.snapshot();
        this.tokens = lexedSource.tokens();
    }

    SourceSnapshot snapshot() {
        return snapshot;
    }

    List<Token> tokens() {
        return tokens;
    }

    Token token(int index) {
        if (index < 0 || index >= tokens.size()) {
            throw new ParserInvariantException("token index " + index + " is outside the lexical artifact");
        }
        return tokens.get(index);
    }

    SourceSpan tokenSpan(int index) {
        return token(index).span();
    }

    /** Creates a span from the first and last token in an end-exclusive range. */
    SourceSpan span(int startTokenIndex, int endTokenIndex) {
        if (startTokenIndex < 0
                || endTokenIndex <= startTokenIndex
                || endTokenIndex > tokens.size()) {
            throw new ParserInvariantException(
                    "cannot derive a source span from token range ["
                            + startTokenIndex + ", " + endTokenIndex + ")");
        }
        SourceSpan first = tokenSpan(startTokenIndex);
        SourceSpan last = tokenSpan(endTokenIndex - 1);
        if (!first.sourceId().equals(last.sourceId())) {
            throw new ParserInvariantException("token range crosses source identities");
        }
        return SourceSpan.of(first.sourceId(), first.startOffset(), last.endOffset());
    }

    SourceSpan span(GrammarDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        return span(descriptor.startTokenIndex(), descriptor.endTokenIndex());
    }

    String sourceText(GrammarDescriptor descriptor) {
        SourceSpan span = span(descriptor);
        return snapshot.text().substring(span.startOffset(), span.endOffset());
    }

    String sourceText(SourceSpan span) {
        span.validateAgainst(snapshot);
        return snapshot.text().substring(span.startOffset(), span.endOffset());
    }
}
