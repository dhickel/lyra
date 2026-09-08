package io.mindspice.lyra.repl;

import io.mindspice.lyra.runtime.RuntimeIoEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 13 cross-surface conformance, surface 2: the plain console.  Runs
 * the console-applicable corpus with one coordinated input owner: source
 * entry, generated readLine program input, subsequent source, history and
 * quit share the exact stream without byte stealing or history
 * contamination.
 */
class CrossSurfacePlainConsoleTest {
    @TempDir
    Path temp;

    private static final String SCRIPT = "import counter\n"
            + "counter->::bump[]\n"
            + "import counter import counter->{bump as bumpAgain}\n"
            + "(bumpAgain)\n"
            + "import counter->{read as readOld bump as oldBump}\n"
            + "let @mut count :I32 = 1\n"
            + "count := 41\n"
            + "let reader :Fn<;I32> = (=> || count)\n"
            + "(reader)\n"
            + "let count :String = \"replacement\"\n"
            + "(reader)\n"
            + "count\n"
            + "let @mut probe :I32 = 1\n"
            + "import higher (higher->:.apply (=> |x| (* x 3)) 7)\n"
            + "import values values->:.items[2]\n"
            + "(values->:.callables[0] 7)\n"
            + "values->:.items[0] := 9\n"
            + "import std->io io->::println[\"console-output\"]\n"
            + "import std->io io->::readLine[]\n"
            + CrossSurfaceCorpus.PROGRAM_INPUT
            + "import errors\n"
            + "errors->::late[]\n"
            + ":bindings\n"
            + ":type { probe := 99 probe }\n"
            + "probe\n"
            + ":history\n"
            + ":reload counter\n"
            + "counter->:.visible\n"
            + "(readOld)\n"
            + "counter->::bump[]\n"
            + ":quit\n";

    @Test
    void consoleCorpusKeepsOneInputOwnerAndExactSourceHistory() {
        var sources = new CrossSurfaceCorpus.Sources();
        sources.useCounterV2OnReload();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        RuntimeIoEnvironment environment = new RuntimeIoEnvironment(
                new ByteArrayInputStream(SCRIPT.getBytes(StandardCharsets.UTF_8)),
                output, error, StandardCharsets.UTF_8);
        var session = LyraSession.open(
                CrossSurfaceCorpus.options(sources).ioEnvironment(environment).build());
        int status;
        try {
            status = new PlainConsole(session, environment, null).run();
        } finally {
            session.close();
        }
        // The ownership diagnostic is a recoverable evaluation failure.
        assertEquals(1, status, error.toString(StandardCharsets.UTF_8));
        String out = output.toString(StandardCharsets.UTF_8);
        String err = error.toString(StandardCharsets.UTF_8);

        // Persistent counter and private replacement across the console.
        assertTrue(out.contains("I32 1\n"), out);
        assertTrue(out.contains("I32 2\n"), out);
        assertTrue(out.contains("I32 41\n"), out);
        assertEquals(1, occurrences(out, "String \"replacement\"\n"), out);
        assertTrue(out.contains("I32 21\n"), out);
        assertTrue(out.contains("I32 8\n"), out);
        // Real std->io output and coordinated program input.
        assertTrue(out.contains("console-output\n"), out);
        assertEquals(1, occurrences(out, "@nilString \"prog-input\"\n"), out);
        // Ownership diagnostics render through the console error surface.
        assertTrue(err.contains("LYC-RESOLVE-022"), err);
        // The delayed imported failure renders with its division diagnostic.
        assertTrue(err.contains("LYR-ARITH"), err);
        assertTrue(err.contains("division by zero"), err);
        // :bindings shows committed metadata; :type analyzes without running.
        assertTrue(out.contains("reader :Fn<;I32>"), out);
        assertTrue(out.contains("I32\n"), out);
        // :type did not execute the staged mutation: probe keeps its value.
        assertTrue(out.contains("I32\nI32 1\n"), out);
        // Source history contains only submitted source, never program input.
        assertTrue(out.contains("1: import counter\n"), out);
        assertFalse(out.contains(": prog-input\n"), out);
        // Reload through the console publishes new defaults while the
        // selective old reader keeps its original producer.
        assertTrue(out.contains("I32 20\n"), out);
        assertTrue(out.contains("I32 110\n"), out);
        int afterVisible = out.indexOf("I32 20\n");
        assertTrue(out.indexOf("I32 2\n", afterVisible) >= 0, out);
    }

    @Test
    void corpusConsoleHistoryFileKeepsSourceOnlyAndSurvivesReread() {
        var sources = new CrossSurfaceCorpus.Sources();
        Path historyFile = temp.resolve("corpus-history.txt");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        RuntimeIoEnvironment environment = new RuntimeIoEnvironment(
                new ByteArrayInputStream(("import counter\ncounter->::bump[]\n:history\n:quit\n")
                        .getBytes(StandardCharsets.UTF_8)),
                output, error, StandardCharsets.UTF_8);
        var session = LyraSession.open(
                CrossSurfaceCorpus.options(sources).ioEnvironment(environment).build());
        try {
            assertEquals(0, new PlainConsole(session, environment, historyFile).run(),
                    error.toString(StandardCharsets.UTF_8));
        } finally {
            session.close();
        }
        String out = output.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("1: import counter\n2: counter->::bump[]\n"), out);
        // The file records escaped exact source entries only; numbering is a
        // presentation concern applied when the file is read again.
        String file = readFile(historyFile);
        assertTrue(file.contains("import counter\\n"), file);
        assertTrue(file.contains("counter->::bump[]\\n"), file);
        assertFalse(file.contains(":history"), file);
        assertFalse(file.contains(":quit"), file);

        ByteArrayOutputStream rereadOutput = new ByteArrayOutputStream();
        ByteArrayOutputStream rereadError = new ByteArrayOutputStream();
        RuntimeIoEnvironment rereadEnvironment = new RuntimeIoEnvironment(
                new ByteArrayInputStream(":history\n:quit\n".getBytes(StandardCharsets.UTF_8)),
                rereadOutput, rereadError, StandardCharsets.UTF_8);
        var rereadSession = LyraSession.open(
                CrossSurfaceCorpus.options(sources).ioEnvironment(rereadEnvironment).build());
        try {
            assertEquals(0, new PlainConsole(rereadSession, rereadEnvironment, historyFile).run(),
                    rereadError.toString(StandardCharsets.UTF_8));
        } finally {
            rereadSession.close();
        }
        assertTrue(rereadOutput.toString(StandardCharsets.UTF_8)
                .contains("1: import counter\n2: counter->::bump[]\n"));
    }

    private static int occurrences(String text, String value) {
        return (text.length() - text.replace(value, "").length()) / value.length();
    }

    private static String readFile(Path path) {
        try {
            return java.nio.file.Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }
}
