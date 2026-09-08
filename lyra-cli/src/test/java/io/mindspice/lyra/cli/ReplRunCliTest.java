package io.mindspice.lyra.cli;

import io.mindspice.lyra.repl.EvaluationSource;
import io.mindspice.lyra.repl.remote.LoopbackEndpoint;
import io.mindspice.lyra.repl.remote.ProtocolMessage;
import io.mindspice.lyra.repl.remote.RemoteClient;
import io.mindspice.lyra.repl.remote.RemoteEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 12 run/compile activation and shutdown: listener bootstrap before
 * root publication, gated live work until registration, wait-for-controller,
 * cleanup ordering, literal-argument/exit preservation, and no credential
 * surface. Ordinary run/compile behavior is unchanged.
 */
@Timeout(180)
final class ReplRunCliTest {
    private static final String ARGS_MAIN =
            "let @pub main :Fn<Array<String>;I32> = (=> |args| args:.length)\n";
    /** Spins at generated safe points until a client assigns count; returns it. */
    private static final String SPIN_SOURCE = "let @pub @mut count :I32 = 0\n"
            + "let @pub spin :Fn<;I32> = (=> | | ((== count 0) -> ::spin[] : count))\n"
            + "let @pub main :Fn<Array<String>;I32> = (=> |args| ::spin[])\n";
    private static final String MARKER_SPIN_SOURCE = "import std->io as io "
            + "let @pub @mut count :I32 = 0\n"
            + "let @pub spin :Fn<;I32> = (=> | | ((== count 0) -> ::spin[] : count))\n"
            + "let @pub main :Fn<Array<String>;I32> = (=> |args| "
            + "{ io->::println[\"MAIN-RAN\"] (spin) })\n";
    /** Initialization blocks on host input, then fails with LYR-ARITH. */
    private static final String BLOCKING_FAILING_INIT = "import std->io as io "
            + "let @pub boom :F64 = { let @mut line :String = (io->::readLine[] : \"\") "
            + "(/ 1 line:.length) }\n"
            + "let @pub main :Fn<Array<String>;I32> = (=> |args| 0)\n";

    private static final Pattern ENDPOINT = Pattern.compile(
            "lyra: repl listener: ([^ ]+):(\\d+) \\(no authentication");

    @TempDir
    Path temp;

    @Test
    void replPortAndWaitRequireReplAndPortsAreValidatedIncludingZero() {
        Path root = source("args.lyra", ARGS_MAIN);
        for (String[] arguments : List.of(
                new String[] {"run", root.toString(), "--repl-port", "1234"},
                new String[] {"run", root.toString(), "--repl-wait"},
                new String[] {"run", root.toString(), "--repl", "--repl-port"},
                new String[] {"run", root.toString(), "--repl", "--repl-port", "65536"},
                new String[] {"run", root.toString(), "--repl", "--repl-port", "-1"},
                new String[] {"run", root.toString(), "--repl", "--repl-port", "abc"},
                new String[] {"run", root.toString(), "--repl", "--repl-port", ""},
                new String[] {"run", root.toString(), "--repl", "--repl-port", "0",
                        "--repl-port", "1"},
                new String[] {"run", root.toString(), "--repl", "--repl-wait", "--repl-wait"},
                new String[] {"compile", root.toString(), "--repl-port", "1"},
                new String[] {"compile", root.toString(), "--repl-wait"})) {
            Invocation failure = invoke(arguments);
            assertEquals(2, failure.status(), String.join(" ", arguments));
            assertEquals("", failure.stdout());
            assertTrue(failure.stderr().contains(LyraCli.USAGE_TEXT), failure.stderr());
        }
    }

    @Test
    void helpDocumentsReplActivationWithoutCredentialSurface() {
        String help = invoke("--help").stdout();
        assertTrue(help.contains("--repl"), help);
        assertTrue(help.contains("--repl-port"), help);
        assertTrue(help.contains("--repl-wait"), help);
        assertTrue(help.contains("lyra.repl.enabled"), help);
        assertTrue(help.contains("unauthenticated"), help);
        assertFalse(help.contains("token"), help);
        assertFalse(help.contains("credential"), help);
    }

    @Test
    void runReplBindsAnUnauthenticatedEndpointAndServicesLiveRootWork() throws Exception {
        Path root = source("spin.lyra", SPIN_SOURCE);
        try (LiveRun run = startRun(new String[] {"run", root.toString(),
                "--repl", "--repl-port", "0"}, new ByteArrayInputStream(new byte[0]))) {
            RemoteEndpoint endpoint = awaitEndpoint(run);
            assertTrue(endpoint.address().port() > 0, "port 0 must bind an ephemeral port");
            // No credential surface: the endpoint carries address and warning only.
            try (RemoteClient client = RemoteClient.connect(endpoint)) {
                // A submit that races the initialization gate is truthfully
                // BUSY until registration; the live app then services it.
                ProtocolMessage.Result result = submitUntilTerminal(client, "count := 42");
                assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, result.status(), run.stderr());
            }
            run.awaitExit();
            assertEquals(42, run.status(), run.stderr());
            assertNoLeakedRemoteThreads();
            // No post-main keepalive: the listener is closed with the run.
            assertThrows(IOException.class, () -> connect(endpoint, 500));
        }
    }

    @Test
    void replWaitGatesMainUntilTheV2HandshakeNotARawAccept() throws Exception {
        Path root = source("marker.lyra", MARKER_SPIN_SOURCE);
        try (LiveRun run = startRun(new String[] {"run", root.toString(), "--repl",
                "--repl-port", "0", "--repl-wait"}, new ByteArrayInputStream(new byte[0]))) {
            RemoteEndpoint endpoint = awaitEndpoint(run);
            // A raw accepted socket is not controller readiness.
            try (Socket raw = connect(endpoint, 2000)) {
                Thread.sleep(400);
                assertFalse(run.stdout().contains("MAIN-RAN"),
                        "main must not run before the v2 controller handshake");
            }
            // A controller that disconnects during the wait must not end it.
            Thread.sleep(300);
            assertFalse(run.stdout().contains("MAIN-RAN"),
                    "a disconnect during the wait must not start main");
            try (RemoteClient client = RemoteClient.connect(endpoint)) {
                run.awaitLine("MAIN-RAN");
                ProtocolMessage.Result result = submitUntilTerminal(client, "count := 41");
                assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, result.status());
            }
            run.awaitExit();
            assertEquals(41, run.status(), run.stderr());
        }
    }

    @Test
    void initFailureGatesLiveWorkAndPreservesThePrimaryFailure() throws Exception {
        Path root = source("failing.lyra", BLOCKING_FAILING_INIT);
        PipedOutputStream inputSource = new PipedOutputStream();
        try (PipedInputStream input = new PipedInputStream(inputSource);
             LiveRun run = startRun(new String[] {"run", root.toString(),
                     "--repl", "--repl-port", "0"}, input)) {
            RemoteEndpoint endpoint = awaitEndpoint(run);
            try (RemoteClient client = RemoteClient.connect(endpoint)) {
                // Initialization blocks on readLine; live work is truthfully
                // rejected and never touches a root.
                ProtocolMessage.Result result = client.submit(EvaluationSource.of(
                        "probe.lyra", "count := 1")).result().get(10, TimeUnit.SECONDS);
                assertEquals(ProtocolMessage.RemoteStatus.BUSY, result.status(), run.stderr());
                assertTrue(run.stdout().isEmpty(), "no root access before registration");
            }
            // Unblock the initializer; the division by zero then fails init.
            inputSource.write("\n".getBytes(StandardCharsets.UTF_8));
            inputSource.flush();
            run.awaitExit();
            assertEquals(1, run.status(), run.stderr());
            assertTrue(run.stderr().contains("LYR-"), run.stderr());
            assertTrue(run.stderr().contains("LYR-INIT")
                            || run.stderr().contains("LYR-ARITH"), run.stderr());
            assertNoLeakedRemoteThreads();
        }
    }

    @Test
    void bindFailureIsAConfigurationFailureAndLeavesNoListener() throws Exception {
        Path root = source("args.lyra", ARGS_MAIN);
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            Invocation result = invoke("run", root.toString(), "--repl",
                    "--repl-port", Integer.toString(occupied.getLocalPort()));
            assertEquals(2, result.status(), result.stderr());
            assertTrue(result.stderr().contains("cannot start the REPL listener"),
                    result.stderr());
            assertEquals("", result.stdout());
        }
    }

    @Test
    void literalArgumentsAfterSeparatorAndNonzeroMainExitRemainUnchanged() throws Exception {
        Path root = source("args.lyra", ARGS_MAIN);
        try (LiveRun run = startRun(new String[] {"run", root.toString(), "--repl",
                "--repl-port", "0", "--", "a", "b", "c"},
                new ByteArrayInputStream(new byte[0]))) {
            awaitEndpoint(run);
            run.awaitExit();
            // The nonzero main return is preserved exactly; no client is
            // needed and there is no post-main keepalive.
            assertEquals(3, run.status(), run.stderr());
            assertTrue(run.stderr().contains("no authentication"), run.stderr());
        }
        // Ordinary runs keep the exact same argument/exit contract.
        assertEquals(2, invoke("run", root.toString(), "--", "x", "y").status());
    }

    @Test
    void compileReplRecordsCapabilityAndNeverListens() throws Exception {
        Path root = source("app.lyra", "let @pub main :Fn<Array<String>;I32> = (=> |args| 7)\n");
        Path bundled = temp.resolve("app.jar");
        assertEquals(0, invoke("compile", root.toString(), "--repl",
                "--output", bundled.toString()).status());
        try (JarFile jar = new JarFile(bundled.toFile())) {
            assertEquals("io.mindspice.lyra.repl.ReplLauncher",
                    jar.getManifest().getMainAttributes().getValue("Main-Class"));
        }
        // Without activation properties the compiled app runs plain main.
        ProcessResult plain = process(java(), "-Xverify:all", "-jar", bundled.toString());
        assertEquals(7, plain.exit(), plain.output());
        assertFalse(plain.output().contains("listener"),
                "compiled artifacts never listen without explicit activation");
        // With activation properties the same artifact activates.
        ProcessResult activated = process(java(), "-Dlyra.repl.enabled=true",
                "-Xverify:all", "-jar", bundled.toString());
        assertEquals(7, activated.exit(), activated.output());
        assertTrue(activated.output().contains("lyra: repl listener: "),
                activated.output());
    }

    @Test
    void activeRemoteOperationAtShutdownKeepsTerminalStatusAndNoThreadsLeak()
            throws Exception {
        // The spin run above proves an active operation completes before
        // shutdown; this run repeats it through a nonzero exit and asserts
        // the wire result stays terminal and the owner/thread cleanup is
        // complete when the service closes under an already-completed op.
        Path root = source("spin.lyra", SPIN_SOURCE);
        try (LiveRun run = startRun(new String[] {"run", root.toString(), "--repl",
                "--repl-port", "0"}, new ByteArrayInputStream(new byte[0]))) {
            RemoteEndpoint endpoint = awaitEndpoint(run);
            ProtocolMessage.Result result;
            try (RemoteClient client = RemoteClient.connect(endpoint)) {
                result = submitUntilTerminal(client, "count := 9");
                run.awaitExit();
            }
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, result.status());
            assertEquals(9, run.status(), run.stderr());
            assertNoLeakedRemoteThreads();
        }
    }

    /* ---- harness ---- */

    /** Submits one assignment, retrying the truthful bootstrap race. */
    private static ProtocolMessage.Result submitUntilTerminal(RemoteClient client,
                                                              String source)
            throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        ProtocolMessage.Result result = null;
        while (System.nanoTime() < deadline) {
            io.mindspice.lyra.repl.remote.RemoteRequest request =
                    client.submit(EvaluationSource.of("write.lyra", source));
            try {
                result = request.result().get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException timeout) {
                throw new AssertionError("no terminal result; request status="
                        + request.status());
            }
            if (result.status() == ProtocolMessage.RemoteStatus.BUSY) {
                Thread.sleep(25);
                continue;
            }
            return result;
        }
        throw new AssertionError("the live root never admitted the evaluation: " + result);
    }

    private static RemoteEndpoint awaitEndpoint(LiveRun run) throws Exception {
        String line = run.awaitErrorLine(30, value -> ENDPOINT.matcher(value).find());
        Matcher matcher = ENDPOINT.matcher(line);
        assertTrue(matcher.find(), line);
        return new RemoteEndpoint(LoopbackEndpoint.of(matcher.group(1),
                Integer.parseInt(matcher.group(2))));
    }

    private static Socket connect(RemoteEndpoint endpoint, int timeoutMillis)
            throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(endpoint.address().address(),
                endpoint.address().port()), timeoutMillis);
        return socket;
    }

    private static void assertNoLeakedRemoteThreads() throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            List<String> leaked = Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> thread.isAlive()
                            && (thread.getName().startsWith("lyra-repl-remote-")
                            || thread.getName().startsWith("lyra-managed-console-owner")))
                    .map(Thread::getName)
                    .toList();
            if (leaked.isEmpty()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("REPL threads leaked after shutdown: "
                + Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive())
                .map(Thread::getName)
                .toList());
    }

    private static final class LiveRun implements AutoCloseable {
        private Thread thread;
        private final CapturingOutput stdout;
        private final CapturingOutput stderr;
        private final AtomicInteger status;
        private final AtomicReference<Throwable> failure;

        private LiveRun(Thread thread, CapturingOutput stdout, CapturingOutput stderr,
                        AtomicInteger status, AtomicReference<Throwable> failure) {
            this.thread = thread;
            this.stdout = stdout;
            this.stderr = stderr;
            this.status = status;
            this.failure = failure;
        }

        String stdout() {
            return stdout.text();
        }

        String stderr() {
            return stderr.text();
        }

        int status() {
            return status.get();
        }

        String awaitLine(String prefix) throws Exception {
            return awaitOutput(stdout, 30, value -> value.contains(prefix));
        }

        String awaitErrorLine(int seconds, java.util.function.Predicate<String> matches)
                throws Exception {
            return awaitOutput(stderr, seconds, matches);
        }

        private static String awaitOutput(CapturingOutput output, int seconds,
                                          java.util.function.Predicate<String> matches)
                throws Exception {
            long deadline = System.nanoTime() + seconds * 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                for (String line : output.text().split("\n", -1)) {
                    if (matches.test(line)) {
                        return line;
                    }
                }
                Thread.sleep(5);
            }
            throw new AssertionError("expected output did not appear: " + output.text());
        }

        void awaitExit() throws Exception {
            thread.join(60_000);
            if (thread.isAlive()) {
                throw new AssertionError("run did not terminate: " + stderr.text());
            }
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }
        }

        @Override
        public void close() {
            if (thread.isAlive()) {
                thread.interrupt();
            }
            try {
                thread.join(5000);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static LiveRun startRun(String[] arguments, InputStream input) {
        CapturingOutput stdout = new CapturingOutput();
        CapturingOutput stderr = new CapturingOutput();
        AtomicInteger status = new AtomicInteger(-1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        LiveRun run = new LiveRun(new Thread(), stdout, stderr, status, failure);
        Thread thread = new Thread(() -> {
            try {
                status.set(LyraCli.execute(arguments, input, stdout, stderr));
            } catch (Throwable problem) {
                failure.set(problem);
            }
        }, "repl-run-cli-test");
        run.thread = thread;
        thread.start();
        return run;
    }

    private static final class CapturingOutput extends OutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int value) {
            buffer.write(value);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            buffer.write(bytes, offset, length);
        }

        synchronized String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    private Invocation invoke(String... arguments) {
        return invoke(arguments, new ByteArrayInputStream(new byte[0]));
    }

    private Invocation invoke(String[] arguments, InputStream input) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int status = LyraCli.execute(arguments, input, output, error);
        return new Invocation(status, output.toString(StandardCharsets.UTF_8),
                error.toString(StandardCharsets.UTF_8));
    }

    private record Invocation(int status, String stdout, String stderr) {
    }

    private Path source(String name, String contents) {
        Path path = temp.resolve(name);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, contents, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
        return path;
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private ProcessResult process(String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(temp.toFile())
                .redirectErrorStream(true).start();
        try {
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("subprocess timed out: " + String.join(" ", command));
            }
            return new ProcessResult(process.exitValue(),
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            process.destroyForcibly();
        }
    }

    private record ProcessResult(int exit, String output) {
    }
}
