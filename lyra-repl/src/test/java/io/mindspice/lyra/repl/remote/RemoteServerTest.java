package io.mindspice.lyra.repl.remote;

import io.mindspice.lyra.repl.EvaluationId;
import io.mindspice.lyra.repl.EvaluationRequest;
import io.mindspice.lyra.repl.EvaluationResult;
import io.mindspice.lyra.repl.EvaluationSource;
import io.mindspice.lyra.repl.SessionRevision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Credential-free v2 server behavior: owner marshaling, controller
 * admission, duplicate identity, sequence and mutation-sequence freshness,
 * reconnect without replay, disconnect cancellation and bounded retention.
 */
class RemoteServerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void serverMarshalsEvaluationToOwnerAndSupportsControlQueries() throws Exception {
        QueueOwner owner = new QueueOwner();
        FakeSession session = new FakeSession(owner.ownerThread);
        RemoteServerOptions options = RemoteServerOptions.builder()
                .handshakeTimeout(Duration.ofSeconds(2))
                .build();
        try (RemoteServer server = RemoteServer.open(session, owner, options);
             RemoteClient client = RemoteClient.connect(server.endpoint())) {
            assertEquals(1, server.controllerCount());
            assertTrue(server.endpoint().sessionId().isPresent());
            RemoteRequest request = client.submit(EvaluationSource.of("one.lyra", ""));
            assertFalse(request.result().isDone());
            assertEquals(0, session.evaluations.get());

            pumpUntil(server, request.result()::isDone);
            ProtocolMessage.Result result = request.result().get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, result.status());
            assertEquals(owner.ownerThread, session.evaluationThread.get());
            assertEquals(new SessionRevision(1), client.revision());
            assertEquals(1, client.mutationSequence());

            ProtocolMessage.QueryResult query = client.queryRequest(request.requestId())
                    .get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.QueryStatus.OK, query.status());
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                    query.request().orElseThrow().status());
            assertTrue(query.terminalResult().isPresent());

            // Reset is owner-confined and cannot execute on the reader thread.
            java.util.concurrent.CompletableFuture<ProtocolMessage.ResetResult> resetFuture =
                    client.reset(1);
            assertFalse(resetFuture.isDone());
            pumpUntil(server, resetFuture::isDone);
            ProtocolMessage.ResetResult reset = resetFuture.get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.ControlStatus.OK, reset.status());
            assertEquals(1, session.resets.get());
            // Reset keeps the local revision contract and advances the
            // mutation sequence so stale console metadata is detectable.
            assertEquals(1, reset.revision());
            assertEquals(2, reset.mutationSequence());
        }
    }

    @Test
    void staleRevisionOrMutationSequenceIsRejectedBeforeOwnerWork() throws Exception {
        QueueOwner owner = new QueueOwner();
        FakeSession session = new FakeSession(owner.ownerThread);
        try (RemoteServer server = RemoteServer.open(session, owner);
             RawConnection connection = RawConnection.open(server.endpoint())) {
            // The client believes the session is at revision 1 mutation 0;
            // the server is at revision 0 mutation 0.
            ProtocolMessage.EvaluateRequest stale = new ProtocolMessage.EvaluateRequest(
                    UUID.randomUUID(), 1, new SessionRevision(1), 0,
                    EvaluationSource.of("stale.lyra", ""));
            connection.send(stale);
            assertEquals(ProtocolMessage.RemoteStatus.REVISION_CONFLICT,
                    readResult(connection).status());
            assertEquals(0, session.evaluations.get());

            // Reset keeps the local revision contract and advances the
            // mutation sequence. It is owner-dispatched, so the owner pump
            // runs it before the response can be read.
            connection.send(new ProtocolMessage.ResetRequest(
                    UUID.randomUUID(), 0, 0));
            waitUntil(owner::hasPending);
            assertTrue(server.poll());
            ProtocolMessage.ResetResult reset = assertInstanceOf(
                    ProtocolMessage.ResetResult.class, connection.read());
            assertEquals(ProtocolMessage.ControlStatus.OK, reset.status());
            assertEquals(0, reset.revision());
            assertEquals(1, reset.mutationSequence());

            // A matching revision with a stale mutation sequence (the
            // reset state) is rejected without owner work.
            ProtocolMessage.EvaluateRequest staleMutation = new ProtocolMessage.EvaluateRequest(
                    UUID.randomUUID(), 2, new SessionRevision(0), 0,
                    EvaluationSource.of("stale-mutation.lyra", ""));
            connection.send(staleMutation);
            assertEquals(ProtocolMessage.RemoteStatus.REVISION_CONFLICT,
                    readResult(connection).status());
            assertEquals(0, session.evaluations.get());

            // Queries from a client that has not observed the reset cannot
            // apply stale console metadata; the stale response is immediate
            // and never reaches the owner.
            connection.send(ProtocolMessage.QueryRequest.bindings(UUID.randomUUID(), 0, 0));
            ProtocolMessage.QueryResult staleQuery = assertInstanceOf(
                    ProtocolMessage.QueryResult.class, connection.read());
            assertEquals(ProtocolMessage.QueryStatus.STALE, staleQuery.status());
            assertFalse(owner.hasPending());
        }
    }

    @Test
    void loadAndReloadRouteThroughTheOwnerExactlyOnce() throws Exception {
        QueueOwner owner = new QueueOwner();
        FakeSession session = new FakeSession(owner.ownerThread);
        try (RemoteServer server = RemoteServer.open(session, owner);
             RemoteClient client = RemoteClient.connect(server.endpoint())) {
            RemoteRequest load = client.load("/server-side/load.lyra");
            pumpUntil(server, load.result()::isDone);
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                    load.result().get(5, TimeUnit.SECONDS).status());
            assertEquals(1, session.loads.get());
            assertEquals(owner.ownerThread, session.loadThread.get());
            assertEquals(List.of("/server-side/load.lyra"), session.loadPaths);

            RemoteRequest reload = client.reload("m->answer");
            pumpUntil(server, reload.result()::isDone);
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                    reload.result().get(5, TimeUnit.SECONDS).status());
            assertEquals(1, session.reloads.get());
            assertEquals(List.of("m->answer"), session.reloadTargets);
        }
    }

    @Test
    void cancellationCanUseTheCapacityReservedByItsTarget() throws Exception {
        QueueOwner owner = new QueueOwner();
        FakeSession session = new FakeSession(owner.ownerThread);
        try (RemoteServer server = RemoteServer.open(session, owner);
             RemoteClient client = RemoteClient.connect(server.endpoint(),
                     RemoteClientOptions.builder().maxOutstandingOperations(1).build())) {
            RemoteRequest request = client.submit(EvaluationSource.of("cancel-capacity.lyra", ""));
            waitUntil(owner::hasPending);

            assertThrows(IllegalStateException.class, () -> client.reset(0));
            ProtocolMessage.CancelResult cancel = request.cancel().get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.ControlStatus.REQUESTED, cancel.status());
            assertEquals(ProtocolMessage.RemoteStatus.CANCELLED,
                    request.result().get(5, TimeUnit.SECONDS).status());
        }
    }

    @Test
    void failedSubmissionAndReconnectReconcileToTheAuthoritativeSequence() throws Exception {
        QueueOwner owner = new QueueOwner();
        FakeSession session = new FakeSession(owner.ownerThread);
        try (RemoteServer server = RemoteServer.open(session, owner);
             RemoteClient client = RemoteClient.connect(server.endpoint(),
                     RemoteClientOptions.builder().maxFrameBytes(512).build())) {
            assertThrows(IllegalArgumentException.class,
                    () -> client.submit(EvaluationSource.of("too-large.lyra", "x".repeat(400))));
            assertThrows(IllegalArgumentException.class,
                    () -> client.load("x".repeat(RemoteProtocol.MAX_LOAD_PATH_CHARACTERS + 1)));

            client.reconnect();
            RemoteRequest request = client.submit(EvaluationSource.of("after-reconnect.lyra", ""));
            assertEquals(1, request.sequence());
            pumpUntil(server, request.result()::isDone);
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                    request.result().get(5, TimeUnit.SECONDS).status());
        }
    }

    @Test
    void oversizedQueryDropsNestedTerminalResultBeforeTheFinalFrameCheck() throws Exception {
        QueueOwner owner = new QueueOwner();
        FakeSession session = new FakeSession(owner.ownerThread);
        RemoteServerOptions options = RemoteServerOptions.builder()
                .maxFrameBytes(300)
                .build();
        try (RemoteServer server = RemoteServer.open(session, owner, options)) {
            UUID requestId = UUID.randomUUID();
            ProtocolMessage.Result nested = new ProtocolMessage.Result(
                    requestId, 1, ProtocolMessage.RemoteStatus.SUCCESS, 0, 0, List.of(),
                    Optional.empty(), Optional.of("x".repeat(4_096)), Optional.empty());
            ProtocolMessage.QueryResult query = new ProtocolMessage.QueryResult(
                    RemoteProtocol.VERSION, UUID.randomUUID(), ProtocolMessage.QueryKind.REQUEST,
                    ProtocolMessage.QueryStatus.OK, 0, 0,
                    Optional.of(new ProtocolMessage.RequestSnapshot(requestId, 1,
                            ProtocolMessage.RemoteStatus.SUCCESS, 0,
                            Optional.of("x".repeat(4_096)))),
                    Optional.of(nested), List.of(), Optional.of("x".repeat(4_096)),
                    Optional.of("x".repeat(4_096)));
            java.lang.reflect.Method fit = RemoteServer.class.getDeclaredMethod(
                    "fitQueryResult", ProtocolMessage.QueryResult.class);
            fit.setAccessible(true);
            ProtocolMessage.QueryResult bounded = (ProtocolMessage.QueryResult) fit.invoke(
                    server, query);

            assertTrue(ProtocolCodec.encode(bounded).length <= options.maxFrameBytes());
            assertTrue(bounded.terminalResult().isEmpty());
            assertEquals(Optional.of(ProtocolMessage.RemoteStatus.SUCCESS),
                    bounded.terminalStatus());
        }
    }

    @Test
    void duplicateControllerAndReconnectDoNotReplaySource() throws Exception {
        QueueOwner owner = new QueueOwner();
        FakeSession session = new FakeSession(owner.ownerThread);
        RemoteServerOptions options = RemoteServerOptions.builder()
                .handshakeTimeout(Duration.ofSeconds(2))
                .maxRetainedResults(2)
                .build();
        try (RemoteServer server = RemoteServer.open(session, owner, options);
             RemoteClient first = RemoteClient.connect(server.endpoint())) {
            assertThrows(RemoteOperationException.class,
                    () -> RemoteClient.connect(server.endpoint()));

            RemoteRequest request = first.submit(EvaluationSource.of("one.lyra", ""));
            pumpUntil(server, request.result()::isDone);
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                    request.result().get(5, TimeUnit.SECONDS).status());
            assertEquals(1, session.evaluations.get());

            first.close();
            waitUntil(() -> server.controllerCount() == 0);
            try (RemoteClient reconnected = RemoteClient.connect(server.endpoint())) {
                ProtocolMessage.QueryResult retained = reconnected
                        .queryRequest(request.requestId()).get(5, TimeUnit.SECONDS);
                assertEquals(ProtocolMessage.QueryStatus.OK, retained.status());
                assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                        retained.request().orElseThrow().status());
                assertEquals(1, session.evaluations.get());
            }
        }
    }

    @Test
    void disconnectCancelsPendingWorkWithoutResubmission() throws Exception {
        QueueOwner owner = new QueueOwner();
        FakeSession session = new FakeSession(owner.ownerThread);
        try (RemoteServer server = RemoteServer.open(session, owner);
             RemoteClient client = RemoteClient.connect(server.endpoint())) {
            RemoteRequest request = client.submit(EvaluationSource.of("disconnect.lyra", ""));
            waitUntil(owner::hasPending);
            client.close();
            waitUntil(() -> server.controllerCount() == 0);
            try (RemoteClient reconnected = RemoteClient.connect(server.endpoint())) {
                ProtocolMessage.QueryResult status = reconnected
                        .queryRequest(request.requestId()).get(5, TimeUnit.SECONDS);
                assertEquals(ProtocolMessage.QueryStatus.OK, status.status());
                assertEquals(ProtocolMessage.RemoteStatus.CANCELLED,
                        status.request().orElseThrow().status());
                assertEquals(0, session.evaluations.get());
            }
        }
    }

    @Test
    void expiredQuerySettlesAReconnectedRetainedRequest() throws Exception {
        QueueOwner owner = new QueueOwner();
        FakeSession session = new FakeSession(owner.ownerThread);
        try (RemoteServer server = RemoteServer.open(session, owner,
                RemoteServerOptions.builder().maxRetainedResults(1).build());
             RemoteClient client = RemoteClient.connect(server.endpoint())) {
            RemoteRequest first = client.submit(EvaluationSource.of("expired-first.lyra", ""));
            waitUntil(owner::hasPending);

            try {
                client.reconnect();
            } catch (IOException reconnectRace) {
                waitUntil(() -> server.controllerCount() == 0);
                client.reconnect();
            }
            assertFalse(first.result().isDone());

            RemoteRequest second = client.submit(EvaluationSource.of("expired-second.lyra", ""),
                    SessionRevision.initial());
            pumpUntil(server, second.result()::isDone);
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                    second.result().get(5, TimeUnit.SECONDS).status());

            ProtocolMessage.QueryResult query = client.queryRequest(first.requestId())
                    .get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.QueryStatus.EXPIRED, query.status());
            ProtocolMessage.Result expired = first.result().get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.RemoteStatus.EXPIRED, expired.status());
            assertEquals(ProtocolMessage.RemoteStatus.EXPIRED, first.status());
            assertEquals(Optional.of("request is not retained"), expired.failureSummary());
            assertTrue(first.isTerminal());
        }
    }

    @Test
    void controllerLeaseIsHeldUntilDisconnectCleanupFinishes() throws Exception {
        QueueOwner owner = new QueueOwner();
        BlockingCancelSession session = new BlockingCancelSession(owner.ownerThread);
        try (RemoteServer server = RemoteServer.open(session, owner);
             RemoteClient client = RemoteClient.connect(server.endpoint())) {
            RemoteRequest request = client.submit(EvaluationSource.of("cleanup.lyra", ""));
            waitUntil(owner::hasPending);
            client.close();
            assertTrue(session.cancelEntered.await(5, TimeUnit.SECONDS));

            assertThrows(RemoteOperationException.class,
                    () -> RemoteClient.connect(server.endpoint()));

            session.releaseCancel.countDown();
            waitUntil(() -> server.controllerCount() == 0);
            try (RemoteClient reconnected = RemoteClient.connect(server.endpoint())) {
                ProtocolMessage.QueryResult result = reconnected
                        .queryRequest(request.requestId()).get(5, TimeUnit.SECONDS);
                assertEquals(ProtocolMessage.RemoteStatus.CANCELLED,
                        result.request().orElseThrow().status());
            }
        } finally {
            session.releaseCancel.countDown();
        }
    }

    @Test
    void cancellationBeforeOwnerPollIsTerminalAndRetainedResultsAreBounded() throws Exception {
        QueueOwner owner = new QueueOwner();
        FakeSession session = new FakeSession(owner.ownerThread);
        try (RemoteServer server = RemoteServer.open(session, owner,
                RemoteServerOptions.builder().maxRetainedResults(1).build());
             RemoteClient client = RemoteClient.connect(server.endpoint())) {
            RemoteRequest cancelled = client.submit(EvaluationSource.of("cancel.lyra", ""));
            ProtocolMessage.CancelResult cancel = cancelled.cancel().get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.ControlStatus.REQUESTED, cancel.status());
            assertEquals(ProtocolMessage.RemoteStatus.CANCELLED,
                    cancelled.result().get(5, TimeUnit.SECONDS).status());
            assertEquals(0, session.evaluations.get());

            RemoteRequest first = client.submit(EvaluationSource.of("first.lyra", ""),
                    SessionRevision.initial());
            pumpUntil(server, first.result()::isDone);
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                    first.result().get(5, TimeUnit.SECONDS).status());
            RemoteRequest second = client.submit(EvaluationSource.of("second.lyra", ""),
                    new SessionRevision(1));
            pumpUntil(server, second.result()::isDone);
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                    second.result().get(5, TimeUnit.SECONDS).status());
            ProtocolMessage.QueryResult expired = client.queryRequest(first.requestId())
                    .get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.QueryStatus.EXPIRED, expired.status());
        }
    }

    @Test
    void loadRejectionBeforeEffectsIsExplicitAndNeverRunsSource() throws Exception {
        QueueOwner owner = new QueueOwner();
        ThrowingLoadSession session = new ThrowingLoadSession(owner.ownerThread);
        try (RemoteServer server = RemoteServer.open(session, owner);
             RemoteClient client = RemoteClient.connect(server.endpoint())) {
            RemoteRequest load = client.load("missing/nonexistent.lyra");
            pumpUntil(server, load.result()::isDone);
            ProtocolMessage.Result result = load.result().get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.RemoteStatus.REJECTED, result.status());
            assertEquals(Optional.of("cannot read load file: "
                            + Path.of("missing/nonexistent.lyra").toAbsolutePath().normalize()),
                    result.failureSummary());
            assertEquals(0, session.evaluations.get());
        }
    }

    private static void pumpUntil(RemoteServer server,
                                  java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            server.poll();
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean());
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean());
    }

    private static ProtocolMessage.Result readResult(RawConnection connection)
            throws Exception {
        while (true) {
            ProtocolMessage message = connection.read();
            if (message instanceof ProtocolMessage.Result result) {
                return result;
            }
        }
    }

    static final class TerminalOwner implements OwnerDispatcher {
        final Thread ownerThread = Thread.currentThread();
        private final DispatchState terminalState;

        TerminalOwner(DispatchState terminalState) {
            this.terminalState = terminalState;
        }

        @Override
        public boolean isOwnerThread() {
            return Thread.currentThread() == ownerThread;
        }

        @Override
        public Dispatch dispatch(Runnable operation) {
            return new Dispatch() {
                @Override
                public DispatchState state() {
                    return terminalState;
                }

                @Override
                public boolean cancel() {
                    return false;
                }
            };
        }

        @Override
        public boolean poll() {
            return false;
        }
    }

    @Test
    void terminalControlDispatchesPublishUnavailableAndReleaseTheirPendingSlots()
            throws Exception {
        for (OwnerDispatcher.DispatchState state : List.of(
                OwnerDispatcher.DispatchState.CANCELLED, OwnerDispatcher.DispatchState.CLOSED,
                OwnerDispatcher.DispatchState.FAILED)) {
            TerminalOwner owner = new TerminalOwner(state);
            FakeSession session = new FakeSession(owner.ownerThread);
            try (RemoteServer server = RemoteServer.open(session, owner);
                 RemoteClient client = RemoteClient.connect(server.endpoint())) {
                ProtocolMessage.ResetResult reset = client.reset(0).get(5, TimeUnit.SECONDS);
                assertEquals(ProtocolMessage.ControlStatus.UNAVAILABLE, reset.status(),
                        state.name());

                ProtocolMessage.QueryResult query = client.queryBindings()
                        .get(5, TimeUnit.SECONDS);
                assertEquals(ProtocolMessage.QueryStatus.UNAVAILABLE, query.status(),
                        state.name());
                assertEquals(0, session.resets.get(), state.name());
            }
        }
    }

    static final class QueueOwner implements OwnerDispatcher {
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

        boolean hasPending() {
            synchronized (this) {
                return !tasks.isEmpty();
            }
        }

        @Override
        public boolean poll() {
            if (!isOwnerThread()) {
                throw new IllegalStateException("wrong owner");
            }
            Task task;
            synchronized (this) {
                task = tasks.poll();
            }
            if (task == null || !task.running.compareAndSet(false, true)) {
                return false;
            }
            task.state.set(DispatchState.RUNNING);
            try {
                task.operation.run();
                task.state.set(DispatchState.COMPLETED);
            } catch (RuntimeException failure) {
                task.state.set(DispatchState.FAILED);
                throw failure;
            }
            return true;
        }

        private final class Task implements Dispatch {
            private final Runnable operation;
            private final AtomicReference<DispatchState> state =
                    new AtomicReference<>(DispatchState.PENDING);
            private final java.util.concurrent.atomic.AtomicBoolean running =
                    new java.util.concurrent.atomic.AtomicBoolean();

            private Task(Runnable operation) {
                this.operation = operation;
            }

            @Override
            public DispatchState state() {
                return state.get();
            }

            @Override
            public synchronized boolean cancel() {
                if (state.compareAndSet(DispatchState.PENDING, DispatchState.CANCELLED)) {
                    tasks.remove(this);
                    return true;
                }
                return false;
            }
        }
    }

    static final class BlockingCancelSession implements RemoteSessionAdapter {
        private final UUID sessionId = UUID.randomUUID();
        private final Thread ownerThread;
        final CountDownLatch cancelEntered = new CountDownLatch(1);
        final CountDownLatch releaseCancel = new CountDownLatch(1);
        private SessionRevision revision = SessionRevision.initial();

        BlockingCancelSession(Thread ownerThread) {
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
            revision = revision.next();
            return new EvaluationResult.Success(request, revision, Optional.empty(), List.of());
        }

        @Override
        public boolean cancel(EvaluationId evaluationId) {
            cancelEntered.countDown();
            try {
                releaseCancel.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return true;
        }

        @Override
        public void reset() {
            assertEquals(ownerThread, Thread.currentThread());
        }
    }

    /** Fake session that tracks evaluate/load/reload invocations exactly. */
    static final class FakeSession implements RemoteSessionAdapter {
        private final UUID sessionId = UUID.randomUUID();
        private final Thread ownerThread;
        final AtomicInteger evaluations = new AtomicInteger();
        final AtomicInteger resets = new AtomicInteger();
        final AtomicInteger loads = new AtomicInteger();
        final AtomicInteger reloads = new AtomicInteger();
        final AtomicReference<Thread> evaluationThread = new AtomicReference<>();
        final AtomicReference<Thread> loadThread = new AtomicReference<>();
        final java.util.List<String> loadPaths = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.List<String> reloadTargets =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private SessionRevision revision = SessionRevision.initial();

        FakeSession(Thread ownerThread) {
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
        public EvaluationResult evaluate(EvaluationRequest request, RemoteCancellation cancellation) {
            assertEquals(ownerThread, Thread.currentThread());
            evaluationThread.set(Thread.currentThread());
            evaluations.incrementAndGet();
            if (cancellation.isRequested()) {
                return new EvaluationResult.Cancelled(request, revision,
                        io.mindspice.lyra.repl.Cancellation.observed(request.evaluationId()));
            }
            revision = revision.next();
            return new EvaluationResult.Success(request, revision, Optional.empty(), List.of());
        }

        @Override
        public EvaluationResult load(ProtocolMessage.LoadRequest request,
                                     RemoteCancellation cancellation) {
            assertEquals(ownerThread, Thread.currentThread());
            loadThread.set(Thread.currentThread());
            loads.incrementAndGet();
            loadPaths.add(request.path());
            revision = revision.next();
            return new EvaluationResult.Success(
                    new EvaluationRequest(EvaluationId.of(request.requestId()),
                            request.revision(), EvaluationSource.of(request.path(), "")),
                    revision, Optional.empty(), List.of());
        }

        @Override
        public EvaluationResult reload(ProtocolMessage.ReloadRequest request,
                                       RemoteCancellation cancellation) {
            assertEquals(ownerThread, Thread.currentThread());
            reloads.incrementAndGet();
            reloadTargets.add(request.target());
            revision = revision.next();
            return new EvaluationResult.Success(
                    new EvaluationRequest(EvaluationId.of(request.requestId()),
                            request.revision(),
                            EvaluationSource.of("reload " + request.target(), "")),
                    revision, Optional.empty(), List.of());
        }

        @Override
        public void reset() {
            assertEquals(ownerThread, Thread.currentThread());
            resets.incrementAndGet();
        }
    }

    /** Adapter whose LOAD always fails before any session effect. */
    static final class ThrowingLoadSession implements RemoteSessionAdapter {
        private final UUID sessionId = UUID.randomUUID();
        private final Thread ownerThread;
        final AtomicInteger evaluations = new AtomicInteger();
        private final SessionRevision revision = SessionRevision.initial();

        ThrowingLoadSession(Thread ownerThread) {
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
            evaluations.incrementAndGet();
            return new EvaluationResult.Success(request, revision, Optional.empty(), List.of());
        }

        @Override
        public EvaluationResult load(ProtocolMessage.LoadRequest request,
                                     RemoteCancellation cancellation) {
            assertEquals(ownerThread, Thread.currentThread());
            throw new RemoteLoadException("cannot read load file: "
                    + Path.of(request.path()).toAbsolutePath().normalize());
        }

        @Override
        public void reset() {
            assertEquals(ownerThread, Thread.currentThread());
        }
    }

    /**
     * Raw v2 client for tests that must control freshness fields exactly.
     * The handshake is the single credential-free hello exchange.
     */
    static final class RawConnection implements AutoCloseable {
        private final java.net.Socket socket;
        private final FrameCodec frames = new FrameCodec();
        private long revision;
        private long mutationSequence;

        private RawConnection(java.net.Socket socket) {
            this.socket = socket;
        }

        static RawConnection open(RemoteEndpoint endpoint) throws Exception {
            java.net.Socket socket = new java.net.Socket(
                    endpoint.address().address(), endpoint.address().port());
            socket.setTcpNoDelay(true);
            RawConnection connection = new RawConnection(socket);
            connection.send(new ProtocolMessage.ClientHello(
                    RemoteProtocol.VERSION, UUID.randomUUID(), endpoint.sessionId()));
            ProtocolMessage.ServerHello hello = assertInstanceOf(
                    ProtocolMessage.ServerHello.class, connection.read());
            connection.revision = hello.revision();
            connection.mutationSequence = hello.mutationSequence();
            return connection;
        }

        long mutationSequence() {
            return mutationSequence;
        }

        void send(ProtocolMessage message) throws IOException {
            ProtocolCodec.write(frames, socket.getOutputStream(), message);
        }

        ProtocolMessage read() throws IOException, ProtocolException {
            return ProtocolCodec.decode(frames.readFrame(socket.getInputStream()).orElseThrow());
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
