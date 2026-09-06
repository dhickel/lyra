package io.mindspice.lyra.repl;

import io.mindspice.lyra.runtime.RuntimeIoEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class PlainConsoleTest {
    @TempDir
    Path temp;

    @Test
    void sessionOptionsRetainTheCallerProvidedIoEnvironment() {
        RuntimeIoEnvironment environment = new RuntimeIoEnvironment(
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(), new ByteArrayOutputStream(),
                StandardCharsets.UTF_8);

        SessionOptions options = SessionOptions.builder()
                .ioEnvironment(environment)
                .build();

        assertSame(environment, options.ioEnvironment());
        assertThrows(NullPointerException.class,
                () -> SessionOptions.builder().ioEnvironment(null));
    }

    @Test
    void commandsArePlainAndBindingsAndHistoryAreMetadataOnly() {
        Invocation invocation = run("let @pub answer :I32 = 1\n"
                + ":bindings\n:history\n:quit\n");

        assertEquals(0, invocation.status());
        assertTrue(invocation.output().contains("answer :I32"));
        assertTrue(invocation.output().contains("let @pub answer :I32 = 1"));
        assertFalse(invocation.output().contains("answer = 1"), invocation.output());
        assertFalse(invocation.output().contains("I32 1"), invocation.output());
        assertEquals("", invocation.error());
    }

    @Test
    void persistentAggregatesAndTypeQueriesUseTheRealSessionWithoutExecutingQueries() {
        Invocation invocation = run("let @mut values :Array<Tuple<I32,String>> = Array<Tuple<I32,String>>[Tuple[1 \"a\"]]\n"
                + "let alias = values\n"
                + ":type { values[0] := Tuple[9 \"not executed\"] values[0]:.0 }\n"
                + "alias[0]:.0\n"
                + "values[0] := Tuple[42 \"changed\"]\n"
                + "alias[0]:.0\n:quit\n");
        assertEquals(0, invocation.status(), invocation.error());
        assertEquals("", invocation.error());
        assertTrue(invocation.output().contains("I32 1\n"), invocation.output());
        assertTrue(invocation.output().contains("I32 42\n"), invocation.output());
        assertFalse(invocation.output().contains("\u001b"));
    }

    @Test
    void bindingsShowCommittedPrivateAndPublicMetadataButNotFailedDeclarations() {
        Invocation invocation = run("let private :I32 = 1 "
                + "let @pub visible :I32 = 2\n"
                + "let @pub rejected :I32 = \"wrong\"\n"
                + ":bindings\n:quit\n");

        assertEquals(1, invocation.status());
        assertTrue(invocation.output().contains("visible :I32"));
        assertTrue(invocation.output().contains("private :I32"), invocation.output());
        assertFalse(invocation.output().contains("rejected"), invocation.output());
    }

    @Test
    void colonCommandsAreRecognizedOnlyAtTopLevel() {
        Invocation invocation = run("(\n"
                + ":help\n"
                + ")\n"
                + ":quit\n");

        assertEquals(1, invocation.status());
        assertTrue(invocation.error().contains("LYC-"), invocation.error());
        assertFalse(invocation.output().contains(PlainConsole.HELP_TEXT), invocation.output());
    }

    @Test
    void mismatchedDelimitersAreSubmittedAndDoNotStopLaterInput() {
        Invocation invocation = run(")\n"
                + "let @pub recovered :I32 = 4\n"
                + ":bindings\n:quit\n");

        assertEquals(1, invocation.status());
        assertTrue(invocation.error().contains("LYC-"), invocation.error());
        assertTrue(invocation.output().contains("recovered :I32"));
    }

    @Test
    void malformedCommandsRecoverAndPlainOutputHasNoDecoration() {
        Invocation invocation = run(":not-a-command\n:help\n:quit\n");

        assertEquals(2, invocation.status());
        assertTrue(invocation.error().contains("LYR-REPL-USAGE"));
        assertEquals(PlainConsole.HELP_TEXT, invocation.output());
        assertFalse(invocation.output().contains("\u001b["), invocation.output());
        assertFalse(invocation.error().contains("\u001b["), invocation.error());
    }

    @Test
    void typeDoesNotExecuteOrRecordAndUnsupportedReloadContinues() {
        Invocation invocation = run(":type 42\n"
                + ":type let @pub answer :I32 = 1\n"
                + ":bindings\n:reload\n:history\n:quit\n");

        assertEquals(2, invocation.status());
        assertTrue(invocation.output().contains("I64"), invocation.output());
        assertTrue(invocation.output().contains("Unit"), invocation.output());
        assertTrue(invocation.error().contains("LYR-REPL-RELOAD-UNSUPPORTED"));
        assertFalse(invocation.output().contains("answer"));
        assertFalse(invocation.output().contains("let @pub answer"));
    }

    @Test
    void localTypeQueryPreservesDiagnosticSourceMapping() {
        String text = "missing";
        URI uri = URI.create("file:///workspace/type.lyra");
        EvaluationSource source = new EvaluationSource(
                new SourceOrigin("type-selection", Optional.of(uri), Optional.of(3L),
                        12, 12 + text.length()),
                text);
        try (LyraSession session = LyraSession.open()) {
            LocalConsoleSession adapter = new LocalConsoleSession(session);
            ConsoleSession.Query query = adapter.query(ConsoleSession.QueryRequest.type(source));

            assertEquals(ConsoleSession.QueryStatus.UNAVAILABLE, query.status());
            assertTrue(query.detail().orElseThrow().contains("LYC-"));
            assertTrue(query.detail().orElseThrow().contains("file:///workspace/type.lyra:12..19"),
                    query.detail().orElseThrow());
            assertTrue(session.sourceRecords().isEmpty());
            assertEquals(SessionRevision.initial(), session.workspaceState().revision());
        }
    }

    @Test
    void localAdapterSupportsNonExecutingTypeQueriesAndKeepsRevisionOnReload() {
        LyraSession session = LyraSession.open();
        LocalConsoleSession adapter = new LocalConsoleSession(session);
        ConsoleSession.Evaluation evaluation = adapter.evaluate(
                EvaluationSource.of("local.lyra", "let @pub answer :I32 = 1"));
        assertEquals(ConsoleSession.EvaluationStatus.SUCCESS, evaluation.status());
        assertEquals(new SessionRevision(1), evaluation.revision());

        ConsoleSession.Query type = adapter.query(ConsoleSession.QueryRequest.type(
                EvaluationSource.of("type.lyra", "42")));
        assertEquals(ConsoleSession.QueryStatus.OK, type.status());
        assertEquals("I64", type.inferredType().orElseThrow());
        ConsoleSession.Control reload = adapter.reload();
        assertEquals(ConsoleSession.ControlStatus.UNAVAILABLE, reload.status());
        assertEquals(new SessionRevision(1), reload.revision());
        assertTrue(reload.detail().orElseThrow().contains("no source was replayed"));

        session.close();
        assertEquals(ConsoleSession.QueryStatus.CLOSED,
                adapter.query(ConsoleSession.QueryRequest.bindings()).status());
        assertEquals(ConsoleSession.ControlStatus.CLOSED, adapter.reset().status());
    }

    @Test
    void multilineUnitsUseNestedCommentsAndDelimiterState() {
        Invocation invocation = run("let @pub answer :I32 = (/* outer /* nested */ still */\n"
                + "+ 1\n"
                + "   2)\n"
                + ":bindings\n:quit\n");

        assertEquals(0, invocation.status(), invocation.error());
        assertTrue(invocation.output().contains("answer :I32"));
        assertEquals("", invocation.error());
    }

    @Test
    void recoverableEvaluationFailuresDoNotStopTheLoop() {
        Invocation invocation = run("let @pub bad :I32 =\n"
                + "let @pub good :I32 = 4\n"
                + ":bindings\n:quit\n");

        assertEquals(1, invocation.status());
        assertTrue(invocation.error().contains("LYC-"));
        assertTrue(invocation.output().contains("good :I32"));
    }

    @Test
    void malformedCommandsAndIncompleteEofHaveDistinctStatuses() {
        Invocation malformed = run(":load \"unterminated\n:quit\n");
        assertEquals(2, malformed.status());
        assertTrue(malformed.error().contains("LYR-REPL-USAGE"));

        Invocation incomplete = run("(+ 1\n");
        assertEquals(1, incomplete.status());
        assertTrue(incomplete.error().contains("LYR-REPL-EOF"));
        assertTrue(incomplete.error().contains("missing closing delimiter for: ("));

        Invocation incompleteComment = run("/* outer /* inner */\n");
        assertEquals(1, incompleteComment.status());
        assertTrue(incompleteComment.error().contains("LYR-REPL-EOF"));
    }

    @Test
    void quotedLoadPathsWorkWithoutPersistingHistoryToDisk() throws IOException {
        Path source = temp.resolve("source with spaces.lyra");
        Files.writeString(source, "/* café */ let @pub loaded :I32 = 3\n", StandardCharsets.UTF_8);

        Invocation invocation = run(":load \"" + source + "\"\n"
                + ":bindings\n:reset\n:history\n:quit\n");

        assertEquals(0, invocation.status(), invocation.error());
        assertTrue(invocation.output().contains("loaded :I32"));
        int reset = invocation.output().indexOf("loaded :I32");
        assertTrue(reset >= 0);
        assertFalse(invocation.output().substring(reset + "loaded :I32".length())
                .contains("loaded :I32"));
        assertFalse(Files.exists(temp.resolve(".lyra-history")));
    }

    @Test
    void loadRejectsSymlinksAndMalformedUtf8() throws IOException {
        Path invalid = temp.resolve("invalid.lyra");
        Files.write(invalid, new byte[] {'l', (byte) 0xC3});
        Invocation malformed = run(":load \"" + invalid + "\"\n:quit\n");
        assertEquals(2, malformed.status());
        assertTrue(malformed.error().contains("LYR-REPL-INFRA"), malformed.error());
        assertTrue(malformed.error().contains("not valid UTF-8"), malformed.error());

        Path target = temp.resolve("target.lyra");
        Files.writeString(target, "let @pub linked :I32 = 3\n", StandardCharsets.UTF_8);
        Path link = temp.resolve("link.lyra");
        boolean symlinksSupported = true;
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException | IOException failure) {
            symlinksSupported = false;
        }
        assumeTrue(symlinksSupported, "symbolic links are unavailable");

        Invocation symlink = run(":load \"" + link + "\"\n"
                + ":bindings\n:quit\n");
        assertEquals(2, symlink.status());
        assertTrue(symlink.error().contains("non-symbolic-link"), symlink.error());
        assertFalse(symlink.output().contains("linked"), symlink.output());
    }

    @Test
    void sourceEntryAndProgramReadLineShareOneExactUnicodeInputOwner() {
        String first = "let source :String = \"source 😀\"\n";
        String program = "программа 😀\n";
        String next = "let next :String = \"次\"\n";
        ByteArrayInputStream input = new ByteArrayInputStream(
                (first + program + next + ":history\n:quit\n")
                        .getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        ArrayList<String> evaluated = new ArrayList<>();
        ArrayList<String> programInput = new ArrayList<>();
        RuntimeIoEnvironment environment = new RuntimeIoEnvironment(
                input, output, error, StandardCharsets.UTF_8);
        ConsoleSession target = new ConsoleSession() {
            @Override
            public SessionRevision revision() { return SessionRevision.initial(); }

            @Override
            public Evaluation evaluate(EvaluationSource source) {
                evaluated.add(source.text());
                if (evaluated.size() == 1) {
                    programInput.add(environment.readLine());
                }
                return new Evaluation(EvaluationId.create(), EvaluationStatus.SUCCESS,
                        SessionRevision.initial(), List.of(), Optional.empty(), Optional.empty());
            }

            @Override
            public Control cancel(EvaluationId evaluationId) {
                return new Control(ControlStatus.NOT_FOUND, SessionRevision.initial(), Optional.empty());
            }

            @Override
            public Control reset() {
                return new Control(ControlStatus.OK, SessionRevision.initial(), Optional.empty());
            }

            @Override
            public Query query(QueryRequest request) {
                return Query.unavailable("not used by this test");
            }
        };

        int status = new PlainConsole(target, environment).run();

        assertEquals(0, status, error.toString(StandardCharsets.UTF_8));
        assertEquals(List.of(first, next), evaluated);
        assertEquals(List.of("программа 😀"), programInput);
        String history = output.toString(StandardCharsets.UTF_8);
        assertTrue(history.contains("1: " + first), history);
        assertTrue(history.contains("2: " + next), history);
        assertFalse(history.contains(program), history);
        assertEquals("", error.toString(StandardCharsets.UTF_8));
    }

    @Test
    void malformedProgramInputCannotBecomeSourceOrHistoryAfterFailure() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write("let source :I32 = 1\nlet privateInput :I32 = 7".getBytes(StandardCharsets.UTF_8));
        bytes.write(0xC3);
        bytes.write("\nlet next :I32 = 2\n:quit\n".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        var environment = new RuntimeIoEnvironment(new ByteArrayInputStream(bytes.toByteArray()),
                output, error, StandardCharsets.UTF_8);
        ArrayList<String> evaluated = new ArrayList<>();
        ConsoleSession target = new ConsoleSession() {
            @Override public SessionRevision revision() { return SessionRevision.initial(); }
            @Override public Evaluation evaluate(EvaluationSource source) {
                evaluated.add(source.text());
                assertThrows(io.mindspice.lyra.runtime.LyraIoException.class, environment::readLine);
                return new Evaluation(EvaluationId.create(), EvaluationStatus.RUNTIME_FAILURE,
                        revision(), List.of(), Optional.empty(), Optional.of("program input failed"));
            }
            @Override public Control cancel(EvaluationId id) {
                return new Control(ControlStatus.NOT_FOUND, revision(), Optional.empty());
            }
            @Override public Control reset() { return new Control(ControlStatus.OK, revision(), Optional.empty()); }
            @Override public Query query(QueryRequest request) { return Query.unavailable("not used"); }
        };
        assertEquals(2, new PlainConsole(target, environment).run());
        assertEquals(List.of("let source :I32 = 1\n"), evaluated);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("LYR-REPL-IO"));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("privateInput"));
        assertThrows(io.mindspice.lyra.runtime.LyraIoException.class, environment::checkInputAvailable);
        assertThrows(io.mindspice.lyra.runtime.LyraIoException.class, environment::readLine);
    }

    @Test
    void invalidConsoleUtf8IsReportedAsInputFailure() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        LyraSession session = LyraSession.open();
        try {
            int status = new PlainConsole(session,
                    new ByteArrayInputStream(new byte[] {'l', (byte) 0xC3}),
                    output, error).run();
            assertEquals(2, status);
            assertTrue(error.toString(StandardCharsets.UTF_8).contains("LYR-REPL-IO"));
        } finally {
            session.close();
        }
    }

    @Test
    void explicitHistoryFileIsPrivateSourceOnlyAndNeverReplayed() throws IOException {
        Path historyFile = temp.resolve("console.history");
        String multiLineSource = "let @pub multi :I32 = (/* outer /* inner */\n"
                + "still */ + 1\n2)\n";
        Invocation first = runWithHistory(historyFile,
                "let @pub remembered :I32 = 1\n" + multiLineSource + ":quit\n");
        assertEquals(0, first.status(), first.error());
        assertTrue(Files.isRegularFile(historyFile));
        String stored = Files.readString(historyFile);
        assertTrue(stored.contains("let @pub remembered :I32 = 1"));
        assertFalse(stored.contains(":quit"));
        assertTrue(stored.contains("\\n"));

        Invocation second = runWithHistory(historyFile,
                ":bindings\n:history\n:quit\n");
        assertEquals(0, second.status(), second.error());
        assertFalse(second.output().contains("\nremembered :I32\n"), second.output());
        assertFalse(second.output().contains("\nmulti :I32\n"), second.output());
        assertTrue(second.output().contains("let @pub remembered :I32 = 1"), second.output());
        assertTrue(second.output().contains(multiLineSource), second.output());
    }

    @Test
    void historyFileRejectsPermissiveModesAndSymlinks() throws IOException {
        Path permissive = temp.resolve("permissive.history");
        Files.writeString(permissive, "let @pub unsafe :I32 = 1\n");
        boolean posix;
        try {
            Files.getPosixFilePermissions(permissive);
            posix = true;
        } catch (UnsupportedOperationException failure) {
            posix = false;
        }
        assumeTrue(posix, "POSIX permissions are unavailable");
        Files.setPosixFilePermissions(permissive, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ));

        LyraSession session = LyraSession.open();
        try {
            assertThrows(IllegalArgumentException.class, () -> new PlainConsole(session,
                    new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(),
                    new ByteArrayOutputStream(), permissive));

            Path target = temp.resolve("history-target");
            Files.writeString(target, "let @pub linked :I32 = 1\n");
            Path link = temp.resolve("history-link");
            try {
                Files.createSymbolicLink(link, target);
            } catch (UnsupportedOperationException | SecurityException | IOException failure) {
                return;
            }
            assertThrows(IllegalArgumentException.class, () -> new PlainConsole(session,
                    new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(),
                    new ByteArrayOutputStream(), link));
        } finally {
            session.close();
        }
    }

    @Test
    void callerOwnedStreamsRemainOpen() {
        TrackingInput input = new TrackingInput(":quit\n");
        TrackingOutput output = new TrackingOutput();
        TrackingOutput error = new TrackingOutput();
        LyraSession session = LyraSession.open();
        try {
            assertEquals(0, new PlainConsole(session, input, output, error).run());
        } finally {
            session.close();
        }
        assertFalse(input.closed);
        assertFalse(output.closed);
        assertFalse(error.closed);
    }

    @Test
    void ctrlDOnEmptyInputExitsSuccessfully() {
        Invocation invocation = run("");
        assertEquals(0, invocation.status());
        assertEquals("", invocation.output());
        assertEquals("", invocation.error());
    }

    private Invocation run(String input) {
        return runWithHistory(null, input);
    }

    private Invocation runWithHistory(Path historyFile, String input) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        LyraSession session = LyraSession.open();
        try {
            int status = new PlainConsole(session,
                    new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                    output, error, historyFile).run();
            return new Invocation(status, text(output), text(error));
        } finally {
            session.close();
        }
    }

    private static String text(ByteArrayOutputStream stream) {
        return stream.toString(StandardCharsets.UTF_8);
    }

    private record Invocation(int status, String output, String error) {
    }

    private static final class TrackingInput extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInput(String text) {
            super(text.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class TrackingOutput extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
