package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Authoritative lexical state machine shared by plain and rich console input.
 * It deliberately follows the compiler's comment and literal boundaries;
 * parsing is still left to the compiler after a unit is complete.
 */
public final class LexicalCompleteness {
    private static final SourceId COMPLETENESS_SOURCE =
            SourceId.path("repl-completeness.lyra");
    private static final PhysicalSourceKey COMPLETENESS_PHYSICAL_KEY =
            PhysicalSourceKey.uri(URI.create("memory:repl-completeness"));

    private LexicalCompleteness() {
    }

    public static State inspect(CharSequence source) {
        Deque<Character> delimiters = new ArrayDeque<>();
        Mode mode = Mode.NORMAL;
        int blockDepth = 0;
        boolean escaped = false;
        boolean invalid = false;

        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : 0;
            switch (mode) {
                case LINE_COMMENT -> {
                    if (character == '\n' || character == '\r') {
                        mode = Mode.NORMAL;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (character == '/' && next == '*') {
                        blockDepth++;
                        index++;
                    } else if (character == '*' && next == '/') {
                        blockDepth--;
                        index++;
                        if (blockDepth == 0) {
                            mode = Mode.NORMAL;
                        }
                    }
                }
                case STRING -> {
                    if (character == '\n' || character == '\r') {
                        // The lexer reports this as an invalid literal. Submit
                        // now so the console does not swallow subsequent input,
                        // including the escaped-newline case.
                        mode = Mode.NORMAL;
                        escaped = false;
                        invalid = true;
                    } else if (escaped) {
                        escaped = false;
                    } else if (character == '\\') {
                        escaped = true;
                    } else if (character == '"') {
                        mode = Mode.NORMAL;
                    }
                }
                case CHAR -> {
                    if (character == '\n' || character == '\r') {
                        mode = Mode.NORMAL;
                        escaped = false;
                        invalid = true;
                    } else if (escaped) {
                        escaped = false;
                    } else if (character == '\\') {
                        escaped = true;
                    } else if (character == '\'') {
                        mode = Mode.NORMAL;
                    }
                }
                case NORMAL -> {
                    if (character == '/' && next == '/') {
                        mode = Mode.LINE_COMMENT;
                        index++;
                    } else if (character == '/' && next == '*') {
                        mode = Mode.BLOCK_COMMENT;
                        blockDepth = 1;
                        index++;
                    } else if (character == '"') {
                        mode = Mode.STRING;
                        escaped = false;
                    } else if (character == '\'') {
                        mode = Mode.CHAR;
                        escaped = false;
                    } else if (character == '(' || character == '{' || character == '[') {
                        delimiters.push(character);
                    } else if (character == ')' || character == '}' || character == ']') {
                        if (delimiters.isEmpty() || !matches(delimiters.peek(), character)) {
                            invalid = true;
                        } else {
                            delimiters.pop();
                        }
                    }
                }
            }
        }
        boolean scannerIncomplete = mode == Mode.BLOCK_COMMENT
                || mode == Mode.STRING
                || mode == Mode.CHAR;
        boolean compilerInvalid = !scannerIncomplete && compilerLexerRejects(source);
        invalid |= compilerInvalid;
        boolean incomplete = !invalid && (scannerIncomplete || !delimiters.isEmpty());
        return new State(!incomplete, invalid, ListCopy.copy(delimiters));
    }

    /**
     * Reuses the compiler lexer for closed lexical units so completeness does
     * not invent a second accepted escape/operator/number vocabulary. The
     * incremental scanner above remains responsible for genuinely unfinished
     * comments and literals, which the compiler intentionally rejects as a
     * whole-source phase failure.
     */
    private static boolean compilerLexerRejects(CharSequence source) {
        String text = source.toString();
        var captured = SourceSnapshot.capture(
                COMPLETENESS_SOURCE,
                COMPLETENESS_PHYSICAL_KEY,
                text.getBytes(StandardCharsets.UTF_8));
        if (!(captured instanceof PhaseResult.Success<SourceSnapshot> success)
                || !success.value().text().equals(text)) {
            return false;
        }
        return Lexer.lex(success.value()) instanceof PhaseResult.Failure<?>;
    }

    private static boolean matches(char opener, char closer) {
        return opener == '(' && closer == ')'
                || opener == '{' && closer == '}'
                || opener == '[' && closer == ']';
    }

    enum Mode {
        NORMAL, LINE_COMMENT, BLOCK_COMMENT, STRING, CHAR
    }

    public record State(boolean complete, boolean invalid, java.util.List<Character> openDelimiters) {
        public State {
            openDelimiters = java.util.List.copyOf(openDelimiters);
        }

        public boolean incomplete() {
            return !complete;
        }
    }

    /** Keeps delimiter order without exposing the mutable scanner stack. */
    private static final class ListCopy {
        private ListCopy() {
        }

        static java.util.List<Character> copy(Deque<Character> values) {
            ArrayDeque<Character> copy = new ArrayDeque<>(values);
            java.util.ArrayList<Character> result = new java.util.ArrayList<>(copy);
            java.util.Collections.reverse(result);
            return result;
        }
    }
}
