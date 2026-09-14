package io.mindspice.lyra.cli;

import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.lex.Token;
import io.mindspice.lyra.compiler.lex.TokenKind;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import org.jline.reader.LineReader;
import org.jline.reader.impl.DefaultHighlighter;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Presentation from the real lexer. Never decides whether to submit or parses forms. */
final class LyraHighlighter extends DefaultHighlighter {
    private static final URI INPUT = URI.create("repl:console-highlight");
    private static final int MAX_HIGHLIGHT_CHARACTERS = 16 * 1024;
    private String cachedSource;
    private List<Token> tokens = List.of();
    private final Map<Integer, Integer> matches = new HashMap<>();

    @Override
    public AttributedString highlight(LineReader reader, String source) {
        AttributedString base = super.highlight(reader, source);
        // Preserve JLine's safe display of control characters and Unicode. Token
        // spans refer to the original UTF-16 text, not its escaped presentation.
        if (!base.toString().equals(source) || source.length() > MAX_HIGHLIGHT_CHARACTERS) {
            return base;
        }
        scan(source);
        int[] colors = new int[source.length()];
        Arrays.fill(colors, -1);
        for (Token token : tokens) {
            int color = switch (token.kind()) {
                case LET, IMPORT, AS, MATCH, COND, ITER, WHILE, WHEN -> AttributedStyle.MAGENTA;
                case TYPE_NAME, MODIFIER -> AttributedStyle.CYAN;
                case STRING_LITERAL, CHAR_LITERAL -> AttributedStyle.GREEN;
                case INTEGER_LITERAL, FLOAT_LITERAL, BOOLEAN_LITERAL, NIL_LITERAL -> AttributedStyle.YELLOW;
                default -> -1;
            };
            Arrays.fill(colors, token.span().startOffset(), token.span().endOffset(), color);
            for (var trivia : token.leadingTrivia()) {
                if (trivia.isComment()) {
                    Arrays.fill(colors, trivia.span().startOffset(), trivia.span().endOffset(),
                            AttributedStyle.BRIGHT + AttributedStyle.BLACK);
                }
            }
        }
        if (isCommandLine(source)) {
            int start = source.indexOf('\\');
            int end = start;
            while (end < source.length() && !Character.isWhitespace(source.charAt(end))) {
                colors[end++] = AttributedStyle.CYAN;
            }
        }
        int cursor = source.offsetByCodePoints(0,
                Math.min(reader.getBuffer().cursor(), source.codePointCount(0, source.length())));
        int selected = selectedDelimiter(cursor);
        int match = matches.getOrDefault(selected, -1);
        AttributedStringBuilder result = new AttributedStringBuilder();
        for (int index = 0; index < source.length(); index++) {
            AttributedStyle style = base.styleAt(index);
            if (colors[index] >= 0) {
                style = style.foreground(colors[index]);
            }
            if (match >= 0 && (index == selected || index == match)) {
                style = style.bold().inverse();
            }
            result.style(style).append(source.charAt(index));
        }
        return result.toAttributedString();
    }

    int matchingDelimiter(String source, int cursor) {
        scan(source);
        return matches.getOrDefault(selectedDelimiter(cursor), -1);
    }

    private int selectedDelimiter(int cursor) {
        return matches.containsKey(cursor) ? cursor : cursor - 1;
    }

    private void scan(String source) {
        if (source.equals(cachedSource)) {
            return;
        }
        cachedSource = source;
        tokens = List.of();
        matches.clear();
        if (source.length() > MAX_HIGHLIGHT_CHARACTERS) {
            return;
        }
        var captured = SourceSnapshot.capture(SourceId.uri(INPUT), PhysicalSourceKey.uri(INPUT),
                source.getBytes(StandardCharsets.UTF_8));
        if (!(captured instanceof PhaseResult.Success<SourceSnapshot> snapshot)
                || !snapshot.value().text().equals(source)
                || !(Lexer.lex(snapshot.value()) instanceof PhaseResult.Success<LexedSource> lexed)) {
            // Incomplete/invalid lexical input is still editable. The compiler
            // owns its eventual diagnostic; do not invent a tolerant parser.
            return;
        }
        tokens = lexed.value().tokens();
        ArrayDeque<Token> open = new ArrayDeque<>();
        for (Token token : tokens) {
            switch (token.kind()) {
                case LEFT_PAREN, LEFT_BRACE, LEFT_BRACKET -> open.push(token);
                case RIGHT_PAREN, RIGHT_BRACE, RIGHT_BRACKET -> {
                    if (!open.isEmpty() && closes(open.peek().kind(), token.kind())) {
                        int start = open.pop().span().startOffset();
                        int end = token.span().startOffset();
                        matches.put(start, end);
                        matches.put(end, start);
                    } else {
                        // Do not suggest a pair crossing a mismatched delimiter.
                        open.clear();
                    }
                }
                default -> { /* Literals and comment trivia cannot supply delimiters. */ }
            }
        }
    }

    private static boolean isCommandLine(String source) {
        return source.stripLeading().startsWith("\\");
    }

    private static boolean closes(TokenKind open, TokenKind close) {
        return open == TokenKind.LEFT_PAREN && close == TokenKind.RIGHT_PAREN
                || open == TokenKind.LEFT_BRACE && close == TokenKind.RIGHT_BRACE
                || open == TokenKind.LEFT_BRACKET && close == TokenKind.RIGHT_BRACKET;
    }
}
