package io.mindspice.lyra.cli;

import io.mindspice.lyra.repl.ConsoleSession;
import io.mindspice.lyra.repl.EvaluationRequest;
import io.mindspice.lyra.repl.EvaluationResult;
import io.mindspice.lyra.repl.SessionRevision;
import io.mindspice.lyra.repl.remote.OwnerDispatcher;
import io.mindspice.lyra.repl.remote.RemoteCancellation;
import io.mindspice.lyra.repl.remote.RemoteQuery;
import io.mindspice.lyra.repl.remote.RemoteServer;
import io.mindspice.lyra.repl.remote.RemoteServerOptions;
import io.mindspice.lyra.repl.remote.RemoteSessionAdapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Credential-free CLI attach: the v2 endpoint takes no token file, rejects
 * non-loopback and zero ports, and drives the plain console over the real
 * remote transport without forwarding host streams.
 */
class AttachCliTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void attachRequiresExplicitEndpointAndAcceptsNoTokenOptions() {
        for (String[] arguments : List.of(
                new String[] {"attach"},
                new String[] {"attach", "127.0.0.1:0"},
                new String[] {"attach", "8.8.8.8:1"},
                new String[] {"attach", "127.0.0.1:1", "--token-file", "token"},
                new String[] {"attach", "127.0.0.1:1", "--credential-file", "x"})) {
            Invocation result = invoke(arguments, "");
            assertEquals(2, result.status(), String.join(" ", arguments));
            assertTrue(result.stderr().contains(LyraCli.USAGE_TEXT), result.stderr());
        }
    }

    @Test
    void attachHasNoCredentialPathAnywhereInTheCommandSurface() {
        String help = invoke(new String[] {"--help"}, "").stdout();
        assertTrue(help.contains("attach ENDPOINT"), help);
        assertFalse(help.contains("token"), help);
        assertFalse(help.contains("credential"), help);
    }

    @Test
    void attachReportsControllerBusyHandshakeErrors() throws Exception {
        PumpOwner owner = new PumpOwner();
        TestAdapter adapter = new TestAdapter(owner.ownerThread);
        try (RemoteServer server = server(adapter, owner);
             RemoteHolder ignored = RemoteHolder.connect(server)) {
            Invocation result = invoke(new String[] {
                    "attach", server.endpoint().address().toString()}, "");
            assertEquals(1, result.status());
            assertTrue(result.stderr().contains("CONTROLLER_BUSY"), result.stderr());
        }
    }

    @Test
    void attachUsesPlainInjectedStreamsAndMapsEvaluationQueriesResetAndHistory()
            throws Exception {
        PumpOwner owner = new PumpOwner();
        TestAdapter adapter = new TestAdapter(owner.ownerThread);
        try (RemoteServer server = server(adapter, owner)) {
            Invocation result = invokeWhilePumping(server, owner,
                    new String[] {"attach", server.endpoint().address().toString()},
                    "source\n:bindings\n:reset\n:history\n:quit\n");
            assertEquals(0, result.status(), result.stderr());
            assertTrue(result.stdout().contains("answer :I32 @mut"), result.stdout());
            assertFalse(result.stdout().contains("remote stdout"), result.stdout());
            assertFalse(result.stdout().contains("remote stderr"), result.stdout());
            assertEquals("remote stdout\n", adapter.stdout.toString());
            assertEquals("remote stderr\n", adapter.stderr.toString());
            assertEquals(1, adapter.resets.get());
            assertEquals("", result.stderr());
        }
    }

    @Test
    void attachReportsUnavailableTypeAndReloadWithoutExecutingTypeSource() throws Exception {
        PumpOwner owner = new PumpOwner();
        TestAdapter adapter = new TestAdapter(owner.ownerThread);
        try (RemoteServer server = server(adapter, owner)) {
            Invocation result = invokeWhilePumping(server, owner,
                    new String[] {"attach", server.endpoint().address().toString()},
                    ":type let @pub notExecuted :I32 = 1\n:reload\n:quit\n");
            assertEquals(2, result.status());
            assertTrue(result.stderr().contains("LYR-REPL-TYPE-UNSUPPORTED"), result.stderr());
            assertTrue(result.stderr().contains("status=UNAVAILABLE"), result.stderr());
            assertTrue(result.stderr().contains("LYR-REPL-RELOAD-UNSUPPORTED"), result.stderr());
            assertEquals(0, adapter.evaluations.get());
        }
    }

    /** A direct v2 client holding the single controller slot. */
    private static final class RemoteHolder implements AutoCloseable {
        private final io.mindspice.lyra.repl.remote.RemoteClient client;

        private RemoteHolder(io.mindspice.lyra.repl.remote.RemoteClient client) {
            this.client = client;
        }

        static RemoteHolder connect(RemoteServer server) throws IOException {
            return new RemoteHolder(io.mindspice.lyra.repl.remote.RemoteClient
                    .connect(server.endpoint()));
        }

        @Override
        public void close() {
            client.close();
        }
    }

    private Invocation invoke(String[] arguments, String input) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int status = LyraCli.execute(arguments,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output, error);
        return new Invocation(status, output.toString(StandardCharsets.UTF_8),
                error.toString(StandardCharsets.UTF_8));
    }

    private Invocation invokeWhilePumping(RemoteServer server, PumpOwner owner,
                                          String[] arguments, String input) throws Exception {
        TrackingInput source = new TrackingInput(input);
        TrackingOutput output = new TrackingOutput();
        TrackingOutput error = new TrackingOutput();
        AtomicInteger status = new AtomicInteger(-1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread cli = new Thread(() -> {
            try {
                status.set(LyraCli.execute(arguments, source, output, error));
            } catch (Throwable problem) {
                failure.set(problem);
            }
        }, "lyra-attach-cli-test");
        cli.start();
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (cli.isAlive() && System.nanoTime() < deadline) {
            server.poll();
            Thread.sleep(2);
        }
        cli.join(1_000);
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        assertFalse(cli.isAlive(), "attach did not finish");
        assertFalse(source.closed);
        assertFalse(output.closed);
        assertFalse(error.closed);
        return new Invocation(status.get(), output.toString(StandardCharsets.UTF_8),
                error.toString(StandardCharsets.UTF_8));
    }

    private RemoteServer server(RemoteSessionAdapter adapter, PumpOwner owner) throws IOException {
        return RemoteServer.open(adapter, owner, RemoteServerOptions.defaults());
    }

    private record Invocation(int status, String stdout, String stderr) {
    }

    private static final class TestAdapter implements RemoteSessionAdapter {
        private final UUID sessionId = UUID.randomUUID();
        private final Thread ownerThread;
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        final AtomicInteger evaluations = new AtomicInteger();
        final AtomicInteger resets = new AtomicInteger();
        private SessionRevision revision = SessionRevision.initial();

        TestAdapter(Thread ownerThread) {
            this.ownerThread = ownerThread;
        }

        @Override
        public UUID sessionId() {
            return sessionId;
        }

        @Override
        public SessionRevision revision() {
            return revision;
        }

        @Override
        public EvaluationResult evaluate(EvaluationRequest request,
                                         RemoteCancellation cancellation) {
            assertEquals(ownerThread, Thread.currentThread());
            stdout.writeBytes("remote stdout\n".getBytes(StandardCharsets.UTF_8));
            stderr.writeBytes("remote stderr\n".getBytes(StandardCharsets.UTF_8));
            evaluations.incrementAndGet();
            revision = revision.next();
            return new EvaluationResult.Success(request, revision, Optional.empty(), List.of());
        }

        @Override
        public void reset() {
            assertEquals(ownerThread, Thread.currentThread());
            resets.incrementAndGet();
        }

        @Override
        public RemoteQuery.Result query(RemoteQuery query) {
            assertEquals(ownerThread, Thread.currentThread());
            return query.kind() == RemoteQuery.Kind.TYPE
                    ? RemoteQuery.Result.unavailable("type query unavailable")
                    : new RemoteQuery.Result(RemoteQuery.Status.OK,
                    List.of(new io.mindspice.lyra.repl.remote.RemoteBinding(
                            "answer", "I32", "PUBLIC", true)),
                    Optional.empty(), Optional.empty());
        }
    }

    private static final class PumpOwner implements OwnerDispatcher {
        final Thread ownerThread = Thread.currentThread();
        private final ArrayDeque<Task> tasks = new ArrayDeque<>();

        @Override
        public boolean isOwnerThread() {
            return Thread.currentThread() == ownerThread;
        }

        @Override
        public synchronized Dispatch dispatch(Runnable operation) {
            if (!tasks.isEmpty()) {
                throw new IllegalStateException("one pending operation only");
            }
            Task task = new Task(operation);
            tasks.add(task);
            return task;
        }

        @Override
        public boolean poll() {
            if (!isOwnerThread()) {
                throw new IllegalStateException("wrong owner thread");
            }
            Task task;
            synchronized (this) {
                task = tasks.poll();
            }
            if (task == null) {
                return false;
            }
            task.state = DispatchState.RUNNING;
            try {
                task.operation.run();
                task.state = DispatchState.COMPLETED;
            } catch (RuntimeException failure) {
                task.state = DispatchState.FAILED;
                throw failure;
            }
            return true;
        }

        private final class Task implements Dispatch {
            private final Runnable operation;
            private volatile DispatchState state = DispatchState.PENDING;

            private Task(Runnable operation) {
                this.operation = operation;
            }

            @Override
            public DispatchState state() {
                return state;
            }

            @Override
            public synchronized boolean cancel() {
                if (state == DispatchState.PENDING) {
                    state = DispatchState.CANCELLED;
                    return tasks.remove(this);
                }
                return false;
            }
        }
    }

    private static final class TrackingInput extends ByteArrayInputStream {
        private boolean closed;

        TrackingInput(String value) {
            super(value.getBytes(StandardCharsets.UTF_8));
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
