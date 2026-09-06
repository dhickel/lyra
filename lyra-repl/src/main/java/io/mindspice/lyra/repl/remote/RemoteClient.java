package io.mindspice.lyra.repl.remote;

import io.mindspice.lyra.repl.EvaluationSource;
import io.mindspice.lyra.repl.SessionRevision;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit loopback client transport with no generic RPC surface. */
public final class RemoteClient implements AutoCloseable {
    private final RemoteEndpoint endpoint;
    private final RemoteClientOptions options;
    private final UUID clientId = UUID.randomUUID();
    private final FrameCodec frames;
    private final Object lifecycleLock = new Object();
    private final Map<UUID, RemoteRequest> requests = new LinkedHashMap<>();
    private final Map<UUID, Waiter> waiters = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile Socket socket;
    private volatile InputStream input;
    private volatile OutputStream output;
    private volatile Thread reader;
    private volatile boolean connected;
    private volatile UUID sessionId;
    private volatile long revision;
    private long nextSequence = 1;

    private RemoteClient(RemoteEndpoint endpoint, RemoteClientOptions options) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.options = Objects.requireNonNull(options, "options");
        this.frames = new FrameCodec(options.maxFrameBytes());
    }

    public static RemoteClient connect(RemoteEndpoint endpoint) throws IOException {
        return connect(endpoint, RemoteClientOptions.defaults());
    }

    public static RemoteClient connect(
            RemoteEndpoint endpoint, RemoteClientOptions options) throws IOException {
        RemoteClient client = new RemoteClient(endpoint, options);
        try {
            client.openTransport(endpoint.sessionId());
            return client;
        } catch (IOException | RuntimeException failure) {
            client.close();
            throw failure;
        }
    }

    public RemoteEndpoint endpoint() {
        return endpoint;
    }

    public UUID sessionId() {
        UUID value = sessionId;
        if (value == null) {
            throw new IllegalStateException("client is not connected");
        }
        return value;
    }

    public SessionRevision revision() {
        return new SessionRevision(revision);
    }

    public boolean isConnected() {
        return connected && !closed.get();
    }

    /**
     * Reconnects explicitly. No source or operation is resubmitted; callers
     * can query retained request identities after the new handshake.
     */
    public void reconnect() throws IOException {
        synchronized (lifecycleLock) {
            requireOpen();
            markRequestsDisconnected();
            closeTransport(false);
            failWaiters(new IOException("remote connection replaced"));
            openTransport(Optional.ofNullable(sessionId));
        }
    }

    public RemoteRequest submit(EvaluationSource source) throws IOException {
        return submit(source, revision());
    }

    public RemoteRequest submit(EvaluationSource source, SessionRevision baseRevision)
            throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(baseRevision, "baseRevision");
        synchronized (lifecycleLock) {
            requireConnected();
            ensureOutstandingCapacity(Optional.empty());
            if (nextSequence <= 0) {
                throw new IOException("remote request sequence exhausted");
            }
            UUID requestId = UUID.randomUUID();
            long sequence = nextSequence;
            RemoteRequest request = new RemoteRequest(this, requestId, sequence);
            requests.put(requestId, request);
            try {
                send(new ProtocolMessage.EvaluateRequest(
                        RemoteProtocol.VERSION, requestId, sequence, baseRevision, source));
                nextSequence = sequence == Long.MAX_VALUE ? 0 : sequence + 1;
            } catch (IOException failure) {
                requests.remove(requestId);
                markRequestsDisconnected();
                closeTransport(false);
                failWaiters(new IOException("remote connection closed", failure));
                throw failure;
            } catch (RuntimeException failure) {
                requests.remove(requestId);
                throw failure;
            }
            return request;
        }
    }

    public CompletableFuture<ProtocolMessage.CancelResult> cancel(UUID requestId)
            throws IOException {
        Objects.requireNonNull(requestId, "requestId");
        UUID operationId = UUID.randomUUID();
        CompletableFuture<ProtocolMessage.CancelResult> future = new CompletableFuture<>();
        synchronized (lifecycleLock) {
            requireConnected();
            registerWaiter(operationId, WaiterKind.CANCEL, future,
                    Optional.of(requestId));
            try {
                send(new ProtocolMessage.CancelRequest(
                        RemoteProtocol.VERSION, operationId, requestId));
            } catch (IOException | RuntimeException failure) {
                waiters.remove(operationId);
                future.completeExceptionally(failure);
                throw failure;
            }
        }
        return future;
    }

    public CompletableFuture<ProtocolMessage.ResetResult> reset(long expectedRevision)
            throws IOException {
        ProtocolValues.nonNegativeLong(expectedRevision, "expectedRevision");
        UUID operationId = UUID.randomUUID();
        CompletableFuture<ProtocolMessage.ResetResult> future = new CompletableFuture<>();
        synchronized (lifecycleLock) {
            requireConnected();
            registerWaiter(operationId, WaiterKind.RESET, future);
            try {
                send(new ProtocolMessage.ResetRequest(
                        RemoteProtocol.VERSION, operationId, expectedRevision));
            } catch (IOException | RuntimeException failure) {
                waiters.remove(operationId);
                future.completeExceptionally(failure);
                throw failure;
            }
        }
        return future;
    }

    public CompletableFuture<ProtocolMessage.QueryResult> querySession() throws IOException {
        return query(ProtocolMessage.QueryRequest.session(UUID.randomUUID()));
    }

    public CompletableFuture<ProtocolMessage.QueryResult> queryRequest(UUID requestId)
            throws IOException {
        return query(ProtocolMessage.QueryRequest.request(UUID.randomUUID(),
                Objects.requireNonNull(requestId, "requestId")));
    }

    public CompletableFuture<ProtocolMessage.QueryResult> queryBindings() throws IOException {
        return query(ProtocolMessage.QueryRequest.bindings(UUID.randomUUID()));
    }

    public CompletableFuture<ProtocolMessage.QueryResult> queryType(EvaluationSource source)
            throws IOException {
        return query(ProtocolMessage.QueryRequest.type(UUID.randomUUID(),
                Objects.requireNonNull(source, "source")));
    }

    private CompletableFuture<ProtocolMessage.QueryResult> query(ProtocolMessage.QueryRequest request)
            throws IOException {
        CompletableFuture<ProtocolMessage.QueryResult> future = new CompletableFuture<>();
        synchronized (lifecycleLock) {
            requireConnected();
            ensureOutstandingCapacity(request.requestId());
            registerWaiter(request.queryId(), WaiterKind.QUERY, future,
                    request.requestId());
            try {
                send(request);
            } catch (IOException | RuntimeException failure) {
                waiters.remove(request.queryId());
                future.completeExceptionally(failure);
                throw failure;
            }
        }
        return future;
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            closeTransport(true);
            IOException failure = new IOException("remote client is closed");
            for (RemoteRequest request : requests.values()) {
                if (!request.isTerminal()) {
                    request.failed(failure);
                }
            }
            for (Waiter waiter : waiters.values()) {
                waiter.future.completeExceptionally(failure);
            }
            waiters.clear();
        }
    }

    private void openTransport(Optional<UUID> expectedSession) throws IOException {
        TokenCredential token = TokenCredential.read(endpoint.credentialFile());
        Socket newSocket = new Socket();
        try {
            newSocket.setTcpNoDelay(true);
            newSocket.connect(endpoint.address().socketAddress(),
                    timeoutMillis(options.connectTimeout()));
            InputStream newInput = newSocket.getInputStream();
            OutputStream newOutput = newSocket.getOutputStream();
            long handshakeDeadline = System.nanoTime()
                    + options.handshakeTimeout().toNanos();
            UUID expectedClientSession = expectedSession.orElse(null);
            sendDirect(newOutput, new ProtocolMessage.ClientHello(
                    RemoteProtocol.VERSION, clientId, Optional.ofNullable(expectedClientSession)));
            ProtocolMessage serverHello = readDirect(newSocket, newInput, handshakeDeadline);
            if (!(serverHello instanceof ProtocolMessage.ServerHello hello)) {
                throw handshakeFailure(serverHello);
            }
            if (expectedClientSession != null && !expectedClientSession.equals(hello.sessionId())) {
                throw new IOException("server session identity changed");
            }
            sendDirect(newOutput, new ProtocolMessage.Authenticate(
                    RemoteProtocol.VERSION, hello.sessionId(), hello.challenge(),
                    token.encodedForTransport()));
            ProtocolMessage authenticated = readDirect(newSocket, newInput, handshakeDeadline);
            if (!(authenticated instanceof ProtocolMessage.Authenticated accepted)) {
                throw handshakeFailure(authenticated);
            }
            if (!accepted.sessionId().equals(hello.sessionId())
                    || (expectedClientSession != null
                    && !expectedClientSession.equals(accepted.sessionId()))) {
                throw new IOException("authenticated server session identity changed");
            }
            reconcileSequence(accepted.lastSequence());
            this.socket = newSocket;
            this.input = newInput;
            this.output = newOutput;
            this.sessionId = accepted.sessionId();
            this.revision = accepted.revision();
            this.connected = true;
            newSocket.setSoTimeout(0);
            Thread thread = new Thread(() -> readLoop(newSocket, newInput),
                    "lyra-repl-remote-client-reader");
            thread.setDaemon(true);
            reader = thread;
            thread.start();
        } catch (SocketTimeoutException timeout) {
            throw new IOException("remote handshake timed out", timeout);
        } finally {
            token.close();
            if (!connected || socket != newSocket) {
                try {
                    newSocket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void readLoop(Socket contextSocket, InputStream contextInput) {
        try {
            while (!closed.get() && connected && socket == contextSocket) {
                Optional<byte[]> payload = frames.readFrame(contextInput);
                if (payload.isEmpty()) {
                    break;
                }
                ProtocolMessage message = ProtocolCodec.decode(payload.orElseThrow());
                receive(message);
            }
        } catch (IOException | RuntimeException failure) {
            // The pending request remains queryable by identity after an
            // explicit reconnect; no source is retried here.
        } finally {
            synchronized (lifecycleLock) {
                if (socket == contextSocket) {
                    connected = false;
                    try {
                        contextSocket.close();
                    } catch (IOException ignored) {
                    }
                    for (RemoteRequest request : requests.values()) {
                        request.disconnected();
                    }
                    for (Waiter waiter : waiters.values()) {
                        waiter.future.completeExceptionally(
                                new IOException("remote connection closed"));
                    }
                    waiters.clear();
                }
            }
        }
    }

    private void receive(ProtocolMessage message) {
        switch (message) {
            case ProtocolMessage.Accepted accepted -> {
                RemoteRequest request = request(accepted.requestId());
                if (request != null) {
                    request.accepted(accepted.status());
                    revision = Math.max(revision, accepted.revision());
                }
            }
            case ProtocolMessage.Status status -> {
                RemoteRequest request = request(status.requestId());
                if (request != null) {
                    request.status(status.status());
                    revision = Math.max(revision, status.revision());
                }
            }
            case ProtocolMessage.Result result -> {
                RemoteRequest request = request(result.requestId());
                revision = Math.max(revision, result.revision());
                if (request != null) {
                    request.completed(result);
                }
            }
            case ProtocolMessage.CancelResult result -> {
                Waiter waiter = removeWaiter(result.operationId());
                if (waiter != null) {
                    complete(waiter, result);
                }
            }
            case ProtocolMessage.ResetResult result -> {
                Waiter waiter = removeWaiter(result.operationId());
                revision = Math.max(revision, result.revision());
                if (waiter != null) {
                    complete(waiter, result);
                }
            }
            case ProtocolMessage.QueryResult result -> {
                revision = Math.max(revision, result.revision());
                Waiter waiter = removeWaiter(result.queryId());
                result.request().ifPresent(snapshot -> {
                    applyQuerySnapshot(result, snapshot);
                    if (result.terminalResult().isEmpty() && snapshot.status().isTerminal()) {
                        RemoteRequest request = request(snapshot.requestId());
                        if (request != null) {
                            request.completed(new ProtocolMessage.Result(
                                    RemoteProtocol.VERSION, snapshot.requestId(),
                                    snapshot.sequence(), snapshot.status(), snapshot.revision(),
                                    List.of(), Optional.empty(), snapshot.detail(), Optional.empty()));
                        }
                    }
                });
                result.terminalResult().ifPresent(terminal -> {
                    RemoteRequest request = request(terminal.requestId());
                    if (request != null) {
                        request.completed(terminal);
                    }
                });
                if (result.terminalResult().isEmpty()
                        && result.status() == ProtocolMessage.QueryStatus.EXPIRED
                        && result.request().isEmpty()
                        && waiter != null) {
                    waiter.targetRequestId().ifPresent(requestId -> {
                        RemoteRequest request = request(requestId);
                        if (request != null) {
                            request.expired(result.revision(), result.detail());
                        }
                    });
                }
                if (waiter != null) {
                    complete(waiter, result);
                }
            }
            case ProtocolMessage.Error error -> receiveError(error);
            default -> throw new IllegalStateException(
                    "handshake message is not legal after authentication");
        }
    }

    private void applyQuerySnapshot(
            ProtocolMessage.QueryResult result, ProtocolMessage.RequestSnapshot snapshot) {
        RemoteRequest request = request(snapshot.requestId());
        if (request == null) {
            return;
        }
        if (result.terminalResult().isEmpty()
                && snapshot.status() == ProtocolMessage.RemoteStatus.EXPIRED) {
            request.expired(snapshot.revision(), snapshot.detail());
        } else {
            request.status(snapshot.status());
        }
    }

    private void receiveError(ProtocolMessage.Error error) {
        RemoteOperationException failure = new RemoteOperationException(error.code(), error.detail());
        if (error.requestId().isPresent()) {
            RemoteRequest request = request(error.requestId().get());
            if (request != null) {
                request.failed(failure);
            }
        }
        UUID correlation = error.correlationId().orElse(null);
        if (correlation != null) {
            Waiter waiter = removeWaiter(correlation);
            if (waiter != null) {
                waiter.future.completeExceptionally(failure);
            }
        }
    }

    private void send(ProtocolMessage message) throws IOException {
        if (!connected || socket == null || output == null) {
            throw new IOException("remote client is disconnected");
        }
        synchronized (lifecycleLock) {
            if (!connected || output == null) {
                throw new IOException("remote client is disconnected");
            }
            ProtocolCodec.write(frames, output, message);
        }
    }

    private void sendDirect(OutputStream output, ProtocolMessage message) throws IOException {
        ProtocolCodec.write(frames, output, message);
    }

    private ProtocolMessage readDirect(
            Socket socket, InputStream input, long deadlineNanos)
            throws IOException, ProtocolException {
        Optional<byte[]> payload = frames.readFrame(input, socket, deadlineNanos);
        if (payload.isEmpty()) {
            throw new IOException("remote server closed during handshake");
        }
        return ProtocolCodec.decode(payload.orElseThrow());
    }

    private IOException handshakeFailure(ProtocolMessage message) {
        if (message instanceof ProtocolMessage.Error error) {
            return new RemoteOperationException(error.code(), error.detail());
        }
        return new IOException("unexpected message during handshake");
    }

    private void closeTransport(boolean terminal) {
        connected = false;
        Socket old = socket;
        socket = null;
        input = null;
        output = null;
        if (old != null) {
            try {
                old.close();
            } catch (IOException ignored) {
            }
        }
        Thread oldReader = reader;
        reader = null;
        if (terminal && oldReader != null && oldReader != Thread.currentThread()) {
            oldReader.interrupt();
        }
    }

    private void markRequestsDisconnected() {
        for (RemoteRequest request : requests.values()) {
            request.disconnected();
        }
    }

    private void failWaiters(IOException failure) {
        for (Waiter waiter : waiters.values()) {
            waiter.future.completeExceptionally(failure);
        }
        waiters.clear();
    }

    private void reconcileSequence(long lastSequence) {
        java.util.Iterator<Map.Entry<UUID, RemoteRequest>> iterator = requests.entrySet().iterator();
        IOException notAccepted = new IOException(
                "remote request was not accepted before the connection was replaced");
        while (iterator.hasNext()) {
            RemoteRequest request = iterator.next().getValue();
            if (request.sequence() > lastSequence) {
                if (!request.isTerminal()) {
                    request.failed(notAccepted);
                }
                iterator.remove();
            }
        }
        nextSequence = lastSequence == Long.MAX_VALUE ? 0 : lastSequence + 1;
    }

    static int timeoutMillis(java.time.Duration duration) {
        long millis = duration.getSeconds() * 1_000L;
        int nanos = duration.getNano();
        if (nanos != 0) {
            millis += (nanos + 999_999L) / 1_000_000L;
        }
        return Math.toIntExact(Math.max(1L, millis));
    }

    private RemoteRequest request(UUID id) {
        synchronized (lifecycleLock) {
            return requests.get(id);
        }
    }

    private Waiter removeWaiter(UUID id) {
        synchronized (lifecycleLock) {
            return waiters.remove(id);
        }
    }

    private void registerWaiter(UUID id, WaiterKind kind, CompletableFuture<?> future) {
        registerWaiter(id, kind, future, Optional.empty());
    }

    private void registerWaiter(
            UUID id, WaiterKind kind, CompletableFuture<?> future, Optional<UUID> targetRequestId) {
        int outstanding = waiters.size() + requests.size();
        if (targetRequestId.isPresent()
                && requests.containsKey(targetRequestId.orElseThrow())) {
            // A control/query waiter for a retained request must be able to
            // reach that request even when the request bound is full. The
            // target is already represented by this waiter, not an unrelated
            // outstanding operation.
            outstanding--;
        }
        if (outstanding >= options.maxOutstandingOperations()) {
            throw new IllegalStateException("remote operation bound exceeded");
        }
        waiters.put(id, new Waiter(kind, future,
                Objects.requireNonNull(targetRequestId, "targetRequestId")));
    }

    private void ensureOutstandingCapacity(Optional<UUID> targetRequestId) {
        boolean targetIsRetained = targetRequestId.isPresent()
                && requests.containsKey(targetRequestId.orElseThrow());
        int outstanding = requests.size() + waiters.size();
        if (targetIsRetained && outstanding < options.maxOutstandingOperations()) {
            return;
        }
        int terminalRequests = 0;
        for (RemoteRequest request : requests.values()) {
            if (request.isTerminal()) {
                terminalRequests++;
            }
        }
        while (requests.size() + waiters.size() >= options.maxOutstandingOperations()
                && terminalRequests > 0) {
            UUID remove = null;
            for (Map.Entry<UUID, RemoteRequest> entry : requests.entrySet()) {
                if (entry.getValue().isTerminal()) {
                    remove = entry.getKey();
                    break;
                }
            }
            if (remove == null) {
                break;
            }
            requests.remove(remove);
            terminalRequests--;
        }
        if (requests.size() + waiters.size() >= options.maxOutstandingOperations()
                && !(targetIsRetained
                && requests.size() + waiters.size() == options.maxOutstandingOperations()
                && requests.get(targetRequestId.orElseThrow()) != null)) {
            throw new IllegalStateException("remote operation bound exceeded");
        }
    }

    @SuppressWarnings("unchecked")
    private static void complete(Waiter waiter, ProtocolMessage message) {
        switch (waiter.kind) {
            case CANCEL -> ((CompletableFuture<ProtocolMessage.CancelResult>) waiter.future)
                    .complete((ProtocolMessage.CancelResult) message);
            case RESET -> ((CompletableFuture<ProtocolMessage.ResetResult>) waiter.future)
                    .complete((ProtocolMessage.ResetResult) message);
            case QUERY -> ((CompletableFuture<ProtocolMessage.QueryResult>) waiter.future)
                    .complete((ProtocolMessage.QueryResult) message);
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("remote client is closed");
        }
    }

    private void requireConnected() throws IOException {
        requireOpen();
        if (!connected) {
            throw new IOException("remote client is disconnected");
        }
    }

    private record Waiter(
            WaiterKind kind, CompletableFuture<?> future, Optional<UUID> targetRequestId) {
    }

    private enum WaiterKind {
        CANCEL,
        RESET,
        QUERY
    }
}
