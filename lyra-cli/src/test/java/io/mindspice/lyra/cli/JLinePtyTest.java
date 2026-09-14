package io.mindspice.lyra.cli;

import io.mindspice.lyra.runtime.RuntimeIoEnvironment;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Timeout(60)
final class JLinePtyTest {
    @TempDir
    Path temp;

    @Test
    void realTerminalHandlesPasteMultilineResizeCommandsAndCleanup() throws Exception {
        Files.writeString(temp.resolve("newmod.lyra"), "let @pub loaded :I32 = 7\n");
        pty("rich", true, LyraCli.class.getName(), "repl",
                "--source-root", temp.toString());
    }

    @Test
    void activeCtrlCCancelsOnlyTheKnownEvaluationAndLaterSourceSucceeds()
            throws Exception {
        pty("rich-cancel", true, LyraCli.class.getName(), "repl");
    }

    @Test
    void generatedReadLinePreservesUnicodeFollowingSourceAndHistoryOrdering()
            throws Exception {
        pty("readline", true, LyraCli.class.getName(), "repl");
    }

    @Test
    void undecoratedTtyCtrlCCancelsTheActiveEvaluationWithoutACompetingReader()
            throws Exception {
        pty("plain-cancel", true, LyraCli.class.getName(), "repl", "--plain");
    }

    @Test
    void terminalInterruptWorksWithInheritedIgnoredSignals() throws Exception {
        pty("rich-ignored", true, LyraCli.class.getName(), "repl");
    }

    @Test
    void realTerminalSelectsViAndForcedPlain() throws Exception {
        pty("vi", true, LyraCli.class.getName(), "repl", "--keymap", "vi");
        pty("plain", true, LyraCli.class.getName(), "repl", "--plain", "--keymap", "vi");
    }

    @Test
    void unavailableNativeAccessFallsBackBeforeReadingAnySource() throws Exception {
        pty("fallback", false, LyraCli.class.getName(), "repl");
    }

    @Test
    void programStreamsKeepTheirOwnershipBetweenReadsAndAfterTerminalClose() throws Exception {
        pty("ownership", true, StreamOwnerProbe.class.getName());
    }

    @Test
    void nonTtyProcessDefaultsToUndecoratedPlainWithoutNativeAccess() throws Exception {
        Process process = new ProcessBuilder(javaCommand(), "-cp", System.getProperty("java.class.path"),
                LyraCli.class.getName(), "repl").start();
        try {
            process.getOutputStream().write("\\help\n\\quit\n".getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            assertEquals(io.mindspice.lyra.repl.PlainConsole.HELP_TEXT,
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("", new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private void pty(String mode, boolean nativeAccess, String... arguments) throws Exception {
        ArrayList<String> command = new ArrayList<>(List.of(javaCommand()));
        if (nativeAccess) {
            command.add("--enable-native-access=ALL-UNNAMED");
        }
        command.addAll(List.of("-cp", System.getProperty("java.class.path")));
        command.addAll(List.of(arguments));
        runPty(temp, mode, command);
    }

    static void runPty(Path directory, String mode, List<String> command) throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux"), "requires Linux PTY");
        Path script = directory.resolve("jline_pty.py");
        try (InputStream input = JLinePtyTest.class.getResourceAsStream("/jline_pty.py")) {
            assertNotNull(input);
            Files.write(script, input.readAllBytes());
        }
        ArrayList<String> invocation = new ArrayList<>(List.of("python3", script.toString(), mode));
        invocation.addAll(command);
        Path log = directory.resolve("pty-" + mode + ".log");
        ProcessBuilder builder = new ProcessBuilder(invocation).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("JAVA_COMMAND", javaCommand());
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "PTY subprocess timed out");
            String output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertEquals("pty-ok:" + mode + "\n", output);
        } finally {
            // The python driver kills its REPL child only while it runs its own
            // teardown. When this test times out or is interrupted and the
            // driver is force-killed, reap any surviving REPL descendant here.
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** Checks stream coordination without claiming unsupported std->io session imports. */
    public static final class StreamOwnerProbe {
        public static void main(String[] args) throws Exception {
            var input = System.in;
            var output = System.out;
            var error = System.err;
            try (Terminal terminal = JLineConsole.openTerminal()) {
                if (terminal == null) {
                    throw new AssertionError("no interactive terminal");
                }
                JLineConsole console = new JLineConsole(terminal, "emacs");
                String source = console.readSource(List.of());
                if (!"let value :String = \"😀\"\n".equals(source)) {
                    throw new AssertionError(source);
                }
                RuntimeIoEnvironment environment = new RuntimeIoEnvironment(
                        console.programInput(), output, error, StandardCharsets.UTF_8);
                output.print("program-input> ");
                output.flush();
                if (!"программа 😀".equals(environment.readLine())) {
                    throw new AssertionError("program input was consumed by JLine");
                }
                output.println("program-output");
                error.println("program-error");
                String next = console.readSource(List.of(source));
                if (!"let next :String = \"次\"\n".equals(next)) {
                    throw new AssertionError("queued next source was lost: " + next);
                }
            }
            output.print("after-close> ");
            output.flush();
            if (!"still-open".equals(readLine(input)) || input != System.in
                    || output != System.out || error != System.err) {
                throw new AssertionError("process streams were closed or replaced");
            }
            output.println("ownership-ok");
        }

        private static String readLine(InputStream input) throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int next;
            while ((next = input.read()) != -1 && next != '\n') {
                bytes.write(next);
            }
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }
}
