package io.mindspice.lyra.repl.remote;

import io.mindspice.lyra.repl.EvaluationId;
import io.mindspice.lyra.repl.EvaluationRequest;
import io.mindspice.lyra.repl.EvaluationResult;
import io.mindspice.lyra.repl.SessionRevision;
import io.mindspice.lyra.repl.ValueSnapshot;
import io.mindspice.lyra.runtime.LyraOwnerController;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.RelatedSpan;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Authenticated loopback protocol service. Socket threads parse, validate and
 * enqueue bounded operations; the supplied owner dispatcher is the only path
 * that invokes the live session adapter.
 */
public final class RemoteServer implements AutoCloseable {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RemoteSessionAdapter session;
    private final OwnerDispatcher owner;
    private final RemoteServerOptions options;
    private final FrameCodec frames;
    private final ServerSocket serverSocket;
    private final TokenCredential credential;
    private final boolean temporaryCredential;
    private final UUID sessionId;
    private final Object stateLock = new Object();
    private final Map<UUID, RequestRecord> requests = new LinkedHashMap<>();
    private final Map<UUID, ControlResponse> controlResponses = new LinkedHashMap<>();
    private final Map<UUID, QueryRecord> queries = new LinkedHashMap<>();
    private final AtomicReference<Connection> controller = new AtomicReference<>();
    private final Map<Connection, Boolean> connections = new ConcurrentHashMap<>();
    private final Semaphore connectionSlots;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread acceptThread;
    private volatile long currentRevision;
    private long lastSequence;
    private RequestRecord activeRequest;
    private ControlRecord pendingControl;
    private QueryRecord pendingQuery;

    private RemoteServer(
            RemoteSessionAdapter session,
            OwnerDispatcher owner,
            RemoteServerOptions options,
            FrameCodec frames,
            ServerSocket serverSocket,
            TokenCredential credential,
            boolean temporaryCredential) throws IOException {
        this.session = session;
        this.owner = owner;
        this.options = options;
        this.frames = frames;
        this.serverSocket = serverSocket;
        this.credential = credential;
        this.temporaryCredential = temporaryCredential;
        this.sessionId = session.sessionId();
        SessionRevision revision = session.revision();
        if (revision.value() < 0) {
            throw new IllegalArgumentException("session revision must not be negative");
        }
        this.currentRevision = revision.value();
        this.connectionSlots = new Semaphore(options.maxConnections());
        this.acceptThread = new Thread(this::acceptLoop,
                "lyra-repl-remote-accept-" + serverSocket.getLocalPort());
        this.acceptThread.setDaemon(true);
    }

    /** Starts a server on loopback, using an ephemeral port by default. */
    public static RemoteServer open(RemoteSessionAdapter session, OwnerDispatcher owner)
            throws IOException {
        return open(session, owner, RemoteServerOptions.defaults());
    }

    /** Convenience embedding overload for the runtime owner controller. */
    public static RemoteServer open(RemoteSessionAdapter session, LyraOwnerController owner)
            throws IOException {
        return open(session, OwnerDispatcher.runtime(owner), RemoteServerOptions.defaults());
    }

    public static RemoteServer open(
            RemoteSessionAdapter session,
            LyraOwnerController owner,
            RemoteServerOptions options) throws IOException {
        return open(session, OwnerDispatcher.runtime(owner), options);
    }

    public static RemoteServer open(
            RemoteSessionAdapter session,
            OwnerDispatcher owner,
            RemoteServerOptions options) throws IOException {
        if (session == null || owner == null || options == null) {
            throw new NullPointerException("session, owner and options are required");
        }
        if (!owner.isOwnerThread()) {
            throw new IllegalStateException("remote server must be opened on the owner thread");
        }

        TokenCredential credential;
        boolean temporary = options.credentialFile().isEmpty();
        try {
            credential = temporary
                    ? TokenCredential.createTemporary()
                    : TokenCredential.create(options.credentialFile().orElseThrow());
        } catch (IOException failure) {
            throw failure;
        }
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(false);
            socket.bind(LoopbackEndpoint.bind(options.port()).socketAddress(),
                    options.maxConnections());
            RemoteServer server = new RemoteServer(session, owner, options,
                    new FrameCodec(options.maxFrameBytes()), socket, credential, temporary);
            server.acceptThread.start();
            return server;
        } catch (IOException | RuntimeException failure) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            credential.close();
            if (temporary) {
                deleteQuietly(credential.path());
            }
            throw failure;
        }
    }

    public RemoteEndpoint endpoint() {
        LoopbackEndpoint address = new LoopbackEndpoint(
                serverSocket.getInetAddress(), serverSocket.getLocalPort());
        return new RemoteEndpoint(address, credential.path(), sessionId);
    }

    public boolean isOpen() {
        return !closed.get();
    }

    public int authenticatedControllerCount() {
        return controller.get() == null ? 0 : 1;
    }

    /** Owner-side pump. It runs at most one queued live operation. */
    public boolean poll() {
        if (!owner.isOwnerThread()) {
            throw new IllegalStateException("remote polling must run on the owner thread");
        }
        if (closed.get()) {
            return false;
        }
        return owner.poll();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        for (Connection connection : connections.keySet()) {
            connection.closeTransport();
        }
        controller.set(null);
        RequestRecord requestToCancel;
        ControlRecord controlToCancel;
        QueryRecord queryToCancel;
        synchronized (stateLock) {
            requestToCancel = activeRequest;
            controlToCancel = pendingControl;
            queryToCancel = pendingQuery;
            activeRequest = null;
            pendingControl = null;
            pendingQuery = null;
            if (requestToCancel != null && !requestToCancel.terminal) {
                requestToCancel.cancellation.request();
                requestToCancel.status = ProtocolMessage.RemoteStatus.CANCELLATION_REQUESTED;
            }
        }
        if (requestToCancel != null) {
            cancelDispatch(requestToCancel);
            cancelAdapter(requestToCancel);
            requestToCancel.cancellation.complete();
        }
        if (controlToCancel != null) {
            cancelDispatch(controlToCancel.dispatch);
        }
        if (queryToCancel != null) {
            cancelDispatch(queryToCancel.dispatch);
        }
        credential.close();
        if (temporaryCredential && options.deleteTemporaryCredentialOnClose()) {
            deleteQuietly(credential.path());
        }
    }

    private void acceptLoop() {
        while (!closed.get()) {
            Socket socket = null;
            boolean slotAcquired = false;
            try {
                socket = serverSocket.accept();
                if (!connectionSlots.tryAcquire()) {
                    socket.close();
                    continue;
                }
                slotAcquired = true;
                Connection connection = new Connection(socket);
                connections.put(connection, Boolean.TRUE);
                Thread handler = new Thread(() -> handleConnection(connection),
                        "lyra-repl-remote-connection-" + connection.id);
                handler.setDaemon(true);
                handler.start();
            } catch (SocketException closedSocket) {
                if (slotAcquired) {
                    connectionSlots.release();
                }
                if (!closed.get()) {
                    // The listener failed unexpectedly; closing prevents a
                    // retry loop and makes the failure visible through isOpen.
                    close();
                }
                closeQuietly(socket);
                return;
            } catch (IOException failure) {
                if (slotAcquired) {
                    connectionSlots.release();
                }
                closeQuietly(socket);
                if (closed.get()) {
                    return;
                }
                // A transient accept failure must not create an unbounded
                // retry storm. The listener is a bounded optional service.
                close();
                return;
            }
        }
    }

    private void handleConnection(Connection connection) {
        try {
            long handshakeDeadline = System.nanoTime() + options.handshakeTimeout().toNanos();
            ProtocolMessage first = readHandshakeMessage(connection, handshakeDeadline);
            if (!(first instanceof ProtocolMessage.ClientHello hello)) {
                sendDirect(connection, error(ProtocolMessage.ErrorCode.INVALID_OPERATION,
                        "client hello is required", null, null, false));
                return;
            }
            if (hello.sessionId().isPresent() && !hello.sessionId().get().equals(sessionId)) {
                sendDirect(connection, error(ProtocolMessage.ErrorCode.SESSION_MISMATCH,
                        "endpoint belongs to another session", null, null, false));
                return;
            }

            byte[] challenge = new byte[RemoteProtocol.CHALLENGE_BYTES];
            RANDOM.nextBytes(challenge);
            try {
                sendDirect(connection, new ProtocolMessage.ServerHello(
                        RemoteProtocol.VERSION, sessionId, challenge));
                ProtocolMessage second = readHandshakeMessage(connection, handshakeDeadline);
                if (!(second instanceof ProtocolMessage.Authenticate authenticate)) {
                    sendDirect(connection, error(ProtocolMessage.ErrorCode.AUTHENTICATION_FAILED,
                            "authentication is required", null, null, false));
                    return;
                }
                boolean challengeMatches = MessageDigest.isEqual(challenge, authenticate.challenge());
                boolean sessionMatches = authenticate.sessionId().equals(sessionId);
                boolean tokenMatches = credential.matches(authenticate.token());
                if (!challengeMatches || !sessionMatches || !tokenMatches) {
                    sendDirect(connection, error(ProtocolMessage.ErrorCode.AUTHENTICATION_FAILED,
                            "authentication failed", null, null, false));
                    return;
                }
                if (!controller.compareAndSet(null, connection)) {
                    sendDirect(connection, error(ProtocolMessage.ErrorCode.CONTROLLER_BUSY,
                            "an authenticated controller is already connected", null, null, true));
                    return;
                }
                connection.authenticated.set(true);
                long revision;
                long sequence;
                Optional<UUID> active;
                synchronized (stateLock) {
                    revision = currentRevision;
                    sequence = lastSequence;
                    active = activeRequest == null
                            ? Optional.empty() : Optional.of(activeRequest.request.requestId());
                }
                connection.socket.setSoTimeout(0);
                sendDirect(connection, new ProtocolMessage.Authenticated(
                        RemoteProtocol.VERSION, sessionId, revision, sequence, active));
                connection.startWriter();
                readLoop(connection);
            } finally {
                java.util.Arrays.fill(challenge, (byte) 0);
            }
        } catch (SocketTimeoutException timeout) {
            if (connection.authenticated.get()) {
                send(connection, error(ProtocolMessage.ErrorCode.HANDSHAKE_TIMEOUT,
                        "connection timed out", null, null, true));
            } else {
                sendDirectQuietly(connection, error(ProtocolMessage.ErrorCode.HANDSHAKE_TIMEOUT,
                        "handshake timed out", null, null, true));
            }
        } catch (ProtocolException malformed) {
            if (connection.authenticated.get()) {
                send(connection, error(errorCode(malformed),
                        safeProtocolDetail(malformed), null, null, false));
            } else {
                sendDirectQuietly(connection, error(errorCode(malformed),
                        safeProtocolDetail(malformed), null, null, false));
            }
        } catch (IOException ignored) {
            // Connection teardown is handled below; no sensitive detail is sent.
        } finally {
            closeConnection(connection);
        }
    }

    private void readLoop(Connection connection) throws IOException, ProtocolException {
        while (!closed.get() && !connection.closed.get()) {
            ProtocolMessage message = readMessage(connection.input);
            if (message == null) {
                return;
            }
            switch (message) {
                case ProtocolMessage.EvaluateRequest request -> handleEvaluate(connection, request);
                case ProtocolMessage.CancelRequest request -> handleCancel(connection, request);
                case ProtocolMessage.ResetRequest request -> handleReset(connection, request);
                case ProtocolMessage.QueryRequest request -> handleQuery(connection, request);
                default -> {
                    send(connection, error(ProtocolMessage.ErrorCode.INVALID_OPERATION,
                            "message is not valid after authentication", null, null, false));
                    return;
                }
            }
        }
    }

    private void handleEvaluate(Connection connection, ProtocolMessage.EvaluateRequest request) {
        RequestRecord record;
        ProtocolMessage replay = null;
        ProtocolMessage immediate = null;
        synchronized (stateLock) {
            RequestRecord existing = requests.get(request.requestId());
            if (existing != null) {
                if (!existing.sameRequest(request)) {
                    immediate = error(ProtocolMessage.ErrorCode.INVALID_SCHEMA,
                            "request identity was reused with different contents",
                            request.requestId(), null, false);
                } else {
                    existing.connection = connection;
                    replay = existing.replay();
                }
            } else if (request.sequence() <= lastSequence) {
                immediate = error(ProtocolMessage.ErrorCode.REQUEST_EXPIRED,
                        "request sequence is expired or already consumed",
                        request.requestId(), null, false);
            } else if (request.sequence() != lastSequence + 1) {
                immediate = error(ProtocolMessage.ErrorCode.SEQUENCE_REJECTED,
                        "request sequence is not the next session sequence",
                        request.requestId(), null, false);
            } else {
                lastSequence = request.sequence();
                record = new RequestRecord(request, connection);
                requests.put(request.requestId(), record);
                evictRequestsLocked();
                if (request.revision().value() != currentRevision) {
                    immediate = finishLocked(record, terminalResult(record,
                            ProtocolMessage.RemoteStatus.REVISION_CONFLICT, currentRevision,
                            Optional.of("request revision does not match the session"),
                            Optional.empty(), Optional.empty()));
                } else if (activeRequest != null || pendingControl != null || pendingQuery != null) {
                    immediate = finishLocked(record, terminalResult(record,
                            ProtocolMessage.RemoteStatus.BUSY, currentRevision,
                            Optional.of("the owner is busy"), Optional.empty(),
                            activeRequest == null ? Optional.empty()
                                    : Optional.of(activeRequest.request.requestId())));
                } else {
                    activeRequest = record;
                    record.status = ProtocolMessage.RemoteStatus.QUEUED;
                    // Enqueue admission before dispatching owner work. The
                    // writer is FIFO, so RUNNING and terminal messages cannot
                    // overtake this response even if the owner polls immediately.
                    send(connection, new ProtocolMessage.Accepted(
                            RemoteProtocol.VERSION, request.requestId(), request.sequence(),
                            ProtocolMessage.RemoteStatus.QUEUED, currentRevision));
                    record.dispatch = dispatchEvaluation(record);
                    if (record.dispatch == null) {
                        immediate = finishLocked(record, terminalResult(record,
                                ProtocolMessage.RemoteStatus.BUSY, currentRevision,
                                Optional.of("the owner dispatcher is busy"), Optional.empty(),
                                Optional.empty()));
                    }
                }
            }
        }
        if (immediate != null) {
            send(connection, immediate);
        } else if (replay != null) {
            send(connection, replay);
        }
    }

    private OwnerDispatcher.Dispatch dispatchEvaluation(RequestRecord record) {
        try {
            OwnerDispatcher.Dispatch dispatch = owner.dispatch(() -> runEvaluation(record));
            if (dispatch == null
                    || dispatch.state() == OwnerDispatcher.DispatchState.CANCELLED
                    || dispatch.state() == OwnerDispatcher.DispatchState.CLOSED
                    || dispatch.state() == OwnerDispatcher.DispatchState.FAILED) {
                return null;
            }
            return dispatch;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private void runEvaluation(RequestRecord record) {
        synchronized (stateLock) {
            if (record.terminal || activeRequest != record) {
                return;
            }
            if (record.cancellation.isRequested()) {
                ProtocolMessage.Result cancelled = terminalResult(record,
                        ProtocolMessage.RemoteStatus.CANCELLED, currentRevision,
                        Optional.of("evaluation cancelled before execution"),
                        Optional.empty(), Optional.empty());
                finishLocked(record, cancelled);
                record.cancellation.complete();
                send(record.connection, cancelled);
                return;
            }
            record.status = ProtocolMessage.RemoteStatus.RUNNING;
            send(record.connection, new ProtocolMessage.Status(
                    RemoteProtocol.VERSION, record.request.requestId(), record.request.sequence(),
                    ProtocolMessage.RemoteStatus.RUNNING, currentRevision, Optional.empty()));
        }

        EvaluationResult evaluation;
        try {
            EvaluationRequest request = new EvaluationRequest(
                    EvaluationId.of(record.request.requestId()), record.request.revision(),
                    record.request.source());
            evaluation = session.evaluate(request, record.cancellation);
        } catch (RemoteSessionUnavailableException unavailable) {
            completeEvaluation(record, unavailable.getMessage(), null);
            return;
        } catch (RuntimeException failure) {
            completeEvaluation(record, "owner session operation is unavailable", null);
            return;
        } finally {
            record.cancellation.complete();
        }
        completeEvaluation(record, null, evaluation);
    }

    private void completeEvaluation(
            RequestRecord record, String unavailableDetail, EvaluationResult evaluation) {
        ProtocolMessage result;
        synchronized (stateLock) {
            if (record.terminal) {
                return;
            }
            long revision = currentRevision;
            if (evaluation != null) {
                revision = Math.max(revision, evaluation.revision().value());
                currentRevision = revision;
                try {
                    result = resultFor(record, evaluation);
                } catch (RuntimeException serializationFailure) {
                    result = terminalResult(record, ProtocolMessage.RemoteStatus.UNAVAILABLE,
                            revision, Optional.of("evaluation result was unavailable"),
                            Optional.empty(), Optional.empty());
                }
            } else {
                result = terminalResult(record, ProtocolMessage.RemoteStatus.UNAVAILABLE,
                        revision, Optional.ofNullable(unavailableDetail)
                                .or(() -> Optional.of("attached session is unavailable")),
                        Optional.empty(), Optional.empty());
            }
            result = finishLocked(record, result);
            // Terminal publication is serialized with cancellation-status
            // publication under stateLock. This prevents a stale nonterminal
            // status from following the terminal result on the wire.
            send(record.connection, result);
        }
    }

    private void handleCancel(Connection connection, ProtocolMessage.CancelRequest request) {
        ProtocolMessage cached;
        RequestRecord record;
        boolean pendingCancellation = false;
        synchronized (stateLock) {
            ControlResponse prior = cachedControlEntry(request.operationId());
            if (prior != null) {
                if (prior.kind() != ControlKind.CANCEL
                        || !prior.targetRequestId().equals(request.requestId())) {
                    send(connection, error(ProtocolMessage.ErrorCode.INVALID_SCHEMA,
                            "control identity was reused with different contents", null,
                            request.operationId(), false));
                } else {
                    send(connection, prior.response());
                }
                return;
            }
            record = requests.get(request.requestId());
            ProtocolMessage.CancelResult response;
            if (record == null) {
                response = new ProtocolMessage.CancelResult(RemoteProtocol.VERSION,
                        request.operationId(), request.requestId(), ProtocolMessage.ControlStatus.NOT_FOUND,
                        currentRevision, Optional.of("request is not retained"));
            } else if (record.terminal) {
                response = new ProtocolMessage.CancelResult(RemoteProtocol.VERSION,
                        request.operationId(), request.requestId(),
                        ProtocolMessage.ControlStatus.ALREADY_TERMINAL, currentRevision, Optional.empty());
            } else {
                record.cancellation.request();
                OwnerDispatcher.Dispatch dispatch = record.dispatch;
                if (dispatch != null && cancelPending(dispatch)) {
                    pendingCancellation = true;
                    finishLocked(record, terminalResult(record,
                            ProtocolMessage.RemoteStatus.CANCELLED, currentRevision,
                            Optional.of("evaluation cancelled before owner execution"),
                            Optional.empty(), Optional.empty()));
                    record.cancellation.complete();
                } else {
                    record.status = ProtocolMessage.RemoteStatus.CANCELLATION_REQUESTED;
                }
                response = new ProtocolMessage.CancelResult(RemoteProtocol.VERSION,
                        request.operationId(), request.requestId(),
                        ProtocolMessage.ControlStatus.REQUESTED, currentRevision,
                        Optional.of("cancellation requested cooperatively"));
            }
            rememberControlLocked(request.operationId(), response);
            cached = response;
        }
        if (record != null && !pendingCancellation) {
            cancelAdapter(record);
            synchronized (stateLock) {
                if (!record.terminal) {
                    send(record.connection, new ProtocolMessage.Status(
                            RemoteProtocol.VERSION, record.request.requestId(),
                            record.request.sequence(),
                            ProtocolMessage.RemoteStatus.CANCELLATION_REQUESTED,
                            currentRevision,
                            Optional.of("cancellation requested cooperatively")));
                }
            }
        }
        send(connection, cached);
        if (pendingCancellation && record != null) {
            send(connection, record.terminalResult);
        }
    }

    private void handleReset(Connection connection, ProtocolMessage.ResetRequest request) {
        ProtocolMessage cached;
        ControlRecord control;
        synchronized (stateLock) {
            ControlResponse prior = cachedControlEntry(request.operationId());
            if (prior != null) {
                if (prior.kind() != ControlKind.RESET
                        || prior.expectedRevision() != request.expectedRevision()) {
                    send(connection, error(ProtocolMessage.ErrorCode.INVALID_SCHEMA,
                            "control identity was reused with different contents", null,
                            request.operationId(), false));
                } else {
                    send(connection, prior.response());
                }
                return;
            }
            if (pendingControl != null
                    && pendingControl.operationId.equals(request.operationId())) {
                if (pendingControl.kind != ControlKind.RESET
                        || pendingControl.expectedRevision != request.expectedRevision()) {
                    send(connection, error(ProtocolMessage.ErrorCode.INVALID_SCHEMA,
                            "control identity was reused with different contents", null,
                            request.operationId(), false));
                }
                // Same-content duplicates share the canonical pending reset
                // result, which will be sent by the original operation.
                return;
            }
            if (request.expectedRevision() != currentRevision) {
                cached = new ProtocolMessage.ResetResult(RemoteProtocol.VERSION,
                        request.operationId(), ProtocolMessage.ControlStatus.REVISION_CONFLICT,
                        currentRevision, Optional.of("reset targets a stale session revision"));
                rememberControlLocked(request.operationId(), cached, request.expectedRevision());
                send(connection, cached);
                return;
            }
            if (activeRequest != null || pendingControl != null || pendingQuery != null) {
                cached = new ProtocolMessage.ResetResult(RemoteProtocol.VERSION,
                        request.operationId(), ProtocolMessage.ControlStatus.BUSY,
                        currentRevision, Optional.of("the owner is busy"));
                rememberControlLocked(request.operationId(), cached, request.expectedRevision());
                send(connection, cached);
                return;
            }
            control = new ControlRecord(request.operationId(), connection, ControlKind.RESET,
                    request.expectedRevision());
            pendingControl = control;
            try {
                control.dispatch = owner.dispatch(() -> runReset(control));
            } catch (RuntimeException failure) {
                pendingControl = null;
                cached = new ProtocolMessage.ResetResult(RemoteProtocol.VERSION,
                        request.operationId(), ProtocolMessage.ControlStatus.UNAVAILABLE,
                        currentRevision, Optional.of("owner dispatcher is unavailable"));
                rememberControlLocked(request.operationId(), cached, request.expectedRevision());
                send(connection, cached);
                return;
            }
            if (control.dispatch == null) {
                pendingControl = null;
                cached = new ProtocolMessage.ResetResult(RemoteProtocol.VERSION,
                        request.operationId(), ProtocolMessage.ControlStatus.BUSY,
                        currentRevision, Optional.of("owner dispatcher is busy"));
                rememberControlLocked(request.operationId(), cached, request.expectedRevision());
                send(connection, cached);
            } else if (pendingControl == control
                    && dispatchCannotProduceResult(control.dispatch)) {
                pendingControl = null;
                cached = new ProtocolMessage.ResetResult(RemoteProtocol.VERSION,
                        request.operationId(), ProtocolMessage.ControlStatus.UNAVAILABLE,
                        currentRevision, Optional.of("owner dispatcher is unavailable"));
                rememberControlLocked(request.operationId(), cached, request.expectedRevision());
                send(connection, cached);
            }
        }
    }

    private void runReset(ControlRecord control) {
        ProtocolMessage.ResetResult result;
        try {
            session.reset();
            long revision = session.revision().value();
            synchronized (stateLock) {
                currentRevision = Math.max(currentRevision, revision);
                result = new ProtocolMessage.ResetResult(RemoteProtocol.VERSION,
                        control.operationId, ProtocolMessage.ControlStatus.OK,
                        currentRevision, Optional.empty());
            }
        } catch (RemoteSessionUnavailableException unavailable) {
            result = new ProtocolMessage.ResetResult(RemoteProtocol.VERSION,
                    control.operationId, ProtocolMessage.ControlStatus.UNAVAILABLE,
                    currentRevision, Optional.ofNullable(unavailable.getMessage()));
        } catch (RuntimeException failure) {
            result = new ProtocolMessage.ResetResult(RemoteProtocol.VERSION,
                    control.operationId, ProtocolMessage.ControlStatus.UNAVAILABLE,
                    currentRevision, Optional.of("attached session reset is unavailable"));
        }
        synchronized (stateLock) {
            if (pendingControl == control) {
                pendingControl = null;
            }
            rememberControlLocked(control.operationId, result, control.expectedRevision);
        }
        send(control.connection, result);
    }

    private void handleQuery(Connection connection, ProtocolMessage.QueryRequest request) {
        QueryRecord query;
        ProtocolMessage.QueryResult immediate = null;
        synchronized (stateLock) {
            QueryRecord existing = queries.get(request.queryId());
            if (existing != null) {
                if (!existing.request.equals(request)) {
                    send(connection, error(ProtocolMessage.ErrorCode.INVALID_SCHEMA,
                            "query identity was reused with different contents", null,
                            request.queryId(), false));
                } else if (existing.result != null) {
                    send(connection, existing.result);
                } else {
                    send(connection, error(ProtocolMessage.ErrorCode.BUSY,
                            "query is already pending", null, request.queryId(), true));
                }
                return;
            }
            query = new QueryRecord(request, connection);
            queries.put(request.queryId(), query);
            evictQueriesLocked();
            switch (request.queryKind()) {
                case SESSION -> {
                    Optional<ProtocolMessage.RequestSnapshot> active = activeRequest == null
                            ? Optional.empty() : Optional.of(activeRequest.snapshot());
                    immediate = new ProtocolMessage.QueryResult(RemoteProtocol.VERSION,
                            request.queryId(), request.queryKind(), ProtocolMessage.QueryStatus.OK,
                            currentRevision, active,
                            activeRequest != null && activeRequest.terminal
                                    ? Optional.ofNullable(activeRequest.terminalResult) : Optional.empty(),
                            List.of(), Optional.empty(), Optional.empty());
                    query.result = immediate;
                }
                case REQUEST -> {
                    RequestRecord requested = requests.get(request.requestId().orElseThrow());
                    if (requested == null) {
                        immediate = new ProtocolMessage.QueryResult(RemoteProtocol.VERSION,
                                request.queryId(), request.queryKind(),
                                lastSequence > 0 ? ProtocolMessage.QueryStatus.EXPIRED
                                        : ProtocolMessage.QueryStatus.NOT_FOUND, currentRevision,
                                Optional.empty(), Optional.empty(), List.of(), Optional.empty(),
                                Optional.of("request is not retained"));
                    } else {
                        immediate = new ProtocolMessage.QueryResult(RemoteProtocol.VERSION,
                                request.queryId(), request.queryKind(), ProtocolMessage.QueryStatus.OK,
                                currentRevision, Optional.of(requested.snapshot()),
                                requested.terminal ? Optional.ofNullable(requested.terminalResult)
                                        : Optional.empty(), List.of(), Optional.empty(), Optional.empty());
                    }
                    query.result = immediate;
                }
                case BINDINGS, TYPE -> {
                    if (activeRequest != null || pendingControl != null || pendingQuery != null) {
                        immediate = new ProtocolMessage.QueryResult(RemoteProtocol.VERSION,
                                request.queryId(), request.queryKind(), ProtocolMessage.QueryStatus.BUSY,
                                currentRevision, Optional.empty(), Optional.empty(), List.of(),
                                Optional.empty(), Optional.of("the owner is busy"));
                        query.result = immediate;
                    } else {
                        pendingQuery = query;
                        try {
                            query.dispatch = owner.dispatch(() -> runQuery(query));
                        } catch (RuntimeException failure) {
                            pendingQuery = null;
                            immediate = unavailableQuery(query, "owner dispatcher is unavailable");
                            query.result = immediate;
                        }
                        if (query.dispatch == null && query.result == null) {
                            pendingQuery = null;
                            immediate = unavailableQuery(query, "owner dispatcher is busy");
                            query.result = immediate;
                        } else if (query.result == null && pendingQuery == query
                                && dispatchCannotProduceResult(query.dispatch)) {
                            pendingQuery = null;
                            immediate = unavailableQuery(query,
                                    "owner dispatcher is unavailable");
                            query.result = immediate;
                        }
                    }
                }
            }
        }
        if (immediate != null) {
            send(connection, immediate);
        }
    }

    private void runQuery(QueryRecord query) {
        RemoteQuery remoteQuery = switch (query.request.queryKind()) {
            case BINDINGS -> new RemoteQuery(RemoteQuery.Kind.BINDINGS, Optional.empty());
            case TYPE -> new RemoteQuery(RemoteQuery.Kind.TYPE, query.request.typeSource());
            default -> throw new IllegalStateException("owner query kind is not live");
        };
        RemoteQuery.Result answer;
        long observedRevision = currentRevision;
        try {
            answer = session.query(remoteQuery);
            observedRevision = session.revision().value();
        } catch (RuntimeException failure) {
            answer = RemoteQuery.Result.unavailable("attached session query is unavailable");
        }
        ProtocolMessage.QueryStatus status = switch (answer.status()) {
            case OK -> ProtocolMessage.QueryStatus.OK;
            case UNAVAILABLE -> ProtocolMessage.QueryStatus.UNAVAILABLE;
            case CLOSED -> ProtocolMessage.QueryStatus.CLOSED;
        };
        ProtocolMessage.QueryResult result;
        synchronized (stateLock) {
            currentRevision = Math.max(currentRevision, observedRevision);
            result = new ProtocolMessage.QueryResult(
                    RemoteProtocol.VERSION, query.request.queryId(), query.request.queryKind(), status,
                    currentRevision, Optional.empty(), Optional.empty(), answer.bindings(),
                    answer.inferredType(), answer.detail());
            query.result = result;
            if (pendingQuery == query) {
                pendingQuery = null;
            }
        }
        send(query.connection, result);
    }

    private ProtocolMessage.QueryResult unavailableQuery(QueryRecord query, String detail) {
        return new ProtocolMessage.QueryResult(RemoteProtocol.VERSION, query.request.queryId(),
                query.request.queryKind(), ProtocolMessage.QueryStatus.UNAVAILABLE,
                currentRevision, Optional.empty(), Optional.empty(), List.of(), Optional.empty(),
                Optional.of(detail));
    }

    private void cancelDispatch(RequestRecord record) {
        cancelPending(record.dispatch);
    }

    private void cancelDispatch(OwnerDispatcher.Dispatch dispatch) {
        cancelPending(dispatch);
    }

    private boolean cancelPending(OwnerDispatcher.Dispatch dispatch) {
        if (dispatch == null) {
            return false;
        }
        try {
            if (dispatch.state() != OwnerDispatcher.DispatchState.PENDING) {
                return false;
            }
            return dispatch.cancel();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean dispatchCannotProduceResult(OwnerDispatcher.Dispatch dispatch) {
        if (dispatch == null) {
            return true;
        }
        try {
            return switch (dispatch.state()) {
                case PENDING, RUNNING -> false;
                case COMPLETED, FAILED, CANCELLED, CLOSED -> true;
            };
        } catch (RuntimeException failure) {
            return true;
        }
    }

    private void cancelAdapter(RequestRecord record) {
        try {
            session.cancel(EvaluationId.of(record.request.requestId()));
        } catch (RuntimeException ignored) {
            // The token remains requested; the owner adapter may observe it.
        }
    }

    private void closeConnection(Connection connection) {
        if (!connection.closed.compareAndSet(false, true)) {
            return;
        }
        connection.closeTransport();
        connections.remove(connection);
        // Keep the controller lease while cancellation cleanup identifies and
        // retires work owned by this connection. A reconnect must not observe
        // half-cleaned request/control/query state.
        if (controller.get() == connection) {
            disconnectOwnedWork(connection);
            controller.compareAndSet(connection, null);
        }
        connectionSlots.release();
    }

    private void disconnectOwnedWork(Connection connection) {
        RequestRecord request;
        ControlRecord control;
        QueryRecord query;
        synchronized (stateLock) {
            request = activeRequest != null && activeRequest.connection == connection
                    ? activeRequest : null;
            control = pendingControl != null && pendingControl.connection == connection
                    ? pendingControl : null;
            query = pendingQuery != null && pendingQuery.connection == connection
                    ? pendingQuery : null;
            if (request != null && !request.terminal) {
                request.connection = null;
                request.cancellation.request();
                request.status = ProtocolMessage.RemoteStatus.CANCELLATION_REQUESTED;
            }
            if (control != null) {
                pendingControl = null;
            }
            if (query != null) {
                pendingQuery = null;
            }
        }
        if (request != null) {
            boolean pending = cancelPending(request.dispatch);
            cancelAdapter(request);
            if (pending) {
                synchronized (stateLock) {
                    if (!request.terminal) {
                        ProtocolMessage.Result cancelled = terminalResult(request,
                                ProtocolMessage.RemoteStatus.CANCELLED, currentRevision,
                                Optional.of("controller disconnected before owner execution"),
                                Optional.empty(), Optional.empty());
                        finishLocked(request, cancelled);
                        request.cancellation.complete();
                    }
                }
            }
        }
        if (control != null) {
            cancelDispatch(control.dispatch);
            ProtocolMessage.ResetResult cancelled = new ProtocolMessage.ResetResult(
                    RemoteProtocol.VERSION, control.operationId,
                    ProtocolMessage.ControlStatus.CANCELLED, currentRevision,
                    Optional.of("controller disconnected"));
            synchronized (stateLock) {
                rememberControlLocked(control.operationId, cancelled, control.expectedRevision);
            }
        }
        if (query != null) {
            cancelDispatch(query.dispatch);
            query.result = unavailableQuery(query, "controller disconnected");
        }
    }

    private ProtocolMessage readMessage(InputStream input) throws IOException, ProtocolException {
        Optional<byte[]> payload = frames.readFrame(input);
        return payload.isEmpty() ? null : ProtocolCodec.decode(payload.orElseThrow());
    }

    /**
     * Reads handshake bytes with a single deadline. A read is deliberately
     * bounded in a loop rather than delegated to readNBytes so a peer that
     * drips one byte at a time cannot renew the socket timeout indefinitely.
     */
    private ProtocolMessage readHandshakeMessage(
            Connection connection, long deadlineNanos) throws IOException, ProtocolException {
        byte[] header = new byte[RemoteProtocol.FRAME_HEADER_BYTES];
        int headerLength = readHandshakeBytes(connection, header, deadlineNanos);
        if (headerLength == 0) {
            return null;
        }
        if (headerLength != header.length) {
            throw new ProtocolException(ProtocolException.Reason.END_OF_FRAME,
                    "truncated frame header");
        }
        int length = ByteBuffer.wrap(header).getInt();
        if (length <= 0) {
            throw new ProtocolException(ProtocolException.Reason.INVALID_FRAME_LENGTH,
                    "frame length must be positive");
        }
        if (length > frames.maxFrameBytes()) {
            throw new ProtocolException(ProtocolException.Reason.FRAME_TOO_LARGE,
                    "frame exceeds configured bound");
        }
        byte[] payload = new byte[length];
        int payloadLength = readHandshakeBytes(connection, payload, deadlineNanos);
        if (payloadLength != length) {
            throw new ProtocolException(ProtocolException.Reason.END_OF_FRAME,
                    "truncated frame payload");
        }
        return ProtocolCodec.decode(payload);
    }

    private static int readHandshakeBytes(
            Connection connection, byte[] target, long deadlineNanos) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            connection.socket.setSoTimeout(remainingTimeoutMillis(deadlineNanos));
            int read = connection.input.read(target, offset, target.length - offset);
            if (read < 0) {
                return offset;
            }
            if (read == 0) {
                continue;
            }
            offset += read;
            if (System.nanoTime() >= deadlineNanos) {
                throw new SocketTimeoutException("handshake deadline exceeded");
            }
        }
        return offset;
    }

    private static int remainingTimeoutMillis(long deadlineNanos) throws SocketTimeoutException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw new SocketTimeoutException("handshake deadline exceeded");
        }
        long millis = (remaining + 999_999L) / 1_000_000L;
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, Math.max(1L, millis)));
    }

    private void send(Connection connection, ProtocolMessage message) {
        if (connection == null || connection.closed.get()) {
            return;
        }
        try {
            ProtocolMessage bounded = switch (message) {
                case ProtocolMessage.Result result -> fitResult(result);
                case ProtocolMessage.QueryResult result -> fitQueryResult(result);
                default -> message;
            };
            connection.enqueue(bounded);
        } catch (RuntimeException failure) {
            closeConnectionAsync(connection);
        }
    }

    private void closeConnectionAsync(Connection connection) {
        if (!connection.cleanupScheduled.compareAndSet(false, true)) {
            return;
        }
        connection.closeTransport();
        Thread cleanup = new Thread(() -> closeConnection(connection),
                "lyra-repl-remote-close-" + connection.id);
        cleanup.setDaemon(true);
        cleanup.start();
    }

    private void sendDirect(Connection connection, ProtocolMessage message) throws IOException {
        if (connection.closed.get()) {
            return;
        }
        ProtocolCodec.write(frames, connection.output, message);
    }

    private void sendDirectQuietly(Connection connection, ProtocolMessage message) {
        try {
            sendDirect(connection, message);
        } catch (IOException ignored) {
        }
    }

    private ProtocolMessage.Error error(
            ProtocolMessage.ErrorCode code,
            String detail,
            UUID requestId,
            UUID correlationId,
            boolean retryable) {
        return new ProtocolMessage.Error(RemoteProtocol.VERSION, code,
                detail == null || detail.isBlank() ? "protocol operation rejected" : detail,
                Optional.ofNullable(requestId), Optional.ofNullable(correlationId), retryable);
    }

    private ProtocolMessage.ErrorCode errorCode(ProtocolException exception) {
        return switch (exception.reason()) {
            case FRAME_TOO_LARGE -> ProtocolMessage.ErrorCode.FRAME_TOO_LARGE;
            case UNSUPPORTED_VERSION -> ProtocolMessage.ErrorCode.UNSUPPORTED_VERSION;
            case AUTHENTICATION_FAILED -> ProtocolMessage.ErrorCode.AUTHENTICATION_FAILED;
            case HANDSHAKE_TIMEOUT -> ProtocolMessage.ErrorCode.HANDSHAKE_TIMEOUT;
            default -> ProtocolMessage.ErrorCode.MALFORMED_MESSAGE;
        };
    }

    private String safeProtocolDetail(ProtocolException exception) {
        return switch (exception.reason()) {
            case FRAME_TOO_LARGE -> "frame exceeds configured bound";
            case UNSUPPORTED_VERSION -> "unsupported protocol version";
            default -> "malformed protocol message";
        };
    }

    private ProtocolMessage.Result resultFor(RequestRecord record, EvaluationResult evaluation) {
        ProtocolMessage.RemoteStatus status = switch (evaluation.status()) {
            case SUCCESS -> ProtocolMessage.RemoteStatus.SUCCESS;
            case COMPILATION_FAILURE -> ProtocolMessage.RemoteStatus.COMPILATION_FAILURE;
            case RUNTIME_FAILURE -> ProtocolMessage.RemoteStatus.RUNTIME_FAILURE;
            case CANCELLED -> ProtocolMessage.RemoteStatus.CANCELLED;
            case BUSY -> ProtocolMessage.RemoteStatus.BUSY;
            case CLOSED -> ProtocolMessage.RemoteStatus.CLOSED;
        };
        var runtimeDiagnostics = evaluation instanceof EvaluationResult.RuntimeFailure failure
                ? failure.frames().stream().map(frame ->
                        io.mindspice.lyra.repl.ConsoleSession.DiagnosticInfo.from(failure, frame))
                        .map(RemoteServer::diagnostic)
                : java.util.stream.Stream.<ProtocolMessage.Diagnostic>empty();
        List<ProtocolMessage.Diagnostic> diagnostics = java.util.stream.Stream.concat(
                        runtimeDiagnostics, evaluation.diagnostics().stream().map(RemoteServer::diagnostic))
                .limit(options.maxDiagnostics()).toList();
        Optional<ProtocolMessage.ValueSnapshot> value = evaluation.value().map(RemoteServer::value);
        Optional<String> summary = evaluation.failureSummary();
        Optional<UUID> active = evaluation instanceof EvaluationResult.Busy busy
                ? busy.activeEvaluationId().map(valueId -> valueId.value()) : Optional.empty();
        return terminalResult(record, status, evaluation.revision().value(), summary,
                value, active, diagnostics);
    }

    private static ProtocolMessage.Diagnostic diagnostic(io.mindspice.lyra.repl.ConsoleSession.DiagnosticInfo value) {
        var primary = value.primarySpan();
        return new ProtocolMessage.Diagnostic(value.code(), value.severity(),
                bounded(value.summary(), RemoteProtocol.MAX_DIAGNOSTIC_CHARACTERS),
                new ProtocolMessage.Span(bounded(primary.sourceId(), 4096), primary.startOffset(), primary.endOffset()),
                value.relatedSpans().stream().map(related -> new ProtocolMessage.RelatedSpan(
                        new ProtocolMessage.Span(bounded(related.span().sourceId(), 4096),
                                related.span().startOffset(), related.span().endOffset()), related.label())).toList());
    }

    private static ProtocolMessage.Diagnostic diagnostic(Diagnostic value) {
        List<ProtocolMessage.RelatedSpan> related = value.relatedSpans().stream()
                .limit(32)
                .map(RemoteServer::relatedSpan)
                .toList();
        return new ProtocolMessage.Diagnostic(value.code().value(), value.severity().name(),
                bounded(value.summary(), RemoteProtocol.MAX_DIAGNOSTIC_CHARACTERS),
                span(value.primarySpan()), related);
    }

    private static ProtocolMessage.RelatedSpan relatedSpan(RelatedSpan value) {
        return new ProtocolMessage.RelatedSpan(span(value.span()),
                bounded(value.label(), 1024));
    }

    private static ProtocolMessage.Span span(io.mindspice.lyra.compiler.source.SourceSpan value) {
        return new ProtocolMessage.Span(bounded(value.sourceId().toString(), 4096),
                value.startOffset(), value.endOffset());
    }

    private static ProtocolMessage.ValueSnapshot value(ValueSnapshot value) {
        return new ProtocolMessage.ValueSnapshot(value.canonicalType(), valueData(value.data()));
    }

    private static ProtocolMessage.ValueData valueData(ValueSnapshot.Data data) {
        return switch (data) {
            case ValueSnapshot.Nil ignored -> new ProtocolMessage.Nil();
            case ValueSnapshot.Unit ignored -> new ProtocolMessage.Unit();
            case ValueSnapshot.Scalar scalar -> new ProtocolMessage.Scalar(
                    scalar.kind().name(), scalar.value());
            case ValueSnapshot.Aggregate aggregate -> new ProtocolMessage.Aggregate(
                    aggregate.kind().name(), aggregate.identity(), aggregate.alias(),
                    aggregate.elements().stream().map(RemoteServer::value).toList(),
                    aggregate.truncation().map(Enum::name));
            case ValueSnapshot.Function function -> new ProtocolMessage.Function(
                    function.identity(), function.description());
            case ValueSnapshot.Reference reference -> new ProtocolMessage.Reference(
                    reference.description());
            case ValueSnapshot.Truncated truncated -> new ProtocolMessage.Truncated(
                    truncated.reason().name(), truncated.description());
        };
    }

    private static ProtocolMessage.Result terminalResult(
            RequestRecord record,
            ProtocolMessage.RemoteStatus status,
            long revision,
            Optional<String> summary,
            Optional<ProtocolMessage.ValueSnapshot> value,
            Optional<UUID> active) {
        return terminalResult(record, status, revision, summary, value, active, List.of());
    }

    private static ProtocolMessage.Result terminalResult(
            RequestRecord record,
            ProtocolMessage.RemoteStatus status,
            long revision,
            Optional<String> summary,
            Optional<ProtocolMessage.ValueSnapshot> value,
            Optional<UUID> active,
            List<ProtocolMessage.Diagnostic> diagnostics) {
        return new ProtocolMessage.Result(RemoteProtocol.VERSION,
                record.request.requestId(), record.request.sequence(), status,
                Math.max(0L, revision), diagnostics, value, summary, active);
    }

    private ProtocolMessage finishLocked(RequestRecord record, ProtocolMessage result) {
        if (result instanceof ProtocolMessage.Result terminal) {
            result = fitResult(terminal);
        }
        record.terminal = true;
        record.status = result instanceof ProtocolMessage.Result terminal
                ? terminal.status() : record.status;
        record.revision = result instanceof ProtocolMessage.Result terminal
                ? terminal.revision() : currentRevision;
        record.terminalResult = result instanceof ProtocolMessage.Result terminal ? terminal : null;
        if (activeRequest == record) {
            activeRequest = null;
        }
        evictRequestsLocked();
        return result;
    }

    private ControlResponse cachedControlEntry(UUID operationId) {
        return controlResponses.get(operationId);
    }

    private void rememberControlLocked(UUID operationId, ProtocolMessage response) {
        if (response instanceof ProtocolMessage.CancelResult cancel) {
            rememberControlLocked(operationId, response, ControlKind.CANCEL,
                    cancel.requestId(), -1L);
        } else {
            rememberControlLocked(operationId, response, ControlKind.RESET,
                    null, -1L);
        }
    }

    private void rememberControlLocked(UUID operationId, ProtocolMessage response,
                                        long expectedRevision) {
        ControlKind kind = response instanceof ProtocolMessage.CancelResult
                ? ControlKind.CANCEL : ControlKind.RESET;
        UUID target = response instanceof ProtocolMessage.CancelResult cancel
                ? cancel.requestId() : null;
        rememberControlLocked(operationId, response, kind, target, expectedRevision);
    }

    private void rememberControlLocked(UUID operationId, ProtocolMessage response,
                                        ControlKind kind, UUID targetRequestId,
                                        long expectedRevision) {
        controlResponses.put(operationId, new ControlResponse(
                kind, targetRequestId, expectedRevision, response));
        while (controlResponses.size() > options.maxRetainedResults()) {
            UUID first = controlResponses.keySet().iterator().next();
            controlResponses.remove(first);
        }
    }

    private void evictRequestsLocked() {
        while (requests.size() > options.maxRetainedResults()) {
            UUID remove = null;
            for (Map.Entry<UUID, RequestRecord> entry : requests.entrySet()) {
                if (entry.getValue().terminal && entry.getValue() != activeRequest) {
                    remove = entry.getKey();
                    break;
                }
            }
            if (remove == null) {
                return;
            }
            requests.remove(remove);
        }
    }

    private void evictQueriesLocked() {
        while (queries.size() > options.maxRetainedResults()) {
            UUID remove = null;
            for (Map.Entry<UUID, QueryRecord> entry : queries.entrySet()) {
                if (entry.getValue().result != null && entry.getValue() != pendingQuery) {
                    remove = entry.getKey();
                    break;
                }
            }
            if (remove == null) {
                return;
            }
            queries.remove(remove);
        }
    }

    private ProtocolMessage.Result fitResult(ProtocolMessage.Result result) {
        try {
            if (ProtocolCodec.encode(result).length <= options.maxFrameBytes()) {
                return result;
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to a data-free terminal result.
        }
        ProtocolMessage.Result reduced = new ProtocolMessage.Result(
                RemoteProtocol.VERSION, result.requestId(), result.sequence(), result.status(),
                result.revision(), List.of(), Optional.empty(),
                result.failureSummary().map(value -> bounded(value, 512)),
                result.activeRequestId());
        try {
            if (ProtocolCodec.encode(reduced).length <= options.maxFrameBytes()) {
                return reduced;
            }
        } catch (IllegalArgumentException ignored) {
        }
        return new ProtocolMessage.Result(
                RemoteProtocol.VERSION, result.requestId(), result.sequence(), result.status(),
                result.revision(), List.of(), Optional.empty(), Optional.empty(),
                result.activeRequestId());
    }

    private ProtocolMessage.QueryResult fitQueryResult(ProtocolMessage.QueryResult result) {
        if (fitsFrame(result)) {
            return result;
        }

        Optional<ProtocolMessage.Result> terminal = result.terminalResult().map(this::fitResult);
        Optional<ProtocolMessage.RequestSnapshot> request = result.request()
                .map(RemoteServer::boundedRequestSnapshot);
        Optional<String> inferredType = result.inferredType()
                .map(value -> bounded(value, 512));
        Optional<String> detail = result.detail().map(value -> bounded(value, 512));
        List<RemoteBinding> bindings = result.bindings();
        for (int count = bindings.size() - 1; count >= 0; count--) {
            ProtocolMessage.QueryResult reduced = queryVariant(result, request, terminal,
                    bindings.subList(0, count), inferredType, detail);
            if (fitsFrame(reduced)) {
                return reduced;
            }
        }

        ProtocolMessage.QueryResult withoutTerminal = queryVariant(result, request,
                Optional.empty(), List.of(), inferredType, detail);
        if (fitsFrame(withoutTerminal)) {
            return withoutTerminal;
        }
        if (request.isPresent()) {
            ProtocolMessage.QueryResult requestOnly = queryVariant(result, request,
                    Optional.empty(), List.of(), Optional.empty(), Optional.empty());
            if (fitsFrame(requestOnly)) {
                return requestOnly;
            }
        }
        ProtocolMessage.QueryResult withoutRequest = queryVariant(result, Optional.empty(),
                Optional.empty(), List.of(), inferredType, detail);
        if (fitsFrame(withoutRequest)) {
            return withoutRequest;
        }
        ProtocolMessage.QueryResult withBoundedDetail = queryVariant(result, Optional.empty(),
                Optional.empty(), List.of(), Optional.empty(),
                Optional.of("query result was bounded to the transport frame limit"));
        if (fitsFrame(withBoundedDetail)) {
            return withBoundedDetail;
        }
        ProtocolMessage.QueryResult minimal = queryVariant(result, Optional.empty(),
                Optional.empty(), List.of(), Optional.empty(), Optional.empty());
        if (!fitsFrame(minimal)) {
            throw new IllegalStateException(
                    "configured frame bound cannot encode a query result");
        }
        return minimal;
    }

    private static ProtocolMessage.QueryResult queryVariant(
            ProtocolMessage.QueryResult source,
            Optional<ProtocolMessage.RequestSnapshot> request,
            Optional<ProtocolMessage.Result> terminal,
            List<RemoteBinding> bindings,
            Optional<String> inferredType,
            Optional<String> detail) {
        return new ProtocolMessage.QueryResult(RemoteProtocol.VERSION, source.queryId(),
                source.queryKind(), source.status(), source.revision(), request, terminal,
                bindings, inferredType, detail);
    }

    private static ProtocolMessage.RequestSnapshot boundedRequestSnapshot(
            ProtocolMessage.RequestSnapshot snapshot) {
        return new ProtocolMessage.RequestSnapshot(snapshot.requestId(), snapshot.sequence(),
                snapshot.status(), snapshot.revision(), snapshot.detail()
                        .map(value -> bounded(value, 512)));
    }

    private boolean fitsFrame(ProtocolMessage message) {
        try {
            return ProtocolCodec.encode(message).length <= options.maxFrameBytes();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String bounded(String value, int maximum) {
        if (value.length() <= maximum) {
            return value;
        }
        int end = maximum - 1;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + "…";
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private final class Connection {
        private static long nextId;
        private final long id = ++nextId;
        private final Socket socket;
        private final InputStream input;
        private final OutputStream output;
        private final ArrayBlockingQueue<byte[]> outbound =
                new ArrayBlockingQueue<>(options.maxOutboundMessages());
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean cleanupScheduled = new AtomicBoolean();
        private final AtomicBoolean authenticated = new AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicInteger outboundBytes =
                new java.util.concurrent.atomic.AtomicInteger();
        private Thread writer;

        private Connection(Socket socket) throws IOException {
            this.socket = socket;
            socket.setTcpNoDelay(true);
            this.input = socket.getInputStream();
            this.output = socket.getOutputStream();
        }

        private void startWriter() {
            writer = new Thread(this::writeLoop, "lyra-repl-remote-writer-" + id);
            writer.setDaemon(true);
            writer.start();
        }

        private void enqueue(ProtocolMessage message) {
            byte[] payload = ProtocolCodec.encode(message);
            int size = payload.length;
            while (true) {
                int current = outboundBytes.get();
                if (size > options.maxOutboundBytes()
                        || current > options.maxOutboundBytes() - size) {
                    throw new IllegalStateException("connection outbound bound exceeded");
                }
                if (outboundBytes.compareAndSet(current, current + size)) {
                    break;
                }
            }
            if (!outbound.offer(payload)) {
                outboundBytes.addAndGet(-size);
                throw new IllegalStateException("connection outbound queue is full");
            }
        }

        private void writeLoop() {
            try {
                while (!closed.get()) {
                    byte[] payload = outbound.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (payload == null) {
                        continue;
                    }
                    outboundBytes.addAndGet(-payload.length);
                    frames.writeFrame(output, payload);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException failure) {
                closeConnection(this);
            }
        }

        private void closeTransport() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            outbound.clear();
            outboundBytes.set(0);
        }
    }

    private static final class RequestRecord {
        private final ProtocolMessage.EvaluateRequest request;
        private final RemoteCancellation cancellation = new RemoteCancellation();
        private volatile Connection connection;
        private volatile OwnerDispatcher.Dispatch dispatch;
        private volatile ProtocolMessage.RemoteStatus status = ProtocolMessage.RemoteStatus.QUEUED;
        private volatile long revision;
        private volatile boolean terminal;
        private volatile ProtocolMessage.Result terminalResult;

        private RequestRecord(ProtocolMessage.EvaluateRequest request, Connection connection) {
            this.request = request;
            this.connection = connection;
            this.revision = request.revision().value();
        }

        private boolean sameRequest(ProtocolMessage.EvaluateRequest other) {
            return request.sequence() == other.sequence()
                    && request.revision().equals(other.revision())
                    && request.source().equals(other.source());
        }

        private ProtocolMessage replay() {
            if (terminalResult != null) {
                return terminalResult;
            }
            if (status == ProtocolMessage.RemoteStatus.CANCELLATION_REQUESTED) {
                return new ProtocolMessage.Status(RemoteProtocol.VERSION, request.requestId(),
                        request.sequence(), status, revision,
                        Optional.of("cancellation requested cooperatively"));
            }
            return new ProtocolMessage.Accepted(RemoteProtocol.VERSION, request.requestId(),
                    request.sequence(), status, revision);
        }

        private ProtocolMessage.RequestSnapshot snapshot() {
            return new ProtocolMessage.RequestSnapshot(request.requestId(), request.sequence(),
                    status, revision, Optional.empty());
        }
    }

    private static final class ControlRecord {
        private final UUID operationId;
        private final Connection connection;
        private final ControlKind kind;
        private final long expectedRevision;
        private OwnerDispatcher.Dispatch dispatch;

        private ControlRecord(UUID operationId, Connection connection, ControlKind kind,
                              long expectedRevision) {
            this.operationId = operationId;
            this.connection = connection;
            this.kind = kind;
            this.expectedRevision = expectedRevision;
        }
    }

    private enum ControlKind {
        CANCEL,
        RESET
    }

    private static final class QueryRecord {
        private final ProtocolMessage.QueryRequest request;
        private final Connection connection;
        private OwnerDispatcher.Dispatch dispatch;
        private ProtocolMessage.QueryResult result;

        private QueryRecord(ProtocolMessage.QueryRequest request, Connection connection) {
            this.request = request;
            this.connection = connection;
        }
    }

    private record ControlResponse(
            ControlKind kind,
            UUID targetRequestId,
            long expectedRevision,
            ProtocolMessage response) {
    }

}
