package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.api.CompiledArtifact;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.compiler.api.SessionCompileRequest;
import io.mindspice.lyra.compiler.api.SessionCompileResult;
import io.mindspice.lyra.compiler.api.LyraCompilerBugException;
import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.source.LogicalModuleId;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSpan;

import io.mindspice.lyra.runtime.LoadOptions;
import io.mindspice.lyra.runtime.LyraLifecycleException;
import io.mindspice.lyra.runtime.LyraRuntime;
import io.mindspice.lyra.runtime.LyraRuntimeException;
import io.mindspice.lyra.runtime.ModuleHandle;
import io.mindspice.lyra.runtime.LoadedArtifact;
import io.mindspice.lyra.runtime.OwnerThread;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * A reusable, owner-thread-confined Lyra evaluation session.
 *
 * <p>A submission is compiled against an immutable compiler session snapshot
 * and, for a single-source artifact, instantiated through {@link LyraRuntime}.
 * Its staged namespace is published only after successful execution. Scalar,
 * array and non-callable tuple bindings retain exact accessors to their original
 * initialized storage; structural JVM types share a session-owned loading domain,
 * and certified callable captures retain their producer generations.
 * Expression results are bounded immutable snapshots. Source-local callable-bearing
 * values are admitted through compiler-issued flow certificates; imported module
 * graphs execute through authenticated prepared state shells and exact
 * producer-qualified links.</p>
 *
 * <p>Submission is synchronous. The opening thread owns the session and is
 * the only thread allowed to submit, reset, or close it. Cancellation is the
 * one cross-thread control operation: it only marks the matching active
 * evaluation and is observed at generated form/function/tail-loop boundaries.
 * No executor or background
 * thread is created.</p>
 */
public final class LyraSession implements AutoCloseable {
    private final SessionOptions options;
    private final OwnerThread owner;
    private final Object admission = new Object();
    private final SourceRegistry sourceRegistry;
    private final SessionWorkspace workspace = new SessionWorkspace();
    private final io.mindspice.lyra.runtime.SessionStorageDomain storage = new io.mindspice.lyra.runtime.SessionStorageDomain();
    private final java.util.Map<Long, io.mindspice.lyra.runtime.SessionStorageDomain.Binding> storageBindings = new java.util.HashMap<>();
    private final java.util.Map<Long, io.mindspice.lyra.runtime.SessionStorageDomain.NominalFactory> nominalFactories = new java.util.HashMap<>();
    private final java.util.List<Generation> generations = new java.util.ArrayList<>();
    /** Compiler-facing immutable namespace; publication follows execution. */
    private io.mindspice.lyra.compiler.session.SessionSnapshot compilerSnapshot =
            io.mindspice.lyra.compiler.session.SessionSnapshot.empty();

    private volatile SessionLifecycleState lifecycle = SessionLifecycleState.OPEN;
    private volatile SessionRevision revision = SessionRevision.initial();
    /** Guarded by {@link #admission}; reads are only for admission/cancellation. */
    private ActiveOperation active;
    /** Guarded by {@link #admission}; blocks reentrant session mutation during \\type. */
    private boolean typeQueryActive;

    private LyraSession(SessionOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        owner = OwnerThread.capture();
        sourceRegistry = new SourceRegistry(options);
    }

    /** Opens an empty session owned by the calling thread. */
    public static LyraSession open() {
        return open(SessionOptions.defaults());
    }

    /** Opens an empty session with immutable compiler and retention options. */
    public static LyraSession open(SessionOptions options) {
        return new LyraSession(Objects.requireNonNull(options, "options"));
    }

    /** Alias for {@link #open()}. */
    public static LyraSession create() {
        return open();
    }

    /** Alias for {@link #open(SessionOptions)}. */
    public static LyraSession create(SessionOptions options) {
        return open(options);
    }

    /**
     * The configured source-discovery roots. Read-only configuration data
     * used by execution-host file listing; it never exposes live state.
     */
    public List<Path> sourceRoots() {
        return options.sourceRoots();
    }

    /**
     * Submits source using the current committed revision and a new identity.
     * The returned result is terminal; this method never queues a second
     * operation.
     */
    public EvaluationResult submit(EvaluationSource source) {
        Objects.requireNonNull(source, "source");
        return submit(new EvaluationRequest(
                EvaluationId.create(), revision, source));
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

    /**
     * Submits with an admission-time cancellation probe used by transport
     * adapters. The probe is checked after this session has installed the
     * active operation, closing the race where cancellation could otherwise
     * arrive between a transport token check and session admission.
     */
    public EvaluationResult submit(
            EvaluationRequest request, BooleanSupplier cancellationRequested) {
        return submit(request, cancellationRequested, Optional.empty(), Optional.empty());
    }

    /** Reloads one retained REPL-owned module by logical name or namespace alias. */
    public EvaluationResult reload(String moduleOrAlias) {
        Objects.requireNonNull(moduleOrAlias, "moduleOrAlias");
        return reload(moduleOrAlias, EvaluationId.create(), () -> false);
    }

    /** Owner-confined explicit reload; old generations are never retargeted. */
    public EvaluationResult reload(LogicalModuleId logicalModule) {
        Objects.requireNonNull(logicalModule, "logicalModule");
        owner.check();
        return reload(logicalModule, EvaluationId.create(), () -> false);
    }

    /**
     * Owner-confined reload carrying the caller's evaluation identity and an
     * admission-time cancellation probe. Used by owner adapters that must
     * correlate cancellation to an exact external request identity.
     */
    public EvaluationResult reload(
            LogicalModuleId logicalModule, EvaluationId evaluationId,
            BooleanSupplier cancellationRequested) {
        Objects.requireNonNull(logicalModule, "logicalModule");
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        owner.check();
        String alias = "__lyra_reload_" + evaluationId.value().toString().replace("-", "");
        EvaluationSource source = EvaluationSource.of(
                "reload " + logicalModule.value(),
                "import " + logicalModule.value() + " as " + alias);
        return submit(new EvaluationRequest(evaluationId, revision, source),
                cancellationRequested, Optional.of(logicalModule), Optional.of(alias));
    }

    /**
     * Owner-confined reload by logical name or namespace alias carrying the
     * caller's evaluation identity and cancellation probe.
     */
    public EvaluationResult reload(
            String moduleOrAlias, EvaluationId evaluationId,
            BooleanSupplier cancellationRequested) {
        Objects.requireNonNull(moduleOrAlias, "moduleOrAlias");
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        owner.check();
        List<LogicalModuleId> matches = new java.util.ArrayList<>();
        compilerSnapshot.importAlias(moduleOrAlias)
                .filter(io.mindspice.lyra.compiler.session.SessionImport::isModuleNamespace)
                .map(io.mindspice.lyra.compiler.session.SessionImport::logicalModule)
                .ifPresent(matches::add);
        try {
            LogicalModuleId logical = LogicalModuleId.of(moduleOrAlias);
            if (compilerSnapshot.moduleEnvironment().module(logical).isPresent()
                    || matches.isEmpty()) {
                matches.add(logical);
            }
        } catch (IllegalArgumentException invalidLogical) {
            if (matches.isEmpty()) {
                return reloadTargetFailure(moduleOrAlias,
                        CompilerDiagnosticCodes.MODULE_INVALID_IMPORT_PATH,
                        "invalid reload target: " + invalidLogical.getMessage());
            }
        }
        List<LogicalModuleId> distinct = matches.stream().distinct().toList();
        if (distinct.size() != 1) {
            return reloadTargetFailure(moduleOrAlias,
                    CompilerDiagnosticCodes.MODULE_DUPLICATE_IDENTITY,
                    "reload target is ambiguous: " + moduleOrAlias);
        }
        return reload(distinct.getFirst(), evaluationId, cancellationRequested);
    }

    private EvaluationResult reloadTargetFailure(
            String target, io.mindspice.lyra.compiler.diagnostic.DiagnosticCode code,
            String summary) {
        EvaluationSource source = EvaluationSource.of("reload " + target, "");
        EvaluationRequest request = new EvaluationRequest(
                EvaluationId.create(), revision, source);
        SourceId sourceId = SourceId.path("repl/reload-target.lyra");
        synchronized (admission) {
            EvaluationResult rejected = admissionFailure(request);
            if (rejected != null) return rejected;
            return new EvaluationResult.CompilationFailure(
                    request, revision, List.of(Diagnostic.error(
                            code, SourceSpan.at(sourceId, 0), summary)));
        }
    }

    private EvaluationResult submit(
            EvaluationRequest request,
            BooleanSupplier cancellationRequested,
            Optional<LogicalModuleId> reloadModule,
            Optional<String> reloadImportAlias) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        Objects.requireNonNull(reloadModule, "reloadModule");
        Objects.requireNonNull(reloadImportAlias, "reloadImportAlias");
        ActiveOperation operation;
        synchronized (admission) {
            EvaluationResult rejected = admissionFailure(request);
            if (rejected != null) return rejected;
            operation = new ActiveOperation(request, reloadModule, reloadImportAlias);
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

    /** Called only while holding admission; no source or namespace work occurs here. */
    private EvaluationResult admissionFailure(EvaluationRequest request) {
        SessionRevision current = revision;
        if (request.revision().value() > current.value()) {
            throw new IllegalArgumentException(
                    "evaluation request targets a future session revision: "
                            + request.revision() + " > " + current);
        }
        if (lifecycle == SessionLifecycleState.CLOSED) {
            return new EvaluationResult.Closed(request, current, lifecycle);
        }
        if (lifecycle == SessionLifecycleState.FAILED) {
            throw new LyraLifecycleException("session is failed");
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
        if (typeQueryActive) {
            return new EvaluationResult.Busy(request, current, Optional.empty());
        }
        owner.check();
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

    /**
     * Checks one source unit without executing or publishing it. This is a
     * console-only operation; it uses the same immutable compiler snapshot as
     * the next submission and never reserves source history.
     */
    TypeQuery type(EvaluationSource source) {
        Objects.requireNonNull(source, "source");
        owner.check();

        final EvaluationRequest request;
        final io.mindspice.lyra.compiler.session.SessionSnapshot snapshot;
        synchronized (admission) {
            requireOpen();
            if (active != null || typeQueryActive) {
                return TypeQuery.busyResult();
            }
            // Admit the whole compiler operation before reading the snapshot.
            // This blocks reentrant evaluation/reset/close from a resolver and
            // keeps a query tied to one immutable committed revision.
            typeQueryActive = true;
            request = new EvaluationRequest(EvaluationId.create(), revision, source);
            snapshot = compilerSnapshot;
        }

        try {
            SourceId compilerSourceId = sourceRegistry.candidate(request);
            SessionCompileResult result = LyraCompiler.compileSession(
                    SessionCompileRequest.builder()
                            .source(compilerSource(source))
                            .sourceId(compilerSourceId)
                            .snapshot(snapshot)
                            .sourceRoots(options.sourceRoots())
                            .resolvers(options.resolvers())
                            .javaBasePackage(options.javaBasePackage())
                            .javaTarget(options.javaTarget())
                            .previewEnabled(options.previewEnabled())
                            .includeSources(options.includeSources())
                            .semanticOptions(options.semanticOptions())
                            .build());
            if (result instanceof SessionCompileResult.Failure failure) {
                return TypeQuery.failure(failure.diagnostics());
            }
            SessionCompileResult.Success success = (SessionCompileResult.Success) result;
            var forms = success.typedIr().rootModule().body().forms();
            String type = forms.isEmpty()
                    ? success.typedIr().rootModule().body().type().canonicalSpelling()
                    : forms.getLast().type().canonicalSpelling();
            return TypeQuery.success(type, success.diagnostics());
        } finally {
            synchronized (admission) {
                typeQueryActive = false;
            }
        }
    }

    /** Immutable result for the package-private console type query. */
    record TypeQuery(Optional<String> canonicalType, List<Diagnostic> diagnostics, boolean busy) {
        TypeQuery {
            canonicalType = Objects.requireNonNull(canonicalType, "canonicalType");
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
            if (busy && (canonicalType.isPresent() || !diagnostics.isEmpty())) {
                throw new IllegalArgumentException("busy type query cannot contain a result");
            }
        }

        TypeQuery(Optional<String> canonicalType, List<Diagnostic> diagnostics) {
            this(canonicalType, diagnostics, false);
        }

        static TypeQuery success(String canonicalType, List<Diagnostic> diagnostics) {
            return new TypeQuery(Optional.of(Objects.requireNonNull(canonicalType, "canonicalType")),
                    diagnostics);
        }

        static TypeQuery failure(List<Diagnostic> diagnostics) {
            return new TypeQuery(Optional.empty(), diagnostics);
        }

        static TypeQuery busyResult() {
            return new TypeQuery(Optional.empty(), List.of(), true);
        }
    }

    /** Clears committed scratch metadata without moving the revision backwards. */
    public void reset() {
        owner.check();
        synchronized (admission) {
            requireOpen();
            if (active != null) {
                active.requestCancellation();
                throw new LyraLifecycleException("cannot reset while an evaluation is active");
            }
            if (typeQueryActive) {
                throw new LyraLifecycleException("cannot reset while a type query is active");
            }
            closeGenerations();
            storage.reset();
            storageBindings.clear();
            nominalFactories.clear();
            sourceRegistry.clear();
            workspace.reset();
            compilerSnapshot = new io.mindspice.lyra.compiler.session.SessionSnapshot(
                    new io.mindspice.lyra.compiler.session.SessionRevision(revision.value()),
                    Map.of(), Map.of(), compilerSnapshot.allocator());
        }
    }

    /**
     * Retires the session. Closing is idempotent for its owner and clears
     * retained source data; caller-owned streams and runtime roots are not
     * present in this standalone domain.
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
            if (typeQueryActive) {
                throw new LyraLifecycleException("cannot close while a type query is active");
            }
            closeGenerations();
            storage.close();
            storageBindings.clear();
            nominalFactories.clear();
            lifecycle = SessionLifecycleState.CLOSED;
            workspace.close();
            sourceRegistry.clear();
        }
    }

    /* Package-private read models for the transport/console domain and tests. */

    SessionRevision currentRevision() {
        synchronized (admission) {
            return revision;
        }
    }

    WorkspaceState.Committed workspaceState() {
        owner.check();
        return workspace.committedState();
    }

    List<SourceRecord> sourceRecords() {
        owner.check();
        return sourceRegistry.records();
    }

    SessionLifecycleState lifecycleState() {
        owner.check();
        return lifecycle;
    }

    boolean isBusy() {
        owner.check();
        synchronized (admission) {
            requireOpen();
            return active != null || typeQueryActive;
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
                    .javaBasePackage(options.javaBasePackage())
                    .javaTarget(options.javaTarget())
                    .previewEnabled(options.previewEnabled())
                    .includeSources(options.includeSources())
                    .semanticOptions(options.semanticOptions());
            operation.reloadModule.ifPresent(compile::reloadModule);
            operation.reloadImportAlias.ifPresent(compile::reloadImportAlias);
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
                            .rootModule().submissionResult().orElseThrow().type().canonicalSpelling(),
                    artifact.metadata().nominalSchemas());
            var forms = success.typedIr().rootModule().body().forms();
            boolean declarationOnly = forms.isEmpty()
                    || forms.getLast() instanceof io.mindspice.lyra.compiler.ir.IrNode.Declaration;
            if (!declarationOnly && !SnapshotReader.canRepresent(resultType, options.snapshotLimits())) {
                return completeCompilationFailure(operation, List.of(sessionDiagnostic(
                        operation.request.source(), operation.compilerSourceId,
                        CompilerDiagnosticCodes.MODULE_INVALID_CONFIGURATION,
                        "snapshot output budget cannot represent the final expression type")), true);
            }
            java.util.LinkedHashMap<Long, io.mindspice.lyra.runtime.SessionStorageDomain.Requirement> requirementIndex = new java.util.LinkedHashMap<>();
            success.typedIr().declarations().stream()
                    .flatMap(declaration -> declaration.externalBinding().stream())
                    .map(binding -> new io.mindspice.lyra.runtime.SessionStorageDomain.Requirement(
                            binding.declarationId().ordinal(), binding.storageIdentity().map(value -> value.ordinal()).orElse(-1L),
                            binding.name(), binding.type().canonicalSpelling(),
                            binding.allowsRebinding()))
                    .forEach(required -> requirementIndex.put(required.id(), required));
            success.typedIr().sessionExecution().orElseThrow().externalAccesses().stream()
                    .map(access -> {
                        var declaration = access.target().declaration();
                        boolean mutable = declaration.contract().orElseThrow().isMutable();
                        return new io.mindspice.lyra.runtime.SessionStorageDomain.Requirement(
                                declaration.id().ordinal(), mutable ? declaration.id().ordinal() : -1L,
                                declaration.name(), declaration.contract().orElseThrow().valueType().canonicalSpelling(),
                                access.writableFacade());
                    })
                    .forEach(required -> requirementIndex.putIfAbsent(required.id(), required));
            var requirements = List.copyOf(requirementIndex.values());
            var capabilities = requirements.stream().map(required -> storageBindings.get(required.id())).toList();
            var currentNominals = success.typedIr().modules().stream()
                    .flatMap(irModule -> irModule.body().forms().stream())
                    .filter(io.mindspice.lyra.compiler.ir.IrNode.NominalDeclaration.class::isInstance)
                    .map(io.mindspice.lyra.compiler.ir.IrNode.NominalDeclaration.class::cast)
                    .collect(java.util.stream.Collectors.toMap(
                            value -> value.declarationId().ordinal(), value -> value));
            var factoryRequirements = success.resolvedGraph().nominals().stream()
                    .filter(nominal -> !currentNominals.containsKey(nominal.declaration().ordinal()))
                    .map(nominal -> new io.mindspice.lyra.runtime.SessionStorageDomain.NominalFactoryRequirement(
                            nominal.declaration().ordinal(), nominal.schema().type().canonicalSpelling()))
                    .toList();
            var factoryCapabilities = factoryRequirements.stream()
                    .map(required -> nominalFactories.get(required.declarationId())).toList();
            List<io.mindspice.lyra.compiler.source.SourceSnapshot> graphSources = success.moduleGraph()
                    .modules().stream().map(io.mindspice.lyra.compiler.source.ModuleGraph.Node::snapshot)
                    .toList();
            if (!sourceRegistry.canRetainGraph(graphSources)) {
                return completeCompilationFailure(operation, List.of(sessionDiagnostic(
                        operation.request.source(), operation.compilerSourceId,
                        CompilerDiagnosticCodes.EMIT_UNSUPPORTED_FEATURE,
                        "the bounded session source registry cannot retain the complete module graph")), true);
            }
            io.mindspice.lyra.runtime.SessionStorageDomain.Linkage linkage;
            try {
                linkage = storage.link(artifact, revision.value(), requirements, capabilities,
                        factoryRequirements, factoryCapabilities);
            } catch (LyraRuntimeException | NullPointerException failure) {
                return completeCompilationFailure(operation, List.of(sessionDiagnostic(
                        operation.request.source(), operation.compilerSourceId,
                        CompilerDiagnosticCodes.SESSION_EXTERNAL_BINDING_UNSUPPORTED,
                        "typed storage linkage was rejected before execution: " + failure.getMessage())), true);
            }
            LoadedArtifact loaded = null;
            ModuleHandle module = null;
            boolean retained = false;
            try (var lease = storage.beginEvaluation()) {
                operation.lease = lease;
                if (operation.isCancellationRequested()) lease.requestCancellation();
                loaded = LyraRuntime.loadSubmission(artifact,
                        LoadOptions.defaults().withPreviewEnabled(options.previewEnabled())
                                .withIoEnvironment(options.ioEnvironment()), linkage);
                module = LyraRuntime.prepareSubmission(loaded);
                operation.module = module;
                // Retain before executing: completed assignments can publish values
                // into older storage even when the new namespace never commits.
                generations.add(new Generation(loaded, module, artifact.classes().keySet()));
                retained = true;
                sourceRegistry.retainGraph(graphSources);
                compilerSnapshot = success.retainAttemptedFlow();
                LyraRuntime.executeSubmission(module);
                Optional<ValueSnapshot> value = declarationOnly ? Optional.empty() : Optional.of(SnapshotReader.read(
                        module, resultType, options.snapshotLimits(), generations.stream()
                                .flatMap(generation -> generation.generatedClasses().stream())
                                .collect(java.util.stream.Collectors.toUnmodifiableSet())));
                java.util.Map<Long, io.mindspice.lyra.runtime.SessionStorageDomain.Binding> stagedStorage = new java.util.LinkedHashMap<>();
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
                    var required = new io.mindspice.lyra.runtime.SessionStorageDomain.Requirement(
                            declaration.id().ordinal(), declaration.isMutable() ? declaration.id().ordinal() : -1L,
                            declaration.name(), declaration.contract().orElseThrow().valueType().canonicalSpelling(),
                            declaration.isMutable());
                    if (!stagedStorage.containsKey(required.id())) {
                        stagedStorage.put(required.id(), storage.register(module,
                                toRuntimeModuleId(declaration.moduleId()), required));
                    }
                }
                java.util.Map<Long, io.mindspice.lyra.runtime.SessionStorageDomain.NominalFactory> stagedFactories =
                        new java.util.LinkedHashMap<>();
                for (var nominal : currentNominals.values()) {
                    var required = new io.mindspice.lyra.runtime.SessionStorageDomain.NominalFactoryRequirement(
                            nominal.declarationId().ordinal(), nominal.schema().type().canonicalSpelling());
                    stagedFactories.put(required.declarationId(), storage.registerNominalFactory(
                            module, toRuntimeModuleId(nominal.schema().type().id().module().moduleId()), required));
                }
                return completeSuccess(operation, pending, success.stagedSnapshot(),
                        value, diagnostics, stagedStorage, stagedFactories);
            } catch (LyraRuntimeException failure) {
                return completeRuntimeFailure(operation, failure, diagnostics);
            } finally {
                if (!retained) {
                    if (module != null) module.close();
                    if (loaded != null) loaded.close();
                }
            }
        } catch (LyraCompilerBugException failure) {
            failSession(operation);
            throw failure;
        } finally {
            releaseActiveIfPresent(operation);
        }
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
            io.mindspice.lyra.compiler.session.SessionSnapshot stagedCompilerSnapshot,
            Optional<ValueSnapshot> value,
            List<Diagnostic> diagnostics,
            java.util.Map<Long, io.mindspice.lyra.runtime.SessionStorageDomain.Binding> stagedStorage,
            java.util.Map<Long, io.mindspice.lyra.runtime.SessionStorageDomain.NominalFactory> stagedFactories) {
        synchronized (admission) {
            ensureActive(operation);
            if (operation.isCancellationRequested()) {
                return completeCancelledLocked(operation);
            }
            storage.commit(revision.value(), List.copyOf(stagedStorage.values()),
                    List.copyOf(stagedFactories.values()));
            storageBindings.putAll(stagedStorage);
            nominalFactories.putAll(stagedFactories);
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

    private void failSession(ActiveOperation operation) {
        synchronized (admission) {
            if (active == operation) {
                lifecycle = SessionLifecycleState.FAILED;
                active = null;
            }
        }
    }

    private void requireOpen() {
        if (lifecycle == SessionLifecycleState.CLOSED) {
            throw new LyraLifecycleException("session is closed");
        }
        if (lifecycle == SessionLifecycleState.FAILED) {
            throw new LyraLifecycleException("session is failed");
        }
    }

    private static List<Diagnostic> sessionDiagnostics(List<Diagnostic> diagnostics) {
        // Preserve the established public-redeclaration code without masking
        // unsupported composite/module linkage failures.
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

    private record Generation(LoadedArtifact loaded, ModuleHandle module, java.util.Set<String> generatedClasses) {
        private Generation { generatedClasses = java.util.Set.copyOf(generatedClasses); }
    }

    private void closeGenerations() {
        owner.check();
        for (int index = generations.size() - 1; index >= 0; index--) {
            Generation generation = generations.get(index);
            generation.module().close();
            generation.loaded().close();
        }
        generations.clear();
    }

    private static final class ActiveOperation {
        private final EvaluationRequest request;
        private final Optional<LogicalModuleId> reloadModule;
        private final Optional<String> reloadImportAlias;
        private volatile boolean cancellationRequested;
        private volatile io.mindspice.lyra.runtime.LyraOwnerController.EvaluationLease lease;
        private volatile ModuleHandle module;
        private io.mindspice.lyra.compiler.session.SessionExecutionPlan executionPlan;
        private SourceId compilerSourceId;
        private boolean retained;

        private ActiveOperation(
                EvaluationRequest request,
                Optional<LogicalModuleId> reloadModule,
                Optional<String> reloadImportAlias) {
            this.request = Objects.requireNonNull(request, "request");
            this.reloadModule = Objects.requireNonNull(reloadModule, "reloadModule");
            this.reloadImportAlias = Objects.requireNonNull(reloadImportAlias, "reloadImportAlias");
            if (this.reloadImportAlias.isPresent() && this.reloadModule.isEmpty()) {
                throw new IllegalArgumentException("reload alias requires a reload module");
            }
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
