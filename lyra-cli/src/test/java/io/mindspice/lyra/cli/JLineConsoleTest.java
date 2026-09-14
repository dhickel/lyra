package io.mindspice.lyra.cli;

import io.mindspice.lyra.compiler.api.SourceResolver;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.ResolvedSource;
import io.mindspice.lyra.repl.ConsoleSession;
import io.mindspice.lyra.repl.EvaluationId;
import io.mindspice.lyra.repl.EvaluationSource;
import io.mindspice.lyra.repl.LyraSession;
import io.mindspice.lyra.repl.ManagedConsoleSession;
import io.mindspice.lyra.repl.PlainConsole;
import io.mindspice.lyra.repl.SessionOptions;
import io.mindspice.lyra.repl.SessionRevision;
import io.mindspice.lyra.runtime.RuntimeIoEnvironment;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.terminal.spi.TerminalProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class JLineConsoleTest {
    @TempDir
    Path temp;

    @Test
    void pinnedProviderAndBothDiscoveryResourcesResolve() throws Exception {
        assertEquals("ffm", TerminalProvider.load("ffm").name());
        ClassLoader loader = TerminalProvider.class.getClassLoader();
        for (String artifact : List.of("jline-reader", "jline-terminal", "jline-terminal-ffm", "jline-native")) {
            try (InputStream stream = loader.getResourceAsStream("META-INF/maven/org.jline/"
                    + artifact + "/pom.properties")) {
                assertNotNull(stream, artifact);
                var properties = new java.util.Properties();
                properties.load(stream);
                assertEquals("4.0.0", properties.getProperty("version"), artifact);
            }
        }
        assertResourceContains("META-INF/jline/providers/ffm", "org.jline.terminal.impl.ffm.FfmTerminalProvider");
        assertResourceContains("META-INF/services/org.jline.terminal.spi.TerminalProvider",
                "org.jline.terminal.impl.ffm.FfmTerminalProvider");
        assertNotNull(loader.getResource("org/jline/utils/xterm.caps"));
    }

    @Test
    void enterContinuesUsingAuthoritativeCommentsDelimitersAndIndentation() throws Exception {
        try (Fixture fixture = new Fixture("let @pub value :I32 = (/* outer /* inner */\n"
                + "still */ + 1\n2)\n\\quit\n", "emacs")) {
            String source = fixture.rich.readSource(List.of());
            assertEquals("let @pub value :I32 = (/* outer /* inner */\n"
                    + "  still */ + 1\n  2)\n", source);
            assertEquals(0, fixture.runSource(source));
            assertTrue(fixture.screen().contains(JLineConsole.CONTINUATION_PROMPT));
            assertEquals("\\quit\n", fixture.rich.readSource(List.of(source)));
        }
    }

    @Test
    void bracketedPasteIsOneSourceUnitAndRequiresExplicitEnter() throws Exception {
        String pasted = "let @pub first :I32 = 1\nlet @pub second :I32 = 2\n";
        try (Fixture fixture = new Fixture("\033[200~" + pasted + "\033[201~\n\\bindings\n\\history\n\\quit\n", "emacs")) {
            assertEquals(0, fixture.run());
            String output = fixture.output.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("first :I32\nsecond :I32\n"), output);
            assertTrue(output.contains("1: " + pasted), output);
            assertFalse(output.contains("2: "), output);
        }
        // EOF without Enter cannot execute paste contents.
        try (Fixture fixture = new Fixture("\033[200~" + pasted + "\033[201~", "emacs", false)) {
            assertNull(fixture.rich.readSource(List.of()));
            assertNull(fixture.rich.readSource(List.of()));
        }
        try (Fixture fixture = new Fixture("\033[200~(\r\n+ 1 2)\033[201~\n", "emacs")) {
            assertEquals("(\n+ 1 2)\n", fixture.rich.readSource(List.of()));
        }
    }

    @Test
    void pasteCannotExecuteEmbeddedCommandsAndPreservesQuotesAndEscapes() throws Exception {
        try (Fixture fixture = new Fixture("\033[200~(\n\\help\n)\033[201~\n\\quit\n", "emacs")) {
            assertEquals(0, fixture.run());
            assertFalse(fixture.output.toString(StandardCharsets.UTF_8).contains(PlainConsole.HELP_TEXT));
            assertTrue(fixture.error.toString(StandardCharsets.UTF_8).contains("LYC-"));
        }
        String source = "let text :String = \"a\\n\\\\!\"";
        try (Fixture fixture = new Fixture(source + "\n", "emacs")) {
            assertEquals(source + "\n", fixture.rich.readSource(List.of()));
        }
    }

    @Test
    void malformedDelimitersAndLiteralNewlinesSubmitRatherThanSwallowLaterCommands() throws Exception {
        for (String source : List.of("{]", "\"unfinished", "'unfinished", "\"trailing\\")) {
            try (Fixture fixture = new Fixture(source + "\n\\quit\n", "emacs")) {
                assertEquals(source + "\n", fixture.rich.readSource(List.of()));
                assertEquals("\\quit\n", fixture.rich.readSource(List.of()));
            }
        }
    }

    @Test
    void emacsAndViKeymapsPerformTheirOwnEditingWithoutStructuralRewrites() throws Exception {
        try (Fixture emacs = new Fixture("abc\001X\005\n", "emacs")) {
            assertEquals("Xabc\n", emacs.rich.readSource(List.of()));
        }
        try (Fixture vi = new Fixture("abc\0330xA!\n", "vi")) {
            assertEquals("bc!\n", vi.rich.readSource(List.of()));
            assertEquals("\\quit\n", vi.rich.readSource(List.of()));
        }
        try (Fixture fixture = new Fixture("(1  )\n", "emacs")) {
            assertEquals("(1  )\n", fixture.rich.readSource(List.of()));
        }
        try (Fixture fixture = new Fixture("\t1\n", "emacs")) {
            assertEquals("  1\n", fixture.rich.readSource(List.of()));
        }
        try (Fixture fixture = new Fixture("(1)\030\002x\005\n", "emacs")) {
            assertEquals("x(1)\n", fixture.rich.readSource(List.of()));
        }
    }

    @Test
    void unqualifiedDirectCallsAreNotTreatedAsCommands() throws Exception {
        try (Fixture fixture = new Fixture(
                "let add :Fn<I32,I32;I32> = (=> |left right| (+ left right))\n"
                        + "::add[2 3]\n", "emacs")) {
            int status = fixture.run();
            assertEquals(0, status, fixture.error.toString(StandardCharsets.UTF_8));
            assertTrue(fixture.output.toString(StandardCharsets.UTF_8).contains("I32 5\n"),
                    fixture.output.toString(StandardCharsets.UTF_8));
            assertFalse(fixture.error.toString(StandardCharsets.UTF_8).contains("unknown command: ::add"));
        }
    }

    @Test
    void completionIsLimitedToCommandsAndTypeNames() throws Exception {
        try (Fixture fixture = new Fixture("\\hel\t\n", "emacs")) {
            assertEquals("\\help \n", fixture.rich.readSource(List.of()));
        }
        try (Fixture fixture = new Fixture("let value :I3\t\n", "emacs")) {
            assertEquals("let value :I32 \n", fixture.rich.readSource(List.of()));
        }
        try (Fixture fixture = new Fixture("// I3\t\n", "emacs")) {
            assertEquals("// I3\n", fixture.rich.readSource(List.of()));
        }
    }

    @Test
    void historyNavigationAndSearchOnlyRecallSubmittedSource() throws Exception {
        List<String> history = List.of("let alpha :I32 = 1\n", "let beta :I32 = 2\n");
        try (Fixture fixture = new Fixture("\033[A\n\022alpha\n\\quit\n", "emacs")) {
            assertEquals("let beta :I32 = 2\n", fixture.rich.readSource(history), fixture::screen);
            assertEquals("let alpha :I32 = 1\n", fixture.rich.readSource(history));
            assertEquals("\\quit\n", fixture.rich.readSource(history));
        }
        try (Fixture fixture = new Fixture("!!\n", "emacs")) {
            assertEquals("!!\n", fixture.rich.readSource(history));
        }
        try (Fixture fixture = new Fixture("\033[A", "emacs", false)) {
            assertNull(fixture.rich.readSource(history), "EOF must not replay recalled source");
        }
    }

    @Test
    void historyExcludesCommandsAbandonedInputAndClearsOnResetWithoutDiskWrites() throws Exception {
        Path source = temp.resolve("a source.lyra");
        Files.writeString(source, "let @pub loaded :I32 = 5\n");
        String input = "credential-must-not-be-stored\007\\help\n\\type 99\n\\reload\n"
                + "\\load \"" + source + "\"\n\\history\n\\reset\n\\history\n\033[A\n\\quit\n";
        try (Fixture fixture = new Fixture(input, "emacs")) {
            assertEquals(2, fixture.run());
            String output = fixture.output.toString(StandardCharsets.UTF_8);
            assertTrue(output.endsWith("1: let @pub loaded :I32 = 5\n"), output);
            assertFalse(output.contains("credential-must-not-be-stored"));
            assertFalse(output.contains("\\load \""));
            assertTrue(output.contains("I64"), output);
            String error = fixture.error.toString(StandardCharsets.UTF_8);
            assertFalse(error.contains("LYR-REPL-TYPE-UNSUPPORTED"), error);
            assertTrue(error.contains("LYR-REPL-USAGE"), error);
            assertTrue(error.contains("\\reload expects 1 argument"), error);
            assertFalse(error.contains("LYC-"), error);
        }
        try (var files = Files.list(temp)) {
            assertEquals(List.of(source), files.toList());
        }
    }

    @Test
    void ambientJLinePropertiesCannotOpenHistoryOrInputrcFiles() throws Exception {
        Path history = temp.resolve("should-not-exist");
        String property = "org.jline.reader.history-file";
        String previous = System.getProperty(property);
        System.setProperty(property, history.toString());
        try (Fixture fixture = new Fixture("let x :I32 = 1\n\\quit\n", "emacs")) {
            assertEquals(0, fixture.run());
            assertFalse(Files.exists(history));
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void inputFailureRestoresTerminalAndDoesNotCloseBorrowedStreams() throws Exception {
        ByteArrayOutputStream display = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        AtomicInteger closed = new AtomicInteger();
        InputStream input = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("test input failure");
            }

            @Override
            public void close() {
                closed.incrementAndGet();
            }
        };
        try (Terminal terminal = new DumbTerminal("failure", "xterm", input, display, StandardCharsets.UTF_8)) {
            terminal.setSize(new Size(80, 24));
            Attributes attributes = terminal.getAttributes();
            attributes.setLocalFlag(Attributes.LocalFlag.ICANON, true);
            terminal.setAttributes(attributes);
            LyraSession session = LyraSession.open();
            try {
                assertEquals(2, new PlainConsole(session, input, display, error)
                        .run(new JLineConsole(terminal, "emacs")));
                assertTrue(error.toString(StandardCharsets.UTF_8).contains("LYR-REPL-IO"));
                assertEquals(attributes.toString(), terminal.getAttributes().toString());
                assertEquals(0, closed.get());
                assertTrue(display.toString(StandardCharsets.UTF_8).contains("\033[?2004l"));
            } finally {
                session.close();
            }
        }
        assertEquals(1, closed.get());
    }

    @Test
    void incompleteEofIsDiagnosedAndInterruptDiscardsOnlyEditing() throws Exception {
        for (String input : List.of("(+ 1\004", "/* outer /* inner */\004")) {
            try (Fixture fixture = new Fixture(input, "emacs", false)) {
                assertEquals(1, fixture.run());
                assertTrue(fixture.error.toString(StandardCharsets.UTF_8).contains("LYR-REPL-EOF"));
            }
        }
        try (Fixture fixture = new Fixture("(discard\007let @pub kept :I32 = 1\n\\bindings\n\\quit\n", "emacs")) {
            assertEquals(0, fixture.run());
            assertEquals("kept :I32\n", fixture.output.toString(StandardCharsets.UTF_8));
        }
        try (Fixture fixture = new Fixture("\004", "emacs")) {
            assertEquals(0, fixture.run());
        }
    }

    @Test
    void resizeRedrawRestoresAttributesHandlersAndPasteModeOnEveryRead() throws Exception {
        try (Fixture fixture = new Fixture("let @pub longName :I32 = 1\n\\quit\n", "emacs")) {
            AtomicInteger signals = new AtomicInteger();
            Terminal.SignalHandler prior = signal -> signals.incrementAndGet();
            fixture.terminal.handle(Terminal.Signal.WINCH, prior);
            Attributes before = fixture.terminal.getAttributes();
            fixture.input.onRead = () -> {
                fixture.terminal.setSize(new Size(32, 12));
                fixture.terminal.raise(Terminal.Signal.WINCH);
            };
            assertEquals(0, fixture.run());
            assertEquals(before.toString(), fixture.terminal.getAttributes().toString());
            assertSame(prior, fixture.terminal.handle(Terminal.Signal.WINCH, prior));
            assertEquals(0, signals.get(), "JLine should handle resize while editing");
            String screen = fixture.screen();
            assertTrue(screen.contains("\033[?2004h"), screen);
            assertTrue(screen.contains("\033[?2004l"), screen);
            assertEquals(occurrences(screen, "\033[?2004h"), occurrences(screen, "\033[?2004l"));
        }
    }

    @Test
    void highlighterUsesCompilerTokensAndMatchingIgnoresCommentsLiteralsAndUnicodeOffsets() throws Exception {
        try (Fixture fixture = new Fixture("", "emacs")) {
            LineReader reader = LineReaderBuilder.builder().terminal(fixture.terminal).build();
            LyraHighlighter highlighter = new LyraHighlighter();
            String source = "(let x :String = \"😀)\" (match 1I32 1I32 when #T -> 2I32 _ -> 3I32) /* ] /* } */ ) */)";
            reader.getBuffer().write(source);
            var highlighted = highlighter.highlight(reader, source);
            assertEquals(source, highlighted.toString());
            assertNotEquals(highlighted.styleAt(1), highlighted.styleAt(5));
            assertNotEquals(highlighted.styleAt(source.indexOf('"')), highlighted.styleAt(5));
            assertEquals(highlighted.styleAt(source.indexOf("let")),
                    highlighted.styleAt(source.indexOf("match")));
            assertEquals(highlighted.styleAt(source.indexOf("let")),
                    highlighted.styleAt(source.indexOf("when")));
            assertEquals(0, highlighter.matchingDelimiter(source, source.length()));
            assertEquals(source.length() - 1, highlighter.matchingDelimiter(source, 0));
            assertEquals(-1, highlighter.matchingDelimiter(source, source.indexOf(']')));
            assertEquals(-1, highlighter.matchingDelimiter("{]}", 0));
            assertEquals(-1, highlighter.matchingDelimiter("\"unfinished", 0));
            String escape = "\033[31m";
            assertFalse(highlighter.highlight(reader, escape).toString().contains("\033"));
        }
    }

    @Test
    void failedProgramInputCannotBeResumedAsEditableSource() throws Exception {
        InputStream unavailable = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("input unavailable"); }
        };
        try (Terminal terminal = new DumbTerminal("failed-input", "xterm", unavailable,
                new ByteArrayOutputStream(), StandardCharsets.UTF_8)) {
            JLineConsole console = new JLineConsole(terminal, "emacs");
            assertThrows(IOException.class, () -> console.programInput().read());
            assertThrows(IOException.class, () -> console.readSource(List.of("accepted source\n")));
        }
    }

    @Test
    void fileAndModuleCompletionListsExecutionHostFilesWithoutInitializingThem()
            throws Exception {
        Path newFile = temp.resolve("newmod.lyra");
        Files.writeString(newFile, "let @pub inside :I32 = 7\n");
        RecordingConsole target = new RecordingConsole(
                List.of(newFile.getParent()));
        String input = "\\load newm\t\n\\reload newm\t\n\\history\n\\quit\n";
        try (Fixture fixture = new Fixture(input, "emacs")) {
            fixture.rich.completeWith(target);
            assertEquals(0, fixture.run(target), fixture.error.toString(StandardCharsets.UTF_8));
            // Completion only listed files/modules; nothing was evaluated,
            // compiled, pinned or initialized before Enter.
            assertEquals(List.of("newm", "newm"), target.modulePrefixes);
            assertEquals(0, target.evaluations);
            assertEquals(List.of("newmod.lyra"), target.loadedPaths);
            assertEquals(List.of("newmod"), target.reloadTargets);
            String output = fixture.output.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("1: let @pub inside :I32 = 7"), output);
            assertFalse(output.contains("\\load"), output);
            assertFalse(output.contains("\\reload"), output);
        }
    }

    @Test
    void nestedModuleCompletionReplacesTheWholeLogicalTarget() throws Exception {
        RecordingConsole target = new RecordingConsole(List.of());
        try (Fixture fixture = new Fixture("\\reload game->ma\t\n\\quit\n", "emacs")) {
            fixture.rich.completeWith(target);
            assertEquals(0, fixture.run(target), fixture.error.toString(StandardCharsets.UTF_8));
            assertEquals(List.of("game->ma"), target.modulePrefixes);
            assertEquals(List.of("game->math"), target.reloadTargets);
        }
    }

    @Test
    void typeCommandPassesVerbatimSourceWithoutEditorLineFraming() throws Exception {
        RecordingConsole target = new RecordingConsole(List.of());
        try (Fixture fixture = new Fixture("\\type   1  +  2  \n\\quit\n", "emacs")) {
            assertEquals(0, fixture.run(target), fixture.error.toString(StandardCharsets.UTF_8));
            assertEquals(List.of("1  +  2  "), target.typeQueries);
        }
    }

    @Test
    void semanticCompletionUsesCommittedBindingsAndMemberMetadata() throws Exception {
        RecordingConsole target = new RecordingConsole(List.of());
        String input = "let @pub text :String = \"hi\"\n"
                + "tex\t\n"
                + "text:.le\t\n"
                + "\\quit\n";
        try (Fixture fixture = new Fixture(input, "emacs")) {
            fixture.rich.completeWith(target);
            assertEquals(0, fixture.run(target), fixture.error.toString(StandardCharsets.UTF_8));
            String output = fixture.output.toString(StandardCharsets.UTF_8);
            // The declared binding name completed from committed metadata and
            // the member completed from the committed type's member table.
            assertTrue(output.contains("String \"hi\"\n"), output);
            assertTrue(target.submitted.stream()
                            .anyMatch(source -> source.strip().equals("text")),
                    target.submitted.toString());
            assertTrue(target.submitted.stream()
                            .anyMatch(source -> source.strip().equals("text:.length")),
                    target.submitted.toString());
            assertEquals(List.of("text"), target.memberRequests);
        }
    }

    @Test
    void commonPersistentCorpusExecutesThroughTheRichManagedConsole() throws Exception {
        String script = "import counter\n"
                + "counter->::bump[]\n"
                + "import counter import counter->{bump as oldBump}\n"
                + "(oldBump)\n"
                + "let @mut count :I32 = 41\n"
                + "let reader :Fn<;I32> = (=> || count)\n"
                + "let count :String = \"replacement\"\n"
                + "(reader)\n"
                + "count\n"
                + "import higher (higher->:.apply (=> |x| (* x 3)) 7)\n"
                + "import rec rec->::fact[6]\n"
                + "import values (values->:.callables[0] 7)\n"
                + "values->:.pair:.1\n"
                + "values->:.items[0] := 9\n"
                + "import std->io io->::println[\"rich-output 😀\"]\n"
                + "import std->io io->::readLine[]\n"
                + "import errors errors->::late[]\n"
                + "\\reload counter\n"
                + "counter->:.visible\n"
                + "(oldBump)\n"
                + "counter->::bump[]\n"
                + "\\quit\n";
        ByteArrayOutputStream hostOutput = new ByteArrayOutputStream();
        RuntimeIoEnvironment environment = new RuntimeIoEnvironment(
                new ByteArrayInputStream("program 😀\n".getBytes(StandardCharsets.UTF_8)),
                hostOutput, hostOutput, StandardCharsets.UTF_8);
        SessionOptions options = SessionOptions.builder()
                .resolver(new RichCorpusResolver())
                .ioEnvironment(environment)
                .build();
        try (ManagedConsoleSession managed = ManagedConsoleSession.open(options);
             Fixture fixture = new Fixture(script, "emacs", false)) {
            fixture.rich.completeWith(managed);
            assertEquals(0, fixture.run(managed),
                    fixture.error.toString(StandardCharsets.UTF_8));
            String output = fixture.output.toString(StandardCharsets.UTF_8);
            String errors = fixture.error.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("I32 1\n"), output);
            assertTrue(output.contains("I32 2\n"), output);
            assertTrue(output.contains("I32 41\n"), output);
            assertTrue(output.contains("String \"replacement\"\n"), output);
            assertTrue(output.contains("I32 21\n"), output);
            assertTrue(output.contains("I32 720\n"), output);
            assertTrue(output.contains("I32 8\n"), output);
            assertTrue(output.contains("String \"one\"\n"), output);
            assertTrue(output.contains("@nilString \"program \\uD83D\\uDE00\"\n"), output);
            assertTrue(output.contains("I32 20\n"), output);
            assertTrue(output.contains("I32 3\n"), output);
            assertTrue(output.contains("I32 110\n"), output);
            assertTrue(errors.contains("LYC-RESOLVE-022"), errors);
            assertTrue(errors.contains("LYR-ARITH"), errors);
            assertTrue(hostOutput.toString(StandardCharsets.UTF_8)
                    .contains("rich-output 😀\n"));
        }
    }

    @Test
    void completionNeverBreaksEditingWhenTheTargetIsUnavailable() throws Exception {
        try (Fixture fixture = new Fixture("\\load any\t\n\\quit\n", "emacs")) {
            // No session wired: completion stays static and harmless.
            assertEquals(2, fixture.run());
            assertTrue(fixture.error.toString(StandardCharsets.UTF_8)
                    .contains("LYR-REPL-INFRA"));
        }
    }

    private static final class RichCorpusResolver implements SourceResolver {
        private final AtomicInteger counterResolutions = new AtomicInteger();

        @Override
        public Optional<ResolvedSource> resolve(LogicalModuleId logical) {
            String source = switch (logical.value()) {
                case "counter" -> counterResolutions.incrementAndGet() == 1
                        ? "import std->io io->::println[\"counter-init\"]\n"
                            + "let @mut hidden :I32 = 0\n"
                            + "let @pub @mut visible :I32 = 10\n"
                            + "let @pub bump :Fn<;I32> = (=> || { hidden := (+ hidden 1) hidden })"
                        : "import std->io io->::println[\"counter-init-2\"]\n"
                            + "let @mut hidden :I32 = 100\n"
                            + "let @pub @mut visible :I32 = 20\n"
                            + "let @pub bump :Fn<;I32> = (=> || { hidden := (+ hidden 10) hidden })";
                case "higher" -> "let @pub apply :Fn<Fn<I32;I32>,I32;I32> = (=> |f x| (f x))";
                case "rec" -> "let @pub fact :Fn<I32;I32> = "
                        + "(=> |n| ((== n 0) -> 1 : { let rest :I32 = ::fact[(- n 1)] (* n rest) }))";
                case "values" -> "let @pub pair :Tuple<I32,String> = Tuple[1 \"one\"]\n"
                        + "let @pub items :Array<I32> = Array<I32>[1 2 3]\n"
                        + "let @pub callables :Array<Fn<I32;I32>> = "
                        + "Array<Fn<I32;I32>>[(=> :I32 |x| (+ x 1))]";
                case "errors" -> "let @pub fail :Fn<I32;I32> = (=> |x| (% 11 x))\n"
                        + "let @pub late :Fn<;I32> = (=> || ::fail[0])";
                default -> null;
            };
            return source == null ? Optional.empty() : Optional.of(ResolvedSource.memory(
                    logical, URI.create("memory://rich-corpus/" + logical.value() + ".lyra"), source));
        }
    }

    /** Records every console operation while completing from a fixed table. */
    private static final class RecordingConsole implements ConsoleSession {
        private final List<Path> sourceRoots;
        final ArrayList<String> modulePrefixes = new ArrayList<>();
        final ArrayList<String> memberRequests = new ArrayList<>();
        final ArrayList<String> loadedPaths = new ArrayList<>();
        final ArrayList<String> reloadTargets = new ArrayList<>();
        final ArrayList<String> typeQueries = new ArrayList<>();
        final ArrayList<String> submitted = new ArrayList<>();
        int evaluations;

        RecordingConsole(List<Path> sourceRoots) {
            this.sourceRoots = sourceRoots;
        }

        @Override
        public SessionRevision revision() {
            return SessionRevision.initial();
        }

        @Override
        public Evaluation evaluate(EvaluationSource source) {
            evaluations++;
            submitted.add(source.text());
            if (source.text().strip().equals("text")) {
                return new Evaluation(EvaluationId.create(), EvaluationStatus.SUCCESS,
                        revision(), List.of(), Optional.of(new Value("String", "\"hi\"")),
                        Optional.empty());
            }
            return new Evaluation(EvaluationId.create(), EvaluationStatus.SUCCESS,
                    revision(), List.of(), Optional.empty(), Optional.empty());
        }

        @Override
        public Loaded load(String path) {
            loadedPaths.add(path);
            return new Loaded(new Evaluation(EvaluationId.create(), EvaluationStatus.SUCCESS,
                    revision(), List.of(), Optional.empty(), Optional.empty()),
                    Optional.of("let @pub inside :I32 = 7\n"));
        }

        @Override
        public Evaluation reload(String moduleOrAlias) {
            reloadTargets.add(moduleOrAlias);
            return new Evaluation(EvaluationId.create(), EvaluationStatus.SUCCESS,
                    revision(), List.of(), Optional.empty(), Optional.empty());
        }

        @Override
        public Control cancel(EvaluationId evaluationId) {
            return new Control(ControlStatus.NOT_FOUND, revision(), Optional.empty());
        }

        @Override
        public Control reset() {
            return new Control(ControlStatus.OK, revision(), Optional.empty());
        }

        @Override
        public Query query(QueryRequest request) {
            if (request.kind() == QueryRequest.Kind.BINDINGS) {
                return new Query(QueryStatus.OK,
                        List.of(new Binding("text", "String", "PUBLIC", false)),
                        Optional.empty(), Optional.empty());
            }
            typeQueries.add(request.source().orElseThrow().text());
            return new Query(QueryStatus.OK, List.of(), Optional.of("I64"), Optional.empty());
        }

        @Override
        public Completion complete(CompletionRequest request) {
            return switch (request.kind()) {
                case MODULE_FILES -> {
                    modulePrefixes.add(request.prefix().orElse(""));
                    ArrayList<CompletionItem> items = new ArrayList<>();
                    if (request.prefix().orElse("").equals("game->ma")) {
                        items.add(new CompletionItem("game->math", ItemKind.MODULE));
                    }
                    for (Path root : sourceRoots) {
                        try (var stream = Files.newDirectoryStream(root,
                                request.prefix().orElse("") + "*.lyra")) {
                            for (Path file : stream) {
                                String name = file.getFileName().toString();
                                items.add(new CompletionItem(name, ItemKind.FILE));
                                items.add(new CompletionItem(
                                        name.substring(0, name.length() - 5), ItemKind.MODULE));
                            }
                        } catch (IOException ignored) {
                            // Completion is read-only and best-effort.
                        }
                    }
                    yield new Completion(QueryStatus.OK, items, Optional.empty());
                }
                case BINDING_MEMBERS -> {
                    memberRequests.add(request.binding().orElseThrow());
                    yield new Completion(QueryStatus.OK,
                            List.of(new CompletionItem("length", ItemKind.MEMBER,
                                    Optional.of("I32"))),
                            Optional.empty());
                }
            };
        }
    }

    private static int occurrences(String text, String value) {
        return (text.length() - text.replace(value, "").length()) / value.length();
    }

    private static void assertResourceContains(String name, String expected) throws IOException {
        var resources = TerminalProvider.class.getClassLoader().getResources(name);
        boolean found = false;
        while (resources.hasMoreElements()) {
            try (var stream = resources.nextElement().openStream()) {
                found |= new String(stream.readAllBytes(), StandardCharsets.UTF_8).contains(expected);
            }
        }
        assertTrue(found, name);
    }

    private static final class Fixture implements AutoCloseable {
        private final ScriptedInput input;
        private final ByteArrayOutputStream display = new ByteArrayOutputStream();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final ByteArrayOutputStream error = new ByteArrayOutputStream();
        private final Terminal terminal;
        private final JLineConsole rich;

        private Fixture(String input, String keymap) throws IOException {
            this(input, keymap, true);
        }

        private Fixture(String input, String keymap, boolean quit) throws IOException {
            this.input = new ScriptedInput(input + (quit ? "\\quit\n" : ""));
            terminal = new DumbTerminal("lyra-test", "xterm", this.input, display, StandardCharsets.UTF_8);
            terminal.setSize(new Size(80, 24));
            Attributes attributes = terminal.getAttributes();
            attributes.setControlChar(Attributes.ControlChar.VEOF, 4);
            attributes.setLocalFlag(Attributes.LocalFlag.ICANON, true);
            attributes.setLocalFlag(Attributes.LocalFlag.ECHO, true);
            terminal.setAttributes(attributes);
            rich = new JLineConsole(terminal, keymap);
        }

        private int run() {
            return run(null);
        }

        private int run(ConsoleSession target) {
            LyraSession session = target == null ? LyraSession.open() : null;
            try {
                ConsoleSession console = target == null ? ConsoleSession.local(session) : target;
                return new PlainConsole(console, InputStream.nullInputStream(), output, error)
                        .runInteractive(rich);
            } finally {
                if (session != null) {
                    session.close();
                }
            }
        }

        private int runSource(String source) {
            var sources = new ArrayList<>(List.of(source, "\\quit"));
            LyraSession session = LyraSession.open();
            try {
                return new PlainConsole(session, InputStream.nullInputStream(), output, error)
                        .run(history -> sources.isEmpty() ? null : sources.removeFirst());
            } finally {
                session.close();
            }
        }

        private String screen() {
            return display.toString(StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws IOException {
            terminal.close();
        }
    }

    private static final class ScriptedInput extends ByteArrayInputStream {
        private Runnable onRead;

        private ScriptedInput(String source) {
            super(source.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public synchronized int read() {
            if (pos > 12 && onRead != null) {
                Runnable callback = onRead;
                onRead = null;
                callback.run();
            }
            return super.read();
        }
    }
}
