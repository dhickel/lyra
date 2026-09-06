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

class RemoteWireSecurityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptedIsQueuedBeforeInlineOwnerStatuses() throws Exception {
        InlineOwner owner = new InlineOwner();
        InlineSession session = new InlineSession();
        try (RemoteServer server = RemoteServer.open(session, owner,
                RemoteServerOptions.builder()
                        .credentialFile(temporaryDirectory.resolve("ordering-credential"))
                        .build());
             RawConnection connection = RawConnection.open(server.endpoint())) {
            ProtocolMessage.EvaluateRequest request = new ProtocolMessage.EvaluateRequest(
                    UUID.randomUUID(), 1, SessionRevision.initial(),
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
        try (RemoteServer server = RemoteServer.open(session, owner,
                RemoteServerOptions.builder()
                        .credentialFile(temporaryDirectory.resolve("credential"))
                        .build());
             RawConnection connection = RawConnection.open(server.endpoint())) {
            UUID requestId = UUID.randomUUID();
            ProtocolMessage.EvaluateRequest request = new ProtocolMessage.EvaluateRequest(
                    requestId, 1, SessionRevision.initial(), EvaluationSource.of("raw.lyra", ""));
            connection.send(request);
            assertInstanceOf(ProtocolMessage.Accepted.class, connection.read());
            pumpUntil(server, () -> session.evaluations.get() == 1);
            ProtocolMessage.Result first = readResult(connection);
            assertEquals(ProtocolMessage.RemoteStatus.SUCCESS, first.status());

            connection.send(request);
            ProtocolMessage.Result replay = readResult(connection);
            assertEquals(first, replay);
            assertEquals(1, session.evaluations.get());

            ProtocolMessage.EvaluateRequest duplicateSequence = new ProtocolMessage.EvaluateRequest(
                    UUID.randomUUID(), 1, new SessionRevision(1), EvaluationSource.of("other.lyra", ""));
            connection.send(duplicateSequence);
            ProtocolMessage.Error error = assertInstanceOf(
                    ProtocolMessage.Error.class, connection.read());
            assertEquals(ProtocolMessage.ErrorCode.REQUEST_EXPIRED, error.code());
            assertEquals(1, session.evaluations.get());
        }
    }

    @Test
    void duplicatePendingResetIdentitySharesResultAndRejectsDifferentContents() throws Exception {
        RemoteServerTest.QueueOwner owner = new RemoteServerTest.QueueOwner();
        RemoteServerTest.FakeSession session = new RemoteServerTest.FakeSession(owner.ownerThread);
        try (RemoteServer server = RemoteServer.open(session, owner,
                RemoteServerOptions.builder()
                        .credentialFile(temporaryDirectory.resolve("reset-identity-credential"))
                        .build());
             RawConnection connection = RawConnection.open(server.endpoint())) {
            UUID operationId = UUID.randomUUID();
            ProtocolMessage.ResetRequest reset = new ProtocolMessage.ResetRequest(operationId, 0);
            connection.send(reset);
            waitUntil(owner::hasPending);

            connection.send(reset);
            connection.send(new ProtocolMessage.ResetRequest(operationId, 1));
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
                        .credentialFile(temporaryDirectory.resolve("drip-credential"))
                        .handshakeTimeout(Duration.ofMillis(150))
                        .build());
             Socket socket = new Socket(server.endpoint().address().address(),
                     server.endpoint().address().port())) {
            socket.setSoTimeout(2_000);
            FrameCodec frames = new FrameCodec();
            ProtocolCodec.write(frames, socket.getOutputStream(), new ProtocolMessage.ClientHello(
                    RemoteProtocol.VERSION, UUID.randomUUID(), server.endpoint().sessionId()));
            ProtocolMessage.ServerHello hello = assertInstanceOf(
                    ProtocolMessage.ServerHello.class,
                    ProtocolCodec.decode(frames.readFrame(socket.getInputStream()).orElseThrow()));
            try (TokenCredential credential = TokenCredential.read(server.endpoint().credentialFile())) {
                ProtocolMessage.Authenticate authenticate = new ProtocolMessage.Authenticate(
                        RemoteProtocol.VERSION, hello.sessionId(), hello.challenge(),
                        credential.encodedForTransport());
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                ProtocolCodec.write(frames, encoded, authenticate);
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
    }

    @Test
    void clientHandshakeDeadlineIsAbsoluteAcrossAByteDrip() throws Exception {
        Path credentialPath = temporaryDirectory.resolve("client-drip-credential");
        UUID sessionId = UUID.randomUUID();
        try (TokenCredential ignored = TokenCredential.create(credentialPath);
             ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            RemoteEndpoint endpoint = new RemoteEndpoint(
                    new LoopbackEndpoint(InetAddress.getLoopbackAddress(), listener.getLocalPort()),
                    credentialPath, sessionId);
            AtomicReference<Throwable> serverFailure = new AtomicReference<>();
            Thread server = new Thread(() -> {
                boolean dripping = false;
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(2_000);
                    FrameCodec frames = new FrameCodec();
                    assertInstanceOf(ProtocolMessage.ClientHello.class,
                            ProtocolCodec.decode(
                                    frames.readFrame(socket.getInputStream()).orElseThrow()));
                    byte[] challenge = new byte[RemoteProtocol.CHALLENGE_BYTES];
                    ProtocolCodec.write(frames, socket.getOutputStream(),
                            new ProtocolMessage.ServerHello(
                                    RemoteProtocol.VERSION, sessionId, challenge));
                    assertInstanceOf(ProtocolMessage.Authenticate.class,
                            ProtocolCodec.decode(
                                    frames.readFrame(socket.getInputStream()).orElseThrow()));
                    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                    ProtocolCodec.write(frames, encoded, new ProtocolMessage.Authenticated(
                            RemoteProtocol.VERSION, sessionId, 0, 0, Optional.empty()));
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
        try (RemoteServer server = RemoteServer.open(session, owner,
                RemoteServerOptions.builder()
                        .credentialFile(temporaryDirectory.resolve("cancel-order-credential"))
                        .build());
             RawConnection connection = RawConnection.open(server.endpoint())) {
            ProtocolMessage.EvaluateRequest request = new ProtocolMessage.EvaluateRequest(
                    UUID.randomUUID(), 1, SessionRevision.initial(),
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
    void authenticatedSessionIdentityMustMatchTheServerHello() throws Exception {
        Path credentialPath = temporaryDirectory.resolve("identity-credential");
        UUID expectedSession = UUID.randomUUID();
        UUID authenticatedSession = UUID.randomUUID();
        try (TokenCredential credential = TokenCredential.create(credentialPath);
             ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            RemoteEndpoint endpoint = new RemoteEndpoint(
                    new LoopbackEndpoint(InetAddress.getLoopbackAddress(), listener.getLocalPort()),
                    credentialPath, expectedSession);
            AtomicReference<Throwable> serverFailure = new AtomicReference<>();
            Thread server = new Thread(() -> {
                try (Socket socket = listener.accept()) {
                    FrameCodec frames = new FrameCodec();
                    ProtocolMessage.ClientHello ignored = assertInstanceOf(
                            ProtocolMessage.ClientHello.class,
                            ProtocolCodec.decode(
                                    frames.readFrame(socket.getInputStream()).orElseThrow()));
                    byte[] challenge = new byte[RemoteProtocol.CHALLENGE_BYTES];
                    ProtocolCodec.write(frames, socket.getOutputStream(),
                            new ProtocolMessage.ServerHello(
                                    RemoteProtocol.VERSION, expectedSession, challenge));
                    ProtocolMessage.Authenticate authenticate = assertInstanceOf(
                            ProtocolMessage.Authenticate.class,
                            ProtocolCodec.decode(
                                    frames.readFrame(socket.getInputStream()).orElseThrow()));
                    ProtocolCodec.write(frames, socket.getOutputStream(),
                            new ProtocolMessage.Authenticated(
                                    RemoteProtocol.VERSION, authenticatedSession, 0, 0,
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
                RemoteServerOptions.builder()
                        .credentialFile(temporaryDirectory.resolve("credential"))
                        .handshakeTimeout(Duration.ofMillis(50))
                        .build());
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
            ProtocolMessage.ServerHello hello = assertInstanceOf(
                    ProtocolMessage.ServerHello.class, connection.read());
            try (TokenCredential credential = TokenCredential.read(endpoint.credentialFile())) {
                connection.send(new ProtocolMessage.Authenticate(
                        RemoteProtocol.VERSION, hello.sessionId(), hello.challenge(),
                        credential.encodedForTransport()));
            }
            assertInstanceOf(ProtocolMessage.Authenticated.class, connection.read());
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
