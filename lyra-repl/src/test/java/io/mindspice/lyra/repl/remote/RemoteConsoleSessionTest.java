package io.mindspice.lyra.repl.remote;

import io.mindspice.lyra.repl.ConsoleSession;
import io.mindspice.lyra.repl.EvaluationId;
import io.mindspice.lyra.repl.EvaluationRequest;
import io.mindspice.lyra.repl.EvaluationResult;
import io.mindspice.lyra.repl.EvaluationSource;
import io.mindspice.lyra.repl.SessionRevision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.io.IOException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteConsoleSessionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void evaluationQueriesResetAndStreamsStayOnTheirOwnSides() throws Exception {
        RemoteServerTest.QueueOwner owner = new RemoteServerTest.QueueOwner();
        ByteArrayOutputStream remoteStdout = new ByteArrayOutputStream();
        ByteArrayOutputStream remoteStderr = new ByteArrayOutputStream();
        TestAdapter adapter = new TestAdapter(owner.ownerThread, remoteStdout, remoteStderr);
        try (RemoteServer server = server(adapter, owner);
             RemoteConsoleSession attached = RemoteConsoleSession.connect(server.endpoint())) {
            CompletableFuture<ConsoleSession.Evaluation> evaluation = CompletableFuture.supplyAsync(
                    () -> attached.evaluate(EvaluationSource.of("attached.lyra", "source")));
            waitUntil(owner::hasPending);
            assertTrue(owner.poll());
            assertEquals(ConsoleSession.EvaluationStatus.SUCCESS,
                    evaluation.get(5, TimeUnit.SECONDS).status());

            CompletableFuture<ConsoleSession.Query> bindings = CompletableFuture.supplyAsync(
                    () -> attached.query(ConsoleSession.QueryRequest.bindings()));
            waitUntil(owner::hasPending);
            assertTrue(owner.poll());
            ConsoleSession.Query query = bindings.get(5, TimeUnit.SECONDS);
            assertEquals(ConsoleSession.QueryStatus.OK, query.status());
            assertEquals("answer", query.bindings().getFirst().name());

            CompletableFuture<ConsoleSession.Control> reset = CompletableFuture.supplyAsync(
                    attached::reset);
            waitUntil(owner::hasPending);
            assertTrue(owner.poll());
            assertEquals(ConsoleSession.ControlStatus.OK,
                    reset.get(5, TimeUnit.SECONDS).status());
            assertEquals(1, adapter.resets.get());
            assertEquals("remote stdout\n", remoteStdout.toString());
            assertEquals("remote stderr\n", remoteStderr.toString());
        }
    }

    @Test
    void retainedStatusSurvivesExplicitReconnectWithoutResubmittingSource() throws Exception {
        RemoteServerTest.QueueOwner owner = new RemoteServerTest.QueueOwner();
        TestAdapter adapter = new TestAdapter(owner.ownerThread,
                new ByteArrayOutputStream(), new ByteArrayOutputStream());
        try (RemoteServer server = server(adapter, owner);
             RemoteConsoleSession attached = RemoteConsoleSession.connect(server.endpoint())) {
            CompletableFuture<ConsoleSession.Evaluation> evaluation = CompletableFuture.supplyAsync(
                    () -> attached.evaluate(EvaluationSource.of("reconnect.lyra", "source")));
            waitUntil(owner::hasPending);
            assertTrue(owner.poll());
            ConsoleSession.Evaluation result = evaluation.get(5, TimeUnit.SECONDS);
            assertEquals(ConsoleSession.EvaluationStatus.SUCCESS, result.status());
            assertEquals(new SessionRevision(1), result.revision());

            reconnect(attached, server);
            RemoteConsoleSession.RequestStatus status = attached.status(result.evaluationId());
            assertEquals(RemoteConsoleSession.RequestState.SUCCESS, status.state());
            assertEquals(new SessionRevision(1), status.revision());
            assertEquals(1, adapter.evaluations.get());
        }
    }

    @Test
    void activeCancellationUsesRequestIdentityAndDoesNotRunSource() throws Exception {
        RemoteServerTest.QueueOwner owner = new RemoteServerTest.QueueOwner();
        BlockingAdapter adapter = new BlockingAdapter(owner.ownerThread);
        try (RemoteServer server = server(adapter, owner);
             RemoteConsoleSession attached = RemoteConsoleSession.connect(server.endpoint())) {
            CompletableFuture<ConsoleSession.Evaluation> evaluation = CompletableFuture.supplyAsync(
                    () -> attached.evaluate(EvaluationSource.of("cancel.lyra", "not replayed")));
            waitUntil(owner::hasPending);
            CompletableFuture<ConsoleSession.Control> cancel = CompletableFuture.supplyAsync(() -> {
                waitUntilUnchecked(() -> attached.activeEvaluationId().isPresent());
                return attached.cancelActive();
            });

            assertTrue(owner.poll());
            assertEquals(ConsoleSession.ControlStatus.REQUESTED,
                    cancel.get(5, TimeUnit.SECONDS).status());
            assertEquals(ConsoleSession.EvaluationStatus.CANCELLED,
                    evaluation.get(5, TimeUnit.SECONDS).status());
            assertEquals(1, adapter.cancelRequests.get());
            assertEquals(0, adapter.evaluations.get());
        }
    }

    @Test
    void disconnectMapsPendingEvaluationToCancellationWithoutReset() throws Exception {
        RemoteServerTest.QueueOwner owner = new RemoteServerTest.QueueOwner();
        BlockingAdapter adapter = new BlockingAdapter(owner.ownerThread);
        RemoteConsoleSession attached;
        RemoteServer server = server(adapter, owner);
        attached = RemoteConsoleSession.connect(server.endpoint());
        try {
            CompletableFuture<ConsoleSession.Evaluation> evaluation = CompletableFuture.supplyAsync(
                    () -> attached.evaluate(EvaluationSource.of("disconnect.lyra", "not replayed")));
            waitUntil(owner::hasPending);
            attached.client().close();
            assertEquals(ConsoleSession.EvaluationStatus.CANCELLED,
                    evaluation.get(5, TimeUnit.SECONDS).status());
            waitUntil(() -> server.controllerCount() == 0);
            assertTrue(server.isOpen());
            assertEquals(0, adapter.resets.get());
            assertEquals(0, adapter.evaluations.get());
        } finally {
            attached.close();
            server.close();
        }
    }

    private RemoteServer server(RemoteSessionAdapter adapter, OwnerDispatcher owner)
            throws IOException {
        return RemoteServer.open(adapter, owner);
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean());
    }

    private static void reconnect(RemoteConsoleSession attached, RemoteServer server)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        IOException last = null;
        while (System.nanoTime() < deadline) {
            assertTrue(server.isOpen());
            try {
                attached.reconnect();
                return;
            } catch (IOException failure) {
                last = failure;
                Thread.sleep(5);
            }
        }
        throw new AssertionError("explicit reconnect did not acquire the controller lease", last);
    }

    private static void waitUntilUnchecked(java.util.function.BooleanSupplier condition) {
        try {
            waitUntil(condition);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static class TestAdapter implements RemoteSessionAdapter {
        private final UUID sessionId = UUID.randomUUID();
        private final Thread ownerThread;
        private final ByteArrayOutputStream stdout;
        private final ByteArrayOutputStream stderr;
        private SessionRevision revision = SessionRevision.initial();
        final AtomicInteger resets = new AtomicInteger();
        final AtomicInteger evaluations = new AtomicInteger();

        TestAdapter(Thread ownerThread, ByteArrayOutputStream stdout,
                    ByteArrayOutputStream stderr) {
            this.ownerThread = ownerThread;
            this.stdout = stdout;
            this.stderr = stderr;
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
            stdout.writeBytes("remote stdout\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            stderr.writeBytes("remote stderr\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
            if (query.kind() == RemoteQuery.Kind.TYPE) {
                return RemoteQuery.Result.unavailable("type query unavailable");
            }
            return new RemoteQuery.Result(RemoteQuery.Status.OK,
                    List.of(new RemoteBinding("answer", "I32", "PUBLIC", true)),
                    Optional.empty(), Optional.empty());
        }
    }

    private static final class BlockingAdapter extends TestAdapter {
        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicInteger cancelRequests = new AtomicInteger();

        BlockingAdapter(Thread ownerThread) {
            super(ownerThread, new ByteArrayOutputStream(), new ByteArrayOutputStream());
        }

        @Override
        public EvaluationResult evaluate(EvaluationRequest request,
                                         RemoteCancellation cancellation) {
            entered.countDown();
            while (!cancellation.isRequested()) {
                Thread.onSpinWait();
            }
            return new EvaluationResult.Cancelled(request, revision(),
                    io.mindspice.lyra.repl.Cancellation.observed(request.evaluationId()));
        }

        @Override
        public boolean cancel(EvaluationId evaluationId) {
            cancelRequests.incrementAndGet();
            return true;
        }
    }
}
