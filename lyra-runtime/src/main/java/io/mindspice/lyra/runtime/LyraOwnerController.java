package io.mindspice.lyra.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opt-in owner-thread boundary for cooperative REPL/application control.
 *
 * <p>The controller creates no executor and never moves work between threads.
 * A caller may publish at most one explicit dispatch request; the owner must
 * call {@link #poll()} to run it. All live operations, including polling,
 * evaluation admission, safe points and close, are owner-thread checked.
 * Cancellation requests and dispatch publication/cancellation are the
 * deliberately thread-safe control operations.</p>
 *
 * <p>Evaluation cancellation is scoped to the current
 * {@link EvaluationLease}. The separate application token returned by
 * {@link #applicationCancellation()} is not consulted by
 * {@link #safePoint()}.</p>
 */
public final class LyraOwnerController implements AutoCloseable {
    private final OwnerThread owner;
    private final Optional<ModuleLifecycle> lifecycle;
    private final AtomicLong nextEvaluationId = new AtomicLong(1L);
    private final AtomicReference<EvaluationLease> active = new AtomicReference<>();
    private final AtomicReference<Dispatch> pending = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LyraCancellation applicationCancellation = LyraCancellation.applicationToken(0L);

    /** Creates a controller owned by the calling thread. */
    public LyraOwnerController() {
        this(OwnerThread.capture(), null);
    }

    /** Creates a controller for an explicit owner thread. */
    public LyraOwnerController(Thread ownerThread) {
        this(OwnerThread.of(ownerThread), null);
    }

    /** Creates a controller for an explicit owner identity. */
    public LyraOwnerController(OwnerThread owner) {
        this(owner, null);
    }

    /**
     * Creates a controller attached to a module lifecycle without taking
     * ownership of that lifecycle. The module must be OPEN for live control
     * operations; closing this controller does not close the module.
     */
    public LyraOwnerController(ModuleLifecycle lifecycle) {
        this(Objects.requireNonNull(lifecycle, "lifecycle").owner(), lifecycle);
    }

    public static LyraOwnerController capture() {
        return new LyraOwnerController();
    }

    public static LyraOwnerController forModule(ModuleLifecycle lifecycle) {
        return new LyraOwnerController(lifecycle);
    }

    private LyraOwnerController(OwnerThread owner, ModuleLifecycle lifecycle) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.lifecycle = Optional.ofNullable(lifecycle);
    }

    public OwnerThread owner() {
        return owner;
    }

    public Thread ownerThread() {
        return owner.thread();
    }

    public boolean isOwnerThread() {
        return owner.isCurrent();
    }

    /** Returns controller state; this live check is owner-thread confined. */
    public Status status() {
        owner.check();
        if (closed.get()) {
            return Status.CLOSED;
        }
        return active.get() == null ? Status.OPEN : Status.EVALUATING;
    }

    /** Returns whether this control boundary has been closed. */
    public boolean isClosed() {
        owner.check();
        return closed.get();
    }

    /**
     * Returns the application-scoped cancellation token. The token itself is
     * safe to request from another thread after the owner publishes it.
     */
    public LyraCancellation applicationCancellation() {
        owner.check();
        requireControllerOpen();
        return applicationCancellation;
    }

    /** Requests application-scope cancellation without affecting evaluation. */
    public boolean requestApplicationCancellation() {
        requireControllerOpen();
        return applicationCancellation.request();
    }

    /** Begins one owner-thread-confined, non-reentrant evaluation. */
    public EvaluationLease beginEvaluation() {
        owner.check();
        requireReadyOnOwner();
        if (active.get() != null) {
            throw new LyraLifecycleException("an evaluation is already active");
        }
        LyraCancellation cancellation = LyraCancellation.evaluationToken(nextId());
        return beginEvaluation(cancellation);
    }

    /** Returns the active lease for owner-side adapters, if one exists. */
    public Optional<EvaluationLease> currentEvaluation() {
        owner.check();
        return Optional.ofNullable(active.get());
    }

    /**
     * Returns whether the supplied lease is currently active on this exact
     * controller.  An admitted lease may be reused by an owner-dispatched
     * evaluation only when this predicate holds; never create a second lease.
     */
    public boolean isActiveEvaluation(EvaluationLease lease) {
        Objects.requireNonNull(lease, "lease");
        return lease.controller == this && lease.isActive();
    }

    /**
     * Thread-safe busy probe for cross-thread admission.  It reads only the
     * safely published control atomics and never touches owner state, so a
     * publisher racing a terminal result can observe truthful transient
     * busyness instead of a stale admission.
     */
    public boolean hasLiveWork() {
        return active.get() != null || pending.get() != null;
    }

    /**
     * Ends a lease. Ending an already-ended lease is harmless, which keeps
     * explicit cleanup and try-with-resources equivalent.
     */
    public void endEvaluation(EvaluationLease lease) {
        owner.check();
        Objects.requireNonNull(lease, "lease");
        requireControllerOpen();
        if (lease.controller != this) {
            throw new IllegalArgumentException("evaluation lease belongs to another controller");
        }
        if (active.compareAndSet(lease, null)) {
            lease.endInternal();
            return;
        }
        if (!lease.ended.get()) {
            throw new LyraLifecycleException("evaluation lease is not active");
        }
    }

    /**
     * Requests cancellation of the active evaluation identified by the
     * controller-issued identity. This method is safe from any thread.
     */
    public boolean requestCancellation(long evaluationId) {
        requireControllerOpen();
        EvaluationLease lease = active.get();
        if (lease == null || lease.evaluationId != evaluationId) {
            return false;
        }
        return requestActiveCancellation(lease);
    }

    /** Requests cancellation of a particular active lease from any thread. */
    public boolean requestCancellation(EvaluationLease lease) {
        Objects.requireNonNull(lease, "lease");
        if (lease.controller != this) {
            throw new IllegalArgumentException("evaluation lease belongs to another controller");
        }
        return requestCancellation(lease.evaluationId);
    }

    /** Requests cancellation of whatever evaluation is active, if any. */
    public boolean requestCancellation() {
        requireControllerOpen();
        EvaluationLease lease = active.get();
        return lease != null && requestActiveCancellation(lease);
    }

    /** Alias for {@link #requestCancellation()}. */
    public boolean cancelCurrentEvaluation() {
        return requestCancellation();
    }

    /**
     * Publishes one owner-thread dispatch request without executing it.
     * Requests made while an evaluation is active, or while another request
     * is pending, are rejected rather than queued or run reentrantly.
     */
    public Dispatch dispatch(Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        requireControllerOpen();
        if (active.get() != null) {
            throw new LyraLifecycleException("cannot dispatch while an evaluation is active");
        }

        LyraCancellation cancellation = LyraCancellation.evaluationToken(nextId());
        Dispatch request = new Dispatch(this, operation, cancellation);
        if (!pending.compareAndSet(null, request)) {
            cancellation.retire();
            throw new LyraLifecycleException("an owner dispatch is already pending");
        }
        // Close may race a non-owner publisher. If the owner claimed the
        // request first, it is already an accepted request and will finish
        // under the normal lease; otherwise close removes it here.
        if (closed.get() && pending.compareAndSet(request, null)) {
            request.closeFromController();
            throw closedException();
        }
        if (active.get() != null && pending.compareAndSet(request, null)) {
            request.closeFromController();
            throw new LyraLifecycleException(
                    "cannot dispatch while an evaluation is active");
        }
        return request;
    }

    /**
     * Explicit owner poll. It runs at most one pending request and returns
     * whether a request was consumed. While an evaluation is active it only
     * checks that evaluation's cancellation and never dispatches nested work.
     * An expected dispatched failure or cancellation is recorded on the
     * {@link Dispatch} and rethrown into the polling frame.
     */
    public boolean poll() {
        return pollInternal(false);
    }

    /**
     * Contained owner poll for optional attachment boundaries.
     *
     * <p>Unlike {@link #poll()}, an expected dispatched evaluation failure or
     * cooperative cancellation is recorded on its {@link Dispatch} and never
     * propagates into the application frame that reached the safe point.
     * Idle initialized application code still consumes at most one pending
     * request, and while an evaluation is active the poll only checks that
     * evaluation's exact token; no nested work is dispatched.</p>
     */
    public boolean pollContained() {
        return pollInternal(true);
    }

    private boolean pollInternal(boolean contained) {
        owner.check();
        requireReadyOnOwner();
        if (active.get() != null) {
            safePoint();
            return false;
        }

        Dispatch request = pending.getAndSet(null);
        if (request == null) {
            return false;
        }
        if (!request.claimForExecution()) {
            return true;
        }

        EvaluationLease lease;
        try {
            lease = beginEvaluation(request.cancellation);
        } catch (Throwable failure) {
            request.failed(failure);
            request.cancellation.retire();
            if (!contained || mustEscapeContained(failure)) {
                rethrow(failure);
            }
            return true;
        }

        try {
            safePoint();
            Runnable operation = request.operation.getAndSet(null);
            if (operation == null) {
                throw new LyraInternalException("owner dispatch operation was unavailable");
            }
            operation.run();
            // The end of an explicitly polled operation is also a boundary,
            // but cancellation remains cooperative: no interruption occurs.
            safePoint();
            request.completed();
        } catch (LyraCancellationException cancellation) {
            request.cancelled(cancellation);
            if (!contained) {
                throw cancellation;
            }
        } catch (Throwable failure) {
            request.failed(failure);
            if (!contained || mustEscapeContained(failure)) {
                rethrow(failure);
            }
        } finally {
            endEvaluation(lease);
        }
        return true;
    }

    /**
     * Owner safe point. A signal is produced only when an evaluation lease is
     * active and that lease's evaluation token was requested. Application
     * cancellation is intentionally ignored here.
     */
    public void safePoint() {
        owner.check();
        requireReadyOnOwner();
        EvaluationLease lease = active.get();
        if (lease == null) {
            return;
        }
        LyraCancellation cancellation = lease.cancellation;
        if (cancellation.isCancellationRequested()) {
            cancellation.observe();
            throw LyraCancellationException.forToken(cancellation);
        }
    }

    /**
     * Closes this control boundary on its owner thread. It never closes an
     * attached module and never terminates a thread. A second close is a
     * no-op; closing while an evaluation is active is rejected so the owner
     * can unwind it at a safe point first.
     */
    @Override
    public void close() {
        owner.check();
        if (closed.get()) {
            return;
        }
        if (active.get() != null) {
            throw new LyraLifecycleException("cannot close while an evaluation is active");
        }
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Dispatch request = pending.getAndSet(null);
        if (request != null) {
            request.closeFromController();
        }
        applicationCancellation.retire();
    }

    private EvaluationLease beginEvaluation(LyraCancellation cancellation) {
        if (active.get() != null) {
            throw new LyraLifecycleException("an evaluation is already active");
        }
        cancellation.claimForEvaluation();
        EvaluationLease lease = new EvaluationLease(this, cancellation);
        if (!active.compareAndSet(null, lease)) {
            cancellation.retire();
            throw new LyraLifecycleException("an evaluation is already active");
        }
        return lease;
    }

    private boolean requestActiveCancellation(EvaluationLease lease) {
        if (!lease.isActive()) {
            return false;
        }
        try {
            lease.cancellation.request();
            return true;
        } catch (LyraClosedException closedToken) {
            if (!lease.isActive()) {
                return false;
            }
            throw closedToken;
        }
    }

    private boolean cancelDispatch(Dispatch request) {
        DispatchStatus current = request.status.get();
        if (current.isTerminal()) {
            return false;
        }
        requireControllerOpen();
        for (;;) {
            current = request.status.get();
            if (current.isTerminal()) {
                return false;
            }
            if (current == DispatchStatus.PENDING) {
                // Claim cancellation before touching the token. Close may
                // retire that token concurrently; the terminal state is the
                // authoritative request identity in that race.
                if (!request.status.compareAndSet(DispatchStatus.PENDING,
                        DispatchStatus.CANCELLED)) {
                    continue;
                }
                pending.compareAndSet(request, null);
                request.operation.set(null);
                try {
                    request.cancellation.request();
                } catch (LyraClosedException ignored) {
                    // Controller close won the token-publication race. The
                    // request is already terminal and cannot affect later
                    // work, so cancellation remains a successful transition.
                }
                request.cancellation.retire();
                request.notifyTerminal();
                return true;
            }
            // RUNNING: publishing the token request is sufficient. The
            // owner will observe it at the next generated/explicit safe point.
            request.cancellation.request();
            return true;
        }
    }

    private void requireReadyOnOwner() {
        requireControllerOpen();
        lifecycle.ifPresent(ModuleLifecycle::checkOpen);
    }

    private void requireControllerOpen() {
        if (closed.get()) {
            throw closedException();
        }
    }

    private long nextId() {
        long id = nextEvaluationId.getAndIncrement();
        if (id <= 0) {
            throw new LyraLifecycleException("evaluation identity space exhausted");
        }
        return id;
    }

    private LyraClosedException closedException() {
        return new LyraClosedException("owner controller is closed");
    }

    private static boolean mustEscapeContained(Throwable failure) {
        // Optional attachment containment is for expected evaluation faults,
        // not host-integrity/fatal JVM signals. Those must retain the normal
        // host-control contract even when an application reached a safe point.
        return failure instanceof VirtualMachineError
                || failure instanceof ThreadDeath
                || failure instanceof LinkageError;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new LyraInternalException("owner dispatch failed", java.util.List.of(),
                java.util.List.of(), failure);
    }

    public enum Status {
        OPEN,
        EVALUATING,
        CLOSED
    }

    public enum DispatchStatus {
        PENDING(false),
        RUNNING(false),
        COMPLETED(true),
        FAILED(true),
        CANCELLED(true),
        CLOSED(true);

        private final boolean terminal;

        DispatchStatus(boolean terminal) {
            this.terminal = terminal;
        }

        public boolean isTerminal() {
            return terminal;
        }
    }

    /** A controller-issued owner-thread evaluation lease. */
    public static final class EvaluationLease implements AutoCloseable {
        private final LyraOwnerController controller;
        private final LyraCancellation cancellation;
        private final long evaluationId;
        private final AtomicBoolean ended = new AtomicBoolean();

        private EvaluationLease(LyraOwnerController controller, LyraCancellation cancellation) {
            this.controller = controller;
            this.cancellation = cancellation;
            this.evaluationId = cancellation.id();
        }

        public long evaluationId() {
            return evaluationId;
        }

        public LyraCancellation cancellation() {
            return cancellation;
        }

        public boolean isActive() {
            return !ended.get() && controller.active.get() == this;
        }

        public boolean isCancellationRequested() {
            return cancellation.isCancellationRequested();
        }

        /** Requests this lease's cancellation from any thread. */
        public boolean requestCancellation() {
            return controller.requestCancellation(this);
        }

        @Override
        public void close() {
            controller.owner.check();
            if (ended.get()) {
                return;
            }
            controller.endEvaluation(this);
        }

        private void endInternal() {
            if (ended.compareAndSet(false, true)) {
                cancellation.retire();
            }
        }
    }

    /**
     * One bounded dispatch request. Its status and cancellation handle are
     * safe to inspect/control from a non-owner thread; its operation is only
     * ever run by {@link #poll()}.
     */
    public static final class Dispatch {
        private final LyraOwnerController controller;
        private final AtomicReference<Runnable> operation;
        private final LyraCancellation cancellation;
        private final AtomicReference<DispatchStatus> status =
                new AtomicReference<>(DispatchStatus.PENDING);
        private final AtomicReference<Runnable> terminalListener =
                new AtomicReference<>();
        private volatile Throwable failure;

        private Dispatch(LyraOwnerController controller, Runnable operation,
                         LyraCancellation cancellation) {
            this.controller = controller;
            this.operation = new AtomicReference<>(operation);
            this.cancellation = cancellation;
        }

        public long evaluationId() {
            return cancellation.id();
        }

        public LyraCancellation cancellation() {
            return cancellation;
        }

        public DispatchStatus status() {
            return status.get();
        }

        public boolean isDone() {
            return status().isTerminal();
        }

        public Optional<Throwable> failure() {
            return Optional.ofNullable(failure);
        }

        /** Requests cancellation before or during owner polling. */
        public boolean cancel() {
            return controller.cancelDispatch(this);
        }

        /**
         * Installs one internal terminal observer without exposing live owner
         * state to the publishing thread. The observer is invoked exactly
         * once, even when cancellation or close wins the race first.
         */
        public void whenTerminal(Runnable listener) {
            Objects.requireNonNull(listener, "listener");
            if (status.get().isTerminal()) {
                listener.run();
                return;
            }
            if (!terminalListener.compareAndSet(null, listener)) {
                throw new IllegalStateException("dispatch already has a terminal observer");
            }
            if (status.get().isTerminal()
                    && terminalListener.compareAndSet(listener, null)) {
                listener.run();
            }
        }

        private void notifyTerminal() {
            Runnable listener = terminalListener.getAndSet(null);
            if (listener != null) {
                listener.run();
            }
        }

        private boolean claimForExecution() {
            return status.compareAndSet(DispatchStatus.PENDING, DispatchStatus.RUNNING);
        }

        private void completed() {
            status.set(DispatchStatus.COMPLETED);
            cancellation.retire();
            notifyTerminal();
        }

        private void cancelled(Throwable signal) {
            failure = signal;
            status.set(DispatchStatus.CANCELLED);
            operation.set(null);
            cancellation.retire();
            notifyTerminal();
        }

        private void failed(Throwable cause) {
            failure = cause;
            status.set(DispatchStatus.FAILED);
            operation.set(null);
            cancellation.retire();
            notifyTerminal();
        }

        private void closeFromController() {
            operation.set(null);
            status.set(DispatchStatus.CLOSED);
            cancellation.retire();
            notifyTerminal();
        }
    }
}
