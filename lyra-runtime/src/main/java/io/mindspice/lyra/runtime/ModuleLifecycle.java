package io.mindspice.lyra.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owner-thread-confined lifecycle bookkeeping for one module instance.
 * State transitions are explicit and no calls are synchronized or hopped to
 * another thread.
 */
public final class ModuleLifecycle implements AutoCloseable {
    private final OwnerThread owner;
    private final Optional<ModuleId> moduleId;
    private final AtomicReference<LifecycleState> state =
            new AtomicReference<>(LifecycleState.INITIALIZING);
    private final LyraOwnershipToken ownership;
    private final LyraClosureAuthority closureAuthority;
    private final RuntimeIoEnvironment ioEnvironment;
    private volatile Throwable failureCause;

    public ModuleLifecycle() {
        this(OwnerThread.capture(), Optional.empty());
    }

    public ModuleLifecycle(Thread ownerThread) {
        this(OwnerThread.of(ownerThread), Optional.empty());
    }

    public ModuleLifecycle(Thread ownerThread, ModuleId moduleId) {
        this(OwnerThread.of(ownerThread), Optional.of(Objects.requireNonNull(moduleId, "moduleId")));
    }

    public ModuleLifecycle(OwnerThread owner) {
        this(owner, Optional.empty());
    }

    public ModuleLifecycle(OwnerThread owner, ModuleId moduleId) {
        this(owner, Optional.of(Objects.requireNonNull(moduleId, "moduleId")));
    }

    private ModuleLifecycle(OwnerThread owner, Optional<ModuleId> moduleId) {
        this(owner, moduleId, new LyraArtifactKey());
    }

    /** Creates an opaque key shared by the module states of one artifact. */
    public static LyraArtifactKey newArtifactKey() {
        return new LyraArtifactKey();
    }

    /** Creates an artifact key whose owner is supplied by immutable runtime options. */
    public static LyraArtifactKey newArtifactKey(RuntimeOptions options) {
        RuntimeOptions value = Objects.requireNonNull(options, "options");
        LyraArtifactKey existing = value.artifactKey();
        return existing != null ? existing
                : new LyraArtifactKey(value.owner(), value.ioEnvironment());
    }

    /**
     * Creates lifecycle state for generated modules that belong to one loaded
     * artifact.  Sharing the opaque key lets closures move between module
     * states in the same artifact while keeping independent artifacts isolated.
     */
    public static ModuleLifecycle forArtifact(ModuleId moduleId, LyraArtifactKey artifactKey) {
        LyraArtifactKey key = Objects.requireNonNull(artifactKey, "artifactKey");
        OwnerThread owner = key.configuredOwner().orElseGet(OwnerThread::capture);
        return new ModuleLifecycle(owner, moduleId, key);
    }

    /** Runtime-internal constructor for modules belonging to one loaded artifact. */
    ModuleLifecycle(OwnerThread owner, ModuleId moduleId, Object artifactKey) {
        this(owner, Optional.of(Objects.requireNonNull(moduleId, "moduleId")), artifactKey);
    }

    private ModuleLifecycle(OwnerThread owner, Optional<ModuleId> moduleId, Object artifactKey) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId");
        Object key = Objects.requireNonNull(artifactKey, "artifactKey");
        this.ownership = new LyraOwnershipToken(this, owner, moduleId, key);
        this.closureAuthority = new LyraClosureAuthority(ownership);
        this.ioEnvironment = key instanceof LyraArtifactKey artifact
                ? artifact.ioEnvironment() : RuntimeIoEnvironment.defaults();
    }

    public OwnerThread owner() {
        return owner;
    }

    public Thread ownerThread() {
        return owner.thread();
    }

    public Optional<ModuleId> moduleId() {
        return moduleId;
    }

    public boolean isOwnerThread() {
        return owner.isCurrent();
    }

    /** Returns state only after validating the owner thread. */
    public LifecycleState state() {
        owner.check();
        return state.get();
    }

    public boolean isOpen() {
        owner.check();
        return state.get() == LifecycleState.OPEN;
    }

    public boolean isClosed() {
        owner.check();
        return state.get() == LifecycleState.CLOSED;
    }

    public void open() {
        owner.check();
        if (!state.compareAndSet(LifecycleState.INITIALIZING, LifecycleState.OPEN)) {
            throw invalidTransition("only INITIALIZING may transition to OPEN");
        }
    }

    public void transitionToOpen() {
        open();
    }

    /** Marks construction as terminally failed and invalidates owned values. */
    public void fail(Throwable cause) {
        owner.check();
        Objects.requireNonNull(cause, "cause");
        if (!state.compareAndSet(LifecycleState.INITIALIZING, LifecycleState.FAILED)) {
            throw invalidTransition("only INITIALIZING may transition to FAILED");
        }
        failureCause = cause;
        ownership.invalidate();
    }

    public Optional<Throwable> failureCause() {
        owner.check();
        return Optional.ofNullable(failureCause);
    }

    void requireOpen() {
        owner.check();
        requireOpenAfterOwnerCheck();
    }

    public void checkOpen() {
        requireOpen();
    }

    public LyraClosureAuthority closureAuthority() {
        owner.check();
        LifecycleState current = state.get();
        if (current == LifecycleState.CLOSED) {
            throw new LyraClosedException("module is closed");
        }
        if (current == LifecycleState.FAILED) {
            throw initializationFailure();
        }
        return closureAuthority;
    }

    RuntimeIoEnvironment ioEnvironment() {
        owner.check();
        return ioEnvironment;
    }

    @Override
    public void close() {
        owner.check();
        if (state.compareAndSet(LifecycleState.OPEN, LifecycleState.CLOSED)) {
            ownership.invalidate();
            return;
        }
        LifecycleState current = state.get();
        if (current == LifecycleState.CLOSED) {
            return;
        }
        if (current == LifecycleState.FAILED) {
            throw invalidTransition("a failed module cannot be closed as an open instance");
        }
        throw invalidTransition("a module cannot be closed while INITIALIZING");
    }

    LifecycleState rawState() {
        return state.get();
    }

    Throwable failureCauseUnchecked() {
        return failureCause;
    }

    private void requireOpenAfterOwnerCheck() {
        LifecycleState current = state.get();
        if (current == LifecycleState.OPEN) {
            return;
        }
        if (current == LifecycleState.CLOSED) {
            throw new LyraClosedException("module is closed");
        }
        if (current == LifecycleState.FAILED) {
            throw initializationFailure();
        }
        throw new LyraLifecycleException("module is still initializing");
    }

    private LyraInitializationException initializationFailure() {
        Throwable cause = failureCause;
        return cause == null ? new LyraInitializationException("module initialization failed")
                : new LyraInitializationException("module initialization failed", List.of(), List.of(), cause);
    }

    private LyraLifecycleException invalidTransition(String summary) {
        return new LyraLifecycleException(summary);
    }

}
