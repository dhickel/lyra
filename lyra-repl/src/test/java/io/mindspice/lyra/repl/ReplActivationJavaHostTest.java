package io.mindspice.lyra.repl;

import io.mindspice.lyra.repl.remote.LoopbackEndpoint;
import io.mindspice.lyra.repl.remote.ProtocolMessage;
import io.mindspice.lyra.repl.remote.RemoteClient;
import io.mindspice.lyra.repl.remote.RemoteEndpoint;
import io.mindspice.lyra.repl.remote.RemoteRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 12 forked public Java host evidence: the shipped activation
 * composition serves a genuinely running attachable main, closes the
 * service without closing the externally owned root, reopens attachment in
 * the retained domain, retires queued control work at shutdown, and leaves
 * no leaked peers or executors.
 */
@Timeout(180)
final class ReplActivationJavaHostTest {
    private static final Pattern ENDPOINT = Pattern.compile(
            "lyra: repl listener: ([^ ]+):(\\d+) \\(no authentication");

    @TempDir
    Path temp;

    @Test
    void ownedRunSurvivalReopenAndCleanup() throws Exception {
        Child child = start("ownedRunSurvivalAndReopen", null);
        try {
            RemoteEndpoint endpoint = awaitEndpoint(child);
            child.awaitLine("REGISTERED");
            try (RemoteClient client = RemoteClient.connect(endpoint)) {
                ProtocolMessage.Result result = submitUntilTerminal(client, "count := 42");
                assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, result.status(),
                        child.lines());
            }
            child.awaitLine("MAIN-DONE 42");
            child.awaitLine("SERVICE-CLOSED");
            child.awaitLine("ROOT-OPEN true");
            // The reopened service shares the retained root domain; the
            // parent drives it through the public endpoint again.
            String reopenLine = child.awaitLineMatching(
                    value -> value.startsWith("REOPEN "));
            RemoteEndpoint reopened = reopenEndpoint(reopenLine);
            child.awaitLine("REOPEN-REGISTERED");
            try (RemoteClient client = RemoteClient.connect(reopened)) {
                ProtocolMessage.Result result = submitUntilTerminal(client, "count := 5");
                assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, result.status(),
                        child.lines());
            }
            child.awaitLine("REOPEN-SERVICED");
            child.awaitLine("DONE");
            assertEquals(0, child.waitFor(90), child.lines());
        } finally {
            child.destroy();
        }
    }

    @Test
    void queuedControlWorkIsRetiredAtShutdownWithoutClosingTheRoot() throws Exception {
        Path signal = temp.resolve("submitted.signal");
        Child child = start("queuedRequestRetiredAtShutdown", signal.toString());
        try {
            RemoteEndpoint endpoint = awaitEndpoint(child);
            child.awaitLine("REGISTERED");
            ProtocolMessage.RemoteStatus terminal;
            try (RemoteClient client = RemoteClient.connect(endpoint)) {
                RemoteRequest request = client.submit(EvaluationSource.of(
                        "queued.lyra", "count := 1"));
                Files.writeString(signal, "submitted", StandardCharsets.UTF_8);
                // The owner never polls; service close retires the queued
                // control request with an explicit terminal status.
                try {
                    terminal = request.result().get(15, TimeUnit.SECONDS).status();
                } catch (java.util.concurrent.TimeoutException timeout) {
                    terminal = request.status();
                }
            }
            child.awaitLine("CLOSED-WITH-QUEUED");
            child.awaitLine("ROOT-OPEN true");
            assertEquals(ProtocolMessage.RemoteStatus.CANCELLED, terminal,
                    child.lines());
            child.awaitLine("DONE");
            assertEquals(0, child.waitFor(90), child.lines());
        } finally {
            child.destroy();
        }
    }

    /* ---- harness ---- */

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

    private static RemoteEndpoint endpointOf(String line) throws Exception {
        Matcher matcher = ENDPOINT.matcher(line);
        assertTrue(matcher.find(), line);
        return new RemoteEndpoint(LoopbackEndpoint.of(matcher.group(1),
                Integer.parseInt(matcher.group(2))));
    }

    private static RemoteEndpoint reopenEndpoint(String line) throws Exception {
        Matcher matcher = Pattern.compile("REOPEN ([^ ]+):(\\d+)").matcher(line);
        assertTrue(matcher.find(), line);
        return new RemoteEndpoint(LoopbackEndpoint.of(matcher.group(1),
                Integer.parseInt(matcher.group(2))));
    }

    private RemoteEndpoint awaitEndpoint(Child child) throws Exception {
        return endpointOf(child.awaitLineMatching(
                value -> ENDPOINT.matcher(value).find()));
    }

    private Child start(String scenario, String signal) throws Exception {
        ArrayList<String> command = new ArrayList<>();
        command.add(java());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ReplActivationHostDriver.class.getName());
        command.add(scenario);
        if (signal != null) {
            command.add(signal);
        }
        Process process = new ProcessBuilder(command).directory(temp.toFile()).start();
        return new Child(process);
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static final class Child implements AutoCloseable {
        private final Process process;
        private final BlockingQueue<String> output = new LinkedBlockingQueue<>();
        private final StringBuilder captured = new StringBuilder();

        private Child(Process process) {
            this.process = process;
            Thread reader = new Thread(() -> read(process.getInputStream()),
                    "repl-activation-host-test");
            reader.setDaemon(true);
            reader.start();
        }

        private void read(InputStream input) {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(
                    input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    synchronized (captured) {
                        captured.append(line).append('\n');
                    }
                    output.add(line);
                }
            } catch (Exception ignored) {
                // Process teardown.
            }
        }

        String lines() {
            synchronized (captured) {
                return captured.toString();
            }
        }

        void awaitLine(String expected) throws Exception {
            awaitLineMatching(expected::equals);
        }

        String awaitLineMatching(java.util.function.Predicate<String> matches)
                throws Exception {
            long deadline = System.nanoTime() + 60_000_000_000L;
            ArrayList<String> seen = new ArrayList<>();
            while (System.nanoTime() < deadline) {
                String line = output.poll(100, TimeUnit.MILLISECONDS);
                if (line != null) {
                    seen.add(line);
                    if (matches.test(line)) {
                        return line;
                    }
                }
            }
            throw new AssertionError("expected marker did not appear; saw: " + seen);
        }

        int waitFor(int seconds) throws Exception {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("driver did not terminate: " + lines());
            }
            return process.exitValue();
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
}
