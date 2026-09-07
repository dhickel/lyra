package io.mindspice.lyra.compiler.api;

import io.mindspice.lyra.compiler.ast.SyntaxProgram;
import io.mindspice.lyra.compiler.artifact.ArtifactAssembly;
import io.mindspice.lyra.compiler.backend.jvm.JvmBytecodeArtifact;
import io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.compiler.diagnostic.ImmutablePhaseArtifact;
import io.mindspice.lyra.compiler.diagnostic.PhaseResult;
import io.mindspice.lyra.compiler.diagnostic.RelatedSpan;
import io.mindspice.lyra.compiler.grammar.GrammarMatcher;
import io.mindspice.lyra.compiler.grammar.GrammarProgram;
import io.mindspice.lyra.compiler.identity.DeclarationId;
import io.mindspice.lyra.compiler.identity.GenerationId;
import io.mindspice.lyra.compiler.identity.IdentityAllocator;
import io.mindspice.lyra.compiler.identity.ProducerId;
import io.mindspice.lyra.compiler.ir.TypedIr;
import io.mindspice.lyra.compiler.ir.TypedIrBuilder;
import io.mindspice.lyra.compiler.lex.LexedSource;
import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.parse.Parser;
import io.mindspice.lyra.compiler.semantic.DeclarationKind;
import io.mindspice.lyra.compiler.semantic.ResolvedDeclaration;
import io.mindspice.lyra.compiler.semantic.ResolvedExport;
import io.mindspice.lyra.compiler.semantic.ResolvedModule;
import io.mindspice.lyra.compiler.semantic.ResolvedSemanticGraph;
import io.mindspice.lyra.compiler.semantic.SemanticResolver;
import io.mindspice.lyra.compiler.semantic.TypeChecker;
import io.mindspice.lyra.compiler.semantic.TypedModule;
import io.mindspice.lyra.compiler.semantic.TypedSemanticGraph;
import io.mindspice.lyra.compiler.source.ModuleGraph;
import io.mindspice.lyra.compiler.source.ModuleGraphDiscovery;
import io.mindspice.lyra.compiler.source.ModuleId;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceConfiguration;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.source.SourceSpan;
import io.mindspice.lyra.compiler.session.ExternalBinding;
import io.mindspice.lyra.compiler.session.PinnedModule;
import io.mindspice.lyra.compiler.session.SessionExecutionPlan;
import io.mindspice.lyra.compiler.session.SessionImport;
import io.mindspice.lyra.compiler.session.SessionModuleEnvironment;
import io.mindspice.lyra.compiler.session.SessionSnapshot;
import io.mindspice.lyra.compiler.session.StorageIdentity;
import io.mindspice.lyra.runtime.ArtifactMetadata;
import io.mindspice.lyra.runtime.PackagingMode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Public source-to-artifact compiler entry point. */
public final class LyraCompiler {
    private LyraCompiler() {
    }

    /** Compiles one complete reachable source graph. */
    public static CompileResult compile(CompileRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            return new Pipeline(request).run();
        } catch (VirtualMachineError | ThreadDeath failure) {
            throw failure;
        } catch (LyraCompilerBugException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new LyraCompilerBugException("compiler invariant failed outside a phase boundary", failure);
        }
    }

    /** Alias for callers that use an instance-looking operation spelling. */
    public static CompileResult compileSource(CompileRequest request) {
        return compile(request);
    }

    /**
     * Compiles one in-memory session submission through the ordinary compiler
     * phases.  The result is staged against the supplied immutable snapshot;
     * publishing it and executing the artifact remain session-owner
     * responsibilities.
     *
     * <p>References to prior source-local names are linked through the supplied
     * session snapshot and its compiler-issued flow certificate. Live storage
     * authentication and generation ownership remain runtime responsibilities;
     * imported module graphs are emitted only when their exact prepared
     * producer-qualified linkage is available.</p>
     */
    public static SessionCompileResult compileSession(SessionCompileRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            return new SessionPipeline(request).run();
        } catch (VirtualMachineError | ThreadDeath failure) {
            throw failure;
        } catch (LyraCompilerBugException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new LyraCompilerBugException(
                    "session compiler invariant failed outside a phase boundary", failure);
        }
    }

    private static final class Pipeline {
        private final CompileRequest request;
        private final ArrayList<Diagnostic> diagnostics = new ArrayList<>();

        private Pipeline(CompileRequest request) {
            this.request = request;
        }

        private CompileResult run() {
            Diagnostic configuration = validateConfiguration();
            if (configuration != null) {
                return new CompileResult.Failure(List.of(configuration));
            }

            PhaseResult<ModuleGraph> graphResult = discover();
            ModuleGraph graph = success(graphResult);
            if (graph == null) {
                return failure();
            }

            PhaseResult<ResolvedSemanticGraph> resolvedResult = SemanticResolver.resolve(graph);
            ResolvedSemanticGraph resolved = success(resolvedResult);
            if (resolved == null) {
                return failure();
            }

            PhaseResult<TypedSemanticGraph> typedResult = TypeChecker.check(resolved);
            TypedSemanticGraph typed = success(typedResult);
            if (typed == null) {
                return failure();
            }

            PhaseResult<TypedIr> irResult = TypedIrBuilder.lower(typed);
            TypedIr ir = success(irResult);
            if (ir == null) {
                return failure();
            }

            PhaseResult<JvmBytecodeArtifact> bytecodeResult = JvmBytecodeArtifact.emit(
                    ir, request.javaBasePackage());
            JvmBytecodeArtifact bytecode = success(bytecodeResult);
            if (bytecode == null) {
                return failure();
            }

            ArtifactAssembly assembly = ArtifactAssembly.assemble(
                    bytecode, PackagingMode.CLASSES, request.includeSources());
            return new CompileResult.Success(
                    new BuiltCompiledArtifact(bytecode, request.includeSources(), assembly),
                    diagnostics);
        }

        private PhaseResult<ModuleGraph> discover() {
            SourceConfiguration configuration = request.sourceConfiguration();
            if (request.rootPath().isPresent()) {
                return ModuleGraphDiscovery.discover(
                        request.rootPath().orElseThrow(), configuration);
            }
            if (request.rootModule().isPresent()) {
                return ModuleGraphDiscovery.discover(
                        request.rootModule().orElseThrow(), configuration);
            }

            SourceInput input = request.rootSource().orElseThrow();
            SourceId sourceId = input.sourceId();
            PhysicalSourceKey physical = memoryPhysicalKey(sourceId, input.text());
            PhaseResult<SourceSnapshot> snapshotResult = SourceSnapshot.capture(
                    sourceId, physical, input.utf8Bytes());
            SourceSnapshot snapshot = sourceSuccess(snapshotResult);
            if (snapshot == null) {
                return PhaseResult.failure(snapshotResult.diagnostics());
            }
            PhaseResult<LexedSource> lexedResult = Lexer.lex(snapshot);
            LexedSource lexed = sourceSuccess(lexedResult);
            if (lexed == null) {
                return PhaseResult.failure(lexedResult.diagnostics());
            }
            PhaseResult<GrammarProgram> grammarResult = GrammarMatcher.match(lexed);
            GrammarProgram grammar = sourceSuccess(grammarResult);
            if (grammar == null) {
                return PhaseResult.failure(grammarResult.diagnostics());
            }
            PhaseResult<SyntaxProgram> syntaxResult = Parser.parse(lexed, grammar);
            SyntaxProgram syntax = sourceSuccess(syntaxResult);
            if (syntax == null) {
                return PhaseResult.failure(syntaxResult.diagnostics());
            }
            return ModuleGraphDiscovery.discover(syntax, snapshot, configuration);
        }

        private <T extends ImmutablePhaseArtifact> T success(PhaseResult<T> result) {
            if (result instanceof PhaseResult.Success<T> success) {
                diagnostics.addAll(success.diagnostics());
                return success.value();
            }
            diagnostics.addAll(result.diagnostics());
            return null;
        }

        private CompileResult failure() {
            return new CompileResult.Failure(diagnostics);
        }

        private <T extends ImmutablePhaseArtifact> T sourceSuccess(PhaseResult<T> result) {
            if (result instanceof PhaseResult.Success<T> success) {
                diagnostics.addAll(success.diagnostics());
                return success.value();
            }
            return null;
        }

        private Diagnostic validateConfiguration() {
            if (request.javaTarget() != 25) {
                return Diagnostic.error(CompilerDiagnosticCodes.MODULE_INVALID_CONFIGURATION,
                        configurationSpan(), "Lyra artifacts require Java target 25");
            }
            if (!validJavaPackage(request.javaBasePackage())) {
                return Diagnostic.error(CompilerDiagnosticCodes.MODULE_INVALID_CONFIGURATION,
                        configurationSpan(), "invalid Java base package: " + request.javaBasePackage());
            }
            return null;
        }

        private SourceSpan configurationSpan() {
            SourceId source = request.rootSource().map(SourceInput::sourceId)
                    .orElseGet(() -> request.rootModule().map(value -> value.defaultSourceId())
                            .orElseGet(() -> request.rootPath()
                                    .map(value -> SourceId.path(rootName(value)))
                                    .orElse(SourceId.path("<compile-request>.lyra"))));
            return SourceSpan.at(source, 0);
        }

        private static String rootName(Path path) {
            Path fileName = path.getFileName();
            String value = fileName == null ? "root.lyra" : fileName.toString();
            return value.endsWith(".lyra") ? value : value + ".lyra";
        }
    }

    /** Session-specific pipeline; the ordinary compiler pipeline remains unchanged. */
    private static final class SessionPipeline {
        private final SessionCompileRequest request;
        private final ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        private final Map<SourceId, SourceId> sourceOrigins = new LinkedHashMap<>();

        private SessionPipeline(SessionCompileRequest request) {
            this.request = request;
            request.snapshot().moduleEnvironment().sourceInventory().forEach(source ->
                    sourceOrigins.put(source.sourceId(), source.originSourceId()));
        }

        private SessionCompileResult run() {
            Diagnostic configuration = validateConfiguration();
            if (configuration != null) {
                return fail(configuration);
            }
            ModuleGraph graph = phase(discover());
            if (graph == null) {
                return failure();
            }

            graph.modules().forEach(node -> sourceOrigins.put(node.sourceId(), node.snapshot().originSourceId()));
            IdentityReservations reservations = reserveModuleIdentities(graph);
            SessionSnapshot semanticSnapshot = request.snapshot()
                    .withAllocator(reservations.allocator());
            ResolvedSemanticGraph resolved = phase(SemanticResolver.resolveSession(
                    graph, semanticSnapshot, request.reloadModule()));
            if (resolved == null) {
                return failure();
            }

            TypedSemanticGraph typed = phase(TypeChecker.check(resolved));
            if (typed == null) {
                return failure();
            }

            SessionEnvironmentData environment = buildEnvironment(graph, resolved, typed, reservations);
            TypedIr ir = phase(TypedIrBuilder.lowerSubmission(typed, environment.plan(), environment.environment()));
            if (ir == null) {
                return failure();
            }

            JvmBytecodeArtifact bytecode = phase(JvmBytecodeArtifact.emit(
                    ir, request.javaBasePackage()));
            if (bytecode == null) {
                return failure();
            }

            StagedNamespace staged = stageNamespace(graph, resolved, typed, environment);
            if (staged.diagnostic() != null) {
                return fail(staged.diagnostic());
            }
            SessionFlowCertificate stagedCertificate = SessionFlowCertificate.issue(
                    request.snapshot(), typed, staged.snapshot().bindings(), false);
            SessionSnapshot stagedSnapshot = staged.snapshot()
                    .withFlowCertificate(stagedCertificate);
            SessionFlowCertificate attemptedCertificate = SessionFlowCertificate.issue(
                    request.snapshot(), typed, request.snapshot().bindings(), true);
            SessionSnapshot attemptedSnapshot = new SessionSnapshot(
                    request.snapshot().revision(), request.snapshot().bindings(),
                    request.snapshot().imports(), request.snapshot().pinnedModules(),
                    environment.allocator(), Optional.of(attemptedCertificate),
                    request.snapshot().moduleEnvironment());
            ArtifactAssembly assembly = ArtifactAssembly.assemble(
                    bytecode, PackagingMode.CLASSES, request.includeSources());
            return new SessionCompileResult.Success(
                    request.baseRevision(),
                    stagedSnapshot.revision(),
                    stagedSnapshot,
                    attemptedSnapshot,
                    new BuiltCompiledArtifact(bytecode, request.includeSources(), assembly),
                    graph,
                    resolved,
                    typed,
                    ir,
                    staged.declarations(),
                    staged.imports(),
                    environment.plan(),
                    mapDiagnostics(diagnostics));
        }

        private PhaseResult<ModuleGraph> discover() {
            SourceId sourceId = request.sourceId();
            PhysicalSourceKey physical = memoryPhysicalKey(sourceId, request.source().text());
            PhaseResult<SourceSnapshot> snapshotResult = SourceSnapshot.capture(
                    sourceId,
                    physical,
                    request.source().text().getBytes(StandardCharsets.UTF_8));
            SourceSnapshot snapshot = sourcePhase(snapshotResult);
            if (snapshot == null) {
                return PhaseResult.failure(snapshotResult.diagnostics());
            }

            PhaseResult<LexedSource> lexedResult = Lexer.lex(snapshot);
            LexedSource lexed = sourcePhase(lexedResult);
            if (lexed == null) {
                return PhaseResult.failure(lexedResult.diagnostics());
            }
            PhaseResult<GrammarProgram> grammarResult = GrammarMatcher.match(lexed);
            GrammarProgram grammar = sourcePhase(grammarResult);
            if (grammar == null) {
                return PhaseResult.failure(grammarResult.diagnostics());
            }
            PhaseResult<SyntaxProgram> syntaxResult = Parser.parse(lexed, grammar);
            SyntaxProgram syntax = sourcePhase(syntaxResult);
            if (syntax == null) {
                return PhaseResult.failure(syntaxResult.diagnostics());
            }
            if (request.reloadModule().isPresent()) {
                var imports = syntax.imports();
                boolean valid = syntax.forms().isEmpty() && imports.size() == 1;
                if (valid) {
                    var imported = imports.getFirst();
                    valid = imported.selection().isEmpty() && imported.modifiers().isEmpty()
                            && io.mindspice.lyra.compiler.source.LogicalModuleId.fromImportPath(imported.path())
                            .equals(request.reloadModule().orElseThrow())
                            && request.reloadImportAlias().map(alias -> imported.alias()
                            .map(value -> value.name().equals(alias)).orElse(false)).orElse(true);
                }
                if (!valid) return PhaseResult.failure(Diagnostic.error(
                        CompilerDiagnosticCodes.MODULE_INVALID_CONFIGURATION, sourceSpan(),
                        "reload requires exactly one namespace import of its selected target and no source forms"));
            }
            return request.reloadModule()
                    .map(reload -> ModuleGraphDiscovery.discoverSession(
                            syntax, snapshot, request.sourceConfiguration(), request.snapshot(), reload))
                    .orElseGet(() -> ModuleGraphDiscovery.discoverSession(
                            syntax, snapshot, request.sourceConfiguration(), request.snapshot()));
        }

        private IdentityReservations reserveModuleIdentities(ModuleGraph graph) {
            IdentityAllocator.Allocation<GenerationId> generation = request.snapshot().allocator().allocateGeneration();
            IdentityAllocator allocator = generation.next();
            Set<ModuleId> reloadFreshModules = reloadFreshModules(graph);
            LinkedHashMap<ModuleId, IdentityPair> identities = new LinkedHashMap<>();
            for (ModuleGraph.Node node : graph.modules()) {
                boolean scratch = node.moduleId().equals(graph.rootModule());
                Optional<io.mindspice.lyra.compiler.source.LogicalModuleId> logical =
                        node.logicalModule();
                SessionModuleEnvironment.ModuleRecord previous = logical
                        .flatMap(value -> request.snapshot().moduleEnvironment().module(value))
                        .orElse(null);
                boolean reloadReplacement = reloadFreshModules.contains(node.moduleId())
                        && previous != null
                        && previous.ownership() == SessionModuleEnvironment.Ownership.SESSION;
                boolean sameProducer = !scratch
                        && !reloadReplacement
                        && previous != null
                        && previous.moduleId().equals(node.moduleId())
                        && previous.revision().equals(node.revision())
                        && previous.source().sha256().equals(node.snapshot().sha256());
                if (sameProducer) {
                    identities.put(node.moduleId(), new IdentityPair(
                            previous.generationId(), previous.producerId()));
                    continue;
                }
                IdentityAllocator.Allocation<ProducerId> producer = allocator.allocateProducer();
                allocator = producer.next();
                identities.put(node.moduleId(), new IdentityPair(
                        generation.id(), producer.id()));
            }
            return new IdentityReservations(allocator, Map.copyOf(identities));
        }

        private Set<ModuleId> reloadFreshModules(ModuleGraph graph) {
            if (request.reloadModule().isEmpty()) {
                return Set.of();
            }
            Set<ModuleId> visited = new HashSet<>();
            Set<ModuleId> fresh = new HashSet<>();
            ArrayDeque<ModuleId> pending = new ArrayDeque<>();
            pending.add(graph.rootModule());
            while (!pending.isEmpty()) {
                ModuleId current = pending.removeFirst();
                if (!visited.add(current)) continue;
                for (var edge : graph.importsFrom(current)) {
                    ModuleId target = edge.target();
                    Optional<io.mindspice.lyra.compiler.source.LogicalModuleId> logical =
                            graph.module(target).flatMap(ModuleGraph.Node::logicalModule);
                    boolean sessionOwned = logical.flatMap(value ->
                                    request.snapshot().moduleEnvironment().module(value))
                            .map(record -> record.ownership()
                                    == SessionModuleEnvironment.Ownership.SESSION)
                            .orElse(true);
                    if (!sessionOwned || !fresh.add(target)) continue;
                    pending.addLast(target);
                }
            }
            return Set.copyOf(fresh);
        }

        private SessionEnvironmentData buildEnvironment(
                ModuleGraph graph,
                ResolvedSemanticGraph resolved,
                TypedSemanticGraph typed,
                IdentityReservations reservations) {
            IdentityAllocator allocator = typed.allocator();
            Set<ModuleId> reloadFreshModules = reloadFreshModules(graph);
            Map<io.mindspice.lyra.compiler.source.LogicalModuleId,
                    SessionModuleEnvironment.ModuleRecord> retained =
                    new LinkedHashMap<>(request.snapshot().moduleEnvironment().modulesByLogical());
            LinkedHashMap<io.mindspice.lyra.compiler.source.LogicalModuleId, PinnedModule> nextPins =
                    new LinkedHashMap<>(request.snapshot().pinnedModules());
            ArrayList<SessionExecutionPlan.ModuleWork> work = new ArrayList<>();

            for (ModuleGraph.Node node : graph.modules()) {
                boolean scratch = node.moduleId().equals(graph.rootModule());
                Optional<io.mindspice.lyra.compiler.source.LogicalModuleId> logical =
                        node.logicalModule();
                SessionModuleEnvironment.ModuleRecord previous = logical
                        .flatMap(value -> Optional.ofNullable(retained.get(value)))
                        .orElse(null);
                boolean reloadReplacement = reloadFreshModules.contains(node.moduleId())
                        && previous != null
                        && previous.ownership() == SessionModuleEnvironment.Ownership.SESSION;
                boolean sameProducer = !scratch
                        && !reloadReplacement
                        && previous != null
                        && previous.moduleId().equals(node.moduleId())
                        && previous.revision().equals(node.revision())
                        && previous.source().sha256().equals(node.snapshot().sha256());

                IdentityPair identity = reservations.identities().get(node.moduleId());
                if (identity == null) {
                    throw new LyraCompilerBugException(
                            "module identity reservation does not cover discovered module");
                }
                GenerationId generation = identity.generation();
                ProducerId producer = identity.producer();
                SessionModuleEnvironment.Ownership ownership = sameProducer
                        ? previous.ownership()
                        : logical.filter(io.mindspice.lyra.compiler.source.LogicalModuleId::isStdIo)
                        .isPresent()
                        ? SessionModuleEnvironment.Ownership.INTRINSIC
                        : SessionModuleEnvironment.Ownership.SESSION;

                ResolvedModule semanticModule = resolved.module(node.moduleId()).orElseThrow();
                List<DeclarationId> initializers = semanticModule.declarations().stream()
                        .map(resolved::declaration)
                        .flatMap(Optional::stream)
                        .filter(value -> value.kind() == DeclarationKind.LET)
                        .filter(value -> value.scopeId().equals(semanticModule.rootScope()))
                        .map(ResolvedDeclaration::id)
                        .toList();
                SessionExecutionPlan.WorkKind kind = scratch
                        ? SessionExecutionPlan.WorkKind.NEW
                        : ownership == SessionModuleEnvironment.Ownership.INTRINSIC
                        || (sameProducer && ownership == SessionModuleEnvironment.Ownership.APPLICATION)
                        ? SessionExecutionPlan.WorkKind.BORROWED
                        : sameProducer
                        ? SessionExecutionPlan.WorkKind.REUSED
                        : SessionExecutionPlan.WorkKind.NEW;
                work.add(new SessionExecutionPlan.ModuleWork(
                        node.moduleId(), logical, generation, producer, kind, scratch,
                        kind == SessionExecutionPlan.WorkKind.NEW ? initializers : List.of()));

                if (!scratch) {
                    if (!sameProducer) {
                        ResolvedModule semantic = resolved.module(node.moduleId()).orElseThrow();
                        TypedModule typedModule = typed.module(node.moduleId()).orElseThrow();
                        List<io.mindspice.lyra.compiler.source.LogicalModuleId> dependencies =
                                graph.importsFrom(node.moduleId()).stream()
                                        .map(ModuleGraph.Edge::logicalTarget)
                                        .distinct()
                                        .sorted()
                                        .toList();
                        SessionModuleEnvironment.ModuleRecord record =
                                new SessionModuleEnvironment.ModuleRecord(
                                        logical.orElseThrow(),
                                        node.moduleId(),
                                        node.snapshot(),
                                        node.revision(),
                                        generation,
                                        producer,
                                        ownership,
                                        semantic,
                                        typedModule,
                                        semantic.exports(),
                                        semantic.imports(),
                                        dependencies,
                                        initializers,
                                        typed.semanticFlowFacts().finalState(node.moduleId()),
                                        Optional.ofNullable(typed.semanticFlowFacts().attemptedStates()
                                                .get(node.moduleId())),
                                        typed.semanticFlowFacts().callableSummaries(), typed,
                                        request.sourceConfiguration().sourceRoots(),
                                        request.sourceConfiguration().revisionOptions().values());
                        retained.put(logical.orElseThrow(), record);
                    }
                    if (logical.isPresent()
                            && !logical.orElseThrow().isStdIo()) {
                        if (!sameProducer) nextPins.put(logical.orElseThrow(), new PinnedModule(
                                logical.orElseThrow(), node.snapshot(), node.revision(),
                                request.sourceConfiguration().revisionOptions()));
                    }
                }
            }

            LinkedHashMap<SourceId, SourceSnapshot> snapshots = new LinkedHashMap<>();
            for (SourceSnapshot source : request.snapshot().moduleEnvironment().sourceInventory()) {
                snapshots.put(source.sourceId(), source);
            }
            for (ModuleGraph.Node node : graph.modules()) {
                snapshots.put(node.snapshot().sourceId(), node.snapshot());
            }
            LinkedHashMap<SourceId, io.mindspice.lyra.compiler.source.ResolvedSource> inputs =
                    new LinkedHashMap<>();
            for (io.mindspice.lyra.compiler.source.ResolvedSource input :
                    request.snapshot().moduleEnvironment().resolvedInputs()) {
                inputs.put(input.sourceId(), input);
            }
            for (ModuleGraph.Node node : graph.modules()) {
                node.logicalModule().ifPresent(logical -> inputs.put(node.sourceId(),
                        io.mindspice.lyra.compiler.source.ResolvedSource.fromSnapshot(
                                logical, node.snapshot())));
            }
            LinkedHashMap<ProducerId, SessionModuleEnvironment.ModuleRecord> producers = new LinkedHashMap<>();
            request.snapshot().moduleEnvironment().producers().forEach(record -> producers.put(record.producerId(), record));
            retained.values().forEach(record -> producers.put(record.producerId(), record));
            SessionModuleEnvironment environment = new SessionModuleEnvironment(
                    new ArrayList<>(retained.values()),
                    request.sourceConfiguration().sourceRoots(),
                    request.sourceConfiguration().revisionOptions().values(),
                    new ArrayList<>(snapshots.values()),
                    new ArrayList<>(inputs.values()),
                    Optional.of(graph),
                    Optional.of(resolved),
                    Optional.of(typed),
                    Optional.of(typed.semanticFlowFacts()), new ArrayList<>(producers.values()));

            ResolvedModule root = resolved.module(graph.rootModule()).orElseThrow();
            ArrayList<SessionImport> imports = new ArrayList<>();
            for (var binding : root.imports()) {
                imports.add(SessionImport.from(binding, environment));
            }
            List<ModuleId> initializationOrder = typed.initializationOrder().stream()
                    .filter(module -> work.stream().anyMatch(value -> value.moduleId().equals(module)
                            && value.isNew() && !value.scratch()))
                    .toList();
            SessionExecutionPlan plan = new SessionExecutionPlan(
                    work, initializationOrder, typed);
            return new SessionEnvironmentData(
                    allocator, environment, Map.copyOf(nextPins), imports, plan);
        }

        private StagedNamespace stageNamespace(
                ModuleGraph graph,
                ResolvedSemanticGraph resolved,
                TypedSemanticGraph typed,
                SessionEnvironmentData environment) {
            var root = graph.rootModule();
            var rootSemantic = resolved.module(root).orElseThrow();
            for (ResolvedDeclaration declaration : resolved.declarations()) {
                if (!declaration.moduleId().equals(root)
                        || !declaration.scopeId().equals(rootSemantic.rootScope())
                        || declaration.kind() == DeclarationKind.EXTERNAL
                        || declaration.kind() == DeclarationKind.IMPORT_MODULE
                        || declaration.kind() == DeclarationKind.IMPORT_VALUE) {
                    continue;
                }
                ExternalBinding previous = request.snapshot().bindings().get(declaration.name());
                boolean protectedBinding = previous != null
                        && previous.visibility() != ExternalBinding.Visibility.PRIVATE;
                if (protectedBinding || request.snapshot().imports().containsKey(declaration.name())) {
                    return StagedNamespace.failure(Diagnostic.error(
                            CompilerDiagnosticCodes.SESSION_NAME_CONFLICT,
                            declaration.nameSpan(),
                            "session name cannot be redeclared: " + declaration.name()));
                }
            }

            LinkedHashMap<String, ExternalBinding> nextBindings =
                    new LinkedHashMap<>(request.snapshot().bindings());
            for (ResolvedDeclaration declaration : resolved.declarations()) {
                if (!declaration.moduleId().equals(root)
                        || !declaration.scopeId().equals(rootSemantic.rootScope())
                        || declaration.kind() != DeclarationKind.LET) continue;
                var contract = typed.contract(declaration.id()).orElseThrow();
                boolean mutable = contract.isMutable();
                nextBindings.put(declaration.name(), new ExternalBinding(
                        declaration.name(), declaration.id(), contract,
                        declaration.isPublic() ? ExternalBinding.Visibility.PUBLIC : ExternalBinding.Visibility.PRIVATE,
                        mutable ? ExternalBinding.AssignmentAuthority.ALL : ExternalBinding.AssignmentAuthority.NONE,
                        mutable ? java.util.Optional.of(StorageIdentity.forDeclaration(declaration.id()))
                                : java.util.Optional.empty(), request.source().origin()));
            }

            LinkedHashMap<String, SessionImport> nextImports =
                    new LinkedHashMap<>(request.snapshot().imports());
            ArrayList<SessionImport> publishedImports = new ArrayList<>();
            for (SessionImport imported : environment.imports()) {
                if (request.reloadImportAlias().map(imported.name()::equals).orElse(false)) {
                    continue;
                }
                SessionImport candidate = rebindReloadNamespace(imported, environment.environment());
                SessionImport previous = nextImports.get(candidate.name());
                if (previous != null && !equivalentImport(previous, candidate)
                        && !isAllowedReloadReplacement(previous, candidate)) {
                    return StagedNamespace.failure(Diagnostic.error(
                            CompilerDiagnosticCodes.SESSION_NAME_CONFLICT,
                            sourceSpan(),
                            "session import cannot be rebound: " + candidate.name()));
                }
                if (previous == null || isAllowedReloadReplacement(previous, candidate)) {
                    nextImports.put(candidate.name(), candidate);
                }
                publishedImports.add(candidate);
            }

            List<DeclarationId> declarations = resolved.declarations().stream()
                    .filter(value -> value.moduleId().equals(root))
                    .filter(value -> value.scopeId().equals(rootSemantic.rootScope()))
                    .filter(value -> value.kind() == DeclarationKind.LET)
                    .map(ResolvedDeclaration::id)
                    .toList();
            try {
                return StagedNamespace.success(request.snapshot().nextRevision(
                        nextBindings,
                        nextImports,
                        environment.pinnedModules(),
                        environment.allocator(),
                        environment.environment()), declarations,
                        publishedImports);
            } catch (IllegalArgumentException | IllegalStateException failure) {
                throw new LyraCompilerBugException(
                        "session namespace staging violated an immutable contract", failure);
            }
        }

        private Diagnostic validateConfiguration() {
            if (request.javaTarget() != 25) {
                return Diagnostic.error(CompilerDiagnosticCodes.MODULE_INVALID_CONFIGURATION,
                        sourceSpan(), "Lyra artifacts require Java target 25");
            }
            if (!validJavaPackage(request.javaBasePackage())) {
                return Diagnostic.error(CompilerDiagnosticCodes.MODULE_INVALID_CONFIGURATION,
                        sourceSpan(), "invalid Java base package: " + request.javaBasePackage());
            }
            if (request.reloadModule().isPresent()) {
                var logical = request.reloadModule().orElseThrow();
                var record = request.snapshot().moduleEnvironment().module(logical);
                if (record.isEmpty()) {
                    return Diagnostic.error(CompilerDiagnosticCodes.RESOLVE_MISSING_MODULE,
                            sourceSpan(), "reload target is not a retained session module: " + logical);
                }
                if (record.orElseThrow().ownership()
                        != SessionModuleEnvironment.Ownership.SESSION) {
                    return Diagnostic.error(CompilerDiagnosticCodes.SESSION_EXTERNAL_BINDING_UNSUPPORTED,
                            sourceSpan(), "application-owned or intrinsic modules cannot be reloaded: " + logical);
                }
            }
            return null;
        }

        private SessionImport rebindReloadNamespace(
                SessionImport imported, SessionModuleEnvironment environment) {
            if (request.reloadModule().isEmpty() || !imported.isModuleNamespace()) {
                return imported;
            }
            var replacement = environment.module(imported.logicalModule()).orElse(null);
            if (replacement == null) {
                return imported;
            }
            var contract = io.mindspice.lyra.compiler.session.SessionModuleContract.from(
                    environment, replacement.moduleId());
            boolean alreadyCurrent = imported.moduleContract()
                    .map(previous -> previous.producer().generationId()
                            .equals(contract.producer().generationId())
                            && previous.producer().producerId().equals(contract.producer().producerId()))
                    .orElse(false);
            if (alreadyCurrent) {
                return imported;
            }
            return new SessionImport(
                    imported.name(), imported.logicalModule(), replacement.moduleId(),
                    contract.producer().revision(), imported.kind(), imported.importedName(),
                    imported.aliasName(), imported.targetDeclaration(), imported.targetExport(),
                    imported.reExport(), Optional.of(contract));
        }

        private boolean isAllowedReloadReplacement(SessionImport previous, SessionImport candidate) {
            if (request.reloadModule().isEmpty() || !candidate.isModuleNamespace()
                    || !previous.isModuleNamespace()
                    || !previous.logicalModule().equals(candidate.logicalModule())) {
                return false;
            }
            if (previous.moduleContract().isEmpty() || candidate.moduleContract().isEmpty()) {
                return false;
            }
            var oldProducer = previous.moduleContract().orElseThrow().producer();
            var newProducer = candidate.moduleContract().orElseThrow().producer();
            return !oldProducer.generationId().equals(newProducer.generationId())
                    || !oldProducer.producerId().equals(newProducer.producerId());
        }

        private static boolean equivalentImport(SessionImport left, SessionImport right) {
            return left.name().equals(right.name())
                    && left.logicalModule().equals(right.logicalModule())
                    && left.moduleId().equals(right.moduleId())
                    && left.revision().equals(right.revision())
                    && left.kind() == right.kind()
                    && left.importedName().equals(right.importedName())
                    && left.aliasName().equals(right.aliasName())
                    && left.targetDeclaration().equals(right.targetDeclaration())
                    && left.targetExport().equals(right.targetExport())
                    && left.moduleContract().equals(right.moduleContract())
                    && left.reExport() == right.reExport();
        }

        private SourceSpan sourceSpan() {
            return SourceSpan.at(request.sourceId(), 0);
        }

        private SessionCompileResult fail(Diagnostic diagnostic) {
            diagnostics.add(Objects.requireNonNull(diagnostic, "diagnostic"));
            return failure();
        }

        private SessionCompileResult failure() {
            return new SessionCompileResult.Failure(
                    request.baseRevision(), mapDiagnostics(diagnostics));
        }

        private <T extends ImmutablePhaseArtifact> T phase(PhaseResult<T> result) {
            if (result instanceof PhaseResult.Success<T> success) {
                diagnostics.addAll(success.diagnostics());
                return success.value();
            }
            diagnostics.addAll(result.diagnostics());
            return null;
        }

        private <T extends ImmutablePhaseArtifact> T sourcePhase(PhaseResult<T> result) {
            if (result instanceof PhaseResult.Success<T> success) {
                diagnostics.addAll(success.diagnostics());
                return success.value();
            }
            return null;
        }

        private List<Diagnostic> mapDiagnostics(List<Diagnostic> values) {
            var mapped = values.stream().map(value -> new Diagnostic(value.code(), value.severity(),
                    value.summary(), mapModuleSpan(value.primarySpan()), value.relatedSpans().stream()
                    .map(related -> new RelatedSpan(mapModuleSpan(related.span()), related.label())).toList())).toList();
            return LyraCompiler.mapSessionDiagnostics(mapped, request.source(), request.sourceId());
        }

        private SourceSpan mapModuleSpan(SourceSpan span) {
            return SourceSpan.of(sourceOrigins.getOrDefault(span.sourceId(), span.sourceId()),
                    span.startOffset(), span.endOffset());
        }

        private record IdentityPair(GenerationId generation, ProducerId producer) {
            private IdentityPair {
                Objects.requireNonNull(generation, "generation");
                Objects.requireNonNull(producer, "producer");
            }
        }

        private record IdentityReservations(
                IdentityAllocator allocator,
                Map<ModuleId, IdentityPair> identities) {
            private IdentityReservations {
                allocator = Objects.requireNonNull(allocator, "allocator");
                identities = Map.copyOf(Objects.requireNonNull(identities, "identities"));
            }
        }

        private record SessionEnvironmentData(
                IdentityAllocator allocator,
                SessionModuleEnvironment environment,
                Map<io.mindspice.lyra.compiler.source.LogicalModuleId, PinnedModule> pinnedModules,
                List<SessionImport> imports,
                SessionExecutionPlan plan) {
            private SessionEnvironmentData {
                allocator = Objects.requireNonNull(allocator, "allocator");
                environment = Objects.requireNonNull(environment, "environment");
                pinnedModules = Map.copyOf(Objects.requireNonNull(pinnedModules, "pinnedModules"));
                imports = List.copyOf(Objects.requireNonNull(imports, "imports"));
                plan = Objects.requireNonNull(plan, "plan");
            }
        }

        private record StagedNamespace(
                SessionSnapshot snapshot,
                List<DeclarationId> declarations,
                List<SessionImport> imports,
                Diagnostic diagnostic) {
            private StagedNamespace {
                if (snapshot == null && diagnostic == null) {
                    throw new IllegalArgumentException(
                            "staged namespace needs a snapshot or diagnostic");
                }
                imports = List.copyOf(Objects.requireNonNull(imports, "imports"));
                if (snapshot != null && diagnostic != null) {
                    throw new IllegalArgumentException(
                            "staged namespace cannot contain both a snapshot and diagnostic");
                }
                declarations = List.copyOf(Objects.requireNonNull(declarations, "declarations"));
            }

            private static StagedNamespace success(
                    SessionSnapshot snapshot,
                    List<DeclarationId> declarations,
                    List<SessionImport> imports) {
                return new StagedNamespace(
                        Objects.requireNonNull(snapshot, "snapshot"), declarations, imports, null);
            }

            private static StagedNamespace failure(Diagnostic diagnostic) {
                return new StagedNamespace(null, List.of(), List.of(),
                        Objects.requireNonNull(diagnostic, "diagnostic"));
            }
        }
    }

    private static final class BuiltCompiledArtifact implements CompiledArtifact {
        private final JvmBytecodeArtifact bytecode;
        private final boolean includeSources;
        private final ArtifactAssembly classesAssembly;

        private BuiltCompiledArtifact(JvmBytecodeArtifact bytecode, boolean includeSources,
                                      ArtifactAssembly classesAssembly) {
            this.bytecode = Objects.requireNonNull(bytecode, "bytecode");
            this.includeSources = includeSources;
            this.classesAssembly = Objects.requireNonNull(classesAssembly, "classesAssembly");
        }

        @Override
        public ArtifactMetadata metadata() {
            return classesAssembly.metadata();
        }

        @Override
        public Map<String, byte[]> entries() {
            return classesAssembly.entries();
        }

        @Override
        public Map<String, byte[]> classes() {
            return classesAssembly.classes();
        }

        @Override
        public void writeClasses(Path output, WriteOptions options) throws java.io.IOException {
            Objects.requireNonNull(options, "options");
            classesAssembly.writeClasses(output, options.force());
        }

        @Override
        public void writeJar(Path output, JarMode mode, WriteOptions options)
                throws java.io.IOException {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(options, "options");
            ArtifactAssembly assembly = ArtifactAssembly.assemble(
                    bytecode, mode.packagingMode(), includeSources);
            assembly.writeJar(output, options.force());
        }
    }

    private static PhysicalSourceKey memoryPhysicalKey(SourceId sourceId, String text) {
        String identity = sourceId.toString() + "\u0000" + text;
        String hash;
        try {
            hash = java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new ExceptionInInitializerError(failure);
        }
        return PhysicalSourceKey.uri(URI.create("memory:lyra/" + hash));
    }

    private static List<Diagnostic> mapSessionDiagnostics(
            List<Diagnostic> values, EvaluationSource source, SourceId compilerSourceId) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(compilerSourceId, "compilerSourceId");
        return values.stream()
                .map(value -> mapSessionDiagnostic(value, source, compilerSourceId))
                .toList();
    }

    private static Diagnostic mapSessionDiagnostic(
            Diagnostic diagnostic, EvaluationSource source, SourceId compilerSourceId) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        SourceSpan primary = mapSessionSpan(
                diagnostic.primarySpan(), source, compilerSourceId);
        List<RelatedSpan> related = diagnostic.relatedSpans().stream()
                .map(value -> new RelatedSpan(
                        mapSessionSpan(value.span(), source, compilerSourceId), value.label()))
                .toList();
        return new Diagnostic(
                diagnostic.code(), diagnostic.severity(), diagnostic.summary(), primary, related);
    }

    private static SourceSpan mapSessionSpan(
            SourceSpan span, EvaluationSource source, SourceId compilerSourceId) {
        Objects.requireNonNull(span, "span");
        if (!span.sourceId().equals(compilerSourceId)) {
            return span;
        }
        if (span.endOffset() > source.text().length()) {
            throw new LyraCompilerBugException(
                    "session diagnostic span exceeds submitted source: " + span);
        }
        return source.origin().map(span, compilerSourceId);
    }

    private static boolean validJavaPackage(String value) {
        if (value == null || value.isBlank() || value.startsWith(".") || value.endsWith(".")
                || value.contains("/") || value.contains("\\")
                || value.equals("io.mindspice.lyra.runtime")
                || value.startsWith("io.mindspice.lyra.runtime.")
                || value.equals("java") || value.startsWith("java.")) {
            return false;
        }
        for (String part : value.split("\\.", -1)) {
            if (part.isEmpty() || isJavaKeyword(part)
                    || !Character.isJavaIdentifierStart(part.charAt(0))) {
                return false;
            }
            for (int index = 1; index < part.length(); index++) {
                if (!Character.isJavaIdentifierPart(part.charAt(index))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isJavaKeyword(String value) {
        return switch (value) {
            case "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
                    "class", "const", "continue", "default", "do", "double", "else", "enum",
                    "extends", "final", "finally", "float", "for", "goto", "if", "implements",
                    "import", "instanceof", "int", "interface", "long", "native", "new", "package",
                    "private", "protected", "public", "return", "short", "static", "strictfp",
                    "super", "switch", "synchronized", "this", "throw", "throws", "transient",
                    "try", "void", "volatile", "while", "true", "false", "null", "_", "record",
                    "sealed", "permits", "non-sealed", "var", "yield", "module", "open", "opens",
                    "requires", "transitive", "exports", "to", "uses", "provides", "with", "when" -> true;
            default -> false;
        };
    }
}
