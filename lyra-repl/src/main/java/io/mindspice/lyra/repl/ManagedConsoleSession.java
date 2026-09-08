package io.mindspice.lyra.repl;

import io.mindspice.lyra.runtime.LyraLifecycleException;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Internal managed-console adapter for the local console surface.
 *
 * <p>The adapter owns one {@link LyraSession} that is created, executed and
 * closed on a single dedicated owner thread, so a blocked or long-running
 * evaluation can still be cancelled cooperatively from the console control
 * thread. The public synchronous {@code LyraSession} API and its terminal
 * {@link EvaluationResult} shapes are unchanged for opening-thread Java
 * callers; this class only adds the owner loop, one-operation admission and
 * identity-specific cross-thread cancellation required by interactive
 * consoles. There is no generic executor or plugin API.</p>
 *
 * <p>Admission is deliberately one-operation-at-a-time. A second evaluate,
 * reset or query while an operation is admitted returns the corresponding
 * BUSY outcome instead of queueing behind it, so no double lease or nested
 * evaluation can begin. The active evaluation identity is published before
 * the owner starts executing it, so a control thread (for example a Ctrl-C
 * handler) can request cancellation for that exact identity; a stale
 * identity can never affect later work. Source acquisition stays on the
 * console caller's thread and shares the configured input owner with
 * generated program input; compile, type, reload, snapshot extraction and
 * teardown all run on the owner thread.</p>
 */
public final class ManagedConsoleSession implements ConsoleSession, AutoCloseable {
    private static final Operation SHUTDOWN = new ShutdownOperation();

    private final SessionOptions options;
    private final BlockingQueue<Operation> queue = new ArrayBlockingQueue<>(1);
    private final Object admission = new Object();
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch ownerExited = new CountDownLatch(1);

    /** Created and closed on the owner thread; safely published through the start latch. */
    private volatile LyraSession session;
    private volatile Throwable startupFailure;
    private volatile Throwable ownerFailure;
    private volatile Throwable closeFailure;
    /** Guarded by {@link #admission}. */
    private boolean closed;
    /** Guarded by {@link #admission}; the one admitted operation, if any. */
    private Operation active;
    private Thread owner;

    private ManagedConsoleSession(SessionOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    /** Opens a managed console with the default session options. */
    public static ManagedConsoleSession open() {
        return open(SessionOptions.defaults());
    }

    /** Opens a managed console whose session lives on one dedicated owner thread. */
    public static ManagedConsoleSession open(SessionOptions options) {
        ManagedConsoleSession console = new ManagedConsoleSession(options);
        console.startOwner();
        return console;
    }

    private void startOwner() {
        owner = new Thread(this::ownerLoop, "lyra-managed-console-owner");
        owner.setDaemon(true);
        owner.start();
        try {
            if (!started.await(30, TimeUnit.SECONDS)) {
                throw new LyraLifecycleException("the managed console owner did not start");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new LyraLifecycleException(
                    "interrupted while starting the managed console owner", List.of(), failure);
        }
        Throwable failure = startupFailure;
        if (failure != null) {
            rethrow(failure);
        }
    }

    private void ownerLoop() {
        try {
            session = LyraSession.open(options);
            started.countDown();
            while (true) {
                Operation operation = queue.take();
                if (operation == SHUTDOWN) {
                    break;
                }
                runOperation(operation);
            }
            try {
                session.close();
            } catch (Throwable failure) {
                closeFailure = failure;
            }
        } catch (Throwable failure) {
            if (session == null) {
                startupFailure = failure;
                started.countDown();
            } else {
                ownerFailure = failure;
                try {
                    session.close();
                } catch (Throwable close) {
                    closeFailure = close;
                }
            }
        } finally {
            // No caller may remain blocked forever if the owner exits
            // unexpectedly; every stranded operation gets a terminal failure.
            Operation stranded;
            while ((stranded = queue.poll()) != null) {
                if (stranded == SHUTDOWN) {
                    continue;
                }
                stranded.fail(ownerFailure != null ? ownerFailure
                        : new LyraLifecycleException("the managed console owner exited"));
                synchronized (admission) {
                    if (active == stranded) {
                        active = null;
                    }
                }
                stranded.complete();
            }
            ownerExited.countDown();
        }
    }

    private void runOperation(Operation operation) {
        try {
            operation.execute(session);
        } catch (Throwable failure) {
            operation.fail(failure);
        }
        synchronized (admission) {
            if (active == operation) {
                active = null;
            }
        }
        operation.complete();
    }

    @Override
    public SessionRevision revision() {
        LyraSession current = session;
        return current == null ? SessionRevision.initial() : current.currentRevision();
    }

    /** Returns whether this managed console has been closed. */
    public boolean isClosed() {
        synchronized (admission) {
            return closed;
        }
    }

    /**
     * Publishes the identity of the admitted evaluation before the owner
     * starts executing it. Console control threads read this before
     * requesting Ctrl-C cancellation so the cancel always binds to the exact
     * active operation.
     */
    public Optional<EvaluationId> activeEvaluationId() {
        synchronized (admission) {
            if (active instanceof EvaluateOperation evaluation) {
                return Optional.of(evaluation.request.evaluationId());
            }
            return Optional.empty();
        }
    }

    /**
     * The configured source-discovery roots for execution-host file
     * listing. Read-only configuration data; never live session state.
     */
    public List<Path> sourceRoots() {
        return options.sourceRoots();
    }

    /**
     * Owner-routed submission carrying the caller's exact evaluation
     * identity, base revision and admission-time cancellation probe. This is
     * the narrow seam used by owner adapters (for example the remote
     * protocol) that must correlate cross-thread cancellation to one exact
     * admitted operation.
     */
    public EvaluationResult submit(EvaluationRequest request,
                                   BooleanSupplier cancellationRequested) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        EvaluateOperation operation;
        synchronized (admission) {
            if (closed) {
                return closedEvaluationResult(request);
            }
            if (active != null) {
                return busyEvaluationResult(request);
            }
            operation = new EvaluateOperation(request, cancellationRequested);
            active = operation;
        }
        enqueue(operation);
        operation.awaitTerminal();
        return operation.rawResult;
    }

    private EvaluationResult closedEvaluationResult(EvaluationRequest request) {
        return new EvaluationResult.Closed(request, revision(),
                SessionLifecycleState.CLOSED);
    }

    private EvaluationResult busyEvaluationResult(EvaluationRequest request) {
        return new EvaluationResult.Busy(request, revision(), Optional.empty());
    }

    @Override
    public Evaluation evaluate(EvaluationSource source) {
        Objects.requireNonNull(source, "source");
        EvaluateOperation operation;
        synchronized (admission) {
            if (closed) {
                return closedEvaluation(request(source));
            }
            if (active != null) {
                return busyEvaluation(request(source), active);
            }
            operation = new EvaluateOperation(request(source));
            active = operation;
        }
        enqueue(operation);
        operation.awaitTerminal();
        return operation.result;
    }

    private void enqueue(Operation operation) {
        if (queue.offer(operation)) {
            return;
        }
        synchronized (admission) {
            if (active == operation) {
                active = null;
            }
        }
        operation.fail(new LyraLifecycleException("the managed console operation queue is full"));
        operation.complete();
    }

    private EvaluationRequest request(EvaluationSource source) {
        return new EvaluationRequest(EvaluationId.create(), revision(), source);
    }

    private Evaluation closedEvaluation(EvaluationRequest request) {
        return new Evaluation(request.evaluationId(), EvaluationStatus.CLOSED, revision(),
                List.of(), Optional.empty(), Optional.of("the managed console is closed"));
    }

    private Evaluation busyEvaluation(EvaluationRequest request, Operation current) {
        return new Evaluation(request.evaluationId(), EvaluationStatus.BUSY, revision(),
                List.of(), Optional.empty(),
                Optional.of("the managed console already has an active operation: "
                        + current.controlId));
    }

    @Override
    public Control cancel(EvaluationId evaluationId) {
        Objects.requireNonNull(evaluationId, "evaluationId");
        Operation operation;
        synchronized (admission) {
            if (closed) {
                return closedControl();
            }
            operation = active;
            if (!(operation instanceof EvaluateOperation evaluation)
                    || !evaluation.request.evaluationId().equals(evaluationId)) {
                return new Control(ControlStatus.NOT_FOUND, revision(), Optional.empty());
            }
            if (evaluation.result != null) {
                return new Control(ControlStatus.ALREADY_TERMINAL, revision(), Optional.empty());
            }
            evaluation.cancellationRequested.set(true);
        }
        // The session is the one cross-thread control target: an already
        // running evaluation observes the token at generated boundaries,
        // while the admission probe covers the pre-execution race.
        session().cancel(evaluationId);
        return new Control(ControlStatus.REQUESTED, revision(), Optional.empty());
    }

    @Override
    public Control reset() {
        ResetOperation operation;
        synchronized (admission) {
            if (closed) {
                return closedControl();
            }
            if (active != null) {
                return busyControl();
            }
            operation = new ResetOperation();
            active = operation;
        }
        enqueue(operation);
        operation.awaitTerminal();
        return operation.result;
    }

    private Control closedControl() {
        return new Control(ControlStatus.CLOSED, revision(),
                Optional.of("the managed console is closed"));
    }

    private Control busyControl() {
        return new Control(ControlStatus.BUSY, revision(),
                Optional.of("the managed console already has an active operation"));
    }

    /**
     * Owner-confined explicit reload of one retained REPL-owned module. The
     * reload compiles and initializes on the owner thread and is never
     * queued behind another operation.
     */
    public Evaluation reload(String moduleOrAlias) {
        Objects.requireNonNull(moduleOrAlias, "moduleOrAlias");
        return submitReload(moduleOrAlias, EvaluationId.create(), () -> false);
    }

    /**
     * Owner-routed reload carrying the caller's exact evaluation identity
     * and admission-time cancellation probe.
     */
    public Evaluation submitReload(String moduleOrAlias, EvaluationId evaluationId,
                                   BooleanSupplier cancellationRequested) {
        Objects.requireNonNull(moduleOrAlias, "moduleOrAlias");
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        ReloadOperation operation;
        synchronized (admission) {
            EvaluationRequest placeholder = request(
                    EvaluationSource.of("reload " + moduleOrAlias, ""));
            if (closed) {
                return closedEvaluation(placeholder);
            }
            if (active != null) {
                return busyEvaluation(placeholder, active);
            }
            operation = new ReloadOperation(moduleOrAlias, evaluationId,
                    cancellationRequested);
            active = operation;
        }
        enqueue(operation);
        operation.awaitTerminal();
        return operation.result;
    }

    /**
     * Owner-routed reload returning the raw session result, preserving exact
     * values and initializer progress for full-fidelity owner adapters.
     */
    public EvaluationResult submitReloadResult(String moduleOrAlias,
                                               EvaluationId evaluationId,
                                               BooleanSupplier cancellationRequested) {
        Objects.requireNonNull(moduleOrAlias, "moduleOrAlias");
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        ReloadResultOperation operation;
        synchronized (admission) {
            EvaluationRequest placeholder = request(
                    EvaluationSource.of("reload " + moduleOrAlias, ""));
            if (closed) {
                return closedEvaluationResult(placeholder);
            }
            if (active != null) {
                return busyEvaluationResult(placeholder);
            }
            operation = new ReloadResultOperation(moduleOrAlias, evaluationId,
                    cancellationRequested);
            active = operation;
        }
        enqueue(operation);
        operation.awaitTerminal();
        return operation.result;
    }

    @Override
    public Query query(QueryRequest request) {
        Objects.requireNonNull(request, "request");
        QueryOperation operation;
        synchronized (admission) {
            if (closed) {
                return new Query(QueryStatus.CLOSED, List.of(), Optional.empty(),
                        Optional.of("the managed console is closed"));
            }
            if (active != null) {
                return new Query(QueryStatus.BUSY, List.of(), Optional.empty(),
                        Optional.of("the managed console already has an active operation"));
            }
            operation = new QueryOperation(request);
            active = operation;
        }
        enqueue(operation);
        operation.awaitTerminal();
        return operation.result;
    }

    /**
     * Closes the managed console. The session is closed on its owner thread
     * and the dedicated owner thread is retired before this method returns;
     * no executor or worker thread outlives the close. Closing while an
     * operation is active requests that operation's cancellation and is
     * rejected, mirroring the synchronous local API.
     */
    @Override
    public void close() {
        synchronized (admission) {
            if (closed) {
                return;
            }
            if (owner == Thread.currentThread()) {
                throw new IllegalStateException("the managed console owner must not close itself");
            }
            if (active != null) {
                if (active instanceof EvaluateOperation evaluation) {
                    evaluation.cancellationRequested.set(true);
                    session().cancel(evaluation.request.evaluationId());
                }
                throw new LyraLifecycleException("cannot close while an operation is active");
            }
            closed = true;
        }
        queue.add(SHUTDOWN);
        try {
            if (!ownerExited.await(30, TimeUnit.SECONDS)) {
                throw new LyraLifecycleException("the managed console owner did not terminate");
            }
            owner.join(1000);
            if (owner.isAlive()) {
                throw new LyraLifecycleException("the managed console owner did not terminate");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new LyraLifecycleException(
                    "interrupted while closing the managed console", List.of(), failure);
        }
        Throwable failure = closeFailure != null ? closeFailure : ownerFailure;
        if (failure != null) {
            rethrow(failure);
        }
    }

    /** Package-private live-session probe for console tests and later CLI wiring. */
    LyraSession session() {
        LyraSession current = session;
        if (current == null) {
            throw new IllegalStateException("the managed console has no live session");
        }
        return current;
    }

    /** Package-private: the dedicated thread that owns the managed session. */
    Thread ownerThread() {
        return owner;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new LyraLifecycleException("managed console owner failed", List.of(), failure);
    }

    /** One admitted owner-thread operation with a terminal handshake. */
    private abstract static class Operation {
        final EvaluationId controlId = EvaluationId.create();
        final CountDownLatch terminal = new CountDownLatch(1);
        volatile Throwable failure;

        abstract void execute(LyraSession session);

        final void fail(Throwable problem) {
            if (failure == null) {
                failure = problem;
            }
        }

        final void complete() {
            terminal.countDown();
        }

        final void awaitTerminal() {
            try {
                terminal.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new LyraLifecycleException(
                        "interrupted while waiting for the managed console owner", List.of(), interrupted);
            }
            Throwable problem = failure;
            if (problem != null) {
                rethrow(problem);
            }
        }
    }

    private static final class EvaluateOperation extends Operation {
        private final EvaluationRequest request;
        private final BooleanSupplier externalProbe;
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private volatile Evaluation result;
        private volatile EvaluationResult rawResult;

        private EvaluateOperation(EvaluationRequest request) {
            this(request, () -> false);
        }

        private EvaluateOperation(EvaluationRequest request,
                                  BooleanSupplier externalProbe) {
            this.request = Objects.requireNonNull(request, "request");
            this.externalProbe = Objects.requireNonNull(externalProbe, "externalProbe");
        }

        @Override
        void execute(LyraSession session) {
            EvaluationResult outcome = session.submit(request,
                    () -> cancellationRequested.get() || externalProbe.getAsBoolean());
            rawResult = outcome;
            result = ConsolePresentation.evaluation(outcome);
        }
    }

    private static final class ResetOperation extends Operation {
        private Control result;

        @Override
        void execute(LyraSession session) {
            session.reset();
            result = new Control(ControlStatus.OK, session.currentRevision(), Optional.empty());
        }
    }

    private static final class ReloadOperation extends Operation {
        private final String moduleOrAlias;
        private final EvaluationId evaluationId;
        private final BooleanSupplier cancellationRequested;
        private Evaluation result;

        private ReloadOperation(String moduleOrAlias) {
            this(moduleOrAlias, EvaluationId.create(), () -> false);
        }

        private ReloadOperation(String moduleOrAlias, EvaluationId evaluationId,
                                BooleanSupplier cancellationRequested) {
            this.moduleOrAlias = Objects.requireNonNull(moduleOrAlias, "moduleOrAlias");
            this.evaluationId = Objects.requireNonNull(evaluationId, "evaluationId");
            this.cancellationRequested = Objects.requireNonNull(
                    cancellationRequested, "cancellationRequested");
        }

        @Override
        void execute(LyraSession session) {
            result = ConsolePresentation.evaluation(session.reload(
                    moduleOrAlias, evaluationId, cancellationRequested));
        }
    }

    private static final class ReloadResultOperation extends Operation {
        private final String moduleOrAlias;
        private final EvaluationId evaluationId;
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private final BooleanSupplier externalProbe;
        private EvaluationResult result;

        private ReloadResultOperation(String moduleOrAlias, EvaluationId evaluationId,
                                      BooleanSupplier externalProbe) {
            this.moduleOrAlias = Objects.requireNonNull(moduleOrAlias, "moduleOrAlias");
            this.evaluationId = Objects.requireNonNull(evaluationId, "evaluationId");
            this.externalProbe = Objects.requireNonNull(externalProbe, "externalProbe");
        }

        @Override
        void execute(LyraSession session) {
            result = session.reload(moduleOrAlias, evaluationId,
                    () -> cancellationRequested.get() || externalProbe.getAsBoolean());
        }
    }

    private static final class QueryOperation extends Operation {
        private final QueryRequest request;
        private Query result;

        private QueryOperation(QueryRequest request) {
            this.request = Objects.requireNonNull(request, "request");
        }

        @Override
        void execute(LyraSession session) {
            if (request.kind() == QueryRequest.Kind.TYPE) {
                LyraSession.TypeQuery type = session.type(request.source().orElseThrow());
                if (type.busy()) {
                    result = new Query(QueryStatus.BUSY, List.of(), Optional.empty(),
                            Optional.of("the managed console session is busy"));
                } else if (type.canonicalType().isEmpty()) {
                    String detail = type.diagnostics().isEmpty()
                            ? "type query failed; source was not executed"
                            : type.diagnostics().stream()
                                    .map(ConsolePresentation::renderDiagnostic)
                                    .collect(Collectors.joining("; "));
                    result = new Query(QueryStatus.UNAVAILABLE, List.of(), Optional.empty(),
                            Optional.of(detail));
                } else {
                    result = new Query(QueryStatus.OK, List.of(), type.canonicalType(),
                            Optional.empty());
                }
            } else {
                result = new Query(QueryStatus.OK,
                        ConsolePresentation.bindings(session.workspaceState()),
                        Optional.empty(), Optional.empty());
            }
        }
    }

    /** Never executed; the owner loop treats it as the shutdown marker. */
    private static final class ShutdownOperation extends Operation {
        @Override
        void execute(LyraSession session) {
            throw new AssertionError("the shutdown marker is not an executable operation");
        }
    }
}
