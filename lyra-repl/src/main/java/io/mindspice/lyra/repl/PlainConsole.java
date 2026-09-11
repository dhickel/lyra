package io.mindspice.lyra.repl;

import io.mindspice.lyra.runtime.RuntimeIoEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Dependency-free, non-TTY console loop. It owns no streams or session and
 * emits no prompt or terminal decoration, which makes it suitable for pipes
 * and tests as well as the CLI.
 */
public final class PlainConsole {
    public static final String HELP_TEXT = "Commands:\n"
            + "  :help                 Show this help.\n"
            + "  :bindings             List committed binding metadata.\n"
            + "  :type SOURCE          Show SOURCE's type without executing or recording it.\n"
            + "  :load PATH            Submit UTF-8 source from PATH on the execution host.\n"
            + "  :reload MODULE        Rebuild one retained REPL-owned module.\n"
            + "  :reset                Clear scratch bindings and in-memory history.\n"
            + "  :history              List in-memory submitted source; never replays.\n"
            + "  :quit                 Exit the console.\n";

    private final ConsoleSession session;
    private final RuntimeIoEnvironment ioEnvironment;
    private final OutputStream output;
    private final OutputStream error;
    private final Path historyFile;
    private final ArrayList<String> history = new ArrayList<>();
    private boolean evaluationFailed;
    private boolean commandFailed;
    private boolean streamFailed;

    public PlainConsole(LyraSession session, InputStream input,
                        OutputStream output, OutputStream error) {
        this(new LocalConsoleSession(session),
                new RuntimeIoEnvironment(input, output, error, StandardCharsets.UTF_8), null);
    }

    public PlainConsole(ConsoleSession session, InputStream input,
                        OutputStream output, OutputStream error) {
        this(session, new RuntimeIoEnvironment(input, output, error, StandardCharsets.UTF_8), null);
    }

    /** Uses one explicit I/O environment for source entry and generated program I/O. */
    public PlainConsole(LyraSession session, RuntimeIoEnvironment ioEnvironment) {
        this(new LocalConsoleSession(session), ioEnvironment, null);
    }

    /** Uses one explicit I/O environment for source entry and generated program I/O. */
    public PlainConsole(ConsoleSession session, RuntimeIoEnvironment ioEnvironment) {
        this(session, ioEnvironment, null);
    }

    /**
     * Creates a console with an optional caller-selected source-history file.
     * The file is never used unless explicitly supplied and is not replayed.
     */
    public PlainConsole(LyraSession session, InputStream input,
                        OutputStream output, OutputStream error, Path historyFile) {
        this(new LocalConsoleSession(session),
                new RuntimeIoEnvironment(input, output, error, StandardCharsets.UTF_8), historyFile);
    }

    public PlainConsole(ConsoleSession session, InputStream input,
                        OutputStream output, OutputStream error, Path historyFile) {
        this(session, new RuntimeIoEnvironment(input, output, error, StandardCharsets.UTF_8), historyFile);
    }

    /** Uses one explicit I/O environment and an optional source-history file. */
    public PlainConsole(LyraSession session, RuntimeIoEnvironment ioEnvironment,
                        Path historyFile) {
        this(new LocalConsoleSession(session), ioEnvironment, historyFile);
    }

    /** Uses one explicit I/O environment and an optional source-history file. */
    public PlainConsole(ConsoleSession session, RuntimeIoEnvironment ioEnvironment,
                        Path historyFile) {
        this.session = Objects.requireNonNull(session, "session");
        this.ioEnvironment = Objects.requireNonNull(ioEnvironment, "ioEnvironment");
        this.output = ioEnvironment.output();
        this.error = ioEnvironment.error();
        this.historyFile = historyFile == null ? null : ConsoleHistoryFile.prepare(historyFile);
        if (this.historyFile != null) {
            try {
                history.addAll(ConsoleHistoryFile.read(this.historyFile));
            } catch (IOException failure) {
                throw new IllegalArgumentException(
                        "cannot read history file: " + this.historyFile, failure);
            }
        }
    }

    /** Runs until :quit or EOF and returns the plain-console exit status. */
    public int run() {
        return run(new ConsoleInputCoordinator(ioEnvironment));
    }

    /**
     * Runs the same commands and evaluations with a caller-owned input adapter.
     * No input, output, terminal, or session resource is closed here.
     */
    public int run(SourceReader reader) {
        return run(reader, true);
    }

    /**
     * Runs an interactive adapter. Recoverable source-evaluation failures are
     * reported but do not turn an otherwise orderly interactive quit into a
     * failure status; input, command, and infrastructure failures still do.
     */
    public int runInteractive(SourceReader reader) {
        return run(reader, false);
    }

    private int run(SourceReader reader, boolean evaluationFailuresAffectStatus) {
        Objects.requireNonNull(reader, "reader");
        try {
            String source;
            while (true) {
                ioEnvironment.checkInputAvailable();
                source = reader.readSource(List.copyOf(history));
                if (source == null) break;
                if (source.isBlank()) {
                    continue;
                }
                if (isCommandLine(source)) {
                    // Source readers may return a line terminator for an
                    // accepted editor line. It is transport framing, not
                    // part of the command argument; remove only that one
                    // terminator so :type receives its source verbatim.
                    if (handleCommand(commandLine(source), evaluationFailuresAffectStatus)) {
                        break;
                    }
                } else if (LexicalCompleteness.inspect(source).incomplete()) {
                    diagnostic("LYR-REPL-EOF", incompleteSourceMessage(source));
                    evaluationFailed = true;
                } else {
                    submit(source, "repl-input", evaluationFailuresAffectStatus);
                }
            }
        } catch (IOException | io.mindspice.lyra.runtime.LyraIoException failure) {
            diagnostic("LYR-REPL-IO", "cannot read console input: " + message(failure));
            streamFailed = true;
        } finally {
            saveHistory();
        }
        return exitStatus();
    }

    /** Console input only: complete source or a top-level command, null at EOF. */
    @FunctionalInterface
    public interface SourceReader {
        /**
         * History is an immutable view of submitted source, not commands or program input.
         * Return an unfinished unit only at EOF so the shared loop can diagnose it.
         */
        String readSource(List<String> history) throws IOException;
    }

    /** A single leading colon is reserved for console commands; {@code ::} is Lyra source. */
    static boolean isCommandLine(String source) {
        String line = source.stripLeading();
        return line.startsWith(":") && !line.startsWith("::");
    }

    private static String commandLine(String source) {
        String line = source.stripLeading();
        if (line.endsWith("\r\n")) {
            return line.substring(0, line.length() - 2);
        }
        if (line.endsWith("\n") || line.endsWith("\r")) {
            return line.substring(0, line.length() - 1);
        }
        return line;
    }

    private boolean handleCommand(String line, boolean evaluationFailuresAffectStatus) {
        final ConsoleCommand command;
        try {
            command = ConsoleCommandParser.parse(line);
        } catch (ConsoleCommandParser.ParseFailure failure) {
            diagnostic("LYR-REPL-USAGE", failure.getMessage());
            commandFailed = true;
            return false;
        }

        try {
            return switch (command.kind()) {
                case HELP -> {
                    write(output, HELP_TEXT);
                    yield false;
                }
                case BINDINGS -> {
                    printBindings();
                    yield false;
                }
                case TYPE -> {
                    printType(command.arguments().getFirst());
                    yield false;
                }
                case LOAD -> {
                    load(command.arguments().getFirst(), evaluationFailuresAffectStatus);
                    yield false;
                }
                case RELOAD -> {
                    reload(command.arguments().getFirst(), evaluationFailuresAffectStatus);
                    yield false;
                }
                case RESET -> {
                    reset();
                    yield false;
                }
                case HISTORY -> {
                    printHistory();
                    yield false;
                }
                case QUIT -> true;
            };
        } catch (RuntimeException failure) {
            diagnostic("LYR-REPL-INFRA", message(failure));
            commandFailed = true;
            return false;
        }
    }

    /**
     * Routes the load to the selected execution host: the local session for
     * a local console, the attached server for a remote one. The client
     * never reads attached load paths. The loaded source text contributes to
     * visible history only when the execution host returned it.
     */
    private void load(String path, boolean evaluationFailuresAffectStatus) {
        ConsoleSession.Loaded loaded = session.load(path);
        loaded.sourceText().ifPresent(this::recordHistory);
        reportEvaluation(loaded.evaluation(), evaluationFailuresAffectStatus);
    }

    /** Routes the rebuild to the execution host and reports its terminal outcome. */
    private void reload(String moduleOrAlias, boolean evaluationFailuresAffectStatus) {
        reportEvaluation(session.reload(moduleOrAlias), evaluationFailuresAffectStatus);
    }

    private void submit(String source, String label, boolean evaluationFailuresAffectStatus) {
        recordHistory(source);
        final ConsoleSession.Evaluation result;
        try {
            result = session.evaluate(EvaluationSource.of(label, source));
        } catch (RuntimeException failure) {
            diagnostic("LYR-REPL-INFRA", message(failure));
            commandFailed = true;
            return;
        }
        reportEvaluation(result, evaluationFailuresAffectStatus);
    }

    private void reportEvaluation(ConsoleSession.Evaluation result,
                                  boolean evaluationFailuresAffectStatus) {
        for (ConsoleSession.DiagnosticInfo diagnostic : result.diagnostics()) {
            diagnostic(diagnostic.render());
        }
        switch (result.status()) {
            case SUCCESS -> result.value().ifPresent(value -> write(output, value.renderLine()));
            case COMPILATION_FAILURE, RUNTIME_FAILURE, CANCELLED,
                    UNAVAILABLE, REVISION_CONFLICT, REJECTED, EXPIRED -> {
                if (evaluationFailuresAffectStatus) {
                    evaluationFailed = true;
                }
            }
            case BUSY, CLOSED -> {
                diagnostic("LYR-REPL-SESSION", "session cannot accept this evaluation: "
                        + result.status());
                commandFailed = true;
            }
        }
        result.detail().ifPresent(detail -> {
            String code = result.status() == ConsoleSession.EvaluationStatus.RUNTIME_FAILURE
                    ? "LYR-REPL-RUNTIME" : "LYR-REPL-REMOTE";
            diagnostic(code, "status=" + result.status() + ": " + detail);
        });
    }

    private void printBindings() {
        ConsoleSession.Query result = session.query(ConsoleSession.QueryRequest.bindings());
        if (result.status() != ConsoleSession.QueryStatus.OK) {
            diagnostic("LYR-REPL-BINDINGS-" + result.status(), queryDetail(result,
                    "binding query is unavailable"));
            commandFailed = true;
            return;
        }
        result.bindings().stream()
                .sorted(Comparator.comparing(ConsoleSession.Binding::name))
                .forEach(binding -> {
                    String mutability = binding.mutable() ? " @mut" : "";
                    write(output, binding.name() + " :" + binding.canonicalType()
                            + mutability + "\n");
                });
    }

    private void printType(String source) {
        ConsoleSession.Query result = session.query(ConsoleSession.QueryRequest.type(
                EvaluationSource.of("console:type", source)));
        if (result.status() != ConsoleSession.QueryStatus.OK) {
            String code = switch (result.status()) {
                case BUSY -> "LYR-REPL-TYPE-BUSY";
                case CLOSED -> "LYR-REPL-TYPE-CLOSED";
                default -> "LYR-REPL-TYPE-UNSUPPORTED";
            };
            diagnostic(code, "status=" + result.status() + ": "
                    + queryDetail(result, "type query unavailable; source was not executed"));
            commandFailed = true;
            return;
        }
        if (result.inferredType().isPresent()) {
            write(output, result.inferredType().orElseThrow() + "\n");
        } else {
            diagnostic("LYR-REPL-TYPE-UNSUPPORTED",
                    "type query returned no inferred type; source was not executed");
            commandFailed = true;
        }
    }

    private void reset() {
        ConsoleSession.Control result = session.reset();
        if (result.status() != ConsoleSession.ControlStatus.OK) {
            diagnostic("LYR-REPL-RESET-" + result.status(), controlDetail(result,
                    "remote reset was unavailable"));
            commandFailed = true;
            return;
        }
        history.clear();
    }

    private static String queryDetail(ConsoleSession.Query result, String fallback) {
        return result.detail().filter(value -> !value.isBlank()).orElse(fallback);
    }

    private static String controlDetail(ConsoleSession.Control result, String fallback) {
        return result.detail().filter(value -> !value.isBlank()).orElse(fallback);
    }

    private void printHistory() {
        for (int index = 0; index < history.size(); index++) {
            write(output, (index + 1) + ": " + history.get(index));
            if (!history.get(index).endsWith("\n")) {
                write(output, "\n");
            }
        }
    }

    private void recordHistory(String source) {
        history.add(source);
        while (history.size() > ConsoleHistoryFile.MAX_ENTRIES) {
            history.removeFirst();
        }
    }

    private void saveHistory() {
        if (historyFile == null) {
            return;
        }
        try {
            ConsoleHistoryFile.write(historyFile, history);
        } catch (IOException failure) {
            diagnostic("LYR-REPL-IO", "cannot write history file: " + message(failure));
            streamFailed = true;
        }
    }

    private static String incompleteSourceMessage(String source) {
        LexicalCompleteness.State state = LexicalCompleteness.inspect(source);
        if (state.openDelimiters().isEmpty()) {
            return "end of input while a source unit was incomplete";
        }
        String delimiters = state.openDelimiters().stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(" "));
        return "end of input while a source unit was incomplete; missing closing delimiter for: "
                + delimiters;
    }

    private int exitStatus() {
        if (streamFailed || commandFailed) {
            return 2;
        }
        return evaluationFailed ? 1 : 0;
    }

    private void diagnostic(String message) {
        write(error, message + (message.endsWith("\n") ? "" : "\n"));
    }

    private void diagnostic(String code, String message) {
        diagnostic(code + ": " + message);
    }

    private void write(OutputStream stream, String text) {
        try {
            stream.write(text.getBytes(ioEnvironment.charset()));
            stream.flush();
        } catch (IOException failure) {
            streamFailed = true;
        }
    }

    private static String message(Throwable failure) {
        String value = failure.getMessage();
        return value == null || value.isBlank() ? failure.getClass().getSimpleName() : value;
    }

    /**
     * Strict, source-only history persistence used only after an explicit
     * path option. Optional-console paths follow normal filesystem
     * semantics: the target must be a regular file with strict UTF-8
     * content, but no permission or symbolic-link security policy applies.
     */
    private static final class ConsoleHistoryFile {
        private static final int MAX_ENTRIES = 256;
        private static final int MAX_BYTES = 1024 * 1024;

        private ConsoleHistoryFile() {
        }

        private static Path prepare(Path value) {
            Objects.requireNonNull(value, "historyFile");
            Path path = value.toAbsolutePath().normalize();
            try {
                Path parent = path.getParent();
                if (parent == null || !Files.isDirectory(parent)) {
                    throw new IOException("history file parent must be an existing directory: " + path);
                }
                if (Files.exists(path) && !Files.isRegularFile(path)) {
                    throw new IOException(
                            "history path must be a regular file: " + path);
                }
                return path;
            } catch (IOException failure) {
                throw new IllegalArgumentException(
                        "invalid history file: " + path + ": " + failure.getMessage(), failure);
            }
        }

        private static List<String> read(Path path) throws IOException {
            if (!Files.exists(path)) {
                return List.of();
            }
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length > MAX_BYTES) {
                throw new IOException("history file exceeds the 1 MiB limit: " + path);
            }
            final String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
            } catch (CharacterCodingException failure) {
                throw new IOException("history file is not valid UTF-8: " + path, failure);
            }
            if (text.isEmpty()) {
                return List.of();
            }
            String[] lines = text.split("\\n", -1);
            int first = Math.max(0, lines.length - 1 - MAX_ENTRIES);
            ArrayList<String> result = new ArrayList<>();
            for (int index = first; index < lines.length; index++) {
                String line = lines[index];
                if (index == lines.length - 1 && line.isEmpty()) {
                    continue;
                }
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                String decoded = decode(line);
                if (!PlainConsole.isCommandLine(decoded)) {
                    result.add(decoded);
                }
            }
            return List.copyOf(result);
        }

        private static void write(Path path, List<String> entries) throws IOException {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(entries, "entries");
            if (entries.size() > MAX_ENTRIES) {
                throw new IOException("too many history entries");
            }
            ArrayList<byte[]> encoded = new ArrayList<>(entries.size());
            int total = 0;
            for (String entry : entries) {
                byte[] bytes = (encode(entry) + "\n").getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_BYTES - total) {
                    total = MAX_BYTES + 1;
                } else {
                    total += bytes.length;
                }
                if (total > MAX_BYTES) {
                    throw new IOException("history entries exceed the 1 MiB limit: " + path);
                }
                encoded.add(bytes);
            }
            if (Files.exists(path) && !Files.isRegularFile(path)) {
                throw new IOException("history path must be a regular file: " + path);
            }
            try (OutputStream target = Files.newOutputStream(
                    path, StandardOpenOption.WRITE, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                for (byte[] bytes : encoded) {
                    target.write(bytes);
                }
            }
        }

        private static String encode(String value) {
            StringBuilder result = new StringBuilder(value.length());
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                switch (character) {
                    case '\\' -> result.append("\\\\");
                    case '\n' -> result.append("\\n");
                    case '\r' -> result.append("\\r");
                    case '\t' -> result.append("\\t");
                    default -> result.append(character);
                }
            }
            return result.toString();
        }

        private static String decode(String value) {
            StringBuilder result = new StringBuilder(value.length());
            boolean escaped = false;
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (!escaped) {
                    if (character == '\\') {
                        escaped = true;
                    } else {
                        result.append(character);
                    }
                    continue;
                }
                result.append(switch (character) {
                    case '\\' -> '\\';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> character;
                });
                if (character != '\\' && character != 'n'
                        && character != 'r' && character != 't') {
                    result.insert(result.length() - 1, '\\');
                }
                escaped = false;
            }
            if (escaped) {
                result.append('\\');
            }
            return result.toString();
        }
    }
}
