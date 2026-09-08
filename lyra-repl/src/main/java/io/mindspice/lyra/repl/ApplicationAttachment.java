package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.api.AttachableRootContext;
import io.mindspice.lyra.compiler.api.CompiledArtifact;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.compiler.api.LyraCompilerBugException;
import io.mindspice.lyra.compiler.api.SessionCompileRequest;
import io.mindspice.lyra.compiler.api.SessionCompileResult;
import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.session.ExternalBinding;
import io.mindspice.lyra.compiler.session.SessionSnapshot;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.runtime.LoadOptions;
import io.mindspice.lyra.runtime.LoadedArtifact;
import io.mindspice.lyra.runtime.LyraClosedException;
import io.mindspice.lyra.runtime.LyraLifecycleException;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.LyraRuntimeException;
import io.mindspice.lyra.runtime.ModuleHandle;
import io.mindspice.lyra.runtime.ModuleId;
import io.mindspice.lyra.runtime.OwnerThread;
import io.mindspice.lyra.runtime.RootTypeRegistration;
import io.mindspice.lyra.runtime.SessionStorageDomain;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * A live, owner-thread-confined workspace over an explicitly registered
 * attachable application root.
 *
 * <p>The attached workspace enters the root module's public top-level scope
 * through exact typed getters/setters/function-value getters bound to the
 * original root instance.  Public {@code @mut} assignment changes real root
 * storage and is visible to resumed application code; current function,
 * array, tuple and cell values are always read from actual storage, never
 * from initializer snapshots.  Application-owned dependencies are seeded as
 * borrowed registry entries usable by session imports but never reloadable
 * or closable by scratch work.</p>
 *
 * <p>The root owns all producer/link/type/source/summary retention.
 * {@link #reset()} and {@link #close()} remove scratch/control state while
 * root-held values and the root lifetime survive; closing the root retires
 * every retained producer.  Only one simultaneous service may exist per root,
 * while independent roots keep independent domains.</p>
 */
public final class ApplicationAttachment implements AutoCloseable {
    private final SessionOptions options;
    private final OwnerThread owner;
    private final Object admission = new Object();
    private final ModuleHandle root;
    private final RootTypeRegistration registration;
    /** The registered root controller; safe for cross-thread dispatch publication. */
    private final io.mindspice.lyra.runtime.LyraOwnerController controller;
    private final AttachableRootContext context;
    private final SessionStorageDomain storage;
    private final SourceRegistry sourceRegistry;
    private final SessionWorkspace workspace = new SessionWorkspace();
    private final Map<Long, SessionStorageDomain.Binding> storageBindings = new HashMap<>();
    /** Conservatively retained generations; only the root lifetime retires them. */
    private final List<Generation> generations = new ArrayList<>();
    /** Generated class names of the root artifact, for snapshot closure checks. */
    private final java.util.Set<String> rootClassNames;
    private SessionSnapshot compilerSnapshot;

    private volatile SessionLifecycleState lifecycle = SessionLifecycleState.OPEN;
    private volatile SessionRevision revision = SessionRevision.initial();
    /** Guarded by {@link #admission}; reads are only for admission/cancellation. */
    private ActiveOperation active;

    private ApplicationAttachment(ModuleHandle root, RootTypeRegistration registration,
                                  AttachableRootContext context, SessionOptions options) {
        this.root = Objects.requireNonNull(root, "root");
        this.registration = Objects.requireNonNull(registration, "registration");
        this.context = Objects.requireNonNull(context, "context");
        this.options = Objects.requireNonNull(options, "options");
        owner = registration.lifecycle().owner();
        SourceRegistry requestedSources = new SourceRegistry(options);
        List<io.mindspice.lyra.compiler.source.SourceSnapshot> rootSources =
                context.moduleEnvironment().sourceInventory();
        if (!requestedSources.canRetainGraph(rootSources)) {
            throw new IllegalArgumentException(
                    "attached root source context exceeds the configured source registry capacity");
        }
        sourceRegistry = registration.rootLifetime().sharedSourceHolder(
                SourceRegistry.class, () -> requestedSources);
        if (!sourceRegistry.fitsWithin(requestedSources)) {
            throw new IllegalArgumentException(
                    "retained root producer sources exceed the configured source registry capacity");
        }
        retainRootSources();
        compilerSnapshot = context.initialSnapshot();
        // The workspace shares the registered root controller so synchronous
        // submissions, owner-dispatched evaluations and generated application
        // safe points observe exactly one active lease.  A dispatched
        // evaluation reuses the poll's admitted lease; no second lease or
        // nested evaluation can begin.
        controller = registration.controller();
        storage = new SessionStorageDomain(registration.rootLifetime(), controller);
        rootClassNames = LyraRuntime.attachmentClassNames(root);
    }

    /**
     * Opens a live attachment on an attachable root from the calling owner
     * thread.  The context must be the sealed original compilation of the
     * same root revision/graph/source/option alignment; it is preflighted
     * before any evaluation is admitted.  Root initializers are never
     * re-executed and no resolver object is ever called.
     */
    public static ApplicationAttachment open(ModuleHandle root,
                                             AttachableRootContext context,
                                             SessionOptions options) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(options, "options");
        RootTypeRegistration registration = LyraRuntime.registerRoot(root);
        ApplicationAttachment attachment = null;
        try {
            requireAligned(root, context, registration);
            // The root owns the compiler context and its summaries/sources.
            registration.rootLifetime().anchorSummary(context);
            attachment = new ApplicationAttachment(root, registration, context, options);
            attachment.registerBorrowedLinks();
            return attachment;
        } catch (RuntimeException | Error failure) {
            // Registration and storage construction are separate ownership
            // steps. If link registration fails part-way through, close the
            // constructed workspace first so its active-domain slot and
            // partial borrowed retentions cannot strand the root.
            try {
                if (attachment != null) {
                    attachment.close();
                } else {
                    registration.close();
                }
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static void requireAligned(ModuleHandle root, AttachableRootContext context,
                                       RootTypeRegistration registration) {
        ModuleId registered = registration.moduleId();
        ModuleId expected = context.rootModule().isUri()
                ? ModuleId.uri(context.rootModule().asUri())
                : ModuleId.path(context.rootModule().value());
        if (!registered.equals(expected)) {
            throw new io.mindspice.lyra.runtime.LyraCompatibilityException(
                    "attachment context belongs to another root module");
        }
        if (!context.attachmentContext().equals(
                registration.metadata().attachmentContext().orElseThrow())) {
            throw new io.mindspice.lyra.runtime.LyraCompatibilityException(
                    "attachment context was compiled from different sources, options, "
                            + "or graph revisions than the live root");
        }
        if (!root.metadata().artifactRevision().equals(registration.metadata().artifactRevision())) {
            throw new io.mindspice.lyra.runtime.LyraCompatibilityException(
                    "registered root metadata disagrees with the supplied root handle");
        }
    }

    /** Retains every source needed to render failures from the live application graph. */
    private void retainRootSources() {
        List<io.mindspice.lyra.compiler.source.SourceSnapshot> sources =
                context.moduleEnvironment().sourceInventory();
        if (!sourceRegistry.canRetainGraph(sources)) {
            throw new IllegalArgumentException(
                    "attached root source context exceeds the configured source registry capacity");
        }
        sourceRegistry.retainGraph(sources);
    }

    /** Seeds root-scope bindings and borrowed application dependency exports. */
    private void registerBorrowedLinks() {
        // 1. Public root-scope bindings under their compiler-assigned
        // scratch identities, bound to the actual root accessors.
        for (ExternalBinding binding : context.rootBindings()) {
            RootTypeRegistration.Binding access = registration.requireBinding(binding.name());
            SessionStorageDomain.Requirement requirement = new SessionStorageDomain.Requirement(
                    binding.declarationId().ordinal(),
                    binding.storageIdentity()
                            .map(io.mindspice.lyra.compiler.session.StorageIdentity::ordinal)
                            .orElse(-1L),
                    binding.name(),
                    binding.type().canonicalSpelling(),
                    binding.allowsRebinding());
            if (access.setter().isPresent() != requirement.writable()) {
                throw new io.mindspice.lyra.runtime.LyraLinkException(
                        "root export mutability disagrees with its compiler contract: "
                                + binding.name());
            }
            SessionStorageDomain.Binding link = storage.registerExternal(
                    root, requirement, access.getter(), access.setter().orElse(null));
            storageBindings.put(requirement.id(), link);
        }
        // 2. Borrowed registry entries for every self-owned public export of
        // every application module, including the root module itself.  These
        // are reusable by session imports and never reloadable or closable.
        Map<ModuleId, Map<String, RootTypeRegistration.Binding>> views =
                LyraRuntime.attachmentExportBindings(root);
        for (Map.Entry<ModuleId, Map<String, RootTypeRegistration.Binding>> module
                : views.entrySet()) {
            for (RootTypeRegistration.Binding access : module.getValue().values()) {
                var metadata = access.metadata();
                SessionStorageDomain.Requirement requirement =
                        new SessionStorageDomain.Requirement(
                                metadata.declarationIdentity(),
                                metadata.mutable() ? metadata.declarationIdentity() : -1L,
                                metadata.name(),
                                metadata.contract().canonicalSpelling(),
                                metadata.mutable());
                SessionStorageDomain.Binding link = storage.registerExternal(
                        root, requirement, access.getter(), access.setter().orElse(null));
                storageBindings.put(requirement.id(), link);
            }
        }
    }

    /** The live root module this attachment executes against. */
    public ModuleHandle root() {
        owner.check();
        requireOpen();
        return root;
    }

    /**
     * The current committed workspace revision. Safe for control metadata
     * reads from transport threads; never touches live root state.
     */
    public SessionRevision revision() {
        synchronized (admission) {
            return revision;
        }
    }

    /** Whether this service surface is still open. */
    public boolean isOpen() {
        synchronized (admission) {
            return lifecycle == SessionLifecycleState.OPEN;
        }
    }

    /**
     * The configured source-discovery roots for execution-host file
     * listing. Read-only configuration data; never live root state.
     */
    public List<Path> sourceRoots() {
        return options.sourceRoots();
    }

    /** The exact public-root registration installed for this attachment. */
    public RootTypeRegistration registration() {
        owner.check();
        requireOpen();
        return registration;
    }

    /** The sealed source-independent context this attachment was opened from. */
    public AttachableRootContext context() {
        owner.check();
        requireOpen();
        return context;
    }

    /** Submits source using the current committed revision and a new identity. */
    public EvaluationResult submit(EvaluationSource source) {
        Objects.requireNonNull(source, "source");
        return submit(new EvaluationRequest(EvaluationId.create(), revision, source));
    }

    /** Convenience overload for a label-backed source origin. */
    public EvaluationResult submit(String label, String text) {
        return submit(EvaluationSource.of(label, text));
    }

    /**
     * Submits a caller-identified request against its exact base revision.
     * Stale or future revisions are API misuse and are rejected rather than
     * silently compiling against a different namespace.
     */
    public EvaluationResult submit(EvaluationRequest request) {
        return submit(request, () -> false);
    }

    /** Submits with an admission-time cancellation probe. */
    public EvaluationResult submit(EvaluationRequest request,
                                   BooleanSupplier cancellationRequested) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        ActiveOperation operation;
        synchronized (admission) {
            EvaluationResult rejected = admissionFailure(request);
            if (rejected != null) return rejected;
            operation = new ActiveOperation(request);
            active = operation;
            try {
                if (cancellationRequested.getAsBoolean()) {
                    operation.requestCancellation();
                    return completeCancelledLocked(operation);
                }
            } catch (RuntimeException | Error failure) {
                active = null;
                throw failure;
            }
        }
        return evaluate(operation);
    }

    /**
     * Whether the owner thread currently holds an admitted lease on this
     * attachment's shared controller.  Transport compositions dispatch
     * remote operations on the root controller; those operations execute
     * inside the poll and must reuse that lease instead of beginning a
     * second evaluation.
     */
    public boolean hasAdmittedLease() {
        owner.check();
        return controller.currentEvaluation().isPresent();
    }

    /**
     * Executes one already-admitted request synchronously under the lease
     * the shared controller is currently polling.  This is the remote
     * transport composition seam: a dispatched operation that runs on this
     * attachment's own controller observes the poll's admitted lease and
     * reuses it, exactly like an owner-dispatched evaluation, instead of
     * being rejected as busy or beginning a nested evaluation.
     */
    public EvaluationResult submitAdmitted(EvaluationRequest request,
                                           BooleanSupplier cancellationRequested) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        ActiveOperation operation;
        synchronized (admission) {
            EvaluationResult rejected = dispatchAdmissionFailure(request);
            if (rejected != null) return rejected;
            io.mindspice.lyra.runtime.LyraOwnerController.EvaluationLease admitted =
                    controller.currentEvaluation().orElseThrow(() ->
                            new LyraLifecycleException("no admitted controller lease; "
                                    + "synchronous submission is required"));
            operation = new ActiveOperation(request);
            operation.admittedLease = admitted;
            active = operation;
            try {
                if (cancellationRequested.getAsBoolean()) {
                    operation.requestCancellation();
                    return completeCancelledLocked(operation);
                }
            } catch (RuntimeException | Error failure) {
                active = null;
                throw failure;
            }
        }
        return evaluate(operation);
    }

    /**
     * Publishes one owner-dispatched evaluation against the current committed
     * revision.  The request is admitted immediately and executed on the
     * application owner thread by the next generated safe point or explicit
     * {@link #poll()}; the returned handle publishes the terminal result from
     * any thread without reading root state.
     */
    public DispatchedEvaluation submitDispatch(EvaluationSource source) {
        Objects.requireNonNull(source, "source");
        return submitDispatch(new EvaluationRequest(EvaluationId.create(),
                currentRevision(), source));
    }

    /** Convenience overload for a label-backed source origin. */
    public DispatchedEvaluation submitDispatch(String label, String text) {
        return submitDispatch(EvaluationSource.of(label, text));
    }

    /**
     * Publishes a caller-identified request against its exact base revision.
     * Stale or future revisions are API misuse and are rejected rather than
     * silently compiling against a different namespace.  Publication is the
     * deliberately thread-safe control operation; live work still runs only
     * on the owner thread.
     */
    public DispatchedEvaluation submitDispatch(EvaluationRequest request) {
        Objects.requireNonNull(request, "request");
        ActiveOperation operation;
        DispatchedEvaluation dispatched;
        synchronized (admission) {
            EvaluationResult rejected = dispatchAdmissionFailure(request);
            if (rejected != null) {
                return DispatchedEvaluation.rejected(this, request, rejected);
            }
            // Truthful transient busyness: the previous evaluation's terminal
            // publication can race its lease release on the shared controller.
            if (controller.hasLiveWork()) {
                return DispatchedEvaluation.rejected(this, request,
                        new EvaluationResult.Busy(request, revision, Optional.empty()));
            }
            dispatched = new DispatchedEvaluation(this, request);
            operation = new ActiveOperation(request, dispatched);
            active = operation;
        }
        try {
            io.mindspice.lyra.runtime.LyraOwnerController.Dispatch dispatch =
                    controller.dispatch(() -> runDispatchedEvaluation(operation, dispatched));
            // Controller close/cancel may win immediately after publication.
            // Observe that terminal transition so a rejected queued request
            // cannot leave the attachment's active slot stranded forever.
            dispatch.whenTerminal(() -> dispatchTerminal(
                    operation, dispatched, dispatch));
        } catch (RuntimeException | Error failure) {
            releaseActiveIfPresent(operation);
            EvaluationResult rejected = dispatchRejection(operation, failure);
            dispatched.publish(rejected);
        }
        return dispatched;
    }

    /**
     * Explicit Java-host poll on the original owner.  It services at most one
     * pending dispatched evaluation without an application executor, and
     * never propagates an expected evaluation failure or cancellation into
     * the hosting frame.
     */
    public boolean poll() {
        owner.check();
        requireOpen();
        return controller.pollContained();
    }

    /**
     * Runs one admitted dispatched evaluation inside the owner poll.  The
     * poll's dispatch lease is already active on the shared controller; the
     * evaluation explicitly reuses that admitted context instead of beginning
     * a second lease.
     */
    private void runDispatchedEvaluation(ActiveOperation operation,
                                         DispatchedEvaluation dispatched) {
        io.mindspice.lyra.runtime.LyraOwnerController.EvaluationLease admitted =
                controller.currentEvaluation()
                        .orElseThrow(() -> new IllegalStateException(
                                "dispatched evaluation has no admitted lease"));
        operation.admittedLease = admitted;
        try {
            dispatched.publish(evaluate(operation));
        } catch (RuntimeException | Error failure) {
            dispatched.publishFailure(failure);
            throw failure;
        } catch (Throwable failure) {
            io.mindspice.lyra.runtime.LyraInternalException wrapped =
                    new io.mindspice.lyra.runtime.LyraInternalException(
                            "owner-dispatched evaluation failed", List.of(), List.of(), failure);
            dispatched.publishFailure(wrapped);
            throw wrapped;
        }
    }

    private EvaluationResult dispatchRejection(ActiveOperation operation, Throwable failure) {
        if (failure instanceof LyraClosedException) {
            return new EvaluationResult.Closed(operation.request, revision,
                    SessionLifecycleState.CLOSED);
        }
        if (failure instanceof LyraLifecycleException) {
            return new EvaluationResult.Busy(operation.request, revision, Optional.empty());
        }
        throw rethrowRuntime(failure);
    }

    /**
     * Completes an attachment handle when the controller removes a request
     * without running its operation (for example, service close or direct
     * controller cancellation). The callback only touches volatile/control
     * metadata and the attachment admission lock, never root state.
     */
    private void dispatchTerminal(
            ActiveOperation operation,
            DispatchedEvaluation dispatched,
            io.mindspice.lyra.runtime.LyraOwnerController.Dispatch terminal) {
        EvaluationResult result = null;
        Throwable failure = null;
        synchronized (admission) {
            if (active != operation) return;
            switch (terminal.status()) {
                case CANCELLED -> {
                    operation.cancellationRequested = true;
                    result = completeCancelledLocked(operation);
                }
                case CLOSED -> {
                    active = null;
                    result = new EvaluationResult.Closed(operation.request, revision,
                            SessionLifecycleState.CLOSED);
                }
                case FAILED -> {
                    active = null;
                    Throwable cause = terminal.failure().orElseGet(
                            () -> new LyraLifecycleException("owner dispatch failed"));
                    if (cause instanceof LyraClosedException) {
                        result = new EvaluationResult.Closed(operation.request, revision,
                                SessionLifecycleState.CLOSED);
                    } else if (cause instanceof LyraLifecycleException) {
                        result = new EvaluationResult.Busy(operation.request, revision,
                                Optional.empty());
                    } else {
                        failure = cause;
                    }
                }
                default -> {
                    return;
                }
            }
        }
        if (result != null) {
            dispatched.publish(result);
        } else if (failure != null) {
            dispatched.publishFailure(failure);
        }
    }

    private static RuntimeException rethrowRuntime(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new io.mindspice.lyra.runtime.LyraInternalException(
                "dispatch publication failed", List.of(), List.of(), failure);
    }

    /** Called only while holding admission; no source or namespace work occurs here. */
    private EvaluationResult admissionFailure(EvaluationRequest request) {
        EvaluationResult failure = dispatchAdmissionFailure(request);
        if (failure != null) return failure;
        try {
            if (registration.isClosed()
                    || registration.rootLifetime().isClosed()) {
                return new EvaluationResult.Closed(request, revision,
                        SessionLifecycleState.CLOSED);
            }
        } catch (LyraClosedException closedRoot) {
            return new EvaluationResult.Closed(request, revision, SessionLifecycleState.CLOSED);
        }
        owner.check();
        if (controller.hasLiveWork()) {
            return new EvaluationResult.Busy(request, revision, Optional.empty());
        }
        return null;
    }

    /**
     * Thread-safe admission shared by synchronous and dispatched submission.
     * It reads only safely published control metadata; no root or module
     * state is inspected.  The root-closed case for a dispatched request is
     * surfaced when the controller rejects publication with a closed failure.
     */
    private EvaluationResult dispatchAdmissionFailure(EvaluationRequest request) {
        SessionRevision current = revision;
        if (request.revision().value() > current.value()) {
            throw new IllegalArgumentException(
                    "evaluation request targets a future session revision: "
                            + request.revision() + " > " + current);
        }
        if (lifecycle == SessionLifecycleState.CLOSED) {
            return new EvaluationResult.Closed(request, current, SessionLifecycleState.CLOSED);
        }
        if (lifecycle == SessionLifecycleState.FAILED) {
            throw new LyraLifecycleException("attachment is failed");
        }
        if (!request.revision().equals(current)) {
            throw new IllegalArgumentException(
                    "evaluation request targets stale session revision: "
                            + request.revision() + ", current is " + current);
        }
        if (active != null) {
            if (active.request.evaluationId().equals(request.evaluationId())) {
                throw new IllegalArgumentException(
                        "evaluation identity is already active: " + request.evaluationId());
            }
            return new EvaluationResult.Busy(request, current, Optional.of(active.request.evaluationId()));
        }
        return null;
    }

    /**
     * Requests cooperative cancellation for exactly one active evaluation.
     * This control operation is safe from a non-owner thread and returns
     * {@code false} when the identity is not currently active.
     */
    public boolean cancel(EvaluationId evaluationId) {
        Objects.requireNonNull(evaluationId, "evaluationId");
        synchronized (admission) {
            if (lifecycle != SessionLifecycleState.OPEN || active == null
                    || !active.request.evaluationId().equals(evaluationId)) {
                return false;
            }
            active.requestCancellation();
            return true;
        }
    }

    /** Cancels only the exact handle admitted by this attachment generation. */
    boolean cancel(DispatchedEvaluation dispatched) {
        Objects.requireNonNull(dispatched, "dispatched");
        synchronized (admission) {
            if (lifecycle != SessionLifecycleState.OPEN || active == null
                    || active.dispatched != dispatched) {
                return false;
            }
            active.requestCancellation();
            return true;
        }
    }

    /**
     * Clears scratch names, aliases, history and control state without moving
     * the revision backwards.  Root-held values, the retained structural
     * domain, and every borrowed producer survive the reset.
     */
    public void reset() {
        owner.check();
        synchronized (admission) {
            requireOpen();
            if (active != null) {
                active.requestCancellation();
                throw new LyraLifecycleException("cannot reset while an evaluation is active");
            }
            if (controller.hasLiveWork()) {
                throw new LyraLifecycleException("cannot reset while an owner operation is active");
            }
            resetLocked();
        }
    }

    /**
     * Owner-dispatched reset executed under the shared controller's current
     * dispatch lease.  A remote reset operation dispatched on this
     * attachment's own controller is itself the admitted owner work, so the
     * ordinary live-work probe must not reject it.
     */
    public void resetAdmitted() {
        owner.check();
        synchronized (admission) {
            requireOpen();
            if (active != null) {
                active.requestCancellation();
                throw new LyraLifecycleException("cannot reset while an evaluation is active");
            }
            resetLocked();
        }
    }

    /** Called only while holding admission; no source or namespace work occurs here. */
    private void resetLocked() {
        storage.reset();
        storageBindings.clear();
        registerBorrowedLinks();
        sourceRegistry.clearRecords();
        workspace.reset();
        Map<String, ExternalBinding> bindings = new LinkedHashMap<>();
        context.rootBindings().forEach(binding -> bindings.put(binding.name(), binding));
        compilerSnapshot = new SessionSnapshot(
                new io.mindspice.lyra.compiler.session.SessionRevision(revision.value()),
                bindings,
                Map.of(),
                context.pinnedModules(),
                compilerSnapshot.allocator(),
                Optional.of(context.rootCertificate()),
                context.moduleEnvironment());
    }

    /**
     * Closes this service/control surface.  The externally owned root, its
     * retained values, and the root-lifetime type/producer domain remain
     * open; a later {@link #open} reuses them with a fresh scratch workspace.
     */
    @Override
    public void close() {
        owner.check();
        synchronized (admission) {
            if (lifecycle == SessionLifecycleState.CLOSED) {
                return;
            }
            if (active != null) {
                active.requestCancellation();
                throw new LyraLifecycleException("cannot close while an evaluation is active");
            }
            if (controller.hasLiveWork()) {
                throw new LyraLifecycleException("cannot close while an owner operation is active");
            }
            storage.close();
            storageBindings.clear();
            lifecycle = SessionLifecycleState.CLOSED;
            workspace.close();
            sourceRegistry.clearRecords();
            registration.close();
        }
    }

    /* Package-private read models for tests and later transport phases. */

    SessionRevision currentRevision() {
        synchronized (admission) {
            return revision;
        }
    }

    SessionLifecycleState lifecycleState() {
        owner.check();
        return lifecycle;
    }

    boolean isBusy() {
        owner.check();
        synchronized (admission) {
            requireOpen();
            return active != null;
        }
    }

    Thread ownerThread() {
        return owner.thread();
    }

    private EvaluationResult evaluate(ActiveOperation operation) {
        SourceId compilerSourceId = sourceRegistry.candidate(operation.request);
        operation.compilerSourceId = compilerSourceId;
        if (!sourceRegistry.canRetain(operation.request.source())) {
            Diagnostic diagnostic = sessionDiagnostic(
                    operation.request.source(),
                    compilerSourceId,
                    CompilerDiagnosticCodes.EMIT_UNSUPPORTED_FEATURE,
                    "the bounded session source registry cannot retain this submission");
            return completeCompilationFailure(operation, List.of(diagnostic), false);
        }
        operation.compilerSourceId = sourceRegistry.reserve(operation.request);
        operation.retained = true;
        try {
            if (operation.isCancellationRequested()) {
                return completeCancelled(operation);
            }
            SessionCompileRequest.Builder compile = SessionCompileRequest.builder()
                    .source(compilerSource(operation.request.source()))
                    .sourceId(operation.compilerSourceId)
                    .snapshot(compilerSnapshot)
                    .sourceRoots(options.sourceRoots())
                    .resolvers(options.resolvers())
                    // Structural tuple/function classes must share the root's
                    // generated package so exact typed links resolve to the
                    // same Class identities as the live root storage.
                    .javaBasePackage(context.attachmentContext().javaPackage())
                    .javaTarget(options.javaTarget())
                    .previewEnabled(options.previewEnabled())
                    .includeSources(options.includeSources())
                    .semanticOptions(options.semanticOptions())
                    .attachableScope(context.rootModule(), rootScopeNames());
            SessionCompileResult compiled = LyraCompiler.compileSession(compile.build());
            if (compiled instanceof SessionCompileResult.Failure failure) {
                return completeCompilationFailure(
                        operation, sessionDiagnostics(failure.diagnostics()), true);
            }

            SessionCompileResult.Success success = (SessionCompileResult.Success) compiled;
            operation.executionPlan = success.executionPlan();
            List<Diagnostic> diagnostics = success.diagnostics();
            if (operation.isCancellationRequested()) {
                return completeCancelled(operation);
            }

            CompiledArtifact artifact = success.artifact();
            SessionWorkspace.Pending pending;
            try {
                pending = workspace.stage(success);
            } catch (SessionWorkspace.Conflict conflict) {
                Diagnostic diagnostic = sessionDiagnostic(
                        operation.request.source(),
                        operation.compilerSourceId,
                        conflict.code(),
                        conflict.getMessage());
                return completeCompilationFailure(operation, List.of(diagnostic), true);
            }
            if (operation.isCancellationRequested()) {
                return completeCancelled(operation);
            }

            var resultType = io.mindspice.lyra.runtime.LyraType.parse(success.typedIr()
                    .rootModule().submissionResult().orElseThrow().type().canonicalSpelling());
            var forms = success.typedIr().rootModule().body().forms();
            boolean declarationOnly = forms.isEmpty()
                    || forms.getLast() instanceof io.mindspice.lyra.compiler.ir.IrNode.Declaration;
            if (!declarationOnly && !SnapshotReader.canRepresent(resultType, options.snapshotLimits())) {
                return completeCompilationFailure(operation, List.of(sessionDiagnostic(
                        operation.request.source(), operation.compilerSourceId,
                        CompilerDiagnosticCodes.MODULE_INVALID_CONFIGURATION,
                        "snapshot output budget cannot represent the final expression type")), true);
            }
            java.util.LinkedHashMap<Long, SessionStorageDomain.Requirement> requirementIndex = new java.util.LinkedHashMap<>();
            success.typedIr().declarations().stream()
                    .flatMap(declaration -> declaration.externalBinding().stream())
                    .map(binding -> new SessionStorageDomain.Requirement(
                            binding.declarationId().ordinal(), binding.storageIdentity().map(value -> value.ordinal()).orElse(-1L),
                            binding.name(), binding.type().canonicalSpelling(),
                            binding.allowsRebinding()))
                    .forEach(required -> requirementIndex.put(required.id(), required));
            success.typedIr().sessionExecution().orElseThrow().externalAccesses().stream()
                    .map(access -> {
                        var declaration = access.target().declaration();
                        boolean mutable = declaration.contract().orElseThrow().isMutable();
                        return new SessionStorageDomain.Requirement(
                                declaration.id().ordinal(), mutable ? declaration.id().ordinal() : -1L,
                                declaration.name(), declaration.contract().orElseThrow().valueType().canonicalSpelling(),
                                access.writableFacade());
                    })
                    .forEach(required -> requirementIndex.putIfAbsent(required.id(), required));
            var requirements = List.copyOf(requirementIndex.values());
            var capabilities = requirements.stream().map(required -> storageBindings.get(required.id())).toList();
            List<io.mindspice.lyra.compiler.source.SourceSnapshot> graphSources = success.moduleGraph()
                    .modules().stream().map(io.mindspice.lyra.compiler.source.ModuleGraph.Node::snapshot)
                    .toList();
            if (!sourceRegistry.canRetainGraph(graphSources)) {
                return completeCompilationFailure(operation, List.of(sessionDiagnostic(
                        operation.request.source(), operation.compilerSourceId,
                        CompilerDiagnosticCodes.EMIT_UNSUPPORTED_FEATURE,
                        "the bounded session source registry cannot retain the complete module graph")), true);
            }
            SessionStorageDomain.Linkage linkage;
            try {
                linkage = storage.link(artifact, revision.value(), requirements, capabilities);
            } catch (LyraRuntimeException | NullPointerException failure) {
                return completeCompilationFailure(operation, List.of(sessionDiagnostic(
                        operation.request.source(), operation.compilerSourceId,
                        CompilerDiagnosticCodes.SESSION_EXTERNAL_BINDING_UNSUPPORTED,
                        "typed storage linkage was rejected before execution: " + failure.getMessage())), true);
            }
            LoadedArtifact loaded = null;
            ModuleHandle module = null;
            boolean retained = false;
            io.mindspice.lyra.runtime.LyraOwnerController.EvaluationLease lease =
                    operation.admittedLease == null
                            ? storage.beginEvaluation()
                            : storage.beginEvaluation(operation.admittedLease);
            operation.lease = lease;
            if (operation.isCancellationRequested()) lease.requestCancellation();
            try {
                loaded = LyraRuntime.loadSubmission(artifact,
                        LoadOptions.defaults().withPreviewEnabled(options.previewEnabled())
                                .withIoEnvironment(options.ioEnvironment()), linkage);
                module = LyraRuntime.prepareSubmission(loaded);
                operation.module = module;
                // Retain under the root lifetime before executing: completed
                // assignments can publish values into root storage even when
                // the new namespace never commits.  The resource record keeps
                // both the exact producer and its loading context alive until
                // the external root closes, not merely until this service closes.
                Generation generation = new Generation(loaded, module, artifact.classes().keySet());
                registration.rootLifetime().retain(generation,
                        SessionStorageDomain.RetentionKind.ROOT_OWNED);
                generations.add(generation);
                retained = true;
                sourceRegistry.retainGraph(graphSources);
                compilerSnapshot = success.retainAttemptedFlow();
                LyraRuntime.executeSubmission(module);
                Optional<ValueSnapshot> value = declarationOnly ? Optional.empty() : Optional.of(SnapshotReader.read(
                        module, resultType, options.snapshotLimits(), snapshotClasses()));
                Map<Long, SessionStorageDomain.Binding> stagedStorage = new LinkedHashMap<>();
                for (var declaration : success.typedIr().declarations()) {
                    if (declaration.kind() != io.mindspice.lyra.compiler.semantic.DeclarationKind.LET
                            || declaration.contract().isEmpty()
                            || !declaration.scopeId().equals(success.resolvedGraph()
                            .module(declaration.moduleId()).orElseThrow().rootScope())
                            || declaration.imported()) {
                        continue;
                    }
                    boolean stagedRoot = success.stagedDeclarations().contains(declaration.id());
                    boolean newProducer = success.executionPlan().module(declaration.moduleId())
                            .filter(work -> work.isNew() && !work.scratch())
                            .isPresent() && declaration.visibility() == io.mindspice.lyra.compiler.semantic.DeclarationVisibility.PUBLIC;
                    if (!stagedRoot && !newProducer) continue;
                    var required = new SessionStorageDomain.Requirement(
                            declaration.id().ordinal(), declaration.isMutable() ? declaration.id().ordinal() : -1L,
                            declaration.name(), declaration.contract().orElseThrow().valueType().canonicalSpelling(),
                            declaration.isMutable());
                    if (!stagedStorage.containsKey(required.id())) {
                        stagedStorage.put(required.id(), storage.register(module,
                                toRuntimeModuleId(declaration.moduleId()), required));
                    }
                }
                return completeSuccess(operation, pending, success.stagedSnapshot(),
                        value, diagnostics, stagedStorage);
            } catch (LyraRuntimeException failure) {
                return completeRuntimeFailure(operation, failure, diagnostics);
            } finally {
                if (!retained) {
                    if (module != null) module.close();
                    if (loaded != null) loaded.close();
                }
                // A direct synchronous submission owns its lease and ends it
                // here on every compile/link/runtime/cancel path.  An
                // owner-dispatched evaluation reuses the poll's admitted
                // lease, which the poll's finally ends exactly once.
                if (operation.admittedLease == null) {
                    lease.close();
                }
            }
        } catch (LyraCompilerBugException failure) {
            failAttachment(operation);
            throw failure;
        } finally {
            releaseActiveIfPresent(operation);
        }
    }

    private java.util.Set<String> rootScopeNames() {
        java.util.Set<String> names = new java.util.HashSet<>();
        context.rootBindings().forEach(binding -> names.add(binding.name()));
        return names;
    }

    private java.util.Set<String> snapshotClasses() {
        java.util.Set<String> classes = new java.util.HashSet<>(rootClassNames);
        generations.forEach(generation -> classes.addAll(generation.generatedClasses()));
        return classes;
    }

    private static io.mindspice.lyra.runtime.ModuleId toRuntimeModuleId(
            io.mindspice.lyra.compiler.source.ModuleId moduleId) {
        return moduleId.isUri()
                ? io.mindspice.lyra.runtime.ModuleId.uri(moduleId.asUri())
                : io.mindspice.lyra.runtime.ModuleId.path(moduleId.value());
    }

    private static io.mindspice.lyra.compiler.api.EvaluationSource compilerSource(
            EvaluationSource source) {
        SourceOrigin origin = source.origin();
        io.mindspice.lyra.compiler.api.SourceOrigin compilerOrigin =
                new io.mindspice.lyra.compiler.api.SourceOrigin(
                        origin.label(), origin.uri(), origin.documentVersion(),
                        origin.originStartOffset(), origin.originEndOffset());
        return new io.mindspice.lyra.compiler.api.EvaluationSource(compilerOrigin, source.text());
    }

    private EvaluationResult completeCompilationFailure(
            ActiveOperation operation, List<Diagnostic> diagnostics, boolean retain) {
        synchronized (admission) {
            ensureActive(operation);
            if (operation.isCancellationRequested()) {
                return completeCancelledLocked(operation);
            }
            EvaluationResult result = new EvaluationResult.CompilationFailure(
                    operation.request, revision, diagnostics, initializerProgress(operation));
            recordLocked(operation, SourceRecordStatus.COMPILATION_FAILURE,
                    Optional.empty(), diagnostics, retain);
            active = null;
            return result;
        }
    }

    private EvaluationResult completeRuntimeFailure(
            ActiveOperation operation, LyraRuntimeException failure, List<Diagnostic> diagnostics) {
        synchronized (admission) {
            ensureActive(operation);
            if (operation.isCancellationRequested()) {
                return completeCancelledLocked(operation);
            }
            EvaluationResult result = new EvaluationResult.RuntimeFailure(
                    operation.request, revision, failure.summary(), diagnostics,
                    failure.code(), runtimeFrames(operation, failure),
                    initializerProgress(operation));
            recordLocked(operation, SourceRecordStatus.RUNTIME_FAILURE,
                    Optional.empty(), diagnostics, true);
            active = null;
            return result;
        }
    }

    private List<RuntimeFrame> runtimeFrames(ActiveOperation operation, LyraRuntimeException failure) {
        return failure.frames().stream().limit(64).map(io.mindspice.lyra.runtime.SourceFrame::nearestOrigin)
                .map(frame -> {
                    var runtimeSource = frame.span().sourceId();
                    SourceId sourceId = runtimeSource.isUri()
                            ? SourceId.uri(runtimeSource.asUri()) : SourceId.path(runtimeSource.value());
                    EvaluationSource source = sourceId.equals(operation.compilerSourceId)
                            ? operation.request.source()
                            : sourceRegistry.source(sourceId).orElse(null);
                    SourceSpan local = SourceSpan.of(sourceId,
                            frame.span().startOffset(), frame.span().endOffset());
                    if (source == null) {
                        return new RuntimeFrame(frame.functionName(),
                                SourceOrigin.forText(frame.sourceLabel().orElse(runtimeSource.value()), local.endOffset()),
                                local, Optional.empty());
                    }
                    SourceSpan mapped = mapSpan(local, source, sourceId);
                    int end = local.startOffset() + Math.min(512, local.endOffset() - local.startOffset());
                    if (end < local.endOffset() && end > local.startOffset()
                            && Character.isHighSurrogate(source.text().charAt(end - 1))) end--;
                    String excerpt = source.text().substring(local.startOffset(), end);
                    if (end < local.endOffset()) excerpt += "…";
                    return new RuntimeFrame(frame.functionName(), source.origin(), mapped, Optional.of(excerpt));
                }).toList();
    }

    private EvaluationResult completeSuccess(
            ActiveOperation operation,
            SessionWorkspace.Pending pending,
            SessionSnapshot stagedCompilerSnapshot,
            Optional<ValueSnapshot> value,
            List<Diagnostic> diagnostics,
            Map<Long, SessionStorageDomain.Binding> stagedStorage) {
        synchronized (admission) {
            ensureActive(operation);
            if (operation.isCancellationRequested()) {
                return completeCancelledLocked(operation);
            }
            storage.commit(revision.value(), List.copyOf(stagedStorage.values()));
            storageBindings.putAll(stagedStorage);
            workspace.commit(pending);
            compilerSnapshot = Objects.requireNonNull(stagedCompilerSnapshot,
                    "stagedCompilerSnapshot");
            revision = pending.state().revision();
            EvaluationResult result = new EvaluationResult.Success(
                    operation.request, revision, value, diagnostics,
                    initializerProgress(operation));
            recordLocked(operation, SourceRecordStatus.COMMITTED,
                    Optional.of(revision), diagnostics, true);
            active = null;
            return result;
        }
    }

    private EvaluationResult completeCancelled(ActiveOperation operation) {
        synchronized (admission) {
            ensureActive(operation);
            return completeCancelledLocked(operation);
        }
    }

    private EvaluationResult completeCancelledLocked(ActiveOperation operation) {
        EvaluationResult result = new EvaluationResult.Cancelled(
                operation.request,
                revision,
                Cancellation.observed(operation.request.evaluationId()),
                initializerProgress(operation));
        recordLocked(operation, SourceRecordStatus.CANCELLED,
                Optional.empty(), List.of(), operation.retained);
        active = null;
        return result;
    }

    private void recordLocked(
            ActiveOperation operation,
            SourceRecordStatus status,
            Optional<SessionRevision> publishedRevision,
            List<Diagnostic> diagnostics,
            boolean retain) {
        if (!retain) {
            return;
        }
        sourceRegistry.append(new SourceRecord(
                operation.request,
                Objects.requireNonNull(operation.compilerSourceId, "compilerSourceId"),
                status,
                publishedRevision,
                diagnostics));
    }

    private static InitializerProgress initializerProgress(ActiveOperation operation) {
        if (operation.executionPlan == null) {
            return InitializerProgress.empty();
        }
        if (operation.module == null) {
            return InitializerProgress.scheduled(operation.executionPlan);
        }
        return InitializerProgress.actual(operation.executionPlan, operation.module);
    }

    private void ensureActive(ActiveOperation operation) {
        if (active != operation) {
            throw new IllegalStateException("evaluation is no longer active");
        }
    }

    private void releaseActiveIfPresent(ActiveOperation operation) {
        synchronized (admission) {
            if (active == operation) {
                active = null;
            }
        }
    }

    private void failAttachment(ActiveOperation operation) {
        synchronized (admission) {
            if (active == operation) {
                lifecycle = SessionLifecycleState.FAILED;
                active = null;
            }
        }
    }

    private void requireOpen() {
        if (lifecycle == SessionLifecycleState.CLOSED) {
            throw new LyraLifecycleException("attachment is closed");
        }
        if (lifecycle == SessionLifecycleState.FAILED) {
            throw new LyraLifecycleException("attachment is failed");
        }
    }

    private static List<Diagnostic> sessionDiagnostics(List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                .map(diagnostic -> diagnostic.code().equals(CompilerDiagnosticCodes.SESSION_NAME_CONFLICT)
                        ? new Diagnostic(
                                CompilerDiagnosticCodes.RESOLVE_PUBLIC_REDECLARATION,
                                diagnostic.severity(), diagnostic.summary(),
                                diagnostic.primarySpan(), diagnostic.relatedSpans())
                        : diagnostic)
                .toList();
    }

    private static SourceSpan mapSpan(
            SourceSpan span, EvaluationSource source, SourceId compilerSourceId) {
        if (!span.sourceId().equals(compilerSourceId)) {
            return span;
        }
        if (span.endOffset() > source.text().length()) {
            throw new IllegalArgumentException(
                    "compiler diagnostic span exceeds submitted source: " + span);
        }
        SourceId displaySource = source.origin().uri()
                .map(SourceId::uri)
                .orElse(compilerSourceId);
        return SourceSpan.of(
                displaySource,
                source.origin().mapOffset(span.startOffset()),
                source.origin().mapOffset(span.endOffset()));
    }

    private static Diagnostic sessionDiagnostic(
            EvaluationSource source,
            SourceId compilerSourceId,
            io.mindspice.lyra.compiler.diagnostic.DiagnosticCode code,
            String summary) {
        SourceSpan local = SourceSpan.at(compilerSourceId, 0);
        return Diagnostic.error(code, mapSpan(local, source, compilerSourceId), summary);
    }

    private record Generation(LoadedArtifact loaded, ModuleHandle module,
                              java.util.Set<String> generatedClasses) implements AutoCloseable {
        private Generation {
            Objects.requireNonNull(loaded, "loaded");
            Objects.requireNonNull(module, "module");
            generatedClasses = java.util.Set.copyOf(generatedClasses);
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            try {
                module.close();
            } catch (RuntimeException current) {
                failure = current;
            }
            try {
                loaded.close();
            } catch (RuntimeException current) {
                if (failure == null) {
                    failure = current;
                } else {
                    failure.addSuppressed(current);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class ActiveOperation {
        private final EvaluationRequest request;
        private final DispatchedEvaluation dispatched;
        private volatile boolean cancellationRequested;
        private volatile io.mindspice.lyra.runtime.LyraOwnerController.EvaluationLease lease;
        /** Owner-dispatched evaluations reuse the poll's admitted lease. */
        private volatile io.mindspice.lyra.runtime.LyraOwnerController.EvaluationLease admittedLease;
        private volatile ModuleHandle module;
        private io.mindspice.lyra.compiler.session.SessionExecutionPlan executionPlan;
        private SourceId compilerSourceId;
        private boolean retained;

        private ActiveOperation(EvaluationRequest request) {
            this(request, null);
        }

        private ActiveOperation(EvaluationRequest request, DispatchedEvaluation dispatched) {
            this.request = Objects.requireNonNull(request, "request");
            this.dispatched = dispatched;
        }

        private boolean requestCancellation() {
            cancellationRequested = true;
            var current = lease;
            if (current != null) current.requestCancellation();
            return true;
        }

        private boolean isCancellationRequested() {
            return cancellationRequested;
        }
    }
}
