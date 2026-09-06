package io.mindspice.lyra.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An explicit, thread-safe cooperative cancellation token.
 *
 * <p>Requesting cancellation is the only cross-thread control operation. It
 * only publishes a flag; it never interrupts or terminates a thread. An
 * evaluation token is consumed by one owner-thread evaluation lease. An
 * application token is deliberately a different scope and is never consulted
 * by {@link LyraOwnerController#safePoint()}.</p>
 */
public final class LyraCancellation {
    private static final AtomicLong STANDALONE_IDS = new AtomicLong(1L);

    private final Scope scope;
    private final long id;
    private final AtomicReference<LyraCancellationState> state =
            new AtomicReference<>(LyraCancellationState.ACTIVE);
    private final AtomicBoolean claimed = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private LyraCancellation(Scope scope, long id) {
        this.scope = Objects.requireNonNull(scope, "scope");
        if (id < 0) {
            throw new IllegalArgumentException("cancellation identity must not be negative");
        }
        this.id = id;
    }

    /** Creates an independent evaluation token with a runtime identity. */
    public static LyraCancellation evaluation() {
        return evaluation(nextStandaloneId());
    }

    /** Creates an evaluation token with a caller-owned identity. */
    public static LyraCancellation evaluation(long id) {
        if (id == 0) {
            throw new IllegalArgumentException("evaluation identity must be positive");
        }
        return new LyraCancellation(Scope.EVALUATION, id);
    }

    /** Creates an independent application-scope token. */
    public static LyraCancellation application() {
        return new LyraCancellation(Scope.APPLICATION, nextStandaloneId());
    }

    static LyraCancellation evaluationToken(long id) {
        return new LyraCancellation(Scope.EVALUATION, id);
    }

    static LyraCancellation applicationToken(long id) {
        return new LyraCancellation(Scope.APPLICATION, id);
    }

    public Scope scope() {
        return scope;
    }

    public long id() {
        return id;
    }

    /** Alias that makes evaluation identity use explicit at call sites. */
    public long evaluationId() {
        if (scope != Scope.EVALUATION) {
            throw new IllegalStateException("application cancellation has no evaluation identity");
        }
        return id;
    }

    /** Returns the current immutable state snapshot. */
    public LyraCancellationState state() {
        return state.get();
    }

    public boolean isCancellationRequested() {
        return state.get().isCancellationRequested();
    }

    public boolean isObserved() {
        return state.get().isObserved();
    }

    /** Returns whether this token has been retired by its owning controller. */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Publishes cancellation from any thread.
     *
     * @return {@code true} only when this call changed ACTIVE to REQUESTED;
     *         repeated requests return {@code false}
     * @throws LyraClosedException if the token has been retired
     */
    public boolean request() {
        for (;;) {
            if (closed.get()) {
                throw closedException();
            }
            LyraCancellationState current = state.get();
            if (current != LyraCancellationState.ACTIVE) {
                if (closed.get()) {
                    throw closedException();
                }
                return false;
            }
            if (state.compareAndSet(LyraCancellationState.ACTIVE,
                    LyraCancellationState.REQUESTED)) {
                return true;
            }
        }
    }

    /** Descriptive alias for {@link #request()}. */
    public boolean requestCancellation() {
        return request();
    }

    /**
     * Marks a published request as observed. This is intentionally package
     * private: only the owner controller may turn a request into a runtime
     * cancellation signal.
     */
    boolean observe() {
        for (;;) {
            if (closed.get()) {
                return false;
            }
            LyraCancellationState current = state.get();
            if (current == LyraCancellationState.ACTIVE) {
                return false;
            }
            if (current == LyraCancellationState.OBSERVED) {
                return true;
            }
            if (state.compareAndSet(LyraCancellationState.REQUESTED,
                    LyraCancellationState.OBSERVED)) {
                return true;
            }
        }
    }

    void claimForEvaluation() {
        if (scope != Scope.EVALUATION) {
            throw new IllegalArgumentException(
                    "only evaluation cancellation tokens may be leased for evaluation");
        }
        if (closed.get()) {
            throw closedException();
        }
        if (!claimed.compareAndSet(false, true)) {
            throw new LyraLifecycleException("evaluation cancellation token is already leased");
        }
    }

    /** Retires this controller-owned token without changing its observation state. */
    void retire() {
        closed.set(true);
    }

    private static long nextStandaloneId() {
        long value = STANDALONE_IDS.getAndIncrement();
        if (value <= 0) {
            throw new IllegalStateException("cancellation identity space exhausted");
        }
        return value;
    }

    private LyraClosedException closedException() {
        return new LyraClosedException("cancellation token is closed");
    }

    @Override
    public String toString() {
        return "LyraCancellation[scope=" + scope + ",id=" + id
                + ",state=" + state() + ",closed=" + isClosed() + "]";
    }

    public enum Scope {
        EVALUATION,
        APPLICATION
    }
}
