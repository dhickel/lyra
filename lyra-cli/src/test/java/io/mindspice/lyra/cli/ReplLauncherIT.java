package io.mindspice.lyra.cli;

import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.CompileResult;
import io.mindspice.lyra.compiler.api.CompiledArtifact;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.repl.EvaluationSource;
import io.mindspice.lyra.repl.remote.LoopbackEndpoint;
import io.mindspice.lyra.repl.remote.ProtocolMessage;
import io.mindspice.lyra.repl.remote.RemoteClient;
import io.mindspice.lyra.repl.remote.RemoteEndpoint;
import io.mindspice.lyra.repl.remote.RemoteRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 12 launcher activation: property-driven run/host activation on the
 * debug launcher, default-disabled, wait gating, explicit-layout artifact
 * location isolation, invalid controls and bind failures.
 */
@Timeout(180)
final class ReplLauncherIT {
    private static final String SEVEN_MAIN =
            "let @pub main :Fn<Array<String>;I32> = (=> |args| 7)\n";
    private static final String ARGS_MAIN =
            "let @pub main :Fn<Array<String>;I32> = (=> |args| args:.length)\n";
    private static final String SPIN_SOURCE = "let @pub @mut count :I32 = 0\n"
            + "let @pub spin :Fn<;I32> = (=> | | ((== count 0) -> ::spin[] : count))\n"
            + "let @pub main :Fn<Array<String>;I32> = (=> |args| ::spin[])\n";
    private static final String MARKER_SPIN_SOURCE = "import std->io as io "
            + "let @pub @mut count :I32 = 0\n"
            + "let @pub spin :Fn<;I32> = (=> | | ((== count 0) -> ::spin[] : count))\n"
            + "let @pub main :Fn<Array<String>;I32> = (=> |args| "
            + "{ io->::println[\"MAIN-RAN\"] (spin) })\n";

    private static final Pattern ENDPOINT = Pattern.compile(
            "lyra: repl listener: ([^ ]+):(\\d+) \\(no authentication");

    @TempDir
    Path temp;

    @Test
    void activationIsDisabledByDefaultAndRunsPlainMain() throws Exception {
        Path jar = compileJar("app.lyra", SEVEN_MAIN);
        Child child = start(java(), "-Xverify:all", "-jar", jar.toString());
        assertEquals(7, child.waitFor(60));
        assertFalse(child.allStderr(2).contains("listener"),
                "no listener may start without explicit activation properties");
    }

    @Test
    void activatedLauncherServicesLiveRootWorkAndPreservesTheMainExit() throws Exception {
        Path jar = compileJar("spin.lyra", SPIN_SOURCE);
        Child child = start(java(), "-Dlyra.repl.enabled=true",
                "-Xverify:all", "-jar", jar.toString());
        try {
            RemoteEndpoint endpoint = awaitEndpoint(child);
            try (RemoteClient client = RemoteClient.connect(endpoint)) {
                ProtocolMessage.Result result = submitUntilTerminal(client, "count := 42");
                assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, result.status(),
                        child.allStderr(2));
            }
            assertEquals(42, child.waitFor(60), child.allStderr(2));
        } finally {
            child.destroy();
        }
    }

    @Test
    void waitGatesMainUntilTheV2HandshakeAndSurvivesDisconnects() throws Exception {
        Path jar = compileJar("marker.lyra", MARKER_SPIN_SOURCE);
        Child child = start(java(), "-Dlyra.repl.enabled=true",
                "-Dlyra.repl.wait=true", "-Xverify:all", "-jar", jar.toString());
        try {
            RemoteEndpoint endpoint = awaitEndpoint(child);
            // A raw accepted socket never counts as a ready controller.
            try (Socket raw = connect(endpoint, 2000)) {
                Thread.sleep(400);
                assertFalse(child.stdoutLines().contains("MAIN-RAN"),
                        "main must not run before the controller handshake");
            }
            Thread.sleep(300);
            assertFalse(child.stdoutLines().contains("MAIN-RAN"),
                    "a disconnect during the wait must not start main");
            try (RemoteClient client = RemoteClient.connect(endpoint)) {
                child.awaitStdout("MAIN-RAN");
                ProtocolMessage.Result result = submitUntilTerminal(client, "count := 41");
                assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, result.status(),
                        child.allStderr(2));
            }
            assertEquals(41, child.waitFor(60), child.allStderr(2));
        } finally {
            child.destroy();
        }
    }

    @Test
    void activationRejectsNonAttachableDebugArtifacts() throws Exception {
        // A debug-capable NORMAL publication carries the source context but
        // no attachable hooks; activation must fail with a structured error.
        CompileResult result = LyraCompiler.compile(CompileRequest.builder()
                .source("normal.lyra", SEVEN_MAIN).debugCapable(true).build());
        if (!(result instanceof CompileResult.Success success)) {
            throw new AssertionError("compile failed: " + result);
        }
        Path jar = temp.resolve("normal-debug.jar");
        success.artifact().writeJar(jar,
                io.mindspice.lyra.compiler.api.JarMode.BUNDLED_JAR);
        Child child = start(java(), "-Dlyra.repl.enabled=true",
                "-Xverify:all", "-jar", jar.toString());
        assertEquals(2, child.waitFor(60));
        assertTrue(child.allStderr(2).contains("attachable"), child.allStderr(2));
    }

    @Test
    void explicitLayoutKeepsTheArtifactLocationOutOfProgramArguments() throws Exception {
        CompiledArtifact compiled = compileRepl("args.lyra", ARGS_MAIN);
        Path classes = temp.resolve("debug-classes");
        compiled.writeClasses(classes);
        String classpath = join(runtimeCodeSource(), compilerCodeSource(),
                replCodeSource(), classes);
        // args[0] is the documented artifact location; only the remaining
        // tokens are main's program arguments.
        Child child = start(java(), "-Xverify:all", "-cp", classpath,
                "io.mindspice.lyra.repl.ReplLauncher", classes.toString(), "x", "y");
        assertEquals(2, child.waitFor(60), child.allStderr(2));

        // The same explicit layout activates without mistaking the repl
        // distribution for the app artifact and keeps argument separation.
        CompiledArtifact spin = compileRepl("spin.lyra", SPIN_SOURCE);
        Path spinClasses = temp.resolve("spin-classes");
        spin.writeClasses(spinClasses);
        String spinClasspath = join(runtimeCodeSource(), compilerCodeSource(),
                replCodeSource(), spinClasses);
        Child activated = start(java(), "-Dlyra.repl.enabled=true", "-Xverify:all",
                "-cp", spinClasspath, "io.mindspice.lyra.repl.ReplLauncher",
                spinClasses.toString(), "only-program-argument");
        try {
            RemoteEndpoint endpoint = awaitEndpoint(activated);
            try (RemoteClient client = RemoteClient.connect(endpoint)) {
                ProtocolMessage.Result result = submitUntilTerminal(client, "count := 8");
                assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, result.status(),
                        activated.allStderr(2));
            }
            assertEquals(8, activated.waitFor(60), activated.allStderr(2));
        } finally {
            activated.destroy();
        }
    }

    @Test
    void invalidActivationControlsAndBindFailuresAreConfigurationErrors()
            throws Exception {
        Path jar = compileJar("app.lyra", SEVEN_MAIN);
        Child invalidPort = start(java(), "-Dlyra.repl.enabled=true",
                "-Dlyra.repl.port=notaport", "-jar", jar.toString());
        assertEquals(2, invalidPort.waitFor(60));
        assertTrue(invalidPort.allStderr(2).contains("activation controls"),
                invalidPort.allStderr(2));

        Child outOfRange = start(java(), "-Dlyra.repl.enabled=true",
                "-Dlyra.repl.port=65536", "-jar", jar.toString());
        assertEquals(2, outOfRange.waitFor(60));
        assertTrue(outOfRange.allStderr(2).contains("activation controls"),
                outOfRange.allStderr(2));

        try (ServerSocket occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            Child bindFailure = start(java(), "-Dlyra.repl.enabled=true",
                    "-Dlyra.repl.port=" + occupied.getLocalPort(), "-jar", jar.toString());
            assertEquals(2, bindFailure.waitFor(60));
            assertTrue(bindFailure.allStderr(2).contains("cannot start the REPL listener"),
                    bindFailure.allStderr(2));
        }
    }

    /* ---- helpers ---- */

    /** Compiles the way the CLI's compile --repl does, then writes a bundle. */
    private Path compileJar(String name, String source) throws IOException {
        Path jar = temp.resolve(name.replace(".lyra", ".jar"));
        CompiledArtifact compiled = compileRepl(name, source);
        compiled.writeJar(jar, io.mindspice.lyra.compiler.api.JarMode.BUNDLED_JAR);
        return jar;
    }

    private static CompiledArtifact compileRepl(String name, String source) {
        CompileResult result = LyraCompiler.compile(CompileRequest.builder()
                .source(name, source).attachable(true).debugCapable(true).build());
        if (!(result instanceof CompileResult.Success success)) {
            throw new AssertionError("compile failed: " + result);
        }
        return success.artifact();
    }

    private static ProtocolMessage.Result submitUntilTerminal(RemoteClient client,
                                                              String source)
            throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        ProtocolMessage.Result result = null;
        while (System.nanoTime() < deadline) {
            RemoteRequest request = client.submit(EvaluationSource.of("write.lyra", source));
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

    private RemoteEndpoint awaitEndpoint(Child child) throws Exception {
        String line = child.awaitStderr(30, value -> ENDPOINT.matcher(value).find());
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

    private Child start(String... command) throws IOException {
        Process process = new ProcessBuilder(command).directory(temp.toFile()).start();
        return new Child(process);
    }

    private static final class Child implements AutoCloseable {
        private final Process process;
        private final BlockingQueue<String> stdoutLines = new LinkedBlockingQueue<>();
        private final BlockingQueue<String> stderrLines = new LinkedBlockingQueue<>();
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        private Child(Process process) {
            this.process = process;
            Thread out = new Thread(() -> readLines(process.getInputStream(),
                    stdoutLines, stdout), "repl-launcher-it-stdout");
            Thread err = new Thread(() -> readLines(process.getErrorStream(),
                    stderrLines, stderr), "repl-launcher-it-stderr");
            out.setDaemon(true);
            err.setDaemon(true);
            out.start();
            err.start();
        }

        private static void readLines(InputStream input, BlockingQueue<String> lines,
                                      ByteArrayOutputStream capture) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    capture.write(line.getBytes(StandardCharsets.UTF_8));
                    capture.write('\n');
                    lines.add(line);
                }
            } catch (IOException ignored) {
                // Process teardown; captured output already read is enough.
            }
        }

        int waitFor(int seconds) throws Exception {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("subprocess timed out: stderr=" + stderr());
            }
            return process.exitValue();
        }

        List<String> stdoutLines() {
            return new ArrayList<>(stdoutLines);
        }

        String allStderr(int seconds) {
            try {
                Thread.sleep(seconds * 1000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return stderr();
        }

        private String stderr() {
            return stderr.toString(StandardCharsets.UTF_8);
        }

        void awaitStdout(String prefix) throws Exception {
            await(stdoutLines, 30, value -> value.contains(prefix));
        }

        String awaitStderr(int seconds, java.util.function.Predicate<String> matches)
                throws Exception {
            return await(stderrLines, seconds, matches);
        }

        private static String await(BlockingQueue<String> lines, int seconds,
                                    java.util.function.Predicate<String> matches)
                throws Exception {
            long deadline = System.nanoTime() + seconds * 1_000_000_000L;
            ArrayList<String> seen = new ArrayList<>();
            while (System.nanoTime() < deadline) {
                String line = lines.poll(100, TimeUnit.MILLISECONDS);
                if (line != null) {
                    seen.add(line);
                    if (matches.test(line)) {
                        return line;
                    }
                }
            }
            throw new AssertionError("expected output did not appear; saw: " + seen);
        }

        void destroy() {
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            destroy();
        }
    }

    private static Path runtimeCodeSource() {
        return minimalCodeSource(io.mindspice.lyra.runtime.LyraRuntime.class,
                LyraCompiler.class);
    }

    private static Path compilerCodeSource() {
        return minimalCodeSource(LyraCompiler.class, LyraCli.class);
    }

    private static Path replCodeSource() {
        return minimalCodeSource(io.mindspice.lyra.repl.LyraSession.class,
                LyraCompiler.class);
    }

    private static Path minimalCodeSource(Class<?> anchor, Class<?> excludedMarker) {
        String entry = anchor.getName().replace('.', '/') + ".class";
        ArrayList<java.net.URL> candidates = new ArrayList<>();
        try {
            java.util.Enumeration<java.net.URL> resources =
                    ClassLoader.getSystemResources(entry);
            while (resources.hasMoreElements()) {
                candidates.add(resources.nextElement());
            }
            for (java.net.URL resource : candidates) {
                Path location = fileLocation(resource, entry);
                if (location == null || !containsClass(location, anchor)) {
                    continue;
                }
                if (excludedMarker != null && containsClass(location, excludedMarker)) {
                    continue;
                }
                return location;
            }
            throw new AssertionError("cannot resolve the minimal production location for "
                    + anchor.getName() + " among " + candidates);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static Path fileLocation(java.net.URL resource, String entry) {
        try {
            String spelling = resource.toString();
            if ("jar".equals(resource.getProtocol())) {
                return Path.of(java.net.URI.create(spelling.substring(
                        "jar:".length(), spelling.indexOf("!/")))).toAbsolutePath();
            }
            if ("file".equals(resource.getProtocol())) {
                Path location = Path.of(resource.toURI());
                for (int segments = entry.split("/").length; segments > 0; segments--) {
                    location = location.getParent();
                    if (location == null) {
                        return null;
                    }
                }
                return location.toAbsolutePath();
            }
            return null;
        } catch (Exception failure) {
            return null;
        }
    }

    private static boolean containsClass(Path location, Class<?> type) throws Exception {
        String entry = type.getName().replace('.', '/') + ".class";
        if (Files.isDirectory(location)) {
            return Files.isRegularFile(location.resolve(entry));
        }
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(location.toFile())) {
            return jar.getJarEntry(entry) != null;
        }
    }

    private static String join(Path... paths) {
        ArrayList<String> parts = new ArrayList<>();
        for (Path path : paths) {
            parts.add(path.toString());
        }
        return String.join(java.io.File.pathSeparator, parts);
    }

    private static String java() {
        return JLinePtyTest.javaCommand();
    }
}
