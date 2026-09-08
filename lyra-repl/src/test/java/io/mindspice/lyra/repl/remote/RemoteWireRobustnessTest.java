package io.mindspice.lyra.repl.remote;

import io.mindspice.lyra.repl.EvaluationId;
import io.mindspice.lyra.repl.EvaluationRequest;
import io.mindspice.lyra.repl.EvaluationResult;
import io.mindspice.lyra.repl.EvaluationSource;
import io.mindspice.lyra.repl.SessionRevision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-robustness coverage for the credential-free v2 protocol: v1 upgrade
 * diagnostics, ordering, duplicates, absolute handshake deadlines,
 * cancellation races, session identity, oversized payload truncation with
 * terminal retention and host I/O separation.
 */
class RemoteWireRobustnessTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void versionOnePeerReceivesAnExplicitUpgradeError() throws Exception {
        RemoteServerTest.QueueOwner owner = new RemoteServerTest.QueueOwner();
        RemoteServerTest.FakeSession session = new RemoteServerTest.FakeSession(owner.ownerThread);
        try (RemoteServer server = RemoteServer.open(session, owner);
             Socket socket = new Socket(server.endpoint().address().address(),
                     server.endpoint().address().port())) {
            socket.setSoTimeout(2_000);
            FrameCodec frames = new FrameCodec();
            // A v1 client hello: version 1 with the legacy schema, sent as
            // raw bytes because the v2 model cannot construct it.
            String v1Hello = "{\"kind\":\"clientHello\",\"version\":1,"
                    + "\"clientId\":\"" + UUID.randomUUID() + "\"}";
            frames.writeFrame(socket.getOutputStream(),
                    v1Hello.getBytes(StandardCharsets.UTF_8));
            ProtocolMessage.Error error = assertInstanceOf(
                    ProtocolMessage.Error.class,
                    ProtocolCodec.decode(frames.readFrame(socket.getInputStream()).orElseThrow()));
            assertEquals(ProtocolMessage.ErrorCode.UNSUPPORTED_VERSION, error.code());
            assertTrue(error.detail().contains("upgrade"), error.detail());
            assertTrue(server.isOpen());
        }
    }

    @Test
    void acceptedIsQueuedBeforeInlineOwnerStatuses() throws Exception {
        InlineOwner owner = new InlineOwner();
        InlineSession session = new InlineSession();
        try (RemoteServer server = RemoteServer.open(session, owner);
             RawConnection connection = RawConnection.open(server.endpoint())) {
            ProtocolMessage.EvaluateRequest request = new ProtocolMessage.EvaluateRequest(
                    UUID.randomUUID(), 1, SessionRevision.initial(), 0,
                    EvaluationSource.of("ordering.lyra", ""));
            connection.send(request);

            assertInstanceOf(ProtocolMessage.Accepted.class, connection.read());
            assertInstanceOf(ProtocolMessage.Status.class, connection.read());
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                    assertInstanceOf(ProtocolMessage.Result.class, connection.read()).status());
        }
    }

    @Test
    void duplicateIdentityAndSequenceNeverExecuteSourceTwice() throws Exception {
        RemoteServerTest.QueueOwner owner = new RemoteServerTest.QueueOwner();
        RemoteServerTest.FakeSession session = new RemoteServerTest.FakeSession(owner.ownerThread);
        try (RemoteServer server = RemoteServer.open(session, owner);
             RawConnection connection = RawConnection.open(server.endpoint())) {
            UUID requestId = UUID.randomUUID();
            ProtocolMessage.EvaluateRequest request = new ProtocolMessage.EvaluateRequest(
                    requestId, 1, SessionRevision.initial(), 0,
                    EvaluationSource.of("raw.lyra", ""));
            connection.send(request);
            assertInstanceOf(ProtocolMessage.Accepted.class, connection.read());
            pumpUntil(server, () -> session.evaluations.get() == 1);
            ProtocolMessage.Result first = readResult(connection);
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, first.status());

            // An identical duplicate replays the retained result without
            // re-execution.
            connection.send(request);
            ProtocolMessage.Result replay = readResult(connection);
            assertEquals(first, replay);
            assertEquals(1, session.evaluations.get());

            // A duplicate identity with different contents is rejected.
            ProtocolMessage.EvaluateRequest changed = new ProtocolMessage.EvaluateRequest(
                    requestId, 1, SessionRevision.initial(), 0,
                    EvaluationSource.of("raw.lyra", "let x :I32 = 1"));
            connection.send(changed);
            ProtocolMessage.Error error = assertInstanceOf(
                    ProtocolMessage.Error.class, connection.read());
            assertEquals(ProtocolMessage.ErrorCode.INVALID_SCHEMA, error.code());
            assertEquals(1, session.evaluations.get());

            // A duplicate sequence with a fresh identity is expired, not
            // re-executed.
            ProtocolMessage.EvaluateRequest duplicateSequence = new ProtocolMessage.EvaluateRequest(
                    UUID.randomUUID(), 1, new SessionRevision(1), 1,
                    EvaluationSource.of("other.lyra", ""));
            connection.send(duplicateSequence);
            assertEquals(ProtocolMessage.ErrorCode.REQUEST_EXPIRED,
                    assertInstanceOf(ProtocolMessage.Error.class,
                            connection.read()).code());
            assertEquals(1, session.evaluations.get());
        }
    }

    @Test
    void duplicatePendingResetIdentitySharesResultAndRejectsDifferentContents() throws Exception {
        RemoteServerTest.QueueOwner owner = new RemoteServerTest.QueueOwner();
        RemoteServerTest.FakeSession session = new RemoteServerTest.FakeSession(owner.ownerThread);
        try (RemoteServer server = RemoteServer.open(session, owner);
             RawConnection connection = RawConnection.open(server.endpoint())) {
            UUID operationId = UUID.randomUUID();
            ProtocolMessage.ResetRequest reset = new ProtocolMessage.ResetRequest(
                    operationId, 0, 0);
            connection.send(reset);
            waitUntil(owner::hasPending);

            connection.send(reset);
            connection.send(new ProtocolMessage.ResetRequest(operationId, 1, 0));
            ProtocolMessage.Error error = assertInstanceOf(
                    ProtocolMessage.Error.class, connection.read());
            assertEquals(ProtocolMessage.ErrorCode.INVALID_SCHEMA, error.code());

            assertTrue(server.poll());
            ProtocolMessage.ResetResult result = assertInstanceOf(
                    ProtocolMessage.ResetResult.class, connection.read());
            assertEquals(ProtocolMessage.ControlStatus.OK, result.status());
            assertEquals(1, session.resets.get());

            connection.send(reset);
            assertEquals(result, assertInstanceOf(
                    ProtocolMessage.ResetResult.class, connection.read()));
        }
    }

    @Test
    void handshakeDeadlineIsAbsoluteAcrossAByteDrip() throws Exception {
        RemoteServerTest.QueueOwner owner = new RemoteServerTest.QueueOwner();
        RemoteServerTest.FakeSession session = new RemoteServerTest.FakeSession(owner.ownerThread);
        try (RemoteServer server = RemoteServer.open(session, owner,
                RemoteServerOptions.builder()
                        .handshakeTimeout(Duration.ofMillis(150))
                        .build());
             Socket socket = new Socket(server.endpoint().address().address(),
                     server.endpoint().address().port())) {
            socket.setSoTimeout(2_000);
            FrameCodec frames = new FrameCodec();
            ProtocolMessage.ClientHello hello = new ProtocolMessage.ClientHello(
                    RemoteProtocol.VERSION, UUID.randomUUID(), server.endpoint().sessionId());
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            ProtocolCodec.write(frames, encoded, hello);
            byte[] bytes = encoded.toByteArray();
            Thread drip = new Thread(() -> {
                try {
                    for (byte value : bytes) {
                        socket.getOutputStream().write(value & 0xff);
                        socket.getOutputStream().flush();
                        Thread.sleep(20);
                    }
                } catch (IOException ignored) {
                    // The server closes the connection when the deadline expires.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            drip.start();
            try {
                ProtocolMessage.Error error = assertInstanceOf(
                        ProtocolMessage.Error.class,
                        ProtocolCodec.decode(
                                frames.readFrame(socket.getInputStream()).orElseThrow()));
                assertEquals(ProtocolMessage.ErrorCode.HANDSHAKE_TIMEOUT, error.code());
            } finally {
                drip.interrupt();
                drip.join(2_000);
            }
        }
    }

    @Test
    void clientHandshakeDeadlineIsAbsoluteAcrossAByteDrip() throws Exception {
        UUID sessionId = UUID.randomUUID();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            RemoteEndpoint endpoint = new RemoteEndpoint(
                    new LoopbackEndpoint(InetAddress.getLoopbackAddress(), listener.getLocalPort()),
                    sessionId);
            AtomicReference<Throwable> serverFailure = new AtomicReference<>();
            Thread server = new Thread(() -> {
                boolean dripping = false;
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(2_000);
                    FrameCodec frames = new FrameCodec();
                    assertInstanceOf(ProtocolMessage.ClientHello.class,
                            ProtocolCodec.decode(
                                    frames.readFrame(socket.getInputStream()).orElseThrow()));
                    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                    ProtocolCodec.write(frames, encoded, new ProtocolMessage.ServerHello(
                            RemoteProtocol.VERSION, sessionId, 0, 0, 0, Optional.empty()));
                    dripping = true;
                    for (byte value : encoded.toByteArray()) {
                        socket.getOutputStream().write(value & 0xff);
                        socket.getOutputStream().flush();
                        Thread.sleep(20);
                    }
                } catch (IOException failure) {
                    if (!dripping) {
                        serverFailure.set(failure);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    serverFailure.set(interrupted);
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            }, "lyra-repl-client-drip-server");
            server.start();
            long started = System.nanoTime();
            assertThrows(IOException.class,
                    () -> RemoteClient.connect(endpoint, RemoteClientOptions.builder()
                            .connectTimeout(Duration.ofSeconds(2))
                            .handshakeTimeout(Duration.ofMillis(150))
                            .build()));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMillis < 1_000,
                    () -> "client handshake exceeded absolute deadline: " + elapsedMillis + "ms");
            server.join(2_000);
            assertFalse(server.isAlive());
            assertTrue(serverFailure.get() == null,
                    () -> "fake server failed: " + serverFailure.get());
        }
    }

    @Test
    void terminalResultSuppressesCancellationStatusAfterEvaluationFinishes() throws Exception {
        RemoteServerTest.QueueOwner owner = new RemoteServerTest.QueueOwner();
        CoordinatedCancelSession session = new CoordinatedCancelSession(owner.ownerThread);
        AtomicReference<Throwable> cancelFailure = new AtomicReference<>();
        try (RemoteServer server = RemoteServer.open(session, owner);
             RawConnection connection = RawConnection.open(server.endpoint())) {
            ProtocolMessage.EvaluateRequest request = new ProtocolMessage.EvaluateRequest(
                    UUID.randomUUID(), 1, SessionRevision.initial(), 0,
                    EvaluationSource.of("cancel-order.lyra", ""));
            connection.send(request);
            assertInstanceOf(ProtocolMessage.Accepted.class, connection.read());

            Thread canceller = new Thread(() -> {
                try {
                    assertTrue(session.evaluationEntered.await(5, TimeUnit.SECONDS));
                    connection.send(new ProtocolMessage.CancelRequest(
                            UUID.randomUUID(), request.requestId()));
                    assertTrue(session.cancelEntered.await(5, TimeUnit.SECONDS));
                    session.releaseEvaluation.countDown();
                    assertTrue(session.evaluationFinished.await(5, TimeUnit.SECONDS));
                    session.releaseCancel.countDown();
                } catch (Throwable failure) {
                    cancelFailure.set(failure);
                    session.releaseEvaluation.countDown();
                    session.releaseCancel.countDown();
                }
            }, "lyra-repl-cancel-order-client");
            canceller.start();
            assertTrue(server.poll());
            session.evaluationFinished.countDown();
            canceller.join(5_000);
            assertFalse(canceller.isAlive());
            assertTrue(cancelFailure.get() == null,
                    () -> "cancellation coordinator failed: " + cancelFailure.get());

            assertEquals(ProtocolMessage.RemoteStatus.RUNNING,
                    assertInstanceOf(ProtocolMessage.Status.class, connection.read()).status());
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS,
                    assertInstanceOf(ProtocolMessage.Result.class, connection.read()).status());
            assertInstanceOf(ProtocolMessage.CancelResult.class, connection.read());
        } finally {
            session.releaseEvaluation.countDown();
            session.releaseCancel.countDown();
        }
    }

    @Test
    void sessionIdentityMustMatchTheServerHello() throws Exception {
        UUID expectedSession = UUID.randomUUID();
        UUID mismatchedSession = UUID.randomUUID();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            RemoteEndpoint endpoint = new RemoteEndpoint(
                    new LoopbackEndpoint(InetAddress.getLoopbackAddress(), listener.getLocalPort()),
                    expectedSession);
            AtomicReference<Throwable> serverFailure = new AtomicReference<>();
            Thread server = new Thread(() -> {
                try (Socket socket = listener.accept()) {
                    FrameCodec frames = new FrameCodec();
                    assertInstanceOf(ProtocolMessage.ClientHello.class,
                            ProtocolCodec.decode(
                                    frames.readFrame(socket.getInputStream()).orElseThrow()));
                    ProtocolCodec.write(frames, socket.getOutputStream(),
                            new ProtocolMessage.ServerHello(
                                    RemoteProtocol.VERSION, mismatchedSession, 0, 0, 0,
                                    Optional.empty()));
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            });
            server.start();
            assertThrows(IOException.class,
                    () -> RemoteClient.connect(endpoint, RemoteClientOptions.builder()
                            .connectTimeout(Duration.ofSeconds(2))
                            .handshakeTimeout(Duration.ofSeconds(2))
                            .build()));
            server.join(2_000);
            assertFalse(server.isAlive());
            assertTrue(serverFailure.get() == null,
                    () -> "fake server failed: " + serverFailure.get());
        }
    }

    @Test
    void anIdlePeerIsBoundedByTheHandshakeTimeout() throws Exception {
        RemoteServerTest.QueueOwner owner = new RemoteServerTest.QueueOwner();
        RemoteServerTest.FakeSession session = new RemoteServerTest.FakeSession(owner.ownerThread);
        try (RemoteServer server = RemoteServer.open(session, owner,
                RemoteServerOptions.builder().handshakeTimeout(Duration.ofMillis(50)).build());
             Socket socket = new Socket(server.endpoint().address().address(),
                     server.endpoint().address().port())) {
            socket.setSoTimeout(2_000);
            FrameCodec frames = new FrameCodec();
            ProtocolMessage.Error error = assertInstanceOf(
                    ProtocolMessage.Error.class,
                    ProtocolCodec.decode(frames.readFrame(socket.getInputStream()).orElseThrow()));
            assertEquals(ProtocolMessage.ErrorCode.HANDSHAKE_TIMEOUT, error.code());
            assertTrue(server.isOpen());
        }
    }

    @Test
    void oversizedDynamicResultIsTruncatedButKeepsTerminalStatus() throws Exception {
        RemoteServerTest.QueueOwner owner = new RemoteServerTest.QueueOwner();
        LargeValueSession session = new LargeValueSession(owner.ownerThread);
        try (RemoteServer server = RemoteServer.open(session, owner,
                RemoteServerOptions.builder().maxFrameBytes(512).build());
             RemoteClient client = RemoteClient.connect(server.endpoint(),
                     RemoteClientOptions.builder().maxFrameBytes(512).build())) {
            RemoteRequest request = client.submit(EvaluationSource.of("large.lyra", ""));
            pumpUntil(server, request.result()::isDone);
            ProtocolMessage.Result result = request.result().get(5, TimeUnit.SECONDS);
            // Terminal status is preserved even when the dynamic payload
            // exceeds the frame bound.
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, result.status());
            assertEquals(new SessionRevision(1), client.revision());
            assertTrue(result.value().isEmpty());
            assertTrue(result.diagnostics().isEmpty());
            assertTrue(result.failureSummary().orElse("").contains("bounded"));
        }
    }

    @Test
    void largeDynamicResultBelowTheFrameBoundRemainsTerminalAndComplete()
            throws Exception {
        RemoteServerTest.QueueOwner owner = new RemoteServerTest.QueueOwner();
        LargeValueSession session = new LargeValueSession(owner.ownerThread);
        try (RemoteServer server = RemoteServer.open(session, owner);
             RemoteClient client = RemoteClient.connect(server.endpoint())) {
            RemoteRequest request = client.submit(EvaluationSource.of("large-fit.lyra", ""));
            pumpUntil(server, request.result()::isDone);
            ProtocolMessage.Result result = request.result().get(5, TimeUnit.SECONDS);
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, result.status());
            assertTrue(result.value().isPresent());
            assertEquals("x".repeat(16 * 1024),
                    ((ProtocolMessage.Scalar) result.value().orElseThrow().data()).value());
        }
    }

    @Test
    void malformedFramesAndOversizedFramesAreRejectedWithoutStateDamage()
            throws Exception {
        RemoteServerTest.QueueOwner owner = new RemoteServerTest.QueueOwner();
        RemoteServerTest.FakeSession session = new RemoteServerTest.FakeSession(owner.ownerThread);
        try (RemoteServer server = RemoteServer.open(session, owner,
                RemoteServerOptions.builder().maxFrameBytes(4096).build());
             Socket socket = new Socket(server.endpoint().address().address(),
                     server.endpoint().address().port())) {
            socket.setSoTimeout(2_000);
            FrameCodec frames = new FrameCodec(4096);
            // A valid hello, then a corrupt follow-up frame must not kill the
            // listener.
            ProtocolCodec.write(frames, socket.getOutputStream(),
                    new ProtocolMessage.ClientHello(RemoteProtocol.VERSION,
                            UUID.randomUUID(), server.endpoint().sessionId()));
            assertInstanceOf(ProtocolMessage.ServerHello.class,
                    ProtocolCodec.decode(frames.readFrame(socket.getInputStream()).orElseThrow()));

            ByteArrayOutputStream oversized = new ByteArrayOutputStream();
            oversized.write(new byte[] {0, 0, 0x20, 0});
            socket.getOutputStream().write(oversized.toByteArray());
            socket.getOutputStream().flush();

            ProtocolMessage.Error error = assertInstanceOf(ProtocolMessage.Error.class,
                    ProtocolCodec.decode(frames.readFrame(socket.getInputStream()).orElseThrow()));
            assertEquals(ProtocolMessage.ErrorCode.FRAME_TOO_LARGE, error.code());
            assertTrue(server.isOpen());
        }
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

    private static void waitUntil(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean());
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

    private static final class CoordinatedCancelSession implements RemoteSessionAdapter {
        private final UUID sessionId = UUID.randomUUID();
        private final Thread ownerThread;
        private SessionRevision revision = SessionRevision.initial();
        final CountDownLatch evaluationEntered = new CountDownLatch(1);
        final CountDownLatch releaseEvaluation = new CountDownLatch(1);
        final CountDownLatch evaluationFinished = new CountDownLatch(1);
        final CountDownLatch cancelEntered = new CountDownLatch(1);
        final CountDownLatch releaseCancel = new CountDownLatch(1);

        private CoordinatedCancelSession(Thread ownerThread) {
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
            evaluationEntered.countDown();
            try {
                releaseEvaluation.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
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

    /** Session that publishes a large dynamic result every time. */
    private static final class LargeValueSession implements RemoteSessionAdapter {
        private static final io.mindspice.lyra.repl.SnapshotLimits LARGE_LIMITS =
                new io.mindspice.lyra.repl.SnapshotLimits(8, 1024, 64 * 1024);
        private final UUID sessionId = UUID.randomUUID();
        private final Thread ownerThread;
        private SessionRevision revision = SessionRevision.initial();

        private LargeValueSession(Thread ownerThread) {
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
            return new EvaluationResult.Success(request, revision,
                    Optional.of(new io.mindspice.lyra.repl.ValueSnapshot(
                            io.mindspice.lyra.runtime.LyraType.parse("String"),
                            new io.mindspice.lyra.repl.ValueSnapshot.Scalar(
                                    io.mindspice.lyra.repl.ScalarKind.STRING,
                                    "x".repeat(16 * 1024)),
                            LARGE_LIMITS)),
                    List.of());
        }

        @Override
        public void reset() {
            assertEquals(ownerThread, Thread.currentThread());
        }
    }

    private static final class InlineOwner implements OwnerDispatcher {
        @Override
        public boolean isOwnerThread() {
            return true;
        }

        @Override
        public Dispatch dispatch(Runnable operation) {
            operation.run();
            return new Dispatch() {
                @Override
                public DispatchState state() {
                    return DispatchState.COMPLETED;
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

    private static final class InlineSession implements RemoteSessionAdapter {
        private final UUID sessionId = UUID.randomUUID();
        private SessionRevision revision = SessionRevision.initial();

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
            revision = revision.next();
            return new EvaluationResult.Success(request, revision, Optional.empty(), List.of());
        }

        @Override
        public void reset() {
        }
    }

    private static final class RawConnection implements AutoCloseable {
        private final Socket socket;
        private final FrameCodec frames = new FrameCodec();

        private RawConnection(Socket socket) {
            this.socket = socket;
        }

        private static RawConnection open(RemoteEndpoint endpoint) throws Exception {
            Socket socket = new Socket(endpoint.address().address(), endpoint.address().port());
            socket.setTcpNoDelay(true);
            RawConnection connection = new RawConnection(socket);
            connection.send(new ProtocolMessage.ClientHello(
                    RemoteProtocol.VERSION, UUID.randomUUID(), endpoint.sessionId()));
            assertInstanceOf(ProtocolMessage.ServerHello.class, connection.read());
            return connection;
        }

        private void send(ProtocolMessage message) throws IOException {
            ProtocolCodec.write(frames, socket.getOutputStream(), message);
        }

        private ProtocolMessage read() throws IOException, ProtocolException {
            return ProtocolCodec.decode(frames.readFrame(socket.getInputStream()).orElseThrow());
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
