package io.mindspice.lyra.cli;

import io.mindspice.lyra.repl.LexicalCompleteness;
import io.mindspice.lyra.repl.PlainConsole;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.Candidate;
import org.jline.reader.CompletingParsedLine;
import org.jline.reader.EndOfFileException;
import org.jline.reader.EOFError;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.MaskingCallback;
import org.jline.reader.ParsedLine;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Widget;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.spi.SystemStream;
import org.jline.terminal.spi.TerminalProvider;

import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.jline.reader.LineReader.*;

/** CLI-only editing adapter. PlainConsole remains the command/evaluation owner. */
final class JLineConsole implements PlainConsole.SourceReader {
    static final String PROMPT = "lyra> ";
    static final String CONTINUATION_PROMPT = "...> ";
    private static final List<String> COMMANDS = List.of(
            ":help", ":bindings", ":type", ":load", ":reload", ":reset",
            ":history", ":quit");
    private static final List<String> TYPE_NAMES = List.of(
            "I8", "I16", "I32", "I64", "U8", "U16", "U32", "U64",
            "F32", "F64", "Bool", "Char", "String", "Unit", "Array", "Tuple", "Fn");
    private static final MaskingCallback SOURCE_HISTORY_ONLY = new MaskingCallback() {
        @Override
        public String display(String line) {
            return line;
        }

        @Override
        public String history(String line) {
            // The shared console supplies submitted source on the next read.
            // Never retain commands, aborted input, or program input here.
            return null;
        }
    };

    private final Terminal terminal;
    private final LineReader reader;
    private final DefaultHistory history = new DefaultHistory();
    private final ProgramInput programInput;
    private List<String> submitted = List.of();
    private boolean eof;

    JLineConsole(Terminal terminal, String keymap) {
        this.terminal = terminal;
        LyraHighlighter highlighter = new LyraHighlighter();
        reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("Lyra")
                // Do not import ambient JLine history-file/inputrc settings. A
                // NUL prefix cannot be supplied as a JVM command-line property.
                .variable(SYSTEM_PROPERTY_PREFIX, "\0")
                .parser(new DefaultParser() {
                    @Override
                    public ParsedLine parse(String line, int cursor, ParseContext context) {
                        if (context == ParseContext.ACCEPT_LINE
                                && !line.stripLeading().startsWith(":")) {
                            // Enter adds a newline, just as it does in PlainConsole.
                            // Literal newlines are errors, not continuations.
                            var state = LexicalCompleteness.inspect(line + "\n");
                            if (state.incomplete()) {
                                throw new EOFError(-1, -1, "incomplete source", "",
                                        Math.min(20, state.openDelimiters().size()), null);
                            }
                        }
                        return new LyraParsedLine(line, cursor);
                    }
                })
                .highlighter(highlighter)
                .completer(JLineConsole::complete)
                .history(history)
                .variable(SECONDARY_PROMPT_PATTERN, CONTINUATION_PROMPT)
                .variable(INDENTATION, 2)
                .variable(HISTORY_SIZE, 256)
                .variable(BLINK_MATCHING_PAREN, 0)
                .option(Option.DISABLE_EVENT_EXPANSION, true)
                .option(Option.HISTORY_IGNORE_SPACE, false)
                .option(Option.HISTORY_IGNORE_DUPS, false)
                .option(Option.HISTORY_REDUCE_BLANKS, false)
                .option(Option.BRACKETED_PASTE, true)
                .option(Option.INSERT_BRACKET, false)
                .build();
        programInput = new ProgramInput(terminal);
        history.attach(reader);
        reader.getWidgets().put(CALLBACK_INIT, () -> {
            // Normalize CRLF before JLine's paste widget converts remaining CRs
            // to LF. Otherwise a Windows paste gains an extra blank line per row.
            var attributes = terminal.getAttributes();
            attributes.setInputFlag(org.jline.terminal.Attributes.InputFlag.INORMEOL, true);
            terminal.setAttributes(attributes);
            return true;
        });
        reader.getKeyMaps().put(MAIN, reader.getKeyMaps().get(switch (keymap) {
            case "emacs" -> EMACS;
            case "vi" -> VIINS;
            default -> throw new IllegalArgumentException("unknown keymap: " + keymap);
        }));
        for (String map : List.of(EMACS, VIINS)) {
            KeyMap<Binding> keys = reader.getKeyMaps().get(map);
            // JLine's insert-close widgets also delete preceding spaces. Lyra
            // supplies lexical matching, not structural editing or auto-rewrites.
            keys.bind(new Reference(SELF_INSERT), ")", "]", "}");
            keys.bind((Widget) () -> {
                String source = reader.getBuffer().toString();
                int cursor = reader.getBuffer().cursor();
                boolean indentation = source.substring(0, cursor).isEmpty()
                        || source.substring(0, cursor).matches("(?s).*\\n[ \\t]*");
                if (indentation) {
                    reader.getBuffer().write("  ");
                    return true;
                }
                return reader.getBuiltinWidgets().get(COMPLETE_WORD).apply();
            }, "\t");
            keys.bind((Widget) () -> {
                String source = reader.getBuffer().toString();
                if (source.isEmpty() || LexicalCompleteness.inspect(source).incomplete()) {
                    throw new EndOfFileException().partialLine(source);
                }
                return reader.getBuiltinWidgets().get(DELETE_CHAR).apply();
            }, KeyMap.ctrl('D'));
        }
        for (String map : List.of(EMACS, VIINS, VICMD)) {
            KeyMap<Binding> keys = reader.getKeyMaps().get(map);
            // Accept normal cursor keys as well as terminfo's application-mode keys.
            keys.bind(new Reference(UP_LINE_OR_HISTORY), "\033[A", "\033OA");
            keys.bind(new Reference(DOWN_LINE_OR_HISTORY), "\033[B", "\033OB");
            keys.bind(new Reference(BACKWARD_CHAR), "\033[D", "\033OD");
            keys.bind(new Reference(FORWARD_CHAR), "\033[C", "\033OC");
        }
        reader.getWidgets().put(VI_MATCH_BRACKET, () -> {
            String source = reader.getBuffer().toString();
            int cursor = source.offsetByCodePoints(0, reader.getBuffer().cursor());
            int match = highlighter.matchingDelimiter(source, cursor);
            return match >= 0 && reader.getBuffer().cursor(source.codePointCount(0, match));
        });
        // No external editor, shell-style history expansion, or automatic next
        // entry. Navigation/search only select text; Enter is still required.
        reader.getWidgets().put(EDIT_AND_EXECUTE_COMMAND, () -> false);
        reader.getWidgets().put(ACCEPT_AND_HOLD, reader.getBuiltinWidgets().get(ACCEPT_LINE));
        reader.getWidgets().put(ACCEPT_LINE_AND_DOWN_HISTORY, reader.getBuiltinWidgets().get(ACCEPT_LINE));
        reader.getWidgets().put(ACCEPT_AND_INFER_NEXT_HISTORY, reader.getBuiltinWidgets().get(ACCEPT_LINE));
    }

    private static void complete(LineReader lineReader, ParsedLine parsedLine,
                                 List<Candidate> candidates) {
        String line = parsedLine.line();
        int cursor = Math.max(0, Math.min(parsedLine.cursor(), line.length()));
        int start = wordStart(line, cursor);
        String word = line.substring(start, cursor);
        List<String> choices;
        if (isCommandPosition(line, start, cursor)) {
            choices = COMMANDS;
        } else if (isTypePosition(line, start)) {
            choices = TYPE_NAMES;
        } else {
            return;
        }
        for (String choice : choices) {
            if (choice.startsWith(word)) {
                candidates.add(new Candidate(choice));
            }
        }
    }

    private static boolean isCommandPosition(String line, int start, int cursor) {
        if (start >= cursor || line.charAt(start) != ':') {
            return false;
        }
        String prefix = line.substring(0, start);
        if (!prefix.isBlank()) {
            return false;
        }
        for (int index = start; index < cursor; index++) {
            if (Character.isWhitespace(line.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTypePosition(String line, int start) {
        if (start < line.length() && !isIdentifierPart(line.charAt(start))) {
            return false;
        }
        int previous = start - 1;
        while (previous >= 0 && Character.isWhitespace(line.charAt(previous))) {
            previous--;
        }
        return previous >= 0 && ":<[,]".indexOf(line.charAt(previous)) >= 0;
    }

    private static int wordStart(String line, int cursor) {
        int start = cursor;
        while (start > 0 && isIdentifierPart(line.charAt(start - 1))) {
            start--;
        }
        if (start > 0 && line.charAt(start - 1) == ':'
                && line.substring(0, start - 1).isBlank()) {
            start--;
        }
        return start;
    }

    private static int wordEnd(String line, int cursor) {
        int end = cursor;
        while (end < line.length() && isIdentifierPart(line.charAt(end))) {
            end++;
        }
        return end;
    }

    private static boolean isIdentifierPart(char character) {
        return character == '_' || character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9';
    }

    private static final class LyraParsedLine implements CompletingParsedLine {
        private final String line;
        private final int cursor;
        private final int wordStart;
        private final int wordEnd;

        private LyraParsedLine(String line, int cursor) {
            this.line = line;
            this.cursor = Math.max(0, Math.min(cursor, line.length()));
            wordStart = JLineConsole.wordStart(line, this.cursor);
            wordEnd = wordEnd(line, this.cursor);
        }

        @Override
        public String word() {
            return line.substring(wordStart, wordEnd);
        }

        @Override
        public int wordCursor() {
            return cursor - wordStart;
        }

        @Override
        public int wordIndex() {
            return 0;
        }

        @Override
        public List<String> words() {
            return List.of(word());
        }

        @Override
        public String line() {
            return line;
        }

        @Override
        public int cursor() {
            return cursor;
        }

        @Override
        public CharSequence escape(CharSequence candidate, boolean complete) {
            return candidate;
        }

        @Override
        public int rawWordCursor() {
            return wordCursor();
        }

        @Override
        public int rawWordLength() {
            return word().length();
        }
    }

    /** Does not acquire a terminal for pipes, redirected output, or dumb terminals. */
    static Terminal openTerminal() throws IOException {
        var console = System.console();
        if (console == null || !console.isTerminal() || "dumb".equals(System.getenv("TERM"))) {
            return null;
        }
        TerminalProvider provider = TerminalProvider.load("ffm");
        if (!provider.isSystemStream(SystemStream.Input)
                || !provider.isSystemStream(SystemStream.Output)) {
            return null;
        }
        // JLine 4.0.0's POSIX FFM/JNI termios conversion drops baud settings.
        // Its built-in exec provider preserves the native state via stty. Keep
        // the Java-25 FFM stream probe, but never apply that lossy conversion.
        String terminalProvider = org.jline.utils.OSUtils.IS_WINDOWS ? "ffm" : "exec";
        Terminal terminal = TerminalBuilder.builder().name("Lyra").system(true)
                .provider(terminalProvider).dumb(false).encoding(StandardCharsets.UTF_8)
                // Avoid a startup probe that reads ahead before source entry owns input.
                .graphemeCluster(false)
                .systemOutput(TerminalBuilder.SystemOutput.SysOut).build();
        if (Terminal.TYPE_DUMB.equals(terminal.getType())
                || Terminal.TYPE_DUMB_COLOR.equals(terminal.getType())) {
            terminal.close();
            return null;
        }
        return terminal;
    }

    /**
     * Returns the sole program-input view for this terminal. It consumes the
     * terminal reader rather than the process stream, so bytes already queued
     * while JLine accepted source remain available to generated {@code readLine}
     * calls and are never lost in a second reader.
     */
    InputStream programInput() {
        return programInput;
    }

    @Override
    public String readSource(List<String> submittedSource) throws IOException {
        programInput.checkHealthy();
        if (eof) {
            return null;
        }
        if (!submitted.equals(submittedSource)) {
            // No HISTORY_FILE is ever configured. :reset clears the navigation
            // view too; :load contributes source, not its command/path spelling.
            history.purge();
            for (String source : submittedSource) {
                history.add(source.endsWith("\n") ? source.substring(0, source.length() - 1) : source);
            }
            history.moveToEnd();
            submitted = List.copyOf(submittedSource);
        }
        while (true) {
            try {
                terminal.resume();
                String source = reader.readLine(PROMPT, null, SOURCE_HISTORY_ONLY, null);
                return source + "\n";
            } catch (UserInterruptException interrupted) {
                // Ctrl-C abandons only the input being edited. No evaluation is active.
                Thread.interrupted();
            } catch (EndOfFileException end) {
                eof = true;
                String partial = end.getPartialLine();
                // Disconnect/EOF must not execute a recalled or pasted buffer
                // that was never accepted. Only unfinished input needs the
                // shared EOF diagnostic; Enter is the sole submission action.
                return partial != null && LexicalCompleteness.inspect(partial).incomplete()
                        ? partial : null;
            } catch (IOError failure) {
                if (failure.getCause() instanceof IOException io) {
                    throw io;
                }
                throw failure;
            } finally {
                // readLine restores attributes, signal handlers, keypad and paste
                // mode before evaluation. Suspend providers with background input
                // pumps; ProgramInput resumes only while generated readLine owns
                // the terminal reader.
                if (terminal.canPauseResume()) {
                    terminal.pause();
                }
            }
        }
    }

    /** Bridges JLine's buffered terminal reader to the runtime's byte decoder. */
    private static final class ProgramInput extends InputStream {
        private final Terminal terminal;
        private final Charset charset;
        private byte[] encoded = new byte[0];
        private int encodedOffset;
        private int pendingCharacter = -1;
        private IOException inputFailure;

        private ProgramInput(Terminal terminal) {
            this.terminal = terminal;
            charset = terminal.inputEncoding();
        }

        @Override
        public synchronized int read() throws IOException {
            checkHealthy();
            try {
                while (true) {
                    if (encodedOffset < encoded.length) {
                        int value = encoded[encodedOffset++] & 0xff;
                        if (value == '\n') pause();
                        return value;
                    }
                    encoded = new byte[0];
                    encodedOffset = 0;
                    String text = readCharacter();
                    if (text == null) return -1;
                    encoded = encode(text);
                }
            } catch (IOException | RuntimeException failure) {
                pause();
                inputFailure = new IOException("program input failed; its remaining line is not source", failure);
                throw inputFailure;
            }
        }

        private synchronized void checkHealthy() throws IOException {
            if (inputFailure != null) throw inputFailure;
        }

        @Override
        public void close() {
            // The terminal and its underlying process streams are caller-owned.
        }

        private String readCharacter() throws IOException {
            try {
                if (terminal.canPauseResume()) {
                    terminal.resume();
                }
                int character = pendingCharacter >= 0
                        ? takePendingCharacter() : readTerminalCharacter();
                if (character == org.jline.utils.NonBlockingReader.EOF) {
                    pause();
                    return null;
                }
                if (Character.isHighSurrogate((char) character)) {
                    int next = readTerminalCharacter();
                    if (next == org.jline.utils.NonBlockingReader.EOF) {
                        throw malformedCharacter();
                    }
                    if (!Character.isLowSurrogate((char) next)) {
                        pendingCharacter = next;
                        throw malformedCharacter();
                    }
                    return new String(new char[] {(char) character, (char) next});
                }
                if (Character.isLowSurrogate((char) character)) {
                    throw malformedCharacter();
                }
                return String.valueOf((char) character);
            } catch (IOError failure) {
                pause();
                if (failure.getCause() instanceof IOException io) {
                    throw io;
                }
                throw failure;
            } catch (IOException | RuntimeException failure) {
                pause();
                throw failure;
            }
        }

        private int takePendingCharacter() {
            int result = pendingCharacter;
            pendingCharacter = -1;
            return result;
        }

        private int readTerminalCharacter() throws IOException {
            int character = terminal.reader().read();
            while (character == org.jline.utils.NonBlockingReader.READ_EXPIRED) {
                character = terminal.reader().read();
            }
            return character;
        }

        private byte[] encode(String text) throws IOException {
            CharsetEncoder encoder = charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                ByteBuffer bytes = encoder.encode(CharBuffer.wrap(text));
                byte[] result = new byte[bytes.remaining()];
                bytes.get(result);
                return result;
            } catch (CharacterCodingException failure) {
                throw new IOException("terminal input contains malformed text", failure);
            }
        }

        private void pause() {
            if (terminal.canPauseResume()) {
                terminal.pause();
            }
        }

        private static IOException malformedCharacter() {
            return new IOException("terminal input contains malformed text");
        }
    }
}
